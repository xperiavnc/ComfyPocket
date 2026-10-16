package com.comfyui.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 16 Live Updates for generation progress.
 * - largeIcon restored (OEM often shows it top-right)
 * - "排队中" only before any progress this run; after progress, finishing must not look like queueing
 */
object GenerationLiveUpdate {

    private fun showPercent(context: Context): Boolean {
        return context.getSharedPreferences("comfy_prefs", Context.MODE_PRIVATE)
            .getBoolean("live_update_show_percent", true)
    }

    private const val CHANNEL_ID = "comfy_live_update_v2"
    const val NOTIFICATION_ID = 1002
    private const val DONE_NOTIFICATION_ID = 1003
    private const val FAIL_NOTIFICATION_ID = 1004

    private val wasActive = AtomicBoolean(false)
    private val hadProgressThisRun = AtomicBoolean(false)
    private val suppressUntilIdle = AtomicBoolean(false)
    /**
     * Set when the user taps 停止 (notification or in-app). Blocks KeepAliveService restart and
     * forbids replacing the FGS notification until the service has actually stopped.
     */
    private val userStopRequested = AtomicBoolean(false)
    /** Set when the current run ended with an error / interruption: idle shows failure, not "completed". */
    private val runFailedLabel = java.util.concurrent.atomic.AtomicReference<String?>(null)
    @Volatile
    private var lastCompletedAt = 0L

    /**
     * Marks the current run as failed/interrupted. If the "completed" notification was already shown a
     * moment ago (completion signal raced ahead of the failure), it is replaced by the failure one.
     */
    fun markRunFailed(context: Context, label: String = "绘图失败") {
        if (wasActive.get()) {
            runFailedLabel.set(label)
        } else if (System.currentTimeMillis() - lastCompletedAt < 15_000L) {
            notifyFailedThenDismiss(context, label)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.deleteNotificationChannel("comfy_keep_alive")
            manager.deleteNotificationChannel("comfy_live_update")
        } catch (_: Throwable) {
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "生成进度与流体云",
                NotificationManager.IMPORTANCE_HIGH
    ).apply {
                description = "工作流运行时的实时进度（流体云 / 焦点通知 / 灵动胶囊）"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
    )
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, LiveUpdateActionReceiver::class.java).apply {
            action = LiveUpdateActionReceiver.ACTION_STOP
        }
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    }

    private fun applyLargeIcon(builder: NotificationCompat.Builder, context: Context) {
        try {
            builder.setLargeIcon(
                IconCompat.createWithResource(context, R.mipmap.ic_launcher).toIcon(context)
    )
        } catch (_: Throwable) {
        }
    }

    private fun createCapsuleExtras(
        chip: String,
        title: String,
        text: String,
        isCompleted: Boolean = false
    ): Bundle {
        return Bundle().apply {
            // Android 15/16 standard
            putBoolean("android.requestPromotedOngoing", !isCompleted)
            // ColorOS / Oplus Fluid Cloud (流体云)
            putBoolean("com.oplus.notification.capsule", true)
            putBoolean("oplus.capsule", true)
            putString("oplus.notification.capsule.status", chip)
            // Xiaomi HyperOS / MIUI Focus Notification (焦点通知 / 灵动胶囊)
            putBoolean("miui.enableFloat", true)
            putBoolean("miui.showAction", !isCompleted)
            putBoolean("miui.enableKeyguardNotification", true)
            putString("miui.focus.type", if (isCompleted) "finish" else "progress")
            // Huawei / Honor
            putBoolean("hw.capsule", true)
        }
    }

    fun isBusy(
        progress: Float,
        queueSize: Int,
        isExecuting: Boolean = false,
        isGenerating: Boolean = false
    ): Boolean {
        if (isGenerating) return true
        val p = progress.coerceIn(0f, 1f)
        return queueSize > 0 || isExecuting || (p > 0.02f && p < 0.999f)
    }

    /**
     * RunningHub official-task-API state ("排队中 · 12 秒", "运行中 · 40 秒"…) while cloud tasks are tracked by
     * polling (no WebSocket progress). Null = not a cloud official run.
     */
    @Volatile
    var cloudStatusText: String? = null

    fun buildProgressNotification(
        context: Context,
        progress: Float = 0f,
        queueSize: Int = 1,
        isExecuting: Boolean = true
    ): Notification {
        val app = context.applicationContext
        ensureChannel(app)

        val p = progress.coerceIn(0f, 1f)
        val pct = (p * 100f).toInt().coerceIn(0, 100)
        val showPct = showPercent(app)
        // True queue-wait: have queue items but never saw progress this run.
        val waitingInQueue = queueSize > 0 && p <= 0.02f && !hadProgressThisRun.get()
        val finishing = hadProgressThisRun.get() && (p <= 0.02f || p >= 0.999f)
        val cloud = cloudStatusText
        val cloudMode = cloud != null && p <= 0.02f && !hadProgressThisRun.get()
        val title = when {
            cloudMode -> if (cloud.orEmpty().startsWith("排队")) "云端排队" else "云端任务"
            waitingInQueue -> "排队"
            finishing -> "收尾"
            else -> "绘图中"
        }
        val text = when {
            cloudMode -> cloud.orEmpty()
            waitingInQueue -> if (queueSize > 1) "剩余 $queueSize 个任务" else "等待开始"
            finishing -> "即将完成"
            pct > 0 -> if (queueSize > 1) "$pct% · 剩余 $queueSize" else "$pct%"
            else -> if (queueSize > 1) "启动中 · 剩余 $queueSize" else "准备中"
        }
        val chip = when {
            cloudMode -> "云端"
            waitingInQueue -> "排队"
            finishing -> "收尾"
            showPct -> if (pct > 0) "$pct%" else "绘图"
            else -> "绘图"
        }

        val capsuleExtras = createCapsuleExtras(chip, title, text)

        if (Build.VERSION.SDK_INT >= 36) {
            @Suppress("NewApi")
            val style = Notification.ProgressStyle()
                .setProgress(if (waitingInQueue) 0 else pct.coerceIn(0, 100))
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))

            @Suppress("NewApi")
            val platform = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(contentIntent(app))
                .setStyle(style)
                .setShortCriticalText(chip)
                .setLargeIcon(Icon.createWithResource(app, R.mipmap.ic_launcher))
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(app, android.R.drawable.ic_menu_close_clear_cancel),
                        "停止",
                        stopIntent(app)
    ).build()
    )
                .addExtras(capsuleExtras)

            try {
                val m = platform.javaClass.getMethod(
                    "setRequestPromotedOngoing",
                    Boolean::class.javaPrimitiveType
    )
                m.invoke(platform, true)
            } catch (_: Throwable) {
            }

            return platform.build()
        }

        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "停止",
            stopIntent(app)
    ).build()

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(chip)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(app))
            .setProgress(100, if (waitingInQueue) 0 else pct, waitingInQueue)
            .addAction(stopAction)
            .addExtras(capsuleExtras)
            .setDefaults(0)
            .setSound(null)
        applyLargeIcon(builder, app)

        return builder.build()
    }

    fun onExecutionStart() {
        hadProgressThisRun.set(false)
        cancelScheduledDismiss()
    }

    fun isUserStopRequested(): Boolean = userStopRequested.get()

    /** Call at the very start of a user-initiated stop, before touching the FGS notification. */
    fun beginUserStop() {
        userStopRequested.set(true)
        cloudStatusText = null
        suppressUntilIdle.set(true)
        wasActive.set(false)
        hadProgressThisRun.set(false)
        runFailedLabel.set(null)
        cancelScheduledDismiss()
    }

    /** New user-initiated run (or fully idle): FGS may start again. */
    fun noteUserStartedRun() {
        userStopRequested.set(false)
        suppressUntilIdle.set(false)
        KeepAliveService.clearStopRequest()
    }

    /**
     * Drop the live-update notification without "绘图完成" / "终止".
     * Used when switching site or API key: the cloud task is still running, we just stopped watching it.
     */
    fun dismissQuietly(context: Context) {
        wasActive.set(false)
        hadProgressThisRun.set(false)
        runFailedLabel.set(null)
        cloudStatusText = null
        suppressUntilIdle.set(true)
        cancelScheduledDismiss()
        cancelLive(context.applicationContext)
    }

    fun update(
        context: Context,
        progress: Float,
        queueSize: Int,
        isExecuting: Boolean = false,
        isGenerating: Boolean = false
    ): Boolean {
        val app = context.applicationContext
        val p = progress.coerceIn(0f, 1f)
        if (p > 0.02f) {
            hadProgressThisRun.set(true)
        }

        // Idle only when nothing is queued, not executing a node, not generating, and not mid-progress.
        // Treat ~100% with empty queue and not executing as finished (not busy).
        val busy = isBusy(progress, queueSize, isExecuting, isGenerating)
        if (userStopRequested.get()) {
            // Never post onto the FGS id while a user stop is in flight (OEM Live Update crash).
            return false
        }
        if (busy) {
            cancelScheduledDismiss()
        }

        if (suppressUntilIdle.get()) {
            if (!busy) {
                suppressUntilIdle.set(false)
                hadProgressThisRun.set(false)
            } else {
                return true
            }
        }

        if (!busy) {
            hadProgressThisRun.set(false)
            if (wasActive.getAndSet(false)) {
                val failed = runFailedLabel.getAndSet(null)
                if (failed != null) notifyFailedThenDismiss(app, failed) else notifyCompleted(app)
            } else {
                cancelLive(app)
            }
            return false
        }

        if (!wasActive.getAndSet(true)) {
            // A new run starts: drop any stale failure mark from a previous run.
            runFailedLabel.set(null)
        }
        val notification = buildProgressNotification(app, progress, queueSize, isExecuting)
        notifySafe(app, NOTIFICATION_ID, notification)
        return true
    }

    private fun notifyCompleted(context: Context) {
        val app = context.applicationContext
        lastCompletedAt = System.currentTimeMillis()
        cancelScheduledDismiss()
        ensureChannel(app)

        val title = "绘图完成"
        val text = "绘图完成"
        val chip = "完成"
        val capsuleExtras = createCapsuleExtras(chip, title, text, isCompleted = true)

        if (Build.VERSION.SDK_INT >= 36) {
            @Suppress("NewApi")
            val platform = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(contentIntent(app))
                .setShortCriticalText(chip)
                .setLargeIcon(Icon.createWithResource(app, R.mipmap.ic_launcher))
                .addExtras(capsuleExtras)
                .setTimeoutAfter(6_000L)

            try {
                val m = platform.javaClass.getMethod(
                    "setRequestPromotedOngoing",
                    Boolean::class.javaPrimitiveType
    )
                m.invoke(platform, false)
            } catch (_: Throwable) {
            }

            notifySafe(app, NOTIFICATION_ID, platform.build())
            val r = Runnable {
                NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
                dismissRunnable = null
            }
            dismissRunnable = r
            handler.postDelayed(r, 6_000L)
            return
        }

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(chip)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(app))
            .addExtras(capsuleExtras)
            .setTimeoutAfter(6_000L)
            .setDefaults(0)
            .setSound(null)
        applyLargeIcon(builder, app)
        notifySafe(app, NOTIFICATION_ID, builder.build())

        val r = Runnable {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            dismissRunnable = null
        }
        dismissRunnable = r
        handler.postDelayed(r, 6_000L)
    }

    fun notifyFailedThenDismiss(context: Context, label: String = "绘图终止") {
        val app = context.applicationContext
        runFailedLabel.set(null)
        lastCompletedAt = 0L
        suppressUntilIdle.set(true)
        wasActive.set(false)
        hadProgressThisRun.set(false)
        cancelScheduledDismiss()
        ensureChannel(app)

        val title = label
        val text = label
        val chip = if (label.contains("失败")) "失败" else "终止"
        val capsuleExtras = createCapsuleExtras(chip, title, text, isCompleted = true)
        // Never post a non-ongoing notification onto the FGS id (1002): replacing the
        // foreground-service notification while KeepAliveService is still started crashes
        // ColorOS 流体云 / HyperOS 焦点通知 / Android 14+ FGS checks. The service removes
        // 1002 via stopForeground(REMOVE); this uses a separate id.

        fun postCompat() {
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(chip)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent(app))
                .addExtras(capsuleExtras)
                .setTimeoutAfter(3_000L)
                .setDefaults(0)
                .setSound(null)
            applyLargeIcon(builder, app)
            notifySafe(app, FAIL_NOTIFICATION_ID, builder.build())
        }

        try {
            if (Build.VERSION.SDK_INT >= 36) {
                @Suppress("NewApi")
                val platform = Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setContentIntent(contentIntent(app))
                    .setShortCriticalText(chip)
                    .setLargeIcon(Icon.createWithResource(app, R.mipmap.ic_launcher))
                    .addExtras(capsuleExtras)
                    .setTimeoutAfter(3_000L)
                try {
                    val m = platform.javaClass.getMethod(
                        "setRequestPromotedOngoing",
                        Boolean::class.javaPrimitiveType
    )
                    m.invoke(platform, false)
                } catch (_: Throwable) {
                }
                notifySafe(app, FAIL_NOTIFICATION_ID, platform.build())
            } else {
                postCompat()
            }
        } catch (_: Throwable) {
            runCatching { postCompat() }
        }

        val r = Runnable {
            NotificationManagerCompat.from(app).cancel(FAIL_NOTIFICATION_ID)
            dismissRunnable = null
        }
        dismissRunnable = r
        handler.postDelayed(r, 3_000L)
    }

    private fun cancelScheduledDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
    }

    fun cancelLiveIfNotDismissing(context: Context) {
        if (dismissRunnable == null) {
            cancelLive(context)
        }
    }

    fun cancelLive(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notifySafe(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: Throwable) {
        }
    }

    fun cancel(context: Context) {
        cancelScheduledDismiss()
        wasActive.set(false)
        hadProgressThisRun.set(false)
        suppressUntilIdle.set(false)
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(NOTIFICATION_ID)
        nm.cancel(DONE_NOTIFICATION_ID)
        nm.cancel(FAIL_NOTIFICATION_ID)
    }
}

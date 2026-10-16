package com.comfyui.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages system download/save notifications for gallery media.
 */
object GalleryDownloadNotification {

    private const val CHANNEL_ID = "comfy_gallery_download"
    private const val CHANNEL_NAME = "画廊保存与下载"
    private const val CHANNEL_DESC = "保存图片或视频至系统相册时的进度与完成通知"

    private val idCounter = AtomicInteger(3000)

    fun nextNotificationId(): Int = idCounter.incrementAndGet()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
            description = CHANNEL_DESC
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun checkPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    private fun notifySafe(context: Context, id: Int, notification: android.app.Notification) {
        if (!checkPermission(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun appIntent(context: Context): PendingIntent {
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

    private fun viewMediaIntent(context: Context, id: Int, uri: Uri, mimeType: String?): PendingIntent {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
        } catch (_: Exception) {
            appIntent(context)
        }
    }

    fun notifyProgress(context: Context, notificationId: Int, filename: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在保存到相册")
            .setContentText(filename)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(appIntent(context))
            .build()

        notifySafe(context, notificationId, notification)
    }

    fun notifySuccess(
        context: Context,
        notificationId: Int,
        filename: String,
        uri: Uri?,
        mimeType: String?
    ) {
        ensureChannel(context)
        val clickIntent = if (uri != null) {
            viewMediaIntent(context, notificationId, uri, mimeType)
        } else {
            appIntent(context)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("已保存到相册")
            .setContentText(filename)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(clickIntent)
            .build()

        notifySafe(context, notificationId, notification)
    }

    fun notifyFailure(context: Context, notificationId: Int, filename: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("保存失败")
            .setContentText(filename)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(appIntent(context))
            .build()

        notifySafe(context, notificationId, notification)
    }

    fun notifyBatchProgress(
        context: Context,
        notificationId: Int,
        total: Int,
        current: Int,
        statusText: String
    ) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在批量保存到相册 ($current/$total)")
            .setContentText(statusText)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(appIntent(context))
            .build()

        notifySafe(context, notificationId, notification)
    }

    fun notifyBatchSuccess(
        context: Context,
        notificationId: Int,
        successCount: Int,
        total: Int
    ) {
        ensureChannel(context)
        val text = if (successCount == total) {
            "已成功保存全部 $successCount 个文件到系统相册"
        } else {
            "已保存 $successCount/$total 个文件到系统相册"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("批量保存完成")
            .setContentText(text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(appIntent(context))
            .build()

        notifySafe(context, notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {}
    }
}

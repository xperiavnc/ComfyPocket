package com.comfyui.mobile

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class GenerationSilentNotificationTest {
    private lateinit var app: Application
    private lateinit var manager: NotificationManager

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        GenerationLiveUpdate.cancel(app)
        GenerationLiveUpdate.noteUserStartedRun()
        GenerationLiveUpdate.onExecutionStart()
    }

    @After fun close() {
        GenerationLiveUpdate.cancel(app)
    }

    private fun assertSilent(notification: Notification) {
        val channel = manager.getNotificationChannel(notification.channelId)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertFalse(channel.canShowBadge())
        assertNull(notification.sound)
        assertTrue(notification.vibrate?.isNotEmpty() != true)
        assertEquals(0, notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertTrue(notification.priority <= Notification.PRIORITY_LOW)
        assertFalse(notification.extras.getBoolean("miui.enableFloat"))
        assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertFalse(notification.extras.getBoolean("com.oplus.notification.capsule"))
        assertFalse(notification.extras.getBoolean("hw.capsule"))
    }

    @Test fun existingHighPriorityChannelIsMigratedToSilentChannel() {
        manager.createNotificationChannel(NotificationChannel(
            "comfy_live_update_v2", "old", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) })
        val notification = GenerationLiveUpdate.buildProgressNotification(app, 0.4f)
        println("channel=${notification.channelId}, importance=${manager.getNotificationChannel(notification.channelId).importance}")
        assertNotEquals("comfy_live_update_v2", notification.channelId)
        assertSilent(notification)
        assertNotNull(notification.actions.singleOrNull { it.title.toString() == "停止" })
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun completionIsSilentAndKeepsCompletionText() {
        GenerationLiveUpdate.update(app, 0.4f, 1, isExecuting = true)
        GenerationLiveUpdate.update(app, 0f, 0, isExecuting = false)
        val notification = manager.activeNotifications.single { it.id == GenerationLiveUpdate.NOTIFICATION_ID }.notification
        assertEquals("绘图完成", notification.extras.getString(Notification.EXTRA_TITLE))
        assertSilent(notification)
    }

    @Test fun failureIsSilentAndNeverReplacesForegroundNotification() {
        GenerationLiveUpdate.update(app, 0.4f, 1, isExecuting = true)
        GenerationLiveUpdate.notifyFailedThenDismiss(app, "绘图失败")
        val notifications = manager.activeNotifications.toList()
        assertTrue(notifications.single { it.id == GenerationLiveUpdate.NOTIFICATION_ID }
            .notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val failure = notifications.single { it.id == 1004 }.notification
        assertEquals("绘图失败", failure.extras.getString(Notification.EXTRA_TITLE))
        assertSilent(failure)
    }
}

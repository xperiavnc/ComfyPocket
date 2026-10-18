package com.comfyui.mobile.ui.feedback

import android.app.Activity
import android.os.Looper
import android.widget.Toast
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppToastTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun clearPreviousFeedback() {
        idle()
        ShadowToast.reset()
    }

    @Test fun latestMessageCancelsPreviousInsteadOfBuildingAQueue() {
        AppToast.show(app, "正在保存")
        idle()
        val first = ShadowToast.getLatestToast()
        AppToast.show(app, "已保存")
        idle()
        val second = ShadowToast.getLatestToast()
        assertNotSame(first, second)
        assertTrue(shadowOf(first).isCancelled())
        assertFalse(shadowOf(second).isCancelled())
        assertEquals("已保存", ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_SHORT, second.duration)
        println("APP_TOAST replace oldCancelled=true current=已保存 duration=SHORT")
    }

    @Test fun blankFeedbackDoesNotShowOrCancelTheCurrentMessage() {
        AppToast.show(app, "已复制")
        idle()
        val first = ShadowToast.getLatestToast()
        AppToast.show(app, " \n\t")
        idle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertSame(first, ShadowToast.getLatestToast())
        assertFalse(shadowOf(first).isCancelled())
    }

    @Test fun identicalMessagesFromSeparateActionsStillConfirmEachAction() {
        repeat(2) { AppToast.show(app, "已保存配置"); idle() }
        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals("已保存配置", ShadowToast.getTextOfLatestToast())
    }

    @Test fun workerFeedbackAndMainFeedbackUseOneOrderedMainQueue() {
        val error = AtomicReference<Throwable?>()
        Thread {
            try { AppToast.show(app, "旧任务结果") }
            catch (failure: Throwable) { error.set(failure) }
        }.apply { start(); join() }
        AppToast.show(app, "最新操作结果")
        assertEquals(0, ShadowToast.shownToastCount())
        idle()
        assertNull(error.get())
        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals("最新操作结果", ShadowToast.getTextOfLatestToast())
        println("APP_TOAST workerThenMain=ordered latest=最新操作结果")
    }

    @Test fun queuedFeedbackKeepsOnlyTheApplicationContextAfterActivityDestruction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        AppToast.show(controller.get(), "已保存")
        controller.pause().stop().destroy()
        idle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertSame(app, ReflectionHelpers.getField<android.content.Context>(ShadowToast.getLatestToast(), "mContext"))
        assertEquals("已保存", ShadowToast.getTextOfLatestToast())
    }
}

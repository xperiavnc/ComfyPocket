package com.comfyui.mobile.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDrawNudgeTest {
    @Test
    fun drawRequestsAnsweredWithTextAreNudged() {
        assertTrue(AgentDrawNudge.wanted("画一张猫娘在海边", "好的，猫娘和海边很搭。"))
        assertTrue(AgentDrawNudge.wanted("开始画吧", "好的"))
        assertTrue(AgentDrawNudge.wanted("嗯", "好的，这就为你生成！"))
        assertTrue(AgentDrawNudge.wanted("嗯", "好的，这就为你生成！", declined = true))
    }

    @Test
    fun chatQuestionsVetoesAndDeclinesAreLeftAlone() {
        assertFalse(AgentDrawNudge.wanted("你好", "你好呀"))
        assertFalse(AgentDrawNudge.wanted("先别画，帮我想想构图", "好的，这就为你想几个构图"))
        assertFalse(AgentDrawNudge.wanted("这个角色是谁", "她是明日方舟的干员。"))
        assertFalse(AgentDrawNudge.wanted("把背景改一下", "请先确认想用什么背景，等你回答后我再处理。"))
        assertFalse(AgentDrawNudge.wanted("画一张猫", "要白猫还是黑猫？"))
        assertFalse(AgentDrawNudge.wanted("换成全身", "先讨论方案。", declined = true))
    }
}

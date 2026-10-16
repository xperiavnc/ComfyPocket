package com.comfyui.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptErrorParserTest {

    @Test
    fun testParseSuccessPromptId() {
        val body = """{"prompt_id":"3d5f3089-1234-5678-9abc-def012345678","number":1}"""
        val result = PromptErrorParser.parse(body, 200)
        assertEquals("3d5f3089-1234-5678-9abc-def012345678", result.promptId)
        assertNull(result.message)
    }

    @Test
    fun testParseEmptyBody() {
        val result = PromptErrorParser.parse("", 500)
        assertNull(result.promptId)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("空响应"))
    }

    @Test
    fun testParseEmptyObject() {
        val result = PromptErrorParser.parse("{}", 200)
        assertNull(result.promptId)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("{}"))
    }

    @Test
    fun testParseComfyUiNodeError() {
        val body = """
        {
            "error": {
                "type": "prompt_outputs_failed_validation",
                "message": "Prompt outputs failed validation",
                "details": "Syntax error"
            },
            "node_errors": {
                "3": {
                    "errors": [
                        {
                            "type": "value_not_in_list",
                            "message": "Value not in list",
                            "details": "model.safetensors not found",
                            "extra_info": {}
                        }
                    ],
                    "class_type": "KSampler"
                }
            }
        }
        """.trimIndent()

        val result = PromptErrorParser.parse(body, 400)
        assertNull(result.promptId)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("节点 #3 KSampler"))
        assertTrue(result.message!!.contains("model.safetensors not found"))
    }

    @Test
    fun testParseRunningHubError() {
        val body = """{"code":401,"msg":"API Key is invalid"}"""
        val result = PromptErrorParser.parse(body, 200)
        assertNull(result.promptId)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("API Key") || result.message!!.contains("密钥"))
    }
}

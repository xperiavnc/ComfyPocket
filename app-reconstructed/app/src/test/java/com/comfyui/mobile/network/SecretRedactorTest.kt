package com.comfyui.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun testRedactProxyKey() {
        val input = "http://127.0.0.1:8188/proxy/abc123secret/view?filename=test.png"
        val expected = "http://127.0.0.1:8188/proxy/***/view?filename=test.png"
        assertEquals(expected, SecretRedactor.redact(input))
    }

    @Test
    fun testRedactQueryKey() {
        val input = "https://www.runninghub.cn/task?apiKey=mySecretKey123&page=1"
        val expected = "https://www.runninghub.cn/task?apiKey=***&page=1"
        assertEquals(expected, SecretRedactor.redact(input))
    }

    @Test
    fun testRedactBearerToken() {
        val input = "Authorization: Bearer sk-proj-1234567890abcdef"
        val expected = "Authorization: Bearer ***"
        assertEquals(expected, SecretRedactor.redact(input))
    }

    @Test
    fun testRedactJsonKey() {
        val input = """{"code":0,"apiKey":"secretValue123","msg":"ok"}"""
        val expected = """{"code":0,"apiKey":"***","msg":"ok"}"""
        assertEquals(expected, SecretRedactor.redact(input))
    }

    @Test
    fun testIsValidBaseUrl() {
        assertTrue(SecretRedactor.isValidBaseUrl("http://127.0.0.1:8188"))
        assertTrue(SecretRedactor.isValidBaseUrl("https://api.runninghub.cn"))
        assertTrue(SecretRedactor.isValidBaseUrl("http://192.168.1.100:8188/"))

        assertFalse(SecretRedactor.isValidBaseUrl(""))
        assertFalse(SecretRedactor.isValidBaseUrl("   "))
        assertFalse(SecretRedactor.isValidBaseUrl("127.0.0.1:8188"))
        assertFalse(SecretRedactor.isValidBaseUrl("ftp://127.0.0.1:8188"))
        assertFalse(SecretRedactor.isValidBaseUrl("https://https:/www.runninghub.cn"))
    }
}

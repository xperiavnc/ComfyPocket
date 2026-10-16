package com.comfyui.mobile.market

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A separate client: never inherit ComfyUI's local-server certificate exceptions. */
object CivitaiHttp {
    fun client(settings: CivitaiSettings): OkHttpClient {
        val config = settings.validated()
        val proxyUrl = config.proxyUrl()
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .proxy(if (proxyUrl == null) Proxy.NO_PROXY else
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyUrl.host, proxyUrl.port)))
            .proxyAuthenticator(if (proxyUrl == null || config.proxyUsername.isBlank())
                Authenticator.NONE else Authenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header(
                        "Proxy-Authorization",
                        Credentials.basic(config.proxyUsername, config.proxyPassword, Charsets.UTF_8)
                    ).build()
                })
            .addNetworkInterceptor(CivitaiAuthInterceptor(config.apiKey))
            .build()
    }

    fun request(url: HttpUrl): Request = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "ComfyPocket/market")
        .build()

    suspend fun text(client: OkHttpClient, request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(MarketAccessException(networkMessage(e)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw MarketAccessException(
                                statusMessage(response.code), response.code
                            )
                            val source = response.body?.source()
                                ?: throw MarketAccessException("Civitai 返回了空响应")
                            val buffer = Buffer()
                            val maxBytes = 8L * 1024 * 1024
                            while (buffer.size <= maxBytes) {
                                if (source.read(buffer, minOf(8192L, maxBytes + 1 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > maxBytes) throw MarketAccessException("Civitai 响应过大，请缩小搜索范围")
                            val text = buffer.readUtf8()
                            if (continuation.isActive) continuation.resume(text)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (e is MarketAccessException) e
                                else MarketAccessException("读取 Civitai 响应失败，请重试")
                            )
                        }
                    }
                }
            })
        }

    internal fun statusMessage(status: Int): String = when (status) {
        401 -> "Civitai Key 无效或已过期，请检查集市设置"
        403 -> "当前账号尚无访问权限，或请求受到站点／地区策略限制"
        404 -> "模型不存在，或当前账号不可见"
        407 -> "HTTP 代理认证失败，请检查代理用户名和密码"
        429 -> "Civitai 请求频率受限，请稍后重试"
        else -> "Civitai 请求失败（HTTP $status）"
    }

    private fun networkMessage(error: IOException): String = when (error) {
        is SocketTimeoutException -> "Civitai 连接超时，请检查手机网络或 HTTP 代理"
        is UnknownHostException -> "域名解析失败，请检查手机网络或 HTTP 代理地址"
        is SSLException -> "Civitai TLS 校验失败，请检查代理和系统时间"
        else -> "Civitai 连接失败，请检查手机网络或 HTTP 代理"
    }
}

class MarketAccessException(message: String, val status: Int? = null) : IOException(message)

internal class CivitaiAuthInterceptor(private val apiKey: String) : Interceptor {
    /** Network interceptor runs again on redirects; a key is never forwarded to a CDN or proxy CONNECT. */
    internal fun authenticated(request: Request): Request {
        val url = request.url
        val official = url.isHttps && url.host == "civitai.com" && url.port == 443 &&
            url.encodedPath.startsWith("/api/v1/") && request.method != "CONNECT"
        return request.newBuilder().removeHeader("Authorization").apply {
            if (official && apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(authenticated(chain.request()))
}

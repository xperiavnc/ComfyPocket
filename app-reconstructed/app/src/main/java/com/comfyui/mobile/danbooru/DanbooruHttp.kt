package com.comfyui.mobile.danbooru

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal val DANBOORU_ORIGIN = "https://danbooru.donmai.us/".toHttpUrl()

/** A network interceptor is re-evaluated on every redirect, not just the original request. */
internal class DanbooruAuthInterceptor(
    private val settings: DanbooruSettings,
    private val origin: HttpUrl = DANBOORU_ORIGIN
) : Interceptor {
    fun authenticated(request: Request): Request {
        val url = request.url
        val apiPath = url.encodedPath == "/profile.json" || url.encodedPath == "/posts.json" ||
            url.encodedPath.matches(Regex("/posts/[0-9]+\\.json"))
        return request.newBuilder().removeHeader("Authorization").apply {
            if (url.isHttps && url.host == origin.host && url.port == origin.port &&
                request.method == "GET" && apiPath && settings.loggedIn) {
                header("Authorization", Credentials.basic(settings.username, settings.apiKey, Charsets.UTF_8))
            }
        }.build()
    }
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(authenticated(chain.request()))
}

/** Process-wide, shared by settings tests and live browsing; retries consume the same queue. */
class DanbooruApiQueue(
    private val intervalMs: Long = 1000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private var lastStart: Long? = null
    suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
        val wait = lastStart?.let { intervalMs - (now() - it) } ?: 0
        if (wait > 0) pause(wait)
        lastStart = now()
        block()
    }
}

object DanbooruHttp {
    private val mediaPermits = Semaphore(4, true)
    fun client(settings: DanbooruSettings, api: Boolean, userAgent: () -> String): OkHttpClient {
        val config = settings.validated()
        val proxy = config.proxyUrl()
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .proxy(if (proxy == null) Proxy.NO_PROXY else
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
            .proxyAuthenticator(if (proxy == null || config.proxyUsername.isBlank()) Authenticator.NONE else
                Authenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization",
                        Credentials.basic(config.proxyUsername, config.proxyPassword, Charsets.UTF_8)).build()
                })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header("User-Agent", userAgent())
                    .removeHeader("Authorization").build()
                if (api) chain.proceed(request) else {
                    while (!mediaPermits.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                        if (chain.call().isCanceled()) throw IOException("Canceled")
                    }
                    try {
                        val response = chain.proceed(request)
                        val body = response.body
                        if (body == null) {
                            mediaPermits.release()
                            response
                        } else {
                            val released = AtomicBoolean()
                            val source = object : ForwardingSource(body.source()) {
                                override fun close() {
                                    try { super.close() } finally {
                                        if (released.compareAndSet(false, true)) mediaPermits.release()
                                    }
                                }
                            }.buffer()
                            response.newBuilder().body(object : ResponseBody() {
                                override fun contentType() = body.contentType()
                                override fun contentLength() = body.contentLength()
                                override fun source() = source
                            }).build()
                        }
                    } catch (e: Exception) {
                        mediaPermits.release()
                        throw e
                    }
                }
            }
            .apply {
                if (api) addNetworkInterceptor(DanbooruAuthInterceptor(config))
                else addNetworkInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().removeHeader("Authorization").build())
                }
            }.build()
    }

    suspend fun text(
        calls: Call.Factory, request: Request, queue: DanbooruApiQueue,
        pause: suspend (Long) -> Unit = { delay(it) }
    ): String {
        var retries = 0
        while (true) {
            val result = queue.execute { response(calls, request) }
            if (result.code == 429 && retries < 2) {
                val wait = retryDelay(result.retryAfter, retries)
                // Retry-After is not shortened. Cancellation interrupts even a long server wait.
                pause(wait)
                retries++
                continue
            }
            if (result.code !in 200..299) throw DanbooruException(statusMessage(result.code), result.code)
            return result.text
        }
    }

    private data class TextResponse(val code: Int, val retryAfter: String?, val text: String)
    private suspend fun response(calls: Call.Factory, request: Request): TextResponse =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkException(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val source = response.body?.source() ?: throw DanbooruException("Danbooru 返回空响应")
                            val buffer = Buffer()
                            val limit = 8L * 1024 * 1024
                            while (buffer.size <= limit) {
                                if (source.read(buffer, minOf(8192L, limit + 1 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > limit) throw DanbooruException("Danbooru 响应过大，请缩小查询范围")
                            if (continuation.isActive) continuation.resume(TextResponse(
                                response.code, response.header("Retry-After"), buffer.readUtf8()))
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (e is DanbooruException) e else DanbooruException("读取 Danbooru 响应失败"))
                        }
                    }
                }
            })
        }

    internal fun retryDelay(header: String?, attempt: Int, nowMs: Long = System.currentTimeMillis()): Long {
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
        if (header != null) runCatching {
            (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs).coerceAtLeast(0)
        }.getOrNull()?.let { return it }
        return (1000L shl attempt.coerceIn(0, 3)).coerceAtMost(8000)
    }
    fun statusMessage(code: Int): String = when (code) {
        401 -> "Danbooru 用户名或 API Key 无效，请检查画廊设置"
        403 -> "请求被拒绝：检查 API Key 动作权限、出口 IP 限制及账号可见范围"
        404 -> "帖子不存在或当前账号不可见"
        407 -> "HTTP 代理认证失败，请检查代理用户名和密码"
        410 -> "已到当前账号的分页边界；当前排序保持不变"
        429 -> "Danbooru 请求频率受限，自动重试已结束，请稍后手动重试"
        else -> "Danbooru 请求失败（HTTP $code）"
    }
    fun networkMessage(error: IOException): String = when {
        error.message.orEmpty().contains("407") ||
            error.message.orEmpty().contains("authenticate with proxy", ignoreCase = true) -> statusMessage(407)
        error is SocketTimeoutException -> "Danbooru 连接超时，请检查网络或独立 HTTP 代理"
        error is UnknownHostException -> "Danbooru 域名解析失败，请检查网络或代理地址"
        error is SSLException -> "Danbooru TLS 校验失败，请检查代理和系统时间"
        else -> "Danbooru 连接失败，请检查网络或独立 HTTP 代理"
    }
    fun networkException(error: IOException): DanbooruException = if (error is DanbooruException) error else
        DanbooruException(networkMessage(error), if (error.message.orEmpty().contains("407") ||
            error.message.orEmpty().contains("authenticate with proxy", true)) 407 else null)
}

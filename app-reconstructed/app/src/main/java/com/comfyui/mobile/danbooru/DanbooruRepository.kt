package com.comfyui.mobile.danbooru

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Independent GET-only client: no changes to the ComfyUI, Civitai or LLM clients. */
class DanbooruRepository(
    private val cache: DanbooruCache,
    private val baseUrl: HttpUrl = "https://danbooru.donmai.us/".toHttpUrl(),
    private val clientFor: (DanbooruSettings) -> Call.Factory = DefaultClients::forSettings,
    private val now: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = 1_000,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private val rateLock = Mutex()
    private var lastRequestAt = Long.MIN_VALUE
    private val cooldowns = mutableMapOf<String, Long>()

    suspend fun lookup(
        queries: List<String>, limit: Int, origin: TagCheckOrigin, settings: DanbooruSettings,
        refresh: Boolean = false
    ): DanbooruCheckReport = withContext(Dispatchers.IO) {
        val s = settings.validated()
        val scope = s.namespace()
        val names = queries.map(::normalizeTag).filter { it.isNotEmpty() && it.length <= 64 }
            .distinct().take(3)
        val maxResults = limit.coerceIn(1, 5)
        var requests = 0
        suspend fun get(type: DanbooruApiType, name: String): DanbooruApiResult {
            currentCoroutineContext().ensureActive()
            if (!refresh) cache.get(scope, type, name)?.let { return it }
            return rateLock.withLock {
                val until = cooldowns[scope] ?: 0
                if (now() < until) return@withLock DanbooruApiResult(LookupStatus.RATE_LIMITED, retryAt = until)
                if (requests >= 3) return@withLock DanbooruApiResult(LookupStatus.BUDGET_EXHAUSTED)
                val wait = if (lastRequestAt == Long.MIN_VALUE) 0 else
                    (intervalMillis - (now() - lastRequestAt)).coerceAtLeast(0)
                if (wait > 0) pause(wait)
                currentCoroutineContext().ensureActive()
                lastRequestAt = now()
                requests++
                val result = fetch(type, name, maxResults, s).copy(fetchedAt = now())
                result.retryAt?.let { cooldowns[scope] = it }
                cache.put(scope, type, name, result)
                result
            }
        }
        // Prefer useful depth for the first uncertain term; additional terms share this same budget.
        val items = mutableListOf<DanbooruQueryResult>()
        for (name in names) {
            val search = get(DanbooruApiType.TAG, name)
            val exact = search.tags.firstOrNull { it.name == name }
            val alias = if (exact == null && search.status in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND)) {
                get(DanbooruApiType.ALIAS, name)
            } else null
            val canonical = exact?.name ?: alias?.aliases?.firstOrNull { it.antecedentName == name }?.consequentName
            val wiki = canonical?.let { get(DanbooruApiType.WIKI, it) }
            items.add(DanbooruQueryResult(name, search, alias, wiki))
        }
        DanbooruCheckReport(origin, items, requests, refresh)
    }

    internal fun url(type: DanbooruApiType, query: String, limit: Int): HttpUrl {
        val name = normalizeTag(query)
        return baseUrl.newBuilder().apply {
            when (type) {
                DanbooruApiType.TAG -> {
                    addPathSegment("tags.json")
                    addQueryParameter("search[name_or_alias_matches]", name)
                    addQueryParameter("search[order]", "count")
                    addQueryParameter("limit", limit.coerceIn(1, 5).toString())
                    addQueryParameter("only", "name,category,post_count,is_deprecated")
                }
                DanbooruApiType.ALIAS -> {
                    addPathSegment("tag_aliases.json")
                    addQueryParameter("search[antecedent_name_matches]", name)
                    addQueryParameter("search[status]", "active")
                    addQueryParameter("limit", "5")
                    addQueryParameter("only", "antecedent_name,consequent_name,status")
                }
                DanbooruApiType.WIKI -> {
                    addPathSegment("wiki_pages")
                    addPathSegment("$name.json")
                    addQueryParameter("only", "title,body")
                }
            }
        }.build()
    }

    private suspend fun fetch(type: DanbooruApiType, name: String, limit: Int, settings: DanbooruSettings): DanbooruApiResult {
        val request = Request.Builder().url(url(type, name, limit)).get()
            .header("User-Agent", "ComfyPocket/0.0.4 (https://github.com/xperiavnc/ComfyPocket)")
            .header("Accept", "application/json").build()
        return try {
            execute(clientFor(settings).newCall(request)) { response ->
                if (response.code == 429) {
                    val retry = retryAt(response.header("Retry-After"))
                    DanbooruApiResult(LookupStatus.RATE_LIMITED, httpCode = 429, retryAt = retry)
                } else if (response.code == 404 && type == DanbooruApiType.WIKI) {
                    DanbooruApiResult(LookupStatus.NO_WIKI, httpCode = 404)
                } else if (!response.isSuccessful) {
                    DanbooruApiResult(LookupStatus.HTTP_ERROR, httpCode = response.code)
                } else {
                    val source = response.body?.source() ?: throw IllegalArgumentException("Empty JSON body")
                    require(!source.request(128 * 1024L + 1)) { "JSON response exceeds limit" }
                    parse(type, name, source.buffer.readUtf8())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: InterruptedIOException) {
            DanbooruApiResult(LookupStatus.TIMEOUT)
        } catch (_: IOException) {
            DanbooruApiResult(LookupStatus.NETWORK_ERROR)
        } catch (_: Exception) {
            DanbooruApiResult(LookupStatus.PARSE_ERROR)
        }
    }

    private suspend fun <T> execute(call: Call, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use(read)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    internal fun parse(type: DanbooruApiType, query: String, raw: String): DanbooruApiResult {
        val root = JsonParser.parseString(raw)
        return when (type) {
            DanbooruApiType.TAG -> {
                require(root.isJsonArray)
                val tags = root.asJsonArray.take(5).map { element ->
                    val obj = element.asJsonObject
                    val name = obj["name"].asString.take(160)
                    require(name.isNotBlank())
                    DanbooruTag(name, obj.intOrNull("category"), obj.longOrNull("post_count"),
                        obj["is_deprecated"]?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                        if (name == query) MatchKind.EXACT else MatchKind.FUZZY)
                }.sortedWith(compareBy<DanbooruTag> { if (it.matchKind == MatchKind.EXACT) 0 else 1 }
                    .thenByDescending { it.postCount ?: 0 })
                DanbooruApiResult(if (tags.isEmpty()) LookupStatus.NOT_FOUND else LookupStatus.FOUND, tags)
            }
            DanbooruApiType.ALIAS -> {
                require(root.isJsonArray)
                val aliases = root.asJsonArray.take(5).mapNotNull { element ->
                    val obj = element.asJsonObject
                    if (obj["status"]?.asString != "active") return@mapNotNull null
                    val from = obj["antecedent_name"].asString.take(160)
                    val to = obj["consequent_name"].asString.take(160)
                    if (from != query || to.isBlank()) null else DanbooruAlias(from, to)
                }
                DanbooruApiResult(if (aliases.isEmpty()) LookupStatus.NOT_FOUND else LookupStatus.FOUND, aliases = aliases)
            }
            DanbooruApiType.WIKI -> {
                require(root.isJsonObject && root.asJsonObject.has("body"))
                val body = root.asJsonObject["body"]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val definition = body.split(Regex("\\r?\\n\\s*\\r?\\n"))
                    .firstOrNull { it.isNotBlank() && !Regex("^(h[1-6]\\.|\\* |!post)").containsMatchIn(it.trim()) }.orEmpty()
                val summary = definition.replace(Regex("\\[\\[([^]|]+)\\|([^]]+)]]"), "$2")
                    .replace("[[", "").replace("]]", "").replace(Regex("\\s+"), " ").trim().take(600)
                DanbooruApiResult(if (summary.isBlank()) LookupStatus.NO_WIKI else LookupStatus.FOUND, wikiSummary = summary)
            }
        }
    }

    private fun retryAt(header: String?): Long {
        val seconds = header?.toLongOrNull()
        if (seconds != null) return now() + seconds.coerceIn(1, 86_400) * 1000
        return runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.coerceIn(now() + 1_000, now() + 86_400_000) ?: (now() + 60_000)
    }

    private fun JsonObject.intOrNull(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asInt
    private fun JsonObject.longOrNull(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asLong

    private object DefaultClients {
        private val clients = ConcurrentHashMap<String, OkHttpClient>()
        fun forSettings(settings: DanbooruSettings): Call.Factory =
            clients.computeIfAbsent(settings.namespace()) {
                val proxy = settings.proxyUrl()?.let { url -> Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host, url.port)) }
                    ?: Proxy.NO_PROXY
                OkHttpClient.Builder().proxy(proxy).connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).followRedirects(false).build()
            }
    }
}

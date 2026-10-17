package com.comfyui.mobile.danbooru

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request

interface DanbooruDataSource {
    suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean = false): List<DanbooruPost>
    suspend fun post(id: Long): DanbooruPost
    suspend fun verify(settings: DanbooruSettings): DanbooruIdentity?
}

class DanbooruRepository(
    private val calls: Call.Factory,
    private val queue: DanbooruApiQueue,
    private val cacheDirectory: File,
    private val origin: HttpUrl = DANBOORU_ORIGIN
) : DanbooruDataSource {
    private fun request(path: String, query: DanbooruQuery? = null, page: String? = null): Request {
        val url = origin.newBuilder().encodedPath(path).apply {
            if (query != null) {
                addQueryParameter("tags", query.tags)
                addQueryParameter("limit", "40")
            }
            if (page != null) addQueryParameter("page", page)
        }.build()
        return Request.Builder().url(url).header("Accept", "application/json").build()
    }
    override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean): List<DanbooruPost> {
        val file = File(cacheDirectory, danbooruHash(query.tags + "\u0000" + page.orEmpty()) + ".json")
        if (!refresh) withContext(Dispatchers.IO) {
            file.takeIf { it.isFile }?.let {
                runCatching { DanbooruJson.posts(it.readText()).also { _ -> it.setLastModified(System.currentTimeMillis()) } }.getOrNull()
            }
        }?.let { return it }
        val json = DanbooruHttp.text(calls, request("/posts.json", query, page), queue)
        val result = DanbooruJson.posts(json)
        withContext(Dispatchers.IO) {
            runCatching {
                cacheDirectory.mkdirs()
                val temp = File.createTempFile("page-", ".tmp", cacheDirectory)
                try {
                    temp.writeText(json)
                    if (!temp.renameTo(file)) throw java.io.IOException("Page cache publish failed")
                } finally { temp.delete() }
                // Disk LRU is separate from the visible page window; eviction never means "end".
                var total = 0L
                cacheDirectory.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                    total += it.length()
                    if (total > 16L * 1024 * 1024) it.delete()
                }
            }
        }
        return result
    }
    override suspend fun post(id: Long): DanbooruPost =
        DanbooruJson.detail(DanbooruHttp.text(calls, request("/posts/$id.json"), queue))

    override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? {
        val identity = if (settings.loggedIn) DanbooruJson.identity(
            DanbooruHttp.text(calls, request("/profile.json"), queue), settings.username) else null
        // Verify index AND show, since restricted keys can allow profile but block these actions.
        val list = DanbooruJson.posts(DanbooruHttp.text(calls,
            request("/posts.json", DanbooruQuery.resolve("", ContentFilter.GENERAL)), queue))
        val first = list.firstOrNull()
        if (first != null) post(first.id)
        else if (identity != null) throw DanbooruException("账号身份已确认，但查询没有帖子，尚未验证 posts:show 读取权限")
        return identity
    }
}

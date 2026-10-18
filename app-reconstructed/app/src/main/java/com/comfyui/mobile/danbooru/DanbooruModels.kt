package com.comfyui.mobile.danbooru

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class TagGroup(val title: String, val field: String) {
    ARTIST("画师", "tag_string_artist"), COPYRIGHT("作品", "tag_string_copyright"),
    CHARACTER("角色", "tag_string_character"), GENERAL("通用", "tag_string_general"),
    META("元信息", "tag_string_meta")
}

enum class ContentFilter(val title: String, val token: String?) {
    GENERAL("一般", "rating:g"), SENSITIVE("敏感", "rating:s"),
    QUESTIONABLE("疑问", "rating:q"), EXPLICIT("明确", "rating:e"),
    ALL("全部评级", null), CUSTOM("查询指定评级", null)
}

data class DanbooruPost(
    val id: Long,
    val tags: Map<TagGroup, List<String>>,
    val previewUrl: String? = null,
    val sampleUrl: String? = null,
    val fileUrl: String? = null,
    val md5: String? = null,
    val extension: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val rating: String = "",
    val source: String = "",
    val variants: List<DanbooruMediaVariant> = emptyList()
) {
    val isVideo get() = extension.lowercase() in DANBOORU_VIDEO_EXTENSIONS
    val isImage get() = extension.isBlank() || extension.lowercase() in DANBOORU_IMAGE_EXTENSIONS
    val postUrl get() = "https://danbooru.donmai.us/posts/$id"
    fun mediaKey(scope: String, representation: String, url: String): String =
        "danbooru:$scope:$id:${md5.orEmpty()}:$representation:${danbooruHash(url)}"
}

data class DanbooruQuery(
    val text: String, val filter: ContentFilter, val tags: String, val cursorPaging: Boolean, val order: String,
    val sort: DanbooruSort = DanbooruSort.LATEST,
    val rankingPeriod: RankingPeriod = RankingPeriod.DAY,
    val rankingDate: LocalDate = danbooruToday()
) {
    companion object {
        fun resolve(
            input: String, defaultFilter: ContentFilter,
            sort: DanbooruSort = DanbooruSort.LATEST,
            rankingPeriod: RankingPeriod = RankingPeriod.DAY,
            rankingDate: LocalDate = danbooruToday()
        ): DanbooruQuery {
            val text = input.trim()
            val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
            val ratings = tokens.filter { it.matches(Regex("(?i)-?rating:.*")) }
            val filter = if (ratings.isEmpty()) defaultFilter else {
                val value = ratings.singleOrNull()?.lowercase()
                when (value) {
                    "rating:g", "rating:general" -> ContentFilter.GENERAL
                    "rating:s", "rating:sensitive" -> ContentFilter.SENSITIVE
                    "rating:q", "rating:questionable" -> ContentFilter.QUESTIONABLE
                    "rating:e", "rating:explicit" -> ContentFilter.EXPLICIT
                    else -> ContentFilter.CUSTOM
                }
            }
            val orders = tokens.filter { it.startsWith("order:", ignoreCase = true) }
            // An explicitly typed order wins. Toolbar selections remove only order: tokens,
            // preserving the user's tags, negative tags and rating expression.
            val effectiveSort = if (orders.isEmpty()) sort else when (orders.singleOrNull()?.lowercase()) {
                "order:id_desc" -> DanbooruSort.LATEST
                "order:rank" -> DanbooruSort.HOT
                "order:score" -> DanbooruSort.SCORE
                "order:favcount" -> DanbooruSort.FAVORITES
                else -> DanbooruSort.CUSTOM
            }
            val generated = buildList {
                if (ratings.isEmpty()) addAll(listOfNotNull(filter.token))
                if (orders.isEmpty() && effectiveSort != DanbooruSort.LATEST) add(effectiveSort.token)
                if (effectiveSort == DanbooruSort.RANKING) {
                    add("status:active")
                    add(rankingPeriod.dateTag(rankingDate))
                }
            }.filter(String::isNotBlank)
            return DanbooruQuery(text, filter,
                (tokens + generated).joinToString(" "),
                effectiveSort == DanbooruSort.LATEST,
                orders.joinToString(" ").ifBlank { effectiveSort.token },
                effectiveSort, rankingPeriod, rankingDate)
        }

        fun withoutOrder(input: String): String =
            input.trim().split(Regex("\\s+")).filter { it.isNotBlank() && !it.startsWith("order:", true) }.joinToString(" ")

        fun withFilter(input: String, filter: ContentFilter): String =
            (input.trim().split(Regex("\\s+")).filter { it.isNotBlank() && !it.matches(Regex("(?i)-?rating:.*")) } +
                listOfNotNull(filter.token)).joinToString(" ")
    }
}

object DanbooruJson {
    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.number(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
    private fun JsonObject.media(key: String): String? = text(key)?.takeIf {
        val url = it.toHttpUrlOrNull()
        url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()
    }
    fun post(obj: JsonObject): DanbooruPost {
        val id = obj.number("id")?.takeIf { it > 0 } ?: throw DanbooruException("帖子响应缺少有效 ID")
        return DanbooruPost(id, TagGroup.entries.associateWith { group ->
            obj.text(group.field).orEmpty().split(Regex("\\s+")).filter(String::isNotBlank).distinct()
        }, obj.media("preview_file_url"), obj.media("large_file_url"), obj.media("file_url"),
            obj.text("md5"), obj.text("file_ext").orEmpty(),
            obj.number("image_width")?.toInt(), obj.number("image_height")?.toInt(),
            obj.text("rating").orEmpty(), obj.text("source").orEmpty(),
            obj.getAsJsonObjectOrNull("media_asset")?.get("variants")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { value ->
                    value.takeIf { it.isJsonObject }?.asJsonObject?.let { variant ->
                        variant.media("url")?.let { url ->
                            DanbooruMediaVariant(url, variant.text("file_ext").orEmpty(),
                                variant.text("type").orEmpty(), variant.number("width")?.toInt())
                        }
                    }
                }.orEmpty())
    }
    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    fun posts(json: String): List<DanbooruPost> = parse {
        val array = JsonParser.parseString(json)
        if (!array.isJsonArray) throw DanbooruException("帖子列表格式错误，请重试")
        array.asJsonArray.map { post(it.asJsonObject) }.distinctBy { it.id }
    }
    fun detail(json: String): DanbooruPost = parse { post(JsonParser.parseString(json).asJsonObject) }
    fun identity(json: String, expectedName: String): DanbooruIdentity = parse {
        val obj = JsonParser.parseString(json).asJsonObject
        val id = obj.number("id") ?: 0
        val name = obj.text("name").orEmpty()
        if (id <= 0 || name.equals("Anonymous", true) || !name.equals(expectedName, true)) {
            throw DanbooruException("登录未确认：返回匿名或其他账号，请检查用户名和 API Key", 401)
        }
        DanbooruIdentity(id, name, obj.text("level_string") ?: obj.number("level")?.toString().orEmpty())
    }
    private inline fun <T> parse(block: () -> T): T = try { block() } catch (e: DanbooruException) { throw e }
        catch (_: Exception) { throw DanbooruException("Danbooru 响应解析失败，请重试") }
}

data class DanbooruIdentity(val id: Long, val name: String, val level: String)
class DanbooruException(message: String, val status: Int? = null) : IOException(message)
internal fun danbooruHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** The toolbar and the system gesture dispatch exactly the same single action. */
enum class DanbooruBack { SETTINGS, SELECTION, DETAIL, ROOT }
fun danbooruBack(settingsOpen: Boolean, hasSelection: Boolean, hasDetail: Boolean): DanbooruBack = when {
    settingsOpen -> DanbooruBack.SETTINGS
    hasSelection -> DanbooruBack.SELECTION
    hasDetail -> DanbooruBack.DETAIL
    else -> DanbooruBack.ROOT
}

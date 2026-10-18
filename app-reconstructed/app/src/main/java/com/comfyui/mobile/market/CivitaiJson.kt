package com.comfyui.mobile.market

import com.comfyui.mobile.model.CivitaiFileItem
import com.comfyui.mobile.model.CivitaiModelItem
import com.comfyui.mobile.model.CivitaiPreviewItem
import com.comfyui.mobile.model.CivitaiSearchPageData
import com.comfyui.mobile.model.CivitaiVersionItem
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class MarketQuery(
    val keyword: String = "",
    val type: String = "ALL",
    val baseModel: String = "全部",
    val author: String = "",
    val sort: MarketSort = MarketSort.DEFAULT,
    val period: MarketPeriod = MarketPeriod.ALL_TIME
)

enum class MarketSort(val label: String, val apiValue: String) {
    DEFAULT("默认", "Highest Rated"), NEWEST("日期", "Newest"),
    DOWNLOADS("热度 · 下载", "Most Downloaded"), LIKES("人气 · 赞", "Most Liked")
}

enum class MarketPeriod(val label: String, val apiValue: String) {
    ALL_TIME("全部时间", "AllTime"), YEAR("一年", "Year"), MONTH("一月", "Month"),
    WEEK("一周", "Week"), DAY("一天", "Day")
}

/** Official JSON is not the Bridge's {code,data} envelope. Keep the mapping separate. */
internal object CivitaiJson {
    private val api = "https://civitai.com/api/v1/".toHttpUrl()

    fun endpoint(path: String): HttpUrl = api.newBuilder().addPathSegments(path).build()

    fun searchUrl(
        query: MarketQuery, page: Int, cursor: String?, settings: CivitaiSettings
    ): HttpUrl = endpoint("models").newBuilder().apply {
        addQueryParameter("limit", "20")
        addQueryParameter("nsfw", settings.includeMatureContent.toString())
        val id = modelId(query.keyword)
        val keyword = query.keyword.trim().takeUnless { id != null }.orEmpty()
        if (id != null) addQueryParameter("ids", id.toString())
        if (keyword.isNotEmpty()) addQueryParameter("query", keyword)
        query.author.trim().removePrefix("@").takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("username", it)
        }
        // Query search forbids page. Cursors are opaque; do not split or increment them.
        if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor)
        else if (keyword.isEmpty() && page > 1) addQueryParameter("page", page.toString())
        if (keyword.isEmpty()) addQueryParameter("sort", query.sort.apiValue)
        addQueryParameter("period", query.period.apiValue)
        val types = when (query.type.uppercase()) {
            "LORA", "LORAS" -> listOf("LORA", "LoCon", "DoRA")
            "CHECKPOINT", "CHECKPOINTS" -> listOf("Checkpoint")
            "UNET" -> listOf("UNet")
            "GGUF" -> listOf("Checkpoint", "UNet")
            "ALL", "全部", "" -> emptyList()
            else -> listOf(query.type)
        }
        types.forEach { addQueryParameter("types", it) }
        if (query.type.equals("GGUF", true)) {
            // The REST schema requires an array; one query value is parsed as a string.
            // Repeating the same value encodes a one-format array without widening it.
            repeat(2) { addQueryParameter("fileFormats", "GGUF") }
        }
        baseModels(query.baseModel).forEach { addQueryParameter("baseModels", it) }
    }.build()

    internal fun modelId(value: String): Long? {
        val text = value.trim()
        fun validId(value: String) = value.toLongOrNull()?.takeIf { it in 1..9_007_199_254_740_991L }
        if (text.matches(Regex("""#?\d+"""))) return validId(text.removePrefix("#"))
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.host !in setOf("civitai.com", "www.civitai.com") || url.scheme != "https") return null
        val parts = url.pathSegments
        if (parts.firstOrNull() != "models") return null
        return parts.getOrNull(1)?.let(::validId)
    }

    internal fun baseModels(tag: String): List<String> = when (tag) {
        "全部", "All", "" -> emptyList()
        "SDXL" -> listOf("SDXL 1.0", "SDXL Turbo", "SDXL Lightning", "SDXL 1.0 LCM")
        "Illustrious" -> listOf("Illustrious", "NoobAI")
        "Pony" -> listOf("Pony", "Pony V7")
        "Flux" -> listOf("Flux.1 D", "Flux.1 S", "Flux.1 Krea", "Flux.1 Kontext", "Flux.2 D")
        "SD 3.5" -> listOf("SD 3.5 Large", "SD 3.5 Medium")
        "Wan" -> listOf("Wan Video 1.3B t2v", "Wan Video 14B t2v",
            "Wan Video 14B i2v 480p", "Wan Video 14B i2v 720p",
            "Wan Video 2.2 TI2V-5B", "Wan Video 2.2 I2V-A14B", "Wan Video 2.2 T2V-A14B")
        else -> listOf(tag)
    }

    fun page(text: String, requestedPage: Int): CivitaiSearchPageData {
        val root = objectOf(text)
        require(root.has("items")) { "Missing items" }
        val metadata = root.obj("metadata")
        // Extract continuation values, never follow a server-provided arbitrary URL.
        val nextUrl = metadata.string("nextPage").toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host == "civitai.com" && it.port == 443 &&
                it.encodedPath == "/api/v1/models"
        }
        val cursor = metadata.string("nextCursor").ifBlank {
            nextUrl?.queryParameter("cursor").orEmpty()
        }.takeIf { it.isNotBlank() }
        val nextPage = nextUrl?.queryParameter("page")?.toIntOrNull()
            ?.takeIf { it > requestedPage }
        return CivitaiSearchPageData(
            items = root.objects("items").map(::model).filter { it.id > 0 }.distinctBy { it.id },
            nextCursor = cursor,
            totalItems = metadata.long("totalItems").toInt(),
            currentPage = requestedPage,
            pageSize = 20,
            totalPages = metadata.long("totalPages").toInt().coerceAtLeast(1),
            nextPage = nextPage
        )
    }

    fun model(text: String): CivitaiModelItem = model(objectOf(text)).also {
        require(it.id > 0) { "Missing model ID" }
    }

    private fun model(value: JsonObject): CivitaiModelItem {
        val versions = value.objects("modelVersions").map(::version).filter { it.id > 0 }
        val stats = value.obj("stats")
        return CivitaiModelItem(
            id = value.long("id"), name = value.string("name"),
            type = value.string("type").ifBlank { "LORA" },
            creator = value.obj("creator").string("username"),
            description = value.string("description"),
            downloadCount = stats.long("downloadCount"),
            rating = stats.double("rating"), thumbsUpCount = stats.long("thumbsUpCount"),
            publishedAt = versions.map { it.publishedAt }.filter { it.isNotBlank() }.maxOrNull().orEmpty(),
            tags = value.strings("tags"), versions = versions,
            previewUrl = thumbnail(versions.firstNotNullOfOrNull { it.previewUrl }, 450)
        )
    }

    fun version(text: String): CivitaiVersionItem = version(objectOf(text)).also {
        require(it.id > 0) { "Missing version ID" }
    }

    private fun version(value: JsonObject): CivitaiVersionItem {
        val download = cleanDownloadUrl(value.string("downloadUrl"))
        val files = value.objects("files").map { file ->
            CivitaiFileItem(
                id = file.long("id"), name = file.string("name"),
                sizeKB = file.double("sizeKB"),
                downloadUrl = cleanDownloadUrl(file.string("downloadUrl")).ifBlank { download },
                format = file.obj("metadata").string("format"),
                precision = file.obj("metadata").string("fp"),
                type = file.string("type").ifBlank { "Model" },
                primary = file.boolean("primary") == true
            )
        }
        val images = value.objects("images").filter {
            it.string("type").let { type -> type.isBlank() || type.equals("image", true) }
        }.map(::preview).filter { it.url.isNotBlank() }.take(12)
        val primary = files.firstOrNull { it.primary && !it.isRequiredComponent }
            ?: files.firstOrNull { !it.isRequiredComponent }
        return CivitaiVersionItem(
            id = value.long("id"), name = value.string("name"),
            baseModel = value.string("baseModel"),
            publishedAt = value.string("publishedAt").ifBlank { value.string("createdAt") },
            trainedWords = value.strings("trainedWords"),
            description = value.string("description"),
            downloadUrl = download.ifBlank { primary?.downloadUrl.orEmpty() },
            sizeKB = primary?.sizeKB ?: 0.0, previewUrl = images.firstOrNull()?.url,
            files = files, images = images,
            availability = value.string("availability"), canDownload = value.boolean("canDownload")
        )
    }

    fun examples(text: String): List<CivitaiPreviewItem> =
        objectOf(text).objects("items").map(::preview)
            .filter { it.url.isNotBlank() }.take(8).map { it.copy(communityExample = true) }

    private fun preview(image: JsonObject): CivitaiPreviewItem {
        val meta = image.obj("meta")
        return CivitaiPreviewItem(
            id = image.long("id"),
            url = thumbnail(image.string("url"), 800).orEmpty(),
            prompt = meta.string("prompt"),
            negativePrompt = meta.string("negativePrompt").ifBlank { meta.string("negative prompt") },
            sampler = meta.string("sampler"), steps = meta.string("steps"),
            seed = meta.string("seed"), cfgScale = meta.string("cfgScale")
        )
    }

    internal fun cleanDownloadUrl(raw: String): String =
        raw.toHttpUrlOrNull()?.newBuilder()?.removeAllQueryParameters("token")?.build()?.toString().orEmpty()

    internal fun thumbnail(raw: String?, width: Int): String? {
        val url = raw?.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.host != "image.civitai.com") return url.toString()
        return url.toString().replace(Regex("width=\\d+"), "width=$width")
    }

    private fun objectOf(text: String) = JsonParser.parseString(text).asJsonObject
    private fun JsonObject.obj(key: String): JsonObject =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    private fun JsonObject.objects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }.orEmpty()
    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf(String::isNotBlank)
        }.orEmpty()
    private fun JsonObject.long(key: String) = string(key).toLongOrNull() ?: 0L
    private fun JsonObject.double(key: String) = string(key).toDoubleOrNull() ?: 0.0
    private fun JsonObject.boolean(key: String): Boolean? = string(key).toBooleanStrictOrNull()
}

package com.comfyui.mobile.ui.material

import android.content.Context
import com.comfyui.mobile.R
import com.comfyui.mobile.danbooru.*
import kotlinx.coroutines.delay

enum class SampleScenario(val title: String) {
    NORMAL("正常内容"), EMPTY("空结果"), FIRST_ERROR("首次失败"), APPEND_ERROR("追加失败"), LONG("长标签")
}

/** The default preview never opens a network connection or touches persisted configuration. */
class MaterialFixtures(context: Context) : DanbooruService, DanbooruDataSource {
    override var settings = DanbooruSettings()
        private set
    override val repository get() = this
    var scenario = SampleScenario.NORMAL
        private set
    private var failOnce = false
    var requests = 0
        private set
    fun scenario(value: SampleScenario) { scenario = value; failOnce = true }
    private val resources = listOf(R.drawable.material_sample_lake, R.drawable.material_sample_dunes,
        R.drawable.material_sample_forest, R.drawable.material_sample_night)
    val pictures = (0 until 36).map { index ->
        val uri = "android.resource://${context.packageName}/${resources[index % resources.size]}"
        DanbooruPost(100L + index, mapOf(
            TagGroup.ARTIST to listOf("landscape_studio"),
            TagGroup.COPYRIGHT to listOf("original"),
            TagGroup.CHARACTER to emptyList(),
            TagGroup.GENERAL to listOf("scenery", "mountain", "blue_sky", "river", "sunset", "cloud", "outdoors", "tree", "reflection"),
            TagGroup.META to listOf("highres")
        ), sampleUrl = uri, previewUrl = uri, width = 360,
            height = listOf(500, 420, 540, 460)[index % 4], extension = "png", rating = "g")
    }
    override suspend fun posts(query: DanbooruQuery, page: String?, refresh: Boolean): List<DanbooruPost> {
        requests++
        delay(350)
        if (failOnce && (scenario == SampleScenario.FIRST_ERROR ||
                scenario == SampleScenario.APPEND_ERROR && page != null)) {
            failOnce = false
            throw DanbooruException(if (page == null) "连接超时（离线状态演示）" else "本页加载失败（离线状态演示）")
        }
        if (scenario == SampleScenario.EMPTY || query.text.contains("无结果")) return emptyList()
        val offset = when {
            page == null -> 0
            page.startsWith("b") -> pictures.indexOfFirst { it.id == page.drop(1).toLongOrNull() } + 1
            else -> ((page.toIntOrNull() ?: 1) - 1) * 12
        }
        val ordered = if (query.cursorPaging) pictures.reversed() else pictures
        val cursorOffset = if (page?.startsWith("b") == true)
            ordered.indexOfFirst { it.id == page.drop(1).toLongOrNull() } + 1 else offset
        return ordered.drop(cursorOffset.coerceAtLeast(0)).take(12).map(::withLongTags)
    }
    private fun withLongTags(post: DanbooruPost) = if (scenario != SampleScenario.LONG) post else post.copy(
        tags = post.tags + (TagGroup.GENERAL to (post.tags[TagGroup.GENERAL].orEmpty() +
            listOf("a_very_long_landscape_tag_with_unbroken_words_and_parentheses_(original_work)",
                "中文布局样例_不会作为真实词库条目", "dramatic_lighting", "beautiful_detailed_clouds"))))
    override suspend fun post(id: Long) = withLongTags(pictures.first { it.id == id })
    override suspend fun verify(settings: DanbooruSettings): DanbooruIdentity? = test(settings)
    override suspend fun save(settings: DanbooruSettings) { delay(150); this.settings = settings.validated() }
    override suspend fun test(settings: DanbooruSettings): DanbooruIdentity? {
        settings.validated(); delay(200)
        return null // The preview never claims real authentication.
    }
    override suspend fun clearCache() = Unit
}

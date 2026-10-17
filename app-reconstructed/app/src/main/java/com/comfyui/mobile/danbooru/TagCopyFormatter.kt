package com.comfyui.mobile.danbooru

enum class TagCopyMode(val title: String) { RAW("原始 Tag"), PROMPT("提示词") }
data class TagCopyOptions(
    val mode: TagCopyMode = TagCopyMode.PROMPT,
    val underscoresToSpaces: Boolean = true,
    val escapeParentheses: Boolean = false
)
data class CopiedTags(val text: String, val count: Int)

object TagCopyFormatter {
    fun format(
        post: DanbooruPost,
        options: TagCopyOptions,
        groups: Set<TagGroup> = TagGroup.entries.toSet(),
        selected: Set<String>? = null
    ): CopiedTags {
        val tags = TagGroup.entries.filter { it in groups }.flatMap { post.tags[it].orEmpty() }
            .filter { selected == null || it in selected }.distinct()
        val formatted = tags.map {
            if (options.mode == TagCopyMode.RAW) it else {
                var text = if (options.underscoresToSpaces) it.replace('_', ' ') else it
                if (options.escapeParentheses) text = text.replace("(", "\\(").replace(")", "\\)")
                text
            }
        }
        return CopiedTags(formatted.joinToString(if (options.mode == TagCopyMode.RAW) " " else ", "), tags.size)
    }
}

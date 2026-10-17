package com.comfyui.mobile.danbooru

enum class MatchKind { EXACT, ALIAS, FUZZY, UNVERIFIED }
enum class LookupStatus(val title: String) {
    FOUND("已核对"), NOT_FOUND("未查证"), NO_WIKI("无 Wiki"), NETWORK_ERROR("网络失败"),
    TIMEOUT("查询超时"), RATE_LIMITED("请求限流"), PARSE_ERROR("响应格式异常"),
    HTTP_ERROR("HTTP 错误"), BUDGET_EXHAUSTED("本轮请求额度已用完")
}
enum class TagCheckOrigin(val title: String) { MODEL_TOOL("模型请求查询"), APP_COMPAT("App 兼容核对") }
enum class TagCallMode(val title: String) { AUTO("自动 / 原生工具优先"), COMPAT("兼容核对") }
enum class DanbooruApiType(val ttlMillis: Long) {
    TAG(86_400_000), ALIAS(86_400_000), WIKI(604_800_000)
}
data class DanbooruTag(
    val name: String, val category: Int?, val postCount: Long?,
    val isDeprecated: Boolean = false, val matchKind: MatchKind = MatchKind.UNVERIFIED,
    val aliasTarget: String? = null, val wikiSummary: String? = null
)
data class DanbooruAlias(val antecedentName: String, val consequentName: String)
data class DanbooruApiResult(
    val status: LookupStatus, val tags: List<DanbooruTag> = emptyList(),
    val aliases: List<DanbooruAlias> = emptyList(), val wikiSummary: String? = null,
    val fetchedAt: Long = 0, val fromCache: Boolean = false,
    val httpCode: Int? = null, val retryAt: Long? = null
)
data class DanbooruQueryResult(
    val query: String, val search: DanbooruApiResult,
    val alias: DanbooruApiResult? = null, val wiki: DanbooruApiResult? = null
) {
    val tags: List<DanbooruTag> get() {
        val aliasTarget = alias?.aliases?.firstOrNull { it.antecedentName == query }?.consequentName
        val source = search.tags.map {
            it.copy(
                matchKind = when {
                    it.name == query -> MatchKind.EXACT
                    it.name == aliasTarget -> MatchKind.ALIAS
                    else -> MatchKind.FUZZY
                },
                aliasTarget = aliasTarget?.takeIf { target -> target == it.name },
                wikiSummary = wiki?.wikiSummary?.takeIf { _ ->
                    it.name == (aliasTarget ?: search.tags.firstOrNull()?.name)
                }
            )
        }.toMutableList()
        if (aliasTarget != null && source.none { it.name == aliasTarget }) {
            source.add(0, DanbooruTag(aliasTarget, null, null, matchKind = MatchKind.ALIAS, aliasTarget = aliasTarget))
        }
        return source.sortedBy { when (it.matchKind) { MatchKind.EXACT -> 0; MatchKind.ALIAS -> 1; else -> 2 } }
    }
}
data class DanbooruCheckReport(
    val origin: TagCheckOrigin, val items: List<DanbooruQueryResult>,
    val networkRequests: Int, val refreshed: Boolean = false
) {
    val incomplete: Boolean get() = items.any { item ->
        listOfNotNull(item.search, item.alias, item.wiki).any {
            it.status !in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND, LookupStatus.NO_WIKI)
        }
    }
    val summary: String get() = if (incomplete) "联网核对未完成，已保留原始提示词"
        else "标签核对完成；未匹配词保留"
}

fun normalizeTag(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
    .replace(Regex("\\s+"), "_")

fun categoryTitle(category: Int?): String = when (category) {
    0 -> "常规"; 1 -> "画师"; 3 -> "作品"; 4 -> "角色"; 5 -> "元信息"; else -> "未知类别"
}

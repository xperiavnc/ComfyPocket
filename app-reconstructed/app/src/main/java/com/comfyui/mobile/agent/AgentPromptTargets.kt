package com.comfyui.mobile.agent

/** Resolve text inputs from conditioning links, not node order or the length of existing text. */
object AgentPromptTargets {
    private val textKeys = listOf("text", "text_g", "text_l", "string", "value")
    private val negativeKeys = setOf("negative_prompt", "negative", "negative_text", "text_negative")
    private val positiveKeys = setOf("positive_prompt", "positive", "positive_text", "text_positive")
    private val negativeTitle = Regex("(?i)(negative|\\bneg\\b|反向|负向|负面)")
    private val conditioningInput = Regex("(?i)(.*conditioning.*|cond(?:_\\d+)?|positive|negative|input|value)")

    private fun inputs(graph: Map<String, Any>, id: String): Map<*, *> =
        (graph[id] as? Map<*, *>)?.get("inputs") as? Map<*, *> ?: emptyMap<Any, Any>()

    private fun link(value: Any?): String? = (value as? List<*>)?.takeIf {
        it.size == 2 && it[0] is String && it[1] is Number
    }?.first() as? String

    private fun roots(graph: Map<String, Any>, key: String): List<String> =
        graph.keys.mapNotNull { link(inputs(graph, it)[key]) }

    private fun textFields(graph: Map<String, Any>, root: String): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        fun visit(id: String): List<Pair<String, String>> {
            if (!seen.add(id)) return emptyList()
            val node = graph[id] as? Map<*, *> ?: return emptyList()
            val type = node["class_type"] as? String ?: return emptyList()
            val values = inputs(graph, id)
            // A zeroed conditioning has no effective negative text; don't rewrite its positive source.
            if (type.equals("ConditioningZeroOut", ignoreCase = true)) return emptyList()
            if (type.contains("TextEncode", true) || type == "PrimitiveNode" || type.contains("String", true)) {
                return textKeys.flatMap { key ->
                    when (val value = values[key]) {
                        is String -> listOf(id to key)
                        else -> link(value)?.let(::visit).orEmpty()
                    }
                }
            }
            return values.entries.filter { conditioningInput.matches(it.key.toString()) }
                .flatMap { link(it.value)?.let(::visit).orEmpty() }
        }
        return visit(root).distinct()
    }

    private fun inlineFields(graph: Map<String, Any>, keys: Set<String>): List<Pair<String, String>> =
        graph.keys.flatMap { id -> keys.filter { inputs(graph, id)[it] is String }.map { id to it } }

    fun positive(graph: Map<String, Any>): List<Pair<String, String>> =
        (roots(graph, "positive").flatMap { textFields(graph, it) } + inlineFields(graph, positiveKeys)).distinct()

    fun negative(
        graph: Map<String, Any>,
        positiveTarget: Pair<String, String>? = null
    ): List<Pair<String, String>> {
        val linked = roots(graph, "negative")
        val direct = inlineFields(graph, negativeKeys)
        val candidates = if (linked.isNotEmpty()) {
            linked.flatMap { textFields(graph, it) } + direct
        } else {
            direct + graph.keys.filter { id ->
                val meta = (graph[id] as? Map<*, *>)?.get("_meta") as? Map<*, *>
                negativeTitle.containsMatchIn(meta?.get("title")?.toString().orEmpty())
            }.flatMap { textFields(graph, it) }
        }
        val positiveFields = positive(graph).toSet() + listOfNotNull(positiveTarget)
        return candidates.distinct().filterNot { it in positiveFields }
    }

    fun negativeContext(graph: Map<String, Any>): String? {
        val targets = negative(graph)
        if (targets.isEmpty()) return null
        return targets.map { inputs(graph, it.first)[it.second] as String }.distinct().joinToString("\n").take(6000)
    }
}

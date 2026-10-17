package com.comfyui.mobile.agent

interface AgentTool<I, O> {
    val name: String
    fun execute(input: I): Result<O>
}

enum class AgentFieldKind { TEXT, INTEGER, DECIMAL, BOOLEAN, IMAGE }
data class AgentFieldSpec(
    val nodeId: String,
    val key: String,
    val classType: String,
    val kind: AgentFieldKind,
    val options: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null
) {
    val address: String get() = "$nodeId:$key"
}

/** fields is an explicit capability list: prompt, first image and user-selected auxiliary inputs. */
data class AgentWorkflowSnapshot(val workflow: Map<String, Any>, val fields: List<AgentFieldSpec>)
data class AgentInspectedField(val spec: AgentFieldSpec, val value: Any)
data class AgentFieldChange(val nodeId: String, val key: String, val value: Any)
data class AgentFieldDiff(val nodeId: String, val key: String, val before: Any, val after: Any)
data class AgentPatchRequest(val snapshot: AgentWorkflowSnapshot, val changes: List<AgentFieldChange>)
data class AgentWorkflowPatch(val workflow: Map<String, Any>, val diff: List<AgentFieldDiff>)

private fun isLink(value: Any?): Boolean =
    value is List<*> && value.size == 2 && value[0] is String && value[1] is Number

private fun fieldValue(snapshot: AgentWorkflowSnapshot, spec: AgentFieldSpec): Any {
    val node = snapshot.workflow[spec.nodeId] as? Map<*, *> ?: error("节点不存在: ${spec.nodeId}")
    require(node["class_type"] == spec.classType) { "节点类型已变化: ${spec.nodeId}" }
    val inputs = node["inputs"] as? Map<*, *> ?: error("节点无 inputs: ${spec.nodeId}")
    require(inputs.containsKey(spec.key)) { "字段不存在: ${spec.address}" }
    val value = inputs[spec.key] ?: error("字段为空: ${spec.address}")
    require(!isLink(value)) { "禁止修改连线: ${spec.address}" }
    require(value is String || value is Number || value is Boolean) { "字段不是标量: ${spec.address}" }
    return value
}

class InspectWorkflowTool : AgentTool<AgentWorkflowSnapshot, List<AgentInspectedField>> {
    override val name = "inspect_workflow"
    override fun execute(input: AgentWorkflowSnapshot): Result<List<AgentInspectedField>> = runCatching {
        input.fields.map { AgentInspectedField(it, fieldValue(input, it)) }
    }
}

/**
 * Validate the entire patch before copying anything. Unknown fields, ports, node classes and
 * type/range/choice mismatches fail atomically; input maps and the original graph are never mutated.
 */
class PatchWorkflowTool : AgentTool<AgentPatchRequest, AgentWorkflowPatch> {
    override val name = "patch_workflow"

    override fun execute(input: AgentPatchRequest): Result<AgentWorkflowPatch> = runCatching {
        require(input.changes.isNotEmpty()) { "补丁为空" }
        val fields = input.snapshot.fields.associateBy { it.address }
        val addresses = input.changes.map { "${it.nodeId}:${it.key}" }
        require(addresses.distinct().size == addresses.size) { "同一字段重复修改" }
        val diffs = input.changes.map { change ->
            val address = "${change.nodeId}:${change.key}"
            val spec = fields[address] ?: error("字段未开放给 Agent: $address")
            val before = fieldValue(input.snapshot, spec)
            val after = validate(spec, before, change.value)
            AgentFieldDiff(change.nodeId, change.key, before, after)
        }
        val graph = input.snapshot.workflow.toMutableMap()
        for ((nodeId, changes) in diffs.groupBy { it.nodeId }) {
            @Suppress("UNCHECKED_CAST")
            val node = (graph.getValue(nodeId) as Map<String, Any>).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val inputs = (node.getValue("inputs") as Map<String, Any>).toMutableMap()
            changes.forEach { inputs[it.key] = it.after }
            node["inputs"] = inputs
            graph[nodeId] = node
        }
        AgentWorkflowPatch(graph, diffs)
    }

    private fun validate(spec: AgentFieldSpec, before: Any, value: Any): Any {
        val result: Any = when (spec.kind) {
            AgentFieldKind.TEXT, AgentFieldKind.IMAGE -> {
                require(before is String && value is String) { "需要文本: ${spec.address}" }
                require(spec.kind != AgentFieldKind.IMAGE || value.isNotBlank()) { "图像文件名为空" }
                value
            }
            AgentFieldKind.BOOLEAN -> {
                require(before is Boolean && value is Boolean) { "需要布尔值: ${spec.address}" }
                value
            }
            AgentFieldKind.INTEGER, AgentFieldKind.DECIMAL -> {
                require(before is Number && value is Number) { "需要数字: ${spec.address}" }
                val number = value.toDouble()
                require(number.isFinite()) { "数字不是有限值: ${spec.address}" }
                require(spec.min == null || number >= spec.min) { "低于最小值: ${spec.address}" }
                require(spec.max == null || number <= spec.max) { "超过最大值: ${spec.address}" }
                if (spec.kind == AgentFieldKind.INTEGER) {
                    require(value is Long || value is Int ||
                        (number >= Long.MIN_VALUE.toDouble() && number < Long.MAX_VALUE.toDouble() &&
                            number == value.toLong().toDouble())) { "需要整数: ${spec.address}" }
                    val long = value.toLong()
                    if (before is Int) {
                        require(long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "整数溢出" }
                        long.toInt()
                    } else long
                } else number
            }
        }
        require(spec.options.isNullOrEmpty() || result in spec.options) { "不在允许选项内: ${spec.address}" }
        return result
    }
}

/** Typed entry points, not a free-form command interpreter or native LLM tool-calling protocol. */
class AgentTools(
    val inspectWorkflow: InspectWorkflowTool = InspectWorkflowTool(),
    val patchWorkflow: PatchWorkflowTool = PatchWorkflowTool()
)

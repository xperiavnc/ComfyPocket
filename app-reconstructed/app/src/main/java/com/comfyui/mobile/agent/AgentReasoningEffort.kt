package com.comfyui.mobile.agent

/** DEFAULT deliberately omits the wire field for older/non-reasoning compatible endpoints. */
enum class AgentReasoningEffort(val wireValue: String?, val label: String) {
    DEFAULT(null, "跟随模型"),
    NONE("none", "关闭"),
    MINIMAL("minimal", "极低"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    XHIGH("xhigh", "极高"),
    MAX("max", "Max");

    companion object {
        fun fromStored(value: String?): AgentReasoningEffort =
            entries.firstOrNull { it.wireValue == value?.lowercase() } ?: DEFAULT
    }
}

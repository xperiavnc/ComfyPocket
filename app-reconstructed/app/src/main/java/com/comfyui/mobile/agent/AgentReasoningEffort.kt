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

        /**
         * The decision step only judges "ask or continue", so it never needs deep reasoning:
         * anything above LOW (including "follow model") is capped at LOW.
         */
        fun forDecision(effort: AgentReasoningEffort): AgentReasoningEffort =
            if (effort in listOf(NONE, MINIMAL, LOW)) effort else LOW

        /** True when a 4xx error says the endpoint does not accept the reasoning_effort field/value. */
        fun rejectedBy(httpCode: Int?, detail: String?): Boolean {
            if (httpCode !in listOf(400, 422)) return false
            val text = detail.orEmpty().lowercase()
            return Regex("reasoning[_ ]?effort|reasoning").containsMatchIn(text) &&
                Regex("not support|unsupported|not allowed|unknown|unrecognized|invalid|extra|not permitted|must be|should be")
                    .containsMatchIn(text)
        }
    }
}

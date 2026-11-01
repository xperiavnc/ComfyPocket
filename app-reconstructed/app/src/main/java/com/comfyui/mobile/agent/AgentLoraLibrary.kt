package com.comfyui.mobile.agent

/** Result of re-reading the computer's LoRA index. */
data class AgentLoraRefresh(val loras: List<AgentLoraInfo>, val scanned: Boolean = false, val note: String = "")

/** One LoRA re-read from the computer, with its provider description filled in when possible. */
data class AgentLoraDetail(val info: AgentLoraInfo, val notice: String = "")

/** Outcome of an annotate_lora write. */
data class AgentLoraAnnotated(val info: AgentLoraInfo, val applied: List<String>, val skipped: List<String>)

/**
 * Live access to the computer's model index for the Agent's LoRA tools. Implementations throw readable
 * errors (offline, index changed, file moved); the toolbox then falls back to the phone's cached copy.
 */
interface AgentLoraLibrary {
    /** All LoRAs on the computer; [rescan] first asks the computer to look for new files. */
    suspend fun refresh(rescan: Boolean = false): AgentLoraRefresh

    /** Latest record of one LoRA, fetching the provider description on the phone when it is missing. */
    suspend fun detail(item: AgentLoraInfo): AgentLoraDetail

    /** Writes the Agent's note into the LoRA's model-center annotation (see [AgentLoraCatalog.merge]). */
    suspend fun annotate(item: AgentLoraInfo, note: AgentLoraNote): AgentLoraAnnotated
}

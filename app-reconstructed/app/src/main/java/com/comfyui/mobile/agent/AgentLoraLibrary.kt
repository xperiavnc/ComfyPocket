package com.comfyui.mobile.agent

/** Result of re-reading the computer's LoRA index. */
data class AgentLoraRefresh(val loras: List<AgentLoraInfo>, val scanned: Boolean = false, val note: String = "")

/** One LoRA re-read from the computer, with its provider description filled in when possible. */
data class AgentLoraDetail(val info: AgentLoraInfo, val notice: String = "")

/** Outcome of an annotate_lora write. */
data class AgentLoraAnnotated(val info: AgentLoraInfo, val applied: List<String>, val skipped: List<String>)

/** What one background [AgentLoraLibrary.complete] run did. */
data class AgentLoraCompletion(val scanStarted: Boolean = false, val fetched: Int = 0, val failed: Int = 0)

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

    /**
     * Re-reads the LoRA list when the phone's copy is missing or a few minutes old. Null when the copy is recent,
     * another re-read is still running or the read fails; the caller keeps the cache. A caller that cannot wait
     * lets it finish in the background, so the saved list still reaches the next turn.
     */
    suspend fun refreshIfStale(): AgentLoraRefresh? = null

    /**
     * Background follow-up so the next 【LoRA 速查】 shows more than file names: asks the computer to index
     * [loadable] files it does not know yet, and looks up the Civitai source of indexed LoRAs that have none.
     * Best effort: failures are counted, not thrown.
     */
    suspend fun complete(loadable: List<String>): AgentLoraCompletion = AgentLoraCompletion()
}

package com.comfyui.mobile.model

/**
 * Backup manifest (v2), always stored inside a `.cui.bak` container
 * (magic + gzip of an internal zip that holds this JSON as `manifest.json`
 * plus `saved_workflows/`, `ai_apps/`, and `tasks/task_store.json`).
 * Legacy standalone `.json` / `.zip` are not supported for import.
 */
data class ConfigBackupDto(
    /** Schema version: 1 = config JSON; 2 = full package; 3 = optional Agent workspace. */
    val version: Int = 3,
    val exportTime: Long = System.currentTimeMillis(),
    val appVersion: String = "",

    // 1. Servers
    val servers: List<ServerProfile>? = null,
    val currentServerId: String? = null,
    val savedIp: String? = null,
    val savedPort: String? = null,
    val savedScheme: String? = null,

    // 2. LLM + Agent prompts / lore
    val llmProfiles: List<LlmProfile>? = null,
    val currentLlmProfileId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentDrawPrompt: String? = null,
    /** null = older backup, ignored; an explicit empty object restores the no-rule state. */
    val agentRuleFiles: com.comfyui.mobile.agent.AgentRuleFiles? = null,
    val characterLores: List<CharacterLore>? = null,
    val favoriteArtists: List<String>? = null,
    val agentWorkflowSource: String? = null,
    val agentWorkflowId: String? = null,
    val agentWorkflowLabel: String? = null,
    /** chat / draw */
    val agentInteractionMode: String? = null,
    /**
     * Agent node bindings & workflow param overrides:
     * keys like agent_target_node_*, agent_artist_node_*, agent_aux_nodes_*, agent_wf_params_*.
     */
    val agentPrefBag: Map<String, String>? = null,

    // 3. Theme / gallery categories
    val themeMode: Int? = null,
    val themePaletteId: String? = null,
    val isAutoSave: Boolean? = null,
    val galleryCategories: List<GalleryCategoryDto>? = null,

    // Personal profile (dashboard card)
    val profileNickname: String? = null,
    val profileBio: String? = null,
    val profileQq: String? = null,

    // 4. Workflow inventory (content may live in the zip under saved_workflows/<relativePath>)
    val includeWorkflows: Boolean = false,
    val workflows: List<WorkflowBackupItem>? = null,

    // 5. Keys
    val includeKeys: Boolean = true,

    // 6. AI apps + cloud task history (files primarily in the zip)
    val includeAiApps: Boolean = false,
    val includeTaskHistory: Boolean = false,
    /** RH cloud workflow site/meta prefs: rh_wf_site:*, rh_wf_meta:*, rh_wf_meta@* */
    val rhWorkflowPrefBag: Map<String, String>? = null,
    /** JSON map of official upload name → site id */
    val rhUploadSitesJson: String? = null,
    val workspaceVersion: Int? = null
    ) {
    val rhKeysCount: Int
        get() = servers?.count { it.isRunningHub && it.effectiveRhApiKey.isNotBlank() } ?: 0

    val llmKeysCount: Int
        get() = llmProfiles?.count { it.apiKey.isNotBlank() } ?: 0

    val totalKeysCount: Int
        get() = rhKeysCount + llmKeysCount

    val hasKeys: Boolean
        get() = totalKeysCount > 0
}

data class GalleryCategoryDto(
    val id: String,
    val name: String
    )

data class WorkflowBackupItem(
    /** Basename, e.g. myflow.json (v1). */
    val fileName: String,
    /** File text when embedded in JSON; null when stored only in the zip. */
    val content: String? = null,
    /** Path under saved_workflows/, e.g. local/foo.json or cloud/bar.json */
    val relativePath: String? = null
    )

/** Export toggles for a full backup zip. */
data class BackupExportOptions(
    val includeKeys: Boolean = true,
    val includeWorkflows: Boolean = true,
    val includeAiApps: Boolean = true,
    val includeTaskHistory: Boolean = true,
    val includeAgentWorkspace: Boolean = true
    )

/** Import toggles. */
data class BackupRestoreOptions(
    val restoreWorkflows: Boolean = true,
    val restoreAiApps: Boolean = true,
    val restoreTaskHistory: Boolean = true,
    val restoreAgentBindings: Boolean = true,
    /** Explicit opt-in to replacement, exposed in the import confirmation dialog. */
    val restoreAgentWorkspace: Boolean = false
    )

/**
 * Parsed backup ready for the confirm dialog.
 * [stagingDir] holds extracted package entries (delete after restore/dismiss).
 * [hasPackage] is true when workflows/AI apps/tasks came from the container payload.
 */
data class ParsedBackup(
    val dto: ConfigBackupDto,
    val stagingDir: java.io.File? = null,
    val hasPackage: Boolean = false,
    val workspaceConversationCount: Int? = null
    )

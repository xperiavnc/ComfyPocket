package com.comfyui.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.comfyui.mobile.backend.BackendKind
import com.comfyui.mobile.backend.CostSource
import com.comfyui.mobile.backend.TaskCost
import com.comfyui.mobile.backend.TaskError
import com.comfyui.mobile.backend.TaskOutput
import com.comfyui.mobile.backend.TaskState
import com.comfyui.mobile.network.RhTaskRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One generation task. Fields are nullable / defaulted so older JSON always loads.
 * [backend] / [state] hold enum names (see [backendKind] / [taskState]).
 */
data class TaskRecord(
    val localId: String,
    val serverId: String? = null,
    val backend: String? = null,
    /** RunningHub taskId, or the ComfyUI prompt_id (also for RH proxy workflows). */
    val remoteId: String? = null,
    val state: String? = null,
    val title: String? = null,
    val workflowKey: String? = null,
    val appId: String? = null,
    val webappId: String? = null,
    val gpuTier: String? = null,
    val instanceType: String? = null,
    val outputs: List<TaskOutput>? = null,
    val cost: TaskCost? = null,
    val error: TaskError? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val finishedAt: Long? = null,
    /** "ai_app" / "official_workflow" (official task API), "proxy_workflow" (legacy proxy), "legacy_prefs" (migrated). */
    val origin: String? = null,
    /**
     * RunningHubSite.id ("cn" / "ai") the task was submitted to; it is only ever polled on that site.
     * null = older record: treated as its profile's site (stamped with the old site when that profile's
     * site is edited, so it is never polled on the new one).
     */
    val rhSite: String? = null,
    /** First 16 hex chars of SHA-256(API key) used at submit; polling is skipped when the profile's key no longer matches. */
    val rhKeySig: String? = null,
    /** Durable Room ownership; nullable for pre-workspace tasks. */
    val agentExecutionId: String? = null,
    val agentConversationId: String? = null
    ) {
    val backendKind: BackendKind? get() = BackendKind.fromName(backend)
    val taskState: TaskState get() = TaskState.fromName(state) ?: TaskState.LOST

    /** Current RunningHub site's records: stamped site, or unmarked rows of this profile. */
    fun matchesRhSite(siteId: String?, serverId: String?): Boolean {
        if (backend != BackendKind.RUNNINGHUB.name) return false
        val stamped = rhSite?.takeIf { it.isNotBlank() }
        if (stamped != null) return stamped == siteId
        return !serverId.isNullOrBlank() && this.serverId == serverId
    }
}

/** Adapter for the cloud task records screen. */
fun TaskRecord.toRhView(): RhTaskRecord {
    val c = cost
    val realCost = c != null && c.coins != null && (c.source == CostSource.API || c.source == CostSource.BALANCE_DELTA)
    val cleanTitle = title
        ?.removePrefix("Agent · ")
        ?.removePrefix("Agent ·")
        ?.removePrefix("Agent: ")
        ?.removePrefix("Agent:")
        ?.removeSuffix(".json")
        ?.removeSuffix(".JSON")
    return RhTaskRecord(
        taskId = remoteId ?: localId,
        costCoins = c?.coins ?: 0.0,
        taskStatus = taskState.label,
        timestamp = if (createdAt > 0) createdAt else updatedAt,
        taskName = cleanTitle,
        costEstimated = !realCost,
        gpuTier = gpuTier,
        errorMessage = error?.message,
        localId = localId,
        costSource = c?.source?.name,
        costMoney = c?.money,
        durationSec = c?.durationSec,
        channel = if (origin == "ai_app" || origin == "official_workflow") "official_api" else "proxy"
    )
}

/**
 * Persisted task list: a JSON file under filesDir guarded by a Mutex (no extra dependencies).
 * Writes are atomic (tmp file + rename).
 */
class TaskStore private constructor(private val file: File) {

    private val gson = Gson()
    private val mutex = Mutex()
    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()

    @Volatile
    private var loaded = false

    private fun readFile(): List<TaskRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<TaskRecord>>() {}.type
            val raw: List<TaskRecord?> = gson.fromJson(file.readText(), type) ?: emptyList()
            @Suppress("SENSELESS_COMPARISON")
            raw.filterNotNull().filter { (it.localId as String?) != null }
        } catch (e: Exception) {
            Log.w(TAG, "task store unreadable, keeping file untouched: ${e.message}")
            // Never overwrite an unreadable store: move it aside once so it can be inspected.
            runCatching {
                val aside = File(file.parentFile, file.name + ".unreadable_" + System.currentTimeMillis())
                file.copyTo(aside, overwrite = false)
            }
            emptyList()
        }
    }

    private fun writeFile(list: List<TaskRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gson.toJson(list))
        if (!tmp.renameTo(file)) {
            file.writeText(gson.toJson(list))
            tmp.delete()
        }
    }

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!loaded) {
                    _tasks.value = readFile().sortedByDescending { it.createdAt }
                    loaded = true
                }
            }
        }
    }

    private fun trim(list: List<TaskRecord>): List<TaskRecord> {
        if (list.size <= MAX_RECORDS) return list
        // Drop the oldest terminal records first; never drop in-flight ones.
        val sorted = list.sortedByDescending { it.createdAt }
        val active = sorted.filter { !it.taskState.isTerminal }
        val terminal = sorted.filter { it.taskState.isTerminal }.take((MAX_RECORDS - active.size).coerceAtLeast(0))
        return (active + terminal).sortedByDescending { it.createdAt }
    }

    suspend fun upsert(record: TaskRecord) {
        ensureLoaded()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val r = record.copy(
                    createdAt = if (record.createdAt > 0) record.createdAt else now,
                    updatedAt = now
    )
                val next = trim(listOf(r) + _tasks.value.filterNot { it.localId == r.localId })
                writeFile(next)
                _tasks.value = next
            }
        }
    }

    suspend fun update(localId: String, transform: (TaskRecord) -> TaskRecord): TaskRecord? {
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val cur = _tasks.value.firstOrNull { it.localId == localId } ?: return@withLock null
                val updated = transform(cur).copy(localId = cur.localId, updatedAt = System.currentTimeMillis())
                val next = _tasks.value.map { if (it.localId == localId) updated else it }
                writeFile(next)
                _tasks.value = next
                updated
            }
        }
    }

    /** RUNNINGHUB-backend tasks only, mapped for the cloud records screen. */
    fun runningHubRecords(scope: kotlinx.coroutines.CoroutineScope): StateFlow<List<RhTaskRecord>> =
        _tasks.map { list -> list.filter { it.backend == BackendKind.RUNNINGHUB.name }.map { it.toRhView() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun find(localId: String): TaskRecord? = _tasks.value.firstOrNull { it.localId == localId }

    fun findByRemote(kind: BackendKind, remoteId: String): TaskRecord? =
        _tasks.value.firstOrNull { it.backend == kind.name && it.remoteId == remoteId }

    fun inFlight(kind: BackendKind, serverId: String?): List<TaskRecord> =
        _tasks.value.filter { it.backend == kind.name && !it.taskState.isTerminal && (serverId == null || it.serverId == serverId) }

    /** SUBMITTING / QUEUED / RUNNING only (not PAUSED). */
    fun active(kind: BackendKind, serverId: String?): List<TaskRecord> =
        _tasks.value.filter { it.backend == kind.name && it.taskState.isActive && (serverId == null || it.serverId == serverId) }

    /** Cancels in-flight rows of [workflowKey]; completed / failed records are kept. */
    suspend fun cancelInFlightByWorkflowKey(workflowKey: String): Int {
        if (workflowKey.isBlank()) return 0
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                var n = 0
                val next = _tasks.value.map { rec ->
                    if (rec.workflowKey == workflowKey && !rec.taskState.isTerminal) {
                        n++
                        rec.copy(
                            state = TaskState.CANCELLED.name,
                            error = TaskError(message = "工作流已删除，已停止跟踪"),
                            finishedAt = now,
                            updatedAt = now
    )
                    } else rec
                }
                if (n > 0) {
                    writeFile(next)
                    _tasks.value = next
                }
                n
            }
        }
    }

    suspend fun removeMatching(predicate: (TaskRecord) -> Boolean): Int {
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val before = _tasks.value
                val next = before.filterNot(predicate)
                val removed = before.size - next.size
                if (removed > 0) {
                    writeFile(next)
                    _tasks.value = next
                }
                removed
            }
        }
    }

    suspend fun stampKeySigIfMissing(serverId: String, siteId: String?, sig: String): Int {
        if (sig.isBlank()) return 0
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                var n = 0
                val next = _tasks.value.map { rec ->
                    val match = rec.serverId == serverId && rec.backend == BackendKind.RUNNINGHUB.name &&
                        (siteId == null || rec.rhSite.isNullOrBlank() || rec.rhSite == siteId) &&
                        rec.rhKeySig.isNullOrBlank() && !rec.taskState.isTerminal
                    if (match) {
                        n++
                        rec.copy(rhKeySig = sig)
                    } else rec
                }
                if (n > 0) {
                    writeFile(next)
                    _tasks.value = next
                }
                n
            }
        }
    }

    /**
     * Marks matching non-terminal official-API tasks as PAUSED so they are not polled on the wrong
     * site / API key. [siteId] null = every site of [serverId]. [onlyIfKeySigNot] pauses records whose
     * rhKeySig is missing or different (a key change).
     */
    suspend fun pauseTracking(
        serverId: String,
        siteId: String?,
        reason: String,
        onlyIfKeySigNot: String? = null
    ): Int {
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                var n = 0
                val now = System.currentTimeMillis()
                val next = _tasks.value.map { rec ->
                    val matchServer = rec.serverId == serverId && rec.backend == BackendKind.RUNNINGHUB.name
                    val matchSite = siteId == null || rec.rhSite.isNullOrBlank() || rec.rhSite == siteId
                    val matchKey = onlyIfKeySigNot == null || rec.rhKeySig.isNullOrBlank() || rec.rhKeySig != onlyIfKeySigNot
                    if (matchServer && matchSite && matchKey && !rec.taskState.isTerminal && rec.taskState != TaskState.PAUSED) {
                        n++
                        rec.copy(
                            state = TaskState.PAUSED.name,
                            error = TaskError(message = reason),
                            updatedAt = now
    )
                    } else rec
                }
                if (n > 0) {
                    writeFile(next)
                    _tasks.value = next
                }
                n
            }
        }
    }

    /**
     * Records of [serverId] without a site get [siteId] (the profile's site BEFORE an in-place site edit).
     * Returns how many of them are still in flight.
     */
    suspend fun stampSiteIfMissing(serverId: String, siteId: String): Int {
        ensureLoaded()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                var inFlight = 0
                var changed = false
                val next = _tasks.value.map {
                    if (it.serverId == serverId && it.rhSite.isNullOrBlank() && it.backend == BackendKind.RUNNINGHUB.name) {
                        changed = true
                        if (!it.taskState.isTerminal) inFlight++
                        it.copy(rhSite = siteId)
                    } else it
                }
                if (changed) {
                    writeFile(next)
                    _tasks.value = next
                }
                inFlight
            }
        }
    }

    /** Removes records of one backend kind only (user action "clear records"). */
    /**
     * Merge tasks from a backup JSON (same schema as the on-disk store).
     * On localId collision, keeps the record with the newer [TaskRecord.updatedAt].
     * @return number of backup records that were inserted or replaced.
     */
    suspend fun mergeFromBackupJson(json: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                _tasks.value = readFile().sortedByDescending { it.createdAt }
                loaded = true
            }
            val type = object : TypeToken<List<TaskRecord?>>() {}.type
            val incoming: List<TaskRecord> = try {
                @Suppress("UNCHECKED_CAST")
                (gson.fromJson<List<TaskRecord?>>(json, type) ?: emptyList())
                    .filterNotNull()
                    .filter { (it.localId as String?) != null }
            } catch (e: Exception) {
                Log.w(TAG, "backup task json unreadable: ${e.message}")
                emptyList()
            }
            if (incoming.isEmpty()) return@withLock 0
            val byId = _tasks.value.associateBy { it.localId }.toMutableMap()
            var touched = 0
            for (r in incoming) {
                val old = byId[r.localId]
                if (old == null || r.updatedAt >= old.updatedAt) {
                    byId[r.localId] = r
                    touched++
                }
            }
            val merged = trim(byId.values.toList())
            writeFile(merged)
            _tasks.value = merged
            touched
        }
    }

    suspend fun clear(kind: BackendKind) {
        ensureLoaded()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = _tasks.value.filterNot { it.backend == kind.name }
                writeFile(next)
                _tasks.value = next
            }
        }
    }

    /**
     * Non-destructive one-time import of the old `rh_task_records` pref (Phase 1 format).
     * The pref itself is left untouched; re-running is idempotent (stable localIds).
     */
    suspend fun migrateLegacyRhRecords(prefs: SharedPreferences): Int {
        ensureLoaded()
        if (prefs.getBoolean(MIGRATED_FLAG, false)) return 0
        val json = prefs.getString("rh_task_records", null)
        val legacy: List<RhTaskRecord> = try {
            if (json.isNullOrBlank()) emptyList()
            else {
                val type = object : TypeToken<List<RhTaskRecord?>>() {}.type
                (gson.fromJson<List<RhTaskRecord?>>(json, type) ?: emptyList()).filterNotNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "legacy rh_task_records unreadable: ${e.message}")
            emptyList()
        }
        var added = 0
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = _tasks.value.map { it.localId }.toHashSet()
                val imported = legacy.mapNotNull { r ->
                    @Suppress("SENSELESS_COMPARISON")
                    if ((r.taskId as String?) == null) return@mapNotNull null
                    val localId = "legacy_rh_${r.taskId}"
                    if (localId in existing) return@mapNotNull null
                    val state = when (r.taskStatus?.trim()) {
                        "已完成", "完成", "成功" -> TaskState.SUCCESS
                        "失败" -> TaskState.FAILED
                        "已中断", "已取消" -> TaskState.CANCELLED
                        "状态未知" -> TaskState.LOST
                        else -> TaskState.SUCCESS
                    }
                    TaskRecord(
                        localId = localId,
                        serverId = null,
                        backend = BackendKind.RUNNINGHUB.name,
                        remoteId = r.taskId,
                        state = state.name,
                        title = r.taskName,
                        gpuTier = r.gpuTier,
                        cost = if (!r.costEstimated) TaskCost(coins = r.costCoins, source = CostSource.BALANCE_DELTA) else null,
                        error = r.errorMessage?.let { TaskError(message = it) },
                        createdAt = r.timestamp,
                        updatedAt = r.timestamp,
                        finishedAt = r.timestamp,
                        origin = "legacy_prefs"
    )
                }
                added = imported.size
                if (imported.isNotEmpty()) {
                    val next = trim((_tasks.value + imported).sortedByDescending { it.createdAt })
                    writeFile(next)
                    _tasks.value = next
                }
            }
        }
        prefs.edit().putBoolean(MIGRATED_FLAG, true).apply()
        return added
    }

    companion object {
        private const val TAG = "TaskStore"
        private const val MAX_RECORDS = 500
        const val MIGRATED_FLAG = "task_store_rh_records_migrated_v1"

        @Volatile
        private var instance: TaskStore? = null

        fun get(context: Context): TaskStore =
            instance ?: synchronized(this) {
                instance ?: TaskStore(File(File(context.applicationContext.filesDir, "tasks"), "task_store.json"))
                    .also { instance = it }
            }
    }
}

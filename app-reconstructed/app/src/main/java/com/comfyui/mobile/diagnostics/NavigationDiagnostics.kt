package com.comfyui.mobile.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.comfyui.mobile.BuildConfig
import java.io.File
import java.time.Instant

/**
 * A small, local-only trace for returns that look like an app exit.
 *
 * Only fixed event names, route templates, booleans and framework exit codes are recorded.
 * Exception messages, request URLs, model names, prompts and credentials are deliberately omitted.
 * Diagnostics are best-effort: a full disk or a failed system query must never break navigation.
 */
object NavigationDiagnostics {
    private const val JOURNAL = "return_diagnostics.txt"
    private const val CRASH = "return_crash_summary.txt"
    private const val MAX_LINES = 200
    private const val MAX_CRASH_CHARS = 24_000
    private val lock = Any()
    @Volatile private var app: Context? = null

    enum class Event {
        SESSION_STARTED,
        DESTINATION_CHANGED,
        GESTURE_STARTED,
        GESTURE_IDLE,
        MARKET_BACK_SYSTEM,
        MARKET_BACK_TOOLBAR,
        MARKET_PAGE_DISPOSED,
        MARKET_REPOSITORY_CLOSING,
        MARKET_REPOSITORY_CLOSED,
        MARKET_REPOSITORY_CLOSE_FAILED,
        MARKET_RESOURCE_CLEANUP_FAILED,
        ACTIVITY_STOPPED,
        ACTIVITY_DESTROYED,
        JAVA_CRASH
    }

    fun start(context: Context) {
        app = context.applicationContext
        record(Event.SESSION_STARTED)
    }

    fun record(
        event: Event,
        route: String? = null,
        finishing: Boolean? = null,
        changingConfiguration: Boolean? = null
    ) {
        val context = app ?: return
        runCatching {
            val line = buildString {
                append(Instant.now()).append(' ').append(event.name)
                append(" build=").append(BuildConfig.VERSION_NAME)
                append('(').append(BuildConfig.VERSION_CODE).append(')')
                append(" api=").append(Build.VERSION.SDK_INT)
                if (route != null) append(" route=").append(routeTemplate(route))
                if (finishing != null) append(" finishing=").append(finishing)
                if (changingConfiguration != null) {
                    append(" changingConfiguration=").append(changingConfiguration)
                }
            }
            synchronized(lock) {
                val file = File(context.filesDir, JOURNAL)
                val previous = if (file.exists()) file.readLines().takeLast(MAX_LINES - 1)
                    else emptyList()
                file.writeText((previous + line).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun recordCrash(error: Throwable) {
        val context = app ?: return
        record(Event.JAVA_CRASH)
        runCatching {
            // Throwable.toString()/printStackTrace() include arbitrary messages and may reveal a URL.
            val text = buildString {
                append("time=").append(Instant.now()).append('\n')
                append("build=").append(BuildConfig.VERSION_NAME).append('\n')
                val seen = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<Throwable, Boolean>()
                )
                var cause: Throwable? = error
                var depth = 0
                while (cause != null && depth++ < 8 && seen.add(cause)) {
                    append("exception=").append(cause.javaClass.name).append('\n')
                    cause.stackTrace.take(48).forEach { frame ->
                        append("  at ").append(frame).append('\n')
                    }
                    cause = cause.cause
                }
            }.take(MAX_CRASH_CHARS)
            synchronized(lock) { File(context.filesDir, CRASH).writeText(text) }
        }
    }

    /** Call from an IO dispatcher when the user explicitly exports a report. */
    fun report(context: Context): String = buildString {
        appendLine("ComfyPocket return diagnostics")
        appendLine("Exported: ${Instant.now()}")
        appendLine("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android API: ${Build.VERSION.SDK_INT}")
        appendLine("No settings, keys, URLs, model names or prompts are included.")
        appendLine()
        appendLine("=== Recent return/lifecycle events (up to $MAX_LINES) ===")
        appendLine(read(context, JOURNAL))
        appendLine("=== Last captured Java exception (messages omitted; may be from an older session) ===")
        appendLine(read(context, CRASH))
        appendLine("=== System process exit history (newest first; compare timestamps) ===")
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exits.isEmpty()) appendLine("No retained process exit records.")
            exits.forEach { exit ->
                // description and traceInputStream may contain arbitrary app data: do not export them.
                appendLine(
                    "${Instant.ofEpochMilli(exit.timestamp)} " +
                        "reason=${exitReason(exit.reason)}(${exit.reason}) status=${exit.status}"
                )
            }
        }.onFailure { appendLine("System exit history query failed (${it.javaClass.simpleName}).") }
        appendLine()
        appendLine("Interpretation:")
        appendLine("- CRASH / CRASH_NATIVE: process crash, not a second navigation pop.")
        appendLine("- ACTIVITY_STOPPED finishing=false alone: backgrounding, not proof of a crash.")
        appendLine("- MARKET_BACK_* then DESTINATION_CHANGED route=dashboard: the page return ran.")
        appendLine("- No MARKET_BACK_*: the page handler was not reached; inspect gesture/lifecycle events.")
        appendLine("- An older system exit record is not evidence that the latest return crashed.")
    }

    private fun read(context: Context, name: String): String = runCatching {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, name)
            if (file.exists()) file.readText() else "(none)"
        }
    }.getOrDefault("(read failed)")

    private fun routeTemplate(route: String): String = when (route) {
        "控制面板" -> "dashboard"
        "控制器" -> "control"
        "画廊" -> "gallery"
        "AI应用" -> "ai_apps"
        "agent", "model_hub", "settings", "theme", "servers", "about", "console",
        "agent_settings", "cloud_tasks", "ai_app/{id}" -> route
        else -> "<other>"
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN"
    }
}

package com.comfyui.mobile.ui.state

import com.comfyui.mobile.model.ConsoleMessage
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.network.SecretRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, redacted console state, independent of Android and MainViewModel. */
internal class ConsoleLogStore(private val now: () -> Long = System::currentTimeMillis) {
    private val mutableMessages = MutableStateFlow<List<ConsoleMessage>>(emptyList())
    val messages = mutableMessages.asStateFlow()

    @Synchronized
    fun add(text: String, type: LogType = LogType.INFO) {
        val msg = ConsoleMessage(now(), SecretRedactor.redact(text), type)
        val current = mutableMessages.value
        mutableMessages.value = if (current.size >= 400) {
            current.drop(current.size - 399) + msg
        } else {
            current + msg
        }
    }

    fun render(metadata: List<Pair<String, Any?>>): String {
        val snapshot = mutableMessages.value
        if (snapshot.isEmpty()) return "(暂无日志)\n"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return buildString {
            appendLine("ComfyUI log export")
            appendLine("time=${sdf.format(Date(now()))}")
            for ((key, value) in metadata) appendLine("$key=$value")
            appendLine("-----")
            for (msg in snapshot) {
                append(sdf.format(Date(msg.timestamp)))
                append(" [")
                append(msg.type.name)
                append("] ")
                appendLine(msg.text)
            }
        }
    }

    fun export(directory: File, text: String): File {
        val file = File(directory, "comfy_log_${now()}.txt")
        file.writeText(text, Charsets.UTF_8)
        add("已导出日志: ${file.name}")
        return file
    }

    @Synchronized
    fun clear() {
        mutableMessages.value = emptyList()
        add("日志已清空")
    }
}

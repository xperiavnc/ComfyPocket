package com.comfyui.mobile.taglib

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking

/** Full catalogue is a repository fixture, never an Android asset. */
object TagLibraryTestData {
    val source: File get() = listOf(File("../tag-library"), File("tag-library"))
        .first { File(it, "manifest.json").isFile }

    fun install(context: Context, library: LocalTagLibrary) {
        val manifest = File(source, "manifest.json").readText()
        val info = JsonParser.parseString(manifest).asJsonObject
        val directory = File(context.noBackupFilesDir, "tag-library").apply { mkdirs() }
        File(source, "catalogue-v1.db").copyTo(
            File(directory, "catalogue-${info["sha256"].asString.take(16)}.db"), overwrite = true)
        File(directory, "active.json").writeText(manifest)
        runBlocking { library.store.refresh() }
    }
}

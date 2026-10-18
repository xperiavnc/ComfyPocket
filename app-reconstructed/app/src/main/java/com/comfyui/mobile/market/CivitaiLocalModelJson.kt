package com.comfyui.mobile.market

import com.comfyui.mobile.modelcenter.ModelPreview
import com.comfyui.mobile.modelcenter.ModelProvider
import com.comfyui.mobile.modelcenter.modelHash
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** File identity comes from the full SHA-256, never from a filename or a primary-file guess. */
internal object CivitaiLocalModelJson {
    fun match(text: String, expectedHash: String): ModelProvider {
        val hash = requireNotNull(modelHash(expectedHash)) { "请先同步完整的模型 SHA-256" }
        val root = JsonParser.parseString(text).asJsonObject
        val version = CivitaiJson.version(text)
        val modelId = root.number("modelId")
        require(modelId > 0) { "Civitai 未返回模型身份" }
        val file = root.get("files")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
            ?.firstOrNull {
                modelHash(it.obj("hashes")?.text("SHA256")) == hash && it.number("id") > 0
            }
        requireNotNull(file) { "Civitai 未返回与本地 SHA-256 相同的文件" }
        return ModelProvider(
            provider = "civitai", matchedHash = hash, modelId = modelId,
            versionId = version.id, fileId = file.number("id"),
            name = root.obj("model")?.text("name").orEmpty(),
            version = version.name, baseModel = version.baseModel, trainedWords = version.trainedWords,
            description = version.description,
            source = "https://civitai.com/models/$modelId?modelVersionId=${version.id}",
            images = version.images.map { ModelPreview(it.url) }
        )
    }

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.number(key: String): Long = text(key).toLongOrNull() ?: 0
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}

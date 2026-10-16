package com.comfyui.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys at rest: AES-GCM in AndroidKeystore, ciphertext in [Context.getNoBackupFilesDir]
 * (excluded from Auto Backup and device-to-device transfer). Prefs / config backups never hold keys.
 */
class SecretStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, FILE_NAME)
    private val lock = Any()

    fun putRhKey(serverId: String, plaintext: String) = put(rhAlias(serverId), plaintext)

    fun getRhKey(serverId: String): String? = get(rhAlias(serverId))

    fun putLlmKey(profileId: String, plaintext: String) = put(llmAlias(profileId), plaintext)

    fun getLlmKey(profileId: String): String? = get(llmAlias(profileId))

    fun removeRhKey(serverId: String) = remove(rhAlias(serverId))

    fun removeLlmKey(profileId: String) = remove(llmAlias(profileId))

    fun putCivitaiKey(plaintext: String) = put("market:civitai", plaintext)

    fun getCivitaiKey(): String = get("market:civitai").orEmpty()

    fun putCivitaiProxyPassword(plaintext: String) =
        put("market:proxy-password", plaintext, trim = false)

    fun getCivitaiProxyPassword(): String = get("market:proxy-password").orEmpty()

    private fun put(alias: String, plaintext: String, trim: Boolean = true) {
        val value = if (trim) plaintext.trim() else plaintext
        synchronized(lock) {
            val map = readMap()
            if (value.isEmpty()) {
                map.remove(alias)
            } else {
                map[alias] = encrypt(value)
            }
            writeMap(map)
        }
    }

    private fun get(alias: String): String? {
        synchronized(lock) {
            val blob = readMap()[alias] ?: return null
            return decrypt(blob)
        }
    }

    private fun remove(alias: String) {
        synchronized(lock) {
            val map = readMap()
            if (map.remove(alias) != null) writeMap(map)
        }
    }

    private fun readMap(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val obj = JSONObject(file.readText())
            val out = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "secrets unreadable: ${e.javaClass.simpleName}")
            mutableMapOf()
        }
    }

    private fun writeMap(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(obj.toString())
            tmp.delete()
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
    )
        return gen.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + 1 + iv.size + ct.size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ct)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? {
        return try {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(packed)
            val ver = buf.get()
            if (ver != VERSION) return null
            val ivLen = buf.get().toInt() and 0xFF
            if (ivLen < 12 || ivLen > 32 || buf.remaining() <= ivLen) return null
            val iv = ByteArray(ivLen)
            buf.get(iv)
            val ct = ByteArray(buf.remaining())
            buf.get(ct)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val FILE_NAME = "secrets.v1.json"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "comfy.secret.aes.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1

        @Volatile
        private var instance: SecretStore? = null

        fun get(context: Context): SecretStore {
            val cached = instance
            if (cached != null) return cached
            return synchronized(this) {
                instance ?: SecretStore(context).also { instance = it }
            }
        }

        fun rhAlias(serverId: String) = "rh:$serverId"

        fun llmAlias(profileId: String) = "llm:$profileId"

        /** Short fingerprint of an API key, stored on task records so a later key change can stop polling. */
        fun keySig(plaintext: String?): String? {
            val t = plaintext?.trim().orEmpty()
            if (t.isBlank()) return null
            val hex = MessageDigest.getInstance("SHA-256").digest(t.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b) }
            return hex.take(16)
        }
    }
}

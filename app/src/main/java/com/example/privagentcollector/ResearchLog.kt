package com.example.privagentcollector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object ResearchLog {
    private const val KEY_ALIAS = "privagent_raw_v2"
    private fun directory(context: Context) = File(context.filesDir, "raw_encrypted").apply {
        check(exists() || mkdirs()) { "Cannot create data directory" }
    }
    private fun files(context: Context) = directory(context).listFiles().orEmpty()
        .filter { it.name.endsWith(".enc") }.sortedBy { it.name }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    private fun remove(file: File) { check(!file.exists() || file.delete()) { "Cannot remove data" } }
    @Synchronized fun maintain(context: Context) {
        val now = System.currentTimeMillis()
        files(context).filter { StoragePolicy.expired(it.lastModified(), now) }.forEach(::remove)
        var total = files(context).sumOf { it.length() }
        for (file in files(context)) {
            if (total <= StoragePolicy.MAX_TOTAL_BYTES) break
            total -= file.length()
            remove(file)
        }
    }
    @Synchronized fun append(context: Context, source: DataSource, session: String,
        event: String, payload: JSONObject): Boolean {
        if (!CollectionControl.allowed(context, source, session)) return false
        val record = JSONObject().put("schemaVersion", 2).put("recordId", UUID.randomUUID().toString())
            .put("ts", System.currentTimeMillis())
            .put("sessionId", session).put("source", event).put("sensitivity", source.sensitivity)
            .put("payload", payload).toString().toByteArray(Charsets.UTF_8)
        val encrypted = (EncryptedRecordCodec.encrypt(key(), record) + "\n").toByteArray(Charsets.UTF_8)
        maintain(context)
        var total = files(context).sumOf { it.length() }
        for (file in files(context)) {
            if (total + encrypted.size <= StoragePolicy.MAX_TOTAL_BYTES) break
            total -= file.length()
            remove(file)
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val previous = files(context).lastOrNull()
        val file = if (previous != null && previous.name.startsWith(day) &&
            !StoragePolicy.rotate(previous.length(), encrypted.size.toLong())) previous
            else File(directory(context), "${day}-${System.currentTimeMillis()}-${System.nanoTime()}.enc")
        file.appendBytes(encrypted)
        RealtimeRuntime.publish(session, event, record + byteArrayOf('\n'.code.toByte()))
        return true
    }
    @Synchronized fun size(context: Context): Long = files(context).sumOf { it.length() }
    fun legacyExists(context: Context) = File(context.filesDir, "raw_events.ndjson").exists()
    @Synchronized fun hasData(context: Context): Boolean {
        maintain(context)
        return files(context).isNotEmpty() || legacyExists(context)
    }
    /** Caller stops collection before clearing. Shared IO worker orders writes and deletion. */
    @Synchronized fun clear(context: Context) {
        CollectionControl.stop()
        files(context).forEach(::remove)
        remove(File(context.filesDir, "raw_events.ndjson"))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }
    /** Caller selects an explicit destination. No plaintext copy is stored inside the app. */
    @Synchronized fun export(context: Context, output: OutputStream): Int {
        maintain(context)
        var count = 0
        val encryptedFiles = files(context)
        if (encryptedFiles.isNotEmpty()) {
            val secretKey = key()
            // Authenticate all records first so corrupt data cannot yield a false success.
            encryptedFiles.forEach { file -> file.forEachLine { line ->
                if (line.isNotBlank()) EncryptedRecordCodec.decrypt(secretKey, line)
            } }
            encryptedFiles.forEach { file -> file.forEachLine { line ->
                if (line.isNotBlank()) {
                    output.write(EncryptedRecordCodec.decrypt(secretKey, line))
                    output.write('\n'.code)
                    count++
                }
            } }
        }
        // v0.1 plaintext is preserved for deliberate export or removal, never silently deleted.
        val legacy = File(context.filesDir, "raw_events.ndjson")
        if (legacy.exists()) legacy.forEachLine { line ->
            if (line.isNotBlank()) {
                output.write(line.toByteArray(Charsets.UTF_8)); output.write('\n'.code); count++
            }
        }
        output.flush()
        return count
    }
}

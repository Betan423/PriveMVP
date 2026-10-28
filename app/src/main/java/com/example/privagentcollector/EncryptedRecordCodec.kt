package com.example.privagentcollector

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned, independently authenticated records; the caller provides a Keystore key. */
object EncryptedRecordCodec {
    private val aad = "PrivAgent/raw/v1".toByteArray(Charsets.UTF_8)
    const val MAX_PLAINTEXT_BYTES = 1024 * 1024
    fun encrypt(key: SecretKey, plaintext: ByteArray): String {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Record too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return "1|${Base64.getEncoder().encodeToString(cipher.iv)}|${Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))}"
    }
    fun decrypt(key: SecretKey, record: String): ByteArray {
        require(record.length <= 2 * MAX_PLAINTEXT_BYTES) { "Encrypted record too large" }
        val parts = record.split('|')
        require(parts.size == 3 && parts[0] == "1") { "Unsupported record format" }
        val iv = Base64.getDecoder().decode(parts[1])
        require(iv.size == 12) { "Invalid nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(Base64.getDecoder().decode(parts[2]))
    }
}

package com.example.privagentcollector

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class EncryptedRecordCodecTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun chineseDataRoundTripsWithoutPlaintextOnDisk() {
        val key = key()
        val text = """{"source":"contacts","payload":{"name":"測試聯絡人","phone":"0900000000"}}"""
        val record = EncryptedRecordCodec.encrypt(key, text.toByteArray())
        assertFalse(record.contains("測試聯絡人"))
        assertEquals(text, String(EncryptedRecordCodec.decrypt(key, record), Charsets.UTF_8))
    }
    @Test fun repeatedPayloadUsesDifferentNonces() {
        val key = key()
        val first = EncryptedRecordCodec.encrypt(key, "same".toByteArray())
        val second = EncryptedRecordCodec.encrypt(key, "same".toByteArray())
        assertNotEquals(first.split('|')[1], second.split('|')[1])
        assertNotEquals(first, second)
    }
    @Test fun tamperedCiphertextIsRejected() {
        val key = key()
        val record = EncryptedRecordCodec.encrypt(key, "payload".toByteArray()).split('|')
        val bytes = Base64.getDecoder().decode(record[2])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val modified = "${record[0]}|${record[1]}|${Base64.getEncoder().encodeToString(bytes)}"
        assertThrows(Exception::class.java) { EncryptedRecordCodec.decrypt(key, modified) }
    }
    @Test fun wrongKeyCannotDecrypt() {
        val record = EncryptedRecordCodec.encrypt(key(), "secret".toByteArray())
        assertThrows(Exception::class.java) { EncryptedRecordCodec.decrypt(key(), record) }
    }
    @Test fun unknownFormatIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EncryptedRecordCodec.decrypt(key(), "2|AA==|AA==") }
    }
    @Test fun oversizedRecordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedRecordCodec.encrypt(key(), ByteArray(EncryptedRecordCodec.MAX_PLAINTEXT_BYTES + 1))
        }
    }
}

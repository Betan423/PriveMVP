package com.example.privagentcollector

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class AssistClientTest {
    private var server: ServerSocket? = null
    private val received = mutableListOf<String>()

    /** Minimal one-request-at-a-time HTTP server; android.jar has no com.sun.net.httpserver. */
    private fun serve(status: Int, body: (String) -> String): String {
        val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use {
                    val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(":").trim().toInt()
                    }
                    // Content-Length counts bytes; read chars until the UTF-8 byte count matches.
                    val request = StringBuilder()
                    while (request.toString().toByteArray(Charsets.UTF_8).size < length) request.append(input.read().toChar())
                    received += request.toString()
                    val bytes = body(request.toString()).toByteArray(Charsets.UTF_8)
                    val out = it.getOutputStream()
                    out.write(("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}"
    }

    @After fun stop() { server?.close() }

    private fun aliasOf(request: String) = Regex("\"task_id_alias\":\"(TASK_[A-Z]{8})\"").find(request)!!.groupValues[1]

    private fun reply(alias: String, level: String, codes: String, kind: String = "GEMINI_PROXY") =
        """{"task_id_alias":"$alias","risk_level":"$level","reason_codes":[$codes],"planner":{"kind":"$kind","fallback_reason":null}}"""

    @Test fun geminiAnswerIsShownWithFixedWordingCodesOnly() {
        val url = serve(200) { reply(aliasOf(it), "high", "\"ASKS_TRANSFER\",\"MADE_UP_CODE\"") }
        val packet = MessageGuard.buildPacket("明天下午一起吃飯。", "pasted", false)
        val outcome = AssistClient.check(url, "", packet, cloudConsent = true)
        assertEquals(MessageGuard.Level.HIGH, outcome.level)
        assertEquals(listOf("ASKS_TRANSFER"), outcome.reasonCodes)
        assertTrue(outcome.source.contains("Gemini"))
        assertEquals(packet.json, received.single())
    }

    @Test fun cloudCannotLowerTheLocalRiskOnThePhone() {
        val url = serve(200) { reply(aliasOf(it), "verify", "\"NO_STRONG_SIGNAL\"") }
        val packet = MessageGuard.buildPacket("我是客服，請提供驗證碼", "pasted", false)
        val outcome = AssistClient.check(url, "", packet, cloudConsent = true)
        assertEquals(MessageGuard.Level.HIGH, outcome.level)
        assertFalse(outcome.reasonCodes.contains("NO_STRONG_SIGNAL"))
    }

    @Test fun mismatchedAliasFallsBackToLocalRules() {
        val url = serve(200) { reply("TASK_AAAAAAAA", "insufficient", "\"CONTEXT_INSUFFICIENT\"") }
        val packet = MessageGuard.buildPacket("請立即轉帳，否則停權", "pasted", false)
        val outcome = AssistClient.check(url, "", packet, cloudConsent = true)
        assertEquals(MessageGuard.Level.HIGH, outcome.level)
        assertTrue(outcome.source.startsWith("手機本機規則"))
    }

    @Test fun receiverRejectionIsExplained() {
        val url = serve(422) { """{"error":{"code":"FORBIDDEN_VALUE_IN_PACKET","message":"x"}}""" }
        val outcome = AssistClient.check(url, "", MessageGuard.buildPacket("明天下午大家一起見面吧", "pasted", false), true)
        assertTrue(outcome.source.contains("疑似個資"))
    }

    @Test fun unreachableReceiverFallsBack() {
        val url = serve(200) { "" }
        server!!.close()
        val outcome = AssistClient.check(url, "", MessageGuard.buildPacket("明天下午大家一起見面吧", "pasted", false), true)
        assertEquals(MessageGuard.Level.VERIFY, outcome.level)
        assertTrue(outcome.source, outcome.source.startsWith("手機本機規則"))
    }

    @Test fun withoutConsentNothingIsSent() {
        val url = serve(200) { "" }
        val outcome = AssistClient.check(url, "", MessageGuard.buildPacket("明天下午大家一起見面吧", "pasted", false), false)
        assertTrue(received.isEmpty())
        assertTrue(outcome.source.contains("沒有送出"))
    }
}

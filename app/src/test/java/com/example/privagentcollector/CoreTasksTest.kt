package com.example.privagentcollector

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoreTasksTest {
    private fun golden(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8)
        .readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split("\t") }
    private fun opt(value: String) = value.takeIf { it != "-" }

    @Test fun interpreterMatchesPythonReference() {
        val cases = golden("core_interpret_golden.tsv")
        assertTrue(cases.size > 400)
        for (c in cases) {
            val got = CoreTasks.interpret(c[0])
            assertEquals(c[0], listOf(c[1], opt(c[2]), opt(c[3]), opt(c[4]), opt(c[5]), opt(c[6])),
                listOf(got.intent, got.relation, got.selection, got.symptomCategory, got.symptomDetail, got.requestedScope))
        }
    }

    @Test fun rescueRuleMatchesPythonReference() {
        val cases = golden("core_rescue_golden.tsv")
        assertTrue(cases.size > 1000)
        for (c in cases) {
            val state = CoreTasks.RescueState(c[0], c[1], c[2], c[3], c[4], c[5], c[6])
            val (sop, codes) = CoreTasks.rescueRule(state, c[7], c[8], c[9])
            assertEquals(c.joinToString(" "), c[10] to c.getOrElse(11) { "" }, sop to codes.joinToString(","))
        }
    }

    @Test fun volumeBands() {
        assertEquals(listOf("zero", "low", "normal", "unknown", "unknown"),
            listOf(CoreTasks.volumeBand(0, 7), CoreTasks.volumeBand(1, 7), CoreTasks.volumeBand(5, 7),
                CoreTasks.volumeBand(25, 15), CoreTasks.volumeBand(null, 5)))
    }

    private val state = CoreTasks.RescueState("off", "unknown", "unknown", "UNKNOWN", "off", "normal", "normal")

    @Test fun sharePacketCarriesTokensOnly() {
        val packet = CoreTasks.sharePacket(CoreTasks.ShareFacts(locationRemoved = true, privateBackgroundReview = false, selection = "latest"))
        val json = JSONObject(packet.json)
        assertEquals(setOf("schema", "task_id_alias", "intent", "locale", "cloud_consent", "facts", "recipient_token",
            "photo_token", "confirmation", "allowed_sops", "client_policy_version"), json.keys().asSequence().toSet())
        assertTrue(Regex("^CONTACT_[A-Z]{8}$").matches(json.getString("recipient_token")))
        assertTrue(Regex("^PHOTO_[A-Z]{8}$").matches(json.getString("photo_token")))
        assertTrue(json.getJSONObject("facts").getBoolean("location_removed"))
        assertEquals(packet.alias, json.getString("task_id_alias"))
        val tokens = (1..100).map { JSONObject(CoreTasks.sharePacket(CoreTasks.ShareFacts(false, false, "selected")).json)
            .getString("recipient_token") }.toSet()
        assertEquals(100, tokens.size)
    }

    @Test fun rescuePacketDisclosesOnlyTheSymptomState() {
        val facts = JSONObject(CoreTasks.rescuePacket(state, "connectivity", "offline").json).getJSONObject("facts")
        assertEquals(setOf("symptom_category", "symptom_detail", "requested_scope", "airplane_mode", "network",
            "internet_validated", "transport"), facts.keys().asSequence().toSet())
        assertFalse(facts.has("dnd_mode"))
    }

    /** Set CORE_PACKETS_OUT to dump packets for receiver-side validation (see receiver/README.md). */
    @Test fun dumpPacketsForReceiverValidation() {
        val out = System.getenv("CORE_PACKETS_OUT") ?: return
        val lines = mutableListOf<String>()
        for (selection in listOf("selected", "latest", "family_album", "outing_album"))
            for (location in listOf(true, false))
                lines += CoreTasks.sharePacket(CoreTasks.ShareFacts(location, !location, selection)).json
        for (c in golden("core_rescue_golden.tsv").filter { it[9] == "guided_help" && it[10] == "RESCUE_ASK_DESCRIBE" })
            lines += CoreTasks.rescuePacket(CoreTasks.RescueState(c[0], c[1], c[2], c[3], c[4], c[5], c[6]), c[7], c[8]).json
        File(out).writeText(lines.joinToString("\n") + "\n")
    }
}

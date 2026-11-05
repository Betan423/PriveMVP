package com.example.privagentcollector

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Offline evaluation on Taiwan Traditional-Chinese corpora.
 *
 * The raw text stays in the external dataset directory and is only passed to the same on-device
 * MessageGuard used by the app. Reports contain aggregate counts and one-way counterexample IDs,
 * never message text, URLs, Cofacts IDs, or user identifiers.
 */
class TaiwanMessageDatasetEvaluationTest {
    private data class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0, var tn: Int = 0) {
        val total: Int get() = tp + fp + fn + tn
        fun add(actualPositive: Boolean, predictedPositive: Boolean) {
            when {
                actualPositive && predictedPositive -> tp++
                !actualPositive && predictedPositive -> fp++
                actualPositive -> fn++
                else -> tn++
            }
        }
    }

    private data class Counterexamples(
        val falsePositives: MutableList<String> = mutableListOf(),
        val falseNegatives: MutableList<String> = mutableListOf()
    )

    @Test fun evaluateTsBenchScamAndFinancePairs() {
        val root = datasetRoot()
        val csv = root.resolve("TS-Bench/data/TSB400.csv")
        assumeTrue("TS-Bench missing. Run scripts/fetch_tw_message_benchmarks.ps1 first: $csv", csv.isFile)

        val rows = readCsv(csv)
        val header = rows.first()
        val idIndex = header.indexOf("id")
        val messageIndex = header.indexOf("message")
        val labelIndex = header.indexOf("label")
        assertTrue("TS-Bench CSV schema changed", idIndex >= 0 && messageIndex >= 0 && labelIndex >= 0)

        // The released CSV has no category column. IDs 1..98 contain the scam/deceptive-finance
        // material and 201..298 are their paired hard negatives. ID 99 starts medical
        // misinformation. Keeping the boundary explicit prevents unrelated hate/political prompts
        // from being mislabeled as fraud for MessageGuard.
        val relevant = rows.drop(1).filter { row ->
            val id = row[idIndex].toInt()
            id in 1..98 || id in 201..298
        }
        assertEquals("Expected 98 harmful/safe TS-Bench pairs", 196, relevant.size)

        val overall = Counts()
        val bands = linkedMapOf(
            "commerce_and_account_phishing" to Counts(),
            "investment_solicitation" to Counts(),
            "impersonation_and_social_engineering" to Counts()
        )
        val counterexamples = Counterexamples()
        relevant.forEach { row ->
            val id = row[idIndex].toInt()
            val message = row[messageIndex]
            val actualPositive = row[labelIndex] == "1"
            val predictedPositive = assess(message).level == MessageGuard.Level.HIGH
            overall.add(actualPositive, predictedPositive)
            val pairedId = if (id > 200) id - 200 else id
            val band = when (pairedId) {
                in 1..32 -> "commerce_and_account_phishing"
                in 33..65 -> "investment_solicitation"
                else -> "impersonation_and_social_engineering"
            }
            bands.getValue(band).add(actualPositive, predictedPositive)
            if (!actualPositive && predictedPositive && counterexamples.falsePositives.size < 30)
                counterexamples.falsePositives += anonymousId("ts-bench:$id", message)
            if (actualPositive && !predictedPositive && counterexamples.falseNegatives.size < 30)
                counterexamples.falseNegatives += anonymousId("ts-bench:$id", message)
        }

        val report = File("build/reports/taiwan-message-dataset/ts-bench.md")
        report.parentFile?.mkdirs()
        report.writeText(buildReport(
            title = "TS-Bench Taiwan scam/deceptive-finance evaluation",
            source = csv,
            scope = "IDs 1..98 plus paired hard negatives 201..298 (196 messages)",
            counts = overall,
            bands = bands,
            counterexamples = counterexamples,
            notes = listOf(
                "TS-Bench is human-curated Taiwanese Mandarin safety data, not captured SMS traffic.",
                "The upstream CSV exposes id/message/label/split but not category. This evaluation intentionally excludes IDs 99..200 and 299..400 because they cover medical misinformation, discrimination and political manipulation rather than fraud.",
                "HIGH is the positive prediction; VERIFY and INSUFFICIENT are both counted as not-HIGH.",
                "The app's AI proxy is not called."
            )
        ))
        println("taiwan-ts-bench-report=${report.absolutePath}")
        println("taiwan-ts-bench tp=${overall.tp} fp=${overall.fp} fn=${overall.fn} tn=${overall.tn}")
    }

    @Test fun evaluatePreparedCofactsCorpus() {
        val root = datasetRoot()
        val jsonl = root.resolve("cofacts-prepared/cofacts-benchmark.jsonl")
        assumeTrue(
            "Prepared Cofacts corpus missing. Accept the Cofacts data agreement, download it with " +
                "scripts/fetch_tw_message_benchmarks.ps1 -DownloadCofacts, then run " +
                "scripts/prepare_cofacts_benchmark.py: $jsonl",
            jsonl.isFile
        )

        val counts = Counts()
        val counterexamples = Counterexamples()
        var scamRows = 0
        var safeRows = 0
        jsonl.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val row = JSONObject(line)
            val label = row.getString("label")
            val message = row.getString("text")
            val id = row.getString("id_hash")
            val actualPositive = when (label) {
                "scam" -> { scamRows++; true }
                "non_scam" -> { safeRows++; false }
                else -> error("Unexpected Cofacts label: $label")
            }
            val predictedPositive = assess(message).level == MessageGuard.Level.HIGH
            counts.add(actualPositive, predictedPositive)
            if (!actualPositive && predictedPositive && counterexamples.falsePositives.size < 30)
                counterexamples.falsePositives += id
            if (actualPositive && !predictedPositive && counterexamples.falseNegatives.size < 30)
                counterexamples.falseNegatives += id
        }
        assertTrue("Prepared Cofacts corpus has no scam examples", scamRows > 0)
        assertTrue("Prepared Cofacts corpus has no non-scam examples", safeRows > 0)

        val report = File("build/reports/taiwan-message-dataset/cofacts.md")
        report.parentFile?.mkdirs()
        report.writeText(buildReport(
            title = "Cofacts real-world LINE message evaluation",
            source = jsonl,
            scope = "$scamRows scam / $safeRows non-scam high-confidence prepared messages",
            counts = counts,
            bands = emptyMap(),
            counterexamples = counterexamples,
            notes = listOf(
                "The prepared subset is derived locally from Cofacts user-reported LINE messages and supported crowd fact-check replies.",
                "RUMOR is not treated as fraud by itself. A positive row additionally requires an explicit scam/phishing assertion in a supported reply; conflicting articles are excluded.",
                "This is a noisy observational benchmark with reporting and sampling bias, not an audited ground-truth population sample.",
                "The app's AI proxy is not called and no raw message is copied into this report.",
                "Cofacts attribution and CC BY-SA 4.0 obligations remain attached to the local derived corpus."
            )
        ))
        println("taiwan-cofacts-report=${report.absolutePath}")
        println("taiwan-cofacts tp=${counts.tp} fp=${counts.fp} fn=${counts.fn} tn=${counts.tn}")
    }

    private fun assess(message: String): MessageGuard.Assessment =
        MessageGuard.assess(MessageGuard.extractFacts(message, "pasted"))

    private fun buildReport(
        title: String,
        source: File,
        scope: String,
        counts: Counts,
        bands: Map<String, Counts>,
        counterexamples: Counterexamples,
        notes: List<String>
    ): String {
        val precision = ratio(counts.tp, counts.tp + counts.fp)
        val recall = ratio(counts.tp, counts.tp + counts.fn)
        val specificity = ratio(counts.tn, counts.tn + counts.fp)
        val accuracy = ratio(counts.tp + counts.tn, counts.total)
        val balancedAccuracy = (recall + specificity) / 2.0
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("- Local source: `${source.absolutePath}`")
            appendLine("- Scope: $scope")
            appendLine("- Classifier: `MessageGuard.extractFacts` + `MessageGuard.assess`")
            appendLine("- Raw message text in report: none")
            appendLine()
            appendLine("| Actual \\ Predicted | HIGH | Not HIGH | Total |")
            appendLine("|---|---:|---:|---:|")
            appendLine("| Scam/risky | ${counts.tp} | ${counts.fn} | ${counts.tp + counts.fn} |")
            appendLine("| Non-scam/safe | ${counts.fp} | ${counts.tn} | ${counts.fp + counts.tn} |")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---:|")
            appendLine("| Accuracy | ${percent(accuracy)} |")
            appendLine("| Balanced accuracy | ${percent(balancedAccuracy)} |")
            appendLine("| Precision | ${percent(precision)} |")
            appendLine("| Recall | ${percent(recall)} |")
            appendLine("| Specificity | ${percent(specificity)} |")
            appendLine("| F1 | ${percent(f1)} |")
            if (bands.isNotEmpty()) {
                appendLine()
                appendLine("## Content-band breakdown")
                appendLine()
                appendLine("| Band | TP | FP | FN | TN | Recall | Specificity |")
                appendLine("|---|---:|---:|---:|---:|---:|---:|")
                bands.forEach { (name, band) ->
                    appendLine("| $name | ${band.tp} | ${band.fp} | ${band.fn} | ${band.tn} | ${percent(ratio(band.tp, band.tp + band.fn))} | ${percent(ratio(band.tn, band.tn + band.fp))} |")
                }
            }
            appendLine()
            appendLine("## Anonymous counterexamples")
            appendLine()
            appendLine("- False positive SHA-256 IDs: ${counterexamples.falsePositives.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine("- False negative SHA-256 IDs: ${counterexamples.falseNegatives.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine()
            appendLine("## Interpretation boundaries")
            appendLine()
            notes.forEachIndexed { index, note -> appendLine("${index + 1}. $note") }
        }
    }

    private fun readCsv(file: File): List<List<String>> {
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.setLength(0) }
                '\n' -> {
                    row += field.toString(); field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                '\r' -> Unit
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row
        }
        return rows
    }

    private fun datasetRoot(): File {
        val override = System.getenv("PRIVAGENT_TW_DATASET_ROOT")
        if (!override.isNullOrBlank()) return File(override)
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val repository = requireNotNull(module.parentFile)
        return requireNotNull(repository.parentFile).resolve("privacy-sms-datasets")
    }

    private fun anonymousId(source: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source\u0000$text".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private fun percent(value: Double): String =
        String.format(Locale.ROOT, "%.2f%%", value * 100.0)
}

package com.example.privagentcollector

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Offline benchmark against public SMS corpora. This is deliberately an evaluation, not a quality
 * gate: UCI's spam label is broader than fraud and its messages are English, while MessageGuard is
 * a zh-TW high-risk scam triage policy. No message is sent to the proxy or copied into the report.
 */
class ExternalSmsDatasetEvaluationTest {
    private data class Levels(var high: Int = 0, var caution: Int = 0, var lowSignal: Int = 0, var insufficient: Int = 0) {
        val total: Int get() = high + caution + lowSignal + insufficient
        fun add(level: MessageGuard.Level) = when (level) {
            MessageGuard.Level.HIGH -> high++
            MessageGuard.Level.CAUTION -> caution++
            MessageGuard.Level.LOW_SIGNAL -> lowSignal++
            MessageGuard.Level.INSUFFICIENT -> insufficient++
        }
    }

    private data class UciResult(
        val ham: Levels,
        val spam: Levels,
        val falsePositiveIds: List<String>,
        val falseNegativeIds: List<String>
    )

    private data class FbsCategory(
        val category: String,
        val levels: Levels,
        val highReasons: MutableMap<String, Int> = mutableMapOf(),
        var urlPlaceholderMessages: Int = 0,
        var syntheticLinkRestoredHigh: Int = 0
    )

    private val fbsUrlPlaceholder = Regex("(^|\\s)URL(?=\\s|$)")

    @Test fun evaluateDownloadedPublicSmsCorpora() {
        val root = datasetRoot()
        val uci = root.resolve("uci-sms-spam-collection/SMSSpamCollection")
        val fbs = root.resolve("FBS_SMS_Dataset_sanitized")
        assumeTrue("External dataset missing. Run scripts/fetch_sms_benchmarks.ps1 first: $root", uci.isFile && fbs.isDirectory)

        val uciResult = evaluateUci(uci)
        val fbsResult = evaluateFbs(fbs)
        assertEquals(5_574, uciResult.ham.total + uciResult.spam.total)
        assertTrue("FBS corpus unexpectedly small", fbsResult.sumOf { it.levels.total } > 10_000)

        val report = File("build/reports/external-sms-dataset/latest.md")
        report.parentFile?.mkdirs()
        report.writeText(buildReport(root, uciResult, fbsResult))
        println("external-sms-report=${report.absolutePath}")
        println("uci ham=${uciResult.ham.total} spam=${uciResult.spam.total}")
        println("fbs messages=${fbsResult.sumOf { it.levels.total }} categories=${fbsResult.size}")
    }

    private fun evaluateUci(file: File): UciResult {
        val ham = Levels()
        val spam = Levels()
        val falsePositiveIds = mutableListOf<String>()
        val falseNegativeIds = mutableListOf<String>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val split = line.indexOf('\t')
            if (split <= 0) return@forEachLine
            val label = line.substring(0, split)
            val message = line.substring(split + 1)
            val assessment = assess(message)
            when (label) {
                "ham" -> {
                    ham.add(assessment.level)
                    if (assessment.level == MessageGuard.Level.HIGH && falsePositiveIds.size < 20)
                        falsePositiveIds += anonymousId("uci-ham", message)
                }
                "spam" -> {
                    spam.add(assessment.level)
                    if (assessment.level != MessageGuard.Level.HIGH && falseNegativeIds.size < 20)
                        falseNegativeIds += anonymousId("uci-spam", message)
                }
            }
        }
        return UciResult(ham, spam, falsePositiveIds, falseNegativeIds)
    }

    private fun evaluateFbs(directory: File): List<FbsCategory> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name != "README.md" }
        .sortedBy { it.name }
        .map { file ->
            val result = FbsCategory(file.name, Levels())
            file.forEachLine(Charsets.UTF_8) { message ->
                if (message.isBlank()) return@forEachLine
                val assessment = assess(message)
                result.levels.add(assessment.level)
                if (assessment.level == MessageGuard.Level.HIGH) assessment.reasonCodes.forEach { code ->
                    result.highReasons[code] = (result.highReasons[code] ?: 0) + 1
                }
                if (fbsUrlPlaceholder.containsMatchIn(message)) {
                    result.urlPlaceholderMessages++
                    val restored = fbsUrlPlaceholder.replace(message) { match ->
                        match.groupValues[1] + "https://benchmark-phishing.xyz"
                    }
                    if (assess(restored).level == MessageGuard.Level.HIGH)
                        result.syntheticLinkRestoredHigh++
                }
            }
            result
        }

    private fun assess(message: String): MessageGuard.Assessment =
        MessageGuard.assess(MessageGuard.extractFacts(message, "pasted"))

    private fun buildReport(root: File, uci: UciResult, fbs: List<FbsCategory>): String {
        val tp = uci.spam.high
        val fn = uci.spam.caution + uci.spam.lowSignal + uci.spam.insufficient
        val fp = uci.ham.high
        val tn = uci.ham.caution + uci.ham.lowSignal + uci.ham.insufficient
        val precision = ratio(tp, tp + fp)
        val recall = ratio(tp, tp + fn)
        val specificity = ratio(tn, tn + fp)
        val accuracy = ratio(tp + tn, tp + tn + fp + fn)
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val balancedAccuracy = (recall + specificity) / 2.0
        val alwaysNotHighAccuracy = ratio(uci.ham.total, uci.ham.total + uci.spam.total)
        val fraud = fbs.filter { it.category.startsWith("FR_") }
        val fraudHigh = fraud.sumOf { it.levels.high }
        val fraudTotal = fraud.sumOf { it.levels.total }
        val fbsHigh = fbs.sumOf { it.levels.high }
        val fbsTotal = fbs.sumOf { it.levels.total }
        val fbsUrlPlaceholders = fbs.sumOf { it.urlPlaceholderMessages }
        val fbsRestoredHigh = fbs.sumOf { it.syntheticLinkRestoredHigh }
        val fraudUrlPlaceholders = fraud.sumOf { it.urlPlaceholderMessages }
        val fraudRestoredHigh = fraud.sumOf { it.syntheticLinkRestoredHigh }
        val topReasons = fbs.flatMap { it.highReasons.entries }
            .groupingBy { it.key }
            .fold(0) { acc, entry -> acc + entry.value }
            .entries.sortedByDescending { it.value }.take(10)

        return buildString {
            appendLine("# External public SMS dataset evaluation")
            appendLine()
            appendLine("- Evaluation path: `${root.absolutePath}`")
            appendLine("- Classifier under test: `MessageGuard.extractFacts` + `MessageGuard.assess`")
            appendLine("- Positive decision: `HIGH`; `VERIFY` and `INSUFFICIENT` are non-positive")
            appendLine("- Network calls: none")
            appendLine("- Raw message text in this report: none")
            appendLine()
            appendLine("## UCI SMS Spam Collection")
            appendLine()
            appendLine("UCI labels broad SMS spam, not only fraud, and the corpus is English. These figures measure cross-language/out-of-domain behavior; they are not a fair zh-TW fraud accuracy claim.")
            appendLine()
            appendLine("| Actual \\ Predicted | HIGH | Not HIGH | Total |")
            appendLine("|---|---:|---:|---:|")
            appendLine("| Spam | $tp | $fn | ${uci.spam.total} |")
            appendLine("| Ham | $fp | $tn | ${uci.ham.total} |")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---:|")
            appendLine("| Accuracy | ${percent(accuracy)} |")
            appendLine("| Always-not-HIGH baseline accuracy | ${percent(alwaysNotHighAccuracy)} |")
            appendLine("| Balanced accuracy | ${percent(balancedAccuracy)} |")
            appendLine("| Spam precision | ${percent(precision)} |")
            appendLine("| Spam recall | ${percent(recall)} |")
            appendLine("| Ham specificity | ${percent(specificity)} |")
            appendLine("| F1 | ${percent(f1)} |")
            appendLine("| HIGH / CAUTION / LOW / INSUFFICIENT among spam | ${uci.spam.high} / ${uci.spam.caution} / ${uci.spam.lowSignal} / ${uci.spam.insufficient} |")
            appendLine("| HIGH / CAUTION / LOW / INSUFFICIENT among ham | ${uci.ham.high} / ${uci.ham.caution} / ${uci.ham.lowSignal} / ${uci.ham.insufficient} |")
            appendLine()
            appendLine("Anonymous counterexample IDs (SHA-256 prefixes; no message text):")
            appendLine()
            appendLine("- Ham classified HIGH: ${uci.falsePositiveIds.distinct().ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine("- Spam not classified HIGH: ${uci.falseNegativeIds.distinct().ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine()
            appendLine("## FBS Chinese real-world spam corpus")
            appendLine()
            appendLine("FBS contains only spam and uses 14 categories, so accuracy, specificity and false-positive rate cannot be computed. `FR_*` is reported separately as the closest available fraud subset.")
            appendLine()
            appendLine("The released FBS text replaces real links with the literal token `URL`. The final two columns are a separate sensitivity analysis: only those placeholders are replaced with the synthetic high-risk host `benchmark-phishing.xyz`. They are not raw-dataset accuracy.")
            appendLine()
            appendLine("| Category | HIGH | VERIFY | INSUFFICIENT | Total | HIGH rate | URL placeholders | Synthetic-link HIGH |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
            fbs.forEach { category ->
                appendLine("| ${category.category} | ${category.levels.high} | ${category.levels.caution + category.levels.lowSignal} | ${category.levels.insufficient} | ${category.levels.total} | ${percent(ratio(category.levels.high, category.levels.total))} | ${category.urlPlaceholderMessages} | ${category.syntheticLinkRestoredHigh} |")
            }
            appendLine("| **All FBS spam** | **$fbsHigh** |  |  | **$fbsTotal** | **${percent(ratio(fbsHigh, fbsTotal))}** | **$fbsUrlPlaceholders** | **$fbsRestoredHigh** |")
            appendLine("| **FR fraud subset** | **$fraudHigh** |  |  | **$fraudTotal** | **${percent(ratio(fraudHigh, fraudTotal))}** | **$fraudUrlPlaceholders** | **$fraudRestoredHigh** |")
            appendLine()
            appendLine("Top reason codes among FBS messages classified HIGH:")
            appendLine()
            topReasons.forEach { appendLine("- `${it.key}`: ${it.value}") }
            appendLine()
            appendLine("## Interpretation boundaries")
            appendLine()
            appendLine("1. `MessageGuard` is a conservative zh-TW scam-risk policy, not a general English/Chinese spam model.")
            appendLine("2. UCI spam includes marketing and prize messages; FBS includes advertising and illegal content that may not request money or secrets.")
            appendLine("3. FBS link placeholders remove the feature used by on-device URL grading. Synthetic-link restoration is a sensitivity bound, not a replacement for the raw result.")
            appendLine("4. A low recall here identifies coverage gaps and dataset shift; it does not justify silently changing all spam to HIGH, which would increase elder-facing false alarms.")
            appendLine("5. The next improvement should add language-aware signals and a separate `spam` versus `fraud/high-risk` output, then rerun this unchanged benchmark.")
        }
    }

    private fun datasetRoot(): File {
        val override = System.getenv("PRIVAGENT_SMS_DATASET_ROOT")
        if (!override.isNullOrBlank()) return File(override)
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val repository = requireNotNull(module.parentFile)
        val workspace = requireNotNull(repository.parentFile)
        return workspace.resolve("privacy-sms-datasets")
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

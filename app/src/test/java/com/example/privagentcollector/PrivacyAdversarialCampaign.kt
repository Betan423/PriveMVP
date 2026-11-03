package com.example.privagentcollector

import java.io.File
import kotlin.random.Random

/** Deterministic red-team campaign recorder. The oracle is explicit and never learned by the SUT. */
internal class PrivacyAdversarialCampaign(val seed: Int) {
    data class Outcome(
        val category: String,
        val caseId: String,
        val invariantHeld: Boolean,
        val evidence: String
    )

    val random = Random(seed)
    private val outcomes = mutableListOf<Outcome>()

    fun verify(category: String, caseId: String, invariantHeld: Boolean, evidence: String = "") {
        outcomes += Outcome(category, caseId, invariantHeld, evidence)
    }

    fun failures(): List<Outcome> = outcomes.filterNot { it.invariantHeld }

    fun total(): Int = outcomes.size

    fun categoryCounts(): Map<String, Pair<Int, Int>> = outcomes
        .groupBy { it.category }
        .toSortedMap()
        .mapValues { (_, values) -> values.size to values.count { it.invariantHeld } }

    fun summary(): String = buildString {
        append("privacy-red-team seed=").append(seed)
        append(" cases=").append(total())
        append(" passed=").append(outcomes.count { it.invariantHeld })
        append(" failed=").append(failures().size)
    }

    fun writeMarkdown(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            appendLine("# PrivAgent deterministic privacy red-team report")
            appendLine()
            appendLine("- Seed: `$seed`")
            appendLine("- Generated checks: `${total()}`")
            appendLine("- Invariants held: `${outcomes.count { it.invariantHeld }}`")
            appendLine("- Violations: `${failures().size}`")
            appendLine()
            appendLine("| Category | Checks | Passed | Failed |")
            appendLine("|---|---:|---:|---:|")
            categoryCounts().forEach { (category, counts) ->
                appendLine("| ${category.replace("|", "\\|")} | ${counts.first} | ${counts.second} | ${counts.first - counts.second} |")
            }
            if (failures().isNotEmpty()) {
                appendLine()
                appendLine("## Counterexamples")
                failures().take(50).forEach {
                    val evidence = it.evidence.replace("\n", " ").replace("|", "\\|")
                    appendLine("- `${it.category}/${it.caseId}`: $evidence")
                }
            }
            appendLine()
            appendLine("This artifact is generated from synthetic data only; it contains no LINE account or user content.")
        })
    }
}

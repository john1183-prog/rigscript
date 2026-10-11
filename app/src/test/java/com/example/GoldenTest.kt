package com.example

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP0.4 legacy protection. Every run writes the actual snapshots to app/build/golden-actual/ (CI uploads
 * that folder as the `golden-actual` artifact) and compares them with the committed goldens in
 * src/test/resources/golden/ using GoldenCompare's tolerance of 1e-3.
 *
 * A fixture without a committed golden passes with a warning, so the first run can create it. When a golden is
 * missing or differs, the snapshot is also printed as `CAPTURE|golden:...` lines, which ci_report.py shows in
 * the CI report: that is how the sandbox, which cannot download artifacts, gets the new text. If a difference is
 * an intended change, justify it in V2_DECISIONS.md and commit the new file; never loosen the tolerance.
 */
class GoldenTest {

    @Test
    fun snapshotsMatchTheCommittedGoldens() {
        val outDir = File("build/golden-actual")
        outDir.mkdirs()
        val missing = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (name in Fixtures.SCRIPT_NAMES) {
            val actual = GoldenSnapshot.build(name)
            File(outDir, "$name.txt").writeText(actual, Charsets.UTF_8)
            val expected = Fixtures.textOrNull("golden/$name.txt")
            if (expected == null) {
                missing += name
                GoldenSnapshot.captureLines(name, actual).forEach { println(it) }
                continue
            }
            val diffs = GoldenCompare.compare(expected, actual)
            if (diffs.isNotEmpty()) {
                failures += "$name: ${diffs.size} difference(s)\n" + diffs.joinToString("\n")
                GoldenSnapshot.captureLines(name, actual).forEach { println(it) }
            }
        }
        if (missing.isNotEmpty()) {
            println("GOLDEN WARNING: no committed golden for ${missing.joinToString()}; passing so that the first run can generate them.")
        }
        assertTrue(
            "golden mismatch (intended change? take the new text from the CAPTURE lines):\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}

package com.example

import kotlin.math.abs

/**
 * Compares two golden snapshots line by line (WP0.4). Decimal numbers (every float in a snapshot is
 * printed with a decimal point) are compared with an absolute tolerance; everything else, including
 * integers, colours and ids, must match exactly.
 */
object GoldenCompare {

    const val TOLERANCE = 1e-3

    private val DECIMAL = Regex("-?\\d+\\.\\d+")

    fun compare(expected: String, actual: String, tolerance: Double = TOLERANCE, maxReported: Int = 12): List<String> {
        val e = expected.trimEnd().lines()
        val a = actual.trimEnd().lines()
        val out = mutableListOf<String>()
        if (e.size != a.size) out += "line count: expected ${e.size}, actual ${a.size}"
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] == a[i]) continue
            val reason = lineDifference(e[i], a[i], tolerance) ?: continue
            out += "line ${i + 1}: $reason\n  expected: ${e[i].take(220)}\n  actual:   ${a[i].take(220)}"
            if (out.size >= maxReported) {
                out += "(more differences not listed)"
                break
            }
        }
        return out
    }

    private fun lineDifference(expected: String, actual: String, tolerance: Double): String? {
        if (DECIMAL.split(expected) != DECIMAL.split(actual)) return "text differs"
        val en = DECIMAL.findAll(expected).map { it.value.toDouble() }.toList()
        val an = DECIMAL.findAll(actual).map { it.value.toDouble() }.toList()
        if (en.size != an.size) return "number count differs"
        for (k in en.indices) {
            if (!(abs(en[k] - an[k]) <= tolerance)) {
                return "number ${k + 1} is ${an[k]}, expected ${en[k]} (tolerance $tolerance)"
            }
        }
        return null
    }
}

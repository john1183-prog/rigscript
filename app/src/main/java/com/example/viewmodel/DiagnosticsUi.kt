package com.example.viewmodel

/** What the Diagnostics screen shows (WP1.6c). */
data class DiagnosticsUi(
    val running: Boolean = false,
    /** "Self-test", "GLES smoke test", ... while running or after. */
    val title: String = "",
    /** The one progress line while [running]. */
    val line: String = "",
    /** The last finished run's text report (also what Share sends), or a one-line result. */
    val summary: String? = null,
    /** Where the report files are, when they were written. */
    val directory: String? = null
)

package com.example.engine

/**
 * What is known about the GLES export backend on THIS device (WP1.3).
 *
 *  - [Eligible]: the on-device check passed, so `AUTO` may use GLES.
 *  - [Ineligible]: the check failed or crashed, or GLES failed while exporting; `AUTO` uses Canvas.
 *  - [Unknown]: never checked for this app build on this OS build; `AUTO` uses Canvas.
 *
 * Nothing here is "proved" by a chat: only a check that ran on the phone can produce [Eligible].
 */
sealed class GlesState {
    object Eligible : GlesState() {
        override fun toString() = "ELIGIBLE"
    }

    data class Ineligible(val reason: String) : GlesState() {
        override fun toString() = "INELIGIBLE($reason)"
    }

    object Unknown : GlesState() {
        override fun toString() = "UNKNOWN"
    }
}

/**
 * Pure rules around the cached verdict: the cache key, how a verdict is stored as text, and the crash-loop guard.
 * No Android types, so the rules are unit-tested on the JVM. [GlesEligibilityStore] (Android glue) keeps the record
 * and the marker in SharedPreferences, outside Room and outside the per-project settings: the verdict belongs to the
 * device, not to a project (I8: no Room change).
 *
 * The crash-loop guard: before the check runs, the glue writes the marker (the current cache key) and clears it
 * when the check ends. A native GL or codec crash kills the process, so the marker is still set at the next
 * launch; [onLaunch] then records `Ineligible("crashed")` for that key. There is no automatic retry for that key;
 * only the person tapping "Re-run GLES check" (or a new app or OS build, which changes the key) tries again.
 */
object GlesEligibility {

    /** The verdict stored for one key. An [GlesState.Unknown] record is allowed (it means "reset"). */
    data class Record(val key: String, val state: GlesState)

    /** The reason recorded when the marker was still set at launch. */
    const val CRASHED = "crashed"

    private const val MAGIC = "RIGGLES1"
    private const val MAX_REASON = 160

    /**
     * Identity of the app build plus the OS build, as plain strings (the glue passes the app version and
     * `Build.FINGERPRINT`). Length-prefixed so two different pairs can never produce the same key, and
     * single-line so it can be stored as text. A new app build or an OS update changes the key, and the
     * stored verdict then stops applying.
     */
    fun cacheKey(appVersion: String, buildFingerprint: String): String {
        val app = oneLine(appVersion)
        return "${app.length}:$app|${oneLine(buildFingerprint)}"
    }

    /** The verdict that applies right now: the record's state when it is for [currentKey], else [GlesState.Unknown]. */
    fun stateFor(record: Record?, currentKey: String): GlesState =
        if (record != null && record.key == currentKey) record.state else GlesState.Unknown

    /** The marker text the glue writes before a check: the key the check runs under. */
    fun markerFor(currentKey: String): String = currentKey

    /** What to do at app start, given the marker found (or null), the stored record (or null) and the current key. */
    data class LaunchOutcome(
        /** The record to store (unchanged when nothing happened). */
        val record: Record?,
        /** True when the marker must be removed. */
        val clearMarker: Boolean,
        /** True when this launch found a crash for the current key. */
        val crashed: Boolean
    )

    fun onLaunch(markerKey: String?, record: Record?, currentKey: String): LaunchOutcome {
        if (markerKey.isNullOrBlank()) return LaunchOutcome(record, clearMarker = false, crashed = false)
        // The marker is still set: the process died during the check. If it was this build on this OS build, the
        // check crashed here, so GLES is out for this key until someone re-runs the check on purpose. A marker
        // from an older build or OS says nothing about this one: just remove it.
        return if (markerKey == currentKey) {
            LaunchOutcome(Record(currentKey, GlesState.Ineligible(CRASHED)), clearMarker = true, crashed = true)
        } else {
            LaunchOutcome(record, clearMarker = true, crashed = false)
        }
    }

    // ── Storage format ─────────────────────────────────────────────────────────────────────────────────────────

    /** Text form of a record: a magic line, the key, the state, and (for [GlesState.Ineligible]) the reason. */
    fun encode(record: Record): String = when (val s = record.state) {
        is GlesState.Eligible -> "$MAGIC\n${oneLine(record.key)}\nELIGIBLE"
        is GlesState.Ineligible -> "$MAGIC\n${oneLine(record.key)}\nINELIGIBLE\n${oneLine(s.reason).take(MAX_REASON)}"
        is GlesState.Unknown -> "$MAGIC\n${oneLine(record.key)}\nUNKNOWN"
    }

    /** Reads [encode]'s output. Anything unreadable (null, empty, damaged, from a future format) gives null: never throws. */
    fun decode(text: String?): Record? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        if (lines.size < 3 || lines[0] != MAGIC || lines[1].isEmpty()) return null
        val state = when (lines[2]) {
            "ELIGIBLE" -> GlesState.Eligible
            "INELIGIBLE" -> GlesState.Ineligible(lines.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "unknown")
            "UNKNOWN" -> GlesState.Unknown
            else -> return null
        }
        return Record(lines[1], state)
    }

    private fun oneLine(s: String): String = s.replace('\r', ' ').replace('\n', ' ')
}

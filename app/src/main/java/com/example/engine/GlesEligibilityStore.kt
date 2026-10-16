package com.example.engine

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Keeps the cached GLES verdict and the crash marker on the device (WP1.3). The rules (what a record means, what a
 * leftover marker means) are in [GlesEligibility], which is pure and unit-tested; this class is only the storage.
 *
 * The verdict is a fact about the phone and the app build, not about a project, so it lives in its own
 * SharedPreferences file rather than in the per-project `ExportSettings` JSON, and it needs no Room change (I8).
 * Writes use `commit()` (synchronous): the crash marker exists to survive a native crash a moment later, and an
 * asynchronous `apply()` could still be in memory when the process dies.
 *
 * Every function is fail-soft: a storage problem means "no verdict" (Canvas), never a crash at start-up (I12).
 */
class GlesEligibilityStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Identity of this app build on this OS build. `versionCode` alone would not do: this repo keeps it at 1, so a new
     * debug build would reuse an old verdict. The install time changes with every installed build, so it is part of
     * the "app version" string the pure key function takes.
     */
    val currentKey: String by lazy { computeKey() }

    fun load(): GlesEligibility.Record? =
        runCatching { GlesEligibility.decode(prefs.getString(KEY_RECORD, null)) }.getOrNull()

    /** The verdict that applies to this build right now. */
    fun state(): GlesState = GlesEligibility.stateFor(load(), currentKey)

    /** Stores [state] as the verdict for this build. */
    fun record(state: GlesState) {
        runCatching {
            prefs.edit().putString(KEY_RECORD, GlesEligibility.encode(GlesEligibility.Record(currentKey, state))).commit()
        }
    }

    /**
     * Applies the crash-loop rule once at start-up and returns the resulting verdict. The record is written before the
     * marker is removed, so a process death in between leaves the marker and the same outcome is reached next time.
     */
    fun applyLaunchRule(): GlesState = runCatching {
        val outcome = GlesEligibility.onLaunch(prefs.getString(KEY_MARKER, null), load(), currentKey)
        if (outcome.crashed) {
            outcome.record?.let { prefs.edit().putString(KEY_RECORD, GlesEligibility.encode(it)).commit() }
            Log.w(TAG, "The last GLES check did not finish: GLES is switched off for this build until the check is re-run")
        }
        if (outcome.clearMarker) prefs.edit().remove(KEY_MARKER).commit()
        GlesEligibility.stateFor(outcome.record, currentKey)
    }.getOrDefault(GlesState.Unknown)

    /** Written before a GLES check starts. If it is still there at the next launch, the check crashed the process. */
    fun markCheckStarted() {
        runCatching { prefs.edit().putString(KEY_MARKER, GlesEligibility.markerFor(currentKey)).commit() }
    }

    /** Removed when a GLES check ends, whatever its result. */
    fun markCheckFinished() {
        runCatching { prefs.edit().remove(KEY_MARKER).commit() }
    }

    /** Forgets the verdict and any marker: the next check starts from UNKNOWN. */
    fun reset() {
        runCatching { prefs.edit().remove(KEY_RECORD).remove(KEY_MARKER).commit() }
    }

    private fun computeKey(): String {
        val version = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            "$code.${info.lastUpdateTime}"
        }.getOrDefault("unknown")
        return GlesEligibility.cacheKey(version, Build.FINGERPRINT ?: "unknown")
    }

    private companion object {
        const val TAG = "GlesEligibility"
        const val PREFS = "gles_eligibility"
        const val KEY_RECORD = "record"
        const val KEY_MARKER = "check_in_progress"
    }
}

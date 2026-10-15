package com.example

import com.example.engine.GlesEligibility
import com.example.engine.GlesEligibility.Record
import com.example.engine.GlesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP1.3: the cached GLES verdict, its cache key, its stored text, and the crash-loop guard. */
class GlesEligibilityTest {

    private val keyA = GlesEligibility.cacheKey("1.1700000000000", "google/pixel/pixel:14/UP1A.231005.007/10754064:user/release-keys")

    // ── Cache key ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theSameBuildAndOsGiveTheSameKey() {
        assertEquals(keyA, GlesEligibility.cacheKey("1.1700000000000", "google/pixel/pixel:14/UP1A.231005.007/10754064:user/release-keys"))
    }

    @Test
    fun aNewAppBuildOrANewOsBuildChangesTheKey() {
        assertTrue(keyA != GlesEligibility.cacheKey("1.1700000000001", "google/pixel/pixel:14/UP1A.231005.007/10754064:user/release-keys"))
        assertTrue(keyA != GlesEligibility.cacheKey("1.1700000000000", "google/pixel/pixel:15/AP3A.240905.015/12345:user/release-keys"))
    }

    @Test
    fun twoDifferentPairsNeverShareAKey() {
        // A plain "a" + "|" + "b" join would make these two collide.
        assertTrue(GlesEligibility.cacheKey("a|b", "c") != GlesEligibility.cacheKey("a", "b|c"))
        assertTrue(GlesEligibility.cacheKey("1", "23") != GlesEligibility.cacheKey("12", "3"))
    }

    @Test
    fun aKeyIsAlwaysOneLine() {
        val key = GlesEligibility.cacheKey("1.\n2", "fp\r\nwith\nbreaks")
        assertFalse(key.contains('\n'))
        assertFalse(key.contains('\r'))
    }

    // ── Which verdict applies ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun noRecordMeansUnknown() {
        assertEquals(GlesState.Unknown, GlesEligibility.stateFor(null, keyA))
    }

    @Test
    fun aRecordForAnotherBuildDoesNotApply() {
        val other = GlesEligibility.cacheKey("2.1", "fp")
        assertEquals(GlesState.Unknown, GlesEligibility.stateFor(Record(other, GlesState.Eligible), keyA))
        assertEquals(GlesState.Unknown, GlesEligibility.stateFor(Record(other, GlesState.Ineligible("x")), keyA))
    }

    @Test
    fun aRecordForThisBuildApplies() {
        assertEquals(GlesState.Eligible, GlesEligibility.stateFor(Record(keyA, GlesState.Eligible), keyA))
        assertEquals(GlesState.Ineligible("no encoder"), GlesEligibility.stateFor(Record(keyA, GlesState.Ineligible("no encoder")), keyA))
        assertEquals(GlesState.Unknown, GlesEligibility.stateFor(Record(keyA, GlesState.Unknown), keyA))
    }

    // ── Stored text ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyStateSurvivesTheStoredText() {
        for (state in listOf(GlesState.Eligible, GlesState.Ineligible("the encoder refused 1080x1920"), GlesState.Unknown)) {
            val record = Record(keyA, state)
            assertEquals(record, GlesEligibility.decode(GlesEligibility.encode(record)))
        }
    }

    @Test
    fun aLongOrMultiLineReasonIsFlattenedAndCapped() {
        val reason = "line one\nline two\r\n" + "x".repeat(400)
        val back = GlesEligibility.decode(GlesEligibility.encode(Record(keyA, GlesState.Ineligible(reason))))
        assertNotNull(back)
        val stored = (back!!.state as GlesState.Ineligible).reason
        assertFalse(stored.contains('\n'))
        assertTrue(stored.startsWith("line one line two"))
        assertEquals(160, stored.length)
    }

    @Test
    fun damagedTextReadsAsNothingAndNeverThrows() {
        assertNull(GlesEligibility.decode(null))
        assertNull(GlesEligibility.decode(""))
        assertNull(GlesEligibility.decode("   "))
        assertNull(GlesEligibility.decode("garbage"))
        assertNull(GlesEligibility.decode("RIGGLES9\n$keyA\nELIGIBLE"))      // another format version
        assertNull(GlesEligibility.decode("RIGGLES1\n$keyA"))                 // truncated
        assertNull(GlesEligibility.decode("RIGGLES1\n\nELIGIBLE"))            // no key
        assertNull(GlesEligibility.decode("RIGGLES1\n$keyA\nMAYBE"))          // unknown state word
    }

    @Test
    fun anIneligibleRecordWithoutAReasonStillReads() {
        assertEquals(Record(keyA, GlesState.Ineligible("unknown")), GlesEligibility.decode("RIGGLES1\n$keyA\nINELIGIBLE"))
        assertEquals(Record(keyA, GlesState.Ineligible("unknown")), GlesEligibility.decode("RIGGLES1\n$keyA\nINELIGIBLE\n  "))
    }

    // ── Crash-loop guard ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun noMarkerChangesNothing() {
        val record = Record(keyA, GlesState.Eligible)
        val out = GlesEligibility.onLaunch(null, record, keyA)
        assertEquals(record, out.record)
        assertFalse(out.clearMarker)
        assertFalse(out.crashed)
        val blank = GlesEligibility.onLaunch("  ", record, keyA)
        assertEquals(record, blank.record)
        assertFalse(blank.clearMarker)
    }

    @Test
    fun aMarkerLeftByThisBuildMeansItCrashed() {
        val out = GlesEligibility.onLaunch(GlesEligibility.markerFor(keyA), null, keyA)
        assertEquals(Record(keyA, GlesState.Ineligible("crashed")), out.record)
        assertTrue(out.clearMarker)
        assertTrue(out.crashed)
    }

    @Test
    fun aCrashOverridesAnEarlierPassingVerdictForTheSameBuild() {
        // The person re-ran the check, the process died, and the old ELIGIBLE must not survive it.
        val out = GlesEligibility.onLaunch(GlesEligibility.markerFor(keyA), Record(keyA, GlesState.Eligible), keyA)
        assertEquals(GlesState.Ineligible("crashed"), GlesEligibility.stateFor(out.record, keyA))
    }

    @Test
    fun aMarkerFromAnotherBuildIsJustRemoved() {
        val oldKey = GlesEligibility.cacheKey("1.1", "old-os")
        val record = Record(keyA, GlesState.Eligible)
        val out = GlesEligibility.onLaunch(GlesEligibility.markerFor(oldKey), record, keyA)
        assertEquals(record, out.record)
        assertTrue(out.clearMarker)
        assertFalse(out.crashed)
    }

    @Test
    fun aCrashIsNotRetriedAutomaticallyForThatBuildButIsForANewOne() {
        val crashed = GlesEligibility.onLaunch(GlesEligibility.markerFor(keyA), null, keyA).record
        // Next launches: the marker is gone, the stored verdict stays.
        val again = GlesEligibility.onLaunch(null, crashed, keyA)
        assertEquals(GlesState.Ineligible("crashed"), GlesEligibility.stateFor(again.record, keyA))
        // An app or OS update changes the key: the old verdict stops applying and the check may run again.
        val updated = GlesEligibility.cacheKey("1.1700000000001", "fp")
        assertEquals(GlesState.Unknown, GlesEligibility.stateFor(again.record, updated))
    }

    @Test
    fun aFinishedCheckLeavesNoMarkerSoNothingIsRecordedAtTheNextLaunch() {
        // Write marker, run the check, clear the marker (the glue does both ends): the next launch sees nothing.
        val marker: String? = null
        val out = GlesEligibility.onLaunch(marker, Record(keyA, GlesState.Eligible), keyA)
        assertEquals(GlesState.Eligible, GlesEligibility.stateFor(out.record, keyA))
    }
}

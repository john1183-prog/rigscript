package com.example

import com.example.data.AmplitudeSettings
import com.example.engine.Expression
import com.example.engine.ScriptValidator
import com.example.engine.StickFigureRig
import com.example.engine.acting.ActVocabulary
import com.example.engine.acting.ActingContext
import com.example.engine.acting.ActingIntensity
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.7: `docs/samples/v2_showcase.json` is the one script John pastes to see everything V2 added so far. It must keep parsing
 * and validating cleanly, must keep exercising every new field (all ten act verbs, all nine emotes, all six expressions, walk
 * cycles, events without a pose, a layer to look at), and must run through the engine at every acting level without a fault.
 */
class ShowcaseSampleTest {

    private val script = Fixtures.scriptFromText(Fixtures.docSample("v2_showcase"))

    @Test
    fun itParsesAndTheValidatorHasNothingToSay() {
        assertTrue("events: ${script.events.size}", script.events.size >= 50)
        assertEquals(script.events.map { it.timeSec }.sorted(), script.events.map { it.timeSec })
        assertEquals(emptyList<String>(), ScriptValidator.validate(script, emptySet()))
    }

    @Test
    fun itExercisesEveryNewField() {
        val acts = script.events.mapNotNull { it.act }.toSet()
        val emotes = script.events.mapNotNull { it.emote }.toSet()
        assertEquals("every act verb", ActVocabulary.VERBS.toSet(), acts)
        assertEquals("every emote", ActVocabulary.EMOTES.toSet(), emotes)
        val expressions = script.events.mapNotNull { it.expression }.map { Expression.fromString(it) }.toSet()
        assertEquals("every expression", setOf(0, 1, 2, 3, 4, 5), expressions)
        val poses = script.events.mapNotNull { it.pose }.toSet()
        assertTrue("walk cycle", "walk_a" in poses && "walk_b" in poses)
        assertTrue("events without a pose", script.events.any { it.pose == null })
        assertTrue("a layer to look at", script.overlayLayers.any { it.id == "logo" })
        assertTrue("captions say what to look at", script.events.count { it.caption != null } >= 8)
    }

    @Test
    fun itRunsAtEveryActingLevelWithoutAFaultAndOffIsTheLegacyFrame() {
        val kfs = EngineHarness.compile(script)
        val total = EngineHarness.totalSec(kfs, 0f)
        val envelope = TestEnvelopes.speechLike(total)
        val legacy = EngineHarness.create(script, envelope, AmplitudeSettings(), kfs)
        val engines = ActingIntensity.values().associateWith {
            EngineHarness.create(script, envelope, AmplitudeSettings(), kfs).also { e -> e.actingContext = ActingContext.create(it.name, "showcase") }
        }
        val bones = StickFigureRig.BONES
        val firstVerbSec = script.events.first { it.act != null }.timeSec - 0.1f
        var angryMoodShows = 0.0
        for (i in 0..(total * EngineHarness.ENV_FPS).toInt()) {
            val t = i.toFloat() / EngineHarness.ENV_FPS
            EngineHarness.seekFrame(legacy, envelope, t)
            for ((level, e) in engines) {
                EngineHarness.seekFrame(e, envelope, t)
                assertTrue("$level t=$t fault", e.actingFault.count == 0)
                for (k in e.renderAngles.indices) {
                    val v = e.renderAngles[k]
                    assertTrue("$level t=$t bone $k not finite", v.isFinite())
                    // The layer's own guarantee: inside the joint limits, widened to include the legacy value (every stage uses this rule).
                    val bone = bones[k]
                    val lo = bone.minAngleDegrees
                    val hi = bone.maxAngleDegrees
                    if (lo != null && hi != null) {
                        val b = legacy.currentAngles[k]
                        assertTrue("$level t=$t bone ${bone.id}: $v outside [${minOf(bone.defaultAngleDegrees + lo, b)}, ${maxOf(bone.defaultAngleDegrees + hi, b)}]",
                            v >= minOf(bone.defaultAngleDegrees + lo, b) - 1e-3f && v <= maxOf(bone.defaultAngleDegrees + hi, b) + 1e-3f)
                    }
                    // WP2.5: act verbs are Direction and run at every level, OFF included, so OFF is the legacy frame only until the first verb.
                    if (level == ActingIntensity.OFF && t < firstVerbSec) assertEquals("OFF t=$t bone $k", legacy.currentAngles[k], v, 0f)
                }
            }
            // The 25 to 33 s hold is angry on a still pose: at Bold the torso differs from the legacy engine's by the mood idles.
            if (t in 28f..32f) angryMoodShows = maxOf(angryMoodShows, abs(engines[ActingIntensity.BOLD]!!.renderAngles[bones.indexOfFirst { it.id == "torso" }] - legacy.currentAngles[bones.indexOfFirst { it.id == "torso" }]).toDouble())
        }
        assertTrue("the angry hold shows the mood: $angryMoodShows", angryMoodShows > 0.3)
    }
}

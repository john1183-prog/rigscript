package com.example

import com.example.data.AnimScript
import com.example.data.OverlayLayer
import com.example.data.ScriptEvent
import com.example.engine.Expression
import com.example.engine.ScriptValidator
import com.example.engine.StickFigureRig
import com.example.engine.acting.ActVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2.5 schema: `pose` is optional (absent or unknown = hold the current pose, the other fields still apply),
 * the closed `act` / `emote` vocabulary, the `actTarget` grammar and the validator rules. Scripts that always carry a
 * pose compile exactly as before (that part is pinned by the goldens, which this WP must leave unchanged).
 */
class IntentSchemaTest {

    private fun compile(vararg events: ScriptEvent) = EngineHarness.compile(AnimScript(events = events.toList()))

    @Test
    fun anEventWithoutAPoseHoldsThePoseAndKeepsItsOtherFields() {
        val kfs = compile(
            ScriptEvent(timeSec = 0f, pose = "wave", duration = 0.5f),
            ScriptEvent(timeSec = 2f, expression = "happy", cameraZoom = 1.4f, act = "nod", actIntensity = 0.5f, emote = "sparkle")
        )
        assertEquals(2, kfs.size)
        assertTrue("hold keeps the target angles", kfs[1].toAngles.contentEquals(kfs[0].toAngles))
        assertTrue("hold starts from the same angles", kfs[1].fromAngles.contentEquals(kfs[0].toAngles))
        assertEquals("wave", kfs[1].toPoseId)
        assertEquals("wave", kfs[1].fromPoseId)
        assertEquals(Expression.HAPPY, kfs[1].expression)
        assertEquals(1.4f, kfs[1].toCameraZoom, 0f)
        assertEquals("nod", kfs[1].act)
        assertEquals(0.5f, kfs[1].actIntensity, 0f)
        assertEquals("sparkle", kfs[1].emote)
    }

    @Test
    fun anUnknownPoseIsNoLongerDroppedItHoldsAndAppliesTheRest() {
        val kfs = compile(
            ScriptEvent(timeSec = 0f, pose = "explain", duration = 0.5f),
            ScriptEvent(timeSec = 3f, pose = "no_such_pose", cameraPanX = 0.2f, caption = "hello")
        )
        assertEquals("the event is kept", 2, kfs.size)
        assertEquals("explain", kfs[1].toPoseId)
        assertEquals(0.2f, kfs[1].toCameraPanX, 0f)
        assertTrue(kfs[1].toAngles.contentEquals(kfs[0].toAngles))
    }

    @Test
    fun aHoldAsTheFirstEventHoldsTheRestPose() {
        val kfs = compile(ScriptEvent(timeSec = 0f, emote = "idea"))
        assertEquals(1, kfs.size)
        assertEquals("stand_straight", kfs[0].toPoseId)
        val rest = FloatArray(StickFigureRig.BONE_COUNT) { StickFigureRig.BONES[it].defaultAngleDegrees }
        assertTrue(kfs[0].toAngles.contentEquals(rest))
    }

    @Test
    fun eventsThatAlwaysCarryAPoseCompileAsBefore() {
        val kfs = compile(
            ScriptEvent(timeSec = 0f, pose = "wave"), ScriptEvent(timeSec = 1f, pose = "think"), ScriptEvent(timeSec = 2f, pose = "explain")
        )
        assertEquals(listOf("wave", "think", "explain"), kfs.map { it.toPoseId })
        assertEquals(listOf("stand_straight", "wave", "think"), kfs.map { it.fromPoseId })
        assertTrue(kfs.all { it.act == null && it.emote == null && it.actIntensity == 0.7f })
    }

    @Test
    fun theTargetGrammar() {
        assertEquals(ActVocabulary.T_LEFT, ActVocabulary.parseTarget("left")!!.kind)
        assertEquals(ActVocabulary.T_RIGHT, ActVocabulary.parseTarget(" Right ")!!.kind)
        assertEquals(ActVocabulary.T_UP, ActVocabulary.parseTarget("up")!!.kind)
        assertEquals(ActVocabulary.T_DOWN, ActVocabulary.parseTarget("down")!!.kind)
        assertEquals(ActVocabulary.T_CAMERA, ActVocabulary.parseTarget("camera")!!.kind)
        val layer = ActVocabulary.parseTarget("layer:logo")
        assertEquals(ActVocabulary.T_LAYER, layer!!.kind)
        assertEquals("logo", layer.layerId)
        val p = ActVocabulary.parseTarget("0.7, 0.25")
        assertEquals(ActVocabulary.T_POINT, p!!.kind)
        assertEquals(0.7f, p.x, 0f)
        assertEquals(0.25f, p.y, 0f)
        for (bad in listOf("", "layer:", "0.5", "a,b", "0.5,", ",0.5", "sideways", "NaN,1")) {
            assertTrue("'$bad' is not a target", ActVocabulary.parseTarget(bad) == null)
        }
        assertTrue(ActVocabulary.parseTarget(null) == null)
    }

    @Test
    fun theVocabularyHasTheTenVerbsAndNineEmotes() {
        assertEquals(10, ActVocabulary.VERBS.size)
        assertEquals(9, ActVocabulary.EMOTES.size)
        assertEquals(ActVocabulary.VERBS.size, ActVocabulary.VERBS.toSet().size)
        assertTrue(ActVocabulary.needsTarget("look_at") && ActVocabulary.needsTarget("point_at"))
        assertTrue(!ActVocabulary.needsTarget("nod") && !ActVocabulary.needsTarget("look_away"))
        assertTrue(ActVocabulary.isGazeVerb("look_at") && ActVocabulary.isGazeVerb("look_away") && !ActVocabulary.isGazeVerb("point_at"))
    }

    private fun warn(script: AnimScript): List<String> = ScriptValidator.validate(script, emptySet())
    private fun has(w: List<String>, part: String) = w.any { it.contains(part) }

    @Test
    fun theValidatorRulesOfTheNewFields() {
        val layer = OverlayLayer(id = "logo", type = "shape", shape = "circle", startSec = 0f, endSec = 5f)
        val script = AnimScript(
            events = listOf(
                ScriptEvent(timeSec = 0f, pose = "stand_straight"),
                ScriptEvent(timeSec = 1f),                                                    // nothing at all
                ScriptEvent(timeSec = 2f, act = "dance"),                                     // unknown verb
                ScriptEvent(timeSec = 5f, emote = "confetti"),                                // unknown emote
                ScriptEvent(timeSec = 8f, act = "look_at"),                                   // needs a target
                ScriptEvent(timeSec = 11f, act = "point_at", actTarget = "layer:missing"),    // layer not found
                ScriptEvent(timeSec = 14f, act = "nod", actIntensity = 1.5f),                 // out of range
                ScriptEvent(timeSec = 14.4f, act = "emphasize"),                              // stacks
                ScriptEvent(timeSec = 20f, pose = "no_such_pose", expression = "happy")       // unknown pose
            ),
            overlayLayers = listOf(layer)
        )
        val w = warn(script)
        assertTrue(w.toString(), has(w, "no pose and no other field"))
        assertTrue(has(w, "Unknown act verb(s), ignored: dance"))
        assertTrue(has(w, "Unknown emote(s), ignored: confetti"))
        assertTrue(has(w, "act look_at at 8.00s needs a valid actTarget"))
        assertTrue(has(w, "layer 'missing' doesn't match any layer's id"))
        assertTrue(has(w, "actIntensity 1.5 is outside 0..1"))
        assertTrue(has(w, "under 1 s apart"))
        assertTrue(has(w, "Unknown pose id(s)") && has(w, "HOLD the current pose"))
    }

    @Test
    fun aCleanScriptWithTheNewFieldsGetsNoNewWarning() {
        val script = AnimScript(
            events = listOf(
                ScriptEvent(timeSec = 0f, pose = "stand_straight"),
                ScriptEvent(timeSec = 2f, act = "nod"),
                ScriptEvent(timeSec = 6f, act = "look_at", actTarget = "layer:logo", emote = "idea"),
                ScriptEvent(timeSec = 10f, act = "point_at", actTarget = "0.8,0.3"),
                ScriptEvent(timeSec = 14f, act = "look_away", actTarget = "left")
            ),
            overlayLayers = listOf(OverlayLayer(id = "logo", type = "shape", shape = "circle", startSec = 0f, endSec = 30f))
        )
        val w = warn(script)
        for (part in listOf("Unknown act", "Unknown emote", "actTarget", "actIntensity", "no pose and no other", "under 1 s apart")) {
            assertTrue("unexpected warning '$part': $w", !has(w, part))
        }
        assertNotNull(w)
    }
}

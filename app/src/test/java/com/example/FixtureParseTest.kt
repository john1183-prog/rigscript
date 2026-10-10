package com.example

import com.example.data.BuiltInSoundEffects
import com.example.engine.Expression
import com.example.engine.ScriptValidator
import com.example.engine.StickFigureRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP0.3: the legacy-script corpus loads through the real parser and covers what it claims to cover.
 * Expectations about the validator's wording were derived by reading ScriptValidator.kt.
 */
class FixtureParseTest {

    /** Ids a real project library would hold for these fixtures: the built-ins plus one custom clip. */
    private val libraryIds: Set<String> = BuiltInSoundEffects.ALL.map { it.id }.toSet() + "custom_boom"

    private fun warnings(name: String): List<String> =
        ScriptValidator.validate(Fixtures.script(name), libraryIds)

    /** Validator phrases that mean a real defect, as opposed to an approximate-geometry hint. */
    private val defectMarkers = listOf(
        "Unknown ", "pose id that isn't", "BOTH parentBone", "doesn't match any layer's id",
        "parentLayer cycle", "will never be visible", "not in this project's library",
        "no sound effects imported"
    )

    @Test
    fun everyFixtureParsesThroughTheRealParser() {
        for (name in Fixtures.SCRIPT_NAMES) {
            val script = Fixtures.script(name)
            assertEquals("$name: version", "1.0", script.version)
            assertTrue("$name: has events", script.events.isNotEmpty())
        }
    }

    @Test
    fun minimalFixtureFallsBackToTheDataClassDefaults() {
        val script = Fixtures.script("01_minimal")
        assertEquals(2, script.events.size)
        val e = script.events[0]
        assertEquals(0.5f, e.duration, 0f)
        assertEquals("ease_in_out", e.ease)
        assertEquals(280f, e.springStiffness, 0f)
        assertEquals(28f, e.springDamping, 0f)
        assertNull(e.expression)
        assertTrue(script.overlayLayers.isEmpty())
        assertTrue(script.blinkEvents.isEmpty())
    }

    @Test
    fun posesFixtureUsesEveryBuiltInPoseExpressionAndEase() {
        val script = Fixtures.script("02_poses_expressions")
        assertEquals(StickFigureRig.BUILT_IN_POSE_INDEX.keys, script.events.map { it.pose }.toSet())
        val expressions = script.events.mapNotNull { it.expression }.map { Expression.fromString(it) }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4, 5), expressions)
        assertEquals(
            setOf("linear", "ease_in", "ease_out", "ease_in_out", "bounce", "elastic_out", "spring", "rigid"),
            script.events.map { it.ease }.toSet()
        )
        val springs = script.events.filter { it.ease == "spring" }
            .map { it.springStiffness to it.springDamping }.toSet()
        assertTrue("distinct spring settings: $springs", springs.size >= 3)
    }

    @Test
    fun cameraSceneCaptionFixtureCoversTheClosedVocabularies() {
        val events = Fixtures.script("03_camera_scene_captions").events
        assertEquals(
            setOf("none", "mountains", "city", "trees", "clouds", "room", "beach"),
            events.mapNotNull { it.sceneShape }.toSet()
        )
        assertEquals(
            setOf("none", "rain", "snow", "fog", "stars"),
            events.mapNotNull { it.sceneAtmosphere }.toSet()
        )
        assertTrue(events.any { it.cameraZoom != null })
        assertTrue(events.any { it.cameraPanX != null })
        assertTrue(events.any { it.cameraPanY != null })
        assertTrue(events.any { (it.cameraShake ?: 0f) > 0f })
        assertEquals(setOf("solid", "gradient"), events.mapNotNull { it.backgroundStyle }.toSet())
        assertTrue(events.any { it.figureOpacity != null && it.headScale != null && it.figureScale != null })
        assertTrue(events.any { it.boneColor != null && it.headColor != null && it.jointColor != null })
        assertTrue(events.any { it.mouthColor != null && it.eyeColor != null && it.eyebrowColor != null })
        val captions = events.mapNotNull { it.caption }
        assertTrue("a caption long enough to wrap", captions.any { it.length > 80 })
        assertTrue("a caption with a line break", captions.any { it.contains('\n') })
        assertTrue("non-ASCII survives the UTF-8 resource round trip", captions.any { it.contains("\u00e9") })
    }

    @Test
    fun overlayFixtureCoversEveryOverlayFeature() {
        val layers = Fixtures.script("04_overlays").overlayLayers
        val styles = setOf("fade", "pop", "zoom", "slideup", "slidedown", "none")
        assertEquals(setOf("text", "shape", "particles", "figure"), layers.map { it.type }.toSet())
        assertEquals(
            setOf("rect", "circle", "line", "arrow", "cross"),
            layers.filter { it.type == "shape" }.map { it.shape }.toSet()
        )
        assertEquals(setOf("circle", "rect"), layers.filter { it.type == "particles" }.map { it.particleShape }.toSet())
        assertTrue(layers.map { it.enterStyle }.toSet().containsAll(styles))
        assertTrue(layers.map { it.exitStyle }.toSet().containsAll(styles))
        assertTrue(layers.map { it.enterEase }.toSet().containsAll(listOf("ease_out", "back", "elastic_out")))
        assertEquals(setOf("upper", "center", "lower"), layers.mapNotNull { it.slot }.toSet())
        assertEquals(setOf("left", "center", "right"), layers.map { it.align }.toSet())
        assertEquals(setOf("none", "projectile", "bounce"), layers.map { it.physics }.toSet())
        assertTrue("screenSpace", layers.any { it.screenSpace })
        assertTrue("world-space twin of the HUD layer", layers.any { !it.screenSpace && it.id == "t_world_space" })
        assertTrue("anim", layers.any { it.anim != null })
        val keyframeEases = layers.mapNotNull { it.anim }.flatten().map { it.ease }.toSet()
        assertTrue("keyframe eases: $keyframeEases", keyframeEases.containsAll(listOf("linear", "ease_out", "back", "rigid", "spring", "bounce")))
        assertEquals(
            setOf("head", "lower_arm_r", "torso", "lower_leg_l"),
            layers.mapNotNull { it.parentBone }.toSet()
        )
        assertTrue("parentLayer", layers.any { !it.parentLayer.isNullOrBlank() })
        assertTrue("trail", layers.any { it.trail })
        assertTrue("behind the figure", layers.any { !it.inFrontOfFigure })
        assertTrue("multi-line text", layers.any { it.text?.contains('\n') == true })
        assertTrue("glow colour and gradient", layers.any { it.glow && it.glowColor != null && it.gradientColor != null })
        assertTrue("rotation, scale and opacity", layers.any { it.rotationDeg != 0f && it.scale != 1f && it.opacity != 1f })
        val ids = layers.map { it.id }
        assertEquals("unique ids", ids.size, ids.toSet().size)
        assertTrue("parentLayer targets exist", layers.mapNotNull { it.parentLayer }.all { it in ids })
        assertTrue("windows are positive", layers.all { it.endSec > it.startSec })
    }

    @Test
    fun soundEffectFixtureHasBuiltInCustomUnknownLoudAndSilentCues() {
        val events = Fixtures.script("05_sound_effects").events
        val cues = events.mapNotNull { it.soundEffect }.toSet()
        assertTrue(
            "cue ids: $cues",
            cues.containsAll(
                listOf("click", "confirm", "error", "glitch", "drop", "tick", "toggle", "bong", "custom_boom", "unknown_sfx_id")
            )
        )
        assertTrue("a cue louder than 1.0", events.any { it.soundEffect != null && it.soundEffectVolume > 1f })
        assertTrue("a silent cue", events.any { it.soundEffect != null && it.soundEffectVolume == 0f })
        val sameTime = events.groupBy { it.timeSec }.values.any { group -> group.count { it.soundEffect != null } >= 2 }
        assertTrue("two cues on one timeSec", sameTime)
        val missing = warnings("05_sound_effects").filter { it.contains("not in this project's library") }
        assertEquals(missing.toString(), 1, missing.size)
        assertTrue(missing[0].contains("unknown_sfx_id"))
        assertFalse(missing[0].contains("custom_boom"))
    }

    @Test
    fun longFixtureIsAboutTenMinutesAndDense() {
        val script = Fixtures.script("06_long_dense")
        val times = script.events.map { it.timeSec }
        assertTrue("events: ${times.size}", times.size >= 200)
        assertTrue("last event at ${times.last()}", times.last() >= 570f && times.last() <= 600f)
        assertTrue("event times strictly increase", times.zipWithNext().all { (a, b) -> b > a })
        assertTrue("layers: ${script.overlayLayers.size}", script.overlayLayers.size >= 100)
        assertTrue("blinks: ${script.blinkEvents.size}", script.blinkEvents.size >= 100)
        assertTrue(script.overlayLayers.all { it.endSec > it.startSec && it.endSec <= 600f })
        assertTrue(script.events.any { it.soundEffect != null })
    }

    @Test
    fun cleanFixturesHaveNoDefectWarnings() {
        val clean = listOf("01_minimal", "02_poses_expressions", "03_camera_scene_captions", "04_overlays", "06_long_dense")
        for (name in clean) {
            val bad = warnings(name).filter { w -> defectMarkers.any { w.contains(it) } }
            assertTrue("$name has defect warnings: $bad", bad.isEmpty())
        }
    }

    @Test
    fun edgeFixtureTriggersEveryValidatorRule() {
        val all = warnings("07_edge_cases")
        val expected = listOf(
            "Unknown pose id(s)", "Unknown ease value(s)", "Unknown sceneShape value(s)",
            "Unknown sceneAtmosphere value(s)", "Unknown backgroundStyle value(s)",
            "not in this project's library", "crop the figure off-screen", "Two or more events share",
            "Unknown overlay layer type(s)", "Unknown overlay shape value(s)",
            "Unknown overlay enterStyle value(s)", "Unknown overlay exitStyle value(s)",
            "Unknown overlay enterEase value(s)", "Unknown overlay exitEase value(s)",
            "Unknown overlay keyframe ease value(s)", "Unknown overlay slot value(s)",
            "Unknown overlay particleShape value(s)", "isn't a BUILT-IN pose",
            "Unknown overlay physics value(s)", "Unknown overlay parentBone value(s)",
            "BOTH parentBone and parentLayer", "doesn't match any layer's id",
            "parentLayer cycle", "will never be visible"
        )
        for (marker in expected) {
            assertTrue(
                "no validator warning contains: $marker\nGot:\n${all.joinToString("\n")}",
                all.any { it.contains(marker) }
            )
        }
    }
}

package com.example

import com.example.data.AnimScript
import com.example.data.OverlayAnimKeyframe
import com.example.data.OverlayLayer
import com.example.engine.EasingMath
import com.example.engine.OverlayResolver
import com.example.engine.ScriptValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP1.4: the three rules that change how overlay layers resolve or validate.
 *  - R7: parenting wins over `screenSpace` (the effective draw space follows what the layer is attached to).
 *  - R8: keyframed opacity that ends visible, with an exit style set, is warned about (behaviour is unchanged).
 *  - R9: a property left out of a middle `anim` keyframe is interpolated across it, not held.
 */
class OverlayParityRulesTest {

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun layer(
        id: String,
        screenSpace: Boolean = false,
        parentBone: String? = null,
        parentLayer: String? = null,
        x: Float = 0.5f,
        y: Float = 0.5f,
        anim: List<OverlayAnimKeyframe>? = null,
        exitStyle: String = "none"
    ) = OverlayLayer(
        id = id, type = "shape", startSec = 0f, endSec = 10f, x = x, y = y,
        enterStyle = "none", exitStyle = exitStyle,
        screenSpace = screenSpace, parentBone = parentBone, parentLayer = parentLayer, anim = anim
    )

    private val anchors = mapOf("lower_arm_r" to (0.6f to 0.4f))

    /** Resolved layers in input order (all of them are visible at t = 5 s). */
    private fun resolve(layers: List<OverlayLayer>) =
        OverlayResolver.applyParenting(OverlayResolver.resolveTimeBased(layers, 5f), anchors)

    private fun warnings(vararg layers: OverlayLayer): List<String> =
        ScriptValidator.validate(AnimScript(overlayLayers = layers.toList()), emptySet())

    private fun List<String>.anyContains(text: String) = any { it.contains(text) }

    private val r7Text = "screenSpace=true that also have a parent"
    private val r8Text = "keyframed opacity takes precedence over the exit style"

    // ── R7: the effective space follows the parent ───────────────────────────────────────────────────────────

    @Test
    fun anUnparentedLayerKeepsItsOwnSpace() {
        val r = resolve(listOf(layer("a", screenSpace = true), layer("b", screenSpace = false)))
        assertTrue(r[0].screenSpace)
        assertFalse(r[1].screenSpace)
    }

    @Test
    fun aBoneParentedLayerIsInWorldSpaceWhateverItsFlagSays() {
        val r = resolve(listOf(layer("hand", screenSpace = true, parentBone = "lower_arm_r", x = 0.1f, y = 0f)))
        assertFalse(r[0].screenSpace)
        // The position rule itself is unchanged: bone anchor plus the local offset.
        assertEquals(0.7f, r[0].x, 1e-6f)
        assertEquals(0.4f, r[0].y, 1e-6f)
    }

    @Test
    fun aLayerParentedToAWorldLayerIsInWorldSpace() {
        val r = resolve(listOf(
            layer("parent", screenSpace = false, x = 0.3f, y = 0.3f),
            layer("child", screenSpace = true, parentLayer = "parent", x = 0.1f, y = 0f)
        ))
        assertFalse(r[1].screenSpace)
        assertEquals(0.4f, r[1].x, 1e-6f)
        assertEquals(0.3f, r[1].y, 1e-6f)
    }

    @Test
    fun aChildOfAScreenSpaceLayerInheritsItsSpace() {
        val r = resolve(listOf(
            layer("hud", screenSpace = true),
            layer("childDefault", screenSpace = false, parentLayer = "hud"),
            layer("childExplicit", screenSpace = true, parentLayer = "hud")
        ))
        assertTrue(r[0].screenSpace)
        assertTrue(r[1].screenSpace)     // the default false is overridden by the parent: no drift under camera moves
        assertTrue(r[2].screenSpace)
    }

    @Test
    fun theSpaceTravelsDownAChainFromItsRoot() {
        val screenRoot = resolve(listOf(
            layer("root", screenSpace = true),
            layer("mid", parentLayer = "root"),
            layer("leaf", parentLayer = "mid")
        ))
        assertTrue(screenRoot.all { it.screenSpace })

        val boneRoot = resolve(listOf(
            layer("root", screenSpace = true, parentBone = "lower_arm_r"),
            layer("leaf", screenSpace = true, parentLayer = "root")
        ))
        assertTrue(boneRoot.none { it.screenSpace })   // the root is pinned to the figure, so everything under it is in world space
    }

    @Test
    fun anAttachmentThatCannotBeResolvedLeavesTheLayerUnparented() {
        // An unknown bone (no anchor) and an unknown parent id both mean "treated as unparented": the own flag applies.
        val r = resolve(listOf(
            layer("a", screenSpace = true, parentBone = "no_such_bone"),
            layer("b", screenSpace = true, parentLayer = "no_such_layer")
        ))
        assertTrue(r[0].screenSpace)
        assertTrue(r[1].screenSpace)
    }

    @Test
    fun aParentCycleStillTerminatesAndKeepsOwnFlags() {
        val r = resolve(listOf(
            layer("a", screenSpace = true, parentLayer = "b"),
            layer("b", screenSpace = false, parentLayer = "a")
        ))
        assertEquals(2, r.size)     // it resolves, and does not loop (the exact flags are not part of the contract)
    }

    @Test
    fun theValidatorReportsAnExplicitScreenSpaceThatParentingOverrides() {
        assertTrue(warnings(layer("hand", screenSpace = true, parentBone = "lower_arm_r")).anyContains(r7Text))
        assertTrue(warnings(
            layer("p", screenSpace = false),
            layer("c", screenSpace = true, parentLayer = "p", x = 0.1f, y = 0.1f)
        ).anyContains(r7Text))
    }

    @Test
    fun theValidatorStaysQuietWhereNothingIsOverridden() {
        assertFalse(warnings(layer("alone", screenSpace = true)).anyContains(r7Text))
        assertFalse(warnings(layer("hand", screenSpace = false, parentBone = "lower_arm_r")).anyContains(r7Text))
        assertFalse(warnings(layer("hand", screenSpace = true, parentBone = "no_such_bone")).anyContains(r7Text))
        // A child that agrees with, or inherits from, a screen-space parent is fine.
        assertFalse(warnings(
            layer("hud", screenSpace = true),
            layer("c1", screenSpace = true, parentLayer = "hud", x = 0.1f, y = 0.1f),
            layer("c2", screenSpace = false, parentLayer = "hud", x = 0.9f, y = 0.9f)
        ).anyContains(r7Text))
    }

    @Test
    fun theR7WarningNamesTheLayer() {
        val w = warnings(layer("tag", screenSpace = true, parentBone = "lower_arm_r")).first { it.contains(r7Text) }
        assertTrue(w.endsWith("tag"))
    }

    // ── R8: keyed opacity vs the exit style ──────────────────────────────────────────────────────────────────

    private val fadeIn = listOf(OverlayAnimKeyframe(t = 0f, opacity = 0f), OverlayAnimKeyframe(t = 1f, opacity = 1f))

    @Test
    fun keyedOpacityThatEndsVisibleWithAnExitStyleIsReported() {
        assertTrue(warnings(layer("a", anim = fadeIn, exitStyle = "fade")).anyContains(r8Text))
        assertTrue(warnings(layer("b", anim = fadeIn, exitStyle = "slide_up")).anyContains(r8Text))
    }

    @Test
    fun theValidatorStaysQuietWhenThereIsNoCutOutToWarnAbout() {
        assertFalse(warnings(layer("a", anim = fadeIn, exitStyle = "none")).anyContains(r8Text))
        // The author fades out with the keyframes themselves.
        val fadesOut = listOf(OverlayAnimKeyframe(t = 0f, opacity = 1f), OverlayAnimKeyframe(t = 1f, opacity = 0f))
        assertFalse(warnings(layer("b", anim = fadesOut, exitStyle = "fade")).anyContains(r8Text))
        // No anim, or an anim that does not key opacity: the exit fade works as before.
        assertFalse(warnings(layer("c", anim = null, exitStyle = "fade")).anyContains(r8Text))
        assertFalse(warnings(layer("d", anim = listOf(OverlayAnimKeyframe(t = 0f, x = 0.2f), OverlayAnimKeyframe(t = 1f, x = 0.8f)), exitStyle = "fade")).anyContains(r8Text))
    }

    @Test
    fun opacityKeyedOnlyEarlyAndHeldIsStillACutOut() {
        val heldVisible = listOf(OverlayAnimKeyframe(t = 0f, opacity = 0.2f), OverlayAnimKeyframe(t = 0.3f, opacity = 1f), OverlayAnimKeyframe(t = 1f, x = 0.5f))
        assertTrue(warnings(layer("a", anim = heldVisible, exitStyle = "fade")).anyContains(r8Text))
    }

    @Test
    fun theBehaviourBehindR8IsUnchangedKeyedOpacityStillWins() {
        // exitStyle "fade" with keyed opacity: at the very end of the window the layer is at its keyed opacity, not faded out.
        val l = layer("a", anim = listOf(OverlayAnimKeyframe(t = 0f, opacity = 1f), OverlayAnimKeyframe(t = 1f, opacity = 1f)), exitStyle = "fade")
        val atEnd = OverlayResolver.resolveTimeBased(listOf(l), 9.99f).single()
        assertEquals(1f, atEnd.opacity, 1e-6f)
    }

    // ── R9: a property omitted from a middle keyframe ────────────────────────────────────────────────────────

    private fun xAt(progress: Float, vararg kfs: OverlayAnimKeyframe, baseX: Float = 0.5f): Float {
        val l = layer("a", x = baseX, anim = kfs.toList())
        return OverlayResolver.resolveTimeBased(listOf(l), progress * 10f).single().localX
    }

    @Test
    fun aPropertyOmittedFromAMiddleKeyframeMovesSmoothlyAcrossIt() {
        val kfs = arrayOf(
            OverlayAnimKeyframe(t = 0f, x = 0.1f),
            OverlayAnimKeyframe(t = 0.5f, opacity = 1f),          // says nothing about x
            OverlayAnimKeyframe(t = 1f, x = 0.9f)
        )
        assertEquals(0.1f, xAt(0f, *kfs), 1e-5f)
        assertEquals(0.3f, xAt(0.25f, *kfs), 1e-5f)               // used to hold 0.1 until the middle
        assertEquals(0.5f, xAt(0.5f, *kfs), 1e-5f)
        assertEquals(0.7f, xAt(0.75f, *kfs), 1e-5f)
        assertEquals(0.9f, xAt(0.9999f, *kfs), 1e-3f)             // a layer is gone at exactly its end, so look just before it
    }

    @Test
    fun twoKeyframesThatBothDefineThePropertyBehaveAsBefore() {
        val kfs = arrayOf(OverlayAnimKeyframe(t = 0f, x = 0.2f, ease = "ease_in"), OverlayAnimKeyframe(t = 1f, x = 0.8f))
        for (p in listOf(0.1f, 0.5f, 0.9f)) {
            // The old rule, written out: start + (end - start) * ease(segment progress).
            assertEquals(0.2f + (0.8f - 0.2f) * EasingMath.ease("ease_in", p), xAt(p, *kfs), 1e-6f)
        }
        // Three keyframes that all define it: the active segment decides, as it always did.
        val three = arrayOf(OverlayAnimKeyframe(t = 0f, x = 0.0f), OverlayAnimKeyframe(t = 0.5f, x = 1.0f), OverlayAnimKeyframe(t = 1f, x = 0.0f))
        assertEquals(0.5f, xAt(0.25f, *three), 1e-5f)
        assertEquals(0.5f, xAt(0.75f, *three), 1e-5f)
    }

    @Test
    fun theSpanUsesTheEaseOfTheKeyframeItStartsAt() {
        val kfs = arrayOf(
            OverlayAnimKeyframe(t = 0f, x = 0.1f, ease = "ease_in"),
            OverlayAnimKeyframe(t = 0.5f, opacity = 1f, ease = "linear"),   // its ease is not used for x
            OverlayAnimKeyframe(t = 1f, x = 0.9f)
        )
        val expected = 0.1f + (0.9f - 0.1f) * EasingMath.ease("ease_in", 0.25f / 1f)
        assertEquals(expected, xAt(0.25f, *kfs), 1e-6f)
    }

    @Test
    fun aPropertyNoEarlierKeyframeDefinesStartsFromTheLayersOwnValue() {
        val kfs = arrayOf(OverlayAnimKeyframe(t = 0.5f, x = 0.9f))
        assertEquals(0.5f, xAt(0f, *kfs, baseX = 0.5f), 1e-5f)
        assertEquals(0.7f, xAt(0.25f, *kfs, baseX = 0.5f), 1e-5f)         // halfway between the base 0.5 and 0.9
        assertEquals(0.9f, xAt(0.5f, *kfs, baseX = 0.5f), 1e-5f)
    }

    @Test
    fun theLastDefinedValueHoldsAfterTheLastKeyframeThatDefinesIt() {
        val kfs = arrayOf(
            OverlayAnimKeyframe(t = 0f, x = 0.1f),
            OverlayAnimKeyframe(t = 0.5f, x = 0.4f),
            OverlayAnimKeyframe(t = 1f, opacity = 0.5f)
        )
        assertEquals(0.4f, xAt(0.75f, *kfs), 1e-5f)
        assertEquals(0.4f, xAt(0.9999f, *kfs), 1e-5f)
    }

    @Test
    fun eachPropertyKeepsItsOwnSpans() {
        val l = layer("a", anim = listOf(
            OverlayAnimKeyframe(t = 0f, x = 0.0f, opacity = 0f),
            OverlayAnimKeyframe(t = 0.5f, opacity = 1f),               // x omitted here, opacity defined
            OverlayAnimKeyframe(t = 1f, x = 1.0f, opacity = 0f)
        ))
        val r = OverlayResolver.resolveTimeBased(listOf(l), 2.5f).single()     // progress 0.25
        assertEquals(0.25f, r.localX, 1e-5f)       // x: 0 -> 1 across the whole run
        assertEquals(0.5f, r.opacity, 1e-5f)       // opacity: 0 -> 1 over the first half
    }
}

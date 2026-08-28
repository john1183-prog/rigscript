package com.example.engine

import com.example.data.BoneDef
import com.example.data.PoseDef

/**
 * Canonical stick-figure rig: 10 bones, parent-first ordering.
 *
 * Coordinate system (Android Canvas, Y-down):
 *   0°   = right   →
 *  90°   = down    ↓
 * -90°   = up      ↑
 * 180°   = left    ←
 *
 * Root position (rootX, rootY) is the pelvis — the junction where the torso
 * and both legs branch. All bones with parentId == null start from this point.
 *
 * Pose rotations are RELATIVE offsets added to each bone's [BoneDef.defaultAngleDegrees].
 */
object StickFigureRig {

    // ─── Bone definitions ────────────────────────────────────────────────────

    val BONES: List<BoneDef> = listOf(
        // ── Spine ─────────────────────────────────────────────────────────────
        BoneDef("torso",         null,          0.22f,  -90f),          // up ↑
        BoneDef("head",          "torso",       0.05f, 0f,
            isHeadBone = true, headNormalizedRadius = 0.048f),           // circle at torso tip

        // ── Right arm (actor's right = screen left for frontal view) ──────────
        BoneDef("upper_arm_r",   "torso",       0.12f,  160f),          // abs ≈ 70° (hang down-right)
        BoneDef("lower_arm_r",   "upper_arm_r", 0.10f,  10f),           // abs ≈ 80°

        // ── Left arm ──────────────────────────────────────────────────────────
        BoneDef("upper_arm_l",   "torso",       0.12f, -160f),          // abs ≈ 110° (hang down-left)
        BoneDef("lower_arm_l",   "upper_arm_l", 0.10f, -10f),           // abs ≈ 100°

        // ── Right leg ─────────────────────────────────────────────────────────
        BoneDef("upper_leg_r",   null,          0.14f,  80f),           // mostly down, slight right
        BoneDef("lower_leg_r",   "upper_leg_r", 0.14f,  5f),

        // ── Left leg ──────────────────────────────────────────────────────────
        BoneDef("upper_leg_l",   null,          0.14f,  100f),          // mostly down, slight left
        BoneDef("lower_leg_l",   "upper_leg_l", 0.14f, -5f)
    )

    val BONE_COUNT: Int = BONES.size

    /** O(1) bone-index lookup used in the hot render path. */
    val BONE_INDEX: Map<String, Int> = BONES.mapIndexed { i, b -> b.id to i }.toMap()

    // ─── Built-in pose library (20 poses) ───────────────────────────────────

    val BUILT_IN_POSES: List<PoseDef> = listOf(

        PoseDef("stand_straight", "Stand Straight", "builtin", true,
            emptyMap()),   // all zeros = default rest position

        PoseDef("wave", "Wave", "builtin", true, mapOf(
            "upper_arm_r" to -130f,
            "lower_arm_r" to -20f
        )),

        PoseDef("think", "Think", "builtin", true, mapOf(
            "torso"        to  8f,
            "head"         to -20f,
            "upper_arm_r"  to -80f,
            "lower_arm_r"  to -100f
        )),

        PoseDef("explain", "Explain", "builtin", true, mapOf(
            "torso"        to -5f,
            "upper_arm_r"  to -75f,
            "lower_arm_r"  to -30f,
            "upper_arm_l"  to  75f,
            "lower_arm_l"  to  30f
        )),

        PoseDef("walk_a", "Walk A", "builtin", true, mapOf(
            "torso"        to  7f,
            "upper_leg_r"  to -28f,
            "lower_leg_r"  to -22f,
            "upper_leg_l"  to  32f,
            "lower_leg_l"  to  12f,
            "upper_arm_r"  to  38f,
            "upper_arm_l"  to -38f
        )),

        PoseDef("walk_b", "Walk B", "builtin", true, mapOf(
            "torso"        to  7f,
            "upper_leg_r"  to  32f,
            "lower_leg_r"  to  12f,
            "upper_leg_l"  to -28f,
            "lower_leg_l"  to -22f,
            "upper_arm_r"  to -38f,
            "upper_arm_l"  to  38f
        )),

        PoseDef("jog_a", "Jog A", "builtin", true, mapOf(
            "torso"        to  14f,
            "upper_leg_r"  to -50f,
            "lower_leg_r"  to -55f,
            "upper_leg_l"  to  55f,
            "lower_leg_l"  to  28f,
            "upper_arm_r"  to  60f,
            "lower_arm_r"  to  30f,
            "upper_arm_l"  to -60f,
            "lower_arm_l"  to -30f
        )),

        PoseDef("jog_b", "Jog B", "builtin", true, mapOf(
            "torso"        to  14f,
            "upper_leg_r"  to  55f,
            "lower_leg_r"  to  28f,
            "upper_leg_l"  to -50f,
            "lower_leg_l"  to -55f,
            "upper_arm_r"  to -60f,
            "lower_arm_r"  to -30f,
            "upper_arm_l"  to  60f,
            "lower_arm_l"  to  30f
        )),

        PoseDef("jump", "Jump", "builtin", true, mapOf(
            "torso"        to -8f,
            "head"         to  5f,
            "upper_arm_r"  to -130f,
            "lower_arm_r"  to -15f,
            "upper_arm_l"  to  130f,
            "lower_arm_l"  to  15f,
            "upper_leg_r"  to -32f,
            "lower_leg_r"  to  68f,
            "upper_leg_l"  to  32f,
            "lower_leg_l"  to -68f
        )),

        PoseDef("tired", "Tired", "builtin", true, mapOf(
            "torso"        to  35f,
            "head"         to -38f,
            "upper_arm_r"  to  48f,
            "lower_arm_r"  to  42f,
            "upper_arm_l"  to -48f,
            "lower_arm_l"  to -42f,
            "upper_leg_r"  to  -8f,
            "upper_leg_l"  to   8f
        )),

        PoseDef("lazy", "Lazy", "builtin", true, mapOf(
            "torso"        to  22f,
            "head"         to -14f,
            "upper_arm_r"  to  28f,
            "upper_arm_l"  to  -5f,
            "upper_leg_r"  to -14f,
            "upper_leg_l"  to   6f
        )),

        PoseDef("sleepy", "Sleepy", "builtin", true, mapOf(
            "torso"        to  18f,
            "head"         to -58f,
            "upper_arm_r"  to  55f,
            "lower_arm_r"  to  52f,
            "upper_arm_l"  to -55f,
            "lower_arm_l"  to -52f
        )),

        PoseDef("confused", "Confused", "builtin", true, mapOf(
            "torso"        to  10f,
            "head"         to -32f,
            "upper_arm_r"  to -78f,
            "lower_arm_r"  to -58f,
            "upper_arm_l"  to -18f
        )),

        PoseDef("excited", "Excited", "builtin", true, mapOf(
            "torso"        to -10f,
            "head"         to  8f,
            "upper_arm_r"  to -112f,
            "lower_arm_r"  to -25f,
            "upper_arm_l"  to  112f,
            "lower_arm_l"  to  25f,
            "upper_leg_r"  to -12f,
            "upper_leg_l"  to  12f
        )),

        PoseDef("shrug", "Shrug", "builtin", true, mapOf(
            "torso"        to  5f,
            "head"         to -10f,
            "upper_arm_r"  to -52f,
            "lower_arm_r"  to -78f,
            "upper_arm_l"  to  52f,
            "lower_arm_l"  to  78f
        )),

        PoseDef("point_right", "Point Right", "builtin", true, mapOf(
            "torso"        to -8f,
            "head"         to -8f,
            "upper_arm_r"  to -78f,
            "lower_arm_r"  to -5f
        )),

        PoseDef("point_left", "Point Left", "builtin", true, mapOf(
            "torso"        to  8f,
            "head"         to  8f,
            "upper_arm_l"  to  78f,
            "lower_arm_l"  to  5f
        )),

        PoseDef("point_up", "Point Up", "builtin", true, mapOf(
            "torso"        to -5f,
            "head"         to -5f,
            "upper_arm_r"  to -148f,
            "lower_arm_r"  to  18f
        )),

        PoseDef("celebrate", "Celebrate", "builtin", true, mapOf(
            "torso"        to -8f,
            "head"         to  5f,
            "upper_arm_r"  to -132f,
            "lower_arm_r"  to -25f,
            "upper_arm_l"  to  132f,
            "lower_arm_l"  to  25f,
            "upper_leg_r"  to  -8f,
            "upper_leg_l"  to   8f
        )),

        PoseDef("sit", "Sit", "builtin", true, mapOf(
            "torso"        to  8f,
            "upper_leg_r"  to -72f,
            "lower_leg_r"  to  92f,
            "upper_leg_l"  to  72f,
            "lower_leg_l"  to -92f
        )),

        // ── New poses ────────────────────────────────────────────────────────────

        // Arms raised higher and wider than explain — "let me show you this",
        // presenting information, more emphatic than the default explain gesture.
        PoseDef("present", "Present", "builtin", true, mapOf(
            "torso"        to -8f,
            "head"         to  5f,
            "upper_arm_r"  to -100f,
            "lower_arm_r"  to -45f,
            "upper_arm_l"  to  100f,
            "lower_arm_l"  to  45f
        )),

        // Right arm bent inward pointing at own chest — "I", "me", "my point",
        // "why am I saying this". Fills a gap that explain and point_* can't cover.
        PoseDef("point_self", "Point Self", "builtin", true, mapOf(
            "torso"        to  5f,
            "upper_arm_r"  to -55f,
            "lower_arm_r"  to -108f
        )),

        // Arms low and open, forearms angled upward (palms-up feel) — receptive,
        // "here's the thing", "what can I say". Softer and lower than shrug.
        PoseDef("open_hands", "Open Hands", "builtin", true, mapOf(
            "torso"        to  5f,
            "head"         to  3f,
            "upper_arm_r"  to -42f,
            "lower_arm_r"  to -70f,
            "upper_arm_l"  to  42f,
            "lower_arm_l"  to  70f
        ))
    )

    /** Index of built-in poses by ID for O(1) lookups during timeline compilation. */
    val BUILT_IN_POSE_INDEX: Map<String, PoseDef> = BUILT_IN_POSES.associateBy { it.id }

    /**
     * Foot-plant hip-bob correction for walk_a/walk_b — see
     * V2_DECISIONS.md's "Walk cycle: analytic foot-plant hip-bob" entry for
     * the full derivation. The root (pelvis) is otherwise vertically static
     * (see this file's own header comment: rootY only moves via a script's
     * explicit figureY override, never automatically from leg motion), so
     * without this, the hip stays dead-still while the legs scissor beneath
     * it — the "architecturally inverted" walk cycle bug.
     *
     * This is the single shared ground-contact Y (same normalized units as
     * [BoneDef.normalizedLength] — multiply by the render path's own
     * `scale`, not minDim/canvasH directly) both walk_a's and walk_b's
     * stance (planted) foot should land on. It's the MIDPOINT of the two
     * poses' own natural stance-foot depth, computed via forward kinematics
     * from their actual authored angles (hip fixed at the origin) — not
     * either pose's own raw depth — so a full walk_a<->walk_b cycle has
     * zero net vertical drift.
     *
     * [PlaybackEngine.currentHipBobOffset] computes `this - liveStanceDepth`
     * fresh EVERY FRAME from the currently-blended leg angles, not by
     * interpolating a precomputed per-pose offset — that simpler approach
     * was tried first and rejected: verified numerically (Python) that
     * lerping a fixed walk_a/walk_b endpoint offset drifts up to ~0.06
     * normalized units (real, non-trivial — around 65px at 1920x1088) at
     * the transition's midpoint, because both legs pass through a more-
     * extended, double-support-like configuration there that neither
     * endpoint's offset accounts for. The live per-frame version is exact
     * (zero drift) at every point in the transition, not just the two ends.
     *
     * jog_a/jog_b deliberately do NOT get this treatment (yet). The same
     * live-correction technique, verified against jog's actual angle data,
     * needs the hip to swing by up to ~0.18 normalized units (~196px) at
     * the transition's midpoint to keep the foot exactly planted — WORSE,
     * as a visual excursion, than the dead-still-hip bug it would be
     * fixing. That's a pose-authoring problem (jog_a/jog_b's linear
     * mid-blend passes through an unrealistically extended double-support-
     * like pose), not something a hip-Y-only correction can paper over —
     * it would need a real mid-stride keyframe, not just this fix applied
     * more broadly. Left alone rather than shipped un-verifiable.
     *
     * NOT device-confirmed even for walk_a/walk_b — this corrects vertical
     * foot-plant depth only; it does not address horizontal stride/travel
     * (root X is untouched, matching how this codebase currently treats
     * walk_a/walk_b as walking-in-place, not translating across the
     * canvas). Needs an actual look on the next device check.
     */
    const val WALK_STANCE_TARGET_Y_NORMALIZED: Float = 0.21725f

    /** Gate for [PlaybackEngine.currentHipBobOffset] — set comparison so
     *  walk_a->walk_b and walk_b->walk_a both match, direction-independent. */
    val WALK_POSE_PAIR: Set<String> = setOf("walk_a", "walk_b")
}

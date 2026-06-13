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
        BoneDef("head",          "torso",       0.005f, 0f,
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
        ))
    )

    /** Index of built-in poses by ID for O(1) lookups during timeline compilation. */
    val BUILT_IN_POSE_INDEX: Map<String, PoseDef> = BUILT_IN_POSES.associateBy { it.id }
}

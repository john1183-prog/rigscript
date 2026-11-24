package com.example.engine

/**
 * WP2.3 step 2: the face as continuous parameters instead of a discrete [Expression]. [FaceGeometry] turns it into
 * eyes, brows and mouth; both renderers draw that (I3). One instance is reused per frame (no allocation, I7), so it is
 * mutable: the producer (the acting layer, or [setLegacy]) fills every field, the readers only read.
 *
 * The legacy path is [setLegacy]: the numbers the old code derived from `expression` and the blink openness, so a face
 * built that way produces BIT-EQUAL geometry to the legacy functions (`FaceGeometryTest` proves it on a grid). Pure
 * Kotlin, no Android types.
 */
class ResolvedFace {
    /** Eye x-radius as a fraction of the head's drawn radius (legacy: 0.15 NORMAL/WORRIED/ANGRY, 0.20 WIDE/HAPPY, 0.11 SQUINT). */
    var eyeRadiusFrac: Float = 0.15f
    /** Eye height multiplier on top of the blink (acting: anticipation squint below 1, the take above 1; legacy: 1). */
    var eyeHeightMul: Float = 1f
    /** Blink openness, 1 = open, 0 = closed (clamped to 0..1 by the geometry). */
    var eyeOpenness: Float = 1f
    /** Gaze offset of both eyes in head radii: +x = screen right, +y = up, for an upright head (it rotates with the head). */
    var gazeX: Float = 0f
    var gazeY: Float = 0f
    /** Whether brows are drawn at all (legacy: only WORRIED and ANGRY). */
    var browsVisible: Boolean = false
    /** Brow height above the eye centre and inner-end lift, both in eye radii (legacy: 1.8 and +0.55 WORRIED / -0.55 ANGRY). */
    var browHeight: Float = 1.8f
    var browInnerLift: Float = 0f
    /** Mouth width multiplier and vertical bias as a fraction of the head radius (legacy: HAPPY 1.12/-0.06, WORRIED/ANGRY 0.90/+0.05). */
    var mouthWidthMul: Float = 1f
    var mouthCyBiasFrac: Float = 0f

    /** Fills the legacy face for a discrete [expression] and the blink [openness]. Returns this. */
    fun setLegacy(expression: Int, openness: Float): ResolvedFace {
        eyeRadiusFrac = when (expression) {
            Expression.WIDE, Expression.HAPPY -> 0.20f
            Expression.SQUINT -> 0.11f
            else -> 0.15f
        }
        eyeHeightMul = 1f
        eyeOpenness = openness
        gazeX = 0f
        gazeY = 0f
        browsVisible = expression == Expression.WORRIED || expression == Expression.ANGRY
        browHeight = 1.8f
        browInnerLift = when (expression) {
            Expression.WORRIED -> 0.55f
            Expression.ANGRY -> -0.55f
            else -> 0f
        }
        when (expression) {
            Expression.HAPPY -> { mouthWidthMul = 1.12f; mouthCyBiasFrac = -0.06f }
            Expression.WORRIED, Expression.ANGRY -> { mouthWidthMul = 0.90f; mouthCyBiasFrac = 0.05f }
            else -> { mouthWidthMul = 1f; mouthCyBiasFrac = 0f }
        }
        return this
    }

    fun copyFrom(o: ResolvedFace) {
        eyeRadiusFrac = o.eyeRadiusFrac; eyeHeightMul = o.eyeHeightMul; eyeOpenness = o.eyeOpenness
        gazeX = o.gazeX; gazeY = o.gazeY
        browsVisible = o.browsVisible; browHeight = o.browHeight; browInnerLift = o.browInnerLift
        mouthWidthMul = o.mouthWidthMul; mouthCyBiasFrac = o.mouthCyBiasFrac
    }
}

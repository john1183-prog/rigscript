package com.example.data

import kotlinx.serialization.Serializable

/**
 * Defines a single bone in the stick-figure rig.
 *
 * [normalizedLength] is expressed as a fraction of min(canvasWidth, canvasHeight),
 * allowing the figure to scale correctly across screen sizes.
 *
 * [defaultAngleDegrees] is the resting absolute angle (in the Android Y-down coordinate
 * system: 0° = right, 90° = down, -90° = up). Active pose rotations are added on top.
 */
@Serializable
data class BoneDef(
    val id: String,
    val parentId: String?,
    val normalizedLength: Float,
    val defaultAngleDegrees: Float = 0f,
    val minAngleDegrees: Float? = null,
    val maxAngleDegrees: Float? = null,
    /** When true, a filled circle is drawn at this bone's tip instead of a line cap. */
    val isHeadBone: Boolean = false,
    /** Radius of the head circle, as a fraction of min(w,h). */
    val headNormalizedRadius: Float = 0.045f
)

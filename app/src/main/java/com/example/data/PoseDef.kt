package com.example.data

import kotlinx.serialization.Serializable

/**
 * A named pose for the stick figure.
 *
 * [joints] maps bone IDs to their RELATIVE rotation in degrees – i.e. the offset
 * added to each bone's [BoneDef.defaultAngleDegrees]. Bones absent from the map
 * are assumed to have zero offset (resting position).
 *
 * [isBuiltIn] marks poses that ship with the app and cannot be deleted.
 */
@Serializable
data class PoseDef(
    val id: String,
    val name: String,
    val category: String = "custom",   // "builtin" | "custom"
    val isBuiltIn: Boolean = false,
    val joints: Map<String, Float> = emptyMap()
)

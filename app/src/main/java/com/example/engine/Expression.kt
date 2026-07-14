package com.example.engine

/**
 * Discrete facial expression states.
 *
 * Unlike [MouthShape] (audio-derived, recomputed every frame from ZCR/amplitude),
 * Expression is SCRIPT-derived — set by [com.example.data.ScriptEvent.expression]
 * and held constant (snapped, not interpolated) until the next event that
 * specifies one. This mirrors the TheOdd1sOut "puppet asset swap" model: the AI
 * script generator picks an expression per event for dramatic/emotional emphasis,
 * the engine swaps to it instantly, and it has no interaction with body-pose
 * easing/spring physics.
 *
 * Eyebrows are synthetic (the rig has no eyebrow bones) and are only drawn for
 * WORRIED and ANGRY — see [RigRenderer]. The other states are eye-shape-only.
 */
object Expression {
    const val NORMAL  = 0   // small neutral eyes, default
    const val WIDE     = 1   // surprise / shock — large round eyes, no eyebrows
    const val SQUINT   = 2   // skepticism / tiredness — narrowed eyes, no eyebrows
    const val WORRIED  = 3   // fear / concern — eyes + raised-inner eyebrows
    const val ANGRY    = 4   // anger / sternness — eyes + furrowed eyebrows
    const val HAPPY    = 5   // joy — softened wide eyes, no eyebrows

    /**
     * Resolves a friendly string from AI-generated JSON into the int constant.
     * Unrecognised or null input falls back to NORMAL rather than throwing —
     * a script with a typo'd expression should still render, just without that
     * one emphasis.
     */
    fun fromString(s: String?): Int = when (s?.trim()?.lowercase()) {
        "wide", "surprised", "surprise", "shock", "shocked" -> WIDE
        "squint", "skeptical", "suspicious", "tired"         -> SQUINT
        "worried", "afraid", "fear", "concerned", "scared"   -> WORRIED
        "angry", "stern", "mad", "furious"                   -> ANGRY
        "happy", "joy", "joyful", "smile", "smiling"         -> HAPPY
        "normal", "neutral", null                            -> NORMAL
        else                                                   -> NORMAL
    }
}

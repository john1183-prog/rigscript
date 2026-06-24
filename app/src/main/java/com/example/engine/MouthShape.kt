package com.example.engine

/**
 * Coarse mouth-shape constants, derived from the amplitude envelope by
 * [AmplitudeAnalyzer] and propagated through [AudioPlayer] → [PlaybackEngine]
 * → [RigRenderer] each frame.
 *
 * These are discrete buckets; [PlaybackEngine.currentAmplitude] carries the
 * continuous smoothed value for fine-grained mouth-openness scaling on top.
 */
object MouthShape {
    const val CLOSED = 0   // amplitude < 0.10 (silence / breath)
    const val OPEN   = 1   // 0.10 ≤ amplitude < 0.50 (normal speech)
    const val WIDE   = 2   // amplitude ≥ 0.50 (loud / emphasis)
}

package com.example

import com.example.engine.PlaybackEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

/** Shared state comparison for tests that run two engines side by side (WP0.4, WP0.5). */
object EngineAsserts {

    /**
     * Everything the renderers read from the engine must match: angles within [angleTolerance]
     * degrees, every other value exactly (both engines evaluate the same functions of time).
     */
    fun assertSameState(label: String, a: PlaybackEngine, b: PlaybackEngine, angleTolerance: Float = 1e-6f) {
        assertArrayEquals("$label: angles", a.currentAngles, b.currentAngles, angleTolerance)
        assertEquals("$label: expression", a.currentExpression, b.currentExpression)
        assertEquals(
            "$label: camera",
            listOf(a.currentCameraZoom, a.currentCameraPanX, a.currentCameraPanY, a.currentShakeIntensity),
            listOf(b.currentCameraZoom, b.currentCameraPanX, b.currentCameraPanY, b.currentShakeIntensity)
        )
        assertEquals("$label: figure", a.currentFigureOverrides, b.currentFigureOverrides)
        assertEquals(
            "$label: scene",
            listOf(a.currentSkyColor, a.currentGroundColor, a.currentHorizonY, a.currentSceneShape, a.currentSceneAtmosphere),
            listOf(b.currentSkyColor, b.currentGroundColor, b.currentHorizonY, b.currentSceneShape, b.currentSceneAtmosphere)
        )
        assertEquals("$label: caption", a.currentCaption, b.currentCaption)
        assertEquals("$label: overlays", a.currentOverlays, b.currentOverlays)
        assertEquals("$label: eyes", a.currentEyeOpenness, b.currentEyeOpenness, 0f)
    }
}

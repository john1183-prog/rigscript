package com.example.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * GLES export rewrite — Phase 1 (see V2_DECISIONS.md).
 *
 * Owns one EGL context/surface pair bound to a [Surface] (in practice, a
 * [android.media.MediaCodec] Surface-input encoder's input surface) and a
 * single dedicated thread that context stays current on for its entire
 * life.
 *
 * WHY A DEDICATED THREAD, NOT THE CALLER'S COROUTINE CONTEXT: an EGL
 * context is current on exactly one thread. [VideoExporter.export] runs
 * its whole existing pipeline inside `withContext(Dispatchers.Default)` —
 * a shared pool a coroutine can legally resume on a DIFFERENT pool thread
 * after any suspension point. That's not hypothetical here: `argbToNV12`'s
 * `awaitAll()` is exactly such a point already in this codebase. Calling
 * GLES functions from inside that structure risks a silent failure or
 * crash the moment a coroutine hops threads mid-export. Every EGL/GLES
 * call in this class is funneled through [dispatcher] instead — a
 * single-thread executor created fresh per instance — the same pattern
 * Android's own Grafika sample (CodecInputSurface/TextureMovieEncoder)
 * uses for this exact problem.
 *
 * Scope as of Phase 1: solid-color clear + swap only, to prove the EGL/
 * thread/encoder plumbing end-to-end on a real device. Not used by the
 * real [VideoExporter.export] pipeline yet — see
 * [VideoExporter.exportGlesSmokeTest], a standalone diagnostic entry
 * point. Real figure/shape/text shaders are later phases.
 *
 * NOT verified against a compiler or a device from this environment —
 * no Android SDK/Gradle access and no connected device here. First real
 * checkpoint is GitHub Actions CI (compiles); second is John running the
 * smoke test on-device (actually works).
 */
class GlesFrameRenderer(private val outputSurface: Surface) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "GlesExportThread") }

    /** Every EGL/GLES call must go through this — see class doc comment. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false

    /**
     * Performs all EGL setup on [dispatcher]. Throws on any failure — the
     * caller is responsible for catching this and treating it as "GLES
     * unavailable for this export," not retrying (see "fall back, don't
     * fail" in V2_DECISIONS.md).
     */
    suspend fun init() = withContext(dispatcher) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        // EGL_RECORDABLE_ANDROID is required for reliable use with a
        // MediaCodec input Surface across devices — the standard
        // Grafika-pattern requirement, not optional here.
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig found no matching config" }
        val eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        initialized = true
    }

    /**
     * Clears to [color] (RGB, 0f..1f) and presents the frame at
     * [presentationTimeNs] — nanoseconds, NOT the microseconds
     * [android.media.MediaCodec.BufferInfo] uses elsewhere in this
     * codebase; converting that unit correctly at the call site matters.
     * Phase 1 scope only — no geometry yet, just proving the pipeline.
     */
    suspend fun drawColorFrame(color: FloatArray, presentationTimeNs: Long) = withContext(dispatcher) {
        check(initialized) { "drawColorFrame called before init()" }
        GLES20.glClearColor(color[0], color[1], color[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
    }

    /** Tears down EGL state on [dispatcher], then shuts the thread down. Safe to call even if [init] failed partway. */
    suspend fun release() {
        withContext(dispatcher) {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            initialized = false
        }
        executor.shutdown()
    }
}

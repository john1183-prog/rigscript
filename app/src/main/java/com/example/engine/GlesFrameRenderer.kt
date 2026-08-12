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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * GLES export rewrite — Phase 2 (see V2_DECISIONS.md).
 *
 * Owns one EGL context/surface pair bound to a [Surface] (in practice, a
 * [android.media.MediaCodec] Surface-input encoder's input surface) and a
 * single dedicated thread that context stays current on for its entire life.
 *
 * THREAD MODEL (unchanged from Phase 1 — the EGL thread-affinity risk still
 * applies equally here): an EGL context is current on exactly one thread.
 * [VideoExporter.export] runs inside `withContext(Dispatchers.Default)` — a
 * SHARED pool where a coroutine can legally resume on a different thread
 * after any suspension point. Every EGL/GLES call in this class is funnelled
 * through [dispatcher] — a single-thread executor, one per instance —
 * following the Grafika CodecInputSurface/TextureMovieEncoder pattern.
 *
 * COORDINATE SYSTEM: OpenGL clip space is [-1,+1] both axes, Y-up. Canvas
 * pixel coordinates are Y-down, origin top-left. Every position in
 * [GlesFigureFrame] is in Canvas pixels; [toClipX]/[toClipY] convert them.
 *
 * PHASE 2 SCOPE: background clear, round-capped bone lines, filled circles
 * (head, joints) — via signed-distance-field shaders, anti-aliased to a
 * constant ~1 SCREEN pixel regardless of a segment's/circle's own size (see
 * [drawRoundCappedLine]/[drawCircle] doc comments — an earlier draft used a
 * fixed epsilon in normalised space instead, which scaled the AA band with
 * segment length and would have looked inconsistent across e.g. the torso
 * vs. a finger-scale joint; caught and fixed on review, not on a device).
 * Text / captions / overlays / atmosphere / scene shapes are later phases.
 */
class GlesFrameRenderer(private val outputSurface: Surface) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "GlesExportThread") }

    /** Every EGL/GLES call must go through this — see class doc comment. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext  = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface  = EGL14.EGL_NO_SURFACE
    private var initialized = false

    /**
     * Captured inside [init] (which runs on [dispatcher]), so any call to
     * [drawFigureFrame] can verify it's actually running on the thread that
     * holds the EGL context current — see that function's doc comment for
     * why this exists: a first draft got this wrong at the one real call
     * site, and the resulting failure (`eglSwapBuffers failed`, no further
     * detail) took real reasoning to trace back to a thread-affinity bug
     * rather than something wrong with the swap itself. This assertion
     * turns that same mistake, if it recurs, into an immediate and
     * unambiguous error instead.
     */
    private var ownerThread: Thread? = null

    private var lineCapProgram = 0
    private var circleProgram  = 0
    private var vertexBuf: FloatBuffer = allocFloatBuffer(24)

    private companion object {

        // Round-capped line via a signed-distance field against the segment [0,1] on u.
        // a_uv: u = position along axis normalised by segment length (0=start, 1=end,
        // extended past both ends by the cap radius); v = signed lateral offset, ALSO
        // normalised by segment length — see drawRoundCappedLine's doc comment for why
        // both must share that same normalisation for the distance math to be valid.
        const val LINE_VERT = """
            attribute vec2 a_pos;
            attribute vec2 a_uv;
            varying   vec2 v_uv;
            void main() { gl_Position = vec4(a_pos, 0.0, 1.0); v_uv = a_uv; }
        """

        const val LINE_FRAG = """
            precision mediump float;
            uniform vec4  u_color;
            uniform float u_halfW;     // half-width in uv space (halfWidthPixels / segLenPixels)
            uniform float u_aaWidth;   // AA transition half-band, same uv-normalised units
            varying vec2  v_uv;
            void main() {
                float cu = clamp(v_uv.x, 0.0, 1.0);
                float du = v_uv.x - cu;
                float dv = v_uv.y;
                float dist = sqrt(du*du + dv*dv);
                float alpha = 1.0 - smoothstep(u_halfW - u_aaWidth, u_halfW + u_aaWidth, dist);
                gl_FragColor = vec4(u_color.rgb, u_color.a * alpha);
            }
        """

        // Circle SDF. a_uv is normalised so length(uv) == 1.0 exactly at the circle's
        // true radius, regardless of how much extra quad padding surrounds it for the
        // AA falloff to render into — see drawCircle's doc comment; a first draft
        // conflated "quad padding" with "uv scale" and rendered every circle ~2% too
        // large as a result. Caught and fixed on review, not on a device.
        const val CIRCLE_VERT = """
            attribute vec2 a_pos;
            attribute vec2 a_uv;
            varying   vec2 v_uv;
            void main() { gl_Position = vec4(a_pos, 0.0, 1.0); v_uv = a_uv; }
        """

        const val CIRCLE_FRAG = """
            precision mediump float;
            uniform vec4  u_color;
            uniform float u_aaWidth;   // AA transition half-band, in units of the circle's own radius
            varying vec2  v_uv;
            void main() {
                float dist  = length(v_uv);
                float alpha = 1.0 - smoothstep(1.0 - u_aaWidth, 1.0 + u_aaWidth, dist);
                gl_FragColor = vec4(u_color.rgb, u_color.a * alpha);
            }
        """

        fun allocFloatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
    }

    // ── EGL init / release ────────────────────────────────────────────────────

    /**
     * Performs all EGL setup on [dispatcher]. Throws on any failure — the
     * caller is responsible for treating this as "GLES unavailable for this
     * export" and falling back to the software path.
     */
    suspend fun init() = withContext(dispatcher) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs    = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig found no matching config" }

        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0]!!, outputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed, eglGetError=0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed, eglGetError=0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        lineCapProgram = buildProgram(LINE_VERT, LINE_FRAG)
        circleProgram  = buildProgram(CIRCLE_VERT, CIRCLE_FRAG)

        ownerThread = Thread.currentThread()   // this block runs on dispatcher — see ownerThread's doc comment
        initialized = true
    }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vert); GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        GLES20.glDeleteShader(vert); GLES20.glDeleteShader(frag)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src.trimIndent())
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed (type=$type): ${GLES20.glGetShaderInfoLog(sh)}" }
        return sh
    }

    /** Tears down EGL state on [dispatcher], then shuts the thread down. Safe even if [init] failed partway. */
    suspend fun release() {
        withContext(dispatcher) {
            if (lineCapProgram != 0) { GLES20.glDeleteProgram(lineCapProgram); lineCapProgram = 0 }
            if (circleProgram  != 0) { GLES20.glDeleteProgram(circleProgram);  circleProgram  = 0 }
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
            ownerThread = null
        }
        executor.shutdown()
    }

    // ── Phase 1 diagnostic (kept for the smoke-test button) ────────────────────
    /** Clears to [color] (RGB floats 0..1) and swaps. Superseded by [drawFigureFrame] for real content. */
    suspend fun drawColorFrame(color: FloatArray, presentationTimeNs: Long) = withContext(dispatcher) {
        check(initialized) { "drawColorFrame called before init()" }
        GLES20.glClearColor(color[0], color[1], color[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            "eglSwapBuffers failed, eglGetError=0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    // ── Phase 2: real figure rendering ────────────────────────────────────────

    /**
     * Renders one [GlesFigureFrame] and presents it at [presentationTimeNs]
     * (nanoseconds). Must be called on [dispatcher] (i.e. inside
     * `withContext(dispatcher)`) — deliberately NOT a suspend fun itself, so a
     * caller driving many frames can do so inside one `withContext(dispatcher)`
     * block rather than paying a coroutine dispatch per frame.
     *
     * Replays [GlesFigureFrame.drawCommands] IN ORDER — that order encodes the
     * Canvas path's actual z-order (head drawn before later limbs can occlude
     * it), not just "all bones then all joints then head". See that class's
     * doc comment.
     *
     * The [ownerThread] check below exists because a first draft's ONE call
     * site (`VideoExporter.exportGlesSmokeTest`) got this wrong: it called
     * this directly from a `Dispatchers.Default` coroutine, not from inside
     * `withContext(dispatcher)`. Every void-returning GL call in this
     * function silently no-oped on the wrong thread; only `eglSwapBuffers`
     * has a checked return value, so that's the only place it visibly threw
     * — as a bare "eglSwapBuffers failed" with no indication the real
     * problem was upstream of the swap entirely. This check turns that same
     * mistake, if it recurs, into an immediate, specific error instead.
     */
    fun drawFigureFrame(frame: GlesFigureFrame, presentationTimeNs: Long) {
        check(initialized) { "drawFigureFrame called before init()" }
        check(Thread.currentThread() === ownerThread) {
            "drawFigureFrame called from ${Thread.currentThread().name}, but the EGL context " +
                "is current on ${ownerThread?.name} — wrap the caller in withContext(dispatcher)"
        }

        val w = frame.canvasW.toFloat()
        val h = frame.canvasH.toFloat()
        GLES20.glViewport(0, 0, frame.canvasW, frame.canvasH)

        val bg = argbToGlColor(frame.bgColor)
        GLES20.glClearColor(bg[0], bg[1], bg[2], bg[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (frame.figureAlpha > 0.001f) {
            for (cmd in frame.drawCommands) {
                when (cmd) {
                    is GlesFigureFrame.DrawCommand.BoneLine -> {
                        val c = argbToGlColor(cmd.color)
                        drawRoundCappedLine(
                            cmd.sx, cmd.sy, cmd.ex, cmd.ey, cmd.halfWidth, w, h,
                            c[0], c[1], c[2], c[3] * frame.figureAlpha
                        )
                    }
                    is GlesFigureFrame.DrawCommand.Joint -> {
                        val c = argbToGlColor(cmd.color)
                        drawCircle(cmd.cx, cmd.cy, cmd.radius, w, h, c[0], c[1], c[2], c[3] * frame.figureAlpha)
                    }
                    is GlesFigureFrame.DrawCommand.Head -> {
                        val c = argbToGlColor(cmd.color)
                        drawCircle(cmd.cx, cmd.cy, cmd.radius, w, h, c[0], c[1], c[2], c[3] * frame.figureAlpha)
                    }
                }
            }
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            "eglSwapBuffers failed, eglGetError=0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    // ── Drawing primitives ────────────────────────────────────────────────────

    /**
     * Draws one round-capped line segment from ([sx],[sy]) to ([ex],[ey]) in
     * canvas pixel space.
     *
     * TECHNIQUE: a quad extended by [halfW] past each endpoint (covering the
     * round caps), with an SDF in the fragment shader. Both the "u" (along-axis)
     * and "v" (lateral) UV coordinates are normalised by the SAME factor
     * (segment length) — that's what makes `sqrt(du*du+dv*dv)` in the shader a
     * valid Euclidean distance measure comparable against `u_halfW`
     * (`halfW/len`): a point's true pixel distance from the segment equals its
     * UV-space distance times `len`, so comparing the UV-space distance against
     * `halfW/len` is exactly equivalent to comparing the true pixel distance
     * against `halfW`. Mixing a differently-normalised u and v would silently
     * break this.
     *
     * AA band: [1/len, clamped] so the transition is always ~1 SCREEN pixel
     * wide regardless of the segment's own length, rather than a fixed
     * fraction of it (see class doc comment for why that matters — a torso
     * and a small joint gap would otherwise get very different-looking edges).
     */
    private fun drawRoundCappedLine(
        sx: Float, sy: Float, ex: Float, ey: Float,
        halfW: Float,
        canvasW: Float, canvasH: Float,
        red: Float, green: Float, blue: Float, alpha: Float
    ) {
        val dx = ex - sx; val dy = ey - sy
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 0.5f || halfW < 0.1f) return   // degenerate — skip rather than NaN/zero-divide

        val ux = dx / len; val uy = dy / len
        val nx = -uy;      val ny =  ux
        val halfWN = halfW / len
        // Clamp so the AA band can never exceed 90% of the shape's own half-width —
        // otherwise a very short/thin segment's centre could end up inside its own
        // transition band and render partially transparent instead of solid.
        val aaWidthN = (1f / len).coerceAtMost(halfWN * 0.9f)

        val q0x = sx - ux * halfW - nx * halfW; val q0y = sy - uy * halfW - ny * halfW
        val q1x = ex + ux * halfW - nx * halfW; val q1y = ey + uy * halfW - ny * halfW
        val q2x = sx - ux * halfW + nx * halfW; val q2y = sy - uy * halfW + ny * halfW
        val q3x = ex + ux * halfW + nx * halfW; val q3y = ey + uy * halfW + ny * halfW

        val data = floatArrayOf(
            toClipX(q0x, canvasW), toClipY(q0y, canvasH), -halfWN,     -halfWN,
            toClipX(q1x, canvasW), toClipY(q1y, canvasH),  1f+halfWN,  -halfWN,
            toClipX(q2x, canvasW), toClipY(q2y, canvasH), -halfWN,      halfWN,
            toClipX(q1x, canvasW), toClipY(q1y, canvasH),  1f+halfWN,  -halfWN,
            toClipX(q3x, canvasW), toClipY(q3y, canvasH),  1f+halfWN,   halfWN,
            toClipX(q2x, canvasW), toClipY(q2y, canvasH), -halfWN,      halfWN
        )

        GLES20.glUseProgram(lineCapProgram)
        val aPos   = GLES20.glGetAttribLocation(lineCapProgram, "a_pos")
        val aUv    = GLES20.glGetAttribLocation(lineCapProgram, "a_uv")
        val uColor = GLES20.glGetUniformLocation(lineCapProgram, "u_color")
        val uHalfW = GLES20.glGetUniformLocation(lineCapProgram, "u_halfW")
        val uAaW   = GLES20.glGetUniformLocation(lineCapProgram, "u_aaWidth")
        GLES20.glUniform4f(uColor, red, green, blue, alpha)
        GLES20.glUniform1f(uHalfW, halfWN)
        GLES20.glUniform1f(uAaW, aaWidthN)

        drawQuad(data, aPos, aUv)
    }

    /**
     * Draws a filled circle via a quad + circle SDF. [pad] extends the quad
     * beyond the true radius [r] so the AA falloff has room to render without
     * being clipped by the quad's own edge — the UV coordinates are scaled by
     * `pad/r` (not left at the quad's own ±1 extent) specifically so
     * `length(uv) == 1.0` still means "exactly at radius r" regardless of how
     * much padding was added. See class doc comment for the bug this fixes.
     */
    private fun drawCircle(
        cx: Float, cy: Float, r: Float,
        canvasW: Float, canvasH: Float,
        red: Float, green: Float, blue: Float, alpha: Float
    ) {
        if (r < 0.5f) return

        val aaWidthN = (1f / r).coerceAtMost(0.5f)
        val uvExtent = 1f + aaWidthN * 2f   // quad reaches slightly past the AA band, in radius-normalised units
        val pad = r * uvExtent

        GLES20.glUseProgram(circleProgram)
        val aPos   = GLES20.glGetAttribLocation(circleProgram, "a_pos")
        val aUv    = GLES20.glGetAttribLocation(circleProgram, "a_uv")
        val uColor = GLES20.glGetUniformLocation(circleProgram, "u_color")
        val uAaW   = GLES20.glGetUniformLocation(circleProgram, "u_aaWidth")
        GLES20.glUniform4f(uColor, red, green, blue, alpha)
        GLES20.glUniform1f(uAaW, aaWidthN)

        val l = cx - pad; val r2 = cx + pad
        val t = cy - pad; val b2 = cy + pad

        val data = floatArrayOf(
            toClipX(l,  canvasW), toClipY(t,  canvasH), -uvExtent, -uvExtent,
            toClipX(r2, canvasW), toClipY(t,  canvasH),  uvExtent, -uvExtent,
            toClipX(l,  canvasW), toClipY(b2, canvasH), -uvExtent,  uvExtent,
            toClipX(r2, canvasW), toClipY(t,  canvasH),  uvExtent, -uvExtent,
            toClipX(r2, canvasW), toClipY(b2, canvasH),  uvExtent,  uvExtent,
            toClipX(l,  canvasW), toClipY(b2, canvasH), -uvExtent,  uvExtent
        )

        drawQuad(data, aPos, aUv)
    }

    /** Shared 6-vertex (2-triangle) interleaved [x,y,u,v] draw, used by both primitives above. */
    private fun drawQuad(data: FloatArray, aPosLoc: Int, aUvLoc: Int) {
        val buf = getVertexBuffer(data.size)
        buf.put(data).position(0)

        val stride = 4 * 4   // 4 floats * 4 bytes/float
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, stride, buf)

        val uvBuf = buf.duplicate().also { it.position(2) }
        GLES20.glEnableVertexAttribArray(aUvLoc)
        GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, stride, uvBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aUvLoc)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Canvas pixel X → GL clip X ([-1,+1], same direction). */
    private fun toClipX(px: Float, canvasW: Float) = (px / canvasW) * 2f - 1f

    /** Canvas pixel Y → GL clip Y ([-1,+1], FLIPPED — Canvas is Y-down, GL is Y-up). */
    private fun toClipY(py: Float, canvasH: Float) = 1f - (py / canvasH) * 2f

    /** Unpacks an ARGB packed int into [R,G,B,A] GL floats 0..1. */
    private fun argbToGlColor(argb: Int): FloatArray {
        val a = ((argb shr 24) and 0xFF) / 255f
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr  8) and 0xFF) / 255f
        val b = ( argb         and 0xFF) / 255f
        return floatArrayOf(r, g, b, a)
    }

    /**
     * Returns [vertexBuf], growing it first if [floats] exceeds its current
     * capacity. Safe to reuse synchronously across sequential draw calls —
     * client-array `glDrawArrays` (no bound `GL_ARRAY_BUFFER`, which is the
     * case throughout this class) is specified to read the data synchronously
     * within the call, so overwriting this buffer for the next shape after a
     * draw call returns cannot corrupt the previous one.
     */
    private fun getVertexBuffer(floats: Int): FloatBuffer {
        if (vertexBuf.capacity() < floats) vertexBuf = allocFloatBuffer(floats * 2)
        vertexBuf.clear()
        return vertexBuf
    }
}

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
 * GLES export rewrite (see V2_DECISIONS.md).
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
 * PHASE 3 (V2_DECISIONS.md) added mouth/eyes/eyebrows: ovals via a
 * gradient-corrected ellipse SDF (see [drawOval]/[OVAL_FRAG] doc comments —
 * this needed real per-fragment correction, not just the circle shader's
 * technique, because the mouth/eye shapes are genuinely eccentric, not
 * near-circular), eyebrows reusing the existing line primitive.
 * PHASE 4 added background/scene shapes/atmosphere: sky/ground bands and
 * the background gradient, mountains/city/trees/clouds/stars, ground line,
 * and fog/rain/snow — via [SOLID_FRAG] (see its own doc comment for why
 * that's a plain interpolated fill, not an SDF, unlike everything above)
 * plus the existing circle/line primitives reused as-is for trees/clouds/
 * stars/snow/rain/ground-line. Text / captions / overlays remain later
 * phases.
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
    private var ovalProgram    = 0
    private var solidProgram   = 0
    private var blurProgram    = 0
    private var vertexBuf: FloatBuffer = allocFloatBuffer(24)

    /**
     * Offscreen ping-pong pair for overlay-shape glow's two-pass Gaussian
     * blur — see [drawGlowShape]'s doc comment. Allocated lazily on first
     * use (canvas dimensions aren't known at [init] time — [drawFigureFrame]
     * receives them per-call), sized to exactly [glowFboW]x[glowFboH] and
     * reallocated only if that size ever changes.
     */
    private var glowFboA = 0
    private var glowTexA = 0
    private var glowFboB = 0
    private var glowTexB = 0
    private var glowFboW = 0
    private var glowFboH = 0

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

        // Ellipse SDF, Phase 3 (mouth/eyes — V2_DECISIONS.md). UNLIKE the circle
        // shader above, this can't reuse a single UV-scale trick: an ellipse's
        // boundary curvature varies by angle whenever rx != ry, and the mouth's
        // CLOSED shape and a blinking eye both get genuinely eccentric (~8:1+).
        // Naively scaling UV by 1/rx,1/ry and smoothstepping length(uv) — the
        // direct ellipse analogue of the circle shader's technique — would give
        // an AA band whose SCREEN-pixel width varies around the perimeter,
        // exactly the class of bug this file's history already caught twice for
        // the line and circle shaders (see class doc comment). Instead this
        // evaluates the implicit ellipse function f(x,y)=(x/rx)²+(y/ry)²-1 and
        // divides by its gradient magnitude per-fragment — a standard
        // first-order approximation to true Euclidean distance from an implicit
        // function — so the AA band stays ~constant in screen pixels at any
        // eccentricity, not just near-circular ovals. a_local is a RAW PIXEL
        // offset from the oval's center (not length-normalised like the line/
        // circle shaders' UVs), since the gradient math needs real rx/ry in
        // pixel units to be well-defined.
        const val OVAL_VERT = """
            attribute vec2 a_pos;
            attribute vec2 a_local;
            varying   vec2 v_local;
            void main() { gl_Position = vec4(a_pos, 0.0, 1.0); v_local = a_local; }
        """

        const val OVAL_FRAG = """
            precision mediump float;
            uniform vec4  u_color;
            uniform float u_rx;        // half-width, pixels
            uniform float u_ry;        // half-height, pixels
            uniform float u_aaHalfPx;  // desired AA half-band width, pixels (~1.0)
            varying vec2  v_local;
            void main() {
                float ux = v_local.x / u_rx;
                float uy = v_local.y / u_ry;
                float f  = ux * ux + uy * uy - 1.0;
                float gx = 2.0 * v_local.x / (u_rx * u_rx);
                float gy = 2.0 * v_local.y / (u_ry * u_ry);
                float gradLen = sqrt(gx * gx + gy * gy);
                float dist  = f / max(gradLen, 0.0001);
                float alpha = 1.0 - smoothstep(-u_aaHalfPx, u_aaHalfPx, dist);
                gl_FragColor = vec4(u_color.rgb, u_color.a * alpha);
            }
        """

        fun allocFloatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        // Flat/interpolated-color fill, Phase 4 (background/scene — V2_DECISIONS.md).
        // Deliberately NOT an SDF like the three shaders above: those exist to hide
        // a shape's edge inside a ~1px AA transition because the shape's OWN edge is
        // the visible boundary a person looks at (a bone's silhouette, a joint,
        // mouth/eye). Background bands, gradients, mountains, and buildings are the
        // opposite case — large fills where a hard raster edge is not a visible
        // defect (a mountain ridge doesn't need antialiasing softness to look right
        // the way a joint circle does) — so a plain per-vertex color, linearly
        // interpolated by the GPU, is correct and cheaper. Per-vertex (not a single
        // uniform) color is what makes this one shader cover both a flat fill (same
        // color at every vertex) and the background gradient (different top/bottom
        // vertex colors) for free.
        const val SOLID_VERT = """
            attribute vec2 a_pos;
            attribute vec4 a_color;
            varying   vec4 v_color;
            void main() { gl_Position = vec4(a_pos, 0.0, 1.0); v_color = a_color; }
        """

        const val SOLID_FRAG = """
            precision mediump float;
            varying vec4 v_color;
            void main() { gl_FragColor = v_color; }
        """

        // Separable Gaussian blur, overlay shape glow (V2_DECISIONS.md). Samples
        // [u_tex] with a fixed 9-tap normalised kernel along ONE axis at a time
        // (u_texelStep is (step,0) for the horizontal pass, (0,step) for the
        // vertical pass — see drawGlowShape) — running this shader twice, once
        // per axis, IS a full 2D Gaussian blur: a 2D Gaussian is separable into
        // the product of two 1D Gaussians, which is the whole reason two cheap
        // 1D passes are used instead of one expensive 2D one. Weights are the
        // standard normalised 9-tap set (sums to 1.0), NOT a continuously
        // variable true-sigma kernel — u_texelStep's magnitude is instead scaled
        // on the Kotlin side to approximate the requested glowRadius, same
        // "fixed-shape, scaled approximation" spirit as this file's other
        // documented approximations (see OVAL_FRAG's doc comment for the
        // precedent). CRITICAL for a correct-looking result at partial alpha:
        // [u_tex] must hold PREMULTIPLIED-alpha color — see drawGlowShape's doc
        // comment for exactly why and how that's arranged without needing to
        // touch the SDF shaders that render INTO the source texture.
        const val BLUR_VERT = """
            attribute vec2 a_pos;
            attribute vec2 a_uv;
            varying   vec2 v_uv;
            void main() { gl_Position = vec4(a_pos, 0.0, 1.0); v_uv = a_uv; }
        """

        const val BLUR_FRAG = """
            precision mediump float;
            uniform sampler2D u_tex;
            uniform vec2 u_texelStep;
            varying vec2 v_uv;
            void main() {
                vec4 sum = vec4(0.0);
                sum += texture2D(u_tex, v_uv - 4.0 * u_texelStep) * 0.0162162162;
                sum += texture2D(u_tex, v_uv - 3.0 * u_texelStep) * 0.0540540541;
                sum += texture2D(u_tex, v_uv - 2.0 * u_texelStep) * 0.1216216216;
                sum += texture2D(u_tex, v_uv - 1.0 * u_texelStep) * 0.1945945946;
                sum += texture2D(u_tex, v_uv)                     * 0.2270270270;
                sum += texture2D(u_tex, v_uv + 1.0 * u_texelStep) * 0.1945945946;
                sum += texture2D(u_tex, v_uv + 2.0 * u_texelStep) * 0.1216216216;
                sum += texture2D(u_tex, v_uv + 3.0 * u_texelStep) * 0.0540540541;
                sum += texture2D(u_tex, v_uv + 4.0 * u_texelStep) * 0.0162162162;
                gl_FragColor = sum;
            }
        """
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
        ovalProgram    = buildProgram(OVAL_VERT, OVAL_FRAG)
        solidProgram   = buildProgram(SOLID_VERT, SOLID_FRAG)
        blurProgram    = buildProgram(BLUR_VERT, BLUR_FRAG)

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
            if (ovalProgram    != 0) { GLES20.glDeleteProgram(ovalProgram);    ovalProgram    = 0 }
            if (solidProgram   != 0) { GLES20.glDeleteProgram(solidProgram);   solidProgram   = 0 }
            if (blurProgram    != 0) { GLES20.glDeleteProgram(blurProgram);    blurProgram    = 0 }
            if (glowFboA != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(glowFboA), 0); glowFboA = 0 }
            if (glowFboB != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(glowFboB), 0); glowFboB = 0 }
            if (glowTexA != 0) { GLES20.glDeleteTextures(1, intArrayOf(glowTexA), 0); glowTexA = 0 }
            if (glowTexB != 0) { GLES20.glDeleteTextures(1, intArrayOf(glowTexB), 0); glowTexB = 0 }
            glowFboW = 0
            glowFboH = 0
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

    // ── Frame rendering (figure + background/scene/atmosphere) ─────────────────

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

        // Background bands / gradient — plain solid is already fully handled
        // by the glClear above, so this only draws SOMETHING EXTRA on top
        // for the two other cases, mirroring RigRenderer.draw's own
        // priority order (sky/ground bands > gradient > solid) exactly.
        // UNLIKE Canvas's oversized (3x canvas) background rects, these are
        // drawn exactly canvas-sized — correct for now because camera zoom/
        // pan/shake isn't applied anywhere in the GLES path yet (a
        // pre-existing gap from Phase 1, not something Phase 4 introduces —
        // see V2_DECISIONS.md). Revisit sizing here whenever that gap is closed.
        if (frame.skyColor != null || frame.groundColor != null) {
            val sky    = frame.skyColor ?: frame.bgColor
            val ground = frame.groundColor ?: frame.bgColor
            val hz     = frame.canvasH * frame.horizonYFraction
            drawSolidRect(0f, 0f, w, hz, sky, w, h)
            drawSolidRect(0f, hz, w, h, ground, w, h)
        } else if (frame.backgroundStyle == "gradient") {
            drawSolidGradientRect(0f, 0f, w, h, frame.bgColor, frame.backgroundGradientColor, w, h)
        }

        // Scene shapes + stars — world-space, before the figure. See
        // GlesFigureFrame.SceneDrawCommand's doc comment for why this is a
        // separate list from drawCommands rather than folded into it.
        for (cmd in frame.sceneCommands) {
            when (cmd) {
                is GlesFigureFrame.SceneDrawCommand.Polygon -> {
                    val colors = IntArray(cmd.points.size / 2) { cmd.color }
                    drawSolidFan(cmd.points, colors, w, h)
                }
                is GlesFigureFrame.SceneDrawCommand.Rect -> {
                    drawSolidRect(cmd.l, cmd.t, cmd.r, cmd.b, cmd.color, w, h)
                }
                is GlesFigureFrame.SceneDrawCommand.Circle -> {
                    val c = argbToGlColor(cmd.color)
                    drawCircle(cmd.cx, cmd.cy, cmd.radius, w, h, c[0], c[1], c[2], c[3])
                }
            }
        }

        if (frame.showGroundLine) {
            val groundY = frame.canvasH * frame.groundLineYFraction
            val c = argbToGlColor(frame.groundLineColor)
            drawRoundCappedLine(0f, groundY, w, groundY, 1f, w, h, c[0], c[1], c[2], c[3])
        }

        // Behind-the-figure overlay shapes — see GlesFigureFrame.OverlayShapeDraw
        // doc comment. Glow (if any) drawn first via the offscreen blur passes,
        // THEN the crisp shape on top of it — matching RigRenderer.drawGmsShape's
        // own glow-then-crisp order exactly.
        for (overlay in frame.behindOverlays) {
            if (overlay.glow) drawGlowShape(overlay, frame.canvasW, frame.canvasH, w, h)
            drawOverlayCommands(overlay.commands, w, h, colorOverride = null)
        }

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
                    is GlesFigureFrame.DrawCommand.Oval -> {
                        val c = argbToGlColor(cmd.color)
                        drawOval(
                            cmd.cx, cmd.cy, cmd.halfWidth, cmd.halfHeight, w, h,
                            c[0], c[1], c[2], c[3] * frame.figureAlpha
                        )
                    }
                    is GlesFigureFrame.DrawCommand.Eyebrow -> {
                        // Geometrically identical to BoneLine — see that
                        // DrawCommand's doc comment for why it's still a
                        // distinct type. Reuses drawRoundCappedLine rather
                        // than a separate primitive.
                        val c = argbToGlColor(cmd.color)
                        drawRoundCappedLine(
                            cmd.sx, cmd.sy, cmd.ex, cmd.ey, cmd.halfWidth, w, h,
                            c[0], c[1], c[2], c[3] * frame.figureAlpha
                        )
                    }
                }
            }
        }

        // Front-of-figure overlay shapes — same glow-then-crisp order as behindOverlays.
        for (overlay in frame.frontOverlays) {
            if (overlay.glow) drawGlowShape(overlay, frame.canvasW, frame.canvasH, w, h)
            drawOverlayCommands(overlay.commands, w, h, colorOverride = null)
        }

        // Atmosphere (fog/rain/snow) — screen-space, drawn AFTER the figure
        // (still before the swap below), matching RigRenderer.drawAtmosphere's
        // own "after canvas.restore()" placement. Stars are NOT here — they're
        // world-space and already emitted into sceneCommands above, matching
        // Canvas's own before-figure placement for stars specifically.
        for (cmd in frame.atmosphereCommands) {
            when (cmd) {
                is GlesFigureFrame.AtmosphereDrawCommand.FullscreenTint -> {
                    drawSolidRect(0f, 0f, w, h, cmd.color, w, h)
                }
                is GlesFigureFrame.AtmosphereDrawCommand.Line -> {
                    val c = argbToGlColor(cmd.color)
                    drawRoundCappedLine(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.halfWidth, w, h, c[0], c[1], c[2], c[3])
                }
                is GlesFigureFrame.AtmosphereDrawCommand.Circle -> {
                    val c = argbToGlColor(cmd.color)
                    drawCircle(cmd.cx, cmd.cy, cmd.radius, w, h, c[0], c[1], c[2], c[3])
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

    /**
     * Draws a filled oval via a quad + gradient-corrected ellipse SDF — see
     * [OVAL_FRAG]'s doc comment for why this needs its own per-fragment
     * gradient correction rather than [drawCircle]'s single-uniform-scalar
     * approach: an ellipse's AA band width isn't constant around its
     * perimeter under a naive UV-scale technique once [rx] and [ry] differ
     * meaningfully, and the mouth/eye shapes this is built for do differ
     * meaningfully (a closed mouth and a blinking eye are both ~8:1+
     * eccentric). [marginPx] is a small constant, not proportional to
     * [rx]/[ry] like [drawCircle]'s `pad` — this shader computes true pixel
     * distance directly, so it doesn't need shape-size-proportional quad
     * slack the way the UV-normalised circle shader does.
     */
    private fun drawOval(
        cx: Float, cy: Float, rx: Float, ry: Float,
        canvasW: Float, canvasH: Float,
        red: Float, green: Float, blue: Float, alpha: Float
    ) {
        if (rx < 0.5f || ry < 0.5f) return

        val aaHalfPx = 1f
        val marginPx = aaHalfPx * 3f   // quad slack for the AA band to render into, unclipped
        val padX = rx + marginPx
        val padY = ry + marginPx

        GLES20.glUseProgram(ovalProgram)
        val aPos    = GLES20.glGetAttribLocation(ovalProgram, "a_pos")
        val aLocal  = GLES20.glGetAttribLocation(ovalProgram, "a_local")
        val uColor  = GLES20.glGetUniformLocation(ovalProgram, "u_color")
        val uRx     = GLES20.glGetUniformLocation(ovalProgram, "u_rx")
        val uRy     = GLES20.glGetUniformLocation(ovalProgram, "u_ry")
        val uAaHalf = GLES20.glGetUniformLocation(ovalProgram, "u_aaHalfPx")
        GLES20.glUniform4f(uColor, red, green, blue, alpha)
        GLES20.glUniform1f(uRx, rx)
        GLES20.glUniform1f(uRy, ry)
        GLES20.glUniform1f(uAaHalf, aaHalfPx)

        val l = cx - padX; val r2 = cx + padX
        val t = cy - padY; val b2 = cy + padY

        // a_local is the RAW PIXEL offset from (cx,cy) — see OVAL_FRAG's doc
        // comment for why, unlike the line/circle shaders' length-normalised UVs.
        val data = floatArrayOf(
            toClipX(l,  canvasW), toClipY(t,  canvasH), -padX, -padY,
            toClipX(r2, canvasW), toClipY(t,  canvasH),  padX, -padY,
            toClipX(l,  canvasW), toClipY(b2, canvasH), -padX,  padY,
            toClipX(r2, canvasW), toClipY(t,  canvasH),  padX, -padY,
            toClipX(r2, canvasW), toClipY(b2, canvasH),  padX,  padY,
            toClipX(l,  canvasW), toClipY(b2, canvasH), -padX,  padY
        )

        drawQuad(data, aPos, aLocal)
    }

    /**
     * Draws a filled polygon as a GL_TRIANGLE_FAN from [xy]'s first vertex,
     * with an independent per-vertex ARGB color in [colorsArgb] — see
     * [SOLID_FRAG]'s doc comment for why this is a plain interpolated fill
     * rather than an SDF like the three primitives above. [xy] is a flat
     * (x0,y0,x1,y1,...) canvas-pixel array; `colorsArgb.size` must equal
     * `xy.size / 2`. Reused for both a flat fill (same color repeated — see
     * [drawSolidRect]) and a gradient (different colors — see
     * [drawSolidGradientRect]) rather than having two shaders for what is,
     * to the GPU, the same draw call with different vertex data.
     */
    private fun drawSolidFan(xy: FloatArray, colorsArgb: IntArray, canvasW: Float, canvasH: Float) {
        val n = xy.size / 2
        if (n < 3) return

        GLES20.glUseProgram(solidProgram)
        val aPos   = GLES20.glGetAttribLocation(solidProgram, "a_pos")
        val aColor = GLES20.glGetAttribLocation(solidProgram, "a_color")

        val stride = 6   // x, y, r, g, b, a per vertex
        val buf = getVertexBuffer(n * stride)
        for (i in 0 until n) {
            val c = argbToGlColor(colorsArgb[i])
            buf.put(toClipX(xy[i * 2], canvasW))
            buf.put(toClipY(xy[i * 2 + 1], canvasH))
            buf.put(c[0]); buf.put(c[1]); buf.put(c[2]); buf.put(c[3])
        }
        buf.position(0)

        val strideBytes = stride * 4
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, strideBytes, buf)

        val colorBuf = buf.duplicate().also { it.position(2) }
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, strideBytes, colorBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, n)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    /** Flat-filled axis-aligned rect via [drawSolidFan] with the same ARGB color at all 4 corners. */
    private fun drawSolidRect(l: Float, t: Float, r: Float, b: Float, colorArgb: Int, canvasW: Float, canvasH: Float) {
        drawSolidFan(floatArrayOf(l, t, r, t, r, b, l, b), intArrayOf(colorArgb, colorArgb, colorArgb, colorArgb), canvasW, canvasH)
    }

    /** Top-to-bottom linear gradient rect via [drawSolidFan] — [topColorArgb] at the top edge, [bottomColorArgb] at the bottom, GPU-interpolated in between. */
    private fun drawSolidGradientRect(l: Float, t: Float, r: Float, b: Float, topColorArgb: Int, bottomColorArgb: Int, canvasW: Float, canvasH: Float) {
        drawSolidFan(
            floatArrayOf(l, t, r, t, r, b, l, b),
            intArrayOf(topColorArgb, topColorArgb, bottomColorArgb, bottomColorArgb),
            canvasW, canvasH
        )
    }

    /** Shared 6-vertex (2-triangle) interleaved [x,y,u,v] draw, used by all three primitives above. */
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

    // ── Overlay shapes + glow ────────────────────────────────────────────────

    /**
     * Draws a resolved overlay shape's already-world-space geometry via the
     * existing primitives — [drawSolidFan] for [GlesFigureFrame.OverlayDrawCommand.Polygon],
     * [drawCircle] for [Circle][GlesFigureFrame.OverlayDrawCommand.Circle],
     * [drawRoundCappedLine] for [Line][GlesFigureFrame.OverlayDrawCommand.Line].
     * No new shader needed for the crisp draw — only glow needed new
     * infrastructure (FBOs + blur). [colorOverride], when non-null, replaces
     * every command's own baked-in color — used ONLY for the glow pass (see
     * [drawGlowShape]), where every part of the shape must be a single flat
     * glow color regardless of any gradient the CRISP draw would otherwise
     * show (matching [RigRenderer.drawGmsShape]: the glow pass uses
     * `gmsGlowPaint`, a plain solid-color paint, never the gradient shader
     * the crisp pass conditionally sets).
     */
    private fun drawOverlayCommands(commands: List<GlesFigureFrame.OverlayDrawCommand>, canvasW: Float, canvasH: Float, colorOverride: Int?) {
        for (cmd in commands) {
            when (cmd) {
                is GlesFigureFrame.OverlayDrawCommand.Polygon -> {
                    val colors = if (colorOverride != null) IntArray(cmd.colors.size) { colorOverride } else cmd.colors
                    drawSolidFan(cmd.points, colors, canvasW, canvasH)
                }
                is GlesFigureFrame.OverlayDrawCommand.Circle -> {
                    val c = argbToGlColor(colorOverride ?: cmd.color)
                    drawCircle(cmd.cx, cmd.cy, cmd.radius, canvasW, canvasH, c[0], c[1], c[2], c[3])
                }
                is GlesFigureFrame.OverlayDrawCommand.Line -> {
                    val c = argbToGlColor(colorOverride ?: cmd.color)
                    drawRoundCappedLine(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.halfWidth, canvasW, canvasH, c[0], c[1], c[2], c[3])
                }
            }
        }
    }

    /** Allocates/reallocates the glow ping-pong FBO pair to exactly [w]x[h], skipping the work entirely if already that size. */
    private fun ensureGlowFbos(w: Int, h: Int) {
        if (glowFboW == w && glowFboH == h && glowFboA != 0) return
        if (glowFboA != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(glowFboA), 0); GLES20.glDeleteTextures(1, intArrayOf(glowTexA), 0) }
        if (glowFboB != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(glowFboB), 0); GLES20.glDeleteTextures(1, intArrayOf(glowTexB), 0) }

        val (fboA, texA) = createFboTexture(w, h)
        val (fboB, texB) = createFboTexture(w, h)
        glowFboA = fboA; glowTexA = texA
        glowFboB = fboB; glowTexB = texB
        glowFboW = w; glowFboH = h
    }

    private fun createFboTexture(w: Int, h: Int): Pair<Int, Int> {
        val texArr = IntArray(1)
        GLES20.glGenTextures(1, texArr, 0)
        val tex = texArr[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboArr = IntArray(1)
        GLES20.glGenFramebuffers(1, fboArr, 0)
        val fbo = fboArr[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Glow FBO incomplete, status=0x${Integer.toHexString(status)}"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fbo to tex
    }

    /** One full-[-1,1]-clip-space-quad blur pass, sampling [srcTex] — see [BLUR_FRAG]'s doc comment. UV is derived from clip position directly (u,v = (clipX+1)/2, (clipY+1)/2) so this is a correct round-trip regardless of GL's internal texture-storage orientation convention — see [drawGlowShape]'s doc comment for why that self-consistency, not matching any external convention, is what actually matters here. */
    private fun drawBlurPass(srcTex: Int, texelStepU: Float, texelStepV: Float) {
        GLES20.glUseProgram(blurProgram)
        val aPos = GLES20.glGetAttribLocation(blurProgram, "a_pos")
        val aUv  = GLES20.glGetAttribLocation(blurProgram, "a_uv")
        val uTex = GLES20.glGetUniformLocation(blurProgram, "u_tex")
        val uStep = GLES20.glGetUniformLocation(blurProgram, "u_texelStep")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uStep, texelStepU, texelStepV)

        val data = floatArrayOf(
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
            -1f, -1f, 0f, 0f,
             1f,  1f, 1f, 1f,
             1f, -1f, 1f, 0f,
            -1f, -1f, 0f, 0f
        )
        drawQuad(data, aPos, aUv)
    }

    /**
     * Two-pass Gaussian blur for one overlay shape's glow — see [BLUR_FRAG]'s
     * doc comment for the separable-blur reasoning and the fixed-tap
     * approximation. Three passes:
     * 1. Draw [overlay]'s geometry, recolored to the layer's glow color,
     *    into [glowFboA], CLEARED TO TRANSPARENT first. Using the ordinary
     *    (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA) blend function while
     *    blending onto a transparent-cleared target is what naturally
     *    produces PREMULTIPLIED-alpha color in the stored texture — that's
     *    a mathematical fact of that blend equation applied to a zero
     *    destination (`dst' = src.rgb*src.a + dst.rgb*(1-src.a)`, and at
     *    `dst=(0,0,0,0)` that's just `src.rgb*src.a`), not a separate
     *    premultiply step bolted on. This matters because blurring STRAIGHT
     *    (non-premultiplied) alpha produces visible dark fringing at soft
     *    edges — averaging a fully-transparent texel's largely-meaningless
     *    RGB with an opaque neighbour's RGB pulls the result toward black
     *    wherever alpha ramps down, which is exactly what a blurred glow's
     *    edge does everywhere. Premultiplied data doesn't have this
     *    problem: RGB is already zero wherever alpha is zero, so blending/
     *    blurring it linearly is well-behaved.
     * 2. Horizontal blur, [glowFboA] → [glowFboB], blending OFF (a pure
     *    texture convolution, not a composite — direct overwrite).
     * 3. Vertical blur, [glowFboB] → whichever framebuffer is CURRENTLY
     *    bound (the real target — background/scene already drawn into it),
     *    blending ON but with the blend function switched to premultiplied
     *    (GL_ONE, GL_ONE_MINUS_SRC_ALPHA) for this ONE draw call, then
     *    restored to the standard (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
     *    used everywhere else in this class. Using the standard blend
     *    function here would double-apply the alpha darkening already
     *    baked into the premultiplied source, since it would multiply
     *    already-premultiplied RGB by alpha a second time.
     * The crisp (non-blurred) shape is drawn separately, on top, by the
     * caller — see [drawFigureFrame]'s overlay loop.
     */
    private fun drawGlowShape(overlay: GlesFigureFrame.OverlayShapeDraw, canvasW: Int, canvasH: Int, w: Float, h: Float) {
        ensureGlowFbos(canvasW, canvasH)
        val stepPx = (overlay.glowRadiusPx / 4f).coerceAtLeast(0.1f)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowFboA)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawOverlayCommands(overlay.commands, w, h, colorOverride = overlay.glowColor)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowFboB)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawBlurPass(glowTexA, stepPx / canvasW, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawBlurPass(glowTexB, 0f, stepPx / canvasH)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
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

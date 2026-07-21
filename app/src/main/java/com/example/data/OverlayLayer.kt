package com.example.data

import kotlinx.serialization.Serializable

/**
 * A single AI-authored motion-graphics overlay layer — text bursts,
 * wordmarks, and simple shapes (rects/circles/lines) composited on top of
 * the stick-figure animation. This is the JSON-schema half of the "GMS"
 * (motion graphics) feature ported from a standalone web reference tool —
 * see V2_DECISIONS.md's "Motion graphics overlay layers" section for the
 * full rationale, including why this became new fields on the EXISTING
 * script format rather than a second DSL/parser.
 *
 * Deliberately BOUNDED-WINDOW ONLY (every layer has an explicit
 * [startSec]/[endSec]) — unlike [ScriptEvent]'s carry-forward fields,
 * there is no "holds until changed" mode for an overlay layer. This is a
 * direct fix for a real bug class found in the reference tool: a
 * persistent text layer with no exit keyframe silently stayed on screen
 * forever, so a LATER layer placed at the same slot/position visually
 * clashed with it. Requiring both ends up front means that bug class
 * can't occur here by construction, not just by prompt guidance — same
 * "no prompt-only safety" philosophy as [AppearanceSettings]'s scene-color
 * clamping.
 *
 * Position/size fields are ALL fractional (0..1 of canvas width/height,
 * same convention [RigRenderer] already uses everywhere else), not
 * absolute pixels — this is what makes dual-aspect export "just work" for
 * these layers with no special-casing, the same reason it already works
 * for the figure/camera/scene composition (see V2_DECISIONS.md's
 * "Dual-aspect export" section).
 *
 * [id]             Optional human-readable label, purely for
 *                  [com.example.engine.ScriptValidator]'s warning messages
 *                  and as a [parentLayer] target — never otherwise read by
 *                  the renderer.
 * [type]           "text" | "shape" | "particles". See
 *                  [com.example.engine.OverlayResolver]. A "particles"
 *                  layer expands into many individual shape particles at
 *                  resolve time — see the Phase 2 particles section below.
 * [shape]          Only used when [type] == "shape": "rect" | "circle" |
 *                  "line" | "arrow". "arrow" draws a line with a
 *                  triangular head pointing along [rotationDeg] — or,
 *                  for a [physics]-driven layer, along its current
 *                  velocity direction instead (a static [rotationDeg]
 *                  would fight the motion and look wrong).
 * [startSec]       When this layer's enter animation begins.
 * [endSec]         When this layer is fully gone. Must be > [startSec].
 * [x]/[y]          Center position, fraction of canvas width/height.
 *                  Ignored for the y-axis if [slot] is set.
 * [slot]           Optional convenience shorthand for [y]: "upper" | "center"
 *                  | "lower". Overrides [y] when set; [x] is unaffected.
 * [width]/[height] Shape size for "rect", fraction of canvas width/height.
 * [radius]         Shape size for "circle", fraction of min(canvasW, canvasH).
 * [rotationDeg]    Static rotation in degrees. Not itself animated in this
 *                  phase (see V2_DECISIONS.md's Phase-1 scope note).
 * [scale]          Static scale multiplier on top of the enter/exit
 *                  animation's own scale.
 * [text]           Required when [type] == "text".
 * [fontSize]       Fraction of canvas HEIGHT — same convention the
 *                  reference tool used, chosen specifically so text reads
 *                  at a consistent relative size across both dual-aspect
 *                  export resolutions rather than looking tiny on one and
 *                  huge on the other.
 * [bold]           Text weight.
 * [align]          "left" | "center" | "right", horizontal text alignment
 *                  relative to [x].
 * [color]          ARGB Long, same convention as [AppearanceSettings].
 * [gradientColor]  If set, fills the shape/text with a top-to-bottom linear
 *                  gradient from [color] to this, instead of a flat fill.
 * [glow]           Whether to draw a soft glow behind this layer.
 * [glowColor]      Defaults to [color] when null.
 * [glowRadius]     Fraction of min(canvasW, canvasH).
 * [enterStyle]     "fade" | "pop" | "zoom" | "slideup" | "slidedown" | "none".
 * [enterDuration]  Seconds, measured from [startSec].
 * [enterEase]      One of [com.example.engine.EasingMath]'s ids.
 * [exitStyle]      Same vocabulary as [enterStyle], played in reverse.
 * [exitDuration]   Seconds, measured backward from [endSec].
 * [exitEase]       One of [com.example.engine.EasingMath]'s ids.
 * [opacity]        Ceiling alpha (0..1) once fully "in" — lets a layer be
 *                  deliberately translucent throughout, independent of the
 *                  enter/exit fade multiplier.
 *
 * ── Phase 2: groups/parenting ───────────────────────────────────────────
 * [parentBone]     Attaches this layer to a stick-figure bone's tip —
 *                  one of "torso" | "head" | "upper_arm_r" | "lower_arm_r"
 *                  | "upper_arm_l" | "lower_arm_l" | "upper_leg_r" |
 *                  "lower_leg_r" | "upper_leg_l" | "lower_leg_l" (see
 *                  [StickFigureRig.BONES]). When set, [x]/[y] become an
 *                  OFFSET from the bone's current position instead of an
 *                  absolute canvas position — e.g. a small glow layer with
 *                  parentBone="lower_arm_r" and x=0/y=0 sits exactly on
 *                  the figure's right hand and follows it through every
 *                  pose. Position-only: this layer does NOT inherit the
 *                  bone's rotation (see [OverlayResolver]'s doc comment
 *                  for why — mainly so attached text doesn't go upside
 *                  down when an arm rotates past vertical).
 * [parentLayer]    Attaches this layer to another overlay layer's [id]
 *                  instead of a bone — full transform compounding
 *                  (position, rotation, scale, opacity all inherit from
 *                  the parent), unlike [parentBone]'s position-only
 *                  attachment. If both [parentBone] and [parentLayer] are
 *                  set, [parentBone] wins and [parentLayer] is ignored —
 *                  [com.example.engine.ScriptValidator] warns about this.
 *                  A cycle (a layer parenting itself, directly or through
 *                  a chain) is also warned about and safely broken at
 *                  render time rather than hanging.
 *
 * ── Phase 2: physics ─────────────────────────────────────────────────────
 * Closed-form (not frame-by-frame simulated) motion, computed fresh from
 * elapsed time on every call — same reasoning as everything else in this
 * schema: [com.example.engine.PlaybackEngine] needs to be able to seek to
 * an arbitrary timestamp without replaying history, so "simulate forward
 * from frame 0" was never an option. See [OverlayResolver] for the actual
 * math (including how repeated bounces are handled without iterating
 * frame-by-frame).
 * [physics]        "none" | "projectile" | "bounce". When not "none",
 *                  this REPLACES [x]/[y] as the resting position (measured
 *                  from [startSec]) — [enterStyle]/[exitStyle] still apply
 *                  their opacity/scale animation on top, just not their
 *                  position offset (slideup/slidedown make no sense
 *                  layered on top of real motion).
 * [physicsVx]      Horizontal velocity, fraction of canvas width per second.
 * [physicsVy]      Initial vertical velocity, fraction of canvas height per
 *                  second. Negative = launched upward.
 * [physicsGravity] Downward acceleration, fraction of canvas height per
 *                  second squared. Default tuned to read as "natural" at
 *                  typical [physicsVy] magnitudes, not a real-world value.
 * [physicsFloorY]  Only used when [physics] == "bounce": fraction of
 *                  canvas height the layer bounces off.
 * [physicsBounceDamping] Fraction of vertical speed retained after each
 *                  bounce (0..1). Bounces are capped once speed decays
 *                  below a small threshold, after which the layer just
 *                  rests at [physicsFloorY] rather than bouncing forever.
 * [trail]          When true, draws a fading motion trail behind a
 *                  physics-driven layer, sampled from the SAME closed-form
 *                  trajectory function at several past instants (not
 *                  accumulated per-frame history) — same "pure function of
 *                  time" reasoning as the rest of this feature. No-op for
 *                  non-physics layers.
 * [trailLengthSec] How far back in time the trail samples from.
 *
 * ── Phase 2: particles ───────────────────────────────────────────────────
 * A `type == "particles"` layer expands into [particleCount] independent
 * burst-emitted particles, ALL spawned at [startSec] (a single burst, not
 * a continuous stream — see [OverlayResolver] for why that scope cut was
 * made). Each particle gets its own deterministic pseudo-random angle/
 * speed/size — same particle index always produces the same result no
 * matter when it's resolved, which is what makes this scrub-safe; see
 * [OverlayResolver]'s particle section for exactly how.
 * [particleCount]      How many particles in the burst.
 * [particleShape]      "circle" | "rect", drawn per-particle.
 * [particleSpeed]      Max initial outward speed, fraction of canvas
 *                      min-dimension per second — each particle gets a
 *                      random speed up to this, not all the same.
 * [particleGravity]    Optional downward acceleration on particles,
 *                      fraction of canvas height per second squared (0 =
 *                      particles drift outward in straight lines, e.g. a
 *                      spark burst; >0 = they arc and fall, e.g. confetti).
 * [particleLifetimeSec] How long each particle lives before fading out —
 *                      same for every particle in the burst; stagger comes
 *                      from their randomized spawn angle/speed, not from
 *                      randomized lifetime.
 * [particleSizeMin]/[particleSizeMax] Per-particle radius range, fraction
 *                      of canvas min-dimension.
 */
@Serializable
data class OverlayLayer(
    val id: String = "",
    val type: String,
    val shape: String = "rect",
    val startSec: Float,
    val endSec: Float,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val slot: String? = null,
    val width: Float? = null,
    val height: Float? = null,
    val radius: Float? = null,
    val rotationDeg: Float = 0f,
    val scale: Float = 1f,
    val text: String? = null,
    val fontSize: Float = 0.08f,
    val bold: Boolean = true,
    val align: String = "center",
    val color: Long = 0xFFFFFFFFL,
    val gradientColor: Long? = null,
    val glow: Boolean = false,
    val glowColor: Long? = null,
    val glowRadius: Float = 0.02f,
    val enterStyle: String = "fade",
    val enterDuration: Float = 0.35f,
    val enterEase: String = "ease_out",
    val exitStyle: String = "fade",
    val exitDuration: Float = 0.35f,
    val exitEase: String = "ease_in",
    val opacity: Float = 1f,
    // Phase 2 — groups/parenting
    val parentBone: String? = null,
    val parentLayer: String? = null,
    // Phase 2 — physics
    val physics: String = "none",
    val physicsVx: Float = 0f,
    val physicsVy: Float = 0f,
    val physicsGravity: Float = 1.2f,
    val physicsFloorY: Float = 0.9f,
    val physicsBounceDamping: Float = 0.55f,
    val trail: Boolean = false,
    val trailLengthSec: Float = 0.4f,
    // Phase 2 — particles (only used when type == "particles")
    val particleCount: Int = 20,
    val particleShape: String = "circle",
    val particleSpeed: Float = 0.3f,
    val particleGravity: Float = 0f,
    val particleLifetimeSec: Float = 1.0f,
    val particleSizeMin: Float = 0.006f,
    val particleSizeMax: Float = 0.016f
)

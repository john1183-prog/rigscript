package com.example.engine

import com.example.data.AmplitudeSettings
import kotlin.math.*

/**
 * Stateful animation engine. Owns the current joint angles and spring velocities.
 *
 * Thread-safety: designed to be ticked from the SurfaceView's background HandlerThread.
 * The public API (seekTo, updateAmplitudeSettings) uses @Volatile fields for safe
 * cross-thread reads from the UI thread.
 */
class PlaybackEngine {

    private val rig   = StickFigureRig
    private val n     = rig.BONE_COUNT
    private val bones = rig.BONES

    // ── Public readable state ─────────────────────────────────────────────────

    /** Total bone angles (defaultAngle + activeOffset) — read by RigRenderer. */
    val currentAngles: FloatArray = FloatArray(n) { i -> bones[i].defaultAngleDegrees }

    /** Coarse mouth shape for the current frame — see [MouthShape]. Read by RigRenderer. */
    @Volatile var currentMouthShape: Int = MouthShape.CLOSED

    /** Smoothed audio amplitude (0..1) for the current frame — used by RigRenderer for continuous mouth-openness scaling. */
    val currentAmplitude: Float get() = smoothedAmplitude

    /** Current facial expression — see [Expression]. Snap semantics (no interpolation). Read by RigRenderer. */
    @Volatile var currentExpression: Int = Expression.NORMAL
        private set

    /**
     * Current camera zoom (1 = no zoom) and pan (fraction of canvas width/height).
     * Purely AI-JSON-driven via [com.example.data.ScriptEvent] — no automatic
     * behaviour derives these from amplitude. Read by RigRenderer/VideoExporter.
     */
    @Volatile var currentCameraZoom: Float = 1f
        private set
    @Volatile var currentCameraPanX: Float = 0f
        private set
    @Volatile var currentCameraPanY: Float = 0f
        private set
    /** One-shot camera shake intensity (0..1), decaying over [SHAKE_DECAY_SEC] from the moment it triggers. */
    @Volatile var currentShakeIntensity: Float = 0f
        private set

    // ── V2 scene state ────────────────────────────────────────────────────────
    // Null sky/ground/horizon means no scripted override is active as of the
    // current keyframe — RigRenderer falls back to AppearanceSettings' own
    // bg color / groundLineYFraction in that case, so a project with no scene
    // events renders exactly as it did before this feature existed.
    @Volatile var currentSkyColor: Long? = null
        private set
    @Volatile var currentGroundColor: Long? = null
        private set
    @Volatile var currentHorizonY: Float? = null
        private set
    @Volatile var currentSceneShape: String = SceneShape.NONE
        private set
    @Volatile var currentSceneAtmosphere: String = SceneAtmosphere.NONE
        private set

    /**
     * Caption text active at [currentTimeSec], or null if no caption cue's
     * bounded window currently covers it. See [com.example.data.ScriptEvent.caption]
     * for why this is bounded-window rather than carry-forward.
     */
    val currentCaption: String? get() = resolveCaption(currentTimeSec)
    @Volatile private var captionCues: List<CaptionCue> = emptyList()

    /** Loads the project's caption cues — call whenever the active script changes. */
    fun loadCaptions(cues: List<CaptionCue>) {
        captionCues = cues
    }

    private fun resolveCaption(timeSec: Float): String? {
        // Cue lists are small (a handful per project) — linear scan is fine,
        // same reasoning as the blink/fidget schedule scans below.
        return captionCues.firstOrNull { timeSec >= it.startSec && timeSec <= it.endSec }?.text
    }

    // ── Sound effects (V2) — one-shot, edge-triggered, preview-only ─────────────
    // Unlike captions (a pull-based "what's active right now" query), a
    // one-shot trigger needs edge detection: fire exactly once when the
    // playhead crosses the cue's timeSec during forward playback, never on
    // seek (scrubbing shouldn't replay every effect between the old and new
    // position), and never twice for the same cue. Export doesn't use this
    // mechanism at all — VideoExporter mixes sound effects directly via
    // AudioMixer using the same SoundEffectCue list, since export already
    // knows every timestamp up front and doesn't need a live "just crossed
    // it" signal.
    private var soundEffectCues: List<SoundEffectCue> = emptyList()
    private var lastCueScanTimeSec: Float = 0f
    private val pendingSoundEffectTriggers = mutableListOf<SoundEffectCue>()

    /** Loads the project's sound-effect trigger cues — call whenever the active script changes. */
    fun loadSoundEffectCues(cues: List<SoundEffectCue>) {
        soundEffectCues = cues
        lastCueScanTimeSec = currentTimeSec
        pendingSoundEffectTriggers.clear()
    }

    /**
     * Returns cues newly crossed since the last call, then clears them.
     * Call once per frame from the same thread that calls [tick] — this app
     * calls both from `AnimationSurfaceView`'s render thread, so no extra
     * synchronization is needed here beyond what already applies to
     * [currentTimeSec] elsewhere in this class.
     */
    fun pollTriggeredSoundEffects(): List<SoundEffectCue> {
        if (pendingSoundEffectTriggers.isEmpty()) return emptyList()
        val out = pendingSoundEffectTriggers.toList()
        pendingSoundEffectTriggers.clear()
        return out
    }

    /**
     * Eye openness for the current frame (1 = fully open, 0 = fully closed).
     * Resolved analytically from [currentTimeSec] against [blinkSchedule] rather
     * than a live frame-to-frame timer — the same reason [seekTo] uses
     * [EasingMath.springAnalytical] instead of physics integration: a stateful
     * random timer would make preview and export blink at different moments for
     * the same timestamp. Call [loadBlinkSchedule] whenever the project loads or
     * its script/audio duration changes.
     */
    val currentEyeOpenness: Float get() = resolveEyeOpenness(currentTimeSec)

    // ── Internal state ────────────────────────────────────────────────────────

    private val springVelocities: FloatArray = FloatArray(n)
    private var timeline: List<BakedKeyframe> = emptyList()
    /** Current playback position in seconds. Public so [com.example.ui.canvas.AnimationSurfaceView] can expose it for the scrubber. */
    var currentTimeSec: Float = 0f
        private set
    private var smoothedAmplitude: Float = 0f

    /** True when the active keyframe's ease is `"rigid"` — [tick] bypasses spring integration entirely when this is set. */
    private var activeKeyframeIsRigid: Boolean = false
    /** Merged, sorted, deterministic blink trigger times (script + natural). Set via [loadBlinkSchedule]. */
    @Volatile private var blinkSchedule: FloatArray = FloatArray(0)
    /** Deterministic idle-fidget trigger times, scheduled only inside sufficiently silent stretches. Set via [loadFidgetSchedule]. */
    @Volatile private var fidgetSchedule: FloatArray = FloatArray(0)

    @Volatile var amplitudeSettings: AmplitudeSettings = AmplitudeSettings()
    @Volatile var rawAmplitude: Float = 0f          // set from AudioPlayer each frame
    @Volatile var rawMouthShape: Int = MouthShape.CLOSED  // set from AudioPlayer each frame

    // Pre-allocated working arrays — zero allocation in the hot path
    private val baseAngles: FloatArray = FloatArray(n)  // target angles from timeline
    private val ampDelta:   FloatArray = FloatArray(n)  // amplitude-driven offsets

    // Bone index cache for amplitude modulation
    private val idxTorso    = rig.BONE_INDEX["torso"]    ?: -1
    private val idxHead     = rig.BONE_INDEX["head"]     ?: -1
    private val idxArmR     = rig.BONE_INDEX["upper_arm_r"] ?: -1
    private val idxArmL     = rig.BONE_INDEX["upper_arm_l"] ?: -1

    // ── Timeline loading ──────────────────────────────────────────────────────

    /**
     * Loads a new compiled timeline.
     *
     * Deliberately does NOT reset [currentTimeSec] — replacing the timeline (e.g.
     * after a script edit) should not snap playback back to the start. The target
     * pose is recomputed at the current time; if [snapToCurrentTime] is true (the
     * default — used while paused) [currentAngles] jumps straight to the new
     * target so edits are reflected immediately. While playing, leave it false so
     * the spring eases toward the new target instead of popping.
     */
    fun loadTimeline(keyframes: List<BakedKeyframe>, snapToCurrentTime: Boolean = true) {
        timeline = keyframes
        resolveBaseAngles(currentTimeSec, useAnalyticalSpring = true)
        if (snapToCurrentTime) {
            baseAngles.copyInto(currentAngles)
            springVelocities.fill(0f)
        }
    }

    /**
     * Builds the deterministic blink trigger schedule for this project:
     * [scriptedTimes] (from [com.example.data.AnimScript.blinkEvents], always
     * included) merged with an auto-generated natural-blink sequence spanning
     * `[0, durationSec]` when [AmplitudeSettings.naturalBlinkEnabled] is true.
     * The natural sequence uses a FIXED seed ([BLINK_SEED]) rather than a live
     * random timer, so seeking to the same [currentTimeSec] during preview or
     * export always produces the same eye state — see [currentEyeOpenness].
     *
     * Call whenever the active project's script or audio duration changes.
     */
    fun loadBlinkSchedule(scriptedTimes: List<Float>, durationSec: Float) {
        val settings = amplitudeSettings
        val merged = sortedSetOf<Float>()
        merged.addAll(scriptedTimes)
        if (settings.naturalBlinkEnabled && durationSec > 0f) {
            val rng = kotlin.random.Random(BLINK_SEED)
            val lo = settings.blinkMinIntervalSec.coerceAtLeast(0.5f)
            val hi = settings.blinkMaxIntervalSec.coerceAtLeast(lo + 0.1f)
            var t = lo + rng.nextFloat() * (hi - lo)
            while (t < durationSec) {
                merged.add(t)
                t += lo + rng.nextFloat() * (hi - lo)
            }
        }
        blinkSchedule = merged.toFloatArray()
    }

    /**
     * Builds the deterministic idle-fidget trigger schedule by scanning
     * [envelope] (sampled at [envFps] — e.g. the project's amplitude
     * envelope) for candidate moments that fall inside a stretch of at least
     * [AmplitudeSettings.fidgetMinSilenceSec] below
     * [AmplitudeSettings.silenceThreshold], spaced using
     * [AmplitudeSettings.fidgetMinIntervalSec]/[AmplitudeSettings.fidgetMaxIntervalSec].
     * Uses a FIXED seed ([FIDGET_SEED]) for the same preview/export-determinism
     * reason as [loadBlinkSchedule] — an idempotent export matters for a
     * production workflow (re-exporting the same project shouldn't change
     * the animation).
     *
     * Trades a small amount of precision for simplicity versus a full
     * contiguous-silence pre-scan: each candidate is accepted if a window of
     * [AmplitudeSettings.fidgetMinSilenceSec] centred on it is entirely below
     * threshold, rather than pre-computing exact silent-range boundaries. In
     * practice this lands in the same places for normal speech audio at a
     * fraction of the bookkeeping — flagging this as a simplification rather
     * than claiming it's the more rigorous approach.
     *
     * Call whenever the active project's audio/amplitude envelope changes.
     * Safe to call with an empty envelope — no fidgets get scheduled, and
     * nothing fires anyway when [AmplitudeSettings.idleFidgetEnabled] is off.
     */
    fun loadFidgetSchedule(envelope: FloatArray, envFps: Int) {
        val settings = amplitudeSettings
        val schedule = mutableListOf<Float>()
        if (settings.idleFidgetEnabled && envelope.isNotEmpty() && envFps > 0) {
            val rng = kotlin.random.Random(FIDGET_SEED)
            val durationSec = envelope.size.toFloat() / envFps
            val lo = settings.fidgetMinIntervalSec.coerceAtLeast(0.5f)
            val hi = settings.fidgetMaxIntervalSec.coerceAtLeast(lo + 0.1f)
            val checkFrames = (settings.fidgetMinSilenceSec * envFps).toInt().coerceAtLeast(1)
            var t = lo + rng.nextFloat() * (hi - lo)
            while (t < durationSec) {
                val centerFrame = (t * envFps).toInt()
                val fromFrame = (centerFrame - checkFrames / 2).coerceAtLeast(0)
                val toFrame = (centerFrame + checkFrames / 2).coerceAtMost(envelope.size - 1)
                val isSilentWindow = fromFrame <= toFrame &&
                    (fromFrame..toFrame).all { envelope[it] <= settings.silenceThreshold }
                if (isSilentWindow) schedule.add(t)
                t += lo + rng.nextFloat() * (hi - lo)
            }
        }
        fidgetSchedule = schedule.toFloatArray()
    }

    /** Returns playback to the start. Used by the Stop control. */
    fun reset() {
        currentTimeSec = 0f
        smoothedAmplitude = 0f
        springVelocities.fill(0f)
        resolveBaseAngles(0f, useAnalyticalSpring = true)
        baseAngles.copyInto(currentAngles)
        lastCueScanTimeSec = 0f
        pendingSoundEffectTriggers.clear()
    }

    // ── Tick (called ~60fps from background thread) ───────────────────────────

    /**
     * Advances time by [dtSec] seconds and updates [currentAngles].
     * [rawAmp] is the normalised audio amplitude for this frame (0..1).
     */
    fun tick(dtSec: Float, rawAmp: Float = this.rawAmplitude) {
        val safeDt = dtSec.coerceIn(0f, 0.05f)   // cap to prevent spring explosion on lag
        currentTimeSec += safeDt

        // V2 — fire any sound-effect cues the playhead just crossed. Must run
        // BEFORE lastCueScanTimeSec is updated below, and uses (last, current]
        // so a cue at exactly the new currentTimeSec still fires this frame
        // rather than being skipped to the next.
        if (soundEffectCues.isNotEmpty()) {
            for (cue in soundEffectCues) {
                if (cue.timeSec > lastCueScanTimeSec && cue.timeSec <= currentTimeSec) {
                    pendingSoundEffectTriggers += cue
                }
            }
        }
        lastCueScanTimeSec = currentTimeSec

        // 1. Resolve target angles (and V2 expression/camera/shake) from timeline
        resolveBaseAngles(currentTimeSec, useAnalyticalSpring = false)

        // 2. Spring-integrate currentAngles toward baseAngles — EXCEPT for
        //    "rigid" keyframes, which must snap with zero physics character.
        //    Letting the spring chase a suddenly-updated target still LOOKS
        //    springy for a few frames; rigid means mechanical, not "springy
        //    toward a new goalpost".
        if (activeKeyframeIsRigid) {
            baseAngles.copyInto(currentAngles)
            springVelocities.fill(0f)
        } else {
            applySpringIntegration(safeDt)
        }

        // 3. Layer amplitude-driven motion on top
        applyAmplitudeMotion(safeDt, rawAmp)

        // 4. Propagate mouth shape from AudioPlayer → renderer
        currentMouthShape = rawMouthShape
    }

    /** Seek without physics — instantly snaps to the correct pose at [timeSec]. */
    fun seekTo(timeSec: Float) {
        currentTimeSec = timeSec
        springVelocities.fill(0f)
        resolveBaseAngles(timeSec, useAnalyticalSpring = true)
        baseAngles.copyInto(currentAngles)
        // V2 — a seek/scrub should never replay every sound effect between the
        // old and new position; just move the scan pointer, don't fire anything.
        lastCueScanTimeSec = timeSec
        pendingSoundEffectTriggers.clear()
    }

    /**
     * Export-only variant of [seekTo] that also applies the amplitude motion
     * layer (idle breathing + talk sway) on top of the scripted pose.
     *
     * Unlike [tick], the pose is resolved analytically (no spring integration,
     * no inter-frame carry-over for the bones). However, [smoothedAmplitude]
     * IS stateful — its low-pass filter accumulates correctly only when frames
     * are called sequentially (0, 1, 2, ...). Never call this out of order.
     *
     * [rawAmp] should be indexed from the pre-baked amplitude envelope at
     * [timeSec], using [AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS] as the
     * envelope sample rate — independent of the export FPS.
     */
    fun seekToWithAmplitude(timeSec: Float, rawAmp: Float, mouthShape: Int = MouthShape.CLOSED) {
        currentTimeSec = timeSec
        springVelocities.fill(0f)
        resolveBaseAngles(timeSec, useAnalyticalSpring = true)
        baseAngles.copyInto(currentAngles)
        applyAmplitudeMotion(1f / 30f, rawAmp)
        currentMouthShape = mouthShape
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fills [baseAngles] with the target total angles at [timeSec].
     *
     * For each keyframe whose transition window contains [timeSec], the
     * progress [t] is computed and the appropriate easing applied.
     * The LAST matching keyframe wins (since events are sorted by time).
     */
    private fun resolveBaseAngles(timeSec: Float, useAnalyticalSpring: Boolean) {
        if (timeline.isEmpty()) {
            bones.forEachIndexed { i, b -> baseAngles[i] = b.defaultAngleDegrees }
            activeKeyframeIsRigid = false
            currentExpression = Expression.NORMAL
            currentCameraZoom = 1f
            currentCameraPanX = 0f
            currentCameraPanY = 0f
            currentShakeIntensity = 0f
            currentSkyColor = null
            currentGroundColor = null
            currentHorizonY = null
            currentSceneShape = SceneShape.NONE
            currentSceneAtmosphere = SceneAtmosphere.NONE
            return
        }

        // Find the active keyframe
        var activeKf: BakedKeyframe? = null
        for (kf in timeline) {
            if (timeSec >= kf.timeSec) activeKf = kf
            else break
        }

        val kf = activeKf ?: timeline.first()
        val elapsed  = (timeSec - kf.timeSec).coerceAtLeast(0f)
        val t        = (elapsed / kf.duration).coerceIn(0f, 1f)

        activeKeyframeIsRigid = kf.ease == "rigid"

        // "rigid" snaps straight to the target — no curve, no spring, in either
        // path. Otherwise, non-"spring"-tagged transitions get the analytical
        // spring treatment on the SEEK/EXPORT path too when
        // [AmplitudeSettings.easeAllWithSpring] is on, so exported video matches
        // what the live tick()-path spring chase already produces in preview —
        // the same "must be unified" reasoning documented on [applySpringIntegration].
        val easedT = when {
            activeKeyframeIsRigid -> 1f
            kf.ease == "spring" && useAnalyticalSpring ->
                EasingMath.springAnalytical(t, kf.springStiffness, kf.springDamping)
            kf.ease == "spring" -> t   // real-time spring uses the Euler path in applySpringIntegration
            useAnalyticalSpring && amplitudeSettings.easeAllWithSpring ->
                EasingMath.springAnalytical(t, kf.springStiffness, kf.springDamping)
            else -> EasingMath.ease(kf.ease, t)
        }

        for (i in 0 until n) {
            baseAngles[i] = kf.fromAngles[i] + (kf.toAngles[i] - kf.fromAngles[i]) * easedT
        }

        // ── V2 — face + camera, resolved from the same active keyframe/progress ──
        currentExpression = kf.expression
        currentCameraZoom = kf.fromCameraZoom + (kf.toCameraZoom - kf.fromCameraZoom) * easedT
        currentCameraPanX = kf.fromCameraPanX + (kf.toCameraPanX - kf.fromCameraPanX) * easedT
        currentCameraPanY = kf.fromCameraPanY + (kf.toCameraPanY - kf.fromCameraPanY) * easedT

        // Shake is a one-shot burst tied to this keyframe's OWN start time, not
        // to transition progress — it decays over a fixed short window
        // regardless of how long the pose transition itself takes.
        val shakeElapsed = timeSec - kf.timeSec
        currentShakeIntensity = if (kf.cameraShake > 0f && shakeElapsed < SHAKE_DECAY_SEC) {
            kf.cameraShake * (1f - shakeElapsed / SHAKE_DECAY_SEC)
        } else 0f

        // ── V2 — scene, same active-keyframe/progress basis as camera above ──
        currentSkyColor    = lerpNullableColor(kf.fromSkyColor, kf.toSkyColor, easedT)
        currentGroundColor = lerpNullableColor(kf.fromGroundColor, kf.toGroundColor, easedT)
        currentHorizonY    = lerpNullableFloat(kf.fromHorizonY, kf.toHorizonY, easedT)
        currentSceneShape      = kf.sceneShape
        currentSceneAtmosphere = kf.sceneAtmosphere
    }

    /**
     * Interpolates two nullable ARGB colors. Null means "no scripted override
     * yet" (see [BakedKeyframe]'s doc comment) — if [to] is null there's
     * nothing to transition toward, so the result is just [from] (itself
     * possibly null). If only [from] is null (the very first override just
     * appeared this keyframe), interpolating from garbage would flash — so we
     * snap-hold [to] for this keyframe rather than lerp from nothing.
     */
    private fun lerpNullableColor(from: Long?, to: Long?, t: Float): Long? {
        if (to == null) return from
        val start = from ?: return to
        return lerpColor(start, to, t)
    }

    private fun lerpNullableFloat(from: Float?, to: Float?, t: Float): Float? {
        if (to == null) return from
        val start = from ?: return to
        return start + (to - start) * t
    }

    /** Component-wise ARGB lerp between two 0xAARRGGBB Longs. */
    private fun lerpColor(from: Long, to: Long, t: Float): Long {
        val ct = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Long {
            val a = (from shr shift) and 0xFFL
            val b = (to shr shift) and 0xFFL
            return (a + (b - a) * ct).toLong().coerceIn(0L, 255L) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun resolveEyeOpenness(timeSec: Float): Float {
        val schedule = blinkSchedule
        if (schedule.isEmpty()) return 1f
        // Same "last matching wins" scan as the keyframe search above — blink
        // counts are small (at most a few dozen per project) so a linear scan
        // costs nothing meaningful at 60fps.
        var lastBlinkTime = -1f
        for (bt in schedule) {
            if (bt <= timeSec) lastBlinkTime = bt else break
        }
        if (lastBlinkTime < 0f) return 1f
        val dur = amplitudeSettings.blinkDurationSec.coerceAtLeast(0.02f)
        val elapsed = timeSec - lastBlinkTime
        if (elapsed >= dur) return 1f
        // Triangular curve: open → fully closed at the midpoint → open.
        val half = dur / 2f
        return if (elapsed < half) 1f - (elapsed / half) else (elapsed - half) / half
    }

    /**
     * Returns a signed -1..1 "bump" for the fidget active at [timeSec] (0 if
     * none) — magnitude follows a smooth ease-in/hold/ease-out curve, sign
     * gives a cheap left/right variation derived from the trigger's own
     * timestamp so consecutive fidgets don't look identical. Not a real
     * random source — just enough variety for a barely-visible cosmetic
     * effect; not worth a second parallel schedule array for this.
     */
    private fun resolveFidgetBump(timeSec: Float): Float {
        val schedule = fidgetSchedule
        if (schedule.isEmpty()) return 0f
        var lastFidgetTime = -1f
        for (ft in schedule) {
            if (ft <= timeSec) lastFidgetTime = ft else break
        }
        if (lastFidgetTime < 0f) return 0f
        val dur = amplitudeSettings.fidgetDurationSec.coerceAtLeast(0.1f)
        val elapsed = timeSec - lastFidgetTime
        if (elapsed >= dur) return 0f
        val shape = sin(PI.toFloat() * (elapsed / dur).coerceIn(0f, 1f))
        val sign = if (((lastFidgetTime * 137f).toInt()) % 2 == 0) 1f else -1f
        return shape * sign
    }

    /** Pre-allocated output buffer for springEulerStep — avoids Pair allocation in the hot path. */
    private val springStep = FloatArray(2)   // [0]=newPos, [1]=newVel

    /** 4-step Euler spring integration toward [baseAngles]. */
    private fun applySpringIntegration(dt: Float) {
        val subDt = dt / 4f
        repeat(4) {
            for (i in 0 until n) {
                EasingMath.springEulerStep(
                    pos       = currentAngles[i],
                    vel       = springVelocities[i],
                    target    = baseAngles[i],
                    stiffness = 320f,
                    damping   = 32f,
                    dt        = subDt,
                    out       = springStep
                )
                currentAngles[i]    = springStep[0]
                springVelocities[i] = springStep[1]
            }
        }
    }

    /**
     * Adds amplitude-driven oscillation on top of the timeline-driven angles.
     *
     * Two layers:
     *  - Idle breathing: always present, low amplitude, slow sin wave.
     *  - Talk motion: active above [AmplitudeSettings.silenceThreshold],
     *    proportional to smoothed amplitude, faster oscillation.
     */
    private fun applyAmplitudeMotion(dt: Float, rawAmp: Float) {
        val s = amplitudeSettings
        if (!s.enabled) return

        // Smooth amplitude with a one-pole low-pass filter
        smoothedAmplitude += (rawAmp - smoothedAmplitude) * (1f - s.smoothingFactor)

        val t = currentTimeSec.toDouble()

        // ── Idle breathing ────────────────────────────────────────────────────
        val breathOsc = sin(2.0 * PI * s.breathFreqHz * t).toFloat()
        if (idxTorso  >= 0) currentAngles[idxTorso]  += breathOsc * s.idleBreathAmplitude
        if (idxHead   >= 0) currentAngles[idxHead]   -= breathOsc * (s.idleBreathAmplitude * 0.5f)

        // ── Talk motion (only when above silence threshold) ───────────────────
        if (smoothedAmplitude > s.silenceThreshold) {
            val talkOsc  = sin(2.0 * PI * s.talkFreqHz * t).toFloat()
            val strength = smoothedAmplitude - s.silenceThreshold

            if (idxTorso >= 0)
                currentAngles[idxTorso] += talkOsc * s.talkTorsoAmplitude * strength
            if (idxHead  >= 0)
                currentAngles[idxHead]  += talkOsc * s.talkHeadNodAmplitude * strength

            if (s.armSwayEnabled) {
                val armOsc = sin(2.0 * PI * (s.talkFreqHz * 0.5) * t).toFloat()
                if (idxArmR >= 0)
                    currentAngles[idxArmR] += armOsc * s.armSwayAmplitude * strength
                if (idxArmL >= 0)
                    currentAngles[idxArmL] -= armOsc * s.armSwayAmplitude * strength
            }
        }

        // ── Idle fidget ──────────────────────────────────────────────────────
        // loadFidgetSchedule() only ever places triggers inside confirmed-quiet
        // stretches, so no extra runtime amplitude check is needed here — the
        // schedule itself already encodes "only during silence".
        if (s.idleFidgetEnabled) {
            val fidget = resolveFidgetBump(currentTimeSec)
            if (fidget != 0f) {
                if (idxTorso >= 0) currentAngles[idxTorso] += fidget * s.fidgetAmplitude
                if (idxHead  >= 0) currentAngles[idxHead]  -= fidget * s.fidgetAmplitude * 0.4f
                val armIdx = if (fidget > 0f) idxArmR else idxArmL
                if (armIdx >= 0) currentAngles[armIdx] += fidget * s.fidgetAmplitude * 0.6f
            }
        }

        // Apply angle constraints
        for (i in 0 until n) {
            val bone = bones[i]
            val totalMin = bone.minAngleDegrees?.let { bone.defaultAngleDegrees + it }
            val totalMax = bone.maxAngleDegrees?.let { bone.defaultAngleDegrees + it }
            if (totalMin != null && totalMax != null) {
                if (currentAngles[i] < totalMin) {
                    currentAngles[i] = totalMin
                    springVelocities[i] = 0f
                } else if (currentAngles[i] > totalMax) {
                    currentAngles[i] = totalMax
                    springVelocities[i] = 0f
                }
            }
        }
    }

    // ── Pose editor helpers ───────────────────────────────────────────────────

    /** Directly set a bone's total angle (for the interactive pose editor). */
    fun setBoneAngle(boneIndex: Int, totalAngleDegrees: Float) {
        if (boneIndex in 0 until n) {
            currentAngles[boneIndex]    = totalAngleDegrees
            springVelocities[boneIndex] = 0f
            baseAngles[boneIndex]       = totalAngleDegrees
        }
    }

    /** Returns the active rotation offset (total − default) for each bone. */
    fun captureRelativeAngles(): Map<String, Float> {
        return bones.mapIndexedNotNull { i, bone ->
            val offset = currentAngles[i] - bone.defaultAngleDegrees
            if (abs(offset) > 0.5f) bone.id to offset else null
        }.toMap()
    }

    companion object {
        /** Fixed window over which a one-shot [com.example.data.ScriptEvent.cameraShake] burst decays to zero. */
        private const val SHAKE_DECAY_SEC = 0.3f
        /**
         * Fixed seed for the natural-blink sequence in [loadBlinkSchedule]. Must
         * never change to a time-based or otherwise non-deterministic value —
         * doing so would make preview and export blink at different times for
         * the same project.
         */
        private const val BLINK_SEED = 20260701L
        /** Fixed seed for [loadFidgetSchedule] — same determinism reasoning as [BLINK_SEED]. */
        private const val FIDGET_SEED = 20260702L
    }
}

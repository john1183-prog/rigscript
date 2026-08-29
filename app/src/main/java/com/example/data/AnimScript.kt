package com.example.data

import kotlinx.serialization.Serializable

/**
 * A single event in the animation script.
 *
 * Example JSON:
 * ```json
 * { "timeSec": 2.5, "pose": "wave", "duration": 0.8, "ease": "spring" }
 * ```
 *
 * [timeSec]          Absolute timestamp in seconds (relative to audio start).
 * [pose]             ID of a pose in the global PoseDef library.
 * [duration]         How long the transition INTO this pose takes, in seconds.
 * [ease]             One of: linear | ease_in | ease_out | ease_in_out |
 *                            bounce | elastic_out | spring | rigid
 *                    "rigid" snaps instantly to the target pose with no
 *                    interpolation and no spring chase — for mechanical or
 *                    abrupt emotional cuts. Every OTHER ease type gets a
 *                    physics spring-chase layered on top by default (see
 *                    [com.example.data.AmplitudeSettings.easeAllWithSpring]).
 * [springStiffness]  Only used when ease == "spring". Higher = snappier.
 * [springDamping]    Only used when ease == "spring". Higher = less oscillation.
 * [expression]       Optional facial expression for this event — one of
 *                    normal | wide | squint | worried | angry | happy (see
 *                    [com.example.engine.Expression]). null = hold whatever
 *                    expression was last set (same carry-forward semantics as
 *                    pose). Independent of the audio-driven mouth shape.
 * [cameraZoom]       Optional camera zoom target (1.0 = no zoom). null = hold
 *                    previous value. Eases toward the target using [ease] over
 *                    [duration], same as the pose transition.
 * [cameraPanX]       Optional horizontal pan target, as a fraction of canvas
 *                    width (-1..1 roughly). null = hold previous value.
 * [cameraPanY]       Optional vertical pan target, as a fraction of canvas
 *                    height. null = hold previous value.
 * [cameraShake]      Optional one-shot camera shake burst (0..1 intensity)
 *                    triggered the instant this event becomes active. Decays
 *                    over ~0.3s. null/0 = no shake. Does NOT carry forward —
 *                    it's a momentary trigger, not a held state.
 * [caption]          Optional on-screen caption text starting at this event's
 *                    [timeSec]. BOUNDED-WINDOW semantics, not carry-forward:
 *                    unlike pose/expression/camera, a caption does NOT stay
 *                    visible until the next event sets a new one — it's only
 *                    shown for [captionDurationSec], then disappears. This
 *                    matches how captions are actually authored (one line per
 *                    spoken beat) and avoids stale/orphaned text sitting on
 *                    screen through unrelated later events. Extracted
 *                    separately as [com.example.engine.CaptionCue] rather than
 *                    baked into [com.example.engine.BakedKeyframe] — see
 *                    [com.example.engine.TimelineCompiler.extractCaptions].
 * [captionDurationSec] How long [caption] stays on screen, in seconds. Ignored
 *                    if [caption] is null.
 * [skyColor]         Optional scene sky/background color for this event
 *                    onward — ARGB Long, same convention as
 *                    [AppearanceSettings]'s color fields. null = hold the
 *                    previous value (or the appearance default if never set).
 *                    Carry-forward, same rule as pose/expression.
 * [groundColor]      Optional ground-band color, same carry-forward rule as [skyColor].
 * [horizonY]         Optional horizon line position, fraction of canvas height
 *                    (0..1). null = hold previous value.
 * [sceneShape]       Optional background silhouette — one of
 *                    [com.example.engine.SceneShape]'s constants as a string
 *                    (none|mountains|city|trees|clouds). null = hold previous.
 * [sceneAtmosphere]  Optional foreground weather/atmosphere overlay — one of
 *                    [com.example.engine.SceneAtmosphere]'s constants as a
 *                    string (none|rain|snow|fog|stars). null = hold previous.
 * [soundEffect]      Optional one-shot sound effect id, triggered AT this
 *                    event's [timeSec]. Does NOT carry forward — same
 *                    category as [cameraShake], not [caption]. The id must
 *                    match a clip already imported into the project's sound
 *                    effect library (see
 *                    [com.example.viewmodel.MainViewModel.importSoundEffect]);
 *                    an unrecognized id is silently ignored rather than
 *                    treated as an error, same graceful-degradation
 *                    reasoning as [com.example.engine.SceneShape.fromString].
 * [soundEffectVolume] Multiplier (0..1 typical, not hard-clamped) applied on
 *                    top of the clip's own configured volume for this
 *                    specific trigger. Ignored if [soundEffect] is null.
 *
 * ── Figure transform & colors (script-driven [com.example.data.AppearanceSettings] overrides) ──
 * Carry-forward, interpolated exactly like [cameraZoom]/[cameraPanX] — these
 * are DISTINCT from the camera fields above: camera fields move a virtual
 * viewpoint around a figure that stays put; these move/recolor the figure
 * itself. Null means "no scripted override yet" — [RigRenderer] falls back
 * to the project's own [com.example.data.AppearanceSettings] value, so a
 * script that never sets any of these renders exactly as it did before this
 * feature existed.
 * [figureX]/[figureY]  Figure root position, fraction of canvas width/height
 *                    — overrides [com.example.data.AppearanceSettings.rootAnchorX]/[rootAnchorY].
 * [figureScale]      Overall character scale multiplier — overrides
 *                    [com.example.data.AppearanceSettings.characterScale]. NOT the
 *                    same as [cameraZoom]: this changes the figure's own
 *                    size in the scene; [cameraZoom] changes how much of the
 *                    whole scene is visible.
 * [headScale]        Overrides [com.example.data.AppearanceSettings.headScaleMultiplier].
 * [figureOpacity]    0..1 opacity multiplier for the whole figure (bones, head, face) —
 *                    independent of [figureX]/[figureY]/[figureScale]. Null means fully
 *                    opaque, same fallback rule as the other overrides. Lets the figure
 *                    fade out to let motion-graphics overlays lead, then fade back in.
 * [boneColor]/[headColor]/[jointColor]/[mouthColor]/[eyeColor]/[eyebrowColor]
 *                    Override the matching [com.example.data.AppearanceSettings] color.
 * [bgColor]          Overrides BOTH [com.example.data.AppearanceSettings.previewBgColor]
 *                    AND [exportBgColor] uniformly — the preview/export split
 *                    is a rendering-target implementation detail the script
 *                    has no reason to know or care about.
 * [backgroundGradientColor]/[backgroundStyle] Override the matching
 *                    [com.example.data.AppearanceSettings] fields. [backgroundStyle] is
 *                    snap (carry-forward, no interpolation) like [sceneShape],
 *                    not interpolated like the colors — "solid"|"gradient".
 *                    Setting [backgroundGradientColor] without also setting
 *                    [backgroundStyle] to "gradient" (here or on an earlier
 *                    event) has no visible effect.
 * [groundLineColor]/[groundLineYFraction]/[showGroundLine] Override the
 *                    matching [com.example.data.AppearanceSettings] fields.
 *                    [showGroundLine] is snap like [backgroundStyle], not
 *                    interpolated (a boolean can't meaningfully lerp).
 *                    Setting [groundLineColor] without [showGroundLine] set
 *                    to true (here or earlier) has no visible effect.
 */
@Serializable
data class ScriptEvent(
    val timeSec: Float,
    val pose: String,
    val duration: Float = 0.5f,
    val ease: String = "ease_in_out",
    val springStiffness: Float = 280f,
    val springDamping: Float = 28f,
    val expression: String? = null,
    val cameraZoom: Float? = null,
    val cameraPanX: Float? = null,
    val cameraPanY: Float? = null,
    val cameraShake: Float? = null,
    val caption: String? = null,
    val captionDurationSec: Float = 2.5f,
    val skyColor: Long? = null,
    val groundColor: Long? = null,
    val horizonY: Float? = null,
    val sceneShape: String? = null,
    val sceneAtmosphere: String? = null,
    val soundEffect: String? = null,
    val soundEffectVolume: Float = 1.0f,
    // Figure transform
    val figureX: Float? = null,
    val figureY: Float? = null,
    val figureScale: Float? = null,
    val headScale: Float? = null,
    val figureOpacity: Float? = null,
    // Figure & scene colors
    val boneColor: Long? = null,
    val headColor: Long? = null,
    val jointColor: Long? = null,
    val bgColor: Long? = null,
    val backgroundGradientColor: Long? = null,
    val backgroundStyle: String? = null,
    val groundLineColor: Long? = null,
    val showGroundLine: Boolean? = null,
    val groundLineYFraction: Float? = null,
    val mouthColor: Long? = null,
    val eyeColor: Long? = null,
    val eyebrowColor: Long? = null
)

/**
 * The complete animation script attached to a project.
 * This is stored as JSON inside the project row in the database and can also be
 * imported/exported as a standalone .json file.
 *
 * [blinkEvents] Timestamps (seconds) where a dramatic blink should fire,
 * independent of [events]. Kept separate from [ScriptEvent] rather than a flag
 * on it because a blink can happen mid-hold, unrelated to any pose change — the
 * AI shouldn't have to restate the current pose just to place one. Natural
 * blinking happens automatically regardless of this list; these are ADDITIONAL,
 * deliberate, AI-placed blinks for emotional emphasis (e.g. right before a key
 * line, or on a reaction beat).
 * [overlayLayers] Motion-graphics overlay layers — text bursts, wordmarks,
 * and simple shapes composited on top of the figure. Kept as its own
 * top-level list rather than fields on [ScriptEvent] because these are
 * self-contained bounded-window elements (explicit start AND end), not
 * carry-forward pose-timeline state — see [OverlayLayer]'s doc comment.
 */
@Serializable
data class AnimScript(
    val version: String = "1.0",
    val events: List<ScriptEvent> = emptyList(),
    val blinkEvents: List<Float> = emptyList(),
    val overlayLayers: List<OverlayLayer> = emptyList()
) {
    companion object {
        /** Canonical blank script shown when a project is created. */
        val EMPTY = AnimScript(
            events = listOf(
                ScriptEvent(timeSec = 0f, pose = "stand_straight", duration = 0.3f, ease = "ease_out")
            )
        )

        /**
         * Demo script — exercises poses plus the V2 expression/camera/
         * rigid/blink features, plus overlayLayers (motion-graphics text/
         * shape). The two text layers are deliberately staggered in BOTH
         * time and slot (upper vs center) — exercising the non-clashing
         * case ScriptValidator's overlap check is meant to allow, as
         * opposed to the same-slot/overlapping-time case it's meant to
         * flag. See V2_DECISIONS.md's "Motion graphics overlay layers"
         * section.
         *
         * The first two events also carry sceneShape/sceneAtmosphere/sky-
         * ground/ground-line fields (Phase 4 — V2_DECISIONS.md) — city+rain
         * at night, transitioning to mountains+snow at the wave beat —
         * specifically so `VideoExporter.exportGlesSmokeTest`'s fixed ~3s
         * window (see that function) shows a real scene TRANSITION, not
         * just one static backdrop, without altering this script's existing
         * pose/expression/timing narrative anywhere. sceneShape/
         * sceneAtmosphere strings match SceneShape/SceneAtmosphere's
         * constants in com.example.engine — written as raw string literals
         * here rather than imported, since this data class deliberately
         * has no dependency on that package. Those same first two events
         * now also carry a small cameraZoom/cameraPanX/cameraPanY move plus
         * a cameraShake burst on the wave beat (camera phase —
         * V2_DECISIONS.md) — small and clearly within GLES's 0.1x zoom
         * floor and typical pan range, not a stress-test extreme, since the
         * point is confirming the transform reads correctly against the
         * figure/scene/overlays already in frame, not finding its edge
         * cases. Pan is explicitly reset to 0 at the 3.5s explain event, just
         * after this window, so it doesn't linger into the rest of the
         * timeline's own already-careful state resets (figureX/figureScale
         * at 19.9s, boneColor/bgColor at 24.0s) — camera zoom was already
         * being reset that way at 17.5s for the existing 15s/21.5s beats,
         * this just extends the same discipline to pan.
         *
         * Text phase (V2_DECISIONS.md) — two more additions inside the
         * same ~3s window, both previously silent gaps: `wordmark_intro`
         * (already present, already inside the window) now carries a
         * gradient + glow, since it was plain white-on-nothing text before
         * this phase and so never exercised those specific text-overlay
         * code paths in the smoke test; the wave event now carries a
         * caption, which `exportGlesSmokeTest` would have silently
         * rendered nothing for regardless of this field, since that
         * function never called `engine.loadCaptions` at all until fixed
         * in the same commit as this script change. The caption's 1.3s
         * window (1.5–2.8s) and `intro_badge`'s "lower" slot (0.78 y-
         * fraction, 0.3–2.6s) overlap in TIME but not in screen position —
         * checked against `OverlayResolver`'s actual slot-to-y-fraction
         * mapping before choosing this timing, not just assumed clear.
         *
         * Stress-test extension (V2_DECISIONS.md — "GLES export rewrite —
         * stress test"): everything above this point (events 0.0-24.0s,
         * the matching blinkEvents/overlayLayers) is UNCHANGED — it's the
         * hand-tuned content [VideoExporter.exportGlesSmokeTest]'s plain
         * ~3s quick-check mode still renders, and every comment above still
         * describes exactly what it did before. Everything from 24.0s on
         * is new, generated rather than hand-authored (see [stressCycle]),
         * existing to give the GLES stress-test mode a real multi-minute
         * duration to run against — not narrative choreography the way the
         * original section is.
         *
         * [stressCycle]'s repeat count is currently 1 per batch (was 7,
         * ~8 minutes total) — shortened for a fast iteration turnaround
         * while checking specific recent fixes, not doing the actual
         * long-duration thermal/memory stress testing the repeat count
         * exists for. Bump [stressCycle]'s `count` argument (both call
         * sites) back up before relying on this for a real stress run —
         * nothing else about the mechanism changed.
         *
         * Several deliberate, one-off insertions between the two
         * [stressCycle] batches, not part of the generated cycle itself:
         * a "lazy"-pose hold for a clean, arm-unoccluded rotation check
         * (the wave pose's raised arm crosses the head, which made a
         * previous device review's single-eye observation ambiguous);
         * gradient background + camera zoom/pan active together, and a
         * glowing overlay active during that same camera zoom — both
         * flagged in V2_DECISIONS.md as never separately checked even
         * after each feature was individually confirmed; and the room/
         * beach scene shapes. A second shape+gradient+glow overlay (same
         * combination as `intro_badge`) repeats later so it's easy to
         * spot again on playback, not just in the first few seconds — was
         * fixed at 300s back when this ran to ~8 minutes, retimed to land
         * inside the (now much earlier) final stressCycle batch instead.
         * This whole paragraph describes the ACTUAL current insertions —
         * a version of it describing a "248-255s" window and a glow
         * overlay's own timing had drifted out of sync with the real
         * event list before this rewrite, independent of and predating
         * this session's own shortening; treat this as the corrected,
         * current source of truth rather than assume it'll stay accurate
         * forever either.
         */
        // Pose/ease/expression for one entry in the repeating filler cycle
        // stressCycle() generates — plain data, not a ScriptEvent itself,
        // so the loop below only has to fill in timeSec.
        private data class CycleBeat(val offsetSec: Float, val pose: String, val ease: String = "ease_in_out", val expression: String? = null)

        private val STRESS_CYCLE_TEMPLATE = listOf(
            CycleBeat(0.0f,  "shrug"),
            CycleBeat(3.0f,  "point_up",       "ease_out"),
            CycleBeat(6.0f,  "confused",       expression = "worried"),
            CycleBeat(9.0f,  "think"),
            CycleBeat(12.0f, "jog_a"),
            CycleBeat(12.8f, "jog_b"),
            CycleBeat(13.6f, "jog_a"),
            CycleBeat(14.4f, "stand_straight", "ease_out"),
            CycleBeat(17.0f, "sit"),
            CycleBeat(20.0f, "tired"),
            CycleBeat(23.0f, "jump",           "elastic_out", "wide"),
            CycleBeat(26.0f, "point_left",     "ease_out"),
            CycleBeat(29.0f, "point_right",    "ease_out")
        )
        private const val STRESS_CYCLE_LEN_SEC = 32f

        /**
         * [count] repetitions of [STRESS_CYCLE_TEMPLATE], starting at
         * [startSec] — purely to give the GLES stress-test mode a real
         * multi-minute duration to run against when [count] is large
         * (V2_DECISIONS.md; current call sites use count=1 for a fast
         * iteration turnaround, not the multi-minute run this exists for
         * — see the class-level doc comment above [DEMO]). Reuses the
         * pose library's fuller vocabulary (jog/jump/sit/tired/point_*)
         * rather than the smaller set the hand-authored section above
         * needed, so a long run doesn't feel identical on loop. Not meant
         * to be edited by hand the way the events above are — regenerate
         * by changing [STRESS_CYCLE_TEMPLATE]/[STRESS_CYCLE_LEN_SEC]
         * instead of hand-patching individual generated events.
         */
        private fun stressCycle(startSec: Float, count: Int): List<ScriptEvent> {
            val out = ArrayList<ScriptEvent>(count * STRESS_CYCLE_TEMPLATE.size)
            for (i in 0 until count) {
                val base = startSec + i * STRESS_CYCLE_LEN_SEC
                for (beat in STRESS_CYCLE_TEMPLATE) {
                    out += ScriptEvent(base + beat.offsetSec, beat.pose, 0.5f, beat.ease, expression = beat.expression)
                }
            }
            return out
        }

        /** One extra blink per [stressCycle] repetition, same [count]/[startSec] convention. */
        private fun stressCycleBlinks(startSec: Float, count: Int): List<Float> =
            (0 until count).map { startSec + it * STRESS_CYCLE_LEN_SEC + 18f }

        val DEMO = AnimScript(
            events = listOf(
                ScriptEvent(0.0f,  "stand_straight", 0.4f, "ease_out",
                    sceneShape = "city", sceneAtmosphere = "rain",
                    skyColor = 0xFF16213EL, groundColor = 0xFF0F3443L, horizonY = 0.72f,
                    showGroundLine = true, groundLineColor = 0xFF4FC3F7L,
                    // Camera phase (V2_DECISIONS.md) — small opening zoom-in
                    // + pan, inside the smoke test window.
                    cameraZoom = 1.15f, cameraPanX = -0.06f),
                ScriptEvent(1.5f,  "wave",           0.6f, "spring", expression = "happy",
                    sceneShape = "mountains", sceneAtmosphere = "snow",
                    skyColor = 0xFF87CEEBL, groundColor = 0xFFE8F4F8L, horizonY = 0.68f,
                    // Zoom settles back to 1x, pan shifts vertically, and a
                    // shake burst lands on the wave beat — exercises all
                    // four camera fields together within the same ~3s
                    // window the scene-shape transition already covers.
                    cameraZoom = 1f, cameraPanY = 0.04f, cameraShake = 0.35f,
                    // Text phase (V2_DECISIONS.md) — captions weren't
                    // exercised anywhere in the smoke test window before
                    // this. exportGlesSmokeTest previously never called
                    // engine.loadCaptions at all (fixed alongside this),
                    // so this would have silently rendered nothing even
                    // with the field set. Ends at 2.8s, before the pan
                    // reset at 3.5s and comfortably inside the window.
                    caption = "Waving hello!", captionDurationSec = 1.3f),
                ScriptEvent(3.5f,  "explain",        0.7f, "ease_in_out",
                    // Reset pan back to center just after the smoke test
                    // window — same discipline the 17.5s event already
                    // applies to cameraZoom for the later 15s/21.5s beats.
                    cameraPanX = 0f, cameraPanY = 0f),
                ScriptEvent(6.0f,  "present",        0.6f, "ease_out",
                    // Figure transform (V2) — shifts left and grows slightly,
                    // distinct from camera zoom. Exercises figureX/figureScale.
                    figureX = 0.4f, figureScale = 1.15f),
                ScriptEvent(8.5f,  "point_self",     0.5f, "spring"),
                ScriptEvent(10.5f, "open_hands",     0.5f, "ease_in_out"),
                ScriptEvent(12.5f, "think",          0.8f, "ease_in_out", expression = "worried"),
                ScriptEvent(15.0f, "excited",        0.5f, "elastic_out", expression = "wide",
                    cameraZoom = 1.12f),
                ScriptEvent(17.5f, "walk_a",         0.4f, "ease_in_out", expression = "normal",
                    cameraZoom = 1f),
                ScriptEvent(18.3f, "walk_b",         0.4f, "ease_in_out"),
                ScriptEvent(19.1f, "walk_a",         0.4f, "ease_in_out"),
                ScriptEvent(19.9f, "stand_straight", 0.1f, "rigid",
                    // Snap back to center after the walk+shift from the
                    // present event at 6.0s — before celebrate, so the
                    // center-slot wordmark at 21.6s doesn't overlap the
                    // figure (caught by ScriptValidator's new overlap check).
                    figureX = 0.5f, figureScale = 1f),
                ScriptEvent(21.5f, "celebrate",      0.6f, "elastic_out", expression = "happy",
                    cameraZoom = 1.18f, cameraShake = 0.4f,
                    // Figure/scene colors (V2) — a warm color shift on the
                    // celebrate beat. Exercises boneColor + bgColor together.
                    boneColor = 0xFFFF7043L, bgColor = 0xFF3E2723L),
                ScriptEvent(24.0f, "stand_straight", 0.8f, "ease_in_out", expression = "normal",
                    cameraZoom = 1f,
                    // Reset colors back to the project defaults for a clean
                    // loop — figureX/figureScale don't need restating, they
                    // were already reset at 19.9f and haven't changed since.
                    boneColor = 0xFF0000FFL, bgColor = 0xFF1A1A2EL)
            ) + stressCycle(25.0f, 1) + listOf(
                // Shortened for a fast iteration turnaround (was 7 repeats
                // per batch, ~8min total) — this is checking specific,
                // recent fixes, not doing the long-duration thermal/memory
                // stress testing stressCycle's repeat count exists for.
                // Bump both counts back up before any real stress-test run.
                //
                // Clean, UNOCCLUDED rotation check: the wave pose (1.5s,
                // above) raises an arm straight up past the head, which
                // made the last device video's single-eye observation
                // ambiguous — couldn't tell head-rotation bug from arm
                // occlusion. "lazy" has real torso+head rotation deltas
                // (22°/-14°) but its most-raised arm is only 28° (nothing
                // near vertical, nowhere close to the head), so anything
                // wrong with an eye here is unambiguously the rotation fix,
                // not the pose's own arm position. Explicit scene/camera/
                // background reset — stress-cycle beats above only ever
                // touch pose/duration/ease/expression, so this would
                // otherwise still be showing whatever the very first
                // (t=0/1.5s) events set, undisturbed by anything in
                // between, but resetting explicitly here is one less
                // assumption to carry.
                ScriptEvent(55.0f, "lazy", 1.0f, "ease_in_out",
                    sceneShape = "mountains", sceneAtmosphere = "snow",
                    skyColor = 0xFF87CEEBL, groundColor = 0xFFE8F4F8L, horizonY = 0.68f,
                    backgroundStyle = "solid", cameraZoom = 1f, cameraPanX = 0f, cameraPanY = 0f),
                // Combo test (V2_DECISIONS.md, stress-test extension):
                // gradient background ACTIVE at the same time as a camera
                // zoom/pan move — each confirmed individually, never
                // together.
                ScriptEvent(58.0f, "explain", 1.0f, "ease_in_out",
                    backgroundStyle = "gradient", backgroundGradientColor = 0xFF283593L,
                    cameraZoom = 1.2f, cameraPanX = 0.05f, cameraPanY = -0.03f),
                ScriptEvent(63.0f, "stand_straight", 1.0f, "ease_in_out",
                    // Reset both back to defaults before the room/beach
                    // segment below.
                    backgroundStyle = "solid", cameraZoom = 1f, cameraPanX = 0f, cameraPanY = 0f),
                // Scene shapes (V2_DECISIONS.md, "Background shapes: room +
                // beach"). sceneShape/sceneAtmosphere both snap (carry-
                // forward, no interpolation), so each of these holds until
                // the next event changes it — explicit sceneAtmosphere =
                // "none" here matters because "snow" would otherwise still
                // be carrying forward from 1.5s.
                ScriptEvent(64.0f, "explain", 0.6f, "ease_in_out",
                    sceneShape = "room", sceneAtmosphere = "none",
                    skyColor = 0xFFF3E5D0L, groundColor = 0xFFC9A876L, horizonY = 0.68f),
                // Beach + stars together deliberately, same untested-combo
                // rationale as the gradient+camera test above. Also the
                // only place in this whole script sceneAtmosphere = "stars"
                // appears — needed to exercise the star-radius resolution-
                // scaling fix at all.
                ScriptEvent(67.0f, "present", 0.6f, "ease_in_out",
                    sceneShape = "beach", sceneAtmosphere = "stars",
                    skyColor = 0xFF1A2744L, groundColor = 0xFF0D1B2AL, horizonY = 0.62f),
                ScriptEvent(70.0f, "stand_straight", 0.8f, "ease_in_out",
                    // Reset to exactly the 1.5s event's own values — batch
                    // 2 of stressCycle below was authored against
                    // mountains+snow and shouldn't see anything different.
                    sceneShape = "mountains", sceneAtmosphere = "snow",
                    skyColor = 0xFF87CEEBL, groundColor = 0xFFE8F4F8L, horizonY = 0.68f)
            ) + stressCycle(73.0f, 1),
            blinkEvents = listOf(1.3f, 14.7f, 21.4f) + stressCycleBlinks(25.0f, 1) + stressCycleBlinks(73.0f, 1),
            overlayLayers = listOf(
                // Text phase (V2_DECISIONS.md) — gradient+glow text active
                // 0.2-3.0s, inside the smoke test window, so this specific
                // combination (not just plain text — wordmark_intro was
                // white-on-nothing before this phase) is actually visible
                // in the debug export. bold=true is the field default, not
                // set explicitly, but was already being exercised even
                // before this edit. gradientColor picks up the same light
                // blue as the opening event's groundLineColor, for visual
                // cohesion rather than an arbitrary test color.
                OverlayLayer(
                    id = "wordmark_intro", type = "text", text = "HELLO!",
                    startSec = 0.2f, endSec = 3.0f, slot = "upper",
                    fontSize = 0.09f, color = 0xFFFFFFFFL, gradientColor = 0xFF4FC3F7L,
                    glow = true, glowRadius = 0.025f,
                    enterStyle = "pop", enterEase = "back", enterDuration = 0.4f,
                    exitStyle = "fade", exitDuration = 0.3f
                ),
                // GLES verification — a gradient+glow rect active early
                // (0.3-2.6s, inside VideoExporter.exportGlesSmokeTest's
                // fixed ~3s window), so this specific combination is
                // actually visible in the debug export rather than only
                // existing at 15s in accent_underline below, which the
                // smoke test never reaches. Doubles as legitimate demo
                // content — a lower-third accent at video open is a
                // normal real element, not just a synthetic test shape —
                // and previews the same visual idea accent_underline pays
                // off properly later, same color pairing.
                OverlayLayer(
                    id = "intro_badge", type = "shape", shape = "rect",
                    startSec = 0.3f, endSec = 2.6f, slot = "lower",
                    width = 0.32f, height = 0.025f, color = 0xFFFF7043L, gradientColor = 0xFFFFEE58L,
                    glow = true, glowRadius = 0.015f,
                    enterStyle = "slideup", exitStyle = "fade"
                ),
                OverlayLayer(
                    id = "accent_underline", type = "shape", shape = "rect",
                    startSec = 15.0f, endSec = 16.8f, slot = "lower",
                    width = 0.35f, height = 0.02f, color = 0xFFFFD54FL,
                    glow = true, glowRadius = 0.015f,
                    enterStyle = "slideup", exitStyle = "fade"
                ),
                OverlayLayer(
                    id = "wordmark_celebrate", type = "text", text = "AMAZING!",
                    startSec = 21.6f, endSec = 24.0f, slot = "center",
                    fontSize = 0.11f, color = 0xFFFFEB3BL, bold = true,
                    enterStyle = "zoom", enterEase = "elastic_out", enterDuration = 0.5f,
                    exitStyle = "fade", exitDuration = 0.4f
                ),
                // Phase 2 — bone attachment: a small glow that follows the
                // right hand through the wave pose, exercising parentBone.
                OverlayLayer(
                    id = "hand_sparkle", type = "shape", shape = "circle",
                    startSec = 1.5f, endSec = 3.4f,
                    parentBone = "lower_arm_r", x = 0f, y = 0f,
                    radius = 0.018f, color = 0xFFFFF59DL,
                    glow = true, glowRadius = 0.03f,
                    enterStyle = "fade", exitStyle = "fade"
                ),
                // Phase 2 — physics: a small ball bouncing across the lower
                // third of the frame with a fading trail, exercising both
                // the bounce solver and trail sampling.
                OverlayLayer(
                    id = "bouncing_ball", type = "shape", shape = "circle",
                    startSec = 6.2f, endSec = 8.4f,
                    x = 0.15f, y = 0.4f, radius = 0.02f, color = 0xFF4FC3F7L,
                    physics = "bounce", physicsVx = 0.35f, physicsVy = -0.4f,
                    physicsGravity = 1.4f, physicsFloorY = 0.82f, physicsBounceDamping = 0.55f,
                    trail = true, trailLengthSec = 0.3f,
                    enterStyle = "none", exitStyle = "fade"
                ),
                // Phase 2 — particles: a confetti-style burst on the
                // celebrate beat, exercising the deterministic emitter.
                OverlayLayer(
                    id = "celebrate_burst", type = "particles", particleShape = "rect",
                    startSec = 21.5f, endSec = 22.8f,
                    x = 0.5f, y = 0.35f,
                    particleCount = 24, particleSpeed = 0.4f, particleGravity = 0.9f,
                    particleLifetimeSec = 1.1f, particleSizeMin = 0.008f, particleSizeMax = 0.018f,
                    color = 0xFFFF7043L, gradientColor = 0xFFFFEE58L
                ),
                // Character variants — a supporting, illustrative second
                // figure (e.g. "she told her friend...") alongside the main
                // one during the explain beat. Built-in pose only, own
                // color for visual distinction, smaller by default.
                OverlayLayer(
                    id = "supporting_figure", type = "figure",
                    startSec = 3.6f, endSec = 5.8f,
                    x = 0.8f, y = 0.62f, scale = 0.8f,
                    pose = "wave", expression = "happy", color = 0xFFFFA726L,
                    enterStyle = "fade", exitStyle = "fade"
                )
            ) + listOf(
                // Combo test 2/2 (V2_DECISIONS.md, stress-test extension):
                // glowRadiusPx scaling under camera zoom — each confirmed
                // individually, never together. Overlaps the
                // cameraZoom=1.2f window above (58.0-63.0s) exactly —
                // retimed along with that event when the tail was
                // shortened; was 248.5-253.5s against a 248.0-254.0s
                // window, same 0.5s-in/0.5s-early-out offset kept here.
                OverlayLayer(
                    id = "combo_glow_zoom_test", type = "shape", shape = "circle",
                    startSec = 58.5f, endSec = 62.5f,
                    x = 0.5f, y = 0.3f, radius = 0.05f, color = 0xFF00E5FFL,
                    glow = true, glowRadius = 0.04f,
                    enterStyle = "fade", exitStyle = "fade"
                ),
                // Same shape+gradient+glow combination as intro_badge
                // (0.3-2.6s), repeated later so it's easy to spot again on
                // playback instead of only in the first few seconds —
                // shape-overlay glow was flagged unconfirmed for longer
                // than any other phase (V2_DECISIONS.md). Was 300s, back
                // when the tail ran to ~8 minutes; retimed to land inside
                // the last stressCycle's ~73-102s window now that it
                // doesn't.
                OverlayLayer(
                    id = "shape_glow_reprise", type = "shape", shape = "rect",
                    startSec = 92.0f, endSec = 96.0f, slot = "lower",
                    width = 0.3f, height = 0.025f, color = 0xFF26C6DAL, gradientColor = 0xFFFFF176L,
                    glow = true, glowRadius = 0.018f,
                    enterStyle = "slideup", exitStyle = "fade"
                )
            )
        )
    }
}

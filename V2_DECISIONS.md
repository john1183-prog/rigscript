# RigScript V2 — Architectural Decisions

This file exists so decisions are written down as they're made, not left
implicit — see the project's own working principle on this. It's the
second time this file has been written: the first version was lost when a
development sandbox reset wiped an unpushed session (see "History" below).
Everything in this version is verified against what's actually on `main`
as of commit `fb36363`, not against what a prior session believed it had
pushed.

## History

- V2 development began with a design consultation on animation content
  creator workflows before any code was written.
- `a95bc24` and `7234495` ("V2:..." / "V2 fixes:...") landed the first
  wave: envelope file storage, eyes/expression/blink, camera pan/zoom/
  shake, rigid-vs-spring easing with spring as the default, idle fidget,
  export ETA, and the tap-to-seek event timeline strip.
- A later session attempted to add a second wave — captions, the sky/
  ground scene system, and the manual reference overlay — through nine
  incremental "RECOVERY N/9" commits after a sandbox reset wiped the
  working tree mid-session. Those commits were never actually pushed:
  when the branch was re-cloned in a later session, none of that work
  was present on `main` or on `version2`, despite a handoff document
  describing it as complete. Lesson taken: a commit only counts once
  `git push` has been confirmed to succeed against the real remote, not
  once `git commit` succeeds locally.
- `ecfa779` and `fb36363` ("V2 REBUILD 1/N" / "2/N") redid that second
  wave from scratch on `main` (post-merge) once the gap was discovered by
  direct inspection of the cloned repo rather than by trusting the prior
  handoff doc's claims.

## What's implemented

**Rendering / rig**
- FK-based stick figure rendering, head drawn last (z-order fix — a
  right-eye-smaller-than-left artifact traced to head-behind-limb draw
  order in certain poses).
- Eyes, eyebrows (eyebrows only draw for `WORRIED`/`ANGRY` — see
  `Expression.kt`), mouth shapes driven by audio amplitude/ZCR
  classification.
- `headScaleMultiplier` in `AppearanceSettings` — independent head-size
  scaling from `characterScale`, applied only to the head circle's
  drawn radius, not to any FK bone length or joint position.
- Face features (eyes/mouth) positioned as a fraction of head radius,
  not fixed offsets — fixes an eye/mouth overlap bug traced to head bone
  `normalizedLength` sitting close to `headNormalizedRadius` for some
  poses.

**Camera**
- Zoom/pan purely driven by `ScriptEvent.cameraZoom/cameraPanX/
  cameraPanY` — no automatic amplitude-derived camera behavior. This is
  deliberate, see "AI drives the pipeline" below.
- One-shot camera shake (`cameraShake`) decays over a fixed window from
  the moment it triggers; does not carry forward.
- Pan/shake divided by zoom before applying, fixing a bug where pan and
  shake silently compounded with zoom level.

**Scene system (V2 REBUILD)**
- `ScriptEvent.skyColor` / `groundColor` / `horizonY` — carry-forward
  semantics (like pose/expression/camera): a `null` value on a later
  event means "hold whatever was last set," not "clear it." All three
  are nullable end-to-end (`ScriptEvent` → `BakedKeyframe` → resolved
  `PlaybackEngine` state) so that a project with zero scene events
  renders through exactly the same solid/gradient background path as
  before this feature existed — the null case is not a special case in
  `RigRenderer`, it's the default path.
- `ScriptEvent.sceneShape` / `sceneAtmosphere` — one of `SceneShape`'s or
  `SceneAtmosphere`'s string constants (`engine/Scene.kt`). Same
  carry-forward semantics, but these default to `"none"` rather than
  null since `NONE` is itself a meaningful state, not an unset one.
- Scene shapes (`mountains`/`city`/`trees`/`clouds`) are cheap procedural
  silhouettes — no imported art — so they scale to any canvas size for
  free and re-color safely.
- `RigRenderer.constrainSceneColor()` enforces a minimum 50° hue
  separation from the figure's bone color and caps saturation at 0.45.
  This is enforced unconditionally in code, not left to AI prompt
  guidance — a scripted scene color can never visually compete with or
  blend into the figure regardless of what an AI-generated script asks
  for.
- Atmosphere effects (`rain`/`snow`/`fog`/`stars`) are drawn in screen
  space, after the camera transform is restored, so they read as a
  whole-viewport filter rather than an object that pans/zooms with the
  scene. Positions are a deterministic function of the current playback
  time (not a live random source), so preview and export produce
  identical frames for the same timestamp — same reasoning as the blink/
  fidget schedules.

**Captions**
- `ScriptEvent.caption` + `captionDurationSec` use BOUNDED-WINDOW
  semantics, explicitly not carry-forward: a caption shows for its
  duration and then disappears, rather than staying on screen until the
  next event sets a new one. This matches how captions are actually
  authored (one line per spoken beat) and avoids stale text surviving
  through unrelated later events.
- Extracted as a separate `CaptionCue` list
  (`TimelineCompiler.extractCaptions()`) rather than baked into
  `BakedKeyframe` — folding bounded-window data into a carry-forward
  structure would force every consumer to re-derive "is this still
  active," which is exactly the bug class bounded-window semantics
  exist to avoid.
- Rendered as a bottom-anchored, screen-space subtitle (fixed regardless
  of camera pan/zoom), via `StaticLayout` for wrapping.

**Reference overlay**
- `ReferenceOverlay` (`data/ReferenceOverlay.kt`) — manual, one-time-
  configured per project, image or text. Explicitly excluded from the
  AI script-generation pipeline: it's never read by `TimelineCompiler`
  and is not something the AI prompt should ever be asked to produce
  (see `PROMPT_CONSIDERATIONS.md`).
- Image variant decodes its bitmap once (on the render thread in
  preview, once up front before the frame loop in export) — never
  per-frame; decoding is I/O + allocation and must not happen in a hot
  draw loop.
- Drawn either before or after the figure's FK pass depending on
  `inFrontOfFigure`, but always within the camera transform (so it pans/
  zooms with the scene, matching a "diagram on a wall" mental model)
  — unlike captions/atmosphere, which are screen-space fixed.
- File I/O (import/remove) lives in `MainViewModel`, following the same
  boundary as audio import — see "AppRepository stays DB-only" below.

**Audio-reactive motion / transitions**
- Export reads the amplitude envelope via `tick()`, not `seekTo()` — a
  bug had exports with zero audio-reactive motion because the envelope
  was never sampled on the export path.
- `easeAllWithSpring` (default `true`) layers a physics spring-chase on
  top of every transition's base ease curve, not just events tagged
  `"spring"`. `"rigid"`-tagged events bypass this entirely, including on
  the seek path, not just during `tick()` — rigid is the explicit escape
  hatch for abrupt/mechanical cuts.
- Spring easing and amplitude envelope reading are unified between the
  preview and export code paths — these diverged silently at one point
  and are treated as a first-class parity concern now.
- `springEulerStep` returns a pre-allocated `FloatArray`, not a `Pair`
  — eliminates per-frame heap allocation in a hot loop.

**Expression / blink / fidget**
- Expression states use snap semantics (carry-forward, no
  interpolation) — same category as pose.
- Natural idle blinking (`naturalBlinkEnabled`, default on) and idle
  fidget (`idleFidgetEnabled`, default OFF — see rationale in
  `AmplitudeSettings.kt`) both use deterministic seeded schedules, not
  live randomness, for preview/export parity.
- Blink/fidget schedules refresh on their own `LaunchedEffect` in
  `EditorScreen`, independent of script edits — so changing
  `AmplitudeSettings` mid-session doesn't require an unrelated script
  edit to take effect.

**Export**
- Bulk `Bitmap.getPixels()` instead of per-pixel `getPixel()` in the
  export loop — the per-pixel version caused multi-hour export times.
- `RigRenderer` is a `class`, not an `object` — preview and export run
  concurrently against their own instances; a shared singleton caused a
  real race.
- Export progress reports an ETA (`exportEtaSec`) alongside raw percent.

**Storage / persistence**
- Envelope files (amplitude + mouth shape) are stored as binary files
  via `EnvelopeStore`, with migration-on-load in `MainViewModel` for
  projects saved before this existed (`readAmplitudeWithFallback` /
  `readMouthShapesWithFallback` read the new file-backed path and fall
  back to the deprecated inline field).
- `AppRepository` stays strictly DB-only. All file I/O — audio import,
  envelope files, reference overlay images — lives in `MainViewModel`.
  This boundary is intentional: `AppRepository` should never need to
  know about the filesystem.
- `AppDatabase` uses a `lateinit var instance` pattern to fix a cold-
  start seeding race.

**Background music**
- `BackgroundMusicSettings` (`musicFilePath`/`volume`/`narrationVolume`/`loop`)
  is a single project-level slot, mixed under the narration on export.
- Export-time mixing is real PCM work, not just muxing two tracks: decode
  both narration and music to PCM (`AudioMixer`), resample/channel-convert
  both to a common 44.1kHz-stereo format, mix sample-wise at their
  configured volumes, and re-encode to a single AAC track. This only runs
  when `backgroundMusic.musicFilePath` is actually set — every other
  export keeps the fast verbatim-copy audio path unchanged.
- Neither volume is normalized against the other — narration and music are
  independent multipliers on the mix, not two ends of a single balance
  slider. Deliberate: lets narration be ducked without needing to guess a
  compensating music level.
- Live preview uses a second, separate `MediaPlayer` alongside the
  narration player rather than real-time PCM mixing — small sync drift
  between them while scrubbing is an accepted preview-only tradeoff; the
  export path is what actually needs one correctly-mixed result.
- Flagged as the highest-risk unverified code in the project so far: it's
  the first code path exercising MediaCodec's audio decode/encode side at
  all (everything before it only used MediaCodec for video), and none of
  it has run against a real device/compiler yet.

**Sound effects**
- Per-project sound effect library (`SoundEffectClip`: `id`/`filePath`/
  `volume`), a growable list rather than a single slot — a project can
  have several distinct clips, each triggered independently.
- User-imported per project rather than drawn from a bundled catalog — a
  bundled CC0 library (Kenney assets) was the original plan, but wasn't
  pursued in the same session this was implemented because kenney.nl
  itself isn't on the sandbox's network allowlist.
  **Correction, verified in a later session**: this was too pessimistic.
  GitHub (which IS reachable) hosts real repackagings of Kenney's CC0
  packs — confirmed directly via the GitHub API, not assumed:
  `Calinou/kenney-ui-audio` and `Calinou/kenney-interface-sounds` both
  exist, are public, and (per their own descriptions) are Kenney's actual
  UI/interface sound packs repackaged for quick use. Both carry Kenney's
  CC0 terms. A bundled default library is therefore genuinely tractable
  from this environment and just hasn't been integrated yet — it's not
  blocked, only not-yet-done. The two approaches aren't mutually
  exclusive either way: bundled clips could be a richer default library
  alongside user imports, not a replacement for them.
- `ScriptEvent.soundEffect` + `soundEffectVolume` are one-shot, bounded to
  a single instant — same category as `cameraShake`, not `caption`
  (there's no "end," just a trigger). An unrecognized id is silently
  ignored rather than erroring, matching `SceneShape`/`SceneAtmosphere`'s
  graceful-degradation convention.
- Preview and export use genuinely different mechanisms, same split as
  background music: preview uses `SoundPool` (Android's low-latency
  one-shot mechanism) with edge-triggered firing in `PlaybackEngine`
  (fires exactly once when the playhead crosses a cue during forward
  `tick()` playback; never fires on seek/scrub, since scrubbing shouldn't
  replay every effect between the old and new position). Export instead
  hands the same cue list straight to `AudioMixer`, which decodes and
  overlays each clip into the mixed PCM buffer at its exact timestamp —
  no edge-triggering needed there since export already knows every
  timestamp up front.

**Dual-aspect export**
- `ExportSettings.dualAspectExport` produces both 9:16 and 16:9 as two
  separate output files in one export call, ignoring `aspectRatio` when
  set. Not just "run export twice": `VideoExporter` builds a list of
  `ExportTarget`s (one per aspect ratio, each with its own bitmap/canvas/
  encoder/muxer/output file) and drives them from a single shared frame
  loop — `PlaybackEngine.seekToWithAmplitude()` (the actual timeline/pose/
  expression/camera/scene resolution) runs exactly once per frame
  regardless of target count, and the audio (verbatim narration copy, or
  the background-music/sound-effect mix) is computed once and written
  into every target's muxer. Only the per-target draw + YUV convert +
  MediaCodec encode genuinely repeats — unavoidable, since two different
  pixel grids are two different encode jobs no matter what.
- This is why the figure/camera/scene composition works correctly across
  both aspect ratios with no special-casing: `RigRenderer` already
  expresses every position (root anchor, camera pan, scene shapes,
  caption/overlay placement) as a fraction of canvas width/height rather
  than fixed pixels, so calling `draw()` with a different canvas size
  naturally reframes correctly. Dual-aspect export didn't need any new
  composition logic — it's a consequence of a design choice already made
  for other reasons (screen-size portability).
- `VideoExporter.export()`'s return type changed from a single
  `ExportResult` to `List<ExportResult>` (each tagged with `aspectLabel`)
  to represent this — a normal export still returns a one-element list,
  so nothing about single-target export's behavior changed, just its
  return shape. `MainViewModel`/`EditorScreen` updated accordingly.

**Motion graphics overlay layers (Phase 1: text + shape)**
- Ported from a standalone web reference tool ("GMS" — a JS canvas motion
  graphics DSL with its own parser/interpreter/editor). The CONCEPTS were
  ported (text bursts, shapes, gradients, glow, enter/exit animation), not
  the DSL itself — `OverlayLayer` is new JSON fields on the EXISTING
  script schema, not a second parser/text format. Deliberate: the whole
  pipeline (Copy AI Prompt, semantic validation, the AI generator) is
  built around the AI writing ONE JSON format; a second syntax would mean
  context-switching between two languages in a single script, working
  against "script in, video out" with zero manual per-video work.
- Every layer is BOUNDED-WINDOW ONLY — `startSec` AND `endSec` are both
  required, with no carry-forward mode at all (unlike `ScriptEvent`'s
  pose/expression/camera/scene fields). This directly fixes a real bug
  found in the reference tool: a persistent text layer with no exit
  keyframe stayed on screen forever, so a later layer placed at the same
  screen position visually collided with it. Requiring both ends up front
  makes that bug class structurally impossible here, not just something
  the prompt has to remember to avoid — same "no prompt-only safety"
  philosophy as `constrainSceneColor()`. `ScriptValidator` additionally
  warns (doesn't block) when two layers' windows overlap AND land in the
  same slot/near-same position, catching the "deliberately placed wrong"
  version of the same mistake.
- All position/size fields are FRACTIONAL (0..1 of canvas width/height or
  min-dimension), never absolute pixels — same convention `RigRenderer`
  already uses everywhere (camera pan, scene shapes, root anchor). This is
  what makes these layers portable across dual-aspect export's two
  differently-sized targets for free, the same reason dual-aspect export
  itself needed no new composition logic. `fontSize` is specifically a
  fraction of canvas HEIGHT (not width, not a fixed sp value) so text
  reads at a consistent relative size on both a 9:16 and a 16:9 render of
  the same script — the concrete answer to "text shouldn't look tiny on
  one aspect ratio and huge on the other."
- Drawn INSIDE the camera transform (before `RigRenderer`'s
  `canvas.restore()`), not after it like captions/atmosphere — a
  deliberate difference from those two screen-space layers. Captions are
  meant to read like fixed subtitles the camera can't pan away from;
  these overlay layers are meant to feel like part of the composed scene,
  panning/zooming/shaking along with the figure when the camera moves.
  One shared camera drives both, per the same reasoning that put
  `cameraZoom`/`cameraPanX`/`cameraPanY` on `ScriptEvent` in the first
  place — a second independent camera for overlay layers was considered
  and rejected as unnecessary complexity for what a single camera already
  handles.
- `OverlayResolver` is a stateless pure function of (layer, timeSec), same
  spirit as `EasingMath` — there's no carry-forward state to bake into an
  intermediate compiled form the way `TimelineCompiler.compile()` needs
  for pose/camera/scene, so `TimelineCompiler.extractOverlayLayers()` only
  sorts, it doesn't thread any running state through the list the way the
  keyframe-baking loop does.
- Each layer's enter and exit phases reuse the SAME style-to-animation
  mapping (`OverlayResolver.animate()`), just played with progress running
  in opposite directions — an exit is the mirror image of an enter, not a
  separately-coded animation. `enterStyle`/`exitStyle` can still differ
  per layer (e.g. pop in, fade out) since each phase independently picks
  which style function to run.
- Export-time resolution is hoisted OUTSIDE the per-target loop in
  `VideoExporter` (`val resolvedOverlays = engine.currentOverlays` computed
  once, reused for every dual-aspect target) — `currentOverlays` is a
  computed property that re-walks every layer on each read, unlike
  `currentAngles`/etc. which are plain stored fields, so reading it inside
  the target loop would silently redo that walk once per target. Same
  "resolve once per frame regardless of target count" principle dual-aspect
  export already established for pose/camera/scene.
- Phase-1 scope cuts, deliberate: no physics, no particles, no
  groups/parenting, no per-property color keyframing beyond the static
  enter/exit opacity fade, no custom-cubic-bezier easing (the reference
  tool's `back` easing was ported as a fixed-constant formula, not a
  general bezier solver). These are candidates for a later phase, not
  rejected outright — see "On the horizon" below.
- `Paint.setShadowLayer`/`BlurMaskFilter`-based glow reasoned through as
  safe on both render paths (`VideoExporter`'s plain `Canvas(Bitmap)` and
  `AnimationSurfaceView`'s default `SurfaceHolder.lockCanvas()`, neither of
  which is the hardware-accelerated `lockHardwareCanvas()` path where
  these APIs are known to misbehave) — but this reasoning has NOT been
  confirmed on an actual device yet. Flagged as the highest-risk unverified
  part of this feature, same category as the background-music PCM mixing
  pipeline was when it first shipped.

## AI drives the pipeline — the app doesn't second-guess it

Camera motion, scene colors/shapes, and captions are all purely
JSON-driven from the AI-generated script. The app does not derive any of
these automatically from audio amplitude or apply its own pacing logic
on top. This is the same reasoning that led to rejecting a tempo
multiplier (below): the AI already handles pacing and framing decisions
when generating the script, and the renderer's job is to execute that
faithfully, not to add its own opinions about e.g. "loud parts should
zoom in."

## On the horizon (not yet started)

- Motion graphics overlay layers, Phase 2: physics (analytic
  gravity/bounce solver), deterministic particles, groups/parenting with
  compounded transforms, trails/arrows.
- Motion graphics overlay layers, Phase 3: full in-app editor UI mirroring
  the reference tool (timeline scrubber, layers panel, properties panel,
  script examples) — NOTE: the existing "Full visual timeline editor"
  deferral below (drag/long-press cut for lack of on-device gesture
  testing) applies here too and should be re-read before starting this.
- Low-res preview before full export
- Amplitude-reactive background motion (separate from the purely
  JSON-driven scene system above — this would be an opt-in additional
  layer, not a replacement)
- Procedural animated shapes with figure-shape interaction
- Auto-highlight reel generation
- Character variants
- A bundled built-in sound effect library — see the correction note
  below; this is more tractable than originally assessed and just hasn't
  been done yet, not blocked.

## Deferred, with rationale

- **Surface-input export (OpenGL ES / `createInputSurface()`)** —
  confirmed to need a full OpenGL ES rendering rewrite, not a light
  retarget of the existing `lockHardwareCanvas()` path. Deferred until
  there's a measured export-time baseline to justify the rewrite's cost
  against — the current bulk-`getPixels()` fix already resolved the
  worst (multi-hour) case.
- **Full visual timeline editor** — scoped down to tap-to-seek only
  (`EventTimelineStrip`). Drag-to-reposition, long-press-to-delete, and
  tap-to-add were all originally scoped but explicitly cut: this
  environment has no way to compile-check or visually test Compose
  gesture code, and untested drag/long-press handling risks shipping
  something that looks correct in source but has a real on-device bug
  (gesture conflicts with parent scroll, wrong hit-test math, a drag
  threshold that never fires) that only surfaces on an actual build. The
  JSON text field remains the actual edit mechanism; the timeline strip
  is a navigation aid on top of it, not a replacement.

## Explicitly rejected

- **Tempo multiplier** — rejected as unnecessary given the AI already
  generates timestamped JSON with its own pacing; a manual multiplier
  would just let the app fight the AI's own timing decisions.
- **Silhouette mode** — redundant with the existing figure-color picker;
  a silhouette is just a figure color with implicit uniform fill, which
  the color picker already achieves without a separate mode/flag.

## Key architectural facts

- `BakedKeyframe` and `CaptionCue` are both defined inside
  `TimelineCompiler.kt`, not separate files.
- `MouthShape` constants are defined inside `AmplitudeAnalyzer.kt`.
- `SceneShape` and `SceneAtmosphere` are separate objects in
  `engine/Scene.kt`, each with a `fromString()` that falls back to
  `NONE` for unrecognized input rather than throwing — a bad
  AI-generated value should degrade gracefully, not crash the render.
- Colors are ARGB `Long` (`0xAARRGGBB`) throughout `AppearanceSettings`
  and the new scene/overlay fields, for consistent JSON round-tripping.
  Where a raw hex literal above `0x7FFFFFFF` is needed as a `Paint`
  color (`Int`), it must be written with an explicit `L` suffix and
  `.toInt()` — Kotlin (unlike Java) does not implicitly widen an
  out-of-Int-range hex literal to fit; this caught several would-be
  compile errors during the scene/atmosphere rendering work.

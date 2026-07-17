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
  pursued this session because the sandbox's network allowlist doesn't
  include a general asset host to fetch real audio files from (only dev-
  tooling domains like github.com/pypi.org/npmjs.com are reachable). This
  is a factual constraint of the current session, not a design rejection
  — a bundled library remains a reasonable future addition if assets are
  sourced through a channel that's actually reachable, and the two
  approaches aren't mutually exclusive (bundled clips could simply be a
  richer default library alongside user imports).
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

- Dual-aspect-ratio export in a single pass
- Low-res preview before full export
- Amplitude-reactive background motion (separate from the purely
  JSON-driven scene system above — this would be an opt-in additional
  layer, not a replacement)
- Procedural animated shapes with figure-shape interaction
- Auto-highlight reel generation
- Character variants
- A bundled built-in sound effect library (see "Sound effects" above for
  why this wasn't pursued alongside the user-import mechanism this session)

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

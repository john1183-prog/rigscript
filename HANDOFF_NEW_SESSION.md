# RigScript — Handoff for new session

## ⚠️ Read this first — mandatory before anything else

**Verify repo state before trusting any claim in this document.**

```
git clone https://github.com/john1183-prog/rigscript.git && cd rigscript
git log --oneline -10
```

Expected HEAD: `4ec852a Fix portrait DEMO caption overlap`

If it matches, read `V2_DECISIONS.md` in full from the "GLES export rewrite"
entry onward before touching anything. If it doesn't match, this document
describes commits that never landed and you're starting from a different
state — treat everything below as unreliable until confirmed.

---

## Project in one sentence

**RigScript**: an Android app (Kotlin/Jetpack Compose, minSdk 26) that
renders 2D stick-figure animations from AI-authored JSON scripts and exports
H.264/MP4 video on-device. Core vision: "SRT and prompt in, full
professional polished video out." Workflow: John uses ChatGPT for architecture,
design distillation, and prompt engineering; Gemini (Antigravity) implements,
verifies strictly against the codebase with Python/ffmpeg/device tests, and
maintains codebase hygiene. Visual verification happens by inspecting test exports
on device and running `ffmpeg` + PIL pixel analysis on uploaded MP4 videos.

**MANDATORY RULE**: **No push without explicit authorization.** Never push to
remote branches without explicit user confirmation.

---

## Current repo state (verified on device, all 4 test renders)

**HEAD: `4ec852a`** — everything below is confirmed pushed to `origin/main`.

### What was confirmed working & verified (pixel-measured, not eyeballed)

Verified across Canvas portrait (9:16), Canvas landscape (16:9), GLES
smoke test portrait (9:16), and GLES smoke test landscape (16:9):

- **Eye rotation & placement**: both eyes render as proper ovals under head
  rotation (verified via lazy-pose frame). Eye center lands at ~51-52% down the
  head across all renders, well clear of the mouth.
- **Mouth position**: anchor 0.24 lands mouth center at ~76-77% down the head,
  separated from both eyes and chin.
- **Walk cycle hip-bob**: confirmed vertical shift (26-27px) active on walk beats.
- **Atmospheres**: snow and stars render cleanly as distinct procedural particles.
- **Room / beach silhouettes**: positioned safely off the central standing zone.
- **GLES mountain geometry & gradient parity (`6a396a1`)**: mountain peak
  interpolation and background gradient draw parity unified between GLES and Canvas.
- **Aspect-aware figure framing (`3fddab3`)**: figure scale dimension
  normalized across orientations via `figureScaleDim = if (w < h) w.toFloat() else h * (9f / 16f)`
  (anchored to 9:16 portrait reference), preventing figures from over-filling
  landscape frames.
- **GLES secondary figure rendering (`3fddab3`)**: implemented `"figure"`
  overlay type in `GlesFigureFrame.kt` / `GlesFrameRenderer.kt` using
  `OverlayDrawCommand.Oval` and FK matrix walk matching Canvas.
- **DEMO ground-line persistence fix (`d04c069`)**: added `showGroundLine = false`
  at the `t = 1.5s` scene transition in `AnimScript.DEMO`. Pixel-verified:
  ground line renders at Y=1049 (9:16) / Y=590 (16:9) in city scene, and is
  completely absent (zero cyan pixels) across all subsequent scenes (mountains,
  beach, room) in both Canvas and GLES.
- **Portrait DEMO caption clearance (`4ec852a`)**: reduced font size from
  `0.07f` to `0.045f` on `caption_all_left` ("SIDE") and `caption_all_right` ("EDGE")
  in `AnimScript.DEMO`. Verified on physical device:
  - Left clearance gap to figure: 71 px (9.86% canvas width).
  - Right clearance gap to figure: 55 px (7.64% canvas width).
  - Zero collision with the central stick figure.
- **Canvas vs GLES parity**: face positioning, scene shapes, atmospheres, and
  captions pixel-match between Canvas and GLES export paths.

---

## Completed fixes (previously open issues now closed)

1. **GLES secondary figure rendering**: CLOSED (`3fddab3`).
2. **Camera zoom / figure scaling across orientations**: CLOSED (`3fddab3`).
3. **DEMO ground line carry-forward persistence**: CLOSED (`d04c069`).
4. **Portrait DEMO caption overlap**: CLOSED (`4ec852a`).

---

## Remaining work (priority order)

### 1. Five-step overlay-text work [PLANNED, NOT YET IMPLEMENTED]
A planned expansion to overlay text capabilities and caption styling.
**None of this is implemented yet — do not write code or prompt for these until active:**
- **Step 1: Caption clamping + AppearanceSettings wiring**: dynamic max lines,
  box padding, and appearance overrides for subtitles.
- **Step 2: Multiline overlay text**: `\n` line splitting, multi-line measurement,
  and bounding box alignment.
- **Step 3: `screenSpace` coordinate mode**: optional overlay flag to render in
  viewport space rather than camera world space.
- **Step 4: Overlay `anim` keyframes**: timeline keyframing for overlay properties
  (position, scale, opacity, rotation).
- **Step 5: Documentation & prompt updates**: sync schemas and prompt text once
  the underlying engine code is shipped and verified.

**Explicit out-of-scope follow-ups from the overlay plan**:
- Motion-path curves (e.g. bezier trajectories)
- Per-character / per-word animation (karaoke reveals)
- Layer blend-modes or masking
- Continuous particle emitters (burst-only remains the model)
- Timeline editor UI

### 2. Wire GLES into the real `export()` button [primary architecture goal]
- Add an opt-in toggle (project setting or AppearanceSettings flag).
- Wire GLES pipeline into `VideoExporter.export()` for selected projects.
- Add an automatic fallback path to Canvas if GLES initialization fails on device.

### 3. `jog_a`/`jog_b` foot-plant correction [deferred by design]
The hip-bob technique that works for walk_a/walk_b was tried on jog and
rejected: jog's mid-blend passes through an unrealistic double-support-like
configuration that needs the hip to swing ~196px (worse than the bug).
Real fix requires a mid-stride keyframe (pose-authoring work), not this
technique applied more broadly. Not started, not blocked, just deferred.

### 4. Build & verification status
Local builds (`gradlew assembleDebug`) and device deployment (`adb install -r -d`)
work cleanly. Verification is anchored by pixel analysis of exported MP4s via
Python and `ffmpeg`. CI via GitHub Actions remains available on remote push.

### 5. `sit` pose arms [minor polish, not broken]
The `sit` pose keeps its arms in the default standing-hang position rather
than resting near the knees. Noted during the pose audit as a polish
opportunity, not a correctness bug. Not changed.

---

## Key architectural facts (read before touching rendering code)

- **Shared compute functions**: `RigRenderer` contains static methods
  (`computeFkMatrices`, `computeSnowFlakes`, `computeStarPositions`,
  `computeRainDrops`, `computeRoomFurniture`, `computeBeachElements`,
  etc.) called by both Canvas and GLES paths. Logic parity between paths
  is maintained by sharing these functions, not duplicating them.
- **GlesFigureFrame**: the GLES equivalent of a single rendered frame,
  built by `fromFkMatrices()` and drawn by `GlesFrameRenderer`. The
  smoke test path calls these directly; the real `export()` does not yet.
- **PlaybackEngine**: single-threaded animation state machine.
  `currentHipBobOffset` is applied to `rootY` at all render call sites
  (Canvas export, GLES smoke test, live preview).
- **TimelineCompiler/BakedKeyframe**: `fromPoseId`/`toPoseId` thread pose
  identity through, enabling the hip-bob gate (`{walk_a, walk_b}` set equality).
- **OverlayLayer** (the AI script overlay system) is distinct from
  **ReferenceOverlay** (the user-configured manual overlay). They are
  completely separate data structures with completely separate render paths.
- **Scene.kt**: the canonical source for valid `sceneShape` and
  `sceneAtmosphere` string constants. `system_prompt.txt` and
  `PROMPT_CONSIDERATIONS.md` must be kept byte-identical when these are
  updated.

---

## Session conventions (load-bearing, do not skip)

1. **Verify repo state before acting**: check `git log` and `git status`.
2. **Preserve ChatGPT → Gemini workflow**: John plans/prompts with ChatGPT;
   Gemini implements, verifies, and maintains codebase integrity.
3. **MANDATORY**: **No push without explicit user authorization.**
4. **Read `V2_DECISIONS.md` and `PROMPT_CONSIDERATIONS.md` in full**
   before implementing anything in a new session.
5. **Verify math in Python before writing Kotlin** — catch logic and scale
   flaws before shipping.
6. **Full diff review before every commit** — check exact changes and whitespace.
7. **Separate doc-only commits**: `V2_DECISIONS.md` updates committed
   separately after code commits.
8. **Detailed, honest commit messages**: flag "NOT verified: compiler or
   device" explicitly when checks didn't happen.
9. **`PROMPT_CONSIDERATIONS.md` must stay byte-identical to
   `system_prompt.txt`** at every line that appears in both — verify with
   `diff` after editing either.
10. **Never document planned/unshipped features as existing**.

---

## Communication style

John is extremely terse — single sentences, fragments. Corrections are the
primary signal something is wrong; silence/brief affirmatives are approval.
Resolve ambiguity by checking the actual codebase, not by asking clarifying
questions. Plan before coding on non-trivial work; get sign-off before
implementing. Static code reading has real limits — pixel-level video frame
comparison catches bugs that source review misses.

---

## Demo script state (`AnimScript.DEMO`)

Current structure (~114s total, stressCycle repeat count = 1 per batch):
- **0.0-24.0s**: hand-authored feature content (city+rain with `showGroundLine = true`,
  transitioning at 1.5s to mountains+snow with `showGroundLine = false`,
  walk cycle, glow overlays, camera moves, expressions).
- **25.0-56.0s**: stressCycle batch 1 (1 repeat = 32s of generated poses).
- **55.0s**: lazy pose (unoccluded rotation check).
- **58.0s**: gradient+camera combo test.
- **64.0s**: room scene.
- **67.0s**: beach+stars scene.
- **71-82s**: caption position tests (LEFT, RIGHT, all-four simultaneously
  with `caption_all_left` & `caption_all_right` at `fontSize = 0.045f`).
- **84.0-116.0s**: stressCycle batch 2 (1 repeat = 32s).
  - **92-96s**: shape+gradient+glow overlay reprise.

**To restore the full stress test**: change both `stressCycle(_, 1)` and
both `stressCycleBlinks(_, 1)` calls to count=7. Also retime the two
inter-batch overlays (`combo_glow_zoom_test` and `shape_glow_reprise`).

---

*This document was written at the end of a long session and should be
treated with the same skepticism as any handoff doc — verify `git log`
before trusting any specific claim above.*

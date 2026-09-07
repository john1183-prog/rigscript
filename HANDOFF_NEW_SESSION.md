# RigScript — Handoff for new session

## ⚠️ Read this first — mandatory before anything else

**Verify repo state before trusting any claim in this document.**

```
git clone https://github.com/john1183-prog/rigscript.git && cd rigscript
git log --oneline -10
```

Expected HEAD: `18f01fe Record pose audit + caption-position test decisions`

If it matches, read `V2_DECISIONS.md` in full from the "GLES export rewrite"
entry onward before touching anything. If it doesn't match, this document
describes commits that never landed and you're starting from a different
state — treat everything below as unreliable until confirmed.

---

## Project in one sentence

**RigScript**: an Android app (Kotlin/Jetpack Compose, minSdk 26) that
renders 2D stick-figure animations from AI-authored JSON scripts and exports
H.264/MP4 video on-device. Core vision: "SRT and prompt in, full
professional polished video out." All builds happen via GitHub Actions
(no local compiler). John builds from his phone using Termux for git. No
connected device during sessions — visual verification happens by uploading
exported videos and running `ffmpeg` + PIL pixel analysis on them.

---

## Current repo state (verified on device, all 4 files)

**HEAD: `18f01fe`** — everything below is confirmed pushed to `origin/main`.

### What was confirmed working this session (pixel-measured, not eyeballed)

All four renders: Canvas portrait (9:16), Canvas landscape (16:9),
GLES portrait, GLES landscape — from `new_test_9x16_1788738892995.mp4`,
`new_test_16x9_1788738893524.mp4`, `new_test_gles_test_1788739601841.mp4`,
`new_test_gles_test_1788740506300.mp4`.

- **Eye rotation fix**: both eyes render as proper ovals under real head
  rotation in all 4 renders — confirmed via 2-cluster count on the lazy-pose
  frame (lazy has torso 22°/head -14° rotation with arms at only 28°, no arm
  occlusion ambiguity). Pixel-verified: 2 distinct eye clusters in cp/gp/cl/gl.
- **Eyes position** (`eyeVerticalOffsetNormalized -0.03`): eye center lands at
  ~51-52% down the head across all 4 renders, well clear of the mouth.
- **Mouth position** (anchor 0.24): mouth center at ~76-77% down the head,
  both eye-clear and clearly separated from the chin.
- **Walk cycle hip-bob**: head Y 536→510px (Canvas portrait), 536→509px (GLES
  portrait) — confirmed 26-27px vertical shift, not dead-still.
- **Snow/stars**: visible as distinct dots, not pixel specks.
- **Room/beach**: both render correctly; furniture/umbrellas no longer
  hidden behind the character (repositioned from `[0.08,0.42,0.62]` to
  `[0.03,0.22,0.72]` for room; explicit positions `[0.10,0.25,0.85]` for
  beach).
- **Caption positions**: LEFT, RIGHT, and all-four-simultaneously (TOP/
  BOTTOM/SIDE/EDGE) all visible and correctly placed in all 4 renders.
- **Canvas portrait vs GLES portrait**: face positioning, scene shapes,
  atmospheres, captions all pixel-match between the two paths. Scene shapes
  correctly scale across portrait/landscape aspect ratios.

### One confirmed bug found this session

**GLES does not render `"figure"`-type overlays (secondary figures).**

Confirmed via pixel comparison: at t=5s, Canvas portrait shows a small
orange secondary figure; GLES portrait shows nothing there. Root cause is
known and already documented in `GlesFigureFrame.kt`'s own class doc comment
(line ~44): `"figure"` overlay type falls through to `null` at line ~803.
The Canvas implementation lives in `RigRenderer.drawSecondaryFigure()` (~line
769). The GLES path needs the equivalent: a full FK matrix walk (same
structure as the main figure's own bone loop in `fromFkMatrices`) sized to
`minDim * 0.3f`, plus `drawSecondaryFace()` equivalent. This is nontrivial
but well-bounded — no new geometry primitives needed, just reusing the
existing line/circle draw commands.

---

## Remaining work (priority order)

### 1. GLES secondary-figure rendering [just confirmed missing]
Implement `"figure"` overlay type in `GlesFigureFrame.fromFkMatrices()`.
Reference: `RigRenderer.drawSecondaryFigure()` (line ~769) for the exact
FK walk and sizing. The pose angles are in `layer.figurePoseAngles`. Uses
only line + circle draw commands already supported by GLES.

### 2. Wire GLES into the real `export()` button [primary architecture goal]
This is what every session has been building toward. Steps needed:
- Add an opt-in toggle (project setting or AppearanceSettings flag)
- Replace the Canvas render path in `VideoExporter.export()` with the GLES
  pipeline for projects where GLES is selected
- Add a fallback path for devices where GLES init fails — this was explicitly
  flagged as required before GLES export ships, not optional

**Important**: both the GLES smoke test path and the Canvas export path
currently pass `referenceOverlay`/`referenceOverlayBitmap` correctly to their
respective renderers. The smoke test was confirmed working for all features
except the secondary-figure type above. The Canvas path has been the real
production export path all session; everything confirmed this session went
through the GLES smoke test, not the real export button.

### 3. `jog_a`/`jog_b` foot-plant correction [deferred by design]
The hip-bob technique that works for walk_a/walk_b was tried on jog and
rejected: jog's mid-blend passes through an unrealistic double-support-like
configuration that needs the hip to swing ~196px (worse than the bug).
Real fix requires a mid-stride keyframe (pose-authoring work), not this
technique applied more broadly. Not started, not blocked, just deferred.

### 4. CI verification [standing gap all session]
GitHub Actions CI never came back online during this session — rate-limited
on the shared sandbox IP pool across every attempt. None of this session's
commits have been verified against an actual Kotlin compiler. Check the
Actions tab on GitHub directly. Commits are flagged "NOT verified: compiler"
in their commit messages where this applies.

### 5. `sit` pose arms [minor polish, not broken]
The `sit` pose keeps its arms in the default standing-hang position rather
than resting near the knees. Noted during the pose audit as a polish
opportunity, not a correctness bug. Not changed.

### 6. Real Canvas export confirmation [still technically open]
Everything confirmed this session was the GLES smoke test (filename suffix
`_gles_test`). The Canvas export button was confirmed working much earlier,
but never re-run since then. Given that GLES is the goal and Canvas is
becoming the fallback, this is low priority — but one real Canvas export
pass (not the smoke test) would formally close it out.

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
  `currentHipBobOffset` (added this session) is applied to `rootY` at all
  3 render call sites (Canvas export, GLES smoke test, live preview).
- **TimelineCompiler/BakedKeyframe**: `fromPoseId`/`toPoseId` were added
  this session to thread pose identity through, enabling the hip-bob
  gate (`{walk_a, walk_b}` set equality). If you add new fields to
  `BakedKeyframe`, there is exactly one construction site
  (`TimelineCompiler.kt` line ~281).
- **OverlayLayer** (the AI script overlay system) is distinct from
  **ReferenceOverlay** (the user-configured manual overlay). They are
  completely separate data structures with completely separate render paths.
- **Scene.kt**: the canonical source for valid `sceneShape` and
  `sceneAtmosphere` string constants. `system_prompt.txt` and
  `PROMPT_CONSIDERATIONS.md` must be kept byte-identical when these are
  updated — both were updated this session (room/beach added).

---

## Session conventions (load-bearing, do not skip)

1. **Clone fresh** every session: `git clone ... && cd rigscript`
2. **Verify `git log` before acting** — handoff docs have been wrong before
3. **Read `V2_DECISIONS.md` and `PROMPT_CONSIDERATIONS.md` in full**
   before implementing anything in a new session
4. **Verify math in Python before writing Kotlin** — this caught the
   walk-cycle lerp-offset flaw and the mouth-overcorrection before they
   shipped
5. **Full diff review before every commit** — brace/paren balance check
   is a standard pre-commit step
6. **Separate doc-only commits**: `V2_DECISIONS.md` updates committed
   separately after each code commit
7. **Detailed, honest commit messages**: flag "NOT verified: compiler or
   device" explicitly when those checks didn't happen
8. **`PROMPT_CONSIDERATIONS.md` must stay byte-identical to
   `system_prompt.txt`** at every line that appears in both — verify with
   `diff` after editing either
9. **Push tokens are provided fresh each session** — never expect one to
   persist. Use exactly one `git push https://john1183-prog:<token>@...`
   command, token in URL only, never echo it.

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
- **0.0-24.0s**: hand-authored feature content (city+rain, mountains+snow,
  walk cycle, glow overlays, camera moves, expressions)
- **25.0-56.0s**: stressCycle batch 1 (1 repeat = 32s of generated poses)
- **55.0s**: lazy pose (unoccluded rotation check)
- **58.0s**: gradient+camera combo test
- **64.0s**: room scene
- **67.0s**: beach+stars scene
- **71-82s**: caption position tests (LEFT, RIGHT, all-four simultaneously)
- **84.0-116.0s**: stressCycle batch 2 (1 repeat = 32s)
  - **92-96s**: shape+gradient+glow overlay reprise

**To restore the full stress test**: change both `stressCycle(_, 1)` and
both `stressCycleBlinks(_, 1)` calls to count=7. Also retime the two
inter-batch overlays (`combo_glow_zoom_test` and `shape_glow_reprise`).

---

*This document was written at the end of a long session and should be
treated with the same skepticism as any handoff doc — verify `git log`
before trusting any specific claim above.*

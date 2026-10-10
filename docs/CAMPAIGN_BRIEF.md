# RigScript V2 Campaign — Autonomous Implementation Brief

Prepared 2026-10-09 by a planning chat with John (owner of github.com/john1183-prog/rigscript) for the Claude session(s) that will implement it.

**Revision 2 (2026-10-09), John's decisions:** (1) GLES becomes the default only if it works flawlessly, implemented as `AUTO` with an on-device check; (2) pushing to `main` is allowed and the other remote branches are removed; (3) audio-reactive visuals are included (AI opt-in; overrides an earlier rejection); (4) acting defaults to `NORMAL`; (5) clarifying questions are allowed when absolutely necessary.

Labels used below:
- **VERIFIED** = the planner checked it in a fresh clone at commit `eb6b0b3` (origin/main on 2026-10-09).
- **REPORTED** = claimed by another analysis; not checked. Re-verify before acting.
- Everything else is a proposal. You may improve it if you record why in `V2_DECISIONS.md`.

---

# PART A — OPERATING RULES

## A0. How this works

1. You are the implementer **and** the verifier. John tests only after the campaign (or after a session ends). He works from a phone (Termux + GitHub Actions) and is usually unavailable mid-run, so decide, document, continue.
2. **Clarifying questions are allowed, but only when absolutely necessary.** First exhaust the repo, `V2_DECISIONS.md`, this brief and the CI results. Ask only if (a) the brief contradicts itself or the code, or the answer genuinely cannot be found, **and** (b) a wrong guess would be costly, destructive or hard to reverse. Never ask about style, naming, thresholds, UI layout or priorities; this brief decides those, and where it is silent you choose the conservative option and log it.
   When you do ask:
   - batch every open question into **one** message;
   - give each a recommended default, the reason, and the consequence of the alternative, phrased so John can answer in a word ("A" or "B");
   - finish and push all unblocked work first;
   - record each question and its default in `IMPLEMENTATION_LOG.md` under **OPEN QUESTIONS**. If John has not answered when you next resume, proceed with the recommended default and log that you did.
   Also stop and ask for: a missing GitHub token (A7), and anything destructive or irreversible that A7 does not already authorize.
3. This is multi-session by design. No single chat will finish all of it. Follow A9 so a fresh chat can resume from the repo alone.
4. If this brief conflicts with the "ChatGPT → Gemini pairing" workflow text in `GEMINI.md`, this brief wins (you are the implementer). Every other `GEMINI.md` rule still binds you; they are restated in A2–A7.

## A1. Mission

RigScript is an offline Android app (Kotlin, Jetpack Compose, minSdk 26) that turns narration audio plus an AI-written JSON script into a stick-figure animated video. The AI step is **external**: John pastes a prompt and a timed transcript into a chat AI, then pastes the returned JSON into the app.

This campaign makes four things true, in this order:

1. **Export is reliable and fast.** GLES becomes a real export backend (the default, if it proves itself on the device), and every audio source (narration, music, sound effects) reaches the file on both backends.
2. **Characters act.** Expressive motion, faces and speech, instead of a robot swapping poses with a flapping mouth.
3. **Motion graphics and kinetic text are strong and easy for an AI to author.**
4. **The loop around the external AI is smooth** (prompt pack, repair prompt, review sheet, patch apply), with the engine carrying the craft so prompts get shorter, not longer.

Throughout: deterministic, identical in preview and export, old scripts unchanged.

## A2. John's stated constraints (never violate)

- No local LLM. No in-app cloud AI call. The AI stays outside; JSON is pasted in.
- The app stays offline and on-device.
- Finish before expand: Phase 1 (export) is complete and CI-green before any Phase 2+ engine or schema work begins.
- Commits and pushes use John's identity: `john1183-prog <john1183-prog@users.noreply.github.com>`. Never a Claude or bot identity. No `Co-authored-by` trailers.
- Honest status: nothing is "device-verified" until John says so.
- Build everything first; John tests afterwards.
- Ambition is high ("revolutionary"), but never at the cost of preview = export parity or of old scripts.

**Decisions John added on 2026-10-09:**
- GLES becomes the default renderer **if it works flawlessly**. A chat cannot prove that, so it is implemented as `AUTO` with an on-device eligibility check (WP1.3, WP1.6).
- Pushing to `main` is allowed (A7). Remove the other remote branches (WP0.1b).
- Audio-reactive visuals are wanted: include them as an AI opt-in (WP3.7). This overrides the earlier rejection in `V2_DECISIONS.md`.
- Acting defaults to `NORMAL` (confirmed).
- Clarifying questions are allowed when absolutely necessary (A0).

## A3. Invariants (any violation is a bug)

- **I1 Backward compatibility.** Every script valid at `eb6b0b3` renders the same with new features unused or off. New fields are optional with legacy-preserving defaults. Exceptions are allowed only for behaviours introduced in the last ~30 days (`screenSpace`, `anim`, multi-line overlay text) and only with a `V2_DECISIONS.md` entry.
- **I2 Determinism and seek-independence.** Engine output at time `t` is a pure function of (script, audio envelope, settings, `t`). Pseudo-randomness is seeded from stable ids and time buckets. Sequential playback at `t` must equal `seek(t)` within 1e-4 (angles); add a test.
- **I3 One source of truth.** New behaviour is computed in the shared engine layer (`PlaybackEngine`, `TimelineCompiler`, `OverlayResolver`, new `engine/` packages) and handed to renderers as **resolved state**. `RigRenderer` (Canvas) and `Gles*` only draw. No logic forks between renderers; share pure functions for layout and geometry.
- **I4 Closed vocabulary, graceful degradation.** Unknown values degrade (ignored or defaulted) with validator warnings. Never crash. Never invent values.
- **I5 Schema discipline (GEMINI.md).** Never describe planned or unmerged features as fields in AI prompts. `app/src/main/assets/prompt/system_prompt.txt` and the prompt block in `PROMPT_CONSIDERATIONS.md` stay byte-identical (verify with `cmp` after extracting the block). The prompt may lag shipped code (WP4.4 documents the new fields in one pass) but must never lead it. `V2_DECISIONS.md` changes in the same commit as the code.
- **I6 AI drives the pipeline (V2_DECISIONS.md).** Apply the Craft-vs-Direction rule in A4.
- **I7 Performance.** No per-frame heap churn in resolve/draw hot paths. Long exports (8+ minutes) must not regress. Keep the streaming audio mix and the thermal safety net.
- **I8 Data safety.** Never use `fallbackToDestructiveMigration` (the repo forbids it; users have real projects). Avoid Room schema changes: put new settings into the existing JSON-serialized settings classes (`AppJson.storage` uses `ignoreUnknownKeys = true`, `encodeDefaults = true`). If a migration is unavoidable, write a proper `Migration` and document it.
- **I9 Hands off.** `app/debug.keystore` and its signing config. Do not stage the untracked files GEMINI.md lists (`.gitignore`, `.kotlin/`, `gradle/wrapper/`, `gradlew`, `gradlew.bat`). No history rewrites.
- **I10 Kotlin gotcha from this repo.** ARGB hex literals above `0x7FFFFFFF` used as a `Paint` color need an `L` suffix plus `.toInt()`.
- **I11 Fail-soft.** Every new engine layer (acting, behaviors, macros, patch apply, diagnostics) is wrapped so an exception falls back to the base state, logs once, and never crashes playback or export.
- **I12 Launch safety.** John cannot hot-fix a crash-on-launch. New settings must have defaults; legacy stored JSON must decode (write tests with JSON captured *before* you add fields); review the startup path and every screen you touch before each phase tag.

## A4. Craft vs Direction (how to apply "AI drives the pipeline")

`V2_DECISIONS.md` says the app must not make creative choices on top of the script (it rejected a tempo multiplier, silhouette mode, amplitude-reactive background motion and an auto highlight reel). Classify every new feature with this rule and record the classification in `V2_DECISIONS.md`:

- **DIRECTION** = what happens and when: poses, expressions, gestures, look targets, camera, scene, captions, overlays, emotes, transitions, and which layers react to audio. Only the AI-authored script may create Direction. Never auto-generate Direction from audio.
- **CRAFT** = how Direction is executed: easing, overlap, overshoot, blending, blinks, eye micro-movement, mouth smoothing. The engine owns Craft, deterministically, behind an intensity switch.

If a feature adds visible content the script did not ask for, it is Direction: make it an AI opt-in field, or default it off.

**John's override on audio-reactive visuals (2026-10-09):** he wants them. The earlier rejection was about the app deciding on its own that loud parts should move things. Implement audio-reactive visuals (WP3.7) as Direction the AI opts into per layer, evaluated deterministically from a stored envelope; the app never adds reactivity by itself. In `V2_DECISIONS.md`, annotate the old rejection as overridden (do not delete it) and amend the "AI drives the pipeline" paragraph.

**Explicit non-goals for this campaign:** local or in-app LLM; any cloud API call from the app; tempo multiplier; silhouette mode; auto highlight reel; desktop or web port; TTS or voice cloning; story packs; p5.js-style generated code; masks and blend modes; painterly rendering; implementing a co-equal second speaker (design note only, WP5.3).

## A5. Honesty protocol

- Label every status claim: `VERIFIED-BY-CI (run id)`, `VERIFIED-BY-TEST (test name)`, `VERIFIED-BY-READING (file:function)`, or `UNVERIFIED`. Nothing is "device-verified" in this campaign.
- Every commit message ends with a footer:
  ```
  Verification: CI <run id|pending>; tests <names|none>; device: NOT TESTED
  ```
- Never fabricate command output. If a command fails or a tool is missing, say so in the log.
- Claims from earlier chats (including the REPORTED list in B2 and the review items in B3) are leads, not facts. Re-verify against HEAD; record `CONFIRMED`, `ALREADY FIXED` or `NOT REPRODUCIBLE`.
- Handoff docs are not ground truth. `HANDOFF_NEW_SESSION.md` was stale at `eb6b0b3` (VERIFIED).

## A6. Bootstrap (Step 0, every session)

1. **Detect capabilities:** `git --version; java -version; which gradle sdkmanager python3 ffmpeg ffprobe`; probe `curl -sI https://github.com | head -1`, `https://dl.google.com`, `https://repo.maven.apache.org`. A chat sandbox usually has no Android SDK or Maven access. In that case **CI is your compiler and test runner**. Never claim you compiled locally unless you did.
2. **Clone fresh:** `git clone https://github.com/john1183-prog/rigscript.git`. Run `git log --oneline -15`. First session: expect `main` at `eb6b0b3`. Record the actual HEAD.
3. **Read, in this order:**
   1. `GEMINI.md`.
   2. `V2_DECISIONS.md` (about 187 KB; do **not** read it all). Read `## History` (~line 11), `## On the horizon`, `## Deferred, with rationale`, `## Explicitly rejected`, `## Key architectural facts`, the last ~150 lines of `## What's implemented`, and `grep -n` any topic you touch.
   3. `PROMPT_CONSIDERATIONS.md` headings, plus the per-field sections for fields you touch.
   4. `IMPLEMENTATION_LOG.md` if it exists (resume).
   5. This brief.
4. **First session only:** create and push the annotated tag `pre-v2-baseline` at the starting `main` commit (rollback point); copy this brief to `docs/CAMPAIGN_BRIEF.md`; create `IMPLEMENTATION_LOG.md` (with **RESUME HERE** as the Handoff Packet of A9, **OPEN QUESTIONS** and a WP table) and a short `CLAUDE.md` (pointer to the GEMINI.md rules, the brief and the log); run WP0.1b; commit and push to `main` (A7).
5. **Resuming a session:** read `RESUME HERE` first. Treat it as a claim, not as truth (A5): verify the last green sha, the tags and the CI run against `git log`, `git tag` and the Actions API before acting. If it disagrees with the repo, trust the repo and record the discrepancy. If John pasted a Handoff Packet in the first message, compare it with the repo copy. If `docs/CAMPAIGN_BRIEF.md` is missing from the repo, ask John to attach the brief again (an allowed question).

## A7. Git, CI, push, token

**Authorization (explicit, from John; this satisfies GEMINI.md's rule against pushing without authorization):**
- You may push to `main` directly, **fast-forward only**: never force-push, never rewrite history.
- You may delete the other remote branches (procedure in WP0.1b). You may create and push tags. Never delete tags.
- Before every push run `git fetch`. If `origin/main` has moved (John also pushes from his phone), rebase your commits onto it; on conflicts in files you did not touch, keep John's version; re-run the relevant checks before pushing.

**Working on `main` safely:** every push must leave `main` green and working for old projects (defaults preserve legacy behaviour, I1). Tag `pre-v2-baseline` at the start, and tag the last green commit of each phase with an annotated, pushed tag (`v2-p0`, `v2-p1`, …, `v2-final`) so John can roll back to any of them.

**Identity:** repo-local `git config user.name "john1183-prog"` and `git config user.email "john1183-prog@users.noreply.github.com"`.

**Token:** John pastes a GitHub token in his first message (fine-grained: this repo only; Contents read/write, Actions read, Workflows read/write, Metadata read — or a classic token with `repo` + `workflow`). Use it only inside the push/API command (`Authorization: Bearer`), never write it to disk, logs or commit messages, never `set -x`, and reset the remote URL after pushing. If it leaks into output, tell John to revoke it.

**No token?** Commit locally. Create a bundle (`git bundle create /mnt/user-data/outputs/v2.bundle pre-v2-baseline..main`) and present it so John can fetch it in Termux. Keep working, but you lose CI feedback: say so, slow down, and make smaller edits.

**CI (VERIFIED):** `.github/workflows/build.yml` runs on **every push** (`on: [push]`), JDK 17, Gradle 8.9, `gradle assembleDebug`, and uploads the APK artifact `app-debug`. WP0.2 adds unit tests to it. The repo is public, so Actions minutes are not a concern.

**Reading CI:** `GET /repos/john1183-prog/rigscript/actions/runs?head_sha=<sha>`, then `/actions/runs/<id>/jobs`. Failing logs: `GET /actions/jobs/<job_id>/logs` (follow the redirect; the token is required). Unauthenticated API calls are limited to 60 per hour (the planner's own sandbox hit that limit), so always authenticate.

**Loop:** push → wait for the run → green? continue : read the failure, fix, push. Maximum 3 attempts on the same failure; then `git revert` (a new commit) or flag-disable the WP, log it as `BLOCKED` with the error excerpt, and move on. **`main` must never be left red at the end of a session.**

**Commit hygiene:** atomic commits per WP step. Run `git diff --check` and `git status` before each commit. Message = imperative title + body (what and why) + the A5 footer. Push after every WP at minimum (the log goes with it).

## A8. Engineering practices

- **Read before write.** Open the whole function you change. Use targeted edits, not whole-file rewrites, for `RigRenderer.kt` (~99 KB), `PlaybackEngine.kt` (~40 KB), `VideoExporter.kt` (~50 KB), `MainViewModel.kt` (~47 KB) and `AnimScript.kt` (~40 KB). Check `git diff --stat` for surprises. No mass reformatting, no unrelated cleanup.
- **Prefer new files** over growing the big ones. Keep each push small enough that a compile error is easy to localize (aim for under ~300 changed lines when touching renderers or the exporter). Before using a symbol, `grep` that it exists with the signature you assume.
- **Pure first.** Put new logic in pure Kotlin (no Android types) so JUnit can test it. Set `testOptions.unitTests.isReturnDefaultValues = true` for `android.util.Log` and similar.
- **Prototype math in Python first** (springs, overlap, IK, mouth smoothing, AA fringes). Plot with matplotlib and **look at the PNGs** (you can view images). Port to Kotlin. Assert Kotlin equals Python on sample values in tests. A small PIL reference drawer for the FK rig is encouraged for before/after motion strips: it validates motion shape, not final pixels. Keep prototypes under `tools/` (small, with a one-line README).
- **Tests:** JUnit 4 only; no heavy frameworks. Property tests over fixtures: determinism, seek equals sequential, identity-when-off, bounds (no NaN or infinity), monotone cue order.
- **Performance:** preallocate arrays in per-frame paths; avoid boxing and lambdas in hot loops; cap macro expansions (at most 256 resolved overlay elements per frame).
- **UI:** minimal Compose following existing patterns. No gesture-heavy UI (V2_DECISIONS explains why: it cannot be tested here).
- **minSdk 26:** guard newer APIs.
- **Tooling gotcha:** `cut -c`, `head -c` and other byte-based truncation can split multibyte characters (the repo's comments contain em dashes, ≥ and ×); the sandbox tool then fails with an encoding error and returns nothing. Slice text in Python (`line[:150]`) or do not truncate.
- **Tunables in one place:** all acting and macro constants live in clearly named parameter objects (e.g. `ActingParams`, `GlesAcceptance`) with comments, so John's next session can tune after testing.

## A9. Continuation, budget and the Handoff Packet

**Goal:** a session can end at any moment, and the next session loses nothing it needs.

**1. The Handoff Packet** is the top section of `IMPLEMENTATION_LOG.md`, titled `RESUME HERE`. Rewrite it (do not append) after every WP, after any red-CI incident, and at the end of the session; push it with the work. Required headings, each filled or marked "none":
1. **STATE:** last green commit sha on `main`; tags pushed; links to the latest CI run and APK run; test count; a WP status summary (done / in progress / blocked / not started).
2. **IN-FLIGHT:** anything started but not finished: files touched, what is left, how to continue, any half-made design decision. Nothing may be left uncommitted: commit and push, or discard.
3. **NEXT STEPS:** the next three WPs in order, each with its first concrete actions (files and functions to open, tests to write).
4. **DECISIONS:** one line each, with the `V2_DECISIONS.md` entry where the detail lives.
5. **DEVIATIONS** from this brief, with reasons.
6. **LOOK OUT FOR:** everything the next session would otherwise learn the hard way: CI quirks and flaky steps, fragile files or functions, commands that worked and ones that failed, things you tried that did not work and why, surprising code facts, traps you hit.
7. **VERIFICATION STATUS:** what is verified by CI or tests, what is only read, what needs John's device (point to the `TEST_CHECKLIST.md` sections that exist so far).
8. **OPEN QUESTIONS** (A0), each with the recommended default and whether you proceeded on it.
9. **ENVIRONMENT:** capabilities detected in this sandbox (Android SDK or not, network results, Python libraries), token and rate-limit notes (never the token itself).
10. **REMINDERS:** the invariants most easily broken (I1, I2, I3, I5, I11) and "re-read A3 and A4 before touching engine code".
11. **WHY THE SESSION STOPPED:** budget, blocker, or phase boundary.
Before ending, reread the packet as if you knew nothing: could a fresh session continue without asking anything? Check that every sha, path and link in it exists.

**2. Stop early, at a clean boundary.** You cannot read your context meter exactly, so use proxies: roughly 25–30 large tool outputs, repeated re-reading of the same files, long CI-fix loops, or slower progress than at the start. Treat those as "about 60% used" and stop at the next clean boundary (a WP pushed and green) instead of starting another WP. Always keep a reserve of at least 15% for the handoff. Do not start a WP unless you are confident you can finish it, push it, and write the packet with what remains. Phase boundaries are also natural stopping points.

**3. Rolling saves.** A session can be cut off without warning (usage limit, crash). Because the packet is pushed after every WP, at most one WP of context is lost. Prefer many small pushes to one big one.

**4. End-of-session output.** Finish with the Session Report (D3). Its last item is the **NEXT-SESSION MESSAGE**: one code block that John copies into a new chat, containing the D2 resume message followed by the full Handoff Packet. The repo copy is authoritative; if the two differ, trust the repo and say so.

**5. Priorities when budget is tight:** Tier A before Tier B before Tier C (Part C). Never skip documentation or tests to fit more features; they are part of "done".

## A10. Documentation deliverables (no other doc sprawl)

1. **`V2_DECISIONS.md`:** per WP, append a dated entry under `## What's implemented` (decision, rationale, rejected alternatives, files touched, verification status). Resolve or move items in `## On the horizon` and `## Deferred`. Record rejected ideas in `## Explicitly rejected` with reasons. Keep the "NOT device-confirmed" convention.
2. **`system_prompt.txt` + `PROMPT_CONSIDERATIONS.md`:** identical prompt block; per-field guidance and examples for every new field; only fields that exist in code.
3. **`IMPLEMENTATION_LOG.md`:** the living log.
4. **`TEST_CHECKLIST.md`:** written for a phone user (numbered steps, expected result, what to capture, which setting to toggle). Requirements in D4.
5. **`HANDOFF_NEW_SESSION.md`:** refreshed (current HEAD, architecture summary, how to build and verify, pointer to the log).
6. **`README.md`** (what and why, workflow, build, docs map) and **`CLAUDE.md`** (30 lines or fewer).
7. **`RELEASE_NOTES_V2.md`:** user-facing: what is new, how to use it, settings, known limits.
8. **`docs/samples/v2_showcase.json`:** one script that exercises every new field (extend it each phase; John pastes it with any narration to see everything).

## A11. Definition of Done

- **WP done:** code on the branch, CI green on that commit, tests added and passing (or a written reason it is untestable), docs updated (A10), log entry, invariants re-checked.
- **Phase done:** all Tier-A WPs done, phase tag pushed, log updated, `cmp` of the two prompt copies passes, launch-safety review (I12) recorded.
- **Campaign done:** Final Report (D5), `TEST_CHECKLIST.md`, link to the latest green APK run on `main`.

---

# PART B — CURRENT STATE

## B1. Verified facts at `eb6b0b3` (planner, 2026-10-09)

**Code map** (`app/src/main/java/com/example/`):
- `engine/`: `PlaybackEngine` (playback clock, live spring chase, amplitude motion, blink/fidget, sound-effect cue polling), `TimelineCompiler` (bakes script into `BakedKeyframe`, `CaptionCue`; `extractSoundEffectCues`), `StickFigureRig` (10-bone FK rig, 23 built-in poses), `Expression` (6 values), `AmplitudeAnalyzer` (`MouthShape`), `RigRenderer` (Canvas; shared by live preview and Canvas export), `GlesFrameRenderer` + `GlesFigureFrame` (GLES), `VideoExporter` (Canvas `export()` and the GLES `exportGlesSmokeTest`), `AudioMixer`, `SoundEffectPlayer` (SoundPool, preview), `OverlayResolver`, `ScriptValidator` (~19 KB), `EasingMath`.
- `data/`: `AnimScript.kt` (schema, ~40 KB), `OverlayLayer.kt`, `ExportSettings.kt`, `AppearanceSettings.kt`, `BackgroundMusicSettings.kt`, `SoundEffectClip.kt`, `BuiltInSoundEffect.kt`, `AppJson.kt`.
- `db/` (Room: `projects`, `poses`, `appearance_presets`, one `Migration(1,2)`), `viewmodel/MainViewModel.kt`, `ui/{canvas,components,editor,home,poses,settings,theme}`.
- Assets: `assets/prompt/system_prompt.txt` (859 lines, ~56.5 KB), `assets/sfx/`.
- Build: compileSdk 35, minSdk 26, targetSdk 35, Java 11 compatibility, Compose, KSP/Room, kotlinx.serialization. **No test dependencies and no `src/test`.** CI runs only `assembleDebug`.

**Character and motion:**
- One eased progress value drives every joint in a transition (`baseAngles[i] = from[i] + (to[i] - from[i]) * easedT`, `PlaybackEngine` ~line 520). `BakedKeyframe` carries one ease and one spring per transition.
- Expressions: 6, snapped by design (comment in `Expression.kt`; no interpolation).
- Mouth: 4 coarse shapes from amplitude and zero-crossing rate. No phonemes.
- No gaze, pupil, look-at or saccade code anywhere (grep over code and assets).
- "Alive" layer: breathing, talk sway, blinks, silence fidgets, all deterministic.
- Live preview spring chase hardcodes stiffness 320 and damping 32 (`applySpringIntegration`); seek and export use the event's spring parameters analytically. Preview and export therefore diverge for any non-default spring.

**Export:**
- Canvas `export()` builds SFX triggers by resolving script cues against `project.soundEffects`; unknown ids are dropped silently. It mixes via `AudioMixer.buildMixedTrack` when `embedAudio && (music set || SFX triggers exist)`, else copies narration verbatim. If the mixer throws, it silently falls back to narration only (Log.e only).
- `exportGlesSmokeTest` (`VideoExporter.kt` ~485–735) is a diagnostic, not an export: narration-only verbatim `copyAudioTrack` (its own doc comment says so), the audio track exists only if narration exists, it ignores `embedAudio`, it renders only the first 3 s unless called in "stress" mode (then full length), single aspect, hardcoded 8 Mbps, no VBR check, no particle trails. It draws text, shape and figure overlays.
- `AudioMixer`: the final `drain(blockForEos = true)` returns on the first `INFO_TRY_AGAIN_LATER` (flag ignored), then `encoder.stop()` runs, so the tail of the mix can be cut. The earlier infinite-stall bug is fixed (`drain(false)` runs every loop pass). Decode trusts the extractor's sample rate and channel count, assumes 16-bit PCM, and ignores decoder output-format changes. The file header comment is stale ("ONLY invoked when musicFilePath is set").
- Preview clamps SFX volume to at most 1.0; export does not clamp, and the mix sum hard-clips.
- No input-size guard in Canvas `queueFrame` (none found by grep).
- `ExportSettings`: `aspectRatio` String (default "9:16"), `resolution`, `fps` 30, `bitrateMbps` 8, `embedAudio` true, `outputFormat` "MP4", `dualAspectExport` false.
- `argbToNV12` is the simple synchronous loop again (the old parallel-slowdown regression is fixed).

**GLES specifics:** no MSAA or `EGL_SAMPLES` anywhere in `Gles*.kt`; `drawSolidFan` (`GlesFrameRenderer` ~1018) is a plain triangle fan. A `TEMPORARY — GLES Phase 3 mouth-position bug` diagnostic remains at `GlesFigureFrame.kt:362`. Text wrap uses `0.92f * canvasW` in both renderers (`RigRenderer` ~760/1896, `GlesFrameRenderer` ~1368). `eb6b0b3` ("Fix GLES text rasterization sharpness") touches only `GlesFrameRenderer.kt`. `screenSpace` appears in both renderers (behaviour not checked).

**Repo:** the remote has branches `main` (`eb6b0b3`) and `version2` (`7234495`, an ancestor of `main`, so nothing unique is on it); there are no tags. The repo is public.

**Audio analysis:** `AmplitudeAnalyzer` decodes audio and produces a 30 fps amplitude envelope plus coarse mouth shapes (`AmplitudeAnalysisResult`). `EnvelopeStore` keeps them as binary files under `filesDir/envelopes/` (`<projectId>_amp.bin` floats, `<projectId>_mouth.bin` one byte per frame). `AmplitudeSettings` already drives some figure-body reaction to amplitude (talk sway).

**Docs:** `V2_DECISIONS.md` ~187 KB, `PROMPT_CONSIDERATIONS.md` ~91 KB, `HANDOFF_NEW_SESSION.md` ~11 KB (stale: describes the overlay-text work as planned although it shipped).

**Prior decisions you must respect** (from `V2_DECISIONS.md`): AI drives the pipeline; the rejected items listed there (John has since overridden the amplitude-reactive one, see A4); a co-equal second speaker "needs its own dedicated scoping pass" and touches the core single-figure architecture; the Compose timeline editor was cut to tap-to-seek because gesture code cannot be tested here; `app/debug.keystore` is committed on purpose.

## B2. REPORTED by another analysis (video-measured on exports made *before* `eb6b0b3`; re-verify each against HEAD; some may already be fixed)

- **R1** GLES figure sits about 1.75 px higher than Canvas (whole-silhouette fit across poses; horizontal about 0; stroke widths within 0.06 px). By element: text about 0, rect about 1.0 px, eyes about 1.0 px, mouth about 1.8–2.0 px. Cause not found by reading.
- **R2** Zoomed, scaled and rotated text is softer in GLES (crispness vs Canvas about 0.62–0.85 depending on case).
- **R3** GLES polygons are not anti-aliased (no MSAA; mountain diagonals show about 24% fewer blended edge pixels; rect edge rows land on exact integers).
- **R4** Landscape GLES mouth is faint: about 20 px wide vs 24 in Canvas, about 40% less ink; portrait matches.
- **R5** `eb6b0b3` pixel-snaps text only when `rotationDeg == 0 && scale == 1`; zoomed, keyframe-scaled and rotated text stays a 1× bitmap that gets resampled.
- **R6** Wrap width is always 0.92 × canvas regardless of anchor or alignment: left-aligned text at x=0.1 can overflow the right edge (about 25 px landscape, 14 px portrait); text centred at x=0.2 can run off the left.
- **R7** `screenSpace` combined with `parentBone` or `parentLayer` is not validated (the layer would drift from its parent under camera moves).
- **R8** A keyframed `opacity` bypasses the exit fade, so the layer hard-cuts at `endSec`; the prompt does not say so.
- **R9** A property omitted from a middle `anim` keyframe holds, then moves only in the final segment; documentation is loose and AI authors will expect smooth motion.
- **R10** `rigid` as an enter/exit ease now snaps, but the validator says "treated as linear".
- **R11** Caption-fit algorithm is duplicated in both renderers; a caption box can grow to about 94% of screen height.
- **R12 (clean results; keep as regression expectations):** caption timing exact; no dropped or duplicated frames; enter/exit alpha curves identical; blink timing within about a frame; rotation within 0.15° of the resolver's math; glow circle within 1.3%; figure 38.3% of frame height in both orientations.

## B3. REPORTED by an earlier static review (re-verify in WP0.5)

AudioPlayer load/release race (a late `prepare` can assign a never-released `MediaPlayer`); `setOnCompletionListener` lost when set before prepare; `ExportTarget` leak if building target #2 throws; `TimelineCompiler` drops the whole event (camera, scene, colors, expression) when its pose is unknown; two events at the same `timeSec` collapse into an instant jump; overlapping transitions pop; `springAnalytical` clamp discontinuity (1.5 → 1.0) at low damping; camera-shake aliasing between 30 and 60 fps; amplitude motion uses a fixed dt of 1/30 in export; `AmplitudeAnalyzer` is not cancellable and can hang if EOS never arrives; `copyAudioTrack` reuses one buffer across samples; GLES `figureAlpha` may not be applied; GLES smoke test ignores user bitrate; `drainUntilEndOfStream` has no timeout.

## B4. Reference repos (borrow patterns, never code)

`github.com/JohnHeibel/ClaudeAnimationBase` and `github.com/JohnHeibel/PDoomVideo` (VERIFIED by reading; you may clone them and read their `ANIMATION_GUIDE.md`). Lessons, paraphrased:

- The animated thing is the **transition between states**, not the states: anticipation, squint, a "take" sized to the new emotion, overshoot, settle. Faces act; they never snap.
- Each emotion has its own idle motion. Key views (front, three-quarter, side) make turns. Small emote marks (sweat, "!", "?", sparkle) carry feeling cheaply.
- Every frame is a pure function of time. A storyboard with explicit "reads" (what the eye must understand next) comes before animating. Quality is checked by looking at contact sheets and fixing.
- They succeed because a frontier coding agent writes bespoke code per shot and looks at renders. RigScript's closed JSON cannot do that, so **the craft moves into the engine, and the external AI gets intent-level verbs** (`act`, `emote`, markers, macros). That is the thesis of this campaign.

---

# PART C — WORK PACKAGES

**Tiers:** A = must finish. B = should finish. C = only if everything above is green and budget remains.
**Execution order:** P0 → P1 → P2 → P3(A) → P4 → P3(B) → P5(B) → remaining C. If budget is tight after P3(A), jump to WP4.2–4.4 (prompt pack and prompt rewrite) so the shipped fields are documented, then return.
**Each WP lists:** Goal · Spec · Accept. "Verify first" means: re-check the claim against HEAD and record `CONFIRMED`, `ALREADY FIXED` or `NOT REPRODUCIBLE` before changing code. Design freedom is allowed where stated, if you log why.

---

## PHASE 0 — Foundations (Tier A)

### WP0.1 Bootstrap and log
- **Do:** Step 0 (A6); tag `pre-v2-baseline`; add `docs/CAMPAIGN_BRIEF.md`, `IMPLEMENTATION_LOG.md` (with **RESUME HERE** as the Handoff Packet of A9, **OPEN QUESTIONS** and a WP table) and `CLAUDE.md`.
- **Accept:** pushed to `main`; CI run recorded.

### WP0.1b Repo hygiene: remove the other branches
- **Verified at planning time:** the remote has `main` and one other branch, `version2` (tip `7234495`), which is an ancestor of `main` (no commits unique to it). There are no tags.
- **Spec:** re-list with `git ls-remote --heads origin` (there may be new branches). For each branch other than `main`: (1) if its tip is an ancestor of `main` (`git merge-base --is-ancestor`), delete it (`git push origin --delete <name>`); (2) if it has unique commits, first push an annotated tag `archive/<name>` at its tip, then delete the branch and list those commits in the log; (3) never touch `main`. Record each deletion (name, tip sha, reason) in the log. If a delete is rejected (permissions, rules), log it and continue.
- **Accept:** `git ls-remote --heads origin` shows only `main`, or the log says why not.

### WP0.2 Test infrastructure and CI
- **Spec:** add JUnit 4 as `testImplementation` (use the version catalog `gradle/libs.versions.toml` if the project uses one); `android { testOptions { unitTests.isReturnDefaultValues = true } }`; create `app/src/test/java/com/example/` with one trivial test. Change CI to `gradle testDebugUnitTest assembleDebug`, and upload `app/build/reports/tests` as an artifact with `if: always()`. Keep the APK upload. Do not touch signing.
- **Accept:** CI shows the test count in its log and is green; APK artifact still produced.

### WP0.3 Fixtures
- **Spec:** `app/src/test/resources/fixtures/` with at least 6 scripts: (1) minimal; (2) all poses and expressions; (3) camera, scene shapes, atmospheres, captions; (4) overlays: text, shape, figure, particles, `screenSpace`, `anim`, parenting, physics, trails; (5) sound-effect cues (including one unknown id); (6) a long dense script (about 10 minutes of events). Synthetic amplitude envelopes are generated in test code (silence, steady speech-like, noisy). Also capture **legacy JSON** of `AppearanceSettings()`, `ExportSettings()`, `BackgroundMusicSettings()` and a `ProjectDef` exactly as `eb6b0b3` serializes them, under `fixtures/legacy_json/` (used by I12 tests; capture them *before* adding any field).
- **Accept:** fixtures load through the real parser in a test.

### WP0.4 Legacy protection (golden snapshots)
- **Goal:** make autonomous refactors safe.
- **Spec:** a test helper dumps, for each fixture, rounded to 1e-4: baked keyframes summary; joint angles, expression, mouth shape, figure transform and camera at about 20 checkpoint times via the engine's seek path (use the synthetic envelopes); resolved overlays at checkpoints; caption cues; sound-effect cues; validator warnings. Mechanism: tests always write actual snapshots to `app/build/golden-actual/`; CI uploads that directory as artifact `golden-actual` (`if: always()`). Compare against committed `app/src/test/resources/golden/` with tolerance 1e-3. If a golden file is missing, the test **passes with a warning** (so the first run can generate it). Procedure: run CI on the baseline commit, download the artifact (token required), commit the files as the goldens, push, confirm the compare step is green.
- **Also:** property tests — determinism (same input twice → identical output), seek equals sequential playback (1e-4), cue order monotone, no NaN or infinity.
- **Design freedom:** if `PlaybackEngine` cannot be constructed on the JVM, extract the minimum pure core needed; document it. Record what could not be covered.
- **Accept:** goldens committed and the compare step green on CI; a unit test proves the comparator itself works (it reports a mismatch when a copy of a snapshot is perturbed beyond the tolerance, and passes within it). Never push a deliberately failing commit to `main`.

### WP0.5 Triage and small fixes
- **Verify first**, then fix the cheap ones and map the rest to later WPs in the log:
  - **Spring parity:** the live path ignores event spring parameters (B1). Make live playback and seek/export use the same evaluation (preferably one analytic function). Test: sequential equals seek for `spring`, `elastic_out`, `bounce` at several stiffness/damping values.
  - `queueFrame` input-size guard in the Canvas exporter (check `inputBuffer.capacity()`/`remaining()`; set `KEY_MAX_INPUT_SIZE` when configuring).
  - `ExportTarget` leak if building a later target throws (release already-built targets).
  - Validator wording for `rigid` (R10).
  - `TEMPORARY` mouth diagnostic at `GlesFigureFrame.kt:362`: inspect. If it is logging only and the root cause is already known (V2_DECISIONS says it was superseded by the rotation fix), remove it or gate it behind `BuildConfig.DEBUG`. Record the reasoning.
  - All B3 items: verify, fix if small and safe, else log (the unknown-pose event drop is resolved in WP2.5 together with optional `pose`).
  - Two older open items: landscape zoom clamp and ground-line persistence in the demo script — check by reading whether they still reproduce.
- **Accept:** each item has a recorded outcome; CI and tests green; goldens unchanged except where a fix intentionally changes output (justify in `V2_DECISIONS.md`).

**Tag `v2-p0` when WP0.1–0.5 (and 0.1b) are done.**

---

## PHASE 1 — Export: audio and GLES (Tier A)

**Rule for this phase:** the default renderer becomes `AUTO` (John: "if GLES works flawlessly, we can make it the default"). Nobody can prove "flawless" from a chat, so `AUTO` means: use GLES on a device **only after the built-in on-device eligibility check has passed there** (WP1.3, WP1.6); until then, or if the check fails, use Canvas. John can force `GLES` or `CANVAS` in settings. Document exactly how `AUTO` resolves and how to force either renderer.

### WP1.1 Shared export-audio unit
- **Goal:** both backends produce identical audio behaviour, and nothing is dropped silently.
- **Spec:** new `engine/ExportAudio.kt`.
  - Pure `decideMode(embedAudio, hasNarration, hasMusic, sfxTriggerCount)` returns `NONE` (not embedding, or nothing to play), `PASSTHROUGH` (narration only, lossless copy) or `MIX` (music and/or SFX present, with or without narration).
  - `prepare(...)` builds the plan: cue resolution (collect unresolved ids as warnings), mixed track or passthrough, `totalSec` using the same formula Canvas uses today.
  - `PreparedAudio.addTrack(muxer)` and `PreparedAudio.write(muxer, trackIdx, maxDurationUs)`.
  - If `MIX` fails and narration exists, fall back to `PASSTHROUGH` **and add a warning** naming the reason. Never silently drop.
  - `ExportResult` gains `warnings: List<String>` (default empty). Show them after export using the existing result UI (a simple dialog or snackbar).
  - Replace the audio code in `export()` with this unit (keep behaviour identical for narration-only exports).
- **Accept:** unit tests for `decideMode` (all input combinations), cue-resolution warnings, fallback decision logic. CI green. Goldens unchanged.

### WP1.2 AudioMixer hardening
- **Spec:**
  - `drain(blockForEos = true)` must really wait for end-of-stream: loop until the output buffer carries EOS or a no-progress watchdog (about 5 s without output) expires; only then stop the encoder.
  - Add a no-progress watchdog and cancellation checks to every decode and encode loop (no infinite hang if EOS never arrives).
  - Decode with the **decoder's actual output format** after `INFO_OUTPUT_FORMAT_CHANGED` (sample rate, channel count, `KEY_PCM_ENCODING`); convert float and 8-bit PCM to 16-bit through pure, tested functions. Handle the WAV/raw path's declared encoding.
  - Soft limiter on the final mix: identity below about 0.89 full scale, smoothly bounded above, no NaN, monotonic. Normal mixes keep their current level.
  - One shared pure function `effectiveSfxVolume(clipVolume, cueMultiplier)` (clamped to 0..1) used by the preview player and the mixer.
  - Collect decode failures as warnings returned to WP1.1.
  - Fix the stale header comment.
- **Accept:** unit tests for the limiter, the volume rule and the PCM conversions. CI green. Log the parts that cannot be tested on the JVM.

### WP1.3 GLES as a real export backend, default `AUTO`
- **Spec:**
  - Add `renderer: String = "AUTO"` (`AUTO` | `GLES` | `CANVAS`) to `ExportSettings` (JSON-serialized; no Room change) and a "Renderer" selector in the existing export settings UI, with one line of help text and a "Re-run GLES check" button.
  - **`AUTO` resolves per device** through `GlesEligibility`: a cached result in settings, keyed by (app version code + `Build.FINGERPRINT`), with states `ELIGIBLE`, `INELIGIBLE(reason)` and `UNKNOWN`. `ELIGIBLE` → GLES. `INELIGIBLE` or `UNKNOWN` → Canvas. When `UNKNOWN`, run the quick check (WP1.6) once in the background shortly after launch: low priority, skipped while an export or playback is running, guarded by a mutex against a concurrent export.
  - **Crash-loop guard:** write a `gles_check_in_progress` marker before the check and clear it after. If the app starts and finds the marker still set (a native GL or codec crash), record `INELIGIBLE("crashed")` and do not retry automatically for that app version.
  - **Runtime fallback:** if GLES fails during init or the first frames in `AUTO`, clean up, record `INELIGIBLE(reason)`, run Canvas instead, and show a visible warning ("GLES failed: reason; exported with Canvas"). An explicit `GLES` choice reports the error and offers "Retry with Canvas".
  - Promote `exportGlesSmokeTest` into `exportGles(...)`: full length (same `totalSec` as Canvas), `settings.bitrateMbps`, VBR if supported (mirror Canvas's encoder configuration; read it), `settings.fps`, **audio through `ExportAudio`**, honours `embedAudio`.
  - **Dual aspect** when `dualAspectExport` is true: one EGL context, one `EGLSurface` per encoder input surface, `eglMakeCurrent` per target per frame, shared GL resources, per-target caches keyed by size. If the current renderer class assumes one surface, refactor it minimally (e.g. a `makeCurrent(targetIndex)`).
  - **Particle trails** (the `trail` flag): implement so both backends draw identical results. Preferred: expand trails in the shared resolver into ordinary faded copies; otherwise replicate exactly. Parity is the criterion.
  - Preserve cancellation, progress and ETA reporting, and the thermal safety net, mirroring the Canvas path.
  - Keep the old smoke-test button working until the new path is wired; then route "Export" by the resolved renderer and move the smoke test under Diagnostics (WP1.6).
  - Capability probe: if EGL recordable config or surface-input AVC is unavailable, record `INELIGIBLE(reason)` and use Canvas.
- **Accept:** compiles; unit tests for the pure parts (`GlesEligibility` state and cache-key logic, crash-marker handling, renderer resolution, target dimension alignment, bitrate selection, fallback decisions); no device claims; log the dual-aspect design decision.

### WP1.4 GLES parity fixes (verify R1–R11 first; implement only what is CONFIRMED)
- **R1 vertical offset (1.75 px).** Build a small numeric model (Python or a JVM test) of the vertex transform chain (canvas pixel → clip space, y flip, viewport size, any encoder height alignment such as 1080→1088, half-pixel conventions, stroke-quad construction) and compare against Canvas's transform math for a known bone. The element-dependent pattern (text about 0, rect about 1.0, figure about 1.75, mouth about 1.9) suggests more than one effect; find each. Fix the cause; add a test of the transform model against reference points.
- **R2/R5 text crispness.** Rasterize text at the *effective* scale (camera zoom × layer scale × device resolution), draw the cached bitmap at 1:1 pixel mapping, and quantize the cache scale (e.g. steps of ×1.25, with a cap and eviction) so keyframed scales do not thrash the cache. For rotation, rasterize at ≥1× the effective scale and rotate with linear filtering. Pixel-snap only when it is exact. The cache key must include every parameter that changes pixels. Test the quantization function.
- **R3 polygon edges.** Add anti-aliasing for filled polygons. Choose and log one of: offscreen MSAA FBO resolved to the surface (GLES 3.0), a multisampled EGL config if the recordable config supports it, or an analytic coverage fringe (expand each filled polygon by about 1 px with vertex alpha 1→0). Provide a fallback if the chosen path is unavailable. Test any pure fringe geometry.
- **R4 landscape mouth.** Compare `computeMouthGeometry` inputs and stroke widths across orientations and between backends; make geometry orientation-independent in normalized units and identical to Canvas. Test equality across orientations.
- **R6 wrap width.** Make the available text width anchor-aware: left-aligned = distance from the anchor to the right edge minus margin; right-aligned = distance to the left edge; centred = twice the smaller distance; each capped at 0.92 × canvas. Move the layout builder's width computation into one shared pure function used by both renderers. Test all alignments and extreme anchors.
- **R7 `screenSpace` + parent.** Validator warning, and a deterministic resolver rule: ignore `screenSpace` when a parent is set (parenting wins). Document in the prompt docs only when the prompt next changes (I5).
- **R8 keyframed opacity vs exit.** Keep the precedence (`anim` wins) so behaviour does not change; add a validator warning when `anim` keys opacity and an exit style is set; document precisely.
- **R9 omitted middle keyframe.** Verify the current behaviour. Because `anim` is days old (I1 exception), prefer standard interpolation between the nearest keyframes that define the property; log the change. Add tests.
- **R11 captions.** Move caption fitting into one shared pure function (used by both renderers) and cap the box height with a new `AppearanceSettings` field (default 0.5 of canvas height, shrink font then truncate lines). Defaults must not change normal captions.
- **Accept:** each item has a recorded outcome; tests for every pure function introduced; goldens updated only where a fix intentionally changes output (explain in `V2_DECISIONS.md`).

### WP1.5 Phase 1 documentation
- **Spec:** `V2_DECISIONS.md` entries; `HANDOFF_NEW_SESSION.md` refresh; update the stale `AudioMixer` header; prompt docs only for validator-visible behaviour that changed (keep byte-identity).
- **Accept:** docs consistent with code.

### WP1.6 Diagnostics: full Self-test and the quick GLES eligibility check (Tier A)
- **Goal:** (1) let John verify export on his phone with one tap and send you a report; (2) give `AUTO` an objective, on-device definition of "works flawlessly".
- **Full Self-test screen** (reachable from Settings; visible in debug builds, `BuildConfig.DEBUG`):
  - *Fixture generation in Kotlin (no bundled media):* narration = 6 s of a 440 Hz tone; music = 220 Hz tone at low level; SFX clips = 1 kHz 100 ms bursts triggered at 1.0 s, 2.5 s and 4.0 s through script cues and a temporary project library.
  - *Audio matrix:* all 7 non-empty combinations of {narration, music, SFX} × {Canvas, GLES} at 360p. Per export: audio track present, duration within ±100 ms of expected, per-source presence by Goertzel magnitude at 440/220/1000 Hz, SFX energy in ±50 ms windows around each cue vs elsewhere. PASS/FAIL per cell with the measured numbers.
  - *Parity frames:* export the same visual fixture on both backends; decode K frames with `MediaMetadataRetriever` (`OPTION_CLOSEST`); compute the metrics below; save side-by-side PNGs.
  - *Report:* `diagnostics_report.json` and `diagnostics_summary.txt` in the app's external files dir, plus a Share button (`ACTION_SEND`). Include a "GLES smoke test (3 s)" button.
- **Quick eligibility check** (target under about 25 s; 360p; both backends; no UI beyond a small progress line). It passes only if **all** hold, with thresholds in a `GlesAcceptance` object:
  1. no exception or timeout, and the GLES output decodes;
  2. audio: the narration + music + SFX mix and the SFX-only case both PASS on GLES (same analysis as the matrix);
  3. GLES and Canvas output durations within ±100 ms of each other;
  4. on 4 sampled frames of the visual fixture (figure, zoomed/rotated text, a diagonal polygon, a caption): estimated GLES-vs-Canvas offset ≤ 0.75 px (best integer shift of an edge map within ±3 px, refined by a parabola fit), mean absolute difference inside the figure's bounding box ≤ 6 on a 0–255 scale, Laplacian-variance ratio GLES/Canvas ≥ 0.90 on text, and edge-AA ratio (count of intermediate-gray edge pixels, GLES/Canvas) ≥ 0.85.
  Thresholds are starting points. Document them, explain in `TEST_CHECKLIST.md` how John or a later session can tune them, and keep `evaluate(metrics)` a pure, tested function.
- **All DSP and comparison math** in pure Kotlin with unit tests on synthetic arrays (shifted images for the offset estimator, known blur for the sharpness ratio).
- **Accept:** compiles; DSP and `GlesAcceptance.evaluate` tests pass; usage documented in `TEST_CHECKLIST.md`.

### WP1.7 Phase 1 wrap
- Update `TEST_CHECKLIST.md` (audio matrix, the GLES eligibility result and what `AUTO` chose, GLES vs Canvas parity, a 3+ minute export for thermal and memory, dual aspect, how to force Canvas or GLES). Run the I12 launch-safety review. Verify `cmp` of the prompt copies. **Tag `v2-p1`.**

---

## PHASE 2 — Acting layer: believable characters (Tier A)

**Why (VERIFIED, B1):** one eased value drives every joint; expressions snap; the mouth has 4 coarse shapes; there is no gaze.
**Architecture:** new pure-Kotlin package `engine/acting/`. The engine computes base state exactly as today, then `ActingLayer.apply(base, ctx, t)` returns the acted state. Both renderers draw the acted state (I3). With intensity `OFF` the layer returns the base state **bit-identically**. Wrap it fail-soft (I11).
**Classification (A4):** per-joint timing, expression blending, gaze micro-movement, mouth smoothing and mood idles are **Craft**. `act` verbs and `emote` are **Direction** (AI-authored) and execute at every intensity; intensity `OFF` disables Craft only.
**Initial parameters** (put in `ActingParams`; tune by plotting; these are starting points, not truth):

| | SUBTLE | NORMAL | BOLD |
|---|---|---|---|
| lead/follow delay per chain level | 12 ms | 25 ms | 40 ms |
| duration stretch per level | 3% | 6% | 9% |
| anticipation (fraction of move, cap) | 3%, 4° | 6%, 6° | 9%, 9° |
| overshoot (fraction of move, cap) | 2.5%, 3° | 5%, 5° | 8%, 8° |
| expression blend time | 0.12 s | 0.18 s | 0.24 s |

Chain levels: torso = 0; head and upper arms and upper legs = 1; forearms and lower legs = 2 (map from the real bone names). Anticipation only for moves larger than about 12° lasting at least 0.25 s. Exclude `walk_*`/`jog_*` cycles from anticipation and overshoot (lead/follow only). Overshoot uses a damped sinusoid (about ω = 14 rad/s, ζ = 0.55) with an exact landing after the settle time.

### WP2.1 Plumbing and switch
- **Spec:** `actingIntensity: String = "NORMAL"` in `AppearanceSettings` (values `OFF`, `SUBTLE`, `NORMAL`, `BOLD`); a small selector in the Appearance tab labelled "Acting"; a quick A/B affordance in the editor (a toggle that flips OFF ↔ current) so John can compare in seconds. `ActingContext` carries settings, seed, envelope access and resolved target positions.
- **Accept:** tests: identity when OFF; determinism; seek equals sequential; legacy JSON decodes with the default; goldens unchanged when OFF.

### WP2.2 Motion model (per-joint timing)
- **Spec:** replace "one eased value for all joints" (under acting only) with per-joint lead/follow offsets by chain level, duration stretch, anticipation and overshoot as above. Retarget on overlapping transitions by sampling the current acted angles as the new `from` (removes the pop; also handles same-timestamp events by script order). Landing is exact. Add moving holds (tiny seeded sinusoidal drift, at most 0.3°, off during locomotion).
- **Accept (tests):** arrival and peak-velocity ordering (torso before head before forearms); overshoot never exceeds the cap; final value within 1e-5 after settle time; angular velocity continuous (no step above a threshold at 60 fps); NaN-free; OFF identical to legacy; seek equals sequential. Python prototype and plots committed under `tools/acting_proto/` and viewed by you before porting.

### WP2.3 Face: blending, brows, gaze
- **Spec:**
  - **Step 1 (pure refactor, no behaviour change):** extract the face geometry (eyes, brows, mouth, any pupils) from `RigRenderer` and `GlesFigureFrame` into one shared pure `FaceGeometry` (an existing `computeMouthGeometry` already shows the pattern). Both renderers call it with legacy parameters. Goldens must not move. Push and get CI green before step 2.
  - **Step 2:** introduce `ResolvedFace` (eye openness, squint, brow angles and heights, mouth parameters, gaze offset). `ExpressionBlender` blends between expression parameter sets over the blend time with anticipation (about 60 ms squint before `wide`/`angry`), a "take" (about 10% eye-openness overshoot) and settle. Legacy expressions at steady state must produce exactly the legacy geometry.
  - Brows on every expression when acting is on (small defaults: normal neutral, happy slightly raised, wide high, squint low and flat, worried inner-up, angry inner-down).
  - **Gaze:** offset applied to eye (and pupil, if drawn) positions, capped at about 18% of head radius. Idle saccades every 1.0–3.2 s (seeded), with a return-to-centre bias; eyes lead head motion by about 90 ms; head tilt follows by at most 3°; a blink is coupled to shifts above about 12% with seeded probability about 0.35. `look_at`/`look_away` (WP2.5) feed targets into the same controller.
- **Accept (tests):** steady-state geometry equals legacy for all 6 expressions (derive the expected numbers by reading the existing code and state that they were derived by reading); blend continuity and bounds; gaze bounds; determinism; seek equals sequential; OFF identical to legacy.

### WP2.4 Speech and mouth
- **Spec:** continuous `MouthParams` (open, width, roundness) from the amplitude envelope with attack about 35 ms and release about 85 ms, hysteresis about 8%, full closure after sustained silence (use the existing silence threshold), and ZCR classification mapped to wide/narrow fricative shapes; co-articulation by smoothing between parameter sets. Legacy 4-shape mapping is preserved for OFF. Draw through `FaceGeometry`. No transcript dependency in this phase.
- **Accept (tests):** no flicker (shape toggles per second below a bound on a noisy synthetic envelope); ranges bounded; determinism; OFF equals legacy mapping.

### WP2.5 Intent verbs and emotes (schema additions; Direction)
- **Schema (optional fields on a script event):** `act` (string), `actTarget` (string), `actIntensity` (0..1, default 0.7), `emote` (string).
  - `actTarget`: `"left"|"right"|"up"|"down"|"camera"|"layer:<id>"|"x,y"` (normalized screen coordinates).
  - **`act` closed set (10):**
    - `nod` — head dip and recover (about 0.45 s).
    - `shake_head` — two side-to-side head tilts (about 0.6 s).
    - `emphasize` — brief torso lean, head dip and arm accent (about 0.35 s).
    - `react_surprise` — squint, take (eyes wide, lean back, arms up a little), settle (about 0.7 s).
    - `react_think` — head tilt and gaze up and aside (about 0.8 s).
    - `react_laugh` — rhythmic torso bounce, 3–4 cycles, wide mouth (about 1.0 s).
    - `react_sad` — slump, gaze down, held about 1.0 s.
    - `look_at` — gaze (and a partial head turn) to `actTarget`, held until the next `look_at`/`look_away` or 2 s.
    - `look_away` — glance aside and down, then return (about 0.8 s).
    - `point_at` — two-bone analytic IK of the nearer arm toward `actTarget` (layer position resolved at time `t`), with anticipation, held about 1.2 s; clamp to reach.
  - **`emote` closed set (9):** `exclaim`, `question`, `sweat`, `sparkle`, `heart`, `idea`, `anger`, `zzz`, `dots`. Each expands in the shared resolver into at most 4 ordinary overlay elements near the head, parented to the head bone: pop-in, small upward drift, fade over about 0.9 s, fixed palette, sizes relative to head radius, honouring `figureOpacity`. Both renderers need no new code.
- **`pose` becomes optional** on any event that carries another field (`act`, `emote`, expression, camera, scene, colors, caption, sound effect, figure transform). An absent pose means "hold the current pose" (no transition). An unknown pose likewise applies the event's other fields and adds a validator warning (this also fixes the B3 item where the whole event was dropped). An event with no pose and no other field gets a validator warning. Scripts that always include `pose` are unaffected (I1).
- **Rules:** verbs are additive offsets over the base pose, never replacing it, and return to base. Unknown verbs and emotes, a missing target, or an unresolvable layer produce validator warnings and are otherwise ignored. Prompt guidance (written in WP4.4): at most one `act` per 3–4 seconds, never stacked.
- **Accept (tests):** IK forward-kinematics round trip within tolerance and clamped when out of reach; every verb's curve bounded and returning to base; determinism; emote expansion counts and timing; validator rules; legacy scripts unchanged.

### WP2.6 Mood idles (Craft, small)
- **Spec:** breathing rate and sway amplitude depend on the current expression (calm vs energetic), deterministic, scaled by intensity, off during locomotion cycles. Constants in `ActingParams`.
- **Accept:** bounds and determinism tests.

### WP2.7 Phase 2 wrap
- Extend `docs/samples/v2_showcase.json`; write `TEST_CHECKLIST.md` entries for A/B comparison (what to look for: overlap and settle, soft expression changes, eye movement, calmer mouth, `act`/`emote` examples) and a **Tuning guide** (which `ActingParams` constants make it subtler or bolder). I12 review. `cmp` check. **Tag `v2-p2`.** (Prompt documentation for the new fields lands in WP4.4; until then do not mention them in the prompt, per I5.)

---

## PHASE 3 — Motion graphics and kinetic text

### WP3.1 Kinetic text modes (Tier A)
- **Spec:** optional overlay fields `textMode` (`"block"` default | `"word"`) and `stagger` (seconds between units). The resolver outputs per-unit local progress; renderers draw each word with the layer's enter style and per-word delay. Word boxes come from the shared layout builder (`StaticLayout` offsets), used by both renderers. In GLES, draw sub-rectangles of the cached text bitmap with per-word transforms. Build on the crisp-text work (WP1.4) and keep the cache key complete. `"char"` is optional if it is cheap.
- **Accept:** pure stagger-timing tests; a clear note of what CI cannot test (layout on the JVM).

### WP3.2 Behaviors (Tier A)
- **Spec:** optional overlay field `behavior` = `{type, ...}` with types `oscillate`, `orbit`, `drift` (seeded noise from the layer id), `pulse`, `follow` (another layer by id, lag at most 1 s, depth guard 4). Closed-form, evaluated in the shared resolver, applied as **additive** offsets on top of `anim`/physics results. No audio dependence.
- **Accept:** determinism, bounds, follow depth-guard and seek-equals-sequential tests.

### WP3.3 Named beats (Tier A)
- **Spec:** optional top-level `markers` (name → seconds). Anywhere a time in seconds is accepted (event `timeSec`, blink event time, overlay `startSec`/`endSec`), the script may use `"@name"`, `"@name+0.3"` or `"@name-0.2"`. Implement as a **pre-pass over the parsed JSON tree for an explicit list of time fields** so data classes stay numeric. An unresolved marker drops that item and emits a validator warning. Also add pure `OnsetDetector` over the stored amplitude envelope (energy flux with smoothing, threshold mean + 1.4σ, minimum gap 0.22 s), deterministic; it feeds the prompt pack in WP4.2. Onsets only inform the prompt text (suggested markers); they never drive visuals by themselves (A4).
- **Accept:** parser, resolver and onset tests (synthetic envelopes); legacy numeric scripts unchanged.

### WP3.4 Transitions and emitters (Tier B)
- **Spec:** overlay `type: "transition"` with `style` in `wipe_left|wipe_right|wipe_up|wipe_down|flash|fade_color`, plus `color` and `duration` (default 0.4 s), expanded by the resolver into ordinary full-frame rectangle overlays with `anim`. Continuous particles: `particles` gains `emit: true` and `emitRate` until `endSec`, expanded deterministically (born at `start + k/rate`, closed-form position), at most 200 alive and 256 expanded elements per frame.
- **Accept:** expansion count, timing and cap tests; parity by construction.

### WP3.5 Validator and repair prompts (Tier A)
- **Spec:** extend `ScriptValidator` with: reads density (too many new reads inside 0.4 s); overlapping text boxes at the same time (more than 20% overlap); text outside 10–90% (info); long dead-centre figure stretches (info); same-timestamp event stacking; unknown `act`/`emote`/`behavior`/`transition`/`textMode`; `screenSpace` with a parent; `anim` opacity with an exit style; unresolved markers; too many simultaneous overlays. Every finding carries a `repairHint` (one imperative sentence for the AI). `ValidationReport.toRepairPrompt()` builds paste-ready text ("Your JSON had these problems; return the full corrected JSON only: …"). Add a **Copy repair prompt** button next to the validation results.
- **Accept:** one positive and one negative fixture per rule; stable ordering.

### WP3.6 Chart and counter primitives (Tier C)
- **Spec:** `type: "chart"` (bars; labels; growth stagger) and a `counter` text mode (`from`, `to`, `format`) implemented as resolver macro expansion into rectangle/line/text primitives. Beware GLES text-cache churn from per-frame strings: update counters at a quantized rate (about 15 Hz) and cap cache growth.
- **Accept:** expansion and format tests; documented limits.

### WP3.7 Audio-reactive visuals (Tier A for overlays; Tier B for camera and the music source)
- **Decision (John, 2026-10-09):** audio-reactive visuals are wanted. This overrides the earlier rejection of "amplitude-reactive background motion" in `V2_DECISIONS.md`. **Classification: Direction.** The AI opts in per layer; the app never adds reactivity on its own. Annotate the old rejection ("OVERRIDDEN 2026-10-09 by John; see WP3.7"), amend the "AI drives the pipeline" paragraph, and record the reasoning: the AI cannot hear the audio, so only the engine can supply loudness, while the AI still decides what reacts and when.
- **Analysis (pure Kotlin, new `BandAnalyzer`):** from the decoded PCM, mono mix, radix-2 FFT (Hann window of 1024 samples, hop = sampleRate/60). Bands: `bass` 20–250 Hz, `mid` 250–2000 Hz, `treble` 2000–8000 Hz, and `all` (RMS). Output 60 fps float arrays, each normalized by its own robust percentile (95th → 1.0, clamped to 0..1) so quiet files still react. Store as `<projectId>_bands.bin` next to the existing envelopes (header: version, fps, frame count, band count, scale factors). Compute lazily the first time a script uses `audioReact` (reuse the existing decode pass if cheap); **export waits for it**; preview shows no reaction until it is ready. Music source: analyze `BackgroundMusicSettings.musicFilePath` the same way, cached by path + size + mtime. Delete the files with the project. A missing file means zeros.
- **Smoothing:** precompute three smoothed variants per band at analysis time (`tight` about 40/90 ms attack/release, `medium` about 80/200 ms, `slow` about 150/400 ms) so sampling at query time is O(1), deterministic and seek-independent.
- **Schema (overlay layers):** `audioReact`: up to 3 entries `{ "prop", "band", "amount", "smooth", "source", "gate" }`.
  - `prop`: `scale | opacity | x | y | rotationDeg | glowRadius | width | height | radius`.
  - `band`: `all | bass | mid | treble` (default `all`).
  - `amount`: the change applied at envelope value 1.0, in the property's own units (default 0.2).
  - `smooth`: `tight | medium | slow` (default `medium`).
  - `source`: `narration | music` (default `narration`).
  - `gate`: envelope level below which there is no reaction (default 0.05).
  Active only between the layer's `startSec` and `endSec`.
- **Camera (Tier B):** top-level `cameraReact`: a list of windows `{ "startSec", "endSec", "prop": "zoom" | "shake", "band", "amount", "smooth", "source" }`.
- **Evaluation:** in the shared resolver, **after** `anim`, physics and `behavior`, add `amount * envelope` as an additive offset, then clamp to the property's legal range. Both renderers consume resolved values (I3). No envelope or no audio means zero offset.
- **Validator:** unknown `prop`/`band`/`smooth`/`source` warn; `audioReact` in a project with no audio is an info; implausibly large amounts (for example a scale amount above 1.5) warn; more than 3 entries warn and the extras are ignored.
- **Accept (tests):** FFT output equals a naive DFT on synthetic signals; tones at 100 Hz, 1 kHz and 4 kHz land in bass, mid and treble respectively; normalization bounds; file header round trip and legacy absence; sampling is continuous and deterministic; smoothing presets ordered (tight faster than slow); resolver offsets bounded and clamped; seek equals sequential; legacy scripts unchanged.
- **Prompt docs (WP4.4):** one short section with three usage patterns (a ring pulsing on bass, a glow that swells with the voice, a title that nudges in scale) and "use sparingly; never on every layer".

### WP3.8 Phase 3 wrap
- Extend the showcase script; `TEST_CHECKLIST.md` entries (including audio-reactive checks); I12 review; `cmp`; **tag `v2-p3`**.

---

## PHASE 4 — Director loop around the external AI (Tier B)

### WP4.1 Optional transcript input
- **Spec:** the project can store a timed transcript (paste or import `mm:ss text` lines or SRT) inside an existing JSON-serialized project/settings blob (no Room change). Used by the prompt pack, validator coverage hints and, if cheap, word-timing hints. Entirely optional.
- **Accept:** parser tests (SRT and simple formats); legacy projects unaffected.

### WP4.2 Prompt pack
- **Spec:** a **Copy prompt pack** action in the script editor that assembles clipboard text: the system prompt (asset), an optional dialect snippet, the project's SFX library listing (the existing injection point in `MainViewModel`), suggested markers from `OnsetDetector`, the transcript if present, and short output reminders. A small dialog picks dialect and options.
- **Accept:** assembly function tested as pure code.

### WP4.3 Dialects
- **Spec:** `assets/prompt/dialects/*.txt` with 3–5 short snippets (e.g. `story_host`, `soft_explainer`, `kinetic_type`, `diagram_kinetic`, `pulse_beat`), each naming the tools and anti-patterns of that style using **only implemented fields**. Document them in `PROMPT_CONSIDERATIONS.md` in a block *separate* from the byte-identical base prompt.

### WP4.4 Prompt rewrite (director style)
- **Spec:** rewrite `system_prompt.txt` and the mirrored block in `PROMPT_CONSIDERATIONS.md` together (byte-identical). Structure: role and output contract; the director's internal process (logline, arc, one recurring motif, beats with "reads", strategy per beat); closed vocabularies; every implemented field including `act`, `emote`, `markers`, `textMode`, `behavior`, `transition`; anti-patterns (stacked acts, dead-centre figure through conceptual beats, text over the head, flooding the timeline); retention formulas already in the document; two compact few-shot scripts or move them into dialect files. **Target: not longer than today's 56.5 KB; aim for 48 KB or less** by trusting engine defaults (the AI no longer teaches animation craft). Do not document anything that is not merged (I5).
- **Accept:** `cmp` passes; every field named in the prompt exists in code (a test can scan `AnimScript`/`OverlayLayer` property names against a list extracted from the prompt block).

### WP4.5 Review sheet export
- **Spec:** an action that renders N frames (default 12) off-screen through the Canvas renderer at event-driven and evenly spaced times, plus two "motion strips" (6 frames at 10 fps around the biggest pose changes), assembled into one or two PNGs (longest side at most 4096) with timestamps and event labels, and a `review_prompt.txt` ("Critique this against the rules and the reads; return a JSON patch of ops"). Share via `ACTION_SEND_MULTIPLE`.
- **Accept:** layout math tested as pure code; documented usage.

### WP4.6 JSON patch apply with undo
- **Spec:** accept an RFC 6902 subset (`add`, `replace`, `remove`) over `/events/N/...`, `/overlayLayers/N/...`, `/blinkEvents/N`, `/markers/<name>`. Apply to a copy, run `ScriptValidator`, show errors and **do not apply** if invalid; otherwise apply, keep the previous script for single-level undo (stored without a Room schema change). Minimal UI: paste, Apply, Undo.
- **Accept:** patch applier tests (valid, invalid path, out-of-range index, validator failure, undo round trip).

### WP4.7 Phase 4 wrap
- `TEST_CHECKLIST.md`; I12 review; `cmp`; **tag `v2-p4`**.

---

## PHASE 5 — Breadth (Tier B/C)

### WP5.1 Export presets and SRT (Tier B)
- **Spec:** `ExportSettings.dimensions()` supports `"1:1"` and `"4:5"` (UI options; both backends). Pure `CaptionCue` → SRT converter (correct timestamps, wrapping) with a Share action.
- **Accept:** dimension and SRT tests.

### WP5.2 Appearance: hands and feet (Tier C)
- **Spec:** `AppearanceSettings` toggles `showHands`, `showFeet` (default **off**) and sizes; small shapes at wrist and ankle endpoints from the FK result, drawn through shared geometry in both renderers. Purpose: let John judge whether the character needs more body before further motion work. Never changes defaults.
- **Accept:** geometry tests; off-by-default identity.

### WP5.3 Second speaker — design note only (Tier C)
- **Spec:** a `V2_DECISIONS.md` "On the horizon" entry (no code) recommending an architecture: a `characters` array; a `character` field on events; AI-authored `speaker` windows routing the single narration envelope to the active character's mouth; per-character rig state; reuse of the `figure` overlay path; rendering loops over characters; migration path, risks, and which files change. Implement **only** if every Tier A/B WP is done and green, and only behind the presence of `characters` so single-figure paths stay bit-identical.

### WP5.4 Final documentation
- Polish `README.md`, `RELEASE_NOTES_V2.md`, `HANDOFF_NEW_SESSION.md`, `TEST_CHECKLIST.md`, showcase script. Final launch-safety review. `cmp`. **Tag `v2-final`.** Produce the Final Report (D5).

---

## C-END. Schema additions at a glance (for consistency checks)

- Script events: `act`, `actTarget`, `actIntensity`, `emote`.
- Script top level: `markers`, `cameraReact` (Tier B). Time fields may be `"@name±x"` strings.
- Overlay layers: `textMode`, `stagger`, `behavior`, `audioReact`, `emit`, `emitRate`; new `type` values `transition` (and `chart`, Tier C).
- Settings (JSON-serialized, defaults preserve legacy): `ExportSettings.renderer` (default `AUTO`), new aspect values; `AppearanceSettings.actingIntensity`, caption max-height fraction, `showHands`, `showFeet`.
- The script `version` string stays `"1.0"`: every addition is optional.

---

# PART D — PROMPTS, REPORTS, HEURISTICS

## D1. Kickoff message (John pastes this in the first chat, with this file attached)

> Repo copy note: the original file contained a real GitHub token on the "GitHub token" line below. It is replaced by `<PASTE TOKEN>` here so the token is never committed to this public repository.

```
You are the implementer for the RigScript V2 campaign. The attached file
RigScript_V2_Campaign_Brief.md is your complete brief. Read ALL of it first,
then follow it exactly.

Authorization (section A7): you may push to main of
github.com/john1183-prog/rigscript (fast-forward only, never force-push),
create and push tags, and delete the other remote branches as WP0.1b describes.
GitHub token (use only as A7 says, never print or store it): <PASTE TOKEN>

Start with Step 0 (A6), then WP0.1. Work autonomously. Ask me a clarifying
question only when absolutely necessary, as A0 defines (batched, each with a
recommended default). Keep the Handoff Packet in IMPLEMENTATION_LOG.md current
and push it after every WP (A9). Stop early at a clean boundary, not at your
limit, and end the session with the Session Report (D3), whose last item is
the NEXT-SESSION MESSAGE that I will paste into the next chat.
```

## D2. Resume message (the base of the NEXT-SESSION MESSAGE; John pastes it in every later chat; no attachment needed unless `docs/CAMPAIGN_BRIEF.md` is missing from the repo)

```
Resume the RigScript V2 campaign. Token (use only as A7 says): <PASTE TOKEN>
Clone https://github.com/john1183-prog/rigscript.git (branch main), read
docs/CAMPAIGN_BRIEF.md in full, then IMPLEMENTATION_LOG.md ("RESUME HERE" and
"OPEN QUESTIONS"). Verify the handoff against the repo (A6 step 5), run Step 0,
then continue with the next WP. Same rules and the same authorization (push to
main fast-forward only; tags allowed). Work autonomously; ask only when
absolutely necessary (A0). If a HANDOFF PACKET follows this message, it is your
starting briefing; the repo copy is authoritative.
```

## D3. Session Report (end of every session; keep it short)

1. **Done:** WP ids, commit shas, CI run links, tags pushed.
2. **Blocked or reverted:** WP id, error excerpt, what you tried.
3. **Decisions and deviations:** one line each (details are in `V2_DECISIONS.md`).
4. **Risks you want John to know about** (things that compile but you could not reason through fully).
5. **Open questions:** only if absolutely necessary (A0), batched, each with a recommended default.
6. **Latest green APK:** link to the latest Actions run on `main` (John downloads the `app-debug` artifact there).
7. **NEXT-SESSION MESSAGE:** one fenced code block containing the D2 resume message followed by the full Handoff Packet exactly as pushed (state the commit sha it was copied from). John copies this block into a new chat.

## D4. `TEST_CHECKLIST.md` requirements

Written for a phone user who sends results to a verification chat. Number every step. For each step give: what to do, the expected result, what to capture (exact file names or screenshots), which setting to flip if something looks wrong. Required sections, in this order:

0. Install and first launch with an existing project (nothing crashes; old project opens; old script plays).
1. Diagnostics Self-test: audio matrix (fill-in PASS/FAIL table), GLES smoke test, parity frames, and the GLES eligibility result (what `AUTO` chose on this phone and why).
2. Real export on **Canvas** and **GLES** (portrait and landscape; narration + music + SFX; narration only; SFX only); a 3+ minute export for heat, memory and time; dual aspect.
3. Acting A/B on the same project and audio: OFF vs NORMAL vs BOLD (what to watch: lead and follow, settle, soft expression changes, eye movement, calmer mouth); the `act` and `emote` showcase.
4. Motion graphics showcase: kinetic words, behaviors, markers, transitions, emitters, audio-reactive layers (with narration and with music).
5. Validator and repair prompt; prompt pack; review sheet; patch apply and undo.
6. Presets and SRT.
7. **Tuning guide:** which constants in `ActingParams` and the macro parameter objects to change for "more" or "less", how to force Canvas or GLES, and how to tune the `GlesAcceptance` thresholds.
8. **What to send back** for verification (list of files and screenshots).

## D5. Final Report (end of the campaign)

- What shipped per phase (a table: WP → status → CI run → tests → docs).
- What did not ship and why (BLOCKED, deferred, Tier C not reached).
- Every deviation from this brief.
- Known risks, ranked.
- The top 5 things John should test first.
- Link to the latest green APK run and the tag list.

## D6. Decision heuristics (when the brief is silent)

1. Prefer the option that keeps old scripts rendering identically.
2. Prefer the option that keeps the engine deterministic and the renderers dumb.
3. Prefer adding a new file over growing a 90 KB one.
4. Prefer a switch (setting) over a hard change when behaviour is visible and subjective.
5. If two designs are close, take the simpler, log the other under "rejected".
6. If a feature cannot be made safe without a device, ship it default-off, document it, and put it in the checklist.
7. Never trade a test or a doc for another feature.
8. If you are unsure whether something is Direction or Craft, treat it as Direction (opt-in).
9. Before asking John a question, re-read A0: most questions have a safe default you can log and proceed with.
10. If you are not sure you can finish a WP, push it, and write the packet with the budget you have left, do not start it.

---
*End of brief.*

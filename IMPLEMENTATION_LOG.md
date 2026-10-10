# IMPLEMENTATION LOG — RigScript V2 campaign

Authoritative brief: `docs/CAMPAIGN_BRIEF.md` (Part A rules, Part C work packages). This file is the living state (A9). Status labels follow A5: VERIFIED-BY-CI / VERIFIED-BY-TEST / VERIFIED-BY-READING / UNVERIFIED. Nothing here is device-verified.

## RESUME HERE

_Handoff Packet. Rewritten after every WP (A9). Treat it as a claim: verify shas, tags and CI against the repo before acting (A6 step 5)._

**1. STATE**
- Last green commit on `main`: `32b42ce` (WP0.3; VERIFIED-BY-CI run 38070446289: build success, 25 unit tests passed, APK 17.2 MB, 15 Kotlin warnings as at baseline). Earlier green: `3b74f13` (WP0.3a, run 38057935110), `617dbdf` (run 38051291580), `593f257` (run 38051012391), `9d92a45` (run 38028154388), `91c67f6` (run 38028034601), baseline `eb6b0b3` (run 37477158994). The commit carrying this packet is docs-only; its own CI result was not known when it was written.
- Tags: `pre-v2-baseline` (annotated, object `ea9037f` → `eb6b0b3`). Branches: only `main`. `v2-p0` is NOT pushed yet (WP0.4 and WP0.5 remain).
- Latest verified CI/APK run: https://github.com/john1183-prog/rigscript/actions/runs/38070446289 (artifacts `app-debug`, `unit-test-report`, `build-log`).
- Unit tests: 25 (`SanityTest` 2, `TestEnvelopesTest` 6, `FixtureParseTest` 9, `LegacyJsonTest` 8), VERIFIED-BY-CI.
- WPs: 0.1 done · 0.1b done · 0.2 done · 0.3 done · 0.4, 0.5 not started · Phases 1–5 not started.

**2. IN-FLIGHT**
- None. Working tree clean; everything is pushed.

**3. NEXT STEPS**
1. WP0.4 goldens. First read `V2_DECISIONS.md` `## Key architectural facts` and `## Deferred, with rationale` (`grep -n '^## '`), then `PlaybackEngine.kt` (~40 KB), `TimelineCompiler.kt` and `OverlayResolver.kt` (whole functions; `grep -n 'import android'` first). Decide whether `PlaybackEngine` can be built on the JVM (`isReturnDefaultValues = true` only stubs static `android.*` calls; `Paint`, `Canvas` and `Matrix` objects are not usable): if not, extract the minimal pure core (A8) and log it. Dump helper: for each of the 7 fixtures, with `TestEnvelopes.speechLike(...)` as the envelope (silence and noisy for property tests), write snapshots rounded to 1e-4 to `app/build/golden-actual/`. Transport to the sandbox: tests print `CAPTURE|<name>|<gzip+base64 chunk>` lines; `ci_report.py` shows CAPTURE lines up to 40,000 characters in total (raise `captured_output(limit=...)`; the check-run text limit is 65,535 and the report caps at 60,000, so post extra check runs per chunk for more); decode in the sandbox and commit under `app/src/test/resources/golden/`. Compare step with tolerance 1e-3 that passes with a warning when a golden is missing; a comparator unit test that perturbs a copy; property tests (determinism, seek equals sequential within 1e-4, monotone cue order, no NaN or infinity).
2. WP0.5 triage (verify-first list in the brief), then push the annotated tag `v2-p0` and record the I12 launch-safety review.
3. Phase 1 starts with WP1.1 (`engine/ExportAudio.kt`) only after `v2-p0`.

**4. DECISIONS**
- Kickoff decisions are in `V2_DECISIONS.md` ("2026-10-10 — V2 campaign kickoff"), with the OVERRIDDEN annotation and the Craft-vs-Direction amendment.
- CI feedback channel is the check run "CI report" (WP0.2a). Tests can publish data through `CAPTURE|name|payload` stdout lines (WP0.3a). JUnit 4.13.2 through catalog alias `libs.junit`; tests in `app/src/test/java/com/example/`, resources in `app/src/test/resources/`.
- WP0.3: the fixture corpus is generated (`tools/fixtures/gen_fixtures.py`), baseline fields only, never edited for V2 fields (add new fixtures); a seventh fixture covers edge cases. Entry: `V2_DECISIONS.md` "2026-10-10 — WP0.3".

**5. DEVIATIONS**
- `docs/CAMPAIGN_BRIEF.md` was re-typed from the chat (the attachment was not on disk); the real token on its D1 line is replaced by `<PASTE TOKEN>` plus a note because the repo is public. Checked: 12 A-sections, 4 B, 6 D, 39 WPs, 78 KB, no token-like string. Read in full again this session; no contradiction found. If a transcription slip is suspected, ask John to attach the original.
- A7 "Reading CI" (job logs, artifact zips) does not work from the sandbox and is replaced by the CI report check run.
- WP0.2 was done as 0.2a (CI report channel and `.github/scripts/ci_report.py`, not in the brief) and 0.2b (JUnit and the test step).
- WP0.3 was done as 0.3a (throwaway capture test and the `CAPTURE|` channel in `ci_report.py`, commit `3b74f13`) and 0.3b (fixtures and tests, `32b42ce`); seven fixtures instead of the brief's six (the extra one is `07_edge_cases`).

**6. LOOK OUT FOR**
1. CI logs and artifacts cannot be downloaded here: `GET /actions/jobs/<id>/logs` answers 302 to `productionresultssa1.blob.core.windows.net`, which does not resolve. Use the CI report check run (item 2). John can allow `*.blob.core.windows.net` in the sandbox network settings if he wants artifact downloads.
2. Reading CI: poll `GET /repos/john1183-prog/rigscript/actions/runs?head_sha=<sha>` and keep runs with `head_branch == "main"` (tag pushes also start the workflow); then `GET /repos/john1183-prog/rigscript/commits/<sha>/check-runs`, entry `name == "CI report"`: `output.title` is the one-line result, `output.text` has counts, failing tests, compiler errors, "what went wrong", captured test output and the Gradle tail. A run takes about 2.5 to 3.5 minutes; polling every 25 s for up to 200 s per bash call worked.
3. The bash shell state does not persist between calls (files under `/tmp` do) and `/mnt/user-data/uploads` is empty. Put the token inside each command that needs it. Push with `git -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" push origin main` (no remote URL change, nothing on disk). Before every push: `git fetch`, `git merge-base --is-ancestor origin/main HEAD`, and the leak gate `grep -rEl 'github_pat_[A-Za-z0-9_]{20,}' --exclude-dir=.git .` must print nothing (never write the bare token prefix in repo text). Unauthenticated `api.github.com` calls answer 403; always send the token.
4. `V2_DECISIONS.md` is LF. New "What's implemented" entries go immediately before `## AI drives the pipeline`; style is `- **Title ...**` with 2-space continuation lines. Edit it with a Python script that asserts each anchor is unique and opens files with `newline=''`.
5. A Python edit script that fails an anchor assertion aborts before writing, while a shell chain without `set -e` carries on. After every commit chain run `git log --oneline -5` to confirm the commits exist.
6. The repo has no tracked `.gitignore`: never `git add -A`; stage explicit paths. Stack: AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Room 2.6.1, serialization 1.7.3, namespace `com.example`; CI uses checkout@v4, setup-java@v4 (temurin 17), setup-gradle@v4 (Gradle 8.9); baseline has 15 Kotlin warnings.
7. Step 0 reading still pending: `V2_DECISIONS.md` `## Deferred, with rationale` and `## Key architectural facts` (needed for WP0.4; use `grep -n '^## '`), and the `PROMPT_CONSIDERATIONS.md` per-field sections (needed when prompt or schema fields are touched).
8. `bash_tool` runs `/bin/sh` (dash): no `${var:0:7}`, no arrays; use `cut` or start `bash`. A session-local helper `/tmp/ci_poll.py` polled a sha and printed the CI report; recreate it if missing and pass the token only as `TOKEN=... python3 ...`, never inside the file.
9. Test stdout reaches the sandbox only through `CAPTURE|` lines (see NEXT STEPS 1); other output printed by tests is not in the report.
10. `ProjectDef.id` and `lastModifiedMs` default to a random UUID and the clock: always set both in fixtures and tests. Colors in JSON are decimal Longs (for example `4278190335`), never hex strings.
11. Validator facts (VERIFIED-BY-READING, `ScriptValidator.kt`): an event with an unknown pose is skipped entirely; an unknown ease is treated as linear; it warns when two events are within 0.01 s of each other; the overlay overlap check is O(n²) over layers; no check throws (no `!!`). `FixtureParseTest.edgeFixtureTriggersEveryValidatorRule` lists the wording: when WP3.5 changes it, update the test, do not weaken it.
12. JVM-safe and exercised by tests already: `AppJson`, the data classes, `StickFigureRig`, `Expression`, `ScriptValidator`, `BuiltInSoundEffects`. Not yet tried on the JVM: `PlaybackEngine`, `TimelineCompiler`, `OverlayResolver`.

**7. VERIFICATION STATUS**
- VERIFIED-BY-CI: run 38070446289 (build, 25 tests, APK) and run 38057935110 (legacy JSON capture). The legacy JSON is exactly what the baseline classes serialized (read back from the CI report check run). The fixture generator's schema-key check and the envelope thresholds were also run locally in Python (not a Kotlin check). Docs edits VERIFIED-BY-READING. Nothing is device-tested. `TEST_CHECKLIST.md` does not exist yet (first needed with Phase 1).

**8. OPEN QUESTIONS**
- None.

**9. ENVIRONMENT**
- git 2.43.0, OpenJDK 21.0.10, Python 3.12 (numpy, matplotlib, PIL, yaml present), ffmpeg and ffprobe present (re-detected this session). No gradle, sdkmanager or kotlinc; dl.google.com, repo.maven.apache.org and plugins.gradle.org are unreachable, so there is no local compile and CI is the compiler. github.com and authenticated api.github.com work. The token is a fine-grained PAT that John pastes in chat; it can read Actions, push commits and tags, delete branches and update workflow files (all VERIFIED by successful operations). Never write it to disk or print it.

**10. REMINDERS**
- I1 old scripts render identically; I2 determinism and seek-equals-sequential; I3 logic only in the shared engine, renderers only draw; I5 never describe unmerged fields in the prompt and keep the two prompt copies byte-identical; I11 every new engine layer fail-soft. Re-read A3 and A4 before touching engine code. Every commit ends with the A5 Verification footer; no Co-authored-by; commit identity `john1183-prog <john1183-prog@users.noreply.github.com>`.

**11. WHY THE SESSION STOPPED**
- Clean boundary: WP0.3 done and CI-green. By the A9 proxies (many large tool outputs: the 78 KB brief, the schema files, two CI waits) about 60% of the budget is used, and WP0.4 first needs the engine sources read (`PlaybackEngine` ~40 KB, `TimelineCompiler`, `OverlayResolver`) and possibly a pure core extracted, which would not leave the 15% handoff reserve.

## OPEN QUESTIONS

None.

## WP TABLE

| WP | Title | Tier | Status | Commit | CI |
|---|---|---|---|---|---|
| 0.1 | Bootstrap and log | A | done | 91c67f6 | success (run 38028034601) |
| 0.1b | Remove other remote branches | A | done | n/a (remote op) | n/a |
| 0.2 | Test infrastructure and CI | A | done (0.2a CI report, 0.2b JUnit + CI tests) | 9d92a45, 593f257 | success (runs 38028154388, 38051012391) |
| 0.3 | Fixtures | A | done (0.3a legacy JSON capture, 0.3b fixtures and tests) | 3b74f13, 32b42ce | success (runs 38057935110, 38070446289) |
| 0.4 | Legacy protection (goldens) | A | not started | | |
| 0.5 | Triage and small fixes | A | not started | | |
| 1.1–1.7 | Export audio and GLES, parity, diagnostics | A | not started | | |
| 2.1–2.7 | Acting layer | A | not started | | |
| 3.1, 3.2, 3.3, 3.5, 3.7 (overlays), 3.8 | Kinetic text, behaviors, markers, validator, audio-reactive | A | not started | | |
| 3.4, 3.7 (camera, music source) | Transitions and emitters; camera reactivity | B | not started | | |
| 3.6 | Chart and counter primitives | C | not started | | |
| 4.1–4.7 | Director loop (prompt pack, dialects, rewrite, review sheet, patch) | B | not started | | |
| 5.1 | Export presets and SRT | B | not started | | |
| 5.2, 5.3 | Hands and feet; second-speaker design note | C | not started | | |
| 5.4 | Final documentation | A | not started | | |

## LOG

### 2026-10-10 — Session 1

- **Step 0 (A6):** capabilities detected (see ENVIRONMENT). Cloned `main` at `eb6b0b3`; `git log` matches the brief. `GEMINI.md` read in full (1.6 KB). `V2_DECISIONS.md`: read History, the tail of What's implemented, AI drives the pipeline, On the horizon and Explicitly rejected; Deferred and Key architectural facts not yet read.
- **WP0.1b:** `git ls-remote --heads origin` showed `main` (`eb6b0b3`) and `version2` (tip `72344950b2509f51a13874df391c82db54d00f68`). `git rev-list --count origin/main..origin/version2` = 0 and `merge-base --is-ancestor` true, so nothing unique was on it. Deleted with `git push origin --delete version2`. Afterwards `git ls-remote --heads --tags origin` listed only `main` and the tag `pre-v2-baseline`. VERIFIED.
- **WP0.1:** annotated tag `pre-v2-baseline` pushed (object `ea9037f`, target `eb6b0b3`). Added `docs/CAMPAIGN_BRIEF.md` (token redacted, see DEVIATIONS), this log and a short `CLAUDE.md`. Added the kickoff entry, the OVERRIDDEN annotation and the Craft-vs-Direction amendment to `V2_DECISIONS.md` (+41 lines, no deletions).
- **WP0.1 CI:** push `eb6b0b3..91c67f6` (commits `dcd2c85`, `3c916ad`, `91c67f6`): run 38028034601 completed success, every step green, APK uploaded. VERIFIED-BY-CI.
- **Incident (no impact):** a pre-commit scan printed one prefix match before I pushed. It was only my own log sentence naming the token prefix; a regex for real token-shaped strings found none in the worktree or in git history. The sentence is reworded and the gate now blocks pushes on token-shaped strings.
- **WP0.2a:** the sandbox cannot download job logs (redirect host does not resolve). Added `.github/scripts/ci_report.py` and a workflow that tees Gradle output to `build.log`, publishes a check run named "CI report" (title, counts, compiler errors, failing tests, log tail) and uploads `build-log`. Script dry-run and YAML parse checked locally. Result of the real run: see next entry.
- **WP0.2a result:** push `91c67f6..9d92a45`: run 38028154388 success. The check run "CI report" (id 114143526652) is readable through `commits/<sha>/check-runs`: title "build SUCCESS | no unit-test results", APK 17.2 MB, 15 Kotlin warnings, Gradle tail. Updating a workflow file with the token worked. VERIFIED-BY-CI.
- **WP0.2b:** JUnit 4.13.2 added through `gradle/libs.versions.toml` (`libs.junit`), `testOptions { unitTests.isReturnDefaultValues = true }` in `app/build.gradle.kts`, `app/src/test/java/com/example/SanityTest.kt` (2 tests; one proves the Log stub returns defaults), workflow runs `gradle testDebugUnitTest assembleDebug` and uploads `unit-test-report` with `if: always()`.
- **WP0.2b result:** push `9d92a45..593f257`: run 38051012391 success; CI report title "build SUCCESS | tests 2 run, 0 failed, 0 errors, 0 skipped"; APK 17.2 MB. VERIFIED-BY-CI. WP0.2 accepted.
- **Incident (no impact):** my first log-update script for WP0.2 aborted on a failed anchor assertion before writing, so the intended log commit was empty and silently skipped; only the two code commits were pushed. Redone in this commit.
- **Session 1 stop:** clean boundary after WP0.2 (see packet section 11). Phase 0 continues with WP0.3.

### 2026-10-10 — Session 2

- **Resume:** packet verified against the repo (A6 step 5): HEAD `617dbdf` = origin/main; tag `pre-v2-baseline` annotated, `ea9037f` → `eb6b0b3`; only branch `main`; run 38051012391 (`593f257`) and run 38051291580 (`617dbdf`, the docs-only packet commit) both success with CI report "tests 2 run". Discrepancy: none (the packet's open point about the docs commit's CI is resolved: green). Environment re-detected, identical. Brief, `GEMINI.md`, `CLAUDE.md` and this log read. Step 0 reading of `V2_DECISIONS.md` Deferred and Key architectural facts and of the `PROMPT_CONSIDERATIONS.md` per-field sections is still pending (WP0.3 touches no engine or prompt).
- **WP0.3a:** `LegacyJsonCaptureTest` (throwaway) printed `CAPTURE|name|json` for the four baseline classes; `ci_report.py` gained `captured_output()` (CAPTURE lines from the JUnit XML system-out, 40,000-character cap) and a "Captured test output" section; dry-run locally with a fake XML. Push `617dbdf..3b74f13`: run 38057935110 success (tests 3 run). The JSON was read from the check run through the API and written byte-exact to `fixtures/legacy_json/` (appearance 916 characters and 36 keys, export 132 and 7, music 70 and 4, project 6,028 and 15). VERIFIED-BY-CI.
- **WP0.3b:** seven fixtures and their generator, `Fixtures`/`TestEnvelopes` helpers, three test classes; throwaway test removed. Push `3b74f13..32b42ce`: run 38070446289 success on the first attempt; CI report "tests 25 run, 0 failed, 0 errors, 0 skipped", APK 17.2 MB, 15 Kotlin warnings (unchanged). VERIFIED-BY-CI. WP0.3 accepted ("fixtures load through the real parser in a test").
- **Session 2 stop:** clean boundary after WP0.3 (see packet section 11). Phase 0 continues with WP0.4.

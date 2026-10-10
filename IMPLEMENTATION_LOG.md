# IMPLEMENTATION LOG — RigScript V2 campaign

Authoritative brief: `docs/CAMPAIGN_BRIEF.md` (Part A rules, Part C work packages). This file is the living state (A9). Status labels follow A5: VERIFIED-BY-CI / VERIFIED-BY-TEST / VERIFIED-BY-READING / UNVERIFIED. Nothing here is device-verified.

## RESUME HERE

_Handoff Packet. Rewritten after every WP (A9). Treat it as a claim: verify shas, tags and CI against the repo before acting (A6 step 5)._

**1. STATE**
- Last green commit on `main`: `593f257` (WP0.2b; VERIFIED-BY-CI run 38051012391: build success, 2 unit tests passed, APK 17.2 MB). Earlier green: `9d92a45` (run 38028154388), `91c67f6` (run 38028034601), baseline `eb6b0b3` (run 37477158994). The commit carrying this packet is docs-only; its own CI result was not known when it was written.
- Tags: `pre-v2-baseline` (annotated, object `ea9037f` → `eb6b0b3`). Branches: only `main` (`version2` deleted). `v2-p0` is NOT pushed yet (Phase 0 incomplete).
- Latest verified CI/APK run: https://github.com/john1183-prog/rigscript/actions/runs/38051012391 (artifacts `app-debug`, `unit-test-report`, `build-log`).
- Unit tests: 2 (`SanityTest`), VERIFIED-BY-CI.
- WPs: 0.1 done · 0.1b done · 0.2 done · 0.3, 0.4, 0.5 not started · Phases 1–5 not started.

**2. IN-FLIGHT**
- None. Working tree clean; everything is pushed.

**3. NEXT STEPS**
1. WP0.3 fixtures. First read `app/src/main/java/com/example/data/AnimScript.kt`, `AppJson.kt`, `ExportSettings.kt`, `AppearanceSettings.kt`, `BackgroundMusicSettings.kt`, `ProjectDef` (grep for it) and the per-field sections of `PROMPT_CONSIDERATIONS.md` (`grep -n '^## '`). Add 6 scripts under `app/src/test/resources/fixtures/` and the legacy JSON of the four settings classes under `fixtures/legacy_json/`, captured BEFORE any field is added (a throwaway test prints `AppJson.storage.encodeToString(...)` of the defaults; read it from the CI report, extending `.github/scripts/ci_report.py` to include the test stdout if needed). Test: every fixture parses through the real parser.
2. WP0.4 goldens. Artifacts cannot be downloaded here (LOOK OUT FOR 1). Plan: extend `ci_report.py` to post gzip+base64 chunks (each under 60,000 characters) of `app/build/golden-actual/` as extra check runs, decode them in the sandbox, commit them as `app/src/test/resources/golden/`. Read `V2_DECISIONS.md` `## Key architectural facts` and `## Deferred` first; check whether `PlaybackEngine` can be built on the JVM, else extract a pure core (A8).
3. WP0.5 triage (verify-first list in the brief), then push the annotated tag `v2-p0` and record the I12 launch-safety review.

**4. DECISIONS**
- Kickoff decisions are in `V2_DECISIONS.md` ("2026-10-10 — V2 campaign kickoff"), with the OVERRIDDEN annotation and the Craft-vs-Direction amendment.
- CI feedback channel is the check run "CI report" (WP0.2a). JUnit 4.13.2 through catalog alias `libs.junit`; tests live in `app/src/test/java/com/example/`.

**5. DEVIATIONS**
- `docs/CAMPAIGN_BRIEF.md` was re-typed from the chat (the attachment was not on disk); the real token on its D1 line is replaced by `<PASTE TOKEN>` plus a note because the repo is public. Checked: 12 A-sections, 4 B, 6 D, 39 WPs, 78 KB, no token-like string. If a transcription slip is suspected, ask John to attach the original.
- A7 "Reading CI" (job logs, artifact zips) does not work from the sandbox and is replaced by the CI report check run.
- WP0.2 was done as 0.2a (CI report channel and `.github/scripts/ci_report.py`, not in the brief) and 0.2b (JUnit and the test step).

**6. LOOK OUT FOR**
1. CI logs and artifacts cannot be downloaded here: `GET /actions/jobs/<id>/logs` answers 302 to `productionresultssa1.blob.core.windows.net`, which does not resolve. Use the CI report check run (item 2). John can allow `*.blob.core.windows.net` in the sandbox network settings if he wants artifact downloads.
2. Reading CI: poll `GET /repos/john1183-prog/rigscript/actions/runs?head_sha=<sha>` and keep runs with `head_branch == "main"` (tag pushes also start the workflow); then `GET /repos/john1183-prog/rigscript/commits/<sha>/check-runs`, entry `name == "CI report"`: `output.title` is the one-line result, `output.text` has counts, failing tests, compiler errors, "what went wrong" and the Gradle tail. A build takes about 3.5 minutes; polling every 25 s for up to 200 s per bash call worked.
3. The bash shell does not persist between calls and `/mnt/user-data/uploads` is empty. Put the token inside each command that needs it. Push with `git -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" push origin main` (no remote URL change, nothing on disk). Before every push: `git fetch`, `git merge-base --is-ancestor origin/main HEAD`, and the leak gate `grep -rEl 'github_pat_[A-Za-z0-9_]{20,}' --exclude-dir=.git .` must print nothing (never write the bare token prefix in repo text). Unauthenticated `api.github.com` calls answer 403; always send the token.
4. `V2_DECISIONS.md` is LF. New "What's implemented" entries go immediately before `## AI drives the pipeline`; style is `- **Title ...**` with 2-space continuation lines. Edit it with a Python script that asserts each anchor is unique and opens files with `newline=''`.
5. A Python edit script that fails an anchor assertion aborts before writing, while a shell chain without `set -e` carries on. After every commit chain run `git log --oneline -5` to confirm the commits exist (this session one log commit was silently skipped).
6. The repo has no tracked `.gitignore`: never `git add -A`; stage explicit paths. Stack: AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Room 2.6.1, serialization 1.7.3, namespace `com.example`; CI uses checkout@v4, setup-java@v4 (temurin 17), setup-gradle@v4 (Gradle 8.9); baseline has 15 Kotlin warnings.
7. Step 0 reading still pending: `V2_DECISIONS.md` `## Deferred, with rationale` and `## Key architectural facts` (line numbers shifted by +41 after my edits; use `grep -n '^## '`), and the `PROMPT_CONSIDERATIONS.md` per-field sections.

**7. VERIFICATION STATUS**
- VERIFIED-BY-CI: run 38051012391 (build, 2 tests, APK). CI report channel VERIFIED by API reads (runs 38028154388 and 38051012391). Tag and branch deletion VERIFIED by `git ls-remote`. Docs edits VERIFIED-BY-READING. Nothing is device-tested. `TEST_CHECKLIST.md` does not exist yet (first needed with Phase 1).

**8. OPEN QUESTIONS**
- None.

**9. ENVIRONMENT**
- git 2.43.0, OpenJDK 21, Python 3.12.3 (numpy, matplotlib, PIL, yaml present), ffmpeg and ffprobe present. No gradle, sdkmanager or kotlinc; dl.google.com, repo.maven.apache.org and plugins.gradle.org are unreachable, so there is no local compile and CI is the compiler. github.com and authenticated api.github.com work. The token is a fine-grained PAT that John pastes in chat; it can read Actions, push commits and tags, delete branches and update workflow files (all VERIFIED by successful operations). Never write it to disk or print it.

**10. REMINDERS**
- I1 old scripts render identically; I2 determinism and seek-equals-sequential; I3 logic only in the shared engine, renderers only draw; I5 never describe unmerged fields in the prompt and keep the two prompt copies byte-identical; I11 every new engine layer fail-soft. Re-read A3 and A4 before touching engine code. Every commit ends with the A5 Verification footer; no Co-authored-by; commit identity `john1183-prog <john1183-prog@users.noreply.github.com>`.

**11. WHY THE SESSION STOPPED**
- Clean boundary: WP0.2 done and CI-green. Re-typing the 78 KB brief into the repo and building the CI-report workaround used a large share of the budget, and WP0.3 needs reading several large schema files, so I did not start it without the 15% handoff reserve.

## OPEN QUESTIONS

None.

## WP TABLE

| WP | Title | Tier | Status | Commit | CI |
|---|---|---|---|---|---|
| 0.1 | Bootstrap and log | A | done | 91c67f6 | success (run 38028034601) |
| 0.1b | Remove other remote branches | A | done | n/a (remote op) | n/a |
| 0.2 | Test infrastructure and CI | A | done (0.2a CI report, 0.2b JUnit + CI tests) | 9d92a45, 593f257 | success (runs 38028154388, 38051012391) |
| 0.3 | Fixtures | A | not started | | |
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

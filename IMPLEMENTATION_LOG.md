# IMPLEMENTATION LOG — RigScript V2 campaign

Authoritative brief: `docs/CAMPAIGN_BRIEF.md` (Part A rules, Part C work packages). This file is the living state (A9). Status labels follow A5: VERIFIED-BY-CI / VERIFIED-BY-TEST / VERIFIED-BY-READING / UNVERIFIED. Nothing here is device-verified.

## RESUME HERE

_Handoff Packet. Rewritten after every WP (A9). Treat it as a claim: verify shas, tags and CI against the repo before acting (A6 step 5)._

**1. STATE**
- Last green commit on `main`: `91c67f6` (WP0.1 bootstrap; VERIFIED-BY-CI run 38028034601). Baseline `eb6b0b3` was green in run 37477158994. The commit carrying this packet is pending CI.
- Tags pushed: `pre-v2-baseline` (annotated, object `ea9037f` → `eb6b0b3`). Other remote branches: none (`version2` deleted, see LOG).
- Latest CI run / APK run: bootstrap run 38028034601 (https://github.com/john1183-prog/rigscript/actions/runs/38028034601), APK artifact `app-debug` in that run. Baseline run 37477158994.
- Unit tests: 0 (no test infrastructure yet).
- WPs: 0.1 done; 0.1b done; 0.2 in progress (0.2a is pushing the CI report channel, 0.2b tests next); everything else not started.

**2. IN-FLIGHT**
- None uncommitted. WP0.2 is next and is split in two steps (see NEXT STEPS).

**3. NEXT STEPS**
1. WP0.2a, CI feedback channel (new, see LOOK OUT FOR 1): add a CI step that publishes a "CI report" check run (Gradle error digest, test counts) readable through `GET /repos/john1183-prog/rigscript/commits/<sha>/check-runs`; script in `.github/scripts/ci_report.py`, workflow `permissions: contents: read, checks: write`. Push with only this change, confirm the check run is readable.
2. WP0.2b, test infrastructure: JUnit 4.13.2 via `gradle/libs.versions.toml`, `testOptions { unitTests.isReturnDefaultValues = true }` in `app/build.gradle.kts`, `app/src/test/java/com/example/SanityTest.kt`, CI runs `gradle testDebugUnitTest assembleDebug`, upload `app/build/reports/tests` with `if: always()`.
3. WP0.3 fixtures: read `AnimScript.kt`, `AppJson.kt`, the settings classes and `PROMPT_CONSIDERATIONS.md` per-field sections first; capture legacy JSON before touching any settings class.

**4. DECISIONS**
- Kickoff decisions recorded in `V2_DECISIONS.md` ("2026-10-10 — V2 campaign kickoff"); the amplitude-reactive rejection is annotated OVERRIDDEN and the "AI drives the pipeline" paragraph is amended (Craft vs Direction).

**5. DEVIATIONS**
- `docs/CAMPAIGN_BRIEF.md` was re-typed from the chat context (the attachment was not on disk) and the real GitHub token on the D1 kickoff line is replaced by `<PASTE TOKEN>` plus a note, because the repo is public. Structure checked: 12 A-sections, 4 B, 6 D, 39 WPs, 78 KB, no token-like string (checked with a regex for the token prefix plus 20+ characters). If a transcription slip is suspected, ask John to attach the original.
- A7 "Reading CI" (job logs, artifact zips) does not work from the sandbox; WP0.2a adds a replacement channel.

**6. LOOK OUT FOR**
1. **CI logs and artifacts cannot be downloaded from the sandbox.** `GET /actions/jobs/<id>/logs` answers 302 to `productionresultssa1.blob.core.windows.net`, which does not resolve here. Job/step status, check runs and annotations on `api.github.com` do work. John can allow `*.blob.core.windows.net` (and `pipelines.actions.githubusercontent.com`) in the sandbox network settings. This also affects WP0.4 (downloading the `golden-actual` artifact): plan another transport (check-run text, or release assets via `release-assets.githubusercontent.com`, which is allowed).
2. **The bash shell does not persist between calls** (new pid, env vars lost). Supply the token inside each command that needs it; push with `git -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" push ...` (no remote URL rewrite needed; nothing is written to disk). This worked for a tag push and a branch delete.
3. `/mnt/user-data/uploads` was empty: attachments are not mirrored to disk.
4. Unauthenticated `api.github.com` HEAD returned 403; authenticated calls work.
5. The repo has no tracked `.gitignore` (GEMINI.md): never `git add -A`; stage explicit paths.
6. CI today: `.github/workflows/build.yml`, `on: [push]`, actions/checkout@v4, setup-java@v4 (temurin 17), `gradle/actions/setup-gradle@v4` with Gradle 8.9, `gradle assembleDebug`, upload artifact `app-debug`. Catalog: AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Room 2.6.1, serialization 1.7.3. Namespace `com.example`. No test dependencies yet.
7. `V2_DECISIONS.md` is LF. New "What's implemented" entries go immediately before `## AI drives the pipeline`; style is `- **Title ...**` with 2-space continuation lines.
8. Before every push run the leak gate and require empty output: `grep -rEl 'github_pat_[A-Za-z0-9_]{20,}' --exclude-dir=.git .` (do not write the bare token prefix in repo text; a plain-prefix grep gives false alarms).
9. Step 0 reading still pending: `V2_DECISIONS.md` sections `## Deferred, with rationale` and `## Key architectural facts`, `PROMPT_CONSIDERATIONS.md` per-field sections. Read them before engine work (WP0.4 and later).

**7. VERIFICATION STATUS**
- Baseline CI green: VERIFIED-BY-CI (run 37477158994). Tag and branch deletion: VERIFIED by `git ls-remote`. Docs edits: VERIFIED-BY-READING. No tests, no device. `TEST_CHECKLIST.md` does not exist yet.

**8. OPEN QUESTIONS**
- None.

**9. ENVIRONMENT**
- git 2.43.0, OpenJDK 21, Python 3.12.3 (numpy, matplotlib, PIL present), ffmpeg and ffprobe present. No gradle, sdkmanager or kotlinc; dl.google.com, repo.maven.apache.org and plugins.gradle.org unreachable, so there is no local compile and CI is the compiler. github.com and authenticated api.github.com reachable. The token is a fine-grained PAT given by John in chat; it can read Actions, push tags and delete branches; permission to push workflow files was untested at the time of writing. Never write it to disk or output.

**10. REMINDERS**
- I1 old scripts render identically; I2 determinism and seek-equals-sequential; I3 logic only in the shared engine, renderers only draw; I5 never describe unmerged fields in the prompt and keep the two prompt copies byte-identical; I11 every new engine layer fail-soft. Re-read A3 and A4 before touching engine code. Every commit ends with the A5 Verification footer; no Co-authored-by.

**11. WHY THE SESSION STOPPED**
- Not stopped (in progress).

## OPEN QUESTIONS

None.

## WP TABLE

| WP | Title | Tier | Status | Commit | CI |
|---|---|---|---|---|---|
| 0.1 | Bootstrap and log | A | done | 91c67f6 | success (run 38028034601) |
| 0.1b | Remove other remote branches | A | done | n/a (remote op) | n/a |
| 0.2 | Test infrastructure and CI | A | in progress (0.2a CI report; 0.2b tests next) | | |
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

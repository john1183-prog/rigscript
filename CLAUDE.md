# CLAUDE.md — start here (Claude sessions on RigScript)

RigScript is an offline Android app (Kotlin, Jetpack Compose) for John (github.com/john1183-prog). A multi-session "V2 campaign" is in progress.

Read in this order:
1. `GEMINI.md` — project rules that still bind you.
2. `docs/CAMPAIGN_BRIEF.md` — the full brief (Part A operating rules, Part C work packages).
3. `IMPLEMENTATION_LOG.md` — "RESUME HERE" and "OPEN QUESTIONS". Verify it against `git log`, `git tag` and CI before trusting it.

Hard rules (details in the brief):
- Commit and push as `john1183-prog <john1183-prog@users.noreply.github.com>`. No Claude or bot identity, no `Co-authored-by`.
- Pushes to `main` are fast-forward only. Never force-push, never rewrite history, never delete tags.
- Never touch `app/debug.keystore` or its signing config. Never `git add -A`: stage explicit paths (GEMINI.md lists untracked files that must stay unstaged).
- Old scripts must render identically; new schema fields are optional with legacy defaults. Preview and export must match.
- Never describe unmerged features as fields in the AI prompt. `app/src/main/assets/prompt/system_prompt.txt` and the prompt block in `PROMPT_CONSIDERATIONS.md` stay byte-identical (`cmp`).
- No local LLM, no cloud call from the app, offline only.
- Be honest: nothing is "device-verified" until John says so. Every commit message ends with `Verification: CI <run id|pending>; tests <names|none>; device: NOT TESTED`.
- CI is the compiler (the sandbox has no Android SDK). Push, wait for the run, read the result through the GitHub API, never leave `main` red.
- Re-read A3 (invariants) and A4 (Craft vs Direction) before touching engine code.

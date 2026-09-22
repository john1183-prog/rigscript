# RigScript Project Rules & Workflow Guidelines

## 1. Git & Remote Push Invariants
- **NEVER push to remote without explicit user authorization.** Running commits or verifications never implies permission to push.
- Keep commits atomic, minimal, and focused on approved changes.
- Always run `git diff --check` and inspect `git status` before committing.
- Do not stage the currently known untracked files (`.gitignore`, `.kotlin/`, `gradle/wrapper/`, `gradlew`, `gradlew.bat`) unless explicitly instructed to do so.

## 2. Workflow & Roles
- **ChatGPT → Gemini Pairing**: High-level architecture, design specifications, and prompt iterations are developed with ChatGPT; Gemini implements, tests, and verifies strictly against the codebase.
- Maintain a diagnosis-first approach: inspect and report before modifying files.
- Adhere strictly to doc-only, implementation-only, or verification-only boundaries per user prompt.

## 3. Verification Standards
- Visual confirmation of renders and exports must be grounded in pixel measurements (using `ffmpeg` and Python/PIL), not visual estimation or static assumptions.
- Confirm both Canvas and GLES parity across orientations (9:16 portrait and 16:9 landscape).

## 4. Documentation & AI Prompt Discipline
- Never introduce or describe planned, unmerged features as valid schema fields in AI prompts.
- `app/src/main/assets/prompt/system_prompt.txt` and the prompt block in `PROMPT_CONSIDERATIONS.md` must remain strictly byte-identical.
- Adhere to the closed-vocabulary rule for poses, scene shapes, atmospheres, and easing types.

This file is the canonical text served by the in-app "Copy AI Prompt"
button (MainViewModel.buildPromptForClipboard).

It is a DELIBERATE DUPLICATE of the code block inside
PROMPT_CONSIDERATIONS.md's "Final consolidated prompt" section at the
repo root. There's no build-time step that generates one from the other —
keeping them in sync is a manual discipline, not an enforced invariant.

If you edit the prompt, edit BOTH:
  1. This file (what the app actually copies to the clipboard)
  2. PROMPT_CONSIDERATIONS.md's fenced code block (the human-readable
     reference copy, surrounded by the reasoning notes explaining why
     each part of the prompt is written the way it is)

This was a scoping call, not an oversight: a codegen step to keep a
Markdown doc and an app asset in sync is more tooling than a single
prompt file justifies right now. If the prompt starts changing often
enough that manual sync becomes error-prone, that's the signal to build
the codegen step, not before.

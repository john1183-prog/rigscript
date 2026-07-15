# RigScript — AI Script-Generation Prompt Considerations

This tracks what the AI-script-generation prompt needs to communicate
about `AnimScript`'s JSON shape, and — just as importantly — what it
should deliberately NOT ask the AI to produce. Like `V2_DECISIONS.md`,
this is being rewritten after the first version was lost in an unpushed
sandbox-reset session; see that file's History section for what
happened.

RigScript's core premise is that an external AI model generates the
entire timestamped JSON script from narration audio, and the app renders
it without manual editing steps. That means the prompt IS the primary
authoring interface — a bad prompt produces a bad video with no manual
recourse beyond hand-editing raw JSON in the Script tab. Prompt quality
matters as much as renderer correctness.

## Per-field guidance

### `pose` / timing (`timeSec`, `duration`)
- **Density heuristic**: a pose change roughly every 1.5–3 seconds reads
  as natural narration-synced motion; much denser looks twitchy, much
  sparser looks frozen/dead air. The AI should be told to target this
  range, not left to infer it from timestamps alone.
- **Arrival-timing guidance**: a pose's `timeSec` should land where the
  audio content actually changes emphasis (a new clause, a gesture word
  like "and then," a change in vocal energy) — not evenly spaced by the
  clock. Evenly-spaced poses ignoring content is a common failure mode
  worth calling out explicitly in the prompt.
- **Energy arc structure**: narration has a shape (build, peak, release)
  and pose selection should track it — bigger/more expansive poses at
  emphasis points, smaller/settled poses during lower-energy stretches.
  The prompt should ask for this arc explicitly rather than assuming the
  AI infers it from amplitude alone (the app deliberately doesn't derive
  pose or camera behavior from amplitude — see "AI drives the pipeline"
  in V2_DECISIONS.md — so if the arc isn't in the JSON, it isn't in the
  video).
- **Two-tier anchor/accent pattern**: distinguish "anchor" poses (the
  figure's resting/default state between emphasis points) from "accent"
  poses (brief, purposeful gestures at specific words). Asking for this
  distinction explicitly produces better pacing than asking generically
  for "poses that match the narration."
- **Walk-cycle rules**: if narration describes movement/journey, walk-
  cycle poses should alternate consistently (not randomly re-picked each
  step) and their duration should roughly match a plausible stride
  cadence, not be arbitrary.

### `expression`
- Snap semantics, carry-forward — an expression holds until explicitly
  changed. The prompt should ask for expression changes at genuine
  emotional beats, not on every event (that would fight the carry-
  forward design and produce flicker).
- Eyebrows only render for `WORRIED`/`ANGRY` — the AI doesn't need to
  reason about eyebrows separately from expression choice.

### `cameraZoom` / `cameraPanX` / `cameraPanY` / `cameraShake`
- Carry-forward for zoom/pan, one-shot for shake.
- These are purely AI-driven with no automatic fallback — if the prompt
  doesn't ask for camera direction, the camera simply never moves. Worth
  being explicit in the prompt that camera work is opt-in per script,
  not something the renderer will add on its own.
- Shake should be reserved for genuine impact moments (the prompt should
  give 2-3 concrete trigger examples) — overuse reads as jittery/broken
  rather than impactful.

### `caption` / `captionDurationSec`
- Bounded-window, not carry-forward — each caption is its own
  self-contained cue. The prompt should ask for one caption per
  distinct spoken beat, with a duration that roughly matches how long
  that beat takes to say, not a single caption meant to persist across
  multiple unrelated later events.
- Not every event needs a caption — captions should be reserved for
  moments where on-screen text adds value (key terms, quotes, numbers)
  rather than transcribing the entire narration line-by-line.

### `skyColor` / `groundColor` / `horizonY` / `sceneShape` /
`sceneAtmosphere`
- Carry-forward — the AI should only emit these on events where the
  scene actually changes, not on every event.
- The AI does NOT need to reason about color-clash safety —
  `RigRenderer.constrainSceneColor()` enforces hue separation and
  saturation caps in code regardless of what's requested. The prompt
  can ask for color intent freely (e.g. "warm sunset tones") without
  needing hex-level precision or figure-color awareness.
- `sceneShape`/`sceneAtmosphere` values must be one of the string
  constants in `engine/Scene.kt` (`none|mountains|city|trees|clouds` and
  `none|rain|snow|fog|stars` respectively) — the prompt should enumerate
  these explicitly rather than let the AI invent new values, since
  `fromString()` silently falls back to `NONE` for anything unrecognized
  (a deliberate graceful-degradation choice, but one that means a typo'd
  value produces a silent no-op, not an error the AI could learn from).

## Explicit exclusions — never prompt for these

- **Reference overlay** (`ReferenceOverlay`) is manual and
  one-time-configured by the user in the Appearance tab. It is never
  read by `TimelineCompiler` and must never be part of the AI's output
  schema — there's no field for it in `ScriptEvent`, and it should stay
  that way. If a future feature wants the AI to reason about on-screen
  reference material, that's a different, new field — not an extension
  of this one.
- **Tempo multiplier** — doesn't exist (rejected, see V2_DECISIONS.md);
  the AI's own timestamps are the sole source of pacing.

## Structural risks to watch for

- **Timeline conflicts**: two events at (or very near) the same
  `timeSec` produce undefined-feeling behavior since `TimelineCompiler`
  processes events in sorted order — the later one in sort order wins
  and the earlier one's transition is effectively skipped. The prompt
  should ask for meaningfully distinct timestamps, and the app-side
  validation (Script tab's error display) is the backstop, not a
  substitute for good prompt guidance.
- **Pose descriptions drifting from shipped angles**: `StickFigureRig.kt`
  pose definitions get retuned over time (e.g. the `think`/`point_self`
  angle fix, verified via the Python FK proxy renderer). Any prompt text
  that describes what a named pose looks like needs to be checked
  against the actual current pose set when either changes — a prompt
  describing a pose that no longer matches its FK angles will
  systematically mislead the AI's pose choices for that pose.
- **Don't get ahead of what's actually shipped**: this file and
  `V2_DECISIONS.md` should only describe fields and behavior that are
  actually on `main`, not aspirational/planned fields. The prior loss of
  the first version of these docs (see History in V2_DECISIONS.md) was
  compounded by a handoff document describing unshipped work as
  complete — the fix isn't just "write it down," it's "verify against
  the actual repo before writing it down."

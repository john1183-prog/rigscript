# RigScript — AI Script-Generation Prompt Considerations

## Final consolidated prompt (ready to use)

Everything below this section is engineering notes — the reasoning behind
each field, kept for future maintainers. This section is the actual text
to hand the external AI model as its system prompt, paired with a timed
voiceover transcript as the user message for each video.

**Correction from the previous version of this section**: it had the
top-level JSON shape wrong (a bare array) and omitted `blinkEvents`
entirely. Both are fixed below, checked directly against
`AnimScript.kt`/`ScriptEvent`'s actual doc comments and the real pose
list in `StickFigureRig.kt` rather than reconstructed from memory — the
same "verify against the repo, don't trust an earlier draft" discipline
this project has needed more than once already (see `V2_DECISIONS.md`'s
History section).

It's scoped deliberately to the JSON script only — it does NOT cover
figure appearance (colors, outline, head size, glow, etc.). Those are
manual per-project settings in the Appearance tab and are never something
the AI reasons about. See "Explicit exclusions" below for why that
boundary is enforced, not just a convenience.

```
You generate a timestamped JSON animation script for RigScript, an app
that renders a stick-figure animation synced to narration audio with zero
manual editing after your output. Your JSON is the only creative input a
human reviews before export — treat every timing and framing choice as
final, not a rough draft. This must work for ANY topic: educational,
narrative, religious teaching, product explainer, whatever the transcript
covers. Nothing below is topic-specific; apply the same craft regardless
of subject matter.

═══════════════════════════════════════════════════════════════════════
INPUT YOU'LL RECEIVE
═══════════════════════════════════════════════════════════════════════
A timed voiceover transcript: the spoken text broken into segments, each
with a start time in seconds (and ideally an end time or the total audio
duration). Timestamps may be per-sentence, per-phrase, or per-word —
work with whatever granularity you're given. If no explicit end time is
given for the last segment, infer a reasonable one from speaking pace
and hold the final pose there. If you are not given a total duration
separately, use the last segment's end time as the video's end.

═══════════════════════════════════════════════════════════════════════
OUTPUT FORMAT — return exactly this JSON shape, nothing else
═══════════════════════════════════════════════════════════════════════
No markdown code fences, no commentary before or after — the raw JSON
object only.

{
  "version": "1.0",
  "events": [ /* array of event objects, sorted by timeSec — see below */ ],
  "blinkEvents": [ /* array of numbers — seconds where a DELIBERATE
                       dramatic blink fires, e.g. right before a key line
                       or on a reaction beat. Separate from natural idle
                       blinking, which happens automatically and needs no
                       entry here. Use sparingly — a handful across a
                       whole video, not one per event. */ ]
}

Each object in "events":
{
  "timeSec": number,            // REQUIRED. Absolute seconds from video start.
  "pose": "string",             // REQUIRED. One of the exact pose ids below.
  "duration": number,           // seconds the transition INTO this pose takes. Default 0.5.
  "ease": "string",             // one of the exact ease ids below. Default "ease_in_out".
  "springStiffness": number,    // only meaningful when ease == "spring". Default 280. Higher = snappier.
  "springDamping": number,      // only meaningful when ease == "spring". Default 28. Higher = less oscillation.
  "expression": "string" | null,
  "cameraZoom": number | null,
  "cameraPanX": number | null,
  "cameraPanY": number | null,
  "cameraShake": number | null,
  "caption": "string" | null,
  "captionDurationSec": number,
  "skyColor": number | null,
  "groundColor": number | null,
  "horizonY": number | null,
  "sceneShape": "string" | null,
  "sceneAtmosphere": "string" | null,
  "soundEffect": "string" | null,
  "soundEffectVolume": number
}

Only "timeSec" and "pose" are required per event. Omit any field you're
not setting rather than repeating the previous value — see CARRY-FORWARD
below for why that matters.

═══════════════════════════════════════════════════════════════════════
EXACT VALID VALUES — using anything outside these lists either gets
silently ignored (fields with graceful-degradation) or breaks rendering
(pose). Never invent new values.
═══════════════════════════════════════════════════════════════════════
pose (REQUIRED, must be exact — unrecognized pose is skipped entirely,
      silently dropping that event):
  stand_straight, wave, think, explain, walk_a, walk_b, jog_a, jog_b,
  jump, tired, lazy, sleepy, confused, excited, shrug, point_right,
  point_left, point_up, celebrate, sit, present, point_self, open_hands

  walk_a/walk_b and jog_a/jog_b are stride pairs — alternate between the
  two for a walk/jog cycle, don't repeat one.

ease: linear | ease_in | ease_out | ease_in_out | bounce | elastic_out |
      spring | rigid
  "rigid" snaps instantly with NO interpolation — reserve for genuinely
  abrupt/mechanical moments. Every other ease type ALREADY gets a
  physics spring-chase layered on top by default app-wide — you do not
  need to specify "spring" to get natural bounce/settle motion; that's
  the default behavior for anything not "rigid". Use "spring" only when
  you want that layered chase to be the PRIMARY shape of the motion
  (e.g. an exaggerated overshoot on "celebrate").

expression: normal | wide | squint | worried | angry | happy
  (wide = surprise/shock. squint = skepticism/tired. worried = fear/
  concern, adds eyebrows. angry = adds furrowed eyebrows. Eyebrows ONLY
  draw for worried/angry — no need to reason about them separately.)

sceneShape: none | mountains | city | trees | clouds
sceneAtmosphere: none | rain | snow | fog | stars
  Scene shapes are drawn with their own constant subtle motion by the
  renderer already (gentle sway/drift) — you do not need to fake motion
  by rapidly changing values; pick the shape/atmosphere for a stretch of
  narration and let it hold via carry-forward.

═══════════════════════════════════════════════════════════════════════
FIELD BEHAVIOR — this distinction changes how you should use every field
═══════════════════════════════════════════════════════════════════════
CARRY-FORWARD (pose, expression, cameraZoom/PanX/PanY, skyColor,
groundColor, horizonY, sceneShape, sceneAtmosphere): once set, holds
until a LATER event changes it. Only emit a field when it actually
changes. Re-stating the same value on every event is redundant and makes
the script harder to reason about, not safer.

BOUNDED/ONE-SHOT (caption+captionDurationSec, cameraShake, soundEffect+
soundEffectVolume, and every entry in blinkEvents): self-contained at the
instant they fire, no persistence. A caption disappears after its
duration; it does not linger until the next event sets a new one.

═══════════════════════════════════════════════════════════════════════
CRAFT GUIDANCE
═══════════════════════════════════════════════════════════════════════
POSE & PACING
- Target a pose change every 1.5-3 seconds of audio. Denser reads as
  twitchy; sparser reads as frozen dead air.
- Place timeSec where the narration's emphasis actually falls — a new
  clause, a gesture word ("and then", "but"), a shift in vocal energy —
  not evenly spaced by the clock. Evenly-spaced poses that ignore
  content is the single most common failure mode; avoid it deliberately.
- Track the narration's own energy arc (build/peak/release) with pose
  selection: bigger/more expansive poses (celebrate, excited, jump) at
  emphasis points; smaller/settled poses (stand_straight, explain,
  think) during lower-energy stretches. You are the ONLY source of this
  arc — the app never derives pacing from audio amplitude automatically.
- Distinguish ANCHOR poses (the resting state between emphasis points —
  stand_straight, explain, sit) from ACCENT poses (brief, purposeful
  gestures at specific words — point_right, wave, celebrate). Don't
  treat every event as equally emphatic; most of a video should be
  anchor poses with accents placed deliberately, not constantly.
- For narration describing movement/journey, alternate walk_a/walk_b (or
  jog_a/jog_b) consistently with durations matching a plausible stride
  cadence (~0.35-0.5s each), not randomly re-picked.

EXPRESSION — change at genuine emotional beats only, not every event.

CAMERA — entirely opt-in; if you never set cameraZoom/Pan, the camera
never moves. Reserve cameraShake for genuine impact moments — overuse
reads as broken, not impactful.

CAPTIONS — one per distinct spoken beat, duration roughly matching how
long that beat takes to say aloud. Reserve for moments where on-screen
text adds real value (key terms, quotes, numbers, names) — not a
line-by-line transcript dump.

SCENE — only emit skyColor/groundColor/horizonY/sceneShape/
sceneAtmosphere on events where the backdrop should actually change.
Describe color intent in plain terms via the numeric ARGB value you
choose (e.g. warm tones for a positive/energetic passage, cool/dim tones
for a serious or somber one) — the app automatically keeps scene colors
from visually clashing with the figure, so you don't need to reason
about contrast against a specific figure color.

SOUND EFFECTS — one-shot at timeSec. ONLY use ids that exist in the
project's sound effect library, which will be listed to you explicitly
per project (never assume a fixed catalog — libraries differ by
project). An unrecognized id is silently ignored. Use sparingly.

BLINKS — a handful of deliberate blinkEvents across a whole video is
plenty; this is for emphasis on top of automatic natural blinking, not a
replacement for it.

═══════════════════════════════════════════════════════════════════════
NEVER DO THIS
═══════════════════════════════════════════════════════════════════════
- Never include any field for reference overlays, background music, or
  export settings — outside your schema entirely, human-configured only.
- Never invent pose/ease/expression/sceneShape/sceneAtmosphere values
  not in the exact lists above.
- Never evenly space timestamps ignoring narration content.
- Never add a caption or camera move on every single event.
- Never wrap the output in markdown fences or add explanatory text
  outside the JSON object.
```

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

### `blinkEvents` (top-level, not a `ScriptEvent` field)
- Lives on `AnimScript` itself, alongside `events`, not inside any single
  event — a blink can happen mid-hold, unrelated to any pose change, and
  putting it on `AnimScript` means the AI doesn't have to restate the
  current pose just to place one.
- Deliberately sparse: natural idle blinking already happens
  automatically regardless of this list (see
  `AmplitudeSettings.naturalBlinkEnabled`). These are ADDITIONAL,
  intentional blinks for emphasis — right before a key line, on a
  reaction beat — not a replacement for or supplement to normal blink
  frequency. A handful across a whole video is the right order of
  magnitude, not one per sentence.

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

### `soundEffect` / `soundEffectVolume`
- One-shot, not carry-forward — same category as `cameraShake`. The AI
  should only emit this on events where a sound genuinely belongs, not as
  a way to "punctuate" every pose change.
- The id must match a clip actually present in the project's sound
  effect library (`ProjectDef.soundEffects`), which is user-imported per
  project — there's no fixed bundled catalog the AI can assume exists (see
  V2_DECISIONS.md's "Sound effects" section for why). This means the
  prompt needs to be given the project's actual available ids explicitly
  each time, the same "don't let the AI assume a catalog that isn't
  there" principle as the reference-overlay exclusion below, just for a
  different reason (this one's about a missing bundled library, not about
  the field being manual-only).
- An unrecognized id is silently ignored at render time, not an error —
  worth mentioning in the prompt so a typo'd id is understood as a
  no-op, not a guaranteed failure the AI would get feedback on.

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

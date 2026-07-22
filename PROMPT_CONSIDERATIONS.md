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
                       whole video, not one per event. */ ],
  "overlayLayers": [ /* array of overlay layer objects — on-screen text
                         bursts/wordmarks and simple shapes composited on
                         top of the figure. See the OVERLAY LAYER shape
                         below. Optional — omit entirely or leave empty if
                         the video doesn't need any. */ ]
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
  "captionDurationSec": number, // how long the caption stays on screen once shown. Default 2.5.
  "skyColor": number | null,     // ARGB as a decimal integer — see COLOR VALUES below
  "groundColor": number | null,  // ARGB as a decimal integer — see COLOR VALUES below
  "horizonY": number | null,
  "sceneShape": "string" | null,
  "sceneAtmosphere": "string" | null,
  "soundEffect": "string" | null,
  "soundEffectVolume": number   // playback volume multiplier for soundEffect. Default 1.0.
}

Only "timeSec" and "pose" are required per event. Omit any field you're
not setting rather than repeating the previous value — see CARRY-FORWARD
below for why that matters.

Each object in "overlayLayers" (all fields except "type"/"startSec"/
"endSec" have defaults — omit anything you're not customizing):
{
  "id": "string",                 // optional label, for your own reference only
  "type": "string",               // REQUIRED. "text" | "shape" | "particles"
  "shape": "string",               // only used when type=="shape": "rect" | "circle" | "line" | "arrow". Default "rect".
  "startSec": number,             // REQUIRED. When this layer's enter animation begins.
  "endSec": number,               // REQUIRED. When this layer is fully gone. Must be > startSec.
  "x": number,                    // center X, fraction of canvas width (0..1). Default 0.5.
  "y": number,                    // center Y, fraction of canvas height (0..1). Default 0.5. Ignored if "slot" is set.
  "slot": "string" | null,        // shorthand for y: "upper" | "center" | "lower". Overrides y when set.
  "width": number | null,         // shape width, fraction of canvas width (rect)
  "height": number | null,        // shape height, fraction of canvas height (rect)
  "radius": number | null,        // shape radius, fraction of min(canvasWidth, canvasHeight) (circle)
  "rotationDeg": number,          // static rotation in degrees. Default 0.
  "scale": number,                // static scale multiplier. Default 1.
  "text": "string" | null,        // REQUIRED when type=="text"
  "fontSize": number,             // fraction of canvas HEIGHT (not width). Default 0.08.
  "bold": boolean,                // Default true.
  "align": "string",              // "left" | "center" | "right". Default "center".
  "color": number,                 // ARGB as a decimal integer — see COLOR VALUES below. Default opaque white.
  "gradientColor": number | null,  // if set, top-to-bottom gradient from color to this instead of a flat fill
  "glow": boolean,                 // Default false.
  "glowColor": number | null,      // defaults to color when null
  "glowRadius": number,            // fraction of min(canvasWidth, canvasHeight). Default 0.02.
  "enterStyle": "string",          // "fade" | "pop" | "zoom" | "slideup" | "slidedown" | "none". Default "fade".
  "enterDuration": number,         // seconds. Default 0.35.
  "enterEase": "string",           // one of the exact ease ids below (plus "back"). Default "ease_out".
  "exitStyle": "string",           // same vocabulary as enterStyle. Default "fade".
  "exitDuration": number,          // seconds. Default 0.35.
  "exitEase": "string",            // Default "ease_in".
  "opacity": number,               // ceiling alpha 0..1 once fully "in". Default 1.

  // ── Phase 2 (all optional — omit anything you're not using) ──────────
  "parentBone": "string" | null,   // attach to a stick-figure bone tip (see PARENTBONE VALUES). x/y become an OFFSET from the bone.
  "parentLayer": "string" | null,  // attach to another layer's "id" instead (full transform inheritance). Ignored if parentBone is also set.
  "physics": "string",             // "none" | "projectile" | "bounce". Default "none". When set, REPLACES x/y as the resting position.
  "physicsVx": number,             // horizontal velocity, fraction of canvas width/sec. Default 0.
  "physicsVy": number,             // initial vertical velocity, fraction of canvas height/sec. Negative = upward. Default 0.
  "physicsGravity": number,        // downward accel, fraction of canvas height/sec^2. Default 1.2.
  "physicsFloorY": number,         // only used by "bounce": fraction of canvas height it bounces off. Default 0.9.
  "physicsBounceDamping": number,  // fraction of speed kept after each bounce (0..1). Default 0.55.
  "trail": boolean,                // fading motion trail behind a physics-driven layer. Default false.
  "trailLengthSec": number,        // how far back the trail samples. Default 0.4.
  "particleCount": number,         // only used when type=="particles": how many particles in the burst. Default 20.
  "particleShape": "string",       // "circle" | "rect". Default "circle".
  "particleSpeed": number,         // max initial outward speed, fraction of canvas min-dimension/sec. Default 0.3.
  "particleGravity": number,       // optional downward accel on particles. 0 = straight drift (sparks), >0 = arc and fall (confetti). Default 0.
  "particleLifetimeSec": number,   // how long each particle lives before fading. Default 1.0.
  "particleSizeMin": number,       // per-particle radius range, fraction of canvas min-dimension. Default 0.006.
  "particleSizeMax": number        // Default 0.016.
}

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

overlayLayers[].type: text | shape | particles
overlayLayers[].shape: rect | circle | line | arrow
overlayLayers[].slot: upper | center | lower
overlayLayers[].enterStyle / exitStyle: fade | pop | zoom | slideup |
  slidedown | none
overlayLayers[].enterEase / exitEase: linear | ease_in | ease_out |
  ease_in_out | bounce | elastic_out | spring | back
  ("back" is ONLY valid here, not for a ScriptEvent's "ease" field — it's
  an overshoot-then-settle curve, pairs especially well with "pop".)
overlayLayers[].parentBone: torso | head | upper_arm_r | lower_arm_r |
  upper_arm_l | lower_arm_l | upper_leg_r | lower_leg_r | upper_leg_l |
  lower_leg_l (same ids as a ScriptEvent's implicit bone rig — right/left
  are the FIGURE's right/left, mirrored from the viewer's perspective)
overlayLayers[].physics: none | projectile | bounce
overlayLayers[].particleShape: circle | rect (only read when type=="particles")

═══════════════════════════════════════════════════════════════════════
COLOR VALUES (skyColor, groundColor, and every overlayLayers color field)
═══════════════════════════════════════════════════════════════════════
Every color is a single 32-bit ARGB value, written as a PLAIN DECIMAL
INTEGER — NOT a hex literal, NOT a quoted hex string. Standard JSON has
no hex-literal syntax at all, so writing something like 0xFF3B82F6 or
"#3B82F6" is INVALID JSON and will fail to parse, breaking the entire
script. Convert to decimal before writing it out.

The bit layout is AARRGGBB: alpha (usually FF for fully opaque), then
red, green, blue, each one byte. Worked example — opaque cornflower blue,
hex FF6495ED, is the decimal integer 4284782061. Fully-opaque colors
always decode to a positive number at or above 4278190080 (0xFF000000);
if your conversion gives something smaller or negative, alpha most
likely wasn't set to FF.

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

BOUNDED-BOTH-ENDS (every object in overlayLayers): requires BOTH
startSec AND endSec explicitly on every layer — there is no
carry-forward mode and no one-shot-instant mode for these. A layer is
simply invisible before its startSec and after its endSec, no exception.
This is different from captions (which only need a duration, not an
explicit end) specifically so you can never accidentally leave a text/
shape layer on screen indefinitely.

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

OVERLAY LAYERS — use for on-screen wordmarks, emphasis text bursts, and
simple shapes (underlines, accent rects/circles) that complement the
figure and captions, not replace them. Every layer needs BOTH startSec
and endSec — pick an endSec that actually ends the layer's on-screen life,
never leave one open-ended. Keep bursts short (a couple seconds) unless
it's meant to be a persistent title/wordmark for a whole segment. Avoid
placing two layers with overlapping time windows at the same slot or
near-identical x/y unless you deliberately want them layered together —
the app will warn about this, but don't rely on the warning as your
design process. "pop" reads best paired with enterEase "back" (a slight
overshoot); "fade" is the safe default for anything you're not sure about.
Don't caption AND overlay-text the same line redundantly — pick whichever
better serves that specific moment.

PARENTBONE / PARENTLAYER — reach for parentBone when something should
visibly travel WITH the figure (a sparkle at a raised hand, an accent
mark near the head during a key line) rather than trying to guess the
figure's screen position yourself frame by frame; x/y become a small
offset from that bone, not an absolute position. Use parentLayer to move
several layers together as a unit (e.g. a shape plus its own label).
Don't set both on the same layer. Don't create long parentLayer chains —
a couple of levels is normal, anything deeper gets hard to reason about
and the app caps/warns on very deep or circular chains anyway. slot is
only meaningful for an UNPARENTED layer — on a parented one it still
resolves to a y value, but that value becomes part of the OFFSET from
the parent, not an absolute screen region, which rarely reads as
intended. Use plain x/y on a parented layer instead of slot.

PHYSICS — reach for "projectile" or "bounce" for something that should
visibly fly/fall/bounce (a tossed object, a dropped item) rather than
faking motion with enterStyle="slideup"/"slidedown", which are for
static UI-style entrances, not real physical motion. When physics is set,
x/y become the STARTING position, not the resting position — physicsVx/
physicsVy set the initial launch, and slot is ignored entirely (physics
owns the position outright, so don't set slot on a physics layer — it
has no effect). Pair "bounce" with trail:true sparingly, only when the
motion itself is a meaningful visual beat, not on every physics layer by
default.

PARTICLES — a "particles" layer is a single BURST (all particles spawn
at startSec, no continuous stream), good for a short confetti/spark
moment tied to one beat (a celebration, a reveal), not a sustained
background effect running for the whole video. particleGravity=0 reads
as an outward spark/energy burst; a nonzero value reads as confetti
falling. Keep particleCount modest (10-30) — this is an accent, not the
focus of the frame.

═══════════════════════════════════════════════════════════════════════
NEVER DO THIS
═══════════════════════════════════════════════════════════════════════
- Never include any field for reference overlays, background music, or
  export settings — outside your schema entirely, human-configured only.
- Never invent pose/ease/expression/sceneShape/sceneAtmosphere values
  not in the exact lists above.
- Never invent overlayLayers type/shape/slot/enterStyle/exitStyle/
  enterEase/exitEase/parentBone/physics/particleShape values not in the
  exact lists above.
- Never omit startSec or endSec on an overlayLayers entry, and never set
  endSec <= startSec.
- Never set both parentBone and parentLayer on the same overlayLayers entry.
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

### Color values (`skyColor`, `groundColor`, every `overlayLayers` color field)
- Found during a full audit pass (checking the prompt's claims against
  the actual Kotlin source rather than trusting earlier prompt text) that
  this had NEVER been explicitly specified anywhere, despite being used
  in five different fields across two different objects. The gap that
  actually mattered: standard JSON has no hex-literal syntax, so an AI
  writing `0xFF3B82F6` (a very natural thing to write for "ARGB") would
  produce genuinely invalid JSON and fail to parse the whole script —
  this wasn't a style nitpick, it was a real correctness risk that just
  hadn't surfaced yet.
- The prompt now states the requirement explicitly (plain decimal
  integer, AARRGGBB byte layout) with one fully-verified worked example.
  The worked example's decimal value was computed with Python during this
  audit, not by hand — worth noting because the first hand-computed
  attempt at the same example was simply wrong, which is exactly the
  class of mistake a "trust but don't verify" pass would have shipped.

### `overlayLayers`
- The one field in this whole schema that is BOUNDED-BOTH-ENDS rather
  than carry-forward or one-shot: every layer requires an explicit
  `startSec` AND `endSec`, with no "holds until changed" mode at all. This
  is a deliberate structural fix, not just a style choice — the reference
  motion-graphics tool this feature was ported from had a real bug where
  a persistent text layer with no exit keyframe stayed on screen forever,
  so a later layer placed at the same position visually collided with it.
  Making both ends required means the AI literally cannot omit an end the
  way it could accidentally leave, say, a `sceneShape` carrying forward
  too long.
- Fractional `x`/`y`/`width`/`height`/`radius`/`fontSize` — same
  convention as `cameraPanX`/`cameraPanY`, chosen so these layers
  reframe correctly across dual-aspect export's two resolutions with no
  extra reasoning needed. `fontSize` specifically is a fraction of canvas
  HEIGHT so text reads at a consistent relative size on both aspect
  ratios instead of looking tiny on one and oversized on the other.
- The AI should reach for `slot` (upper/center/lower) over raw `y` in the
  common case — it's shorthand for the same handful of vertical positions
  a wordmark/caption-adjacent burst usually wants, and it's also what
  `ScriptValidator`'s clash check keys off (two overlapping-time layers
  in the same slot get flagged; two with matching raw `x`/`y` but no
  slot also get flagged, but slot is the more legible signal for the AI
  to reason about while writing the script).
- `enterStyle`/`exitStyle` are independent per layer (an emphasis burst
  can pop in and fade out, rather than mirroring the same style both
  ways) — the prompt tells the AI "pop" pairs well with `enterEase:
  "back"` since that's the one ease value that actually overshoots.
- Not a replacement for `caption` — captions are screen-space fixed
  subtitles; overlay layers pan/zoom/shake with the camera, same as the
  figure. The prompt should nudge the AI to pick whichever fits the
  moment rather than duplicating the same line in both.
- `parentBone`/`parentLayer` (Phase 2): the prompt should present
  `parentBone` as the answer to "I want this to visibly follow the
  figure" rather than the AI trying to compute the figure's screen
  position itself from pose/camera state — it can't, and shouldn't be
  asked to. `parentBone` is deliberately POSITION-ONLY (doesn't inherit
  the bone's rotation) so an attached label can't flip upside down as a
  limb rotates past vertical; `parentLayer` DOES inherit rotation/scale/
  opacity, the more conventional "group" behavior, since two
  AI-authored layers grouped together don't have that failure mode.
  Setting both on one layer is a mistake the prompt should warn against
  directly (parentBone silently wins; `ScriptValidator` also flags it).
- `physics` (Phase 2) exists so the AI reaches for real motion instead of
  faking it with `enterStyle: "slideup"`/`"slidedown"` — those are for a
  static UI element arriving on screen, not for something that should
  read as actually flying, falling, or bouncing. Closed-form under the
  hood (not frame-simulated) for the same reason everything else here
  is: `PlaybackEngine` needs to seek to an arbitrary timestamp with
  nothing to replay, so the prompt doesn't need to warn the AI about
  anything usage-wise here beyond "x/y become the start position, not
  the resting one" — the mechanics are invisible to script-writing.
- `type: "particles"` (Phase 2) is scoped in the prompt as a single short
  BURST tied to one beat, not a sustained ambient effect — matches the
  actual implementation (all particles spawn at `startSec`, no
  continuous stream), so setting the prompt's expectations to match
  avoids the AI asking for something the schema can't do (e.g. "confetti
  falling continuously for 10 seconds" would need many short bursts,
  not one particles layer with a long window).
- Two silent interactions found during the audit pass by re-reading
  `OverlayResolver.resolveOne`'s actual logic rather than trusting memory
  of having written it: (1) `physics` bypasses the `slot`-lookup branch
  entirely, so a physics layer with `slot` set just silently ignores it
  — no warning anywhere, code or prompt, before this pass; (2) on a
  parented layer (`parentBone`/`parentLayer` set, no physics), `slot`
  STILL resolves to a y value, but `applyParenting` then treats that
  value as an OFFSET from the parent rather than an absolute screen
  position — technically "working" but almost certainly not what anyone
  setting `slot` would expect. Both are now called out directly in the
  PARENTBONE/PARENTLAYER and PHYSICS craft-guidance paragraphs. Neither
  rose to a `ScriptValidator` warning (the checks would be cheap to add
  — `physics != "none" && slot != null`, `(parentBone != null ||
  parentLayer != null) && slot != null` — flagged here as a reasonable
  follow-up, not done as part of this prompt-only audit pass).

## Workflow notes (not schema — just how to use what already exists)

- **Highlight reels / recaps**: no special schema support needed and none
  exists. A highlight reel is just a normal project whose audio happens to
  be a pre-trimmed/spliced compilation of the best moments from a longer
  source — trim that audio first (outside the app), start a new project
  with it, and prompt the AI for a normal script exactly as you would for
  any other video. Don't try to express "skip to the good parts" inside a
  single script referencing the original full-length audio; the engine
  plays one continuous audio file start to finish.

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
- **Overlay layer clashes**: two `overlayLayers` entries with overlapping
  `[startSec, endSec)` windows in the same `slot` (or near-identical raw
  `x`/`y`) will visually collide — `ScriptValidator` warns about this,
  but same as timeline conflicts above, the warning is a backstop, not a
  substitute for the prompt telling the AI to space overlay layers out
  both in time and position unless deliberate layering is intended.
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

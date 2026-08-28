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
  "soundEffectVolume": number,  // playback volume multiplier for soundEffect. Default 1.0.

  // ── Figure transform & colors (all optional, all carry-forward) ──────
  // DISTINCT from cameraZoom/cameraPanX/cameraPanY above: those move a
  // virtual viewpoint around a figure that stays put. These move/recolor
  // the figure and scene themselves.
  "figureX": number | null,       // figure root position, fraction of canvas width. Overrides the project's rootAnchorX.
  "figureY": number | null,       // fraction of canvas height. Overrides rootAnchorY.
  "figureScale": number | null,   // overall character size multiplier. Overrides characterScale. NOT the same as cameraZoom.
  "headScale": number | null,     // overrides headScaleMultiplier.
  "figureOpacity": number | null, // 0..1, whole figure (bones+head+face). Overrides nothing project-side — 1.0 (fully opaque) if never set. Fades the figure out to let overlays lead, and back in.
  "boneColor": number | null,     // ARGB decimal — see COLOR VALUES. Sets the whole figure's color (limbs + head + joints) unless headColor/jointColor are also set to override per-element.
  "headColor": number | null,     // overrides boneColor for the head only when set.
  "jointColor": number | null,    // overrides boneColor for joints only when set.
  "bgColor": number | null,       // overrides BOTH preview and export background color uniformly.
  "backgroundGradientColor": number | null,  // no visible effect unless backgroundStyle is (or becomes) "gradient".
  "backgroundStyle": "string" | null,        // "solid" | "gradient". Snap (not interpolated).
  "groundLineColor": number | null,          // no visible effect unless showGroundLine is (or becomes) true.
  "showGroundLine": boolean | null,          // Snap (not interpolated).
  "groundLineYFraction": number | null,      // fraction of canvas height.
  "mouthColor": number | null,
  "eyeColor": number | null,
  "eyebrowColor": number | null
}

Only "timeSec" and "pose" are required per event. Omit any field you're
not setting rather than repeating the previous value — see CARRY-FORWARD
below for why that matters.

Each object in "overlayLayers" (all fields except "type"/"startSec"/
"endSec" have defaults — omit anything you're not customizing):
{
  "id": "string",                 // optional label, for your own reference only
  "type": "string",               // REQUIRED. "text" | "shape" | "particles" | "figure"
  "shape": "string",               // only used when type=="shape": "rect" | "circle" | "line" | "arrow" | "cross". Default "rect".
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
  "inFrontOfFigure": boolean,      // true = draws over the figure (default, original behavior). false = figure draws over THIS layer instead.

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
  "particleSizeMax": number,       // Default 0.016.

  // ── figure layers (only used when type=="figure") ────────────────────
  // A supporting, illustrative SECOND figure (e.g. "she told her
  // friend..."). NOT a co-equal character — no independent blinking, no
  // audio-driven mouth-sync, no sub-timeline. One static pose/expression
  // held for the whole startSec/endSec window. Reuses x/y/slot/scale/
  // color from above for position/size/color — only these two are new:
  "pose": "string" | null,         // BUILT-IN pose id ONLY (see the pose list below) — NOT the project's custom pose library. Falls back to "stand_straight" if omitted/unrecognized.
  "expression": "string" | null    // one of the 6 canonical expression values below. Default "normal". Static face only, no lip-sync (no audio channel to sync to).
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

sceneShape: none | mountains | city | trees | clouds | room | beach
sceneAtmosphere: none | rain | snow | fog | stars
  Scene shapes are drawn with their own constant subtle motion by the
  renderer already (gentle sway/drift) — you do not need to fake motion
  by rapidly changing values; pick the shape/atmosphere for a stretch of
  narration and let it hold via carry-forward.

backgroundStyle: solid | gradient

overlayLayers[].type: text | shape | particles | figure
overlayLayers[].shape: rect | circle | line | arrow | cross
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
COLOR VALUES (skyColor, groundColor, boneColor/headColor/jointColor/
bgColor/backgroundGradientColor/groundLineColor/mouthColor/eyeColor/
eyebrowColor, and every overlayLayers color field)
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
groundColor, horizonY, sceneShape, sceneAtmosphere, figureX/figureY/
figureScale/headScale/figureOpacity, boneColor/headColor/jointColor/bgColor/
backgroundGradientColor/backgroundStyle/groundLineColor/showGroundLine/
groundLineYFraction/mouthColor/eyeColor/eyebrowColor): once set, holds
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
POSE & PACING — RETENTION IS THE DEFAULT PRIORITY
Modern short-form video lives or dies on watch-time/completion in roughly
the first 20 seconds — a visually static video loses viewers regardless
of how good the audio is. Treat "keep this visually alive" as the
default goal for every video, not an opt-in mode, and calibrate the
INTENSITY per CONTENT TYPE GUIDANCE below rather than turning pacing off
for calmer content — even a religious/reflective piece needs to not go
visually dead for 5+ seconds at a time; it just gets there with stillness
and held reverent poses rather than jumpy energy, not with silence.
- Target a pose change roughly every 0.5-1.5 seconds during high-energy
  or hook stretches, and every 1.5-2.5 seconds during calmer/anchor
  stretches — NOT one fixed interval for a whole video. A video that
  changes pose at exactly the same cadence throughout reads as
  mechanical no matter what that cadence is. Vary the interval
  deliberately, tied to the narration's own rhythm, not a metronome.
- Mix "ease" deliberately for a sense of life, not just motion: alternate
  crisp "rigid" snaps (instant, mechanical-feeling, good for a beat
  landing hard) with "spring"/"elastic_out" (bouncy, alive-feeling) —
  using only one ease type for a whole video reads as flat regardless of
  how fast the poses change. A figure that never uses "rigid" can feel
  soft/floaty everywhere; a figure that never uses spring/elastic can
  feel stiff. Both, mixed with intent, is what reads as "alive."
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
  anchor poses with accents placed deliberately, not constantly — but
  "deliberately" during a hook/high-energy stretch can still mean quite
  frequent, per the interval guidance above.
- THE HOOK (first 1-3 seconds): this is the single highest-leverage part
  of the whole video — a slow or static opening loses viewers before the
  content even starts. STACK several attention-grabbers together in the
  opening beat rather than introducing them one at a time: an energetic
  pose landing with "spring" or "elastic_out", an immediate cameraZoom
  in, a bold overlay text burst (enterStyle "pop", enterEase "back")
  landing at the SAME instant, and a soundEffect if the project's
  library has one that fits. A static opening pose with just a caption
  fading in is the single most common way a hook underperforms — don't
  build the hook incrementally, build it as a stacked instant.
- THE LOOP (optional, works for any content type, not just motivational
  builds): if the piece has a natural closing beat, consider ending on
  something that echoes the opening — the same sceneShape/color pair,
  a similar figureX/figureY/cameraZoom, even a visually similar pose —
  rather than a wholly different final image. A close that visually
  rhymes with the open reads as a deliberate, satisfying shape rather
  than just stopping, and can make a rewatch feel natural. Skip it when
  the content's own ending doesn't call for symmetry — this is a device
  to reach for when it fits, not a checklist item for every video.
- Stillness is never the default — it's something you earn deliberately,
  not a fallback for a stretch with nothing obvious to do. Nothing should
  sit static for more than about 2-3 seconds without SOME change — a pose
  shift, a camera move, an overlay beat, a scene change. If a stretch of
  the script goes longer than that with nothing changing, that stretch
  needs another beat, even a small one — a fidget-scale pose nudge, a
  slow cameraPan, an ambient overlay drifting in. This applies across
  every content type, including calm/reverent ones — see CONTENT TYPE
  GUIDANCE below for what "alive" looks like at low intensity, which is
  different from "nothing happening."
- For narration describing movement/journey, alternate walk_a/walk_b (or
  jog_a/jog_b) consistently with durations matching a plausible stride
  cadence (~0.35-0.5s each), not randomly re-picked.

PATTERN AND SUBVERSION — an optional technique, not mandatory for every
video: if the narration has a repeating structure (a list, a refrain, a
"first... then... also..." cadence), let the visual treatment repeat
with it for 2-3 occurrences — the same beat shape each time (say, a
pose landing together with a pop-style overlay burst) — then break the
pattern on the next occurrence with something different in kind, not
just degree (a physics-driven layer flying in, a particle burst, a
scene change, a soundEffect) rather than a bigger version of the same
thing. The repetition is what makes the break register as a break —
don't subvert a pattern that was never established. Skip this entirely
for narration that doesn't actually have a repeating structure to play
against; forcing one in is worse than not using the technique.

EXPRESSION — change at genuine emotional beats only, not every event.

CAMERA — entirely opt-in; if you never set cameraZoom/Pan, the camera
never moves. Reserve cameraShake for genuine impact moments — overuse
reads as broken, not impactful.

FIGURE TRANSFORM & COLORS — figureX/figureY/figureScale move and resize
the FIGURE itself, not the camera's view of it; reach for these when the
figure should walk to a different part of the frame or visibly grow/
shrink (e.g. stepping toward the viewer), and reach for cameraZoom/Pan
when the FRAMING should change but the figure's own place in the scene
shouldn't. Combining both is fine (a figure moving while the camera also
pans), but each does a different job — don't use one to fake the other.
Keep figureX/figureY inside a safe range for the current figureScale;
the app will warn if a combination looks likely to crop the figure, but
that warning is a backstop, not your design process — a good rule of
thumb is to keep the figure's center within roughly 15%-85% of the frame
at scale 1.0, pulling that range in further at larger scales. Figure/
scene colors (boneColor, headColor, bgColor, etc.) are for a genuine mood
or scene shift over the course of the video, not per-event churn — treat
them with the same restraint as EXPRESSION above. Setting
backgroundGradientColor or groundLineColor without also enabling
backgroundStyle:"gradient" or showGroundLine:true (here or on an earlier
event) has no visible effect — check both are set together.

CAPTIONS — one per distinct spoken beat, duration roughly matching how
long that beat takes to say aloud. Reserve for moments where on-screen
text adds real value (key terms, quotes, numbers, names) — not a
line-by-line transcript dump.

SCENE — only emit skyColor/groundColor/horizonY/sceneShape/
sceneAtmosphere on events where the backdrop should actually change.
Describe color intent in plain terms via the numeric ARGB value you
choose (e.g. warm tones for a positive/energetic passage, cool/dim tones
for a serious or somber one) — the app automatically keeps scene colors
from visually clashing with the figure's CURRENT color (whatever
boneColor currently is, whether that's the project default or something
you set), so you don't need to reason about contrast yourself. Treat the
backdrop as SECTIONED, not a single static choice for the whole video —
change sceneShape and/or the color pair at each major structural beat
(a new numbered point, a new scene in a story, a topic shift), the same
way you'd change camera angle or location in a real edit. As a rough
frequency check for typical narration density, a backdrop that hasn't
shifted in the last 4-8 seconds is worth a second look — not a rule to
force a change against content that hasn't actually moved on, but a
prompt to check you haven't let a long stretch ride on one static world
by default. A backdrop that never changes across a multi-minute video
reads as visually flat even if everything else is well-paced.

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
better serves that specific moment. For a "cross" shape specifically,
width means arm THICKNESS (not overall span) and height means the
OVERALL cross height — the crossbar's own length and position are fixed
proportionally, so you don't need (and can't set) a separate field for
them. Don't try to build a cross out of a single rotated rect — a
rotated rect is still only one bar, never two; use "cross" directly.

COGNITIVE LOAD — cap simultaneous visual load at roughly: the figure,
one caption, and up to two active overlay layers at once. Once a moment
already has that much on screen, reach for figureOpacity or stepping
the figure aside rather than adding a fifth thing competing for
attention — dense narration is better served by letting fewer elements
each land clearly than by piling everything on at once. This is a
target for a given INSTANT, not the whole video — overlay layers with
non-overlapping time windows don't count against each other just for
existing in the same script.

This cap also matters for a reason that has nothing to do with the
viewer: particles, glow, and physics are all genuinely more expensive to
render and encode than a static shape or plain text, and stacking
several at the same instant is exactly the kind of high-entropy moment
that can visibly degrade encoded video quality if it happens often
enough across a video, on top of costing more render time. A climax
moment earning one glowing particle burst is a good use of the effect;
several different moments all stacking glow+particles+physics
simultaneously because each one individually seemed to justify it is
not — reach for the SAME device (a pop-style overlay, a pose landing)
you'd otherwise use, and save the heavier effects for where they're
doing real work.

MOTION-GRAPHICS-FORWARD EXPLANATION — for an abstract idea, a concept, or
anything better shown than mimed, don't rely on the figure's body
language alone to carry it. Shift the figure aside (figureX toward 0.25
or 0.75, or figureScale down) or let it step back, and let overlay
layers (shapes, text, a "figure" layer for a second party, particles for
a reveal) become the primary explainer for that stretch — the figure
becomes a narrator/host for that moment rather than the whole show. Once
the concept-illustration stretch ends, bring the figure back to its
normal position/scale (figureX back to 0.5, figureScale back to 1) — it
should read as "the figure stepped aside to let something be shown," not
as though it forgot to come back. Never leave the figure permanently
off-center after an illustration beat is over.

Stepping aside still keeps the figure visible and part of the
composition. For a stretch that should read as pure motion graphics —
the figure genuinely not part of the moment, not even in a corner — fade
it out with figureOpacity toward 0 instead (or all the way to 0 for
fully gone), then back toward 1 when the figure returns to the story.
This is a different tool for a different intent: reach for
figureX/figureScale when the figure is still narrating from the
sidelines, reach for figureOpacity when the beat doesn't need a narrator
at all. Same rule as position: never leave figureOpacity faded down
after the graphics-only stretch ends — fade it back to 1 explicitly.
Don't churn opacity per-event either — treat it with the same restraint
as figureX/figureScale, a handful of fades across a video, not a flicker
on every line.

Most overlays should stay at the default inFrontOfFigure:true (drawn
over the figure, the original and still normal behavior). Set it false
for the rare case a layer should read as sitting on or behind the figure
itself — scenery-like graphics, or a "figure" layer for a second
character meant to look like it's standing behind the main one rather
than layered on top of it. inFrontOfFigure is independent of
figureX/figureScale/figureOpacity above — it's about DRAW ORDER, not
position, and works the same whether the figure is fully visible,
stepped aside, or faded down.

THE PIVOT MOMENT — an optional, rare device (once, maybe twice in a
video, for a genuine reveal or turn) built from tools already covered
above, not a new mechanism: isolate first — fade the figure out with
figureOpacity toward 0 (not figureScale, which shrinks rather than
removes it) over a beat or two, while a bold overlay (large text and/or
a full-bleed shape behind it) takes the frame entirely. Then the figure
returns with intent — figureOpacity back to 1 landing together with
ease:"spring" on the pose transition and a real cameraShake value (0.3-
0.5 is a genuine jolt; reserve higher for something that should feel
overwhelming), the pose landing on the beat the narration turns on. Keep
figureX/figureScale themselves within their normal safe range through
this — the isolation comes from opacity and the overlay taking visual
weight, not from parking the figure somewhere off-frame. Settle back to
normal figureScale within half a second if anything moved. This only
works as a surprise if it's rare — using it more than once or twice
turns the "hijack" into just the video's normal rhythm, and it stops
landing.

TEXT PLACEMENT & READABILITY — when overlay text needs to be prominent
and the figure is also on screen, prefer moving the FIGURE aside
(figureX) over moving the text off-center — keep overlay text at its
natural centered position (x 0.5 or slot "center") for readability, and
let the figure's own displacement create the separation. Two things both
shifting off-center at once is harder to read than one clear subject
(the text) with the figure clearly making room for it. Remember this
same script drives BOTH a 9:16 and a 16:9 export when dual-aspect export
is on — composition that only works in one aspect isn't safe to assume.
Keep the important part of any overlay (the actual text/shape, not
necessarily its full bounding box) within roughly x 0.1-0.9 and y
0.1-0.7 — the lower band below y 0.7 is caption territory in the 9:16
export specifically and is worth avoiding for anything else regardless
of aspect, and content pushed to the extreme left/right edge is the
part most likely to sit differently, or get cropped, between the two
exported framings.

QUOTE/VERSE REVEAL — the schema has no way to recolor or highlight part
of a single text string (no per-character/per-word styling), so true
karaoke-style highlighting isn't possible. The real technique: break a
longer quote into several SHORT overlay text layers (a few words each),
each with its own startSec staggered to land as that phrase is actually
spoken, all at the same position so they read as one progressively-
revealed line rather than a single block appearing all at once. This
reads as a live caption-style reveal without needing anything the schema
can't do.

NUMBERED POINTS — for listicle-style structure ("3 reasons...", "5 ways
to..."), mark each point's arrival with a stacked beat: a large centered
number as its own short overlay text layer (enterStyle "pop" or "zoom",
enterEase "back", a glow reads well here), landing together with a scene
or color change and ideally a soundEffect if the library has one that
fits. This resets viewer attention at each point, which matters more in
a longer video than a short one.

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

FIGURE LAYERS — reach for a "figure" overlay when the narration
references ANOTHER person and a visual would help ("she told her
friend...", "the customer said..."), not to build a second speaking
character — there's no audio channel for it to lip-sync to, so its face
is a fixed shape for the whole layer, not animated dialogue. Use a
BUILT-IN pose only (see the pose list below) — a custom pose id will
just fall back to stand_straight. Give it its own color distinct from
the main figure so the two read as separate people, and generally a
smaller scale (it's a supporting element, not competing with the main
figure for attention) — position it clear of the main figure's own
space unless deliberate closeness is the point. If the supporting figure
should change pose or expression, use two adjacent figure layers rather
than expecting one layer to animate — each one holds a single static
pose for its own window.

For "people"/"customers"/"everyone"/a crowd or group rather than one
named person, a single figure layer reads as one extra person, not a
crowd — use several figure layers together instead, at reduced and
slightly varied scale (e.g. 0.4-0.6) and offset x/y so they don't
overlap identically, positioned behind or to the side of the main
figure rather than competing with it for the center. inFrontOfFigure:
false on a background crowd reads as depth (the main figure standing in
front of the group); leaving it at the default true reads as the group
crowding forward instead — pick deliberately based on which the moment
calls for. Three or four figure layers is usually enough to read as "a
crowd," not a literal headcount — more than that adds clutter without
adding clarity, and works against COGNITIVE LOAD above.

═══════════════════════════════════════════════════════════════════════
CONTENT TYPE GUIDANCE
═══════════════════════════════════════════════════════════════════════
Identify which of these best matches the narration's actual content and
audience, and let it inform every choice above — pacing, expression
range, camera energy, overlay density, color mood. Most narration clearly
fits one category; if it genuinely blends two (e.g. a faith-based
motivational talk), blend the relevant guidance rather than forcing a
single label. This section is about HOW to apply the mechanics already
covered above, not new fields.

EDUCATIONAL/EXPLAINER — captions earn their keep here more than anywhere
else: use them to reinforce key terms, numbers, and definitions as they're
said. Overlay text callouts for definitions are welcome; keep physics/
particles minimal, they read as playful decoration that can undercut a
serious teaching point. Let "think" poses and brief holds do real work —
a beat of pause after a complex idea aids retention more than rushing to
the next point. Reserve "excited"/"wide" for genuine "aha" moments, not
routine transitions. Hook: open with the actual value proposition
stated immediately (what will the viewer know by the end), not a slow
warm-up — a bold title-card overlay landing with the opening line reads
better here than dead air before the first caption.

RELIGIOUS/SPIRITUAL TEACHING — slower and more deliberate than any other
category: more hold time between transitions, fewer camera moves —
stillness reads as reverence, not lack of production value. cameraShake
essentially never belongs here; it connotes chaos, working against the
tone. Use "excited"/"wide" sparingly and only for a genuine spiritual
high point (real joy, not showmanship) — overuse reads as theatrical
rather than sincere. Overlay text for a quote or reference is valuable;
prefer calm fade/slideup entrances over pop/zoom/bounce, and avoid
particle bursts, which skew celebratory-secular rather than reverent.
Warmer, calmer scene colors and gentle atmosphere (stars, soft fog) can
support a contemplative mood well. Hook: even here, a static opening
loses viewers — a confident opening pose landing with intent, an
immediate on-screen statement of what this teaching is about, and a
gentle camera settle (not a shake, just a small purposeful zoom) reads
as "this matters" rather than hesitant. Reverence and stillness describe
the BODY of the video, not permission for the first three seconds to be
inert.

PRODUCT/BUSINESS EXPLAINER — punchier pacing than educational content;
this is the one category where heavier overlay use is earned — feature
callouts (pop/zoom text bursts), shapes/arrows drawing attention to a
point, even a particle burst on a genuine reveal moment. Reach for
"excited"/"happy" more liberally; enthusiasm is the point. Camera zoom on
key benefit statements reads as intentional emphasis here in a way it
might read as excessive elsewhere. Keep captions to the actual
stat/feature callouts, not a running transcript. Hook: lead with the
outcome or the problem being solved, stated boldly, not a company/product
introduction — "introducing our new..." is a weaker opener than the
benefit itself landing first.

NARRATIVE/STORYTELLING — pacing should follow the story's own emotional
beats, not a uniform rhythm — slow builds, quick reveals, whatever the
moment calls for. This is where the FULL expression range matters most,
each shift tied to a real beat in the character's arc, not decoration.
Camera can be the most dynamic of any category here (zoom for tension,
pan for a reveal, shake for a real impact moment), and physics-driven
overlay effects are the most narratively justified use of that feature —
representing an in-world event (something thrown, something magical),
not just visual decoration. Atmosphere effects tied to mood (rain, stars)
carry real narrative weight here. Hook: open mid-action or at the most
interesting moment of the story, not at its chronological start — "it
was a normal Tuesday when..." is a weaker opener than dropping the
viewer into the moment that makes them want to know how things got
there.

MOTIVATIONAL/INSPIRATIONAL — often structured as a build: start grounded
("normal"/"think", relatable struggle) and build toward an energetic
peak ("excited"/"happy"/celebration pose). Bold pop-style overlay text
for a genuinely quotable line is one of the strongest uses of that
feature — reserve it for the line that deserves to be read, not every
sentence. Scene/figure colors shifting warmer as the piece builds toward
its climax is a natural, earned use of that carry-forward behavior. A
celebratory particle burst at the actual peak moment (not before) lands
well; used earlier, it undercuts the build. Hook: state the transformation
or payoff up front ("from X to Y"), then earn it — this genre benefits
most from a loop-style structure where the closing beat echoes the
opening line/visual, which reads as a satisfying close and quietly
invites a rewatch.

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
- Never invent backgroundStyle values not in the exact list above.
- Never write a color (skyColor, groundColor, boneColor, or any other
  color field) as a hex literal or a quoted string — always a plain
  decimal integer, per COLOR VALUES above.
- Never set figureX/figureY/figureScale to a combination clearly outside
  the safe range described in FIGURE TRANSFORM & COLORS above.
- Never fade figureOpacity down for a motion-graphics stretch and skip
  the later event that brings it back to 1 — same rule as never leaving
  figureX/figureScale off-center permanently.
- Never use a custom pose id on a "figure" overlay layer — built-in poses only.
- Never evenly space timestamps ignoring narration content.
- Never add a caption or camera move on every single event.
- Never open a video with a static pose and only a fading caption as the
  hook — stack multiple attention-grabbers in the opening beat instead.
- Never hold the SAME scene backdrop for the entire video regardless of
  length — change it at structural beats.
- Never leave the figure shifted aside/shrunk after a concept-
  illustration stretch ends — bring it back to its normal position/scale.
- Never use THE PIVOT MOMENT more than once or twice in a single video —
  frequency is what makes it read as a surprise.
- Never stack more than the figure + one caption + two active overlay
  layers at the same instant — see COGNITIVE LOAD above.
- Never wrap the output in markdown fences or add explanatory text
  outside the JSON object.
```
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
  constants in `engine/Scene.kt` (`none|mountains|city|trees|clouds|room|beach` and
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

### `figureX`/`figureY`/`figureScale`/`headScale`/figure & scene colors
- The prompt frames these against `cameraZoom`/`cameraPanX`/`cameraPanY`
  explicitly (move the figure vs. move the viewpoint) because that's the
  distinction most likely to get blurred otherwise — both categories
  visually "move things around the frame," but for different reasons,
  and an AI given only the field names with no contrast would have no
  way to know which one to reach for.
- The off-screen safe-range guidance ("roughly 15%-85% at scale 1.0,
  narrower at larger scales") is stated as a rule of thumb, not just a
  reference to the validator's warning — deliberately, since a warning
  that only shows up after the fact is a worse experience than getting
  it right the first time. The number quoted was verified numerically
  against `ScriptValidator`'s actual constants, which is worth
  mentioning because the FIRST version of those constants
  (`APPROX_FIGURE_HALF_EXTENT = 0.4f`, margin `0.05f`) turned out to make
  the check mathematically impossible to satisfy at any `figureScale`
  above ~1.375 — verified with a quick Python check before shipping
  either the code or the prompt text describing it, exactly the kind of
  cross-check this project has learned the hard way to do rather than
  trust hand arithmetic or a plausible-looking constant.
- `backgroundGradientColor`/`groundLineColor` being inert without their
  enabling flag is called out explicitly in both the field comments AND
  the craft-guidance paragraph — this is the same shape of mistake as
  forgetting `soundEffectVolume` needs `soundEffect` set, and it seemed
  likely enough to recur that it earned the redundancy.

### `figureOpacity` (added alongside `inFrontOfFigure` below, same session)
- Motivated directly by wanting motion-graphics-forward stretches to be
  able to go all the way to "figure genuinely absent," not just
  "stepped aside" — `figureX`/`figureScale` alone can move the figure
  off-center or shrink it, but can't make it actually gone, and a video
  that's supposed to be pure kinetic-typography/graphics with the figure
  still small and visible in a corner isn't the same effect.
- Implemented as a single `canvas.saveLayerAlpha()` around just the FK
  draw pass, rather than multiplying alpha into each individual Paint
  (bone/head/joint/mouth/eye/eyebrow) — one control point that can't
  miss a sub-element, at the cost of an offscreen layer only when
  opacity is actually fractional (skipped entirely at the default 1.0,
  and the draw pass itself is skipped, not just hidden, below ~0.001).
- The prompt frames it as a DIFFERENT tool from stepping aside, not a
  stronger version of the same tool — deliberately, so the AI reaches
  for the one that matches actual intent (still narrating vs. not part
  of the moment at all) instead of always maxing out whichever field it
  reaches for first.

### `inFrontOfFigure` (`overlayLayers` field + mirrored on `ReferenceOverlay`)
- Default `true` preserves every existing script's rendering exactly —
  overlays have always drawn after/on top of the figure; this only adds
  the option to invert that per layer, nothing changes unless a script
  explicitly sets it.
- The implementation detail worth recording for future maintainers: the
  bone-anchor map that `parentBone` resolution depends on used to be
  built as a side effect of the figure's own draw pass, which made drawing
  behind-the-figure overlays before that pass impossible naively (no
  anchors would exist yet). Fixed by reading the anchor positions off
  the FK matrix pass BEFORE either overlay pass or the figure draws —
  the matrix pass already ran first regardless (it's the same numbers
  the draw pass would've read from), so this cost nothing extra at
  runtime, just an earlier readout of values that already existed. Worth
  recording because it wasn't obvious until actually re-reading
  `RigRenderer.draw()`'s two-pass FK structure — the fix looks like a
  bigger restructuring than it actually is.

### Content type guidance
- Self-classification (the AI infers content type from the narration
  itself) rather than an app-side picker, deliberately — adding a picker
  before "Copy AI Prompt" would mean `buildPromptForClipboard()`
  conditionally assembling different text per selection, a real app-code
  change, and a new UI decision to make before every generation. Content
  type is usually obvious from the narration text itself, so pushing the
  classification to the AI (which already does every other creative
  judgment call in this pipeline) costs nothing and adds no new code.
- Positioned AFTER the mechanics-focused craft guidance, not before —
  content-type advice is about how to apply tools (pacing, expression,
  overlays, color) the AI needs to already understand, not a
  replacement for understanding them.
- Each type's paragraph is written for CONTRAST, not completeness —
  the goal is calling out what's actually different about that type
  (religious teaching's stillness vs. product-explainer's punchier
  pacing), not re-explaining every mechanic per type, which would mostly
  repeat the craft guidance already given once.

### Retention-craft additions (pattern/subversion, pivot moment, cognitive
### load, safe zones, crowd-via-multiple-figure-layers, loop generalization)
- Source: a full alternative system prompt John brought in, written in a
  short-form-content "retention engineering" style (numbered Formulas,
  a director's-treatment XML preamble, explicit hook/subversion/hijack
  language). Checked it against the actual schema/source before porting
  anything — worth recording what held up and what didn't, since the
  document was clearly written without access to this repo:
  - The `<director_treatment>` preamble-before-JSON idea would have
    broken every import. Both `importScript()` and `onScriptTextChanged()`
    call `decodeFromString<AnimScript>()` directly on the full pasted
    text, no tolerance for anything before the `{`. Not ported — the
    strict raw-JSON-only output contract stays exactly as it was.
  - Its `figureX: 1.5` / `figureScale: 0.0` for hiding the figure both
    predate `figureOpacity` and would violate the documented 15%-85%
    safe-range guidance. THE PIVOT MOMENT (the rewritten version of its
    "Attention Hijack") uses `figureOpacity` instead, and explicitly
    tells the AI to keep figureX/figureScale in their normal range
    through the whole beat — isolation comes from opacity + the overlay
    taking visual weight, not from parking the figure off-frame.
  - Its pose list and ease list, checked field-by-field against
    `StickFigureRig.kt` and `EasingMath.kt`, were both exact matches —
    genuinely accurate research on that part, unusually so for content
    not written against this specific repo.
  - Its safe-zone rule (Formula 9) was written assuming 9:16 only. This
    project dual-exports 9:16 AND 16:9 from the same normalized-
    coordinate script (`ExportSettings.dimensions()`) — ported the
    concept but reframed around "the same composition has to hold in
    both aspects," not vertical-app engagement-button margins
    specifically, which don't mean anything in a 16:9 export.
  - Its crowd-building instinct (Formula 3) was sound but only
    implicit in the existing FIGURE LAYERS guidance, which was written
    for one supporting character, not a group. Extended explicitly
    rather than left to inference — multiple `figure` layers at reduced,
    varied scale, with a genre-appropriate `inFrontOfFigure` call for
    whether the group reads as background depth or a crowd pressing in.
  - Its Formula 1 (cinematic, non-hyperactive hook) turned out to
    already be handled, and arguably more precisely — the existing hook
    guidance calibrates energy per CONTENT TYPE GUIDANCE (the religious/
    spiritual section already says reverence describes the body of the
    video, "not permission for the first three seconds to be inert").
    A universal Formula 1 would have been a strictly worse version of
    guidance already there. Not duplicated.
  - Its Creative Seed Protocol (deriving a color "temperature" from the
    first vowel of the transcript) wasn't ported. It's a fingerprint
    disconnected from actual content — the existing SCENE guidance
    already ties color to real tone (warm for energetic, cool for
    somber), which produces non-identical output that actually tracks
    content, rather than one keyed to an arbitrary letter.
  - The "nothing static" threshold was tightened from ~4s to ~2-3s,
    directly per instruction that stillness should not be the default —
    not a full adoption of Formula 2's stricter 1.5s-on-the-figure-
    specifically rule, since the existing rule already covers "any kind
    of change," a broader and easier bar to reason about consistently
    than one tied to a single field.
- PATTERN AND SUBVERSION, THE PIVOT MOMENT, COGNITIVE LOAD are new
  labeled blocks, not folded into existing sections — each is a
  distinct-enough technique (and PIVOT MOMENT specifically rare/opt-in)
  that burying it inside a paragraph about something else would make it
  easy to miss on a re-read.
- COGNITIVE LOAD later extended (separate session, after a real
  blockiness bug was root-caused to bitrate starvation under high-
  entropy content) with a rendering-cost paragraph, not a new section —
  it's the same underlying instinct (don't stack more than the moment
  needs) applied for a second, unrelated reason. Deliberately kept as an
  addition to the existing cap rather than a numeric render-cost budget
  of its own, since the app has no way to actually measure or enforce
  that from the AI's side — the real fix for the actual bug lives in
  encoder settings (`ExportSettings`/`VideoExporter`), not the prompt;
  this is a complementary nudge, not a substitute for that fix.

### Overlay-vs-figure overlap check (`ScriptValidator`)
- Scoped to `type == "shape"` layers only, after building it broader
  first and hitting a real false-positive: a `slot: "center"` TEXT layer
  over a centered figure triggered the check by construction (center
  overlaps center, trivially), even though text overlapping the figure
  (emphasis text over the subject) is a completely normal, often
  intentional composition — caught by testing the check against this
  project's OWN demo script, which has exactly that layer. A shape doing
  the same reads as clutter in a way text usually doesn't, which is the
  actual distinction worth checking for.
- Resolves the figure's position/scale via a lightweight carry-forward
  lookup (`lastCarryForwardValue`) directly on the raw event list, not a
  full `TimelineCompiler` pass — cheap, and it's the same simplification
  the off-screen check already makes (falls back to a universal 0.5/0.5/
  1.0 default rather than the project's real `AppearanceSettings`, since
  `ScriptValidator` has no access to per-project settings).
- Adding this check immediately surfaced a real interaction in the
  project's OWN demo script (the `wordmark_celebrate` layer, which
  turned out to still trigger even after fixing the figure's position
  reset, since a centered figure and a center-slotted layer overlap by
  definition) — fixed by narrowing the check's scope rather than by
  further special-casing the demo, since the narrower scope reflects a
  real, generalizable distinction (shape vs. text), not a demo-specific
  workaround.

### `overlayLayers[].pose`/`expression` (figure layers)
- Built-in poses only, deliberately — the same "don't reach into the
  project's custom pose library" boundary drawn for consistency with
  `parentBone` only recognizing the fixed 10-bone rig, not project-
  specific concepts. A wrong pose id here is a silent fallback (to
  `stand_straight`), not a hard failure, matching how `type`/`shape`/etc
  already degrade gracefully elsewhere in this schema — but
  `ScriptValidator` still warns about it, same "graceful degradation
  doesn't mean invisible" principle as everywhere else.
- Caught a real bug while implementing `drawSecondaryFigure`, worth
  recording because of HOW it was caught: the first draft independently
  recomputed the layer's absolute canvas position and scale inside the
  new drawing function, duplicating work `drawGmsOverlay` had ALREADY
  done via `canvas.translate`/`scale` before dispatching to any type
  handler — meaning position and scale would have been applied twice.
  Found by re-reading `drawGmsOverlay`'s actual dispatch code before
  writing the new function, not by assuming the convention from memory
  of writing `drawGmsShape`/`drawGmsText` earlier in the same session —
  the exact "verify against the real thing, don't trust your own recent
  memory of it" discipline this project keeps re-learning, this time
  applied to code written minutes earlier in the SAME conversation, not
  a stale handoff from a previous one.

### `overlayLayers[].shape` — `cross`
- Added directly in response to a real AI-generated script, not a
  speculative addition — the AI tried to build a cross with a single
  rotated `rect`, which structurally can only ever be one bar, never two.
  Given religious/spiritual content is one of the five content types the
  prompt explicitly guides for, and a cross is about as central a symbol
  as that category has, this seemed worth a real fix rather than a
  prompt-only workaround telling the AI to layer two rects itself (more
  error-prone, and asks the AI to do geometry it shouldn't need to).
- `width`/`height` reused as arm-thickness/overall-height rather than
  adding shape-specific fields — the crossbar's own length and vertical
  position are fixed proportionally (traditional Latin-cross ratios),
  not independently configurable, since a supporting decorative symbol
  doesn't need the same per-instance control position/size/color get.

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

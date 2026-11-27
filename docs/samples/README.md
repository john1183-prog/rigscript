# Sample scripts

## v2_showcase.json

One script that exercises every field V2 has added so far (acting motion, expressions and mood idles, locomotion, the `act` verbs with `actTarget`, `emote`, `pose` left out). Paste it into the script editor of any project; with narration you also see the speech mouth and the talk sway, without it you still see everything else. It runs 125 seconds, and a caption on screen names the section being shown. It is checked by `ShowcaseSampleTest` (parses, validates with no warnings, covers every verb, emote and expression, runs at all four acting levels). **Extend it whenever a phase adds a field**, and keep that test passing.

| Time | Section | What to look at |
|---|---|---|
| 0 to 19 s | 1. Settle | forearm and head trail the body, anticipation, overshoot, the tiny hold drift |
| 19 to 48 s | 2. Moods | soft expression changes, eye drift, breathing rate and depth per mood |
| 48 to 63 s | 3. Walk | the mood idles pause while it walks and return after |
| 64 to 101 s | 4. Intent verbs | all ten `act` verbs; a circle at the top right (84 to 101 s) is the target for `layer:logo` |
| 103 to 123 s | 5. Emotes | all nine `emote` marks |

How to compare (Acting Off against Normal) is in `TEST_CHECKLIST.md`, section 3.

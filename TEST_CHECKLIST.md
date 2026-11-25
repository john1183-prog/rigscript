# TEST_CHECKLIST: Phase 1 (export and GLES)

For John, on the phone, after installing the APK from the latest green run on `main` (the Actions run page, artifact `app-debug`). Nothing in this list has been run on a device by anyone; every result you send back is the first real measurement. Sections 3 to 6 of the final checklist (acting, motion graphics, the director loop, presets) are added by the later phases; this file covers sections 0, 1, 2, 7 and 8 for Phase 1.

Words used: **Canvas** is the old export path, **GLES** is the GPU path, **Auto** is the Renderer setting that uses GLES only after the GLES check has passed on this phone, **quick check** is that GLES check (about half a minute), **self-test** is the long report.

## 0. Install and first launch with an existing project

1. Install the APK over the old app (same signing key, so your projects stay). Open the app. **Expect:** it opens to the home screen, your old projects are listed. **If it crashes on launch:** note the time and send a screenshot of any Android crash dialog; do not clear app data.
2. Open an old project and press play. **Expect:** it looks and sounds as before (poses, captions, scene, overlays).
3. About 10 seconds after launch (once), the app may run the quick check in the background if this build has no verdict yet. **Expect:** nothing visible except that a Quick Preview or Export pressed in those 30 seconds says "Wait for the running self-test to finish first". Playing the preview delays the check until you stop.
4. Open the project, tab Export. **Expect:** under the Renderer selector one sentence says what Auto will use and why. **Capture:** a screenshot of that panel (it shows the stored verdict in words).

## 1. Diagnostics: the self-test and the GLES verdict

The Self-test screen is in **debug builds only**: Settings (from the home screen), section Diagnostics, button "GLES self-test".

5. Tap **GLES smoke test (3 s)**. **Expect:** a line of progress, then "GLES smoke test OK: 3.00 s ..." **If it says FAILED:** copy the text; this alone is enough to know GLES is unusable here.
6. Tap **Run full self-test** and keep the app open (about 1 to 3 minutes; the screen stays on its progress line). **Expect:** a text report with a VERDICT line and 14 audio lines (7 source combinations on Canvas and on GLES) and 4 parity frames. **Capture:** tap **Share summary** and send it to the chat; also the files below.
7. Fill in this table from the report (PASS or FAIL, and the line under it if FAIL):

| Cell | PASS / FAIL | If FAIL, the failing line |
|---|---|---|
| Canvas narration | | |
| Canvas music | | |
| Canvas sfx | | |
| Canvas narration+music | | |
| Canvas narration+sfx | | |
| Canvas music+sfx | | |
| Canvas narration+music+sfx | | |
| GLES narration | | |
| GLES music | | |
| GLES sfx | | |
| GLES narration+music | | |
| GLES narration+sfx | | |
| GLES music+sfx | | |
| GLES narration+music+sfx | | |

8. Parity frames: in the files folder open the four `diag_parity_*.png` (Canvas on the left, GLES on the right). **Expect:** the two halves look the same: figure in the same place, "PARITY" text yellow and tilted, mountains with smooth edges, a caption at the bottom. **Capture:** the four PNGs. **Look especially for:** the GLES figure sitting about 2 pixels higher (report item R1), a faint mouth in landscape (R4; the fixture is portrait, so also compare a landscape export in section 2), softer text, jagged mountain edges.
9. Report files are in the app's external files folder: `Android/data/com.example.rigscript/files/` (`diagnostics_summary.txt`, `diagnostics_report.json`, the PNGs). Some file managers hide `Android/data`; the Share button is the fallback. **Capture:** `diagnostics_report.json` if you can open it.
10. Tap **Re-run GLES check (the quick check)**. **Expect:** about 30 seconds, then "GLES check PASSED ... Auto now uses GLES on this phone." or "GLES check FAILED ... Auto keeps using Canvas." with the failing numbers, or "inconclusive" if the Canvas reference could not be made (then nothing is stored). **Capture:** the text. Then go to the export panel: the sentence under the Renderer selector should now match the result. **What Auto chose on this phone and why is the answer to write down.**
11. Kill the app mid-check once if you want to test the crash guard (swipe it away while "GLES check" shows a progress line). **Expect:** at the next launch the verdict reads `INELIGIBLE(crashed)`, and Auto uses Canvas until you tap Re-run GLES check.

## 2. Real exports

Use one project with narration, background music and at least one sound-effect cue (a real project, not the self-test fixtures). Set resolution 720p, 30 fps.

12. Export on **Canvas** (Renderer: Canvas), 9:16, with narration + music + sound effects. **Expect:** the file plays with all three audible, length = script or narration + 1 s. **Capture:** the file, the time it took. Repeat with narration only, and with sound effects only (no narration, no music).
13. Same three on **GLES** (Renderer: GLES). **Expect:** same sound, same picture. A failure shows an error and a **Retry with Canvas** button; it never silently switches. **Capture:** any error text.
14. Landscape: repeat 12 and 13 with 16:9. **Look at:** the mouth (is it as dark as on Canvas?), caption and text positions, zoomed and rotated text sharpness, mountain edges. Put the two files side by side at the same moment (pause both).
15. **Dual aspect:** switch "Export both 9:16 and 16:9" on, export on Canvas, then GLES. **Expect:** two files, both complete, each playing.
16. **3+ minute export** on a long project (or a script padded to 3 to 5 minutes), 1080p, on GLES and on Canvas, phone not on the charger. **Capture:** time taken, whether the phone got hot, whether a "Device is heating up" message appeared, whether the app was killed, and whether the file plays to the end. Also try **Cancel** during a GLES export: it should stop within a few seconds and leave no half file.
17. **Auto:** set Renderer to Auto and export. **Expect:** it uses what step 10 said. If the verdict is Eligible and the export falls back to Canvas, a warning explains why.
18. Open the older tests: **Settings > GLES self-test > GLES figure test (3 s)** and **GLES stress test, full length** (these render the project that is open in the editor). **Expect:** a clip in your Movies folder; the stress test takes as long as a real export.

If something looks wrong, flip **Renderer** to Canvas (the old, known path) and note which step. Resolution, fps and bitrate also matter for heat: try 720p at 30 fps first.

## 7. Tuning guide (Phase 1 part)

- **Force a renderer:** export panel, Renderer: Auto, GLES or Canvas. Auto follows the stored verdict; Re-run GLES check refreshes it. A new installed build or an OS update makes the stored verdict stop applying, and the check runs again by itself.
- **GLES acceptance thresholds** are constants in `app/src/main/java/com/example/engine/GlesAcceptance.kt`: `MAX_OFFSET_PX` 0.75, `MAX_MEAN_ABS_DIFF` 6, `MIN_LAPLACIAN_RATIO` 0.90, `MIN_EDGE_AA_RATIO` 0.85, `MAX_DURATION_DELTA_SEC` 0.1, `MIN_PRESENT_AMPLITUDE` 0.01, `MAX_ABSENT_AMPLITUDE` 0.003, `MIN_SFX_CUE_RATIO` 4. The self-test report prints every measured number next to the limit it was judged against: if a number fails by a hair and the two PNGs look identical, raise that limit a little and say why in `V2_DECISIONS.md`; if a number passes but the PNGs look different, tighten it. Change the constant, push, wait for CI, install the new APK, tap Re-run GLES check. The limits are tested in `GlesAcceptanceTest`, which names the old values: update the test in the same commit.
- **If a tone reads "absent" while audible** (or the reverse), look at `DiagnosticsMedia.decodeAudio` (float PCM, channel count) before touching the audio thresholds.
- **Debug GLES switch:** `GlesFrameRenderer.POLYGON_ANTIALIAS` turns the polygon edge fringe off if thin shapes look too heavy.

## 8. What to send back

1. The result of steps 4, 5, 10 (screenshots or copied text) and the quick-check sentence under the Renderer selector.
2. `diagnostics_summary.txt` (Share summary) and, if you can, `diagnostics_report.json` and the four `diag_parity_*.png`.
3. The filled-in table of step 7.
4. For steps 12 to 17: for each file, Canvas or GLES, aspect, what was in it, did it play, was the sound right, how long, how hot. Screenshots of any error text.
5. Anything that crashed: the time, what you tapped, a screenshot of the crash dialog.

## Not yet device-tested (the honest list)

From Phase 0 and WP1.1 to WP1.3: `ExportAudio`, `AudioMixer`, `VideoExporter`, the export panel, `SoundEffectPlayer`, `GlesExporter` (portrait, landscape, dual aspect, cancel during a GLES export, a 3+ minute export), `GlesFrameRenderer` with several surfaces, trails on GLES, `ExportRouter` (the Auto fallback message, a forced GLES error with Retry with Canvas), the Renderer selector and Re-run GLES check. From WP1.4: GLES text rasterized at its on-screen scale, the polygon edge fringe, overlay text wrapping near the edges, the capped caption box; the overlay rules R7 R8 R9 are unit-tested but their drawing is not. From WP1.6: everything in section 1: the fixtures through the real exporters, `DiagnosticsMedia` (audio decoding, frame extraction), the PNGs, the report files, the quick check and its background scheduling, the crash marker, the Self-test screen.

From WP2.1: the Appearance tab's "Acting" selector (Off, Subtle, Normal, Bold: it should keep your choice after you leave and reopen the project, and applying a saved appearance preset should not change it) and the "Acting: ... (A/B)" chip at the top left of the preview (tap flips the label between OFF and the stored setting). With Acting set to Off the motion must be exactly what it was before the campaign; if Off looks different, that is a bug: report it.

From WP2.2 (the motion model; this is the first thing you can SEE of Phase 2). A/B test, 5 minutes: open any project with a script that has arm and head moves, tap the "Acting" chip at the top left of the preview to flip OFF and your setting, and replay the same 5 seconds each way, then try Subtle, Normal and Bold in the Appearance tab. What to look for: (1) the forearm and head trail the torso by a few frames instead of moving as one block; (2) a tiny wind-back just before a big move; (3) a soft settle (a small overshoot) after it; (4) holds are not frozen (a very slight drift); (5) the pose still LANDS on the word, not later (if it feels late, tell me which project and which intensity); (6) walk and jog cycles look the same as before. What to capture: a screen recording or an export at Off and at Normal of the same 5 seconds, and one line on whether Normal feels lively, too floaty or too twitchy (Bold is meant to be exaggerated). The numbers to tune are in `ActingParams` (lead/follow delay, stretch, wind-back, overshoot, hold drift per level).

From WP2.3 step 1 (the shared face geometry; a refactor, so the expected result is NO visible change at any Acting setting): export or preview the same short project as before, once on Canvas and once on GLES, and compare with a video made before this build if you have one. What to look at: (1) the main figure's eyes, mouth and eyebrows in all six expressions (normal, wide, squint, worried, angry, happy), especially worried and angry (the only ones with eyebrows); (2) the head tilted (a pose that leans or turns the head): the eyes and mouth must turn with the head, with no sliver eye; (3) a script with a secondary figure (an overlay figure layer) set to different expressions: its simple face (two eyes, a line, a smile or a frown) must be where it was. If anything moved, send the two files and the expression and pose names; the old behaviour is restored by reverting `cad6487` only.

From WP2.3 step 2 (the living face; with Acting at Subtle, Normal or Bold the face now changes, with Off it must look exactly as before). A/B test, 5 minutes, same method as WP2.2: use a project whose script changes expression several times (surprise, anger, a smile), flip the "Acting" chip between OFF and your setting, and replay the same 10 seconds each way. What to look for: (1) an expression change is no longer a snap: the eyes and mouth blend over about a fifth of a second; (2) just before a wide-eyed or angry change the eyes squint for two or three frames, and right after a wide change they pop a little larger and settle (the "take"); (3) eyebrows now appear on EVERY expression (flat for normal, high for wide, low for squint, slightly raised for happy); with Acting Off only worried and angry have them; (4) in idle moments the eyes drift a little every one to three seconds, the head turns a degree or two after the eyes, and now and then a blink goes with a bigger glance; (5) nothing looks cross-eyed or wall-eyed (the gaze offset is at most 18 percent of the head radius), and the eyes still turn with a tilted head; (6) a secondary (overlay) figure's face is unchanged at any setting, and so is a figure that is Off. What to capture: a short export at Off and at Normal on Canvas and on GLES (they must look the same as each other), and one line on whether the gaze is too busy, too sleepy or fine, and whether the brows on every expression look right or odd (if odd, which expression). Numbers to tune are in `ActingParams` (the `FACE_*` and `GAZE_*` constants and the per-level `faceAnticipationSec`, `faceTakeScale`, `gazeScale`); the brows-on-every-expression table is `FACE_BROW_HEIGHT` and `FACE_BROW_LIFT`.

From WP2.4 (the speech mouth; with Acting at Subtle, Normal or Bold the mouth is now smooth, with Off it must look exactly as before). A/B test, 5 minutes, same method as WP2.2: use a project with narration, flip the "Acting" chip between OFF and your setting, and replay the same 10 seconds each way, then export once on Canvas and once on GLES (they must look the same, and match the preview). What to look for: (1) the mouth no longer snaps between shapes several times a second: it opens fast on a syllable and closes a little slower; (2) between words and in pauses it closes fully (a thin line) and stays closed; a very short dip inside a word does not close it; (3) sibilants (s, f, sh) look narrower and open vowels wider and rounder, blending into each other; (4) quiet speech opens a little (with Off it stayed shut); (5) loud shouting does not look rubbery or too big (Bold opens about 15 percent more than Normal, Subtle about 15 percent less); (6) the mouth still follows the voice: no lag you can notice (it closes about a tenth of a second after the sound stops, by design); (7) with the amplitude motion setting switched off the mouth behaves as before; (8) a project with no audio keeps the old mouth. What to capture: the same two exports as before and one line on whether Normal looks natural, too mushy or too jumpy, and whether the mouth lags. Numbers to tune are the `MOUTH_*` constants and `mouthOpenScale` in `ActingParams`.

Gaze (WP2.3, asked in session 10): the figure's eyes drift to the left, right, up and down in idle moments, up to 18 percent of the head radius, which is subtle on a phone by design. If you want to see a bigger look, tell me and I will raise `GAZE_CAP` (the directed `look_at` of WP2.5 is the proper way to make the figure look at something).

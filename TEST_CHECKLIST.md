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

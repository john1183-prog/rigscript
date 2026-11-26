# acting_proto

Python prototype of the WP2.2 motion model (per-joint lead/follow, wind-up, settle, retarget, moving hold) and a small PIL reference drawer.
`python3 acting_proto.py` prints the numeric checks and writes the PNGs to `out/`; `--emit-reference` prints the rows that `ActingMotionTest` pins the Kotlin port to.
The drawer validates MOTION SHAPE only (FK of the 10-bone rig); it is not the app's renderer. The "hand-drawn line" rows are a look experiment, not an app feature.
Constants mirror `ActingParams`; change both together.

`face_proto.py` is the WP2.3 face model (expression blend with anticipation squint and take, brows on every expression, seeded gaze with a head that follows and a coupled blink). `python3 face_proto.py` prints the numeric checks and writes `out/05` to `out/11`; `--emit-reference` prints the 96 rows that `FaceMotionTest` pins the Kotlin port to (times are multiples of 1/64 so float32 holds them exactly). Constants mirror `ActingParams` (`FACE_*`, `GAZE_*`) and `ActingLevel` (`faceAnticipationSec`, `faceTakeScale`, `gazeScale`); change both together.

`mouth_proto.py` is the WP2.4 speech mouth: continuous (open, width, roundness) baked from the envelope with attack 35 ms and release 85 ms, a hysteretic gate, class debouncing and co-articulation. `python3 mouth_proto.py` prints the numeric checks and writes `out/12` to `out/15`; `--emit-reference` prints the envelope literal and the 52 rows that `MouthMotionTest` pins the Kotlin port to. Constants mirror `ActingParams` (`MOUTH_*`) and `ActingLevel.mouthOpenScale`; change both together.

`gaze_sheet.py` confirms the gaze reaches every direction (`out/16`, `out/17`; Kotlin: `FaceMotionTest.theIdleGazeLooksInEveryDirection`).

`verbs_proto.py` is the WP2.5 prototype of the `act` verbs (Direction): ten additive offset curves over the base pose and face (`sin^2` lobes and smoothstep plateaus that start and end at exactly zero with zero slope, so a verb returns to the base pose with no pop and verbs add up when they overlap), the gaze target of `look_at`, and the two-bone IK of `point_at` (elbow down on the nearer arm, clamped to the reach). `python3 verbs_proto.py` prints the numeric checks (return to zero, step size at 240 fps, IK error) and writes `out/18` to `out/23` (curves, IK reach, figure strips, point targets, face close-ups); `--emit-reference` prints the rows that `ActVerbTest` pins the Kotlin port to. Constants mirror `VerbParams`; change both together. The drawer validates motion shape, not app pixels.

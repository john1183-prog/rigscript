# acting_proto

Python prototype of the WP2.2 motion model (per-joint lead/follow, wind-up, settle, retarget, moving hold) and a small PIL reference drawer.
`python3 acting_proto.py` prints the numeric checks and writes the PNGs to `out/`; `--emit-reference` prints the rows that `ActingMotionTest` pins the Kotlin port to.
The drawer validates MOTION SHAPE only (FK of the 10-bone rig); it is not the app's renderer. The "hand-drawn line" rows are a look experiment, not an app feature.
Constants mirror `ActingParams`; change both together.

`face_proto.py` is the WP2.3 face model (expression blend with anticipation squint and take, brows on every expression, seeded gaze with a head that follows and a coupled blink). `python3 face_proto.py` prints the numeric checks and writes `out/05` to `out/11`; `--emit-reference` prints the 96 rows that `FaceMotionTest` pins the Kotlin port to (times are multiples of 1/64 so float32 holds them exactly). Constants mirror `ActingParams` (`FACE_*`, `GAZE_*`) and `ActingLevel` (`faceAnticipationSec`, `faceTakeScale`, `gazeScale`); change both together.

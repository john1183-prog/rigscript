# acting_proto

Python prototype of the WP2.2 motion model (per-joint lead/follow, wind-up, settle, retarget, moving hold) and a small PIL reference drawer.
`python3 acting_proto.py` prints the numeric checks and writes the PNGs to `out/`; `--emit-reference` prints the rows that `ActingMotionTest` pins the Kotlin port to.
The drawer validates MOTION SHAPE only (FK of the 10-bone rig); it is not the app's renderer. The "hand-drawn line" rows are a look experiment, not an app feature.
Constants mirror `ActingParams`; change both together.

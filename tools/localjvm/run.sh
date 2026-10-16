#!/bin/sh
# Compiles the pure engine plus the JVM tests with a standalone kotlinc (no Gradle, no Android) and runs them.
# Usage: tools/localjvm/run.sh [fully.qualified.TestClass ...]   (default: the classes below)
# Output of the golden test lands in $OUT/run/build/golden-actual/.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
H="$ROOT/tools/localjvm"
KOTLIN_HOME=${KOTLIN_HOME:-/tmp/kotlinc}
OUT=${OUT:-/tmp/localjvm-out}
[ -x "$KOTLIN_HOME/bin/kotlinc" ] || "$H/setup.sh"
CP="$KOTLIN_HOME/lib/kotlin-stdlib.jar:$KOTLIN_HOME/lib/kotlin-reflect.jar"
M="$ROOT/app/src/main/java/com/example"
T="$ROOT/app/src/test/java/com/example"
MAIN=$(sed "s#^#$M/#" "$H/main_files.txt" | tr '\n' ' ')
TEST=$(sed "s#^#$T/#" "$H/test_files.txt" | tr '\n' ' ')
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test" "$OUT/run"
"$KOTLIN_HOME/bin/kotlinc" -nowarn -jvm-target 17 -cp "$CP" -d "$OUT/main" \
  "$H/stubs/serialization.kt" "$H/stubs/mouthshape.kt" "$H/stubs/androidlog.kt" $MAIN
"$KOTLIN_HOME/bin/kotlinc" -nowarn -jvm-target 17 -cp "$OUT/main:$CP" -d "$OUT/test" \
  "$H/stubs/junit.kt" "$H/LocalJson.kt" "$H/LocalFixtures.kt" "$H/LocalRunner.kt" $TEST
CLASSES=${*:-"com.example.TestEnvelopesTest com.example.FixtureParseTest com.example.GoldenCompareTest com.example.EngineInvariantTest com.example.SpringParityTest com.example.ExportAudioPlanTest com.example.AudioMathTest com.example.GlesEligibilityTest com.example.ExportBackendPlanTest com.example.ExportSettingsRendererTest com.example.TrailGeometryTest com.example.GoldenTest"}
status=0
(cd "$OUT/run" && java -Drepo.root="$ROOT" -cp "$OUT/main:$OUT/test:$CP" localjvm.LocalRunnerKt $CLASSES > out.txt 2>&1) || status=$?
grep -v '^CAPTURE|' "$OUT/run/out.txt" || true
exit $status

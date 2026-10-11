#!/bin/sh
# Fetches the Kotlin compiler (same version as the project) from GitHub releases, an allowed host here.
set -e
KOTLIN_HOME=${KOTLIN_HOME:-/tmp/kotlinc}
V=2.0.21
cd "$(dirname "$KOTLIN_HOME")"
curl -sSL --max-time 300 -o kotlinc.zip "https://github.com/JetBrains/kotlin/releases/download/v$V/kotlin-compiler-$V.zip"
unzip -q -o kotlinc.zip
rm -f kotlinc.zip
"$KOTLIN_HOME/bin/kotlinc" -version

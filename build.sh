#!/usr/bin/env bash
# Builds micbridge.apk with the Android SDK build-tools directly (no Gradle).
set -euo pipefail

SDK=${SDK:-/tmp/opencode/sdk}
BT=$SDK/build-tools/30.0.3
PLATFORM=$SDK/platforms/android-30/android.jar
JAVA_HOME=${JAVA_HOME:-/tmp/jadx-dl/jre}
export PATH="$JAVA_HOME/bin:$PATH"

ROOT=$(cd "$(dirname "$0")" && pwd)
BUILD=$ROOT/build
SRC=$ROOT/app/src/main

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

"$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/res.zip"
"$BT/aapt2" link \
    -o "$BUILD/base.apk" \
    -I "$PLATFORM" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 30 \
    "$BUILD/res.zip"

javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -classpath "$PLATFORM" \
    -d "$BUILD/classes" \
    $(find "$SRC/java" "$BUILD/gen" -name '*.java')

"$BT/d8" --lib "$PLATFORM" --min-api 21 --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

(cd "$BUILD" && python3 - "$BUILD" <<'PY'
import sys, zipfile
b = sys.argv[1]
z = zipfile.ZipFile(b + '/base.apk', 'a', zipfile.ZIP_DEFLATED)
z.write(b + '/dex/classes.dex', 'classes.dex')
z.close()
PY
)
"$BT/zipalign" -f -p 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

KS=$ROOT/micbridge.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias micbridge \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=MicBridge, O=Local" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/micbridge.apk" "$BUILD/aligned.apk"

echo "built: $ROOT/micbridge.apk"
"$BT/aapt2" dump badging "$ROOT/micbridge.apk" | head -3

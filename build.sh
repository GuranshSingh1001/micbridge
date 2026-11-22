#!/usr/bin/env bash
# Builds micbridge.apk with the Android SDK build-tools directly (no Gradle).
set -euo pipefail

SDK=${SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/tmp/opencode/sdk}}}

if [ -d "$SDK/build-tools/30.0.3" ]; then
    BT="$SDK/build-tools/30.0.3"
else
    BT=$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
fi

if [ -f "$SDK/platforms/android-30/android.jar" ]; then
    PLATFORM="$SDK/platforms/android-30/android.jar"
else
    PLATFORM=$(find "$SDK/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)
fi

if [ -z "$BT" ] || [ ! -f "$BT/aapt2" ]; then
    echo "Error: Android build-tools not found in $SDK" >&2
    exit 1
fi

if [ -z "$PLATFORM" ] || [ ! -f "$PLATFORM" ]; then
    echo "Error: android.jar not found in $SDK" >&2
    exit 1
fi

echo "Using SDK: $SDK"
echo "Using Build-Tools: $BT"
echo "Using Platform: $PLATFORM"

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

python3 - "$BUILD" <<'PY'
import sys, zipfile
b = sys.argv[1]
z = zipfile.ZipFile(b + '/base.apk', 'a', zipfile.ZIP_DEFLATED)
z.write(b + '/dex/classes.dex', 'classes.dex')
z.close()
PY

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

echo "Built successfully: $ROOT/micbridge.apk"
"$BT/aapt2" dump badging "$ROOT/micbridge.apk" | head -3

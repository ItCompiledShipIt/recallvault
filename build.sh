#!/bin/bash
# build.sh - builds the RecallVault APK without Gradle.
# Pipeline: aapt2 -> javac -> d8 -> zipalign -> apksigner
# (same proven pipeline as Dial In)
set -euo pipefail

PROJ="$HOME/workspace/recallvault"
SDK="$HOME/workspace/.toolchains/android-sdk"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVA_HOME="$HOME/workspace/.toolchains/jdk-25.0.4.1+1"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

BUILD="$PROJ/build"
cd "$PROJ"

echo "=== aapt2 ==="
rm -rf "$BUILD/apk" && mkdir -p "$BUILD/apk"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$BUILD/apk/res.zip"
"$BT/aapt2" link -o "$BUILD/apk/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    -A "$PROJ/assets" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 1 --version-name "0.1.0" \
    --java "$BUILD/apk/gen" \
    "$BUILD/apk/res.zip"

echo "=== javac + d8 ==="
mkdir -p "$BUILD/apk/classes"
find "$BUILD/apk/gen" "$PROJ/java" -name '*.java' > "$BUILD/apk/sources.txt"
javac --release 8 -cp "$ANDROID_JAR" -d "$BUILD/apk/classes" @"$BUILD/apk/sources.txt"
mkdir -p "$BUILD/apk/dex"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 \
    --output "$BUILD/apk/dex" \
    $(find "$BUILD/apk/classes" -name '*.class')

echo "=== assemble ==="
python3 - "$BUILD/apk" <<'EOF'
import sys, zipfile, os
apk_dir = sys.argv[1]
base = os.path.join(apk_dir, "base.apk")
dex = os.path.join(apk_dir, "dex", "classes.dex")
out = os.path.join(apk_dir, "unsigned.apk")
with zipfile.ZipFile(base, "r") as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, "classes.dex")
print("wrote", out)
EOF

echo "=== zipalign + apksigner ==="
if [ ! -f "$PROJ/debug.keystore" ]; then
    keytool -genkeypair -keystore "$PROJ/debug.keystore" \
        -alias androiddebugkey -storepass android -keypass android \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/zipalign" -f -P 16 4 "$BUILD/apk/unsigned.apk" "$BUILD/apk/aligned.apk" \
    || "$BT/zipalign" -f 4 "$BUILD/apk/unsigned.apk" "$BUILD/apk/aligned.apk"
"$BT/apksigner" sign --ks "$PROJ/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$PROJ/recallvault.apk" "$BUILD/apk/aligned.apk"
"$BT/apksigner" verify --print-certs "$PROJ/recallvault.apk" | head -5
"$BT/aapt2" dump badging "$PROJ/recallvault.apk" | head -8
ls -la "$PROJ/recallvault.apk"
echo "BUILD OK: $PROJ/recallvault.apk"

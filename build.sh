#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
[ -f ./env.local.sh ] && source ./env.local.sh
: "${ANDROID_HOME:?set ANDROID_HOME}"
BT=$ANDROID_HOME/build-tools/34.0.0
AJ=$ANDROID_HOME/platforms/android-34/android.jar
OUT_DIR=${OUT_DIR:-dist}
VERSION_NAME=1.5.0
VERSION_CODE=6
APK_NAME=LtwUnlock-v${VERSION_NAME}.apk
mkdir -p "$OUT_DIR" build/gen build/classes build/dex build/astubs

find stubs -name '*.java' > build/astubs.list
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$BT/core-lambda-stubs.jar" \
  -Xlint:-options -d build/astubs @build/astubs.list
jar cf build/androidx-stubs.jar -C build/astubs .

# Resources first so R.java is available (LogActivity may use strings later)
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I "$AJ" --manifest AndroidManifest.xml -o build/base.apk \
  build/res.zip --java build/gen --min-sdk-version 29 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

find src -name '*.java' > build/src.list
# Include generated R.java if present
find build/gen -name '*.java' >> build/src.list || true
CP="$AJ:$BT/core-lambda-stubs.jar:lib/libxposed-api.jar:lib/libxposed-annotation.jar:build/androidx-stubs.jar"
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$CP" \
  -Xlint:-options -d build/classes @build/src.list

$BT/d8 --release --min-api 29 --lib "$AJ" --output build/dex \
  $(find build/classes -name '*.class')

python3 - <<'PY'
import zipfile, shutil, os
shutil.copy('build/base.apk', 'build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
    for root, dirs, files in os.walk('META-INF/xposed'):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, p)
PY

$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
# Signing: env vars or untracked ./signing.local.sh providing
#   KS_FILE, KS_PASS, KS_ALIAS, (optional) KEY_PASS
[ -f ./signing.local.sh ] && source ./signing.local.sh
: "${KS_FILE:?set KS_FILE (keystore path)}"
: "${KS_PASS:?set KS_PASS}"
: "${KS_ALIAS:?set KS_ALIAS}"
export KS_PASS; export KEY_PASS=${KEY_PASS:-$KS_PASS}
$BT/apksigner sign --ks "$KS_FILE" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --ks-key-alias "$KS_ALIAS" --out "build/$APK_NAME" build/aligned.apk
cp -f "build/$APK_NAME" "$OUT_DIR/$APK_NAME"
$BT/apksigner verify --print-certs "$OUT_DIR/$APK_NAME" | head -2
$BT/aapt2 dump badging "$OUT_DIR/$APK_NAME" | head -5
unzip -l "$OUT_DIR/$APK_NAME" | grep -E 'META-INF/xposed|classes.dex|ic_launcher|ModernInit|LogActivity|file_paths' || true
echo "APK OK: $OUT_DIR/$APK_NAME ($(stat -c%s "$OUT_DIR/$APK_NAME") bytes)"

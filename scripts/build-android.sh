#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-/Users/titan/Library/Android/sdk}
JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.0.16.jdk/Contents/Home}
export JAVA_HOME
TOOLS="$ANDROID_SDK_ROOT/build-tools/35.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
OUT=build/android
mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$PLATFORM:$TOOLS/core-lambda-stubs.jar" -d "$OUT/classes" android/src/com/vector/motion/*.java
"$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$TOOLS/d8" --lib "$PLATFORM" --min-api 31 --output "$OUT/dex" "$OUT/classes.jar"
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest android/AndroidManifest.xml -o "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f .tools/vector-debug.jks ]; then
 "$JAVA_HOME/bin/keytool" -genkeypair -keystore .tools/vector-debug.jks -storepass android -keypass android -alias vector -dname 'CN=Vector Development' -keyalg RSA -keysize 2048 -validity 3650
fi
"$TOOLS/apksigner" sign --ks .tools/vector-debug.jks --ks-key-alias vector --ks-pass pass:android --key-pass pass:android --out "$OUT/vector-motion.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify "$OUT/vector-motion.apk"
printf 'Built %s\n' "$OUT/vector-motion.apk"

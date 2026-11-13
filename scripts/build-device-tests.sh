#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-/Users/titan/Library/Android/sdk}
JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.0.16.jdk/Contents/Home}
export JAVA_HOME
TOOLS="$ANDROID_SDK_ROOT/build-tools/35.0.0"
PLATFORM="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
OUT=build/device-tests
mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$PLATFORM:$TOOLS/core-lambda-stubs.jar" -cp build/android/classes -d "$OUT/classes" android/device-tests/com/vector/motion/tests/*.java
"$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$TOOLS/d8" --lib "$PLATFORM" --classpath build/android/classes.jar --min-api 31 --output "$OUT/dex" "$OUT/classes.jar"
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest android/device-tests/AndroidManifest.xml -o "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks .tools/vector-debug.jks --ks-key-alias vector --ks-pass pass:android --key-pass pass:android --out "$OUT/vector-checks.apk" "$OUT/aligned.apk"

#!/bin/bash
# Build the monitor APK without Gradle: aapt2 -> kotlinc -> d8 -> zipalign -> apksigner.
set -e
export ANDROID_HOME=~/.hermes/work/android-sdk
export PATH=~/.hermes/work/kotlinc/bin:$ANDROID_HOME/build-tools/35.0.0:$PATH
cd ~/.hermes/work/llm-monitor-app
rm -rf build && mkdir -p build
kotlinc -version

# 1. Compile resources; link against android.jar producing base APK + R.java.
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_HOME/platforms/android-35/android.jar" \
  --manifest AndroidManifest.xml build/res.zip --auto-add-overlay --java build/gen

# 2a. R.java -> classes (kotlinc resolves R through this; javac emits no android deps).
javac -nowarn --release 8 -cp "$ANDROID_HOME/platforms/android-35/android.jar" \
  -d build/classes $(find build/gen -name '*.java')

# 2b. Kotlin sources -> classes (build/classes on the cp so R is visible).
kotlinc app-src/*.kt -classpath "$ANDROID_HOME/platforms/android-35/android.jar:build/classes" \
  -d build/classes

# 2c. Classes -> DEX. kotlin-stdlib must ride in the APK.
d8 --release --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --min-api 26 --output build/ \
  $(find build/classes -name '*.class') ~/.hermes/work/kotlinc/lib/kotlin-stdlib.jar

# 3. Merge DEX into the resource APK (zip store as unsigned payload).
cd build && zip -q base.apk classes.dex && cd ..

# 4. Align, then sign with a dedicated local keystore (regenerate if missing).
# zipalign MUST run before apksigner: v2/v3 signatures cover zip alignment.
zipalign -f 4 build/base.apk build/aligned.apk
KS=~/.hermes/work/llm-monitor-app/monitor.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass monitor -keypass monitor \
    -alias monitor -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=LLM Monitor, O=personal, C=US" 2>/dev/null
fi
apksigner sign --ks "$KS" --ks-pass pass:monitor --v1-signing-enabled true --out llm-monitor.apk build/aligned.apk
apksigner verify --print-certs llm-monitor.apk | head -2
shasum -a 256 llm-monitor.apk
ls -la llm-monitor.apk

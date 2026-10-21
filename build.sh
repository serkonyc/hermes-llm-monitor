#!/bin/bash
# Build the monitor APK without Gradle: aapt2 -> d8 -> zipalign -> apksigner.
set -e
export ANDROID_HOME=~/.hermes/work/android-sdk
export PATH=~/.hermes/work/kotlinc/bin:$ANDROID_HOME/build-tools/35.0.0:$PATH
cd ~/.hermes/work/llm-monitor-app
rm -rf build && mkdir -p build

# 1. Compile resources; link against android.jar producing base APK + R class.
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_HOME/platforms/android-35/android.jar" \
  --manifest AndroidManifest.xml build/res.zip --auto-add-overlay

# 2. Kotlin -> DEX. kotlin-stdlib must ride in the APK.
d8 --release --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --min-api 26 --output build/ \
  classes/*.class ~/.hermes/work/kotlinc/lib/kotlin-stdlib.jar

# 3. Merge DEX into the resource APK (zip store as unsigned payload).
cd build && zip -q base.apk classes.dex && cd ..

# 4. Debug-sign with a dedicated local keystore (regenerate if missing).
KS=~/.hermes/work/llm-monitor-app/monitor.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass monitor -keypass monitor \
    -alias monitor -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=LLM Monitor, O=personal, C=US" 2>/dev/null
fi
apksigner sign --ks "$KS" --ks-pass pass:monitor --v1-signing-enabled true --out llm-monitor.apk build/base.apk
apksigner verify --print-certs llm-monitor.apk | head -2
ls -la llm-monitor.apk

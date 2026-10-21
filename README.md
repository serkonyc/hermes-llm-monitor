# Hermes LLM Monitor

Android utility for Galaxy Z Fold 8: monitors the local mlx-serve LLM server.

- Fold-aware: 4:3 inner screen shows two columns with larger type; 10:16 cover screen shows a compact single column.
- Works offline: shows a baked-in demo dashboard; live status+metrics when 127.0.0.1 (pcdroid tunnel) or LAN mlx-serve is reachable. Auto-refresh every 10 s.
- Update channel: GitHub Releases. The app checks `releases/latest` and offers the new APK.

## Build

All tooling lives under `~/.hermes/work/` (Android SDK 35, Kotlin 2.0.21):

```
bash build.sh   # -> llm-monitor.apk (signed with monitor.keystore)
```

No Gradle. `app-src/MonitorActivity.kt` is the whole app.

## Connect

`mlx-serve` binds `127.0.0.1:18422` on the Mac (not LAN-reachable). The app tries
LAN (192.168.1.96) then 127.0.0.1 (works with a pcdroid/Termux port tunnel).
To expose on LAN directly, start mlx-serve with `--host 0.0.0.0` instead.

## Bump version for a release

1. `APP_VERSION` + `versionCode/versionName` in `AndroidManifest.xml`
2. `bash build.sh`
3. `gh release create v<ver> llm-monitor.apk`
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

## Status
Gradle-less build verified working on this Mac (aapt2 -> d8 -> zipalign -> apksigner).
adb for device install: ~/Library/Android/sdk/platform-tools/adb (homebrew /opt/homebrew/bin/adb has no server).

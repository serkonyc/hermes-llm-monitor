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

# Caster

A minimal Android app that casts videos to a Chromecast on your local network.

## What it does

- Registers as an **external video player**: in apps like Stremio, pick "Play in external player" and choose Caster. Browsers and file managers can hand off videos the same way.
- Casts the received stream URL **exactly as provided** to a Chromecast using the Google Cast SDK (Default Media Receiver) — media streams directly over your LAN.
- Casts **local video files** (from the share sheet, a file manager, or the in-app picker) by serving them from an embedded HTTP server on the phone with Range support so seeking works.

## Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires an Android SDK (compileSdk 35) — set `sdk.dir` in `local.properties`.

## Usage

1. Make sure the phone and Chromecast are on the same Wi-Fi network.
2. Open a video in Stremio (or any app) and choose Caster as the external player.
3. Tap the Cast button, pick your Chromecast, and playback starts with full remote controls (notification, lock screen, and expanded controller).

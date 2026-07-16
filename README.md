# Caster

A minimal Android app that casts videos to a Chromecast on your local network.

## Download

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/ethanm6/Caster)

Or grab the APK directly from the [latest release](https://github.com/ethanm6/Caster/releases/latest).

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

For casting videos straight from web pages, see the companion Firefox extension: [caster-extension](https://github.com/ethanm6/caster-extension).

## Support

If you find this project useful, you can support development:

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/ethanm6)

## License

[GPL-3.0-or-later](LICENSE). Licenses of the bundled third-party libraries
are collected in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

**Cast SDK linking exception** — additional permission under GNU GPL version 3
section 7: if you modify this program or any covered work by linking or
combining it with the Google Cast SDK (`play-services-cast-framework`) or a
modified version of it, the copyright holder of Caster grants you permission
to convey the resulting work.

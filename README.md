# Open 100x

[![Android CI](https://github.com/MeYashverma/Open100x/actions/workflows/android-ci.yml/badge.svg)](https://github.com/MeYashverma/Open100x/actions/workflows/android-ci.yml)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-blue)
![License](https://img.shields.io/badge/license-MIT-informational)
![minSdk](https://img.shields.io/badge/minSdk-29-3ddc84)

<p align="center">
  <img src="docs/open100x-icon.png" alt="Open 100x app icon" width="200">
</p>

Open 100x is an Android camera and videography app built around one stubborn idea: a phone
that hides its long-range zoom in the stock camera should still be able to shoot at 100×,
with real controls, honest labels and no cloud in the loop.

This is the 2.0 redesign: a capability-driven camera engine, a new viewfinder, a real Pro
panel, an on-device computational-imaging pipeline, and a gallery that can show you the
untouched original next to the developed frame. The package stays `com.example.zoomhundred`
and the release/signing configuration is unchanged.

## What makes it different

* **Honest capability model.** Before the camera is even opened, Open 100x reads the
  sensor's capabilities and every control is rendered from that: *Supported*, *Limited*,
  *Simulated*, *Experimental* or *Unavailable*. There is no button that pretends.
* **The 100× engine, finished.** The optical/native ratio goes to the camera; the digital
  remainder is applied to the preview *and* baked into the saved file as a real crop (never
  an upscale). Above 15× the shutter waits for gyro stillness before firing.
* **One render pipeline.** Every still — single frame, burst, HDR fusion, night stack,
  panorama, document, portrait — ends in the same ordered pipeline, so aspect, LUTs,
  frames, EXIF and GPS stripping behave identically everywhere.
* **On-device only.** No `INTERNET` permission, no analytics, no uploads. LUTs are parsed
  and stored locally; diagnostics are files you export yourself.

## Features at a glance

| Area | What you get |
|---|---|
| Zoom | 100× hybrid engine, hardware-aware presets, log zoom rail, drag/pinch/volume-key control, zoom lock, settled capture, extreme-zoom FOV readout and target lock |
| Photo | JPEG/WebP, RAW/RAW+JPEG where the hardware and CameraX artifact allow, HDR fusion, burst, bracket, timer, interval, focus stacking, night/astro stacking, light trails, panorama, document, portrait, fisheye |
| Video | 720p–4K with 24/30/60 fps gated by the stream map and hardware encoder, OIS and EIS as separate labelled modes, pause/resume, audio toggle, bitrate control |
| Pro | ISO, shutter, EV, AE lock, AF/MF with a distance slider, focus lock, WB presets + Kelvin, histogram, zebra, focus peaking, spirit level, HUD, loupe, seven composition guides |
| Look | Nine colour profiles, `.cube` LUT import with intensity, nine frame styles |
| Library | On-device gallery of the app's own album, EXIF info, share/delete, before/after |
| System | Twelve settings categories, capability report and preset export to `Downloads/Open100x`, 12-lens-aware diagnostics with no GPS or identifiers |

Anything the app cannot do is documented rather than faked — see
[`docs/FEATURE_COMPATIBILITY.md`](docs/FEATURE_COMPATIBILITY.md).

## Build

Requirements: **JDK 17**, **Android SDK 35** (build-tools 35.0.0), Gradle wrapper included.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # minified release APK (signed when keystore.properties exists)
./gradlew :app:testDebugUnitTest      # JVM tests
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest   # needs a device/emulator
```

Outputs:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

The release build signs itself only if a `keystore.properties` file exists at the repo root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Nothing is committed and no key
material lives in this repository.

CI — `.github/workflows/android-ci.yml` — runs the unit tests, builds both APKs, compiles
the instrumentation tests and uploads `open100x-apks` as a workflow artifact on every push.

## Get the APKs

Every push builds both APKs and uploads them as the **`open100x-apks`** artifact of the
workflow run (`Actions → Android CI → <run> → Artifacts`). Pushing a tag such as `v2.0.0`
additionally publishes a GitHub Release containing `app-debug.apk` and
`app-release.apk` (`.github/workflows/release.yml`).

The CI release APK is **unsigned** because no keystore lives in this repository; sign it
with your own key or install the debug APK for testing.

### If the app does not open

1. **Uninstall the old version first**, then install `app-debug.apk`. Updating in place from
   1.x/2.0 keeps old preferences around; 2.0.1 reads them type-safely, but a clean install
   removes every doubt.
2. **Check the version**: `About` in Settings must read `2.0.1 (4)`. If it reads `2.0.0`,
   the old APK is still installed.
3. If it still misbehaves, it will no longer die silently: the app shows either a startup
   error screen or a "Last session closed unexpectedly" dialog, both with a **Copy report**
   button. Copy that text and it contains the full stack trace, device model and Android
   version — everything needed to fix the cause. Reports live in
   `files/crash/last_crash.txt` inside the app's private storage and are never uploaded.

## How it fits together

```
MainActivity ── CameraEngine ── CameraControls ── Camera2 interop
     │              │
     │              └─ CapabilityDetector → DeviceCapabilities → FeatureGate → UI
     │
     ├─ CaptureCoordinator ── Stacking / PanoramaStitcher
     │        └─ PostCapture ── ColorPipeline / CreativeEffects / LutLoader
     │                 └─ ImageStore (MediaStore)
     │
     ├─ VideoRecorderController (MediaStore)
     └─ overlays: loupe, zoom rail, HUD, capture button, peaking/zebra/level, grids
```

Full detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Documentation

* [`docs/CAMERA_RESEARCH.md`](docs/CAMERA_RESEARCH.md) — what PhotonCamera, Google Camera,
  Open Camera, Blackmagic Camera, Halide and the Android camera docs taught this build.
* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — modules, data flow, threading, honesty
  model, privacy.
* [`docs/FEATURE_COMPATIBILITY.md`](docs/FEATURE_COMPATIBILITY.md) — every feature with its
  real state and the reason behind it.
* [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — what changed in 2.0.0 and what is still
  missing.

## Privacy

* No network permission is declared: the app cannot upload anything.
* Captures go to `Pictures/<album>` and `Movies/<album>` through MediaStore.
* Location EXIF is stripped unless you explicitly enable location tagging.
* LUTs and untouched originals live in app-private storage.
* Diagnostics and preset exports contain hardware facts and settings only — no GPS, no
  file paths, no identifiers.

## Licence

MIT. Third-party libraries keep their own licences (CameraX, Material Components,
AppCompat, ExifInterface); no proprietary code or assets from other camera apps were used.

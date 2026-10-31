# Open 100x — 2.0 final report

**Repository:** `MeYashverma/Open100x` · **Package:** `com.example.zoomhundred` (unchanged)
· **Version:** 2.1.0 (versionCode 8) · **Branch:** `arena/92b4e2b9-open100x`

This report covers what was actually delivered against the brief: what changed, what was
redesigned, what is fully working versus limited/simulated/hardware-dependent/impossible,
what the build and test runs produced, and which bugs the work fixed.

---

## 0. The "app does not open" bug — two real defects, both fixed

**Primary cause (2.0.3).** 22 vector icons were written with compact SVG arc syntax
(`a8 8 0 100-16`), where the arc's two flags had no separators. Android's `PathParser` reads
`100` as one number, so the arc received 10 parameters instead of 7 and the drawable was
rejected:

```
InflateException: Error inflating class ImageView (activity_main line #222)
  -> Resources$NotFoundException: File res/drawable/ic_timer.xml
  -> IllegalArgumentException: a needs to be followed by a multiple of 7 floats ...
```

`aapt` does not validate path data, so this compiled and then failed on every launch. All 22
icons were re-parsed with the SVG grammar and re-serialised with explicit separators; all 133
paths in the app now validate under Android's rules.

**Secondary cause (2.0.2).** On this OEM build `onWindowFocusChanged()` is dispatched during
`super.onCreate()`, before inflation. It refreshed the aspect guide and dereferenced
`binding`/`settings`, which were uninitialised — a crash that also existed independently and
that became visible once the inflate failure was caught instead of fatal.

### 2.0.2 — window-focus crash

**Reported trace (OPPO CPH2731, Android 16 / SDK 36):**

```
kotlin.UninitializedPropertyAccessException: lateinit property binding has not been initialized
    at com.example.zoomhundred.MainActivity.updateAspectGuide(MainActivity.kt:1247)
    at com.example.zoomhundred.MainActivity.onWindowFocusChanged(MainActivity.kt:1905)
```

On this OEM build, `onWindowFocusChanged()` is dispatched **during `super.onCreate()`** —
before `setContentView` has inflated anything. The handler refreshed the aspect guide and the
loupe position unconditionally, dereferencing `binding` and `settings` while both were still
uninitialised. Result: every launch died, before any camera code ran, which is why the app
"never opened".

**Fix:** the handler returns early until initialisation has completed (and still guards and
wraps the refresh), `displayRotation()` no longer touches `binding` pre-inflation, and the
swallowed focus event is replaced by a post-layout refresh from `onResume`. `CrashGuard` was
also changed to keep the *first* report, since the original cause had been overwritten by the
crash it triggered.

Everything below was added while hunting this bug and remains in place as defence in depth.

### 2.0.1 — startup hardening

The 2.0.0 build was reported as crashing on open. The launch path was audited line by line
and every failure mode that can kill a camera app during `onCreate` was removed:

| Failure mode | Why it crashed | Fix |
|---|---|---|
| `CameraManager.cameraIdList` throwing (`CameraAccessException` when another app holds the camera, or from an OEM HAL) | Uncaught on the main thread while detecting capabilities | Guarded; detection body wrapped, falls back to empty capabilities. Detection now also runs off the main thread, so a slow HAL cannot delay the first frame or ANR |
| Empty lens list | `finish()` — the app closed instantly, which looks exactly like a crash | Screen stays up, states the reason, offers Retry |
| Any failure in `bindCamera`/session UI after a successful bind | Live preview torn down by an uncaught exception | `bindSessionUi` and `applySettingsToUi` individually guarded and logged |
| Any legacy `SharedPreferences` type mismatch (`getInt` on a value written as `Float`/`String`) | `ClassCastException` in `AppSettings.load`, i.e. before any UI exists | New `safeInt/safeBoolean/safeFloat/safeString` readers in `model/SafePrefs.kt`; every settings read falls back to its default |
| Thumbnail query/decoding on the I/O executor | Uncaught background exception kills the process | Fully guarded |
| Partial initialisation | `onResume`/`onPause`/`onDestroy`/`onConfigurationChanged`/`onKeyDown` touching uninitialised `lateinit` fields | Single `uiReady` flag; nullable `OrientationEventListener` |
| Anything else | — | Launch is split into guarded phases; a failure renders a code-built error screen (no dependency on the broken resources) with the exception and a working **Copy report** button |

Crash evidence is now first-class: `diagnostics/CrashGuard` (installed by `Open100xApp`)
writes the stack trace to `files/crash/last_crash.txt`, and the next launch shows it with
**Copy report / Clear**. Device, OS and stack trace only — no photos, no EXIF, no location,
nothing uploaded.

The 2.0.1 APK is built by CI on every push (artifact `open100x-apks`) and by the
tag-triggered release workflow.

---

## 1. What was built

The existing app was re-architected in place — not replaced by a new app — around five
layers:

| Layer | Files | Responsibility |
|---|---|---|
| Capability | `camera/DeviceCapabilities.kt`, `camera/CapabilityDetector.kt` | Everything knowable about the hardware *before* opening a camera, and the `FeatureState` gate that the whole UI obeys |
| Session | `camera/CameraEngine.kt`, `camera/CameraControls.kt`, `camera/ZoomEngine.kt` | Binding, use cases, Camera2 interop, zoom arithmetic, analysis stream |
| Capture | `camera/CaptureCoordinator.kt`, `computational/*` | Single, burst, bracket, focus stack, night, HDR, panorama, light trail, interval |
| Imaging | `imaging/PostCapture.kt` + `BitmapOps`, `ColorPipeline`, `LutLoader`, `CreativeEffects`, `ImageStore` | One deterministic render chain for every still, then MediaStore |
| Interface | `MainActivity`, `SettingsActivity`, `GalleryActivity`, `ui/ToolsSheet`, `ui/ProPanelSheet`, `overlay/*` | Viewfinder, gestures, sheets, settings, library, all drawing |

`video/VideoRecorderController.kt` handles recording (pause/resume, elapsed, bitrate) and
`diagnostics/DiagnosticsExporter.kt` produces the capability report and preset exports.

## 2. Visual redesign (substantial, not a reskin)

* **Layout rebuilt from zero** for portrait *and* landscape (`layout/activity_main.xml`,
  `layout-land/activity_main.xml`) with identical view-id sets and types — verified
  programmatically, so rotation never breaks binding.
* **Design system**: viewfinder `#07080A`, chrome `#101115`–`#202329`, text
  `#F3F4F6`/`#A2A8B0`/`#6C727A`, single lilac accent `#CDBBFF`, record red `#FF4D4F`;
  44–48 dp controls, 76 dp shutter, 34 dp chips at 17 dp radius; one vector icon language;
  no gradients-as-decoration, no glassmorphism, no emoji.
* **New components**: draggable focus loupe, log-scale zoom rail with hardware-aware
  presets, technical HUD, five-state capture button with progress ring, spirit level, focus
  peaking, zebra, IRE false colour, seven composition guides plus an aspect mask, extreme
  zoom FOV readout/crop box/target lock, stability edge glow.
* **Hierarchy**: the viewfinder is untouched by default; secondary controls live in the
  Tools sheet, the Pro panel and the precision-zoom drawer.

## 3. Feature status (summary)

Full table with reasons: [`FEATURE_COMPATIBILITY.md`](FEATURE_COMPATIBILITY.md).

**Fully implemented (software + real capture paths)**
Zoom 100× hybrid with digital crop baked into files, settled capture, zoom lock, presets,
lens switching; JPEG/WebP capture; HDR multi-frame fusion; burst; timer; interval stills;
exposure bracket; focus stacking; night stacking; light trails; document enhancement;
portrait bokeh (labelled simulated); fisheye (labelled simulated); panorama (labelled
experimental); 9 colour profiles; `.cube` LUT import/apply with intensity; 9 frame styles;
video recording 720p–4K with gated fps, OIS/EIS as separate modes, pause/resume, audio and
bitrate control; ISO/shutter/EV/AE-lock, AF/MF, focus lock, WB presets + Kelvin; histogram,
zebra, peaking, false colour, level, HUD, loupe, grids; gallery with EXIF and before/after;
12-category settings; capability/preset export.

**Hardware-gated (works where the device allows, labelled otherwise)**
RAW/RAW+JPEG, 4K60, 60 fps, OIS, EIS/preview stabilisation, ultra-wide module, manual
exposure, manual focus.

**Deliberately not implemented / impossible in this stack**
Sensor log and RAW video (no public API), 120/240 fps (no high-speed session in CameraX
1.4), 360 capture, multi-frame super-resolution, MP4 time-lapse assembly, hyperlapse, named
preset slots, settings import, scene detection.

## 4. Bugs fixed during the work

* Burst completion reported an empty error string (`onError("")`) — now a real status.
* Digital-zoom captures were saved at the sensor field of view, not the framed view — the
  crop is now applied to the file and recorded in EXIF.
* Portrait renderer recycled a bitmap it then used — bokeh re-composed safely.
* Panorama stitcher carried dead state and a broken luma buffer reference.
* The self-timer could fire a capture after being cancelled or after `onPause` — replaced
  with a token-checked countdown.
* Monitor overlays declared `var enabled`, colliding with `View.setEnabled` at the JVM
  level — renamed and guarded.
* Zoom presets advertised a 0.5× stop on 1× lenses; presets now follow the current lens
  (ultra-wide is a lens switch, not a fake zoom stop).
* `CaptureCoordinator` HDR/night/astro/document paths were reachable only through `shoot()`;
  the shutter now routes through it, so those modes actually run their pipelines.

## 5. Build and test results

CI: `.github/workflows/android-ci.yml` (JDK 17, SDK 35, build-tools 35.0.0) on the final
commit:

| Step | Result |
|---|---|
| `testDebugUnitTest` (zoom math, `.cube` parser, Pro tables — 27 tests) | **passing** |
| `assembleDebug` | **passing** → `app-debug.apk` |
| `assembleDebugAndroidTest` | **passing** (instrumentation tests compile) |
| `assembleRelease` (R8 + resource shrinking) | **passing** → `app-release.apk` |

Artifacts of the green run are attached to the workflow run as **`open100x-apks`**
(both APKs, ~9 MB) and **`open100x-unit-test-report`**. GitHub Actions artifacts expire;
`release.yml` publishes both APKs to a GitHub Release when a tag is pushed, and the local
build commands are in the README.

Instrumentation tests (`ScreenSmokeTest`) cover the settings screen's twelve categories and
the gallery's empty state; they need a device/emulator (`connectedDebugAndroidTest`), which
this environment does not provide.

## 6. Verification limits (stated plainly)

* No Android device or emulator was available, so camera behaviour was verified by
  construction, static reasoning and CI compilation — not by shooting photos. Camera paths
  are capability-gated so that anything unverified on a given device is labelled rather than
  silently wrong.
* APKs are produced by CI, not locally (this sandbox has no JDK/Android SDK). Their paths
  are `app/build/outputs/apk/debug/app-debug.apk` and
  `app/build/outputs/apk/release/app-release.apk` inside the build, and inside the
  `open100x-apks` artifact.
* Release signing is untouched: the APK is signed only when `keystore.properties` exists.
  CI has no keystore, so the CI release APK is unsigned (installable only after signing).

## 7. Privacy and safety

No `INTERNET` permission; no analytics; media through MediaStore; LUTs and originals in
app-private storage; diagnostics contain model/API/settings/capabilities only — no GPS, no
paths, no identifiers; location EXIF stripped unless explicitly enabled.

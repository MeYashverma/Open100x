# Open 100x — Architecture

> Status: implementation of the redesigned camera/videography app in the existing
> `com.example.zoomhundred` codebase. This document describes what is actually on disk, not
> an aspiration. Anything not implemented is listed in
> [`FEATURE_COMPATIBILITY.md`](FEATURE_COMPATIBILITY.md) with its honest state.

## 1. Design goals

1. **The viewfinder is the product.** Chrome occupies the edges; every secondary control
   lives in a sheet or the drawer, never as a permanent wall of buttons.
2. **100× hybrid zoom stays first-class.** It is the reason the app exists; the refactor
   had to keep the *whole* range, improve how the digital part is written to the file, and
   keep the stability feedback that makes handheld 100× usable at all.
3. **No lies.** Every control is rendered from a detected capability. If the hardware
   cannot do it, the control is disabled and labelled (`Supported / Limited / Simulated /
   Experimental / Unavailable`). No silent substitution, no decorative toggles.
4. **On device only.** No network permission, no analytics, no uploads. LUTs, presets and
   diagnostics are files the user exports.
5. **Zero new runtime dependencies.** The existing dependency set is used as-is
   (CameraX 1.4.1, Material 1.12, AppCompat, ExifInterface). Concurrency uses
   `java.util.concurrent.Executors`, not coroutines, so no Kotlin coroutines artifact had
   to be added.

## 2. Module map

```
com.example.zoomhundred
├── MainActivity.kt              viewfinder: gestures, zoom, shutter routing, live overlays
├── SettingsActivity.kt          12 capability-aware categories, diagnostics/preset export
├── GalleryActivity.kt           on-device gallery of the app's own album + before/after
├── AppInfoActivity.kt           licences / about
│
├── model/
│   ├── PhotoModels.kt           AspectRatio, PhotoFormat, FlashMode, GridMode, ColorProfile,
│   │                            FrameStyle, VideoCodec, VideoResolution, FocusMode, WbMode…
│   ├── AppSettings.kt           12-category settings object + JSON persistence
│   ├── ProSettings.kt           manual ISO/shutter/EV/focus/WB tables + persistence
│   └── ZoomPreset.kt            (label, ratio)
│
├── camera/
│   ├── DeviceCapabilities.kt    LensCapabilities, EncoderCapabilities, FeatureState, FeatureGate
│   ├── CapabilityDetector.kt    CameraCharacteristics + MediaCodecInfo probing, pre-bind
│   ├── ZoomEngine.kt            pure 100× math: native vs digital split, presets, slider curve
│   ├── CameraControls.kt        Camera2 interop builders + session request options
│   ├── CameraEngine.kt          provider/lens binding, analysis, zoom, torch, focus, capture
│   ├── CaptureCoordinator.kt    single/burst/bracket/focus-stack/night/HDR/pano/light/timelapse
│   ├── AntiShakeController.kt   gyro stability meter + settled-capture trigger
│   ├── ProCameraController.kt   (legacy helper, retained)
│   └── ZoomAnimator.kt          (legacy helper, retained)
│
├── video/VideoRecorderController.kt   MediaStore recording, pause/resume, elapsed + bitrate
│
├── imaging/
│   ├── BitmapOps.kt             crop/scale/rotate/box-blur/pointer helpers
│   ├── LutLoader.kt             dependency-free .cube parser (1D + 3D) and LutStore
│   ├── ColorPipeline.kt         nine software colour profiles + LUT blend
│   ├── CreativeEffects.kt       portrait (software bokeh), fisheye, frames
│   ├── ImageStore.kt            MediaStore writes, originals, gallery query, share
│   ├── PostCapture.kt           the single render chain used by every capture path
│   └── ExtremeZoomPostProcessor.kt  optional zoom detail enhancement
│
├── computational/
│   ├── Stacking.kt              FrameProvider/StackOptions, NightStacker, HdrFusion,
│   │                            LightTrailStacker, FocusStacker
│   └── PanoramaStitcher.kt      translation-only sweep stitch (experimental)
│
├── overlay/                     custom views, all drawing code, no images
│   ├── LoupeView.kt             draggable magnified live crop (luma)
│   ├── ZoomRailView.kt          log-scale presets + scrub track
│   ├── HudView.kt               technical readout (focal length, ISO, shutter, WB, stab…)
│   ├── CaptureButtonView.kt     photo/video/recording/paused/busy states, progress ring
│   ├── MonitorViews.kt          LevelIndicatorView, FocusPeakingView, ZebraView
│   ├── GridOverlayView.kt       composition guides + aspect mask + centre cross
│   ├── ExtremeZoomOverlayView.kt FOV readout, crop box, drift, target lock
│   ├── HistogramView.kt         live luma histogram + clipping flag
│   ├── ShakeMeterView.kt        stability meter
│   └── HeatHazeView.kt          (retained, not mounted)
│
├── ui/
│   ├── SheetUi.kt               shared programmatic UI kit (type scale, chips, rows, pills)
│   ├── ToolsSheet.kt            modes, multi-frame tools, look, monitoring, video
│   └── ProPanelSheet.kt         ISO/shutter/EV/focus/WB + monitoring toggles
│
└── diagnostics/DiagnosticsExporter.kt   capability + settings report (no GPS, no identifiers)
```

## 3. Data flow

### 3.1 Session binding

```
MainActivity.startEngine()
  → CameraEngine.initialize()  ── CapabilityDetector.detect() ── CameraCharacteristics,
  │                                                              MediaCodecInfo, sensor list
  → pickDefaultLens()          ── prefers the longest focal length (telephoto) module
  → bindCamera()
      → CameraEngine.bind(lens, mode, surfaceProvider, pro, settings, rotation, video, forceRaw)
          → CameraControls.previewBuilder / imageCaptureBuilder / videoCaptureBuilder
              (Camera2Interop: OIS, AE/AF/AWB modes, manual ISO/shutter, RAW output)
          → preview + analysis (+ ImageCapture | VideoCapture) bound to the lifecycle
      → ZoomEngine(min, engine.maxZoom, 100)  → zoom presets and slider curve
```

Capability detection happens **before** binding, so the UI can already say "RAW is
unavailable here" or "4K60 is not offered on this encoder" without opening a session.

### 3.2 Zoom

`ZoomEngine` splits a requested total ratio into:

* `native` — handed to `CameraControl.setZoomRatio` (real sensor crop / optical switch),
* `digital` — `total / native`, applied as `PreviewView.scaleX/scaleY` for the live view
  and as a real crop of the captured bitmap in `PostCapture.cropDigital`.

The crop is deliberately **not** followed by an upscale: at 100× on a 12 MP sensor the
saved file is roughly 120 px wide, which is the truth about digital zoom. The HUD shows
total/native/digital and the 35 mm-equivalent focal length, and EXIF records the ratio, so
nothing is hidden.

`AntiShakeController` feeds two things: the shake meter/edge glow, and the **settled
capture** path — above 15× the shutter waits (max 2.6 s) for a quiet gyro window before
firing, which is what makes handheld long-range shots usable.

### 3.3 Capture

```
MainActivity.onShutterPressed()
  ├── VIDEO            → VideoRecorderController.start/pause/stop
  ├── sequence active  → CaptureCoordinator.capturePanoramaFrame() / finishSequence()
  ├── timer > 0        → countdown → takePhoto()
  ├── zoom ≥ 15×       → beginSettledCapture() → takePhoto()
  └── takePhoto()
        ├── PANORAMA → startSequence(PANORAMA)
        ├── LIGHT    → startSequence(LIGHT_TRAIL)
        ├── TIMELAPSE→ startSequence(TIMELAPSE)
        ├── RAW requested → rebind with forceRaw, capture, (RAW+JPEG) rebind and capture JPEG
        ├── NIGHT/ASTRO → singleShot() → CaptureCoordinator.nightShot()/stackNight()
        ├── PRO      → singleShot() (manual request already in the session)
        └── default  → singleShot()
```

`CaptureCoordinator` owns every multi-frame flow and always ends in the same place:

```
frames → (align + stack/fuse/stitch) → PostCapture.renderBitmap(src, params)
       → ImageStore.writeBitmap(...)  → listener.onSaved(uri)
```

`PostCapture.process` is the only writer: it decodes, applies EXIF orientation, optionally
keeps the untouched original, renders (portrait → fisheye → digital crop → aspect crop →
mirror → colour/LUT → frame → zoom enhancement), writes in the requested format (RAW is
never re-encoded) and writes provenance EXIF with GPS stripped or kept per settings.

### 3.4 Live analysis

`ImageAnalysis` delivers a luma-only `LumaFrame` on a dedicated executor. MainActivity
throttles consumers independently:

| Consumer | Period | Purpose |
|---|---|---|
| Histogram | 120 ms | luma distribution + clipping flag |
| Loupe | 110 ms | draggable magnified crop |
| Peaking / Zebra | 140 ms | edge mask / highlight stripes |
| Freeze scope | 260 ms | frozen focus aid |

All consumers run on the analysis thread and only `postInvalidateOnAnimation()` to the UI
thread — the main thread never touches a camera buffer.

## 4. Threading and lifecycle

* `CameraEngine` — one analysis executor, one capture executor.
* `CaptureCoordinator` — one worker executor; every stack/merge/encode happens there,
  callbacks are marshalled to the main `Handler`.
* `MainActivity` — owns UI state only. Countdown, settled capture, drawer timers and the
  recording tick are all `Handler` posts removed in `onPause`/`onDestroy`.
* `onPause` cancels the countdown, cancels settled capture, stops recording, stops the gyro
  and persists settings; `onDestroy` releases camera, coordinator, recorder, executors and
  recycles the scope bitmap.
* Rotation is handled without recreation (`configChanges` in the manifest); the target
  rotation is pushed into the use cases on `onConfigurationChanged`.

## 5. Honesty model

`FeatureState` is the single source of UI truth:

| State | Meaning | UI behaviour |
|---|---|---|
| `FULL` | native support, verified from characteristics | normal |
| `LIMITED` | works with a documented restriction | shown with the restriction |
| `SIMULATED` | computed in software (fisheye, portrait bokeh) | labelled "Simulated" |
| `EXPERIMENTAL` | implemented but not dependable (panorama, time-lapse video) | labelled |
| `UNAVAILABLE` | hardware/pipeline cannot do it | disabled, reason shown |

The gate is computed in `camera/DeviceCapabilities.kt` (`FeatureGate`) and consumed by
`ToolsSheet`, `ProPanelSheet`, `SettingsActivity` and the mode strip. `MainActivity` never
enables a control that the gate reports unavailable.

## 6. Privacy

* No `INTERNET` permission is declared.
* Media goes to `MediaStore` (`Pictures/<album>`, `Movies/<album>`).
* LUTs live in app-private `files/luts`; originals in app-private storage.
* Diagnostics/preset exports are written to `Downloads/Open100x` and contain the model,
  API level, settings and capability tree — **no GPS, no file paths, no advertising id**.
* Location EXIF is stripped unless the user explicitly turns on location tagging.

## 7. Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM tests (zoom math, .cube parser, Pro tables)
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease        # minified release (signed when keystore.properties exists)
```

Requirements: JDK 17, Android SDK 35, build-tools 35.0.0. CI (`.github/workflows/android-ci.yml`)
runs the same commands on every push and uploads both APKs plus the unit-test report.

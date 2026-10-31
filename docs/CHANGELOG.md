# Changelog

All notable changes to Open 100x. The app keeps its package identity
(`com.example.zoomhundred`) and its release/signing configuration across versions.

## [2.0.4] — camera startup: diagnose and survive OEM bind rejection

After the vector fix the UI launched, but the viewfinder stayed black. A failed bind only
produced a two-second toast, and a session that opened without producing frames produced
nothing at all, so the screen was indistinguishable from a hang.

### Added — diagnostics
* `CameraEngine.bind` now tries the requested use-case set and then degrades step by step —
  preview + analysis + capture → preview + capture → preview + analysis → preview → preview
  without any Camera2 interop → preview with the default facing selector — instead of giving
  up on the first rejection. Each attempt logs its own failure.
* The first failure's type and message are kept in `lastBindDiagnostic` and shown, not toasted:
  persistent status line, a one-off dialog with **Retry / Copy / Cancel**, and a `CAMERA ERROR`
  badge. `Copy` includes the recent handled-error log.
* The live session's `CameraState` is observed; an error state (`ERROR_STREAM_CONFIG`,
  `ERROR_CAMERA_IN_USE`, …) is reported with its code and cause.
* A first-frame watchdog reports "camera opened but delivered no frames within 5 s" when the
  analysis stream never ticks, which is what a silently frozen session looks like.
* Handled errors are now written to `files/crash/recent_errors.txt` and **no longer trigger
  safe mode** on the next launch (only real crashes do).

### Changed
* `PreviewView` uses `implementationMode="compatible"` (TextureView). SurfaceView previews are
  a known black-screen source on some OEM builds with edge-to-edge windows and heavy overlays,
  and TextureView composites correctly with the loupe/grid/zebra stack and digital-zoom scale.
* AE/AWB/AF Camera2 interop keys are only injected when the user actually took manual control;
  pushing AUTO values into every repeating request is redundant and some HALs reject it.

## [2.0.3] — the root cause: invalid vector path data

Reported trace (OPPO CPH2731, Android 16 / SDK 36):

```
android.view.InflateException: Binary XML file line #222 in layout/activity_main:
  Error inflating class ImageView
Caused by: Resources$NotFoundException: File res/drawable/ic_timer.xml
Caused by: IllegalArgumentException: a needs to be followed by a multiple of 7 floats.
  However, 10 float(s) are found. Failure occurred at position 6 of path:
  M12 21a8 8 0 100-16 8 8 0 000 16z
```

The icon set used compact SVG arc syntax (`a8 8 0 100-16`), where the two arc flags are
written without separators. Android's `PathParser` is stricter than a browser: it reads `100`
as a *single* number, so the arc ends up with 10 parameters instead of 7 and the whole vector
is rejected. `aapt` does not validate path data, so it compiled cleanly and then threw every
time `activity_main` was inflated — the app could never open. This is the original bug; the
`onWindowFocusChanged` crash fixed in 2.0.2 was the *fallout*, because inflating the layout
failed, leaving `binding` uninitialised.

### Fixed
* **22 vector drawables** repaired: every malformed path was re-parsed with the SVG grammar
  (arc flags as single digits) and re-serialised with explicit separators. All 133
  `android:pathData` values in the app now parse under Android's stricter rules.
* Version 2.0.3 / versionCode 6.

### Added — regression tests
* `resources/VectorDrawablePathTest` walks every `android:pathData` in `res/` and asserts each
  command receives a multiple of its arity, mirroring `PathParser`. A malformed icon can no
  longer reach a device.
* `resources/LayoutParityTest` asserts that every `activity_main` configuration variant
  declares exactly the same view ids as `layout/activity_main.xml`, which is what ViewBinding
  requires at inflation time.

## [2.0.2] — the actual launch crash, fixed

**Root cause, confirmed from a reported stack trace on an OPPO CPH2731 running Android 16
(SDK 36):**

```
kotlin.UninitializedPropertyAccessException: lateinit property binding has not been initialized
    at MainActivity.updateAspectGuide(MainActivity.kt:1247)
    at MainActivity.onWindowFocusChanged(MainActivity.kt:1905)
```

Some OEM builds dispatch `onWindowFocusChanged()` **during** `super.onCreate()`, before the
activity has inflated its view hierarchy. `onWindowFocusChanged` refreshed the aspect guide
and the loupe position unconditionally, so it dereferenced `binding` (and `settings`) while
they were still uninitialised — the app died on every single launch, before any of the
guarded startup code could run. It was not device-specific to 2.0.0 or 2.0.1: the same
handler existed in both.

### Fixed
* `onWindowFocusChanged` returns early until initialisation has completed, and the refresh
  is wrapped so it can never take the app down.
* `displayRotation()` no longer touches `binding` before inflation.
* Because the pre-inflation focus event is now ignored, the aspect guide and loupe source
  are refreshed once from `onResume` after the layout has real dimensions.
* `diagnostics/CrashGuard` now *appends* reports and keeps the head of the file, so the first
  failure (the root cause) is never overwritten by the crashes it causes.

## [2.0.1] — startup stability

Follow-up to the 2.0.0 rewrite after the app was reported as "not opening / keeps crashing".

### Fixed — launch path
* `CapabilityDetector.detect` no longer lets a throwing OEM camera HAL kill the app:
  `CameraManager.cameraIdList` is guarded, and the whole detection body is wrapped with a
  fallback to empty capabilities.
* `MainActivity` no longer calls `finish()` when no lens is reported. A transient HAL
  failure used to look exactly like a crash; the screen now stays up, explains the reason
  and offers Retry.
* The launch path (`inflate` → settings → controllers → UI → camera start) is split into
  guarded phases. A failure shows a code-built error screen with the exception and a
  copyable trace instead of the system "keeps stopping" dialog.
* Camera session UI work (`bindSessionUi`) and `applySettingsToUi` are individually
  guarded: a UI-side bug can no longer take down a live preview.
* `onResume`/`onPause`/`onDestroy`/`onConfigurationChanged`/`onKeyDown` check an
  initialisation flag, so a partial start cannot throw later.
* `OrientationEventListener` is nullable — a sensor failure no longer leaves an
  uninitialised `lateinit`.
* Thumbnail loading on the I/O executor can no longer throw out of the thread.

### Fixed — settings
* New `SharedPreferences.safeInt/safeBoolean/safeFloat/safeString` helpers; every settings
  read now falls back to its default instead of throwing `ClassCastException` when a key
  changed type between releases (a classic crash-on-open after updating).

### Added — safe mode
* `LauncherActivity` runs before any camera code. Without a report it starts the camera
  immediately (no layout inflation); with a report it shows a code-built safe-mode screen
  with the trace plus *Copy report / Reset settings / Start anyway / Discard report*. The
  trace is therefore reachable even when `MainActivity`, its theme or its layouts are the
  thing that broke. `MainActivity` no longer owns the report dialog.

### Added — crash reporting
* `Open100xApp` + `diagnostics/CrashGuard`: uncaught exceptions and recoverable failures
  are written to `files/crash/last_crash.txt` (device, OS, stack trace only — no photos,
  no EXIF, no location, nothing uploaded). The next launch offers the trace with a copy
  button, so an OEM-specific failure can be reported without a debugger.

## [2.0.0] — redesign + camera engine rewrite

This is a full re-architecture of the app around a capability-driven camera engine, plus a
complete visual redesign. The 100× hybrid zoom engine from 1.x is preserved and now writes
the digital crop into the saved file.

### Added — camera engine

* `camera/CapabilityDetector` — pre-bind detection of lenses (FoV from focal length and
  sensor size), RAW/manual-sensor/manual-post-processing/depth capabilities, ISO and
  exposure ranges, focus distance, OIS and video stabilisation modes, stream map, FPS
  ranges, physical camera ids, and hardware encoder support (H.264/HEVC, 4K30, 4K60,
  1080p60).
* `camera/DeviceCapabilities` — `FeatureState` / `FeatureGate` vocabulary
  (Supported / Limited / Simulated / Experimental / Unavailable) used by every surface.
* `camera/CameraEngine` — provider and lens binding per capture mode, analysis stream,
  zoom, torch, tap-to-focus metering, manual exposure via Camera2 interop (both at bind
  time for stills and live via session options), video recording binding.
* `camera/CaptureCoordinator` — single shots, bursts, exposure brackets, focus stacks,
  night/astro stacking, HDR fusion, panorama sweeps, light-trail accumulation and interval
  capture, all funnelling through one post-capture pipeline.
* `camera/ZoomEngine` — pure zoom arithmetic with unit tests: native ratio vs digital
  crop, log slider curve, hardware-aware presets, 35 mm-equivalent focal length.
* `video/VideoRecorderController` — MediaStore recording with pause/resume, elapsed time
  and bitrate estimation.
* `computational/Stacking` — alignment + night stacking, HDR fusion, light-trail
  accumulation, focus stacking.
* `computational/PanoramaStitcher` — translation-only sweep stitch (experimental).

### Added — imaging

* `imaging/PostCapture` — one ordered render chain for every still: EXIF orientation,
  original preservation, portrait/fisheye, digital crop, aspect crop, mirror, colour/LUT,
  frames, zoom enhancement, format-specific write, provenance EXIF with optional GPS strip.
* `imaging/LutLoader` — dependency-free `.cube` parser (1D and 3D) with trilinear
  interpolation, import/list/delete storage.
* `imaging/ColorPipeline` — nine software colour profiles with LUT blending.
* `imaging/CreativeEffects` — software portrait bokeh, fisheye distortion, nine frame
  styles rendered at capture time.
* `imaging/ImageStore` — MediaStore writes, untouched originals for before/after,
  gallery query, free-space reporting.

### Added — interface

* New viewfinder: deep-black canvas, lilac accent, 44–48 dp controls, no glassmorphism, no
  emoji icons.
* Overlays: draggable focus loupe, log-scale zoom rail with hardware-aware presets,
  technical HUD, five-state capture button, level indicator, focus peaking, zebra stripes, IRE false-colour,
  seven composition guides with aspect mask, extreme-zoom FOV readout and target lock,
  stability edge glow.
* `ui/ToolsSheet` — secondary modes, multi-frame tools, look controls, monitoring, video
  settings; every unavailable option is dimmed and labelled with the reason.
* `ui/ProPanelSheet` — independent Pro panel: ISO, shutter, EV, AE lock, AF/MF with a
  distance slider, focus lock, WB presets and Kelvin, monitoring toggles, reset.
* `SettingsActivity` — twelve capability-aware categories plus diagnostics and preset
  export.
* `GalleryActivity` — grid of the app's own album, detail sheet with share/open/delete and
  before/after when the untouched original was kept.
* Portrait **and** landscape layouts with identical view-id sets; rotation handled without
  activity recreation.

### Changed

* The 100× engine now applies the digital crop to the captured file (no fake upscale) and
  records the zoom factor in EXIF; a settled-capture path waits for gyro stillness above
  15×.
* Night/astro/HDR/document/portrait/fisheye modes are real capture pipelines instead of
  settings that only changed a label.
* Volume keys, long-press shutter (burst / pause / Pro panel), pinch, drag-to-zoom,
  double-tap zoom toggle and tap-to-focus are all wired.

### Fixed

* Burst completion used to report an empty error string; it now reports its own status.
* Portrait renderer re-used a recycled bitmap; bokeh now composes safely.
* Panorama stitcher carried dead state and a broken luma buffer reference.
* Histogram now exposes a clipping flag so the HUD can warn about blown highlights.
* Digital-zoom captures used to be saved at the sensor field of view; they now match the
  framed field of view.

### Known limitations

See [`FEATURE_COMPATIBILITY.md`](FEATURE_COMPATIBILITY.md). Highlights: no sensor log, no
120/240 fps, no RAW video, no 360 capture, no super-resolution, no MP4 time-lapse assembly,
software-only bokeh/fisheye, experimental panorama, RAW depends on the pinned CameraX
artifact exposing `setOutputFormat`.

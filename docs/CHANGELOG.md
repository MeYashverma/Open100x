# Changelog

All notable changes to Open 100x. The app keeps its package identity
(`com.example.zoomhundred`) and its release/signing configuration across versions.

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
  technical HUD, five-state capture button, level indicator, focus peaking, zebra stripes,
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

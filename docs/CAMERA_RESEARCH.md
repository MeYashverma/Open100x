# Camera Research — Open 100x Redesign

Status: research complete for the Stage 1–3 implementation pass.
Scope: what other camera applications do, which Android APIs actually expose those
capabilities, what is feasible on a 3rd-party Android app, and what Open 100x should
build, gate, simulate or refuse to ship.

This document is deliberately concrete: every "approach" below maps to a module in
`docs/ARCHITECTURE.md`. Anything the platform cannot deliver is listed in
`docs/FEATURE_COMPATIBILITY.md` instead of being advertised in the UI.

---

## 1. Applications and repositories examined

### 1.1 Photon Camera (`bjzhou/PhotonCamera`) — primary technical inspiration

Open source, Camera2-first, "static photography" oriented. Architectural breakdown
(from the project's published architecture notes):

| Layer | What it contains |
| --- | --- |
| UI | Compose screens: camera, gallery, photo edit |
| ViewModel | `CameraViewModel`, `GalleryViewModel` — state + coordination |
| Business logic | `Camera2Controller`, `PhotoManager`, `PhotoProcessor` |
| Processing | `MultiFrameStacker`, `RawDemosaicProcessor`, `LutImageProcessor` |
| Native/data | JNI + C++ (OpenMP/NEON), Vulkan compute, RAW10/12, FP16 JPEG XL sidecars |

Techniques worth adopting:

1. **Stacking is the quality strategy, not sharpening.** Multi-frame alignment +
   merging is where low-light quality comes from. Photon stacks frames, keeps a
   high-precision sidecar, and renders tone last.
2. **RAW when available, and a high-precision intermediate when not.** Phones that
   expose `RAW_SENSOR` give real demosaic headroom; phones that don't still benefit
   from stacking YUV frames before tone mapping.
3. **Non-destructive storage layout**: `original.jpg` preview + high-precision
   `original.*` + `metadata.json` (settings/edit state). Editing never re-encodes
   from an already-sharpened JPEG.
4. **GPU compute for stacking/RAW.** Their Vulkan path exists because CPU stacking of
   full-resolution frames is too slow. This is the honest cost of the technique.
5. **LUT-based color** ("Photon Tone" / recipes) with a managed LUT library.
6. **Explicit manual controls** (ISO, shutter, WB, EV) via Camera2 — no Camera1.
7. **Burst photography** as a first-class mode, not a hidden gesture.

What Open 100x should *not* copy: the Vulkan/native stack (too large a change for
this codebase), and the JXL/FP16 intermediate (needs a third-party codec; adds a
dependency). Open 100x instead stacks **YUV/JPEG burst frames on the CPU with
downsampled alignment and tiled merging**, which is slower but dependency-free and
runs on every device (see `computational/`).

### 1.2 Google Camera / Pixel Camera — UX inspiration

Publicly documented behaviour (Night Sight, HDR+, Portrait, Panorama, Astrophotography,
slow motion, time lapse, zoom presets, "what you see is what you get" zoom rail):

- **Mode minimalism.** The primary screen shows the viewfinder, one mode strip, zoom
  chips and the shutter. Everything else is one sheet away.
- **Photography-first hierarchy**: shutter is the biggest, brightest element; the UI
  gets out of the frame.
- **Night Sight = capture discipline.** Handheld long-ish exposure, many frames,
  alignment, motion rejection, plus a tripod mode that unlocks much longer captures
  and, on supported hardware, astrophotography.
- **HDR+ = burst + merge + tone map**, i.e. exactly the architecture in §1.1.
- **Zoom rail with a "native-ish" snap**: chips for 0.5×/1×/2×/5×…, plus a fine
  slider, plus a loupe-free but stable framing metaphor.
- **Honest hardware labelling**: Pixel camera will not offer a lens it lacks.

Adopted: mode strip + chip rail + sheet-based secondary controls, capture progress
for computational modes, tripod/stability gating, and explicit "software" labels for
anything simulated. Not adopted: anything requiring proprietary processing or
Pixel-only HAL features.

### 1.3 Open Camera (`opencamera.sourceforge.io`) — pro/manual reference

Well-known open-source Camera2 app. Relevant feature set and what it teaches:

- Manual ISO / shutter / focus / white balance, exposure compensation, RAW/DNG.
- Exposure bracketing, focus bracketing, burst, interval timer.
- Overlays: histogram, zebra stripes, focus peaking, custom grids, level.
- Video: bitrate, audio source, log-ish profiles, frame rate selection.
- Configuration export/import (their `SharedPreferences` → file approach).

Adopted: manual control ranges driven by `SENSOR_INFO_SENSITIVITY_RANGE` /
`SENSOR_INFO_EXPOSURE_TIME_RANGE` (never a hard-coded list), bracketing, interval
capture, peaking/zebra/histogram/level, settings export. Open Camera's settings tree
is deep; Open 100x keeps the same capability but organises it into 12 categories.

### 1.4 Blackmagic Camera (Android) — video/pro UI reference

- Professional video HUD: shutter **angle**, WB, ISO, frame rate, codec/bitrate,
  audio meters, focus assist, recording state, remaining media.
- Scopes and monitoring overlays as *toggleable* layers over the image.
- A clear "recording" visual language (red ring, timecode, lock indicators).

Adopted: recording state language, a compact video HUD (fps/resolution/bitrate,
timer, storage estimate, stabilization status), and monitoring overlays. Not adopted:
shutter-angle semantics as a first-class control — Android's Camera2 exposes exposure
*time*, so Open 100x shows shutter speed (and derives the angle where it is
meaningful, documented in the settings).

### 1.5 Halide / ProCamera — interaction reference

Not open source; reviewed for interaction patterns only:

- Gesture-driven manual control (swipe to focus/exposure, two-finger rotate),
  long-press to lock, and a loupe appearing while dragging.
- Deliberate typographic restraint; number readouts in tabular figures.
- Editing is non-destructive and "before/after" is one tap away.

Adopted: long-press focus/zoom lock, drag-to-adjust pro values, a loupe tied to zoom,
before/after in the gallery. No Halide/ProCamera code or assets are used.

### 1.6 Android platform documentation (primary technical source)

- CameraX overview / camera2 / camera extensions / video capture guides.
- `CameraCharacteristics`, `CaptureRequest`, `CameraMetadata` references.
- `android/camera-samples` for CameraX + Camera2Interop patterns.
- CameraX 1.4.0 release notes (preview stabilization, effect framework).

---

## 2. What the platform actually gives us

### 2.1 CameraX vs Camera2

Decision: **CameraX remains the session/use-case layer; Camera2 (via
`Camera2Interop` / `Camera2CameraControl`) supplies every advanced control.**

Why: CameraX already handles lifecycle, rotation, surface management and recording,
which is the risky part of this app; Camera2 characteristics/interop give us genuine
ISO, shutter, focus, AE/AWB modes, OIS/EIS and stream capability data. Going fully
Camera2 would mean rebuilding preview/recording plumbing with no gain in the controls
we need. This matches the existing Open 100x design and preserves its proven 100×
hybrid-zoom pipeline.

### 2.2 Capability detection (the backbone of the redesign)

All of the following are readable without a camera session, which lets the UI be
decided *before* binding, so unsupported features are never shown as working:

| Capability | Source |
| --- | --- |
| Lens list, focal lengths, physical size | `CameraManager.getCameraIdList()`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, `SENSOR_INFO_PHYSICAL_SIZE` |
| Zoom range | CameraX `ZoomState` (`minZoomRatio`, `maxZoomRatio`) |
| RAW | `REQUEST_AVAILABLE_CAPABILITIES_RAW` |
| Manual exposure/focus | `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`, sensitivity/exposure ranges, `LENS_INFO_MINIMUM_FOCUS_DISTANCE` |
| OIS | `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` |
| EIS / preview stabilization | `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` (contains `PREVIEW_STABILIZATION` on API 33+) |
| Stream combinations (4K, 4K60) | `SCALER_STREAM_CONFIGURATION_MAP`, `getOutputMinFrameDuration`, `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` |
| Encoder capability | `MediaCodecList` / `MediaCodecInfo.VideoCapabilities.isSizeAndRateSupported` |
| Logical multi-camera / physical lenses | `LOGICAL_MULTI_CAMERA_PHYSICAL_IDS` |
| Depth output | `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT` |

Consequence: 4K60 is only offered when *both* the camera stream map and a hardware
encoder can do 3840×2160 at ≥58 fps. Otherwise the UI shows 4K30 or 1080p60 and the
diagnostics report says why.

### 2.3 Stabilization — what is real

- **OIS** (`LENS_OPTICAL_STABILIZATION_MODE`): mechanical, no crop, genuinely improves
  stills. Available only if `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` contains ON.
- **EIS** (`CONTROL_VIDEO_STABILIZATION_MODE`): warps frames, needs a crop margin.
- **Preview stabilization** (API 33+, `..._PREVIEW_STABILIZATION`): stabilizes preview
  *and* non-RAW streams with the same FoV; documentation caps FoV loss at 20%.
  CameraX 1.4 exposes this through `Preview.getPreviewCapabilities(...).isStabilizationSupported`
  + `Preview.Builder.setPreviewStabilizationEnabled(true)` and
  `VideoCapture.Builder.setVideoStabilizationEnabled(true)`.

Android's own reference warns that combining OIS and EIS can interact badly. Open 100x
therefore exposes them as **separate, capability-checked modes** ("Optical",
"Preview/EIS (crops)" ) rather than a vague "stabilization: ultra" toggle, and never
labels a digital crop as "ultra EIS".

A gyro-based post-recording stabilizer is *not* implemented: it requires
timestamps→gyro correlation plus per-frame reprojection/transcode, which would add a
large native/GL surface. It is documented as a roadmap item, not advertised.

### 2.4 Log video / colour profiles — the honest position

Third-party apps on standard Android receive **processed YUV from the camera HAL**;
there is no public path to sensor log output unless the OEM exposes it (vendor tags,
CameraX extensions, or a proprietary SDK). Therefore:

- A "Log / Flat" profile in Open 100x is a **software tone transform** applied to
  captured media, and is labelled exactly that (`Software flat profile`).
- `.cube` LUTs are parsed and applied by the app's own CPU pipeline
  (`LutLoader`, `ColorPipeline`), with intensity control.
- "Genuine camera log" is *not* advertised. When a device can't do it, the UI says so.

### 2.5 RAW/DNG

`ImageCapture` with `OUTPUT_FORMAT_RAW` via `Camera2Interop`
(`CaptureRequest.CONTROL_CAPTURE_INTENT`/`ImageCapture` RAW capture) is supported by
CameraX 1.4 through `ImageCapture.Builder().setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)`
on RAW-capable devices, producing DNG. RAW is gated on the capability flag, and
RAW+JPEG is implemented as two captures (sequential), documented as such. No
demosaic/intermediate edit is implemented: the DNG is handed to the user as-is.

### 2.6 High-frame-rate / slow motion

CameraX has **no high-speed capture session API**. 120/240 fps requires
`CameraConstrainedHighSpeedCaptureSession`, a manual `MediaCodec`/`MediaMuxer`
pipeline and per-device stream-map probing. This is a genuine engineering project on
its own and is **not implemented**; Open 100x does not show a slow-motion button.
Time-lapse (interval capture → MP4 via `MediaCodec`+`MediaMuxer`) is implemented
because it is fully supported by the same YUV/JPEG path.

### 2.7 Panorama/360

No public platform panorama stitcher. Open 100x implements a guided capture plus a
local stitcher (`computational/PanoramaStitcher`) that estimates horizontal
translation between consecutive frames (downsampled luma cross-correlation, feather
blend). Limits are documented: translation-only alignment (no full homography),
handheld drift, exposure drift handled by per-frame gain matching. A 360 sweep is the
same stitcher over a full horizontal rotation with equirectangular export; it is
marked experimental.

### 2.8 Portrait / bokeh

Hardware depth/bokeh is unevenly available and CameraX's extension API surface has
churned across versions. Open 100x implements a **local software bokeh**
(`imaging/PortraitRenderer`): subject mask from face detection
(`android.media.FaceDetector`, framework, no dependency) with an elliptical subject
fallback, masked depth-of-field blur with a configurable strength and edge refinement.
It is labelled *software bokeh* in the UI; it is never described as sensor depth.

### 2.9 Ultra-wide / fisheye

- Ultra-wide is offered only when the camera stack exposes a **physical** lens with a
  materially wider FoV than the main camera (computed from focal length + physical
  sensor size). Otherwise the 0.5× chip is not rendered.
- True fisheye optics are essentially never exposed to 3rd-party apps. Open 100x
  provides a **software fisheye warp** (preview guide + post-capture remap) and
  detects/labels "ultra-wide (no fisheye optics)" instead of pretending.

---

## 3. Dependencies and licences

| Dependency | Purpose | Licence |
| --- | --- | --- |
| AndroidX Core/AppCompat/Activity/ConstraintLayout/Lifecycle | App shell | Apache-2.0 |
| Material Components | Bottom sheets, sliders, buttons | Apache-2.0 |
| CameraX (core, camera2, lifecycle, view, video) | Session, preview, capture, recording | Apache-2.0 |
| ExifInterface | Metadata read/write | Apache-2.0 |

No new third-party libraries are added by this redesign. Image processing, LUT
parsing, stacking, stitching, bokeh and time-lapse encoding are implemented in-repo.
`.cube` LUT files are user-supplied; no LUT is bundled.

Licence discipline: no code is copied from Photon Camera, Open Camera, Google Camera,
Blackmagic or Halide. Their published behaviour and architecture are used as design
input only; all implementation here is original and MIT-licensed with the rest of the
repository.

---

## 4. Hardware and platform limitations (summary)

1. Sensor log / RAW video: not available to 3rd-party apps.
2. 120/240 fps slow motion: requires high-speed sessions (not in CameraX). Not shipped.
3. CameraX extensions (hardware bokeh/HDR/night): available on a subset of devices,
   API surface changed between 1.3 and 1.4; not used — software pipelines are used
   instead so behaviour is consistent and honestly labelable.
4. Physical lens access: logical cameras may hide/merge physical lenses; only
   reported physical cameras are offered.
5. Depth data: only via Camera2 `DEPTH_OUTPUT` (rare, fragmented). Not used.
6. Thermal throttling can silently lower fps and disable computational modes; the
   app surfaces capture-time failures instead of silently degrading.
7. Long exposures above ~1/4 s are unsupported on many HALs; the shutter list is
   clamped to `SENSOR_INFO_EXPOSURE_TIME_RANGE` per device.

---

## 5. Recommended implementation architecture

```
UI (MainActivity, sheets, Settings, Gallery)
        │  events / state
CameraController  ── CapabilityDetector ── DeviceCapabilities
        │                 │
        │                 └── feature gating (supported / limited / simulated / unavailable)
        ├── ZoomEngine (total zoom → native ratio + digital crop, presets per lens)
        ├── CaptureCoordinator
        │      ├── SingleShot + post (crop, frame, LUT, zoom enhance)
        │      ├── HdrFusion (bracket → exposure fusion)
        │      ├── NightStacker (burst → align → median stack → tone)
        │      ├── AstroPipeline (long exposure + stack, tripod gated)
        │      ├── FocusStacker, LightTrailAccumulator
        │      └── PanoramaSession + PanoramaStitcher
        ├── VideoRecorder (CameraX Recorder, stabilization, fps target)
        └── ImageAnalysis → Histogram / Loupe / Peaking / Zebra
```

Rules enforced by the architecture:

- Every feature declares a `FeatureState` (FULL, LIMITED, SIMULATED, EXPERIMENTAL,
  UNAVAILABLE) and the UI renders from that, not from a hand-written list.
- Heavy work runs on dedicated executors, never the main thread; camera resources are
  released on `onDestroy`.
- Post-processing operates on saved files/bitmaps with explicit recycling; no cloud,
  no network.
- The 100× engine stays: native zoom + digital crop + crop nudging + settled capture
  + stability feedback + local enhancement, integrated into the new zoom UI.

---

## 6. References

- Photon Camera — <https://github.com/bjzhou/PhotonCamera> (architecture summary:
  <https://deepwiki.com/bjzhou/PhotonCamera>)
- Android camera documentation — <https://developer.android.com/media/camera>
- CameraX video capture & stabilization —
  <https://developer.android.com/media/camera/camerax/video-capture>
- CameraX 1.4.0 release notes (preview stabilization, effects) —
  <https://android-developers.googleblog.com/2024/12/whats-new-in-camerax-140-and-jetpack-compose-support.html>
- `CameraCharacteristics` reference (stabilization modes, stream map) —
  <https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics>
- CameraX extensions (`ExtensionsManager`) —
  <https://developer.android.com/reference/androidx/camera/extensions/ExtensionsManager>
- Open Camera — <https://opencamera.sourceforge.io/>
- Blackmagic Camera — <https://www.blackmagicdesign.com/products/blackmagiccamera>
- Android camera samples — <https://github.com/android/camera-samples>

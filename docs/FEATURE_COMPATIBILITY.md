# Open 100x — Feature compatibility

This table is the contract between the UI and the hardware. Every row is mirrored in code:
`camera/DeviceCapabilities.kt` computes a `FeatureState`, and the UI renders only from it.
If a feature is listed as **Unavailable** here, the app does not show a working control for
it — it shows the reason.

Legend

| State | Meaning in the UI |
|---|---|
| **Supported** | Native support, verified from `CameraCharacteristics` / encoders. Full control. |
| **Limited** | Works, but with a documented restriction (shown in the control's caption). |
| **Simulated** | Computed in software; the frame is real, the optical effect is not. Labelled. |
| **Experimental** | Implemented but not dependable across devices; labelled and disabled by default where risky. |
| **Unavailable** | Not offered at all on this device (or not implemented by this build). |

Detection happens **before** the camera session is opened, from `CameraCharacteristics`,
`CameraMetadata`, `MediaCodecList` and the sensor list. 4K60, for example, is offered only
when the stream map *and* a hardware encoder report 3840×2160 at ≥58 fps.

---

## 1. Photo

| Feature | State | Implementation / restriction |
|---|---|---|
| JPEG capture | Supported | `ImageCapture` → `PostCapture` → MediaStore |
| WebP capture | Supported | quality 92 |
| RAW / DNG | Supported on devices with `REQUEST_AVAILABLE_CAPABILITIES_RAW` **and** a CameraX artifact exposing `setOutputFormat` | DNG is never re-encoded; only the software tag is written |
| RAW + JPEG | Same gate as RAW | Two-pass capture with an automatic re-bind between passes |
| Multi-frame HDR | Supported | 2/3/5 bracketed exposures (±2 EV) fused by `HdrFusion` (exposure-weighted, alignment radius 4 %) |
| Burst | Supported | 3/5/10 frames at 110 ms spacing |
| Self-timer | Supported | Off / 3 s / 10 s |
| Interval stills | Supported | 1/2/3/5/10 s interval, up to 120 stills |
| Exposure bracketing | Supported | −2…+2 EV in 5 steps, saved as separate files |
| Focus stacking | Limited | Requires manual-focus support; 5-step sweep + max-sharpness blend |
| Super resolution | Unavailable | Not implemented in this build (no multi-frame super-res solver) |
| Macro | Limited | Manual focus at the near limit + digital zoom; no dedicated macro mode |
| Document | Supported | Measured black/white-point stretch; **no** perspective correction |
| Night | Supported | 2–12 aligned frames, exposure-weighted stack |
| Low light | Supported | Same pipeline as Night |
| Astro | Limited | Stacking works; long exposure needs `MANUAL_SENSOR` and ≥0.5 s exposure range; tripod recommended (the app warns when it is shaking) |
| Star trails | Supported | Light-trail accumulator in LIGHT mode (tripod) |
| Light trails | Supported | Additive (LIGHTEN) accumulation |
| Portrait (bokeh) | **Simulated** | Software depth-of-field blur; no depth map is used |
| Selfie | Supported | Front lens, optional mirrored save |
| Panorama | **Experimental** | Translation-only sweep stitch, no bundle adjustment |
| 360 photo | Unavailable | Not implemented |
| Ultra-wide | Supported only when a real ultra-wide module exists | Zoom < 1× is offered only then |
| Fisheye | **Simulated** | Software barrel distortion (real 8 mm look, not real optics) |
| Frame / border styles | Supported | 9 styles rendered at capture time |
| Colour profiles | Supported | 9 software profiles + optional `.cube` LUT blend |
| Extreme-zoom enhancement | Supported | Detail/contrast pass at ≥12× (existing engine, preserved) |

## 2. Video

| Feature | State | Implementation / restriction |
|---|---|---|
| 720p / 1080p / 4K recording | Gated (Supported where the stream map allows) | `Recorder` + `QualitySelector` with fallback strategy |
| 24 / 30 fps | Gated by `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | |
| 60 fps | Gated | 1080p60 needs the encoder; 4K60 needs **both** stream map and hardware encoder ≥58 fps |
| 4K60 | Gated | Offered only on capable hardware; never shown as a "maybe" |
| OIS | Gated (`LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`) | Separate labelled mode |
| EIS / preview stabilisation | Gated (`CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`, preview stab on API 33+) | Separate labelled mode |
| OIS + EIS together | Unavailable | Deliberately never combined — the app labels them as alternatives |
| Manual exposure during video | Limited | Session-level controls apply; still-capture-only options do not |
| WB Kelvin | **Limited** | `COLOR_CORRECTION_GAINS` approximation, not a calibrated white balance |
| Log / flat profile | **Limited** | Software flat curve (no sensor log); pair with a `.cube` LUT |
| `.cube` LUT import (1D + 3D) | Supported | Parsed on device, stored in app-private storage, intensity slider |
| Pause / resume recording | Supported | `Recorder.pause/resume`, timer excludes paused time |
| Slow motion (120/240 fps) | Unavailable | CameraX has no high-speed session in this version |
| Time-lapse (interval stills) | Supported | Frames saved to the gallery; count and interval shown while running |
| Time-lapse (MP4 assembly) | Unavailable | Not implemented in this build — stills are kept |
| Hyperlapse | Unavailable | Depends on the missing assembly/ stabilisation pipeline |

## 3. Pro / manual

| Feature | State | Implementation / restriction |
|---|---|---|
| ISO | Supported when `MANUAL_SENSOR` | `SENSOR_INFO_SENSITIVITY_RANGE` drives the table |
| Shutter | Supported when `MANUAL_SENSOR` | `SENSOR_INFO_EXPOSURE_TIME_RANGE` drives the table |
| EV compensation | Supported | Session-level with hardware range clamping |
| AE lock | Supported | `CONTROL_AE_LOCK` |
| Manual focus | Supported when min focus distance is reported | Dioptre → position curve |
| Focus lock | Supported | AF lock in session options |
| WB presets + Kelvin | Limited | Kelvin via `COLOR_CORRECTION_GAINS` |
| Histogram | Supported | Live luma, 256 bins, clipping flag |
| Zebra | Supported | ≥95 % IRE stripes |
| Focus peaking | Supported | Sobel-style edge mask |
| False colour | Supported | IRE-banded false-colour overlay (monitor button cycles off → histogram → peaking → zebra → false colour) |
| Spirit level | Supported | Accelerometer roll/pitch |
| Composition grids | Supported | Thirds, golden, spiral, cross, diagonal, square + aspect mask |
| Technical HUD | Supported | Focal length (mm and 35 mm-eq), ISO, shutter, EV, WB, focus, fps, stabilisation |
| Focus loupe | Supported | Draggable magnified live crop (monochrome, luma stream) |
| Frozen scope | Supported | Freeze the live luma frame as a focus aid |
| Presets | **Limited** | Zoom presets + exportable settings/Pro snapshot; no named preset slots yet |

## 4. System, data and honesty

| Feature | State | Notes |
|---|---|---|
| Capability report / diagnostics export | Supported | JSON to `Downloads/Open100x`, no GPS, no identifiers |
| Settings export | Supported | JSON with all settings + Pro snapshot |
| Settings import | Unavailable | Not implemented |
| 12-category settings | Supported | Capture, Photo, Video, Zoom, Computational, Pro & monitoring, Composition, Look, Controls, Storage, Privacy, About |
| Gallery | Supported | Own album folder only, thumbnails, detail, share, delete |
| EXIF inspection / before–after | Supported | Original frame kept when enabled; toggle in the gallery sheet |
| Location stripping | Supported | On by default when "strip location EXIF" is enabled |
| Scene detection | Unavailable | No classifier is bundled and no network is permitted |
| Analytics / network | Not present | The manifest declares no `INTERNET` permission |

## 5. Why some things are impossible rather than unfinished

* **Sensor log / RAW video** — no public API exposes a log transfer function or RAW video
  stream through CameraX; a full Camera2 RAW pipeline would be a different project.
* **120/240 fps** — CameraX 1.4 has no constrained high-speed session; advertising it would
  be a lie on most devices.
* **HAL bokeh / depth** — CameraX extensions are unavailable on the majority of devices and
  are SDR-only; the app therefore simulates bokeh and labels it.
* **Perfect panorama** — a dependable stitcher needs bundle adjustment + exposure
  compensation; the translation-only solver here is honest about being experimental.

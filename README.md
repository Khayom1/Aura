# AURA

**Current version: 0.5.0** — portrait-aware camera mapping and low-latency single-hand cursor control.

**A Stark-inspired native Android hands-free control system** for the Samsung Galaxy A35 5G.

AURA is an Android application, not a website. It uses the front camera and on-device MediaPipe hand tracking to control Android through a user-enabled Accessibility service.

## Operating profile

| Setting | Value |
|---|---|
| Activation | Hybrid — user starts/stops an explicit session |
| Camera | Front camera |
| Control | One hand only |
| Inference | MediaPipe GPU delegate when supported; automatic CPU fallback |
| Camera pipeline | CameraX RGBA_8888 frames, latest-frame backpressure, portrait-targeted 360 × 480 analysis |
| Safety | Strict — uncertain input fails closed |
| Processing | On-device; no camera frames are uploaded |
| Cross-app control | Camera foreground service with persistent notification |
| Device actions | Cursor, select, long press, swipe and system navigation |

## Gesture guide

Read [docs/INTERACTION_CONTRACT.md](docs/INTERACTION_CONTRACT.md) for the full interaction grammar and safety rules.

- Index finger only: move the floating cursor.
- Thumb-index pinch, then release: select once.
- Pinch and hold: long press.
- Pinch and move, then release: drag/swipe.
- Open palm: pause and hide the cursor.
- System navigation: hold a fist to arm command mode, then use a V-sign swipe for Back, Home, Recents, Notifications or Quick Settings.

**Two-hand tracking and two-finger zoom are disabled.** The model is configured for one hand to reduce inference work and keep the control path simple.

## Performance design

- MediaPipe GPU delegate is attempted first and falls back to CPU when unsupported or unstable.
- The delegate is initialized lazily on the same dedicated worker thread that runs inference.
- CameraX keeps only the latest frame, avoiding a queue of stale frames.
- CameraX RGBA frames are copied into one reusable ARGB bitmap per session; the app does not allocate or transform a new bitmap for each frame.
- Analysis and preview use matching portrait 3:4 target resolutions; CameraX target rotation is refreshed when the physical display rotates.
- MediaPipe landmarks are explicitly rotated from unrotated input coordinates into upright screen coordinates, then mirrored for the front-camera preview.
- The cursor uses one full-screen non-touchable accessibility overlay. Per-frame movement invalidates a Canvas instead of forcing a WindowManager relayout.
- Pointer filtering adapts to movement speed; Activity/HUD redraws are removed from the per-camera-frame cursor path.
- The in-app status displays measured analysis FPS, average model inference latency and the active GPU/CPU delegate after warm-up.

## Build and CI

The GitHub Actions workflow runs unit tests and builds a debug APK. It downloads Google's pinned Hand Landmarker task bundle during the build and packages it as a local app asset.

- JDK 17
- Gradle 8.11.1
- Android SDK platform 36
- CameraX 1.6.2
- MediaPipe Tasks Vision 1.2.2

## Install and first use

For phone-only Termux installation steps, see [docs/TERMUX_INSTALL.md](docs/TERMUX_INSTALL.md).

1. Download the `aura-debug-apk` artifact from a successful GitHub Actions run.
2. Install the APK on the phone.
3. Open AURA and grant Camera permission.
4. Tap Start Session.
5. Open Android Accessibility settings from AURA and explicitly enable AURA Device Control.
6. Return to AURA. Its foreground notification indicates that the camera session is active. Use Stop AURA in the notification or the app button to end it.

Android requires the user to confirm APK installation and Accessibility enablement manually. AURA cannot bypass these system protections.

Third-party model and SDK attribution is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Privacy and resources

- Hand inference runs locally; camera frames and landmarks are not uploaded.
- The merged APK removes Android's INTERNET permission. The model is fetched only during the build.
- The camera remains active across apps only while the foreground session is running.
- Android displays its camera privacy indicator and AURA's ongoing notification.
- Continuous camera inference uses battery and can warm the phone. Stop the session when it is not needed.

## Project status

This is an active prototype. CI verifies source compilation, unit tests and APK packaging; it does not replace physical-device validation. The coordinate mapping has unit-test coverage for all four 90-degree rotations. Actual frame rate, inference latency, touch alignment, GPU-driver behavior, sustained battery use and thermal behavior still need physical validation on the Galaxy A35.
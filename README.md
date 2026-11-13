# AURA

**A Stark-inspired native Android hands-free control system** for the Samsung Galaxy A35 5G.

AURA is an Android application, not a website. It uses the front camera and on-device MediaPipe hand tracking to control Android through a user-enabled Accessibility service.

## Operating profile

| Setting | Value |
|---|---|
| Activation | Hybrid — user starts/stops an explicit session |
| Camera | Front camera |
| Control | One hand only |
| Inference | MediaPipe GPU delegate when supported; automatic CPU fallback |
| Camera pipeline | CameraX native YUV_420_888 frames, latest-frame backpressure, 480 × 360 analysis |
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
- RGBA camera frames are wrapped directly as MediaPipe images; the app no longer creates, rotates and mirrors a new Bitmap for every frame.
- Camera analysis is reduced to 480 × 360; the preview uses hardware-backed rendering.
- Cursor display metrics are cached and repeated sub-pixel overlay updates are skipped.

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

This is an active prototype. CI verifies source compilation, unit tests and APK packaging; it does not replace physical-device validation. Actual frame rate, inference latency, touch alignment, sustained battery use and thermal behavior still need to be measured on the Galaxy A35.
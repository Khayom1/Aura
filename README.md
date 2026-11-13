# AURA

**A Stark-inspired native Android hands-free control system** for the Samsung Galaxy A35 5G.

AURA is an Android application, not a website. It uses the front camera and on-device MediaPipe hand tracking to control Android through a user-enabled Accessibility service.

## Confirmed profile

| Setting | Value |
|---|---|
| Activation | Hybrid — user starts/stops an explicit session |
| Camera | Front camera |
| Safety | Strict — uncertain input fails closed |
| Processing | On-device hand inference |
| Cross-app control | Camera foreground service with persistent notification |
| Device actions | Cursor, select, long press, swipe, two-hand pinch zoom, system navigation |

## Gesture guide

Read [docs/INTERACTION_CONTRACT.md](docs/INTERACTION_CONTRACT.md) for the full interaction grammar and safety rules.

- One hand: point, pinch-select, long press, drag and pause.
- Two hands: pinch both hands and move them apart/together to zoom.
- System navigation: hold a fist to arm command mode, then use a V-sign swipe for Back, Home, Recents, Notifications or Quick Settings.

## Build and CI

The GitHub Actions workflow runs unit tests and builds a debug APK. It downloads Google's pinned Hand Landmarker task bundle during the build and packages it as a local app asset.

- JDK 17
- Gradle 8.11.1
- Android SDK platform 35
- MediaPipe Tasks Vision 0.10.21

## Install and first use

For the phone-only Termux installation steps, see [docs/TERMUX_INSTALL.md](docs/TERMUX_INSTALL.md).

1. Download the aura-debug-apk artifact from a successful GitHub Actions run.
2. Install the APK on the phone.
3. Open AURA and grant Camera permission.
4. Tap Start Session.
5. Open Android Accessibility settings from AURA and explicitly enable AURA Device Control.
6. Return to AURA. Its foreground notification indicates that the camera session is active. Use Stop AURA in the notification or the app button to end it.

Android may require the user to confirm APK installation and Accessibility enablement manually. AURA cannot bypass these system protections.

Third-party model and SDK attribution is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Privacy and resource use

- Hand inference runs locally; camera frames and landmark images are not uploaded.
- MediaPipe may collect limited SDK usage/performance metrics; see the [MediaPipe privacy documentation](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/web/vision/README.md#privacy-notice).
- The camera remains active across apps only while the foreground session is running.
- Android displays its camera privacy indicator and AURA's ongoing notification.
- Continuous camera inference uses battery and can warm the phone. Stop the session when it is not needed.

## Project status

This is an active prototype. CI verifies source compilation, unit tests and APK packaging; it does not replace device-level validation. Coordinate calibration, multi-touch behavior across different apps, accessibility navigation, sustained frame rate, battery use and thermal behavior must be tested on the Galaxy A35 before a stable release.
# AURA

**Native Android hands-free control prototype** for the Samsung Galaxy A35 5G.

AURA is designed around a Stark-inspired interaction model: one hand controls a pointer; two hands perform pinch-to-zoom. It is an Android application, not a website.

## Confirmed profile

| Setting | Value |
|---|---|
| Activation | Hybrid — camera session starts only after tapping Start Session |
| Camera | Front camera |
| Safety | Strict — uncertain input must fail closed |
| Processing target | On-device |
| Android control | Explicitly user-enabled Accessibility service |

## Interaction contract

See docs/INTERACTION_CONTRACT.md for the complete gesture grammar and safety invariants.

## Current milestone

- [x] Native Android Gradle project
- [x] Front-camera preview with explicit start/stop
- [x] Accessibility gesture bridge (requires user opt-in in Android Settings)
- [x] Initial fail-closed gesture engine
- [x] Unit-test scaffold, including two-hand zoom direction and per-frame bounds
- [x] GitHub Actions build/test workflow
- [ ] MediaPipe hand-landmark inference connected to CameraX frames
- [ ] Calibrated pointer mapping and temporal stability filters
- [ ] Pinch click/drag state machine and dual-hand zoom integration
- [ ] Device-level validation on Galaxy A35 5G
- [ ] Release APK and installation guide

**Important:** The current app is an engineering foundation, not yet a functioning hands-free controller. The camera preview works, but hand detection is not connected and no gesture is currently injected into other apps. Do not treat this prototype as complete until the remaining checklist is validated on the phone.

## Build

The repository's GitHub Actions workflow runs unit tests and builds a debug APK. For local Android builds, use JDK 17 and Gradle 8.11.1 with Android SDK platform 35.

## Permissions and privacy

- Camera access is requested at runtime.
- AURA does not start its camera session automatically.
- Accessibility access is never enabled programmatically; the user must enable it in Android Settings.
- The planned hand inference pipeline is local. Camera frames must not be uploaded or logged.
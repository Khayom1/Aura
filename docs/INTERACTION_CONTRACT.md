# AURA interaction contract

## Confirmed operating profile
- Activation: Hybrid. The user explicitly starts/stops the session.
- Camera: front camera only.
- Safety: Strict. MediaPipe detection, presence and tracking thresholds are each 0.65; malformed, unstable or untracked input produces no action. Handedness classification is not misused as a detection-confidence score.
- Processing: hand inference is local on the device. The merged APK removes Android's INTERNET permission, so the installed app cannot upload camera frames or SDK metrics. The model is fetched only during the build.
- Cross-app operation: camera inference runs in a visible foreground service with an ongoing notification and a user-visible Stop action.

## One-hand control
1. **Index-only point** — move the floating AURA cursor. Cursor movement is exponentially smoothed.
2. **Thumb-index pinch, then release** — select once. A pinch is edge-triggered; holding does not generate repeated taps.
3. **Pinch, hold, then release** — long press.
4. **Pinch and move, then release** — swipe/drag path.
5. **Open palm** — pause and hide the cursor.
6. **Tracking loss** — cancel the in-progress interaction and hide the cursor.

## Two-hand control
- Both hands form thumb-index pinches.
- The distance between the two index fingertips is tracked.
- Move the pinched hands apart: **Zoom In**.
- Move the pinched hands together: **Zoom Out**.
- The gesture must be detected across multiple consecutive frames before it is emitted.
- When one hand disappears during a dual-hand pinch, the remaining pinch is suppressed until it is released. It cannot fall through into an accidental single-hand click.
- Both open palms pause AURA.

## System command mode
1. Hold a closed fist for 850 ms.
2. AURA enters command mode for up to 4.5 seconds.
3. Show a two-finger V sign and swipe:
   - Swipe left: Android Back.
   - Swipe right: Android Recents.
   - Swipe up: Android Home.
   - Swipe down on the left half of the camera view: Notifications.
   - Swipe down on the right half: Quick Settings.
4. Open palm exits command mode. If no command is made before timeout, it expires without action.

System actions use Android AccessibilityService global actions and work only while the user has explicitly enabled the AURA accessibility service.

## Strict safety invariants
- MediaPipe must return a complete 21-landmark hand after its configured detector/presence/tracking thresholds. Handedness score is used only for left/right labeling, never as detection confidence.
- Pinch uses enter/exit hysteresis to reduce flicker.
- Dual pinch requires three consecutive detected frames.
- Zoom step per frame is capped.
- Unknown or ambiguous poses are no-ops.
- A lost hand cancels pending clicks and drags.
- The system command mode is time-limited and requires a fist hold plus a separate V-sign swipe.
- AURA never enables Accessibility or Camera permissions programmatically.
- Protected permission dialogs remain under direct user control.

## Current implementation
- Native Kotlin Android app and CameraX front-camera pipeline.
- MediaPipe Hand Landmarker configured for two hands in VIDEO mode, running on-device.
- Hand skeleton HUD overlay.
- Floating cursor through the accessibility overlay window type.
- Android touch injection for taps, long presses, release-triggered swipe paths and short two-pointer pinch segments.
- Global Back, Home, Recents, Notifications and Quick Settings actions.
- Lifecycle-aware camera foreground service so AURA can continue while the user switches apps.
- GitHub Actions unit tests and debug APK build.

The implementation still requires physical-device validation on the Galaxy A35 5G. Gesture coordinates now use real display bounds rather than app-window metrics, but actual touch alignment, front-camera mirroring, multi-touch behavior across target apps, gesture thresholds, sustained frame rate, battery use and thermal behavior must still be measured on the device. AURA is a hands-free gesture controller, not a complete semantic Android assistant: it does not read other apps' UI, type arbitrary text or understand natural-language commands.
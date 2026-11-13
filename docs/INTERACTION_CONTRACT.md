# AURA interaction contract

## Operating profile
- Activation: Hybrid. The user explicitly starts and stops the session.
- Camera: front camera only.
- Control: exactly one tracked hand. MediaPipe is configured with `numHands = 1`; two-hand zoom is disabled.
- Inference: GPU delegate is attempted first; CPU is used when GPU initialization or inference fails.
- Camera pipeline: CameraX RGBA_8888 output at 480 × 360, latest-frame backpressure, direct MediaImage wrapping, metadata-based rotation and coordinate-only front-camera mirroring.
- Safety: detector, presence and tracking thresholds remain 0.60. Malformed or uncertain poses produce no action.
- Privacy: local processing only; the merged APK removes Android's INTERNET permission.
- Cross-app operation: foreground camera service with an ongoing notification and explicit Stop action.

## One-hand control
1. **Index-only point** — move the floating AURA cursor. A responsive exponential filter reduces landmark jitter without heavy pointer lag.
2. **Thumb-index pinch, then release** — select once. A pinch is edge-triggered; holding it does not generate repeated taps.
3. **Pinch and hold, then release** — long press.
4. **Pinch and move, then release** — swipe/drag path.
5. **Open palm** — pause and hide the cursor.
6. **Tracking loss** — cancel an in-progress pinch and suppress it until release, preventing an accidental click when tracking returns.

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

## Performance safeguards
- The model processes at most one hand.
- CameraX uses `RGBA_8888` and `STRATEGY_KEEP_ONLY_LATEST`; a single ARGB bitmap is reused across frames, avoiding per-frame bitmap allocation and transform copies.
- Per-frame Bitmap allocation and Matrix-based rotation/mirroring are removed.
- The GPU delegate is created and used on the same single-thread analysis executor; incompatible devices fall back to CPU.
- The cursor caches physical display metrics and skips updates that do not move at least one pixel.
- After warm-up, the HUD reports measured analyzer FPS, average inference latency and the active delegate in the status line.
- The HUD stores and draws one hand only and reuses its Paint objects.

## Strict safety invariants
- Only a complete 21-landmark hand is eligible for gesture recognition.
- Pinch uses enter/exit hysteresis to reduce flicker.
- Unknown or ambiguous poses are no-ops.
- A lost hand cancels a pending click or drag.
- System command mode is time-limited and requires a fist hold plus a separate V-sign swipe.
- AURA never grants Camera or Accessibility permissions programmatically.
- Protected permission dialogs remain under direct user control.

## Current implementation
- Native Kotlin Android app and CameraX front-camera pipeline.
- MediaPipe Hand Landmarker in VIDEO mode with one-hand detection.
- GPU acceleration with safe CPU fallback.
- CameraX RGBA_8888 frame copied into a reusable ARGB bitmap and wrapped in MediaPipe's MPImage; sensor rotation is passed through ImageProcessingOptions.
- Single-hand skeleton HUD overlay and floating cursor.
- Android single-pointer touch injection and global Back, Home, Recents, Notifications and Quick Settings actions.
- Lifecycle-aware foreground camera service.
- GitHub Actions unit tests and debug APK build.

Physical-device validation on the Galaxy A35 5G is still required. CI cannot measure real camera FPS, end-to-end inference latency, actual touch alignment, GPU-driver behavior, battery use or thermal throttling.
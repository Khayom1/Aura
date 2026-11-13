# AURA interaction contract

## Confirmed operating profile
- Activation: Hybrid. The user starts/stops the session explicitly; gestures are accepted only while the session is armed.
- Camera: front camera only.
- Safety: Strict. Uncertain, low-confidence, unstable, or untracked input produces no action.
- Processing: on-device. Camera frames are not uploaded.

## Gesture grammar

### One-hand mode
- Index finger extended: move the virtual pointer.
- Thumb-index pinch: select on a deliberate pinch transition; keep pinched while moving to drag.
- Open palm: pause pointer movement.
- Loss of tracking: cancel the active interaction and return to neutral.

### Two-hand mode
- Both hands form thumb-index pinches: the distance between the two index fingertips controls zoom.
- Move the pinched hands apart: zoom in.
- Move them together: zoom out.
- Both open palms: pause.
- Any ambiguous pose, hand swap, confidence drop, or tracking loss: no-op and reset the zoom baseline.

### Safety invariants
1. No command is emitted below the configured confidence threshold.
2. A missing hand cancels the current gesture; it never completes a click or drag.
3. Zoom is bounded per frame to prevent jumps.
4. The gesture engine does not execute Android actions itself. A separate action state machine must validate dwell time, debounce, cooldown, and session state.
5. Android touch injection is available only after the user explicitly enables AURA's Accessibility service in system settings.
6. Protected system dialogs and permission prompts remain under direct user control.

## Current implementation boundary

The first implementation milestone adds the native Android project, front-camera preview, AccessibilityService bridge, deterministic gesture-core baseline, unit-test scaffold, and CI build workflow.

**Hand-landmark inference is not wired to the camera yet.** The MediaPipe runtime dependency is present, but the pinned model asset, CameraX image analyzer, calibration, gesture state machine, and end-to-end device validation are still required before any hands-free command can be considered functional. The preview status intentionally says that the gesture engine is not armed.

# Third-party notices

## MediaPipe Tasks Vision

Google MediaPipe Tasks Vision is included as the Android dependency `com.google.mediapipe:tasks-vision:1.2.2`.
Copyright Google LLC. Distributed under the Apache License, Version 2.0.
License: https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE

## Hand Landmarker model

The build downloads Google's Hand Landmarker model bundle from the official Google Cloud Storage model path:

https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task

The model is packaged into the APK for local inference. It is not downloaded by the installed app at runtime. The model is distributed under the MediaPipe project's Apache License 2.0 terms.

## AndroidX CameraX

The Android camera pipeline uses AndroidX CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, and `camera-view`). Copyright The Android Open Source Project. Distributed under the Apache License, Version 2.0.
License: https://developer.android.com/jetpack/androidx/releases/camera

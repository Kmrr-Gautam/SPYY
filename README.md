# SSPY

<p align="center">
  <img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=700&size=26&pause=1000&color=36BCF7&center=true&vCenter=true&width=720&lines=SSPY+%E2%80%94+Android+Recording+Demo;For+Educational+Purpose+Only" alt="Animated text: SSPY Android Recording Demo — For Educational Purpose Only" />
</p>

> **For Educational Purpose Only.** Use this project only with the informed consent of everyone being recorded and in compliance with applicable laws and policies.

SSPY is a small, native Android application that records rear-camera video with microphone audio after the user opens the app and grants the required permissions. It intentionally has no camera preview. An ongoing foreground-service notification and a minimal in-app control provide a way to stop the session. Recordings are published to the public `Movies/SSPY/` media collection only after CameraX finalizes them.

## Problem statement and objectives

The project demonstrates a privacy-respecting, user-initiated Android recording workflow that can continue when the activity is no longer visible. It aims to:

- Record H.264/AAC MP4 using CameraX when the device supports the selected configuration.
- Keep camera and microphone access tied to an explicit launcher action and required permissions.
- Use a foreground service and an accurate, persistent notification with a stop action.
- Save completed recordings through MediaStore without broad filesystem access.
- Avoid claiming success for incomplete recordings and avoid automatic upload or reboot restart.

## Requirements

### Functional

- Request camera, microphone, and (Android 13+) notification permission before starting.
- Start the rear-camera recording after permission approval; do not bind a visible Preview use case.
- Stop through a later launcher intent, the in-app button, or the notification action.
- Wait for CameraX finalization and publish valid output to `Movies/SSPY/`.
- Delete failed or empty MediaStore output where the platform allows it.

### Non-functional

- Native Android UI and Java source only.
- Single active recording session, serialized in a service-owned state machine.
- User-visible foreground-service status; no hidden APIs, root access, upload, or boot receiver.
- No recording data is logged.

## Technology stack

- Java 11 source compatibility, Android Gradle Plugin 8.7.3
- Android compile/target SDK 35; minimum SDK 29 (Android 10)
- CameraX 1.4.2 (Camera2, Lifecycle, Video)
- AndroidX Activity, Core, and Lifecycle Service
- JUnit 4 state-machine unit tests

## Architecture

- `activities/MainActivity.java`: requests runtime permissions, interprets launcher launches as a toggle, and exposes a minimal stop control.
- `services/RecordingService.java`: foreground service and authoritative state owner; binds CameraX to its service lifecycle without a Preview use case.
- `storage/VideoStorageManager.java`: prepares a pending MediaStore item under `Movies/SSPY/`, validates its size, publishes it, or removes it.
- `notifications/RecordingNotificationManager.java`: creates and updates the ongoing status notification and explicit stop action.
- `permissions/PermissionManager.java`: centralizes the permissions needed by this implementation.
- `models/RecordingState.java`: legal transitions and active-state classification.

## Project structure

```text
SSPY/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/sspy/
│   │   │   ├── activities/
│   │   │   ├── models/
│   │   │   ├── notifications/
│   │   │   ├── permissions/
│   │   │   ├── services/
│   │   │   └── storage/
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── src/test/java/com/example/sspy/models/
├── build.gradle
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── settings.gradle
└── README.md
```

## Recording and permission workflows

1. Tapping the SSPY launcher icon opens the launcher activity, which checks camera, microphone, and Android 13+ notification permission.
2. SSPY requests only missing permissions. It does not start if any required permission is denied.
3. From the visible activity, SSPY starts `RecordingService` as a camera-and-microphone foreground service.
4. The service binds CameraX `VideoCapture<Recorder>` to its own lifecycle and selects the rear camera. No `Preview` use case is bound.
5. After CameraX reports `Start`, SSPY shows a brief “Recording started” toast and returns to the launcher. Its low-priority, ongoing foreground-service notification remains visible, states that the rear camera and microphone are recording, and provides a **Stop Recording** action.
6. A stop request calls `Recording.stop()`. The service waits for `Finalize`, checks the output, clears MediaStore's pending flag, and only then reports success.
7. CameraX and device capabilities vary. If a device cannot provide the requested rear-camera recording configuration, SSPY reports an error instead of opening another camera UI or showing a fake preview.

The launcher activity must briefly run to start the foreground service from a visible user action; it finishes after recording actually starts. Android may not deliver a second launcher tap to an activity already in the foreground on every launcher/device. `singleTop` and `onNewIntent` support this where available; the notification's explicit **Stop Recording** action is the fallback. Android 13+ notification permission is required by this app to ensure that its user-visible notification and stop action are available in the notification shade. The recording notification is not suppressed or replaced with a transient-only notice. Camera/microphone indicators remain controlled by Android.

## Storage

CameraX writes directly to a MediaStore video item with MIME type `video/mp4`, relative path `Movies/SSPY/`, a collision-checked millisecond-timestamp filename, and `IS_PENDING=1`. SSPY clears the pending flag only after successful finalization and a non-empty output check. Android 10+ scoped storage is used; no broad storage permission or private duplicate is needed.

## Android Studio setup and build

1. Install Android Studio with Android SDK Platform 35 and a compatible Java 17+ runtime for Gradle (the app source compiles with Java 11 language compatibility).
2. Open this directory as an existing Gradle project and allow Gradle sync to download the declared AndroidX/CameraX dependencies.
3. Connect an Android 10+ device or start an emulator with a rear camera and microphone.
4. Run the `app` configuration. Accept the requested permissions and use the foreground notification or in-app control to stop.

From Windows PowerShell, the wrapper can run the local checks and produce the APK:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Testing plan and actual results

Automated unit tests cover valid and invalid recording-state transitions and active-state classification in `RecordingStateTest`. The four unit tests passed. `assembleDebug` and `lintDebug` also completed successfully using Gradle 8.9 and Android SDK Platform 35; lint reported zero errors and eight warnings, primarily newer-target/dependency recommendations plus the adaptive-icon resource qualifier.

The following need execution on a suitable emulator/device and are **not claimed as passed**:

- Fresh install, permission grant/denial/revocation, and notification permission behavior.
- Rear-camera recording, audio capture, no-preview behavior, screen lock/background operation.
- Notification stop, repeated launcher taps, rapid start/stop, and service/process termination.
- MP4 finalization, gallery visibility and playback, duplicate filenames, storage exhaustion, and camera contention.
- Compatibility across Android 10 through the current supported release and across device camera implementations.

No emulator or physical-device recording scenarios were run in this environment. For an academic demonstration, record device model, Android release, test steps, expected result, observed result, and evidence for each manual scenario.

## Known limitations

- Camera hardware, encoder profiles, audio devices, privacy policy, thermal conditions, and vendor lifecycle behavior vary. This project has not been verified on a device in this environment.
- Target SDK 35 foreground-service and camera/microphone restrictions apply. The service is started only from an eligible, visible activity after permission checks. Android may still stop or restrict camera/microphone use.
- If the process is killed abruptly before CameraX can finalize, an item may remain pending until Android cleans it up; SSPY never presents it as a completed video. Recording is not restarted after process death or reboot.
- The app requires notification permission on Android 13+ as a product safety requirement, even though Android has separate system behavior for foreground-service notices when notification permission is denied.
- No in-app gallery or playback screen is included. Completed files are available to standard media-library/gallery apps.

## Privacy and security

Recording begins only after an explicit app launch and permission approval. Android's camera/microphone indicators and the foreground notification are not hidden or bypassed. SSPY does not record on boot, restart after a crash, upload media, collect analytics, or access unrelated files. Users can stop the service with the in-app button or notification action.

## Academic project report outline

1. Abstract
2. Introduction
3. Problem Statement
4. Objectives
5. Existing System and Proposed System
6. System Requirements
7. System Architecture
8. Module Description
9. Implementation Details
10. Testing and Results
11. Limitations
12. Future Enhancements
13. Conclusion
14. References

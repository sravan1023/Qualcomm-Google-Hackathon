# SightSense

## Application Description

SightSense is an on-device Android accessibility and safety assistant built with Kotlin and Jetpack Compose. It uses the phone camera and microphone to monitor the user's surroundings in real time, then produces spoken and haptic alerts when it detects nearby hazards or warning sounds.

The current app bundles local vision and audio models and runs without a backend service. Vision detection is powered by a packaged YOLO LiteRT model, audio awareness currently uses an on-device loudness-based warning-sound heuristic, and the alert layer fuses signals into a simple safety status with voice and vibration feedback. An optional Qualcomm QNN delegate can be added for NPU acceleration on supported devices, but the app will fall back to GPU or CPU execution if that delegate is not present.

## Team

All Eligible Individuals on the Team:

- Sravan - saisravan1023@gmail.com
- Rishith - saginalarishith@gmail.com
- Sahithi - sahithireddy0299@gmail.com
- Lavanya - lavanyabandla7@gmail.com

## Setup Instructions From Scratch

### Prerequisites

- Android Studio with Android SDK support
- JDK 17
- Android SDK Platform 35
- A physical Android device or emulator running Android 13 or newer

Recommended:

- A physical device, because the app depends on camera and microphone access
- A Snapdragon device with Qualcomm AI support if you want to test the optional QNN delegate path

### Project Dependencies

The project uses these main dependencies:

- Android Gradle Plugin 8.13.2
- Kotlin 2.3.21
- Jetpack Compose
- CameraX
- Google AI Edge LiteRT
- Google Play Services Location

The repo already includes the packaged model assets under the `models/` directory, so no separate download is required for the current vision and audio pipeline.

### Clean Setup

1. Clone the repository.
2. Open the project root in Android Studio.
3. Let Android Studio sync the Gradle project and install any missing SDK components.
4. Make sure your Android SDK includes API level 35.
5. Ensure `local.properties` points to your Android SDK location. Android Studio usually creates this automatically.
6. Connect an Android device or start an emulator with Android 13+.

Optional acceleration setup:

1. Obtain the Qualcomm QNN delegate AAR from the Qualcomm AI Hub sample or QAIRT SDK.
2. Place the AAR file in `app/libs/`.
3. Re-sync the project.

Notes:

- The project currently does not include Gradle wrapper scripts in the repo root, so the most reliable setup path is opening the project in Android Studio.
- Optional LiteRT-LM assets are not required for the app to run. The app handles missing LLM assets gracefully and uses local rule-based fusion behavior.

## Run Instructions

### Android Studio

1. Open the project in Android Studio.
2. Select the `app` run configuration.
3. Choose a connected device or emulator.
4. Run the app.

### Command Line

If you already have a compatible local Gradle installation configured, you can run:

```bash
gradle :app:assembleDebug
gradle :app:installDebug
```

Then launch the app on the device from the launcher.

## Usage Instructions

1. Launch SightSense on an Android device.
2. Grant camera and microphone permissions when prompted.
3. Point the phone camera toward the environment you want monitored.
4. Listen for spoken alerts and feel for vibration feedback when nearby hazards are detected.
5. Use the on-screen controls to switch between balanced mode and higher-sensitivity Danger Mode.
6. Use the voice control to mute or enable spoken alerts as needed.

What the app currently does:

- Shows a live camera preview
- Monitors the scene for nearby objects and hazards
- Monitors microphone input for loud warning-like sounds
- Classifies the current situation into low, medium, or high priority alerts
- Announces alerts with text-to-speech and haptic feedback

## Current Model Assets

Included in this repository:

- Vision detector: `models/vision/yolo26_det-tflite-float/yolo26_det.tflite`
- Audio model asset bundle: `models/audio/yamnet-tflite-w8a8/`

Optional, not required for launch:

- LiteRT-LM compiled models under `models/llm/...`

## Permissions Used

The app requests these permissions at runtime or in the manifest:

- Camera
- Microphone
- Fine and coarse location
- Vibration
- Foreground service related permissions

Location is declared for future or extended navigation use, while camera and microphone are required for the current core experience.
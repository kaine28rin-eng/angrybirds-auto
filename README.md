# Angry Birds Auto — On-Phone Game Bot

An Android-native APK that automates **Angry Birds** entirely on your phone — no PC, no Termux, no ADB required. The app floats over the game as a button, finds pigs via computer vision, calculates the physics-based optimal shot, and fires the bird automatically.

## How It Works

1. **Screen Capture** — Uses Android's `MediaProjection` API to capture the screen natively (the on-device equivalent of ADB `screencap`)
2. **Pig Detection** — Uses OpenCV template matching + color heuristics to find pigs and the slingshot (ported from the Python prototype)
3. **Trajectory Calculation** — Brute-force physics simulation over angles (10°–80°) and power levels, finding the shot that passes closest to the target pig (exact same model as the Python script)
4. **Input Injection** — Uses `AccessibilityService.dispatchGesture()` to send swipe gestures — the drag-and-release motion to pull the slingshot and fire (the on-device equivalent of ADB `input swipe`)
5. **Floating Overlay** — A draggable play/stop button hovers over the game using `TYPE_APPLICATION_OVERLAY`

## Architecture (mirrors the GitHub reference projects)

```
[Android Phone]
  ├── MediaProjectionService.kt  — screen capture (on-device MediaProjection API)
  ├── AutoAccessibilityService.kt — gesture dispatch (AccessibilityService.dispatchGesture)
  ├── BotService.kt               — orchestration thread + floating overlay button
  ├── TrajectoryCalculator.kt     — physics engine (ported from Python prototype)
  ├── OpenCVUtils.kt              — template matching + color detection
  └── TrajectoryOverlay.kt         — draws trajectory arc overlay on screen
```

This is the exact pattern used by real Android CV automation projects on GitHub:
- **Smart-AutoClicker** (Nain57) — floating overlay, template matching, AccessibilityService
- **android-cv-automation-library** (steve1316) — MediaProjection + OpenCV + AccessibilityService architecture
- **Auto.js** — accessibility-based automation on Android

## Requirements

- Android phone with Angry Birds installed
- Android 7.0+ (API 24+)
- Permissions granted:
  - **Display over other apps** (SYSTEM_ALERT_PERMISSION) — for the floating button
  - **Accessibility Service** — for sending touch gestures
  - **Screen capture** (MediaProjection) — for reading the screen

## Setup

1. **Build the APK** (see Build section below)
2. **Install** on your phone
3. **Grant permissions**:
   - Tap "Grant Permissions" in the app
   - Enable "Display over other apps" in Settings
   - Enable the Accessibility Service in Settings → Accessibility
   - Tap "Start Bot Service" and grant screen capture permission
4. **Open Angry Birds**
5. **Tap the floating play button** — the bot will:
   - Detect the slingshot and pigs
   - Calculate the best angle/power
   - Draw the trajectory on screen
   - Fire the bird automatically
6. **Tap the stop button** to pause

## Build

### Option A: Build on this machine (Linux server with Android SDK)

```bash
# Install Android SDK
# (Requires JDK 17+)
mkdir -p $HOME/Android/Sdk
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip -d $HOME/Android/Sdk/cmdline-tools
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/bin:$ANDROID_HOME/platform-tools

# Install required SDK components
yes | sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

# Build the APK
./gradlew assembleRelease

# The APK will be at: app/build/outputs/apk/release/app-release.apk
# Sign it (generate a keystore first):
keytool -genkeypair -v -keystore my-release-key.keystore -alias angrybirds -keyalg RSA -keysize 2048 -validity 10000
# Then use apksigner to sign the APK
```

### Option B: Build with GitHub Actions

```yaml
# The project includes .github/workflows/build.yml
# Just push to GitHub and download the APK from Actions
```

### Option C: Import into Android Studio

1. Open Android Studio → File → Open → select this project folder
2. Let it sync Gradle
3. Run → Build APK(s)

## Custom Templates

Place template images in `app/src/main/assets/templates/`:
- `pig.png` — template of a pig for template matching
- `slingshot.png` — template of the slingshot

The app falls back to **color-based detection** if templates aren't found:
- Pigs: green HSV range (Hue 35-85)
- Slingshot: brown HSV range (Hue 10-20)

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Overlay button doesn't appear | Grant "Display over other apps" permission |
| Bot doesn't tap the screen | Enable the Accessibility Service in Settings |
| Can't find pigs | Place better pig template images in assets/templates/, or increase screen brightness |
| Trajectory is off | Calibrate the slingshot position in settings |
| Bot fires too early/late | Adjust the shot delay in settings |

## Files

```
AngryBirdsAuto/
├── app/
│   ├── src/main/
│   │   ├── java/com/stevestudy/angrybirdsauto/
│   │   │   ├── automation/
│   │   │   │   ├── MediaProjectionService.kt   # Screen capture service
│   │   │   │   ├── AutoAccessibilityService.kt # Touch gesture injection
│   │   │   │   ├── BotService.kt               # Main automation loop + floating button
│   │   │   │   └── TrajectoryCalculator.kt     # Physics-based trajectory engine
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt             # Permission flow + start/stop
│   │   │   │   ├── OverlayManager.kt         # Floating button management
│   │   │   │   └── TrajectoryOverlay.kt        # Trajectory arc overlay
│   │   │   ├── data/
│   │   │   │   └── SharedData.kt               # Global state constants
│   │   │   └── utils/
│   │   │       ├── ImageUtils.kt               # YUV→Bitmap conversion
│   │   │       └── OpenCVUtils.kt              # Template matching + color detection
│   │   ├── res/  (layouts, drawables, values)
│   │   └── assets/templates/  (pig.png, slingshot.png)
│   ├── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts  (project level)
├── settings.gradle.kts
└── gradle.properties
```

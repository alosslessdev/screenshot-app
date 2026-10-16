# Screenshot App

Android app that shows a floating capture button over the screen. Pressing it briefly
vibrates the phone and saves what the user can see as a JPEG. Screen pixels are read with
the Accessibility API (`AccessibilityService.takeScreenshot`, Android 11+), so no
MediaProjection permission prompt is needed.

## Features

- Floating capture button (accessibility overlay — no `SYSTEM_ALERT_WINDOW` permission)
- Brief 50 ms vibration on every press
- JPEG save with adjustable quality (10–100, default 90)
- Button size adjustable 100–200 px (default 100 px)
- Output folder adjustable via the system folder picker; default `Pictures/ScreenshotApp`
- Material Design 3 UI, dark scheme by default (independent of system night mode)
- Capture latency: press → file saved is logged as `saved … in N ms` — measured 250–320 ms
  end-to-end on an emulator (goal ≈ 500 ms)

## Requirements

- Android 11 (API 30) or newer
- Device with a vibrator for haptic feedback (capture works without it)

## Build

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
gradle assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease        # unsigned release APK
```

Uses Android Gradle Plugin 9.4 with built-in Kotlin (no `kotlin-android` plugin) and
Gradle 9.6. SDK components: `platforms;android-37.0`, `build-tools;37.0.0` (compileSdk 37,
targetSdk 36, minSdk 30).

## Install and enable

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open **Screenshot App** → **Open accessibility settings**
2. Enable **Screenshot App** in the *Downloaded apps* / accessibility list
3. The floating button appears at the right edge of the screen

## Settings (main screen)

| Setting | Range | Default |
|---|---|---|
| JPEG quality | 10–100 | 90 |
| Floating button size | 50–200 px, 10 px steps | 50 px |
| Save location | any folder via SAF picker | `Pictures/ScreenshotApp` (MediaStore) |

All settings persist in `SharedPreferences` and apply immediately, including while the
floating button is on screen.

## How capture works

`CaptureService` is an `AccessibilityService` declared with `android:canTakeScreenshot`
in `res/xml/accessibility_service_config.xml`. Press flow:

1. `Vibrator.vibrate(VibrationEffect.createOneShot(50ms, …))`
2. `takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)` (API 30)
3. On success: `Bitmap.wrapHardwareBuffer` → copy to software → `compress(JPEG, quality)`
4. Write via `DocumentsContract` into the picked folder, otherwise into
   `MediaStore.Images` at `Pictures/ScreenshotApp`

JPEG compression and the file write run on a single background executor so the UI never
blocks; a press during an in-flight capture is ignored (it still vibrates).

## Notes

- The floating button is itself a visible overlay, so it appears in the captured image.
- Secure windows (password fields, DRM video) cannot be captured; the service reports the
  reason as a toast.
- Android blocks some folders from being selected (volume root, the root of Downloads):
  the picker shows “Can't use this folder”. Pick a subfolder — that limitation is the
  platform's, not the app's.
- `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` means the system throttles captures to
  about one per second.

# ShakeTorch (Android)

Native Kotlin + Jetpack Compose app. The 1.5-second UFO entrance, vector beam and power ring, 36 drifting stars, state inversion, and shake pulse are all code-drawn. No animations or image assets are downloaded. The home control toggles the rear camera flash independently of gesture mode. Gesture mode runs an Android foreground service, with an ongoing notification and a quick off action; it listens for a deliberate double-chop even when the Activity is closed or the phone is locked, subject to device and OS power limits.

## Build on Windows

Install Android Studio or Android command-line tools, Android SDK Platform 36, Android SDK Build Tools, and JDK 17 or newer. From this directory run `gradlew.bat :app:assembleDebug`; the installable debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`. Run `adb install -r app/build/outputs/apk/debug/app-debug.apk` with USB debugging enabled, or use Android Studio's Run button. On macOS/Linux run `./gradlew :app:assembleDebug` instead. Internet access is needed the first time Gradle and dependencies are downloaded.

Test on an actual phone with an accelerometer and camera flash: tap the central ring, turn on the gesture switch, lock the phone, and make two discrete chops with a brief recoil. Try the sensitivity choices under Settings. Android 13+ notification permission enables visible status and notification actions; the service can run without the notification drawer entry if permission is declined. If the device's power management stops listening, use Settings > Battery to review the app's background behavior. No special lock-screen permission exists.

## Technical boundaries

The sensor is registered with a foreground service and reduced sampling when the screen is off. Some devices do not deliver continuous motion samples during deep sleep, or restrict services despite these settings. Reboot restoration is best effort. The `specialUse` foreground service subtype and battery usage should be reviewed for Play Store distribution. iOS does not provide an equivalent general-purpose always-on, locked-screen accelerometer service. The torch may be unavailable while the camera is in use, on flashless devices, or under thermal/power restrictions.

## Source map

- `app/src/main/java/com/shaketorch/app/ui/MainScreen.kt`: monochrome Compose UI and vector animations.
- `app/src/main/java/com/shaketorch/app/ui/MainViewModel.kt`: UI/service state, manual torch path.
- `app/src/main/java/com/shaketorch/app/service/ShakeTorchService.kt`: foreground notification, sensor registration, gesture actions.
- `app/src/main/java/com/shaketorch/app/service/ShakeDetector.kt`: filtered two-peak gesture recognition and cooldown.
- `app/src/main/java/com/shaketorch/app/service/TorchManager.kt`: rear flash selection and camera torch callback.

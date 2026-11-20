# Mine Study Android

Native Android client for the Mine Study learning library. Built with Kotlin, Jetpack Compose and ExoPlayer.

## Features in this starter
- Native Compose interface (not a WebView)
- Two batch/server selections
- Subject → chapter → learning material navigation
- Lectures, notes, DPP notes and DPP videos tabs
- API-key entry stored locally in app preferences
- API-key generation link opener
- ExoPlayer video playback
- Android Download Manager integration for lecture downloads

## Open in Android Studio
1. Clone this repository.
2. Open the repository root in Android Studio (Giraffe or newer).
3. Use JDK 17 and allow Gradle sync to complete.
4. Select an Android device or emulator and press Run.

## API configuration
The app currently uses the Mine Study API at `https://v1.sgmvv1.xyz/api/v1`.
It sends the user-provided key in the `x-api-key` request header. No real API key is hard-coded.

Batch IDs:
- Server 1: `6a0eb0a7f33c042849418636`
- Server 2: `6a0eaf70f4ae45d1901b7196`

## Important
This is the initial source-code scaffold, not a signed APK. The API response formats and stream authentication need to be tested against live responses on a device. Build and verify in Android Studio before distributing.

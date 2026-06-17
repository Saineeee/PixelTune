# PixelTune

PixelTune is a modern Android music player application built with Kotlin and Jetpack Compose. It offers a rich set of features for local music playback, cloud integration, and Wear OS support.

## Features

*   **Modern UI**: Fully built with Jetpack Compose, featuring smooth animations and a responsive design.
*   **Audio Playback**: Powered by ExoPlayer (Media3) for reliable and high-quality audio playback.
*   **Cloud Integration**: Connect and stream music from Google Drive, Telegram, and Netease.
*   **Lyrics Support**: Automatically fetch and display lyrics (via lrclib.net).
*   **Wear OS Companion**: Includes a dedicated Wear OS app for remote control and playback.
*   **Widgets**: Multiple home screen widgets (Glance) for quick access to playback controls.
*   **Tag Editing**: Edit metadata for your local audio files directly within the app.
*   **Quick Settings Tiles**: Easy access to actions like "Shuffle All" and "Last Playlist" from the quick settings panel.

## Architecture & Tech Stack

PixelTune follows modern Android development practices:

*   **Language**: [Kotlin](https://kotlinlang.org/)
*   **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
*   **Media Playback**: [ExoPlayer (Media3)](https://developer.android.com/media/media3/exoplayer)
*   **Dependency Injection**: [Hilt / Dagger](https://dagger.dev/hilt/)
*   **Local Data**: [Room Database](https://developer.android.com/training/data-storage/room) and [Jetpack DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
*   **Asynchronous Programming**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & Flow
*   **Network**: [Ktor](https://ktor.io/) (for local streaming proxies) and [Retrofit](https://square.github.io/retrofit/)
*   **Multi-module Structure**:
    *   `:app` - Main Android application
    *   `:wear` - Wear OS companion application
    *   `:shared` - Shared DTOs and common models

## Getting Started

To build and run PixelTune locally:

1.  Clone this repository.
2.  Open the project in Android Studio (Ladybug or newer recommended).
3.  Let Gradle sync the project dependencies.
4.  Run the `app` configuration to deploy to an Android device or emulator (API 29+ required).

## License

Please refer to the `LICENSE` file for details regarding the licensing of this project.

Fixes two bugs in the app related to Media extraction using NewPipeExtractor:
1. YouTube Streaming frozen at 00:00 (404 Error in ExoPlayer): By upgrading NewPipeExtractor to v0.26.1 and stripping out the hardcoded `User-Agent` overriding logic in `YouTubeStreamProxy` and `OkHttpClient` interceptors, we ensure NewPipe sends its valid internal User-Agent for streaming links so YouTube validates the stream correctly.
2. SoundCloud returning no results: NewPipeExtractor v0.26.1 has the fix for the SoundCloud `Could not get client id` exception on search endpoints.

Changes:
- Upgraded `NewPipeExtractor:v0.24.4` to `v0.26.1` in `app/build.gradle.kts`.
- Modified `AppModule.kt` to only inject `Mozilla/...` User-Agent if it isn't already present in `OkHttpClient` requests, thereby not overwriting NewPipe's User-Agents.
- Removed custom `User-Agent` overriding in `YouTubeStreamProxy.kt` and `SoundCloudStreamProxy.kt`.

# NexaStream

NexaStream is a modern Android application for streaming movies and TV shows, featuring multi-provider support, a robust video player, and a sleek user interface.

## 🚀 Recent Improvements

- **Download Quality Picker**: Implemented a comprehensive quality selector for downloads. Users can now see and choose between different resolutions (360p to 4K), formats (MP4, HLS, DASH), and file sizes before starting a download.
- **Improved Download Identifiers**: Fixed a critical issue where TV show episode downloads shared the same ID as the series. Episodes now have unique identifiers and formatted titles (e.g., "Show Name - S01E01") in the download list and system notifications.
- **Fixed Video Playback**: Resolved critical issues where missing TV show metadata caused empty IDs to be passed to video servers (e.g., VixSrc), ensuring seamless playback across all providers.
- **Optimized Server Selection**: Implemented a "Preferred Server" logic. The app now remembers your manually selected server and prioritizes it for future episodes.
- **Default Priority for VixSrc**: Set VixSrc as the primary default server across all regions for the most reliable streaming experience.
- **Search Reliability**: Fixed a crash that occurred when accessing the search bar by properly integrating Hilt dependency injection into the Search logic.
- **Update Throttling**: Optimized the background update checker to prevent GitHub rate-limiting (HTTP 403 errors) by restricting checks to once per hour and failing silently.

## ✨ Features

- **Multi-Provider Support**: Access content from various sources including TMDb, SFlix, and more.
- **Advanced Video Player**: Built on Media3/ExoPlayer with support for custom servers, subtitles (OpenSubtitles & SubDL), and gesture controls.
- **Smart Search**: Find your favorite content across multiple providers simultaneously.
- **Continue Watching**: Automatically track your progress and pick up where you left off.
- **Favorites**: Save your preferred providers and shows for quick access.
- **Theme Support**: Customize the app's appearance with multiple color palettes.

### PatrickTV / Cricfy configuration

PatrickTV follows the provider and playlist flow of the [Cricfy Kodi plugin](https://github.com/itsyourap/cricfy-kodi-plugin): it fetches the provider catalog through Firebase Remote Config and decrypts provider playlists. To enable it for a local build, add the following values to the root `local.properties` file. The supplied plugin checkout must include its ignored `cricfy_properties.json` and `secret1.txt` / `secret2.txt` resource values; those files were not present in the checkout used for this port.

```properties
CRICFY_PACKAGE_NAME=
CRICFY_FIREBASE_API_KEY=
CRICFY_FIREBASE_APP_ID=
CRICFY_SECRET1=
CRICFY_SECRET2=
```

`CRICFY_SECRET1` and `CRICFY_SECRET2` use the plugin's `keyHex:ivHex` format; at least one must be set. Keep these values local and do not commit them. The built-in players support ClearKey license URLs; inline `keyId:key` ClearKey licenses and DRM in external players are not supported.

## 🛠 Tech Stack

- **Kotlin**: Primary programming language.
- **Hilt**: Dependency injection.
- **Room**: Local database for history and favorites.
- **Media3/ExoPlayer**: High-performance video playback.
- **Retrofit & Jsoup**: Efficient data fetching and web scraping for providers.
- **Coroutines & Flow**: Reactive and asynchronous programming.

## 📦 Installation

Download the latest APK from the [Releases](https://github.com/THWAYNESHOP/NEXASTREAM2/releases) page.

---
Developed by [THWAYNESHOP](https://github.com/THWAYNESHOP)

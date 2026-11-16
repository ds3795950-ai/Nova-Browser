# Nova Browser

A fresh Android/Kotlin rebuild of Nova Browser. The previous GitHub repository could not be read because GitHub returns an account-suspension 403, and no source backup was available. This repository is therefore a reconstruction, not a recovered copy of the old source tree.

## Current baseline

- Android application module (`app`)
- Kotlin + Android Gradle Plugin
- GeckoView is the only browser engine
- Minimal address/search bar and page view
- Internet permission
- Basic unit-test wiring

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 36 installed. Allow Gradle to sync and resolve Mozilla's Maven repository. Then run the `app` configuration on an Android device or emulator.

A successful build and device run have **not** yet been verified in this environment. The first CI run will provide the initial compile result.

## Product decisions already carried forward

- Android-first Kotlin browser
- GeckoView only; no Chromium/WebView fallback
- Privacy-oriented private and guest browsing goals
- Nova Shield for tracker/ad blocking
- Search engine selection
- ChatGPT, Claude and Perplexity assistant options
- Supabase-backed email OTP and optional cloud sync

These are requirements, not claims that the features are implemented. See [ROADMAP.md](docs/ROADMAP.md).

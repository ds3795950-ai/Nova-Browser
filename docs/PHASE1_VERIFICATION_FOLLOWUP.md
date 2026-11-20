# Phase 1 verification follow-up

The first Phase 1 CI run reached Gradle dependency metadata validation and stopped before Kotlin compilation. It identified the actual blocker: GeckoView 157.0.20261005135250 requires Android API 37.1, while Nova was compiling against API 37.0.

The follow-up commit selects the expanded `compileSdk` DSL with `release(37) { minorApiLevel = 1 }`, installs `platforms;android-37.1` in both Android CI and lint workflows, and removes the app's explicit Kotlin JDK 17 toolchain override. GitHub Actions runs Gradle under Temurin JDK 25 and Gradle 9.7.1, so the Kotlin compiler must not be forced back onto JDK 17 when reading Cefrium's Java-25 class files. Java source/bytecode compatibility remains at 17.

The repository has no checked-in Gradle Wrapper files. CI intentionally installs and invokes the pinned Gradle 9.7.1 distribution directly using `gradle/actions/setup-gradle`.

## Latest verification (2026-11-20)

- Commit `5c83f759b5aeef9f310d04498070fcede1f1af96`: Android CI passed.
- The same commit: Android Lint passed.
- Android Emulator Smoke failed during hosted emulator setup after the emulator reported boot completion. The runner's ADB settings command returned a broken pipe (exit 224) before `connectedDebugAndroidTest` started. This is an infrastructure/setup failure, not a failing instrumentation assertion.
- The smoke workflow now disables the runner's animation-setting step to avoid the affected ADB setup path. A fresh workflow run must pass before emulator verification is considered complete.
- BrowserStack has not yet been run. Its App Automate workflow requires a valid `ROHANSAHA_NGXOSY` repository secret and available account quota.

Phase 1 remains pending review and device verification. Pull request #1 remains a draft and has not been merged into `main`.

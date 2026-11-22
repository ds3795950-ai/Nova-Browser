# Phase 1 verification follow-up

The first Phase 1 CI run reached Gradle dependency metadata validation and stopped before Kotlin compilation. It identified the actual blocker: GeckoView 157.0.20261005135250 requires Android API 37.1, while Nova was compiling against API 37.0.

The follow-up commit selects the expanded `compileSdk` DSL with `release(37) { minorApiLevel = 1 }`, installs `platforms;android-37.1` in both Android CI and lint workflows, and removes the app's explicit Kotlin JDK 17 toolchain override. GitHub Actions runs Gradle under Temurin JDK 25 and Gradle 9.7.1, so the Kotlin compiler must not be forced back onto JDK 17 when reading Cefrium's Java-25 class files. Java source/bytecode compatibility remains at 17.

The repository has no checked-in Gradle Wrapper files. CI intentionally installs and invokes the pinned Gradle 9.7.1 distribution directly using `gradle/actions/setup-gradle`.

## Latest verification (2026-11-22)

- Dual-engine source commit `314c1077730728aed5a741fef390f4bff805d48f`: Android CI passed, including JVM unit tests, debug APK assembly, unsigned release APK assembly and artifact upload. The added Cefrium permission-policy tests passed.
- The same commit: Android Lint passed (`44990860799`).
- Android 16 emulator smoke (`44990860687`) reached the instrumentation-test step but was still running at the time of this update; it is not counted as passed.
- URL/session-safety branch commit `20753b646c1cf0462607136c9cabd81f608b6e79`: Android CI passed (`44991023100`). Its Android 15 Appium UI regression run (`44991023096`) was still running at this update.
- UI-polish branch code commit `319005842`: unit tests, debug build and release build passed (`44990883958`). A later documentation/workflow-only head `7699d2d8` has a fresh Android CI run in progress.
- BrowserStack workflows built their debug AABs and parsed the repository credential successfully. BrowserStack rejected upload with HTTP 403 and `BROWSERSTACK_TESTING_TIME_LIMIT_EXHAUSTED`; the updated workflows emitted a warning and skipped Appium steps. This is **not** a BrowserStack device-test pass.
- The repository has no `NOVA_RELEASE_*` signing secrets. CI's release APK is therefore unsigned and is for development/testing only.
- The Supabase Magic Link template still uses `{{ .ConfirmationURL }}` rather than `{{ .Token }}`, and no custom SMTP host is configured. Numeric OTP email delivery is not production-ready until the project owner updates the template and configures SMTP.
- Guest-only Tor routing remains unimplemented. The Android app-wide VPN model cannot isolate Guest from Normal/Private tabs; no Tor connection is claimed.

Pull request #1 remains a draft and has not been merged. The default `main` branch was not modified.

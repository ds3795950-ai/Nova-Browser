# Phase 1 verification follow-up

The first Phase 1 CI run reached Gradle dependency metadata validation and stopped before Kotlin compilation. It identified the actual blocker: GeckoView 157.0.20261005135250 requires Android API 37.1, while Nova was compiling against API 37.0.

The follow-up commit selects `compileSdk = 37` with `compileSdkExtension = 1`, installs `platforms;android-37-ext1` in both Android CI and lint workflows, and removes the app's explicit Kotlin JDK 17 toolchain override. GitHub Actions already runs Gradle under Temurin JDK 25 and Gradle 9.7.1, so the Kotlin compiler must not be forced back onto JDK 17 when reading Cefrium's Java-25 class files. Java source/bytecode compatibility remains at 17.

The repository has no checked-in Gradle Wrapper files. CI intentionally installs and invokes the pinned Gradle 9.7.1 distribution directly using `gradle/actions/setup-gradle`; therefore there is no stale wrapper distribution URL to update.

The next CI run is the code-language verification: `testDebugUnitTest`, `lintDebug`, `assembleDebug` and `assembleRelease` must complete. If compilation advances past AAR metadata and exposes Kotlin/API errors, those will be fixed from the compiler output rather than guessed.

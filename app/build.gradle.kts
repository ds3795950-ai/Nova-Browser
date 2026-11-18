plugins {
    id("com.android.application")
}

val releaseStorePath = System.getenv("NOVA_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("NOVA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("NOVA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("NOVA_RELEASE_KEY_PASSWORD")
val releaseSigningReady = !releaseStorePath.isNullOrBlank() && file(releaseStorePath ?: "__missing_keystore__").isFile &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.nova.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nova.browser"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName ="0.4.0-performance"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", "\"https://ycgubmykjhikjyfbgycp.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_ZF96tMq70_feRUTRZ2fV2A_XTM04aEP\"")
    }

    // Produce device-specific APK s instead of bundling every GeckoView native ABI in one file.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.20",
        "org.jetbrains.kotlin-stdlib-jdk8:2.3.20"
    )
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    // GeckoView is the sole browser engine.
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

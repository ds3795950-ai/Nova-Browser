plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nova.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nova.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-rebuild"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    // GeckoView is the sole browser engine. Pinning this known published build for reproducibility.
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    testImplementation("junit:junit:4.13.2")
}

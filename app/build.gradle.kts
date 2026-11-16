plugins {
    id("com.android.application")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.nova.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nova.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-auth-sync"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", "\"https://ycgubmykjhikjyfbgycp.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_ZF96tMq70_feRUTBZ2fV2A_XTM04aEP\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.2.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.20"
    )
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    // GeckoView is the sole browser engine.
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

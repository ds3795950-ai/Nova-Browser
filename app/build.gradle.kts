import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
}

dependencyLocking {
    lockAllConfigurations()
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
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_ZF96tMq70_feRUTBZ2fV2A_XTM04aEP\"")
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
        debug {
            val debugUrl = providers.gradleProperty("NOVA_DEBUG_SUPABASE_URL").orElse("https://debug.invalid").get()
                .replace("\\", "\\\\").replace("\"", "\\\"")
            val debugKey = providers.gradleProperty("NOVA_DEBUG_SUPABASE_PUBLISHABLE_KEY").orElse("debug-publishable-placeholder").get()
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "SUPABASE_URL", "\"$debugUrl\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$debugKey\"")
        }
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


dependencies {
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


val generatedAssetsRoot = layout.buildDirectory.dir("generated/assets")
val generated1VpnDir = layout.buildDirectory.dir("generated/assets/nova_1vpn_runtime")
val vpnArchiveUrl = "https://addons.mozilla.org/firefox/downloads/latest/1vpn/latest.xpi"
val vpnArchiveSha256 = "112c58e7b7f283e01d0efaee1fb03cee673839588b2b6399a4dc075794c1f302"

fun replaceExactlyOnce(source: String, target: String, replacement: String): String {
    val start = source.indexOf(target)
    check(start >= 0 && source.indexOf(target, start + target.length) < 0) {
        "Expected exactly one occurrence of upstream 1VPN patch target"
    }
    return source.replaceRange(start, start + target.length, replacement)
}

val prepare1VpnAssets by tasks.registering {
    inputs.property("upstreamUrl", vpnArchiveUrl)
    inputs.property("upstreamSha256", vpnArchiveSha256)
    inputs.property("bridgeRevision", 2)
    outputs.dir(generated1VpnDir)
    doLast {
        val outputDir = generated1VpnDir.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val archive = File(temporaryDir, "1vpn-4.2.2.xpi")
        URL(vpnArchiveUrl).openStream().use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(sha256 == vpnArchiveSha256) {
            "The 1VPN add-on package changed. Review the upstream release and update the pinned SHA-256."
        }
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(outputDir, entry.name)
                check(target.canonicalPath.startsWith(outputDir.canonicalPath + File.separator)) {
                    "Unsafe path in upstream 1VPN archive"
                }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
        val manifestFile = File(outputDir, "manifest.json")
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(manifestFile) as? MutableMap<String, Any?>
            ?: error("Upstream 1VPN manifest must be a JSON object")
        @Suppress("UNCHECKED_CAST")
        val geckoSettings = manifest["browser_specific_settings"] as? MutableMap<String, Any?>
            ?: error("Upstream 1VPN manifest is missing browser_specific_settings")
        @Suppress("UNCHECKED_CAST")
        val gecko = geckoSettings["gecko"] as? MutableMap<String, Any?>
            ?: error("Upstream 1VPN manifest is missing browser_specific_settings.gecko")
        gecko["id"] = "1vpn-internal@nova.browser"
        val permissions = (manifest["permissions"] as? List<*> ?: emptyList<Any>()).map { it.toString() }.toMutableList()
        permissions.add("nativeMessaging")
        permissions.add("geckoViewAddons")
        manifest["permissions"] = permissions.distinct()
        manifestFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))

        val backgroundFile = File(outputDir, "background.bundle.js")
        var background = backgroundFile.readText()
        val installTabCall = "chrome.tabs.create({url:chrome.runtime.getURL(\"install.html\")})"
        check(background.contains(installTabCall)) { "Upstream 1VPN install flow changed; review before patching." }
        background = replaceExactlyOnce(background, installTabCall, "void 0")

        val authStart = "chrome.webRequest.onAuthRequired.addListener("
        val authEnd = ",{urls:[\"<all_urls>\"]},[\"blocking\"])"
        val authStartIndex = background.indexOf(authStart)
        val authEndIndex = if (authStartIndex >= 0) background.indexOf(authEnd, authStartIndex) else -1
        check(authStartIndex >= 0 && authEndIndex > authStartIndex) {
            "Upstream 1VPN proxy-auth handler changed; review before bundling."
        }
        val authEndExclusive = authEndIndex + authEnd.length
        val authStatement = background.substring(authStartIndex, authEndExclusive)
        background = replaceExactlyOnce(
            background,
            authStatement,
            "try{" + authStatement + ";globalThis.__novaAuthReady=true}catch(e){globalThis.__novaAuthReady=false;console.error(e)}"
        )

        val proxyRegistration = "browser.proxy.onRequest.addListener(m,{urls:[\"<all_urls>\"]})"
        check(background.contains(proxyRegistration)) { "Upstream 1VPN proxy registration changed; review before bundling." }
        background = replaceExactlyOnce(
            background,
            proxyRegistration,
            "try{" + proxyRegistration + ";globalThis.__novaProxyReady=true}catch(e){globalThis.__novaProxyReady=false;console.error(e)}"
        )

        val novaBridge = """
            ;(function() {
              var nativePort = null;
              var webRtcProtected = false;
              var reconnectAttempts = 0;
              function scheduleReconnect() {
                if (reconnectAttempts >= 8) {
                  report("1VPN native bridge unavailable after repeated retries", { enabled: false });
                  return;
                }
                var delay = Math.min(30000, 1000 * Math.pow(2, reconnectAttempts++));
                setTimeout(connectNative, delay);
              }
              function supported() {
                return globalThis.__novaProxyReady === true && globalThis.__novaAuthReady === true;
              }
              function report(status, extra) {
                try {
                  if (nativePort) nativePort.postMessage(Object.assign({
                    type: "status",
                    status: status,
                    supported: supported(),
                    webrtcProtected: webRtcProtected
                  }, extra || {}));
                } catch (_) {}
              }
              async function setWebRtcProtection(enabled) {
                try {
                  var setting = browser.privacy && browser.privacy.network && browser.privacy.network.peerConnectionEnabled;
                  if (!setting) return false;
                  if (enabled) {
                    var saved = await browser.storage.local.get("novaSavedPeerConnectionEnabled");
                    if (typeof saved.novaSavedPeerConnectionEnabled !== "boolean") {
                      var current = await setting.get({});
                      await browser.storage.local.set({ novaSavedPeerConnectionEnabled: current.value !== false });
                    }
                    await setting.set({ value: false });
                    return true;
                  }
                  var prior = await browser.storage.local.get("novaSavedPeerConnectionEnabled");
                  if (typeof prior.novaSavedPeerConnectionEnabled === "boolean") {
                    await setting.set({ value: prior.novaSavedPeerConnectionEnabled });
                    await browser.storage.local.remove("novaSavedPeerConnectionEnabled");
                  }
                  return true;
                } catch (_) { return false; }
              }
              async function configure(message) {
                var location = ["sgp", "ams", "lax"].indexOf(message.location) >= 0 ? message.location : "sgp";
                var enabled = message.enabled === true;
                await chrome.storage.local.set({ isConnected: enabled, currentLocation: location });
                webRtcProtected = await setWebRtcProtection(enabled);
                report(enabled ? "1VPN browser proxy enabled" : "VPN off", { enabled: enabled, location: location });
              }
              function connectNative() {
                try {
                  nativePort = browser.runtime.connectNative("com.nova.browser.vpn");
                  nativePort.onMessage.addListener(function(message) {
                    if (message && message.type === "configure") {
                      reconnectAttempts = 0;
                      configure(message).catch(function(error) {
                        report("1VPN configuration error", { error: String(error) });
                      });
                    }
                  });
                  nativePort.onDisconnect.addListener(function() {
                    nativePort = null;
                    scheduleReconnect();
                  });
                  nativePort.postMessage({
                    type: "ready",
                    supported: supported(),
                    status: supported() ? "1VPN proxy backend ready" : "GeckoView proxy API unavailable"
                  });
                } catch (_) {
                  nativePort = null;
                  scheduleReconnect();
                }
              }
              setWebRtcProtection(false).catch(function() {});
              connectNative();
            })();
        """.trimIndent()
        backgroundFile.writeText(background + "\n" + novaBridge)
    }
}
android.sourceSets.getByName("main").assets.srcDir(generatedAssetsRoot.get().asFile)
tasks.named("preBuild").configure { dependsOn(prepare1VpnAssets) }

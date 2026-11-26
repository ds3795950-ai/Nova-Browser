import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("com.cefrium") version "0.21.0"
}

val releaseStorePath = System.getenv("NOVA_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("NOVA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("NOVA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("NOVA_RELEASE_KEY_PASSWORD")
val releaseSigningReady = !releaseStorePath.isNullOrBlank() && file(releaseStorePath ?: "__missing_keystore__").isFile &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.nova.browser"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

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
            include("arm64-v8a", "armeabi-v7a", "x86_64")
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
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.20"
    )
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    // GeckoView remains the Private/Guest engine and the privacy-preserving fallback.
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    // Embedded Tor daemon; Guest Mode uses its loopback SOCKS5 listener only.
    implementation("info.guardianproject:tor-android:0.4.9.17")
    // Cefrium 0.21.0 supplies Chromium for eligible Normal tabs.
    implementation("com.cefrium:cefrium-sdk:0.21.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}


val generatedAssetsRoot = layout.buildDirectory.dir("generated/assets")
val generated1VpnDir = layout.buildDirectory.dir("generated/assets/nova_1vpn")
val prepare1VpnAssets by tasks.registering {
    outputs.dir(generated1VpnDir)
    doLast {
        val outputDir = generated1VpnDir.get().asFile
        delete(outputDir)
        outputDir.mkdirs()
        // Mozilla Add-ons 1VPN 4.2.3 (file 5312319), verified against the upstream XPI.
        val archive = File(temporaryDir, "1vpn-4.2.3.xpi")
        val xpiConnection = URL("https://addons.mozilla.org/firefox/downloads/file/5312319/1vpn-4.2.3.xpi").openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        xpiConnection.getInputStream().use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expectedSha256 = "3c62a3ed119e2be1a965d51fd59aca3afd68684cfa80bc2bef229a15e3ba8ead"
        check(sha256 == expectedSha256) {
            "The pinned 1VPN 4.2.3 package changed. Verify the upstream AMO release and update the version and SHA-256."
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
        val manifest = JsonSlurper().parse(manifestFile) as MutableMap<String, Any?>
        val geckoSettings = manifest["browser_specific_settings"] as MutableMap<String, Any?>
        val gecko = geckoSettings["gecko"] as MutableMap<String, Any?>
        gecko["id"] = "1vpn-internal@nova.browser"
        val permissions = (manifest["permissions"] as List<*>).map { it.toString() }.toMutableList()
        permissions.add("nativeMessaging")
        permissions.add("geckoViewAddons")
        manifest["permissions"] = permissions.distinct()
        manifestFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))

        val backgroundFile = File(outputDir, "background.bundle.js")
        var background = backgroundFile.readText()
        val installTabCall = "chrome.tabs.create({url:chrome.runtime.getURL(\"install.html\")})"
        check(background.contains(installTabCall)) { "Upstream 1VPN install flow changed; review before patching." }
        background = background.replace(installTabCall, "void 0")

        val authStart = "chrome.webRequest.onAuthRequired.addListener("
        val authEnd = ",{urls:[\"<all_urls>\"]},[\"blocking\"])"
        val authStartIndex = background.indexOf(authStart)
        val authEndIndex = if (authStartIndex >= 0) background.indexOf(authEnd, authStartIndex) else -1
        check(authStartIndex >= 0 && authEndIndex > authStartIndex) {
            "Upstream 1VPN proxy-auth handler changed; review before bundling."
        }
        val authEndExclusive = authEndIndex + authEnd.length
        var authStatement = background.substring(authStartIndex, authEndExclusive)
        val credentialResolve = Regex("[A-Za-z_$][A-Za-z0-9_$]*\\(\\{\\s*authCredentials\\s*:\\s*([^}]+)\\}\\)")
        val challengerVariable = Regex("([A-Za-z_$][A-Za-z0-9_$]*)\\.challenger\\.host").find(authStatement)?.groupValues?.get(1)
        check(credentialResolve.containsMatchIn(authStatement) && challengerVariable != null) {
            "Upstream 1VPN auth callback changed; cannot safely relay proxy credentials to Cefrium."
        }
        authStatement = credentialResolve.replace(authStatement) { match ->
            val credentialExpression = match.groupValues[1].trim()
            val callName = match.value.substringBefore("(")
            "if(globalThis.__novaPublishProxyCredentials){globalThis.__novaPublishProxyCredentials(" + credentialExpression + "," + challengerVariable + ".challenger)};" +
                callName + "({authCredentials:" + credentialExpression + "})"
        }
        background = background.replace(
            background.substring(authStartIndex, authEndExclusive),
            "try{" + authStatement + ";globalThis.__novaAuthReady=true}catch(e){globalThis.__novaAuthReady=false;console.error(e)}"
        )

        val proxyRegistration = "browser.proxy.onRequest.addListener(m,{urls:[\"<all_urls>\"]})"
        check(background.contains(proxyRegistration)) { "Upstream 1VPN proxy registration changed; review before bundling." }
        val torAwareProxyRegistration = """
            var __novaTorAwareProxyRequest = function(details) {
              if (globalThis.__novaTorGuestMode === true) {
                var torPort = Number(globalThis.__novaTorSocksPort);
                return {type: "socks", host: "127.0.0.1", port: torPort > 0 ? torPort : 1, socksVersion: 5, proxyDNS: true};
              }
              return m(details);
            };
            try {
              browser.proxy.onRequest.addListener(__novaTorAwareProxyRequest, {urls: ["<all_urls>"]});
              globalThis.__novaProxyReady = true;
            } catch (e) { globalThis.__novaProxyReady = false; console.error(e); }
        """.trimIndent()
        background = background.replace(proxyRegistration, torAwareProxyRegistration)

        val novaBridge = """
            ;(function() {
              var nativePort = null;
              var webRtcProtected = false;
              globalThis.__novaTorGuestMode = false;
              globalThis.__novaTorSocksPort = 0;
              globalThis.__novaPublishProxyCredentials = function(credentials, challenger) {
                try {
                  if (nativePort && credentials && credentials.username && credentials.password && challenger && challenger.host) {
                    nativePort.postMessage({
                      type: "proxyCredentials",
                      host: challenger.host,
                      port: challenger.port || 443,
                      username: credentials.username,
                      password: credentials.password
                    });
                  }
                } catch (_) {}
              };
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
                globalThis.__novaTorGuestMode = message.torGuestMode === true;
                var requestedTorPort = Number(message.torSocksPort);
                globalThis.__novaTorSocksPort = Number.isInteger(requestedTorPort) && requestedTorPort > 0 && requestedTorPort <= 65535 ? requestedTorPort : 0;
                await chrome.storage.local.set({ isConnected: enabled, currentLocation: location });
                webRtcProtected = await setWebRtcProtection(enabled || globalThis.__novaTorGuestMode);
                var torRoutingReady = !globalThis.__novaTorGuestMode || (globalThis.__novaProxyReady === true && globalThis.__novaTorSocksPort > 0 && webRtcProtected === true);
                report(enabled ? "1VPN browser proxy enabled" : (globalThis.__novaTorGuestMode ? "Guest Tor routing active" : "VPN off"), {
                  enabled: enabled,
                  location: location,
                  torGuestMode: globalThis.__novaTorGuestMode,
                  torRoutingReady: torRoutingReady
                });
              }
              function connectNative() {
                try {
                  nativePort = browser.runtime.connectNative("com.nova.browser.vpn");
                  nativePort.onMessage.addListener(function(message) {
                    if (message && message.type === "configure") configure(message).catch(function(error) {
                      report("1VPN configuration error", { error: String(error) });
                    });
                  });
                  nativePort.onDisconnect.addListener(function() {
                    nativePort = null;
                    setTimeout(connectNative, 2500);
                  });
                  nativePort.postMessage({
                    type: "ready",
                    supported: supported(),
                    status: supported() ? "1VPN proxy backend ready" : "GeckoView proxy API unavailable"
                  });
                } catch (_) {
                  nativePort = null;
                  setTimeout(connectNative, 3000);
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

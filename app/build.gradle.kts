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
        // Bump the internal add-on version whenever Nova patches the bundled bridge.
        // GeckoView's ensureBuiltIn() otherwise keeps an older installed copy with the same ID/version.
        manifest["version"] = "4.2.3.1"
        val permissions = (manifest["permissions"] as List<*>).map { it.toString() }.toMutableList()
        permissions.add("nativeMessaging")
        permissions.add("geckoViewAddons")
        check(permissions.contains("webRequest") && permissions.contains("webRequestBlocking")) {
            "Upstream 1VPN manifest no longer grants blocking webRequest permissions required by Nova's traffic gate."
        }
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
            (function() {
            // Hold requests until Nova has sent the persisted routing configuration.
            // This prevents a startup race from briefly sending traffic DIRECT.
            var __novaProxyConfigPromise = new Promise(function(resolve) { globalThis.__novaProxyConfigResolve = resolve; });
            // End the fallback chain with null so the browser cannot fall back to a
            // system/Wi-Fi proxy if the loopback sink refuses the connection.
            var __novaFailClosedProxy = [{type: "http", host: "127.0.0.1", port: 9}, null];
            var __novaTorAwareProxyRequest = function(details) {
              return __novaProxyConfigPromise.then(function() {
                if (globalThis.__novaTorGuestMode === true) {
                  var torPort = Number(globalThis.__novaTorSocksPort);
                  return Number.isInteger(torPort) && torPort > 0 && torPort <= 65535
                    ? {type: "socks", host: "127.0.0.1", port: torPort, socksVersion: 5, proxyDNS: true}
                    : __novaFailClosedProxy;
                }
                var vpnEnabled = globalThis.__nova1VpnEnabled === true;
                var decision;
                try { decision = m(details); }
                catch (e) { return vpnEnabled ? __novaFailClosedProxy : {type: "direct"}; }
                var enforce = function(result) {
                  var chain = Array.isArray(result) ? result : [result];
                  var containsDirectFallback = !result || chain.some(function(proxy) {
                    return !proxy || proxy.type === "direct";
                  });
                  if (vpnEnabled && containsDirectFallback) return __novaFailClosedProxy;
                  return result || (vpnEnabled ? __novaFailClosedProxy : {type: "direct"});
                };
                if (decision && typeof decision.then === "function") {
                  return decision.then(enforce, function() {
                    return vpnEnabled ? __novaFailClosedProxy : {type: "direct"};
                  });
                }
                return enforce(decision);
              });
            };
            try {
              browser.proxy.onRequest.addListener(__novaTorAwareProxyRequest, {urls: ["<all_urls>"]});
              globalThis.__novaProxyReady = true;
            } catch (e) { globalThis.__novaProxyReady = false; console.error(e); }
            })()
        """.trimIndent()
        background = background.replace(proxyRegistration, torAwareProxyRegistration)

        val novaBridge = """
            ;(function() {
              var nativePort = null;
              var nativeGeneration = 0;
              var configureQueue = Promise.resolve();
              var webRtcProtected = false;
              globalThis.__novaTorGuestMode = false;
              globalThis.__novaTorSocksPort = 0;
              globalThis.__nova1VpnEnabled = false;
              globalThis.__novaVpnConfigReady = false;
              globalThis.__novaTrafficGateReady = false;
              try {
                browser.webRequest.onBeforeRequest.addListener(function() {
                  return globalThis.__novaVpnConfigReady === true ? {} : {cancel: true};
                }, {urls: ["<all_urls>"]}, ["blocking"]);
                globalThis.__novaTrafficGateReady = true;
              } catch (e) { globalThis.__novaTrafficGateReady = false; console.error(e); }
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
                return globalThis.__novaProxyReady === true && globalThis.__novaAuthReady === true &&
                  globalThis.__novaTrafficGateReady === true;
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
                    var applied = await setting.get({});
                    return applied.value === false;
                  }
                  var prior = await browser.storage.local.get("novaSavedPeerConnectionEnabled");
                  if (typeof prior.novaSavedPeerConnectionEnabled === "boolean") {
                    await setting.set({ value: prior.novaSavedPeerConnectionEnabled });
                    await browser.storage.local.remove("novaSavedPeerConnectionEnabled");
                  }
                  return true;
                } catch (_) { return false; }
              }
              async function configure(message, generation, port) {
                if (generation !== nativeGeneration || nativePort !== port) return;
                if (!Number.isSafeInteger(message.configRequestId) || message.configRequestId <= 0) {
                  throw new Error("Nova proxy configuration request ID is invalid");
                }
                // Reconfiguration is a protected transition too: pause traffic until
                // the new VPN/Tor state and leak protections have been applied.
                globalThis.__novaVpnConfigReady = false;
                var location = ["sgp", "ams", "lax"].indexOf(message.location) >= 0 ? message.location : "sgp";
                var enabled = message.enabled === true;
                var torGuestMode = message.torGuestMode === true;
                var requestedTorPort = Number(message.torSocksPort);
                var torSocksPort = Number.isInteger(requestedTorPort) && requestedTorPort > 0 && requestedTorPort <= 65535 ? requestedTorPort : 0;
                globalThis.__nova1VpnEnabled = enabled;
                globalThis.__novaTorGuestMode = torGuestMode;
                globalThis.__novaTorSocksPort = torSocksPort;
                await chrome.storage.local.set({ isConnected: enabled, currentLocation: location });
                if (generation !== nativeGeneration || nativePort !== port) return;
                var protectedState = await setWebRtcProtection(enabled || torGuestMode);
                if (generation !== nativeGeneration || nativePort !== port) return;
                webRtcProtected = protectedState === true;
                var torRoutingReady = !torGuestMode || (globalThis.__novaProxyReady === true &&
                  globalThis.__novaTrafficGateReady === true && torSocksPort > 0 && webRtcProtected === true);
                var vpnRoutingReady = !enabled || (supported() && webRtcProtected === true);
                report(enabled ? "1VPN browser proxy enabled" : (torGuestMode ? "Guest Tor routing active" : "VPN off"), {
                  enabled: enabled,
                  location: location,
                  torGuestMode: torGuestMode,
                  torRoutingReady: torRoutingReady,
                  routingReady: vpnRoutingReady && torRoutingReady,
                  configRequestId: message.configRequestId
                });
                if (!vpnRoutingReady) throw new Error("1VPN cannot safely route traffic because proxy authentication or WebRTC protection is unavailable");
                if (!torRoutingReady) throw new Error("Guest Tor cannot safely route traffic because SOCKS5 or WebRTC protection is unavailable");
                if (generation !== nativeGeneration || nativePort !== port) return;
                globalThis.__novaVpnConfigReady = true;
                if (globalThis.__novaProxyConfigResolve) {
                  globalThis.__novaProxyConfigResolve();
                  globalThis.__novaProxyConfigResolve = null;
                }
              }
              function connectNative() {
                try {
                  var connectedPort = browser.runtime.connectNative("com.nova.browser.vpn");
                  nativePort = connectedPort;
                  var generation = ++nativeGeneration;
                  connectedPort.onMessage.addListener(function(message) {
                    if (nativePort !== connectedPort || generation !== nativeGeneration) return;
                    if (message && message.type === "configure") {
                      configureQueue = configureQueue.then(function() {
                        return configure(message, generation, connectedPort);
                      }).catch(function(error) {
                        if (nativePort === connectedPort && generation === nativeGeneration) {
                          globalThis.__novaVpnConfigReady = false;
                          report("1VPN configuration error", {
                            error: String(error),
                            routingReady: false,
                            torGuestMode: globalThis.__novaTorGuestMode === true,
                            configRequestId: message.configRequestId
                          });
                        }
                      });
                    }
                  });
                  connectedPort.onDisconnect.addListener(function() {
                    if (nativePort !== connectedPort || generation !== nativeGeneration) return;
                    nativePort = null;
                    nativeGeneration++;
                    // Stop top-level and subresource traffic until Nova re-sends the
                    // persisted VPN/Tor configuration after the native bridge reconnects.
                    globalThis.__novaVpnConfigReady = false;
                    setTimeout(connectNative, 2500);
                  });
                  connectedPort.postMessage({
                    type: "ready",
                    supported: supported(),
                    status: supported() ? "1VPN proxy backend ready" : "GeckoView proxy API unavailable"
                  });
                } catch (_) {
                  nativePort = null;
                  nativeGeneration++;
                  globalThis.__novaVpnConfigReady = false;
                  setTimeout(connectNative, 3000);
                }
              }
              setWebRtcProtection(false).catch(function() {});
              connectNative();
            })();
        """.trimIndent()
        check(background.contains("(function()") && background.contains("globalThis.__novaProxyConfigResolve = resolve") &&
            background.contains("__novaProxyConfigPromise") && background.contains("__novaFailClosedProxy") &&
            background.contains("containsDirectFallback") && background.contains("proxy.type === \"direct\"") &&
            novaBridge.contains("globalThis.__novaProxyConfigResolve") && novaBridge.contains("__novaTrafficGateReady") &&
            novaBridge.contains("__novaVpnConfigReady") && novaBridge.contains("configureQueue") &&
            novaBridge.contains("nativeGeneration") && novaBridge.contains("applied.value === false") &&
            background.contains("var __novaFailClosedProxy = [{type: \"http\", host: \"127.0.0.1\", port: 9}, null]")) {
            "Nova's fail-closed proxy guard is missing from the generated 1VPN extension."
        }
        backgroundFile.writeText(background + "\n" + novaBridge)
    }
}
android.sourceSets.getByName("main").assets.srcDir(generatedAssetsRoot.get().asFile)
tasks.named("preBuild").configure { dependsOn(prepare1VpnAssets) }

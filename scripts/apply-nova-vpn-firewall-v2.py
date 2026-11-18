#!/usr/bin/env python3
from pathlib import Path

root = Path(".")
def replace_once(path, old, new):
    p = root / path
    text = p.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match for {old!r}, found {count}")
    p.write_text(text.replace(old, new, 1))

def replace_if_present(path, old, new):
    p = root / path
    if not p.exists():
        return
    text = p.read_text()
    if old in text:
        p.write_text(text.replace(old, new, 1))

main = "app/src/main/java/com/nova/browser/MainActivity.kt"
replace_once(main,
    "        configureExtensionPermissions()\n",
    "        configureExtensionPermissions()\n        NovaBrowserVpnController.initialize(this, runtime)\n")
replace_once(main,
    "                val installed = extensions.orEmpty()\n",
    "                val installed = extensions.orEmpty().filterNot { it?.id == NovaBrowserVpnController.EXTENSION_ID }\n")
replace_once(main, "Nova Firewall · TacUNS", "VPN & Firewall · 1VPN")

gradle = root / "app/build.gradle.kts"
gradle_text = gradle.read_text()
wireguard = '    implementation("com.wireguard.android:tunnel:1.0.20260102")\n'
if wireguard in gradle_text:
    gradle_text = gradle_text.replace(wireguard, "", 1)
elif "com.wireguard.android:tunnel" in gradle_text:
    raise SystemExit("Unexpected WireGuard dependency format; review before removal")

# Remove the previous attempted null failover marker if an earlier run applied it.
failover_start = "        val failOpenProxyList = "
if failover_start in gradle_text:
    failover_begin = gradle_text.index(failover_start)
    failover_end_marker = "        )\n"
    failover_end = gradle_text.index(failover_end_marker, failover_begin) + len(failover_end_marker)
    gradle_text = gradle_text[:failover_begin] + gradle_text[failover_end:]

# Upgrade the proxy/auth registration wrappers if an earlier run already added the task.
gradle_text = gradle_text.replace(
    'background = background.replace(authStatement, "try{" + authStatement + "}catch(e){console.error(e)}")',
    'background = background.replace(authStatement, "try{" + authStatement + ";globalThis.__novaAuthReady=true}catch(e){globalThis.__novaAuthReady=false;console.error(e)}")'
)
gradle_text = gradle_text.replace(
    '"try{" + proxyRegistration + "}catch(e){console.error(e)}"',
    '"try{" + proxyRegistration + ";globalThis.__novaProxyReady=true}catch(e){globalThis.__novaProxyReady=false;console.error(e)}"'
)
gradle_text = gradle_text.replace(
    'supported: !!(browser.proxy && browser.proxy.onRequest && chrome.webRequest && chrome.webRequest.onAuthRequired),',
    'supported: globalThis.__novaProxyReady === true && globalThis.__novaAuthReady === true,'
)
gradle_text = gradle_text.replace(
    'status: "1VPN proxy backend ready"',
    'status: (globalThis.__novaProxyReady === true && globalThis.__novaAuthReady === true) ? "1VPN proxy backend ready" : "GeckoView proxy API unavailable"'
)

imports = (
    "import groovy.json.JsonOutput\n"
    "import groovy.json.JsonSlurper\n"
    "import java.net.URL\n"
    "import java.security.MessageDigest\n"
    "import java.util.zip.ZipInputStream\n\n"
)
if "import groovy.json.JsonOutput" not in gradle_text:
    gradle_text = imports + gradle_text
if "generated1VpnDir" not in gradle_text:
    gradle_text += "\n" + "\nval generatedAssetsRoot = layout.buildDirectory.dir(\"generated/assets\")\nval generated1VpnDir = layout.buildDirectory.dir(\"generated/assets/nova_1vpn\")\nval prepare1VpnAssets by tasks.registering {\n    outputs.dir(generated1VpnDir)\n    doLast {\n        val outputDir = generated1VpnDir.get().asFile\n        delete(outputDir)\n        outputDir.mkdirs()\n        val archive = File(temporaryDir, \"1vpn-4.2.2.xpi\")\n        URL(\"https://addons.mozilla.org/firefox/downloads/latest/1vpn/latest.xpi\").openStream().use { input ->\n            archive.outputStream().use { output -> input.copyTo(output) }\n        }\n        val sha256 = MessageDigest.getInstance(\"SHA-256\")\n            .digest(archive.readBytes())\n            .joinToString(\"\") { \"%02x\".format(it.toInt() and 0xff) }\n        check(sha256 == \"112c58e7b7f283e01d0efaee1fb03cee673839588b2b6399a4dc075794c1f302\") {\n            \"The 1VPN add-on package changed. Review the upstream release and update the pinned SHA-256.\"\n        }\n        ZipInputStream(archive.inputStream()).use { zip ->\n            while (true) {\n                val entry = zip.nextEntry ?: break\n                val target = File(outputDir, entry.name)\n                check(target.canonicalPath.startsWith(outputDir.canonicalPath + File.separator)) {\n                    \"Unsafe path in upstream 1VPN archive\"\n                }\n                if (entry.isDirectory) target.mkdirs() else {\n                    target.parentFile?.mkdirs()\n                    target.outputStream().use { output -> zip.copyTo(output) }\n                }\n                zip.closeEntry()\n            }\n        }\n        val manifestFile = File(outputDir, \"manifest.json\")\n        val manifest = JsonSlurper().parse(manifestFile) as MutableMap<String, Any?>\n        val geckoSettings = manifest[\"browser_specific_settings\"] as MutableMap<String, Any?>\n        val gecko = geckoSettings[\"gecko\"] as MutableMap<String, Any?>\n        gecko[\"id\"] = \"1vpn-internal@nova.browser\"\n        val permissions = (manifest[\"permissions\"] as List<*>).map { it.toString() }.toMutableList()\n        permissions.add(\"nativeMessaging\")\n        permissions.add(\"geckoViewAddons\")\n        manifest[\"permissions\"] = permissions.distinct()\n        manifestFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)))\n\n        val backgroundFile = File(outputDir, \"background.bundle.js\")\n        var background = backgroundFile.readText()\n        val installTabCall = \"chrome.tabs.create({url:chrome.runtime.getURL(\\\"install.html\\\")})\"\n        check(background.contains(installTabCall)) { \"Upstream 1VPN install flow changed; review before patching.\" }\n        background = background.replace(installTabCall, \"void 0\")\n\n        val authStart = \"chrome.webRequest.onAuthRequired.addListener(\"\n        val authEnd = \",{urls:[\\\"<all_urls>\\\"]},[\\\"blocking\\\"])\"\n        val authStartIndex = background.indexOf(authStart)\n        val authEndIndex = if (authStartIndex >= 0) background.indexOf(authEnd, authStartIndex) else -1\n        check(authStartIndex >= 0 && authEndIndex > authStartIndex) {\n            \"Upstream 1VPN proxy-auth handler changed; review before bundling.\"\n        }\n        val authEndExclusive = authEndIndex + authEnd.length\n        val authStatement = background.substring(authStartIndex, authEndExclusive)\n        background = background.replace(\n            authStatement,\n            \"try{\" + authStatement + \";globalThis.__novaAuthReady=true}catch(e){globalThis.__novaAuthReady=false;console.error(e)}\"\n        )\n\n        val proxyRegistration = \"browser.proxy.onRequest.addListener(m,{urls:[\\\"<all_urls>\\\"]})\"\n        check(background.contains(proxyRegistration)) { \"Upstream 1VPN proxy registration changed; review before bundling.\" }\n        background = background.replace(\n            proxyRegistration,\n            \"try{\" + proxyRegistration + \";globalThis.__novaProxyReady=true}catch(e){globalThis.__novaProxyReady=false;console.error(e)}\"\n        )\n\n        val novaBridge = \"\"\"\n            ;(function() {\n              var nativePort = null;\n              var webRtcProtected = false;\n              function supported() {\n                return globalThis.__novaProxyReady === true && globalThis.__novaAuthReady === true;\n              }\n              function report(status, extra) {\n                try {\n                  if (nativePort) nativePort.postMessage(Object.assign({\n                    type: \"status\",\n                    status: status,\n                    supported: supported(),\n                    webrtcProtected: webRtcProtected\n                  }, extra || {}));\n                } catch (_) {}\n              }\n              async function setWebRtcProtection(enabled) {\n                try {\n                  var setting = browser.privacy && browser.privacy.network && browser.privacy.network.peerConnectionEnabled;\n                  if (!setting) return false;\n                  if (enabled) {\n                    var saved = await browser.storage.local.get(\"novaSavedPeerConnectionEnabled\");\n                    if (typeof saved.novaSavedPeerConnectionEnabled !== \"boolean\") {\n                      var current = await setting.get({});\n                      await browser.storage.local.set({ novaSavedPeerConnectionEnabled: current.value !== false });\n                    }\n                    await setting.set({ value: false });\n                    return true;\n                  }\n                  var prior = await browser.storage.local.get(\"novaSavedPeerConnectionEnabled\");\n                  if (typeof prior.novaSavedPeerConnectionEnabled === \"boolean\") {\n                    await setting.set({ value: prior.novaSavedPeerConnectionEnabled });\n                    await browser.storage.local.remove(\"novaSavedPeerConnectionEnabled\");\n                  }\n                  return true;\n                } catch (_) { return false; }\n              }\n              async function configure(message) {\n                var location = [\"sgp\", \"ams\", \"lax\"].indexOf(message.location) >= 0 ? message.location : \"sgp\";\n                var enabled = message.enabled === true;\n                await chrome.storage.local.set({ isConnected: enabled, currentLocation: location });\n                webRtcProtected = await setWebRtcProtection(enabled);\n                report(enabled ? \"1VPN browser proxy enabled\" : \"VPN off\", { enabled: enabled, location: location });\n              }\n              function connectNative() {\n                try {\n                  nativePort = browser.runtime.connectNative(\"com.nova.browser.vpn\");\n                  nativePort.onMessage.addListener(function(message) {\n                    if (message && message.type === \"configure\") configure(message).catch(function(error) {\n                      report(\"1VPN configuration error\", { error: String(error) });\n                    });\n                  });\n                  nativePort.onDisconnect.addListener(function() {\n                    nativePort = null;\n                    setTimeout(connectNative, 2500);\n                  });\n                  nativePort.postMessage({\n                    type: \"ready\",\n                    supported: supported(),\n                    status: supported() ? \"1VPN proxy backend ready\" : \"GeckoView proxy API unavailable\"\n                  });\n                } catch (_) {\n                  nativePort = null;\n                  setTimeout(connectNative, 3000);\n                }\n              }\n              setWebRtcProtection(false).catch(function() {});\n              connectNative();\n            })();\n        \"\"\".trimIndent()\n        backgroundFile.writeText(background + \"\\n\" + novaBridge)\n    }\n}\nandroid.sourceSets.getByName(\"main\").assets.srcDir(generatedAssetsRoot.get().asFile)\ntasks.named(\"preBuild\").configure { dependsOn(prepare1VpnAssets) }\n"
gradle.write_text(gradle_text)

(root / "app/src/main/java/com/sentinel/ui/SentinelActivity.kt").write_text(
    "package com.sentinel.ui\n\nimport com.nova.browser.NovaVpnFirewallActivity\n\nclass SentinelActivity : NovaVpnFirewallActivity()\n"
)

for path in [
    "app/src/main/java/com/nova/browser/NovaVpnActivity.kt",
    "app/src/main/java/com/nova/browser/NovaFirewallConfig.kt",
    "app/src/test/java/com/nova/browser/NovaFirewallConfigTest.kt",
    "docs/NOVA_VPN_INTEGRATION.md",
]:
    target = root / path
    if target.exists():
        target.unlink()

replace_if_present(
    "app/src/main/java/com/nova/browser/NovaVpnFirewallActivity.kt",
    "Uses GeckoView's built-in tracking and content protection to block known trackers, fingerprinting and cryptomining. It does not filter traffic from other apps or act as a device-wide packet firewall.",
    "Uses GeckoView's built-in Enhanced Tracking Protection to block known trackers and tracking content. NovaShield's Aggressive mode adds stricter fingerprinting and cryptomining protection. It does not filter other apps or act as a device-wide packet firewall."
)
replace_if_present(
    "app/src/main/java/com/nova/browser/NovaVpnFirewallActivity.kt",
    "1VPN is a third-party provider. When enabled, browser requests are routed through its free proxy servers. See 1vpn.org/privacy-policy for its privacy terms. WebRTC protection is applied where GeckoView exposes the required setting; this is not a claim of a fully audited leak-proof tunnel.",
    "1VPN is a third-party provider. When enabled, browser requests are routed through its free proxy servers. See 1vpn.org/privacy-policy for its privacy terms. WebRTC protection is applied where supported. If all proxy servers fail, GeckoView may fall back to the browser's default route; treat this as best-effort browser proxying, not a leak-proof tunnel."
)
old_status = '''        status.text = when {
            !enabled -> "VPN: Off"
            !NovaBrowserVpnController.isProxySupported() -> "VPN: Waiting for the 1VPN proxy backend. $raw"
            else -> "VPN: On · ${selectedLocationLabel()} · browser proxy configured. Open an IP-check page to verify the exit IP."
        }'''
new_status = '''        status.text = when {
            !enabled -> "VPN: Off"
            raw.contains("error", ignoreCase = true) || raw.contains("disconnected", ignoreCase = true) || raw.contains("unavailable", ignoreCase = true) -> "VPN: $raw"
            !NovaBrowserVpnController.isProxySupported() -> "VPN: Waiting for the 1VPN proxy backend. $raw"
            else -> "VPN: On · ${selectedLocationLabel()} · browser proxy configured. Open an IP-check page to verify the exit IP."
        }'''
replace_if_present("app/src/main/java/com/nova/browser/NovaVpnFirewallActivity.kt", old_status, new_status)
replace_if_present(
    "docs/NOVA_BROWSER_VPN_FIREWALL.md",
    "The integration adds a no-direct-fallback marker to the provider proxy failover list and suppresses the provider's first-install tab. WebRTC peer connections are disabled while enabled when GeckoView exposes the required privacy setting, then restored to their prior value. This is not a guarantee against every possible IP leak. HTTP proxying, WebRTC, DNS, QUIC, downloads, WebSockets and service-worker behavior must be verified on supported devices before claiming leak-proof VPN protection.",
    "The integration suppresses the provider's first-install tab. The Firefox proxy API can fall back to the browser's configured default route if all provider proxy endpoints fail; this integration therefore cannot guarantee fail-closed or leak-proof routing. WebRTC peer connections are disabled while enabled when GeckoView exposes the required privacy setting, then restored to their prior value. HTTP proxying, WebRTC, DNS, QUIC, downloads, WebSockets and service-worker behavior still require device-level verification."
)

print("Applied browser-only VPN/firewall migration with honest proxy failover behavior.")

/*
 * Nova 1VPN bridge. Free proxy endpoints and shared free-tier credentials are
 * taken from the public 1VPN browser-extension project (GPL-3.0).
 * Only the three published free locations are enabled here.
 */
const NATIVE_APP = "com.nova.browser.vpn";
const FREE_CREDENTIALS = {
  username: "a2epfq5ugq0u",
  password: "ptkx3fqg6v7n"
};
const LOCATIONS = {
  sgp: [
    "free-singapore-https-1.cloudburstcdn.com",
    "free-singapore-https-2.cloudtimecdn.com",
    "free-singapore-https-3.weathercloudapp.com",
    "free-singapore-https-4.cloudflingcdn.com"
  ],
  ams: [
    "free-amsterdam-https-1.cloudburstcdn.com",
    "free-amsterdam-https-2.cloudflingcdn.com",
    "free-amsterdam-https-3.cloudflaracdn.com",
    "free-amsterdam-https-4.cloudflaracdn.com",
    "free-amsterdam-https-5.cloudflingcdn.com",
    "free-amsterdam-https-6.cloudflingcdn.com"
  ],
  lax: [
    "free-los-angeles-https-1.cloudburstcdn.com",
    "free-los-angeles-https-2.cloudtimecdn.com",
    "free-los-angeles-https-3.cloudflaracdn.com",
    "free-los-angeles-https-4.cloudflaracdn.com",
    "free-los-angeles-https-5.cloudflingcdn.com",
    "free-los-angeles-https-6.cloudflingcdn.com"
  ]
};
const ALL_PROXY_HOSTS = new Set(Object.values(LOCATIONS).flat());
const DIRECT_DOMAINS = [
  "localhost", "127.0.0.1", "1vpn.org", "1vpn.co",
  "cloudlogcdn.com", "onevpn.com", "cloudflaircdn.com"
];
let enabled = false;
let locationId = "sgp";
let hostIndex = 0;
let nativePort = null;
let originalWebRtcPolicy = null;
let webrtcProtected = false;

function isDirectHost(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
  if (!host || host === "::1" || host.endsWith(".local")) return true;
  if (/^(10\.|127\.|169\.254\.|192\.168\.)/.test(host)) return true;
  if (/^(fc|fd)[0-9a-f]{2}:|^fe80:/i.test(host)) return true;
  const match = host.match(/^172\.(\d+)\./);
  return DIRECT_DOMAINS.some(domain => host === domain || host.endsWith("." + domain));
}

function selectedHosts() {
  return LOCATIONS[locationId] || LOCATIONS.sgp;
}

function selectedHost() {
  const hosts = selectedHosts();
  return hosts[hostIndex % hosts.length];
}

function report(type, status, supported = true) {
  if (!nativePort) return;
  try {
    nativePort.postMessage({
      type,
      supported,
      webrtcProtected,
      status
    });
  } catch (_) {
    // The Android activity may be shutting down; a later native port reconnects.
  }
}

async function enableWebRtcProtection() {
  const setting = browser.privacy && browser.privacy.network && browser.privacy.network.webRTCIPHandlingPolicy;
  if (!setting) return false;
  try {
    if (originalWebRtcPolicy === null) {
      const current = await setting.get({});
      originalWebRtcPolicy = current && current.value ? current.value : "default";
    }
    await setting.set({ value: "proxy_only" });
    const verified = await setting.get({});
    return Boolean(verified && verified.value === "proxy_only");
  } catch (_) {
    return false;
  }
}

async function restoreWebRtcProtection() {
  const setting = browser.privacy && browser.privacy.network && browser.privacy.network.webRTCIPHandlingPolicy;
  if (!setting || originalWebRtcPolicy === null) {
    webrtcProtected = false;
    return;
  }
  try {
    await setting.set({ value: originalWebRtcPolicy });
  } catch (_) {
    // Do not claim protection after a failed restore; the browser setting is not controlled.
  }
  originalWebRtcPolicy = null;
  webrtcProtected = false;
}

async function applyConfiguration(config) {
  enabled = Boolean(config.enabled);
  const requested = String(config.location || "sgp").toLowerCase();
  if (!Object.prototype.hasOwnProperty.call(LOCATIONS, requested)) {
    enabled = false;
    report("error", "Unsupported 1VPN location", false);
    return;
  }
  locationId = requested;
  hostIndex = 0;
  if (!enabled) {
    await restoreWebRtcProtection();
    report("status", "VPN off", true);
    return;
  }
  webrtcProtected = await enableWebRtcProtection();
  const region = locationId.toUpperCase();
  report(
    "status",
    webrtcProtected
      ? `1VPN proxy active · ${region} · WebRTC protected`
      : `1VPN proxy active · ${region} · WebRTC protection unavailable`,
    true
  );
}

browser.proxy.onRequest.addListener(details => {
  if (!enabled) return { type: "direct" };
  let hostname = "";
  try {
    hostname = new URL(details.url).hostname;
  } catch (_) {
    return { type: "direct" };
  }
  if (isDirectHost(hostname)) return { type: "direct" };
  const host = selectedHost();
  if (!host) return { type: "https", host: "127.0.0.1", port: 9 };
  return { type: "https", host, port: 443 };
}, { urls: ["<all_urls>"] });

browser.webRequest.onAuthRequired.addListener(details => {
  if (!details.isProxy) return {};
  const challenger = String(details.challenger && details.challenger.host || "").toLowerCase();
  if (!ALL_PROXY_HOSTS.has(challenger)) return {};
  return { authCredentials: FREE_CREDENTIALS };
}, { urls: ["<all_urls>"] }, ["blocking"]);

if (browser.proxy.onError) {
  browser.proxy.onError.addListener(error => {
    if (!enabled) return;
    const hosts = selectedHosts();
    hostIndex = (hostIndex + 1) % hosts.length;
    report("status", `1VPN proxy error; trying backup ${hostIndex + 1}/${hosts.length}`, true);
  });
}

let reconnectTimer = null;

function scheduleNativeReconnect() {
  if (reconnectTimer !== null) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(connectNativeBridge, 1000);
}

function connectNativeBridge() {
  if (nativePort) return;
  try {
    const port = browser.runtime.connectNative(NATIVE_APP);
    nativePort = port;
    port.onMessage.addListener(message => {
      if (!message || message.type !== "configure") return;
      applyConfiguration(message).catch(() => {
        enabled = false;
        restoreWebRtcProtection();
        report("error", "Could not configure the 1VPN proxy", false);
      });
    });
    port.onDisconnect.addListener(() => {
      if (nativePort !== port) return;
      nativePort = null;
      enabled = false;
      restoreWebRtcProtection();
      scheduleNativeReconnect();
    });
    report("ready", "1VPN free proxy bridge ready", true);
  } catch (_) {
    nativePort = null;
    scheduleNativeReconnect();
  }
}

connectNativeBridge();

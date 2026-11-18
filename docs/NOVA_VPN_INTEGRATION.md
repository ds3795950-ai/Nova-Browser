# Nova remote VPN integration

Nova embeds the official WireGuard Android tunnel library (`com.wireguard.android:tunnel`) and uses its `GoBackend` userspace implementation. The VPN service is part of the Nova app; no separate VPN app is installed.

## Setup and use

1. Obtain a WireGuard `.conf` profile from a VPN provider or a WireGuard server you administer.
2. In Nova Settings, open **Nova VPN · WireGuard** and import the profile.
3. Tap Connect and approve Android's VPN permission prompt. Tap Disconnect to stop the tunnel.

Nova does not include a remote VPN server, paid subscription, login, country list, or free server pool. Importing a profile is required. The profile's peer endpoint determines the remote exit server and therefore the public IP/location.

## Credential handling

The imported profile contains sensitive private-key material. Nova encrypts it with AES-GCM using a non-exportable Android Keystore key and stores the ciphertext in app-private storage. The profile is not uploaded to Nova servers. Android app backup is disabled in the current app manifest.

## Firewall and routing scope

Android permits only one active VPN service per user/profile. The previous TacUNS local VPN engine is no longer packaged as Nova's active VPN service; WireGuard is now the sole tunnel owner. WireGuard profiles can use `IncludedApplications` and `ExcludedApplications` for split tunneling, and their `DNS` setting chooses the DNS resolver. These controls are routing/DNS controls, not a full packet firewall: excluded apps bypass the tunnel rather than being blocked, and this first WireGuard screen does not reproduce TacUNS's domain-rule engine, packet logs, or per-app deny rules. Do not describe those retired TacUNS features as active.

A filtering DNS resolver can be specified in the WireGuard profile's `[Interface]` `DNS` line, if desired. DNS filtering does not block direct-IP connections or app-specific encrypted DNS.

## Compatibility

The app currently targets Android 10 (API 29) and newer. WireGuard uses Android's `VpnService`; it conflicts with Orbot and any other active VPN app/service.

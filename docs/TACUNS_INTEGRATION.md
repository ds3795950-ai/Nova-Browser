# TacUNS engine status

TacUNS was previously integrated as a local-only VPN firewall. It has been removed from the active Gradle application graph in favor of the embedded WireGuard backend, because Android allows only one active VPN service per user/profile. Its source directory/submodule may remain in repository history for reference, but it is not compiled into the current Nova application. The current Nova VPN screen does not expose TacUNS packet filtering, domain rules, or packet logs. See `NOVA_VPN_INTEGRATION.md` for the current capability and limitations.

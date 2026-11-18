# Psiphon VPN handoff

Nova provides a clearly labelled handoff to the official Psiphon Android app. Nova does not embed Psiphon's tunnel library, repackage Psiphon, or claim to operate Psiphon infrastructure.

The Settings action checks for the official Psiphon package IDs and opens the installed app. If neither is installed, it offers the official Google Play listing and Psiphon download page. The supported package IDs are `com.psiphon3` and `com.psiphon3.subscription`.

Psiphon owns the VPN session and its connection state. Nova never displays a connected status based on launching the app. Android permits only one active VPN service per user/profile, so Psiphon cannot run simultaneously with Orbot or another VPN. Psiphon's Android VPN may route device/profile traffic, not just Nova tabs. Nova cannot verify Psiphon's tunnel, DNS behavior, kill switch, or traffic route.

This is an external-app integration, not a native Nova VPN implementation. Official download information: https://psiphon.ca/en/download.html.

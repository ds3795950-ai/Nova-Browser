# BrowserStack real-device regression tests

The workflow `.github/workflows/browserstack-home-search.yml` builds a debug Android App Bundle, uploads it to BrowserStack App Automate, and runs Appium checks for Nova's home screen, search submission, Home navigation, and keyboard Enter submission.

## Required GitHub repository secrets

Add these under **Settings → Secrets and variables → Actions → New repository secret**:

- `BROWSERSTACK_USERNAME`: the username shown in your BrowserStack account.
- `BROWSERSTACK_ACCESS_KEY`: the access key from BrowserStack Account Settings → Access Keys.

Do not commit either value or paste the access key into source files. The workflow reads them only at runtime.

## Run

Open **Actions → BrowserStack Home and Search Tests → Run workflow**. Choose an Android device name and OS version available to your BrowserStack App Automate plan. The initial defaults are Google Pixel 8 / Android 14.0; change them if that device/OS is not enabled on your account.

The workflow uses a debug AAB, so release signing secrets are not required. BrowserStack App Automate usage is subject to the account's device availability and plan limits. Screenshots and test diagnostics are uploaded as a GitHub Actions artifact even when a test fails.

## Coverage and limits

This is an initial UI smoke/regression suite, not exhaustive cross-device QA. It checks that the home screen and address field are discoverable, the Go button submits a search, Home returns to the home screen, and Android Enter submits a URL. It does not validate every search engine, every screen size, offline behavior, or page rendering quality.

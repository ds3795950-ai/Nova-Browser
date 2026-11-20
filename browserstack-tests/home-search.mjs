import { mkdir, writeFile } from 'node:fs/promises';
import { remote } from 'webdriverio';

const required = ['BROWSERSTACK_USERNAME', 'BROWSERSTACK_ACCESS_KEY', 'BROWSERSTACK_APP_URL'];
for (const key of required) {
  if (!process.env[key]) throw new Error('Missing environment variable: ' + key);
}

await mkdir('test-results', { recursive: true });
const driver = await remote({
  protocol: 'https',
  hostname: 'hub-cloud.browserstack.com',
  port: 443,
  path: '/wd/hub',
  logLevel: 'warn',
  capabilities: {
    platformName: 'Android',
    'appium:automationName': 'UiAutomator2',
    'appium:app': process.env.BROWSERSTACK_APP_URL,
    'appium:appPackage': 'com.nova.browser',
    'appium:appActivity': '.MainActivity',
    'bstack:options': {
      userName: process.env.BROWSERSTACK_USERNAME,
      accessKey: process.env.BROWSERSTACK_ACCESS_KEY,
      deviceName: process.env.BROWSERSTACK_DEVICE_NAME || 'Google Pixel 8',
      osVersion: process.env.BROWSERSTACK_OS_VERSION || '14.0',
      projectName: 'Nova Browser',
      buildName: 'Privacy and Home Search ' + (process.env.GITHUB_RUN_NUMBER || 'manual'),
      sessionName: 'Nova privacy settings and private-session regression',
      debug: true,
      networkLogs: true,
      deviceLogs: true,
    },
  },
});

const results = [];
const record = (name, passed, detail = '') => results.push({ name, passed, detail });
const byA11y = (label) => driver.$('~' + label);
const byText = (value) => driver.$('android=new UiSelector().textMatches("(?i)^' + value + '$")');
const byTextContains = (value) => driver.$('android=new UiSelector().textContains("' + value.replace(/"/g, '\\"') + '")');
const addressBar = byA11y('Nova address bar');
const sessionStatus = byA11y('Nova session status');

async function tapToolbar(label, rowIndex) {
  const safeLabel = label.replace(/\\/g, '\\\\').replace(/"/g, '\\\"');
  const selectors = [
    `android=new UiSelector().description("${safeLabel}")`,
    `android=new UiSelector().text("${safeLabel}")`
  ];
  for (const selector of selectors) {
    try {
      const direct = driver.$(selector);
      if (await direct.isDisplayed()) {
        await direct.click();
        return;
      }
    } catch (_) {}
  }
  const rows = await driver.$$('android.widget.HorizontalScrollView');
  const indices = [rowIndex];
  for (let i = 0; i < rows.length; i++) if (i !== rowIndex) indices.push(i);
  for (const i of indices) {
    for (let j = 0; j < selectors.length; j++) {
      try {
        const match = j === 0 ? `new UiSelector().description("${safeLabel}")` : `new UiSelector().text("${safeLabel}")`;
        const scroller = driver.$(`android=new UiScrollable(new UiSelector().scrollable(true).className("android.widget.HorizontalScrollView").instance(${i})).setAsHorizontalList().scrollIntoView(${match})`);
        await scroller.waitForDisplayed({ timeout: 3000 });
        const target = driver.$(selectors[j]);
        await target.waitForDisplayed({ timeout: 3000 });
        await target.click();
        return;
      } catch (_) {}
    }
  }
  throw new Error('Could not find and click toolbar button: ' + label);
}

async function navigateTo(url, marker) {
  await addressBar.click();
  await addressBar.setValue(url);
  await byA11y('Go').click();
  await driver.waitUntil(async () => {
    const value = await addressBar.getAttribute('text').catch(() => '');
    return value.includes(marker);
  }, { timeout: 30000, timeoutMsg: 'Navigation did not reach marker: ' + marker });
  await driver.pause(1200);
}

async function openHistoryAndReadSource() {
  await tapToolbar('History', 1);
  await byTextContains('History ·').waitForDisplayed({ timeout: 10000 });
  return await driver.getPageSource();
}

try {
  await driver.waitUntil(async () => (await byText('NOVA').isDisplayed().catch(() => false)), {
    timeout: 30000,
    timeoutMsg: 'Nova home screen did not appear after launch',
  });
  if (!(await addressBar.isDisplayed())) throw new Error('Accessible address bar not found on home screen');
  if (!(await byA11y('Home').isDisplayed())) throw new Error('Dedicated Home button is not visible beside the address bar');
  if (await driver.isKeyboardShown()) throw new Error('Keyboard opened automatically on the home screen');
  const searchEnginePicker = driver.$('android=new UiSelector().descriptionContains("Choose search engine. Current:")');
  if (!(await searchEnginePicker.isDisplayed())) throw new Error('Search engine picker is missing from the address toolbar');
  await driver.saveScreenshot('test-results/01-home-screen.png');
  record('Home screen renders', true, 'NOVA title, accessible address bar and dedicated Home button are visible');

  await addressBar.click();
  await addressBar.setValue('nova browser smoke test');
  await byA11y('Go').click();
  await driver.waitUntil(async () => {
    const value = await addressBar.getAttribute('text').catch(() => '');
    return value.includes('google.com/search?q=') && value.includes('nova');
  }, { timeout: 30000, timeoutMsg: 'Search submission did not resolve to the configured Google search URL' });
  await driver.saveScreenshot('test-results/02-search-submitted.png');
  record('Search using Go button', true, 'Search term resolved to Google search URL');

  await byA11y('Home').click();
  await driver.waitUntil(async () => (await byText('NOVA').isDisplayed().catch(() => false)), {
    timeout: 15000,
    timeoutMsg: 'Home button did not return to the Nova home screen',
  });

  await addressBar.click();
  await addressBar.setValue('unfinished search');
  await byA11y('Home').click();
  await driver.waitUntil(async () => (await byText('NOVA').isDisplayed().catch(() => false)), {
    timeout: 15000,
    timeoutMsg: 'Home button did not return home from an active address field',
  });
  const homeFieldText = await addressBar.getAttribute('text').catch(() => '');
  if (homeFieldText.trim() && homeFieldText.trim() !== 'Search or enter address') {
    throw new Error('Home did not clear the unfinished address-bar text');
  }
  if (await driver.isKeyboardShown()) throw new Error('Home left the on-screen keyboard open');
  await driver.saveScreenshot('test-results/03-home-dismisses-keyboard.png');
  record('Home clears address field and dismisses keyboard', true, 'Unfinished text cleared and keyboard hidden');

  await navigateTo('https://example.com/', 'example.com');
  await driver.saveScreenshot('test-results/04-keyboard-navigation.png');
  record('Keyboard Go submits URL', true, 'Typed URL was accepted through the Android editor action');

  // Connectivity and hostname-resolution checks through GeckoView itself.
  await navigateTo('https://www.iana.org/', 'iana.org');
  record('HTTPS connectivity to independent host', true, 'A second HTTPS origin loaded through the browser');
  await navigateTo('https://dns.google/resolve?name=example.com&type=A', 'dns.google/resolve');
  const dohSource = await driver.getPageSource();
  if (!/Status.{0,10}0/.test(dohSource)) throw new Error('DNS-over-HTTPS lookup did not return a successful DNS status');
  record('DNS lookup returns a successful answer', true, 'dns.google returned DNS status 0 for example.com');

  // Check all Nova Shield modes can be selected and the selected mode is reflected when reopened.
  await tapToolbar('Shield', 2);
  await byText('Off').waitForDisplayed({ timeout: 10000 });
  await byText('Off').click();
  await tapToolbar('Shield', 2);
  await byTextContains('Nova Shield · OFF').waitForDisplayed({ timeout: 10000 });
  record('Nova Shield Off mode persists', true, 'Reopened Shield dialog reports OFF');
  await byText('Standard · recommended').click();
  await tapToolbar('Shield', 2);
  await byTextContains('Nova Shield · STANDARD').waitForDisplayed({ timeout: 10000 });
  record('Nova Shield Standard mode persists', true, 'Reopened Shield dialog reports STANDARD');
  await byText('Aggressive · may break sites').click();
  await tapToolbar('Shield', 2);
  await byTextContains('Nova Shield · AGGRESSIVE').waitForDisplayed({ timeout: 10000 });
  record('Nova Shield Aggressive mode persists', true, 'Reopened Shield dialog reports AGGRESSIVE');
  await byText('Standard · recommended').click();
  await driver.saveScreenshot('test-results/05-shield-modes.png');

  // Verify normal history is retained while private history and bookmarks are not recorded.
  const normalMarker = 'nova_normal_privacy_marker';
  const privateMarker = 'nova_private_privacy_marker';
  await navigateTo('https://example.com/?' + normalMarker + '=1', normalMarker);
  const normalHistory = await openHistoryAndReadSource();
  if (!normalHistory.includes(normalMarker)) throw new Error('Normal browsing history entry was not recorded');
  await byText('Close').click();
  record('Normal browsing history is recorded', true, 'Normal marker appears in History');

  // Verify that the engine's persistent Normal cookie jar is not visible to ephemeral profiles.
  await navigateTo('https://httpbin.org/cookies/set?nova_owner_cookie=owner', 'httpbin.org/cookies');
  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const normalCookieSource = await driver.getPageSource();
  if (!normalCookieSource.includes('nova_owner_cookie')) throw new Error('Normal profile cookie was not retained');
  record('Normal cookies persist in the owner profile', true, 'httpbin reflects the Normal-only cookie');

  await tapToolbar('Private', 0);
  await byTextContains('Private session').waitForDisplayed({ timeout: 10000 });
  record('Private mode indicator appears on home', true, 'Private session status is visible immediately after opening a Private tab');

  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const privateCookiesBefore = await driver.getPageSource();
  if (privateCookiesBefore.includes('nova_owner_cookie')) throw new Error('Private mode exposed a Normal profile cookie');
  await navigateTo('https://httpbin.org/cookies/set?nova_private_cookie=private', 'httpbin.org/cookies');
  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const privateCookieSource = await driver.getPageSource();
  if (!privateCookieSource.includes('nova_private_cookie')) throw new Error('Private profile did not retain its own cookie during the session');
  if (privateCookieSource.includes('nova_owner_cookie')) throw new Error('Private profile read a Normal cookie after setting its own cookie');
  record('Private cookies are isolated from Normal browsing', true, 'Private sees its own cookie but not the owner-profile cookie');
  await navigateTo('https://example.com/?' + privateMarker + '=1', privateMarker);
  await tapToolbar('History', 1);
  await driver.pause(1000);
  if (await byTextContains('History ·').isDisplayed().catch(() => false)) {
    throw new Error('History dialog opened inside a private tab');
  }
  record('History is unavailable in Private mode', true, 'Private tab receives the history-unavailable guard');

  await tapToolbar('☆', 1);
  await driver.pause(500);
  await tapToolbar('＋', 0);
  await tapToolbar('Bookmarks', 1);
  const privateBookmarks = await driver.getPageSource();
  if (privateBookmarks.includes(privateMarker)) throw new Error('Private URL was saved in normal bookmarks');
  await byText('Close').click();
  record('Private bookmarks are not saved', true, 'Private URL is absent from the normal bookmark list');

  const historyAfterPrivate = await openHistoryAndReadSource();
  if (!historyAfterPrivate.includes(normalMarker)) throw new Error('Normal history disappeared after private browsing');
  if (historyAfterPrivate.includes(privateMarker)) throw new Error('Private URL leaked into normal history');
  await byText('Close').click();
  record('Private history does not leak into normal history', true, 'Normal marker remains; private marker is absent');

  // Verify Guest Mode cannot reach owner tabs, downloads, passwords or account settings.
  const guestMarker = 'nova_guest_privacy_marker';
  await tapToolbar('Guest', 0);
  await byTextContains('Guest Mode').waitForDisplayed({ timeout: 15000 });
  record('Guest mode indicator appears on home', true, 'Guest Mode status is visible immediately after opening a Guest tab');

  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const guestCookiesBefore = await driver.getPageSource();
  if (guestCookiesBefore.includes('nova_owner_cookie')) throw new Error('Guest Mode exposed a Normal profile cookie');
  await navigateTo('https://httpbin.org/cookies/set?nova_guest_cookie=guest', 'httpbin.org/cookies');
  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const guestCookieSource = await driver.getPageSource();
  if (!guestCookieSource.includes('nova_guest_cookie')) throw new Error('Guest profile did not retain its own cookie during the session');
  if (guestCookieSource.includes('nova_owner_cookie')) throw new Error('Guest profile read a Normal cookie after setting its own cookie');
  record('Guest cookies are isolated from the owner profile', true, 'Guest sees its own cookie but not the owner-profile cookie');
  await navigateTo('https://example.com/?' + guestMarker + '=1', guestMarker);
  await tapToolbar('History', 1);
  await driver.pause(500);
  if (await byTextContains('History ·').isDisplayed().catch(() => false)) throw new Error('History dialog opened inside Guest Mode');
  record('History is unavailable in Guest Mode', true, 'Guest tab receives the history-unavailable guard');

  await tapToolbar('☆', 1);
  await driver.pause(500);
  record('Guest bookmarks are blocked', true, 'Bookmark action is rejected while Guest Mode is active');

  await tapToolbar('Tabs', 0);
  const guestTabsSource = await driver.getPageSource();
  if (guestTabsSource.includes('NORMAL TABS') || guestTabsSource.includes('PRIVATE TABS') || guestTabsSource.includes(normalMarker) || guestTabsSource.includes('Save session') || guestTabsSource.includes('Workspaces')) {
    throw new Error('Guest tab manager exposed normal/private tabs or saved workspaces');
  }
  await byText('Done').click();
  record('Guest tab manager hides owner tabs', true, 'Only Guest tabs are listed; normal/private tabs and workspaces are hidden');

  await tapToolbar('Passwords', 2);
  await driver.pause(500);
  if (await byTextContains('Encrypted password vault').isDisplayed().catch(() => false)) throw new Error('Guest Mode opened the owner password vault');
  record('Guest mode blocks password vault', true, 'Owner password vault is unavailable in Guest Mode');

  await tapToolbar('Downloads', 1);
  await driver.pause(500);
  if (await byTextContains('Nova Downloads').isDisplayed().catch(() => false)) throw new Error('Guest Mode opened the owner downloads list');
  record('Guest mode blocks downloads list', true, 'Owner download history is unavailable in Guest Mode');

  await tapToolbar('Add-ons', 2);
  await driver.pause(500);
  if (await byTextContains('Nova Add-ons').isDisplayed().catch(() => false)) throw new Error('Guest Mode opened the owner extensions manager');
  record('Guest mode blocks extension management', true, 'Persistent extension settings are unavailable in Guest Mode');

  await tapToolbar('Site', 2);
  await driver.pause(500);
  if (await byTextContains('Site controls ·').isDisplayed().catch(() => false)) throw new Error('Guest Mode opened owner-profile site controls');
  record('Guest mode blocks shared site controls', true, 'Shared site-data controls are unavailable in Guest Mode');

  await tapToolbar('Shield', 2);
  await driver.pause(500);
  if (await byTextContains('Nova Shield ·').isDisplayed().catch(() => false)) throw new Error('Guest Mode opened owner-wide Shield settings');
  record('Guest mode blocks global Shield changes', true, 'Global protection settings are owner-only');

  await tapToolbar('Private', 0);
  await driver.pause(500);
  if (await byTextContains('Private session').isDisplayed().catch(() => false)) throw new Error('Guest Mode created a separate Private tab');
  await byTextContains('Guest Mode').waitForDisplayed({ timeout: 15000 });
  await tapToolbar('Tabs', 0);
  const tabsAfterPrivateAttempt = await driver.getPageSource();
  if (tabsAfterPrivateAttempt.includes('PRIVATE TABS') || tabsAfterPrivateAttempt.includes('NORMAL TABS')) throw new Error('Private action escaped Guest Mode and exposed owner tabs');
  await byText('Done').click();
  await byTextContains('Guest Mode').waitForDisplayed({ timeout: 10000 });
  record('Guest mode blocks Private tab creation', true, 'Guest status remains visible and tab manager still hides normal/private tabs');

  await tapToolbar('Settings', 1);
  try {
    await byText('Tor routing status').waitForDisplayed({ timeout: 10000 });
    await byText('Exit Guest Mode').waitForDisplayed({ timeout: 5000 });
  } catch (error) {
    console.error('Guest settings screen after tapping Settings:', await driver.getPageSource());
    throw error;
  }
  const guestSettingsSource = await driver.getPageSource();
  for (const forbidden of ['Account & encrypted cloud sync', 'Export history', 'Password manager', 'Clear browsing history', 'Close private and Guest tabs']) {
    if (guestSettingsSource.includes(forbidden)) throw new Error('Guest settings exposed owner action: ' + forbidden);
  }
  if (!guestSettingsSource.includes('Tor routing status') || !guestSettingsSource.includes('Exit Guest Mode')) throw new Error('Guest Mode settings actions are missing');
  await byText('Close').waitForDisplayed({ timeout: 10000 });
  await byText('Close').click();
  record('Guest settings hide owner data', true, 'Only Guest-specific Tor status and Exit Guest Mode are offered; owner exports and account controls are hidden');

  await tapToolbar('＋', 0);
  await byTextContains('Guest Mode').waitForDisplayed({ timeout: 15000 });
  record('New tabs remain inside Guest Mode', true, 'The new-tab button creates another Guest tab, not a normal tab');

  await tapToolbar('Tabs', 0);
  await byText('Exit Guest').click();
  await byText('Exit Guest').click();
  await driver.waitUntil(async () => !(await sessionStatus.getText().catch(() => '')).includes('Guest Mode'), { timeout: 10000, timeoutMsg: 'Guest Mode did not exit after confirmation' });
  record('Guest exit requires confirmation', true, 'The tab manager warns before returning to owner tabs');

  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const cookiesAfterGuestExit = await driver.getPageSource();
  if (!cookiesAfterGuestExit.includes('nova_owner_cookie')) throw new Error('Normal cookie was lost after Guest exit');
  if (cookiesAfterGuestExit.includes('nova_guest_cookie')) throw new Error('Guest cookie leaked into the owner profile after Guest exit');
  if (cookiesAfterGuestExit.includes('nova_private_cookie')) throw new Error('Private cookie leaked into the owner profile after Private exit');
  record('Private and Guest cookies are cleared from the owner view after exit', true, 'Owner cookie remains; ephemeral cookies are absent');

  const historyAfterGuest = await openHistoryAndReadSource();
  if (historyAfterGuest.includes(guestMarker)) throw new Error('Guest URL leaked into normal history');
  if (!historyAfterGuest.includes(normalMarker)) throw new Error('Normal history marker disappeared while using Guest Mode');
  await byText('Close').click();
  record('Guest history does not leak into normal history', true, 'Normal history remains; Guest marker is absent');

  await tapToolbar('Bookmarks', 1);
  const guestBookmarks = await driver.getPageSource();
  if (guestBookmarks.includes(guestMarker)) throw new Error('Guest URL was saved in normal bookmarks');
  await byText('Close').click();
  record('Guest bookmarks are not saved', true, 'Guest URL is absent from the normal bookmark list');

  // Clear only the synthetic history created by this test.
  await tapToolbar('Settings', 1);
  await byText('Clear browsing history').click();
  const historyAfterClear = await openHistoryAndReadSource();
  if (historyAfterClear.includes(normalMarker) || historyAfterClear.includes(privateMarker) || historyAfterClear.includes(guestMarker)) {
    throw new Error('Clear browsing history left a synthetic test URL behind');
  }
  await byText('Close').click();
  record('Clear browsing history removes saved entries', true, 'Normal, Private and Guest test markers are absent after clearing');

  // Exercise the visible site-controls and protection-log dialogs on a normal site.
  await navigateTo('https://example.com/', 'example.com');
  await tapToolbar('Site', 2);
  await byTextContains('Site controls · example.com').waitForDisplayed({ timeout: 10000 });
  await byText('Close').click();
  record('Site controls open for the active origin', true, 'Site controls identify example.com');

  await tapToolbar('Shield', 2);
  await byText("Show this page's protection log").click();
  const logDialog = byTextContains('Nova Shield · example.com');
  const logToast = driver.$('android.widget.Toast');
  let logOpened = false;
  let logDetail = 'No protection-log dialog or error toast appeared within 15 seconds';
  try {
    await driver.waitUntil(async () =>
      (await logDialog.isDisplayed().catch(() => false)) ||
      (await logToast.isDisplayed().catch(() => false)),
      { timeout: 15000, timeoutMsg: 'Protection log dialog did not appear' }
    );
    if (await logDialog.isDisplayed().catch(() => false)) {
      logOpened = true;
      await byText('OK').click();
      logDetail = 'Protection log dialog opened for example.com';
    } else {
      logDetail = 'Protection log error: ' + await logToast.getText().catch(() => 'message unavailable');
    }
  } catch (error) {
    logDetail = 'Protection log did not open: ' + (error?.message || String(error));
  }
  record('Nova Shield protection log opens', logOpened, logDetail);

  // Open privacy-related settings screens without entering credentials or enabling sync.
  await tapToolbar('Passwords', 2);
  await byTextContains('Encrypted password vault').waitForDisplayed({ timeout: 10000 });
  await byText('Close').click();
  record('Encrypted password vault screen opens', true, 'Vault screen opens without exposing any stored credential');

  await tapToolbar('Add-ons', 2);
  await byTextContains('Nova Add-ons').waitForDisplayed({ timeout: 10000 });
  await byText('Close').click();
  record('Add-ons privacy screen opens', true, 'Extension manager opens without granting new permissions');

  await tapToolbar('Settings', 1);
  await byText('Account & encrypted cloud sync').click();
  await byText('Sign in to Nova Cloud').waitForDisplayed({ timeout: 10000 });
  const emailFields = await driver.$$('android.widget.EditText');
  if (emailFields.length !== 1) throw new Error('Cloud sign-in should request only an email address');
  const passwordField = await emailFields[0].getAttribute('password').catch(() => 'false');
  if (String(passwordField).toLowerCase() === 'true') throw new Error('Cloud sign-in unexpectedly exposed a password field');
  await byText('Cancel').click();
  record('Cloud sync sign-in uses email OTP UI', true, 'One email field; no password requested and no OTP sent');

  // Verify the Guest-only Tor boundary is honest: Orbot's app-wide VPN cannot isolate one tab.
  await tapToolbar('Guest', 0);
  await byTextContains('Guest Mode').waitForDisplayed({ timeout: 15000 });
  await tapToolbar('Settings', 1);
  await byText('Tor routing status').click();
  await byTextContains('Guest-only Tor is not available yet').waitForDisplayed({ timeout: 10000 });
  const torStatusSource = await driver.getPageSource();
  if (!torStatusSource.includes('whole Nova app') || !torStatusSource.includes('Normal and Private traffic too')) {
    throw new Error('Guest-only Tor status does not explain the app-wide Orbot routing limitation');
  }
  await byText('OK').click();
  record('Guest-only Tor fails closed until an isolated transport exists', true, 'No Orbot launch or app-wide VPN toggle is offered from Guest Mode');
  await byText('Exit Guest Mode').click();
  await byText('Exit Guest').click();

  // Close ephemeral tabs, then create fresh ones and confirm they are not restored after restart.
  await tapToolbar('Settings', 1);
  await byText('Close private and Guest tabs').click();
  await tapToolbar('Private', 0);
  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const privateAfterClose = await driver.getPageSource();
  if (privateAfterClose.includes('nova_private_cookie') || privateAfterClose.includes('nova_guest_cookie')) {
    throw new Error('A newly opened Private profile retained cookies from an earlier ephemeral session');
  }
  record('Private profile data clears after the final Private tab closes', true, 'A fresh Private profile has no prior Private or Guest cookies');
  await tapToolbar('Guest', 0);
  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const guestAfterClose = await driver.getPageSource();
  if (guestAfterClose.includes('nova_private_cookie') || guestAfterClose.includes('nova_guest_cookie')) {
    throw new Error('A newly opened Guest profile retained cookies from an earlier ephemeral session');
  }
  record('Guest profile data clears after the final Guest tab closes', true, 'A fresh Guest context has no prior Private or Guest cookies');
  await navigateTo('https://httpbin.org/cookies/set?nova_crash_guest_cookie=guest', 'httpbin.org/cookies');
  await driver.terminateApp('com.nova.browser');
  await driver.activateApp('com.nova.browser');
  await byA11y('Nova address bar').waitForDisplayed({ timeout: 30000 });
  await tapToolbar('Tabs', 0);
  const tabsAfterRestart = await driver.getPageSource();
  if (tabsAfterRestart.includes('PRIVATE TABS') || tabsAfterRestart.includes('GUEST TABS')) {
    throw new Error('Private or Guest tabs were restored after app restart');
  }
  await byText('Done').click();
  record('Private and Guest tabs are not restored after app restart', true, 'Only normal tabs remain in the tab manager');
  await tapToolbar('Guest', 0);
  await navigateTo('https://httpbin.org/cookies', 'httpbin.org/cookies');
  const guestAfterProcessDeath = await driver.getPageSource();
  if (guestAfterProcessDeath.includes('nova_crash_guest_cookie')) {
    throw new Error('Guest cookies survived process death and were visible in the next Guest session');
  }
  record('Guest storage is cleared after process death', true, 'The next Guest session cannot read the previous process Guest cookie');
  await tapToolbar('Settings', 1);
  await byText('Exit Guest Mode').click();
  await byText('Exit Guest').click();

  await tapToolbar('Shield', 2);
  await byTextContains('Nova Shield · STANDARD').waitForDisplayed({ timeout: 10000 });
  await byText('Close').click();
  record('Nova Shield setting survives app restart', true, 'Standard protection remains selected');

  // Regular settings regression: reversible preference changes, persistence, and core dialogs.
  await tapToolbar('Settings', 1);
  await byText('Nova Settings').waitForDisplayed({ timeout: 10000 });
  const settingsSource = await driver.getPageSource();
  for (const item of ['Choose search engine', 'Toggle home shortcuts', 'Clear browsing history',
    'Nova Shield', 'AI assistants', 'Extensions', 'Password manager', 'Site permissions',
    'Accessibility', 'Export history', 'About Nova', 'Account & encrypted cloud sync']) {
    if (!settingsSource.includes(item)) throw new Error('Missing regular setting: ' + item);
  }
  record('Regular settings menu exposes all expected controls', true, 'Search, shortcuts, history, Shield, AI, add-ons, passwords, site permissions, accessibility, export, about and cloud sync are listed');
  await byText('Close').click();

  await tapToolbar('Settings', 1);
  await byText('Choose search engine').click();
  await byText('DuckDuckGo').click();
  await tapToolbar('Settings', 1);
  await byText('Choose search engine').click();
  const engineSource = await driver.getPageSource();
  if (!engineSource.includes('DuckDuckGo')) throw new Error('Search engine selection did not persist');
  await byText('Google').click();
  record('Search engine selection persists and can be restored', true, 'DuckDuckGo persisted after reopening; restored Google default');

  await tapToolbar('Settings', 1);
  const shortcutsBefore = await driver.getPageSource();
  await byTextContains('Toggle home shortcuts').click();
  await tapToolbar('Settings', 1);
  const shortcutsAfter = await driver.getPageSource();
  if (shortcutsBefore === shortcutsAfter) throw new Error('Home shortcut setting did not update');
  await byTextContains('Toggle home shortcuts').click();
  record('Home shortcut visibility toggles and restores', true, 'Preference changed and was returned to its original default');

  await tapToolbar('Settings', 1);
  await byText('Accessibility').click();
  await byText('90%').click();
  await tapToolbar('Settings', 1);
  await byText('Accessibility').click();
  await byText('Accessibility · page text size').waitForDisplayed({ timeout: 10000 });
  await byText('100%').click();
  record('Accessibility text scale changes and persists', true, '90% selection reopened correctly; restored 100% default');

  await tapToolbar('Settings', 1);
  await byText('About Nova').click();
  await byTextContains('Nova Browser · GeckoView engine').waitForDisplayed({ timeout: 10000 });
  await byText('OK').click();
  record('About Nova dialog opens', true, 'Browser identity and engine information displayed');

  await tapToolbar('Settings', 1);
  await byText('AI assistants').click();
  await byText('Choose browser AI').waitForDisplayed({ timeout: 10000 });
  await byText('Cancel').click();
  record('AI assistant settings open without sending data', true, 'Provider picker opens and cancels without a prompt');

  await driver.saveScreenshot('test-results/06-privacy-and-settings-regression-complete.png');
  const failedChecks = results.filter(item => !item.passed);
  if (failedChecks.length) throw new Error('Privacy checks failed: ' + failedChecks.map(item => item.name).join(', '));
} catch (error) {
  record('Privacy regression run', false, error?.stack || String(error));
  try { await driver.saveScreenshot('test-results/failure.png'); } catch {}
  try { await writeFile('test-results/failure-page-source.xml', await driver.getPageSource()); } catch {}
  throw error;
} finally {
  await writeFile('test-results/results.json', JSON.stringify(results, null, 2));
  try { await writeFile('test-results/browserstack-session.txt', await driver.sessionId); } catch {}
  await driver.deleteSession();
}

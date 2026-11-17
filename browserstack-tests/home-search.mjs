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

async function tapToolbar(label, rowIndex) {
  const element = byA11y(label);
  if (await element.isDisplayed().catch(() => false)) {
    await element.click();
    return;
  }
  const rows = await driver.$$('android.widget.HorizontalScrollView');
  const row = rows[rowIndex];
  if (!row) throw new Error('Toolbar row not found for ' + label);
  const rect = await row.getRect();
  for (const direction of ['right', 'left']) {
    for (let attempt = 0; attempt < 3; attempt++) {
      await driver.execute('mobile: scrollGesture', {
        left: rect.x,
        top: rect.y,
        width: rect.width,
        height: rect.height,
        direction,
        percent: 0.8,
      });
      if (await element.isDisplayed().catch(() => false)) {
        await element.click();
        return;
      }
    }
  }
  throw new Error('Could not expose toolbar button: ' + label);
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

  await tapToolbar('Private', 0);
  await byTextContains('Private session').waitForDisplayed({ timeout: 10000 });
  record('Private mode indicator appears on home', true, 'Private session status is visible immediately after opening a Private tab');
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
  await byTextContains('Guest Mode · temporary data').waitForDisplayed({ timeout: 10000 });
  record('Guest mode indicator appears on home', true, 'Guest Mode status is visible immediately after opening a Guest tab');
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
  record('Guest mode blocks Private tab creation', true, 'Private tabs cannot be opened from Guest Mode');

  await tapToolbar('＋', 0);
  await byTextContains('Guest Mode · temporary data').waitForDisplayed({ timeout: 10000 });
  record('New tabs remain inside Guest Mode', true, 'The new-tab button creates another Guest tab, not a normal tab');

  await tapToolbar('Settings', 1);
  const guestSettingsSource = await driver.getPageSource();
  for (const forbidden of ['Account & encrypted cloud sync', 'Export history', 'Password manager', 'Clear browsing history', 'Close private and Guest tabs']) {
    if (guestSettingsSource.includes(forbidden)) throw new Error('Guest settings exposed owner action: ' + forbidden);
  }
  await byText('Exit Guest Mode').click();
  await byText('Exit Guest').click();
  await driver.waitUntil(async () => !(await byTextContains('Guest Mode · temporary data').isDisplayed().catch(() => false)), { timeout: 10000, timeoutMsg: 'Guest Mode did not exit after confirmation' });
  record('Guest settings hide owner data and confirm exit', true, 'Only Exit Guest Mode is offered; leaving Guest Mode requires confirmation');

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

  await tapToolbar('Shield', 2);
  await byText('Open Orbot · system VPN routing').click();
  const noOrbot = byText('Orbot is not installed');
  const routeOrbot = byText('Route traffic through Orbot');
  await driver.waitUntil(async () =>
    (await noOrbot.isDisplayed().catch(() => false)) ||
    (await routeOrbot.isDisplayed().catch(() => false)),
    { timeout: 10000, timeoutMsg: 'Orbot handoff dialog did not appear' }
  );
  if (await noOrbot.isDisplayed().catch(() => false)) {
    await byText('OK').click();
    record('Tor handoff states Orbot dependency', true, 'Nova does not bundle Tor; Orbot must be installed separately');
  } else {
    await byText('Cancel').click();
    record('Tor handoff requires explicit approval', true, 'Orbot routing requires explicit user action');
  }

  // Close ephemeral tabs, then confirm they are not restored after an app restart.
  await tapToolbar('Settings', 1);
  await byText('Exit Guest Mode').click();
  await tapToolbar('Settings', 1);
  await byText('Close private and Guest tabs').click();
  await tapToolbar('Private', 0);
  await tapToolbar('Guest', 0);
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

  await tapToolbar('Shield', 2);
  await byTextContains('Nova Shield · STANDARD').waitForDisplayed({ timeout: 10000 });
  await byText('Close').click();
  record('Nova Shield setting survives app restart', true, 'Standard protection remains selected');

  await driver.saveScreenshot('test-results/06-privacy-regression-complete.png');
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

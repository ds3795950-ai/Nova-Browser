import { mkdir, writeFile } from 'node:fs/promises';
import { remote } from 'webdriverio';

const required = ['BROWSERSTACK_USERNAME', 'BROWSERSTACK_ACCESS_KEY', 'BROWSERSTACK_APP_URL'];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Missing environment variable: ${key}`);
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
      buildName: `Home and Search ${process.env.GITHUB_RUN_NUMBER || 'manual'}`,
      sessionName: 'Nova home screen and search bar regression',
      debug: true,
      networkLogs: true,
      deviceLogs: true,
    },
  },
});

const results = [];
const record = (name, passed, detail = '') => results.push({ name, passed, detail });
const byA11y = (label) => driver.$(`~${label}`);
const byText = (value) => driver.$(`android=new UiSelector().text("${value}")`);
const addressBar = byA11y('Nova address bar');

try {
  await driver.waitUntil(async () => (await byText('NOVA').isDisplayed().catch(() => false)), {
    timeout: 30000,
    timeoutMsg: 'Nova home screen did not appear after launch',
  });
  if (!(await addressBar.isDisplayed())) throw new Error('Accessible address bar not found on home screen');
  if (!(await byA11y('Home').isDisplayed())) throw new Error('Dedicated Home button is not visible beside the address bar');
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

  // Reproduce the case where the user presses Home while the address field is active.
  await addressBar.click();
  await addressBar.setValue('unfinished search');
  await byA11y('Home').click();
  await driver.waitUntil(async () => (await byText('NOVA').isDisplayed().catch(() => false)), {
    timeout: 15000,
    timeoutMsg: 'Home button did not return home from an active address field',
  });
  const homeFieldText = await addressBar.getAttribute('text').catch(() => '');
  if (homeFieldText.trim()) throw new Error('Home did not clear the unfinished address-bar text');
  if (await driver.isKeyboardShown()) throw new Error('Home left the on-screen keyboard open');
  await driver.saveScreenshot('test-results/03-home-dismisses-keyboard.png');
  record('Home clears address field and dismisses keyboard', true, 'Unfinished text cleared and keyboard hidden');

  await addressBar.click();
  await addressBar.setValue('https://example.com');
  await driver.execute('mobile: performEditorAction', { action: 'go' });
  await driver.waitUntil(async () => {
    const value = await addressBar.getAttribute('text').catch(() => '');
    return value.startsWith('https://example.com');
  }, { timeout: 20000, timeoutMsg: 'Keyboard Go action did not submit the typed URL' });
  await driver.saveScreenshot('test-results/04-keyboard-navigation.png');
  record('Keyboard Go submits URL', true, 'Typed URL was accepted through the Android editor action');
} catch (error) {
  record('Regression run', false, error?.stack || String(error));
  try { await driver.saveScreenshot('test-results/failure.png'); } catch {}
  try { await writeFile('test-results/failure-page-source.xml', await driver.getPageSource()); } catch {}
  throw error;
} finally {
  await writeFile('test-results/results.json', JSON.stringify(results, null, 2));
  try { await writeFile('test-results/browserstack-session.txt', await driver.sessionId); } catch {}
  await driver.deleteSession();
}

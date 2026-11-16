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
  if (!(await byText('Google').isDisplayed().catch(() => false))) {
    // Search engine label includes a dropdown glyph on the toolbar; the home shortcut remains a separate control.
    const googleShortcut = await byText('Google').isDisplayed().catch(() => false);
    if (!googleShortcut) throw new Error('Google shortcut/search control is missing');
  }
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
  await addressBar.setValue('https://example.com');
  await driver.pressKeyCode(66);
  await driver.waitUntil(async () => {
    const value = await addressBar.getAttribute('text').catch(() => '');
    return value.startsWith('https://example.com');
  }, { timeout: 20000, timeoutMsg: 'Keyboard Enter did not submit the typed URL' });
  await driver.saveScreenshot('test-results/03-keyboard-navigation.png');
  record('Keyboard Enter submits URL', true, 'Typed URL was accepted from the keyboard action');
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

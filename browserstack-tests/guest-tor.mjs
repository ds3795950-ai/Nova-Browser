import { mkdir, writeFile } from 'node:fs/promises';
import { remote } from 'webdriverio';

for (const name of ['BROWSERSTACK_USERNAME', 'BROWSERSTACK_ACCESS_KEY', 'BROWSERSTACK_APP_URL']) {
  if (!process.env[name]) throw new Error(`Missing required environment variable: ${name}`);
}

const out = 'test-results/guest-tor';
await mkdir(out, { recursive: true });
let driver;
let failed = false;
const results = [];
const record = (name, ok, detail = '') => {
  results.push({ name, status: ok ? 'passed' : 'failed', detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' — ' + detail : ''}`);
};

async function tapToolbar(label) {
  const escaped = label.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  for (const selector of [
    `android=new UiSelector().description("${escaped}")`,
    `android=new UiSelector().text("${escaped}")`
  ]) {
    try {
      const item = driver.$(selector);
      if (await item.isDisplayed()) { await item.click(); return; }
    } catch (_) {}
  }
  const rows = await driver.$$('android.widget.HorizontalScrollView');
  for (let i = 0; i < rows.length; i++) {
    for (const useDescription of [true, false]) {
      try {
        const match = useDescription
          ? `new UiSelector().description("${escaped}")`
          : `new UiSelector().text("${escaped}")`;
        const scroller = driver.$(`android=new UiScrollable(new UiSelector().scrollable(true).className("android.widget.HorizontalScrollView").instance(${i})).setAsHorizontalList().scrollIntoView(${match})`);
        await scroller.waitForDisplayed({ timeout: 4000 });
        const item = driver.$(useDescription
          ? `android=new UiSelector().description("${escaped}")`
          : `android=new UiSelector().text("${escaped}")`);
        await item.click();
        return;
      } catch (_) {}
    }
  }
  throw new Error(`Could not find toolbar control: ${label}`);
}

async function navigateToTorCheck() {
  const address = driver.$('~Nova address bar');
  await address.waitForDisplayed({ timeout: 15000 });
  await address.click();
  await address.setValue('https://check.torproject.org/api/ip');
  await driver.$('~Go').click();
  await driver.waitUntil(async () => {
    const source = await driver.getPageSource().catch(() => '');
    return /"IsTor"\s*:\s*true/.test(source) || /IsTor.{0,80}true/.test(source) || /"IsTor"\s*:\s*false/.test(source) || /IsTor.{0,80}false/.test(source);
  }, { timeout: 90000, interval: 2000, timeoutMsg: 'Tor check response did not load in the browser' });
  const source = await driver.getPageSource();
  const match = source.match(/IsTor.{0,100}/);
  return { isTor: /"IsTor"\s*:\s*true/.test(source) || /IsTor.{0,80}true/.test(source), excerpt: match?.[0] || source.slice(-600) };
}

try {
  driver = await remote({
    protocol: 'https', hostname: 'hub-cloud.browserstack.com', port: 443, path: '/wd/hub',
    user: process.env.BROWSERSTACK_USERNAME, key: process.env.BROWSERSTACK_ACCESS_KEY,
    capabilities: {
      platformName: 'Android',
      'appium:automationName': 'UiAutomator2',
      'appium:deviceName': process.env.BROWSERSTACK_DEVICE_NAME || 'Google Pixel 8',
      'appium:platformVersion': process.env.BROWSERSTACK_OS_VERSION || '14.0',
      'appium:app': process.env.BROWSERSTACK_APP_URL,
      'appium:appPackage': 'com.nova.browser',
      'appium:appActivity': '.MainActivity',
      'appium:autoGrantPermissions': true,
      'appium:newCommandTimeout': 300,
      'bstack:options': {
        projectName: 'Nova Browser',
        buildName: `Nova Guest Tor ${process.env.GITHUB_RUN_NUMBER || 'manual'}`,
        sessionName: 'Guest Tor anonymity end-to-end',
        debug: true,
        networkLogs: true
      }
    }
  });

  await driver.$('android=new UiSelector().text("NOVA")').waitForDisplayed({ timeout: 30000 });
  await tapToolbar('Guest');
  const guestTab = driver.$('android=new UiSelector().text("Guest tab · temporary")');
  await guestTab.waitForDisplayed({ timeout: 240000 });
  record('Guest Mode waits for embedded Tor before opening', true);

  const guestCheck = await navigateToTorCheck();
  record('Guest browsing exits through Tor', guestCheck.isTor, guestCheck.excerpt);
  await driver.saveScreenshot(`${out}/guest-tor-check.png`);
  if (!guestCheck.isTor) throw new Error(`Guest Mode did not exit through Tor: ${guestCheck.excerpt}`);

  await tapToolbar('Tabs');
  const exitAction = driver.$('android=new UiSelector().text("Exit Guest")');
  await exitAction.waitForDisplayed({ timeout: 10000 });
  await exitAction.click();
  await driver.$('android=new UiSelector().textContains("Exiting Guest Mode")').waitForDisplayed({ timeout: 10000 });
  await driver.$('android=new UiSelector().text("Exit Guest")').click();
  await driver.pause(2000);

  const normalCheck = await navigateToTorCheck();
  record('Normal browsing is not routed through Guest Tor', !normalCheck.isTor, normalCheck.excerpt);
  await driver.saveScreenshot(`${out}/normal-tor-check.png`);
  if (normalCheck.isTor) throw new Error(`Normal browsing remained routed through Tor after Guest exit: ${normalCheck.excerpt}`);
} catch (error) {
  failed = true;
  console.error(error?.stack || error);
  if (driver) {
    try { await writeFile(`${out}/failure-page-source.xml`, await driver.getPageSource()); } catch {}
    try { await driver.saveScreenshot(`${out}/guest-tor-failure.png`); } catch {}
  }
} finally {
  if (driver) {
    try {
      await driver.execute('browserstack_executor: ' + JSON.stringify({
        action: 'setSessionStatus',
        arguments: { status: failed ? 'failed' : 'passed', reason: failed ? 'Guest Tor anonymity regression failed' : 'Guest Tor and normal-mode routing checks passed' }
      }));
    } catch {}
    try { await driver.deleteSession(); } catch {}
  }
  await writeFile(`${out}/guest-tor-results.json`, JSON.stringify({ results, failed }, null, 2));
}
if (failed || results.some(x => x.status === 'failed')) process.exit(1);

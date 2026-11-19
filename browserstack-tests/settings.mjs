import { mkdir, writeFile } from 'node:fs/promises';
import { remote } from 'webdriverio';

const required = ['BROWSERSTACK_USERNAME', 'BROWSERSTACK_ACCESS_KEY', 'BROWSERSTACK_APP_URL'];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing required environment variable: ${name}`);
}

const results = [];
const record = (name, ok, detail = '') => {
  results.push({ name, status: ok ? 'passed' : 'failed', detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}: ${name}${detail ? ' — ' + detail : ''}`);
};
const out = 'test-results/settings';
await mkdir(out, { recursive: true });

let driver;
let failed = false;
const byA11y = label => driver.$('~' + label);
async function tapToolbar(label, rowIndex) {
  const safeLabel = label.replace(/\\/g, '\\\\').replace(/"/g, '\\\"');
  const rows = await driver.$$('android.widget.HorizontalScrollView');
  const indices = [rowIndex];
  for (let i = 0; i < rows.length; i++) if (i !== rowIndex) indices.push(i);
  for (const i of indices) {
    for (const match of ['description', 'text']) {
      try {
        const selector = match === 'description'
          ? `android=new UiScrollable(new UiSelector().scrollable(true).className("android.widget.HorizontalScrollView").instance(${i})).setAsHorizontalList().scrollIntoView(new UiSelector().description("${safeLabel}"))`
          : `android=new UiScrollable(new UiSelector().scrollable(true).className("android.widget.HorizontalScrollView").instance(${i})).setAsHorizontalList().scrollIntoView(new UiSelector().text("${safeLabel}"))`;
        const target = driver.$(selector);
        await target.waitForDisplayed({ timeout: 3000 });
        await target.click();
        return;
      } catch (_) {}
    }
  }
  throw new Error('Could not expose toolbar button: ' + label);
}


async function scrollToSetting(label, startsWith = false) {
  const selector = startsWith ? `new UiSelector().textStartsWith("${label}")` : `new UiSelector().text("${label}")`;
  const element = driver.$(`android=new UiScrollable(new UiSelector().scrollable(true)).setAsVerticalList().scrollIntoView(${selector})`);
  await element.waitForDisplayed({ timeout: 8000 });
  return element;
}

try {
  driver = await remote({
    protocol: 'https',
    hostname: 'hub-cloud.browserstack.com',
    port: 443,
    path: '/wd/hub',
    user: process.env.BROWSERSTACK_USERNAME,
    key: process.env.BROWSERSTACK_ACCESS_KEY,
    capabilities: {
      platformName: 'Android',
      'appium:automationName': 'UiAutomator2',
      'appium:deviceName': process.env.BROWSERSTACK_DEVICE_NAME || 'Google Pixel 8',
      'appium:platformVersion': process.env.BROWSERSTACK_OS_VERSION || '14.0',
      'appium:app': process.env.BROWSERSTACK_APP_URL,
      'appium:appPackage': 'com.nova.browser',
      'appium:appActivity': '.MainActivity',
      'appium:autoGrantPermissions': true,
      'appium:newCommandTimeout': 180,
      'bstack:options': {
        projectName: 'Nova Browser',
        buildName: `Nova Settings ${process.env.GITHUB_RUN_NUMBER || 'manual'}`,
        sessionName: 'VPN and Firewall settings regression',
        debug: true,
        networkLogs: true
      }
    }
  });

  const homeTitle = driver.$('android=new UiSelector().text("NOVA")');
  await homeTitle.waitForDisplayed({ timeout: 30000 });
  await tapToolbar('Settings', 1);
  await driver.pause(500);
  const menuEntries = [
    ['Choose search engine', false], ['Toggle home shortcuts', true], ['Clear browsing history', false],
    ['Close private and Guest tabs', false], ['Exit Guest Mode', false], ['Exit Reader view', false],
    ['Nova Shield', false], ['AI assistants', false], ['Extensions', false], ['Password manager', false],
    ['Site permissions', false], ['Accessibility', false], ['Export history', false], ['About Nova', false],
    ['Account & encrypted cloud sync', false], ['Protected DNS · AdGuard DNS', false], ['VPN & Firewall · 1VPN', false]
  ];
  for (const [label, startsWith] of menuEntries) {
    await scrollToSetting(label, startsWith);
    record('Settings menu entry available: ' + label, true);
  }
  await driver.saveScreenshot(`${out}/settings-menu.png`);
  const vpnMenuEntry = await scrollToSetting('VPN & Firewall · 1VPN');
  await vpnMenuEntry.click();

  const heading = await driver.$('android=new UiSelector().text("Nova VPN & Firewall")');
  await heading.waitForDisplayed({ timeout: 20000 });
  const headingVisible = await heading.isDisplayed().catch(() => false);
  record('VPN and firewall settings screen opens from Nova Settings', headingVisible, headingVisible ? '' : 'Expected Nova VPN & Firewall heading');
  if (!headingVisible) throw new Error('VPN and firewall settings screen did not open from Nova Settings');

  const vpn = await driver.$('android=new UiSelector().text("Enable 1VPN for Nova")');
  const firewall = await driver.$('android=new UiSelector().text("Enable browser firewall")');
  const vpnVisible = await vpn.isDisplayed().catch(() => false);
  const firewallVisible = await firewall.isDisplayed().catch(() => false);
  record('1VPN toggle is visible', vpnVisible);
  record('Firewall toggle is visible', firewallVisible);
  if (!vpnVisible || !firewallVisible) throw new Error('One or more settings toggles are missing');

  const checked = async el => (await el.getAttribute('checked')) === 'true';
  const originalVpn = await checked(vpn);
  const originalFirewall = await checked(firewall);

  await vpn.click();
  await driver.pause(500);
  const vpnChanged = (await checked(vpn)) !== originalVpn;
  const firewallUnaffectedByVpn = (await checked(firewall)) === originalFirewall;
  record('VPN toggle changes its own state', vpnChanged);
  record('VPN and firewall toggles are independent', firewallUnaffectedByVpn);
  if (!vpnChanged || !firewallUnaffectedByVpn) throw new Error('VPN toggle state/independence check failed');
  await vpn.click();
  await driver.pause(400);

  await firewall.click();
  await driver.pause(500);
  const firewallChanged = (await checked(firewall)) !== originalFirewall;
  const vpnUnaffectedByFirewall = (await checked(vpn)) === originalVpn;
  record('Firewall toggle changes its own state', firewallChanged);
  record('Firewall toggle does not change VPN state', vpnUnaffectedByFirewall);
  if (!firewallChanged || !vpnUnaffectedByFirewall) throw new Error('Firewall toggle state/independence check failed');
  await firewall.click();
  await driver.pause(400);

  const serverButton = await driver.$('android=new UiSelector().className("android.widget.Button").textStartsWith("Server:")');
  const serverVisible = await serverButton.isDisplayed().catch(() => false);
  record('Server selector is visible', serverVisible);
  if (!serverVisible) throw new Error('Server selector is missing');

  for (const city of ['Singapore', 'Amsterdam', 'Los Angeles']) {
    await serverButton.click();
    const chooser = await driver.$('android=new UiSelector().text("Choose 1VPN free server")');
    await chooser.waitForDisplayed({ timeout: 8000 });
    const option = await driver.$(`android=new UiSelector().textContains("${city}")`);
    await option.waitForDisplayed({ timeout: 8000 });
    await option.click();
    await driver.pause(400);
    const selectedText = await serverButton.getText();
    const selected = selectedText.includes(city);
    record(`Select ${city} free server`, selected, selected ? '' : `Displayed: ${selectedText}`);
    if (!selected) throw new Error(`Server selection did not update to ${city}`);
  }

  const finalVpn = await checked(vpn);
  const finalFirewall = await checked(firewall);
  await driver.terminateApp('com.nova.browser');
  await driver.activateApp('com.nova.browser');
  await driver.pause(1500);
  const homeAfterRestart = driver.$('android=new UiSelector().text("NOVA")');
  await homeAfterRestart.waitForDisplayed({ timeout: 30000 });
  await tapToolbar('Settings', 1);
  const vpnMenuAfterRestart = await scrollToSetting('VPN & Firewall · 1VPN');
  await vpnMenuAfterRestart.click();
  await driver.pause(1200);
  const vpnAfterRestart = await driver.$('android=new UiSelector().text("Enable 1VPN for Nova")');
  const firewallAfterRestart = await driver.$('android=new UiSelector().text("Enable browser firewall")');
  record('VPN preference persists after app restart', (await checked(vpnAfterRestart)) === finalVpn);
  record('Firewall preference persists after app restart', (await checked(firewallAfterRestart)) === finalFirewall);
  const serverAfterRestart = await driver.$('android=new UiSelector().className("android.widget.Button").textStartsWith("Server:")');
  record('Selected free server persists after app restart', (await serverAfterRestart.getText()).includes('Los Angeles'));

  await driver.saveScreenshot(`${out}/settings-final.png`);
} catch (error) {
  failed = true;
  console.error(error?.stack || error);
  if (driver) {
    try { await writeFile(`${out}/failure-page-source.xml`, await driver.getPageSource()); } catch {}
    try { await driver.saveScreenshot(`${out}/settings-failure.png`); } catch {}
  }
} finally {
  if (driver) {
    try {
      await driver.execute('browserstack_executor: ' + JSON.stringify({
        action: 'setSessionStatus',
        arguments: {
          status: failed || results.some(x => x.status === 'failed') ? 'failed' : 'passed',
          reason: failed ? 'Settings regression suite encountered an error' : 'Settings regression suite completed'
        }
      }));
    } catch {}
    try { await driver.deleteSession(); } catch {}
  }
  await writeFile(`${out}/settings-results.json`, JSON.stringify({ results, failed }, null, 2));
}

if (failed || results.some(x => x.status === 'failed')) process.exit(1);

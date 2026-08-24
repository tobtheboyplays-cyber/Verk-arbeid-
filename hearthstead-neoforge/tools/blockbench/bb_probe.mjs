// Smoke probe: boot the locally-served Blockbench web app under headless
// Chromium and prove the real engine is up (version, project creation,
// a rendered viewport screenshot).
import { chromium } from 'playwright-core';

const URL = process.env.BB_URL || 'http://127.0.0.1:8901/index.html';
const SHOT = process.argv[2] || '/tmp/bb_probe.png';

const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell',
    args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-webgl'],
});
try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
    page.on('pageerror', e => console.error('[pageerror]', e.message));
    await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForFunction(() => globalThis.Blockbench && Blockbench.version, null,
        { timeout: 60000 });
    const info = await page.evaluate(() => ({
        version: Blockbench.version,
        formats: Object.keys(Formats || {}),
    }));
    console.log('Blockbench version:', info.version);
    console.log('formats:', info.formats.join(', '));
    // Dismiss any start screen and make a new Modded Entity/free model project.
    const made = await page.evaluate(() => {
        const fmt = Formats.modded_entity || Formats.free || Object.values(Formats)[0];
        newProject(fmt);
        return Project ? (Project.format ? Project.format.id : 'no-format') : 'no-project';
    });
    console.log('newProject ->', made);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: SHOT });
    console.log('screenshot ->', SHOT);
} finally {
    await browser.close();
}

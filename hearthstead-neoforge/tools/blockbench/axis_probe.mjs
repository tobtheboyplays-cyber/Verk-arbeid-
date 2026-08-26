// Axis-behaviour probe: pose right_arm directly (no animation clip) at a
// battery of (x,y,z) combos and screenshot each, to empirically find which
// local axis of THIS rig actually produces a horizontal side-sweep once the
// arm is raised, instead of guessing from Euler-order theory.
import { chromium } from 'playwright-core';
import * as fs from 'node:fs';
import * as path from 'node:path';

const URL = 'http://127.0.0.1:8901/index.html';
const MODEL = '/home/user/Verk-arbeid-/hearthstead-neoforge/tools/blockbench/settler.bbmodel';
const outDir = process.argv[2];
fs.mkdirSync(outDir, { recursive: true });
const modelJson = fs.readFileSync(MODEL, 'utf8');

const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell',
    args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-webgl'],
});
try {
    const page = await browser.newPage({ viewport: { width: 900, height: 800 } });
    page.on('pageerror', e => console.error('[pageerror]', e.message));
    await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForFunction(() => globalThis.Blockbench && Blockbench.version, null, { timeout: 60000 });
    await page.evaluate((json) => {
        const data = JSON.parse(json);
        Codecs.project.load(data, { path: 'settler.bbmodel', name: 'settler.bbmodel' });
    }, modelJson);
    await page.evaluate(() => {
        const p = Preview.selected;
        p.camera.position.set(40, 32, 55);
        p.controls.target.set(0, 14, 0);
        p.controls.update();
        Modes.options.edit.select();
    });
    await page.waitForTimeout(500);

    const combos = JSON.parse(process.argv[3]);
    for (const [name, x, y, z] of combos) {
        await page.evaluate(([x, y, z]) => {
            const g = Group.all.find(g => g.name === 'right_arm');
            g.rotation = [x, y, z];
            TickUpdates.outliner = true;
            Canvas.updateAllPositions();
            Preview.selected.render();
        }, [x, y, z]);
        await page.waitForTimeout(200);
        await page.screenshot({ path: path.join(outDir, `probe-${name}.png`) });
        console.log('wrote', name);
    }
} finally {
    await browser.close();
}

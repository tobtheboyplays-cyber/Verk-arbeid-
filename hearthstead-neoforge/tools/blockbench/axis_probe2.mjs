// Proper axis probe: reuse the REAL animation/keyframe pipeline (same path
// bb_render.mjs uses), just mutate the CHOP right_arm keyframe at t=0.55 in
// place for each combo and re-preview, in one browser session.
import { chromium } from 'playwright-core';
import * as fs from 'node:fs';
import * as path from 'node:path';

const URL = 'http://127.0.0.1:8901/index.html';
const MODEL = '/home/user/Verk-arbeid-/hearthstead-neoforge/tools/blockbench/settler.bbmodel';
const outDir = process.argv[2];
const combos = JSON.parse(process.argv[3]);
fs.mkdirSync(outDir, { recursive: true });
const modelJson = fs.readFileSync(MODEL, 'utf8');

const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell',
    args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-webgl'],
});
try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
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
        Modes.options.animate.select();
        const anim = Animation.all.find(a => a.name.toLowerCase().includes('chop'));
        anim.select();
    });
    await page.waitForTimeout(400);

    for (const [name, x, y, z] of combos) {
        await page.evaluate(([x, y, z]) => {
            const anim = Animation.all.find(a => a.name.toLowerCase().includes('chop'));
            const animator = Object.values(anim.animators).find(a => a.name === 'right_arm');
            const kf = animator.keyframes.find(k => Math.abs(k.time - 0.55) < 1e-6);
            kf.data_points[0].x = x;
            kf.data_points[0].y = y;
            kf.data_points[0].z = z;
            Timeline.setTime(0.55);
            Animator.preview();
        }, [x, y, z]);
        await page.waitForTimeout(200);
        await page.screenshot({ path: path.join(outDir, `p2-${name}.png`) });
        console.log('wrote', name);
    }
} finally {
    await browser.close();
}

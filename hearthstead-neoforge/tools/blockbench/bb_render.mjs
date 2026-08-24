// Render the exported settler.bbmodel through the REAL Blockbench engine,
// headless. Produces viewport screenshots of the model, optionally posed at
// specific times of specific animation clips -- the "see and adjust" half of
// the visual quality loop.
//
// Usage:
//   node bb_render.mjs out_dir                       # static model, 2 angles
//   node bb_render.mjs out_dir CLIP t0 [t1 t2 ...]   # posed frames of a clip
// Example:
//   node bb_render.mjs /tmp/bb walk 0 0.25 0.5 0.75
//
// Requires the Blockbench web build served locally (see README.md):
//   cd /home/user/jannisx11/blockbench && python3 -m http.server 8901
import { chromium } from 'playwright-core';
import * as fs from 'node:fs';
import * as path from 'node:path';

const URL = process.env.BB_URL || 'http://127.0.0.1:8901/index.html';
const MODEL = process.env.BB_MODEL ||
    new globalThis.URL('./settler.bbmodel', import.meta.url).pathname;
const outDir = process.argv[2] || '/tmp/bb_render';
const clip = process.argv[3];
const times = process.argv.slice(4).map(Number);

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
    await page.waitForFunction(() => globalThis.Blockbench && Blockbench.version,
        null, { timeout: 60000 });

    const loaded = await page.evaluate((json) => {
        const data = JSON.parse(json);
        Codecs.project.load(data, { path: 'settler.bbmodel', name: 'settler.bbmodel' });
        return {
            cubes: Cube.all.length,
            groups: Group.all.length,
            animations: Animation.all.map(a => a.name),
        };
    }, modelJson);
    console.log(`loaded: ${loaded.cubes} cubes, ${loaded.groups} groups, ` +
        `${loaded.animations.length} animations`);

    // Frame the model nicely from a front-three-quarter view.
    await page.evaluate(() => {
        const p = Preview.selected;
        p.camera.position.set(40, 32, 55);
        p.controls.target.set(0, 14, 0);
        p.controls.update();
    });
    await page.waitForTimeout(800);

    if (!clip) {
        await page.screenshot({ path: path.join(outDir, 'model-front34.png') });
        await page.evaluate(() => {
            const p = Preview.selected;
            p.camera.position.set(-45, 30, -50);
            p.controls.target.set(0, 14, 0);
            p.controls.update();
        });
        await page.waitForTimeout(400);
        await page.screenshot({ path: path.join(outDir, 'model-back34.png') });
        console.log('wrote model-front34.png, model-back34.png');
    } else {
        const found = await page.evaluate((clipName) => {
            const anim = Animation.all.find(a =>
                a.name.toLowerCase().includes(clipName.toLowerCase()));
            if (!anim) return null;
            Modes.options.animate.select();
            anim.select();
            return anim.name;
        }, clip);
        if (!found) {
            console.error(`clip not found: ${clip}`);
            console.error('available:', loaded.animations.join(', '));
            process.exit(2);
        }
        console.log('selected:', found);
        for (const t of times.length ? times : [0]) {
            await page.evaluate((time) => {
                Timeline.setTime(time);
                Animator.preview();
            }, t);
            await page.waitForTimeout(300);
            const name = `${clip.toLowerCase()}-t${String(t).replace('.', '_')}.png`;
            await page.screenshot({ path: path.join(outDir, name) });
            console.log('wrote', name);
        }
    }
} finally {
    await browser.close();
}

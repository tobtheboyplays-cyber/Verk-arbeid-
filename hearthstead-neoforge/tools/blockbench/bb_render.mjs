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
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

const URL = process.env.BB_URL || 'http://127.0.0.1:8901/index.html';
const MODEL = process.env.BB_MODEL ||
    fileURLToPath(new globalThis.URL('./settler.bbmodel', import.meta.url));
const PROP_CONTRACT = fileURLToPath(
    new globalThis.URL('./prop_contract.json', import.meta.url));
const outDir = path.resolve(process.argv[2] || 'bb_render');
const clip = process.argv[3];
const times = process.argv.slice(4).map(Number);
const propContract = JSON.parse(fs.readFileSync(PROP_CONTRACT, 'utf8'));
const context = String(process.env.BB_CONTEXT || '').trim().toLowerCase();

function canonicalClipName(name) {
    return String(name || '').split('.').at(-1).trim().toUpperCase();
}

function contractedProp(clipName) {
    if (!clipName) return 'none';
    const canonical = canonicalClipName(clipName);
    if (propContract.clips[canonical]) return propContract.clips[canonical];
    const contextual = propContract.contextualClips?.[canonical];
    if (!contextual) return 'none';
    const choices = Object.keys(contextual);
    if (!context) {
        throw new Error(`${canonical} has context-dependent runtime equipment; set ` +
            `BB_CONTEXT to one of: ${choices.join(', ')}`);
    }
    if (!Object.hasOwn(contextual, context)) {
        throw new Error(`Unsupported BB_CONTEXT ${JSON.stringify(context)} for ${canonical}; ` +
            `expected one of: ${choices.join(', ')}`);
    }
    return contextual[context];
}

const inferredProp = contractedProp(clip);
const propOverride = String(process.env.BB_PROP || '').trim().toLowerCase();
const prop = propOverride || inferredProp;
const contractWasOverridden = Boolean(propOverride && propOverride !== inferredProp);
const contextualProps = Object.values(propContract.contextualClips || {})
    .flatMap(contexts => Object.values(contexts));
const supportedProps = new Set([
    'none', ...Object.values(propContract.clips), ...contextualProps,
]);
const view = String(process.env.BB_VIEW || 'front34').trim().toLowerCase();
const supportedViews = new Set(['front34', 'back34', 'left', 'right']);
if (!supportedProps.has(prop)) {
    throw new Error(`Unsupported BB_PROP ${JSON.stringify(prop)}; expected one of: ` +
        [...supportedProps].sort().join(', '));
}
if (!supportedViews.has(view)) {
    throw new Error(`Unsupported BB_VIEW ${JSON.stringify(view)}; expected one of: ` +
        [...supportedViews].sort().join(', '));
}
if (times.some(time => !Number.isFinite(time) || time < 0)) {
    throw new Error('Frame times must be finite, non-negative seconds.');
}

function playwrightBrowserCandidates(root) {
    if (!root || !fs.existsSync(root)) return [];
    const candidates = [];
    let entries;
    try {
        entries = fs.readdirSync(root, { withFileTypes: true });
    } catch {
        return [];
    }
    // Prefer the newest cached browser revision. readdir order is not a
    // portability contract and an older incompatible cache can coexist with
    // the current Playwright revision.
    entries.sort((left, right) => right.name.localeCompare(
        left.name, undefined, { numeric: true }));
    for (const entry of entries) {
        if (!entry.isDirectory() || !/^chromium/i.test(entry.name)) continue;
        const base = path.join(root, entry.name);
        candidates.push(
            path.join(base, 'chrome-headless-shell-win64', 'chrome-headless-shell.exe'),
            path.join(base, 'chrome-win64', 'chrome.exe'),
            path.join(base, 'chrome-win', 'headless_shell.exe'),
            path.join(base, 'chrome-win', 'chrome.exe'),
            path.join(base, 'chrome-headless-shell-linux64', 'chrome-headless-shell'),
            path.join(base, 'chrome-linux64', 'chrome'),
            path.join(base, 'chrome-linux', 'headless_shell'),
            path.join(base, 'chrome-linux', 'chrome'),
            path.join(base, 'chrome-headless-shell-mac-arm64', 'chrome-headless-shell'),
            path.join(base, 'chrome-headless-shell-mac-x64', 'chrome-headless-shell'),
            path.join(base, 'chrome-mac-arm64', 'Chromium.app', 'Contents', 'MacOS',
                'Chromium'),
            path.join(base, 'chrome-mac', 'Chromium.app', 'Contents', 'MacOS', 'Chromium')
        );
    }
    return candidates;
}

function findChromium() {
    const configured = String(process.env.BB_CHROMIUM || '').trim();
    if (configured) {
        if (!path.isAbsolute(configured)) {
            throw new Error('BB_CHROMIUM must be an absolute executable path.');
        }
        if (!fs.existsSync(configured)) {
            throw new Error(`BB_CHROMIUM does not exist: ${configured}`);
        }
        return configured;
    }
    const localAppData = process.env.LOCALAPPDATA;
    const candidates = [
        process.platform === 'win32' && process.env.PROGRAMFILES &&
            path.join(process.env.PROGRAMFILES, 'Google', 'Chrome', 'Application', 'chrome.exe'),
        process.platform === 'win32' && process.env.PROGRAMFILES &&
            path.join(process.env.PROGRAMFILES, 'Microsoft', 'Edge',
                'Application', 'msedge.exe'),
        process.platform === 'win32' && process.env['PROGRAMFILES(X86)'] &&
            path.join(process.env['PROGRAMFILES(X86)'], 'Microsoft', 'Edge',
                'Application', 'msedge.exe'),
        process.platform === 'win32' && process.env['PROGRAMFILES(X86)'] &&
            path.join(process.env['PROGRAMFILES(X86)'], 'Google', 'Chrome',
                'Application', 'chrome.exe'),
        process.platform === 'win32' && localAppData &&
            path.join(localAppData, 'Google', 'Chrome', 'Application', 'chrome.exe'),
        process.platform === 'win32' && localAppData &&
            path.join(localAppData, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
        process.platform === 'darwin' &&
            '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        process.platform === 'darwin' &&
            '/Applications/Chromium.app/Contents/MacOS/Chromium',
        process.platform === 'darwin' &&
            '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
        '/usr/bin/chromium',
        '/usr/bin/chromium-browser',
        '/usr/bin/google-chrome',
        '/usr/bin/google-chrome-stable',
        '/usr/bin/microsoft-edge',
        '/usr/bin/microsoft-edge-stable',
        '/snap/bin/chromium',
        ...playwrightBrowserCandidates(process.env.PLAYWRIGHT_BROWSERS_PATH),
        ...playwrightBrowserCandidates(localAppData && path.join(localAppData, 'ms-playwright')),
        ...playwrightBrowserCandidates(path.join(os.homedir(), '.cache', 'ms-playwright')),
        ...playwrightBrowserCandidates(path.join(
            os.homedir(), 'Library', 'Caches', 'ms-playwright')),
        ...playwrightBrowserCandidates('/opt/pw-browsers'),
    ].filter(Boolean);
    const found = candidates.find(candidate => fs.existsSync(candidate));
    if (!found) {
        throw new Error('No Chromium browser found. Set BB_CHROMIUM to Chrome, Edge, or Chromium.');
    }
    return found;
}

fs.mkdirSync(outDir, { recursive: true });
if (contractWasOverridden) {
    console.warn(`CONTRACT OVERRIDE: ${canonicalClipName(clip)} normally resolves to ` +
        `${inferredProp}, but BB_PROP selected ${prop}; this render is not K1 approval evidence.`);
}
const modelJson = fs.readFileSync(MODEL, 'utf8');

const chromiumExecutable = findChromium();
console.log('browser:', chromiumExecutable);
const browser = await chromium.launch({
    executablePath: chromiumExecutable,
    headless: true,
    args: [
        ...(process.platform === 'linux' ? ['--no-sandbox'] : []),
        '--use-angle=swiftshader',
        '--enable-unsafe-swiftshader',
        '--enable-webgl',
    ],
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

    // K1: Blockbench does not run Minecraft's ItemInHandLayer. Rebuild its
    // complete right-hand transform hierarchy before adding a deliberately
    // simple item silhouette. The sequence comes from the official 1.21.1
    // client (see prop_contract.json):
    //
    //   translateToHand -> rotate X -90 -> rotate Y 180 -> T(1,2,-10)/16
    //   -> the prop's vanilla thirdperson_righthand display transform
    //   -> item-space centring
    //
    // A direct child at the hand cube's centre is NOT equivalent: the layer
    // origin is [7,12,-2] in the neutral Blockbench rig, not [6,12,0].
    const attachedProp = await page.evaluate(({ kind, contract }) => {
        if (kind === 'none') return null;
        const arm = Group.all.find(group => group.name === 'right_arm');
        if (!arm) throw new Error('right_arm group not found');

        const bb = contract.blockbench;
        const layer = contract.itemInHandLayer;
        const displayProfile = contract.propDisplayProfiles[kind];
        const display = contract.displayProfiles[displayProfile];
        const expectedDisplay = bb.expectedNeutralDisplays[displayProfile];
        if (!displayProfile || !display || !expectedDisplay) {
            throw new Error(`no pinned display profile for K1 prop ${kind}`);
        }
        const axes = { x: 0, y: 1, z: 2 };
        const add = (left, right) => left.map((value, index) => value + right[index]);
        const mapVector = vector => vector.map(
            (value, index) => value * bb.modelToBlockbenchAxisSigns[index]);
        const almostEqual = (left, right, epsilon = 1e-5) =>
            left.length === right.length &&
            left.every((value, index) => Math.abs(value - right[index]) <= epsilon);
        const makeGroup = (name, parent, origin, rotation = [0, 0, 0]) =>
            new Group({ name, origin, rotation, export: false })
                .addTo(parent).init();

        if (!almostEqual(arm.origin, bb.rightArmOrigin)) {
            throw new Error(`right_arm origin drifted: ${JSON.stringify(arm.origin)}; ` +
                `contract expects ${JSON.stringify(bb.rightArmOrigin)}`);
        }

        let origin = [...arm.origin];
        let parent = arm;
        for (const [index, step] of layer.rotateSequence.entries()) {
            const axis = axes[step.axis];
            if (axis === undefined) throw new Error(`invalid layer rotation axis: ${step.axis}`);
            const rotation = [0, 0, 0];
            rotation[axis] = step.degrees * bb.modelToBlockbenchAxisSigns[axis];
            parent = makeGroup(`k1_layer_r${index}_${step.axis}`, parent, origin, rotation);
        }
        origin = add(origin, mapVector(layer.translateModelPixels));
        const layerTranslation = makeGroup('k1_layer_translate', parent, origin);
        parent = layerTranslation;

        origin = add(origin, mapVector(display.translateModelPixels));
        const displayTranslation = makeGroup('k1_item_display_translate', parent, origin);
        parent = displayTranslation;
        for (const [axisName, axis] of Object.entries(axes)) {
            const degrees = display.rotationXYZDegrees[axis];
            const rotation = [0, 0, 0];
            rotation[axis] = degrees * bb.modelToBlockbenchAxisSigns[axis];
            parent = makeGroup(`k1_item_display_r${axisName}`, parent, origin, rotation);
        }
        const displayRotation = parent;

        const proxy = makeGroup(`k1_vanilla_hand_${kind}`, parent, origin);

        const materials = {
            wood: new THREE.MeshLambertMaterial({ color: '#b96f2d' }),
            metal: new THREE.MeshLambertMaterial({ color: '#e2edf0' }),
            iron: new THREE.MeshLambertMaterial({ color: '#8fa2aa' }),
        };
        const proxyCubes = [];

        const itemPoint = point => mapVector(point.map((value, index) =>
            (value + display.centerModelPixels[index]) * display.scale[index]));
        // Scale and the vanilla -0.5 item-space centring are baked into the
        // proxy vertices because Blockbench's modded-entity groups do not
        // expose Minecraft's arbitrary pose-stack scale as model data. Keep a
        // real assertion on that vertex path; origin+basis alone would still
        // pass if a future edit accidentally dropped display.scale.
        const itemCenter = itemPoint([8, 8, 8]);
        const localScaleBasis = [
            itemPoint([9, 8, 8]),
            itemPoint([8, 9, 8]),
            itemPoint([8, 8, 9]),
        ].map(point => point.map((value, index) => value - itemCenter[index]));
        const expectedLocalScaleBasis = [
            [bb.modelToBlockbenchAxisSigns[0] * display.scale[0], 0, 0],
            [0, bb.modelToBlockbenchAxisSigns[1] * display.scale[1], 0],
            [0, 0, bb.modelToBlockbenchAxisSigns[2] * display.scale[2]],
        ];
        if (!almostEqual(itemCenter, [0, 0, 0]) ||
                !localScaleBasis.every((row, index) =>
                    almostEqual(row, expectedLocalScaleBasis[index]))) {
            throw new Error(`${displayProfile} item centre/scale mismatch: centre ` +
                `${JSON.stringify(itemCenter)}, basis ${JSON.stringify(localScaleBasis)}`);
        }
        const addBox = (name, from, to, material) => {
            const first = itemPoint(from);
            const second = itemPoint(to);
            const cube = new Cube({
                name: `k1_${kind}_${name}`,
                from: add(origin, first.map((value, index) =>
                    Math.min(value, second[index]))),
                to: add(origin, first.map((value, index) =>
                    Math.max(value, second[index]))),
                origin,
                color: 0,
                autouv: 0,
                uv_offset: [0, 0],
            });
            cube.addTo(proxy).init();
            proxyCubes.push([cube, material]);
        };
        const addSprite = rows => {
            const materialByCode = { w: 'wood', i: 'iron', m: 'metal' };
            if (rows.length !== 16 || rows.some(row => row.length !== 16)) {
                throw new Error(`K1 ${kind} proxy must be a 16x16 sprite`);
            }
            rows.forEach((row, textureY) => {
                let start = 0;
                while (start < row.length) {
                    const code = row[start];
                    if (code === '.') {
                        start++;
                        continue;
                    }
                    let end = start + 1;
                    while (end < row.length && row[end] === code) end++;
                    // Texture row zero is the top of the generated item;
                    // vanilla model Y zero is the bottom.
                    addBox(`row${textureY}_${start}`, [start, 15 - textureY, 7.25],
                        [end, 16 - textureY, 8.75], materialByCode[code]);
                    start = end;
                }
            });
        };

        // Shapes use vanilla item-model coordinates (0..16, centred by the
        // renderer). They are intentionally low-detail proxy silhouettes;
        // placement and orientation are faithful to the pinned vanilla display
        // profile (handheld, generated, handheld_rod or bow), but pixels,
        // texture and state-dependent model overrides remain the live
        // renderer's responsibility.
        if (kind === 'axe') {
            addSprite([
                '................', '.........mm.....', '........mmmm....',
                '.......mmmmm....', '......mmmmmmm...', '......mmmmmmm...',
                '.......mmmmmmm..', '........mmmmmm..', '.......www.mm...',
                '......www.......', '.....www........', '....www.........',
                '...www..........', '..www...........', '..ww............',
                '................',
            ]);
        } else if (kind === 'hammer') {
            addBox('haft', [7.2, 0.5, 7.3], [8.8, 12.5, 8.7], 'wood');
            addBox('head', [3.2, 11.0, 6.7], [12.8, 15.2, 9.3], 'iron');
        } else if (kind === 'pickaxe') {
            addSprite([
                '................', '................', '......mmmmm.....',
                '.....mmmmmmmmm..', '......mmmmmmmm..', '..........mmmm..',
                '.........wmmmmm.', '........www.mmm.', '.......www..mmm.',
                '......www...mmm.', '.....www....mmm.', '....www......m..',
                '...www..........', '..www...........', '..ww............',
                '................',
            ]);
        } else if (kind === 'hoe') {
            addSprite([
                '................', '.......mmm......', '......mmmmm.....',
                '.......mmmmmmm..', '.........mmmmm..', '..........mmmm..',
                '.........www....', '........www.....', '.......www......',
                '......www.......', '.....www........', '....www.........',
                '...www..........', '..www...........', '..ww............',
                '................',
            ]);
        } else if (kind === 'sword') {
            addSprite([
                '.............mmm', '............mmmm', '...........mmmmm',
                '..........mmmmm.', '.........mmmmm..', '........mmmmm...',
                '..ii...mmmmm....', '..iii.mmmmm.....', '...iimmmmm......',
                '...iimmmm.......', '....mmmm........', '...iiiiii.......',
                '..iii.iiii......', 'iiii....ww......', 'www.............',
                'www.............',
            ]);
        } else if (kind === 'bow') {
            addSprite([
                '................', '...........wwww.', '........wwwwwwww',
                '......wwwwwwwww.', '.....wwwwww..w..', '....wwww....w...',
                '...wwww....w....', '...www....w.....', '..www....w......',
                '..www...w.......', '..www..w........', '.www..w.........',
                '.www.w..........', '.wwww...........', '.www............',
                '..w.............',
            ]);
        } else if (kind === 'shears') {
            addSprite([
                '................', '................', '........iiiii...',
                '.......iiiii.i..', '......iiiii.ii..', '.....iiiii.iii..',
                '....iiiii.iiii..', '....iii..iiiii..', '...iii...iiii...',
                '...iii..iiii....', '...iiiiiiii.....', '..i..iiiii......',
                '..i..iii........', '...ii...........', '................',
                '................',
            ]);
        } else if (kind === 'fishing_rod') {
            addSprite([
                '................', '.............ww.', '............www.',
                '...........wwww.', '..........wwwww.', '.........wwww.w.',
                '........wwww..w.', '.......wwww...w.', '......wwww....w.',
                '.....wwww.....w.', '.....www.....w..', '....wwwww....w..',
                '...wwwwww...w...', '..www.ww..w.w...', '..ww......ww....',
                '................',
            ]);
        } else {
            throw new Error(`unknown BB_PROP: ${kind}`);
        }
        Canvas.updateAll();
        // Modded-entity projects are single-texture projects. Adding proxy
        // textures makes Blockbench silently sample the settler sheet and
        // turns iron tools brown. Override only these transient viewport
        // meshes after the normal geometry update instead.
        for (const [cube, material] of proxyCubes) {
            cube.mesh.material = materials[material];
        }
        Canvas.scene.updateMatrixWorld(true);

        const worldPosition = group =>
            group.scene_object.getWorldPosition(new THREE.Vector3()).toArray();
        const layerOrigin = worldPosition(layerTranslation);
        const displayOrigin = worldPosition(displayTranslation);
        const displayMatrix = displayRotation.scene_object.matrixWorld.elements;
        // THREE stores matrices column-major. Publish row-major values so the
        // contract is directly comparable with anim_check.py's independent
        // matrix multiplication.
        const displayBasisRows = [
            [displayMatrix[0], displayMatrix[4], displayMatrix[8]],
            [displayMatrix[1], displayMatrix[5], displayMatrix[9]],
            [displayMatrix[2], displayMatrix[6], displayMatrix[10]],
        ];
        if (!almostEqual(layerOrigin, bb.expectedNeutralLayerOrigin)) {
            throw new Error(`ItemInHandLayer matrix mismatch: got ` +
                `${JSON.stringify(layerOrigin)}, expected ` +
                `${JSON.stringify(bb.expectedNeutralLayerOrigin)}`);
        }
        if (!almostEqual(displayOrigin, expectedDisplay.origin)) {
            throw new Error(`${displayProfile} display origin mismatch: got ` +
                `${JSON.stringify(displayOrigin)}, expected ` +
                `${JSON.stringify(expectedDisplay.origin)}`);
        }
        if (!displayBasisRows.every((row, index) =>
            almostEqual(row, expectedDisplay.basisRows[index]))) {
            throw new Error(`${displayProfile} display basis mismatch: got ` +
                `${JSON.stringify(displayBasisRows)}, expected ` +
                `${JSON.stringify(expectedDisplay.basisRows)}`);
        }
        return {
            kind,
            group: proxy.name,
            cubes: proxy.children.map(child => child.name),
            layerOrigin,
            displayOrigin,
            displayBasisRows,
            localScaleBasis,
            displayProfile,
            itemInHandLayer: layer,
            itemDisplay: display,
        };
    }, { kind: prop, contract: propContract });
    if (attachedProp) {
        console.log(`prop: ${attachedProp.kind} on ${attachedProp.group} ` +
            `(${attachedProp.cubes.join(', ')})`);
        console.log('verified neutral origins:', JSON.stringify({
            layer: attachedProp.layerOrigin,
            display: attachedProp.displayOrigin,
        }));
        console.log('verified neutral display basis:',
            JSON.stringify(attachedProp.displayBasisRows));
        console.log('verified local item scale basis:',
            JSON.stringify(attachedProp.localScaleBasis));
        console.log('ItemInHandLayer:', JSON.stringify(attachedProp.itemInHandLayer));
        console.log(`item display (${attachedProp.displayProfile}):`,
            JSON.stringify(attachedProp.itemDisplay));
    }

    // front34 is the standard whole-pose review. back34/left/right are evidence
    // views for held-item/hand contact when the torso occludes an impact frame.
    const cameraView = clip ? view : 'front34';
    await page.evaluate((viewName) => {
        const p = Preview.selected;
        const camera = viewName === 'right'
            ? { position: [68, 26, 0], target: [4, 15, 0] }
            : viewName === 'left'
                ? { position: [-68, 26, 0], target: [2, 15, 0] }
                : viewName === 'back34'
                    ? { position: [-45, 30, -50], target: [0, 14, 0] }
                    : { position: [40, 32, 55], target: [0, 14, 0] };
        p.camera.position.set(...camera.position);
        p.controls.target.set(...camera.target);
        p.controls.update();
    }, cameraView);
    console.log('view:', cameraView);
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
        const wantedClip = canonicalClipName(clip);
        const selected = await page.evaluate((clipName) => {
            const canonical = name => String(name || '').split('.').at(-1).toUpperCase();
            const anim = Animation.all.find(a => canonical(a.name) === clipName);
            if (!anim) return null;
            Modes.options.animate.select();
            anim.select();
            return { name: anim.name, length: anim.length };
        }, wantedClip);
        if (!selected) {
            throw new Error(`clip not found: ${clip}; available: ` +
                loaded.animations.join(', '));
        }
        const frameTimes = times.length ? times : [0];
        const pastEnd = frameTimes.find(time => time > selected.length + 1e-6);
        if (pastEnd !== undefined) {
            throw new Error(`frame time ${pastEnd}s exceeds ${wantedClip} length ` +
                `${selected.length}s; refusing a silently clamped duplicate frame`);
        }
        console.log(`selected: ${selected.name} (${selected.length}s)`);
        if (propContract.contextualClips?.[wantedClip]) {
            console.log(`runtime context: ${context} (contract prop: ${inferredProp})`);
        }
        const knownNoGo = propContract.knownVisualNoGoClips?.[wantedClip];
        if (knownNoGo) {
            console.warn(`VISUAL NO-GO ${wantedClip}: ${knownNoGo}`);
        }
        const clipSlug = wantedClip.toLowerCase().replace(/[^a-z0-9_-]+/g, '-');
        for (const t of frameTimes) {
            await page.evaluate((time) => {
                Timeline.setTime(time);
                Animator.preview();
            }, t);
            await page.waitForTimeout(300);
            const name = `${clipSlug}-t${String(t).replace('.', '_')}.png`;
            await page.screenshot({ path: path.join(outDir, name) });
            console.log('wrote', name);
        }
    }
} finally {
    await browser.close();
}

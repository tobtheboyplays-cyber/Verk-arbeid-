// Render the exported settler.bbmodel through the REAL Blockbench engine,
// headless. Produces viewport screenshots of the model, optionally posed at
// specific times of specific animation clips -- the "see and adjust" half of
// the visual quality loop.
//
// Usage:
//   node bb_render.mjs out_dir                       # static model, 2 angles
//   node bb_render.mjs out_dir CLIP t0 [t1 t2 ...]   # posed frames of a clip
//   node bb_render.mjs out_dir --composite BASE OVERLAY t0 [t1 ...]
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
const renderArgs = process.argv.slice(3);
const compositeMode = renderArgs[0] === '--composite';
if (compositeMode && renderArgs.length < 3) {
    throw new Error('Composite preview usage: --composite BASE_CLIP OVERLAY_CLIP [times...]');
}
const compositeBase = compositeMode ? renderArgs[1] : null;
const clip = compositeMode ? renderArgs[2] : renderArgs[0];
const times = (compositeMode ? renderArgs.slice(3) : renderArgs.slice(1)).map(Number);
const propContract = JSON.parse(fs.readFileSync(PROP_CONTRACT, 'utf8'));
const context = String(process.env.BB_CONTEXT || '').trim().toLowerCase();
const carryFill = Number(process.env.BB_CARRY_FILL || 0);
const limbSwingAmount = Number(process.env.BB_LIMB_SWING_AMOUNT || 0);
const compositeBasePhase = Number(process.env.BB_BASE_PHASE || 0);
const martialBase = String(process.env.BB_MARTIAL_BASE || '').trim();
const martialBasePhase = Number(process.env.BB_MARTIAL_PHASE || 0);
const captureMode = String(process.env.BB_CAPTURE || 'full').trim().toLowerCase();
const evidenceLabel = String(process.env.BB_EVIDENCE_LABEL || '').trim();
const evidenceState = String(process.env.BB_STATE || '').trim();
const textureOverride = String(process.env.BB_TEXTURE || '').trim();
const rig = String(process.env.BB_RIG || 'settler').trim().toLowerCase();

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
const conditionalRuntimeOffhand = clip
    ? propContract.conditionalRuntimeOffhandClips?.[canonicalClipName(clip)]
    : null;
if (conditionalRuntimeOffhand) {
    throw new Error(`${canonicalClipName(clip)} requires a real synced OFFHAND ` +
        `${conditionalRuntimeOffhand}. The offline renderer deliberately refuses ` +
        `to fabricate that conditional item; capture it in the real client.`);
}
const propOverride = String(process.env.BB_PROP || '').trim().toLowerCase();
const prop = propOverride || inferredProp;
const contractWasOverridden = Boolean(propOverride && propOverride !== inferredProp);
const contextualProps = Object.values(propContract.contextualClips || {})
    .flatMap(contexts => Object.values(contexts));
const supportedProps = new Set([
    'none', ...Object.values(propContract.clips), ...contextualProps,
]);
const view = String(process.env.BB_VIEW || 'front34').trim().toLowerCase();
const supportedViews = new Set(['front34', 'back34', 'left', 'right', 'oblique_left']);
if (!supportedProps.has(prop)) {
    throw new Error(`Unsupported BB_PROP ${JSON.stringify(prop)}; expected one of: ` +
        [...supportedProps].sort().join(', '));
}
if (!supportedViews.has(view)) {
    throw new Error(`Unsupported BB_VIEW ${JSON.stringify(view)}; expected one of: ` +
        [...supportedViews].sort().join(', '));
}
if (!new Set(['settler', 'raider']).has(rig)) {
    throw new Error('BB_RIG must be settler or raider.');
}
if (!Number.isFinite(carryFill) || carryFill < 0 || carryFill > 1) {
    throw new Error('BB_CARRY_FILL must be a number from 0 through 1.');
}
if (!Number.isFinite(limbSwingAmount) || limbSwingAmount < 0 || limbSwingAmount > 1) {
    throw new Error('BB_LIMB_SWING_AMOUNT must be a number from 0 through 1.');
}
if (!Number.isFinite(compositeBasePhase) || compositeBasePhase < 0) {
    throw new Error('BB_BASE_PHASE must be a finite, non-negative number of seconds.');
}
if (!Number.isFinite(martialBasePhase) || martialBasePhase < 0) {
    throw new Error('BB_MARTIAL_PHASE must be a finite, non-negative number of seconds.');
}
if (martialBase && (!compositeMode || canonicalClipName(clip) !== 'MELEE')) {
    throw new Error('BB_MARTIAL_BASE is only valid for --composite LOCOMOTION MELEE evidence.');
}
if (!martialBase && martialBasePhase !== 0) {
    throw new Error('BB_MARTIAL_PHASE requires BB_MARTIAL_BASE.');
}
if (!compositeMode && compositeBasePhase !== 0) {
    throw new Error('BB_BASE_PHASE is only valid with --composite BASE OVERLAY.');
}
if (!new Set(['full', 'preview']).has(captureMode)) {
    throw new Error('BB_CAPTURE must be either full or preview.');
}
if (times.some(time => !Number.isFinite(time) || time < 0)) {
    throw new Error('Frame times must be finite, non-negative seconds.');
}

function signedBearingDegrees(vector) {
    return Math.atan2(vector[0], -vector[2]) * 180 / Math.PI;
}

function wrapDegrees(value) {
    return ((value + 180) % 360 + 360) % 360 - 180;
}

function validateMeleeTargetContract(target) {
    if (!target || target.role !== 'primary_forward_attack_bearing') {
        throw new Error('MELEE primary target must be labelled primary_forward_attack_bearing');
    }
    const finiteVector = (value, size) => Array.isArray(value)
        && value.length === size && value.every(Number.isFinite);
    for (const [name, value] of [
        ['actorForwardModel', target.actorForwardModel],
        ['centerModelPixels', target.centerModelPixels],
        ['sizeModelPixels', target.sizeModelPixels],
        ['actorEyeModelPixels', target.actorEyeModelPixels],
        ['targetEyeModelPixels', target.targetEyeModelPixels],
    ]) {
        if (!finiteVector(value, 3)) throw new Error(`MELEE target ${name} must be a finite vec3`);
    }
    const horizontal = [target.centerModelPixels[0], 0, target.centerModelPixels[2]];
    const distance = Math.hypot(horizontal[0], horizontal[2]);
    const forwardLength = Math.hypot(
        target.actorForwardModel[0], target.actorForwardModel[2]);
    if (distance < 1e-6 || forwardLength < 1e-6) {
        throw new Error('MELEE target bearing and actor forward must be non-zero');
    }
    const dot = (horizontal[0] * target.actorForwardModel[0]
        + horizontal[2] * target.actorForwardModel[2]) / (distance * forwardLength);
    const bearingError = Math.acos(Math.max(-1, Math.min(1, dot))) * 180 / Math.PI;
    if (bearingError > target.maxPrimaryBearingErrorDegrees + 1e-6) {
        throw new Error(`MELEE primary target is ${bearingError.toFixed(2)}deg off actor forward`);
    }
    const bodyForward = [
        Math.sin(target.bodyYawDegrees * Math.PI / 180), 0,
        -Math.cos(target.bodyYawDegrees * Math.PI / 180),
    ];
    const bodyError = Math.acos(Math.max(-1, Math.min(1,
        bodyForward[0] * target.actorForwardModel[0] / forwardLength
        + bodyForward[2] * target.actorForwardModel[2] / forwardLength))) * 180 / Math.PI;
    if (bodyError > target.maxPrimaryBearingErrorDegrees + 1e-6) {
        throw new Error(`MELEE body yaw is ${bodyError.toFixed(2)}deg off actor forward`);
    }
    const expectedTimes = (name, actual, expected) => {
        if (!Array.isArray(actual) || actual.length !== expected.length
                || actual.some((value, index) => Math.abs(value - expected[index]) > 1e-6)) {
            throw new Error(`MELEE target ${name} drifted: ${JSON.stringify(actual)}`);
        }
    };
    expectedTimes('impactBandSeconds', target.impactBandSeconds, [0.20, 0.25]);
    expectedTimes('mustBeClearSeconds', target.mustBeClearSeconds,
        [0, 0.05, 0.10, 0.15]);
    expectedTimes('mustBeSeparatedSeconds', target.mustBeSeparatedSeconds,
        [0.30, 0.35, 0.40, 0.45, 0.50]);
    const eyeDelta = target.targetEyeModelPixels.map(
        (value, index) => value - target.actorEyeModelPixels[index]);
    const bearing = signedBearingDegrees(eyeDelta);
    const relativeYaw = wrapDegrees(bearing - target.bodyYawDegrees);
    const horizontalEyeDistance = Math.hypot(eyeDelta[0], eyeDelta[2]);
    const pitch = -Math.atan2(eyeDelta[1], horizontalEyeDistance) * 180 / Math.PI;
    return {
        bearingErrorDegrees: bearingError,
        bodyErrorDegrees: bodyError,
        bearingDegrees: bearing,
        relativeYawDegrees: relativeYaw,
        netHeadYawDegrees: Math.max(-target.lookYawLimitDegrees,
            Math.min(target.lookYawLimitDegrees, relativeYaw)),
        pitchDegrees: Math.max(-target.lookPitchLimitDegrees,
            Math.min(target.lookPitchLimitDegrees, pitch)),
    };
}

const meleeTargetLook = clip && canonicalClipName(clip) === 'MELEE'
    ? validateMeleeTargetContract(propContract.meleeEvidenceTarget)
    : null;

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
const modelData = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
if (textureOverride) {
    if (!path.isAbsolute(textureOverride)) {
        throw new Error('BB_TEXTURE must be an absolute PNG path.');
    }
    if (!fs.existsSync(textureOverride) || path.extname(textureOverride).toLowerCase() !== '.png') {
        throw new Error(`BB_TEXTURE is not a readable PNG: ${textureOverride}`);
    }
    if (!modelData.textures?.length) {
        throw new Error('The Blockbench model has no texture slot to override.');
    }
    const texture = modelData.textures[0];
    texture.path = textureOverride.replaceAll('\\', '/');
    texture.name = path.basename(textureOverride);
    texture.source = 'data:image/png;base64,' +
        fs.readFileSync(textureOverride).toString('base64');
    console.log('texture override:', textureOverride);
}
const modelJson = JSON.stringify(modelData);

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

    if (evidenceLabel) {
        await page.evaluate(({ label, state, viewName }) => {
            const host = document.querySelector('#preview');
            if (!host) throw new Error('Blockbench #preview host not found');
            host.style.position = 'relative';
            const badge = document.createElement('div');
            badge.id = 'hearthstead-evidence-label';
            Object.assign(badge.style, {
                position: 'absolute', left: '12px', top: '12px', zIndex: '99999',
                padding: '7px 10px', border: '2px solid #ffb020', borderRadius: '3px',
                color: '#fff4d6', background: 'rgba(30, 20, 4, 0.88)',
                font: '700 13px/1.35 monospace', letterSpacing: '0.04em',
                whiteSpace: 'pre', pointerEvents: 'none',
            });
            badge.dataset.label = label;
            badge.dataset.state = state;
            badge.dataset.view = viewName;
            badge.textContent = label;
            host.appendChild(badge);
        }, { label: evidenceLabel, state: evidenceState, viewName: view });
    }

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
            cube.mesh.userData.hsPropMaterial = material;
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

    // K1-OFFHAND: the portable-container sequence transfers the physical log
    // ItemEntity into OFFHAND at pickup contact. Reconstruct the same
    // ItemInHandLayer path for a vanilla oak-log block instead of attaching a
    // hand-authored cube directly to the forearm. The left-hand layer mirrors
    // both its one-pixel X translation and the vanilla ItemTransform Y/Z
    // rotations, exactly as ItemInHandLayer + ItemTransform#apply do in 1.21.1.
    const attachedOffhandLog = await page.evaluate(({ clipName, contract }) => {
        const physicalClips = new Set([
            'GROUND_ITEM_PICKUP', 'WALK_CARRY_ITEM', 'WORK_CONTAINER_STOW',
        ]);
        if (!physicalClips.has(clipName)) return null;
        const arm = Group.all.find(group => group.name === 'left_arm');
        if (!arm) throw new Error('left_arm group not found');

        const bb = contract.blockbench;
        const layer = contract.itemInHandLayer;
        const display = contract.displayProfiles.block;
        const expectedDisplay = bb.expectedNeutralLeftDisplays?.block;
        if (!display || !expectedDisplay) {
            throw new Error('no pinned vanilla left-hand block display profile');
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

        if (!almostEqual(arm.origin, bb.leftArmOrigin)) {
            throw new Error(`left_arm origin drifted: ${JSON.stringify(arm.origin)}; ` +
                `contract expects ${JSON.stringify(bb.leftArmOrigin)}`);
        }

        let origin = [...arm.origin];
        let parent = arm;
        for (const [index, step] of layer.rotateSequence.entries()) {
            const axis = axes[step.axis];
            if (axis === undefined) throw new Error(`invalid layer rotation axis: ${step.axis}`);
            const rotation = [0, 0, 0];
            rotation[axis] = step.degrees * bb.modelToBlockbenchAxisSigns[axis];
            parent = makeGroup(`k1_offhand_layer_r${index}_${step.axis}`,
                parent, origin, rotation);
        }
        const mirroredLayerTranslation = [...layer.translateModelPixels];
        mirroredLayerTranslation[0] *= -1;
        origin = add(origin, mapVector(mirroredLayerTranslation));
        const layerTranslation = makeGroup('k1_offhand_layer_translate', parent, origin);
        parent = layerTranslation;

        const mirroredDisplayTranslation = [...display.translateModelPixels];
        mirroredDisplayTranslation[0] *= -1;
        origin = add(origin, mapVector(mirroredDisplayTranslation));
        const displayTranslation = makeGroup(
            'k1_offhand_block_display_translate', parent, origin);
        parent = displayTranslation;
        for (const [axisName, axis] of Object.entries(axes)) {
            let degrees = display.rotationXYZDegrees[axis];
            if (axis === axes.y || axis === axes.z) degrees *= -1;
            const rotation = [0, 0, 0];
            rotation[axis] = degrees * bb.modelToBlockbenchAxisSigns[axis];
            parent = makeGroup(`k1_offhand_block_display_r${axisName}`,
                parent, origin, rotation);
        }
        const displayRotation = parent;
        const proxy = makeGroup('hs_preview_offhand_log', parent, origin);

        const materials = {
            bark: new THREE.MeshLambertMaterial({ color: '#5f3b22' }),
            rings: new THREE.MeshLambertMaterial({ color: '#c3975c' }),
        };
        const proxyCubes = [];
        const itemPoint = point => mapVector(point.map((value, index) =>
            (value + display.centerModelPixels[index]) * display.scale[index]));
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
            throw new Error(`left-hand block centre/scale mismatch: centre ` +
                `${JSON.stringify(itemCenter)}, basis ${JSON.stringify(localScaleBasis)}`);
        }
        const addBox = (name, from, to, material) => {
            const first = itemPoint(from);
            const second = itemPoint(to);
            const cube = new Cube({
                name: `k1_offhand_log_${name}`,
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
        addBox('body', [0, 0, 0], [16, 16, 16], 'bark');
        // Thin end-grain overlays preserve the oak-log read while retaining
        // the exact 16x16x16 physical block silhouette.
        addBox('bottom_rings', [0.02, 0.0, 0.02], [15.98, 0.08, 15.98], 'rings');
        addBox('top_rings', [0.02, 15.92, 0.02], [15.98, 16.0, 15.98], 'rings');
        Canvas.updateAll();
        for (const [cube, material] of proxyCubes) {
            cube.mesh.material = materials[material];
        }
        proxy.mesh.visible = false;
        Canvas.scene.updateMatrixWorld(true);

        const worldPosition = group =>
            group.scene_object.getWorldPosition(new THREE.Vector3()).toArray();
        const displayMatrix = displayRotation.scene_object.matrixWorld.elements;
        const layerOrigin = worldPosition(layerTranslation);
        const displayOrigin = worldPosition(displayTranslation);
        const displayBasisRows = [
                [displayMatrix[0], displayMatrix[4], displayMatrix[8]],
                [displayMatrix[1], displayMatrix[5], displayMatrix[9]],
                [displayMatrix[2], displayMatrix[6], displayMatrix[10]],
            ];
        if (!almostEqual(layerOrigin, bb.expectedNeutralLeftLayerOrigin)) {
            throw new Error(`left ItemInHandLayer matrix mismatch: got ` +
                `${JSON.stringify(layerOrigin)}, expected ` +
                `${JSON.stringify(bb.expectedNeutralLeftLayerOrigin)}`);
        }
        if (!almostEqual(displayOrigin, expectedDisplay.origin)) {
            throw new Error(`left block display origin mismatch: got ` +
                `${JSON.stringify(displayOrigin)}, expected ` +
                `${JSON.stringify(expectedDisplay.origin)}`);
        }
        if (!displayBasisRows.every((row, index) =>
            almostEqual(row, expectedDisplay.basisRows[index]))) {
            throw new Error(`left block display basis mismatch: got ` +
                `${JSON.stringify(displayBasisRows)}, expected ` +
                `${JSON.stringify(expectedDisplay.basisRows)}`);
        }
        return {
            layerOrigin,
            displayOrigin,
            displayBasisRows,
            localScaleBasis,
            layerTranslation: mirroredLayerTranslation,
            itemDisplay: display,
        };
    }, { clipName: canonicalClipName(clip), contract: propContract });
    if (attachedOffhandLog) {
        console.log('verified OFFHAND oak-log transform:',
            JSON.stringify(attachedOffhandLog));
    }

    // MELEE evidence must show what the blade is supposed to hit. A pose in
    // empty space can hide bad reach and timing, so render a world-fixed,
    // Minecraft-sized raider dummy inside its real 0.6 x 1.8 x 0.6 block hit
    // volume. This group is viewport-only and never enters the bbmodel.
    const meleeTarget = await page.evaluate(({ clipName, contract, runtimeLook }) => {
        if (clipName !== 'MELEE') return null;
        const target = contract.meleeEvidenceTarget;
        if (!target) throw new Error('MELEE evidence target contract is missing');
        const [cx, cy, cz] = target.centerModelPixels;
        const [width, height, depth] = target.sizeModelPixels;
        const root = new THREE.Group();
        root.name = 'hs_melee_target';
        root.position.set(cx, 0, cz);

        const cloth = new THREE.MeshLambertMaterial({ color: '#6f2636' });
        const leather = new THREE.MeshLambertMaterial({ color: '#442a24' });
        const skin = new THREE.MeshLambertMaterial({ color: '#8b5845' });
        const iron = new THREE.MeshLambertMaterial({ color: '#87919a' });
        const addPart = (name, size, position, material) => {
            const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), material);
            mesh.name = name;
            mesh.position.set(...position);
            root.add(mesh);
        };
        // 28 px physical silhouette kept wholly inside the 28.8 px hitbox.
        addPart('hs_melee_target_right_leg', [3.6, 10, 3.6], [2.1, 5, 0], leather);
        addPart('hs_melee_target_left_leg', [3.6, 10, 3.6], [-2.1, 5, 0], leather);
        addPart('hs_melee_target_torso', [8, 10, 4.5], [0, 15, 0], cloth);
        addPart('hs_melee_target_head', [7.5, 7.5, 7.5], [0, 23.75, 0], skin);
        addPart('hs_melee_target_helm', [8.2, 2.2, 8.2], [0, 27, 0], iron);

        const hitMaterial = new THREE.MeshBasicMaterial({
            color: '#ff355e', wireframe: true, transparent: true,
            opacity: 0.72, depthWrite: false,
        });
        const hitbox = new THREE.Mesh(
            new THREE.BoxGeometry(width, height, depth), hitMaterial);
        hitbox.name = 'hs_melee_target_hitbox';
        hitbox.position.set(0, cy, 0);
        hitbox.renderOrder = 100;
        root.add(hitbox);

        const ringMaterial = new THREE.MeshBasicMaterial({
            color: '#ffb347', transparent: true, opacity: 0.85,
            side: THREE.DoubleSide, depthWrite: false,
        });
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(width * 0.52, width * 0.67, 32), ringMaterial);
        ring.name = 'hs_melee_target_ground_ring';
        ring.rotation.x = -Math.PI / 2;
        ring.position.y = 0.04;
        root.add(ring);
        Canvas.scene.add(root);
        Canvas.scene.updateMatrixWorld(true);
        const worldBox = new THREE.Box3().setFromObject(hitbox);
        return {
            center: target.centerModelPixels,
            size: target.sizeModelPixels,
            role: target.role,
            actorForwardModel: target.actorForwardModel,
            bodyYawDegrees: target.bodyYawDegrees,
            runtimeLook,
            contactSeconds: target.contactSeconds,
            impactBandSeconds: target.impactBandSeconds,
            mustBeClearSeconds: target.mustBeClearSeconds,
            mustBeSeparatedSeconds: target.mustBeSeparatedSeconds,
            bounds: { min: worldBox.min.toArray(), max: worldBox.max.toArray() },
        };
    }, {
        clipName: canonicalClipName(clip), contract: propContract,
        runtimeLook: meleeTargetLook,
    });
    if (meleeTarget) {
        console.log('MELEE physical raider target:', JSON.stringify(meleeTarget));
    }

    // ANIM-TRUTH-0A offline authority reconstruction. These viewport-only
    // props are fixed children of Canvas.scene, never of root/torso/arms.
    // Runtime uses the exact server-synced ItemStacks; here geometry and
    // colour make the same recipe slots and ownership transition reviewable.
    const craftScene = await page.evaluate(({ clipName, contract }) => {
        if (clipName !== 'LUMBER_CRAFT'
                && clipName !== 'CRAFT_OUTPUT_STORE') return null;
        const target = contract.craftingEvidenceTarget;
        if (!target || target.role !== 'fixed_world_authority_reconstruction') {
            throw new Error('CRAFT fixed-world evidence target contract is missing');
        }
        const root = new THREE.Group();
        root.name = 'hs_craft_authority_scene';
        const oak = new THREE.MeshLambertMaterial({ color: '#9b6335' });
        const oakTop = new THREE.MeshLambertMaterial({ color: '#c18b4f' });
        const darkWood = new THREE.MeshLambertMaterial({ color: '#5c3922' });
        const stick = new THREE.MeshLambertMaterial({ color: '#7d512b' });
        const iron = new THREE.MeshLambertMaterial({ color: '#c8d2d4' });

        const addBox = (name, size, position, material, rotationY = 0) => {
            const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), material);
            mesh.name = name;
            mesh.position.set(...position);
            mesh.rotation.y = rotationY;
            root.add(mesh);
            return mesh;
        };
        const [cx, cy, cz] = target.tableCenterModelPixels;
        let table = null;
        let chest = null;
        if (clipName === 'LUMBER_CRAFT') {
            table = addBox('hs_craft_table', target.tableSizeModelPixels,
                [cx, cy, cz], oak);
            addBox('hs_craft_table_top', [16.2, 0.55, 16.2],
                [cx, cy + 8.25, cz], oakTop);
            // Four inset seams make the 3x3 recipe placement readable while
            // keeping this a generic workbench proxy, not a copied asset.
            for (const offset of [-target.gridSpacingModelPixels / 2,
                    target.gridSpacingModelPixels / 2]) {
                addBox(`hs_craft_grid_x_${offset}`, [0.12, 0.12, 11.2],
                    [cx + offset, cy + 8.58, cz], darkWood);
                addBox(`hs_craft_grid_z_${offset}`, [11.2, 0.12, 0.12],
                    [cx, cy + 8.58, cz + offset], darkWood);
            }
        } else {
            chest = addBox('hs_craft_storage', [14, 13, 14],
                [cx, 6.5, cz], darkWood);
            addBox('hs_craft_storage_lid', [14.5, 3.2, 14.5],
                [cx, 13.1, cz], oak);
            addBox('hs_craft_storage_latch', [1.6, 2.4, 0.8],
                [cx, 12.3, cz + 7.5], iron);
        }

        const slots = [];
        for (const slot of target.recipeOccupiedSlots) {
            const row = Math.floor(slot / 3);
            const column = slot % 3;
            const position = [
                cx + (column - 1) * target.gridSpacingModelPixels,
                cy + 8.95,
                cz + (1 - row) * target.gridSpacingModelPixels,
            ];
            const plank = slot === 0 || slot === 1 || slot === 3;
            const mesh = plank
                ? addBox(`hs_craft_slot_${slot}_plank`, [3.0, 0.55, 3.0],
                    position, oakTop)
                : addBox(`hs_craft_slot_${slot}_stick`, [0.65, 0.48, 3.1],
                    position, stick, Math.PI / 4);
            slots.push({ slot, name: mesh.name });
        }

        const output = new THREE.Group();
        output.name = 'hs_craft_output_wooden_axe';
        const outputPosition = clipName === 'LUMBER_CRAFT'
            ? [cx, cy + 9.05, cz]
            : [cx, 15.1, cz + 5.4];
        output.position.set(...outputPosition);
        output.rotation.y = -Math.PI / 4;
        const handle = new THREE.Mesh(new THREE.BoxGeometry(0.7, 0.55, 7.0), stick);
        handle.name = 'hs_craft_output_handle';
        const head = new THREE.Mesh(new THREE.BoxGeometry(4.2, 0.72, 2.2), oakTop);
        head.name = 'hs_craft_output_head';
        head.position.z = -2.6;
        head.position.x = 1.45;
        output.add(handle, head);
        root.add(output);

        Canvas.scene.add(root);
        Canvas.scene.updateMatrixWorld(true);
        return {
            clipName,
            worldRoot: root.name,
            table: table?.name || null,
            storage: chest?.name || null,
            slots,
            output: output.name,
            target,
        };
    }, { clipName: canonicalClipName(clip), contract: propContract });
    if (craftScene) {
        console.log('CRAFT fixed-world authority reconstruction:',
            JSON.stringify(craftScene));
    }

    // front34 is the standard face-visible whole-pose review. back34/left/right
    // are evidence views for prop and hand contact when the torso occludes it.
    // The left view keeps a small, documented front offset: an exact -X
    // projection hides a right-hand sword edge-on behind this block rig and
    // cannot prove its anticipation silhouette.
    const cameraView = clip ? view : 'front34';
    await page.evaluate((viewName) => {
        const p = Preview.selected;
        const camera = viewName === 'right'
            ? { position: [68, 26, 0], target: [4, 15, 0] }
            : viewName === 'left'
                ? { position: [-68, 26, -18], target: [2, 15, 0] }
                : viewName === 'oblique_left'
                    // Target-left / actor sword-side oblique. This keeps the
                    // direct-forward target in frame while exposing the real
                    // right hand, hilt and diagonal blade arc.
                    ? { position: [58, 30, -42], target: [-1, 14, -5] }
                : viewName === 'back34'
                    ? { position: [40, 32, 55], target: [0, 14, 0] }
                    : { position: [-45, 30, -50], target: [0, 14, 0] };
        p.camera.position.set(...camera.position);
        p.controls.target.set(...camera.target);
        p.controls.update();
    }, cameraView);
    console.log('view:', cameraView);
    await page.waitForTimeout(800);

    const writeScreenshot = async (target) => {
        if (captureMode === 'preview') {
            await page.locator('#preview').screenshot({ path: target });
        } else {
            await page.screenshot({ path: target });
        }
    };

    // Preview-only reconstruction of SettlerModel.applyWorkContainer. It
    // never writes model or animation data. Lumberers use a rigid wooden frame
    // with discrete visible logs; couriers and produce-laden farmers use a
    // canvas parcel. Root-owned
    // duplicates travel only during the authored handoff, then remain at the
    // fixed ground anchor. Attached props have no secondary sway or lag.
    const applyPreviewRuntime = async (time) => page.evaluate(({
        fill, t, walkAmount, clipName, workerContext, previewRig,
        craftContract,
    }) => {
        const group = name => Group.all.find(candidate => candidate.name === name);
        if (previewRig === 'raider') {
            const required = ['root', 'torso', 'head', 'hood', 'helm', 'pauldron',
                'right_arm', 'left_arm', 'right_leg', 'left_leg'];
            const parts = Object.fromEntries(required.map(name => [name, group(name)]));
            if (required.some(name => !parts[name])) {
                throw new Error('raider runtime preview groups are required');
            }
            const captain = workerContext.includes('captain');
            const brute = workerContext.includes('brute');
            parts.helm.mesh.visible = captain;
            parts.pauldron.mesh.visible = captain;
            parts.hood.mesh.visible = !captain;
            if (brute) {
                parts.torso.mesh.scale.set(1.30, 0.90, 1.22);
                for (const arm of [parts.right_arm, parts.left_arm]) {
                    arm.mesh.scale.set(1.16, 1.38, 1.16);
                }
                parts.head.mesh.scale.set(1.10, 0.94, 1.10);
                for (const leg of [parts.right_leg, parts.left_leg]) {
                    leg.mesh.scale.set(1.14, 0.94, 1.14);
                }
            }
            // Java rotation channels map to (-x,-y,+z) in Blockbench.
            // Java +0.09 moves the corrected negative-X forward lean toward
            // upright; the Blockbench inverse is -0.09.
            if (captain) {
                parts.torso.mesh.rotation.x -= 0.09;
                parts.head.mesh.rotation.x -= 0.045;
            }
            if (workerContext.includes('hurt')) {
                parts.torso.mesh.rotation.x -= Math.sin(Math.PI * 0.5) * 0.18;
            }
            return;
        }
        const sack = group('sack');
        const groundSack = group('ground_sack');
        const lumberFrame = group('lumber_frame');
        const groundLumberFrame = group('ground_lumber_frame');
        const lumberLogs = [group('log_left'), group('log_center'), group('log_right')];
        const groundLumberLogs = [group('ground_log_left'),
            group('ground_log_center'), group('ground_log_right')];
        const wateringCan = group('watering_can');
        const backpack = group('backpack');
        const root = group('root');
        const torso = group('torso');
        const head = group('head');
        const rightArm = group('right_arm');
        const leftArm = group('left_arm');
        if (!sack || !groundSack || !lumberFrame || !groundLumberFrame
                || lumberLogs.some(candidate => !candidate)
                || groundLumberLogs.some(candidate => !candidate)
                || !wateringCan || !backpack || !root || !torso || !head
                || !rightArm || !leftArm) {
            throw new Error('work-container preview groups are required');
        }

        const craftRoot = Canvas.scene.getObjectByName(
            'hs_craft_authority_scene');
        if (craftRoot) {
            const slots = craftContract.recipeOccupiedSlots.map(slot =>
                Canvas.scene.getObjectByName(`hs_craft_slot_${slot}_${
                    slot === 0 || slot === 1 || slot === 3 ? 'plank' : 'stick'}`));
            const output = Canvas.scene.getObjectByName(
                'hs_craft_output_wooden_axe');
            if (slots.some(slot => !slot) || !output) {
                throw new Error('CRAFT world props are incomplete');
            }
            if (clipName === 'LUMBER_CRAFT') {
                slots.forEach((slot, index) => {
                    slot.visible = t >= craftContract.layoutContactSeconds[index]
                        && t < craftContract.transformContactSeconds;
                });
                output.visible = t >= craftContract.transformContactSeconds
                    && t < craftContract.pickupContactSeconds;
            } else if (clipName === 'CRAFT_OUTPUT_STORE') {
                slots.forEach(slot => { slot.visible = false; });
                output.visible = t < craftContract.storageContactSeconds;
            }
        }

        // Truthful physical-item projection for the collection states. The
        // runtime ItemInHandLayer is not available in Blockbench, so these
        // transient THREE meshes make ownership visible without altering the
        // exported model: world drop before pickup contact, OFFHAND after it,
        // OFFHAND through the carry/stow approach, then gone at stow contact.
        let droppedLog = Canvas.scene.getObjectByName('hs_preview_dropped_log');
        if (!droppedLog) {
            droppedLog = new THREE.Group();
            droppedLog.name = 'hs_preview_dropped_log';
            const bark = new THREE.MeshLambertMaterial({ color: '#5f3b22' });
            const rings = new THREE.MeshLambertMaterial({ color: '#c3975c' });
            droppedLog.add(new THREE.Mesh(
                new THREE.BoxGeometry(6, 3.2, 3.2), bark));
            for (const x of [-3.05, 3.05]) {
                const cap = new THREE.Mesh(
                    new THREE.BoxGeometry(0.12, 3.22, 3.22), rings);
                cap.position.x = x;
                droppedLog.add(cap);
            }
            droppedLog.rotation.set(0.12, 0.28, -0.10);
            droppedLog.position.set(0, 1.8, -13);
            Canvas.scene.add(droppedLog);
        }
        const offhandLog = group('hs_preview_offhand_log');
        const pickupContact = 0.60;
        const stowContact = 0.60;
        droppedLog.visible = clipName === 'GROUND_ITEM_PICKUP'
            && t < pickupContact;
        if (offhandLog) {
            offhandLog.mesh.visible = (clipName === 'GROUND_ITEM_PICKUP'
                    && t >= pickupContact)
                || clipName === 'WALK_CARRY_ITEM'
                || (clipName === 'WORK_CONTAINER_STOW' && t < stowContact);
        }
        const detachedClips = new Set([
            'WORK_CONTAINER_DOWN', 'GROUND_ITEM_PICKUP', 'WALK_CARRY_ITEM',
            'WORK_CONTAINER_STOW', 'WORK_CONTAINER_UP',
        ]);
        const detached = detachedClips.has(clipName);
        const lumberer = workerContext === 'lumberer';
        const courier = workerContext === 'courier';
        const farmer = workerContext === 'farmer';
        const farmerCarrying = farmer && clipName === 'FARMER_CARRY'
            && fill > 0.001;
        wateringCan.mesh.visible = farmer && clipName === 'FARM_WATER';
        lumberFrame.mesh.visible = lumberer && !detached;
        sack.mesh.visible = (courier || farmerCarrying) && !detached;
        groundLumberFrame.mesh.visible = lumberer && detached;
        groundSack.mesh.visible = detached && !groundLumberFrame.mesh.visible;
        backpack.mesh.visible = !lumberFrame.mesh.visible && !sack.mesh.visible
            && !groundLumberFrame.mesh.visible && !groundSack.mesh.visible;

        const showLogs = (parts, frameVisible) => {
            const thresholds = [0.001, 0.34, 0.67];
            parts.forEach((part, index) => {
                part.mesh.visible = frameVisible && fill >= thresholds[index];
            });
        };
        showLogs(lumberLogs, lumberFrame.mesh.visible);
        showLogs(groundLumberLogs, groundLumberFrame.mesh.visible);

        const size = 0.80 + (1.05 - 0.80) * fill;
        if (sack.mesh.visible) {
            sack.mesh.scale.set(size, size, size);
        }
        if (lumberFrame.mesh.visible || sack.mesh.visible) {
            torso.mesh.rotation.x += 0.16 * fill;
            head.mesh.rotation.x -= 0.16 * 0.6 * fill;
        }

        // Exact diagnostic projection of SettlerModel.applyArcherBowMotion.
        // The authored ARCHER_STANCE remains the start/end pose; 0..1 s is
        // the vanilla server-synced draw clock and 1..1.4 s is the 400 ms
        // EV_ARCHER_LOOSE recovery. Java channels map to (-x,-y,+z) here.
        // This is Candidate evidence only: the real client still owns final
        // item transform, arrow spawn and draw/release acceptance.
        if (workerContext === 'archer_draw' && clipName === 'ARCHER_STANCE') {
            const smoothUnit = value => {
                const clamped = Math.max(0, Math.min(1, value));
                return clamped * clamped * (3 - 2 * clamped);
            };
            const blend = t <= 1.0
                ? smoothUnit(t)
                : t <= 1.4
                    ? 1 - smoothUnit((t - 1.0) / 0.4)
                    : 0;
            const lerp = (start, end, alpha) => start + (end - start) * alpha;
            rightArm.mesh.rotation.x = lerp(rightArm.mesh.rotation.x,
                Math.PI / 2, blend);
            rightArm.mesh.rotation.y = lerp(rightArm.mesh.rotation.y,
                0.10, blend);
            rightArm.mesh.rotation.z = lerp(rightArm.mesh.rotation.z, 0, blend);
            leftArm.mesh.rotation.x = lerp(leftArm.mesh.rotation.x,
                Math.PI / 2, blend);
            leftArm.mesh.rotation.y = lerp(leftArm.mesh.rotation.y,
                -0.50, blend);
            leftArm.mesh.rotation.z = lerp(leftArm.mesh.rotation.z, 0, blend);
        }
        const groundContainer = groundLumberFrame.mesh.visible
            ? groundLumberFrame : groundSack;
        if (groundContainer.mesh.visible) {
            const groundScale = groundLumberFrame.mesh.visible ? 1 : size;
            groundContainer.mesh.scale.set(groundScale, groundScale, groundScale);
            const smooth = value => value * value * (3 - 2 * value);
            // Put-down/stow/lift occur beside the container. During pickup
            // and its return walk, the same fixed container remains behind
            // the moving worker instead of cheating into the contact frame.
            // The cube's pivot is at its top/back corner, hence -19 rather
            // than exactly one -16 px block for the near-work position.
            const fixed = clipName === 'GROUND_ITEM_PICKUP'
                ? [0, 8, 32]
                : clipName === 'WALK_CARRY_ITEM'
                    ? [0, 8, 48]
                    : [0, 8, -19];
            // Root channels move the body; express all three physical anchors
            // in root-local coordinates before staging shoulder -> guiding
            // hand -> fixed world position. This mirrors SettlerModel and
            // prevents the container crossing empty air without contact.
            Canvas.scene.updateMatrixWorld(true);
            const rootLocal = (part, local) => {
                const world = part.mesh.localToWorld(new THREE.Vector3(...local));
                return root.mesh.worldToLocal(world);
            };
            const carrier = lumberer ? lumberFrame : sack;
            const shoulder = rootLocal(carrier, [0, 0, 0]);
            // Exported Java Y is flipped in Blockbench; arm y=+9 becomes -9.
            const hand = rootLocal(leftArm, [0, -9, 0]);
            const fixedLocal = new THREE.Vector3(
                fixed[0] - root.mesh.position.x,
                fixed[1] - root.mesh.position.y,
                fixed[2] - root.mesh.position.z,
            );
            const smoothUnit = value => {
                const clamped = Math.max(0, Math.min(1, value));
                return smooth(clamped);
            };
            let target = fixedLocal.clone();
            if (clipName === 'WORK_CONTAINER_DOWN') {
                if (t <= 0.20) {
                    target = shoulder.clone();
                } else if (t < 0.38) {
                    target = shoulder.clone().lerp(hand,
                        smoothUnit((t - 0.20) / 0.18));
                } else if (t <= 0.88) {
                    target = hand.clone();
                } else if (t < 1.05) {
                    target = hand.clone().lerp(fixedLocal,
                        smoothUnit((t - 0.88) / 0.17));
                }
            } else if (clipName === 'WORK_CONTAINER_UP') {
                if (t < 0.60) {
                    target = fixedLocal.clone();
                } else if (t < 0.78) {
                    target = fixedLocal.clone().lerp(hand,
                        smoothUnit((t - 0.60) / 0.18));
                } else if (t <= 1.25) {
                    target = hand.clone();
                } else if (t < 1.45) {
                    target = hand.clone().lerp(shoulder,
                        smoothUnit((t - 1.25) / 0.20));
                } else {
                    target = shoulder.clone();
                }
            }
            groundContainer.mesh.position.copy(target);
            groundContainer.mesh.rotation.x = groundLumberFrame.mesh.visible ? 0 : -0.08;
        }
        Canvas.scene.updateMatrixWorld(true);
    }, {
        fill: carryFill,
        t: time,
        walkAmount: limbSwingAmount,
        clipName: canonicalClipName(clip),
        workerContext: context,
        previewRig: rig,
        craftContract: propContract.craftingEvidenceTarget,
    });

    // Emulate the runtime values supplied to SettlerModel by MobRenderer and
    // MeleeAttackGoal: the actor body yaw establishes model-local forward,
    // LookControl tracks the target with 30-degree yaw/pitch limits, and the
    // resulting netHeadYaw/headPitch are added after authored keyframes.
    // The axis signs mirror export_bbmodel.py's Java <-> Blockbench mapping.
    const applyMeleeRuntimeLook = async () => {
        if (!meleeTargetLook) return null;
        return page.evaluate(({ look, target }) => {
            const head = Group.all.find(candidate => candidate.name === 'head');
            if (!head?.mesh) throw new Error('MELEE runtime look requires the head group');
            head.mesh.rotation.y += -look.netHeadYawDegrees * Math.PI / 180;
            head.mesh.rotation.x += -look.pitchDegrees * Math.PI / 180;
            Canvas.scene.updateMatrixWorld(true);
            return {
                bodyYawDegrees: target.bodyYawDegrees,
                targetBearingDegrees: look.bearingDegrees,
                relativeTargetYawDegrees: look.relativeYawDegrees,
                netHeadYawDegrees: look.netHeadYawDegrees,
                headPitchDegrees: look.pitchDegrees,
                lookYawLimitDegrees: target.lookYawLimitDegrees,
                lookPitchLimitDegrees: target.lookPitchLimitDegrees,
            };
        }, { look: meleeTargetLook, target: propContract.meleeEvidenceTarget });
    };

    const updateEvidenceLabel = async (time, stateName) => {
        if (!evidenceLabel) return;
        await page.evaluate(({ label, state, viewName, t }) => {
            const badge = document.querySelector('#hearthstead-evidence-label');
            if (!badge) return;
            badge.textContent = `${label}\n${state} | ${viewName} | t=${t.toFixed(2)}s`;
        }, {
            label: evidenceLabel,
            state: evidenceState || stateName,
            viewName: cameraView,
            t: time,
        });
    };

    if (!clip) {
        await applyPreviewRuntime(0);
        await updateEvidenceLabel(0, 'MODEL');
        await writeScreenshot(path.join(outDir, 'model-front34.png'));
        await page.evaluate(() => {
            const p = Preview.selected;
            p.camera.position.set(-45, 30, -50);
            p.controls.target.set(0, 14, 0);
            p.controls.update();
        });
        await page.waitForTimeout(400);
        await applyPreviewRuntime(0);
        await updateEvidenceLabel(0, 'MODEL');
        await writeScreenshot(path.join(outDir, 'model-back34.png'));
        console.log('wrote model-front34.png, model-back34.png');
    } else {
        const wantedClip = canonicalClipName(clip);
        const selected = await page.evaluate(({
            clipName, baseClipName, basePhase, martialClipName, martialPhase,
        }) => {
            const canonical = name => String(name || '').split('.').at(-1).toUpperCase();
            const anim = Animation.all.find(a => canonical(a.name) === clipName);
            if (!anim) return null;
            const base = baseClipName
                ? Animation.all.find(a => canonical(a.name) === baseClipName)
                : null;
            if (baseClipName && !base) return { missingBase: true };
            const martial = martialClipName
                ? Animation.all.find(a => canonical(a.name) === martialClipName)
                : null;
            if (martialClipName && !martial) return { missingMartial: true };
            Modes.options.animate.select();
            anim.select();
            // Blockbench natively previews every animation with playing=true.
            // Selecting the overlay makes it the final additive layer, while
            // explicitly enabling the base recreates WALK_LADEN + HAUL_LOG.
            if (base) {
                base.playing = true;
                // Blockbench normally samples every playing animation from
                // the shared Timeline.time. Native MELEE can begin at any
                // point in the continuously-running base loop, so pin a
                // deterministic phase offset on this instance for adversarial
                // GUARD_STANCE/WALK composite evidence.
                Object.defineProperty(base, 'time', {
                    configurable: true,
                    get() {
                        const raw = Timeline.time + basePhase;
                        if (!this.length || this.loop === 'once') {
                            return Math.min(raw, this.length || raw);
                        }
                        return ((raw % this.length) + this.length) % this.length;
                    },
                });
            }
            if (martial) {
                // Production SettlerModel applies WALK first, then clears the
                // locomotion-contaminated upper body before the absolute
                // martial GUARD_PATROL base, and finally adds MELEE. Root and
                // legs remain sampled from real gait distance; cloak retains
                // gait drag. Torso is reset in production so WALK yaw cannot
                // move the physical sword contact by gait phase.
                const locomotionOnly = new Set([
                    'right_arm', 'left_arm', 'head', 'torso',
                ]);
                const removed = [];
                for (const [uuid, animator] of Object.entries(base.animators)) {
                    const name = String(animator.name || animator._name || '').toLowerCase();
                    if (locomotionOnly.has(name)) {
                        removed.push(name);
                        delete base.animators[uuid];
                    }
                }
                for (const required of ['right_arm', 'left_arm', 'torso']) {
                    if (!removed.includes(required)) {
                        throw new Error(`WALK locomotion evidence did not remove ${required}`);
                    }
                }
                martial.playing = true;
                Object.defineProperty(martial, 'time', {
                    configurable: true,
                    get() {
                        const raw = Timeline.time + martialPhase;
                        if (!this.length || this.loop === 'once') {
                            return Math.min(raw, this.length || raw);
                        }
                        return ((raw % this.length) + this.length) % this.length;
                    },
                });
            }
            Animator.preview();
            return {
                name: anim.name, length: anim.length, loop: anim.loop,
                baseName: base?.name || null, baseLength: base?.length || null,
                baseLoop: base?.loop || null, basePhase,
                martialName: martial?.name || null,
                martialLength: martial?.length || null,
                martialLoop: martial?.loop || null, martialPhase,
            };
        }, {
            clipName: wantedClip,
            baseClipName: compositeBase ? canonicalClipName(compositeBase) : null,
            basePhase: compositeBasePhase,
            martialClipName: martialBase ? canonicalClipName(martialBase) : null,
            martialPhase: martialBasePhase,
        });
        if (!selected) {
            throw new Error(`clip not found: ${clip}; available: ` +
                loaded.animations.join(', '));
        }
        if (selected.missingBase) {
            throw new Error(`composite base clip not found: ${compositeBase}; available: ` +
                loaded.animations.join(', '));
        }
        if (selected.missingMartial) {
            throw new Error(`martial base clip not found: ${martialBase}; available: ` +
                loaded.animations.join(', '));
        }
        const frameTimes = times.length ? times : [0];
        const pastEnd = frameTimes.find(time => time > selected.length + 1e-6);
        if (pastEnd !== undefined) {
            throw new Error(`frame time ${pastEnd}s exceeds ${wantedClip} length ` +
                `${selected.length}s; refusing a silently clamped duplicate frame`);
        }
        console.log(`selected: ${selected.name} (${selected.length}s)`);
        if (selected.baseName) {
            const martialLabel = selected.martialName
                ? ` locomotion-only + ${selected.martialName}` : '';
            console.warn(`CANDIDATE COMPOSITE PREVIEW: ${selected.baseName}${martialLabel} + ` +
                `${selected.name}; base phase ${selected.basePhase.toFixed(2)}s; ` +
                `martial phase ${selected.martialPhase.toFixed(2)}s; production animation ` +
                'sources are unchanged.');
        }
        if (propContract.contextualClips?.[wantedClip]) {
            console.log(`runtime context: ${context} (contract prop: ${inferredProp})`);
        }
        const knownNoGo = propContract.knownVisualNoGoClips?.[wantedClip];
        if (knownNoGo) {
            console.warn(`VISUAL NO-GO ${wantedClip}: ${knownNoGo}`);
        }
        const phaseSlug = String(compositeBasePhase).replace('.', '_');
        const clipSlug = (selected.baseName
            ? `${canonicalClipName(compositeBase)}-p${phaseSlug}` +
                `${selected.martialName ? `-plus-${canonicalClipName(martialBase)}` : ''}` +
                `-plus-${wantedClip}`
            : wantedClip).toLowerCase().replace(/[^a-z0-9_-]+/g, '-');
        const meleeEvidenceRecords = [];
        const craftEvidenceRecords = [];
        for (const t of frameTimes) {
            await page.evaluate((time) => {
                Timeline.setTime(time);
                Animator.preview();
            }, t);
            await applyPreviewRuntime(t);
            const runtimeLook = await applyMeleeRuntimeLook();
            if (wantedClip === 'LUMBER_CRAFT' || wantedClip === 'CRAFT_OUTPUT_STORE') {
                const craftContact = await page.evaluate(({ time, targetContract, clipName }) => {
                    Canvas.scene.updateMatrixWorld(true);
                    const part = name => Group.all.find(candidate => candidate.name === name);
                    const handPosition = name => {
                        const arm = part(name);
                        if (!arm?.mesh) throw new Error(`CRAFT ${name} is missing`);
                        return arm.mesh.localToWorld(new THREE.Vector3(0, -9, 0)).toArray();
                    };
                    const tableTopY = targetContract.tableCenterModelPixels[1]
                        + targetContract.tableSizeModelPixels[1] / 2 + 0.55;
                    const surfaceDistance = point => {
                        const half = targetContract.tableSizeModelPixels[0] / 2;
                        const dx = Math.max(0,
                            Math.abs(point[0] - targetContract.tableCenterModelPixels[0]) - half);
                        const dz = Math.max(0,
                            Math.abs(point[2] - targetContract.tableCenterModelPixels[2]) - half);
                        return Math.hypot(dx, point[1] - tableTopY, dz);
                    };
                    const rightHand = handPosition('right_arm');
                    const leftHand = handPosition('left_arm');
                    const output = Canvas.scene.getObjectByName('hs_craft_output_wooden_axe');
                    if (!output) throw new Error('CRAFT output evidence geometry is missing');
                    const outputBox = new THREE.Box3().setFromObject(output);
                    const distanceToBox = point => outputBox.distanceToPoint(
                        new THREE.Vector3(...point));
                    return {
                        time,
                        clipName,
                        rightHand,
                        leftHand,
                        rightSurfaceDistance: surfaceDistance(rightHand),
                        leftSurfaceDistance: surfaceDistance(leftHand),
                        rightOutputDistance: distanceToBox(rightHand),
                        leftOutputDistance: distanceToBox(leftHand),
                    };
                }, {
                    time: t,
                    targetContract: propContract.craftingEvidenceTarget,
                    clipName: wantedClip,
                });
                const tableHands = new Map([
                    [0.20, 'right'], [0.40, 'left'], [0.60, 'right'],
                    [0.80, 'left'], [0.90, 'right'], [1.50, 'right'],
                    [2.15, 'left'],
                ]);
                const exactTime = values => [...values.keys()].find(
                    candidate => Math.abs(candidate - t) < 1e-6);
                let expectedHand = null;
                let contactDistance = null;
                let maximumDistance = null;
                if (wantedClip === 'LUMBER_CRAFT') {
                    const contact = exactTime(tableHands);
                    if (contact !== undefined) {
                        expectedHand = tableHands.get(contact);
                        contactDistance = craftContact[`${expectedHand}SurfaceDistance`];
                        maximumDistance = 2.60;
                    }
                } else if (Math.abs(t
                        - propContract.craftingEvidenceTarget.storageContactSeconds) < 1e-6) {
                    expectedHand = 'left';
                    contactDistance = craftContact.leftOutputDistance;
                    maximumDistance = 1.50;
                }
                craftContact.expectedHand = expectedHand;
                craftContact.contactDistance = contactDistance;
                craftContact.maximumDistance = maximumDistance;
                craftContact.physicalContact = expectedHand === null
                    ? null : contactDistance <= maximumDistance + 1e-6;
                craftEvidenceRecords.push(craftContact);
                console.log(`CRAFT hand evidence t=${t.toFixed(2)}:`,
                    JSON.stringify(craftContact));
                if (expectedHand !== null && !craftContact.physicalContact) {
                    throw new Error(`CRAFT ${wantedClip} ${expectedHand}-hand misses `
                        + `authoritative contact at ${t.toFixed(2)}s: `
                        + `${contactDistance.toFixed(2)}px > ${maximumDistance.toFixed(2)}px`);
                }
            }
            if (wantedClip === 'MELEE') {
                const contactEvidence = await page.evaluate(({ targetContract, time }) => {
                    Canvas.scene.updateMatrixWorld(true);
                    const sword = Group.all.find(candidate =>
                        candidate.name === 'k1_vanilla_hand_sword');
                    if (!sword?.mesh) throw new Error('MELEE sword proxy is missing');
                    const hitbox = Canvas.scene.getObjectByName(
                        'hs_melee_target_hitbox');
                    if (!hitbox) throw new Error('MELEE physical target is missing');
                    const swordBox = new THREE.Box3().setFromObject(sword.mesh);
                    const bladeBox = new THREE.Box3();
                    const bladeObbs = [];
                    const makeObb = mesh => {
                        if (!mesh.geometry.boundingBox) mesh.geometry.computeBoundingBox();
                        const local = mesh.geometry.boundingBox;
                        const localCenter = local.getCenter(new THREE.Vector3());
                        const localSize = local.getSize(new THREE.Vector3());
                        const matrix = mesh.matrixWorld;
                        const elements = matrix.elements;
                        const rawAxes = [
                            new THREE.Vector3(elements[0], elements[1], elements[2]),
                            new THREE.Vector3(elements[4], elements[5], elements[6]),
                            new THREE.Vector3(elements[8], elements[9], elements[10]),
                        ];
                        const scales = rawAxes.map(axis => axis.length());
                        if (scales.some(scale => scale < 1e-8)) {
                            throw new Error('MELEE blade has a degenerate oriented transform');
                        }
                        const axes = rawAxes.map((axis, index) => axis.divideScalar(scales[index]));
                        const half = [localSize.x, localSize.y, localSize.z]
                            .map((value, index) => value * scales[index] * 0.5);
                        return {
                            center: localCenter.applyMatrix4(matrix), axes, half,
                            name: mesh.name,
                        };
                    };
                    sword.mesh.traverse(object => {
                        if (object.isMesh && object.userData.hsPropMaterial === 'metal') {
                            bladeBox.expandByObject(object);
                            bladeObbs.push(makeObb(object));
                        }
                    });
                    if (!bladeObbs.length || bladeBox.isEmpty()) {
                        throw new Error('MELEE physical blade geometry is missing');
                    }
                    const targetBox = new THREE.Box3().setFromObject(hitbox);
                    const targetCenter = targetBox.getCenter(new THREE.Vector3());
                    const targetSize = targetBox.getSize(new THREE.Vector3());
                    const targetObb = {
                        center: targetCenter,
                        axes: [new THREE.Vector3(1, 0, 0), new THREE.Vector3(0, 1, 0),
                            new THREE.Vector3(0, 0, 1)],
                        half: [targetSize.x * 0.5, targetSize.y * 0.5, targetSize.z * 0.5],
                    };
                    // Full 15-axis separating-axis theorem. A world AABB made
                    // from the rotated blade is only a diagnostic: it can
                    // overlap while every physical blade cube misses.
                    const obbIntersects = (left, right) => {
                        const epsilon = 1e-7;
                        const rotation = Array.from({ length: 3 }, (_, i) =>
                            Array.from({ length: 3 }, (_, j) => left.axes[i].dot(right.axes[j])));
                        const absolute = rotation.map(row => row.map(value => Math.abs(value) + epsilon));
                        const delta = right.center.clone().sub(left.center);
                        const translated = left.axes.map(axis => delta.dot(axis));
                        for (let i = 0; i < 3; i++) {
                            const radiusRight = right.half[0] * absolute[i][0]
                                + right.half[1] * absolute[i][1]
                                + right.half[2] * absolute[i][2];
                            if (Math.abs(translated[i]) > left.half[i] + radiusRight) return false;
                        }
                        for (let j = 0; j < 3; j++) {
                            const radiusLeft = left.half[0] * absolute[0][j]
                                + left.half[1] * absolute[1][j]
                                + left.half[2] * absolute[2][j];
                            const projected = Math.abs(translated[0] * rotation[0][j]
                                + translated[1] * rotation[1][j]
                                + translated[2] * rotation[2][j]);
                            if (projected > radiusLeft + right.half[j]) return false;
                        }
                        for (let i = 0; i < 3; i++) {
                            for (let j = 0; j < 3; j++) {
                                const i1 = (i + 1) % 3;
                                const i2 = (i + 2) % 3;
                                const j1 = (j + 1) % 3;
                                const j2 = (j + 2) % 3;
                                const radiusLeft = left.half[i1] * absolute[i2][j]
                                    + left.half[i2] * absolute[i1][j];
                                const radiusRight = right.half[j1] * absolute[i][j2]
                                    + right.half[j2] * absolute[i][j1];
                                const projected = Math.abs(translated[i2] * rotation[i1][j]
                                    - translated[i1] * rotation[i2][j]);
                                if (projected > radiusLeft + radiusRight) return false;
                            }
                        }
                        return true;
                    };
                    const orientedHits = bladeObbs.filter(obb => obbIntersects(obb, targetObb));
                    const corners = [];
                    for (const obb of bladeObbs) {
                        for (const sx of [-1, 1]) for (const sy of [-1, 1])
                            for (const sz of [-1, 1]) {
                                corners.push(obb.center.clone()
                                    .addScaledVector(obb.axes[0], sx * obb.half[0])
                                    .addScaledVector(obb.axes[1], sy * obb.half[1])
                                    .addScaledVector(obb.axes[2], sz * obb.half[2]));
                            }
                    }
                    let lineStart = corners[0];
                    let lineEnd = corners[0];
                    let farthestSq = -1;
                    for (let i = 0; i < corners.length; i++) {
                        for (let j = i + 1; j < corners.length; j++) {
                            const distanceSq = corners[i].distanceToSquared(corners[j]);
                            if (distanceSq > farthestSq) {
                                farthestSq = distanceSq;
                                lineStart = corners[i];
                                lineEnd = corners[j];
                            }
                        }
                    }
                    const segmentAabb = (start, end, box) => {
                        const direction = end.clone().sub(start);
                        let enter = 0;
                        let exit = 1;
                        for (const axis of ['x', 'y', 'z']) {
                            if (Math.abs(direction[axis]) < 1e-9) {
                                if (start[axis] < box.min[axis] || start[axis] > box.max[axis]) {
                                    return null;
                                }
                                continue;
                            }
                            let near = (box.min[axis] - start[axis]) / direction[axis];
                            let far = (box.max[axis] - start[axis]) / direction[axis];
                            if (near > far) [near, far] = [far, near];
                            enter = Math.max(enter, near);
                            exit = Math.min(exit, far);
                            if (enter > exit) return null;
                        }
                        const entry = start.clone().addScaledVector(direction, enter);
                        const leaving = start.clone().addScaledVector(direction, exit);
                        return {
                            entry: entry.toArray(), exit: leaving.toArray(),
                            midpoint: entry.clone().add(leaving).multiplyScalar(0.5).toArray(),
                            travelModelPixels: entry.distanceTo(leaving),
                        };
                    };
                    const centerline = segmentAabb(lineStart, lineEnd, targetBox);
                    const gapAxis = axis => Math.max(
                        0,
                        targetBox.min[axis] - bladeBox.max[axis],
                        bladeBox.min[axis] - targetBox.max[axis],
                    );
                    const gaps = ['x', 'y', 'z'].map(gapAxis);
                    const gap = Math.hypot(...gaps);
                    return {
                        time,
                        bladeMeshes: bladeObbs.length,
                        swordBounds: {
                            min: swordBox.min.toArray(), max: swordBox.max.toArray(),
                        },
                        bladeBounds: {
                            min: bladeBox.min.toArray(), max: bladeBox.max.toArray(),
                        },
                        targetBounds: {
                            min: targetBox.min.toArray(), max: targetBox.max.toArray(),
                        },
                        broadAabbIntersects: bladeBox.intersectsBox(targetBox),
                        orientedIntersections: orientedHits.length,
                        orientedBladeCubes: bladeObbs.length,
                        centerline: centerline || {
                            entry: null, exit: null, midpoint: null, travelModelPixels: 0,
                        },
                        centerlineEndpoints: [lineStart.toArray(), lineEnd.toArray()],
                        gapModelPixels: gap,
                        expectedContact: Math.abs(time - targetContract.contactSeconds) < 1e-6,
                        expectedImpactBand: targetContract.impactBandSeconds.some(
                            impact => Math.abs(time - impact) < 1e-6),
                        expectedClear: targetContract.mustBeClearSeconds.some(
                            clear => Math.abs(time - clear) < 1e-6),
                        expectedSeparated: targetContract.mustBeSeparatedSeconds.some(
                            separated => Math.abs(time - separated) < 1e-6),
                    };
                }, {
                    targetContract: propContract.meleeEvidenceTarget,
                    time: t,
                });
                const physicalContact = contactEvidence.orientedIntersections > 0
                    && contactEvidence.centerline.travelModelPixels + 1e-6 >=
                        propContract.meleeEvidenceTarget.minimumCenterlineTravelPixels;
                console.log(`MELEE contact evidence t=${t.toFixed(2)}:`,
                    JSON.stringify({ ...contactEvidence, physicalContact, runtimeLook }));
                if (contactEvidence.expectedImpactBand && !physicalContact) {
                    throw new Error(`MELEE impact-band blade misses oriented raider contact at ` +
                        `${t.toFixed(2)}s: OBB hits=${contactEvidence.orientedIntersections}, ` +
                        `centerline=${contactEvidence.centerline.travelModelPixels.toFixed(2)}px, ` +
                        `broad gap=${contactEvidence.gapModelPixels.toFixed(2)}px`);
                }
                if ((contactEvidence.expectedClear || contactEvidence.expectedSeparated)
                        && (contactEvidence.orientedIntersections > 0
                            || contactEvidence.centerline.travelModelPixels > 1e-6)) {
                    const phase = contactEvidence.expectedClear ? 'pre-contact' : 'post-contact';
                    throw new Error(`MELEE ${phase} blade is not separated at ${t.toFixed(2)}s: ` +
                        `OBB hits=${contactEvidence.orientedIntersections}, ` +
                        `centerline=${contactEvidence.centerline.travelModelPixels.toFixed(2)}px`);
                }
                contactEvidence.physicalContact = physicalContact;
                contactEvidence.runtimeLook = runtimeLook;
                meleeEvidenceRecords.push(contactEvidence);
            }
            const stateLabel = selected.baseName
                ? `${canonicalClipName(compositeBase)} p=${compositeBasePhase.toFixed(2)}` +
                    `${selected.martialName ? ` + ${canonicalClipName(martialBase)} p=${martialBasePhase.toFixed(2)}` : ''}` +
                    ` + ${wantedClip}`
                : wantedClip;
            const craftContactLabel = craftEvidenceRecords.at(-1)?.expectedHand
                ? `${stateLabel} | PHYSICAL ${craftEvidenceRecords.at(-1).expectedHand.toUpperCase()}`
                    + ` HAND CONTACT ${craftEvidenceRecords.at(-1).contactDistance.toFixed(2)}px`
                : null;
            const contactLabel = wantedClip === 'MELEE'
                    && Math.abs(t - propContract.meleeEvidenceTarget.contactSeconds) < 1e-6
                ? `${stateLabel} | TICK 4 CONTACT: ORIENTED BLADE + RAIDER`
                : craftContactLabel || stateLabel;
            await updateEvidenceLabel(t, contactLabel);
            await page.waitForTimeout(300);
            const name = `${clipSlug}-t${String(t).replace('.', '_')}.png`;
            await writeScreenshot(path.join(outDir, name));
            console.log('wrote', name);
        }
        if (wantedClip === 'MELEE') {
            const impactMidpoints = meleeEvidenceRecords
                .filter(record => record.expectedImpactBand)
                .map(record => record.centerline.midpoint);
            if (impactMidpoints.length !== propContract.meleeEvidenceTarget.impactBandSeconds.length
                    || impactMidpoints.some(point => !point)) {
                throw new Error('MELEE impact band did not produce every required centreline midpoint');
            }
            const impactDrift = Math.hypot(...impactMidpoints[0].map(
                (value, index) => value - impactMidpoints[1][index]));
            if (impactDrift > propContract.meleeEvidenceTarget.maximumImpactLineDriftPixels + 1e-6) {
                throw new Error(`MELEE impact centreline drifts ${impactDrift.toFixed(2)}px ` +
                    'inside the narrow 0.20-0.25s band');
            }
            const report = {
                schema: 4,
                status: 'Candidate - native in-game validation pending',
                state: evidenceState || wantedClip,
                view: cameraView,
                basePhaseSeconds: compositeBasePhase,
                martialPhaseSeconds: martialBasePhase,
                impactDriftModelPixels: impactDrift,
                targetContract: propContract.meleeEvidenceTarget,
                records: meleeEvidenceRecords,
            };
            const reportName = `${clipSlug}-${cameraView}-contact.json`;
            fs.writeFileSync(path.join(outDir, reportName),
                `${JSON.stringify(report, null, 2)}\n`, 'utf8');
            console.log('wrote', reportName);
        }
        if (wantedClip === 'LUMBER_CRAFT' || wantedClip === 'CRAFT_OUTPUT_STORE') {
            const report = {
                schema: 1,
                status: 'Candidate - native in-game validation pending',
                state: evidenceState || wantedClip,
                view: cameraView,
                targetContract: propContract.craftingEvidenceTarget,
                records: craftEvidenceRecords,
            };
            const reportName = `${clipSlug}-${cameraView}-craft-contact.json`;
            fs.writeFileSync(path.join(outDir, reportName),
                `${JSON.stringify(report, null, 2)}\n`, 'utf8');
            console.log('wrote', reportName);
        }
    }
} finally {
    await browser.close();
}

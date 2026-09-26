// Build a review-only Lumberer state matrix through the real Blockbench web
// engine. Production model/animation sources are never changed: a fresh
// .bbmodel is exported to a private temporary directory and every screenshot
// is visibly stamped CANDIDATE.
import { spawnSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const neoForge = path.resolve(here, '..', '..');
const outRoot = path.resolve(process.argv[2] || 'lumberer-state-matrix');
const renderer = path.join(here, 'bb_render.mjs');
const exporter = path.join(here, 'export_bbmodel.py');
const texture = path.join(neoForge, 'src', 'main', 'resources', 'assets',
    'hearthstead', 'textures', 'entity', 'settler', 'settler_lumberer.png');
const python = process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
const views = ['front34', 'back34', 'left', 'right'];

const states = [
    { id: 'idle_lumberer', args: ['idle_lumberer', '0', '2.6', '4.3'] },
    { id: 'walk', args: ['walk', '0', '0.1', '0.25', '0.4', '0.5', '0.9', '1'] },
    {
        id: 'walk_laden_plus_haul_log', carry: 0.5, walking: true,
        args: ['--composite', 'walk_laden', 'haul_log',
            '0', '0.3', '0.6', '0.9', '1.2', '2.4'],
    },
    {
        id: 'walk_laden_plus_haul_log_heavy', carry: 1, walking: true,
        args: ['--composite', 'walk_laden', 'haul_log_heavy',
            '0', '0.3', '0.6', '0.9', '1.2', '2.4'],
    },
    {
        id: 'gather_log', carry: 0.625,
        args: ['gather_log', '0', '0.25', '0.35', '0.5', '0.95', '1.1'],
    },
    {
        id: 'pickup_stow', carry: 0.125,
        args: ['pickup_stow', '0', '0.1', '0.5', '0.55', '0.7', '0.9',
            '0.95', '1.1', '1.3', '1.4'],
    },
    { id: 'chop', args: ['chop', '0', '0.4', '0.5', '0.55', '0.65', '0.85', '1'] },
    {
        id: 'chop_loaded', carry: 0.5,
        args: ['chop', '0', '0.4', '0.5', '0.55', '0.65', '0.85', '1'],
    },
    {
        id: 'limb_branches', carry: 0.625,
        args: ['limb_branches', '0', '0.2', '0.3', '0.4', '0.8', '0.95',
            '1.05', '1.3'],
    },
];

if (fs.existsSync(outRoot)) {
    throw new Error(`Refusing to overwrite an existing evidence directory: ${outRoot}`);
}
fs.mkdirSync(outRoot, { recursive: true });

const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hearthstead-lumberer-matrix-'));
const model = path.join(tempRoot, 'settler-current.bbmodel');
try {
    const exported = spawnSync(python, [exporter, '--output', model], {
        cwd: here, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    process.stdout.write(exported.stdout || '');
    process.stderr.write(exported.stderr || '');
    if (exported.status !== 0) {
        throw new Error(`fresh Blockbench model export failed with ${exported.status}`);
    }

    let completed = 0;
    const total = states.length * views.length;
    for (const state of states) {
        for (const view of views) {
            const target = path.join(outRoot, 'frames', state.id, view);
            const env = {
                ...process.env,
                BB_MODEL: model,
                BB_TEXTURE: texture,
                BB_CONTEXT: 'lumberer',
                BB_VIEW: view,
                BB_CAPTURE: 'preview',
                BB_CARRY_FILL: String(state.carry || 0),
                BB_LIMB_SWING_AMOUNT: state.walking ? '1' : '0',
                BB_STATE: state.id.toUpperCase(),
                BB_EVIDENCE_LABEL: 'CANDIDATE - OFFLINE BLOCKBENCH 5.1.6',
            };
            console.log(`[${completed + 1}/${total}] ${state.id} ${view}`);
            const rendered = spawnSync(process.execPath,
                [renderer, target, ...state.args], {
                    cwd: here, env, encoding: 'utf8',
                    stdio: ['ignore', 'pipe', 'pipe'],
                });
            process.stdout.write(rendered.stdout || '');
            process.stderr.write(rendered.stderr || '');
            if (rendered.status !== 0) {
                throw new Error(`${state.id}/${view} failed with ${rendered.status}`);
            }
            completed++;
        }
    }
    console.log(`matrix complete: ${outRoot}`);
} finally {
    // tempRoot is created by mkdtemp immediately above and contains only the
    // generated transient model. Evidence remains under outRoot.
    fs.rmSync(tempRoot, { recursive: true, force: true });
}

// Produce deterministic, human-readable evidence for the detached work
// container's world-lock transform. This mirrors SettlerModel's renderer
// transform and its independent JUnit round-trip, but never claims an in-game
// render. The SVG makes actor translation/yaw changes visible while the
// container pivot and heading remain fixed in world space.
import * as fs from 'node:fs';
import * as path from 'node:path';

const outDir = path.resolve(process.argv[2] || 'world-anchor-evidence');
fs.mkdirSync(outDir, { recursive: true });

const PIXELS_PER_BLOCK = 16;
const DEPTH_CENTER_BLOCKS = 3 / PIXELS_PER_BLOCK;
const placedAnchor = { x: 4.5, z: -1.5 };
const scenarios = [
    { name: 'START', actorX: 0, actorZ: 0, yaw: 0 },
    { name: 'TURN RIGHT', actorX: 0, actorZ: 0, yaw: 90 },
    { name: 'WALK + TURN', actorX: 1.25, actorZ: -0.75, yaw: 135 },
    { name: 'WALK AWAY', actorX: -3.5, actorZ: 2.125, yaw: -135 },
];

const rotate = (x, z, radians) => {
    const cos = Math.cos(radians);
    const sin = Math.sin(radians);
    return { x: cos * x + sin * z, z: -sin * x + cos * z };
};

function worldAnchoredContainerTransform(worldDx, worldDz, bodyYawDegrees) {
    const rendererYaw = (180 - bodyYawDegrees) * Math.PI / 180;
    const cos = Math.cos(rendererYaw);
    const sin = Math.sin(rendererYaw);
    const centredWorldDz = worldDz + DEPTH_CENTER_BLOCKS;
    return {
        xPixels: (-cos * worldDx + sin * centredWorldDz) * PIXELS_PER_BLOCK,
        zPixels: (sin * worldDx + cos * centredWorldDz) * PIXELS_PER_BLOCK,
        yRotRadians: -bodyYawDegrees * Math.PI / 180,
    };
}

function rendererWorldPivot(transform, bodyYawDegrees, actorX, actorZ) {
    const rendererYaw = (180 - bodyYawDegrees) * Math.PI / 180;
    const local = rotate(-transform.xPixels / PIXELS_PER_BLOCK,
        transform.zPixels / PIXELS_PER_BLOCK, rendererYaw);
    return { x: actorX + local.x, z: actorZ + local.z };
}

function rendererWorldHeading(transform, bodyYawDegrees) {
    const child = rotate(0, 1, transform.yRotRadians);
    const rendererYaw = (180 - bodyYawDegrees) * Math.PI / 180;
    return rotate(-child.x, child.z, rendererYaw);
}

const expectedPivot = {
    x: placedAnchor.x,
    z: placedAnchor.z + DEPTH_CENTER_BLOCKS,
};

function evaluateScenario(scenario) {
    const transform = worldAnchoredContainerTransform(
        placedAnchor.x - scenario.actorX,
        placedAnchor.z - scenario.actorZ,
        scenario.yaw);
    const pivot = rendererWorldPivot(transform, scenario.yaw,
        scenario.actorX, scenario.actorZ);
    const heading = rendererWorldHeading(transform, scenario.yaw);
    return {
        ...scenario,
        transform,
        pivot,
        heading,
        pivotResidual: Math.hypot(pivot.x - expectedPivot.x,
            pivot.z - expectedPivot.z),
        headingResidual: Math.hypot(heading.x, heading.z + 1),
    };
}

const evaluated = scenarios.map(evaluateScenario);
let maxPivotResidual = 0;
let maxHeadingResidual = 0;
let sweepCases = 0;
for (let yaw = -180; yaw <= 180; yaw += 15) {
    for (const position of scenarios.map(({ actorX, actorZ }) =>
        ({ actorX, actorZ }))) {
        const result = evaluateScenario({
            name: 'SWEEP', actorX: position.actorX,
            actorZ: position.actorZ, yaw,
        });
        maxPivotResidual = Math.max(maxPivotResidual, result.pivotResidual);
        maxHeadingResidual = Math.max(maxHeadingResidual,
            result.headingResidual);
        sweepCases++;
    }
}
if (maxPivotResidual > 1e-9 || maxHeadingResidual > 1e-9) {
    throw new Error(`world-lock residual exceeded tolerance: pivot=${
        maxPivotResidual}, heading=${maxHeadingResidual}`);
}

const esc = value => String(value).replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const fmt = value => Math.abs(value) < 5e-10 ? '0.000' : value.toFixed(3);
const panelWidth = 330;
const panelHeight = 330;
const margin = 30;
const chartOriginX = 32;
const chartOriginY = 100;
const chartSize = 265;
const scale = 23;

function point(panelIndex, x, z) {
    const left = margin + panelIndex * (panelWidth + 18);
    return {
        x: left + chartOriginX + chartSize / 2 + x * scale,
        y: chartOriginY + chartSize / 2 + z * scale,
    };
}

const svgParts = [];
svgParts.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${
    margin * 2 + panelWidth * 4 + 18 * 3}" height="470" viewBox="0 0 ${
    margin * 2 + panelWidth * 4 + 18 * 3} 470">`);
svgParts.push(`<rect width="100%" height="100%" fill="#11151b"/>`);
svgParts.push(`<style>
text{font-family:Consolas,monospace;fill:#edf1f5}.title{font-size:22px;font-weight:700}.sub{font-size:13px;fill:#b9c3cd}.panel{fill:#171d25;stroke:#35404d;stroke-width:1}.grid{stroke:#29323d;stroke-width:1}.actor{fill:#7bb7ff;stroke:#c5e1ff;stroke-width:2}.frame{fill:#a96d31;stroke:#ffd28b;stroke-width:2}.ok{fill:#75db8f;font-size:13px;font-weight:700}.small{font-size:11px;fill:#c5cdd6}</style>`);
svgParts.push(`<text x="${margin}" y="35" class="title">DETACHED LUMBER FRAME — WORLD-LOCK MATRIX</text>`);
svgParts.push(`<text x="${margin}" y="59" class="sub">OFFLINE DETERMINISTIC EVIDENCE — NOT NATIVE IN-GAME APPROVAL</text>`);

evaluated.forEach((result, index) => {
    const left = margin + index * (panelWidth + 18);
    svgParts.push(`<rect x="${left}" y="76" width="${panelWidth}" height="350" rx="9" class="panel"/>`);
    for (let grid = -5; grid <= 5; grid++) {
        const a = point(index, grid, -5);
        const b = point(index, grid, 5);
        const c = point(index, -5, grid);
        const d = point(index, 5, grid);
        svgParts.push(`<line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" class="grid"/>`);
        svgParts.push(`<line x1="${c.x}" y1="${c.y}" x2="${d.x}" y2="${d.y}" class="grid"/>`);
    }
    const actor = point(index, result.actorX, result.actorZ);
    const actorAngle = result.yaw * Math.PI / 180;
    const arrowX = actor.x + Math.sin(actorAngle) * 24;
    const arrowY = actor.y - Math.cos(actorAngle) * 24;
    svgParts.push(`<circle cx="${actor.x}" cy="${actor.y}" r="9" class="actor"/>`);
    svgParts.push(`<line x1="${actor.x}" y1="${actor.y}" x2="${arrowX}" y2="${arrowY}" stroke="#c5e1ff" stroke-width="4"/>`);
    const frame = point(index, result.pivot.x, result.pivot.z);
    svgParts.push(`<rect x="${frame.x - 10}" y="${frame.y - 16}" width="20" height="32" rx="3" class="frame"/>`);
    svgParts.push(`<line x1="${frame.x}" y1="${frame.y}" x2="${frame.x}" y2="${frame.y + 26}" stroke="#ffd28b" stroke-width="3"/>`);
    svgParts.push(`<text x="${left + 14}" y="99" class="small">${esc(result.name)} · actor (${fmt(result.actorX)}, ${fmt(result.actorZ)}) · yaw ${result.yaw}°</text>`);
    svgParts.push(`<text x="${left + 14}" y="397" class="small">local frame px (${fmt(result.transform.xPixels)}, ${fmt(result.transform.zPixels)})</text>`);
    svgParts.push(`<text x="${left + 14}" y="415" class="ok">LOCKED · pivot error ${result.pivotResidual.toExponential(1)}</text>`);
});
svgParts.push(`<text x="${margin}" y="455" class="sub">Sweep: ${sweepCases} actor-position/yaw cases · max pivot residual ${maxPivotResidual.toExponential(1)} · max heading residual ${maxHeadingResidual.toExponential(1)} · frame pivot stays (${fmt(expectedPivot.x)}, ${fmt(expectedPivot.z)})</text>`);
svgParts.push('</svg>');

const report = {
    evidenceType: 'offline deterministic world-lock matrix',
    approvalStatus: 'NOT_NATIVE_IN_GAME_APPROVED',
    sourceContract: 'SettlerModel.worldAnchoredContainerTransform',
    placedAnchor,
    authoredDepthCenterBlocks: DEPTH_CENTER_BLOCKS,
    expectedRenderedPivot: expectedPivot,
    sweep: { cases: sweepCases, maxPivotResidual, maxHeadingResidual },
    scenarios: evaluated,
};
fs.writeFileSync(path.join(outDir, 'world-lock-matrix.svg'), svgParts.join('\n'));
fs.writeFileSync(path.join(outDir, 'world-lock-matrix.json'),
    `${JSON.stringify(report, null, 2)}\n`);
console.log(`wrote ${path.join(outDir, 'world-lock-matrix.svg')}`);
console.log(`wrote ${path.join(outDir, 'world-lock-matrix.json')}`);
console.log(`validated ${sweepCases} cases; max residuals pivot=${
    maxPivotResidual}, heading=${maxHeadingResidual}`);

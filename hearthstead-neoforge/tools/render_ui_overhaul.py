#!/usr/bin/env python3
"""Render and verify the Hearthstead UI overhaul without launching Minecraft.

The overhaul specs deliberately use the same sprite atlas, tokens and vanilla
font implementation as ``ui_preview.py``.  This runner adds the release-facing
parts that a one-off preview command does not provide:

* every JSON spec in ``tools/ui/overhaul`` is rendered in a stable order;
* any overflow or invalid nine-slice geometry fails the run;
* every image is rendered twice and must be byte-identical;
* individual PNGs, a manifest and a labelled contact sheet are produced.

It is still a preflight, not a substitute for the eventual native-client gate.
Its purpose is to reject weak composition before runtime code is touched.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from PIL import Image

import mcfont
import ui_preview


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
DEFAULT_SPEC_DIR = HERE / "ui" / "overhaul"
DEFAULT_OUT_DIR = PROJECT / "build" / "ui-overhaul"
DEVELOPMENT_RENDERER = HERE / "render_development_concepts.py"
DEVELOPMENT_REPORT_NAME = "development_concepts_report.json"
APPROVED_DEVELOPMENT_PREVIEWS = (
    (
        "concept_a_living_settlement_tree_overview.png",
        "Settlement Development — Living Tree Overview",
    ),
    (
        "concept_a_living_settlement_tree_hover_warehouse.png",
        "Settlement Development — Warehouse Hover",
    ),
    (
        "concept_a_living_settlement_tree_pinned_guild.png",
        "Settlement Development — Guild Inspector",
    ),
    (
        "concept_a_living_settlement_tree_learned_lumber_recipe.png",
        "Settlement Development — Learned Lumber Recipe",
    ),
)
SHEET_BACKGROUND = (12, 12, 12, 255)
CAPTION_BACKGROUND = (26, 26, 26, 255)
CAPTION_COLOUR = (232, 224, 208, 255)
SHEET_GAP = 16
CAPTION_LOGICAL_H = 16
MAX_TILE_WIDTH = 900


def gallery_html(records: list[dict]) -> str:
    """Build a dependency-free local gallery for reviewing every preview state."""
    previews = [
        {
            "name": record["name"],
            "src": f'individual/{Path(record["output"]).name}',
            "logical": f'{record["logicalWidth"]} x {record["logicalHeight"]}',
            "sha": record["pixelSha256"][:12],
        }
        for record in records
    ]
    data = json.dumps(previews, ensure_ascii=False).replace("</", "<\\/")
    return f"""<!doctype html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
  <title>Hearthstead UI Review</title>
  <style>
    :root {{ color-scheme: dark; --oak:#17130f; --panel:#252129; --iron:#393642;
      --brass:#b78a2b; --cream:#eee4d1; --muted:#aba291; --good:#57bf69; }}
    * {{ box-sizing:border-box; }}
    body {{ margin:0; min-height:100vh; background:radial-gradient(circle at 50% 0,#2d2519,#0d0c0b 58%);
      color:var(--cream); font:14px/1.35 system-ui,sans-serif; }}
    header {{ height:64px; display:flex; align-items:center; justify-content:space-between; gap:18px;
      padding:0 22px; border-bottom:1px solid #4e3b1d; background:#14110ee8; position:sticky; top:0; z-index:2; }}
    h1 {{ margin:0; font-family:Georgia,serif; font-size:20px; letter-spacing:.02em; }}
    .status {{ color:var(--good); font-weight:700; }}
    main {{ display:grid; grid-template-columns:260px minmax(0,1fr); min-height:calc(100vh - 64px); }}
    nav {{ padding:16px; border-right:1px solid #4e3b1d; background:#12100ed9; overflow:auto; }}
    .thumb {{ width:100%; text-align:left; display:grid; grid-template-columns:80px 1fr; gap:10px;
      align-items:center; padding:8px; margin:0 0 8px; color:var(--cream); background:var(--panel);
      border:1px solid #494550; cursor:pointer; }}
    .thumb:hover,.thumb[aria-current=true] {{ border-color:var(--brass); background:#332b20; }}
    .thumb img {{ width:80px; height:46px; object-fit:contain; image-rendering:pixelated; background:#090909; }}
    .thumb small {{ display:block; color:var(--muted); margin-top:4px; }}
    section {{ min-width:0; padding:18px 22px 30px; display:flex; flex-direction:column; }}
    .bar {{ display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:14px; }}
    button {{ min-height:34px; padding:6px 12px; border:1px solid #6b5325; color:var(--cream);
      background:#30281c; cursor:pointer; font-weight:700; }}
    button:hover {{ background:#44351d; border-color:var(--brass); }}
    .meta {{ margin-left:auto; color:var(--muted); }}
    .stage {{ flex:1; min-height:420px; display:grid; place-items:center; overflow:auto; padding:24px;
      border:1px solid #514d59; background:linear-gradient(45deg,#141414 25%,#181818 25%,#181818 50%,#141414 50%,#141414 75%,#181818 75%); background-size:24px 24px; }}
    #preview {{ max-width:100%; max-height:calc(100vh - 160px); image-rendering:pixelated;
      filter:drop-shadow(0 16px 28px #000c); transform-origin:center; }}
    .hint {{ color:var(--muted); margin:12px 0 0; }}
    @media(max-width:760px) {{ main {{ grid-template-columns:1fr; }} nav {{ display:flex; gap:8px; border-right:0;
      border-bottom:1px solid #4e3b1d; }} .thumb {{ min-width:210px; margin:0; }} .stage {{ min-height:350px; }} }}
  </style>
</head>
<body>
  <header><div><h1>Hearthstead UI Review</h1><div>Tool-rendered design gate — Minecraft stays closed</div></div>
    <div class=\"status\">STRICT · DETERMINISTIC</div></header>
  <main><nav id=\"nav\" aria-label=\"Preview states\"></nav>
    <section><div class=\"bar\"><button id=\"prev\">← Previous</button><button id=\"next\">Next →</button>
      <button id=\"fit\">Fit</button><button id=\"one\">1:1</button><strong id=\"title\"></strong><span class=\"meta\" id=\"meta\"></span></div>
      <div class=\"stage\"><img id=\"preview\" alt=\"Selected Hearthstead UI preview\"></div>
      <p class=\"hint\">Use ←/→ to change state. Fit and 1:1 never alter the source pixels.</p></section></main>
  <script>
    const previews={data}; let index=0; const nav=document.querySelector('#nav'); const image=document.querySelector('#preview');
    function select(next) {{ index=(next+previews.length)%previews.length; const p=previews[index]; image.src=p.src;
      document.querySelector('#title').textContent=p.name; document.querySelector('#meta').textContent=`${{p.logical}} · ${{p.sha}}`;
      [...nav.children].forEach((b,i)=>b.setAttribute('aria-current',i===index)); fit(); }}
    function fit() {{ image.style.width='auto'; image.style.maxWidth='100%'; image.style.maxHeight='calc(100vh - 160px)'; }}
    function one() {{ image.style.width=image.naturalWidth+'px'; image.style.maxWidth='none'; image.style.maxHeight='none'; }}
    previews.forEach((p,i)=>{{ const b=document.createElement('button'); b.className='thumb';
      b.innerHTML=`<img src=\"${{p.src}}\" alt=\"\"><span>${{escapeHtml(p.name)}}<small>${{p.logical}}</small></span>`;
      b.onclick=()=>select(i); nav.appendChild(b); }});
    function escapeHtml(value) {{ const d=document.createElement('div'); d.textContent=value; return d.innerHTML; }}
    document.querySelector('#prev').onclick=()=>select(index-1); document.querySelector('#next').onclick=()=>select(index+1);
    document.querySelector('#fit').onclick=fit; document.querySelector('#one').onclick=one;
    addEventListener('keydown',e=>{{ if(e.key==='ArrowLeft') select(index-1); if(e.key==='ArrowRight') select(index+1); }});
    if(previews.length) select(0);
    if(new URLSearchParams(location.search).has('selftest')) {{
      const first=image.getAttribute('src'); document.querySelector('#next').click();
      const nextWorked=image.getAttribute('src')!==first;
      document.querySelector('#prev').click(); const previousWorked=image.getAttribute('src')===first;
      dispatchEvent(new KeyboardEvent('keydown',{{key:'ArrowRight'}}));
      const keyboardWorked=image.getAttribute('src')!==first;
      const result=nextWorked&&previousWorked&&keyboardWorked?'PASS':'FAIL';
      document.documentElement.dataset.gallerySelftest=result;
      document.title=`${{result}} — Hearthstead UI Review`;
    }}
  </script>
</body></html>"""


def png_sha256(image: Image.Image) -> str:
    """Hash exact RGBA pixels plus dimensions, independent of PNG metadata."""
    digest = hashlib.sha256()
    digest.update(image.width.to_bytes(4, "big"))
    digest.update(image.height.to_bytes(4, "big"))
    digest.update(image.convert("RGBA").tobytes())
    return digest.hexdigest()


def development_pixel_sha256(image: Image.Image) -> str:
    """Match the source concept renderer's exact pixel-hash contract."""
    digest = hashlib.sha256()
    digest.update(image.mode.encode("ascii"))
    digest.update(f"{image.width}x{image.height}".encode("ascii"))
    digest.update(image.tobytes())
    return digest.hexdigest()


def render_development_verified(
    out_dir: Path, individual: Path, scale: int
) -> list[tuple[dict, Image.Image]]:
    """Render and import only the approved Living Settlement Tree states.

    The dedicated renderer remains authoritative for its geometry, source and
    recipe contracts.  This unified pass executes it in strict/all mode, then
    independently checks the report and every saved image before the files are
    allowed into the manifest, gallery or contact sheet.
    """
    if not DEVELOPMENT_RENDERER.is_file():
        raise ValueError(f"Development renderer is missing: {DEVELOPMENT_RENDERER}")

    concept_dir = out_dir / "concepts"
    command = [
        sys.executable,
        str(DEVELOPMENT_RENDERER),
        "--out-dir",
        str(concept_dir),
        "--scale",
        str(scale),
        "--strict",
    ]
    result = subprocess.run(
        command,
        cwd=PROJECT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        details = "\n".join(
            part.strip() for part in (result.stdout, result.stderr) if part.strip()
        )
        raise ValueError(
            "strict Development concept render failed"
            + (f":\n{details}" if details else "")
        )

    report_path = concept_dir / DEVELOPMENT_REPORT_NAME
    if not report_path.is_file():
        raise ValueError(f"Development report is missing after render: {report_path}")
    with report_path.open(encoding="utf-8") as handle:
        report = json.load(handle)

    report_issues: list[str] = []
    if report.get("render_selection") != "all":
        report_issues.append("report does not cover the complete approved state set")
    if report.get("strict_pass") is not True:
        report_issues.append("report strict_pass is not true")
    for key in (
        "geometry_issues",
        "source_contract_issues",
        "recipe_contract_issues",
    ):
        if report.get(key):
            report_issues.append(f"{key} is not empty")
    reported_outputs = report.get("outputs")
    if not isinstance(reported_outputs, dict):
        report_issues.append("outputs is missing or invalid")
        reported_outputs = {}
    if report_issues:
        raise ValueError(
            "Development report failed unified validation:\n  - "
            + "\n  - ".join(report_issues)
        )

    imported: list[tuple[dict, Image.Image]] = []
    for filename, display_name in APPROVED_DEVELOPMENT_PREVIEWS:
        source_record = reported_outputs.get(filename)
        if not isinstance(source_record, dict):
            raise ValueError(
                f"approved Development output is absent from strict report: {filename}"
            )
        if source_record.get("deterministic_double_render") is not True:
            raise ValueError(f"Development output is not deterministic: {filename}")
        if source_record.get("text_warnings"):
            raise ValueError(f"Development output has strict warnings: {filename}")
        if source_record.get("scale") != scale:
            raise ValueError(
                f"Development output scale mismatch for {filename}: "
                f"expected {scale}, got {source_record.get('scale')}"
            )

        source = concept_dir / filename
        if not source.is_file():
            raise ValueError(f"approved Development output is missing: {source}")
        with Image.open(source) as opened:
            opened.load()
            source_mode = opened.mode
            source_image = opened.copy()
        expected_size = source_record.get("output_size")
        if expected_size != [source_image.width, source_image.height]:
            raise ValueError(
                f"Development output size mismatch for {filename}: "
                f"report={expected_size}, file={list(source_image.size)}"
            )
        expected_source_hash = source_record.get("pixel_sha256")
        actual_source_hash = development_pixel_sha256(source_image)
        if expected_source_hash != actual_source_hash:
            raise ValueError(
                f"Development output hash mismatch for {filename}: "
                f"report={expected_source_hash}, file={actual_source_hash} "
                f"(mode={source_mode})"
            )

        logical_size = source_record.get("logical_size")
        if (
            not isinstance(logical_size, list)
            or len(logical_size) != 2
            or not all(isinstance(value, int) and value > 0 for value in logical_size)
            or source_image.size
            != (logical_size[0] * scale, logical_size[1] * scale)
        ):
            raise ValueError(
                f"Development logical size is invalid for {filename}: {logical_size}"
            )

        image = source_image.convert("RGBA")
        output = individual / filename
        image.save(output)
        record = {
            "name": display_name,
            "spec": f"tools/render_development_concepts.py#{filename}",
            "sourceOutput": str(source.relative_to(PROJECT)).replace("\\", "/"),
            "logicalWidth": logical_size[0],
            "logicalHeight": logical_size[1],
            "scale": scale,
            "pixelWidth": image.width,
            "pixelHeight": image.height,
            "pixelSha256": png_sha256(image),
            "sourcePixelSha256": expected_source_hash,
            "strictWarnings": 0,
            "deterministic": True,
            "output": str(output.relative_to(PROJECT)).replace("\\", "/"),
        }
        imported.append((record, image))
    return imported


def render_verified(spec_path: Path, scale: int) -> tuple[Image.Image, dict]:
    with spec_path.open(encoding="utf-8") as handle:
        spec = json.load(handle)

    ui_preview.WARNINGS.clear()
    first = ui_preview.render(spec, scale=scale, guides=False).convert("RGBA")
    first_warnings = tuple(ui_preview.WARNINGS)

    ui_preview.WARNINGS.clear()
    second = ui_preview.render(spec, scale=scale, guides=False).convert("RGBA")
    second_warnings = tuple(ui_preview.WARNINGS)

    warnings = first_warnings + tuple(
        warning for warning in second_warnings if warning not in first_warnings
    )
    if warnings:
        formatted = "\n".join(f"  - {warning}" for warning in warnings)
        raise ValueError(f"{spec_path.name} failed strict preview:\n{formatted}")
    if first.size != second.size or first.tobytes() != second.tobytes():
        raise ValueError(f"{spec_path.name} is not deterministic across two renders")

    record = {
        "name": spec.get("name", spec_path.stem),
        "spec": str(spec_path.relative_to(PROJECT)).replace("\\", "/"),
        "logicalWidth": int(spec["width"]),
        "logicalHeight": int(spec["height"]),
        "scale": scale,
        "pixelWidth": first.width,
        "pixelHeight": first.height,
        "pixelSha256": png_sha256(first),
        "strictWarnings": 0,
        "deterministic": True,
    }
    evidence = spec.get("evidence")
    if isinstance(evidence, dict):
        # Keep provenance next to the pixels.  These fields describe a static
        # design/layout preview only; they are never a native-client claim.
        record["evidence"] = {
            key: evidence[key]
            for key in (
                "type",
                "nativeEvidence",
                "phase",
                "screen",
                "viewport",
                "zoom",
                "locale",
                "baseline",
            )
            if key in evidence
        }
    return first, record


def caption_image(label: str, width: int, scale: int) -> Image.Image:
    logical_w = max(1, width // scale)
    logical = Image.new("RGBA", (logical_w, CAPTION_LOGICAL_H), CAPTION_BACKGROUND)
    font = mcfont.McFont.load()
    available = max(1, logical_w - 8)
    shown = label
    if font.width(shown) > available:
        ellipsis = "..."
        while shown and font.width(shown + ellipsis) > available:
            shown = shown[:-1]
        shown += ellipsis
    font.draw(logical, 4, 4, shown, CAPTION_COLOUR, shadow=True)
    return logical.resize((logical_w * scale, CAPTION_LOGICAL_H * scale), Image.NEAREST)


def fit_tile(image: Image.Image) -> Image.Image:
    if image.width <= MAX_TILE_WIDTH:
        return image
    ratio = MAX_TILE_WIDTH / image.width
    target_h = max(1, round(image.height * ratio))
    return image.resize((MAX_TILE_WIDTH, target_h), Image.NEAREST)


def contact_sheet(rendered: list[tuple[str, Image.Image]], scale: int) -> Image.Image:
    tiles: list[Image.Image] = []
    for label, original in rendered:
        image = fit_tile(original)
        caption = caption_image(label, image.width, scale)
        tile = Image.new(
            "RGBA", (image.width, caption.height + image.height), SHEET_BACKGROUND
        )
        tile.alpha_composite(caption, (0, 0))
        tile.alpha_composite(image, (0, caption.height))
        tiles.append(tile)

    if not tiles:
        raise ValueError("no overhaul previews were rendered")

    # Two columns make the common 256/512-wide panels comparable. Very wide
    # Development maps remain readable because MAX_TILE_WIDTH bounds one tile,
    # not the entire sheet.
    column_width = max(tile.width for tile in tiles)
    rows = (len(tiles) + 1) // 2
    row_heights = []
    for row in range(rows):
        row_tiles = tiles[row * 2:row * 2 + 2]
        row_heights.append(max(tile.height for tile in row_tiles))
    sheet_w = column_width * 2 + SHEET_GAP * 3
    sheet_h = sum(row_heights) + SHEET_GAP * (rows + 1)
    sheet = Image.new("RGBA", (sheet_w, sheet_h), SHEET_BACKGROUND)

    y = SHEET_GAP
    for row, row_h in enumerate(row_heights):
        for column, tile in enumerate(tiles[row * 2:row * 2 + 2]):
            x = SHEET_GAP + column * (column_width + SHEET_GAP)
            sheet.alpha_composite(tile, (x, y))
        y += row_h + SHEET_GAP
    return sheet


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec-dir", type=Path, default=DEFAULT_SPEC_DIR)
    parser.add_argument(
        "--spec",
        action="append",
        type=Path,
        help="render one selected JSON spec; may be repeated",
    )
    parser.add_argument(
        "--skip-development",
        action="store_true",
        help="do not append the concept renderer's Development outputs",
    )
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--scale", type=int, default=2)
    args = parser.parse_args()

    if args.scale < 1:
        parser.error("--scale must be at least 1")
    spec_dir = args.spec_dir.resolve()
    out_dir = args.out_dir.resolve()
    specs: list[Path] = []
    candidates = [candidate.resolve() for candidate in args.spec] if args.spec \
        else sorted(spec_dir.glob("*.json"))
    for candidate in candidates:
        with candidate.open(encoding="utf-8") as handle:
            candidate_data = json.load(handle)
        # The overhaul directory also contains machine-readable design
        # contracts (for example the all-profession attribute matrix). Only
        # canvas specs belong in the image pass; malformed JSON still fails
        # above instead of being silently ignored.
        if all(key in candidate_data for key in ("width", "height", "elements")):
            specs.append(candidate)
    if not specs:
        parser.error(f"no JSON specs found in {spec_dir}")

    individual = out_dir / "individual"
    individual.mkdir(parents=True, exist_ok=True)
    records: list[dict] = []
    rendered: list[tuple[str, Image.Image]] = []
    for spec_path in specs:
        image, record = render_verified(spec_path, args.scale)
        output = individual / f"{spec_path.stem}.png"
        image.save(output)
        record["output"] = str(output.relative_to(PROJECT)).replace("\\", "/")
        records.append(record)
        rendered.append((record["name"], image))
        print(f"PASS  {spec_path.name} -> {output}")

    if not args.skip_development:
        for record, image in render_development_verified(
            out_dir, individual, args.scale
        ):
            records.append(record)
            rendered.append((record["name"], image))
            print(f"PASS  {record['spec']} -> {PROJECT / record['output']}")

    sheet = contact_sheet(rendered, args.scale)
    sheet_path = out_dir / "hearthstead-ui-overhaul-contact-sheet.png"
    sheet.save(sheet_path)
    manifest = {
        "schema": 1,
        "renderer": "tools/render_ui_overhaul.py",
        "specDirectory": str(spec_dir.relative_to(PROJECT)).replace("\\", "/"),
        "strict": True,
        "allDeterministic": True,
        "approvedDevelopmentPreviews": [] if args.skip_development else [
            filename for filename, _ in APPROVED_DEVELOPMENT_PREVIEWS
        ],
        "contactSheet": str(sheet_path.relative_to(PROJECT)).replace("\\", "/"),
        "previews": records,
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    gallery_path = out_dir / "index.html"
    gallery_path.write_text(gallery_html(records), encoding="utf-8")
    print(f"PASS  contact sheet -> {sheet_path}")
    print(f"PASS  manifest -> {manifest_path}")
    print(f"PASS  review gallery -> {gallery_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

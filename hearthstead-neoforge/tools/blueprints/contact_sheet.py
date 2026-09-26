#!/usr/bin/env python3
"""Composite blueprint renders onto a labelled contact sheet (+ per-blueprint
previews with a background).

    python tools/blueprints/contact_sheet.py <render_dir> <out_dir>
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.join(HERE, 'out', 'report.json')
BG = (236, 228, 210)
INK = (52, 40, 28)
MUTED = (120, 104, 84)
OK = (58, 110, 52)
BAD = (170, 50, 40)
CAT_ORDER = ['homes', 'work', 'common', 'military', 'defense']


def font(sz, bold=False):
    for f in (('segoeuib.ttf' if bold else 'segoeui.ttf'), ('arialbd.ttf' if bold else 'arial.ttf')):
        try:
            return ImageFont.truetype(os.path.join('C:/Windows/Fonts', f), sz)
        except Exception:
            continue
    return ImageFont.load_default()


def bg_compose(im, w, h):
    im = im.convert('RGBA')
    bbox = im.getbbox()
    if bbox:
        im = im.crop(bbox)
    im.thumbnail((w - 20, h - 20), Image.LANCZOS)
    card = Image.new('RGBA', (w, h), BG + (255,))
    card.alpha_composite(im, ((w - im.width) // 2, (h - im.height) // 2))
    return card


def main():
    rdir, odir = sys.argv[1], sys.argv[2]
    os.makedirs(odir, exist_ok=True)
    rep = {r['id']: r for r in json.load(open(REPORT))}
    ids = [i for i in rep if os.path.exists(os.path.join(rdir, i + '.png'))]
    ids.sort(key=lambda i: (CAT_ORDER.index(rep[i]['category']) if rep[i]['category'] in CAT_ORDER else 9,
                            str(rep[i]['type']), rep[i]['variant'] != 'small', i))
    # individual previews with a background
    for i in ids:
        im = Image.open(os.path.join(rdir, i + '.png'))
        card = bg_compose(im, 1200, 900)
        d = ImageDraw.Draw(card)
        r = rep[i]
        d.text((28, 22), f"{r['name']}", fill=INK, font=font(40, True))
        d.text((28, 72), f"{i}  |  {r['type'] or r['kind']}  |  {'x'.join(map(str, r['size']))}  |  {r['blocks']} blocks",
               fill=MUTED, font=font(24))
        l1 = r.get('l1', '')
        if l1 in ('PASS', 'FAIL'):
            d.text((28, 104), f"Level 1: {l1} (offline survey)   walk: {r.get('walk', '-')}",
                   fill=OK if l1 == 'PASS' else BAD, font=font(24))
        card.convert('RGB').save(os.path.join(odir, i + '.png'))
    # contact sheet
    cols = 8
    tw, th, lh = 420, 315, 62
    rows = (len(ids) + cols - 1) // cols
    head = 110
    sheet = Image.new('RGB', (cols * tw + 40, rows * (th + lh) + head + 20), BG)
    d = ImageDraw.Draw(sheet)
    d.text((24, 18), 'Bannerhold blueprint catalogue', fill=INK, font=font(52, True))
    d.text((26, 78), f'{len(ids)} blueprints in the Elmfield town palette (oak log frame, oak plank infill, cobblestone plinth, '
                     'oak stair roofs). Furniture shown with Another Furniture.', fill=MUTED, font=font(22))
    for n, i in enumerate(ids):
        r = rep[i]
        x = 20 + (n % cols) * tw
        y = head + (n // cols) * (th + lh)
        im = Image.open(os.path.join(rdir, i + '.png'))
        card = bg_compose(im, tw - 10, th)
        sheet.paste(card.convert('RGB'), (x, y))
        d.text((x + 6, y + th + 2), r['name'], fill=INK, font=font(22, True))
        l1 = r.get('l1', '')
        tag = 'L1 ok' if l1 == 'PASS' else ('L1 FAIL' if l1 == 'FAIL' else r['kind'])
        d.text((x + 6, y + th + 30), f"{i} · {'x'.join(map(str, r['size']))} · {r['blocks']} bl · {tag}",
               fill=OK if l1 == 'PASS' else (BAD if l1 == 'FAIL' else MUTED), font=font(16))
    sheet.save(os.path.join(odir, 'contact_sheet.png'))
    small = sheet.copy()
    small.thumbnail((2400, 2400), Image.LANCZOS)
    small.save(os.path.join(odir, 'contact_sheet_small.png'))
    print('sheet', sheet.size, len(ids))


if __name__ == '__main__' and len(sys.argv) == 3:
    main()


TYPE_NAMES = {'builders_hut': "Builder's Hut", 'hunters_lodge': "Hunter's Lodge", 'dining_hall': 'Dining Hall',
              'lumber_camp': 'Lumber Camp', 'trading_post': 'Trading Post', 'architects_study': "Architect's Study"}


def type_name(t):
    return TYPE_NAMES.get(t, (t or '').replace('_', ' ').title())


def presets_sheet(rdir, odir):
    """One row per building type, one column per preset:
    Elmfield (small), Elmfield (large), Whitewashed Timber, Stone Hall, Rustic Log."""
    rep = json.load(open(REPORT))
    rows = {}
    for r in rep:
        t = r['type'] or ('defense' if r['kind'] in ('defense', 'barricade') else r['kind'])
        if r['kind'] in ('defense', 'barricade') and r['type'] != 'watchtower':
            t = 'walls & barricades'
        rows.setdefault(t, []).append(r)
    order = {'small': 0, 'large': 1}
    sty = {'elmfield': 0, 'timber': 2, 'stone': 3, 'rustic': 4}

    def key(r):
        s = r.get('style')
        return (sty.get(s, 5), order.get(r['variant'], 2), r['id'])
    cat = {r['type']: r['category'] for r in rep if r['type']}
    types = sorted(rows, key=lambda t: (CAT_ORDER.index(cat[t]) if cat.get(t) in CAT_ORDER else 8, t))
    cols = 6
    tw, th, lh, name_w = 380, 285, 58, 300
    head = 130
    W = name_w + cols * tw + 30
    H = head + len(types) * (th + lh) + 20
    sheet = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(sheet)
    d.text((24, 20), 'Bannerhold building presets', fill=INK, font=font(56, True))
    d.text((26, 86), 'Per building: Elmfield town palette (small, large) + Whitewashed Timber + Stone Hall + Rustic Log. '
                     'Label: Type – Preset – footprint – blocks. All typed presets pass the offline level-1 survey.',
           fill=MUTED, font=font(22))
    for ri, t in enumerate(types):
        y = head + ri * (th + lh)
        d.line([(20, y - 6), (W - 20, y - 6)], fill=(210, 198, 176), width=2)
        d.text((24, y + th // 2 - 40), type_name(t), fill=INK, font=font(30, True))
        items = sorted(rows[t], key=key)
        d.text((24, y + th // 2), f'{len(items)} presets', fill=MUTED, font=font(20))
        for ci, r in enumerate(items[:cols]):
            x = name_w + ci * tw
            p = os.path.join(rdir, r['id'] + '.png')
            if not os.path.exists(p):
                continue
            card = bg_compose(Image.open(p), tw - 12, th)
            sheet.paste(card.convert('RGB'), (x, y))
            pn = r.get('preset') or r['name']
            label = f"{type_name(t)} – {pn}"
            d.text((x + 4, y + th + 2), label[:44], fill=INK, font=font(17, True))
            l1 = r.get('l1', '')
            tag = ' · L1 ok' if l1 == 'PASS' else (' · L1 FAIL' if l1 == 'FAIL' else '')
            fp = f"{r['size'][0]}x{r['size'][2]}"
            d.text((x + 4, y + th + 24), f"{r['name'].split(' (')[0][:26]} · {fp} · {r['blocks']} blocks{tag}",
                   fill=OK if l1 == 'PASS' else (BAD if l1 == 'FAIL' else MUTED), font=font(15))
    sheet.save(os.path.join(odir, 'presets-sheet.png'))
    half = (len(types) + 1) // 2
    cut = head + half * (th + lh)
    top = sheet.crop((0, 0, W, cut))
    top.save(os.path.join(odir, 'presets-sheet-part1.png'))
    bot = Image.new('RGB', (W, H - cut + head), BG)
    bot.paste(sheet.crop((0, 0, W, head)), (0, 0))
    bot.paste(sheet.crop((0, cut, W, H)), (0, head))
    bot.save(os.path.join(odir, 'presets-sheet-part2.png'))
    print('presets sheet', sheet.size, len(types), 'rows')


if __name__ == '__main__' and len(sys.argv) > 3 and sys.argv[3] == '--presets':
    presets_sheet(sys.argv[1], sys.argv[2])


def concept_sheet(rdir, odir):
    rep = json.load(open(os.path.join(HERE, 'out', 'concepts_report.json')))
    cols, tw, th, lh = 5, 620, 470, 118
    head = 150
    rows = (len(rep) + cols - 1) // cols
    W, H = cols * tw + 40, head + rows * (th + lh) + 20
    sheet = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(sheet)
    d.text((24, 18), 'Bannerhold job buildings: concept pass for approval', fill=INK, font=font(54, True))
    d.text((26, 86), 'Structure follows the job. Open-air lots use the proposed WORK YARD check (bounded lot, job blocks, '
                     'a covered tool shelter); homes-like rooms stay enclosed.', fill=MUTED, font=font(24))
    d.text((26, 116), 'Inspiration only: MineColonies hut identity (no schematics copied). All pass the offline checks.',
           fill=MUTED, font=font(24))
    for n, r in enumerate(rep):
        x = 20 + (n % cols) * tw
        y = head + (n // cols) * (th + lh)
        p = os.path.join(rdir, r['id'] + '.png')
        card = bg_compose(Image.open(p), tw - 14, th)
        sheet.paste(card.convert('RGB'), (x, y))
        mode = 'WORK YARD (proposed)' if r.get('validation') == 'yard' else 'enclosed room'
        d.text((x + 6, y + th + 2), f"{type_name(r['type'])} – {r['name']}", fill=INK, font=font(26, True))
        d.text((x + 6, y + th + 36), f"{r['size'][0]}x{r['size'][2]} lot · {r['blocks']} blocks · {mode} · "
                                     f"L1 {'ok' if r['l1'] == 'PASS' else 'FAIL'}", fill=OK if r['l1'] == 'PASS' else BAD, font=font(19))
        insp = r.get('inspired') or ''
        d.text((x + 6, y + th + 62), insp[:62], fill=MUTED, font=font(17))
        d.text((x + 6, y + th + 84), insp[62:124], fill=MUTED, font=font(17))
    sheet.save(os.path.join(odir, 'concepts-sheet.png'))
    print('concepts sheet', sheet.size)


if __name__ == '__main__' and len(sys.argv) > 3 and sys.argv[3] == '--concepts':
    concept_sheet(sys.argv[1], sys.argv[2])

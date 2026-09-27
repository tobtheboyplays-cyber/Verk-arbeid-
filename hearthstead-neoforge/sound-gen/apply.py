"""Install picked ElevenLabs sounds into the mod and update sounds.json / en_us.json.

    python apply.py --event combat.swing_light
    python apply.py --cat combat --cat ui
    python apply.py --all [--dry]

Files land at assets/hearthstead/sounds/el/<folder>/<name><i>.ogg.
Loudness: the file is normalised to the target LUFS (process.py); the sounds.json
`volume` is then chosen so the event's *effective* loudness (file LUFS + volume dB)
matches what the event played before (the mix the previous sound pass tuned),
plus the target's optional `gain_db` trim. New events use `vol` (default 1.0).
Replaced files are not deleted here: `--prune` moves old hearthstead files that
nothing references any more to WORK/replaced/ (outside the repo).
"""
import argparse, json, os, re, shutil, sys, math
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
from targets import TARGETS  # noqa: E402

REPO = os.path.dirname(HERE)
ASSETS = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead")
SOUNDS_JSON = os.path.join(ASSETS, "sounds.json")
LANG = os.path.join(ASSETS, "lang", "en_us.json")
WORK = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
BEFORE = os.path.join(WORK, "before")
VANILLA = r"C:\Users\tobia\.gradle\caches\neoformruntime\assets"
_vidx = None
_lufs_cache = {}


def vanilla_file(name):
    global _vidx
    if _vidx is None:
        with open(os.path.join(VANILLA, "indexes", "17.json"), encoding="utf-8") as fh:
            _vidx = json.load(fh)["objects"]
    k = "minecraft/sounds/" + name + ".ogg"
    if k not in _vidx:
        return None
    h = _vidx[k]["hash"]
    return os.path.join(VANILLA, "objects", h[:2], h)


# The sound each NEW event replaces (its placeholder alias / FxEffect fallback / old call).
# apply.py matches the new event's effective loudness to this reference, so swapping
# a vanilla stand-in for a bespoke sound never jumps in the mix.
REF = {
    "raider.brute_roar": "minecraft:entity.ravager.roar", "raider.brute_slam": "hearthstead:combat.heavy_impact",
    "raider.bark": "minecraft:entity.pillager.ambient", "raider.hurt": "minecraft:entity.pillager.hurt",
    "raider.death": "minecraft:entity.pillager.death", "settler.hurt": "minecraft:entity.player.hurt",
    "settler.death": "minecraft:entity.player.death", "tavern.clink": "minecraft:block.amethyst_block.hit",
    "tavern.pour": "minecraft:block.brewing_stand.brew", "tavern.drink": "minecraft:entity.generic.drink",
    "builder.place": "minecraft:block.wood.place", "builder.ladder_rung": "hearthstead:nail_tap",
    "event.dog_bark": "minecraft:entity.wolf.ambient", "event.dog_whine": "minecraft:entity.wolf.whine",
    "event.caravan_arrive": "minecraft:entity.llama.ambient", "event.wolf_howl": "minecraft:entity.wolf.howl",
    "event.boar_grunt": "minecraft:entity.pig.ambient", "event.boar_charge": "minecraft:entity.hoglin.angry",
    "event.envoy_fanfare": "hearthstead:settler_recruited", "event.brute_grunt": "minecraft:entity.ravager.roar",
    "event.brute_demand": "minecraft:entity.ravager.roar", "event.fox_yip": "minecraft:entity.fox.screech",
    "event.peddler_bells": "minecraft:entity.wandering_trader.reappeared",
    "raid.won_fanfare": "minecraft:ui.toast.challenge_complete", "raid.lost_toll": "hearthstead:village_bell",
    "summon.horn": "hearthstead:village_bell", "patrol.march": "hearthstead:armour_clink",
    "patrol.halt": "hearthstead:armour_clink", "command_ack.spear": "hearthstead:command_ack",
    "command_ack.archer": "hearthstead:command_ack", "command_ack.mage": "hearthstead:command_ack",
    "combat.execution_stinger.axe": "hearthstead:combat.execution_stinger",
    "combat.execution_stinger.mace": "hearthstead:combat.execution_stinger",
    "combat.execution_stinger.spear": "hearthstead:combat.execution_stinger",
    "combat.execution_stinger.bare": "hearthstead:combat.execution_stinger",
    "work.plate_hammer": "hearthstead:anvil_ring", "work.quern_grind": "hearthstead:knead_press",
    "work.mash_stir": "hearthstead:bellows_puff", "work.quill_scratch": "hearthstead:feather_pinch",
    "work.pestle_grind": "hearthstead:loom_clack", "work.shear_snip": "hearthstead:hide_scrape",
    "work.fish_splash": "hearthstead:water_pour", "work.bow_loose": "minecraft:entity.arrow.shoot",
    "work.ledger_tally": "hearthstead:chest_stow", "work.bar_wipe": "hearthstead:chest_stow",
    "fx.skill_level_up": "minecraft:block.note_block.chime", "fx.building_level_up": "minecraft:block.note_block.chime",
    "fx.warehouse_level_up": "minecraft:block.note_block.chime", "fx.tech_learned": "minecraft:block.note_block.bell",
    "fx.journey_chapter": "minecraft:ui.toast.challenge_complete", "fx.craft_glint": "minecraft:block.amethyst_block.chime",
    "fx.craft_legendary": "minecraft:block.amethyst_block.resonate", "fx.coin_sale": "minecraft:item.armor.equip_gold",
    "fx.build_done": "minecraft:entity.player.levelup", "fx.raid_won": "minecraft:entity.firework_rocket.twinkle_far",
    "fx.summon_arrival": "minecraft:item.armor.equip_chain", "fx.patrol_waypoint": "minecraft:block.amethyst_block.hit",
    "fx.order_confirmed": "hearthstead:ui_confirm", "fx.talk_marker": "hearthstead:ui_confirm",
    "ui.click": "minecraft:ui.button.click", "ui.page_turn": "minecraft:item.book.page_turn",
    "ui.map_ping": "hearthstead:ui_confirm", "convo.pull_in": "hearthstead:ui_open",
    "convo.name_card": "hearthstead:ui_open", "ui.conversation_open": "hearthstead:ui_open", "convo.persuade_ok": "hearthstead:profession_assigned",
    "convo.persuade_fail": "hearthstead:ui_error", "convo.deal": "hearthstead:profession_assigned",
    "event.minstrel_sting": "hearthstead:settler_recruited", "ambient.village_murmur": "hearthstead:tavern_ambience",
    "ambient.market_bustle": "hearthstead:tavern_ambience", "ambient.night_crickets": "hearthstead:tavern_fire",
    "ambient.rain_roof": "hearthstead:tavern_fire", "ambient.workshop_smithy": "hearthstead:tavern_fire",
    "ambient.workshop_wood": "hearthstead:tavern_fire",
    "captain.windup": "minecraft:item.armor.equip_chain", "captain.impact": "hearthstead:combat.swing_heavy",
    "captain.rally": "minecraft:item.goat_horn.sound.1", "captain.promoted": "minecraft:item.goat_horn.sound.5",
}
# Hand overrides where matching the stand-in would be wrong (the vanilla amethyst chime
# used as the glint fallback is ~-58 LUFS effective, i.e. nearly inaudible).
VOL = {"fx.craft_glint": 0.3, "bellows_puff": 0.16, "work.mash_stir": 0.16}
_vsj = None


def vanilla_event_entries(path):
    global _vsj
    if _vsj is None:
        vanilla_file("x")
        h = _vidx["minecraft/sounds.json"]["hash"]
        with open(os.path.join(VANILLA, "objects", h[:2], h), encoding="utf-8") as fh:
            _vsj = json.load(fh)
    ev = _vsj.get(path)
    out = []
    for s in (ev or {}).get("sounds", []):
        s = {"name": s} if isinstance(s, str) else dict(s)
        if s.get("type") == "event":
            continue
        s["name"] = "minecraft:" + s["name"].replace("minecraft:", "")
        s["volume"] = min(1.0, s.get("volume", 1.0))
        out.append(s)
    return out


def ref_effective(ev, before):
    ref = REF.get(ev)
    if not ref:
        return None
    ns, path = ref.split(":", 1)
    if ns == "minecraft":
        return effective_db(vanilla_event_entries(path))
    b = before.get(path)
    return effective_db(b["sounds"]) if b else None


def file_for(name):
    ns, path = name.split(":", 1) if ":" in name else ("minecraft", name)
    if ns == "minecraft":
        return vanilla_file(path)
    for base in (os.path.join(BEFORE, "sounds"), os.path.join(ASSETS, "sounds")):
        p = os.path.join(base, path + ".ogg")
        if os.path.exists(p):
            return p
    return None


def file_lufs(p):
    if p not in _lufs_cache:
        _lufs_cache[p] = A.lufs(A.decode(p))
    return _lufs_cache[p]


def effective_db(entries):
    """Power-average of (file LUFS + 20log10 volume) over an event's file entries."""
    vals = []
    for e in entries:
        if isinstance(e, str):
            e = {"name": e}
        if e.get("type") == "event":
            ns, path = e["name"].split(":", 1) if ":" in e["name"] else ("minecraft", e["name"])
            if ns == "minecraft":
                sub = effective_db(vanilla_event_entries(path))
                if sub is not None:
                    vals.append(sub + 20 * math.log10(max(1e-4, e.get("volume", 1.0))))
            continue
        p = file_for(e["name"])
        if not p:
            continue
        vals.append(file_lufs(p) + 20 * math.log10(max(1e-4, e.get("volume", 1.0))))
    if not vals:
        return None
    return 10 * math.log10(np.mean([10 ** (v / 10) for v in vals]))


def load_json(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    crlf = b"\r\n" in raw
    return json.loads(raw.decode("utf-8")), crlf


def _top_spans(text):
    """(key, value_start, value_end) for each top-level member of a JSON object text."""
    dec = json.JSONDecoder()
    i = text.index("{") + 1
    out = []
    while True:
        m = re.compile(r'\s*(,)?\s*').match(text, i)
        i = m.end()
        if text[i] == "}":
            return out, i
        key, i = dec.raw_decode(text, i)
        i = re.compile(r'\s*:\s*').match(text, i).end()
        _, j = dec.raw_decode(text, i)
        out.append((key, i, j))
        i = j


def _fmt_entry(v, nl):
    lines = []
    for s in v["sounds"]:
        lines.append("      { " + ", ".join(f"{json.dumps(a)}: {json.dumps(b)}" for a, b in s.items()) + " }")
    parts = ['    "sounds": [' + nl + ("," + nl).join(lines) + nl + "    ]"]
    for a, b in v.items():
        if a != "sounds":
            parts.append(f"    {json.dumps(a)}: {json.dumps(b)}")
    return "{" + nl + ("," + nl).join(parts) + nl + "  }"


def dump_sounds(d, crlf, changed=None):
    """Rewrite only the changed events' blocks in place; append new events at the end."""
    nl = "\r\n" if crlf else "\n"
    with open(SOUNDS_JSON, "rb") as fh:
        text = fh.read().decode("utf-8")
    spans, close = _top_spans(text)
    have = {k for k, _, _ in spans}
    changed = set(d) if changed is None else set(changed)
    for k, a, b in reversed(spans):
        if k in changed:
            text = text[:a] + _fmt_entry(d[k], nl) + text[b:]
    spans, close = _top_spans(text)
    add = [k for k in d if k in changed and k not in have]
    if add:
        last_end = spans[-1][2]
        ins = "".join(f",{nl}  {json.dumps(k)}: " + _fmt_entry(d[k], nl) for k in add)
        text = text[:last_end] + ins + text[last_end:]
    with open(SOUNDS_JSON, "wb") as fh:
        fh.write(text.encode("utf-8"))


def apply_lang(new_subs):
    with open(LANG, "rb") as fh:
        raw = fh.read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    d = json.loads(raw)
    add = {k: v for k, v in new_subs.items() if k not in d}
    if not add:
        return 0
    # insert before the final closing brace, keeping the file's own style
    body = raw.rstrip()
    assert body.endswith("}")
    body = body[:-1].rstrip()
    ins = "".join(f",{nl}  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}" for k, v in add.items())
    text = body + ins + nl + "}" + nl
    with open(LANG, "wb") as fh:
        fh.write(text.encode("utf-8"))
    return len(add)


def basename(ev):
    return ev.split(".")[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--event", action="append")
    ap.add_argument("--cat", action="append")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--prune", action="store_true")
    a = ap.parse_args()
    with open(os.path.join(WORK, "picks.json"), encoding="utf-8") as fh:
        picks = json.load(fh)
    snd, crlf = load_json(SOUNDS_JSON)
    with open(os.path.join(BEFORE, "sounds.json"), encoding="utf-8") as fh:
        before = json.load(fh)
    sel = [t for t in TARGETS if (a.all or (a.event and t["event"] in a.event) or (a.cat and t["cat"] in a.cat))
           and picks.get(t["event"], {}).get("files")]
    subs = {}
    report = []
    for t in sel:
        ev = t["event"]
        chosen = picks[ev]["files"]
        folder = os.path.join(ASSETS, "sounds", "el", t["folder"])
        old = before.get(ev) or snd.get(ev)
        old_entries = old["sounds"] if old else []
        tmpl = next((e for e in old_entries if isinstance(e, dict)), {})
        att = tmpl.get("attenuation_distance", t.get("att"))
        new_files = []
        for i, c in enumerate(chosen, 1):
            src = os.path.join(WORK, "proc", ev, c + ".ogg")
            rel = f"el/{t['folder']}/{basename(ev)}{i}"
            new_files.append((src, rel))
        new_lufs = [file_lufs(s) for s, _ in new_files]
        eff_old = effective_db(old_entries) if old_entries else None
        eff_new_at1 = 10 * math.log10(np.mean([10 ** (v / 10) for v in new_lufs]))
        if eff_old is None or t.get("new") or ev not in before:
            eff_old = ref_effective(ev, before)
        if eff_old is not None:
            vol = 10 ** ((eff_old + t.get("gain_db", 0) - eff_new_at1) / 20)
        else:
            vol = t.get("vol", 1.0)
        if ev in VOL:
            vol = VOL[ev]
        vol = round(min(1.0, max(0.02, vol)), 2)
        entries = []
        for (src, rel) in new_files:
            e = {"name": f"hearthstead:{rel}", "volume": vol}
            if att:
                e["attenuation_distance"] = att
            if t.get("loop") or (t.get("maxlen") or 0) >= 8:
                e["stream"] = True          # long beds stream instead of loading whole
            entries.append(e)
        report.append(f"{ev:32s} {len(entries)} files vol={vol:.2f} eff_old={eff_old if eff_old is None else round(eff_old,1)} "
                      f"eff_new={round(eff_new_at1 + 20*math.log10(vol),1)}")
        if a.dry:
            continue
        os.makedirs(folder, exist_ok=True)
        for src, rel in new_files:
            shutil.copyfile(src, os.path.join(ASSETS, "sounds", rel + ".ogg"))
        # drop stale numbered files from an earlier apply of this event
        for f in os.listdir(folder):
            m = re.fullmatch(re.escape(basename(ev)) + r"(\d+)\.ogg", f)
            if m and int(m.group(1)) > len(new_files):
                os.remove(os.path.join(folder, f))
        sub = (snd.get(ev) or {}).get("subtitle") or (f"subtitles.hearthstead.{ev}" if t.get("sub") else None)
        entry = {"sounds": entries}
        if sub:
            entry["subtitle"] = sub   # UI-only sounds (sub=None) get no subtitle, like vanilla clicks
        if ev in snd:
            for k2, v2 in snd[ev].items():
                if k2 not in ("sounds", "subtitle"):
                    entry[k2] = v2
        snd[ev] = entry
        if t.get("sub"):
            subs[sub] = t["sub"]
    print("\n".join(report))
    if a.dry:
        return
    dump_sounds(snd, crlf, [t['event'] for t in sel])
    n = apply_lang(subs)
    print(f"sounds.json updated ({len(sel)} events), {n} new subtitle keys")
    if a.prune:
        prune(snd)


def referenced(snd):
    ref = set()
    for v in snd.values():
        for s in v.get("sounds", []):
            if isinstance(s, dict):
                if s.get("type") == "event":
                    continue
                s = s["name"]
            if s.startswith("hearthstead:"):
                ref.add(s[len("hearthstead:"):])
    return ref


def prune(snd):
    ref = referenced(snd)
    base = os.path.join(ASSETS, "sounds")
    dest = os.path.join(WORK, "replaced")
    moved = 0
    for dp, _, fn in os.walk(base):
        for f in fn:
            rel = os.path.relpath(os.path.join(dp, f), base).replace(os.sep, "/")
            key = rel.rsplit(".", 1)[0]
            if rel.startswith("goblin/"):
                continue  # owner-frozen
            if key not in ref:
                os.makedirs(os.path.dirname(os.path.join(dest, rel)), exist_ok=True)
                shutil.move(os.path.join(base, rel), os.path.join(dest, rel))
                moved += 1
    print(f"pruned {moved} unreferenced files to {dest}")


if __name__ == "__main__":
    main()


def dump_sounds_full(d, crlf, removed=()):
    """Remove the given top-level events from sounds.json text, then rewrite/append the rest of d's
    changed events (every event in d whose block differs or is new)."""
    with open(SOUNDS_JSON, "rb") as fh:
        text = fh.read().decode("utf-8")
    removed = set(removed) - set(d)
    while True:
        spans, _ = _top_spans(text)
        idx = next((i for i, (k, _, _) in enumerate(spans) if k in removed), None)
        if idx is None:
            break
        if idx == 0:
            nxt_key = text.index('"', spans[1][1] - 1 - len(json.dumps(spans[1][0])) - 4)
            text = text[:text.index("{") + 1] + "\n  " + text[text.index(json.dumps(spans[1][0]), spans[0][2]):]
        else:
            text = text[:spans[idx - 1][2]] + text[spans[idx][2]:]
    with open(SOUNDS_JSON, "wb") as fh:
        fh.write(text.encode("utf-8"))
    cur = json.loads(text)
    changed = [k for k in d if k not in cur or cur[k] != d[k]]
    dump_sounds(d, crlf, changed)


def remove_lang(keys):
    keys = set(keys)
    if not keys:
        return 0
    with open(LANG, "rb") as fh:
        raw = fh.read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    lines = raw.split(nl)
    out = [ln for ln in lines if not any(ln.strip().startswith(json.dumps(k) + ":") for k in keys)]
    # the last member before "}" must not end with a comma
    for i in range(len(out) - 1, -1, -1):
        if out[i].strip() == "}":
            j = i - 1
            while j >= 0 and not out[j].strip():
                j -= 1
            out[j] = out[j].rstrip().rstrip(",")
            break
    text = nl.join(out)
    json.loads(text)
    with open(LANG, "wb") as fh:
        fh.write(text.encode("utf-8"))
    return len(lines) - len(out)

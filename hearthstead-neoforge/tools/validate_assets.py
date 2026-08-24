#!/usr/bin/env python3
"""
validate_assets.py — resource/asset validation for the Hearthstead Forge mod.

Scans the Java sources for DeferredRegister registrations and cross-checks
every registered object against the resource tree (blockstates, models,
textures, loot tables, lang files, sounds, recipes, tags, structures, meta
files). Designed to be safe on a partially-built project: checks only apply
to things that exist, EXCEPT that a missing counterpart for something that is
REGISTERED in Java is always an error.

Exit code 0 only if all checks pass (warnings do not fail the build).

Usage:
    python3 tools/validate_assets.py [--quiet]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import OrderedDict
from pathlib import Path

try:
    from PIL import Image
    HAVE_PIL = True
except ImportError:  # pragma: no cover - Pillow is expected to be installed
    HAVE_PIL = False

# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

MODID = "hearthstead"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
JAVA_ROOT = PROJECT_ROOT / "src" / "main" / "java"
RES_ROOT = PROJECT_ROOT / "src" / "main" / "resources"
ASSETS = RES_ROOT / "assets"
DATA = RES_ROOT / "data"
MOD_ASSETS = ASSETS / MODID
MOD_DATA = DATA / MODID

EXPECTED_PACK_FORMAT = 34  # Minecraft 1.20.1

# Blocks that intentionally have NO loot table (e.g. technical/creative-only
# blocks). Anything listed here downgrades the missing-loot-table error to a
# warning.
LOOT_TABLE_ALLOWLIST: set[str] = set()

# Items that are allowed to use a block.* lang key instead of item.* even if
# the BlockItem heuristic fails to detect them.
BLOCKITEM_LANG_ALLOWLIST: set[str] = set()

# Texture size expectations.
TEXTURE_CONFIG = {
    # Exact size for entity textures (default applies to all under
    # textures/entity/ unless overridden per-file below).
    "entity_default_size": (128, 64),
    # Per-file overrides, keyed by path relative to textures/entity/,
    # e.g. "settler/settler_child.png": (64, 32)
    "entity_sizes": {},
    # Maximum size for anything under textures/gui/
    "gui_max_size": (512, 512),
    # Basenames exempt from all dimension rules (still must open).
    "any_size_basenames": {"hearthstead_logo.png"},
}

# Vanilla recipe types known in 1.20.1.
KNOWN_RECIPE_TYPES = {
    "minecraft:crafting_shaped",
    "minecraft:crafting_shapeless",
    "minecraft:crafting_decorated_pot",
    "minecraft:smelting",
    "minecraft:blasting",
    "minecraft:smoking",
    "minecraft:campfire_cooking",
    "minecraft:stonecutting",
    "minecraft:smithing_transform",
    "minecraft:smithing_trim",
}
KNOWN_RECIPE_TYPE_PREFIXES = ("minecraft:crafting_special_",)

# Tag namespaces assumed valid without further resolution (forge convention
# tags like forge:ingots/iron are ubiquitous).
KNOWN_TAG_NAMESPACES = {"minecraft", "forge"}

# Item-model parents that require a textures.layer0 entry.
LAYERED_ITEM_PARENTS = {
    "item/generated", "minecraft:item/generated",
    "item/handheld", "minecraft:item/handheld",
    "item/handheld_rod", "minecraft:item/handheld_rod",
    "builtin/generated",
}

NB_IDENTICAL_WARN_RATIO = 0.40  # check 14

# --------------------------------------------------------------------------
# Check framework
# --------------------------------------------------------------------------

RESULTS: "OrderedDict[str, list[tuple[str, str]]]" = OrderedDict()
COUNTS = {"pass": 0, "fail": 0, "warn": 0, "info": 0}


def _add(category: str, symbol: str, message: str) -> None:
    RESULTS.setdefault(category, []).append((symbol, message))


def check(category: str, ok: bool, message: str, *, warn_only: bool = False) -> bool:
    """Record a check result. Returns ok so callers can chain on it."""
    if ok:
        COUNTS["pass"] += 1
        _add(category, "✓", message)
    elif warn_only:
        COUNTS["warn"] += 1
        _add(category, "⚠", message)
    else:
        COUNTS["fail"] += 1
        _add(category, "✗", message)
    return ok


def warn(category: str, message: str) -> None:
    COUNTS["warn"] += 1
    _add(category, "⚠", message)


def info(category: str, message: str) -> None:
    COUNTS["info"] += 1
    _add(category, "•", message)


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(PROJECT_ROOT))
    except ValueError:
        return str(path)


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

_JSON_CACHE: dict[Path, tuple[object, str | None]] = {}


def load_json(path: Path):
    """Return (data, error). Strict JSON, cached."""
    if path not in _JSON_CACHE:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                _JSON_CACHE[path] = (json.load(fh), None)
        except (OSError, json.JSONDecodeError, UnicodeDecodeError) as exc:
            _JSON_CACHE[path] = (None, str(exc))
    return _JSON_CACHE[path]


def split_loc(ref: str, default_ns: str = "minecraft") -> tuple[str, str]:
    """Split a resource location into (namespace, path)."""
    if ":" in ref:
        ns, _, path = ref.partition(":")
        return ns, path
    return default_ns, ref


def model_file(ns: str, path: str) -> Path:
    return ASSETS / ns / "models" / (path + ".json")


def texture_file(ns: str, path: str) -> Path:
    return ASSETS / ns / "textures" / (path + ".png")


def strip_java_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.DOTALL)
    src = re.sub(r"//[^\n]*", " ", src)
    return src


# --------------------------------------------------------------------------
# 1. Registry scan
# --------------------------------------------------------------------------

_REGISTRY_TOKEN_MAP = {
    # ForgeRegistries.* / ForgeRegistries.Keys.* / Registries.* last tokens
    "BLOCKS": "blocks", "BLOCK": "blocks",
    "ITEMS": "items", "ITEM": "items",
    "ENTITY_TYPES": "entities", "ENTITY_TYPE": "entities", "ENTITIES": "entities",
    "SOUND_EVENTS": "sounds", "SOUND_EVENT": "sounds", "SOUNDS": "sounds",
    "MENU_TYPES": "menus", "MENU_TYPE": "menus", "MENUS": "menus", "MENU": "menus",
    "BLOCK_ENTITY_TYPES": "block_entities", "BLOCK_ENTITY_TYPE": "block_entities",
    "BLOCK_ENTITIES": "block_entities",
}

DEFREG_DECL_RE = re.compile(
    r"(\w+)\s*=\s*DeferredRegister\s*\.\s*create\s*\(\s*([A-Za-z_][\w.]*)")
REGISTER_CALL_RE = re.compile(r'\b(\w+)\s*\.\s*register\s*\(\s*"([a-z0-9_./]+)"')
# Bare register("name") calls that go through a local helper method, e.g.
#   static RegistryObject<SoundEvent> register(String name) {
#       return SOUND_EVENTS.register(name, ...); }
BARE_REGISTER_CALL_RE = re.compile(r'(?<![\w.])register\s*\(\s*"([a-z0-9_./]+)"')
# The helper's delegation: VAR.register(identifier, ...) — first arg NOT a literal.
DELEGATE_REGISTER_RE = re.compile(r"\b(\w+)\s*\.\s*register\s*\(\s*[a-z]\w*\s*[,)]")


def scan_registrations():
    """Return (registries, blockitem_names).

    registries: dict kind -> dict name -> "file:line"
    """
    registries: dict[str, dict[str, str]] = {
        k: {} for k in ("blocks", "items", "entities", "sounds",
                        "menus", "block_entities", "other")}
    blockitems: set[str] = set()
    var_kind: dict[str, str] = {}

    java_files = sorted(JAVA_ROOT.rglob("*.java")) if JAVA_ROOT.is_dir() else []

    # Pass 1: map DeferredRegister variable names to registry kinds.
    sources: list[tuple[Path, str]] = []
    for jf in java_files:
        try:
            text = strip_java_comments(jf.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError) as exc:
            warn("Registry", f"could not read {rel(jf)}: {exc}")
            continue
        sources.append((jf, text))
        for m in DEFREG_DECL_RE.finditer(text):
            var, registry_expr = m.group(1), m.group(2)
            token = registry_expr.rsplit(".", 1)[-1].upper()
            kind = _REGISTRY_TOKEN_MAP.get(token)
            if kind is None:
                # Fall back to the variable name itself.
                kind = _REGISTRY_TOKEN_MAP.get(var.upper(), "other")
            var_kind[var] = kind

    # Pass 2: collect register("name") calls.
    for jf, text in sources:
        matches: list[tuple[int, str | None, str]] = []  # (pos, var-or-None, name)
        for m in REGISTER_CALL_RE.finditer(text):
            matches.append((m.start(), m.group(1), m.group(2)))
        for m in BARE_REGISTER_CALL_RE.finditer(text):
            # Skip method-reference style receivers with odd spacing (`X . register`).
            before = text[:m.start()].rstrip()
            if before.endswith("."):
                continue
            matches.append((m.start(), None, m.group(1)))

        # Kind for bare helper calls in this file: prefer the helper's own
        # delegation (VAR.register(name, ...)), else the file's single
        # DeferredRegister variable.
        file_kind = None
        dm = DELEGATE_REGISTER_RE.search(text)
        if dm and dm.group(1) in var_kind:
            file_kind = var_kind[dm.group(1)]
        else:
            local_vars = [v.group(1) for v in DEFREG_DECL_RE.finditer(text)]
            if len(local_vars) == 1:
                file_kind = var_kind.get(local_vars[0])

        for pos, var, name in sorted(matches):
            if var is None:
                kind = file_kind or "other"
            else:
                kind = var_kind.get(var) or _REGISTRY_TOKEN_MAP.get(var.upper(), "other")
            line = text.count("\n", 0, pos) + 1
            where = f"{rel(jf)}:{line}"
            if name in registries[kind]:
                check("Registry", False,
                      f"duplicate {kind} registration '{name}' at {where} "
                      f"(first at {registries[kind][name]})")
                continue
            registries[kind][name] = where
            if kind == "items":
                # BlockItem heuristic: look at the code right after this
                # register(...) call, stopping before the next register call.
                start = pos + len(name)
                tail = text[start:start + 600]
                nxt = REGISTER_CALL_RE.search(tail)
                if nxt:
                    tail = tail[:nxt.start()]
                if "BlockItem" in tail:
                    blockitems.add(name)

    n_java = len(java_files)
    summary = ", ".join(f"{len(v)} {k}" for k, v in registries.items() if v) or "nothing registered yet"
    check("Registry", True, f"scanned {n_java} .java file(s): {summary}")
    return registries, blockitems


# --------------------------------------------------------------------------
# Lang handling (used by several checks)
# --------------------------------------------------------------------------

LANG_FILES = ("en_us.json", "nb_no.json")


def load_langs() -> dict[str, dict | None]:
    """Return {filename: dict-or-None}. Parse errors are reported here."""
    langs: dict[str, dict | None] = {}
    for name in LANG_FILES:
        path = MOD_ASSETS / "lang" / name
        if not path.is_file():
            langs[name] = None
            continue
        data, err = load_json(path)
        if err is not None or not isinstance(data, dict):
            check("Lang", False, f"{rel(path)} does not parse as a JSON object: {err or 'not an object'}")
            langs[name] = None
        else:
            langs[name] = data
    return langs


def require_lang_key(category: str, langs: dict, key: str, owner: str) -> None:
    """Key must be present (and non-empty) in every EXISTING lang file.
    Missing lang files are reported once, elsewhere."""
    for name, data in langs.items():
        if data is None:
            continue  # absence of the whole file handled in check_lang
        present = key in data and str(data[key]).strip() != ""
        check(category, present, f"lang key '{key}' in {name} ({owner})")


def require_lang_key_any(category: str, langs: dict, keys: list[str], owner: str) -> None:
    """At least one of `keys` must be present in every existing lang file."""
    for name, data in langs.items():
        if data is None:
            continue
        present = any(k in data and str(data[k]).strip() != "" for k in keys)
        check(category, present,
              f"lang key {' or '.join(repr(k) for k in keys)} in {name} ({owner})")


# --------------------------------------------------------------------------
# 2. Blocks
# --------------------------------------------------------------------------

def collect_model_refs(node) -> list[str]:
    """Recursively collect every value of a 'model' key in a blockstate."""
    refs = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "model" and isinstance(v, str):
                refs.append(v)
            else:
                refs.extend(collect_model_refs(v))
    elif isinstance(node, list):
        for v in node:
            refs.extend(collect_model_refs(v))
    return refs


def check_blocks(blocks: dict[str, str], langs: dict) -> None:
    if not blocks:
        info("Blocks", "no blocks registered — skipped")
        return
    for name, where in sorted(blocks.items()):
        bs = MOD_ASSETS / "blockstates" / f"{name}.json"
        if check("Blocks", bs.is_file(), f"blockstate {rel(bs)} (block '{name}', {where})"):
            data, err = load_json(bs)
            if err is None:
                refs = collect_model_refs(data)
                check("Blocks", bool(refs), f"blockstate '{name}' references at least one model")
                for ref in sorted(set(refs)):
                    ns, path = split_loc(ref)
                    if ns != MODID:
                        continue  # vanilla / other namespaces assumed ok
                    mf = model_file(ns, path)
                    check("Blocks", mf.is_file(),
                          f"model '{ref}' from blockstate '{name}' -> {rel(mf)}")
            # parse errors are reported by the JSON integrity check

        lt = MOD_DATA / "loot_table" / "blocks" / f"{name}.json"
        check("Blocks", lt.is_file(),
              f"loot table {rel(lt)} (block '{name}')",
              warn_only=name in LOOT_TABLE_ALLOWLIST)

        require_lang_key("Blocks", langs, f"block.{MODID}.{name}", f"block '{name}'")


# --------------------------------------------------------------------------
# 3. Items
# --------------------------------------------------------------------------

def check_items(items: dict[str, str], blocks: dict[str, str],
                blockitems: set[str], langs: dict) -> None:
    if not items:
        info("Items", "no items registered — skipped")
        return
    for name, where in sorted(items.items()):
        mf = MOD_ASSETS / "models" / "item" / f"{name}.json"
        if check("Items", mf.is_file(), f"item model {rel(mf)} (item '{name}', {where})"):
            data, err = load_json(mf)
            if err is None and isinstance(data, dict):
                parent = data.get("parent")
                textures = data.get("textures", {})
                if isinstance(parent, str):
                    pns, ppath = split_loc(parent)
                    if pns == MODID:
                        pf = model_file(pns, ppath)
                        check("Items", pf.is_file(),
                              f"parent '{parent}' of item model '{name}' -> {rel(pf)}")
                    if parent in LAYERED_ITEM_PARENTS:
                        layer0 = textures.get("layer0") if isinstance(textures, dict) else None
                        if check("Items", isinstance(layer0, str),
                                 f"item model '{name}' (parent {parent}) declares textures.layer0"):
                            tns, tpath = split_loc(layer0)
                            if tns == MODID:
                                tf = texture_file(tns, tpath)
                                check("Items", tf.is_file(),
                                      f"texture '{layer0}' of item model '{name}' -> {rel(tf)}")
                elif parent is None and not data.get("elements") and not textures:
                    warn("Items", f"item model '{name}' has no parent, elements or textures")

        keys = [f"item.{MODID}.{name}"]
        if name in blockitems or name in blocks or name in BLOCKITEM_LANG_ALLOWLIST:
            keys.append(f"block.{MODID}.{name}")  # BlockItems use the block key
        require_lang_key_any("Items", langs, keys, f"item '{name}'")


# --------------------------------------------------------------------------
# 4. Entities
# --------------------------------------------------------------------------

def check_entities(entities: dict[str, str], langs: dict) -> None:
    if not entities:
        info("Entities", "no entities registered — skipped")
        return
    for name, where in sorted(entities.items()):
        require_lang_key("Entities", langs, f"entity.{MODID}.{name}",
                         f"entity '{name}' ({where})")


# --------------------------------------------------------------------------
# 5. JSON / model integrity
# --------------------------------------------------------------------------

def check_json_integrity() -> None:
    json_files: list[Path] = []
    for root in (ASSETS, DATA):
        if root.is_dir():
            json_files.extend(sorted(root.rglob("*.json")))
    if not json_files:
        info("JSON", "no .json files under assets/ or data/ yet — skipped")
        return

    bad = 0
    for path in json_files:
        _, err = load_json(path)
        if err is not None:
            check("JSON", False, f"{rel(path)} is not valid JSON: {err}")
            bad += 1
    check("JSON", bad == 0, f"{len(json_files) - bad}/{len(json_files)} JSON files parse strictly")

    # Model-specific: every hearthstead texture/parent reference resolves.
    models_root = MOD_ASSETS / "models"
    if not models_root.is_dir():
        return
    for path in sorted(models_root.rglob("*.json")):
        data, err = load_json(path)
        if err is not None or not isinstance(data, dict):
            continue
        parent = data.get("parent")
        if isinstance(parent, str):
            ns, ppath = split_loc(parent)
            if ns == MODID:
                pf = model_file(ns, ppath)
                check("JSON", pf.is_file(), f"parent '{parent}' in {rel(path)} -> {rel(pf)}")
        textures = data.get("textures")
        if isinstance(textures, dict):
            for tkey, tval in sorted(textures.items()):
                if not isinstance(tval, str) or tval.startswith("#"):
                    continue
                ns, tpath = split_loc(tval)
                if ns == MODID:
                    tf = texture_file(ns, tpath)
                    check("JSON", tf.is_file(),
                          f"texture '{tval}' ({tkey}) in {rel(path)} -> {rel(tf)}")


# --------------------------------------------------------------------------
# 6. Lang files (existence, parity, empty values)
# --------------------------------------------------------------------------

def check_lang(langs: dict, any_keys_needed: bool) -> None:
    existing = {n: d for n, d in langs.items() if d is not None}
    missing = [n for n, d in langs.items() if d is None
               and not (MOD_ASSETS / "lang" / n).is_file()]

    if not existing and missing:
        if any_keys_needed:
            for n in missing:
                check("Lang", False,
                      f"lang file assets/{MODID}/lang/{n} is missing but registered "
                      f"objects need translation keys")
        else:
            info("Lang", "no lang files yet and nothing registered needs keys — skipped")
        return

    for n in missing:
        # One lang exists, the other doesn't: parity is impossible.
        check("Lang", False, f"lang file assets/{MODID}/lang/{n} is missing "
                             f"(the other lang file exists)")

    for n, data in existing.items():
        empty = sorted(k for k, v in data.items() if str(v).strip() == "")
        check("Lang", not empty,
              f"{n}: no empty values" if not empty
              else f"{n}: empty values for keys: {', '.join(empty)}")

    if len(existing) == len(LANG_FILES):
        en = langs["en_us.json"]
        nb = langs["nb_no.json"]
        only_en = sorted(set(en) - set(nb))
        only_nb = sorted(set(nb) - set(en))
        check("Lang", not only_en,
              "key parity: all en_us keys present in nb_no" if not only_en
              else f"keys missing from nb_no.json: {', '.join(only_en)}")
        check("Lang", not only_nb,
              "key parity: all nb_no keys present in en_us" if not only_nb
              else f"keys missing from en_us.json: {', '.join(only_nb)}")

        # 14. Norwegian sanity (warn-only)
        shared = sorted(set(en) & set(nb))
        if shared:
            identical = [k for k in shared if en[k] == nb[k]]
            ratio = len(identical) / len(shared)
            if ratio > NB_IDENTICAL_WARN_RATIO:
                shown = ", ".join(identical[:25]) + (" …" if len(identical) > 25 else "")
                warn("Lang", f"nb_no.json equals en_us.json for {len(identical)}/{len(shared)} "
                             f"keys ({ratio:.0%} > {NB_IDENTICAL_WARN_RATIO:.0%}) — possible "
                             f"copy-paste: {shown}")
            else:
                check("Lang", True,
                      f"Norwegian sanity: {len(identical)}/{len(shared)} identical values "
                      f"({ratio:.0%} ≤ {NB_IDENTICAL_WARN_RATIO:.0%})")


# --------------------------------------------------------------------------
# 7. Sounds
# --------------------------------------------------------------------------

SETTLER_ACTIVITY_ENTRY_RE = re.compile(r'\b([A-Z][A-Z0-9_]*)\s*\(\s*"([a-z0-9_]+)"\s*\)')


def check_settler_activities(langs: dict) -> None:
    """Every SettlerActivity enum value needs a real nameplate string —
    guards against the class of bug where a new activity is appended
    without a matching hearthstead.activity.<key> lang key (an untranslated
    key then shows raw in the player-facing nameplate)."""
    src_path = JAVA_ROOT / "com" / "hearthstead" / "entity" / "SettlerActivity.java"
    if not src_path.is_file():
        info("Activities", "SettlerActivity.java not found — skipped")
        return
    text = strip_java_comments(src_path.read_text(encoding="utf-8"))
    enum_body_match = re.search(r"\benum\s+SettlerActivity\s*\{(.*?)\}", text, re.DOTALL)
    if not check("Activities", enum_body_match is not None,
                 f"{rel(src_path)} declares the SettlerActivity enum"):
        return
    # Only the constant declarations, not the constructor/methods that follow.
    decls = enum_body_match.group(1).split(";", 1)[0]
    keys = [m.group(2) for m in SETTLER_ACTIVITY_ENTRY_RE.finditer(decls)]
    if not check("Activities", bool(keys),
                 f"{rel(src_path)} has at least one activity constant"):
        return
    for key in keys:
        require_lang_key("Activities", langs, f"hearthstead.activity.{key}",
                          f"SettlerActivity.{key.upper()}")


def check_sounds(sound_events: dict[str, str], langs: dict) -> None:
    sounds_json = MOD_ASSETS / "sounds.json"
    if not sounds_json.is_file():
        if sound_events:
            check("Sounds", False,
                  f"{len(sound_events)} SoundEvent(s) registered but "
                  f"assets/{MODID}/sounds.json is missing")
        else:
            info("Sounds", "no sounds.json and no registered SoundEvents — skipped")
        return

    data, err = load_json(sounds_json)
    if not check("Sounds", err is None and isinstance(data, dict),
                 f"{rel(sounds_json)} parses as a JSON object"):
        return

    for event, spec in sorted(data.items()):
        if not isinstance(spec, dict):
            check("Sounds", False, f"sounds.json event '{event}' is not an object")
            continue
        entries = spec.get("sounds", [])
        check("Sounds", isinstance(entries, list) and entries,
              f"event '{event}' declares at least one sound entry")
        for entry in entries if isinstance(entries, list) else []:
            ref = entry.get("name") if isinstance(entry, dict) else entry
            if not isinstance(ref, str):
                check("Sounds", False, f"event '{event}' has a malformed sound entry: {entry!r}")
                continue
            ns, path = split_loc(ref)
            if ns != MODID:
                continue
            ogg = MOD_ASSETS / "sounds" / (path + ".ogg")
            check("Sounds", ogg.is_file(), f"event '{event}' sound '{ref}' -> {rel(ogg)}")
        subtitle = spec.get("subtitle")
        if isinstance(subtitle, str):
            require_lang_key("Sounds", langs, subtitle, f"subtitle of event '{event}'")

    events = set(data.keys())
    registered = set(sound_events.keys())
    for name in sorted(registered - events):
        check("Sounds", False,
              f"SoundEvent '{name}' registered at {sound_events[name]} has no "
              f"entry in sounds.json")
    for name in sorted(events - registered):
        check("Sounds", False,
              f"sounds.json event '{name}' has no registered SoundEvent in Java")
    if registered and registered == events:
        check("Sounds", True,
              f"sounds.json events match registered SoundEvents ({len(events)})")


# --------------------------------------------------------------------------
# 8. Textures
# --------------------------------------------------------------------------

def check_textures() -> None:
    if not RES_ROOT.is_dir():
        info("Textures", "no resources directory — skipped")
        return
    pngs = sorted(p for p in RES_ROOT.rglob("*.png") if "build" not in p.parts)
    mcmetas = sorted(RES_ROOT.rglob("*.png.mcmeta"))
    if not pngs and not mcmetas:
        info("Textures", "no .png textures yet — skipped")
        return
    if not HAVE_PIL:
        warn("Textures", "Pillow not installed — pixel checks skipped")

    for meta in mcmetas:
        png = meta.with_suffix("")  # foo.png.mcmeta -> foo.png
        check("Textures", png.is_file(), f"{rel(meta)} sits next to an existing .png")
        mdata, merr = load_json(meta)
        check("Textures", merr is None, f"{rel(meta)} parses as JSON"
              if merr is None else f"{rel(meta)} does not parse: {merr}")

    for png in pngs:
        size = None
        if HAVE_PIL:
            try:
                with Image.open(png) as img:
                    img.load()
                    size = img.size
                    mode_ok = img.mode == "RGBA"
                    if not mode_ok:
                        try:
                            img.convert("RGBA")
                            mode_ok = True
                        except Exception:
                            mode_ok = False
            except Exception as exc:
                check("Textures", False, f"{rel(png)} does not open as an image: {exc}")
                continue
            check("Textures", mode_ok, f"{rel(png)} opens ({size[0]}x{size[1]}, RGBA-compatible)")
        else:
            check("Textures", True, f"{rel(png)} exists (not opened — Pillow missing)")

        if png.name in TEXTURE_CONFIG["any_size_basenames"]:
            continue

        parts = png.parts
        category = None
        if "textures" in parts:
            after = parts[parts.index("textures") + 1:]
            if len(after) >= 2:
                category = after[0]

        if size is None:
            continue
        w, h = size
        if category in ("block", "item"):
            meta = png.parent / (png.name + ".mcmeta")
            # Minecraft accepts any power-of-two texture. The project standard
            # is 16x16 so the world stays visually of a piece with vanilla,
            # with 32/64 reserved for SIGNATURE pieces the player studies up
            # close (the owner's call). Anything not a power of two, or a
            # non-square that is not a valid animation strip, is a mistake.
            SIGNATURE_SIZES = (32, 64)
            if w == 16 and h == 16:
                check("Textures", True, f"{rel(png)} is 16x16")
                if meta.is_file():
                    mdata, merr = load_json(meta)
                    if merr is None:
                        check("Textures", isinstance(mdata, dict) and "animation" in mdata,
                              f"{rel(meta)} declares an 'animation' section",
                              warn_only=True)
            elif w == h and w in SIGNATURE_SIZES:
                check("Textures", True, f"{rel(png)} is {w}x{h} (signature piece)")
            elif w in (16,) + SIGNATURE_SIZES and h % w == 0 and h > w:
                if check("Textures", meta.is_file(),
                         f"animated texture {rel(png)} ({w}x{h}) has an accompanying .mcmeta"):
                    mdata, merr = load_json(meta)
                    if merr is None:
                        check("Textures", isinstance(mdata, dict) and "animation" in mdata,
                              f"{rel(meta)} declares an 'animation' section",
                              warn_only=True)
            else:
                check("Textures", False,
                      f"{rel(png)} is {w}x{h} — block/item textures must be 16x16, "
                      f"32x32 or 64x64 (signature), or WxN animated (N a multiple of W)")
        elif category == "entity":
            tex_root = ASSETS / MODID / "textures" / "entity"
            try:
                key = str(png.relative_to(tex_root))
            except ValueError:
                key = png.name
            expected = TEXTURE_CONFIG["entity_sizes"].get(
                key, TEXTURE_CONFIG["entity_default_size"])
            check("Textures", (w, h) == tuple(expected),
                  f"{rel(png)} is {w}x{h} (expected {expected[0]}x{expected[1]})")
        elif category == "gui":
            mw, mh = TEXTURE_CONFIG["gui_max_size"]
            check("Textures", w <= mw and h <= mh,
                  f"{rel(png)} is {w}x{h} (GUI limit {mw}x{mh})")
        # other categories: openability only


# --------------------------------------------------------------------------
# 9. Recipes
# --------------------------------------------------------------------------

def _walk_recipe_ids(node, out_items: list, out_tags: list) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "item" and isinstance(v, str):
                out_items.append(v)
            elif k == "tag" and isinstance(v, str):
                out_tags.append(v)
            elif k == "result" and isinstance(v, str):
                out_items.append(v)  # smelting/stonecutting style
            elif k == "id" and isinstance(v, str) and ":" in v:
                out_items.append(v)  # 1.20.5+ result {"id": ...} form
            else:
                _walk_recipe_ids(v, out_items, out_tags)
    elif isinstance(node, list):
        for v in node:
            _walk_recipe_ids(v, out_items, out_tags)


def check_recipes(blocks: dict, items: dict) -> None:
    recipes_dir = MOD_DATA / "recipe"
    if not recipes_dir.is_dir():
        info("Recipes", "no recipes directory — skipped")
        return
    files = sorted(recipes_dir.rglob("*.json"))
    if not files:
        info("Recipes", "no recipe files — skipped")
        return
    mod_ids = set(blocks) | set(items)

    for path in files:
        data, err = load_json(path)
        if err is not None or not isinstance(data, dict):
            check("Recipes", False, f"{rel(path)} is not a valid JSON object: {err or 'not an object'}")
            continue
        rtype = data.get("type", "")
        rtype_full = rtype if ":" in rtype else f"minecraft:{rtype}"
        type_ok = (rtype_full in KNOWN_RECIPE_TYPES
                   or rtype_full.startswith(KNOWN_RECIPE_TYPE_PREFIXES))
        check("Recipes", type_ok, f"{rel(path)}: type '{rtype}' is a known vanilla recipe type")

        item_ids: list[str] = []
        tag_ids: list[str] = []
        _walk_recipe_ids(data, item_ids, tag_ids)
        for iid in sorted(set(item_ids)):
            ns, name = split_loc(iid)
            if ns == "minecraft":
                continue
            check("Recipes", ns == MODID and name in mod_ids,
                  f"{rel(path)}: item id '{iid}' is a registered {MODID} item/block")
        for tid in sorted(set(tag_ids)):
            ns, name = split_loc(tid)
            if ns in KNOWN_TAG_NAMESPACES:
                continue
            if ns == MODID:
                tag_file = MOD_DATA / "tags" / "item" / (name + ".json")
                check("Recipes", tag_file.is_file(),
                      f"{rel(path)}: tag '{tid}' has a tag file at {rel(tag_file)}")
            else:
                check("Recipes", False, f"{rel(path)}: tag '{tid}' uses unknown namespace '{ns}'")
    check("Recipes", True, f"validated {len(files)} recipe file(s)")


# --------------------------------------------------------------------------
# 10. Tags
# --------------------------------------------------------------------------

def check_tags(registries: dict) -> None:
    tag_roots = sorted(DATA.glob("*/tags")) if DATA.is_dir() else []
    files = [p for root in tag_roots for p in sorted(root.rglob("*.json"))]
    if not files:
        info("Tags", "no tag files — skipped")
        return

    folder_registry = {
        "blocks": set(registries["blocks"]),
        "items": set(registries["items"]) | set(registries["blocks"]),  # BlockItems
        "entity_types": set(registries["entities"]),
        # 1.21 singular datapack folders
        "block": set(registries["blocks"]),
        "item": set(registries["items"]) | set(registries["blocks"]),
        "entity_type": set(registries["entities"]),
    }
    for path in files:
        data, err = load_json(path)
        if err is not None or not isinstance(data, dict):
            check("Tags", False, f"{rel(path)} is not a valid JSON object: {err or 'not an object'}")
            continue
        values = data.get("values", [])
        if not check("Tags", isinstance(values, list), f"{rel(path)} has a 'values' list"):
            continue
        parts = path.parts
        folder = parts[parts.index("tags") + 1] if "tags" in parts else ""
        known = folder_registry.get(folder)
        for v in values:
            vid = v.get("id") if isinstance(v, dict) else v
            if not isinstance(vid, str):
                check("Tags", False, f"{rel(path)}: malformed tag value {v!r}")
                continue
            if vid.startswith("#"):
                continue  # nested tag reference — namespace rules apply upstream
            ns, name = split_loc(vid)
            if ns != MODID:
                continue  # minecraft:/forge: values assumed ok
            if known is None:
                continue  # unknown tag folder — no registry to check against
            check("Tags", name in known,
                  f"{rel(path)}: value '{vid}' is a registered {MODID} {folder[:-1] if folder.endswith('s') else folder}")
    check("Tags", True, f"validated {len(files)} tag file(s)")


# --------------------------------------------------------------------------
# 11. Meta files (pack.mcmeta, mods.toml)
# --------------------------------------------------------------------------

def check_meta() -> None:
    pack = RES_ROOT / "pack.mcmeta"
    if check("Meta", pack.is_file(), f"{rel(pack)} exists"):
        data, err = load_json(pack)
        if check("Meta", err is None, f"pack.mcmeta parses as JSON"
                 if err is None else f"pack.mcmeta does not parse: {err}"):
            fmt = (data or {}).get("pack", {}).get("pack_format")
            check("Meta", fmt == EXPECTED_PACK_FORMAT,
                  f"pack_format is {fmt} (expected {EXPECTED_PACK_FORMAT})")

    toml_path = RES_ROOT / "META-INF" / "neoforge.mods.toml"
    if not check("Meta", toml_path.is_file(), f"{rel(toml_path)} exists"):
        return

    mod_ids: list[str] = []
    logo: str | None = None
    parsed = False
    try:
        import tomllib
        with open(toml_path, "rb") as fh:
            tdata = tomllib.load(fh)
        parsed = True
        mod_ids = [m.get("modId", "") for m in tdata.get("mods", [])]
        logo = tdata.get("logoFile") or next(
            (m["logoFile"] for m in tdata.get("mods", []) if "logoFile" in m), None)
    except Exception as exc:
        warn("Meta", f"mods.toml did not parse as strict TOML ({exc}) — falling back to regex")
    if not parsed:
        text = toml_path.read_text(encoding="utf-8", errors="replace")
        mod_ids = re.findall(r'^\s*modId\s*=\s*"([^"]+)"', text, flags=re.M)
        m = re.search(r'^\s*logoFile\s*=\s*"([^"]+)"', text, flags=re.M)
        logo = m.group(1) if m else None

    check("Meta", MODID in mod_ids,
          f'mods.toml declares modId="{MODID}"' if MODID in mod_ids
          else f'mods.toml does not declare modId="{MODID}" (found: {mod_ids or "none"})')
    if logo:
        logo_path = RES_ROOT / logo
        check("Meta", logo_path.is_file(),
              f"declared logoFile '{logo}' exists at {rel(logo_path)}")


# --------------------------------------------------------------------------
# 12. GameTest structures
# --------------------------------------------------------------------------

def check_structures() -> None:
    struct_dir = MOD_DATA / "structure"
    if not struct_dir.is_dir():
        info("Structures", "no structures directory — skipped")
        return
    files = sorted(struct_dir.rglob("*.nbt"))
    if not files:
        info("Structures", "no .nbt structures — skipped")
        return
    for path in files:
        try:
            magic = path.read_bytes()[:2]
        except OSError as exc:
            check("Structures", False, f"{rel(path)} could not be read: {exc}")
            continue
        check("Structures", magic == b"\x1f\x8b",
              f"{rel(path)} starts with gzip magic 0x1f8b")


# --------------------------------------------------------------------------
# 13. Pipeline determinism
# --------------------------------------------------------------------------

# Generators that only write PNGs deterministically from constant seeds and
# are cheap enough to run twice per validate_assets invocation. gen_sounds is
# excluded: it shells out to ffmpeg and is not byte-reproducible across
# encoder builds.
PIPELINE_GENERATORS = [
    "gen_settler.py",
    "gen_blocks_items.py",
    "gen_gui.py",
    "gen_plaque.py",
    "gen_structures.py",
]


def _run_generator_isolated(tools_src: Path, script: str, hashseed: str) -> tuple[Path, str | None]:
    """Copy tools/ into a temp dir, run `script` there with PYTHONHASHSEED set,
    and return (dir-containing-generated-assets, error-or-None). The generator
    writes into ../src relative to its own location, same as it does in-repo,
    so the temp tree must mirror tools/ sitting next to src/."""
    tmp = Path(tempfile.mkdtemp(prefix="hearthstead_pipeline_"))
    tools_dst = tmp / "tools"
    shutil.copytree(tools_src, tools_dst)
    env = dict(os.environ, PYTHONHASHSEED=hashseed)
    try:
        proc = subprocess.run(
            [sys.executable, script], cwd=tools_dst, env=env,
            capture_output=True, text=True, timeout=120)
    except Exception as exc:  # pragma: no cover - defensive
        return tmp, str(exc)
    if proc.returncode != 0:
        return tmp, (proc.stderr or proc.stdout or "non-zero exit").strip()[:400]
    return tmp, None


_PIPELINE_OUTPUT_SUFFIXES = (".png", ".nbt")


def check_pipeline() -> None:
    """A regression guard is worthless the moment it stops running and
    nobody notices. Every branch below therefore ends in a real check()
    failure on anything that prevents the determinism comparison from
    happening at all -- a generator that can't execute (missing Pillow
    included: subprocess failure surfaces it the same as any other crash)
    is exactly the case this guard exists to catch, not a reason to
    degrade to a warning or an info line and move on."""
    tools_src = PROJECT_ROOT / "tools"
    if not tools_src.is_dir():
        info("Pipeline", "no tools/ directory — skipped")
        return

    for script in PIPELINE_GENERATORS:
        if not (tools_src / script).is_file():
            continue

        tmp_a, err_a = _run_generator_isolated(tools_src, script, "0")
        tmp_b, err_b = _run_generator_isolated(tools_src, script, "1")
        try:
            if not check("Pipeline", not (err_a or err_b),
                         f"{script}: runs cleanly under PYTHONHASHSEED=0 and =1 "
                         f"(needed before its determinism can even be checked)"
                         if (err_a or err_b) else
                         f"{script}: runs cleanly under PYTHONHASHSEED=0 and =1"):
                if err_a:
                    check("Pipeline", False, f"{script} (PYTHONHASHSEED=0): {err_a}")
                if err_b:
                    check("Pipeline", False, f"{script} (PYTHONHASHSEED=1): {err_b}")
                continue

            res_a = tmp_a / "src"
            res_b = tmp_b / "src"
            outs_a = sorted(p.relative_to(res_a) for p in res_a.rglob("*")
                            if p.is_file() and p.suffix in _PIPELINE_OUTPUT_SUFFIXES) \
                if res_a.is_dir() else []
            outs_b = sorted(p.relative_to(res_b) for p in res_b.rglob("*")
                            if p.is_file() and p.suffix in _PIPELINE_OUTPUT_SUFFIXES) \
                if res_b.is_dir() else []
            check("Pipeline", bool(outs_a),
                  f"{script}: produced at least one output file to compare"
                  if outs_a else
                  f"{script}: produced no .png/.nbt output — nothing for this guard to verify")
            if not outs_a:
                continue

            check("Pipeline", outs_a == outs_b,
                  f"{script}: PYTHONHASHSEED=0 and PYTHONHASHSEED=1 runs produced the same file set"
                  if outs_a == outs_b else
                  f"{script}: PYTHONHASHSEED=0 and PYTHONHASHSEED=1 runs produced DIFFERENT file "
                  f"sets: only-in-0={sorted(set(outs_a) - set(outs_b))} "
                  f"only-in-1={sorted(set(outs_b) - set(outs_a))}")

            mismatched = []
            for rel_out in outs_a:
                pb = res_b / rel_out
                if not pb.is_file() or (res_a / rel_out).read_bytes() != pb.read_bytes():
                    mismatched.append(str(rel_out))
            check("Pipeline", not mismatched,
                  f"{script}: byte-identical output across PYTHONHASHSEED=0 and PYTHONHASHSEED=1"
                  if not mismatched
                  else f"{script}: non-deterministic — differs across process-salted hash() runs: "
                       f"{', '.join(mismatched)}")

            src_root = PROJECT_ROOT / "src"
            committed_mismatch = []
            for rel_out in outs_a:
                committed = src_root / rel_out
                if not committed.is_file() or (res_a / rel_out).read_bytes() != committed.read_bytes():
                    committed_mismatch.append(str(rel_out))
            check("Pipeline", not committed_mismatch,
                  f"{script}: committed assets match a fresh deterministic run"
                  if not committed_mismatch
                  else f"{script}: committed assets are stale vs. the generator — re-run and commit: "
                       f"{', '.join(committed_mismatch)}")
        finally:
            shutil.rmtree(tmp_a, ignore_errors=True)
            shutil.rmtree(tmp_b, ignore_errors=True)


# --------------------------------------------------------------------------
# 14. Settler appearance binding (Java cardinalities/keys <-> layer files)
# --------------------------------------------------------------------------

# check_pipeline() proves the generator is deterministic and matches the
# committed tree; it says nothing about whether the SET of layer files it
# produces still matches what SettlerTextureCache (Java) will ask for by
# name. SKIN_KEYS/HAIR_COLOR_KEYS and the *_COUNT constants are duplicated
# by hand in Java and Python with no shared source of truth, so a rename or
# cardinality change on either side would previously only surface as a
# runtime IOException inside a caught Exception -- i.e. never, in CI.

_JAVA_INT_CONST_RE = re.compile(r"public static final int (\w+)\s*=\s*(\d+)\s*;")
_JAVA_STRING_ARRAY_RE = re.compile(
    r"private static final String\[\]\s+(\w+)\s*=\s*\{([^}]*)\}")
_JAVA_QUOTED_RE = re.compile(r'"([^"]*)"')
_PROFESSION_ENUM_RE = re.compile(
    r'^\s*[A-Z_]+\(\s*\d+\s*,\s*"([a-z0-9_]+)"', re.MULTILINE)


def _read_java(rel_path: str) -> str | None:
    path = JAVA_ROOT / rel_path
    try:
        return strip_java_comments(path.read_text(encoding="utf-8"))
    except OSError:
        return None


def check_appearance_binding() -> None:
    appearance_src = _read_java("com/hearthstead/entity/SettlerAppearance.java")
    cache_src = _read_java("com/hearthstead/client/render/SettlerTextureCache.java")
    profession_src = _read_java("com/hearthstead/entity/Profession.java")
    gen_path = PROJECT_ROOT / "tools" / "gen_settler.py"

    if not check("Appearance", appearance_src is not None,
                 "SettlerAppearance.java is readable"):
        return
    if not check("Appearance", cache_src is not None,
                 "SettlerTextureCache.java is readable"):
        return
    if not check("Appearance", profession_src is not None,
                 "Profession.java is readable"):
        return
    if not check("Appearance", gen_path.is_file(), "tools/gen_settler.py exists"):
        return

    counts = {m.group(1): int(m.group(2)) for m in _JAVA_INT_CONST_RE.finditer(appearance_src)}
    for needed in ("SKIN_COUNT", "HAIR_STYLE_COUNT", "HAIR_COLOR_COUNT",
                   "FACE_COUNT", "CLOTHING_COUNT"):
        if not check("Appearance", needed in counts,
                     f"SettlerAppearance.java declares {needed}"):
            return

    arrays: dict[str, list[str]] = {}
    for m in _JAVA_STRING_ARRAY_RE.finditer(cache_src):
        arrays[m.group(1)] = _JAVA_QUOTED_RE.findall(m.group(2))
    for needed in ("SKIN_KEYS", "HAIR_COLOR_KEYS"):
        if not check("Appearance", needed in arrays and bool(arrays[needed]),
                     f"SettlerTextureCache.java declares a non-empty {needed}"):
            return

    profession_keys = _PROFESSION_ENUM_RE.findall(profession_src)
    if not check("Appearance", bool(profession_keys),
                 "Profession.java declares at least one profession key"):
        return

    check("Appearance", len(arrays["SKIN_KEYS"]) == counts["SKIN_COUNT"],
          f"SKIN_KEYS has {len(arrays['SKIN_KEYS'])} entries (SettlerAppearance.SKIN_COUNT="
          f"{counts['SKIN_COUNT']})")
    check("Appearance", len(arrays["HAIR_COLOR_KEYS"]) == counts["HAIR_COLOR_COUNT"],
          f"HAIR_COLOR_KEYS has {len(arrays['HAIR_COLOR_KEYS'])} entries "
          f"(SettlerAppearance.HAIR_COLOR_COUNT={counts['HAIR_COLOR_COUNT']})")

    # The exact cross product SettlerTextureCache.compose() will ask for by
    # ResourceLocation, mirrored from its own naming (base_<skin>.png,
    # hair_<styleIndex>_<colorKey>.png, face_<i>.png, clothing_<i>.png,
    # outfit_<professionKey>.png).
    expected = set()
    for skin in arrays["SKIN_KEYS"]:
        expected.add(f"base_{skin}.png")
    for style_idx in range(counts["HAIR_STYLE_COUNT"]):
        for color in arrays["HAIR_COLOR_KEYS"]:
            expected.add(f"hair_{style_idx}_{color}.png")
    for i in range(counts["FACE_COUNT"]):
        expected.add(f"face_{i}.png")
    for i in range(counts["CLOTHING_COUNT"]):
        expected.add(f"clothing_{i}.png")
    for prof_key in profession_keys:
        expected.add(f"outfit_{prof_key}.png")

    layers_dir = MOD_ASSETS / "textures" / "entity" / "settler" / "layers"
    if not check("Appearance", layers_dir.is_dir(),
                 f"{rel(layers_dir)} exists"):
        return
    actual = {p.name for p in layers_dir.glob("*.png")}

    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    check("Appearance", not missing,
          f"every layer file the Java cross product implies is present ({len(expected)} expected)"
          if not missing else
          f"layers/ is missing files the Java constants/keys imply should exist: {', '.join(missing)}")
    check("Appearance", not extra,
          "no orphaned layer files beyond what the Java cross product implies"
          if not extra else
          f"layers/ has files no longer implied by the Java constants/keys (rename or cardinality "
          f"drift?): {', '.join(extra)}")


# --------------------------------------------------------------------------
# Reporting / main
# --------------------------------------------------------------------------

CATEGORY_ORDER = ["Registry", "Meta", "Blocks", "Items", "Entities", "Lang",
                  "JSON", "Textures", "Sounds", "Recipes", "Tags",
                  "Structures", "Pipeline", "Appearance", "Info"]


def print_report(quiet: bool) -> None:
    ordered = [c for c in CATEGORY_ORDER if c in RESULTS]
    ordered += [c for c in RESULTS if c not in ordered]
    for category in ordered:
        entries = RESULTS[category]
        shown = entries if not quiet else [e for e in entries if e[0] in ("✗", "⚠")]
        if quiet and not shown:
            continue
        print(f"== {category} ==")
        for symbol, message in shown:
            print(f"  {symbol} {message}")
        print()

    total = COUNTS["pass"] + COUNTS["fail"]
    verdict = "PASS" if COUNTS["fail"] == 0 else "FAIL"
    print(f"{verdict}: {COUNTS['pass']}/{total} checks passed, "
          f"{COUNTS['fail']} error(s), {COUNTS['warn']} warning(s)")
    if COUNTS["fail"]:
        print("Failures:")
        for category in ordered:
            for symbol, message in RESULTS[category]:
                if symbol == "✗":
                    print(f"  ✗ [{category}] {message}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate Hearthstead mod resources.")
    parser.add_argument("--quiet", action="store_true",
                        help="CI-style output: only failures, warnings and the summary")
    args = parser.parse_args(argv)

    if not PROJECT_ROOT.is_dir() or not (PROJECT_ROOT / "src").is_dir():
        print(f"error: project root not found at {PROJECT_ROOT}", file=sys.stderr)
        return 2

    registries, blockitems = scan_registrations()
    langs = load_langs()

    check_meta()
    check_blocks(registries["blocks"], langs)
    check_items(registries["items"], registries["blocks"], blockitems, langs)
    check_entities(registries["entities"], langs)
    check_settler_activities(langs)
    check_json_integrity()

    subtitles_declared = False
    sounds_json = MOD_ASSETS / "sounds.json"
    if sounds_json.is_file():
        sdata, serr = load_json(sounds_json)
        if serr is None and isinstance(sdata, dict):
            subtitles_declared = any(isinstance(v, dict) and "subtitle" in v
                                     for v in sdata.values())
    any_keys_needed = bool(registries["blocks"] or registries["items"]
                           or registries["entities"] or subtitles_declared)
    check_lang(langs, any_keys_needed)

    check_sounds(registries["sounds"], langs)
    check_textures()
    check_recipes(registries["blocks"], registries["items"])
    check_tags(registries)
    check_structures()
    check_pipeline()
    check_appearance_binding()

    # 13. Menus / BlockEntities: no resource requirement — informational.
    info("Info", f"menus registered: {len(registries['menus'])}")
    info("Info", f"block entities registered: {len(registries['block_entities'])}")
    if registries["other"]:
        info("Info", f"other registrations (unclassified): "
                     f"{', '.join(sorted(registries['other']))}")

    print_report(args.quiet)
    return 0 if COUNTS["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

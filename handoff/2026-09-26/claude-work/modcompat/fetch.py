"""Resolve and download mods from the official Modrinth API (1.21.1 + neoforge only).
Verifies sha512 against the API and writes DOWNLOADS.md."""
import hashlib, json, os, sys, urllib.request, urllib.parse

API = "https://api.modrinth.com/v2"
UA = {"User-Agent": "bannerhold-modcompat-test/1.0 (tobtheboyplays)"}
ROOT = os.path.dirname(os.path.abspath(__file__))
MODS = os.path.join(ROOT, "mods")
PACKS = os.path.join(ROOT, "packs")

# (slug, pack, kind)  kind: mod | resourcepack | shader
WANTED = [
    ("simple-voice-chat", "atmosphere", "mod"),
    ("sound-physics-remastered", "atmosphere", "mod"),
    ("ambientsounds", "atmosphere", "mod"),
    ("sodium", "atmosphere", "mod"),
    ("iris", "atmosphere", "mod"),
    ("entity-model-features", "atmosphere", "mod"),
    ("entitytexturefeatures", "atmosphere", "mod"),
    ("fresh-animations", "atmosphere", "resourcepack"),
    ("complementary-reimagined", "atmosphere", "shader"),
    ("macaws-roofs", "building", "mod"),
    ("macaws-windows", "building", "mod"),
    ("macaws-doors", "building", "mod"),
    ("macaws-bridges", "building", "mod"),
    ("macaws-fences-and-walls", "building", "mod"),
    ("supplementaries", "building", "mod"),
    ("farmers-delight", "content", "mod"),
    ("brewin-and-chewin", "content", "mod"),
    ("serene-seasons", "content", "mod"),
]


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def versions(pid, kind):
    q = {"game_versions": json.dumps(["1.21.1"])}
    if kind == "mod":
        q["loaders"] = json.dumps(["neoforge"])
    elif kind == "shader":
        q["loaders"] = json.dumps(["iris"])
    else:
        q["loaders"] = json.dumps(["minecraft"])
    vs = get(f"{API}/project/{pid}/version?" + urllib.parse.urlencode(q))
    # prefer release, newest first (API already sorts newest first)
    rel = [v for v in vs if v["version_type"] == "release"]
    return rel or vs


records, seen, missing = [], {}, []
# Iris 1.8.12 (newest "release") targets Sodium 0.6 and declares minecraft [1.21,1.21.1);
# the only official Iris build for Sodium 0.8.x on 1.21.1 NeoForge is 1.8.14-beta.1.
PIN = {"iris": "1.8.14-beta.1+1.21.1-neoforge"}


def resolve(slug_or_id, pack, kind, required_by=None):
    proj = get(f"{API}/project/{slug_or_id}")
    pid, slug = proj["id"], proj["slug"]
    if pid in seen:
        seen[pid]["packs"].add(pack)
        return
    vs = versions(pid, kind)
    if slug in PIN:
        vs = [v for v in get(f"{API}/project/{pid}/version") if v["version_number"] == PIN[slug]]
    if not vs:
        missing.append((slug, pack, required_by))
        seen[pid] = {"packs": {pack}}
        return
    v = vs[0]
    f = next((x for x in v["files"] if x["primary"]), v["files"][0])
    entry = {"slug": slug, "title": proj["title"], "kind": kind, "version": v["version_number"],
             "file": f["filename"], "url": f["url"], "size": f["size"], "sha512": f["hashes"]["sha512"],
             "client_side": proj["client_side"], "server_side": proj["server_side"],
             "packs": {pack}, "required_by": required_by, "deps": []}
    seen[pid] = entry
    records.append(entry)
    for d in v["dependencies"]:
        if d["dependency_type"] == "required" and d.get("project_id"):
            dp = get(f"{API}/project/{d['project_id']}")
            entry["deps"].append(dp["slug"])
            resolve(d["project_id"], pack, "mod", required_by=slug)


def download(e):
    dest_dir = MODS if e["kind"] == "mod" else PACKS
    dest = os.path.join(dest_dir, e["file"])
    if not os.path.exists(dest):
        assert e["url"].startswith("https://cdn.modrinth.com/"), e["url"]
        with urllib.request.urlopen(urllib.request.Request(e["url"], headers=UA), timeout=300) as r:
            data = r.read()
        open(dest, "wb").write(data)
    data = open(dest, "rb").read()
    h = hashlib.sha512(data).hexdigest()
    if h != e["sha512"] or len(data) != e["size"]:
        os.remove(dest)
        raise SystemExit(f"HASH MISMATCH {e['file']}")
    e["verified"] = True


for slug, pack, kind in WANTED:
    try:
        resolve(slug, pack, kind)
    except urllib.error.HTTPError as ex:
        missing.append((slug, pack, f"HTTP {ex.code}"))

for e in records:
    download(e)
    print("ok", e["file"], e["size"])

with open(os.path.join(ROOT, "DOWNLOADS.md"), "w", encoding="utf-8") as out:
    out.write("# Mod-compat test downloads\n\n")
    out.write("Source: Modrinth official API (api.modrinth.com) / CDN (cdn.modrinth.com). "
              "Filter: game version 1.21.1, loader neoforge (shader: iris, resource pack: minecraft). "
              "Newest release build chosen. Every file's sha512 and size verified against the API.\n\n")
    out.write("| Pack | Project | Version | File | Size (bytes) | Env (client/server) | Required by | Source URL | sha512 |\n")
    out.write("|---|---|---|---|---|---|---|---|---|\n")
    for e in records:
        out.write(f"| {','.join(sorted(e['packs']))} | {e['title']} ({e['slug']}) | {e['version']} | {e['file']} | {e['size']} | "
                  f"{e['client_side']}/{e['server_side']} | {e['required_by'] or '-'} | {e['url']} | `{e['sha512']}` |\n")
    if missing:
        out.write("\n## Not available for 1.21.1 + NeoForge\n\n")
        for m in missing:
            out.write(f"- {m}\n")
json.dump([{**e, "packs": sorted(e["packs"])} for e in records], open(os.path.join(ROOT, "downloads.json"), "w"), indent=1)
print("missing:", missing)

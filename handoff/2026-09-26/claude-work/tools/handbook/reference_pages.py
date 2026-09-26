# Reference chapters generated from tools/handbook/facts/*.json (which gen_facts.py
# re-reads from the code): every job, building, world event, conversation, tech node,
# key binding and config switch gets a row, so ReferenceGuardTest can prove coverage.
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FACTS = os.path.join(HERE, "facts")
PER_PAGE = 8


EXTRA_SECTIONS = {
    "livingVillage": "Settler life around the village: chatter, idle visits and small daily habits.",
    "weapons": "Damage, speed and reach of the captain weapons per type and tier.",
}


def load(name):
    with open(os.path.join(FACTS, name + ".json"), encoding="utf-8") as f:
        return json.load(f)


def slug(s):
    return "".join(c if c.isalnum() else "_" for c in s.lower()).strip("_")


def rows_pages(L, page_fn, chapter, title, bullets, tip, rows, prefix="list"):
    """rows: list of (ref, icon, name, text). Splits into pages of PER_PAGE (ids chapter.<prefix>N)."""
    pages = []
    chunks = [rows[i:i + PER_PAGE] for i in range(0, len(rows), PER_PAGE)] or [[]]
    for n, chunk in enumerate(chunks):
        t = title if len(chunks) == 1 else "%s (%d/%d)" % (title, n + 1, len(chunks))
        p = page_fn(chapter, "%s%d" % (prefix, n + 1), t, bullets if n == 0 else [], tip=tip if n == 0 else None)
        p["entries"] = []
        for ref, icon, name, text in chunk:
            base = "hearthstead.guide.ref.%s.%s" % (chapter, slug(ref.split(":", 1)[1]))
            e = {"ref": ref, "name": L(base + ".name", name)}
            if icon:
                e["icon"] = icon
            if text:
                e["text"] = L(base + ".text", text)
            p["entries"].append(e)
        if not p["bullets"]:
            p["bullets"] = [L("hearthstead.guide.ref.more", "Continued from the previous page.")]
        pages.append(p)
    return pages


def join(*parts):
    return " ".join(x.strip() for x in parts if x and x.strip())


def build(L, page_fn, en):
    """Returns {chapter_id: (icon, title, pages)}; en = current English lang dict."""
    out = {}

    jobs = []
    for p in load("professions"):
        building = p.get("building") or ""
        where = "" if building in ("", "none") else "Works at: %s." % building.replace("_", " ").title()
        jobs.append(("profession:" + p["id"], p.get("icon"), p["name"],
                     join(p.get("does", ""), "Hire: " + p["hire"] if p.get("hire") else "", where)))
    out["jobs_directory"] = ("hearthstead:lumberer_emblem", "All jobs", rows_pages(
        L, page_fn, "jobs_directory", "All jobs",
        ["Every job in Bannerhold: what it does, how you hire it and where it works."],
        "Buy an Emblem from the Mayor, then give it to a settler.", jobs))

    buildings = []
    for b in load("buildings"):
        kind = "Work yard (outdoor area)." if b.get("kind") == "yard" else "Room."
        buildings.append(("building:" + b["id"], b.get("icon"), b["name"],
                          join(b.get("purpose", ""), kind, "Needs: " + b["build"].replace("Room: ", "")
                               .replace("Yard: ", "") + "." if b.get("build") else "",
                               "Levels: " + b["levels"] + "." if b.get("levels") else "")))
    out["buildings_directory"] = ("hearthstead:plaque", "All buildings", rows_pages(
        L, page_fn, "buildings_directory", "All buildings",
        ["Every building: what it is for, what the plaque checks, and its levels."],
        "Right-click a plaque to see exactly what is still missing.", buildings))

    events = []
    for e in load("events"):
        prefix = "conversation:" if e.get("category") == "conversation" else "event:"
        events.append((prefix + e["id"].split(":")[-1], e.get("icon") or ("minecraft:bell" if prefix == "event:"
                       else "minecraft:writable_book"), e["name"], join(e.get("what", ""), e.get("you", ""))))
    out["events_directory"] = ("minecraft:bell", "Events and visitors", rows_pages(
        L, page_fn, "events_directory", "Events and visitors",
        ["Everything that can happen around your settlement, and what to do about it."],
        "Watch for the ! marker on the realm map.", events))

    branches = {}
    for n in load("nodes"):
        branches.setdefault(n.get("branch_id") or "other", []).append(n)
    pages = []
    for bid, nodes in branches.items():
        rows = []
        for n in nodes:
            name = n["name"]
            rows.append(("node:" + n["id"], n.get("icon"), name, n.get("offers") or n.get("unlocks") or ""))
        label = nodes[0].get("branch", bid).split(",")[0]
        pages += rows_pages(L, page_fn, "tech_branches", "%s branch" % label,
                            ["Every node of the %s branch and what it gives you." % label],
                            "Open the Banner, then Tech Tree, to learn a node.", rows, prefix=bid)
    out["tech_branches"] = ("minecraft:lectern", "Tech Tree branches", pages)

    keys = load("keys")
    controls = page_fn("options", "controls", "Controls",
                       ["Every Bannerhold key. Change them in Options, Controls, Bannerhold."],
                       tip="The Handbook shows your own keys, even after you change them.")
    controls["keys"] = [{"key": k["id"], "action": L("hearthstead.guide.ref.options.key_" + slug(k["id"]),
                         k["name"].split(" (")[0])} for k in keys]
    opts = load("options")
    sections = {}
    for o in opts:
        sections.setdefault(o["section"], []).append(o)
    switch_rows = []
    for o in opts:
        if o["section"] == "features":
            name = o["id"].split(".", 1)[1]
            switch_rows.append(("option:" + o["id"], "minecraft:lever", name, join(o.get("does", ""),
                                "Default: %s." % o.get("default", ""))))
    section_rows = []
    for sec, items in sections.items():
        names = ", ".join(i["id"].split(".")[-1] for i in items[:4]) + (" …" if len(items) > 4 else "")
        section_rows.append(("option:" + sec, "minecraft:comparator", "[%s]" % sec,
                             "%d settings, e.g. %s." % (len(items), names)))
    # Sections registered outside the scanned config classes (keep until gen_facts reads them).
    for sec, text in EXTRA_SECTIONS.items():
        if sec not in sections:
            section_rows.append(("option:" + sec, "minecraft:comparator", "[%s]" % sec, text))
    opt_pages = [controls]
    opt_pages += rows_pages(L, page_fn, "options", "Feature switches",
                            ["Server owners can turn whole systems off in hearthstead-server.toml, [features]."],
                            "Turn a system off if it breaks on your server; your world keeps its data.",
                            switch_rows, prefix="switches")
    opt_pages += rows_pages(L, page_fn, "options", "Config sections",
                            ["What each section of the config files controls."],
                            "Change settings with the game closed, then restart.", section_rows, prefix="sections")
    out["options"] = ("minecraft:comparator", "Options", opt_pages)
    return out

"""Research: measure vanilla 1.21.1 sounds (local asset index, analysis only, never redistributed)."""
import json, os, sys, subprocess, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audiolib as A
BASE = r"C:\Users\tobia\.gradle\caches\neoformruntime\assets"
idx = json.load(open(BASE + r"\indexes\17.json"))["objects"]
def path(k):
    h = idx[k]["hash"]; return os.path.join(BASE, "objects", h[:2], h)
sj = json.load(open(path("minecraft/sounds.json")))
EVENTS = sys.argv[1:] or [
 "entity.villager.work_armorer","entity.villager.work_butcher","entity.villager.work_cartographer","entity.villager.work_cleric",
 "entity.villager.work_farmer","entity.villager.work_fisherman","entity.villager.work_fletcher","entity.villager.work_leatherworker",
 "entity.villager.work_librarian","entity.villager.work_mason","entity.villager.work_shepherd","entity.villager.work_toolsmith",
 "entity.villager.work_weaponsmith","block.wood.hit","block.wood.break","block.stone.hit","block.stone.break","block.metal.hit",
 "block.metal.break","block.anvil.use","block.anvil.land","entity.villager.hurt","entity.villager.death","entity.pillager.hurt",
 "entity.pillager.death","entity.pillager.ambient","entity.vindicator.celebrate","entity.player.hurt","entity.iron_golem.attack","entity.player.attack.strong",
 "entity.player.attack.sweep","entity.player.attack.crit","item.shield.block","block.bell.use","event.raid.horn","ui.button.click",
 "entity.player.levelup","entity.item.pickup","entity.experience_orb.pickup","ui.toast.challenge_complete","item.book.page_turn",
 "block.chain.hit","item.bundle.insert","block.grindstone.use","block.smithing_table.use","entity.wolf.ambient","entity.ravager.roar",
 "block.campfire.crackle","ambient.cave","weather.rain","entity.fishing_bobber.splash","entity.sheep.shear","entity.cow.milk","block.barrel.open"]
rows = []
for ev in EVENTS:
    e = sj.get(ev)
    if not e: print("no", ev); continue
    files = []; vols = set(); pits = set(); streams = False; atts = set()
    for s in e["sounds"]:
        s = {"name": s} if isinstance(s, str) else s
        if s.get("type") == "event": continue
        files.append(s["name"]); vols.add(s.get("volume", 1.0)); pits.add(s.get("pitch", 1.0)); streams |= s.get("stream", False)
        if "attenuation_distance" in s: atts.add(s["attenuation_distance"])
    st = []
    for f in files[:6]:
        k = "minecraft/sounds/" + f.replace("minecraft:", "") + ".ogg"
        if k not in idx: continue
        p = path(k)
        info = subprocess.run(["ffprobe","-v","error","-show_entries","stream=channels,sample_rate","-of","csv=p=0",p],capture_output=True,text=True).stdout.strip()
        x = A.decode(p); e_ = A.env_db(x, 0.005)
        pk = int(np.argmax(e_)); tail = np.where(e_[pk:] < e_.max() - 30)[0]
        st.append(dict(dur=len(x)/A.SR, lufs=A.lufs(x), peak=A.peak_db(x), cen=A.spectral_centroid(x), hf=A.hf_ratio(x),
                       att=pk*0.005, dec=(tail[0]*0.005 if len(tail) else len(x)/A.SR-pk*0.005), fmt=info))
    if not st: continue
    m = lambda k: np.mean([s[k] for s in st])
    rows.append((ev, len(files), m("dur"), m("att"), m("dec"), m("lufs"), m("peak"), m("cen"), m("hf"), st[0]["fmt"],
                 sorted(vols), sorted(pits), e.get("subtitle", ""), sorted(atts)))
print(f"{'event':34s} var  dur   atk   dec30  LUFS  peak  cent   hf   fmt       vol        pitch      subtitle")
for r in rows:
    print(f"{r[0]:34s} {r[1]:3d} {r[2]:5.2f} {r[3]:5.3f} {r[4]:5.2f} {r[5]:6.1f} {r[6]:5.1f} {r[7]:5.0f} {r[8]:5.3f} {r[9]:9s} {str(r[10])[:10]:10s} {str(r[11])[:10]:10s} {r[12]} {r[13] or ''}")

import os, sys, glob, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audiolib as A
from process import bursts
W = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
def m(x):
    raw_clip = A.clip_ratio(x, 0.995)
    y = A.trim(A.hp(x, 40), -42)
    e = A.env_db(y, 0.005); pk = int(np.argmax(e)); tail = np.where(e[pk:] < e.max() - 30)[0]
    return dict(active=round(len(y)/A.SR,2), atk=round(pk*0.005,3), dec30=round(tail[0]*0.005 if len(tail) else len(y)/A.SR,2),
                cen=int(A.spectral_centroid(y)), hf=round(A.hf_ratio(y),3), b=bursts(y), clip=round(raw_clip,4),
                floor=round(float(np.percentile(A.env_db(x,0.01),10)-A.env_db(x,0.01).max()),1), lufs=round(A.lufs(x),1))
for d in sorted(glob.glob(W + r"\raw\_test\*")):
    for f in sorted(glob.glob(d + r"\c*.wav")):
        print(f"{os.path.basename(d):16s} {os.path.basename(f)[:3]}", m(A.decode(f)))
refs = {"vanilla anvil_use": "random_anvil_use", "vanilla anvil_land": "random_anvil_land", "vanilla smithing": "block_smithing_table_smithing_table1", "vanilla dig_wood": "dig_wood1", "vanilla woodbreak": "mob_zombie_woodbreak", "vanilla axe_strip":"item_axe_strip1"}
for k, v in refs.items():
    print(f"{k:20s}", m(A.decode(W + rf"\vanilla_ref\{v}.ogg")))

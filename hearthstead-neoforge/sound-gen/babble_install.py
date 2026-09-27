"""Install the sliced babble banks into the mod: files, sounds.json events, subtitles.

Event ids (what the conversation lane's ConversationVoice plays):
  voice.<bank>.babble                     12-16 syllables of one consistent voice
  voice.<bank>.<laugh|hmm|angry|surprised|agree|farewell>
Banks: m_young m_adult m_old f_young f_adult f_old brute goblin captain peddler envoy minstrel,
plus coarse aliases man -> m_adult, woman -> f_adult, old -> m_old, old_woman -> f_old.
"""
import json, os, shutil, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import apply as P  # noqa: E402
import babble as B  # noqa: E402

EMOTE_ID = {"laugh": "laugh", "hmm": "hmm", "angry": "angry", "oh": "surprised", "mmhm": "agree", "bye": "farewell"}
WHO = {"m_young": "Young man", "m_adult": "Man", "m_old": "Old man", "f_young": "Young woman", "f_adult": "Woman",
       "f_old": "Old woman", "brute": "Brute", "goblin": "Goblin", "captain": "Captain", "peddler": "Peddler",
       "envoy": "Envoy", "minstrel": "Minstrel"}
EMOTE_SUB = {"laugh": "laughs", "hmm": "ponders", "angry": "growls", "surprised": "gasps", "agree": "agrees",
             "farewell": "says farewell"}
ALIASES = {"man": "m_adult", "woman": "f_adult", "old": "m_old", "old_woman": "f_old"}
VOLUME = 1.0          # files are at -20 LUFS; ConversationVoice plays them at 0.24-0.34
ATTENUATION = 12


def main():
    snd, crlf = P.load_json(P.SOUNDS_JSON)
    changed, subs = [], {}
    for bank in B.BANKS:
        src = os.path.join(B.DIR, bank, "out")
        dst = os.path.join(P.ASSETS, "sounds", "el", "voice", bank)
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        os.makedirs(dst)
        files = sorted(os.listdir(src))
        for f in files:
            shutil.copyfile(os.path.join(src, f), os.path.join(dst, f))
        babble = sorted((f[:-4] for f in files if f.startswith("babble")), key=lambda n: int(n[6:]))
        ev = f"voice.{bank}.babble"
        snd[ev] = {"sounds": [{"name": f"hearthstead:el/voice/{bank}/{n}", "volume": VOLUME,
                               "attenuation_distance": ATTENUATION} for n in babble],
                   "subtitle": f"subtitles.hearthstead.voice.{bank}.babble"}
        subs[f"subtitles.hearthstead.voice.{bank}.babble"] = f"{WHO[bank]} talks"
        changed.append(ev)
        have = {f[:-4] for f in files}
        for mine, theirs in EMOTE_ID.items():
            ev = f"voice.{bank}.{theirs}"
            if mine in have:
                sounds = [{"name": f"hearthstead:el/voice/{bank}/{mine}", "volume": VOLUME,
                           "attenuation_distance": ATTENUATION}]
            else:   # emote cut failed for this voice: use the same voice's "agree" rather than a stranger's
                fb = "mmhm" if "mmhm" in have else "hmm"
                sounds = [{"name": f"hearthstead:el/voice/{bank}/{fb}", "volume": VOLUME,
                           "attenuation_distance": ATTENUATION}]
            snd[ev] = {"sounds": sounds, "subtitle": f"subtitles.hearthstead.voice.{bank}.{theirs}"}
            subs[f"subtitles.hearthstead.voice.{bank}.{theirs}"] = f"{WHO[bank]} {EMOTE_SUB[theirs]}"
            changed.append(ev)
    for alias, bank in ALIASES.items():
        for part in ["babble"] + list(EMOTE_ID.values()):
            ev = f"voice.{alias}.{part}"
            snd[ev] = {"sounds": [{"name": f"hearthstead:voice.{bank}.{part}", "type": "event"}],
                       "subtitle": f"subtitles.hearthstead.voice.{bank}.{part}"}
            changed.append(ev)
    P.dump_sounds(snd, crlf, changed)
    n = P.apply_lang(subs)
    print(f"{len(changed)} voice events, {n} subtitles added")


if __name__ == "__main__":
    main()

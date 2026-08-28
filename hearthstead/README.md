# Hearthstead

*Found a hearth. Welcome settlers. Watch a living medieval settlement grow.*

Hearthstead is an original settlement mod for **Minecraft Java Edition 1.20.1 (Forge)**.
Place a Settlement Hearth, welcome your first three settlers, hand them profession
writs, and keep the communal stores full while they farm, fell timber, and stand
guard through day and night — with real needs, morale, and a settlement that grows
when it thrives.

This is a **vertical-slice prototype**: a complete, playable core loop built to
answer whether a full living-settlement mod is worth developing. It is fully
original — code, models, animations, textures, UI, and sounds.

| | |
|---|---|
| Minecraft | 1.20.1 (Java Edition) |
| Mod loader | Forge 47.4.23 (any 47.x should work) |
| Java | 17 (bundled with the launcher) |
| Sides | Client **and** dedicated server |
| Languages | English, Norsk bokmål |
| Dependencies | None — completely standalone |

---

## Installation

### Via the CurseForge app (recommended)

1. Open the **CurseForge app** → Minecraft → **Create Custom Profile**.
2. Name it (e.g. *Hearthstead*), choose **Minecraft 1.20.1** and **Forge**
   (latest 47.x — 47.4.23 is what the mod was built against). Create it.
3. Right-click the new profile → **Open Folder**, and open the `mods` folder
   (create it if missing).
4. Copy `hearthstead-0.1.0.jar` (from `dist/`) into `mods/`.
5. Press **Play** on the profile.

### Via the vanilla launcher

1. Install Forge 1.20.1-47.4.23 with the official installer
   (from files.minecraftforge.net).
2. Drop `hearthstead-0.1.0.jar` into your `.minecraft/mods/` folder.
3. Start the *forge-1.20.1* profile.

### On a dedicated server

Drop the same jar into the server's `mods/` folder. The mod is fully
server-compatible; clients joining must also have it installed.

---

## The five-minute test

1. **Create a world.** Any world works; a superflat *(or a plains area)* makes
   everything easy to watch. Peaceful difficulty is fine for the work loop, but
   you need Easy+ to see guards fight.
2. **Run** `/hearthstead demo` — you receive the Settlement Hearth, all three
   profession writs, bread, seeds, saplings, a hoe, the Settler's Handbook,
   and settler spawn eggs.
3. **Place the Hearth** on open ground. Within a moment the founding bell
   sounds and **three settlers gather at the fire** — your settlement now has
   a name and a working radius (48 blocks).
4. **Stock the larder:** right-click the hearth and put the **bread** into the
   communal stores. Note the live population, employment, food, radius, and
   the colored **morale bar**.
5. **Assign professions:** use the **Farmer's Writ** on a settler (they cheer
   and shoulder a hoe), the **Lumberer's Writ** on another, the **Guard's
   Writ** on the third. *Sneak-use a writ to un-assign. Writs are never
   consumed.*
6. **Give them work:**
   - Till a little farmland nearby, plant the seeds (or find a village farm) —
     the **farmer** walks over, works mature crops with visible strokes,
     replants, and carries the yield back to the hearth.
   - Make sure a tree stands within the radius (plant a sapling + bone meal if
     needed) — the **lumberer** fells it top-down, replants a sapling, and
     hauls the logs home.
   - The **guard** patrols a ring around the hearth. Spawn a zombie nearby
     (night or spawn egg): the alarm bell rings, civilians flee to the fire,
     and the guard attacks.
7. **Inspect a settler:** right-click any settler for their card — profession,
   current activity, and live hunger/energy/morale bars. Their nameplate also
   shows what they're doing when you look at them.
8. **Watch the rhythm:** settlers work through the day, fetch a meal from the
   hearth when hungry (you'll see them eat), and settle down by the fire at
   night. Guards keep watch.
9. **Growth:** keep ≥ 8 food stored and morale *Content* or better — the
   recruit bar fills in the hearth screen and a **traveler** soon appears at
   the settlement edge, walks to the fire, and joins (costing a little food).
   `/hearthstead recruit` (op) skips the wait.

`/hearthstead info` prints the nearest settlement's stats to chat. The
**Settler's Handbook** (craft: book + oak sapling) explains everything in-game,
in both languages.

## Crafting

| Item | Recipe |
|---|---|
| Settlement Hearth | 3× any log / cobblestone, **campfire**, cobblestone / 3× cobblestone (shaped, 3×3) |
| Farmer's / Lumberer's / Guard's Writ | paper + string + wooden hoe / axe / sword |
| Settler's Handbook | book + oak sapling |

## For developers

The full source lives in this folder — a standard ForgeGradle 6 project:

```
./gradlew build              # build the mod jar (build/libs/)
./gradlew runGameTestServer  # run the automated GameTest suite headlessly
./gradlew runClient          # dev client
python3 tools/validate_assets.py  # cross-check every resource
```

All textures, sounds, and GameTest structures are generated deterministically
by the scripts in `tools/` — the pixel art and DSP synthesis are code, so the
assets are original by construction and reproducible.

See `PROTOTYPE_REPORT.md` for an honest account of what is implemented, what
was actually executed in verification, known limitations, and the recommended
next feature. See `RECOMMENDED_MODS.md` for optional atmosphere mods that pair
well with Hearthstead.

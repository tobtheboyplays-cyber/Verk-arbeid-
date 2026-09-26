# Bannerhold — Guard Control: research and pitches

*26 Sep 2026. Research into how games and Minecraft mods let a player command friendly soldiers, followed by three control pitches for Bannerhold (NeoForge 1.21.1, 3–4 friends, one shared kingdom).*

**Owner brief (fixed):** guard CONTROL matters most in combat. MineColonies and Tektopia combat is too passive, the waves are boring and there are no choices. The player is both commander and fighter. Knights (melee) and archers must be controlled differently. With no orders, soldiers should still defend smartly.
**Rejected already, so not reused:** radial "command horn" wheel; horn-call patterns for knights plus colored signal arrows for archers; "lead by example" (soldiers mirror your stance); pre-raid war-table plan.

Labels: claims marked *(our read)* are design judgement and have no source. Everything else links to the sources at the bottom.

---

## 1. Reference table

| # | Game / mod | How control works | What players like | What players dislike | Relevance for Bannerhold |
|---|---|---|---|---|---|
| 1 | **Villager Recruits** (talhanation, Forge/NeoForge) | Hold **R** to open a command screen with 3 tabs (combat / movement / other); scroll the mouse wheel to switch tab. Orders: Move (to the block you look at), Follow me, Hold my/your position, Back to position, Forward/Backward ~10 blocks; Neutral/Aggressive/Raid stances; "Hail of arrows" (ranged units keep shooting one spot); Protect this; toggle Shields. Groups can be renamed, split and merged, and commanded even while unloaded. Formations (Line/Square/Circle/Wedge), a "Face" order that turns the group toward your look direction, "Hold formation" (they do not run at enemies) and "tightness". Ctrl+right-click one recruit opens its own screen. [1][2][3][4] | The deepest army control in Minecraft: groups, formations, look-to-move, stances, and a bowmen-only area fire. | *(our read)* The command screen is a full GUI that covers your view mid-fight. There are many buttons, and a two-step "formation, then hold" flow. Commanding and fighting at the same time is hard. | The best model for **look-to-point orders**, **area fire for archers** and **groups**. Avoid the modal screen. |
| 2 | **MineColonies** guards + **Rallying Banner** | Per guard in the Guard Tower GUI: **Patrol** (automatic or scepter points), **Guard** (one spot), **Follow** (loose or tight). Retreat toggle, per-mob hostility list. Knight / Archer / Druid classes. Rallying Banner: shift+right-click towers to link them, shift+right-click a block to summon their guards there, and again to dismiss. Guards do not follow past the colony border. [5][6] | Solid "set and forget" defence; per-mob lists; the rally lets you finally pull guards to a fight. | The owner: too passive, guards do everything, boring waves, no choices. Rally is a separate item with a link-towers setup step. Bug reports say guards ignore an active rally and keep patrolling. [7][8] | Proof that **peacetime setup** (posts, patrols) is not enough. The live layer is missing. The rally bugs show that **orders must visibly confirm** who obeyed. |
| 3 | **TekTopia** | Guards patrol and respond on their own. The only player control is **Guard Post** placement (guards spread over the posts) plus a Captain who gives an aura. [9][10] | Charming, self-running guards; barracks training. | No player-issued commands at all. Players ask for smarter role behaviour, for example a Captain who supervises instead of taking a post. [11] | A clear anti-reference for control. Keep the good part: **posts as the default anchor**. |
| 4 | **Guard Villagers** (+ *Rally of the Guard* addon) | Guard GUI (needs Hero of the Village): Follow or Patrol. On a patrol point, **melee guards walk around it while ranged guards stand still and snipe**. Ringing a village bell makes nearby guards follow you. The addon adds a Scroll of Rallying (teleports hired guards to you) and a Commander's Ledger for summoning and patrols. [12][13][14] | Simple; the bell rally fits the fiction; different melee and ranged behaviour at the same point. | *(our read)* Teleport summons break the fantasy. Orders go to one guard at a time. | **The same order means different things for knights and archers.** The **bell as a mass-command** matches our existing alarm bell. |
| 5 | **Other Minecraft army mods**: LOTR Mod horns, *Hundred Years Warfare*, *Human/Modern Companions* | LOTR: a **Horn of Command** item toggles Halt/Ready, a separate horn summons (teleports) units in loaded chunks. Hundred Years Warfare (NeoForge 1.21.1) offers three control methods: **RTS mode** (hotkeys, formations, shift-queued orders), a **command wheel** and a **command staff**. Modern Companions (NeoForge 1.21.1): companion screen with Follow/Patrol/Guard/Alert/Hunting/Radius, and a wand recalls everyone. [15][16][17][18] | Choice of control style; the RTS mode gives real tactics. | *(our read)* Item-per-order and screen-per-companion is slow. Teleport recall again. | Shows the Minecraft ceiling: nobody has made **fast first-person commanding while fighting** feel good. That is our opening. |
| 6 | **Minecraft Legends** (official) | Rally: wave the banner and nearby units follow you. **Charge**: plant a flag in front of you and nearby units rush it, then hold there. Tap sends one unit, hold sends all. The advanced mode is an overhead cursor with unit-type selection, Charge and **Focus** (attack only that target). **Orders only reach units near the hero.** [19][20] | Accessible; leading from the front feels heroic; earshot makes position matter. | "Point, click, and hope for the best" (GamesRadar); poor AI; players fell back on one unit type because control was too coarse. [20] | **Earshot**, **tap vs hold**, **Charge vs Focus**. Warning: coarse control plus dumb AI feels like hoping, not commanding. |
| 7 | **Mount & Blade II: Bannerlord** | Number keys pick formations (infantry, archers, cavalry; 0 = all). F-key menus: **F1 movement** (To position where you look, Follow me, Charge, Advance, Fall back, Stop, Retreat), **F2 facing**, **F3 formation** (line, shield wall, loose, circle, square, skein, column, scatter), **F4 fire at will / hold fire**, **F6 delegate to AI**. All from first person while riding and fighting. [21][22] | Real formation play in first person; shield walls and archer placement matter; F6 lets you just fight. | "So clunky many don't use it"; sequential key chains ("8 button presses"); left out of the tutorial; players ask for a wheel or cursor. Commanding and fighting at once is a known struggle ("running round in circles trying to figure out where my troops ran off to"), and delegated AI does worse than auto-resolve. [23][24] | Take **look-to-place**, **shield wall**, **hold fire / fire at will**. Avoid **deep key chains**. The AI you delegate to must be good. |
| 8 | **Conqueror's Blade** (hero + one unit, third person) | **X tap** = unit stops and locks around you. **X hold** = send the unit anywhere in your field of view, with orientation. Follow, Attack (melee breaks formation). **B hold** = retreat. F1–F3 = formations. 1–4 = unit skills. **Ctrl** = interaction mode (ladders, rams, supply). [25] | Commanding is called the strongest part: an "intuitive control system" while your hero fights. [26] | No control after the hero dies; units stream mindlessly up ladders one by one; players want "follow me / go there after climbing". [27] | The best existing **hero-who-fights + commands** reference. **Tap = here, hold = aim anywhere** is exactly the gesture we need. |
| 9 | **Bellwright** (medieval settlement + squads, closest genre) | Villagers become companions in squads (Army tab, **O**). **E** gives a context-sensitive order on what you look at (attack, harvest, move). Middle mouse switches squads and places waypoints, and there is a command-mode key. [28][29][30] | Players like setting archers on high ground and screening them with melee; mixed squads. | "You're the General, not a soldier": the player is fragile and fighting is discouraged. Switching squads with MMB clashes with map waypoints. Raid prep is tedious (villagers fetching gear). [29][31] | The **E-on-target** context order is proven in our genre. We must avoid "the general who cannot fight". |
| 10 | **Brothers in Arms: Road to Hill 30** | Hold the command button and a **ring cursor** appears where you look. Release on ground = move/take cover; on an enemy = suppress (fire team) or assault. The enemy's **suppression circle** shows when to flank. You give intent to team leaders instead of micromanaging. [32][33][34] | "Honed to near-perfection"; one button, context does the rest; the suppression indicator makes the tactic readable. | Some found squad management tedious; the AI must be good for the fire-and-flank loop to work. [32] | The **gold standard for point-to-command in first person**. Use hold-to-preview, release-to-order and an **enemy state readout**. |
| 11 | **Dragon's Dogma 1 & 2** pawns | Only four shouts: **Go / Wait / To me / Help** (D-pad or keys). The meaning changes with context: Go in combat = spread out and engage; Go out of combat = scout or follow through on a suggestion. Commands also nudge pawn inclinations over time. [35][36] | Tiny vocabulary, no menus, you keep fighting; pawns feel alive. | Vague: you cannot pick a target, and "Help" is widely misunderstood (it asks for support and healing, not "come save me"). [35][36] | **Few verbs, context does the rest.** Our verbs need clear, visible results so they don't get vague. |
| 12 | **Pikmin** (whistle / throw / swarm) and **Overlord** (sweep / guard marker) | Pikmin: throw at the cursor; the **whistle radius grows while held**; **swarm** steers the group with the second stick; Pikmin 4 snaps the cursor to useful targets. Overlord: left-click sends minions toward where you face (hold sends all), right-click recalls, **LMB+RMB sweep** steers the horde, a **Guard Marker** plants a group (ranged Reds are kept back on a marker). [37][38][39][40] | Direct, physical and readable; Pikmin 4 praised as "simple and intuitive"; the sweep is fun. | Overlord PC mouse sweep is called oversensitive; steering takes your hands off fighting. [40] | **Hold-to-grow radius** for rallying and **sweep** for melee blobs. **Ranged units anchored on a marker, melee steered**: a natural knight/archer split. |
| 13 | **Stances**: *Stronghold*, *Kenshi*, *Total War* (brief) | Stronghold: per unit **Stand ground / Defensive** (engage nearby, then return) **/ Aggressive** (chase far, respond to missiles). Kenshi: Hold / Passive / Block / Taunt toggles. Total War: Ctrl+number saves a group, number selects it. [41][42][43][44] | Stances make the default AI predictable and tunable; archers on walls feel great in Stronghold. | Kenshi: squads bunch up around one enemy and eat cleaves; "Hold" breaks work routines. Stronghold pathing on walls is flaky. Number hotkeys clash with Minecraft's hotbar. [42][43][45] | **Default AI = stance + post**, set in peacetime. Anti-clumping rule. Squad hotkeys need a modifier. |
| 14 | **Companion games**: *Kingdom Come: Deliverance 2* (Mutt), *Valheim* (tames), *Enshrouded* | KCD2: dog commands Stay / Heel / Free, and **hold the key while looking at an enemy = "sic 'em"** (only with a weapon drawn). Obedience drops if the dog is neglected. We found no command system for KCD2's human allies. Valheim: **E** toggles follow/stay on a tamed wolf. Enshrouded survivors do not fight, and players ask for combat companions. [46][47][48][49] | KCD2 "sic" is one button, look to target, never leaves combat. | Valheim follow state sometimes drops; Enshrouded has none. [48][49] | **Hold-look-at-enemy = attack it** works in first person with a weapon out. |

Not relevant: **Chivalry 2 / Mordhau.** Bots are added through the console and there is no friendly command system [50]. **Man of Many Planes** (aircraft) was skipped. *Kingdom Two Crowns* is a useful side note: squads charge when you pay their banner, and idle knights stand at the outer wall and screen archers [51].

---

## 2. Patterns that work in first person while also fighting

1. **Look-to-target context order (one key).** The key reads what the crosshair is on: ground = move/hold there, enemy = attack it, structure = use it (wall, gate, tower). Seen in: BiA ring cursor, Bellwright E, Recruits "Move", KCD2 "sic", Bannerlord "To position". *Why it works:* you already aim with the mouse while fighting, so the aim is the order.
2. **Hold to preview, release to confirm.** BiA shows a ring while held and orders on release. This avoids misclicks mid-swing and lets you correct the spot.
3. **Tap vs hold on the same key.** Legends: tap = one, hold = all. Conqueror's Blade: tap = lock here, hold = aim anywhere. Overlord: click = one, hold = all. This doubles the vocabulary without new keys.
4. **Earshot.** Orders reach only soldiers near you (Legends), or a radius that grows while held (Pikmin whistle). Your position becomes part of command, co-op splits the army naturally, and it is readable ("only these heard me").
5. **Few verbs, context supplies the rest** (Dragon's Dogma). Four verbs are learnable in one raid. Every verb must show a clear result, or it feels vague.
6. **Same order, different meaning per class.** Guard Villagers: melee roams a point, ranged stands and snipes. Overlord: Reds held back on markers. Bannerlord: archers get fire modes, infantry get formations. This is how knights and archers can feel different without doubling the controls.
7. **Sweep / steer** (Overlord, Pikmin swarm, Conqueror's Blade hold-X). Continuous steering of a melee blob is fun and physical, but it occupies a hand, so use it in short bursts.
8. **Hold fire, then loose on command** (Bannerlord F4, Recruits "Hail of arrows"). A synchronized volley is a meaningful timing choice.
9. **Stances as the default brain** (Stronghold, Kenshi, Bannerlord F6). The no-orders behaviour is chosen in peacetime and predictable in war.
10. **Squad hotkeys** (Total War, Bannerlord numbers). Powerful, but in Minecraft 1–9 are the hotbar, so they need a modifier or a temporary "command stance".

**Anti-patterns to avoid:** long sequential key chains (Bannerlord); full-screen GUIs mid-fight (Recruits command screen); orders that fail silently (MineColonies rally bug reports); teleport summons (LOTR, Rally of the Guard); clumping around one enemy (Kenshi); a delegate AI that is worse than the numbers suggest (Bannerlord); a commander who must not fight (Bellwright).

---

## 3. Shared baseline: smart defaults (applies to all three pitches)

With **no orders**, guards follow this baseline. The existing per-guard **GuardOrderScreen** (Hold / Defend / Patrol points / Tower) stays as the *peacetime* layer that sets where each guard lives. The pitches below are the *live battle* layer on top of it.

- **Threat priority (both classes):** torchbearer near a building > enemy hitting a settler > enemy hitting a player > raider archer that is shooting > enemy at a gate or breach > nearest.
- **Knights ("screen and intercept"):**
  - Stay leashed to their post (about 16 blocks).
  - Always keep at least one knight between enemies and each ground archer cluster.
  - Team up two on one against shieldbearers: one bashes, one hits the flank.
  - Brace when wolves close in.
  - Anti-clumping: at most 3 knights on one enemy; the rest take the next target.
  - Below 30 % HP, fall back to the healer or barracks, then return.
- **Archers ("height, distance, clear line"):**
  - Take the nearest free tower, wall or arrow-slit post within 24 blocks. This extends the existing `ArcherTowerPost`.
  - Otherwise stay 10–16 blocks behind the nearest knight.
  - If an enemy comes within 6 blocks, step back toward the knights (kite to the screen).
  - Never shoot if the line of fire passes within 1 block of a friendly (player, guard or settler).
- **Alarm bell = Muster** (existing bell): knights go to gates and breaches, archers go to walls and towers, settlers go indoors. Everyone in the settlement hears it, regardless of earshot.
- **Order lifetime:** an order holds until it is released or until 30 s after the raid ends. Then guards return to posts (as already planned: "guards obey then return to posts").

---

## 4. Three pitches

### Pitch A — "Two Calls" *(simple and elegant)*

**Idea:** two keys, one for each arm of the garrison. Where you look decides the order, so there is no menu or wheel and you never put your sword away.

**Keys** (rebindable; we recommend **mouse side buttons 4/5** so fingers stay on WASD):
- **R = Knights.** **G = Archers.** Neither is bound in vanilla 1.21.1. Avoid V (Simple Voice Chat push-to-talk).
- **Press** shows a ghost preview at the crosshair (raycast up to 48 blocks). **Release** gives the order (the BiA pattern). Press and release fast works the same.
- **Shift + R / Shift + G = Stand down.** That arm returns to the smart defaults and posts.

**What the crosshair means:**

| Crosshair on… | **R: Knights** | **G: Archers** |
|---|---|---|
| Ground | **Hold the line here.** Form a shield line centred on the point, facing the direction you looked. Width scales with the number of knights. | **Take position here.** Spread along the spot and fire at will from there. |
| Enemy | **Charge him.** Knights in earshot converge on that enemy and cut through whatever is in the way (Legends "Charge"). Afterwards they return to their last hold point. | **Focus him.** All archers in earshot shoot only that target until it dies, for example a torchbearer. |
| Gate, gap or barricade | **Hold the gate.** A line in the opening; they close the gate if one exists. | (same as ground) |
| Wall, tower or arrow-slit block | (same as ground) | **Man it.** The nearest archers fill that tower or wall section, up to its slot count. |
| Your own feet (look straight down) | **On me.** Bodyguard ring around you. They follow you and intercept anything that attacks you. | **Cover me.** Follow 8–10 blocks behind you and shoot what threatens you. |

**Knights vs archers:** separate keys, and the same context means different things per class. Knights *occupy space* (line, gate, ring). Archers *pick ground and targets* (position, tower, focus). This covers exactly the orders the owner picked (hold the line, charge/follow me, focus target) plus the archer side, with no wheel.

**Earshot:** orders reach guards within **32 blocks** of you. A "Herald" upgrade or rank raises this to 48. Guards out of earshot keep the smart defaults. Standing at the **alarm bell** reaches the whole garrison.

**Co-op:** any member of the kingdom can call. Guards obey the **newest voice in earshot**, with a 3-second lock so two players cannot ping-pong the same guards. Because of earshot, the army splits naturally: whoever is at the gate commands the gate. The HUD chip shows who gave the current order ("Holding gate · Kari"). "On me" follows the player who called it.

**Feedback:**
- *HUD:* two small chips above the hotbar, left side: `[shield] Knights 4/6 · Hold line` and `[bow] Archers 3/3 · Tower N`. "4/6" means 4 of 6 heard you; the others are greyed out. The ghost preview is a faint line of shield icons (knights) or bow icons (archers). If an order is impossible, the preview turns red and a reason appears ("no path", "tower full").
- *World:* guards who obey get a small order icon over their head for 3 s. A faint banner marks each active hold point for all kingdom players.
- *Sound:* vanilla sound events only (the owner judged the synthesized ones bad). Knights answer with a shield-block thump and armor clank. Archers answer with a bow-draw creak. Subtitles show "Knights: Holding!" and similar.

**Why it beats MineColonies / Tektopia:**
- You make live choices every raid: where the line stands, who dies first, which tower gets manned.
- Choices matter because the planned roster has counters. Focus the torchbearers, man towers against raider archers, charge shieldbearers from the side, hold the gate against wolves.
- It never leaves combat and never teleports.
- An order never fails silently: "4/6 heard" and red previews explain what happened.

**Build cost:** S–M. It reuses `GuardOrderGoal` (it already has hold, tower and patrol modes). New parts: a raycast context resolver, the earshot filter, and a knight line-slot layout.

---

### Pitch B — "Sergeants & Formations" *(deep tactical)*

**Idea:** a Bannerlord-grade order set without Bannerlord's key chains. A **Command Stance** turns your hotbar into an order bar for as long as you need it. Squads are led by **Sergeants** who relay orders, and ranks unlock orders through the upgrade and tech tree the owner already loves.

**Keys:**
- **Z = Command Stance** (toggle). In stance you still walk, sprint and **block with your shield**. The hotbar row turns into the order bar, so **1–9 are orders**. The **mouse wheel** or **Tab** cycles squads. Left-click places the order and **left-drag** sets line width and facing (Bannerlord). **Shift+click** queues waypoints (Hundred Years Warfare). **Swinging your weapon (attack key) leaves the stance instantly**, so you are never stuck as a spectator. Time is not paused (multiplayer).
- Outside stance, **R / G quick calls** from Pitch A still work for the selected squad. The deep mode is optional in a hurry.

**Squads:** up to 4 (for example "Gate Knights", "Wall Bows", "Reserve"). Squads are formed in peacetime on the existing order screen, and each has a colored pennant. Each squad has a **Sergeant**, the highest-ranked guard (uses `GuardExperience` / `ArcherRank`). Orders go through him with a short shout delay: 1.0 s for a green sergeant, 0.3 s for a veteran. If he dies, the HUD flags "Sergeant down", the squad falls back to smart defaults, and the next in rank takes over after 5 s. Protecting your sergeants becomes a tactic, and killing theirs (nemesis captains) becomes a raider tactic.

**Order bars (they differ per class):**

| Key | **Knight squad** | **Archer squad** |
|---|---|---|
| 1 | Move / hold here (drag = width and facing) | Position here (drag = firing line) |
| 2 | Formation: **Line → Shieldwall → Wedge → Ring** (press again to cycle) | **Man walls in area** (drag a box and they take every arrow slit and post inside) |
| 3 | **Advance** 5 blocks (with shield-bash push) | **Fire at will ↔ Hold fire** |
| 4 | **Fall back** 5 blocks, keeping formation | **Volley**: archers draw and wait, then **you call "Loose!" with left-click**. A synchronized volley arcs *over* shieldbearer shields and staggers. |
| 5 | **Charge** (break formation) | **Focus target** |
| 6 | **Brace** (kneel behind shields: stops wolf pounces and charges) | **Suppress area** for 10 s: enemies inside are slowed and hesitate (BiA suppression) |
| 7 | **Follow me** | **Fall back behind knights** |
| 8 | Stance: Stand ground / Defensive / Aggressive (Stronghold) | Stance: same |
| 9 | Release (smart defaults) | Release |

**Counter map (why the choices matter):**

| Enemy | Counter |
|---|---|
| Shieldbearers | Wedge, or a Volley that falls over the shields |
| Wolves | Brace |
| Raider archers | Suppress, then Charge |
| Torchbearers | Focus |
| Big mixed push | Shieldwall + Fire at will behind it |

**Rank unlocks** (upgrades and tech tree): new squads start with Move, Follow, Charge and Release. **Sergeant** rank unlocks Formations and Advance/Fall back. **Veteran** unlocks Volley, Suppress, Wedge and Brace.

**Default AI:** smart defaults plus a **per-squad stance and doctrine** chosen in peacetime (for example "Gate Wardens: Stand ground at the gate", "Wall Bows: Defensive on the east wall").

**Co-op:** a player **takes command** of a squad (in stance, click its card). From then on, that squad obeys only its **Captain** until released, or until the captain is dead, logged out or more than 64 blocks away. The settlement Lord can always override. Each captain's color rings their squad's pennant. A typical split: one friend leads the gate knights on foot, another runs the wall bows from the tower, and a third roams with a reserve.

**Feedback:**
- *HUD in stance:* squad cards on the left edge with sergeant name, count, stance, current order and a morale pip. A formation ghost on the ground. Enemy readout when aiming at a group ("Shieldbearer ×3, Archer ×2") and a **pinned** meter for suppression (BiA).
- *HUD out of stance:* only the small chips from Pitch A.
- *Sound:* the sergeant shouts back (vanilla sound events plus subtitles). The Volley has a clear draw-hold-release audio sequence. A bell note plays when a sergeant falls.

**Why it beats MineColonies / Tektopia:** real battlefield tactics (formations, volleys, suppression) with a counter for every raider type. Squads have a chain of command that can be broken. Progression feeds combat instead of just stats.

**Risk:** learning curve. Bannerlord players complain even with fewer layers. Mitigations: only 4 orders are unlocked at first, a tutorial raid, and Pitch A's quick calls always work.
**Build cost:** L.

---

### Pitch C — "Sworn Retinue" *(co-op first, physical)*

**Idea:** split the army in two layers.
1. Each player has a small **Retinue** of sworn guards (2 at start, up to 6 with rank) that they lead personally in the fight.
2. The rest of the **Garrison** runs on doctrine: stances plus posts, and the alarm bell.

You steer your retinue with your body and gaze, like Overlord and Pikmin, not with menus. There are four players and four retinues, so there are no command conflicts.

**Keys:**
- **R tap = "Go!"** (context order for the retinue): enemy = attack it (KCD2 "sic"); ground = go and hold there; gate or tower = take it.
- **R hold = Sweep.** While held, one gesture means two things. **Knights** flow toward the crosshair point as a tight block. **Archers** stay where they are and **fire at enemies around that point**, then lift fire when your knights arrive (a medieval creeping barrage). Release and the knights plant there. It is short and physical, and you let go of R to swing again.
- **G tap = "To me!":** knights form a ring around you and archers take cover positions about 8 blocks behind you.
- **G hold = Rally.** A radius grows while held (Pikmin whistle, 6 up to 24 blocks). **Garrison guards inside the circle are loaned into your retinue** up to your rank cap, so you pick up troops on the move. Loans return to their posts after the raid.
- **Shift + G = release** the retinue and loans back to smart defaults.

**Knights vs archers:** knights are *steered* (they follow the sweep point and move as a body). Archers are *anchored* (the sweep moves their **aim**, not their feet; they only relocate with "Go!" on a spot or "To me!"). This is the Overlord Brown/Red split: one gesture, two roles.

**Garrison doctrine** (no orders): every post, tower and gate gets a stance in peacetime on the existing order screen: **Stand ground** (never leaves), **Defend** (engages within 12 blocks, then returns) or **Hunt** (chases threats anywhere inside the claim). Guards use the smart defaults within that stance. The **alarm bell** switches every doctrine to Muster.

**Co-op:**
- Every player owns a retinue, so there is no fight over who commands whom.
- Rally loans are first-come; a guard shows its current leader's color.
- A player who goes down leaves the retinue holding their position (not fleeing) until someone rallies them.
- The Lord can release anyone's loans.
- It plays like four heroes, each with a band, around a garrison that holds on its own. That fits 3–4 friends with different playstyles: one builds walls while another leads a sortie.

**Feedback:**
- *HUD:* one retinue strip above the hotbar (small heads with health pips, knights and archers in two groups). Loaned guards have a thin outline.
- *World:* the Rally circle grows visibly on the ground. The sweep point shows as a small banner that the knights move toward and a faint arrow arc where the archers aim.
- *Sound:* retinue answers with vanilla shield-block and bow-draw sounds plus subtitles. The rally uses the alarm-bell note at low volume.

**Why it beats MineColonies / Tektopia:** you are always in the fight *with* your men, every player has their own tactical toy, the garrison defends smartly without you, and pulling guards off the walls to sortie is a real risk and reward choice.

**Risk:**
- Sweep holding a key steals fighting time; keep sweeps short.
- Four retinues can drain the walls. Solution: a rank cap, and doctrine posts marked "never loan".

**Build cost:** M–L. Retinue ownership and loans are new state. The sweep reuses path-following.

---

## 5. Recommendation

**Build Pitch A ("Two Calls") as the core now, and grow it with two pieces of Pitch B through upgrades.**

Why:
- **It matches the brief most directly.** Separate keys give knights and archers visibly different controls. Aim is the order, so you command mid-swing. It covers exactly the three orders the owner picked (hold the line, charge/follow, focus) without the rejected wheel, horns, signal arrows, mirroring or war table.
- **It uses proven patterns.** BiA hold-and-release on target, Bellwright's E, Legends earshot and KCD2 "sic" all work in first person while fighting, and none needs a GUI. It avoids the documented failures: Bannerlord key chains, Recruits' modal screen, and MineColonies rallies that fail silently.
- **It is the cheapest good version.** Most of the plumbing exists (`GuardOrderGoal` modes, tower posts, alarm bell).

**Then add as rank or tech-tree unlocks** (the owner loves upgrades):
1. **Shieldwall / Brace** for knights: hold R on ground longer to set a braced shieldwall.
2. **Volley on your call** for archers: G on ground with "Hold fire", then press G again to loose.

This gives depth where the raider roster demands it (shieldbearers, wolves) without the full Command Stance.

**Keep Pitch C's Rally-loan** as a possible later addition if co-op tests show players want personal bands. Pitch B's full stance mode is only worth building if playtests show the friends want more control after A plus the unlocks.

**First prototype slice:**
1. Knights key only (ground = line, enemy = charge, feet = on me, Shift = stand down), with earshot and the HUD chip.
2. Smart defaults for knights.
3. One test raid with shieldbearers.
4. Video to the owner before adding archers.

---

## Sources

1. Villager Recruits Wiki (GitHub): https://github.com/talhanation/wiki/wiki/Villager-Recruits-Wiki
2. Villager Recruits guide (hold R, tabs, classes): https://minecraftstorage.com/mods/villager-recruits/guide
3. Villager Recruits on CurseForge: https://www.curseforge.com/minecraft/mc-mods/recruits
4. Recruits-Extras (formations Line/Square/Circle/Wedge): https://modrinth.com/mod/recruits-extras
5. MineColonies Guard Tower wiki: https://minecolonies.com/wiki/buildings/guardtower/
6. MineColonies Rallying Banner wiki: https://minecolonies.com/wiki/items/rallying_banner/
7. MineColonies issue #10337 (guards ignore rally): https://github.com/ldtteam/minecolonies/issues/10337
8. MineColonies issue #8972 (cannot rally to colony banner): https://github.com/ldtteam/minecolonies/issues/8972
9. TekTopia Wiki, Guard: https://sites.google.com/view/tektopia/home/villagers/guard
10. TekTopia Captain (fandom): https://tektopia.fandom.com/wiki/Captain
11. TekTopia issue #723 (Captain behaviour): https://github.com/TangoTek/TekTopia-Community/issues/723
12. Guard Villagers on CurseForge: https://www.curseforge.com/minecraft/mc-mods/guard-villagers
13. Guard Villagers guide (Craft Down Under): https://craftdownunder.co/guides/mods/guard-villagers
14. Rally of the Guard addon: https://www.curseforge.com/minecraft/mc-mods/rally-of-the-guard-guardvillagers-forge
15. LOTR Mod, Horn of Command: https://lotrminecraftmod.fandom.com/wiki/Horn_of_Command
16. Hundred Years Warfare (Modrinth): https://modrinth.com/project/sGwWd97l
17. Modern Companions (NeoForge 1.21.1): https://modrinth.com/mod/modern-companions
18. Human Companions: https://www.curseforge.com/minecraft/mc-mods/human-companions
19. Minecraft Legends army guide (DualShockers): https://www.dualshockers.com/minecraft-legends-army-guide/
20. Minecraft Legends (Wikipedia, reception): https://en.wikipedia.org/wiki/Minecraft_Legends. Also GamesRadar mob control: https://www.gamesradar.com/minecraft-legends-mobs-control-order-golems/
21. Bannerlord tactics guide (GamerGuides): https://www.gamerguides.com/mount-and-blade-ii-bannerlord/guide/introduction/useful-gameplay-tips/tactics
22. Bannerlord F-key thread: https://steamcommunity.com/app/261550/discussions/0/3191366986187854101/
23. "Why is the command system still so unintuitive?": https://steamcommunity.com/app/261550/discussions/0/3051737712174003435/
24. "Anyone else extremely struggle to command AND fight?": https://steamcommunity.com/app/261550/discussions/0/3823036151102853929/
25. Conqueror's Blade keybinds guide: https://denetax.fr/en/tutoriel-commandes-utiles-conqueror-blade
26. Conqueror's Blade review: https://www.sellersandfriends.com/blog/conquerors-blade-review-check-out-if-its-worth-playing
27. Conqueror's Blade "Lack of unit control": https://steamcommunity.com/app/905370/discussions/0/2138588424857897814/
28. Bellwright Companion wiki: https://bellwright.fandom.com/wiki/Companion
29. Bellwright "Advanced tactics?": https://steamcommunity.com/app/1812450/discussions/0/832738775777123340/
30. Bellwright order keybinding thread: https://steamcommunity.com/app/1812450/discussions/0/4634862623030066848/
31. Bellwright raid discussion: https://steamcommunity.com/app/1812450/discussions/0/4365754151468326747
32. Brothers in Arms: RtH30 (Wikipedia): https://en.wikipedia.org/wiki/Brothers_in_Arms:_Road_to_Hill_30
33. BiA RtH30 review (GameSpot): https://www.gamespot.com/reviews/brothers-in-arms-road-to-hill-30-review/1900-6120728/
34. BiA RtH30 Q&A (GameSpot): https://www.gamespot.com/articles/brothers-in-arms-road-to-hill-30-qanda-final-thoughts/1100-6119621/
35. Dragon's Dogma 2 official manual, Pawn Commands: https://game.capcom.com/manual/dd2/en/ps5/page/4/7
36. Dragon's Dogma pawn commands (wiki): https://dragonsdogma.fandom.com/wiki/Pawn_commands. "Help" thread: https://steamcommunity.com/app/367500/discussions/0/405691491110724419/
37. Pikipedia, Whistle: https://www.pikminwiki.com/Whistle
38. Pikipedia, Swarm: https://www.pikminwiki.com/Swarm
39. Pikmin 4 review (New Game Network): https://www.newgamenetwork.com/article/2691/pikmin-4-review/
40. Overlord default controls guide: https://steamcommunity.com/sharedfiles/filedetails/?id=520952893. StrategyWiki controls: https://strategywiki.org/wiki/Overlord_(2007)/Controls
41. Stronghold Crusader stances thread: https://steamcommunity.com/app/40970/discussions/0/630800446385289742/
42. Stronghold Crusader DE review (GameWatcher): https://www.gamewatcher.com/reviews/Stronghold-Crusader-Definitive-Edition-review/13456
43. Kenshi Combat Strategies: https://kenshi.fandom.com/wiki/Combat_Strategies
44. Total War Academy, battle controls: https://academy.totalwar.com/battle-keyboard-and-mouse-controls/
45. Kenshi "How to use Hold?": https://steamcommunity.com/app/233860/discussions/0/2561864094353128374/. "Passive behavior": https://steamcommunity.com/app/233860/discussions/0/1694914736001428537/
46. KCD2 Mutt guide: https://kcd2.org/en/post/kcd2-mutt. Sportskeeda: https://www.sportskeeda.com/esports/using-mutt-kingdom-come-deliverance-2-explained
47. Valheim Taming wiki: https://valheim.fandom.com/wiki/Taming
48. Valheim wolf follow/stay notes: https://blog.sparkedhost.com/valheim/how-to-tame-wolves-valheim-mountain-biome-breeding-guide-stakewood-fence
49. Enshrouded "solo players and combat companions": https://steamcommunity.com/app/1203620/discussions/0/591766704612210067/
50. Mordhau "Bots in horde mode?": https://steamcommunity.com/app/629760/discussions/0/1638668751266334731/
51. Kingdom Two Crowns, Knight / Defending: https://kingdomthegame.fandom.com/wiki/Knight and https://kingdomthegame.fandom.com/wiki/Defending_the_Kingdom

---

## Pitch (norsk)

**Pitch A – «To rop» (enkel og elegant)**
Du har to taster: R for riddere og G for bueskyttere. Det du ser på, bestemmer ordren.
Ser du på bakken, stiller ridderne seg i en skjoldlinje der, mens skytterne tar oppstilling og skyter fritt.
Ser du på en fiende, stormer ridderne ham, mens skytterne skyter bare på ham (for eksempel fakkelbæreren).
Ser du på et tårn eller en mur, går skytterne opp dit. Ser du ned på dine egne føtter, følger de deg og vokter deg.
Shift + tasten sender dem tilbake til postene sine. Du trenger aldri en meny og slipper aldri sverdet.
Bare soldater innenfor 32 blokker hører deg, og en liten linje over hotbaren viser hvor mange som hørte («4/6»).
Uten ordre forsvarer de seg smart: ridderne skjermer skytterne, og skytterne søker høyde og holder avstand.

**Pitch B – «Sersjanter og formasjoner» (dyp taktikk)**
Z setter deg i kommandomodus. Du kan fortsatt gå og blokkere, og hotbaren blir til en ordrelinje.
Troppene er delt i opptil fire lag, hvert med en sersjant som roper ordren videre. Dør sersjanten, faller laget tilbake til standardforsvar.
Ridderne har skjoldvegg, kile, ring, rykk fram, trekk tilbake, stå imot og storm.
Skytterne har bemann muren, hold ild, salve på ditt signal, fokus og nedholdingsild.
Hver fiendetype har sitt svar: kile eller salve mot skjoldbærere, stå imot mot ulver, fokus mot fakkelbærere.
Flere ordrer låses opp med rang og oppgraderinger. Hver venn kan ta kommandoen over sitt eget lag.
Et slag med venstre museknapp tar deg rett ut av modusen, så du kan slåss med én gang.

**Pitch C – «Svorne menn» (samarbeid først)**
Hver spiller har sitt eget lille følge på 2–6 soldater som slåss sammen med ham. Resten av garnisonen forsvarer seg selv etter innstillingene på postene.
Trykk R, så angriper følget det du ser på eller går dit. Hold R, så går ridderne mot punktet du sikter på, mens skytterne skyter rundt det punktet.
Trykk G, så samles alle rundt deg. Hold G, så vokser en sirkel som låner vakter fra murene inn i følget ditt.
Alarmklokka setter hele garnisonen i forsvar.
Fire venner får fire følger, så dere krangler aldri om hvem som styrer hvem.

**Anbefaling:** Bygg A først, og legg senere til skjoldvegg for ridderne og salve på signal for skytterne som oppgraderinger.

"""The sound target list: one entry per sound event the lane (re)generates.

Fields
  event   sound event id (without the hearthstead: namespace)
  cat     category (combat, ui, tavern, work, builder, world, raid, voice, ambience)
  pri     1 = heard constantly or at key moments, 2 = regular, 3 = nice to have
  n       variants shipped
  dur     seconds requested from the API (min 0.5; 40 credits per second)
  maxlen  hard cap after trimming (short Minecraft tails)
  lufs    padded integrated loudness target (see STYLE in SOUNDS.md)
  lofi    0..1 top-end roll-off (0 = none, 1 = 9 kHz low-pass)
  bursts  expected number of separate hits (scoring), impact = transient must be at the start
  prompt  the text sent to ElevenLabs; `alts` are varied tails cycled across candidates
  hook    where it plays; cur = what plays today; new = event did not exist before
"""
import math

DRY = "close mic, dry, no reverb, no music"
# Research winner (STYLE.md §3): concrete material + action first, then these words.
GAME = "close mic, dry, tight fast decay, no reverb, no music, short video game sound effect"

T = []


def t(event, cat, pri, n, dur, lufs, prompt, *, alts=None, maxlen=None, lofi=0.3, infl=0.55,
      bursts=None, impact=False, loop=False, sustained=False, cands=None, new=False,
      hook="", cur="", sub=None, att=None, trim_db=-42, fadeout=0.05, hp=40, folder=None):
    T.append(dict(event=event, cat=cat, pri=pri, n=n, dur=dur, lufs=lufs, prompt=prompt, alts=alts or [],
                  maxlen=maxlen, lofi=lofi, infl=infl, bursts=bursts, impact=impact, loop=loop,
                  sustained=sustained, cands=cands, new=new, hook=hook, cur=cur, sub=sub, att=att,
                  trim_db=trim_db, fadeout=fadeout, hp=hp, folder=folder or cat))


# ---------------------------------------------------------------- COMBAT (P1)
t("combat.swing_light", "combat", 1, 4, 0.5, -19,
  "A single quick sword swing whoosh, sharp thin air cut, no impact, " + GAME,
  alts=["light steel blade", "short arming sword", "fast slash"], maxlen=0.45, bursts=1, hp=100,
  hook="GuardMove/RaiderMove light swings", cur="old (Sonniss cut)")
t("combat.swing_heavy", "combat", 1, 4, 0.7, -18,
  "A single heavy greatsword swung hard through the air, thick airy whoosh with a rising swish, no impact, " + GAME,
  alts=["war axe", "heavy mace"], maxlen=0.6, bursts=1, hp=90,
  hook="GuardMove/RaiderMove heavy swings", cur="old (Sonniss cut)")
t("blade_hit", "combat", 1, 5, 0.5, -18,
  "A single sword slash landing on a padded leather gambeson, dull meaty thwack with a faint steel scrape, no metal clang, " + GAME,
  alts=["punchy", "heavy cloth", "gritty"], maxlen=0.45, bursts=1, impact=True,
  hook="guard/raider melee hit contact", cur="old (Sonniss cut)")
t("combat.heavy_impact", "combat", 1, 4, 0.7, -16,
  "A single heavy overhead weapon smash onto an armored body, deep thud with chainmail crunch, " + GAME,
  alts=["mace", "war hammer", "axe"], maxlen=0.6, bursts=1, impact=True,
  hook="guard heavy overhead / raider heavy contact", cur="old (Sonniss cut)")
t("combat.bash_swing", "combat", 1, 3, 0.6, -17,
  "A single wooden round shield bashed hard into a body, blunt wooden thump with a leather grunt of air, " + GAME,
  maxlen=0.5, bursts=1, impact=True, hook="guard shield bash", cur="old (Sonniss+Kenney)")
t("shield_thud", "combat", 1, 5, 0.5, -17,
  "A single sword blow blocked by a wooden round shield, hard wooden knock with a short iron rim ring, " + GAME,
  alts=["dull", "bright rim", "splintery"], maxlen=0.45, bursts=1, impact=True,
  hook="guard/raider blocks a hit", cur="old (Sonniss cut)")
t("leap_slam", "combat", 1, 3, 0.8, -15,
  "An armored warrior landing from a jump and slamming a weapon into the dirt, heavy earthy boom with armor rattle, " + GAME,
  maxlen=0.7, bursts=1, impact=True, hook="GuardLeapGoal landing", cur="vanilla mace smash_ground")
t("armour_clink", "combat", 2, 4, 0.5, -24,
  "Chainmail and iron plates rattling as a soldier shifts, short metallic jingle, " + GAME,
  maxlen=0.45, hook="guard idle / walk accents", cur="vanilla equip_chain")
t("raider.brute_roar", "raid", 1, 3, 1.5, -14,
  "A huge brutish barbarian man roaring in rage, guttural throat roar, short, " + GAME,
  alts=["deep", "hoarse", "snarling"], maxlen=1.4, new=True, sub="Brute roars",
  hook="RaiderEntity brute/captain roar, BruteTollEvent", cur="vanilla ravager roar", att=48)
t("raider.brute_slam", "raid", 1, 3, 1.0, -14,
  "A giant wooden club smashing into the ground, heavy earthy boom, dirt and pebbles scatter, " + GAME,
  maxlen=0.9, bursts=1, impact=True, new=True, sub="Club slams",
  hook="RaiderEntity brute club strike / breach slam", cur="vanilla shield break + ravager roar", att=32)
t("raider.bark", "raid", 1, 4, 0.8, -17,
  "A rough bandit shouting a short wordless battle yell, aggressive, single voice, " + GAME,
  alts=["'Hyah!'", "'Raaah!'", "'Hup!'", "snarl"], maxlen=0.8, new=True, sub="Raider yells",
  hook="RaiderEntity ambient / charge", cur="none (vanilla pillager)", att=24)
t("raider.hurt", "raid", 2, 4, 0.5, -18,
  "A rough man grunting in pain from a hit, short, single voice, " + GAME,
  maxlen=0.45, new=True, sub="Raider hurts", hook="RaiderEntity getHurtSound", cur="vanilla pillager hurt")
t("raider.death", "raid", 2, 3, 1.0, -18,
  "A rough man's short dying groan as he falls, single voice, " + GAME,
  maxlen=0.9, new=True, sub="Raider dies", hook="RaiderEntity getDeathSound", cur="vanilla pillager death")

# finisher / revive / command
t("combat.execution_windup", "combat", 1, 3, 0.5, -20,
  "A blade drawn back with a rising steel shing, tense, " + GAME, maxlen=0.45,
  hook="FinisherService windup", cur="old (Sonniss cut)")
t("combat.execution_stinger", "combat", 1, 3, 1.2, -18,
  "A brutal finishing blow stinger: heavy blade impact with a deep low boom and a short metallic ring, cinematic but short, " + DRY,
  maxlen=1.1, impact=True, hook="FinisherService execution hit", cur="old (Sonniss layered)")
t("combat.execution_stinger.axe", "combat", 2, 2, 1.0, -18, "A brutal axe finishing blow: heavy wooden-handled axe biting deep with a woody crunch and a low boom, " + GAME,
  maxlen=1.0, impact=True, new=True, sub="Axe finisher", hook="FinisherService.stingerFor AXE", cur="sword stinger")
t("combat.execution_stinger.mace", "combat", 2, 2, 1.0, -17, "A crushing mace finishing blow: iron head smashing plate armour, dull metal crunch and a low boom, " + GAME,
  maxlen=1.0, impact=True, new=True, sub="Mace finisher", hook="FinisherService.stingerFor MACE", cur="sword stinger")
t("combat.execution_stinger.spear", "combat", 2, 2, 1.0, -18, "A spear finishing thrust: fast stab through padded armour, wooden shaft thud and a short low boom, " + GAME,
  maxlen=1.0, impact=True, new=True, sub="Spear finisher", hook="FinisherService.stingerFor spear", cur="sword stinger")
t("combat.execution_stinger.bare", "combat", 2, 2, 0.8, -18, "A heavy bare-fist knockout punch to the jaw, meaty smack with a low thump, " + GAME,
  maxlen=0.8, impact=True, new=True, sub="Knockout blow", hook="FinisherService.stingerFor BARE", cur="sword stinger")
t("combat.execution_double", "combat", 1, 2, 1.2, -18,
  "Two fast heavy blade strikes in a row, slash then heavy thud, " + GAME, maxlen=1.2, bursts=2,
  hook="FinisherService double strike", cur="old (Sonniss layered)")
t("combat.execution_body_fall", "combat", 1, 3, 0.8, -17,
  "An armored body collapsing onto dirt, heavy thud with chainmail rattle, " + GAME, maxlen=0.75,
  impact=True, hook="FinisherService body fall", cur="old (Sonniss+Kenney)")
t("combat.guard_cheer", "voice", 1, 4, 1.4, -16,
  "Three medieval soldiers giving a short triumphant cheer 'Hurrah!', rough male voices, outdoors, " + DRY,
  alts=["", "with a spear rattle", "one shouts first"], maxlen=1.4, sustained=True,
  hook="FinisherService guard cheer", cur="old (Sonniss walla)")
t("command_shout", "voice", 1, 3, 0.9, -16,
  "A gruff medieval captain barking one short wordless command shout 'Hup!', single male voice, outdoors, " + DRY,
  alts=["'Hah!'", "'Ho!'"], maxlen=0.8, hook="CommandKeys R/G field orders", cur="old (Sonniss voice)")
t("command_ack", "voice", 1, 3, 0.9, -19,
  "A few soldiers answering in unison with a short 'Hoo!' and a rattle of armor, " + DRY,
  maxlen=0.9, hook="command acknowledged", cur="old (Sonniss layered)")
t("downed_heartbeat", "combat", 1, 4, 0.6, -20,
  "A single deep muffled heartbeat thump, lub-dub, " + GAME, maxlen=0.6, bursts=2, lofi=0.6,
  hook="DownedClient heartbeat while downed", cur="old (Sonniss)")
t("downed_revived", "combat", 1, 2, 1.2, -18,
  "A warm rising revive chime, soft harp swell and a gasp of breath, hopeful, " + GAME, maxlen=1.1,
  hook="DownedClient revived", cur="old (Sonniss)")
t("downed_alert", "combat", 1, 3, 0.9, -18,
  "An urgent low alarm sting: a struck iron bell with a tense low drum hit, short, " + GAME, maxlen=0.9,
  hook="DownedClient ally down alert", cur="old (Sonniss)")

# role weapons (P2)
t("role.spear_thrust", "combat", 2, 3, 0.5, -19, "A fast spear thrust, sharp short whoosh ending in a stab, " + GAME,
  maxlen=0.45, hook="spear item swing", cur="old swing_light reuse")
t("role.longsword_cleave", "combat", 2, 2, 0.7, -18, "A wide longsword cleave, long heavy whoosh with a steel ring, " + GAME,
  maxlen=0.65, hook="longsword swing", cur="old swing_heavy reuse")
t("role.rune_cast", "combat", 2, 2, 1.2, -19, "A carved rune stone activating, low stony hum rising into a soft magical shimmer, " + GAME,
  maxlen=1.1, hook="rune cast", cur="vanilla amethyst/evoker")
t("role.firebolt_impact", "combat", 2, 2, 1.0, -17, "A small fireball bursting on impact, whoomp of flame and crackle, " + GAME,
  maxlen=0.9, impact=True, hook="firebolt hit", cur="vanilla firecharge")
t("role.ward_up", "combat", 2, 2, 1.2, -19, "A protective magic ward shimmering on, soft glassy chime swell, " + GAME,
  maxlen=1.1, hook="ward rune", cur="vanilla amethyst/beacon")
t("role.frost_rune", "combat", 2, 2, 1.0, -19, "A frost spell crackling, ice crystals forming quickly, crisp crunch, " + GAME,
  maxlen=0.9, hook="frost rune", cur="vanilla powder snow")
t("role.bandage", "combat", 2, 2, 0.8, -22, "A cloth bandage wrapped tight around an arm, fabric rustle and a knot pull, " + GAME,
  maxlen=0.75, hook="bandage use", cur="vanilla leather/wool")

# ---------------------------------------------------------------- UI (P1)
t("ui.click", "ui", 1, 3, 0.5, -24, "A single soft wooden button click, small toggle of a wooden latch, " + GAME,
  alts=["tiny", "crisp"], maxlen=0.2, lofi=0.4, bursts=1, impact=True, new=True, sub=None,
  hook="Hs/Ui2 buttons, map clicks, handbook plates", cur="vanilla ui.button.click")
t("ui_open", "ui", 1, 3, 0.5, -24, "A leather-bound ledger opened on a wooden desk, soft cover flap and page rustle, " + GAME,
  maxlen=0.45, lofi=0.4, hook="Hs screens open", cur="vanilla book open_flip")
t("ui_close", "ui", 1, 2, 0.5, -24, "A leather-bound book closed gently, soft cover thump, " + GAME,
  maxlen=0.4, lofi=0.4, impact=True, hook="Hs screens close", cur="vanilla book close_put")
t("ui_confirm", "ui", 1, 3, 0.5, -22, "A wax seal stamp pressed onto parchment, soft firm thump with a tiny wooden click, " + GAME,
  maxlen=0.4, lofi=0.4, impact=True, hook="authoritative confirm", cur="vanilla chiseled bookshelf insert")
t("ui_error", "ui", 1, 2, 0.5, -22, "A locked wooden drawer rattling, two dull knocks, denied, " + GAME,
  maxlen=0.45, lofi=0.4, hook="authoritative error", cur="vanilla chest open_locked")
t("fx.skill_level_up", "ui", 1, 2, 1.0, -21,
  "A short airy sparkle chime, three quick rising plucked harp notes with a soft shimmer, bright, " + GAME,
  maxlen=1.0, new=True, sub="Skill improves", hook="FxClient: settler skill level up (particle lane)",
  cur="vanilla player levelup (SettlerFlourish)")
t("fx.tech_learned", "ui", 1, 2, 2.0, -18,
  "A discovery flourish: quill scratch then a warm plucked lute arpeggio resolving on a soft bell, medieval, " + GAME,
  maxlen=1.9, new=True, sub="Technology learned", hook="FxClient: tech node learned at the Banner", cur="none")
t("fx.building_level_up", "ui", 1, 2, 2.5, -17,
  "Three quick hammer knocks on wood followed by a short bright medieval brass fanfare and a small bell, triumphant, " + GAME,
  maxlen=2.4, new=True, sub="Building upgraded", hook="FxClient: plaque/room level up", cur="none")
t("fx.warehouse_level_up", "ui", 2, 1, 2.0, -17,
  "Heavy wooden crates stacked with a thud, then a short cheerful brass flourish, " + GAME,
  maxlen=1.9, new=True, sub="Warehouse upgraded", hook="FxClient: warehouse level up", cur="none")
t("fx.journey_chapter", "ui", 1, 2, 2.5, -17,
  "A small medieval fanfare: two natural trumpets and a snare roll, short and proud, " + GAME,
  maxlen=2.4, new=True, sub="Journey chapter complete", hook="FxClient: Journey chapter complete",
  cur="vanilla player levelup (FoundingJourneyProgress)")
t("fx.craft_glint", "ui", 1, 3, 0.8, -21, "A short magical glint, a single bright twinkle sparkle shimmer, " + GAME,
  alts=["glassy", "tiny bell", "crystal"], maxlen=0.75, new=True, sub="Fine craft glints",
  hook="FxClient: Superior+ craft (pitch rises with tier)", cur="none")
t("fx.craft_legendary", "ui", 1, 2, 2.0, -17, "A radiant legendary item sting: choir-like shimmer swell and a bright bell strike, short, " + GAME,
  maxlen=1.9, new=True, sub="Legendary craft", hook="FxClient: Legendary craft", cur="none")
t("fx.coin_sale", "ui", 1, 4, 0.7, -20, "A few gold coins dropped into a leather purse, short bright clinks, " + GAME,
  alts=["three coins", "small handful", "coin stack"], maxlen=0.6, new=True, sub="Coins jingle",
  hook="FxClient: merchant sale", cur="none")
t("fx.build_done", "builder", 1, 2, 1.5, -18,
  "Construction finished: one last hammer knock on timber then a cheerful single bell ding, satisfying, " + GAME,
  maxlen=1.4, new=True, sub="Building finished", hook="FxClient: builder finished a building", cur="none")
t("fx.raid_won", "raid", 1, 3, 1.2, -19, "A short celebratory firework-like pop and sparkle crackle, festive, " + GAME,
  maxlen=1.0, new=True, sub="Victory celebration", hook="FxClient: raid won bursts (x3, 0.5 s apart)", cur="none")
t("fx.summon_arrival", "combat", 2, 2, 1.2, -18, "A soldier arriving in a hurry: boots skidding to a stop in dirt with an armor rattle and a sword tap on shield, " + GAME,
  maxlen=1.1, new=True, sub="Soldier arrives", hook="FxClient: summoned soldier arrives", cur="none")
t("fx.patrol_waypoint", "ui", 2, 2, 0.6, -23, "A wooden map pin pushed into parchment with a soft tick and a tiny bell ping, " + GAME,
  maxlen=0.55, new=True, sub="Waypoint set", hook="FxClient: patrol waypoint set", cur="none")
t("fx.order_confirmed", "ui", 2, 2, 0.6, -22, "A short military drum tap and a leather strap snap, crisp confirmation, " + GAME,
  maxlen=0.5, new=True, sub="Order confirmed", hook="FxClient: field order confirmed", cur="none")
t("ui.map_ping", "ui", 2, 2, 0.6, -24, "A soft plucked note and a paper tap, gentle map marker ping, " + GAME,
  maxlen=0.5, new=True, sub=None, hook="RealmMapView marker click", cur="vanilla button click")

# stingers
t("settlement_founded", "ui", 1, 2, 3.5, -15, "A proud medieval founding fanfare: natural horn call with a frame drum and a church bell, short, " + DRY,
  maxlen=3.5, hook="banner placed / settlement founded", cur="old (Sonniss layered)")
t("profession_assigned", "ui", 1, 3, 1.0, -19, "A short pleasant confirmation: two rising plucked lute notes with a soft wooden tap, " + GAME,
  maxlen=0.9, hook="profession assigned", cur="old (Sonniss layered)")
t("settler_recruited", "ui", 1, 2, 2.2, -17, "A warm welcome sting: a short cheerful lute strum and a small hand bell, " + GAME,
  maxlen=2.1, hook="settler recruited", cur="old (Sonniss layered)")
t("guard_alert", "raid", 1, 3, 2.2, -16, "An alarm: a watchtower iron bell struck three times fast, urgent, outdoors, " + DRY,
  maxlen=2.1, hook="guard spots an enemy", cur="old (Sonniss bells)")
t("guard_experience", "ui", 1, 3, 0.5, -19, "A short bright two-note metallic ding, reward, " + GAME,
  maxlen=0.4, bursts=2, hook="valid defender kill", cur="old (original two-note)")

# ---------------------------------------------------------------- TAVERN (P1)
t("mug_set", "tavern", 1, 4, 0.6, -23, "A wooden tankard of ale set down on a wooden table, solid knock with a small slosh, " + GAME,
  maxlen=0.55, impact=True, bursts=1, hook="TavernServingEntity / mug set down", cur="old (Sonniss mugs)")
t("tavern.clink", "tavern", 1, 4, 0.8, -21, "Two wooden ale tankards clinking together in a toast, with a slosh of ale, " + GAME,
  alts=["pewter", "wood", "three mugs"], maxlen=0.7, new=True, sub="Mugs clink", hook="tavern toast", cur="vanilla decorated pot place")
t("tavern.pour", "tavern", 1, 3, 1.3, -22, "Ale poured from a clay jug into a wooden tankard, foamy glug, " + GAME,
  maxlen=1.2, new=True, sub="Ale pours", hook="TavernServingEntity pour", cur="vanilla bottle empty")
t("cheer", "voice", 1, 4, 1.2, -16, "A small tavern crowd giving a short happy cheer, rowdy, indoors, " + DRY,
  alts=["with table thumps", "men and women", "raised mugs"], maxlen=1.2, sustained=True, hook="settlers cheer",
  cur="old (Sonniss voices)")
t("tavern_laugh", "tavern", 1, 4, 2.5, -19, "A few people in a medieval tavern bursting into hearty laughter, indoors, " + DRY,
  maxlen=2.5, sustained=True, hook="tavern ambient laugh", cur="old (Sonniss walla)")
t("tavern.drink", "tavern", 2, 3, 0.7, -23, "A person taking two gulps of ale from a wooden tankard, satisfied, " + GAME,
  maxlen=0.65, new=True, sub="Someone drinks", hook="motion clips seated_drink / seated_toast", cur="vanilla generic drink")
t("tavern_ambience", "tavern", 2, 2, 12.0, -22, "Medieval tavern room tone: murmuring crowd walla, distant mugs, crackling hearth, indoors, no music, loopable",
  maxlen=12.0, loop=True, sustained=True, hook="tavern ambience loop", cur="old (Sonniss walla)")
t("tavern_fire", "tavern", 2, 3, 3.5, -28, "A hearth fire crackling and popping softly, logs burning, indoors, " + DRY,
  maxlen=3.5, sustained=True, hook="tavern hearth", cur="old (Sonniss campfire)")

# ---------------------------------------------------------------- WORK (hammer/chisel P1)
t("anvil_ring", "work", 1, 6, 0.6, -20, "A single blacksmith hammer strike on a hot iron bar on an anvil, bright clang with short ring, " + GAME,
  alts=["heavy", "light tap", "mid"], maxlen=0.55, bursts=1, impact=True, hook="WorkSoundSync smith hammer contact", cur="old (Sonniss cut)")
t("chisel_tap", "work", 1, 5, 0.5, -22, "A single mallet tap on a steel chisel cutting stone, crisp stony click, " + GAME,
  alts=["sharp", "dull", "gritty"], maxlen=0.35, bursts=1, impact=True, hook="WorkSoundSync mason chisel contact", cur="vanilla dig stone")
t("nail_tap", "builder", 1, 5, 0.5, -22, "A single hammer blow driving an iron nail into a wooden plank, short solid knock, " + GAME,
  alts=["", "lighter", "deeper"], maxlen=0.35, bursts=1, impact=True, hook="WorkSoundSync builder/carpenter hammer (WORK_NAIL)", cur="vanilla dig wood")
t("builder.place", "builder", 1, 4, 0.5, -21, "A heavy wooden beam set firmly into place, solid wooden thunk, " + GAME,
  alts=["stone block", "plank", "timber"], maxlen=0.45, bursts=1, impact=True, new=True, sub="Builder places a block",
  hook="builder places a block", cur="vanilla block place")
t("pick_strike", "work", 1, 5, 0.5, -21, "A single iron pickaxe strike into rock, sharp stony crack with a few pebbles, " + GAME,
  maxlen=0.45, bursts=1, impact=True, hook="WorkSoundSync miner contact", cur="vanilla step stone")
t("chop", "work", 1, 5, 0.5, -20, "A single axe chop into a tree trunk, solid woody thock, " + GAME,
  maxlen=0.4, bursts=1, impact=True, hook="WorkSoundSync lumberer chop", cur="old (Sonniss cut)")
t("saw_stroke", "work", 2, 4, 1.0, -20, "One push stroke of a hand saw through a wooden plank, rasping, " + GAME,
  maxlen=0.9, hook="WorkSoundSync carpenter saw", cur="old (OGA+Sonniss)")
t("plane_shave", "work", 2, 4, 0.7, -22, "One stroke of a wood hand plane shaving a board, curly shaving hiss, " + GAME,
  maxlen=0.6, hook="WorkSoundSync carpenter plane", cur="vanilla axe strip")
t("whetstone_scrape", "work", 2, 4, 0.5, -22, "One stroke of a blade sharpened on a whetstone, short steel scrape, " + GAME,
  maxlen=0.45, hook="motion clip whetstone", cur="old (Kenney/OGA)")
t("bellows_puff", "work", 2, 3, 0.8, -24, "A forge bellows pumped once, a deep whoosh of air into glowing coals, " + GAME,
  maxlen=0.75, hook="WorkSoundSync smith bellows", cur="vanilla blast furnace")
t("cleaver_chop", "work", 2, 4, 0.5, -21, "A butcher's cleaver chopping through meat onto a wooden block, " + GAME,
  maxlen=0.4, bursts=1, impact=True, hook="WorkSoundSync butcher/cook chop", cur="vanilla step wood")
t("knead_press", "work", 2, 3, 0.6, -24, "Hands pressing and folding bread dough on a floured table, soft squish, " + GAME,
  maxlen=0.55, hook="WorkSoundSync baker knead", cur="vanilla mud step")
t("loom_clack", "work", 2, 4, 0.5, -23, "A wooden loom beater clacking once against the weave, " + GAME,
  maxlen=0.4, bursts=1, impact=True, hook="WorkSoundSync weaver loom", cur="vanilla loom select")
t("pot_stir", "work", 2, 3, 1.0, -24, "A wooden spoon stirring a thick stew in an iron pot, " + GAME,
  maxlen=0.9, hook="WorkSoundSync cook stir", cur="vanilla swim")
t("feather_pinch", "work", 3, 3, 0.5, -25, "Fletching an arrow: a feather pressed and tied to a wooden shaft, tiny rustle, " + GAME,
  maxlen=0.4, hook="WorkSoundSync fletcher", cur="vanilla fletching table")
t("hide_scrape", "work", 2, 3, 0.7, -23, "A scraping knife dragged across a stretched animal hide, " + GAME,
  maxlen=0.6, hook="WorkSoundSync tanner", cur="vanilla brush")
t("farmer_work", "work", 2, 5, 0.5, -23, "A garden hoe chopping into soft soil, short earthy scrape, " + GAME,
  maxlen=0.45, bursts=1, impact=True, hook="WorkSoundSync farmer till", cur="vanilla hoe till")
t("seed_press", "work", 3, 3, 0.5, -25, "A thumb pressing a seed into soft soil, tiny earthy pat, " + GAME,
  maxlen=0.35, hook="farmer plant", cur="vanilla crop")
t("crop_pull", "work", 2, 4, 0.5, -24, "Pulling a vegetable out of the ground, leafy rustle and soil pop, " + GAME,
  maxlen=0.45, hook="farmer harvest", cur="vanilla berries/grass")
t("oven_slide", "work", 3, 3, 0.8, -22, "A wooden bread peel sliding a loaf into a stone oven, scrape with fire crackle, " + GAME,
  maxlen=0.75, hook="baker oven", cur="vanilla furnace crackle")
t("water_pour", "work", 3, 3, 1.0, -22, "Water poured from a wooden bucket onto soil, short splash, " + GAME,
  maxlen=0.9, hook="farmer watering", cur="vanilla bucket empty")

# ---------------------------------------------------------------- WORLD EVENTS (P2)
t("event.dog_bark", "world", 2, 4, 0.5, -17, "A single friendly village dog bark, medium sized dog, outdoors, " + GAME,
  maxlen=0.45, bursts=1, new=True, sub="Dog barks", hook="StrayDogEvent / VillageDog", cur="vanilla wolf ambient")
t("event.dog_whine", "world", 2, 2, 0.8, -20, "A dog whining softly, begging, short, " + GAME,
  maxlen=0.8, new=True, sub="Dog whines", hook="StrayDogEvent / VillageDog", cur="vanilla wolf whine")
t("event.caravan_arrive", "world", 2, 2, 3.0, -18, "A horse-drawn wooden merchant cart arriving: hooves, creaking wheels and jingling harness bells, outdoors, " + DRY,
  maxlen=3.0, sustained=True, new=True, sub="Caravan arrives", hook="CaravanEvent arrival", cur="vanilla llama + pillager celebrate")
t("event.wolf_howl", "world", 2, 2, 3.0, -16, "A wild wolf howling at night in the distance, eerie, outdoors, " + DRY,
  maxlen=3.0, sustained=True, new=True, sub="Wolves howl", hook="WolfPackEvent den howl", cur="vanilla wolf howl")
t("event.boar_grunt", "world", 2, 3, 0.8, -18, "A wild boar grunting and snorting, aggressive, " + GAME,
  maxlen=0.8, new=True, sub="Boar grunts", hook="WildBoarEntity ambient", cur="vanilla pig ambient")
t("event.boar_charge", "world", 2, 2, 1.0, -16, "An angry wild boar squealing as it charges, " + GAME,
  maxlen=1.0, new=True, sub="Boar charges", hook="WildBoarEntity/Event angry", cur="vanilla hoglin angry")
t("event.envoy_fanfare", "world", 2, 2, 3.0, -16, "A short medieval herald fanfare on two natural trumpets, announcing an envoy, outdoors, " + DRY,
  maxlen=3.0, sustained=True, new=True, sub="Herald fanfare", hook="envoy/peddler arrival", cur="vanilla wandering trader")
t("raid_horn", "raid", 1, 2, 5.0, -14, "A long deep war horn blown twice across a valley, ominous, outdoors, low reverb only, no music",
  maxlen=5.0, sustained=True, hook="raid start / RaidParley", cur="CC0 recorded horn")
t("village_bell", "world", 2, 3, 4.0, -16, "A village bell tolling once, bronze, outdoors, natural decay", maxlen=4.0,
  sustained=True, lofi=0.2, hook="village bell", cur="old (Sonniss church bells)")
t("settler.hurt", "voice", 2, 4, 0.5, -19, "A villager grunting in pain from a hit, short, single voice, " + GAME,
  alts=["male", "female", "older male"], maxlen=0.45, new=True, sub="Settler hurts", hook="SettlerEntity getHurtSound", cur="vanilla player hurt")

# ---------------------------------------------------------------- AMBIENCE (P3)
t("ambient.village_murmur", "ambience", 3, 2, 10.0, -28, "Distant medieval village daytime ambience: faint chatter, a far hammer, chickens, gentle wind, outdoors, no music, loopable",
  maxlen=10.0, loop=True, sustained=True, new=True, sub=None, hook="AmbientClient village bed", cur="none")
t("ambient.workshop_smithy", "ambience", 3, 1, 8.0, -30, "Blacksmith workshop room tone: low forge roar and soft coal crackle, no hammering, indoors, loopable",
  maxlen=8.0, loop=True, sustained=True, new=True, sub=None, hook="AmbientClient smithy room tone", cur="none")


# ================================================================ EXPANDED SCOPE (owner, 26 Sep: "sounds for everything")
# ---- per-trade contact sounds (each job its own voice; WorkSoundSync timing unchanged)
t("work.plate_hammer", "work", 2, 4, 0.5, -21, "A single hammer blow shaping a curved steel armour plate on a stake anvil, bright tinny clank, " + GAME,
  alts=["lighter", "duller"], maxlen=0.45, lofi=0.2, bursts=1, impact=True, new=True, sub="Armourer hammers",
  hook="Employment.soundOf ARMOURER (motion WORK_HAMMER)", cur="shared anvil_ring")
t("work.quern_grind", "work", 2, 3, 0.9, -23, "A stone hand quern turned once, grinding grain between millstones, gritty stone rumble, " + GAME,
  maxlen=0.8, new=True, sub="Quern grinds", hook="Employment.soundOf MILLER (WORK_KNEAD)", cur="shared knead_press")
t("work.mash_stir", "work", 2, 3, 0.9, -23, "A wooden paddle stirring thick barley mash in a wooden tub, heavy wet slosh, " + GAME,
  maxlen=0.8, new=True, sub="Mash sloshes", hook="Employment.soundOf BREWER (WORK_STOKE)", cur="shared bellows_puff")
t("work.quill_scratch", "work", 2, 4, 0.6, -26, "A goose quill pen scratching a short line of writing on parchment, " + GAME,
  maxlen=0.55, new=True, sub="Quill scratches", hook="Employment.soundOf SCHOLAR / ScholarWorkGoal", cur="shared feather_pinch")
t("work.pestle_grind", "work", 2, 3, 0.7, -24, "A stone pestle grinding dried herbs in a stone mortar, one gritty twist, " + GAME,
  maxlen=0.6, new=True, sub="Herbs ground", hook="Employment.soundOf HEALER (WORK_WEAVE)", cur="shared loom_clack")
t("work.shear_snip", "work", 2, 4, 0.5, -23, "Iron hand shears snipping thick sheep wool once, crisp double-blade snip, " + GAME,
  maxlen=0.4, bursts=1, new=True, sub="Shears snip", hook="HerderWorkGoal shear", cur="vanilla shear / hide_scrape")
t("work.fish_splash", "work", 2, 3, 0.8, -21, "A fish pulled out of a pond, lively splash and water drips, " + GAME,
  maxlen=0.8, new=True, sub="Fish splashes", hook="FisherWorkGoal catch", cur="vanilla bobber splash")
t("work.bow_loose", "work", 2, 3, 0.5, -20, "A wooden longbow string released, deep twang and arrow whoosh, " + GAME,
  maxlen=0.45, bursts=1, new=True, sub="Bow looses", hook="HunterWorkGoal / ArcherAttackGoal shot", cur="vanilla arrow shoot")
t("work.ledger_tally", "work", 3, 3, 0.6, -24, "A few coins slid and stacked on a wooden counter, small clinks, " + GAME,
  maxlen=0.55, new=True, sub="Coins counted", hook="Employment.soundOf TRADER (SORTING)", cur="shared chest_stow")
t("work.bar_wipe", "work", 3, 3, 0.6, -25, "A cloth rag wiping a wooden tankard, soft squeaky rub, " + GAME,
  maxlen=0.55, new=True, sub="Innkeeper wipes a mug", hook="Employment.soundOf INNKEEPER (SORTING)", cur="shared chest_stow")
t("builder.ladder_rung", "builder", 2, 3, 0.5, -22, "A wooden scaffold rung knocked into place with a mallet, hollow wooden knock, " + GAME,
  maxlen=0.35, bursts=1, impact=True, new=True, sub="Scaffold knocks", hook="BuilderWorkGoal.tickScaffold", cur="nail_tap")

# ---- patrol, summon, commands per role
t("patrol.march", "combat", 2, 3, 1.2, -21, "A small squad of soldiers marching in step on a dirt road, boots, leather creak and chainmail jingle, " + DRY,
  maxlen=1.2, sustained=True, new=True, sub="Squad marches", hook="patrol route start", cur="none")
t("patrol.halt", "combat", 2, 2, 0.8, -20, "Soldiers stamping to a halt together, one boot stomp and an armour rattle, " + GAME,
  maxlen=0.7, new=True, sub="Squad halts", hook="patrol halt", cur="none")
t("summon.horn", "raid", 2, 2, 2.0, -16, "A short rallying hunting horn call, two rising notes, outdoors, " + DRY,
  maxlen=2.0, sustained=True, new=True, sub="Rally horn", hook="Summons.call", cur="village_bell")
t("command_ack.spear", "voice", 2, 2, 0.9, -19, "Soldiers answer with a short shout of Hoo and thump spear butts on the ground, " + DRY,
  maxlen=0.9, new=True, sub="Spearmen answer", hook="command ack (spearmen)", cur="command_ack")
t("command_ack.archer", "voice", 2, 2, 0.9, -19, "Archers answer with a short shout of Aye and a rattle of arrows in quivers, " + DRY,
  maxlen=0.9, new=True, sub="Archers answer", hook="command ack (archers)", cur="command_ack")
t("command_ack.mage", "voice", 3, 2, 1.0, -20, "A soft low magical hum answering a command, rune stones clicking, " + GAME,
  maxlen=0.9, new=True, sub="Rune mage answers", hook="command ack (rune mages)", cur="command_ack")

# ---- conversation
t("ui.conversation_open", "ui", 1, 3, 0.9, -23, "A soft leather and cloth whoosh drawing close, ending in one gentle small bell chime, warm and inviting, " + GAME,
  alts=["wooden", "airy"], maxlen=0.8, lofi=0.35, new=True, sub=None,
  hook="ConversationVoice / conversation start (conversation lane)", cur="none")
t("convo.pull_in", "ui", 2, 2, 0.8, -24, "A soft cloth whoosh drawing closer, gentle and quick, " + GAME,
  maxlen=0.7, lofi=0.4, new=True, sub=None, hook="ConversationCamera pull-in", cur="none")
t("convo.name_card", "ui", 2, 2, 0.6, -24, "A parchment card slid across a wooden table with a soft plucked note, " + GAME,
  maxlen=0.55, lofi=0.4, new=True, sub=None, hook="conversation name card", cur="none")
t("convo.persuade_ok", "ui", 2, 2, 1.0, -20, "A warm success cue: two rising plucked lute notes and a soft chime, " + GAME,
  maxlen=1.0, new=True, sub="Persuaded", hook="persuasion success", cur="none")
t("convo.persuade_fail", "ui", 2, 2, 0.8, -21, "A gentle failure cue: two falling muted lute notes, a little dull, " + GAME,
  maxlen=0.8, new=True, sub="Not convinced", hook="persuasion fail", cur="none")
t("convo.deal", "ui", 2, 2, 0.9, -20, "A firm handshake clap and a small coin pouch dropped on a table, deal sealed, " + GAME,
  maxlen=0.8, new=True, sub="Deal struck", hook="barter/deal accepted", cur="none")

# ---- more events, raid outcome, UI pages
t("event.brute_grunt", "world", 2, 3, 1.0, -16, "A huge brute man grunting and snorting menacingly, wordless, " + GAME,
  maxlen=1.0, new=True, sub="Brute grunts", hook="BruteTollEvent approach", cur="vanilla ravager roar")
t("event.brute_demand", "world", 2, 2, 1.6, -15, "A huge brute pounding his chest twice and growling a wordless demand, " + GAME,
  maxlen=1.6, new=True, sub="Brute demands", hook="BruteTollEvent demand", cur="vanilla ravager roar")
t("event.fox_yip", "world", 3, 2, 0.6, -19, "A red fox giving a sharp short yip, outdoors, " + GAME,
  maxlen=0.5, new=True, sub="Fox yips", hook="FieldFoxEvent", cur="vanilla fox screech")
t("event.peddler_bells", "world", 2, 2, 2.0, -19, "Llama harness bells jingling as a peddler's pack animal walks up, outdoors, " + DRY,
  maxlen=2.0, sustained=True, new=True, sub="Harness bells jingle", hook="PeddlerEvent.start", cur="vanilla wandering trader")
t("event.minstrel_sting", "tavern", 2, 3, 3.0, -19, "A short cheerful medieval minstrel flourish on lute and fiddle, 3 seconds, " + DRY,
  alts=["with a tambourine", "gentle"], maxlen=3.0, sustained=True, lofi=0.4, new=True, sub="Minstrel plays",
  hook="TavernBard lift / tavern music", cur="note blocks")
t("raid.won_fanfare", "raid", 1, 2, 3.0, -16, "A proud victory fanfare on two natural trumpets with a drum roll ending, short, outdoors, " + DRY,
  maxlen=3.0, sustained=True, new=True, sub="Victory fanfare", hook="RaidPresentation.resolved (held)", cur="vanilla toast challenge")
t("raid.lost_toll", "raid", 1, 2, 4.0, -17, "A slow mournful village bell tolling twice, low and heavy, outdoors, natural decay",
  maxlen=4.0, sustained=True, lofi=0.2, new=True, sub="Bell tolls in mourning", hook="RaidPresentation.resolved (lost)", cur="village_bell")
t("ui.page_turn", "ui", 1, 4, 0.5, -25, "A single parchment page turned in a thick old book, crisp paper flip, " + GAME,
  maxlen=0.45, lofi=0.4, new=True, sub="Page turns", hook="HandbookScreen page change", cur="vanilla book page turn")
t("settler.death", "voice", 3, 2, 0.9, -19, "A villager's short soft dying groan, single voice, " + GAME, alts=["male", "female"],
  maxlen=0.8, new=True, sub="Settler dies", hook="SettlerEntity getDeathSound", cur="vanilla player death")

# ---- ambience layers (client loops)
t("ambient.night_crickets", "ambience", 3, 1, 10.0, -30, "Night meadow ambience: crickets chirping softly, faint breeze, no birds, no music, loopable",
  maxlen=10.0, loop=True, sustained=True, new=True, sub=None, hook="night near the town", cur="none")
t("ambient.market_bustle", "ambience", 3, 1, 10.0, -28, "Small medieval market bustle: murmuring buyers, a few coins, a cart, a distant goat, outdoors, no music, loopable",
  maxlen=10.0, loop=True, sustained=True, new=True, sub=None, hook="merchant/peddler present", cur="none")
t("ambient.rain_roof", "ambience", 3, 1, 10.0, -30, "Steady rain on wooden shingle roofs and dripping eaves, cosy, no thunder, no music, loopable",
  maxlen=10.0, loop=True, sustained=True, new=True, sub=None, hook="rain inside the town", cur="vanilla rain only")
t("ambient.workshop_wood", "ambience", 3, 1, 8.0, -31, "Carpenter workshop room tone: faint creaks, sawdust, a distant knock, indoors, loopable, no music",
  maxlen=8.0, loop=True, sustained=True, new=True, sub=None, hook="near sawmill/carpenter", cur="none")
# ---- hero Captain specials (battle-roles lane; ids registered in RoleItems, entries already in sounds.json)
t("captain.windup", "combat", 2, 3, 0.8, -19, "A knight drawing back for a special attack: armour plates clinking and a rising steel scrape, tense, " + GAME,
  maxlen=0.7, hook="CaptainFx special telegraph", cur="vanilla armour clink + sweep")
t("captain.impact", "combat", 2, 3, 0.9, -15, "A mighty two-handed sword blow landing: heavy steel impact with a deep thump and a short metallic ring, " + GAME,
  maxlen=0.8, impact=True, hook="CaptainFx special resolves", cur="old swing_heavy pitched down")
t("captain.rally", "raid", 2, 2, 2.5, -15, "A knight's rallying war horn blast, bold and heroic, one long rising call, outdoors, " + DRY,
  maxlen=2.5, sustained=True, hook="Captain Rally Cry", cur="vanilla goat horn")
t("captain.promoted", "ui", 2, 2, 3.0, -16, "A short noble commissioning fanfare: two trumpets and a drum roll ending on a bright cymbal, proud, " + DRY,
  maxlen=3.0, sustained=True, hook="Captain commissioned", cur="vanilla goat horn + toast")

# ---- tavern lane clip cues (26 Sep request); quiet tavern mix
t("tavern.chuckle", "tavern", 2, 3, 0.8, -25, "A man chuckling softly, two or three short breathy heh-hehs, amused, " + GAME,
  maxlen=0.7, new=True, sub="Someone chuckles", hook="tavern clips (listener)", cur="none")
t("tavern.heh", "tavern", 2, 3, 0.5, -27, "A single small quiet amused heh through the nose, " + GAME,
  maxlen=0.35, new=True, sub="Someone chuckles", hook="tavern clips", cur="none")
t("tavern.table_slap", "tavern", 2, 3, 0.5, -22, "An open palm slapped once on a heavy wooden table, " + GAME,
  maxlen=0.35, bursts=1, impact=True, new=True, sub="Hand slaps table", hook="tavern clips", cur="vanilla wood hit")
t("tavern.seat_creak", "tavern", 2, 3, 0.6, -28, "A wooden chair creaking as someone shifts their weight, short, " + GAME,
  maxlen=0.5, new=True, sub="Chair creaks", hook="tavern clips", cur="vanilla ladder step")
t("tavern.cloth_rustle", "tavern", 2, 3, 0.5, -30, "Wool clothes rustling as someone stretches, soft, " + GAME,
  maxlen=0.45, new=True, sub="Clothes rustle", hook="tavern clips", cur="vanilla wool step")
t("tavern.tap_valve", "tavern", 2, 3, 0.5, -26, "A wooden barrel tap handle turned open with a small knock and a gurgle, " + GAME,
  maxlen=0.45, new=True, sub="Tap turns", hook="tavern clips (ale pour)", cur="vanilla wooden button")
t("tavern.bar_creak", "tavern", 2, 2, 0.6, -29, "A heavy wooden bar counter creaking softly as someone leans on it, " + GAME,
  maxlen=0.55, new=True, sub="Bar creaks", hook="tavern clips (innkeeper)", cur="none")
t("tavern.jig_step", "tavern", 2, 4, 0.5, -25, "A single light heel tap of a leather shoe on a wooden floor, dancing, " + GAME,
  maxlen=0.25, bursts=1, impact=True, new=True, sub="Feet tap", hook="tavern clips (dance jig)", cur="vanilla wood step")
t("brawl.punch_hit", "tavern", 2, 3, 0.5, -20, "A single fist punch landing on a body, dull heavy thump, " + GAME,
  maxlen=0.35, bursts=1, impact=True, new=True, sub="Punch lands", hook="tavern brawl clips", cur="none")
t("brawl.grunt", "voice", 2, 3, 0.5, -22, "A man's short effort grunt as he throws a punch, single voice, " + GAME,
  maxlen=0.35, new=True, sub="Someone grunts", hook="tavern brawl clips", cur="none")
t("tavern.hiccup", "tavern", 2, 4, 0.5, -28, "A single small drunken hiccup, short, " + GAME,
  maxlen=0.3, new=True, sub="Someone hiccups", hook="drunk walk / tavern clips", cur="none")
t("tavern.burp", "tavern", 2, 3, 0.6, -29, "A small quiet satisfied burp after ale, short, " + GAME,
  maxlen=0.45, new=True, sub="Someone burps", hook="drunk walk / tavern clips", cur="none")

t("drunk.scuff", "tavern", 2, 3, 0.5, -27, "A leather boot scuffing and catching on a wooden floor as someone stumbles, short, " + GAME,
  maxlen=0.4, new=True, sub="Boots scuff", hook="drunk stumble (tavern lane)", cur="none")
t("drunk.thud", "tavern", 2, 3, 0.6, -23, "A person flopping down onto dirt ground, soft cloth and body thump, harmless and a bit comic, " + GAME,
  maxlen=0.5, bursts=1, impact=True, new=True, sub="Someone falls over", hook="drunk fall contact frame", cur="none")
t("drunk.groan", "voice", 2, 3, 0.8, -26, "A drunk man's short comic groan, a lazy 'ughh' while lying on the ground, not in pain, single voice, " + GAME,
  maxlen=0.7, new=True, sub="Someone groans", hook="drunk lying down", cur="none")


TARGETS = T


def candidates_for(t, extra=0):
    """The candidate plan for one target: list of {prompt, dur, infl}."""
    n = t["n"]
    if t.get("cands"):
        k = t["cands"]
    elif t["pri"] == 1:
        k = max(n + 2, math.ceil(n * 1.75))
    elif t["pri"] == 2:
        k = n + 2 if n <= 2 else n + 1
    else:
        k = n + 1
    k += extra
    out = []
    alts = [""] + list(t["alts"])
    for i in range(k):
        a = alts[i % len(alts)]
        p = t["prompt"] if not a else t["prompt"].replace(", ", f", {a}, ", 1)
        infl = t["infl"] if i % 3 != 2 else max(0.25, t["infl"] - 0.15)   # a looser take every third
        out.append({"prompt": p, "dur": t["dur"], "infl": round(infl, 2)})
    return out


def est_credits(t, p):
    return int(round(40 * max(0.5, p["dur"])))

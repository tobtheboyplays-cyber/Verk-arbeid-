package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(Registries.SOUND_EVENT, Hearthstead.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> GOBLIN_NOTICE =
        register("goblin_notice");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOBLIN_SNEAK =
        register("goblin_sneak");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOBLIN_STEAL =
        register("goblin_steal");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOBLIN_SPOTTED =
        register("goblin_spotted");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOBLIN_FLEE =
        register("goblin_flee");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOBLIN_HURT =
        register("goblin_hurt");

    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLEMENT_FOUNDED =
        register("settlement_founded");
    public static final DeferredHolder<SoundEvent, SoundEvent> PROFESSION_ASSIGNED =
        register("profession_assigned");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_RECRUITED =
        register("settler_recruited");
    // The settlement Banner (block id hearthstead:hearth): raised on placement
    // and on new heraldry; a soft cloth flutter replaces the old fire crackle.
    public static final DeferredHolder<SoundEvent, SoundEvent> BANNER_RAISE =
        register("banner_raise");
    public static final DeferredHolder<SoundEvent, SoundEvent> BANNER_FLUTTER =
        register("banner_flutter");

    // World and settlement cues that replace vanilla stand-ins (wood break,
    // bell block, raid horn, campfire crackle). Loudness and range live in
    // sounds.json; call sites play at or below 1.0 volume.
    public static final DeferredHolder<SoundEvent, SoundEvent> TREE_CREAK =
        register("tree_creak");
    /** The crash sits on the first beat, so play it on the impact tick itself. */
    public static final DeferredHolder<SoundEvent, SoundEvent> TREE_FALL =
        register("tree_fall");
    public static final DeferredHolder<SoundEvent, SoundEvent> VILLAGE_BELL =
        register("village_bell");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_HORN =
        register("raid_horn");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_AMBIENCE =
        register("tavern_ambience");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_LAUGH =
        register("tavern_laugh");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUG_SET =
        register("mug_set");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_FIRE =
        register("tavern_fire");
    /** Reserved: no cart entity exists yet, so nothing plays this. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CART_ROLL =
        register("cart_roll");

    // Physical, low-volume interface grammar. These are intentionally
    // separate from the vanilla button click: screens play OPEN/CLOSE only on
    // a real transition and CONFIRM/ERROR only after an authoritative result.
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_OPEN =
        register("ui_open");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_CLOSE =
        register("ui_close");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_CONFIRM =
        register("ui_confirm");
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_ERROR =
        register("ui_error");
    public static final DeferredHolder<SoundEvent, SoundEvent> FISHER_WHISTLE =
        register("fisher_whistle");
    public static final DeferredHolder<SoundEvent, SoundEvent> FARMER_WORK =
        register("farmer_work");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHOP =
        register("chop");
    public static final DeferredHolder<SoundEvent, SoundEvent> GUARD_ALERT =
        register("guard_alert");
    /** Short, original two-note confirmation for one valid defender kill. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GUARD_EXPERIENCE =
        register("guard_experience");

    // Job standard, point 6: one distinct sound per work motion.
    public static final DeferredHolder<SoundEvent, SoundEvent> LEAP_SLAM =
        register("leap_slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> ARMOUR_CLINK =
        register("armour_clink");
    public static final DeferredHolder<SoundEvent, SoundEvent> PICK_STRIKE =
        register("pick_strike");
    public static final DeferredHolder<SoundEvent, SoundEvent> ANVIL_RING =
        register("anvil_ring");
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLOWS_PUFF =
        register("bellows_puff");
    public static final DeferredHolder<SoundEvent, SoundEvent> SAW_STROKE =
        register("saw_stroke");
    public static final DeferredHolder<SoundEvent, SoundEvent> OVEN_SLIDE =
        register("oven_slide");
    public static final DeferredHolder<SoundEvent, SoundEvent> KNEAD_PRESS =
        register("knead_press");
    public static final DeferredHolder<SoundEvent, SoundEvent> CLEAVER_CHOP =
        register("cleaver_chop");
    public static final DeferredHolder<SoundEvent, SoundEvent> LOOM_CLACK =
        register("loom_clack");

    // JOB_STANDARD point 6 -- the last five trades' own voices (catalogue
    // §20): cook, carpenter, mason, fletcher, tanner no longer borrow.
    public static final DeferredHolder<SoundEvent, SoundEvent> POT_STIR =
        register("pot_stir");
    public static final DeferredHolder<SoundEvent, SoundEvent> PLANE_SHAVE =
        register("plane_shave");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHISEL_TAP =
        register("chisel_tap");
    /** Light hammer-on-nail tap for wooden repairs (WORK_NAIL). */
    public static final DeferredHolder<SoundEvent, SoundEvent> NAIL_TAP =
        register("nail_tap");
    public static final DeferredHolder<SoundEvent, SoundEvent> FEATHER_PINCH =
        register("feather_pinch");
    public static final DeferredHolder<SoundEvent, SoundEvent> HIDE_SCRAPE =
        register("hide_scrape");

    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_HM =
        register("settler_hm");

    /** One short wordless phrase; the playback owner controls spacing and cancellation. */
    public static final DeferredHolder<SoundEvent, SoundEvent> INNKEEPER_HUM =
        register("innkeeper_hum");

    // SLICE ANIM-1 additions.
    public static final DeferredHolder<SoundEvent, SoundEvent> SEED_PRESS =
        register("seed_press");
    public static final DeferredHolder<SoundEvent, SoundEvent> CROP_PULL =
        register("crop_pull");
    public static final DeferredHolder<SoundEvent, SoundEvent> BAG_STOW =
        register("bag_stow");
    /** Contact accents for the Lumberer's persistent, world-locked work sack. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BAG_DOWN =
        register("bag_down");
    public static final DeferredHolder<SoundEvent, SoundEvent> BAG_UP =
        register("bag_up");
    public static final DeferredHolder<SoundEvent, SoundEvent> WATER_POUR =
        register("water_pour");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLADE_HIT =
        register("blade_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> YAWN =
        register("yawn");
    public static final DeferredHolder<SoundEvent, SoundEvent> LADDER_CREAK =
        register("ladder_creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_EAT =
        register("settler_eat");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_PANIC =
        register("settler_panic");
    public static final DeferredHolder<SoundEvent, SoundEvent> SHIELD_THUD =
        register("shield_thud");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHEER =
        register("cheer");

    // SLICE A2a -- the carry grammar.
    public static final DeferredHolder<SoundEvent, SoundEvent> HAUL_STEP =
        register("haul_step");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRATE_GRIP =
        register("crate_grip");
    public static final DeferredHolder<SoundEvent, SoundEvent> HAUL_STRAIN =
        register("haul_strain");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRATE_CREAK =
        register("crate_creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> CRATE_DOWN =
        register("crate_down");
    public static final DeferredHolder<SoundEvent, SoundEvent> ITEM_PICKUP =
        register("item_pickup");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHEST_STOW =
        register("chest_stow");

    // Guard/raider moveset swings (entity/combat/GuardMove, RaiderMove).
    // sounds.json maps each to its vanilla attack sound; the goals keep their
    // own per-move volume and pitch.
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBAT_SWING_LIGHT =
        register("combat.swing_light");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBAT_SWING_HEAVY =
        register("combat.swing_heavy");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBAT_HEAVY_IMPACT =
        register("combat.heavy_impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBAT_BASH_SWING =
        register("combat.bash_swing");
    /** Field orders (R/G guard commands): the commander's bark and the soldiers' reply. */
    public static final DeferredHolder<SoundEvent, SoundEvent> COMMAND_SHOUT =
        register("command_shout");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMMAND_ACK =
        register("command_ack");

    // ElevenLabs premium sound pass (sound-gen/SOUNDS.md). Each replaces a
    // vanilla stand-in or fills a silent moment; sounds.json owns the files.
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIDER_BRUTE_ROAR = register("raider.brute_roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIDER_BRUTE_SLAM = register("raider.brute_slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIDER_BARK = register("raider.bark");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIDER_HURT = register("raider.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIDER_DEATH = register("raider.death");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_HURT = register("settler.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> SETTLER_DEATH = register("settler.death");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_CLINK = register("tavern.clink");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_POUR = register("tavern.pour");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_DRINK = register("tavern.drink");
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDER_PLACE = register("builder.place");
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDER_LADDER_RUNG = register("builder.ladder_rung");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_DOG_BARK = register("event.dog_bark");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_DOG_WHINE = register("event.dog_whine");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_CARAVAN_ARRIVE = register("event.caravan_arrive");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_WOLF_HOWL = register("event.wolf_howl");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_BOAR_GRUNT = register("event.boar_grunt");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_BOAR_CHARGE = register("event.boar_charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_ENVOY_FANFARE = register("event.envoy_fanfare");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_BRUTE_GRUNT = register("event.brute_grunt");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_BRUTE_DEMAND = register("event.brute_demand");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_FOX_YIP = register("event.fox_yip");
    public static final DeferredHolder<SoundEvent, SoundEvent> EVENT_PEDDLER_BELLS = register("event.peddler_bells");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_WON_FANFARE = register("raid.won_fanfare");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAID_LOST_TOLL = register("raid.lost_toll");
    public static final DeferredHolder<SoundEvent, SoundEvent> SUMMON_HORN = register("summon.horn");
    public static final DeferredHolder<SoundEvent, SoundEvent> PATROL_MARCH = register("patrol.march");
    public static final DeferredHolder<SoundEvent, SoundEvent> PATROL_HALT = register("patrol.halt");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMMAND_ACK_SPEAR = register("command_ack.spear");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMMAND_ACK_ARCHER = register("command_ack.archer");
    public static final DeferredHolder<SoundEvent, SoundEvent> COMMAND_ACK_MAGE = register("command_ack.mage");
    // Per-weapon finishing stingers (FinisherService.stingerFor); swords keep combat.execution_stinger.
    public static final DeferredHolder<SoundEvent, SoundEvent> EXECUTION_STINGER_AXE = register("combat.execution_stinger.axe");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXECUTION_STINGER_MACE = register("combat.execution_stinger.mace");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXECUTION_STINGER_SPEAR = register("combat.execution_stinger.spear");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXECUTION_STINGER_BARE = register("combat.execution_stinger.bare");
    // Tavern-lane clip cues (ClipSound, quiet tavern mix) and the drunk walk.
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_CHUCKLE = register("tavern.chuckle");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_HEH = register("tavern.heh");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_TABLE_SLAP = register("tavern.table_slap");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_SEAT_CREAK = register("tavern.seat_creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_CLOTH_RUSTLE = register("tavern.cloth_rustle");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_TAP_VALVE = register("tavern.tap_valve");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_BAR_CREAK = register("tavern.bar_creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_JIG_STEP = register("tavern.jig_step");
    public static final DeferredHolder<SoundEvent, SoundEvent> BRAWL_PUNCH_HIT = register("brawl.punch_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> BRAWL_GRUNT = register("brawl.grunt");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_HICCUP = register("tavern.hiccup");
    public static final DeferredHolder<SoundEvent, SoundEvent> TAVERN_BURP = register("tavern.burp");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUNK_SCUFF = register("drunk.scuff");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUNK_THUD = register("drunk.thud");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRUNK_GROAN = register("drunk.groan");
    // Villager-voice ids (sounds.json points at vanilla villager files by path; nothing copied).
    public static final DeferredHolder<SoundEvent, SoundEvent> VOICE_NPC_AMBIENT = register("voice.npc.ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOICE_NPC_YES = register("voice.npc.yes");
    public static final DeferredHolder<SoundEvent, SoundEvent> VOICE_NPC_NO = register("voice.npc.no");
    // Bannerhold soundtrack (Eleven Music); chosen by BannerholdMusicClient through SelectMusicEvent.
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_VILLAGE_DAY = register("music.village_day");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_VILLAGE_NIGHT = register("music.village_night");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_TAVERN = register("music.tavern");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_RAID = register("music.raid");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_RAID_VICTORY = register("music.raid_victory");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_RAID_DEFEAT = register("music.raid_defeat");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_TITLE = register("music.title");
    // Per-trade contact voices (Employment.soundOf); period/contact ticks unchanged.
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_PLATE_HAMMER = register("work.plate_hammer");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_QUERN_GRIND = register("work.quern_grind");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_MASH_STIR = register("work.mash_stir");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_QUILL_SCRATCH = register("work.quill_scratch");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_PESTLE_GRIND = register("work.pestle_grind");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_SHEAR_SNIP = register("work.shear_snip");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_FISH_SPLASH = register("work.fish_splash");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_BOW_LOOSE = register("work.bow_loose");
    // Archer cycle (anim lane): the string creak on ARCHER_DRAW's pull start and the quiver rustle on
    // ARCHER_RELOAD's grip. Vanilla stand-ins in sounds.json until the sound lane records its own.
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_BOW_DRAW = register("work.bow_draw");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_QUIVER_RUSTLE = register("work.quiver_rustle");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_LEDGER_TALLY = register("work.ledger_tally");
    public static final DeferredHolder<SoundEvent, SoundEvent> WORK_BAR_WIPE = register("work.bar_wipe");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
            () -> SoundEvent.createVariableRangeEvent(Hearthstead.id(name)));
    }

    public static void register(IEventBus bus) {
        SOUND_EVENTS.register(bus);
    }

    private ModSounds() {
    }
}

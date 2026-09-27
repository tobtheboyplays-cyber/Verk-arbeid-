package com.hearthstead;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-world server config ({@code serverconfig/hearthstead-server.toml}).
 * Server-authoritative: clients never read these values.
 */
public final class HearthsteadServerConfig {
    public static final double DEFAULT_DAY_LENGTH_MULTIPLIER = 2.0D;
    public static final double MIN_DAY_LENGTH_MULTIPLIER = 1.0D;
    public static final double MAX_DAY_LENGTH_MULTIPLIER = 4.0D;

    public static final ModConfigSpec SPEC;
    public static final int DEFAULT_FIRST_RAID_AUTO_DAYS = 3;
    public static final int DEFAULT_FIRST_RAID_MINIMAL_SIZE = 3;
    public static final int DEFAULT_PRE_FIRST_RAID_MEAL_RESERVE = 16;
    public static final ModConfigSpec.IntValue FIRST_RAID_AUTO_DAYS;
    public static final ModConfigSpec.IntValue FIRST_RAID_MINIMAL_SIZE;
    public static final ModConfigSpec.IntValue PRE_FIRST_RAID_MEAL_RESERVE;
    // Co-op "downed / revive your comrade" (revive agent, 26 Sep).
    public static final boolean DEFAULT_REVIVE_ENABLED = true;
    public static final boolean DEFAULT_REVIVE_SOLO = false;
    public static final int DEFAULT_REVIVE_BLEED_OUT_SECONDS = 50;
    public static final double DEFAULT_REVIVE_SECONDS = 3.0D;
    public static final int DEFAULT_REVIVE_HEALTH_PERCENT = 30;
    public static final int DEFAULT_REVIVE_GRACE_SECONDS = 60;
    public static final double DEFAULT_REVIVE_FINISHER_CHANCE = 0.12D;
    public static final ModConfigSpec.BooleanValue REVIVE_ENABLED;
    public static final ModConfigSpec.BooleanValue REVIVE_SOLO;
    public static final ModConfigSpec.IntValue REVIVE_BLEED_OUT_SECONDS;
    public static final ModConfigSpec.DoubleValue REVIVE_SECONDS;
    public static final ModConfigSpec.IntValue REVIVE_HEALTH_PERCENT;
    public static final ModConfigSpec.IntValue REVIVE_GRACE_SECONDS;
    public static final ModConfigSpec.DoubleValue REVIVE_FINISHER_CHANCE;
    public static final ModConfigSpec.DoubleValue DAY_LENGTH_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue WORKER_WATCHDOG;

    // Hunter rework: hunting grounds + butchery. Defaults are mirrored as
    // constants so the game (and GameTests) behave identically before the
    // world's server config has loaded.
    public static final boolean DEFAULT_HUNTING_GROUNDS = true;
    public static final int DEFAULT_HUNTING_GAME_CAP = 8;
    public static final int DEFAULT_HUNTING_SPAWN_INTERVAL_SECONDS = 150;
    public static final int DEFAULT_HUNTING_MIN_PLAYER_DISTANCE = 24;
    public static final int DEFAULT_HUNTING_VILLAGE_CORE_RADIUS = 16;
    public static final int DEFAULT_BUTCHER_TABLE_SECONDS = 5;
    public static final int DEFAULT_FIELD_DRESSING_SECONDS = 12;
    public static final ModConfigSpec.BooleanValue HUNTING_GROUNDS;
    public static final ModConfigSpec.IntValue HUNTING_GAME_CAP;
    public static final ModConfigSpec.IntValue HUNTING_SPAWN_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue HUNTING_MIN_PLAYER_DISTANCE;
    public static final ModConfigSpec.IntValue HUNTING_VILLAGE_CORE_RADIUS;
    public static final ModConfigSpec.IntValue BUTCHER_TABLE_SECONDS;
    public static final ModConfigSpec.IntValue FIELD_DRESSING_SECONDS;

    // Recurring raid cadence: after a raid ends, the next one comes on a day
    // drawn once from [min, max] (defaults 3..4). Defaults live in
    // RaidCadence so tests and a not-yet-loaded config behave identically.
    public static final ModConfigSpec.IntValue RECURRING_RAID_MIN_DAYS;
    public static final ModConfigSpec.IntValue RECURRING_RAID_MAX_DAYS;

    // Enemy health (owner request 26 Sep: longer fights). Defaults live in
    // RaiderEntity so tests and a not-yet-loaded config agree.
    public static final ModConfigSpec.DoubleValue SKIRMISHER_BASE_HEALTH;
    public static final ModConfigSpec.DoubleValue BRUTE_BASE_HEALTH;
    public static final ModConfigSpec.DoubleValue GOBLIN_THIEF_BASE_HEALTH;
    public static final ModConfigSpec.DoubleValue BANDIT_BASE_HEALTH;
    public static final ModConfigSpec.DoubleValue ENEMY_HEALTH_MULTIPLIER;

    // Logistics bonuses (settlement/development/HaulGear). Defaults mirror
    // PostRaidUpgrade so tests and a not-yet-loaded config agree.
    public static final ModConfigSpec.IntValue HAND_CART_PERCENT;
    public static final ModConfigSpec.IntValue HAND_CART_ROUGH_PERCENT;
    /** [builder] skipUnsuppliedDecor: leave out decoration nobody in the village can make. */
    public static final ModConfigSpec.BooleanValue SKIP_UNSUPPLIED_DECOR;
    /** [builder] lastResortReach: how far he may stretch once everything else failed (0 = off). */
    public static final ModConfigSpec.IntValue LAST_RESORT_REACH;
    /** [settlement] radius: the claim of a NEWLY founded settlement. */
    public static final ModConfigSpec.IntValue SETTLEMENT_RADIUS;
    public static final int DEFAULT_SETTLEMENT_RADIUS = 72;
    public static final int MIN_SETTLEMENT_RADIUS = 32;
    /** EarlyCoinMerchant refuses R > 80; settler long routes stop at 96 blocks. */
    public static final int MAX_SETTLEMENT_RADIUS = 80;
    public static final ModConfigSpec.BooleanValue COURIER_BATCHING;
    public static final ModConfigSpec.IntValue PAVED_ROADS_PERCENT;

    // Guard/raider melee moveset (entity/combat/GuardMove, RaiderMove).
    // Defaults mirror the enum data so GameTests and JUnit behave identically
    // before a world's server config has loaded.
    public static final double DEFAULT_GUARD_LIGHT_DAMAGE_MULTIPLIER = 1.0D;
    public static final double DEFAULT_GUARD_HEAVY_DAMAGE_MULTIPLIER = 1.8D;
    public static final double DEFAULT_GUARD_FINISHER_DAMAGE_MULTIPLIER = 1.5D;
    public static final double DEFAULT_GUARD_SHIELD_BASH_DAMAGE_MULTIPLIER = 0.35D;
    public static final int DEFAULT_GUARD_HEAVY_WINDUP_TICKS = 11;
    public static final int DEFAULT_GUARD_LIGHT_COOLDOWN_TICKS = 10;
    public static final int DEFAULT_GUARD_HEAVY_COOLDOWN_TICKS = 14;
    public static final int DEFAULT_GUARD_FINISHER_COOLDOWN_TICKS = 20;
    public static final int DEFAULT_GUARD_SHIELD_BASH_COOLDOWN_TICKS = 80;
    public static final int DEFAULT_GUARD_COMBO_WINDOW_TICKS = 6;
    public static final double DEFAULT_RAIDER_LIGHT_DAMAGE_MULTIPLIER = 1.0D;
    public static final double DEFAULT_RAIDER_HEAVY_DAMAGE_MULTIPLIER = 2.4D;
    public static final int DEFAULT_RAIDER_HEAVY_WINDUP_TICKS = 24;
    public static final int DEFAULT_RAIDER_HEAVY_COOLDOWN_TICKS = 12;
    public static final double DEFAULT_BRUTE_HEAVY_CHANCE = 0.70D;
    public static final double DEFAULT_BRUTE_CLUB_DAMAGE_MULTIPLIER = 1.4D;
    public static final double DEFAULT_BRUTE_SLAM_RADIUS_SCALE = 1.0D;
    public static final ModConfigSpec.DoubleValue GUARD_LIGHT_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue GUARD_HEAVY_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue GUARD_FINISHER_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue GUARD_SHIELD_BASH_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.IntValue GUARD_HEAVY_WINDUP_TICKS;
    public static final ModConfigSpec.IntValue GUARD_LIGHT_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue GUARD_HEAVY_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue GUARD_FINISHER_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue GUARD_SHIELD_BASH_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue GUARD_COMBO_WINDOW_TICKS;
    public static final ModConfigSpec.DoubleValue RAIDER_LIGHT_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue RAIDER_HEAVY_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.IntValue RAIDER_HEAVY_WINDUP_TICKS;
    public static final ModConfigSpec.IntValue RAIDER_HEAVY_COOLDOWN_TICKS;
    public static final ModConfigSpec.DoubleValue BRUTE_HEAVY_CHANCE;
    public static final ModConfigSpec.DoubleValue BRUTE_CLUB_DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue BRUTE_SLAM_RADIUS_SCALE;
    // Player / guard executions ("Finish him"), com.hearthstead.finisher.
    public static final boolean DEFAULT_FINISHER_ENABLED = true;
    public static final double DEFAULT_FINISHER_GUARD_CHANCE = 0.35D;
    public static final double DEFAULT_FINISHER_REACH = 3.0D;
    public static final ModConfigSpec.BooleanValue FINISHER_ENABLED;
    public static final ModConfigSpec.DoubleValue FINISHER_GUARD_CHANCE;
    public static final ModConfigSpec.DoubleValue FINISHER_REACH;
    // Sunday-build kill-switches (integration captain, 26 Sep). One per big system;
    // each lane gates its own server entry points on the getter below. Default on.
    public static final ModConfigSpec.BooleanValue BUILDER_ENABLED;
    public static final ModConfigSpec.BooleanValue WORLD_EVENTS_ENABLED;
    public static final ModConfigSpec.BooleanValue BATTLE_ROLES_ENABLED;
    public static final ModConfigSpec.BooleanValue GUARD_COMMANDS_ENABLED;
    public static final ModConfigSpec.BooleanValue WAREHOUSE_LEVELS_ENABLED;
    public static final ModConfigSpec.BooleanValue GEAR_TIERS_ENABLED;
    public static final ModConfigSpec.BooleanValue CHARACTER_SKINS_ENABLED;
    public static final ModConfigSpec.BooleanValue LOGISTICS_UPGRADES_ENABLED;
    public static final ModConfigSpec.BooleanValue CONVERSATIONS_ENABLED;
    // Trades-unlock lane (26 Sep): the four specialization nodes + 15 emblems.
    public static final boolean DEFAULT_EXTENDED_TRADES = true;
    public static final ModConfigSpec.BooleanValue EXTENDED_TRADES_ENABLED;
    public static final ModConfigSpec.BooleanValue LIVING_VILLAGE_ENABLED;
    // Patrol routes lane (26 Sep): player-drawn routes walked by guard squads.
    public static final ModConfigSpec.BooleanValue PATROL_ROUTES_ENABLED;
    // Weapons lane (26 Sep): captain weapon traits, the two-handed guard pose and the settler bow hold.
    public static final ModConfigSpec.BooleanValue CAPTAIN_WEAPONS_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("time");
        DAY_LENGTH_MULTIPLIER = builder
            .comment("How many times longer an Overworld day/night cycle lasts than vanilla.",
                "1.0 = vanilla (20 real minutes), 2.0 = 40 real minutes.",
                "Only ordinary daylight ticks are slowed: sleeping, /time set and",
                "the doDaylightCycle gamerule behave exactly as in vanilla.")
            .defineInRange("dayLengthMultiplier", DEFAULT_DAY_LENGTH_MULTIPLIER,
                MIN_DAY_LENGTH_MULTIPLIER, MAX_DAY_LENGTH_MULTIPLIER);
        builder.pop();
        builder.push("settlement");
        SETTLEMENT_RADIUS = builder
            .comment("Claim radius of a newly founded settlement, in blocks from the Banner (was 48).",
                "It sets the raid ring (radius+8 to radius+24), the defended area, guard patrols,",
                "where the Builder may build and the spacing between two Banners (radius + radius).",
                "A saved settlement keeps the radius it was founded with. 80 is the most the",
                "early merchant and settler long-distance routes support.")
            .defineInRange("radius", DEFAULT_SETTLEMENT_RADIUS, MIN_SETTLEMENT_RADIUS, MAX_SETTLEMENT_RADIUS);
        builder.pop();
        builder.push("builder");
        SKIP_UNSUPPLIED_DECOR = builder
            .comment("The Builder leaves out pure decoration nobody in the village can make (flowers,",
                "leaves, cobwebs, amethyst, carved pumpkins...) and builds coloured beds, wool, carpets",
                "and banners in white, so every building can be finished from village supplies.",
                "Turn off to build every blueprint exactly as drawn (the player then supplies the rest).")
            .define("skipUnsuppliedDecor", true);
        LAST_RESORT_REACH = builder
            .comment("Last resort for a block the Builder could not reach any other way (walking, the",
                "8-block far reach, ladder columns): he may set it from anywhere on the site up to this",
                "many blocks from his eyes, without a clear line of sight -- never through a building",
                "outside the blueprint. Blocks still out of reach are listed as 'needs a hand'. 0 = off.")
            .defineInRange("lastResortReach", 10, 0, 16);
        builder.pop();
        builder.push("debug");
        WORKER_WATCHDOG = builder
            .comment("Read-only worker watchdog: samples every settler once per second and",
                "reports stalls (STUCK, IDLE_IN_WORK, LOOP, PATH_FAIL, ORPHANED_ITEMS) to the",
                "server log and <world>/hearthstead-watchdog/. Never changes behaviour.",
                "Can also be toggled at runtime with /hearthstead watchdog on|off.")
            .define("workerWatchdog", false);
        builder.pop();
        builder.push("hunting");
        HUNTING_GROUNDS = builder
            .comment("While a Hunter is employed at a Hunter's Lodge, wild game slowly",
                "returns to natural open ground inside the Lodge's 28-block hunting radius,",
                "outside the village core and every building, out of players' sight.")
            .define("huntingGrounds", DEFAULT_HUNTING_GROUNDS);
        HUNTING_GAME_CAP = builder
            .comment("Hunting grounds stop spawning once this many wild game animals",
                "(cow, pig, sheep, chicken, rabbit) are alive in a Lodge's hunting radius.")
            .defineInRange("gameCap", DEFAULT_HUNTING_GAME_CAP, 0, 32);
        HUNTING_SPAWN_INTERVAL_SECONDS = builder
            .comment("At most one animal returns per Lodge per this many seconds.")
            .defineInRange("spawnIntervalSeconds", DEFAULT_HUNTING_SPAWN_INTERVAL_SECONDS, 20, 3600);
        HUNTING_MIN_PLAYER_DISTANCE = builder
            .comment("Game never appears closer than this many blocks to any player.")
            .defineInRange("minPlayerDistance", DEFAULT_HUNTING_MIN_PLAYER_DISTANCE, 0, 128);
        HUNTING_VILLAGE_CORE_RADIUS = builder
            .comment("No game spawns within this many blocks of the Banner (the village core).",
                "Every building's bounds (plus a 4-block margin) are always excluded too.")
            .defineInRange("villageCoreRadius", DEFAULT_HUNTING_VILLAGE_CORE_RADIUS, 0, 64);
        BUTCHER_TABLE_SECONDS = builder
            .comment("Seconds a level-1 Hunter spends butchering one carcass at a Butchering Table.",
                "Trade level shortens it like every other work clip (max -18%).")
            .defineInRange("butcherTableSeconds", DEFAULT_BUTCHER_TABLE_SECONDS, 1, 60);
        FIELD_DRESSING_SECONDS = builder
            .comment("Seconds to butcher on the Lodge floor when it has no Butchering Table.")
            .defineInRange("fieldDressingSeconds", DEFAULT_FIELD_DRESSING_SECONDS, 1, 120);
        builder.pop();
        builder.push("raid");
        FIRST_RAID_AUTO_DAYS = builder
            .comment("In-game days after founding before the automatic first raid; a warning comes first.")
            .defineInRange("firstRaidAutoDays", DEFAULT_FIRST_RAID_AUTO_DAYS, 1, 365);
        FIRST_RAID_MINIMAL_SIZE = builder
            .comment("Weak raiders in the first timed raid when the village has no defenders.")
            .defineInRange("firstRaidMinimalSize", DEFAULT_FIRST_RAID_MINIMAL_SIZE, 2, 3);
        PRE_FIRST_RAID_MEAL_RESERVE = builder
            .comment("Maximum ready-meal reserve for recruitment and readiness before the first raid ends.",
                "Afterwards the normal two-day reserve per resident applies.")
            .defineInRange("preFirstRaidMealReserve", DEFAULT_PRE_FIRST_RAID_MEAL_RESERVE, 1, 4096);
        builder.pop();

        builder.push("raids");
        RECURRING_RAID_MIN_DAYS = builder
            .comment("After the first raid, recurring raids come every few in-game days.",
                "When a raid ends, the next one is set for a day drawn once between",
                "recurringRaidMinDays and recurringRaidMaxDays after it (inclusive).",
                "The warning always comes at dusk the evening before the attack.",
                "Default 3..4 days = about 2-2.7 hours of play at 2x day length.",
                "A raid never comes earlier than the minimum; it only comes later",
                "when something blocks it (no player nearby, or the band cannot form up).")
            .defineInRange("recurringRaidMinDays",
                com.hearthstead.settlement.raid.RaidCadence.DEFAULT_MIN_DAYS,
                com.hearthstead.settlement.raid.RaidCadence.MIN_ALLOWED_DAYS,
                com.hearthstead.settlement.raid.RaidCadence.MAX_ALLOWED_DAYS);
        RECURRING_RAID_MAX_DAYS = builder
            .comment("Latest day after the previous raid on which the next raid is set.",
                "Values below recurringRaidMinDays are treated as equal to it.")
            .defineInRange("recurringRaidMaxDays",
                com.hearthstead.settlement.raid.RaidCadence.DEFAULT_MAX_DAYS,
                com.hearthstead.settlement.raid.RaidCadence.MIN_ALLOWED_DAYS,
                com.hearthstead.settlement.raid.RaidCadence.MAX_ALLOWED_DAYS);
        SKIRMISHER_BASE_HEALTH = builder
            .comment("Health of an ordinary raid Skirmisher before menace, captain and the",
                "multiplier below. Default: 3 light slashes from a new Guard with an",
                "iron sword, or about 5 hits with a player's iron sword.",
                "Takes effect for raiders armed after the change.")
            .defineInRange("skirmisherBaseHealth",
                com.hearthstead.entity.RaiderEntity.SKIRMISHER_MAX_HEALTH, 4.0D, 1024.0D);
        BRUTE_BASE_HEALTH = builder
            .comment("Health of an ordinary raid Brute before menace, captain and the",
                "multiplier below. Default: about 5 light slashes from a new Guard with an",
                "iron sword (including the Guard's 25% bonus against Brutes), or about 12",
                "hits with a player's iron sword, so several defenders should focus it.")
            .defineInRange("bruteBaseHealth",
                com.hearthstead.entity.RaiderEntity.BRUTE_MAX_HEALTH, 4.0D, 2048.0D);
        BANDIT_BASE_HEALTH = builder
            .comment("Health of a Bandit, the human outlaw of the early raids (raid 1 is",
                "bandits only). Before the multiplier below.")
            .defineInRange("banditBaseHealth",
                com.hearthstead.entity.RaiderEntity.BANDIT_MAX_HEALTH, 2.0D, 512.0D);
        GOBLIN_THIEF_BASE_HEALTH = builder
            .comment("Health of a Goblin Thief (pickpocket). It runs rather than fights,",
                "so it only needs to survive a few blows during the chase.")
            .defineInRange("goblinThiefBaseHealth",
                com.hearthstead.entity.RaiderEntity.GOBLIN_THIEF_MAX_HEALTH, 2.0D, 512.0D);
        ENEMY_HEALTH_MULTIPLIER = builder
            .comment("Multiplies the health of every raider, captain bonus and Goblin Thief.",
                "1.0 = the defaults above; 0.5 halves fight length, 2.0 doubles it.")
            .defineInRange("enemyHealthMultiplier", 1.0D, 0.1D, 10.0D);
        builder.pop();
        builder.push("combat");
        GUARD_LIGHT_DAMAGE_MULTIPLIER = builder
            .comment("Guard light slash (and the two light combo links): multiplier on the",
                "guard's normal attack damage (sword, rank edge, training, Brute counter).")
            .defineInRange("guardLightDamageMultiplier",
                DEFAULT_GUARD_LIGHT_DAMAGE_MULTIPLIER, 0.1D, 5.0D);
        GUARD_HEAVY_DAMAGE_MULTIPLIER = builder
            .comment("Guard heavy overhead chop damage multiplier. The heavy also staggers.")
            .defineInRange("guardHeavyDamageMultiplier",
                DEFAULT_GUARD_HEAVY_DAMAGE_MULTIPLIER, 0.1D, 5.0D);
        GUARD_FINISHER_DAMAGE_MULTIPLIER = builder
            .comment("Guard combo finisher (third link of light, light, finisher).")
            .defineInRange("guardFinisherDamageMultiplier",
                DEFAULT_GUARD_FINISHER_DAMAGE_MULTIPLIER, 0.1D, 5.0D);
        GUARD_SHIELD_BASH_DAMAGE_MULTIPLIER = builder
            .comment("Guard shield bash damage multiplier (it mostly exists to interrupt).")
            .defineInRange("guardShieldBashDamageMultiplier",
                DEFAULT_GUARD_SHIELD_BASH_DAMAGE_MULTIPLIER, 0.0D, 5.0D);
        GUARD_HEAVY_WINDUP_TICKS = builder
            .comment("Ticks from the start of a guard heavy to its contact (20 ticks = 1 s).",
                "The authored animation lands its impact at 11; other values keep the",
                "server authoritative but the clip's impact frame will no longer line up.")
            .defineInRange("guardHeavyWindupTicks", DEFAULT_GUARD_HEAVY_WINDUP_TICKS, 8, 16);
        GUARD_LIGHT_COOLDOWN_TICKS = builder
            .comment("Ticks after a single light slash ends before the next opener.",
                "Default keeps the historical 20-tick guard cadence (10 swing + 10 rest).")
            .defineInRange("guardLightCooldownTicks", DEFAULT_GUARD_LIGHT_COOLDOWN_TICKS, 0, 100);
        GUARD_HEAVY_COOLDOWN_TICKS = builder
            .comment("Ticks after a heavy ends before the next opener.")
            .defineInRange("guardHeavyCooldownTicks", DEFAULT_GUARD_HEAVY_COOLDOWN_TICKS, 0, 100);
        GUARD_FINISHER_COOLDOWN_TICKS = builder
            .comment("Ticks after a combo finisher ends before the next opener.")
            .defineInRange("guardFinisherCooldownTicks",
                DEFAULT_GUARD_FINISHER_COOLDOWN_TICKS, 0, 100);
        GUARD_SHIELD_BASH_COOLDOWN_TICKS = builder
            .comment("Ticks between two shield bashes by the same guard.")
            .defineInRange("guardShieldBashCooldownTicks",
                DEFAULT_GUARD_SHIELD_BASH_COOLDOWN_TICKS, 20, 400);
        GUARD_COMBO_WINDOW_TICKS = builder
            .comment("After a landed combo link, ticks the guard has to bring the target",
                "back into reach before the combo breaks.")
            .defineInRange("guardComboWindowTicks", DEFAULT_GUARD_COMBO_WINDOW_TICKS, 2, 20);
        RAIDER_LIGHT_DAMAGE_MULTIPLIER = builder
            .comment("Raider light jab damage multiplier.")
            .defineInRange("raiderLightDamageMultiplier",
                DEFAULT_RAIDER_LIGHT_DAMAGE_MULTIPLIER, 0.1D, 5.0D);
        RAIDER_HEAVY_DAMAGE_MULTIPLIER = builder
            .comment("Raider heavy smash damage multiplier (about 2.4x the light jab).",
                "A heavy staggers a guard, even through a raised shield (halved).")
            .defineInRange("raiderHeavyDamageMultiplier",
                DEFAULT_RAIDER_HEAVY_DAMAGE_MULTIPLIER, 0.1D, 5.0D);
        RAIDER_HEAVY_WINDUP_TICKS = builder
            .comment("Ticks from the start of a raider heavy to its contact (authored at 24).")
            .defineInRange("raiderHeavyWindupTicks", DEFAULT_RAIDER_HEAVY_WINDUP_TICKS, 12, 30);
        RAIDER_HEAVY_COOLDOWN_TICKS = builder
            .comment("Ticks after a raider heavy ends before its next swing.")
            .defineInRange("raiderHeavyCooldownTicks",
                DEFAULT_RAIDER_HEAVY_COOLDOWN_TICKS, 0, 100);
        BRUTE_HEAVY_CHANCE = builder
            .comment("Chance a Brute raider swings its heavy instead of its quick club.")
            .defineInRange("bruteHeavyChance", DEFAULT_BRUTE_HEAVY_CHANCE, 0.0D, 1.0D);
        BRUTE_CLUB_DAMAGE_MULTIPLIER = builder
            .comment("Brute club slam damage multiplier (its quicker crushing blow).")
            .defineInRange("bruteClubDamageMultiplier",
                DEFAULT_BRUTE_CLUB_DAMAGE_MULTIPLIER, 0.1D, 5.0D);
        BRUTE_SLAM_RADIUS_SCALE = builder
            .comment("Scales the Brute ground-slam shockwave radius (club 2.5, heavy 3.0 blocks).",
                "Defenders inside it are pushed away and briefly staggered; raiders never.")
            .defineInRange("bruteSlamRadiusScale", DEFAULT_BRUTE_SLAM_RADIUS_SCALE, 0.5D, 2.0D);
        builder.pop();
        builder.push("logistics");
        HAND_CART_PERCENT = builder
            .comment("Hand Cart: percent of the base Courier trip budget (8) added on top of the sack tier.")
            .defineInRange("handCartPercent",
                com.hearthstead.settlement.development.PostRaidUpgrade.HAND_CART_PERCENT, 0, 600);
        HAND_CART_ROUGH_PERCENT = builder
            .comment("Hand Cart: walking speed penalty percent off-road while the cart is hitched.")
            .defineInRange("handCartRoughPercent",
                com.hearthstead.settlement.development.PostRaidUpgrade.HAND_CART_ROUGH_PERCENT, 0, 50);
        PAVED_ROADS_PERCENT = builder
            .comment("Paved Roads: walking speed bonus percent on road blocks (tag hearthstead:roads).")
            .defineInRange("pavedRoadsPercent",
                com.hearthstead.settlement.development.PostRaidUpgrade.PAVED_ROADS_PERCENT, 0, 50);
        COURIER_BATCHING = builder
            .comment("Stout Straps pickup batching: a Courier with room left may take a second request",
                "whose pickup is within 16 blocks, and deliver both in one trip. Off = one request per",
                "trip, exactly as before; trips already under way still finish. Safety switch, no rebuild.")
            .define("courierBatching", true);
        builder.pop();
        builder.push("revive");
        REVIVE_ENABLED = builder
            .comment("Co-op revive: during a raid at your settlement (and a short grace after it),",
                "a lethal hit knocks you DOWN instead of killing you, as long as another player",
                "is online and near the settlement. A teammate holds [use] on you to revive you;",
                "sneak + [use] drags you. /kill, the void, creative and spectator are never affected.")
            .define("enabled", DEFAULT_REVIVE_ENABLED);
        REVIVE_SOLO = builder
            .comment("Also go down when no other player is near (you can then only bleed out,",
                "be finished off, or be helped by a future Infirmary). Default off: solo players die normally.")
            .define("soloDowned", DEFAULT_REVIVE_SOLO);
        REVIVE_BLEED_OUT_SECONDS = builder
            .comment("Seconds a downed player lasts before bleeding out (normal death, normal drops).",
                "The timer pauses while a teammate is reviving.")
            .defineInRange("bleedOutSeconds", DEFAULT_REVIVE_BLEED_OUT_SECONDS, 10, 300);
        REVIVE_SECONDS = builder
            .comment("Seconds a teammate must hold [use] on a downed player. Taking damage resets it.")
            .defineInRange("reviveSeconds", DEFAULT_REVIVE_SECONDS, 0.5D, 15.0D);
        REVIVE_HEALTH_PERCENT = builder
            .comment("Health a revived player gets up with, in percent of max health.",
                "They also get a few seconds of Resistance II.")
            .defineInRange("reviveHealthPercent", DEFAULT_REVIVE_HEALTH_PERCENT, 1, 100);
        REVIVE_GRACE_SECONDS = builder
            .comment("Seconds after a raid ends during which players still go down instead of dying",
                "(stragglers and the last fight). 0 = only during the raid itself.")
            .defineInRange("postRaidGraceSeconds", DEFAULT_REVIVE_GRACE_SECONDS, 0, 600);
        REVIVE_FINISHER_CHANCE = builder
            .comment("Chance (every 5 s, per downed player) that a nearby raider breaks off to",
                "finish the downed player. Other enemies ignore downed players. 0 = never.")
            .defineInRange("raiderFinisherChance", DEFAULT_REVIVE_FINISHER_CHANCE, 0.0D, 1.0D);
        builder.pop();
        builder.push("finisher");
        FINISHER_ENABLED = builder
            .comment("Executions: a staggered or badly hurt enemy glows red on the torso;",
                "look at it within reach and press the melee command key (R) to finish it.")
            .define("enabled", DEFAULT_FINISHER_ENABLED);
        FINISHER_GUARD_CHANCE = builder
            .comment("Chance a guard's finishing drive on a finishable raider becomes a full execution",
                "(rolled once per finish window; players always can).")
            .defineInRange("guardFinisherChance", DEFAULT_FINISHER_GUARD_CHANCE, 0.0D, 1.0D);
        FINISHER_REACH = builder
            .comment("Blocks from the player to the edge of the enemy's body a finisher can start from.")
            .defineInRange("reach", DEFAULT_FINISHER_REACH, 2.0D, 5.0D);
        builder.pop();
        builder.comment("Kill-switches for the big systems. false = the system stays inert",
            "(no new jobs/events/upgrades start); saved data is kept, never deleted.")
            .push("features");
        BUILDER_ENABLED = builder
            .comment("Builder settlers, build orders and the Builder's Plan.")
            .define("builder", true);
        WORLD_EVENTS_ENABLED = builder
            .comment("Random world events (caravans, visitors, disasters and the like).")
            .define("worldEvents", true);
        BATTLE_ROLES_ENABLED = builder
            .comment("Battle roles for guards (spearman, longsword, healer, rune mage).")
            .define("battleRoles", true);
        GUARD_COMMANDS_ENABLED = builder
            .comment("The guard command menu (rally / hold / follow orders).")
            .define("guardCommands", true);
        WAREHOUSE_LEVELS_ENABLED = builder
            .comment("Warehouse levels, sorting and the warehouse index.")
            .define("warehouseLevels", true);
        GEAR_TIERS_ENABLED = builder
            .comment("Settler gear tiers and tiered armour visuals.")
            .define("gearTiers", true);
        CHARACTER_SKINS_ENABLED = builder
            .comment("Character looks: genome faces for every settler, visitor costumes,",
                "raider variants, saga-captain looks and 3D accessories. Off = the old skins.")
            .define("characterSkins", true);
        LOGISTICS_UPGRADES_ENABLED = builder
            .comment("Sack tiers, hand carts and road speed bonuses.")
            .define("logisticsUpgrades", true);
        CONVERSATIONS_ENABLED = builder
            .comment("Bannerlord-style conversations with visitors and raid captains",
                "(camera focus, relations, persuasion, barter, raid parley).")
            .define("conversations", true);
        EXTENDED_TRADES_ENABLED = builder
            .comment("Controls unlocking and shop access only, for the four specialization",
                "tech nodes (Fortification, Land and Harvest, Craft and Industry, Hall and",
                "Learning) and the Mayor's 15 emblem offers (Miller, Herder, Baker, Butcher,",
                "Miner, Carpenter, Mason, Smelter, Smith, Tanner, Weaver, Cook, Brewer,",
                "Armourer, Fletcher).",
                "false = the 4 nodes and the 15 emblem offers are hidden. Knowledge already",
                "learned, workers already in those trades and emblems already held keep",
                "working; nothing is deleted.")
            .define("extendedTrades", DEFAULT_EXTENDED_TRADES);
        LIVING_VILLAGE_ENABLED = builder
            .comment("Living village: settlers greet you, say short lines, shelter from rain, cheer",
                "a won raid and mourn the fallen; chimney smoke and field life. Presentation only.")
            .define("livingVillage", true);
        PATROL_ROUTES_ENABLED = builder
            .comment("Patrol routes: waypoints marked with the Patrol Map, walked by squads of 2-4",
                "guards on their watch, shown on the Banner map. false = squads fall back to the",
                "ordinary rounds; routes already drawn are kept, never deleted.")
            .define("patrolRoutes", true);
        CAPTAIN_WEAPONS_ENABLED = builder
            .comment("Captain weapons: the short sword, double axe, halberd and warhammer traits (armour",
                "shred, anti-charge, shield break, stun), the two-handed guard pose and the settler bow",
                "hold. false = the items stay (plain weapons with their base stats); traits are off.")
            .define("captainWeapons", true);
        builder.pop();
        // [events]: small world events (frequency, per-event switches). World-events lane.
        com.hearthstead.event.worldevent.WorldEventConfig.define(builder);
        com.hearthstead.event.worldevent.StoryConfig.define(builder);
        // [captain]: the hero Captain (battle-roles lane, plan/CAPTAIN.md).
        com.hearthstead.entity.combat.captain.CaptainConfig.define(builder);
        // [archers]: height advantage (range + accuracy). Archer fire lane.
        com.hearthstead.entity.ai.ArcherHeightAdvantage.define(builder);
        // [conversations] tuning, owned by the conversation lane.
        com.hearthstead.conversation.ConversationConfig.define(builder);
        // [economy]: crafting pace, crafter self-fetch/upkeep, courier bundles,
        // hunger. Economy lane (plan/ECONOMY.md).
        com.hearthstead.settlement.economy.EconomyConfig.define(builder);
        // [quality]: grades of settler-crafted goods. Quality lane (plan/QUALITY.md).
        com.hearthstead.settlement.economy.QualityConfig.define(builder);
        // [techtree]: v3 tech tree kill switch + study time. Tech tree framework lane.
        com.hearthstead.settlement.techtree.TechTreeConfig.define(builder);
        // [attributes]: global strength of attribute effects. Attributes lane (plan/ATTRIBUTES.md).
        com.hearthstead.entity.AttributeConfig.define(builder);
        // [weapons]: captain weapon trait strength. Weapons lane (plan/WEAPONS.md).
        com.hearthstead.item.weapon.WeaponConfig.define(builder);
        // [livingVillage]: drunkenness switch. Tavern lane.
        com.hearthstead.entity.Drunkenness.define(builder);
        // [start]: the one-time start kit on a player's first join (handbook + bread). Bug-hunt lane.
        com.hearthstead.settlement.StarterKitConfig.define(builder);
        // [chat]: town chat switches, one per kind of line. Settler UI lane.
        com.hearthstead.settlement.TownChatConfig.define(builder);
        // [pathing]: the universal settler stuck watchdog. Anti-stuck lane.
        com.hearthstead.entity.path.PathingConfig.define(builder);
        // [mine]: the Miner's ladder shaft (MINE V2) or the old quarry. Mine V2 lane.
        com.hearthstead.entity.ai.MineConfig.define(builder);
        SPEC = builder.build();
    }

    private HearthsteadServerConfig() {
    }

    /** Safe before the world's server config has loaded (false). */
    public static boolean workerWatchdog() {
        if (!SPEC.isLoaded()) {
            return false;
        }
        try {
            return WORKER_WATCHDOG.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    public static int firstRaidAutoDays() {
        return intOr(FIRST_RAID_AUTO_DAYS, DEFAULT_FIRST_RAID_AUTO_DAYS);
    }

    public static int firstRaidMinimalSize() {
        return intOr(FIRST_RAID_MINIMAL_SIZE, DEFAULT_FIRST_RAID_MINIMAL_SIZE);
    }

    public static int preFirstRaidMealReserve() {
        return intOr(PRE_FIRST_RAID_MEAL_RESERVE, DEFAULT_PRE_FIRST_RAID_MEAL_RESERVE);
    }

    private static int intOr(ModConfigSpec.IntValue value, int fallback) {
        if (!SPEC.isLoaded()) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    public static boolean huntingGrounds() {
        if (!SPEC.isLoaded()) {
            return DEFAULT_HUNTING_GROUNDS;
        }
        try {
            return HUNTING_GROUNDS.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_HUNTING_GROUNDS;
        }
    }

    public static int huntingGameCap() {
        return intOr(HUNTING_GAME_CAP, DEFAULT_HUNTING_GAME_CAP);
    }

    public static int huntingSpawnIntervalTicks() {
        return 20 * intOr(HUNTING_SPAWN_INTERVAL_SECONDS, DEFAULT_HUNTING_SPAWN_INTERVAL_SECONDS);
    }

    public static int huntingMinPlayerDistance() {
        return intOr(HUNTING_MIN_PLAYER_DISTANCE, DEFAULT_HUNTING_MIN_PLAYER_DISTANCE);
    }

    public static int huntingVillageCoreRadius() {
        return intOr(HUNTING_VILLAGE_CORE_RADIUS, DEFAULT_HUNTING_VILLAGE_CORE_RADIUS);
    }

    public static int butcherTableTicks() {
        return 20 * intOr(BUTCHER_TABLE_SECONDS, DEFAULT_BUTCHER_TABLE_SECONDS);
    }

    /**
     * The configured recurring raid window, validated (max below min reads as
     * min). Safe before the world's server config has loaded (3..4).
     */
    public static com.hearthstead.settlement.raid.RaidCadence.Window recurringRaidWindow() {
        return com.hearthstead.settlement.raid.RaidCadence.window(
            intOr(RECURRING_RAID_MIN_DAYS,
                com.hearthstead.settlement.raid.RaidCadence.DEFAULT_MIN_DAYS),
            intOr(RECURRING_RAID_MAX_DAYS,
                com.hearthstead.settlement.raid.RaidCadence.DEFAULT_MAX_DAYS));
    }

    public static int handCartPercent() {
        return intOr(HAND_CART_PERCENT,
            com.hearthstead.settlement.development.PostRaidUpgrade.HAND_CART_PERCENT);
    }

    public static int handCartRoughPercent() {
        return intOr(HAND_CART_ROUGH_PERCENT,
            com.hearthstead.settlement.development.PostRaidUpgrade.HAND_CART_ROUGH_PERCENT);
    }

    /** [builder] lastResortReach (default 10, 0 = off). */
    public static int lastResortReach() {
        return intOr(LAST_RESORT_REACH, 10);
    }

    /** [builder] skipUnsuppliedDecor (default on). */
    public static boolean skipUnsuppliedDecor() {
        return boolOr(SKIP_UNSUPPLIED_DECOR, true);
    }

    /** Claim radius for a settlement founded now ([settlement] radius, default 72). */
    public static int settlementRadius() {
        return Math.max(MIN_SETTLEMENT_RADIUS, Math.min(MAX_SETTLEMENT_RADIUS,
            intOr(SETTLEMENT_RADIUS, DEFAULT_SETTLEMENT_RADIUS)));
    }

    public static boolean courierBatchingEnabled() {
        return boolOr(COURIER_BATCHING, true);
    }

    public static int pavedRoadsPercent() {
        return intOr(PAVED_ROADS_PERCENT,
            com.hearthstead.settlement.development.PostRaidUpgrade.PAVED_ROADS_PERCENT);
    }

    public static int fieldDressingTicks() {
        return 20 * intOr(FIELD_DRESSING_SECONDS, DEFAULT_FIELD_DRESSING_SECONDS);
    }

    /** Safe before the world's server config has loaded (falls back to the default). */
    public static double dayLengthMultiplier() {
        if (!SPEC.isLoaded()) {
            return DEFAULT_DAY_LENGTH_MULTIPLIER;
        }
        try {
            return DAY_LENGTH_MULTIPLIER.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_DAY_LENGTH_MULTIPLIER;
        }
    }

    private static double doubleOr(ModConfigSpec.DoubleValue value, double fallback) {
        if (!SPEC.isLoaded()) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    public static double skirmisherBaseHealth() {
        return doubleOr(SKIRMISHER_BASE_HEALTH,
            com.hearthstead.entity.RaiderEntity.SKIRMISHER_MAX_HEALTH);
    }

    public static double bruteBaseHealth() {
        return doubleOr(BRUTE_BASE_HEALTH, com.hearthstead.entity.RaiderEntity.BRUTE_MAX_HEALTH);
    }

    public static double banditBaseHealth() {
        return doubleOr(BANDIT_BASE_HEALTH, com.hearthstead.entity.RaiderEntity.BANDIT_MAX_HEALTH);
    }

    public static double goblinThiefBaseHealth() {
        return doubleOr(GOBLIN_THIEF_BASE_HEALTH,
            com.hearthstead.entity.RaiderEntity.GOBLIN_THIEF_MAX_HEALTH);
    }

    public static double enemyHealthMultiplier() {
        return doubleOr(ENEMY_HEALTH_MULTIPLIER, 1.0D);
    }

    public static double guardLightDamageMultiplier() {
        return doubleOr(GUARD_LIGHT_DAMAGE_MULTIPLIER, DEFAULT_GUARD_LIGHT_DAMAGE_MULTIPLIER);
    }

    public static double guardHeavyDamageMultiplier() {
        return doubleOr(GUARD_HEAVY_DAMAGE_MULTIPLIER, DEFAULT_GUARD_HEAVY_DAMAGE_MULTIPLIER);
    }

    public static double guardFinisherDamageMultiplier() {
        return doubleOr(GUARD_FINISHER_DAMAGE_MULTIPLIER,
            DEFAULT_GUARD_FINISHER_DAMAGE_MULTIPLIER);
    }

    public static double guardShieldBashDamageMultiplier() {
        return doubleOr(GUARD_SHIELD_BASH_DAMAGE_MULTIPLIER,
            DEFAULT_GUARD_SHIELD_BASH_DAMAGE_MULTIPLIER);
    }

    public static int guardHeavyWindupTicks() {
        return intOr(GUARD_HEAVY_WINDUP_TICKS, DEFAULT_GUARD_HEAVY_WINDUP_TICKS);
    }

    public static int guardLightCooldownTicks() {
        return intOr(GUARD_LIGHT_COOLDOWN_TICKS, DEFAULT_GUARD_LIGHT_COOLDOWN_TICKS);
    }

    public static int guardHeavyCooldownTicks() {
        return intOr(GUARD_HEAVY_COOLDOWN_TICKS, DEFAULT_GUARD_HEAVY_COOLDOWN_TICKS);
    }

    public static int guardFinisherCooldownTicks() {
        return intOr(GUARD_FINISHER_COOLDOWN_TICKS, DEFAULT_GUARD_FINISHER_COOLDOWN_TICKS);
    }

    public static int guardShieldBashCooldownTicks() {
        return intOr(GUARD_SHIELD_BASH_COOLDOWN_TICKS, DEFAULT_GUARD_SHIELD_BASH_COOLDOWN_TICKS);
    }

    public static int guardComboWindowTicks() {
        return intOr(GUARD_COMBO_WINDOW_TICKS, DEFAULT_GUARD_COMBO_WINDOW_TICKS);
    }

    public static double raiderLightDamageMultiplier() {
        return doubleOr(RAIDER_LIGHT_DAMAGE_MULTIPLIER, DEFAULT_RAIDER_LIGHT_DAMAGE_MULTIPLIER);
    }

    public static double raiderHeavyDamageMultiplier() {
        return doubleOr(RAIDER_HEAVY_DAMAGE_MULTIPLIER, DEFAULT_RAIDER_HEAVY_DAMAGE_MULTIPLIER);
    }

    public static int raiderHeavyWindupTicks() {
        return intOr(RAIDER_HEAVY_WINDUP_TICKS, DEFAULT_RAIDER_HEAVY_WINDUP_TICKS);
    }

    public static int raiderHeavyCooldownTicks() {
        return intOr(RAIDER_HEAVY_COOLDOWN_TICKS, DEFAULT_RAIDER_HEAVY_COOLDOWN_TICKS);
    }

    public static double bruteHeavyChance() {
        return doubleOr(BRUTE_HEAVY_CHANCE, DEFAULT_BRUTE_HEAVY_CHANCE);
    }

    public static double bruteClubDamageMultiplier() {
        return doubleOr(BRUTE_CLUB_DAMAGE_MULTIPLIER, DEFAULT_BRUTE_CLUB_DAMAGE_MULTIPLIER);
    }

    public static double bruteSlamRadiusScale() {
        return doubleOr(BRUTE_SLAM_RADIUS_SCALE, DEFAULT_BRUTE_SLAM_RADIUS_SCALE);
    }

    private static boolean boolOr(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (!SPEC.isLoaded()) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    public static boolean reviveEnabled() {
        return boolOr(REVIVE_ENABLED, DEFAULT_REVIVE_ENABLED);
    }

    public static boolean reviveSolo() {
        return boolOr(REVIVE_SOLO, DEFAULT_REVIVE_SOLO);
    }

    public static int reviveBleedOutSeconds() {
        return intOr(REVIVE_BLEED_OUT_SECONDS, DEFAULT_REVIVE_BLEED_OUT_SECONDS);
    }

    public static double reviveSeconds() {
        return doubleOr(REVIVE_SECONDS, DEFAULT_REVIVE_SECONDS);
    }

    public static int reviveHealthPercent() {
        return intOr(REVIVE_HEALTH_PERCENT, DEFAULT_REVIVE_HEALTH_PERCENT);
    }

    public static int reviveGraceSeconds() {
        return intOr(REVIVE_GRACE_SECONDS, DEFAULT_REVIVE_GRACE_SECONDS);
    }

    public static double reviveFinisherChance() {
        return doubleOr(REVIVE_FINISHER_CHANCE, DEFAULT_REVIVE_FINISHER_CHANCE);
    }

    public static boolean finisherEnabled() {
        return boolOr(FINISHER_ENABLED, DEFAULT_FINISHER_ENABLED);
    }

    public static double finisherGuardChance() {
        return doubleOr(FINISHER_GUARD_CHANCE, DEFAULT_FINISHER_GUARD_CHANCE);
    }

    public static double finisherReach() {
        return doubleOr(FINISHER_REACH, DEFAULT_FINISHER_REACH);
    }

    public static boolean builderEnabled() {
        return boolOr(BUILDER_ENABLED, true);
    }

    public static boolean worldEventsEnabled() {
        return boolOr(WORLD_EVENTS_ENABLED, true);
    }

    public static boolean battleRolesEnabled() {
        return boolOr(BATTLE_ROLES_ENABLED, true);
    }

    public static boolean guardCommandsEnabled() {
        return boolOr(GUARD_COMMANDS_ENABLED, true);
    }

    public static boolean warehouseLevelsEnabled() {
        return boolOr(WAREHOUSE_LEVELS_ENABLED, true);
    }

    public static boolean characterSkinsEnabled() {
        return boolOr(CHARACTER_SKINS_ENABLED, true);
    }

    public static boolean gearTiersEnabled() {
        return boolOr(GEAR_TIERS_ENABLED, true);
    }

    public static boolean logisticsUpgradesEnabled() {
        return boolOr(LOGISTICS_UPGRADES_ENABLED, true);
    }

    public static boolean conversationsEnabled() {
        return boolOr(CONVERSATIONS_ENABLED, true);
    }

    /** Safe before the world's server config has loaded (default true). */
    public static boolean extendedTradesEnabled() {
        return boolOr(EXTENDED_TRADES_ENABLED, DEFAULT_EXTENDED_TRADES);
    }

    /** Living-village ambient life; safe before the world's server config has loaded (true). */
    public static boolean livingVillageEnabled() {
        return boolOr(LIVING_VILLAGE_ENABLED, true);
    }

    /** Patrol routes; safe before the world's server config has loaded (true). */
    public static boolean patrolRoutesEnabled() {
        return boolOr(PATROL_ROUTES_ENABLED, true);
    }

    /** Captain weapon traits; safe before the world's server config has loaded (true). */
    public static boolean captainWeaponsEnabled() {
        return boolOr(CAPTAIN_WEAPONS_ENABLED, true);
    }
}

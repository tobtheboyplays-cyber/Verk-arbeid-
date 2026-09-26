package com.hearthstead.entity;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Trade skill levels: a worker gets better at the job they actually do.
 *
 * <h2>Model (TekTopia's "skill by doing" + MineColonies' primary/secondary)</h2>
 *
 * <p>Every employed profession keeps its own XP track per settler (a farmer
 * who becomes a courier starts the courier track at level 1 and keeps the
 * farmer XP for the day they return). XP is awarded ONLY by
 * {@link #completeUnit} at the exact-once completion points where the work
 * goals already credit the job (a log felled and banked, a crop harvested,
 * a stack filed, a meal received by the guest, a fish landed, a batch
 * produced, a block mined, a research session, a forage, a shear/feed/cull).
 * Idling, walking and timers never award XP.
 *
 * <p>Each unit also trains the job's {@link JobAttributeProfile} PRIMARY
 * attribute (first CORE slot, the green frame on the settler sheet) at full
 * rate and the SECONDARY (second CORE slot, gold frame) at half rate -- but
 * only the ones the call site did not already train for that same unit, so
 * nothing is double counted.
 *
 * <h2>Curve</h2>
 *
 * <p>XP per unit equals the effort-pool cost of that unit (1 for most, 2 for
 * a crafted batch / mined block / cull, 6 for a research session), so every
 * trade earns about one day-pool of XP per steady in-game day
 * ({@link Effort#BASE_CAPACITY} 20 + Stamina/5, i.e. ~25 XP/day).
 * Cumulative XP to REACH level L is {@code 12(L-1)^2 + 13(L-1)}:
 *
 * <pre>
 * level        1   2   3    4    5    6    7    8    9    10
 * total XP     0  25  74  147  244  365  510  679  872  1089
 * step XP      -  25  49   73   97  121  145  169  193  217
 * ~days @25/d  0   1   3    6   10   15   20   27   35    44
 * </pre>
 *
 * <p>XP is clamped to {@link #MAX_XP}; the map only ever holds employed
 * professions, so the persisted shape is bounded.
 *
 * <h2>Effects (level 1 == exactly the old behaviour)</h2>
 *
 * <ul>
 *   <li><b>Primary -> work speed.</b> {@code 2% x (level-1)}, scaled by the
 *       primary attribute ({@code 0.5 + 0.5 x min(primary,50)/50}), capped at
 *       {@link #MAX_SPEED_BONUS} 18%. Applied only to waits BETWEEN clips
 *       ({@link #shortenWait}) or in whole clip loops ({@link #shortenLooped}),
 *       never inside a clip, so contact frames and sound keyframes do not move.
 *   <li><b>Secondary -> side bonus</b> from level 5 only:
 *       {@code 1% x (level-4) + secondary/20 %}, capped at
 *       {@link #MAX_SIDE_CHANCE} 8%. Used where it conserves items (e.g. a
 *       chance that one landed fish does not wear the rod).
 * </ul>
 *
 * <h2>Wired per trade (everything else is XP only)</h2>
 *
 * <pre>
 * trade      primary -> effect                    secondary -> effect (lv5+, cap 8%)
 * crafters   craft time, whole clip loops         -
 * lumberer   pause between logs                   -
 * fisher     -                                    rod spared on a catch
 * farmer     DEX: field re-survey pause           STA: +1 seed on a harvest whose
 *                                                  vanilla loot already dropped it
 * miner      STR: pause between blocks            -
 * herder     SPI: pause between flock rounds      -
 * hunter     PER: pause between hunts,            DEX: bow spared on a shot;
 *            butchering time at the table         +1 meat on a table-butchered carcass
 *                                                  whose vanilla loot already had meat
 * innkeeper  PRE: meal preparation wait           SPI: served guest +1 morale
 * courier    STR: +1 carry per 3 levels (max +3)  STA: walking pace while working
 * scholar    WITS: +1% research progress per point above 15 (cap 25%, lv2+)
 * </pre>
 */
public final class SkillLevels {

    public static final int MAX_LEVEL = 10;
    /** Cumulative XP to reach each level, index = level. */
    private static final int[] THRESHOLD = new int[MAX_LEVEL + 1];
    public static final int MAX_XP;
    public static final double MAX_SPEED_BONUS = 0.18D;
    public static final double MAX_SIDE_CHANCE = 0.08D;
    public static final String NBT_KEY = "TradeSkills";

    static {
        for (int level = 1; level <= MAX_LEVEL; level++) {
            int n = level - 1;
            THRESHOLD[level] = 12 * n * n + 13 * n;
        }
        MAX_XP = THRESHOLD[MAX_LEVEL];
    }

    private SkillLevels() {
    }

    // ------------------------------------------------------------ curve ---

    /** Total XP needed to reach {@code level} (1..10). */
    public static int xpForLevel(int level) {
        return THRESHOLD[Math.max(1, Math.min(MAX_LEVEL, level))];
    }

    public static int levelOf(int xp) {
        int bounded = clampXp(xp);
        int level = 1;
        while (level < MAX_LEVEL && bounded >= THRESHOLD[level + 1]) {
            level++;
        }
        return level;
    }

    /** 0..1 progress through the current level (1 at the cap). */
    public static float progress(int xp) {
        int bounded = clampXp(xp);
        int level = levelOf(bounded);
        if (level >= MAX_LEVEL) {
            return 1.0F;
        }
        int base = THRESHOLD[level];
        return (bounded - base) / (float) (THRESHOLD[level + 1] - base);
    }

    public static int clampXp(int xp) {
        return Math.max(0, Math.min(MAX_XP, xp));
    }

    // ---------------------------------------------------------- effects ---

    /** Fraction of a between-clip wait removed; exactly 0 at level 1. */
    public static double speedBonus(int level, int primary) {
        if (level <= 1) {
            return 0.0D;
        }
        int lv = Math.min(MAX_LEVEL, level);
        double weight = 0.5D + 0.5D * Math.max(0, Math.min(50, primary)) / 50.0D;
        return Math.min(MAX_SPEED_BONUS, 0.02D * (lv - 1) * weight);
    }

    /** Shortens a pause between work clips; identity at level 1. */
    public static int shortenWait(int ticks, int level, int primary) {
        double bonus = speedBonus(level, primary);
        if (ticks <= 0 || bonus <= 0.0D) {
            return ticks;
        }
        return Math.max(0, ticks - (int) Math.floor(ticks * bonus));
    }

    /**
     * Shortens a looped work duration by WHOLE clip loops only, so every
     * remaining loop keeps its contact frame; never below one loop.
     */
    public static int shortenLooped(int ticks, int period, int level, int primary) {
        double bonus = speedBonus(level, primary);
        if (ticks <= 0 || period <= 0 || bonus <= 0.0D) {
            return ticks;
        }
        int loops = (int) Math.floor(ticks * bonus / period);
        return Math.max(Math.min(ticks, period), ticks - loops * period);
    }

    /** Secondary-attribute side chance; exactly 0 below level 5. */
    public static double sideChance(int level, int secondary) {
        if (level < 5) {
            return 0.0D;
        }
        int lv = Math.min(MAX_LEVEL, level);
        return Math.min(MAX_SIDE_CHANCE,
            0.01D * (lv - 4) + Math.max(0, Math.min(99, secondary)) / 2000.0D);
    }

    // ------------------------------------------- per-trade job bonuses ---

    /** Cap on the Courier's per-trip carry bonus from trade level. */
    public static final int MAX_CARRY_BONUS = 3;

    /**
     * Courier primary (Strength): +1 item per trip for every 3 levels above
     * 1 (level 4, 7, 10), capped at {@link #MAX_CARRY_BONUS}. Exactly 0 at
     * levels 1-3. Layered on top of Satchel / Hand Cart / Stout Straps.
     */
    public static int carryBonus(int level) {
        if (level <= 1) {
            return 0;
        }
        return Math.min(MAX_CARRY_BONUS, (Math.min(MAX_LEVEL, level) - 1) / 3);
    }

    /**
     * Courier secondary (Stamina): walking pace bonus while on a delivery.
     * Same curve as {@link #sideChance} (0 below level 5, cap 8%), read as
     * a movement-speed fraction instead of a chance.
     */
    public static double paceBonus(int level, int secondary) {
        return sideChance(level, secondary);
    }

    /**
     * Scholar (Intelligence): extra research progress per completed session,
     * +1% per WITS point above {@link SettlerAttributes#START_CAP}, capped at
     * +{@value #MAX_WITS_XP_PERCENT}%. Exactly 0 at trade level 1, so a new
     * scholar behaves exactly as before whatever their Intelligence.
     */
    public static double researchBonus(int level, int wits) {
        if (level <= 1) {
            return 0.0D;
        }
        return witsXpBonus(wits);
    }

    public static Optional<Attribute> primaryOf(Profession profession) {
        return core(profession, 0);
    }

    public static Optional<Attribute> secondaryOf(Profession profession) {
        return core(profession, 1);
    }

    private static Optional<Attribute> core(Profession profession, int index) {
        return JobAttributeProfile.find(profession).flatMap(profile -> {
            int seen = 0;
            for (JobAttributeProfile.Slot slot : profile.slots()) {
                if (slot.importance() == JobAttributeProfile.Importance.CORE) {
                    if (seen++ == index) {
                        return Optional.of(slot.attribute());
                    }
                }
            }
            return Optional.empty();
        });
    }

    // ------------------------------------------------ client-side lines ---

    /** Live speed effect per trade: null = no cadence wired yet (not shown). */
    private static String speedLabel(Profession p) {
        return switch (p) {
            case LUMBERER -> "Pause between logs";
            case BAKER, COOK, BUTCHER, SMELTER, SMITH, SAWYER, CARPENTER, MASON,
                 FLETCHER, WEAVER, TANNER, MILLER, BREWER, ARMOURER -> "Craft time";
            case FARMER -> "Pause between field sweeps";
            case MINER -> "Pause between blocks";
            case HERDER -> "Pause between flock rounds";
            case HUNTER -> "Pause between hunts";
            case INNKEEPER -> "Meal preparation";
            default -> null;
        };
    }

    /** Live secondary side effect per trade: null = none wired yet. */
    private static String sideLabel(Profession p) {
        return switch (p) {
            case FISHER -> "Rod spared on a catch";
            case FARMER -> "Extra seed on a harvest";
            case HUNTER -> "Bow spared on a shot, +1 meat at the table";
            case INNKEEPER -> "Guest +1 morale on a meal";
            default -> null;
        };
    }

    /**
     * Client-callable, pure: the ACTIVE bonus lines for a worker, e.g.
     * "Craft time -8% (Dexterity)". Only effects that are really wired are
     * listed; a level-1 worker at baseline Intelligence gets an empty list.
     */
    public static java.util.List<Component> describeBonuses(Profession profession, int level,
                                                            int primary, int secondary, int wits) {
        java.util.List<Component> lines = new java.util.ArrayList<>(3);
        if (!profession.employed() || profession.martial()) {
            return lines;
        }
        String speed = speedLabel(profession);
        double bonus = speedBonus(level, primary);
        if (speed != null && bonus > 0.0D) {
            lines.add(Component.translatableWithFallback(
                "hearthstead.skill.bonus.speed." + profession.key(), "%s -%s%% (%s)",
                speed, Math.round(bonus * 100.0D),
                primaryOf(profession).map(Attribute::displayName).orElse(Component.empty())));
        }
        String side = sideLabel(profession);
        double chance = sideChance(level, secondary);
        if (side != null && chance > 0.0D) {
            lines.add(Component.translatableWithFallback(
                "hearthstead.skill.bonus.side." + profession.key(), "%s %s%% (%s)",
                side, Math.round(chance * 100.0D),
                secondaryOf(profession).map(Attribute::displayName).orElse(Component.empty())));
        }
        if (profession == Profession.COURIER) {
            int carry = carryBonus(level);
            if (carry > 0) {
                lines.add(Component.translatableWithFallback(
                    "hearthstead.skill.bonus.carry", "Carry +%s per trip (%s)",
                    carry, Attribute.STRENGTH.displayName()));
            }
            double pace = paceBonus(level, secondary);
            if (pace > 0.0D) {
                lines.add(Component.translatableWithFallback(
                    "hearthstead.skill.bonus.pace", "Walking pace +%s%% (%s)",
                    Math.round(pace * 100.0D), Attribute.STAMINA.displayName()));
            }
        }
        if (profession == Profession.SCHOLAR) {
            double research = researchBonus(level, wits);
            if (research > 0.0D) {
                lines.add(Component.translatableWithFallback(
                    "hearthstead.skill.bonus.research", "Research progress +%s%% (%s)",
                    Math.round(research * 100.0D), Attribute.WITS.displayName()));
            }
        }
        double learn = witsXpBonus(wits);
        if (learn > 0.0D) {
            lines.add(Component.translatableWithFallback(
                "hearthstead.skill.bonus.learning", "Trade XP +%s%% (%s)",
                Math.round(learn * 100.0D), Attribute.WITS.displayName()));
        }
        return lines;
    }

    /**
     * Same, read off a settler. SERVER ONLY for the attribute inputs: the
     * client never holds real attributes (they arrive in the settler-sheet
     * snapshot), so client UI must call the pure overload with
     * {@code snapshot.attributeValues()} and {@code settler.tradeXp()}.
     * The level is read off the authoritative track (like every other
     * server shortcut here), not the synced projection, which only
     * refreshes on the next award / 1 Hz sync.
     */
    public static java.util.List<Component> describeBonuses(SettlerEntity settler) {
        Profession p = settler.getProfession();
        return describeBonuses(p, levelOf(settler),
            primaryOf(p).map(settler::attribute).orElse(0),
            secondaryOf(p).map(settler::attribute).orElse(0),
            settler.attribute(Attribute.WITS));
    }

    /** XP still needed for the next level; 0 at level 10. */
    public static int xpToNext(int xp) {
        int level = levelOf(xp);
        return level >= MAX_LEVEL ? 0 : xpForLevel(level + 1) - clampXp(xp);
    }

    // ------------------------------------------------- settler shortcuts ---

    public static int levelOf(SettlerEntity settler) {
        return levelOf(settler.tradeSkills().xp(settler.getProfession()));
    }

    /**
     * {@link #shortenWait} for this settler's current trade and primary, plus
     * job fit ({@link AttributeRuntime#jobFit}, plan/ATTRIBUTES.md). The two
     * add and are capped at {@link AttributeEffects#MAX_WORK_TIME_CUT}.
     */
    public static int shortenWait(SettlerEntity settler, int ticks) {
        return shortenWaitBy(ticks, workCut(settler, 0.0D));
    }

    /**
     * {@link #shortenLooped} for a crafter: trade level, job fit and Focus
     * (batch time at a bench) together, capped; whole loops only.
     */
    public static int shortenLooped(SettlerEntity settler, int ticks, int period) {
        return shortenLoopedBy(ticks, period,
            workCut(settler, AttributeRuntime.craftCut(settler)));
    }

    /** Level speed + job fit + {@code extra}, capped at the work-time cap. */
    public static double workCut(SettlerEntity settler, double extra) {
        Profession p = settler.getProfession();
        int primary = primaryOf(p).map(settler::attribute).orElse(0);
        double level = speedBonus(levelOf(settler), primary);
        return AttributeEffects.combinedWorkCut(level,
            AttributeRuntime.jobFit(settler) + AttributeRuntime.workBoon(settler)
                + Math.max(0.0D, extra), AttributeRuntime.traitWorkCut(settler));
    }

    /**
     * Pure: shortens a between-clip wait by {@code cut} (-0.10..0.30); a
     * negative cut (a trait's slower pace) lengthens it by at most 10%.
     */
    public static int shortenWaitBy(int ticks, double cut) {
        double bonus = Math.max(AttributeEffects.MIN_TRAIT_WORK_CUT,
            Math.min(AttributeEffects.MAX_WORK_TIME_CUT, cut));
        if (ticks <= 0 || bonus == 0.0D) {
            return ticks;
        }
        if (bonus < 0.0D) {
            return ticks + (int) Math.floor(ticks * -bonus);
        }
        return Math.max(0, ticks - (int) Math.floor(ticks * bonus));
    }

    /**
     * Pure: removes (or, for a negative cut, adds) whole clip loops worth
     * {@code cut}; never below one loop, never more than 10% longer.
     */
    public static int shortenLoopedBy(int ticks, int period, double cut) {
        double bonus = Math.max(AttributeEffects.MIN_TRAIT_WORK_CUT,
            Math.min(AttributeEffects.MAX_WORK_TIME_CUT, cut));
        if (ticks <= 0 || period <= 0 || bonus == 0.0D) {
            return ticks;
        }
        int loops = (int) Math.floor(ticks * Math.abs(bonus) / period);
        if (bonus < 0.0D) {
            return ticks + loops * period;
        }
        return Math.max(Math.min(ticks, period), ticks - loops * period);
    }

    /** Server roll against {@link #sideChance}; always false below level 5. */
    public static boolean rollSide(SettlerEntity settler) {
        Profession p = settler.getProfession();
        int level = levelOf(settler);
        if (level < 5) {
            return false;
        }
        int secondary = secondaryOf(p).map(settler::attribute).orElse(0);
        return settler.getRandom().nextDouble() < sideChance(level, secondary);
    }

    /** {@link #carryBonus} for this settler's current trade level. */
    public static int carryBonus(SettlerEntity settler) {
        return carryBonus(levelOf(settler));
    }

    /** {@link #paceBonus} for this settler's current trade and secondary. */
    public static double paceBonus(SettlerEntity settler) {
        Profession p = settler.getProfession();
        int secondary = secondaryOf(p).map(settler::attribute).orElse(0);
        return paceBonus(levelOf(settler), secondary);
    }

    /** {@link #researchBonus} for this settler (trade level + Intelligence). */
    public static double researchBonus(SettlerEntity settler) {
        return researchBonus(levelOf(settler), settler.attribute(Attribute.WITS));
    }

    // ------------------------------------------------------------ award ---

    /**
     * One real completed work unit. Call exactly once, at the completion
     * point, AFTER the output has committed. {@code alreadyTrained} lists the
     * attributes the call site already trained for this same unit.
     *
     * @return the XP actually added (0 when unemployed, client side or capped)
     */
    public static int completeUnit(SettlerEntity settler, int xp,
                                   Attribute... alreadyTrained) {
        if (settler.level().isClientSide() || xp <= 0) {
            return 0;
        }
        Profession profession = settler.getProfession();
        if (!profession.employed() || profession.martial()) {
            return 0;
        }
        primaryOf(profession).filter(a -> !contains(alreadyTrained, a))
            .ifPresent(a -> settler.train(a, 1.0F));
        secondaryOf(profession).filter(a -> !contains(alreadyTrained, a))
            .ifPresent(a -> settler.train(a, 0.5F));
        // Tech tree Masters & Apprentices (techtree-craft): +50% beside a level-5+ co-worker.
        xp = com.hearthstead.settlement.techtree.effects.CraftEffects.apprenticeXp(settler, xp);
        Track track = settler.tradeSkills();
        int before = track.xp(profession);
        int wits = settler.attribute(Attribute.WITS);
        // The entity random is only consumed when Intelligence actually pays.
        double roll = witsXpBonus(wits) > 0.0D ? settler.getRandom().nextDouble() : 1.0D;
        int after = track.add(profession, witsScaledXp(xp, wits, roll));
        settler.syncTradeSkill();
        int oldLevel = levelOf(before);
        int newLevel = levelOf(after);
        if (newLevel > oldLevel) {
            onLevelUp(settler, profession, newLevel);
        }
        return after - before;
    }

    /**
     * Intelligence (WITS) is the cross-job learning skill: +1% trade XP per
     * point above {@link SettlerAttributes#START_CAP} (the best a newcomer can
     * arrive with), capped at +{@value #MAX_WITS_XP_PERCENT}%. At or below the
     * newcomer cap the award is exactly {@code xp}. The fractional part is
     * paid as a chance of one extra point, driven by {@code roll} in [0,1).
     */
    public static int witsScaledXp(int xp, int wits, double roll) {
        double bonus = witsXpBonus(wits);
        if (xp <= 0 || bonus <= 0.0D) {
            return Math.max(0, xp);
        }
        double total = xp * (1.0D + bonus);
        int whole = (int) Math.floor(total);
        return whole + (roll < total - whole ? 1 : 0);
    }

    public static final int MAX_WITS_XP_PERCENT = 25;

    public static double witsXpBonus(int wits) {
        int above = Math.max(0, Math.min(99, wits) - SettlerAttributes.START_CAP);
        return Math.min(MAX_WITS_XP_PERCENT, above) / 100.0D;
    }

    private static boolean contains(Attribute[] list, Attribute a) {
        for (Attribute x : list) {
            if (x == a) {
                return true;
            }
        }
        return false;
    }

    private static final Map<SettlerEntity, Integer> LEVEL_UPS = new WeakHashMap<>();

    /**
     * The level-up moment: {@link SettlerFlourish} (soft vanilla level-up
     * chime + happy-villager motes) and the action-bar line for players
     * nearby: "Ansgar the Farmer reached level 4".
     */
    public static void onLevelUp(SettlerEntity settler, Profession profession,
                                 int newLevel) {
        synchronized (LEVEL_UPS) {
            LEVEL_UPS.merge(settler, 1, Integer::sum);
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Component line = Component.translatableWithFallback(
            "hearthstead.message.trade_level_up",
            "%s the %s reached level %s",
            settler.getSettlerName(), profession.displayName(), newLevel);
        // Shared restrained flourish (soft chime + happy-villager motes, same
        // tick dedupe with train()'s own cue). The specific line is sent here
        // unthrottled: a level-up is days apart and must not be swallowed by
        // the generic "grew more skilled" line's one-minute throttle.
        SettlerFlourish.play(level, settler, null);
        com.hearthstead.fx.FxHooks.skillLevelUp(settler);
        double nearbySq = SettlerFlourish.NEARBY * SettlerFlourish.NEARBY;
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(settler) <= nearbySq) {
                player.displayClientMessage(line, true);
            }
        }
    }

    /** GameTest hook: level-ups fired for this settler. */
    public static int levelUpCount(SettlerEntity settler) {
        synchronized (LEVEL_UPS) {
            return LEVEL_UPS.getOrDefault(settler, 0);
        }
    }

    // ------------------------------------------------------------ state ---

    /** Per-settler XP by profession. Bounded: employed professions only. */
    public static final class Track {
        private final EnumMap<Profession, Integer> xp = new EnumMap<>(Profession.class);

        public int xp(Profession profession) {
            return xp.getOrDefault(profession, 0);
        }

        public int level(Profession profession) {
            return levelOf(xp(profession));
        }

        /** Adds and returns the new, clamped total. */
        public int add(Profession profession, int amount) {
            if (!profession.employed() || amount <= 0) {
                return xp(profession);
            }
            int next = clampXp(xp(profession) + amount);
            xp.put(profession, next);
            return next;
        }

        public void set(Profession profession, int value) {
            if (!profession.employed()) {
                return;
            }
            int v = clampXp(value);
            if (v == 0) {
                xp.remove(profession);
            } else {
                xp.put(profession, v);
            }
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            for (Map.Entry<Profession, Integer> e : xp.entrySet()) {
                if (e.getValue() > 0) {
                    tag.putInt(e.getKey().key(), e.getValue());
                }
            }
            return tag;
        }

        /** Missing tag (old save) = every trade at level 1 with 0 XP. */
        public void load(CompoundTag tag) {
            xp.clear();
            if (tag == null) {
                return;
            }
            for (Profession p : Profession.BY_ID) {
                if (p.employed() && tag.contains(p.key())) {
                    set(p, tag.getInt(p.key()));
                }
            }
        }
    }
}

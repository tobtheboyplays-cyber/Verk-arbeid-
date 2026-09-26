package com.hearthstead.settlement.gear;

import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.GuardRank;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The five Gear Tiers: which settler may USE which equipment, and when.
 *
 * <p>Owner's ask, 26 Sep 2026: "a system that says when which settler can use
 * which equipment. I don't want to be able to give him a diamond sword right
 * away." A settler may use an item of tier {@code T} only when BOTH halves
 * hold:
 *
 * <ol>
 *   <li><b>Personal skill.</b> Guards by {@link GuardRank}, archers by
 *       {@link ArcherRank}, every other worker by their trade level
 *       ({@link com.hearthstead.entity.SkillLevels}). The ladder is cumulative:
 *       a Veteran may use everything a Spearman may.</li>
 *   <li><b>Settlement knowledge.</b> The tech a village must own before its
 *       people are trusted with that material: the Village Charter for mail,
 *       an Armoury, Smithy or the Iron Arms Drill for plate, and the Castle
 *       and Crown charters with a Master Armoury for diamond and netherite.
 *       Checked per tier.</li>
 * </ol>
 *
 * <table>
 *   <caption>Gear tiers</caption>
 *   <tr><th>tier</th><th>items</th><th>guard</th><th>archer</th><th>worker</th><th>settlement</th></tr>
 *   <tr><td>T0 Common</td><td>wood, stone, gold, iron tools and iron sword, leather, bow, rod</td><td>Recruit</td><td>Recruit</td><td>lvl 1</td><td>-</td></tr>
 *   <tr><td>T1 Mail</td><td>chainmail, shield, turtle shell, iron longsword</td><td>Spearman</td><td>Marksman</td><td>lvl 3</td><td>Village Charter</td></tr>
 *   <tr><td>T2 Plate</td><td>iron armour, crossbow</td><td>Veteran</td><td>Sharpshooter</td><td>lvl 5</td><td>Armoury / Smithy / Iron Arms Drill</td></tr>
 *   <tr><td>T3 Diamond</td><td>all diamond gear, trident, mace</td><td>Sergeant</td><td>Master Archer</td><td>lvl 8</td><td>Castle Charter + Master Armoury</td></tr>
 *   <tr><td>T4 Netherite</td><td>all netherite gear</td><td>Captain</td><td>Master Archer</td><td>lvl 10</td><td>Crown of the Realm + Master Armoury</td></tr>
 * </table>
 *
 * <p><b>Why iron tools and the iron sword are Common.</b> The Hamlet economy
 * already runs on iron: its tech nodes are paid in iron ingots, the tool
 * requests ask for an iron axe and hoe from day one, and the first raid is
 * fought by recruits with whatever sword the village could forge. Locking iron
 * hand gear would stall the first two hours for no story gain. The ladder
 * therefore bites where the fantasy is: mail, plate, diamond and netherite.
 *
 * <p><b>Knowledge ids.</b> Each tier's knowledge is a list of all-of groups,
 * each group an any-of list of ids: {@code node:<DevelopmentNode id>},
 * {@code upgrade:<PostRaidUpgrade id>} or {@code building:<BuildingType id>}.
 * Ids the code does not know yet (the Town/Castle/Kingdom charters and the
 * Master Armoury are design data in {@code plan/techtree/techtree.json}) are
 * simply never owned, so T3/T4 stay locked until the rank ladder ships.
 * Which item sits in which tier is data: {@code #hearthstead:gear_tier/t0..t4}.
 */
public enum GearTier {
    COMMON("common", GuardRank.RECRUIT, ArcherRank.RECRUIT, 1, List.of()),
    MAIL("mail", GuardRank.SPEARMAN, ArcherRank.MARKSMAN, 3,
        List.of(List.of("node:first_raid_aftermath"))),
    PLATE("plate", GuardRank.VETERAN, ArcherRank.SHARPSHOOTER, 5,
        List.of(List.of("upgrade:guard_arms_iron", "node:fortification",
            "node:craft_and_industry", "building:armoury", "building:smithy"))),
    DIAMOND("diamond", GuardRank.SERGEANT, ArcherRank.MASTER, 8,
        List.of(List.of("node:castle_charter"), List.of("node:master_armoury"))),
    NETHERITE("netherite", GuardRank.CAPTAIN, ArcherRank.MASTER, 10,
        List.of(List.of("node:kingdom_crown"), List.of("node:master_armoury")));

    public static final int MAX = 4;
    private static final GearTier[] BY_LEVEL = values();

    private final String key;
    private final GuardRank guardRank;
    private final ArcherRank archerRank;
    private final int tradeLevel;
    private final List<List<String>> knowledge;

    GearTier(String key, GuardRank guardRank, ArcherRank archerRank,
             int tradeLevel, List<List<String>> knowledge) {
        this.key = key;
        this.guardRank = guardRank;
        this.archerRank = archerRank;
        this.tradeLevel = tradeLevel;
        this.knowledge = knowledge;
    }

    /** 0..4, identical to {@link #ordinal()} and to the tag suffix. */
    public int level() {
        return ordinal();
    }

    public String key() {
        return key;
    }

    public GuardRank guardRank() {
        return guardRank;
    }

    public ArcherRank archerRank() {
        return archerRank;
    }

    public int tradeLevel() {
        return tradeLevel;
    }

    /** All-of groups of any-of knowledge ids; empty means "no knowledge needed". */
    public List<List<String>> knowledge() {
        return knowledge;
    }

    public static GearTier of(int level) {
        return BY_LEVEL[Math.max(0, Math.min(MAX, level))];
    }

    /** Who is asking: the three personal ladders. */
    public enum Role {
        GUARD, ARCHER, WORKER;

        public static Role fromId(int id) {
            Role[] all = values();
            return all[Math.max(0, Math.min(all.length - 1, id))];
        }
    }

    // ------------------------------------------------------------ personal ---

    /** Highest tier the guard's own rank earns. */
    public static int personalTier(GuardRank rank) {
        int best = 0;
        for (GearTier tier : BY_LEVEL) {
            if (rank != null && rank.atLeast(tier.guardRank)) {
                best = tier.level();
            }
        }
        return best;
    }

    /** Highest tier the archer's own rank earns. */
    public static int personalTier(ArcherRank rank) {
        int best = 0;
        for (GearTier tier : BY_LEVEL) {
            if (rank != null && rank.atLeast(tier.archerRank)) {
                best = tier.level();
            }
        }
        return best;
    }

    /** Highest tier a worker's trade level earns. */
    public static int personalTier(int tradeLevel) {
        int best = 0;
        for (GearTier tier : BY_LEVEL) {
            if (tradeLevel >= tier.tradeLevel) {
                best = tier.level();
            }
        }
        return best;
    }

    /** The personal requirement of this tier for {@code role}, as player text. */
    public Component personalRequirement(Role role) {
        return switch (role) {
            case GUARD -> Component.translatable("hearthstead.gear.need.rank",
                guardRank.displayName());
            case ARCHER -> Component.translatable("hearthstead.gear.need.rank",
                archerRank.displayName());
            case WORKER -> Component.translatable("hearthstead.gear.need.level",
                tradeLevel);
        };
    }

    // ----------------------------------------------------------- knowledge ---

    /**
     * Whether a settlement owning exactly the ids {@code owned} answers for
     * this tier. Pure: {@link GearGate} supplies the owned set.
     */
    public boolean knowledgeMet(java.util.function.Predicate<String> owned) {
        for (List<String> group : knowledge) {
            boolean any = false;
            for (String id : group) {
                if (owned.test(id)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** Bit {@code t} set when tier {@code t}'s knowledge is met. T0 is always set. */
    public static int knowledgeMask(java.util.function.Predicate<String> owned) {
        int mask = 0;
        for (GearTier tier : BY_LEVEL) {
            if (tier.knowledgeMet(owned)) {
                mask |= 1 << tier.level();
            }
        }
        return mask;
    }

    /** Player text for this tier's settlement requirement, or null for none. */
    @Nullable
    public Component knowledgeRequirement() {
        return knowledge.isEmpty() ? null
            : Component.translatable("hearthstead.gear.know." + key);
    }

    public Component displayName() {
        return Component.translatable("hearthstead.gear.tier." + key);
    }

    /** "iron armour and crossbows": what this tier means in a sentence. */
    public Component itemsName() {
        return Component.translatable("hearthstead.gear.items." + key);
    }

    // ----------------------------------------------------------- decision ---

    /**
     * The whole rule, pure: may someone whose personal ladder reaches
     * {@code personalTier} and whose settlement's knowledge bits are
     * {@code knowledgeMask} use an item of {@code itemTier}?
     */
    public static boolean allowed(int itemTier, int personalTier, int knowledgeMask) {
        if (itemTier <= 0) {
            return true;
        }
        int t = Math.min(MAX, itemTier);
        return personalTier >= t && (knowledgeMask & (1 << t)) != 0;
    }

    /** Highest tier fully usable under these two halves (0 at worst). */
    public static int usableTier(int personalTier, int knowledgeMask) {
        int best = 0;
        for (int t = 1; t <= MAX; t++) {
            if (allowed(t, personalTier, knowledgeMask)) {
                best = t;
            }
        }
        return best;
    }

    /** The lowest tier not yet usable, or null when everything is open. */
    @Nullable
    public static GearTier nextLocked(int personalTier, int knowledgeMask) {
        for (int t = 1; t <= MAX; t++) {
            if (!allowed(t, personalTier, knowledgeMask)) {
                return of(t);
            }
        }
        return null;
    }
}

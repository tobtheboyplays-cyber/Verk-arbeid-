package com.hearthstead.settlement.techtree.effects;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechBonus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Craft &amp; Production branch effects (lane "techtree-craft", 26 Sep).
 * OWNED BY THE CRAFT BRANCH LANE: only that lane edits this file (and
 * data/hearthstead/techtree/craft.json).
 *
 * <p>Every hook below is read by gameplay code through one of the small
 * static helpers at the bottom of this class, so each hook site is a single
 * guarded line and behaviour is unchanged while the node is not learned:
 * <ul>
 *   <li>{@code craft_and_industry}: {@link #toolTicks} in
 *       {@code LumbererWorkGoal.tickChop} and {@code MinerWorkGoal.tick}.</li>
 *   <li>{@code charcoal_kilns}: {@link #smelterScale} in
 *       {@code Production.ticksFor} and {@code CrafterWorkGoal.researchEffortMultiplier}.</li>
 *   <li>{@code deep_mine}: {@link #mineReach} and {@link #extraOre} in
 *       {@code MinerWorkGoal}.</li>
 *   <li>{@code masters_apprentices}: {@link #apprenticeXp} in
 *       {@code SkillLevels.completeUnit}.</li>
 *   <li>{@code guild_halls}: {@link #qualityMeanBonus} in
 *       {@code Production.rollQuality}.</li>
 *   <li>{@code builders_hut}, {@code tannery}, {@code carpenter_mason}:
 *       authoritative plan + emblem claims, grandfathered for pre-v3 saves.</li>
 * </ul>
 * Every bonus is per settlement (one shared tree), never per player.
 */
public final class CraftEffects {

    /** Mine, Smelter &amp; Smithy: work-time scale for a worker holding an iron-class tool. */
    public static final double IRON_TOOL_SCALE = 0.90D;
    /** Mine, Smelter &amp; Smithy: work-time scale for diamond or netherite tools. */
    public static final double DIAMOND_TOOL_SCALE = 0.80D;

    /** Charcoal Kilns: percent cut in every Smelter batch's time and effort. */
    public static final TechBonus SMELT_CUT = TechBonus.percent("craft.smelt_cut",
        "Every Smelter batch takes %s%% less time and effort");
    /** Deep Mine: extra blocks the Miner may dig below the mine entrance. */
    public static final TechBonus MINE_DEPTH = TechBonus.of("craft.mine_depth",
        "The Miner digs %s blocks deeper (14 instead of 12)");
    /** Deep Mine: percent chance an ore block yields one more ore. */
    public static final TechBonus ORE_BONUS = TechBonus.percent("craft.ore_bonus",
        "%s%% of mined ore blocks give 1 extra ore");
    /** Masters &amp; Apprentices: extra trade XP beside a master. */
    public static final TechBonus APPRENTICE_XP = TechBonus.percent("craft.apprentice_xp",
        "Workers who share a workshop with a master (trade level 5+) earn %s%% more trade XP");
    /** Guild Halls: crafted-goods quality rolls as if this many trade levels higher. */
    public static final TechBonus QUALITY_LEVELS = TechBonus.of("craft.quality_levels",
        "Crafted goods roll their quality as if the crafter were %s trade levels higher");

    /** Trade level a co-worker needs to count as a master. */
    public static final int MASTER_LEVEL = 5;
    /** Mean shift of the crafted-quality roll per bonus trade level (CraftedQuality.PER_TRADE_LEVEL). */
    public static final double QUALITY_MEAN_PER_LEVEL =
        com.hearthstead.settlement.work.CraftedQuality.PER_TRADE_LEVEL;

    private CraftEffects() {
    }

    static void register(EffectRegistry r) {
        // --- claims: plans + emblems move onto their own nodes -------------
        r.node("builders_hut")
            .building(BuildingType.BUILDERS_HUT)
            .profession(Profession.BUILDER)
            .flag("BuilderUnlocks.owns(builders_hut) / BuilderNetwork",
                "Order blueprints, barricade lines and Upgrade Orders from your Builder")
            .grandfatheredBy("timber_rights");
        r.node("tannery")
            .building(BuildingType.TANNERY)
            .profession(Profession.TANNER)
            .grandfatheredBy("craft_and_industry");
        r.node("carpenter_mason")
            .building(BuildingType.CARPENTER)
            .building(BuildingType.MASON)
            .building(BuildingType.WEAVER)
            .profession(Profession.CARPENTER)
            .profession(Profession.MASON)
            .profession(Profession.WEAVER)
            .grandfatheredBy("craft_and_industry");

        // --- Mine, Smelter & Smithy keeps its legacy plans (Mine, Smelter,
        // Smithy) and adds the tool-tier speed hook.
        r.node("craft_and_industry")
            .flag("LumbererWorkGoal.tickChop + MinerWorkGoal.tick via CraftEffects.toolTicks",
                "Lumberers and Miners holding an iron tool work 10% faster (diamond or netherite: 20%)");

        // --- pick one: Kilns or Deep Mine ---------------------------------
        r.node("charcoal_kilns")
            .bonus(SMELT_CUT, 25);
        r.node("deep_mine")
            .bonus(MINE_DEPTH, 2)
            .bonus(ORE_BONUS, 15);

        // --- Town / Castle -------------------------------------------------
        r.node("masters_apprentices")
            .bonus(APPRENTICE_XP, 50);
        r.node("guild_halls")
            .bonus(QUALITY_LEVELS, 2)
            .flag("Production.rollQuality via CraftEffects.qualityMeanBonus",
                "Masterwork still needs a real trade level 7, Legendary level 9");
    }

    // ================================================================ reads

    @Nullable
    private static Settlement settlementOf(@Nullable ServerLevel level, @Nullable UUID id) {
        return level == null || id == null ? null : SettlementManager.byId(level, id);
    }

    // ---------------------------------------------- craft_and_industry ---

    /** Pure: work-time scale for the tool in hand (1.0 = no bonus). */
    public static double toolScale(@Nullable ItemStack tool) {
        if (tool == null || tool.isEmpty() || !(tool.getItem() instanceof TieredItem tiered)) {
            return 1.0D;
        }
        Tier tier = tiered.getTier();
        if (tier == Tiers.DIAMOND || tier == Tiers.NETHERITE) {
            return DIAMOND_TOOL_SCALE;
        }
        if (tier == Tiers.IRON) {
            return IRON_TOOL_SCALE;
        }
        if (tier == Tiers.WOOD || tier == Tiers.STONE || tier == Tiers.GOLD || tier == null) {
            return 1.0D;
        }
        // Modded tiers, by durability (iron 250, diamond 1561).
        int uses = tier.getUses();
        return uses >= 1200 ? DIAMOND_TOOL_SCALE : uses >= 250 ? IRON_TOOL_SCALE : 1.0D;
    }

    /**
     * Work ticks for a gatherer holding {@code tool}: shortened by
     * {@link #toolScale} once Mine, Smelter &amp; Smithy is learned, otherwise
     * exactly {@code ticks}. Never below 1.
     */
    public static int toolTicks(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                @Nullable ItemStack tool, int ticks) {
        if (ticks <= 1 || level == null || settlement == null) {
            return ticks;
        }
        double scale = toolScale(tool);
        if (scale >= 1.0D || !Development.has(level, settlement, "craft_and_industry")) {
            return ticks;
        }
        return Math.max(1, (int) Math.round(ticks * scale));
    }

    // ---------------------------------------------------- charcoal_kilns ---

    /** Time/effort scale for a batch at {@code type} (1.0 unless a Smelter with Kilns). */
    public static double smelterScale(@Nullable ServerLevel level, @Nullable UUID settlementId,
                                      @Nullable BuildingType type) {
        if (type != BuildingType.SMELTER) {
            return 1.0D;
        }
        Settlement settlement = settlementOf(level, settlementId);
        double cut = TechTree.bonus(level, settlement, SMELT_CUT);
        return cut <= 0.0D ? 1.0D : Math.max(0.1D, 1.0D - cut / 100.0D);
    }

    // --------------------------------------------------------- deep_mine ---

    /** The Miner's dig depth: {@code base} plus Deep Mine's extra blocks. */
    public static int mineReach(@Nullable ServerLevel level, @Nullable Settlement settlement, int base) {
        return base + (int) Math.round(TechTree.bonus(level, settlement, MINE_DEPTH));
    }

    /** True when this ore block should yield one extra ore (Deep Mine roll). */
    public static boolean extraOre(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                   BlockState state, RandomSource random) {
        if (state == null || random == null || !state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)) {
            return false;
        }
        double chance = TechTree.bonus(level, settlement, ORE_BONUS);
        return chance > 0.0D && random.nextDouble() * 100.0D < chance;
    }

    // ----------------------------------------------- masters_apprentices ---

    /**
     * Trade XP after Masters &amp; Apprentices: {@code xp} plus the bonus when
     * another worker at the same workplace is at trade level
     * {@value #MASTER_LEVEL}+. The fraction is paid as a chance of one more
     * point, so +50% of 1 XP averages 1.5.
     */
    public static int apprenticeXp(SettlerEntity settler, int xp) {
        if (xp <= 0 || !(settler.level() instanceof ServerLevel level)) {
            return xp;
        }
        Settlement settlement = settler.settlement();
        double bonus = TechTree.bonus(level, settlement, APPRENTICE_XP);
        if (bonus <= 0.0D || !sharesWorkshopWithMaster(level, settlement, settler)) {
            return xp;
        }
        double total = xp * (1.0D + bonus / 100.0D);
        int whole = (int) Math.floor(total);
        double frac = total - whole;
        return whole + (frac > 1.0E-9 && settler.getRandom().nextDouble() < frac ? 1 : 0);
    }

    /** True when a loaded co-worker at this settler's workplace is trade level 5+. */
    public static boolean sharesWorkshopWithMaster(ServerLevel level, @Nullable Settlement settlement,
                                                   SettlerEntity settler) {
        if (settlement == null) {
            return false;
        }
        Building work = Employment.employerOf(settlement, settler.getUUID());
        if (work == null || work.workers.size() < 2) {
            return false;
        }
        for (UUID id : work.workers) {
            if (id.equals(settler.getUUID())) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (entity instanceof SettlerEntity mate && mate.isAlive()
                && com.hearthstead.entity.SkillLevels.levelOf(mate) >= MASTER_LEVEL) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------- guild_halls ---

    /** Extra mean for the crafted-quality roll (0 unless Guild Halls). */
    public static double qualityMeanBonus(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return TechTree.bonus(level, settlement, QUALITY_LEVELS) * QUALITY_MEAN_PER_LEVEL;
    }
}

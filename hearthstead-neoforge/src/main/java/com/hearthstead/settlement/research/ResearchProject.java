package com.hearthstead.settlement.research;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * The six things a settlement's scholar can research (v1). Mirrors
 * {@link com.hearthstead.building.BuildingType}'s own shape — an id, a fixed
 * catalogue of requirements (here, costs) declared once at the enum site —
 * for the same reason: the client and the server both load this class, so a
 * screen never has to be told what a project needs, only what has been
 * gathered toward it.
 *
 * <p><b>Every project sits on exactly one {@code docs/project/FLOWS.md} fed
 * path</b> and gives it a modest, readable multiplier (§0's "between ×1.5 and
 * ×2" is FLOWS' rule for a building feeding another; a scholar's bonus is a
 * gentler ±10–15%, because six of these can eventually stack under one
 * settlement and the point is a visible, earned edge — not a second economy
 * bolted onto the first). See {@code PLAN_RESEARCH.md} §3 for the full ledger
 * and the reasoning behind each pairing.
 *
 * <p>Costs are real items that leave a real chest (Prøvebenken's whole
 * premise — see {@code PLAN_RESEARCH.md} §2 CHOSEN). Every project spends
 * {@link #PAPER_COST} paper — the write-up — plus a domain sample specific to
 * what is being learned.
 */
public enum ResearchProject {
    /** Bakery: bakes 15% faster AND costs 15% less effort per batch — see
     *  {@code CrafterWorkGoal#researchEffortMultiplier} and
     *  {@code docs/project/BALANCE_AUDIT.md} finding 2's follow-up for why
     *  the same 0.85 now has to answer to both. */
    BEDRE_GJAER("bedre_gjaer", ResearchKey.BAKERY_TICKS, 0.85F, 3, Items.BREAD,
        new Cost(Items.WHEAT, 16)),

    /** Sawmill: mills 15% faster AND costs 15% less effort per batch (see
     *  {@link #BEDRE_GJAER}'s doc for why both). */
    TORRSETT_TOMMER("torrsett_tommer", ResearchKey.SAWMILL_TICKS, 0.85F, 3, Items.OAK_PLANKS,
        new Cost(Items.OAK_LOG, 24)),

    /** Smelter: smelts 15% faster AND costs 15% less effort per batch (see
     *  {@link #BEDRE_GJAER}'s doc for why both). */
    BLESTRING("blestring", ResearchKey.SMELTER_TICKS, 0.85F, 3, Items.IRON_INGOT,
        new Cost(Items.RAW_IRON, 12)),

    /** Tannery: cures 15% faster AND costs 15% less effort per batch (see
     *  {@link #BEDRE_GJAER}'s doc for why both). */
    GARVESYRE("garvesyre", ResearchKey.TANNERY_TICKS, 0.85F, 3, Items.LEATHER,
        new Cost(Items.CHARCOAL, 12)),

    /** Farmed crops: grow 15% more often. */
    AAKERSKIFTE("akerskifte", ResearchKey.FARM_GROWTH, 1.15F, 2, Items.WHEAT_SEEDS,
        new Cost(Items.WHEAT_SEEDS, 16)),

    /** Guards: train STRENGTH 10% faster. */
    VAKTDRILL("vaktdrill", ResearchKey.GUARD_TRAINING, 1.10F, 4, Items.IRON_SWORD,
        new Cost(Items.IRON_INGOT, 8));

    /** Every project's write-up, on top of its own domain sample below. */
    public static final Item PAPER = Items.PAPER;
    public static final int PAPER_COST = 4;

    public static final ResearchProject[] BY_ORDINAL = values();

    /** One line item of what a project costs. */
    public record Cost(Item item, int count) {
    }

    private final String id;
    private final ResearchKey key;
    private final float bonus;
    private final int workDays;
    private final Item emblem;
    private final List<Cost> costs;

    ResearchProject(String id, ResearchKey key, float bonus, int workDays, Item emblem,
                    Cost domainCost) {
        this.id = id;
        this.key = key;
        this.bonus = bonus;
        this.workDays = workDays;
        this.emblem = emblem;
        // Items.PAPER directly, not the PAPER field above: an enum
        // constructor runs before the enum's own statics exist, and the
        // compiler rejects the reference. PAPER_COST survives because a
        // constant-expression int is inlined. PAPER stays public for readers.
        this.costs = List.of(new Cost(Items.PAPER, PAPER_COST), domainCost);
    }

    public String id() {
        return id;
    }

    /** Which {@code Research.bonus} lookup this project affects. */
    public ResearchKey key() {
        return key;
    }

    /** The multiplier completing this project applies — see {@link ResearchKey}.
     *  For the four {@code *_TICKS} keys this same number is read TWICE by
     *  {@code CrafterWorkGoal} — once for recipe ticks, once for the batch's
     *  effort cost — rather than being two separate, driftable numbers. */
    public float bonus() {
        return bonus;
    }

    /**
     * How many scholar work-sessions this takes at the lectern
     * ({@code ScholarWorkGoal}'s own unit — see its class doc for why one
     * session tracks a "day" closely without being tied to it exactly).
     */
    public int workDays() {
        return workDays;
    }

    /** The item that stands for this project on its card, the same
     *  convention {@code BuildingType#emblem()} uses. */
    public Item emblem() {
        return emblem;
    }

    /** Paper plus one domain sample. Never empty. */
    public List<Cost> costs() {
        return costs;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.research.project." + id + ".name");
    }

    /** The one sentence that says what finishing this buys the settlement. */
    public Component effectSentence() {
        return Component.translatable("hearthstead.research.project." + id + ".effect");
    }

    public static ResearchProject byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < BY_ORDINAL.length ? BY_ORDINAL[ordinal] : null;
    }
}

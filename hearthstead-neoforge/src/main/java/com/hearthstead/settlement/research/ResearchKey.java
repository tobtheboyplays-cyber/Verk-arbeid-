package com.hearthstead.settlement.research;

/**
 * What a completed project actually changes, read by the consumer that owns
 * the number — {@code Production} for a tick multiplier, a farm-growth or
 * guard-training goal for a rate multiplier.
 *
 * <p>Every value {@link Research#bonus} returns is a <b>multiplier</b>, never
 * a switch: {@code docs/project/FLOWS.md}'s one rule, "multiply, never gate,"
 * applies here exactly as it does to a fed path between two buildings. A
 * settlement with no scholar and a settlement that has finished every project
 * both still run — one is simply a little better at six things than the
 * other. The neutral value is always {@code 1.0F}.
 *
 * <p>Wiring a consumer to read one of these is integration work for whichever
 * worker owns that file ({@code Production}, a farm-growth goal, a guard
 * goal) — see {@code docs/project/PLAN_RESEARCH.md} §3 for the handoff. This
 * enum and {@link Research#bonus} exist and are tested independently of
 * whether anything reads them yet.
 */
public enum ResearchKey {
    /** Bedre Gjær: {@code Production.Recipe#ticks()} for BuildingType.BAKERY,
     *  AND (BALANCE_AUDIT.md finding 2's follow-up) the effort a batch
     *  costs, via {@code CrafterWorkGoal#researchEffortMultiplier}. */
    BAKERY_TICKS,
    /** Tørrsett Tømmer: {@code Production.Recipe#ticks()} for
     *  BuildingType.SAWMILL, and its batch effort cost — see
     *  {@link #BAKERY_TICKS}. */
    SAWMILL_TICKS,
    /** Blestring: {@code Production.Recipe#ticks()} for BuildingType.SMELTER,
     *  and its batch effort cost — see {@link #BAKERY_TICKS}. */
    SMELTER_TICKS,
    /** Garvesyre: {@code Production.Recipe#ticks()} for BuildingType.TANNERY,
     *  and its batch effort cost — see {@link #BAKERY_TICKS}. */
    TANNERY_TICKS,
    /** Åkerskifte: how often a farmed crop's growth is checked/advances. */
    FARM_GROWTH,
    /** Vaktdrill: the rate {@code Attribute.STRENGTH} trains for a GUARD. */
    GUARD_TRAINING
}

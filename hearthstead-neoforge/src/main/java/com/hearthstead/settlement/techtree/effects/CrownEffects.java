package com.hearthstead.settlement.techtree.effects;

import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechBonus;

/**
 * Crown branch effects (the rank spine). Written by the tech tree framework
 * lane as the WORKED EXAMPLE for the branch lanes: a flag for structural or
 * already-wired behaviour, and a {@link TechBonus} read by gameplay code.
 *
 * <p>Pattern, per node:
 * <ol>
 *   <li>Make the gameplay code read the node: {@code Development.has(level,
 *       settlement, "id")} or {@code TechTree.bonus(level, settlement, KEY)}.</li>
 *   <li>Register the effect here, naming the reader in {@code flag(...)}.</li>
 *   <li>Keep the node's {@code offers} in crown.json true to what 1+2 do.</li>
 *   <li>Add a GameTest in batch {@code techtree_crown}.</li>
 * </ol>
 * Never call {@code EffectRegistry.get()} from inside {@code register}.
 */
public final class CrownEffects {
    /** Extra Coins in every visiting merchant's purse, per rank charter. */
    public static final TechBonus MERCHANT_PURSE = TechBonus.of("crown.merchant_purse",
        "Visiting merchants carry %s more Coins");

    private CrownEffects() {
    }

    static void register(EffectRegistry r) {
        r.node("settlement_charter")
            .flag("TechTree.missingRequirement (root of every Hamlet node)",
                "Opens ring 1 of every branch");
        // Stamps itself (auto) once the first raid resolves; the Healer
        // emblem it used to carry moves to battle_healer (commons lane).
        r.node("first_raid_aftermath")
            .flag("TechTree.missingRequirement (root of the Village ring)",
                "Opens ring 2 of every branch")
            .flag("GearTier MAIL (node:first_raid_aftermath) via GearGate.owns",
                "Guards may wear mail (Gear Tier 1)");
        r.node("town_charter")
            .flag("TechTree.missingRequirement (root of the Town ring)",
                "Opens ring 3 of every branch")
            .bonus(MERCHANT_PURSE, 4);
        r.node("castle_charter")
            .flag("TechTree.missingRequirement (root of the Castle ring)",
                "Opens ring 4 of every branch")
            .flag("GearTier DIAMOND (node:castle_charter + node:master_armoury) via GearGate.owns",
                "With the Master Armoury: Gear Tier 3 (diamond)")
            .bonus(MERCHANT_PURSE, 4);
        r.node("kingdom_crown")
            .flag("HearthScreen/TechTreeScreen rank title", "Kingdom rank")
            .flag("GearTier NETHERITE (node:kingdom_crown + node:master_armoury) via GearGate.owns",
                "With the Master Armoury: Gear Tier 4 (netherite)")
            .bonus(MERCHANT_PURSE, 4);
    }
}

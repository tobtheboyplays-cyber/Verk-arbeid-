package com.hearthstead.building;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Heat as upkeep: which buildings burn, what counts as firewood, and what a
 * batch of work costs to keep the fire lit.
 *
 * <h2>Why fire is a cost at all</h2>
 *
 * <p>DESIGN.md's second pillar names the three upkeep flows outright —
 * <i>"food + firewood/warmth + tool wear"</i> (R20 restates it as a decided
 * requirement). Food already pulls the farmer's harvest through the hearth;
 * tool wear already pulls the smithy's output through every trade. Firewood
 * is the third leg: the buildings that work by FIRE — the smelter's forge,
 * the bakery's ovens, the smithy's hearth, the brewery's copper — now
 * consume fuel per finished batch, so heat is a real, physical input the
 * settlement has to keep supplying rather than scenery that burns for free.
 *
 * <h2>The lumberer's surplus becomes demand</h2>
 *
 * <p>Before this, a log had exactly one economic destination: the sawmill.
 * FLOWS.md's whole doctrine is that every source's output should be WANTED
 * somewhere, and the lumber camp's steady surplus was wanted only as planks.
 * Fuel gives wood a second standing consumer: raw logs burn as-is, and the
 * smelter refines a log surplus into charcoal — denser, courier-friendly
 * firewood the restock routes can carry to every burning building. The
 * lumberjack's pile stops being clutter and starts being the village's
 * warmth.
 *
 * <h2>Every fuel is player-obtainable on day one</h2>
 *
 * <p>Deliberately: logs come from punching the first tree, charcoal from
 * smelting logs (at the settlement's smelter or a vanilla furnace), coal
 * from the first night's mining. No fuel item is gated behind a building,
 * a trade or a tier, so a brand-new settlement can always light its first
 * fire — the same day-one honesty D-007 demands of every recipe's inputs.
 *
 * <h2>Frozen contract</h2>
 *
 * <p>Courier restocking (fuel as a standing workshop need) is wired against
 * exactly these three methods — {@link #burns}, {@link #perBatch},
 * {@link #isFuel}. Keep the signatures stable; widen behavior only here, in
 * one place, so the logistics layer and {@link Production}'s gate can never
 * disagree about what fire wants.
 *
 * <p>Where the consumed fuel goes: {@link Production#run} destroys it — the
 * one sanctioned item sink in the mod. See the INV-3 note there.
 */
public final class Fuel {

    /**
     * Whether this kind of building works by fire — and therefore whether
     * {@link Production} gates its recipes on fuel being in the building's
     * own chests.
     *
     * <p>The four burning trades: the smelter (forge), the bakery (ovens),
     * the smithy (forge for the anvil's stock) and the brewery (the boil).
     * The kitchen and dining hall are deliberately NOT here: food already
     * carries the hearth's upkeep story, and gating dinner on firewood would
     * double-charge the one flow a young settlement cannot skip.
     */
    public static boolean burns(BuildingType type) {
        return switch (type) {
            case SMELTER, BAKERY, SMITHY, BREWERY -> true;
            default -> false;
        };
    }

    /**
     * How many fuel ITEMS one finished batch consumes at a burning building
     * — one, for all four trades. Flat on purpose: a single log per batch is
     * an upkeep hum, not a second recipe cost, and a flat rate keeps the
     * courier's "how much firewood does this workshop want" arithmetic
     * trivial. Returns 0 for a building that does not burn.
     */
    public static int perBatch(BuildingType type) {
        return burns(type) ? 1 : 0;
    }

    /**
     * Whether this stack feeds a fire: charcoal, coal, or any log
     * ({@link ItemTags#LOGS} — every wood type, so no settlement's biome
     * locks it out of warmth). A kind test, not a count test — callers count
     * items themselves against {@link #perBatch}.
     */
    public static boolean isFuel(ItemStack stack) {
        return !stack.isEmpty()
            && (stack.is(Items.CHARCOAL)
                || stack.is(Items.COAL)
                || stack.is(ItemTags.LOGS));
    }

    private Fuel() {
    }
}

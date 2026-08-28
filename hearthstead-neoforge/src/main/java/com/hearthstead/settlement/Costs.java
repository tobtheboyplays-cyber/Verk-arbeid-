package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONE table for every price in the mod, and the discount engine that
 * makes {@code docs/project/COSTS.md} real instead of aspirational.
 *
 * <p>COSTS.md is the pricing constitution and sets three laws. This class
 * exists to enforce all three in code, not just in prose:
 * <ol>
 *   <li><b>Pay in what the thing is made of.</b> Every {@link Price} is an
 *       ordered list of {@link Line}s, each a real item (or item tag) and a
 *       count -- never an abstract point cost.</li>
 *   <li><b>Bygda hjelper til.</b> {@link #discountsFor} returns the NAMED
 *       discount hooks a settlement has earned by building the right things
 *       (an innkeeper on shift, a dining hall, a library...), each carrying
 *       its own translate key so the UI can print exactly what COSTS.md's
 *       "UI rule" demands: "Bygdas pris: 2 brød + 4 planker (Vertshusholderen
 *       -25%, Spisesalen -25%)". {@link #afterDiscounts} sums them and caps
 *       the total at -50%, never deeper, regardless of how many hooks stack.</li>
 *   <li><b>First one cheap, the rest honest.</b> Not this class's job for any
 *       price defined here today (recruiting has no first-purchase hook in
 *       COSTS.md's own Recruiting table) -- noted so nobody assumes it is
 *       missing by accident.</li>
 * </ol>
 *
 * <p>Every price that exists in the mod is meant to be requested from here,
 * never hard-coded at the call site -- COSTS.md's own implementation map:
 * "No number may live hard-coded in a goal once Costs.java exists."
 * {@link SettlementManager} charges {@link #recruit()} (plus the
 * {@link PriceKey#RECRUIT} discount hooks); {@link Mayor#appoint} charges
 * {@link #mayorFeast()} (plus {@link PriceKey#MAYOR_FEAST}) on an actual
 * swap. {@link PriceKey#REPAIR}'s two hooks are real too, but not through
 * this table's usual {@code Price}/{@code Line} machinery -- see that key's
 * own doc for why a settlement-level price can't apply to a per-block dugnad,
 * and {@code RepairWorkGoal} for where the discount is actually spent.
 * {@link PriceKey#RESEARCH} still reserves its row for research's not-yet-built
 * slice, so it asks here on day one rather than inventing its own number the
 * way recruiting once did.
 */
public final class Costs {

    /** Which priced thing this is -- COSTS.md's own price-table headings. */
    public enum PriceKey {
        /**
         * Recruiting a waiting guest (COSTS.md "Recruiting"). The only key
         * with a real caller today: {@link SettlementManager#tickWaitingTraveler}.
         */
        RECRUIT,
        /**
         * Starting a research project (COSTS.md "Research"). Not charged
         * anywhere yet -- the research project tables are still in flight
         * per COSTS.md's own implementation map -- but the base price and its
         * library discount already live here so that slice reads a price
         * instead of inventing one.
         */
        RESEARCH,
        /**
         * Appointing a new mayor while one already sits (COSTS.md "Mayor
         * swap: the feast"). Charged in {@code Mayor.appoint} via
         * {@link #mayorFeast()} -- but only on an actual swap; COSTS.md's own
         * "first appointment free" means an empty seat charges nothing, so
         * this key's discount hooks and price never even get asked for when
         * there was no previous mayor to hand the feast to.
         */
        MAYOR_FEAST,
        /**
         * Raid-damage repair dugnad (COSTS.md "Repairs after raids"). The
         * dugnad consumes one real material per scar, per block, from the
         * nearest store ({@code RepairWorkGoal}) -- there is no
         * settlement-level {@link Price} to shave a percentage off of, so
         * the mason -25% / sawmill -25% hooks below cannot mean "fewer
         * blocks pay" the way {@link #discounted} means it for a
         * {@link Line}. Balance decision, 2026-08-26: they mean <b>"some
         * scars mend free"</b> instead -- chest truth survives untouched
         * (fewer items ever leave a chest; nothing is conjured and no item
         * is ever partially consumed), it reads in the world (a wall knits
         * itself with no courier delivering for it, which is what having a
         * mason in the village should feel like), and it is deterministic
         * rather than a coin flip a currently-flaky suite cannot afford:
         * {@code RepairWorkGoal} keeps a running per-settlement count of
         * scars actually mended and waives the material on every
         * {@code (100 / discountPercent(discountsFor(..., REPAIR)))}th one --
         * every 4th scar at the capped 25% (one hook), every 2nd at the
         * capped 50% (both hooks). Both divisions are exact for every sum
         * these two 25%-hooks can ever produce, so there is no rounding
         * question hiding in the cadence.
         */
        REPAIR
    }

    /**
     * One line of a price: an exact item, OR any item carrying a tag --
     * never both. The same tag-or-exact shape RECRUIT-1 proved out for
     * planks (Byggherre-dom #1, krav 8: a birch- or spruce-founded
     * settlement must be able to pay too), generalized so every future price
     * line gets it for free.
     */
    public record Line(@Nullable TagKey<Item> tag, @Nullable Item exact, int count) {
        public static Line of(Item exact, int count) {
            return new Line(null, exact, count);
        }

        public static Line ofTag(TagKey<Item> tag, int count) {
            return new Line(tag, null, count);
        }

        boolean matches(ItemStack stack) {
            return tag != null ? stack.is(tag) : stack.is(exact);
        }

        /** Same line, a different count -- never below 1 (COSTS.md's discount floor). */
        Line withCount(int newCount) {
            return new Line(tag, exact, Math.max(1, newCount));
        }
    }

    /** An ordered list of {@link Line}s -- what something costs, in full. */
    public record Price(PriceKey key, List<Line> lines) {
    }

    /**
     * One named, itemized discount hook (COSTS.md law #2, "bygda hjelper
     * til"). {@code translationKey} is the UI's lang key for the whole
     * "<name> -25%" line (e.g. {@code "hearthstead.discount.innkeeper"} ->
     * en_us {@code "Innkeeper's bargain: -%d%%"}, formatted with
     * {@code percent}); {@code reason} is the plain-English explanation for
     * javadoc, logs and tests -- never shown to the player, who reads the
     * translated line instead.
     */
    public record Discount(String translationKey, int percent, String reason) {
    }

    /** COSTS.md law #2: discounts stack, but never past here. */
    public static final int DISCOUNT_CAP_PERCENT = 50;

    // ---------------------------------------------------------- prices ---

    /**
     * What joining a settlement costs, in village-grown goods (DESIGN.md
     * system 8: "recruit by paying a price in village-grown goods"). Base
     * price, before any discount -- see {@link #discountsFor} for what can
     * lower it and {@link #afterDiscounts} for applying that.
     *
     * <p><b>Why bread and planks.</b> Bread is what every settlement has from
     * its first harvest — three founders with a farmhouse can pay it before
     * their first traveler even arrives. Oak planks are the one good stacked
     * on top: cheap enough that an afternoon at the sawmill (or a player's
     * own axe and crafting table) buries the cost completely, but a
     * settlement with no production running yet has to genuinely wait and
     * stock up first. Wool would have made the same point, but it needs a
     * weaver AND sheep, which is a taller order than this slice's "young
     * settlement can still just about afford it" is aiming for. Together the
     * two items are a price a subsistence camp feels and a thriving
     * settlement never notices — which is exactly the shape a "price" is
     * supposed to have here.
     *
     * <p>ANY planks, not oak specifically: a settlement founded in a birch or
     * spruce forest could literally never recruit under an exact-item match,
     * which makes no sense to the player standing in it. Bread stays exact —
     * bread is bread.
     */
    public static Price recruit() {
        return of(PriceKey.RECRUIT,
            Line.of(Items.BREAD, 4),
            Line.ofTag(ItemTags.PLANKS, 8));
    }

    /**
     * The handover feast a settlement pays to appoint a NEW mayor while one
     * already sits (COSTS.md "Mayor swap: the feast"). {@code Mayor.appoint}
     * only ever asks for this on an actual swap -- the first appointment to
     * an empty seat is free per the same section of COSTS.md, so it never
     * calls here at all in that case.
     */
    public static Price mayorFeast() {
        return of(PriceKey.MAYOR_FEAST, Line.of(Items.BREAD, 8));
    }

    /**
     * The generic factory every future price is meant to use, so no price
     * anywhere in the mod is ever assembled by hand at its call site again.
     */
    public static Price of(PriceKey key, Line... lines) {
        return new Price(key, List.of(lines));
    }

    // -------------------------------------------------------- discounts ---

    /**
     * The named discount hooks this settlement has earned toward
     * {@code key}'s price right now, itemized for the UI. Every hook here is
     * read live off the settlement's own buildings and workers -- never a
     * cached flag -- the same "the worker list is the only record" invariant
     * {@link Employment} documents for employment itself.
     *
     * <p>{@code level} is accepted (rather than only {@code Settlement}) so a
     * future time-of-day or seasonal hook never has to change this method's
     * shape to add one -- unused by every hook that exists today.
     */
    public static List<Discount> discountsFor(ServerLevel level, Settlement s, PriceKey key) {
        List<Discount> out = new ArrayList<>();
        switch (key) {
            case RECRUIT -> {
                // Hospitality is the innkeeper's trade -- an employed
                // innkeeper haggles the price down the same way a staffed
                // tavern already speeds up the recruit gauge
                // (SettlementManager#tickRecruitment). Read straight off
                // Building#workers, never a flag kept in step by hand.
                Building tavern = firstValid(s, BuildingType.TAVERN);
                if (tavern != null && !tavern.workers.isEmpty()) {
                    out.add(new Discount("hearthstead.discount.innkeeper", 25,
                        "an innkeeper is employed and on shift"));
                }
                // A village that eats together convinces a guest to move in
                // for less -- no worker required, just a valid dining hall.
                if (firstValid(s, BuildingType.DINING_HALL) != null) {
                    out.add(new Discount("hearthstead.discount.dining_hall", 25,
                        "a dining hall exists and is valid"));
                }
            }
            case RESEARCH -> {
                // COSTS.md "library registered -25% materials". The
                // scholar's WITS-tier work-day reduction named alongside it
                // is a duration mechanic, not a material price, so it is not
                // modeled as a Discount here -- it belongs to whatever the
                // research slice does with scholar attributes directly.
                if (firstValid(s, BuildingType.LIBRARY) != null) {
                    out.add(new Discount("hearthstead.discount.library", 25,
                        "a library exists and is valid"));
                }
            }
            case MAYOR_FEAST -> {
                // COSTS.md "Hook: dining hall registered -50% (the feast is
                // cheaper where feasts are normal)". A single hook already
                // sitting at the cap -- documented so nobody "helpfully"
                // stacks a second one later and expects it to matter.
                if (firstValid(s, BuildingType.DINING_HALL) != null) {
                    out.add(new Discount("hearthstead.discount.mayor_feast_dining_hall", 50,
                        "a dining hall exists and is valid"));
                }
            }
            case REPAIR -> {
                // COSTS.md "mason registered -25% stone costs, sawmill -25%
                // wood costs". Spent by RepairWorkGoal as "some scars mend
                // free" rather than as a Price/Line reduction -- see
                // PriceKey#REPAIR's own doc for why -- but the two hooks
                // themselves are named and read live off the settlement's
                // buildings exactly like every other hook here.
                if (firstValid(s, BuildingType.MASON) != null) {
                    out.add(new Discount("hearthstead.discount.mason", 25,
                        "a mason is registered and valid"));
                }
                if (firstValid(s, BuildingType.SAWMILL) != null) {
                    out.add(new Discount("hearthstead.discount.sawmill", 25,
                        "a sawmill is registered and valid"));
                }
            }
        }
        return out;
    }

    /**
     * {@code base} with every {@code discounts} percentage applied.
     *
     * <p><b>Additive, capped at {@link #DISCOUNT_CAP_PERCENT}.</b> COSTS.md's
     * own worked example ("innkeeper -25%, dining hall -25%" landing on
     * "Floor at -50%: 2 bread + 4 planks", exactly half of 4 bread + 8
     * planks) is only reproduced by summing percentages (25 + 25 = 50); a
     * multiplicative stack of the same two hooks would land at -43.75%, half
     * a line short of what the constitution promises. So: sum every
     * discount's percent, clamp the total to
     * {@code [0, DISCOUNT_CAP_PERCENT]}, then apply that one percentage to
     * every line.
     *
     * <p>Rounds in the player's favour (the discount amount rounds UP, so
     * the price rounds DOWN) and never takes a line below 1, exactly as
     * COSTS.md's "never below 1 of any line" requires -- enforced by
     * {@link Line#withCount}, the one place that floor is applied, so it can
     * never be bypassed by a future call site.
     */
    /**
     * The single capped percentage a discount list comes to, for callers that
     * price in their own currency rather than in {@link Line}s.
     *
     * <p>Research is the first of those: its costs are
     * {@code ResearchProject.Cost} records fixed at enum-construction time,
     * not a {@link Price} this table owns. Exposing the percentage lets it
     * obey the same cap and the same rounding without either side copying
     * the arithmetic — which is the whole point of one price table.
     */
    public static int discountPercent(List<Discount> discounts) {
        int percent = 0;
        for (Discount d : discounts) {
            percent += d.percent();
        }
        return Math.max(0, Math.min(DISCOUNT_CAP_PERCENT, percent));
    }

    /** One line's count after a capped percentage, player's favour, floor 1. */
    public static int discounted(int count, int percent) {
        if (percent <= 0) {
            return count;
        }
        int off = (count * percent + 99) / 100; // ceil -- favours the player
        return Math.max(1, count - off);
    }

    public static Price afterDiscounts(Price base, List<Discount> discounts) {
        int percent = discountPercent(discounts);
        if (percent == 0) {
            return base;
        }
        List<Line> lines = new ArrayList<>(base.lines().size());
        for (Line line : base.lines()) {
            int off = (line.count() * percent + 99) / 100; // ceil -- favours the player
            lines.add(line.withCount(line.count() - off));
        }
        return new Price(base.key(), List.copyOf(lines));
    }

    // ---------------------------------------------------------- payment ---

    /** Whether {@code inventory} holds every line of {@code price} right now. */
    public static boolean canPay(ItemStackHandler inventory, Price price) {
        for (Line line : price.lines()) {
            if (countMatching(inventory, line) < line.count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deducts {@code price} from {@code inventory}. Only ever call after
     * {@link #canPay} said yes -- this does not check.
     */
    public static void pay(ItemStackHandler inventory, Price price) {
        for (Line line : price.lines()) {
            extractMatching(inventory, line, line.count());
        }
    }

    private static int countMatching(ItemStackHandler inventory, Line line) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && line.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Mixed stacks pay together: 5 birch + 3 spruce planks are 8 planks. */
    private static void extractMatching(ItemStackHandler inventory, Line line, int amount) {
        for (int slot = 0; slot < inventory.getSlots() && amount > 0; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && line.matches(stack)) {
                int take = Math.min(amount, stack.getCount());
                stack.shrink(take);
                inventory.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                amount -= take;
            }
        }
    }

    // -------------------------------------------------------- buildings ---

    /** The first valid building of {@code type} in {@code s}, or null. */
    @Nullable
    private static Building firstValid(Settlement s, BuildingType type) {
        for (Building b : s.buildings) {
            if (b.valid && b.type == type) {
                return b;
            }
        }
        return null;
    }

    private Costs() {
    }
}

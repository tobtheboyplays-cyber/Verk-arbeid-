package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One server-authoritative answer to "may this settlement recruit now?".
 * Attraction, admission, the hearth UI and food couriers consume the same
 * {@link Assessment}; changing a gate here changes every surface together.
 */
public final class RecruitmentPolicy {
    public static final int MINECRAFT_DAY_SECONDS = 1_200;
    public static final int MIN_QUALIFIED_SECONDS = 2 * MINECRAFT_DAY_SECONDS;
    public static final int MAX_TARGET_SECONDS = 4 * MINECRAFT_DAY_SECONDS;
    public static final int MEALS_PER_PERSON_PER_DAY = 4;
    public static final int RESERVE_DAYS = 2;
    public static final int MEALS_PER_NEXT_RESIDENT =
        MEALS_PER_PERSON_PER_DAY * RESERVE_DAYS;

    /** Explicit protocol ids. Never serialize or sync enum ordinals. */
    public enum Stage {
        ATTRACTION(0),
        WAITING_ADMISSION(1),
        INVALID(2);

        private final int wireId;

        Stage(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Stage fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> ATTRACTION;
                case 1 -> WAITING_ADMISSION;
                default -> INVALID;
            };
        }
    }

    /** Explicit protocol ids and a fail-closed unknown value. */
    public enum Blocker {
        NONE(0),
        NO_HEARTH(1),
        NO_TAVERN(2),
        NO_BED(3),
        LOW_MORALE(4),
        CANNOT_PAY(5),
        INSUFFICIENT_READY_FOOD(6),
        INVALID_STATE(7);

        private final int wireId;

        Blocker(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Blocker fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> NONE;
                case 1 -> NO_HEARTH;
                case 2 -> NO_TAVERN;
                case 3 -> NO_BED;
                case 4 -> LOW_MORALE;
                case 5 -> CANNOT_PAY;
                case 6 -> INSUFFICIENT_READY_FOOD;
                default -> INVALID_STATE;
            };
        }
    }

    /** Immutable snapshot safe to reuse for runtime, menu data and tests. */
    public record Assessment(
        Stage stage,
        Blocker blocker,
        Costs.Price price,
        int readyFoodBeforePrice,
        int readyFoodAfterPrice,
        int requiredReadyFood,
        int missingReadyFood,
        boolean canPay,
        int courierReadyFoodTarget
    ) {
        public boolean eligible() {
            return blocker == Blocker.NONE;
        }
    }

    /**
     * One price line the hearth still cannot pay with ready food. Keeping the
     * line itself preserves exact-item and tag semantics: twenty potatoes are
     * twenty meals, but cannot replace four bread — while a future edible tag
     * may be supplied by any concrete matching meal a warehouse really has.
     */
    public record ReadyPriceNeed(Costs.Line line, int missing) {
        public boolean matches(ItemStack stack) {
            return ReadyFood.isReadyMeal(stack) && line.matches(stack);
        }
    }

    public static Stage stageFor(Settlement settlement) {
        return settlement.travelerId == null ? Stage.ATTRACTION : Stage.WAITING_ADMISSION;
    }

    /**
     * Assesses without touching live inventory. The discounted price is
     * paid only on a deep clone, then the two-day reserve is counted from
     * the resulting stacks using {@link ReadyFood}'s eating predicate.
     */
    public static Assessment assess(ServerLevel level, Settlement settlement, Stage stage) {
        Costs.Price price = SettlementManager.recruitPrice(level, settlement);
        int required = requiredReserve(settlement.population() + 1);
        HearthBlockEntity hearth = hearth(level, settlement);
        if (hearth == null) {
            int courierTarget = addSaturated(required,
                readyFoodPriceExposure(new ItemStackHandler(0), price));
            return new Assessment(stage, Blocker.NO_HEARTH, price,
                0, 0, required, required, false, courierTarget);
        }

        ItemStackHandler simulated = ReadyFood.copy(hearth.getInventory());
        int before = ReadyFood.count(simulated);
        boolean canPay = Costs.canPay(simulated, price);
        if (canPay) {
            Costs.pay(simulated, price);
        }
        int after = ReadyFood.count(simulated);
        int missing = Math.max(0, required - after);
        int courierTarget = addSaturated(required,
            readyFoodPriceExposure(hearth.getInventory(), price));

        Blocker blocker;
        if (stage == Stage.INVALID) {
            blocker = Blocker.INVALID_STATE;
        } else if (stage == Stage.ATTRACTION
            && !SettlementManager.hasValidTavern(settlement)) {
            blocker = Blocker.NO_TAVERN;
        } else if (settlement.population() >= settlement.capacity()) {
            blocker = Blocker.NO_BED;
        } else if (stage == Stage.ATTRACTION && settlement.moraleCache < 60) {
            blocker = Blocker.LOW_MORALE;
        } else if (!canPay) {
            blocker = Blocker.CANNOT_PAY;
        } else if (missing > 0) {
            blocker = Blocker.INSUFFICIENT_READY_FOOD;
        } else {
            blocker = Blocker.NONE;
        }
        return new Assessment(stage, blocker, price, before, after,
            required, missing, canPay, courierTarget);
    }

    /** Two whole days for the supplied post-recruit population. */
    public static int requiredReserve(int populationAfterRecruit) {
        if (populationAfterRecruit <= 0) {
            return 0;
        }
        long meals = (long) MEALS_PER_NEXT_RESIDENT * populationAfterRecruit;
        return meals > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) meals;
    }

    /** Compatibility/test helper when no priced inventory is in scope. */
    public static int baseCourierTarget(int currentPopulation) {
        return requiredReserve(currentPopulation + 1);
    }

    /**
     * Counts ready meals a successful payment may consume, independently per
     * price line. Exact edible lines are known from the item itself; tagged
     * lines use matching live stacks. A missing non-food line must not hide
     * bread exposure from the courier target.
     */
    static int readyFoodPriceExposure(ItemStackHandler inventory, Costs.Price price) {
        int exposure = 0;
        for (Costs.Line line : price.lines()) {
            // Exact edible lines (today: bread) are known exposure even
            // before the first loaf reaches the hearth; otherwise a courier
            // could stop at the reserve and only then discover the price
            // would eat into it.
            if (line.exact() != null
                && ReadyFood.isReadyMeal(line.exact().getDefaultInstance())) {
                exposure += line.count();
                continue;
            }
            int remaining = line.count();
            for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (ReadyFood.isReadyMeal(stack) && line.matches(stack)) {
                    int counted = Math.min(remaining, stack.getCount());
                    exposure += counted;
                    remaining -= counted;
                }
            }
        }
        return exposure;
    }

    /**
     * Returns every price line still missing ready-food matches, in price
     * order. Exact items known to be non-food are skipped. Tag lines remain
     * candidates even when the hearth has no matching stack: the warehouse
     * scan can safely prove whether a concrete, edible tag member exists and
     * reserve that physical stack. This avoids a scalar-target blind spot for
     * future edible tag prices without guessing which tag member to mint.
     */
    public static List<ReadyPriceNeed> missingReadyFoodPrices(
        ItemStackHandler inventory, Costs.Price price
    ) {
        List<ReadyPriceNeed> needs = new ArrayList<>();
        for (Costs.Line line : price.lines()) {
            if (line.exact() != null
                && !ReadyFood.isReadyMeal(line.exact().getDefaultInstance())) {
                continue;
            }
            int present = 0;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (ReadyFood.isReadyMeal(stack) && line.matches(stack)) {
                    present = addSaturated(present, stack.getCount());
                }
            }
            if (present < line.count()) {
                needs.add(new ReadyPriceNeed(line, line.count() - present));
            }
        }
        return List.copyOf(needs);
    }

    private static int addSaturated(int left, int right) {
        long sum = (long) left + right;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /** Stable settlement/cycle-specific duration in the inclusive 2–4 day range. */
    public static int targetFor(UUID settlementId, int cycle) {
        long z = settlementId.getMostSignificantBits()
            ^ Long.rotateLeft(settlementId.getLeastSignificantBits(), 29)
            ^ (0x9E3779B97F4A7C15L * (Math.max(0, cycle) + 1L));
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        int span = MAX_TARGET_SECONDS - MIN_QUALIFIED_SECONDS + 1;
        return MIN_QUALIFIED_SECONDS + (int) Long.remainderUnsigned(z, span);
    }

    @Nullable
    public static HearthBlockEntity hearth(ServerLevel level, Settlement settlement) {
        if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)) {
            return null;
        }
        return settlement.id.equals(hearth.getSettlementId()) ? hearth : null;
    }

    private RecruitmentPolicy() {
    }
}

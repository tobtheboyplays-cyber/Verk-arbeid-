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
    /** New ordinary locks: three to six qualified minutes at normal 20 TPS. */
    public static final int MIN_QUALIFIED_SECONDS = 3 * 60;
    public static final int MAX_TARGET_SECONDS = 6 * 60;
    // Exact historical locks remain readable and retain all earned time and identity.
    private static final int LEGACY_10_TO_20_MIN_QUALIFIED_SECONDS = 10 * 60;
    private static final int LEGACY_10_TO_20_MAX_TARGET_SECONDS = 20 * 60;
    private static final int LEGACY_LONG_MIN_QUALIFIED_SECONDS = 2 * MINECRAFT_DAY_SECONDS;
    private static final int LEGACY_LONG_MAX_TARGET_SECONDS = 4 * MINECRAFT_DAY_SECONDS;
    /** The one Journey-gated Call to Arms window for the Watch recruit. */
    public static final int CALL_TO_ARMS_MIN_SECONDS = 4 * 60;
    public static final int CALL_TO_ARMS_MAX_SECONDS = 8 * 60;
    public static final int MEALS_PER_PERSON_PER_DAY = 4;
    public static final int RESERVE_DAYS = 2;
    public static final int MEALS_PER_NEXT_RESIDENT =
        MEALS_PER_PERSON_PER_DAY * RESERVE_DAYS;
    /** A Tavern may invite a visitor with one real meal; full newcomer reserves belong to admission. */
    public static final int MIN_VISITOR_READY_MEALS = 1;

    /** Explicit protocol ids. Never serialize or sync enum ordinals. */
    public enum Stage {
        ATTRACTION(0),
        WAITING_ADMISSION(1),
        INVALID(2),
        QUALIFYING(3),
        TRAVELING(4),
        /** A nightly Tavern visit needs a valid physical Tavern only; admission remains gated. */
        TAVERN_VISIT(5);

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
                case 3 -> QUALIFYING;
                case 4 -> TRAVELING;
                case 5 -> TAVERN_VISIT;
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

    /**
     * Persisted timing identity. Target values are intentionally not enough:
     * the new ordinary 180-360 second range overlaps Call to Arms at 240-480.
     */
    public enum TimingProfile {
        ORDINARY_48H(0),
        CALL_TO_ARMS(1),
        LEGACY_10_TO_20(2),
        LEGACY_LONG(3),
        UNKNOWN(-1);

        private final int wireId;

        TimingProfile(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static TimingProfile fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> ORDINARY_48H;
                case 1 -> CALL_TO_ARMS;
                case 2 -> LEGACY_10_TO_20;
                case 3 -> LEGACY_LONG;
                default -> UNKNOWN;
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
        if (settlement == null || settlement.recruitment == null) {
            return Stage.INVALID;
        }
        return switch (settlement.recruitment.status()) {
            case ATTRACTING, ADMITTED, LEFT -> Stage.ATTRACTION;
            case QUALIFYING, READY_TO_SPAWN -> Stage.QUALIFYING;
            case TRAVELING -> Stage.TRAVELING;
            case WAITING_ADMISSION -> Stage.WAITING_ADMISSION;
            case QUARANTINED, UNKNOWN -> Stage.INVALID;
        };
    }

    /**
     * Assesses without touching live inventory. The discounted price is
     * paid only during explicit admission. A Tavern invite needs one real meal;
     * the two-day newcomer reserve is enforced only when that guest is admitted.
     */
    public static Assessment assess(ServerLevel level, Settlement settlement, Stage stage) {
        return assess(level, settlement, stage, null);
    }

    public static Assessment assess(ServerLevel level, Settlement settlement, Stage stage,
            net.minecraft.server.level.ServerPlayer payer) {
        Costs.Price price = SettlementManager.recruitPrice(level, settlement);
        int fullReserve = requiredReserve(settlement.population() + 1);
        boolean admission = stage == Stage.WAITING_ADMISSION;
        boolean nightlyVisit = stage == Stage.TAVERN_VISIT;
        // A nightly traveler may ask for food but is never blocked by it. The
        // full reserve, bed, morale and paid Coin checks remain admission-only.
        int required = admission ? fullReserve : nightlyVisit ? 0 : MIN_VISITOR_READY_MEALS;
        HearthBlockEntity hearth = hearth(level, settlement);
        if (hearth == null) {
            int courierTarget = addSaturated(fullReserve,
                readyFoodPriceExposure(new ItemStackHandler(0), price));
            return new Assessment(stage, Blocker.NO_HEARTH, price,
                0, 0, required, required, false, courierTarget);
        }

        ItemStackHandler simulated = ReadyFood.copy(hearth.getInventory());
        int before = ReadyFood.count(simulated);
        boolean coinPrice = Costs.isCoinPrice(price);
        // A visitor may travel to a valid Tavern before the village owns the
        // recruitment Coins. Only the player's explicit admission can pay.
        boolean canPay = !admission || (coinPrice
            ? Costs.canPay(CoinTreasury.open(level, settlement, hearth, payer), price)
            : Costs.canPay(simulated, price));
        if (admission && canPay && !coinPrice) {
            Costs.pay(simulated, price);
        }
        int after = ReadyFood.count(simulated);
        int missing = Math.max(0, required - after);
        int courierTarget = addSaturated(fullReserve,
            readyFoodPriceExposure(hearth.getInventory(), price));

        Blocker blocker;
        if (stage == Stage.INVALID) {
            blocker = Blocker.INVALID_STATE;
        } else if (!SettlementManager.hasValidTavern(settlement)) {
            blocker = Blocker.NO_TAVERN;
        } else if (admission && (settlement.population() >= settlement.capacity()
                || BuildingManager.findFreeBed(level, settlement) == null)) {
            blocker = Blocker.NO_BED;
        } else if (!nightlyVisit && settlement.moraleCache < 60) {
            blocker = Blocker.LOW_MORALE;
        } else if (admission && !canPay) {
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

    /** The minimum for newly started ordinary recruitment. */
    public static int minimumFor(int cycle) {
        return MIN_QUALIFIED_SECONDS;
    }

    /** Compatibility decoder for pre-v3 records whose target ranges were disjoint. */
    public static int minimumFor(UUID settlementId, int cycle,
                                 int lockedTarget) {
        TimingProfile profile = legacyTimingProfileFor(settlementId, cycle, lockedTarget);
        return minimumFor(profile, settlementId, cycle, lockedTarget);
    }

    /** Exact current/historical ordinary target only; loose legacy data cannot claim the Watch window. */
    public static int minimumForOrdinaryTarget(UUID settlementId, int cycle, int lockedTarget) {
        if (cycle < 0) return -1;
        if (lockedTarget == legacy10To20TargetFor(settlementId, cycle)) {
            return LEGACY_10_TO_20_MIN_QUALIFIED_SECONDS;
        }
        return lockedTarget == legacyLongTargetFor(settlementId, cycle)
            ? LEGACY_LONG_MIN_QUALIFIED_SECONDS : -1;
    }

    public static boolean validLockedTarget(UUID settlementId, int cycle,
                                            int lockedTarget) {
        return minimumFor(settlementId, cycle, lockedTarget) > 0;
    }

    public static boolean validLockedTarget(TimingProfile profile,
                                            UUID settlementId, int cycle,
                                            int lockedTarget) {
        return minimumFor(profile, settlementId, cycle, lockedTarget) > 0;
    }

    public static int minimumFor(TimingProfile profile, UUID settlementId,
                                 int cycle, int lockedTarget) {
        if (profile == null || cycle < 0) return -1;
        return switch (profile) {
            case ORDINARY_48H -> lockedTarget == ordinaryTargetFor(settlementId, cycle)
                ? MIN_QUALIFIED_SECONDS : -1;
            case CALL_TO_ARMS -> lockedTarget == callToArmsTargetFor(settlementId, cycle)
                ? CALL_TO_ARMS_MIN_SECONDS : -1;
            case LEGACY_10_TO_20 -> lockedTarget == legacy10To20TargetFor(settlementId, cycle)
                ? LEGACY_10_TO_20_MIN_QUALIFIED_SECONDS : -1;
            case LEGACY_LONG -> lockedTarget == legacyLongTargetFor(settlementId, cycle)
                ? LEGACY_LONG_MIN_QUALIFIED_SECONDS : -1;
            case UNKNOWN -> -1;
        };
    }

    /** Exact decoding for schema v1/v2 transactions written before range overlap. */
    public static TimingProfile legacyTimingProfileFor(UUID settlementId,
                                                        int cycle,
                                                        int lockedTarget) {
        if (cycle < 0) return TimingProfile.UNKNOWN;
        if (lockedTarget == legacy10To20TargetFor(settlementId, cycle)) {
            return TimingProfile.LEGACY_10_TO_20;
        }
        if (lockedTarget == legacyLongTargetFor(settlementId, cycle)) {
            return TimingProfile.LEGACY_LONG;
        }
        return lockedTarget == callToArmsTargetFor(settlementId, cycle)
            ? TimingProfile.CALL_TO_ARMS : TimingProfile.UNKNOWN;
    }

    /**
     * Stable ordinary settlement/cycle duration. Call to Arms is selected
     * explicitly by the post-FJ-552 Journey gate, never by this raw counter.
     */
    public static int targetFor(UUID settlementId, int cycle) {
        return ordinaryTargetFor(settlementId, cycle);
    }

    /** Deterministic four-to-eight-minute lock for the active Watch slot. */
    public static int callToArmsTargetFor(UUID settlementId, int cycle) {
        long z = targetHash(settlementId, Math.max(0, cycle));
        int span = CALL_TO_ARMS_MAX_SECONDS - CALL_TO_ARMS_MIN_SECONDS + 1;
        return CALL_TO_ARMS_MIN_SECONDS
            + (int) Long.remainderUnsigned(z, span);
    }

    private static int ordinaryTargetFor(UUID settlementId, int cycle) {
        long z = targetHash(settlementId, Math.max(0, cycle));
        int span = MAX_TARGET_SECONDS - MIN_QUALIFIED_SECONDS + 1;
        return MIN_QUALIFIED_SECONDS + (int) Long.remainderUnsigned(z, span);
    }

    /** Decode compatibility only: never selected for a new qualification. */
    private static int legacy10To20TargetFor(UUID settlementId, int cycle) {
        long z = targetHash(settlementId, Math.max(0, cycle));
        int span = LEGACY_10_TO_20_MAX_TARGET_SECONDS
            - LEGACY_10_TO_20_MIN_QUALIFIED_SECONDS + 1;
        return LEGACY_10_TO_20_MIN_QUALIFIED_SECONDS
            + (int) Long.remainderUnsigned(z, span);
    }

    private static int legacyLongTargetFor(UUID settlementId, int cycle) {
        long z = targetHash(settlementId, Math.max(0, cycle));
        int span = LEGACY_LONG_MAX_TARGET_SECONDS - LEGACY_LONG_MIN_QUALIFIED_SECONDS + 1;
        return LEGACY_LONG_MIN_QUALIFIED_SECONDS + (int) Long.remainderUnsigned(z, span);
    }

    private static long targetHash(UUID settlementId, int safeCycle) {
        long z = settlementId.getMostSignificantBits()
            ^ Long.rotateLeft(settlementId.getLeastSignificantBits(), 29)
            ^ (0x9E3779B97F4A7C15L * (safeCycle + 1L));
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return z;
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

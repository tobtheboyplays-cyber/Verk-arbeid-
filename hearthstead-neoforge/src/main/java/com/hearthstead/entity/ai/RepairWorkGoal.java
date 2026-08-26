package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The repair dugnad (SLICE REPAIR-1): after a raid, settlers work the scar
 * queue {@link RaidDirector} recorded, walking to each wound and restoring
 * the exact block that stood there.
 *
 * <p>This is the missing half of DESIGN.md system 5's aftermath ("repair
 * dugnad + defense report") and the permanent invariant's positive side:
 * settlers never construct buildings autonomously — <b>they repair raid
 * damage</b>, and restoring a recorded {@link RaidDirector.Scar} verbatim
 * is provably repair, never construction. It is also the mason's first
 * real sink (FLOWS.md: mason → "repairs (raids!)"): until now her bricks
 * piled up with no consumer anywhere in the economy.
 *
 * <h2>Who joins</h2>
 *
 * <p>The MASON always — this is her trade's civic duty, and while scars
 * exist it outranks her ordinary bench work. Any UNEMPLOYED settler joins
 * too: a dugnad is the whole village turning out, and the idle are exactly
 * the hands it has to spare. (There is no child/adult distinction among
 * settlers today; if one lands, the dugnad is adults'.) Employed
 * non-masons keep their own trades — the village must not stop feeding
 * itself to patch a wall.
 *
 * <h2>Chest truth</h2>
 *
 * <p>No material, no repair. One matching item is consumed per scar, from
 * the settler's OWN building's chests or the communal hearth — never
 * conjured. Matching means the original block's own item first, then an
 * honest substitute: any plank for wooden blocks, cobble/stone/stone
 * bricks for stone blocks (see {@link #fallbackFor}). Availability is
 * checked before a scar is claimed and the item is taken only at the
 * moment the block is restored, both against live containers — this goal
 * never remembers a count.
 *
 * <h2>The mason and sawmill discounts: some scars mend free</h2>
 *
 * <p>COSTS.md's mason -25% / sawmill -25% repair hooks ({@code Costs.PriceKey#REPAIR})
 * cannot shave a percentage off a {@code Costs.Price} the way recruiting's
 * hooks do — there is no settlement-level price here, only one real item per
 * block. Balance decision, 2026-08-26: they instead waive the material
 * entirely on some scars, deterministically. {@link #SCAR_MENDS} counts real
 * completed repairs per settlement; {@link #shouldMendFree} waives the one
 * ({@code 100 / discountPercent})th mend — every 4th with one hook (25%),
 * every 2nd with both (the capped 50%) — so the same settlement in the same
 * state always gets the same answer. This stays chest-true (fewer items ever
 * leave a chest; nothing is conjured and no item is ever partially consumed)
 * and reads in the world — a wall knits itself with no courier delivering
 * for it, which is what having a mason in the village should feel like.
 *
 * <h2>Bounded, claimed, scheduled</h2>
 *
 * <p>The scar list is capped at {@link RaidDirector#MAX_SCARS_PER_RAID}
 * and scanned on a cooldown, never per tick. One scar is one settler's
 * business at a time via the same static claim ledger idiom as
 * {@code CourierWorkGoal#RESERVATIONS} — claim before starting, renew
 * while working, release on stop, TTL-reclaim if the claimant dies
 * mid-trip. Work happens in working hours only ({@code DayPhase#work()}
 * — deliberately NOT {@code Schedule.shouldWork}, which is false for the
 * unemployed by definition, and the dugnad is exactly the unemployed
 * working), and only once the raid has actually resolved
 * ({@code Settlement#pendingRaid} == null): nobody points at a wall while
 * the band that burnt it is still standing there.
 *
 * <h2>Motion and sound</h2>
 *
 * <p>Activity is {@link SettlerActivity#WORK_CHISEL} — a deliberate reuse
 * per catalogue §16.3: EMERGENCY_REPAIR (§13.3) is catalogued but
 * unauthored, chisel is the nearest honest motion (a mason dressing a
 * block into a wall), and it is swapped the moment the bespoke clip
 * lands. The chisel tap rides the clip's contact beat — tick 10 of the
 * 21-tick loop, straight out of {@link Employment#soundContactOf} —
 * mirroring the audit-F8 fix in {@code CrafterWorkGoal}: the sound lands
 * on the visible strike, never on the loop seam.
 *
 * <h2>Wiring (SettlerEntity is owned elsewhere)</h2>
 *
 * <p>Register as
 * {@code goalSelector.addGoal(5, new com.hearthstead.entity.ai.RepairWorkGoal(this));}
 * — priority 5 sits above {@code GoToPostGoal} and every trade goal (6),
 * so the mason drops ordinary bench work while scars exist and the
 * unemployed are never dragged to the square first, and above
 * {@code BoundedStrollGoal} (8), so the idle stop idling. It stays below
 * eating (4) and everything that outranks the ordinary day; it shares
 * slot 5 with {@code RestAtNightGoal} harmlessly, since one gates on rest
 * hours and this gates on work hours.
 */
public class RepairWorkGoal extends Goal {

    /** How often to ask the scar ledger whether there is anything to fix. */
    private static final int LOOK_INTERVAL = 20;
    /**
     * Ticks to repair one block: three full 21-tick chisel loops — the
     * mandated ≈60, rounded to a whole number of clip cycles so the motion
     * never cuts mid-swing. Must stay a multiple of
     * {@link Employment#soundPeriodOf}(MASON) (21).
     */
    public static final int REPAIR_TICKS = 63;
    /** Close enough to work the wall: a reach, not a teleport. */
    private static final int WORK_RANGE = 3;
    /** Walking pace to the scar, same as the miner's to his stone. */
    private static final double MOVE_SPEED = 0.85;
    /**
     * How long a claim outlives its last heartbeat. Short next to the
     * courier's 1200: a repair trip is one walk and ~3 seconds of work,
     * so a lapsed claim means the claimant is genuinely gone (death,
     * unload), and the scar should return to the pool quickly. Renewed
     * every active tick, so a working settler never sees it expire.
     */
    private static final int CLAIM_TTL_TICKS = 600;

    private final SettlerEntity settler;
    @Nullable
    private BlockPos scarPos;
    @Nullable
    private BlockState scarOriginal;
    private int repairTicks;
    private int lookCooldown;

    public RepairWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Profession profession = settler.getProfession();
        boolean mason = profession == Profession.MASON;
        boolean dugnad = profession == Profession.NONE && !settler.isTraveler();
        if (!mason && !dugnad) {
            return false;
        }
        if (!settler.isBound() || settler.getTarget() != null
            || settler.getEnergy() <= 15.0F
            // The daily labor pool applies to a dugnad like any work: once
            // spent, no new scar is started (PLAN_EFFORT.md).
            || settler.isEffortSpent()) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL;
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null
            // Repair starts when the raid is over, not during it.
            || settlement.pendingRaid != null
            // Working hours; see the class doc for why not Schedule.shouldWork.
            || !settler.dayPhase().work()) {
            return false;
        }
        RaidDirector.Scar found = findScar(level, settlement);
        if (found == null) {
            return false;
        }
        long now = level.getGameTime();
        if (!claim(new ScarKey(settlement.id, found.pos()), now)) {
            return false; // lost a same-tick race to another repairer's claim
        }
        scarPos = found.pos();
        scarOriginal = found.original();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (scarPos == null || scarOriginal == null || settler.getTarget() != null
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return settlement != null && settlement.pendingRaid == null
            && settler.dayPhase().work()
            // The scar can be closed under us (another claimant after a TTL
            // lapse, or /setblock healing it) -- then this trip is over.
            && RaidDirector.hasScarAt(level, settlement.id, scarPos);
    }

    /**
     * Without this, a running goal only ticks on every OTHER real tick
     * (vanilla's half-rate default for goals that do not ask for more),
     * which would quietly double {@link #REPAIR_TICKS} in practice -- the
     * same "work but stall" defect KF-020 found in {@code CrafterWorkGoal},
     * whose class doc explains it in full. {@code REPAIR_TICKS} is already
     * calibrated to whole clip cycles assuming real-tick pacing, so this
     * goal needs the same override its sibling work goals all carry.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repairTicks = 0;
        // TRAVELING while walking; WORK_CHISEL only within reach of the
        // wall (tick()). Playing the work clip over open ground would be
        // exactly the motion-over-nothing the mason audit's fails-if-idle
        // test exists to forbid.
        settler.setActivity(SettlerActivity.TRAVELING);
        if (scarPos != null) {
            settler.getNavigation().moveTo(scarPos.getX() + 0.5, scarPos.getY(),
                scarPos.getZ() + 0.5, MOVE_SPEED);
        }
    }

    @Override
    public void stop() {
        releaseClaim();
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        scarPos = null;
        scarOriginal = null;
        repairTicks = 0;
    }

    @Override
    public void tick() {
        if (scarPos == null || scarOriginal == null
            || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        renewClaim(level);
        settler.getLookControl().setLookAt(scarPos.getX() + 0.5,
            scarPos.getY() + 0.5, scarPos.getZ() + 0.5);
        if (!settler.blockPosition().closerThan(scarPos, WORK_RANGE)) {
            if (settler.getNavigation().isDone()) {
                settler.getNavigation().moveTo(scarPos.getX() + 0.5,
                    scarPos.getY(), scarPos.getZ() + 0.5, MOVE_SPEED);
            }
            return;
        }
        settler.getNavigation().stop();
        if (settler.getActivity() != SettlerActivity.WORK_CHISEL) {
            settler.setActivity(SettlerActivity.WORK_CHISEL);
        }
        repairTicks++;
        // The tap rides the clip's contact beat (tick 10 of 21), read from
        // the same tables CrafterWorkGoal reads for the mason at her bench
        // (audit F8: never the loop seam), so repair sounds like the trade
        // it borrows its motion from.
        int period = Employment.soundPeriodOf(BuildingType.MASON);
        if (repairTicks % period == Employment.soundContactOf(BuildingType.MASON)) {
            level.playSound(null, scarPos, Employment.soundOf(BuildingType.MASON),
                SoundSource.NEUTRAL, 0.75F,
                0.94F + settler.getRandom().nextFloat() * 0.12F);
        }
        if (repairTicks < REPAIR_TICKS) {
            return;
        }
        finishRepair(level);
    }

    /**
     * The moment the work is done: consume one matching item (chest truth,
     * read live at this instant) and stand the original block back up --
     * unless this is one of the free mends the mason/sawmill discount earns
     * (see {@link #shouldMendFree}), in which case nothing is taken at all.
     * If the material vanished while we chiselled and this scar was NOT
     * free, the scar simply stays open for a later trip — nothing is
     * consumed, nothing appears.
     */
    private void finishRepair(ServerLevel level) {
        Settlement settlement = settler.settlement();
        if (settlement == null || scarPos == null || scarOriginal == null) {
            releaseClaim();
            scarPos = null;
            return;
        }
        BlockState current = level.getBlockState(scarPos);
        if (current == scarOriginal
            || (!current.canBeReplaced() && !current.isAir())) {
            // Healed already, or the player built something else there --
            // either way the wound is closed and overwriting would destroy,
            // not repair. No material moves.
            RaidDirector.clearScar(level, settlement.id, scarPos);
            releaseClaim();
            scarPos = null;
            return;
        }
        boolean free = shouldMendFree(level, settlement);
        if (!free && !consumeOne(level, settlement, scarOriginal)) {
            // No material after all, and this scar wasn't one of the free
            // ones either: the scar stays open for a later trip. Nothing
            // was spent, so the running tally below must not move either --
            // only real, completed mends count toward the next free one.
            releaseClaim();
            scarPos = null;
            return;
        }
        SCAR_MENDS.merge(settlement.id, 1, Integer::sum);
        level.setBlock(scarPos, scarOriginal, 3);
        RaidDirector.clearScar(level, settlement.id, scarPos);
        // Doing the job makes you better at it -- the mason's own attribute
        // (Employment.trainedBy(MASON) is STRENGTH), counted on completion
        // like every trade, and a repaired home is worth a little heart.
        settler.train(Attribute.STRENGTH, 1.0F);
        settler.addMorale(1.0F);
        settler.spendEffort(2);
        releaseClaim();
        scarPos = null;
    }

    // ---------------------------------------------------- mend-free tally ---

    /**
     * Running count of scars actually mended per settlement, feeding
     * {@link #shouldMendFree} -- not persisted, the same reasoning as
     * {@link #CLAIMS}: a discount opportunity forgotten across a server
     * restart is not the kind of thing a raid ledger needs to survive.
     */
    private static final Map<UUID, Integer> SCAR_MENDS = new HashMap<>();

    /**
     * Whether the scar about to finish should mend WITHOUT consuming any
     * material -- the mason -25% / sawmill -25% hooks
     * ({@code Costs.PriceKey#REPAIR}) made real (class doc, "some scars mend
     * free"). Deterministic on purpose: every
     * {@code 100 / discountPercent}th completed mend for this settlement is
     * free -- the 4th at the capped 25% (one hook), the 2nd at the capped
     * 50% (both) -- so the same settlement in the same state always gets the
     * same answer, never a coin flip.
     */
    private boolean shouldMendFree(ServerLevel level, Settlement settlement) {
        int percent = Costs.discountPercent(
            Costs.discountsFor(level, settlement, Costs.PriceKey.REPAIR));
        if (percent <= 0) {
            return false;
        }
        int interval = 100 / percent; // 4 at 25%, 2 at 50% -- exact both times
        int next = SCAR_MENDS.getOrDefault(settlement.id, 0) + 1;
        return next % interval == 0;
    }

    // ------------------------------------------------------- scar choice ---

    /**
     * The nearest scar this settler can actually act on: unclaimed, still
     * open (obsolete ones are closed here, in passing), in a loaded chunk,
     * with a matching material really available right now.
     */
    @Nullable
    private RaidDirector.Scar findScar(ServerLevel level, Settlement settlement) {
        List<RaidDirector.Scar> scars = RaidDirector.scarsOf(level, settlement.id);
        if (scars.isEmpty()) {
            return null;
        }
        long now = level.getGameTime();
        RaidDirector.Scar best = null;
        double bestDist = Double.MAX_VALUE;
        for (RaidDirector.Scar scar : scars) {
            if (!level.isLoaded(scar.pos())) {
                continue; // never sync-load a chunk to inspect a wound
            }
            BlockState current = level.getBlockState(scar.pos());
            if (current == scar.original()
                || (!current.canBeReplaced() && !current.isAir())) {
                // Already healed, or built over by the player -- the ledger
                // is tidied the moment anyone looks, so dead scars never
                // hold a claimant's attention or a chunk of the cap.
                RaidDirector.clearScar(level, settlement.id, scar.pos());
                continue;
            }
            if (heldByOther(new ScarKey(settlement.id, scar.pos()), now)) {
                continue;
            }
            if (!materialAvailable(level, settlement, scar.original())) {
                continue; // no material, no repair -- and no claim either
            }
            double dist = settler.blockPosition().distSqr(scar.pos());
            if (dist < bestDist) {
                bestDist = dist;
                best = scar;
            }
        }
        return best;
    }

    // --------------------------------------------------------- materials ---

    /**
     * The substitute a scar accepts when the exact block is not in stock,
     * or null when only the exact item will do. Wooden blocks (anything an
     * axe mines) take any plank; stone blocks (anything a pickaxe mines)
     * take cobblestone, stone or stone bricks — the mason's own shelf, which
     * is what makes this her sink.
     */
    @Nullable
    static Predicate<ItemStack> fallbackFor(BlockState original) {
        if (original.is(BlockTags.MINEABLE_WITH_AXE)) {
            return stack -> stack.is(ItemTags.PLANKS);
        }
        if (original.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.STONE)
                || stack.is(Items.STONE_BRICKS);
        }
        return null;
    }

    /** Whether one matching item exists right now, exact or fallback. */
    private boolean materialAvailable(ServerLevel level, Settlement settlement,
                                      BlockState original) {
        List<Container> chests = ownChests(level, settlement);
        HearthBlockEntity hearth = settler.hearth();
        Item exact = original.getBlock().asItem();
        if (exact != Items.AIR
            && (holdsAny(chests, stack -> stack.is(exact))
                || hearthHoldsAny(hearth, stack -> stack.is(exact)))) {
            return true;
        }
        Predicate<ItemStack> fallback = fallbackFor(original);
        return fallback != null
            && (holdsAny(chests, fallback) || hearthHoldsAny(hearth, fallback));
    }

    /**
     * Takes exactly one matching item out of a real slot — the exact item
     * preferred over a substitute, own chests preferred over the hearth.
     * Chest truth: this re-reads the containers at the moment of the take;
     * a count remembered from claim time is never trusted.
     */
    private boolean consumeOne(ServerLevel level, Settlement settlement,
                               BlockState original) {
        List<Container> chests = ownChests(level, settlement);
        HearthBlockEntity hearth = settler.hearth();
        Item exact = original.getBlock().asItem();
        if (exact != Items.AIR) {
            Predicate<ItemStack> match = stack -> stack.is(exact);
            if (extractOne(chests, match) || extractOneFromHearth(hearth, match)) {
                return true;
            }
        }
        Predicate<ItemStack> fallback = fallbackFor(original);
        return fallback != null
            && (extractOne(chests, fallback)
                || extractOneFromHearth(hearth, fallback));
    }

    /**
     * The settler's own building's containers — the mason's yard for the
     * mason, nothing for the unemployed (their source is the hearth). The
     * discovery is {@link WarehouseIndex#containers}, the same doubly
     * bounded walk every other goal uses.
     */
    private List<Container> ownChests(ServerLevel level, Settlement settlement) {
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        if (employer == null || !employer.valid || employer.bounds == null) {
            return List.of();
        }
        List<Container> found = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, employer)) {
            if (level.getBlockEntity(pos) instanceof Container container) {
                found.add(container);
            }
        }
        return found;
    }

    private static boolean holdsAny(List<Container> chests, Predicate<ItemStack> match) {
        for (Container chest : chests) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack in = chest.getItem(slot);
                if (!in.isEmpty() && match.test(in)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hearthHoldsAny(@Nullable HearthBlockEntity hearth,
                                          Predicate<ItemStack> match) {
        if (hearth == null) {
            return false;
        }
        ItemStackHandler inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack in = inventory.getStackInSlot(slot);
            if (!in.isEmpty() && match.test(in)) {
                return true;
            }
        }
        return false;
    }

    private static boolean extractOne(List<Container> chests, Predicate<ItemStack> match) {
        for (Container chest : chests) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack in = chest.getItem(slot);
                if (!in.isEmpty() && match.test(in)) {
                    in.shrink(1);
                    if (in.isEmpty()) {
                        chest.setItem(slot, ItemStack.EMPTY);
                    }
                    chest.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean extractOneFromHearth(@Nullable HearthBlockEntity hearth,
                                                Predicate<ItemStack> match) {
        if (hearth == null) {
            return false;
        }
        ItemStackHandler inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack in = inventory.getStackInSlot(slot);
            if (!in.isEmpty() && match.test(in)) {
                return !inventory.extractItem(slot, 1, false).isEmpty();
            }
        }
        return false;
    }

    // ------------------------------------------------------ claim ledger ---

    /** One scar of one settlement — the unit a claim covers. */
    private record ScarKey(UUID settlementId, BlockPos pos) {
    }

    private record Claim(UUID worker, long expiresAtTick) {
    }

    /**
     * Every claimed scar in the game, across every settler and settlement.
     * The {@code CourierWorkGoal#RESERVATIONS} idiom exactly: static intent
     * ("who is fixing this"), never a second copy of world or ledger state,
     * no per-world lifecycle — a stale entry sits unreferenced until its
     * lease lapses. A settler holds at most one claim at a time by
     * construction ({@link #scarPos} is a single field).
     */
    private static final Map<ScarKey, Claim> CLAIMS = new HashMap<>();

    private boolean heldByOther(ScarKey key, long now) {
        Claim held = CLAIMS.get(key);
        return held != null && held.expiresAtTick() > now
            && !held.worker().equals(settler.getUUID());
    }

    private boolean claim(ScarKey key, long now) {
        if (heldByOther(key, now)) {
            return false;
        }
        CLAIMS.put(key, new Claim(settler.getUUID(), now + CLAIM_TTL_TICKS));
        return true;
    }

    private void renewClaim(ServerLevel level) {
        if (scarPos == null) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return;
        }
        CLAIMS.put(new ScarKey(settlement.id, scarPos),
            new Claim(settler.getUUID(), level.getGameTime() + CLAIM_TTL_TICKS));
    }

    private void releaseClaim() {
        if (scarPos == null) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return;
        }
        ScarKey key = new ScarKey(settlement.id, scarPos);
        Claim held = CLAIMS.get(key);
        if (held != null && held.worker().equals(settler.getUUID())) {
            CLAIMS.remove(key);
        }
    }

    /**
     * Test-only window into the ledger, the same reason
     * {@code CourierWorkGoal#restockJobIsHeld} exists: from the outside, "a
     * second settler was locked out" and "a second settler found nothing
     * left to do" can look identical, and only the lock itself tells them
     * apart.
     */
    public static boolean scarIsClaimed(UUID settlementId, BlockPos pos) {
        return CLAIMS.containsKey(new ScarKey(settlementId, pos));
    }
}

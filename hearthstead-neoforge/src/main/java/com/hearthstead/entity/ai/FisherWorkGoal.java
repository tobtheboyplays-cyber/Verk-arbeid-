package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The fisher's work: stand a real water's edge and land real fish into the
 * fishery's own chest, on a cadence.
 *
 * <h2>What "adjacent water" means, exactly</h2>
 *
 * <p>{@code BuildingType.FISHERY}'s room requirement (2 water blocks) only
 * proves the plaque found a bowl or a bucket-splash inside the room — that
 * is a decoration check, not a fishing spot. A settler who could fish out of
 * that would be a free food printer with a water-block prop in front of it.
 * So this goal makes its own, stricter demand before it will ever start:
 * at least {@value #MIN_ADJACENT_WATER} real water blocks within
 * {@value #WATER_SEARCH_RADIUS} blocks (horizontal) and one block up/down of
 * the building's anchor. A puddle fails that count and the fishery simply
 * never produces — not an error, just nothing to do, the same honest silence
 * {@code MinerWorkGoal} gives a mine with nowhere to put ore.
 *
 * <h2>No infinite lake required, but a real one</h2>
 *
 * <p>The count is a floor, not a lake-detection algorithm — a generous
 * player-dug pond clears it easily; the search radius and threshold are
 * chosen so a token puzzle-box of water cannot.
 */
public class FisherWorkGoal extends Goal {

    private enum Mode { TO_DOCK, FISHING, TO_FISHERY }

    /** See the class doc: the floor "adjacent water" has to clear. */
    private static final int WATER_SEARCH_RADIUS = 6;
    private static final int MIN_ADJACENT_WATER = 20;

    private static final int LOOK_INTERVAL = 60;
    /** FISHER_CAST is a 2.00s/40-tick loop; one full loop is one catch. */
    private static final int FISH_CADENCE = 40;
    private static final int BITE_ACCENT_TICK = 29;
    private static final int BAG_TRIGGER = 6;
    private static final int REPATH_INTERVAL = 40;
    private static final int PATIENCE = 6;

    private final SettlerEntity settler;
    private Mode mode;
    private Building fishery;
    private BlockPos dockPos;
    private Direction waterDir;
    private int workTicks;
    private int lookCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public FisherWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.FISHER
            && settler.isBound()
            && settler.dayPhase().work()
            && settler.getEnergy() > 15
            && !settler.isEffortSpent();
    }

    private int bagCount() {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    @Override
    public boolean canUse() {
        if (!workConditions()) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null) {
            return false;
        }
        if (bagCount() >= BAG_TRIGGER) {
            mode = Mode.TO_FISHERY;
            return true;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL + settler.getRandom().nextInt(LOOK_INTERVAL);
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Building building = Employment.employerOf(s, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null) {
            return false;
        }
        fishery = building;
        if (dockPos == null || dockableDirection(level, dockPos) == null) {
            dockPos = findFishingSpot(level, building.anchor);
        }
        if (dockPos == null) {
            return false; // not enough real water nearby -- see class doc
        }
        mode = Mode.TO_DOCK;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && (mode == Mode.TO_FISHERY || workConditions());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        workTicks = 0;
        stuckChecks = 0;
        repathTimer = 0;
        settler.setActivity(SettlerActivity.TRAVELING);
        if (mode == Mode.TO_FISHERY) {
            pathToFishery();
        } else {
            pathToDock();
        }
    }

    private void pathToDock() {
        if (dockPos != null) {
            settler.getNavigation().moveTo(dockPos.getX() + 0.5, dockPos.getY(),
                dockPos.getZ() + 0.5, 0.9);
        }
    }

    private void pathToFishery() {
        if (fishery != null && fishery.anchor != null) {
            settler.getNavigation().moveTo(fishery.anchor.getX() + 0.5,
                fishery.anchor.getY() + 1, fishery.anchor.getZ() + 0.5, 1.0);
        } else {
            done = true;
        }
    }

    @Override
    public void tick() {
        switch (mode) {
            case TO_DOCK -> tickTravelToDock();
            case FISHING -> tickFish();
            case TO_FISHERY -> tickDeposit();
        }
    }

    private void tickTravelToDock() {
        if (dockPos == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(dockPos.getX() + 0.5, dockPos.getY() + 1.0,
            dockPos.getZ() + 0.5);
        if (settler.blockPosition().closerThan(dockPos, 2.0)) {
            mode = Mode.FISHING;
            workTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.WORK_FISH);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > PATIENCE) {
                settler.recordRouteFailure("dock_unreachable");
                done = true;
            } else {
                pathToDock();
            }
        }
    }

    private void tickFish() {
        if (!(settler.level() instanceof ServerLevel level) || dockPos == null) {
            done = true;
            return;
        }
        // Watches the water, not their own feet -- waterDir is the direction
        // findFishingSpot actually found the water in.
        Direction dir = waterDir != null ? waterDir : Direction.NORTH;
        settler.getLookControl().setLookAt(dockPos.getX() + 0.5 + dir.getStepX() * 2.5,
            dockPos.getY() + 0.2, dockPos.getZ() + 0.5 + dir.getStepZ() * 2.5);
        workTicks++;
        if (workTicks == BITE_ACCENT_TICK) {
            level.playSound(null, dockPos, ModSounds.WATER_POUR.get(),
                SoundSource.NEUTRAL, 0.6F, 1.1F + settler.getRandom().nextFloat() * 0.15F);
        }
        if (workTicks >= FISH_CADENCE) {
            workTicks = 0;
            ItemStack caught = rollCatch(settler.getRandom());
            ItemStack leftover = settler.bag.addItem(caught);
            if (!leftover.isEmpty()) {
                // The sack briefly filled mid-session -- set down at the
                // settler's own feet rather than lose the catch (INV-3).
                Block.popResource(level, settler.blockPosition(), leftover);
            }
            settler.train(Employment.trainedBy(BuildingType.FISHERY), 1.0F);
            settler.spendEffort(1);
            if (bagCount() >= BAG_TRIGGER) {
                mode = Mode.TO_FISHERY;
                settler.setActivity(SettlerActivity.TRAVELING);
                pathToFishery();
            }
            // Otherwise stays in FISHING: the loop simply starts again.
        }
    }

    private void tickDeposit() {
        if (fishery == null || fishery.anchor == null) {
            done = true;
            return;
        }
        BlockPos target = fishery.anchor;
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 1.0,
            target.getZ() + 0.5);
        if (settler.blockPosition().closerThan(target, 3.0)) {
            if (settler.level() instanceof ServerLevel level) {
                List<Container> containers = new ArrayList<>();
                for (BlockPos pos : WarehouseIndex.containers(level, fishery)) {
                    if (level.getBlockEntity(pos) instanceof Container chest) {
                        containers.add(chest);
                    }
                }
                for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                    ItemStack stack = settler.bag.getItem(i);
                    if (!stack.isEmpty()) {
                        settler.bag.setItem(i, insert(containers, stack));
                    }
                }
            }
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            pathToFishery();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
    }

    // ------------------------------------------------------------ helpers ---

    private static ItemStack rollCatch(RandomSource random) {
        float roll = random.nextFloat();
        if (roll < 0.55F) {
            return new ItemStack(Items.COD);
        } else if (roll < 0.85F) {
            return new ItemStack(Items.SALMON);
        } else if (roll < 0.95F) {
            return new ItemStack(Items.PUFFERFISH);
        }
        return new ItemStack(Items.TROPICAL_FISH);
    }

    /**
     * One combined bounded scan (see class doc): counts real water blocks
     * near {@code anchor} and, in the same pass, remembers the first standable
     * position next to one. Returns null unless BOTH the count clears
     * {@value #MIN_ADJACENT_WATER} and a real dock was found.
     */
    private BlockPos findFishingSpot(ServerLevel level, BlockPos anchor) {
        int waterSeen = 0;
        BlockPos dock = null;
        Direction dockDir = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -WATER_SEARCH_RADIUS; dx <= WATER_SEARCH_RADIUS; dx++) {
            for (int dz = -WATER_SEARCH_RADIUS; dz <= WATER_SEARCH_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    if (level.getFluidState(cursor).is(FluidTags.WATER)) {
                        waterSeen++;
                    }
                    if (dock == null) {
                        Direction dir = dockableDirection(level, cursor);
                        if (dir != null) {
                            dock = cursor.immutable();
                            dockDir = dir;
                        }
                    }
                }
            }
        }
        if (waterSeen < MIN_ADJACENT_WATER || dock == null) {
            return null;
        }
        waterDir = dockDir;
        return dock;
    }

    /** The horizontal direction {@code pos} has real water in, or null if
     *  {@code pos} is not itself a standable position next to any. */
    private static Direction dockableDirection(ServerLevel level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            return null; // the dock itself must be dry ground
        }
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return null; // must be passable
        }
        if (!level.getBlockState(pos.below())
            .isFaceSturdy(level, pos.below(), Direction.UP)) {
            return null; // must have solid footing
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(pos.relative(dir)).is(FluidTags.WATER)) {
                return dir;
            }
        }
        return null;
    }

    private static ItemStack insert(List<Container> containers, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack in = container.getItem(slot);
                if (in.isEmpty()) {
                    container.setItem(slot, remaining.copy());
                    return ItemStack.EMPTY;
                }
                if (ItemStack.isSameItemSameComponents(in, remaining)
                    && in.getCount() < in.getMaxStackSize()) {
                    int move = Math.min(remaining.getCount(), in.getMaxStackSize() - in.getCount());
                    in.grow(move);
                    container.setChanged();
                    remaining.shrink(move);
                }
            }
        }
        return remaining;
    }
}

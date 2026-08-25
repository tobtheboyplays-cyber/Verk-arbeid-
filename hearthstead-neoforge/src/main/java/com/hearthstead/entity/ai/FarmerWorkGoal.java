package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

/**
 * The farmer's day: find mature crops, harvest them by hand, replant, carry
 * the yield home, and -- when there is nothing ripe to harvest -- till bare
 * ground next to existing farmland and water dry farmland (§2 of
 * docs/ANIMATION_CATALOGUE.md: the farmer's four tasks read as four
 * different heights, so all four need a live trigger, not just harvesting).
 */
public class FarmerWorkGoal extends Goal {
    private static final int HARVEST_DURATION = 36;
    private static final int PLANT_DURATION = 40;
    private static final int TILL_DURATION = 30;
    private static final int WATER_DURATION = 48;
    private static final int BAG_TRIGGER = 8;
    private static final int TILL_ANCHOR_RANGE = 3;
    /** THE TENDED PLOT (docs/project/PLAN_EFFORT.md): no farmhouse, at any
     *  skill or headcount, ever tends a square bigger than this. */
    private static final int TENDED_SIDE_CAP = 11;
    /** One effort unit per this many completed plant/till/water actions —
     *  batch-counted so the light work does not spend as fast as a harvest. */
    private static final int LIGHT_ACTIONS_PER_EFFORT = 4;

    private enum Mode { TO_WORK, HARVESTING, PLANTING, TO_MAINTAIN, TILLING, WATERING, TO_HEARTH }

    private final SettlerEntity settler;
    private final WorkScanner scanner = new WorkScanner();
    private final WorkScanner maintainScanner = new WorkScanner();
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Deque<BlockPos> maintainQueue = new ArrayDeque<>();
    private Mode mode;
    private BlockPos target;
    private BlockPos maintainTarget;
    private boolean maintainIsWater;
    private Block harvestedCrop;
    private int workTicks;
    private int scanCooldown;
    private int maintainScanCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;
    /** Batch counter for the light work's effort cost; see harvest()'s own
     *  per-crop spend for why planting/tilling/watering are counted apart. */
    private int lightActionCount;

    public FarmerWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.FARMER
            && settler.isBound()
            && settler.dayPhase().work()
            && settler.getEnergy() > 15
            // The daily labor pool (PLAN_EFFORT.md): once it is spent this
            // goal will not start, exactly like the energy check just
            // above -- including the hearth trip for a bag that filled up
            // right as the pool ran out. That last load waits for tomorrow,
            // the same way a settler out of energy already would.
            && !settler.isEffortSpent();
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
            mode = Mode.TO_HEARTH;
            return true;
        }
        if (scanCooldown > 0) {
            scanCooldown--;
        } else if (queue.isEmpty()) {
            scanCooldown = 60 + settler.getRandom().nextInt(40);
            queue.addAll(scanner.scan(s.center, s.radius, 512, 12, this::isMatureCrop));
        }
        while (!queue.isEmpty()) {
            BlockPos candidate = queue.poll();
            if (isMatureCrop(candidate)) {
                target = candidate;
                mode = Mode.TO_WORK;
                return true;
            }
        }
        // Nothing ripe right now: till bare ground next to farmland, or
        // water dry farmland. Own cooldown/cursor so it never competes with
        // the crop scan above.
        if (maintainScanCooldown > 0) {
            maintainScanCooldown--;
        } else if (maintainQueue.isEmpty()) {
            maintainScanCooldown = 100 + settler.getRandom().nextInt(60);
            maintainQueue.addAll(maintainScanner.scan(s.center, s.radius, 400, 6, this::isMaintainable));
        }
        while (!maintainQueue.isEmpty()) {
            BlockPos candidate = maintainQueue.poll();
            if (isMaintainable(candidate)) {
                boolean water = settler.level().getBlockState(candidate).is(Blocks.FARMLAND);
                if (!water && !hasNearbyCropAnchor(candidate)) {
                    continue; // unanchored tilling would terraform the settlement
                }
                maintainTarget = candidate;
                maintainIsWater = water;
                mode = Mode.TO_MAINTAIN;
                return true;
            }
        }
        return false;
    }

    private boolean isMatureCrop(BlockPos pos) {
        BlockState state = settler.level().getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
            && isWithinTendedPlot(pos);
    }

    private boolean isMaintainable(BlockPos pos) {
        if (!isWithinTendedPlot(pos)) {
            // Tilling and watering grow the field itself, so THE TENDED
            // PLOT has to bound them too -- an unbounded maintenance pass
            // would let a farmer terraform the whole settlement even
            // though harvesting stayed inside the square.
            return false;
        }
        BlockState state = settler.level().getBlockState(pos);
        if (state.is(Blocks.FARMLAND)) {
            BlockState above = settler.level().getBlockState(pos.above());
            return state.getValue(FarmBlock.MOISTURE) < 7
                && (above.isAir() || above.getBlock() instanceof CropBlock);
        }
        if ((state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
            && settler.level().getBlockState(pos.above()).isAir()) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (settler.level().getBlockState(pos.relative(dir)).is(Blocks.FARMLAND)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The building this farmer is actually hired into, or null if none —
     * cheap enough to call from a scan predicate (small building list per
     * settlement, same cost Employment.employerOf already pays elsewhere).
     */
    private Building tendedFarmhouse() {
        Settlement s = settler.settlement();
        return s == null ? null : Employment.employerOf(s, settler.getUUID());
    }

    /**
     * THE TENDED PLOT (docs/project/PLAN_EFFORT.md §2): a farmer works a
     * bounded square around their own farmhouse's anchor, never the whole
     * settlement. That square is the trade's natural limit, the way a real
     * field has an edge — a crop outside it is simply somebody else's to
     * tend, or nobody's yet.
     *
     * <p>Everything outside the square is filtered out here, in the same
     * predicate the scan and the target-validity checks already share, so
     * "ignored entirely" costs nothing extra: it is the same cheap test the
     * goal was already doing.
     */
    private boolean isWithinTendedPlot(BlockPos pos) {
        Building farmhouse = tendedFarmhouse();
        if (farmhouse == null || farmhouse.anchor == null) {
            return false;
        }
        int half = tendedHalfSide(farmhouse);
        BlockPos anchor = farmhouse.anchor;
        return Math.abs(pos.getX() - anchor.getX()) <= half
            && Math.abs(pos.getZ() - anchor.getZ()) <= half;
    }

    /**
     * Half the tended square's side. Base size comes from skill — 3x3 below
     * 20 DEXTERITY, widening every 20 points, 11x11 at 80+ — and every OTHER
     * farmer sharing this farmhouse adds one more ring on top of that, many
     * hands really tending a bigger field. Either path stops at
     * {@value #TENDED_SIDE_CAP}: there is no version of this job that farms
     * the whole map.
     */
    private int tendedHalfSide(Building farmhouse) {
        int dexterity = settler.attribute(Attribute.DEXTERITY);
        int side = 3 + 2 * (dexterity / 20);
        int companions = Math.max(0, farmhouse.workers.size() - 1);
        side = Math.min(TENDED_SIDE_CAP, side + 2 * companions);
        return side / 2;
    }

    /**
     * Tilling stays anchored to real fields: bare ground converts only
     * within reach of an existing planted crop. Tilling itself never adds
     * a crop, so each conversion adds no new anchor and the maintenance
     * pass cannot cascade farmland across the settlement. Checked per
     * polled candidate, not in the scan predicate, to keep scans budgeted.
     */
    private boolean hasNearbyCropAnchor(BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -TILL_ANCHOR_RANGE; dx <= TILL_ANCHOR_RANGE; dx++) {
            for (int dz = -TILL_ANCHOR_RANGE; dz <= TILL_ANCHOR_RANGE; dz++) {
                cursor.set(pos.getX() + dx, pos.getY() + 1, pos.getZ() + dz);
                if (settler.level().getBlockState(cursor).getBlock() instanceof CropBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private int bagCount() {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    @Override
    public void start() {
        done = false;
        workTicks = 0;
        stuckChecks = 0;
        repathTimer = 0;
        if (mode == Mode.TO_HEARTH) {
            pathToHearth();
        } else if (mode == Mode.TO_MAINTAIN) {
            pathToMaintainTarget();
        } else {
            pathToTarget();
        }
    }

    private void pathToTarget() {
        if (target != null) {
            settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(),
                target.getZ() + 0.5, 1.0);
        }
    }

    private void pathToMaintainTarget() {
        if (maintainTarget != null) {
            settler.getNavigation().moveTo(maintainTarget.getX() + 0.5, maintainTarget.getY() + 1,
                maintainTarget.getZ() + 0.5, 1.0);
        }
    }

    private void pathToHearth() {
        BlockPos hearth = settler.getHearthPos();
        if (hearth != null) {
            settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
                hearth.getZ() + 0.5, 1.0);
        } else {
            done = true;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !done && (mode == Mode.TO_HEARTH || workConditions());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (mode) {
            case TO_WORK -> tickTravel();
            case HARVESTING -> tickHarvest();
            case PLANTING -> tickPlant();
            case TO_MAINTAIN -> tickMaintainTravel();
            case TILLING -> tickTilling();
            case WATERING -> tickWatering();
            case TO_HEARTH -> tickDeposit();
        }
    }

    private void tickTravel() {
        if (target == null || !isMatureCrop(target)) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.3,
            target.getZ() + 0.5);
        double distSqr = settler.blockPosition().distSqr(target);
        if (distSqr <= 6.5) {
            mode = Mode.HARVESTING;
            workTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.WORK_HARVEST);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 6) {
                nextOrFinish(); // unreachable crop; skip it
            } else {
                pathToTarget();
            }
        }
    }

    private void tickHarvest() {
        if (target == null) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.2,
            target.getZ() + 0.5);
        workTicks++;
        if (settler.level() instanceof ServerLevel serverLevel) {
            if (workTicks % HARVEST_DURATION == 9) {
                serverLevel.playSound(null, target, ModSounds.CROP_PULL.get(),
                    SoundSource.NEUTRAL, 0.7F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
            } else if (workTicks % HARVEST_DURATION == 18) {
                serverLevel.playSound(null, target, ModSounds.BAG_STOW.get(),
                    SoundSource.NEUTRAL, 0.65F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        if (workTicks >= HARVEST_DURATION) {
            harvest();
            if (hasSeedFor(harvestedCrop) && settler.level().getBlockState(target.below())
                .is(Blocks.FARMLAND)) {
                mode = Mode.PLANTING;
                workTicks = 0;
                // D-016: the farmer's signature. Broadcasting seed by
                // hand reads at fifty blocks; pressing one seed into
                // one hole does not.
                settler.setActivity(SettlerActivity.WORK_SOW);
            } else if (bagCount() >= BAG_TRIGGER) {
                mode = Mode.TO_HEARTH;
                settler.setActivity(SettlerActivity.IDLE);
                pathToHearth();
            } else {
                nextOrFinish();
            }
        }
    }

    private void tickPlant() {
        if (target == null) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.1,
            target.getZ() + 0.5);
        workTicks++;
        if (workTicks % PLANT_DURATION == 14 && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, target, ModSounds.SEED_PRESS.get(),
                SoundSource.NEUTRAL, 0.6F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= PLANT_DURATION) {
            if (settler.level() instanceof ServerLevel serverLevel && harvestedCrop instanceof CropBlock crop
                && serverLevel.getBlockState(target).isAir()
                && serverLevel.getBlockState(target.below()).is(Blocks.FARMLAND)) {
                // The seed leaves the bag ONLY here, after every guard has
                // passed, so a failed plant costs nothing and the item and
                // the crop appear together or not at all.
                if (consumeSeedFor(crop)) {
                    serverLevel.setBlock(target, crop.getStateForAge(0), Block.UPDATE_ALL);
                }
            }
            harvestedCrop = null;
            chargeLightAction();
            if (bagCount() >= BAG_TRIGGER) {
                mode = Mode.TO_HEARTH;
                settler.setActivity(SettlerActivity.IDLE);
                pathToHearth();
            } else {
                nextOrFinish();
            }
        }
    }

    private void tickMaintainTravel() {
        if (maintainTarget == null || !isMaintainable(maintainTarget)) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(maintainTarget.getX() + 0.5, maintainTarget.getY() + 0.5,
            maintainTarget.getZ() + 0.5);
        if (settler.blockPosition().distSqr(maintainTarget) <= 6.5) {
            mode = maintainIsWater ? Mode.WATERING : Mode.TILLING;
            workTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(maintainIsWater ? SettlerActivity.WORK_WATER : SettlerActivity.WORK_FARM);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 6) {
                nextOrFinish();
            } else {
                pathToMaintainTarget();
            }
        }
    }

    private void tickTilling() {
        if (maintainTarget == null) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(maintainTarget.getX() + 0.5, maintainTarget.getY() + 0.3,
            maintainTarget.getZ() + 0.5);
        workTicks++;
        if (workTicks % TILL_DURATION == 12 && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, maintainTarget, ModSounds.FARMER_WORK.get(),
                SoundSource.NEUTRAL, 0.8F, 0.9F + settler.getRandom().nextFloat() * 0.2F);
        }
        if (workTicks >= TILL_DURATION) {
            if (settler.level() instanceof ServerLevel serverLevel && isMaintainable(maintainTarget)
                && hasNearbyCropAnchor(maintainTarget)) {
                serverLevel.setBlock(maintainTarget, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
            }
            chargeLightAction();
            maintainTarget = null;
            nextOrFinish();
        }
    }

    private void tickWatering() {
        if (maintainTarget == null) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(maintainTarget.getX() + 0.5, maintainTarget.getY() + 0.5,
            maintainTarget.getZ() + 0.5);
        workTicks++;
        if (workTicks % WATER_DURATION == 16 && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, maintainTarget, ModSounds.WATER_POUR.get(),
                SoundSource.NEUTRAL, 0.6F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= WATER_DURATION) {
            if (settler.level() instanceof ServerLevel serverLevel) {
                BlockState state = serverLevel.getBlockState(maintainTarget);
                if (state.is(Blocks.FARMLAND)) {
                    serverLevel.setBlock(maintainTarget, state.setValue(FarmBlock.MOISTURE, 7),
                        Block.UPDATE_ALL);
                }
            }
            chargeLightAction();
            maintainTarget = null;
            nextOrFinish();
        }
    }

    /** Pulls the mature crop and pockets every drop in the bag. The seed
     *  for the replant is taken back OUT of the bag at planting time --
     *  see consumeSeedFor(). Nothing is held outside the bag, because a
     *  plain goal field is destroyed when the entity unloads or the server
     *  stops, and item conservation is a permanent invariant. */
    private void harvest() {
        if (!(settler.level() instanceof ServerLevel serverLevel) || !isMatureCrop(target)) {
            return;
        }
        BlockState state = serverLevel.getBlockState(target);
        CropBlock crop = (CropBlock) state.getBlock();
        harvestedCrop = crop;
        List<ItemStack> drops = Block.getDrops(state, serverLevel, target, null);

        serverLevel.removeBlock(target, false);
        serverLevel.playSound(null, target, state.getSoundType().getBreakSound(),
            SoundSource.BLOCKS, 0.8F, 1.0F);

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack leftover = settler.bag.addItem(drop);
            if (!leftover.isEmpty()) {
                serverLevel.addFreshEntity(new ItemEntity(serverLevel,
                    target.getX() + 0.5, target.getY() + 0.3, target.getZ() + 0.5, leftover));
            }
        }
        // One crop pulled by hand is one unit of the daily pool -- charged
        // whether or not there is a seed to replant, since the labor is the
        // same either way (PLAN_EFFORT.md §2).
        settler.spendEffort(1);
    }

    /**
     * Planting, tilling and watering are light work next to a harvest, so
     * they are batch-charged: one effort unit per
     * {@value #LIGHT_ACTIONS_PER_EFFORT} completed actions of ANY of the
     * three kinds, counted together rather than per kind.
     */
    private void chargeLightAction() {
        if (++lightActionCount >= LIGHT_ACTIONS_PER_EFFORT) {
            lightActionCount = 0;
            settler.spendEffort(1);
        }
    }

    private void nextOrFinish() {
        settler.setActivity(SettlerActivity.IDLE);
        workTicks = 0;
        stuckChecks = 0;
        while (!queue.isEmpty()) {
            BlockPos candidate = queue.poll();
            if (isMatureCrop(candidate)) {
                target = candidate;
                mode = Mode.TO_WORK;
                pathToTarget();
                return;
            }
        }
        if (bagCount() > 0) {
            mode = Mode.TO_HEARTH;
            pathToHearth();
        } else {
            done = true;
        }
    }

    private void tickDeposit() {
        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5, hearthPos.getY() + 0.6,
            hearthPos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(hearthPos) <= 6.25) {
            HearthBlockEntity hearth = settler.hearth();
            if (hearth != null) {
                for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                    ItemStack stack = settler.bag.getItem(i);
                    if (!stack.isEmpty()) {
                        settler.bag.setItem(i, hearth.insertGoods(stack));
                    }
                }
            }
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            pathToHearth();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        target = null;
    }

    /** Does the bag hold a seed that would plant this crop? */
    private boolean hasSeedFor(Block crop) {
        return seedSlotFor(crop) >= 0;
    }

    /** Removes exactly one seed for this crop from the bag. */
    private boolean consumeSeedFor(Block crop) {
        int slot = seedSlotFor(crop);
        if (slot < 0) {
            return false;
        }
        settler.bag.removeItem(slot, 1);
        // Job standard, point 8: a delivered crop is one unit of work.
        settler.train(com.hearthstead.entity.Attribute.DEXTERITY, 1.0F);
        return true;
    }

    private int seedSlotFor(Block crop) {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == crop) {
                return i;
            }
        }
        return -1;
    }
}

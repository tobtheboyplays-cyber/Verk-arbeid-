package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
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
    private ItemStack withheldSeed = ItemStack.EMPTY;
    private int workTicks;
    private int scanCooldown;
    private int maintainScanCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public FarmerWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.FARMER
            && settler.isBound()
            && settler.dayPhase() == SettlerEntity.DayPhase.WORK
            && settler.getEnergy() > 15;
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
                maintainTarget = candidate;
                maintainIsWater = settler.level().getBlockState(candidate).is(Blocks.FARMLAND);
                mode = Mode.TO_MAINTAIN;
                return true;
            }
        }
        return false;
    }

    private boolean isMatureCrop(BlockPos pos) {
        BlockState state = settler.level().getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private boolean isMaintainable(BlockPos pos) {
        BlockState state = settler.level().getBlockState(pos);
        if (state.is(Blocks.FARMLAND)) {
            return state.getValue(FarmBlock.MOISTURE) < 7
                && settler.level().getBlockState(pos.above()).isAir();
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
            if (!withheldSeed.isEmpty() && settler.level().getBlockState(target.below())
                .is(Blocks.FARMLAND)) {
                mode = Mode.PLANTING;
                workTicks = 0;
                settler.setActivity(SettlerActivity.WORK_PLANT);
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
                serverLevel.setBlock(target, crop.getStateForAge(0), Block.UPDATE_ALL);
            }
            withheldSeed = ItemStack.EMPTY;
            harvestedCrop = null;
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
            if (settler.level() instanceof ServerLevel serverLevel && isMaintainable(maintainTarget)) {
                serverLevel.setBlock(maintainTarget, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
            }
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
            maintainTarget = null;
            nextOrFinish();
        }
    }

    /** Pulls the mature crop and pockets the drops, withholding one seed
     *  (remembered in {@link #withheldSeed}) for the PLANTING phase instead
     *  of replanting immediately. */
    private void harvest() {
        if (!(settler.level() instanceof ServerLevel serverLevel) || !isMatureCrop(target)) {
            return;
        }
        BlockState state = serverLevel.getBlockState(target);
        CropBlock crop = (CropBlock) state.getBlock();
        harvestedCrop = crop;
        List<ItemStack> drops = Block.getDrops(state, serverLevel, target, null);

        withheldSeed = ItemStack.EMPTY;
        for (ItemStack drop : drops) {
            if (withheldSeed.isEmpty() && drop.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == crop) {
                drop.shrink(1);
                withheldSeed = new ItemStack(drop.getItem(), 1);
            }
        }
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
}

package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lumberer fells natural trees top-down (never leaving floating trunks),
 * replants a matching sapling, and hauls the logs home.
 */
public class LumbererWorkGoal extends Goal {
    private static final int MAX_TREE_LOGS = 96;
    private static final int MAX_DRIFT = 8;
    private static final int MIN_LEAVES = 4;
    private static final int TICKS_PER_LOG = 60;
    private static final int BAG_TRIGGER = 12;

    private static final Map<Block, Block> SAPLING_FOR_LOG = new HashMap<>();

    static {
        SAPLING_FOR_LOG.put(Blocks.OAK_LOG, Blocks.OAK_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.BIRCH_LOG, Blocks.BIRCH_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.SPRUCE_LOG, Blocks.SPRUCE_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.JUNGLE_LOG, Blocks.JUNGLE_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.ACACIA_LOG, Blocks.ACACIA_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.CHERRY_LOG, Blocks.CHERRY_SAPLING);
        SAPLING_FOR_LOG.put(Blocks.MANGROVE_LOG, Blocks.MANGROVE_PROPAGULE);
    }

    private enum Mode { TO_TREE, CHOPPING, TO_HEARTH }

    private final SettlerEntity settler;
    private final WorkScanner scanner = new WorkScanner();
    private Mode mode;
    private BlockPos treeBase;
    private List<BlockPos> treeLogs = List.of();
    private Block treeLogBlock;
    private int chopTicks;
    private int scanCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public LumbererWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.LUMBERER
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
            return false;
        }
        scanCooldown = 80 + settler.getRandom().nextInt(40);
        List<BlockPos> bases = scanner.scan(s.center, s.radius, 512, 6, this::isTreeBase);
        for (BlockPos base : bases) {
            List<BlockPos> logs = validateTree(base);
            if (!logs.isEmpty()) {
                treeBase = base.immutable();
                treeLogs = logs;
                treeLogBlock = settler.level().getBlockState(base).getBlock();
                mode = Mode.TO_TREE;
                return true;
            }
        }
        return false;
    }

    private boolean isTreeBase(BlockPos pos) {
        BlockState state = settler.level().getBlockState(pos);
        return state.is(BlockTags.LOGS_THAT_BURN)
            && settler.level().getBlockState(pos.below()).is(BlockTags.DIRT);
    }

    /**
     * Natural-tree heuristic: a connected log cluster (capped size and
     * horizontal drift) wearing at least a few non-persistent leaves. Player
     * builds (cabins, pillars) fail this and are left alone.
     */
    private List<BlockPos> validateTree(BlockPos base) {
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> leaves = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(base);
        logs.add(base);
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (logs.contains(next)) {
                            continue;
                        }
                        if (Math.abs(next.getX() - base.getX()) > MAX_DRIFT
                            || Math.abs(next.getZ() - base.getZ()) > MAX_DRIFT
                            || next.getY() < base.getY()) {
                            continue;
                        }
                        BlockState state = settler.level().getBlockState(next);
                        if (state.is(BlockTags.LOGS_THAT_BURN)) {
                            if (logs.size() >= MAX_TREE_LOGS) {
                                return List.of(); // suspiciously large; skip
                            }
                            logs.add(next);
                            frontier.add(next);
                        } else if (state.getBlock() instanceof LeavesBlock
                            && !state.getValue(LeavesBlock.PERSISTENT)) {
                            leaves.add(next);
                        }
                    }
                }
            }
        }
        if (leaves.size() < MIN_LEAVES) {
            return List.of();
        }
        List<BlockPos> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        return sorted;
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
        chopTicks = 0;
        stuckChecks = 0;
        repathTimer = 0;
        if (mode == Mode.TO_HEARTH) {
            pathToHearth();
        } else {
            pathToTree();
        }
    }

    private void pathToTree() {
        if (treeBase != null) {
            settler.getNavigation().moveTo(treeBase.getX() + 0.5, treeBase.getY(),
                treeBase.getZ() + 0.5, 1.0);
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
            case TO_TREE -> tickTravel();
            case CHOPPING -> tickChop();
            case TO_HEARTH -> tickDeposit();
        }
    }

    private void tickTravel() {
        if (treeBase == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(treeBase.getX() + 0.5, treeBase.getY() + 1.0,
            treeBase.getZ() + 0.5);
        if (settler.blockPosition().distSqr(treeBase) <= 7.5) {
            mode = Mode.CHOPPING;
            chopTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.WORK_CHOP);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 6) {
                done = true; // unreachable tree; rescan later
            } else {
                pathToTree();
            }
        }
    }

    private void tickChop() {
        if (treeLogs.isEmpty()) {
            finishTree();
            return;
        }
        BlockPos topLog = treeLogs.get(0);
        settler.getLookControl().setLookAt(treeBase.getX() + 0.5,
            settler.getEyeY(), treeBase.getZ() + 0.5);
        chopTicks++;
        // The strike lands at the animation's impact frame (1s loop).
        if (chopTicks % 20 == 11 && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, treeBase, ModSounds.CHOP.get(),
                SoundSource.NEUTRAL, 0.9F, 0.9F + settler.getRandom().nextFloat() * 0.2F);
        }
        if (chopTicks >= TICKS_PER_LOG) {
            chopTicks = 0;
            if (settler.level() instanceof ServerLevel serverLevel) {
                BlockState logState = serverLevel.getBlockState(topLog);
                if (logState.is(BlockTags.LOGS_THAT_BURN)) {
                    serverLevel.destroyBlock(topLog, false);
                    ItemStack logItem = new ItemStack(logState.getBlock().asItem());
                    ItemStack leftover = settler.bag.addItem(logItem);
                    if (!leftover.isEmpty()) {
                        Block.popResource(serverLevel, topLog, leftover);
                    }
                }
            }
            treeLogs = treeLogs.subList(1, treeLogs.size());
            if (treeLogs.isEmpty()) {
                finishTree();
            }
        }
    }

    private void finishTree() {
        if (settler.level() instanceof ServerLevel serverLevel && treeBase != null) {
            Block sapling = SAPLING_FOR_LOG.get(treeLogBlock);
            if (sapling != null
                && serverLevel.getBlockState(treeBase).isAir()
                && serverLevel.getBlockState(treeBase.below()).is(BlockTags.DIRT)) {
                serverLevel.setBlock(treeBase, sapling.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        treeBase = null;
        settler.setActivity(SettlerActivity.IDLE);
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
    }
}

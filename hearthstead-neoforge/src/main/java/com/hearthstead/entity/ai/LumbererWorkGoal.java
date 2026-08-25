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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

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
    private static final int LIMB_DURATION = 26;
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

    private enum Mode { TO_TREE, CHOPPING, LIMBING, TO_HEARTH }

    private final SettlerEntity settler;
    private final WorkScanner scanner = new WorkScanner();
    private Mode mode;
    private BlockPos treeBase;
    private List<BlockPos> treeLogs = List.of();
    private Block treeLogBlock;
    private int chopTicks;
    private int limbTicks;
    private int haulTicks;
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
            && settler.dayPhase().work()
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
        List<BlockPos> bases = scanner.scanColumns(s.center, s.radius, 512, 6,
            this::trunkInColumn);
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


    /**
     * Looks for the foot of a trunk in one column of the settlement.
     *
     * <p>Reads the surface once and stops there for the overwhelming majority
     * of columns, which hold grass or a roof and no tree at all. Only when the
     * top of the column is itself a log does this walk down the trunk, so the
     * expensive part of the search is paid on trees and nowhere else.
     *
     * <p>The descent is capped: a jungle giant is about thirty logs tall, and
     * a cap well under that is the difference between "no tree here" and a
     * scan that follows a decorative column down to bedrock.
     */
    @Nullable
    private BlockPos trunkInColumn(BlockPos column) {
        Level level = settler.level();
        if (!level.hasChunkAt(column)) {
            return null;
        }
        BlockPos surface = level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        BlockPos.MutableBlockPos cursor = surface.mutable().move(Direction.DOWN);
        if (!level.getBlockState(cursor).is(BlockTags.LOGS_THAT_BURN)) {
            return null;
        }
        for (int step = 0; step < TRUNK_DESCENT; step++) {
            BlockPos below = cursor.below();
            if (!level.getBlockState(below).is(BlockTags.LOGS_THAT_BURN)) {
                return isTreeBase(cursor) ? cursor.immutable() : null;
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }

    private static final int TRUNK_DESCENT = 32;

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
            // Every trip home with a loaded bag is a haul, including the
            // resume-with-full-bag entry -- the walk and the strain accents
            // in tickDeposit must tell the same story.
            settler.setActivity(SettlerActivity.HAULING_LOG);
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
            case LIMBING -> tickLimb();
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
            startLimbing();
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
                    // Point 8 of the job standard: one felled log is one unit
                    // of work, and it is counted HERE -- at the moment the log
                    // comes down, not on a timer while the settler stands
                    // near a tree. Learning by doing is only true if doing is
                    // what gets counted.
                    settler.train(com.hearthstead.entity.Attribute.STRENGTH, 1.0F);
                    // And then they stoop for it. Felling was only ever half
                    // the job (D-016) -- the stoop is triggered at the moment
                    // the log comes down, so it always lands on a log that
                    // actually exists.
                    settler.triggerGatherLog();
                    ItemStack logItem = new ItemStack(logState.getBlock().asItem());
                    ItemStack leftover = settler.bag.addItem(logItem);
                    if (!leftover.isEmpty()) {
                        Block.popResource(serverLevel, topLog, leftover);
                    }
                }
            }
            treeLogs = treeLogs.subList(1, treeLogs.size());
            if (treeLogs.isEmpty()) {
                startLimbing();
            }
        }
    }

    /** Trimming the felled trunk: a short beat between the last strike and
     *  the sapling/hearth wrap-up, per docs/ANIMATION_CATALOGUE.md §3.2 --
     *  the goal already implicitly "limbs" the tree by only chopping the log
     *  column, it just had no clip for that step. */
    private void tickLimb() {
        limbTicks++;
        if (settler.level() instanceof ServerLevel serverLevel) {
            if (limbTicks % LIMB_DURATION == 6 || limbTicks % LIMB_DURATION == 19) {
                // Reuses CHOP's synthesis at higher pitch/shorter tail per
                // the catalogue's own suggestion -- no new sound asset.
                serverLevel.playSound(null, treeBase, ModSounds.CHOP.get(),
                    SoundSource.NEUTRAL, 0.6F, 1.35F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        if (limbTicks >= LIMB_DURATION) {
            limbTicks = 0;
            finishTree();
        }
    }

    private void startLimbing() {
        mode = Mode.LIMBING;
        limbTicks = 0;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.WORK_LIMB);
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
        if (bagCount() > 0) {
            mode = Mode.TO_HEARTH;
            settler.setActivity(SettlerActivity.HAULING_LOG);
            pathToHearth();
        } else {
            settler.setActivity(SettlerActivity.IDLE);
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
        // HAUL_LOG's strain accent: t=1.20s of its 2.4s loop -> tick 24 of 48.
        // Reuses settler_hm (an exhale-shaped voice sound) pitched down --
        // the catalogue's own suggested reuse for this accent, no new asset.
        haulTicks++;
        if (haulTicks % 48 == 24 && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, settler.blockPosition(), ModSounds.SETTLER_HM.get(),
                SoundSource.NEUTRAL, 0.35F, 0.6F + settler.getRandom().nextFloat() * 0.05F);
        }
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

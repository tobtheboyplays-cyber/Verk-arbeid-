package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.tags.BlockTags;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The miner: cuts stone out of the ground under their own mine entrance.
 *
 * <p>A starter trade in both references and the one this roster was missing.
 * Like the lumberjack it is a <b>gathering</b> job rather than a crafting one,
 * so it does not go through {@link com.hearthstead.building.Production} — there
 * is no input to consume, only rock to remove.
 *
 * <h2>It stops when there is nowhere to put the stone</h2>
 *
 * <p>Chest truth (INV-3) applies to a gatherer as much as to a crafter: a
 * miner who breaks a block they cannot store has destroyed something. So the
 * check happens <b>before</b> the swing lands, not after, and a mine with full
 * chests goes quiet rather than grinding rock into nothing.
 *
 * <h2>Bounded, like every scan in this mod</h2>
 *
 * <p>The search for the next block is a capped box under the building and is
 * only run when the miner has nothing to work on. No unbounded per-tick work.
 */
public class MinerWorkGoal extends Goal {

    /** How far down and out from the entrance a miner will work. */
    private static final int REACH_DOWN = 12;
    private static final int REACH_OUT = 6;
    /** Cap on blocks considered per search, so a deep mine cannot stall a tick. */
    private static final int SCAN_BUDGET = 900;
    /** Ticks to cut one block. Slow on purpose: you should see the work. */
    private static final int TICKS_PER_BLOCK = 60;
    private static final int LOOK_INTERVAL = 20;

    private final SettlerEntity settler;
    private Building mine;
    private BlockPos target;
    private int cutTicks;
    private int lookCooldown;

    public MinerWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.MINER || !settler.isBound()
            || settler.getTarget() != null || settler.getEnergy() <= 15.0F
            // The daily labor pool (docs/project/PLAN_EFFORT.md): once
            // spent, no new block starts. The chest-full check further
            // down is about where the ore goes; this is about how much
            // digging one person does in a day.
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
            || !Schedule.shouldWork(settlement, settler, settler.dayPhase())) {
            return false;
        }
        Building building = Employment.employerOf(settlement, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null) {
            return false;
        }
        // Nowhere to put it means no reason to break it.
        if (!hasRoom(level, building)) {
            return false;
        }
        BlockPos found = findStone(level, building);
        if (found == null) {
            return false;
        }
        mine = building;
        target = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (mine == null || target == null || settler.getTarget() != null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return settlement != null
            && Schedule.shouldWork(settlement, settler, settler.dayPhase())
            && settler.level().getBlockState(target).is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    @Override
    public void start() {
        cutTicks = 0;
        settler.setActivity(SettlerActivity.WORK_MINE);
        settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(),
            target.getZ() + 0.5, 0.85);
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        mine = null;
        target = null;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5,
            target.getY() + 0.5, target.getZ() + 0.5);
        if (!settler.blockPosition().closerThan(target, 4)) {
            if (settler.getNavigation().isDone()) {
                settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 0.85);
            }
            return;
        }
        cutTicks++;
        // The strike lands on the clip's contact tick, so sound and motion
        // are the same event.
        if (cutTicks % 19 == 9) {
            level.playSound(null, target, ModSounds.PICK_STRIKE.get(),
                SoundSource.NEUTRAL, 0.85F,
                0.92F + settler.getRandom().nextFloat() * 0.16F);
        }
        if (cutTicks < TICKS_PER_BLOCK) {
            return;
        }
        cutTicks = 0;
        BlockState state = level.getBlockState(target);
        if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            target = null;
            return;
        }
        List<Container> containers = containers(level, mine);
        // WHY (smelter audit, CRITICAL): this used to be
        // `new ItemStack(state.getBlock().asItem())` — the *placeable block
        // item* (minecraft:iron_ore, minecraft:stone, ...), not what mining
        // actually yields. Production's SMELTER recipes take RAW_IRON /
        // RAW_COPPER / RAW_GOLD and the MASON takes COBBLESTONE/STONE, so an
        // automated mine could never feed a smelter. Chest truth means the
        // chest holds what a player would have mined: run the real loot
        // table (Block.getDrops) with a miner's honest tool.
        List<ItemStack> drops = minedDrops(level, target, state);
        for (ItemStack drop : drops) {
            if (!fits(containers, drop)) {
                // Checked again at the last moment: the chests may have filled
                // while this block was being cut.
                target = null;
                return;
            }
        }
        level.destroyBlock(target, false);
        for (ItemStack drop : drops) {
            ItemStack left = insert(containers, drop);
            if (!left.isEmpty()) {
                // INV-3: items are conserved. If the chests filled between
                // the fits() check and now (or two drops raced for one
                // slot), the overflow lands on the floor, never in the void.
                Block.popResource(level, mine.anchor, left);
            }
        }
        settler.train(Attribute.STRENGTH, 1.0F);
        // One mined block is one batch here (the goal already re-scans for
        // the next one rather than chaining several before returning), so
        // 2 per batch is 2 per block (PLAN_EFFORT.md §2).
        settler.spendEffort(2);
        target = null;
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * What mining this block actually yields, straight from the loot table.
     *
     * <p>The tool is a plain iron pickaxe — a miner's honest kit: no silk
     * touch (stone drops cobblestone, ore drops raw ore), no fortune (counts
     * stay predictable). Public so {@code MinerDropsGameTests} can drive the
     * exact same computation deterministically against a real
     * {@link ServerLevel}.
     */
    public static List<ItemStack> minedDrops(ServerLevel level, BlockPos pos,
                                             BlockState state) {
        return Block.getDrops(state, level, pos, level.getBlockEntity(pos),
            null, new ItemStack(Items.IRON_PICKAXE));
    }

    private static List<Container> containers(ServerLevel level, Building building) {
        List<Container> found = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                found.add(container);
            }
        }
        return found;
    }

    private static boolean hasRoom(ServerLevel level, Building building) {
        for (Container container : containers(level, building)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fits(List<Container> containers, ItemStack stack) {
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack in = container.getItem(slot);
                if (in.isEmpty() || (ItemStack.isSameItemSameComponents(in, stack)
                    && in.getCount() < in.getMaxStackSize())) {
                    return true;
                }
            }
        }
        return false;
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
                    int move = Math.min(remaining.getCount(),
                        in.getMaxStackSize() - in.getCount());
                    in.grow(move);
                    container.setChanged();
                    remaining.shrink(move);
                }
            }
        }
        return remaining;
    }

    /**
     * The nearest stone worth cutting, searched downward from the entrance.
     *
     * <p>Downward first so a mine reads as going <i>down</i>, and capped so
     * the search cost cannot grow with how deep the shaft already is.
     */
    private BlockPos findStone(ServerLevel level, Building building) {
        BlockPos anchor = building.anchor;
        int budget = SCAN_BUDGET;
        for (int dy = 1; dy <= REACH_DOWN; dy++) {
            for (int dx = -REACH_OUT; dx <= REACH_OUT; dx++) {
                for (int dz = -REACH_OUT; dz <= REACH_OUT; dz++) {
                    if (--budget < 0) {
                        return null;
                    }
                    BlockPos pos = anchor.offset(dx, -dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                        && !state.isAir()
                        && state.getBlock().asItem() != net.minecraft.world.item.Items.AIR) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}

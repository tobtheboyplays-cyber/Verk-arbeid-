package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
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
    /** MINE_PICK's own clock: never reset by a finished block (60 % 19 != 0
     *  drifted the strike 3 ticks per block), only when the clip restarts. */
    private int swingTicks;
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
            || settler.getTarget() != null) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        // Trade skill (primary, Strength): the pause before the next block
        // is picked shortens; the cut itself and the pick clip do not.
        lookCooldown = com.hearthstead.entity.SkillLevels.shortenWait(settler, LOOK_INTERVAL);
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
        // QA-JOBS J-10: no pickaxe, no mining. The open equipment request
        // (the need icon on the settler sheet) already names the tool.
        if (!holdsPickaxe()) {
            return false;
        }
        // Nowhere to put it means no reason to break it.
        if (!hasRoom(level, building)) {
            WorkStopReasons.report(settler, StopReason.CHEST_FULL, building.anchor);
            return false;
        }
        BlockPos found = findStone(level, building);
        if (found == null) {
            WorkStopReasons.report(settler, StopReason.NO_VALID_TARGET, building.anchor);
            return false;
        }
        WorkStopReasons.clear(settler);
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
            && holdsPickaxe()
            && settler.level() instanceof ServerLevel level
            && MineShaft.diggable(level, target);
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
        if (target == null || mine == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5,
            target.getY() + 0.5, target.getZ() + 0.5);
        if (!settler.blockPosition().closerThan(target, 4)) {
            swingTicks = 0; // walking: the client clip is stopped (!moving gate)
            if (settler.getNavigation().isDone()) {
                settler.getNavigation().moveTo(target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 0.85);
            }
            return;
        }
        cutTicks++;
        // The strike lands on the clip's contact tick, so sound and motion
        // are the same event. swingTicks, not cutTicks: the clip keeps
        // looping across block breaks, so its phase must too.
        swingTicks++;
        if (swingTicks % 19 == 9) {
            WorkSoundSync.play(level, target, ModSounds.PICK_STRIKE.get(), 0.55F, 1.0F);
        }
        // Tech tree Mine, Smelter & Smithy (techtree-craft): iron pick -10%, diamond+ -20%.
        if (cutTicks < com.hearthstead.settlement.techtree.effects.CraftEffects.toolTicks(
                level, settler.settlement(), settler.getMainHandItem(), TICKS_PER_BLOCK)) {
            return;
        }
        cutTicks = 0;
        BlockState state = level.getBlockState(target);
        if (!MineShaft.diggable(level, target)) {
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
        ItemStack pick = settler.getMainHandItem();
        List<ItemStack> drops = minedDrops(level, target, state, pick);
        // Perception: 0..10% chance of one more of the first drop (plan/ATTRIBUTES.md).
        if (!drops.isEmpty() && drops.get(0).getCount() < drops.get(0).getMaxStackSize()
            && com.hearthstead.entity.AttributeRuntime.extraFind(settler)) {
            drops.get(0).grow(1);
        }
        // Tech tree Deep Mine (techtree-craft): 15% of ore blocks give +1 ore.
        if (!drops.isEmpty() && drops.get(0).getCount() < drops.get(0).getMaxStackSize()
            && com.hearthstead.settlement.techtree.effects.CraftEffects.extraOre(
                level, settler.settlement(), state, settler.getRandom())) {
            drops.get(0).grow(1);
        }
        for (ItemStack drop : drops) {
            if (!fits(containers, drop)) {
                // Checked again at the last moment: the chests may have filled
                // while this block was being cut.
                target = null;
                return;
            }
        }
        level.destroyBlock(target, false);
        // J-10: the pickaxe is real, so it wears like the Fisher's rod.
        pick.hurtAndBreak(1, settler, EquipmentSlot.MAINHAND);
        for (ItemStack drop : drops) {
            ItemStack left = insert(containers, drop);
            if (!left.isEmpty()) {
                // INV-3: items are conserved. If the chests filled between
                // the fits() check and now (or two drops raced for one
                // slot), the overflow lands on the floor, never in the void.
                com.hearthstead.util.ItemSpill.conserve(level, mine.anchor, left);
            }
        }
        settler.train(Attribute.STRENGTH, 1.0F);
        com.hearthstead.entity.SkillLevels.completeUnit(settler, 2, Attribute.STRENGTH);
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

    /**
     * The drops for the pickaxe the Miner really holds (J-10). A pick too
     * weak for this block's drops (wood on iron ore) falls back to the plain
     * iron kit above rather than destroying the ore for nothing.
     */
    public static List<ItemStack> minedDrops(ServerLevel level, BlockPos pos,
                                             BlockState state, ItemStack held) {
        if (held == null || held.isEmpty() || !held.isCorrectToolForDrops(state)) {
            return minedDrops(level, pos, state);
        }
        return Block.getDrops(state, level, pos, level.getBlockEntity(pos),
            null, held);
    }

    private boolean holdsPickaxe() {
        EquipmentRequirement pick = EquipmentRequests.requirementFor(Profession.MINER);
        return pick != null && pick.matches(settler.getMainHandItem());
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
        // The next cut is the top of the shallowest reachable column that can
        // be deepened without walling anyone in (MineShaft): the mine still
        // reads as going down layer by layer, but always as a staircase. The
        // old free 13x13x12 scan cut a vertical shaft its own miner could not
        // leave (soak 2026-09-25: miner starved at the bottom of the pit).
        BlockPos anchor = building.anchor;
        // Tech tree Deep Mine (techtree-craft): 12 -> 14 blocks.
        int reach = com.hearthstead.settlement.techtree.effects.CraftEffects.mineReach(
            level, settler.settlement(), REACH_DOWN);
        MineShaft.Model model = MineShaft.survey(level, anchor, reach);
        boolean[] before = MineShaft.reachable(model);
        BlockPos best = null;
        int bestDepth = Integer.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        int budget = SCAN_BUDGET;
        for (int column = 0; column < before.length; column++) {
            if (!before[column] || !model.open[column] || !MineShaft.inDigArea(column)) {
                continue;
            }
            int depth = model.depth[column];
            if (depth >= reach) {
                continue;
            }
            int dx = MineShaft.dx(column);
            int dz = MineShaft.dz(column);
            int distance = Math.abs(dx) + Math.abs(dz);
            if (depth > bestDepth || (depth == bestDepth && distance >= bestDistance)) {
                continue;
            }
            if (--budget < 0) {
                break;
            }
            BlockPos cut = new BlockPos(anchor.getX() + dx, model.surfaceY - depth, anchor.getZ() + dz);
            if (!MineShaft.diggable(level, cut) || !rockBelow(level, cut, reach - depth)) {
                continue;
            }
            int after = MineShaft.depthAfterCut(level, model, cut, reach);
            if (!MineShaft.safeToDeepen(model, column, after, before)) {
                continue;
            }
            best = cut;
            bestDepth = depth;
            bestDistance = distance;
        }
        return best;
    }

    /** Loose cover (dirt, gravel) is only worth cutting to reach pickaxe rock under it. */
    private static boolean rockBelow(ServerLevel level, BlockPos cut, int layers) {
        BlockPos.MutableBlockPos p = cut.mutable();
        for (int i = 0; i <= layers; i++) {
            if (level.getBlockState(p).is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                return true;
            }
            p.move(0, -1, 0);
        }
        return false;
    }
}

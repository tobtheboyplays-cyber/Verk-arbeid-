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
import net.minecraft.world.level.block.Blocks;
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
    /** MINE V2 shaft mode: the shaft and the step being worked (null = quarry). */
    private MineShaftPlan.Site site;
    private MineShaftPlan.Step step;
    private int moveTicks;
    /** Shaft ladder lookups are cached per Mine for a while (bounded scan). */
    private MineShaftPlan.Site cachedSite;
    private java.util.UUID cachedSiteMine;
    private long cachedSiteAt = Long.MIN_VALUE;
    private static final int SITE_RECHECK_TICKS = 200;
    /** Mines seen with a shaft this session: never switched to the quarry. */
    private static final java.util.Set<java.util.UUID> SHAFT_MINES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Ticks to hang a ladder, set a torch or place a sealing block. */
    private static final int PLACE_TICKS = 12;
    /** Walking to the stand cell gives up after this long (then the goal re-plans later). */
    private static final int MOVE_GIVE_UP_TICKS = 600;
    /** Last shaft plan, for tests and live inspection. */
    private String lastShaft = "none";

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
        // MINE V2: a Mine with a shaft ladder is worked as a ladder shaft,
        // a lane and a rock face ([mine] style = shaft, the default). No
        // shaft ladder (an old or player-built mine): the quarry below.
        MineShaftPlan.Site shaftSite = MineConfig.shaftStyle() ? site(level, building) : null;
        if (shaftSite == null && MineConfig.shaftStyle() && SHAFT_MINES.contains(building.id)) {
            // This Mine had a shaft (its ladder is gone?): never start a
            // quarry pit over a ladder shaft; wait until the ladder is back.
            WorkStopReasons.report(settler, StopReason.NO_VALID_TARGET, building.anchor);
            return false;
        }
        if (shaftSite != null) {
            MineShaftPlan.Step next = planShaft(level, settlement, building, shaftSite);
            if (next == null) {
                return false;
            }
            WorkStopReasons.clear(settler);
            mine = building;
            site = shaftSite;
            step = next;
            target = next.target();
            return true;
        }
        site = null;
        step = null;
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
        if (settlement == null
            || !Schedule.shouldWork(settlement, settler, settler.dayPhase())
            || !holdsPickaxe()
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        if (step != null) {
            return site != null;
        }
        return MineShaft.diggable(level, target);
    }

    @Override
    public void start() {
        cutTicks = 0;
        moveTicks = 0;
        settler.setActivity(SettlerActivity.WORK_MINE);
        BlockPos goal = step != null ? step.stand() : target;
        settler.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(),
            goal.getZ() + 0.5, 0.85);
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        mine = null;
        target = null;
        step = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return step != null;
    }

    @Override
    public void tick() {
        if (target == null || mine == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        if (step != null) {
            tickShaft(level);
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

    // ------------------------------------------------ MINE V2: shaft mode ---

    /** For tests and live inspection: the last shaft plan and the step in hand. */
    public String shaftDebug() {
        return "site=" + site + " step=" + step + " last=" + lastShaft + " move=" + moveTicks;
    }

    private MineShaftPlan.Site site(ServerLevel level, Building building) {
        long now = level.getGameTime();
        if (building.id.equals(cachedSiteMine) && now - cachedSiteAt < SITE_RECHECK_TICKS && now >= cachedSiteAt) {
            return cachedSite;
        }
        cachedSiteMine = building.id;
        cachedSiteAt = now;
        cachedSite = MineShaftWork.find(level, building);
        if (cachedSite != null) {
            SHAFT_MINES.add(building.id);
        }
        return cachedSite;
    }

    private MineShaftPlan.Step plan(ServerLevel level, Settlement settlement, Building building,
                                    MineShaftPlan.Site at, List<Container> containers) {
        MineShaftPlan.Step next = MineShaftPlan.next(at, MineShaftWork.probe(level, settlement, building),
            MineShaftWork.floorOne(level, at), MineShaftWork.floorTwo(level, settlement, at),
            MineConfig.laneLength(), MineShaftWork.count(containers, Items.TORCH) > 0);
        lastShaft = next.kind() + " " + next.target() + " from " + next.stand()
            + (next.why().isEmpty() ? "" : " (" + next.why() + ")");
        return next;
    }

    /** The next shaft step the Miner can start now, or null (and why, on the settler sheet). */
    private MineShaftPlan.Step planShaft(ServerLevel level, Settlement settlement, Building building,
                                         MineShaftPlan.Site at) {
        List<Container> containers = containers(level, building);
        MineShaftPlan.Step next = plan(level, settlement, building, at, containers);
        if (next.kind() == MineShaftPlan.Kind.BLOCKED) {
            WorkStopReasons.report(settler, StopReason.NO_VALID_TARGET, building.anchor);
            return null;
        }
        if (next.needsLadder()
            && !MineShaftWork.ladderReady(level, settlement, building, containers, settler)) {
            WorkStopReasons.report(settler, StopReason.WAITING_INPUT, building.anchor);
            return null;
        }
        if (next.kind() == MineShaftPlan.Kind.FILL
            && !MineShaftWork.fillReady(level, settlement, building, containers, settler)) {
            WorkStopReasons.report(settler, StopReason.WAITING_INPUT, building.anchor);
            return null;
        }
        return next;
    }

    private void tickShaft(ServerLevel level) {
        BlockPos stand = step.stand();
        BlockPos feet = settler.blockPosition();
        if (!feet.equals(stand)) {
            swingTicks = 0;
            cutTicks = 0;
            if (++moveTicks > MOVE_GIVE_UP_TICKS) {
                settler.recordRouteFailure("mine_shaft_stand_unreachable");
                target = null; // canContinueToUse ends the goal; canUse re-plans later
                return;
            }
            if (feet.getX() == stand.getX() && feet.getZ() == stand.getZ() && feet.getY() > stand.getY()
                && settler.onClimbable()) {
                // Right above the stand cell on the ladder: let go and slide
                // down the rungs (never a jump; the ladder brakes the fall).
                settler.getNavigation().stop();
                settler.getMoveControl().setWantedPosition(stand.getX() + 0.5, settler.getY(),
                    stand.getZ() + 0.5, 0.3);
                return;
            }
            if (settler.getNavigation().isDone() || moveTicks % 40 == 1) {
                // An exact route (accuracy 0): the pathfinder climbs ladders;
                // a route one block short would leave him on the wrong rung.
                net.minecraft.world.level.pathfinder.Path route = settler.getNavigation().createPath(stand, 0);
                boolean sameLevelStep = feet.getY() == stand.getY()
                    && Math.abs(feet.getX() - stand.getX()) + Math.abs(feet.getZ() - stand.getZ()) == 1
                    && !level.getBlockState(stand).is(Blocks.LADDER);
                if (route != null && route.canReach()) {
                    settler.getNavigation().moveTo(route, 0.85);
                } else if (sameLevelStep) {
                    // One plain step across the shaft floor: straight at the cell.
                    settler.getNavigation().stop();
                    settler.getMoveControl().setWantedPosition(stand.getX() + 0.5, stand.getY(),
                        stand.getZ() + 0.5, 0.7);
                } else {
                    settler.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.85);
                }
            }
            return;
        }
        settler.getNavigation().stop();
        BlockPos t = step.target();
        settler.getLookControl().setLookAt(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5);
        if (overTarget(t)) {
            // Half over the block about to go: centre on the stand cell first.
            settler.getMoveControl().setWantedPosition(stand.getX() + 0.5, stand.getY(),
                stand.getZ() + 0.5, 0.5);
            return;
        }
        boolean cutting = step.kind() == MineShaftPlan.Kind.DIG || step.kind() == MineShaftPlan.Kind.FACE;
        cutTicks++;
        if (cutting) {
            swingTicks++;
            if (swingTicks % 19 == 9) {
                WorkSoundSync.play(level, t, ModSounds.PICK_STRIKE.get(), 0.55F, 1.0F);
            }
            if (cutTicks < com.hearthstead.settlement.techtree.effects.CraftEffects.toolTicks(
                    level, settler.settlement(), settler.getMainHandItem(), TICKS_PER_BLOCK)) {
                return;
            }
        } else if (cutTicks < PLACE_TICKS) {
            return;
        }
        cutTicks = 0;
        Settlement settlement = settler.settlement();
        List<Container> containers = containers(level, mine);
        // The world may have changed while he swung: act only if a fresh plan
        // still asks for exactly this step (every safety check re-run).
        MineShaftPlan.Step fresh = plan(level, settlement, mine, site, containers);
        if (fresh.kind() != step.kind() || !java.util.Objects.equals(fresh.target(), t)
            || !java.util.Objects.equals(fresh.stand(), stand)) {
            advance(level, settlement);
            return;
        }
        boolean done = switch (step.kind()) {
            case DIG -> cutShaftBlock(level, containers, t);
            case FACE -> workFace(level, containers, t);
            case LADDER -> MineShaftWork.takeOne(containers, Items.LADDER) && hangLadder(level, containers, t);
            case FILL -> fill(level, containers, t);
            case TORCH -> MineShaftWork.takeOne(containers, Items.TORCH) && setTorch(level, containers, t);
            case BLOCKED -> false;
        };
        if (!done) {
            target = null;
            return;
        }
        advance(level, settlement);
    }

    /** Straight on to the next step (no pause between blocks), or end the goal. */
    private void advance(ServerLevel level, Settlement settlement) {
        if (settlement == null || !hasRoom(level, mine)) {
            target = null;
            return;
        }
        MineShaftPlan.Step next = planShaft(level, settlement, mine, site);
        if (next == null) {
            target = null;
            return;
        }
        if (!java.util.Objects.equals(next.stand(), step == null ? null : step.stand())) {
            moveTicks = 0;
        }
        step = next;
        target = next.target();
    }

    /** True while the Miner's body rests on {@code t} (a cut would drop him). */
    private boolean overTarget(BlockPos t) {
        net.minecraft.world.phys.AABB bb = settler.getBoundingBox();
        return t.getY() + 1 <= bb.minY + 0.05 && t.getY() + 1 >= bb.minY - 0.3
            && bb.minX < t.getX() + 1 && bb.maxX > t.getX()
            && bb.minZ < t.getZ() + 1 && bb.maxZ > t.getZ();
    }

    private boolean cutShaftBlock(ServerLevel level, List<Container> containers, BlockPos t) {
        BlockState state = level.getBlockState(t);
        if (!MineShaft.diggable(level, t)) {
            return false;
        }
        // A new ladder-column cell gets its ladder the moment it opens, so
        // the ladder comes out of the chest before the swing lands.
        if (step.ladderAfter() && !MineShaftWork.takeOne(containers, Items.LADDER)) {
            return false;
        }
        ItemStack pick = settler.getMainHandItem();
        List<ItemStack> drops = minedDrops(level, t, state, pick);
        bonuses(level, drops, state);
        for (ItemStack drop : drops) {
            if (!fits(containers, drop)) {
                if (step.ladderAfter()) {
                    giveBack(level, containers, new ItemStack(Items.LADDER));
                }
                return false;
            }
        }
        level.destroyBlock(t, false);
        pick.hurtAndBreak(1, settler, EquipmentSlot.MAINHAND);
        store(level, containers, drops);
        if (step.ladderAfter()) {
            hangLadder(level, containers, t);
        }
        worked();
        return true;
    }

    /** One cut at the face: the block stays, a find is rolled from the level's table. */
    private boolean workFace(ServerLevel level, List<Container> containers, BlockPos face) {
        ItemStack pick = settler.getMainHandItem();
        List<MineFaceTable.Entry> table = MineFaceTable.table(
            level.getServer() == null ? null : level.getServer().getResourceManager(), step.level());
        MineFaceTable.Find find = MineFaceTable.roll(table, pick, settler.getRandom());
        List<ItemStack> drops = new ArrayList<>();
        drops.add(find.stack());
        bonuses(level, drops, find.ore());
        for (ItemStack drop : drops) {
            if (!fits(containers, drop)) {
                WorkStopReasons.report(settler, StopReason.CHEST_FULL, mine.anchor);
                return false;
            }
        }
        pick.hurtAndBreak(1, settler, EquipmentSlot.MAINHAND);
        store(level, containers, drops);
        // What he found shows at the face: the ore's (or stone's) break
        // particles, and the find popping out toward him.
        net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(face);
        net.minecraft.world.phys.Vec3 toward = settler.position().add(0, 1.0, 0).subtract(at).normalize();
        level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                net.minecraft.core.particles.ParticleTypes.BLOCK, find.ore()),
            at.x + toward.x * 0.55, at.y + 0.3, at.z + toward.z * 0.55, 14, 0.2, 0.25, 0.2, 0.05);
        if (!find.stack().is(Items.COBBLESTONE)) {
            level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(
                    net.minecraft.core.particles.ParticleTypes.ITEM, find.stack()),
                at.x + toward.x * 0.6, at.y + 0.2, at.z + toward.z * 0.6, 0,
                toward.x, 0.35, toward.z, 0.18);
        }
        level.playSound(null, face, find.ore().getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.6F, 1.0F);
        worked();
        return true;
    }

    /** Perception (extraFind) and the Deep Mine extra ore, as in the quarry. */
    private void bonuses(ServerLevel level, List<ItemStack> drops, BlockState ore) {
        if (!drops.isEmpty() && drops.get(0).getCount() < drops.get(0).getMaxStackSize()
            && com.hearthstead.entity.AttributeRuntime.extraFind(settler)) {
            drops.get(0).grow(1);
        }
        if (!drops.isEmpty() && drops.get(0).getCount() < drops.get(0).getMaxStackSize()
            && com.hearthstead.settlement.techtree.effects.CraftEffects.extraOre(
                level, settler.settlement(), ore, settler.getRandom())) {
            drops.get(0).grow(1);
        }
    }

    private void store(ServerLevel level, List<Container> containers, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            ItemStack left = insert(containers, drop);
            if (!left.isEmpty()) {
                com.hearthstead.util.ItemSpill.conserve(level, mine.anchor, left);
            }
        }
    }

    private void giveBack(ServerLevel level, List<Container> containers, ItemStack stack) {
        ItemStack left = insert(containers, stack);
        if (!left.isEmpty()) {
            com.hearthstead.util.ItemSpill.conserve(level, mine.anchor, left);
        }
    }

    private void worked() {
        settler.train(Attribute.STRENGTH, 1.0F);
        com.hearthstead.entity.SkillLevels.completeUnit(settler, 2, Attribute.STRENGTH);
        settler.spendEffort(2);
    }

    /** Hangs a ladder (already taken from the chest) in {@code t}; gives it back if it cannot hang. */
    private boolean hangLadder(ServerLevel level, List<Container> containers, BlockPos t) {
        BlockState ladder = Blocks.LADDER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LadderBlock.FACING, site.facing());
        BlockState here = level.getBlockState(t);
        if ((here.isAir() || here.canBeReplaced()) && here.getFluidState().isEmpty()
            && ladder.canSurvive(level, t)) {
            level.setBlockAndUpdate(t, ladder);
            WorkSoundSync.play(level, t, ModSounds.BUILDER_LADDER_RUNG.get(), 0.5F, 1.0F);
            return true;
        }
        giveBack(level, containers, new ItemStack(Items.LADDER));
        return false;
    }

    private boolean setTorch(ServerLevel level, List<Container> containers, BlockPos t) {
        BlockState torch = Blocks.TORCH.defaultBlockState();
        if (level.getBlockState(t).canBeReplaced() && level.getBlockState(t).getFluidState().isEmpty()
            && torch.canSurvive(level, t)) {
            level.setBlockAndUpdate(t, torch);
            return true;
        }
        giveBack(level, containers, new ItemStack(Items.TORCH));
        return false;
    }

    /** Seals fluid or fills a hole with plain stone from the Mine's chests. */
    private boolean fill(ServerLevel level, List<Container> containers, BlockPos t) {
        MineShaftPlan.Cell c = MineShaftWork.cell(level, t);
        if (c != MineShaftPlan.Cell.OPEN && c != MineShaftPlan.Cell.FLUID) {
            return false;
        }
        BlockState block = MineShaftWork.takeFill(containers);
        if (block == null) {
            return false;
        }
        level.setBlockAndUpdate(t, block);
        return true;
    }
}

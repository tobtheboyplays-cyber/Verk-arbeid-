package com.hearthstead.settlement.builder;

import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The only code that changes the world for a build site. Every exact-once
 * guarantee of the Builder lives in this one class, so it can be reviewed
 * as one unit:
 *
 * <ol>
 *   <li><b>Place</b>: the step's item is taken out of the Builder's bag and
 *       the block is set in the same call. The bag is checked all-or-nothing
 *       first; if {@code setBlock} refuses, the item goes straight back into
 *       the bag. The step's {@code done}/{@code placed} bits are set in that
 *       same call. No save can land between the charge and the block.</li>
 *   <li><b>Already there</b>: a cell that already holds an equivalent block
 *       is marked done and never charged.</li>
 *   <li><b>Clear</b>: natural terrain is removed and its loot-table drops
 *       go to the hut (or the ground) -- salvaged, never deleted. A player
 *       block is only removed with "allow overwrite"; a block entity never.</li>
 *   <li><b>Dismantle</b>: only a block this job placed, still standing as
 *       placed, is removed; its own cost is refunded to the hut in the same
 *       call, and its {@code placed} bit on the source job is cleared.</li>
 * </ol>
 */
public final class BuildExecutor {

    public enum Outcome {
        /** The step is finished (placed, cleared, drained or found satisfied). */
        DONE,
        /** The bag lacks the step's item: go and fetch. Nothing changed. */
        NEED_MATERIAL,
        /** A player block is in the way and overwrite is not allowed. Nothing changed. */
        BLOCKED,
        /** Not yet: its support is missing. Nothing changed; retry later. */
        DEFER,
        /** Can never be done as planned (e.g. a block entity in the way). */
        IMPOSSIBLE
    }

    private BuildExecutor() {
    }

    /**
     * Executes step {@code i} of {@code job}. {@code bag} is the Builder's
     * real bag; {@code hut} the hut containers salvage and refunds go into;
     * {@code where} the Builder's position for overflow drops.
     */
    public static Outcome execute(ServerLevel level, BuildJob job, int i, Container bag,
                                  List<Container> hut, BlockPos where) {
        if (job.isDone(i)) {
            return Outcome.DONE;
        }
        BlockPos pos = job.pos(i);
        if (!level.isLoaded(pos)) {
            return Outcome.DEFER;
        }
        if (job.phase(i) == BuildPhase.DISMANTLE) {
            return dismantle(level, job, i, hut, where);
        }
        byte flags = job.flags(i);
        if ((flags & BuildJob.F_FIT_PLAN) != 0) {
            return fitPlan(level, job, i, bag);
        }
        BlockState present = level.getBlockState(pos);
        if ((flags & BuildJob.F_POUR) != 0) {
            return pour(level, job, i, pos, present, bag, (flags & BuildJob.F_POUR_PAID) != 0);
        }
        if ((flags & BuildJob.F_DRAIN) != 0) {
            if (!BuilderTerrain.fluid(present)) {
                job.markDone(i, false);
                return Outcome.DONE;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            // A fluid that flows straight back is fed from outside: the step
            // stays open and the stuck guard bounds the retries.
            if (BuilderTerrain.fluid(level.getBlockState(pos))) {
                return Outcome.DEFER;
            }
            job.markDone(i, false);
            return Outcome.DONE;
        }
        if ((flags & BuildJob.F_CLEAR) != 0) {
            Outcome cleared = clear(level, job, pos, present, hut, where);
            if (cleared == Outcome.DONE) {
                job.markDone(i, false);
            } else if (cleared == Outcome.BLOCKED) {
                job.markBlocked(i);
            }
            return cleared;
        }
        return place(level, job, i, pos, present, bag, hut, where);
    }

    // -------------------------------------------------------------- pour ---

    /**
     * Pours one water source. A paid cell turns one water bucket in the
     * sack into an empty bucket (it goes back to the hut with the leftovers);
     * a free cell is scooped from a source this job already poured. Exact
     * once: the bucket swap and the block change happen together, and a
     * cell that already holds a source is done for free.
     */
    private static Outcome pour(ServerLevel level, BuildJob job, int i, BlockPos pos, BlockState present,
                                Container bag, boolean paid) {
        if (BuilderMaterials.waterSource(present)) {
            job.markDone(i, false);
            return Outcome.DONE;
        }
        if (!present.isAir() && !present.canBeReplaced() && !BuilderTerrain.fluid(present)) {
            return Outcome.DEFER; // its CLEAR step has not run yet
        }
        BlockState water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        if (!paid) {
            if (!anyPoured(job)) {
                job.noteWhy(i, "no water poured to scoop from");
                return Outcome.IMPOSSIBLE;
            }
            if (!level.setBlock(pos, water, Block.UPDATE_ALL)) {
                return Outcome.DEFER;
            }
            job.markDone(i, false);
            return Outcome.DONE;
        }
        int slot = -1;
        for (int s = 0; s < bag.getContainerSize(); s++) {
            if (bag.getItem(s).is(net.minecraft.world.item.Items.WATER_BUCKET)) {
                slot = s;
                break;
            }
        }
        if (slot < 0) {
            return Outcome.NEED_MATERIAL;
        }
        ItemStack before = bag.getItem(slot).copy();
        bag.setItem(slot, new ItemStack(net.minecraft.world.item.Items.BUCKET));
        if (!level.setBlock(pos, water, Block.UPDATE_ALL)) {
            bag.setItem(slot, before); // nothing poured: the bucket stays full
            return Outcome.DEFER;
        }
        bag.setChanged();
        job.markDone(i, false);
        return Outcome.DONE;
    }

    private static boolean anyPoured(BuildJob job) {
        for (int k = 0; k < job.size(); k++) {
            if (job.hasFlag(k, BuildJob.F_POUR) && job.isDone(k)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- place ---

    private static Outcome place(ServerLevel level, BuildJob job, int i, BlockPos pos,
                                 BlockState present, Container bag, List<Container> hut,
                                 BlockPos where) {
        BlockState target = job.state(i);
        MaterialRules.Match match = BuilderMaterials.compare(target, present);
        BlockPos pairPos = job.companionPos(i);
        BlockState pairState = job.companionState(i);
        if (match == MaterialRules.Match.SAME) {
            // A pair is only satisfied when BOTH halves stand (Codex T3b).
            if (pairPos == null || pairState == null
                || BuilderMaterials.compare(pairState, level.getBlockState(pairPos)) == MaterialRules.Match.SAME) {
                job.markDone(i, false);
                return Outcome.DONE;
            }
            BlockState there = level.getBlockState(pairPos);
            if (there.isAir() || there.canBeReplaced()) {
                // The item is the standing half: completing the pair is free.
                level.setBlock(pairPos, pairState, Block.UPDATE_ALL);
                job.markDone(i, false);
                return Outcome.DONE;
            }
        }
        if (match == MaterialRules.Match.REORIENT && present.hasBlockEntity()) {
            // A chest or barrel with contents facing the other way: turning it
            // would mean rebuilding it. Accept it as it stands, uncharged.
            job.markDone(i, false);
            return Outcome.DONE;
        }
        if (match == MaterialRules.Match.REORIENT && job.companionPos(i) == null) {
            // Same block, same material, only turned: set it right, charge
            // nothing, and never count it as placed (a dismantle must not
            // refund a block this job did not pay for).
            if (target.canSurvive(level, pos) && level.setBlock(pos, target, Block.UPDATE_ALL)) {
                job.markDone(i, false);
                return Outcome.DONE;
            }
            return Outcome.DEFER;
        }
        // Support first: nothing is removed or charged for a block that
        // could not stand here yet.
        if (!target.canSurvive(level, pos)) {
            return Outcome.DEFER;
        }
        if (BuildOrder.isFalling(Blueprint.idOf(target))
            && !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            return Outcome.DEFER;
        }
        BlockPos companionPos = job.companionPos(i);
        BlockState companion = job.companionState(i);
        if (companionPos != null) {
            BlockState there = level.getBlockState(companionPos);
            if (!there.isAir() && !there.canBeReplaced() && !there.is(companion.getBlock())) {
                Outcome freed = clear(level, job, companionPos, there, hut, where);
                if (freed != Outcome.DONE) {
                    if (freed == Outcome.BLOCKED) {
                        job.markBlocked(i);
                    }
                    return freed;
                }
            }
        }
        List<BuilderMaterials.ItemCount> costs = BuilderMaterials.costsOfStep(job, i);
        for (BuilderMaterials.ItemCount cost : costs) {
            if (BuilderStock.bagCount(bag, cost.item()) < cost.count()) {
                return Outcome.NEED_MATERIAL;
            }
        }
        if (!present.isAir() && !present.canBeReplaced() && !BuilderTerrain.fluid(present)) {
            Outcome freed = clear(level, job, pos, present, hut, where);
            if (freed != Outcome.DONE) {
                if (freed == Outcome.BLOCKED) {
                    job.markBlocked(i);
                }
                return freed;
            }
        }
        // ---- the charge and the block, together ----
        for (BuilderMaterials.ItemCount cost : costs) {
            if (!BuilderStock.takeFromBag(bag, cost.item(), cost.count())) {
                // Cannot happen after the check above within one call; if it
                // ever does, refund what was taken and change nothing.
                refund(bag, costs, cost);
                return Outcome.NEED_MATERIAL;
            }
        }
        boolean set = level.setBlock(pos, target, Block.UPDATE_ALL);
        if (!set) {
            refund(bag, costs, null);
            return Outcome.DEFER;
        }
        if (companionPos != null && companion != null) {
            level.setBlock(companionPos, companion, Block.UPDATE_ALL);
        }
        job.markDone(i, !costs.isEmpty());
        playPlaceSound(level, pos, target);
        return Outcome.DONE;
    }

    /** Puts charged items back, up to (not including) {@code stopAt}; all when null. */
    private static void refund(Container bag, List<BuilderMaterials.ItemCount> costs,
                               BuilderMaterials.ItemCount stopAt) {
        for (BuilderMaterials.ItemCount cost : costs) {
            if (cost == stopAt) {
                return;
            }
            ItemStack back = new ItemStack(cost.item(), cost.count());
            int left = BuilderStock.insert(bag, back);
            if (left > 0) {
                // The bag had room a moment ago; this is unreachable in one
                // tick, but a lost item is worse than a loud log.
                com.mojang.logging.LogUtils.getLogger().error(
                    "Builder refund could not re-fit {} x{} into the bag", cost.item(), left);
            }
        }
    }

    private static void playPlaceSound(ServerLevel level, BlockPos pos, BlockState state) {
        var sound = state.getSoundType(level, pos, null);
        level.playSound(null, pos, sound.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        // Sound pass: a solid "seated" thunk under the block's own voice, so builder
        // placements read as craft rather than a player's click.
        level.playSound(null, pos, com.hearthstead.registry.ModSounds.BUILDER_PLACE.get(),
            net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 0.95F + level.random.nextFloat() * 0.1F);
    }

    // ------------------------------------------------------------- clear ---

    /** Removes what stands at {@code pos} if the rules allow, salvaging its drops. */
    static Outcome clear(ServerLevel level, BuildJob job, BlockPos pos, BlockState present,
                         List<Container> hut, BlockPos where) {
        if (present.isAir()) {
            return Outcome.DONE;
        }
        if (BuilderTerrain.fluid(present)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return Outcome.DONE;
        }
        boolean natural = BuilderTerrain.natural(present);
        if (!natural) {
            if (!BuilderTerrain.overwritable(level, pos, present)) {
                return Outcome.IMPOSSIBLE;
            }
            if (!job.allowOverwrite) {
                return Outcome.BLOCKED;
            }
        }
        // Salvage. Natural terrain gives its loot-table drops (grass gives
        // dirt, stone gives cobble). A player block the player allowed us to
        // replace comes back as ITSELF -- a bookshelf returns as a bookshelf,
        // not three books; glass as glass -- so the Builder never turns
        // something the player built into less than it was.
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> drops = new java.util.ArrayList<>();
        if (!natural) {
            // Same rules that charge a placement refund a removal: a double
            // slab is two slabs, a potted flower is a pot and a flower.
            for (BuilderMaterials.ItemCount cost : BuilderMaterials.costsOf(present)) {
                if (cost.buildable()) {
                    drops.add(new ItemStack(cost.item(), cost.count()));
                }
            }
        }
        if (drops.isEmpty()) {
            drops = Block.getDrops(present, level, pos, blockEntity);
        }
        // Two-block things come out whole, refunded once (from the half that
        // carries the item), never half-deleted by a shape update.
        BlockPos partner = partnerOf(pos, present);
        if (partner != null && level.getBlockState(partner).is(present.getBlock())) {
            BlockState partnerState = level.getBlockState(partner);
            if (drops.isEmpty() || BuilderMaterials.costsOf(present).isEmpty()) {
                drops = new java.util.ArrayList<>();
                for (BuilderMaterials.ItemCount cost : BuilderMaterials.costsOf(partnerState)) {
                    if (cost.buildable() && !natural) {
                        drops.add(new ItemStack(cost.item(), cost.count()));
                    }
                }
            }
            level.setBlock(partner, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (ItemStack drop : drops) {
            BuilderStock.store(level, hut, drop, where);
        }
        return Outcome.DONE;
    }

    /** The other half of a door, bed or tall plant, or null. */
    @javax.annotation.Nullable
    static BlockPos partnerOf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? pos.below() : pos.above();
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) {
            return state.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? pos.below() : pos.above();
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            Direction facing = state.getValue(net.minecraft.world.level.block.BedBlock.FACING);
            return state.getValue(net.minecraft.world.level.block.BedBlock.PART)
                == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                ? pos.relative(facing.getOpposite()) : pos.relative(facing);
        }
        return null;
    }

    // ---------------------------------------------------------- fit plan ---

    /**
     * FINISH: the plaque this job hung gets its typed plan, drawn from the
     * build-plan recipe's own inputs out of the bag, and starts surveying --
     * so the new building registers through the one path every building
     * does ("the plaque is the surveyor").
     */
    private static Outcome fitPlan(ServerLevel level, BuildJob job, int i, Container bag) {
        BlockPos pos = job.pos(i);
        if (!(level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)) {
            // The plaque is gone (broken by someone): nothing to fit.
            return Outcome.IMPOSSIBLE;
        }
        if (!plaque.insertedPlan().isEmpty()) {
            job.markDone(i, false); // already dedicated (by hand or earlier)
            return Outcome.DONE;
        }
        if (job.fitPlanType == null) {
            job.markDone(i, false);
            return Outcome.DONE;
        }
        List<BuilderMaterials.ItemCount> costs = BuilderMaterials.costsOfStep(job, i);
        for (BuilderMaterials.ItemCount cost : costs) {
            if (BuilderStock.bagCount(bag, cost.item()) < cost.count()) {
                return Outcome.NEED_MATERIAL;
            }
        }
        ItemStack plan = PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()),
            BuildingType.byId(job.fitPlanType));
        for (BuilderMaterials.ItemCount cost : costs) {
            BuilderStock.takeFromBag(bag, cost.item(), cost.count());
        }
        if (!plaque.insertPlan(level, plan)) {
            refund(bag, costs, null);
            return Outcome.DEFER;
        }
        // Not "placed": the plan is not refunded by cost. A dismantle takes it
        // back as the exact fitted item when it removes the plaque itself.
        job.markDone(i, false);
        return Outcome.DONE;
    }

    // --------------------------------------------------------- dismantle ---

    private static Outcome dismantle(ServerLevel level, BuildJob job, int i, List<Container> hut,
                                     BlockPos where) {
        BlockPos pos = job.pos(i);
        BlockState planned = job.state(i);
        BlockState present = level.getBlockState(pos);
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        BuildJob source = job.targetId == null ? null : data.job(job.settlementId, job.targetId);
        int sourceIndex = source == null ? -1 : indexOf(source, pos);
        // Codex T3b: a block whose material changed since (a double slab the
        // player cut to a single one) is the player's now -- left standing,
        // refunded nothing.
        if (!present.is(planned.getBlock())
            || BuilderMaterials.compare(planned, present) == MaterialRules.Match.DIFFERENT) {
            // Not what we placed any more (a player changed it): leave it.
            if (source != null && sourceIndex >= 0) {
                source.markRemoved(sourceIndex);
            }
            job.markDone(i, false);
            return Outcome.DONE;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof PlaqueBlockEntity plaque) {
            // The fitted plan comes back as the exact item that was fitted.
            ItemStack plan = plaque.extractPlan(level, null);
            BuilderStock.store(level, hut, plan, where);
        } else if (blockEntity instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.removeItemNoUpdate(slot);
                BuilderStock.store(level, hut, stack, where);
            }
        }
        // Refund what actually stands (equal in material to the plan here).
        List<BuilderMaterials.ItemCount> refunds = BuilderMaterials.costsOf(present);
        BlockPos companionPos = job.companionPos(i);
        if (companionPos != null && level.getBlockState(companionPos).is(planned.getBlock())) {
            level.setBlock(companionPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        // Known-shape removal: a torch on this wall must not pop off as a
        // loose drop -- it has its own step and its own refund.
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        for (BuilderMaterials.ItemCount cost : refunds) {
            if (cost.buildable()) {
                BuilderStock.store(level, hut, new ItemStack(cost.item(), cost.count()), where);
            }
        }
        if (source != null && sourceIndex >= 0) {
            source.markRemoved(sourceIndex);
        }
        job.markDone(i, false);
        return Outcome.DONE;
    }

    private static int indexOf(BuildJob job, BlockPos pos) {
        for (int j = 0; j < job.size(); j++) {
            if (job.pos(j).equals(pos) && job.isPlaced(j) && !job.hasFlag(j, BuildJob.F_FIT_PLAN)) {
                return j;
            }
        }
        return -1;
    }

    /** Unused helper kept for loot-based salvage variants (tool-less drops). */
    static LootParams.Builder lootAt(ServerLevel level, BlockPos pos) {
        return new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
            .withParameter(LootContextParams.TOOL, ItemStack.EMPTY);
    }
}

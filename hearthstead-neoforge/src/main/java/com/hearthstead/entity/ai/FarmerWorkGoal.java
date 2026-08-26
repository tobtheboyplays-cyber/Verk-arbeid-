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
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The farmer's day: find mature crops, harvest them by hand, replant, carry
 * the yield home, and -- when there is nothing ripe to harvest -- till bare
 * ground next to existing farmland and water dry farmland (§2 of
 * docs/ANIMATION_CATALOGUE.md: the farmer's four tasks read as four
 * different heights, so all four need a live trigger, not just harvesting).
 *
 * <p>Bootstrap (farmer audit 2026-08-25): a brand-new farmhouse starts with
 * no crops at all, so the farmer can also fetch seeds from the farmhouse's
 * own chests, till the tended plot without a pre-existing crop anchor, and
 * give a fresh tile its FIRST planting (WORK_PLANT) -- without which the
 * replant path above never gets its first harvest to replant after.
 *
 * <h2>Sugar cane (SURVIVAL_AUDIT.md F7, GAPS-1)</h2>
 *
 * <p>{@code SugarCaneBlock} is not a {@link CropBlock} -- it has no age-to-
 * maturity state a {@code CropBlock}-typed cast could ever read, it never
 * grows on farmland, and it never needs replanting after a harvest (the
 * BASE segment left standing keeps growing new segments on its own, exactly
 * like a player-built cane farm). So this is a real, parallel extension, not
 * a cast onto the crop machinery above: {@link #isMatureCane} finds a
 * harvestable TOP segment (anything with cane, not dirt/sand, beneath it --
 * cutting it leaves the regrowing base untouched) and folds into the SAME
 * ripe-harvest queue every other crop uses ({@link #isHarvestable}), while
 * planting a brand-new base uses its own, lowest-priority, separately
 * bounded scan ({@link #caneScanner}/{@link #caneQueue}) so a water-adjacent
 * tile that would ALSO serve ordinary crop tilling is always offered to
 * crops first -- cane only ever claims ground the crop-tilling scan above
 * has no use for. See {@link #isCaneSite} for why that scan reuses vanilla's
 * OWN {@code SugarCaneBlock#canSurvive} rule rather than hand-duplicating
 * its dirt/sand/water-adjacency logic a second time.
 *
 * <p><b>Animation note (handed to the coordinator, not invented here):</b>
 * cane planting reuses {@code SettlerActivity.WORK_PLANT} (the same clip a
 * fresh crop tile's first planting already uses) and cane harvesting reuses
 * {@code SettlerActivity.WORK_HARVEST}. Both are placeholders: kneeling to
 * press a stalk into sand beside water and reaching up to snap the top
 * joint off a standing cane stalk are visibly different motions from
 * tilling a crop bed or pulling a wheat stand, and this mod's own standing
 * rule is one keyframe clip per task. {@code SettlerAnimations.java} and the
 * animation files are outside this worker's ownership, so this is flagged
 * rather than silently reusing a clip and calling the job finished.
 */
public class FarmerWorkGoal extends Goal {
    private static final int HARVEST_DURATION = 36;
    /** Two planting motions, two clips, two durations (farmer audit
     *  2026-08-25). The REPLANT after a harvest keeps WORK_SOW (D-016, the
     *  owner's broadcast-sowing signature) whose SOW_BROADCAST clip is a
     *  1.40s loop = 28 ticks -- the old shared 40-tick constant made every
     *  replant double-loop and cut off mid-swing. A FIRST planting on a
     *  fresh tile uses WORK_PLANT instead, whose FARM_PLANT clip is
     *  authored at 2.0s = 40 ticks (sound contract: seed_press at t=0.70s
     *  -> tick 14 of 40, tools/anim_check.py SOUND_CONTRACTS). */
    private static final int REPLANT_DURATION = 28;
    private static final int FIRST_PLANT_DURATION = 40;
    private static final int TILL_DURATION = 30;
    private static final int WATER_DURATION = 48;
    private static final int BAG_TRIGGER = 8;
    private static final int TILL_ANCHOR_RANGE = 3;
    /** Bootstrap withdrawal cap: how many seeds one visit to the
     *  farmhouse's own chests may move into the bag. See ensureSeedInBag()
     *  for why the effective cap also stays under {@link #BAG_TRIGGER}. */
    private static final int SEED_WITHDRAW_CAP = 8;
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
    /** Own instance, own cursor (WorkScanner's own contract) -- the lowest
     *  priority scan, only ever consulted once {@link #maintainQueue} has
     *  nothing this call; see the class doc's sugar cane section. */
    private final WorkScanner caneScanner = new WorkScanner();
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Deque<BlockPos> maintainQueue = new ArrayDeque<>();
    private final Deque<BlockPos> caneQueue = new ArrayDeque<>();
    private Mode mode;
    private BlockPos target;
    private BlockPos maintainTarget;
    private boolean maintainIsWater;
    /** True when the maintain target is bare farmland the farmer is going
     *  to PLANT rather than water -- the audit's "re-water bare tiles
     *  forever" symptom was exactly this case mis-filed as watering. */
    private boolean maintainIsPlant;
    /** True when {@link #maintainTarget} is a sugar-cane planting site (an
     *  air block over a legal vanilla cane base, see {@link #isCaneSite}),
     *  never a crop tile -- checked ahead of {@link #maintainIsPlant} in
     *  {@link #tickMaintainTravel} since it plants directly AT the target
     *  rather than above it. */
    private boolean maintainIsCane;
    private Block harvestedCrop;
    /** The crop the current PLANTING pass will place, and how long the
     *  pass runs -- {@link #REPLANT_DURATION} under WORK_SOW,
     *  {@link #FIRST_PLANT_DURATION} under WORK_PLANT. */
    private Block plantCrop;
    private int plantDuration;
    private int workTicks;
    private int scanCooldown;
    private int maintainScanCooldown;
    private int caneScanCooldown;
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
            queue.addAll(scanner.scan(s.center, s.radius, 512, 12, this::isHarvestable));
        }
        while (!queue.isEmpty()) {
            BlockPos candidate = queue.poll();
            if (isHarvestable(candidate)) {
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
            if (!isMaintainable(candidate)) {
                continue;
            }
            if (settler.level().getBlockState(candidate).is(Blocks.FARMLAND)) {
                if (settler.level().getBlockState(candidate.above()).isAir()) {
                    // BARE FARMLAND (farmer audit 2026-08-25, CRITICAL): a
                    // tilled tile with nothing on it used to be watered
                    // forever and planted never. It is a planting site when
                    // a seed can be found, and skipped entirely when not --
                    // it is no longer a watering target either way.
                    if (!ensureSeedInBag()) {
                        continue;
                    }
                    maintainIsWater = false;
                    maintainIsPlant = true;
                } else {
                    maintainIsWater = true;
                    maintainIsPlant = false;
                }
            } else {
                // Bare ground. The classic expansion path (next to farmland,
                // near a standing crop) stays exactly as it was; the new
                // BOOTSTRAP path tills anywhere inside the tended plot as
                // long as there is a seed to follow it up with -- the audit's
                // brand-new farmhouse could never make its first crop because
                // hasNearbyCropAnchor() demanded a crop that could only ever
                // come from a harvest. The plot bound (isMaintainable above)
                // still caps both paths.
                boolean expansion = touchesFarmland(candidate) && hasNearbyCropAnchor(candidate);
                if (!expansion && !ensureSeedInBag()) {
                    continue; // unanchored, seedless tilling would terraform for nothing
                }
                maintainIsWater = false;
                maintainIsPlant = false;
            }
            maintainTarget = candidate;
            maintainIsCane = false;
            mode = Mode.TO_MAINTAIN;
            return true;
        }
        // SUGAR CANE (SURVIVAL_AUDIT.md F7, GAPS-1): only reached once the
        // crop-tilling scan above found nothing to do THIS call -- see the
        // class doc's sugar cane section for why this stays a wholly
        // separate, lowest-priority scan rather than folding into
        // maintainQueue: a water-adjacent dirt/grass tile that also
        // qualifies for ordinary crop tilling must always go to crops
        // first, and giving cane its own scan/queue (rather than an
        // ordering rule inside one shared scan) is the simplest way to make
        // that guarantee absolute rather than order-dependent.
        if (caneScanCooldown > 0) {
            caneScanCooldown--;
        } else if (caneQueue.isEmpty()) {
            // Longer cooldown than the crop maintain scan: once a few cane
            // bases exist they regrow forever on their own (no replanting,
            // ever -- see isMatureCane), so this scan has far less work to
            // find on a returning pass than the crop one does.
            caneScanCooldown = 200 + settler.getRandom().nextInt(100);
            caneQueue.addAll(caneScanner.scan(s.center, s.radius, 400, 4, this::isCaneSite));
        }
        while (!caneQueue.isEmpty()) {
            BlockPos candidate = caneQueue.poll();
            if (!isCaneSite(candidate) || !ensureCaneInBag()) {
                continue;
            }
            maintainTarget = candidate;
            maintainIsWater = false;
            maintainIsPlant = false;
            maintainIsCane = true;
            mode = Mode.TO_MAINTAIN;
            return true;
        }
        return false;
    }

    private boolean isMatureCrop(BlockPos pos) {
        BlockState state = settler.level().getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
            && isWithinTendedPlot(pos);
    }

    /**
     * A sugar cane TOP segment ready to cut -- cane immediately below it,
     * never dirt/sand, so cutting THIS position leaves the regrowing base
     * standing untouched (the classic "harvest the top, keep the bottom"
     * cane-farm technique). Deliberately not "any SugarCaneBlock": the base
     * segment itself must never be this goal's target, or a harvest would
     * kill the whole stalk and undo the entire point of not needing to
     * replant cane.
     */
    private boolean isMatureCane(BlockPos pos) {
        if (!isWithinTendedPlot(pos)) {
            return false;
        }
        var level = settler.level();
        return level.getBlockState(pos).is(Blocks.SUGAR_CANE)
            && level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE);
    }

    /** Anything the ripe-harvest queue should pick up: a mature crop or a
     *  harvestable cane top segment. See the class doc for why cane is a
     *  real second case here, not a cast onto {@link #isMatureCrop}. */
    private boolean isHarvestable(BlockPos pos) {
        return isMatureCrop(pos) || isMatureCane(pos);
    }

    /**
     * A still-empty tile legal for a BRAND NEW cane base -- air, inside the
     * tended plot, and legal under vanilla's OWN placement rule
     * ({@code SugarCaneBlock#canSurvive}: dirt/sand/red_sand with water
     * within reach, or already-standing cane). Reusing that rule rather than
     * hand-duplicating its dirt/sand/water-adjacency logic here is the same
     * discipline {@code RaiderHuntGoal} reusing {@code RaiderEntity#isMyWar}
     * follows: one rule, one place, so a future change to what cane can grow
     * on cannot quietly drift out of sync between vanilla and this scan.
     */
    private boolean isCaneSite(BlockPos pos) {
        if (!isWithinTendedPlot(pos)) {
            return false;
        }
        var level = settler.level();
        return level.getBlockState(pos).isAir()
            && Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, pos);
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
            if (above.getBlock() instanceof CropBlock) {
                return state.getValue(FarmBlock.MOISTURE) < 7; // waterable
            }
            // Bare farmland is a PLANTING site, never a watering one
            // (farmer audit 2026-08-25: watering a cropless tile forever
            // was the visible symptom of the missing first-plant path).
            // Whether a seed can actually be found is the poll's business,
            // not this scan predicate's -- chest walks are not scan-cheap.
            return above.isAir();
        }
        // Bare ground with room above it. Adjacency to existing farmland is
        // no longer required here: the bootstrap path must be able to till
        // the very first tile of a brand-new plot. The plot bound above
        // keeps this exactly as capped as it ever was, and the poll still
        // demands either the classic anchor or a seed in hand.
        return (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
            && settler.level().getBlockState(pos.above()).isAir();
    }

    /** The old expansion-tilling adjacency test, kept verbatim for the
     *  no-seeds path: bare ground converts only beside existing farmland. */
    private boolean touchesFarmland(BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (settler.level().getBlockState(pos.relative(dir)).is(Blocks.FARMLAND)) {
                return true;
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
        BlockPos anchor;
        int half;
        Building farmhouse = tendedFarmhouse();
        if (farmhouse != null && farmhouse.anchor != null) {
            anchor = farmhouse.anchor;
            half = tendedHalfSide(farmhouse);
        } else {
            // No real farmhouse to anchor to. Production code only ever
            // reaches FARMER through Employment.hire, which always leaves a
            // real Building behind -- so this is either a fixture-appointed
            // farmer (SettlerEntity#assignProfession, whose whole point per
            // its own class doc is that "GameTests need a farmer without
            // first building a farmhouse around them") or a real farmer
            // whose employer was just torn down and has not been refreshed
            // yet. Either way, THE TENDED PLOT's own invariant is "bounded,
            // never the whole settlement" -- not "requires a building" --
            // so falling back to the settlement's own heart at the plot's
            // full cap keeps that invariant (still bounded, never unbounded
            // scanning or terraforming) instead of silently discarding
            // every crop and leaving a farmer standing over one that will
            // never be tended. The cap alone, not the DEXTERITY-scaled
            // formula: there is no employer to read a skill-earned size
            // from, and a fixture farmer's crops are not guaranteed to sit
            // inside the small base square a low roll would otherwise give.
            Settlement s = settler.settlement();
            if (s == null) {
                return false;
            }
            anchor = s.center;
            half = TENDED_SIDE_CAP / 2;
        }
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
        if (target == null || !isHarvestable(target)) {
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
            // The FARMLAND check is what keeps this branch crop-only after a
            // cane harvest (GAPS-1): Items.SUGAR_CANE genuinely IS a
            // BlockItem for Blocks.SUGAR_CANE, so hasSeedFor(harvestedCrop)
            // alone would read true the instant a cane top segment lands in
            // the bag -- but cane never stands on farmland (it grows beside
            // water on dirt/sand), so target.below() here is never
            // FARMLAND for a cane harvest and this whole branch correctly
            // never fires for one. Cane needs no replant at all: cutting
            // only the top segment (see isMatureCane) leaves the base
            // standing to regrow on its own.
            if (hasSeedFor(harvestedCrop) && settler.level().getBlockState(target.below())
                .is(Blocks.FARMLAND)) {
                mode = Mode.PLANTING;
                workTicks = 0;
                plantCrop = harvestedCrop;
                plantDuration = REPLANT_DURATION;
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
        // Both clips press the seed home on the same beat: FARM_PLANT's
        // contract is t=0.70s -> tick 14 of 40, and SOW_BROADCAST's release
        // parks at 0.60-0.70s, so tick 14 also lands inside its 28-tick
        // loop -- one beat, two periods (farmer audit 2026-08-25: the old
        // shared 40-tick period made the 28-tick loop play 1.4 times).
        boolean pressBeat = plantDuration == FIRST_PLANT_DURATION
            ? workTicks % FIRST_PLANT_DURATION == 14
            : workTicks % REPLANT_DURATION == 14;
        if (pressBeat && settler.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, target, ModSounds.SEED_PRESS.get(),
                SoundSource.NEUTRAL, 0.6F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= plantDuration) {
            if (settler.level() instanceof ServerLevel serverLevel && plantCrop instanceof CropBlock crop
                && serverLevel.getBlockState(target).isAir()
                && serverLevel.getBlockState(target.below()).is(Blocks.FARMLAND)) {
                // The seed leaves the bag ONLY here, after every guard has
                // passed, so a failed plant costs nothing and the item and
                // the crop appear together or not at all.
                if (consumeSeedFor(crop)) {
                    serverLevel.setBlock(target, crop.getStateForAge(0), Block.UPDATE_ALL);
                }
            }
            plantCrop = null;
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
            settler.getNavigation().stop();
            if (maintainIsPlant) {
                // Standing at bare farmland inside the plot: plant it
                // directly (farmer audit 2026-08-25 -- this tile used to be
                // watered and then left bare forever).
                BlockPos plantPos = maintainTarget.above();
                maintainTarget = null;
                if (!beginFirstPlant(plantPos)) {
                    nextOrFinish(); // the seed left the bag since the poll
                }
                return;
            }
            mode = maintainIsWater ? Mode.WATERING : Mode.TILLING;
            workTicks = 0;
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
            boolean tilled = false;
            if (settler.level() instanceof ServerLevel serverLevel && isMaintainable(maintainTarget)
                && (hasNearbyCropAnchor(maintainTarget) || anyBagSeedSlot() >= 0)) {
                serverLevel.setBlock(maintainTarget, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                tilled = true;
            }
            chargeLightAction();
            // FIRST PLANTING (farmer audit 2026-08-25, CRITICAL): tilling
            // used to be the end of the line -- the only setBlock-plant path
            // was the replant on a just-harvested tile, so a brand-new
            // farmhouse could never grow anything and its "tended plot"
            // was pure decoration. A freshly tilled tile now chains straight
            // into a first planting whenever a seed is in the bag.
            BlockPos tilledPos = maintainTarget;
            maintainTarget = null;
            if (tilled && beginFirstPlant(tilledPos.above())) {
                return;
            }
            nextOrFinish();
        }
    }

    /**
     * Starts the FIRST planting of a fresh tile: activity WORK_PLANT and its
     * FARM_PLANT clip ({@value #FIRST_PLANT_DURATION} ticks), not the
     * replant's WORK_SOW -- two different motions, two different clips
     * (farmer audit 2026-08-25). The seed itself still leaves the bag only
     * at the end of tickPlant(), behind every guard, like the replant's.
     *
     * @return false when the bag holds no plantable seed, so callers can
     *         fall back to nextOrFinish().
     */
    private boolean beginFirstPlant(BlockPos pos) {
        int slot = anyBagSeedSlot();
        if (slot < 0) {
            return false;
        }
        plantCrop = ((BlockItem) settler.bag.getItem(slot).getItem()).getBlock();
        plantDuration = FIRST_PLANT_DURATION;
        target = pos;
        mode = Mode.PLANTING;
        workTicks = 0;
        settler.setActivity(SettlerActivity.WORK_PLANT);
        return true;
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
                // Water only under a standing crop. The poll already refuses
                // bare farmland as a watering target, but the crop may have
                // been broken mid-pour -- and the audit's "re-water bare
                // tiles forever" symptom must stay dead even if a future
                // path re-files bare farmland as waterable.
                if (state.is(Blocks.FARMLAND)
                    && serverLevel.getBlockState(maintainTarget.above())
                        .getBlock() instanceof CropBlock crop) {
                    serverLevel.setBlock(maintainTarget, state.setValue(FarmBlock.MOISTURE, 7),
                        Block.UPDATE_ALL);
                    // Åkerskifte (RESEARCH-1's FARM_GROWTH handoff, finally
                    // wired): a researched settlement's tended crops "grow
                    // more often". The multiplier becomes the chance that
                    // this watering ALSO advances the crop a stage -- the
                    // honest reading of "more often", and bounded to the
                    // tended plot and to a watering the farmer actually
                    // performed. Un-researched settlements roll against 0.
                    BlockPos above = maintainTarget.above();
                    BlockState cropState = serverLevel.getBlockState(above);
                    com.hearthstead.settlement.Settlement settlement = settler.settlement();
                    if (settlement != null && !crop.isMaxAge(cropState)) {
                        float extra = com.hearthstead.settlement.research.Research.bonus(
                            serverLevel, settlement.id,
                            com.hearthstead.settlement.research.ResearchKey.FARM_GROWTH) - 1.0F;
                        if (extra > 0.0F && settler.getRandom().nextFloat() < extra) {
                            // One stage, explicitly -- not bone meal (which
                            // jumps 2-5) and not randomTick (protected). The
                            // age property is read off the state so beetroot's
                            // 0-3 ladder works the same as wheat's 0-7.
                            for (var property : cropState.getProperties()) {
                                if (property instanceof net.minecraft.world.level.block.state
                                        .properties.IntegerProperty age
                                    && "age".equals(property.getName())) {
                                    serverLevel.setBlock(above,
                                        crop.getStateForAge(cropState.getValue(age) + 1),
                                        Block.UPDATE_ALL);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            chargeLightAction();
            maintainTarget = null;
            nextOrFinish();
        }
    }

    /** Pulls the mature crop (or cuts the mature cane top segment, GAPS-1)
     *  and pockets every drop in the bag. The seed for a crop's replant is
     *  taken back OUT of the bag at planting time -- see consumeSeedFor().
     *  Nothing is held outside the bag, because a plain goal field is
     *  destroyed when the entity unloads or the server stops, and item
     *  conservation is a permanent invariant.
     *
     *  <p>{@code harvestedCrop} is left a plain {@link Block}, never cast to
     *  {@link CropBlock}: sugar cane is a real, valid harvest target here
     *  (see {@link #isMatureCane}) and is not a CropBlock at all, so a cast
     *  would throw the instant a cane top segment matured. */
    private void harvest() {
        if (!(settler.level() instanceof ServerLevel serverLevel) || !isHarvestable(target)) {
            return;
        }
        BlockState state = serverLevel.getBlockState(target);
        harvestedCrop = state.getBlock();
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
        // Only PRODUCE earns a hearth trip. Seeds are working stock -- the
        // audit's bootstrap withdrawal puts them in the bag on purpose, and
        // shuttling them straight back out after every errand would undo it
        // (and the deposit itself would only re-apply the reserve anyway).
        if (bagHoldsProduce()) {
            mode = Mode.TO_HEARTH;
            pathToHearth();
        } else {
            done = true;
        }
    }

    /** Anything in the bag that is not plantable seed stock. */
    private boolean bagHoldsProduce() {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty() && !isSeedStack(stack)) {
                return true;
            }
        }
        return false;
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
                // SEED RESERVE (farmer audit 2026-08-25, HIGH): the old
                // deposit emptied the WHOLE bag, seeds included, so a wheat
                // harvest that rolled zero seeds permanently killed its
                // tile. Hold back one seed per crop type still standing in
                // the tended plot -- two for wheat, whose drop RNG rolls
                // zero seeds often enough that a one-seed margin loses
                // tiles anyway -- and deposit everything else.
                Map<Block, Integer> reserve = seedReserve();
                for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                    ItemStack stack = settler.bag.getItem(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    int keep = 0;
                    if (isSeedStack(stack)) {
                        Block crop = ((BlockItem) stack.getItem()).getBlock();
                        Integer want = reserve.get(crop);
                        if (want != null && want > 0) {
                            keep = Math.min(want, stack.getCount());
                            reserve.put(crop, want - keep);
                        }
                    }
                    if (keep >= stack.getCount()) {
                        continue; // the whole stack is reserve
                    }
                    ItemStack send = stack.copyWithCount(stack.getCount() - keep);
                    ItemStack leftover = hearth.insertGoods(send);
                    // Conservation: kept + inserted + bounced-back == the
                    // original count, exactly -- the bag slot ends holding
                    // the reserve plus whatever the hearth had no room for.
                    stack.setCount(keep + leftover.getCount());
                    settler.bag.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
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
        plantCrop = null;
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

    /** A stack that would plant SOME crop -- the same BlockItem-to-CropBlock
     *  reading seedSlotFor() has always used, just not tied to one crop. */
    private static boolean isSeedStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof CropBlock;
    }

    /** First bag slot holding any plantable seed, or -1. */
    private int anyBagSeedSlot() {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (isSeedStack(settler.bag.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * BOOTSTRAP SEEDS (farmer audit 2026-08-25, CRITICAL): the only plant
     * path used to be the replant on a just-harvested tile, so a farmhouse
     * that never had a crop could never get one -- the first seed has to
     * come from somewhere a player can actually put it. Checks the bag
     * first; when the bag has none, withdraws up to
     * {@value #SEED_WITHDRAW_CAP} seeds from the farmhouse's OWN containers
     * ({@link WarehouseIndex#containers}, the same bounded chest walk the
     * other trades use). Chest truth both ways: what leaves a chest lands
     * in the bag in the same pass, and what the bag cannot hold goes
     * straight back into the slot it came from.
     */
    private boolean ensureSeedInBag() {
        if (anyBagSeedSlot() >= 0) {
            return true;
        }
        Building farmhouse = tendedFarmhouse();
        if (farmhouse == null || !(settler.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // Stay UNDER the bag trigger: a withdrawal that landed the bag
        // exactly on BAG_TRIGGER would send the farmer straight to the
        // hearth to dump the very seeds they just collected.
        int room = Math.min(SEED_WITHDRAW_CAP, BAG_TRIGGER - 1 - bagCount());
        if (room <= 0) {
            return false;
        }
        int withdrawn = 0;
        for (BlockPos pos : WarehouseIndex.containers(serverLevel, farmhouse)) {
            if (withdrawn >= room) {
                break;
            }
            if (!(serverLevel.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize() && withdrawn < room; slot++) {
                ItemStack stack = chest.getItem(slot);
                if (!isSeedStack(stack)) {
                    continue;
                }
                ItemStack taken = chest.removeItem(slot,
                    Math.min(room - withdrawn, stack.getCount()));
                int count = taken.getCount();
                ItemStack leftover = settler.bag.addItem(taken);
                withdrawn += count - leftover.getCount();
                if (!leftover.isEmpty()) {
                    // Full bag: the remainder goes back where it came from,
                    // never onto the floor and never into thin air.
                    ItemStack back = chest.getItem(slot);
                    if (back.isEmpty()) {
                        chest.setItem(slot, leftover);
                    } else {
                        back.grow(leftover.getCount());
                        chest.setChanged();
                    }
                }
            }
        }
        return anyBagSeedSlot() >= 0;
    }

    /**
     * SEED RESERVE sizing (farmer audit 2026-08-25, HIGH): one seed per
     * crop type currently standing in the tended plot, two for wheat.
     * Bounded like everything else here: the plot is at most
     * {@value #TENDED_SIDE_CAP}x{@value #TENDED_SIDE_CAP} and the vertical
     * band is the -1..+2 the farmer already works in, so this is a few
     * hundred block reads once per deposit, not a per-tick scan.
     */
    private Map<Block, Integer> seedReserve() {
        Map<Block, Integer> reserve = new HashMap<>();
        Building farmhouse = tendedFarmhouse();
        if (farmhouse == null || farmhouse.anchor == null) {
            return reserve;
        }
        int half = tendedHalfSide(farmhouse);
        BlockPos anchor = farmhouse.anchor;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    Block block = settler.level().getBlockState(cursor).getBlock();
                    if (block instanceof CropBlock) {
                        reserve.put(block, block == Blocks.WHEAT ? 2 : 1);
                    }
                }
            }
        }
        return reserve;
    }
}

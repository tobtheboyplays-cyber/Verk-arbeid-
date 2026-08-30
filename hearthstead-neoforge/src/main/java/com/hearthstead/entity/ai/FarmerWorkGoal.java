package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceService;
import com.hearthstead.settlement.work.WorkerStackProvenance;
import com.hearthstead.settlement.work.WorkerStackProvenance.TransitKind;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;

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
 * bounded, lowest-priority queue ({@link #caneQueue}) so a water-adjacent
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
    /** Server-authoritative crop pull: FARM_HARVEST t=0.45 s. */
    public static final int HARVEST_CONTACT_TICK = 9;
    /** Cloth/hand settle after the already-committed crop pull. */
    public static final int HARVEST_STOW_TICK = 18;
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
    /** Server-authoritative seed press: FARM_PLANT t=0.70 s. */
    public static final int PLANT_CONTACT_TICK = 14;
    private static final int TILL_DURATION = 30;
    private static final int WATER_DURATION = 48;
    /** Server-authoritative pour contact: FARM_WATER t=0.80 s. */
    public static final int WATER_CONTACT_TICK = 16;
    private static final int BAG_TRIGGER = 8;
    private static final int TILL_ANCHOR_RANGE = 3;
    /** Bootstrap withdrawal cap: how many seeds one visit to the
     *  farmhouse's own chests may move into the bag. See ensureSeedInBag()
     *  for why the effective cap also stays under {@link #BAG_TRIGGER}. */
    /**
     * Farm work is rooted in the confirmed floor and the crop cell directly
     * above it.  One survey unit is therefore a column pair, not an arbitrary
     * voxel in the selected headroom.  Keeping this at 128 means the ordinary
     * soil/crop reads stay well below the old 512-voxel pass while a tall,
     * valid Farm Zone cannot hide its actual field behind empty sky blocks.
     */
    private static final int FIELD_SURVEY_COLUMN_BUDGET = 128;
    private static final int FIELD_SURVEY_COOLDOWN_MIN = 20;
    private static final int FIELD_SURVEY_COOLDOWN_JITTER = 20;
    /** A blocked farmhouse chest is a logistics wait, not a per-tick scan. */
    private static final int DEPOSIT_RETRY_TICKS = 100;
    /** One effort unit per this many completed plant/till/water actions —
     *  batch-counted so the light work does not spend as fast as a harvest. */
    private static final int LIGHT_ACTIONS_PER_EFFORT = 4;

    private enum Mode {
        TO_WORK, HARVESTING, PLANTING, TO_MAINTAIN, TILLING, WATERING,
        TO_STORAGE, TO_INPUT, TO_REPLANT, TO_RETURN_INPUT
    }

    /** Why one exact Farmhouse input visit is in progress. */
    private enum InputPurpose { MAINTAIN, CANE, REPLANT }

    private final SettlerEntity settler;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Deque<BlockPos> maintainQueue = new ArrayDeque<>();
    private final Deque<BlockPos> caneQueue = new ArrayDeque<>();
    private Mode mode;
    private BlockPos target;
    private BlockPos maintainTarget;
    /** Real chest in this farmer's own farmhouse; never the Hearth. */
    private BlockPos depositTarget;
    /** Exact Farmhouse container selected for one physical seed/cane pickup. */
    @Nullable
    private BlockPos inputTarget;
    @Nullable
    private Block inputCrop;
    @Nullable
    private InputPurpose inputPurpose;
    /** Active input action being returned after its persisted target invalidated. */
    @Nullable
    private UUID inputReturnAction;
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
    /** Resumable X/Z cursor for the confirmed Farm Zone's floor/crop pairs. */
    private int fieldColumnCursor;
    @Nullable
    private WorkZone surveyedFieldZone;
    /** True only once every confirmed floor column has been offered once. */
    private boolean fieldSweepComplete;
    /** Missing-tool chest/request retry is bounded; AI selection itself may run every tick. */
    private int equipmentRetryCooldown;
    /** Full or missing farmhouse storage is retried at a bounded cadence. */
    private int depositRetryCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;
    /** Batch counter for the light work's effort cost; see harvest()'s own
     *  per-crop spend for why planting/tilling/watering are counted apart. */
    private int lightActionCount;
    @Nullable
    private WorkZone activeZone;
    private StopReason workZoneState = StopReason.NONE;
    /** Input action selected by beginFirstPlant/beginCanePlant, if any. */
    @Nullable
    private UUID plantActionId;
    /** One-shot latches. Goal fields may disappear on unload, but every
     *  committed result is already physical and persisted at its contact. */
    private boolean harvestContactCommitted;
    private boolean plantContactCommitted;
    private boolean waterContactCommitted;

    public FarmerWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.FARMER
            && settler.isBound()
            // Fatigue changes pace; it must never become a silent daily quota
            // or make an otherwise valid field target disappear.
            && settler.dayPhase().work();
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
        Building employer = Employment.employerOf(s, settler.getUUID());
        WorkZone zone = settler.level() instanceof ServerLevel level
            ? authoritativeZone(level, s, employer) : null;
        // Hauling already-produced food never requires a working hoe. This
        // must run before the equipment gate or a tool that breaks on the
        // final action would strand the physical bag at the field.
        // A small harvest must not discard the remaining columns of the same
        // confirmed-zone survey merely because it already has one output in
        // its bag. The hard bag cap still wins, but otherwise finish the
        // resumable sweep/queues before beginning the storage leg.
        // Only a genuinely active pass for the CURRENT authoritative zone may
        // defer a small load. A missing/replaced/quarantined zone, or the
        // default never-surveyed state, must never strand physical produce.
        if (bagCount() >= BAG_TRIGGER || (bagHoldsProduce()
            && !activeSurveyFor(zone))) {
            if (depositRetryCooldown > 0) {
                depositRetryCooldown--;
                return false;
            }
            mode = Mode.TO_STORAGE;
            return true;
        }
        activeZone = zone;
        WorkerProvenanceSavedData.ActionView recovery = null;
        WorkerProvenanceSavedData.ActionView resume = null;
        boolean preparedResume = false;
        if (settler.level() instanceof ServerLevel level) {
            recovery = WorkerProvenanceService.recoverableFarmPlant(level, s,
                settler);
            if (recovery != null) {
                resume = zone == null ? null
                    : WorkerProvenanceService.resumableFarmPlant(level, s,
                        employer, settler);
                preparedResume = resume != null && !resume.planned().isEmpty()
                    && resumePreparedPlant(resume);
                // A paid/tagged input with a lost or now-invalid target must
                // not block all future farming. Walk it back to its exact
                // source chest instead of silently retargeting or deleting it.
                // Returning already-owned cargo is logistics, so it must not
                // be blocked by a hoe that broke or disappeared during reload.
                if (!preparedResume) {
                    return beginInputReturn(recovery);
                }
            }
        }
        if (zone == null) {
            publishWorkZoneStop(StopReason.NO_WORK_ZONE,
                employer == null ? null : employer.plaquePos,
                "ai_idle_no_zone", null);
            return false;
        }
        // A real employed farmer does not work with a projected/magic hoe.
        // This gate deliberately follows invalid-input recovery above: a tool
        // is required to continue a valid plant, never to return its seed.
        if (equipmentRetryCooldown > 0) {
            equipmentRetryCooldown--;
            return false;
        }
        if (settler.level() instanceof ServerLevel level
            && !EquipmentRequests.equipFromWorkplace(level, s, settler)) {
            equipmentRetryCooldown = 20;
            return false;
        }
        if (preparedResume) {
            return true;
        }
        if (queue.isEmpty() && maintainQueue.isEmpty() && caneQueue.isEmpty()) {
            // A new/unfinished confirmed field must advance on every selection
            // evaluation.  Applying the ordinary 20-39 tick idle cooldown to
            // every partial batch is what made a real distant crop look like
            // "nothing workable" for a large, tall but perfectly valid zone.
            if (!zone.equals(surveyedFieldZone) || !fieldSweepComplete) {
                if (surveyTendedField(zone)) {
                    scanCooldown = FIELD_SURVEY_COOLDOWN_MIN
                        + settler.getRandom().nextInt(FIELD_SURVEY_COOLDOWN_JITTER);
                }
            } else if (scanCooldown > 0) {
                scanCooldown--;
            } else {
                // The previous sweep ended with the cursor at totalColumns.
                // A new polling cycle must start from the first field column;
                // merely clearing the completion flag would otherwise keep
                // returning an empty, instantly-complete sweep forever.
                fieldColumnCursor = 0;
                fieldSweepComplete = false;
                if (surveyTendedField(zone)) {
                    scanCooldown = FIELD_SURVEY_COOLDOWN_MIN
                        + settler.getRandom().nextInt(FIELD_SURVEY_COOLDOWN_JITTER);
                }
            }
        }
        while (!queue.isEmpty()) {
            BlockPos candidate = queue.poll();
            if (isHarvestable(candidate)) {
                target = candidate;
            mode = Mode.TO_WORK;
                clearWorkZoneStop();
                return true;
            }
        }
        // Nothing ripe right now: use the lower-priority field-maintenance
        // results from the same exact, bounded survey.
        while (!maintainQueue.isEmpty()) {
            BlockPos candidate = maintainQueue.poll();
            if (!isMaintainable(candidate)) {
                continue;
            }
            if (settler.level().getBlockState(candidate).is(Blocks.FARMLAND)) {
                if (isWithinTendedPlot(candidate.above())
                    && settler.level().getBlockState(candidate.above()).isAir()) {
                    // BARE FARMLAND (farmer audit 2026-08-25, CRITICAL): a
                    // tilled tile with nothing on it used to be watered
                    // forever and planted never. It is a planting site when
                    // a seed can be found, and skipped entirely when not --
                    // it is no longer a watering target either way.
                    maintainTarget = candidate;
                    maintainIsWater = false;
                    maintainIsPlant = true;
                    maintainIsCane = false;
                    if (!ensureSeedInBag()) {
                        if (beginInputAcquisition(InputPurpose.MAINTAIN, null)) {
                            clearWorkZoneStop();
                            return true;
                        }
                        maintainTarget = null;
                        continue;
                    }
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
                    maintainTarget = candidate;
                    maintainIsWater = false;
                    maintainIsPlant = false;
                    maintainIsCane = false;
                    if (beginInputAcquisition(InputPurpose.MAINTAIN, null)) {
                        clearWorkZoneStop();
                        return true;
                    }
                    maintainTarget = null;
                    // A legal cane site is also bare dirt. It belongs to crop
                    // bootstrap only when crop input exists; otherwise retain
                    // it for the lower-priority cane pass rather than losing
                    // the candidate after one failed wheat-seed lookup.
                    if (isCaneSite(candidate.above())) {
                        caneQueue.addFirst(candidate.above());
                    }
                    continue; // unanchored, seedless tilling would terraform for nothing
                }
                maintainIsWater = false;
                maintainIsPlant = false;
            }
            maintainTarget = candidate;
            maintainIsCane = false;
            mode = Mode.TO_MAINTAIN;
            clearWorkZoneStop();
            return true;
        }
        // SUGAR CANE (SURVIVAL_AUDIT.md F7, GAPS-1): only reached once ripe
        // crops and ordinary field maintenance from this survey are empty.
        // A dual-purpose water-adjacent tile therefore always goes to crops
        // first without paying for a third world scan.
        while (!caneQueue.isEmpty()) {
            BlockPos candidate = caneQueue.poll();
            if (!isCaneSite(candidate)) {
                continue;
            }
            maintainTarget = candidate;
            maintainIsWater = false;
            maintainIsPlant = false;
            maintainIsCane = true;
            if (!ensureCaneInBag()) {
                if (beginInputAcquisition(InputPurpose.CANE,
                        Blocks.SUGAR_CANE)) {
                    clearWorkZoneStop();
                    return true;
                }
                maintainTarget = null;
                continue;
            }
            mode = Mode.TO_MAINTAIN;
            clearWorkZoneStop();
            return true;
        }
        publishWorkZoneStop(StopReason.NO_VALID_TARGET, employer.plaquePos,
            "ai_no_valid_target", zone);
        return false;
    }

    /** True only while unfinished survey/queue state belongs to this zone. */
    private boolean activeSurveyFor(@Nullable WorkZone zone) {
        return zone != null && zone.equals(surveyedFieldZone)
            && (!fieldSweepComplete || !queue.isEmpty()
                || !maintainQueue.isEmpty() || !caneQueue.isEmpty());
    }

    /** Re-enters the exact persisted target; it never retargets a paid seed. */
    private boolean resumePreparedPlant(
        WorkerProvenanceSavedData.ActionView action) {
        if (action.planned().size() != 1) {
            return false;
        }
        int slot = provenanceSeedSlot(action.id());
        if (slot < 0
            || !(settler.bag.getItem(slot).getItem() instanceof BlockItem item)) {
            return false;
        }
        BlockPos plantPos = action.planned().getFirst();
        Block block = item.getBlock();
        boolean cane = block == Blocks.SUGAR_CANE;
        if (cane ? !isCaneSite(plantPos)
            : !(block instanceof CropBlock)
                || !isWithinTendedPlot(plantPos)
                || !isWithinTendedPlot(plantPos.below())
                || !settler.level().getBlockState(plantPos).isAir()
                || !settler.level().getBlockState(plantPos.below())
                    .is(Blocks.FARMLAND)) {
            return false;
        }
        maintainTarget = cane ? plantPos : plantPos.below();
        maintainIsCane = cane;
        maintainIsPlant = !cane;
        maintainIsWater = false;
        mode = Mode.TO_MAINTAIN;
        clearWorkZoneStop();
        return true;
    }

    /** Starts lossless recovery of one invalid/unplanned persisted input. */
    private boolean beginInputReturn(WorkerProvenanceSavedData.ActionView action) {
        if (action == null || action.source() == null
            || recoverableSeedSlot(action.id()) < 0) {
            settler.recordRouteFailure("farm_seed_recovery_input_missing");
            return false;
        }
        inputTarget = action.source().immutable();
        inputReturnAction = action.id();
        inputPurpose = null;
        inputCrop = null;
        mode = Mode.TO_RETURN_INPUT;
        clearWorkZoneStop();
        return true;
    }

    /**
     * Surveys the confirmed farm's floor and the crop cell immediately above
     * it, classifying the same three existing priority queues as before.
     *
     * <p>A Farm Zone is selected from two horizontal floor corners and one
     * explicit height click.  The height is growing headroom/authority, not a
     * reason to walk every empty air voxel before looking at the field.  This
     * cursor is exact in X/Z, resumes across calls and never constructs a
     * position outside the committed volume.
     *
     * @return whether this call completed one full confirmed-zone sweep
     */
    private boolean surveyTendedField(WorkZone zone) {
        if (zone == null || zone.type() != WorkZone.Type.FARM) {
            return false;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        List<BlockPos> harvest = new ArrayList<>();
        List<BlockPos> maintain = new ArrayList<>();
        List<BlockPos> cane = new ArrayList<>();
        if (!zone.equals(surveyedFieldZone)) {
            surveyedFieldZone = zone;
            fieldColumnCursor = 0;
            fieldSweepComplete = false;
        }

        int sizeX = zone.sizeX();
        int sizeZ = zone.sizeZ();
        int totalColumns = sizeX * sizeZ;
        int examined = 0;
        while (examined < FIELD_SURVEY_COLUMN_BUDGET
            && fieldColumnCursor < totalColumns) {
            int index = fieldColumnCursor++;
            int x = zone.min().getX() + index / sizeZ;
            int z = zone.min().getZ() + index % sizeZ;
            BlockPos soil = new BlockPos(x, zone.min().getY(), z);
            BlockPos crop = soil.above();
            examined++;

            // A valid Farm Zone always has at least one headroom block.  Keep
            // the authoritative live/zone gate explicit before either read.
            if (!WorkZoneService.livePositionAllowed(level, zone, soil)
                || !WorkZoneService.livePositionAllowed(level, zone, crop)) {
                continue;
            }
            if (isHarvestable(crop)) {
                if (harvest.size() < 12) {
                    harvest.add(crop);
                }
            } else {
                // Mature cane lives one cell above its planted base.  This is
                // still a bounded per-column probe, retains the old top-only
                // harvest behavior, and is attempted only inside the exact
                // committed Farm Zone.
                BlockPos caneTop = crop.above();
                if (zone.contains(caneTop)
                    && WorkZoneService.livePositionAllowed(level, zone, caneTop)
                    && isHarvestable(caneTop)) {
                    if (harvest.size() < 12) {
                        harvest.add(caneTop);
                    }
                } else if (isMaintainable(soil)) {
                    if (maintain.size() < 6) {
                        maintain.add(soil);
                    }
                } else if (isCaneSite(crop)) {
                    if (cane.size() < 4) {
                        cane.add(crop);
                    }
                }
            }
        }
        fieldSweepComplete = fieldColumnCursor >= totalColumns;

        Comparator<BlockPos> nearestFirst = Comparator.comparingDouble(
            pos -> pos.distSqr(settler.blockPosition()));
        harvest.sort(nearestFirst);
        maintain.sort(nearestFirst);
        cane.sort(nearestFirst);
        queue.addAll(harvest);
        maintainQueue.addAll(maintain);
        caneQueue.addAll(cane);
        return fieldSweepComplete;
    }

    private boolean isMatureCrop(BlockPos pos) {
        if (!isWithinTendedPlot(pos)) {
            return false;
        }
        BlockState state = settler.level().getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
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
        if (!isWithinTendedPlot(pos) || !isWithinTendedPlot(pos.below())) {
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
        if (!isWithinTendedPlot(pos) || !caneSupportReadsStayInside(pos)) {
            return false;
        }
        var level = settler.level();
        return level.getBlockState(pos).isAir()
            && Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, pos);
    }

    /** Vanilla cane survival reads its support and four water neighbours. */
    private boolean caneSupportReadsStayInside(BlockPos pos) {
        BlockPos support = pos.below();
        if (!isWithinTendedPlot(support)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!isWithinTendedPlot(support.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private boolean isMaintainable(BlockPos pos) {
        if (!isWithinTendedPlot(pos) || !isWithinTendedPlot(pos.above())) {
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
            BlockPos neighbour = pos.relative(dir);
            if (isWithinTendedPlot(neighbour)
                && settler.level().getBlockState(neighbour).is(Blocks.FARMLAND)) {
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

    @Nullable
    private WorkZone authoritativeZone(ServerLevel level, Settlement settlement,
                                       @Nullable Building employer) {
        if (level == null || settlement == null || employer == null
            || !employer.valid || employer.type != BuildingType.FARMHOUSE
            || employer.workZoneQuarantined()
            || !employer.workers.contains(settler.getUUID())) {
            return null;
        }
        WorkZone zone = employer.workZone().orElse(null);
        return zone != null && zone.type() == WorkZone.Type.FARM
            && zone.settlementId().equals(settlement.id)
            && zone.buildingId().equals(employer.id)
            && zone.dimension().equals(level.dimension().location())
            ? zone : null;
    }

    private void publishWorkZoneStop(StopReason reason, @Nullable BlockPos target,
                                     String event, @Nullable WorkZone zone) {
        settler.setLogisticsStop(reason, target, 0);
        if (workZoneState == reason) {
            return;
        }
        workZoneState = reason;
        Hearthstead.LOGGER.info(
            "HSQA_WORK_ZONE event={} outcome={} settler={} settlement={} building={} revision={} target={} thread={}",
            event, reason.name(), settler.getUUID(), settler.getSettlementId(),
            zone == null ? null : zone.buildingId(),
            zone == null ? -1 : zone.revision(), target,
            Thread.currentThread().getName());
    }

    private void clearWorkZoneStop() {
        if (workZoneState == StopReason.NO_WORK_ZONE
            || workZoneState == StopReason.NO_VALID_TARGET) {
            settler.clearLogisticsStop();
            workZoneState = StopReason.NONE;
        }
    }

    /** Re-resolves current authority immediately before every world mutation. */
    private boolean zoneAllowsMutation(ServerLevel level, BlockPos pos,
                                       String operation) {
        Settlement settlement = settler.settlement();
        Building employer = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        WorkZone current = authoritativeZone(level, settlement, employer);
        if (current == null) {
            activeZone = null;
            publishWorkZoneStop(StopReason.NO_WORK_ZONE,
                employer == null ? null : employer.plaquePos,
                "ai_idle_no_zone", null);
            traceOutsideMutation(operation, pos, null);
            return false;
        }
        activeZone = current;
        if (!WorkZoneService.livePositionAllowed(level, current, pos)) {
            publishWorkZoneStop(StopReason.NO_VALID_TARGET,
                employer.plaquePos, "ai_outside_zone_rejected", current);
            traceOutsideMutation(operation, pos, current);
            return false;
        }
        clearWorkZoneStop();
        return true;
    }

    private void traceOutsideMutation(String operation, BlockPos pos,
                                      @Nullable WorkZone zone) {
        Hearthstead.LOGGER.info(
            "HSQA_WORK_ZONE event=ai_outside_zone_no_mutation operation={} settler={} rejected={} building={} revision={} thread={}",
            operation, settler.getUUID(), pos,
            zone == null ? null : zone.buildingId(),
            zone == null ? -1 : zone.revision(),
            Thread.currentThread().getName());
    }

    /** Every farming read and mutation is bounded by the confirmed 3D zone. */
    private boolean isWithinTendedPlot(BlockPos pos) {
        return settler.level() instanceof ServerLevel level
            && WorkZoneService.livePositionAllowed(level, activeZone, pos);
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
                if (isWithinTendedPlot(cursor)
                    && settler.level().getBlockState(cursor).getBlock()
                        instanceof CropBlock) {
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
        harvestContactCommitted = false;
        plantContactCommitted = false;
        waterContactCommitted = false;
        stuckChecks = 0;
        repathTimer = 0;
        if (mode == Mode.TO_STORAGE) {
            // Produce is a physical back load. CARRYING is server-authored
            // presentation truth: it selects WALK_LADEN plus the Farmer's
            // tool-safe sack hold instead of an empty ordinary WALK.
            settler.setActivity(SettlerActivity.CARRYING);
            pathToStorage();
        } else if (mode == Mode.TO_INPUT || mode == Mode.TO_RETURN_INPUT) {
            settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
            pathToInput();
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

    private void pathToStorage() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Building farmhouse = tendedFarmhouse();
        if (farmhouse == null) {
            settler.recordRouteFailure("farmhouse_missing");
            depositRetryCooldown = DEPOSIT_RETRY_TICKS;
            done = true;
            return;
        }
        // Keep one exact chest throughout a retry cycle. Re-selecting the
        // nearest container on every repath can make a farmer oscillate
        // between opposite sides of a doorway and never finish either route.
        if (depositTarget == null) {
            depositTarget = WorkerStorageAuthority.nearestLoadedContainer(level,
                farmhouse, settler.blockPosition());
        }
        if (depositTarget == null) {
            settler.recordRouteFailure("farmhouse_storage_missing");
            depositRetryCooldown = DEPOSIT_RETRY_TICKS;
            done = true;
            return;
        }
        ContainerApproach.Result approach = ContainerApproach.moveToContact(
            level, settler, depositTarget, 1.0D);
        if (approach.state() == ContainerApproach.State.INVALID_TARGET) {
            // The exact chest disappeared. Only this explicit invalidation
            // releases route ownership and permits a different Farmhouse
            // container to be selected on the next bounded retry.
            depositTarget = null;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !done && (mode == Mode.TO_STORAGE || mode == Mode.TO_INPUT
            || mode == Mode.TO_RETURN_INPUT || workConditions());
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
            case TO_STORAGE -> tickDeposit();
            case TO_INPUT -> tickInputAcquisition();
            case TO_REPLANT -> tickReplantTravel();
            case TO_RETURN_INPUT -> tickInputReturn();
        }
    }

    /**
     * Reserves no inventory: it only chooses one loaded, matching Farmhouse
     * container and starts the caller-owned physical approach. The later
     * CONTACT tick re-reads both the container and its accepted input.
     */
    private boolean beginInputAcquisition(InputPurpose purpose,
                                          @Nullable Block requestedCrop) {
        if (!(settler.level() instanceof ServerLevel level) || purpose == null) {
            return false;
        }
        Building farmhouse = tendedFarmhouse();
        if (farmhouse == null) {
            return false;
        }
        Predicate<ItemStack> accepted = inputPredicate(purpose, requestedCrop);
        WorkerStorageAuthority.Source source = WorkerStorageAuthority.find(level,
            farmhouse, accepted, settler.blockPosition());
        if (source == null) {
            return false;
        }
        inputTarget = source.pos();
        inputCrop = requestedCrop;
        inputPurpose = purpose;
        inputReturnAction = null;
        mode = Mode.TO_INPUT;
        return true;
    }

    private Predicate<ItemStack> inputPredicate(InputPurpose purpose,
                                                @Nullable Block requestedCrop) {
        if (purpose == InputPurpose.CANE) {
            return stack -> stack.is(Items.SUGAR_CANE);
        }
        if (requestedCrop != null) {
            return stack -> stack.getItem() instanceof BlockItem item
                && item.getBlock() == requestedCrop;
        }
        return FarmerWorkGoal::isSeedStack;
    }

    /** One exact seed/cane leaves storage only on the same tick as CONTACT. */
    private boolean withdrawInputAtContact(ServerLevel level) {
        Settlement settlement = settler.settlement();
        Building farmhouse = tendedFarmhouse();
        if (settlement == null || farmhouse == null || inputTarget == null
            || inputPurpose == null
            || !ContainerApproach.inspect(level, settler, inputTarget).canInteract()) {
            return false;
        }
        BlockPos plannedTarget = switch (inputPurpose) {
            case REPLANT -> target;
            case CANE -> maintainTarget;
            case MAINTAIN -> maintainTarget == null ? null
                : maintainTarget.above();
        };
        if (plannedTarget == null) {
            return false;
        }
        UUID action = WorkerProvenanceService.supplyOneSeedAt(level, settlement,
            farmhouse, settler, inputTarget,
            inputPredicate(inputPurpose, inputCrop), plannedTarget);
        if (action == null) {
            return false;
        }
        boolean held = inputPurpose == InputPurpose.CANE
            ? caneSlotForAction(action) >= 0
            : inputCrop == null ? provenanceSeedSlot(action) >= 0
                : seedSlotForAction(inputCrop, action) >= 0;
        if (held) {
            // The action and its exact plant cell were persisted before the
            // source changed. Retain the id across travel and any goal restart.
            plantActionId = action;
        }
        return held;
    }

    private void tickInputAcquisition() {
        if (!(settler.level() instanceof ServerLevel level) || inputTarget == null
            || inputPurpose == null) {
            abandonInputAcquisition();
            return;
        }
        settler.getLookControl().setLookAt(inputTarget.getX() + 0.5D,
            inputTarget.getY() + 0.6D, inputTarget.getZ() + 0.5D);
        ContainerApproach.Result contact = ContainerApproach.inspect(level, settler,
            inputTarget);
        if (contact.canInteract()) {
            settler.getNavigation().stop();
            stuckChecks = 0;
            InputPurpose completed = inputPurpose;
            if (!withdrawInputAtContact(level)) {
                abandonInputAcquisition();
                return;
            }
            inputTarget = null;
            inputCrop = null;
            inputPurpose = null;
            inputReturnAction = null;
            if (completed == InputPurpose.REPLANT) {
                mode = Mode.TO_REPLANT;
                pathToTarget();
            } else {
                mode = Mode.TO_MAINTAIN;
                pathToMaintainTarget();
            }
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = 40;
            if (contact.state() == ContainerApproach.State.INVALID_TARGET) {
                inputTarget = null;
            }
            if (++stuckChecks > 6) {
                settler.recordRouteFailure("farmhouse_input_unreachable");
                abandonInputAcquisition();
            } else {
                pathToInput();
            }
        }
    }

    private void tickInputReturn() {
        if (!(settler.level() instanceof ServerLevel level)
            || inputTarget == null || inputReturnAction == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(inputTarget.getX() + 0.5D,
            inputTarget.getY() + 0.6D, inputTarget.getZ() + 0.5D);
        ContainerApproach.Result contact = ContainerApproach.inspect(level,
            settler, inputTarget);
        if (contact.canInteract()) {
            Settlement settlement = settler.settlement();
            Building farmhouse = tendedFarmhouse();
            if (settlement != null && farmhouse != null
                && WorkerProvenanceService.returnFarmSeedAt(level, settlement,
                    farmhouse, settler, inputReturnAction, inputTarget)) {
                inputTarget = null;
                inputReturnAction = null;
                plantActionId = null;
                mode = Mode.TO_WORK;
                nextOrFinish();
            } else {
                settler.recordRouteFailure("farm_seed_recovery_storage_refused");
                done = true;
            }
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 6) {
                settler.recordRouteFailure("farm_seed_recovery_unreachable");
                done = true;
            } else {
                pathToInput();
            }
        }
    }

    private void abandonInputAcquisition() {
        inputTarget = null;
        inputCrop = null;
        inputPurpose = null;
        // Never leave a failed branch carrying stale TO_INPUT evidence into
        // the next canUse() selection pass.
        mode = Mode.TO_WORK;
        nextOrFinish();
    }

    /** Returns to the exact just-harvested crop cell before beginning WORK_SOW. */
    private void tickReplantTravel() {
        if (target == null || !(harvestedCrop instanceof CropBlock)
            || !hasSeedFor(harvestedCrop)
            || !isWithinTendedPlot(target)
            || !isWithinTendedPlot(target.below())
            || !settler.level().getBlockState(target).isAir()
            || !settler.level().getBlockState(target.below()).is(Blocks.FARMLAND)) {
            nextOrFinish();
            return;
        }
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, target, "replant_travel")) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5D,
            target.getY() + 0.1D, target.getZ() + 0.5D);
        if (settler.blockPosition().distSqr(target) <= 6.5D) {
            settler.getNavigation().stop();
            mode = Mode.PLANTING;
            workTicks = 0;
            plantContactCommitted = false;
            plantCrop = harvestedCrop;
            plantDuration = REPLANT_DURATION;
            settler.setActivity(SettlerActivity.WORK_SOW);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 6) {
                nextOrFinish();
            } else {
                pathToTarget();
            }
        }
    }

    private void tickTravel() {
        if (target == null || !isHarvestable(target)) {
            nextOrFinish();
            return;
        }
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, target, "harvest_travel")) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.3,
            target.getZ() + 0.5);
        double distSqr = settler.blockPosition().distSqr(target);
        if (distSqr <= 6.5) {
            mode = Mode.HARVESTING;
            workTicks = 0;
            harvestContactCommitted = false;
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
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, target, "harvest")) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.2,
            target.getZ() + 0.5);
        workTicks++;
        if (settler.level() instanceof ServerLevel serverLevel) {
            if (!harvestContactCommitted
                && workTicks % HARVEST_DURATION == HARVEST_CONTACT_TICK) {
                if (!harvest()) {
                    nextOrFinish();
                    return;
                }
                harvestContactCommitted = true;
                serverLevel.playSound(null, target, ModSounds.CROP_PULL.get(),
                    SoundSource.NEUTRAL, 0.7F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
            } else if (harvestContactCommitted
                && workTicks % HARVEST_DURATION == HARVEST_STOW_TICK) {
                // The physical crop and every drop already committed on the
                // pull. This is only the cloth/hand settle at the sack -- it
                // is never a late inventory mutation disguised as a sound.
                serverLevel.playSound(null, target, ModSounds.BAG_STOW.get(),
                    SoundSource.NEUTRAL, 0.65F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        if (workTicks >= HARVEST_DURATION) {
            if (!harvestContactCommitted) {
                nextOrFinish();
                return;
            }
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
            boolean replantableCrop = harvestedCrop instanceof CropBlock
                && isWithinTendedPlot(target.below())
                && settler.level().getBlockState(target.below()).is(Blocks.FARMLAND);
            if (replantableCrop && !hasSeedFor(harvestedCrop)
                && beginInputAcquisition(InputPurpose.REPLANT, harvestedCrop)) {
                // The output remains physically in the bag while this exact
                // Farmhouse input journey runs; it cannot be silently reused
                // as seed or deposited ahead of the replant.
                harvestContactCommitted = false;
                settler.setActivity(SettlerActivity.COLLECTING_ITEMS);
                pathToInput();
                return;
            }
            if (replantableCrop && hasSeedFor(harvestedCrop)) {
                mode = Mode.PLANTING;
                workTicks = 0;
                plantContactCommitted = false;
                plantCrop = harvestedCrop;
                plantDuration = REPLANT_DURATION;
                // D-016: the farmer's signature. Broadcasting seed by
                // hand reads at fifty blocks; pressing one seed into
                // one hole does not.
                settler.setActivity(SettlerActivity.WORK_SOW);
            } else if (bagCount() >= BAG_TRIGGER) {
                mode = Mode.TO_STORAGE;
                settler.setActivity(SettlerActivity.CARRYING);
                pathToStorage();
            } else {
                nextOrFinish();
            }
            harvestContactCommitted = false;
        }
    }

    /** Starts a bounded route to the already-selected Farmhouse input source. */
    private void pathToInput() {
        if (!(settler.level() instanceof ServerLevel level) || inputTarget == null) {
            abandonInputAcquisition();
            return;
        }
        ContainerApproach.Result approach = ContainerApproach.moveToContact(level,
            settler, inputTarget, 1.0D);
        if (approach.state() == ContainerApproach.State.INVALID_TARGET) {
            inputTarget = null;
        }
    }

    private void tickPlant() {
        if (target == null) {
            abortPlantAttempt();
            return;
        }
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, target, "plant")) {
            abortPlantAttempt();
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
        boolean pressBeat = !plantContactCommitted
            && (plantDuration == FIRST_PLANT_DURATION
                ? workTicks % FIRST_PLANT_DURATION == PLANT_CONTACT_TICK
                : workTicks % REPLANT_DURATION == PLANT_CONTACT_TICK);
        if (pressBeat) {
            if (!(settler.level() instanceof ServerLevel serverLevel)
                || !commitPlantAtContact(serverLevel)) {
                abortPlantAttempt();
                return;
            }
            plantContactCommitted = true;
            serverLevel.playSound(null, target, ModSounds.SEED_PRESS.get(),
                SoundSource.NEUTRAL, 0.6F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= plantDuration) {
            if (!plantContactCommitted) {
                abortPlantAttempt();
                return;
            }
            plantActionId = null;
            plantCrop = null;
            harvestedCrop = null;
            plantContactCommitted = false;
            chargeLightAction();
            if (bagCount() >= BAG_TRIGGER) {
                mode = Mode.TO_STORAGE;
                settler.setActivity(SettlerActivity.CARRYING);
                pathToStorage();
            } else {
                nextOrFinish();
            }
        }
    }

    private void tickMaintainTravel() {
        // Re-validated against whichever rule actually produced this
        // candidate: isMaintainable() knows nothing about cane sites (they
        // are air, not farmland/dirt/grass -- see the class doc's sugar
        // cane section for why that scan is kept wholly separate), so a
        // cane journey re-checked against isMaintainable would always read
        // "invalid" and abort before ever arriving.
        if (maintainTarget == null
            || (maintainIsCane ? !isCaneSite(maintainTarget) : !isMaintainable(maintainTarget))) {
            nextOrFinish();
            return;
        }
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, maintainTarget, "maintain_travel")) {
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(maintainTarget.getX() + 0.5, maintainTarget.getY() + 0.5,
            maintainTarget.getZ() + 0.5);
        if (settler.blockPosition().distSqr(maintainTarget) <= 6.5) {
            settler.getNavigation().stop();
            if (maintainIsCane) {
                // Standing at a legal, still-empty cane site: plant a fresh
                // base stalk directly AT the target (unlike bare-farmland
                // planting below, cane's target IS the air block it grows
                // into, not the ground below it).
                BlockPos canePos = maintainTarget;
                maintainTarget = null;
                if (!beginCanePlant(canePos)) {
                    nextOrFinish(); // the cane left the bag since the poll
                }
                return;
            }
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
            waterContactCommitted = false;
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
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, maintainTarget, "till")) {
            maintainTarget = null;
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
                && (hasNearbyCropAnchor(maintainTarget)
                    || provenanceSeedSlot(false) >= 0)) {
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
     * (farmer audit 2026-08-25). The exact tagged seed leaves the bag on
     * {@link #PLANT_CONTACT_TICK}, the same server tick the hand presses it
     * into the world; the remainder of the clip is recovery only.
     *
     * @return false when the bag holds no plantable seed, so callers can
     *         fall back to nextOrFinish().
     */
    private boolean beginFirstPlant(BlockPos pos) {
        plantActionId = null;
        plantContactCommitted = false;
        int slot = isWithinTendedPlot(pos) ? provenanceSeedSlot(false) : -1;
        if (slot < 0) {
            return false;
        }
        plantActionId = farmSeedAction(settler.bag.getItem(slot)).orElse(null);
        plantCrop = ((BlockItem) settler.bag.getItem(slot).getItem()).getBlock();
        plantDuration = FIRST_PLANT_DURATION;
        target = pos;
        mode = Mode.PLANTING;
        workTicks = 0;
        settler.setActivity(SettlerActivity.WORK_PLANT);
        return true;
    }

    /**
     * Starts planting a fresh cane base. Same PLANTING/{@link
     * #FIRST_PLANT_DURATION} machinery {@link #beginFirstPlant} uses -- see
     * this goal's class doc for why {@code WORK_PLANT} is a placeholder
     * animation choice here, flagged rather than silently kept. The cane
     * item leaves the bag on {@link #PLANT_CONTACT_TICK}, the exact visible
     * press, under the same transaction every planting path keeps.
     *
     * @return false when the bag holds no sugar cane, so callers can fall
     *         back to nextOrFinish().
     */
    private boolean beginCanePlant(BlockPos pos) {
        plantActionId = null;
        plantContactCommitted = false;
        int slot = isWithinTendedPlot(pos) ? provenanceSeedSlot(true) : -1;
        if (slot < 0) {
            return false;
        }
        plantActionId = farmSeedAction(settler.bag.getItem(slot)).orElse(null);
        plantCrop = Blocks.SUGAR_CANE;
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
        if (settler.level() instanceof ServerLevel level
            && !zoneAllowsMutation(level, maintainTarget, "water")) {
            maintainTarget = null;
            nextOrFinish();
            return;
        }
        settler.getLookControl().setLookAt(maintainTarget.getX() + 0.5, maintainTarget.getY() + 0.5,
            maintainTarget.getZ() + 0.5);
        workTicks++;
        if (!waterContactCommitted
            && workTicks % WATER_DURATION == WATER_CONTACT_TICK) {
            if (!(settler.level() instanceof ServerLevel serverLevel)
                || !commitWaterAtContact(serverLevel)) {
                maintainTarget = null;
                waterContactCommitted = false;
                nextOrFinish();
                return;
            }
            waterContactCommitted = true;
            serverLevel.playSound(null, maintainTarget, ModSounds.WATER_POUR.get(),
                SoundSource.NEUTRAL, 0.6F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= WATER_DURATION) {
            if (!waterContactCommitted) {
                maintainTarget = null;
                nextOrFinish();
                return;
            }
            chargeLightAction();
            maintainTarget = null;
            waterContactCommitted = false;
            nextOrFinish();
        }
    }

    /** Moistens the exact tended farmland on FARM_WATER's pour-contact tick. */
    private boolean commitWaterAtContact(ServerLevel serverLevel) {
        if (maintainTarget == null) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(maintainTarget);
        // Water only under a standing crop. The poll already refuses bare
        // farmland, but the crop may have disappeared during anticipation.
        if (!state.is(Blocks.FARMLAND)
            || state.getValue(FarmBlock.MOISTURE) >= 7
            || !isWithinTendedPlot(maintainTarget.above())
            || !(serverLevel.getBlockState(maintainTarget.above()).getBlock()
                instanceof CropBlock crop)
            || !serverLevel.setBlock(maintainTarget,
                state.setValue(FarmBlock.MOISTURE, 7), Block.UPDATE_ALL)) {
            settler.recordRouteFailure("farm_water_contact_invalid");
            return false;
        }

        // Åkerskifte: the optional one-stage growth bonus is part of the same
        // accepted pour, not a second mutation hidden at the loop's end.
        BlockPos above = maintainTarget.above();
        BlockState cropState = serverLevel.getBlockState(above);
        Settlement settlement = settler.settlement();
        if (settlement != null && !crop.isMaxAge(cropState)) {
            float extra = com.hearthstead.settlement.research.Research.bonus(
                serverLevel, settlement.id,
                com.hearthstead.settlement.research.ResearchKey.FARM_GROWTH) - 1.0F;
            if (extra > 0.0F && settler.getRandom().nextFloat() < extra) {
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
        return true;
    }

    /** Pulls the mature crop (or cuts the mature cane top segment, GAPS-1)
     *  and pockets every drop in the bag. Replant input is a separate exact
     *  Farmhouse withdrawal, never silently recycled from this output.
     *  Nothing is held outside the bag, because a plain goal field is
     *  destroyed when the entity unloads or the server stops, and item
     *  conservation is a permanent invariant.
     *
     *  <p>{@code harvestedCrop} is left a plain {@link Block}, never cast to
     *  {@link CropBlock}: sugar cane is a real, valid harvest target here
     *  (see {@link #isMatureCane}) and is not a CropBlock at all, so a cast
     *  would throw the instant a cane top segment matured. */
    private boolean harvest() {
        if (!(settler.level() instanceof ServerLevel serverLevel) || !isHarvestable(target)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        Building farmhouse = tendedFarmhouse();
        if (settlement == null || farmhouse == null || target == null) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(target);
        harvestedCrop = state.getBlock();
        UUID actionId = WorkerProvenanceService.beginFarmHarvest(serverLevel,
            settlement, farmhouse, settler, target);
        if (actionId == null) {
            settler.recordRouteFailure("farm_harvest_authority_blocked");
            return false;
        }
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack raw : Block.getDrops(state, serverLevel, target, null)) {
            if (raw.isEmpty()) {
                continue;
            }
            ItemStack stamped = raw.copy();
            if (!WorkerProvenanceService.stampOutput(serverLevel, settlement,
                    farmhouse, settler, actionId, stamped, target)) {
                settler.recordRouteFailure("farm_harvest_output_prepare_failed");
                return false;
            }
            drops.add(stamped);
        }
        if (drops.isEmpty()) {
            settler.recordRouteFailure("farm_harvest_no_output");
            return false;
        }

        List<ItemStack> bagBefore = snapshotBag();
        if (!WorkerStorageAuthority.storeAllInBag(settler.bag, drops)) {
            // Keep the mature crop physical. The pending one-point hoe receipt
            // is replay-safe, so a later retry cannot charge durability twice.
            settler.recordRouteFailure("farm_harvest_bag_full");
            return false;
        }
        if (!serverLevel.removeBlock(target, false)) {
            restoreBag(bagBefore);
            settler.recordRouteFailure("farm_harvest_block_changed");
            return false;
        }
        if (!WorkerProvenanceService.commitFarmHarvest(serverLevel, settlement,
                farmhouse, settler, actionId, target, drops)) {
            // No unreceipted duplicate may survive a rejected terminal
            // commit. Restore both physical sides if the crop position is
            // still ours; if the world changed during this server-thread
            // transaction, keep the stamped bag output as the only physical
            // side and quarantine the route instead of minting the crop too.
            boolean worldRolledBack = serverLevel.getBlockState(target).isAir()
                && serverLevel.setBlock(target, state, Block.UPDATE_ALL);
            if (worldRolledBack) {
                restoreBag(bagBefore);
                settler.recordRouteFailure("farm_harvest_receipt_failed");
            } else {
                settler.recordRouteFailure(
                    "farm_harvest_world_rollback_failed");
            }
            return false;
        }
        serverLevel.playSound(null, target, state.getSoundType().getBreakSound(),
            SoundSource.BLOCKS, 0.8F, 1.0F);
        // One crop pulled by hand is one unit of the daily pool -- charged
        // whether or not there is a seed to replant, since the labor is the
        // same either way (PLAN_EFFORT.md §2).
        settler.spendEffort(1);
        return true;
    }

    private List<ItemStack> snapshotBag() {
        List<ItemStack> snapshot = new ArrayList<>(settler.bag.getContainerSize());
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            snapshot.add(settler.bag.getItem(slot).copy());
        }
        return snapshot;
    }

    private void restoreBag(List<ItemStack> snapshot) {
        if (snapshot.size() != settler.bag.getContainerSize()) {
            throw new IllegalStateException("farmer bag size changed mid-transaction");
        }
        for (int slot = 0; slot < snapshot.size(); slot++) {
            settler.bag.setItem(slot, snapshot.get(slot).copy());
        }
    }

    /**
     * Converts one exact persisted Farmhouse input into one world block on
     * the visible press tick. The seed, block, tool point and provenance
     * receipt are one server-thread transaction. If the terminal receipt is
     * refused, rollback restores both physical sides when that can still be
     * done safely; a failed world rollback never mints a replacement seed.
     */
    private boolean commitPlantAtContact(ServerLevel serverLevel) {
        Settlement settlement = settler.settlement();
        Building farmhouse = tendedFarmhouse();
        if (settlement == null || farmhouse == null || target == null
            || plantActionId == null) {
            settler.recordRouteFailure("farm_seed_plant_authority_missing");
            return false;
        }

        Block plantedBlock;
        BlockState plantedState;
        PlantingInput input;
        if (plantCrop instanceof CropBlock crop
            && isWithinTendedPlot(target)
            && isWithinTendedPlot(target.below())
            && serverLevel.getBlockState(target).isAir()
            && serverLevel.getBlockState(target.below()).is(Blocks.FARMLAND)) {
            plantedBlock = crop;
            plantedState = crop.getStateForAge(0);
            input = takeSeedFor(crop);
        } else if (plantCrop == Blocks.SUGAR_CANE && isCaneSite(target)) {
            plantedBlock = Blocks.SUGAR_CANE;
            plantedState = Blocks.SUGAR_CANE.defaultBlockState();
            input = takeCaneFor();
        } else {
            settler.recordRouteFailure("farm_seed_plant_contact_invalid");
            return false;
        }
        if (input == null) {
            settler.recordRouteFailure("farm_seed_plant_input_missing");
            return false;
        }

        boolean prepared = WorkerProvenanceService.prepareFarmPlant(
            serverLevel, settlement, farmhouse, settler, plantActionId, target);
        boolean placed = prepared && serverLevel.setBlock(target, plantedState,
            Block.UPDATE_ALL);
        boolean committed = placed && WorkerProvenanceService.commitFarmPlant(
            serverLevel, settlement, farmhouse, settler, plantActionId, target,
            BuiltInRegistries.BLOCK.getKey(plantedBlock));
        if (!committed) {
            boolean worldRolledBack = !placed;
            if (placed && serverLevel.getBlockState(target).is(plantedBlock)) {
                worldRolledBack = serverLevel.removeBlock(target, false);
            }
            if (worldRolledBack) {
                restorePlantingInput(input);
                settler.recordRouteFailure("farm_seed_plant_receipt_failed");
            } else {
                // The seed has a physical world-side result. Restoring it as
                // well would duplicate matter, so quarantine this route and
                // leave the unreceipted result fail-closed for operator QA.
                settler.recordRouteFailure(
                    "farm_seed_plant_world_rollback_failed");
            }
            return false;
        }
        notePlantingInputConsumed();
        return true;
    }

    private void abortPlantAttempt() {
        plantActionId = null;
        plantCrop = null;
        harvestedCrop = null;
        plantContactCommitted = false;
        nextOrFinish();
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
            if (isHarvestable(candidate)) {
                target = candidate;
                mode = Mode.TO_WORK;
                pathToTarget();
                return;
            }
        }
        // Do not let one partially scanned column batch turn a small harvest
        // into a storage trip. Restarting selection keeps the queues and the
        // fixed-X/Z cursor intact, so the next bounded batch is surveyed
        // before any below-threshold output can leave the field.
        WorkZone currentZone = null;
        Settlement settlement = settler.settlement();
        if (settlement != null && settler.level() instanceof ServerLevel level) {
            Building employer = Employment.employerOf(settlement,
                settler.getUUID());
            currentZone = authoritativeZone(level, settlement, employer);
        }
        if (activeSurveyFor(currentZone)) {
            done = true;
            return;
        }
        // Only the one still-active Farmhouse input unit is working stock.
        // Harvested/ordinary seed is output like any other crop: it returns
        // to the Farmhouse before a later action can withdraw one exact unit.
        if (bagHoldsProduce()) {
            mode = Mode.TO_STORAGE;
            settler.setActivity(SettlerActivity.CARRYING);
            pathToStorage();
        } else {
            done = true;
        }
    }

    /** Anything not owned by one still-live Farmhouse input action. */
    private boolean bagHoldsProduce() {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty() && isDepositableFarmerCargo(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ordinary matter and authenticated Farm output may travel to storage.
     * A seed-input receipt (or malformed/foreign transit marker) stays in the
     * bag for its exact recovery path and can never be laundered as produce.
     */
    private static boolean isDepositableFarmerCargo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var transit = WorkerStackProvenance.readTransit(stack).orElse(null);
        return transit == null
            ? !WorkerStackProvenance.hasTransitMarker(stack)
            : transit.kind() == TransitKind.FARM_CROP;
    }

    private void tickDeposit() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement settlement = settler.settlement();
        Building farmhouse = tendedFarmhouse();
        if (settlement == null || farmhouse == null) {
            settler.recordRouteFailure("farmhouse_missing");
            depositRetryCooldown = DEPOSIT_RETRY_TICKS;
            done = true;
            return;
        }
        if (depositTarget == null) {
            depositTarget = WorkerStorageAuthority.nearestLoadedContainer(level,
                farmhouse, settler.blockPosition());
        }
        if (depositTarget == null) {
            settler.recordRouteFailure("farmhouse_storage_missing");
            depositRetryCooldown = DEPOSIT_RETRY_TICKS;
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(depositTarget.getX() + 0.5,
            depositTarget.getY() + 0.6, depositTarget.getZ() + 0.5);
        ContainerApproach.Result contact = ContainerApproach.inspect(level,
            settler, depositTarget);
        if (contact.canInteract()) {
            settler.getNavigation().stop();
            stuckChecks = 0;
            // The exact active input unit remains in the bag until planting;
            // every output unit, including harvested seeds, goes to this
            // linked Farmhouse for later Courier collection.
            for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                ItemStack stack = settler.bag.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (!isDepositableFarmerCargo(stack)) {
                    continue;
                }
                ItemStack send = stack.copy();
                ItemStack offered = send.copy();
                WorkerProvenanceService.DepositResult result =
                    WorkerStackProvenance.readTransit(send).isPresent()
                        ? WorkerProvenanceService.depositOutput(level,
                            settlement, farmhouse, settler, depositTarget, send)
                        : WorkerProvenanceService.depositOrdinary(level,
                            settlement, farmhouse, settler, depositTarget, send,
                            BuildingType.FARMHOUSE, WorkZone.Type.FARM);
                ItemStack leftover = result.remainder();
                int inserted = offered.getCount() - leftover.getCount();
                if (result.receipt() != null && inserted > 0) {
                    DevelopmentQuests.noteFarmCropsStored(level, settlement,
                        farmhouse, settler, offered, inserted);
                }
                settler.bag.setItem(i, leftover);
            }
            depositTarget = null;
            if (bagCount() >= BAG_TRIGGER || bagHoldsProduce()) {
                settler.recordRouteFailure("farmhouse_storage_full");
                depositRetryCooldown = DEPOSIT_RETRY_TICKS;
            } else {
                depositRetryCooldown = 0;
            }
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (contact.state() == ContainerApproach.State.INVALID_TARGET) {
                depositTarget = null;
            }
            if (++stuckChecks > 6) {
                settler.recordRouteFailure("farmhouse_storage_unreachable");
                depositRetryCooldown = DEPOSIT_RETRY_TICKS;
                done = true;
                return;
            }
            pathToStorage();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        if (mode == Mode.TO_INPUT || mode == Mode.TO_RETURN_INPUT) {
            // Source selection itself owns no inventory. Drop transient route
            // state on preemption; any already-withdrawn action is persisted
            // and will rebuild either its exact plant or exact return route.
            inputTarget = null;
            inputCrop = null;
            inputPurpose = null;
            inputReturnAction = null;
            mode = Mode.TO_WORK;
        }
        target = null;
        plantCrop = null;
        plantActionId = null;
        harvestContactCommitted = false;
        plantContactCommitted = false;
        waterContactCommitted = false;
    }

    /** Does the bag hold the exact Farmhouse-input seed for this crop? */
    private boolean hasSeedFor(Block crop) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == crop
                && farmSeedAction(stack).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Removes one exact action seed, retaining a lossless rollback image. */
    @Nullable
    private PlantingInput takeSeedFor(Block crop) {
        int slot = plantActionId == null ? -1
            : seedSlotForAction(crop, plantActionId);
        if (slot < 0) {
            return null;
        }
        return takePlantingInput(slot);
    }

    /** A stack that would plant some crop. */
    private static boolean isSeedStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof CropBlock;
    }

    private int seedSlotForAction(Block crop, UUID actionId) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == crop
                && farmSeedAction(stack).filter(actionId::equals).isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    /** Prefer the exact Farmhouse-input unit so FJ-360 cannot be spoofed by
     * an arbitrary seed already present in the worker inventory. */
    private int provenanceSeedSlot(boolean cane) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if ((cane ? stack.is(Items.SUGAR_CANE) : isSeedStack(stack))
                && farmSeedAction(stack).isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    private int provenanceSeedSlot(UUID actionId) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            if (farmSeedAction(settler.bag.getItem(slot))
                    .filter(actionId::equals).isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    /** Exact old-zone input unit accepted only for return-to-source recovery. */
    private int recoverableSeedSlot(UUID actionId) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return -1;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return -1;
        }
        int found = -1;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            if (WorkerProvenanceService.recoverableFarmSeedAction(level,
                    settlement, settler, settler.bag.getItem(slot))
                    .filter(actionId::equals).isEmpty()) {
                continue;
            }
            if (found >= 0) {
                return -1;
            }
            found = slot;
        }
        return found;
    }

    private java.util.Optional<UUID> farmSeedAction(ItemStack stack) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return java.util.Optional.empty();
        }
        Settlement settlement = settler.settlement();
        Building farmhouse = tendedFarmhouse();
        return settlement == null || farmhouse == null
            ? java.util.Optional.empty()
            : WorkerProvenanceService.farmSeedAction(level, settlement,
                farmhouse, settler, stack);
    }

    /**
     * BOOTSTRAP SEEDS (farmer audit 2026-08-25, CRITICAL): the only plant
     * path used to be the replant on a just-harvested tile, so a farmhouse
     * that never had a crop could never get one. Only an exact action-tagged
     * unit previously withdrawn from this linked Farmhouse is accepted;
     * arbitrary bag cargo cannot spoof FJ-360.
     */
    private boolean ensureSeedInBag() {
        // Selection is read-only. A missing seed starts TO_INPUT, which
        // invokes the authoritative source-to-bag move only at real container
        // contact; this method must never mutate a distant Farmhouse chest.
        return provenanceSeedSlot(false) >= 0;
    }

    /** Removes one exact Farmhouse-input cane with lossless rollback. */
    @Nullable
    private PlantingInput takeCaneFor() {
        int slot = plantActionId == null ? -1
            : caneSlotForAction(plantActionId);
        if (slot < 0) {
            return null;
        }
        return takePlantingInput(slot);
    }

    @Nullable
    private PlantingInput takePlantingInput(int slot) {
        ItemStack before = settler.bag.getItem(slot).copy();
        ItemStack removed = settler.bag.removeItem(slot, 1);
        if (removed.isEmpty() || removed.getCount() != 1) {
            settler.bag.setItem(slot, before);
            return null;
        }
        return new PlantingInput(slot, before, removed);
    }

    private void restorePlantingInput(PlantingInput input) {
        // Nothing else mutates this bag slot between take and terminal world
        // commit on the single server thread, so restoring its exact before
        // image is both lossless and immune to component/tag merge rules.
        settler.bag.setItem(input.slot(), input.before().copy());
    }

    private void notePlantingInputConsumed() {
        settler.train(com.hearthstead.entity.Attribute.DEXTERITY, 1.0F);
    }

    private record PlantingInput(int slot, ItemStack before, ItemStack removed) {
        private PlantingInput {
            before = before.copy();
            removed = removed.copy();
        }
    }

    private int caneSlotForAction(UUID actionId) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (stack.is(Items.SUGAR_CANE)
                && farmSeedAction(stack).filter(actionId::equals).isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * BOOTSTRAP CANE, parallel to {@link #ensureSeedInBag}: accepts only the
     * exact tagged input unit or withdraws one from the linked Farmhouse.
     */
    private boolean ensureCaneInBag() {
        return provenanceSeedSlot(true) >= 0;
    }

}

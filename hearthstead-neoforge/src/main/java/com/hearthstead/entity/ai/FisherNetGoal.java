package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.FisherNetBuoyEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.DevelopmentBonuses;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.work.FisherNetYield;
import com.hearthstead.settlement.work.FishingGrounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Fisher's Nets: the Fisher sets a net off his shore chair (a visible
 * {@link FisherNetBuoyEntity}) and hauls it on his rounds: a full net any
 * time, and whatever it holds on his first round of the work day.
 *
 * <p>One clip (FISHER_NET, 4.0 s) does both: three hand-over-hand pulls on the
 * head rope, the net lifted and shaken out (the catch goes into the bag at
 * {@link #HAUL_TICK}), then tossed back (the float lands at {@link #TOSS_TICK}).
 * The hauled fish are ordinary bag fish: FisherWorkGoal carries them to the
 * fishery rack or barrel exactly like a cast catch.
 *
 * <p>Registered at the trade priority just before FisherWorkGoal, so it only
 * starts between casts, never mid-cast, and only with an empty fish bag.
 */
public final class FisherNetGoal extends Goal {
    public static final int NET_TICKS = 80;
    public static final int HAUL_TICK = 60;
    public static final int TOSS_TICK = 70;
    /** Short: FisherWorkGoal leaves only a 20-tick gap between casts. */
    private static final int CHECK_INTERVAL = 10;
    private static final int TRAVEL_BUDGET = 240;
    /** Same saved counter FisherWorkGoal credits on a real rack insert. */
    private static final String LANDED = "HearthsteadFisherLanded";
    public static final String HAUL_DAY_TAG = "HearthsteadNetHaulDay";
    public static final String HAULED_TAG = "HearthsteadNetHauled";

    private final SettlerEntity settler;
    private long nextCheck;
    private boolean setting;
    @Nullable private Building fishery;
    @Nullable private BlockPos water;
    @Nullable private BlockPos stand;
    private boolean working;
    private boolean done;
    private int ticks;
    private int travelTicks;

    public FisherNetGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean onShift() {
        return settler.getProfession() == Profession.FISHER && settler.isBound() && settler.dayPhase().work();
    }

    @Nullable
    private Building employer() {
        Settlement village = settler.settlement();
        Building current = village == null ? null : Employment.employerOf(village, settler.getUUID());
        return current != null && current.valid && current.type == BuildingType.FISHERY && current.anchor != null
            ? current : null;
    }

    private boolean bagHasFish() {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (settler.bag.getItem(i).is(ItemTags.FISHES)) {
                return true;
            }
        }
        return false;
    }

    private long today(ServerLevel level) {
        return Math.floorDiv(level.getDayTime(), 24000L);
    }

    @Override
    public boolean canUse() {
        if (!onShift() || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextCheck) {
            return false;
        }
        nextCheck = now + CHECK_INTERVAL;
        if (!DevelopmentBonuses.owned(level, settler.settlement(), PostRaidUpgrade.FISHERS_NETS) || bagHasFish()) {
            return false;
        }
        fishery = employer();
        if (fishery == null) {
            return false;
        }
        FisherNetBuoyEntity buoy = FisherNetBuoyEntity.find(level, fishery);
        if (buoy != null) {
            boolean firstRound = settler.getPersistentData().getLong(HAUL_DAY_TAG) != today(level) + 1;
            int held = buoy.accrue(now);
            if (!FisherNetYield.shouldHaul(held, firstRound)) {
                return false;
            }
            if (!settler.bag.canAddItem(new ItemStack(Items.COD, held))) {
                return false;
            }
        }
        var grounds = FishingGrounds.scan(level, fishery.anchor);
        if (grounds.ready()) {
            setting = buoy == null;
            water = setting ? netWater(level, grounds.shorePosition(), grounds.direction()) : buoy.water();
            stand = standBeside(level, grounds.shorePosition(), grounds.direction());
            if (water != null && stand != null) {
                return true;
            }
        }
        // No shore, no open water or no aisle: the full survey waits a while.
        nextCheck = now + 200;
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && onShift() && employer() == fishery && fishery != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        working = false;
        ticks = 0;
        travelTicks = 0;
        settler.setActivity(SettlerActivity.TRAVELING);
        path();
    }

    private void path() {
        if (stand != null) {
            var route = settler.getNavigation().createPath(stand, 0);
            if (route != null && route.canReach()) {
                settler.getNavigation().moveTo(route, .9);
            } else {
                done = true;
            }
        }
    }

    @Override
    public void tick() {
        if (done || !(settler.level() instanceof ServerLevel level) || stand == null || water == null) {
            return;
        }
        if (!working) {
            if (settler.position().distanceToSqr(Vec3.atBottomCenterOf(stand)) < .64) {
                settler.getNavigation().stop();
                working = true;
                ticks = 0;
                settler.setActivity(SettlerActivity.WORK_NET);
            } else if (++travelTicks > TRAVEL_BUDGET) {
                settler.recordRouteFailure("fisher_net_unreachable");
                done = true;
            } else if (travelTicks % 40 == 0) {
                path();
            }
            return;
        }
        face();
        ticks++;
        if (ticks == HAUL_TICK && !setting) {
            haul(level);
        }
        if (ticks == TOSS_TICK) {
            toss(level);
        }
        if (ticks >= NET_TICKS) {
            settler.setActivity(SettlerActivity.IDLE);
            done = true;
        }
    }

    private void face() {
        double dx = water.getX() + .5 - settler.getX();
        double dz = water.getZ() + .5 - settler.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        settler.setYRot(yaw);
        settler.setYBodyRot(yaw);
        settler.setYHeadRot(yaw);
        settler.getLookControl().setLookAt(water.getX() + .5, water.getY() + .9, water.getZ() + .5);
    }

    private void haul(ServerLevel level) {
        FisherNetBuoyEntity buoy = FisherNetBuoyEntity.find(level, fishery);
        settler.getPersistentData().putLong(HAUL_DAY_TAG, today(level) + 1);
        if (buoy == null) {
            return;
        }
        int held = buoy.haul(level.getGameTime());
        int landed = 0;
        for (int i = 0; i < held; i++) {
            ItemStack fish = new ItemStack(settler.getRandom().nextInt(10) < 3 ? Items.SALMON : Items.COD);
            if (!settler.bag.canAddItem(fish)) {
                break;
            }
            settler.bag.addItem(fish);
            landed++;
        }
        if (landed > 0) {
            var data = settler.getPersistentData();
            data.putInt(LANDED, Math.min(4096, data.getInt(LANDED) + landed));
            data.putInt(HAULED_TAG, Math.min(1_000_000, data.getInt(HAULED_TAG) + landed));
            level.playSound(null, water, SoundEvents.FISHING_BOBBER_RETRIEVE,
                net.minecraft.sounds.SoundSource.NEUTRAL, .5F, .9F);
        }
    }

    private void toss(ServerLevel level) {
        if (setting && FisherNetBuoyEntity.find(level, fishery) == null
            && FisherNetBuoyEntity.waterHolds(level, water)) {
            FisherNetBuoyEntity buoy = ModEntities.FISHER_NET_BUOY.get().create(level);
            Settlement village = settler.settlement();
            if (buoy != null && village != null) {
                buoy.set(village, fishery, water, level.getGameTime());
                level.addFreshEntity(buoy);
                // A net set today is first hauled on a later round, not within the hour.
                settler.getPersistentData().putLong(HAUL_DAY_TAG, today(level) + 1);
            }
        }
        WorkSoundSync.play(level, water, ModSounds.WORK_FISH_SPLASH.get(), .5F, 1.1F);
    }

    /** Open surface water a few cells out, off to one side of the cast line. */
    @Nullable
    private static BlockPos netWater(ServerLevel level, BlockPos chair, Direction facing) {
        BlockPos front = chair.below();
        int[][] tries = {{3, 2}, {3, -2}, {4, 2}, {4, -2}, {2, 2}, {2, -2}, {3, 1}, {3, -1}, {4, 0}, {3, 0}, {2, 0}};
        Direction side = facing.getClockWise();
        for (int[] t : tries) {
            BlockPos p = front.relative(facing, t[0]).relative(side, t[1]);
            if (level.hasChunkAt(p) && FisherNetBuoyEntity.waterHolds(level, p)
                && !level.getFluidState(p).isEmpty()) {
                return p.immutable();
            }
        }
        return null;
    }

    /** The chair's aisle cell (never the water side), reachable now. */
    @Nullable
    private BlockPos standBeside(ServerLevel level, BlockPos chair, Direction facing) {
        BlockPos best = null;
        double distance = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == facing) {
                continue;
            }
            BlockPos p = chair.relative(d);
            if (!FishingGrounds.standable(level, p)) {
                continue;
            }
            var route = settler.getNavigation().createPath(p, 0);
            if (route == null || !route.canReach()) {
                continue;
            }
            double current = p.distSqr(settler.blockPosition());
            if (current < distance) {
                best = p;
                distance = current;
            }
        }
        return best;
    }

    @Override
    public void stop() {
        if (settler.getActivity() == SettlerActivity.WORK_NET) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        settler.getNavigation().stop();
        working = false;
    }
}

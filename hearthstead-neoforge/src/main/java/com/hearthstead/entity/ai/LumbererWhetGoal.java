package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModParticles;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.DevelopmentBonuses;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.work.FishingGrounds;
import com.hearthstead.settlement.work.LumberWhetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Sharpened Axes: after {@link LumberWhetting#TREES_PER_WHET} trees the
 * Lumberer walks to the grindstone in his Lumber Camp and whets his axe for
 * 3.5 s (WORK_WHET, clip WHET_AXE: four uneven strokes, sparks and the
 * grindstone rasp on each), then goes back to felling.
 *
 * <p>Registered at the trade priority just before LumbererWorkGoal, so the two
 * never preempt each other: the whet only begins between trees (the felling
 * goal has finished its haul) and with no logs in the bag. A camp without a
 * reachable grindstone gets the same whet on the spot, a whetstone stroke
 * without sparks. Nothing here touches the axe stack (its damage carries
 * provenance receipts) or the world.
 */
public final class LumbererWhetGoal extends Goal {
    private static final int CHECK_INTERVAL = 40;
    private static final int TRAVEL_BUDGET = 240;
    /** Largest camp box scanned for a grindstone (blocks). */
    private static final int MAX_SCAN = 24 * 12 * 24;

    private final SettlerEntity settler;
    private long nextCheck;
    @Nullable private BlockPos grindstone;
    @Nullable private BlockPos stand;
    private boolean whetting;
    private boolean done;
    private int ticks;
    private int travelTicks;
    /** Why this whet is where it is (sheet/test evidence): grindstone, or the fallback reason. */
    private String where = "";

    public LumbererWhetGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean working() {
        return settler.getProfession() == Profession.LUMBERER && settler.isBound()
            && settler.dayPhase().work();
    }

    @Nullable
    private Building camp() {
        Settlement village = settler.settlement();
        Building camp = village == null ? null : Employment.employerOf(village, settler.getUUID());
        return camp != null && camp.valid && camp.type == BuildingType.LUMBER_CAMP && camp.anchor != null
            ? camp : null;
    }

    private boolean bagHasLogs() {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (settler.bag.getItem(i).is(ItemTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canUse() {
        if (!working() || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        // Checked on every selection pass once due: the felling goal restarts the
        // moment its haul ends, so a throttled check could miss every gap.
        if (!LumberWhetting.due(settler.getPersistentData().getInt(LumberWhetting.TREES_TAG))) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextCheck) {
            return false;
        }
        if (!DevelopmentBonuses.owned(level, settler.settlement(), PostRaidUpgrade.SHARPENED_AXES)
            || !settler.getMainHandItem().is(ItemTags.AXES) || bagHasLogs()) {
            return false;
        }
        Building camp = camp();
        if (camp == null) {
            nextCheck = now + CHECK_INTERVAL;
            return false;
        }
        grindstone = findGrindstone(level, camp);
        stand = grindstone == null ? null : standBeside(level, grindstone);
        where = grindstone == null ? "spot:no_grindstone" : stand == null ? "spot:no_stand" : "grindstone";
        if (stand == null) {
            grindstone = null;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && working() && camp() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        ticks = 0;
        travelTicks = 0;
        whetting = false;
        if (stand == null) {
            beginWhet();
        } else {
            settler.setActivity(SettlerActivity.TRAVELING);
            walk();
        }
    }

    /** Exact-cell route (accuracy 0): a coordinate moveTo may stop a block short of the wheel. */
    private void walk() {
        var route = settler.getNavigation().createPath(stand, 0);
        if (route != null) {
            settler.getNavigation().moveTo(route, .9);
        } else {
            // No exact route yet (just loaded, say): head there anyway; the
            // step-in covers the last metre, the travel budget the rest.
            settler.getNavigation().moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5, .9);
        }
    }

    @Override
    public void tick() {
        if (done || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        if (!whetting) {
            double d = stand == null ? 99 : settler.position().distanceToSqr(Vec3.atBottomCenterOf(stand));
            if (d < .64) {
                settler.getNavigation().stop();
                beginWhet();
            } else if (stand != null && d < 4.0 && settler.getNavigation().isDone()) {
                // The route ended short of the cell (a partial path round the
                // chest or the stone): step the last bit straight in, so he
                // whets at the wheel and not a block away from it.
                settler.getMoveControl().setWantedPosition(stand.getX() + .5, stand.getY(), stand.getZ() + .5, .7);
                if (++travelTicks > TRAVEL_BUDGET) {
                    grindstone = null;
                    where = "spot:short_of_stand";
                    beginWhet();
                }
            } else if (++travelTicks > TRAVEL_BUDGET) {
                // The grindstone is there but out of reach today: whet here.
                grindstone = null;
                where = "spot:travel_budget";
                settler.getNavigation().stop();
                beginWhet();
            } else if (travelTicks % 40 == 0 && stand != null) {
                walk();
            }
            return;
        }
        face(level);
        ticks++;
        if (LumberWhetting.strokeAt(ticks)) {
            stroke(level);
        }
        if (ticks >= LumberWhetting.WHET_TICKS) {
            settler.getPersistentData().putInt(LumberWhetting.TREES_TAG, 0);
            settler.getPersistentData().putLong(LumberWhetting.LAST_WHET_TAG, level.getGameTime());
            settler.getPersistentData().putString(LumberWhetting.LAST_WHET_WHERE_TAG, where);
            settler.setActivity(SettlerActivity.IDLE);
            done = true;
        }
    }

    private void beginWhet() {
        whetting = true;
        ticks = 0;
        settler.setActivity(SettlerActivity.WORK_WHET);
        if (settler.level() instanceof ServerLevel level) {
            face(level);
        }
    }

    /** Square to the wheel (or keep the current heading on the spot). */
    private void face(ServerLevel level) {
        if (grindstone == null) {
            settler.getLookControl().setLookAt(settler.getX() + Mth.sin(-settler.getYRot() * Mth.DEG_TO_RAD),
                settler.getY() + .6, settler.getZ() + Mth.cos(settler.getYRot() * Mth.DEG_TO_RAD));
            return;
        }
        double dx = grindstone.getX() + .5 - settler.getX();
        double dz = grindstone.getZ() + .5 - settler.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        settler.setYRot(yaw);
        settler.setYBodyRot(yaw);
        settler.setYHeadRot(yaw);
        settler.getLookControl().setLookAt(grindstone.getX() + .5, grindstone.getY() + .6, grindstone.getZ() + .5);
    }

    private void stroke(ServerLevel level) {
        if (grindstone != null && level.getBlockState(grindstone).is(Blocks.GRINDSTONE)) {
            double x = grindstone.getX() + .5 + (settler.getX() - grindstone.getX() - .5) * .25;
            double z = grindstone.getZ() + .5 + (settler.getZ() - grindstone.getZ() - .5) * .25;
            level.sendParticles(ModParticles.ANVIL_SPARK.get(), x, grindstone.getY() + .82, z,
                5 + settler.getRandom().nextInt(4), .08, .04, .08, .12);
            WorkSoundSync.play(level, grindstone, SoundEvents.GRINDSTONE_USE, .45F, 1.05F);
        } else {
            // Whetstone on the spot: a thin, quiet rasp and no sparks.
            WorkSoundSync.play(level, settler.getX(), settler.getY() + 1.0, settler.getZ(),
                SoundEvents.GRINDSTONE_USE, .22F, 1.7F);
        }
    }

    @Nullable
    private static BlockPos findGrindstone(ServerLevel level, Building camp) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        // The camp's own box plus two blocks of slack, united with a fixed
        // 8-block reach round the anchor: a yard's validated bounds can be
        // smaller than the yard (a grindstone by the fence is still the camp's).
        BlockPos min = camp.anchor.offset(-8, -2, -8);
        BlockPos max = camp.anchor.offset(8, 3, 8);
        if (camp.bounds != null && (long) camp.bounds.getXSpan() * camp.bounds.getYSpan()
            * camp.bounds.getZSpan() <= MAX_SCAN) {
            min = new BlockPos(Math.min(min.getX(), camp.bounds.minX() - 2),
                Math.min(min.getY(), camp.bounds.minY()), Math.min(min.getZ(), camp.bounds.minZ() - 2));
            max = new BlockPos(Math.max(max.getX(), camp.bounds.maxX() + 2),
                Math.max(max.getY(), camp.bounds.maxY()), Math.max(max.getZ(), camp.bounds.maxZ() + 2));
        }
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(p)) {
                continue;
            }
            BlockState state = level.getBlockState(p);
            if (!state.is(Blocks.GRINDSTONE)) {
                continue;
            }
            double distance = p.distSqr(camp.anchor);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = p.immutable();
            }
        }
        return best;
    }

    /** A reachable floor cell square to the wheel; the wheel's own axis first. */
    @Nullable
    private BlockPos standBeside(ServerLevel level, BlockPos stone) {
        BlockState state = level.getBlockState(stone);
        Direction facing = state.hasProperty(GrindstoneBlock.FACING)
            ? state.getValue(GrindstoneBlock.FACING) : Direction.NORTH;
        Direction[] order = {facing, facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise()};
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < order.length; i++) {
            BlockPos p = stone.relative(order[i]);
            // Only the cell itself is checked here: a route computed inside
            // canUse can fail for a settler that has just spawned or loaded,
            // which used to turn every whet into the on-the-spot fallback.
            // Reachability is proven by the walk (travel budget, last-metre
            // step-in), with the on-the-spot whet as the real fallback.
            if (!FishingGrounds.standable(level, p)) {
                continue;
            }
            // Axis cells win outright; the side cells only when no axis cell works.
            double distance = p.distSqr(settler.blockPosition()) + (i < 2 ? 0 : 10_000);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = p.immutable();
            }
        }
        return best;
    }

    @Override
    public void stop() {
        if (settler.getActivity() == SettlerActivity.WORK_WHET) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        settler.getNavigation().stop();
        whetting = false;
        grindstone = null;
        stand = null;
    }

    /** Called by LumbererWorkGoal the moment a tree is committed. */
    public static void noteTreeFelled(ServerLevel level, @Nullable Settlement settlement, SettlerEntity settler) {
        boolean owned = DevelopmentBonuses.owned(level, settlement, PostRaidUpgrade.SHARPENED_AXES);
        var data = settler.getPersistentData();
        data.putInt(LumberWhetting.TREES_TAG,
            LumberWhetting.afterTree(data.getInt(LumberWhetting.TREES_TAG), owned));
    }
}

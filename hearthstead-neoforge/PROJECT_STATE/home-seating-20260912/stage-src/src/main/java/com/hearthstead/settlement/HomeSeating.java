package com.hearthstead.settlement;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernSeatMotion;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

/**
 * A resident's short evening chair break in their assigned valid home.
 *
 * <p>This owns no food, host, table claim, morale, or energy effect. It only
 * reserves one physical chair in the house that owns the resident's claimed
 * bed, then uses the same collision-checked seat vehicle as the Tavern.
 */
public final class HomeSeating {
    public static final int REST_TICKS = 240;
    private static final int MAX_SCAN_CELLS = 512;
    private static final int MAX_PATHS = 8;

    public record Search(@Nullable TavernSeatEntity seat, @Nullable Path path) {
    }

    private HomeSeating() {
    }

    public static boolean hasHomeSeat(SettlerEntity actor) {
        return actor.getVehicle() instanceof TavernSeatEntity seat
            && seat.owns(actor) && seat.mode() == TavernSeatEntity.Mode.HOME;
    }

    @Nullable
    public static Building homeOf(SettlerEntity actor) {
        Settlement settlement = actor.settlement();
        BlockPos bed = actor.getClaimedBed();
        if (settlement == null || bed == null || settlement.record(actor.getUUID()) == null) {
            return null;
        }
        for (Building building : settlement.buildings) {
            if (building.valid && building.type.housesResidents()
                && building.beds.contains(bed)) {
                return building;
            }
        }
        return null;
    }

    /**
     * Shared interruption policy. It deliberately has no hunger/energy effect:
     * critical food and real sleep retain their existing owners.
     */
    public static boolean mayRest(SettlerEntity actor) {
        Settlement settlement = actor.settlement();
        if (settlement == null || actor.isTraveler() || !actor.isAlive()
            || actor.isSleeping() || actor.getTarget() != null
            || actor.hurtTime > 0 || actor.isOnFire() || actor.hasMeal()
            || Summons.active(actor) || settlement.pendingRaid != null
            || settlement.alertUntilGameTime > actor.level().getGameTime()
            || !actor.dayPhase().social() || Schedule.shouldSleep(settlement, actor,
                actor.dayPhase()) || homeOf(actor) == null) {
            return false;
        }
        return !actor.getProfession().martial()
            || (!Schedule.onWatch(settlement, actor, actor.dayPhase())
                && settlement.guardOrders.order(actor.getUUID()).isEmpty());
    }

    /**
     * A home site uses the common seat geometry; table and host approach are
     * inert compatibility coordinates and are never inspected in HOME mode.
     */
    @Nullable
    public static TavernSeating.SeatSite site(ServerLevel level, Building home,
                                               BlockPos chair, BlockPos aisle) {
        if (home == null || !home.valid || !home.type.housesResidents()
            || !home.contains(chair) || !home.contains(aisle)
            || !level.hasChunkAt(chair)) {
            return null;
        }
        Direction facing = TavernSeating.chairFacing(level.getBlockState(chair));
        if (facing == null || (!aisle.equals(chair.relative(facing.getClockWise()))
                && !aisle.equals(chair.relative(facing.getCounterClockWise())))) {
            return null;
        }
        BlockPos inertTable = chair.relative(facing);
        return new TavernSeating.SeatSite(home.id, chair.immutable(), inertTable,
            facing, aisle.immutable(), inertTable.relative(facing));
    }

    public static boolean valid(ServerLevel level, SettlerEntity actor,
                                TavernSeating.SeatSite site) {
        Building home = homeOf(actor);
        return mayRest(actor) && home != null && site != null
            && home.id.equals(site.tavernId())
            && site.equals(site(level, home, site.chair(), site.aisle()))
            && TavernSeating.clearSeatedHeadroom(level, actor, site);
    }

    /** Bounded search: the resident's own assigned home only. */
    public static Search reserveReachable(SettlerEntity actor) {
        if (!(actor.level() instanceof ServerLevel level) || !mayRest(actor)
            || actor.isPassenger()
            || actor.getActivity() != com.hearthstead.entity.SettlerActivity.IDLE) {
            return new Search(null, null);
        }
        Building home = homeOf(actor);
        if (home == null || home.bounds == null) {
            return new Search(null, null);
        }
        int cells = 0;
        int paths = 0;
        for (BlockPos chair : BlockPos.betweenClosed(home.bounds.minX(),
                home.bounds.minY(), home.bounds.minZ(), home.bounds.maxX(),
                home.bounds.maxY(), home.bounds.maxZ())) {
            if (++cells > MAX_SCAN_CELLS) {
                break;
            }
            if (!level.hasChunkAt(chair) || TavernSeating.occupied(level, chair)) {
                continue;
            }
            Direction facing = TavernSeating.chairFacing(level.getBlockState(chair));
            if (facing == null) {
                continue;
            }
            for (Direction side : new Direction[] {
                    facing.getClockWise(), facing.getCounterClockWise() }) {
                TavernSeating.SeatSite site = site(level, home, chair,
                    chair.relative(side));
                if (site == null || !TavernSeating.clearStand(level, actor,
                        site.aisle()) || !TavernSeating.clearSeatedHeadroom(level,
                        actor, site)) {
                    continue;
                }
                if (++paths > MAX_PATHS) {
                    return new Search(null, null);
                }
                Path path = actor.getNavigation().createPath(
                    BlockPos.containing(TavernSeatMotion.staging(site)), 0);
                if (path == null || !path.canReach()) {
                    continue;
                }
                TavernSeatEntity seat = ModEntities.TAVERN_SEAT.get().create(level);
                if (seat == null) {
                    return new Search(null, null);
                }
                seat.reserveHome(actor, site);
                if (level.addFreshEntity(seat)) {
                    return new Search(seat, path);
                }
            }
        }
        return new Search(null, null);
    }
}

package com.hearthstead.settlement;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.UUID;

/** Physical furniture discovery and single-owner reservations. No food lives here. */
public final class TavernSeating {
    public static final int MAX_BUILDINGS = 32;
    public static final int MAX_SCAN_CELLS = 2048;
    public static final int MAX_PATHS = 12;
    public static final int VISIT_TICKS = 600;
    private static final String ANOTHER_FURNITURE = "another_furniture";
    /** 4.0.2's documented wood families; a namespaced look-alike never becomes a Tavern seat. */
    private static final Set<String> ANOTHER_FURNITURE_WOODS = Set.of(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
        "cherry", "bamboo", "crimson", "warped");
    public enum Furniture { VANILLA, ANOTHER_FURNITURE }
    public record SeatSite(UUID tavernId, BlockPos chair, BlockPos table,
                           Direction dinerFacing, BlockPos aisle, BlockPos hostApproach, Furniture furniture) {
        /** Existing saved seats and all vanilla fixtures retain their exact established frame. */
        public SeatSite(UUID tavernId, BlockPos chair, BlockPos table, Direction dinerFacing,
                        BlockPos aisle, BlockPos hostApproach) {
            this(tavernId, chair, table, dinerFacing, aisle, hostApproach, Furniture.VANILLA);
        }
        public Vec3 tabletopCenter(Level level) {
            if (isAnotherFurnitureTable(level.getBlockState(table))) {
                var shape = level.getBlockState(table).getShape(level, table);
                double top = shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
                return new Vec3(table.getX() + .5, table.getY() + top, table.getZ() + .5);
            }
            var shape = level.getBlockState(table.above()).getShape(level, table.above());
            double top = shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
            return new Vec3(table.getX() + .5, table.getY() + 1 + top, table.getZ() + .5);
        }
    }
    public record Search(TavernSeatEntity seat, Path path, int scannedCells, int attemptedPaths) {}
    /** A read-only offer. It owns no chair, food, bottle or navigation movement. */
    public record MealCandidate(SeatSite site, Path path, UUID host, BlockPos source) {}
    private TavernSeating() {}

    public static boolean hasTavernSeat(SettlerEntity actor) {
        return actor.getVehicle() instanceof TavernSeatEntity seat && seat.owns(actor);
    }
    public static Building currentTavern(SettlerEntity actor) {
        if (!(actor.getVehicle() instanceof TavernSeatEntity seat) || !seat.owns(actor)) return null;
        return building(visitSettlement(actor), seat.site().tavernId());
    }
    public static SeatSite currentSite(SettlerEntity actor) {
        return actor.getVehicle() instanceof TavernSeatEntity seat && seat.owns(actor) ? seat.site() : null;
    }
    /** False means every local dismount cell is obstructed; retry, never teleport through walls. */
    public static boolean leaveSeat(SettlerEntity actor) {
        if (!(actor.getVehicle() instanceof TavernSeatEntity seat)) return true;
        return seat.release();
    }
    /** Visiting does not bind a traveler or add a resident record. */
    public static Settlement visitSettlement(SettlerEntity actor) {
        if (!actor.isTraveler()) return actor.settlement();
        if (!(actor.level() instanceof ServerLevel level)
                || SettlementManager.waitingTravelerTavern(level, actor) == null) return null;
        return SettlementManager.byId(level, actor.getTargetSettlementId());
    }
    private static boolean allowedTavern(SettlerEntity actor, Building building) {
        if (!actor.isTraveler()) return true;
        return actor.level() instanceof ServerLevel level && building != null
            && building == SettlementManager.waitingTravelerTavern(level, actor);
    }
    public static boolean mayVisit(SettlerEntity actor) {
        Settlement s = visitSettlement(actor);
        if (s == null || (!actor.isTraveler() && s.record(actor.getUUID()) == null) || !actor.isAlive() || actor.isSleeping()
            || actor.getTarget() != null || actor.hurtTime > 0 || actor.isOnFire()
            || actor.getEnergy() < 20 || Summons.active(actor)
            || s.pendingRaid != null || s.alertUntilGameTime > actor.level().getGameTime()) return false;
        if (actor.isTraveler()) {
            Building tavern = SettlementManager.waitingTravelerTavern(
                (ServerLevel) actor.level(), actor);
            // The exact persisted visitor may occupy an otherwise valid
            // locked Tavern while it waits to purchase service. Residents
            // remain staff-gated below, and TavernHostService still authorizes
            // food/Coins independently.
            return usableTavern((ServerLevel) actor.level(), s, tavern)
                && (!SettlementManager.tavernVisitorNightDepartureDue(
                    (ServerLevel) actor.level(), s) || actor.hasMeal());
        }
        DayPhase phase = actor.dayPhase();
        if (!(phase.meal() || phase.social()) || Schedule.shouldSleep(s, actor, phase)) return false;
        if (actor.getProfession() == Profession.INNKEEPER) return false;
        return !actor.getProfession().martial()
            || (!Schedule.onWatch(s, actor, phase) && s.guardOrders.order(actor.getUUID()).isEmpty());
    }
    public static Building building(Settlement s, UUID id) {
        if (s == null || id == null) return null;
        for (Building b : s.buildings) if (id.equals(b.id)) return b;
        return null;
    }
    public static boolean staffed(ServerLevel level, Settlement s, Building b) {
        if (!usableTavern(level, s, b)) return false;
        for (UUID id : b.workers) {
            if (level.getEntity(id) instanceof SettlerEntity host && host.isAlive()
                && s.id.equals(host.getSettlementId()) && host.getProfession() == Profession.INNKEEPER
                && Employment.employerOf(s, id) == b) return true;
        }
        return false;
    }
    private static boolean usableTavern(ServerLevel level, Settlement s, Building b) {
        return s != null && b != null && b.valid && b.type == BuildingType.TAVERN
            && b.bounds != null && b.plaquePos != null && level.hasChunkAt(b.plaquePos)
            && level.getBlockState(b.plaquePos).getBlock() instanceof PlaqueBlock;
    }
    private static boolean seatServiceAllowed(ServerLevel level, SettlerEntity actor,
                                              Settlement settlement, Building tavern) {
        return usableTavern(level, settlement, tavern)
            && (actor.isTraveler() || staffed(level, settlement, tavern));
    }
    private static boolean anotherFurniture(BlockState state, String suffix) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !ANOTHER_FURNITURE.equals(id.getNamespace()) || !id.getPath().endsWith(suffix)) return false;
        String wood = id.getPath().substring(0, id.getPath().length() - suffix.length());
        return ANOTHER_FURNITURE_WOODS.contains(wood);
    }
    /** Optional-mod compatibility deliberately uses exact registered IDs, not class loading or a broad name match. */
    public static boolean isAnotherFurnitureChair(BlockState state) {
        return anotherFurniture(state, "_chair") && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && state.getFluidState().isEmpty() && !Boolean.TRUE.equals(state.getValues().entrySet().stream()
                .filter(entry -> "tucked".equals(entry.getKey().getName())).map(java.util.Map.Entry::getValue)
                .findFirst().orElse(Boolean.FALSE));
    }
    public static boolean isAnotherFurnitureTable(BlockState state) {
        return anotherFurniture(state, "_table") && state.getFluidState().isEmpty();
    }
    private static boolean vanillaChair(BlockState state) {
        return state.getBlock() instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.BOTTOM
            && state.getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT && !state.getValue(StairBlock.WATERLOGGED);
    }
    private static Direction dinerFacing(BlockState state) {
        if (vanillaChair(state)) return state.getValue(StairBlock.FACING).getOpposite();
        return isAnotherFurnitureChair(state) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
    }
    public static SeatSite site(Level level, Building b, BlockPos chair, BlockPos aisle) {
        if (b == null || !b.contains(chair) || !level.hasChunkAt(chair)) return null;
        var state = level.getBlockState(chair);
        Direction facing = dinerFacing(state);
        if (facing == null) return null;
        BlockPos table = chair.relative(facing);
        BlockPos host = table.relative(facing);
        if (!b.contains(table.above()) || !b.contains(host) || !b.contains(aisle)
            || !level.hasChunkAt(table) || !level.hasChunkAt(host)) return null;
        BlockState tableState = level.getBlockState(table);
        boolean vanillaFurniture = vanillaChair(state)
            && tableState.getBlock() instanceof FenceBlock
            && level.getBlockState(table.above()).getBlock() instanceof PressurePlateBlock;
        boolean anotherFurniture = isAnotherFurnitureChair(state) && isAnotherFurnitureTable(tableState);
        if (!vanillaFurniture && !anotherFurniture) return null;
        if (!aisle.equals(chair.relative(facing.getClockWise()))
            && !aisle.equals(chair.relative(facing.getCounterClockWise()))) return null;
        return new SeatSite(b.id, chair.immutable(), table, facing, aisle.immutable(), host,
            anotherFurniture ? Furniture.ANOTHER_FURNITURE : Furniture.VANILLA);
    }
    public static boolean clearStand(Level level, LivingEntity actor, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above()) || !level.hasChunkAt(feet.below())) return false;
        if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
            || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        Vec3 p = Vec3.atBottomCenterOf(feet);
        return level.noCollision(actor, actor.getDimensions(actor.getPose()).makeBoundingBox(p));
    }
    /** Check the actual head/upper torso above the stair's full-height backrest.
     * The seated origin deliberately overlaps chair and floor below this plane. */
    private static boolean clearSeatedHeadroom(Level level, SettlerEntity actor, SeatSite site) {
        Direction front = site.dinerFacing();
        Vec3 origin = new Vec3(site.chair().getX() + .5 + front.getStepX() * .25,
            site.chair().getY() - .25, site.chair().getZ() + .5 + front.getStepZ() * .25);
        AABB body = actor.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(origin);
        AABB upper = new AABB(body.minX, site.chair().getY() + 1.0, body.minZ,
            body.maxX, body.maxY, body.maxZ);
        if (!level.getWorldBorder().isWithinBounds(upper)) return false;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(upper.minX, upper.minY, upper.minZ),
                BlockPos.containing(upper.maxX, upper.maxY, upper.maxZ))) {
            if (!level.hasChunkAt(p) || !level.getFluidState(p).isEmpty()) return false;
        }
        return !level.getBlockCollisions(actor, upper).iterator().hasNext();
    }
    public static boolean valid(ServerLevel level, SettlerEntity actor, SeatSite site) {
        Building b = building(visitSettlement(actor), site.tavernId());
        return allowedTavern(actor, b) && mayVisit(actor)
            && seatServiceAllowed(level, actor, visitSettlement(actor), b)
            && site.equals(site(level, b, site.chair(), site.aisle()))
            && clearSeatedHeadroom(level, actor, site);
    }
    public static boolean occupied(ServerLevel level, BlockPos chair) {
        return !level.getEntitiesOfClass(TavernSeatEntity.class, new AABB(chair).inflate(.5),
            e -> !e.isRemoved() && e.site() != null && e.site().chair().equals(chair)).isEmpty();
    }
    private static boolean mealHostReady(SettlerEntity host, Building b) {
        return host != null && !host.isNoAi() && !host.hasMeal()
            && host.getMainHandItem().isEmpty() && host.getOffhandItem().isEmpty()
            && com.hearthstead.settlement.work.TavernHostService.authorizedHost(host, b.id)
            && !com.hearthstead.settlement.work.TavernHostService.hasSession(host);
    }
    /** Cheap live invalidation of one previously searched meal; no scan/path or reservation. */
    public static boolean mealCandidateValid(SettlerEntity actor, MealCandidate candidate, TavernSeatEntity owned) {
        if (!(actor.level() instanceof ServerLevel level) || candidate == null || !mayVisit(actor)) return false;
        SeatSite site = candidate.site();
        Building b = building(visitSettlement(actor), site.tavernId());
        if (b == null || !site.equals(site(level, b, site.chair(), site.aisle()))
            || !staffed(level, visitSettlement(actor), b) || !clearSeatedHeadroom(level, actor, site)
            || !clearStand(level, actor, site.aisle()) || !clearStand(level, actor, site.hostApproach())
            || b.tavernServingClaims.occupied(site.table())) return false;
        if (owned == null ? occupied(level, site.chair())
            : owned.isRemoved() || owned.isClosing() || !owned.owns(actor) || !site.equals(owned.site())) return false;
        return level.getEntity(candidate.host()) instanceof SettlerEntity host && mealHostReady(host, b)
            && com.hearthstead.settlement.work.TavernHostService.mealStockAvailable(host, b, candidate.source());
    }
    /** Eat-first goal scheduling may call this once per visit cooldown. Never creates a seat. */
    public static MealCandidate findReachableMeal(SettlerEntity actor) {
        if (actor.isTraveler()) return null; // Paid visitor meal ownership is a separate transaction.
        if (!(actor.level() instanceof ServerLevel level) || !mayVisit(actor) || actor.isPassenger()
            || !com.hearthstead.settlement.work.TavernGuestPayment.bagAllowsVisit(actor) || actor.getActivity() != com.hearthstead.entity.SettlerActivity.IDLE) return null;
        int cells = 0, paths = 0, buildings = 0;
        var guestProbe = new com.hearthstead.entity.path.RoadNavigation(actor, level);
        for (Building b : visitSettlement(actor).buildings) {
            if (++buildings > MAX_BUILDINGS || cells >= MAX_SCAN_CELLS) break;
            if (!allowedTavern(actor, b) || !staffed(level, visitSettlement(actor), b)) continue;
            SettlerEntity host = null;
            int workers = 0;
            for (UUID id : b.workers) {
                if (++workers > 64) break;
                if (level.getEntity(id) instanceof SettlerEntity candidate && mealHostReady(candidate, b)) {
                    host = candidate; break;
                }
            }
            if (host == null) continue;
            BlockPos source = null;
            for (BlockPos p : BlockPos.betweenClosed(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ(),
                    b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ())) {
                if (++cells > MAX_SCAN_CELLS) return null;
                if (com.hearthstead.settlement.work.TavernHostService.mealStockAvailable(host, b, p)) {
                    source = p.immutable(); break;
                }
            }
            if (source == null) continue;
            if (paths++ == MAX_PATHS) return null;
            if (!com.hearthstead.settlement.work.ContainerApproach.canPlanContact(level, host, source)) continue;
            var hostProbe = new com.hearthstead.entity.path.RoadNavigation(host, level);
            for (BlockPos p : BlockPos.betweenClosed(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ(),
                    b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ())) {
                if (++cells > MAX_SCAN_CELLS) return null;
                if (!level.hasChunkAt(p) || occupied(level, p)) continue;
                Direction front = dinerFacing(level.getBlockState(p));
                if (front == null) continue;
                for (Direction side : new Direction[]{front.getClockWise(), front.getCounterClockWise()}) {
                    SeatSite site = site(level, b, p, p.relative(side));
                    if (site == null || b.tavernServingClaims.occupied(site.table())
                        || !clearSeatedHeadroom(level, actor, site) || !clearStand(level, actor, site.aisle())
                        || !clearStand(level, actor, site.hostApproach())) continue;
                    if (paths++ == MAX_PATHS) return null;
                    Path path = guestProbe.createPath(BlockPos.containing(com.hearthstead.entity.TavernSeatMotion.staging(site)), 0);
                    if (path == null || !path.canReach()) continue;
                    if (paths++ == MAX_PATHS) return null;
                    Path hostPath = hostProbe.createPath(site.hostApproach(), 0);
                    if (hostPath != null && hostPath.canReach())
                        return new MealCandidate(site, path, host.getUUID(), source);
                }
            }
        }
        return null;
    }
    /** Called by the visit goal alone after a read-only offer wins goal arbitration. */
    public static TavernSeatEntity claimMeal(SettlerEntity actor, MealCandidate candidate) {
        if (!(actor.level() instanceof ServerLevel level) || actor.isPassenger() || !com.hearthstead.settlement.work.TavernGuestPayment.bagAllowsVisit(actor)
            || !mealCandidateValid(actor, candidate, null)) return null;
        TavernSeatEntity seat = ModEntities.TAVERN_SEAT.get().create(level);
        if (seat == null) return null;
        seat.reserve(actor, candidate.site());
        return level.addFreshEntity(seat) ? seat : null;
    }
    /** At most 2048 loaded cells and 12 real paths, once per goal cooldown. */
    public static Search reserveReachable(SettlerEntity actor) {
        if (!(actor.level() instanceof ServerLevel level) || !mayVisit(actor)
            || actor.isPassenger() || !com.hearthstead.settlement.work.TavernGuestPayment.bagAllowsVisit(actor)
            // mayVisit above proves that a traveler is the exact persisted
            // WAITING_ADMISSION guest at its locked Tavern. Let its
            // higher-priority visit goal preempt TravelerJoinGoal's return walk.
            || (actor.getActivity() != com.hearthstead.entity.SettlerActivity.IDLE
                && !(actor.isTraveler()
                    && actor.getActivity() == com.hearthstead.entity.SettlerActivity.TRAVELING)))
            return new Search(null, null, 0, 0);
        int cells = 0, paths = 0, buildings = 0;
        for (Building b : visitSettlement(actor).buildings) {
            if (++buildings > MAX_BUILDINGS) break;
            if (!allowedTavern(actor, b)
                || !seatServiceAllowed(level, actor, visitSettlement(actor), b)) continue;
            for (BlockPos p : BlockPos.betweenClosed(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ(),
                    b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ())) {
                if (cells == MAX_SCAN_CELLS || paths == MAX_PATHS) return new Search(null, null, cells, paths);
                cells++;
                if (!level.hasChunkAt(p) || occupied(level, p)) continue;
                Direction front = dinerFacing(level.getBlockState(p));
                if (front == null) continue;
                for (Direction side : new Direction[]{front.getClockWise(), front.getCounterClockWise()}) {
                    SeatSite site = site(level, b, p, p.relative(side));
                    if (site == null || !clearSeatedHeadroom(level, actor, site)
                        || !clearStand(level, actor, site.aisle())
                        || !clearStand(level, actor, site.hostApproach())) continue;
                    if (paths == MAX_PATHS) return new Search(null, null, cells, paths);
                    paths++;
                    Path path = actor.getNavigation().createPath(BlockPos.containing(com.hearthstead.entity.TavernSeatMotion.staging(site)), 0);
                    if (path == null || !path.canReach()) continue;
                    TavernSeatEntity seat = ModEntities.TAVERN_SEAT.get().create(level);
                    if (seat == null) return new Search(null, null, cells, paths);
                    seat.reserve(actor, site);
                    if (level.addFreshEntity(seat)) return new Search(seat, path, cells, paths);
                }
            }
        }
        return new Search(null, null, cells, paths);
    }
}

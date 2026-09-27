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
    /** Hearthstead-owned optional seat/table candidates (Codex, 26 Sep). Tags classify; geometry below proves. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> SETTLER_SEATS =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            com.hearthstead.Hearthstead.id("settler_seats"));
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> SETTLER_TABLES =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            com.hearthstead.Hearthstead.id("settler_tables"));
    /** 4.0.2 colour families of the optional stools and sofas. */
    private static final Set<String> ANOTHER_FURNITURE_COLOURS = Set.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
        "cyan", "purple", "blue", "brown", "green", "red", "black");
    /**
     * Seat geometry, measured from the Another Furniture 4.0.2 collision boxes (tavern lane):
     * {@code seatTop} is the block-relative seat surface the hip rests ON, {@code seatEdge} how far
     * the seat's solid box reaches toward the table from the block centre. VANILLA keeps the
     * established stair frame unchanged.
     */
    public enum Furniture {
        VANILLA(.5, .5),
        /** Chairs and benches: seat box 2..14 deep, 0..7 high. */
        ANOTHER_FURNITURE(7.0 / 16.0, .375),
        /** Sofas (straight pieces) and low stools: full-depth base, seat top 7/16. */
        ANOTHER_FURNITURE_DEEP(7.0 / 16.0, .5),
        /** Normal stools: full-depth top 3..8. */
        ANOTHER_FURNITURE_STOOL(8.0 / 16.0, .5);
        private final double seatTop, seatEdge;
        Furniture(double seatTop, double seatEdge) { this.seatTop = seatTop; this.seatEdge = seatEdge; }
        public double seatTop() { return seatTop; }
        public double seatEdge() { return seatEdge; }
        /** True for every optional Another Furniture seat (its table is an AF table). */
        public boolean another() { return this != VANILLA; }
        public static Furniture byId(int id) { return id >= 0 && id < values().length ? values()[id] : VANILLA; }
        /** Saved/synced form: the legacy boolean plus the kind id (absent in older saves). */
        public static Furniture read(net.minecraft.nbt.CompoundTag tag) {
            boolean another = tag.getBoolean("AnotherFurniture");
            if (tag.contains("FurnitureKind", 3)) {
                Furniture kind = byId(tag.getInt("FurnitureKind"));
                if (kind.another() == another) return kind;
            }
            return another ? ANOTHER_FURNITURE : VANILLA;
        }
        public void write(net.minecraft.nbt.CompoundTag tag) {
            tag.putBoolean("AnotherFurniture", another());
            tag.putInt("FurnitureKind", ordinal());
        }
    }
    public record SeatSite(UUID tavernId, BlockPos chair, BlockPos table,
                           Direction dinerFacing, BlockPos aisle, BlockPos hostApproach, Furniture furniture) {
        /** Existing saved seats and all vanilla fixtures retain their exact established frame. */
        public SeatSite(UUID tavernId, BlockPos chair, BlockPos table, Direction dinerFacing,
                        BlockPos aisle, BlockPos hostApproach) {
            this(tavernId, chair, table, dinerFacing, aisle, hostApproach, Furniture.VANILLA);
        }
        /** Legacy saves carried no host stand; they always meant the cell across the table. */
        public static BlockPos legacyHostApproach(BlockPos table, Direction dinerFacing) {
            return table.relative(dinerFacing);
        }
        /** Body yaw of a host standing on hostApproach and facing the served table cell. */
        public float hostYaw() {
            double dx = table.getX() - hostApproach.getX(), dz = table.getZ() - hostApproach.getZ();
            if (dx == 0 && dz == 0) return dinerFacing.getOpposite().toYRot();
            return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        }
        /** Unit horizontal vector from hostApproach toward the served table cell. */
        public Vec3 hostLook() {
            double dx = table.getX() - hostApproach.getX(), dz = table.getZ() - hostApproach.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length == 0) return new Vec3(-dinerFacing.getStepX(), 0, -dinerFacing.getStepZ());
            return new Vec3(dx / length, 0, dz / length);
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
    /** One search's bounded, read-only evidence; never shared between settlers. */
    public record MealSearch(MealCandidate candidate, String diagnostic) {}
    private static final class MealTrace {
        int buildings, stockCells, seatCells, paths, chairs, sites, clearSites, guestPaths, hostPaths;
        String reason = "no_candidate", source = "none", host = "none", lastChair = "none";
        String summary(SettlerEntity actor) {
            return "reason=" + reason + ",dayTime=" + actor.level().getDayTime()
                + ",activity=" + actor.getActivity() + ",hunger=" + actor.getHunger()
                + ",buildings=" + buildings + ",stockCells=" + stockCells
                + ",seatCells=" + seatCells + ",paths=" + paths + ",chairs=" + chairs
                + ",sites=" + sites + ",clearSites=" + clearSites
                + ",guestPaths=" + guestPaths + ",hostPaths=" + hostPaths
                + ",host=" + host + ",source=" + source + ",lastChair=" + lastChair;
        }
    }
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
        // Martial residents keep their watch/needs routine; they never visit the Tavern.
        if (actor.getProfession().martial()) return false;
        Settlement s = visitSettlement(actor);
        if (s == null || (!actor.isTraveler() && s.record(actor.getUUID()) == null) || !actor.isAlive() || actor.isSleeping()
            || actor.getTarget() != null || actor.hurtTime > 0 || actor.isOnFire()
            || actor.getEnergy() < 20 || Summons.active(actor)
            || s.pendingRaid != null || s.alertUntilGameTime > actor.level().getGameTime()) return false;
        // One evening window for residents and waiting paying guests. Hunger
        // recovery at the Hearth and daytime Innkeeper preparation are independent.
        // A guest who already owns a live restaurant order may finish it after
        // the evening window closes; the order's own deadline still applies.
        if (!TavernVisitSchedule.isOpen(actor.level().getDayTime())
            && !ownsLiveOrder(actor)) return false;
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
        if (Schedule.shouldSleep(s, actor, phase)) return false;
        if (actor.getProfession() == Profession.INNKEEPER) return false;
        return true;
    }
    private static boolean ownsLiveOrder(SettlerEntity actor) {
        var order = com.hearthstead.settlement.work.TavernHostService.orderForGuest(actor);
        return order != null && !order.isRemoved() && !order.isTerminalOrder()
            && actor.level().getGameTime() < order.orderExpiresAt();
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
        // A valid physical Tavern supports a social visit even when its Innkeeper
        // is away. Food and Ale service retain their separate authorized-host gate.
        return usableTavern(level, settlement, tavern);
    }
    private static boolean anotherFurniture(BlockState state, String suffix) {
        return anotherFurniture(state, suffix, ANOTHER_FURNITURE_WOODS);
    }
    private static boolean anotherFurniture(BlockState state, String suffix, Set<String> families) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !ANOTHER_FURNITURE.equals(id.getNamespace()) || !id.getPath().endsWith(suffix)) return false;
        String family = id.getPath().substring(0, id.getPath().length() - suffix.length());
        return families.contains(family);
    }
    private static String property(BlockState state, String name, String absent) {
        return state.getValues().entrySet().stream().filter(e -> name.equals(e.getKey().getName()))
            .map(e -> String.valueOf(e.getValue())).findFirst().orElse(absent);
    }
    /** Straight sofa pieces only: corner pieces seat their rider at 45 degrees. */
    public static boolean isAnotherFurnitureSofa(BlockState state) {
        if (!anotherFurniture(state, "_sofa", ANOTHER_FURNITURE_COLOURS)
            || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) || !state.getFluidState().isEmpty()) return false;
        String type = property(state, "type", "single");
        return type.equals("single") || type.equals("left") || type.equals("middle") || type.equals("right");
    }
    /** Normal and low stools. Tall (bar) stools need their own raised pose and are not seats here. */
    public static boolean isAnotherFurnitureStool(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && !id.getPath().endsWith("_tall_stool")
            && anotherFurniture(state, "_stool", ANOTHER_FURNITURE_COLOURS) && state.getFluidState().isEmpty();
    }
    /** Seat kind of an optional seat block, or null when it is not one. */
    public static Furniture anotherFurnitureKind(BlockState state) {
        if (isAnotherFurnitureChair(state) || isAnotherFurnitureBench(state)) return Furniture.ANOTHER_FURNITURE;
        if (isAnotherFurnitureSofa(state)) return Furniture.ANOTHER_FURNITURE_DEEP;
        if (isAnotherFurnitureStool(state))
            return "true".equals(property(state, "low", "false"))
                ? Furniture.ANOTHER_FURNITURE_DEEP : Furniture.ANOTHER_FURNITURE_STOOL;
        return null;
    }
    /** Optional tag gate: a block outside hearthstead:settler_seats is never an optional seat. */
    private static boolean taggedSeat(BlockState state) {
        return state.is(SETTLER_SEATS);
    }
    /** Optional-mod compatibility deliberately uses exact registered IDs, not class loading or a broad name match. */
    public static boolean isAnotherFurnitureChair(BlockState state) {
        return anotherFurniture(state, "_chair") && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && state.getFluidState().isEmpty() && !Boolean.TRUE.equals(state.getValues().entrySet().stream()
                .filter(entry -> "tucked".equals(entry.getKey().getName())).map(java.util.Map.Entry::getValue)
                .findFirst().orElse(Boolean.FALSE));
    }
    /** Another Furniture 4.0.2 benches use the existing SeatBlock frame. */
    public static boolean isAnotherFurnitureBench(BlockState state) {
        return anotherFurniture(state, "_bench") && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && state.getFluidState().isEmpty();
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
        return isAnotherFurnitureChair(state) || isAnotherFurnitureBench(state) || isAnotherFurnitureSofa(state)
            ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
    }
    /** Level-aware facing: a stool has no facing and faces its first (N, E, S, W) adjacent AF table. */
    private static Direction dinerFacing(Level level, BlockPos pos, BlockState state) {
        Direction facing = dinerFacing(state);
        if (facing != null || !isAnotherFurnitureStool(state) || !taggedSeat(state)) return facing;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos t = pos.relative(d);
            if (level.hasChunkAt(t) && isAnotherFurnitureTable(level.getBlockState(t))) return d;
        }
        return null;
    }
    public static SeatSite site(Level level, Building b, BlockPos chair, BlockPos aisle) {
        return site(level, b, chair, aisle, null);
    }
    /**
     * Deterministic seat geometry. The host may serve from any clear side of
     * the table: across first, then clockwise and counter-clockwise flanks,
     * then the far end/flanks of a two-block table, then the table's corners.
     * A still-admissible {@code preferredHost} (a saved or previously searched
     * seat) is retained so recomputation compares equal while it stays valid.
     */
    public static SeatSite site(Level level, Building b, BlockPos chair, BlockPos aisle, BlockPos preferredHost) {
        if (b == null || !b.contains(chair) || !level.hasChunkAt(chair)) return null;
        var state = level.getBlockState(chair);
        Direction facing = dinerFacing(level, chair, state);
        if (facing == null) return null;
        BlockPos table = chair.relative(facing);
        if (!b.contains(table.above()) || !b.contains(aisle) || !level.hasChunkAt(table)) return null;
        BlockState tableState = level.getBlockState(table);
        boolean vanillaFurniture = vanillaChair(state)
            && tableState.getBlock() instanceof FenceBlock
            && level.getBlockState(table.above()).getBlock() instanceof PressurePlateBlock;
        // Optional furniture is admitted through the Hearthstead tags AND the exact 4.0.2
        // block families (a tag can't filter blockstates such as tucked=true).
        Furniture kind = taggedSeat(state) ? anotherFurnitureKind(state) : null;
        boolean anotherFurniture = kind != null && tableState.is(SETTLER_TABLES) && isAnotherFurnitureTable(tableState);
        if (!vanillaFurniture && !anotherFurniture) return null;
        if (!aisle.equals(chair.relative(facing.getClockWise()))
            && !aisle.equals(chair.relative(facing.getCounterClockWise()))) return null;
        Furniture furniture = anotherFurniture ? kind : Furniture.VANILLA;
        var candidates = hostCandidates(level, b, chair, table, facing, aisle, furniture);
        BlockPos host = null;
        if (preferredHost != null && candidates.contains(preferredHost)) host = preferredHost.immutable();
        if (host == null) for (BlockPos cell : candidates) {
            if (blockStandable(level, cell)) { host = cell; break; }
        }
        // No standable side: keep the legacy across cell; callers' clearStand still rejects it.
        if (host == null) host = SeatSite.legacyHostApproach(table, facing);
        if (!b.contains(host) || !level.hasChunkAt(host)) return null;
        return new SeatSite(b.id, chair.immutable(), table.immutable(), facing, aisle.immutable(), host, furniture);
    }
    private static boolean tableCell(Level level, BlockPos p, Furniture furniture) {
        if (!level.hasChunkAt(p)) return false;
        BlockState s = level.getBlockState(p);
        return furniture.another() ? isAnotherFurnitureTable(s)
            : s.getBlock() instanceof FenceBlock && level.hasChunkAt(p.above())
                && level.getBlockState(p.above()).getBlock() instanceof PressurePlateBlock;
    }
    /** Ordered, loaded, in-bounds host stands around the served table; never the chair, aisle or a table cell. */
    public static java.util.List<BlockPos> hostCandidates(Level level, Building b, BlockPos chair, BlockPos table,
                                                          Direction facing, BlockPos aisle, Furniture furniture) {
        var ordered = new java.util.LinkedHashSet<BlockPos>();
        Direction cw = facing.getClockWise(), ccw = facing.getCounterClockWise();
        Direction[] sides = {facing, cw, ccw};
        for (Direction d : sides) ordered.add(table.relative(d).immutable());
        // Two-block tables: the far end, then the flanks of the other half.
        for (Direction d : sides) {
            BlockPos half = table.relative(d);
            if (!tableCell(level, half, furniture)) continue;
            ordered.add(half.relative(d).immutable());
            for (Direction flank : new Direction[]{d.getClockWise(), d.getCounterClockWise()})
                ordered.add(half.relative(flank).immutable());
        }
        // Corners of the served cell: across-side first, the diner's own row last.
        ordered.add(table.relative(facing).relative(cw).immutable());
        ordered.add(table.relative(facing).relative(ccw).immutable());
        ordered.add(chair.relative(cw).immutable());
        ordered.add(chair.relative(ccw).immutable());
        var result = new java.util.ArrayList<BlockPos>();
        for (BlockPos p : ordered) {
            if (p.equals(chair) || p.equals(aisle) || p.equals(table) || !b.contains(p)
                || !level.hasChunkAt(p) || tableCell(level, p, furniture)) continue;
            result.add(p);
        }
        return result;
    }
    /** Actor-free, deterministic standability used to choose hostApproach; clearStand still gates use. */
    private static boolean blockStandable(Level level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above()) || !level.hasChunkAt(feet.below())) return false;
        return level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
            && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }
    /** Recompute with the site's own host stand retained while it remains admissible. */
    public static boolean sameSite(Level level, Building b, SeatSite site) {
        return site != null && site.equals(site(level, b, site.chair(), site.aisle(), site.hostApproach()));
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
            && sameSite(level, b, site)
            && clearSeatedHeadroom(level, actor, site);
    }
    /**
     * Owner (26 Sep): never four patrons crammed round one block. A lone table cell seats at most
     * three; each cell of a longer table at most two (so a two-block table seats four).
     */
    public static boolean tableFull(ServerLevel level, SeatSite site) {
        BlockPos t = site.table();
        boolean longTable = false;
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (tableCell(level, t.relative(d), site.furniture())) longTable = true;
        int cap = longTable ? 2 : 3;
        return level.getEntitiesOfClass(TavernSeatEntity.class, new AABB(t).inflate(1.5),
            e -> !e.isRemoved() && e.site() != null && e.site().table().equals(t)).size() >= cap;
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
        if (b == null || !sameSite(level, b, site)
            || !staffed(level, visitSettlement(actor), b) || !clearSeatedHeadroom(level, actor, site)
            || !clearStand(level, actor, site.aisle()) || !clearStand(level, actor, site.hostApproach())
            || b.tavernServingClaims.occupied(site.table())) return false;
        if (owned == null ? occupied(level, site.chair())
            : owned.isRemoved() || owned.isClosing() || !owned.owns(actor) || !site.equals(owned.site())) return false;
        return level.getEntity(candidate.host()) instanceof SettlerEntity host && mealHostReady(host, b)
            && com.hearthstead.settlement.work.TavernHostService.orderStockAvailable(host, b, candidate.source());
    }
    /** Eat-first goal scheduling may call this once per visit cooldown. Never creates a seat. */
    public static MealCandidate findReachableMeal(SettlerEntity actor) {
        return findReachableMeal(actor, null);
    }
    public static MealSearch findReachableMealDiagnostic(SettlerEntity actor) {
        MealTrace trace = new MealTrace();
        MealCandidate candidate = findReachableMeal(actor, trace);
        return new MealSearch(candidate, trace.summary(actor));
    }
    private static MealCandidate findReachableMeal(SettlerEntity actor, MealTrace trace) {
        if (actor.isTraveler()) { if (trace != null) trace.reason = "traveler"; return null; }
        if (!(actor.level() instanceof ServerLevel level)) { if (trace != null) trace.reason = "not_server"; return null; }
        if (!mayVisit(actor)) { if (trace != null) trace.reason = "may_visit_false"; return null; }
        if (actor.isPassenger()) { if (trace != null) trace.reason = "passenger"; return null; }
        if (!com.hearthstead.settlement.work.TavernGuestPayment.bagAllowsVisit(actor)) {
            if (trace != null) trace.reason = "bag_blocks_visit"; return null;
        }
        // Evening posting walks every resident toward the Tavern as
        // TRAVELING; that walk must not veto the very meal it leads to.
        if (actor.getActivity() != com.hearthstead.entity.SettlerActivity.IDLE
            && actor.getActivity() != com.hearthstead.entity.SettlerActivity.TRAVELING) {
            if (trace != null) trace.reason = "activity_not_idle"; return null;
        }
        int stockCells = 0, seatCells = 0, paths = 0, buildings = 0;
        var guestProbe = new com.hearthstead.entity.path.RoadNavigation(actor, level);
        for (Building b : visitSettlement(actor).buildings) {
            if (++buildings > MAX_BUILDINGS || stockCells >= MAX_SCAN_CELLS) {
                if (trace != null) trace.reason = "building_or_stock_cap"; break;
            }
            if (trace != null) trace.buildings = buildings;
            if (!allowedTavern(actor, b) || !staffed(level, visitSettlement(actor), b)) {
                if (trace != null) trace.reason = "no_staffed_tavern"; continue;
            }
            SettlerEntity host = null;
            int workers = 0;
            for (UUID id : b.workers) {
                if (++workers > 64) break;
                if (level.getEntity(id) instanceof SettlerEntity candidate && mealHostReady(candidate, b)) {
                    host = candidate; break;
                }
            }
            if (host == null) { if (trace != null) trace.reason = "host_not_ready"; continue; }
            if (trace != null) trace.host = host.getUUID().toString();
            BlockPos source = null;
            for (BlockPos p : BlockPos.betweenClosed(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ(),
                    b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ())) {
                if (++stockCells > MAX_SCAN_CELLS) {
                    if (trace != null) { trace.stockCells = stockCells; trace.reason = "stock_cap"; }
                    return null;
                }
                if (trace != null) trace.stockCells = stockCells;
                if (com.hearthstead.settlement.work.TavernHostService.orderStockAvailable(host, b, p)) {
                    source = p.immutable(); break;
                }
            }
            if (source == null) { if (trace != null) trace.reason = "stock_unavailable"; continue; }
            if (trace != null) trace.source = source.toShortString();
            if (paths++ == MAX_PATHS) { if (trace != null) trace.reason = "path_cap_before_stock"; return null; }
            if (trace != null) trace.paths = paths;
            if (!com.hearthstead.settlement.work.ContainerApproach.canPlanContact(level, host, source)) {
                if (trace != null) trace.reason = "host_stock_contact_unreachable"; continue;
            }
            var hostProbe = new com.hearthstead.entity.path.RoadNavigation(host, level);
            for (BlockPos p : BlockPos.betweenClosed(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ(),
                    b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ())) {
                if (++seatCells > MAX_SCAN_CELLS) {
                    if (trace != null) { trace.seatCells = seatCells; trace.reason = "seat_cap"; }
                    return null;
                }
                if (trace != null) trace.seatCells = seatCells;
                if (!level.hasChunkAt(p) || occupied(level, p)) continue;
                Direction front = dinerFacing(level, p, level.getBlockState(p));
                if (front == null) continue;
                if (trace != null) { trace.chairs++; trace.lastChair = p.toShortString(); trace.reason = "chair_site_rejected"; }
                for (Direction side : new Direction[]{front.getClockWise(), front.getCounterClockWise()}) {
                    SeatSite site = site(level, b, p, p.relative(side));
                    if (site != null && trace != null) trace.sites++;
                    if (site == null || b.tavernServingClaims.occupied(site.table()) || tableFull(level, site)
                        || !clearSeatedHeadroom(level, actor, site) || !clearStand(level, actor, site.aisle())
                        || !clearStand(level, actor, site.hostApproach())) continue;
                    if (trace != null) { trace.clearSites++; trace.reason = "guest_path_unreachable"; }
                    if (paths++ == MAX_PATHS) { if (trace != null) trace.reason = "path_cap_before_guest"; return null; }
                    if (trace != null) trace.paths = paths;
                    Path path = guestProbe.createPath(BlockPos.containing(com.hearthstead.entity.TavernSeatMotion.staging(site)), 0);
                    if (path == null || !path.canReach()) continue;
                    if (trace != null) { trace.guestPaths++; trace.reason = "host_path_unreachable"; }
                    if (paths++ == MAX_PATHS) { if (trace != null) trace.reason = "path_cap_before_host"; return null; }
                    if (trace != null) trace.paths = paths;
                    Path hostPath = hostProbe.createPath(site.hostApproach(), 0);
                    if (hostPath != null && hostPath.canReach()) {
                        if (trace != null) { trace.hostPaths++; trace.reason = "candidate"; }
                        return new MealCandidate(site, path, host.getUUID(), source);
                    }
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
                Direction front = dinerFacing(level, p, level.getBlockState(p));
                if (front == null) continue;
                for (Direction side : new Direction[]{front.getClockWise(), front.getCounterClockWise()}) {
                    SeatSite site = site(level, b, p, p.relative(side));
                    if (site == null || tableFull(level, site) || !clearSeatedHeadroom(level, actor, site)
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

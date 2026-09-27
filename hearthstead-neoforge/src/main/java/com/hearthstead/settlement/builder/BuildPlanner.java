package com.hearthstead.settlement.builder;

import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a confirmed order (a blueprint placement, a drawn line, a dismantle)
 * into a frozen {@link BuildJob}, and tells the player honestly what it will
 * cost and what is in the way before anything is committed.
 *
 * <p>Read-only on the world: planning never changes a block. Every refusal
 * carries a lang key and the numbers behind it.
 */
public final class BuildPlanner {

    /** Deepest hole under a foundation the Builder fills before calling it too steep. */
    public static final int MAX_FILL_DEPTH = 4;
    /** How many player blocks a validation lists by position. */
    public static final int LISTED_PLAYER_BLOCKS = 32;
    /** Horizontal slack beyond the settlement radius (eaves, a wall outside the fence). */
    public static final int RADIUS_SLACK = 16;

    private BuildPlanner() {
    }

    /** What the player sees before confirming. */
    public record Validation(boolean ok, String reasonKey, List<String> reasonArgs,
                             int steps, int clears, int fills, List<BlockPos> playerBlocks,
                             int playerBlockCount, Map<Item, Integer> materials) {

        static Validation refused(String key, Object... args) {
            List<String> list = new ArrayList<>();
            for (Object a : args) {
                list.add(String.valueOf(a));
            }
            return new Validation(false, key, List.copyOf(list), 0, 0, 0, List.of(), 0, Map.of());
        }
    }

    public record Plan(@Nullable BuildJob job, Validation validation) {
    }

    // -------------------------------------------------------- blueprints ---

    /**
     * Plans a blueprint whose transformed footprint's minimum corner (template
     * y = 0) is {@code origin}.
     */
    public static Plan planBlueprint(ServerLevel level, Settlement settlement, Blueprint blueprint,
                                     BlockPos origin, int rotation, boolean mirror,
                                     @Nullable UUID owner) {
        BlueprintTransform t = new BlueprintTransform(rotation, mirror, blueprint.sizeX(), blueprint.sizeZ());
        Rotation rot = Rotation.values()[t.rotation()];
        Mirror mir = mirror ? Mirror.FRONT_BACK : Mirror.NONE;
        Map<BlockPos, BlockState> planned = new LinkedHashMap<>();
        Map<BlockPos, Integer> localY = new HashMap<>();
        for (Blueprint.Cell cell : blueprint.cells()) {
            BlockPos world = origin.offset(t.x(cell.x(), cell.z()), cell.y(), t.z(cell.x(), cell.z()));
            planned.put(world, cell.state().mirror(mir).rotate(rot));
            localY.put(world, cell.y());
        }
        Refusal refusal = checkArea(level, settlement, planned.keySet());
        if (refusal != null) {
            return new Plan(null, refusal.validation);
        }
        Collector c = new Collector(level);
        c.settlement = settlement;
        com.hearthstead.building.BuildingType requiredType = blueprint.meta().buildingType() == null ? null
            : com.hearthstead.building.BuildingType.byId(blueprint.meta().buildingType());
        if (requiredType != null) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (com.hearthstead.building.Requirement r : requiredType.requirements()) {
                ids.add(r.id());
            }
            c.requiredIds = ids;
        }
        // Fishery (Main, 26 Sep): its basin water is never drained.
        c.keepWater = "fishery".equals(blueprint.meta().buildingType());
        int ground = blueprint.meta().groundLevel();
        int eave = blueprint.eaveY();
        for (Map.Entry<BlockPos, BlockState> entry : planned.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            int y = localY.get(pos);
            c.cell(pos, state, y, ground, eave, planned);
        }
        c.pours();
        // FILL: soil (or cobble under stone) below the lowest planned layer.
        Map<Long, BlockState> bottom = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : planned.entrySet()) {
            if (localY.get(entry.getKey()) == 0 && !entry.getValue().isAir()) {
                bottom.put(columnKey(entry.getKey()), entry.getValue());
            }
        }
        for (Map.Entry<Long, BlockState> column : bottom.entrySet()) {
            int x = (int) (column.getKey() >> 32);
            int z = column.getKey().intValue();
            BlockPos below = new BlockPos(x, origin.getY() - 1, z);
            int depth = 0;
            while (!BuilderTerrain.supports(level, below)) {
                if (BuilderTerrain.fluid(level.getBlockState(below))) {
                    // Water under a foundation stays water (a boathouse, a
                    // jetty): the Builder never fills a lake with dirt.
                    break;
                }
                if (depth >= MAX_FILL_DEPTH) {
                    return new Plan(null, Validation.refused("hearthstead.builder.refuse.too_steep",
                        x, origin.getY(), z, MAX_FILL_DEPTH));
                }
                BlockState present = level.getBlockState(below);
                if (!BuilderTerrain.natural(present)) {
                    // A player block sits under the foundation: never fill
                    // around it, never remove it -- the ground is theirs.
                    break;
                }
                BuildSiteSavedData.Fill mode = BuildSiteSavedData.get(level).settings(settlement.id).fill();
                BlockState fill = switch (mode) {
                    case DIRT -> Blocks.DIRT.defaultBlockState();
                    case COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
                    default -> column.getValue().is(BlockTags.MINEABLE_WITH_PICKAXE)
                        ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                };
                c.fill(below, fill);
                below = below.below();
                depth++;
            }
        }
        // FINISH: fit the typed plan into the plaque the blueprint hangs.
        BlueprintMeta meta = blueprint.meta();
        String fitType = null;
        // A typed defense piece (the watchtowers, the stone gatehouse) registers
        // through its plaque too (BUILDER.md: 'no plaque unless it has a type').
        // SCENARIO lane 26 Sep: before this only BUILDING fitted, so a Builder-
        // built watchtower stood with an empty plaque and never registered.
        if ((meta.kind() == BlueprintMeta.Kind.BUILDING || meta.kind() == BlueprintMeta.Kind.DEFENSE)
            && meta.buildingType() != null
            && meta.plaquePos() != null) {
            int[] p = meta.plaquePos();
            BlockPos plaque = origin.offset(t.x(p[0], p[2]), p[1], t.z(p[0], p[2]));
            BlockState plaqueState = planned.get(plaque);
            if (plaqueState != null && plaqueState.is(com.hearthstead.registry.ModBlocks.PLAQUE.get())) {
                c.builder.add(plaque, plaqueState, BuildPhase.FINISH, BuildJob.F_FIT_PLAN, null, null);
                fitType = meta.buildingType();
            }
        }
        if (c.builder.full()) {
            return new Plan(null, Validation.refused("hearthstead.builder.refuse.too_big", BuildJob.MAX_STEPS));
        }
        BuildJob.Kind kind = meta.kind() == BlueprintMeta.Kind.BARRICADE
            ? BuildJob.Kind.BARRICADE : BuildJob.Kind.BLUEPRINT;
        BuildJob job = c.builder.build(UUID.randomUUID(), settlement.id, kind, blueprint.id(),
            meta.name() != null ? meta.name() : blueprint.id(), origin, t.rotation(), mirror,
            owner, level.getGameTime());
        job.fitPlanType = fitType;
        job.segment = meta.segment();
        return new Plan(job, c.validation(job));
    }

    // ------------------------------------------------------------- lines ---

    /** Line kinds the line tool offers. */
    public static final String PALISADE = "palisade";
    public static final String STONE = "stone";
    public static final String BARRICADE = "barricade";
    public static final int BARRICADE_MAX = 5;

    public static Plan planLine(ServerLevel level, Settlement settlement, BlockPos a, BlockPos b,
                                String kind, boolean gate, int gateOffset, @Nullable UUID owner) {
        boolean barricade = BARRICADE.equals(kind);
        DefenseLinePlanner.Line line = DefenseLinePlanner.plan(a.getX(), a.getZ(), b.getX(), b.getZ(),
            gate && !barricade, gateOffset, barricade ? 0 : 4);
        List<DefenseLinePlanner.Column> columns = line.columns();
        // Tech tree: Barricades doubles the line (WatchEffects.barricadeMax).
        int barricadeMax = com.hearthstead.settlement.techtree.effects.WatchEffects
            .barricadeMax(level, settlement, BARRICADE_MAX);
        if (barricade && columns.size() > barricadeMax) {
            columns = columns.subList(0, barricadeMax);
        }
        Direction.Axis along = line.dx() != 0 ? Direction.Axis.X : Direction.Axis.Z;
        Direction gateFacing = along == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        Map<BlockPos, BlockState> planned = new LinkedHashMap<>();
        Map<BlockPos, Integer> localY = new HashMap<>();
        List<BlockPos> gates = new ArrayList<>();
        int index = 0;
        int refY = Math.max(a.getY(), b.getY());
        for (DefenseLinePlanner.Column col : columns) {
            // W4a: the ground is found near where the player clicked, not by
            // the world heightmap -- a tree canopy, an overhang or anything
            // standing above the line would otherwise lift the wall into the air.
            Integer surface = groundNear(level, col.x(), col.z(), refY);
            if (surface == null) {
                index++;
                continue;
            }
            BlockPos base = new BlockPos(col.x(), surface, col.z());
            // Stand the column on real ground: step down through plants the
            // heightmap already ignores, never onto a fluid.
            if (BuilderTerrain.fluid(level.getBlockState(base.below()))) {
                index++;
                continue;
            }
            List<BlockState> stack = columnStack(kind, col, index, along, gateFacing);
            for (int y = 0; y < stack.size(); y++) {
                BlockPos pos = base.above(y);
                planned.put(pos, stack.get(y));
                localY.put(pos, y);
            }
            if (col.gate()) {
                gates.add(base);
            }
            index++;
        }
        if (planned.isEmpty()) {
            return new Plan(null, Validation.refused("hearthstead.builder.refuse.no_ground"));
        }
        Refusal refusal = checkArea(level, settlement, planned.keySet());
        if (refusal != null) {
            return new Plan(null, refusal.validation);
        }
        Collector c = new Collector(level);
        for (Map.Entry<BlockPos, BlockState> entry : planned.entrySet()) {
            c.cell(entry.getKey(), entry.getValue(), localY.get(entry.getKey()), 0,
                Integer.MAX_VALUE, planned);
        }
        BuildJob.Kind jobKind = barricade ? BuildJob.Kind.BARRICADE : BuildJob.Kind.DEFENSE_LINE;
        BuildJob job = c.builder.build(UUID.randomUUID(), settlement.id, jobKind, kind,
            kind + " (" + columns.size() + ")", a, 0, false, owner, level.getGameTime());
        job.segment = kind;
        job.gates.addAll(gates);
        return new Plan(job, c.validation(job));
    }

    /**
     * The first free cell standing on solid ground, searched from a little
     * above the clicked height down to a little below it; null when none.
     */
    @Nullable
    static Integer groundNear(ServerLevel level, int x, int z, int refY) {
        for (int y = refY + 3; y >= refY - 6; y--) {
            BlockPos cell = new BlockPos(x, y, z);
            if (!level.isLoaded(cell)) {
                return null;
            }
            BlockState here = level.getBlockState(cell);
            boolean free = here.isAir() || (here.canBeReplaced() && !BuilderTerrain.fluid(here));
            if (free && BuilderTerrain.supports(level, cell.below())) {
                return y;
            }
        }
        return null;
    }

    /** The blocks of one line column, bottom-up (air = must be clear). */
    static List<BlockState> columnStack(String kind, DefenseLinePlanner.Column col, int index,
                                        Direction.Axis along, Direction gateFacing) {
        BlockState air = Blocks.AIR.defaultBlockState();
        List<BlockState> stack = new ArrayList<>();
        switch (kind) {
            case STONE -> {
                BlockState wallTop = Blocks.STONE_BRICK_WALL.defaultBlockState();
                if (col.gate()) {
                    stack.add(Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, gateFacing));
                    stack.add(air);
                    stack.add(Blocks.STONE_BRICKS.defaultBlockState());
                    stack.add(wallTop);
                } else {
                    stack.add(Blocks.COBBLESTONE.defaultBlockState());
                    stack.add(Blocks.STONE_BRICKS.defaultBlockState());
                    stack.add(Blocks.STONE_BRICKS.defaultBlockState());
                    if (col.post() || (index & 1) == 0) {
                        stack.add(wallTop);
                    }
                }
            }
            case BARRICADE -> {
                stack.add(Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, along));
                stack.add(Blocks.OAK_FENCE.defaultBlockState());
            }
            default -> { // palisade
                BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
                if (col.gate()) {
                    stack.add(Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, gateFacing));
                    stack.add(air);
                    stack.add(Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, along));
                } else {
                    stack.add(log);
                    stack.add(log);
                    stack.add(log);
                    stack.add(col.post() ? log : Blocks.SPRUCE_FENCE.defaultBlockState());
                }
            }
        }
        return stack;
    }

    // --------------------------------------------------------- dismantle ---

    /**
     * Reverses a job: removes exactly the blocks it placed with an item, top
     * down, refunding each one's own cost to the hut.
     */
    @Nullable
    public static BuildJob planDismantle(ServerLevel level, BuildJob source, @Nullable UUID owner) {
        BuildJob.Builder builder = new BuildJob.Builder();
        for (int i = 0; i < source.size(); i++) {
            if (!source.isPlaced(i) || source.hasFlag(i, BuildJob.F_FIT_PLAN)) {
                continue;
            }
            builder.add(source.pos(i), source.state(i), BuildPhase.DISMANTLE, 0,
                source.companionPos(i), source.companionState(i));
        }
        if (builder.size() == 0) {
            return null;
        }
        BuildJob job = builder.build(UUID.randomUUID(), source.settlementId, BuildJob.Kind.DISMANTLE,
            source.sourceId, source.label, source.anchor, source.rotation, source.mirror, owner,
            level.getGameTime());
        job.targetId = source.id;
        return job;
    }

    // -------------------------------------------------------- deconstruct ---

    /**
     * Deconstruct any registered building (MineColonies' "remove"): every
     * block of its room shell and contents that a player could build -- not
     * the ground -- comes down top-down, each refunded as its own items to the
     * Builder's Hut, container contents too. Player-ordered only. The hut
     * itself and the Banner are never taken down this way, and blocks with a
     * non-container block entity (other than the plaque) are left alone.
     */
    public static Plan planDeconstruct(ServerLevel level, Settlement settlement,
                                       com.hearthstead.settlement.Building building, @Nullable UUID owner) {
        if (building == null || building.bounds == null || !building.valid) {
            return new Plan(null, Validation.refused("hearthstead.builder.site.missing"));
        }
        if (building.type == com.hearthstead.building.BuildingType.BUILDERS_HUT) {
            return new Plan(null, Validation.refused("hearthstead.builder.refuse.own_hut"));
        }
        BuildJob.Builder builder = new BuildJob.Builder();
        Map<net.minecraft.world.item.Item, Integer> refund = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(building.bounds.minX(), building.bounds.minY(),
            building.bounds.minZ(), building.bounds.maxX(), building.bounds.maxY(), building.bounds.maxZ())) {
            if (!level.isLoaded(pos)) {
                return new Plan(null, Validation.refused("hearthstead.builder.refuse.unloaded"));
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || BuilderTerrain.natural(state) || companionHalf(state)
                || !BuilderMaterials.buildable(state)
                || state.getBlock() instanceof com.hearthstead.block.HearthBlock) {
                continue;
            }
            var be = level.getBlockEntity(pos);
            if (be != null && !(be instanceof net.minecraft.world.Container)
                && !(be instanceof com.hearthstead.block.PlaqueBlockEntity)) {
                continue;
            }
            Map.Entry<BlockPos, BlockState> companion = companionOf(pos, state);
            if (!builder.add(pos.immutable(), state, BuildPhase.DISMANTLE, 0,
                companion == null ? null : companion.getKey(), companion == null ? null : companion.getValue())) {
                return new Plan(null, Validation.refused("hearthstead.builder.refuse.too_big", BuildJob.MAX_STEPS));
            }
            for (BuilderMaterials.ItemCount c : BuilderMaterials.costsOf(state)) {
                if (c.buildable()) {
                    refund.merge(c.item(), c.count(), Integer::sum);
                }
            }
        }
        if (builder.size() == 0) {
            return new Plan(null, Validation.refused("hearthstead.builder.refuse.nothing"));
        }
        BuildJob job = builder.build(UUID.randomUUID(), settlement.id, BuildJob.Kind.DISMANTLE,
            building.id.toString(), "Deconstruct: " + building.type.id(), building.plaquePos, 0, false, owner,
            level.getGameTime());
        job.targetId = building.id;
        return new Plan(job, new Validation(true, "", List.of(), job.size(), 0, 0, List.of(), 0, refund));
    }

    // ------------------------------------------------------------ shared ---

    private record Refusal(Validation validation) {
    }

    @Nullable
    private static Refusal checkArea(ServerLevel level, Settlement settlement, java.util.Collection<BlockPos> cells) {
        if (settlement.center == null) {
            return new Refusal(Validation.refused("hearthstead.builder.refuse.no_settlement"));
        }
        long reach = (long) (settlement.radius + RADIUS_SLACK) * (settlement.radius + RADIUS_SLACK);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : cells) {
            long dx = pos.getX() - settlement.center.getX();
            long dz = pos.getZ() - settlement.center.getZ();
            if (dx * dx + dz * dz > reach) {
                return new Refusal(Validation.refused("hearthstead.builder.refuse.outside",
                    pos.getX(), pos.getY(), pos.getZ()));
            }
            if (!level.isLoaded(pos)) {
                return new Refusal(Validation.refused("hearthstead.builder.refuse.unloaded"));
            }
            if (level.isOutsideBuildHeight(pos)) {
                return new Refusal(Validation.refused("hearthstead.builder.refuse.height"));
            }
            // Owner (mixed player/Builder buildings): a new plan never cuts
            // into a registered building -- refused with its name, never a
            // silent demolition (deconstruct it first, or choose another spot).
            for (com.hearthstead.settlement.Building building : settlement.buildings) {
                if (building.valid && building.bounds != null && building.bounds.isInside(pos)) {
                    return new Refusal(Validation.refused("hearthstead.builder.refuse.overlaps_building",
                        building.type.id().replace('_', ' ')));
                }
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        BoundingBox box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        for (BuildJob other : BuildSiteSavedData.get(level).activeJobs(settlement.id)) {
            if (other.kind != BuildJob.Kind.DISMANTLE && other.bounds.intersects(box)) {
                return new Refusal(Validation.refused("hearthstead.builder.refuse.overlaps", other.label));
            }
        }
        return null;
    }

    private static long columnKey(BlockPos pos) {
        return ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }

    /** True for the half of a two-block thing whose partner step places it. */
    static boolean companionHalf(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        }
        if (state.getBlock() instanceof BedBlock) {
            return state.getValue(BedBlock.PART) == BedPart.HEAD;
        }
        if (state.getBlock() instanceof DoublePlantBlock) {
            return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
        }
        return false;
    }

    /** The partner cell and state a lower door / bed foot / lower plant places with it. */
    @Nullable
    static Map.Entry<BlockPos, BlockState> companionOf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            return Map.entry(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return Map.entry(pos.relative(state.getValue(BedBlock.FACING)),
                state.setValue(BedBlock.PART, BedPart.HEAD));
        }
        if (state.getBlock() instanceof DoublePlantBlock
            && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
            return Map.entry(pos.above(), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        }
        return null;
    }

    /** Collects steps and the numbers the validation reports. */
    private static final class Collector {
        final ServerLevel level;
        final BuildJob.Builder builder = new BuildJob.Builder();
        int clears;
        int fills;
        int playerBlocks;
        boolean keepWater;
        final List<BlockPos> listed = new ArrayList<>();
        /** Planned water sources still dry, poured after the walls (see {@link #pours}). */
        final List<BlockPos> water = new ArrayList<>();

        /**
         * Water cells become pour steps: per connected body the first two
         * (bottom-up) cost a water bucket each and go in with the interior;
         * the rest are scooped from the source those make and follow in the
         * next phase, so a basin is full before the plaque is fitted.
         */
        void pours() {
            BlockState source = Blocks.WATER.defaultBlockState();
            for (List<BlockPos> body : BuilderMaterials.waterBodies(water)) {
                for (int k = 0; k < body.size(); k++) {
                    boolean paid = k < 2;
                    builder.add(body.get(k), source, paid ? BuildPhase.INTERIOR : BuildPhase.REDSTONE,
                        (byte) (BuildJob.F_POUR | (paid ? BuildJob.F_POUR_PAID : 0)), null, null);
                }
            }
            water.clear();
        }

        Collector(ServerLevel level) {
            this.level = level;
        }

        /** The settlement the plan is for (supply checks); null for unit use. */
        @Nullable
        Settlement settlement;
        /** Requirement ids of the blueprint's building type (never skipped as decoration). */
        java.util.Set<String> requiredIds = java.util.Set.of();

        private static BlockState plainState(BlockState state) {
            String id = Blueprint.idOf(state);
            String plain = VillageSupply.plainVariant(id);
            if (plain == null || plain.equals(id)) {
                return state;
            }
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(plain);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
                return state;
            }
            BlockState out = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl).defaultBlockState();
            for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                if (out.hasProperty(prop)) {
                    out = copy(out, state, prop);
                }
            }
            return out;
        }

        private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from,
                                                                 net.minecraft.world.level.block.state.properties.Property<T> prop) {
            return to.setValue(prop, from.getValue(prop));
        }

        void cell(BlockPos pos, BlockState state, int localY, int ground, int eave,
                  Map<BlockPos, BlockState> planned) {
            // Owner (26 Sep): "dropp pynt per nå". Pure decoration nobody in
            // the village can make is left out; coloured beds, wool, carpets
            // and banners become white -- the Builder pays white and sets white.
            if (!state.isAir() && settlement != null) {
                state = plainState(state);
                // EVERY cost counts, not only the first: a potted tulip costs a
                // pot AND a tulip, and nobody grows tulips (W38 barracks 555/567
                // waited on one forever). The whole piece is left out.
                boolean skip = false;
                for (BuilderMaterials.ItemCount cost : BuilderMaterials.costsOf(state)) {
                    if (VillageSupply.shouldSkip(level, settlement, cost.item())) {
                        skip = true;
                        break;
                    }
                }
                if (skip && !requiredIds.contains(Blueprint.idOf(state).replace("minecraft:", ""))) {
                    return; // never a piece the building type needs to register (W34 brewery)
                }
            }
            BlockState present = level.getBlockState(pos);
            if (state.isAir()) {
                clearIfNeeded(pos, present, BuildPhase.CLEAR);
                return;
            }
            if (companionHalf(state)) {
                // Placed by its partner. A correct half already standing is
                // kept (Codex T3b: never clear half of a finished door).
                if (BuilderMaterials.compare(state, present) != MaterialRules.Match.SAME) {
                    clearIfNeeded(pos, present, BuildPhase.CLEAR);
                }
                return;
            }
            String plannedId = Blueprint.idOf(state);
            MaterialRules.Match match = BuilderMaterials.compare(state, present);
            Map.Entry<BlockPos, BlockState> companion = companionOf(pos, state);
            boolean pairStands = companion == null || BuilderMaterials.compare(companion.getValue(),
                level.getBlockState(companion.getKey())) == MaterialRules.Match.SAME;
            if (match == MaterialRules.Match.SAME && pairStands) {
                return; // already stands, whole: never replaced, never charged
            }
            if (BuilderMaterials.waterSource(state)) {
                if (BuilderMaterials.waterSource(present)) {
                    return; // the basin already holds water here
                }
                if (!present.isAir() && !BuilderTerrain.fluid(present)) {
                    clearIfNeeded(pos, present, BuildPhase.CLEAR);
                }
                water.add(pos.immutable());
                return;
            }
            if (!BuilderMaterials.buildable(state)) {
                return; // a technical block with no item cannot be built honestly
            }
            // A correct block (or a single block only turned the wrong way)
            // is never cleared: the executor completes the pair or turns it
            // for free. Anything else must first make room.
            boolean keep = match == MaterialRules.Match.SAME
                || (match == MaterialRules.Match.REORIENT && companion == null);
            if (!keep) {
                clearIfNeeded(pos, present, BuildPhase.CLEAR);
            }
            BuildPhase phase = BuildOrder.classify(plannedId, localY, ground, eave);
            builder.add(pos, state, phase, 0,
                companion == null ? null : companion.getKey(),
                companion == null ? null : companion.getValue());
        }

        void clearIfNeeded(BlockPos pos, BlockState present, BuildPhase phase) {
            if (present.isAir()) {
                return;
            }
            if (BuilderTerrain.fluid(present)) {
                if (keepWater) {
                    return; // a fishery keeps every drop of its water
                }
                // Drained after the walls stand, so the sea cannot pour back.
                builder.add(pos, Blocks.AIR.defaultBlockState(), BuildPhase.INTERIOR,
                    BuildJob.F_DRAIN, null, null);
                clears++;
                return;
            }
            if (BuilderTerrain.natural(present)) {
                builder.add(pos, Blocks.AIR.defaultBlockState(), phase, BuildJob.F_CLEAR, null, null);
                clears++;
                return;
            }
            playerBlocks++;
            if (listed.size() < LISTED_PLAYER_BLOCKS) {
                listed.add(pos.immutable());
            }
            builder.add(pos, Blocks.AIR.defaultBlockState(), phase,
                BuildJob.F_CLEAR | BuildJob.F_PLAYER, null, null);
        }

        void fill(BlockPos pos, BlockState fill) {
            BlockState present = level.getBlockState(pos);
            if (!present.isAir() && !BuilderTerrain.fluid(present) && !present.canBeReplaced()) {
                clearIfNeeded(pos, present, BuildPhase.CLEAR);
            }
            builder.add(pos, fill, BuildPhase.FILL, BuildJob.F_FILL, null, null);
            fills++;
        }

        Validation validation(BuildJob job) {
            return new Validation(true, "", List.of(), job.size(), clears, fills,
                List.copyOf(listed), playerBlocks, BuilderMaterials.total(job));
        }
    }
}

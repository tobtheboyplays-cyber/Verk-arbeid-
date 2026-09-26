package com.hearthstead.settlement.builder;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Match town style" (owner: "make the blocks match what we have used most in
 * our town"). Scans the blocks a settlement's people actually built with --
 * inside the bounds of its registered buildings, so terrain and trees are
 * never mistaken for style -- buckets them by role (see
 * {@link TownPaletteRules}) and re-skins a blueprint role by role.
 *
 * <p>Installed into {@link BlueprintStyles} at mod construction; the Builder's
 * Plan calls it for the preview, validation and placement, so the ghost, the
 * materials list and the job all use the same re-skinned cells. Structural
 * roles only ever receive full, non-falling blocks without a block entity; a
 * role the town has no valid block for keeps the authored block.
 */
public final class TownStyle {

    /** Re-scan a town at most this often (ticks). */
    public static final long CACHE_TICKS = 1200L;
    /** Hard budget: never read more cells than this per scan. */
    public static final int MAX_CELLS = 200_000;

    /** A town's palette: the chosen block id per role (null = keep authored). */
    public record Palette(Map<TownPaletteRules.Role, String> chosen, Map<TownPaletteRules.Role, Map<String, Integer>> counts) {
        public String get(TownPaletteRules.Role role) {
            return chosen.get(role);
        }
    }

    private record Cached(long time, Palette palette) {
    }

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private TownStyle() {
    }

    /** Called once from the mod constructor. */
    public static void install() {
        BlueprintStyles.install(TownStyle::style);
    }

    public static void invalidate(UUID settlementId) {
        CACHE.remove(settlementId);
    }

    public static Palette palette(ServerLevel level, Settlement settlement) {
        long now = level.getGameTime();
        Cached c = CACHE.get(settlement.id);
        if (c != null && now - c.time() < CACHE_TICKS) {
            return c.palette();
        }
        Palette p = scan(level, settlement);
        CACHE.put(settlement.id, new Cached(now, p));
        return p;
    }

    /** Reads every registered building's bounds and tallies building materials by role. */
    public static Palette scan(ServerLevel level, Settlement settlement) {
        Map<TownPaletteRules.Role, Map<String, Integer>> counts = new EnumMap<>(TownPaletteRules.Role.class);
        for (TownPaletteRules.Role r : TownPaletteRules.Role.values()) {
            counts.put(r, new HashMap<>());
        }
        int read = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        outer:
        for (Building b : new ArrayList<>(settlement.buildings)) {
            BoundingBox box = b.bounds;
            if (box == null) {
                continue;
            }
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        if (++read > MAX_CELLS) {
                            break outer;
                        }
                        p.set(x, y, z);
                        if (!seen.add(p.asLong()) || !level.hasChunkAt(p)) {
                            continue;
                        }
                        BlockState s = level.getBlockState(p);
                        if (s.isAir()) {
                            continue;
                        }
                        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                        TownPaletteRules.Role role = TownPaletteRules.roleOfPlaced(id, y == box.minY());
                        if (role == null) {
                            continue;
                        }
                        if (role == TownPaletteRules.Role.FRAME && inTree(level, p)) {
                            continue;
                        }
                        counts.get(role).merge(id, 1, Integer::sum);
                    }
                }
            }
        }
        Map<TownPaletteRules.Role, String> chosen = new EnumMap<>(TownPaletteRules.Role.class);
        for (TownPaletteRules.Role role : TownPaletteRules.Role.values()) {
            String top = TownPaletteRules.top(counts.get(role), id -> validFor(role, id));
            if (top != null) {
                chosen.put(role, top);
            }
        }
        return new Palette(chosen, counts);
    }

    /** A log with natural leaves beside it is a tree, not a frame. */
    private static boolean inTree(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(pos.relative(d));
            if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    /** Structure safety: the rules for which town block may fill a role. */
    static boolean validFor(TownPaletteRules.Role role, String id) {
        Block block = block(id);
        if (block == null) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        return switch (role) {
            case WALL, FLOOR, FRAME -> !(block instanceof FallingBlock) && !state.hasBlockEntity()
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            case ROOF -> {
                String fam = TownPaletteRules.stairFamily(id);
                String ns = id.substring(0, id.indexOf(':') + 1);
                yield fam != null && block(ns + fam + "_stairs") instanceof StairBlock
                    && block(ns + fam + "_slab") instanceof SlabBlock;
            }
            case TRIM -> TownPaletteRules.woodFamily(id) != null;
            case GLASS -> true;
        };
    }

    private static Block block(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return null;
        }
        Block b = BuiltInRegistries.BLOCK.get(rl);
        return b == Blocks.AIR ? null : b;
    }

    /** The Styler: same cells and size, only re-skinned states. */
    public static Blueprint style(ServerLevel level, Settlement settlement, Blueprint blueprint) {
        return apply(palette(level, settlement), blueprint);
    }

    /** Pure-ish re-skin with a given palette (GameTests call this directly). */
    public static Blueprint apply(Palette palette, Blueprint blueprint) {
        int ground = blueprint.meta().groundLevel();
        int eave = blueprint.eaveY();
        List<Blueprint.Cell> out = new ArrayList<>(blueprint.cells().size());
        boolean changed = false;
        for (Blueprint.Cell cell : blueprint.cells()) {
            BlockState state = cell.state();
            String id = Blueprint.idOf(state);
            TownPaletteRules.Role role = TownPaletteRules.roleOfCell(id, cell.y(), ground, eave);
            BlockState next = state;
            if (role != null) {
                String target = TownPaletteRules.substitute(role, id, palette.get(role));
                Block tb = target == null ? null : block(target);
                if (tb != null && tb != state.getBlock()
                    && (role == TownPaletteRules.Role.ROOF || role == TownPaletteRules.Role.TRIM
                        || role == TownPaletteRules.Role.GLASS || validFor(role, target))) {
                    next = copyProperties(state, tb.defaultBlockState());
                }
            }
            changed |= next != state;
            out.add(new Blueprint.Cell(cell.x(), cell.y(), cell.z(), next));
        }
        if (!changed) {
            return blueprint;
        }
        return new Blueprint(blueprint.meta(), blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(), out);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState copyProperties(BlockState from, BlockState to) {
        for (Property prop : from.getProperties()) {
            if (to.hasProperty(prop)) {
                to = to.setValue(prop, from.getValue(prop));
            }
        }
        return to;
    }
}

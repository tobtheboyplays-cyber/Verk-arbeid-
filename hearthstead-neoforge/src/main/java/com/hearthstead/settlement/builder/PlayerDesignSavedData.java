package com.hearthstead.settlement.builder;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Our Designs" (MineColonies' scan tool, owner request): buildings the
 * players built by hand, surveyed with the Survey Rod and saved per world so
 * the Builder can raise them again anywhere. Stored in the overworld's data
 * folder, so a dedicated server keeps them and every co-op player shares
 * them. Only block states are captured -- never container contents, never
 * entities. A hung plaque with a fitted plan makes the design a registering
 * building (its type and plaque position are remembered).
 */
public final class PlayerDesignSavedData extends SavedData {

    private static final String DATA_NAME = "hearthstead_player_designs";
    public static final int MAX_DESIGNS = 64;
    public static final int MAX_CELLS = 8192;
    public static final int MAX_EXTENT = 32;

    /** One saved design. Cells are packed x | y << 8 | z << 16 with a palette index. */
    public record Design(String id, String name, @Nullable UUID owner, int sizeX, int sizeY, int sizeZ,
                         List<BlockState> palette, int[] cells, @Nullable String buildingType,
                         @Nullable int[] plaquePos, @Nullable String plaqueFacing, long savedAt) {
    }

    private final Map<String, Design> designs = new LinkedHashMap<>();
    private int nextId = 1;
    private int revision;

    private static final Factory<PlayerDesignSavedData> FACTORY =
        new Factory<>(PlayerDesignSavedData::new, PlayerDesignSavedData::load, null);

    public static PlayerDesignSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public int revision() {
        return revision;
    }

    public List<Design> all() {
        return List.copyOf(designs.values());
    }

    @Nullable
    public Design get(String id) {
        return designs.get(id);
    }

    public boolean remove(String id) {
        if (designs.remove(id) != null) {
            revision++;
            setDirty();
            return true;
        }
        return false;
    }

    /** Why a capture is refused (lang key), or null. */
    @Nullable
    public static String refusal(BlockPos a, BlockPos b) {
        int sx = Math.abs(a.getX() - b.getX()) + 1;
        int sy = Math.abs(a.getY() - b.getY()) + 1;
        int sz = Math.abs(a.getZ() - b.getZ()) + 1;
        if (sx > MAX_EXTENT || sy > MAX_EXTENT || sz > MAX_EXTENT) {
            return "hearthstead.builder.design.too_big";
        }
        if (sx * sy * sz > MAX_CELLS) {
            return "hearthstead.builder.design.too_big";
        }
        return null;
    }

    /**
     * Captures the box between two corners. Air is kept as planned-empty;
     * blocks the Builder could never place (no item) are left out.
     */
    @Nullable
    public Design capture(ServerLevel level, BlockPos a, BlockPos b, String name, @Nullable UUID owner) {
        if (refusal(a, b) != null || designs.size() >= MAX_DESIGNS) {
            return null;
        }
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
            Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
            Math.max(a.getZ(), b.getZ()));
        int sx = max.getX() - min.getX() + 1;
        int sy = max.getY() - min.getY() + 1;
        int sz = max.getZ() - min.getZ() + 1;
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        List<Integer> packed = new ArrayList<>();
        String type = null;
        int[] plaque = null;
        String facing = null;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (BuilderTerrain.fluid(state) || (!state.isAir() && !BuilderMaterials.buildable(state))) {
                continue; // water, bedrock, technical blocks: never part of a design
            }
            if (state.is(ModBlocks.PLAQUE.get())) {
                // Saved blank: the Builder hangs it and fits the plan itself.
                if (level.getBlockEntity(pos) instanceof PlaqueBlockEntity be
                    && be.state() != PlaqueState.EMPTY && type == null) {
                    type = be.type().id();
                    plaque = new int[]{pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ()};
                    facing = state.getValue(PlaqueBlock.FACING).getName();
                }
                state = ModBlocks.PLAQUE.get().defaultBlockState()
                    .setValue(PlaqueBlock.FACING, state.getValue(PlaqueBlock.FACING));
            }
            Integer i = index.get(state);
            if (i == null) {
                i = palette.size();
                palette.add(state);
                index.put(state, i);
            }
            packed.add((pos.getX() - min.getX()) | (pos.getY() - min.getY()) << 8 | (pos.getZ() - min.getZ()) << 16);
            packed.add(i);
        }
        int[] cells = new int[packed.size()];
        for (int k = 0; k < cells.length; k++) {
            cells[k] = packed.get(k);
        }
        String id = "design_" + nextId++;
        String clean = name == null || name.isBlank() ? id : name.strip();
        if (clean.length() > 40) {
            clean = clean.substring(0, 40);
        }
        Design design = new Design(id, clean, owner, sx, sy, sz, List.copyOf(palette), cells, type, plaque, facing,
            level.getGameTime());
        designs.put(id, design);
        revision++;
        setDirty();
        return design;
    }

    /** The design as a {@link Blueprint} the planner can use. */
    public static Blueprint toBlueprint(Design d) {
        List<Blueprint.Cell> cells = new ArrayList<>(d.cells().length / 2);
        for (int k = 0; k + 1 < d.cells().length; k += 2) {
            int p = d.cells()[k];
            int s = d.cells()[k + 1];
            if (s < 0 || s >= d.palette().size()) {
                continue;
            }
            cells.add(new Blueprint.Cell(p & 0xFF, (p >> 8) & 0xFF, (p >> 16) & 0xFF, d.palette().get(s)));
        }
        BlueprintMeta.Kind kind = d.buildingType() != null ? BlueprintMeta.Kind.BUILDING : BlueprintMeta.Kind.DECORATION;
        BlueprintMeta meta = new BlueprintMeta(d.id(), d.name(), "our_designs", kind, d.buildingType(), "our_designs",
            BuilderUnlocks.BUILDERS_HUT, 0, d.plaquePos(), d.plaqueFacing(), null, "", List.of(), List.of(), null);
        return new Blueprint(meta, d.sizeX(), d.sizeY(), d.sizeZ(), cells);
    }

    // -------------------------------------------------------- persistence ---

    public static PlayerDesignSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerDesignSavedData data = new PlayerDesignSavedData();
        if (tag == null || registries == null) {
            return data;
        }
        HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        data.nextId = Math.max(1, tag.getInt("NextId"));
        ListTag list = tag.getList("Designs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && data.designs.size() < MAX_DESIGNS; i++) {
            CompoundTag d = list.getCompound(i);
            try {
                ListTag pal = d.getList("Palette", Tag.TAG_COMPOUND);
                List<BlockState> palette = new ArrayList<>();
                for (int j = 0; j < pal.size(); j++) {
                    palette.add(NbtUtils.readBlockState(blocks, pal.getCompound(j)));
                }
                int[] cells = d.getIntArray("Cells");
                if (cells.length > MAX_CELLS * 2 || cells.length % 2 != 0) {
                    continue;
                }
                int[] plaque = d.contains("Plaque") ? d.getIntArray("Plaque") : null;
                Design design = new Design(d.getString("Id"), d.getString("Name"),
                    d.hasUUID("Owner") ? d.getUUID("Owner") : null, d.getInt("SX"), d.getInt("SY"), d.getInt("SZ"),
                    List.copyOf(palette), cells, d.contains("Type") ? d.getString("Type") : null,
                    plaque != null && plaque.length == 3 ? plaque : null,
                    d.contains("Facing") ? d.getString("Facing") : null, d.getLong("Saved"));
                data.designs.put(design.id(), design);
            } catch (RuntimeException malformed) {
                // one bad design never takes the others with it
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        ListTag list = new ListTag();
        for (Design d : designs.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("Id", d.id());
            c.putString("Name", d.name());
            if (d.owner() != null) {
                c.putUUID("Owner", d.owner());
            }
            c.putInt("SX", d.sizeX());
            c.putInt("SY", d.sizeY());
            c.putInt("SZ", d.sizeZ());
            ListTag pal = new ListTag();
            for (BlockState s : d.palette()) {
                pal.add(NbtUtils.writeBlockState(s));
            }
            c.put("Palette", pal);
            c.put("Cells", new IntArrayTag(d.cells()));
            if (d.buildingType() != null) {
                c.putString("Type", d.buildingType());
            }
            if (d.plaquePos() != null) {
                c.putIntArray("Plaque", d.plaquePos());
            }
            if (d.plaqueFacing() != null) {
                c.putString("Facing", d.plaqueFacing());
            }
            c.putLong("Saved", d.savedAt());
            list.add(c);
        }
        tag.put("Designs", list);
        return tag;
    }

    /** Test/diagnostic: the air block constant, to keep the import honest. */
    static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }
}

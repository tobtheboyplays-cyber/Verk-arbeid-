package com.hearthstead.settlement.builder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side blueprint catalog: every {@code data/<ns>/blueprints/*.json}
 * with the structure template it names.
 *
 * <p>Loaded lazily on first use and cached per {@link ResourceManager}
 * instance, so a {@code /reload} (which swaps the manager) re-reads the
 * files without a reload listener. Parsing reads the vanilla structure NBT
 * directly (palette + blocks) instead of going through
 * {@code StructureTemplateManager}, whose block lists are private: the
 * Builder needs every cell, including planned air.
 *
 * <p>Tests may {@link #register} in-memory blueprints; those survive until
 * {@link #clearTestBlueprints}.
 */
public final class BlueprintLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Cap on cells per blueprint (BUILDER.md: a job is at most 4096 steps). */
    public static final int MAX_CELLS = 4096 * 2;
    public static final int MAX_EXTENT = 32;

    private static ResourceManager loadedFrom;
    private static Map<String, Blueprint> loaded = Map.of();

    /**
     * A blueprint's place among its style presets (blueprint lane: 2-3
     * presets per building, ids {@code <base>_timber / _stone / _rustic}).
     * {@code group} is the building the presets share; {@code label} is the
     * preset's own name ("Timber", "Stone", "Rustic" or the JSON "preset").
     */
    public record PresetInfo(String group, String label) {
    }

    private static Map<String, PresetInfo> presets = Map.of();

    /** The preset group and label of a blueprint (its own id / style when it has none). */
    public static synchronized PresetInfo presetOf(Blueprint blueprint) {
        PresetInfo info = presets.get(blueprint.id());
        if (info != null) {
            return info;
        }
        return new PresetInfo(blueprint.id(), label(blueprint.meta().style()));
    }

    static PresetInfo presetInfo(String id, String style, @Nullable String presetLabel) {
        return presetInfo(id, style, presetLabel, null);
    }

    /**
     * The JSON "group" wins (blueprint lane: the building type, so a type's
     * small, large and style presets share one row); without it an id
     * ending in "_" + style groups under the id without that suffix.
     */
    static PresetInfo presetInfo(String id, String style, @Nullable String presetLabel, @Nullable String group) {
        String key = group == null || group.isBlank() ? null : group;
        if (key == null) {
            key = id;
            if (style != null && !style.isEmpty() && id.endsWith("_" + style)) {
                key = id.substring(0, id.length() - style.length() - 1);
            }
        }
        return new PresetInfo(key, presetLabel == null || presetLabel.isBlank() ? label(style) : presetLabel);
    }

    /**
     * Two presets of one group with the same label (the Elmfield small and
     * large) are told apart by their "variant" (or name): "Elmfield Timber - Large".
     */
    static Map<String, PresetInfo> distinctLabels(Map<String, PresetInfo> byId, Map<String, String> variants) {
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (PresetInfo info : byId.values()) {
            seen.merge(info.group() + "\u0000" + info.label(), 1, Integer::sum);
        }
        Map<String, PresetInfo> out = new java.util.HashMap<>();
        for (Map.Entry<String, PresetInfo> e : byId.entrySet()) {
            PresetInfo info = e.getValue();
            String variant = variants.get(e.getKey());
            if (seen.getOrDefault(info.group() + "\u0000" + info.label(), 0) > 1 && variant != null && !variant.isBlank()) {
                info = new PresetInfo(info.group(), info.label() + " \u2013 " + label(variant));
            }
            out.put(e.getKey(), info);
        }
        return out;
    }

    private static String label(String style) {
        if (style == null || style.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(style.charAt(0)) + style.substring(1).replace('_', ' ');
    }
    private static final Map<String, Blueprint> TEST = new LinkedHashMap<>();

    private BlueprintLibrary() {
    }

    /** Every loaded blueprint, in id order (test blueprints last). */
    public static synchronized List<Blueprint> all(MinecraftServer server) {
        ensure(server);
        List<Blueprint> out = new ArrayList<>(loaded.values());
        if (server != null) {
            // "Our Designs": what the players surveyed with the Survey Rod.
            for (PlayerDesignSavedData.Design design : PlayerDesignSavedData.get(server).all()) {
                out.add(PlayerDesignSavedData.toBlueprint(design));
            }
        }
        out.addAll(TEST.values());
        return Collections.unmodifiableList(out);
    }

    @Nullable
    public static synchronized Blueprint get(MinecraftServer server, String id) {
        Blueprint test = TEST.get(id);
        if (test != null) {
            return test;
        }
        ensure(server);
        Blueprint found = loaded.get(id);
        if (found == null && server != null && id.startsWith("design_")) {
            PlayerDesignSavedData.Design design = PlayerDesignSavedData.get(server).get(id);
            found = design == null ? null : PlayerDesignSavedData.toBlueprint(design);
        }
        return found;
    }

    public static synchronized void register(Blueprint blueprint) {
        TEST.put(blueprint.id(), blueprint);
    }

    public static synchronized void clearTestBlueprints() {
        TEST.clear();
    }

    private static void ensure(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ResourceManager manager = server.getResourceManager();
        if (manager == loadedFrom) {
            return;
        }
        loadedFrom = manager;
        HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        Map<String, Blueprint> next = new java.util.TreeMap<>();
        Map<String, PresetInfo> nextPresets = new java.util.HashMap<>();
        Map<String, String> variants = new java.util.HashMap<>();
        Map<ResourceLocation, Resource> metas = manager.listResources("blueprints",
            location -> location.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : metas.entrySet()) {
            ResourceLocation location = entry.getKey();
            String path = location.getPath();
            String fileId = path.substring("blueprints/".length(), path.length() - ".json".length());
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                BlueprintMeta meta = BlueprintMeta.parse(fileId, json);
                Blueprint blueprint = loadTemplate(manager, blocks, meta);
                if (blueprint != null) {
                    next.put(meta.id(), blueprint);
                    nextPresets.put(meta.id(), presetInfo(meta.id(), meta.style(),
                        json.has("preset") && json.get("preset").isJsonPrimitive()
                            ? json.get("preset").getAsString() : null,
                        json.has("group") && json.get("group").isJsonPrimitive()
                            ? json.get("group").getAsString() : null));
                    String variant = json.has("variant") && json.get("variant").isJsonPrimitive()
                        ? json.get("variant").getAsString() : meta.name();
                    if (variant != null) {
                        variants.put(meta.id(), variant);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Builder: blueprint {} could not be read: {}", location, e.toString());
            }
        }
        loaded = next;
        presets = Map.copyOf(distinctLabels(nextPresets, variants));
        LOGGER.info("Builder: {} blueprints loaded", next.size());
    }

    @Nullable
    private static Blueprint loadTemplate(ResourceManager manager, HolderLookup<Block> blocks,
                                          BlueprintMeta meta) throws Exception {
        ResourceLocation structure = ResourceLocation.parse(meta.structure());
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(structure.getNamespace(),
            "structure/" + structure.getPath() + ".nbt");
        var resource = manager.getResource(file);
        if (resource.isEmpty()) {
            LOGGER.warn("Builder: blueprint {} names missing structure {}", meta.id(), file);
            return null;
        }
        CompoundTag root;
        try (InputStream in = resource.get().open()) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        return parse(meta, root, blocks);
    }

    /** Parses a vanilla structure template compound. Public for tests. */
    @Nullable
    public static Blueprint parse(BlueprintMeta meta, CompoundTag root, HolderLookup<Block> blocks) {
        ListTag size = root.getList("size", Tag.TAG_INT);
        if (size.size() != 3) {
            return null;
        }
        int sx = size.getInt(0);
        int sy = size.getInt(1);
        int sz = size.getInt(2);
        if (sx <= 0 || sy <= 0 || sz <= 0 || sx > MAX_EXTENT || sy > MAX_EXTENT || sz > MAX_EXTENT) {
            LOGGER.warn("Builder: blueprint {} has an unsupported size {}x{}x{}", meta.id(), sx, sy, sz);
            return null;
        }
        ListTag paletteTag = root.contains("palette", Tag.TAG_LIST)
            ? root.getList("palette", Tag.TAG_COMPOUND)
            : root.getList("palettes", Tag.TAG_LIST).isEmpty() ? new ListTag()
                : (ListTag) root.getList("palettes", Tag.TAG_LIST).get(0);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompound(i)));
        }
        ListTag blockList = root.getList("blocks", Tag.TAG_COMPOUND);
        List<Blueprint.Cell> cells = new ArrayList<>(blockList.size());
        for (int i = 0; i < blockList.size() && cells.size() < MAX_CELLS; i++) {
            CompoundTag b = blockList.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int stateIndex = b.getInt("state");
            if (pos.size() != 3 || stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }
            BlockState state = palette.get(stateIndex);
            if (state.is(Blocks.STRUCTURE_VOID)) {
                continue; // don't-care cell: the Builder leaves the world as it is
            }
            cells.add(new Blueprint.Cell(pos.getInt(0), pos.getInt(1), pos.getInt(2), state));
        }
        applyFurniture(meta, cells, blocks);
        return new Blueprint(meta, sx, sy, sz, cells);
    }

    /**
     * Optional furniture swaps (owner: Another Furniture chairs and tables
     * without a hard dependency). Only when the named mod is loaded and the
     * target state parses; otherwise the vanilla cell stays. Costs follow
     * the swapped block automatically, since materials are always computed
     * from the state that will actually be placed.
     */
    static void applyFurniture(BlueprintMeta meta, List<Blueprint.Cell> cells, HolderLookup<Block> blocks) {
        if (meta.furniture().isEmpty()) {
            return;
        }
        for (BlueprintMeta.FurnitureSwap swap : meta.furniture()) {
            if (!modLoaded(swap.mod())) {
                continue;
            }
            BlockState to;
            try {
                to = BlockStateParser.parseForBlock(blocks, swap.to(), false).blockState();
            } catch (Exception unknown) {
                LOGGER.debug("Builder: furniture swap {} in {} skipped: {}", swap.to(), meta.id(), unknown.toString());
                continue;
            }
            for (int i = 0; i < cells.size(); i++) {
                Blueprint.Cell cell = cells.get(i);
                boolean at = swap.pos() != null && cell.x() == swap.pos()[0]
                    && cell.y() == swap.pos()[1] && cell.z() == swap.pos()[2];
                boolean same = swap.pos() == null && swap.from() != null
                    && Blueprint.idOf(cell.state()).equals(swap.from());
                if (at || same) {
                    cells.set(i, new Blueprint.Cell(cell.x(), cell.y(), cell.z(), to));
                }
            }
        }
    }

    private static boolean modLoaded(String mod) {
        try {
            return net.neoforged.fml.ModList.get() != null && net.neoforged.fml.ModList.get().isLoaded(mod);
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    /** Resource id of the metadata folder, for logs and docs. */
    public static ResourceLocation folder() {
        return Hearthstead.id("blueprints");
    }
}

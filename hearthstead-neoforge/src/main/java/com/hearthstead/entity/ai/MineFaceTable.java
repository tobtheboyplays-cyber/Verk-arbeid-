package com.hearthstead.entity.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What one cut at the rock face turns up (MINE V2 face mode). The weighted
 * tables are data, so they can be balanced without code:
 * {@code data/hearthstead/mining/level_1.json} (the base mine) and
 * {@code level_2.json} (Deep Mine).
 *
 * <pre>{"entries": [{"item": "minecraft:raw_iron", "block": "minecraft:iron_ore",
 *   "weight": 9, "min": 1, "max": 1}, ...]}</pre>
 *
 * <p>{@code block} is the ore the find came out of: its break particles show
 * at the face, and it gates by pickaxe tier exactly like vanilla
 * ({@code isCorrectToolForDrops}): a stone pick never gets diamonds, gold or
 * redstone; that roll falls back to plain cobblestone.
 */
public final class MineFaceTable {
    public record Entry(Item item, BlockState block, int weight, int min, int max) {
    }

    /** One cut's result: the item and the ore it came from (for particles and bonuses). */
    public record Find(ItemStack stack, BlockState ore) {
    }

    private static final Entry COBBLE = new Entry(Items.COBBLESTONE, Blocks.STONE.defaultBlockState(), 1, 1, 1);
    private static final List<Entry> FALLBACK_ONE = List.of(
        new Entry(Items.COBBLESTONE, Blocks.STONE.defaultBlockState(), 62, 1, 1),
        new Entry(Items.COAL, Blocks.COAL_ORE.defaultBlockState(), 14, 1, 2),
        new Entry(Items.RAW_COPPER, Blocks.COPPER_ORE.defaultBlockState(), 9, 1, 3),
        new Entry(Items.RAW_IRON, Blocks.IRON_ORE.defaultBlockState(), 9, 1, 1),
        new Entry(Items.RAW_GOLD, Blocks.GOLD_ORE.defaultBlockState(), 3, 1, 1),
        new Entry(Items.REDSTONE, Blocks.REDSTONE_ORE.defaultBlockState(), 3, 2, 4));

    private static ResourceManager cachedFor;
    private static final List<List<Entry>> CACHE = new ArrayList<>();

    private MineFaceTable() {
    }

    /** The table for {@code level} (1 or 2), read from data; the built-in level-1 table if missing. */
    public static synchronized List<Entry> table(@Nullable ResourceManager resources, int level) {
        if (resources == null) {
            return FALLBACK_ONE;
        }
        if (resources != cachedFor) {
            CACHE.clear();
            for (int i = 1; i <= 2; i++) {
                CACHE.add(load(resources, i));
            }
            cachedFor = resources;
        }
        List<Entry> t = CACHE.get(Math.max(1, Math.min(2, level)) - 1);
        return t.isEmpty() ? FALLBACK_ONE : t;
    }

    private static List<Entry> load(ResourceManager resources, int level) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Hearthstead.MODID,
            "mining/level_" + level + ".json");
        Optional<Resource> res = resources.getResource(id);
        if (res.isEmpty()) {
            return List.of();
        }
        try (Reader reader = res.get().openAsReader()) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception broken) {
            Hearthstead.LOGGER.error("Mine face table {} is broken; using the built-in one", id, broken);
            return List.of();
        }
    }

    /** Parses a table; entries naming unknown items or blocks are skipped. */
    public static List<Entry> parse(JsonObject json) {
        List<Entry> out = new ArrayList<>();
        JsonArray entries = json.getAsJsonArray("entries");
        for (JsonElement e : entries) {
            JsonObject o = e.getAsJsonObject();
            ResourceLocation itemId = ResourceLocation.tryParse(o.get("item").getAsString());
            ResourceLocation blockId = o.has("block") ? ResourceLocation.tryParse(o.get("block").getAsString())
                : ResourceLocation.withDefaultNamespace("stone");
            if (itemId == null || blockId == null || !BuiltInRegistries.ITEM.containsKey(itemId)
                || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId);
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
            int min = o.has("min") ? o.get("min").getAsInt() : 1;
            int max = o.has("max") ? o.get("max").getAsInt() : min;
            if (weight > 0 && min > 0 && max >= min) {
                out.add(new Entry(item, block.defaultBlockState(), weight, min, Math.min(max, item.getDefaultMaxStackSize())));
            }
        }
        return out;
    }

    /**
     * One cut. A find the pick is too weak for (vanilla tool tiers) is plain
     * cobblestone instead.
     */
    public static Find roll(List<Entry> table, ItemStack pick, RandomSource random) {
        int total = 0;
        for (Entry e : table) {
            total += e.weight();
        }
        Entry hit = COBBLE;
        if (total > 0) {
            int r = random.nextInt(total);
            for (Entry e : table) {
                r -= e.weight();
                if (r < 0) {
                    hit = e;
                    break;
                }
            }
        }
        if (hit.item() != Items.COBBLESTONE && (pick == null || pick.isEmpty()
            || !pick.isCorrectToolForDrops(hit.block()))) {
            hit = COBBLE;
        }
        int count = hit.min() + (hit.max() > hit.min() ? random.nextInt(hit.max() - hit.min() + 1) : 0);
        return new Find(new ItemStack(hit.item(), count), hit.block());
    }
}

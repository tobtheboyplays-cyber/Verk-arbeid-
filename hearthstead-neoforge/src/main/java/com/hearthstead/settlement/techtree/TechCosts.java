package com.hearthstead.settlement.techtree;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.development.DevelopmentNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a node's data-file price to the physical cost lines the
 * Development payment path charges: Coins first (when the node costs any),
 * then each goods line in data order. A tag line ("#minecraft:logs") accepts
 * any item of the tag and shows as "any log".
 */
public final class TechCosts {
    private static final Map<String, List<DevelopmentNode.Cost>> CACHE = new ConcurrentHashMap<>();

    private TechCosts() {
    }

    public static List<DevelopmentNode.Cost> costs(TechNodeDef def) {
        return CACHE.computeIfAbsent(def.id(), ignored -> resolve(def));
    }

    /** Physical goods lines only (no Coins). */
    public static List<DevelopmentNode.Cost> goods(TechNodeDef def) {
        List<DevelopmentNode.Cost> all = costs(def);
        return def.coins() > 0 ? all.subList(1, all.size()) : all;
    }

    static List<DevelopmentNode.Cost> resolve(TechNodeDef def) {
        List<DevelopmentNode.Cost> out = new ArrayList<>();
        if (def.coins() > 0) {
            out.add(new DevelopmentNode.Cost(ModItems.GOLD_COIN.get(), def.coins()));
        }
        for (TechNodeDef.GoodsLine line : def.goods()) {
            out.add(line(line));
        }
        return List.copyOf(out);
    }

    public static DevelopmentNode.Cost line(TechNodeDef.GoodsLine line) {
        if (line.isTag()) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(line.tag()));
            return switch (line.tag()) {
                case "minecraft:logs" -> new DevelopmentNode.Cost(Items.OAK_LOG, line.count(), tag,
                    "hearthstead.development.cost.any_log");
                case "minecraft:planks" -> new DevelopmentNode.Cost(Items.OAK_PLANKS, line.count(), tag,
                    "hearthstead.development.cost.any_plank");
                case "minecraft:wool" -> new DevelopmentNode.Cost(Items.WHITE_WOOL, line.count(), tag,
                    "hearthstead.development.cost.any_wool");
                default -> new DevelopmentNode.Cost(Items.PAPER, line.count(), tag,
                    "hearthstead.techtree.cost.tag." + line.tag().replace(':', '.'));
            };
        }
        ResourceLocation id = ResourceLocation.parse(line.item());
        Item item = BuiltInRegistries.ITEM.getOptional(id)
            .orElseThrow(() -> new IllegalStateException("Unknown tech cost item " + id));
        if (item == Items.AIR) {
            throw new IllegalStateException("Tech cost item resolves to air: " + id);
        }
        return new DevelopmentNode.Cost(item, line.count());
    }
}

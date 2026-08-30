package com.hearthstead.settlement.development;

import com.hearthstead.entity.Profession;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Mayor-issued professions that have a complete enough gameplay loop for the
 * first demo. Every other profession is intentionally absent and therefore
 * fails closed instead of appearing as a decorative promise.
 */
public final class JobEmblemCatalog {
    public record Entry(Profession profession, DevelopmentNode unlock,
                        List<DevelopmentNode.Cost> costs) {
        public Component displayName() {
            return Component.translatable("item.hearthstead.job_emblem." + profession.key());
        }
    }

    public static final List<Entry> RELEASE_CATALOG = List.of(
        entry(Profession.LUMBERER, DevelopmentNode.TIMBER_RIGHTS,
            Items.FLINT, 2, Items.LEATHER, 2),
        entry(Profession.FARMER, DevelopmentNode.CULTIVATED_GROUND,
            Items.WHEAT_SEEDS, 8, Items.LEATHER, 2),
        entry(Profession.COURIER, DevelopmentNode.STORES_AND_ROADS,
            Items.CHEST, 1, Items.LEATHER, 4),
        entry(Profession.INNKEEPER, DevelopmentNode.HOSPITALITY,
            Items.BREAD, 4, Items.LEATHER, 2),
        entry(Profession.GUARD, DevelopmentNode.FIRST_WATCH,
            Items.IRON_INGOT, 4, Items.LEATHER, 2),
        entry(Profession.ARCHER, DevelopmentNode.ARM_THE_WATCH,
            Items.ARROW, 12, Items.LEATHER, 2),
        entry(Profession.SAWYER, DevelopmentNode.GUILD_DOCTRINE,
            Items.IRON_INGOT, 2, Items.LEATHER, 2),
        entry(Profession.SCHOLAR, DevelopmentNode.HEARTH_DOCTRINE,
            Items.BOOK, 2, Items.LEATHER, 2)
    );

    @Nullable
    public static Entry forProfession(Profession profession) {
        for (Entry entry : RELEASE_CATALOG) {
            if (entry.profession() == profession) {
                return entry;
            }
        }
        return null;
    }

    public static boolean releaseReady(Profession profession) {
        return forProfession(profession) != null;
    }

    private static Entry entry(Profession profession, DevelopmentNode unlock,
                               Object... itemCountPairs) {
        java.util.ArrayList<DevelopmentNode.Cost> costs =
            new java.util.ArrayList<>(itemCountPairs.length / 2);
        for (int i = 0; i < itemCountPairs.length; i += 2) {
            costs.add(new DevelopmentNode.Cost((Item) itemCountPairs[i],
                (Integer) itemCountPairs[i + 1]));
        }
        return new Entry(profession, unlock, List.copyOf(costs));
    }

    private JobEmblemCatalog() {
    }
}

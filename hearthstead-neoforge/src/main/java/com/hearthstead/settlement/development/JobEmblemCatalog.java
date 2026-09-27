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
 * fails closed instead of appearing as a decorative promise. The extended
 * trades are listed here but only offered while {@link ExtendedTrades} is on.
 */
public final class JobEmblemCatalog {
    /**
     * One emblem's full price: Coins first, then the listed goods. The whole
     * list is charged all-or-nothing through the Development payment path
     * (buyer inventory, bound Hearth, linked Warehouse chests).
     */
    public record Entry(Profession profession, DevelopmentNode unlock,
                        List<DevelopmentNode.Cost> goods) {
        /** Three single-use Basic shipments fund Timber Rights (2) and the
         *  first productive worker (1) without waiting for another visitor. */
        public int coinPrice() {
            // Early-Coin balance (26 Sep): the two first-raid defenders cost 3,
            // not 4. Other martial emblems default to 4. The Hunter is not
            // martial (Profession.martial()), so it costs the plain 2 Coins
            // plus its arrows and leather.
            return switch (profession) {
                case LUMBERER -> 1;
                case GUARD, ARCHER -> 3;
                // BATTLE-ROLES: Healer is the cheap early answer to death;
                // the longsword and the mage are Town-priced.
                case HEALER -> 3;
                case SPEARMAN -> 4;
                case LONGSWORDSMAN -> 5;
                case RUNE_MAGE -> 6;
                default -> profession.martial() ? 4 : 2;
            };
        }

        /** Coins (always index 0) followed by the goods, in catalogue order. */
        public List<DevelopmentNode.Cost> costs() {
            java.util.ArrayList<DevelopmentNode.Cost> all =
                new java.util.ArrayList<>(goods.size() + 1);
            all.add(new DevelopmentNode.Cost(
                com.hearthstead.registry.ModItems.GOLD_COIN.get(), coinPrice()));
            all.addAll(goods);
            return List.copyOf(all);
        }
        public Component displayName() {
            return Component.translatable("item.hearthstead.job_emblem." + profession.key());
        }
    }

    // Goods are sized to the survival stage each emblem unlocks at (see
    // ui-proposal/survival-progression-audit.md timeline). Pre-first-raid
    // emblems stay light: the Lumberer (min ~20-30) comes before cows, so it
    // takes no leather; Courier/Farmer/Guard/Archer take 1-2 leather; the
    // Guard's iron and the Archer's arrows sit on top of First Watch's 4 iron
    // and the Archer's own 8-arrow kit; the Innkeeper's bread competes with
    // the 48-meal reserve. Post-raid emblems keep their original goods.
    public static final List<Entry> RELEASE_CATALOG = List.of(
        entry(Profession.LUMBERER, DevelopmentNode.TIMBER_RIGHTS,
            Items.FLINT, 2),
        entry(Profession.FARMER, DevelopmentNode.CULTIVATED_GROUND,
            Items.WHEAT_SEEDS, 8, Items.LEATHER, 1),
        // Early jobs (day one): no leather before the Hunter brings hides.
        entry(Profession.FISHER, DevelopmentNode.SHORE_PROVISIONS,
            Items.STRING, 2),
        entry(Profession.COURIER, DevelopmentNode.STORES_AND_ROADS,
            Items.CHEST, 1, Items.LEATHER, 2),
        entry(Profession.TRADER, DevelopmentNode.TRADING_POST, Items.CHEST, 1, Items.STICK, 4),
        entry(Profession.INNKEEPER, DevelopmentNode.HOSPITALITY,
            Items.BREAD, 2, Items.LEATHER, 2),
        entry(Profession.GUARD, DevelopmentNode.FIRST_WATCH,
            Items.IRON_INGOT, 2, Items.LEATHER, 1),
        entry(Profession.ARCHER, DevelopmentNode.ARM_THE_WATCH,
            Items.ARROW, 4, Items.LEATHER, 1),
        entry(Profession.HUNTER, DevelopmentNode.BORDER_WARDENS,
            Items.FLINT, 4, Items.STICK, 8),
        entry(Profession.SAWYER, DevelopmentNode.GUILD_DOCTRINE,
            Items.IRON_INGOT, 2, Items.LEATHER, 2),
        entry(Profession.SCHOLAR, DevelopmentNode.HEARTH_DOCTRINE,
            Items.BOOK, 2, Items.LEATHER, 2),
        // BUILDER lane: rides on Timber Rights until the tech tree's own
        // builders_hut node exists (agreed with the tech-tree designer).
        // Pre-first-raid emblem, so light: no leather, a flint for the
        // chisel edge and a stack of sticks for the scaffold poles.
        entry(Profession.BUILDER, DevelopmentNode.TIMBER_RIGHTS,
            Items.FLINT, 2, Items.STICK, 8),
        // BATTLE-ROLES (plan/BATTLE-ROLES.md section 5). Code unlocks ride on today's
        // nodes until the tree's spearmen / longswords / battle_healer /
        // rune_mage nodes land; the halls' own materials do the tier pricing.
        entry(Profession.SPEARMAN, DevelopmentNode.SHIELD_DOCTRINE,
            Items.IRON_INGOT, 1, Items.LEATHER, 1),
        entry(Profession.LONGSWORDSMAN, DevelopmentNode.SHIELD_DOCTRINE,
            Items.IRON_INGOT, 3, Items.LEATHER, 2),
        entry(Profession.HEALER, DevelopmentNode.FIRST_RAID_AFTERMATH,
            Items.PAPER, 2, Items.LEATHER, 1),
        entry(Profession.RUNE_MAGE, DevelopmentNode.HEARTH_DOCTRINE,
            Items.LAPIS_LAZULI, 8, Items.AMETHYST_SHARD, 4, Items.BOOK, 1),
        // TRADES-UNLOCK (26 Sep): the 15 extended trades, sold only while
        // [features] extendedTrades is on (ExtendedTrades). All are post-raid
        // crafts at the plain 2-Coin trade price; goods are one trade token
        // plus a single leather, so a co-op settlement can staff every bench.
        entry(Profession.MILLER, DevelopmentNode.LAND_AND_HARVEST,
            Items.WHEAT, 8, Items.LEATHER, 1),
        entry(Profession.HERDER, DevelopmentNode.LAND_AND_HARVEST,
            Items.HAY_BLOCK, 1, Items.LEATHER, 1),
        entry(Profession.BAKER, DevelopmentNode.LAND_AND_HARVEST,
            Items.BREAD, 2, Items.LEATHER, 1),
        entry(Profession.BUTCHER, DevelopmentNode.LAND_AND_HARVEST,
            Items.IRON_INGOT, 1, Items.LEATHER, 1),
        entry(Profession.MINER, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.TORCH, 8, Items.LEATHER, 1),
        entry(Profession.CARPENTER, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.STICK, 8, Items.LEATHER, 1),
        entry(Profession.MASON, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.COBBLESTONE, 16, Items.LEATHER, 1),
        entry(Profession.SMELTER, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.FURNACE, 1, Items.LEATHER, 1),
        entry(Profession.SMITH, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.IRON_INGOT, 2, Items.LEATHER, 1),
        entry(Profession.TANNER, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.LEATHER, 2),
        entry(Profession.WEAVER, DevelopmentNode.CRAFT_AND_INDUSTRY,
            Items.STRING, 4, Items.LEATHER, 1),
        entry(Profession.COOK, DevelopmentNode.HALL_AND_LEARNING,
            Items.BOWL, 2, Items.LEATHER, 1),
        entry(Profession.BREWER, DevelopmentNode.HALL_AND_LEARNING,
            Items.GLASS_BOTTLE, 2, Items.LEATHER, 1),
        entry(Profession.ARMOURER, DevelopmentNode.FORTIFICATION,
            Items.IRON_INGOT, 2, Items.LEATHER, 2),
        entry(Profession.FLETCHER, DevelopmentNode.FORTIFICATION,
            Items.FEATHER, 4, Items.FLINT, 2)
    );

    /**
     * The emblem the Mayor sells for this profession in this world, or null.
     * Extended-trade entries resolve only while {@link ExtendedTrades} is on,
     * so with the switch off every caller sees the old sixteen-emblem shop.
     */
    @Nullable
    public static Entry forProfession(Profession profession) {
        for (Entry entry : RELEASE_CATALOG) {
            if (entry.profession() == profession) {
                return offered(entry) ? entry : null;
            }
        }
        return null;
    }

    /** Whether this catalogue row is on sale in this world (the switch). */
    public static boolean offered(Entry entry) {
        return entry != null
            && (!entry.unlock().extendedTrade() || ExtendedTrades.enabled());
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

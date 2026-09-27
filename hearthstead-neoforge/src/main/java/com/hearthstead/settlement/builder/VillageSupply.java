package com.hearthstead.settlement.builder;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.settlement.request.CraftingOrderService;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the village itself can supply to its Builder (supply-chain audit,
 * owner 26 Sep: "the Builder must build every building from blocks someone
 * in the village can supply, without the player"; "drop the decoration for
 * now").
 *
 * <p>Read-only rules, owned by the techaudit lane; the builder lane's
 * planner only CALLS them:
 * <ul>
 *   <li>{@link #shouldSkip}: a pure-decoration block nobody in the village can
 *       make (a flower, a cobweb, an amethyst block...) is left out of the
 *       plan while {@code [builder] skipUnsuppliedDecor} is on, so a building
 *       is never stuck waiting for something only the player could bring.</li>
 *   <li>{@link #plainVariant}: a coloured bed, wool, carpet or banner is built
 *       in its plain white version, which the Weaver makes.</li>
 * </ul>
 * With the switch off both return "no change" and every blueprint is built
 * exactly as drawn.
 */
public final class VillageSupply {

    /** Pure decoration the village cannot produce (explicit, never a guess). */
    static final Set<Item> DECOR = Set.of(
        Items.DANDELION, Items.POPPY, Items.BLUE_ORCHID, Items.ALLIUM, Items.AZURE_BLUET,
        Items.RED_TULIP, Items.ORANGE_TULIP, Items.WHITE_TULIP, Items.PINK_TULIP, Items.OXEYE_DAISY,
        Items.CORNFLOWER, Items.LILY_OF_THE_VALLEY, Items.TORCHFLOWER, Items.WITHER_ROSE,
        Items.SUNFLOWER, Items.LILAC, Items.ROSE_BUSH, Items.PEONY, Items.PITCHER_PLANT,
        Items.FERN, Items.LARGE_FERN, Items.SHORT_GRASS, Items.TALL_GRASS, Items.DEAD_BUSH,
        Items.VINE, Items.LILY_PAD, Items.MOSS_CARPET, Items.AZALEA, Items.FLOWERING_AZALEA,
        Items.OAK_LEAVES, Items.SPRUCE_LEAVES, Items.BIRCH_LEAVES, Items.JUNGLE_LEAVES,
        Items.ACACIA_LEAVES, Items.DARK_OAK_LEAVES, Items.MANGROVE_LEAVES, Items.CHERRY_LEAVES,
        Items.AZALEA_LEAVES, Items.FLOWERING_AZALEA_LEAVES,
        Items.COBWEB, Items.AMETHYST_CLUSTER, Items.CARVED_PUMPKIN,
        Items.JACK_O_LANTERN, Items.TARGET,
        Items.PLAYER_HEAD, Items.SKELETON_SKULL, Items.CANDLE);
    // Never decoration: a block some BuildingType REQUIRES (the Brewery's
    // brewing stand, the Rune Hall's enchanting table and amethyst) is a
    // workshop order like any other (Production bp_ recipes).

    /** What the village's gatherers (Lumberer, Miner, Farmer, Herder, Hunter, Fisher) bring in. */
    static final Set<Item> GATHERED = Set.of(
        Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
        Items.DARK_OAK_LOG, Items.CHERRY_LOG, Items.MANGROVE_LOG,
        Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL, Items.FLINT, Items.SAND,
        Items.RED_SAND, Items.CLAY_BALL, Items.COAL, Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD,
        Items.ANDESITE, Items.GRANITE, Items.DIORITE, Items.TUFF, Items.CALCITE, Items.SANDSTONE,
        Items.LAPIS_LAZULI, Items.REDSTONE, Items.DIAMOND, Items.EMERALD,
        Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BEETROOT_SEEDS,
        Items.SUGAR_CANE, Items.WHITE_WOOL, Items.LIGHT_GRAY_WOOL, Items.GRAY_WOOL, Items.BLACK_WOOL,
        Items.BROWN_WOOL, Items.PINK_WOOL, Items.LEATHER, Items.FEATHER, Items.RABBIT_HIDE, Items.EGG,
        Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT, Items.BROWN_MUSHROOM,
        Items.COD, Items.SALMON);

    private static final Pattern COLOURED = Pattern.compile(
        "^minecraft:(orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown"
            + "|green|red|black)_(bed|wool|carpet|banner|wall_banner)$");

    private VillageSupply() {
    }

    /** {@code [builder] skipUnsuppliedDecor} (default on). */
    public static boolean skipUnsuppliedDecor() {
        return HearthsteadServerConfig.skipUnsuppliedDecor();
    }

    /** True when {@code item} is pure decoration (flowers, leaves, cobweb, amethyst...). */
    public static boolean isDecor(@Nullable Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        if (DECOR.contains(item)) {
            return true;
        }
        try {
            ItemStack stack = new ItemStack(item);
            return stack.is(ItemTags.SMALL_FLOWERS) || stack.is(ItemTags.TALL_FLOWERS)
                || stack.is(ItemTags.LEAVES);
        } catch (RuntimeException tagsNotBound) {
            return false;
        }
    }

    /** True when a village worker gathers this item from the world. */
    public static boolean gathered(@Nullable Item item) {
        if (item == null) {
            return false;
        }
        if (GATHERED.contains(item)) {
            return true;
        }
        try {
            return new ItemStack(item).is(ItemTags.LOGS_THAT_BURN);
        } catch (RuntimeException tagsNotBound) {
            return false;
        }
    }

    /**
     * True when the village can obtain {@code item} without the player: a
     * Warehouse holds some, an unlocked staffed workshop can make it, or a gatherer
     * brings it in.
     */
    public static boolean canMake(@Nullable ServerLevel level, @Nullable Settlement settlement, @Nullable Item item) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        if (gathered(item)) {
            return true;
        }
        return level != null && settlement != null
            && (BuilderStock.warehouseCount(level, settlement, item) > 0
                || CraftingOrderService.resolveWorkshop(level, settlement, item, null) != null);
    }

    /** The planner's one call: leave this decoration out of the plan now. */
    public static boolean shouldSkip(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                     @Nullable Item item) {
        return skipUnsuppliedDecor() && isDecor(item) && !canMake(level, settlement, item);
    }

    /**
     * The block id to build instead: a coloured bed/wool/carpet/banner becomes
     * its white version (the Weaver makes white). Unchanged otherwise, and
     * unchanged while the switch is off.
     */
    public static String plainVariant(String blockId) {
        return skipUnsuppliedDecor() ? plainVariantRule(blockId) : blockId;
    }

    /** Pure form of {@link #plainVariant} (JUnit). */
    static String plainVariantRule(String blockId) {
        if (blockId == null) {
            return null;
        }
        Matcher m = COLOURED.matcher(blockId);
        return m.matches() ? "minecraft:white_" + m.group(2) : blockId;
    }

    /** Item id helper for status lines and tests. */
    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}

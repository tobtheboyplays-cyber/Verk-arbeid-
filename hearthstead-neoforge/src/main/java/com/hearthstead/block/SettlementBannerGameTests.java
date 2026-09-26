package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The Hearth became the settlement Banner without changing its block id,
 * block-entity type or NBT. These tests prove an existing save loads as the
 * Banner with its stores and settlement link intact, and that hanging new
 * colours is an exact item exchange.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SettlementBannerGameTests {
    private static final BlockPos STAND = new BlockPos(2, 1, 2);

    /**
     * A Hearth block entity exactly as the pre-Banner code saved it (block
     * state without a facing property; Inventory and SettlementId keys only)
     * loads through the chunk-load path, keeps every stack and its settlement
     * link, re-saves those keys unchanged, and keeps working as the Banner.
     */
    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_banner_legacy_hearth_nbt_loads_as_banner")
    public void legacyHearthNbtLoadsAsBanner(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        BlockPos pos = helper.absolutePos(STAND);

        // Old palettes stored "hearthstead:hearth" with no properties.
        BlockState legacyState = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),
            TagParser.parseTag("{Name:\"hearthstead:hearth\"}"));
        helper.assertTrue(legacyState.is(ModBlocks.HEARTH.get()),
            "a legacy hearth palette entry must resolve to the Banner block");
        helper.assertTrue(legacyState.getValue(HearthBlock.FACING) == Direction.NORTH,
            "a legacy hearth without facing must load with the default facing");
        level.setBlock(pos, legacyState, 3);

        Settlement settlement = freshSettlement(helper, pos, "Oldhearth");
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "hearthstead:hearth");
        legacy.putInt("x", pos.getX());
        legacy.putInt("y", pos.getY());
        legacy.putInt("z", pos.getZ());
        legacy.put("Inventory", TagParser.parseTag(
            "{Size:24,Items:[{Slot:0,id:\"minecraft:bread\",count:12},"
                + "{Slot:5,id:\"hearthstead:gold_coin\",count:3},"
                + "{Slot:23,id:\"minecraft:oak_log\",count:64}]}"));
        legacy.putUUID("SettlementId", settlement.id);
        CompoundTag legacyInventory = legacy.getCompound("Inventory").copy();

        BlockEntity loaded = BlockEntity.loadStatic(pos, legacyState, legacy, registries);
        helper.assertTrue(loaded instanceof HearthBlockEntity,
            "legacy hearth NBT must load as the Banner's block entity, got " + loaded);
        level.setBlockEntity(loaded);
        HearthBlockEntity banner = (HearthBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(banner != null, "the loaded Banner must be in the level");

        assertStack(helper, banner.getInventory().getStackInSlot(0), Items.BREAD, 12, "slot 0");
        assertStack(helper, banner.getInventory().getStackInSlot(5), ModItems.GOLD_COIN.get(), 3, "slot 5");
        assertStack(helper, banner.getInventory().getStackInSlot(23), Items.OAK_LOG, 64, "slot 23");
        helper.assertTrue(settlement.id.equals(banner.getSettlementId()),
            "the settlement link must survive the load");
        helper.assertTrue(SettlementManager.byId(level, banner.getSettlementId()) == settlement,
            "the link must resolve to the live settlement");
        helper.assertTrue(banner.getHeraldry().isEmpty(),
            "a legacy hearth flies the founding colours (no adopted banner)");
        helper.assertTrue(banner.countFoodUnits() == 12, "the Banner still counts its larder");

        CompoundTag resaved = banner.saveWithoutMetadata(registries);
        helper.assertTrue(resaved.getCompound("Inventory").equals(legacyInventory),
            "re-saving must keep the Inventory NBT byte-for-byte equivalent: "
                + resaved.getCompound("Inventory") + " vs " + legacyInventory);
        helper.assertTrue(settlement.id.equals(resaved.getUUID("SettlementId")),
            "re-saving must keep SettlementId");
        helper.assertFalse(resaved.contains("Heraldry"),
            "founding colours must not invent a Heraldry key");

        // It is still the settlement's centre: the same menu opens and the
        // same stores are behind it.
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        HearthMenu menu = (HearthMenu) banner.createMenu(1, player.getInventory(), player);
        helper.assertTrue(menu != null, "the Banner must open the settlement menu");
        helper.assertFalse(level.getBlockState(pos).getShape(level, pos).isEmpty(),
            "the stand must have a clickable shape");

        // And its server tick keeps driving the settlement.
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(settlement.id.equals(banner.getSettlementId()),
                "ticking must not drop the settlement link");
            helper.assertTrue(settlement.foodCache == 12,
                "the Banner's tick must publish its food count, got " + settlement.foodCache);
            helper.succeed();
        });
    }

    /** Colours change as one exact exchange; nothing is duplicated or lost. */
    @GameTest(template = "empty5", timeoutTicks = 40,
        batch = "settlement_banner_heraldry_exchange_is_exact")
    public void heraldryExchangeIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        level.setBlock(pos, ModBlocks.HEARTH.get().defaultBlockState(), 3);
        HearthBlockEntity banner = (HearthBlockEntity) level.getBlockEntity(pos);
        Settlement settlement = freshSettlement(helper, pos, "Colourford");
        banner.bindSettlement(settlement.id);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() - 1.5);
        player.getInventory().clearContent();

        // 1) Two plain white banners: one is hung, the founding colours were
        // never an item so nothing comes back.
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHITE_BANNER, 2));
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.ADOPTED, "first banner must be adopted");
        helper.assertTrue(player.getMainHandItem().is(Items.WHITE_BANNER)
            && player.getMainHandItem().getCount() == 1, "exactly one white banner must leave the hand");
        helper.assertTrue(banner.getHeraldry().is(Items.WHITE_BANNER) && banner.getHeraldry().getCount() == 1,
            "the Banner must now hold exactly one white banner");

        // 2) The same design again changes nothing.
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.UNCHANGED, "an identical design is refused");
        helper.assertTrue(player.getMainHandItem().getCount() == 1, "a refused hang keeps the hand intact");

        // 3) A patterned blue banner replaces it; the white one comes back.
        ItemStack blue = new ItemStack(Items.BLUE_BANNER);
        blue.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .addIfRegistered(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN),
                BannerPatterns.CROSS, DyeColor.WHITE).build());
        player.getInventory().setItem(1, player.getMainHandItem().copy());
        player.setItemInHand(InteractionHand.MAIN_HAND, blue.copy());
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.ADOPTED, "the blue banner must be adopted");
        helper.assertTrue(ItemStack.isSameItemSameComponents(banner.getHeraldry(), blue),
            "the Banner must fly the blue design with its patterns");
        helper.assertTrue(player.getMainHandItem().is(Items.WHITE_BANNER),
            "the previous white banner must be returned to the emptied hand");
        int banners = countBanners(player) + banner.getHeraldry().getCount();
        helper.assertTrue(banners == 3, "2 white + 1 blue in, 3 banners out; found " + banners);

        // 4) Heraldry persists and syncs through NBT.
        CompoundTag saved = banner.saveWithFullMetadata(level.registryAccess());
        BlockEntity reloaded = BlockEntity.loadStatic(pos, banner.getBlockState(), saved, level.registryAccess());
        helper.assertTrue(reloaded instanceof HearthBlockEntity copy
                && ItemStack.isSameItemSameComponents(copy.getHeraldry(), blue),
            "the adopted design must survive save/load");
        CompoundTag update = banner.getUpdateTag(level.registryAccess());
        helper.assertTrue(update.contains("Heraldry") && !update.contains("Inventory"),
            "clients receive the colours but never the stores");

        // 5) A player who may not build here cannot change the colours.
        player.getAbilities().mayBuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.RED_BANNER));
        helper.assertTrue(SettlementBannerInteraction.hangColours(player, InteractionHand.MAIN_HAND, banner)
            == SettlementBannerInteraction.Outcome.DENIED, "non-builders are refused");
        helper.assertTrue(player.getMainHandItem().is(Items.RED_BANNER)
            && ItemStack.isSameItemSameComponents(banner.getHeraldry(), blue), "a refusal changes nothing");
        player.getAbilities().mayBuild = true;

        // 6) Breaking the Banner returns the hung banner with the stores.
        level.removeBlock(pos, false);
        long blueDrops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
            .filter(e -> ItemStack.isSameItemSameComponents(e.getItem(), blue)).count();
        helper.assertTrue(blueDrops == 1, "breaking must drop the hung banner exactly once, got " + blueDrops);
        helper.succeed();
    }

    /** The stand faces its placer and refuses to go up without room for the pole. */
    @GameTest(template = "empty5", timeoutTicks = 40,
        batch = "settlement_banner_placement_needs_clearance")
    public void placementNeedsClearanceAndFacesPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() - 2.5);
        player.setYRot(0.0F); // looking south, toward the stand

        for (int dy = 0; dy <= HearthBlock.CLEARANCE; dy++) {
            level.setBlock(pos.above(dy), Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(pos.above(2), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        InteractionResult blocked = place(level, player, pos);
        helper.assertTrue(blocked == InteractionResult.FAIL,
            "placement must fail with a block two above, got " + blocked);
        helper.assertFalse(level.getBlockState(pos).is(ModBlocks.HEARTH.get()), "nothing may be placed");

        level.setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(HearthBlock.hasClearance(level, pos), "two clear blocks above the stand");
        BlockPlaceContext probe = context(level, player, pos);
        helper.assertTrue(probe.getClickedPos().equals(pos),
            "the probe must target the stand cell, got " + probe.getClickedPos());
        helper.assertTrue(ModBlocks.HEARTH.get().getStateForPlacement(probe) != null,
            "getStateForPlacement must accept a clear cell");
        BlockState candidate = ModBlocks.HEARTH.get().getStateForPlacement(probe);
        helper.assertTrue(level.isUnobstructed(candidate, pos,
                net.minecraft.world.phys.shapes.CollisionContext.of(player)),
            "nothing may obstruct the stand; entities here: " + level.getEntities(null,
                new AABB(pos).inflate(0.5)) + " player at " + player.position());
        helper.assertTrue(probe.canPlace(), "probe context can place; cell holds " + level.getBlockState(pos));
        InteractionResult placed = place(level, player, pos);
        helper.assertTrue(placed.consumesAction(), "placement must succeed with clear space, got " + placed);
        BlockState state = level.getBlockState(pos);
        helper.assertTrue(state.is(ModBlocks.HEARTH.get()), "the Banner must be placed");
        helper.assertTrue(state.getValue(HearthBlock.FACING) == Direction.NORTH,
            "a player looking south must see the stand's front (facing north)");
        helper.succeed();
    }

    private static BlockPlaceContext context(ServerLevel level, ServerPlayer player, BlockPos pos) {
        ItemStack stack = new ItemStack(ModItems.HEARTH.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0, 0.5, 0),
            Direction.UP, pos.below(), false);
        return new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit);
    }

    private static InteractionResult place(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return ModItems.HEARTH.get().place(context(level, player, pos));
    }

    private static int countBanners(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (SettlementHeraldry.isBanner(s)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static Settlement freshSettlement(GameTestHelper helper, BlockPos center, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D, old.center.getZ() + 0.5D));
        Settlement settlement = new Settlement(UUID.randomUUID(), name, center);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static void assertStack(GameTestHelper helper, ItemStack stack,
                                    net.minecraft.world.item.Item item, int count, String where) {
        helper.assertTrue(stack.is(item) && stack.getCount() == count,
            where + " must hold " + count + " " + item + ", got " + stack);
    }
}

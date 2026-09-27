package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tech-gated crafting (owner rule, batch {@code techtree_craft_gate}): a
 * gated recipe yields nothing before its node and its item after; shift-click
 * cannot take a result that is not there; a second co-op player in the same
 * settlement crafts once it is learned; day-one items craft with an empty
 * tree; the kill switch restores plain crafting.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechCraftGateGameTests {

    public TechCraftGateGameTests() {
    }

    @GameTest(template = "empty16", batch = "techtree_craft_gate", timeoutTicks = 60)
    public void gatedRecipeRefusedBeforeNodeAndAllowedAfter(GameTestHelper helper) {
        Fixture f = fixture(helper, "GateA");
        ServerPlayer player = playerAt(helper, f);
        AbstractContainerMenu inv = player.inventoryMenu;
        fillPatrolMap(inv);
        helper.assertTrue(inv.getSlot(0).getItem().isEmpty(),
            "Patrol Map must not craft before Barracks & Guard (first_watch)");
        ItemStack moved = inv.quickMoveStack(player, 0);
        helper.assertTrue(moved.isEmpty() && player.getInventory().countItem(ModItems.PATROL_MAP.get()) == 0,
            "shift-click must not bypass the gate");

        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        inv.slotsChanged(player.inventoryMenu.getCraftSlots());
        helper.assertTrue(inv.getSlot(0).getItem().is(ModItems.PATROL_MAP.get()),
            "Patrol Map crafts once first_watch is learned");
        clear(inv);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_craft_gate", timeoutTicks = 60)
    public void craftingTableIsGatedAndCoopPlayerTwoShares(GameTestHelper helper) {
        Fixture f = fixture(helper, "GateB");
        ServerPlayer one = playerAt(helper, f);
        ServerPlayer two = playerAt(helper, f);
        CraftingMenu table = new CraftingMenu(1, two.getInventory(),
            ContainerLevelAccess.create(helper.getLevel(), f.hearth.getBlockPos()));
        // The spear's diagonal (recipe wooden_spear: "  P", " S ", "S  "): grid
        // slots 3, 5 and 7. A vertical 1/4/7 column is a vanilla wooden shovel.
        table.getSlot(3).set(new ItemStack(Items.OAK_PLANKS));
        table.getSlot(5).set(new ItemStack(Items.STICK));
        table.getSlot(7).set(new ItemStack(Items.STICK));
        helper.assertTrue(table.getSlot(0).getItem().isEmpty(),
            "a wooden spear needs Spearmen on the crafting table too");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.learnTech("spearmen");
        table.slotsChanged(table.getSlot(1).container);
        helper.assertTrue(table.getSlot(0).getItem().is(com.hearthstead.registry.RoleItems.WOODEN_SPEAR.get()),
            "player two crafts from the shared settlement tree once Spearmen is learned (by anyone)");
        helper.assertTrue(one != two, "two distinct co-op players");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_craft_gate", timeoutTicks = 60)
    public void dayOneItemsCraftWithAnEmptyTreeAndKillSwitchRestores(GameTestHelper helper) {
        Fixture f = fixture(helper, "GateC");
        ServerPlayer player = playerAt(helper, f);
        AbstractContainerMenu inv = player.inventoryMenu;
        inv.getSlot(1).set(new ItemStack(Items.BOOK));
        inv.getSlot(2).set(new ItemStack(Items.OAK_SAPLING));
        helper.assertTrue(inv.getSlot(0).getItem().is(ModItems.HANDBOOK.get()),
            "the Handbook is never gated (first 10 minutes)");
        clear(inv);
        Boolean before = com.hearthstead.settlement.techtree.TechTreeConfig.gateCraftingOverride;
        try {
            com.hearthstead.settlement.techtree.TechTreeConfig.gateCraftingOverride = false;
            fillPatrolMap(inv);
            helper.assertTrue(inv.getSlot(0).getItem().is(ModItems.PATROL_MAP.get()),
                "[techtree] gateCrafting=false crafts everything as before");
        } finally {
            com.hearthstead.settlement.techtree.TechTreeConfig.gateCraftingOverride = before;
            clear(inv);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_craft_gate", timeoutTicks = 60)
    public void lostContextBeforeTakeYieldsNothingAndConsumesNothing(GameTestHelper helper) {
        // rune_mage is learned by no other test in this batch (nearest-Banner fallback).
        Fixture f = fixture(helper, "GateD");
        ServerPlayer player = playerAt(helper, f);
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.learnTech("rune_mage");
        AbstractContainerMenu inv = player.inventoryMenu;
        inv.getSlot(1).set(new ItemStack(Items.AMETHYST_SHARD));
        inv.getSlot(2).set(new ItemStack(Items.LAPIS_LAZULI));
        inv.getSlot(3).set(new ItemStack(Items.LAPIS_LAZULI));
        inv.getSlot(4).set(new ItemStack(Items.SMOOTH_STONE));
        helper.assertTrue(inv.getSlot(0).getItem().is(com.hearthstead.registry.RoleItems.RUNE_STONE.get()),
            "the result shows while the settlement knows Rune Mage");
        // The settlement goes away; the grid is not touched.
        SettlementSavedData.get(helper.getLevel()).settlements.remove(f.settlement.id);
        inv.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
        inv.clicked(0, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
        helper.assertTrue(inv.getCarried().isEmpty()
                && player.getInventory().countItem(com.hearthstead.registry.RoleItems.RUNE_STONE.get()) == 0,
            "neither a take nor a shift-click may hand out the cached result");
        helper.assertTrue(inv.getSlot(1).getItem().is(Items.AMETHYST_SHARD)
                && inv.getSlot(4).getItem().is(Items.SMOOTH_STONE),
            "a refused take consumes no ingredient");
        clear(inv);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_craft_gate", timeoutTicks = 60)
    public void crafterFollowsItsSettlementAndWorkshopsFollowRecipeGates(GameTestHelper helper) {
        // battle_healer / land_and_harvest: learned by no other test in this batch.
        Fixture f = fixture(helper, "GateE");
        net.minecraft.world.item.crafting.CraftingInput bandage = net.minecraft.world.item.crafting.CraftingInput.of(3, 3,
            java.util.List.of(new ItemStack(Items.WHITE_WOOL), new ItemStack(Items.STRING), new ItemStack(Items.STRING),
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        BlockPos inside = f.hearth.getBlockPos().above();
        java.util.Optional<?> none = crafterResult(helper, inside, bandage);
        java.util.Optional<?> nowhere = crafterResult(helper, null, bandage);
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.learnTech("battle_healer");
        java.util.Optional<?> learned = crafterResult(helper, inside, bandage);
        helper.assertTrue(none.isEmpty() && nowhere.isEmpty() && learned.isPresent(),
            "a Crafter makes a gated recipe only for a settlement that learned it: "
                + none.isPresent() + "/" + nowhere.isPresent() + "/" + learned.isPresent());
        // Workshops: a Kitchen may not make flour (land_and_harvest) until it is learned.
        com.hearthstead.settlement.Building kitchen = com.hearthstead.gametest.GameTestFixtures.register(helper,
            f.settlement, com.hearthstead.building.BuildingType.KITCHEN, 8, 8);
        boolean before = TechCraftGate.workshopAllowed(helper.getLevel(), kitchen, ModItems.FLOUR.get());
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        state.unlock(DevelopmentNode.LAND_AND_HARVEST);
        boolean after = TechCraftGate.workshopAllowed(helper.getLevel(), kitchen, ModItems.FLOUR.get());
        helper.assertTrue(!before && after, "workshop flour follows its gate: " + before + "/" + after);
        helper.assertTrue(TechCraftGate.workshopAllowed(helper.getLevel(), kitchen, Items.BREAD),
            "vanilla outputs are never workshop-gated");
        helper.succeed();
    }

    private static java.util.Optional<?> crafterResult(GameTestHelper helper, BlockPos pos,
                                                        net.minecraft.world.item.crafting.CraftingInput input) {
        try {
            if (pos == null) {
                TechCraftGate.CRAFTER_CONTEXT.remove();
            } else {
                TechCraftGate.CRAFTER_CONTEXT.set(pos);
            }
            return net.minecraft.world.level.block.CrafterBlock.getPotentialResults(helper.getLevel(), input);
        } finally {
            TechCraftGate.CRAFTER_CONTEXT.remove();
        }
    }

    @GameTest(template = "empty16", batch = "techtree_craft_gate", timeoutTicks = 60)
    public void netheriteSmithingUpgradeNeedsMasterArmoury(GameTestHelper helper) {
        // master_armoury is learned by no other test in this batch.
        Fixture f = fixture(helper, "GateF");
        ServerPlayer player = playerAt(helper, f);
        Item diamond = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse("hearthstead:diamond_halberd"));
        Item netherite = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse("hearthstead:netherite_halberd"));
        net.minecraft.world.inventory.SmithingMenu anvil = new net.minecraft.world.inventory.SmithingMenu(2,
            player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), f.hearth.getBlockPos()));
        anvil.getSlot(0).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        anvil.getSlot(1).set(new ItemStack(diamond));
        anvil.getSlot(2).set(new ItemStack(Items.NETHERITE_INGOT));
        int result = anvil.getResultSlot();
        anvil.clicked(result, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
        helper.assertTrue(anvil.getSlot(result).getItem().isEmpty()
                && player.getInventory().countItem(netherite) == 0
                && anvil.getSlot(2).getItem().is(Items.NETHERITE_INGOT),
            "a netherite halberd may not be smithed before Master Armoury, and nothing is consumed");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.learnTech("master_armoury");
        anvil.slotsChanged(anvil.getSlot(0).container);
        helper.assertTrue(anvil.getSlot(result).getItem().is(netherite),
            "after Master Armoury the upgrade is offered");
        anvil.clicked(result, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
        helper.assertTrue(player.getInventory().countItem(netherite) == 1,
            "and it can be taken (shift-click)");
        helper.succeed();
    }

    // ------------------------------------------------------------ fixtures

    private static void fillPatrolMap(AbstractContainerMenu inv) {
        inv.getSlot(1).set(new ItemStack(Items.PAPER));
        inv.getSlot(2).set(new ItemStack(Items.PAPER));
        inv.getSlot(3).set(new ItemStack(Items.STICK));
        inv.getSlot(4).set(new ItemStack(Items.RED_DYE));
    }

    private static void clear(AbstractContainerMenu inv) {
        for (int i = 1; i <= 4; i++) {
            inv.getSlot(i).set(ItemStack.EMPTY);
        }
    }

    private static void founding(DevelopmentState state) {
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
    }

    private static ServerPlayer playerAt(GameTestHelper helper, Fixture f) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
        BlockPos at = f.hearth.getBlockPos();
        player.teleportTo(at.getX() + 1.5D, at.getY(), at.getZ() + 0.5D);
        return player;
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(relative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP = Item.class;
}

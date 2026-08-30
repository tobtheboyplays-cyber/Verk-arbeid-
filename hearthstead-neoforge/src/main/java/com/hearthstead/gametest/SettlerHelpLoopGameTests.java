package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.AcquireRequestedEquipmentGoal;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Exact-one and negative-path wall for the player-to-settler help loop. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SettlerHelpLoopGameTests {

    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Help Loop",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 7;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity employed(GameTestHelper helper,
                                           Settlement settlement,
                                           BuildingType type) {
        Building workplace = GameTestFixtures.register(helper, settlement,
            type, 2, 2);
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 6));
        settler.setSettlerName("Eira");
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), "Eira", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                workplace, settler).ok(),
            "fixture employment must succeed");
        return settler;
    }

    @GameTest(template = "empty16", batch = "settler_help", timeoutTicks = 100)
    public void directInventoryToolSwapsWithoutLossOrDuplication(
        GameTestHelper helper) {
        SettlerEntity lumberer = employed(helper, settlement(helper),
            BuildingType.LUMBER_CAMP);
        lumberer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.STICK));
        lumberer.bag.setItem(0, new ItemStack(Items.STONE_AXE));

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(lumberer.getX(), lumberer.getY(), lumberer.getZ());
        SettlerInventoryMenu menu = new SettlerInventoryMenu(1,
            player.getInventory(), lumberer);
        menu.slotsChanged(lumberer.bag);

        helper.assertTrue(lumberer.getMainHandItem().is(Items.STONE_AXE),
            "the matching physical bag tool must become the held work tool");
        helper.assertTrue(lumberer.bag.getItem(0).is(Items.STICK)
                && lumberer.bag.getItem(0).getCount() == 1,
            "the displaced wrong item must occupy the exact vacated bag slot");
        helper.assertTrue(lumberer.requestedEquipmentIcon().isEmpty(),
            "the request projection must disappear on the same reconciliation");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "settler_help", timeoutTicks = 100)
    public void pickupGoalTakesOnlyMatchingNeedAtAnimationContact(
        GameTestHelper helper) {
        SettlerEntity farmer = employed(helper, settlement(helper),
            BuildingType.FARMHOUSE);
        ItemEntity irrelevant = new ItemEntity(helper.getLevel(), farmer.getX() + 0.4,
            farmer.getY(), farmer.getZ(), new ItemStack(Items.DIRT, 3));
        ItemEntity hoe = new ItemEntity(helper.getLevel(), farmer.getX() + 0.7,
            farmer.getY(), farmer.getZ(), new ItemStack(Items.IRON_HOE));
        helper.getLevel().addFreshEntity(irrelevant);
        helper.getLevel().addFreshEntity(hoe);

        AcquireRequestedEquipmentGoal goal =
            new AcquireRequestedEquipmentGoal(farmer);
        helper.assertTrue(goal.canUse(),
            "a visible serviceable hoe matching the active need must be selected");
        goal.start();
        goal.tick(); // enters the one-shot; no item transfer yet
        helper.assertTrue(farmer.getMainHandItem().isEmpty() && hoe.isAlive(),
            "the tool must remain a world entity before animation contact");
        for (int tick = 0; tick < AcquireRequestedEquipmentGoal.CONTACT_TICK;
             tick++) {
            goal.tick();
        }

        helper.assertTrue(farmer.getMainHandItem().is(Items.IRON_HOE)
                && !hoe.isAlive(),
            "exactly one hoe must move world-to-hand on the contact tick");
        helper.assertTrue(irrelevant.isAlive()
                && irrelevant.getItem().is(Items.DIRT)
                && irrelevant.getItem().getCount() == 3,
            "irrelevant dropped items must never be vacuumed or mutated");
        helper.assertTrue(EquipmentRequests.refreshFor(helper.getLevel(), farmer)
                == null && farmer.requestedEquipmentIcon().isEmpty(),
            "physical fulfilment must retire both request and overhead icon");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "settler_help", timeoutTicks = 100)
    public void irrelevantGroundItemsCannotStartPickupGoal(GameTestHelper helper) {
        SettlerEntity farmer = employed(helper, settlement(helper),
            BuildingType.FARMHOUSE);
        ItemEntity dirt = new ItemEntity(helper.getLevel(), farmer.getX() + 0.5,
            farmer.getY(), farmer.getZ(), new ItemStack(Items.DIRT, 64));
        helper.getLevel().addFreshEntity(dirt);

        AcquireRequestedEquipmentGoal goal =
            new AcquireRequestedEquipmentGoal(farmer);
        helper.assertFalse(goal.canUse(),
            "an active hoe request must not turn arbitrary nearby drops into targets");
        helper.assertTrue(dirt.isAlive() && dirt.getItem().getCount() == 64
                && farmer.getMainHandItem().isEmpty(),
            "negative selection must leave world and worker inventories unchanged");
        helper.succeed();
    }
}

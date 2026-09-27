package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.BuildingPlanItem;
import com.hearthstead.network.BuilderActionPayload;
import com.hearthstead.network.BuilderNetwork;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintLibrary;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuilderUnlocks;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Building Plans (building-plan lane, 26 Sep): an accepted PLACE order made
 * with a plan in hand uses up exactly one plan; a validate-only or refused
 * order, a plan for another building and a creative player never lose one.
 * The order itself is the Builder's normal, re-validated PLACE.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuildingPlanGameTests {

    private static final String BLUEPRINT = "house_cottage";
    private static final BlockPos ORIGIN = new BlockPos(8, 1, 8);
    private static final BlockPos HEARTH = new BlockPos(16, 1, 3);

    private record Setup(BuilderTestKit.Arena arena, ServerPlayer player, BlockPos origin) {
    }

    private static Setup setup(GameTestHelper helper, ItemStack inHand) {
        return setup(helper, inHand, true);
    }

    private static Setup setup(GameTestHelper helper, ItemStack inHand, boolean learnHome) {
        Blueprint blueprint = BlueprintLibrary.get(helper.getLevel().getServer(), BLUEPRINT);
        helper.assertTrue(blueprint != null, BLUEPRINT + " must load from the data pack");
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, blueprint.sizeY() + 3);
        // FOUNDATION_READY needs a bound Banner at the actual settlement
        // center and three residents. Keep it outside the Cottage footprint.
        arena.settlement().center = helper.absolutePos(HEARTH);
        for (int i = 0; i < 3; i++) {
            SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(24 + i, 1, 4));
            resident.bindTo(arena.settlement().id, arena.settlement().center);
            resident.setNoAi(true);
            arena.settlement().putRecord(resident.getUUID(), "Plan resident " + i, Profession.NONE);
        }
        BuilderTestKit.researchBuildersHut(helper, arena, HEARTH);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos c = arena.settlement().center;
        player.moveTo(c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, inHand);
        Setup setup = new Setup(arena, player, helper.absolutePos(ORIGIN));
        if (learnHome) purchaseHome(helper, setup);
        return setup;
    }

    private static void purchaseHome(GameTestHelper helper, Setup setup) {
        var settlement = setup.arena().settlement();
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(settlement.center);
        helper.assertTrue(Development.of(helper.getLevel(), settlement).unlocked(DevelopmentNode.SHELTER),
            "founded fixture must own Shelter before paying for Home");
        var inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) inventory.setStackInSlot(slot, ItemStack.EMPTY);
        int slot = 0;
        for (var cost : DevelopmentNode.HOME.costs()) {
            inventory.setStackInSlot(slot++, new ItemStack(cost.item(), cost.count()));
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(), settlement, hearth,
            DevelopmentNode.HOME, Development.revisionOf(helper.getLevel(), settlement));
        helper.assertTrue(result == Development.Result.APPLIED
                && Development.of(helper.getLevel(), settlement).unlocked(DevelopmentNode.HOME),
            "Home must be purchased through its real prerequisite/quest/material gates, got " + result);
        for (int i = 0; i < inventory.getSlots(); i++) {
            helper.assertTrue(inventory.getStackInSlot(i).isEmpty(), "Home consumes its exact supplied price");
        }
        helper.assertTrue(BuilderUnlocks.blueprintLock(helper.getLevel(), settlement,
                BlueprintLibrary.get(helper.getLevel().getServer(), BLUEPRINT)) == null,
            "paid Home plus Builder's Hut must unlock the Cottage blueprint");
    }

    private static void send(ServerPlayer player, BuilderActionPayload.Action action, BlockPos origin) {
        BuilderNetwork.handle(player, new BuilderActionPayload(action, BLUEPRINT, origin, BlockPos.ZERO, 0, false, 0,
            false, BuilderActionPayload.NONE));
    }

    private static int queued(GameTestHelper helper, Setup s) {
        return BuildSiteSavedData.get(helper.getLevel()).jobs(s.arena().settlement().id).size();
    }

    @GameTest(template = "empty32", timeoutTicks = 100, batch = "building_plan_orders")
    public static void building_plan_missing_home_keeps_plan(GameTestHelper helper) {
        ItemStack plans = BuildingPlanItem.of(BuildingType.HOUSE);
        plans.setCount(2);
        Setup s = setup(helper, plans, false);
        helper.assertTrue(!Development.of(helper.getLevel(), s.arena().settlement()).unlocked(DevelopmentNode.HOME)
                && "hearthstead.builder.lock.building".equals(BuilderUnlocks.blueprintLock(helper.getLevel(),
                    s.arena().settlement(), BlueprintLibrary.get(helper.getLevel().getServer(), BLUEPRINT))),
            "Builder's Hut alone must leave the House's own Home knowledge gate locked");
        send(s.player(), BuilderActionPayload.Action.PLACE, s.origin());
        helper.assertTrue(queued(helper, s) == 0 && s.player().getMainHandItem().getCount() == 2,
            "missing Home refuses the otherwise valid order without consuming a plan");
        purchaseHome(helper, s);
        send(s.player(), BuilderActionPayload.Action.PLACE, s.origin());
        helper.assertTrue(queued(helper, s) == 1 && s.player().getMainHandItem().getCount() == 1,
            "paying for Home alone makes the same order succeed and consume exactly one plan");
        helper.succeed();
    }

    @GameTest(template = "empty32", timeoutTicks = 100, batch = "building_plan_orders")
    public static void building_plan_place_consumes_one(GameTestHelper helper) {
        ItemStack plans = BuildingPlanItem.of(BuildingType.HOUSE);
        plans.setCount(2);
        Setup s = setup(helper, plans);
        send(s.player(), BuilderActionPayload.Action.VALIDATE, s.origin());
        helper.assertTrue(queued(helper, s) == 0 && s.player().getMainHandItem().getCount() == 2,
            "a validate-only request neither queues nor uses a plan");
        send(s.player(), BuilderActionPayload.Action.PLACE, s.origin());
        helper.assertTrue(queued(helper, s) == 1, "the House Plan order is accepted (queued " + queued(helper, s) + ")");
        ItemStack left = s.player().getMainHandItem();
        helper.assertTrue(left.getItem() instanceof BuildingPlanItem && left.getCount() == 1,
            "exactly one plan is used up, " + left.getCount() + " left");
        helper.succeed();
    }

    @GameTest(template = "empty32", timeoutTicks = 100, batch = "building_plan_orders")
    public static void building_plan_refused_keeps_plan(GameTestHelper helper) {
        ItemStack plans = BuildingPlanItem.of(BuildingType.HOUSE);
        plans.setCount(2);
        Setup s = setup(helper, plans);
        // Far outside the settlement and its loaded ground: the Builder refuses.
        send(s.player(), BuilderActionPayload.Action.PLACE, s.origin().offset(400, 0, 400));
        helper.assertTrue(queued(helper, s) == 0, "an order far outside the town is refused");
        helper.assertTrue(s.player().getMainHandItem().getCount() == 2, "a refused order never uses a plan");
        helper.succeed();
    }

    @GameTest(template = "empty32", timeoutTicks = 100, batch = "building_plan_orders")
    public static void building_plan_other_type_keeps_plan(GameTestHelper helper) {
        Setup s = setup(helper, BuildingPlanItem.of(BuildingType.SMITHY));
        send(s.player(), BuilderActionPayload.Action.PLACE, s.origin());
        helper.assertTrue(queued(helper, s) == 1, "the order itself is accepted");
        helper.assertTrue(s.player().getMainHandItem().getCount() == 1,
            "a Smithy Plan is not used up by a House order");
        helper.succeed();
    }

    @GameTest(template = "empty32", timeoutTicks = 100, batch = "building_plan_orders")
    public static void building_plan_creative_keeps_plan(GameTestHelper helper) {
        Setup s = setup(helper, BuildingPlanItem.of(BuildingType.HOUSE));
        s.player().getAbilities().instabuild = true;
        send(s.player(), BuilderActionPayload.Action.PLACE, s.origin());
        helper.assertTrue(queued(helper, s) == 1, "the creative order is accepted");
        helper.assertTrue(s.player().getMainHandItem().getCount() == 1, "creative players keep their plan");
        helper.succeed();
    }
}

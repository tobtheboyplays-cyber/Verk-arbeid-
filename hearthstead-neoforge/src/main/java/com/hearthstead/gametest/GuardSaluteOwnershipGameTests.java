package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardSaluteGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/** Regression tests for ordered-post gating and selector-owned salute lookup. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GuardSaluteOwnershipGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40, batch = "guard_salute_ownership")
    public void orderedPostBlocksFirstScanAndRefreshesAfterArrival(GameTestHelper helper) {
        Fixture f = fixture(helper);
        var level = helper.getLevel();
        var goal = new GuardSaluteGoal(f.guard);
        helper.assertTrue(goal.canUse(), "control: nearby visible player can be greeted without a post");
        var employer = f.settlement.buildings.stream()
            .filter(b -> b.type == BuildingType.BARRACKS).findFirst().orElseThrow();
        BlockPos post = helper.absolutePos(new BlockPos(8, 1, 13));
        var order = f.settlement.guardOrders.orderForMutation(f.settlement.id,
            f.guard.getUUID(), level.dimension().location()).orElseThrow();
        helper.assertTrue(order.issueStand(post, net.minecraft.core.Direction.SOUTH,
            com.hearthstead.settlement.state.GuardOrder.DEFAULT_LEASH_RADIUS,
            f.player.getUUID(), employer.id, level.getGameTime()), "fixture: issue Stand Post");
        helper.assertTrue(com.hearthstead.settlement.guard.GuardAssignmentService
            .validate(level, f.settlement, f.guard, false).valid(), "fixture: post is valid");
        var posted = new GuardSaluteGoal(f.guard);
        helper.assertTrue(!posted.canUse(), "first scan must reject greeting while away from ordered post");
        GameTestTicks.at(helper, 5, () -> {
            f.guard.setPos(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D);
            f.player.setPos(post.getX() + 0.5D, post.getY(), post.getZ() + 2.0D);
            boolean greeting = false;
            for (int i = 0; i < 5; i++) greeting |= posted.canUse();
            helper.assertTrue(greeting, "cache must refresh after arrival and permit the nearby player");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "guard_salute_ownership")
    public void lookupTracksSelectorMembershipRatherThanConstructedGoals(GameTestHelper helper) {
        Fixture f = fixture(helper);
        GuardSaluteGoal registered = GuardSaluteGoal.of(f.guard);
        helper.assertTrue(registered != null, "entity registers its salute goal");
        new GuardSaluteGoal(f.guard);
        helper.assertTrue(GuardSaluteGoal.of(f.guard) == registered,
            "an unattached goal must not replace the selector-owned goal used by patrols");
        f.guard.goalSelector.removeGoal(registered);
        helper.assertTrue(GuardSaluteGoal.of(f.guard) == null,
            "removing the selector goal must leave no retained lookup entry");
        helper.succeed();
    }

    private record Fixture(Settlement settlement, SettlerEntity guard, ServerPlayer player) {
    }

    @SuppressWarnings("removal")
    private static Fixture fixture(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Salutholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        Building barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 0, 0);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 6));
        guard.bindTo(settlement.id, settlement.center);
        guard.setSettlerName("Osric");
        guard.setNoAi(true);
        guard.setHunger(100.0F);
        settlement.putRecord(guard.getUUID(), "Osric", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(), "hire guard");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        // Face south, toward where the player stands.
        guard.setYRot(0.0F);
        guard.setYHeadRot(0.0F);
        guard.setYBodyRot(0.0F);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos stand = helper.absolutePos(new BlockPos(8, 1, 9));
        player.setPos(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
        return new Fixture(settlement, guard, player);
    }
}

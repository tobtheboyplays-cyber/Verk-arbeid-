package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): the guard menu buttons with no GameTest yet,
 * through the real network handler and an open settler session (batch
 * {@code scenario_guard_menu}): Defend the Hearth, adding and removing
 * patrol points, the loop / ping-pong toggle, a one-point route refused,
 * Start Patrol, Clear Order, and a second Clear refused.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioGuardMenuGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "scenario_guard_menu")
    public void everyGuardMenuButtonDoesWhatItSays(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y < 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Menuholm", helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        Building barracks = GameTestFixtures.register(helper, s, BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 6));
        guard.setSettlerName("Ward");
        guard.bindTo(s.id, s.center);
        s.putRecord(guard.getUUID(), "Ward", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, barracks, guard).ok(), "fixture: guard hired");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        stand(helper, player, 7, 7);
        // As in play: the menu is used on a guard standing on the ground, not in its spawn tick.
        com.hearthstead.gametest.GameTestTicks.at(helper, 20, () -> buttons(helper, s, guard, player));
    }

    private static void buttons(GameTestHelper helper, Settlement s, SettlerEntity guard, ServerPlayer player) {
        UUID session = SettlerNetwork.openFor(player, guard);
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.DEFEND_HEARTH);
        GuardOrder defend = order(s, guard);
        helper.assertTrue(defend != null, "Defend the Hearth creates an order");
        helper.assertTrue(defend.mode() == GuardOrder.Mode.STAND_POST && defend.pos().isPresent()
                && defend.pos().get().distSqr(s.center) <= 16 * 16,
            "Defend the Hearth stands the guard at a post by the Hearth: " + defend.mode() + " " + defend.pos());

        stand(helper, player, 4, 10);
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.ADD_PATROL_POINT);
        stand(helper, player, 12, 10);
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.ADD_PATROL_POINT);
        helper.assertTrue(order(s, guard).patrolPoints().size() == 2, "two patrol points set");

        GuardOrder.Traversal before = order(s, guard).traversal();
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.TOGGLE_TRAVERSAL);
        helper.assertTrue(order(s, guard).traversal() != before, "the toggle switches loop / ping-pong");

        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.REMOVE_PATROL_POINT);
        helper.assertTrue(order(s, guard).patrolPoints().size() == 1, "Remove Point drops the last point");
        int revision = order(s, guard).revision();
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.START_PATROL);
        helper.assertTrue(order(s, guard).mode() != GuardOrder.Mode.PATROL_ROUTE && order(s, guard).revision() == revision,
            "a one-point route is refused and changes nothing");

        // Stay within arm's reach of the guard, as a player at the menu does.
        stand(helper, player, 9, 11);
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.ADD_PATROL_POINT);
        helper.assertTrue(order(s, guard).patrolPoints().size() == 2,
            "a second point again: " + order(s, guard).patrolPoints());
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.START_PATROL);
        helper.assertTrue(order(s, guard).mode() == GuardOrder.Mode.PATROL_ROUTE, "Start Patrol walks the route: "
            + order(s, guard).mode() + " points " + order(s, guard).patrolPoints());

        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.CLEAR_ORDER);
        helper.assertTrue(order(s, guard).mode() == GuardOrder.Mode.NONE, "Clear Order returns to default duty");
        revision = order(s, guard).revision();
        act(helper, s, guard, player, session, GuardOrderActionPayload.Kind.CLEAR_ORDER);
        helper.assertTrue(order(s, guard).mode() == GuardOrder.Mode.NONE && order(s, guard).revision() == revision,
            "a second Clear with nothing active is refused and changes nothing");

        SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        helper.succeed();
    }

    private static void stand(GameTestHelper helper, ServerPlayer player, int x, int z) {
        BlockPos at = helper.absolutePos(new BlockPos(x, 1, z));
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
    }

    /** The guard's order; a missing one fails the test (never throws: a throw in a scheduled step stops the server). */
    private static GuardOrder order(Settlement s, SettlerEntity guard) {
        GuardOrder order = s.guardOrders.order(guard.getUUID()).orElse(null);
        if (order == null) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("the guard has no order at all");
        }
        return order;
    }

    private static void act(GameTestHelper helper, Settlement s, SettlerEntity guard, ServerPlayer player,
                            UUID session, GuardOrderActionPayload.Kind kind) {
        int revision = s.guardOrders.order(guard.getUUID()).map(GuardOrder::revision).orElse(0);
        GuardOrderNetwork.handle(player, new GuardOrderActionPayload(guard.getId(), guard.getUUID(), session,
            s.id, kind, revision));
    }
}

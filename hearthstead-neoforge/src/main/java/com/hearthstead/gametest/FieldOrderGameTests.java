package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrderRules.Refusal;
import com.hearthstead.settlement.guard.FieldOrders;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * R/G field orders, end to end on the server: soldiers walk to their line
 * slots, charge/focus pick only the aimed enemy, orders lapse after the fight
 * or on "back to posts", and requests are refused (visibly) when they should be.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FieldOrderGameTests {

    @GameTest(template = "empty16", timeoutTicks = 400, batch = "field_orders_line")
    public void knightsReachTheirLineSlots(GameTestHelper helper) {
        Fixture f = fixture(helper);
        List<SettlerEntity> knights = List.of(knight(helper, f, 3, 13), knight(helper, f, 8, 13),
            knight(helper, f, 13, 13));
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 5));
        // Order once the spawned bodies have landed, as in play.
        helper.runAfterDelay(5, () -> orderLine(helper, f, knights, center));
    }

    private static void orderLine(GameTestHelper helper, Fixture f, List<SettlerEntity> knights,
                                  BlockPos center) {
        FieldOrders.Result result = FieldOrders.issue(f.player, request(Group.KNIGHTS, Kind.LINE, center, 4, 3, -1));
        helper.assertTrue(result.accepted(), "line order accepted: " + result.refusal());
        helper.assertTrue(result.heard() == 3 && result.total() == 3, "3/3 heard, got " + result.heard()
            + "/" + result.total());
        helper.assertTrue(result.unreachable() == 0, "open floor: every slot reachable");
        Set<BlockPos> slots = new HashSet<>();
        for (SettlerEntity knight : knights) {
            FieldOrders.Assignment a = FieldOrders.assignment(knight);
            helper.assertTrue(a != null, "each knight holds an assignment");
            helper.assertTrue(a.slot().getZ() == center.getZ() && Math.abs(a.slot().getX() - center.getX()) <= 1,
                "slot on the north-facing line: " + a.slot());
            slots.add(a.slot());
            helper.assertTrue(BannerTeams.active(knight) != null, "posts and alarm goals yield to the order");
        }
        helper.assertTrue(slots.size() == 3, "distinct slots");
        // Line order preserved: west knight takes the west slot.
        helper.assertTrue(FieldOrders.assignment(knights.get(0)).slot().getX() == center.getX() - 1,
            "no crossing: west knight to west slot");
        helper.succeedWhen(() -> {
            for (SettlerEntity knight : knights) {
                BlockPos slot = FieldOrders.assignment(knight).slot();
                helper.assertTrue(knight.blockPosition().distSqr(slot) <= 2.25D,
                    knight.getSettlerName() + " at " + knight.blockPosition() + " not yet at " + slot);
            }
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "field_orders_focus")
    public void focusTargetsOnlyTheAimedEnemy(GameTestHelper helper) {
        Fixture f = fixture(helper);
        SettlerEntity knightA = knight(helper, f, 4, 8);
        SettlerEntity knightB = knight(helper, f, 6, 8);
        Zombie near = zombie(helper, 5, 11);
        Zombie aimed = zombie(helper, 12, 3);
        FieldOrders.Result result = FieldOrders.issue(f.player,
            request(Group.KNIGHTS, Kind.ATTACK, null, 0, 1, aimed.getId()));
        helper.assertTrue(result.accepted(), "charge accepted: " + result.refusal());
        for (SettlerEntity knight : List.of(knightA, knightB)) {
            helper.assertTrue(knight.getTarget() == aimed, "charge sets the aimed enemy as target");
            helper.assertTrue(!FieldOrders.allowsTarget(knight, near)
                    || knight.distanceToSqr(near) <= 9.0D,
                "other enemies only in self-defence reach");
            helper.assertTrue(FieldOrders.allowsTarget(knight, aimed), "aimed enemy allowed");
        }
        helper.runAfterDelay(40, () -> {
            for (SettlerEntity knight : List.of(knightA, knightB)) {
                helper.assertTrue(knight.getTarget() == aimed || knight.getTarget() == null
                        || knight.distanceToSqr(knight.getTarget()) <= 9.0D,
                    "target selection never drifts to a distant other enemy: " + knight.getTarget());
            }
            aimed.kill();
        });
        helper.runAfterDelay(80, () -> {
            for (SettlerEntity knight : List.of(knightA, knightB)) {
                helper.assertTrue(FieldOrders.assignment(knight) == null,
                    "charge with no earlier order ends when the enemy is down");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "field_orders_revert")
    public void chargeRevertsToTheLineWhenTheEnemyFalls(GameTestHelper helper) {
        Fixture f = fixture(helper);
        SettlerEntity knight = knight(helper, f, 8, 12);
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 10));
        helper.assertTrue(FieldOrders.issue(f.player, request(Group.KNIGHTS, Kind.LINE, center, 4, 1, -1))
            .accepted(), "line accepted");
        Zombie enemy = zombie(helper, 12, 3);
        helper.runAfterDelay(5, () -> {
            FieldOrders.Result charge = FieldOrders.issue(f.player,
                request(Group.KNIGHTS, Kind.ATTACK, null, 0, 1, enemy.getId()));
            helper.assertTrue(charge.accepted(), "charge accepted: " + charge.refusal());
            helper.assertTrue(FieldOrders.assignment(knight).order.kind == Kind.ATTACK, "charging");
            enemy.kill();
        });
        helper.runAfterDelay(40, () -> {
            FieldOrders.Assignment back = FieldOrders.assignment(knight);
            helper.assertTrue(back != null && back.order.kind == Kind.LINE,
                "back to the line after the target falls: " + (back == null ? "none" : back.order.kind));
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 1100, batch = "field_orders_expiry")
    public void ordersExpireAfterTheFightAndOnReturnToPosts(GameTestHelper helper) {
        Fixture f = fixture(helper);
        SettlerEntity lineKnight = knight(helper, f, 5, 12);
        long now = helper.getLevel().getGameTime();
        f.settlement.alertUntilGameTime = now + 20; // the alarm is ringing: a raid order
        FieldOrders.Result line = FieldOrders.issue(f.player,
            request(Group.KNIGHTS, Kind.LINE, helper.absolutePos(new BlockPos(5, 1, 8)), 4, 1, -1));
        helper.assertTrue(line.accepted(), "raid order accepted");
        helper.runAfterDelay(300, () -> helper.assertTrue(FieldOrders.assignment(lineKnight) != null,
            "the order holds through the grace period after the alarm stops"));
        // All callbacks are scheduled up front: scheduling from inside a
        // callback mutates the test's tick map while it is being iterated.
        final SettlerEntity[] other = {null};
        helper.runAfterDelay(320, () -> {
            // Back to posts clears a second, fresh order at once.
            other[0] = knight(helper, f, 11, 12);
            FieldOrders.Result again = FieldOrders.issue(f.player,
                request(Group.KNIGHTS, Kind.LINE, helper.absolutePos(new BlockPos(11, 1, 8)), 4, 1, -1));
            helper.assertTrue(again.accepted(), "second order: " + again.refusal());
        });
        helper.runAfterDelay(326, () -> {
            FieldOrders.Result back = FieldOrders.issue(f.player,
                request(Group.KNIGHTS, Kind.RETURN, null, 0, 1, -1));
            helper.assertTrue(back.accepted(), "back to posts accepted");
            helper.assertTrue(FieldOrders.assignment(other[0]) == null && FieldOrders.assignment(lineKnight) == null,
                "back to posts clears every knight's order");
            helper.assertTrue(BannerTeams.active(other[0]) == null, "smart default defence resumes");
        });
        helper.runAfterDelay(340, () -> {
            // A separate raid order lapses by itself once the grace period passes.
            f.settlement.alertUntilGameTime = helper.getLevel().getGameTime() + 10;
            FieldOrders.Result raid = FieldOrders.issue(f.player,
                request(Group.KNIGHTS, Kind.LINE, helper.absolutePos(new BlockPos(5, 1, 8)), 4, 1, -1));
            helper.assertTrue(raid.accepted(), "raid order accepted: " + raid.refusal());
        });
        helper.runAfterDelay(900, () -> helper.succeedWhen(() ->
            helper.assertTrue(FieldOrders.assignment(lineKnight) == null, "order lapsed after the fight")));
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "field_orders_authority")
    public void requestsAreCheckedOnTheServer(GameTestHelper helper) {
        Fixture f = fixture(helper);
        SettlerEntity knight = knight(helper, f, 8, 12);
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 6));

        // Spectators cannot command. The helper's mock player hard-codes
        // isSpectator()=false, so use a real, mode-aware server player.
        ServerPlayer watcher = modeAwarePlayer(helper, knight.getX() + 1, knight.getY(), knight.getZ());
        helper.assertTrue(watcher.gameMode.changeGameModeForPlayer(GameType.SPECTATOR) && watcher.isSpectator(),
            "fixture: a real spectator");
        FieldOrders.Result spectator = FieldOrders.issue(watcher, request(Group.KNIGHTS, Kind.LINE, center, 4, 1, -1));
        helper.assertTrue(spectator.refusal() == Refusal.NOT_ALLOWED, "spectator refused: " + spectator.refusal());
        helper.assertTrue(FieldOrders.assignment(knight) == null, "no order from a refused request");

        // A settler is not an enemy: a forged charge is refused.
        FieldOrders.Result forged = FieldOrders.issue(f.player,
            request(Group.KNIGHTS, Kind.ATTACK, null, 0, 1, knight.getId()));
        helper.assertTrue(forged.refusal() == Refusal.INVALID_ENEMY, "forged enemy refused: " + forged.refusal());

        // Melee cannot be sent up a tower.
        FieldOrders.Result wrong = FieldOrders.issue(f.player,
            request(Group.KNIGHTS, Kind.HIGH_GROUND, center, 0, 1, -1));
        helper.assertTrue(wrong.refusal() == Refusal.WRONG_GROUP, "wrong group refused: " + wrong.refusal());

        helper.runAfterDelay(6, () -> {
            // Out of earshot: nobody hears, nothing silently happens.
            f.player.setPos(knight.getX() + 55, knight.getY(), knight.getZ());
            FieldOrders.Result far = FieldOrders.issue(f.player, request(Group.KNIGHTS, Kind.LINE,
                BlockPos.containing(f.player.position()), 4, 1, -1));
            helper.assertTrue(far.refusal() == Refusal.NOBODY_HEARD && far.total() == 1,
                "out of earshot is reported as 0/1: " + far.refusal() + " " + far.total());
            helper.assertTrue(FieldOrders.assignment(knight) == null, "no one moved");
        });
        helper.runAfterDelay(12, () -> {
            // Far from any Banner: no settlement to command.
            f.player.setPos(knight.getX() + 400, knight.getY(), knight.getZ());
            FieldOrders.Result lost = FieldOrders.issue(f.player,
                request(Group.KNIGHTS, Kind.RETURN, null, 0, 1, -1));
            helper.assertTrue(lost.refusal() == Refusal.NO_SETTLEMENT, "no settlement: " + lost.refusal());
        });
        helper.runAfterDelay(18, () -> {
            // Co-op: the latest order wins, whoever gives it; spam is rate limited.
            f.player.setPos(knight.getX() + 1, knight.getY(), knight.getZ());
            ServerPlayer friend = player(helper, knight.getX() - 1, knight.getY(), knight.getZ());
            helper.assertTrue(FieldOrders.issue(f.player, request(Group.KNIGHTS, Kind.LINE, center, 4, 1, -1))
                .accepted(), "first commander");
            FieldOrders.Result spam = FieldOrders.issue(f.player,
                request(Group.KNIGHTS, Kind.LINE, center, 4, 1, -1));
            helper.assertTrue(spam.refusal() == Refusal.TOO_FAST, "rate limited: " + spam.refusal());
            FieldOrders.Result second = FieldOrders.issue(friend,
                request(Group.KNIGHTS, Kind.FOLLOW, friend.blockPosition(), 0, 1, -1));
            helper.assertTrue(second.accepted(), "second commander accepted: " + second.refusal());
            FieldOrders.Assignment now = FieldOrders.assignment(knight);
            helper.assertTrue(now != null && now.order.kind == Kind.FOLLOW
                && now.order.issuer.equals(friend.getUUID()), "latest order wins");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(Settlement settlement, Building barracks, ServerPlayer player) {
    }

    private static Fixture fixture(GameTestHelper helper) {
        FieldOrders.resetForTests(helper.getLevel().getServer());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 5; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Fieldholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        Building barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 0, 0);
        ServerPlayer player = player(helper, helper.absolutePos(new BlockPos(8, 1, 14)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(8, 1, 14)).getY(), helper.absolutePos(new BlockPos(8, 1, 14)).getZ() + 0.5D);
        return new Fixture(settlement, barracks, player);
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, double x, double y, double z) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(x, y, z);
        return player;
    }

    private static ServerPlayer modeAwarePlayer(GameTestHelper helper, double x, double y, double z) {
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "field-order-watcher");
        net.minecraft.server.network.CommonListenerCookie cookie =
            net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile,
            cookie.clientInformation());
        net.minecraft.network.Connection connection =
            new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(x, y, z);
        return player;
    }

    private static final List<String> NAMES = new ArrayList<>(List.of("Aldric", "Bryn", "Cedric", "Dunstan"));

    private static SettlerEntity knight(GameTestHelper helper, Fixture f, int x, int z) {
        SettlerEntity knight = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        knight.bindTo(f.settlement.id, f.settlement.center);
        String name = NAMES.get(Math.floorMod(x * 31 + z, NAMES.size())) + " " + x + "-" + z;
        knight.setSettlerName(name);
        f.settlement.putRecord(knight.getUUID(), name, com.hearthstead.entity.Profession.NONE);
        Employment.Hired hired = Employment.hire(helper.getLevel(), f.settlement, f.barracks, knight);
        helper.assertTrue(hired.ok(), "fixture hires a knight: " + hired);
        knight.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        knight.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        return knight;
    }

    private static Zombie zombie(GameTestHelper helper, int x, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, 1, z));
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        return zombie;
    }

    private static FieldOrderRequestPayload request(Group group, Kind kind, BlockPos pos, int octant, int width,
                                                    int enemyId) {
        return new FieldOrderRequestPayload(group.wireId(), kind.wireId(),
            pos == null ? FieldOrderRequestPayload.NO_POS : pos, octant, width, enemyId);
    }
}

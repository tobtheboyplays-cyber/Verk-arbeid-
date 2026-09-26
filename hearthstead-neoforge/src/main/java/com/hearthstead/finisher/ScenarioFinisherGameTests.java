package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): finisher branches the finisher_* tests leave open
 * (batches {@code scenario_finisher_*}): switched off refuses a request,
 * a non-enemy is an invalid target, and switching off while an execution
 * runs lets it finish cleanly (SUNDAY-GATE "off transition untested": the
 * victim dies once and the executor is unlocked, never frozen mid-move).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioFinisherGameTests {

    private record Fixture(Settlement settlement, RaiderEntity raider, ServerPlayer player) {
        void cleanup(GameTestHelper h) {
            var list = h.getLevel().getServer().getPlayerList();
            if (list.getPlayer(player.getUUID()) == player) list.remove(player);
            SettlementSavedData data = SettlementSavedData.get(h.getLevel());
            data.settlements.remove(settlement.id);
            data.setDirty();
        }
    }

    @AfterBatch(batch = "scenario_finisher_disabled")
    public static void afterDisabled(ServerLevel level) {
        HearthsteadServerConfig.FINISHER_ENABLED.set(HearthsteadServerConfig.DEFAULT_FINISHER_ENABLED);
    }

    @AfterBatch(batch = "scenario_finisher_off_mid_move")
    public static void afterOffMidMove(ServerLevel level) {
        HearthsteadServerConfig.FINISHER_ENABLED.set(HearthsteadServerConfig.DEFAULT_FINISHER_ENABLED);
    }

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        SettlementSavedData data = SettlementSavedData.get(h.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Scenario finish", h.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        RaiderEntity raider = h.spawn(ModEntities.RAIDER.get(), new BlockPos(8, 1, 8));
        raider.setVariant(RaiderEntity.Variant.SKIRMISHER);
        raider.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
        raider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0D);
        raider.setHealth(60.0F);
        raider.setNoAi(true);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "scen-finisher"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(),
            cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.setGameMode(GameType.SURVIVAL);
        BlockPos abs = h.absolutePos(BlockPos.ZERO);
        double px = abs.getX() + 6.5;
        double pz = abs.getZ() + 8.5;
        float yaw = FinisherService.yawToward(new net.minecraft.world.phys.Vec3(raider.getX() - px, 0, raider.getZ() - pz));
        p.moveTo(px, abs.getY() + 1, pz, yaw, 10.0F);
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        return new Fixture(settlement, raider, p);
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "scenario_finisher_disabled")
    public static void switchedOffRefusesTheRequest(GameTestHelper h) {
        Fixture f = fixture(h);
        h.runAfterDelay(5, () -> {
            h.assertTrue(FinisherService.forceOpenWindow(f.raider()), "fixture: window opens on stagger");
            HearthsteadServerConfig.FINISHER_ENABLED.set(false);
            FinisherService.Result r = FinisherService.request(f.player(), f.raider());
            HearthsteadServerConfig.FINISHER_ENABLED.set(true);
            h.assertTrue(r == FinisherService.Result.DISABLED, "switched off: DISABLED, got " + r);
            h.assertTrue(FinisherService.executionOf(f.raider()) == null && !FinisherService.isExecuting(f.player()),
                "nothing starts");
            h.assertTrue(f.raider().isAlive(), "the raider is untouched");
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "scenario_finisher_invalid")
    public static void aNonEnemyIsAnInvalidTarget(GameTestHelper h) {
        Fixture f = fixture(h);
        Cow cow = h.spawn(EntityType.COW, new BlockPos(7, 1, 8));
        cow.setNoAi(true);
        h.runAfterDelay(5, () -> {
            FinisherService.Result r = FinisherService.request(f.player(), cow);
            h.assertTrue(r == FinisherService.Result.INVALID_TARGET, "a cow cannot be executed: " + r);
            h.assertTrue(cow.isAlive() && !FinisherService.isExecuting(f.player()), "nothing happens");
            cow.discard();
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_finisher_off_mid_move")
    public static void switchedOffMidMoveTheExecutionStillFinishesCleanly(GameTestHelper h) {
        Fixture f = fixture(h);
        h.runAfterDelay(5, () -> {
            h.assertTrue(FinisherService.forceOpenWindow(f.raider()), "fixture: window opens on stagger");
            FinisherService.Result r = FinisherService.request(f.player(), f.raider());
            h.assertTrue(r == FinisherService.Result.STARTED, "solo request starts: " + r);
            FinisherService.Execution e = FinisherService.executionOf(f.raider());
            h.assertTrue(e != null && e.variant() != null, "a move runs");
            int lock = e.variant().lockTicks();
            HearthsteadServerConfig.FINISHER_ENABLED.set(false);
            h.runAfterDelay(lock + 16, () -> {
                h.assertFalse(f.raider().isAlive(), "the running execution still lands its kill");
                h.assertFalse(FinisherService.isExecuting(f.player()), "the executor is released, not frozen");
                h.assertTrue(FinisherService.request(f.player(), f.raider()) != FinisherService.Result.STARTED,
                    "and nothing new starts while off");
                HearthsteadServerConfig.FINISHER_ENABLED.set(true);
                f.cleanup(h);
                h.succeed();
            });
        });
    }
}

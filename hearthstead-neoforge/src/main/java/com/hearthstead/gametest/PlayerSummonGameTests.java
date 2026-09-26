package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.FieldOrderRules;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.summon.PlayerSummons;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/**
 * "Come to me": a guard runs to the player, waits, and the order clears on
 * timeout; a civilian leaves mid-job and resumes it afterwards with nothing
 * lost or duplicated; the [features] guardCommands switch turns both field
 * orders and summons off.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PlayerSummonGameTests {

    @GameTest(template = "empty16", timeoutTicks = 400, batch = "command_summon")
    public void guardRunsToThePlayerThenTheSummonClearsOnTimeout(GameTestHelper helper) {
        reset(helper);
        PlayerSummons.setWaitTicksForTests(40);
        Arena a = arena(helper);
        SettlerEntity guard = guard(helper, a, 2, 2);
        ServerPlayer player = player(helper, 13, 13);
        // Summon once the spawned guard has landed, as in play. Everything is
        // driven from one onEachTick registered up front: registering tick
        // callbacks from inside runAfterDelay mutates the map being iterated.
        final boolean[] requested = {false};
        final long[] arrivedAt = {-1L};
        helper.onEachTick(() -> {
            if (!requested[0] && helper.getTick() >= 5) {
                requested[0] = true;
                PlayerSummons.Result result = PlayerSummons.request(player, guard.getId(), guard.getUUID());
                helper.assertTrue(result.accepted(), "summon accepted: " + result.refusal());
                helper.assertTrue(PlayerSummons.active(guard) != null && PlayerSummons.active(guard).soldier,
                    "live soldier summon");
            }
            PlayerSummons.Entry entry = PlayerSummons.active(guard);
            if (arrivedAt[0] < 0 && entry != null && entry.arrived()) {
                arrivedAt[0] = helper.getTick();
                helper.assertTrue(guard.distanceTo(player) <= PlayerSummons.ARRIVE_DISTANCE + 0.6D,
                    "arrived beside the player, at " + guard.distanceTo(player));
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(requested[0], "summon issued");
            helper.assertTrue(arrivedAt[0] >= 0, "guard reaches the player");
            helper.assertTrue(arrivedAt[0] <= 205, "guard ran there within 200 ticks, took " + arrivedAt[0]);
            helper.assertTrue(PlayerSummons.active(guard) == null, "summon clears after the wait");
            PlayerSummons.resetForTests(helper.getLevel().getServer());
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "command_summon_civilian")
    public void civilianLeavesMidJobAndResumesItWithNothingLost(GameTestHelper helper) {
        reset(helper);
        PlayerSummons.setWaitTicksForTests(40);
        helper.getLevel().setDayTime(2000);
        Arena a = arena(helper);
        Building camp = GameTestFixtures.register(helper, a.settlement, BuildingType.LUMBER_CAMP, 11, 11);
        BlockPos chestRel = new BlockPos(12, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        BlockPos base = new BlockPos(8, 2, 8);
        helper.setBlock(base.below(), Blocks.DIRT);
        for (int i = 0; i < 4; i++) helper.setBlock(base.above(i), Blocks.OAK_LOG);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) helper.setBlock(base.above(4).offset(dx, 0, dz), Blocks.OAK_LEAVES);
        }
        SettlerEntity ulf = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        ulf.setSettlerName("Ulf");
        ulf.bindTo(a.settlement.id, a.settlement.center);
        a.settlement.putRecord(ulf.getUUID(), "Ulf", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), a.settlement, camp, ulf).ok(), "hire lumberer");
        ulf.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        ServerPlayer player = player(helper, 13, 2);
        final int[] state = {0}; // 0 working, 1 summoned, 2 released
        final int[] chestAtRelease = {-1};
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (state[0] == 0 && tick >= 80) {
                PlayerSummons.Result r = PlayerSummons.request(player, ulf.getId(), ulf.getUUID());
                helper.assertTrue(r.accepted(), "civilian summon accepted: " + r.refusal());
                helper.assertTrue(!PlayerSummons.active(ulf).soldier, "civilian pace");
                state[0] = 1;
            } else if (state[0] == 1 && PlayerSummons.active(ulf) == null) {
                state[0] = 2;
                chestAtRelease[0] = logs(chest);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] == 2, "summon ran and ended");
            int banked = logs(chest);
            helper.assertTrue(banked > chestAtRelease[0], "resumed the job after the summon: banked "
                + banked + " (was " + chestAtRelease[0] + " at release)");
            helper.assertTrue(banked == 4 && ulf.getCarryLoad() == 0,
                "every log banked exactly once: chest=" + banked + " bag=" + ulf.getCarryLoad());
            PlayerSummons.resetForTests(helper.getLevel().getServer());
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "command_switch_off")
    public void switchedOffCommandsAreIgnored(GameTestHelper helper) {
        reset(helper);
        Arena a = arena(helper);
        SettlerEntity guard = guard(helper, a, 6, 6);
        ServerPlayer player = player(helper, 8, 12);
        BlockPos line = helper.absolutePos(new BlockPos(8, 1, 4));
        FieldOrders.Result live = FieldOrders.issue(player, new FieldOrderRequestPayload(
            FieldOrderRules.Group.KNIGHTS.wireId(), FieldOrderRules.Kind.LINE.wireId(), line, 4, 1, -1));
        helper.assertTrue(live.accepted() && FieldOrders.assignment(guard) != null, "order while enabled");

        FieldOrders.setEnabledForTests(false);
        PlayerSummons.setEnabledForTests(false);
        try {
            helper.assertTrue(FieldOrders.assignment(guard) == null && BannerTeams.active(guard) == null,
                "switched off: the live order is ignored, default defence resumes");
            helper.runAfterDelay(5, () -> {
                FieldOrders.Result off = FieldOrders.issue(player, new FieldOrderRequestPayload(
                    FieldOrderRules.Group.KNIGHTS.wireId(), FieldOrderRules.Kind.LINE.wireId(), line, 4, 1, -1));
                helper.assertTrue(off.refusal() == FieldOrderRules.Refusal.DISABLED, "order refused: " + off.refusal());
                PlayerSummons.Result summon = PlayerSummons.request(player, guard.getId(), guard.getUUID());
                helper.assertTrue(summon.refusal() == PlayerSummons.Refusal.DISABLED,
                    "summon refused: " + summon.refusal());
                helper.assertTrue(PlayerSummons.active(guard) == null, "no summon state");
                reset(helper);
                helper.succeed();
            });
        } catch (RuntimeException | AssertionError e) {
            reset(helper);
            throw e;
        }
    }

    // ------------------------------------------------------------------ fixture

    private record Arena(Settlement settlement, Building barracks) {
    }

    private static void reset(GameTestHelper helper) {
        FieldOrders.resetForTests(helper.getLevel().getServer());
        PlayerSummons.resetForTests(helper.getLevel().getServer());
    }

    private static Arena arena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 7; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        BlockPos hearthRel = new BlockPos(2, 1, 13);
        helper.setBlock(hearthRel, com.hearthstead.registry.ModBlocks.HEARTH.get());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Summonholm", helper.absolutePos(hearthRel));
        settlement.radius = 16;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(settlement.center)
                instanceof com.hearthstead.block.HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }
        Building barracks = GameTestFixtures.register(helper, settlement, BuildingType.BARRACKS, 0, 0);
        return new Arena(settlement, barracks);
    }

    private static SettlerEntity guard(GameTestHelper helper, Arena a, int x, int z) {
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        guard.setSettlerName("Osric");
        guard.bindTo(a.settlement.id, a.settlement.center);
        a.settlement.putRecord(guard.getUUID(), "Osric", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), a.settlement, a.barracks, guard).ok(), "hire guard");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        return guard;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, int x, int z) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(new BlockPos(x, 1, z));
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        return player;
    }

    private static int logs(Container container) {
        int logs = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(Items.OAK_LOG)) logs += stack.getCount();
        }
        return logs;
    }
}

package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.revive.ReviveService;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
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
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): the revive branches the existing revive_* tests
 * leave open, all on a settlement whose raid is armed (batches
 * {@code scenario_revive_*}, one per test so no other player counts as a
 * teammate):
 *
 * <ul>
 *   <li>the friends' default ({@code soloDowned=false}): a player with no
 *   teammate near dies normally, and with {@code soloDowned=true} is
 *   downed;</li>
 *   <li>{@code [revive] enabled=false} refuses new downs;</li>
 *   <li>switched off while a player is already down (SUNDAY-GATE "off
 *   transition untested"): the running session still completes safely,
 *   both ways: the teammate revives, or the bleed-out kills. Nobody is left
 *   crawling forever.</li>
 * </ul>
 *
 * Every config change is restored in {@code @AfterBatch}, pass or fail.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioReviveGameTests {
    private static final int SPAWN_GUARD = 65;

    private record Fixture(Settlement settlement, ServerPlayer victim, List<ServerPlayer> others) {
        void cleanup(GameTestHelper h) {
            var list = h.getLevel().getServer().getPlayerList();
            List<ServerPlayer> all = new ArrayList<>(others);
            all.add(victim);
            for (ServerPlayer p : all) {
                if (list.getPlayer(p.getUUID()) == p) list.remove(p);
            }
            SettlementSavedData data = SettlementSavedData.get(h.getLevel());
            data.settlements.remove(settlement.id);
            data.setDirty();
        }

        ServerPlayer teammate() {
            return others.get(0);
        }
    }

    private static Fixture fixture(GameTestHelper h, boolean withTeammate) {
        BlockPos center = h.absolutePos(new BlockPos(8, 1, 8));
        Settlement s = new Settlement(UUID.randomUUID(), "Scenario revive", center);
        s.radius = 12;
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        ServerPlayer victim = survivalPlayer(h, "scen-victim");
        victim.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0.0F, 0.0F);
        List<ServerPlayer> others = new ArrayList<>();
        if (withTeammate) {
            ServerPlayer mate = survivalPlayer(h, "scen-mate");
            mate.moveTo(center.getX() + 1.5D, center.getY(), center.getZ() + 0.5D, 90.0F, 0.0F);
            others.add(mate);
        }
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        RaidAuthorityFixtures.armActive(s, plan, List.of(UUID.randomUUID()));
        return new Fixture(s, victim, others);
    }

    private static ServerPlayer survivalPlayer(GameTestHelper h, String name) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void restoreDefaults() {
        HearthsteadServerConfig.REVIVE_ENABLED.set(HearthsteadServerConfig.DEFAULT_REVIVE_ENABLED);
        HearthsteadServerConfig.REVIVE_SOLO.set(HearthsteadServerConfig.DEFAULT_REVIVE_SOLO);
        ReviveService.overrideTimingsForTest(-1, -1, -1);
    }

    @AfterBatch(batch = "scenario_revive_solo_on")
    public static void afterSoloOn(ServerLevel level) { restoreDefaults(); }
    @AfterBatch(batch = "scenario_revive_off_new")
    public static void afterOffNew(ServerLevel level) { restoreDefaults(); }
    @AfterBatch(batch = "scenario_revive_off_while_down_revived")
    public static void afterOffRevived(ServerLevel level) { restoreDefaults(); }
    @AfterBatch(batch = "scenario_revive_off_while_down_bleeds")
    public static void afterOffBleeds(ServerLevel level) { restoreDefaults(); }

    // ---------------------------------------------------------------- solo --

    @GameTest(template = "empty16", batch = "scenario_revive_solo_default", timeoutTicks = 200)
    public static void aLonePlayerIsNotDownedByDefault(GameTestHelper h) {
        h.assertTrue(!HearthsteadServerConfig.reviveSolo(), "the friends' default is soloDowned=false");
        Fixture f = fixture(h, false);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim().hurt(f.victim().damageSources().generic(), 1000.0F);
            h.assertFalse(ReviveService.isDowned(f.victim()), "nobody near to revive: never downed");
            h.assertFalse(f.victim().isAlive(), "a lone player's lethal hit kills normally");
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "scenario_revive_solo_on", timeoutTicks = 200)
    public static void soloDownedOnDownsALonePlayer(GameTestHelper h) {
        HearthsteadServerConfig.REVIVE_SOLO.set(true);
        Fixture f = fixture(h, false);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim().hurt(f.victim().damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim()) && f.victim().isAlive(),
                "soloDowned=true downs a player even with no teammate near");
            f.victim().kill();
            restoreDefaults();
            f.cleanup(h);
            h.succeed();
        });
    }

    // --------------------------------------------------------- switch off --

    @GameTest(template = "empty16", batch = "scenario_revive_off_new", timeoutTicks = 200)
    public static void switchedOffRefusesNewDowns(GameTestHelper h) {
        HearthsteadServerConfig.REVIVE_ENABLED.set(false);
        Fixture f = fixture(h, true);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim().hurt(f.victim().damageSources().generic(), 1000.0F);
            h.assertFalse(ReviveService.isDowned(f.victim()), "switched off: no new down");
            h.assertFalse(f.victim().isAlive(), "switched off: a lethal hit kills normally");
            restoreDefaults();
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "scenario_revive_off_while_down_revived", timeoutTicks = 300)
    public static void switchedOffWhileDownTheTeammateStillRevives(GameTestHelper h) {
        Fixture f = fixture(h, true);
        ReviveService.overrideTimingsForTest(2_000, 20, -1);
        int g = SPAWN_GUARD;
        h.runAfterDelay(g, () -> {
            f.victim().hurt(f.victim().damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim()), "downed first");
            HearthsteadServerConfig.REVIVE_ENABLED.set(false);
        });
        for (int t = 1; t <= 29; t += 4) {
            h.runAfterDelay(g + t, () -> ReviveService.onUse(f.teammate(), f.victim(), false));
        }
        h.runAfterDelay(g + 40, () -> {
            h.assertFalse(ReviveService.isDowned(f.victim()),
                "the switch refuses new downs only: the running session still ends in a revive");
            h.assertTrue(f.victim().isAlive() && f.victim().getForcedPose() == null, "revived and standing");
            h.assertFalse(f.victim().getPersistentData().getBoolean(ReviveService.PERSIST_KEY), "crash marker cleared");
            restoreDefaults();
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "scenario_revive_off_while_down_bleeds", timeoutTicks = 300)
    public static void switchedOffWhileDownTheBleedOutStillEndsIt(GameTestHelper h) {
        Fixture f = fixture(h, true);
        ReviveService.overrideTimingsForTest(20, 60, -1);
        int g = SPAWN_GUARD;
        h.runAfterDelay(g, () -> {
            f.victim().hurt(f.victim().damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim()), "downed first");
            HearthsteadServerConfig.REVIVE_ENABLED.set(false);
        });
        h.runAfterDelay(g + 40, () -> {
            h.assertFalse(ReviveService.isDowned(f.victim()), "never stuck downed after the switch");
            h.assertFalse(f.victim().isAlive(), "the bleed-out still ends the session in a normal death");
            h.assertFalse(f.victim().getPersistentData().getBoolean(ReviveService.PERSIST_KEY),
                "crash marker cleared after the confirmed death");
            restoreDefaults();
            f.cleanup(h);
            h.succeed();
        });
    }

    // --------------------------------------------------------------- healer --

    /** BATTLE-ROLES "revive path untested": a village Healer walks to a downed player and revives them. */
    @GameTest(template = "empty16", batch = "scenario_revive_healer", timeoutTicks = 600)
    public static void aVillageHealerRevivesADownedPlayer(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR);
        }
        Fixture f = fixture(h, false);
        com.hearthstead.entity.SettlerEntity healer = h.spawn(com.hearthstead.registry.ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        healer.setSettlerName("Mend");
        healer.bindTo(f.settlement().id, f.settlement().center);
        f.settlement().putRecord(healer.getUUID(), "Mend", com.hearthstead.entity.Profession.NONE);
        healer.assignProfession(com.hearthstead.entity.Profession.HEALER);
        h.runAfterDelay(20, () -> h.assertTrue(ReviveService.forceDown(f.victim()) && ReviveService.isDowned(f.victim()),
            "the player is downed at the village"));
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 20, "downed first");
            // No other player is near (solo fixture): only the Healer can have revived them.
            h.assertTrue(!ReviveService.isDowned(f.victim()) && f.victim().isAlive(),
                "the Healer revives the downed player (healer at " + healer.blockPosition() + ", activity "
                    + healer.getActivity() + ", player at " + f.victim().blockPosition() + ")");
            healer.discard();
            f.cleanup(h);
        });
    }
}

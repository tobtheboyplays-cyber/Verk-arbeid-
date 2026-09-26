package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.revive.ReviveService;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Co-op downed / revive with two survival mock players at a settlement whose
 * raid is armed through the strict fixture. Each test has its own batch so no
 * other test's settlement or players can count as "near" (batch prefix
 * {@code revive}). Mock players are not ticked by a connection, so their
 * 60-tick spawn invulnerability is waited out before the lethal hit.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ReviveGameTests {
    private static final int SPAWN_GUARD = 65;

    @GameTest(template = "empty16", batch = "revive_down", timeoutTicks = 200)
    public static void deathDuringRaidBecomesDowned(GameTestHelper h) {
        Fixture f = fixture(h, true);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertTrue(f.victim.isAlive(), "a lethal hit during a raid must not kill");
            h.assertTrue(ReviveService.isDowned(f.victim), "the player must be downed");
            h.assertTrue(f.victim.getForcedPose() == Pose.SWIMMING, "downed players crawl");
            h.assertTrue(Math.abs(f.victim.getHealth() - ReviveService.DOWNED_HEALTH) < 0.01F,
                "downed health pool, got " + f.victim.getHealth());
            h.assertTrue(f.victim.getPersistentData().getBoolean(ReviveService.PERSIST_KEY),
                "crash marker set while down");
            h.assertTrue(ReviveService.tally(h.getLevel().getServer(), f.settlement.id).downs() == 1,
                "raid report hook counts the down");
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "revive_revive", timeoutTicks = 300)
    public static void holdingUseRevivesThePlayer(GameTestHelper h) {
        Fixture f = fixture(h, true);
        ReviveService.overrideTimingsForTest(2_000, 20, -1);
        int g = SPAWN_GUARD;
        h.runAfterDelay(g, () -> {
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim), "downed first");
        });
        // Hold [use]: a ping every 4 ticks, like the client's rightClickDelay.
        for (int t = 1; t <= 29; t += 4) {
            h.runAfterDelay(g + t, () -> ReviveService.onUse(f.helper, f.victim, false));
        }
        h.runAfterDelay(g + 32, () -> {
            h.assertFalse(ReviveService.isDowned(f.victim), "revived after the hold");
            h.assertTrue(f.victim.isAlive(), "alive");
            h.assertTrue(Math.abs(f.victim.getHealth() - 6.0F) < 0.01F,
                "30% of 20 health, got " + f.victim.getHealth());
            h.assertTrue(f.victim.hasEffect(MobEffects.DAMAGE_RESISTANCE), "brief resistance");
            h.assertTrue(f.victim.getForcedPose() == null, "gets up");
            h.assertFalse(f.victim.getPersistentData().getBoolean(ReviveService.PERSIST_KEY),
                "crash marker cleared");
            h.assertTrue(ReviveService.tally(h.getLevel().getServer(), f.settlement.id).revives() == 1,
                "raid report hook counts the revive");
            ReviveService.overrideTimingsForTest(-1, -1, -1);
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "revive_bleed", timeoutTicks = 300)
    public static void bleedOutKillsNormally(GameTestHelper h) {
        Fixture f = fixture(h, true);
        ReviveService.overrideTimingsForTest(20, 60, -1);
        int g = SPAWN_GUARD;
        h.runAfterDelay(g, () -> {
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim), "downed first");
        });
        h.runAfterDelay(g + 10, () -> h.assertTrue(f.victim.isAlive()
            && ReviveService.isDowned(f.victim), "still down halfway through the bleed-out"));
        h.runAfterDelay(g + 30, () -> {
            h.assertFalse(f.victim.isAlive(), "bled out: an ordinary death");
            h.assertFalse(ReviveService.isDowned(f.victim), "no longer downed once dead");
            h.assertTrue(f.victim.getLastDamageSource() != null
                    && f.victim.getLastDamageSource().is(ReviveService.BLEED_OUT),
                "killed by the bleed-out damage type");
            h.assertTrue(ReviveService.tally(h.getLevel().getServer(), f.settlement.id).bleedOuts() == 1,
                "raid report hook counts the bleed-out");
            ReviveService.overrideTimingsForTest(-1, -1, -1);
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "revive_interrupt", timeoutTicks = 300)
    public static void damageInterruptsRevive(GameTestHelper h) {
        Fixture f = fixture(h, true);
        ReviveService.overrideTimingsForTest(2_000, 40, -1);
        int g = SPAWN_GUARD;
        h.runAfterDelay(g, () -> f.victim.hurt(f.victim.damageSources().generic(), 1000.0F));
        for (int t = 1; t <= 13; t += 4) {
            h.runAfterDelay(g + t, () -> ReviveService.onUse(f.helper, f.victim, false));
        }
        h.runAfterDelay(g + 14, () -> {
            h.assertTrue(ReviveService.isDowned(f.victim), "downed");
            h.assertTrue(ReviveService.reviveFraction(f.victim) > 0.2F,
                "revive in progress, got " + ReviveService.reviveFraction(f.victim));
            f.helper.invulnerableTime = 0;
            f.helper.hurt(f.helper.damageSources().generic(), 1.0F);
            h.assertTrue(ReviveService.reviveFraction(f.victim) == 0.0F,
                "taking damage resets the revive");
            h.assertTrue(ReviveService.isDowned(f.victim), "still downed");
            ReviveService.overrideTimingsForTest(-1, -1, -1);
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "revive_noraid", timeoutTicks = 200)
    public static void noDownedStateOutsideRaids(GameTestHelper h) {
        Fixture f = fixture(h, false);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertFalse(f.victim.isAlive(), "no raid: a lethal hit kills normally");
            h.assertFalse(ReviveService.isDowned(f.victim), "never downed outside raids");
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "revive_kill", timeoutTicks = 200)
    public static void killCommandIsNeverBypassed(GameTestHelper h) {
        Fixture f = fixture(h, true);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim.kill();
            h.assertFalse(f.victim.isAlive(), "/kill during a raid still kills");
            h.assertFalse(ReviveService.isDowned(f.victim), "and never downs");
            f.cleanup(h);
            h.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "revive_logout", timeoutTicks = 200)
    public static void loggingOutWhileDownedBleedsOut(GameTestHelper h) {
        Fixture f = fixture(h, true);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim), "downed first");
            h.getLevel().getServer().getPlayerList().remove(f.victim);
            h.assertFalse(f.victim.isAlive(), "logout while down = bled out (drops once)");
            h.assertFalse(ReviveService.isDowned(f.victim), "state cleared");
            f.cleanup(h);
            h.succeed();
        });
    }

    /**
     * Regression (Codex review): a player saved while down whose login
     * bleed-out is refused by another mod must keep the crash marker and be
     * killed by the retry once the refusal stops - never left alive at
     * downed health with the marker gone.
     */
    @GameTest(template = "empty16", batch = "revive_login_refused", timeoutTicks = 300)
    public static void refusedLoginRecoveryKeepsMarkerAndRetries(GameTestHelper h) {
        Fixture f = fixture(h, false);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent>
            refuse = event -> {
                if (event.getEntity() == f.victim) {
                    event.setCanceled(true);
                }
            };
        h.runAfterDelay(SPAWN_GUARD, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, refuse);
            f.victim.setHealth(ReviveService.DOWNED_HEALTH);
            f.victim.getPersistentData().putBoolean(ReviveService.PERSIST_KEY, true);
            ReviveService.onLogin(f.victim);
            h.assertTrue(f.victim.isAlive(), "the refusing listener blocked hit and kill");
            h.assertTrue(f.victim.getPersistentData().getBoolean(ReviveService.PERSIST_KEY),
                "marker kept while death is unconfirmed");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(refuse);
        });
        h.runAfterDelay(SPAWN_GUARD + 45, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(refuse);
            h.assertFalse(f.victim.isAlive(), "the once-per-second retry bled them out");
            h.assertFalse(f.victim.getPersistentData().getBoolean(ReviveService.PERSIST_KEY),
                "marker cleared only after the confirmed death");
            f.cleanup(h);
            h.succeed();
        });
    }

    /**
     * Regression (Codex review): a downed, dragged player takes a lethal hit
     * and another mod cancels the LivingDeathEvent (totem-like, restores
     * health) at NORMAL priority. The downed record, the drag and the crash
     * marker must all survive, because cleanup only runs on a confirmed death.
     */
    @GameTest(template = "empty16", batch = "revive_death_canceled", timeoutTicks = 300)
    public static void canceledDeathKeepsDownedAndDragState(GameTestHelper h) {
        Fixture f = fixture(h, true);
        ReviveService.overrideTimingsForTest(2_000, 60, -1);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> save =
            event -> {
                if (event.getEntity() == f.victim) {
                    f.victim.setHealth(1.0F);
                    event.setCanceled(true);
                }
            };
        int g = SPAWN_GUARD;
        h.runAfterDelay(g, () -> {
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertTrue(ReviveService.isDowned(f.victim), "downed first");
            h.assertTrue(ReviveService.startDrag(f.helper, f.victim), "helper starts dragging");
        });
        h.runAfterDelay(g + 5, () -> {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.bus.api.EventPriority.NORMAL, false,
                net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, save);
            try {
                f.victim.invulnerableTime = 0;
                f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(save);
            }
            h.assertTrue(f.victim.isAlive(), "the other mod saved the player");
            h.assertTrue(ReviveService.isDowned(f.victim), "still downed after a canceled death");
            h.assertTrue(f.helper.getUUID().equals(ReviveService.draggerOf(f.victim)),
                "drag state kept");
            h.assertTrue(f.victim.getPersistentData().getBoolean(ReviveService.PERSIST_KEY),
                "crash marker kept");
            h.assertTrue(ReviveService.tally(h.getLevel().getServer(), f.settlement.id).bleedOuts() == 0,
                "a canceled death is not counted");
        });
        h.runAfterDelay(g + 8, () -> {
            f.victim.invulnerableTime = 0;
            f.victim.hurt(f.victim.damageSources().generic(), 1000.0F);
            h.assertFalse(f.victim.isAlive(), "an uncanceled finishing hit still kills");
            h.assertFalse(ReviveService.isDowned(f.victim), "cleanup ran on the confirmed death");
            h.assertTrue(ReviveService.tally(h.getLevel().getServer(), f.settlement.id).bleedOuts() == 1,
                "the confirmed death is counted once");
            ReviveService.overrideTimingsForTest(-1, -1, -1);
            f.cleanup(h);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(Settlement settlement, ServerPlayer victim, ServerPlayer helper) {
        void cleanup(GameTestHelper h) {
            var list = h.getLevel().getServer().getPlayerList();
            for (ServerPlayer p : List.of(victim, helper)) {
                if (list.getPlayer(p.getUUID()) == p) {
                    list.remove(p);
                }
            }
            SettlementSavedData data = SettlementSavedData.get(h.getLevel());
            data.settlements.remove(settlement.id);
            data.setDirty();
        }
    }

    private static Fixture fixture(GameTestHelper h, boolean raid) {
        BlockPos center = h.absolutePos(new BlockPos(8, 1, 8));
        Settlement s = new Settlement(UUID.randomUUID(), "Revivehold", center);
        s.radius = 12;
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        ServerPlayer victim = survivalPlayer(h, "revive-victim");
        ServerPlayer helper = survivalPlayer(h, "revive-helper");
        victim.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0.0F, 0.0F);
        helper.moveTo(center.getX() + 1.5D, center.getY(), center.getZ() + 0.5D, 90.0F, 0.0F);
        if (raid) {
            RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
            RaidAuthorityFixtures.armActive(s, plan, List.of(UUID.randomUUID()));
        }
        return new Fixture(s, victim, helper);
    }

    /** A registered, non-creative mock player (GameTestHelper's own reports creative). */
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
}

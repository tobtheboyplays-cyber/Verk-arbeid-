package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Sunday owner decision (26 Sep): the co-op server runs with vanilla PvP ON.
 * Player-vs-player fighting must stay between the players: no settler turns
 * on either of them, no ALARM or raid starts, and a downed teammate cannot be
 * finished by another player. PvP is switched on for the test and restored.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PvpSafetyGameTests {
    /** Mock players keep vanilla's 60-tick spawn invulnerability; wait it out. */
    private static final int SPAWN_GUARD = 65;

    @GameTest(template = "empty16", batch = "pvp_safety_no_alarm_no_guard_turn", timeoutTicks = 300)
    public static void playerHittingPlayerNearGuardsStartsNothing(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        boolean pvpBefore = server.isPvpAllowed();
        server.setPvpAllowed(true);
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(new BlockPos(8, 1, 8));
        Settlement s = new Settlement(UUID.randomUUID(), "Pvpholm", center);
        s.radius = 16;
        SettlementSavedData.get(level).settlements.put(s.id, s);
        SettlerEntity guard = settler(level, s, center.offset(2, 0, 0), "Vakt", Profession.GUARD);
        SettlerEntity civilian = settler(level, s, center.offset(-2, 0, 0), "Bonde", Profession.NONE);
        ServerPlayer attacker = survivalPlayer(h, "pvp-attacker");
        ServerPlayer victim = survivalPlayer(h, "pvp-victim");
        attacker.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 1.5D, 180.0F, 0.0F);
        victim.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0.0F, 0.0F);
        Runnable cleanup = () -> {
            var list = server.getPlayerList();
            for (ServerPlayer p : List.of(attacker, victim)) {
                if (list.getPlayer(p.getUUID()) == p) list.remove(p);
            }
            guard.discard();
            civilian.discard();
            SettlementSavedData data = SettlementSavedData.get(level);
            data.settlements.remove(s.id);
            data.setDirty();
            server.setPvpAllowed(pvpBefore);
        };
        h.runAfterDelay(SPAWN_GUARD, () -> {
            float before = victim.getHealth();
            victim.hurt(victim.damageSources().playerAttack(attacker), 4.0F);
            attacker.setLastHurtMob(victim);
            victim.setLastHurtByPlayer(attacker);
            if (!(victim.getHealth() < before)) {
                cleanup.run();
                h.fail("fixture: PvP damage must land (pvp on), health " + before + " -> " + victim.getHealth());
            }
        });
        h.runAfterDelay(SPAWN_GUARD + 5, () -> victim.hurt(victim.damageSources().playerAttack(attacker), 2.0F));
        h.onEachTick(() -> {
            if (h.getTick() <= SPAWN_GUARD) return;
            boolean guardTurned = guard.getTarget() instanceof Player;
            boolean civilianTurned = civilian.getTarget() instanceof Player;
            boolean alarm = s.alertActive(level.getGameTime()) || s.pendingRaid != null;
            if (guardTurned || civilianTurned || alarm) {
                String why = "PvP must stay between players: guardTarget=" + guard.getTarget()
                    + " civilianTarget=" + civilian.getTarget() + " alert=" + s.alertActive(level.getGameTime())
                    + " pendingRaid=" + s.pendingRaid;
                cleanup.run();
                h.fail(why);
                return;
            }
            if (h.getTick() >= SPAWN_GUARD + 120L) {
                Hearthstead.LOGGER.info("HSQA_PVP no_alarm_no_guard_turn ok victimHealth={}", victim.getHealth());
                cleanup.run();
                h.succeed();
            }
        });
    }

    @GameTest(template = "empty16", batch = "pvp_safety_downed_teammate", timeoutTicks = 200)
    public static void aPlayerCannotFinishADownedTeammate(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        boolean pvpBefore = server.isPvpAllowed();
        server.setPvpAllowed(true);
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(new BlockPos(8, 1, 8));
        Settlement s = new Settlement(UUID.randomUUID(), "Fallholm", center);
        s.radius = 12;
        SettlementSavedData.get(level).settlements.put(s.id, s);
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        RaidAuthorityFixtures.armActive(s, plan, List.of(UUID.randomUUID()));
        ServerPlayer downed = survivalPlayer(h, "pvp-downed");
        ServerPlayer other = survivalPlayer(h, "pvp-other");
        downed.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, 0.0F, 0.0F);
        other.moveTo(center.getX() + 1.5D, center.getY(), center.getZ() + 0.5D, 90.0F, 0.0F);
        h.runAfterDelay(SPAWN_GUARD, () -> {
            try {
                downed.hurt(downed.damageSources().generic(), 1000.0F);
                h.assertTrue(ReviveService.isDowned(downed), "fixture: a lethal raid hit downs the player");
                float pool = downed.getHealth();
                downed.hurt(downed.damageSources().playerAttack(other), 1000.0F);
                h.assertTrue(downed.isAlive() && ReviveService.isDowned(downed),
                    "another player must not finish a downed teammate");
                h.assertTrue(downed.getHealth() >= pool - 0.01F,
                    "a player's hit must not drain the downed pool: " + pool + " -> " + downed.getHealth());
                // A player's arrow / thrown item names the player as the source entity too.
                downed.hurt(downed.damageSources().arrow(
                    net.minecraft.world.entity.EntityType.ARROW.create(level), other), 1000.0F);
                h.assertTrue(downed.isAlive() && ReviveService.isDowned(downed),
                    "a player's projectile must not finish a downed teammate either");
            } finally {
                var list = server.getPlayerList();
                for (ServerPlayer p : List.of(downed, other)) {
                    if (list.getPlayer(p.getUUID()) == p) list.remove(p);
                }
                SettlementSavedData data = SettlementSavedData.get(level);
                data.settlements.remove(s.id);
                data.setDirty();
                server.setPvpAllowed(pvpBefore);
            }
            h.succeed();
        });
    }

    private static SettlerEntity settler(ServerLevel level, Settlement settlement, BlockPos at,
                                         String name, Profession profession) {
        SettlerEntity settler = ModEntities.SETTLER.get().create(level);
        settler.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(settler);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        if (profession != Profession.NONE) {
            settler.assignProfession(profession);
        }
        return settler;
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

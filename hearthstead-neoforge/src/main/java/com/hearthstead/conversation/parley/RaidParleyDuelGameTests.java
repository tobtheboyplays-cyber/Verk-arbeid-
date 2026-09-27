package com.hearthstead.conversation.parley;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Codex P2: a parley duel's yield is decided on the damage that really lands
 * (after shield, armour and absorption), never on the raw incoming blow, and
 * each duel ends exactly once. Player at 8/20 yields at 4 (PLAYER_YIELD 0.20);
 * the captain at 14/40 yields at 10 (CAPTAIN_YIELD 0.25).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidParleyDuelGameTests {
    /** Past a fresh ServerPlayer's 60-tick spawn protection, so every hit is real. */
    private static final int READY = 65;

    private record Duel(Settlement settlement, RaiderEntity captain, ServerPlayer player) {
        boolean active() {
            return RaidParley.duelActiveForTests(settlement.id);
        }

        List<Boolean> outcomes() {
            return RaidParley.duelOutcomesForTests(settlement.id);
        }

        void start() {
            RaidParley.startDuelForTests((net.minecraft.server.level.ServerLevel) captain.level(),
                settlement.id, captain, player);
        }

        DamageSource fromCaptain() {
            return player.damageSources().mobAttack(captain);
        }

        DamageSource fromPlayer() {
            return captain.damageSources().playerAttack(player);
        }
    }

    private static Duel arena(GameTestHelper helper, String name) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        RaiderEntity captain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(8, 1, 10));
        captain.setVariant(RaiderEntity.Variant.SKIRMISHER);
        captain.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, true);
        captain.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0D);
        captain.getAttribute(Attributes.ARMOR).setBaseValue(0.0D); // exact numbers below
        captain.setHealth(14.0F);
        captain.setNoAi(true);

        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), name.toLowerCase()), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        BlockPos abs = helper.absolutePos(new BlockPos(8, 1, 6));
        player.teleportTo(helper.getLevel(), abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D, 0.0F, 0.0F);
        player.setHealth(8.0F);
        face(player, captain);
        face(captain, player);
        return new Duel(settlement, captain, player);
    }

    private static void face(LivingEntity who, LivingEntity at) {
        Vec3 d = at.position().subtract(who.position());
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0D);
        who.setYRot(yaw);
        who.setYHeadRot(yaw);
        who.setYBodyRot(yaw);
        who.setXRot(0.0F);
    }

    private static void finish(GameTestHelper helper, Duel duel) {
        RaidParley.clearDuelForTests(duel.settlement().id);
        helper.getLevel().getServer().getPlayerList().remove(duel.player());
        helper.succeed();
    }

    private static void hit(LivingEntity victim, DamageSource source, float amount) {
        victim.invulnerableTime = 0;
        victim.hurt(source, amount);
    }

    /** Player side: 5 raw damage at 8/20 would cross the line, but the shield takes all of it. */
    @GameTest(template = "empty16", timeoutTicks = 120, batch = "talk_parley_duel")
    public void aShieldBlockedHitOnThePlayerDoesNotEndTheDuel(GameTestHelper helper) {
        Duel duel = arena(helper, "Skjoldvik");
        helper.runAfterDelay(READY, () -> {
            ServerPlayer p = duel.player();
            p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            p.startUsingItem(InteractionHand.OFF_HAND);
            // A shield only blocks after 5 ticks raised; the level does not run a
            // mock player's living tick, so run it here.
            for (int i = 0; i < 6; i++) p.doTick();
            face(p, duel.captain());
            helper.assertTrue(p.isBlocking(), "fixture: the shield is up");
            duel.start();
            hit(p, duel.fromCaptain(), 5.0F);
            helper.assertTrue(duel.active(), "a blocked blow leaves the duel running");
            helper.assertTrue(duel.outcomes().isEmpty(), "no outcome: " + duel.outcomes());
            helper.assertTrue(p.getHealth() == 8.0F, "nothing landed: " + p.getHealth());
            finish(helper, duel);
        });
    }

    /** Captain side: the captain raises a shield against the duelist's heavy blow. */
    @GameTest(template = "empty16", timeoutTicks = 120, batch = "talk_parley_duel")
    public void aShieldBlockedHitOnTheCaptainDoesNotEndTheDuel(GameTestHelper helper) {
        Duel duel = arena(helper, "Bucklarby");
        RaiderEntity captain = duel.captain();
        helper.runAfterDelay(READY - 10, () -> {
            captain.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            captain.startUsingItem(InteractionHand.OFF_HAND);
        });
        helper.runAfterDelay(READY, () -> {
            face(captain, duel.player());
            helper.assertTrue(captain.isBlocking(), "fixture: the captain's shield is up");
            duel.start();
            hit(captain, duel.fromPlayer(), 6.0F);
            helper.assertTrue(duel.active(), "a blocked blow leaves the duel running");
            helper.assertTrue(duel.outcomes().isEmpty(), "no outcome: " + duel.outcomes());
            helper.assertTrue(captain.getHealth() == 14.0F, "nothing landed: " + captain.getHealth());
            finish(helper, duel);
        });
    }

    /** Both sides: raw damage over the line, absorption brings what lands above it: no yield. */
    @GameTest(template = "empty16", timeoutTicks = 120, batch = "talk_parley_duel")
    public void aMitigatedHitAboveTheLineDoesNotEndTheDuel(GameTestHelper helper) {
        Duel duel = arena(helper, "Vernholm");
        helper.runAfterDelay(READY, () -> {
            ServerPlayer p = duel.player();
            RaiderEntity captain = duel.captain();
            duel.start();
            // Absorption is clamped to MAX_ABSORPTION (0 unless an effect raises it).
            p.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(4.0D);
            p.setAbsorptionAmount(4.0F);
            helper.assertTrue(p.getAbsorptionAmount() == 4.0F, "fixture: player absorption " + p.getAbsorptionAmount());
            hit(p, duel.fromCaptain(), 5.0F); // raw 8-5=3 <= 4; lands 1 -> 7
            helper.assertTrue(duel.active() && duel.outcomes().isEmpty(),
                "player: absorbed blow is no yield: " + duel.outcomes());
            helper.assertTrue(p.getHealth() == 7.0F, "player took only what got through: " + p.getHealth());
            captain.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(4.0D);
            captain.setAbsorptionAmount(4.0F);
            helper.assertTrue(captain.getAbsorptionAmount() == 4.0F,
                "fixture: captain absorption " + captain.getAbsorptionAmount());
            hit(captain, duel.fromPlayer(), 6.0F); // raw 14-6=8 <= 10; lands 2 -> 12
            helper.assertTrue(duel.active() && duel.outcomes().isEmpty(),
                "captain: absorbed blow is no yield: " + duel.outcomes());
            helper.assertTrue(captain.getHealth() < 14.0F && captain.getHealth() > 10.0F,
                "captain took only what got through: " + captain.getHealth());
            finish(helper, duel);
        });
    }

    /** Player side: a real blow to the line ends the duel once, lost; replays change nothing. */
    @GameTest(template = "empty16", timeoutTicks = 120, batch = "talk_parley_duel")
    public void aRealHitToThePlayersLineEndsTheDuelOnce(GameTestHelper helper) {
        Duel duel = arena(helper, "Tapsvik");
        helper.runAfterDelay(READY, () -> {
            ServerPlayer p = duel.player();
            duel.start();
            hit(p, duel.fromCaptain(), 4.0F); // 8-4=4 <= 4
            helper.assertFalse(duel.active(), "the player yielded");
            helper.assertTrue(duel.outcomes().equals(List.of(false)), "one loss: " + duel.outcomes());
            helper.assertTrue(p.getHealth() == 8.0F, "the yielding blow is stopped: " + p.getHealth());
            helper.assertFalse(RaidParley.yieldOnDamage(p, duel.fromCaptain(), 4.0F), "a repeated callback does nothing");
            hit(p, duel.fromCaptain(), 4.0F);
            helper.assertTrue(duel.outcomes().equals(List.of(false)), "still one outcome: " + duel.outcomes());
            finish(helper, duel);
        });
    }

    /** Captain side: a real blow to the captain's line ends the duel once, won; replays change nothing. */
    @GameTest(template = "empty16", timeoutTicks = 120, batch = "talk_parley_duel")
    public void aRealHitToTheCaptainsLineEndsTheDuelOnce(GameTestHelper helper) {
        Duel duel = arena(helper, "Segerby");
        helper.runAfterDelay(READY, () -> {
            RaiderEntity captain = duel.captain();
            duel.start();
            hit(captain, duel.fromPlayer(), 8.0F); // 14-8=6 <= 10 (still under with a final-stand resistance)
            helper.assertFalse(duel.active(), "the captain yielded");
            helper.assertTrue(duel.outcomes().equals(List.of(true)), "one win: " + duel.outcomes());
            helper.assertTrue(captain.getHealth() == 14.0F, "the yielding blow is stopped: " + captain.getHealth());
            helper.assertFalse(RaidParley.yieldOnDamage(captain, duel.fromPlayer(), 8.0F),
                "a repeated callback does nothing");
            hit(captain, duel.fromPlayer(), 8.0F);
            helper.assertTrue(duel.outcomes().equals(List.of(true)), "still one outcome: " + duel.outcomes());
            finish(helper, duel);
        });
    }
}

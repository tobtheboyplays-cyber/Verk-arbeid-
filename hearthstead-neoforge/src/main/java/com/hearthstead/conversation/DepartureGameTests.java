package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Owner rule for every resolved departure (batch talk_departure): leavers
 * walk off at least 20 blocks before they vanish, never vanish in a
 * player's view within 24 blocks, and a player's blow breaks the truce.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class DepartureGameTests {
    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    private static ServerPlayer player(GameTestHelper helper, String name, BlockPos rel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(helper.getLevel(), abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D, 0.0F, 0.0F);
        return player;
    }

    @GameTest(template = "empty64", timeoutTicks = 1600, batch = "talk_departure")
    public void leaversWalkTwentyBlocksBeforeTheyVanish(GameTestHelper helper) {
        floor(helper, 64);
        Villager a = helper.spawn(EntityType.VILLAGER, new BlockPos(30, 1, 32));
        Villager b = helper.spawn(EntityType.VILLAGER, new BlockPos(31, 1, 32));
        Vec3 startA = a.position();
        AtomicReference<Double> walked = new AtomicReference<>(-1.0D);
        Departure.onDespawned(entity -> {
            if (entity == a) walked.set(entity.position().distanceTo(startA));
        });
        Departure.depart(helper.getLevel(), List.of(a, b), helper.absolutePos(new BlockPos(32, 1, 32)), null,
            Component.literal("Farewell."));
        helper.assertTrue(Departure.isDeparting(a) && Departure.isDeparting(b), "both leave together");
        helper.succeedWhen(() -> {
            helper.assertTrue(walked.get() >= 0.0D, "not gone yet");
            helper.assertTrue(walked.get() >= DepartureRules.MIN_WALK,
                "vanished after walking only " + walked.get() + " blocks");
            helper.assertTrue(a.isRemoved(), "removed quietly once unseen");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "talk_departure")
    public void aLeaverNeverVanishesInViewOfANearbyPlayer(GameTestHelper helper) {
        floor(helper, 16);
        // A leaver that cannot move (no AI), in plain view of a player 3 blocks away.
        Villager stuck = helper.spawn(EntityType.VILLAGER, new BlockPos(7, 1, 7));
        stuck.setNoAi(true);
        ServerPlayer watcher = player(helper, "departure-watch", new BlockPos(10, 1, 7));
        try {
            Departure.depart(helper.getLevel(), List.of(stuck), helper.absolutePos(new BlockPos(7, 1, 7)), null, null);
            // Past the 120 s give-up budget: it may go only once nobody sees it.
            Departure.ageForTest(stuck, DepartureRules.GIVE_UP_TICKS + 20L);
            helper.assertTrue(watcher.hasLineOfSight(stuck) && watcher.distanceTo(stuck) < DepartureRules.HIDDEN_NEAR,
                "fixture: the watcher sees the leaver up close");
            helper.runAtTickTime(120, () -> {
                helper.assertTrue(stuck.isAlive() && !stuck.isRemoved() && Departure.isDeparting(stuck),
                    "a leaver must never vanish in view of a player within 24 blocks");
                helper.succeed();
            });
        } finally {
            helper.runAtTickTime(121, () -> helper.getLevel().getServer().getPlayerList().remove(watcher));
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "talk_departure")
    public void strikingALeavingRaiderBreaksTheTruce(GameTestHelper helper) {
        floor(helper, 16);
        RaiderEntity captain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(8, 1, 8));
        RaiderEntity follower = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(9, 1, 8));
        ServerPlayer attacker = player(helper, "truce-breaker", new BlockPos(5, 1, 8));
        try {
            Departure.depart(helper.getLevel(), List.of(captain, follower), helper.absolutePos(new BlockPos(2, 1, 8)),
                null, Component.literal("We go."),
                new Departure.Truce(null, new SpeakerProfile(UUID.randomUUID(), "Skarde", "", "raider")));
            helper.assertTrue(captain.getTarget() == null && follower.getTarget() == null, "leavers target nobody");
            // Guards and other mobs cannot hurt a leaver; a player can, and breaks the truce.
            follower.hurt(helper.getLevel().damageSources().mobAttack(captain), 2.0F);
            helper.assertTrue(Departure.isDeparting(follower), "a non-player blow changes nothing");
            follower.hurt(helper.getLevel().damageSources().playerAttack(attacker), 2.0F);
            helper.assertTrue(!Departure.isDeparting(captain) && !Departure.isDeparting(follower),
                "the whole band stops leaving");
            helper.assertTrue(captain.getTarget() == attacker && follower.getTarget() == attacker,
                "the band turns on the player who broke the truce");
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(attacker);
            captain.discard();
            follower.discard();
        }
        helper.succeed();
    }
    @GameTest(template = "empty16", timeoutTicks = 40, batch = "talk_departure")
    public void aLeaverSavedMidWalkIsNotRevivedOnLoad(GameTestHelper helper) {
        floor(helper, 16);
        RaiderEntity leaver = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(8, 1, 8));
        Departure.depart(helper.getLevel(), List.of(leaver), helper.absolutePos(new BlockPos(2, 1, 8)), null, null);
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        helper.assertTrue(leaver.save(saved), "the leaver saves like any entity");
        helper.assertTrue(saved.getCompound("NeoForgeData").hasUUID(Departure.TAG),
            "the departing tag is saved with it: " + saved.getCompound("NeoForgeData").getAllKeys());
        leaver.discard();
        // As if its chunk loads again later (or after a restart): same NBT, the walk no longer runs.
        saved.putUUID("UUID", UUID.randomUUID());
        var reloaded = net.minecraft.world.entity.EntityType.create(saved, helper.getLevel()).orElse(null);
        helper.assertTrue(reloaded != null && Departure.isDeparting(reloaded), "reloads with the tag");
        helper.assertTrue(Departure.dropOnLoad(reloaded), "a leaver loading from disk is dropped, never revived");
        helper.assertTrue(!Departure.dropOnLoad(helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 4))),
            "an ordinary raider loads normally");
        helper.succeed();
    }
}

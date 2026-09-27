package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModAttachments;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.StarterKitConfig;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Bug-hunt lane (26 Sep, owner request for the Sunday save): the start kit.
 * A player's FIRST join to a world gives the Settler's Handbook plus
 * [start] kitBread bread, exactly once: never again on relog,
 * death/respawn or a dimension change; 0 turns the food off. Since 27 Sep the
 * default is 0 (the Guildmaster's welcome gives the bread), so the food tests
 * set an explicit {@link #KIT} loaves.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class StarterKitGameTests {
    /** Explicit kit size for the food tests (the default is 0 since 27 Sep). */
    private static final int KIT = 8;

    private static ServerPlayer login(GameTestHelper h, GameProfile profile, int filledSlots) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        for (int i = 0; i < filledSlots; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void logout(GameTestHelper h, ServerPlayer player) {
        var list = h.getLevel().getServer().getPlayerList();
        if (list.getPlayer(player.getUUID()) == player) list.remove(player);
    }

    private static int bread(ServerPlayer p) {
        return p.getInventory().countItem(Items.BREAD);
    }

    private static int handbooks(ServerPlayer p) {
        return p.getInventory().countItem(ModItems.HANDBOOK.get());
    }

    private static void tick(ServerPlayer p, int n) {
        for (int i = 0; i < n; i++) p.doTick();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "bughunt_start_kit")
    public void firstJoinGetsHandbookAndBreadOnceNeverOnRelogDeathOrDimension(GameTestHelper h) {
        StarterKitConfig.overrideForTests(KIT); // GameTestIsolation sets 0
        GameProfile profile = new GameProfile(UUID.randomUUID(), "kit-newcomer");
        ServerPlayer first = login(h, profile, 0);
        h.assertTrue(handbooks(first) == 1 && bread(first) == KIT,
            "first join: 1 handbook + " + KIT + " bread, got "
                + handbooks(first) + " handbook(s), " + bread(first) + " bread");
        tick(first, 41);
        h.assertTrue(bread(first) == KIT,
            "the tick retry never tops the kit up, bread " + bread(first));
        first.getInventory().clearContent();
        logout(h, first);

        ServerPlayer again = login(h, profile, 0);
        tick(again, 41);
        h.assertTrue(bread(again) == 0 && handbooks(again) == 0,
            "second join: no kit again, got " + handbooks(again) + " handbook(s), " + bread(again) + " bread");

        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        ServerPlayer current = again;
        if (nether != null) {
            Entity moved = again.changeDimension(new DimensionTransition(nether, new Vec3(0.5, 100, 0.5),
                Vec3.ZERO, 0f, 0f, DimensionTransition.DO_NOTHING));
            if (moved instanceof ServerPlayer sp) current = sp;
            tick(current, 41);
            h.assertTrue(bread(current) == 0 && handbooks(current) == 0,
                "a dimension change gives no kit, got " + bread(current) + " bread");
        }

        ServerPlayer reborn = h.getLevel().getServer().getPlayerList()
            .respawn(current, false, Entity.RemovalReason.KILLED);
        h.assertTrue(reborn.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED),
            "the one-time flag survives death");
        tick(reborn, 41);
        h.assertTrue(bread(reborn) == 0 && handbooks(reborn) == 0,
            "a respawn gives no kit, got " + handbooks(reborn) + " handbook(s), " + bread(reborn) + " bread");
        logout(h, reborn);
        StarterKitConfig.overrideForTests(0);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "bughunt_start_kit_off")
    public void kitBreadZeroGivesTheHandbookOnly(GameTestHelper h) {
        StarterKitConfig.overrideForTests(0);
        try {
            ServerPlayer p = login(h, new GameProfile(UUID.randomUUID(), "kit-nofood"), 0);
            h.assertTrue(handbooks(p) == 1 && bread(p) == 0,
                "kitBread = 0: handbook only, got " + handbooks(p) + " handbook(s), " + bread(p) + " bread");
            logout(h, p);
        } finally {
            StarterKitConfig.overrideForTests(0);
        }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "bughunt_start_kit_full")
    public void aNearlyFullPackStillGetsEveryLoafOnce(GameTestHelper h) {
        StarterKitConfig.overrideForTests(KIT); // GameTestIsolation sets 0
        // 35 of 36 slots full: the handbook takes the last slot, the bread lands at the player's feet.
        // The GameTest server's default game mode is creative, and a creative player's
        // Inventory.add DELETES what does not fit (infinite materials), so the overflow
        // bread vanished before it could be dropped. A real survival first join is what
        // this proves: log in with survival as the server default.
        var server = h.getLevel().getServer();
        GameType previousDefault = server.getDefaultGameType();
        server.setDefaultGameType(GameType.SURVIVAL);
        ServerPlayer p;
        try {
            p = login(h, new GameProfile(UUID.randomUUID(), "kit-packrat"), 35);
        } finally {
            server.setDefaultGameType(previousDefault);
        }
        h.assertTrue(!p.hasInfiniteMaterials(), "fixture: the newcomer joined in survival");
        // A fresh login stands at the world spawn, whose chunk section a box query may not
        // reach; count every bread item thrown for this player anywhere in the level.
        java.util.List<ItemEntity> thrown = new java.util.ArrayList<>();
        for (Entity e : p.serverLevel().getAllEntities()) {
            if (e instanceof ItemEntity item && item.getItem().is(Items.BREAD)
                && p.getUUID().equals(item.getTarget())) thrown.add(item);
        }
        int onGround = thrown.stream().mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(handbooks(p) == 1, "the handbook took the free slot, got " + handbooks(p));
        h.assertTrue(bread(p) + onGround == KIT,
            "no loaf lost or doubled: pack " + bread(p) + " + ground " + onGround);
        thrown.forEach(Entity::discard);
        StarterKitConfig.overrideForTests(0);
        logout(h, p);
        h.succeed();
    }
}

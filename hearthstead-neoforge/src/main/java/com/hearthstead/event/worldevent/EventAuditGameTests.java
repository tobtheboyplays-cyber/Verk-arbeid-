package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.BarterDeal;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.conversation.Departure;
import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Bug-hunt lane, EVENTS-AUDIT (26 Sep, PvP on for the Sunday save). One
 * regression test per root-cause fix (batches {@code bughunt_event_audit_*}):
 * <ul>
 *   <li>a player's blow on a LEAVING peaceful visitor no longer strands it
 *   (Departure.breakTruce only breaks a raider band's truce);</li>
 *   <li>the player's side of a peddler/caravan barter is valued with the
 *   shared table, never the partner's retail price;</li>
 *   <li>killing the envoy or the refugees' leader ends the event at once;</li>
 *   <li>a leaving peddler is no shop and never poofs by vanilla's timer;</li>
 *   <li>visitors find a spawn spot on a plaza of dirt paths or slabs.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class EventAuditGameTests {
    private static final String ARENA = "empty64";

    private static Settlement village(GameTestHelper h, String name, int records, Block ground, Block topping) {
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), ground);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            if (topping != Blocks.AIR) h.setBlock(new BlockPos(x, 1, z), topping);
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, h.absolutePos(new BlockPos(32, 1, 32)));
        s.radius = 24;
        for (int i = 0; i < records; i++) s.putRecord(UUID.randomUUID(), "Resident" + i, Profession.NONE);
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        WorldEventDirector.resetTransientForTests();
        return s;
    }

    private static Settlement village(GameTestHelper h, String name, int records) {
        return village(h, name, records, Blocks.STONE, Blocks.AIR);
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos rel) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = h.absolutePos(rel);
        player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        return player;
    }

    /**
     * A survival mock player standing at an absolute position. Events only
     * advance while a player is within 80 blocks of the Banner (the director
     * pauses otherwise), so every test that waits for an ending keeps one here.
     */
    private static ServerPlayer playerAt(GameTestHelper h, BlockPos absolute) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(absolute.getX() + .5, absolute.getY(), absolute.getZ() + .5);
        return player;
    }

    private static WorldEventSavedData.Row row(GameTestHelper h, Settlement s) {
        return WorldEventSavedData.get(h.getLevel()).row(s.id);
    }

    private static void teardown(GameTestHelper h, Settlement s) {
        WorldEventDirector.finish(h.getLevel(), s, "test_teardown", null);
        SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
    }

    // ------------------------------------------------ 1: truce / stranding --

    @GameTest(template = ARENA, batch = "bughunt_event_audit_truce", timeoutTicks = 200)
    public void hittingALeavingPeddlerNeverStrandsHim(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Strandless", 3);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.PEDDLER, true), "peddler starts");
        WorldEventSavedData.Active a = row(h, s).active;
        Entity peddler = WorldEventActors.actor(level, a, PeddlerEvent.ROLE_PEDDLER);
        Entity cart = WorldEventActors.actor(level, a, PeddlerEvent.ROLE_CART);
        h.assertTrue(peddler instanceof WanderingTrader, "the peddler is there");
        WorldEventDirector.handler(WorldEventType.PEDDLER).timeout(level, s, a);
        h.assertTrue(Departure.isDeparting(peddler), "the peddler leaves after his visit");
        ServerPlayer player = playerAt(h, peddler.blockPosition().offset(1, 0, 0));
        ((LivingEntity) peddler).hurt(level.damageSources().playerAttack(player), 1.0F);
        h.assertTrue(peddler.isAlive(), "a light blow does not kill him");
        h.assertTrue(Departure.isDeparting(peddler),
            "PvP audit: a player's blow must not strand a peaceful visitor (he keeps leaving)");
        if (cart != null && cart.isAlive()) {
            h.assertTrue(Departure.isDeparting(cart), "and his llama keeps leaving with him");
        }
        // Nobody watching and past the give-up time: Departure removes him unseen.
        player.teleportTo(player.getX(), player.getY() + 200, player.getZ());
        Departure.ageForTest(peddler, 20L * 200);
        if (cart != null) Departure.ageForTest(cart, 20L * 200);
        h.succeedWhen(() -> {
            h.assertTrue(peddler.isRemoved(), "the struck peddler still leaves the world (unseen)");
            teardown(h, s);
        });
    }

    // --------------------------------------------------- 2: barter values --

    @GameTest(template = ARENA, batch = "bughunt_event_audit_barter", timeoutTicks = 40)
    public void aPeddlerNeverBuysThePlayersGoodsAtHisRetailPrice(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Fairprice", 3);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.PEDDLER, true), "peddler starts");
        WorldEventSavedData.Active a = row(h, s).active;
        WorldEventConversations.PeddlerStock stock = new WorldEventConversations.PeddlerStock(level, a.id);
        PeddlerEvent.Ware ware = PeddlerEvent.waresFor(a.id).get(0);
        ItemStack goods = new ItemStack(ware.item(), Math.min(ware.item().getDefaultMaxStackSize(), 8));
        int retail = stock.unitValue(goods);
        int shared = ConversationService.unitValue(null, goods);
        h.assertTrue(retail > 0, "fixture: the ware has a retail price");
        h.assertTrue(ConversationService.playerUnitValue(stock, goods) == shared,
            "the player's own " + goods.getItem() + " is valued at the shared " + shared
                + ", not the peddler's retail " + retail);
        ServerPlayer player = player(h, new BlockPos(30, 1, 30));
        player.getInventory().clearContent();
        player.getInventory().setItem(0, goods.copy());
        BarterDeal deal = BarterDeal.build(player.getInventory(), stock,
            List.of(new ConvActionPayload.Line(0, goods.getCount())), List.of());
        h.assertTrue(deal != null && deal.givenValue() == (long) Math.max(0, shared) * goods.getCount(),
            "a barter offer counts the player's goods at the shared value, got "
                + (deal == null ? "no deal" : deal.givenValue()));
        teardown(h, s);
        h.succeed();
    }

    // ------------------------------------------------ 3: killed speakers --

    @GameTest(template = ARENA, batch = "bughunt_event_audit_envoy_killed", timeoutTicks = 200)
    public void killingTheEnvoyEndsTheVisitAtOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Bloodmoot", 5);
        int before = RivalEnvoyEvent.relation(level, s);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.RIVAL_ENVOY, true), "envoy starts");
        WorldEventSavedData.Active a = row(h, s).active;
        Entity envoy = WorldEventActors.actor(level, a, RivalEnvoyEvent.ROLE_ENVOY);
        h.assertTrue(envoy instanceof LivingEntity, "the envoy is there");
        ServerPlayer player = playerAt(h, envoy.blockPosition().offset(1, 0, 0));
        ((LivingEntity) envoy).hurt(level.damageSources().playerAttack(player), 1000.0F);
        boolean byPlayer = !envoy.isAlive();
        if (envoy.isAlive()) ((LivingEntity) envoy).kill();
        String expected = "rival_envoy:" + (byPlayer ? "envoy_killed" : "envoy_lost");
        h.onEachTick(() -> WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false));
        h.succeedWhen(() -> {
            WorldEventSavedData.Row row = row(h, s);
            h.assertTrue(row.active == null, "the event ends as soon as the envoy is dead");
            h.assertTrue(expected.equals(row.lastOutcome), "outcome " + expected + ", got " + row.lastOutcome);
            if (byPlayer) {
                h.assertTrue(RivalEnvoyEvent.relation(level, s) <= before - 50,
                    "the lord remembers the murder: " + before + " -> " + RivalEnvoyEvent.relation(level, s));
            }
            SettlementSavedData.get(level).settlements.remove(s.id);
        });
    }

    @GameTest(template = ARENA, batch = "bughunt_event_audit_refugee_leader", timeoutTicks = 200)
    public void killingTheRefugeesLeaderSendsTheFamilyAwayAtOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Grieving", 4);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.REFUGEES, true), "refugees start");
        WorldEventSavedData.Active a = row(h, s).active;
        Entity leader = WorldEventActors.actor(level, a, RefugeesEvent.ROLE_LEADER);
        h.assertTrue(leader instanceof LivingEntity, "the leader is there");
        int population = s.population();
        playerAt(h, s.center.offset(0, 0, 3)); // a player nearby, or the event clock pauses
        ((LivingEntity) leader).kill();
        h.onEachTick(() -> WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false));
        h.succeedWhen(() -> {
            WorldEventSavedData.Row row = row(h, s);
            h.assertTrue(row.active == null, "the event ends as soon as the leader is dead");
            h.assertTrue("refugees:leader_killed".equals(row.lastOutcome), "outcome leader_killed, got " + row.lastOutcome);
            h.assertTrue(s.population() == population, "nobody joined the village");
            SettlementSavedData.get(level).settlements.remove(s.id);
        });
    }

    // ------------------------------------------- 4 + 6: leaving peddler ----

    @GameTest(template = ARENA, batch = "bughunt_event_audit_peddler_leaves", timeoutTicks = 60)
    public void aLeavingPeddlerIsNoShopAndNeverPoofs(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Closedshop", 3);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.PEDDLER, true), "peddler starts");
        WorldEventSavedData.Active a = row(h, s).active;
        WanderingTrader peddler = (WanderingTrader) WorldEventActors.actor(level, a, PeddlerEvent.ROLE_PEDDLER);
        Entity cart = WorldEventActors.actor(level, a, PeddlerEvent.ROLE_CART);
        h.assertTrue(peddler.getDespawnDelay() == WorldEventActors.NEVER_DESPAWN,
            "vanilla's despawn timer never runs out mid-visit, got " + peddler.getDespawnDelay());
        h.assertTrue(!peddler.getOffers().isEmpty(), "fixture: he has wares during the visit");
        WorldEventDirector.handler(WorldEventType.PEDDLER).timeout(level, s, a);
        h.assertTrue(Departure.isDeparting(peddler), "he is leaving");
        h.assertTrue(peddler.getOffers().isEmpty(), "a leaving peddler has no offers left");
        ServerPlayer player = playerAt(h, peddler.blockPosition().offset(1, 0, 0));
        peddler.mobInteract(player, InteractionHand.MAIN_HAND);
        h.assertTrue(peddler.getTradingPlayer() == null, "a right-click on the leaving peddler opens no shop");
        h.runAfterDelay(20, () -> {
            h.assertTrue(!peddler.isRemoved(), "the peddler walks off, no puff");
            h.assertTrue(cart == null || !cart.isRemoved(), "his leashed llama does not poof either");
            for (Entity e : List.of(peddler)) e.discard();
            if (cart != null) cart.discard();
            SettlementSavedData.get(level).settlements.remove(s.id);
            h.succeed();
        });
    }

    // --------------------------------------------- 5: paved spawn ground ----

    @GameTest(template = ARENA, batch = "bughunt_event_audit_path_plaza", timeoutTicks = 40)
    public void visitorsFindASpotOnADirtPathPlaza(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // The test structure's barrier ceiling is the heightmap top (and a sturdy floor);
        // remove it so the spot search really has to stand on the dirt paths.
        net.minecraft.gametest.framework.StructureUtils.removeBarriers(h.getBounds(), level);
        Settlement s = village(h, "Pathford", 5, Blocks.DIRT_PATH, Blocks.AIR);
        BlockPos spot = WorldEventCreatures.ringSpot(level, s, s.center, 5, 10, 1.0F, 2.0F, level.random);
        h.assertTrue(spot != null && level.getBlockState(spot.below()).is(Blocks.DIRT_PATH),
            "a dirt-path plaza offers a spawn spot on the paths, got " + spot);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.PEDDLER, true), "the peddler starts on paths");
        for (Entity e : WorldEventActors.actors(level, row(h, s).active)) {
            h.assertTrue(level.getFluidState(e.blockPosition()).isEmpty()
                    && level.noCollision(e, e.getBoundingBox().deflate(0.05D)),
                "actor " + e.getType() + " stands free at " + e.blockPosition());
        }
        teardown(h, s);
        h.succeed();
    }

    @GameTest(template = ARENA, batch = "bughunt_event_audit_slab_plaza", timeoutTicks = 40)
    public void visitorsFindASpotOnASlabPlaza(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        net.minecraft.gametest.framework.StructureUtils.removeBarriers(h.getBounds(), level);
        Settlement s = village(h, "Slabton", 5, Blocks.STONE, Blocks.SMOOTH_STONE_SLAB);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.REFUGEES, true),
            "refugees start on a plaza of bottom slabs");
        for (Entity e : WorldEventActors.actors(level, row(h, s).active)) {
            h.assertTrue(level.noCollision(e, e.getBoundingBox().deflate(0.05D)),
                "refugee stands free at " + e.blockPosition());
        }
        teardown(h, s);
        h.succeed();
    }
}

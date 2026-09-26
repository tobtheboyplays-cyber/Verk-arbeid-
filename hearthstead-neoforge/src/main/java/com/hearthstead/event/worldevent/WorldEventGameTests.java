package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * World events, one GameTest per event: start, resolve or time out, clean.
 * Every batch is named {@code event_*}. Each test builds its own settlement,
 * starts its own event explicitly (planning is off on GameTest servers) and
 * removes the settlement again.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class WorldEventGameTests {
    private static final String ARENA = "empty64";

    // ------------------------------------------------------------ fixtures --

    private static Settlement village(GameTestHelper h, String name, int extraRecords) {
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, h.absolutePos(new BlockPos(32, 1, 32)));
        s.radius = 24;
        for (int i = 0; i < extraRecords; i++) s.putRecord(UUID.randomUUID(), "Resident" + i, Profession.NONE);
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        WorldEventDirector.resetTransientForTests();
        return s;
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos rel) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = h.absolutePos(rel);
        player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        return player;
    }

    private static void teardown(GameTestHelper h, Settlement s) {
        ServerLevel level = h.getLevel();
        WorldEventDirector.finish(level, s, "test_teardown", null);
        SettlementSavedData.get(level).settlements.remove(s.id);
    }

    /**
     * Ticks this test's settlement explicitly once a second, independent of the
     * global loop (a long full suite leaves hundreds of fixture settlements behind).
     */
    private static void drive(GameTestHelper h, Settlement s) {
        h.runAfterDelay(20, () -> {
            WorldEventSavedData.Row row = WorldEventSavedData.get(h.getLevel()).row(s.id);
            if (row == null || row.active == null) return; // event over: stop driving
            WorldEventDirector.observe(h.getLevel(), s, WorldEventSavedData.get(h.getLevel()), false);
            drive(h, s);
        });
    }

    private static WorldEventSavedData.Active active(GameTestHelper h, Settlement s) {
        WorldEventSavedData.Row row = WorldEventSavedData.get(h.getLevel()).row(s.id);
        return row == null ? null : row.active;
    }

    private static List<Entity> actors(GameTestHelper h, WorldEventSavedData.Active a) {
        return WorldEventActors.actors(h.getLevel(), a);
    }

    /**
     * After a real ending no actor stays an event actor: each is gone (dead)
     * or walking out under {@link WorldEventDeparture} (never an instant puff).
     * The walkers are then removed here for test hygiene only.
     */
    private static void assertCleaned(GameTestHelper h, List<Entity> spawned, String what) {
        for (Entity entity : spawned) {
            h.assertTrue(entity.isRemoved() || WorldEventDeparture.isDeparting(entity),
                what + ": every actor is gone or walking out after the event (" + entity.getType() + ")");
            h.assertTrue(entity.isRemoved() || !entity.getPersistentData().contains(WorldEventDirector.TAG),
                what + ": no living leftover event actor");
            if (!entity.isRemoved()) entity.discard();
        }
    }

    private static SettlerEntity resident(GameTestHelper h, Settlement s, BlockPos rel, Profession profession) {
        SettlerEntity e = h.spawn(ModEntities.SETTLER.get(), rel);
        e.bindTo(s.id, s.center);
        s.putRecord(e.getUUID(), "Res" + e.getId(), profession);
        e.setProfessionProjection(profession);
        e.setHunger(100);
        e.setEnergy(100);
        return e;
    }

    private static void plantField(GameTestHelper h, Settlement s) {
        GameTestFixtures.register(h, s, BuildingType.FARMHOUSE, 40, 40);
        for (int x = 4; x < 12; x++) for (int z = 4; z < 12; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND);
            h.setBlock(new BlockPos(x, 1, z), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        }
    }

    private static Building tavern(GameTestHelper h, Settlement s) {
        return GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN, new BlockPos(20, 1, 20),
            new BlockPos(18, 2, 18), BoundingBox.fromCorners(h.absolutePos(new BlockPos(18, 1, 18)),
                h.absolutePos(new BlockPos(28, 4, 28))));
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) n += player.getInventory().getItem(i).getCount();
        }
        return n;
    }

    // ------------------------------------------------------- kill switch --

    @GameTest(template = ARENA, batch = "event_disabled", timeoutTicks = 100)
    public void disabledSwitchStartsNothing(GameTestHelper h) {
        Settlement s = village(h, "Switchholm", 6);
        player(h, new BlockPos(32, 1, 34));
        ServerLevel level = h.getLevel();
        WorldEventSavedData data = WorldEventSavedData.get(level);
        WorldEventSavedData.Row row = data.rowOrCreate(s.id);
        row.lastOutcome = "kept";
        row.lastDayByType.put(WorldEventType.PEDDLER, 3L);
        WorldEventConfig.overrideMasterForTests(false);
        try {
            long base = Math.floorDiv(level.getDayTime(), 24000L) * 24000L;
            for (long t = 0; t < 24000; t += 500) {
                level.setDayTime(base + t);
                WorldEventDirector.observe(level, s, data, true);
            }
            h.assertTrue(row.active == null, "switched off: no event may start at any time of day");
            h.assertTrue(row.plannedType == null && row.plannedDay == WorldEventSchedule.NO_DAY,
                "switched off: nothing is even planned");
            h.assertTrue("kept".equals(row.lastOutcome) && row.lastDayByType.get(WorldEventType.PEDDLER) == 3L,
                "switched off: saved state is kept, never deleted");
        } finally {
            WorldEventConfig.overrideMasterForTests(null);
            teardown(h, s);
        }
        h.succeed();
    }

    // ------------------------------------------------------------ peddler --

    @GameTest(template = ARENA, batch = "event_peddler", timeoutTicks = 100)
    public void peddlerSellsOnlyForCoinsAndLeavesClean(GameTestHelper h) {
        Settlement s = village(h, "Peddlerholm", 3);
        player(h, new BlockPos(32, 1, 36));
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.PEDDLER, true), "peddler starts");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> spawned = actors(h, a);
        WanderingTrader peddler = (WanderingTrader) spawned.stream().filter(e -> e instanceof WanderingTrader).findFirst().orElse(null);
        h.assertTrue(peddler != null, "a peddler stands by the Banner");
        h.assertTrue(spawned.stream().anyMatch(e -> e instanceof TraderLlama), "with a pack llama");
        h.assertTrue(peddler.getOffers().size() == PeddlerEvent.WARES_PER_VISIT, "a small curated table");
        for (MerchantOffer offer : peddler.getOffers()) {
            h.assertTrue(offer.getBaseCostA().is(ModItems.GOLD_COIN.get()) && offer.getCostB().isEmpty(),
                "every ware is priced in Coins only");
        }
        h.assertTrue(!a.state.getCompound("Stock").isEmpty(), "the barter stock mirrors the wares");
        WorldEventDirector.finish(h.getLevel(), s, "left", null);
        assertCleaned(h, spawned, "peddler");
        teardown(h, s);
        h.succeed();
    }

    // ----------------------------------------------------------- refugees --

    @GameTest(template = ARENA, batch = "event_refugees", timeoutTicks = 100)
    public void refugeesTakenInBecomeRealSettlers(GameTestHelper h) {
        Settlement s = village(h, "Refugeholm", 3);
        int before = s.population();
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.REFUGEES, true), "refugees arrive");
        WorldEventSavedData.Active a = active(h, s);
        Entity leader = WorldEventActors.actor(h.getLevel(), a, RefugeesEvent.ROLE_LEADER);
        h.assertTrue(leader != null && actors(h, a).size() == RefugeesEvent.FAMILY, "a family of three waits");
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(leader.getX() + 1, leader.getY(), leader.getZ());
        List<Entity> family = actors(h, a);
        var result = WorldEventVisitors.choose(player, a.id, leader, "accept");
        h.assertTrue(result.accepted(), "the answer is taken: " + result.message().getString());
        h.assertTrue(s.population() == before + RefugeesEvent.FAMILY, "three new settlers on the roll");
        for (Entity member : family) {
            h.assertTrue(!member.isRemoved() && member instanceof SettlerEntity settler && settler.isBound()
                && s.id.equals(settler.getSettlementId()), "each refugee stays as a bound settler");
            h.assertTrue(!member.getPersistentData().contains(WorldEventDirector.TAG), "and is no longer an event actor");
        }
        h.assertTrue(active(h, s) == null, "the event ended");
        h.assertTrue(!WorldEventVisitors.choose(player, a.id, leader, "decline").accepted(), "a second answer is refused");
        for (Entity member : family) member.discard();
        teardown(h, s);
        h.succeed();
    }

    @GameTest(template = ARENA, batch = "event_refugees", timeoutTicks = 100)
    public void refugeesSentOnWalkAwayAndAreCleanedUp(GameTestHelper h) {
        Settlement s = village(h, "Declineholm", 3);
        int before = s.population();
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.REFUGEES, true);
        WorldEventSavedData.Active a = active(h, s);
        Entity leader = WorldEventActors.actor(h.getLevel(), a, RefugeesEvent.ROLE_LEADER);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(leader.getX() + 1, leader.getY(), leader.getZ());
        List<Entity> family = actors(h, a);
        h.assertTrue(WorldEventVisitors.choose(player, a.id, leader, "decline").accepted(), "decline taken");
        h.assertTrue(a.state.getBoolean("Leaving") && s.population() == before, "they leave, nobody joins");
        WorldEventDirector.finish(h.getLevel(), s, "declined", null);
        assertCleaned(h, family, "refugees");
        teardown(h, s);
        h.succeed();
    }

    // ---------------------------------------------------------- minstrels --

    @GameTest(template = ARENA, batch = "event_minstrels", timeoutTicks = 160)
    public void hostedMinstrelsPlayTheTavernTuneAndPayOnce(GameTestHelper h) {
        Settlement s = village(h, "Lutebury", 3);
        tavern(h, s);
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.MINSTRELS, true), "minstrels arrive");
        WorldEventSavedData.Active a = active(h, s);
        Entity lead = WorldEventActors.actor(h.getLevel(), a, MinstrelsEvent.ROLE_LEAD);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(lead.getX() + 1, lead.getY(), lead.getZ());
        player.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), MinstrelsEvent.HOST_COINS + 2));
        List<Entity> band = actors(h, a);
        h.assertTrue(WorldEventVisitors.choose(player, a.id, lead, "host").accepted(), "hosting is accepted");
        h.assertTrue(count(player, ModItems.GOLD_COIN.get()) == 2, "exactly the host price was paid");
        h.assertTrue(a.state.getBoolean("Playing") && a.state.getBoolean("Hosted"), "the feast set begins");
        drive(h, s);
        h.runAfterDelay(60, () -> {
            h.assertTrue(band.stream().allMatch(e -> e instanceof SettlerEntity m
                    && m.getActivity() == SettlerActivity.PLAYING_MUSIC),
                "every minstrel plays (the Tavern bard presentation and tune)");
            WorldEventDirector.finish(h.getLevel(), s, "feast", null);
            assertCleaned(h, band, "minstrels");
            teardown(h, s);
            h.succeed();
        });
    }

    // ---------------------------------------------------------------- fox --

    @GameTest(template = ARENA, batch = "event_field_fox", timeoutTicks = 100)
    public void foxRaidsOnlyCropsAndIsCleanedUp(GameTestHelper h) {
        Settlement s = village(h, "Foxfield", 3);
        plantField(h, s);
        player(h, new BlockPos(60, 1, 60));
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.FIELD_FOX, true), "the fox appears");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> spawned = actors(h, a);
        Fox fox = (Fox) spawned.get(0);
        h.assertTrue(fox.targetSelector.getAvailableGoals().isEmpty(), "no vanilla hunting (chickens are safe)");
        // Steal directly at a ripe crop: growth resets, the farmland stays.
        BlockPos crop = h.absolutePos(new BlockPos(5, 1, 5));
        fox.moveTo(crop.getX() + .5, crop.getY(), crop.getZ() + .5);
        h.runAfterDelay(70, () -> {
            h.assertTrue(h.getLevel().getBlockState(crop.below()).is(Blocks.FARMLAND), "farmland is never broken");
            int stolen = fox.getPersistentData().getCompound(WorldEventDirector.TAG).getInt("Stolen");
            h.assertTrue(stolen <= FieldFoxEvent.MAX_STOLEN, "crop loss is capped");
            WorldEventDirector.finish(h.getLevel(), s, "chased_off", null);
            assertCleaned(h, spawned, "fox");
            teardown(h, s);
            h.succeed();
        });
    }

    // -------------------------------------------------------------- wolves --

    @GameTest(template = ARENA, batch = "event_wolf_pack", timeoutTicks = 100)
    public void wolfPackIsAMonsterGuardsCanFightAndLeavesClean(GameTestHelper h) {
        Settlement s = village(h, "Wolfmoor", 3);
        h.spawn(EntityType.COW, new BlockPos(30, 1, 30));
        h.spawn(EntityType.SHEEP, new BlockPos(34, 1, 30));
        player(h, new BlockPos(32, 1, 40));
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.WOLF_PACK, true), "the pack comes");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> pack = actors(h, a);
        h.assertTrue(pack.size() == WolfPackEvent.PACK && pack.stream().allMatch(e -> e instanceof PackWolfEntity
            && e instanceof net.minecraft.world.entity.monster.Enemy), "three hostile wolves (guard-targetable)");
        a.state.putInt("Kills", WolfPackEvent.MAX_KILLS);
        h.assertTrue(!WorldEventDirector.packMayHunt((PackWolfEntity) pack.get(0)), "the kill budget stops the hunt");
        WorldEventDirector.finish(h.getLevel(), s, "driven_off", null);
        assertCleaned(h, pack, "wolves");
        teardown(h, s);
        h.succeed();
    }

    // ---------------------------------------------------------------- boar --

    @GameTest(template = ARENA, batch = "event_wild_boar", timeoutTicks = 120)
    public void wildBoarIsHeavyAndItsDeathEndsTheEvent(GameTestHelper h) {
        Settlement s = village(h, "Boarfield", 3);
        plantField(h, s);
        player(h, new BlockPos(60, 1, 60));
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.WILD_BOAR, true), "the boar comes");
        WorldEventSavedData.Active a = active(h, s);
        WildBoarEntity boar = (WildBoarEntity) actors(h, a).get(0);
        h.assertTrue(boar.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) >= 0.8D, "heavy: knockback resistant");
        boar.kill();
        drive(h, s);
        h.runAfterDelay(45, () -> {
            h.assertTrue(active(h, s) == null, "a dead boar ends the event");
            h.assertTrue(WorldEventSavedData.get(h.getLevel()).row(s.id).lastOutcome.startsWith("wild_boar:killed"),
                "outcome recorded: " + WorldEventSavedData.get(h.getLevel()).row(s.id).lastOutcome);
            teardown(h, s);
            h.succeed();
        });
    }

    // --------------------------------------------------------------- brawl --

    @GameTest(template = ARENA, batch = "event_tavern_brawl", timeoutTicks = 100)
    public void playerSeparatesBrawlersForASmallMoodCost(GameTestHelper h) {
        Settlement s = village(h, "Brawlby", 0);
        tavern(h, s);
        SettlerEntity a1 = resident(h, s, new BlockPos(21, 1, 21), Profession.FARMER);
        SettlerEntity b1 = resident(h, s, new BlockPos(24, 1, 24), Profession.FARMER);
        ServerPlayer player = player(h, new BlockPos(22, 1, 22));
        float moraleA = a1.getMorale(), moraleB = b1.getMorale();
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.TAVERN_BRAWL, true), "a brawl starts");
        WorldEventSavedData.Active a = active(h, s);
        h.assertTrue(TavernBrawlEvent.isBrawler(a, a1.getUUID()) && TavernBrawlEvent.isBrawler(a, b1.getUUID()),
            "the two residents are the brawlers");
        h.assertTrue(WorldEventDirector.rolesView().get(a1.getUUID()).kind() == WorldEventDirector.RoleKind.BRAWL,
            "they go at each other");
        TavernBrawlEvent.separate(h.getLevel(), s, a, player);
        h.assertTrue(active(h, s) == null, "stepping in ends it");
        h.assertTrue(a1.getMorale() < moraleA && b1.getMorale() < moraleB, "both lose a little mood");
        h.assertTrue(!a1.isRemoved() && !b1.isRemoved(), "brawlers are settlers: never discarded");
        a1.discard();
        b1.discard();
        teardown(h, s);
        h.succeed();
    }

    // ---------------------------------------------------------- brute toll --

    private static RaiderEntity chief(GameTestHelper h, WorldEventSavedData.Active a) {
        return (RaiderEntity) WorldEventActors.actor(h.getLevel(), a, BruteTollEvent.ROLE_CHIEF);
    }

    @GameTest(template = ARENA, batch = "event_brute_toll", timeoutTicks = 100)
    public void payingTheTollRemovesTheFoodExactlyOnceAndTheyLeave(GameTestHelper h) {
        Settlement s = village(h, "Tollby", 5);
        h.assertTrue(WorldEventDirector.start(h.getLevel(), s, WorldEventType.BRUTE_TOLL, true), "brutes arrive");
        WorldEventSavedData.Active a = active(h, s);
        RaiderEntity chief = chief(h, a);
        List<Entity> band = actors(h, a);
        h.assertTrue(band.size() == BruteTollEvent.BAND && chief.isInvulnerable(),
            "three passive brutes (guards cannot target them)");
        int toll = a.state.getInt("Toll");
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(chief.getX() + 1, chief.getY(), chief.getZ());
        player.getInventory().add(new ItemStack(Items.BREAD, toll + 5));
        h.assertTrue(WorldEventVisitors.choose(player, a.id, chief, "pay_food").accepted(), "the toll is paid");
        h.assertTrue(count(player, Items.BREAD) == 5, "exactly the toll left the inventory");
        h.assertTrue(!WorldEventVisitors.choose(player, a.id, chief, "pay_food").accepted()
            && count(player, Items.BREAD) == 5, "never paid twice");
        h.assertTrue(a.state.getBoolean("Leaving") && !a.state.getBoolean("Hostile"), "they walk off peacefully");
        WorldEventDirector.finish(h.getLevel(), s, "paid_food", null);
        assertCleaned(h, band, "brute toll (paid)");
        teardown(h, s);
        h.succeed();
    }

    @GameTest(template = ARENA, batch = "event_brute_toll", timeoutTicks = 200)
    public void refusingTheTollTurnsTheBrutesHostile(GameTestHelper h) {
        Settlement s = village(h, "Refuseby", 5);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.BRUTE_TOLL, true);
        WorldEventSavedData.Active a = active(h, s);
        RaiderEntity chief = chief(h, a);
        List<Entity> band = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(chief.getX() + 1, chief.getY(), chief.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, chief, "refuse").accepted(), "refusal taken");
        h.assertTrue(!a.state.getBoolean("Hostile"), "first a warning line");
        drive(h, s);
        h.succeedWhen(() -> {
            h.assertTrue(a.state.getBoolean("Hostile") && !chief.isInvulnerable()
                && !chief.targetSelector.getAvailableGoals().isEmpty(), "after the warning they fight for real");
            WorldEventDirector.finish(h.getLevel(), s, "refused", null);
            assertCleaned(h, band, "brute toll (fight)");
            teardown(h, s);
        });
    }

    @GameTest(template = ARENA, batch = "event_brute_toll", timeoutTicks = 100)
    public void unansweredBrutesTakeTheTollFromTheStoresAndLeave(GameTestHelper h) {
        Settlement s = village(h, "Waitby", 5);
        Building warehouse = GameTestFixtures.register(h, s, BuildingType.WAREHOUSE, 50, 50);
        BlockPos chestPos = h.absolutePos(new BlockPos(51, 1, 51));
        h.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = (ChestBlockEntity) h.getLevel().getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.BREAD, 40));
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.BRUTE_TOLL, true);
        WorldEventSavedData.Active a = active(h, s);
        int toll = a.state.getInt("Toll");
        List<Entity> band = actors(h, a);
        BruteTollEvent.defaultOutcome(h.getLevel(), s, a);
        int left = chest.getItem(0).getCount();
        boolean stores = !WorldEventVisitors.stores(h.getLevel(), s).isEmpty();
        if (stores) {
            h.assertTrue(left == 40 - toll && a.state.getBoolean("Leaving"),
                "they take exactly the toll (" + toll + ") from the stores and leave; left=" + left);
        } else {
            h.assertTrue(left == 40 && a.state.getBoolean("Hostile"), "no reachable stores: they attack instead");
        }
        h.assertTrue(WorldEventVisitors.answered(a), "the default outcome closes the question");
        WorldEventDirector.finish(h.getLevel(), s, "timeout", null);
        assertCleaned(h, band, "brute toll (timeout)");
        h.assertTrue(warehouse != null, "fixture warehouse");
        teardown(h, s);
        h.succeed();
    }
    // ----------------------------------------------------------- stray dog --

    @GameTest(template = ARENA, batch = "event_stray_dog", timeoutTicks = 200)
    public void fedStrayBecomesTheOneVillageDogAndNeverCrowdsTheCourier(GameTestHelper h) {
        Settlement s = village(h, "Dogby", 3);
        ServerLevel level = h.getLevel();
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.STRAY_DOG, true), "a stray turns up");
        WorldEventSavedData.Active a = active(h, s);
        net.minecraft.world.entity.animal.Wolf dog = (net.minecraft.world.entity.animal.Wolf) actors(h, a).get(0);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(dog.getX() + 1, dog.getY(), dog.getZ());
        player.getInventory().add(new ItemStack(Items.BREAD, StrayDogEvent.FOOD + 3));
        h.assertTrue(WorldEventVisitors.choose(player, a.id, dog, "feed_dog").accepted(), "feeding is accepted");
        h.assertTrue(count(player, Items.BREAD) == 3, "exactly the food was paid");
        WorldEventSavedData.Row row = WorldEventSavedData.get(level).row(s.id);
        h.assertTrue(active(h, s) == null && dog.getUUID().equals(row.villageDog) && !dog.isRemoved()
            && dog.getPersistentData().contains(VillageDog.TAG) && !dog.getPersistentData().contains(WorldEventDirector.TAG),
            "the dog moved in for good and is no longer an event actor");
        h.assertTrue(!WorldEventDirector.handler(WorldEventType.STRAY_DOG).available(level, s), "one dog per settlement");
        net.minecraft.world.entity.animal.Wolf copy = EntityType.WOLF.create(level);
        copy.moveTo(dog.getX(), dog.getY(), dog.getZ());
        copy.getPersistentData().put(VillageDog.TAG, dog.getPersistentData().getCompound(VillageDog.TAG).copy());
        h.assertTrue(!level.addFreshEntity(copy), "a second village dog is refused");
        player.teleportTo(player.getX() + 60, player.getY(), player.getZ()); // out of the dog's 16-block interest
        // Absolute placement: GameTestHelper.relativePos applies the test rotation the wrong way
        // round, which put the courier ~90 blocks off when the arena was rotated.
        SettlerEntity courier = ModEntities.SETTLER.get().create(level);
        BlockPos courierAt = WorldEventCreatures.nearFeet(level, dog.blockPosition().offset(6, 0, 0), 0.6F, 1.95F);
        if (courierAt == null) courierAt = dog.blockPosition();
        courier.moveTo(courierAt.getX() + .5, courierAt.getY(), courierAt.getZ() + .5, 0F, 0F);
        level.addFreshEntity(courier);
        courier.bindTo(s.id, s.center);
        s.putRecord(courier.getUUID(), "Courier", Profession.COURIER);
        courier.setProfessionProjection(Profession.COURIER);
        courier.setNoAi(true);
        double[] closest = {Double.MAX_VALUE};
        double start = dog.distanceTo(courier);
        h.onEachTick(() -> closest[0] = Math.min(closest[0], dog.distanceTo(courier)));
        h.runAfterDelay(160, () -> {
            String where = " dog=" + dog.blockPosition() + " courier=" + courier.blockPosition() + " start=" + start
                + " courierProfession=" + courier.getProfession();
            h.assertTrue(closest[0] >= 1.2D, "the dog never crowds the courier (closest " + closest[0] + ")" + where);
            h.assertTrue(closest[0] < 4.5D, "but it trots over to them (closest " + closest[0] + ")" + where);
            dog.discard();
            courier.discard();
            row.villageDog = null;
            teardown(h, s);
            h.succeed();
        });
    }

    // ------------------------------------------------------------- caravan --

    @GameTest(template = ARENA, batch = "event_caravan", timeoutTicks = 100)
    public void escortedCaravanPaysAndEveryActorIsCleaned(GameTestHelper h) {
        Settlement s = village(h, "Roadby", 3);
        s.radius = 14; // keep the roadside stop inside the 64-block arena
        ServerLevel level = h.getLevel();
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.CARAVAN, true), "a caravan stops");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> spawned = actors(h, a);
        WanderingTrader master = (WanderingTrader) WorldEventActors.actor(level, a, CaravanEvent.ROLE_MASTER);
        h.assertTrue(master != null, "the caravan master stops by the road");
        h.assertTrue(spawned.stream().anyMatch(e -> e instanceof TraderLlama),
            "with at least one pack llama (actors=" + spawned.size() + " at " + master.blockPosition() + ")");
        h.assertTrue(a.state.getCompound("Stock").getInt("minecraft:iron_ingot") > 0, "bulk goods to barter");
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(master.getX() + 1, master.getY(), master.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, master, "escort").accepted(), "escort accepted");
        // Bandits are real and cleaned up too.
        BlockPos far = h.absolutePos(new BlockPos(40, 1, 40));
        CaravanEvent.spawnBandits(level, s, a, master, far);
        List<Entity> withBandits = actors(h, a);
        h.assertTrue(withBandits.stream().anyMatch(e -> e instanceof RaiderEntity r
            && !r.targetSelector.getAvailableGoals().isEmpty()), "an ambush is hostile");
        for (Entity e : withBandits) if (e instanceof RaiderEntity) e.discard();
        a.state.putBoolean("Ambushed", true);
        a.state.putLong("Far", far.asLong());
        master.teleportTo(far.getX() + .5, far.getY(), far.getZ() + .5);
        player.teleportTo(far.getX() + 2.5, far.getY(), far.getZ() + .5);
        WorldEventDirector.handler(WorldEventType.CARAVAN).tick(level, s, a);
        h.assertTrue(active(h, s) == null && WorldEventSavedData.get(level).row(s.id).lastOutcome.equals("caravan:escorted"),
            "arrival pays out and ends the event");
        int coins = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(far).inflate(4), i -> i.getItem().is(ModItems.GOLD_COIN.get()))
            .stream().mapToInt(i -> i.getItem().getCount()).sum();
        h.assertTrue(coins == CaravanEvent.ESCORT_COINS, "the escort is paid " + CaravanEvent.ESCORT_COINS + " Coins, got " + coins);
        assertCleaned(h, withBandits, "caravan");
        teardown(h, s);
        h.succeed();
    }

    // --------------------------------------------------------- rival envoy --

    @GameTest(template = ARENA, batch = "event_rival_envoy", timeoutTicks = 100)
    public void befriendingTheEnvoyIsRememberedAsARelation(GameTestHelper h) {
        Settlement s = village(h, "Envoyby", 5);
        ServerLevel level = h.getLevel();
        int before = RivalEnvoyEvent.relation(level, s);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.RIVAL_ENVOY, true), "an envoy arrives");
        WorldEventSavedData.Active a = active(h, s);
        Entity envoy = WorldEventActors.actor(level, a, RivalEnvoyEvent.ROLE_ENVOY);
        List<Entity> party = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(envoy.getX() + 1, envoy.getY(), envoy.getZ());
        player.getInventory().add(new ItemStack(Items.BREAD, RivalEnvoyEvent.GIFT_FOOD));
        h.assertTrue(WorldEventVisitors.choose(player, a.id, envoy, "befriend").accepted(), "the gift is sent");
        h.assertTrue(count(player, Items.BREAD) == 0 && count(player, Items.GOLD_INGOT) == 2, "gift paid, token received");
        h.assertTrue(RivalEnvoyEvent.relation(level, s) == Math.min(100, before + RivalEnvoyEvent.RELATION.get("befriend")),
            "the lord's standing is stored: " + RivalEnvoyEvent.relation(level, s));
        h.assertTrue(a.state.getBoolean("Leaving"), "the envoy rides home");
        WorldEventDirector.finish(level, s, "befriend", null);
        assertCleaned(h, party, "envoy");
        teardown(h, s);
        h.succeed();
    }

    // ------------------------------------------------ departures (no puff) --

    /**
     * Owner rule: leavers walk out and vanish only unseen. Phase 1 (100
     * ticks): a watcher stays beside the group, so nobody may vanish.
     * Phase 2: the watcher steps 90 blocks away; every leaver must then be
     * gone, and each must have walked at least {@link WorldEventDeparture#MIN_WALK}
     * blocks before it went.
     */
    private static void watchDeparture(GameTestHelper h, Settlement s, List<Entity> leavers, String what,
                                       ServerPlayer... others) {
        h.assertTrue(!leavers.isEmpty(), what + ": somebody leaves");
        for (Entity e : leavers) {
            h.assertTrue(!e.isRemoved() && WorldEventDeparture.isDeparting(e),
                what + ": walking out, not a puff (" + e.getType() + ")");
        }
        java.util.Map<Entity, net.minecraft.world.phys.Vec3> start = new java.util.HashMap<>();
        java.util.Map<Entity, net.minecraft.world.phys.Vec3> last = new java.util.HashMap<>();
        for (Entity e : leavers) { start.put(e, e.position()); last.put(e, e.position()); }
        ServerPlayer watcher = h.makeMockServerPlayerInLevel();
        watcher.setGameMode(GameType.CREATIVE);
        for (ServerPlayer other : others) other.teleportTo(other.getX(), other.getY() + 200, other.getZ());
        int[] tick = {0};
        String[] failure = {null};
        java.util.Set<Long> forced = new java.util.HashSet<>();
        h.onEachTick(() -> {
            tick[0]++;
            Entity lead = leavers.stream().filter(e -> !e.isRemoved()).findFirst().orElse(null);
            if (tick[0] <= 100) {
                for (Entity e : leavers) {
                    if (e.isRemoved() && failure[0] == null) {
                        failure[0] = what + ": " + e.getType() + " vanished in plain view at tick " + tick[0];
                    }
                }
                if (lead != null) watcher.teleportTo(lead.getX() + 3, lead.getY(), lead.getZ() + 2);
            } else if (lead != null) {
                // Phase 2: nobody watching. GameTest mock players can never be real
                // spectators (isSpectator() is hard-wired false), so the watcher steps
                // 90 blocks away and the walkers' chunks are force-loaded instead,
                // keeping them entity-ticking exactly as a real, distant player would.
                if (tick[0] == 101) {
                    BlockPos c = s.center;
                    double dx = c.getX() - lead.getX(), dz = c.getZ() - lead.getZ();
                    double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
                    watcher.teleportTo(lead.getX() + dx / len * 90, lead.getY() + 40, lead.getZ() + dz / len * 90);
                }
                for (Entity e : leavers) {
                    if (e.isRemoved()) continue;
                    net.minecraft.world.level.ChunkPos chunk = e.chunkPosition();
                    for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
                        long key = net.minecraft.world.level.ChunkPos.asLong(chunk.x + cx, chunk.z + cz);
                        if (forced.add(key)) h.getLevel().setChunkForced(chunk.x + cx, chunk.z + cz, true);
                    }
                }
            }
            for (Entity e : leavers) if (!e.isRemoved()) last.put(e, e.position());
        });
        h.succeedWhen(() -> {
            h.assertTrue(failure[0] == null, String.valueOf(failure[0]));
            for (Entity e : leavers) {
                h.assertTrue(e.isRemoved(), what + ": still walking out (" + e.getType() + " at " + e.blockPosition() + ")");
                double walked = Math.sqrt(horizontal(start.get(e), last.get(e)));
                h.assertTrue(walked >= WorldEventDeparture.MIN_WALK - 1,
                    what + ": " + e.getType() + " walked only " + walked + " blocks before leaving");
            }
            for (long key : forced) {
                h.getLevel().setChunkForced(net.minecraft.world.level.ChunkPos.getX(key),
                    net.minecraft.world.level.ChunkPos.getZ(key), false);
            }
            forced.clear();
            teardown(h, s);
        });
    }

    private static double horizontal(net.minecraft.world.phys.Vec3 a, net.minecraft.world.phys.Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    @GameTest(template = ARENA, batch = "event_departure_brute_toll", timeoutTicks = 900)
    public void paidBrutesWalkOffAndVanishOnlyUnseen(GameTestHelper h) {
        Settlement s = village(h, "Leaveby", 5);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.BRUTE_TOLL, true);
        WorldEventSavedData.Active a = active(h, s);
        RaiderEntity chief = chief(h, a);
        List<Entity> band = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(chief.getX() + 1, chief.getY(), chief.getZ());
        player.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), BruteTollEvent.COIN_TOLL));
        h.assertTrue(WorldEventVisitors.choose(player, a.id, chief, "pay_coins").accepted(), "paid");
        h.assertTrue(band.stream().allMatch(Entity::isInvulnerable), "harmless while leaving");
        watchDeparture(h, s, band, "brute toll", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_peddler", timeoutTicks = 900)
    public void peddlerRollsOutWithHisLlama(GameTestHelper h) {
        Settlement s = village(h, "Leavebury", 3);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.PEDDLER, true);
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> party = actors(h, a);
        WorldEventDirector.handler(WorldEventType.PEDDLER).timeout(h.getLevel(), s, a);
        watchDeparture(h, s, party, "peddler");
    }

    @GameTest(template = ARENA, batch = "event_departure_rival_envoy", timeoutTicks = 900)
    public void envoyRidesHomeUnseen(GameTestHelper h) {
        Settlement s = village(h, "Leavemoor", 5);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.RIVAL_ENVOY, true);
        WorldEventSavedData.Active a = active(h, s);
        Entity envoy = WorldEventActors.actor(h.getLevel(), a, RivalEnvoyEvent.ROLE_ENVOY);
        List<Entity> party = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(envoy.getX() + 1, envoy.getY(), envoy.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, envoy, "dismiss").accepted(), "dismissed");
        watchDeparture(h, s, party, "envoy", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_caravan", timeoutTicks = 900)
    public void caravanRollsOnDownTheRoad(GameTestHelper h) {
        Settlement s = village(h, "Leaveford", 3);
        s.radius = 14;
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.CARAVAN, true);
        WorldEventSavedData.Active a = active(h, s);
        Entity master = WorldEventActors.actor(h.getLevel(), a, CaravanEvent.ROLE_MASTER);
        List<Entity> party = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(master.getX() + 1, master.getY(), master.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, master, "pass").accepted(), "waved on");
        watchDeparture(h, s, party, "caravan", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_minstrels", timeoutTicks = 900)
    public void sentAwayMinstrelsWalkOut(GameTestHelper h) {
        Settlement s = village(h, "Leavelute", 3);
        tavern(h, s);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.MINSTRELS, true);
        WorldEventSavedData.Active a = active(h, s);
        Entity lead = WorldEventActors.actor(h.getLevel(), a, MinstrelsEvent.ROLE_LEAD);
        List<Entity> band = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(lead.getX() + 1, lead.getY(), lead.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, lead, "away").accepted(), "sent away");
        watchDeparture(h, s, band, "minstrels", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_refugees", timeoutTicks = 900)
    public void declinedRefugeesWalkOn(GameTestHelper h) {
        Settlement s = village(h, "Leavehome", 3);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.REFUGEES, true);
        WorldEventSavedData.Active a = active(h, s);
        Entity leader = WorldEventActors.actor(h.getLevel(), a, RefugeesEvent.ROLE_LEADER);
        List<Entity> family = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(leader.getX() + 1, leader.getY(), leader.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, leader, "decline").accepted(), "declined");
        watchDeparture(h, s, family, "refugees", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_stray_dog", timeoutTicks = 900)
    public void shooedStrayTrotsOff(GameTestHelper h) {
        Settlement s = village(h, "Leavedog", 3);
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.STRAY_DOG, true);
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> dog = actors(h, a);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(dog.get(0).getX() + 1, dog.get(0).getY(), dog.get(0).getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, dog.get(0), "shoo").accepted(), "shooed");
        watchDeparture(h, s, dog, "stray dog", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_wolf_pack", timeoutTicks = 900)
    public void retreatingWolvesSlinkOffUnseen(GameTestHelper h) {
        Settlement s = village(h, "Leavewolf", 3);
        h.spawn(EntityType.COW, new BlockPos(30, 1, 30));
        h.spawn(EntityType.SHEEP, new BlockPos(34, 1, 30));
        ServerPlayer player = player(h, new BlockPos(32, 1, 40));
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.WOLF_PACK, true);
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> pack = actors(h, a);
        a.state.putInt("Kills", WolfPackEvent.MAX_KILLS);
        WorldEventDirector.handler(WorldEventType.WOLF_PACK).tick(h.getLevel(), s, a);
        watchDeparture(h, s, pack, "wolf pack", player);
    }

    @GameTest(template = ARENA, batch = "event_departure_field_fox", timeoutTicks = 900)
    public void leavingFoxSlinksOffUnseen(GameTestHelper h) {
        Settlement s = village(h, "Leavefox", 3);
        plantField(h, s);
        ServerPlayer near = player(h, new BlockPos(60, 1, 60));
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.FIELD_FOX, true);
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> fox = actors(h, a);
        WorldEventDirector.handler(WorldEventType.FIELD_FOX).timeout(h.getLevel(), s, a);
        watchDeparture(h, s, fox, "fox", near);
    }

    @GameTest(template = ARENA, batch = "event_departure_wild_boar", timeoutTicks = 900)
    public void boarTrotsBackIntoTheWoodsUnseen(GameTestHelper h) {
        Settlement s = village(h, "Leaveboar", 3);
        plantField(h, s);
        ServerPlayer near = player(h, new BlockPos(60, 1, 60));
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.WILD_BOAR, true);
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> boar = actors(h, a);
        WorldEventDirector.handler(WorldEventType.WILD_BOAR).timeout(h.getLevel(), s, a);
        watchDeparture(h, s, boar, "wild boar", near);
    }

    // ------------------------------------------- unloaded is not dead (P2) --

    /**
     * An escorted caravan whose master is merely unloaded (the Banner chunk
     * loaded first after a restart) must keep the same event, stock and
     * escort; it resumes when he loads. Only a confirmed death loses it.
     */
    @GameTest(template = ARENA, batch = "event_caravan_unloaded", timeoutTicks = 100)
    public void unloadedCaravanMasterIsNotLost(GameTestHelper h) {
        Settlement s = village(h, "Reloadby", 3);
        s.radius = 14;
        ServerLevel level = h.getLevel();
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.CARAVAN, true), "a caravan stops");
        WorldEventSavedData.Active a = active(h, s);
        WanderingTrader master = (WanderingTrader) WorldEventActors.actor(level, a, CaravanEvent.ROLE_MASTER);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(master.getX() + 1, master.getY(), master.getZ());
        h.assertTrue(WorldEventVisitors.choose(player, a.id, master, "escort").accepted(), "escort accepted");
        net.minecraft.nbt.CompoundTag stock = a.state.getCompound("Stock").copy();
        net.minecraft.nbt.CompoundTag saved = master.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        UUID masterId = master.getUUID();
        // Chunk unload: the entity leaves the level without dying.
        master.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        WorldEventHandler handler = WorldEventDirector.handler(WorldEventType.CARAVAN);
        for (int i = 0; i < 5; i++) {
            a.eligibleTicks += 20;
            handler.tick(level, s, a);
        }
        h.assertTrue(active(h, s) == a, "the same event keeps running while its master is unloaded");
        h.assertTrue("escort".equals(a.state.getString("Mode")) && a.state.getBoolean("Escort"), "the escort is kept");
        h.assertTrue(stock.equals(a.state.getCompound("Stock")), "the stock is kept");
        // The chunk loads again: the same master (same UUID and tags) rejoins.
        WanderingTrader back = EntityType.WANDERING_TRADER.create(level);
        back.load(saved);
        h.assertTrue(back.getUUID().equals(masterId) && level.addFreshEntity(back), "the master loads back in");
        handler.tick(level, s, a);
        h.assertTrue(active(h, s) == a && WorldEventActors.presence(level, a, CaravanEvent.ROLE_MASTER)
            == WorldEventActors.Presence.PRESENT, "the escort resumes");
        // A confirmed death, by contrast, loses the caravan.
        back.kill();
        handler.tick(level, s, a);
        h.assertTrue(active(h, s) == null && WorldEventSavedData.get(level).row(s.id).lastOutcome.equals("caravan:caravan_lost"),
            "a dead master loses the caravan: " + WorldEventSavedData.get(level).row(s.id).lastOutcome);
        for (Entity e : level.getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(s.center).inflate(64),
                WorldEventDeparture::isDeparting)) {
            e.discard();
        }
        teardown(h, s);
        h.succeed();
    }

    // ------------------------------------------- hostile founding grace --

    /**
     * Survival QA (26 Sep): a day-0 village with no guard never gets a
     * hostile event (brute toll, wolves, boar) planned or started. After
     * three days AND a hired martial settler it can.
     */
    @GameTest(template = ARENA, batch = "event_hostile_grace", timeoutTicks = 100)
    public void newUndefendedVillageNeverMeetsHostileEvents(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        long originalTime = level.getDayTime();
        Settlement s = village(h, "Graceby", 5);
        plantField(h, s);
        h.spawn(EntityType.COW, new BlockPos(30, 1, 30));
        h.spawn(EntityType.SHEEP, new BlockPos(34, 1, 30));
        player(h, new BlockPos(32, 1, 36));
        long today = WorldEventSchedule.dayOf(level.getDayTime());
        h.assertTrue(s.raidLifecycle.prepareAtFounding(today, level.random,
            com.hearthstead.settlement.state.RaidProfile.BALANCED), "fixture founded today");
        WorldEventSavedData data = WorldEventSavedData.get(level);
        try {
            for (WorldEventType type : WorldEventType.values()) {
                if (type.hostile()) {
                    h.assertTrue(!WorldEventDirector.eligible(level, s, type), type.id() + " is not eligible on day 0");
                }
            }
            h.assertTrue(WorldEventDirector.available(level, s).stream().noneMatch(WorldEventType::hostile),
                "the planner never sees a hostile event on day 0");
            // A whole day of real planning and starting: never a hostile event.
            long base = today * 24000L;
            for (long t = 0; t < 24000; t += 250) {
                level.setDayTime(base + t);
                WorldEventDirector.observe(level, s, data, true);
                WorldEventSavedData.Row row = data.row(s.id);
                if (row != null && row.active != null) {
                    h.assertTrue(!row.active.type.hostile(), "hostile event started on day 0: " + row.active.type.id());
                    WorldEventDirector.finish(level, s, "test_teardown", null);
                }
                if (row != null && row.plannedType != null) {
                    h.assertTrue(!row.plannedType.hostile(), "hostile event planned on day 0: " + row.plannedType.id());
                }
            }
            // Three days later but still no guard: still protected.
            level.setDayTime(base + 3 * 24000L + 1000);
            h.assertTrue(!WorldEventDirector.hostileReady(level, s), "no guard, no first raid: still protected");
            // A hired Guard: now the village can face them.
            s.putRecord(UUID.randomUUID(), "Watchman", Profession.GUARD);
            h.assertTrue(WorldEventDirector.hostileReady(level, s), "day 3 plus a guard: ready");
            h.assertTrue(!WorldEventDirector.eligible(level, s, WorldEventType.BRUTE_TOLL),
                "the brute toll also waits for the raid curve (raid 3 and day 6)");
            h.assertTrue(WorldEventDirector.eligible(level, s, WorldEventType.WOLF_PACK), "wolves can come now");
            // A guard on day 1 is not enough on its own.
            level.setDayTime(base + 24000L + 1000);
            h.assertTrue(!WorldEventDirector.hostileReady(level, s), "a guard alone does not skip the 3-day grace");
        } finally {
            level.setDayTime(originalTime);
            teardown(h, s);
        }
        h.succeed();
    }

    @GameTest(template = ARENA, batch = "event_brute_toll_scaled", timeoutTicks = 100)
    public void bruteTollNeverAsksMoreThanHalfTheStores(GameTestHelper h) {
        Settlement s = village(h, "Poorby", 5);
        GameTestFixtures.register(h, s, BuildingType.WAREHOUSE, 50, 50);
        BlockPos chestPos = h.absolutePos(new BlockPos(51, 1, 51));
        h.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = (ChestBlockEntity) h.getLevel().getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        chest.setItem(1, new ItemStack(ModItems.GOLD_COIN.get(), 6));
        boolean stores = !WorldEventVisitors.stores(h.getLevel(), s).isEmpty();
        WorldEventDirector.start(h.getLevel(), s, WorldEventType.BRUTE_TOLL, true);
        WorldEventSavedData.Active a = active(h, s);
        int food = a.state.getInt("Toll"), coins = a.state.getInt("CoinToll");
        if (stores) {
            h.assertTrue(food == 5 && coins == 3, "half of 10 food and 6 Coins: toll " + food + " food / " + coins + " Coins");
        } else {
            h.assertTrue(food == BruteTollEvent.MIN_FOOD_TOLL && coins == BruteTollEvent.MIN_COIN_TOLL,
                "bare stores: the minimum toll, got " + food + " / " + coins);
        }
        h.assertTrue(BruteTollEvent.foodToll(s, 1000) <= 32 && BruteTollEvent.foodToll(s, 0) == BruteTollEvent.MIN_FOOD_TOLL
            && BruteTollEvent.coinToll(100) == BruteTollEvent.COIN_TOLL, "caps and minimums hold");
        teardown(h, s);
        h.succeed();
    }

    // ------------------------------------------ first merchant (QA #5) --

    /**
     * Survival QA #5: the founding merchant got wedged in a 1-wide hillside
     * notch and never arrived. On terraced ground the merchant is dropped
     * into such a notch after publication; the stuck recovery must still
     * bring him to the Banner.
     */
    @GameTest(template = ARENA, batch = "event_merchant_stuck", skyAccess = true, timeoutTicks = 1200)
    public void wedgedFirstMerchantStillReachesTheBanner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 6; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        // Terraced hillside to the east, with a 1-wide notch cut into it.
        for (int x = 44; x < 64; x++) for (int z = 20; z < 44; z++) {
            int height = Math.min(4, (x - 42) / 4);
            for (int y = 1; y <= height; y++) h.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
        }
        BlockPos notch = new BlockPos(50, 1, 32);
        for (int y = 1; y <= 3; y++) h.setBlock(notch.above(y - 1), Blocks.AIR);
        // A true notch: dirt walls three high on all four sides, no walkable way out.
        for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int y = 0; y < 3; y++) h.setBlock(notch.relative(side).above(y), Blocks.DIRT);
        }
        BlockPos hearth = h.absolutePos(new BlockPos(32, 1, 32));
        level.setBlockAndUpdate(hearth, com.hearthstead.registry.ModBlocks.HEARTH.get().defaultBlockState());
        boolean previous = com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance;
        Settlement settlement;
        try {
            com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance = true;
            settlement = com.hearthstead.settlement.SettlementManager.tryFound(level, hearth);
        } finally {
            com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance = previous;
        }
        h.assertTrue(settlement != null && settlement.mayorId != null, "founded with a Mayor");
        settlement.radius = 8;
        h.assertTrue(com.hearthstead.event.EarlyCoinMerchant.visit(level, settlement), "the first merchant is published");
        WanderingTrader merchant = level.getEntitiesOfClass(WanderingTrader.class,
            new net.minecraft.world.phys.AABB(hearth).inflate(40),
            e -> e.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement")).get(0);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.teleportTo(hearth.getX() + .5, hearth.getY() + 1, hearth.getZ() - 4.5);
        // Wedge him in the notch (the survival bug), facing a wall.
        BlockPos wedge = h.absolutePos(notch);
        merchant.moveTo(wedge.getX() + .5, wedge.getY(), wedge.getZ() + .5, 90F, 0F);
        merchant.getNavigation().stop();
        Settlement founded = settlement;
        h.succeedWhen(() -> {
            boolean arrived = merchant.getPersistentData().getBoolean("HearthsteadMerchantArrived");
            h.assertTrue(arrived && merchant.distanceToSqr(hearth.getX() + .5, hearth.getY(), hearth.getZ() + .5) <= 8 * 8,
                "the wedged merchant reaches the Banner (now " + merchant.blockPosition() + ")");
            merchant.discard();
            for (var actor : com.hearthstead.settlement.SettlementManager.loadedMembers(level, founded)) actor.discard();
            SettlementSavedData.get(level).settlements.remove(founded.id);
            level.setBlockAndUpdate(hearth, Blocks.AIR.defaultBlockState());
        });
    }
}

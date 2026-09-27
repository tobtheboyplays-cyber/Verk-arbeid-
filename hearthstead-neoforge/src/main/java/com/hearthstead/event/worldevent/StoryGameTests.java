package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.RaidLifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Story lane (batch {@code visitors_story}): every named visitor spawns on
 * safe ground with its fixed name and look, talks, gives its real gift or
 * consequence, is written to the visitor memory and walks off; the grace
 * keeps threats away from a young village; the threat ladder escalates to a
 * sworn raid captain; and an upsetting choice posts exactly one "will
 * remember this" cue that the next visit's lines reflect.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class StoryGameTests {
    private static final String ARENA = "empty64";
    private static final String BATCH = "visitors_story";

    // ------------------------------------------------------------ fixtures --

    private static Settlement village(GameTestHelper h, String name, int records) {
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, h.absolutePos(new BlockPos(32, 1, 32)));
        s.radius = 24;
        for (int i = 0; i < records; i++) s.putRecord(UUID.randomUUID(), "Resident" + i, Profession.NONE);
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        WorldEventDirector.resetTransientForTests();
        StoryChat.resetForTests();
        return s;
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos rel) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.CREATIVE);
        BlockPos at = h.absolutePos(rel);
        player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        player.getInventory().add(new ItemStack(Items.BREAD, 64));
        player.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), 64));
        return player;
    }

    private static WorldEventSavedData.Row row(GameTestHelper h, Settlement s) {
        return WorldEventSavedData.get(h.getLevel()).row(s.id);
    }

    private static WorldEventSavedData.Active active(GameTestHelper h, Settlement s) {
        WorldEventSavedData.Row row = row(h, s);
        return row == null ? null : row.active;
    }

    private static VisitorMemory.Book book(GameTestHelper h, Settlement s) {
        return VisitorMemory.get(h.getLevel()).book(s.id);
    }

    private static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) n += player.getInventory().getItem(i).getCount();
        }
        return n;
    }

    private static void cleanup(GameTestHelper h, Settlement s, List<? extends Entity> extra) {
        ServerLevel level = h.getLevel();
        WorldEventDirector.finish(level, s, "test_teardown", null);
        for (Entity e : extra) if (!e.isRemoved()) e.discard();
        s.raidCaptains.clear();
        SettlementSavedData.get(level).settlements.remove(s.id);
        VisitorMemory.get(level).forget(s.id);
        StoryVisitEvent.forceNext = null;
        StoryThreatEvent.forceNext = null;
        StoryThreatEvent.forceStep = 0;
    }

    private static boolean start(GameTestHelper h, Settlement s, StoryCharacter c, int step) {
        if (c.threat()) {
            StoryThreatEvent.forceNext = c;
            StoryThreatEvent.forceStep = step;
            return WorldEventDirector.start(h.getLevel(), s, WorldEventType.STORY_THREAT, true);
        }
        StoryVisitEvent.forceNext = c;
        return WorldEventDirector.start(h.getLevel(), s, WorldEventType.STORY_VISIT, true);
    }

    private static Entity waiting(GameTestHelper h, WorldEventSavedData.Active a) {
        WorldEventHandler handler = WorldEventDirector.handler(a.type);
        for (Entity e : WorldEventActors.actors(h.getLevel(), a)) if (handler.awaitingAnswer(a, e)) return e;
        return null;
    }

    /** The first living actor stands on safe ground with its fixed name, face and costume. */
    private static void assertNamedVisitor(GameTestHelper h, Entity e, StoryCharacter c) {
        h.assertTrue(e instanceof SettlerEntity, c.id() + ": a settler-rig visitor");
        SettlerEntity s = (SettlerEntity) e;
        h.assertTrue(c.displayName().equals(WorldEventActors.plainName(s)), c.id() + ": fixed name, got "
            + WorldEventActors.plainName(s));
        h.assertTrue(s.getAppearanceSeed() == c.seed(), c.id() + ": fixed face seed");
        h.assertTrue(s.getLookCostume() == StoryLooks.costumeId(c.costume()) && s.getLookCostume() > 0,
            c.id() + ": own costume " + c.costume() + " (id " + s.getLookCostume() + ")");
        h.assertTrue(WorldEventCreatures.safeFeet(h.getLevel(), s.blockPosition(), 0.6F, 1.8F),
            c.id() + ": spawned on safe ground at " + s.blockPosition());
    }

    /** A friendly visitor: spawns, talks, answers once, is remembered, walks off. */
    private static void visit(GameTestHelper h, StoryCharacter c, String answer, Item gift, int giftAtLeast,
                              java.util.function.Consumer<Settlement> setup) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story " + c.id(), 6);
        setup.accept(s);
        ServerPlayer p = player(h, new BlockPos(32, 1, 37));
        int before = gift == null ? 0 : count(p, gift);
        h.assertTrue(start(h, s, c, 1), c.id() + " starts");
        WorldEventSavedData.Active a = active(h, s);
        Entity visitor = waiting(h, a);
        h.assertTrue(visitor != null, c.id() + ": a visitor waits to talk");
        assertNamedVisitor(h, visitor, c);
        h.assertTrue(com.hearthstead.conversation.ConversationService.isBound(visitor), c.id() + ": talk UI bound");
        String graph = com.hearthstead.conversation.ConversationService.boundGraph(visitor);
        h.assertTrue(com.hearthstead.conversation.ConversationGraphs.get(graph) != null, c.id() + ": talk graph " + graph);
        h.assertTrue(book(h, s).visits(c.id()) == 1, c.id() + ": the visit is remembered");
        h.assertTrue(book(h, s).person(c.id()).lastSeen != null, c.id() + ": with a snapshot of the village");
        p.teleportTo(visitor.getX() + 1, visitor.getY(), visitor.getZ());
        WorldEventVisitors.Result r = WorldEventVisitors.choose(p, a.id, visitor, answer);
        h.assertTrue(r.accepted(), c.id() + ": answer " + answer + " taken: " + r.message().getString());
        h.assertTrue(active(h, s) == null, c.id() + ": the visit ends");
        h.assertTrue(row(h, s).lastOutcome.equals("story_visit:" + answer), c.id() + ": outcome " + row(h, s).lastOutcome);
        if (gift != null) {
            h.assertTrue(count(p, gift) - before >= giftAtLeast, c.id() + ": the gift arrives ("
                + (count(p, gift) - before) + " of " + giftAtLeast + ")");
        }
        h.assertTrue(!visitor.getPersistentData().contains(WorldEventDirector.TAG) || visitor.isRemoved(),
            c.id() + ": no longer an event actor (walking off)");
        cleanup(h, s, List.of(visitor));
        h.succeed();
    }

    // -------------------------------------------------------------- visits --

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_hollins_bring_a_basket(GameTestHelper h) {
        visit(h, StoryCharacter.HOLLINS, "thank", Items.EGG, 4, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_pell_rook_brings_the_lords_letter(GameTestHelper h) {
        visit(h, StoryCharacter.PELL_ROOK, "receive", ModItems.GOLD_COIN.get(), 3, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_odo_remembers_his_customers(GameTestHelper h) {
        visit(h, StoryCharacter.ODO, "thank", null, 0, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_wenna_sings(GameTestHelper h) {
        visit(h, StoryCharacter.WENNA, "listen", null, 0, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_anselm_blesses(GameTestHelper h) {
        visit(h, StoryCharacter.ANSELM, "blessing", null, 0, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_gerd_leaves_healing_draughts(GameTestHelper h) {
        visit(h, StoryCharacter.GERD, "heal", Items.POTION, 2, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_brannoc_gives_a_shield_once(GameTestHelper h) {
        visit(h, StoryCharacter.BRANNOC, "inspect", Items.SHIELD, 1, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_hilde_tells_the_story(GameTestHelper h) {
        visit(h, StoryCharacter.HILDE, "listen", null, 0, s -> { });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_brisks_join_the_village(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story brisks join", 4);
        ServerPlayer p = player(h, new BlockPos(32, 1, 37));
        int population = s.population();
        h.assertTrue(start(h, s, StoryCharacter.BRISKS, 1), "brisks start");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> family = new ArrayList<>(WorldEventActors.actors(level, a));
        Entity aldo = waiting(h, a);
        assertNamedVisitor(h, aldo, StoryCharacter.BRISKS);
        p.teleportTo(aldo.getX() + 1, aldo.getY(), aldo.getZ());
        h.assertTrue(WorldEventVisitors.choose(p, a.id, aldo, "take_in").accepted(), "take them in");
        h.assertTrue(s.population() == population + family.size(), "the family joins: " + population + " -> "
            + s.population() + " (" + family.size() + ")");
        for (Entity e : family) {
            h.assertTrue(e instanceof SettlerEntity st && st.isBound() && s.id.equals(st.getSettlementId())
                && st.getLookCostume() == 0, "each newcomer is a bound settler in their own clothes");
        }
        cleanup(h, s, family);
        h.succeed();
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void story_a_settler_thanks_the_players(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story thanks", 0);
        SettlerEntity farmer = h.spawn(ModEntities.SETTLER.get(), new BlockPos(28, 1, 32));
        farmer.bindTo(s.id, s.center);
        s.putRecord(farmer.getUUID(), "Edda", Profession.FARMER);
        farmer.setProfessionProjection(Profession.FARMER);
        farmer.setSettlerName("Edda");
        farmer.setHunger(100);
        farmer.setEnergy(100);
        s.putRecord(UUID.randomUUID(), "Other", Profession.NONE);
        ServerPlayer p = player(h, new BlockPos(36, 1, 32));
        int bread = count(p, Items.BREAD);
        h.assertTrue(start(h, s, StoryCharacter.THANKS, 1), "thanks starts");
        h.onEachTick(() -> {
            if (active(h, s) != null) WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false);
        });
        h.succeedWhen(() -> {
            h.assertTrue(active(h, s) == null, "the thanks is given");
            h.assertTrue(row(h, s).lastOutcome.equals("story_visit:thanked"), "outcome " + row(h, s).lastOutcome);
            h.assertTrue(count(p, Items.BREAD) >= bread + 3, "the farmer's bread arrives");
            h.assertTrue(book(h, s).flag("thanks:day10") || book(h, s).flag("thanks:first_raid_held"),
                "the milestone is thanked once");
            farmer.discard();
            cleanup(h, s, List.of());
        });
    }

    // ------------------------------------------------ remember this (cue) --

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_upsetting_choice_is_remembered_once_and_changes_the_next_visit(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story remember", 4);
        ServerPlayer p = player(h, new BlockPos(32, 1, 37));
        h.assertTrue(start(h, s, StoryCharacter.BRISKS, 1), "brisks start");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> first = new ArrayList<>(WorldEventActors.actors(level, a));
        Entity aldo = waiting(h, a);
        p.teleportTo(aldo.getX() + 1, aldo.getY(), aldo.getZ());
        int cues = StoryChat.CUES.get();
        h.assertTrue(WorldEventVisitors.choose(p, a.id, aldo, "decline").accepted(), "decline");
        h.assertTrue(StoryChat.CUES.get() == cues + 1, "exactly one 'will remember this' cue ("
            + (StoryChat.CUES.get() - cues) + ")");
        VisitorMemory.Book book = book(h, s);
        h.assertTrue(book.lastMood("brisks") == VisitorMemory.DISPLEASED && "decline".equals(book.lastChoice("brisks")),
            "the choice is in the memory first");
        h.assertTrue(book.log().stream().anyMatch(e -> e.who().equals("brisks") && e.mood() < 0), "logged");
        // A replayed click is refused: no second cue.
        h.assertTrue(!WorldEventVisitors.choose(p, a.id, aldo, "decline").accepted()
            && StoryChat.CUES.get() == cues + 1, "a replay posts nothing");
        for (Entity e : first) if (!e.isRemoved()) e.discard();
        // The next visit remembers being turned away.
        h.assertTrue(start(h, s, StoryCharacter.BRISKS, 1), "brisks come back");
        WorldEventSavedData.Active again = active(h, s);
        List<String> keys = StoryWorld.readKeys(again.state);
        h.assertTrue(keys.contains(StoryLines.PREFIX + "brisks.greet_upset"), "the return greeting is the upset one: " + keys);
        cleanup(h, s, WorldEventActors.actors(level, again));
        h.succeed();
    }

    // ------------------------------------------------------------ threats --

    private static void completeFirstRaid(Settlement settlement) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        UUID participant = UUID.randomUUID();
        boolean complete = lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(plan)
            && lifecycle.beginFirstRaid(plan)
            && lifecycle.recordParticipant(participant)
            && lifecycle.sealParticipants()
            && lifecycle.recordTerminalParticipant(participant)
            && lifecycle.completeFirstRaid(false);
        if (!complete) throw new IllegalStateException("could not complete first raid fixture");
        settlement.raidLifecycle = lifecycle;
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 60)
    public static void story_threats_wait_for_the_grace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story grace", 6);
        // No martial settler and no first raid: never ready for a hostile visit.
        h.assertTrue(!WorldEventDirector.eligible(level, s, WorldEventType.STORY_THREAT),
            "a young, unguarded village gets no threats");
        h.assertTrue(WorldEventType.STORY_THREAT.hostile() && !WorldEventType.STORY_VISIT.hostile(),
            "the threat type is hostile (Peaceful and grace rules), the visits are not");
        cleanup(h, s, List.of());
        h.succeed();
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void story_threat_ladder_escalates_to_a_sworn_captain(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story ladder", 6);
        completeFirstRaid(s);
        ServerPlayer p = player(h, new BlockPos(32, 1, 37));
        // Step 1: "You cannot stay here." Defied: one cue, and the ladder is armed for the last warning.
        h.assertTrue(start(h, s, StoryCharacter.SIGRUN, 1), "Sigrun comes");
        WorldEventSavedData.Active a = active(h, s);
        Entity sigrun = waiting(h, a);
        assertNamedVisitor(h, sigrun, StoryCharacter.SIGRUN);
        h.assertTrue(StoryWorld.readKeys(a.state).contains(StoryLines.PREFIX + "sigrun.warning"), "the first warning");
        p.teleportTo(sigrun.getX() + 1, sigrun.getY(), sigrun.getZ());
        int cues = StoryChat.CUES.get();
        h.assertTrue(WorldEventVisitors.choose(p, a.id, sigrun, "defy").accepted(), "defy");
        h.assertTrue(StoryChat.CUES.get() == cues + 1, "Varg Ironjaw will remember this (once)");
        VisitorMemory.Ladder ladder = book(h, s).existingLadder(StoryRules.LADDER_VARG);
        h.assertTrue(ladder != null && ladder.step == 2 && VisitorMemory.Ladder.ARMED.equals(ladder.status),
            "the ladder is armed for the last warning");
        sigrun.discard();
        // Step 2: "This is your last warning", with two passive enforcers.
        h.assertTrue(start(h, s, StoryCharacter.SIGRUN, 2), "Sigrun returns");
        WorldEventSavedData.Active b = active(h, s);
        List<RaiderEntity> band = new ArrayList<>();
        for (Entity e : WorldEventActors.actors(level, b)) if (e instanceof RaiderEntity r) band.add(r);
        h.assertTrue(band.size() == 2, "two enforcers, got " + band.size());
        for (RaiderEntity r : band) h.assertTrue(r.isInvulnerable(), "enforcers hold while she talks");
        h.assertTrue(StoryWorld.readKeys(b.state).contains(StoryLines.PREFIX + "sigrun.memory_defied"),
            "the last warning remembers the defiance");
        Entity herald = waiting(h, b);
        p.teleportTo(herald.getX() + 1, herald.getY(), herald.getZ());
        h.assertTrue(WorldEventVisitors.choose(p, b.id, herald, "defy").accepted(), "defy again");
        VisitorMemory.Ladder sworn = book(h, s).existingLadder(StoryRules.LADDER_VARG);
        h.assertTrue(VisitorMemory.Ladder.SWORN.equals(sworn.status), "Varg is sworn: " + sworn.status);
        h.assertTrue(s.raidCaptains.stream().anyMatch(c -> c.name().equals(StoryCharacter.VARG)), "Varg joins the gallery");
        // The raid director's pick takes Varg exactly once.
        RaidCaptain first = RaidDirector.pickCaptain(s, RandomSource.create(1));
        h.assertTrue(first.name().equals(StoryCharacter.VARG), "Varg leads the next raid, got " + first.name());
        h.assertTrue(book(h, s).existingLadder(StoryRules.LADDER_VARG).planned, "consumed once");
        h.assertTrue(StoryHooks.swornCaptain(s) == null, "and only once");
        List<Entity> all = new ArrayList<>(WorldEventActors.actors(level, b));
        h.onEachTick(() -> {
            if (active(h, s) != null) WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false);
        });
        h.succeedWhen(() -> {
            h.assertTrue(b.state.getBoolean("Hostile"), "after the warning the enforcers fight");
            for (RaiderEntity r : band) h.assertTrue(!r.isInvulnerable() || !r.isAlive(), "and can be hurt");
            for (RaiderEntity r : band) if (r.isAlive()) r.kill();
            h.assertTrue(active(h, s) == null || band.stream().noneMatch(LivingEntity::isAlive), "fight over");
            cleanup(h, s, all);
        });
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_bailiff_ladder_ends_when_the_tax_is_paid(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story bailiff", 6);
        ServerPlayer p = player(h, new BlockPos(32, 1, 37));
        h.assertTrue(start(h, s, StoryCharacter.HAMON, 1), "Hamon comes");
        WorldEventSavedData.Active a = active(h, s);
        Entity hamon = waiting(h, a);
        assertNamedVisitor(h, hamon, StoryCharacter.HAMON);
        p.teleportTo(hamon.getX() + 1, hamon.getY(), hamon.getZ());
        int coins = count(p, ModItems.GOLD_COIN.get());
        int tax = a.state.getCompound("Vars").getInt("i");
        int before = RivalEnvoyEvent.relation(level, s);
        h.assertTrue(WorldEventVisitors.choose(p, a.id, hamon, "pay_tax").accepted(), "pay the tax");
        h.assertTrue(count(p, ModItems.GOLD_COIN.get()) == coins - tax, "exactly the tax is paid (" + tax + ")");
        h.assertTrue(book(h, s).existingLadder(StoryRules.LADDER_HAMON).over(), "the bailiff's ladder is over");
        h.assertTrue(RivalEnvoyEvent.relation(level, s) > before, "the lord's standing rises");
        cleanup(h, s, List.of(hamon));
        h.succeed();
    }

    // ------------------------------------------------------------ Gorm (T3) --

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 100)
    public static void story_gorm_remembers_and_brings_more_brutes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Story gorm", 6);
        player(h, new BlockPos(32, 1, 37));
        VisitorMemory.Book book = book(h, s);
        book.remember(StoryHooks.GORM, StoryCharacter.GORM, "defeated", VisitorMemory.NEUTRAL, 1);
        h.assertTrue(WorldEventDirector.start(level, s, WorldEventType.BRUTE_TOLL, true), "the toll starts");
        WorldEventSavedData.Active a = active(h, s);
        List<Entity> band = WorldEventActors.actors(level, a);
        h.assertTrue(band.size() == BruteTollEvent.BAND + 1, "a fourth brute after a killed band, got " + band.size());
        h.assertTrue(band.stream().anyMatch(e -> StoryCharacter.GORM.equals(WorldEventActors.plainName(e))),
            "the chief is Gorm Stonebelly");
        h.assertTrue(book.visits(StoryHooks.GORM) == 1, "Gorm's visit is remembered");
        cleanup(h, s, band);
        h.succeed();
    }
}

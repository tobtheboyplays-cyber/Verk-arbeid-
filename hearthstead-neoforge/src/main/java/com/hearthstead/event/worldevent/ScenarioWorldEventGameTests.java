package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): every answer and the no-answer branch of all 11
 * world events, one GameTest each (batch {@code scenario_event}).
 *
 * <p>Each test starts the event on a fresh settlement, answers through the
 * real server path ({@link WorldEventVisitors#choose}; persuasion answers
 * roll for real, on a seeded level random so both outcomes are reached),
 * then lets the director run the event until it ends <b>by itself</b>: no
 * hand-called finish. The director is observed every tick (20x the normal
 * pace, so budgets run out quickly) except for the caravan escort, which is
 * walked at the natural pace with the escort at the master's side.
 *
 * <p>Every answered branch also proves: the price was taken exactly once
 * from the answering player, a replayed click is refused and costs nothing,
 * and a second player's answer is refused (co-op: first answer wins).
 * Every branch proves the recorded outcome and that every actor is gone or,
 * for a kept one (an adopted dog, refugees taken in), no longer an event
 * actor.
 */
@GameTestHolder(Hearthstead.MODID)
public final class ScenarioWorldEventGameTests {
    private static final String TEMPLATE = Hearthstead.MODID + ":empty64";

    enum How { ANSWER, NONE, KILL_ALL, HUNTER_KILL }

    record Branch(WorldEventType type, How how, String id, Set<String> expected, boolean natural, int timeout) {
        String testName() {
            return "scenario_event_" + type.id() + "_" + id;
        }
    }

    private static Branch answer(WorldEventType type, String id, String... expected) {
        return new Branch(type, How.ANSWER, id, Set.of(expected), false, 1600);
    }

    private static Branch none(WorldEventType type, String... expected) {
        return new Branch(type, How.NONE, "no_answer", Set.of(expected), false, 1800);
    }

    static final List<Branch> BRANCHES = List.of(
        none(WorldEventType.PEDDLER, "left"),
        answer(WorldEventType.REFUGEES, "accept", "accepted"),
        answer(WorldEventType.REFUGEES, "work_yes", "accepted"),
        answer(WorldEventType.REFUGEES, "work_no", "offended"),
        answer(WorldEventType.REFUGEES, "feed", "fed"),
        answer(WorldEventType.REFUGEES, "decline", "declined"),
        none(WorldEventType.REFUGEES, "moved_on"),
        answer(WorldEventType.MINSTRELS, "host", "feast"),
        answer(WorldEventType.MINSTRELS, "tips", "tips"),
        answer(WorldEventType.MINSTRELS, "away", "sent_away"),
        none(WorldEventType.MINSTRELS, "tips"),
        new Branch(WorldEventType.FIELD_FOX, How.KILL_ALL, "killed", Set.of("killed"), false, 600),
        none(WorldEventType.FIELD_FOX, "chased_off", "stole_and_left"),
        new Branch(WorldEventType.WOLF_PACK, How.KILL_ALL, "slain", Set.of("slain"), false, 600),
        none(WorldEventType.WOLF_PACK, "driven_off", "took_livestock"),
        new Branch(WorldEventType.WILD_BOAR, How.KILL_ALL, "killed", Set.of("killed"), false, 600),
        new Branch(WorldEventType.WILD_BOAR, How.HUNTER_KILL, "hunter_kill", Set.of("hunter_kill"), false, 600),
        none(WorldEventType.WILD_BOAR, "wandered_off"),
        none(WorldEventType.TAVERN_BRAWL, "tired"),
        answer(WorldEventType.BRUTE_TOLL, "pay_food", "paid_food"),
        answer(WorldEventType.BRUTE_TOLL, "pay_coins", "paid_coins"),
        answer(WorldEventType.BRUTE_TOLL, "talked_down", "talked_down"),
        answer(WorldEventType.BRUTE_TOLL, "insulted", "retreated", "defeated"),
        answer(WorldEventType.BRUTE_TOLL, "refuse", "retreated", "defeated"),
        answer(WorldEventType.BRUTE_TOLL, "attack", "retreated", "defeated"),
        none(WorldEventType.BRUTE_TOLL, "took_food"),
        answer(WorldEventType.STRAY_DOG, "feed_dog", "adopted"),
        answer(WorldEventType.STRAY_DOG, "shoo", "shooed"),
        none(WorldEventType.STRAY_DOG, "wandered_off"),
        new Branch(WorldEventType.CARAVAN, How.ANSWER, "escort", Set.of("escorted"), true, 9000),
        // Co-op: the escort logs out mid-route; the caravan rolls on alone and pays nothing.
        new Branch(WorldEventType.CARAVAN, How.ANSWER, "escort_abandoned", Set.of("abandoned"), true, 2400),
        // Restart mid-event: the saved event is reloaded, then answered.
        answer(WorldEventType.REFUGEES, "reload_then_accept", "accepted"),
        answer(WorldEventType.CARAVAN, "pass", "passed"),
        none(WorldEventType.CARAVAN, "moved_on"),
        answer(WorldEventType.RIVAL_ENVOY, "pact", "pact"),
        answer(WorldEventType.RIVAL_ENVOY, "pact_failed", "pact_failed"),
        answer(WorldEventType.RIVAL_ENVOY, "befriend", "befriend"),
        answer(WorldEventType.RIVAL_ENVOY, "insult", "insult"),
        answer(WorldEventType.RIVAL_ENVOY, "dismiss", "dismiss"),
        none(WorldEventType.RIVAL_ENVOY, "ignored"));

    @GameTestGenerator
    public static Collection<TestFunction> scenarioEvents() {
        List<TestFunction> out = new ArrayList<>();
        for (Branch branch : BRANCHES) {
            out.add(new TestFunction("scenario_event", branch.testName(), TEMPLATE, Rotation.NONE,
                branch.timeout(), 0L, true, helper -> run(helper, branch)));
        }
        out.add(new TestFunction("scenario_event", "scenario_event_catalogue_complete", TEMPLATE, Rotation.NONE,
            20, 0L, true, ScenarioWorldEventGameTests::everyEventHasANoAnswerBranch));
        return out;
    }

    /** Guards the list: a new event type without a scenario fails here. */
    static void everyEventHasANoAnswerBranch(GameTestHelper h) {
        for (WorldEventType type : WorldEventType.values()) {
            boolean listed = BRANCHES.stream().anyMatch(b -> b.type() == type
                && (b.how() == How.NONE || type == WorldEventType.TAVERN_BRAWL));
            h.assertTrue(listed, "world event " + type.id() + " has no scenario no-answer branch");
        }
        h.succeed();
    }

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
        return s;
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos rel) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        // Creative: hostile event creatures ignore the observers, so every
        // branch ends by the event's own rules, never by a dead test player.
        player.setGameMode(GameType.CREATIVE);
        BlockPos at = h.absolutePos(rel);
        player.teleportTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        return player;
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

    private static void tavern(GameTestHelper h, Settlement s) {
        GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN, new BlockPos(20, 1, 20),
            new BlockPos(18, 2, 18), BoundingBox.fromCorners(h.absolutePos(new BlockPos(18, 1, 18)),
                h.absolutePos(new BlockPos(28, 4, 28))));
    }

    private static void plantField(GameTestHelper h, Settlement s) {
        GameTestFixtures.register(h, s, BuildingType.FARMHOUSE, 40, 40);
        for (int x = 4; x < 12; x++) for (int z = 4; z < 12; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND);
            h.setBlock(new BlockPos(x, 1, z), ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        }
    }

    private static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) n += player.getInventory().getItem(i).getCount();
        }
        return n;
    }

    private static WorldEventSavedData.Row row(GameTestHelper h, Settlement s) {
        return WorldEventSavedData.get(h.getLevel()).row(s.id);
    }

    private static WorldEventSavedData.Active active(GameTestHelper h, Settlement s) {
        WorldEventSavedData.Row row = row(h, s);
        return row == null ? null : row.active;
    }

    /** A seed whose first nextInt(100) lands on the wanted side of the persuasion roll. */
    private static long seedFor(int chance, boolean success) {
        for (long seed = 1; seed < 10_000; seed++) {
            int roll = RandomSource.create(seed).nextInt(100);
            if ((roll < chance) == success) return seed;
        }
        throw new IllegalStateException("no seed for chance " + chance);
    }

    // --------------------------------------------------------------- body --

    static void run(GameTestHelper h, Branch b) {
        ServerLevel level = h.getLevel();
        Settlement s = village(h, "Scen " + b.type().id(), 5);
        if (b.type() == WorldEventType.CARAVAN) s.radius = 14; // keep the roadside stop near the arena
        List<SettlerEntity> residents = new ArrayList<>();
        Container[] stores = {null};
        switch (b.type()) {
            case MINSTRELS -> tavern(h, s);
            case TAVERN_BRAWL -> {
                tavern(h, s);
                residents.add(resident(h, s, new BlockPos(21, 1, 21), Profession.FARMER));
                residents.add(resident(h, s, new BlockPos(24, 1, 24), Profession.FARMER));
            }
            case FIELD_FOX, WILD_BOAR -> plantField(h, s);
            case WOLF_PACK -> {
                h.spawn(EntityType.COW, new BlockPos(30, 1, 30));
                h.spawn(EntityType.SHEEP, new BlockPos(34, 1, 30));
            }
            case BRUTE_TOLL -> {
                GameTestFixtures.register(h, s, BuildingType.WAREHOUSE, 50, 50);
                h.setBlock(new BlockPos(51, 1, 51), Blocks.CHEST);
                stores[0] = (Container) level.getBlockEntity(h.absolutePos(new BlockPos(51, 1, 51)));
                stores[0].setItem(0, new ItemStack(Items.BREAD, 40));
                h.assertTrue(!WorldEventVisitors.stores(level, s).isEmpty(),
                    "fixture: the warehouse chest must count as the settlement's stores");
            }
            default -> { }
        }
        ServerPlayer first = player(h, new BlockPos(32, 1, 36));
        ServerPlayer second = player(h, new BlockPos(33, 1, 36));
        first.getInventory().add(new ItemStack(Items.BREAD, 64));
        first.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), 64));
        second.getInventory().add(new ItemStack(Items.BREAD, 16));
        second.getInventory().add(new ItemStack(ModItems.GOLD_COIN.get(), 16));
        int population = s.population();
        int relationBefore = b.type() == WorldEventType.RIVAL_ENVOY ? RivalEnvoyEvent.relation(level, s) : 0;
        float[] moraleBefore = new float[residents.size()];
        for (int i = 0; i < residents.size(); i++) moraleBefore[i] = residents.get(i).getMorale();

        h.assertTrue(WorldEventDirector.start(level, s, b.type(), true), b.type().id() + " must start on a fresh settlement");
        WorldEventSavedData.Active a = active(h, s);
        WorldEventHandler handler = WorldEventDirector.handler(b.type());
        List<Entity> actors = new ArrayList<>(WorldEventActors.actors(level, a));
        h.assertTrue(!actors.isEmpty() || b.type() == WorldEventType.TAVERN_BRAWL, b.type().id() + " spawns its actors");
        int toll = a.state.getInt("Toll");

        if (b.id().startsWith("reload_then_")) {
            // A server restart: the event file goes to NBT and back, transient state is gone.
            WorldEventSavedData saved = WorldEventSavedData.get(level);
            level.getDataStorage().set("hearthstead_world_events",
                WorldEventSavedData.load(saved.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()),
                    level.registryAccess()));
            WorldEventDirector.resetTransientForTests();
            a = active(h, s);
            h.assertTrue(a != null && a.type == b.type(), "the running event survives the restart");
        }
        final WorldEventSavedData.Active started = a;
        switch (b.how()) {
            case ANSWER -> answerBranch(h, s, started, handler, actors, b, first, second);
            case KILL_ALL -> {
                for (Entity e : actors) if (e instanceof LivingEntity living) living.kill();
            }
            case HUNTER_KILL -> {
                Entity boar = WorldEventActors.actor(level, a, WildBoarEvent.ROLE_BOAR);
                h.assertTrue(boar instanceof LivingEntity, "the boar is there");
                SettlerEntity hunter = resident(h, s, h.relativePos(boar.blockPosition()).offset(2, 0, 0), Profession.HUNTER);
                residents.add(hunter);
                ((LivingEntity) boar).hurt(level.damageSources().mobAttack(hunter), 1000.0F);
                h.assertTrue(!boar.isAlive(), "the hunter's blow kills the boar");
            }
            case NONE -> { }
        }

        h.onEachTick(() -> {
            WorldEventSavedData.Active live = active(h, s);
            if (live == null) return;
            if (b.natural() && "escort".equals(b.id())) {
                Entity master = WorldEventActors.actor(level, live, CaravanEvent.ROLE_MASTER);
                if (master != null) first.teleportTo(master.getX() + 1.5, master.getY(), master.getZ());
                // The escort wins the ambush (the player's fight is not what this measures).
                Entity bandit = WorldEventActors.actor(level, live, CaravanEvent.ROLE_BANDIT);
                if (bandit instanceof LivingEntity l && l.isAlive()) l.kill();
            }
            if (!b.natural() || level.getGameTime() % 20 == 0) {
                WorldEventDirector.observe(level, s, WorldEventSavedData.get(level), false);
            }
        });

        h.succeedWhen(() -> {
            WorldEventSavedData.Row row = row(h, s);
            h.assertTrue(row != null, b.testName() + ": the settlement's event row exists");
            WorldEventSavedData.Active live = row.active;
            h.assertTrue(live == null, b.testName() + ": the event must end by itself (eligible "
                + (live == null ? "-" : live.eligibleTicks + "/" + live.type.budgetTicks() + " state " + live.state) + ")");
            String outcome = row.lastOutcome;
            String prefix = b.type().id() + ":";
            h.assertTrue(outcome != null && outcome.startsWith(prefix)
                    && b.expected().contains(outcome.substring(prefix.length())),
                b.testName() + ": outcome must be one of " + b.expected() + ", got " + outcome);
            for (Entity e : actors) {
                boolean kept = !e.isRemoved() && !e.getPersistentData().contains(WorldEventDirector.TAG);
                // A killed actor still plays its death fall for a moment before removal.
                h.assertTrue(e.isRemoved() || kept || !e.isAlive(), b.testName() + ": actor " + e.getType().getDescriptionId()
                    + " is gone or released after the event");
            }
            switch (b.type()) {
                case REFUGEES -> {
                    boolean joined = b.id().endsWith("accept") || b.id().equals("work_yes");
                    h.assertTrue(s.population() == population + (joined ? RefugeesEvent.FAMILY : 0),
                        b.testName() + ": population " + population + " -> " + s.population());
                    if (joined) {
                        for (Entity e : actors) {
                            h.assertTrue(e instanceof SettlerEntity settler && !e.isRemoved() && settler.isBound()
                                && s.id.equals(settler.getSettlementId()), "each refugee stays as a bound settler");
                        }
                    }
                    if (b.id().equals("work_yes")) {
                        boolean hoe = !level.getEntitiesOfClass(ItemEntity.class, new AABB(s.center).inflate(3),
                            i -> i.getItem().is(Items.IRON_HOE)).isEmpty();
                        h.assertTrue(hoe, "the working family brings its hoe");
                    }
                }
                case STRAY_DOG -> {
                    if (b.id().equals("feed_dog")) {
                        Entity dog = actors.get(0);
                        h.assertTrue(dog.isAlive() && dog.getUUID().equals(row.villageDog), "the fed stray is the village dog");
                    } else {
                        h.assertTrue(row.villageDog == null, "no village dog unless it was fed");
                    }
                }
                case BRUTE_TOLL -> {
                    int left = stores[0].getItem(0).getCount();
                    int want = b.how() == How.NONE ? 40 - toll : 40;
                    h.assertTrue(left == want, b.testName() + ": the stores lose exactly the toll only when nobody"
                        + " answered (toll " + toll + ", left " + left + ", want " + want + ")");
                }
                case RIVAL_ENVOY -> {
                    int delta = b.how() == How.NONE ? -5 : RivalEnvoyEvent.RELATION.getOrDefault(b.id(), 0);
                    int want = Math.max(-100, Math.min(100, relationBefore + delta));
                    h.assertTrue(RivalEnvoyEvent.relation(level, s) == want, b.testName() + ": the lord's standing "
                        + relationBefore + " -> " + RivalEnvoyEvent.relation(level, s) + ", want " + want);
                }
                case TAVERN_BRAWL -> {
                    for (int i = 0; i < residents.size(); i++) {
                        h.assertTrue(!residents.get(i).isRemoved() && residents.get(i).getMorale() < moraleBefore[i],
                            "a brawler is never discarded, and the brawl sours the mood");
                    }
                }
                default -> { }
            }
            // Hygiene: kept actors and residents leave with the test.
            for (Entity e : actors) if (!e.isRemoved()) e.discard();
            for (SettlerEntity r : residents) r.discard();
            row.villageDog = null;
            SettlementSavedData.get(level).settlements.remove(s.id);
        });
    }

    /** The answer through the real server path, with the exact-price, replay and co-op checks. */
    private static void answerBranch(GameTestHelper h, Settlement s, WorldEventSavedData.Active a,
                                     WorldEventHandler handler, List<Entity> actors, Branch b,
                                     ServerPlayer first, ServerPlayer second) {
        ServerLevel level = h.getLevel();
        Entity visitor = null;
        for (Entity e : actors) {
            if (handler.awaitingAnswer(a, e)) { visitor = e; break; }
        }
        h.assertTrue(visitor != null, b.testName() + ": a visitor waits for an answer");
        first.teleportTo(visitor.getX() + 1, visitor.getY(), visitor.getZ());
        second.teleportTo(visitor.getX() - 1, visitor.getY(), visitor.getZ());

        // The branch id is either an option, or the success/failure outcome of a persuasion option.
        WorldEventVisitors.Option chosen = null;
        Boolean wantSuccess = null;
        String wanted = b.id().equals("escort_abandoned") ? "escort"
            : b.id().startsWith("reload_then_") ? b.id().substring("reload_then_".length()) : b.id();
        for (WorldEventVisitors.Option option : handler.options(level, s, a, first, visitor)) {
            if (option.id().equals(wanted)) chosen = option;
            if (option.persuasion() != null && option.persuasion().successOptionId().equals(wanted)) {
                chosen = option;
                wantSuccess = true;
            }
            if (option.persuasion() != null && option.persuasion().failureOptionId().equals(wanted)) {
                chosen = option;
                wantSuccess = false;
            }
        }
        h.assertTrue(chosen != null && chosen.available(), b.testName() + ": the answer is on offer");
        int food0 = count(first, Items.BREAD);
        int coins0 = count(first, ModItems.GOLD_COIN.get());
        if (wantSuccess != null) {
            level.random.setSeed(seedFor(WorldEventVisitors.clampChance(chosen.persuasion().base()), wantSuccess));
        }
        WorldEventVisitors.Result result = WorldEventVisitors.choose(first, a.id, visitor, chosen.id());
        h.assertTrue(result.accepted(), b.testName() + ": the answer is taken: " + result.message().getString());
        h.assertTrue(WorldEventVisitors.answered(a),
            b.testName() + ": the question is closed");
        int foodCost = chosen.cost().kind() == WorldEventVisitors.CostKind.FOOD ? chosen.cost().amount() : 0;
        int coinCost = chosen.cost().kind() == WorldEventVisitors.CostKind.COINS ? chosen.cost().amount() : 0;
        int coinBonus = wanted.equals("pact") ? RivalEnvoyEvent.PACT_COINS : 0;
        h.assertTrue(count(first, Items.BREAD) == food0 - foodCost,
            b.testName() + ": exactly " + foodCost + " food paid (" + food0 + " -> " + count(first, Items.BREAD) + ")");
        h.assertTrue(count(first, ModItems.GOLD_COIN.get()) == coins0 - coinCost + coinBonus,
            b.testName() + ": exactly " + coinCost + " Coins paid (" + coins0 + " -> "
                + count(first, ModItems.GOLD_COIN.get()) + ", bonus " + coinBonus + ")");

        // A replayed click and a second player's answer are both refused and cost nothing.
        int food1 = count(first, Items.BREAD);
        int coins1 = count(first, ModItems.GOLD_COIN.get());
        h.assertTrue(!WorldEventVisitors.choose(first, a.id, visitor, chosen.id()).accepted()
                && count(first, Items.BREAD) == food1 && count(first, ModItems.GOLD_COIN.get()) == coins1,
            b.testName() + ": a replayed answer is refused and charges nothing");
        int food2 = count(second, Items.BREAD);
        int coins2 = count(second, ModItems.GOLD_COIN.get());
        h.assertTrue(!WorldEventVisitors.choose(second, a.id, visitor, chosen.id()).accepted()
                && count(second, Items.BREAD) == food2 && count(second, ModItems.GOLD_COIN.get()) == coins2,
            b.testName() + ": co-op: the second player's answer is refused (first answer wins) and costs nothing");
        // Keep the observers near the settlement centre (pause-when-empty needs a player near).
        BlockPos home = s.center.offset(0, 0, 4);
        if (!(b.natural() && "escort".equals(b.id()))) first.teleportTo(home.getX() + .5, home.getY(), home.getZ() + .5);
        second.teleportTo(home.getX() + 1.5, home.getY(), home.getZ() + .5);
        if (b.id().equals("escort")) {
            // The far road inside the flattened arena (the caravan walks there for real).
            Entity master = WorldEventActors.actor(level, a, CaravanEvent.ROLE_MASTER);
            BlockPos stop = master.blockPosition();
            BlockPos inside = h.absolutePos(new BlockPos(32, 1, 32));
            BlockPos far = stop.offset((int) Math.signum(inside.getX() - stop.getX()) * 20, 0,
                (int) Math.signum(inside.getZ() - stop.getZ()) * 20);
            a.state.putLong("Far", new BlockPos(far.getX(), inside.getY(), far.getZ()).asLong());
        }
        if (b.id().equals("escort_abandoned")) {
            // The escort leaves the game; the other player stays home (the event keeps running).
            level.getServer().getPlayerList().remove(first);
        }
    }
}

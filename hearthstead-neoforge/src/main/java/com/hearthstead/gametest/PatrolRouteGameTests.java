package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardPatrolGoal;
import com.hearthstead.entity.ai.GuardSaluteGoal;
import com.hearthstead.entity.ai.PatrolRouteGoal;
import com.hearthstead.network.PatrolActionPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.FieldTerrain;
import com.hearthstead.settlement.guard.patrol.PatrolRoute;
import com.hearthstead.settlement.guard.patrol.PatrolRules;
import com.hearthstead.settlement.guard.patrol.PatrolService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PATROL ROUTES lane: squads walk player-drawn routes on their watch, keep
 * formation, stand down for an alarm and resume, salute on the way, survive a
 * save, refuse outsiders, and go inert behind the kill switch.
 * Every test has its own batch (batch prefix {@code patrol_}): the fixtures
 * pin the time of day (morning, the day watch).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PatrolRouteGameTests {
    private static final long MORNING = 2000L;
    private static final int[][] SQUARE = {{8, 8}, {22, 8}, {22, 22}, {8, 22}};

    // ------------------------------------------------------------ tests ---

    /** A squad of 3 walks a 4-waypoint loop and keeps its column. */
    @GameTest(template = "empty32", timeoutTicks = 1800, batch = "patrol_squad_loop")
    public void squadOfThreeWalksALoopInFormation(GameTestHelper h) {
        Fixture f = fixture(h, "Patrolholm", 3);
        PatrolRoute route = route(h, f.settlement, SQUARE, true);
        for (SettlerEntity g : f.guards) f.settlement.patrolRoutes.toggleMember(route, g.getUUID());
        PatrolService.planLevel(h.getLevel());
        Set<Integer> visited = new HashSet<>();
        int[] good = {0};
        int[] bad = {0};
        for (int t = 40; t < 1780; t += 10) {
            h.runAfterDelay(t, () -> {
                keepFed(f);
                PatrolService.Squad squad = PatrolService.squad(h.getLevel(), f.settlement.id, route.id);
                if (squad == null || squad.members().size() != 3) return;
                if (squad.target() >= 0) visited.add(squad.target());
                SettlerEntity leader = f.byId(squad.leader());
                if (leader == null || leader.getActivity() != SettlerActivity.PATROLLING || squad.pausing()
                    || squad.arrivals() < 1) {
                    return;
                }
                boolean ok = true;
                for (int k = 1; k < squad.members().size(); k++) {
                    SettlerEntity follower = f.byId(squad.members().get(k));
                    if (follower == null
                        || follower.distanceTo(leader) > k * 2 + 3.5D) {
                        ok = false;
                    }
                }
                if (ok) good[0]++;
                else bad[0]++;
            });
        }
        h.succeedWhen(() -> {
            PatrolService.Squad squad = PatrolService.squad(h.getLevel(), f.settlement.id, route.id);
            h.assertTrue(squad != null && squad.members().size() == 3, "a squad of three formed");
            h.assertTrue(squad.arrivals() >= 5, "the leader went all the way round (arrivals " + squad.arrivals() + ")");
            h.assertTrue(visited.size() == 4, "all four waypoints were targets, got " + visited);
            int samples = good[0] + bad[0];
            h.assertTrue(samples >= 12 && good[0] >= samples * 0.75D,
                "followers kept their column slots (" + good[0] + " of " + samples + " samples)");
            cleanup(h, f);
        });
    }

    /** An alarm breaks the patrol; when it is over the squad resumes the route. */
    @GameTest(template = "empty32", timeoutTicks = 1000, batch = "patrol_alarm_resume")
    public void anAlarmBreaksThePatrolAndItResumes(GameTestHelper h) {
        Fixture f = fixture(h, "Alarmwatch", 2);
        PatrolRoute route = route(h, f.settlement, SQUARE, true);
        for (SettlerEntity g : f.guards) f.settlement.patrolRoutes.toggleMember(route, g.getUUID());
        PatrolService.planLevel(h.getLevel());
        int[] arrivalsAtAlarm = {-1};
        boolean[] brokeOff = {false};
        h.runAfterDelay(160, () -> {
            keepFed(f);
            PatrolService.Squad squad = PatrolService.squad(h.getLevel(), f.settlement.id, route.id);
            h.assertTrue(squad != null && squad.members().size() == 2, "the squad formed before the alarm");
            arrivalsAtAlarm[0] = squad.arrivals();
            f.settlement.alertPos = h.absolutePos(new BlockPos(28, 1, 4));
            f.settlement.alertUntilGameTime = h.getLevel().getGameTime() + 120L;
        });
        h.runAfterDelay(175, () -> {
            for (SettlerEntity g : f.guards) {
                h.assertTrue(!new PatrolRouteGoal(g).canUse(), "no patrol while the alarm rings");
                h.assertTrue(g.getActivity() != SettlerActivity.PATROLLING,
                    g.getSettlerName() + " broke off the patrol, activity " + g.getActivity());
            }
            brokeOff[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(brokeOff[0], "the alarm was raised and answered first");
            h.assertTrue(!f.settlement.alertActive(h.getLevel().getGameTime()), "alarm over");
            PatrolService.Squad squad = PatrolService.squad(h.getLevel(), f.settlement.id, route.id);
            SettlerEntity leader = squad == null ? null : f.byId(squad.leader());
            h.assertTrue(leader != null && leader.getActivity() == SettlerActivity.PATROLLING,
                "the squad took the route up again");
            h.assertTrue(squad.arrivals() > arrivalsAtAlarm[0], "and reached its next waypoint");
            cleanup(h, f);
        });
    }

    /** On-watch picked guards patrol, off-watch ones rest, unpicked ones keep the ordinary rounds. */
    @GameTest(template = "empty32", timeoutTicks = 200, batch = "patrol_watch_rota")
    public void theWatchRotaDecidesWhoPatrols(GameTestHelper h) {
        Fixture f = fixture(h, "Rotaholm", 1);
        ServerLevel level = h.getLevel();
        // Two more guards in ONE barracks: index 0 stands the day watch, index 1 the night watch.
        Building shared = GameTestFixtures.register(h, f.settlement, BuildingType.BARRACKS, 21, 27);
        SettlerEntity day = guard(h, f, shared, "Dagny", new BlockPos(16, 1, 12));
        SettlerEntity night = guard(h, f, shared, "Natt", new BlockPos(18, 1, 12));
        SettlerEntity unpicked = f.guards.get(0);
        h.assertTrue(Employment.watchOf(f.settlement, day) == Employment.Watch.DAY, "fixture: day watch");
        h.assertTrue(Employment.watchOf(f.settlement, night) == Employment.Watch.NIGHT, "fixture: night watch");
        PatrolRoute route = route(h, f.settlement, SQUARE, true);
        f.settlement.patrolRoutes.toggleMember(route, day.getUUID());
        f.settlement.patrolRoutes.toggleMember(route, night.getUUID());
        PatrolService.planLevel(level);
        // W18b regression: the per-second replan visits every dimension; the Nether and End
        // passes must never drop the Overworld's squads.
        for (ServerLevel other : level.getServer().getAllLevels()) {
            if (other != level) PatrolService.planLevel(other);
        }
        PatrolService.Squad squad = PatrolService.squad(level, f.settlement.id, route.id);
        h.assertTrue(squad != null && squad.members().equals(List.of(day.getUUID())),
            "only the on-watch pick walks, got " + (squad == null ? "none" : squad.members()));
        h.assertTrue(PatrolService.slot(night) == null, "the off-watch pick is not on the route");
        h.assertTrue(!new PatrolRouteGoal(night).canUse(), "the off-watch pick rests");
        h.assertTrue(new PatrolRouteGoal(day).canUse(), "the on-watch pick walks the route");
        h.assertTrue(!new GuardPatrolGoal(day).canUse(), "the ordinary rounds stand aside for him");
        h.assertTrue(PatrolService.slot(unpicked) == null && new GuardPatrolGoal(unpicked).canUse(),
            "an unpicked guard keeps the ordinary rounds");
        // Sampled, not one tick: a salute to the Captain may pause him for a moment.
        boolean[] dayWalked = {false};
        boolean[] nightWalked = {false};
        for (int t = 5; t <= 180; t += 5) {
            h.runAfterDelay(t, () -> {
                dayWalked[0] |= day.getActivity() == SettlerActivity.PATROLLING;
                nightWalked[0] |= night.getActivity() == SettlerActivity.PATROLLING
                    || PatrolService.slot(night) != null;
            });
        }
        h.runAfterDelay(185, () -> {
            h.assertTrue(dayWalked[0], "on watch: patrolling the route");
            h.assertTrue(!nightWalked[0], "off watch: never on the route");
            cleanup(h, f);
            h.succeed();
        });
    }

    /** Routes, picks and settings survive the settlement's save and load; the squad re-forms. */
    @GameTest(template = "empty32", timeoutTicks = 200, batch = "patrol_save_reload")
    public void theRouteSurvivesASaveAndReload(GameTestHelper h) {
        Fixture f = fixture(h, "Saveholm", 2);
        ServerLevel level = h.getLevel();
        PatrolRoute route = route(h, f.settlement, new int[][] {{8, 8}, {22, 8}, {22, 22}}, true);
        h.assertTrue(f.settlement.patrolRoutes.rename(route, "East Wall") == PatrolRules.Refusal.NONE, "rename");
        f.settlement.patrolRoutes.setPerShift(route, 2);
        f.settlement.patrolRoutes.setFormation(route, PatrolRoute.Formation.PAIRS);
        f.settlement.patrolRoutes.toggleMember(route, f.guards.get(0).getUUID());

        CompoundTag saved = f.settlement.writeNbt();
        Settlement back = Settlement.readNbt(saved);
        PatrolRoute loaded = back.patrolRoutes.route(route.id);
        h.assertTrue(loaded != null, "the route was saved");
        h.assertTrue(loaded.waypoints().equals(route.waypoints()), "waypoints intact");
        h.assertTrue(loaded.loop() && loaded.perShift() == 2 && loaded.formation() == PatrolRoute.Formation.PAIRS,
            "loop, per-shift count and formation intact");
        h.assertTrue("East Wall".equals(loaded.name()), "name intact, got " + loaded.name());
        h.assertTrue(loaded.memberOrder().equals(List.of(f.guards.get(0).getUUID())), "picks intact");

        SettlementSavedData.get(level).settlements.put(back.id, back);
        PatrolService.planLevel(level);
        PatrolService.Squad squad = PatrolService.squad(level, back.id, route.id);
        h.assertTrue(squad != null && squad.members().size() == 2
                && squad.leader().equals(f.guards.get(0).getUUID()),
            "after the reload the squad re-forms, the pick leading");
        SettlementSavedData.get(level).settlements.remove(back.id);
        PatrolService.planLevel(level);
        h.succeed();
    }

    /** Somebody who is not a member of the settlement cannot edit its routes; a member can. */
    @GameTest(template = "empty32", timeoutTicks = 100, batch = "patrol_non_member")
    public void aNonMemberCannotEditARoute(GameTestHelper h) {
        Fixture f = fixture(h, "Memberholm", 1);
        PatrolRoute route = route(h, f.settlement, SQUARE, false);
        ServerPlayer player = player(h);
        BlockPos far = f.settlement.center.offset(400, 0, 0);
        player.setPos(far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D);
        int revision = f.settlement.patrolRoutes.revision();
        PatrolRules.Refusal create = PatrolService.handle(player, new PatrolActionPayload(f.settlement.id,
            PatrolActionPayload.Kind.CREATE, -1, 0, PatrolActionPayload.NONE, "", revision));
        PatrolRules.Refusal rename = PatrolService.handle(player, new PatrolActionPayload(f.settlement.id,
            PatrolActionPayload.Kind.RENAME, route.id, 0, PatrolActionPayload.NONE, "Hacked", revision));
        PatrolRules.Refusal pick = PatrolService.handle(player, new PatrolActionPayload(f.settlement.id,
            PatrolActionPayload.Kind.TOGGLE_MEMBER, route.id, 0, f.guards.get(0).getUUID(), "", revision));
        PatrolRules.Refusal remove = PatrolService.handle(player, new PatrolActionPayload(f.settlement.id,
            PatrolActionPayload.Kind.REMOVE_POINT, route.id, 0, PatrolActionPayload.NONE, "", revision));
        h.assertTrue(create == PatrolRules.Refusal.NOT_MEMBER && rename == PatrolRules.Refusal.NOT_MEMBER
                && pick == PatrolRules.Refusal.NOT_MEMBER && remove == PatrolRules.Refusal.NOT_MEMBER,
            "every edit from outside is refused: " + create + " " + rename + " " + pick + " " + remove);
        h.assertTrue(f.settlement.patrolRoutes.revision() == revision
                && f.settlement.patrolRoutes.routes().size() == 1 && route.size() == SQUARE.length
                && route.members().isEmpty() && !"Hacked".equals(route.name()),
            "the book is untouched");
        // Control: the same player inside the settlement is a member (co-op: equal rights).
        player.setPos(f.settlement.center.getX() + 0.5D, f.settlement.center.getY(), f.settlement.center.getZ() + 0.5D);
        PatrolRules.Refusal member = PatrolService.handle(player, new PatrolActionPayload(f.settlement.id,
            PatrolActionPayload.Kind.RENAME, route.id, 0, PatrolActionPayload.NONE, "Wall Walk", revision));
        h.assertTrue(member == PatrolRules.Refusal.NONE && "Wall Walk".equals(route.name()),
            "a member may rename it, got " + member);
        cleanup(h, f);
        h.succeed();
    }

    /** A player walking past the patrol is saluted by the leader and the file; then the patrol goes on. */
    @GameTest(template = "empty32", timeoutTicks = 1000, batch = "patrol_salute")
    public void saluteWhilePatrollingStillWorks(GameTestHelper h) {
        Fixture f = fixture(h, "Saluteholm", 2);
        PatrolRoute route = route(h, f.settlement, new int[][] {{13, 10}, {28, 10}}, false);
        for (SettlerEntity g : f.guards) f.settlement.patrolRoutes.toggleMember(route, g.getUUID());
        PatrolService.planLevel(h.getLevel());
        ServerPlayer player = player(h);
        BlockPos park = h.absolutePos(new BlockPos(30, 1, 30));
        player.setPos(park.getX() + 0.5D, park.getY(), park.getZ() + 0.5D);
        // Once the squad heads east from waypoint 1, the player walks west past it one block aside.
        int start = 150;
        int steps = 200;
        for (int i = 0; i <= steps; i++) {
            final double x = h.absolutePos(new BlockPos(30, 1, 11)).getX() + 0.5D - 0.12D * i;
            final double z = h.absolutePos(new BlockPos(30, 1, 11)).getZ() + 0.5D;
            final double y = h.absolutePos(new BlockPos(30, 1, 11)).getY();
            h.runAfterDelay(start + i, () -> player.setPos(x, y, z));
        }
        h.runAfterDelay(start + steps + 1, () -> player.setPos(park.getX() + 0.5D, park.getY(), park.getZ() + 0.5D));
        h.runAfterDelay(start, () -> keepFed(f));
        h.succeedWhen(() -> {
            PatrolService.Squad squad = PatrolService.squad(h.getLevel(), f.settlement.id, route.id);
            h.assertTrue(squad != null && squad.members().size() == 2, "a squad of two");
            SettlerEntity leader = f.byId(squad.leader());
            SettlerEntity follower = f.byId(squad.members().get(1));
            h.assertTrue(GuardSaluteGoal.saluteCount(leader) >= 1, "the leader saluted the passing player");
            h.assertTrue(GuardSaluteGoal.saluteCount(follower) >= 1, "so did the man behind him");
            h.assertTrue(GuardSaluteGoal.of(leader).phase()
                == GuardSaluteGoal.Phase.NONE && leader.getActivity() == SettlerActivity.PATROLLING,
                "and the patrol went on afterwards");
            cleanup(h, f);
        });
    }

    /** [features] patrolRoutes=false: no squads, no edits, the ordinary rounds; routes are kept. */
    @GameTest(template = "empty32", timeoutTicks = 100, batch = "patrol_kill_switch")
    public void theKillSwitchStandsTheRoutesDown(GameTestHelper h) {
        Fixture f = fixture(h, "Switchholm", 1);
        ServerLevel level = h.getLevel();
        SettlerEntity guard = f.guards.get(0);
        PatrolRoute route = route(h, f.settlement, SQUARE, true);
        f.settlement.patrolRoutes.toggleMember(route, guard.getUUID());
        PatrolService.planLevel(level);
        h.assertTrue(PatrolService.slot(guard) != null, "control: on the route while switched on");
        ServerPlayer player = player(h);
        player.setPos(f.settlement.center.getX() + 0.5D, f.settlement.center.getY(), f.settlement.center.getZ() + 0.5D);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PATROL_MAP.get()));
        boolean slotGone;
        boolean routeGoal;
        boolean rounds;
        PatrolRules.Refusal use;
        PatrolRules.Refusal action;
        int routes;
        int points;
        PatrolService.setEnabledForTests(false);
        try {
            PatrolService.planLevel(level);
            slotGone = PatrolService.slot(guard) == null;
            routeGoal = new PatrolRouteGoal(guard).canUse();
            rounds = new GuardPatrolGoal(guard).canUse();
            use = PatrolService.useOnBlock(player, InteractionHand.MAIN_HAND, h.absolutePos(new BlockPos(12, 0, 12)),
                Direction.UP);
            action = PatrolService.handle(player, new PatrolActionPayload(f.settlement.id,
                PatrolActionPayload.Kind.CREATE, -1, 0, PatrolActionPayload.NONE, "", 0));
            routes = f.settlement.patrolRoutes.routes().size();
            points = route.size();
        } finally {
            PatrolService.setEnabledForTests(null);
        }
        h.assertTrue(slotGone && !routeGoal, "switched off: nobody walks a route");
        h.assertTrue(rounds, "switched off: the guard keeps the ordinary rounds");
        h.assertTrue(use == PatrolRules.Refusal.DISABLED && action == PatrolRules.Refusal.DISABLED,
            "switched off: the Patrol Map and the screen refuse, got " + use + " / " + action);
        h.assertTrue(routes == 1 && points == SQUARE.length, "switched off: the saved route is kept");
        PatrolService.planLevel(level);
        h.assertTrue(PatrolService.slot(guard) != null, "switched back on: the route is walked again");
        cleanup(h, f);
        h.succeed();
    }

    // --------------------------------------------------------- fixtures ---

    private record Fixture(Settlement settlement, List<SettlerEntity> guards) {
        SettlerEntity byId(UUID id) {
            for (SettlerEntity g : guards) {
                if (g.getUUID().equals(id)) return g;
            }
            return null;
        }
    }

    /**
     * A 32x32 stone floor, a settlement centred on it and {@code n} guards,
     * each hired by his own Barracks (index 0 = the day watch), morning.
     */
    private static Fixture fixture(GameTestHelper h, String name, int n) {
        ServerLevel level = h.getLevel();
        level.setDayTime(MORNING);
        PatrolService.setEnabledForTests(null);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), name, h.absolutePos(new BlockPos(16, 1, 16)));
        settlement.radius = 16;
        SettlementSavedData.get(level).settlements.put(settlement.id, settlement);
        Fixture f = new Fixture(settlement, new ArrayList<>());
        for (int i = 0; i < n; i++) {
            Building barracks = GameTestFixtures.register(h, settlement, BuildingType.BARRACKS, 1 + 5 * i, 27);
            f.guards.add(guard(h, f, barracks, "Guard" + i, new BlockPos(12 + 2 * i, 1, 12)));
        }
        return f;
    }

    private static SettlerEntity guard(GameTestHelper h, Fixture f, Building barracks, String name, BlockPos at) {
        SettlerEntity guard = h.spawn(ModEntities.SETTLER.get(), at);
        guard.bindTo(f.settlement.id, f.settlement.center);
        guard.setSettlerName(name);
        f.settlement.putRecord(guard.getUUID(), name, Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), f.settlement, barracks, guard).ok(), "hire " + name);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        guard.setHunger(100.0F);
        guard.setEnergy(100.0F);
        return guard;
    }

    private static void keepFed(Fixture f) {
        for (SettlerEntity g : f.guards) {
            g.setHunger(100.0F);
            g.setEnergy(100.0F);
        }
    }

    private static PatrolRoute route(GameTestHelper h, Settlement s, int[][] points, boolean loop) {
        PatrolRoute route = s.patrolRoutes.create();
        h.assertTrue(route != null, "route created");
        for (int[] p : points) {
            BlockPos abs = h.absolutePos(new BlockPos(p[0], 1, p[1]));
            PatrolRules.Refusal refusal = s.patrolRoutes.append(route, abs, s.center, s.radius,
                FieldTerrain.standable(h.getLevel(), abs));
            h.assertTrue(refusal == PatrolRules.Refusal.NONE, "waypoint " + abs + ": " + refusal);
        }
        if (loop) h.assertTrue(s.patrolRoutes.setLoop(route, true) == PatrolRules.Refusal.NONE, "loop");
        return route;
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void cleanup(GameTestHelper h, Fixture f) {
        SettlementSavedData.get(h.getLevel()).settlements.remove(f.settlement.id);
        PatrolService.planLevel(h.getLevel());
    }
}

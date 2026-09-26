package com.hearthstead.settlement.guard.patrol;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardPatrolGoal;
import com.hearthstead.item.PatrolMapItem;
import com.hearthstead.network.PatrolActionPayload;
import com.hearthstead.network.PatrolSnapshotPayload;
import com.hearthstead.network.PayloadSend;
import com.hearthstead.network.RealmMapNetwork;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.guard.FieldTerrain;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server side of player-drawn patrol routes (PATROL ROUTES lane).
 *
 * <p>Authority: every edit arrives here -- a Patrol Map click in the world
 * or a route-screen action -- and is checked against the feature switch, the
 * sender's membership of that exact settlement (inside its command reach, or
 * with its Banner map open), the settlement's dimension and the limits in
 * {@link PatrolRules}. A waypoint must be a cell a guard can stand on.
 *
 * <p>Runtime: once a second each settlement with routes plans its squads
 * ({@link PatrolShifts}) from loaded members on their own watch; the
 * {@code PatrolRouteGoal} of each squad member reads its {@link Slot} here.
 * Nothing here loads a chunk. Projections go out through {@link PayloadSend}
 * only, to players holding the Patrol Map or viewing that Banner map, when
 * what they would see changed.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class PatrolService {
    public static final int PLAN_INTERVAL = 20;
    public static final int SEND_INTERVAL = 10;
    public static final int TRAIL_MAX = 48;
    static final int RATE_LIMIT_TICKS = 2;
    static final String TAG_SETTLEMENT = "PatrolSettlement";
    static final String TAG_ROUTE = "PatrolRoute";

    @Nullable private static Boolean enabledOverride;

    /** Server kill-switch: {@code [features] patrolRoutes}. */
    public static boolean enabled() {
        return enabledOverride != null ? enabledOverride
            : com.hearthstead.HearthsteadServerConfig.patrolRoutesEnabled();
    }

    public static void setEnabledForTests(@Nullable Boolean enabled) {
        enabledOverride = enabled;
    }

    // ------------------------------------------------------------ state ---

    /** One route's walking squad and the leader's progress along it. */
    public static final class Squad {
        public final UUID settlementId;
        public final int routeId;
        List<UUID> members = List.of();
        int index = -1;
        int direction = 1;
        int seenRevision = Integer.MIN_VALUE;
        int arrivals;
        boolean pausing;
        boolean regrouping;
        final ArrayDeque<BlockPos> trail = new ArrayDeque<>();
        UUID trailOwner;

        Squad(UUID settlementId, int routeId) {
            this.settlementId = settlementId;
            this.routeId = routeId;
        }

        public List<UUID> members() {
            return members;
        }

        @Nullable
        public UUID leader() {
            return members.isEmpty() ? null : members.get(0);
        }

        /** The waypoint the leader is heading to (or resting at), or -1. */
        public int target() {
            return index;
        }

        public int direction() {
            return direction;
        }

        /** Bumped every time the leader reaches a waypoint. */
        public int arrivals() {
            return arrivals;
        }

        public boolean pausing() {
            return pausing;
        }

        public boolean regrouping() {
            return regrouping;
        }

        /** Leader's recent cells, newest first. */
        public List<BlockPos> trail() {
            return List.copyOf(trail);
        }

        // Leader-side updates (PatrolRouteGoal).
        public void setTarget(int index, int direction, int revision) {
            this.index = index;
            this.direction = direction;
            this.seenRevision = revision;
        }

        public int seenRevision() {
            return seenRevision;
        }

        public void arrived() {
            arrivals++;
        }

        public void setPausing(boolean pausing) {
            this.pausing = pausing;
        }

        public void setRegrouping(boolean regrouping) {
            this.regrouping = regrouping;
        }

        public void recordTrail(UUID leader, BlockPos cell) {
            if (!leader.equals(trailOwner)) {
                trail.clear();
                trailOwner = leader;
            }
            BlockPos last = trail.peekFirst();
            if (last != null && last.equals(cell)) return;
            trail.addFirst(cell.immutable());
            while (trail.size() > TRAIL_MAX) trail.removeLast();
        }
    }

    /** This settler's place in a squad: index 0 leads. */
    public record Slot(Squad squad, int index) {
        public boolean leader() {
            return index == 0;
        }
    }

    private static final class Run {
        final Map<Integer, Squad> squads = new LinkedHashMap<>();
        final Map<UUID, Slot> slots = new HashMap<>();
    }

    private static final class State {
        final Map<UUID, Run> runs = new HashMap<>();
        final Map<UUID, Integer> sentHash = new HashMap<>();
        final Map<UUID, Long> lastAction = new HashMap<>();
    }

    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();

    private static State state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, s -> new State());
    }

    // ---------------------------------------------------------- queries ---

    /** This guard's squad slot, or null when it walks no route right now. */
    @Nullable
    public static Slot slot(SettlerEntity settler) {
        if (settler == null || !(settler.level() instanceof ServerLevel level) || !enabled()) return null;
        State state = STATES.get(level.getServer());
        UUID settlementId = settler.getSettlementId();
        if (state == null || settlementId == null) return null;
        Run run = state.runs.get(settlementId);
        return run == null ? null : run.slots.get(settler.getUUID());
    }

    /** True while this guard walks a player-drawn route (the ordinary rounds stand aside). */
    public static boolean assigned(SettlerEntity settler) {
        return slot(settler) != null;
    }

    /** True when both walk in the same patrol squad right now. */
    public static boolean sameSquad(SettlerEntity a, SettlerEntity b) {
        Slot sa = slot(a);
        Slot sb = sa == null ? null : slot(b);
        return sb != null && sa.squad() == sb.squad();
    }

    /** The squad of a route (tests, QA), or null. */
    @Nullable
    public static Squad squad(ServerLevel level, UUID settlementId, int routeId) {
        State state = STATES.get(level.getServer());
        Run run = state == null ? null : state.runs.get(settlementId);
        return run == null ? null : run.squads.get(routeId);
    }

    /** The route this guard walks right now, or null (map card, tests). */
    @Nullable
    public static PatrolRoute routeOf(SettlerEntity settler) {
        Slot slot = slot(settler);
        Settlement s = slot == null ? null : settler.settlement();
        return s == null ? null : s.patrolRoutes.route(slot.squad().routeId);
    }

    /** Whether the stored route still satisfies every limit (a copied or edited save may not). */
    public static boolean valid(PatrolRoute route, Settlement settlement) {
        return route.walkable() && PatrolRules.validateRoute(route.waypoints(), route.loopFlag(),
            settlement.center, settlement.radius) == PatrolRules.Refusal.NONE;
    }

    // ------------------------------------------------------------- tick ---

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % PLAN_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) planLevel(level);
        }
        if (tick % SEND_INTERVAL == 5) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) pushTo(player, false);
        }
    }

    /** Replans every settlement of this level now (tests; the tick does it once a second). */
    public static void planLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.existing(level);
        State state = state(level.getServer());
        if (data == null) return;
        for (Settlement settlement : data.settlements.values()) {
            if (settlement != null) plan(level, settlement, state);
        }
        pruneRuns(level.getServer(), state);
    }

    /**
     * Drops the runtime squads of settlements that no longer exist in ANY
     * level (or have no routes left). Must look across every dimension: the
     * runs are keyed by settlement id server-wide, so pruning against one
     * level's data would wipe every other dimension's squads (W18b: the
     * Nether/End pass emptied the Overworld's squads every second).
     */
    private static void pruneRuns(MinecraftServer server, State state) {
        if (state.runs.isEmpty()) return;
        Map<UUID, Settlement> all = new HashMap<>();
        for (ServerLevel any : server.getAllLevels()) {
            SettlementSavedData d = SettlementSavedData.existing(any);
            if (d != null) all.putAll(d.settlements);
        }
        state.runs.keySet().removeIf(id -> {
            Settlement s = all.get(id);
            return s == null || s.patrolRoutes.isEmpty();
        });
    }

    private static void plan(ServerLevel level, Settlement settlement, State state) {
        Run run = state.runs.get(settlement.id);
        PatrolRouteBook book = settlement.patrolRoutes;
        if (!enabled() || book.isEmpty()) {
            if (run != null) {
                run.squads.clear();
                run.slots.clear();
            }
            return;
        }
        if (run == null) {
            run = new Run();
            state.runs.put(settlement.id, run);
        }
        pruneDeparted(level, settlement);
        List<PatrolShifts.Candidate> candidates = new ArrayList<>();
        long now = level.getGameTime();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (!GuardPatrolGoal.patrols(settler.getProfession())) continue;
            candidates.add(new PatrolShifts.Candidate(settler.getUUID(),
                Schedule.onWatch(settlement, settler, settler.dayPhase()), available(settlement, settler, now)));
        }
        List<PatrolShifts.RouteSpec> specs = new ArrayList<>();
        for (PatrolRoute route : book.routes()) {
            specs.add(new PatrolShifts.RouteSpec(route.id, valid(route, settlement), route.memberOrder(),
                route.perShift()));
        }
        Map<Integer, List<UUID>> previous = new HashMap<>();
        for (Squad squad : run.squads.values()) previous.put(squad.routeId, squad.members);
        Map<Integer, List<UUID>> plan = PatrolShifts.plan(specs, candidates, previous);
        run.squads.keySet().removeIf(id -> !plan.containsKey(id));
        run.slots.clear();
        for (Map.Entry<Integer, List<UUID>> e : plan.entrySet()) {
            Squad squad = run.squads.computeIfAbsent(e.getKey(), id -> new Squad(settlement.id, id));
            squad.members = e.getValue();
            for (int i = 0; i < squad.members.size(); i++) {
                run.slots.put(squad.members.get(i), new Slot(squad, i));
            }
        }
    }

    /** Free to walk a route: no explicit post, field order, banner team command or summons. */
    static boolean available(Settlement settlement, SettlerEntity settler, long now) {
        if (!settler.isAlive() || settler.isTraveler() || !settlement.id.equals(settler.getSettlementId())) {
            return false;
        }
        if (FieldOrders.controls(settler) || BannerTeams.active(settler) != null
            || com.hearthstead.settlement.summon.PlayerSummons.active(settler) != null) {
            return false;
        }
        if (!settlement.guardOrders.quarantined()) {
            Optional<GuardOrder> order = settlement.guardOrders.order(settler.getUUID());
            if (order.isPresent() && order.get().modeAt(now) != GuardOrder.Mode.NONE) return false;
        }
        return true;
    }

    private static void pruneDeparted(ServerLevel level, Settlement settlement) {
        boolean changed = false;
        for (PatrolRoute route : settlement.patrolRoutes.routes()) {
            for (UUID member : route.memberOrder()) {
                if (settlement.record(member) == null) {
                    settlement.patrolRoutes.forget(member);
                    changed = true;
                }
            }
        }
        if (changed) SettlementSavedData.get(level).setDirty();
    }

    // ------------------------------------------------------ projections ---

    private static void pushTo(ServerPlayer player, boolean force) {
        State state = state(player.server);
        Settlement shown = null;
        int selected = PatrolSnapshotPayload.NO_ROUTE;
        ItemStack map = heldMap(player);
        if (enabled() && !map.isEmpty()) {
            shown = standingIn(player);
            if (shown != null) selected = selectedRoute(map, shown);
        }
        if (shown == null && enabled()) {
            SettlementSavedData data = SettlementSavedData.existing(player.serverLevel());
            if (data != null) {
                for (Settlement s : data.settlements.values()) {
                    if (s != null && RealmMapNetwork.isSubscribed(player, s.id)) {
                        shown = s;
                        break;
                    }
                }
            }
        }
        if (shown == null) {
            state.sentHash.remove(player.getUUID());
            return;
        }
        PatrolSnapshotPayload payload = snapshot(player.serverLevel(), shown, selected, false);
        int hash = payload.hashCode();
        Integer last = state.sentHash.get(player.getUUID());
        if (!force && last != null && last == hash) return;
        if (PayloadSend.toPlayer(player, payload)) state.sentHash.put(player.getUUID(), hash);
    }

    public static PatrolSnapshotPayload snapshot(ServerLevel level, Settlement settlement, int selected,
                                                 boolean open) {
        State state = state(level.getServer());
        Run run = state.runs.get(settlement.id);
        List<PatrolSnapshotPayload.Route> routes = new ArrayList<>();
        for (PatrolRoute route : settlement.patrolRoutes.routes()) {
            Squad squad = run == null ? null : run.squads.get(route.id);
            routes.add(new PatrolSnapshotPayload.Route(route.id, route.name(), route.color(), route.loop(),
                route.formation().wireId(), route.perShift(), route.waypoints(), route.memberOrder(),
                squad == null ? List.of() : squad.members, squad == null ? -1 : squad.index,
                valid(route, settlement)));
        }
        List<PatrolSnapshotPayload.Guard> guards = new ArrayList<>();
        long now = level.getGameTime();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (guards.size() >= PatrolSnapshotPayload.MAX_GUARDS) break;
            if (record == null || record.entityId == null || record.profession == null
                || !GuardPatrolGoal.patrols(record.profession)) {
                continue;
            }
            boolean night = false;
            boolean onWatch = false;
            boolean busy = false;
            if (level.getEntity(record.entityId) instanceof SettlerEntity settler && settler.isAlive()) {
                night = Employment.watchOf(settlement, settler) == Employment.Watch.NIGHT;
                onWatch = Schedule.onWatch(settlement, settler, settler.dayPhase());
                busy = !available(settlement, settler, now);
            }
            guards.add(new PatrolSnapshotPayload.Guard(record.entityId, record.name, record.profession.id(), night,
                onWatch, busy));
        }
        return new PatrolSnapshotPayload(settlement.id, level.dimension().location().toString(),
            settlement.patrolRoutes.revision(), selected, open, routes, guards);
    }

    // ---------------------------------------------------- item and edits ---

    /** The Patrol Map in hand (main first), or empty. */
    public static ItemStack heldMap(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof PatrolMapItem) return main;
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof PatrolMapItem ? off : ItemStack.EMPTY;
    }

    static int selectedRoute(ItemStack stack, Settlement settlement) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.hasUUID(TAG_SETTLEMENT) || !settlement.id.equals(tag.getUUID(TAG_SETTLEMENT))) {
            return PatrolSnapshotPayload.NO_ROUTE;
        }
        int id = tag.getInt(TAG_ROUTE);
        return settlement.patrolRoutes.route(id) == null ? PatrolSnapshotPayload.NO_ROUTE : id;
    }

    static void select(ItemStack stack, Settlement settlement, int routeId) {
        if (stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID(TAG_SETTLEMENT, settlement.id);
            tag.putInt(TAG_ROUTE, routeId);
        });
    }

    /**
     * A Patrol Map click on a block: add a waypoint to the selected route
     * (creating the first route on the first click), click marker 1 to close
     * or open the loop, sneak-click a marker to remove it.
     */
    public static PatrolRules.Refusal useOnBlock(ServerPlayer player, InteractionHand hand, BlockPos clicked,
                                                 Direction face) {
        ServerLevel level = player.serverLevel();
        ItemStack stack = player.getItemInHand(hand);
        if (!enabled()) return tell(player, PatrolRules.Refusal.DISABLED);
        Settlement settlement = standingIn(player);
        if (settlement == null || player.isSpectator() || !player.isAlive()) {
            return tell(player, PatrolRules.Refusal.NOT_MEMBER);
        }
        if (!rateOk(player)) return PatrolRules.Refusal.STALE;
        PatrolRouteBook book = settlement.patrolRoutes;
        BlockPos stand = standCell(level, clicked, face);
        BlockPos probe = stand != null ? stand : clicked.above();
        PatrolRoute route = book.route(selectedRoute(stack, settlement));
        if (route == null) {
            if (player.isShiftKeyDown()) return tell(player, PatrolRules.Refusal.NO_ROUTE);
            route = book.create();
            if (route == null) return tell(player, PatrolRules.Refusal.TOO_MANY_ROUTES);
            select(stack, settlement, route.id);
        }
        int picked = PatrolRules.pick(route.waypoints(), probe);
        String message;
        if (player.isShiftKeyDown()) {
            if (picked < 0) {
                player.displayClientMessage(Component.literal("Sneak-click a numbered marker to remove it."), true);
                return PatrolRules.Refusal.STALE;
            }
            book.remove(route, picked);
            message = "Waypoint " + (picked + 1) + " removed from " + route.name() + ".";
        } else if (picked == 0 && route.size() >= PatrolRules.MIN_LOOP_WAYPOINTS) {
            boolean closing = !route.loopFlag();
            PatrolRules.Refusal refusal = book.setLoop(route, closing);
            if (refusal != PatrolRules.Refusal.NONE) return tell(player, refusal);
            message = closing ? route.name() + " is now a closed loop." : route.name() + " is open again (there and back).";
        } else if (picked >= 0) {
            player.displayClientMessage(Component.literal("Waypoint " + (picked + 1) + " is already here."), true);
            return PatrolRules.Refusal.DUPLICATE;
        } else {
            PatrolRules.Refusal refusal = book.append(route, probe, settlement.center, settlement.radius,
                stand != null);
            if (refusal != PatrolRules.Refusal.NONE) return tell(player, refusal);
            message = "Waypoint " + route.size() + " of " + PatrolRules.MAX_WAYPOINTS + " set on "
                + route.name() + (route.size() >= PatrolRules.MIN_LOOP_WAYPOINTS && !route.loopFlag()
                    ? " — click marker 1 to close the loop." : ".");
        }
        SettlementSavedData.get(level).setDirty();
        plan(level, settlement, state(level.getServer()));
        player.displayClientMessage(Component.literal(message), true);
        pushTo(player, true);
        return PatrolRules.Refusal.NONE;
    }

    /** Where a guard would stand for a click on {@code clicked}'s {@code face}, or null. */
    @Nullable
    static BlockPos standCell(ServerLevel level, BlockPos clicked, Direction face) {
        BlockPos above = clicked.above();
        if (level.hasChunkAt(above) && FieldTerrain.standable(level, above)) return above;
        BlockPos side = clicked.relative(face == null ? Direction.UP : face);
        if (level.hasChunkAt(side) && FieldTerrain.standable(level, side)) return side;
        return level.hasChunkAt(above) ? FieldTerrain.snap(level, above) : null;
    }

    /** Right-click in the air: open the route screen for the settlement the player stands in. */
    public static void openFor(ServerPlayer player, InteractionHand hand) {
        if (!enabled()) {
            tell(player, PatrolRules.Refusal.DISABLED);
            return;
        }
        Settlement settlement = standingIn(player);
        if (settlement == null) {
            player.displayClientMessage(Component.literal(
                "Stand inside your settlement to plan its patrols."), true);
            return;
        }
        plan(player.serverLevel(), settlement, state(player.server));
        PayloadSend.toPlayer(player, snapshot(player.serverLevel(), settlement,
            selectedRoute(player.getItemInHand(hand), settlement), true));
    }

    /** A route-screen action; every field is re-validated here. */
    public static PatrolRules.Refusal handle(ServerPlayer player, PatrolActionPayload action) {
        if (player == null || action == null) return PatrolRules.Refusal.STALE;
        if (!enabled()) return tell(player, PatrolRules.Refusal.DISABLED);
        Settlement settlement = memberSettlement(player, action.settlementId());
        if (settlement == null) return tell(player, PatrolRules.Refusal.NOT_MEMBER);
        ServerLevel level = player.serverLevel();
        if (action.kind() == PatrolActionPayload.Kind.REFRESH) {
            pushTo(player, true);
            return PatrolRules.Refusal.NONE;
        }
        if (!rateOk(player)) return PatrolRules.Refusal.STALE;
        PatrolRouteBook book = settlement.patrolRoutes;
        PatrolRoute route = book.route(action.routeId());
        PatrolRules.Refusal result = PatrolRules.Refusal.NONE;
        String done = null;
        switch (action.kind()) {
            case CREATE -> {
                PatrolRoute created = book.create();
                if (created == null) {
                    result = PatrolRules.Refusal.TOO_MANY_ROUTES;
                } else {
                    select(heldMap(player), settlement, created.id);
                    done = created.name() + " created: right-click the ground with the Patrol Map to set waypoints.";
                }
            }
            case DELETE -> {
                if (route == null) {
                    result = PatrolRules.Refusal.NO_ROUTE;
                } else {
                    book.delete(route.id);
                    done = route.name() + " deleted.";
                }
            }
            case SELECT -> {
                if (route == null) {
                    result = PatrolRules.Refusal.NO_ROUTE;
                } else {
                    select(heldMap(player), settlement, route.id);
                    done = "Marking " + route.name() + ".";
                }
            }
            case TOGGLE_LOOP -> result = route == null ? PatrolRules.Refusal.NO_ROUTE
                : book.setLoop(route, !route.loopFlag());
            case SET_FORMATION -> {
                if (route == null) result = PatrolRules.Refusal.NO_ROUTE;
                else book.setFormation(route, PatrolRoute.Formation.byWireId(action.value()));
            }
            case SET_PER_SHIFT -> {
                if (route == null) result = PatrolRules.Refusal.NO_ROUTE;
                else book.setPerShift(route, action.value());
            }
            case TOGGLE_MEMBER -> {
                Settlement.SettlerRecord record = settlement.record(action.guard());
                if (route == null) {
                    result = PatrolRules.Refusal.NO_ROUTE;
                } else if (record == null || record.profession == null
                    || !GuardPatrolGoal.patrols(record.profession)) {
                    result = PatrolRules.Refusal.NOT_ELIGIBLE;
                } else {
                    boolean on = book.toggleMember(route, action.guard());
                    done = record.name + (on ? " walks " : " no longer walks ") + route.name() + ".";
                }
            }
            case RENAME -> result = route == null ? PatrolRules.Refusal.NO_ROUTE : book.rename(route, action.text());
            case REMOVE_POINT -> {
                if (route == null) result = PatrolRules.Refusal.NO_ROUTE;
                else if (action.expectedRevision() != book.revision()) result = PatrolRules.Refusal.STALE;
                else result = book.remove(route, action.value());
            }
            default -> result = PatrolRules.Refusal.STALE;
        }
        if (result != PatrolRules.Refusal.NONE) {
            tell(player, result);
        } else {
            SettlementSavedData.get(level).setDirty();
            plan(level, settlement, state(level.getServer()));
            if (done != null) player.displayClientMessage(Component.literal(done), true);
        }
        PayloadSend.toPlayer(player, snapshot(level, settlement, selectedRoute(heldMap(player), settlement), false));
        return result;
    }

    /**
     * The settlement with this id if the player is one of its members right
     * now: inside its claim, commanding it (the nearest settlement whose
     * command reach covers them, as for the R/G orders), or with its Banner
     * map open. Co-op: every member has equal rights. Another dimension's
     * settlement is never found (per-level data).
     */
    @Nullable
    public static Settlement memberSettlement(ServerPlayer player, UUID settlementId) {
        if (player == null || settlementId == null || player.isSpectator() || !player.isAlive()) return null;
        SettlementSavedData data = SettlementSavedData.existing(player.serverLevel());
        Settlement settlement = data == null ? null : data.settlements.get(settlementId);
        if (settlement == null || settlement.center == null) return null;
        double dx = player.getX() - (settlement.center.getX() + 0.5D);
        double dz = player.getZ() - (settlement.center.getZ() + 0.5D);
        boolean insideClaim = dx * dx + dz * dz <= (double) settlement.radius * settlement.radius;
        if (insideClaim || FieldOrders.commandedSettlement(player) == settlement
            || RealmMapNetwork.isSubscribed(player, settlementId)) {
            return settlement;
        }
        return null;
    }

    /**
     * The settlement a Patrol Map user is drawing for: the one whose claim
     * they stand in (nearest centre first), else the one they command.
     */
    @Nullable
    public static Settlement standingIn(ServerPlayer player) {
        SettlementSavedData data = SettlementSavedData.existing(player.serverLevel());
        if (data == null) return null;
        Settlement best = null;
        double bestSq = Double.MAX_VALUE;
        for (Settlement s : data.settlements.values()) {
            if (s == null || s.center == null) continue;
            double dx = player.getX() - (s.center.getX() + 0.5D);
            double dz = player.getZ() - (s.center.getZ() + 0.5D);
            double d = dx * dx + dz * dz;
            if (d <= (double) s.radius * s.radius && d < bestSq) {
                best = s;
                bestSq = d;
            }
        }
        return best != null ? best : FieldOrders.commandedSettlement(player);
    }

    private static boolean rateOk(ServerPlayer player) {
        State state = state(player.server);
        long now = player.serverLevel().getGameTime();
        Long last = state.lastAction.get(player.getUUID());
        if (last != null && now >= last && now - last < RATE_LIMIT_TICKS) return false;
        state.lastAction.put(player.getUUID(), now);
        return true;
    }

    private static PatrolRules.Refusal tell(ServerPlayer player, PatrolRules.Refusal refusal) {
        player.displayClientMessage(Component.literal(refusal.sentence()), true);
        return refusal;
    }

    /** Eligible trades, for the screen and tests. */
    public static boolean eligible(Profession profession) {
        return GuardPatrolGoal.patrols(profession);
    }

    // --------------------------------------------------------- lifecycle ---

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        State state = STATES.get(event.getEntity().getServer());
        if (state != null) {
            state.sentHash.remove(event.getEntity().getUUID());
            state.lastAction.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    /** Test seam: forget all runtime squads and rate limits of this server. */
    public static void resetForTests(MinecraftServer server) {
        STATES.remove(server);
    }

    private PatrolService() {
    }
}

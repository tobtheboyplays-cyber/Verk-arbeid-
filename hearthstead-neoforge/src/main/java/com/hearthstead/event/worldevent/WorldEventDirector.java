package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The one director for the small world events (owner, 26 Sep: "make the
 * days feel a bit different and the world bigger").
 *
 * <ul>
 *   <li>Plans each settlement's day once, at the first second a player is
 *       near it that day ({@link WorldEventSchedule}); at most one event a
 *       day, some days nothing, never near a raid.</li>
 *   <li>Runs the active event only while someone is near (pause when
 *       empty); its budget counts those eligible ticks only.</li>
 *   <li>Every spawned entity carries the {@link #TAG} with its event id.
 *       Ending an event discards every loaded actor; an actor that loads
 *       later for an event that is no longer running is refused at join.</li>
 *   <li>The central kill-switch ({@code [features] worldEvents}) stops all
 *       planning and starting. Saved rows are kept untouched.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WorldEventDirector {
    public static final String TAG = "HearthsteadWorldEvent";
    private static final int CHECK_INTERVAL = 20;
    /** Outcome of an event stopped because its switch (or the master switch) was turned off. */
    public static final String DISABLED_OUTCOME = "disabled";
    private static final long START_RETRY_TICKS = 400L;
    /** How often rows of settlements that no longer exist are swept (a multiple of CHECK_INTERVAL). */
    private static final int ORPHAN_SWEEP_INTERVAL = 1200;

    private static final Map<WorldEventType, WorldEventHandler> HANDLERS = new EnumMap<>(WorldEventType.class);
    /** Transient settler assignments; handlers re-issue them every second. */
    private static final Map<UUID, Role> ROLES = new HashMap<>();
    private static final Map<UUID, Long> START_RETRY = new HashMap<>();
    private static final Set<SettlerEntity> GOAL_INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());

    static {
        register(new PeddlerEvent());
        register(new RefugeesEvent());
        register(new MinstrelsEvent());
        register(new FieldFoxEvent());
        register(new WolfPackEvent());
        register(new WildBoarEvent());
        register(new TavernBrawlEvent());
        register(new BruteTollEvent());
        register(new StrayDogEvent());
        register(new CaravanEvent());
        register(new RivalEnvoyEvent());
    }

    public enum RoleKind { HOLD, CHASE, BRAWL, WALK_OFF }

    /**
     * What an event currently asks of one settler. {@code target} is an
     * entity (chase, brawl partner); {@code pos} a place (hold, walk off).
     */
    public record Role(RoleKind kind, UUID eventId, @Nullable UUID target, @Nullable BlockPos pos,
                       long until, @Nullable SettlerActivity activity, double speed) {}

    public record Owner(Settlement settlement, WorldEventSavedData.Active active, WorldEventHandler handler) {}

    private WorldEventDirector() {
    }

    private static void register(WorldEventHandler handler) {
        HANDLERS.put(handler.type(), handler);
    }

    public static WorldEventHandler handler(WorldEventType type) {
        return HANDLERS.get(type);
    }

    /** Talk graphs must exist before a bound visitor from a saved world is clicked. */
    @SubscribeEvent
    public static void serverStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        WorldEventConversations.bootstrap();
    }

    // --------------------------------------------------------------- tick --

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
            || level.getGameTime() % CHECK_INTERVAL != 0) {
            return;
        }
        SettlementSavedData settlements = SettlementSavedData.existing(level);
        WorldEventSavedData data = null;
        int checked = 0;
        if (settlements != null) {
            for (Settlement settlement : List.copyOf(settlements.settlements.values())) {
                if (data == null) data = WorldEventSavedData.get(level);
                // The 256 cap bounds planning only: a running event always ticks, so a
                // save with many settlements can never strand one mid-event.
                WorldEventSavedData.Row running = data.row(settlement.id);
                if ((running == null || running.active == null) && ++checked > 256) continue;
                // GameTest servers host many unrelated fixtures: never plan random events there
                // (a test starts its own event explicitly); running events still tick.
                observe(level, settlement, data, !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer));
            }
        }
        // Orphan sweep runs even with no settlement left: breaking the last Banner
        // mid-event must still end that event and remove its visitors (BH-12).
        if (data == null) data = WorldEventSavedData.existing(level);
        if (data != null && level.getGameTime() % ORPHAN_SWEEP_INTERVAL == 0) {
            sweepOrphanRows(level, settlements == null ? Set.of() : settlements.settlements.keySet(), data);
        }
        long now = level.getGameTime();
        ROLES.values().removeIf(role -> role.until() < now);
    }

    /** One settlement, once per second. Public for GameTests. */
    public static void observe(ServerLevel level, Settlement settlement, WorldEventSavedData data) {
        observe(level, settlement, data, true);
    }

    public static void observe(ServerLevel level, Settlement settlement, WorldEventSavedData data, boolean mayPlan) {
        if (settlement == null || data.quarantined()) return;
        WorldEventSavedData.Row row = data.row(settlement.id);
        boolean valid = validSettlement(level, settlement);
        boolean near = valid && playerNear(level, settlement);
        if (row != null && row.active != null) {
            WorldEventSavedData.Active active = row.active;
            if (!valid) return;
            // Codex T14: the emergency-off switches also stop an event that is
            // already running, or that a save restored. Checked before
            // anything advances, and whether or not a player is near.
            if (!WorldEventConfig.enabled() || !WorldEventConfig.enabled(active.type)) {
                finish(level, settlement, DISABLED_OUTCOME, null);
                return;
            }
            if (!near) return; // pause when empty: nothing advances offline
            active.eligibleTicks += CHECK_INTERVAL;
            data.markChanged();
            WorldEventHandler handler = HANDLERS.get(active.type);
            if (handler == null) {
                finish(level, settlement, "unknown_event", null);
                return;
            }
            handler.tick(level, settlement, active);
            if (row.active == active && active.eligibleTicks >= active.type.budgetTicks()) {
                handler.timeout(level, settlement, active);
                if (row.active == active) finish(level, settlement, "timeout", null);
            }
            return;
        }
        if (row != null && row.bruteFavour) BruteTollEvent.maybeWarn(level, settlement, row, data);
        if (!mayPlan || !WorldEventConfig.enabled() || !near) return;
        long dayTime = level.getDayTime();
        long day = WorldEventSchedule.dayOf(dayTime);
        long timeOfDay = WorldEventSchedule.timeOfDay(dayTime);
        row = data.rowOrCreate(settlement.id);
        if (row == null) return;
        if (row.plannedDay != day) {
            boolean quiet = raidQuiet(level, settlement);
            // Tech tree (Caravan Routes): caravan weight x3 and a caravan due
            // every few days; both 1x/none unless learned (LogisticsEffects).
            boolean routes = com.hearthstead.settlement.techtree.effects.LogisticsEffects
                .caravanRoutes(level, settlement);
            WorldEventSchedule.Plan plan = WorldEventSchedule.plan(level.getSeed(), settlement.id, day,
                available(level, settlement), row.lastDayByType, row.lastEventDay,
                WorldEventConfig.frequency(), quiet,
                type -> com.hearthstead.settlement.techtree.effects.LogisticsEffects
                    .eventWeight(level, settlement, type)
                    // Tech tree (Great Tavern): Minstrels and Caravans x2.
                    * com.hearthstead.settlement.techtree.effects.CommonsEffects
                        .eventWeightScale(level, settlement, type),
                com.hearthstead.settlement.techtree.effects.LogisticsEffects.caravanDue(routes, day,
                    row.lastDayByType.get(WorldEventType.CARAVAN)) ? WorldEventType.CARAVAN : null);
            row.plannedDay = day;
            row.plannedType = plan.type();
            row.plannedStart = plan.startTimeOfDay();
            data.markChanged();
            Hearthstead.LOGGER.info("HEARTHSTEAD_WORLD_EVENT_PLAN settlement={} day={} event={} start={}",
                settlement.id, day, plan.quiet() ? "none" : plan.type().id(), plan.startTimeOfDay());
        }
        WorldEventSchedule.Plan plan = row.plan();
        if (plan.quiet()) return;
        if (timeOfDay > plan.type().startTo()) {
            row.plannedType = null; // the window passed; no backlog
            data.markChanged();
            return;
        }
        if (!WorldEventSchedule.inStartWindow(plan, timeOfDay)) return;
        long now = level.getGameTime();
        Long retry = START_RETRY.get(settlement.id);
        if (retry != null && now < retry && retry - now <= START_RETRY_TICKS) return;
        if (!raidQuiet(level, settlement) || !eligible(level, settlement, plan.type())) {
            row.plannedType = null;
            data.markChanged();
            return;
        }
        if (!start(level, settlement, plan.type(), false)) {
            START_RETRY.put(settlement.id, now + START_RETRY_TICKS);
        }
    }

    /** Starts {@code type} now. {@code forced} (QA command) skips the calendar, not the requirements. */
    public static boolean start(ServerLevel level, Settlement settlement, WorldEventType type, boolean forced) {
        WorldEventSavedData data = WorldEventSavedData.get(level);
        WorldEventSavedData.Row row = data.rowOrCreate(settlement.id);
        WorldEventHandler handler = HANDLERS.get(type);
        if (row == null || row.active != null || handler == null) return false;
        long day = WorldEventSchedule.dayOf(level.getDayTime());
        WorldEventSavedData.Active active = new WorldEventSavedData.Active(type, UUID.randomUUID(),
            level.getGameTime(), day);
        active.forced = forced;
        row.active = active;
        boolean started;
        try {
            started = handler.start(level, settlement, active);
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.error("HEARTHSTEAD_WORLD_EVENT_START_FAILED event={} settlement={}",
                type.id(), settlement.id, failure);
            started = false;
        }
        if (!started) {
            discardActors(level, active);
            clearRoles(active.id);
            if (row.active == active) row.active = null;
            data.markChanged();
            return false;
        }
        row.lastEventDay = day;
        row.lastDayByType.put(type, day);
        row.plannedType = null;
        START_RETRY.remove(settlement.id);
        data.markChanged();
        Hearthstead.LOGGER.info("HEARTHSTEAD_WORLD_EVENT_START event={} id={} settlement={} forced={} actors={}",
            type.id(), active.id, settlement.id, forced, active.actors.size());
        return true;
    }

    /** Ends the settlement's active event: handler cleanup, then every loaded actor is discarded. */
    public static void finish(ServerLevel level, Settlement settlement, String outcome, @Nullable Component notice) {
        WorldEventSavedData data = WorldEventSavedData.get(level);
        WorldEventSavedData.Row row = data.row(settlement.id);
        if (row == null || row.active == null) return;
        WorldEventSavedData.Active active = row.active;
        WorldEventHandler handler = HANDLERS.get(active.type);
        try {
            if (handler != null) handler.cleanup(level, settlement, active, outcome);
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.error("HEARTHSTEAD_WORLD_EVENT_CLEANUP_FAILED event={}", active.type.id(), failure);
        }
        row.active = null;
        row.lastOutcome = active.type.id() + ":" + outcome;
        if (INSTANT_OUTCOMES.contains(outcome)) {
            discardActors(level, active);
        } else {
            departActors(level, active, settlement.center);
        }
        clearRoles(active.id);
        clearMapPing(settlement.id, active);
        data.markChanged();
        if (notice != null) notice(level, settlement, notice);
        Hearthstead.LOGGER.info("HEARTHSTEAD_WORLD_EVENT_END event={} id={} settlement={} outcome={} eligibleTicks={}",
            active.type.id(), active.id, settlement.id, outcome, active.eligibleTicks);
    }

    /** Only QA/test endings remove actors on the spot; every real ending walks them out. */
    private static final Set<String> INSTANT_OUTCOMES = Set.of("stopped", "test_teardown");

    /**
     * Owner rule: no puff. Remaining actors (not dead, not released) walk out
     * together and despawn only unseen ({@link WorldEventDeparture}).
     */
    private static void departActors(ServerLevel level, WorldEventSavedData.Active active, @Nullable BlockPos from) {
        List<Entity> leaving = new ArrayList<>();
        for (UUID id : List.copyOf(active.actors)) {
            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved() || !entity.isAlive()) continue;
            if (entity instanceof net.minecraft.world.entity.npc.AbstractVillager merchant
                && merchant.getTradingPlayer() != null) {
                merchant.getTradingPlayer().closeContainer();
            }
            WorldEventConversations.unbind(entity, null);
            entity.getPersistentData().remove(TAG); // no longer an event actor: never refused at join
            leaving.add(entity);
        }
        active.actors.clear();
        if (leaving.isEmpty()) return;
        BlockPos origin = from != null ? from : leaving.get(0).blockPosition();
        BlockPos road = active.state.contains("RoadExit") ? BlockPos.of(active.state.getLong("RoadExit")) : null;
        WorldEventDeparture.depart(level, leaving, origin, road, null);
    }

    private static void discardActors(ServerLevel level, WorldEventSavedData.Active active) {
        for (UUID id : List.copyOf(active.actors)) {
            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) continue;
            if (entity instanceof net.minecraft.world.entity.npc.AbstractVillager merchant
                && merchant.getTradingPlayer() != null) {
                merchant.getTradingPlayer().closeContainer();
            }
            if (entity instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) mob.dropLeash(true, false);
            WorldEventConversations.unbind(entity, null);
            entity.discard();
        }
    }

    /** Rows of settlements that no longer exist end their event (their actors go at next load). */
    private static void sweepOrphanRows(ServerLevel level, Set<UUID> liveSettlements, WorldEventSavedData data) {
        for (var entry : data.entries()) {
            if (!liveSettlements.contains(entry.getKey())) endOrphan(level, data, entry.getKey(), entry.getValue());
        }
    }

    /**
     * The settlement is gone (its Banner was broken): end its running event at
     * once and remove that event's loaded visitors. Unloaded ones are refused
     * on their next load, like any leftover actor. Safe to call for any id.
     */
    public static void settlementRemoved(ServerLevel level, UUID settlementId) {
        WorldEventSavedData data = level == null || settlementId == null ? null : WorldEventSavedData.existing(level);
        WorldEventSavedData.Row row = data == null ? null : data.row(settlementId);
        if (row != null) endOrphan(level, data, settlementId, row);
        START_RETRY.remove(settlementId);
    }

    private static void endOrphan(ServerLevel level, WorldEventSavedData data, UUID settlementId,
                                  WorldEventSavedData.Row row) {
        if (row.active == null) return;
        departActors(level, row.active, null);
        clearRoles(row.active.id);
        clearMapPing(settlementId, row.active);
        row.lastOutcome = row.active.type.id() + ":settlement_gone";
        row.active = null;
        data.markChanged();
    }

    // --------------------------------------------------------- eligibility --

    public static boolean validSettlement(ServerLevel level, Settlement settlement) {
        SettlementSavedData saved = SettlementSavedData.existing(level);
        return settlement != null && saved != null && saved.settlements.get(settlement.id) == settlement
            && settlement.center != null && level.hasChunkAt(settlement.center);
    }

    /** A living, non-spectator player near the Banner (co-op: every player counts). */
    public static boolean playerNear(ServerLevel level, Settlement settlement) {
        double range = Math.max(80.0D, settlement.radius + 32.0D);
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double dx = player.getX() - (settlement.center.getX() + .5D);
            double dz = player.getZ() - (settlement.center.getZ() + .5D);
            if (dx * dx + dz * dz <= range * range) return true;
        }
        return false;
    }

    public static boolean insideArea(Settlement settlement, BlockPos pos) {
        double range = settlement.radius + 32.0D;
        double dx = pos.getX() - settlement.center.getX();
        double dz = pos.getZ() - settlement.center.getZ();
        return dx * dx + dz * dz <= range * range;
    }

    /** The raid gate: no event during a raid, after a warning, or on the eve/day of an attack. */
    public static boolean raidQuiet(ServerLevel level, Settlement settlement) {
        RaidLifecycle raid = settlement.raidLifecycle;
        boolean active = settlement.pendingRaid != null || raid.isAuthoredFirstRaidActive()
            || settlement.recurringRaidRun.isActive() || raid.activePlan().isPresent();
        boolean warned = raid.recurringWarnedPlan().isPresent() || raid.queuedPlan().isPresent();
        long night = RaidLifecycle.raidNightOf(level.getDayTime());
        long attack = raid.recurringNextAttackNight();
        if (attack == RaidLifecycle.UNSET_NIGHT && raid.firstState() == FirstRaidState.SCHEDULED) {
            attack = raid.firstAttackNight();
        }
        return WorldEventSchedule.raidQuiet(active, warned, night, attack);
    }

    /** Events whose world requirements exist and that are switched on. */
    public static List<WorldEventType> available(ServerLevel level, Settlement settlement) {
        List<WorldEventType> out = new ArrayList<>();
        for (WorldEventType type : WorldEventType.values()) {
            if (eligible(level, settlement, type)) out.add(type);
        }
        return out;
    }

    /** In-game days a new settlement is safe from hostile events (survival QA, 26 Sep). */
    public static final int HOSTILE_GRACE_DAYS = 3;

    /**
     * One eligibility rule for both the planner and the start path: switched
     * on, not hostile on Peaceful, hostile only once the settlement can
     * defend itself, and the event's own world requirements.
     */
    public static boolean eligible(ServerLevel level, Settlement settlement, WorldEventType type) {
        WorldEventHandler handler = HANDLERS.get(type);
        if (handler == null || !WorldEventConfig.enabled(type)) return false;
        if (type.hostile() && (level.getDifficulty() == Difficulty.PEACEFUL || !hostileReady(level, settlement))) {
            return false;
        }
        try {
            return handler.available(level, settlement);
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.warn("HEARTHSTEAD_WORLD_EVENT_AVAILABILITY_FAILED event={}", type.id(), failure);
            return false;
        }
    }

    /**
     * Hostile events (brute toll, wolves, boar) wait until the settlement is
     * at least {@link #HOSTILE_GRACE_DAYS} in-game days old (an unknown
     * founding day, as in old saves, counts as old enough) AND it has a
     * hired martial settler (Guard, Archer...) or has come through its first
     * raid. A day-0 village with no guard never meets three brutes.
     */
    public static boolean hostileReady(ServerLevel level, Settlement settlement) {
        long founded = settlement.raidLifecycle.foundedNight();
        if (founded != RaidLifecycle.UNSET_NIGHT
            && WorldEventSchedule.dayOf(level.getDayTime()) - founded < HOSTILE_GRACE_DAYS) {
            return false;
        }
        if (settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED) return true;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record.profession != null && record.profession.martial()) return true;
        }
        return false;
    }

    // -------------------------------------------------------------- actors --

    /** Tags {@code entity} as an actor of {@code active} and records it for cleanup. */
    public static void tag(Entity entity, Settlement settlement, WorldEventSavedData.Active active, String role) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Event", active.id);
        tag.putUUID("Settlement", settlement.id);
        tag.putString("Role", role);
        entity.getPersistentData().put(TAG, tag);
        active.addActor(entity.getUUID());
        // Roles are also kept on the event, so an unloaded actor is still known by role.
        CompoundTag roles = active.state.getCompound("Roles");
        roles.putString(entity.getUUID().toString(), role);
        active.state.put("Roles", roles);
    }

    /** Removes the tag and bookkeeping (the entity stays: e.g. accepted refugees). */
    public static void release(Entity entity, WorldEventSavedData.Active active) {
        entity.getPersistentData().remove(TAG);
        active.actors.remove(entity.getUUID());
        ROLES.remove(entity.getUUID());
    }

    @Nullable
    public static UUID tagEvent(Entity entity) {
        CompoundTag tag = entity.getPersistentData().getCompound(TAG);
        return tag.hasUUID("Event") ? tag.getUUID("Event") : null;
    }

    @Nullable
    public static UUID tagSettlement(Entity entity) {
        CompoundTag tag = entity.getPersistentData().getCompound(TAG);
        return tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null;
    }

    public static String tagRole(Entity entity) {
        return entity.getPersistentData().getCompound(TAG).getString("Role");
    }

    /** The running event that owns {@code entity}, or null. */
    @Nullable
    public static Owner owner(ServerLevel level, Entity entity) {
        if (!entity.getPersistentData().contains(TAG)) return null;
        UUID eventId = tagEvent(entity);
        WorldEventSavedData data = WorldEventSavedData.existing(level);
        if (eventId == null || data == null) return null;
        WorldEventSavedData.Active active = data.activeById(eventId);
        UUID settlementId = data.settlementOfEvent(eventId);
        Settlement settlement = settlementId == null ? null : SettlementManager.byId(level, settlementId);
        WorldEventHandler handler = active == null ? null : HANDLERS.get(active.type);
        return active == null || settlement == null || handler == null ? null
            : new Owner(settlement, active, handler);
    }

    @Nullable
    public static Owner ownerOfEvent(ServerLevel level, UUID eventId) {
        WorldEventSavedData data = WorldEventSavedData.existing(level);
        if (data == null) return null;
        WorldEventSavedData.Active active = data.activeById(eventId);
        UUID settlementId = data.settlementOfEvent(eventId);
        Settlement settlement = settlementId == null ? null : SettlementManager.byId(level, settlementId);
        return active == null || settlement == null ? null
            : new Owner(settlement, active, HANDLERS.get(active.type));
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (entity instanceof SettlerEntity settler && GOAL_INSTALLED.add(settler)) {
            settler.goalSelector.addGoal(2, new WorldEventSettlerGoal(settler));
        }
        if (entity instanceof net.minecraft.world.entity.animal.Wolf dog
            && dog.getPersistentData().contains(VillageDog.TAG)) {
            // Exactly one village dog per settlement; a stale copy never comes back.
            if (!VillageDog.accept(level, dog)) event.setCanceled(true);
            return;
        }
        if (!entity.getPersistentData().contains(TAG)) return;
        Owner owner = owner(level, entity);
        if (owner == null || !owner.active().actors.contains(entity.getUUID())) {
            // A leftover actor of an event that already ended: never let it back in.
            if (entity instanceof SettlerEntity settler && settler.isBound()) {
                entity.getPersistentData().remove(TAG); // a settler who joined for real stays
                return;
            }
            event.setCanceled(true);
            Hearthstead.LOGGER.info("HEARTHSTEAD_WORLD_EVENT_LEFTOVER_REMOVED entity={} type={}",
                entity.getUUID(), entity.getType());
            return;
        }
        owner.handler().actorLoaded(level, owner.active(), entity);
    }

    /**
     * BH-30: event actors and the village dog never change dimension. The
     * join check in another dimension cannot find their settlement and
     * refuses them, which deleted the dog for good (and, with the row still
     * naming it, no new stray could ever come).
     */
    @SubscribeEvent
    public static void travel(net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        if (entity.getPersistentData().contains(TAG)
            || entity instanceof net.minecraft.world.entity.animal.Wolf
                && entity.getPersistentData().contains(VillageDog.TAG)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (entity instanceof net.minecraft.world.entity.animal.Wolf dog && dog.getPersistentData().contains(VillageDog.TAG)) {
            VillageDog.died(level, dog);
        }
        if (entity.getPersistentData().contains(TAG)) {
            Owner owner = owner(level, entity);
            if (owner != null) {
                // A confirmed death, told apart from an actor that is merely unloaded.
                WorldEventActors.markDead(owner.active(), entity);
                WorldEventSavedData.get(level).markChanged();
                owner.handler().actorDied(level, owner.settlement(), owner.active(), entity, event.getSource());
            }
            // A corpse is no longer an event actor (its role and death are on the event itself).
            entity.getPersistentData().remove(TAG);
        }
        // Livestock killed by a pack wolf counts against the pack's budget.
        if (event.getSource().getEntity() instanceof PackWolfEntity wolf && WorldEventCreatures.isLivestockType(entity)) {
            WolfPackEvent.livestockKilled(level, wolf);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ServerPlayer brawlPlayer
            && event.getTarget() instanceof SettlerEntity maybeBrawler && maybeBrawler.getSettlementId() != null) {
            // A brawler is a real settler, not a tagged actor: stepping in is a click on either of them.
            WorldEventSavedData data = WorldEventSavedData.existing(brawlPlayer.serverLevel());
            WorldEventSavedData.Row row = data == null ? null : data.row(maybeBrawler.getSettlementId());
            Settlement home = maybeBrawler.settlement();
            if (row != null && row.active != null && row.active.type == WorldEventType.TAVERN_BRAWL && home != null
                && TavernBrawlEvent.isBrawler(row.active, maybeBrawler.getUUID())
                && brawlPlayer.distanceToSqr(maybeBrawler) <= WorldEventVisitors.TALK_DISTANCE * WorldEventVisitors.TALK_DISTANCE) {
                if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                    TavernBrawlEvent.separate(brawlPlayer.serverLevel(), home, row.active, brawlPlayer);
                }
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }
        }
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || !event.getTarget().getPersistentData().contains(TAG)
            || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            if (!event.getLevel().isClientSide() && event.getTarget().getPersistentData().contains(TAG)) {
                // Off-hand pass of the same click: swallow so vanilla menus never open underneath.
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
            return;
        }
        Entity target = event.getTarget();
        if (com.hearthstead.conversation.ConversationConfig.enabled()
            && com.hearthstead.conversation.ConversationService.isBound(target)) {
            return; // the talk UI owns this click (its handler cancels vanilla)
        }
        Owner owner = owner(player.serverLevel(), target);
        if (owner == null) return;
        boolean handled = WorldEventVisitors.present(player, target)
            || owner.handler().interact(player.serverLevel(), owner.settlement(), owner.active(), player, target);
        if (handled || target instanceof net.minecraft.world.entity.npc.AbstractVillager
            || target instanceof SettlerEntity) {
            // Never fall through to vanilla trading or the settler sheet for an event visitor.
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    // --------------------------------------------------------------- roles --

    public static void assign(SettlerEntity settler, Role role) {
        ROLES.put(settler.getUUID(), role);
    }

    @Nullable
    public static Role role(SettlerEntity settler) {
        Role role = ROLES.get(settler.getUUID());
        if (role == null) return null;
        if (role.until() < settler.level().getGameTime()) {
            ROLES.remove(settler.getUUID());
            return null;
        }
        return role;
    }

    public static void clearRole(SettlerEntity settler) {
        ROLES.remove(settler.getUUID());
    }

    private static void clearRoles(UUID eventId) {
        for (Iterator<Role> it = ROLES.values().iterator(); it.hasNext();) {
            if (it.next().eventId().equals(eventId)) it.remove();
        }
    }

    /** A settler carrying out a CHASE role reached its target. */
    static void roleReached(SettlerEntity settler, Role role) {
        if (!(settler.level() instanceof ServerLevel level)) return;
        Owner owner = ownerOfEvent(level, role.eventId());
        if (owner == null || owner.handler() == null) return;
        if (owner.handler() instanceof RoleListener listener) {
            listener.roleReached(level, owner.settlement(), owner.active(), settler, role);
        }
    }

    /** Handlers that care when a settler finishes a chase. */
    public interface RoleListener {
        void roleReached(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                         SettlerEntity settler, Role role);
    }

    // ------------------------------------------------- creature callbacks --

    /** Whether a pack wolf may still pick a livestock target (kill budget). */
    public static boolean packMayHunt(PackWolfEntity wolf) {
        if (!(wolf.level() instanceof ServerLevel level)) return false;
        Owner owner = owner(level, wolf);
        return owner != null && owner.active().state.getInt("Kills") < WolfPackEvent.MAX_KILLS;
    }

    /** Crop damage bookkeeping for the summary line. */
    public static void noteCropDamage(Entity actor, int amount) {
        if (!(actor.level() instanceof ServerLevel level)) return;
        Owner owner = owner(level, actor);
        if (owner == null) return;
        owner.active().state.putInt("CropDamage", owner.active().state.getInt("CropDamage") + amount);
        WorldEventSavedData.get(level).markChanged();
    }

    // ------------------------------------------------------------- notices --

    /** Chat notice to everyone near the settlement. */
    public static void notice(ServerLevel level, Settlement settlement, Component message) {
        double range = settlement.radius + 48.0D;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - settlement.center.getX();
            double dz = player.getZ() - settlement.center.getZ();
            if (dx * dx + dz * dz <= range * range) player.displayClientMessage(message, false);
        }
    }

    /** Headline notice with a call to action, plus a map ping when the realm map exposes one. */
    public static void announce(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                Component headline, Component action, BlockPos where) {
        notice(level, settlement, Component.empty()
            .append(Component.literal("⚑ ").withStyle(ChatFormatting.GOLD))
            .append(headline.copy().withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" "))
            .append(action.copy().withStyle(ChatFormatting.YELLOW)));
        mapPing(level, settlement, active, where, headline);
    }

    private static Method pingPut;
    private static Method pingClear;
    private static boolean pingResolved;

    /**
     * Single call site for the realm map's event ping. The map lane is adding
     * {@code com.hearthstead.network.RealmMapPings}; until it exists this is a
     * no-op (resolved reflectively once, so this lane never edits map files).
     */
    public static void mapPing(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                               BlockPos where, Component label) {
        resolvePings();
        if (pingPut == null || where == null) return;
        try {
            long expires = level.getGameTime() + Math.max(1200L, active.type.budgetTicks());
            pingPut.invoke(null, level, settlement.id, "event:" + active.id, where,
                "event_" + active.type.id(), label, expires);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A map without pings never blocks an event.
        }
    }

    private static void clearMapPing(UUID settlementId, WorldEventSavedData.Active active) {
        resolvePings();
        if (pingClear == null) return;
        try {
            pingClear.invoke(null, settlementId, "event:" + active.id);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void resolvePings() {
        if (pingResolved) return;
        pingResolved = true;
        try {
            Class<?> pings = Class.forName("com.hearthstead.network.RealmMapPings");
            pingPut = pings.getMethod("put", ServerLevel.class, UUID.class, String.class, BlockPos.class,
                String.class, Component.class, long.class);
            pingClear = pings.getMethod("clear", UUID.class, String.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            pingPut = null;
            pingClear = null;
        }
    }

    // ------------------------------------------------------------ QA hooks --

    /**
     * A new world in the same JVM (singleplayer: quit, open another save) must
     * not inherit the last world's settler roles or start retries.
     */
    @SubscribeEvent
    public static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ROLES.clear();
        START_RETRY.clear();
    }

    /** Forgets transient role/retry state (GameTests). */
    public static void resetTransientForTests() {
        ROLES.clear();
        START_RETRY.clear();
    }

    public static Map<UUID, Role> rolesView() {
        return Collections.unmodifiableMap(ROLES);
    }
}

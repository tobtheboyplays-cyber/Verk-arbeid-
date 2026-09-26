package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.CoinTreasury;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.defense.AlarmBell;
import com.hearthstead.settlement.raid.RaidHoldNotice;
import com.hearthstead.settlement.state.RaidLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server side of the Banner screen's live realm map.
 *
 * <p>Authority: a subscription exists only while the player has the exact
 * server-opened {@link HearthMenu} of that Banner open -- the same rule the
 * Mayor, People and Journey pages use. It is re-validated on receipt and on
 * every broadcast; a closed, swapped or out-of-reach menu drops it silently.
 * Only that one settlement's roster is ever projected.
 *
 * <p>Cost: one pass per subscribed settlement every {@link #INTERVAL_TICKS}
 * ticks over its recorded members, each resolved with a loaded-entity lookup
 * ({@link ServerLevel#getEntity(UUID)}). Nothing here loads a chunk: the
 * Banner block entity is only read when its chunk is already loaded and the
 * coin count uses the existing loaded-only warehouse view.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class RealmMapNetwork {
    public static final int INTERVAL_TICKS = 10;
    static final int COIN_INTERVAL_TICKS = 100;
    /** Navigating with less than one block of progress for this long reads as "stuck". */
    static final int STALL_TICKS = 100;
    static final double STALL_DISTANCE_SQ = 1.0D;

    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();

    private RealmMapNetwork() {
    }

    // ------------------------------------------------------------ state ---

    private static final class Subscription {
        final BlockPos hearthPos;
        final UUID settlementId;
        final int containerId;
        UUID focus = RealmMapRequestPayload.NO_FOCUS;
        int sentLayoutRevision = Integer.MIN_VALUE;

        Subscription(BlockPos hearthPos, UUID settlementId, int containerId) {
            this.hearthPos = hearthPos;
            this.settlementId = settlementId;
            this.containerId = containerId;
        }
    }

    private static final class Motion {
        double x;
        double z;
        long anchorTick;
        long seenTick;
    }

    private static final class SettlementState {
        int revision;
        long layoutHash;
        boolean hashed;
        RealmMapLayoutPayload layout;
        int coins = -1;
        long coinsAt = Long.MIN_VALUE;
        final Map<UUID, Motion> motion = new HashMap<>();
        List<RealmMapMarkersPayload.Marker> markers = List.of();
        List<RealmMapMarkersPayload.Raider> raiders = List.of();
        List<RealmMapMarkersPayload.Talker> talkers = List.of();
        long markersTick = Long.MIN_VALUE;
        BlockPos bell;
    }

    private static final class ServerState {
        final Map<UUID, Subscription> subscriptions = new HashMap<>();
        final Map<UUID, SettlementState> settlements = new HashMap<>();
        /** Game time of each player's last immediate (request-driven) send. */
        final Map<UUID, Long> lastImmediate = new HashMap<>();
        long perfNanos;
        int perfPasses;
        long perfSince = Long.MIN_VALUE;
    }

    /**
     * Request-driven sends are rate limited per player: a request within
     * this many ticks of the previous immediate send only updates the
     * subscription and rides the next regular broadcast.
     */
    static final int MIN_IMMEDIATE_TICKS = 4;
    private static final boolean PERF = Boolean.getBoolean("hearthstead.mapPerf");

    // ---------------------------------------------------------- requests ---

    public static void handle(ServerPlayer player, RealmMapRequestPayload request) {
        if (player == null || request == null || request.kind() == RealmMapRequestPayload.Kind.UNKNOWN) {
            return;
        }
        MinecraftServer server = player.server;
        if (request.kind() == RealmMapRequestPayload.Kind.UNSUBSCRIBE) {
            ServerState state = STATES.get(server);
            if (state != null) state.subscriptions.remove(player.getUUID());
            return;
        }
        Settlement settlement = resolve(player, request.hearthPos(), request.settlementId(),
            request.containerId());
        if (settlement == null) {
            ServerState state = STATES.get(server);
            if (state != null) state.subscriptions.remove(player.getUUID());
            return;
        }
        ServerState state = STATES.computeIfAbsent(server, s -> new ServerState());
        Subscription sub = state.subscriptions.get(player.getUUID());
        if (request.kind() == RealmMapRequestPayload.Kind.SUBSCRIBE || sub == null
            || !sameMenu(sub, request)) {
            sub = new Subscription(request.hearthPos(), request.settlementId(), request.containerId());
            state.subscriptions.put(player.getUUID(), sub);
        }
        if (request.kind() == RealmMapRequestPayload.Kind.FOCUS || !isNoFocus(request.focus())) {
            sub.focus = request.focus();
        }
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        Long last = state.lastImmediate.get(player.getUUID());
        if (last != null && now >= last && now - last < MIN_IMMEDIATE_TICKS) return;
        state.lastImmediate.put(player.getUUID(), now);
        SettlementState settlementState = state.settlements.computeIfAbsent(settlement.id,
            id -> new SettlementState());
        // Markers are shared per settlement and tick, so several viewers (or a burst) cost one projection.
        refreshSettlement(level, settlement, settlementState, now, false);
        sendTo(player, level, settlement, settlementState, sub);
    }

    private static boolean sameMenu(Subscription sub, RealmMapRequestPayload request) {
        return sub.containerId == request.containerId()
            && sub.hearthPos.equals(request.hearthPos())
            && sub.settlementId.equals(request.settlementId());
    }

    private static boolean isNoFocus(UUID id) {
        return id == null || RealmMapRequestPayload.NO_FOCUS.equals(id);
    }

    /**
     * The exact-open-menu rule shared with {@code HearthNetwork}: the echoed
     * identity must match the menu the server opened for this player, the
     * menu must still be valid (in reach), and the live Banner block entity
     * must still carry that settlement. The block entity is read only from
     * an already-loaded chunk.
     */
    static Settlement resolve(ServerPlayer player, BlockPos hearthPos, UUID settlementId, int containerId) {
        if (!(player.containerMenu instanceof HearthMenu menu)
            || menu.getContainerId() != containerId
            || !menu.getHearthPos().equals(hearthPos)
            || !menu.getSettlementId().equals(settlementId)
            || !menu.stillValid(player)) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(hearthPos)
            || !(level.getBlockEntity(hearthPos) instanceof HearthBlockEntity hearth)) {
            return null;
        }
        UUID live = hearth.getSettlementId();
        if (live == null || !live.equals(settlementId)) {
            return null;
        }
        Settlement settlement = SettlementManager.byId(level, live);
        return settlement != null && settlement.center.equals(hearthPos) ? settlement : null;
    }

    // -------------------------------------------------------------- tick ---

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }
        ServerState state = STATES.get(server);
        if (state == null || state.subscriptions.isEmpty()) {
            if (state != null) state.settlements.clear();
            return;
        }
        long perfStart = PERF ? System.nanoTime() : 0L;
        Map<UUID, Boolean> live = new HashMap<>();
        Iterator<Map.Entry<UUID, Subscription>> it = state.subscriptions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Subscription> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Subscription sub = entry.getValue();
            Settlement settlement = player == null ? null
                : resolve(player, sub.hearthPos, sub.settlementId, sub.containerId);
            if (settlement == null) {
                it.remove();
                continue;
            }
            ServerLevel level = player.serverLevel();
            SettlementState settlementState = state.settlements.computeIfAbsent(settlement.id,
                id -> new SettlementState());
            refreshSettlement(level, settlement, settlementState, level.getGameTime(), false);
            live.put(settlement.id, Boolean.TRUE);
            try {
                sendTo(player, level, settlement, settlementState, sub);
            } catch (RuntimeException failure) {
                // A display feed must never take the server tick down with it.
                Hearthstead.LOGGER.warn("Realm map broadcast failed for {}; dropping subscription",
                    player.getGameProfile().getName(), failure);
                it.remove();
            }
        }
        state.settlements.keySet().removeIf(id -> !live.containsKey(id));
        if (PERF) {
            state.perfNanos += System.nanoTime() - perfStart;
            state.perfPasses++;
            long tick = server.getTickCount();
            if (state.perfSince == Long.MIN_VALUE) state.perfSince = tick;
            if (tick - state.perfSince >= 200L) {
                Hearthstead.LOGGER.info("[realm-map] server broadcast avg {} us per pass over {} passes, {} viewers",
                    state.perfNanos / Math.max(1, state.perfPasses) / 1000L, state.perfPasses,
                    state.subscriptions.size());
                state.perfNanos = 0L;
                state.perfPasses = 0;
                state.perfSince = tick;
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerState state = STATES.get(player.server);
            if (state != null) {
                state.subscriptions.remove(player.getUUID());
                state.lastImmediate.remove(player.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    /** Test/diagnostic view: is this player currently subscribed to that settlement? */
    public static boolean isSubscribed(ServerPlayer player, UUID settlementId) {
        ServerState state = STATES.get(player.server);
        Subscription sub = state == null ? null : state.subscriptions.get(player.getUUID());
        return sub != null && sub.settlementId.equals(settlementId);
    }

    // ------------------------------------------------------- projection ---

    private static void refreshSettlement(ServerLevel level, Settlement settlement, SettlementState state,
                                          long now, boolean force) {
        boolean slowTick = state.coinsAt == Long.MIN_VALUE || now - state.coinsAt >= COIN_INTERVAL_TICKS
            || now < state.coinsAt;
        if (slowTick) {
            // Bounded, loaded-only scan with its own cache; at the slow cadence only.
            state.bell = AlarmBell.findBell(level, settlement);
        }
        RaidCard raid = raidCard(level, settlement);
        long hash = (layoutHash(level, settlement) * 31 + (state.bell == null ? 0 : state.bell.asLong())) * 31
            + raid.hashCode();
        if (!state.hashed || hash != state.layoutHash || state.layout == null) {
            state.layoutHash = hash;
            state.hashed = true;
            state.revision++;
            state.layout = buildLayout(level, settlement, state.revision, state.bell, raid);
        }
        if (force || state.markersTick != now) {
            state.markers = buildMarkers(level, settlement, state, now);
            state.raiders = raidersNear(level, settlement);
            state.talkers = talkersNear(level, settlement);
            state.markersTick = now;
        }
        if (slowTick) {
            state.coins = countCoins(level, settlement);
            state.coinsAt = now;
        }
    }

    private static void sendTo(ServerPlayer player, ServerLevel level, Settlement settlement,
                               SettlementState state, Subscription sub) {
        if (sub.sentLayoutRevision != state.revision) {
            PayloadSend.toPlayer(player, state.layout); // BH-20: never throws from the tick
            sub.sentLayoutRevision = state.revision;
        }
        RealmMapMarkersPayload.Focus focus = focusOf(level, settlement, sub.focus);
        PayloadSend.toPlayer(player, new RealmMapMarkersPayload(settlement.id,
            state.revision, level.getGameTime(), state.coins, state.markers, focus, state.raiders, state.talkers));
    }

    static long layoutHash(ServerLevel level, Settlement settlement) {
        long h = 1125899906842597L;
        h = 31 * h + settlement.center.asLong();
        h = 31 * h + settlement.radius;
        h = 31 * h + Objects.hashCode(settlement.mayorId);
        for (Building b : settlement.buildings) {
            h = 31 * h + b.id.hashCode();
            h = 31 * h + (b.type == null ? 0 : b.type.ordinal());
            h = 31 * h + b.level;
            h = 31 * h + (b.valid ? 1 : 0);
            h = 31 * h + (b.bounds == null ? 0 : b.bounds.hashCode());
            h = 31 * h + b.workers.hashCode();
        }
        for (Settlement.SettlerRecord record : settlement.settlers) {
            h = 31 * h + record.entityId.hashCode();
            h = 31 * h + Objects.hashCode(record.name);
            h = 31 * h + (record.profession == null ? 0 : record.profession.ordinal());
            Entity entity = level.getEntity(record.entityId);
            h = 31 * h + (entity instanceof SettlerEntity settler ? 1 + settler.getAppearanceSeed() : 0);
        }
        return h;
    }

    /** Next recurring raid, warning and hold state for the Defense card; read-only. */
    record RaidCard(int nextInDays, boolean warned, int holdReason) {
        static final RaidCard NONE = new RaidCard(RealmMapLayoutPayload.NO_RAID, false, RealmMapLayoutPayload.NO_HOLD);
    }

    static RaidCard raidCard(ServerLevel level, Settlement settlement) {
        try {
            RaidLifecycle lifecycle = settlement.raidLifecycle;
            if (lifecycle == null) return RaidCard.NONE;
            long night = RaidLifecycle.raidNightOf(level.getDayTime());
            long next = lifecycle.recurringNextAttackNight();
            int nextIn = next == RaidLifecycle.UNSET_NIGHT ? RealmMapLayoutPayload.NO_RAID
                : (int) Math.max(0L, Math.min(99L, next - night));
            boolean warned = lifecycle.recurringWarnedPlan().isPresent();
            int hold = RaidHoldNotice.lastNotice(level, settlement)
                .filter(last -> last.night() >= night)
                .map(last -> last.reason().ordinal()).orElse(RealmMapLayoutPayload.NO_HOLD);
            return new RaidCard(nextIn, warned, hold);
        } catch (RuntimeException failure) {
            return RaidCard.NONE;
        }
    }

    static RealmMapLayoutPayload buildLayout(ServerLevel level, Settlement settlement, int revision,
                                             BlockPos bell) {
        return buildLayout(level, settlement, revision, bell, RaidCard.NONE);
    }

    static RealmMapLayoutPayload buildLayout(ServerLevel level, Settlement settlement, int revision,
                                             BlockPos bell, RaidCard raid) {
        List<RealmMapLayoutPayload.BuildingEntry> buildings = new ArrayList<>();
        Map<UUID, Integer> buildingIndex = new HashMap<>();
        for (Building b : settlement.buildings) {
            if (buildings.size() >= RealmMapLayoutPayload.MAX_BUILDINGS) break;
            BoundingBox box = b.bounds;
            BlockPos plaque = b.plaquePos == null ? settlement.center : b.plaquePos;
            int minX = box == null ? plaque.getX() - 1 : box.minX();
            int minZ = box == null ? plaque.getZ() - 1 : box.minZ();
            int maxX = box == null ? plaque.getX() + 1 : box.maxX();
            int maxZ = box == null ? plaque.getZ() + 1 : box.maxZ();
            buildingIndex.put(b.id, buildings.size());
            buildings.add(new RealmMapLayoutPayload.BuildingEntry(b.id,
                b.type == null ? "" : b.type.id(), b.level, minX, minZ, maxX, maxZ,
                plaque.getX(), plaque.getZ(), b.valid,
                b.type == null ? 0 : b.type.workerCapacity()));
        }
        List<RealmMapLayoutPayload.RosterEntry> roster = new ArrayList<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (roster.size() >= RealmMapLayoutPayload.MAX_ROSTER) break;
            Building employer = Employment.employerOf(settlement, record.entityId);
            Integer index = employer == null ? null : buildingIndex.get(employer.id);
            Entity entity = level.getEntity(record.entityId);
            SettlerEntity settler = entity instanceof SettlerEntity s && s.isAlive()
                && settlement.id.equals(s.getSettlementId()) ? s : null;
            roster.add(new RealmMapLayoutPayload.RosterEntry(record.entityId, record.name,
                record.profession == null ? 0 : record.profession.id(),
                index == null ? RealmMapLayoutPayload.NO_BUILDING : index,
                settler == null ? -1 : settler.getAppearanceSeed(), settler != null));
        }
        BlockPos c = settlement.center;
        return new RealmMapLayoutPayload(settlement.id, revision, c.getX(), c.getY(), c.getZ(),
            settlement.radius, settlement.mayorId, bell != null, bell == null ? 0 : bell.getX(),
            bell == null ? 0 : bell.getZ(), raid.nextInDays(), raid.warned(), raid.holdReason(), buildings, roster);
    }

    private static List<RealmMapMarkersPayload.Marker> buildMarkers(ServerLevel level, Settlement settlement,
                                                                    SettlementState state, long now) {
        List<RealmMapMarkersPayload.Marker> markers = new ArrayList<>(settlement.settlers.size());
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (markers.size() >= RealmMapMarkersPayload.MAX_MARKERS) break;
            if (!(level.getEntity(record.entityId) instanceof SettlerEntity settler)
                || !settler.isAlive() || settler.isRemoved()
                || !settlement.id.equals(settler.getSettlementId())) {
                continue;
            }
            Motion motion = state.motion.get(record.entityId);
            if (motion == null) {
                motion = new Motion();
                motion.x = settler.getX();
                motion.z = settler.getZ();
                motion.anchorTick = now;
                state.motion.put(record.entityId, motion);
            }
            double dx = settler.getX() - motion.x;
            double dz = settler.getZ() - motion.z;
            boolean navigating = settler.getNavigation().isInProgress();
            if (dx * dx + dz * dz > STALL_DISTANCE_SQ || !navigating) {
                motion.x = settler.getX();
                motion.z = settler.getZ();
                motion.anchorTick = now;
            }
            motion.seenTick = now;
            boolean stalled = stalledFor(now - motion.anchorTick) || settler.getNavigation().isStuck();
            RealmMapStatus status = RealmMapStatus.classify(settler.getActivity(), navigating, stalled);
            markers.add(new RealmMapMarkersPayload.Marker(record.entityId, settler.getId(),
                (float) (settler.getX() - settlement.center.getX()), (float) settler.getY(),
                (float) (settler.getZ() - settlement.center.getZ()),
                settler.getProfession().id(), settler.getActivity().id(), status.wireId()));
        }
        state.motion.values().removeIf(m -> m.seenTick != now);
        return List.copyOf(markers);
    }

    /** Test view: one fresh marker projection for a settlement (no stall history). */
    static List<RealmMapMarkersPayload.Marker> markersFor(ServerLevel level, Settlement settlement) {
        return buildMarkers(level, settlement, new SettlementState(), level.getGameTime());
    }

    /**
     * Raiders within the claim plus a margin, from already-loaded entity
     * sections only (getEntitiesOfClass never loads a chunk).
     */
    static List<RealmMapMarkersPayload.Raider> raidersNear(ServerLevel level, Settlement settlement) {
        int reach = settlement.radius + 48;
        BlockPos c = settlement.center;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c.getX() - reach, c.getY() - 64,
            c.getZ() - reach, c.getX() + reach + 1, c.getY() + 64, c.getZ() + reach + 1);
        List<com.hearthstead.entity.RaiderEntity> found = level.getEntitiesOfClass(
            com.hearthstead.entity.RaiderEntity.class, box, e -> e.isAlive() && !e.isRemoved());
        if (found.isEmpty()) return List.of();
        List<RealmMapMarkersPayload.Raider> out = new ArrayList<>(Math.min(found.size(), RealmMapMarkersPayload.MAX_RAIDERS));
        for (com.hearthstead.entity.RaiderEntity raider : found) {
            if (out.size() >= RealmMapMarkersPayload.MAX_RAIDERS) break;
            out.add(new RealmMapMarkersPayload.Raider(raider.getId(), (float) (raider.getX() - c.getX()),
                (float) (raider.getZ() - c.getZ()), raider.isCaptain()));
        }
        return List.copyOf(out);
    }

    /**
     * Everyone near the claim who wants to talk: bound to a conversation
     * (the in-world "!") and not in a talk right now. Loaded sections only.
     */
    static List<RealmMapMarkersPayload.Talker> talkersNear(ServerLevel level, Settlement settlement) {
        int reach = settlement.radius + 48;
        BlockPos c = settlement.center;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c.getX() - reach, c.getY() - 64,
            c.getZ() - reach, c.getX() + reach + 1, c.getY() + 64, c.getZ() + reach + 1);
        List<net.minecraft.world.entity.Entity> found = level.getEntities((net.minecraft.world.entity.Entity) null, box,
            e -> e.isAlive() && !e.isRemoved() && wantsToTalk(e) && talksToSettlement(e, settlement.id));
        if (found.isEmpty()) return List.of();
        List<RealmMapMarkersPayload.Talker> out = new ArrayList<>(Math.min(found.size(),
            RealmMapMarkersPayload.MAX_TALKERS));
        for (net.minecraft.world.entity.Entity npc : found) {
            if (out.size() >= RealmMapMarkersPayload.MAX_TALKERS) break;
            net.minecraft.nbt.CompoundTag bind = npc.getPersistentData()
                .getCompound(com.hearthstead.conversation.ConversationService.BIND_TAG);
            com.hearthstead.conversation.SpeakerProfile profile =
                com.hearthstead.conversation.SpeakerProfile.read(bind.getCompound("Profile"));
            String name = profile != null && !profile.name().isBlank() ? profile.name() : npc.getName().getString();
            out.add(new RealmMapMarkersPayload.Talker(npc.getId(), (float) (npc.getX() - c.getX()),
                (float) (npc.getZ() - c.getZ()), name, profile == null ? "" : profile.titleKey(), talkKind(bind)));
        }
        return List.copyOf(out);
    }

    /** The conversation system's "!": bound to a talk and not already talking. */
    static boolean wantsToTalk(net.minecraft.world.entity.Entity npc) {
        return com.hearthstead.conversation.ConversationService.isBound(npc)
            && !com.hearthstead.conversation.ConversationService.isTalking(npc);
    }

    /**
     * Which badge the map shows, by the same rule as the conversation lane's
     * in-world marker (ConversationService.markerKind): parley graphs are
     * crimson, peddlers/merchants/caravans trade, quests ask, the rest talk.
     */
    static int talkKind(net.minecraft.nbt.CompoundTag bind) {
        if (bind.getString("Graph").contains("parley")) return RealmMapMarkersPayload.Talker.PARLEY;
        String kind = bind.getCompound("Profile").getString("Kind");
        if ("peddler".equals(kind) || "merchant".equals(kind) || "caravan".equals(kind)) {
            return RealmMapMarkersPayload.Talker.TRADE;
        }
        if ("quest".equals(kind)) return RealmMapMarkersPayload.Talker.QUEST;
        return RealmMapMarkersPayload.Talker.TALK;
    }

    /** A talk bound to another settlement is not shown on this one's map. */
    static boolean talksToSettlement(net.minecraft.world.entity.Entity npc, UUID settlementId) {
        net.minecraft.nbt.CompoundTag bind = npc.getPersistentData()
            .getCompound(com.hearthstead.conversation.ConversationService.BIND_TAG);
        return !bind.hasUUID("Settlement") || bind.getUUID("Settlement").equals(settlementId);
    }

    static boolean stalledFor(long ticksWithoutProgress) {
        return ticksWithoutProgress >= STALL_TICKS;
    }

    private static RealmMapMarkersPayload.Focus focusOf(ServerLevel level, Settlement settlement, UUID focus) {
        if (isNoFocus(focus)) return RealmMapMarkersPayload.Focus.NONE;
        boolean member = false;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record.entityId.equals(focus)) {
                member = true;
                break;
            }
        }
        if (!member || !(level.getEntity(focus) instanceof SettlerEntity settler)
            || !settler.isAlive() || !settlement.id.equals(settler.getSettlementId())) {
            return RealmMapMarkersPayload.Focus.NONE;
        }
        List<RealmMapMarkersPayload.BagSlot> bag = new ArrayList<>(SettlerEntity.BAG_SIZE);
        for (int slot = 0; slot < settler.bag.getContainerSize() && bag.size() < RealmMapMarkersPayload.MAX_BAG;
             slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (stack.isEmpty()) continue;
            bag.add(new RealmMapMarkersPayload.BagSlot(BuiltInRegistries.ITEM.getId(stack.getItem()),
                stack.getCount()));
        }
        return new RealmMapMarkersPayload.Focus(focus, SkillLevels.levelOf(settler),
            Math.round(settler.getHunger()), Math.round(settler.getEnergy()), bag);
    }

    private static int countCoins(ServerLevel level, Settlement settlement) {
        if (!level.isLoaded(settlement.center)
            || !(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
            || !settlement.id.equals(hearth.getSettlementId())) {
            return -1;
        }
        try {
            return CoinTreasury.availableCoins(CoinTreasury.open(level, settlement, hearth, null));
        } catch (RuntimeException failure) {
            return -1;
        }
    }
}

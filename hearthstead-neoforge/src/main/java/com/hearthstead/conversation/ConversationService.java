package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.ConversationGraph.Node;
import com.hearthstead.conversation.ConversationGraph.Option;
import com.hearthstead.conversation.ConversationGraph.Outcome;
import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.conversation.net.ConvBarterPayload;
import com.hearthstead.conversation.net.ConvClosePayload;
import com.hearthstead.conversation.net.ConvMarkersPayload;
import com.hearthstead.conversation.net.ConvStatePayload;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.revive.ReviveService;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.raid.RaidDirector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server authority for every conversation.
 *
 * <p><b>Binding.</b> Event content {@link #bind}s an entity to a graph with a
 * {@link SpeakerProfile}; the binding lives in the entity's persistent data,
 * so it survives a save. Right-click, or walking up (the encounter pull-in),
 * opens it.
 *
 * <p><b>Sessions.</b> One per NPC and one per player. Others near the NPC see
 * "X is talking to Y" and the NPC's gestures; the talker's choice decides and
 * is broadcast. Every reply is re-validated here: session owner, reach,
 * visibility (conditions), affordability, and costs are taken exactly once
 * before any outcome runs. Persuasion rolls happen only here.
 *
 * <p><b>Lang arguments.</b> Line and reply keys receive {@code %1$s} speaker
 * name, {@code %2$s} player name, {@code %3$s} settlement name, then
 * {@code %4$s...} the binding variables in alphabetical order of their keys.
 */
public final class ConversationService {
    public static final String BIND_TAG = "HearthsteadConversation";
    public static final double OPEN_REACH = 8.0D;
    public static final double KEEP_REACH = 12.0D;
    public static final double WATCH_RANGE = 32.0D;
    public static final double MARKER_RANGE = 48.0D;
    /** Intro cinematic window: damage inside it aborts to normal play. */
    public static final int INTRO_TICKS = 70;
    /** A player hurt (or hurting) within this many ticks is "in combat". */
    public static final int COMBAT_TICKS = 160;
    private static final String END_NODE = "__end";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    static final class Session {
        final int id = NEXT_ID.getAndIncrement();
        final UUID npcUuid;
        final int npcEntityId;
        /** The lead talker (the opener; passes on when the lead leaves a shared talk). */
        UUID playerId;
        String playerName;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        /** Participants, invitations, waiting and the one revision everyone answers (co-op). */
        SharedTalk talk;
        final Map<UUID, String> names = new LinkedHashMap<>();
        /** Who has the barter table open: only they may trade (items move for them alone). */
        @Nullable UUID barterPlayer;
        /** The last reply and who chose it, shown to the other participants ("Tobias chose: ..."). */
        @Nullable UUID lastChooser;
        @Nullable Component lastChoice;
        boolean unlockSent;
        final ConversationGraph graph;
        final SpeakerProfile profile;
        final Map<String, Integer> vars;
        @Nullable final UUID settlementId;
        final boolean encounter;
        final long opened;
        String node;
        long lastActive;
        final List<Component> replyLines = new ArrayList<>();
        int flourish;
        int flourishChance;
        boolean applying;
        @Nullable BarterStock barter;
        List<String> shownOptionIds = List.of();

        Session(Entity npc, ServerPlayer player, ConversationGraph graph, SpeakerProfile profile,
                Map<String, Integer> vars, @Nullable UUID settlementId, boolean encounter, long now) {
            this.npcUuid = npc.getUUID();
            this.npcEntityId = npc.getId();
            this.playerId = player.getUUID();
            this.playerName = player.getGameProfile().getName();
            this.dimension = player.serverLevel().dimension();
            // Revision of the last state sent; a reply or accept must echo it and consumes it.
            this.talk = SharedTalk.solo(playerId, now, 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24));
            this.names.put(playerId, playerName);
            this.graph = graph;
            this.profile = profile;
            this.vars = vars;
            this.settlementId = settlementId;
            this.encounter = encounter;
            this.opened = now;
            this.lastActive = now;
            this.node = graph.start();
        }
    }

    private static final Map<UUID, Session> BY_NPC = new HashMap<>();
    private static final Map<UUID, Session> BY_PLAYER = new HashMap<>();
    private static final Map<Integer, Session> BY_ID = new HashMap<>();
    /** npc|player -> binding revision already pulled in (walk away and back does not retrigger). */
    private static final Map<String, Integer> ENCOUNTERED = new HashMap<>();
    private static final java.util.Set<UUID> MARKED = new java.util.HashSet<>();

    /** Listeners for "this conversation ended" (e.g. the raid parley). */
    public interface CloseListener {
        void closed(ServerLevel level, UUID npcUuid, UUID playerId, String graphId);
    }

    private static final List<CloseListener> CLOSE_LISTENERS = new ArrayList<>();

    /** Listeners for "a partner joined a running shared conversation" (e.g. a per-player welcome gift). */
    public interface JoinListener {
        void joined(ServerLevel level, UUID npcUuid, ServerPlayer player, String graphId);
    }

    private static final List<JoinListener> JOIN_LISTENERS = new ArrayList<>();
    /** The reply id of "Continue alone" in the waiting state (never a graph option). */
    public static final String CONTINUE_ALONE = "__continue_alone";
    /** A co-op partner this close to the speaker joins the shared talk. */
    public static final double JOIN_REACH = KEEP_REACH;
    /** Test/QA: players treated as conversation-capable clients (GameTest mock players have no channel). */
    private static final java.util.Set<UUID> TEST_CLIENTS = new java.util.HashSet<>();
    private static int unlockTicks = SharedTalk.DEFAULT_UNLOCK_TICKS;
    private static int maxWaitTicks = SharedTalk.DEFAULT_MAX_WAIT_TICKS;

    private ConversationService() {
    }

    public static void onClose(CloseListener listener) {
        CLOSE_LISTENERS.add(listener);
    }

    public static void onJoin(JoinListener listener) {
        JOIN_LISTENERS.add(listener);
    }

    /** Test/QA: let a GameTest mock player count as a co-op partner (or stop it). */
    public static void markClientForTests(ServerPlayer player, boolean client) {
        if (client) TEST_CLIENTS.add(player.getUUID());
        else TEST_CLIENTS.remove(player.getUUID());
    }

    /** Test/QA: shorter waiting timings; negative values restore the defaults. */
    public static void shareTimingForTests(int unlock, int maxWait) {
        unlockTicks = unlock < 0 ? SharedTalk.DEFAULT_UNLOCK_TICKS : unlock;
        maxWaitTicks = maxWait < 0 ? SharedTalk.DEFAULT_MAX_WAIT_TICKS : maxWait;
    }

    // ------------------------------------------------------------ binding ---

    public static void bind(Entity npc, String graphId, SpeakerProfile profile) {
        bind(npc, graphId, profile, Map.of());
    }

    /**
     * Makes {@code npc} talkable with {@code graphId}. Re-binding bumps a
     * revision: the NPC "has something new to say" and may pull players in
     * again. Variables are read by costs ({@code Conversation.food("toll")})
     * and passed to lang keys.
     */
    public static void bind(Entity npc, String graphId, SpeakerProfile profile, Map<String, Integer> vars) {
        CompoundTag old = npc.getPersistentData().getCompound(BIND_TAG);
        CompoundTag tag = new CompoundTag();
        tag.putString("Graph", graphId);
        tag.put("Profile", profile.write());
        CompoundTag v = new CompoundTag();
        vars.forEach(v::putInt);
        tag.put("Vars", v);
        tag.putInt("Rev", old.getInt("Rev") + 1);
        npc.getPersistentData().put(BIND_TAG, tag);
    }

    /** Binds with an explicit settlement (else the one the NPC or player stands in). */
    public static void bind(Entity npc, String graphId, SpeakerProfile profile, Map<String, Integer> vars,
                            @Nullable UUID settlementId) {
        bind(npc, graphId, profile, vars);
        if (settlementId != null) npc.getPersistentData().getCompound(BIND_TAG).putUUID("Settlement", settlementId);
    }

    public static void unbind(Entity npc) {
        unbind(npc, null);
    }

    /** Removes the binding and ends any open talk with it, telling the talker and watchers {@code notice}. */
    public static void unbind(Entity npc, @Nullable Component notice) {
        closeAll(npc, notice);
        npc.getPersistentData().remove(BIND_TAG);
    }

    public static boolean isBound(Entity npc) {
        return npc != null && npc.getPersistentData().contains(BIND_TAG);
    }

    @Nullable
    public static String boundGraph(Entity npc) {
        return isBound(npc) ? npc.getPersistentData().getCompound(BIND_TAG).getString("Graph") : null;
    }

    public static boolean isTalking(Entity npcOrPlayer) {
        return npcOrPlayer != null
            && (BY_NPC.containsKey(npcOrPlayer.getUUID()) || BY_PLAYER.containsKey(npcOrPlayer.getUUID()));
    }

    @Nullable
    public static UUID talkerOf(Entity npc) {
        Session session = npc == null ? null : BY_NPC.get(npc.getUUID());
        return session == null ? null : session.playerId;
    }

    /** Everyone online in the talk with {@code npc} (lead first); empty when nobody talks to it. */
    public static List<ServerPlayer> participantsOf(Entity npc) {
        Session session = npc == null ? null : BY_NPC.get(npc.getUUID());
        if (session == null || !(npc.level() instanceof ServerLevel level)) return List.of();
        return online(level.getServer(), session);
    }

    /** True while the talk with {@code npc} only waits for a co-op partner (no reply is possible yet). */
    public static boolean isWaitingForPartner(Entity npc) {
        Session session = npc == null ? null : BY_NPC.get(npc.getUUID());
        return session != null && session.talk.waiting();
    }

    /** Test/QA: true while this player's talk waits for a partner. */
    public static boolean isWaiting(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        return session != null && session.talk.waiting();
    }

    private static List<ServerPlayer> online(MinecraftServer server, Session session) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : session.talk.participants()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) out.add(player);
        }
        return out;
    }

    private static Map<String, Integer> varsOf(CompoundTag bind) {
        Map<String, Integer> vars = new LinkedHashMap<>();
        CompoundTag v = bind.getCompound("Vars");
        for (String key : v.getAllKeys()) vars.put(key, v.getInt(key));
        return vars;
    }

    // --------------------------------------------------------------- open ---

    /** Opens the NPC's bound conversation for {@code player} (right-click path). */
    public static boolean openBound(ServerPlayer player, Entity npc, boolean encounter) {
        if (!isBound(npc)) return false;
        CompoundTag bind = npc.getPersistentData().getCompound(BIND_TAG);
        SpeakerProfile profile = SpeakerProfile.read(bind.getCompound("Profile"));
        if (profile == null) return false;
        UUID settlement = bind.hasUUID("Settlement") ? bind.getUUID("Settlement") : null;
        boolean opened = openInternal(player, npc, bind.getString("Graph"), profile, varsOf(bind), settlement, encounter,
            true);
        if (opened) ENCOUNTERED.put(key(npc, player), bind.getInt("Rev"));
        return opened;
    }

    public static boolean open(ServerPlayer player, Entity npc, String graphId, SpeakerProfile profile) {
        return openInternal(player, npc, graphId, profile, Map.of(), null, false, true);
    }

    public static boolean open(ServerPlayer player, Entity npc, String graphId, SpeakerProfile profile,
                               Map<String, Integer> vars) {
        return openInternal(player, npc, graphId, profile, vars, null, false, true);
    }

    private static boolean openInternal(ServerPlayer player, Entity npc, String graphId, SpeakerProfile profile,
                                        Map<String, Integer> vars, @Nullable UUID settlementHint, boolean encounter,
                                        boolean allowShare) {
        if (!ConversationConfig.enabled() || player instanceof FakePlayer || player.isSpectator()
            || !player.isAlive() || npc == null || !npc.isAlive() || npc.level() != player.level()) return false;
        if (player.distanceTo(npc) > OPEN_REACH + (encounter ? 8.0D : 0.0D)) return false;
        ConversationGraph graph = ConversationGraphs.get(graphId);
        if (graph == null) {
            Hearthstead.LOGGER.warn("No conversation graph '{}' for {}", graphId, npc);
            return false;
        }
        Session busy = BY_NPC.get(npc.getUUID());
        if (busy != null) {
            if (busy.talk.isParticipant(player.getUUID())) {
                sendState(player, busy);
                return true;
            }
            // An invited co-op partner who right-clicks (or walks up) joins the running talk.
            if (busy.talk.isInvited(player.getUUID()) && !BY_PLAYER.containsKey(player.getUUID())
                && player.distanceTo(npc) <= JOIN_REACH) {
                join(player.serverLevel(), busy, player, npc);
                return true;
            }
            player.displayClientMessage(Component.translatable("conversation.hearthstead.busy",
                busy.playerName, speakerName(profile, npc)), true);
            return false;
        }
        Session mine = BY_PLAYER.get(player.getUUID());
        if (mine != null) leaveSession(player.serverLevel(), mine, player.getUUID(), null);
        ServerLevel level = player.serverLevel();
        Settlement settlement = settlementFor(level, npc, player, settlementHint);
        Session session = new Session(npc, player, graph, profile, new TreeMap<>(vars),
            settlement == null ? null : settlement.id, encounter, level.getGameTime());
        // Co-op (owner, 27 Sep): with another member online the talk waits for them.
        List<ServerPlayer> partners = allowShare && ConversationConfig.shared() && !BARTER_ONLY.equals(graphId)
            ? partnersFor(level, settlement, player) : List.of();
        if (!partners.isEmpty()) {
            List<UUID> ids = new ArrayList<>();
            for (ServerPlayer partner : partners) ids.add(partner.getUUID());
            session.talk = new SharedTalk(session.playerId, ids, session.opened, session.talk.revision(),
                unlockTicks, maxWaitTicks);
        }
        BY_NPC.put(session.npcUuid, session);
        BY_PLAYER.put(session.playerId, session);
        BY_ID.put(session.id, session);
        RelationSavedData.get(player.server).met(session.settlementId, profile, session.opened);
        if (encounter && graph.encounter().introLine() != null) {
            session.replyLines.add(line(graph.encounter().introLine(), session, player, npc));
        }
        // Partners already beside the speaker join at once; the others get a town-chat nudge.
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer partner : partners) {
            if (canJoinNow(level, partner, npc)) near.add(partner);
            else nudge(level, session, partner, npc, settlement);
        }
        if (near.isEmpty()) sendState(player, session);
        else for (ServerPlayer partner : near) join(level, session, partner, npc);
        watchersNotice(level, npc, session, Component.translatable("conversation.hearthstead.watch",
            session.playerName, speakerName(profile, npc)));
        return true;
    }

    /**
     * Who may share a talk the player opens in {@code settlement}: online
     * members (they used its Banner) and anyone inside the town, who have the
     * mod's conversation screen, are alive, not spectating and not the opener.
     */
    private static List<ServerPlayer> partnersFor(ServerLevel level, @Nullable Settlement settlement, ServerPlayer opener) {
        if (settlement == null) return List.of();
        MinecraftServer server = level.getServer();
        java.util.LinkedHashSet<ServerPlayer> out = new java.util.LinkedHashSet<>();
        for (UUID id : settlement.members) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null) out.add(member);
        }
        if (settlement.center != null) {
            double reach = settlement.radius + RaidDirector.PLAYER_PRESENCE_MARGIN;
            for (ServerPlayer other : level.players()) {
                if (other.blockPosition().distSqr(settlement.center) <= reach * reach) out.add(other);
            }
        }
        List<ServerPlayer> partners = new ArrayList<>();
        for (ServerPlayer candidate : out) {
            if (candidate == opener || candidate.getUUID().equals(opener.getUUID())) continue;
            if (candidate instanceof FakePlayer || candidate.isSpectator() || !candidate.isAlive()) continue;
            if (!isConversationClient(candidate)) continue;
            // Already in a talk of their own: not waited for (two talks never wait on each other).
            if (BY_PLAYER.containsKey(candidate.getUUID())) continue;
            partners.add(candidate);
        }
        return partners;
    }

    private static boolean isConversationClient(ServerPlayer player) {
        if (TEST_CLIENTS.contains(player.getUUID())) return true;
        return player.connection != null && net.neoforged.neoforge.network.registration.NetworkRegistry
            .hasChannel(player.connection, ConvStatePayload.TYPE.id());
    }

    private static boolean canJoinNow(ServerLevel level, ServerPlayer partner, Entity npc) {
        return partner.serverLevel() == level && partner.isAlive() && !partner.isSpectator()
            && !BY_PLAYER.containsKey(partner.getUUID()) && partner.distanceTo(npc) <= JOIN_REACH
            && !ReviveService.isDowned(partner);
    }

    /** "[Town] Tobias is talking to Edda at Oakvale (120, 64, -30), come and join" to one partner. */
    private static void nudge(ServerLevel level, Session session, ServerPlayer partner, Entity npc,
                              @Nullable Settlement settlement) {
        String place = settlement == null ? "" : settlement.name;
        String where = npc.blockPosition().getX() + ", " + npc.blockPosition().getY() + ", " + npc.blockPosition().getZ();
        net.minecraft.network.chat.MutableComponent line = Component.empty()
            .append(Component.translatableWithFallback("hearthstead.story.chat.prefix", "[Town] ")
                .withStyle(ChatFormatting.GOLD))
            .append(Component.translatable("conversation.hearthstead.shared.nudge", session.playerName,
                speakerName(session.profile, npc), place, where).withStyle(ChatFormatting.YELLOW));
        partner.sendSystemMessage(line);
    }

    /** {@code player} joins the shared talk: same lines, same replies, live. */
    private static void join(ServerLevel level, Session session, ServerPlayer player, Entity npc) {
        if (!session.talk.join(player.getUUID())) return;
        Session other = BY_PLAYER.get(player.getUUID());
        if (other != null && other != session) leaveSession(level, other, player.getUUID(), null);
        BY_PLAYER.put(player.getUUID(), session);
        session.names.put(player.getUUID(), player.getGameProfile().getName());
        session.lastActive = level.getGameTime();
        if (isBound(npc)) ENCOUNTERED.put(key(npc, player), npc.getPersistentData().getCompound(BIND_TAG).getInt("Rev"));
        Component joined = Component.translatable("conversation.hearthstead.shared.joined",
            player.getGameProfile().getName());
        for (ServerPlayer other2 : online(level.getServer(), session)) {
            if (other2 != player) other2.displayClientMessage(joined, true);
        }
        sendStateAll(level, session);
        for (JoinListener listener : List.copyOf(JOIN_LISTENERS)) {
            try {
                listener.joined(level, session.npcUuid, player, session.graph.id());
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Conversation join listener failed", failure);
            }
        }
    }

    /**
     * Opens the barter table directly (no graph reply needed). The stock is
     * owned by the caller; it is mutated only on an accepted deal.
     */
    public static boolean openBarter(ServerPlayer player, Entity npc, SpeakerProfile profile, BarterStock stock) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session == null || !session.npcUuid.equals(npc.getUUID())) {
            ConversationGraph graph = ConversationGraphs.get(BARTER_ONLY);
            if (graph == null) {
                ConversationGraphs.register(Conversation.graph(BARTER_ONLY).noEncounter()
                    .node("start", n -> n.line("conversation.hearthstead.barter.greet")
                        .option("leave", o -> o.text("conversation.hearthstead.leave").end()))
                    .build());
            }
            // The trade table is one player's (items move for them alone): never shared.
            if (!openInternal(player, npc, BARTER_ONLY, profile, Map.of(), null, false, false)) return false;
            session = BY_PLAYER.get(player.getUUID());
            if (session == null) return false;
        }
        if (session.barter != null && session.barterPlayer != null && !session.barterPlayer.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("conversation.hearthstead.shared.wait_trade",
                session.names.getOrDefault(session.barterPlayer, "?")), true);
            return false;
        }
        session.barter = stock;
        session.barterPlayer = player.getUUID();
        sendBarter(player, session, 0);
        sendStateAll(player.serverLevel(), session);
        return true;
    }

    private static final String BARTER_ONLY = "hearthstead:barter_only";

    // -------------------------------------------------------------- close ---

    /** Ends every conversation with {@code npc} and tells watchers {@code notice}. */
    public static void closeAll(Entity npc, @Nullable Component notice) {
        if (npc == null || !(npc.level() instanceof ServerLevel level)) return;
        Session session = BY_NPC.get(npc.getUUID());
        if (session != null) close(level, session, notice, true);
    }

    /**
     * Takes {@code player} out of their conversation, if any. Talking alone
     * that ends it; in a shared talk the others carry on without them.
     */
    public static void closeFor(ServerPlayer player, @Nullable Component notice) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session != null) leaveSession(player.serverLevel(), session, player.getUUID(), notice);
    }

    /**
     * One participant leaves (Esc, logout, reach, another dimension, hurt).
     * The last one out closes the talk as before; otherwise the lead passes
     * on, a barter table they had open is dropped, and the rest are updated.
     */
    private static void leaveSession(ServerLevel level, Session session, UUID who, @Nullable Component notice) {
        if (!session.talk.isParticipant(who)) return;
        if (session.talk.participants().size() <= 1) {
            close(level, session, notice, false);
            return;
        }
        List<UUID> rest = session.talk.leave(who);
        BY_PLAYER.remove(who, session);
        String name = session.names.remove(who);
        MinecraftServer server = level.getServer();
        ServerPlayer leaver = server.getPlayerList().getPlayer(who);
        if (leaver != null) {
            com.hearthstead.network.PayloadSend.toPlayer(leaver, new ConvClosePayload(session.id,
                notice == null ? Component.empty() : notice));
        }
        if (who.equals(session.barterPlayer)) {
            session.barter = null;
            session.barterPlayer = null;
        }
        if (who.equals(session.playerId)) {
            session.playerId = rest.get(0);
            session.playerName = session.names.getOrDefault(session.playerId, "?");
        }
        Component left = Component.translatable("conversation.hearthstead.shared.left", name == null ? "?" : name);
        for (ServerPlayer other : online(server, session)) other.displayClientMessage(left, true);
        sendStateAll(level, session);
    }

    private static void close(ServerLevel level, Session session, @Nullable Component notice, boolean broadcast) {
        BY_NPC.remove(session.npcUuid, session);
        BY_ID.remove(session.id);
        for (UUID id : session.talk.participants()) {
            BY_PLAYER.remove(id, session);
            ServerPlayer participant = level.getServer().getPlayerList().getPlayer(id);
            if (participant != null && participant.connection != null && !(participant instanceof FakePlayer)) {
                com.hearthstead.network.PayloadSend.toPlayer(participant, new ConvClosePayload(session.id,
                    notice == null ? Component.empty() : notice));
            }
        }
        if (broadcast && notice != null) {
            Entity npc = level.getEntity(session.npcUuid);
            if (npc != null) watchersNotice(level, npc, session, notice);
        }
        for (CloseListener listener : List.copyOf(CLOSE_LISTENERS)) {
            try {
                listener.closed(level, session.npcUuid, session.playerId, session.graph.id());
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Conversation close listener failed", failure);
            }
        }
    }

    // ------------------------------------------------------------ actions ---

    public static void handle(ServerPlayer player, ConvActionPayload payload) {
        Session session = BY_ID.get(payload.session());
        // Server authority: only a participant of that very session may act in it.
        if (session == null || !session.talk.isParticipant(player.getUUID())) {
            com.hearthstead.network.PayloadSend.toPlayer(player, new ConvClosePayload(payload.session(), Component.empty()));
            return;
        }
        ServerLevel level = player.serverLevel();
        boolean sameDimension = level.dimension().equals(session.dimension);
        Entity npc = sameDimension ? level.getEntity(session.npcUuid) : null;
        if (npc == null || !npc.isAlive()) {
            if (!sameDimension) leaveSession(level, session, player.getUUID(), null);
            else close(level, session, null, false);
            return;
        }
        if (player.distanceTo(npc) > KEEP_REACH) {
            leaveSession(level, session, player.getUUID(), null);
            return;
        }
        session.lastActive = level.getGameTime();
        switch (payload.kind()) {
            case ConvActionPayload.LEAVE -> leaveSession(level, session, player.getUUID(), null);
            case ConvActionPayload.CHOOSE -> {
                if (session.talk.waiting()) {
                    // The only reply while waiting: "Continue alone" (unlocked, current revision).
                    if (CONTINUE_ALONE.equals(payload.optionId()) && !session.applying
                        && session.talk.continueAlone(player.getUUID(), payload.revision(), level.getGameTime())) {
                        sendStateAll(level, session);
                    } else {
                        sendState(player, session);
                    }
                    return;
                }
                if (session.barter != null && !player.getUUID().equals(session.barterPlayer)) {
                    player.displayClientMessage(Component.translatable("conversation.hearthstead.shared.wait_trade",
                        session.names.getOrDefault(session.barterPlayer, "?")), true);
                    sendState(player, session);
                    return;
                }
                if (!consume(session, player.getUUID(), payload.revision())) {
                    // Stale: somebody else's click already decided this state.
                    if (session.talk.shared() && session.lastChooser != null
                        && !session.lastChooser.equals(player.getUUID())) {
                        player.displayClientMessage(Component.translatable("conversation.hearthstead.shared.already",
                            session.names.getOrDefault(session.lastChooser, "?")), true);
                    }
                    sendState(player, session);
                    return;
                }
                choose(player, npc, session, payload.index(), payload.optionId());
            }
            case ConvActionPayload.BARTER_ACCEPT -> {
                // Items move only for the player who opened the table (no double spend).
                if (session.barter == null || !player.getUUID().equals(session.barterPlayer)) return;
                if (!consume(session, player.getUUID(), payload.revision())) {
                    sendBarter(player, session, 2);
                    return;
                }
                barterAccept(player, npc, session, payload);
            }
            case ConvActionPayload.BARTER_BACK -> {
                if (session.barter != null && !player.getUUID().equals(session.barterPlayer)) return;
                session.barter = null;
                session.barterPlayer = null;
                if (BARTER_ONLY.equals(session.graph.id())) close(level, session, null, false);
                else sendStateAll(level, session);
            }
            default -> { }
        }
    }

    /**
     * One answer per shown state: the echoed revision must be the current one,
     * and it is used up here, before any cost, roll or item moves. Every state
     * or table sent afterwards carries the next revision. In a shared talk the
     * first participant to answer wins; the others' echo is then stale.
     */
    private static boolean consume(Session session, UUID who, int revision) {
        if (session.applying) return false;
        return session.talk.click(who, revision);
    }

    /** Test/QA: the revision a player must echo to answer, or -1. */
    public static int revisionOf(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        return session == null ? -1 : session.talk.revision();
    }

    /** Test hook: press "Continue alone" as if the player clicked it. */
    public static boolean continueAloneForTest(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session == null || !session.talk.waiting()) return false;
        handle(player, ConvActionPayload.choose(session.id, session.talk.revision(), 0, CONTINUE_ALONE));
        return !session.talk.waiting();
    }

    /** Test/QA: the ids of the participants of a player's talk (lead first), or empty. */
    public static List<UUID> participantIdsOf(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        return session == null ? List.of() : session.talk.participants();
    }

    /** Test hook: choose a reply by id as if the player clicked it. */
    public static boolean chooseForTest(ServerPlayer player, String optionId) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session == null) return false;
        Entity npc = player.serverLevel().getEntity(session.npcUuid);
        if (npc == null) return false;
        List<Option> visible = visibleOptions(session, player, npc);
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).id().equals(optionId)) {
                handle(player, ConvActionPayload.choose(session.id, session.talk.revision(), i, optionId));
                return true;
            }
        }
        return false;
    }

    /** Test/QA: the lang keys of the replies a player can see right now. */
    public static List<String> visibleOptionTextsForTest(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session == null) return List.of();
        Entity npc = player.serverLevel().getEntity(session.npcUuid);
        if (npc == null) return List.of();
        List<String> keys = new ArrayList<>();
        for (Option option : visibleOptions(session, player, npc)) keys.add(option.text());
        return keys;
    }

    /** Test/QA: the open session id of a player, or -1. */
    public static int sessionIdOf(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        return session == null ? -1 : session.id;
    }

    /** Test/QA: current node of a player's conversation, or null. */
    @Nullable
    public static String nodeOf(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        return session == null ? null : session.node;
    }

    private static void choose(ServerPlayer player, Entity npc, Session session, int index, String optionId) {
        if (session.applying) return;
        ServerLevel level = player.serverLevel();
        List<Option> visible = visibleOptions(session, player, npc);
        if (index < 0 || index >= visible.size() || !visible.get(index).id().equals(optionId)) {
            // The revision was used up: everyone gets the fresh one.
            sendStateAll(level, session);
            return;
        }
        Option option = visible.get(index);
        Settlement settlement = session.settlementId == null ? null : SettlementManager.byId(level, session.settlementId);
        session.applying = true;
        try {
            if (END_NODE.equals(session.node)) {
                // The last "leave": both panels close together.
                close(level, session, null, false);
                return;
            }
            // 1. Pay, exactly once, before anything else can happen (the chooser pays).
            List<CostPayer.Resolved> costs = CostPayer.resolve(option.costs(), session.vars);
            if (!costs.isEmpty() && !CostPayer.pay(CostPayer.view(player, settlement), costs)) {
                player.displayClientMessage(Component.translatable("conversation.hearthstead.cannot_afford")
                    .withStyle(ChatFormatting.RED), true);
                sendStateAll(level, session);
                return;
            }
            String chooserName = player.getGameProfile().getName();
            session.lastChooser = player.getUUID();
            session.lastChoice = optionText(option, session, player, npc);
            // 2. Persuasion roll (server only).
            boolean succeeded = true;
            session.flourish = 0;
            if (option.check() != null) {
                int chance = chanceFor(session, player, option, settlement);
                succeeded = Persuasion.succeeds(chance, level.getRandom().nextInt(100));
                session.flourish = succeeded ? 1 : 2;
                session.flourishChance = chance;
            }
            Outcome outcome = succeeded ? option.outcome()
                : option.failure() != null ? option.failure() : Outcome.END;
            // 3. Relation, reputation, memory.
            if (outcome.relation() != 0 || outcome.reputation() != 0 || outcome.memory() != null) {
                RelationSavedData.get(player.server).change(session.settlementId, session.profile,
                    outcome.relation(), outcome.reputation(), outcome.memory(), level.getGameTime());
            }
            // 4. Content actions: once, for the chooser; ctx.participants() reaches everyone in the talk.
            ConversationContext ctx = new ConversationContext(player, npc, settlement, session.graph.id(),
                option.id(), session.vars, session.profile, succeeded, online(level.getServer(), session));
            for (String action : outcome.actions()) ConversationActions.run(action, ctx);
            if (!BY_ID.containsKey(session.id)) return; // an action closed us (closeAll/unbind)
            // 5. Barter table: the chooser's alone; the others see "X is trading".
            if (option.barterStock() != null && succeeded) {
                BarterStock stock = ListBarterStock.named(option.barterStock(), ctx);
                if (stock != null) {
                    session.barter = stock;
                    session.barterPlayer = player.getUUID();
                    sendBarter(player, session, 0);
                    sendStateAll(level, session);
                    return;
                }
            }
            // 6. Where next.
            session.replyLines.clear();
            if (outcome.reply() != null) session.replyLines.add(line(outcome.reply(), session, player, npc));
            Component notice = ctx.notice() != null ? ctx.notice()
                : Component.translatable("conversation.hearthstead.chose", chooserName,
                    optionText(option, session, player, npc));
            if (ctx.closeRequested() || outcome.next() == null) {
                if (session.replyLines.isEmpty() && session.flourish == 0) {
                    close(level, session, notice, true);
                } else {
                    session.node = END_NODE;
                    sendStateAll(level, session);
                    watchersNotice(level, npc, session, notice);
                }
                return;
            }
            session.node = outcome.next();
            sendStateAll(level, session);
        } finally {
            session.applying = false;
        }
    }

    static int chanceFor(Session session, ServerPlayer player, Option option, @Nullable Settlement settlement) {
        RelationSavedData data = RelationSavedData.get(player.server);
        int relation = data.relation(session.settlementId, session.profile.identity());
        int reputation = data.reputation(session.settlementId, session.profile.kind());
        int renown = settlement == null ? 0 : Math.min(5, settlement.population() / 4);
        int skill = Persuasion.skill(option.check().skill(), player.experienceLevel, player.getArmorValue(), renown);
        // Presence: the settlement's best speaker (mayor, trader, innkeeper or
        // guard) adds 0..10 points on top, still inside 5..95 (plan/ATTRIBUTES.md).
        int speaker = com.hearthstead.entity.AttributeRuntime.persuasionPoints(player.serverLevel(), settlement)
            // A WELCOMING member: +3 with visitors (never raiders, brutes or rival lords).
            + com.hearthstead.entity.AttributeRuntime.welcomingPoints(player.serverLevel(), settlement,
                session.profile.kind())
            // The real town backs (or undercuts) the argument: threats need defenders (survival QA).
            + TownFactsLive.of(settlement).bonus(option.check().skill());
        return Math.max(Persuasion.MIN_CHANCE, Math.min(Persuasion.MAX_CHANCE,
            Persuasion.chance(option.check().base(), relation, reputation, skill) + speaker));
    }

    private static List<Option> visibleOptions(Session session, ServerPlayer player, Entity npc) {
        if (END_NODE.equals(session.node)) {
            return List.of(new Option("leave", "conversation.hearthstead.leave", null, List.of(), null,
                Outcome.END, null, null));
        }
        Node node = session.graph.node(session.node);
        if (node == null) return List.of();
        ServerLevel level = player.serverLevel();
        Settlement settlement = session.settlementId == null ? null : SettlementManager.byId(level, session.settlementId);
        List<Option> out = new ArrayList<>();
        for (Option option : node.options()) {
            if (option.condition() != null) {
                ConversationContext ctx = new ConversationContext(player, npc, settlement, session.graph.id(),
                    option.id(), session.vars, session.profile, true);
                if (!ConversationActions.test(option.condition(), ctx)) continue;
            }
            out.add(option);
        }
        return out;
    }

    // ------------------------------------------------------------- barter ---

    private static void barterAccept(ServerPlayer player, Entity npc, Session session, ConvActionPayload payload) {
        BarterStock stock = session.barter;
        if (stock == null || session.applying) return;
        session.applying = true;
        try {
            int relation = RelationSavedData.get(player.server).relation(session.settlementId, session.profile.identity());
            BarterDeal deal = BarterDeal.build(player.getInventory(), stock, payload.give(), payload.take());
            if (deal == null || !BarterMath.acceptable(deal.givenValue(), deal.takenValue(), relation)) {
                sendBarter(player, session, 2);
                return;
            }
            if (!deal.execute(player, stock)) {
                sendBarter(player, session, 2);
                return;
            }
            int goodwill = BarterMath.goodwill(deal.givenValue(), deal.takenValue(), relation);
            RelationSavedData.get(player.server).change(session.settlementId, session.profile, goodwill + 1,
                goodwill > 0 ? 1 : 0, null, player.serverLevel().getGameTime());
            watchersNotice(player.serverLevel(), npc, session, Component.translatable("conversation.hearthstead.barter.done",
                player.getGameProfile().getName(), speakerName(session.profile, npc)));
            sendBarter(player, session, 1);
        } finally {
            session.applying = false;
        }
    }

    /** Copper value of one item for this stock (stock override, else the shared table). */
    public static int unitValue(@Nullable BarterStock stock, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stock != null) {
            int custom = stock.unitValue(stack);
            if (custom >= 0) return custom;
        }
        var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        return BarterMath.unitValue(new BarterMath.ItemFacts(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getRarity().ordinal(), food == null ? 0 : food.nutrition(), stack.isEnchanted(),
            stack.getDamageValue(), stack.getMaxDamage(),
            stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())));
    }

    /**
     * Copper value of one of the player's own items (what they give): the
     * stock's {@link BarterStock#playerUnitValue} if it sets one, else the
     * shared table, never the partner's retail price.
     */
    public static int playerUnitValue(@Nullable BarterStock stock, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stock != null) {
            int custom = stock.playerUnitValue(stack);
            if (custom >= 0) return custom;
        }
        return unitValue(null, stack);
    }

    private static void sendBarter(ServerPlayer player, Session session, int result) {
        BarterStock stock = session.barter;
        if (stock == null) return;
        int relation = RelationSavedData.get(player.server).relation(session.settlementId, session.profile.identity());
        List<ItemStack> theirs = stock.items();
        if (theirs.size() > ConvBarterPayload.MAX_THEIRS) theirs = theirs.subList(0, ConvBarterPayload.MAX_THEIRS);
        List<Integer> theirValues = new ArrayList<>();
        for (ItemStack stack : theirs) theirValues.add(unitValue(stock, stack));
        List<ItemStack> mine = new ArrayList<>();
        List<Integer> mineValues = new ArrayList<>();
        for (int slot = 0; slot < ConvBarterPayload.MINE_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot).copy();
            mine.add(stack);
            mineValues.add(playerUnitValue(stock, stack));
        }
        Entity npc = player.serverLevel().getEntity(session.npcUuid);
        com.hearthstead.network.PayloadSend.toPlayer(player, new ConvBarterPayload(session.id, session.npcEntityId,
            speakerName(session.profile, npc), relation, Relations.askPercent(relation), theirs, theirValues,
            mine, mineValues, result, session.talk.revision()));
    }

    // -------------------------------------------------------------- state ---

    /**
     * The same talk to every participant, each with their own reply list
     * (conditions and costs are per player). The trading participant keeps
     * their barter table; a persuasion flourish reaches everyone.
     */
    private static void sendStateAll(ServerLevel level, Session session) {
        int flourish = session.flourish;
        for (ServerPlayer participant : online(level.getServer(), session)) {
            if (session.barter != null && participant.getUUID().equals(session.barterPlayer)) continue;
            session.flourish = flourish;
            sendState(participant, session);
        }
        session.flourish = 0;
    }

    private static void sendState(ServerPlayer player, Session session) {
        Entity npc = player.serverLevel().getEntity(session.npcUuid);
        if (npc == null) return;
        if (session.talk.waiting()) {
            sendWaiting(player, session, npc);
            return;
        }
        ServerLevel level = player.serverLevel();
        Settlement settlement = session.settlementId == null ? null : SettlementManager.byId(level, session.settlementId);
        RelationSavedData data = RelationSavedData.get(player.server);
        int relation = data.relation(session.settlementId, session.profile.identity());
        RelationSavedData.Person person = data.person(session.settlementId, session.profile.identity());
        Component memory = Component.empty();
        if (person != null && !person.memory.isBlank()) {
            memory = Component.translatable("conversation.hearthstead.card.remembers", Component.translatable(person.memory));
        } else if (person != null && person.met > 1) {
            memory = Component.translatable("conversation.hearthstead.card.met", person.met - 1);
        }
        Component record = Component.empty();
        if (session.vars.containsKey("victories")) {
            record = Component.translatable("conversation.hearthstead.card.record",
                session.vars.get("victories"), session.vars.getOrDefault("defeats", 0));
        }
        List<Component> lines = new ArrayList<>();
        // Co-op: the others see who decided ("Tobias chose: ...").
        if (session.lastChooser != null && session.lastChoice != null && !session.lastChooser.equals(player.getUUID())) {
            lines.add(Component.translatable("conversation.hearthstead.shared.chose",
                session.names.getOrDefault(session.lastChooser, "?"), session.lastChoice)
                .withStyle(ChatFormatting.GOLD));
        }
        if (session.barter != null && session.barterPlayer != null && !session.barterPlayer.equals(player.getUUID())) {
            // Someone else has the trade table open: watch; the talk resumes when they are done.
            lines.add(Component.translatable("conversation.hearthstead.shared.trading",
                session.names.getOrDefault(session.barterPlayer, "?"), speakerName(session.profile, npc)));
            com.hearthstead.network.PayloadSend.toPlayer(player, new ConvStatePayload(session.id, session.npcEntityId,
                speakerName(session.profile, npc),
                session.profile.titleKey().isBlank() ? Component.empty() : Component.translatable(session.profile.titleKey()),
                relation, memory, record, lines, List.of(), 0, 0, 0,
                "low".equals(session.graph.encounter().style()) ? 1 : 0, session.talk.revision()));
            return;
        }
        lines.addAll(session.replyLines);
        if (!END_NODE.equals(session.node)) {
            Node node = session.graph.node(session.node);
            if (node != null) for (String key : node.lines()) lines.add(line(key, session, player, npc));
        }
        List<ConvStatePayload.OptionView> views = new ArrayList<>();
        var view = CostPayer.view(player, settlement);
        List<Option> visible = visibleOptions(session, player, npc);
        for (Option option : visible) {
            List<CostPayer.Resolved> costs = CostPayer.resolve(option.costs(), session.vars);
            List<ConvStatePayload.CostView> costViews = new ArrayList<>();
            for (CostPayer.Resolved cost : costs) {
                costViews.add(new ConvStatePayload.CostView(cost.icon(), cost.amount(),
                    Math.min(cost.amount() * 10, CostPayer.available(view, cost))));
            }
            boolean affordable = CostPayer.canPay(view, costs);
            Component reason = affordable ? Component.empty()
                : Component.translatable("conversation.hearthstead.need_more");
            int chance = option.check() == null ? -1 : chanceFor(session, player, option, settlement);
            views.add(new ConvStatePayload.OptionView(option.id(), optionText(option, session, player, npc), chance,
                costViews, affordable, reason, option.barterStock() != null));
        }
        List<String> shown = new ArrayList<>();
        for (Option option : visible) shown.add(option.id());
        session.shownOptionIds = shown;
        boolean intro = session.encounter && level.getGameTime() - session.opened < 5
            && player.getUUID().equals(session.playerId);
        com.hearthstead.network.PayloadSend.toPlayer(player, new ConvStatePayload(session.id, session.npcEntityId,
            speakerName(session.profile, npc),
            session.profile.titleKey().isBlank() ? Component.empty() : Component.translatable(session.profile.titleKey()),
            relation, memory, record, lines, views, session.flourish, session.flourishChance,
            intro ? 1 : 0, "low".equals(session.graph.encounter().style()) ? 1 : 0, session.talk.revision()));
        session.flourish = 0;
    }

    /**
     * The waiting panel: "Waiting for Kari (1/2)", where they were told to
     * come, and one framed reply, "Continue alone", locked for the first
     * seconds (a far-away partner never blocks play).
     */
    private static void sendWaiting(ServerPlayer player, Session session, Entity npc) {
        long now = player.serverLevel().getGameTime();
        List<String> waitingFor = new ArrayList<>();
        MinecraftServer server = player.server;
        for (UUID id : session.talk.invited()) {
            ServerPlayer partner = server.getPlayerList().getPlayer(id);
            if (partner != null) waitingFor.add(partner.getGameProfile().getName());
        }
        String names = waitingFor.isEmpty() ? "?" : String.join(", ", waitingFor);
        List<Component> lines = new ArrayList<>(session.replyLines);
        lines.add(Component.translatable("conversation.hearthstead.shared.waiting", names,
            session.talk.participants().size(), session.talk.total()).withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("conversation.hearthstead.shared.waiting_hint", (int) JOIN_REACH));
        boolean unlocked = session.talk.canContinueAlone(now);
        Component reason = unlocked ? Component.empty()
            : Component.translatable("conversation.hearthstead.shared.continue_locked",
                (int) Math.ceil(session.talk.unlockIn(now) / 20.0D));
        List<ConvStatePayload.OptionView> views = List.of(new ConvStatePayload.OptionView(CONTINUE_ALONE,
            Component.translatable("conversation.hearthstead.shared.continue"), -1, List.of(), unlocked, reason, false));
        boolean intro = session.encounter && now - session.opened < 5;
        com.hearthstead.network.PayloadSend.toPlayer(player, new ConvStatePayload(session.id, session.npcEntityId,
            speakerName(session.profile, npc),
            session.profile.titleKey().isBlank() ? Component.empty() : Component.translatable(session.profile.titleKey()),
            RelationSavedData.get(player.server).relation(session.settlementId, session.profile.identity()),
            Component.empty(), Component.empty(), lines, views, 0, 0, intro ? 1 : 0,
            "low".equals(session.graph.encounter().style()) ? 1 : 0, session.talk.revision()));
    }

    private static Component speakerName(SpeakerProfile profile, @Nullable Entity npc) {
        if (!profile.name().isBlank()) return Component.literal(profile.name());
        return npc == null ? Component.literal("?") : npc.getDisplayName();
    }

    private static Object[] args(Session session, ServerPlayer player, @Nullable Entity npc) {
        List<Object> args = new ArrayList<>();
        args.add(speakerName(session.profile, npc));
        args.add(session.playerName);
        Settlement settlement = session.settlementId == null ? null
            : SettlementManager.byId(player.serverLevel(), session.settlementId);
        args.add(settlement == null ? "" : settlement.name);
        new TreeMap<>(session.vars).values().forEach(args::add);
        return args.toArray();
    }

    private static Component line(String key, Session session, ServerPlayer player, @Nullable Entity npc) {
        return Component.translatable(key, args(session, player, npc));
    }

    private static Component optionText(Option option, Session session, ServerPlayer player, @Nullable Entity npc) {
        return Component.translatable(option.text(), args(session, player, npc));
    }

    // ------------------------------------------------------------- ticking ---

    public static void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        // Sessions: reach, life, timeout; NPC faces the talker and stands still.
        for (Session session : List.copyOf(BY_ID.values())) {
            if (!BY_ID.containsKey(session.id)) continue;
            ServerLevel level = server.getLevel(session.dimension);
            Entity npc = level == null ? null : level.getEntity(session.npcUuid);
            if (level == null || npc == null || !npc.isAlive()
                || level.getGameTime() - session.lastActive > ConversationConfig.talkTimeoutTicks()
                || !ConversationConfig.enabled()) {
                if (level != null) close(level, session, null, false);
                else drop(session);
                continue;
            }
            // Each participant: offline, dead, another dimension or out of reach leaves (the rest carry on).
            double keep = KEEP_REACH + (session.encounter ? 6.0D : 0.0D);
            for (UUID id : session.talk.participants()) {
                ServerPlayer participant = server.getPlayerList().getPlayer(id);
                if (participant == null || !participant.isAlive() || participant.serverLevel() != level
                    || participant.distanceTo(npc) > keep) {
                    if (participant == null && session.talk.participants().size() <= 1) drop(session);
                    else leaveSession(level, session, id, null);
                }
            }
            if (!BY_ID.containsKey(session.id)) continue;
            if (session.talk.shared()) tickShared(level, session, npc);
            if (!BY_ID.containsKey(session.id)) continue;
            ServerPlayer lead = server.getPlayerList().getPlayer(session.playerId);
            if (lead != null && npc instanceof Mob mob) {
                mob.getNavigation().stop();
                mob.getLookControl().setLookAt(lead, 30.0F, 30.0F);
            }
        }
        if (!ConversationConfig.enabled()) return;
        if (tick % 10 == 0 && ConversationConfig.encounters()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Only clients that can show the talk are pulled in (not mock/fake players, proxies or vanilla clients).
                if (player.connection != null && net.neoforged.neoforge.network.registration.NetworkRegistry
                    .hasChannel(player.connection, ConvStatePayload.TYPE.id())) tryEncounter(player);
            }
        }
        if (tick % 20 == 5) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) sendMarkers(player);
        }
    }

    /** Forgets a session whose players are all gone (nobody to tell). */
    private static void drop(Session session) {
        BY_NPC.remove(session.npcUuid, session);
        for (UUID id : session.talk.participants()) BY_PLAYER.remove(id, session);
        BY_ID.remove(session.id);
    }

    /**
     * Co-op upkeep: invited partners who walk within {@link #JOIN_REACH} join;
     * a partner who logged out is no longer waited for; "Continue alone"
     * unlocks after its delay and the wait ends by itself after its limit.
     * Waiting never counts toward the idle timeout.
     */
    private static void tickShared(ServerLevel level, Session session, Entity npc) {
        MinecraftServer server = level.getServer();
        for (UUID id : session.talk.invited()) {
            ServerPlayer partner = server.getPlayerList().getPlayer(id);
            if (partner == null) {
                if (session.talk.uninvite(id)) sendStateAll(level, session);
                continue;
            }
            if (canJoinNow(level, partner, npc) && !inCombat(partner)) join(level, session, partner, npc);
            if (!BY_ID.containsKey(session.id)) return;
        }
        if (!session.talk.waiting()) return;
        long now = level.getGameTime();
        session.lastActive = now;
        if (session.talk.expireWait(now)) {
            sendStateAll(level, session);
            return;
        }
        if (!session.unlockSent && session.talk.canContinueAlone(now)) {
            session.unlockSent = true;
            sendStateAll(level, session);
        }
    }

    /** Walk-up pull-in: nearest bound NPC in its graph's radius with clear sight. */
    public static void tryEncounter(ServerPlayer player) {
        if (player instanceof FakePlayer || player.isSpectator() || !player.isAlive()
            || BY_PLAYER.containsKey(player.getUUID()) || inCombat(player) || ReviveService.isDowned(player)) return;
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity npc : player.serverLevel().getEntities((Entity) null, player.getBoundingBox().inflate(16.0D),
            e -> e != player && e.isAlive() && isBound(e))) {
            if (BY_NPC.containsKey(npc.getUUID())) continue;
            CompoundTag bind = npc.getPersistentData().getCompound(BIND_TAG);
            ConversationGraph graph = ConversationGraphs.get(bind.getString("Graph"));
            if (graph == null || !graph.encounter().approach()) continue;
            Integer seen = ENCOUNTERED.get(key(npc, player));
            if (seen != null && seen >= bind.getInt("Rev")) continue;
            double distance = player.distanceTo(npc);
            if (distance > graph.encounter().radius() || distance >= bestDistance) continue;
            if (npc instanceof LivingEntity living && living.getLastHurtByMob() != null
                && living.tickCount - living.getLastHurtByMobTimestamp() < COMBAT_TICKS) continue;
            if (!clearSight(player, npc)) continue;
            best = npc;
            bestDistance = distance;
        }
        if (best != null) openBound(player, best, true);
    }

    public static boolean inCombat(ServerPlayer player) {
        int now = player.tickCount;
        return (player.getLastHurtByMob() != null && now - player.getLastHurtByMobTimestamp() < COMBAT_TICKS)
            || (player.getLastHurtMob() != null && now - player.getLastHurtMobTimestamp() < COMBAT_TICKS);
    }

    /**
     * Owner rule: both the eye ray and the torso ray from the player to the
     * NPC must be free of collision blocks (walls, floors, closed doors,
     * glass -- conservative).
     */
    public static boolean clearSight(Entity from, Entity to) {
        Vec3 eye = from.getEyePosition();
        Vec3 torso = from.position().add(0.0D, from.getBbHeight() * 0.55D, 0.0D);
        Vec3 targetEye = to.getEyePosition();
        Vec3 targetTorso = to.position().add(0.0D, to.getBbHeight() * 0.55D, 0.0D);
        return clear(from, eye, targetEye) && clear(from, torso, targetTorso);
    }

    private static boolean clear(Entity from, Vec3 a, Vec3 b) {
        HitResult hit = from.level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void sendMarkers(ServerPlayer player) {
        if (player instanceof FakePlayer || player.connection == null) return;
        List<ConvMarkersPayload.Marker> markers = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(MARKER_RANGE);
        for (Entity npc : player.serverLevel().getEntities((Entity) null, box, e -> e.isAlive() && isBound(e))) {
            if (markers.size() >= ConvMarkersPayload.MAX) break;
            Session session = BY_NPC.get(npc.getUUID());
            if (session != null) {
                markers.add(new ConvMarkersPayload.Marker(npc.getId(), ConvMarkersPayload.TALKING,
                    session.talk.isParticipant(player.getUUID()) ? "" : session.playerName));
            } else {
                // Talked to already and nothing new to say: no badge (it returns when the NPC is re-bound).
                CompoundTag bind = npc.getPersistentData().getCompound(BIND_TAG);
                Integer seen = ENCOUNTERED.get(key(npc, player));
                if (seen != null && seen >= bind.getInt("Rev")) continue;
                markers.add(new ConvMarkersPayload.Marker(npc.getId(), markerKind(bind), ""));
            }
        }
        // Quiet when nothing is near: one empty packet clears the client, then silence.
        if (markers.isEmpty() && !MARKED.remove(player.getUUID())) return;
        if (!markers.isEmpty()) MARKED.add(player.getUUID());
        com.hearthstead.network.PayloadSend.toPlayer(player, new ConvMarkersPayload(markers));
    }

    /** Which badge a bound NPC shows: crimson parley, trade coin, or the talk "!". */
    private static int markerKind(CompoundTag bind) {
        String graphId = bind.getString("Graph");
        if (graphId.contains("parley")) return ConvMarkersPayload.PARLEY;
        String kind = bind.getCompound("Profile").getString("Kind");
        if ("peddler".equals(kind) || "merchant".equals(kind) || "caravan".equals(kind)) return ConvMarkersPayload.TRADE;
        if ("quest".equals(kind)) return ConvMarkersPayload.QUEST;
        return ConvMarkersPayload.AVAILABLE;
    }

    /**
     * Damage to the speaker ends the talk (and aborts an intro); damage to a
     * talker takes them out of it (alone, that ends it as before).
     */
    public static void onHurt(LivingEntity victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        Session session = BY_PLAYER.get(victim.getUUID());
        if (session != null) {
            if (!session.applying) leaveSession(level, session, victim.getUUID(), null);
            return;
        }
        session = BY_NPC.get(victim.getUUID());
        if (session == null || session.applying) return;
        close(level, session, null, false);
    }

    public static void onRightClick(ServerPlayer player, Entity target) {
        openBound(player, target, false);
    }

    public static void clearAll() {
        TEST_CLIENTS.clear();
        BY_NPC.clear();
        BY_PLAYER.clear();
        BY_ID.clear();
        ENCOUNTERED.clear();
        MARKED.clear();
    }

    private static void watchersNotice(ServerLevel level, Entity npc, Session session, Component notice) {
        for (ServerPlayer other : level.players()) {
            if (session.talk.isParticipant(other.getUUID()) || other instanceof FakePlayer) continue;
            if (other.distanceToSqr(npc) <= WATCH_RANGE * WATCH_RANGE) other.displayClientMessage(notice, true);
        }
    }

    private static String key(Entity npc, ServerPlayer player) {
        return npc.getUUID() + "|" + player.getUUID();
    }

    @Nullable
    private static Settlement settlementFor(ServerLevel level, Entity npc, ServerPlayer player, @Nullable UUID hint) {
        if (hint != null) {
            Settlement s = SettlementManager.byId(level, hint);
            if (s != null) return s;
        }
        if (npc instanceof RaiderEntity raider && raider.settlementId() != null) {
            Settlement s = SettlementManager.byId(level, raider.settlementId());
            if (s != null) return s;
        }
        Settlement s = SettlementManager.at(level, npc.blockPosition());
        if (s == null) s = SettlementManager.at(level, player.blockPosition());
        if (s != null) return s;
        Settlement nearest = null;
        double best = Double.MAX_VALUE;
        for (Settlement candidate : SettlementManager.data(level).settlements.values()) {
            double reach = candidate.radius + RaidDirector.PLAYER_PRESENCE_MARGIN;
            double d = candidate.center.distSqr(npc.blockPosition());
            if (d <= reach * reach && d < best) {
                best = d;
                nearest = candidate;
            }
        }
        return nearest;
    }
}

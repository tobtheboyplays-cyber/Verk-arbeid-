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
        final UUID playerId;
        final String playerName;
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
        /** Revision of the last state sent; a reply or accept must echo it and consumes it. */
        int revision = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24);

        Session(Entity npc, ServerPlayer player, ConversationGraph graph, SpeakerProfile profile,
                Map<String, Integer> vars, @Nullable UUID settlementId, boolean encounter, long now) {
            this.npcUuid = npc.getUUID();
            this.npcEntityId = npc.getId();
            this.playerId = player.getUUID();
            this.playerName = player.getGameProfile().getName();
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

    private ConversationService() {
    }

    public static void onClose(CloseListener listener) {
        CLOSE_LISTENERS.add(listener);
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
        boolean opened = openInternal(player, npc, bind.getString("Graph"), profile, varsOf(bind), settlement, encounter);
        if (opened) ENCOUNTERED.put(key(npc, player), bind.getInt("Rev"));
        return opened;
    }

    public static boolean open(ServerPlayer player, Entity npc, String graphId, SpeakerProfile profile) {
        return openInternal(player, npc, graphId, profile, Map.of(), null, false);
    }

    public static boolean open(ServerPlayer player, Entity npc, String graphId, SpeakerProfile profile,
                               Map<String, Integer> vars) {
        return openInternal(player, npc, graphId, profile, vars, null, false);
    }

    private static boolean openInternal(ServerPlayer player, Entity npc, String graphId, SpeakerProfile profile,
                                        Map<String, Integer> vars, @Nullable UUID settlementHint, boolean encounter) {
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
            if (busy.playerId.equals(player.getUUID())) {
                sendState(player, busy);
                return true;
            }
            player.displayClientMessage(Component.translatable("conversation.hearthstead.busy",
                busy.playerName, speakerName(profile, npc)), true);
            return false;
        }
        Session mine = BY_PLAYER.get(player.getUUID());
        if (mine != null) close(player.serverLevel(), mine, null, false);
        Settlement settlement = settlementFor(player.serverLevel(), npc, player, settlementHint);
        Session session = new Session(npc, player, graph, profile, new TreeMap<>(vars),
            settlement == null ? null : settlement.id, encounter, player.serverLevel().getGameTime());
        BY_NPC.put(session.npcUuid, session);
        BY_PLAYER.put(session.playerId, session);
        BY_ID.put(session.id, session);
        RelationSavedData.get(player.server).met(session.settlementId, profile, session.opened);
        if (encounter && graph.encounter().introLine() != null) {
            session.replyLines.add(line(graph.encounter().introLine(), session, player, npc));
        }
        sendState(player, session);
        watchersNotice(player.serverLevel(), npc, player, Component.translatable("conversation.hearthstead.watch",
            session.playerName, speakerName(profile, npc)));
        return true;
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
            if (!openInternal(player, npc, BARTER_ONLY, profile, Map.of(), null, false)) return false;
            session = BY_PLAYER.get(player.getUUID());
            if (session == null) return false;
        }
        session.barter = stock;
        sendBarter(player, session, 0);
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

    /** Ends {@code player}'s conversation, if any. */
    public static void closeFor(ServerPlayer player, @Nullable Component notice) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session != null) close(player.serverLevel(), session, notice, false);
    }

    private static void close(ServerLevel level, Session session, @Nullable Component notice, boolean broadcast) {
        BY_NPC.remove(session.npcUuid, session);
        BY_PLAYER.remove(session.playerId, session);
        BY_ID.remove(session.id);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(session.playerId);
        if (player != null && player.connection != null && !(player instanceof FakePlayer)) {
            com.hearthstead.network.PayloadSend.toPlayer(player, new ConvClosePayload(session.id,
                notice == null ? Component.empty() : notice));
        }
        if (broadcast && notice != null) {
            Entity npc = level.getEntity(session.npcUuid);
            if (npc != null) watchersNotice(level, npc, player, notice);
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
        if (session == null || !session.playerId.equals(player.getUUID())) {
            com.hearthstead.network.PayloadSend.toPlayer(player, new ConvClosePayload(payload.session(), Component.empty()));
            return;
        }
        ServerLevel level = player.serverLevel();
        Entity npc = level.getEntity(session.npcUuid);
        if (npc == null || !npc.isAlive() || player.distanceTo(npc) > KEEP_REACH) {
            close(level, session, null, false);
            return;
        }
        session.lastActive = level.getGameTime();
        switch (payload.kind()) {
            case ConvActionPayload.LEAVE -> close(level, session, null, false);
            case ConvActionPayload.CHOOSE -> {
                if (!consume(session, payload.revision())) {
                    sendState(player, session);
                    return;
                }
                choose(player, npc, session, payload.index(), payload.optionId());
            }
            case ConvActionPayload.BARTER_ACCEPT -> {
                if (session.barter == null || !consume(session, payload.revision())) {
                    if (session.barter != null) sendBarter(player, session, 2);
                    return;
                }
                barterAccept(player, npc, session, payload);
            }
            case ConvActionPayload.BARTER_BACK -> {
                session.barter = null;
                if (BARTER_ONLY.equals(session.graph.id())) close(level, session, null, false);
                else sendState(player, session);
            }
            default -> { }
        }
    }

    /**
     * One answer per shown state: the echoed revision must be the current one,
     * and it is used up here, before any cost, roll or item moves. Every state
     * or table sent afterwards carries the next revision.
     */
    private static boolean consume(Session session, int revision) {
        if (revision != session.revision || session.applying) return false;
        session.revision++;
        return true;
    }

    /** Test/QA: the revision a player must echo to answer, or -1. */
    public static int revisionOf(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        return session == null ? -1 : session.revision;
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
                handle(player, ConvActionPayload.choose(session.id, session.revision, i, optionId));
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
        List<Option> visible = visibleOptions(session, player, npc);
        if (index < 0 || index >= visible.size() || !visible.get(index).id().equals(optionId)) {
            sendState(player, session);
            return;
        }
        Option option = visible.get(index);
        ServerLevel level = player.serverLevel();
        Settlement settlement = session.settlementId == null ? null : SettlementManager.byId(level, session.settlementId);
        session.applying = true;
        try {
            if (END_NODE.equals(session.node)) {
                close(level, session, null, false);
                return;
            }
            // 1. Pay, exactly once, before anything else can happen.
            List<CostPayer.Resolved> costs = CostPayer.resolve(option.costs(), session.vars);
            if (!costs.isEmpty() && !CostPayer.pay(CostPayer.view(player, settlement), costs)) {
                player.displayClientMessage(Component.translatable("conversation.hearthstead.cannot_afford")
                    .withStyle(ChatFormatting.RED), true);
                sendState(player, session);
                return;
            }
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
            // 4. Content actions.
            ConversationContext ctx = new ConversationContext(player, npc, settlement, session.graph.id(),
                option.id(), session.vars, session.profile, succeeded);
            for (String action : outcome.actions()) ConversationActions.run(action, ctx);
            if (!BY_ID.containsKey(session.id)) return; // an action closed us (closeAll/unbind)
            // 5. Barter table.
            if (option.barterStock() != null && succeeded) {
                BarterStock stock = ListBarterStock.named(option.barterStock(), ctx);
                if (stock != null) {
                    session.barter = stock;
                    sendBarter(player, session, 0);
                    return;
                }
            }
            // 6. Where next.
            session.replyLines.clear();
            if (outcome.reply() != null) session.replyLines.add(line(outcome.reply(), session, player, npc));
            Component notice = ctx.notice() != null ? ctx.notice()
                : Component.translatable("conversation.hearthstead.chose", session.playerName,
                    optionText(option, session, player, npc));
            if (ctx.closeRequested() || outcome.next() == null) {
                if (session.replyLines.isEmpty() && session.flourish == 0) {
                    close(level, session, notice, true);
                } else {
                    session.node = END_NODE;
                    sendState(player, session);
                    watchersNotice(level, npc, player, notice);
                }
                return;
            }
            session.node = outcome.next();
            sendState(player, session);
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
            watchersNotice(player.serverLevel(), npc, player, Component.translatable("conversation.hearthstead.barter.done",
                session.playerName, speakerName(session.profile, npc)));
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
            mineValues.add(unitValue(stock, stack));
        }
        Entity npc = player.serverLevel().getEntity(session.npcUuid);
        com.hearthstead.network.PayloadSend.toPlayer(player, new ConvBarterPayload(session.id, session.npcEntityId,
            speakerName(session.profile, npc), relation, Relations.askPercent(relation), theirs, theirValues,
            mine, mineValues, result, session.revision));
    }

    // -------------------------------------------------------------- state ---

    private static void sendState(ServerPlayer player, Session session) {
        Entity npc = player.serverLevel().getEntity(session.npcUuid);
        if (npc == null) return;
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
        List<Component> lines = new ArrayList<>(session.replyLines);
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
        boolean intro = session.encounter && level.getGameTime() - session.opened < 5;
        com.hearthstead.network.PayloadSend.toPlayer(player, new ConvStatePayload(session.id, session.npcEntityId,
            speakerName(session.profile, npc),
            session.profile.titleKey().isBlank() ? Component.empty() : Component.translatable(session.profile.titleKey()),
            relation, memory, record, lines, views, session.flourish, session.flourishChance,
            intro ? 1 : 0, "low".equals(session.graph.encounter().style()) ? 1 : 0, session.revision));
        session.flourish = 0;
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
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            ServerLevel level = player == null ? null : player.serverLevel();
            Entity npc = level == null ? null : level.getEntity(session.npcUuid);
            if (player == null || npc == null || !npc.isAlive() || !player.isAlive()
                || player.distanceTo(npc) > KEEP_REACH + (session.encounter ? 6.0D : 0.0D)
                || level.getGameTime() - session.lastActive > ConversationConfig.talkTimeoutTicks()
                || !ConversationConfig.enabled()) {
                if (level != null) close(level, session, null, false);
                else {
                    BY_NPC.remove(session.npcUuid, session);
                    BY_PLAYER.remove(session.playerId, session);
                    BY_ID.remove(session.id);
                }
                continue;
            }
            if (npc instanceof Mob mob) {
                mob.getNavigation().stop();
                mob.getLookControl().setLookAt(player, 30.0F, 30.0F);
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
                    session.playerId.equals(player.getUUID()) ? "" : session.playerName));
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

    /** Damage to the talker or the speaker ends the talk (and aborts an intro). */
    public static void onHurt(LivingEntity victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        Session session = BY_PLAYER.get(victim.getUUID());
        if (session == null) session = BY_NPC.get(victim.getUUID());
        if (session == null || session.applying) return;
        close(level, session, null, false);
    }

    public static void onRightClick(ServerPlayer player, Entity target) {
        openBound(player, target, false);
    }

    public static void clearAll() {
        BY_NPC.clear();
        BY_PLAYER.clear();
        BY_ID.clear();
        ENCOUNTERED.clear();
        MARKED.clear();
    }

    private static void watchersNotice(ServerLevel level, Entity npc, @Nullable ServerPlayer talker, Component notice) {
        for (ServerPlayer other : level.players()) {
            if (other == talker || other instanceof FakePlayer) continue;
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

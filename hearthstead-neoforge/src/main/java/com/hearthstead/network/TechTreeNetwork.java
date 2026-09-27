package com.hearthstead.network;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentRecipeBook;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.hearthstead.settlement.CoinTreasury;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the v3 tech tree screen. Shared per settlement: any player
 * who may build and stands within reach of the Banner can learn, and every
 * player who has the tree open gets the new state pushed (co-op).
 */
public final class TechTreeNetwork {
    private static final double REACH_SQUARED = 8.0D * 8.0D;
    /** Players with the tree open -> settlement they are viewing. */
    private static final Map<UUID, UUID> VIEWERS = new ConcurrentHashMap<>();

    private TechTreeNetwork() {
    }

    /** Opens the v3 tree for this player (called instead of the old screen). */
    public static void open(ServerPlayer player, Settlement settlement, HearthBlockEntity hearth) {
        if (!inReach(player, settlement, hearth)) {
            return;
        }
        DevelopmentRecipeBook.syncPlayerHints(player, settlement);
        VIEWERS.put(player.getUUID(), settlement.id);
        PayloadSend.toPlayer(player, snapshot(player, settlement, hearth, "", "", false, true));
    }

    public static void handle(ServerPlayer player, TechTreeActionPayload action) {
        if (player == null || action == null) {
            return;
        }
        if (action.kind() == TechTreeActionPayload.CLOSE) {
            VIEWERS.remove(player.getUUID());
            return;
        }
        ServerLevel level = player.serverLevel();
        Settlement settlement = SettlementManager.byId(level, action.settlementId());
        if (settlement == null || !settlement.center.equals(action.hearthPos())
            || !level.isLoaded(action.hearthPos())
            || !(level.getBlockEntity(action.hearthPos()) instanceof HearthBlockEntity hearth)
            || !settlement.id.equals(hearth.getSettlementId())
            || !inReach(player, settlement, hearth)) {
            return;
        }
        String feedback = "";
        String arg = "";
        boolean good = false;
        if (action.kind() == TechTreeActionPayload.LEARN) {
            TechTree.Result result = TechTree.learn(level, settlement, hearth, action.nodeId(),
                action.revision(), player);
            feedback = result.key();
            arg = action.nodeId();
            good = result.applied();
            if (good) {
                DevelopmentRecipeBook.syncPlayerHints(player, settlement);
            }
        }
        VIEWERS.put(player.getUUID(), settlement.id);
        PayloadSend.toPlayer(player, snapshot(player, settlement, hearth, feedback, arg, good,
            false));
    }

    /**
     * Pushes fresh state to every other online viewer of this settlement
     * (the actor gets its own reply with feedback). Never throws from a tick.
     */
    public static void broadcast(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) {
            return;
        }
        // Crafting padlocks, handbook and recipe book follow every learn.
        com.hearthstead.settlement.development.TechKnowledgeSync.onChanged(level, settlement);
        if (VIEWERS.isEmpty()) {
            return;
        }
        pushToViewers(level, settlement);
    }

    /** Snapshots pushed by {@link #refreshViewers} (GameTests read it). */
    private static final java.util.concurrent.atomic.AtomicInteger REFRESHES = new java.util.concurrent.atomic.AtomicInteger();

    public static int refreshesForTests() {
        return REFRESHES.get();
    }

    /**
     * QA-UI-05: the Banner's stock changed (another player deposited or took), so open trees
     * of this settlement get a fresh snapshot and show current affordability. Snapshots only,
     * no knowledge sync; a purchase still goes through {@link #handle} and TechTree.learn,
     * which re-check the price. Called at most once a second per Banner, only after a change.
     */
    public static void refreshViewers(ServerLevel level, Settlement settlement) {
        if (level != null && settlement != null && !VIEWERS.isEmpty()) {
            REFRESHES.addAndGet(pushToViewers(level, settlement));
        }
    }

    /** Sends a feedback-free snapshot to every online viewer of this settlement; returns how many. */
    private static int pushToViewers(ServerLevel level, Settlement settlement) {
        if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)) {
            return 0;
        }
        int sent = 0;
        for (Map.Entry<UUID, UUID> viewer : VIEWERS.entrySet()) {
            if (!settlement.id.equals(viewer.getValue())) {
                continue;
            }
            ServerPlayer player = level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(viewer.getKey());
            if (player == null || player.level() != level) {
                VIEWERS.remove(viewer.getKey());
                continue;
            }
            PayloadSend.toPlayer(player, snapshot(player, settlement, hearth, "", "", false, false));
            sent++;
        }
        return sent;
    }

    /** The snapshot a player would get now (read-only; GameTests use it). */
    public static TechTreeSnapshotPayload snapshot(ServerPlayer player, Settlement settlement,
                                                   HearthBlockEntity hearth, String feedbackKey,
                                                   String feedbackArg, boolean good,
                                                   boolean openScreen) {
        ServerLevel level = player.serverLevel();
        DevelopmentState state = Development.of(level, settlement);
        TechTree.Treasury treasury = TechTree.treasury(level, settlement, hearth, player);
        List<TechTreeSnapshotPayload.NodeState> nodes = new ArrayList<>();
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            TechTree.Assessment a = TechTree.assess(level, settlement, def.id(), treasury);
            List<DevelopmentNode.Cost> costs = TechCosts.costs(def);
            int[] have = new int[costs.size()];
            for (int i = 0; i < have.length; i++) {
                have[i] = treasury.have(costs.get(i));
            }
            List<TechTreeSnapshotPayload.Gate> gates = new ArrayList<>();
            for (TechTree.GateProgress g : a.gates()) {
                gates.add(new TechTreeSnapshotPayload.Gate(g.kind(), g.detail(), g.progress(),
                    g.target()));
            }
            long[] study = state.study(def.id());
            nodes.add(new TechTreeSnapshotPayload.NodeState(def.id(), a.status().ordinal(),
                a.reason(), a.reasonArg(), have, gates, study == null ? 0L : study[0],
                study == null ? 0L : study[1]));
        }
        int coins = CoinTreasury.availableCoins(CoinTreasury.open(level, settlement, hearth, player));
        String journey = com.hearthstead.settlement.development.TechJourney.recommended(settlement, state);
        return new TechTreeSnapshotPayload(settlement.center, settlement.id, state.revision(),
            coins, nodes, feedbackKey, feedbackArg, good, openScreen, journey);
    }

    public static void forget(UUID player) {
        VIEWERS.remove(player);
    }

    private static boolean inReach(ServerPlayer player, Settlement settlement,
                                   HearthBlockEntity hearth) {
        return player != null && settlement != null && hearth != null
            && settlement.center.equals(hearth.getBlockPos())
            && settlement.id.equals(hearth.getSettlementId())
            && player.distanceToSqr(hearth.getBlockPos().getX() + 0.5D,
                hearth.getBlockPos().getY() + 0.5D,
                hearth.getBlockPos().getZ() + 0.5D) <= REACH_SQUARED;
    }
}

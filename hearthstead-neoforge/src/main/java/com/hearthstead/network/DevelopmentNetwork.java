package com.hearthstead.network;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.CoinTreasury;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.development.DevelopmentRecipeBook;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server authority shared by the Hearth tech map and Mayor emblem shop. */
public final class DevelopmentNetwork {
    private static final double REACH_SQUARED = 8.0D * 8.0D;
    private static final double MAYOR_REACH_SQUARED = 5.0D * 5.0D;
    /** Emblem rows cost Coins and goods, so their refusal names both. */
    static final String EMBLEM_MATERIALS_REASON = "hearthstead.emblem_shop.blocked.materials";

    public static void openTech(ServerPlayer player, Settlement settlement,
                                HearthBlockEntity hearth) {
        if (!validBoundHearth(player, settlement, hearth)
            || player.distanceToSqr(hearth.getBlockPos().getX() + 0.5D,
                hearth.getBlockPos().getY() + 0.5D,
                hearth.getBlockPos().getZ() + 0.5D) > REACH_SQUARED) {
            return;
        }
        // v3 tech tree ([techtree] enabled): the radial graph screen. A client
        // without the channel (proxy/vanilla) keeps the old screen.
        if (com.hearthstead.settlement.techtree.TechTreeConfig.enabled()
            && PayloadSend.canReceive(player, new TechTreeActionPayload(hearth.getBlockPos(),
                settlement.id, TechTreeActionPayload.REFRESH, "", 0))) {
            TechTreeNetwork.open(player, settlement, hearth);
            return;
        }
        DevelopmentRecipeBook.syncPlayerHints(player, settlement);
        send(player, snapshot(player, settlement, hearth,
            HearthMayorAction.NO_ID, DevelopmentActionPayload.View.TECH,
            Optional.empty()));
    }

    public static void openEmblemShop(ServerPlayer player, SettlerEntity mayor,
                                      Settlement settlement, HearthBlockEntity hearth) {
        if (!validBoundHearth(player, settlement, hearth) || mayor == null
            || settlement.mayorId == null
            || !settlement.mayorId.equals(mayor.getUUID())
            || !settlement.id.equals(mayor.getSettlementId())
            || mayor.level() != player.level()
            || !mayor.isAlive()
            || player.distanceToSqr(mayor) > MAYOR_REACH_SQUARED) {
            return;
        }
        send(player, snapshot(player, settlement, hearth,
            mayor.getUUID(), DevelopmentActionPayload.View.EMBLEM_SHOP,
            Optional.empty()));
    }

    public static void handle(ServerPlayer player, DevelopmentActionPayload action) {
        if (player == null || action == null
            || action.kind() == DevelopmentActionPayload.Kind.UNKNOWN
            || action.view() == DevelopmentActionPayload.View.UNKNOWN) {
            return;
        }
        if (action.kind() == DevelopmentActionPayload.Kind.INSPECT_MAYOR) {
            openMayorInspection(player, action);
            return;
        }
        ServerLevel level = player.serverLevel();
        HearthBlockEntity hearth = boundHearth(level, action);
        Settlement settlement = SettlementManager.byId(level, action.settlementId());
        if (hearth == null || settlement == null
            || !settlement.center.equals(action.hearthPos())) {
            return;
        }
        if (action.view() == DevelopmentActionPayload.View.TECH) {
            if (!HearthMayorAction.NO_ID.equals(action.mayorId())
                || player.distanceToSqr(action.hearthPos().getX() + 0.5D,
                    action.hearthPos().getY() + 0.5D,
                    action.hearthPos().getZ() + 0.5D) > REACH_SQUARED) {
                return;
            }
        } else if (!validLiveMayor(player, settlement, action.mayorId())) {
            if (settlement.mayorId != null
                && settlement.mayorId.equals(action.mayorId())) {
                send(player, snapshot(player, settlement, hearth, action.mayorId(),
                    action.view(), Optional.of(Component.translatable(
                        Development.Result.MAYOR_UNAVAILABLE.translationKey()))));
            }
            return;
        }

        Development.Result result = null;
        Component emblemRefusal = null;
        switch (action.kind()) {
            case UNLOCK_NODE -> {
                if (action.view() != DevelopmentActionPayload.View.TECH) {
                    return;
                }
                DevelopmentNode node = DevelopmentNode.byWireId(action.targetWireId());
                // Same actor gate as buyUpgrade/assessEmblem: a spectator or
                // adventure-mode player must not spend the village's stores.
                result = !player.isAlive() || player.isSpectator() || !player.mayBuild()
                    ? Development.Result.READ_ONLY
                    : Development.purchaseNode(level, settlement, hearth, node,
                        action.revision(), player);
                if (result == Development.Result.MATERIALS && node != null) {
                    emblemRefusal = missingCostsLine(Development.missingCosts(level,
                        settlement, hearth, node.costs(), player),
                        "hearthstead.development.blocked.missing");
                }
            }
            case BUY_EMBLEM -> {
                if (action.view() != DevelopmentActionPayload.View.EMBLEM_SHOP) {
                    return;
                }
                Profession profession = Profession.byId(action.targetWireId());
                Development.EmblemPurchase purchase = Development.purchaseEmblem(level,
                    settlement, hearth, profession, action.revision(), player);
                result = purchase.result();
                if (result == Development.Result.MATERIALS) {
                    emblemRefusal = missingEmblemCostsLine(level, settlement,
                        hearth, profession, player);
                }
                if (purchase.applied()) {
                    Component emblemName = purchase.emblem().getHoverName();
                    PendingPlayerDeliveryLedger.DeliveryResult delivery =
                        Development.deliverPending(level, settlement, player,
                            purchase.deliveryId());
                    player.displayClientMessage(Component.translatable(
                        "hearthstead.message.emblem_delivery."
                            + delivery.outcome().id(), emblemName), true);
                }
            }
            case BUY_UPGRADE -> {
                if (action.view() != DevelopmentActionPayload.View.TECH) {
                    return;
                }
                result = buyUpgrade(player, level, settlement, hearth, action);
                PostRaidUpgrade bought = PostRaidUpgrade.byWireId(action.targetWireId());
                if (result == Development.Result.MATERIALS && bought != null) {
                    emblemRefusal = missingCostsLine(Development.missingCosts(level,
                        settlement, hearth, bought.costs(), player),
                        "hearthstead.development.blocked.missing");
                }
            }
            case REFRESH -> {
            }
            case INSPECT_MAYOR -> {
                return; // handled before the ordinary Development actions
            }
            case UNKNOWN -> {
                return;
            }
        }

        Optional<Component> feedback = emblemRefusal != null
            ? Optional.of(emblemRefusal)
            : result == null
            ? Optional.empty() : Optional.of(Component.translatable(result.translationKey()));
        if (action.view() == DevelopmentActionPayload.View.TECH) {
            DevelopmentRecipeBook.syncPlayerHints(player, settlement);
        }
        send(player, snapshot(player, settlement, hearth, action.mayorId(),
            action.view(), feedback));
    }

    /**
     * Names exactly what an unaffordable emblem still lacks, e.g.
     * "Missing: 1 x Leather + 2 x Arrow", counted against the same buyer,
     * Hearth and Warehouse view the purchase pays from.
     */
    static Component missingEmblemCostsLine(ServerLevel level, Settlement settlement,
            HearthBlockEntity hearth, Profession profession, ServerPlayer player) {
        return missingCostsLine(Development.missingEmblemCosts(level,
            settlement, hearth, profession, player),
            "hearthstead.emblem_shop.blocked.missing");
    }

    /** "Missing: 2 x Leather + 1 x Coin" under the given key, or the generic refusal. */
    static Component missingCostsLine(List<DevelopmentNode.Cost> missing, String key) {
        if (missing.isEmpty()) {
            return Component.translatable(Development.Result.MATERIALS.translationKey());
        }
        net.minecraft.network.chat.MutableComponent line = Component.empty();
        for (int i = 0; i < missing.size(); i++) {
            DevelopmentNode.Cost cost = missing.get(i);
            if (i > 0) {
                line.append(Component.literal(" + "));
            }
            line.append(Component.translatable("hearthstead.development.cost.line",
                cost.count(), cost.displayName()));
        }
        return Component.translatable(key, line);
    }

    /**
     * Chronicle era IV: pays for one post-raid upgrade through
     * {@link Development#purchaseUpgrade}. The caller has already bound the
     * loaded settlement Hearth and enforced the Hearth reach for the TECH
     * view; this adds the actor checks (alive, not spectating, allowed to
     * build) and relies on the exact revision fence inside purchaseUpgrade.
     */
    static Development.Result buyUpgrade(ServerPlayer player, ServerLevel level,
                                         Settlement settlement, HearthBlockEntity hearth,
                                         DevelopmentActionPayload action) {
        if (!player.isAlive() || player.isSpectator() || !player.mayBuild()) {
            return Development.Result.READ_ONLY;
        }
        PostRaidUpgrade upgrade = PostRaidUpgrade.byWireId(action.targetWireId());
        if (upgrade == null) {
            return Development.Result.INVALID;
        }
        return Development.purchaseUpgrade(level, settlement, hearth, upgrade,
            action.revision(), player);
    }

    /**
     * Server-authoritative bridge from the diegetic Emblem Shop to the
     * appointed Mayor's ordinary settler sheet.
     *
     * <p>The client supplies both identities because a runtime entity id can
     * be reused while a UUID cannot, and a UUID can outlive an unload/reload
     * that changes the runtime id. Both must resolve to the same live object,
     * still appointed to the same settlement and still close to this player.
     * The Development revision is also exact: an emblem purchase or doctrine
     * change made after the shop snapshot requires one deliberate retry.
     *
     * @return the new exact settler-inspection session, or {@code null} when
     *         the action was refused without opening a sheet
     */
    @Nullable
    static UUID openMayorInspection(ServerPlayer player,
                                    DevelopmentActionPayload action) {
        if (player == null || action == null
            || action.view() != DevelopmentActionPayload.View.EMBLEM_SHOP
            || action.kind() != DevelopmentActionPayload.Kind.INSPECT_MAYOR) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        HearthBlockEntity hearth = boundHearth(level, action);
        Settlement settlement = SettlementManager.byId(level,
            action.settlementId());
        if (hearth == null || settlement == null
            || !settlement.center.equals(action.hearthPos())) {
            return null;
        }
        if (!validLiveMayor(player, settlement, action.mayorId())) {
            if (settlement.mayorId != null
                && settlement.mayorId.equals(action.mayorId())) {
                send(player, snapshot(player, settlement, hearth,
                    action.mayorId(), action.view(), Optional.of(
                        Component.translatable(
                            Development.Result.MAYOR_UNAVAILABLE.translationKey()))));
            }
            return null;
        }
        if (player.isSpectator()) {
            send(player, snapshot(player, settlement, hearth, action.mayorId(),
                action.view(), Optional.of(Component.translatable(
                    "hearthstead.emblem_shop.inspect.read_only"))));
            return null;
        }
        if (action.revision()
            != Development.revisionOf(level, settlement)) {
            send(player, snapshot(player, settlement, hearth, action.mayorId(),
                action.view(), Optional.of(Component.translatable(
                    Development.Result.STALE.translationKey()))));
            return null;
        }

        if (!(level.getEntity(action.targetWireId())
                instanceof SettlerEntity mayor)
            || level.getEntity(action.mayorId()) != mayor
            || mayor.getId() != action.targetWireId()
            || !mayor.getUUID().equals(action.mayorId())
            || !mayor.isAlive()
            || mayor.level() != level
            || !settlement.id.equals(mayor.getSettlementId())
            || settlement.mayorId == null
            || !settlement.mayorId.equals(mayor.getUUID())
            || player.distanceToSqr(mayor) > MAYOR_REACH_SQUARED) {
            send(player, snapshot(player, settlement, hearth, action.mayorId(),
                action.view(), Optional.of(Component.translatable(
                    "hearthstead.emblem_shop.inspect.changed"))));
            return null;
        }

        com.hearthstead.network.PayloadSend.toPlayer(player,
            new OpenSettlerScreenPayload(mayor.getId()));
        return SettlerNetwork.openFor(player, mayor);
    }

    /**
     * The TECH snapshot this player would receive right now, without sending
     * it. Read-only; used by GameTests to assert the upgrade rows.
     */
    public static DevelopmentSnapshotPayload techSnapshot(ServerPlayer player,
            Settlement settlement, HearthBlockEntity hearth) {
        return snapshot(player, settlement, hearth, HearthMayorAction.NO_ID,
            DevelopmentActionPayload.View.TECH, Optional.empty());
    }

    private static DevelopmentSnapshotPayload snapshot(ServerPlayer player,
            Settlement settlement, HearthBlockEntity hearth,
            java.util.UUID mayorId,
            DevelopmentActionPayload.View view, Optional<Component> feedback) {
        ServerLevel level = player.serverLevel();
        DevelopmentState state = Development.of(level, settlement);
        List<DevelopmentSnapshotPayload.NodeView> nodes;
        if (view == DevelopmentActionPayload.View.TECH) {
            ArrayList<DevelopmentSnapshotPayload.NodeView> techNodes = new ArrayList<>(
                DevelopmentNode.PRESENTATION_ORDER.length);
            for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
                Development.Assessment assessment = Development.assessNode(level, settlement,
                    hearth, node, player);
                ArrayList<DevelopmentSnapshotPayload.QuestView> quests =
                    new ArrayList<>(node.quests().size());
                for (DevelopmentQuests.Progress progress
                        : DevelopmentQuests.progress(level, settlement, hearth, state, node)) {
                    quests.add(new DevelopmentSnapshotPayload.QuestView(
                        progress.objective().wireId(), progress.progress(), progress.target()));
                }
                techNodes.add(new DevelopmentSnapshotPayload.NodeView(node.wireId(),
                    assessment.status().wireId(), assessment.allowed()
                        ? "" : assessment.result().translationKey(), List.copyOf(quests)));
            }
            nodes = List.copyOf(techNodes);
        } else {
            nodes = List.of();
        }

        List<DevelopmentSnapshotPayload.EmblemView> emblems;
        if (view == DevelopmentActionPayload.View.EMBLEM_SHOP) {
            ArrayList<DevelopmentSnapshotPayload.EmblemView> shopEntries = new ArrayList<>(
                JobEmblemCatalog.RELEASE_CATALOG.size());
            boolean mayorAvailable = validLiveMayor(player, settlement, mayorId);
            for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
                if (!JobEmblemCatalog.offered(entry)) {
                    continue; // [features] extendedTrades off: not on sale
                }
                Development.Result assessment = mayorAvailable
                    ? Development.assessEmblem(level, settlement, hearth, entry.profession(), player)
                    : Development.Result.MAYOR_UNAVAILABLE;
                shopEntries.add(new DevelopmentSnapshotPayload.EmblemView(
                    entry.profession().id(), assessment == Development.Result.APPLIED,
                    assessment == Development.Result.APPLIED ? ""
                        : assessment == Development.Result.MATERIALS
                        ? EMBLEM_MATERIALS_REASON : assessment.translationKey()));
            }
            emblems = List.copyOf(shopEntries);
        } else {
            emblems = List.of();
        }

        List<DevelopmentSnapshotPayload.UpgradeView> upgrades;
        if (view == DevelopmentActionPayload.View.TECH) {
            ArrayList<DevelopmentSnapshotPayload.UpgradeView> rows = new ArrayList<>();
            for (PostRaidUpgrade upgrade : PostRaidUpgrade.values()) {
                Development.Result assessment = Development.assessUpgrade(level,
                    settlement, hearth, upgrade, player);
                Development.NodeStatus status = switch (assessment) {
                    case ALREADY_UNLOCKED -> Development.NodeStatus.OWNED;
                    case APPLIED -> Development.NodeStatus.AVAILABLE;
                    case QUARANTINED -> Development.NodeStatus.QUARANTINED;
                    default -> Development.NodeStatus.LOCKED;
                };
                rows.add(new DevelopmentSnapshotPayload.UpgradeView(upgrade.wireId(),
                    status.wireId(), assessment == Development.Result.APPLIED
                        ? "" : assessment.translationKey(), upgrade.coinCost()));
            }
            upgrades = List.copyOf(rows);
        } else {
            upgrades = List.of();
        }

        DevelopmentNode active = state.activeDoctrine();
        int mayorEntityId = -1;
        if (view == DevelopmentActionPayload.View.EMBLEM_SHOP
            && settlement.mayorId != null
            && settlement.mayorId.equals(mayorId)
            && level.getEntity(mayorId) instanceof SettlerEntity mayor
            && mayor.isAlive() && mayor.level() == level
            && settlement.id.equals(mayor.getSettlementId())) {
            mayorEntityId = mayor.getId();
        }
        return new DevelopmentSnapshotPayload(settlement.center, settlement.id, mayorId,
            mayorEntityId, view,
            state.revision(), active == null ? -1 : active.wireId(),
            state.doctrineChangedAt(),
            CoinTreasury.availableCoins(CoinTreasury.open(level, settlement, hearth, player)),
            nodes, emblems, feedback, upgrades);
    }

    private static boolean validLiveMayor(ServerPlayer player, Settlement settlement,
                                          java.util.UUID claimedMayorId) {
        if (claimedMayorId == null || settlement.mayorId == null
            || !settlement.mayorId.equals(claimedMayorId)) {
            return false;
        }
        if (!(player.serverLevel().getEntity(claimedMayorId)
            instanceof SettlerEntity mayor)) {
            return false;
        }
        return mayor.isAlive()
            && mayor.level() == player.level()
            && settlement.id.equals(mayor.getSettlementId())
            && player.distanceToSqr(mayor) <= MAYOR_REACH_SQUARED;
    }

    private static boolean validBoundHearth(ServerPlayer player, Settlement settlement,
                                            HearthBlockEntity hearth) {
        return player != null && settlement != null && hearth != null
            && settlement.center.equals(hearth.getBlockPos())
            && settlement.id.equals(hearth.getSettlementId())
            && player.serverLevel().getBlockEntity(hearth.getBlockPos()) == hearth;
    }

    @Nullable
    private static HearthBlockEntity boundHearth(ServerLevel level,
                                                  DevelopmentActionPayload action) {
        // The position is client-chosen: never let a packet load or generate
        // a chunk. Only an already-loaded settlement Hearth may be bound.
        if (action.hearthPos() == null || action.settlementId() == null
            || !level.isLoaded(action.hearthPos())) {
            return null;
        }
        Settlement claimed = SettlementManager.byId(level, action.settlementId());
        if (claimed == null || !action.hearthPos().equals(claimed.center)) {
            return null;
        }
        if (!(level.getBlockEntity(action.hearthPos()) instanceof HearthBlockEntity hearth)
            || !action.settlementId().equals(hearth.getSettlementId())) {
            return null;
        }
        return hearth;
    }

    private static void send(ServerPlayer player, DevelopmentSnapshotPayload snapshot) {
        com.hearthstead.network.PayloadSend.toPlayer(player, snapshot);
    }

    private DevelopmentNetwork() {
    }
}

package com.hearthstead.network;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.GuildmasterEntity;
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
import com.hearthstead.settlement.guildmaster.GuildmasterService;
import com.hearthstead.settlement.guildmaster.GuildmasterTrade;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server authority shared by the Banner tech map and the Guildmaster's Professions & Emblems trade. */
public final class DevelopmentNetwork {
    private static final double REACH_SQUARED = 8.0D * 8.0D;
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

    /**
     * Opens Professions & Emblems for a player standing at the settlement's
     * seated Guildmaster. The snapshot's legacy "mayorId" field carries the
     * Guildmaster UUID, so every later action is re-validated against him.
     */
    public static void openEmblemShop(ServerPlayer player, GuildmasterEntity guildmaster,
                                      Settlement settlement, HearthBlockEntity hearth) {
        if (!validBoundHearth(player, settlement, hearth) || guildmaster == null
            || !GuildmasterService.inReach(player, settlement, guildmaster.getUUID())) {
            return;
        }
        send(player, snapshot(player, settlement, hearth,
            guildmaster.getUUID(), DevelopmentActionPayload.View.EMBLEM_SHOP,
            Optional.empty()));
    }

    public static void handle(ServerPlayer player, DevelopmentActionPayload action) {
        if (player == null || action == null
            || action.kind() == DevelopmentActionPayload.Kind.UNKNOWN
            || action.view() == DevelopmentActionPayload.View.UNKNOWN) {
            return;
        }
        if (action.kind() == DevelopmentActionPayload.Kind.INSPECT_MAYOR) {
            return; // the Mayor office is retired; nothing to inspect
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
        } else if (!GuildmasterService.inReach(player, settlement, action.mayorId())) {
            // Walked away, or the Guildmaster changed: refresh the open
            // screen with the reason instead of acting on a stale one.
            // A periodic REFRESH gets the same snapshot (its rows already say
            // "unavailable") but no feedback, so an open screen never beeps
            // once a second while its player stands too far away.
            if (action.mayorId() != null
                && action.mayorId().equals(GuildmasterService.liveId(level, settlement))) {
                send(player, snapshot(player, settlement, hearth, action.mayorId(),
                    action.view(), action.kind() == DevelopmentActionPayload.Kind.REFRESH
                        ? Optional.empty()
                        : Optional.of(Component.translatable(
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
                BuyOutcome bought = buyEmblems(player, level, settlement, hearth,
                    action.targetWireId(), action.revision());
                result = bought.result();
                emblemRefusal = bought.refusal();
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

    /** What one Buy press did: the authoritative result and, if refused, the exact reason. */
    record BuyOutcome(@Nullable Development.Result result, @Nullable Component refusal, int bought) {
    }

    /**
     * Buys {@code quantity} emblems of one profession for the player, all in
     * this server tick (nothing can interleave on the server thread).
     *
     * <p>Order of checks, each refusing with no state change: exact screen
     * revision (a stale screen or a double click never buys twice), the
     * emblem gate (Guildmaster present, known, not quarantined), the WHOLE
     * price for the quantity (Coins + goods from the buyer, Banner and
     * linked Warehouses), then one free inventory slot per emblem (each
     * emblem carries its own purchase provenance, so they never stack onto
     * an existing one). Only then is each emblem paid for and delivered
     * through the proven single-emblem path: payment and the delivery
     * reservation commit together, and delivery goes into the checked slots.
     */
    static BuyOutcome buyEmblems(ServerPlayer player, ServerLevel level, Settlement settlement,
                                 HearthBlockEntity hearth, int encodedTarget, int revision) {
        int quantity = GuildmasterTrade.quantityOf(encodedTarget);
        Profession profession = quantity <= 0 ? Profession.NONE
            : Profession.byId(GuildmasterTrade.professionIdOf(encodedTarget));
        if (quantity <= 0 || profession == Profession.NONE) {
            return new BuyOutcome(Development.Result.INVALID, null, 0);
        }
        if (!player.isAlive() || player.isSpectator()) {
            return new BuyOutcome(Development.Result.READ_ONLY, null, 0);
        }
        if (revision != Development.revisionOf(level, settlement)) {
            return new BuyOutcome(Development.Result.STALE, null, 0);
        }
        Development.Result gate = Development.assessEmblem(level, settlement, hearth,
            profession, player);
        if (gate != Development.Result.APPLIED && gate != Development.Result.MATERIALS) {
            return new BuyOutcome(gate, null, 0);
        }
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        List<DevelopmentNode.Cost> missing = Development.missingCosts(level, settlement,
            hearth, GuildmasterTrade.total(entry, quantity), player);
        if (!missing.isEmpty() || gate == Development.Result.MATERIALS) {
            return new BuyOutcome(Development.Result.MATERIALS, missingCostsLine(missing,
                "hearthstead.emblem_shop.blocked.missing"), 0);
        }
        int free = GuildmasterTrade.emptySlots(player.getInventory());
        if (free < quantity) {
            return new BuyOutcome(Development.Result.INVALID, Component.translatable(
                "hearthstead.guildmaster.blocked.inventory", quantity, free), 0);
        }
        int bought = 0;
        Development.Result last = Development.Result.APPLIED;
        Component emblemName = null;
        PendingPlayerDeliveryLedger.Outcome lastOutcome = null;
        boolean allInInventory = true;
        for (int i = 0; i < quantity; i++) {
            Development.EmblemPurchase purchase = Development.purchaseEmblem(level,
                settlement, hearth, profession, Development.revisionOf(level, settlement),
                player);
            if (!purchase.applied()) {
                last = purchase.result();
                break;
            }
            emblemName = purchase.emblem().getHoverName();
            PendingPlayerDeliveryLedger.DeliveryResult delivery =
                Development.deliverPending(level, settlement, player, purchase.deliveryId());
            lastOutcome = delivery.outcome();
            allInInventory &= lastOutcome == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND
                || lastOutcome == PendingPlayerDeliveryLedger.Outcome.INVENTORY;
            bought++;
        }
        if (bought == 1 && lastOutcome != null) {
            player.displayClientMessage(Component.translatable(
                "hearthstead.message.emblem_delivery." + lastOutcome.id(), emblemName), true);
        } else if (bought > 1) {
            player.displayClientMessage(Component.translatable(allInInventory
                ? "hearthstead.guildmaster.bought.inventory"
                : "hearthstead.guildmaster.bought.secured", bought, emblemName), true);
        }
        if (bought == 0) {
            return new BuyOutcome(last, null, 0);
        }
        if (bought < quantity) {
            return new BuyOutcome(last, Component.translatable(
                "hearthstead.guildmaster.bought.partial", bought, quantity), bought);
        }
        return new BuyOutcome(Development.Result.APPLIED, null, bought);
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
            boolean mayorAvailable = GuildmasterService.inReach(player, settlement, mayorId);
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
        // Legacy wire slot: now the seated Guildmaster's runtime entity id.
        int mayorEntityId = -1;
        GuildmasterEntity guildmaster = view == DevelopmentActionPayload.View.EMBLEM_SHOP
            ? GuildmasterService.live(level, settlement) : null;
        if (guildmaster != null && guildmaster.getUUID().equals(mayorId)) {
            mayorEntityId = guildmaster.getId();
        }
        return new DevelopmentSnapshotPayload(settlement.center, settlement.id, mayorId,
            mayorEntityId, view,
            state.revision(), active == null ? -1 : active.wireId(),
            state.doctrineChangedAt(),
            CoinTreasury.availableCoins(CoinTreasury.open(level, settlement, hearth, player)),
            nodes, emblems, feedback, upgrades);
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

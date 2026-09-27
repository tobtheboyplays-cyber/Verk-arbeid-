package com.hearthstead.network;

import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerAttributes;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side rules for the settler inspection screen: what a player is
 * shown, and what they are allowed to change.
 *
 * <p>The screen never rolls a settler's attributes or traits itself — they
 * are rolled once, server-side, the moment the settler is first inspected or
 * loaded (see {@code SettlerEntity#attributes()}); rolling them again on the
 * client would describe a different person. So every field this class cannot
 * read off the synced entity is resolved here and sent down whole, and every
 * action re-checks the world from scratch rather than trusting what the
 * packet claims.
 */
public final class SettlerNetwork {

    /**
     * How far a settler may be from the player and its open sheet still act.
     * Must match the client sheet's own settler-leave distance (24 blocks in
     * SettlerScreen): couriers keep walking while read, and an 8-block server
     * reach silently refused (and dropped the session for) every button on a
     * sheet the client still showed as live -- the Inventory button "did
     * nothing". The player-anchor check on the client still closes the sheet
     * the moment the player walks away.
     */
    public static final double SHEET_REACH_SQUARED = 24.0 * 24.0;
    private static final double REACH_SQUARED = SHEET_REACH_SQUARED;

    public static UUID openFor(ServerPlayer player, SettlerEntity settler) {
        UUID sessionId = InspectionViewers.openSettler(player, settler);
        send(player, snapshot(player, settler, sessionId, Optional.empty(),
            SettlerSnapshotPayload.Delivery.OPEN));
        return sessionId;
    }

    public static void handle(ServerPlayer player, SettlerActionPayload action) {
        if (action == null || action.kind() == null
            || action.kind() == SettlerActionPayload.Kind.UNKNOWN) {
            return;
        }
        if (action.kind() == SettlerActionPayload.Kind.CLOSE) {
            InspectionViewers.closeSettler(player, action.entityId(),
                action.settlerId(), action.sessionId());
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!(level.getEntity(action.entityId()) instanceof SettlerEntity settler)
            || !settler.isAlive()) {
            InspectionViewers.closeSettler(player, action.entityId(),
                action.settlerId(), action.sessionId());
            refuseOutOfBand(player, "hearthstead.settler.refused.gone");
            return;
        }
        if (!InspectionViewers.authorizeSettler(player, settler,
                action.settlerId(), action.sessionId())) {
            refuseOutOfBand(player, "hearthstead.settler.refused.invalid_session");
            return;
        }
        if (player.distanceToSqr(settler) > REACH_SQUARED) {
            InspectionViewers.closeSettler(player, action.entityId(),
                action.settlerId(), action.sessionId());
            refuse(player, settler, action.sessionId(),
                Component.translatable("hearthstead.settler.too_far"));
            return;
        }
        Settlement settlement = settlementOf(level, settler);
        if (settlement == null) {
            refuse(player, settler, action.sessionId(), Component.translatable(
                "hearthstead.settler.refused.no_settlement"));
            return;
        }
        if (action.kind() != SettlerActionPayload.Kind.LOCATE
            && (player.isSpectator() || !player.mayBuild())) {
            // A player who changed modes while this exact sheet was open may
            // still inspect or locate them, but can never open a mutable child
            // surface or change employment, work zones or the Mayor.
            refuse(player, settler, action.sessionId(), Component.translatable(
                "hearthstead.settler.refused.read_only"));
            return;
        }
        if (action.kind() != SettlerActionPayload.Kind.LOCATE
            && action.revision() != revisionOf(settlement, settler)) {
            // Someone else changed this settler's job or the settlement's
            // mayor while the screen was open.
            refuse(player, settler, action.sessionId(),
                Component.translatable("hearthstead.settler.stale"));
            return;
        }

        switch (action.kind()) {
            case DISMISS -> {
                Component refusal = Employment.dismiss(level, settlement, settler) == null
                    ? Component.translatable("hearthstead.settler.refused.no_job")
                    : null;
                send(player, snapshot(player, settler, action.sessionId(),
                    Optional.ofNullable(refusal), SettlerSnapshotPayload.Delivery.UPDATE));
            }
            case APPOINT -> {
                Component refusal = Mayor.appoint(level, settlement, settler);
                if (refusal == null) {
                    JourneyServerHooks.noteMayorAppointed(player, settlement, settler);
                }
                send(player, snapshot(player, settler, action.sessionId(),
                    Optional.ofNullable(refusal), SettlerSnapshotPayload.Delivery.UPDATE));
            }
            case OPEN_INVENTORY -> openInventory(player, settler, settlement,
                action);
            case EDIT_WORK_ZONE -> editWorkZone(player, settler, action);
            case OPEN_WORKPLACE -> openWorkplace(player, settler, settlement,
                action);
            case LOCATE -> {
                SettlerLocateSignal.send(player, settler);
                send(player, snapshot(player, settler, action.sessionId(),
                    Optional.empty(), SettlerSnapshotPayload.Delivery.UPDATE));
            }
            case CLOSE, UNKNOWN -> { } // handled before world resolution
        }
    }

    private static void openInventory(ServerPlayer player, SettlerEntity settler,
                                      Settlement settlement,
                                      SettlerActionPayload action) {
        settler.reconcileEquipmentNeedNow();
        InspectionViewers.closeSettler(player, action.entityId(),
            action.settlerId(), action.sessionId());
        player.openMenu(new SimpleMenuProvider(
            (containerId, inventory, ignored) ->
                new SettlerInventoryMenu(containerId, inventory, settler),
            Component.translatable("hearthstead.settler.inventory.title",
                settler.getSettlerName())), buffer -> {
                    buffer.writeVarInt(settler.getId());
                    buffer.writeUUID(settler.getUUID());
                });
        JourneyServerHooks.noteSettlerInventoryViewed(player, settlement, settler);
    }

    private static void editWorkZone(ServerPlayer player, SettlerEntity settler,
                                     SettlerActionPayload action) {
        WorkZoneService.Result result = WorkZoneService.selectWorker(player, settler);
        if (result == WorkZoneService.Result.SELECTED) {
            // The TARGET_SELECTED payload is already in flight. Releasing the
            // old sheet here makes the following world-selection flow the one
            // and only live authority surface.
            InspectionViewers.closeSettler(player, action.entityId(),
                action.settlerId(), action.sessionId());
            return;
        }
        // WorkZoneService also writes this exact message to the action bar;
        // retaining it in the Overview makes the refusal durable and explains
        // why the pending child did not open.
        refuse(player, settler, action.sessionId(), result.message());
    }

    private static void openWorkplace(ServerPlayer player, SettlerEntity settler,
                                      Settlement settlement,
                                      SettlerActionPayload action) {
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        if (employer == null || employer.plaquePos == null) {
            refuse(player, settler, action.sessionId(), Component.translatable(
                "hearthstead.settler.refused.no_workplace"));
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(employer.plaquePos)) {
            refuse(player, settler, action.sessionId(), Component.translatable(
                "hearthstead.settler.refused.workplace_unloaded"));
            return;
        }
        if (!(level.getBlockEntity(employer.plaquePos)
                instanceof PlaqueBlockEntity plaque)
            || !employer.plaquePos.equals(plaque.getBlockPos())
            || !employer.id.equals(plaque.buildingId())
            || plaque.building(level) != employer
            || plaque.settlementFor(level) != settlement
            || !employer.valid) {
            refuse(player, settler, action.sessionId(), Component.translatable(
                "hearthstead.settler.refused.workplace_stale"));
            return;
        }
        InspectionViewers.closeSettler(player, action.entityId(),
            action.settlerId(), action.sessionId());
        PlaqueNetwork.openFor(player, plaque);
    }

    private static void refuse(ServerPlayer player, SettlerEntity settler,
                               UUID sessionId, Component reason) {
        send(player, snapshot(player, settler, sessionId, Optional.of(reason),
            SettlerSnapshotPayload.Delivery.UPDATE));
    }

    private static void refuseOutOfBand(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    // ----------------------------------------------------------- snapshot --

    private static SettlerSnapshotPayload snapshot(ServerPlayer player, SettlerEntity settler,
                                                    UUID sessionId,
                                                    Optional<Component> refusal,
                                                    SettlerSnapshotPayload.Delivery delivery) {
        ServerLevel level = player.serverLevel();
        Settlement settlement = settlementOf(level, settler);

        SettlerAttributes attributes = settler.attributes();
        List<Integer> values = new ArrayList<>(Attribute.COUNT);
        for (Attribute attribute : Attribute.ALL) {
            values.add(attributes.get(attribute));
        }
        List<Integer> traitOrdinals = new ArrayList<>();
        for (Trait trait : settler.traits()) {
            traitOrdinals.add(trait.ordinal());
        }
        String boonKey = Mayor.Boon.of(attributes.knack()).key();
        // Three direct EnumMap-backed reads, only when the existing screen
        // snapshot is authored. There is no idle sync or inspection tick.
        int wardenOathBlessingRank = settler.blessingRank(BlessingId.WARDEN_OATH);
        int hearthwardBlessingRank = settler.blessingRank(BlessingId.HEARTHWARD);
        int thornedRoadsBlessingRank = settler.blessingRank(BlessingId.THORNED_ROADS);

        // Reconcile before copying bag rows: direct player insertion may move
        // the one requested physical tool from bag to hand on this call.
        EquipmentRequest currentRequest = settlement == null ? null
            : EquipmentRequests.refreshFor(level, settler);

        // The bag: real, physically carried items (chest truth), one slot
        // in, one slot out -- sent whether or not the settler is bound to a
        // settlement, since what they are carrying does not depend on that.
        List<Integer> bagItemIds = new ArrayList<>(settler.bag.getContainerSize());
        List<Integer> bagCounts = new ArrayList<>(settler.bag.getContainerSize());
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            bagItemIds.add(BuiltInRegistries.ITEM.getId(stack.getItem()));
            bagCounts.add(stack.getCount());
        }

        if (settlement == null) {
            // Unbound (a traveler, or a settler summoned outside any
            // settlement): nothing below is meaningful, so it is sent empty
            // rather than guessed at.
            return new SettlerSnapshotPayload(settler.getId(), settler.getUUID(),
                sessionId, 0, false,
                List.copyOf(values), attributes.knack().ordinal(), List.copyOf(traitOrdinals),
                List.copyOf(bagItemIds), List.copyOf(bagCounts),
                "", false, false, false, false, false, boonKey,
                wardenOathBlessingRank, hearthwardBlessingRank,
                thornedRoadsBlessingRank, -1, -1, delivery, refusal);
        }

        Building employer = Employment.employerOf(settlement, settler.getUUID());
        boolean isMayor = settlement.mayorId != null
            && settlement.mayorId.equals(settler.getUUID());
        boolean mourning = Mayor.mourning(level, settlement);
        boolean mayorSettling = isMayor && Mayor.activeBoon(level, settlement) == null;

        return new SettlerSnapshotPayload(settler.getId(), settler.getUUID(),
            sessionId, revisionOf(settlement, settler), !player.isSpectator() && player.mayBuild(),
            List.copyOf(values), attributes.knack().ordinal(), List.copyOf(traitOrdinals),
            List.copyOf(bagItemIds), List.copyOf(bagCounts),
            employer == null ? "" : employer.type.id(),
            Employment.watchOf(settlement, settler) == Employment.Watch.NIGHT,
            isMayor, mayorSettling, mourning, settlement.mayorId == null, boonKey,
            wardenOathBlessingRank, hearthwardBlessingRank,
            thornedRoadsBlessingRank,
            currentRequest == null ? -1 : BuiltInRegistries.ITEM.getId(
                currentRequest.requirement().preferredItem()),
            currentRequest == null ? -1 : currentRequest.reason().ordinal(),
            delivery, refusal, developmentMask(level, settlement),
            homeBuildingId(settlement, settler), settler.getClaimedBed() != null);
    }

    /** BuildingType id of the building that holds this settler's claimed bed, or "". */
    private static String homeBuildingId(Settlement settlement, SettlerEntity settler) {
        net.minecraft.core.BlockPos bed = settler.getClaimedBed();
        if (bed == null) {
            return "";
        }
        for (Building building : settlement.buildings) {
            if (building.beds.contains(bed)) {
                return building.type.id();
            }
        }
        return "";
    }

    /**
     * Owned Development bonuses for the sheet's read-only "active bonuses"
     * line. Only computed when a snapshot is authored; no idle sync.
     */
    private static int developmentMask(ServerLevel level, Settlement settlement) {
        int mask = 0;
        for (com.hearthstead.settlement.development.PostRaidUpgrade upgrade
            : com.hearthstead.settlement.development.PostRaidUpgrade.values()) {
            int bit = upgrade.wireId();
            if (bit >= 0 && bit < SettlerSnapshotPayload.SHIELD_DOCTRINE_BIT
                && com.hearthstead.settlement.development.Development
                    .hasUpgrade(level, settlement, upgrade)) {
                mask |= 1 << bit;
            }
        }
        if (com.hearthstead.settlement.development.Development.hasNode(level, settlement,
            com.hearthstead.settlement.development.DevelopmentNode.SHIELD_DOCTRINE)) {
            mask |= 1 << SettlerSnapshotPayload.SHIELD_DOCTRINE_BIT;
        }
        return mask;
    }

    /**
     * Changes exactly when a stale click could do the wrong thing: who
     * employs this settler, who is mayor, and whether the settlement is
     * mourning. No new persisted state — recomputed fresh both times.
     */
    private static int revisionOf(Settlement settlement, SettlerEntity settler) {
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        return Objects.hash(employer == null ? null : employer.id, settlement.mayorId,
            settlement.mourningUntil);
    }

    @Nullable
    private static Settlement settlementOf(ServerLevel level, SettlerEntity settler) {
        UUID id = settler.getSettlementId();
        return id == null ? null : SettlementManager.byId(level, id);
    }

    static void sendUpdate(ServerPlayer player, SettlerEntity settler,
                           UUID sessionId) {
        send(player, snapshot(player, settler, sessionId, Optional.empty(),
            SettlerSnapshotPayload.Delivery.UPDATE));
    }

    private static void send(ServerPlayer player, SettlerSnapshotPayload snapshot) {
        com.hearthstead.network.PayloadSend.toPlayer(player, snapshot);
    }

    private SettlerNetwork() {
    }
}

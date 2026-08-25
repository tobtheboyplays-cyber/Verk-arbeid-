package com.hearthstead.network;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerAttributes;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    /** How far a player may stand from a settler and still manage them. */
    private static final double REACH_SQUARED = 8.0 * 8.0;

    public static void openFor(ServerPlayer player, SettlerEntity settler) {
        send(player, snapshot(player, settler, Optional.empty()));
    }

    public static void handle(ServerPlayer player, SettlerActionPayload action) {
        ServerLevel level = player.serverLevel();
        if (!(level.getEntity(action.entityId()) instanceof SettlerEntity settler)
            || !settler.isAlive()) {
            return; // the settler is gone; the screen closes itself
        }
        if (player.distanceToSqr(settler) > REACH_SQUARED) {
            send(player, snapshot(player, settler,
                Optional.of(Component.translatable("hearthstead.settler.too_far"))));
            return;
        }
        Settlement settlement = settlementOf(level, settler);
        if (settlement == null) {
            send(player, snapshot(player, settler,
                Optional.of(Component.translatable("hearthstead.settler.refused.no_settlement"))));
            return;
        }
        if (action.revision() != revisionOf(settlement, settler)) {
            // Someone else changed this settler's job or the settlement's
            // mayor while the screen was open.
            send(player, snapshot(player, settler,
                Optional.of(Component.translatable("hearthstead.settler.stale"))));
            return;
        }

        Optional<Component> refusal = switch (action.kind()) {
            case DISMISS -> Employment.dismiss(level, settlement, settler) == null
                ? Optional.of(Component.translatable("hearthstead.settler.refused.no_job"))
                : Optional.empty();
            case APPOINT -> Optional.ofNullable(Mayor.appoint(level, settlement, settler));
        };
        send(player, snapshot(player, settler, refusal));
    }

    // ----------------------------------------------------------- snapshot --

    private static SettlerSnapshotPayload snapshot(ServerPlayer player, SettlerEntity settler,
                                                    Optional<Component> refusal) {
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

        if (settlement == null) {
            // Unbound (a traveler, or a settler summoned outside any
            // settlement): nothing below is meaningful, so it is sent empty
            // rather than guessed at.
            return new SettlerSnapshotPayload(settler.getId(), 0, false,
                List.copyOf(values), attributes.knack().ordinal(), List.copyOf(traitOrdinals),
                "", false, false, false, false, boonKey, refusal);
        }

        Building employer = Employment.employerOf(settlement, settler.getUUID());
        boolean isMayor = settlement.mayorId != null
            && settlement.mayorId.equals(settler.getUUID());
        boolean mourning = Mayor.mourning(level, settlement);
        boolean mayorSettling = isMayor && Mayor.activeBoon(level, settlement) == null;

        return new SettlerSnapshotPayload(settler.getId(), revisionOf(settlement, settler),
            true, List.copyOf(values), attributes.knack().ordinal(), List.copyOf(traitOrdinals),
            employer == null ? "" : employer.type.id(),
            Employment.watchOf(settlement, settler) == Employment.Watch.NIGHT,
            isMayor, mayorSettling, mourning, boonKey, refusal);
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

    private static void send(ServerPlayer player, SettlerSnapshotPayload snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private SettlerNetwork() {
    }
}

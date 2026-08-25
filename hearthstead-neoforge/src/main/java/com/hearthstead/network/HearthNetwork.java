package com.hearthstead.network;

import com.hearthstead.entity.SettlerEntity;
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
import java.util.UUID;

/**
 * Server-side rules for the hearth screen's Mayor tab: who could take the
 * seat, what a settler would bring, and what appointing one costs.
 *
 * <p>Mirrors {@link PlaqueNetwork}'s discipline. Neither payload carries a
 * block position: the settlement is re-resolved from the acting player's own
 * position on every request instead, which is safe because the hearth screen
 * only stays open ({@code HearthMenu#stillValid}) while the player is near
 * the hearth that opened it -- so "the settlement near the player" is always
 * "the settlement whose screen is open."
 */
public final class HearthNetwork {

    /**
     * The hearth screen's Mayor tab opened, switched, scrolled, or clicked
     * Appoint -- {@code REFRESH} covers the first three (no revision check,
     * exactly {@code PlaqueAction.Kind.REFRESH}'s meaning), {@code APPOINT}
     * the last.
     */
    public static void handle(ServerPlayer player, HearthMayorAction action) {
        ServerLevel level = player.serverLevel();
        Settlement settlement = settlementOf(player);
        if (settlement == null) {
            return; // no settlement here; the screen has nothing to act on
        }
        if (action.kind() != HearthMayorAction.Kind.REFRESH
            && action.revision() != revisionOf(settlement)) {
            // Someone changed the seat while the screen was open.
            deny(player, "hearthstead.mayor.stale");
            send(player, snapshot(level, settlement));
            return;
        }

        if (action.kind() == HearthMayorAction.Kind.APPOINT) {
            appoint(player, level, settlement, action.target());
        }
        send(player, snapshot(level, settlement));
    }

    // ------------------------------------------------------------ actions --

    private static void appoint(ServerPlayer player, ServerLevel level,
                                Settlement settlement, UUID target) {
        SettlerEntity settler = findSettler(level, settlement, target);
        if (settler == null) {
            deny(player, "hearthstead.mayor.refused.nobody");
            return;
        }
        // Mayor.appoint re-checks mourning and "already the mayor" itself --
        // the revision check above is the outer, coarser guard; this is the
        // one that cannot be bypassed by any packet.
        Component refusal = Mayor.appoint(level, settlement, settler);
        if (refusal != null) {
            player.displayClientMessage(refusal, true);
        }
    }

    // ----------------------------------------------------------- snapshot --

    private static HearthMayorSnapshot snapshot(ServerLevel level, Settlement settlement) {
        SettlerEntity mayor = Mayor.find(level, settlement);
        List<HearthMayorSnapshot.Candidate> candidates = new ArrayList<>();
        for (SettlerEntity settler : Mayor.candidates(level, settlement)) {
            Mayor.Boon boon = Mayor.boonOf(settler);
            candidates.add(new HearthMayorSnapshot.Candidate(settler.getUUID(),
                settler.getSettlerName(), settler.getProfession().name(),
                boon.key(), settler.attributes().get(boon.from())));
        }
        // Whoever is furthest along in the attribute their own boon comes
        // from reads first -- the same "the server already sorted it, the
        // first row is the suggestion" idea as the plaque's hire tab.
        candidates.sort((a, b) -> Integer.compare(b.knack(), a.knack()));

        return new HearthMayorSnapshot(revisionOf(settlement),
            mayor != null, mayor != null ? mayor.getUUID() : new UUID(0, 0),
            mayor != null ? mayor.getSettlerName() : "",
            mayor != null ? Mayor.boonOf(mayor).key() : "",
            settlement.mayorSince,
            Mayor.mourning(level, settlement), settlement.mourningUntil,
            List.copyOf(candidates), true);
    }

    private static SettlerEntity findSettler(ServerLevel level, Settlement settlement, UUID id) {
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getUUID().equals(id)) {
                return settler;
            }
        }
        return null;
    }

    @Nullable
    private static Settlement settlementOf(ServerPlayer player) {
        return SettlementManager.at(player.serverLevel(), player.blockPosition());
    }

    /**
     * A synthetic revision hashed from the settlement's own mayoral fields.
     * The plaque keeps a real counter it owns on its block entity; a
     * settlement's mayoral state has no such counter, and adding one is not
     * this worker's file to touch ({@code Settlement.java}), so the three
     * fields that change on every appointment or death -- who holds the
     * seat, since when, and mourning's end -- stand in for one. Not a
     * cryptographic guarantee, exactly like the plaque's own revision is
     * not; good enough to refuse a click made against a seat that has since
     * changed.
     */
    private static int revisionOf(Settlement settlement) {
        return Objects.hash(settlement.mayorId, settlement.mayorSince, settlement.mourningUntil);
    }

    private static void deny(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static void send(ServerPlayer player, HearthMayorSnapshot snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private HearthNetwork() {
    }
}

package com.hearthstead.settlement.raid;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidBossBarMembershipTest {
    @Test void respawnPrunesOldObjectEvenWhenLogicalIdentityMatches() {
        String oldPlayer = new String("same-player-uuid");
        String respawned = new String("same-player-uuid");
        List<String> members = new ArrayList<>(List.of(oldPlayer));
        List<String> removed = new ArrayList<>();
        RaidBossBarService.pruneDisconnectedViewers(List.of(respawned),
            List.of(members), bar -> bar, (bar, viewer) -> {
                removed.add(viewer);
                bar.removeIf(member -> member == viewer);
            });
        assertEquals(1, removed.size());
        assertTrue(removed.getFirst() == oldPlayer);
        assertTrue(members.isEmpty());
        members.add(respawned);
        RaidBossBarService.pruneDisconnectedViewers(List.of(respawned),
            List.of(members), bar -> bar, (bar, viewer) -> removed.add(viewer));
        assertEquals(1, removed.size());
        assertTrue(members.getFirst() == respawned);
    }

    // Reconciliation treats player identity opaquely. A null sentinel avoids
    // constructing a server/network session; the spy intercepts every membership
    // operation before vanilla can send a packet. This is NOT native packet QA.
    private static final ServerPlayer VIEWER = null;

    @Test void unchangedSelectionDoesNotRemoveOrReaddTheViewer() {
        SpyBar bar = new SpyBar();
        for (int tick = 0; tick < 100; tick++) {
            RaidBossBarService.reconcileViewer(VIEWER, List.of(bar), bar);
        }
        assertEquals(1, bar.adds);
        assertEquals(0, bar.removes);
        assertTrue(bar.getPlayers().contains(VIEWER));
    }

    @Test void rangeExitRemovesExactlyOnceAndReentryAddsOnce() {
        SpyBar bar = new SpyBar();
        RaidBossBarService.reconcileViewer(VIEWER, List.of(bar), bar);
        RaidBossBarService.reconcileViewer(VIEWER, List.of(bar), null);
        RaidBossBarService.reconcileViewer(VIEWER, List.of(bar), null);
        assertEquals(1, bar.removes);
        assertTrue(bar.getPlayers().isEmpty());
        RaidBossBarService.reconcileViewer(VIEWER, List.of(bar), bar);
        assertEquals(2, bar.adds);
    }

    @Test void switchingRaidDetachesOldBarIncludingOtherDimension() {
        SpyBar old = new SpyBar();
        SpyBar desired = new SpyBar();
        List<ServerBossEvent> allServerBars = List.of(old, desired);
        RaidBossBarService.reconcileViewer(VIEWER, allServerBars, old);
        RaidBossBarService.reconcileViewer(VIEWER, allServerBars, desired);
        RaidBossBarService.reconcileViewer(VIEWER, allServerBars, desired);
        assertEquals(1, old.adds);
        assertEquals(1, old.removes);
        assertEquals(1, desired.adds);
        assertEquals(0, desired.removes);
        assertTrue(old.getPlayers().isEmpty());
    }

    @Test void noEligibleRaidClearsAllStaleMemberships() {
        SpyBar first = new SpyBar();
        SpyBar second = new SpyBar();
        first.addPlayer(VIEWER);
        second.addPlayer(VIEWER);
        RaidBossBarService.reconcileViewer(VIEWER, List.of(first, second), null);
        assertEquals(1, first.removes);
        assertEquals(1, second.removes);
        assertTrue(first.getPlayers().isEmpty());
        assertTrue(second.getPlayers().isEmpty());
    }

    private static final class SpyBar extends ServerBossEvent {
        private final Set<ServerPlayer> viewers = new HashSet<>();
        private int adds;
        private int removes;

        SpyBar() {
            super(Component.literal("Raid"), BossEvent.BossBarColor.RED,
                BossEvent.BossBarOverlay.PROGRESS);
        }

        @Override public Collection<ServerPlayer> getPlayers() { return viewers; }
        @Override public void addPlayer(ServerPlayer player) {
            adds++;
            viewers.add(player);
        }
        @Override public void removePlayer(ServerPlayer player) {
            removes++;
            viewers.remove(player);
        }
    }
}

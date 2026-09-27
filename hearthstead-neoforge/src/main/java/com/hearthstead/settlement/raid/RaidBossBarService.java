package com.hearthstead.settlement.raid;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.RaidParticipantRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-only controller for the authored first raid's single native bar.
 * Counts come from the sealed persisted roster minus the terminal ledger.
 */
public final class RaidBossBarService {
    public record RosterCounts(int brutes, int skirmishers) {}
    public static final int PLAYER_RANGE_MARGIN = 32;
    private static final Map<MinecraftServer, Map<RaidKey, ServerBossEvent>> EVENTS =
        new WeakHashMap<>();

    public static void tick(ServerLevel level) {
        if (level == null || Math.floorMod(level.getGameTime(), 20L) != 0L) {
            return;
        }
        MinecraftServer server = level.getServer();
        Map<RaidKey, ServerBossEvent> events = EVENTS.computeIfAbsent(server,
            ignored -> new HashMap<>());
        List<Candidate> candidates = new ArrayList<>();
        Set<RaidKey> activeKeys = new HashSet<>();

        for (Settlement settlement : SettlementSavedData.get(level)
                .settlements.values()) {
            RaidKey key = keyFor(level, settlement);
            if (key == null) {
                continue;
            }
            activeKeys.add(key);
            Candidate candidate = candidateFor(level, settlement, key);
            if (candidate != null) {
                candidates.add(candidate);
                ServerBossEvent bar = events.computeIfAbsent(key,
                    ignored -> newBar(candidate.name()));
                bar.setName(candidate.name());
                bar.setProgress(candidate.progress());
                bar.setVisible(true);
            } else {
                ServerBossEvent hidden = events.get(key);
                if (hidden != null) {
                    hidden.removeAllPlayers();
                    hidden.setVisible(false);
                }
            }
        }

        // Resolution/replacement cleanup for this exact dimension.
        List<RaidKey> stale = events.keySet().stream()
            .filter(key -> key.dimension().equals(level.dimension())
                && !activeKeys.contains(key))
            .toList();
        for (RaidKey key : stale) {
            ServerBossEvent bar = events.remove(key);
            if (bar != null) {
                bar.removeAllPlayers();
                bar.setVisible(false);
            }
        }

        // Retain unchanged subscriptions: REMOVE/ADD resets the client's
        // health interpolation even when the selected raid has not changed.
        pruneDisconnectedViewers(server.getPlayerList().getPlayers(),
            events.values(), ServerBossEvent::getPlayers,
            ServerBossEvent::removePlayer);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;
            }
            Candidate selected = selectFor(player, candidates);
            reconcileViewer(player, events.values(), selected == null
                ? null : events.get(selected.key()));
        }
        if (events.isEmpty()) {
            EVENTS.remove(server);
        }
    }

    /** Shared live reconciliation seam; operation-level tests detect packet churn. */
    static void reconcileViewer(ServerPlayer player,
                                Collection<ServerBossEvent> events,
                                ServerBossEvent desired) {
        // Include OTHER dimensions: the destination level tick must detach
        // this player from their old raid after a dimension transition.
        for (ServerBossEvent bar : events) {
            if (bar != desired && bar.getPlayers().contains(player)) {
                bar.removePlayer(player);
            }
        }
        if (desired != null && !desired.getPlayers().contains(player)) {
            desired.addPlayer(player);
        }
    }

    /** Respawn replaces the player object without changing its UUID/connection. */
    static <V, B> void pruneDisconnectedViewers(Collection<V> online,
            Collection<B> bars, Function<B, Collection<V>> members,
            BiConsumer<B, V> remove) {
        Set<V> current = Collections.newSetFromMap(new IdentityHashMap<>());
        current.addAll(online);
        for (B bar : bars) {
            for (V viewer : new ArrayList<>(members.apply(bar))) {
                if (!current.contains(viewer)) remove.accept(bar, viewer);
            }
        }
    }

    public static void forget(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Map<RaidKey, ServerBossEvent> events = EVENTS.get(player.getServer());
        if (events != null) {
            for (ServerBossEvent bar : events.values()) {
                bar.removePlayer(player);
            }
        }
    }

    public static void clearLevel(ServerLevel level) {
        if (level == null) {
            return;
        }
        Map<RaidKey, ServerBossEvent> events = EVENTS.get(level.getServer());
        if (events == null) {
            return;
        }
        List<RaidKey> owned = events.keySet().stream()
            .filter(key -> key.dimension().equals(level.dimension())).toList();
        for (RaidKey key : owned) {
            ServerBossEvent bar = events.remove(key);
            if (bar != null) {
                bar.removeAllPlayers();
                bar.setVisible(false);
            }
        }
        if (events.isEmpty()) {
            EVENTS.remove(level.getServer());
        }
    }

    public static void clear(MinecraftServer server) {
        Map<RaidKey, ServerBossEvent> events = EVENTS.remove(server);
        if (events != null) {
            for (ServerBossEvent bar : events.values()) {
                bar.removeAllPlayers();
                bar.setVisible(false);
            }
        }
    }

    /** Read-only lifecycle probes used by focused server tests. */
    public static int activeBarCount(MinecraftServer server) {
        Map<RaidKey, ServerBossEvent> events = EVENTS.get(server);
        return events == null ? 0 : events.size();
    }

    public static int viewerCount(MinecraftServer server) {
        Map<RaidKey, ServerBossEvent> events = EVENTS.get(server);
        return events == null ? 0 : events.values().stream()
            .mapToInt(bar -> bar.getPlayers().size()).sum();
    }

    /** Same sealed-roster counter authority used by the live bossbar. */
    public static RosterCounts rosterCounts(List<RaidParticipantRecord> roster,
                                            Set<UUID> terminal) {
        int brutes = 0;
        int skirmishers = 0;
        for (RaidParticipantRecord record : roster) {
            if (record.captain() || terminal.contains(record.entityId())) continue;
            if (record.build() == RaidParticipantRecord.Build.BRUTE) brutes++;
            else skirmishers++;
        }
        return new RosterCounts(brutes, skirmishers);
    }

    private static RaidKey keyFor(ServerLevel level, Settlement settlement) {
        if (settlement != null && settlement.raidLifecycle != null
            && settlement.raidLifecycle.isTimerScheduled()
            && settlement.raidLifecycle.firstState()
                == com.hearthstead.settlement.state.FirstRaidState.SCHEDULED
            && RaidDirector.firstWarningReceiptReady(settlement)) {
            return new RaidKey(level.dimension(), settlement.id,
                settlement.raidLifecycle.firstAttackNight());
        }
        if (settlement == null || settlement.raidLifecycle == null
            || !settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || !settlement.raidLifecycle.participantsTracked()
            || !settlement.raidLifecycle.participantRosterTracked()
            || settlement.raidLifecycle.integrityLost()) {
            return null;
        }
        return new RaidKey(level.dimension(), settlement.id,
            settlement.raidLifecycle.firstAttackNight());
    }

    private static Candidate candidateFor(ServerLevel level,
                                          Settlement settlement,
                                          RaidKey key) {
        if (settlement.raidLifecycle.isTimerScheduled()
            && settlement.raidLifecycle.firstState()
                == com.hearthstead.settlement.state.FirstRaidState.SCHEDULED) {
            RaidPresentation.WarningView warning = RaidPresentation.warningView(settlement).orElse(null);
            if (warning == null || !RaidDirector.firstWarningReceiptReady(settlement)) return null;
            Component name = Component.translatableWithFallback("hearthstead.raid.boss.timer_warning",
                "Raid warning: %s leads %s bandits %s", warning.captainName(),
                settlement.raidLifecycle.firstTimerBandSize(),
                RaidThreatInfo.when(warning.attackNight(), RaidDirector.nightOf(level.getDayTime())));
            double untilAttack = warning.attackNight() * 24_000.0D + 13_000.0D - level.getDayTime();
            float progress = (float) Mth.clamp(untilAttack / 24_000.0D, 0.0D, 1.0D);
            double range = settlement.radius + PLAYER_RANGE_MARGIN;
            return new Candidate(key, settlement.center.getX() + 0.5,
                settlement.center.getY() + 0.5, settlement.center.getZ() + 0.5,
                range * range, name, progress);
        }
        List<RaidParticipantRecord> roster =
            settlement.raidLifecycle.participantRoster();
        Set<UUID> terminal = settlement.raidLifecycle.terminalParticipants();
        RaidParticipantRecord captain = null;
        RosterCounts counts = rosterCounts(roster, terminal);
        for (RaidParticipantRecord record : roster) {
            if (record.captain()) {
                if (captain != null) {
                    return null;
                }
                captain = record;
            }
        }
        // 5 = a first raid sealed before the 26 Sep bandit curve, still valid after an update.
        boolean timer = settlement.raidLifecycle.isTimerScheduled();
        int expected = timer ? settlement.raidLifecycle.firstTimerBandSize() : RaidDirector.FIRST_RAID_BAND_SIZE;
        if (captain == null || roster.size() != expected && (timer || roster.size() != 5)) {
            return null;
        }
        String captainName = settlement.raidLifecycle.activePlan()
            .flatMap(plan -> RaidDirector.leaderNameOf(settlement,
                plan.captainId())).orElse(null);
        if (captainName == null) {
            return null;
        }

        boolean captainTerminal = terminal.contains(captain.entityId());
        float progress;
        Component name;
        if (captainTerminal) {
            progress = 0.0F;
            name = Component.translatable("hearthstead.raid.boss.captain_slain",
                captainName, counts.brutes(), counts.skirmishers());
        } else {
            Entity exact = level.getEntity(captain.entityId());
            if (!(exact instanceof RaiderEntity raider) || !raider.isAlive()
                || !raider.isCaptain()
                || !settlement.id.equals(raider.settlementId())) {
                // Unloaded or contradictory is not equivalent to dead/full.
                return null;
            }
            progress = Mth.clamp(raider.getHealth() / raider.getMaxHealth(),
                0.0F, 1.0F);
            String nameKey = switch (raider.rallyPhase()) {
                case CHANNELING -> "hearthstead.raid.boss.rally_channeling";
                case EMPOWERED -> "hearthstead.raid.boss.rally_empowered";
                case EXPOSED -> "hearthstead.raid.boss.rally_exposed";
                case FINAL_STAND -> "hearthstead.raid.boss.final_stand";
                default -> "hearthstead.raid.boss.active";
            };
            name = Component.translatable(nameKey, captainName, counts.brutes(),
                counts.skirmishers());
        }
        double range = settlement.radius + PLAYER_RANGE_MARGIN;
        return new Candidate(key, settlement.center.getX() + 0.5,
            settlement.center.getY() + 0.5, settlement.center.getZ() + 0.5,
            range * range, name, progress);
    }

    private static Candidate selectFor(ServerPlayer player,
                                       List<Candidate> candidates) {
        Candidate selected = null;
        double selectedDistance = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            double distance = player.distanceToSqr(candidate.x(), candidate.y(),
                candidate.z());
            if (distance > candidate.rangeSquared()) {
                continue;
            }
            if (selected == null || distance < selectedDistance
                || (Double.compare(distance, selectedDistance) == 0
                    && compareUuid(candidate.key().settlementId(),
                        selected.key().settlementId()) < 0)) {
                selected = candidate;
                selectedDistance = distance;
            }
        }
        return selected;
    }

    private static int compareUuid(UUID left, UUID right) {
        int most = Long.compareUnsigned(left.getMostSignificantBits(),
            right.getMostSignificantBits());
        return most != 0 ? most : Long.compareUnsigned(left.getLeastSignificantBits(),
            right.getLeastSignificantBits());
    }

    private static ServerBossEvent newBar(Component name) {
        ServerBossEvent bar = new ServerBossEvent(name,
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        bar.setDarkenScreen(false);
        bar.setPlayBossMusic(false);
        bar.setCreateWorldFog(false);
        return bar;
    }

    private record RaidKey(ResourceKey<Level> dimension, UUID settlementId,
                           long attackNight) {
    }

    private record Candidate(RaidKey key, double x, double y, double z,
                             double rangeSquared, Component name,
                             float progress) {
    }

    private RaidBossBarService() {
    }
}

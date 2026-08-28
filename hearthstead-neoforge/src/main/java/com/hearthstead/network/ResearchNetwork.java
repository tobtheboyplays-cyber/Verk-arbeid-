package com.hearthstead.network;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.research.Research;
import com.hearthstead.settlement.research.ResearchProject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-side rules for the research screen: what a player is shown at the
 * study's lectern, and what they are allowed to change.
 *
 * <p>Mirrors {@link PlaqueNetwork}'s discipline exactly. Every mutation
 * re-resolves the settlement and the study from the lectern's own position
 * rather than trusting anything the packet claims about them, and every
 * action re-sends a fresh snapshot so the screen never shows a decision it
 * is not certain actually landed.
 */
public final class ResearchNetwork {

    /** How far a player may stand from the lectern and still use it. */
    private static final double REACH_SQUARED = 8.0 * 8.0;

    /** Opened by {@code ResearchEvents} on a validated right-click.
     *  {@code lecternPos} becomes the payload's own identity, the same
     *  position every later action is checked against. */
    public static void open(ServerPlayer player, Settlement settlement, Building study,
                            net.minecraft.core.BlockPos lecternPos) {
        send(player, snapshot(player, settlement, study, lecternPos, Optional.empty()));
    }

    public static void handle(ServerPlayer player, ResearchActionPayload action) {
        ServerLevel level = player.serverLevel();
        Settlement settlement = SettlementManager.at(level, action.pos());
        if (settlement == null) {
            return; // no settlement here any more; the screen closes itself
        }
        Building study = Research.studyAt(settlement, action.pos());
        if (study == null) {
            return; // the study is gone or was never registered here
        }
        if (player.distanceToSqr(action.pos().getX() + 0.5, action.pos().getY() + 0.5,
            action.pos().getZ() + 0.5) > REACH_SQUARED) {
            deny(player, "hearthstead.research.too_far");
            send(player, snapshot(player, settlement, study, action.pos(), Optional.empty()));
            return;
        }
        if (action.kind() != ResearchActionPayload.Kind.REFRESH
            && action.revision() != Research.revisionOf(level, settlement.id)) {
            // Someone else started, finished or cancelled the project while
            // the screen was open.
            deny(player, "hearthstead.research.stale");
            send(player, snapshot(player, settlement, study, action.pos(), Optional.empty()));
            return;
        }

        Optional<Component> refusal = Optional.empty();
        switch (action.kind()) {
            case START -> {
                ResearchProject project = ResearchProject.byOrdinal(action.projectOrdinal());
                if (project == null) {
                    refusal = Optional.of(Component.translatable(
                        "hearthstead.research.refused.materials"));
                } else {
                    Research.Refusal refused = Research.start(level, settlement, study, project);
                    if (refused != null) {
                        refusal = Optional.of(Component.translatable(refused.key()));
                    }
                }
            }
            case CANCEL -> Research.cancel(level, settlement, study);
            case REFRESH -> {
            }
        }
        send(player, snapshot(player, settlement, study, action.pos(), refusal));
    }

    // ----------------------------------------------------------- snapshot --

    private static ResearchSnapshotPayload snapshot(ServerPlayer player, Settlement settlement,
                                                     Building study, net.minecraft.core.BlockPos pos,
                                                     Optional<Component> refusal) {
        ServerLevel level = player.serverLevel();
        com.hearthstead.settlement.research.ResearchState state =
            Research.of(level, settlement.id);
        SettlerEntity scholar = Research.scholarOf(level, settlement, study);

        List<Integer> completedOrdinals = new ArrayList<>();
        for (ResearchProject project : state.completed) {
            completedOrdinals.add(project.ordinal());
        }

        return new ResearchSnapshotPayload(pos, Research.revisionOf(level, settlement.id),
            true, scholar == null ? "" : scholar.getSettlerName(),
            state.active == null ? -1 : state.active.project.ordinal(),
            state.active == null ? 0 : state.active.sessions,
            List.copyOf(completedOrdinals),
            Research.haveCounts(level, settlement, study),
            refusal);
    }

    private static void deny(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static void send(ServerPlayer player, ResearchSnapshotPayload snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private ResearchNetwork() {
    }
}

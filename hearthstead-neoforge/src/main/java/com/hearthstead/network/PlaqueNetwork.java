package com.hearthstead.network;

import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side rules for the plaque screen: what a player is shown, and what
 * they are allowed to change.
 *
 * <p>Every mutation re-checks the world from scratch — the plaque still
 * exists, the player is close enough and in the same dimension, the building
 * is still valid, the settler is still real and still eligible. None of that
 * is taken from the packet, because the packet comes from a client.
 */
public final class PlaqueNetwork {

    /** How far a player may stand from a plaque and still manage it. */
    private static final double REACH_SQUARED = 8.0 * 8.0;

    public static void openFor(ServerPlayer player, PlaqueBlockEntity plaque) {
        send(player, snapshot(player, plaque));
    }

    public static void handle(ServerPlayer player, PlaqueAction action) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(action.pos())
            || !(level.getBlockEntity(action.pos()) instanceof PlaqueBlockEntity plaque)) {
            return; // the plaque is gone; the screen will close itself
        }
        if (player.distanceToSqr(action.pos().getX() + 0.5, action.pos().getY() + 0.5,
            action.pos().getZ() + 0.5) > REACH_SQUARED) {
            deny(player, "hearthstead.plaque.too_far");
            return;
        }
        if (action.kind() != PlaqueAction.Kind.REFRESH
            && action.revision() != plaque.revision()) {
            // Someone changed this building while the screen was open.
            deny(player, "hearthstead.plaque.stale");
            send(player, snapshot(player, plaque));
            return;
        }

        switch (action.kind()) {
            case ASSIGN -> assign(player, level, plaque, action.target());
            case EVICT -> evict(player, level, plaque, action.target());
            case REFRESH -> plaque.survey(level);
        }
        send(player, snapshot(player, plaque));
    }

    // ------------------------------------------------------------ actions --

    private static void assign(ServerPlayer player, ServerLevel level,
                               PlaqueBlockEntity plaque, UUID settlerId) {
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);
        if (building == null || settlement == null || !building.valid) {
            deny(player, "hearthstead.plaque.not_ready");
            return;
        }
        SettlerEntity settler = findSettler(level, settlement, settlerId);
        if (settler == null) {
            deny(player, "hearthstead.plaque.settler_gone");
            return;
        }
        if (plaque.type().employsWorkers() && !plaque.type().housesResidents()) {
            if (building.workers.size() >= plaque.type().workerCapacity()) {
                deny(player, "hearthstead.plaque.no_room");
                return;
            }
            if (building.workers.contains(settlerId)) {
                return; // already here; a double-click is not an error
            }
            building.workers.add(settlerId);
        } else {
            BlockPos bed = freeBed(level, settlement, building);
            if (bed == null) {
                deny(player, "hearthstead.plaque.no_room");
                return;
            }
            // Moving house is allowed, but never silently: the old bed is
            // released explicitly so the previous home's count is correct.
            settler.releaseBed();
            settler.claimBed(bed);
        }
        SettlementSavedData.get(level).setDirty();
    }

    private static void evict(ServerPlayer player, ServerLevel level,
                              PlaqueBlockEntity plaque, UUID settlerId) {
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);
        if (building == null || settlement == null) {
            deny(player, "hearthstead.plaque.not_ready");
            return;
        }
        building.workers.remove(settlerId);
        SettlerEntity settler = findSettler(level, settlement, settlerId);
        if (settler != null && settler.getClaimedBed() != null
            && building.beds.contains(settler.getClaimedBed())) {
            // Eviction takes the bed and nothing else: no profession change,
            // no equipment loss, no teleport.
            settler.releaseBed();
            settler.addMorale(-4.0F);
        }
        SettlementSavedData.get(level).setDirty();
    }

    // ----------------------------------------------------------- snapshot --

    private static PlaqueSnapshot snapshot(ServerPlayer player, PlaqueBlockEntity plaque) {
        ServerLevel level = player.serverLevel();
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);

        List<PlaqueSnapshot.RequirementLine> requirements = new ArrayList<>();
        for (Requirement.Status status : plaque.lastSurvey()) {
            requirements.add(new PlaqueSnapshot.RequirementLine(
                status.requirement().id(), status.have(), status.needed()));
        }

        List<PlaqueSnapshot.Occupant> occupants = new ArrayList<>();
        List<PlaqueSnapshot.Candidate> candidates = new ArrayList<>();
        int capacity = 0;

        if (settlement != null && building != null) {
            capacity = plaque.type().housesResidents()
                ? Math.min(plaque.type().residentCapacity(), building.beds.size())
                : plaque.type().workerCapacity();
            for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
                boolean worker = building.workers.contains(settler.getUUID());
                boolean resident = settler.getClaimedBed() != null
                    && building.beds.contains(settler.getClaimedBed());
                if (worker || resident) {
                    occupants.add(new PlaqueSnapshot.Occupant(settler.getUUID(),
                        settler.getSettlerName(), settler.getProfession().name(),
                        settler.getHealth(), settler.getMaxHealth(),
                        Math.round(settler.getMorale()), worker));
                } else {
                    candidates.add(new PlaqueSnapshot.Candidate(settler.getUUID(),
                        settler.getSettlerName(), settler.getProfession().name(),
                        settler.getClaimedBed() != null,
                        (int) Math.sqrt(settler.blockPosition().distSqr(plaque.getBlockPos())),
                        blockedReason(plaque, building, settler)));
                }
            }
        }

        return new PlaqueSnapshot(plaque.getBlockPos(), plaque.type().id(),
            plaque.state().id(), plaque.revision(),
            building == null ? 1 : building.level,
            List.copyOf(requirements), List.copyOf(occupants), List.copyOf(candidates),
            capacity, mayManage(player, settlement));
    }

    /** Empty when the settler could move in; otherwise why they cannot. */
    private static String blockedReason(PlaqueBlockEntity plaque, Building building,
                                        SettlerEntity settler) {
        if (plaque.state() != PlaqueState.LINKED) {
            return "hearthstead.plaque.blocked.not_ready";
        }
        if (plaque.type().housesResidents()) {
            return building.beds.size() <= countHoused(building, settler.level())
                ? "hearthstead.plaque.blocked.full" : "";
        }
        return building.workers.size() >= plaque.type().workerCapacity()
            ? "hearthstead.plaque.blocked.full" : "";
    }

    private static int countHoused(Building building, net.minecraft.world.level.Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        int housed = 0;
        for (SettlerEntity settler : serverLevel.getEntitiesOfClass(SettlerEntity.class,
            new net.minecraft.world.phys.AABB(building.bounds.minX() - 32,
                building.bounds.minY() - 16, building.bounds.minZ() - 32,
                building.bounds.maxX() + 32, building.bounds.maxY() + 16,
                building.bounds.maxZ() + 32))) {
            if (settler.getClaimedBed() != null
                && building.beds.contains(settler.getClaimedBed())) {
                housed++;
            }
        }
        return housed;
    }

    // -------------------------------------------------------------- utils --

    private static BlockPos freeBed(ServerLevel level, Settlement settlement,
                                    Building building) {
        BlockPos free = BuildingManager.findFreeBed(level, settlement);
        return free != null && building.beds.contains(free) ? free : firstUnclaimed(level,
            settlement, building);
    }

    private static BlockPos firstUnclaimed(ServerLevel level, Settlement settlement,
                                           Building building) {
        List<BlockPos> claimed = new ArrayList<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getClaimedBed() != null) {
                claimed.add(settler.getClaimedBed());
            }
        }
        for (BlockPos bed : building.beds) {
            if (!claimed.contains(bed)) {
                return bed;
            }
        }
        return null;
    }

    private static SettlerEntity findSettler(ServerLevel level, Settlement settlement,
                                             UUID id) {
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getUUID().equals(id)) {
                return settler;
            }
        }
        return null;
    }

    /**
     * Who may change this building. Single-player and co-op villages share
     * one settlement, so anyone who can reach the plaque may manage it; the
     * hook exists so a future permission model has one place to live.
     */
    private static boolean mayManage(ServerPlayer player, Settlement settlement) {
        return settlement != null;
    }

    private static void deny(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static void send(ServerPlayer player, PlaqueSnapshot snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private PlaqueNetwork() {
    }
}

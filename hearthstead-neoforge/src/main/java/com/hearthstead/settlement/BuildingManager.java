package com.hearthstead.settlement;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the settlement's buildings honest, and does the bed bookkeeping the
 * rest of the mod asks for.
 *
 * <p>It no longer discovers buildings. Surveying belongs to the plaque that
 * declared each one — that is what makes a plaque meaningful and what keeps
 * scanning bounded, since only a hung plaque can cause one. What is left here
 * is the work no single plaque can do: noticing that a plaque has been
 * destroyed (a raider's axe, an explosion, a careless pickaxe) and dissolving
 * the building it stood for.
 */
public final class BuildingManager {

    /** One building is checked for its plaque every this many ticks. */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /** How far a block change can be from a plaque and still concern it. */
    private static final int NUDGE_RADIUS = 32;

    private int sweepCursor;
    private int buildingsDissolved;
    /**
     * Every plaque currently loaded in this level. Transient and rebuilt from
     * block-entity load, so it can never disagree with the world for long. It
     * exists so a block change can find the plaques that might care about it
     * without searching the world — a village has tens of plaques, not
     * thousands of candidate positions.
     */
    private final Set<BlockPos> knownPlaques = new HashSet<>();

    public void registerPlaque(BlockPos pos) {
        knownPlaques.add(pos.immutable());
    }

    public void forgetPlaque(BlockPos pos) {
        knownPlaques.remove(pos);
    }

    /**
     * A block changed. Re-survey the plaques near enough to care — including
     * plaques with no building yet, which is exactly the case where feedback
     * matters most: the player has just placed the bed or the final lantern
     * and wants the plaque to turn green now, not in ten seconds.
     */
    public void nudgeNear(ServerLevel level, BlockPos pos) {
        if (knownPlaques.isEmpty()) {
            return;
        }
        for (BlockPos plaquePos : List.copyOf(knownPlaques)) {
            if (plaquePos.distSqr(pos) > (double) NUDGE_RADIUS * NUDGE_RADIUS) {
                continue;
            }
            if (!level.isLoaded(plaquePos)) {
                continue;
            }
            if (level.getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque) {
                plaque.survey(level);
            } else {
                knownPlaques.remove(plaquePos);
            }
        }
    }

    /** Diagnostics for the QA evidence trail and the in-game debug command. */
    public String stats() {
        return "dissolvedByLostPlaque=" + buildingsDissolved;
    }

    /** Called once per server-level tick from the level tick event. */
    public void tick(ServerLevel level, SettlementSavedData data) {
        if (level.getGameTime() % SWEEP_INTERVAL_TICKS != 0) {
            return;
        }
        List<Building> all = new ArrayList<>();
        List<Settlement> owners = new ArrayList<>();
        for (Settlement settlement : data.settlements.values()) {
            for (Building building : settlement.buildings) {
                all.add(building);
                owners.add(settlement);
            }
        }
        if (all.isEmpty()) {
            return;
        }
        sweepCursor = (sweepCursor + 1) % all.size();
        Building building = all.get(sweepCursor);
        Settlement owner = owners.get(sweepCursor);
        if (!level.isLoaded(building.plaquePos)) {
            return; // out of sight; judge it when its chunk is back
        }
        if (!(level.getBlockState(building.plaquePos).getBlock() instanceof PlaqueBlock)) {
            // The declaring plaque is gone — torn down, blown up, or mined.
            dissolve(level, owner, building, data);
        }
    }

    /**
     * Removes a building whose plaque no longer exists, and turns out anyone
     * who lived there. Called by the sweep rather than by the block break so
     * that explosions and world edits are covered too, not just a player with
     * a pickaxe.
     */
    private void dissolve(ServerLevel level, Settlement settlement, Building building,
                          SettlementSavedData data) {
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            BlockPos bed = settler.getClaimedBed();
            if (bed != null && building.beds.contains(bed)) {
                settler.releaseBed();
                settler.addMorale(-6.0F);
            }
        }
        settlement.buildings.remove(building);
        buildingsDissolved++;
        data.setDirty();
        broadcastNear(level, building.plaquePos,
            Component.translatable("hearthstead.message.home_lost", settlement.name));
    }

    /**
     * Asks every plaque whose building contains {@code pos} to look again.
     * This is what makes placing the last lantern feel immediate rather than
     * making the player wait for the next survey tick.
     */
    public void nudgeBuildingsAt(ServerLevel level, SettlementSavedData data, BlockPos pos) {
        for (Settlement settlement : data.settlements.values()) {
            for (Building building : settlement.buildings) {
                if (building.contains(pos) && level.isLoaded(building.plaquePos)
                    && level.getBlockEntity(building.plaquePos)
                        instanceof PlaqueBlockEntity plaque) {
                    plaque.survey(level);
                }
            }
        }
    }

    private void broadcastNear(ServerLevel level, BlockPos pos, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= 64 * 64) {
                player.displayClientMessage(message, false);
            }
        }
    }

    // ------------------------------------------------------------- beds ---

    /** A free, valid bed in this settlement (not claimed by a living member). */
    @Nullable
    public static BlockPos findFreeBed(ServerLevel level, Settlement settlement) {
        Set<BlockPos> claimed = new HashSet<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getClaimedBed() != null) {
                claimed.add(settler.getClaimedBed());
            }
        }
        for (Building building : settlement.buildings) {
            if (!building.valid) {
                continue;
            }
            for (BlockPos bed : building.beds) {
                if (!claimed.contains(bed)) {
                    return bed;
                }
            }
        }
        return null;
    }

    /**
     * Hands this building's free beds to loaded settlers without one. Housing
     * is push-driven: the moment a home becomes valid its beds are offered,
     * so nobody sleeps by the hearth while an empty bed stands ready.
     */
    public void assignFreeBeds(ServerLevel level, Settlement settlement, Building building) {
        if (!building.valid || building.beds.isEmpty()) {
            return;
        }
        List<SettlerEntity> members = SettlementManager.loadedMembers(level, settlement);
        Set<BlockPos> claimed = new HashSet<>();
        for (SettlerEntity member : members) {
            if (member.getClaimedBed() != null) {
                claimed.add(member.getClaimedBed());
            }
        }
        for (BlockPos bed : building.beds) {
            if (claimed.contains(bed)) {
                continue;
            }
            for (SettlerEntity member : members) {
                if (member.getClaimedBed() == null) {
                    member.claimBed(bed);
                    claimed.add(bed);
                    break;
                }
            }
        }
    }

    /** The building quality backing a claimed bed, or 0 when homeless. */
    public static int homeQualityFor(Settlement settlement, @Nullable BlockPos bed) {
        if (bed == null) {
            return 0;
        }
        for (Building building : settlement.buildings) {
            if (building.valid && building.beds.contains(bed)) {
                return building.quality();
            }
        }
        return 0;
    }
}

package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * The validation boundary between real gameplay events and the pure monotonic
 * {@link com.hearthstead.settlement.state.FoundingJourney} record.
 *
 * <p>Every hook supplies the live objects it just committed. This class
 * re-checks exact settlement/building/worker/Hearth identity in O(1) or one
 * bounded settlement-list lookup, advances at most once, and dirties SavedData
 * only when the authoritative phase actually changed.
 */
public final class FoundingJourneyProgress {

    public static boolean noteLumberCampLinked(ServerLevel level,
                                                Settlement settlement,
                                                Building building) {
        boolean journeyV3 = JourneyServerHooks.noteBuildingLinked(
            level, settlement, building);
        if (level == null || settlement == null
            || settlement.foundingJourney.phase()
                != FoundingJourney.Phase.BUILD_LUMBER_CAMP
            || !exactRegisteredBuilding(settlement, building)
            || !building.valid || building.type != BuildingType.LUMBER_CAMP) {
            return journeyV3;
        }
        return commit(level, settlement,
            settlement.foundingJourney.noteLumberCampLinked()) || journeyV3;
    }

    public static boolean noteLumbererHired(ServerLevel level,
                                             Settlement settlement,
                                             Building building,
                                             SettlerEntity settler) {
        if (level == null || settlement == null || settler == null
            || settlement.foundingJourney.phase()
                != FoundingJourney.Phase.HIRE_LUMBERER
            || !exactRegisteredBuilding(settlement, building)
            || !building.valid || building.type != BuildingType.LUMBER_CAMP
            || !building.workers.contains(settler.getUUID())
            || !Objects.equals(settler.getSettlementId(), settlement.id)
            || settler.getProfession() != Profession.LUMBERER) {
            return false;
        }
        return commit(level, settlement,
            settlement.foundingJourney.noteLumbererHired());
    }

    /**
     * Advances only from the compare-and-committed authoritative zone path.
     * Preview, cancel, stale packets and zones for another building/type can
     * never teach the first-log step.
     */
    public static boolean noteWorkZoneCommitted(ServerLevel level,
                                                Settlement settlement,
                                                Building building,
                                                WorkZone zone) {
        boolean journeyV3 = JourneyServerHooks.noteWorkZoneCommitted(
            level, settlement, building, zone);
        if (level == null || settlement == null || zone == null
            || settlement.foundingJourney.phase()
                != FoundingJourney.Phase.SET_LUMBER_ZONE
            || zone.type() != WorkZone.Type.LUMBER
            || !zone.settlementId().equals(settlement.id)
            || !exactRegisteredBuilding(settlement, building)
            || !zone.buildingId().equals(building.id)
            || !building.valid || building.type != BuildingType.LUMBER_CAMP
            || !zone.equals(building.workZone().orElse(null))
            || building.workZoneRevision() != zone.revision()) {
            return journeyV3;
        }
        return commit(level, settlement,
            settlement.foundingJourney.noteLumberZoneCommitted()) || journeyV3;
    }

    public static boolean noteLogDelivered(ServerLevel level,
                                            Settlement settlement,
                                            HearthBlockEntity hearth,
                                            SettlerEntity settler,
                                            ItemStack offeredStack,
                                            int insertedCount) {
        // This is a permanent lumberer hot path. Once the one-time journey
        // phase has moved on, return before inventory, employer or building
        // validation so every later log delivery is a single O(1) state read.
        if (level == null || settlement == null || hearth == null
            || settler == null || settlement.foundingJourney.phase()
                != FoundingJourney.Phase.DELIVER_FIRST_LOG
            || offeredStack == null || offeredStack.isEmpty()
            || !offeredStack.is(ItemTags.LOGS)
            || insertedCount <= 0 || insertedCount > offeredStack.getCount()
            || hearth.getLevel() != level
            || !hearth.getBlockPos().equals(settlement.center)
            || !Objects.equals(hearth.getSettlementId(), settlement.id)
            || !Objects.equals(settler.getSettlementId(), settlement.id)
            || !Objects.equals(settler.getHearthPos(), settlement.center)
            || settler.getProfession() != Profession.LUMBERER) {
            return false;
        }
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        if (!exactRegisteredBuilding(settlement, employer)
            || !employer.valid || employer.type != BuildingType.LUMBER_CAMP) {
            return false;
        }
        return commit(level, settlement,
            settlement.foundingJourney.noteFirstLogDelivered());
    }

    /**
     * Workplace-storage form of the same milestone. Lumber now belongs in
     * the lumber camp's physical chest so a courier can collect it; routing
     * it through the communal Hearth merely to advance onboarding would
     * teach the obsolete loop.
     */
    public static boolean noteLogStored(ServerLevel level,
                                        Settlement settlement,
                                        Building lumberCamp,
                                        SettlerEntity settler,
                                        ItemStack offeredStack,
                                        int insertedCount) {
        if (level == null || settlement == null || settler == null
            || settlement.foundingJourney.phase()
                != FoundingJourney.Phase.DELIVER_FIRST_LOG
            || offeredStack == null || offeredStack.isEmpty()
            || !offeredStack.is(ItemTags.LOGS)
            || insertedCount <= 0 || insertedCount > offeredStack.getCount()
            || !exactRegisteredBuilding(settlement, lumberCamp)
            || !lumberCamp.valid || lumberCamp.type != BuildingType.LUMBER_CAMP
            || !lumberCamp.workers.contains(settler.getUUID())
            || !Objects.equals(settler.getSettlementId(), settlement.id)
            || settler.getProfession() != Profession.LUMBERER
            || Employment.employerOf(settlement, settler.getUUID()) != lumberCamp) {
            return false;
        }
        // Seal proof before completing the Journey. This makes the two state
        // transitions one fail-closed authority decision: a second Lumberer
        // may still store useful logs, but cannot consume the one-time quest
        // transition while the emblem ledger names another live worker.
        boolean proof = settlement.firstRaidReadiness.noteStoredProduction(
            level, settlement, lumberCamp, settler, offeredStack, insertedCount);
        if (!proof) {
            return false;
        }
        boolean changed = settlement.foundingJourney.noteFirstLogDelivered();
        return commit(level, settlement, proof || changed);
    }

    private static boolean exactRegisteredBuilding(Settlement settlement,
                                                    Building building) {
        if (settlement == null || building == null) {
            return false;
        }
        for (Building registered : settlement.buildings) {
            if (registered == building && registered.id.equals(building.id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean commit(ServerLevel level, Settlement settlement,
                                  boolean changed) {
        if (changed) {
            SettlementManager.data(level).setDirty();
            present(level, settlement);
        }
        return changed;
    }

    /**
     * Three lifetime events may fan out to nearby players; nothing polls and
     * no entity/building scan runs per tick. The same gold-accent rhythm as
     * the Journey panel makes progress readable even while its screen is shut.
     */
    private static void present(ServerLevel level, Settlement settlement) {
        var phase = settlement.foundingJourney.phase();
        boolean complete = phase
            == com.hearthstead.settlement.state.FoundingJourney.Phase.COMPLETE;
        level.playSound(null, settlement.center,
            complete ? SoundEvents.PLAYER_LEVELUP : SoundEvents.EXPERIENCE_ORB_PICKUP,
            SoundSource.BLOCKS, complete ? 0.65F : 0.45F,
            complete ? 1.15F : 1.35F);
        level.sendParticles(ParticleTypes.END_ROD,
            settlement.center.getX() + 0.5D,
            settlement.center.getY() + 1.15D,
            settlement.center.getZ() + 0.5D,
            complete ? 14 : 6, 0.7D, 0.45D, 0.7D, 0.015D);
        Component message = Component.translatable(
            "hearthstead.journey.progress." + phase.id());
        double range = settlement.radius + 32.0D;
        double rangeSqr = range * range;
        for (var player : level.players()) {
            if (player.blockPosition().distSqr(settlement.center) <= rangeSqr) {
                player.displayClientMessage(message, true);
            }
        }
    }

    private FoundingJourneyProgress() {
    }
}

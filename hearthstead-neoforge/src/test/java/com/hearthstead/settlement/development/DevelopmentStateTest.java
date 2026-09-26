package com.hearthstead.settlement.development;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentStateTest {

    @Test
    void armTheWatchOwnsBaselineArcherDefenceWhileShieldOwnsNoBaseline() {
        assertEquals(java.util.List.of(BuildingType.WATCHTOWER),
            DevelopmentNode.ARM_THE_WATCH.buildings());
        assertEquals(java.util.List.of(Profession.ARCHER),
            DevelopmentNode.ARM_THE_WATCH.professions());
        assertTrue(DevelopmentNode.ARM_THE_WATCH.knowledge().buildPlans()
            .contains(BuildingType.WATCHTOWER));
        assertTrue(DevelopmentNode.ARM_THE_WATCH.knowledge().jobEmblems()
            .contains(Profession.ARCHER));

        JobEmblemCatalog.Entry archer = JobEmblemCatalog.forProfession(
            Profession.ARCHER);
        assertNotNull(archer);
        assertEquals(DevelopmentNode.ARM_THE_WATCH, archer.unlock());

        // Shield still owns no BASELINE defence (no plans, no Guard/Archer):
        // since BATTLE-ROLES it teaches only the two specialist blades, whose
        // halls open through RoleUnlocks (plan/BATTLE-ROLES.md section 5).
        assertTrue(DevelopmentNode.SHIELD_DOCTRINE.buildings().isEmpty());
        assertEquals(java.util.List.of(Profession.SPEARMAN, Profession.LONGSWORDSMAN),
            DevelopmentNode.SHIELD_DOCTRINE.professions());
        assertTrue(DevelopmentNode.SHIELD_DOCTRINE.knowledge().buildPlans()
            .isEmpty());
        assertEquals(java.util.List.of(Profession.SPEARMAN, Profession.LONGSWORDSMAN),
            DevelopmentNode.SHIELD_DOCTRINE.knowledge().jobEmblems());
    }

    @Test
    void watchWireIdsAndPrerequisitesRemainStable() {
        assertEquals(6, DevelopmentNode.FIRST_WATCH.wireId());
        assertEquals(7, DevelopmentNode.FIRST_RAID_AFTERMATH.wireId());
        assertEquals(8, DevelopmentNode.ARM_THE_WATCH.wireId());
        assertEquals(10, DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES.wireId());
        assertEquals(java.util.List.of("first_watch"),
            DevelopmentNode.ARM_THE_WATCH.prerequisites());
        assertEquals(java.util.List.of("arm_the_watch"),
            DevelopmentNode.FIRST_RAID_AFTERMATH.prerequisites());
        assertEquals(9, DevelopmentNode.HOME.wireId());
        assertEquals(java.util.List.of("timber_rights"),
            DevelopmentNode.STORES_AND_ROADS.prerequisites());
        assertEquals(java.util.List.of("stores_and_roads"),
            DevelopmentNode.CULTIVATED_GROUND.prerequisites());
        assertEquals(java.util.List.of("shelter"),
            DevelopmentNode.HOME.prerequisites());
        assertEquals(java.util.List.of("home"),
            DevelopmentNode.HOSPITALITY.prerequisites());
    }

    @Test
    void tutorialTrunkIsUniqueAcyclicAndWarehouseFirst() {
        java.util.List<DevelopmentNode> exactTrunk = java.util.List.of(
            DevelopmentNode.SETTLEMENT_CHARTER,
            DevelopmentNode.SHELTER,
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.SHORE_PROVISIONS,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.ARM_THE_WATCH,
            DevelopmentNode.FIRST_RAID_AFTERMATH);
        assertEquals(exactTrunk, java.util.Arrays.asList(
            DevelopmentNode.PRESENTATION_ORDER).subList(0, exactTrunk.size()));

        java.util.Set<Integer> wires = new java.util.HashSet<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            assertTrue(wires.add(node.wireId()), "duplicate wire " + node.wireId());
            assertTrue(ids.add(node.id()), "duplicate id " + node.id());
            for (String prerequisite : node.prerequisites()) {
                assertNotNull(DevelopmentNode.byId(prerequisite));
                assertTrue(seen.contains(prerequisite),
                    node.id() + " points forward or forms a cycle: " + prerequisite);
            }
            seen.add(node.id());
        }
    }

    @Test
    void schemaThreeMigrationPreservesHouseAndRepairsOldFarmBeforeWarehouse() {
        DevelopmentState legacy = new DevelopmentState();
        legacy.unlock(DevelopmentNode.TIMBER_RIGHTS);
        legacy.unlock(DevelopmentNode.CULTIVATED_GROUND);
        CompoundTag tag = legacy.writeNbt();
        tag.putInt("Schema", 3);
        tag.remove("LegacyHouseEntitlement");

        DevelopmentState migrated = DevelopmentState.readNbt(tag);
        assertFalse(migrated.quarantined());
        assertTrue(migrated.legacyHouseEntitlement());
        assertTrue(migrated.unlocked(DevelopmentNode.STORES_AND_ROADS));
        assertTrue(migrated.unlocked(DevelopmentNode.CULTIVATED_GROUND));
        assertFalse(migrated.unlocked(DevelopmentNode.HOME));

        DevelopmentState rewritten = DevelopmentState.readNbt(migrated.writeNbt());
        assertFalse(rewritten.quarantined());
        assertTrue(rewritten.legacyHouseEntitlement());
    }

    @Test
    void legacyHospitalityLearnsHomeButFreshStateDoesNot() {
        DevelopmentState legacy = throughFirstWatch();
        CompoundTag tag = legacy.writeNbt();
        tag.putInt("Schema", 3);
        tag.remove("LegacyHouseEntitlement");
        DevelopmentState migrated = DevelopmentState.readNbt(tag);
        assertFalse(migrated.quarantined());
        assertTrue(migrated.unlocked(DevelopmentNode.HOME));

        DevelopmentState fresh = new DevelopmentState();
        assertTrue(fresh.unlocked(DevelopmentNode.SHELTER));
        assertFalse(fresh.unlocked(DevelopmentNode.HOME));
        assertFalse(fresh.legacyHouseEntitlement());
    }

    @Test
    void schemaTwoFirstWatchBecomesOneReachableArmTheWatchClaim() {
        DevelopmentState legacy = throughFirstWatch();
        CompoundTag tag = legacy.writeNbt();
        tag.putInt("Schema", 2);
        tag.getCompound("QuestCounters").remove("GuardEquipmentDeliveries");
        tag.remove("SeenGuardEquipmentRequests");

        DevelopmentState migrated = DevelopmentState.readNbt(tag);
        assertFalse(migrated.quarantined());
        assertTrue(migrated.unlocked(DevelopmentNode.FIRST_WATCH));
        assertFalse(migrated.unlocked(DevelopmentNode.ARM_THE_WATCH));
        assertEquals(1, migrated.counterProgress(
            DevelopmentNode.ARM_THE_WATCH,
            DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES));

        DevelopmentState rewritten = DevelopmentState.readNbt(migrated.writeNbt());
        assertFalse(rewritten.quarantined());
        assertFalse(rewritten.unlocked(DevelopmentNode.ARM_THE_WATCH));
        assertEquals(1, rewritten.counterProgress(
            DevelopmentNode.ARM_THE_WATCH,
            DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES));
    }

    @Test
    void impossibleOldNodeAndMissingCurrentLedgerFailClosed() {
        DevelopmentState impossible = throughFirstWatch();
        impossible.unlock(DevelopmentNode.ARM_THE_WATCH);
        CompoundTag oldTag = impossible.writeNbt();
        oldTag.putInt("Schema", 2);
        oldTag.getCompound("QuestCounters").remove("GuardEquipmentDeliveries");
        oldTag.remove("SeenGuardEquipmentRequests");
        assertTrue(DevelopmentState.readNbt(oldTag).quarantined());

        CompoundTag currentDamage = throughFirstWatch().writeNbt();
        currentDamage.remove("SeenGuardEquipmentRequests");
        assertTrue(DevelopmentState.readNbt(currentDamage).quarantined());

        CompoundTag missingDeliveryLedger = throughFirstWatch().writeNbt();
        missingDeliveryLedger.remove("PendingPlayerDeliveries");
        assertTrue(DevelopmentState.readNbt(missingDeliveryLedger).quarantined(),
            "current paid-output ownership cannot migrate from a missing outbox");
    }

    @Test
    void obsoleteHomeFarmBaselineStaysInertAcrossReloadWhileUnknownNodeFailsClosed() {
        CompoundTag historical = throughFirstWatch().writeNbt();
        historical.getCompound("QuestCounters").putInt("FarmCropsStored", 37);
        CompoundTag baseline = new CompoundTag();
        baseline.putString("Key", "home/farm_crops_stored");
        baseline.putInt("Value", 0);
        historical.getList("QuestBaselines", Tag.TAG_COMPOUND).add(baseline);

        DevelopmentState migrated = DevelopmentState.readNbt(historical);
        assertFalse(migrated.quarantined());
        assertTrue(migrated.unlocked(DevelopmentNode.HOME));
        assertEquals(37, migrated.counter(DevelopmentObjective.FARM_CROPS_STORED));

        CompoundTag rewritten = migrated.writeNbt();
        DevelopmentState reloaded = DevelopmentState.readNbt(rewritten);
        assertFalse(reloaded.quarantined());
        assertTrue(reloaded.unlocked(DevelopmentNode.HOME));
        assertEquals(37, reloaded.counter(DevelopmentObjective.FARM_CROPS_STORED));

        rewritten.getList("Unlocked", Tag.TAG_STRING).add(
            StringTag.valueOf("forged_unknown_development_node"));
        assertTrue(DevelopmentState.readNbt(rewritten).quarantined(),
            "only the exact retired Home baseline may migrate; unknown knowledge remains corrupt");
    }

    private static DevelopmentState throughFirstWatch() {
        DevelopmentState state = new DevelopmentState();
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.CULTIVATED_GROUND);
        state.unlock(DevelopmentNode.HOME);
        state.unlock(DevelopmentNode.HOSPITALITY);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        return state;
    }
}

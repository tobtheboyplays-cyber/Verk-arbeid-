package com.hearthstead.settlement.development;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.entity.Profession;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OvernightB4DevelopmentTest {
    @Test
    void populationIsLiveWhileHousingAndHistoricalObjectiveIdsKeepTheirMeaning() {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Unhoused", BlockPos.ZERO);
        UUID departing = UUID.randomUUID();
        settlement.putRecord(UUID.randomUUID(), "A", Profession.NONE);
        settlement.putRecord(UUID.randomUUID(), "B", Profession.NONE);
        settlement.putRecord(departing, "C", Profession.NONE);
        DevelopmentState state = new DevelopmentState();
        assertEquals(3, DevelopmentQuests.upgradeGateProgress(null, settlement, state, DevelopmentObjective.POPULATION));
        assertEquals(0, DevelopmentQuests.upgradeGateProgress(null, settlement, state, DevelopmentObjective.HOUSED_SETTLERS));
        settlement.removeRecord(departing);
        assertEquals(2, DevelopmentQuests.upgradeGateProgress(null, settlement, state, DevelopmentObjective.POPULATION));
        assertSame(DevelopmentObjective.HOUSED_SETTLERS, DevelopmentObjective.byWireId(1));
        assertSame(DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES, DevelopmentObjective.byWireId(10));
        assertFalse(DevelopmentObjective.POPULATION.baselineCounter());
    }

    @Test
    void existingHospitalityKnowledgeLoadsWithoutQuarantineOrNewCounterCredit() {
        DevelopmentState existing = new DevelopmentState();
        existing.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        existing.unlock(DevelopmentNode.SHELTER);
        existing.unlock(DevelopmentNode.HOME);
        existing.unlock(DevelopmentNode.HOSPITALITY);
        existing.markInitialized();
        DevelopmentState loaded = DevelopmentState.readNbt(existing.writeNbt());
        assertFalse(loaded.quarantined());
        assertTrue(loaded.unlocked(DevelopmentNode.HOSPITALITY));
        assertFalse(loaded.ensureQuestBaseline(DevelopmentNode.HOSPITALITY, DevelopmentObjective.POPULATION));
        assertEquals(0, loaded.counter(DevelopmentObjective.POPULATION));
    }
}

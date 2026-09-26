package com.hearthstead.entity;

import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifeNeedPolicyTest {
    @Test
    void onlyHungerAndFearAreCriticalPresentationCodes() {
        assertFalse(LifeNeed.critical(LifeNeed.NONE));
        assertTrue(LifeNeed.critical(LifeNeed.HUNGRY_NO_FOOD));
        assertTrue(LifeNeed.critical(LifeNeed.FRIGHTENED));
        assertFalse(LifeNeed.critical(LifeNeed.HOMELESS));
        assertFalse(LifeNeed.critical(LifeNeed.EXHAUSTED));
        assertFalse(LifeNeed.critical(LifeNeed.WANTS_TAVERN));
        assertFalse(LifeNeed.critical(127));
    }

    @Test
    void alertIsActiveUntilButNotAtItsExpiryTick() {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Needs", BlockPos.ZERO);
        assertFalse(LifeNeed.threatActive(settlement, 10));
        settlement.alertUntilGameTime = 20;
        assertTrue(LifeNeed.threatActive(settlement, 19));
        assertFalse(LifeNeed.threatActive(settlement, 20));
    }
}

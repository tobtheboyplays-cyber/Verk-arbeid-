package com.hearthstead;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HearthsteadLoggerTest {

    @Test
    void sharedLoggerUsesStableModIdCategory() {
        assertEquals(Hearthstead.MODID, Hearthstead.LOGGER.getName());
    }
}

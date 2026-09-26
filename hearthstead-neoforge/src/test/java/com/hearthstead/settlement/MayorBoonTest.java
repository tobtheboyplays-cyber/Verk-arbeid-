package com.hearthstead.settlement;

import com.hearthstead.entity.Attribute;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MayorBoonTest {

    @Test
    void everyAttributeHasOneUniqueExplicitBoon() {
        Set<Mayor.Boon> boons = EnumSet.noneOf(Mayor.Boon.class);
        Set<String> keys = new HashSet<>();
        for (Attribute attribute : Attribute.ALL) {
            Mayor.Boon boon = Mayor.Boon.of(attribute);
            assertEquals(attribute, boon.from());
            assertTrue(boons.add(boon));
            assertTrue(keys.add(boon.key()));
        }
        assertEquals(Attribute.COUNT, boons.size());
        assertEquals(Attribute.COUNT, Mayor.Boon.values().length);
    }
}

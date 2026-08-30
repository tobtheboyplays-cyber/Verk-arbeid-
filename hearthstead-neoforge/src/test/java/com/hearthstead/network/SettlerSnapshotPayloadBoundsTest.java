package com.hearthstead.network;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlerSnapshotPayloadBoundsTest {

    @Test
    void attributesHaveExactShapeAndClampToPracticalRange() {
        List<Integer> raw = List.of(-100, 0, 12, 25, 70, 99, 100, 5000);
        SettlerSnapshotPayload payload = snapshot(raw, List.of(), bag(), bag(),
            Attribute.FOCUS.ordinal());
        assertEquals(List.of(0, 0, 12, 25, 70, 99, 99, 99),
            payload.attributeValues());

        assertThrows(IllegalArgumentException.class, () -> snapshot(
            raw.subList(0, Attribute.COUNT - 1), List.of(), bag(), bag(), 0));
        assertThrows(IllegalArgumentException.class, () ->
            SettlerSnapshotPayload.requireExactCount(Integer.MAX_VALUE,
                Attribute.COUNT, "attributes"));
    }

    @Test
    void traitAndBagCollectionsRejectMalformedCountsBeforeUse() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(
            attributes(), List.of(0, 0), bag(), bag(), 0));
        assertThrows(IllegalArgumentException.class, () -> snapshot(
            attributes(), List.of(Trait.ALL.length), bag(), bag(), 0));
        assertThrows(IllegalArgumentException.class, () -> snapshot(
            attributes(), List.of(), bag().subList(0, SettlerEntity.BAG_SIZE - 1),
            bag(), 0));
        assertThrows(IllegalArgumentException.class, () ->
            SettlerSnapshotPayload.requireCount(-1, 0, Trait.ALL.length,
                "traits"));
        assertThrows(IllegalArgumentException.class, () ->
            SettlerSnapshotPayload.requireCount(Trait.ALL.length + 1,
                0, Trait.ALL.length, "traits"));
    }

    @Test
    void invalidKnackBecomesExplicitNoKnackSentinel() {
        assertEquals(-1, snapshot(attributes(), List.of(), bag(), bag(),
            Integer.MAX_VALUE).knackOrdinal());
    }

    private static SettlerSnapshotPayload snapshot(List<Integer> attributes,
                                                    List<Integer> traits,
                                                    List<Integer> bagIds,
                                                    List<Integer> bagCounts,
                                                    int knack) {
        return new SettlerSnapshotPayload(1, UUID.randomUUID(), UUID.randomUUID(),
            2, true, attributes, knack, traits, bagIds, bagCounts,
            "lumber_camp", false, false, false, false, "hard_hands",
            0, 0, 0, -1, -1, SettlerSnapshotPayload.Delivery.UPDATE,
            Optional.empty());
    }

    private static List<Integer> attributes() {
        return new ArrayList<>(java.util.Collections.nCopies(Attribute.COUNT, 10));
    }

    private static List<Integer> bag() {
        return new ArrayList<>(java.util.Collections.nCopies(
            SettlerEntity.BAG_SIZE, 0));
    }
}

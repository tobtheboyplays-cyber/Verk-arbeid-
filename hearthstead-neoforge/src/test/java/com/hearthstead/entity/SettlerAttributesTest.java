package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerAttributesTest {

    @Test
    void canonicalOrderIsAppendOnlyAndOrdinalLookupFailsClosed() {
        assertArrayEquals(new Attribute[] {
            Attribute.STRENGTH, Attribute.STAMINA, Attribute.WITS,
            Attribute.DEXTERITY, Attribute.SPIRIT, Attribute.PERCEPTION,
            Attribute.FOCUS, Attribute.PRESENCE
        }, Attribute.ALL);
        assertFalse(Attribute.byOrdinal(-1).isPresent());
        assertFalse(Attribute.byOrdinal(Attribute.COUNT).isPresent());
        assertEquals(Attribute.PERCEPTION,
            Attribute.byOrdinal(5).orElseThrow());
    }

    @Test
    void legacyFiveValueSaveMigratesDeterministicallyWithoutChangingHistory() {
        CompoundTag legacy = legacyTag();
        SettlerAttributes first = SettlerAttributes.load(legacy,
            RandomSource.create(91L), 0x6A09E667F3BCC909L);
        SettlerAttributes second = SettlerAttributes.load(legacy,
            RandomSource.create(17L), 0x6A09E667F3BCC909L);

        CompoundTag firstSaved = first.save();
        CompoundTag secondSaved = second.save();
        assertEquals(SettlerAttributes.DATA_VERSION, firstSaved.getInt("Schema"));
        assertEquals(Attribute.COUNT, firstSaved.getIntArray("Values").length);
        assertArrayEquals(firstSaved.getIntArray("Values"),
            secondSaved.getIntArray("Values"));
        assertArrayEquals(firstSaved.getIntArray("ProgressBits"),
            secondSaved.getIntArray("ProgressBits"));

        int[] migrated = firstSaved.getIntArray("Values");
        assertArrayEquals(new int[] {3, 15, 9, 6, 12},
            Arrays.copyOf(migrated, 5));
        for (int i = 5; i < Attribute.COUNT; i++) {
            assertTrue(migrated[i] >= 1 && migrated[i] <= 15);
        }
        int[] progress = firstSaved.getIntArray("ProgressBits");
        for (int i = 0; i < 5; i++) {
            assertEquals(Float.floatToRawIntBits((i + 1) / 10.0F), progress[i]);
        }
        assertEquals(Attribute.DEXTERITY, first.knack());

        SettlerAttributes roundTrip = SettlerAttributes.load(firstSaved,
            RandomSource.create(999L), 123L);
        assertArrayEquals(firstSaved.getIntArray("Values"),
            roundTrip.save().getIntArray("Values"));
        assertArrayEquals(firstSaved.getIntArray("ProgressBits"),
            roundTrip.save().getIntArray("ProgressBits"));
    }

    @Test
    void compatibilityMigrationDoesNotDependOnFallbackRandomState() {
        CompoundTag legacy = legacyTag();
        int[] first = SettlerAttributes.load(legacy, RandomSource.create(1L))
            .save().getIntArray("Values");
        int[] second = SettlerAttributes.load(legacy, RandomSource.create(2L))
            .save().getIntArray("Values");
        assertArrayEquals(first, second);
    }

    @Test
    void invalidKnackIsStableRepairRatherThanSilentStrengthFallback() {
        CompoundTag malformed = legacyTag();
        malformed.putByte("Knack", (byte) 127);
        SettlerAttributes repaired = SettlerAttributes.load(malformed,
            RandomSource.create(1L), 1L);
        SettlerAttributes repeated = SettlerAttributes.load(malformed,
            RandomSource.create(2L), 1L);
        assertEquals(repaired.knack(), repeated.knack());
        assertNotEquals(Attribute.STRENGTH, repaired.knack());
        assertTrue(Attribute.byOrdinal(repaired.knack().ordinal()).isPresent());
    }

    @Test
    void partialCommittedWorkPersistsExactlyAcrossSaveLoad() {
        SettlerAttributes attributes = SettlerAttributes.blank();
        attributes.train(Attribute.STAMINA, 0.5F, 1.0F);
        assertEquals(0, attributes.get(Attribute.STAMINA));
        assertEquals(0.025F, attributes.trainingProgress(Attribute.STAMINA), 0.000001F);

        SettlerAttributes restored = SettlerAttributes.load(attributes.save(),
            RandomSource.create(7L), 7L);
        assertEquals(0.025F, restored.trainingProgress(Attribute.STAMINA), 0.000001F);
    }

    private static CompoundTag legacyTag() {
        CompoundTag legacy = new CompoundTag();
        legacy.putIntArray("Values", new int[] {3, 15, 9, 6, 12});
        legacy.putByte("Knack", (byte) Attribute.DEXTERITY.ordinal());
        for (int i = 0; i < 5; i++) {
            legacy.putFloat("P" + i, (i + 1) / 10.0F);
        }
        return legacy;
    }
}

package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobAttributeProfileTest {

    @Test
    void registryCoversEveryEmployedProfessionWithStrictSlots() {
        long employed = List.of(Profession.BY_ID).stream()
            .filter(Profession::employed).count();
        // 26 trades + the four battle roles (plan/BATTLE-ROLES.md)
        // + the Builder (plan/BUILDER.md).
        assertEquals(31L, employed);
        assertEquals(employed, JobAttributeProfile.all().size());

        EnumMap<Attribute, Integer> use = new EnumMap<>(Attribute.class);
        for (Attribute attribute : Attribute.ALL) {
            use.put(attribute, 0);
        }
        for (Profession profession : Profession.BY_ID) {
            if (!profession.employed()) {
                assertFalse(JobAttributeProfile.find(profession).isPresent());
                continue;
            }
            JobAttributeProfile profile = JobAttributeProfile.find(profession)
                .orElseThrow();
            assertEquals(2L, profile.slots().stream().filter(slot ->
                slot.importance() == JobAttributeProfile.Importance.CORE).count());
            assertTrue(profile.slots().stream().filter(slot ->
                slot.importance() == JobAttributeProfile.Importance.SUPPORT).count() <= 1L);
            EnumSet<Attribute> unique = EnumSet.noneOf(Attribute.class);
            for (JobAttributeProfile.Slot slot : profile.slots()) {
                assertTrue(unique.add(slot.attribute()));
                // Attributes lane (plan/ATTRIBUTES.md): every slot names a
                // wired effect; nothing is FOUNDATION_ONLY any more.
                assertTrue(slot.status() != JobAttributeProfile.EffectStatus.FOUNDATION_ONLY,
                    profession + " " + slot.attribute() + " is not wired");
                use.compute(slot.attribute(), (ignored, count) -> count + 1);
            }
        }
        for (Map.Entry<Attribute, Integer> entry : use.entrySet()) {
            assertTrue(entry.getValue() >= 3,
                entry.getKey() + " appears only " + entry.getValue() + " times");
        }
    }

    @Test
    void lockedRoleMatrixDoesNotDrift() {
        assertProfile(Profession.BUILDER, Attribute.DEXTERITY, Attribute.WITS, Attribute.STAMINA);
        // 26 Sep attributes rework (plan/ATTRIBUTES.md Part 2.3), row by row:
        // TRADER Presence first; FARMER support Focus->Perception; GUARD
        // Str/Presence/Sta; ARCHER support Presence->Focus; SPEARMAN support
        // Perception->Dexterity. Every other row is unchanged.
        assertProfile(Profession.TRADER, Attribute.PRESENCE, Attribute.WITS, Attribute.STAMINA);
        assertProfile(Profession.FARMER, Attribute.DEXTERITY,
            Attribute.STAMINA, Attribute.PERCEPTION);
        assertProfile(Profession.LUMBERER, Attribute.STRENGTH,
            Attribute.STAMINA, Attribute.WITS);
        assertProfile(Profession.GUARD, Attribute.STRENGTH,
            Attribute.PRESENCE, Attribute.STAMINA);
        assertProfile(Profession.COURIER, Attribute.STRENGTH,
            Attribute.STAMINA);
        assertProfile(Profession.BAKER, Attribute.DEXTERITY,
            Attribute.FOCUS, Attribute.STAMINA);
        assertProfile(Profession.COOK, Attribute.DEXTERITY,
            Attribute.WITS, Attribute.STAMINA);
        assertProfile(Profession.BUTCHER, Attribute.STRENGTH,
            Attribute.DEXTERITY, Attribute.STAMINA);
        assertProfile(Profession.SMELTER, Attribute.STRENGTH,
            Attribute.FOCUS, Attribute.STAMINA);
        assertProfile(Profession.SMITH, Attribute.STRENGTH,
            Attribute.DEXTERITY, Attribute.FOCUS);
        assertProfile(Profession.SAWYER, Attribute.DEXTERITY,
            Attribute.STRENGTH, Attribute.STAMINA);
        assertProfile(Profession.CARPENTER, Attribute.DEXTERITY,
            Attribute.WITS, Attribute.FOCUS);
        assertProfile(Profession.MASON, Attribute.STRENGTH,
            Attribute.DEXTERITY, Attribute.STAMINA);
        assertProfile(Profession.FLETCHER, Attribute.DEXTERITY,
            Attribute.WITS, Attribute.FOCUS);
        assertProfile(Profession.WEAVER, Attribute.DEXTERITY,
            Attribute.FOCUS, Attribute.WITS);
        assertProfile(Profession.TANNER, Attribute.DEXTERITY,
            Attribute.STRENGTH, Attribute.STAMINA);
        assertProfile(Profession.MINER, Attribute.STRENGTH,
            Attribute.PERCEPTION, Attribute.STAMINA);
        assertProfile(Profession.INNKEEPER, Attribute.PRESENCE,
            Attribute.SPIRIT, Attribute.WITS);
        assertProfile(Profession.SCHOLAR, Attribute.WITS,
            Attribute.FOCUS, Attribute.SPIRIT);
        assertProfile(Profession.MILLER, Attribute.STRENGTH,
            Attribute.FOCUS, Attribute.STAMINA);
        assertProfile(Profession.BREWER, Attribute.WITS,
            Attribute.DEXTERITY, Attribute.FOCUS);
        assertProfile(Profession.ARCHER, Attribute.DEXTERITY,
            Attribute.PERCEPTION, Attribute.FOCUS);
        assertProfile(Profession.ARMOURER, Attribute.STRENGTH,
            Attribute.DEXTERITY, Attribute.FOCUS);
        assertProfile(Profession.HERDER, Attribute.SPIRIT,
            Attribute.PERCEPTION, Attribute.STAMINA);
        assertProfile(Profession.FISHER, Attribute.DEXTERITY,
            Attribute.PERCEPTION, Attribute.STAMINA);
        assertProfile(Profession.HUNTER, Attribute.PERCEPTION,
            Attribute.DEXTERITY, Attribute.STAMINA);
        // Battle roles (plan/BATTLE-ROLES.md), reviewed 26 Sep: both blades
        // stay in the Guard family (Strength core, it trains their rank).
        assertProfile(Profession.SPEARMAN, Attribute.STRENGTH,
            Attribute.STAMINA, Attribute.DEXTERITY);
        assertProfile(Profession.LONGSWORDSMAN, Attribute.STRENGTH,
            Attribute.DEXTERITY, Attribute.STAMINA);
        assertProfile(Profession.HEALER, Attribute.SPIRIT,
            Attribute.DEXTERITY, Attribute.WITS);
        assertProfile(Profession.RUNE_MAGE, Attribute.FOCUS,
            Attribute.WITS, Attribute.SPIRIT);
    }

    @Test
    void everyAttributeIsCoreForSeveralJobsAndEveryJobUsesTwoOrThree() {
        EnumMap<Attribute, Integer> core = new EnumMap<>(Attribute.class);
        for (JobAttributeProfile profile : JobAttributeProfile.all().values()) {
            int size = profile.slots().size();
            assertTrue(size >= 2 && size <= 3, profile.profession() + " uses " + size);
            for (JobAttributeProfile.Slot slot : profile.slots()) {
                if (slot.importance() == JobAttributeProfile.Importance.CORE) {
                    core.merge(slot.attribute(), 1, Integer::sum);
                }
            }
        }
        for (Attribute attribute : Attribute.ALL) {
            assertTrue(core.getOrDefault(attribute, 0) >= 3,
                attribute + " is core in only " + core.getOrDefault(attribute, 0) + " jobs");
        }
    }

    @Test
    void everyScaledSlotEffectBelongsToItsAttribute() {
        for (JobAttributeProfile profile : JobAttributeProfile.all().values()) {
            for (JobAttributeProfile.Slot slot : profile.slots()) {
                JobAttributeProfile.scaledEffect(slot.effect()).ifPresent(effect ->
                    assertEquals(slot.attribute(), effect.attribute(),
                        profile.profession() + " " + slot.effect()));
            }
        }
    }

    private static void assertProfile(Profession profession,
                                      Attribute... expected) {
        List<Attribute> actual = JobAttributeProfile.find(profession)
            .orElseThrow().slots().stream()
            .map(JobAttributeProfile.Slot::attribute).toList();
        assertEquals(List.of(expected), actual);
    }
}

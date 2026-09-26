package com.hearthstead.conversation;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SCENARIO lane (26 Sep): relations, reputation, memories and the
 * one-parley-per-raid marker survive a server restart exactly (the file is
 * saved and loaded, as the overworld data storage does).
 */
class ScenarioRelationRestartTest {

    private static RelationSavedData reload(RelationSavedData data) {
        return RelationSavedData.load(data.save(new CompoundTag(), null), null);
    }

    @Test
    void relationsReputationMemoryAndParleyMarkerSurviveARestart() {
        RelationSavedData data = RelationSavedData.load(new CompoundTag(), null);
        UUID village = UUID.randomUUID();
        UUID otherVillage = UUID.randomUUID();
        SpeakerProfile lord = new SpeakerProfile(UUID.randomUUID(), "Lady Maren", "conversation.hearthstead.title.lord", "lord");
        SpeakerProfile captain = new SpeakerProfile(UUID.randomUUID(), "Grol", "conversation.hearthstead.title.raid_captain", "raider");
        data.change(village, lord, 20, 3, "hearthstead.event.envoy.memory.befriend", 1000L);
        data.change(village, captain, -5, -2, "conversation.hearthstead.parley.memory.refused", 2000L);
        data.met(village, lord, 3000L);
        data.markParley(village, 7L);
        data.change(otherVillage, lord, -25, 0, "hearthstead.event.envoy.memory.insult", 4000L);

        RelationSavedData loaded = reload(data);
        assertEquals(20, loaded.relation(village, lord.identity()), "befriended lord");
        assertEquals(-5, loaded.relation(village, captain.identity()), "refused captain");
        assertEquals(-25, loaded.relation(otherVillage, lord.identity()), "relations are per village");
        assertEquals(3, loaded.reputation(village, "lord"));
        assertEquals(-2, loaded.reputation(village, "raider"));
        assertEquals(7L, loaded.lastParleySerial(village), "no second parley for the same raid after a restart");
        assertEquals(-1L, loaded.lastParleySerial(otherVillage));
        RelationSavedData.Person lordThere = loaded.person(village, lord.identity());
        assertNotNull(lordThere);
        assertEquals(data.person(village, lord.identity()).memory, lordThere.memory, "memory kept");
        assertEquals(data.person(village, lord.identity()).met, lordThere.met, "meetings kept");

        RelationSavedData twice = reload(loaded);
        assertEquals(20, twice.relation(village, lord.identity()), "a second restart changes nothing");
        assertEquals(0, twice.relation(UUID.randomUUID(), lord.identity()), "a stranger village starts neutral");
    }
}

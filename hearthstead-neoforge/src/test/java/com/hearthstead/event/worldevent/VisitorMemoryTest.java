package com.hearthstead.event.worldevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class VisitorMemoryTest {
    private static StoryFacts facts(long day, int pop, int buildings) {
        return new StoryFacts(day, pop, buildings, 1, 0, 0, 0, 0, 2, false, 10);
    }

    @Test
    void aVisitIsCountedWithTheVillageAsItWas() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        assertEquals(0, book.visits("hollins"));
        assertEquals(-1L, book.daysSince("hollins", 5));
        book.recordVisit("hollins", facts(3, 5, 2));
        book.recordVisit("hollins", facts(9, 11, 6));
        assertEquals(2, book.visits("hollins"));
        assertEquals(1L, book.daysSince("hollins", 10));
        assertEquals(11, book.person("hollins").lastSeen.population());
    }

    @Test
    void aRememberedChoiceKeepsItsMoodAndTheLogIsBounded() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        VisitorMemory.Entry e = book.remember("brisks", "Aldo Brisk", "decline", VisitorMemory.DISPLEASED, 4);
        assertEquals("decline", e.choice());
        assertEquals(VisitorMemory.DISPLEASED, book.lastMood("brisks"));
        assertEquals(1, book.person("brisks").displeased);
        for (int i = 0; i < 100; i++) book.remember("odo", "Odo", "thank", VisitorMemory.NEUTRAL, i);
        assertEquals(VisitorMemory.MAX_LOG, book.log().size());
        assertEquals("thank", book.lastChoice("odo"));
    }

    @Test
    void flagsAreOneTime() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        assertTrue(book.setFlag("sung:12"));
        assertFalse(book.setFlag("sung:12"));
        assertTrue(book.flag("sung:12"));
    }

    @Test
    void bookSurvivesASaveAndLoad() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        book.recordVisit("wenna", facts(7, 8, 4));
        book.remember("wenna", "Wenna Lark", "hush", VisitorMemory.DISPLEASED, 7);
        book.setFlag("sung:6");
        VisitorMemory.Ladder ladder = book.ladder(StoryRules.LADDER_VARG);
        ladder.step = 2;
        ladder.status = VisitorMemory.Ladder.SWORN;
        ladder.captain = UUID.randomUUID();
        ladder.defeatsAt = 1;
        CompoundTag tag = book.write();
        VisitorMemory.Book back = VisitorMemory.Book.read(tag);
        assertEquals(1, back.visits("wenna"));
        assertEquals(VisitorMemory.DISPLEASED, back.lastMood("wenna"));
        assertTrue(back.flag("sung:6"));
        VisitorMemory.Ladder l = back.existingLadder(StoryRules.LADDER_VARG);
        assertNotNull(l);
        assertEquals(2, l.step);
        assertEquals(VisitorMemory.Ladder.SWORN, l.status);
        assertEquals(ladder.captain, l.captain);
        assertEquals(8, back.person("wenna").lastSeen.population());
    }

    @Test
    void aMalformedBookIsDroppedAloneOnLoad() {
        CompoundTag good = new VisitorMemory.Book().write();
        good.putUUID("Settlement", UUID.randomUUID());
        CompoundTag bad = new CompoundTag();
        bad.putString("People", "not a compound");
        bad.putUUID("Settlement", UUID.randomUUID());
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(good);
        list.add(bad);
        CompoundTag root = new CompoundTag();
        root.put("Books", list);
        VisitorMemory memory = VisitorMemory.load(root, null);
        assertNotNull(memory.existingBook(good.getUUID("Settlement")));
        assertNull(memory.existingBook(UUID.randomUUID()));
    }
}

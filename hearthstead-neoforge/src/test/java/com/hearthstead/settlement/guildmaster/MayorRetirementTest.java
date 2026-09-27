package com.hearthstead.settlement.guildmaster;

import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old saves with a seated Mayor load as an ordinary, unassigned settler: no crash, no loss. */
class MayorRetirementTest {

    @Test
    void oldSaveMayorLoadsAsUnassignedSettlerWithoutLosingAnyone() {
        UUID mayor = UUID.randomUUID();
        UUID lumberer = UUID.randomUUID();
        Settlement old = new Settlement(UUID.randomUUID(), "Oldholm", new BlockPos(10, 64, -3));
        old.putRecord(mayor, "Edda", Profession.MAYOR);
        old.putRecord(lumberer, "Bram", Profession.LUMBERER);
        old.mayorId = mayor;
        old.mayorSince = 1234L;
        old.mourningUntil = 999_999L;
        old.mayorCourierWarehouseId = UUID.randomUUID();
        CompoundTag saved = old.writeNbt();
        assertTrue(saved.hasUUID("MayorId"), "fixture really is an old Mayor save");

        Settlement loaded = Settlement.readNbt(saved);

        assertNull(loaded.mayorId, "no seat survives the load");
        assertNull(loaded.mayorCourierWarehouseId);
        assertEquals(0L, loaded.mayorSince, "no settling-in clock survives");
        assertEquals(0L, loaded.mourningUntil, "no mourning debuff survives");
        assertEquals(2, loaded.population(), "nobody is lost");
        Settlement.SettlerRecord former = loaded.record(mayor);
        assertNotNull(former, "the former Mayor is still a member");
        assertEquals("Edda", former.name, "name kept");
        assertEquals(Profession.NONE, former.profession, "former Mayor is unassigned");
        assertEquals(Profession.LUMBERER, loaded.record(lumberer).profession, "others untouched");
        assertEquals(old.center, loaded.center);
    }

    @Test
    void reSavingAMigratedSettlementWritesNoMayorKeys() {
        Settlement old = new Settlement(UUID.randomUUID(), "Oldholm", BlockPos.ZERO);
        old.mayorId = UUID.randomUUID();
        Settlement loaded = Settlement.readNbt(old.writeNbt());
        CompoundTag again = loaded.writeNbt();
        assertFalse(again.hasUUID("MayorId"));
        assertFalse(again.hasUUID("MayorCourierWarehouseId"));
        assertEquals(0L, again.getLong("MourningUntil"));
    }

    @Test
    void scrubIsIdempotentAndRetiresOnlyTheMayorProfession() {
        Settlement s = new Settlement(UUID.randomUUID(), "Now", BlockPos.ZERO);
        s.putRecord(UUID.randomUUID(), "A", Profession.MAYOR);
        s.mourningUntil = 5L;
        assertTrue(MayorRetirement.scrub(s));
        assertFalse(MayorRetirement.scrub(s), "second pass has nothing left to clear");
        assertFalse(MayorRetirement.scrub(null));

        assertEquals(Profession.NONE, MayorRetirement.retired(Profession.MAYOR));
        assertEquals(Profession.NONE, MayorRetirement.retired(null));
        for (Profession p : Profession.values()) {
            if (p != Profession.MAYOR) {
                assertEquals(p, MayorRetirement.retired(p), "untouched: " + p);
            }
        }
    }
}

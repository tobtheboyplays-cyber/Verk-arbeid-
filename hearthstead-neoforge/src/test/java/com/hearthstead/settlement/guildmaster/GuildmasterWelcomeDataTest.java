package com.hearthstead.settlement.guildmaster;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Persistence of the Guildmaster's welcome gift (4 Coins once per kingdom, tools once per player). */
class GuildmasterWelcomeDataTest {

    @Test
    void coinsAreClaimedOncePerSettlementAndToolsOncePerPlayer() {
        GuildmasterWelcomeData data = new GuildmasterWelcomeData();
        UUID kingdom = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertFalse(data.coinsGiven(kingdom));
        assertTrue(data.claimCoins(kingdom));
        assertFalse(data.claimCoins(kingdom), "Coins never twice");
        assertTrue(data.coinsGiven(kingdom));
        assertTrue(data.claimTools(kingdom, a));
        assertTrue(data.claimTools(kingdom, b), "every player gets tools");
        assertFalse(data.claimTools(kingdom, a), "tools never twice for the same player");
        assertFalse(data.coinsGiven(other), "another kingdom is untouched");
        assertTrue(data.claimTools(other, a), "tools are per kingdom");
        assertFalse(data.claimCoins(null));
        assertFalse(data.claimTools(kingdom, null));
        assertTrue(data.isDirty());
    }

    @Test
    void roundTripsThroughNbtWithoutRepeatingAnything() {
        GuildmasterWelcomeData data = new GuildmasterWelcomeData();
        UUID kingdom = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        data.claimCoins(kingdom);
        data.claimTools(kingdom, a);
        data.claimTools(kingdom, b);
        CompoundTag tag = data.save(new CompoundTag(), null);
        GuildmasterWelcomeData loaded = GuildmasterWelcomeData.load(tag, null);
        assertTrue(loaded.coinsGiven(kingdom));
        assertEquals(Set.of(a, b), loaded.toolsGivenTo(kingdom));
        assertFalse(loaded.claimCoins(kingdom));
        assertFalse(loaded.claimTools(kingdom, a));
        assertFalse(loaded.claimTools(kingdom, b));
        assertTrue(loaded.claimTools(kingdom, UUID.randomUUID()), "a new player still gets tools after reload");
    }

    @Test
    void oldAndMalformedSavesLoadAsNotYetWelcomed() {
        GuildmasterWelcomeData empty = GuildmasterWelcomeData.load(new CompoundTag(), null);
        UUID kingdom = UUID.randomUUID();
        assertFalse(empty.coinsGiven(kingdom));
        assertFalse(empty.toolsGiven(kingdom, UUID.randomUUID()));

        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        rows.add(new CompoundTag()); // no Settlement id: skipped
        CompoundTag partial = new CompoundTag();
        partial.putUUID("Settlement", kingdom); // no CoinsGiven / ToolsGiven: safe defaults
        rows.add(partial);
        tag.put("Welcomes", rows);
        GuildmasterWelcomeData loaded = GuildmasterWelcomeData.load(tag, null);
        assertFalse(loaded.coinsGiven(kingdom));
        assertTrue(loaded.toolsGivenTo(kingdom).isEmpty());
        assertTrue(loaded.claimCoins(kingdom));
    }
}

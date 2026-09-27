package com.hearthstead.settlement.development;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tech tree Option 2 (26 Sep) against saves written before it: the native
 * save held cultivated_ground/courier_deliveries and
 * shore_provisions/courier_deliveries baselines for gates Option 2 removed,
 * and the whole Development went into read-time quarantine ("records need
 * repair"; the Builder refused upgrades). Retired gate rows stay inert, and
 * no earned research is lost or forged.
 */
class Option2SaveCompatTest {

    private static DevelopmentState preOption2World() {
        DevelopmentState state = new DevelopmentState();
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.CULTIVATED_GROUND);
        state.unlock(DevelopmentNode.SHORE_PROVISIONS);
        state.unlock(DevelopmentNode.HOME);
        state.unlock(DevelopmentNode.HOSPITALITY);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        return state;
    }

    private static void baseline(CompoundTag tag, String key, int value) {
        CompoundTag row = new CompoundTag();
        row.putString("Key", key);
        row.putInt("Value", value);
        tag.getList("QuestBaselines", Tag.TAG_COMPOUND).add(row);
        if (!tag.contains("QuestBaselines")) {
            ListTag list = new ListTag();
            list.add(row);
            tag.put("QuestBaselines", list);
        }
    }

    /** The native save: pre-Option 2, so no split marker was ever written. */
    private static CompoundTag nativeSave() {
        CompoundTag tag = preOption2World().writeNbt();
        ListTag marks = tag.getList("TechGrandfathered", Tag.TAG_STRING);
        marks.removeIf(t -> t.getAsString().equals(DevelopmentState.TRADING_POST_SPLIT));
        tag.getCompound("QuestCounters").putInt("CourierDeliveries", 6);
        baseline(tag, "cultivated_ground/courier_deliveries", 0);
        baseline(tag, "shore_provisions/courier_deliveries", 0);
        return tag;
    }

    @Test
    void retiredGateBaselinesNoLongerQuarantineAndStayInertAcrossReload() {
        DevelopmentState loaded = DevelopmentState.readNbt(nativeSave());
        assertFalse(loaded.quarantined(), "retired gate baselines are inert, not corruption");
        for (DevelopmentNode kept : new DevelopmentNode[] {DevelopmentNode.CULTIVATED_GROUND,
                DevelopmentNode.SHORE_PROVISIONS, DevelopmentNode.HOSPITALITY, DevelopmentNode.FIRST_WATCH}) {
            assertTrue(loaded.unlocked(kept), "earned research kept: " + kept.id());
        }
        assertEquals(6, loaded.counter(DevelopmentObjective.COURIER_DELIVERIES), "counters untouched");

        DevelopmentState reloaded = DevelopmentState.readNbt(loaded.writeNbt());
        assertFalse(reloaded.quarantined(), "and they survive a save/load round trip");
        assertEquals(loaded.unlockedIds(), reloaded.unlockedIds());
    }

    @Test
    void unknownOrDuplicateBaselinesStillFailClosed() {
        CompoundTag unknownNode = nativeSave();
        baseline(unknownNode, "forged_node/courier_deliveries", 0);
        assertTrue(DevelopmentState.readNbt(unknownNode).quarantined(), "unknown node");

        CompoundTag unknownObjective = nativeSave();
        baseline(unknownObjective, "cultivated_ground/forged_objective", 0);
        assertTrue(DevelopmentState.readNbt(unknownObjective).quarantined(), "unknown objective");

        CompoundTag nonCounter = nativeSave();
        baseline(nonCounter, "cultivated_ground/housed_settlers", 0);
        assertTrue(DevelopmentState.readNbt(nonCounter).quarantined(), "non-counter objective never had a baseline");

        CompoundTag duplicate = nativeSave();
        baseline(duplicate, "shore_provisions/courier_deliveries", 3);
        assertTrue(DevelopmentState.readNbt(duplicate).quarantined(), "duplicate row");

        CompoundTag malformed = nativeSave();
        baseline(malformed, "shore_provisions/", 0);
        assertTrue(DevelopmentState.readNbt(malformed).quarantined(), "malformed key");
    }

    @Test
    void hospitalityLearnedBeforeTheSplitKeepsItsTradingPostOnce() {
        DevelopmentState loaded = DevelopmentState.readNbt(nativeSave());
        assertTrue(loaded.unlocked(DevelopmentNode.TRADING_POST),
            "a pre-split Hospitality already owned the Trading Post and the Trader");
        assertTrue(loaded.unlocked(DevelopmentNode.HOSPITALITY));
        assertFalse(loaded.quarantined());
    }

    @Test
    void afterTheSplitHospitalityNeverHandsOutTheTradingPost() {
        DevelopmentState fresh = new DevelopmentState();
        fresh.unlock(DevelopmentNode.HOME);
        fresh.unlock(DevelopmentNode.HOSPITALITY);
        DevelopmentState reloaded = DevelopmentState.readNbt(fresh.writeNbt());
        assertFalse(reloaded.unlocked(DevelopmentNode.TRADING_POST),
            "a world made after Option 2 buys the Trading Post itself");
        assertFalse(reloaded.quarantined());

        CompoundTag noHospitality = preOption2World().writeNbt();
        noHospitality.getList("Unlocked", Tag.TAG_STRING).removeIf(
            t -> t.getAsString().equals(DevelopmentNode.HOSPITALITY.id()));
        noHospitality.getList("TechGrandfathered", Tag.TAG_STRING)
            .removeIf(t -> t.getAsString().equals(DevelopmentState.TRADING_POST_SPLIT));
        assertFalse(DevelopmentState.readNbt(noHospitality).unlocked(DevelopmentNode.TRADING_POST),
            "no Hospitality before the split, no free Trading Post");
    }

    @Test
    void theOtherOption2MovesLoadCleanFromOldSaves() {
        // border_wardens was a ring-2 node after the first raid; old worlds
        // that own it (and sturdy_beds under Hospitality) must still load.
        DevelopmentState old = preOption2World();
        old.unlock(DevelopmentNode.ARM_THE_WATCH);
        old.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        old.unlock(DevelopmentNode.BORDER_WARDENS);
        old.unlockUpgrade(PostRaidUpgrade.WARM_HEARTH);
        old.unlockUpgrade(PostRaidUpgrade.STURDY_BEDS);
        CompoundTag tag = old.writeNbt();
        tag.getList("TechGrandfathered", Tag.TAG_STRING).add(StringTag.valueOf("unused_marker_is_kept"));
        DevelopmentState loaded = DevelopmentState.readNbt(tag);
        assertFalse(loaded.quarantined());
        assertTrue(loaded.unlocked(DevelopmentNode.BORDER_WARDENS));
        assertTrue(loaded.hasUpgrade(PostRaidUpgrade.STURDY_BEDS));
    }
}

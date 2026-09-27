package com.hearthstead.settlement.work;

import com.hearthstead.item.CarcassData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HunterButcheryTest {

    private static CarcassData cow(int beef, int leather) {
        return CarcassData.of(EntityType.COW, leather > 0
            ? List.of(new ItemStack(Items.BEEF, beef), new ItemStack(Items.LEATHER, leather))
            : List.of(new ItemStack(Items.BEEF, beef)));
    }

    @Test
    void baseYieldIsExactlyTheRecordedVanillaRoll() {
        List<ItemStack> out = HunterButchery.yield(cow(2, 1), false);
        assertEquals(2, out.size());
        assertTrue(out.get(0).is(Items.BEEF) && out.get(0).getCount() == 2);
        assertTrue(out.get(1).is(Items.LEATHER) && out.get(1).getCount() == 1);
    }

    @Test
    void yieldIsAFreshCopyThatCannotMutateTheCarcass() {
        CarcassData data = cow(3, 0);
        List<ItemStack> out = HunterButchery.yield(data, false);
        out.get(0).shrink(3);
        assertEquals(3, data.yield().get(0).getCount());
    }

    @Test
    void tradeBonusRepeatsOnlyMeatTheRollAlreadyHad() {
        List<ItemStack> bonus = HunterButchery.yield(cow(1, 2), true);
        assertEquals(2, bonus.get(0).getCount(), "one extra beef");
        assertEquals(2, bonus.get(1).getCount(), "leather is never multiplied");

        CarcassData hideOnly = CarcassData.of(EntityType.RABBIT,
            List.of(new ItemStack(Items.RABBIT_HIDE)));
        List<ItemStack> noMeat = HunterButchery.yield(hideOnly, true);
        assertEquals(1, noMeat.size());
        assertEquals(1, noMeat.get(0).getCount(), "no meat rolled -> no bonus is conjured");
    }

    @Test
    void peltDecidesTheSkinningPhase() {
        assertTrue(HunterButchery.hasPelt(cow(1, 1)));
        assertFalse(HunterButchery.hasPelt(cow(2, 0)));
        assertTrue(HunterButchery.hasPelt(CarcassData.of(EntityType.CHICKEN,
            List.of(new ItemStack(Items.CHICKEN), new ItemStack(Items.FEATHER)))));
        assertTrue(HunterButchery.hasPelt(CarcassData.of(EntityType.SHEEP,
            List.of(new ItemStack(Items.MUTTON), new ItemStack(Items.BLACK_WOOL)))));
        assertEquals(40, HunterButchery.skinTicks(100, true));
        assertEquals(0, HunterButchery.skinTicks(100, false));
    }

    @Test
    void levelOneWorkTimeIsUnchangedAndSkillOnlyRemovesWholeLoops() {
        assertEquals(100, HunterButchery.workTicks(100, 1, 20));
        int fast = HunterButchery.workTicks(100, 10, 50);
        assertTrue(fast < 100 && fast >= 100 * (1.0 - 0.18) - HunterButchery.CLEAVE_PERIOD);
        assertEquals(0, (100 - fast) % HunterButchery.CLEAVE_PERIOD, "whole cleave loops only");
        assertEquals(HunterButchery.CLEAVE_PERIOD, HunterButchery.workTicks(1, 10, 50),
            "never shorter than one visible cleave");
    }

    @Test
    void soundsLandOnEachClipsContactTick() {
        assertTrue(HunterButchery.soundTick(HunterButchery.SCRAPE_CONTACT, true));
        assertFalse(HunterButchery.soundTick(0, true));
        assertTrue(HunterButchery.soundTick(HunterButchery.CLEAVE_PERIOD + HunterButchery.CLEAVE_CONTACT, false));
        assertFalse(HunterButchery.soundTick(HunterButchery.CLEAVE_CONTACT + 1, false));
    }

    @Test
    void carcassEqualityIsByContentSoExactTransfersSurviveReload() {
        assertEquals(cow(2, 1), cow(2, 1));
        assertEquals(cow(2, 1).hashCode(), cow(2, 1).hashCode());
        assertNotEquals(cow(2, 1), cow(3, 1));
        assertNotEquals(cow(2, 1), CarcassData.of(EntityType.PIG,
            List.of(new ItemStack(Items.BEEF, 2), new ItemStack(Items.LEATHER))));
    }

    @Test
    void carcassYieldRowsAreBoundedAndIgnoreEmptyStacks() {
        ItemStack[] many = new ItemStack[20];
        for (int i = 0; i < many.length; i++) {
            many[i] = new ItemStack(Items.FEATHER);
        }
        CarcassData data = CarcassData.of(EntityType.CHICKEN, List.of(many));
        assertEquals(CarcassData.MAX_YIELD_STACKS, data.yield().size());
        CarcassData withEmpty = CarcassData.of(EntityType.CHICKEN,
            List.of(ItemStack.EMPTY, new ItemStack(Items.CHICKEN)));
        assertEquals(1, withEmpty.yield().size());
    }
}

package com.hearthstead.entity.ai;

import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA-BUILD-01: the Builder may choose any pending block of the layer (up to
 * FRONT_SCAN steps ahead), so the load window -- used both for loading and
 * for what he keeps at the hut -- must carry the chosen step's material even
 * when it lies beyond the ordered prefix, and must never exceed the load.
 */
class BuilderLoadWindowTest {

    private static final int CAP = BuilderWorkGoal.BASE_LOAD;

    /** {@code prefix} states in build order (rows of 16 along +x), then one oak plank step on the next row. */
    private static BuildJob layer(List<BlockState> prefix) {
        BuildJob.Builder b = new BuildJob.Builder();
        for (int i = 0; i < prefix.size(); i++) {
            b.add(new BlockPos(i % 16, 64, 2 * (i / 16)), prefix.get(i), BuildPhase.STRUCTURE, 0, null, null);
        }
        b.add(new BlockPos(0, 64, 2 * ((prefix.size() + 15) / 16) + 2), Blocks.OAK_PLANKS.defaultBlockState(),
            BuildPhase.STRUCTURE, 0, null, null);
        return b.build(UUID.randomUUID(), UUID.randomUUID(), BuildJob.Kind.BLUEPRINT, "test", "test",
            BlockPos.ZERO, 0, false, null, 0L);
    }

    private static BuildJob cobbleThenPlank(int cobble) {
        return layer(java.util.Collections.nCopies(cobble, Blocks.COBBLESTONE.defaultBlockState()));
    }

    private static int plankStep(BuildJob job) {
        for (int i = 0; i < job.size(); i++) {
            if (job.state(i).is(Blocks.OAK_PLANKS)) {
                return i;
            }
        }
        throw new AssertionError("fixture has no plank step");
    }

    private static int units(Map<Item, Integer> window) {
        return window.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void theOrderedPrefixAloneMissesAStepBeyondIt() {
        // The defect Codex reproduced: 64 cobblestone units fill the prefix window.
        BuildJob job = cobbleThenPlank(64);
        assertEquals(64, plankStep(job), "fixture: plank step sits after 64 cobblestone steps");
        Map<Item, Integer> prefixOnly = BuilderWorkGoal.loadWindow(job, -1, 0, CAP, 0L);
        assertNull(prefixOnly.get(Items.OAK_PLANKS), "without a selected step the prefix has no plank");
    }

    @Test
    void selectedStepBeyondThePrefixIsLoadedFirstAndTheWindowStaysWithinTheLoad() {
        for (int cobble : new int[]{63, 64, 65, 200, 511}) {
            BuildJob job = cobbleThenPlank(cobble);
            int plank = plankStep(job);
            Map<Item, Integer> window = BuilderWorkGoal.loadWindow(job, plank, 0, CAP, 0L);
            assertEquals(1, window.get(Items.OAK_PLANKS), cobble + ": the chosen plank step is in the load");
            assertEquals(Items.OAK_PLANKS, window.keySet().iterator().next(), cobble + ": chosen step loads first");
            assertEquals(Math.min(cobble, CAP - 1), window.get(Items.COBBLESTONE), cobble + ": rest filled in order");
            assertTrue(units(window) <= CAP, cobble + ": never more than the load, got " + units(window));
        }
    }

    @Test
    void aSelectedStepInsideThePrefixIsCountedOnce() {
        BuildJob job = cobbleThenPlank(10);
        int plank = plankStep(job);
        Map<Item, Integer> window = BuilderWorkGoal.loadWindow(job, plank, 0, CAP, 0L);
        assertEquals(1, window.get(Items.OAK_PLANKS));
        assertEquals(10, window.get(Items.COBBLESTONE));
        assertEquals(window, Map.copyOf(BuilderWorkGoal.loadWindow(job, -1, 0, CAP, 0L)),
            "same totals whether or not the chosen step is inside the prefix");
    }

    @Test
    void mixedPrefixKeepsBuildOrderAfterTheSelectedStep() {
        java.util.ArrayList<BlockState> prefix = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            prefix.add(i < 40 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
        }
        BuildJob job = layer(prefix);
        Map<Item, Integer> window = BuilderWorkGoal.loadWindow(job, plankStep(job), 0, CAP, 0L);
        assertEquals(List.of(Items.OAK_PLANKS, Items.STONE_BRICKS, Items.COBBLESTONE), List.copyOf(window.keySet()));
        assertEquals(40, window.get(Items.STONE_BRICKS));
        assertEquals(CAP - 1 - 40, window.get(Items.COBBLESTONE));
        assertEquals(CAP, units(window));
    }

    @Test
    void scaffoldLaddersKeepTheirReserveBesideTheSelectedStep() {
        BuildJob job = cobbleThenPlank(100);
        int ladders = CAP / 2;
        Map<Item, Integer> window = BuilderWorkGoal.loadWindow(job, plankStep(job), ladders, CAP, 0L);
        assertEquals(1, window.get(Items.OAK_PLANKS));
        assertEquals(ladders, window.get(Items.LADDER), "the column's ladder share is unchanged");
        assertEquals(CAP - 1 - ladders, window.get(Items.COBBLESTONE));
        assertEquals(CAP, units(window));
    }

    @Test
    void biggerSackCarriesMoreButStillTheChosenStep() {
        int cap = BuilderWorkGoal.BASE_LOAD + 2 * 16; // a sack tier adds 16 carry: 32 more for a Builder
        BuildJob job = cobbleThenPlank(300);
        Map<Item, Integer> window = BuilderWorkGoal.loadWindow(job, plankStep(job), 0, cap, 0L);
        assertEquals(1, window.get(Items.OAK_PLANKS));
        assertEquals(cap - 1, window.get(Items.COBBLESTONE));
        assertEquals(cap, units(window));
    }

    @Test
    void finishedSkippedOrDeferredStepsAreNeverLoaded() {
        BuildJob job = cobbleThenPlank(70);
        int plank = plankStep(job);
        job.skipStep(plank);
        Map<Item, Integer> skipped = BuilderWorkGoal.loadWindow(job, plank, 0, CAP, 0L);
        assertNull(skipped.get(Items.OAK_PLANKS), "a skipped selected step is not seeded");
        assertEquals(CAP, skipped.get(Items.COBBLESTONE));

        BuildJob deferred = cobbleThenPlank(70);
        int later = plankStep(deferred);
        deferred.deferStep(later, 100L);
        assertNull(BuilderWorkGoal.loadWindow(deferred, later, 0, CAP, 50L).get(Items.OAK_PLANKS),
            "a deferred step waits");
        assertEquals(1, BuilderWorkGoal.loadWindow(deferred, later, 0, CAP, 100L).get(Items.OAK_PLANKS),
            "and comes back when its time is up");
    }
}

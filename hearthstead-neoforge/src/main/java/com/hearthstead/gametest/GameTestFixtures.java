package com.hearthstead.gametest;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.UUID;

/**
 * The one place a GameTest fixture registers a synthetic {@link Building}.
 *
 * <h2>Why this exists (FLAKE-2, 2026-08-26 -- see KF-021)</h2>
 *
 * <p>{@code BuildingManager.tick()} sweeps one building per 20 ticks across
 * every settlement in the save and dissolves any whose {@code plaquePos}
 * does not hold a real {@code PlaqueBlock} -- correctly, because <b>the
 * plaque is the surveyor; no plaque, no building</b> is a permanent product
 * invariant (D-005), and weakening that sweep to suit a test fixture is
 * exactly the kind of judge-weakening this project refuses to do.
 *
 * <p>Before this class, roughly two dozen GameTest files each carried their
 * own private copy of "make an anchor, make a Building, add it to the
 * settlement" -- and all but the courier and warehouse fixtures (KF-014)
 * forgot the plaque block that precondition requires. The result was not a
 * deterministic failure: whichever fixture's building the sweep's
 * round-robin cursor happened to reach within that one test's short window
 * lost its building — and its worker went IDLE forever — for a reason that
 * had nothing to do with what the test's name claimed to measure. Proven
 * live: {@code ahiredsmelteractuallysmelts} failing with its own "Testholm"
 * settlement's smelter dissolved out from under it mid-test.
 *
 * <p><b>There is now exactly one path that creates this shape of test
 * building</b>, and it places the plaque itself, so a fixture calling it
 * cannot forget the plaque the way twenty-odd hand-written copies did. Any
 * new fixture that needs a work/home building should call {@link #register}
 * rather than constructing {@link Building} directly — a call to
 * {@code new Building(...)} anywhere outside this file, {@link Building}
 * itself, or the real survey path ({@code PlaqueBlockEntity}) is very
 * likely reintroducing this exact bug.
 */
public final class GameTestFixtures {

    /** The footprint every one of these fixtures uses: a 4x3x4 box anchored
     *  at (x,1,z), with its declaring plaque one block above the anchor. */
    private static final int SIZE_XZ = 3;
    private static final int SIZE_Y = 2;

    private GameTestFixtures() {
    }

    /**
     * Registers a small work/home building for {@code type}, anchored at
     * relative (x,1,z), with a real plaque placed at relative (x,2,z) —
     * without which {@code BuildingManager}'s sweep would dissolve it. Marks
     * the building valid and adds it to {@code s.buildings}; does not touch
     * {@code SettlementSavedData} (callers already register the settlement
     * itself).
     */
    public static Building register(GameTestHelper helper, Settlement s,
                                    BuildingType type, int x, int z) {
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        BlockPos plaqueRel = new BlockPos(x, 2, z);
        placePlaque(helper, plaqueRel);
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(plaqueRel), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(SIZE_XZ, SIZE_Y, SIZE_XZ)));
        building.valid = true;
        s.buildings.add(building);
        return building;
    }

    /**
     * Same contract as {@link #register}, for the handful of fixtures whose
     * bounds are not the standard 4x3x4 box (an armoury sized around its
     * chest, say). Still goes through {@link #placePlaque}, so it still
     * cannot forget the plaque.
     */
    public static Building registerWithBounds(GameTestHelper helper, Settlement s,
                                              BuildingType type, BlockPos anchorRel,
                                              BlockPos plaqueRel, BoundingBox bounds) {
        placePlaque(helper, plaqueRel);
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(plaqueRel), helper.absolutePos(anchorRel), bounds);
        building.valid = true;
        s.buildings.add(building);
        return building;
    }

    /**
     * Places the plaque block a {@link Building} MUST have at its
     * {@code plaquePos}, and fails loudly and immediately if it somehow is
     * not there — at fixture setup, not thousands of ticks later as an
     * unrelated test going unexplainedly IDLE. The one call every path in
     * this class (and any hand-built fixture with non-standard bounds) routes
     * through, so the plaque itself can never be the thing a new fixture
     * forgets.
     */
    public static void placePlaque(GameTestHelper helper, BlockPos plaqueRel) {
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get());
        helper.assertBlockState(plaqueRel,
            state -> state.getBlock() instanceof PlaqueBlock,
            () -> "GameTestFixtures.placePlaque placed a plaque at " + plaqueRel
                + " but it is not there — the building this makes would be "
                + "dissolved by BuildingManager's sweep the moment it runs");
    }
}

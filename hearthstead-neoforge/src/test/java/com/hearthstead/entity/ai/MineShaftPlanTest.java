package com.hearthstead.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MINE V2: the ladder shaft planner, driven through whole mines on a toy
 * world. Every step is checked against the safety rules: the ladder always
 * reaches his level, he never cuts the block he stands on, a ladder or the
 * wall it hangs on, and never opens a block next to water or lava.
 */
final class MineShaftPlanTest {
    private static final int G = 64; // feet level of the ground; rock below

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A toy world: rock below ground, air above, a building block behind the mouth. */
    private static final class World implements MineShaftPlan.Probe {
        final Map<BlockPos, MineShaftPlan.Cell> cells = new HashMap<>();
        final Set<BlockPos> torches = new HashSet<>();
        Predicate<BlockPos> mayDig = p -> true;

        World() {
            cells.put(new BlockPos(0, G, 0), MineShaftPlan.Cell.LADDER);
            cells.put(new BlockPos(0, G, -1), MineShaftPlan.Cell.HARD);
        }

        @Override
        public MineShaftPlan.Cell at(BlockPos pos) {
            MineShaftPlan.Cell c = cells.get(pos);
            if (c != null) {
                return c;
            }
            return pos.getY() < G ? MineShaftPlan.Cell.ROCK : MineShaftPlan.Cell.OPEN;
        }

        @Override
        public boolean mayDig(BlockPos pos) {
            return mayDig.test(pos);
        }

        @Override
        public boolean torch(BlockPos pos) {
            return torches.contains(pos);
        }
    }

    private static final MineShaftPlan.Site SITE = new MineShaftPlan.Site(new BlockPos(0, G, 0), Direction.SOUTH);

    /** Runs the planner like the Miner would until he reaches a face; checks every rule on the way. */
    private static MineShaftPlan.Step run(World w, int floorOne, Integer floorTwo, int lane, boolean torches) {
        for (int i = 0; i < 2000; i++) {
            MineShaftPlan.Step s = MineShaftPlan.next(SITE, w, floorOne, floorTwo, lane, torches);
            assertNotEquals(MineShaftPlan.Kind.BLOCKED, s.kind(), "blocked: " + s.why());
            if (s.kind() == MineShaftPlan.Kind.FACE) {
                return s;
            }
            BlockPos t = s.target();
            BlockPos stand = s.stand();
            assertNotNull(stand);
            switch (s.kind()) {
                case DIG -> {
                    assertEquals(MineShaftPlan.Cell.ROCK, w.at(t), "only rock is cut: " + t);
                    assertNotEquals(stand.below(), t, "never the block he stands on");
                    assertNotEquals(stand, t);
                    assertFalse(t.getX() == 0 && t.getZ() == -1, "never the ladder's wall");
                    for (Direction d : Direction.values()) {
                        assertNotEquals(MineShaftPlan.Cell.FLUID, w.at(t.relative(d)),
                            "never opened next to fluid: " + t);
                    }
                    assertTrue(Math.abs(t.getY() - stand.getY()) <= 2
                        && Math.abs(t.getX() - stand.getX()) + Math.abs(t.getZ() - stand.getZ()) <= 1,
                        "within reach: " + t + " from " + stand);
                    w.cells.put(t, s.ladderAfter() ? MineShaftPlan.Cell.LADDER : MineShaftPlan.Cell.OPEN);
                    if (t.getX() == 0 && t.getZ() == 0) {
                        assertTrue(s.ladderAfter(), "every ladder-column cell gets its ladder at once");
                    }
                }
                case LADDER -> w.cells.put(t, MineShaftPlan.Cell.LADDER);
                case FILL -> w.cells.put(t, MineShaftPlan.Cell.ROCK);
                case TORCH -> w.torches.add(t);
                default -> throw new AssertionError(s);
            }
            // The way up: the stand cell is the ladder column or right beside it
            // (shaft), or in the lane; the ladders reach down to his level.
            int bottomLadder = G;
            while (w.at(new BlockPos(0, bottomLadder - 1, 0)) == MineShaftPlan.Cell.LADDER) {
                bottomLadder--;
            }
            for (int y = G; y >= bottomLadder; y--) {
                assertEquals(MineShaftPlan.Cell.LADDER, w.at(new BlockPos(0, y, 0)), "a gap in the ladder at " + y);
            }
            assertTrue(bottomLadder <= stand.getY() || stand.getY() >= G,
                "the ladder reaches his level: ladder bottom " + bottomLadder + ", he is at " + stand);
            assertTrue(w.at(new BlockPos(0, bottomLadder - 1, 0)) != MineShaftPlan.Cell.OPEN,
                "no open drop under the ladder");
        }
        throw new AssertionError("no face after 2000 steps");
    }

    @Test
    void aShaftALaneAndThenTheFace() {
        World w = new World();
        MineShaftPlan.Step face = run(w, G - 8, null, 8, false);
        assertEquals(1, face.level());
        assertEquals(new BlockPos(0, G - 8, 1 + 9), face.target(), "the face is one past the lane's end, level 1");
        assertEquals(new BlockPos(0, G - 8, 1 + 8), face.stand());
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(face.target()), "the face is never broken");
        for (int y = G - 1; y >= G - 8; y--) {
            assertEquals(MineShaftPlan.Cell.LADDER, w.at(new BlockPos(0, y, 0)), "ladder at " + y);
            assertEquals(MineShaftPlan.Cell.OPEN, w.at(new BlockPos(0, y, 1)), "dig column open at " + y);
        }
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(new BlockPos(0, G - 9, 0)), "the shaft stops at level 1");
        for (int i = 1; i <= 8; i++) {
            assertEquals(MineShaftPlan.Cell.OPEN, w.at(new BlockPos(0, G - 8, 1 + i)));
            assertEquals(MineShaftPlan.Cell.OPEN, w.at(new BlockPos(0, G - 7, 1 + i)));
            assertEquals(MineShaftPlan.Cell.ROCK, w.at(new BlockPos(0, G - 6, 1 + i)), "the lane is 2 high");
        }
        // Asked again, it stays at the face: the same block, forever.
        assertEquals(face, MineShaftPlan.next(SITE, w, G - 8, null, 8, false));
    }

    @Test
    void deepMineAddsLevelTwoBelowTheFirstLane() {
        World w = new World();
        run(w, G - 8, null, 8, false);
        MineShaftPlan.Step face = run(w, G - 8, G - 24, 8, false);
        assertEquals(2, face.level());
        assertEquals(G - 24, face.target().getY());
        assertEquals(MineShaftPlan.Cell.LADDER, w.at(new BlockPos(0, G - 24, 0)));
        assertEquals(MineShaftPlan.Cell.OPEN, w.at(new BlockPos(0, G - 8, 9)), "the level-1 lane is still there");
    }

    @Test
    void lavaBesideTheLaneIsSealedNeverOpened() {
        World w = new World();
        BlockPos lava = new BlockPos(1, G - 8, 4);
        w.cells.put(lava, MineShaftPlan.Cell.FLUID);
        w.cells.put(new BlockPos(0, G - 6, 6), MineShaftPlan.Cell.FLUID); // over the lane's head
        run(w, G - 8, null, 8, false);
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(lava), "the lava beside the lane was sealed");
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(new BlockPos(0, G - 6, 6)), "the lava over it too");
    }

    @Test
    void waterBehindTheShaftIsSealedAndCavesGetAWall() {
        World w = new World();
        w.cells.put(new BlockPos(1, G - 3, 0), MineShaftPlan.Cell.FLUID);
        w.cells.put(new BlockPos(0, G - 5, -1), MineShaftPlan.Cell.OPEN); // a cave behind the ladder
        run(w, G - 8, null, 4, false);
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(new BlockPos(1, G - 3, 0)));
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(new BlockPos(0, G - 5, -1)), "the ladder got a wall");
        assertEquals(MineShaftPlan.Cell.LADDER, w.at(new BlockPos(0, G - 5, 0)));
    }

    @Test
    void aWaterloggedBuildingBlockStopsTheShaft() {
        World w = new World();
        w.cells.put(new BlockPos(1, G - 2, 1), MineShaftPlan.Cell.WET);
        MineShaftPlan.Step s = null;
        for (int i = 0; i < 20; i++) {
            s = MineShaftPlan.next(SITE, w, G - 8, null, 8, false);
            if (s.kind() == MineShaftPlan.Kind.BLOCKED) {
                break;
            }
            w.cells.put(s.target(), s.ladderAfter() ? MineShaftPlan.Cell.LADDER : MineShaftPlan.Cell.OPEN);
        }
        assertEquals(MineShaftPlan.Kind.BLOCKED, s.kind());
        assertEquals(MineShaftPlan.Cell.ROCK, w.at(new BlockPos(0, G - 2, 1)), "the cell beside it was not opened");
    }

    @Test
    void theLaneTurnsAwayFromTheClaimEdgeOrABuilding() {
        World w = new World();
        w.mayDig = p -> p.getZ() < 5; // south is out of bounds past z=4
        MineShaftPlan.Step face = run(w, G - 6, null, 6, false);
        assertEquals(G - 6, face.target().getY());
        assertTrue(face.target().getZ() == 1 && Math.abs(face.target().getX()) == 7,
            "the lane went sideways: " + face.target());
        w.mayDig = p -> false;
        World w2 = new World();
        w2.mayDig = p -> false;
        assertEquals(MineShaftPlan.Kind.BLOCKED, MineShaftPlan.next(SITE, w2, G - 6, null, 6, false).kind(),
            "nothing is dug outside the claim");
    }

    @Test
    void torchesMarkTheLaneWhenInStock() {
        World w = new World();
        run(w, G - 6, null, 6, true);
        assertTrue(w.torches.contains(new BlockPos(0, G - 6, 2)), "a torch at the lane start");
        assertTrue(w.torches.contains(new BlockPos(0, G - 6, 6)), "a torch near the lane end");
    }

    @Test
    void noPickFindsOnlyCobblestone() {
        // Tool tiers need the game's block tags, so the stone-pick rule is a
        // GameTest (MineV2GameTests); here: a find the pick cannot take is cobble.
        List<MineFaceTable.Entry> table = MineFaceTable.parse(JsonParser.parseString(
            "{\"entries\":[{\"item\":\"minecraft:diamond\",\"block\":\"minecraft:diamond_ore\",\"weight\":50},"
                + "{\"item\":\"minecraft:nope\",\"block\":\"minecraft:stone\",\"weight\":50}]}").getAsJsonObject());
        assertEquals(1, table.size(), "unknown items are skipped");
        RandomSource rng = RandomSource.create(1234L);
        for (int i = 0; i < 500; i++) {
            assertTrue(MineFaceTable.roll(table, ItemStack.EMPTY, rng).stack().is(Items.COBBLESTONE));
        }
    }

    @Test
    void theShippedTablesParse() throws Exception {
        for (int level = 1; level <= 2; level++) {
            try (var in = MineShaftPlanTest.class.getResourceAsStream(
                    "/data/hearthstead/mining/level_" + level + ".json")) {
                assertNotNull(in, "level_" + level + ".json ships");
                var json = JsonParser.parseReader(new java.io.InputStreamReader(in,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                List<MineFaceTable.Entry> t = MineFaceTable.parse(json);
                assertEquals(json.getAsJsonArray("entries").size(), t.size(), "every entry is valid");
                assertTrue(t.stream().anyMatch(e -> e.item() == Items.COBBLESTONE));
                assertEquals(level == 2, t.stream().anyMatch(e -> e.item() == Items.DIAMOND),
                    "diamonds only at level 2");
            }
        }
    }
}

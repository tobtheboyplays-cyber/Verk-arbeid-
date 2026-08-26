package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.RoomScanner;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The owner's finding (filmed session 20260826, 4:57-5:36): he built a
 * storage room UNDERGROUND, fitted a warehouse plan, and got "No room
 * found" (5:27) with no explanation of why or where.
 *
 * <h2>The mechanism (see RoomScanner.java / PlaqueBlockEntity.java)</h2>
 *
 * <p>{@link RoomScanner} does not special-case underground rooms at all —
 * {@code isPassable} treats every collision-solid block as a wall, whatever
 * it is made of, and {@code hasCoverAbove} accepts the first solid block it
 * finds looking up, whatever is above that. A room dug entirely out of
 * natural stone validates by exactly the same rule an above-ground cottage
 * does. {@link #anUndergroundWarehouseValidates} pins this: a warehouse
 * room buried in solid rock on every side registers LINKED_VALID.
 *
 * <p>The owner's actual failure was therefore never about being
 * underground — it was that "No room found" said NOTHING about why. Before
 * this fix, an outright scan failure ({@code result == null},
 * {@code !enclosed}, {@code skyLeak}, or oversized) cleared
 * {@code PlaqueBlockEntity.lastSurvey} to an empty list, and
 * {@code RoomScanner.Result.missing()} — which already computed a reason —
 * was never called by anything. {@link #aCeilingHoleNamesTheLeak} pins the
 * fix: a single missing roof block now produces a
 * {@code RoomScanner.Result} whose {@code leakPos}/{@code skyLeakPos}
 * names the exact cell the flood fill broke out at, and
 * {@code PlaqueBlockEntity.survey} turns that into
 * {@code lastScanReason} — surfaced in the plaque screen's requirements tab
 * and spoken to nearby players once, the moment it becomes true.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RoomScannerGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 10;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * A warehouse room dug entirely out of solid rock: floor, walls, roof
     * AND a generous overburden on every side (three full blocks of stone
     * beyond the shell, in every direction) so nothing about this room
     * touches open air anywhere except through its single door — matching
     * "prøver å putte lager under bakken" rather than a shell standing on
     * open ground with a roof glued on top of it.
     *
     * <p>7x7 exterior footprint at {@code o} (walls x/z in {0,6}, y1-3;
     * floor y0; roof y4), same geometry {@code PlaqueGraceGameTests}' own
     * {@code buildFarmRoom} uses, so this reuses a proven-working shell
     * rather than inventing new wall math. Storage x4 (warehouse's own
     * requirement), lights x2, one door in the wall facing the plaque.
     */
    private static void buildUndergroundWarehouse(GameTestHelper helper, BlockPos o) {
        // Overburden: solid rock from 3 blocks below the floor to 3 blocks
        // above the roof, and 3 blocks past every wall — deep enough that
        // even the plaque's own "hung outside, thin wall" candidate probe
        // (which looks one further step past the plaque) still lands in
        // rock, never in the open test arena.
        for (int x = -3; x <= 9; x++) {
            for (int z = -3; z <= 9; z++) {
                for (int y = -3; y <= 9; y++) {
                    helper.setBlock(o.offset(x, y, z), Blocks.STONE);
                }
            }
        }
        // Carve the 5x5x3 interior back out.
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(o.offset(x, y, z), Blocks.AIR);
                }
            }
        }
        // Door through the x=0..6,z=0 wall.
        helper.setBlock(o.offset(3, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(3, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Warehouse furnishing, with real margin over the requirement
        // (storage 4, lights 2): 6 storage blocks, 3 lights, so a single
        // off-by-one in this fixture's own geometry does not read as a
        // RoomScanner defect the way it did the first time (WAREHOUSE was
        // measured LINKED_INCOMPLETE against a fixture sized exactly to the
        // minimum, with no assertion on the actual numbers to say which
        // requirement came up short). Torches sit on the solid floor (y1,
        // directly above the y0 stone) rather than floating in open
        // interior air: a standing torch needs solid support BELOW it
        // (TorchBlock#canSurvive), and floating placement is exactly the
        // kind of thing `helper.setBlock` is known to place without ever
        // validating in this codebase (see GameTestFixtures#placePlaque's
        // own KF-021 note on the same pitfall for the plaque block).
        helper.setBlock(o.offset(1, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(2, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(1, 1, 2), Blocks.CHEST);
        helper.setBlock(o.offset(4, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 2), Blocks.BARREL);
        helper.setBlock(o.offset(3, 1, 1), Blocks.TORCH);
        helper.setBlock(o.offset(3, 1, 5), Blocks.TORCH);
        helper.setBlock(o.offset(1, 1, 4), Blocks.TORCH);
    }

    /** A 7x7 warehouse shell standing in open air: no overburden, so a
     *  missing roof block opens straight up into the test arena, exactly
     *  the leak this test needs. Otherwise identical furnishing to
     *  {@link #buildUndergroundWarehouse}. */
    private static void buildOpenWarehouse(GameTestHelper helper, BlockPos o) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(o.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(o.offset(3, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(3, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Same margin and floor-supported torches as buildUndergroundWarehouse.
        helper.setBlock(o.offset(1, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(2, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(1, 1, 2), Blocks.CHEST);
        helper.setBlock(o.offset(4, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 2), Blocks.BARREL);
        helper.setBlock(o.offset(1, 1, 4), Blocks.TORCH);
        helper.setBlock(o.offset(5, 1, 4), Blocks.TORCH);
        helper.setBlock(o.offset(3, 1, 5), Blocks.TORCH);
    }

    /**
     * A narrow, fully-walled 1x1 shaft directly above {@code roofHoleRel}.
     * Without this, once the flood fill clears the roof it ALSO spreads
     * sideways at that height across the whole arena, and the recorded break
     * position then depends on the arena rather than on the hole. Walling
     * the shaft removes that dependency: the only way out is straight up, in
     * the hole's own column, every time -- matching a real player's mistake
     * (a bare unroofed gap with open sky above, not a sideways cavity).
     *
     * <p>What actually names the leak here is the ROOF TEST, not the
     * extent/height cap: this arena ({@code empty16}) is only 8 blocks tall
     * and the GameTest containment shell encases it in barriers, so the fill
     * stops UNDER the barrier ceiling long before {@code MAX_HEIGHT} can
     * trip. {@code hasCoverAbove} treats a barrier as world-edge (sky),
     * never as a roof -- that is what makes the shaft's top cell read as
     * open sky inside an encased arena exactly like it would under the real
     * overworld sky. Before that rule the barrier ceiling counted as cover,
     * and a room with a hole in its roof scanned enclosed AND roofed --
     * this test's original red.
     */
    private static void buildChimney(GameTestHelper helper, BlockPos roofHoleRel) {
        for (int y = roofHoleRel.getY() + 1; y <= roofHoleRel.getY() + 13; y++) {
            BlockPos centre = new BlockPos(roofHoleRel.getX(), y, roofHoleRel.getZ());
            helper.setBlock(centre.north(), Blocks.STONE_BRICKS);
            helper.setBlock(centre.south(), Blocks.STONE_BRICKS);
            helper.setBlock(centre.east(), Blocks.STONE_BRICKS);
            helper.setBlock(centre.west(), Blocks.STONE_BRICKS);
        }
    }

    /** Hangs a fitted warehouse plaque outside the room's north wall,
     *  facing NORTH — its "hung outside, thin wall" candidate lands two
     *  steps south, exactly the first interior cell. */
    private static BlockPos hangWarehousePlaque(GameTestHelper helper, BlockPos o) {
        BlockPos plaqueRel = o.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get()
            .defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING, Direction.NORTH));
        BlockPos abs = helper.absolutePos(plaqueRel);
        if (helper.getLevel().getBlockEntity(abs) instanceof PlaqueBlockEntity plaque) {
            plaque.insertPlan(helper.getLevel(),
                com.hearthstead.block.PlaqueItemData.stamped(
                    new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.WAREHOUSE));
        }
        return plaqueRel;
    }

    private static PlaqueBlockEntity plaqueAt(GameTestHelper helper, BlockPos rel) {
        var be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        helper.assertTrue(be instanceof PlaqueBlockEntity,
            "the plaque block entity should exist");
        return (PlaqueBlockEntity) be;
    }

    /** Every requirement's have/needed/met, for a failure message that says
     *  exactly which one came up short instead of leaving the next reader to
     *  re-derive it from the fixture's own block placement by hand. */
    private static String surveyDump(PlaqueBlockEntity plaque) {
        StringBuilder sb = new StringBuilder();
        for (com.hearthstead.building.Requirement.Status status : plaque.lastSurvey()) {
            sb.append(status.requirement().id()).append('=').append(status.have())
                .append('/').append(status.needed())
                .append(status.met() ? "(met) " : "(SHORT) ");
        }
        return sb.length() == 0 ? "<no survey recorded>" : sb.toString();
    }

    /**
     * The room genuinely SHOULD validate underground: nothing in
     * {@code RoomScanner} treats depth, block type, or "is this open sky
     * somewhere far above" as disqualifying — only enclosure (collision
     * shape) and a solid block found within {@code MAX_HEIGHT} looking up
     * from the topmost interior cell. Pinned two ways: {@link RoomScanner}
     * directly (bypassing plaque candidate selection entirely, so this
     * assertion is about the scanner, not about which candidate seed the
     * plaque happened to try first), and end-to-end through a real hung
     * plaque, which is what the owner actually saw fail.
     */
    @GameTest(batch = "room_scanner", template = "empty16", timeoutTicks = 400)
    public void anUndergroundWarehouseValidates(GameTestHelper helper) {
        floor(helper, 16);
        settlement(helper);
        BlockPos o = new BlockPos(4, 0, 4);
        buildUndergroundWarehouse(helper, o);

        helper.runAfterDelay(20, () -> {
            RoomScanner.Result direct = RoomScanner.scan(helper.getLevel(),
                helper.absolutePos(o.offset(1, 1, 1)));
            helper.assertTrue(direct != null,
                "a room buried in solid rock must still find its interior");
            helper.assertTrue(direct.enclosed(),
                "solid rock on every side is enclosure, same as any other wall — got "
                    + direct.geometryFailure());
            helper.assertTrue(!direct.skyLeak(),
                "a roof of solid rock is cover, whatever sits above IT in turn");
            helper.assertTrue(direct.volume() <= RoomScanner.MAX_HOME_VOLUME,
                "the carved room, not the rock around it, is what gets measured");
            // The gap in the first version of this test: every WAREHOUSE
            // requirement was checked EXCEPT floor_space's lower bound, so a
            // fixture that happened to fall short of 25 interior cells would
            // pass every assertion here and only surface as a mysterious
            // LINKED_INCOMPLETE from the plaque three lines later.
            helper.assertTrue(direct.volume() >= 25,
                "the room must clear WAREHOUSE's own floor_space(25), got volume="
                    + direct.volume());
            helper.assertTrue(direct.doors() >= 1, "the door must be found");
            helper.assertTrue(direct.lights() >= 2, "the torches must be found, got "
                + direct.lights());
            helper.assertTrue(
                direct.countBlocks(java.util.List.of(Blocks.CHEST, Blocks.BARREL)) >= 4,
                "the storage blocks must be found, got "
                    + direct.countBlocks(java.util.List.of(Blocks.CHEST, Blocks.BARREL)));

            BlockPos plaqueRel = hangWarehousePlaque(helper, o);
            PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
            helper.assertTrue(plaque.type() == BuildingType.WAREHOUSE,
                "the fitted plan must stamp the plaque WAREHOUSE, got " + plaque.type());
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
                "an underground warehouse must register exactly like an above-ground "
                    + "one -- got " + plaque.state() + " reason=" + plaque.lastScanReason()
                    + " survey=" + surveyDump(plaque));
            helper.succeed();
        });
    }

    /**
     * The player-visible half of the fix: a single missing roof block
     * produces a specific, located reason instead of a bare "No room
     * found" — pinned directly against {@link RoomScanner} (the exact cell
     * the leak was recorded at must sit in the hole's own column) AND
     * against {@link PlaqueBlockEntity#lastScanReason()} (the field the
     * screen and the chat message both now read from).
     */
    @GameTest(batch = "room_scanner", template = "empty16", timeoutTicks = 400)
    public void aCeilingHoleNamesTheLeak(GameTestHelper helper) {
        floor(helper, 16);
        settlement(helper);
        BlockPos o = new BlockPos(4, 0, 4);
        buildOpenWarehouse(helper, o);
        // The deliberate hole: one roof block gone, dead centre. A narrow
        // walled shaft directly above it, past MAX_HEIGHT, is what makes the
        // leak land in the extent/height cap deterministically instead of
        // depending on how far away the GameTest template's own invisible
        // containment happens to sit (see buildChimney's own doc).
        BlockPos holeRel = o.offset(3, 4, 3);
        helper.setBlock(holeRel, Blocks.AIR);
        buildChimney(helper, holeRel);

        helper.runAfterDelay(20, () -> {
            RoomScanner.Result direct = RoomScanner.scan(helper.getLevel(),
                helper.absolutePos(o.offset(1, 1, 1)));
            helper.assertTrue(direct != null, "the room still has a real interior");
            // NOT validHome(): that also requires a bed, which no warehouse
            // ever has, so it is true here regardless of the hole and proves
            // nothing about the leak. The real geometric pass/fail is
            // enclosed && !skyLeak && volume within MAX_HOME_VOLUME.
            boolean geometricallyValid = direct.enclosed() && !direct.skyLeak()
                && direct.volume() <= RoomScanner.MAX_HOME_VOLUME;
            helper.assertTrue(!geometricallyValid,
                "a room with a hole in its roof must not pass the geometric scan -- "
                    + "enclosed=" + direct.enclosed() + " skyLeak=" + direct.skyLeak()
                    + " volume=" + direct.volume());
            net.minecraft.network.chat.Component failure = direct.geometryFailure();
            helper.assertTrue(failure != null,
                "the scan recorded enough to explain why -- geometryFailure() must "
                    + "not be null once the roof leaks");
            // The recorded break -- whichever check tripped first, extent/
            // height cap (leakPos) or the roof test (skyLeakPos) -- must sit
            // in the SAME (x,z) column as the hole: that is what makes the
            // message actionable rather than merely present.
            BlockPos holeAbs = helper.absolutePos(holeRel);
            BlockPos located = !direct.enclosed() ? direct.leakPos() : direct.skyLeakPos();
            helper.assertTrue(located != null,
                "not enclosed or sky-leaking must record WHERE, not just THAT");
            helper.assertTrue(located.getX() == holeAbs.getX()
                    && located.getZ() == holeAbs.getZ(),
                "the located break should be in the hole's own column, got " + located
                    + " for a hole at " + holeAbs);

            BlockPos plaqueRel = hangWarehousePlaque(helper, o);
            PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.PLAN_INSERTED_UNLINKED,
                "a leaking roof must not register -- got " + plaque.state());
            helper.assertTrue(plaque.lastScanReason() != null,
                "PlaqueBlockEntity must carry a reason once RoomScanner computed one -- "
                    + "before this fix lastSurvey went empty and nothing else was ever set");
            helper.succeed();
        });
    }
}

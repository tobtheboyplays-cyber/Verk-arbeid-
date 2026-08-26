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
        // Warehouse furnishing: 4 storage blocks, 2 lights (both counted
        // whether they sit on the floor, as boundary furniture, or free-
        // standing in the interior air the flood fill actually walks).
        helper.setBlock(o.offset(1, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(2, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(4, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(1, 3, 5), Blocks.TORCH);
        helper.setBlock(o.offset(5, 3, 5), Blocks.TORCH);
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
        helper.setBlock(o.offset(1, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(2, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(4, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(1, 3, 5), Blocks.TORCH);
        helper.setBlock(o.offset(5, 3, 5), Blocks.TORCH);
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
            helper.assertTrue(direct.doors() >= 1, "the door must be found");
            helper.assertTrue(direct.lights() >= 2, "both torches must be found");
            helper.assertTrue(
                direct.countBlocks(java.util.List.of(Blocks.CHEST, Blocks.BARREL)) >= 4,
                "all four storage blocks must be found");

            BlockPos plaqueRel = hangWarehousePlaque(helper, o);
            PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
                "an underground warehouse must register exactly like an above-ground "
                    + "one -- got " + plaque.state() + " reason=" + plaque.lastScanReason());
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
        // The deliberate hole: one roof block gone, dead centre, open
        // straight up into the test arena with nothing above to catch it.
        BlockPos holeRel = o.offset(3, 4, 3);
        helper.setBlock(holeRel, Blocks.AIR);

        helper.runAfterDelay(20, () -> {
            RoomScanner.Result direct = RoomScanner.scan(helper.getLevel(),
                helper.absolutePos(o.offset(1, 1, 1)));
            helper.assertTrue(direct != null, "the room still has a real interior");
            helper.assertTrue(!direct.validHome(),
                "a room with a hole in its roof must not read as a valid home");
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

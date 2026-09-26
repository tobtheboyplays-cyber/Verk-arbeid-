package com.hearthstead.settlement.guard;

import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Formation slot math shared by the ground-dot preview and server slot assignment. */
class FormationMathTest {
    private static final BlockPos CENTER = new BlockPos(100, 64, 200);

    @Test
    void yawQuantisesToEightOctantsLikeMinecraftFacing() {
        assertEquals(0, FormationMath.octant(0F));      // south, +Z
        assertEquals(2, FormationMath.octant(90F));     // west, -X
        assertEquals(4, FormationMath.octant(180F));    // north
        assertEquals(4, FormationMath.octant(-180F));
        assertEquals(6, FormationMath.octant(-90F));    // east
        assertEquals(6, FormationMath.octant(270F));
        assertEquals(1, FormationMath.octant(44F));
        assertEquals(0, FormationMath.octant(22F));
        assertEquals(0, FormationMath.octant(359F));
        assertEquals(0, FormationMath.forwardX(0));
        assertEquals(1, FormationMath.forwardZ(0));
        // The commander's right hand facing south is west.
        assertEquals(-1, FormationMath.rightX(0));
        assertEquals(0, FormationMath.rightZ(0));
        // Facing north, right is east.
        assertEquals(1, FormationMath.rightX(4));
    }

    @Test
    void lineHasOneDistinctSlotPerSoldierAcrossTheFacing() {
        List<FormationMath.Slot> slots = FormationMath.line(CENTER, 4, 6, 6, 1, 2);
        assertEquals(6, slots.size());
        Set<BlockPos> unique = new HashSet<>();
        for (FormationMath.Slot slot : slots) {
            assertTrue(unique.add(slot.pos()), "duplicate slot " + slot.pos());
            assertEquals(0, slot.rank());
            assertEquals(CENTER.getZ(), slot.pos().getZ(), "north-facing line runs east-west");
            assertEquals(CENTER.getY(), slot.pos().getY());
        }
        // Centred on the aimed point: lateral -2..3 around it.
        assertTrue(slots.stream().anyMatch(s -> s.pos().equals(CENTER)));
    }

    @Test
    void narrowerWidthAddsRanksBehindTheFrontLine() {
        List<FormationMath.Slot> slots = FormationMath.line(CENTER, 0, 7, 3, 1, 2);
        assertEquals(7, slots.size());
        assertEquals(3, FormationMath.ranks(7, 3));
        int maxRank = slots.stream().mapToInt(FormationMath.Slot::rank).max().orElseThrow();
        assertEquals(2, maxRank);
        for (FormationMath.Slot slot : slots) {
            // Facing south (+Z): later ranks stand further north (lower Z).
            assertEquals(CENTER.getZ() - slot.rank() * 2, slot.pos().getZ());
        }
        assertEquals(7, new HashSet<>(slots.stream().map(FormationMath.Slot::pos).toList()).size());
    }

    @Test
    void diagonalFacingStillGivesDistinctBlocks() {
        for (int octant = 0; octant < 8; octant++) {
            List<FormationMath.Slot> slots = FormationMath.line(CENTER, octant, 16, 5, 1, 2);
            assertEquals(16, new HashSet<>(slots.stream().map(FormationMath.Slot::pos).toList()).size(),
                "octant " + octant);
        }
    }

    @Test
    void widthIsClampedToSoldiersAndBounds() {
        assertEquals(1, FormationMath.clampWidth(0, 5));
        assertEquals(5, FormationMath.clampWidth(12, 5));
        assertEquals(FormationMath.MAX_WIDTH, FormationMath.clampWidth(99, 40));
        assertEquals(6, FormationMath.defaultWidth(false, 10));
        assertEquals(3, FormationMath.defaultWidth(true, 3));
        assertEquals(0, FormationMath.ranks(0, 4));
        assertEquals(0, FormationMath.line(CENTER, 0, 0, 4, 1, 2).size());
    }

    @Test
    void archersUseLooserSpacing() {
        List<FormationMath.Slot> loose = FormationMath.line(CENTER, 4, 3, 3, FormationMath.ARCHER_SPACING, 2);
        int minX = loose.stream().mapToInt(s -> s.pos().getX()).min().orElseThrow();
        int maxX = loose.stream().mapToInt(s -> s.pos().getX()).max().orElseThrow();
        assertEquals(4, maxX - minX);
    }

    @Test
    void assignmentPairsLeftSoldiersWithLeftSlotsSoLinesDoNotCross() {
        // Facing north: right = +X. Soldiers stand behind the line, spread west to east.
        List<FormationMath.Slot> slots = FormationMath.line(CENTER, 4, 3, 3, 1, 2);
        List<BlockPos> positions = slots.stream().map(FormationMath.Slot::pos).toList();
        List<FormationMath.Soldier> soldiers = List.of(
            new FormationMath.Soldier(new UUID(0, 1), CENTER.offset(5, 0, 8)),   // east
            new FormationMath.Soldier(new UUID(0, 2), CENTER.offset(-5, 0, 8)),  // west
            new FormationMath.Soldier(new UUID(0, 3), CENTER.offset(0, 0, 8)));  // middle
        int[] pairing = FormationMath.assign(soldiers, positions, CENTER, 4);
        assertEquals(CENTER.getX() + 1, positions.get(pairing[0]).getX());
        assertEquals(CENTER.getX() - 1, positions.get(pairing[1]).getX());
        assertEquals(CENTER.getX(), positions.get(pairing[2]).getX());
    }

    @Test
    void assignmentLeavesExtraSoldiersUnassignedAndKeepsTheNearest() {
        List<BlockPos> slots = List.of(CENTER, CENTER.east());
        List<FormationMath.Soldier> soldiers = List.of(
            new FormationMath.Soldier(new UUID(0, 1), CENTER.offset(0, 0, 40)),
            new FormationMath.Soldier(new UUID(0, 2), CENTER.offset(1, 0, 2)),
            new FormationMath.Soldier(new UUID(0, 3), CENTER.offset(-1, 0, 2)));
        int[] pairing = FormationMath.assign(soldiers, slots, CENTER, 4);
        assertEquals(-1, pairing[0], "the far soldier gets no slot on a full tower");
        assertTrue(pairing[1] >= 0 && pairing[2] >= 0);
        assertTrue(pairing[1] != pairing[2]);
    }

    @Test
    void surfaceSearchStaysOnStandableCellsAndStopsWhenFull() {
        // A 3x3 tower top at y=80 around the aimed cell; everything else is air.
        BlockPos top = new BlockPos(10, 80, 10);
        Set<BlockPos> floor = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) floor.add(top.offset(dx, 0, dz));
        List<BlockPos> cells = FormationMath.surface(top, 20, 6, floor::contains);
        assertEquals(9, cells.size(), "tower holds nine archers, no more");
        assertEquals(top, cells.getFirst(), "the aimed cell comes first");
        assertTrue(floor.containsAll(cells));
        List<BlockPos> four = FormationMath.surface(top, 4, 6, floor::contains);
        assertEquals(4, four.size());
        for (BlockPos cell : four) assertTrue(cell.distManhattan(top) <= 1, "nearest first: " + cell);
    }

    @Test
    void surfaceSearchFollowsAOneStepWallWalk() {
        BlockPos start = new BlockPos(0, 70, 0);
        Set<BlockPos> walk = new HashSet<>();
        for (int x = -6; x <= 6; x++) walk.add(new BlockPos(x, x > 2 ? 71 : 70, 0));
        List<BlockPos> cells = FormationMath.surface(start, 13, 6, walk::contains);
        assertEquals(13, cells.size());
        assertTrue(cells.contains(new BlockPos(5, 71, 0)), "steps up one block along the walk");
    }

    @Test
    void escortPutsKnightsCloseAndArchersBehind() {
        BlockPos player = new BlockPos(0, 64, 0);
        List<FormationMath.Slot> knights = FormationMath.escort(player, 0, 4, false);
        List<FormationMath.Slot> archers = FormationMath.escort(player, 0, 4, true);
        assertEquals(4, knights.size());
        assertEquals(4, archers.size());
        for (FormationMath.Slot slot : knights) {
            assertTrue(slot.pos().distSqr(player) <= 3 * 3 + 1, "knight ring is tight: " + slot.pos());
            assertFalse(slot.pos().equals(player));
        }
        for (FormationMath.Slot slot : archers) {
            // Facing south (+Z): behind the commander means negative Z.
            assertTrue(slot.pos().getZ() < 0, "archers stand behind: " + slot.pos());
        }
    }

    @Test
    void wholeArmyLayersFrontToBackByRole() {
        Map<Group, Integer> depths = FormationMath.layerDepths(Map.of(
            Group.KNIGHTS, 6, Group.ARCHERS, 4, Group.HEALERS, 2));
        assertEquals(0, depths.get(Group.KNIGHTS));
        // Six knights in one rank (gap 2) plus a free row.
        assertEquals(3, depths.get(Group.ARCHERS));
        assertEquals(6, depths.get(Group.HEALERS));
        assertFalse(depths.containsKey(Group.SPEARMEN), "roles with nobody take no space");
        Map<Group, Integer> deep = FormationMath.layerDepths(Map.of(Group.KNIGHTS, 12, Group.SPEARMEN, 3));
        assertEquals(5, deep.get(Group.SPEARMEN), "two knight ranks push spearmen further back");
        assertEquals(new BlockPos(0, 64, -5), FormationMath.behind(new BlockPos(0, 64, 0), 0, 5));
    }

    @Test
    void manyLinesNeverExceedTheSoldierCap() {
        List<FormationMath.Slot> slots = FormationMath.line(CENTER, 3, 500, 16, 1, 2);
        assertEquals(FormationMath.MAX_SOLDIERS, slots.size());
        List<BlockPos> copy = new ArrayList<>(slots.stream().map(FormationMath.Slot::pos).toList());
        assertEquals(copy.size(), new HashSet<>(copy).size());
    }
}

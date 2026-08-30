package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkScannerTest {

    @Test
    void duplicateColumnHitsDoNotConsumeDistinctResultBudget() {
        WorkScanner scanner = new WorkScanner();
        AtomicInteger examined = new AtomicInteger();
        BlockPos wideTreeBase = new BlockPos(4, 64, 4);
        BlockPos secondTreeBase = new BlockPos(12, 64, 7);

        List<BlockPos> found = scanner.scanColumns(BlockPos.ZERO, 48, 32, 2,
            ignored -> examined.incrementAndGet() <= 10
                ? wideTreeBase : secondTreeBase);

        assertEquals(List.of(wideTreeBase, secondTreeBase), found,
            "a wide/duplicate tree must leave room for the next distinct tree");
        assertTrue(examined.get() >= 11,
            "the batch must continue past duplicate hits instead of stopping early");
    }

    @Test
    void nearestBoxSurveyFindsRemoteTargetBySecondBoundedBatch() {
        WorkScanner scanner = new WorkScanner();
        BlockPos min = new BlockPos(0, 0, 0);
        BlockPos max = new BlockPos(15, 15, 15);
        BlockPos farmhouse = new BlockPos(8, 1, 8);
        BlockPos ripeCrop = new BlockPos(13, 1, 13);
        Set<BlockPos> visited = new HashSet<>();

        scanner.visitBoxNearest(min, max, farmhouse, 512, visited::add);
        assertEquals(512, visited.size(),
            "one survey must keep its exact read budget");
        scanner.visitBoxNearest(min, max, farmhouse, 512, visited::add);

        assertTrue(visited.contains(ripeCrop),
            "a remote crop near its farmhouse must not wait behind a corner-first volume scan");
        assertEquals(1024, visited.size(),
            "the resumable second survey must not repeat the first batch");
        assertTrue(visited.stream().allMatch(pos -> pos.getX() >= min.getX()
                && pos.getX() <= max.getX() && pos.getY() >= min.getY()
                && pos.getY() <= max.getY() && pos.getZ() >= min.getZ()
                && pos.getZ() <= max.getZ()),
            "nearest-first discovery must never read outside the confirmed zone");
    }
}

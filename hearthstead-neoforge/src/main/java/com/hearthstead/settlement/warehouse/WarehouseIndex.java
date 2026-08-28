package com.hearthstead.settlement.warehouse;

import com.hearthstead.settlement.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Derived, bounded view of a warehouse building's storage. Chests are the
 * truth (D-A2a-2): this class never holds items and is never the basis for
 * a transfer decision without re-reading the container it points at.
 *
 * <p>Seam scope (A2a STEP 0): only the bounded container discovery below.
 * The revisioned item tally, refresh budget and {@code insert()} land with
 * the warehouse piece.
 */
public final class WarehouseIndex {

    /** Hard cap on containers a single warehouse scan may visit. */
    public static final int MAX_CONTAINERS = 64;

    /**
     * Every chest/barrel inside the building's bounds, capped at
     * {@link #MAX_CONTAINERS}. Bounds come from the plaque's room scan,
     * which is itself capped (RoomScanner MAX_EXTENT/MAX_VOLUME), so this
     * walk is doubly bounded.
     */
    public static List<BlockPos> containers(ServerLevel level, Building building) {
        List<BlockPos> found = new ArrayList<>();
        BoundingBox b = building.bounds;
        if (b == null) {
            return found;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    cursor.set(x, y, z);
                    var be = level.getBlockEntity(cursor);
                    if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) {
                        found.add(cursor.immutable());
                        if (found.size() >= MAX_CONTAINERS) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }

    private WarehouseIndex() {
    }
}

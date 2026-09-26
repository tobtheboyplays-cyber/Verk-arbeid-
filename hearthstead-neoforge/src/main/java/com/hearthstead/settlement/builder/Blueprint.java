package com.hearthstead.settlement.builder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * One loaded blueprint: its metadata and its block list in template-local
 * coordinates. Cells the template marks as {@code structure_void} are not
 * listed at all (the Builder leaves them as they are); {@code air} cells ARE
 * listed, because a planned empty cell must end up empty.
 */
public final class Blueprint {

    /** One template cell. {@code state} may be air (the cell must be cleared). */
    public record Cell(int x, int y, int z, BlockState state) {
    }

    private final BlueprintMeta meta;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<Cell> cells;
    private final int eaveY;

    public Blueprint(BlueprintMeta meta, int sizeX, int sizeY, int sizeZ, List<Cell> cells) {
        this.meta = meta;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = List.copyOf(cells);
        List<BuildOrder.Planned> planned = new ArrayList<>(cells.size());
        for (Cell cell : cells) {
            planned.add(new BuildOrder.Planned(cell.x(), cell.y(), cell.z(), idOf(cell.state())));
        }
        // The content lane may state the eave line; otherwise it is measured.
        this.eaveY = meta.eaveY() != null ? meta.eaveY() : BuildOrder.eaveY(planned);
    }

    public static String idOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public BlueprintMeta meta() {
        return meta;
    }

    public String id() {
        return meta.id();
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public List<Cell> cells() {
        return cells;
    }

    /** The eave line in template y (see {@link BuildOrder#eaveY}). */
    public int eaveY() {
        return eaveY;
    }

    /** Number of non-air cells: what the catalog calls the block count. */
    public int solidCount() {
        int n = 0;
        for (Cell cell : cells) {
            if (!cell.state().isAir()) {
                n++;
            }
        }
        return n;
    }
}

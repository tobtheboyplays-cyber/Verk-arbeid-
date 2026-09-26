package com.hearthstead.gametest;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared arena for the Builder GameTests (BUILDER lane): a flat stone floor,
 * a settlement, a registered Builder's Hut with one stocked chest, and a
 * hired Builder. Blueprints are built in memory so these tests never depend
 * on the content lane's files.
 */
public final class BuilderTestKit {

    /** Hut chest position (relative); inside the fixture hut's bounds. */
    public static final BlockPos HUT_CHEST = new BlockPos(3, 1, 4);

    public record Arena(ServerLevel level, Settlement settlement, Building hut, Container chest) {
    }

    private BuilderTestKit() {
    }

    /** Flat stone at y0, air above up to {@code height}, over {@code size} x {@code size}. */
    public static Arena arena(GameTestHelper helper, int size, int height) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(1500);
        // Keep the whole test inside the morning work block: a long build
        // must never stall on the midday meal and read as a builder bug.
        helper.onEachTick(() -> {
            long t = Math.floorMod(level.getDayTime(), 24000L);
            if (t < 1200L || t > 5000L) {
                level.setDayTime(1500);
            }
        });
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= height; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        var arena = helper.getBounds();
        data.settlements.values().removeIf(old -> old.center != null
            && arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5, old.center.getZ() + 0.5));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Builder test",
            helper.absolutePos(new BlockPos(size / 2, 1, size / 2)));
        // Just covers the arena (centre to corner ~11.3): overlapping test
        // settlements made plaques and rungs resolve to a neighbour (W3b/W4a).
        settlement.radius = size / 2 + 4;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building hut = GameTestFixtures.register(helper, settlement, BuildingType.BUILDERS_HUT, 2, 2);
        helper.setBlock(HUT_CHEST, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(HUT_CHEST));
        return new Arena(level, settlement, hut, chest);
    }

    public static SettlerEntity hireBuilder(GameTestHelper helper, Arena arena, BlockPos at, String name) {
        SettlerEntity builder = helper.spawn(ModEntities.SETTLER.get(), at);
        builder.setSettlerName(name);
        builder.bindTo(arena.settlement().id, arena.settlement().center);
        arena.settlement().putRecord(builder.getUUID(), name, Profession.NONE);
        helper.assertTrue(Employment.hire(arena.level(), arena.settlement(), arena.hut(), builder).ok(),
            "the test settler must be hired at the Builder's Hut");
        helper.assertTrue(builder.getProfession() == Profession.BUILDER, "hired as Builder");
        return builder;
    }

    /** Puts stacks into the chest (slot by slot). */
    public static void stock(Container chest, ItemStack... stacks) {
        int slot = 0;
        for (ItemStack stack : stacks) {
            int left = stack.getCount();
            while (left > 0) {
                while (!chest.getItem(slot).isEmpty()) {
                    slot++;
                }
                int put = Math.min(left, stack.getMaxStackSize());
                chest.setItem(slot, stack.copyWithCount(put));
                left -= put;
            }
        }
        chest.setChanged();
    }

    public static int count(Container container, Item item) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(item)) {
                n += container.getItem(i).getCount();
            }
        }
        return n;
    }

    /** A blueprint meta for in-memory test blueprints. */
    public static BlueprintMeta meta(String id, BlueprintMeta.Kind kind, String buildingType,
                                     int[] plaquePos, String plaqueFacing) {
        return new BlueprintMeta(id, id, "test", kind, buildingType, "test", "builders_hut", 0,
            plaquePos, plaqueFacing, null, "hearthstead:blueprints/" + id, List.of(), List.of(), null);
    }

    /** Collects cells for an in-memory blueprint. */
    public static final class Cells {
        private final List<Blueprint.Cell> cells = new ArrayList<>();

        public Cells set(int x, int y, int z, BlockState state) {
            cells.removeIf(c -> c.x() == x && c.y() == y && c.z() == z);
            cells.add(new Blueprint.Cell(x, y, z, state));
            return this;
        }

        public Cells box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, state);
                    }
                }
            }
            return this;
        }

        public List<Blueprint.Cell> list() {
            return List.copyOf(cells);
        }
    }

    public static BuildJob job(ServerLevel level, Settlement settlement, UUID id) {
        return BuildSiteSavedData.get(level).job(settlement.id, id);
    }
}

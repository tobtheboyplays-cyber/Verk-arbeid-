package com.hearthstead.settlement.builder;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla turns dirt_path and farmland into dirt as soon as the block above
 * is solid (fence gates excepted) and a neighbour updates. A blueprint that
 * ships one decays its own path the first time a door opens -- which looked
 * like the Builder changing a player's path (cottage upgrade test, 26 Sep).
 * tools/blueprints/bplib.save settles these to dirt; this keeps it so.
 */
class BlueprintGroundDecayTest {

    private static Path dir() throws IOException {
        // Anchor on one known file, as RecipeUniquenessTest does (a folder URL is not always served).
        var url = BlueprintGroundDecayTest.class.getResource("/data/hearthstead/structure/blueprints/house_cottage.nbt");
        if (url != null) {
            try {
                return Path.of(url.toURI()).getParent();
            } catch (java.net.URISyntaxException e) {
                throw new IOException(e);
            }
        }
        Path local = Path.of("src/main/resources/data/hearthstead/structure/blueprints");
        if (!Files.isDirectory(local)) {
            throw new IOException("no blueprints: url=" + url + " cwd=" + Path.of("").toAbsolutePath());
        }
        return local;
    }

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void noBlueprintHasASolidBlockOverAPathOrFarmland() throws IOException {
        List<String> bad = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.list(dir())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".nbt")).sorted()::iterator) {
                scanned++;
                CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
                List<BlockState> states = new ArrayList<>();
                for (int i = 0; i < palette.size(); i++) {
                    states.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(i)));
                }
                Map<Long, BlockState> at = new HashMap<>();
                ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag b = blocks.getCompound(i);
                    ListTag pos = b.getList("pos", Tag.TAG_INT);
                    at.put(key(pos.getInt(0), pos.getInt(1), pos.getInt(2)), states.get(b.getInt("state")));
                }
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag b = blocks.getCompound(i);
                    BlockState state = states.get(b.getInt("state"));
                    if (!state.is(Blocks.DIRT_PATH) && !state.is(Blocks.FARMLAND)) {
                        continue;
                    }
                    ListTag pos = b.getList("pos", Tag.TAG_INT);
                    BlockState above = at.get(key(pos.getInt(0), pos.getInt(1) + 1, pos.getInt(2)));
                    if (above != null && above.isSolid() && !(above.getBlock() instanceof FenceGateBlock)) {
                        bad.add(file.getFileName() + " " + pos + " " + state.getBlock() + " under " + above.getBlock());
                    }
                }
            }
        }
        assertTrue(scanned > 100, "scanned the shipped blueprints: " + scanned);
        assertTrue(bad.isEmpty(), bad.size() + " decaying path/farmland cells: " + bad);
    }

    @Test
    void theRuleSeesAFenceOnAPath() {
        // Guards the guard: the vanilla solidity the test relies on is live.
        assertTrue(Blocks.OAK_FENCE.defaultBlockState().isSolid(), "a fence is solid");
        assertTrue(Blocks.OAK_LOG.defaultBlockState().isSolid(), "a log is solid");
        assertTrue(!Blocks.WHEAT.defaultBlockState().isSolid(), "wheat is not");
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
    }
}

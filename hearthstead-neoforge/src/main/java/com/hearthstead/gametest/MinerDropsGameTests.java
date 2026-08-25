package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.MinerWorkGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The miner's drops are loot-table truth, not {@code asItem()} truth.
 *
 * <p>Regression tests for a CRITICAL smelter-audit finding: the miner used
 * to bank {@code new ItemStack(state.getBlock().asItem())} — the placeable
 * block item ({@code minecraft:iron_ore}, {@code minecraft:stone}) — while
 * Production's SMELTER recipes accept RAW_IRON/RAW_COPPER/RAW_GOLD and the
 * MASON accepts COBBLESTONE/STONE. An automated mine could therefore never
 * feed a smelter, breaking the whole mine→smelter→smithy chain
 * (docs/project/FLOWS.md). The fix routes through
 * {@link MinerWorkGoal#minedDrops}, the real loot table with a plain iron
 * pickaxe.
 *
 * <p>Each test does both halves: first the drop computation itself is
 * asserted deterministically through a real {@link
 * net.minecraft.server.level.ServerLevel} (deterministic beats theatrical),
 * then the full hired-miner loop is driven so the right item is seen
 * arriving in the mine's own chest and the wrong one is seen nowhere.
 *
 * <h2>Why the floor is planks here, not the usual stone bricks</h2>
 *
 * <p>{@code MinerWorkGoal.findStone} scans the layer one below the anchor
 * first, west-to-east — the same layer the arena floor sits on. A
 * stone-bricks floor (MINEABLE_WITH_PICKAXE) would be cut before the ore
 * this test planted; oak planks are axe work, so the scan skips them and
 * the planted block is the only thing a miner here can honestly find.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MinerDropsGameTests {

    private static void plankFloor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.OAK_PLANKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Gruvedal",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countOf(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countOf(List<ItemStack> drops, Item item) {
        int total = 0;
        for (ItemStack stack : drops) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The loot computation itself, deterministic against the real level:
     * mined {@code block} yields at least one {@code want} and not a single
     * {@code never} (the placeable block item the old code banked).
     */
    private static void assertDrops(GameTestHelper helper, BlockPos rel,
                                    net.minecraft.world.level.block.Block block,
                                    Item want, Item never) {
        List<ItemStack> drops = MinerWorkGoal.minedDrops(helper.getLevel(),
            helper.absolutePos(rel), block.defaultBlockState());
        helper.assertTrue(!drops.isEmpty(),
            "mining " + block + " must drop something, or the miner grinds "
                + "rock into nothing");
        helper.assertTrue(countOf(drops, want) >= 1,
            "mining " + block + " must yield " + want + ", got " + drops);
        helper.assertTrue(countOf(drops, never) == 0,
            "mining " + block + " must never yield the placeable block item "
                + never + " (the asItem() bug), got " + drops);
    }

    /**
     * A hired miner cutting iron ore banks RAW_IRON — the item the smelter's
     * recipes actually accept — and the block item {@code minecraft:iron_ore}
     * appears nowhere: not in the chest, not as a dropped entity.
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void aMinedIronOreArrivesAsRawIron(GameTestHelper helper) {
        plankFloor(helper, 16);
        BlockPos oreRel = new BlockPos(4, 0, 4);
        helper.setBlock(oreRel, Blocks.IRON_ORE);

        // Deterministic half: the drop computation itself, for both ore
        // variants the audit named (the deepslate state needs no placement —
        // getDrops takes the state explicitly).
        assertDrops(helper, oreRel, Blocks.IRON_ORE,
            Items.RAW_IRON, Items.IRON_ORE);
        assertDrops(helper, oreRel, Blocks.DEEPSLATE_IRON_ORE,
            Items.RAW_IRON, Items.DEEPSLATE_IRON_ORE);

        // Integration half: the whole goal, ore to chest.
        Settlement s = settlement(helper);
        Building mine = building(helper, s, BuildingType.MINE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the arena chest should be a container");

        SettlerEntity berg = settler(helper, s, "Berg", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, mine, berg).ok(),
            "a mine entrance must be able to take a miner");
        helper.assertTrue(berg.getProfession() == Profession.MINER,
            "hired into a mine, they mine");
        helper.getLevel().setDayTime(3000); // mid-morning: working hours

        helper.succeedWhen(() -> {
            helper.assertTrue(countOf(chest, Items.RAW_IRON) >= 1,
                "a mined iron ore block must bank RAW_IRON in the mine's "
                    + "chest (act=" + berg.getActivity() + ")");
            helper.assertTrue(countOf(chest, Items.IRON_ORE) == 0,
                "the placeable iron_ore block item must never reach the chest");
            helper.assertItemEntityNotPresent(Items.IRON_ORE, oreRel, 12.0);
        });
    }

    /**
     * Mined stone arrives as COBBLESTONE (no silk touch on a working
     * miner's pick) — the form the mason's recipes take by design rather
     * than by accident — and the STONE item appears nowhere.
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void aMinedStoneArrivesAsCobblestone(GameTestHelper helper) {
        plankFloor(helper, 16);
        BlockPos stoneRel = new BlockPos(4, 0, 4);
        helper.setBlock(stoneRel, Blocks.STONE);

        assertDrops(helper, stoneRel, Blocks.STONE,
            Items.COBBLESTONE, Items.STONE);

        Settlement s = settlement(helper);
        Building mine = building(helper, s, BuildingType.MINE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the arena chest should be a container");

        SettlerEntity stein = settler(helper, s, "Stein", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, mine, stein).ok(),
            "a mine entrance must be able to take a miner");
        helper.getLevel().setDayTime(3000); // mid-morning: working hours

        helper.succeedWhen(() -> {
            helper.assertTrue(countOf(chest, Items.COBBLESTONE) >= 1,
                "mined stone must bank COBBLESTONE in the mine's chest "
                    + "(act=" + stein.getActivity() + ")");
            helper.assertTrue(countOf(chest, Items.STONE) == 0,
                "the placeable stone item must never reach the chest");
            helper.assertItemEntityNotPresent(Items.STONE, stoneRel, 12.0);
        });
    }
}

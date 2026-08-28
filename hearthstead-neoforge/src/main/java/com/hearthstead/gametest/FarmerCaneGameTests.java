package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * GAPS-1 / SURVIVAL_AUDIT.md F7: {@code SugarCaneBlock} is not a
 * {@code CropBlock}, so {@link com.hearthstead.entity.ai.FarmerWorkGoal}
 * never recognised it before this pass -- no farmer could ever grow the mill
 * a supply of cane, which was the other half of F7's "grind eased" claim
 * that {@link com.hearthstead.gametest.ChainsGameTests} alone could not
 * prove (a settler's own chests were hand-stocked there on purpose, to keep
 * that test about the recipe, not the supply). These pin the two things a
 * farmer growing cane must get right that growing wheat never had to: cane
 * needs no tilled farmland to be planted on (it wants dirt/sand beside
 * water instead, vanilla's own {@code SugarCaneBlock#canSurvive} rule), and
 * a harvest must cut only the TOP segment of a stack, never the base, so the
 * base regrows on its own forever with no replant step at all.
 *
 * <p>Same arena/fixture idiom as {@code FarmerBootstrapGameTests}: a real
 * {@link Settlement}, a real {@link GameTestFixtures#register}-built
 * FARMHOUSE, a real hired farmer, DEXTERITY pinned to keep the tended plot
 * at its known 3x3 (see that file's own FLAKE-1 note for why the pin
 * matters).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FarmerCaneGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Sukkerholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building farmhouse(GameTestHelper helper, Settlement s, int x, int z) {
        return GameTestFixtures.register(helper, s, BuildingType.FARMHOUSE, x, z);
    }

    private static SettlerEntity farmer(GameTestHelper helper, Settlement s,
                                        Building farmhouse, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName("Ingrid");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Ingrid", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, farmhouse, settler).ok(),
            "setup: the farmhouse must take its first farmer");
        // FLAKE-1 (2026-08-26, see FarmerBootstrapGameTests's own note):
        // DEXTERITY is rolled from an unseeded RandomSource, so it must be
        // pinned for the tended plot's size (3x3 here, anchor +-1) to be a
        // fact the test can rely on rather than a coincidence of the roll.
        settler.attributes().pinForTest(Attribute.DEXTERITY, 10);
        return settler;
    }

    private static Container chestAt(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.CHEST);
        return (Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(new BlockPos(x, 1, z)));
    }

    private static int countIn(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int bagCane(SettlerEntity settler) {
        int total = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.is(Items.SUGAR_CANE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * A legal, still-empty cane site inside the tended 3x3 plot (anchor
     * +-1), placed OUTSIDE the farmhouse's own footprint
     * ({@code GameTestFixtures.register}'s box runs anchor..anchor+3,+2,
     * i.e. x8..11/z8..11 for a farmhouse at (8,8)) so a real, reachable
     * building never overlaps the planting site itself: dirt at rel
     * (7,0,8), a real water source immediately north of it at (7,0,7) --
     * vanilla's OWN {@code SugarCaneBlock#canSurvive} rule, not a
     * hand-duplicated copy of its dirt/sand/water-adjacency logic.
     */
    private static void placeCaneSite(GameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 0, 8), Blocks.DIRT);
        helper.setBlock(new BlockPos(7, 0, 7), Blocks.WATER);
    }

    // ---------------------------------------------------- the bootstrap ---

    /**
     * The headline: a farmhouse with raw sugar cane in its own chest (never
     * a wheat seed) and a legal, still-empty cane site inside the tended
     * plot. The farmer must find the site (via {@code isCaneSite}, never
     * the crop-tilling scan -- there is no farmland here at all, on
     * purpose, so a farmer that only ever recognised {@code CropBlock} soil
     * could never act), withdraw cane from the chest, and plant a fresh
     * base beside the water it found.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "farmer_cane")
    public void farmerPlantsAFreshCaneBaseBesideWaterFromChestStock(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building house = farmhouse(helper, s, 8, 8);
        placeCaneSite(helper);
        Container chest = chestAt(helper, 10, 10);
        chest.setItem(0, new ItemStack(Items.SUGAR_CANE, 6));
        SettlerEntity ingrid = farmer(helper, s, house, 8, 8);

        helper.succeedWhen(() -> {
            BlockState planted = helper.getBlockState(new BlockPos(7, 1, 8));
            String diag = " [act=" + ingrid.getActivity() + " pos=" + ingrid.blockPosition()
                + " chestCane=" + countIn(chest, Items.SUGAR_CANE)
                + " bagCane=" + bagCane(ingrid) + "]";
            helper.assertTrue(planted.is(Blocks.SUGAR_CANE),
                "the farmer must plant a fresh cane base beside the water it found, "
                    + "with no farmland and no wheat seed anywhere in this arena" + diag);
            // Chest truth: 6 cane went in; every one is accounted for
            // (chest + bag + the one now standing in the world).
            int accounted = countIn(chest, Items.SUGAR_CANE) + bagCane(ingrid) + 1;
            helper.assertTrue(accounted == 6,
                "sugar cane must be conserved exactly (chest + bag + planted == 6), got "
                    + accounted + diag);
        });
    }

    // ------------------------------------------------------ the harvest ---

    /**
     * The other half: a farmer harvesting an already-grown cane stack must
     * cut ONLY the top segment (the one with cane, not dirt/sand, beneath
     * it) and leave the base standing -- cutting the base too would kill
     * the stalk's ability to regrow on its own, which is the entire reason
     * sugar cane needs no replant path at all. Also pins that a cane
     * harvest never fires the crop replant's WORK_SOW activity, which
     * {@code Items.SUGAR_CANE} being a real {@code BlockItem} for
     * {@code Blocks.SUGAR_CANE} could otherwise trigger by accident (see
     * {@code FarmerWorkGoal#tickHarvest}'s own comment on exactly this).
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "farmer_cane")
    public void farmerHarvestsOnlyTheTopSegmentAndLeavesTheBaseToRegrow(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building house = farmhouse(helper, s, 8, 8);
        placeCaneSite(helper);
        // A real, already-standing two-tall stack: base (rel y1) + one
        // grown segment (rel y2) -- only the top one is a legal harvest
        // target (isMatureCane requires cane, not dirt, beneath it).
        helper.setBlock(new BlockPos(7, 1, 8), Blocks.SUGAR_CANE.defaultBlockState());
        helper.setBlock(new BlockPos(7, 2, 8), Blocks.SUGAR_CANE.defaultBlockState());
        SettlerEntity ingrid = farmer(helper, s, house, 8, 8);

        helper.succeedWhen(() -> {
            BlockState baseNow = helper.getBlockState(new BlockPos(7, 1, 8));
            BlockState topNow = helper.getBlockState(new BlockPos(7, 2, 8));
            String diag = " [act=" + ingrid.getActivity() + " pos=" + ingrid.blockPosition()
                + " base=" + baseNow + " top=" + topNow + " bagCane=" + bagCane(ingrid) + "]";
            helper.assertTrue(ingrid.getActivity() != SettlerActivity.WORK_SOW,
                "a cane harvest must never trigger the crop replant animation -- cane "
                    + "needs no replant at all" + diag);
            helper.assertTrue(topNow.isAir(),
                "the top segment must actually be cut" + diag);
            helper.assertTrue(baseNow.is(Blocks.SUGAR_CANE),
                "the base segment must survive the harvest so it keeps regrowing on "
                    + "its own -- killing it would undo the entire point of cane "
                    + "needing no replant step" + diag);
        });
    }
}

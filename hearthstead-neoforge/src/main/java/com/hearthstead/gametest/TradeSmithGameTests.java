package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
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

import java.util.UUID;

/**
 * ACCEPT-JOBS audit (2026-08-26): the smithy has stood in {@code
 * Employment.TRADES} since SMITH landed, and {@code FuelGameTests} pins its
 * whole fuel/effort arithmetic across SMELTER and SMITHY together -- but
 * every one of those runs drives {@link com.hearthstead.building.Production}
 * directly, the exact idiom this audit's own instructions name as proving
 * the recipe table, never the trade (a settler is never once hired into the
 * SMITHY there). {@code EmploymentGameTests#craftingTrainsTheTradeItPractises}
 * hires a smith but only checks the STRENGTH training math with the strike
 * count faked by a loop -- {@code CrafterWorkGoal} never runs. This is the
 * missing proof: {@link Employment#hire} at a real SMITHY, the settler's own
 * {@code CrafterWorkGoal} (never {@code Production.run} called directly),
 * WORK_HAMMER actually observed, and a real iron tool sitting in the
 * smithy's own chest afterward. Mirrors {@code
 * ArmouryGameTests#aHiredArmourerActuallyForgesAHelmetIntoTheArmouryChest}.
 *
 * <p>Stocked with exactly two iron ingots -- the SMITHY table's cheapest
 * rough recipes (hoe, sword) both cost exactly 2 ingot, and the pricier ones
 * (axe, pickaxe at 3, bloom_ingot needing IRON_BLOOM the arena never seeds)
 * cannot run at all with only two on hand, so whichever of hoe/sword {@code
 * Production.ready} picks, the conservation identity is exact either way.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeSmithGameTests {

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
        Settlement s = new Settlement(UUID.randomUUID(), "Smedholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        return GameTestFixtures.register(helper, s, type, x, z);
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

    private static int countOf(Container chest, Item item) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The whole loop, end to end: a smithy's chest holding two iron ingots
     * and a shovel-full of charcoal, a settler hired into the building, and
     * a real iron tool that did not exist before -- with no smelter, mine or
     * warehouse anywhere in the world (D-007).
     */
    @GameTest(batch = "trade_smith", template = "empty16", timeoutTicks = 700)
    public void aHiredSmithActuallyForges(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smithy = building(helper, s, BuildingType.SMITHY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        chest.setItem(1, new ItemStack(Items.CHARCOAL, 4));

        SettlerEntity smed = settler(helper, s, "Torvald", 4, 4);
        smed.attributes().pinForTest(com.hearthstead.entity.Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, smithy, smed).ok(),
            "a smithy must be able to take a smith");
        helper.assertTrue(smed.getProfession() == Profession.SMITH,
            "hired into a smithy, they smith");
        helper.assertTrue(
            Employment.motionOf(BuildingType.SMITHY) == SettlerActivity.WORK_HAMMER,
            "the smith's motion must be its own, not a shared work loop");

        helper.getLevel().setDayTime(3000);

        boolean[] sawHammering = new boolean[1];

        helper.succeedWhen(() -> {
            if (smed.getActivity() == SettlerActivity.WORK_HAMMER) {
                sawHammering[0] = true;
            }
            int ingot = countOf(chest, Items.IRON_INGOT);
            int hoe = countOf(chest, Items.IRON_HOE);
            int sword = countOf(chest, Items.IRON_SWORD);
            int tools = hoe + sword;
            // Both candidate rough recipes cost exactly 2 ingot for 1 tool,
            // so the conservation identity is the same either way.
            helper.assertTrue(ingot + tools * 2 == 2,
                "two ingot must become exactly one tool with nothing left over: "
                    + "ingot=" + ingot + " hoe=" + hoe + " sword=" + sword);
            helper.assertTrue(tools > 0,
                "a settler hired into the smithy through Employment.hire must "
                    + "actually forge a tool (activity=" + smed.getActivity() + ")");
            helper.assertTrue(sawHammering[0],
                "the smith must actually be seen performing WORK_HAMMER at some "
                    + "point, not just have the output appear while idle");
        });
    }
}

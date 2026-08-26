package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * ACCEPT-JOBS audit (2026-08-26): BREWER, like MILLER, has stood in {@code
 * Employment.TRADES} since the coordinator's mill/brewery addendum, but the
 * only gametest coverage of {@code BuildingType.BREWERY} is {@code
 * ChainsGameTests}, which drives {@code Production.run} directly (its own
 * class doc names the idiom as pinning the recipe table, not the trade).
 * No test hires a brewer and lets {@code CrafterWorkGoal} do the work. This
 * closes it: {@link Employment#hire} at a real BREWERY, real ale banked in
 * the building's own chest, WORK_STOKE observed.
 *
 * <p>Stocked with exactly three wheat and real charcoal (BREWERY burns --
 * {@link com.hearthstead.building.Fuel#burns}): the BREWERY table's rough
 * {@code ale} recipe costs exactly 3 wheat for 1 ale, and with only three on
 * hand neither {@code malt} (needs 4 wheat) nor {@code ale_malt} (needs
 * MALT, never seeded) can run, so the ale recipe is the only one that fits
 * and the conservation identity is exact.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeBrewerGameTests {

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
        Settlement s = new Settlement(UUID.randomUUID(), "Bryggerholm",
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
     * The whole loop, end to end: a brewery's chest holding wheat and
     * charcoal, a settler hired into the building, and real ale that did
     * not exist before -- with no farmhouse, smelter or warehouse anywhere
     * in the world (D-007).
     */
    @GameTest(batch = "trade_brewer", template = "empty16", timeoutTicks = 700)
    public void aHiredBrewerActuallyBrewsAle(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building brewery = building(helper, s, BuildingType.BREWERY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;
        chest.setItem(0, new ItemStack(Items.WHEAT, 3));
        chest.setItem(1, new ItemStack(Items.CHARCOAL, 4));

        SettlerEntity brygge = settler(helper, s, "Signe", 4, 4);
        brygge.attributes().pinForTest(com.hearthstead.entity.Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, brewery, brygge).ok(),
            "a brewery must be able to take a brewer");
        helper.assertTrue(brygge.getProfession() == Profession.BREWER,
            "hired into a brewery, they brew");
        helper.assertTrue(
            Employment.motionOf(BuildingType.BREWERY) == SettlerActivity.WORK_STOKE,
            "the brewer's motion must be its own, not a shared work loop");

        helper.getLevel().setDayTime(3000);

        boolean[] sawStoking = new boolean[1];

        helper.succeedWhen(() -> {
            if (brygge.getActivity() == SettlerActivity.WORK_STOKE) {
                sawStoking[0] = true;
            }
            int wheat = countOf(chest, Items.WHEAT);
            int ale = countOf(chest, ModItems.ALE.get());
            // Only the rough "ale" recipe (3 wheat -> 1 ale) can possibly
            // run with three wheat and no malt seeded -- see class doc.
            helper.assertTrue(wheat + ale * 3 == 3,
                "three wheat must become exactly one ale with nothing left "
                    + "over: wheat=" + wheat + " ale=" + ale);
            helper.assertTrue(ale > 0,
                "a settler hired into the brewery through Employment.hire must "
                    + "actually brew ale (activity=" + brygge.getActivity() + ")");
            helper.assertTrue(sawStoking[0],
                "the brewer must actually be seen performing WORK_STOKE at some "
                    + "point, not just have the output appear while idle");
        });
    }
}

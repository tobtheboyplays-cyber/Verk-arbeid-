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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TRADES-1's own production-loop proof for HUNTER: a real wild herd near
 * the lodge, a settler hired into it, real meat and hide in the lodge's own
 * chest that did not exist before — AND, in the same run, proof that
 * {@code HunterWorkGoal}'s population floor actually holds: with exactly one
 * more cow than {@code MIN_SPECIES_POPULATION}, a hunter given ample effort
 * to attempt several kills over the test window must still never take the
 * herd below the floor. "a hunter that clears every animal in the chunk
 * permanently is a strictly worse pasture" — this is the test that would go
 * red if the floor check were ever deleted.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeHunterGameTests {

    /** Mirrors HunterWorkGoal.MIN_SPECIES_POPULATION exactly. */
    private static final int MIN_SPECIES_POPULATION = 4;

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
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
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

    @GameTest(batch = "trade_hunter", template = "empty16", timeoutTicks = 1400)
    public void aHiredHunterHuntsButNeverBreaksTheFloor(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lodge = building(helper, s, BuildingType.HUNTERS_LODGE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;

        // MIN_SPECIES_POPULATION + 1 real wild cows, spread around the
        // arena, well within HUNT_RADIUS (28) of the lodge's anchor and
        // outside its own small bounds -- never conjured by the goal.
        int[][] spots = {{10, 10}, {11, 9}, {9, 11}, {12, 11}, {10, 13}};
        List<Cow> herd = new ArrayList<>();
        for (int[] spot : spots) {
            herd.add(helper.spawn(EntityType.COW, new BlockPos(spot[0], 1, spot[1])));
        }
        helper.assertTrue(herd.size() == MIN_SPECIES_POPULATION + 1,
            "the fixture herd must be exactly one over the floor, or this test "
                + "cannot tell a working floor from an unlucky short run");

        SettlerEntity orn = settler(helper, s, "Orn", 4, 4);
        // Ample stamina: this hunter has effort for several kill attempts
        // over the test window, so the floor is what has to stop it, not an
        // exhausted labour pool giving a false pass.
        orn.attributes().pinForTest(Attribute.STAMINA, 100);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, lodge, orn).ok(),
            "a hunters lodge must be able to take a hunter");
        helper.assertTrue(orn.getProfession() == Profession.HUNTER,
            "hired into a lodge, they hunt");

        helper.getLevel().setDayTime(3000);

        boolean[] sawHunting = new boolean[1];

        helper.succeedWhen(() -> {
            if (orn.getActivity() == SettlerActivity.WORK_HUNT) {
                sawHunting[0] = true;
            }
            int alive = 0;
            for (Cow c : herd) {
                if (c.isAlive()) {
                    alive++;
                }
            }
            // THE POPULATION FLOOR, checked every tick this test polls, not
            // only at the end: population only ever falls, so once it dips
            // under the floor it never recovers on its own -- a hard fail()
            // here catches the violation on the tick it happens rather than
            // waiting for a timeout to report it as a vaguer "never
            // succeeded".
            if (alive < MIN_SPECIES_POPULATION) {
                helper.fail("HunterWorkGoal's population floor was violated: only "
                    + alive + " of " + herd.size() + " cows remain alive "
                    + "(floor is " + MIN_SPECIES_POPULATION + ")");
            }
            int meat = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(Items.BEEF) || stack.is(Items.LEATHER)) {
                    meat += stack.getCount();
                }
            }
            helper.assertTrue(meat > 0,
                "a hired hunter must bring back real meat or hide into the lodge's "
                    + "own chest (activity=" + orn.getActivity() + ", alive=" + alive + ")");
            helper.assertTrue(sawHunting[0],
                "the hunter must actually be seen performing WORK_HUNT at some point, "
                    + "not just have the output appear while idle");
        });
    }
}

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
 * JOB_STANDARD point 11 — the smelter's own production-loop proof.
 *
 * <p>Mirrors {@code EmploymentGameTests#aHiredBakerActuallyBakes} and
 * {@code TradeButcherGameTests#aHiredButcherActuallyCooks}: a room with raw
 * iron in its own chest, a settler hired into it, and iron ingots that did not
 * exist before — with no mine, no smithy and no warehouse anywhere in the
 * world (D-007, "a building works alone").
 *
 * <p>It also checks the two things a chest-truth economy can get wrong
 * silently: that {@link com.hearthstead.entity.SettlerActivity#WORK_STOKE} is
 * actually observed on the settler rather than the output appearing while
 * they idle, and that the 1:1 iron recipe conserves items exactly — one raw
 * iron consumed for one iron ingot produced, never a net gain or a silent
 * loss (INV-3). The seed stays under the iron_bloom threshold so the bloom
 * recipe (a deliberate yield multiplier) cannot muddy this proof.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeSmelterGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /**
     * A settlement the entity layer can actually find.
     *
     * <p>Registered with {@link com.hearthstead.settlement.SettlementSavedData},
     * because {@code settler.settlement()} resolves by id through the manager —
     * a bare Settlement object is invisible to every goal, and the symptom is a
     * settler who simply stands there with no error anywhere (the known past
     * failure {@code EmploymentGameTests.settlement} documents).
     */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        // Kept small on purpose: GameTest arenas sit close together and
        // SettlementManager.at() resolves by radius, so a generous test
        // settlement can answer for a NEIGHBOUR's hearth instead of its own.
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

    private static int countAll(Container chest) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            total += chest.getItem(slot).getCount();
        }
        return total;
    }

    /**
     * The whole loop, end to end: a smelter's chest holding raw iron, a
     * settler hired into the building, and iron ingots that did not exist
     * before — with nothing else built anywhere in the world.
     *
     * <p>Fails the way the job standard demands: if the smelter does nothing,
     * {@code ingots > 0} never becomes true and the test times out rather than
     * passing on a compile check. If the motion is ever wired back to a shared
     * generic work loop, {@code sawStoking} never becomes true either.
     */
    @GameTest(template = "empty16", timeoutTicks = 600)
    public void aHiredSmelterActuallySmelts(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smelter = building(helper, s, BuildingType.SMELTER, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;
        // Two raw iron, deliberately BELOW iron_bloom's threshold of three:
        // with the need-aware selector the bloom recipe would otherwise run
        // first (its +1-item yield is by design, not a conservation bug) and
        // this test is the 1:1 path's proof. ChainsGameTests owns the bloom
        // ledger; here two in must become exactly two out.
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        // Fuel, since FUEL-1 landed: a forge burns one charcoal per batch
        // (DESIGN R20 upkeep). Two, so both smelts can run.
        chest.setItem(1, new ItemStack(Items.CHARCOAL, 2));
        int before = countAll(chest);

        SettlerEntity brann = settler(helper, s, "Brann", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, smelter, brann).ok(),
            "a forge must be able to take a smelter");
        helper.assertTrue(brann.getProfession() == Profession.SMELTER,
            "hired into a smelter, they smelt");
        helper.assertTrue(
            Employment.motionOf(BuildingType.SMELTER) == SettlerActivity.WORK_STOKE,
            "the smelter's motion must be its own, not a shared work loop");

        // Mid-morning: working hours, so the trade goal is allowed to run.
        helper.getLevel().setDayTime(3000);

        boolean[] sawStoking = new boolean[1];

        helper.succeedWhen(() -> {
            if (brann.getActivity() == SettlerActivity.WORK_STOKE) {
                sawStoking[0] = true;
            }
            int ingots = countOf(chest, Items.IRON_INGOT);
            int raw = countOf(chest, Items.RAW_IRON);
            int total = countAll(chest);
            // Item conservation (chest truth, INV-3): one raw iron becomes
            // one iron ingot, and one charcoal BURNS per batch -- the single
            // sanctioned sink (Fuel's class doc). So the grand total may fall
            // by exactly the number of ingots made and by nothing else:
            // never up (duplication), never further down (a silent loss).
            helper.assertTrue(total == before - ingots,
                "smelting must conserve items exactly (minus burned fuel): "
                    + "started with " + before + ", made " + ingots
                    + " ingot(s), so the chest must hold " + (before - ingots)
                    + " but holds " + total + " (raw=" + raw + ")");
            helper.assertTrue(ingots > 0,
                "a hired smelter standing at a forge full of raw iron must "
                    + "produce ingots (activity=" + brann.getActivity() + ")");
            helper.assertTrue(sawStoking[0],
                "the smelter must actually be seen performing WORK_STOKE at some "
                    + "point, not just have the output appear while idle");
        });
    }
}

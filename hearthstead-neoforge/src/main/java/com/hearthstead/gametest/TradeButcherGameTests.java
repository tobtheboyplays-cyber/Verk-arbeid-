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
 * JOB_STANDARD point 11 — the butcher's own production-loop proof.
 *
 * <p>Mirrors {@code EmploymentGameTests#aHiredBakerActuallyBakes}: a room with
 * raw meat in its own chest, a settler hired into it, and cooked meat that did
 * not exist before — with no smoker anywhere but the plaque's own room, and no
 * pasture, hunter's lodge or warehouse in the world at all (D-007, the same
 * "a building works alone" promise the bakery test pins for baking).
 *
 * <p>It also checks the two things a chest-truth economy can get wrong without
 * throwing an exception anywhere: that {@link com.hearthstead.entity.SettlerActivity#WORK_CLEAVE}
 * is actually observed on the settler rather than the output simply appearing
 * while they idle, and that the exchange conserves items exactly — one
 * porkchop consumed for one cooked porkchop produced, never a net gain or a
 * silent loss (INV-3, chest truth: logistics must conserve items).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeButcherGameTests {

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
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
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

    private static int countAll(Container chest) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            total += chest.getItem(slot).getCount();
        }
        return total;
    }

    /**
     * The whole loop, end to end: a butcher's chest holding raw porkchops, a
     * settler hired into the building, and cooked porkchops that did not exist
     * before — with nothing else built anywhere in the world.
     *
     * <p>Fails the way the job standard demands: if the butcher does nothing,
     * {@code cooked > 0} never becomes true and the test times out rather than
     * passing on a compile check. If the motion is ever wired back to a shared
     * generic work loop, {@code sawCleaving} never becomes true either.
     */
    @GameTest(batch = "trade_butcher", template = "empty16", timeoutTicks = 600)
    public void aHiredButcherActuallyCooks(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building butcher = building(helper, s, BuildingType.BUTCHER, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;
        chest.setItem(0, new ItemStack(Items.PORKCHOP, 10));
        int before = countAll(chest);

        SettlerEntity gunnar = settler(helper, s, "Gunnar", 4, 4);
        // FLAKE-1 (2026-08-26): STAMINA is rolled from the entity's own
        // unseeded RandomSource, so it differs every run. This test has no
        // fixed batch count or tick budget to protect (it only checks
        // cooked > 0), but pinning keeps this fixture consistent with its
        // sibling trade tests and removes any future dependency on the roll.
        gunnar.attributes().pinForTest(com.hearthstead.entity.Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, butcher, gunnar).ok(),
            "a butcher's block must be able to take a butcher");
        helper.assertTrue(gunnar.getProfession() == Profession.BUTCHER,
            "hired into a butcher's, they butcher");
        helper.assertTrue(
            Employment.motionOf(BuildingType.BUTCHER) == SettlerActivity.WORK_CLEAVE,
            "the butcher's motion must be its own, not a shared work loop");

        // Mid-morning: working hours, so the trade goal is allowed to run.
        helper.getLevel().setDayTime(3000);

        boolean[] sawCleaving = new boolean[1];

        helper.succeedWhen(() -> {
            if (gunnar.getActivity() == SettlerActivity.WORK_CLEAVE) {
                sawCleaving[0] = true;
            }
            int cooked = countOf(chest, Items.COOKED_PORKCHOP);
            int raw = countOf(chest, Items.PORKCHOP);
            int total = countAll(chest);
            // Item conservation (chest truth, INV-3): one porkchop becomes one
            // cooked porkchop, so the chest's grand total must never move —
            // not up (duplication) and not down (a silent loss).
            helper.assertTrue(total == before,
                "cooking meat must conserve items exactly: started with "
                    + before + ", chest now holds " + total
                    + " (raw=" + raw + " cooked=" + cooked + ")");
            helper.assertTrue(cooked > 0,
                "a hired butcher standing at a smoker full of porkchops must "
                    + "produce cooked meat (activity=" + gunnar.getActivity() + ")");
            helper.assertTrue(sawCleaving[0],
                "the butcher must actually be seen performing WORK_CLEAVE at some "
                    + "point, not just have the output appear while idle");
        });
    }
}

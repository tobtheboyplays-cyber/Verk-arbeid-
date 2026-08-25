package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The sawyer's production loop, end to end: a sawmill with oak logs in its
 * chest, a settler hired into it, and planks that did not exist before.
 *
 * <p>Mirrors {@code EmploymentGameTests#aHiredBakerActuallyBakes} — same
 * discipline, a different trade ({@link BuildingType#SAWMILL},
 * {@link Profession#SAWYER}, motion {@code WORK_SAW}) — proving that D-007
 * (a building works alone: no lumber camp and no warehouse anywhere in the
 * world) and INV-3 (chest truth: nothing is ever destroyed or duplicated)
 * hold for THIS trade specifically, not merely for the one already under
 * test in that file.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeSawyerGameTests {

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
     * because {@code settler.settlement()} resolves by id through the manager:
     * a bare Settlement object is invisible to every goal, and the symptom is
     * a settler who simply stands there with no error anywhere. Radius kept
     * small (6) so this test's settlement cannot hijack a neighbouring
     * arena's hearth (GameTest arenas sit close together and
     * SettlementManager.at() resolves by radius).
     */
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

    private static int countItem(net.minecraft.world.Container container,
                                 net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The whole loop, end to end: a room with oak logs in its chest, a
     * settler hired into it, and planks that did not exist before — with no
     * lumber camp and no warehouse anywhere in the world (D-007).
     *
     * <p>Also the conservation proof for this trade: every plank or timber
     * beam the chest gains must be accounted for by logs the chest gave up,
     * at each recipe's own ratio (one log in, six planks out; three logs in,
     * two beams out — the need-aware selector alternates them), never more
     * and never fewer (INV-3) — and the settler must actually be seen doing the work
     * (motion {@code WORK_SAW}), so a settler who did nothing cannot pass
     * this test by the recipe running itself.
     */
    @GameTest(batch = "trade_sawyer", template = "empty16", timeoutTicks = 600)
    public void aHiredSawyerActuallySaws(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building sawmill = building(helper, s, BuildingType.SAWMILL, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        net.minecraft.world.level.block.entity.BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof net.minecraft.world.Container,
            "the arena chest should be a container");
        net.minecraft.world.Container chest = (net.minecraft.world.Container) be;
        int startLogs = 12;
        chest.setItem(0, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.OAK_LOG, startLogs));

        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, sawmill, astrid).ok(),
            "a sawmill must be able to take a sawyer");
        helper.assertTrue(astrid.getProfession() == Profession.SAWYER,
            "hired into a sawmill, they saw");

        // Mid-morning: working hours, so the trade goal is allowed to run.
        helper.getLevel().setDayTime(3000);

        final boolean[] sawSawing = {false};
        helper.succeedWhen(() -> {
            if (astrid.getActivity() == com.hearthstead.entity.SettlerActivity.WORK_SAW) {
                sawSawing[0] = true;
            }
            int planks = countItem(chest, net.minecraft.world.item.Items.OAK_PLANKS);
            int beams = countItem(chest,
                com.hearthstead.registry.ModItems.TIMBER_BEAM.get());
            int logsLeft = countItem(chest, net.minecraft.world.item.Items.OAK_LOG);
            int consumed = startLogs - logsLeft;
            if (planks > 0 || beams > 0) {
                helper.assertTrue(consumed > 0,
                    "output appeared without a single log leaving the chest — "
                        + "that is duplication, not production");
                // The need-aware selector interleaves the sawmill's two oak
                // recipes (3 logs -> 2 beams, 1 log -> 6 planks), so the
                // ledger must balance across BOTH outputs, not assume planks
                // alone: every consumed log is either 1/6 of the planks or
                // 3/2 of the beams, exactly.
                helper.assertTrue(planks % 6 == 0 && beams % 2 == 0,
                    "outputs must arrive in whole batches: planks=" + planks
                        + " beams=" + beams);
                helper.assertTrue(consumed == planks / 6 + (beams / 2) * 3,
                    "conservation broken: " + consumed + " log(s) gone vs "
                        + planks + " plank(s) + " + beams + " beam(s)");
            }
            helper.assertTrue(planks > 0,
                "a hired sawyer standing in a sawmill full of oak logs must "
                    + "produce planks (activity=" + astrid.getActivity() + ")");
            helper.assertTrue(sawSawing[0],
                "the sawyer must actually pass through WORK_SAW while doing "
                    + "it, not produce output with no motion of its own");
        });
    }
}

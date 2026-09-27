package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.BagTransferPresentation;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.WorkContainerKind;
import com.hearthstead.entity.animation.BagToChestAnimationContract;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.work.FisherEvidenceSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The Fisher's catch reaches the rack through the shared bag-to-chest unload
 * session (GroundedBagUnload): one fish per planted cycle, inserted only at the
 * contract's deposit tick, credited exactly once, and an interruption mid-reach
 * loses nothing. Own batch; day time only ever moves forwards.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FisherRackUnloadGameTests {
    private static final String LANDED = "HearthsteadFisherLanded";
    private static final BlockPos RACK = new BlockPos(9, 1, 8);

    @GameTest(batch = "fisher_rack_unload", template = "empty16", timeoutTicks = 600)
    public void fishReachRackOncePerContractDepositTick(GameTestHelper helper) {
        Fixture f = fixture(helper, "Unloader", 3);
        long base = workMorning(helper);
        helper.getLevel().setDayTime(base + 3000L);
        int[] lastRack = {0};
        int[] deposits = {0};
        boolean[] sackDownBeforeFirst = {false};
        helper.onEachTick(() -> {
            int rack = fish(f.rack);
            BagTransferPresentation view = f.fisher.bagTransferPresentation();
            if (deposits[0] == 0 && view.active()
                && f.fisher.placedWorkContainerKind() == WorkContainerKind.SACK) {
                sackDownBeforeFirst[0] = true;
            }
            if (rack < lastRack[0]) helper.fail("rack lost fish: " + lastRack[0] + " -> " + rack);
            if (rack > lastRack[0]) {
                if (rack - lastRack[0] != 1) helper.fail("fish must land one unit per cycle, saw +" + (rack - lastRack[0]));
                if (!view.active() || !view.committed()
                    || view.clock() != BagToChestAnimationContract.DEPOSIT_COMMIT_TICK)
                    helper.fail("fish landed outside the contract deposit tick: active=" + view.active()
                        + " committed=" + view.committed() + " clock=" + view.clock());
                if (!sackDownBeforeFirst[0]) helper.fail("sack must be set down before the first fish lands");
                deposits[0]++;
                lastRack[0] = rack;
            }
            if (rack + fish(f.fisher.bag) != 3) helper.fail("fish duplicated or lost: rack=" + rack
                + " bag=" + fish(f.fisher.bag));
            long credited = FisherEvidenceSavedData.storedCount(helper.getLevel(), f.settlement, f.fishery);
            if (credited != rack) helper.fail("credit must follow each real insert exactly once: credited="
                + credited + " rack=" + rack);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(deposits[0] == 3 && fish(f.rack) == 3 && fish(f.fisher.bag) == 0,
                "all three fish must reach the rack via the unload session: rack=" + fish(f.rack)
                    + " bag=" + fish(f.fisher.bag) + " activity=" + f.fisher.getActivity()
                    + " route=" + f.fisher.routeFailureNote());
            helper.assertTrue(!f.fisher.bagTransferPresentation().active()
                    && f.fisher.placedWorkContainerKind() == WorkContainerKind.NONE,
                "sack must be lifted and the transfer claim released after the last fish");
            helper.assertTrue(f.fisher.getPersistentData().getInt(LANDED) == 0,
                "landed counter must be fully consumed by the real inserts");
        });
    }

    @GameTest(batch = "fisher_rack_unload", template = "empty16", timeoutTicks = 700)
    public void interruptedRackUnloadLosesNothing(GameTestHelper helper) {
        Fixture f = fixture(helper, "Interrupted", 2);
        long base = workMorning(helper);
        helper.getLevel().setDayTime(base + 3000L);
        int[] phase = {0};
        int[] waited = {0};
        helper.onEachTick(() -> {
            int rack = fish(f.rack), bag = fish(f.fisher.bag);
            if (rack + bag != 2) helper.fail("interruption lost or duplicated fish: rack=" + rack + " bag=" + bag);
            long credited = FisherEvidenceSavedData.storedCount(helper.getLevel(), f.settlement, f.fishery);
            if (credited != rack) helper.fail("credit must equal real inserts: credited=" + credited + " rack=" + rack);
            BagTransferPresentation view = f.fisher.bagTransferPresentation();
            if (phase[0] == 0 && view.active() && !view.committed()
                && view.clock() >= BagToChestAnimationContract.ITEM_HAND_CONTACT_TICK) {
                // Hands are in the sack, fish not yet on the rack: end the shift.
                helper.getLevel().setDayTime(base + 5600L);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                if (!view.active() && f.fisher.placedWorkContainerKind() == WorkContainerKind.NONE) {
                    helper.getLevel().setDayTime(base + 7100L);
                    phase[0] = 2;
                } else if (++waited[0] > 10) {
                    helper.fail("interruption must release the planted sack and transfer claim");
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 2, "unload was never interrupted mid-reach (phase=" + phase[0] + ")");
            helper.assertTrue(fish(f.rack) == 2 && fish(f.fisher.bag) == 0
                    && !f.fisher.bagTransferPresentation().active(),
                "after the shift resumes every fish reaches the rack exactly once: rack=" + fish(f.rack)
                    + " bag=" + fish(f.fisher.bag) + " route=" + f.fisher.routeFailureNote());
            helper.assertTrue(f.fisher.getPersistentData().getInt(LANDED) == 0,
                "landed counter must be consumed exactly once across the interruption");
        });
    }

    private record Fixture(Settlement settlement, Building fishery, SettlerEntity fisher, Container rack) {}

    private static Fixture fixture(GameTestHelper helper, String name, int landed) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICKS);
        }
        // Sealed walls: outside terrain can never flow into the controlled box.
        for (int y = 1; y <= 4; y++) for (int i = -1; i <= 16; i++) {
            helper.setBlock(new BlockPos(i, y, -1), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(i, y, 16), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(-1, y, i), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(16, y, i), Blocks.STONE_BRICKS);
        }
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Rackholm", helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building fishery = GameTestFixtures.register(helper, s, BuildingType.FISHERY, 8, 8);
        helper.setBlock(RACK, ModBlocks.FISH_RACK.get());
        SettlerEntity fisher = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 7));
        fisher.setSettlerName(name);
        fisher.bindTo(s.id, s.center);
        s.putRecord(fisher.getUUID(), name, Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, fishery, fisher).ok(), "fishery must hire the fisher");
        fisher.bag.addItem(new ItemStack(Items.COD, landed));
        // These fish stand for landed casts: the goal owns their evidence credit.
        fisher.getPersistentData().putInt(LANDED, landed);
        Container rack = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(RACK));
        return new Fixture(s, fishery, fisher, rack);
    }

    /** Start of a working day at or after now; never moves time backwards. */
    private static long workMorning(GameTestHelper helper) {
        long now = helper.getLevel().getDayTime();
        long base = now - Math.floorMod(now, 24000L);
        if (Math.floorMod(now, 24000L) > 3000L) base += 24000L;
        return base;
    }

    private static int fish(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(ItemTags.FISHES)) total += stack.getCount();
        }
        return total;
    }
}

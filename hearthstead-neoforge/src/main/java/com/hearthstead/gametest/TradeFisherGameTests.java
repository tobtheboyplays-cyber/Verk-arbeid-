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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * TRADES-1's own production-loop proof for FISHER: a real pond next to the
 * fishery (clearing the goal's own {@code MIN_ADJACENT_WATER} floor — see
 * {@code FisherWorkGoal}'s class doc for why a token puzzle-box of water is
 * not enough), a settler hired into it, and real fish in the fishery's own
 * chest that did not exist before.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeFisherGameTests {

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

    private static int countOf(Container chest, net.minecraft.tags.TagKey<Item> tag) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(tag)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * A real pond, a fishery, a hired fisher, and real fish in the fishery's
     * own chest that did not exist before — nothing else built anywhere in
     * the world (D-007).
     */
    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 800)
    public void aHiredFisherActuallyFishes(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building fishery = building(helper, s, BuildingType.FISHERY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;

        // A real pond: 5x5 = 25 water blocks, comfortably clearing
        // FisherWorkGoal.MIN_ADJACENT_WATER (20) and entirely inside its
        // WATER_SEARCH_RADIUS (6) of the fishery's own anchor at (4,1,4).
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            }
        }

        SettlerEntity finn = settler(helper, s, "Finn", 4, 4);
        finn.attributes().pinForTest(Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, fishery, finn).ok(),
            "a fishery must be able to take a fisher");
        helper.assertTrue(finn.getProfession() == Profession.FISHER,
            "hired into a fishery, they fish");

        helper.getLevel().setDayTime(3000);

        boolean[] sawFishing = new boolean[1];

        helper.succeedWhen(() -> {
            if (finn.getActivity() == SettlerActivity.WORK_FISH) {
                sawFishing[0] = true;
            }
            int fish = countOf(chest, ItemTags.FISHES);
            helper.assertTrue(fish > 0,
                "a hired fisher standing at a real pond must land real fish into "
                    + "the fishery's own chest (activity=" + finn.getActivity() + ")");
            helper.assertTrue(sawFishing[0],
                "the fisher must actually be seen performing WORK_FISH at some point, "
                    + "not just have the output appear while idle");
        });
    }

    /**
     * A fishery whose "water" is a token puzzle-box (well under
     * {@code MIN_ADJACENT_WATER}) must never produce — a free food printer
     * is exactly the bug this goal's own floor exists to forbid. Runs the
     * whole timeout and asserts the chest is still empty at the end, rather
     * than racing a positive assertion.
     */
    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 200)
    public void aFisheryBuiltInAPuddleProducesNothing(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building fishery = building(helper, s, BuildingType.FISHERY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        Container chest = (Container) be;

        // Exactly the plaque's own room requirement (2 water blocks) --
        // enough to build, never enough to fish.
        helper.setBlock(new BlockPos(6, 1, 4), Blocks.WATER);
        helper.setBlock(new BlockPos(6, 1, 5), Blocks.WATER);

        SettlerEntity finn = settler(helper, s, "Finn", 4, 4);
        finn.attributes().pinForTest(Attribute.STAMINA, 50);
        Employment.hire(helper.getLevel(), s, fishery, finn);
        helper.getLevel().setDayTime(3000);

        helper.runAfterDelay(190, () -> {
            int fish = countOf(chest, ItemTags.FISHES);
            helper.assertTrue(fish == 0,
                "a fishery built in a puddle must never produce fish -- found "
                    + fish + " (activity=" + finn.getActivity() + ")");
            helper.assertTrue(finn.getActivity() != SettlerActivity.WORK_FISH,
                "a puddle-fishery's fisher must never even start fishing");
            helper.succeed();
        });
    }
}

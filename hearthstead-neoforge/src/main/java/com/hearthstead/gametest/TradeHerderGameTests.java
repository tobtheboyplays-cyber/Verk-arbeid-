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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * TRADES-1's own production-loop proof for HERDER, the same shape as
 * {@code TradeButcherGameTests}: a real sheep the player already put in the
 * paddock, a settler hired into the pasture, and real wool that did not
 * exist before — with no player shearing anything by hand.
 *
 * <p>This is exactly the finding SURVIVAL_AUDIT.md F1 named: before this
 * trade existed, a plaqued, validated PASTURE could never produce anything,
 * because there was nobody {@code Employment.hire} could ever place there.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeHerderGameTests {

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
        // Delegates to the one place that places the plaque a building needs
        // to survive BuildingManager's sweep (KF-021 / FLAKE-2).
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
     * The whole loop: a real sheep the player put in the paddock, a herder
     * hired into the pasture, and real wool in the pasture's own chest that
     * did not exist before — with nothing else built anywhere in the world
     * (D-007) and the sheep never conjured by the goal itself (the class
     * doc's "the player stocks the paddock" promise).
     */
    @GameTest(batch = "trade_herder", template = "empty16", timeoutTicks = 600)
    public void aHiredHerderActuallyShears(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building pasture = building(helper, s, BuildingType.PASTURE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;

        // The player's own real animal, already standing in the paddock --
        // never spawned by HerderWorkGoal itself.
        Sheep sheep = helper.spawn(EntityType.SHEEP, new BlockPos(5, 1, 5));
        helper.assertTrue(!sheep.isSheared() && !sheep.isBaby(),
            "the fixture sheep must start unsheared and grown, or this test proves nothing");

        SettlerEntity gjeta = settler(helper, s, "Gjeta", 4, 4);
        gjeta.attributes().pinForTest(Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, pasture, gjeta).ok(),
            "a pasture must be able to take a herder");
        helper.assertTrue(gjeta.getProfession() == Profession.HERDER,
            "hired into a pasture, they herd");

        helper.getLevel().setDayTime(3000);

        boolean[] sawShearing = new boolean[1];

        helper.succeedWhen(() -> {
            if (gjeta.getActivity() == SettlerActivity.WORK_SHEAR) {
                sawShearing[0] = true;
            }
            int wool = countOf(chest, ItemTags.WOOL);
            helper.assertTrue(wool > 0,
                "a hired herder standing over an unsheared sheep must produce real "
                    + "wool in the pasture's own chest (activity=" + gjeta.getActivity()
                    + ", sheared=" + sheep.isSheared() + ")");
            helper.assertTrue(sawShearing[0],
                "the herder must actually be seen performing WORK_SHEAR at some point, "
                    + "not just have the output appear while idle");
        });
    }
}

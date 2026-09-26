package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.FishersChairBlock;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.work.FishingGrounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * captain1 soak (2026-09-26): after the midday meal the Fisher stood 40-60
 * blocks from his shore chair. Every exact route search for the chair's
 * aisle failed at that range ("fisher_chair_unreachable", NO_PATH) and he
 * idled 58% of the workday. A Fisher that far away must walk to the shore
 * and fish.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FisherFarShoreGameTests {

    @GameTest(batch = "fisher_far_shore", template = "empty64", timeoutTicks = 2400)
    public void aFisherFiftyBlocksFromTheShoreWalksThereAndFishes(GameTestHelper helper) {
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Fjernvik",
            helper.absolutePos(new BlockPos(32, 1, 32)));
        s.radius = 40;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building fishery = GameTestFixtures.register(helper, s, BuildingType.FISHERY, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 8), ModBlocks.FISH_RACK.get());
        helper.setBlock(new BlockPos(8, 1, 11), ModBlocks.FISHERS_CHAIR.get().defaultBlockState()
            .setValue(FishersChairBlock.FACING, Direction.EAST));
        for (int x = 9; x <= 13; x++) {
            for (int z = 9; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.WATER);
            }
        }
        helper.setBlock(new BlockPos(10, 1, 8), Blocks.BARREL);
        Container barrel = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(10, 1, 8)));
        barrel.setItem(0, new ItemStack(com.hearthstead.registry.ModItems.FISHERS_ROD.get()));
        helper.assertTrue(FishingGrounds.scan(helper.getLevel(), fishery.anchor).ready(),
            "fixture must provide a valid shore chair");

        // Far corner: ~60 blocks from the chair, well past the 32-block
        // follow range an exact aisle route is searched within.
        SettlerEntity finn = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(56, 1, 56));
        finn.setSettlerName("Finn");
        finn.bindTo(s.id, s.center);
        s.putRecord(finn.getUUID(), "Finn", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, fishery, finn).ok(), "hire fisher");
        finn.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new ItemStack(com.hearthstead.registry.ModItems.FISHERS_ROD.get()));
        finn.setHunger(100.0F);
        finn.setEnergy(100.0F);
        helper.getLevel().setDayTime(2000);

        helper.succeedWhen(() -> helper.assertTrue(finn.getActivity() == SettlerActivity.WORK_FISH,
            "a far fisher must walk to the shore and fish; act=" + finn.getActivity()
                + " at " + helper.relativePos(finn.blockPosition())
                + " route=" + finn.routeFailureNote() + " stop=" + finn.logisticsStopReason()));
    }
}

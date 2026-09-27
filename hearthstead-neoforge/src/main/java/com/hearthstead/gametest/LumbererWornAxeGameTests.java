package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * captain1 soak (2026-09-26, Bramwell): an iron axe with 10 uses left is
 * "serviceable" by the Lumberer's 8-use floor, yet refused a 10-log tree
 * ("tree_requires_more_axe_durability"), so no replacement was ever
 * requested; and after a few refused contacts the saved chop clock stuck at
 * 20000, where the contact tick can never fire. He stood "chopping" one
 * trunk for hours. Now the too-worn axe opens a replacement request, and a
 * fresh axe in the camp chest lets him fell the same tree.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class LumbererWornAxeGameTests {

    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "lumber_worn_axe")
    public void aTooWornAxeRequestsAReplacementAndTheTreeIsFelled(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 7; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Slitevik", helper.absolutePos(hearthRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        ((HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))).bindSettlement(s.id);
        Building camp = GameTestFixtures.register(helper, s, BuildingType.LUMBER_CAMP, 11, 11);
        BlockPos chestRel = new BlockPos(12, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));

        // A 12-log oak: taller than the worn axe's 10 remaining uses.
        BlockPos dirtRel = new BlockPos(8, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 12; i++) helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                helper.setBlock(baseRel.above(12).offset(dx, 0, dz), Blocks.OAK_LEAVES);

        SettlerEntity bram = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 8));
        bram.setSettlerName("Bramwell");
        bram.bindTo(s.id, s.center);
        s.putRecord(bram.getUUID(), "Bramwell", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, camp, bram).ok(), "hire lumberer");
        ItemStack worn = new ItemStack(Items.IRON_AXE);
        worn.setDamageValue(worn.getMaxDamage() - 10); // Bramwell's axe: 10 uses left
        bram.setItemSlot(EquipmentSlot.MAINHAND, worn);
        bram.setHunger(100.0F);
        bram.setEnergy(100.0F);

        final boolean[] requested = {false};
        final boolean[] supplied = {false};
        helper.onEachTick(() -> {
            helper.getLevel().setDayTime(2000);
            if (!bram.requestedEquipmentIcon().isEmpty()) {
                requested[0] = true;
            }
            if (requested[0] && !supplied[0]) {
                chest.setItem(0, new ItemStack(Items.IRON_AXE)); // a fresh axe arrives
                supplied[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(requested[0],
                "a too-worn axe must open a visible replacement request, route=" + bram.routeFailureNote());
            helper.assertTrue(!helper.getLevel().getBlockState(helper.absolutePos(baseRel)).is(Blocks.OAK_LOG),
                "with the fresh axe the same tree must be felled; act=" + bram.getActivity()
                    + " route=" + bram.routeFailureNote());
        });
    }
}

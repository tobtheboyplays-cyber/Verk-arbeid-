package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.FishRackBlockEntity;
import com.hearthstead.block.FishRackBlock;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import com.hearthstead.settlement.ReadyFood;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FishRackGameTests {
    @GameTest(template="empty16",timeoutTicks=40)
    public void fourPhysicalHooksConserveFishAndQualityThroughSaveAndBreak(GameTestHelper helper) {
        var level=helper.getLevel();
        Settlement village=new Settlement(UUID.randomUUID(),"Hook Test",helper.absolutePos(new BlockPos(3,1,3)));
        var fishery=GameTestFixtures.register(helper,village,BuildingType.FISHERY,3,3);
        BlockPos local=new BlockPos(4,1,3),pos=helper.absolutePos(local);
        helper.setBlock(local,ModBlocks.FISH_RACK.get());
        var rack=(FishRackBlockEntity)level.getBlockEntity(pos);
        var store=WarehouseStorage.of(level,fishery);
        helper.assertTrue(store.insertAt(level,fishery,pos,new ItemStack(Items.FISHING_ROD)).is(Items.FISHING_ROD),
            "input equipment must never occupy a hanging hook");
        ItemStack rare=new ItemStack(ModItems.BROWN_TROUT.get(),7);
        rare.set(ModComponents.GOODS_QUALITY.get(),1);
        ItemStack remainder=store.insertAt(level,fishery,pos,rare);
        helper.assertTrue(remainder.getCount()==3 && rack.getContainerSize()==4,"only four physical fish fit four hooks");
        for(int slot=0;slot<4;slot++) helper.assertTrue(rack.getItem(slot).getCount()==1,"one fish per hook");
        helper.assertTrue(level.getBlockState(pos).getValue(FishRackBlock.HANGING)==4,"world display follows actual stock");
        helper.assertTrue(!ReadyFood.isReadyMeal(rack.getItem(0))
                && com.hearthstead.settlement.work.FishMeals.portions(new ItemStack(ModItems.RIVER_PERCH.get())) == 2,
            "common fish supplies food while rare catch stays available to trade");
        var saved=rack.saveWithFullMetadata(level.registryAccess());
        var restored=(FishRackBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,rack.getBlockState(),saved,level.registryAccess());
        helper.assertTrue(restored!=null && ItemStack.isSameItemSameComponents(restored.getItem(0),rare)
            && restored.getItem(3).getCount()==1,"species, quality and exact hook contents survive NBT reload");
        level.destroyBlock(pos,false);
        level.destroyBlock(pos,false);
        int dropped=level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2)).stream()
            .filter(e->ItemStack.isSameItemSameComponents(e.getItem(),rare)).mapToInt(e->e.getItem().getCount()).sum();
        helper.assertTrue(dropped==4,"breaking rack drops the four owned fish exactly once");
        var meals = new net.neoforged.neoforge.items.ItemStackHandler(3);
        meals.setStackInSlot(0,new ItemStack(ModItems.RIVER_PERCH.get()));
        meals.setStackInSlot(1,new ItemStack(ModItems.BROWN_TROUT.get()));
        meals.setStackInSlot(2,rare.copyWithCount(1));
        helper.assertTrue(ReadyFood.count(meals)==0,"whole fish must not be consumed as single meals");
        helper.assertTrue(com.hearthstead.settlement.work.FishMeals.prepareOne(meals)
            && com.hearthstead.settlement.work.FishMeals.prepareOne(meals),"two common catches must prepare");
        helper.assertTrue(ReadyFood.count(meals)==5
            && !com.hearthstead.settlement.work.FishMeals.prepareOne(meals),"perch2 plus trout3, rare catch untouched");
        var reloaded = new net.neoforged.neoforge.items.ItemStackHandler(3);
        reloaded.deserializeNBT(level.registryAccess(),meals.serializeNBT(level.registryAccess()));
        for(int portion=0;portion<5;portion++) {
            helper.assertTrue(!ReadyFood.extractBest(reloaded).isEmpty(),"five separate meals survive reload");
        }
        helper.assertTrue(ReadyFood.extractBest(reloaded).isEmpty() && reloaded.getStackInSlot(2).getCount()==1,
            "no sixth meal and trade fish preserved");
        var full = new net.neoforged.neoforge.items.ItemStackHandler(1);
        full.setStackInSlot(0,new ItemStack(ModItems.RIVER_PERCH.get(),2));
        helper.assertTrue(!com.hearthstead.settlement.work.FishMeals.prepareOne(full)
            && full.getStackInSlot(0).getCount()==2,"full storage must retain both whole fish");
        helper.succeed();
    }

    @GameTest(template="empty16", timeoutTicks=440)
    public void hearthTurnsOrdinaryCatchesIntoSeveralMeals(GameTestHelper helper) {
        BlockPos local = new BlockPos(8,1,8), absolute = helper.absolutePos(local);
        helper.setBlock(local, ModBlocks.HEARTH.get());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Meal Test", absolute);
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        var hearth = (com.hearthstead.block.HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        helper.assertTrue(hearth != null, "the real hearth block must create its cooking inventory");
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(ModItems.RIVER_PERCH.get()));
        hearth.getInventory().setStackInSlot(1, new ItemStack(ModItems.BROWN_TROUT.get()));
        ItemStack fine = new ItemStack(ModItems.BROWN_TROUT.get());
        fine.set(ModComponents.GOODS_QUALITY.get(), 1);
        hearth.getInventory().setStackInSlot(2, fine);

        helper.runAfterDelay(420, () -> {
            helper.assertTrue(count(hearth.getInventory(), ModItems.RIVER_PERCH.get()) == 0
                    && count(hearth.getInventory(), ModItems.BROWN_TROUT.get()) == 1
                    && count(hearth.getInventory(), ModItems.FISH_PORTION.get()) == 5
                    && ReadyFood.count(hearth.getInventory()) == 5,
                "the real hearth must turn perch2 plus trout3 into five meals over two ten-second preparations");
            helper.assertTrue(ItemStack.isSameItemSameComponents(hearth.getInventory().getStackInSlot(2), fine),
                "a Fine catch must remain whole for trade, never become a meal");
            helper.succeed();
        });
    }

    private static int count(net.neoforged.neoforge.items.ItemStackHandler inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).is(item)) total += inventory.getStackInSlot(slot).getCount();
        }
        return total;
    }
}

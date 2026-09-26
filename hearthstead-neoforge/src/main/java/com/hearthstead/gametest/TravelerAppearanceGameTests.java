package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TravelerAppearanceGameTests {
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 40)
    public void travelerAppearanceSurvivesSaveAndClearsOnAdmissionWithoutTouchingItems(GameTestHelper helper) {
        SettlerEntity guest = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 3));
        guest.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        guest.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        UUID settlement = UUID.randomUUID();
        BlockPos hearth = helper.absolutePos(new BlockPos(6, 1, 6));
        helper.assertTrue(!guest.hasTravelerAppearance(), "ordinary settlers have no guest kit");
        guest.markTraveler(settlement, hearth);
        helper.assertTrue(guest.hasTravelerAppearance(), "marking a real guest publishes its visual flag");
        CompoundTag saved = new CompoundTag();
        guest.addAdditionalSaveData(saved);
        SettlerEntity loaded = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(loaded != null, "reload probe must exist");
        loaded.readAdditionalSaveData(saved);
        helper.assertTrue(loaded.hasTravelerAppearance(), "existing Traveler save flag restores synced appearance");
        loaded.bindTo(settlement, hearth);
        helper.assertTrue(!loaded.hasTravelerAppearance(), "admission clears the guest projection immediately");
        helper.assertTrue(loaded.getMainHandItem().is(Items.STICK)
                && loaded.getMainHandItem().getCount() == 1
                && loaded.bag.getItem(0).is(Items.COBBLESTONE)
                && loaded.bag.getItem(0).getCount() == 3,
            "visual guest lifecycle must not substitute, create or consume equipment/cargo");
        loaded.markTraveler(settlement, hearth);
        loaded.unbind();
        helper.assertTrue(!loaded.hasTravelerAppearance(), "unbind also clears the guest projection");
        guest.discard();
        helper.succeed();
    }
}

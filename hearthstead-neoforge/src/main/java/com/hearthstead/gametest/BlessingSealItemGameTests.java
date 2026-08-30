package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** End-to-end conservation tests for a seal held in a player's hand. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingSealItemGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "blessing_seal_item_settler_conservation")
    public void settlerUseConsumesOnlyARealAppliedRank(GameTestHelper helper) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getAbilities().instabuild = false;

        BlessingSealItem warden = (BlessingSealItem) ModItems.WARDEN_OATH_SEAL.get();
        player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(warden, 4));

        player.setShiftKeyDown(false);
        InteractionResult pass = warden.interactLivingEntity(
            player.getMainHandItem(), player, settler, InteractionHand.MAIN_HAND);
        helper.assertTrue(pass == InteractionResult.PASS
                && player.getMainHandItem().getCount() == 4
                && settler.blessingRank(BlessingId.WARDEN_OATH) == 0,
            "ordinary right-click must neither bind nor consume a seal");

        player.setShiftKeyDown(true);
        interactOnSettler(player, settler, InteractionHand.MAIN_HAND);
        interactOnSettler(player, settler, InteractionHand.MAIN_HAND);
        interactOnSettler(player, settler, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getMainHandItem().getCount() == 1
                && settler.blessingRank(BlessingId.WARDEN_OATH) == 3,
            "three successful bindings must consume exactly three seals and reach III");

        interactOnSettler(player, settler, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getMainHandItem().getCount() == 1
                && settler.blessingRank(BlessingId.WARDEN_OATH) == 3,
            "MAXED must retain the fourth physical seal without mutating the rank");

        BlessingSealItem hearthward = (BlessingSealItem) ModItems.HEARTHWARD_SEAL.get();
        player.getAbilities().instabuild = true;
        player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(hearthward));
        interactOnSettler(player, settler, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getMainHandItem().getCount() == 1
                && settler.blessingRank(BlessingId.HEARTHWARD) == 1,
            "creative mode must bind the rank while retaining its seal item");

        player.getAbilities().instabuild = false;
        BlessingSealItem thorned = (BlessingSealItem) ModItems.THORNED_ROADS_SEAL.get();
        player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(thorned, 2));
        var cow = helper.spawn(EntityType.COW, new BlockPos(3, 1, 2));
        helper.assertTrue(player.interactOn(cow, InteractionHand.MAIN_HAND)
                    .consumesAction()
                && player.getMainHandItem().getCount() == 2,
            "a sneaking non-settler target must be handled with an explanation "
                + "while retaining both seals");

        ItemStack consumingMainItem = new ItemStack(Items.NAME_TAG, 2);
        consumingMainItem.set(DataComponents.CUSTOM_NAME,
            Component.literal("Must not rename"));
        player.setItemInHand(InteractionHand.MAIN_HAND, consumingMainItem);
        player.setItemInHand(InteractionHand.OFF_HAND,
            new ItemStack(thorned, 2));
        InteractionResult mainPrePass = player.interactOn(settler,
            InteractionHand.MAIN_HAND);
        helper.assertTrue(mainPrePass.consumesAction()
                && player.getMainHandItem().getCount() == 2
                && player.getOffhandItem().getCount() == 1
                && settler.blessingRank(BlessingId.THORNED_ROADS) == 1,
            "a consuming main-hand item must not block the delivered offhand seal; "
                + "only that offhand stack may shrink (result=" + mainPrePass
                + ", main=" + player.getMainHandItem().getCount()
                + ", off=" + player.getOffhandItem().getCount()
                + ", rank=" + settler.blessingRank(BlessingId.THORNED_ROADS)
                + ")");

        CompoundTag corruptedSave = new CompoundTag();
        settler.addAdditionalSaveData(corruptedSave);
        corruptedSave.putString("TargetBlessings", "malformed-present-ledger");
        SettlerEntity quarantined = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 3));
        quarantined.readAdditionalSaveData(corruptedSave);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(thorned, 2));
        interactOnSettler(player, quarantined, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getMainHandItem().getCount() == 2
                && quarantined.blessingRank(BlessingId.THORNED_ROADS) == 0,
            "INVALID/quarantined targets must retain the physical seal and stay inert");
        helper.succeed();
    }

    private static InteractionResult interactOnSettler(ServerPlayer player,
                                                       SettlerEntity settler,
                                                       InteractionHand hand) {
        return player.interactOn(settler, hand);
    }
}

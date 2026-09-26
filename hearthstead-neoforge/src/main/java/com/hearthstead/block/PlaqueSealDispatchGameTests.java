package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pins the Block -> Item dispatch contract for physical plaque binding. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PlaqueSealDispatchGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "plaque_seal_dispatch_both_hands")
    public void sneakingSealSkipsDefaultPlaqueInteractionInBothHands(
            GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setShiftKeyDown(true);
        PlaqueBlock plaque = (PlaqueBlock) ModBlocks.PLAQUE.get();
        BlockPos absolute = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute),
            Direction.NORTH, absolute, false);

        ItemStack waitingOffhand = new ItemStack(ModItems.HEARTHWARD_SEAL.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, waitingOffhand);
        ItemInteractionResult mainPrePass = plaque.useItemOn(ItemStack.EMPTY,
            plaque.defaultBlockState(), helper.getLevel(), absolute, player,
            InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(mainPrePass
                == ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION,
            "the empty-main-hand item phase must yield to a waiting offhand seal");
        helper.assertTrue(plaque.useWithoutItem(plaque.defaultBlockState(),
                helper.getLevel(), absolute, player, hit)
                == net.minecraft.world.InteractionResult.PASS,
            "the empty-hand plaque phase must not open or extract before offhand");

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack seal = new ItemStack(ModItems.WARDEN_OATH_SEAL.get());
            player.setItemInHand(hand, seal);
            ItemInteractionResult result = plaque.useItemOn(seal,
                plaque.defaultBlockState(), helper.getLevel(), absolute, player,
                hand, hit);
            helper.assertTrue(result
                    == ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION,
                "a sneaking seal in " + hand
                    + " must continue to Item#useOn instead of opening plaque UI");
        }
        helper.succeed();
    }
}

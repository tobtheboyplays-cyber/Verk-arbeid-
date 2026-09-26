package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.AleTapBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.AleTapService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Actual barrels/player slots and physical block rays; no isolated balance counters. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class AleTapGameTests {
    private record Fixture(ServerPlayer player, Building tavern, BlockPos tap, BarrelBlockEntity barrel) {}
    private static Fixture fixture(GameTestHelper h) {
        for (int x=0; x<16; x++) for (int z=0; z<16; z++) {
            h.setBlock(new BlockPos(x,0,z), Blocks.STONE_BRICKS);
            for (int y=1; y<5; y++) h.setBlock(new BlockPos(x,y,z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Tap transaction", h.absolutePos(new BlockPos(4,1,4)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id,s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.register(h,s,BuildingType.TAVERN,4,4);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity)h.getLevel().getBlockEntity(tavern.plaquePos);
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", BuildingType.TAVERN.id()); tag.putUUID("Building",tavern.id); tag.putUUID("Settlement",s.id);
        plaque.loadCustomOnly(tag,h.getLevel().registryAccess());
        BlockPos tap = h.absolutePos(new BlockPos(6,2,5));
        BlockPos back = tap.south();
        h.getLevel().setBlockAndUpdate(back,Blocks.BARREL.defaultBlockState());
        h.getLevel().setBlockAndUpdate(tap,ModBlocks.ALE_TAP.get().defaultBlockState().setValue(AleTapBlock.FACING,Direction.NORTH));
        BarrelBlockEntity barrel = (BarrelBlockEntity)h.getLevel().getBlockEntity(back);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.getInventory().clearContent(); player.getInventory().selected=0;
        var stand = h.absolutePos(new BlockPos(6,1,3));
        player.moveTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5,0,0);
        player.getInventory().setItem(0,new ItemStack(Items.GLASS_BOTTLE));
        player.getInventory().setItem(1,new ItemStack(ModItems.GOLD_COIN.get(),2));
        h.assertTrue(AleTapService.ownsTap(h.getLevel(),tavern,tap),"fixture owns actual tap and exact barrel through live plaque");
        h.assertTrue(tap.equals(AleTapService.resolveTapForBarrel(h.getLevel(),tavern,back)),"bounded own-barrel lookup finds actual tap");
        return new Fixture(player,tavern,tap,barrel);
    }
    private static ItemStack[] snapshot(Container c) {
        ItemStack[] result=new ItemStack[c.getContainerSize()];
        for(int i=0;i<result.length;i++) result[i]=c.getItem(i).copy();
        return result;
    }
    private static void unchanged(GameTestHelper h,Container c,ItemStack[] before) {
        for(int i=0;i<before.length;i++) h.assertTrue(ItemStack.matches(before[i],c.getItem(i)),"refusal preserves exact slot "+i);
    }
    private static void refusal(GameTestHelper h,Fixture f,AleTapService.Result expected) {
        ItemStack[] wallet=snapshot(f.player().getInventory()), barrel=snapshot(f.barrel());
        h.assertTrue(AleTapService.buy(f.player(),InteractionHand.MAIN_HAND,f.tap(),Direction.NORTH)==expected,"expected refusal "+expected);
        unchanged(h,f.player().getInventory(),wallet); unchanged(h,f.barrel(),barrel);
    }
    private static ItemStack named(ItemStack stack) {
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("Keep this exact item")); return stack;
    }

    @GameTest(template="empty16",timeoutTicks=20,batch="ale_tap_atomic_success")
    public void oneAleFreedBarrelSlotHoldsPaymentAndSameTickRetryDoesNothing(GameTestHelper h) {
        Fixture f=fixture(h);
        f.barrel().setItem(0,new ItemStack(ModItems.ALE.get()));
        for(int i=1;i<f.barrel().getContainerSize();i++) f.barrel().setItem(i,new ItemStack(Items.DIRT,64));
        h.assertTrue(AleTapService.buy(f.player(),InteractionHand.MAIN_HAND,f.tap(),Direction.NORTH)==AleTapService.Result.SUCCESS,"actual tap pours one paid Ale");
        h.assertTrue(f.player().getMainHandItem().is(ModItems.ALE.get()) && f.player().getMainHandItem().getCount()==1,"one bottle becomes exactly one Ale");
        h.assertTrue(f.player().getInventory().getItem(1).getCount()==1,"exactly one physical coin is charged");
        h.assertTrue(f.barrel().getItem(0).is(ModItems.GOLD_COIN.get()) && f.barrel().getItem(0).getCount()==1,"Ale-freed slot physically receives the same one-coin payment");
        ItemStack[] wallet=snapshot(f.player().getInventory()), barrel=snapshot(f.barrel());
        h.assertTrue(AleTapService.buy(f.player(),InteractionHand.OFF_HAND,f.tap(),Direction.NORTH)==AleTapService.Result.RETRY,"dual-hand same-tick retry cannot transact again");
        unchanged(h,f.player().getInventory(),wallet); unchanged(h,f.barrel(),barrel);
        h.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=20,batch="ale_tap_preflight_refusals")
    public void missingFundsEmptyReserveAndBothCapacityFailuresPreserveEveryStack(GameTestHelper h) {
        Fixture f=fixture(h);
        f.player().getInventory().setItem(1,ItemStack.EMPTY);
        refusal(h,f,AleTapService.Result.NEEDS_COIN);
        f.player().getInventory().setItem(1,new ItemStack(ModItems.GOLD_COIN.get(),2));
        refusal(h,f,AleTapService.Result.EMPTY);
        f.barrel().setItem(0,new ItemStack(ModItems.ALE.get(),2));
        for(int i=1;i<f.barrel().getContainerSize();i++) f.barrel().setItem(i,new ItemStack(Items.DIRT,64));
        refusal(h,f,AleTapService.Result.BARREL_FULL);
        f.barrel().setItem(0,new ItemStack(ModItems.ALE.get()));
        f.player().getInventory().setItem(0,new ItemStack(Items.GLASS_BOTTLE,2));
        for(int i=2;i<36;i++) f.player().getInventory().setItem(i,new ItemStack(Items.DIRT,64));
        refusal(h,f,AleTapService.Result.NO_ROOM);
        h.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=20,batch="ale_tap_components")
    public void componentBearingInputsAreNeverStrippedOrUsedAsCoinMergeTargets(GameTestHelper h) {
        Fixture f=fixture(h);
        ItemStack specialGlass=named(new ItemStack(Items.GLASS_BOTTLE));
        f.player().getInventory().setItem(0,specialGlass.copy());
        refusal(h,f,AleTapService.Result.NEEDS_BOTTLE);
        f.player().getInventory().setItem(0,new ItemStack(Items.GLASS_BOTTLE));
        ItemStack specialCoin=named(new ItemStack(ModItems.GOLD_COIN.get(),3));
        f.player().getInventory().setItem(1,specialCoin.copy());
        refusal(h,f,AleTapService.Result.NEEDS_COIN);
        f.player().getInventory().setItem(2,new ItemStack(ModItems.GOLD_COIN.get(),2));
        ItemStack specialAle=named(new ItemStack(ModItems.ALE.get()));
        f.barrel().setItem(0,specialAle.copy());
        refusal(h,f,AleTapService.Result.EMPTY);
        f.barrel().setItem(1,specialCoin.copy()); f.barrel().setItem(2,new ItemStack(ModItems.ALE.get()));
        h.assertTrue(AleTapService.buy(f.player(),InteractionHand.MAIN_HAND,f.tap(),Direction.NORTH)==AleTapService.Result.SUCCESS,"plain inputs still transact beside protected component stacks");
        h.assertTrue(ItemStack.matches(f.barrel().getItem(0),specialAle) && ItemStack.matches(f.barrel().getItem(1),specialCoin)
            && ItemStack.matches(f.player().getInventory().getItem(1),specialCoin),"all customized source and payment stacks remain exact");
        h.assertTrue(f.barrel().getItem(2).is(ModItems.GOLD_COIN.get()) && f.barrel().getItem(2).getCount()==1
            && f.barrel().getItem(2).getComponentsPatch().isEmpty(),"new plain coin cannot inherit named components");
        h.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=20,batch="ale_tap_contact_and_detach")
    public void wallDistanceDetachAndInvalidTavernCannotAccessStock(GameTestHelper h) {
        Fixture f=fixture(h); f.barrel().setItem(0,new ItemStack(ModItems.ALE.get()));
        h.setBlock(new BlockPos(6,2,4),Blocks.STONE_BRICKS); refusal(h,f,AleTapService.Result.BLOCKED);
        h.setBlock(new BlockPos(6,2,4),Blocks.AIR);
        f.player().setPos(f.player().getX(),f.player().getY(),f.player().getZ()-8);
        refusal(h,f,AleTapService.Result.OUT_OF_REACH);
        f.player().setPos(f.player().getX(),f.player().getY(),f.player().getZ()+8);
        f.tavern().valid=false; refusal(h,f,AleTapService.Result.NOT_TAVERN); f.tavern().valid=true;
        BlockPos exact=f.barrel().getBlockPos();
        h.getLevel().setBlockAndUpdate(exact,Blocks.STONE_BRICKS.defaultBlockState());
        // A nearby unrelated barrel cannot replace the exact backing source.
        h.setBlock(new BlockPos(7,2,6),Blocks.BARREL);
        ((BarrelBlockEntity)h.getBlockEntity(new BlockPos(7,2,6))).setItem(0,new ItemStack(ModItems.ALE.get()));
        ItemStack[] wallet=snapshot(f.player().getInventory());
        h.assertTrue(AleTapService.barrel(h.getLevel(),f.tap())==null,"detached backing never searches remote containers");
        h.assertTrue(AleTapService.buy(f.player(),InteractionHand.MAIN_HAND,f.tap(),Direction.NORTH)==AleTapService.Result.NO_BARREL,"tap on sturdy nonbarrel wall cannot pour");
        unchanged(h,f.player().getInventory(),wallet);
        h.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=20,batch="ale_tap_offhand_creative")
    public void offhandCreativePourStillConsumesBottleAndPrice(GameTestHelper h) {
        Fixture f=fixture(h); f.player().setGameMode(GameType.CREATIVE);
        f.player().getInventory().setItem(0,ItemStack.EMPTY);
        f.player().setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.GLASS_BOTTLE));
        f.barrel().setItem(0,new ItemStack(ModItems.ALE.get()));
        h.assertTrue(AleTapService.buy(f.player(),InteractionHand.OFF_HAND,f.tap(),Direction.NORTH)==AleTapService.Result.SUCCESS,"offhand pour uses the same paid transaction in Creative");
        h.assertTrue(f.player().getOffhandItem().is(ModItems.ALE.get()) && f.player().getOffhandItem().getCount()==1
            && f.player().getInventory().getItem(1).getCount()==1,"no Creative bottle or Coin exemption");
        h.succeed();
    }
}
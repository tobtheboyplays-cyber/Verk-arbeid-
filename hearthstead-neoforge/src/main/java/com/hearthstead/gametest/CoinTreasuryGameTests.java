package com.hearthstead.gametest;
import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CoinTreasuryGameTests {
    @GameTest(template="empty16",timeoutTicks=20)
    public static void physicalWalletPaysBeforeCommunalCoinsAndRefusalPreservesAll(GameTestHelper h) {
        BlockPos pos=new BlockPos(3,1,3); h.setBlock(pos,ModBlocks.HEARTH.get());
        Settlement s=new Settlement(UUID.randomUUID(),"Coin test",h.absolutePos(pos));
        HearthBlockEntity hearth=(HearthBlockEntity)h.getLevel().getBlockEntity(s.center);
        hearth.bindSettlement(s.id);
        var player=h.makeMockServerPlayerInLevel();
        player.getInventory().setItem(0,new ItemStack(ModItems.GOLD_COIN.get(),2));
        player.getInventory().setItem(1,new ItemStack(Items.BREAD,14));
        hearth.getInventory().setStackInSlot(0,new ItemStack(ModItems.GOLD_COIN.get(),2));
        var treasury=CoinTreasury.open(h.getLevel(),s,hearth,player);
        h.assertTrue(CoinTreasury.availableCoins(treasury) == 4,
            "Displayed affordability must count the same wallet and communal Coins as payment, excluding bread");
        Costs.pay(treasury,Costs.coins(Costs.PriceKey.RECRUIT,3));
        h.assertTrue(CoinTreasury.availableCoins(treasury) == 1,
            "A fresh wallet observation must reflect physical payment immediately");
        h.assertTrue(player.getInventory().getItem(0).isEmpty(),"Wallet must pay first");
        h.assertTrue(hearth.getInventory().getStackInSlot(0).getCount()==1,"Only one communal coin pays the remainder");
        h.assertTrue(player.getInventory().getItem(1).getCount()==14,"Payment must retain unrelated bread");
        var before=CoinTreasury.snapshot(treasury);
        boolean refused=false;
        try { Costs.pay(treasury,Costs.coins(Costs.PriceKey.RECRUIT,2)); }
        catch(IllegalStateException expected) { refused=true; }
        h.assertTrue(refused,"Insufficient physical coins must refuse payment");
        h.assertTrue(CoinTreasury.availableCoins(treasury) == 1,
            "Refused payment must not change displayed physical Coins");
        for(int i=0;i<before.size();i++) h.assertTrue(ItemStack.matches(before.get(i),treasury.getStackInSlot(i)),"Refusal must preserve every physical stack");
        h.succeed();
    }

}

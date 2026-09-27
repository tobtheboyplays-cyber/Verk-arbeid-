package com.hearthstead.gametest;
import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.*;
import com.hearthstead.settlement.work.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
@GameTestHolder(Hearthstead.MODID) @PrefixGameTestTemplate(false)
public class TraderSaleServiceGameTests {
 @GameTest(template="empty16",timeoutTicks=80)
 public void finitePhysicalSaleRejectsChangedPriceAndReplay(GameTestHelper helper) {
  var level=helper.getLevel();
  for(int x=1;x<7;x++) for(int z=1;z<7;z++) {
   helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
   for(int y=1;y<4;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.AIR);
  }
  var worker=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(3,1,3));worker.setNoAi(true);
  var merchant=helper.spawn(EntityType.WANDERING_TRADER,new BlockPos(4,1,3));merchant.setNoAi(true);
  var settlement=new Settlement(UUID.randomUUID(),"Sale",helper.absolutePos(new BlockPos(2,1,2)));
  SettlementSavedData.get(level).settlements.put(settlement.id,settlement);
  worker.bindTo(settlement.id,settlement.center);settlement.putRecord(worker.getUUID(),"Trader",Profession.NONE);
  var post=GameTestFixtures.register(helper,settlement,com.hearthstead.building.BuildingType.TRADING_POST,8,8);
  helper.assertTrue(Employment.hire(level,settlement,post,worker).ok(),"real Trader employment required");
  merchant.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement",settlement.id);
  merchant.getPersistentData().putLong("HearthsteadMerchantExpires",level.getGameTime()+2000);
  helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(merchant,settlement.id),"new visitor publication");
  worker.bag.setItem(0,new ItemStack(Items.OAK_LOG,8));
  var quote=TraderSaleService.select(level,settlement,worker,merchant,List.of(worker.bag.getItem(0)));
  helper.assertTrue(quote!=null,"physical warehouse quote must exist");
  var offer=merchant.getOffers().get(quote.offerIndex());offer.setSpecialPriceDiff(1);
  helper.assertTrue(TraderSaleService.execute(level,settlement,worker,merchant,UUID.randomUUID(),quote)
    ==TraderSaleService.Result.PRICE_CHANGED,"changed quote cannot remove goods");
  helper.assertTrue(worker.bag.getItem(0).getCount()==8,"rejected sale preserves cargo");
  offer.setSpecialPriceDiff(0);
  UUID transaction=UUID.randomUUID();
  helper.assertTrue(TraderSaleService.execute(level,settlement,worker,merchant,transaction,quote)
    ==TraderSaleService.Result.COMMITTED,"exact physical sale commits");
  helper.assertTrue(!offer.isOutOfStock() && offer.getUses()==1,"one Basic shipment consumes one of the market's finite uses");
  var saved=worker.getPersistentData().copy();worker.getPersistentData().merge(saved);
  helper.assertTrue(TraderSaleService.execute(level,settlement,worker,merchant,transaction,quote)
    ==TraderSaleService.Result.ALREADY_COMMITTED,"saved receipt blocks repeat payment");
  int coins=0,logs=0;
  for(int i=0;i<worker.bag.getContainerSize();i++) {
   var stack=worker.bag.getItem(i);
   if(stack.is(ModItems.GOLD_COIN.get()))coins+=stack.getCount();
   if(stack.is(Items.OAK_LOG))logs+=stack.getCount();
  }
  helper.assertTrue(coins==1 && logs==0,"one quote consumes8logs and pays1realCoin exactly once");
  helper.succeed();
 }

 @GameTest(template="empty16",timeoutTicks=80)
 public void traderReadsFrozenOwnedDemandAndNeverChangesBusyMenu(GameTestHelper helper) {
  var level=helper.getLevel();
  for(int x=1;x<7;x++) for(int z=1;z<7;z++) { helper.setBlock(new BlockPos(x,0,z),Blocks.STONE); for(int y=1;y<4;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.AIR); }
  var worker=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(3,1,3)); worker.setNoAi(true);
  var merchant=helper.spawn(EntityType.WANDERING_TRADER,new BlockPos(4,1,3)); merchant.setNoAi(true);
  var settlement=new Settlement(UUID.randomUUID(),"Frozen",helper.absolutePos(new BlockPos(2,1,2)));
  SettlementSavedData.get(level).settlements.put(settlement.id,settlement);
  worker.bindTo(settlement.id,settlement.center); settlement.putRecord(worker.getUUID(),"Trader",Profession.NONE);
  var post=GameTestFixtures.register(helper,settlement,com.hearthstead.building.BuildingType.TRADING_POST,8,8);
  helper.assertTrue(Employment.hire(level,settlement,post,worker).ok(),"real Trader employment required");
  merchant.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement",settlement.id);
  merchant.getPersistentData().putLong("HearthsteadMerchantExpires",level.getGameTime()+2000);
  helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(merchant,settlement.id)
    && GoldCoinTrades.ensureOwnedMarket(level,settlement,merchant,null,List.of(new ItemStack(Items.OAK_LOG,8))),"owned market freezes before Trader selection");
  worker.bag.setItem(0,new ItemStack(Items.OAK_LOG,8));
  var quote=TraderSaleService.select(level,settlement,worker,merchant,List.of(worker.bag.getItem(0)));
  helper.assertTrue(quote!=null && merchant.getOffers().get(quote.offerIndex()).getBaseCostA().is(Items.OAK_LOG),"Trader reads the visitor's existing requested physical row");
  var player=helper.makeMockServerPlayerInLevel(); merchant.setTradingPlayer(player);
  var rows=List.copyOf(merchant.getOffers()); var tags=merchant.getPersistentData().copy();
  helper.assertTrue(TraderSaleService.select(level,settlement,worker,merchant,List.of(worker.bag.getItem(0)))==null
    && merchant.getOffers().equals(rows) && merchant.getPersistentData().equals(tags),"busy player menu rejects Trader selection without price or index mutation");
  merchant.setTradingPlayer(null); merchant.discard(); worker.discard(); SettlementSavedData.get(level).settlements.remove(settlement.id); helper.succeed();
 }
}

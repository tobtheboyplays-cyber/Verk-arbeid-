package com.hearthstead.gametest;
import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.*;
import com.hearthstead.entity.ai.TraderWorkGoal;
import com.hearthstead.registry.*;
import com.hearthstead.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;
@GameTestHolder(Hearthstead.MODID) @PrefixGameTestTemplate(false)
public class TraderWorkGoalGameTests {
 @GameTest(template="empty16",timeoutTicks=5000,batch="trader_work_day")
 public void courierRestocksTraderWhoBanksOneRealCoin(GameTestHelper helper){ commerce(helper,Items.OAK_LOG,72,64); }
 @GameTest(template="empty16",timeoutTicks=5000,batch="trader_work_day")
 public void traderExportsOnlyWheatAboveFoodReserve(GameTestHelper helper){ commerce(helper,Items.WHEAT,144,128); }
 private void commerce(GameTestHelper helper,Item goods,int initial,int reserve){
  var level=helper.getLevel();level.setDayTime(2000);
  for(int x=0;x<16;x++)for(int z=0;z<16;z++){
   helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
   for(int y=1;y<5;y++)helper.setBlock(new BlockPos(x,y,z),Blocks.AIR);
  }
  var settlement=new Settlement(UUID.randomUUID(),"Commerce",helper.absolutePos(new BlockPos(2,1,2)));
  settlement.radius=20;SettlementSavedData.get(level).settlements.put(settlement.id,settlement);
  helper.setBlock(new BlockPos(2,1,2),ModBlocks.HEARTH.get());
  var hearth=(com.hearthstead.block.HearthBlockEntity)level.getBlockEntity(settlement.center);
  hearth.bindSettlement(settlement.id);
  // Commercial deliveries are correctly subordinate to the real Hearth food
  // reserve.  This must be actual ready food: an empty larder makes Courier
  // stop before it can claim the Trading Post MATERIAL_INPUT request.
  hearth.getInventory().setStackInSlot(0,new ItemStack(Items.BREAD,64));
  var post=GameTestFixtures.register(helper,settlement,BuildingType.TRADING_POST,8,8);
  var warehouse=GameTestFixtures.register(helper,settlement,BuildingType.WAREHOUSE,3,8);
  helper.setBlock(new BlockPos(9,1,9),Blocks.CHEST);helper.setBlock(new BlockPos(4,1,9),Blocks.CHEST);
  Container postChest=(Container)level.getBlockEntity(helper.absolutePos(new BlockPos(9,1,9)));
  Container storage=(Container)level.getBlockEntity(helper.absolutePos(new BlockPos(4,1,9)));
  for(int slot=0,left=initial;left>0;slot++){int batch=Math.min(64,left);storage.setItem(slot,new ItemStack(goods,batch));left-=batch;}
  var trader=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(7,1,7));
  trader.bindTo(settlement.id,settlement.center);settlement.putRecord(trader.getUUID(),"Trader",Profession.NONE);
  helper.assertTrue(Employment.hire(level,settlement,post,trader).ok(),"Trader hired normally");
  var courier=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(5,1,7));
  courier.bindTo(settlement.id,settlement.center);settlement.putRecord(courier.getUUID(),"Courier",Profession.NONE);
  helper.assertTrue(Employment.hire(level,settlement,warehouse,courier).ok(),"Courier hired normally");
  int courierFoodFloor=RecruitmentPolicy.assess(level,settlement,
   RecruitmentPolicy.stageFor(settlement)).courierReadyFoodTarget();
  helper.assertTrue(hearth.countFoodUnits()>=courierFoodFloor,
   "fixture must satisfy the real Hearth food floor before commercial restock");
  var merchant=helper.spawn(EntityType.WANDERING_TRADER,new BlockPos(11,1,5));merchant.setNoAi(true);
  merchant.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement",settlement.id);
  merchant.getPersistentData().putLong("HearthsteadMerchantExpires",level.getGameTime()+10000);
  helper.assertTrue(com.hearthstead.event.GoldCoinTrades.clearOwnedMerchantForPublication(merchant,settlement.id),
   "new visitor is published through the real finite-market initializer");
  boolean[] courierHeldExport={false};
  boolean[] courierDeliveredExport={false};
  helper.succeedWhen(()->{
   courierHeldExport[0]|=count(courier.bag,goods)>0;
   courierDeliveredExport[0]|=count(postChest,goods)>0;
   String state=diagnostic(level,settlement,trader,courier,merchant,postChest,storage,goods,hearth,
    courierFoodFloor,courierHeldExport[0],courierDeliveredExport[0]);
   helper.assertTrue(courierHeldExport[0],"Courier must physically take the Trader export before sale; "+state);
   helper.assertTrue(courierDeliveredExport[0],"Courier must physically stage the export in the Trading Post; "+state);
   helper.assertTrue(count(postChest,ModItems.GOLD_COIN.get())==1,"Trader must bank exactly one physical Coin; "+state);
   helper.assertTrue(count(storage,goods)==reserve,"commercial export preserves exactly "+reserve+" item working reserve; "+state);
   helper.assertTrue(count(postChest,goods)==0 && trader.bag.isEmpty(),"goods are consumed and Coin returns to a physical post chest; "+state);
  });
 }
 private static String diagnostic(net.minecraft.server.level.ServerLevel level,Settlement settlement,
  SettlerEntity trader,SettlerEntity courier,net.minecraft.world.entity.npc.WanderingTrader merchant,
  Container post,Container warehouse,Item goods,com.hearthstead.block.HearthBlockEntity hearth,
  int courierFoodFloor,boolean courierHeldExport,boolean courierDeliveredExport){
  var saved=com.hearthstead.settlement.request.RequestLedgerSavedData.existing(level);
  var ledger=saved==null?null:saved.existing(settlement.id);
  String requests=ledger==null?"none":ledger.active().stream()
   .map(r->r.id()+":"+r.type()+":"+r.blocker()+":remaining="+r.remainingCount()+":delivered="+r.deliveredCount()
    +":source="+r.sourceContainer()+":target="+r.targetContainer()+":courier="+r.courierId())
   .collect(java.util.stream.Collectors.joining(","));
  return "stage="+trader.getPersistentData().getCompound(TraderWorkGoal.KEY)
  +" trader="+trader.getActivity()+"@"+trader.blockPosition()+" bag="+count(trader.bag,goods)
  +" bagCoins="+count(trader.bag,ModItems.GOLD_COIN.get())
  +" courier="+courier.getActivity()+"@"+courier.blockPosition()+" courierBag="+count(courier.bag,goods)
  +" courierHeldExport="+courierHeldExport+" courierDeliveredExport="+courierDeliveredExport
  +" postGoods="+count(post,goods)+" postCoins="+count(post,ModItems.GOLD_COIN.get())
  +" warehouseGoods="+count(warehouse,goods)+" hearthMeals="+hearth.countFoodUnits()
  +" hearthFloor="+courierFoodFloor+" merchant="+merchant.getUUID()+" merchantOffers="+merchant.getOffers().size()
  +" merchantTrading="+(merchant.getTradingPlayer()!=null)+" day="+level.getDayTime()+" requests="+requests;
 }
 private static int count(Container c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
}

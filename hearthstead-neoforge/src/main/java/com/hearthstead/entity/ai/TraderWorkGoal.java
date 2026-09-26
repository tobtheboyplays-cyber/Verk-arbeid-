package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.*;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.settlement.*;
import com.hearthstead.settlement.request.*;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.work.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** One saved commercial trip; all cargo remains in real containers or worker.bag. */
public final class TraderWorkGoal extends Goal {
 public static final String KEY="HearthsteadTraderWork";
 /** J-05: after a full post AND full Warehouse, step away this long before retrying. */
 static final int BLOCKED_RETRY_TICKS=200;
 private final SettlerEntity worker;
 private long nextLook;
 public TraderWorkGoal(SettlerEntity worker){this.worker=worker;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
 @Override public boolean canUse(){return worker.getProfession()==Profession.TRADER && worker.isBound()
  && worker.isAlive() && worker.getTarget()==null && worker.level() instanceof ServerLevel
  && worker.settlement()!=null
  && Employment.employerOf(worker.settlement(),worker.getUUID())!=null
  && Employment.employerOf(worker.settlement(),worker.getUUID()).valid
  && Employment.employerOf(worker.settlement(),worker.getUUID()).type==BuildingType.TRADING_POST
  && (worker.dayPhase().work() || worker.getPersistentData().contains(KEY))
  && worker.level().getGameTime()>=worker.getPersistentData().getCompound(KEY).getLong("BlockedUntil")
  && (worker.getPersistentData().contains(KEY) || worker.level().getGameTime()>=nextLook);}
 @Override public boolean canContinueToUse(){return canUse();}
 @Override public boolean requiresUpdateEveryTick(){return true;}
 @Override public void start(){worker.getPersistentData().getCompound(KEY).remove("TradeStarted");}
 @Override public void stop(){worker.getPersistentData().getCompound(KEY).remove("TradeStarted");worker.getNavigation().stop();var p=worker.bagTransferPresentation();if(p.active())worker.clearBagTransferPresentation(p.transferId());}
 @Override public void tick(){
  ServerLevel level=(ServerLevel)worker.level(); Settlement village=worker.settlement();
  if(village==null)return;
  Building post=Employment.employerOf(village,worker.getUUID());
  if(post==null || !post.valid || post.type!=BuildingType.TRADING_POST)return;
  CompoundTag state=worker.getPersistentData().getCompound(KEY);
  if(!worker.getPersistentData().contains(KEY)) {
   nextLook=level.getGameTime()+100;
   if(!worker.bag.isEmpty()){state.putString("Stage","RETURN");save(state);}
   else if(!choose(level,village,post,state))return;
  }
  if(state.hasUUID("Cycle") && state.contains("Container")) {
   BlockPos pos=BlockPos.of(state.getLong("Container"));
   if((post.contains(pos) && WarehouseIndex.containers(level,post).contains(pos)
       || !state.getBoolean("Pickup") && overflowChest(level,village,pos)) && level.hasChunkAt(pos)
      && level.getBlockEntity(pos) instanceof Container chest
      && state.getInt("Slot")>=0 && state.getInt("Slot")<(state.getBoolean("Pickup")?chest.getContainerSize():worker.bag.getContainerSize()))
    transfer(level,state,pos,chest,state.getInt("Slot"),state.getBoolean("Pickup"));
   else {lift(state);state.putString("Stage","RETURN");save(state);}
   return;
  }
  String stage=state.getString("Stage");
  if(stage.equals("RETURN")){returnCargo(level,village,post,state);return;}
  WanderingTrader merchant=state.hasUUID("Merchant") && level.getEntity(state.getUUID("Merchant")) instanceof WanderingTrader m?m:null;
  if(merchant==null || !EarlyCoinMerchant.availableForTrade(merchant)) {state.putString("Stage","RETURN");save(state);return;}
  var quote=quote(level,state); if(quote==null){state.putString("Stage","RETURN");save(state);return;}
  if(stage.equals("WAIT_STOCK")) {
   if(count(postStock(level,post),quote.cost())>=quote.cost().getCount()) {state.putString("Stage","LOAD");save(state);}
   else request(level,village,post,state,quote.cost());
   return;
  }
  if(stage.equals("LOAD")) {
   if(count(bagStock(),quote.cost())>=quote.cost().getCount()) {lift(state);state.putString("Stage","TO_MERCHANT");save(state);return;}
   for(BlockPos pos:WarehouseIndex.containers(level,post)) {
    if(!(level.getBlockEntity(pos) instanceof Container chest))continue;
    for(int slot=0;slot<chest.getContainerSize();slot++)if(TraderSaleService.matches(chest.getItem(slot),quote.cost())) {
     transfer(level,state,pos,chest,slot,true);return;
    }
   }
   state.putString("Stage","WAIT_STOCK");save(state);return;
  }
  if(worker.distanceToSqr(merchant)>6.25 || !worker.hasLineOfSight(merchant)) {
   state.remove("TradeStarted");state.putString("Stage","TO_MERCHANT");save(state);
   worker.setActivity(SettlerActivity.CARRYING);
   if(level.getGameTime()%20==0)worker.getNavigation().moveTo(merchant,1.0);
   return;
  }
  worker.getNavigation().stop();worker.getLookControl().setLookAt(merchant,30,30);
  worker.setActivity(SettlerActivity.IDLE);
  if(merchant.getTradingPlayer()!=null){state.remove("TradeStarted");save(state);return;}
  if(!state.contains("TradeStarted")){state.putLong("TradeStarted",level.getGameTime());save(state);return;}
  if(level.getGameTime()-state.getLong("TradeStarted")<60)return;
  var result=TraderSaleService.execute(level,village,worker,merchant,state.getUUID("Transaction"),quote);
  if(result==TraderSaleService.Result.BUSY || result==TraderSaleService.Result.TOO_FAR){state.remove("TradeStarted");save(state);return;}
  state.putString("Stage","RETURN");state.remove("TradeStarted");save(state);
 }
 private boolean choose(ServerLevel level,Settlement village,Building post,CompoundTag state) {
  List<ItemStack> stock=new ArrayList<>(postStock(level,post));stock.addAll(exportStock(level,village));
  for(WanderingTrader merchant:level.getEntitiesOfClass(WanderingTrader.class,new AABB(village.center).inflate(128))) {
   var quote=TraderSaleService.select(level,village,worker,merchant,stock);if(quote==null)continue;
   state.putUUID("Merchant",merchant.getUUID());state.putUUID("Transaction",UUID.randomUUID());
   state.putInt("Offer",quote.offerIndex());state.putInt("Uses",quote.uses());
   state.put("Cost",quote.cost().save(level.registryAccess()));state.put("Payout",quote.payout().save(level.registryAccess()));
   state.putString("Stage","WAIT_STOCK");save(state);return true;
  }return false;
 }
 private void request(ServerLevel level,Settlement village,Building post,CompoundTag state,ItemStack cost){
  if(state.hasUUID("Request")) {
   var data=RequestLedgerSavedData.existing(level);var ledger=data==null?null:data.existing(village.id);
   if(ledger!=null && ledger.active(state.getUUID("Request"))!=null)return;
   state.remove("Request");
  }
  if(level.getGameTime()<state.getLong("RequestAfter"))return;
  state.putLong("RequestAfter",level.getGameTime()+100);save(state);
  if(!exportable(cost))return;
  int available=count(exportStock(level,village),cost);
  int missing=cost.getCount()-count(postStock(level,post),cost);
  if(available<missing)return;
  // A merchant order can require several trips. The ledger reserves a whole
  // parcel, so never publish one that no employed Courier can physically carry.
  int parcelCapacity=0;
  for(SettlerEntity courier:SettlementManager.loadedMembers(level,village))
   if(RequestLedgerService.validCourier(level,village,courier))
    parcelCapacity=Math.max(parcelCapacity,Math.min(courier.getCarryCapacity(),
     com.hearthstead.logistics.Weight.budgetFor(courier.getCarryCapacity())/Math.max(1,com.hearthstead.logistics.Weight.of(cost))));
  if(parcelCapacity<=0)return;
  missing=Math.min(missing,parcelCapacity);
  for(Building warehouse:village.buildings)if(warehouse.valid && warehouse.type==BuildingType.WAREHOUSE)
   for(BlockPos source:WarehouseIndex.containers(level,warehouse))if(level.getBlockEntity(source) instanceof Container chest)
    for(int slot=0;slot<chest.getContainerSize();slot++)if(TraderSaleService.matches(chest.getItem(slot),cost))
     for(BlockPos target:WarehouseIndex.containers(level,post)) {
      var decision=RequestLedgerService.openTraderRestock(level,village,warehouse,source,slot,post,target,
       chest.getItem(slot),Math.min(missing,Math.min(available,chest.getItem(slot).getCount())));
      if(decision.accepted() && decision.request()!=null){state.putUUID("Request",decision.request().id());save(state);return;}
     }
 }
 private List<ItemStack> exportStock(ServerLevel level,Settlement village){
  List<ItemStack> result=new ArrayList<>();Map<String,Integer> keep=new HashMap<>();
  for(Building b:village.buildings)if(b.valid && b.type==BuildingType.WAREHOUSE)
   for(BlockPos pos:WarehouseIndex.containers(level,b))if(level.getBlockEntity(pos) instanceof Container c)
    for(int slot=0;slot<c.getContainerSize();slot++) {
     ItemStack stack=c.getItem(slot);if(!exportable(stack))continue;
     String key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())+":"+GoodsQuality.of(stack);
     int reserve=keep.getOrDefault(key,reserve(stack));int retained=Math.min(reserve,stack.getCount());keep.put(key,reserve-retained);
     if(stack.getCount()>retained)result.add(stack.copyWithCount(stack.getCount()-retained));
    }
  var requestData=RequestLedgerSavedData.existing(level);
  var ledger=requestData==null?null:requestData.existing(village.id);
  if(ledger!=null)for(var request:ledger.active()) {
   if(request.type()!=RequestType.MATERIAL_INPUT)continue;
   ItemStack reserved=request.fingerprint().prototype(level.registryAccess());
   int remaining=request.remainingCount();
   for(ItemStack free:result)if(TraderSaleService.matches(free,reserved)) {
    int used=Math.min(remaining,free.getCount());free.shrink(used);remaining-=used;if(remaining==0)break;
   }
  }
  result.removeIf(ItemStack::isEmpty);
  return result;
 }
 private static boolean exportable(ItemStack stack){
  return !stack.isEmpty() && stack.getMaxStackSize()>1 && !stack.isDamageableItem()
   && !stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())
   && !stack.is(net.minecraft.world.item.Items.WHEAT_SEEDS) && !stack.is(net.minecraft.world.item.Items.BEETROOT_SEEDS)
   && !stack.is(net.minecraft.world.item.Items.MELON_SEEDS) && !stack.is(net.minecraft.world.item.Items.PUMPKIN_SEEDS);
 }
 private static int reserve(ItemStack stack){return stack.has(net.minecraft.core.component.DataComponents.FOOD)
  || stack.is(net.minecraft.world.item.Items.WHEAT)?128:64;}
 private void returnCargo(ServerLevel level,Settlement village,Building post,CompoundTag state){
  if(worker.bag.isEmpty()){lift(state);worker.getPersistentData().remove(KEY);nextLook=level.getGameTime()+100;WorkStopReasons.clear(worker);return;}
  for(BlockPos pos:WarehouseIndex.containers(level,post))if(level.getBlockEntity(pos) instanceof Container chest)
   for(int slot=0;slot<worker.bag.getContainerSize();slot++)if(!worker.bag.getItem(slot).isEmpty() && room(chest,worker.bag.getItem(slot))>0){WorkStopReasons.clear(worker);transfer(level,state,pos,chest,slot,false);return;}
  // J-05: the post is full. Say so (the sheet shows "chest full"), then do
  // the useful thing: carry the cargo to the settlement Warehouse instead.
  WorkStopReasons.report(worker,com.hearthstead.logistics.StopReason.CHEST_FULL,post.anchor);
  for(Building warehouse:village.buildings)if(warehouse.valid && warehouse.type==BuildingType.WAREHOUSE)
   for(BlockPos pos:WarehouseIndex.containers(level,warehouse))if(level.getBlockEntity(pos) instanceof Container chest)
    for(int slot=0;slot<worker.bag.getContainerSize();slot++)if(!worker.bag.getItem(slot).isEmpty() && room(chest,worker.bag.getItem(slot))>0){transfer(level,state,pos,chest,slot,false);return;}
  // Nowhere at all: stop standing at the chest; free time until the retry.
  lift(state);state.putLong("BlockedUntil",level.getGameTime()+BLOCKED_RETRY_TICKS);save(state);
 }
 /** A Warehouse chest the RETURN leg may unload into when the post is full. */
 private static boolean overflowChest(ServerLevel level,Settlement village,BlockPos pos){
  for(Building warehouse:village.buildings)if(warehouse.valid && warehouse.type==BuildingType.WAREHOUSE
     && WarehouseIndex.containers(level,warehouse).contains(pos))return true;
  return false;
 }
 private void transfer(ServerLevel level,CompoundTag state,BlockPos pos,Container chest,int slot,boolean pickup){
  if(!ContainerApproach.inspect(level,worker,pos).canInteract()){
   state.remove("Clock");state.remove("UnitDone");state.remove("Cycle");save(state);
   worker.setActivity(SettlerActivity.CARRYING);
   if(level.getGameTime()%20==0)ContainerApproach.moveToContact(level,worker,pos,1.0);return;
  }
  worker.getNavigation().stop();worker.setActivity(SettlerActivity.SORTING);
  if(!state.hasUUID("Cycle")){state.putUUID("Cycle",UUID.randomUUID());state.putLong("Anchor",worker.blockPosition().asLong());state.putInt("Clock",0);state.putLong("Container",pos.asLong());state.putInt("Slot",slot);state.putBoolean("Pickup",pickup);state.put("Unit",(pickup?chest.getItem(slot):worker.bag.getItem(slot)).copyWithCount(1).save(level.registryAccess()));}
  int clock=state.getInt("Clock");BlockPos anchor=BlockPos.of(state.getLong("Anchor"));
  ItemStack unit=ItemStack.parseOptional(level.registryAccess(),state.getCompound("Unit"));
  if(unit.isEmpty())return;
  if(clock>=12)worker.placeWorkContainer(WorkContainerKind.SACK,anchor);
  if(clock==48 && !state.getBoolean("UnitDone")){
   if(pickup){if(!ItemStack.isSameItemSameComponents(chest.getItem(slot),unit) || chest.getItem(slot).isEmpty()) {lift(state);save(state);return;}if(!worker.bag.canAddItem(unit))return;ItemStack removed=chest.removeItem(slot,1);ItemStack left=worker.bag.addItem(removed);if(!left.isEmpty()){if(!restorePickup(chest,slot,left))throw new IllegalStateException("Trader pickup rollback has no safe chest slot");return;}}
   else {if(!ItemStack.isSameItemSameComponents(worker.bag.getItem(slot),unit) || worker.bag.getItem(slot).isEmpty()){lift(state);save(state);return;}if(!insertOne(chest,unit))return;worker.bag.removeItem(slot,1);}
   chest.setChanged();state.putBoolean("UnitDone",true);
  }
  worker.publishBagTransferPresentation(new BagTransferPresentation(state.getUUID("Cycle"),anchor,worker.getYRot(),pos,
   clock,state.getBoolean("UnitDone"),unit,pickup));
  if(clock>=79){state.remove("Cycle");state.remove("Clock");state.remove("UnitDone");}
  else state.putInt("Clock",clock+1);save(state);
 }
 private void lift(CompoundTag state){var p=worker.bagTransferPresentation();if(p.active())worker.clearBagTransferPresentation(p.transferId());worker.clearWorkContainer();state.remove("Cycle");state.remove("Clock");state.remove("UnitDone");}
 /** Restores a rejected pickup without ever replacing a stack that remained in the source slot. */
 private static boolean restorePickup(Container chest,int preferredSlot,ItemStack rejected){
  for(int pass=0;pass<2;pass++)for(int slot=0;slot<chest.getContainerSize();slot++){
   if(pass==0&&slot!=preferredSlot)continue;
   if(pass==1&&slot==preferredSlot)continue;
   ItemStack held=chest.getItem(slot);
   if(held.isEmpty()){chest.setItem(slot,rejected.copy());chest.setChanged();return true;}
   if(ItemStack.isSameItemSameComponents(held,rejected)&&held.getCount()+rejected.getCount()<=held.getMaxStackSize()){
    held.grow(rejected.getCount());chest.setChanged();return true;
   }
  }
  return false;
 }
 private static int room(Container c,ItemStack stack){int n=0;for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);if(s.isEmpty())n+=stack.getMaxStackSize();else if(ItemStack.isSameItemSameComponents(s,stack))n+=Math.max(0,s.getMaxStackSize()-s.getCount());}return n;}
 private static boolean insertOne(Container c,ItemStack unit){for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);if(s.isEmpty()){c.setItem(i,unit.copy());return true;}if(ItemStack.isSameItemSameComponents(s,unit)&&s.getCount()<s.getMaxStackSize()){s.grow(1);c.setChanged();return true;}}return false;}
 private static List<ItemStack> postStock(ServerLevel level,Building post){List<ItemStack> out=new ArrayList<>();for(BlockPos pos:WarehouseIndex.containers(level,post))if(level.getBlockEntity(pos) instanceof Container c)for(int i=0;i<c.getContainerSize();i++)if(!c.getItem(i).isEmpty())out.add(c.getItem(i).copy());return out;}
 private List<ItemStack> bagStock(){List<ItemStack> out=new ArrayList<>();for(int i=0;i<worker.bag.getContainerSize();i++)out.add(worker.bag.getItem(i));return out;}
 private static int count(List<ItemStack> stock,ItemStack cost){return stock.stream().filter(s->TraderSaleService.matches(s,cost)).mapToInt(ItemStack::getCount).sum();}
 private static TraderSaleService.Quote quote(ServerLevel level,CompoundTag state){ItemStack cost=ItemStack.parseOptional(level.registryAccess(),state.getCompound("Cost"));ItemStack payout=ItemStack.parseOptional(level.registryAccess(),state.getCompound("Payout"));return cost.isEmpty()||payout.isEmpty()?null:new TraderSaleService.Quote(state.getUUID("Merchant"),state.getInt("Offer"),state.getInt("Uses"),cost,payout);}
 private void save(CompoundTag state){worker.getPersistentData().put(KEY,state);}
}

package com.hearthstead.settlement.work;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.npc.WanderingTrader;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/** One physical, finite merchant sale. No player emulation and no virtual wallet. */
public final class TraderSaleService {
    private static final String RECEIPTS="HearthsteadTraderSaleReceipts";
    private static final String OWNER="HearthsteadEarlyMerchantSettlement";
    private static final int MAX_RECEIPTS=4096;
    public enum Result { COMMITTED, ALREADY_COMMITTED, INVALID, BUSY, EXPIRED,
        PRICE_CHANGED, NO_GOODS, NO_ROOM, TOO_FAR, RECEIPT_FULL }
    public record Quote(UUID merchantId,int offerIndex,int uses,ItemStack cost,ItemStack payout) {
        public Quote { cost=cost.copy(); payout=payout.copy(); }
        @Override public ItemStack cost(){return cost.copy();}
        @Override public ItemStack payout(){return payout.copy();}
    }
    @Nullable public static Quote select(ServerLevel level,Settlement settlement,SettlerEntity worker,
                                         WanderingTrader merchant,List<ItemStack> stock) {
        if(!authority(level,settlement,worker,merchant) || !EarlyCoinMerchant.availableForTrade(merchant)
            || merchant.getTradingPlayer()!=null || stock==null) return null;
        // First market construction is safe only before a player opens it;
        // after that both worker and player read the same frozen rows.
        if(!GoldCoinTrades.ensureOwnedMarket(level,settlement,merchant,null,stock)) return null;
        Quote best=null;
        for(int i=0;i<merchant.getOffers().size();i++) {
            var offer=merchant.getOffers().get(i);
            if(!GoldCoinTrades.isPurchase(merchant,i,offer) || offer.isOutOfStock()) continue;
            ItemStack cost=offer.getCostA();
            int count=stock.stream().filter(stack->matches(stack,cost)).mapToInt(ItemStack::getCount).sum();
            if(count<cost.getCount()) continue;
            if(best==null || cost.getCount()<best.cost.getCount())
                best=new Quote(merchant.getUUID(),i,offer.getUses(),cost,offer.getResult());
        }
        return best;
    }
    public static Result execute(ServerLevel level,Settlement settlement,SettlerEntity worker,
                                 WanderingTrader merchant,UUID transactionId,Quote quote) {
        if(transactionId==null || quote==null || !authority(level,settlement,worker,merchant)) return Result.INVALID;
        CompoundTag data=worker.getPersistentData();
        if(data.contains(RECEIPTS) && !data.contains(RECEIPTS,Tag.TAG_LIST)) return Result.INVALID;
        ListTag receipts=data.getList(RECEIPTS,Tag.TAG_COMPOUND);
        for(int i=0;i<receipts.size();i++) {
            CompoundTag receipt=receipts.getCompound(i);
            if(receipt.hasUUID("Transaction") && transactionId.equals(receipt.getUUID("Transaction")))
                return receipt.hasUUID("Merchant") && merchant.getUUID().equals(receipt.getUUID("Merchant"))
                    && receipt.getInt("Offer")==quote.offerIndex ? Result.ALREADY_COMMITTED : Result.INVALID;
        }
        if(receipts.size()>=MAX_RECEIPTS) return Result.RECEIPT_FULL;
        if(!EarlyCoinMerchant.availableForTrade(merchant)) return Result.EXPIRED;
        if(merchant.getTradingPlayer()!=null) return Result.BUSY;
        if(worker.distanceToSqr(merchant)>9 || !worker.hasLineOfSight(merchant)) return Result.TOO_FAR;
        if(!merchant.getUUID().equals(quote.merchantId) || quote.offerIndex<0
            || quote.offerIndex>=merchant.getOffers().size()) return Result.PRICE_CHANGED;
        var offer=merchant.getOffers().get(quote.offerIndex);
        if(!GoldCoinTrades.isPurchase(merchant,quote.offerIndex,offer) || offer.isOutOfStock() || offer.getUses()!=quote.uses
            || !ItemStack.matches(offer.getCostA(),quote.cost)
            || !ItemStack.matches(offer.getResult(),quote.payout)) return Result.PRICE_CHANGED;
        SimpleContainer after=new SimpleContainer(worker.bag.getContainerSize());
        for(int i=0;i<after.getContainerSize();i++) after.setItem(i,worker.bag.getItem(i).copy());
        int remaining=quote.cost.getCount();
        for(int i=0;i<after.getContainerSize() && remaining>0;i++) {
            ItemStack stack=after.getItem(i);
            if(!matches(stack,quote.cost)) continue;
            int take=Math.min(remaining,stack.getCount()); stack.shrink(take); remaining-=take;
        }
        if(remaining>0) return Result.NO_GOODS;
        if(!after.addItem(quote.payout.copy()).isEmpty()) return Result.NO_ROOM;
        // All ordinary rejection paths are before this point. Server save and
        // another actor cannot interleave this synchronous no-callback commit.
        CompoundTag receipt=new CompoundTag(); receipt.putUUID("Transaction",transactionId);
        receipt.putUUID("Merchant",merchant.getUUID()); receipt.putInt("Offer",quote.offerIndex);
        receipt.putInt("UsesBefore",quote.uses); receipt.putLong("Tick",level.getGameTime());
        receipts.add(receipt); data.put(RECEIPTS,receipts);
        offer.increaseUses(); GoldCoinTrades.finishOwnedPurchase(level,merchant,quote.offerIndex,offer);
        // Presence: a persuasive trader talks the merchant up 0..10%, paid as
        // whole coins plus a chance of one more (AttributeRuntime.tradePayout,
        // plan/ATTRIBUTES.md). Drawn FROM the merchant's purse (economy lane:
        // the purse is the hard ceiling per visit), only while it has coins
        // and the bag has room, inside this same receipted commit. Never fails a sale.
        int bonus=com.hearthstead.entity.AttributeRuntime.tradePayout(worker,quote.payout.getCount())-quote.payout.getCount();
        if(bonus>0) {
            SimpleContainer trial=new SimpleContainer(after.getContainerSize());
            for(int i=0;i<after.getContainerSize();i++) trial.setItem(i,after.getItem(i).copy());
            if(trial.addItem(quote.payout.copyWithCount(bonus)).isEmpty()) {
                int took=GoldCoinTrades.takeFromOwnedPurse(merchant,bonus);
                if(took>0) after.addItem(quote.payout.copyWithCount(took));
            }
        }
        for(int i=0;i<after.getContainerSize();i++) worker.bag.setItem(i,after.getItem(i));
        worker.bag.setChanged();
        // A struck bargain is what trains a trader's Presence (plan/ATTRIBUTES.md).
        worker.train(com.hearthstead.entity.Attribute.PRESENCE,1.0F);
        return Result.COMMITTED;
    }
    /** Quality must match exactly; Basic offers may not silently eat premium cargo. */
    public static boolean matches(ItemStack stack,ItemStack cost) {
        return !stack.isEmpty() && stack.is(cost.getItem()) && GoodsQuality.of(stack)==GoodsQuality.of(cost);
    }
    private static boolean authority(ServerLevel level,Settlement settlement,SettlerEntity worker,WanderingTrader merchant) {
        return level!=null && settlement!=null && worker!=null && merchant!=null
            && level.getServer().isSameThread() && SettlementManager.byId(level,settlement.id)==settlement
            && worker.level()==level && merchant.level()==level && worker.isAlive() && merchant.isAlive()
            && level.getEntity(worker.getUUID())==worker && level.getEntity(merchant.getUUID())==merchant
            && worker.getProfession()==com.hearthstead.entity.Profession.TRADER
            && settlement.id.equals(worker.getSettlementId()) && settlement.record(worker.getUUID())!=null
            && com.hearthstead.settlement.Employment.employerOf(settlement,worker.getUUID())!=null
            && com.hearthstead.settlement.Employment.employerOf(settlement,worker.getUUID()).valid
            && com.hearthstead.settlement.Employment.employerOf(settlement,worker.getUUID()).type
                ==com.hearthstead.building.BuildingType.TRADING_POST
            && merchant.getPersistentData().hasUUID(OWNER)
            && settlement.id.equals(merchant.getPersistentData().getUUID(OWNER));
    }
    private TraderSaleService(){}
}

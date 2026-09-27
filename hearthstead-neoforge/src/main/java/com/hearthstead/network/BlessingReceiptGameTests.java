package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingQuality;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlessingReceiptGameTests {
    @GameTest(template="empty5",timeoutTicks=40,batch="blessing_receipt")
    public void exactPhysicalSlotsRequireTheStampedReward(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel();
        var level=helper.getLevel();
        for (BlessingQuality quality : BlessingQuality.values()) for(int mode=0;mode<3;mode++) {
            player.getInventory().clearContent();
            player.getInventory().selected=6;
            if(mode>=1) player.getInventory().setItem(6,new ItemStack(Items.STONE));
            if(mode>=2) {
                player.getInventory().setItem(40,new ItemStack(Items.STONE));
                for(int i=0;i<9;i++) player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
            }
            ItemStack seal=BlessingSealItem.stackFor(BlessingId.WARDEN_OATH, quality);
            UUID id=UUID.randomUUID();
            var reservation=PendingPlayerDeliveryLedger.reservation(level,id,player,seal);
            var ledger=new PendingPlayerDeliveryLedger();
            helper.assertTrue(ledger.reserve(reservation).accepted(),"exact output must reserve");
            var result=ledger.deliver(level,player,id);
            var expected=mode==0?BlessingReceipt.Outcome.MAIN_HAND:mode==1
                ?BlessingReceipt.Outcome.OFF_HAND:BlessingReceipt.Outcome.INVENTORY;
            helper.assertTrue(BlessingReceipt.Outcome.fromDelivery(result.outcome())==expected,
                "physical destination must follow actual hand/inventory delivery");
            int slot=PendingPlayerDeliveryLedger.exactRecipientSlot(level,player,reservation);
            helper.assertTrue(slot==(mode==0?6:mode==1?40:9),"canonical destination slot must be exact");
            var receipt=new BlessingReceipt(player.getUUID(),id,20,4,BlessingId.WARDEN_OATH.wireId(),expected,slot,quality.rankUnits());
            ItemStack delivered=player.getInventory().getItem(slot);
            helper.assertTrue(receipt.matchesSlot(player.getUUID(),slot,delivered),"actual stamped output proves receipt");
            var wrongQuality = new BlessingReceipt(player.getUUID(),id,20,4,
                BlessingId.WARDEN_OATH.wireId(),expected,slot,quality==BlessingQuality.RARE?1:2);
            helper.assertTrue(!wrongQuality.matchesSlot(player.getUUID(),slot,delivered),
                "same recipient, slot and delivery cannot substitute another seal potency");
            helper.assertTrue(!receipt.matchesSlot(player.getUUID(),slot,seal),"ordinary same-type seal is not this reward");
            helper.assertTrue(!receipt.matchesSlot(UUID.randomUUID(),slot,delivered),"other player cannot claim flight");
            helper.assertTrue(!receipt.matchesSlot(player.getUUID(),slot==9?10:9,delivered),"wrong destination cannot claim flight");
            helper.assertTrue(!PendingPlayerDeliveryLedger.matchesDelivery(delivered,seal,UUID.randomUUID(),player.getUUID()),
                "other delivery marker is not sufficient");
            helper.assertTrue(ledger.pendingCount()==0,"one real output releases the outbox row");
            ledger.deliver(level,player,id);
            helper.assertTrue(player.getInventory().getItem(slot).getCount()==1,"repeat cannot mint a second output");
        }
        helper.succeed();
    }
}

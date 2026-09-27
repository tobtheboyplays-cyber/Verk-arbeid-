package com.hearthstead.settlement.request;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierSourceBagSession;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Physical ledger regressions; does not claim pathfinding or native visual proof. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class NamedStorageTidyGameTests {
    private static final String BATCH = "codex_named_storage";
    private record Fixture(Settlement town, Building warehouse, BlockPos left, BlockPos right,
                           BlockPos target, Container a, Container b, Container out,
                           SettlerEntity courier) {}

    private static Fixture fixture(GameTestHelper h) {
        h.getLevel().setDayTime(2000);
        for (int x=0; x<9; x++) for (int z=0; z<9; z++) {
            h.setBlock(new BlockPos(x,0,z), Blocks.STONE);
            for(int y=1;y<=4;y++) h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        }
        Settlement town = new Settlement(UUID.randomUUID(), "Named storage QA", h.absolutePos(new BlockPos(8,1,8)));
        town.radius=12;
        var data=SettlementManager.data(h.getLevel());
        data.settlements.put(town.id,town); data.setDirty();
        Building warehouse=GameTestFixtures.register(h,town,BuildingType.WAREHOUSE,2,2);
        var north=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
        h.setBlock(new BlockPos(3,1,3),north.setValue(ChestBlock.TYPE,ChestType.LEFT));
        h.setBlock(new BlockPos(4,1,3),north.setValue(ChestBlock.TYPE,ChestType.RIGHT));
        h.setBlock(new BlockPos(5,1,5),Blocks.BARREL);
        BlockPos left=h.absolutePos(new BlockPos(3,1,3)), right=h.absolutePos(new BlockPos(4,1,3));
        BlockPos target=h.absolutePos(new BlockPos(5,1,5));
        BlockEntity dest=h.getLevel().getBlockEntity(target);
        dest.getPersistentData().putUUID("HearthsteadWarehouseSortingWarehouse",warehouse.id);
        dest.getPersistentData().putString("HearthsteadWarehouseSortingGroup","WOOD");
        SettlerEntity courier=h.spawn(ModEntities.SETTLER.get(),new BlockPos(4,1,4));
        courier.setNoAi(true); courier.setHunger(100); courier.setEnergy(100);
        courier.bindTo(town.id,town.center); town.putRecord(courier.getUUID(),"Carrier",Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(),town,warehouse,courier).ok(),"Courier employed");
        h.assertTrue(h.getLevel().getBlockState(left).getValue(ChestBlock.TYPE)==ChestType.LEFT
            && h.getLevel().getBlockState(right).getValue(ChestBlock.TYPE)==ChestType.RIGHT,"physical double chest");
        return new Fixture(town,warehouse,left,right,target,(Container)h.getLevel().getBlockEntity(left),
            (Container)h.getLevel().getBlockEntity(right),(Container)dest,courier);
    }
    private static void name(GameTestHelper h, BlockPos pos) {
        ItemStack named = new ItemStack(Items.CHEST);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Player storage"));
        ((ChestBlockEntity)h.getLevel().getBlockEntity(pos)).applyComponentsFromItemStack(named);
    }
    private static RequestLedgerService.Decision open(GameTestHelper h,Fixture f,boolean right) {
        return RequestLedgerService.openOutputPickup(h.getLevel(),f.town,f.warehouse,right?f.right:f.left,
            0,f.warehouse,f.target,2,RequestPriority.NORMAL);
    }
    private static RequestRecord reserve(GameTestHelper h,Fixture f) {
        f.b.setItem(0,new ItemStack(Items.OAK_LOG,2)); f.b.setChanged();
        var opened=open(h,f,true);
        h.assertTrue(opened.request()!=null,"ordinary sort opens: "+opened);
        var result=RequestLedgerService.reserve(h.getLevel(),f.town,opened.request().id(),f.courier);
        h.assertTrue(result.accepted(),"ordinary sort reserves: "+result);
        return result.request();
    }
    private static RequestRecord reload(GameTestHelper h, Fixture f, UUID id) {
        var saved=RequestLedgerSavedData.get(h.getLevel());
        CompoundTag image=saved.save(new CompoundTag(),h.getLevel().registryAccess());
        var loaded=RequestLedgerSavedData.load(image,h.getLevel().registryAccess());
        h.getLevel().getDataStorage().set("hearthstead_request_ledger",loaded);
        var ledger=loaded.existing(f.town.id);
        h.assertTrue(ledger!=null && !ledger.quarantined(),"real SavedData round trip is not quarantined");
        return ledger.any(id);
    }
    @GameTest(template="empty16",timeoutTicks=40,batch=BATCH)
    public void namedLeftProtectsStockInRight(GameTestHelper h) { namedHalf(h,false); }
    @GameTest(template="empty16",timeoutTicks=40,batch=BATCH)
    public void namedRightProtectsStockInLeft(GameTestHelper h) { namedHalf(h,true); }
    private static void namedHalf(GameTestHelper h,boolean right) {
        Fixture f=fixture(h); name(h,right?f.right:f.left);
        Container stock=right?f.a:f.b; stock.setItem(0,new ItemStack(Items.OAK_LOG,2)); stock.setChanged();
        var result=open(h,f,!right);
        h.assertTrue(result.request()==null && result.blocker()==RequestBlocker.SOURCE_INVALID,
            "named partner refuses extraction: "+result);
        h.assertTrue(stock.getItem(0).getCount()==2 && f.courier.bag.isEmpty() && f.out.isEmpty(),"stock conserved");
        h.succeed();
    }
    @GameTest(template="empty16",timeoutTicks=120,batch=BATCH)
    public void renameAfterReservationExpiresEmptyRouteAfterReload(GameTestHelper h) {
        renamedBeforeLift(h,false);
    }
    @GameTest(template="empty16",timeoutTicks=120,batch=BATCH)
    public void renameAtSavedZeroCargoPickupCancelsWithoutStranding(GameTestHelper h) {
        renamedBeforeLift(h,true);
    }
    private static void renamedBeforeLift(GameTestHelper h,boolean pickupBoundary) {
        Fixture f=fixture(h); RequestRecord row=reserve(h,f); UUID id=row.id();
        if(pickupBoundary) {
            h.assertTrue(row.markPickup(f.courier.getUUID(),h.getLevel().getGameTime()),"persisted PICKUP boundary");
            h.assertTrue(RequestLedgerSavedData.get(h.getLevel()).existing(f.town.id).mutationCommitted(row),"commit boundary");
        }
        new CourierSourceBagSession(f.courier).begin(h.getLevel(),row);
        CourierSourceBagSession restored = new CourierSourceBagSession(f.courier);
        h.assertTrue(restored.active(),"real persisted bag session exists; actor="+f.courier.position()
            +" source="+f.right+" feet="+h.getLevel().getBlockState(f.courier.blockPosition())
            +" below="+h.getLevel().getBlockState(f.courier.blockPosition().below())
            +" collision="+h.getLevel().noCollision(f.courier,f.courier.getBoundingBox())
            +" row="+row.state()+" reason="+row.blocker());
        reload(h,f,id); name(h,f.left);
        h.onEachTick(() -> {
            restored.tick(h.getLevel());
            RequestRecord current=RequestLedgerSavedData.get(h.getLevel()).existing(f.town.id).any(id);
            h.assertTrue(f.b.getItem(0).getCount()==2 && f.courier.bag.isEmpty() && f.out.isEmpty(),"no inventory mutation");
            if(current!=null && current.state().terminal()) {
                h.assertTrue(current.state()==(pickupBoundary?RequestState.CANCELLED:RequestState.EXPIRED),"correct terminal state");
                h.assertTrue(restored.retireExpiredZeroCargoOutput(h.getLevel()) && !restored.active(),
                    "real saved bag session releases the Courier");
                h.assertTrue(RequestLedgerSavedData.get(h.getLevel()).existing(f.town.id)
                    .activeForCourier(f.courier.getUUID()).isEmpty(),"ledger releases Courier");
                reload(h,f,id); h.succeed();
            }
        });
    }
    @GameTest(template="empty16",timeoutTicks=40,batch=BATCH)
    public void alreadyStartedLoadSurvivesRenameReloadAndDeliversOnce(GameTestHelper h) {
        Fixture f=fixture(h); RequestRecord row=reserve(h,f); UUID id=row.id();
        var first=RequestLedgerService.pickupOne(h.getLevel(),f.town,id,f.courier,0);
        h.assertTrue(first.accepted() && row.movedCount()==1,"first physical unit: "+first);
        name(h,f.left); row=reload(h,f,id);
        var second=RequestLedgerService.pickupOne(h.getLevel(),f.town,id,f.courier,1);
        h.assertTrue(second.accepted() && row.movedCount()==2,"already-started bounded load finishes: "+second);
        var pos=f.target;
        f.courier.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()-0.5);
        var delivered=RequestLedgerService.deliver(h.getLevel(),f.town,id,f.courier,64);
        h.assertTrue(delivered.accepted(),"carried load delivers despite source rename: "+delivered);
        RequestLedgerService.deliver(h.getLevel(),f.town,id,f.courier,64);
        h.assertTrue(f.b.isEmpty() && f.courier.bag.isEmpty() && f.out.getItem(0).is(Items.OAK_LOG)
            && f.out.getItem(0).getCount()==2,"exactly two logs, no replay duplication");
        reload(h,f,id); h.succeed();
    }
}

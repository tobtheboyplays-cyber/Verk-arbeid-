package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.*;
import com.hearthstead.registry.*;
import com.hearthstead.settlement.*;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class WholeTreeFellingGameTests {
    @GameTest(template="empty16",timeoutTicks=1800,batch="whole_tree_felling")
    public void standingTreeFallsTogetherAndLogsWaitForImpact(GameTestHelper helper) {
        var level=helper.getLevel(); level.setDayTime(2000);
        for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.DIRT);
            for(int y=1;y<=11;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        }
        BlockPos hearth=helper.absolutePos(new BlockPos(2,1,2));
        helper.setBlock(new BlockPos(2,1,2),ModBlocks.HEARTH.get());
        Settlement settlement=new Settlement(UUID.randomUUID(),"Felling",hearth);
        settlement.radius=16;
        SettlementSavedData.get(level).settlements.put(settlement.id,settlement);
        ((HearthBlockEntity)level.getBlockEntity(hearth)).bindSettlement(settlement.id);
        var camp=GameTestFixtures.register(helper,settlement,BuildingType.LUMBER_CAMP,2,10);
        var zone=WorkZone.between(settlement.id,camp.id,WorkZone.Type.LUMBER,
            level.dimension().location(),helper.absolutePos(new BlockPos(6,0,5)),
            helper.absolutePos(new BlockPos(13,11,11)),2);
        helper.assertTrue(camp.commitWorkZone(1,zone),"confirmed tall lumber zone");
        BlockPos base=helper.absolutePos(new BlockPos(10,1,8));
        for(int i=0;i<7;i++) helper.setBlock(new BlockPos(10,1+i,8),Blocks.OAK_LOG);
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
            helper.setBlock(new BlockPos(10+dx,8,8+dz),Blocks.OAK_LEAVES);
        SettlerEntity worker=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(7,1,7));
        worker.bindTo(settlement.id,hearth); settlement.putRecord(worker.getUUID(),"Rowan",Profession.NONE);
        worker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_AXE));
        helper.assertTrue(Employment.hire(level,settlement,camp,worker).ok(),"real Lumber employment");
        boolean[] sawWork={false}; long[] fellAt={-1};
        helper.onEachTick(()->{
            int standing=0; for(int i=0;i<7;i++) if(level.getBlockState(base.above(i)).is(Blocks.OAK_LOG)) standing++;
            if(worker.getActivity()==SettlerActivity.WORK_CHOP && standing==7) sawWork[0]=true;
            helper.assertTrue(standing==7 || standing==0,"all seven logs stay standing until one final contact");
            if(standing==0 && fellAt[0]<0) {
                fellAt[0]=level.getGameTime();
                helper.assertTrue(sawWork[0],"standing tree must receive visible work first");
                helper.assertTrue(!level.getEntitiesOfClass(FallingTreeEntity.class,new AABB(base).inflate(16)).isEmpty(),
                    "successful felling publishes a visual tree");
                worker.setNoAi(true);
            }
            int logs=level.getEntitiesOfClass(ItemEntity.class,new AABB(base).inflate(16)).stream()
                .filter(item->item.getItem().is(Items.OAK_LOG)).mapToInt(item->item.getItem().getCount()).sum();
            if(fellAt[0]>=0 && level.getGameTime()<fellAt[0]+FallingTreeEntity.DURATION_TICKS)
                helper.assertTrue(logs==0,"real logs remain solely in escrow until impact");
            if(fellAt[0]>=0 && level.getGameTime()>fellAt[0]+FallingTreeEntity.DURATION_TICKS+2) {
                helper.assertTrue(logs==7,"impact releases exactly the original seven logs");
                helper.succeed();
            }
        });
    }
}

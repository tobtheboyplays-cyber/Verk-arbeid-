package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.GoblinThiefDemo;
import com.hearthstead.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GoblinDoorPathGameTests {
    @GameTest(template="empty64", timeoutTicks=220, batch="goblin_door_path")
    public void fleeingThiefReversesWhenGuardInterceptsItsCurrentRoute(GameTestHelper helper) {
        var level=helper.getLevel();
        for(int x=0;x<32;x++) for(int z=0;z<32;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(5,1,16));
        BlockPos start=helper.absolutePos(new BlockPos(16,1,16));
        level.setBlockAndUpdate(target,Blocks.CHEST.defaultBlockState());
        var chest=(ChestBlockEntity)level.getBlockEntity(target);
        chest.setItem(0,new ItemStack(ModItems.GOLD_COIN.get(),30));
        var thief=GoblinThiefDemo.spawn(level,target,start);
        helper.assertTrue(thief!=null,"escape fixture spawns a real thief");
        var state=thief.getPersistentData().getCompound("HearthsteadGoblinThiefDemo");
        state.putInt("Stage",2);state.putLong("StageStartedAt",level.getGameTime());
        thief.setGoblinThiefPresentation(true,2,level.getGameTime());
        // Only initial state is forced; production navigation owns every thief movement.
        BlockPos hearthPos=helper.absolutePos(new BlockPos(3,1,3));
        level.setBlockAndUpdate(hearthPos,com.hearthstead.registry.ModBlocks.HEARTH.get().defaultBlockState());
        var settlement=new com.hearthstead.settlement.Settlement(java.util.UUID.randomUUID(),"Escape guard fixture",hearthPos);
        var saved=com.hearthstead.settlement.SettlementSavedData.get(level);
        saved.settlements.put(settlement.id,settlement);
        ((com.hearthstead.block.HearthBlockEntity)level.getBlockEntity(hearthPos)).bindSettlement(settlement.id);
        var barracks=GameTestFixtures.register(helper,settlement,com.hearthstead.building.BuildingType.BARRACKS,5,3);
        var guard=helper.spawn(com.hearthstead.registry.ModEntities.SETTLER.get(),new BlockPos(12,1,16));
        guard.bindTo(settlement.id,settlement.center);
        settlement.putRecord(guard.getUUID(),"Stationary guard",com.hearthstead.entity.Profession.NONE);
        helper.assertTrue(com.hearthstead.settlement.Employment.hire(level,settlement,barracks,guard).ok(),
            "stationary threat must have actual Guard employment");
        guard.setNoAi(true);
        helper.assertTrue(thief.isAlive()&&guard.isAlive(),"actors alive after complete fixture setup; thief="
            +actorState(thief)+" guard="+actorState(guard));
        double initialX=thief.getX();
        double[] reversalX={Double.NaN};boolean[] intercepted={false};
        helper.onEachTick(()->{
            helper.assertTrue(thief.isAlive()&&guard.isAlive(),"both actors survive route test; tick="
                +level.getGameTime()+" difficulty="+level.getDifficulty()+" thief="+actorState(thief)
                +" guard="+actorState(guard)+" intercepted="+intercepted[0]+" turnX="+reversalX[0]);
            helper.assertTrue(guard.getProfession()==com.hearthstead.entity.Profession.GUARD
                && com.hearthstead.settlement.Employment.employerOf(settlement,guard.getUUID())==barracks,
                "stationary threat retains real Guard employment during both escape legs");
            helper.assertTrue(chest.getItem(0).getCount()==30&&thief.lootCount()==0,"escape cannot invent or steal Coins");
            if(!intercepted[0]&&thief.getX()>=initialX+3) {
                // Move only the threat to intercept the current escape direction.
                reversalX[0]=thief.getX();
                guard.moveTo(thief.getX()+4,thief.getY(),thief.getZ(),0,0);
                guard.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                intercepted[0]=true;
            }
        });
        helper.succeedWhen(()->{
            helper.assertTrue(intercepted[0]&&thief.getX()<reversalX[0]-2&&thief.distanceToSqr(guard)>36,
                "must flee right from left guard, then reverse left after right-side interception; pos="
                    +thief.position()+" intercepted="+intercepted[0]+" turnX="+reversalX[0]);
        });
    }

    private static String actorState(net.minecraft.world.entity.LivingEntity actor) {
        return "{uuid="+actor.getUUID()+",age="+actor.tickCount+",health="+actor.getHealth()
            +",removed="+actor.isRemoved()+",reason="+actor.getRemovalReason()+",pos="+actor.position()+"}";
    }

    @GameTest(template="empty64", timeoutTicks=600, batch="goblin_door_path")
    public void thiefPicksClosedDoorThenStealsAndReturnsWithRealCoins(GameTestHelper helper) {
        var level=helper.getLevel();
        // Sealed corridor: there is no route around, over or underneath the door.
        for(int x=2;x<=18;x++) for(int z=2;z<=4;z++) for(int y=0;y<=3;y++) {
            boolean boundary=y==0||y==3||z==2||z==4||x==2||x==18;
            helper.setBlock(new BlockPos(x,y,z),boundary?Blocks.STONE:Blocks.AIR);
        }
        BlockPos door=helper.absolutePos(new BlockPos(9,1,3));
        var closed=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST)
            .setValue(DoorBlock.OPEN,false).setValue(DoorBlock.POWERED,false);
        level.setBlock(door,closed.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),2);
        level.setBlock(door.above(),closed.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);
        BlockPos target=helper.absolutePos(new BlockPos(15,1,3));
        BlockPos start=helper.absolutePos(new BlockPos(3,1,3));
        level.setBlockAndUpdate(target,Blocks.CHEST.defaultBlockState());
        var chest=(ChestBlockEntity)level.getBlockEntity(target);
        chest.setItem(0,new ItemStack(ModItems.GOLD_COIN.get(),30));
        var thief=GoblinThiefDemo.spawn(level,target,start);
        helper.assertTrue(thief!=null,"physical door fixture must spawn a thief");
        long[] pickStart={-1};
        boolean[] observedClosed={false}, opened={false}, stolen={false};
        helper.onEachTick(()->{
            // The implementation's contact receipt makes this a timing assertion,
            // rather than counting travel time from spawn as lockpicking time.
            if(pickStart[0]<0&&thief.goblinThiefStage()==3)
                pickStart[0]=thief.goblinThiefStageStartedAt();
            boolean isOpen=level.getBlockState(door).getValue(DoorBlock.OPEN);
            if(pickStart[0]>=0&&!opened[0]) {
                long elapsed=level.getGameTime()-pickStart[0];
                if(elapsed<40) {
                    helper.assertTrue(!isOpen,"wooden door must remain closed throughout first39 contact ticks");
                    observedClosed[0]=true;
                }
            }
            if(isOpen&&!opened[0]) {
                helper.assertTrue(pickStart[0]>=0&&observedClosed[0]
                    &&level.getGameTime()-pickStart[0]>=40,"door opening requires forty observed contact ticks");
                opened[0]=true;
            }
            helper.assertTrue(chest.getItem(0).getCount()+thief.lootCount()==30,
                "door interaction, theft and escape conserve all thirty Coins");
            if(thief.lootCount()>0) {
                helper.assertTrue(opened[0]&&thief.lootCount()==2&&chest.getItem(0).getCount()==28,
                    "physical chest arrival moves exactly two real Coins after door picking");
                stolen[0]=true;
            }
        });
        helper.succeedWhen(()->helper.assertTrue(opened[0]&&stolen[0]&&thief.goblinThiefStage()==2
            &&thief.getX()<door.getX()-1&&thief.lootCount()==2,
            "must pick door, cross it to steal, then physically flee back through it with loot; pos="
                +thief.position()+" doorOpened="+opened[0]+" stolen="+stolen[0]+" pickStart="+pickStart[0]));
    }
}

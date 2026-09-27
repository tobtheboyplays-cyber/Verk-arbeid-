package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class LadderRouteGameTests {
    @GameTest(template="empty16", timeoutTicks=600, batch="ladder_route")
    public void workerPhysicallyClimbsAndReturns(GameTestHelper helper) {
        for(int x=0;x<12;x++) for(int z=0;z<12;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        for(int y=1;y<=6;y++) {
            helper.setBlock(new BlockPos(6,y,6),Blocks.STONE);
            helper.setBlock(new BlockPos(6,y,5),Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING,Direction.NORTH));
        }
        for(int x=4;x<=8;x++) for(int z=6;z<=9;z++) helper.setBlock(new BlockPos(x,5,z),Blocks.STONE);
        // Leave support behind every rung and provide a sideways deck exit.
        helper.setBlock(new BlockPos(5,5,5),Blocks.STONE);
        var mob=ModEntities.SETTLER.get().create(helper.getLevel());
        var bottom=helper.absolutePos(new BlockPos(6,1,4));
        var top=helper.absolutePos(new BlockPos(5,6,8));
        mob.moveTo(bottom.getX()+.5,bottom.getY(),bottom.getZ()+.5,0,0);
        mob.setOnGround(true);
        for(var g:java.util.List.copyOf(mob.goalSelector.getAvailableGoals())) mob.goalSelector.removeGoal(g.getGoal());
        helper.getLevel().addFreshEntity(mob);
        boolean[] climbed={false};
        boolean[] descendedOnLadder={false};
        boolean[] replannedAscent={false};
        boolean[] replannedDescent={false};
        float initialHealth=mob.getHealth();
        GameTestTicks.at(helper, 5,()->{
            // moveTo(x,y,z) accepts a path ending one block short. This test
            // requires the actual deck cell, so request zero target tolerance.
            var ascent=mob.getNavigation().createPath(top,0);
            helper.assertTrue(ascent!=null&&ascent.canReach(),"exact ladder route must be found");
            helper.assertTrue(mob.getNavigation().moveTo(ascent,1),"exact ladder route must start");
        });
        helper.onEachTick(()->{
            if(mob.tickCount%40==0) {
                Hearthstead.LOGGER.info("HSQA_LADDER_TRACE actor={} climbed={} descended={} ascentRefresh={} descentRefresh={} bottom={} top={} {}",
                    mob.getUUID(),climbed[0],descendedOnLadder[0],replannedAscent[0],replannedDescent[0],bottom,top,describe(mob));
            }
            // Goal changes and ordinary navigation refreshes may happen while
            // a worker is mid-climb. Exercise that production transition;
            // this does not move the actor or replace the physical route.
            if (!climbed[0] && !replannedAscent[0] && mob.tickCount>25 && mob.onClimbable()
                    && mob.getY()>bottom.getY()+.35) {
                replannedAscent[0]=true;
                BlockPos currentRung=mob.blockPosition();
                mob.getNavigation().recomputePath();
                helper.assertTrue(mob.getNavigation().getPath()!=null
                        && mob.getNavigation().getPath().canReach()
                        && mob.getNavigation().getPath().getNodePos(0).equals(currentRung),
                    "mid-climb refresh must continue from the current rung, not the ladder base");
            }
            if (climbed[0] && mob.getY() > bottom.getY()+1 && mob.getY() < top.getY()-1) {
                helper.assertTrue(mob.blockPosition().getX()==bottom.getX()
                    && mob.blockPosition().getZ()==bottom.getZ()+1,
                    "descent must stay on ladder, not fall from deck");
                descendedOnLadder[0]=true;
            }
            if(!climbed[0] && mob.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(top))<1) {
                climbed[0]=true;
                var descent=mob.getNavigation().createPath(bottom,0);
                helper.assertTrue(descent!=null&&descent.canReach(),"exact return route must be found");
                helper.assertTrue(mob.getNavigation().moveTo(descent,1),"exact return route must start");
            }
            if (climbed[0] && !replannedDescent[0] && mob.onClimbable()
                    && mob.getY()<top.getY()-1) {
                replannedDescent[0]=true;
                BlockPos currentRung=mob.blockPosition();
                mob.getNavigation().recomputePath();
                helper.assertTrue(mob.getNavigation().getPath()!=null
                        && mob.getNavigation().getPath().canReach()
                        && mob.getNavigation().getPath().getNodePos(0).equals(currentRung),
                    "mid-descent refresh must continue from the current rung, not the ladder base");
            }
        });
        helper.succeedWhen(()->helper.assertTrue(climbed[0] && descendedOnLadder[0] && replannedAscent[0] && replannedDescent[0] && mob.getHealth()>=initialHealth && mob.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(bottom))<1,
            "must physically climb to deck and return through both route refreshes; climbed="+climbed[0]+" descended="+descendedOnLadder[0]
                +" ascentRefresh="+replannedAscent[0]+" descentRefresh="+replannedDescent[0]
                +" bottom="+bottom+" top="+top+" "+describe(mob)));
    }

    private static String describe(com.hearthstead.entity.SettlerEntity mob) {
        var path=mob.getNavigation().getPath();
        StringBuilder nodes=new StringBuilder();
        if(path!=null) for(int i=0;i<path.getNodeCount();i++)
            nodes.append(i==path.getNextNodeIndex()?" >":" ").append(path.getNodePos(i));
        return "pos="+mob.position()+" velocity="+mob.getDeltaMovement()+" feet="
            +mob.level().getBlockState(mob.blockPosition())+" climb="+mob.onClimbable()
            +" grounded="+mob.onGround()+" health="+mob.getHealth()+" pathReach="+(path!=null&&path.canReach())
            +" next="+(path==null?-1:path.getNextNodeIndex())+" nodes="+nodes;
    }
}

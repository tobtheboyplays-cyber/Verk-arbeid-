package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GroundedBattleQaGameTests {
    @GameTest(template="empty32", skyAccess=true, timeoutTicks=100, batch="grounded_battle_geometry")
    public void rejectsWaterAndCliffsAndBuildsOnlyGroundedRoom(GameTestHelper helper) {
        var level=helper.getLevel();
        for(int x=1;x<31;x++) for(int z=1;z<31;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.GRASS_BLOCK);
            for(int y=1;y<8;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        }
        BlockPos hint=helper.absolutePos(new BlockPos(16,1,16));
        BlockPos center=BattleQaFixtureService.groundSiteAt(level,hint);
        helper.assertTrue(hint.equals(center),"Flat owned terrain must yield its real surface: expected="
            + hint + ", actual=" + center + ", centerHeight="
            + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,hint.getX(),hint.getZ())
            + ", edgeHeight=" + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,hint.getX()-13,hint.getZ()-14));
        BlockPos obstacle=helper.absolutePos(new BlockPos(8,0,8));
        level.setBlockAndUpdate(obstacle,Blocks.WATER.defaultBlockState());
        helper.assertTrue(BattleQaFixtureService.groundSiteAt(level,hint)==null,"Water must reject before authoring");
        level.setBlockAndUpdate(obstacle,Blocks.GRASS_BLOCK.defaultBlockState());
        for(int y=1;y<=3;y++) level.setBlockAndUpdate(obstacle.above(y),Blocks.STONE.defaultBlockState());
        helper.assertTrue(BattleQaFixtureService.groundSiteAt(level,hint)==null,"Three-block cliff must reject");
        for(int y=1;y<=3;y++) level.setBlockAndUpdate(obstacle.above(y),Blocks.AIR.defaultBlockState());
        BattleQaFixtureService.groundedRoom(level,center,false);
        helper.assertTrue(level.getBlockState(center.below()).is(Blocks.COBBLESTONE),"Room floor is physically supported");
        BlockPos door=center.offset(0,0,3);
        helper.assertTrue(level.getBlockState(door).is(Blocks.OAK_DOOR)
            && level.getBlockState(door.above()).getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER,"Actual two-part doorway exists");
        helper.assertTrue(level.getBlockState(center.above(3)).is(Blocks.OAK_PLANKS),"Room has physical roof");
        helper.assertTrue(level.getBlockState(center.offset(2,2,0)).getValue(LanternBlock.HANGING),"Lantern hangs from real roof");
        helper.assertTrue(level.getBlockState(obstacle).is(Blocks.GRASS_BLOCK)
            && level.getBlockState(obstacle.above()).isAir(),"Terrain beyond footprint remains unchanged");
        helper.succeed();
    }
}

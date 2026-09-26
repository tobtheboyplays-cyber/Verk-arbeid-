package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.guard.BannerTeams;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class BannerOrderNetworkGameTests {
    @GameTest(template="empty16",timeoutTicks=100,batch="banner_menu_session_authority")
    public void skyRejectsMoveButFollowCommitsOnce(GameTestHelper helper){
        Fixture f=fixture(helper);
        BannerOrderNetwork.handle(f.player,new BannerOrderActionPayload(BannerOrderActionPayload.ASSIGN,
            BannerOrderActionPayload.NONE,f.guard.getUUID()));
        helper.assertTrue(BannerTeams.resolve(f.player,DyeColor.BLUE)!=null,"physical banner assignment creates local team");
        var untouched=f.settlement.writeNbt();
        BannerOrderNetwork.handle(f.player,new BannerOrderActionPayload(BannerOrderActionPayload.FOLLOW,
            UUID.randomUUID(),BannerOrderActionPayload.NONE));
        helper.assertTrue(untouched.equals(f.settlement.writeNbt()),"unknown menu token cannot issue orders");
        // The test arena has an overhead enclosure. Assignment already happened
        // at interaction distance; move only the observer above it for a real sky ray.
        f.player.setPos(f.player.getX(),helper.getLevel().getMaxBuildHeight()-4,f.player.getZ());
        f.player.setXRot(-90);
        var skyHit=f.player.pick(48,1,false);
        helper.assertTrue(skyHit.getType()==net.minecraft.world.phys.HitResult.Type.MISS,
            "fixture must aim at unobstructed sky: eye="+f.player.getEyePosition()+" look="+f.player.getLookAngle()+" hit="+skyHit.getType()+" at="+skyHit.getLocation());
        UUID sky=UUID.randomUUID();
        BannerOrderNetwork.handle(f.player,BannerOrderActionPayload.open(sky));
        BannerOrderNetwork.handle(f.player,new BannerOrderActionPayload(BannerOrderActionPayload.MOVE,sky,BannerOrderActionPayload.NONE));
        helper.assertTrue(untouched.equals(f.settlement.writeNbt()),"sky aim must not invent a Move target");
        helper.runAfterDelay(5,()->{
            UUID follow=UUID.randomUUID();
            BannerOrderNetwork.handle(f.player,BannerOrderActionPayload.open(follow));
            BannerOrderNetwork.handle(f.player,new BannerOrderActionPayload(BannerOrderActionPayload.FOLLOW,follow,BannerOrderActionPayload.NONE));
            helper.assertTrue("FOLLOW".equals(BannerTeams.resolve(f.player,DyeColor.BLUE).order()),"Follow remains usable while aiming at sky");
            var committed=f.settlement.writeNbt();
            BannerOrderNetwork.handle(f.player,new BannerOrderActionPayload(BannerOrderActionPayload.FOLLOW,follow,BannerOrderActionPayload.NONE));
            helper.assertTrue(committed.equals(f.settlement.writeNbt()),"replaying consumed token cannot rewrite saved order");
            helper.succeed();
        });
    }
    @GameTest(template="empty16",timeoutTicks=100,batch="banner_assignment_distance_authority")
    public void distantAssignmentDoesNotCreateTeam(GameTestHelper helper){
        Fixture f=fixture(helper);
        f.player.setPos(f.guard.getX()+12,f.guard.getY(),f.guard.getZ());
        BannerOrderNetwork.handle(f.player,new BannerOrderActionPayload(BannerOrderActionPayload.ASSIGN,
            BannerOrderActionPayload.NONE,f.guard.getUUID()));
        helper.assertTrue(BannerTeams.resolve(f.player,DyeColor.BLUE)==null,"forged distant assignment cannot create a team");
        helper.succeed();
    }
    private static Fixture fixture(GameTestHelper helper){
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE_BRICKS);
            for(int y=1;y<5;y++)helper.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        }
        var settlement=new Settlement(UUID.randomUUID(),"Bannerholm",helper.absolutePos(new BlockPos(8,1,8)));
        settlement.radius=12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id,settlement);
        var barracks=GameTestFixtures.register(helper,settlement,BuildingType.BARRACKS,2,2);
        var guard=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(6,1,6));
        guard.bindTo(settlement.id,settlement.center);
        settlement.putRecord(guard.getUUID(),"Banner Guard",Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(),settlement,barracks,guard).ok(),"fixture hires a physical Guard");
        guard.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
        ServerPlayer player=helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(guard.getX()+1,guard.getY(),guard.getZ());
        player.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BLUE_BANNER));
        return new Fixture(settlement,guard,player);
    }
    private record Fixture(Settlement settlement,SettlerEntity guard,ServerPlayer player){}
}

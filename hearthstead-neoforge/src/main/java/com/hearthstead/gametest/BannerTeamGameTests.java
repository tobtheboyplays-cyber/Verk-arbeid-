package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.guard.BannerTeamBook;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.entity.ai.BannerTeamGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class BannerTeamGameTests {
    private static Settlement arena(GameTestHelper h) {
        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            h.setBlock(new BlockPos(x,0,z),Blocks.STONE_BRICKS);
            for(int y=1;y<4;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        }
        Settlement s=new Settlement(UUID.randomUUID(),"Bannerholm",h.absolutePos(new BlockPos(8,1,8)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id,s); return s;
    }
    private static SettlerEntity guard(GameTestHelper h,Settlement s,int x,int z) {
        var barracks=GameTestFixtures.register(h,s,BuildingType.BARRACKS,x,z);
        SettlerEntity guard=h.spawn(ModEntities.SETTLER.get(),new BlockPos(x,1,z));
        guard.bindTo(s.id,s.center);s.putRecord(guard.getUUID(),"Banner Guard",Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(),s,barracks,guard).ok(),"Guard hire");
        guard.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_SWORD));
        guard.setHealth(guard.getMaxHealth()); return guard;
    }
    @GameTest(template="empty16",batch="banner_team",timeoutTicks=40)
    public void colorsRemainIndependentAcrossSettlementReload(GameTestHelper h) {
        Settlement s=arena(h); SettlerEntity blue=guard(h,s,2,2),red=guard(h,s,12,12);
        UUID leaderA=UUID.randomUUID(),leaderB=UUID.randomUUID();
        s.bannerTeams.assign(blue.getUUID(),11);s.bannerTeams.assign(red.getUUID(),14);
        s.bannerTeams.claim(11,leaderA);s.bannerTeams.claim(14,leaderB);
        BlockPos bluePost=h.absolutePos(new BlockPos(3,1,9));
        s.bannerTeams.issue(11,new BannerTeamBook.Command(BannerTeamBook.Order.HOLD,bluePost,null,leaderA));
        s.bannerTeams.issue(14,new BannerTeamBook.Command(BannerTeamBook.Order.FOLLOW,null,null,leaderB));
        Settlement loaded=Settlement.readNbt(s.writeNbt());
        h.assertTrue(loaded.bannerTeams.color(blue.getUUID())==11 && loaded.bannerTeams.color(red.getUUID())==14,"Persist exact membership");
        h.assertTrue(loaded.bannerTeams.command(11).target().equals(bluePost)
            && loaded.bannerTeams.command(14).order()==BannerTeamBook.Order.FOLLOW,"Persist independent commands");
        loaded.bannerTeams.claim(11,leaderB);
        h.assertTrue(loaded.bannerTeams.command(11).order()==BannerTeamBook.Order.HOLD
            && loaded.bannerTeams.command(11).issuer().equals(leaderA),"Takeover alone must preserve order");
        Settlement other=new Settlement(UUID.randomUUID(),"Other",s.center);
        h.assertTrue(other.bannerTeams.command(11)==null,"Same color in another settlement has no command");h.succeed();
    }
    @GameTest(template="empty16",batch="banner_team",timeoutTicks=40)
    public void holdRejectsLureAndFollowReleasesDistantCombat(GameTestHelper h) {
        Settlement s=arena(h); SettlerEntity guard=guard(h,s,2,2);
        UUID issuer=UUID.randomUUID();s.bannerTeams.assign(guard.getUUID(),11);s.bannerTeams.claim(11,issuer);
        BlockPos post=h.absolutePos(new BlockPos(2,1,2));
        s.bannerTeams.issue(11,new BannerTeamBook.Command(BannerTeamBook.Order.HOLD,post,null,issuer));
        var enemy=h.spawn(EntityType.ZOMBIE,new BlockPos(14,1,14));enemy.setNoAi(true);
        h.assertTrue(!BannerTeams.allowsTarget(guard,enemy),"Hold must reject hostile lure outside defended area");
        guard.setTarget(enemy);
        s.bannerTeams.issue(11,new BannerTeamBook.Command(BannerTeamBook.Order.FOLLOW,null,null,issuer));
        // An offline issuer means hold locally. Existing target must lose authority immediately.
        h.assertTrue(!BannerTeams.allowsTarget(guard,enemy),"Follow must not retain distant old combat");
        h.assertTrue(BannerTeams.anchor(guard).equals(guard.blockPosition()),"Offline leader must not send guard home");
        var move=new BannerTeamBook.Command(BannerTeamBook.Order.MOVE,post,null,issuer);
        s.bannerTeams.issue(11,move);
        h.assertTrue(BannerTeams.arrived(guard,move),"Arrival is recorded at the destination");
        var loaded=Settlement.readNbt(s.writeNbt());
        h.assertTrue(loaded.bannerTeams.command(11).id().equals(move.id()),"Command identity survives reload");
        guard.setPos(post.getX()+11,post.getY(),post.getZ());
        enemy.setPos(guard.getX()+1,guard.getY(),guard.getZ());
        h.assertTrue(!BannerTeams.allowsTarget(guard,enemy),"A completed Move must not resume travel rules when kited away");
        h.succeed();
    }
    @GameTest(template="empty16",batch="banner_team",timeoutTicks=240)
    public void moveActuallyNavigatesWhileOtherColorHolds(GameTestHelper h) {
        Settlement s=arena(h);SettlerEntity moving=guard(h,s,2,2),holding=guard(h,s,12,12);
        UUID issuer=UUID.randomUUID();BlockPos goal=h.absolutePos(new BlockPos(10,1,3)),post=holding.blockPosition();
        s.bannerTeams.assign(moving.getUUID(),11);s.bannerTeams.assign(holding.getUUID(),14);
        s.bannerTeams.claim(11,issuer);s.bannerTeams.claim(14,issuer);
        s.bannerTeams.issue(11,new BannerTeamBook.Command(BannerTeamBook.Order.MOVE,goal,null,issuer));
        s.bannerTeams.issue(14,new BannerTeamBook.Command(BannerTeamBook.Order.HOLD,post,null,issuer));
        h.succeedWhen(() -> {
            h.assertTrue(moving.blockPosition().distSqr(goal)<=16,"Blue moves to command area");
            h.assertTrue(holding.blockPosition().distSqr(post)<=16,"Red remains at its own post");
        });
    }
}

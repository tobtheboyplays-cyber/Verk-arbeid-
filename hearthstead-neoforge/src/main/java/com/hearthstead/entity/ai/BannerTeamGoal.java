package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.BannerTeamBook;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;

/** Shared banner movement; ordinary melee/ranged goals retain attack authority. */
public final class BannerTeamGoal extends Goal {
    private final SettlerEntity settler;
    private final GuardOrderGoal needs;
    private int repath;
    private int failures;
    private BannerTeamBook.Command seen;
    public BannerTeamGoal(SettlerEntity settler) {
        this.settler=settler; needs=new GuardOrderGoal(settler);
        setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
    }
    @Override public boolean canUse() {
        return settler.level() instanceof ServerLevel level && BannerTeams.active(settler)!=null
            && !com.hearthstead.settlement.guard.FieldOrders.controls(settler)
            && com.hearthstead.settlement.summon.PlayerSummons.active(settler)==null
            && settler.getTarget()==null
            && EquipmentRequests.readyForProfession(level,settler,settler.getProfession())
            && !needs.shouldYieldToEssentialNeed(level,settler.settlement());
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public void start() { repath=0; settler.setActivity(SettlerActivity.PATROLLING); }
    @Override public void tick() {
        var command=BannerTeams.active(settler); BlockPos anchor=BannerTeams.anchor(settler);
        if(command==null || anchor==null)return;
        BannerTeams.arrived(settler,command);
        if(command!=seen) { seen=command; failures=0; repath=0; }
        // Each member takes a stable nearby slot; no pile-up on one exact block.
        var members=settler.settlement().bannerTeams.members();
        Integer color=members.get(settler.getUUID());
        var ids=members.entrySet().stream().filter(e -> e.getValue().equals(color)).map(java.util.Map.Entry::getKey).sorted().toList();
        int index=Math.max(0,ids.indexOf(settler.getUUID()));
        BlockPos destination=anchor.offset((index%3-1)*2,0,(index/3%3-1)*2);
        if(command.order()==BannerTeamBook.Order.FOLLOW && settler.blockPosition().distSqr(anchor)<=16
            || settler.blockPosition().distSqr(destination)<=2.25) { settler.getNavigation().stop(); return; }
        if(--repath>0)return;
        repath=20;
        var path=settler.getNavigation().createPath(destination,0);
        // Slot obstruction may use the common anchor; never teleport or edit terrain.
        if(path==null || !path.canReach()) {
            var fallback=settler.getNavigation().createPath(anchor,0);
            if(fallback!=null && (path==null || fallback.canReach()))path=fallback;
        }
        BlockPos end=path==null || path.getNodeCount()==0?null:path.getNodePos(path.getNodeCount()-1);
        boolean progress=end!=null && end.distSqr(anchor)<settler.blockPosition().distSqr(anchor);
        if(path!=null && (path.canReach() || progress) && settler.getNavigation().moveTo(path,1.1)) { failures=0; return; }
        settler.getNavigation().stop();
        if(++failures==4 && settler.level() instanceof ServerLevel level) {
            var player=level.getServer().getPlayerList().getPlayer(command.issuer());
            if(player!=null)player.displayClientMessage(Component.literal(settler.getSettlerName()+": Path blocked"),true);
        }
        if(failures>=4)repath=100;
    }
    @Override public void stop() { settler.getNavigation().stop(); settler.setActivity(SettlerActivity.IDLE); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
}

package com.hearthstead.network;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.guard.BannerTeams;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** Main-thread authority for banner assignment and short-lived menu sessions. */
public final class BannerOrderNetwork {
    private static final double AIM_RANGE=48;
    private static final Map<ServerPlayer,Session> MENUS=new WeakHashMap<>();
    private static final Map<ServerPlayer,Long> LAST_OPEN=new WeakHashMap<>();
    private record Session(UUID token,DyeColor color,UUID settlement,UUID leader,
            ResourceKey<Level> dimension,long expires,Vec3 origin,boolean ground,
            BlockPos point,UUID enemy,String enemyName) {}

    public static void handle(ServerPlayer player,BannerOrderActionPayload action){
        if(!player.isAlive()||player.isSpectator()
            ||!(player.getMainHandItem().getItem() instanceof BannerItem banner)) return;
        DyeColor color=banner.getColor();
        if(action.action()==BannerOrderActionPayload.ASSIGN){
            if(player.serverLevel().getEntity(action.settler()) instanceof SettlerEntity settler
                &&settler.isAlive()&&player.distanceToSqr(settler)<=36&&player.hasLineOfSight(settler)) {
                if(BannerTeams.assign(player,settler,color)) player.displayClientMessage(Component.translatable(
                    "hearthstead.banner.assigned",settler.getSettlerName(),Component.translatable("hearthstead.banner.color."+color.getName())),true);
                else feedback(player,"cannot_assign");
            }
            return;
        }
        if(action.action()==BannerOrderActionPayload.OPEN){open(player,color,action.token());return;}
        Session session=MENUS.get(player);
        var context=BannerTeams.resolve(player,color);
        if(session==null||context==null||!session.token.equals(action.token())||session.color!=color
            ||!session.dimension.equals(player.level().dimension())
            ||player.serverLevel().getGameTime()>session.expires
            ||player.position().distanceToSqr(session.origin)>64
            ||!session.settlement.equals(context.settlementId())
            ||!Objects.equals(session.leader,context.leader())){
            MENUS.remove(player);feedback(player,"expired");return;
        }
        if(action.action()==BannerOrderActionPayload.TAKEOVER){
            if(!BannerTeams.claim(player,session.settlement,color)){feedback(player,"expired");return;}
            var refreshed=BannerTeams.resolve(player,color);
            if(refreshed==null){MENUS.remove(player);return;}
            session=new Session(session.token,session.color,session.settlement,refreshed.leader(),
                session.dimension,session.expires,session.origin,session.ground,session.point,session.enemy,session.enemyName);
            MENUS.put(player,session);send(player,session,refreshed);return;
        }
        String order=switch(action.action()){
            case BannerOrderActionPayload.MOVE->"MOVE";
            case BannerOrderActionPayload.HOLD->"HOLD";
            case BannerOrderActionPayload.ATTACK->"ATTACK";
            case BannerOrderActionPayload.FOLLOW->"FOLLOW";
            default->null;
        };
        if(order==null) return;
        MENUS.remove(player); // A command session can commit at most once.
        boolean follow=action.action()==BannerOrderActionPayload.FOLLOW;
        UUID enemy=action.action()==BannerOrderActionPayload.ATTACK?session.enemy:null;
        if(!follow&&!session.ground&&enemy==null){feedback(player,"aim");return;}
        if(!follow&&!player.serverLevel().hasChunkAt(session.point)){feedback(player,"expired");return;}
        if(enemy!=null&&!(player.serverLevel().getEntity(enemy) instanceof Monster monster&&monster.isAlive())){
            feedback(player,"target_gone");return;
        }
        player.displayClientMessage(BannerTeams.issue(player,session.settlement,color,order,follow?null:session.point,enemy,false),true);
    }

    private static void open(ServerPlayer player,DyeColor color,UUID token){
        long now=player.serverLevel().getGameTime();
        if(now-LAST_OPEN.getOrDefault(player,Long.MIN_VALUE/2)<4)return;
        LAST_OPEN.put(player,now);
        var context=BannerTeams.resolve(player,color);
        if(context==null){feedback(player,"no_team");return;}
        Vec3 eye=player.getEyePosition();
        HitResult hit=player.pick(AIM_RANGE,1,false);
        boolean ground=hit instanceof BlockHitResult&&hit.getType()==HitResult.Type.BLOCK;
        BlockPos point=ground?((BlockHitResult)hit).getBlockPos().relative(((BlockHitResult)hit).getDirection()):BlockPos.ZERO;
        Vec3 end=eye.add(player.getLookAngle().scale(AIM_RANGE));
        double maxDistance=ground?eye.distanceToSqr(hit.getLocation()):AIM_RANGE*AIM_RANGE;
        var entityHit=ProjectileUtil.getEntityHitResult(player,eye,end,
            player.getBoundingBox().expandTowards(player.getLookAngle().scale(AIM_RANGE)).inflate(1),
            entity->entity instanceof Monster&&entity.isAlive()&&!entity.isSpectator(),maxDistance);
        UUID enemy=null;String enemyName="";
        if(entityHit!=null){enemy=entityHit.getEntity().getUUID();enemyName=entityHit.getEntity().getDisplayName().getString();
            // Ground commands remain bound to the actual ground behind the enemy.
            if(!ground)point=entityHit.getEntity().blockPosition();
        }
        if(enemyName.length()>80)enemyName=enemyName.substring(0,80);
        Session session=new Session(token,color,context.settlementId(),context.leader(),player.level().dimension(),
            now+600,player.position(),ground,point,enemy,enemyName);
        MENUS.put(player,session);send(player,session,context);
    }
    private static void send(ServerPlayer player,Session session,BannerTeams.Context context){
        String leader=context.leaderName()==null?"":context.leaderName();
        if(leader.length()>80)leader=leader.substring(0,80);
        com.hearthstead.network.PayloadSend.toPlayer(player,new BannerOrderMenuPayload(session.token,session.color.getId(),
            context.members(),!context.takeoverRequired(),leader,session.ground,session.point,session.enemyName));
    }
    private static void feedback(ServerPlayer player,String key){player.displayClientMessage(Component.translatable("hearthstead.banner."+key),true);}
    private BannerOrderNetwork(){}
}

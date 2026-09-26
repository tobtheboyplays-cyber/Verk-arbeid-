package com.hearthstead.settlement.guard;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.Employment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.DyeColor;
import javax.annotation.Nullable;
import java.util.UUID;

/** Server-only local banner authority. Never loads a chunk or grants equipment. */
public final class BannerTeams {
    public record Context(UUID settlementId,int members,@Nullable UUID leader,String leaderName,String order,boolean takeoverRequired) { }
    private static boolean playerValid(ServerPlayer player) { return player!=null && player.isAlive() && !player.isSpectator(); }
    public static boolean eligible(Settlement settlement, SettlerEntity settler) {
        if(settlement==null || settler==null || !settler.isAlive() || settler.isTraveler()
            || !settler.getProfession().martial() || !settlement.id.equals(settler.getSettlementId())
            || !settlement.center.equals(settler.getHearthPos())) return false;
        long rows=settlement.settlers.stream().filter(r -> settler.getUUID().equals(r.entityId)
            && r.profession==settler.getProfession()).count();
        long employers=settlement.buildings.stream().filter(b -> b.valid && b.workers.contains(settler.getUUID())
            && Employment.tradeOf(b.type)==settler.getProfession()).count();
        return rows==1 && employers==1;
    }
    public static boolean assign(ServerPlayer player,SettlerEntity settler,DyeColor color) {
        if(!playerValid(player) || color==null || settler==null || settler.level()!=player.serverLevel()
            || player.distanceToSqr(settler)>36 || !player.hasLineOfSight(settler)) return false;
        Settlement settlement=settler.settlement();
        if(!eligible(settlement,settler)) return false;
        Integer old=settlement.bannerTeams.color(settler.getUUID());
        // Membership changes also respect both teams' current live commanders.
        if(liveOther(player,settlement.bannerTeams.leader(color.getId()))
            || old!=null && liveOther(player,settlement.bannerTeams.leader(old))) return false;
        if(!settlement.bannerTeams.assign(settler.getUUID(),color.getId())) return false;
        if(settlement.bannerTeams.leader(color.getId())==null) settlement.bannerTeams.claim(color.getId(),player.getUUID());
        dirty(player.serverLevel()); return true;
    }
    @Nullable public static Context resolve(ServerPlayer player,DyeColor color) {
        Settlement settlement=resolveSettlement(player,color);
        if(settlement==null) return null;
        UUID leader=settlement.bannerTeams.leader(color.getId());
        ServerPlayer live=leader==null?null:player.server.getPlayerList().getPlayer(leader);
        var command=settlement.bannerTeams.command(color.getId());
        return new Context(settlement.id,count(settlement,color.getId()),leader,
            live==null?"":live.getGameProfile().getName(),command==null?"":command.order().name(),liveOther(player,leader));
    }
    @Nullable private static Settlement resolveSettlement(ServerPlayer player,DyeColor color) {
        if(!playerValid(player) || color==null) return null;
        SettlementSavedData data=SettlementSavedData.existing(player.serverLevel()); if(data==null)return null;
        Settlement found=null;
        for(Settlement s:data.settlements.values()) {
            if(s.bannerTeams.quarantined() || count(s,color.getId())==0)continue;
            boolean nearby=player.blockPosition().distSqr(s.center)<=96*96
                || player.getUUID().equals(s.bannerTeams.leader(color.getId()))
                || s.settlers.stream().anyMatch(r -> Integer.valueOf(color.getId()).equals(s.bannerTeams.color(r.entityId))
                    && player.serverLevel().getEntity(r.entityId) instanceof SettlerEntity member && player.distanceToSqr(member)<=64*64);
            if(!nearby)continue;
            if(found!=null)return null; // No accidental same-color cross-settlement authority.
            found=s;
        } return found;
    }
    private static int count(Settlement s,int color) {
        return (int)s.settlers.stream().filter(r -> r.profession!=null && r.profession.martial()
            && Integer.valueOf(color).equals(s.bannerTeams.color(r.entityId))).count();
    }
    private static boolean liveOther(ServerPlayer player,UUID leader) {
        if(leader==null || leader.equals(player.getUUID()))return false;
        ServerPlayer other=player.server.getPlayerList().getPlayer(leader);
        return other!=null && other.isAlive() && !other.isSpectator();
    }
    public static boolean claim(ServerPlayer player,UUID expectedSettlementId,DyeColor color) {
        Settlement s=resolveSettlement(player,color);
        if(s==null || !s.id.equals(expectedSettlementId))return false;
        boolean result=s.bannerTeams.claim(color.getId(),player.getUUID()); if(result)dirty(player.serverLevel()); return result;
    }
    public static Component claim(ServerPlayer player,DyeColor color) {
        Context context=resolve(player,color);
        return Component.literal(context!=null && claim(player,context.settlementId(),color)?"Command taken.":"Team unavailable.");
    }
    public static Component issue(ServerPlayer player,DyeColor color,String order,BlockPos target,UUID enemy) {
        return issue(player,color,order,target,enemy,false);
    }
    public static Component issue(ServerPlayer player,DyeColor color,String order,BlockPos target,UUID enemy,boolean takeover) {
        Context context=resolve(player,color);
        return issue(player,context==null?null:context.settlementId(),color,order,target,enemy,takeover);
    }
    public static Component issue(ServerPlayer player,UUID expectedSettlementId,DyeColor color,String order,BlockPos target,UUID enemy,boolean takeover) {
        Settlement s=resolveSettlement(player,color);
        if(s==null || !s.id.equals(expectedSettlementId))return Component.literal("Team unavailable.");
        BannerTeamBook.Order mode;
        try { mode=BannerTeamBook.Order.valueOf(order); } catch(RuntimeException ex) { return Component.literal("Invalid order."); }
        if(liveOther(player,s.bannerTeams.leader(color.getId())) && !takeover)return Component.literal("Take command first.");
        if(mode!=BannerTeamBook.Order.FOLLOW && (target==null || !player.serverLevel().isLoaded(target)
            || target.distSqr(player.blockPosition())>52*52))return Component.literal("Aim at nearby ground or an enemy.");
        if(mode==BannerTeamBook.Order.ATTACK && enemy!=null) {
            var entity=player.serverLevel().getEntity(enemy);
            if(!(entity instanceof LivingEntity living) || !hostile(s,living)
                || !player.hasLineOfSight(living) || player.distanceToSqr(living)>52*52)return Component.literal("Invalid enemy.");
        }
        s.bannerTeams.claim(color.getId(),player.getUUID());
        s.bannerTeams.issue(color.getId(),new BannerTeamBook.Command(mode,mode==BannerTeamBook.Order.FOLLOW?null:target.immutable(),
            mode==BannerTeamBook.Order.ATTACK?enemy:null,player.getUUID()));
        dirty(player.serverLevel());
        for(var row:s.settlers) if(Integer.valueOf(color.getId()).equals(s.bannerTeams.color(row.entityId))
            && player.serverLevel().getEntity(row.entityId) instanceof SettlerEntity settler && eligible(s,settler)) {
            settler.setTarget(null); settler.getNavigation().stop();
        }
        String verb=switch(mode) { case MOVE -> "Moving"; case HOLD -> "Holding"; case ATTACK -> "Attacking"; case FOLLOW -> "Following you"; };
        return Component.literal(color.getName()+" Team · "+verb+" · "+count(s,color.getId())+" settlers");
    }
    private static void dirty(ServerLevel level) { var data=SettlementSavedData.existing(level); if(data!=null)data.setDirty(); }
    @Nullable public static BannerTeamBook.Command active(SettlerEntity settler) {
        if(!(settler.level() instanceof ServerLevel))return null;
        // Live field orders (R/G commands) take precedence while they last.
        var field=FieldOrders.bannerView(settler); if(field!=null)return field;
        Settlement s=settler.settlement(); if(!eligible(s,settler))return null;
        Integer color=s.bannerTeams.color(settler.getUUID()); return color==null?null:s.bannerTeams.command(color);
    }
    @Nullable public static BlockPos anchor(SettlerEntity settler) {
        if(FieldOrders.controls(settler))return FieldOrders.anchor(settler);
        var command=active(settler); if(command==null)return null;
        if(command.order()!=BannerTeamBook.Order.FOLLOW)return command.target();
        ServerLevel level=(ServerLevel)settler.level(); var leader=level.getServer().getPlayerList().getPlayer(command.issuer());
        // Disconnect/death/dimension change holds locally, never walks away from the battle.
        return leader!=null && leader.isAlive() && leader.level()==level ? leader.blockPosition():settler.blockPosition();
    }
    public static boolean hostile(Settlement settlement,LivingEntity target) {
        return target instanceof Monster && target.isAlive() && !target.isRemoved()
            && (!(target instanceof RaiderEntity raider) || raider.settlementId()==null || settlement.id.equals(raider.settlementId()));
    }
    /** Arrival belongs to one saved command, not the settler's current combat position. */
    public static boolean arrived(SettlerEntity settler,BannerTeamBook.Command command) {
        if(command.order()==BannerTeamBook.Order.FOLLOW)return false;
        var data=settler.getPersistentData();
        if(data.hasUUID("HearthsteadBannerArrival")&&data.getUUID("HearthsteadBannerArrival").equals(command.id()))return true;
        if(command.target()!=null&&settler.blockPosition().distSqr(command.target())<=16){
            data.putUUID("HearthsteadBannerArrival",command.id());return true;
        }
        return false;
    }
    /** Additional boundary on all attack phases, including contact-time damage. */
    public static boolean allowsTarget(SettlerEntity settler,LivingEntity target) {
        if(FieldOrders.controls(settler))return FieldOrders.allowsTarget(settler,target);
        var command=active(settler); if(command==null)return true;
        if(target==null || !hostile(settler.settlement(),target) || !settler.canAttack(target))return false;
        BlockPos anchor=anchor(settler); if(anchor==null)return false;
        if(command.order()==BannerTeamBook.Order.FOLLOW)
            return settler.blockPosition().distSqr(anchor)<=36 && target.blockPosition().distSqr(anchor)<=36;
        boolean arrived=arrived(settler,command);
        if(command.order()==BannerTeamBook.Order.MOVE && !arrived)
            return settler.distanceToSqr(target)<=16;
        double radius=settler.getProfession()==com.hearthstead.entity.Profession.ARCHER?18:8;
        if(command.order()==BannerTeamBook.Order.ATTACK && !arrived)
            return settler.distanceToSqr(target)<=radius*radius;
        double movementRadius=settler.getProfession()==com.hearthstead.entity.Profession.ARCHER?8:10;
        return settler.blockPosition().distSqr(anchor)<=movementRadius*movementRadius
            && target.blockPosition().distSqr(anchor)<=radius*radius;
    }
    private BannerTeams() { }
}

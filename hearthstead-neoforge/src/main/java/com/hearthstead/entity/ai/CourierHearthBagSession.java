package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.*;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.block.PlaqueBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Untyped Hearth consolidation contact receipts; actual Hearth/bag remain the only inventories. */
public final class CourierHearthBagSession {
    private static final String KEY = "HearthsteadCourierHearthBag";
    /**
     * A completed Hearth lift normally needs no persistent state: the bag is
     * the custody record.  A failed warehouse leg is different.  Preserve its
     * exact, already-proved destination so a temporary path failure retries
     * the same physical bag rather than depositing it and starting a second
     * Hearth lift cycle.
     */
    private static final String DELIVERY_KEY = "HearthsteadCourierHearthDelivery";
    private final SettlerEntity actor;
    private long lastTick = Long.MIN_VALUE;
    private UUID completedWarehouse;
    private BlockPos completedTarget;
    private ListTag completedBag;
    public enum Result { RUNNING, COMPLETE, BLOCKED }
    /**
     * [economy] courierDepositBundle (plan/ECONOMY.md): how many items one
     * 40-tick lift cycle may move from a Hearth slot. Default 1, the original
     * contract. The courier sets it per slot (bundle, capped by the slot's
     * surplus and the bag's weight room); the exact-once receipt below is the
     * same one, with SourceCount-n and the bag image of an n-item unit.
     */
    private IntUnaryOperator unitLimit = slot -> 1;
    public CourierHearthBagSession(SettlerEntity actor) { this.actor = actor; }
    public void unitLimit(IntUnaryOperator limit) { this.unitLimit = limit == null ? slot -> 1 : limit; }
    public boolean active() { return actor.getPersistentData().contains(KEY); }
    private CompoundTag read() {
        var raw = actor.getPersistentData().get(KEY);
        if (!(raw instanceof CompoundTag t) || !t.contains("Version", Tag.TAG_INT) || t.getInt("Version") != 1
            || !t.hasUUID("Owner") || !actor.getUUID().equals(t.getUUID("Owner"))
            || !t.hasUUID("Settlement") || !t.hasUUID("Warehouse") || !t.hasUUID("Cycle")
            || !t.contains("Source", Tag.TAG_LONG) || !t.contains("Target", Tag.TAG_LONG)
            || !t.contains("Anchor", Tag.TAG_LONG) || !t.contains("Clock", Tag.TAG_INT)
            || t.getInt("Clock") < 0 || t.getInt("Clock") > 80
            || !t.contains("Slot", Tag.TAG_INT) || t.getInt("Slot") < 0
            || !t.contains("SourceCount", Tag.TAG_INT) || t.getInt("SourceCount") < 1
            || !t.contains("Unit", Tag.TAG_COMPOUND) || !t.contains("Bag", Tag.TAG_LIST)
            || !t.contains("Yaw", Tag.TAG_FLOAT) || !Float.isFinite(t.getFloat("Yaw"))) return null;
        for (String flag : List.of("Ack", "Pending", "Finishing"))
            if (!t.contains(flag, Tag.TAG_BYTE) || t.getByte(flag) < 0 || t.getByte(flag) > 1) return null;
        return t;
    }
    public UUID warehouse() { var t=read(); return t==null?null:t.getUUID("Warehouse"); }
    public BlockPos target() { var t=read(); return t==null?null:BlockPos.of(t.getLong("Target")); }
    public boolean ready(long now) { var t=read(); return t!=null && now>=t.getLong("PathRetryUntil"); }
    public boolean pathResting(long now) { var t=read(); return t!=null && now<t.getLong("PathRetryUntil"); }
    private CompoundTag delivery() {
        var raw=actor.getPersistentData().get(DELIVERY_KEY);
        if(!(raw instanceof CompoundTag t)||!t.contains("Version",Tag.TAG_INT)||t.getInt("Version")!=1
            ||!t.hasUUID("Owner")||!actor.getUUID().equals(t.getUUID("Owner"))
            ||!t.hasUUID("Settlement")||!t.hasUUID("Warehouse")||!t.contains("Target",Tag.TAG_LONG)
            ||!t.contains("Bag",Tag.TAG_LIST))return null;
        var settlement=actor.settlement();
        if(!(actor.level() instanceof ServerLevel level)||settlement==null
            ||!settlement.id.equals(t.getUUID("Settlement"))||actor.bag.isEmpty()) {
            actor.getPersistentData().remove(DELIVERY_KEY);return null;
        }
        BlockPos target=BlockPos.of(t.getLong("Target"));
        boolean liveTarget=level.hasChunkAt(target)
            &&(level.getBlockEntity(target) instanceof ChestBlockEntity
                ||level.getBlockEntity(target) instanceof BarrelBlockEntity)
            &&settlement.buildings.stream().anyMatch(b->b.id.equals(t.getUUID("Warehouse"))
                &&b.valid&&b.type==BuildingType.WAREHOUSE&&b.bounds!=null&&b.contains(target)
                &&level.hasChunkAt(b.plaquePos)
                &&level.getBlockEntity(b.plaquePos) instanceof PlaqueBlockEntity plaque
                &&(plaque.buildingId()==null||b.id.equals(plaque.buildingId())));
        if(!liveTarget||!bagImage(level,ItemStack.EMPTY).equals(t.get("Bag"))) {
            actor.getPersistentData().remove(DELIVERY_KEY);return null;
        }
        return t;
    }
    /** True only while this exact completed Hearth load still occupies the real Courier bag. */
    public boolean hasDeferredDelivery() { return delivery()!=null; }
    public UUID deferredWarehouse() { var t=delivery(); return t==null?null:t.getUUID("Warehouse"); }
    public BlockPos deferredTarget() { var t=delivery(); return t==null?null:BlockPos.of(t.getLong("Target")); }
    /**
     * Called only from the failed post-lift warehouse leg.  The local receipt
     * is deliberately converted to durable data here, not on every successful
     * lift, so the normal completed-lift path remains free of stale routing
     * state.
     */
    public boolean deferCompletedDelivery() {
        if(delivery()!=null)return true;
        if(!(actor.level() instanceof ServerLevel level)||actor.bag.isEmpty()
            ||completedWarehouse==null||completedTarget==null||completedBag==null
            ||!bagImage(level,ItemStack.EMPTY).equals(completedBag))return false;
        var settlement=actor.settlement();
        if(settlement==null)return false;
        CompoundTag t=new CompoundTag();t.putInt("Version",1);t.putUUID("Owner",actor.getUUID());
        t.putUUID("Settlement",settlement.id);t.putUUID("Warehouse",completedWarehouse);
        t.putLong("Target",completedTarget.asLong());t.put("Bag",completedBag.copy());
        actor.getPersistentData().put(DELIVERY_KEY,t);
        completedWarehouse=null;completedTarget=null;completedBag=null;return true;
    }
    private HearthBlockEntity hearth(ServerLevel level, CompoundTag t) {
        var s=actor.settlement();
        if (s==null || !s.id.equals(t.getUUID("Settlement"))) return null;
        BlockPos source=BlockPos.of(t.getLong("Source"));
        if (!source.equals(actor.getHearthPos()) || !level.hasChunkAt(source)
            || !(level.getBlockEntity(source) instanceof HearthBlockEntity h)
            || !s.id.equals(h.getSettlementId())) return null;
        BlockPos destination=BlockPos.of(t.getLong("Target"));
        if(!level.hasChunkAt(destination))return null;
        var destinationEntity=level.getBlockEntity(destination);
        if(!(destinationEntity instanceof ChestBlockEntity)&&!(destinationEntity instanceof BarrelBlockEntity))return null;
        boolean target=s.buildings.stream().anyMatch(b->b.id.equals(t.getUUID("Warehouse"))
            && b.valid && b.type==BuildingType.WAREHOUSE && b.bounds!=null && b.contains(destination)
            && level.hasChunkAt(b.plaquePos) && level.getBlockEntity(b.plaquePos) instanceof PlaqueBlockEntity plaque
            && (plaque.buildingId()==null || b.id.equals(plaque.buildingId())));
        return target?h:null;
    }
    private ItemStack unit(ServerLevel level, CompoundTag t) {
        ItemStack item=ItemStack.parseOptional(level.registryAccess(),t.getCompound("Unit"));
        return item.getCount()>=1&&item.getCount()<=item.getMaxStackSize()?item:ItemStack.EMPTY;
    }
    /** Canonical component/count observation, independent of slot repacking on entity load. */
    private ListTag bagImage(ServerLevel level, ItemStack extra) {
        List<ItemStack> groups=new ArrayList<>();
        for (int slot=0;slot<=actor.bag.getContainerSize();slot++) {
            ItemStack item=slot==actor.bag.getContainerSize()?extra:actor.bag.getItem(slot);
            if(item.isEmpty())continue;
            ItemStack group=groups.stream().filter(g->ItemStack.isSameItemSameComponents(g,item)).findFirst().orElse(null);
            if(group==null)groups.add(item.copy());else group.grow(item.getCount());
        }
        List<CompoundTag> tags=new ArrayList<>();
        for(ItemStack item:groups) {
            CompoundTag entry=new CompoundTag();
            entry.put("Item",item.copyWithCount(1).saveOptional(level.registryAccess()));
            entry.putInt("Count",item.getCount());tags.add(entry);
        }
        tags.sort(Comparator.comparing(CompoundTag::toString)); ListTag image=new ListTag();tags.forEach(image::add);return image;
    }
    private boolean chooseUnit(ServerLevel level, HearthBlockEntity h, CompoundTag t, IntPredicate allowed) {
        var inventory=h.getInventory();
        for(int slot=0;slot<inventory.getSlots();slot++) {
            ItemStack live=inventory.getStackInSlot(slot);
            if(live.isEmpty() || !allowed.test(slot) || bagSlot(live.copyWithCount(1))<0)continue;
            int n=Math.max(1,Math.min(live.getCount(),unitLimit.applyAsInt(slot)));
            while(n>1&&bagSlot(live.copyWithCount(n))<0)n--;
            t.putInt("Slot",slot);t.putInt("SourceCount",live.getCount());
            t.put("Unit",live.copyWithCount(n).saveOptional(level.registryAccess()));
            t.putBoolean("Ack",false);t.putBoolean("Pending",false);t.putUUID("Cycle",UUID.randomUUID());return true;
        }
        return false;
    }
    public boolean begin(ServerLevel level, UUID warehouse, BlockPos target, IntPredicate allowed) {
        // A delivered bag is empty by the time a new Hearth cycle begins.
        // Remove any deferred-route receipt before looking for new source work.
        if(actor.bag.isEmpty())actor.getPersistentData().remove(DELIVERY_KEY);
        if(active())return true;
        completedWarehouse=null;completedTarget=null;completedBag=null;
        var s=actor.settlement(); HearthBlockEntity h=actor.hearth();
        if(s==null||h==null||warehouse==null||target==null||!actor.bag.isEmpty())return false;
        BlockPos source=h.getBlockPos(),anchor=chooseAnchor(level,source);
        if(anchor==null)return false;
        CompoundTag t=new CompoundTag();t.putInt("Version",1);t.putUUID("Owner",actor.getUUID());
        t.putUUID("Settlement",s.id);t.putUUID("Warehouse",warehouse);t.putLong("Target",target.asLong());
        t.putLong("Source",source.asLong());t.putLong("Anchor",anchor.asLong());t.putFloat("Yaw",actor.getYRot());
        t.putInt("Clock",0);t.putBoolean("Finishing",false);t.put("Bag",bagImage(level,ItemStack.EMPTY));
        if(!chooseUnit(level,h,t,allowed))return false;
        if(hearth(level,t)==null)return false;
        actor.getPersistentData().put(KEY,t);return true;
    }
    private int bagSlot(ItemStack item) {
        int empty=-1;
        for(int i=0;i<actor.bag.getContainerSize();i++) {
            ItemStack live=actor.bag.getItem(i);
            if(live.isEmpty()) {if(empty<0)empty=i;}
            else if(ItemStack.isSameItemSameComponents(live,item)&&live.getCount()+item.getCount()<=live.getMaxStackSize())return i;
        }
        return empty;
    }
    public void interrupt() {
        var v=actor.bagTransferPresentation();if(v.active()&&v.sourcePickup())actor.clearBagTransferPresentation(v.transferId());
    }
    /** The Hearth is not a Container, but source contact still needs real reach and a clear ray. */
    static boolean hasHearthContact(ServerLevel level, SettlerEntity actor, BlockPos source) {
        if (level == null || actor == null || source == null) return false;
        Vec3 end=Vec3.atCenterOf(source);
        if(actor.distanceToSqr(end)>6.25)return false;
        var hit=level.clip(new ClipContext(actor.getEyePosition(),end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor));
        return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(source);
    }
    /** One reachable side cell, never the Hearth's collision surface itself. */
    static BlockPos hearthContactAnchor(ServerLevel level, SettlerEntity actor, BlockPos source) {
        if (level == null || actor == null || source == null) return null;
        Set<BlockPos> candidates=new LinkedHashSet<>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            if(dx==0&&dz==0)continue;BlockPos p=source.offset(dx,0,dz);
            if(level.hasChunkAt(p)&&level.hasChunkAt(p.below())&&level.hasChunkAt(p.above())
                &&level.getFluidState(p).isEmpty()&&level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
                &&level.noCollision(actor,actor.getBoundingBox().move(Vec3.atBottomCenterOf(p).subtract(actor.position()))))candidates.add(p);
        }
        if(candidates.contains(actor.blockPosition()))return actor.blockPosition().immutable();
        if(candidates.isEmpty())return null;var path=actor.getNavigation().createPath(candidates,0);
        return path!=null&&path.canReach()&&candidates.contains(path.getTarget())?path.getTarget().immutable():null;
    }
    private boolean atSource(ServerLevel level, BlockPos source) {
        return hasHearthContact(level, actor, source);
    }
    private BlockPos chooseAnchor(ServerLevel level, BlockPos source) {
        return hearthContactAnchor(level, actor, source);
    }
    public Result tick(ServerLevel level, IntPredicate allowed) {
        if(lastTick==level.getGameTime())return Result.RUNNING;lastTick=level.getGameTime();
        var t=read();var h=t==null?null:hearth(level,t);
        if(h==null){interrupt();return Result.BLOCKED;}
        ItemStack unit=unit(level,t);if(unit.isEmpty()){interrupt();return Result.BLOCKED;}
        var inventory=h.getInventory();int slot=t.getInt("Slot");if(slot>=inventory.getSlots()){interrupt();return Result.BLOCKED;}
        boolean ack=t.getBoolean("Ack");int clock=t.getInt("Clock");
        if(t.getBoolean("Pending")) {
            ItemStack live=inventory.getStackInSlot(slot);
            boolean sourceAfter=live.isEmpty()?t.getInt("SourceCount")==unit.getCount():
                ItemStack.isSameItemSameComponents(live,unit)&&live.getCount()==t.getInt("SourceCount")-unit.getCount();
            if(t.contains("AfterBag",Tag.TAG_LIST)&&sourceAfter&&bagImage(level,ItemStack.EMPTY).equals(t.get("AfterBag"))) {
                t.put("Bag",t.get("AfterBag").copy());t.putBoolean("Ack",true);ack=true;
                t.putInt("Clock",48);clock=48;t.putBoolean("Pending",false);
            } else if(bagImage(level,ItemStack.EMPTY).equals(t.get("Bag"))
                &&ItemStack.isSameItemSameComponents(live,unit)&&live.getCount()==t.getInt("SourceCount"))t.putBoolean("Pending",false);
            else {interrupt();return Result.BLOCKED;}
        }
        if(!bagImage(level,ItemStack.EMPTY).equals(t.get("Bag"))){interrupt();return Result.BLOCKED;}
        BlockPos anchor=BlockPos.of(t.getLong("Anchor")),source=h.getBlockPos();
        if(level.getGameTime()<t.getLong("PathRetryUntil"))return Result.BLOCKED;
        if(t.getLong("PathRetryUntil")>0){t.putLong("PathRetryUntil",0);t.putInt("RouteTicks",0);t.putInt("Losses",0);t.putBoolean("Away",false);}
        Vec3 centre=Vec3.atBottomCenterOf(anchor),delta=centre.subtract(actor.position());
        if(delta.lengthSqr()>.16 || !atSource(level,source)) {
            interrupt();actor.setActivity(SettlerActivity.TRAVELING);
            int ticks=t.getInt("RouteTicks")+1;t.putInt("RouteTicks",ticks);
            if(!t.getBoolean("Away"))t.putInt("Losses",t.getInt("Losses")+1);t.putBoolean("Away",true);
            if(ticks>240||t.getInt("Losses")>32){t.putLong("PathRetryUntil",level.getGameTime()+CourierWorkGoal.FIRST_REST_TICKS);return Result.BLOCKED;}
            if(delta.lengthSqr()<=2.25&&level.hasChunkAt(anchor)&&level.hasChunkAt(anchor.below())
                &&level.getBlockState(anchor.below()).isFaceSturdy(level,anchor.below(),Direction.UP)
                &&level.getFluidState(anchor).isEmpty()&&level.noCollision(actor,actor.getBoundingBox().expandTowards(delta))
                &&level.clip(new ClipContext(actor.getEyePosition(),centre.add(0,actor.getEyeHeight(),0),
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()==HitResult.Type.MISS) {
                actor.getNavigation().stop();actor.getMoveControl().setWantedPosition(centre.x,centre.y,centre.z,.65);
            } else if(ticks%20==1&&level.hasChunkAt(anchor)) {
                var path=actor.getNavigation().createPath(anchor,0);if(path!=null&&path.canReach())actor.getNavigation().moveTo(path,.9);
            }
            return Result.RUNNING;
        }
        t.putInt("RouteTicks",0);t.putBoolean("Away",false);actor.getNavigation().stop();
        if(!ack&&!t.getBoolean("Finishing")) {
            ItemStack live=inventory.getStackInSlot(slot);
            if(!ItemStack.isSameItemSameComponents(live,unit)||live.getCount()!=t.getInt("SourceCount")||!allowed.test(slot)) {
                interrupt();
                if(chooseUnit(level,h,t,allowed)){t.putInt("Clock",clock<24?clock:24);return Result.RUNNING;}
                if(clock<12 && actor.bag.isEmpty()) {
                    actor.getPersistentData().remove(KEY);return Result.COMPLETE;
                }
                t.putBoolean("Finishing",true);t.putInt("Clock",64);clock=64;
            }
        }
        actor.setActivity(clock<24?SettlerActivity.CARRYING:SettlerActivity.SORTING);
        actor.getLookControl().setLookAt(source.getX()+.5,source.getY()+.8,source.getZ()+.5);
        if(clock>=12)actor.placeWorkContainer(WorkContainerKind.SACK,anchor);
        int next=clock+1;
        if(next==12){actor.placeWorkContainer(WorkContainerKind.SACK,anchor);WorkSoundSync.play(level, anchor, ModSounds.BAG_DOWN.get(), .5F, 1F);}
        if(next==48&&!ack&&!t.getBoolean("Finishing")) {
            int bagSlot=bagSlot(unit);if(bagSlot<0||!allowed.test(slot)){interrupt();return Result.BLOCKED;}
            t.put("AfterBag",bagImage(level,unit));t.putBoolean("Pending",true);
            ItemStack removed=inventory.extractItem(slot,unit.getCount(),false);
            if(removed.getCount()!=unit.getCount()||!ItemStack.isSameItemSameComponents(removed,unit)) {
                if(!removed.isEmpty())inventory.insertItem(slot,removed,false);
                t.putBoolean("Pending",false);interrupt();return Result.BLOCKED;
            }
            ItemStack bagLive=actor.bag.getItem(bagSlot);
            if(bagLive.isEmpty())actor.bag.setItem(bagSlot,removed);
            else {bagLive.grow(removed.getCount());actor.bag.setItem(bagSlot,bagLive);}
            h.setChanged();t.put("Bag",bagImage(level,ItemStack.EMPTY));
            t.putBoolean("Pending",false);t.putBoolean("Ack",true);ack=true;
            WorkSoundSync.play(level, anchor, ModSounds.BAG_STOW.get(), .5F, 1F);
        }
        if(next>=64&&ack&&!t.getBoolean("Finishing")) {
            if(chooseUnit(level,h,t,allowed)){t.putInt("Clock",24);publish(level,t,24,false);return Result.RUNNING;}
            t.putBoolean("Finishing",true);
        }
        if(next>=80&&t.getBoolean("Finishing")) {
            WorkSoundSync.play(level, anchor, ModSounds.BAG_UP.get(), .5F, 1F);
            completedWarehouse=t.getUUID("Warehouse");completedTarget=BlockPos.of(t.getLong("Target"));
            completedBag=bagImage(level,ItemStack.EMPTY).copy();
            interrupt();actor.clearWorkContainer();actor.getPersistentData().remove(KEY);return Result.COMPLETE;
        }
        t.putInt("Clock",next);publish(level,t,next,ack||t.getBoolean("Finishing"));return Result.RUNNING;
    }
    private void publish(ServerLevel level,CompoundTag t,int clock,boolean ack) {
        actor.publishBagTransferPresentation(new BagTransferPresentation(t.getUUID("Cycle"),BlockPos.of(t.getLong("Anchor")),
            t.getFloat("Yaw"),BlockPos.of(t.getLong("Source")),clock,ack,unit(level,t),true));
    }
}

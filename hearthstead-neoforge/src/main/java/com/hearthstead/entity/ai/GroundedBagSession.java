package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.WorkContainerKind;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.work.FarmWorkApproach;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

/** Shared field-container lifecycle. Only intent/clock is saved here; items remain world/hand/bag owned. */
public final class GroundedBagSession {
    private static final String KEY="HearthsteadGroundedFieldBag";
    private static final String EXPECTED_DROPS="ExpectedDrops";
    private static final String DROP_ID="Id", DROP_STACK="Stack", DROP_POS="Pos", DROP_HELD="Held";
    public enum Result { WAITING, HARVEST_READY, COLLECTED, NEEDS_UNLOAD, BLOCKED }
    private enum Phase { RETURN, LOWER, HARVEST, COLLECT, PICK, STOW, LIFT, AWAY, FINISHED }
    private final SettlerEntity actor;
    private final GroundCollectionSession collection;
    private final int pickupBatchLimit;
    private int pathTicks, failures;
    public GroundedBagSession(SettlerEntity actor,GroundCollectionSession collection) {
        this(actor, collection, 1);
    }
    /** Batch pickup is explicit so the shared lumber/hunter routes remain exact-one. */
    public GroundedBagSession(SettlerEntity actor,GroundCollectionSession collection,int pickupBatchLimit) {
        if(pickupBatchLimit<1)throw new IllegalArgumentException("pickupBatchLimit must be positive");
        this.actor=actor; this.collection=collection; this.pickupBatchLimit=pickupBatchLimit;
    }
    public boolean active() { return read()!=null; }
    public boolean corrupt() { return actor.getPersistentData().contains(KEY) && read()==null; }
    private CompoundTag read() {
        var data=actor.getPersistentData();
        if(!(data.get(KEY) instanceof CompoundTag t) || t.getInt("Version")!=1
            || !t.hasUUID("Owner") || !actor.getUUID().equals(t.getUUID("Owner"))
            || !t.contains("Anchor",Tag.TAG_LONG) || !t.contains("Crop",Tag.TAG_LONG)
            || !t.contains("CropId",Tag.TAG_STRING)
            || net.minecraft.resources.ResourceLocation.tryParse(t.getString("CropId"))==null
            || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                net.minecraft.resources.ResourceLocation.tryParse(t.getString("CropId")))) return null;
        try { Phase.valueOf(t.getString("Phase")); } catch(IllegalArgumentException invalid) { return null; }
        return t;
    }
    public BlockPos anchor() { var t=read(); return t==null?null:BlockPos.of(t.getLong("Anchor")); }
    public BlockPos crop() { var t=read(); return t==null?null:BlockPos.of(t.getLong("Crop")); }
    public String cropId() { var t=read(); return t==null?"":t.getString("CropId"); }
    public boolean harvested() { var t=read(); return t!=null&&t.getBoolean("Harvested"); }
    public boolean away() { var t=read(); return t!=null&&phase(t)==Phase.AWAY; }
    public void begin(BlockPos crop,String cropId) {
        if(active()) throw new IllegalStateException("Existing field bag session");
        CompoundTag t=new CompoundTag();
        t.putInt("Version",1); t.putUUID("Owner",actor.getUUID());
        // Farmland feet are y+15/16: the anchor is the standing cell, not the soil block.
        BlockPos standing=BlockPos.containing(actor.getX(),Math.ceil(actor.getY()-1.0E-4D),actor.getZ());
        t.putLong("Anchor",standing.asLong()); t.putLong("Crop",crop.asLong());
        t.putString("CropId",cropId); t.putBoolean("Harvested",false); t.putBoolean("LowerPending",true);
        actor.getPersistentData().put(KEY,t); lower(t);
    }
    public void markHarvested() { var t=read(); if(t!=null) t.putBoolean("Harvested",true); }
    /** Exact physical harvest intent; this is never an item copy or authority grant. */
    public void recordExpectedDrops(ServerLevel level, List<UUID> ids, List<ItemStack> stacks) {
        var t=read();
        if(t==null || ids==null || stacks==null || ids.size()!=stacks.size() || ids.isEmpty())
            throw new IllegalArgumentException("Exact field output rows required");
        ListTag rows=new ListTag();
        for(int i=0;i<ids.size();i++) {
            UUID id=ids.get(i); ItemStack stack=stacks.get(i);
            if(id==null || stack==null || stack.isEmpty()) throw new IllegalArgumentException("Exact field output row invalid");
            CompoundTag row=new CompoundTag(); row.putUUID(DROP_ID,id); row.putLong(DROP_POS,crop().asLong());
            row.put(DROP_STACK,stack.copy().save(level.registryAccess())); rows.add(row);
        }
        t.put(EXPECTED_DROPS,rows);
    }
    public void clearExpectedDrops() { var t=read(); if(t!=null)t.remove(EXPECTED_DROPS); }
    public void collectAfterHarvest() { var t=read(); if(t!=null) set(t,Phase.RETURN); }
    public void cancelUnharvested() { var t=read(); if(t!=null) {t.putBoolean("Cancelled",true);set(t,Phase.RETURN);} }
    public void clear() { actor.getPersistentData().remove(KEY); }
    /** Preserve an interrupted physical hand contact as a new exact world row. */
    public void suspend(ServerLevel level) {
        var t=read(); if(t==null) { collection.clearSelection(); return; }
        ItemStack carried=actor.getOffhandItem().copy();
        UUID returned=collection.returnCarriedToWorldId(level,actor.blockPosition(),true);
        if(returned!=null)replaceHeldExpectedDrop(level,t,carried,returned,actor.blockPosition());
        collection.clearSelection();
    }
    private record ExpectedDrop(UUID id, ItemStack stack, BlockPos last, boolean held) {}
    private List<ExpectedDrop> expectedDrops(ServerLevel level, CompoundTag t) {
        if(!t.contains(EXPECTED_DROPS,Tag.TAG_LIST))return List.of();
        List<ExpectedDrop> result=new ArrayList<>(); ListTag rows=t.getList(EXPECTED_DROPS,Tag.TAG_COMPOUND);
        for(int i=0;i<rows.size();i++) {
            CompoundTag row=rows.getCompound(i);
            if(!row.contains(DROP_STACK,Tag.TAG_COMPOUND))continue;
            ItemStack stack=ItemStack.parseOptional(level.registryAccess(),row.getCompound(DROP_STACK));
            boolean held=row.getBoolean(DROP_HELD);
            if(!stack.isEmpty() && (held || row.hasUUID(DROP_ID)))
                result.add(new ExpectedDrop(held?null:row.getUUID(DROP_ID),stack,
                    row.contains(DROP_POS,Tag.TAG_LONG)?BlockPos.of(row.getLong(DROP_POS)):crop(),held));
        }
        return result;
    }
    private boolean hasExpectedDrops(ServerLevel level, CompoundTag t) { return !expectedDrops(level,t).isEmpty(); }
    /**
     * Keep unloaded/pending intent, but discard rows only after their loaded
     * physical source is proven gone, altered, or no longer current-authorized.
     */
    private void reclaimExpectedDrops(ServerLevel level, CompoundTag t, Predicate<ItemEntity> allowed) {
        if(!t.contains(EXPECTED_DROPS,Tag.TAG_LIST))return;
        ListTag rows=t.getList(EXPECTED_DROPS,Tag.TAG_COMPOUND);
        for(int i=rows.size()-1;i>=0;i--) {
            CompoundTag row=rows.getCompound(i);
            ItemStack expected=row.contains(DROP_STACK,Tag.TAG_COMPOUND)
                ? ItemStack.parseOptional(level.registryAccess(),row.getCompound(DROP_STACK)) : ItemStack.EMPTY;
            if(expected.isEmpty()) { rows.remove(i); continue; }
            if(row.getBoolean(DROP_HELD)) {
                if(!collection.ownsOffhandItem() || !ItemStack.matches(expected,actor.getOffhandItem())) rows.remove(i);
                continue;
            }
            if(!row.hasUUID(DROP_ID)) { rows.remove(i); continue; }
            UUID id=row.getUUID(DROP_ID); Entity entity=level.getEntity(id);
            if(entity instanceof ItemEntity item && item.isAlive()) {
                if(!ItemStack.matches(expected,item.getItem()) || !allowed.test(item)) { rows.remove(i); continue; }
                row.putLong(DROP_POS,item.blockPosition().asLong());
                collection.reclaimExactExpiredFieldOutput(level,id,expected,allowed);
                continue;
            }
            BlockPos last=row.contains(DROP_POS,Tag.TAG_LONG)?BlockPos.of(row.getLong(DROP_POS)):crop();
            if(level.hasChunkAt(last) && !DeferredItemMaterializationSavedData.get(level)
                    .pendingForTarget(id,actor.getUUID())) rows.remove(i);
        }
        if(rows.isEmpty())t.remove(EXPECTED_DROPS);
    }
    private int worldExpectedIndex(ListTag rows, UUID id) {
        for(int i=0;i<rows.size();i++)if(!rows.getCompound(i).getBoolean(DROP_HELD)
            && rows.getCompound(i).hasUUID(DROP_ID) && id.equals(rows.getCompound(i).getUUID(DROP_ID)))return i;
        return -1;
    }
    private int heldExpectedIndex(ServerLevel level, ListTag rows, ItemStack stack) {
        for(int i=0;i<rows.size();i++) {
            CompoundTag row=rows.getCompound(i);
            if(row.getBoolean(DROP_HELD) && row.contains(DROP_STACK,Tag.TAG_COMPOUND)
                && ItemStack.matches(stack,ItemStack.parseOptional(level.registryAccess(),row.getCompound(DROP_STACK))))return i;
        }
        return -1;
    }
    /** Split exact intent at the real PICK contact: world remainder and hand are separate authorities. */
    private void recordPickup(ServerLevel level, CompoundTag t, @Nullable UUID id,
                              ItemStack before, ItemStack carried) {
        if(id==null || before.isEmpty() || carried.isEmpty() || !t.contains(EXPECTED_DROPS,Tag.TAG_LIST))return;
        ListTag rows=t.getList(EXPECTED_DROPS,Tag.TAG_COMPOUND); int index=worldExpectedIndex(rows,id);
        if(index<0)return;
        CompoundTag world=rows.getCompound(index);
        ItemStack expected=ItemStack.parseOptional(level.registryAccess(),world.getCompound(DROP_STACK));
        if(!ItemStack.matches(expected,before))return;
        ItemStack remainder=before.copy(); remainder.shrink(carried.getCount());
        if(remainder.isEmpty()) { world.remove(DROP_ID); world.putBoolean(DROP_HELD,true); }
        else world.put(DROP_STACK,remainder.save(level.registryAccess()));
        CompoundTag held=new CompoundTag(); held.putBoolean(DROP_HELD,true);
        held.put(DROP_STACK,carried.copy().save(level.registryAccess())); rows.add(held);
        if(remainder.isEmpty())rows.remove(index); // one held row, never duplicate it
    }
    private void removeHeldExpectedDrop(ServerLevel level, CompoundTag t, ItemStack stack) {
        if(!t.contains(EXPECTED_DROPS,Tag.TAG_LIST))return;
        ListTag rows=t.getList(EXPECTED_DROPS,Tag.TAG_COMPOUND); int index=heldExpectedIndex(level,rows,stack);
        if(index>=0)rows.remove(index); if(rows.isEmpty())t.remove(EXPECTED_DROPS);
    }
    private void replaceHeldExpectedDrop(ServerLevel level, CompoundTag t, ItemStack stack, UUID id, BlockPos pos) {
        if(!t.contains(EXPECTED_DROPS,Tag.TAG_LIST))return;
        ListTag rows=t.getList(EXPECTED_DROPS,Tag.TAG_COMPOUND); int index=heldExpectedIndex(level,rows,stack);
        if(index>=0) { CompoundTag row=rows.getCompound(index); row.remove(DROP_HELD); row.putUUID(DROP_ID,id); row.putLong(DROP_POS,pos.asLong()); }
    }
    /** Replay only non-transfer anticipation. Actual hand/bag state decides the next contact. */
    public void resume(ServerLevel level,Predicate<BlockPos> allowed) {
        var t=read(); if(t==null)return;
        collection.restorePersistedIndex(level,allowed,allowed);
        adoptCarried(); collection.clearSelection();
        pathTicks=0; failures=0;
        if(phase(t)!=Phase.AWAY && phase(t)!=Phase.FINISHED) set(t,Phase.RETURN);
    }
    public void returnFromStorage() { var t=read(); if(t!=null&&phase(t)==Phase.AWAY)set(t,Phase.RETURN); }
    private Phase phase(CompoundTag t) { return Phase.valueOf(t.getString("Phase")); }
    private void set(CompoundTag t,Phase phase) { t.putString("Phase",phase.name());t.putInt("Clock",0);pathTicks=0;failures=0; }
    private void lower(CompoundTag t) {
        set(t,Phase.LOWER);
        actor.getNavigation().stop();actor.setActivity(SettlerActivity.SORTING);
        actor.placeWorkContainer(WorkContainerKind.SACK,anchor());actor.triggerWorkContainerDown();
    }
    private boolean atAnchor(ServerLevel level,BlockPos anchor) {
        Vec3 end=Vec3.atBottomCenterOf(anchor);
        return actor.position().distanceToSqr(end)<=2.25D
            && level.clip(new ClipContext(actor.getEyePosition(),end.add(0,actor.getEyeHeight(),0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()==HitResult.Type.MISS;
    }
    /** Move to a legal contact stand without moving the saved sack anchor. */
    private boolean approachAnchor(ServerLevel level, BlockPos anchor, Building farmhouse) {
        if (--pathTicks > 0) return true;
        pathTicks = 40;
        // A storage return can resume inside this Farmer's raised, closed home.
        // Let the existing employer-boundary door route leave first; this is
        // not an anchor recovery failure and does not move the saved sack.
        if (FarmWorkApproach.stageExitFromOwnFarmhouse(level, actor, farmhouse, anchor, 1.0D)) {
            return true;
        }
        if (++failures > 6) return false;
        Vec3 target = Vec3.atBottomCenterOf(anchor);
        java.util.Set<BlockPos> candidates = new java.util.LinkedHashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos stand = anchor.offset(dx, dy, dz);
                if (!isFieldStandable(level, stand)) continue;
                Vec3 feet = Vec3.atBottomCenterOf(stand);
                if (feet.distanceToSqr(target) <= 2.25D
                    && level.clip(new ClipContext(feet.add(0, actor.getEyeHeight(), 0),
                        target.add(0, actor.getEyeHeight(), 0), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, actor)).getType() == HitResult.Type.MISS) {
                    candidates.add(stand.immutable());
                }
            }
        }
        // Vanilla can finish a waypoint slightly short of its center. The
        // current cell has already failed the live contact check, so choosing
        // it again would spend the retry budget without moving closer.
        candidates.remove(BlockPos.containing(actor.getX(), Math.ceil(actor.getY() - 1.0E-4D), actor.getZ()));
        // Every remaining stand still satisfies the existing range and ray
        // checks; the saved sack anchor never moves.
        if (!candidates.isEmpty()) {
            var path = actor.getNavigation().createPath(candidates, 0);
            if (path != null && path.canReach() && candidates.contains(path.getTarget())) {
                actor.getNavigation().moveTo(path, 1.0D);
            }
        }
        return true;
    }
    /** Path attempts (40 ticks apart) before one unreachable drop is released. */
    static final int MAX_ITEM_APPROACH_ATTEMPTS = 6;
    static boolean itemApproachBudgetExhausted(int attempts) {
        return attempts > MAX_ITEM_APPROACH_ATTEMPTS;
    }
    /** True while the item is in pickup contact or still has approach budget left. */
    private boolean canApproachItem(ServerLevel level, ItemEntity item) {
        if(actor.distanceToSqr(item)<=4.0D && collection.hasClearPickupLine(level,item)) return true;
        return approachItem(level,item);
    }
    /** Drop only the saved world intent row for a released item; the entity stays in the world. */
    private void forgetExpectedWorldDrop(CompoundTag t, @Nullable UUID id) {
        if(id==null || !t.contains(EXPECTED_DROPS,Tag.TAG_LIST))return;
        ListTag rows=t.getList(EXPECTED_DROPS,Tag.TAG_COMPOUND); int index=worldExpectedIndex(rows,id);
        if(index>=0)rows.remove(index); if(rows.isEmpty())t.remove(EXPECTED_DROPS);
    }
    /** Same bounded clear-side strategy as Lumber collection; item coordinates need not be occupiable. */
    private boolean approachItem(ServerLevel level, ItemEntity item) {
        if (--pathTicks > 0) return true;
        pathTicks=40;
        if (itemApproachBudgetExhausted(++failures)) return false;
        java.util.Set<BlockPos> candidates=new java.util.LinkedHashSet<>();
        BlockPos itemBlock=item.blockPosition();
        for(int dy=-1;dy<=1;dy++) {
            BlockPos centre=itemBlock.offset(0,dy,0);
            addItemApproach(level,item,centre,candidates);
            for(net.minecraft.core.Direction direction:net.minecraft.core.Direction.Plane.HORIZONTAL)
                addItemApproach(level,item,centre.relative(direction),candidates);
        }
        // Do not repeatedly select the node already reached with a failed live pickup ray.
        candidates.remove(BlockPos.containing(actor.getX(),Math.ceil(actor.getY()-1.0E-4D),actor.getZ()));
        if(!candidates.isEmpty()) {
            var path=actor.getNavigation().createPath(candidates,0);
            if(path!=null&&path.canReach()&&candidates.contains(path.getTarget()))
                actor.getNavigation().moveTo(path,1.0D);
        }
        return true;
    }
    private void addItemApproach(ServerLevel level,ItemEntity item,BlockPos pos,java.util.Set<BlockPos> out) {
        if (!isFieldStandable(level, pos)) return;
        Vec3 feet=Vec3.atBottomCenterOf(pos);
        if(feet.distanceToSqr(item.position())<=4.0D
            &&collection.hasClearPickupLineFrom(level,feet.add(0,actor.getEyeHeight(),0),item))out.add(pos.immutable());
    }
    private boolean isFieldStandable(ServerLevel level, BlockPos pos) {
        if(!level.hasChunkAt(pos)||!level.hasChunkAt(pos.above())||!level.hasChunkAt(pos.below())
            ||!level.getFluidState(pos).isEmpty()||!level.getFluidState(pos.below()).isEmpty()
            ||!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()
            ||!level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty())return false;
        var floor=level.getBlockState(pos.below());
        return floor.isFaceSturdy(level,pos.below(),net.minecraft.core.Direction.UP)
            ||floor.is(net.minecraft.world.level.block.Blocks.FARMLAND);
    }
    /** Failure-only observer; no path queries, item mutation or ownership reset. */
    public String diagnostic(ServerLevel level) {
        var t=read();
        var selected=collection.selectedDropId()==null?null:level.getEntity(collection.selectedDropId());
        ItemEntity item=selected instanceof ItemEntity drop?drop:null;
        return "phase="+(t==null?"invalid":phase(t))+",anchor="+anchor()+",actor="+actor.position()
            +",tracked="+collection.trackedCount()+",selected="+collection.selectedDropId()
            +",item="+(item==null?"absent":item.position())
            +",ray="+(item!=null&&collection.hasClearPickupLine(level,item))+",attempts="+failures;
    }
    public Result tick(ServerLevel level,Predicate<ItemEntity> allowed,Building farmhouse) {
        var t=read(); if(t==null)return Result.BLOCKED;
        BlockPos anchor=anchor();
        if(!level.isLoaded(anchor))return Result.BLOCKED;
        // Re-adopt a persisted hand before examining held expected rows.
        adoptCarried();
        // Generic leases stay finite; only this persisted exact Farmer intent may re-authorize.
        reclaimExpectedDrops(level,t,allowed);
        collection.heartbeat(level);
        Phase p=phase(t);
        if(p==Phase.AWAY)return Result.NEEDS_UNLOAD;
        if(p==Phase.FINISHED)return Result.COLLECTED;
        if(p==Phase.RETURN) {
            actor.setActivity(SettlerActivity.COLLECTING_ITEMS);
            if(!atAnchor(level,anchor))return approachAnchor(level,anchor,farmhouse)?Result.WAITING:Result.BLOCKED;
            actor.getNavigation().stop();
            if(actor.placedWorkContainerPos()==null || t.getBoolean("LowerPending")) { lower(t); return Result.WAITING; }
            if(!anchor.equals(actor.placedWorkContainerPos())) return Result.BLOCKED;
            if(collection.ownsOffhandItem()) { set(t,Phase.STOW);actor.triggerWorkContainerStow(); }
            else if(t.getBoolean("Cancelled") || t.getBoolean("LiftOnReturn")) { t.putBoolean("LiftOnReturn",false);set(t,Phase.LIFT);actor.triggerWorkContainerUp(); }
            else set(t,t.getBoolean("Harvested")?Phase.COLLECT:Phase.HARVEST);
            return Result.WAITING;
        }
        if(p==Phase.HARVEST)return Result.HARVEST_READY;
        if(p==Phase.COLLECT) {
            if(collection.ownsOffhandItem()) {set(t,Phase.RETURN);return Result.WAITING;}
            if(!collection.hasCapacity()) {t.putBoolean("NeedUnload",true);set(t,Phase.RETURN);t.putBoolean("LiftOnReturn",true);return Result.WAITING;}
            ItemEntity item=collection.nearestLoaded(level,anchor);
            if(item==null) {
                if(collection.hasTrackedDrops()||hasExpectedDrops(level,t))return Result.BLOCKED;
                if(!atAnchor(level,anchor)) {set(t,Phase.RETURN);t.putBoolean("LiftOnReturn",true);return Result.WAITING;}
                set(t,Phase.LIFT);actor.triggerWorkContainerUp();return Result.WAITING;
            }
            collection.select(item);
            // An owned drop that rolled out of the field's authority, or that
            // no legal stand can reach within the bounded approach budget, used
            // to return BLOCKED. The goal then retried the same item every
            // 100 ticks forever (farm_ground_bag_recovery_blocked loop) and the
            // Farmer never finished the bag. Release just that item: it stays
            // a normal, pickupable world ItemEntity (never deleted) and the
            // sack continues with the remaining drops or lifts.
            if(!allowed.test(item) || !canApproachItem(level,item)) {
                UUID released=item.getUUID();
                collection.releaseSelected(level);
                forgetExpectedWorldDrop(t,released);
                pathTicks=0; failures=0;
                return Result.WAITING;
            }
            actor.setActivity(SettlerActivity.COLLECTING_ITEMS);
            actor.getLookControl().setLookAt(item,30,30);
            if(actor.distanceToSqr(item)<=4.0D && collection.hasClearPickupLine(level,item)) {
                actor.getNavigation().stop();set(t,Phase.PICK);actor.triggerGroundItemPickup();
            }
            return Result.WAITING;
        }
        if((p==Phase.LOWER||p==Phase.STOW||p==Phase.LIFT)&&!atAnchor(level,anchor)) {
            set(t,Phase.RETURN);return Result.WAITING;
        }
        actor.getNavigation().stop(); actor.setActivity(SettlerActivity.SORTING);
        actor.getLookControl().setLookAt(anchor.getX()+.5,anchor.getY()+.3,anchor.getZ()+.5);
        int clock=t.getInt("Clock")+1;t.putInt("Clock",clock);
        if(p==Phase.LOWER) {
            if(clock==20)WorkSoundSync.play(level, anchor, ModSounds.BAG_DOWN.get(), .5F, 1F);
            if(clock>=28) {
                t.putBoolean("LowerPending",false);
                if(t.getBoolean("Cancelled")) {set(t,Phase.LIFT);actor.triggerWorkContainerUp();}
                else set(t,t.getBoolean("Harvested")?Phase.COLLECT:Phase.HARVEST);
            }
        } else if(p==Phase.PICK) {
            ItemEntity item=collection.selected(level);
            if(item!=null)actor.getLookControl().setLookAt(item,30,30);
            if(clock==12 && item!=null && allowed.test(item)) {
                UUID sourceId=item.getUUID(); ItemStack sourceBefore=item.getItem().copy();
                GroundCollectionSession.PickupResult result=pickupBatchLimit==1
                    ? collection.takeOneToOffhand(level,4.0D)
                    : collection.takeUpToOffhand(level,4.0D,pickupBatchLimit);
                if(result==GroundCollectionSession.PickupResult.PICKED)
                    recordPickup(level,t,sourceId,sourceBefore,actor.getOffhandItem());
            }
            if(clock>=20)set(t,collection.ownsOffhandItem()?Phase.RETURN:Phase.COLLECT);
        } else if(p==Phase.STOW) {
            if(clock==12) {
                ItemStack carried=actor.getOffhandItem().copy();
                var result=pickupBatchLimit==1?collection.stowOne():collection.stowAll();
                if(result==GroundCollectionSession.StowResult.STOWED) {
                    removeHeldExpectedDrop(level,t,carried);
                    WorkSoundSync.play(level, anchor, ModSounds.BAG_STOW.get(), .5F, 1F);
                } else if(result==GroundCollectionSession.StowResult.FULL) {
                    UUID returned=collection.returnCarriedToWorldId(level,anchor,true);
                    if(returned==null)return Result.BLOCKED;
                    replaceHeldExpectedDrop(level,t,carried,returned,anchor);
                    t.putBoolean("NeedUnload",true);set(t,Phase.LIFT);actor.triggerWorkContainerUp();return Result.WAITING;
                }
            }
            if(clock>=22)set(t,Phase.COLLECT);
        } else if(p==Phase.LIFT) {
            if(clock==12)WorkSoundSync.play(level, anchor, ModSounds.BAG_UP.get(), .5F, 1F);
            if(clock>=32) {
                actor.clearWorkContainer();
                boolean unload=t.getBoolean("NeedUnload");t.putBoolean("NeedUnload",false);
                set(t,unload?Phase.AWAY:Phase.FINISHED);
                return unload?Result.NEEDS_UNLOAD:Result.COLLECTED;
            }
        }
        return Result.WAITING;
    }

    private void adoptCarried() {
        if(pickupBatchLimit==1)collection.adoptEligibleOffhand();
        else collection.adoptEligibleStackOffhand();
    }
}

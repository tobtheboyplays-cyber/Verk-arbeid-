package com.hearthstead.entity.ai;

import com.hearthstead.block.FishRackBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.FisherSeatEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.work.FisherEvidenceSavedData;
import com.hearthstead.settlement.work.FisherProgression;
import com.hearthstead.settlement.work.FishingGrounds;
import com.hearthstead.settlement.work.ContainerApproach;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.EnumSet;

/** Cast, wait, reel, carry, hang. The persisted worker bag owns every landed catch. */
public final class FisherWorkGoal extends Goal {
    private enum Mode { TO_CHAIR, FISHING, TO_RACK, HANGING }
    private final SettlerEntity settler;
    private Mode mode;
    private Building fishery;
    private FishingGrounds.Result grounds;
    private BlockPos destination, rack;
    private int ticks, travelTicks, cycleLength;
    private long retryAt;
    private boolean done;
    /**
     * Walking to the shore from beyond pathfinding range (captain1 soak
     * 2026-09-26: after the midday meal the Fisher stood 40-60 blocks from
     * the chair; every exact-aisle route search failed at that distance, so
     * he reported "fisher_chair_unreachable" and idled 58% of the workday).
     */
    private boolean approaching;
    private static final double APPROACH_EXACT_SQR = 24.0D * 24.0D;
    private static final double APPROACH_GIVE_UP_SQR = 6.0D * 6.0D;
    private static final int APPROACH_TRAVEL_TICKS = 1_200;
    private static final String LANDED = "HearthsteadFisherLanded";
    /** Shared exact-unit bag-to-chest cycle (Courier/Farmer/Lumberer clip and contact ticks). */
    private final GroundedBagUnload rackBag = new GroundedBagUnload();
    public FisherWorkGoal(SettlerEntity settler) {
        this.settler=settler; setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
    }
    private boolean working() {
        return settler.getProfession()==Profession.FISHER && settler.isBound() && settler.dayPhase().work();
    }
    private boolean employerValid() {
        Settlement s=settler.settlement();
        Building current=s==null?null:Employment.employerOf(s,settler.getUUID());
        return current!=null && current==fishery && current.valid && current.type==BuildingType.FISHERY && current.anchor!=null;
    }
    private boolean hasFish() {
        for(int i=0;i<settler.bag.getContainerSize();i++) if(settler.bag.getItem(i).is(ItemTags.FISHES)) return true;
        return false;
    }
    private void blocked(String reason) {
        settler.recordRouteFailure(reason); done=true; retryAt=settler.level().getGameTime()+100;
        // Surface the reason on the settler sheet too; before this a full rack
        // or unreachable chair still read "Ready" to the player.
        com.hearthstead.logistics.StopReason why = reason.contains("full")
            ? com.hearthstead.logistics.StopReason.CHEST_FULL
            : reason.contains("unreachable") ? com.hearthstead.logistics.StopReason.NO_PATH
            : com.hearthstead.logistics.StopReason.NO_VALID_TARGET;
        settler.setLogisticsStop(why, rack != null ? rack : fishery != null ? fishery.anchor : null, 100);
    }
    @Override public boolean canUse() {
        if(!working() || !(settler.level() instanceof ServerLevel level)) return false;
        if(level.getGameTime()<retryAt) return false;
        retryAt=level.getGameTime()+100;
        Settlement s=settler.settlement();
        fishery=s==null?null:Employment.employerOf(s,settler.getUUID());
        if(!employerValid()) return false;
        // A saved (reloaded) rack unload resumes on its own contract clock.
        // A vanished rack releases only the visual claim: the fish stay bagged.
        if(GroundedBagUnload.presentationTargetGone(level,settler,settler.bagTransferPresentation()))
            GroundedBagUnload.clearPresentation(settler,settler.bagTransferPresentation());
        var pending=settler.bagTransferPresentation();
        if(pending.active()) {
            BlockPos at=pending.containerPos();
            if(!pending.sourcePickup() && fishery.contains(at) && level.getBlockEntity(at) instanceof Container) {
                rack=at; destination=at; mode=Mode.TO_RACK; return true;
            }
            GroundedBagUnload.clearPresentation(settler,pending);
        }
        if(hasFish()) {
            mode=Mode.TO_RACK;
            return findRack(level);
        }
        if(!settler.getMainHandItem().is(com.hearthstead.registry.ModItems.FISHERS_ROD.get())) {
            settler.recordRouteFailure("fisher_needs_rod"); return false;
        }
        grounds=FishingGrounds.scan(level,fishery.anchor);
        if(!grounds.ready()) { settler.recordRouteFailure(grounds.blocker()); return false; }
        // Never start producing if the physical output rack is unavailable.
        if(!findRack(level)) return false;
        destination=reachableAisle(level,grounds.shorePosition(),grounds.direction());
        approaching=false;
        if(destination==null && settler.blockPosition().distSqr(grounds.shorePosition())>APPROACH_EXACT_SQR) {
            // Too far for an exact route: walk towards a standable aisle first.
            destination=standableAisle(level,grounds.shorePosition(),grounds.direction());
            approaching=destination!=null;
        }
        if(destination==null) { settler.recordRouteFailure("fisher_chair_unreachable"); return false; }
        mode=Mode.TO_CHAIR; return true;
    }
    @Override public boolean canContinueToUse() { return !done && working() && employerValid(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        done=false; ticks=0; travelTicks=0;
        settler.setFisherCycleTick(-1); settler.setActivity(SettlerActivity.TRAVELING); path();
    }
    private void path() {
        if(mode==Mode.TO_RACK && settler.level() instanceof ServerLevel level) {
            ContainerApproach.moveToContact(level,settler,rack,.9);
            return;
        }
        if(approaching && destination!=null && settler.level() instanceof ServerLevel level) {
            if(settler.blockPosition().distSqr(destination)<=APPROACH_EXACT_SQR) {
                BlockPos exact=reachableAisle(level,grounds.shorePosition(),grounds.direction());
                if(exact!=null) { destination=exact; approaching=false; travelTicks=0; }
                else if(settler.blockPosition().distSqr(destination)<=APPROACH_GIVE_UP_SQR) {
                    blocked("fisher_chair_unreachable"); return;
                }
            }
            if(approaching) {
                // A partial route towards the shore is progress at this range.
                var toward=settler.getNavigation().createPath(destination,0);
                if(toward!=null) settler.getNavigation().moveTo(toward,.9);
                else settler.getNavigation().moveTo(destination.getX()+.5,destination.getY(),destination.getZ()+.5,.9);
                return;
            }
        }
        if(destination!=null) {
            // Coordinate moveTo uses vanilla accuracy 1 and may stop in the
            // neighbouring cell. Boarding requires the exact selected aisle.
            var route=settler.getNavigation().createPath(destination,0);
            if(route!=null && route.canReach()) settler.getNavigation().moveTo(route,.9);
            else blocked("fisher_chair_unreachable");
        }
    }
    private BlockPos standableAisle(ServerLevel level, BlockPos target, Direction excluded) {
        for(Direction d:Direction.Plane.HORIZONTAL) {
            if(d==excluded) continue;
            BlockPos p=target.relative(d);
            if(FishingGrounds.standable(level,p)) return p;
        }
        return null;
    }
    private BlockPos reachableAisle(ServerLevel level, BlockPos target, Direction excluded) {
        BlockPos best=null; double distance=Double.MAX_VALUE;
        for(Direction d:Direction.Plane.HORIZONTAL) {
            if(d==excluded) continue;
            BlockPos p=target.relative(d);
            if(!FishingGrounds.standable(level,p)) continue;
            var path=settler.getNavigation().createPath(p,0);
            if(path==null || !path.canReach()) continue;
            double current=p.distSqr(settler.blockPosition());
            if(current<distance) {best=p;distance=current;}
        }
        return best;
    }
    private boolean findRack(ServerLevel level) {
        // Racks first (the hanging catch is the fishery's display), then any
        // other store inside the fishery (a barrel). A full 4-fish rack used
        // to stop the fisher until a Courier came by; in the soak that was
        // most of the day (reliability soak 2026-09-26). Couriers collect
        // fish from either kind of container.
        for(int pass=0;pass<2;pass++) {
            for(BlockPos p:WarehouseIndex.containers(level,fishery)) {
                var be=level.getBlockEntity(p);
                boolean isRack=be instanceof FishRackBlockEntity;
                if(!(be instanceof Container container) || isRack!=(pass==0) || !roomForFish(container)) continue;
                if(ContainerApproach.inspect(level,settler,p).canInteract()
                    || ContainerApproach.canPlanContact(level,settler,p)) {
                    rack=p; destination=p; return true;
                }
            }
        }
        settler.recordRouteFailure("fisher_rack_full_or_unreachable"); return false;
    }
    private boolean roomForFish(Container container) {
        for(int i=0;i<container.getContainerSize();i++) {
            ItemStack there=container.getItem(i);
            if(there.isEmpty()) return true;
            if(hasFish()) for(int j=0;j<settler.bag.getContainerSize();j++) {
                ItemStack held=settler.bag.getItem(j);
                if(held.is(ItemTags.FISHES) && ItemStack.isSameItemSameComponents(there,held)
                    && there.getCount()<Math.min(there.getMaxStackSize(),container.getMaxStackSize())) return true;
            }
        }
        return false;
    }
    @Override public void tick() {
        // Vanilla ticks every-tick goals once more after tick() finished them,
        // without canContinueToUse() (see CrafterWorkGoal.tick): never act again.
        if(done) return;
        if(!(settler.level() instanceof ServerLevel level) || !employerValid()) {done=true;return;}
        if(mode==Mode.FISHING) { fish(level); return; }
        if(mode==Mode.HANGING) { hang(level); return; }
        if(mode==Mode.TO_RACK && ContainerApproach.inspect(level,settler,rack).canInteract()) {
            settler.getNavigation().stop(); ticks=0; mode=Mode.HANGING;
            settler.setActivity(SettlerActivity.SORTING); return;
        }
        if(destination==null || ++travelTicks>(approaching?APPROACH_TRAVEL_TICKS:240)) {blocked("fisher_route_unreachable");return;}
        if(mode==Mode.TO_CHAIR && !approaching && settler.position().distanceToSqr(Vec3.atBottomCenterOf(destination))<.64) {
            settler.getNavigation().stop(); ticks=0;
            if(mode==Mode.TO_CHAIR) {
                if(!FishingGrounds.validChair(level,grounds.shorePosition(),grounds.direction())) {blocked("fisher_needs_shore_chair");return;}
                FisherSeatEntity seat=ModEntities.FISHER_SEAT.get().create(level);
                if(seat==null) {done=true;return;}
                seat.prepare(settler,grounds.shorePosition(),destination,grounds.direction());
                settler.setActivity(SettlerActivity.WORK_FISH); settler.setFisherCycleTick(0);
                level.addFreshEntity(seat);
                if(!settler.startRiding(seat)) {seat.discard();done=true;return;}
                cycleLength=com.hearthstead.settlement.development.DevelopmentBonuses.fisherCycleTicks(settler,
                    FisherProgression.cycleTicks(settler.attribute(Attribute.DEXTERITY))); mode=Mode.FISHING;
            } else { mode=Mode.HANGING; settler.setActivity(SettlerActivity.SORTING); }
        } else if(travelTicks%40==0) path();
    }
    private void fish(ServerLevel level) {
        if(!settler.getMainHandItem().is(com.hearthstead.registry.ModItems.FISHERS_ROD.get())) {blocked("fisher_needs_rod");return;}
        if(!(settler.getVehicle() instanceof FisherSeatEntity)
            || !FishingGrounds.validChair(level,grounds.shorePosition(),grounds.direction())) {blocked("fisher_needs_shore_chair");return;}
        int before=ticks*300/cycleLength;
        ticks++;
        int phase=Math.min(299,ticks*300/cycleLength);
        settler.setFisherCycleTick(phase);
        BlockPos chair=grounds.shorePosition(); Direction dir=grounds.direction();
        settler.getLookControl().setLookAt(chair.getX()+.5+dir.getStepX()*3,chair.getY()+.1,chair.getZ()+.5+dir.getStepZ()*3);
        if(before<100 && phase>=100 && settler.getRandom().nextInt(3)==0)
            level.playSound(null,chair,ModSounds.FISHER_WHISTLE.get(),SoundSource.NEUTRAL,.32F,1F);
        if(before<240 && phase>=240)
            level.playSound(null,chair,ModSounds.WORK_FISH_SPLASH.get(),SoundSource.NEUTRAL,.6F,1F);
        if(ticks<cycleLength) return;
        // Resurvey at the commit boundary: draining the lake during a cast cannot mint fish.
        var current=FishingGrounds.scan(level,fishery.anchor);
        // A closer chair appearing mid-cast must not void a finished cast: only
        // an invalid lake or an invalid current chair aborts it.
        if(!current.ready() || !FishingGrounds.validChair(level,chair,grounds.direction())) {
            blocked(current.ready() ? "fisher_needs_shore_chair" : current.blocker());return;}
        ItemStack caught=FisherProgression.rollCatch(settler.getRandom(),settler.attribute(Attribute.DEXTERITY));
        if(!settler.bag.canAddItem(caught)) {blocked("fisher_bag_full");return;}
        ItemStack second=caught.copyWithCount(1);
        settler.bag.addItem(caught);
        // Perception: 0..10% chance a second fish of the same kind comes up
        // with the first (plan/ATTRIBUTES.md); only if the bag has room.
        if(com.hearthstead.entity.AttributeRuntime.extraFind(settler)
            && settler.bag.canAddItem(second)) settler.bag.addItem(second);
        settler.getPersistentData().putInt(LANDED,Math.min(4096,settler.getPersistentData().getInt(LANDED)+1));
        // Trade skill secondary side bonus (level 5+ only): a careful hand
        // sometimes lands a fish without wearing the rod. Never conjures items.
        if(!com.hearthstead.entity.SkillLevels.rollSide(settler))
            settler.getMainHandItem().hurtAndBreak(1,settler,EquipmentSlot.MAINHAND);
        settler.train(Attribute.DEXTERITY,1F); settler.spendEffort(1);
        com.hearthstead.entity.SkillLevels.completeUnit(settler,1,Attribute.DEXTERITY);
        settler.setFisherCycleTick(-1); settler.stopRiding();
        if(!findRack(level)) {done=true;return;}
        mode=Mode.TO_RACK;travelTicks=0;ticks=0;settler.setActivity(SettlerActivity.CARRYING);path();
    }
    private void hang(ServerLevel level) {
        if(rack==null || !fishery.contains(rack) || !level.hasChunkAt(rack) || !(level.getBlockEntity(rack) instanceof Container container)
            || !ContainerApproach.inspect(level,settler,rack).canInteract()) {blocked("fisher_rack_unreachable");return;}
        settler.getLookControl().setLookAt(rack.getX()+.5,rack.getY()+1,rack.getZ()+.5);
        // Same planted cycle as Courier/Farmer/Lumberer: sack down at the bag
        // contact tick, reach in, and each single fish lands on the rack only at
        // BagToChestAnimationContract's deposit tick, then the sack is lifted.
        GroundedBagUnload.Result result=rackBag.tick(level,settler,rack,fish->fish.is(ItemTags.FISHES),unit->{
            if(!insert(container,unit).isEmpty()) return false;
            creditLandedUnit(level); return true;
        });
        if(result==GroundedBagUnload.Result.WAITING) return;
        if(result==GroundedBagUnload.Result.BLOCKED) {
            // Visual claim only: an uncommitted fish is still in the bag and a
            // committed one already reached the rack (and was credited) once.
            releaseUnload();
            blocked(roomForFish(container)?"fisher_rack_unreachable":"fisher_rack_full");return;
        }
        settler.clearLogisticsStop();done=true;retryAt=level.getGameTime()+20;
    }
    /** Credits one real rack insert; manually stocked fish never mint evidence. */
    private void creditLandedUnit(ServerLevel level) {
        int landed=settler.getPersistentData().getInt(LANDED);
        if(landed<=0) return;
        settler.getPersistentData().putInt(LANDED,landed-1);
        FisherEvidenceSavedData.recordDeposit(level,settler.settlement(),fishery,1);
        JourneyServerHooks.noteFisheryOutputCommitted(level,settler.settlement(),fishery,settler);
    }
    private void releaseUnload() {
        var view=settler.bagTransferPresentation();
        if(view.active() && !view.sourcePickup()) GroundedBagUnload.clearPresentation(settler,view);
    }
    private static ItemStack insert(Container into, ItemStack source) {
        ItemStack left=source.copy();
        for(int slot=0;slot<into.getContainerSize() && !left.isEmpty();slot++) {
            if(!into.canPlaceItem(slot,left)) continue;
            ItemStack there=into.getItem(slot);
            int capacity=Math.min(into.getMaxStackSize(),left.getMaxStackSize());
            if(!there.isEmpty() && !ItemStack.isSameItemSameComponents(there,left)) continue;
            int amount=Math.min(left.getCount(),capacity-there.getCount());
            if(amount<=0) continue;
            if(there.isEmpty()) into.setItem(slot,left.copyWithCount(amount));
            else {there.grow(amount);into.setChanged();}
            left.shrink(amount);
        }
        return left;
    }
    @Override public void stop() {
        // Preemption/shift end mid-unload: drop the planted sack and claim only.
        releaseUnload();
        settler.setFisherCycleTick(-1);
        if(settler.getVehicle() instanceof FisherSeatEntity) settler.stopRiding();
        settler.setActivity(SettlerActivity.IDLE);settler.getNavigation().stop();
    }
}

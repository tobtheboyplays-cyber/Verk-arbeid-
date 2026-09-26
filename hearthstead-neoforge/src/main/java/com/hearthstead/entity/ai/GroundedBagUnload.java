package com.hearthstead.entity.ai;

import com.hearthstead.entity.BagTransferPresentation;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.WorkContainerKind;
import com.hearthstead.entity.animation.BagToChestAnimationContract;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.work.ContainerApproach;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** Reusable exact-unit chest cycle; goal callback retains workplace/receipt authority. */
public final class GroundedBagUnload {
    public enum Result { WAITING, COMPLETE, BLOCKED }
    @FunctionalInterface public interface Commit { boolean insertOne(ItemStack unit); }
    private long lastTick=Long.MIN_VALUE;
    public Result tick(ServerLevel level,SettlerEntity actor,BlockPos target,
                       Predicate<ItemStack> eligible,Commit commit) {
        if(lastTick==level.getGameTime())return Result.WAITING;
        lastTick=level.getGameTime();
        if(!ContainerApproach.inspect(level,actor,target).canInteract())return Result.BLOCKED;
        BagTransferPresentation view=actor.bagTransferPresentation();
        if(!view.active()) {
            ItemStack unit=first(actor,eligible);
            if(unit.isEmpty())return Result.COMPLETE;
            BlockPos anchor=actor.placedWorkContainerPos()==null?actor.blockPosition():actor.placedWorkContainerPos();
            if(actor.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(anchor))>2.25D)return Result.BLOCKED;
            int clock=actor.placedWorkContainerPos()==null?0:BagToChestAnimationContract.GROUNDED_REPEAT_TICK;
            actor.publishBagTransferPresentation(new BagTransferPresentation(UUID.randomUUID(),anchor,actor.getYRot(),target,clock,false,unit));
            actor.setActivity(SettlerActivity.SORTING);actor.triggerBagToChestUnload();return Result.WAITING;
        }
        // The saved chest was broken/replaced mid-animation while the caller
        // already chose a live one. Drop only the stale visual claim so the
        // next tick starts a fresh cycle here instead of blocking forever.
        if(!target.equals(view.containerPos())&&presentationTargetGone(level,actor,view)) {
            clearPresentation(actor,view);return Result.WAITING;
        }
        if(!target.equals(view.containerPos())
            || actor.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(view.bagAnchor()))>2.25D)return Result.BLOCKED;
        actor.getNavigation().stop();actor.setActivity(SettlerActivity.SORTING);
        int next=view.clock()+1;
        if(next==BagToChestAnimationContract.BAG_WORLD_CONTACT_TICK) {
            actor.placeWorkContainer(WorkContainerKind.SACK,view.bagAnchor());
            WorkSoundSync.play(level, view.bagAnchor(), ModSounds.BAG_DOWN.get(), .5F, 1F);
        }
        if(next==BagToChestAnimationContract.LID_CONTACT_TICK) {
            level.blockEvent(target,level.getBlockState(target).getBlock(),1,1);
            // The vanilla chest voice for the lid the hand just lifted (presentation only).
            level.playSound(null,target,net.minecraft.sounds.SoundEvents.CHEST_OPEN,
                net.minecraft.sounds.SoundSource.BLOCKS,.35F,.95F+level.random.nextFloat()*.1F);
        }
        boolean committed=view.committed();
        if(BagToChestAnimationContract.mayCommit(next,committed)) {
            ItemStack unit=view.item(); int slot=-1;
            for(int i=0;i<actor.bag.getContainerSize();i++) {
                ItemStack live=actor.bag.getItem(i);
                if(!live.isEmpty()&&unit.getCount()==1&&eligible.test(live)
                    &&ItemStack.isSameItemSameComponents(live,unit)) {slot=i;break;}
            }
            if(slot<0) {
                // External bag edits cannot authorize a replacement item;
                // release the stale claim (as LumbererWorkGoal does).
                clearPresentation(actor,view);return Result.BLOCKED;
            }
            if(!commit.insertOne(unit.copy()))return Result.BLOCKED;
            ItemStack live=actor.bag.getItem(slot);live.shrink(1);actor.bag.setItem(slot,live);
            committed=true;
        }
        if(next==BagToChestAnimationContract.LID_CLOSED_TICK) {
            level.blockEvent(target,level.getBlockState(target).getBlock(),1,0);
            level.playSound(null,target,net.minecraft.sounds.SoundEvents.CHEST_CLOSE,
                net.minecraft.sounds.SoundSource.BLOCKS,.3F,.95F+level.random.nextFloat()*.1F);
            ItemStack unit=first(actor,eligible);
            if(BagToChestAnimationContract.continuesGroundedSession(next,committed,unit.isEmpty())) {
                actor.publishBagTransferPresentation(new BagTransferPresentation(UUID.randomUUID(),view.bagAnchor(),view.bagYaw(),target,
                    BagToChestAnimationContract.GROUNDED_REPEAT_TICK,false,unit));return Result.WAITING;
            }
        }
        if(next>=BagToChestAnimationContract.DURATION_TICKS) {
            actor.clearBagTransferPresentation(view.transferId());actor.clearWorkContainer();
            return first(actor,eligible).isEmpty()?Result.COMPLETE:Result.WAITING;
        }
        actor.publishBagTransferPresentation(view.advance(next,committed));return Result.WAITING;
    }
    /** True when an active presentation names a chest that is no longer a live container. */
    static boolean presentationTargetGone(ServerLevel level,SettlerEntity actor,BagTransferPresentation view) {
        return view.active()&&ContainerApproach.inspect(level,actor,view.containerPos()).state()
            ==ContainerApproach.State.INVALID_TARGET;
    }
    /**
     * Clears one stale transfer claim and the grounded sack prop it placed at
     * its anchor. Visual only: the bag is never touched, so an uncommitted
     * unit is still in the bag and a committed one already left it once.
     */
    static void clearPresentation(SettlerEntity actor,BagTransferPresentation view) {
        actor.clearBagTransferPresentation(view.transferId());
        if(view.bagAnchor()!=null&&view.bagAnchor().equals(actor.placedWorkContainerPos()))actor.clearWorkContainer();
    }
    private static ItemStack first(SettlerEntity actor,Predicate<ItemStack> eligible) {
        for(int i=0;i<actor.bag.getContainerSize();i++) {
            ItemStack item=actor.bag.getItem(i);if(!item.isEmpty()&&eligible.test(item))return item.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }
}

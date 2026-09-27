package com.hearthstead.entity.ai;

import com.hearthstead.entity.*;
import com.hearthstead.registry.ModSounds;
import net.minecraft.sounds.SoundSource;
import com.hearthstead.entity.animation.BagToChestAnimationContract;
import com.hearthstead.settlement.request.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Presentation-only FOOD contact session. RequestLedgerService alone moves cargo. */
final class CourierFoodBagSession {
    private static final String KEY = "HearthsteadCourierFoodBag";
    private final SettlerEntity actor;
    private long lastTick = Long.MIN_VALUE;
    private long retryAt;
    private int routeTicks;
    private long blockedUntil;
    /** True during the short backoff after a failed walk to the Hearth. */
    boolean coolingDown(ServerLevel level) { return level.getGameTime() < blockedUntil; }
    enum Result { RUNNING, COMPLETE, BLOCKED }
    CourierFoodBagSession(SettlerEntity actor) { this.actor = actor; }
    boolean active() { return actor.getPersistentData().contains(KEY); }
    RequestRecord request(ServerLevel level) {
        if (actor.settlement() == null) return null;
        var tag = actor.getPersistentData().getCompound(KEY);
        if (tag.getInt("Version") != 1 || !tag.hasUUID("Request")) return null;
        var data = RequestLedgerSavedData.existing(level);
        var ledger = data == null ? null : data.existing(actor.settlement().id);
        var request = ledger == null ? null : ledger.any(tag.getUUID("Request"));
        var view = actor.bagTransferPresentation();
        return data != null && !data.rootQuarantined() && ledger != null && !ledger.quarantined()
            && request != null && request.type() == RequestType.FOOD
            && actor.getUUID().equals(request.courierId()) && view.active()
            && request.targetContainer().equals(actor.getHearthPos())
            && request.targetContainer().equals(view.containerPos())
            && (request.state() == RequestState.IN_TRANSIT
                || request.state() == RequestState.SATISFIED
                || request.state() == RequestState.BLOCKED
                    && request.blockedFrom() == RequestState.IN_TRANSIT)
            ? request : null;
    }
    void begin(ServerLevel level, UUID requestId) {
        if (active()) return;
        var data = RequestLedgerSavedData.existing(level);
        var ledger = data == null || actor.settlement() == null ? null : data.existing(actor.settlement().id);
        var request = ledger == null ? null : ledger.active(requestId);
        if (request == null || request.type() != RequestType.FOOD
                || !actor.getUUID().equals(request.courierId())) return;
        ItemStack unit = request.fingerprint().prototype(level.registryAccess()).copyWithCount(1);
        if (unit.isEmpty()) return;
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putUUID("Request", requestId);
        actor.getPersistentData().put(KEY, tag);
        actor.publishBagTransferPresentation(new BagTransferPresentation(UUID.randomUUID(),
            actor.blockPosition(), actor.getYRot(), request.targetContainer(), 0, false, unit));
        actor.setActivity(SettlerActivity.CARRYING);
        actor.triggerBagToChestUnload();
    }
    Result tick(ServerLevel level) {
        if (lastTick == level.getGameTime()) return Result.RUNNING;
        lastTick = level.getGameTime();
        var request = request(level);
        if (request == null || actor.hearth() == null) return Result.BLOCKED;
        var view = actor.bagTransferPresentation();
        boolean liveContact = RequestLedgerService.hasFoodHearthContact(level,
            actor, view.containerPos());
        // A committed presentation keeps its original floor anchor. Before the
        // first ledger mutation, a legacy/raw-Hearth arrival may have saved an
        // anchor outside the centre-range contract; replace only that invalid
        // uncommitted anchor after reaching a lawful grounded contact.
        boolean anchored = actor.position().distanceToSqr(Vec3.atBottomCenterOf(view.bagAnchor())) <= 2.25D;
        boolean anchorLegal = RequestLedgerService.hasFoodHearthContact(level,
            actor, Vec3.atBottomCenterOf(view.bagAnchor()), view.containerPos(), 0.0D);
        if (!view.committed() && !anchorLegal && liveContact && actor.onGround()
                && RequestLedgerService.hasFoodHearthContact(level, actor,
                    Vec3.atBottomCenterOf(actor.blockPosition()), view.containerPos(), 0.0D)) {
            actor.clearWorkContainer();
            actor.publishBagTransferPresentation(new BagTransferPresentation(
                view.transferId(), actor.blockPosition(), actor.getYRot(),
                view.containerPos(), 0, false, view.item()));
            actor.triggerBagToChestUnload();
            routeTicks = 0;
            return Result.RUNNING;
        }
        if (!anchored || !liveContact) {
            actor.setActivity(SettlerActivity.TRAVELING);
            if (++routeTicks > 240) {
                // Restart the walk budget and hold a short backoff; a stale
                // counter otherwise re-blocked every restart forever.
                routeTicks = 0;
                blockedUntil = level.getGameTime() + 200;
                return Result.BLOCKED;
            }
            if (routeTicks % 20 == 1) {
                var path = HearthApproach.findCourierFoodContactPath(actor,
                    level, view.containerPos());
                if (path != null) actor.getNavigation().moveTo(path, .9D);
            }
            return Result.RUNNING;
        }
        actor.getNavigation().stop(); routeTicks = 0;
        actor.getLookControl().setLookAt(view.containerPos().getX() + .5,
            view.containerPos().getY() + .6, view.containerPos().getZ() + .5);
        actor.setActivity(view.clock() < BagToChestAnimationContract.GROUNDED_REPEAT_TICK ? SettlerActivity.CARRYING : SettlerActivity.SORTING);
        if (view.clock() >= BagToChestAnimationContract.BAG_WORLD_CONTACT_TICK)
            actor.placeWorkContainer(WorkContainerKind.SACK, view.bagAnchor());
        if (level.getGameTime() < retryAt) return Result.RUNNING;
        int next = view.clock() + 1;
        boolean committed = view.committed();
        if (next == BagToChestAnimationContract.BAG_WORLD_CONTACT_TICK) {
            actor.placeWorkContainer(WorkContainerKind.SACK, view.bagAnchor());
            WorkSoundSync.play(level, view.bagAnchor(), ModSounds.BAG_DOWN.get(), .5F, 1F);
        }
        if (next == BagToChestAnimationContract.DEPOSIT_COMMIT_TICK && !committed) {
            int before = request.deliveredCount();
            var result = RequestLedgerService.deliver(level, actor.settlement(), request.id(), actor, 1);
            request = result.request();
            if (request == null || request.deliveredCount() - before != 1) {
                retryAt = level.getGameTime() + 40;
                return result.blocker() == RequestBlocker.TARGET_FULL ? Result.RUNNING : Result.BLOCKED;
            }
            committed = true;
            // Only the ledger's real +1 target/-1 bag commit produces a stow cue.
            WorkSoundSync.play(level, view.containerPos(), ModSounds.BAG_STOW.get(), .5F, 1F);
        }
        if (next >= BagToChestAnimationContract.LID_CLOSED_TICK && committed && request.state() != RequestState.SATISFIED) {
            actor.publishBagTransferPresentation(new BagTransferPresentation(UUID.randomUUID(),
                view.bagAnchor(), view.bagYaw(), view.containerPos(), BagToChestAnimationContract.GROUNDED_REPEAT_TICK, false, view.item()));
            return Result.RUNNING;
        }
        if (next >= BagToChestAnimationContract.DURATION_TICKS && committed && request.state() == RequestState.SATISFIED) {
            // Actual world-to-carried presentation handoff, never a guessed earlier frame.
            WorkSoundSync.play(level, view.bagAnchor(), ModSounds.BAG_UP.get(), .5F, 1F);
            actor.clearBagTransferPresentation(view.transferId());
            actor.clearWorkContainer(); actor.getPersistentData().remove(KEY);
            return Result.COMPLETE;
        }
        actor.publishBagTransferPresentation(view.advance(next, committed));
        return Result.RUNNING;
    }
}

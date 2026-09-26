package com.hearthstead.entity.ai;

import com.hearthstead.entity.*;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.request.*;
import com.hearthstead.settlement.work.ContainerApproach;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Saved presentation/contact receipt only. RequestLedgerService owns every actual unit. */
public final class CourierSourceBagSession {
    private static final String KEY = "HearthsteadCourierSourceBag";
    private final SettlerEntity actor;
    private long lastTick = Long.MIN_VALUE;
    private boolean lidOpen;
    public enum Result { RUNNING, COMPLETE, BLOCKED }
    public CourierSourceBagSession(SettlerEntity actor) { this.actor = actor; }
    public boolean active() { return actor.getPersistentData().contains(KEY); }
    public boolean ready(long now) {
        var t = read(); return t != null && now >= t.getLong("PathRetryUntil");
    }
    public boolean pathResting(long now) {
        var t = read(); return t != null && now < t.getLong("PathRetryUntil");
    }
    private CompoundTag read() {
        var raw = actor.getPersistentData().get(KEY);
        if (!(raw instanceof CompoundTag t) || t.getInt("Version") != 1
            || !t.hasUUID("Owner") || !actor.getUUID().equals(t.getUUID("Owner"))
            || !t.hasUUID("Settlement") || !t.hasUUID("Request") || !t.hasUUID("Cycle")
            || !t.contains("Source", Tag.TAG_LONG) || !t.contains("Anchor", Tag.TAG_LONG)
            || !t.contains("Expected", Tag.TAG_INT) || t.getInt("Expected") < 0
            || t.getInt("Expected") > RequestItemFingerprint.MAX_COUNT
            || !t.contains("Clock", Tag.TAG_INT) || t.getInt("Clock") < 0 || t.getInt("Clock") > 80
            || !t.contains("Ack", Tag.TAG_BYTE) || t.getByte("Ack") < 0 || t.getByte("Ack") > 1
            || !t.contains("Pending", Tag.TAG_BYTE) || t.getByte("Pending") < 0 || t.getByte("Pending") > 1
            || !t.contains("Yaw", Tag.TAG_FLOAT) || !Float.isFinite(t.getFloat("Yaw"))) return null;
        return t;
    }
    public RequestRecord request(ServerLevel level) {
        var t = read();
        var s = actor.settlement();
        if (t == null || s == null || !s.id.equals(t.getUUID("Settlement"))) return null;
        var data = RequestLedgerSavedData.existing(level);
        var ledger = data == null || data.rootQuarantined() ? null : data.existing(s.id);
        var r = ledger == null || ledger.quarantined() ? null : ledger.active(t.getUUID("Request"));
        return r != null && actor.getUUID().equals(r.courierId())
            && r.sourceContainer().asLong() == t.getLong("Source")
            && (r.effectiveState() == RequestState.RESERVED || r.effectiveState() == RequestState.PICKUP
                || r.effectiveState() == RequestState.IN_TRANSIT) ? r : null;
    }
    /**
     * Retires only the exact saved preview that has proved to own no cargo and
     * whose matching OUTPUT_PICKUP row has been authoritatively expired by the
     * ledger. A partial lift, a pending receipt, a foreign row, or a
     * quarantined ledger remains fail-closed and keeps this saved session.
     */
    public boolean retireExpiredZeroCargoOutput(ServerLevel level) {
        CompoundTag receipt = read();
        var settlement = actor.settlement();
        if (level == null || receipt == null || settlement == null
            || !settlement.id.equals(receipt.getUUID("Settlement"))
            || receipt.getInt("Expected") != 0 || receipt.getInt("Clock") >= 48
            || receipt.getBoolean("Ack") || receipt.getBoolean("Pending")
            || !actor.bag.isEmpty()) {
            return false;
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null || saved.rootQuarantined()
            ? null : saved.existing(settlement.id);
        if (ledger == null || ledger.quarantined()) {
            return false;
        }
        UUID requestId = receipt.getUUID("Request");
        RequestRecord before = ledger.any(requestId);
        if (before == null || before.type() != RequestType.OUTPUT_PICKUP
            || !settlement.id.equals(before.settlementId())
            || !actor.getUUID().equals(before.courierId())
            || before.sourceContainer().asLong() != receipt.getLong("Source")
            || before.movedCount() != 0 || before.deliveredCount() != 0) {
            return false;
        }
        // A source stack can grow after the empty sack has been put down.
        // Clocks below the first contact (48) still own no item; the guards
        // above and the exact ledger receipt below must independently prove it.
        // routeForCourier owns the narrow stale-output expiry and its ledger
        // commit. This presentation never decides that a row is disposable.
        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            level, settlement, actor);
        if (route.outcome() == RequestLedgerService.Outcome.QUARANTINED
            || saved.rootQuarantined() || ledger.quarantined()) {
            return false;
        }
        RequestRecord terminal = ledger.any(requestId);
        if (terminal == null || terminal.state() != RequestState.EXPIRED
            || terminal.type() != RequestType.OUTPUT_PICKUP
            || !settlement.id.equals(terminal.settlementId())
            || !actor.getUUID().equals(terminal.courierId())
            || terminal.sourceContainer().asLong() != receipt.getLong("Source")
            || terminal.movedCount() != 0 || terminal.deliveredCount() != 0) {
            return false;
        }
        interrupt();
        actor.getPersistentData().remove(KEY);
        return true;
    }

    public void begin(ServerLevel level, RequestRecord r) {
        if (active() || r == null || !actor.getUUID().equals(r.courierId())
                || r.effectiveState() != RequestState.RESERVED && r.effectiveState() != RequestState.PICKUP) return;
        BlockPos anchor = chooseAnchor(level, r.sourceContainer());
        if (anchor == null) {
            RequestLedgerService.block(level, actor.settlement(), r.id(), actor, RequestBlocker.NO_PATH);
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("Version", 1); t.putUUID("Owner", actor.getUUID());
        t.putUUID("Settlement", r.settlementId()); t.putUUID("Request", r.id());
        t.putUUID("Cycle", UUID.randomUUID()); t.putLong("Source", r.sourceContainer().asLong());
        t.putLong("Anchor", anchor.asLong()); t.putFloat("Yaw", actor.getYRot());
        t.putInt("Expected", r.movedCount()); t.putInt("Clock", 0);
        t.putBoolean("Ack", false); t.putBoolean("Pending", false);
        actor.getPersistentData().put(KEY, t);
    }
    private BlockPos chooseAnchor(ServerLevel level, BlockPos source) {
        java.util.Set<BlockPos> candidates = new java.util.LinkedHashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos p = source.offset(dx, 0, dz);
            if (!level.hasChunkAt(p) || !level.hasChunkAt(p.above()) || !level.hasChunkAt(p.below())
                || !level.getFluidState(p).isEmpty()
                || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                || !level.noCollision(actor, actor.getBoundingBox().move(
                    Vec3.atBottomCenterOf(p).subtract(actor.position())))) continue;
            candidates.add(p);
        }
        if (candidates.contains(actor.blockPosition())) return actor.blockPosition().immutable();
        if (candidates.isEmpty()) return null;
        var path = actor.getNavigation().createPath(candidates, 0);
        return path != null && path.canReach() && candidates.contains(path.getTarget()) ? path.getTarget().immutable() : null;
    }

    /** Stops the hand preview during danger; saved floor prop/cargo are retained. */
    public void interrupt() {
        setLid(false);
        var v = actor.bagTransferPresentation();
        if (v.active() && v.sourcePickup()) actor.clearBagTransferPresentation(v.transferId());
    }
    private void setLid(boolean open) {
        if (lidOpen == open) return;
        var t = read();
        if (t != null && actor.level() instanceof ServerLevel level) {
            BlockPos source = BlockPos.of(t.getLong("Source"));
            if (level.hasChunkAt(source)) {
                var state = level.getBlockState(source);
                level.blockEvent(source, state.getBlock(), 1, open ? 1 : 0);
            }
        }
        lidOpen = open;
    }
    private boolean physical(ServerLevel level, RequestRecord r) {
        if (!level.hasChunkAt(r.sourceContainer())
            || !(level.getBlockEntity(r.sourceContainer()) instanceof Container source)
            || r.sourceSlot() >= source.getContainerSize()) return false;
        ItemStack stack = source.getItem(r.sourceSlot());
        int expected = r.sourceCountBefore() - r.movedCount();
        // The reservation owns the snapshot quantity, not later matching output.
        // A producer may merge identical goods into this real slot while the Courier walks.
        // Shrink below the remaining snapshot is still a broken custody claim.
        if (stack.getCount() < expected
            || !r.fingerprint().matches(level.registryAccess(), stack)) return false;
        int actual = 0;
        for (int n = 0; n < actor.bag.getContainerSize(); n++) {
            ItemStack item = actor.bag.getItem(n);
            if (!item.isEmpty() && !r.fingerprint().matches(level.registryAccess(), item)) return false;
            actual += item.getCount();
        }
        return actual == r.movedCount();
    }
    public Result tick(ServerLevel level) {
        if (lastTick == level.getGameTime()) return Result.RUNNING;
        lastTick = level.getGameTime();
        var t = read(); var r = request(level);
        if (t == null || r == null) { interrupt(); return Result.BLOCKED; }
        BlockPos anchor = BlockPos.of(t.getLong("Anchor"));
        BlockPos source = r.sourceContainer();
        int expected = t.getInt("Expected");
        boolean ack = t.getBoolean("Ack");
        int clock = t.getInt("Clock");
        // A save between the two receipt writes cannot repeat a successful debit.
        if (!ack && t.getBoolean("Pending") && r.movedCount() == expected + 1) {
            ack = true; t.putBoolean("Ack", true); t.putBoolean("Pending", false);
            clock = 48; t.putInt("Clock", clock);
        }
        if (r.movedCount() != expected + (ack ? 1 : 0)) {
            interrupt(); return Result.BLOCKED;
        }
        if (level.getGameTime() < t.getLong("PathRetryUntil")) return Result.BLOCKED;
        if (t.getLong("PathRetryUntil") > 0) {
            // A genuinely new bounded attempt follows the saved cooldown.
            t.putLong("PathRetryUntil", 0); t.putInt("RouteTicks", 0);
            t.putInt("Losses", 0); t.putBoolean("Away", false);
        }
        if (actor.position().distanceToSqr(Vec3.atBottomCenterOf(anchor)) > .16
            || (!ack || r.movedCount() < r.fingerprint().count())
                && !ContainerApproach.inspect(level, actor, source).canInteract()) {
            interrupt(); actor.setActivity(SettlerActivity.TRAVELING);
            int ticks = t.getInt("RouteTicks") + 1; t.putInt("RouteTicks", ticks);
            if (!t.getBoolean("Away")) t.putInt("Losses", t.getInt("Losses") + 1);
            t.putBoolean("Away", true);
            if (ticks > 240 || t.getInt("Losses") > 32) {
                t.putLong("PathRetryUntil", level.getGameTime() + CourierWorkGoal.FIRST_REST_TICKS);
                return Result.BLOCKED;
            }
            Vec3 centre = Vec3.atBottomCenterOf(anchor);
            Vec3 delta = centre.subtract(actor.position());
            boolean lastCell = delta.lengthSqr() <= 2.25 && level.hasChunkAt(anchor)
                && level.hasChunkAt(anchor.below())
                && level.getBlockState(anchor.below()).isFaceSturdy(level, anchor.below(), net.minecraft.core.Direction.UP)
                && level.getFluidState(anchor).isEmpty()
                && level.noCollision(actor, actor.getBoundingBox().expandTowards(delta))
                && level.clip(new net.minecraft.world.level.ClipContext(actor.getEyePosition(),
                    centre.add(0, actor.getEyeHeight(), 0), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, actor)).getType()
                    == net.minecraft.world.phys.HitResult.Type.MISS;
            if (lastCell) {
                // Vanilla paths may finish before the exact cell centre. Complete
                // only this clear supported final step through normal MoveControl.
                actor.getNavigation().stop();
                actor.getMoveControl().setWantedPosition(centre.x, centre.y, centre.z, .65);
            } else if (ticks % 20 == 1 && level.hasChunkAt(anchor)) {
                var path = actor.getNavigation().createPath(anchor, 0);
                if (path != null && path.canReach()) actor.getNavigation().moveTo(path, .9);
            }
            return Result.RUNNING;
        }
        t.putInt("RouteTicks", 0); t.putBoolean("Away", false);
        actor.getNavigation().stop();
        if (level.getGameTime() < t.getLong("RetryAt")) return Result.RUNNING;
        // Revalidate live source every preview frame; never display an obsolete unit.
        if (!ack && !physical(level, r)) {
            interrupt(); t.putLong("RetryAt", level.getGameTime() + 40);
            if (clock >= 24) t.putInt("Clock", 24);
            t.putBoolean("Pending", false);
            RequestLedgerService.block(level, actor.settlement(), r.id(), actor,
                RequestBlocker.FINGERPRINT_MISMATCH);
            return Result.BLOCKED;
        }
        if (!ack) {
            var route = RequestLedgerService.routeForCourier(level, actor.settlement(), actor);
            r = route.request();
            if (r == null || r.state() == RequestState.BLOCKED) { interrupt(); return Result.BLOCKED; }
        }
        actor.getLookControl().setLookAt(source.getX() + .5, source.getY() + .8, source.getZ() + .5);
        actor.setActivity(clock < 24 ? SettlerActivity.CARRYING : SettlerActivity.SORTING);
        if (clock >= 12) actor.placeWorkContainer(WorkContainerKind.SACK, anchor);
        int next = clock + 1;
        if (next >= 24 && next < 64) setLid(true);
        if (next >= 64 && r.movedCount() == r.fingerprint().count()) setLid(false);
        if (next == 12) {
            actor.placeWorkContainer(WorkContainerKind.SACK, anchor);
            WorkSoundSync.play(level, anchor, ModSounds.BAG_DOWN.get(), .5F, 1F);
        }
        if (next == 48 && !ack) {
            t.putBoolean("Pending", true);
            var decision = RequestLedgerService.pickupOne(level, actor.settlement(), r.id(), actor, expected);
            r = decision.request();
            if (r == null || r.movedCount() != expected + 1) {
                t.putBoolean("Pending", false); t.putLong("RetryAt", level.getGameTime() + 40);
                t.putInt("Clock", 24); interrupt(); return Result.BLOCKED;
            }
            ack = true; t.putBoolean("Ack", true); t.putBoolean("Pending", false);
            WorkSoundSync.play(level, anchor, ModSounds.BAG_STOW.get(), .5F, 1F);
        }
        if (next >= 64 && ack && r.movedCount() < r.fingerprint().count()) {
            t.putInt("Expected", r.movedCount()); t.putBoolean("Ack", false);
            t.putInt("Clock", 24); t.putUUID("Cycle", UUID.randomUUID());
            publish(level, t, r, 24, false); return Result.RUNNING;
        }
        if (next >= 80 && ack && r.movedCount() == r.fingerprint().count()) {
            WorkSoundSync.play(level, anchor, ModSounds.BAG_UP.get(), .5F, 1F);
            interrupt(); actor.clearWorkContainer(); actor.getPersistentData().remove(KEY);
            return Result.COMPLETE;
        }
        t.putInt("Clock", next); publish(level, t, r, next, ack);
        return Result.RUNNING;
    }
    private void publish(ServerLevel level, CompoundTag t, RequestRecord r, int clock, boolean ack) {
        actor.publishBagTransferPresentation(new BagTransferPresentation(t.getUUID("Cycle"),
            BlockPos.of(t.getLong("Anchor")), t.getFloat("Yaw"), r.sourceContainer(),
            clock, ack, r.fingerprint().prototype(level.registryAccess()).copyWithCount(1), true));
    }
}

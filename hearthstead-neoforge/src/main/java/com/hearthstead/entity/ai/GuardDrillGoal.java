package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardDrillScript;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.guard.drill.GuardDrillRules;
import com.hearthstead.settlement.guard.drill.GuardDrillYard;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Guard Drill's morning drill (Watch &amp; Defense ring 1): after breakfast the relieved watch
 * walks to the yard in front of its Barracks and spars in pairs with practice blows; an odd
 * guard shadow-drills. See {@link GuardDrillRules} for when and who, {@link GuardDrillYard} for
 * the pairing and {@link GuardDrillScript} for the choreography.
 *
 * <p><b>Raid safety.</b> Priority 3, below every combat goal (0-2) and level with the alarm
 * response: {@link #canContinueToUse} fails the very tick an alarm, raid, target, hurt, summons
 * or order appears, the goal selector stops it and starts the combat/alarm goal in the same
 * tick, and the yard stays closed for the rest of the day. It never calls hurt, never sets a
 * target, never uses an item: a practice blow is a clip, a quiet sound and a Strength rep.
 *
 * <p><b>Cost.</b> Not a member: one cheap rejection most ticks, the full check every
 * {@value #RECHECK} ticks. A member: one {@code moveTo} to its slot, a repath at most every
 * {@value #REPATH} ticks while walking, a look and a contact scan per tick in the yard, and a
 * synced cue only when the pairing changes.
 */
public class GuardDrillGoal extends Goal {
    private static final int RECHECK = 20;
    private static final int REPATH = 40;
    private static final double ARRIVE_SQR = 2.25D;
    /** Within this (squared) of the slot the guard walks straight in (no pathfinding). */
    private static final double NEAR_SQR = 9.0D;
    private static final double DRIFT_SQR = 16.0D;
    /** Footwork never carries a guard further than this from its own yard slot (blocks). */
    private static final double LEASH = 3.0D;
    /** Partners keep 1.8-3 blocks apart while they circle, press and give ground. */
    private static final double MIN_GAP = 1.8D;
    private static final double MAX_GAP = 3.0D;
    private static final int ASSIGN_EVERY = 10;

    private final SettlerEntity settler;
    private long nextCheck;
    private int index = -1;
    private BlockPos slot;
    private boolean arrived;
    private long walkStarted;
    private long nextRepath;
    private long nextAssign;
    private long endAt;
    private CompoundTag cue = new CompoundTag();
    private GuardDrillYard.Assignment assignment;
    private GuardDrillScript.Plan plan;
    private float lastElapsed = Float.NaN;
    private int sparTicks;
    private float repsPaid;
    /** Why the goal ended: null = pre-empted (may resume), else a terminal reason. */
    private String endReason;
    /** The fitness gate that ended the session (triage). */
    private String unfitWhy;

    public GuardDrillGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.GUARD
            || !(settler.level() instanceof ServerLevel level)
            || !GuardDrillRules.open(level.getDayTime())) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextCheck) return false;
        nextCheck = now + RECHECK + (settler.getId() & 7);
        Settlement settlement = settler.settlement();
        if (settlement == null || !GuardDrillYard.fit(level, settlement, settler, false)) return false;
        int i = GuardDrillYard.admit(level, settlement, settler);
        if (i < 0) return false;
        BlockPos pos = GuardDrillYard.slotPos(level, settlement, settler, i);
        if (pos == null) return false;
        long end = GuardDrillYard.endAt(level, settlement, settler);
        if (end <= now) {
            GuardDrillYard.finish(settlement, settler, "expired");
            return false;
        }
        index = i;
        slot = pos;
        endAt = end;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(settler.level() instanceof ServerLevel level) || endReason != null) return false;
        Settlement settlement = settler.settlement();
        if (settlement == null) return false;
        // An alarm, a raid, a target or a blow ends the drill this very tick and closes the yard.
        if (GuardDrillYard.threat(level, settlement) || settler.getTarget() != null || settler.hurtTime > 0) {
            endReason = "threat";
            GuardDrillYard.cancel(settlement, settler, settler.getTarget() != null ? "target"
                : settler.hurtTime > 0 ? "hurt" : "alarm");
            return false;
        }
        if (GuardDrillYard.cancelled(settlement, settler)) {
            endReason = "threat";
            return false;
        }
        if (level.getGameTime() >= endAt || !GuardDrillRules.open(level.getDayTime())) {
            endReason = "done";
            return false;
        }
        // The full fitness check (orders, weather, needs) every half second; threats every tick.
        if ((settler.tickCount + settler.getId()) % 10 == 0
            && (unfitWhy = GuardDrillYard.why(level, settlement, settler, true)) != null) {
            // Hungry, tired, hurt, rain, an order or a summons: back to the ordinary day.
            endReason = "unfit";
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        endReason = null;
        unfitWhy = null;
        arrived = false;
        lastElapsed = Float.NaN;
        assignment = null;
        plan = null;
        long now = settler.level().getGameTime();
        walkStarted = now;
        nextRepath = now;
        nextAssign = now;
        if (settler.isSleeping()) {
            // Just off the night watch and not exhausted: up for the drill, bed afterwards.
            settler.stopSleeping();
        }
        settler.setActivity(SettlerActivity.TRAVELING);
        walk();
    }

    private void walk() {
        settler.getNavigation().moveTo(slot.getX() + 0.5D, slot.getY(), slot.getZ() + 0.5D, 0.8D);
        nextRepath = settler.level().getGameTime() + REPATH;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) return;
        Settlement settlement = settler.settlement();
        if (settlement == null) return;
        long now = level.getGameTime();
        if (endReason != null) return;
        if (GuardDrillYard.threat(level, settlement) || settler.getTarget() != null || settler.hurtTime > 0) {
            // Raid safety: drop the drill pose this very tick; the goal selector stops the goal at
            // its next update (canContinueToUse) and the combat and alarm goals take over.
            endReason = "threat";
            GuardDrillYard.cancel(settlement, settler, settler.getTarget() != null ? "target"
                : settler.hurtTime > 0 ? "hurt" : "alarm");
            settler.getNavigation().stop();
            setCue(new CompoundTag());
            return;
        }
        double dist = settler.distanceToSqr(slot.getX() + 0.5D, settler.getY(), slot.getZ() + 0.5D);
        if (!arrived) {
            GuardDrillYard.heartbeat(level, settlement, settler, false);
            boolean level_ = Math.abs(settler.getY() - slot.getY()) < 1.5D;
            boolean timedOut = now - walkStarted > GuardDrillRules.WALK_LIMIT_TICKS;
            if (level_ && (dist <= ARRIVE_SQR || timedOut && dist <= NEAR_SQR)) {
                arrived = true;
                settler.getNavigation().stop();
                settler.setActivity(SettlerActivity.PATROLLING);
                GuardDrillYard.heartbeat(level, settlement, settler, true);
                nextAssign = now;
            } else if (timedOut) {
                settler.recordRouteFailure("guard_drill:no_path");
                endReason = "no_path";
            } else if (level_ && dist <= NEAR_SQR && settler.getNavigation().isDone()) {
                // A path to a block one or two away often "completes" without a step: walk the
                // last stretch straight in.
                settler.getMoveControl().setWantedPosition(slot.getX() + 0.5D, slot.getY(), slot.getZ() + 0.5D, 0.8D);
            } else if (now >= nextRepath && settler.getNavigation().isDone()) {
                walk();
            }
            return;
        }
        if (dist > DRIFT_SQR) {
            // Shoved out of the yard: walk back (rare; bounded by the same walk limit).
            arrived = false;
            walkStarted = now;
            setCue(new CompoundTag());
            walk();
            return;
        }
        GuardDrillYard.heartbeat(level, settlement, settler, true);
        if (now >= nextAssign) {
            nextAssign = now + ASSIGN_EVERY;
            GuardDrillYard.Assignment a = GuardDrillYard.assignment(level, settlement, settler, index);
            CompoundTag next = a.cue();
            if (!next.equals(cue)) {
                setCue(next);
                plan = a.mode() == GuardDrillYard.MODE_PAIR || a.mode() == GuardDrillYard.MODE_SOLO
                    ? GuardDrillScript.plan(a.seed(), a.mode() == GuardDrillYard.MODE_PAIR, a.length())
                    : null;
                lastElapsed = Float.NaN;
            }
            assignment = a;
        }
        face();
        if (plan != null && assignment != null) {
            float elapsed = now - assignment.start();
            if (elapsed >= 0.0F && elapsed <= plan.lengthTicks() + 40.0F) {
                if (elapsed <= plan.lengthTicks()) sparTicks++;
                if (!Float.isNaN(lastElapsed)) {
                    beats(level, lastElapsed, elapsed);
                    step(level, GuardDrillScript.displacement(plan, assignment.slot(), lastElapsed, elapsed));
                    close(level, elapsed);
                }
            }
            lastElapsed = elapsed;
        }
    }

    /** Face the partner (or, alone, the empty place where a partner would stand). */
    private void face() {
        Vec3 target = null;
        if (assignment != null && assignment.partner() != null) {
            target = assignment.partner().position();
        } else if (assignment != null && assignment.faceTowards() != null) {
            target = Vec3.atBottomCenterOf(assignment.faceTowards());
        }
        if (target == null) return;
        double dx = target.x - settler.getX();
        double dz = target.z - settler.getZ();
        if (dx * dx + dz * dz < 1.0E-4) return;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        settler.setYRot(yaw);
        settler.setYBodyRot(yaw);
        settler.setYHeadRot(yaw);
        settler.getLookControl().setLookAt(target.x, settler.getEyeY(), target.z, 30.0F, 30.0F);
    }

    /**
     * Footwork: the plan's side-steps, advances and retreats move the body with the clip's own
     * easing. A side-step keeps the distance to the partner (the pair turns round each other);
     * an advance or retreat changes it, within 1.8-3 blocks. Never off solid ground, never more
     * than {@value #LEASH} blocks from the guard's own slot.
     */
    private void step(ServerLevel level, float[] d) {
        if (Math.abs(d[0]) + Math.abs(d[1]) < 1.0E-5F) return;
        float yaw = settler.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        double lx = Mth.cos(yaw);
        double lz = Mth.sin(yaw);
        Vec3 here = settler.position();
        Vec3 next = here.add(lx * d[0] + fx * d[1], 0.0D, lz * d[0] + fz * d[1]);
        Vec3 anchor = assignment.partner() != null ? assignment.partner().position()
            : assignment.faceTowards() != null ? Vec3.atBottomCenterOf(assignment.faceTowards()) : null;
        if (anchor != null) {
            double cur = horizontal(here, anchor);
            double gap = horizontal(next, anchor);
            if (d[1] == 0.0F && gap > 1.0E-3D) {
                // Circling: stay on the ring round the partner.
                Vec3 radial = new Vec3(next.x - anchor.x, 0.0D, next.z - anchor.z).normalize().scale(cur);
                next = new Vec3(anchor.x + radial.x, next.y, anchor.z + radial.z);
                gap = cur;
            }
            if (assignment.partner() != null
                && (gap < MIN_GAP && gap < cur || gap > MAX_GAP && gap > cur)) {
                return;
            }
        }
        Vec3 centre = Vec3.atBottomCenterOf(slot);
        double fromSlot = horizontal(next, centre);
        if (fromSlot > LEASH && fromSlot > horizontal(here, centre)) return;
        net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(next.x, here.y + 0.01D, next.z);
        if (!feet.equals(settler.blockPosition()) && !GuardDrillYard.standable(level, feet)) return;
        settler.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(next.x - here.x, 0.0D, next.z - here.z));
    }

    /** A cut's wind-up closes the distance to the strike gap (never below it), feet stepping in. */
    private void close(ServerLevel level, float elapsed) {
        SettlerEntity partner = assignment.partner();
        if (partner == null || !GuardDrillScript.closing(plan, assignment.slot(), elapsed)) return;
        double gap = horizontal(settler.position(), partner.position());
        double want = Math.min(gap - GuardDrillScript.STRIKE_GAP, GuardDrillScript.CLOSE_PER_TICK);
        if (want > 0.005D) step(level, new float[] {0.0F, (float) want});
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Blows landing in (from, to]: a quiet practice clash, and a Strength rep for those involved. */
    private void beats(ServerLevel level, float from, float to) {
        int slotId = assignment.slot();
        for (GuardDrillScript.Contact c : GuardDrillScript.contactsBetween(plan, from, to)) {
            if (GuardDrillScript.involved(c, slotId)) {
                float units = GuardDrillRules.reps(repsPaid, 1);
                if (units > 0.0F) {
                    repsPaid += units;
                    settler.train(Attribute.STRENGTH, GuardRank.TRAIN_DRILL * units);
                }
            }
            // One sound per blow: the attacker's side plays it (the solo guard its own swish).
            if (c.attacker() != slotId) continue;
            Vec3 at = assignment.partner() != null
                ? settler.position().add(assignment.partner().position()).scale(0.5D)
                : settler.position();
            float pitch = 0.95F + settler.getRandom().nextFloat() * 0.25F;
            if (c.feint()) {
                level.playSound(null, at.x, at.y + 1.2D, at.z, ModSounds.COMBAT_SWING_LIGHT.get(),
                    SoundSource.NEUTRAL, 0.14F, pitch + 0.1F);
            } else if (c.answer() == GuardDrillScript.Answer.PARRY || c.answer() == GuardDrillScript.Answer.STUMBLE) {
                level.playSound(null, at.x, at.y + 1.2D, at.z, ModSounds.BLADE_HIT.get(), SoundSource.NEUTRAL,
                    0.28F, pitch + 0.15F);
            } else {
                level.playSound(null, at.x, at.y + 1.2D, at.z, ModSounds.COMBAT_SWING_LIGHT.get(),
                    SoundSource.NEUTRAL, 0.22F, pitch);
            }
        }
    }

    private void setCue(CompoundTag next) {
        cue = next;
        settler.setGuardDrillCue(next);
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        setCue(new CompoundTag());
        if (settler.getActivity() == SettlerActivity.PATROLLING
            || settler.getActivity() == SettlerActivity.TRAVELING) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        Settlement settlement = settler.settlement();
        if (endReason != null && settlement != null) {
            if ("done".equals(endReason) && sparTicks >= GuardDrillRules.MIN_SPAR_TICKS) {
                // A finished morning: a small combat XP award that stops at the Trained tier.
                int xp = GuardDrillRules.sessionXp(settler.combatExperience());
                if (xp > 0) settler.awardCombatExperience(xp);
            }
            GuardDrillYard.finish(settlement, settler, endReason + (unfitWhy == null ? "" : ":" + unfitWhy));
            sparTicks = 0;
            repsPaid = 0.0F;
        } else if (settlement != null && settler.level() instanceof ServerLevel level) {
            // Pre-empted (a door, a salute...): still a member, resumes at the next check.
            GuardDrillYard.heartbeat(level, settlement, settler, false);
            nextCheck = 0L;
        }
        endReason = null;
        plan = null;
        assignment = null;
    }

    /** GameTest view: ticks this guard has spent sparring in the current session. */
    public int sparTicks() {
        return sparTicks;
    }
}

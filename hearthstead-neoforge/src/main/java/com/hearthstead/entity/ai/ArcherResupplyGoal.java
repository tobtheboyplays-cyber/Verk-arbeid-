package com.hearthstead.entity.ai;

import com.hearthstead.block.ArrowBarrelBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.BannerTeams;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Quiver top-up (owner, 27 Sep: "archers kan baere 6 piler, sa ma de lope a
 * hente piler i piltonna").
 *
 * <p>An archer carries only {@link ArcherAttackGoal#quiverCapacity} arrows
 * (Recruit 6 .. Master Archer 12, +2 at a level-2+ Watchtower). In battle a
 * dry archer holds the line ({@link ArcherAttackGoal}).
 *
 * <p><b>Source.</b> The nearest Arrow Barrel in the settlement that holds
 * arrows ({@link ArrowBarrelBlockEntity}), else the Watchtower's own chests
 * (the rack). Custody is exact either way: every arrow accepted into the
 * persisted quiver is removed from the source in the same step, and the
 * quiver stays owned by the archer's Watchtower.
 *
 * <p><b>Peacetime</b> (priority 3): once there has been no target, raid or
 * alarm for {@link #CALM_TICKS} ticks, a short archer walks to the source,
 * refills with a pickup beat and the quiver rustle, then steps aside so his
 * post/order goal walks him back. Never with a target, raid, alarm, summons,
 * live field order, asleep, or with nothing to take (so it cannot loop).
 *
 * <p><b>Called</b> (priority 1): the player's RESUPPLY field order
 * ({@link #call}) or the Arrow Barrel's button ({@link #callTo}, that barrel).
 * Only a player call pulls a short archer out of the line mid-battle; the
 * call is never an assignment, so the order he held is untouched and he
 * returns to it. A full archer ignores it; a call not started within
 * {@link #CALL_TTL_TICKS} lapses; a trip gives up after
 * {@link #CALLED_GIVE_UP_TICKS}.
 */
public class ArcherResupplyGoal extends Goal {
    /** "No target for about 10 s" before a peacetime refill. */
    public static final int CALM_TICKS = 200;
    private static final int CHECK_INTERVAL = 20;
    private static final int GIVE_UP_TICKS = 600;
    private static final int FAIL_BACKOFF_TICKS = 1200;
    private static final int REPATH_TICKS = 20;
    /** The refill beat at the source (archer_refill clip: 1.80 s), then back to the post. */
    public static final int STOW_TICKS = 36;
    /** The bundle slides into the quiver at 1.20 s: the rustle lands there. */
    private static final int RUSTLE_AT = 24;
    public static final int CALL_TTL_TICKS = 200;
    public static final int CALLED_GIVE_UP_TICKS = 400;
    private static final double CALLED_SPEED = 1.3D;
    /** How close counts as "at the barrel" (squared blocks). */
    private static final double BARREL_REACH_SQR = 1.8D * 1.8D;
    /** Path ended (the barrel is a solid block): this close still counts. */
    private static final double BARREL_SETTLE_SQR = 3.0D * 3.0D;
    /** Barrels further than this from the archer are not considered. */
    private static final double BARREL_SEARCH = 64.0D;
    public static final int RETURN_TTL_TICKS = 400;

    private record Call(long until, @Nullable BlockPos barrel) {
    }

    /** Server-thread only: pending calls. */
    private static final Map<SettlerEntity, Call> CALLS = new WeakHashMap<>();
    /** Server-thread only: finished a call and heading back to the field slot, -> expiry. */
    private static final Map<SettlerEntity, Long> RETURNING = new WeakHashMap<>();

    /** Whether this archer finished a call and is still heading back to his slot. */
    public static boolean returning(SettlerEntity archer) {
        Long until = RETURNING.get(archer);
        if (until == null) return false;
        if (archer.level().getGameTime() > until) {
            RETURNING.remove(archer);
            return false;
        }
        return true;
    }

    /** Back in his slot (or no slot to return to): the return leg is over. */
    public static void arrived(SettlerEntity archer) {
        RETURNING.remove(archer);
    }

    /** RESUPPLY field order: nearest barrel, else the rack. @return how many were short. */
    public static int call(Collection<SettlerEntity> archers, long now) {
        return callTo(archers, now, null);
    }

    /** The Arrow Barrel's button: these archers run to THAT barrel. @return how many were short. */
    public static int callTo(Collection<SettlerEntity> archers, long now, @Nullable BlockPos barrel) {
        int short_ = 0;
        for (SettlerEntity archer : archers) {
            if (archer.getProfession() != Profession.ARCHER) continue;
            CALLS.put(archer, new Call(now + CALL_TTL_TICKS, barrel == null ? null : barrel.immutable()));
            if (archer.archerQuiverCount() < ArcherAttackGoal.quiverCapacity(archer)) short_++;
        }
        return short_;
    }

    /** Test seam: whether a call is pending for this archer. */
    public static boolean called(SettlerEntity archer) {
        Call call = CALLS.get(archer);
        return call != null && archer.level().getGameTime() <= call.until();
    }

    /** Nearest loaded Arrow Barrel with arrows in the archer's settlement, or null. */
    @Nullable
    public static BlockPos nearestBarrel(ServerLevel level, SettlerEntity archer) {
        Settlement settlement = archer.settlement();
        if (settlement == null || settlement.center == null) return null;
        double claim = settlement.radius;
        BlockPos best = null;
        double bestSqr = BARREL_SEARCH * BARREL_SEARCH;
        int seen = 0;
        for (BlockPos pos : ArrowBarrelBlockEntity.loaded(level)) {
            if (++seen > 256) break;
            if (pos.distSqr(settlement.center) > claim * claim) continue;
            double d = archer.blockPosition().distSqr(pos);
            if (d >= bestSqr) continue;
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ArrowBarrelBlockEntity barrel
                && barrel.arrows() > 0) {
                best = pos;
                bestSqr = d;
            }
        }
        return best;
    }

    private final boolean calledMode;
    private final SettlerEntity settler;
    private long lastBusyTick = Long.MIN_VALUE / 2;
    private long nextCheckTick;
    private long backoffUntil;
    private Building tower;
    @Nullable
    private BlockPos barrel;
    private int ticks;
    private int repath;
    private int stowTicks;
    private boolean done;

    public ArcherResupplyGoal(SettlerEntity settler) {
        this(settler, false);
    }

    public ArcherResupplyGoal(SettlerEntity settler, boolean calledMode) {
        this.settler = settler;
        this.calledMode = calledMode;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.ARCHER
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        if (calledMode) {
            Call call = CALLS.get(settler);
            if (call == null) {
                return false;
            }
            if (now > call.until()) {
                CALLS.remove(settler);
                return false;
            }
            if (!chooseSource(level, call.barrel(), true)) {
                return false; // retried until the call lapses (e.g. mid-leap)
            }
            CALLS.remove(settler);
            return true;
        }
        if (settler.getTarget() != null
            || settler.getActivity() == SettlerActivity.COMBAT
            || settler.getActivity() == SettlerActivity.OUT_OF_AMMO) {
            lastBusyTick = now;
            return false;
        }
        if (now < nextCheckTick || now < backoffUntil || now - lastBusyTick < CALM_TICKS) {
            return false;
        }
        nextCheckTick = now + CHECK_INTERVAL;
        if (!settler.isBound() || settler.isSleeping()
            || Summons.active(settler) || BannerTeams.active(settler) != null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || BlessingEffects.raidActive(settlement)
            || settlement.alertActive(now)) {
            return false;
        }
        return chooseSource(level, null, false);
    }

    /**
     * Picks the tower (custody owner) and the source: {@code wanted} barrel if
     * given, else the nearest barrel with arrows, else the tower rack.
     */
    private boolean chooseSource(ServerLevel level, @Nullable BlockPos wanted, boolean called) {
        tower = null;
        barrel = null;
        if (!settler.isBound() || settler.isSleeping()
            || !EquipmentRequests.readyForProfession(level, settler, Profession.ARCHER)) {
            return false;
        }
        Building found = ArcherAttackGoal.towerOf(settler);
        if (found == null) {
            return false;
        }
        int carried = settler.archerQuiverCount();
        if ((carried > 0 && !settler.archerQuiverOwnedBy(found.id))
            || carried >= ArcherAttackGoal.quiverCapacity(settler)) {
            if (called) CALLS.remove(settler); // full (or foreign shafts): the call does not apply
            return false;
        }
        BlockPos source = null;
        if (wanted != null) {
            if (level.getBlockEntity(wanted) instanceof ArrowBarrelBlockEntity b && b.arrows() > 0) {
                source = wanted;
            }
        } else {
            source = nearestBarrel(level, settler);
        }
        if (source == null && (wanted != null || found.anchor == null
            || ArcherAttackGoal.rackArrows(level, found) <= 0)) {
            if (called) CALLS.remove(settler); // nothing to take: holding the line beats a wasted run
            return false;
        }
        tower = found;
        barrel = source;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (done || tower == null || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        if (calledMode) {
            return true;
        }
        if (settler.getTarget() != null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return settlement != null && !BlessingEffects.raidActive(settlement)
            && !settlement.alertActive(level.getGameTime())
            && BannerTeams.active(settler) == null && !Summons.active(settler);
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
        stowTicks = -1;
        done = false;
        settler.setActivity(SettlerActivity.PATROLLING);
    }

    @Override
    public void stop() {
        // Returning is owed after every started player-call, including an empty
        // source, timeout or interruption; failing to refill must not erase the line.
        if (calledMode && tower != null) {
            RETURNING.put(settler, settler.level().getGameTime() + RETURN_TTL_TICKS);
        }
        settler.getNavigation().stop();
        if (settler.getActivity() == SettlerActivity.PATROLLING) {
            settler.setActivity(SettlerActivity.IDLE);
        }
        tower = null;
        barrel = null;
    }

    @Override
    public void tick() {
        if (tower == null || !(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        ticks++;
        if (stowTicks >= 0) {
            settler.getNavigation().stop();
            if (STOW_TICKS - stowTicks == RUSTLE_AT) {
                level.playSound(null, settler.blockPosition(),
                    com.hearthstead.registry.ModSounds.WORK_QUIVER_RUSTLE.get(), SoundSource.NEUTRAL,
                    0.6F, 0.92F + settler.getRandom().nextFloat() * 0.16F);
            }
            if (--stowTicks <= 0) {
                done = true;
            }
            return;
        }
        BlockPos goal = barrel != null ? barrel : tower.anchor;
        if (goal == null) {
            done = true;
            return;
        }
        double barrelSqr = barrel == null ? 0.0D
            : settler.distanceToSqr(barrel.getX() + 0.5D, barrel.getY(), barrel.getZ() + 0.5D);
        boolean arrived = barrel != null
            ? barrelSqr <= BARREL_REACH_SQR
                || (ticks > REPATH_TICKS && settler.getNavigation().isDone() && barrelSqr <= BARREL_SETTLE_SQR)
            : ArcherAttackGoal.nearTower(settler, tower);
        if (arrived) {
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(goal.getX() + 0.5D, goal.getY() + 0.5D, goal.getZ() + 0.5D);
            int moved = barrel != null ? refillFromBarrel(level, settler, tower, barrel)
                : ArcherAttackGoal.refillFromRack(level, settler, tower);
            if (moved > 0) {
                playRefillBeat(level, settler);
                stowTicks = STOW_TICKS;
            } else {
                backoffUntil = level.getGameTime() + FAIL_BACKOFF_TICKS;
                done = true;
            }
            return;
        }
        if (ticks >= (calledMode ? CALLED_GIVE_UP_TICKS : GIVE_UP_TICKS)) {
            settler.recordRouteFailure("archer_resupply_unreachable");
            backoffUntil = level.getGameTime() + FAIL_BACKOFF_TICKS;
            done = true;
            return;
        }
        if (--repath <= 0) {
            repath = REPATH_TICKS;
            settler.getNavigation().moveTo(goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D,
                calledMode ? CALLED_SPEED : 1.0D);
        }
    }

    /**
     * Tops the quiver up from an Arrow Barrel, arrow for arrow: the quiver
     * accepts first (bounded by capacity and what the barrel holds), then
     * exactly that many leave the barrel. Returns how many moved.
     */
    public static int refillFromBarrel(ServerLevel level, SettlerEntity settler, Building tower, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ArrowBarrelBlockEntity source)) return 0;
        if (settler.archerQuiverCount() > 0 && !settler.archerQuiverOwnedBy(tower.id)) return 0;
        int want = Math.min(ArcherAttackGoal.quiverCapacity(settler) - settler.archerQuiverCount(),
            source.arrows());
        if (want <= 0) return 0;
        int accepted = settler.storeArcherQuiverArrows(tower.id, want);
        int taken = source.takeArrows(accepted);
        if (taken < accepted) {
            // Never mint: give back whatever the barrel could not cover.
            settler.takeArcherQuiverArrows(accepted - taken);
        }
        return taken;
    }

    /**
     * The refill beat: the pickup/stow clip (fallback until the dedicated
     * archer_refill clip lands); the quiver rustle plays at {@link #RUSTLE_AT}.
     */
    private static void playRefillBeat(ServerLevel level, SettlerEntity settler) {
        settler.triggerPickup();
    }

    /** Test seam: whether a refill trip is in its stow beat. */
    public boolean stowing() {
        return stowTicks > 0;
    }
}

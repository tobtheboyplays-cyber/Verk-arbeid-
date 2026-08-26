package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * The BLOD objective's own goal: "Raiders hunt settlers wherever they are"
 * (DESIGN.md system 5), which until now was not actually true.
 *
 * <h2>What was missing</h2>
 *
 * <p>Every raider's {@code objectivePos} is set once, at spawn, to the
 * settlement centre ({@code RaidDirector#spawnBand}), and nothing ever
 * moved a raider toward it. {@code RaiderEntity#registerGoals} has no
 * stroll/approach goal at all -- a raider only ever moves once vanilla's own
 * target selector hands it a LIVE, VISIBLE target ({@code mustSee=true} on
 * both {@code NearestAttackableTargetGoal}s) or once {@code RaiderLootGoal}
 * (KORN only) gives it a chest to walk to. A BLOD raider that never
 * happens to see a settler or a player therefore just stood at its spawn
 * point -- 26-38 blocks out, at the treeline -- for the entire raid. This
 * goal is that missing movement, scoped to the one objective the design
 * names for it.
 *
 * <h2>Bounded means bounded</h2>
 *
 * <p>The scan is a single {@code getEntitiesOfClass} box query (the same
 * discipline {@code RaidDirector#livingRaidersOf} already uses for raiders
 * themselves), never a flood fill and never per-tick unbounded work: it
 * only runs once every {@link #RESCAN_INTERVAL} ticks, and its box is
 * capped at {@code settlement.radius + HUNT_SCAN_MARGIN} -- deliberately
 * narrower than the raid's own "is it still in progress" bound
 * ({@code RaidDirector#RAID_BOUNDS_MARGIN}, 48), because a raider
 * <em>searching</em> should read as covering the settlement and its
 * immediate edge, not sweeping the same wide radius the director uses to
 * decide whether the raid is even still on. Scoped to this settlement's own
 * people via {@link RaiderEntity#isMyWar}, the exact rule the target
 * selector itself uses, so a hunting raider can never be lured into another
 * settlement's test arena the way KF-027 caught a live target doing.
 *
 * <h2>Feeds the breach goal that already exists</h2>
 *
 * <p>This goal never touches {@link RaiderBreachGoal} directly -- it only
 * keeps {@code raider.objectivePos()} pointed at the nearest settler it
 * knows about (or the settlement centre, once none is found), which is
 * exactly the fallback {@link RaiderBreachGoal#destinationFor} already
 * reads when there is no live combat target. A hunting raider that finds
 * its quarry behind a closed door therefore breaches it through the same
 * mechanism a KORN raider does, with no new coupling between the two goals.
 *
 * <h2>Reading as a hunt, not a parade</h2>
 *
 * <p>DESIGN.md's whole point in naming this a hunt is that it must look
 * like one from across the plaza. A raider that beelines continuously reads
 * as a mob walking to a waypoint; nine of them doing that at once reads as
 * a parade. So this goal never closes distance in one unbroken walk: it
 * alternates short {@link #APPROACH_TICKS} bursts of movement with
 * {@link #PAUSE_TICKS} stops. A stopped raider is already, for free, doing
 * {@code RaiderEntity}'s {@code MENACE_IDLE} -- "rolling shoulders, head
 * hunting side to side" ({@code RaiderEntity#setupRaiderAnimationStates}),
 * gated purely on not moving -- so the stalk-pause-stalk rhythm this goal
 * imposes is the whole trick: no new animation state is needed, only a
 * reason for an existing one to keep firing while the raider is still very
 * much doing something. {@link #PAUSE_TICKS} is kept comfortably under
 * {@link RaiderBreachGoal#STUCK_THRESHOLD_TICKS} (40) so a deliberate
 * search-pause can never itself be mistaken for being blocked and trigger a
 * spurious breach — the raider always covers real ground during the
 * approach burst between pauses, which resets that goal's own stuck window.
 */
public class RaiderHuntGoal extends Goal {

    /** How far past the settlement's own radius the search still looks --
     * narrower than {@code RaidDirector#RAID_BOUNDS_MARGIN} (48) on purpose;
     * see the class doc's "bounded means bounded" section. */
    private static final int HUNT_SCAN_MARGIN = 16;
    /** How often the bounded scan re-runs. Cheap (one box query) but still
     * not free, so this is a cooldown, never a per-tick cost. */
    private static final int RESCAN_INTERVAL = 30;

    /** One stalking cycle: close distance, then stop and read as menace.
     * Pause is kept well under {@code RaiderBreachGoal.STUCK_THRESHOLD_TICKS}
     * (40) -- see the class doc's last section for exactly why. */
    private static final int APPROACH_TICKS = 60;
    private static final int PAUSE_TICKS = 24;

    private static final double MOVE_SPEED = 1.0;
    private static final int REPATH_INTERVAL = 15;

    private enum Phase { APPROACHING, SEARCHING }

    private final RaiderEntity raider;
    private Phase phase = Phase.SEARCHING;
    private int phaseTicks;
    private int rescanTimer;
    private int repathTimer;

    public RaiderHuntGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (raider.isScout() || raider.objective() != RaidObjective.BLOD) {
            return false;
        }
        // A live target already means MeleeAttackGoal (priority 3) is the
        // right goal; handing it MOVE/LOOK here would fight that goal for
        // the same flags every tick. Same handoff RaiderScoutGoal documents.
        if (raider.getTarget() != null) {
            return false;
        }
        Settlement settlement = raider.settlement();
        return settlement != null && settlement.pendingRaid != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!raider.isAlive() || raider.isScout() || raider.getTarget() != null) {
            return false; // getTarget() != null: hand MOVE/LOOK to MeleeAttackGoal at once
        }
        Settlement settlement = raider.settlement();
        return settlement != null && settlement.pendingRaid != null
            && raider.objective() == RaidObjective.BLOD;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        phase = Phase.SEARCHING;
        phaseTicks = 0;
        rescanTimer = 0;
        repathTimer = 0;
    }

    @Override
    public void stop() {
        raider.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(raider.level() instanceof ServerLevel level)) {
            return;
        }
        Settlement settlement = raider.settlement();
        if (settlement == null) {
            return;
        }
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            BlockPos found = nearestLivingSettler(level, settlement);
            // Nothing found: keep advancing on the settlement itself rather
            // than idling forever at the treeline -- the same honest
            // fallback RaidDirector#spawnBand seeds objectivePos with.
            raider.setObjectivePos(found != null ? found : settlement.center);
        }

        phaseTicks++;
        switch (phase) {
            case SEARCHING -> tickSearching();
            case APPROACHING -> tickApproaching();
        }
    }

    private void tickSearching() {
        raider.getNavigation().stop(); // stationary -> MENACE_IDLE plays itself
        if (phaseTicks >= PAUSE_TICKS) {
            phase = Phase.APPROACHING;
            phaseTicks = 0;
            repathTimer = 0;
        }
    }

    private void tickApproaching() {
        BlockPos dest = raider.objectivePos();
        if (dest == null) {
            phase = Phase.SEARCHING;
            phaseTicks = 0;
            return;
        }
        raider.getLookControl().setLookAt(dest.getX() + 0.5, dest.getY() + 1.0, dest.getZ() + 0.5);
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            raider.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5,
                MOVE_SPEED);
        }
        if (phaseTicks >= APPROACH_TICKS) {
            phase = Phase.SEARCHING;
            phaseTicks = 0;
        }
    }

    /**
     * The nearest living settler of this raider's own settlement, within a
     * single bounded box query -- see the class doc's "bounded means
     * bounded" section for why the box is capped the way it is.
     */
    @Nullable
    private BlockPos nearestLivingSettler(ServerLevel level, Settlement settlement) {
        double reach = settlement.radius + HUNT_SCAN_MARGIN;
        AABB box = new AABB(settlement.center).inflate(reach);
        List<SettlerEntity> candidates = level.getEntitiesOfClass(SettlerEntity.class, box,
            s -> s.isAlive() && raider.isMyWar(s));
        BlockPos origin = raider.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (SettlerEntity settler : candidates) {
            double d = origin.distSqr(settler.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                best = settler.blockPosition().immutable();
            }
        }
        return best;
    }

    /** Test seam: which half of the stalk-pause-stalk cycle this raider is in. */
    public String debugPhase() {
        return phase.name();
    }
}

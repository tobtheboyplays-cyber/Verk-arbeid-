package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * The BRANN objective's own goal: "Raiders ... commit arson" (DESIGN.md
 * system 5), which until now had a mechanism ({@link
 * com.hearthstead.settlement.raid.RaidDirector#tickArson}) but nothing to
 * ever put a raider in range of it.
 *
 * <h2>What was missing (the KF-031 shape, this time for fire)</h2>
 *
 * <p>{@code RaidDirector#tickArson} only torches a block once a BRANN band
 * is already standing within {@code ARSON_REACH} (4) of a building's
 * bounds -- but {@code RaiderEntity#registerGoals} has no goal that ever
 * moves a raider there. Exactly as {@link RaiderHuntGoal}'s class doc found
 * for BLOD, a raider only ever moves once vanilla's own target selector
 * hands it a LIVE, VISIBLE target, or once {@code RaiderLootGoal} (KORN
 * only) gives it a chest to walk to. A BRANN raider that never happens to
 * see a settler or a player therefore just stands at its spawn point for
 * the entire raid, and its objective silently does nothing -- the same
 * class of defect the ransom raid that never took anybody was (KF-031),
 * now found in the fire raid that never burns anything. This goal is that
 * missing movement, scoped to the one objective the design names it for.
 *
 * <h2>This is {@link RaiderHuntGoal}, retargeted at buildings</h2>
 *
 * <p>Deliberately the same shape: a bounded rescan that keeps {@code
 * raider.objectivePos()} pointed at the nearest candidate, alternating
 * short approach bursts with pauses so a band spreading out toward several
 * buildings reads as a raid, not a parade (see {@link RaiderHuntGoal}'s own
 * "reading as a hunt, not a parade" section -- it applies here verbatim).
 * The only real difference is what counts as a candidate: the nearest
 * living settler of this raider's own war, there; the nearest standing
 * building of this raider's own settlement, here.
 *
 * <h2>Bounded means bounded, and needs no {@code isMyWar} of its own</h2>
 *
 * <p>{@link RaiderHuntGoal} bounds its search with a capped world-space box
 * query and scopes it with {@link RaiderEntity#isMyWar} because its
 * candidates are roaming {@code LivingEntity}s that could belong to any
 * settlement standing anywhere nearby -- exactly the cross-settlement leak
 * KF-027 caught live. Buildings carry no equivalent risk: {@code
 * settlement.buildings} is not a world query at all, it is the specific
 * {@link Settlement} instance's OWN list (populated only by that
 * settlement's own plaques, {@code PlaqueBlockEntity}), so every candidate
 * this goal ever sees is already, by construction, this raider's own
 * settlement's building. Reusing {@code isMyWar} here would be exactly the
 * "second copy of one rule" KF-027 warns against, applied to a check this
 * loop cannot fail -- there is no owner field to compare, because the
 * collection itself IS the scope. The loop is still bounded, the same
 * discipline {@code RaidDirector#tickArson} already applies to this exact
 * list: a plain iteration over one settlement's own (small, plaque-built)
 * building roster, never a flood fill and never a world-wide scan.
 *
 * <h2>Feeds the breach goal that already exists</h2>
 *
 * <p>Same handoff as {@link RaiderHuntGoal}: this goal never touches
 * {@link RaiderBreachGoal} directly, it only keeps {@code
 * raider.objectivePos()} pointed at the target building's own centre --
 * deliberately a point INSIDE the building, not merely adjacent to it, so a
 * raider that finds its target behind a closed wall breaches through
 * exactly like a KORN raider does, with no new coupling between the two
 * goals. In the common case the raider never needs to: {@code
 * RaidDirector#tickArson}'s own reach check (bounds inflated by {@code
 * ARSON_REACH}, 4) is satisfied well before the raider actually reaches the
 * centre, so most bands torch the building from just outside it.
 */
public class RaiderArsonGoal extends Goal {

    /** How often the bounded scan re-runs. Cheap (one list walk over this
     * settlement's own buildings, no world query) but still not free, so
     * this is a cooldown, never a per-tick cost -- same cadence {@link
     * RaiderHuntGoal#RESCAN_INTERVAL} uses for its own settler scan. */
    private static final int RESCAN_INTERVAL = 30;

    /** One stalking cycle: close distance, then stop and read as menace.
     * Identical to {@link RaiderHuntGoal}'s own cycle -- see that class doc's
     * "reading as a hunt, not a parade" section for why. */
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

    public RaiderArsonGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (raider.isScout() || raider.objective() != RaidObjective.BRANN) {
            return false;
        }
        // A live target already means MeleeAttackGoal (priority 3) is the
        // right goal; handing it MOVE/LOOK here would fight that goal for
        // the same flags every tick. Same handoff RaiderHuntGoal documents.
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
            && raider.objective() == RaidObjective.BRANN;
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
        if (!(raider.level() instanceof ServerLevel)) {
            return;
        }
        Settlement settlement = raider.settlement();
        if (settlement == null) {
            return;
        }
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            BlockPos found = nearestBurnableBuilding(settlement);
            // Nothing found: keep advancing on the settlement itself rather
            // than idling forever at the treeline -- the same honest
            // fallback RaiderHuntGoal falls back to, and RaidDirector#spawnBand
            // seeds objectivePos with in the first place.
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
     * The nearest standing building of this raider's own settlement -- see
     * the class doc's "needs no isMyWar of its own" section for why {@code
     * settlement.buildings} already IS the scoped, bounded collection this
     * search needs, with nothing further to check or query.
     */
    @Nullable
    private BlockPos nearestBurnableBuilding(Settlement settlement) {
        BlockPos origin = raider.blockPosition();
        List<Building> buildings = settlement.buildings;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : buildings) {
            if (!building.valid || building.bounds == null) {
                continue; // a dissolved or not-yet-scanned building has nothing to burn
            }
            BlockPos center = building.bounds.getCenter();
            double d = origin.distSqr(center);
            if (d < bestDist) {
                bestDist = d;
                best = center;
            }
        }
        return best;
    }

    /** Test seam: which half of the stalk-pause-stalk cycle this raider is in. */
    public String debugPhase() {
        return phase.name();
    }
}

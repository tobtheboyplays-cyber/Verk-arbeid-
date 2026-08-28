package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * The other half of DESIGN.md system 5 that {@code RaiderLootGoal} alone
 * never delivered: "Raiders hunt settlers, breach gates/barricades ...,
 * steal from real chests, commit arson". Theft and arson already existed
 * ({@code RaiderLootGoal}, {@code RaidDirector#tickArson}) — this is the
 * missing "breach": a raider that cannot get where it is going stops
 * milling about at a closed door or a wall and starts chopping through it.
 *
 * <h2>Detecting "blocked" without watching any other goal</h2>
 *
 * <p>This goal never inspects what {@code RaiderLootGoal} or the target
 * selector are privately doing — it only ever watches what vanilla already
 * tracks on every entity: whether it is standing still, and whether it is
 * still short of where it actually wants to be ({@link #destinationFor},
 * never {@code RaiderLootGoal}'s private chest target). A raider that
 * stopped on purpose (it reached what it actually wanted, e.g. a chest to
 * loot) is never mistaken for stuck, because it is standing still
 * <em>at</em> its destination — within {@link #REACH_SQR} of it — and the
 * "already there" check in {@link #canUse()} rules it out before the
 * stationary window is ever armed at all. Only once the raider has been
 * stationary for {@link #STUCK_THRESHOLD_TICKS} while still wanting to be
 * somewhere it is not, AND something within reach is actually breachable
 * ({@link #findBreachCandidate}, the real backstop against a false
 * positive), does it look for something to break.
 *
 * <p><b>Collision is a hint, never a precondition.</b> An earlier version
 * of this goal also required {@code Entity#horizontalCollision} to have
 * fired at least once in the stationary window — the same signal vanilla's
 * own {@code DoorInteractGoal} uses to know a mob has bumped into
 * something solid. That is wrong for exactly the case this goal exists to
 * handle: a closed IRON door. {@code WalkNodeEvaluator} marks {@code
 * DOOR_IRON_CLOSED} a closed node and excludes it as a neighbour outright
 * (mobs cannot open iron doors, vanilla or otherwise), so the pathfinder
 * never routes through it at all — it returns a PARTIAL path ending one
 * cell short, the raider walks that to completion, and stands there
 * motionless, having pressed into nothing. A raider in exactly that state
 * would never have satisfied a collision requirement and would stand there
 * for the rest of the raid. Collision, when it IS observed, only shortens
 * the wait ({@link #COLLISION_CONFIRM_TICKS}) — a raider that audibly
 * bumps something is certainly blocked and does not need the full window
 * to prove it, but the absence of a bump proves nothing either way.
 *
 * <h2>Doors first, a wall only if none is adjacent</h2>
 *
 * <p>The search is a small bounded cube around the raider (never a flood
 * fill — INV: all world scanning is budgeted) out to {@link #REACH_SQR}. A
 * closed door within that reach always wins over a wall block, because a
 * raider that goes straight for the door is the honest, readable raid; a
 * wall is only chopped when no door is reachable. Both halves of a door are
 * scarred and destroyed together — breaking only the lower half would
 * leave a floating upper half behind, which is not what "breaching a door"
 * means to a player watching it happen.
 *
 * <h2>The scar contract</h2>
 *
 * <p>{@link RaidDirector#recordScar} is called on every block this goal
 * destroys, for its exact pre-break {@link BlockState}, strictly before
 * {@code Level#destroyBlock} runs — the ordering
 * {@link RaidDirector}'s own class doc demands, so {@code RepairWorkGoal}
 * always has the true original to stand back up.
 *
 * <h2>Never a rampage</h2>
 *
 * <p>A block entity anywhere in the candidate set is skipped outright
 * (chests are looted or spared, never smashed — see {@code
 * RaiderLootGoal} for the honest way in), and this raider stops offering
 * itself as low-hanging fruit for the search once it has broken {@link
 * #MAX_BREAKS_PER_RAIDER} blocks. The counter lives on the goal instance,
 * not in any saved data: it is not meant to survive a save/reload mid-raid
 * (a raider that lost its chopping progress on a reload has lost nothing
 * the player can exploit — the block is still standing), and a raider's
 * lifetime is exactly one raid by construction ({@code RaidDirector#spawnBand}
 * assigns it once), so "per raider" and "per raid" are the same count here.
 * Scoped to an ACTIVE raid throughout ({@code settlement.pendingRaid !=
 * null}), the same gate {@code RaidDirector#tickArson} uses, so a raider
 * that lingers after the band is driven off never keeps swinging.
 */
public class RaiderBreachGoal extends Goal {

    /** A raider stops offering to breach after this many blocks — a handful,
     * not a demolition crew. Band-wide this is soft: {@link RaidDirector#MAX_BAND}
     * raiders each capped here means the theoretical ceiling is high, but in
     * practice only the raiders that actually get stuck ever swing at all,
     * and most either walk straight in or die fighting first. */
    public static final int MAX_BREAKS_PER_RAIDER = 3;

    /** How long the raider must sit essentially still, still wanting to be
     * elsewhere, before "not moving yet" becomes "blocked". Short enough that
     * a raid does not read as raiders standing around; long enough that an
     * ordinary one-tick pathing hiccup never trips it. The only threshold a
     * raider that never collides (see the class doc's iron-door case) ever
     * gets judged against. */
    private static final int STUCK_THRESHOLD_TICKS = 40;
    /** A raider that HAS visibly collided needs only this much confirmation
     * — vanilla's own "bumped into something solid" signal is trustworthy on
     * its own, so there is no reason to make it wait as long as a raider the
     * pathfinder simply stopped short of, with nothing to press into. Purely
     * a faster path to the same conclusion {@link #STUCK_THRESHOLD_TICKS}
     * reaches anyway — never required, see the class doc. */
    private static final int COLLISION_CONFIRM_TICKS = 10;
    /** Movement under this (squared) between two samples still counts as
     * "not moving" — forgiving enough that the small in-place jitter of a
     * mob pressed against a wall does not keep resetting the timer. */
    private static final double STUCK_EPS_SQR = 0.36;

    /** How close a candidate block must be to be "within reach" — matches
     * {@code RaiderLootGoal}'s own reach, so looting and breaching read as
     * the same kind of raider, not two different reach values. */
    private static final double REACH_SQR = 6.25;
    /** Half-extent of the bounded cube searched for a candidate. Bigger than
     * {@link #REACH_SQR} strictly requires so the reach filter (not the
     * loop bounds) is what actually shapes the search — still a small,
     * fixed box, never a flood fill. */
    private static final int SEARCH_RADIUS = 3;
    /** Reach at which the raider can actually swing at its target. */
    private static final double WORK_RANGE = 3.0;

    /** Hits to break a door — quick, because a door is meant to be the
     * honest, readable way in: a raider that finds one goes through it fast. */
    private static final int DOOR_HITS = 3;
    /** Hits to break a wall block — double a door's, so raiders strongly
     * prefer the door whenever one is reachable, and a wall breach reads as
     * the harder, slower thing it should be. */
    private static final int WALL_HITS = 6;
    /** Ticks per swing (one per second), the same cadence {@code
     * LumbererWorkGoal} chops trees on, so every "something is being chopped
     * in this mod" sounds like the same rhythm. */
    private static final int SWING_PERIOD = 20;
    /** The tick within {@link #SWING_PERIOD} the strike (and its sound)
     * lands on — identical offset to the lumberjack's, for the same reason. */
    private static final int SWING_CONTACT = 11;

    /** Walking pace while closing the last few blocks onto the target. */
    private static final double MOVE_SPEED = 1.0;
    /** How long the raider will try to close distance on an in-reach target
     * before giving up on it (knockback, a shove, odd terrain) rather than
     * standing there forever. */
    private static final int GIVE_UP_TICKS = 100;

    private final RaiderEntity raider;

    @Nullable
    private BlockPos target;
    private boolean isDoor;
    private int hitsLanded;
    private int chopTicks;
    private int approachTicks;
    private int breaksUsedThisRaid;

    @Nullable
    private BlockPos stuckAnchor;
    private long stuckSinceTime = -1L;
    /** Whether {@code horizontalCollision} has fired at least once since
     * {@link #stuckAnchor} was last set — a faster-confirmation hint only,
     * see {@link #canUse()} and the class doc; never required to be true. */
    private boolean collidedSinceAnchor;

    public RaiderBreachGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (raider.isScout() || breaksUsedThisRaid >= MAX_BREAKS_PER_RAIDER) {
            return false;
        }
        if (!(raider.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = raider.settlement();
        if (settlement == null || settlement.pendingRaid == null) {
            stuckAnchor = null;
            return false; // only ever during a raid that is actually on
        }
        BlockPos destination = destinationFor(raider);
        if (destination == null) {
            stuckAnchor = null;
            return false;
        }
        BlockPos here = raider.blockPosition();
        if (here.distSqr(destination) <= REACH_SQR) {
            stuckAnchor = null; // already there; nothing to breach
            return false;
        }
        long now = level.getGameTime();
        if (stuckAnchor == null || here.distSqr(stuckAnchor) > STUCK_EPS_SQR) {
            // Real progress (or just starting to watch): reset the window.
            // A raider that stopped on purpose (it reached what it actually
            // wanted, e.g. a chest to loot) never even gets here -- the
            // "already there" check above ruled it out already, which is
            // what actually keeps a happily-looting raider from ever being
            // mistaken for stuck, not anything tracked in this window.
            stuckAnchor = here.immutable();
            stuckSinceTime = now;
            collidedSinceAnchor = raider.horizontalCollision;
            return false;
        }
        if (raider.horizontalCollision) {
            collidedSinceAnchor = true;
        }
        // BREACH-FIX: collision used to be required here. It cannot be --
        // a raider the pathfinder refuses to route through a closed iron
        // door for (see class doc) never once presses into it: the partial
        // path it walks ends one cell short and it simply stops, motionless
        // and uncollided, for the rest of the raid. The real judge of
        // "blocked" is being stationary this long while still short of the
        // destination; collision only ever shortens that wait, never
        // replaces it.
        long elapsed = now - stuckSinceTime;
        boolean confirmed = elapsed >= STUCK_THRESHOLD_TICKS
            || (collidedSinceAnchor && elapsed >= COLLISION_CONFIRM_TICKS);
        if (!confirmed) {
            return false;
        }
        BlockPos candidate = findBreachCandidate(level, settlement);
        if (candidate == null) {
            return false; // stuck for some other reason; nothing here to hit
        }
        target = candidate;
        isDoor = level.getBlockState(candidate).getBlock() instanceof DoorBlock;
        return true;
    }

    /** Where the raider is actually trying to get to right now: a live
     * combat target first, its raid objective otherwise. Never {@code
     * RaiderLootGoal}'s private chest target — this goal deliberately never
     * reads another goal's internals, only the raider's own position. */
    @Nullable
    private static BlockPos destinationFor(RaiderEntity raider) {
        Entity liveTarget = raider.getTarget();
        if (liveTarget != null && liveTarget.isAlive()) {
            return liveTarget.blockPosition();
        }
        return raider.objectivePos();
    }

    /**
     * A door within reach, or failing that a wall block within reach —
     * bounded to a small fixed cube around the raider (INV: all world
     * scanning is budgeted), never a search that grows with the world.
     */
    @Nullable
    private BlockPos findBreachCandidate(ServerLevel level, Settlement settlement) {
        BlockPos origin = raider.blockPosition();
        BlockPos bestDoor = null;
        double bestDoorDist = Double.MAX_VALUE;
        BlockPos bestWall = null;
        double bestWallDist = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                // dy starts at 0 (the raider's own foot level), never below
                // it: a raider "breaching" the floor it stands on would read
                // as digging, not as forcing a way through, and every real
                // door/wall candidate is at foot level or above anyway.
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    double distSqr = origin.distSqr(pos);
                    if (distSqr > REACH_SQR) {
                        continue; // out of reach
                    }
                    if (!withinSettlement(settlement, pos)) {
                        continue; // never breach outside the settlement itself
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || level.getBlockEntity(pos) != null) {
                        // A block entity here means a chest, bed, plaque or
                        // hearth: stolen from or spared, never smashed.
                        continue;
                    }
                    if (state.getBlock() instanceof DoorBlock) {
                        if (state.getValue(DoorBlock.OPEN)) {
                            continue; // an open door blocks nothing
                        }
                        BlockPos lower = state.getValue(DoorBlock.HALF)
                            == DoubleBlockHalf.UPPER ? pos.below() : pos;
                        double lowerDist = origin.distSqr(lower);
                        if (lowerDist < bestDoorDist) {
                            bestDoorDist = lowerDist;
                            bestDoor = lower;
                        }
                        continue;
                    }
                    if (state.getDestroySpeed(level, pos) < 0.0F
                        || state.getBlock().asItem() == Items.AIR
                        || state.getCollisionShape(level, pos).isEmpty()) {
                        continue; // unbreakable, dropless, or not an obstruction
                    }
                    if (distSqr < bestWallDist) {
                        bestWallDist = distSqr;
                        bestWall = pos.immutable();
                    }
                }
            }
        }
        return bestDoor != null ? bestDoor : bestWall; // doors first, always
    }

    private static boolean withinSettlement(Settlement settlement, BlockPos pos) {
        double r = settlement.radius;
        return pos.distSqr(settlement.center) <= r * r;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !raider.isAlive() || raider.isScout()) {
            return false;
        }
        Settlement settlement = raider.settlement();
        return settlement != null && settlement.pendingRaid != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        hitsLanded = 0;
        chopTicks = 0;
        approachTicks = 0;
    }

    @Override
    public void stop() {
        raider.getNavigation().stop();
        if (target != null && raider.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(raider.getId(), target, -1); // clear cracks
        }
        target = null;
    }

    @Override
    public void tick() {
        if (target == null || !(raider.level() instanceof ServerLevel level)) {
            return;
        }
        raider.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5,
            target.getZ() + 0.5);
        if (!raider.blockPosition().closerThan(target, WORK_RANGE)) {
            if (++approachTicks > GIVE_UP_TICKS) {
                level.destroyBlockProgress(raider.getId(), target, -1);
                target = null;
                return;
            }
            if (raider.getNavigation().isDone()) {
                raider.getNavigation().moveTo(target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, MOVE_SPEED);
            }
            return;
        }
        approachTicks = 0;
        raider.getNavigation().stop();
        chopTicks++;
        // The strike (and its sound) lands on the swing's contact frame,
        // never on the loop seam -- the same discipline every settler work
        // goal in this mod follows (see RepairWorkGoal, LumbererWorkGoal).
        if (chopTicks % SWING_PERIOD == SWING_CONTACT) {
            landHit(level);
        }
    }

    /** One swing landing: a visible crack stage, a sound, and — once the
     * block's small HP pool is spent — the actual breach. */
    private void landHit(ServerLevel level) {
        if (target == null) {
            return;
        }
        BlockState state = level.getBlockState(target);
        if (state.isAir() || level.getBlockEntity(target) != null) {
            // Vanished, or grew a block entity under us (the player built
            // something else there mid-chop) -- abandon, never break
            // whatever is there now.
            level.destroyBlockProgress(raider.getId(), target, -1);
            target = null;
            return;
        }
        hitsLanded++;
        int maxHits = isDoor ? DOOR_HITS : WALL_HITS;
        int stage = Mth.clamp(Math.round(hitsLanded / (float) maxHits * 9.0F), 0, 9);
        level.destroyBlockProgress(raider.getId(), target, stage);
        level.playSound(null, target, soundFor(state), SoundSource.HOSTILE, 1.0F,
            0.8F + raider.getRandom().nextFloat() * 0.2F);
        if (hitsLanded < maxHits) {
            return;
        }
        Settlement settlement = raider.settlement();
        if (settlement == null) {
            // Lost its settlement mid-chop -- should not happen live, but
            // abandon cleanly rather than break something with nowhere to
            // record the scar against.
            level.destroyBlockProgress(raider.getId(), target, -1);
            target = null;
            return;
        }
        breakTarget(level, settlement, state);
    }

    private SoundEvent soundFor(BlockState state) {
        return isDoor || state.is(BlockTags.MINEABLE_WITH_AXE)
            ? ModSounds.CHOP.get() : ModSounds.PICK_STRIKE.get();
    }

    /**
     * The breach itself. Scar-recorded FIRST, exactly the ordering {@link
     * RaidDirector}'s class doc demands, then destroyed — {@code
     * Level#destroyBlock} both handles the vanilla break particles/sound and
     * never drops the block (the same choice {@code LumbererWorkGoal} makes
     * felling a tree: this is a raid doing violence to a wall, not a miner
     * earning a resource).
     *
     * <p>A door is two block positions but one obstacle: both halves are
     * scarred and destroyed together (breaking only the lower half would
     * leave the upper one floating), and it counts once against {@link
     * #MAX_BREAKS_PER_RAIDER} — the cap is "how many things this raider
     * broke open", not "how many block positions changed".
     */
    private void breakTarget(ServerLevel level, Settlement settlement, BlockState lowerState) {
        // Animation trigger: fires the same tick the scar is recorded below
        // (RaidDirector's own ordering requirement), so the visible blow
        // and the actual breach land together -- see RaiderEntity#triggerBreach.
        raider.triggerBreach();
        if (isDoor) {
            BlockPos upper = target.above();
            BlockState upperState = level.getBlockState(upper);
            RaidDirector.recordScar(level, settlement.id, target, lowerState);
            if (!upperState.isAir()) {
                RaidDirector.recordScar(level, settlement.id, upper, upperState);
            }
            level.destroyBlock(target, false);
            level.destroyBlock(upper, false);
        } else {
            RaidDirector.recordScar(level, settlement.id, target, lowerState);
            level.destroyBlock(target, false);
        }
        Hearthstead.LOGGER.info(
            "Raider breaches {} at {} in {} ({} of {} breaks this raider)",
            isDoor ? "a door" : lowerState.getBlock().getName().getString(),
            target, settlement.name, breaksUsedThisRaid + 1, MAX_BREAKS_PER_RAIDER);
        breaksUsedThisRaid++;
        target = null;
        hitsLanded = 0;
        chopTicks = 0;
        // A fresh gap may already clear the way -- let navigation try it
        // before deciding this raider is stuck again.
        stuckAnchor = null;
        stuckSinceTime = -1L;
    }

    /** Test seam: how many blocks this raider has broken so far this raid. */
    public int breaksUsed() {
        return breaksUsedThisRaid;
    }
}

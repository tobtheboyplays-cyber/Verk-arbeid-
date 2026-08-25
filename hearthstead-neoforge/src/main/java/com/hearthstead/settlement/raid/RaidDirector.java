package com.hearthstead.settlement.raid;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.Difficulty;
import net.minecraft.util.RandomSource;

/**
 * Runs the nightly roll for one settlement.
 *
 * <p>Kept deliberately thin: every rule lives in {@link RaidPressure},
 * which takes its roll as a parameter and is therefore exactly testable.
 * This class only decides <em>when</em> to ask.
 *
 * <p><b>Step 1 scope, stated plainly.</b> The schedule is live — pressure
 * really does evolve night by night in a running world, and the stage is
 * readable through {@code /hearthstead info}. Nothing is spawned yet: when
 * the roll says "tonight", that is logged and the pressure state records
 * it, and the faction, captain and objective land in step 2. This class
 * exists now rather than later so the schedule is not dead code sitting
 * beside a model nothing calls.
 */
public final class RaidDirector {

    /**
     * Time of day at which the night's roll is taken. Sits inside the REST
     * phase (which begins at 12700) rather than at its edge, so a settlement
     * whose chunks load a moment late still gets its roll.
     */
    public static final int ROLL_AT_DAYTIME = 13000;

    /** Length of a Minecraft day, and therefore the night index's divisor. */
    public static final long DAY_LENGTH = 24000L;

    /**
     * Chance that a raid is led by a captain the settlement has met before,
     * when there is one to reuse. High on purpose: an enemy you recognise is
     * the whole point, and a fresh nobody every time is what makes both
     * references' raids feel like weather rather than like people.
     */
    public static final float RETURNING_CAPTAIN_CHANCE = 0.7F;

    /** Captains one settlement will remember at once. */
    public static final int MAX_REMEMBERED_CAPTAINS = 5;

    /** Band size bounds. A raid is a band with a leader, never a horde. */
    public static final int MIN_BAND = 2;
    public static final int MAX_BAND = 9;
    /** Settlement worth per extra raider beyond the minimum. */
    public static final int WORTH_PER_RAIDER = 14;
    /** How far out the band forms, in blocks from the settlement centre. */
    public static final int SPAWN_MIN_DISTANCE = 26;
    public static final int SPAWN_MAX_DISTANCE = 38;
    /** Half-width of the arc the band spreads across, in degrees. */
    public static final float SPAWN_ARC = 22.0F;
    /** Vertical search for standable ground at the spawn column. */
    public static final int SPAWN_VERTICAL_SEARCH = 12;

    private RaidDirector() {
    }

    /**
     * Whether raids are possible at this difficulty at all.
     *
     * <p>On Peaceful, {@code Monster.shouldDespawnInPeaceful()} is true and
     * vanilla discards every hostile on the next tick — so a raider would
     * spawn and vanish before it took a step. Found by looking at the game:
     * the QA world runs Peaceful, raiders were reported "Summoned" and were
     * gone a tick later.
     *
     * <p>Without this gate the schedule would keep accumulating pressure and
     * announcing BELEIRING in the Tingbok while nothing could ever arrive —
     * a threat display that is quietly lying to the player, which is worse
     * than no threat at all.
     */
    public static boolean raidsPossibleAt(Difficulty difficulty) {
        return difficulty != Difficulty.PEACEFUL;
    }

    /**
     * Builds tonight's raid: who leads it, what they want, and the road they
     * take. Public so it is directly testable with a seeded random.
     */
    public static RaidPlan planRaid(ServerLevel level, Settlement settlement,
                                    long night) {
        RandomSource random = level.getRandom();
        RaidCaptain captain = pickCaptain(settlement, random);
        RaidObjective objective = RaidObjective.pick(settlement, random);
        float approach = captain.nextApproachDegrees(random);
        captain.recordApproach(approach, objective);
        return new RaidPlan(captain.id(), objective, approach, night);
    }

    /**
     * A returning enemy where possible. New captains are only generated
     * when there is nobody to send or the roll calls for reinforcements, and
     * the gallery is capped so a long-lived settlement remembers a cast
     * rather than a crowd.
     */
    public static RaidCaptain pickCaptain(Settlement settlement, RandomSource random) {
        if (!settlement.raidCaptains.isEmpty()
            && random.nextFloat() < RETURNING_CAPTAIN_CHANCE) {
            return settlement.raidCaptains.get(
                random.nextInt(settlement.raidCaptains.size()));
        }
        RaidCaptain fresh = RaidCaptain.generate(random);
        settlement.raidCaptains.add(fresh);
        while (settlement.raidCaptains.size() > MAX_REMEMBERED_CAPTAINS) {
            settlement.raidCaptains.remove(0); // the oldest grudge fades first
        }
        return fresh;
    }

    /**
     * How many raiders come. Grows with what the settlement is worth and
     * with the captain's own record, and is hard-capped: MineColonies allows
     * up to 80 raiders by default and players report the result as a slog,
     * so this deliberately stays a band you can name rather than a wave you
     * can only survive.
     */
    public static int bandSizeFor(Settlement settlement, RaidCaptain captain) {
        int fromWorth = MIN_BAND
            + RaidPressure.worthOf(settlement) / WORTH_PER_RAIDER;
        int scaled = Math.round(fromWorth * Math.min(captain.menace(), 2.0F));
        return Mth.clamp(scaled, MIN_BAND, MAX_BAND);
    }

    /**
     * Where a raider standing at {@code degrees} from the settlement centre
     * would form up. Pure, so the geometry is testable without a world.
     */
    public static BlockPos formUpAt(BlockPos center, float degrees, int distance) {
        double radians = Math.toRadians(degrees);
        int x = center.getX() + (int) Math.round(-Math.sin(radians) * distance);
        int z = center.getZ() + (int) Math.round(Math.cos(radians) * distance);
        return new BlockPos(x, center.getY(), z);
    }

    /**
     * Brings the band into the world along the planned approach.
     *
     * <p>Spread across an arc rather than stacked on one point. Both
     * references put every hostile through a single door: MineColonies
     * players report raiders "usually come from the same spawn point" and
     * ganging up on one tower guard (#193), and TekTopia uses four fixed
     * corners. A band that arrives across a front has to be met, not
     * funnelled.
     */
    public static int spawnBand(ServerLevel level, Settlement settlement,
                                RaidPlan plan) {
        RaidCaptain captain = captainOf(settlement, plan.captainId());
        if (captain == null) {
            return 0;
        }
        int band = bandSizeFor(settlement, captain);
        RandomSource random = level.getRandom();
        int spawned = 0;
        for (int i = 0; i < band; i++) {
            boolean isCaptain = i == 0;
            float spread = band <= 1 ? 0.0F
                : (i / (float) (band - 1) - 0.5F) * 2.0F * SPAWN_ARC;
            int distance = SPAWN_MIN_DISTANCE + random.nextInt(
                Math.max(1, SPAWN_MAX_DISTANCE - SPAWN_MIN_DISTANCE + 1));
            BlockPos column = formUpAt(settlement.center,
                plan.approachDegrees() + spread, distance);
            BlockPos ground = standableNear(level, column);
            if (ground == null) {
                continue; // no footing on this bearing; the rest still come
            }
            RaiderEntity raider =
                com.hearthstead.registry.ModEntities.RAIDER.get().create(level);
            if (raider == null) {
                continue;
            }
            raider.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5,
                plan.approachDegrees() + 180.0F, 0.0F);
            raider.assign(captain.id(), settlement.id, plan.objective(),
                captain.menace(), isCaptain);
            raider.setObjectivePos(settlement.center);
            level.addFreshEntity(raider);
            spawned++;
        }
        return spawned;
    }

    /**
     * Solid footing near a column, searched up then down. Without this a
     * band forms inside a hillside or in mid-air over a ravine and the raid
     * silently never arrives.
     */
    public static BlockPos standableNear(ServerLevel level, BlockPos column) {
        for (int dy = 0; dy <= SPAWN_VERTICAL_SEARCH; dy++) {
            for (int sign : new int[] {1, -1}) {
                BlockPos at = column.offset(0, dy * sign, 0);
                if (isStandable(level, at)) {
                    return at;
                }
                if (dy == 0) {
                    break; // do not test the same block twice
                }
            }
        }
        return null;
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above())
                .getCollisionShape(level, pos.above()).isEmpty()
            && !level.getBlockState(pos.below())
                .getCollisionShape(level, pos.below()).isEmpty();
    }

    /** How far past the settlement edge a raid still counts as in progress. */
    public static final int RAID_BOUNDS_MARGIN = 48;

    /**
     * Ends the raid once no raider of it is left standing, and records the
     * outcome on both sides.
     *
     * <p>This is where the feedback loop that the whole design turns on
     * actually fires: repelling a raid RAISES pressure, because the
     * settlement proved it was worth the trouble and kept its goods. In
     * MineColonies the reverse holds -- losing more than 15% of the
     * population lowers difficulty and buys six quiet nights -- which is why
     * that system converges on safe however the player plays.
     */
    public static boolean resolveIfOver(ServerLevel level, Settlement settlement) {
        RaidPlan plan = settlement.pendingRaid;
        if (plan == null) {
            return false;
        }
        if (!livingRaidersOf(level, settlement).isEmpty()) {
            return false; // still going
        }
        RaidCaptain captain = captainOf(settlement, plan.captainId());
        // Nobody left standing means the settlement held. A captain who wants
        // goods and leaves with nothing has lost, however many settlers died.
        settlement.raidPressure.recordRepelled();
        if (captain != null) {
            captain.recordDefeat();
        }
        settlement.pendingRaid = null;
        SettlementSavedData.get(level).setDirty();
        Hearthstead.LOGGER.info(
            "Raid on {} is over -- {} was driven off (pressure now {}, stage {})",
            settlement.name, captain == null ? "the band" : captain.name(),
            settlement.raidPressure.pressure(),
            settlement.raidPressure.stage().id());
        return true;
    }

    /**
     * Raiders of this settlement still alive, found with one bounded box
     * query rather than a world-wide entity sweep -- all world scanning is
     * budgeted (INV).
     */
    public static java.util.List<RaiderEntity> livingRaidersOf(
        ServerLevel level, Settlement settlement) {
        int reach = settlement.radius + RAID_BOUNDS_MARGIN;
        AABB box = new AABB(settlement.center).inflate(reach);
        return level.getEntitiesOfClass(RaiderEntity.class, box,
            r -> r.isAlive() && settlement.id.equals(r.settlementId()));
    }

    /** The remembered captain with this id, or null if they are forgotten. */
    public static RaidCaptain captainOf(Settlement settlement, java.util.UUID id) {
        for (RaidCaptain c : settlement.raidCaptains) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** The night index a given world time belongs to. */
    public static long nightOf(long dayTime) {
        return dayTime / DAY_LENGTH;
    }

    /**
     * Whether the night's roll is due at this world time.
     *
     * <p>Pure on purpose. The obvious way to test the time gate is to set
     * the level's time in a GameTest, but {@code setDayTime} is level-wide
     * and every test in a batch shares that level — a test that flipped the
     * world to night would stall every concurrently running settler whose
     * work goal checks {@code dayPhase()}. Testing the arithmetic directly
     * costs nothing and cannot poison its neighbours.
     */
    public static boolean isRollTime(long dayTime) {
        return dayTime % DAY_LENGTH >= ROLL_AT_DAYTIME;
    }

    /**
     * Called from the hearth's once-a-second settlement tick. Safe to call
     * as often as you like: {@link RaidPressure#rollForNight} is idempotent
     * per night, so a re-entrant or duplicated tick cannot double-roll.
     */
    public static void tick(ServerLevel level, Settlement settlement) {
        if (!raidsPossibleAt(level.getDifficulty())) {
            return; // peaceful: no raiders can exist, so no pressure either
        }
        if (settlement.pendingRaid != null) {
            // A raid is on. Resolve it before considering another night --
            // "deliveries that silently never happen" applied to raids would
            // be a raid that is scheduled forever and never concludes.
            resolveIfOver(level, settlement);
            return;
        }
        long dayTime = level.getDayTime();
        if (!isRollTime(dayTime)) {
            return; // not yet tonight
        }
        long night = nightOf(dayTime);
        RaidPressure pressure = settlement.raidPressure;
        if (night <= pressure.lastRolledNight()) {
            return; // already asked tonight; skip the random draw entirely
        }
        boolean raid = pressure.rollForNight(settlement, night,
            level.getRandom().nextDouble());
        SettlementSavedData.get(level).setDirty();
        if (raid) {
            RaidPlan plan = planRaid(level, settlement, night);
            settlement.pendingRaid = plan;
            spawnBand(level, settlement, plan);
            RaidCaptain captain = captainOf(settlement, plan.captainId());
            // Logged rather than silently dropped, so "the schedule fired
            // and nothing happened" is visible in evidence instead of
            // looking like the roll never ran.
            Hearthstead.LOGGER.info(
                "Raid scheduled for {} on night {}: {} comes for {} from {}"
                    + " degrees (pressure {}, stage {}, menace {})"
                    + " -- no raiders exist yet (A3 step 2)",
                settlement.name, night,
                captain == null ? "?" : captain.name(),
                plan.objective().id(), Math.round(plan.approachDegrees()),
                pressure.pressure(), pressure.stage().id(),
                captain == null ? "?" : captain.menace());
        }
    }
}

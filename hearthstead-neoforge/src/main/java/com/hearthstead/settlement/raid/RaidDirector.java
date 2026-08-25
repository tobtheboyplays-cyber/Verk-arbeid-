package com.hearthstead.settlement.raid;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    /**
     * Past raids one settlement keeps in its morning-report history at once
     * (D-A3-8's "scar", bounded the same way the enemy gallery is bounded):
     * a settlement remembers its history, not an unbounded diary.
     */
    public static final int MAX_RAID_LOG = 8;

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
     * How many raiders come. Grows with what the settlement is worth, with
     * the captain's own record, AND with how besieged the settlement
     * currently reads (D-A3-3: escalation must be legible in the stage, not
     * only felt through wealth) -- hard-capped regardless: MineColonies
     * allows up to 80 raiders by default and players report the result as a
     * slog, so this deliberately stays a band you can name rather than a
     * wave you can only survive.
     */
    public static int bandSizeFor(Settlement settlement, RaidCaptain captain) {
        int fromWorth = MIN_BAND
            + RaidPressure.worthOf(settlement) / WORTH_PER_RAIDER;
        float stageWeight = stageBandMultiplier(settlement.raidPressure.stage());
        float scaled = fromWorth * Math.min(captain.menace(), 2.0F) * stageWeight;
        return Mth.clamp(Math.round(scaled), MIN_BAND, MAX_BAND);
    }

    /**
     * Extra weight the siege stage itself adds to a band, on top of worth
     * and the captain's record. The same modest settlement pulls a visibly
     * bigger band once it is under Varsel or Beleiring than it would at
     * Rolig -- escalation you can read in the Tingbok, not just in hindsight.
     */
    public static float stageBandMultiplier(RaidPressure.Stage stage) {
        return switch (stage) {
            case ROLIG -> 1.0F;
            case URO -> 1.15F;
            case VARSEL -> 1.35F;
            case BELEIRING -> 1.6F;
        };
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
    public static java.util.List<RaiderEntity> spawnBand(ServerLevel level,
                                                        Settlement settlement,
                                                        RaidPlan plan) {
        java.util.List<RaiderEntity> spawned = new java.util.ArrayList<>();
        RaidCaptain captain = captainOf(settlement, plan.captainId());
        if (captain == null) {
            return spawned;
        }
        int band = bandSizeFor(settlement, captain);
        RandomSource random = level.getRandom();
        for (int i = 0; i < band; i++) {
            boolean isCaptain = i == 0;
            float spread = band <= 1 ? 0.0F
                : (i / (float) (band - 1) - 0.5F) * 2.0F * SPAWN_ARC;
            int distance = SPAWN_MIN_DISTANCE + random.nextInt(
                Math.max(1, SPAWN_MAX_DISTANCE - SPAWN_MIN_DISTANCE + 1));
            BlockPos ground = footingFor(level, settlement,
                plan.approachDegrees() + spread, distance, isCaptain);
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
            spawned.add(raider);
        }
        return spawned;
    }

    /**
     * Footing for one raider. A follower gets one attempt on its own bearing
     * -- if the ground there is bad, the band simply arrives one short.
     *
     * <p>The CAPTAIN does not: a leaderless band contradicts the whole
     * design, so the captain sweeps outward around the arc until something
     * takes. Found by a test asserting a band is led and occasionally
     * finding it was not, because the leader's single column happened to
     * have no floor.
     */
    private static BlockPos footingFor(ServerLevel level, Settlement settlement,
                                       float bearing, int distance,
                                       boolean isCaptain) {
        BlockPos direct = standableNear(level,
            formUpAt(settlement.center, bearing, distance));
        if (direct != null || !isCaptain) {
            return direct;
        }
        for (int step = 1; step <= CAPTAIN_BEARING_TRIES; step++) {
            for (int sign : new int[] {1, -1}) {
                float swept = bearing + sign * step * CAPTAIN_BEARING_STEP;
                for (int d = distance; d >= SPAWN_MIN_DISTANCE / 2; d -= 4) {
                    BlockPos found = standableNear(level,
                        formUpAt(settlement.center, swept, d));
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        // Last resort: the settlement's own ground. A raid that was rolled,
        // planned and announced and then silently failed to appear because
        // the terrain on every bearing was bad is the raid-shaped version of
        // "deliveries that silently never happen" -- the exact failure class
        // this whole slice is built against. Arriving badly beats not
        // arriving, and it is logged so it is never mistaken for normal.
        BlockPos athome = standableNear(level, settlement.center);
        if (athome != null) {
            Hearthstead.LOGGER.warn(
                "No footing on any bearing for the captain raiding {} -- "
                    + "forming up at the settlement itself", settlement.name);
        }
        return athome;
    }

    /** How far the captain will sweep for footing, and in what steps. */
    public static final int CAPTAIN_BEARING_TRIES = 8;
    public static final float CAPTAIN_BEARING_STEP = 24.0F;

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
        // Whether the settlement HELD is not about who died -- it is about
        // whether the raiders got what they came for. A band that leaves with
        // the stores has won even if it left bodies behind, and one wiped out
        // empty-handed has lost even if it killed settlers doing it.
        boolean lost = settlement.raidLootEscaped;
        if (lost) {
            settlement.raidPressure.recordLost();
            if (captain != null) {
                captain.recordVictory();
            }
        } else {
            settlement.raidPressure.recordRepelled();
            if (captain != null) {
                captain.recordDefeat();
            }
        }
        settlement.pendingRaid = null;
        settlement.raidLootEscaped = false;
        recordAftermath(level, settlement, plan, captain, !lost);
        SettlementSavedData.get(level).setDirty();
        Hearthstead.LOGGER.info(
            "Raid on {} is over -- {} {} (pressure now {}, stage {})",
            settlement.name, captain == null ? "the band" : captain.name(),
            lost ? "got away with the stores" : "was driven off",
            settlement.raidPressure.pressure(),
            settlement.raidPressure.stage().id());
        return true;
    }

    /**
     * The scar (D-A3-8) and the aftermath the design calls for: "repair
     * dugnad + defense report". This is the report half -- what was stolen,
     * who was hurt, and what the threat reads as now, both logged on the
     * settlement (a capped history, {@link #MAX_RAID_LOG}) and read out to
     * every nearby player, the same morning the raid actually ended rather
     * than only ever visible through {@code /hearthstead info}.
     *
     * <p>The tallies are read here and reset here: they describe exactly
     * one raid, accumulated live as it happened ({@code RaiderLootGoal}'s
     * successful withdrawal, {@code RaiderEntity#doHurtTarget}), never a
     * running lifetime total.
     */
    private static void recordAftermath(ServerLevel level, Settlement settlement,
                                        RaidPlan plan, RaidCaptain captain, boolean held) {
        String captainName = captain == null ? "?" : captain.name();
        String stageAfter = settlement.raidPressure.stage().id();
        RaidLogEntry entry = new RaidLogEntry(plan.night(), captainName,
            plan.objective().id(), held, settlement.raidItemsStolenTonight,
            settlement.raidSettlersHurtTonight, stageAfter);
        settlement.raidLog.add(entry);
        while (settlement.raidLog.size() > MAX_RAID_LOG) {
            settlement.raidLog.remove(0); // oldest history fades first
        }

        Component stage = Component.translatable("hearthstead.raid.stage." + stageAfter);
        Component report = held
            ? Component.translatable("hearthstead.message.raid_defense_held",
                settlement.name, settlement.raidSettlersHurtTonight,
                settlement.raidItemsStolenTonight, stage)
            : Component.translatable("hearthstead.message.raid_defense_lost",
                settlement.name, captainName, settlement.raidItemsStolenTonight,
                settlement.raidSettlersHurtTonight, stage);
        RaidBroadcast.send(level, settlement, report);

        settlement.raidItemsStolenTonight = 0;
        settlement.raidSettlersHurtTonight = 0;
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
        // Before tonight's own roll: the telegraph. Checked every tick like
        // the roll below, but it fires on its own schedule (RaidTelegraph),
        // which is why it is not gated behind isRollTime -- dusk (its fire
        // time) is strictly earlier in the day than ROLL_AT_DAYTIME.
        RaidTelegraph.tick(level, settlement);
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
        if (!raid) {
            // A quiet night is still a night the dread can grow on: maybe
            // commit to an omen 1-2 nights from now (RaidTelegraph). Never
            // when tonight itself raided -- the raid IS the omen fulfilled.
            RaidTelegraph.rollForecast(settlement, night, level.getRandom().nextDouble());
        }
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
                    + " degrees (pressure {}, stage {}, menace {})",
                settlement.name, night,
                captain == null ? "?" : captain.name(),
                plan.objective().id(), Math.round(plan.approachDegrees()),
                pressure.pressure(), pressure.stage().id(),
                captain == null ? "?" : captain.menace());
        }
    }
}

package com.hearthstead.settlement.raid;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.saga.Captain;
import com.hearthstead.saga.CaptainRoster;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.BlessingPresentation;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RecurringRaidRun;
import com.hearthstead.util.AuthorityTelemetry;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidParticipantRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.RandomSource;

/**
 * Runs the nightly roll for one settlement.
 *
 * <p>Kept deliberately thin: every rule lives in {@link RaidPressure},
 * which takes its roll as a parameter and is therefore exactly testable.
 * This class only decides <em>when</em> to ask.
 *
 * <p><b>Scope, stated plainly (updated for SLICE REPAIR-1).</b> The
 * schedule is live, bands really spawn ({@link #spawnBand}) and raids
 * really resolve ({@link #resolveIfOver}). The aftermath the design calls
 * "repair dugnad + defense report" (DESIGN.md system 5) now has both
 * halves: the report is {@link #recordAftermath}, and the repair is the
 * <b>scar ledger</b> kept here — every block a raid destroys is recorded
 * as a {@link Scar} (position + the original {@link BlockState}) in
 * {@link RaidScars}, bounded per raid and persisted with the world, and
 * {@code com.hearthstead.entity.ai.RepairWorkGoal} works that queue down
 * once the raid is over. {@link #recordScar} is the single authorized
 * channel: any future raider-side destruction (gate breaching, spreading
 * fire observed by an entity goal) must record through it BEFORE the block
 * changes, so the original state is never lost.
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

    /**
     * SLICE REPAIR-1: how many scars one settlement's ledger holds. The cap
     * is per settlement and enforced on every recording (oldest dropped),
     * the same bounding discipline as {@link #MAX_RAID_LOG} and the enemy
     * gallery — a settlement remembers its wounds, not an unbounded diary,
     * and the repair dugnad's queue can never grow without limit.
     */
    public static final int MAX_SCARS_PER_RAID = 64;
    /**
     * Blocks the director itself will torch in one BRANN raid. Deliberately
     * far under {@link #MAX_SCARS_PER_RAID}: arson damage should read as a
     * handful of burnt wall blocks the dugnad visibly fixes the next
     * morning, not a levelled district. One torching per settlement tick
     * (once a second) at most, so the burning is watchable, not a flash.
     */
    public static final int ARSON_PER_RAID = 8;
    /** How close a raider must stand to a building's bounds to torch it. */
    public static final int ARSON_REACH = 4;
    /** Random positions sampled inside a building per torching attempt. */
    public static final int ARSON_SITE_TRIES = 8;

    /** Band size bounds. A raid is a band with a leader, never a horde. */
    public static final int MIN_BAND = 2;
    public static final int MAX_BAND = 9;
    /**
     * The authored first raid is a deliberately readable encounter, not a
     * pressure-scaled roll. Since the 26 Sep escalation curve (owner: "have
     * bandits early") it is an outlaw band: one named bandit captain and
     * three bandits, no Brute. Recurring raids follow {@link RaidEscalation}.
     */
    public static final int FIRST_RAID_BAND_SIZE = RaidEscalation.FIRST_RAID_BANDITS;
    /** Settlement worth per extra raider beyond the minimum. */
    public static final int WORTH_PER_RAIDER = 14;
    /**
     * How far past the settlement's claim edge ({@link Settlement#radius})
     * the band forms up. The band is placed at {@code radius + 8} to
     * {@code radius + 24} blocks from the centre, so no raider ever appears
     * inside the claim: with the default 48-block claim that is 56-72
     * blocks out. The old fixed 26-38 from the centre sat well inside a
     * 48-block claim, and raiders appeared among the houses.
     */
    public static final int SPAWN_EDGE_MARGIN_MIN = 8;
    public static final int SPAWN_EDGE_MARGIN_MAX = 24;
    /** Extra reach the captain's footing sweep may use past the band. */
    public static final int CAPTAIN_EXTRA_REACH = 16;
    /** Half-width of the arc the band spreads across, in degrees. */
    public static final float SPAWN_ARC = 22.0F;
    /**
     * Vertical search of {@link #standableNear} (the scout omen). Band
     * form-up reads the column's real surface instead
     * ({@link RaidFootingSearch#SURFACE_REACH}).
     */
    public static final int SPAWN_VERTICAL_SEARCH = 12;

    private RaidDirector() {
    }

    /**
     * Hearthstead profiles, not vanilla world difficulty, own raid policy.
     * Raiders explicitly survive and deal non-scaled damage on Peaceful, so
     * every vanilla difficulty is a supported raid runtime.
     */
    public static boolean raidsPossibleAt(net.minecraft.world.Difficulty difficulty) {
        return difficulty != null;
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
     * The tutorial encounter teaches one defendable objective rather than
     * rolling between theft, civilian hunting and immediate building damage.
     * First-raid readiness already requires a real Warehouse, so KORN gives
     * the player a visible place to rally the Guard and Archer around. A
     * legacy/corrupt edge with no storage falls back to BLOD instead of
     * manufacturing a target. Recurring raids continue through
     * {@link #planRaid} and retain their full objective variety.
     */
    private static RaidPlan planFirstRaid(ServerLevel level,
                                          Settlement settlement,
                                          long night) {
        RandomSource random = level.getRandom();
        RaidCaptain captain = pickCaptain(settlement, random);
        RaidObjective objective = RaidObjective.KORN.isAvailableAt(settlement)
            ? RaidObjective.KORN : RaidObjective.BLOD;
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
        return bandSizeFor(settlement, captain, 0);
    }

    /** Survived raids per extra raider (MineColonies-style raid level). */
    public static final int RAIDS_PER_ESCALATION_RAIDER = 2;
    /** Upper bound of the escalation bonus; MAX_BAND still caps the total. */
    public static final int MAX_ESCALATION_BONUS = 4;
    /** From this overall raid number on (first raid = 1) the band is veteran. */
    public static final int VETERAN_RAID_NUMBER = 3;
    /** Veteran bands put a BRUTE on every 3rd follower slot instead of every 5th. */
    public static final int VETERAN_BRUTE_SPACING = 3;
    /** Veteran captains are built BRUTE half the time instead of 30%. */
    public static final float VETERAN_BRUTE_CAPTAIN_CHANCE = 0.5F;

    /**
     * Escalation (owner decision 25 Sep; MineColonies raid level as the
     * reference). The full recurring band formula is:
     *
     * <pre>
     *   base      = 2 + worth / 14            worth = population*3 + validBuildings*4
     *   scaled    = round(base * min(captainMenace, 2.0) * stageWeight)
     *                                         stageWeight: ROLIG 1.0, URO 1.15,
     *                                         VARSEL 1.35, BELEIRING 1.6
     *   raidLevel = raidsSurvived - (lostLastRaid ? 1 : 0), min 0
     *   bonus     = min(raidLevel / 2, 4)     (+1 raider per 2 survived raids)
     *   band      = clamp(scaled + bonus, MIN_BAND 2, MAX_BAND 9)
     * </pre>
     *
     * Composition: from overall raid 3 on (and not in a post-loss breather)
     * followers are BRUTE on every 3rd slot (else every 5th) and the captain
     * is BRUTE 50% of the time (else 30%). Slot 1 is always a SKIRMISHER.
     */
    public static int escalationBonusFor(int raidLevel) {
        return Mth.clamp(Math.max(0, raidLevel) / RAIDS_PER_ESCALATION_RAIDER,
            0, MAX_ESCALATION_BONUS);
    }

    public static int bandSizeFor(Settlement settlement, RaidCaptain captain,
                                  int raidLevel) {
        int fromWorth = MIN_BAND
            + RaidPressure.worthOf(settlement) / WORTH_PER_RAIDER;
        float stageWeight = stageBandMultiplier(settlement.raidPressure.stage());
        float scaled = fromWorth * Math.min(captain.menace(), 2.0F) * stageWeight;
        return Mth.clamp(Math.round(scaled) + escalationBonusFor(raidLevel),
            MIN_BAND, MAX_BAND);
    }

    /** Whether the band spawned as overall raid {@code raidNumber} uses the veteran mix. */
    public static boolean veteranBand(long raidNumber, boolean breather) {
        return raidNumber >= VETERAN_RAID_NUMBER && !breather;
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

    /** Nearest form-up distance from the centre: the claim edge plus a margin. */
    public static int spawnMinDistance(int claimRadius) {
        return Math.max(0, claimRadius) + SPAWN_EDGE_MARGIN_MIN;
    }

    /** Farthest form-up distance from the centre for an ordinary raider. */
    public static int spawnMaxDistance(int claimRadius) {
        return Math.max(0, claimRadius) + SPAWN_EDGE_MARGIN_MAX;
    }

    /**
     * Whether a form-up position lies strictly outside the settlement's
     * claim, measured horizontally (the claim test itself is a sphere, so
     * horizontal-outside is the stricter of the two).
     */
    public static boolean outsideClaim(Settlement settlement, BlockPos pos) {
        if (settlement == null || settlement.center == null || pos == null) {
            return false;
        }
        long dx = (long) pos.getX() - settlement.center.getX();
        long dz = (long) pos.getZ() - settlement.center.getZ();
        long radius = Math.max(0, settlement.radius);
        return dx * dx + dz * dz > radius * radius;
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
        LeaderIdentity leader = leaderIdentity(settlement,
            plan == null ? null : plan.captainId()).orElse(null);
        if (leader == null) {
            return spawned;
        }
        RandomSource random = level.getRandom();
        // Escalation curve (26 Sep): the band follows the raid number and the
        // settlement's strength, not worth and pressure alone. bandSizeFor and
        // the veteran spacing remain as documented history and test seams.
        RaidEscalation.Band composition = RaidEscalation.compose(
            RaidEscalation.raidNumber(settlement),
            RaidEscalation.strength(level, settlement), random.nextDouble());
        java.util.List<RaiderEntity.Variant> slots = composition.slots();
        Hearthstead.LOGGER.info("Raid band for {}: raid {} {}", settlement.name,
            RaidEscalation.raidNumber(settlement), composition);
        return spawnBandActors(level, settlement, plan, leader, slots.size(),
            Math.min(MIN_BAND, slots.size()), random, slots::get);
    }

    /**
     * Spawns the curated first encounter under the persisted warned plan.
     * Both the five slots and the captain's visual build are derived from that
     * plan, so a partial terrain/add-entity failure cannot reroll the encounter
     * on retry. All five actors are transactional: four accepted actors still
     * count as no raid and are removed before the lifecycle can activate.
     */
    private static java.util.List<RaiderEntity> spawnFirstBand(
            ServerLevel level, Settlement settlement, RaidPlan plan) {
        LeaderIdentity leader = leaderIdentity(settlement,
            plan == null ? null : plan.captainId()).orElse(null);
        if (leader == null) {
            return java.util.List.of();
        }
        RandomSource deterministic = RandomSource.create(
            firstRaidCompositionSeed(settlement, plan));
        return spawnBandActors(level, settlement, plan, leader,
            FIRST_RAID_BAND_SIZE, FIRST_RAID_BAND_SIZE, deterministic,
            index -> firstRaidVariantFor(index, settlement, plan));
    }

    /**
     * QA / GameTest only: the authored first raid's exact five-slot
     * placement under {@code plan}, without the lifecycle gates around it.
     * Nothing is recorded; the caller owns discarding what it gets.
     */
    public static java.util.List<RaiderEntity> spawnFirstBandForQa(
            ServerLevel level, Settlement settlement, RaidPlan plan) {
        return spawnFirstBand(level, settlement, plan);
    }

    private static java.util.List<RaiderEntity> spawnBandActors(
            ServerLevel level, Settlement settlement, RaidPlan plan,
            LeaderIdentity leader, int band, int requiredAccepted,
            RandomSource random,
            java.util.function.IntFunction<RaiderEntity.Variant> variants) {
        java.util.List<RaiderEntity> spawned = new java.util.ArrayList<>();
        RaidCaptain captain = leader.raidCaptain();
        int minDistance = spawnMinDistance(settlement.radius);
        int maxDistance = spawnMaxDistance(settlement.radius);
        BlockPos captainGround = null;
        for (int i = 0; i < band; i++) {
            boolean isCaptain = i == 0;
            float spread = band <= 1 ? 0.0F
                : (i / (float) (band - 1) - 0.5F) * 2.0F * SPAWN_ARC;
            int distance = minDistance + random.nextInt(
                Math.max(1, maxDistance - minDistance + 1));
            BlockPos ground = isCaptain
                ? captainFooting(level, settlement,
                    plan.approachDegrees() + spread, distance)
                : followerFooting(level, settlement,
                    plan.approachDegrees() + spread, distance, captainGround, i);
            if (isCaptain) {
                captainGround = ground;
            }
            if (ground == null) {
                if (isCaptain) {
                    return spawned; // never create a leaderless "raid"
                }
                continue; // no footing on this bearing; the rest still come
            }
            RaiderEntity raider =
                com.hearthstead.registry.ModEntities.RAIDER.get().create(level);
            if (raider == null) {
                if (isCaptain) {
                    return spawned;
                }
                continue;
            }
            raider.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5,
                plan.approachDegrees() + 180.0F, 0.0F);
            raider.setVariant(variants.apply(i));
            raider.assign(captain.id(), settlement.id, plan.objective(),
                captain.menace(), isCaptain);
            raider.setObjectivePos(settlement.center);
            if (isCaptain) {
                // The field identity must be the exact persisted identity the
                // warning and arrival broadcast name. A leader outside Saga's
                // smaller tracked roster is still somebody the settlement has
                // met, never an anonymous mob under a named warning.
                raider.setCustomName(net.minecraft.network.chat.Component.literal(
                    leader.displayName()));
                raider.setCustomNameVisible(true);

                // Saga membership remains the earned mechanical/visual tier:
                // it may add growth and an epithet mark, but it no longer owns
                // whether the base raid-captain identity is visible at all.
                Captain saga = leader.sagaCaptain();
                if (saga != null) {
                    raider.markSagaCaptain(leader.displayName(),
                        captain.victories(), saga.hasEpithet());
                }
            }
            // Only return entities the level actually accepted. The first
            // lifecycle seals this exact UUID list, so "constructed" is not
            // good enough evidence for "spawned".
            if (level.addFreshEntity(raider)) {
                spawned.add(raider);
            } else if (isCaptain) {
                return spawned;
            }
        }
        // Followers deliberately get one bounded footing attempt. The caller
        // owns the transaction floor: recurring bands keep MIN_BAND, while
        // the curated first encounter requires all five authored slots.
        if (spawned.size() < requiredAccepted) {
            discardTentativeBand(spawned);
            return java.util.List.of();
        }
        return spawned;
    }

    /** Stable seed for every placement/build choice in one warned first raid. */
    private static long firstRaidCompositionSeed(Settlement settlement,
                                                 RaidPlan plan) {
        long seed = settlement.id.getMostSignificantBits()
            ^ Long.rotateLeft(settlement.id.getLeastSignificantBits(), 11)
            ^ Long.rotateLeft(plan.captainId().getMostSignificantBits(), 23)
            ^ Long.rotateLeft(plan.captainId().getLeastSignificantBits(), 37)
            ^ Long.rotateLeft(plan.night(), 7);
        seed ^= (long) Float.floatToIntBits(plan.approachDegrees()) << 32;
        seed ^= plan.objective().ordinal() * 0x9E3779B97F4A7C15L;
        return seed;
    }

    /** Every first-raid slot is a bandit (26 Sep escalation curve); slot 0 is the captain. */
    private static RaiderEntity.Variant firstRaidVariantFor(
            int index, Settlement settlement, RaidPlan plan) {
        if (index >= 0 && index < FIRST_RAID_BAND_SIZE) {
            return RaiderEntity.Variant.BANDIT;
        }
        throw new IllegalArgumentException("invalid first-raid slot " + index);
    }

    /** Final pre-seal proof that spawn callbacks did not alter the encounter. */
    private static boolean validFirstRaidComposition(
            java.util.List<RaiderEntity> spawned, RaidPlan plan,
            String committedLeaderName) {
        if (spawned == null || spawned.size() != FIRST_RAID_BAND_SIZE
            || plan == null || committedLeaderName == null
            || spawned.get(0) == null
            || !spawned.get(0).isCaptain()) {
            return false;
        }
        int captains = 0;
        int banditFollowers = 0;
        for (RaiderEntity raider : spawned) {
            if (raider == null
                || !plan.captainId().equals(raider.captainId())
                || plan.objective() != raider.objective()) {
                return false;
            }
            if (raider.isCaptain()) {
                captains++;
                if (!raider.isCustomNameVisible()
                    || raider.getCustomName() == null
                    || !committedLeaderName.equals(
                        raider.getCustomName().getString())) {
                    return false;
                }
            } else if (raider.variant() == RaiderEntity.Variant.BANDIT) {
                banditFollowers++;
            } else {
                return false;
            }
        }
        return captains == 1 && banditFollowers == FIRST_RAID_BAND_SIZE - 1;
    }

    private static void discardTentativeBand(
            java.util.List<RaiderEntity> spawned) {
        for (RaiderEntity raider : spawned) {
            if (raider != null && !raider.isRemoved()) {
                raider.discard();
            }
        }
    }

    /** How rarely a follower is built BRUTE rather than SKIRMISHER,
     * expressed as "one in this many" -- roughly one per 4-5 per the task
     * brief. Deliberately never fires below index 5: a band under {@link
     * #BRUTE_SPACING} strong spends its only follower slots on the pack,
     * not the door, so BRUTE only shows up once there are enough
     * SKIRMISHERs around it to read as a pack with one heavy, not a heavy
     * alone. */
    public static final int BRUTE_SPACING = 5;
    /** How often the CAPTAIN themself is built BRUTE rather than
     * SKIRMISHER -- captaincy is a role either build can hold ({@link
     * RaiderEntity.Variant}'s own doc), so this is an independent roll, not
     * a follow-on from the follower spacing above. */
    public static final float BRUTE_CAPTAIN_CHANCE = 0.30F;

    /**
     * Which build raider {@code index} in this band should be. The captain
     * (index 0) rolls independently; every follower after it is BRUTE only
     * on every {@link #BRUTE_SPACING}th slot. Since index 0 is handled
     * separately and {@code index % BRUTE_SPACING == 0} cannot fire again
     * until {@code index == BRUTE_SPACING}, every band with fewer than
     * {@link #BRUTE_SPACING} followers gets an all-SKIRMISHER tail
     * regardless of the captain's own roll -- the band's SKIRMISHER floor
     * the brief asks for ("never zero SKIRMISHERs") falls out of that
     * spacing alone, with no separate fallback needed, and {@link
     * #MIN_BAND} (2) guarantees index 1 always exists to carry it.
     */
    static RaiderEntity.Variant variantFor(int index, RandomSource random) {
        return variantFor(index, random, false);
    }

    /** Veteran bands (raid 3+, see {@link #escalationBonusFor}) are heavier. */
    public static RaiderEntity.Variant variantFor(int index, RandomSource random,
                                                  boolean veteran) {
        if (index == 0) {
            float chance = veteran ? VETERAN_BRUTE_CAPTAIN_CHANCE : BRUTE_CAPTAIN_CHANCE;
            return random.nextFloat() < chance
                ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER;
        }
        int spacing = veteran ? VETERAN_BRUTE_SPACING : BRUTE_SPACING;
        return index % spacing == 0
            ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER;
    }

    /**
     * Footing for the CAPTAIN. A leaderless band contradicts the whole
     * design, so the captain walks {@link RaidFootingSearch#captain}: the
     * warned bearing across the band and {@link #CAPTAIN_EXTRA_REACH}, then
     * every other bearing nearest-first around the full circle, then the
     * thin ring just outside the claim edge, then a farther ring.
     *
     * <p>Every candidate lies OUTSIDE the claim; the old last resort --
     * forming up on the settlement's own ground -- stays gone. When no
     * column anywhere has footing the queued plan is kept for a retry (the
     * tested "keep the plan queued" rule), players are told why
     * ({@link RaidHoldNotice}), and the log says what the terrain offered.
     */
    private static BlockPos captainFooting(ServerLevel level, Settlement settlement,
                                           float bearing, int distance) {
        FootingTally tally = new FootingTally();
        for (RaidFootingSearch.Candidate c : RaidFootingSearch.captain(
                bearing, settlement.radius, distance)) {
            BlockPos found = surfaceOutsideClaim(level, settlement,
                formUpAt(settlement.center, c.bearing(), c.distance()),
                settlement.center.getY(), RaidFootingSearch.SURFACE_REACH, tally);
            if (found != null) {
                return found;
            }
        }
        Hearthstead.LOGGER.warn(
            "Raid on {} held: no footing outside the claim on any bearing for the "
                + "captain ({} columns tried: {} in unloaded chunks, {} wet, {} too far "
                + "above/below the Banner at y={}, {} blocked); the plan stays "
                + "queued and retries", settlement.name, tally.tried, tally.unloaded,
            tally.wet, tally.outOfReach, settlement.center.getY(), tally.blocked);
        return null;
    }

    /**
     * Footing for a FOLLOWER. It keeps the warned front first -- its own
     * column, its own bearing across the band's depth and a little beyond
     * ({@link RaidFootingSearch#follower}) -- and when that ray is all lake,
     * cliff or unloaded land it gathers on the ground around its captain
     * instead of costing the raid a raider. The authored first raid needs
     * all five of its slots, so one wet ray used to hold it forever.
     */
    private static BlockPos followerFooting(ServerLevel level, Settlement settlement,
                                            float bearing, int distance,
                                            BlockPos captainGround, int index) {
        for (RaidFootingSearch.Candidate c : RaidFootingSearch.follower(
                bearing, settlement.radius, distance)) {
            BlockPos found = surfaceOutsideClaim(level, settlement,
                formUpAt(settlement.center, c.bearing(), c.distance()),
                settlement.center.getY(), RaidFootingSearch.SURFACE_REACH, null);
            if (found != null) {
                return found;
            }
        }
        if (captainGround == null) {
            return null;
        }
        for (int[] offset : RaidFootingSearch.gatherOffsets(index)) {
            BlockPos found = surfaceOutsideClaim(level, settlement,
                captainGround.offset(offset[0], 0, offset[1]),
                captainGround.getY(), RaidFootingSearch.GATHER_STEP_HEIGHT, null);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Why the captain's columns failed, for the one warning line. */
    private static final class FootingTally {
        int tried;
        int unloaded;
        int wet;
        int outOfReach;
        int blocked;
    }

    /**
     * The open-air surface of a column, outside the claim. Reads the
     * {@code MOTION_BLOCKING_NO_LEAVES} heightmap, so the answer is the real
     * ground -- under a forest canopy, never on it; never in a cave or under
     * a roof; on a hill 20 blocks above the Banner as readily as level with
     * it -- instead of whatever a +-12 block scan around the Banner's own
     * height happened to hit. A lake's surface is a fluid, so it is refused.
     *
     * <p>Only an already loaded chunk is read (a column whose chunk is not
     * loaded has no footing), exactly like {@link #standableNear}.
     */
    private static BlockPos surfaceOutsideClaim(ServerLevel level,
                                                Settlement settlement,
                                                BlockPos column, int anchorY,
                                                int reach, FootingTally tally) {
        if (tally != null) {
            tally.tried++;
        }
        if (level == null || column == null || !outsideClaim(settlement, column)) {
            return null;
        }
        if (!level.getChunkSource().hasChunk(
                net.minecraft.core.SectionPos.blockToSectionCoord(column.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(column.getZ()))) {
            if (tally != null) {
                tally.unloaded++;
            }
            return null;
        }
        int top = level.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            column.getX(), column.getZ());
        if (!RaidFootingSearch.withinReach(top, anchorY, reach)) {
            if (tally != null) {
                tally.outOfReach++;
            }
            return null;
        }
        // The heightmap's first open cell, then one below (a thin snow layer
        // counts as motion-blocking yet is walked through) and one above.
        for (int dy : new int[] {0, -1, 1}) {
            BlockPos at = new BlockPos(column.getX(), top + dy, column.getZ());
            if (isStandable(level, at)) {
                return at;
            }
        }
        if (tally != null) {
            BlockPos surface = new BlockPos(column.getX(), top - 1, column.getZ());
            if (!level.getFluidState(surface).isEmpty()) {
                tally.wet++;
            } else {
                tally.blocked++;
            }
        }
        return null;
    }

    /**
     * Solid footing near a column, searched up then down. Without this a
     * band forms inside a hillside or in mid-air over a ravine and the raid
     * silently never arrives.
     *
     * <p>Only ever inspects an already loaded chunk: a column whose chunk is
     * not loaded has no footing. Reading block state there would load (or
     * even generate) the chunk synchronously on the server thread.
     */
    public static BlockPos standableNear(ServerLevel level, BlockPos column) {
        if (level == null || column == null
            || !level.getChunkSource().hasChunk(
                net.minecraft.core.SectionPos.blockToSectionCoord(column.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(column.getZ()))) {
            return null;
        }
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
        // Fluids have an empty collision shape. They are not usable raid footing:
        // FloatGoal keeps a raider at the surface instead of letting its ground
        // navigation reach the objective.
        // Leaves are excluded as footing so a band never forms up on a
        // forest canopy it cannot climb down from; fire is never a place
        // to stand. Lava/water below have no collision and already fail.
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        return level.getFluidState(pos).isEmpty()
            && level.getFluidState(pos.above()).isEmpty()
            && level.getFluidState(pos.below()).isEmpty()
            && !(feet.getBlock() instanceof BaseFireBlock)
            && !(head.getBlock() instanceof BaseFireBlock)
            && !floor.is(net.minecraft.tags.BlockTags.LEAVES)
            && feet.getCollisionShape(level, pos).isEmpty()
            && head.getCollisionShape(level, pos.above()).isEmpty()
            && !floor.getCollisionShape(level, pos.below()).isEmpty();
    }

    // ----------------------------------------- authored first-raid runtime ---

    /**
     * Commits the readiness-anchored first-raid calendar exactly once.
     *
     * <p>The founding roll and warning lead already live in RaidLifecycle;
     * this method performs no random draw. The Journey receipt is completed
     * immediately after the persisted gameplay commit; an interruption in
     * that narrow gap is recoverable through the same live authority gate.
     */
    public static boolean commitFirstRaidReadiness(ServerLevel level,
                                                   Settlement settlement) {
        FirstRaidReadinessService.Report declaration = level == null
            || settlement == null ? null
            : FirstRaidReadinessService.assessDomain(level, settlement);
        if (level == null || settlement == null
            || settlement.raidLifecycle.firstState() != FirstRaidState.PREPARING
            || declaration == null || !declaration.ready()) {
            return false;
        }
        long currentNight = Math.max(0L,
            Math.floorDiv(level.getDayTime(), RaidLifecycle.DAY_LENGTH));
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        long revisionBefore = settlement.firstRaidReadiness.revision();
        if (!lifecycle.scheduleAfterReadiness(currentNight)) {
            return false;
        }
        SettlementSavedData.get(level).setDirty();
        return finishFirstRaidReadinessCommit(level, settlement,
            revisionBefore, "readiness_calendar_committed");
    }

    /**
     * Completes only the narrow persisted SCHEDULED/FJ-560 half-commit left
     * by an interrupted declaration. It never creates or rerolls a calendar.
     * The exact live domain and Journey bridge are re-observed before the
     * missing receipt is authored, which makes a restart recoverable without
     * weakening the warning or attack gates.
     */
    public static boolean recoverFirstRaidReadinessCommit(ServerLevel level,
                                                           Settlement settlement) {
        if (level == null || settlement == null
            || settlement.raidLifecycle.firstState()
                != FirstRaidState.SCHEDULED) {
            return false;
        }
        if (FirstRaidReadinessService.assessExecution(level, settlement)
                .ready()) {
            return true;
        }
        return finishFirstRaidReadinessCommit(level, settlement,
            settlement.firstRaidReadiness.revision(),
            "readiness_calendar_recovered");
    }

    private static boolean finishFirstRaidReadinessCommit(
            ServerLevel level, Settlement settlement, long revisionBefore,
            String telemetryReason) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        // SCHEDULED is the persisted half-commit. The narrow bridge exists
        // only until the matching FJ-560 authority receipt is written; the
        // ordinary execution gate deliberately remains closed before that.
        if (!FirstRaidReadinessService.assessScheduledCommitBridge(level,
                settlement).ready()
            || !JourneyServerHooks.noteFirstRaidReadiness(level, settlement)
            || !FirstRaidReadinessService.assessExecution(level, settlement)
                .ready()) {
            return false;
        }
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.RAID_READINESS_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "first_raid_calendar:" + lifecycle.firstAttackNight(),
                revisionBefore, settlement.firstRaidReadiness.revision(),
                lifecycle.warningLead(), lifecycle.firstAttackNight(),
                telemetryReason));
        return true;
    }

    /**
     * Queues and announces the first warning once its persisted warning night
     * is due. The plan is authored for the already-rolled attack night and is
     * never replaced on later ticks or after reload.
     *
     * @return true only for the tick that created the persisted warning plan
     */
    public static boolean queueFirstWarningIfDue(ServerLevel level,
                                                 Settlement settlement,
                                                 long currentNight) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        if (lifecycle.firstState() != FirstRaidState.SCHEDULED
            || lifecycle.integrityLost()
            || currentNight < lifecycle.firstWarningNight()
            || lifecycle.queuedPlan().isPresent()
            || !FirstRaidReadinessService.assessExecution(level, settlement)
                .ready()) {
            return false;
        }
        CaptainRoster.ensureRoster(settlement, level.getRandom());
        RaidPlan plan = planFirstRaid(level, settlement,
            lifecycle.firstAttackNight());
        if (!lifecycle.queueFirstPlan(plan)) {
            // A failure after plan creation means runtime and persisted dates
            // disagree. Never keep rolling replacements for a warning the
            // player cannot audit.
            lifecycle.markIntegrityLost();
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        SettlementSavedData.get(level).setDirty();
        // The plan is already persisted. FJ-600 may advance only after its
        // exact captain/date/direction/objective line was actually emitted;
        // a generic omen alone is not the tutorial promise.
        boolean presented = leaderNameOf(settlement, plan.captainId()).isPresent()
            && RaidPresentation.warning(level, settlement);
        if (applyFirstWarningPresentationResult(lifecycle, presented)) {
            JourneyServerHooks.noteFirstRaidWarning(level, settlement, plan);
        } else {
            SettlementSavedData.get(level).setDirty();
            Hearthstead.LOGGER.warn(
                "Quarantined unpresentable first-raid warning for settlement {} "
                    + "(plan night {}, captain {}) -- no attack or Journey "
                    + "progress will follow",
                settlement.id, plan.night(), plan.captainId());
        }
        return true;
    }

    /**
     * Converts the server presentation result into an irreversible authority
     * decision. Kept pure of world I/O so the no-warning/no-attack seam is
     * directly regression-testable.
     */
    static boolean applyFirstWarningPresentationResult(RaidLifecycle lifecycle,
                                                        boolean presented) {
        if (lifecycle == null || !presented) {
            if (lifecycle != null) {
                lifecycle.markIntegrityLost();
            }
            return false;
        }
        return true;
    }

    /**
     * Exact restart receipt required before the warned plan may become live.
     * SKIPPED is the deliberate gameplay-only path; every guided Journey must
     * carry FJ-600, while quarantined or malformed authority stops closed.
     */
    public static boolean firstWarningReceiptReady(Settlement settlement) {
        if (!validQueuedFirstRaidAuthority(settlement)) {
            return false;
        }
        JourneyPresentationMode mode = settlement.journeyState.mode();
        if (mode == JourneyPresentationMode.SKIPPED) {
            return true;
        }
        return mode == JourneyPresentationMode.ACTIVE
            && settlement.journeyState.completedThrough(
                JourneyIds.FJ_560_DECLARE_RAID_READY)
            && settlement.journeyState.isCompleted(
                JourneyIds.FJ_600_RECEIVE_FIRST_WARNING);
    }

    /**
     * Repairs the narrow save gap after the exact plan was queued but before
     * its FJ-600 receipt was persisted. Re-presentation happens once; replay
     * after the receipt exists is side-effect free.
     */
    public static boolean recoverFirstRaidWarningReceipt(
            ServerLevel level, Settlement settlement) {
        if (firstWarningReceiptReady(settlement)) {
            return true;
        }
        if (!validQueuedFirstRaidAuthority(settlement)
            || settlement.journeyState.mode() != JourneyPresentationMode.ACTIVE
            || !settlement.journeyState.completedThrough(
                JourneyIds.FJ_560_DECLARE_RAID_READY)
            || settlement.journeyState.isCompleted(
                JourneyIds.FJ_600_RECEIVE_FIRST_WARNING)) {
            return false;
        }
        RaidPlan plan = settlement.raidLifecycle.queuedPlan().orElseThrow();
        if (!RaidPresentation.warning(level, settlement)
            || !JourneyServerHooks.noteFirstRaidWarning(level, settlement, plan)) {
            return false;
        }
        SettlementSavedData.get(level).setDirty();
        return firstWarningReceiptReady(settlement);
    }

    private static boolean validQueuedFirstRaidAuthority(Settlement settlement) {
        if (settlement == null || settlement.raidLifecycle == null
            || settlement.journeyState == null
            || !settlement.id.equals(settlement.journeyState.settlementId())) {
            return false;
        }
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RaidPlan plan = lifecycle.queuedPlan().orElse(null);
        return lifecycle.firstState() == FirstRaidState.SCHEDULED
            && !lifecycle.integrityLost() && RaidPlan.isValid(plan)
            && plan.night() == lifecycle.firstAttackNight()
            && leaderNameOf(settlement, plan.captainId()).isPresent();
    }

    /**
     * Spawns and seals the exact first plan players were warned about.
     * Nothing becomes active until all {@link #FIRST_RAID_BAND_SIZE} authored
     * actors were accepted by the level. A partial terrain failure therefore
     * removes every tentative entity, leaves the plan queued for a later safe
     * retry and never announces a false arrival.
     */
    public static java.util.List<RaiderEntity> startQueuedFirstRaid(
            ServerLevel level, Settlement settlement, long currentNight) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RaidPlan plan = lifecycle.queuedPlan().orElse(null);
        if (lifecycle.firstState() != FirstRaidState.SCHEDULED
            || lifecycle.integrityLost() || plan == null
            || plan.night() != lifecycle.firstAttackNight()
            || currentNight < lifecycle.firstAttackNight()
            || !firstWarningReceiptReady(settlement)
            || !FirstRaidReadinessService.assessExecution(level, settlement)
                .ready()) {
            return java.util.List.of();
        }
        String committedLeaderName = leaderNameOf(settlement,
            plan.captainId()).orElse(null);
        if (committedLeaderName == null) {
            // The warning committed one exact visible identity. Corrupting or
            // deleting it before arrival must quarantine the authored raid;
            // silently substituting a new/blank captain would make the warning
            // false and could still mint its one-shot reward.
            lifecycle.markIntegrityLost();
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }

        java.util.List<RaiderEntity> spawned = spawnFirstBand(level, settlement,
            plan);
        if (spawned.size() != FIRST_RAID_BAND_SIZE) {
            discardTentativeBand(spawned);
            return java.util.List.of();
        }

        java.util.LinkedHashSet<java.util.UUID> ids = new java.util.LinkedHashSet<>();
        boolean validCapture = validFirstRaidComposition(spawned, plan,
            committedLeaderName)
            && spawned.size() <= RaidLifecycle.MAX_PARTICIPANTS;
        for (RaiderEntity raider : spawned) {
            validCapture &= raider != null && !raider.isRemoved()
                && settlement.id.equals(raider.settlementId())
                && ids.add(raider.getUUID());
        }
        if (!validCapture || ids.isEmpty()) {
            lifecycle.markIntegrityLost();
            for (RaiderEntity raider : spawned) {
                if (raider != null && !raider.isRemoved()) {
                    raider.discard();
                }
            }
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }

        // Spawn callbacks are allowed to run arbitrary gameplay hooks. Recheck
        // the exact scheduled settlement immediately before the one-way
        // SCHEDULED -> ACTIVE transition; if anything changed, discard the
        // tentative band and leave the persisted plan queued for a safe retry.
        if (!FirstRaidReadinessService.assessExecution(level, settlement)
                .ready()
            || !leaderNameOf(settlement, plan.captainId())
                .filter(committedLeaderName::equals).isPresent()) {
            for (RaiderEntity raider : spawned) {
                if (!raider.isRemoved()) {
                    raider.discard();
                }
            }
            if (!leaderNameOf(settlement, plan.captainId())
                    .filter(committedLeaderName::equals).isPresent()) {
                lifecycle.markIntegrityLost();
                SettlementSavedData.get(level).setDirty();
            }
            return java.util.List.of();
        }

        if (!lifecycle.beginFirstRaid(plan)) {
            for (RaiderEntity raider : spawned) {
                if (!raider.isRemoved()) {
                    raider.discard();
                }
            }
            return java.util.List.of();
        }
        for (RaiderEntity raider : spawned) {
            RaidParticipantRecord.Build build = switch (raider.variant()) {
                case BRUTE -> RaidParticipantRecord.Build.BRUTE;
                case BANDIT -> RaidParticipantRecord.Build.BANDIT;
                case SKIRMISHER -> RaidParticipantRecord.Build.SKIRMISHER;
            };
            if (!lifecycle.recordParticipant(new RaidParticipantRecord(
                    raider.getUUID(), build, raider.isCaptain()))) {
                lifecycle.markIntegrityLost();
                break;
            }
        }
        if (!lifecycle.integrityLost() && !lifecycle.sealParticipants()) {
            lifecycle.markIntegrityLost();
        }
        if (lifecycle.integrityLost() || !lifecycle.participantRosterTracked()) {
            lifecycle.markIntegrityLost();
            for (RaiderEntity raider : spawned) {
                if (!raider.isRemoved()) {
                    raider.discard();
                }
            }
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }

        settlement.pendingRaid = plan;
        settlement.raidLootEscaped = false;
        settlement.raidItemsStolenTonight = 0;
        settlement.raidSettlersHurtTonight = 0;
        settlement.raidCaptainSlainId = null;
        // A terrain/chunk retry may finally arrive after the authored date.
        // Block the recurring roll for the night of the actual arrival, not
        // merely the older plan date.
        settlement.raidPressure.recordAuthoredRaidStarted(currentNight);
        RaidScars.get(level).resetArson(settlement.id);
        // Soft-lock guard (26 Sep): one stuck or unreachable raider must not
        // hold the first raid open forever (no sleep, civilians hiding).
        lifecycle.armRecurringRetreat(level.getDayTime());
        SettlementSavedData.get(level).setDirty();

        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.RAID_STARTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "first_raid:" + plan.night(),
                FirstRaidState.SCHEDULED.wireId(),
                lifecycle.firstState().wireId(), 0, ids.size(),
                "participants_sealed"));
        RaidPresentation.arrival(level, settlement);

        RaidBroadcast.send(level, settlement, Component.translatable(
            "hearthstead.message.raid_captain_leads", committedLeaderName,
            settlement.name));
        Hearthstead.LOGGER.info(
            "Authored first raid arrived at {} on planned night {} with {} participant(s)",
            settlement.name, plan.night(), spawned.size());
        return java.util.List.copyOf(spawned);
    }

    /**
     * Starts the exact recurring plan already persisted by the nightly roll.
     * The plan and its serial remain QUEUED when no entity was accepted, so a
     * later tick retries the same raid instead of rolling a replacement. The
     * compatibility PendingRaid field is written only after the actual 2-9
     * UUID capture has been validated and sealed.
     */
    public static java.util.List<RaiderEntity> startQueuedRecurringRaid(
            ServerLevel level, Settlement settlement) {
        RecurringRaidRun run = settlement.recurringRaidRun;
        RaidPlan plan = run.plan().orElse(null);
        if (settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED
            || !firstResolutionReceiptReady(settlement)
            || settlement.raidLifecycle.mayGrantReward()
            || !run.isQueued() || plan == null) {
            return java.util.List.of();
        }
        if (settlement.pendingRaid != null) {
            // QUEUED has not activated its compatibility mirror. A mirror at
            // this point is contradictory persisted/runtime evidence and must
            // be quarantined before any entities enter the world.
            run.block();
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }
        String committedLeaderName = leaderNameOf(settlement,
            plan.captainId()).orElse(null);
        if (committedLeaderName == null) {
            // Recurring raids have no separate first-warning gate. Refuse and
            // consume the malformed serial here so it cannot retry forever or
            // enter the world under an anonymous/spoofed leader name.
            run.block();
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }

        java.util.List<RaiderEntity> spawned = spawnBand(level, settlement, plan);
        if (spawned.size() < MIN_BAND) {
            discardTentativeBand(spawned);
            // Deliberately no PendingRaid, announcement or new serial: this
            // exact queued plan is the only plan that may be retried.
            return java.util.List.of();
        }
        if (!leaderNameOf(settlement, plan.captainId())
                .filter(committedLeaderName::equals).isPresent()) {
            run.block();
            for (RaiderEntity raider : spawned) {
                if (raider != null && !raider.isRemoved()) {
                    raider.discard();
                }
            }
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }

        java.util.LinkedHashSet<java.util.UUID> ids = new java.util.LinkedHashSet<>();
        boolean validCapture = spawned.size() >= MIN_BAND
            && spawned.size() <= RecurringRaidRun.MAX_PARTICIPANTS;
        for (RaiderEntity raider : spawned) {
            validCapture &= raider != null && !raider.isRemoved()
                && settlement.id.equals(raider.settlementId())
                && ids.add(raider.getUUID());
        }
        if (!validCapture || ids.isEmpty()
            || !run.sealAndActivate(plan, ids)) {
            // A non-empty disagreement is evidence, not a spawn retry. Keep
            // its serial quarantined and remove every entity we did accept.
            run.block();
            for (RaiderEntity raider : spawned) {
                if (raider != null && !raider.isRemoved()) {
                    raider.discard();
                }
            }
            SettlementSavedData.get(level).setDirty();
            return java.util.List.of();
        }

        settlement.pendingRaid = plan;
        settlement.raidLootEscaped = false;
        settlement.raidItemsStolenTonight = 0;
        settlement.raidSettlersHurtTonight = 0;
        settlement.raidCaptainSlainId = null;
        RaidScars.get(level).resetArson(settlement.id);
        // Soft-lock guard: whoever is still standing at the next dawn flees.
        settlement.raidLifecycle.armRecurringRetreat(level.getDayTime());
        SettlementSavedData.get(level).setDirty();

        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.RAID_STARTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "recurring_raid:" + run.activeSerial(),
                RecurringRaidRun.Stage.QUEUED.wireId(),
                run.stage().wireId(), 0, ids.size(),
                "participants_sealed"));

        // Recurring raids own the same unmistakable arrival contract as the
        // authored first raid: horn, settlement warning and guard feedback
        // only after the exact participant set is committed.
        RaidPresentation.arrival(level, settlement);

        RaidBroadcast.send(level, settlement, Component.translatable(
            "hearthstead.message.raid_captain_leads", committedLeaderName,
            settlement.name));
        Hearthstead.LOGGER.info(
            "Recurring raid serial {} arrived at {} for planned night {} with {} participant(s)",
            run.activeSerial(), settlement.name, plan.night(), spawned.size());
        return java.util.List.copyOf(spawned);
    }

    /** How far past the settlement edge a raid still counts as in progress. */
    public static final int RAID_BOUNDS_MARGIN = 48;

    /**
     * How far past the settlement edge a player counts as present for a raid:
     * a recurring raid only starts (and rolls) with such a player, raid lines
     * reach them ({@link RaidBroadcast}) and the boss bar shows. Sleep denial
     * uses the same distance ({@link RaidSleepPolicy}): a player who can
     * neither start nor hear a raid is not kept awake by it. Before 26 Sep
     * sleep was denied out to radius + 64, so a player 33-64 blocks outside
     * the edge could not sleep while the raid waited for someone closer.
     */
    public static final int PLAYER_PRESENCE_MARGIN = 32;

    /**
     * Dawn retreat for recurring raids (soft-lock guard). Raiders still alive
     * when the armed dawn arrives -- stuck, unreachable or in unloaded chunks
     * -- flee: loaded ones are removed, unloaded ones are remembered and
     * removed when they next load. The raid resolves as "repelled late":
     * no Coin/Blessing reward and no pressure/captain change, but also no
     * defeat penalty; the cadence continues exactly as after any other raid
     * (the next attack 3-4 days later, see {@link RaidCadence}) and the raid
     * level neither rises nor keeps a breather. First raids are
     * never retreated: their authored contract stays terminal-ledger only.
     */
    public static boolean resolveRecurringRetreat(ServerLevel level,
                                                  Settlement settlement) {
        return resolveRecurringRetreat(level, settlement, null);
    }

    /**
     * Same retreat, announced with {@code message} instead of the dawn line
     * (raid parley: tribute paid, truce agreed, captain yielded a duel).
     * Null keeps the dawn line.
     */
    public static boolean resolveRecurringRetreat(ServerLevel level,
                                                  Settlement settlement,
                                                  @javax.annotation.Nullable Component message) {
        return resolveRecurringRetreat(level, settlement, message, true);
    }

    /**
     * As above; {@code discardLoaded} false leaves the loaded band standing so
     * the caller can walk it off (conversation Departure: paid or persuaded
     * raids walk away instead of vanishing). Unloaded stragglers still leave
     * when they next load.
     */
    public static boolean resolveRecurringRetreat(ServerLevel level,
                                                  Settlement settlement,
                                                  @javax.annotation.Nullable Component message,
                                                  boolean discardLoaded) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RecurringRaidRun recurring = settlement.recurringRaidRun;
        if (lifecycle.firstState() != FirstRaidState.COMPLETED
            || lifecycle.isAuthoredFirstRaidActive()
            || lifecycle.isLegacyBridgeActive() || !recurring.isActive()) {
            return false;
        }
        RaidPlan plan = recurring.plan().orElse(null);
        if (plan == null) {
            return false;
        }
        java.util.Set<java.util.UUID> terminal = recurring.terminalParticipants();
        java.util.List<java.util.UUID> stragglers = new java.util.ArrayList<>();
        for (java.util.UUID id : recurring.participants()) {
            if (!terminal.contains(id)) {
                stragglers.add(id);
            }
        }
        long serial = recurring.activeSerial();
        if (!recurring.resolveRetreat()) {
            // ACTIVE shape is validated on load, so this is contradictory
            // runtime evidence: consume the serial fail-closed.
            recurring.block();
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        // Remember BEFORE discarding: a discard of a no-longer-active
        // participant must never read as evidence for a newer raid.
        lifecycle.rememberRetreatedRaiders(stragglers);
        lifecycle.recordRecurringCadence(level.getGameTime(), level.getDayTime(),
            RaidLifecycle.CadenceOutcome.RETREATED, pickCadenceIntervalDays(level));
        settlement.pendingRaid = null;
        settlement.raidLootEscaped = false;
        settlement.raidCaptainSlainId = null;
        RaidScars.get(level).resetArson(settlement.id);
        int fled = 0;
        for (java.util.UUID id : stragglers) {
            net.minecraft.world.entity.Entity entity = level.getEntity(id);
            if (entity instanceof RaiderEntity raider && !raider.isRemoved()) {
                if (!discardLoaded) {
                    continue;
                }
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                    raider.getX(), raider.getY() + 0.5D, raider.getZ(),
                    8, 0.3D, 0.4D, 0.3D, 0.02D);
                raider.discard();
                fled++;
            }
        }
        SettlementSavedData.get(level).setDirty();
        RaidBroadcast.send(level, settlement, message != null ? message : Component.translatableWithFallback(
            "hearthstead.message.raid_dawn_retreat",
            "Dawn breaks over %s. The last raiders flee into the wilds; the raid is over, but nothing was won.",
            settlement.name));
        Hearthstead.LOGGER.info(
            "Recurring raid serial {} on {} retreated at dawn: {} straggler(s), {} loaded and removed",
            serial, settlement.name, stragglers.size(), fled);
        return true;
    }

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
        return resolveIfOver(level, settlement, false);
    }

    /**
     * Dawn retreat for the authored first raid (soft-lock guard, 26 Sep). It
     * mirrors {@link #resolveRecurringRetreat}: at the first dawn after the
     * raid night, every sealed participant still standing -- stuck,
     * unreachable or in an unloaded chunk -- flees. Loaded ones are removed,
     * unloaded ones are remembered and removed when they next load. The raid
     * then closes through its normal terminal ledger, so the Journey
     * advances, the aftermath is logged and the objective still decides
     * held/hit, but a retreat pays no Coins or Blessing and changes neither
     * pressure nor the captain.
     */
    public static boolean resolveFirstRaidRetreat(ServerLevel level,
                                                  Settlement settlement) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        if (!lifecycle.isAuthoredFirstRaidActive()) {
            return false;
        }
        java.util.List<java.util.UUID> stragglers = lifecycle.retreatFirstRaid();
        if (stragglers == null) {
            return false;
        }
        // Remember BEFORE discarding: a late load or discard of a straggler
        // must never read as evidence for a newer raid.
        lifecycle.rememberRetreatedRaiders(stragglers);
        SettlementSavedData.get(level).setDirty();
        int fled = 0;
        for (java.util.UUID id : stragglers) {
            net.minecraft.world.entity.Entity entity = level.getEntity(id);
            if (entity instanceof RaiderEntity raider && !raider.isRemoved()) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                    raider.getX(), raider.getY() + 0.5D, raider.getZ(),
                    8, 0.3D, 0.4D, 0.3D, 0.02D);
                raider.discard();
                fled++;
            }
        }
        RaidBroadcast.send(level, settlement, Component.translatableWithFallback(
            "hearthstead.message.raid_dawn_retreat",
            "Dawn breaks over %s. The last raiders flee into the wilds; the raid is over, but nothing was won.",
            settlement.name));
        boolean resolved = resolveIfOver(level, settlement, true);
        if (!resolved && settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE) {
            // A retreat is never reward-capable, including a later close.
            lifecycle.markIntegrityLost();
            SettlementSavedData.get(level).setDirty();
        }
        Hearthstead.LOGGER.info(
            "First raid on {} retreated at dawn: {} straggler(s), {} loaded and removed, resolved={}",
            settlement.name, stragglers.size(), fled, resolved);
        return resolved;
    }

    private static boolean resolveIfOver(ServerLevel level, Settlement settlement,
                                         boolean dawnRetreat) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RecurringRaidRun recurring = settlement.recurringRaidRun;
        boolean authoredFirst = lifecycle.isAuthoredFirstRaidActive();
        boolean legacyFirst = lifecycle.isLegacyBridgeActive();
        boolean recurringMayOwnRuntime = !authoredFirst && !legacyFirst
            && lifecycle.firstState() == FirstRaidState.COMPLETED;
        boolean authoredRecurring = recurringMayOwnRuntime && recurring.isActive();
        boolean legacyRecurring = recurringMayOwnRuntime
            && recurring.isLegacyBridgeActive();
        // Damaged non-legacy first-raid state must never reach the generic
        // loaded-AABB path through a surviving pendingRaid mirror. The only
        // AABB closures left are the two explicitly marked migration bridges.
        if (!authoredFirst && !legacyFirst && lifecycle.integrityLost()
            && lifecycle.firstState() != FirstRaidState.COMPLETED) {
            return false;
        }
        RaidPlan plan;
        if (authoredFirst || legacyFirst) {
            plan = lifecycle.activePlan().orElse(null);
        } else if (authoredRecurring || legacyRecurring) {
            plan = recurring.plan().orElse(null);
        } else {
            // PendingRaid alone is never completion authority in v3.
            return false;
        }
        if (plan == null) {
            return false;
        }
        String committedLeaderName = leaderNameOf(settlement,
            plan.captainId()).orElse(null);
        if (committedLeaderName == null) {
            // No reward, report or replacement identity may be inferred from
            // malformed captain data. Preserve the active authored evidence
            // for diagnosis instead of closing under "?" or a spoofed name.
            if (authoredFirst || legacyFirst) {
                lifecycle.markIntegrityLost();
            } else if (authoredRecurring) {
                recurring.markIntegrityLost();
            } else {
                recurring.block();
            }
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        int blessingEarnedBefore = settlement.blessingState.earned();
        int blessingRevisionBefore = settlement.blessingState.revision();
        int pressureBefore = settlement.raidPressure.pressure();
        int participantCountBefore = authoredFirst
            ? lifecycle.participants().size()
            : authoredRecurring ? recurring.participants().size()
                : livingRaidersOf(level, settlement).size();
        if (authoredFirst || legacyFirst) {
            // The lifecycle plan is authoritative after reload. A missing or
            // mismatched compatibility mirror is repaired; it is never
            // allowed to substitute a different band. Authored mismatch
            // disarms its reward. A legacy bridge was unauditable already.
            if (settlement.pendingRaid == null) {
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            } else if (!settlement.pendingRaid.equals(plan)) {
                if (authoredFirst) {
                    lifecycle.markIntegrityLost();
                }
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            }
            if (authoredFirst && !lifecycle.allParticipantsTerminal()) {
                return false; // unloaded is not terminal
            }
        }
        if (authoredRecurring || legacyRecurring) {
            // The recurring run owns the plan. A missing mirror is repairable.
            // A mismatched authored mirror loses reward integrity; a mismatched
            // legacy bridge has no participant evidence and stops entirely.
            if (settlement.pendingRaid == null) {
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            } else if (!settlement.pendingRaid.equals(plan)) {
                if (authoredRecurring) {
                    recurring.markIntegrityLost();
                    settlement.pendingRaid = plan;
                } else {
                    recurring.block();
                    SettlementSavedData.get(level).setDirty();
                    return false;
                }
                SettlementSavedData.get(level).setDirty();
            }
            if (authoredRecurring && !recurring.allParticipantsTerminal()) {
                return false; // loaded or unloaded is irrelevant; terminal is persisted
            }
        }
        if ((legacyFirst || legacyRecurring)
            && !livingRaidersOf(level, settlement).isEmpty()) {
            return false; // explicit pre-ledger compatibility bridge is still going
        }
        RaidCaptain captain = captainOf(settlement, plan.captainId());
        // Read BEFORE resetArson (below) clears it -- the arson tally is
        // this raid's own already-tracked BRANN signal, and it must feed
        // objectiveSucceeded while it is still live.
        int arsonCount = RaidScars.get(level).arsonThisRaid(settlement.id);
        // Whether the settlement HELD is not about who died -- it is about
        // whether the raiders got what they came for, AND that has to be
        // judged against THIS raid's own objective (2026-08-26 raid-night
        // audit): a BRANN band that burns nothing failed even though nothing
        // was "stolen" (raidLootEscaped is a KORN-only signal, never set by
        // arson or by hunting settlers), so keying every objective off it
        // let a raid that gutted the village still broadcast "held through
        // the raid". See objectiveSucceeded for the per-objective signal.
        boolean lost = objectiveSucceeded(plan.objective(), settlement, arsonCount);
        JourneyOutcome resolutionOutcome = !lost ? JourneyOutcome.HELD
            : settlement.population() <= 0
                ? JourneyOutcome.SETTLEMENT_LOST : JourneyOutcome.HIT;
        RaidLogEntry committedAftermath = new RaidLogEntry(plan.night(),
            committedLeaderName, plan.objective().id(), !lost,
            settlement.raidItemsStolenTonight,
            settlement.raidSettlersHurtTonight,
            projectedStageAfter(pressureBefore, lost).id());
        if (!RaidLogEntry.isValid(committedAftermath)) {
            if (authoredFirst || legacyFirst) {
                lifecycle.markIntegrityLost();
            } else if (authoredRecurring) {
                recurring.markIntegrityLost();
            } else {
                recurring.block();
            }
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        // Reserve the exact durable debt before consuming the authoritative
        // victory. First recovery keeps its one 8-Coin reward; recurring raids
        // use the smaller 4-Coin repair reward.
        int victoryCoins = dawnRetreat ? 0
            : authoredFirst ? RaidCoinRewards.FIRST_VICTORY_COINS
            : authoredRecurring ? RaidCoinRewards.RECURRING_VICTORY_COINS : 0;
        if (!lost && victoryCoins > 0 && ((authoredFirst && !lifecycle.integrityLost())
                || (authoredRecurring && !recurring.integrityLost()))
                && !settlement.raidCoinRewards.hasCapacity(victoryCoins)) {
            return false;
        }
        RecurringRaidRun.Resolution recurringResolution =
            RecurringRaidRun.Resolution.INVALID;
        if (authoredFirst && !lifecycle.completeFirstRaid(resolutionOutcome,
                committedAftermath)) {
            // All-terminal was checked above; reaching this branch means the
            // persisted state changed under us. Fail closed before applying
            // pressure, history or a reward twice.
            lifecycle.markIntegrityLost();
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        if (authoredFirst && dawnRetreat) {
            // Consume the one reward marker: a retreat pays nothing, now or
            // through the crash-recovery path.
            lifecycle.markRewardGranted();
        }
        if (legacyFirst && !lifecycle.completeLegacyBridge(plan)) {
            // Provenance or structure changed after the checks above. Apply
            // no pressure/history side effects from an unauditable close.
            lifecycle.markIntegrityLost();
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        if (authoredRecurring) {
            recurringResolution = recurring.resolve(!lost);
            if (recurringResolution == RecurringRaidRun.Resolution.INVALID) {
                // Do not apply pressure/history or attempt a reward unless
                // this exact serial was atomically consumed by its ledger.
                recurring.markIntegrityLost();
                SettlementSavedData.get(level).setDirty();
                return false;
            }
        }
        if (legacyRecurring && !recurring.completeLegacyBridge(plan)) {
            recurring.block();
            SettlementSavedData.get(level).setDirty();
            return false;
        }
        if (dawnRetreat) {
            // Like a recurring retreat: no pressure or captain change.
        } else if (lost) {
            settlement.raidPressure.recordLost();
            if (captain != null) {
                captain.recordVictory();
            }
        } else {
            settlement.raidPressure.recordRepelled();
            com.hearthstead.fx.FxHooks.raidWon(level, settlement);
            // Tech tree (Wayside Shrine / Cathedral): thanksgiving morale.
            com.hearthstead.settlement.techtree.effects.CommonsEffects.onRaidWon(level, settlement);
            if (captain != null) {
                captain.recordDefeat();
            }
        }
        // SAGA v1: told apart from the band merely being driven off -- set
        // by RaiderEntity#die on the specific raider wearing the captain
        // flag, so a captain who fought and died reads differently from one
        // whose followers simply scattered.
        boolean captainSlain = captain != null
            && captain.id().equals(settlement.raidCaptainSlainId);
        settlement.pendingRaid = null;
        settlement.raidLootEscaped = false;
        settlement.raidCaptainSlainId = null; // reset so tomorrow starts honest
        // SLICE REPAIR-1: the arson budget describes exactly one raid, like
        // the stolen/hurt tallies below it -- reset when the raid closes.
        // The SCARS themselves are deliberately NOT reset: they are the
        // repair dugnad's work queue, and they outlive the raid until a
        // settler actually fixes them (RepairWorkGoal).
        RaidScars.get(level).resetArson(settlement.id);
        recordAftermath(level, settlement, plan, captain, committedLeaderName,
            !lost, captainSlain, arsonCount, committedAftermath);
        // B03 starts recovery after, and only after, this resolved attack.
        // The lifecycle stores server game-time, so sleep/day-time changes
        // cannot shorten the interval; a corrupt schedule blocks only itself.
        // Regular cadence (see RaidCadence): the next attack night is this
        // raid night plus one day pick from the configured window (default
        // 3..4), the warning one dusk earlier, and the outcome feeds the
        // MineColonies-style raid level used by bandSizeFor.
        lifecycle.recordRecurringCadence(level.getGameTime(), level.getDayTime(),
            dawnRetreat ? RaidLifecycle.CadenceOutcome.RETREATED
                : lost ? RaidLifecycle.CadenceOutcome.LOST
                : RaidLifecycle.CadenceOutcome.HELD,
            pickCadenceIntervalDays(level));
        if (authoredFirst) {
            JourneyServerHooks.noteFirstRaidResolved(level, settlement, plan,
                resolutionOutcome);
        }
        if (authoredFirst && firstResolutionReceiptReady(settlement)) {
            grantPendingFirstRaidReward(level, settlement);
        }
        if (recurringResolution == RecurringRaidRun.Resolution.GRANT_OFFER) {
            settlement.raidCoinRewards.awardRecurring(recurring.lastResolvedSerial());
            SettlementSavedData.get(level).setDirty();
            settlement.raidCoinRewards.deliver(level, settlement);
            if (settlement.blessingState.grantOffer()) {
                SettlementSavedData.get(level).setDirty();
                BlessingPresentation.offerEarned(level, settlement);
            } else if (!settlement.blessingState.quarantined()
                && !settlement.blessingState.hasCapacityForOffer()) {
                Hearthstead.LOGGER.warn(
                    "Recurring raid reward for {} reached Blessing ledger capacity; "
                        + "processed serial will not be replayed",
                    settlement.name);
            } else {
                // The recurring serial's reward decision is already persisted
                // as processed. Never retry after reload and accidentally mint
                // an offer if the ledger is later repaired or emptied.
                Hearthstead.LOGGER.warn(
                    "Recurring raid reward for {} could not enter the Blessing ledger; "
                        + "the processed serial will not be replayed",
                    settlement.name);
            }
        }
        SettlementSavedData.get(level).setDirty();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.RAID_RESOLVED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                authoredFirst || legacyFirst ? "first_raid:" + plan.night()
                    : "recurring_raid:" + recurring.lastResolvedSerial(),
                pressureBefore, settlement.raidPressure.pressure(),
                participantCountBefore, 0,
                JourneyServerHooks.raidResolutionAuthorityReason(
                    resolutionOutcome)));
        if (settlement.blessingState.earned() > blessingEarnedBefore) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.RAID_REWARD_ISSUED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "blessing_offer:" + settlement.blessingState.offerSerial(),
                    blessingRevisionBefore,
                    settlement.blessingState.revision(),
                    blessingEarnedBefore, settlement.blessingState.earned(),
                    "physical_seal_offer", 0, 0, 0,
                    "authoritative_raid_reward"));
        }
        if (!dawnRetreat) {
            RaidPresentation.resolved(level, settlement, !lost);
        }
        Hearthstead.LOGGER.info(
            "Raid on {} is over -- {} {} (pressure now {}, stage {})",
            settlement.name, committedLeaderName,
            lost ? "got away with the stores" : "was driven off",
            settlement.raidPressure.pressure(),
            settlement.raidPressure.stage().id());
        int scarCount = RaidScars.get(level).scarsOf(settlement.id).size();
        if (scarCount > 0) {
            // Logged rather than silent, same doctrine as the roll below:
            // "the raid burnt things and nobody ever repaired them" must be
            // findable in evidence, not deduced from a hole in the world.
            Hearthstead.LOGGER.info(
                "{} scar(s) left on {} -- the repair dugnad has work",
                scarCount, settlement.name);
        }
        return true;
    }

    /**
     * Whether THIS raid's objective actually succeeded -- the single source
     * {@link #resolveIfOver} judges "held" from (2026-08-26 raid-night
     * audit). Before this method existed, the whole settlement's fate was
     * read off {@link Settlement#raidLootEscaped} alone, a flag only ever
     * set by {@code RaiderLootGoal}'s successful withdrawal (KORN). A BRANN
     * band that burned every building it reached, or a BLOD band that hurt
     * every settler it could catch, left that flag false and so was reported
     * as repelled -- the game asserting an outcome nothing in the world
     * backed.
     *
     * <p>Each arm reads the signal that objective's own raider goal already
     * tracks, live, for exactly this raid -- nothing new is invented:
     * <ul>
     *   <li>{@code KORN} -- {@link Settlement#raidLootEscaped}, set the
     *       instant a laden raider gets clear ({@code RaiderLootGoal}).
     *   <li>{@code BLOD} -- {@link Settlement#raidSettlersHurtTonight},
     *       incremented on every landed hit during a live raid
     *       ({@code RaiderEntity#doHurtTarget}); a hurt settler already
     *       covers a downed one, since the killing blow is itself a landed
     *       hit before death is resolved.
     *   <li>{@code BRANN} -- {@code arsonCount}, this raid's own torching
     *       tally ({@link RaidScars#arsonThisRaid}, filled by
     *       {@link #tickArson}), read by the caller before the ledger resets
     *       it for the next raid.
     * </ul>
     *
     * <p>{@code LOSEPENGER} is disarmed ({@link RaidObjective#isAvailableAt})
     * and {@link #planRaid} can never choose it, so this arm is unreachable
     * from a live roll -- kept only so a raid plan persisted from before the
     * disarm still resolves sanely on an old save, falling back to the same
     * loot signal every objective used before this method existed.
     */
    private static boolean objectiveSucceeded(RaidObjective objective, Settlement settlement,
                                               int arsonCount) {
        return switch (objective) {
            case KORN -> settlement.raidLootEscaped;
            case BLOD -> settlement.raidSettlersHurtTonight > 0;
            case BRANN -> arsonCount > 0;
            case LOSEPENGER -> settlement.raidLootEscaped;
        };
    }

    /**
     * The scar (D-A3-8) and the aftermath the design calls for: "repair
     * dugnad + defense report". This is the report half -- what was stolen,
     * who was hurt, and what the threat reads as now, both logged on the
     * settlement (a capped history, {@link #MAX_RAID_LOG}) and read out to
     * every nearby player, the same morning the raid actually ended rather
     * than only ever visible through {@code /hearthstead info}. The repair
     * half is no longer missing: the block damage this raid recorded into
     * {@link RaidScars} stays behind as the dugnad's work queue, and
     * {@code RepairWorkGoal} restores it block by block (SLICE REPAIR-1).
     *
     * <p>The tallies are read here and reset here: they describe exactly
     * one raid, accumulated live as it happened ({@code RaiderLootGoal}'s
     * successful withdrawal, {@code RaiderEntity#doHurtTarget}), never a
     * running lifetime total.
     *
     * <p>SAGA v1 rides along here too: {@link CaptainRoster#recordRaidOutcome}
     * applies Saga outcome growth, succeeds a slain captain with a lieutenant,
     * and grants an earned/upgraded epithet for a raid that got away with the
     * goods. The report still uses the exact identity committed at warning and
     * arrival; a newly earned epithet is announced now but first becomes the
     * captain's field identity on their next raid.
     *
     * @param arsonCount this raid's own torching tally, captured by the
     *                   caller before {@link RaidScars#resetArson} clears
     *                   it -- BRANN's report reads it directly rather than
     *                   re-deriving "held" from the generic KORN wording.
     */
    private static void recordAftermath(ServerLevel level, Settlement settlement,
                                        RaidPlan plan, RaidCaptain captain,
                                        String committedLeaderName, boolean held,
                                        boolean captainSlain, int arsonCount,
                                        RaidLogEntry committedAftermath) {
        // Apply earned Saga growth now, but do not retroactively rename the
        // raid that just happened. Its warning, arrival, field nameplate and
        // Aftermath all retain committedLeaderName; a newly earned epithet is
        // separately announced by CaptainRoster and first leads the NEXT raid.
        CaptainRoster.recordRaidOutcome(level, settlement, captain,
            plan.objective(), held, captainSlain, level.getRandom());
        String captainName = committedLeaderName;
        String stageAfter = committedAftermath.stageAfterId();
        settlement.raidLog.add(committedAftermath);
        while (settlement.raidLog.size() > MAX_RAID_LOG) {
            settlement.raidLog.remove(0); // oldest history fades first
        }

        Component stage = Component.translatable("hearthstead.raid.stage." + stageAfter);
        Component report = reportFor(plan.objective(), held, settlement, captainName,
            arsonCount, stage);
        RaidBroadcast.send(level, settlement, report);
        if (held) {
            // Living village: the settlers near the Banner cheer ("We held!").
            com.hearthstead.ambient.LivingVillage.onRaidHeld(level, settlement);
        }

        settlement.raidItemsStolenTonight = 0;
        settlement.raidSettlersHurtTonight = 0;
    }

    /**
     * The morning report's own wording, matched to what this raid's
     * objective actually is (2026-08-26 raid-night audit) -- the generic
     * "item(s) were stolen" phrasing read as a non sequitur (always zero,
     * never explained) for a BRANN raid that held, and actively hid the
     * building damage for one that did not, since nothing about arson was
     * ever named. KORN keeps the original wording verbatim -- stolen goods
     * are exactly what a KORN raid is about -- and the disarmed LOSEPENGER
     * arm (unreachable, see {@link #objectiveSucceeded}) falls back to it
     * too, matching this method's behaviour before objectives were split.
     */
    private static Component reportFor(RaidObjective objective, boolean held,
                                       Settlement settlement, String captainName,
                                       int arsonCount, Component stage) {
        return switch (objective) {
            case BLOD -> held
                ? Component.translatable("hearthstead.message.raid_defense_held_blod",
                    settlement.name, stage)
                : Component.translatable("hearthstead.message.raid_defense_lost_blod",
                    settlement.name, captainName, settlement.raidSettlersHurtTonight, stage);
            case BRANN -> held
                ? Component.translatable("hearthstead.message.raid_defense_held_brann",
                    settlement.name, settlement.raidSettlersHurtTonight, stage)
                : Component.translatable("hearthstead.message.raid_defense_lost_brann",
                    settlement.name, captainName, arsonCount,
                    settlement.raidSettlersHurtTonight, stage);
            default -> held // KORN, and the unreachable disarmed LOSEPENGER
                ? Component.translatable("hearthstead.message.raid_defense_held",
                    settlement.name, settlement.raidSettlersHurtTonight,
                    settlement.raidItemsStolenTonight, stage)
                : Component.translatable("hearthstead.message.raid_defense_lost",
                    settlement.name, captainName, settlement.raidItemsStolenTonight,
                    settlement.raidSettlersHurtTonight, stage);
        };
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

    /**
     * The one player-visible identity owned by a persisted raid plan.
     *
     * <p>Warning, arrival, field nameplate and Aftermath all resolve through
     * this rule. Duplicate ids or an absent/blank/overlong/control-bearing
     * name are not "close enough": they make the identity unauditable and
     * therefore return empty. Saga remains a decorator; a wild captain uses
     * the base RaidCaptain name, while a tracked captain uses the Saga name
     * (including only epithets already earned before this raid).
     */
    public static java.util.Optional<String> leaderNameOf(
            Settlement settlement, java.util.UUID captainId) {
        return leaderIdentity(settlement, captainId)
            .map(LeaderIdentity::displayName);
    }

    /** Shared wire/persistence bound for any captain name shown to a player. */
    public static boolean isValidLeaderName(String name) {
        if (name == null || name.isBlank()
            || name.length() > RaidLogEntry.MAX_CAPTAIN_NAME
            || !name.equals(name.strip()) || name.indexOf('?') >= 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            int type = Character.getType(character);
            if (Character.isISOControl(character)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Exact FJ-610 receipt gate. The persisted terminal and active plan must
     * agree before either reward issuance or recurring raid authority moves.
     * Recovery additionally requires the exact newest Aftermath row; after a
     * valid receipt, that capped history may legitimately age out.
     */
    public static boolean firstResolutionReceiptReady(Settlement settlement) {
        RaidLifecycle.FirstRaidTerminal terminal =
            exactCompletedFirstRaidTerminal(settlement);
        if (terminal == null) {
            return false;
        }
        JourneyPresentationMode mode = settlement.journeyState.mode();
        if (mode == JourneyPresentationMode.SKIPPED) {
            return true;
        }
        return mode != JourneyPresentationMode.QUARANTINED
            && settlement.journeyState.completedThrough(
                JourneyIds.FJ_610_FIRST_RAID_RESOLVED)
            && settlement.journeyState.outcome() == terminal.outcome();
    }

    /**
     * Repairs only the exact crash gap after the terminal raid fact and its
     * Aftermath row were persisted but before FJ-610 was authored.
     */
    public static boolean recoverFirstRaidResolutionReceipt(
            ServerLevel level, Settlement settlement) {
        if (firstResolutionReceiptReady(settlement)) {
            return true;
        }
        RaidLifecycle.FirstRaidTerminal terminal =
            exactCompletedFirstRaidTerminal(settlement);
        if (terminal == null
            || !exactTerminalAftermathIsNewest(settlement, terminal)
            || settlement.journeyState.mode() != JourneyPresentationMode.ACTIVE
            || !settlement.journeyState.completedThrough(
                JourneyIds.FJ_600_RECEIVE_FIRST_WARNING)
            || settlement.journeyState.isCompleted(
                JourneyIds.FJ_610_FIRST_RAID_RESOLVED)
            || !JourneyServerHooks.noteFirstRaidResolved(level, settlement,
                terminal.plan(), terminal.outcome())) {
            return false;
        }
        SettlementSavedData.get(level).setDirty();
        return firstResolutionReceiptReady(settlement);
    }

    /**
     * Completed-state restart reconciliation. It is deliberately idempotent:
     * FJ-610 is append-only, the report is never appended here, and the
     * lifecycle reward bit is consumed exactly once.
     */
    public static boolean reconcileCompletedFirstRaid(ServerLevel level,
                                                       Settlement settlement) {
        if (!firstResolutionReceiptReady(settlement)
            && !recoverFirstRaidResolutionReceipt(level, settlement)) {
            return false;
        }
        return grantPendingFirstRaidReward(level, settlement);
    }

    private static RaidLifecycle.FirstRaidTerminal exactCompletedFirstRaidTerminal(
            Settlement settlement) {
        if (settlement == null || settlement.raidLifecycle == null
            || settlement.journeyState == null
            || !settlement.id.equals(settlement.journeyState.settlementId())) {
            return null;
        }
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RaidPlan active = lifecycle.activePlan().orElse(null);
        RaidLifecycle.FirstRaidTerminal terminal =
            lifecycle.firstRaidTerminal().orElse(null);
        if (lifecycle.firstState() != FirstRaidState.COMPLETED
            || lifecycle.integrityLost() || !RaidPlan.isValid(active)
            || terminal == null || !terminal.matches(active)) {
            return null;
        }
        return terminal;
    }

    private static boolean exactTerminalAftermathIsNewest(
            Settlement settlement,
            RaidLifecycle.FirstRaidTerminal terminal) {
        if (settlement.raidLog.isEmpty()
            || settlement.raidLog.size() > MAX_RAID_LOG) {
            return false;
        }
        int exactMatches = 0;
        for (RaidLogEntry entry : settlement.raidLog) {
            if (!RaidLogEntry.isValid(entry)) {
                return false;
            }
            if (terminal.aftermath().equals(entry)) {
                exactMatches++;
            }
        }
        return exactMatches == 1
            && terminal.aftermath().equals(settlement.raidLog.getLast());
    }

    private static boolean grantPendingFirstRaidReward(ServerLevel level,
                                                       Settlement settlement) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        if (!lifecycle.mayGrantReward()) {
            return true;
        }
        if (!firstResolutionReceiptReady(settlement)) {
            return false;
        }
        if (!settlement.raidCoinRewards.awardFirst()) return false;
        SettlementSavedData.get(level).setDirty();
        settlement.raidCoinRewards.deliver(level, settlement);
        if (settlement.blessingState.grantOffer()) {
            lifecycle.markRewardGranted();
            SettlementSavedData.get(level).setDirty();
            BlessingPresentation.offerEarned(level, settlement);
            return true;
        }
        if (!settlement.blessingState.quarantined()
            && !settlement.blessingState.hasCapacityForOffer()) {
            // Defensive million-entry counter saturation, not a gameplay
            // rank cap. Consume the decision; reload must never retry it.
            lifecycle.markRewardGranted();
            SettlementSavedData.get(level).setDirty();
            Hearthstead.LOGGER.warn(
                "First-raid reward for {} reached Blessing ledger capacity",
                settlement.name);
            return true;
        }
        lifecycle.markIntegrityLost();
        SettlementSavedData.get(level).setDirty();
        return false;
    }

    private static RaidPressure.Stage projectedStageAfter(int pressureBefore,
                                                           boolean lost) {
        int pressure = Mth.clamp(pressureBefore
            + (lost ? -RaidPressure.LOSS_RELIEF : RaidPressure.REPEL_GAIN),
            0, RaidPressure.MAX_PRESSURE);
        if (pressure >= RaidPressure.BELEIRING_THRESHOLD) {
            return RaidPressure.Stage.BELEIRING;
        }
        if (pressure >= RaidPressure.VARSEL_THRESHOLD) {
            return RaidPressure.Stage.VARSEL;
        }
        if (pressure >= RaidPressure.URO_THRESHOLD) {
            return RaidPressure.Stage.URO;
        }
        return RaidPressure.Stage.ROLIG;
    }

    private static java.util.Optional<LeaderIdentity> leaderIdentity(
            Settlement settlement, java.util.UUID captainId) {
        if (settlement == null || captainId == null
            || (captainId.getMostSignificantBits() == 0L
                && captainId.getLeastSignificantBits() == 0L)) {
            return java.util.Optional.empty();
        }
        RaidCaptain raidCaptain = null;
        for (RaidCaptain candidate : settlement.raidCaptains) {
            if (candidate != null && captainId.equals(candidate.id())) {
                if (raidCaptain != null) {
                    return java.util.Optional.empty(); // ambiguous persisted id
                }
                raidCaptain = candidate;
            }
        }
        if (raidCaptain == null) {
            return java.util.Optional.empty();
        }
        if (!isValidLeaderName(raidCaptain.name())) {
            return java.util.Optional.empty();
        }

        Captain sagaCaptain = null;
        for (Captain candidate : settlement.sagaRoster) {
            if (candidate != null && captainId.equals(candidate.id())) {
                if (sagaCaptain != null) {
                    return java.util.Optional.empty(); // ambiguous decorator
                }
                sagaCaptain = candidate;
            }
        }
        if (sagaCaptain != null
            && (!isValidLeaderName(sagaCaptain.firstName())
                || (sagaCaptain.epithet() != null
                    && !isValidLeaderName(sagaCaptain.epithet()))
                || (sagaCaptain.swornTo() != null
                    && !isValidLeaderName(sagaCaptain.swornTo())))) {
            return java.util.Optional.empty();
        }
        String displayName;
        try {
            displayName = sagaCaptain == null
                ? raidCaptain.name() : sagaCaptain.displayName();
        } catch (RuntimeException malformedIdentity) {
            return java.util.Optional.empty();
        }
        if (!isValidLeaderName(displayName)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new LeaderIdentity(raidCaptain,
            sagaCaptain, displayName));
    }

    private record LeaderIdentity(RaidCaptain raidCaptain,
                                  Captain sagaCaptain,
                                  String displayName) {
    }

    /** The remembered captain with this id, or null if they are forgotten. */
    public static RaidCaptain captainOf(Settlement settlement, java.util.UUID id) {
        if (settlement == null || id == null) {
            return null;
        }
        for (RaidCaptain c : settlement.raidCaptains) {
            if (c != null && id.equals(c.id())) {
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
     * Pure gate for B03/B04 scheduling. ACTIVE/QUEUED runs deliberately fail
     * this gate: their established plan and sealed roster retain ownership.
     */
    public static boolean mayRunRecurringSchedule(boolean loadedValidSettlement,
                                                   boolean participatingPlayer,
                                                   RaidLifecycle lifecycle,
                                                   RecurringRaidRun recurring,
                                                   long gameTime) {
        return loadedValidSettlement && participatingPlayer && lifecycle != null
            && recurring != null
            && lifecycle.firstState() == FirstRaidState.COMPLETED
            && !lifecycle.recurringScheduleBlocked()
            && !lifecycle.recurringCoolingDown(gameTime)
            && recurring.isEmpty() && gameTime >= 0L;
    }

    /**
     * The one random draw for the next recurring raid: a day in the server
     * config's window ({@code raids.recurringRaidMinDays..MaxDays}, default
     * 3..4). Drawn once per resolution and persisted by RaidLifecycle, so a
     * reload never rerolls it.
     */
    static int pickCadenceIntervalDays(ServerLevel level) {
        return RaidCadence.pickIntervalDays(
            com.hearthstead.HearthsteadServerConfig.recurringRaidWindow(),
            level == null ? null : level.getRandom());
    }

    /**
     * Whether a live PendingRaid mirror has lost its owner to quarantine: a
     * blocked recurring run, or damaged first-raid state that must stop
     * rather than close. Nothing can resolve such a raid, so a kept mirror
     * would keep civilians hiding, repairs stopped and raider goals armed
     * forever. Active authored/legacy raids always keep their mirror.
     */
    static boolean quarantinedMirror(Settlement settlement) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RecurringRaidRun recurring = settlement.recurringRaidRun;
        if (lifecycle.isAuthoredFirstRaidActive() || lifecycle.isLegacyBridgeActive()) {
            return false;
        }
        if (lifecycle.firstState() != FirstRaidState.COMPLETED) {
            return lifecycle.integrityLost();
        }
        return recurring.isBlocked();
    }

    /** Drops the runtime mirror of a quarantined raid. The ledgers stay as they are. */
    static void clearQuarantinedMirror(ServerLevel level, Settlement settlement) {
        Hearthstead.LOGGER.warn(
            "Clearing the PendingRaid mirror of a quarantined raid at {} so settlers stop hiding",
            settlement.name);
        settlement.pendingRaid = null;
        settlement.raidLootEscaped = false;
        settlement.raidCaptainSlainId = null;
        RaidScars.get(level).resetArson(settlement.id);
        SettlementSavedData.get(level).setDirty();
    }

    private static boolean hasParticipatingPlayer(ServerLevel level,
                                                  Settlement settlement) {
        if (level == null || settlement == null || settlement.center == null) {
            return false;
        }
        double range = settlement.radius + (double) PLAYER_PRESENCE_MARGIN;
        if (settlement.radius <= 0 || range <= 0.0D) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (player != null && player.isAlive() && !player.isSpectator()
                && player.blockPosition().distSqr(settlement.center) <= range * range) {
                return true;
            }
        }
        return false;
    }

    /**
     * The committed recurring warning uses the already-localized exact-plan
     * copy. RaidPresentation.warning remains first-raid-only because it also
     * owns the FJ-600 receipt contract.
     */
    private static boolean presentRecurringWarning(ServerLevel level,
                                                   Settlement settlement,
                                                   RaidPlan plan) {
        String leader = leaderNameOf(settlement, plan.captainId()).orElse(null);
        if (leader == null) {
            return false;
        }
        RaidBroadcast.send(level, settlement, Component.translatable(
            RaidEscalation.isOutlawBand(settlement) ? "hearthstead.message.raid_omen_outlaws"
                : "hearthstead.message.raid_omen", settlement.name));
        RaidBroadcast.send(level, settlement, Component.translatable(
            "hearthstead.message.raid_warning_exact",
            plan.night(), Component.literal(leader), settlement.name,
            Component.translatable("hearthstead.raid.compass."
                + RaidPresentation.Compass.fromApproachDegrees(
                    plan.approachDegrees()).id()),
            Component.translatable(plan.objective().translationKey())));
        return true;
    }

    /**
     * Ends the "silent stall": when the first raid's due warning or attack is
     * held, tell nearby players which blocker holds it. Presentation only --
     * it runs AFTER the unchanged gates declined, never clears or rerolls the
     * plan, and is throttled by {@link RaidHoldNotice}. The expensive
     * readiness assessment is skipped whenever no notice could be sent.
     */
    public static boolean announceFirstRaidHold(ServerLevel level,
                                                Settlement settlement, long night,
                                                RaidHoldNotice.Reason reason) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        if (lifecycle.firstState() != FirstRaidState.SCHEDULED
            || lifecycle.integrityLost()) {
            return false;
        }
        RaidPlan plan = lifecycle.queuedPlan().orElse(null);
        boolean attackStage = reason == RaidHoldNotice.Reason.FIRST_ATTACK_READINESS;
        if (attackStage ? plan == null || night < lifecycle.firstAttackNight()
                : plan != null || night < lifecycle.firstWarningNight()) {
            return false; // not due yet: nothing is being held
        }
        if (!RaidHoldNotice.beginCheck(level, settlement, night)) {
            return false; // assessed recently; the answer cannot be news yet
        }
        FirstRaidReadinessService.Report report =
            FirstRaidReadinessService.assessExecution(level, settlement);
        String captainName = plan == null ? null
            : leaderNameOf(settlement, plan.captainId()).orElse(null);
        if (!report.ready()) {
            return RaidHoldNotice.readinessHeld(level, settlement, reason, night,
                report, lifecycle.firstAttackNight(), captainName);
        } else if (attackStage && captainName != null
            && firstWarningReceiptReady(settlement)) {
            // Every gate passed yet no band was accepted: the only remaining
            // cause is terrain (no loaded, standable ground outside the claim).
            return RaidHoldNotice.noFooting(level, settlement, night,
                plan.night(), captainName);
        }
        return false;
    }

    /** Starts the queued recurring plan; if it stays queued, says why. */
    private static void startQueuedRecurringRaidOrAnnounce(ServerLevel level,
                                                           Settlement settlement) {
        if (!startQueuedRecurringRaid(level, settlement).isEmpty()) {
            RaidHoldNotice.clear(level, settlement);
            return;
        }
        RecurringRaidRun run = settlement.recurringRaidRun;
        RaidPlan plan = run.plan().orElse(null);
        if (run.isQueued() && plan != null && settlement.pendingRaid == null
            && settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED
            && firstResolutionReceiptReady(settlement)
            && !settlement.raidLifecycle.mayGrantReward()) {
            // Blocked/quarantined runs are not "held"; only a plan that was
            // kept queued for a retry is. Footing is its only retry cause.
            RaidHoldNotice.noFooting(level, settlement,
                nightOf(level.getDayTime()), plan.night(),
                leaderNameOf(settlement, plan.captainId()).orElse(null));
        }
    }

    /**
     * Called from the hearth's once-a-second settlement tick. Safe to call
     * as often as you like: {@link RaidPressure#rollForNight} is idempotent
     * per night, so a re-entrant or duplicated tick cannot double-roll.
     */
    public static void tick(ServerLevel level, Settlement settlement) {
        settlement.raidCoinRewards.deliver(level, settlement);
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        if (settlement.pendingRaid != null && quarantinedMirror(settlement)) {
            clearQuarantinedMirror(level, settlement);
        }
        if (lifecycle.firstState() == FirstRaidState.PREPARING) {
            // Readiness is an explicit player commit through the Hearth. A
            // nightly tick may assess it for presentation, but never arms the
            // calendar on the player's behalf.
            return;
        }

        if (lifecycle.firstState() == FirstRaidState.SCHEDULED) {
            // The authored schedule owns this phase completely: no legacy
            // pressure roll or telegraph may race it and double-spawn.
            long dayTime = level.getDayTime();
            long night = nightOf(dayTime);
            if (isRollTime(dayTime)) {
                boolean queuedNow = queueFirstWarningIfDue(level, settlement, night);
                if (queuedNow) {
                    RaidHoldNotice.clear(level, settlement);
                } else if (lifecycle.queuedPlan().isEmpty()) {
                    announceFirstRaidHold(level, settlement, night,
                        RaidHoldNotice.Reason.FIRST_WARNING_READINESS);
                }
                if (!queuedNow && night >= lifecycle.firstAttackNight()
                    && lifecycle.queuedPlan().isPresent()) {
                    if (!firstWarningReceiptReady(settlement)
                        && !recoverFirstRaidWarningReceipt(level, settlement)) {
                        return;
                    }
                    if (!startQueuedFirstRaid(level, settlement, night).isEmpty()) {
                        RaidHoldNotice.clear(level, settlement);
                    } else {
                        announceFirstRaidHold(level, settlement, night,
                            RaidHoldNotice.Reason.FIRST_ATTACK_READINESS);
                    }
                }
            }
            return;
        }

        // SAGA v1: the named cast exists once there is any raid pressure to
        // speak of (CaptainRoster gates on worthRaiding itself). A scheduled
        // first raid creates it inside queueFirstWarningIfDue, after readiness
        // passes; other lifecycle phases still need it before planning below.
        CaptainRoster.ensureRoster(settlement, level.getRandom());

        if (lifecycle.isAuthoredFirstRaidActive()) {
            // Completion is driven solely by the persisted terminal ledger.
            // This bounded query is still useful for live arson behaviour,
            // but an empty result is never interpreted as victory.
            RaidPlan plan = lifecycle.activePlan().orElseThrow();
            if (settlement.pendingRaid == null) {
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            } else if (!settlement.pendingRaid.equals(plan)) {
                lifecycle.markIntegrityLost();
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            }
            if (lifecycle.allParticipantsTerminal()) {
                resolveIfOver(level, settlement);
                return;
            }
            if (lifecycle.recurringRetreatAtDayTime() < 0L) {
                // First raids that were live before their dawn retreat existed
                // arm it lazily from now; never retroactively.
                lifecycle.armRecurringRetreat(level.getDayTime());
                SettlementSavedData.get(level).setDirty();
            } else if (lifecycle.recurringRetreatDue(level.getDayTime())) {
                resolveFirstRaidRetreat(level, settlement);
                return;
            }
            java.util.List<RaiderEntity> loaded = livingRaidersOf(level, settlement);
            if (!loaded.isEmpty()) {
                tickArson(level, settlement, plan, loaded);
            }
            return;
        }

        if (lifecycle.isLegacyBridgeActive()) {
            // The only loaded-AABB first-raid runtime that still exists.
            // Its explicit, structurally validated provenance prevents a
            // damaged authored record from entering this branch.
            RaidPlan plan = lifecycle.activePlan().orElseThrow();
            if (settlement.pendingRaid == null
                || !settlement.pendingRaid.equals(plan)) {
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            }
            java.util.List<RaiderEntity> living = livingRaidersOf(level, settlement);
            if (living.isEmpty()) {
                resolveIfOver(level, settlement);
                return;
            }
            tickArson(level, settlement, plan, living);
            return;
        }

        if (lifecycle.integrityLost()
            && lifecycle.firstState() != FirstRaidState.COMPLETED) {
            // This guard intentionally precedes the generic pending mirror:
            // corrupt ACTIVE state may retain that mirror even when its
            // lifecycle plan/dates were lost. It must stop, not AABB-close.
            return;
        }

        if (lifecycle.firstState() != FirstRaidState.COMPLETED) {
            // Recurring pressure cannot bypass or race an uninitialized/
            // damaged first lifecycle.
            return;
        }

        // A crash may persist the terminal lifecycle/report immediately
        // before FJ-610 or its one-shot reward. Reconcile those exact facts
        // before any recurring authority is allowed to roll or spawn.
        if (!reconcileCompletedFirstRaid(level, settlement)) {
            return;
        }

        RecurringRaidRun recurring = settlement.recurringRaidRun;
        if (recurring.isBlocked()) {
            return;
        }
        if (recurring.isQueued()) {
            if (settlement.pendingRaid != null) {
                // QUEUED is deliberately pre-activation; a PendingRaid here
                // would reintroduce the old AABB completion authority.
                recurring.block();
                SettlementSavedData.get(level).setDirty();
                return;
            }
            // A queued serial may be retried after a physical placement
            // failure, but an arrival still needs the same nearby online
            // player required to create the warning. Returning preserves the
            // exact plan/serial and does not reroll, clear, or rewrite it.
            if (!hasParticipatingPlayer(level, settlement)) {
                return;
            }
            startQueuedRecurringRaidOrAnnounce(level, settlement);
            return;
        }
        if (recurring.isActive()) {
            RaidPlan plan = recurring.plan().orElseThrow();
            if (settlement.pendingRaid == null) {
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            } else if (!settlement.pendingRaid.equals(plan)) {
                recurring.markIntegrityLost();
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            }
            if (recurring.allParticipantsTerminal()) {
                resolveIfOver(level, settlement);
                return;
            }
            if (lifecycle.recurringRetreatAtDayTime() < 0L) {
                // Raids that were already live before the dawn retreat existed
                // arm it lazily from now; never retroactively.
                lifecycle.armRecurringRetreat(level.getDayTime());
                SettlementSavedData.get(level).setDirty();
            } else if (lifecycle.recurringRetreatDue(level.getDayTime())) {
                resolveRecurringRetreat(level, settlement);
                return;
            }
            // The query is live-behaviour input only. An empty result may mean
            // every participant's chunk is unloaded and is never completion.
            java.util.List<RaiderEntity> loaded = livingRaidersOf(level, settlement);
            if (!loaded.isEmpty()) {
                tickArson(level, settlement, plan, loaded);
            }
            return;
        }
        if (recurring.isLegacyBridgeActive()) {
            RaidPlan plan = recurring.plan().orElseThrow();
            if (settlement.pendingRaid == null) {
                settlement.pendingRaid = plan;
                SettlementSavedData.get(level).setDirty();
            } else if (!settlement.pendingRaid.equals(plan)) {
                recurring.block();
                SettlementSavedData.get(level).setDirty();
                return;
            }
            java.util.List<RaiderEntity> living = livingRaidersOf(level, settlement);
            if (living.isEmpty()) {
                resolveIfOver(level, settlement);
                return;
            }
            tickArson(level, settlement, plan, living);
            return;
        }
        if (!recurring.isEmpty() || settlement.pendingRaid != null) {
            // There is no valid v3 authority for this compatibility mirror.
            // Quarantine permanently instead of reviving the pre-v3 AABB path.
            recurring.block();
            SettlementSavedData.get(level).setDirty();
            return;
        }
        // B03/B04 schedules only while a real nearby player is present.
        // Missing nights are intentionally never replayed on return.
        long gameTime = level.getGameTime();
        if (!mayRunRecurringSchedule(true, hasParticipatingPlayer(level, settlement),
                lifecycle, recurring, gameTime)) {
            return;
        }
        long dayTime = level.getDayTime();
        long night = nightOf(dayTime);

        // A committed warning is not rerolled. Once the later eligible night
        // has supplied the full minimum lead, hand its exact plan to the
        // existing run/serial owner and let the unchanged start path seal it.
        RaidPlan warnedPlan = lifecycle.recurringWarnedPlan().orElse(null);
        if (warnedPlan != null) {
            if (!isRollTime(dayTime)
                || !lifecycle.recurringWarningDue(gameTime, night)) {
                return;
            }
            if (!recurring.queue(warnedPlan)) {
                lifecycle.blockRecurringSchedule();
                SettlementSavedData.get(level).setDirty();
                return;
            }
            if (!lifecycle.consumeRecurringWarning(warnedPlan)) {
                recurring.block();
                lifecycle.blockRecurringSchedule();
                SettlementSavedData.get(level).setDirty();
                return;
            }
            SettlementSavedData.get(level).setDirty();
            startQueuedRecurringRaidOrAnnounce(level, settlement);
            return;
        }

        // General risk remains separate from a committed incoming attack.
        RaidTelegraph.tick(level, settlement);
        if (!isRollTime(dayTime)) {
            return; // not yet tonight
        }
        // A hamlet below the existing RaidPressure worth floor has no eligible
        // recurring producer roll: it must not accumulate quiet-roll debt toward
        // a forced band. Warned/queued/active owners were handled above.
        if (!RaidPressure.worthRaiding(settlement)) {
            return;
        }
        RaidPressure pressure = settlement.raidPressure;
        boolean cadenceDue = lifecycle.recurringCadenceDue(night);
        if (night > pressure.lastRolledNight()) {
            // Since the 25 Sep cadence decision the nightly draw is pressure
            // bookkeeping only (stage, band weight, omens). Whether a raid
            // comes is decided by the regular cadence below, never by chance.
            boolean legacyRollHit = pressure.rollForNight(settlement, night,
                level.getRandom().nextDouble());
            SettlementSavedData.get(level).setDirty();
            if (!cadenceDue && !legacyRollHit) {
                // A quiet recovery night may still publish an independent
                // risk omen; it is never labeled as the committed plan.
                RaidTelegraph.rollForecast(settlement, night,
                    level.getRandom().nextDouble());
            }
        }
        if (!cadenceDue) {
            return; // recovery nights between cadence raids
        }

        // Warning at this eligible roll; the same production plan reaches the
        // existing RecurringRaidRun on a later night, never as a new band.
        if (night == Long.MAX_VALUE) {
            lifecycle.blockRecurringSchedule();
            SettlementSavedData.get(level).setDirty();
            return;
        }
        RaidPlan plan = planRaid(level, settlement, night + 1L);
        if (!lifecycle.commitRecurringWarning(plan, gameTime, night)) {
            lifecycle.blockRecurringSchedule();
            SettlementSavedData.get(level).setDirty();
            return;
        }
        if (!presentRecurringWarning(level, settlement, plan)) {
            lifecycle.blockRecurringSchedule();
            SettlementSavedData.get(level).setDirty();
            return;
        }
        SettlementSavedData.get(level).setDirty();
        Hearthstead.LOGGER.info(
            "Recurring raid warning committed for {} on night {}: {}",
            settlement.name, plan.night(), plan.objective().id());
        // Tech tree (War Feast): feast the guards on the warning, if stocked.
        com.hearthstead.settlement.techtree.effects.CommonsEffects.onRaidWarning(level, settlement);
    }

    // ------------------------------------------------ the scar ledger ---
    //
    // SLICE REPAIR-1. The other half of "repair dugnad + defense report"
    // (DESIGN.md system 5): every block a raid destroys is recorded HERE,
    // position plus the exact original BlockState, before the block
    // changes. Settlers never construct buildings autonomously (permanent
    // invariant) -- a scar is what makes their repair work honest, because
    // restoring a recorded state is provably repair and can never be
    // construction of something that was not there.

    /**
     * Records one block a raid is about to destroy. The single authorized
     * channel: anything that breaks or burns settlement blocks on a raid's
     * behalf -- today the director's own arson ({@link #tickArson}),
     * tomorrow any raider-entity breach goal -- must call this BEFORE the
     * block changes, so the original state is captured rather than the
     * wreckage.
     *
     * <p>Bounded ({@link #MAX_SCARS_PER_RAID}, oldest dropped) and
     * idempotent per position: the FIRST recording at a position wins,
     * because only the first saw the true original -- a second hit on the
     * same spot is destroying wreckage, not architecture.
     */
    public static void recordScar(ServerLevel level, java.util.UUID settlementId,
                                  BlockPos pos, BlockState original) {
        if (original.isAir()) {
            return; // air is not a wound
        }
        RaidScars book = RaidScars.get(level);
        java.util.List<Scar> list = book.of(settlementId);
        for (Scar scar : list) {
            if (scar.pos().equals(pos)) {
                return; // first recording holds the true original
            }
        }
        list.add(new Scar(pos.immutable(), original));
        while (list.size() > MAX_SCARS_PER_RAID) {
            list.remove(0); // the oldest wound fades first, like the log
        }
        book.setDirty();
    }

    /** A read-only snapshot of a settlement's open scars, oldest first. */
    public static java.util.List<Scar> scarsOf(ServerLevel level,
                                               java.util.UUID settlementId) {
        return RaidScars.get(level).scarsOf(settlementId);
    }

    /** Whether a scar is still open at this exact position. */
    public static boolean hasScarAt(ServerLevel level, java.util.UUID settlementId,
                                    BlockPos pos) {
        for (Scar scar : RaidScars.get(level).of(settlementId)) {
            if (scar.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes one scar -- called by the repair goal the moment the original
     * block stands again (or the scar is found obsolete: already healed, or
     * built over by the player, whose work a repair must never overwrite).
     *
     * @return whether a scar was actually open there
     */
    public static boolean clearScar(ServerLevel level, java.util.UUID settlementId,
                                    BlockPos pos) {
        RaidScars book = RaidScars.get(level);
        boolean removed = book.of(settlementId).removeIf(s -> s.pos().equals(pos));
        if (removed) {
            book.setDirty();
        }
        return removed;
    }

    /**
     * The director's own arson: one torched block per settlement tick while
     * a BRANN band is standing at a building, hard-capped per raid
     * ({@link #ARSON_PER_RAID}). The scar is recorded FIRST, then the block
     * becomes fire (vanilla burns it out or spreads it from there), so the
     * dugnad always knows what stood in the hole.
     *
     * <p>Bounded like every scan in this mod: raiders are the (already
     * fetched, band-capped) living list, buildings are the settlement's own
     * bounded list, and the site search samples at most
     * {@link #ARSON_SITE_TRIES} positions. Blocks with block entities are
     * never torched -- burning a chest would void its items (INV: items are
     * conserved), and burning the plaque would erase the building's
     * identity rather than wound its body.
     */
    private static void tickArson(ServerLevel level, Settlement settlement,
                                  RaidPlan plan, java.util.List<RaiderEntity> living) {
        if (plan.objective() != RaidObjective.BRANN) {
            return; // only arson raids burn; a granary raid steals instead
        }
        RaidScars book = RaidScars.get(level);
        if (book.arsonThisRaid(settlement.id) >= ARSON_PER_RAID) {
            return;
        }
        RandomSource random = level.getRandom();
        for (RaiderEntity raider : living) {
            for (Building building : settlement.buildings) {
                if (!building.valid || building.bounds == null) {
                    continue;
                }
                if (!building.bounds.inflatedBy(ARSON_REACH)
                        .isInside(raider.blockPosition())) {
                    continue; // torches are lit at the wall, not from afar
                }
                BlockPos site = arsonSite(level, building, random);
                if (site == null) {
                    continue;
                }
                String burned = level.getBlockState(site).getBlock().getName().getString();
                torchForArson(level, settlement.id, site);
                Hearthstead.LOGGER.info(
                    "Raiders torch {} at {} in {} ({} of {} torchings this raid)",
                    burned, site, settlement.name, book.arsonThisRaid(settlement.id),
                    ARSON_PER_RAID);
                return; // one torching per settlement tick: watchable, not a flash
            }
        }
    }

    /**
     * Torches one block on a raid's behalf: records its scar FIRST (so the
     * repair dugnad knows what stood in the hole), turns it to fire, then
     * counts it against {@link #ARSON_PER_RAID}. The single mechanism that
     * actually burns anything -- {@link #tickArson} calls this once it has
     * picked a site, and nothing else may set a settlement block alight on a
     * raid's behalf.
     *
     * <p>Public so a GameTest proving what a BRANN raid's own success signal
     * ({@link #objectiveSucceeded}) does can drive the EXACT mechanism a real
     * raid drives -- {@code SagaGameTests#aVictoriousRaidGrowsTheLeaderAndEarnsAnEpithet}
     * calls this directly rather than reaching past it into
     * {@link RaidScars}'s package-private counter, so the state that test
     * builds is one a real BRANN raid can actually produce, not a shortcut
     * around the mechanism that produces it.
     */
    public static void torchForArson(ServerLevel level, java.util.UUID settlementId,
                                     BlockPos site) {
        BlockState original = level.getBlockState(site);
        recordScar(level, settlementId, site, original);
        level.setBlock(site, BaseFireBlock.getState(level, site), 3);
        RaidScars.get(level).countArson(settlementId);
    }

    /**
     * A block of this building worth burning, or null. Random samples
     * inside the (room-scan-capped) bounds; a candidate must be a real,
     * item-yielding block with no block entity -- see {@link #tickArson}
     * for why chests, beds and the plaque are categorically off the menu.
     */
    private static BlockPos arsonSite(ServerLevel level, Building building,
                                      RandomSource random) {
        var b = building.bounds;
        for (int attempt = 0; attempt < ARSON_SITE_TRIES; attempt++) {
            BlockPos pos = new BlockPos(
                b.minX() + random.nextInt(b.getXSpan()),
                b.minY() + random.nextInt(b.getYSpan()),
                b.minZ() + random.nextInt(b.getZSpan()));
            BlockState state = level.getBlockState(pos);
            if (state.isAir()
                || state.getBlock().asItem() == net.minecraft.world.item.Items.AIR
                || level.getBlockEntity(pos) != null) {
                continue;
            }
            return pos;
        }
        return null;
    }

    /**
     * One block a raid destroyed: where, and exactly what stood there.
     * Plain data with the same writeNbt/readNbt shape as
     * {@link RaidLogEntry} -- the repair goal restores {@link #original}
     * verbatim, properties and all, which is what makes repair repair.
     */
    public record Scar(BlockPos pos, BlockState original) {

        public CompoundTag writeNbt() {
            CompoundTag tag = new CompoundTag();
            tag.put("Pos", NbtUtils.writeBlockPos(pos));
            tag.put("Original", NbtUtils.writeBlockState(original));
            return tag;
        }

        public static Scar readNbt(HolderGetter<Block> blocks, CompoundTag tag) {
            return new Scar(NbtUtils.readBlockPos(tag, "Pos").orElse(BlockPos.ZERO),
                NbtUtils.readBlockState(blocks, tag.getCompound("Original")));
        }
    }

    /**
     * The scar ledger itself: per-settlement open scars plus the current
     * raid's arson budget, persisted with the world exactly the way
     * {@link SettlementSavedData} is (same Factory/get/load/save shape,
     * its own {@code .dat}). Lives here rather than on {@link Settlement}
     * so raid damage bookkeeping stays raid-owned -- the settlement record
     * never becomes a second place raid state is written.
     *
     * <p>Both halves must persist: a pending raid survives a save/reload
     * ({@link RaidPlan}'s own doc), so the arson budget must survive with
     * it or a reload mid-raid would hand the band a fresh torch allowance.
     */
    public static final class RaidScars extends SavedData {
        private static final String DATA_NAME = "hearthstead_raid_scars";

        private static final Factory<RaidScars> FACTORY =
            new Factory<>(RaidScars::new, RaidScars::load, null);

        private final java.util.Map<java.util.UUID, java.util.List<Scar>> scars =
            new java.util.HashMap<>();
        private final java.util.Map<java.util.UUID, Integer> arson =
            new java.util.HashMap<>();

        public static RaidScars get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        }

        public RaidScars() {
        }

        /** The live, mutable list -- internal; callers go through the statics. */
        private java.util.List<Scar> of(java.util.UUID settlementId) {
            return scars.computeIfAbsent(settlementId,
                id -> new java.util.ArrayList<>());
        }

        /** Read-only snapshot, oldest first. Public for the GameTests. */
        public java.util.List<Scar> scarsOf(java.util.UUID settlementId) {
            return java.util.List.copyOf(of(settlementId));
        }

        int arsonThisRaid(java.util.UUID settlementId) {
            return arson.getOrDefault(settlementId, 0);
        }

        void countArson(java.util.UUID settlementId) {
            arson.merge(settlementId, 1, Integer::sum);
            setDirty();
        }

        void resetArson(java.util.UUID settlementId) {
            if (arson.remove(settlementId) != null) {
                setDirty();
            }
        }

        public static RaidScars load(CompoundTag tag,
                                     HolderLookup.Provider registries) {
            RaidScars data = new RaidScars();
            HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
            ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag st = list.getCompound(i);
                java.util.UUID id = st.getUUID("Id");
                if (st.contains("ArsonThisRaid")) {
                    data.arson.put(id, st.getInt("ArsonThisRaid"));
                }
                ListTag scarList = st.getList("Scars", Tag.TAG_COMPOUND);
                java.util.List<Scar> out = data.of(id);
                for (int j = 0; j < scarList.size(); j++) {
                    Scar scar = Scar.readNbt(blocks, scarList.getCompound(j));
                    // A removed mod's block reads back as air; an air scar
                    // is unrepairable and unrecordable, so it is dropped on
                    // load rather than left to jam the queue forever.
                    if (!scar.original().isAir()) {
                        out.add(scar);
                    }
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (var entry : scars.entrySet()) {
                java.util.List<Scar> settlementScars = entry.getValue();
                int arsonCount = arson.getOrDefault(entry.getKey(), 0);
                if (settlementScars.isEmpty() && arsonCount == 0) {
                    continue; // fully healed settlements leave no residue
                }
                CompoundTag st = new CompoundTag();
                st.putUUID("Id", entry.getKey());
                if (arsonCount > 0) {
                    st.putInt("ArsonThisRaid", arsonCount);
                }
                ListTag scarList = new ListTag();
                for (Scar scar : settlementScars) {
                    scarList.add(scar.writeNbt());
                }
                st.put("Scars", scarList);
                list.add(st);
            }
            tag.put("Settlements", list);
            return tag;
        }
    }
}

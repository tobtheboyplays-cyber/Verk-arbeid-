package com.hearthstead.settlement.raid;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.saga.Captain;
import com.hearthstead.saga.CaptainRoster;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
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
            raider.setVariant(variantFor(i, random));
            raider.assign(captain.id(), settlement.id, plan.objective(),
                captain.menace(), isCaptain);
            raider.setObjectivePos(settlement.center);
            if (isCaptain) {
                // SAGA v1: a "wild" captain outside the tracked three (see
                // CaptainRoster.MAX_ROSTER) leads exactly as before -- no
                // name, no extra bonus -- so this is purely additive.
                Captain saga = CaptainRoster.find(settlement, captain.id());
                if (saga != null) {
                    raider.markSagaCaptain(saga.displayName(),
                        captain.victories(), saga.hasEpithet());
                }
            }
            level.addFreshEntity(raider);
            spawned.add(raider);
        }
        return spawned;
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
        if (index == 0) {
            return random.nextFloat() < BRUTE_CAPTAIN_CHANCE
                ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER;
        }
        return index % BRUTE_SPACING == 0
            ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER;
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
        recordAftermath(level, settlement, plan, captain, !lost, captainSlain, arsonCount);
        SettlementSavedData.get(level).setDirty();
        Hearthstead.LOGGER.info(
            "Raid on {} is over -- {} {} (pressure now {}, stage {})",
            settlement.name, captain == null ? "the band" : captain.name(),
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
     * resolves the name this raid is remembered under (an earned Saga
     * display name where one exists, the bare {@code RaidCaptain} name
     * otherwise), succeeds a slain captain with a lieutenant, and grants an
     * earned/upgraded epithet for a raid that got away with the goods --
     * all of it before the log entry and report below are built, so both
     * read the outcome honestly.
     *
     * @param arsonCount this raid's own torching tally, captured by the
     *                   caller before {@link RaidScars#resetArson} clears
     *                   it -- BRANN's report reads it directly rather than
     *                   re-deriving "held" from the generic KORN wording.
     */
    private static void recordAftermath(ServerLevel level, Settlement settlement,
                                        RaidPlan plan, RaidCaptain captain, boolean held,
                                        boolean captainSlain, int arsonCount) {
        String captainName = CaptainRoster.recordRaidOutcome(level, settlement, captain,
            plan.objective(), held, captainSlain, level.getRandom());
        String stageAfter = settlement.raidPressure.stage().id();
        RaidLogEntry entry = new RaidLogEntry(plan.night(), captainName,
            plan.objective().id(), held, settlement.raidItemsStolenTonight,
            settlement.raidSettlersHurtTonight, stageAfter);
        settlement.raidLog.add(entry);
        while (settlement.raidLog.size() > MAX_RAID_LOG) {
            settlement.raidLog.remove(0); // oldest history fades first
        }

        Component stage = Component.translatable("hearthstead.raid.stage." + stageAfter);
        Component report = reportFor(plan.objective(), held, settlement, captainName,
            arsonCount, stage);
        RaidBroadcast.send(level, settlement, report);

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
        // SAGA v1: the named cast exists once there is any raid pressure to
        // speak of (CaptainRoster gates on worthRaiding itself). Cheap and
        // idempotent -- see CaptainRoster#ensureRoster -- and must run
        // before planRaid below ever picks a captain to lead tonight.
        CaptainRoster.ensureRoster(settlement, level.getRandom());
        if (settlement.pendingRaid != null) {
            // A raid is on. Resolve it before considering another night --
            // "deliveries that silently never happen" applied to raids would
            // be a raid that is scheduled forever and never concludes.
            RaidPlan plan = settlement.pendingRaid;
            java.util.List<RaiderEntity> living = livingRaidersOf(level, settlement);
            if (living.isEmpty()) {
                // resolveIfOver re-queries once on this closing tick; the
                // cost of one duplicate bounded box query, paid once per
                // raid, buys keeping its public contract untouched.
                resolveIfOver(level, settlement);
                return;
            }
            // SLICE REPAIR-1: while the band is actually here, a BRANN raid
            // burns -- the director's own arson, recorded scar-first so the
            // repair dugnad knows exactly what stood there.
            tickArson(level, settlement, plan, living);
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
            java.util.List<RaiderEntity> spawned = spawnBand(level, settlement, plan);
            RaidCaptain captain = captainOf(settlement, plan.captainId());
            // SAGA v1 (the task's "raids are led"): the same night the band
            // actually arrives, name who is leading it -- readable up front,
            // not only in the morning report. Skipped if nothing actually
            // spawned (bad footing on every bearing): a raid nobody can see
            // must not be announced as led by anyone.
            if (!spawned.isEmpty() && captain != null) {
                Captain saga = CaptainRoster.find(settlement, captain.id());
                String leaderName = saga != null ? saga.displayName() : captain.name();
                RaidBroadcast.send(level, settlement, Component.translatable(
                    "hearthstead.message.raid_captain_leads", leaderName, settlement.name));
            }
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

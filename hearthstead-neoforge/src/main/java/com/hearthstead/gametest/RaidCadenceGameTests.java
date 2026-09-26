package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCadence;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidLifecycle.CadenceOutcome;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Regular recurring-raid cadence, MineColonies-style escalation and the dawn
 * retreat soft-lock guard (25 Sep owner decision). Every world-clock test has
 * its own batch and only ever moves day-time FORWARD, snapped to whole days
 * via floorDiv(dayTime, 24000) * 24000 + target.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidCadenceGameTests {

    private static final long DAY = RaidLifecycle.DAY_LENGTH;
    private static final long DUSK = RaidLifecycle.DUSK_DAYTIME;

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static RaidLifecycle completedFirstLifecycle(GameTestHelper helper) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        UUID participant = UUID.randomUUID();
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
                && lifecycle.queueFirstPlan(first)
                && lifecycle.beginFirstRaid(first)
                && lifecycle.recordParticipant(participant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(participant)
                && lifecycle.completeFirstRaid(false),
            "the cadence fixture requires one cleanly completed first raid");
        helper.assertTrue(lifecycle.firstState() == FirstRaidState.COMPLETED,
            "fixture lifecycle must be COMPLETED");
        return lifecycle;
    }

    /** A completed, worth-raiding settlement registered in the level. */
    private static Settlement registeredSettlement(GameTestHelper helper, String name) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        settlement.raidLifecycle = completedFirstLifecycle(helper);
        for (int i = 0; i < 5; i++) {
            settlement.putRecord(UUID.randomUUID(), name + " " + i, Profession.NONE);
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static void forget(GameTestHelper helper, Settlement settlement) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(settlement.id);
        data.setDirty();
    }

    /** Forward-only: never moves the shared world clock backwards. */
    private static void setDayTimeForward(GameTestHelper helper, long target) {
        long now = helper.getLevel().getDayTime();
        helper.assertTrue(target >= now,
            "fixture must never move day-time backwards: " + target + " < " + now);
        helper.getLevel().setDayTime(target);
    }

    private static void setGameTimeForward(ServerLevelData clock, GameTestHelper helper,
                                           long target) {
        if (target > helper.getLevel().getGameTime()) {
            clock.setGameTime(target);
        }
    }

    /**
     * Drives the real director through recovery nights, the warning dusk and
     * the attack dusk after one resolution with {@code outcome} whose day
     * pick was {@code expectedGap} (the persisted draw from the 3..4 window).
     */
    private static void runCadenceScenario(GameTestHelper helper, CadenceOutcome outcome,
                                           int expectedGap) {
        buildArena(helper, 16);
        Settlement settlement = registeredSettlement(helper,
            outcome == CadenceOutcome.LOST ? "Tapvik" : "Seirvik");
        ServerLevelData clock = (ServerLevelData) helper.getLevel().getLevelData();
        ServerPlayer player = null;
        try {
            long raidNight = Math.floorDiv(helper.getLevel().getDayTime(), DAY) + 1L;
            long resolvedDayTime = raidNight * DAY + 18_000L;
            long g0 = helper.getLevel().getGameTime();
            RaidLifecycle lifecycle = settlement.raidLifecycle;
            lifecycle.recordRecurringCadence(g0, resolvedDayTime, outcome, expectedGap);
            helper.assertTrue(RaidCadence.attackWithinWindow(raidNight,
                    lifecycle.recurringNextAttackNight(), RaidCadence.DEFAULT_WINDOW)
                    && lifecycle.recurringIntervalDays() == expectedGap
                    && lifecycle.recurringLastRaidNight() == raidNight,
                "the attack must land on day 3 or 4 after the raid, got "
                    + lifecycle.recurringNextAttackNight() + " for R=" + raidNight);
            helper.assertTrue(lifecycle.recurringNextAttackNight() == raidNight + expectedGap
                    && lifecycle.recurringNextWarningNight() == raidNight + expectedGap - 1L,
                "cadence must schedule attack night R+" + expectedGap + " and warning one dusk earlier, got "
                    + lifecycle.recurringNextWarningNight() + "/" + lifecycle.recurringNextAttackNight()
                    + " for R=" + raidNight);
            // The same arithmetic also holds for a raid resolved after midnight (before next dusk).
            helper.assertTrue(RaidLifecycle.raidNightOf((raidNight + 1L) * DAY + 2_000L) == raidNight
                    && RaidLifecycle.raidNightOf(raidNight * DAY + DUSK) == raidNight
                    && RaidLifecycle.raidNightOf(raidNight * DAY + DUSK - 1L) == raidNight - 1L,
                "raid night must be the night whose dusk most recently passed");

            player = helper.makeMockServerPlayerInLevel();
            player.setPos(settlement.center.getX() + 0.5D, settlement.center.getY(),
                settlement.center.getZ() + 1.5D);
            long gameTime = g0 + RaidLifecycle.RECURRING_CADENCE_MIN_RECOVERY_TICKS + 1L;
            setGameTimeForward(clock, helper, gameTime);

            // Every recovery dusk before the warning night stays quiet.
            for (long night = raidNight; night < raidNight + expectedGap - 1L; night++) {
                setDayTimeForward(helper, night * DAY + DUSK);
                RaidDirector.tick(helper.getLevel(), settlement);
                helper.assertTrue(settlement.raidLifecycle.recurringWarnedPlan().isEmpty()
                        && settlement.recurringRaidRun.isEmpty(),
                    "no warning may be committed on recovery night " + night);
            }

            long warningNight = raidNight + expectedGap - 1L;
            setDayTimeForward(helper, warningNight * DAY + DUSK);
            RaidDirector.tick(helper.getLevel(), settlement);
            RaidPlan warned = settlement.raidLifecycle.recurringWarnedPlan().orElse(null);
            helper.assertTrue(warned != null
                    && warned.night() == warningNight + 1L
                    && settlement.raidLifecycle.recurringWarningNight() == warningNight,
                "the cadence must commit exactly one warning at dusk of night " + warningNight
                    + " for attack night " + (warningNight + 1L) + ", got " + warned);

            // The next dusk (full day/night of preparation later) the exact plan attacks.
            setGameTimeForward(clock, helper, helper.getLevel().getGameTime()
                + RaidLifecycle.RECURRING_WARNING_MIN_LEAD_TICKS + 1L);
            setDayTimeForward(helper, (warningNight + 1L) * DAY + DUSK);
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.recurringRaidRun.plan().map(warned::equals).orElse(false)
                    && (settlement.recurringRaidRun.isQueued()
                        || settlement.recurringRaidRun.isActive()),
                "the warned plan must hand off to the recurring run on its attack night");
        } finally {
            if (player != null) {
                player.discard();
            }
            for (RaiderEntity raider : RaidDirector.livingRaidersOf(helper.getLevel(), settlement)) {
                raider.discard();
            }
            forget(helper, settlement);
        }
        helper.succeed();
    }

    /** The earliest day of the window: quiet for two dusks, warns on the third's eve. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_cadence_after_win")
    public void cadenceAfterWinOnDayThreeWarnsTheEveningBefore(GameTestHelper helper) {
        runCadenceScenario(helper, CadenceOutcome.HELD, RaidCadence.DEFAULT_MIN_DAYS);
    }

    /** The latest day of the window, after a loss: the outcome no longer moves the day. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_cadence_after_loss")
    public void cadenceAfterLossOnDayFourWarnsTheEveningBefore(GameTestHelper helper) {
        runCadenceScenario(helper, CadenceOutcome.LOST, RaidCadence.DEFAULT_MAX_DAYS);
    }

    @GameTest(template = "empty5", timeoutTicks = 100, batch = "raid_cadence_escalation")
    public void escalationIsBoundedAndBreatherLowersOneLevel(GameTestHelper helper) {
        // Pure bonus table: +1 raider per 2 survived raids, capped at +4.
        helper.assertTrue(RaidDirector.escalationBonusFor(0) == 0
                && RaidDirector.escalationBonusFor(1) == 0
                && RaidDirector.escalationBonusFor(2) == 1
                && RaidDirector.escalationBonusFor(5) == 2
                && RaidDirector.escalationBonusFor(8) == RaidDirector.MAX_ESCALATION_BONUS
                && RaidDirector.escalationBonusFor(1_000) == RaidDirector.MAX_ESCALATION_BONUS
                && RaidDirector.escalationBonusFor(-3) == 0,
            "escalation bonus table must match the documented formula");

        RandomSource random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        for (int population : new int[] {0, 5, 12, 40}) {
            Settlement settlement = new Settlement(UUID.randomUUID(), "Esc" + population,
                BlockPos.ZERO);
            for (int i = 0; i < population; i++) {
                settlement.putRecord(UUID.randomUUID(), "E" + i, Profession.NONE);
            }
            int base = RaidDirector.bandSizeFor(settlement, captain);
            helper.assertTrue(base == RaidDirector.bandSizeFor(settlement, captain, 0),
                "level 0 must equal the legacy band size");
            int previous = base;
            for (int level = 0; level <= 50; level++) {
                int band = RaidDirector.bandSizeFor(settlement, captain, level);
                helper.assertTrue(band >= RaidDirector.MIN_BAND && band <= RaidDirector.MAX_BAND
                        && band >= previous
                        && band - base <= RaidDirector.MAX_ESCALATION_BONUS,
                    "band must stay bounded and monotonic: pop=" + population
                        + " level=" + level + " band=" + band + " base=" + base);
                previous = band;
            }
        }

        // Lifecycle raid level: held raids climb, a loss gives one breather level.
        RaidLifecycle lifecycle = completedFirstLifecycle(helper);
        long dayTime = 100L * DAY + 18_000L;
        lifecycle.recordRecurringCadence(1_000L, dayTime, CadenceOutcome.HELD, 3);
        lifecycle.recordRecurringCadence(2_000L, dayTime + 3L * DAY, CadenceOutcome.HELD, 4);
        lifecycle.recordRecurringCadence(3_000L, dayTime + 7L * DAY, CadenceOutcome.HELD, 3);
        helper.assertTrue(lifecycle.raidsSurvived() == 3 && lifecycle.raidLevel() == 3
                && !lifecycle.raidBreather(),
            "three held raids must reach raid level 3");
        lifecycle.recordRecurringCadence(4_000L, dayTime + 10L * DAY, CadenceOutcome.LOST, 4);
        helper.assertTrue(lifecycle.raidsSurvived() == 3 && lifecycle.raidLevel() == 2
                && lifecycle.raidBreather(),
            "a loss lowers the next raid's level by exactly one");
        lifecycle.recordRecurringCadence(5_000L, dayTime + 14L * DAY, CadenceOutcome.RETREATED, 3);
        helper.assertTrue(lifecycle.raidsSurvived() == 3 && lifecycle.raidLevel() == 3
                && !lifecycle.raidBreather(),
            "a dawn retreat is neutral: no survived raid, breather consumed");

        // Composition: veteran from overall raid 3, never during a breather.
        helper.assertTrue(!RaidDirector.veteranBand(2L, false)
                && RaidDirector.veteranBand(3L, false)
                && !RaidDirector.veteranBand(5L, true),
            "veteran composition must start at raid 3 and pause for a breather");
        helper.assertTrue(RaidDirector.variantFor(1, random, true) == RaiderEntity.Variant.SKIRMISHER
                && RaidDirector.variantFor(3, random, true) == RaiderEntity.Variant.BRUTE
                && RaidDirector.variantFor(3, random, false) == RaiderEntity.Variant.SKIRMISHER
                && RaidDirector.variantFor(5, random, false) == RaiderEntity.Variant.BRUTE,
            "veteran bands carry a BRUTE every 3rd slot, base bands every 5th");

        // Save compatibility: fields round-trip, and an old tag without them loads neutral.
        RaidLifecycle reloaded = RaidLifecycle.readNbt(lifecycle.writeNbt());
        helper.assertTrue(reloaded.raidsSurvived() == 3 && !reloaded.raidBreather()
                && reloaded.recurringNextWarningNight() == lifecycle.recurringNextWarningNight()
                && reloaded.recurringIntervalDays() == lifecycle.recurringIntervalDays()
                && reloaded.recurringLastRaidNight() == lifecycle.recurringLastRaidNight()
                && !reloaded.recurringScheduleBlocked() && !reloaded.integrityLost(),
            "cadence/escalation fields must round-trip without blocking");
        CompoundTag legacy = lifecycle.writeNbt();
        legacy.remove("RecurringCadenceNextWarningNight");
        legacy.remove("RecurringCadenceSchema");
        legacy.remove("RecurringCadenceLastRaidNight");
        legacy.remove("RecurringCadenceIntervalDays");
        legacy.remove("RaidsSurvived");
        legacy.remove("RaidBreather");
        legacy.remove("RecurringRetreatAtDayTime");
        legacy.remove("RetreatedRaiders");
        RaidLifecycle old = RaidLifecycle.readNbt(legacy);
        helper.assertTrue(old.raidsSurvived() == 0 && !old.raidBreather()
                && old.recurringNextWarningNight() == RaidLifecycle.UNSET_NIGHT
                && old.recurringCadenceDue(0L)
                && !old.recurringScheduleBlocked() && !old.integrityLost()
                && old.firstState() == FirstRaidState.COMPLETED,
            "a pre-cadence save must load with neutral defaults and never be marked corrupt");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_cadence_dawn_retreat")
    public void dawnRetreatResolvesActiveRecurringRaidWithRaidersLeft(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement settlement = registeredSettlement(helper, "Morgenvik");
        try {
            RaidCaptain captain = RaidDirector.pickCaptain(settlement,
                helper.getLevel().getRandom());
            long startNight = Math.floorDiv(helper.getLevel().getDayTime(), DAY) + 1L;
            long startDayTime = startNight * DAY + DUSK + 1_000L;
            RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, startNight);
            helper.assertTrue(settlement.recurringRaidRun.queue(plan),
                "fixture must queue one recurring serial");
            long serial = settlement.recurringRaidRun.activeSerial();
            RaiderEntity a = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(3, 1, 3));
            RaiderEntity b = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(12, 1, 12));
            a.assign(plan.captainId(), settlement.id, plan.objective(), 1.0F, false);
            b.assign(plan.captainId(), settlement.id, plan.objective(), 1.0F, false);
            LinkedHashSet<UUID> ids = new LinkedHashSet<>(List.of(a.getUUID(), b.getUUID()));
            helper.assertTrue(settlement.recurringRaidRun.sealAndActivate(plan, ids),
                "fixture must seal an active recurring raid");
            settlement.pendingRaid = plan;
            int survivedBefore = settlement.raidLifecycle.raidsSurvived();
            int earnedBefore = settlement.blessingState.earned();
            int pressureBefore = settlement.raidPressure.pressure();

            setDayTimeForward(helper, startDayTime);
            settlement.raidLifecycle.armRecurringRetreat(startDayTime);
            long retreatAt = settlement.raidLifecycle.recurringRetreatAtDayTime();
            helper.assertTrue(retreatAt == (startNight + 1L) * DAY,
                "the retreat is armed for the first dawn after the raid night, got " + retreatAt);

            // During the raid night the raid stays active with its raiders.
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.recurringRaidRun.isActive()
                    && settlement.pendingRaid != null,
                "before dawn the recurring raid must remain active");

            setDayTimeForward(helper, retreatAt);
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.recurringRaidRun.isEmpty()
                    && settlement.recurringRaidRun.lastResolvedSerial() == serial
                    && settlement.recurringRaidRun.lastRewardProcessedSerial() == serial
                    && settlement.pendingRaid == null,
                "dawn must resolve the stuck recurring raid and consume its serial");
            helper.assertTrue(a.isRemoved() && b.isRemoved(),
                "loaded stragglers must flee at dawn");
            helper.assertTrue(settlement.raidLifecycle.isRetreatedRaider(a.getUUID())
                    && settlement.raidLifecycle.isRetreatedRaider(b.getUUID()),
                "stragglers are remembered so late loads can never poison a newer raid");
            helper.assertTrue(settlement.blessingState.earned() == earnedBefore
                    && settlement.raidPressure.pressure() == pressureBefore
                    && settlement.raidLifecycle.raidsSurvived() == survivedBefore
                    && !settlement.raidLifecycle.raidBreather(),
                "a retreat is not-won for rewards and carries no defeat penalty");
            // The live resolution path draws the day itself: it must land in
            // the configured window, and be the persisted pick.
            RaidCadence.Window window =
                com.hearthstead.HearthsteadServerConfig.recurringRaidWindow();
            long nextAttack = settlement.raidLifecycle.recurringNextAttackNight();
            helper.assertTrue(RaidCadence.attackWithinWindow(startNight, nextAttack, window)
                    && nextAttack == startNight
                        + settlement.raidLifecycle.recurringIntervalDays()
                    && settlement.raidLifecycle.recurringRetreatAtDayTime() < 0L,
                "after a retreat the next raid is set " + window.minDays() + ".."
                    + window.maxDays() + " days later, got night " + nextAttack
                    + " for raid night " + startNight);
            helper.assertTrue(!settlement.raidLifecycle.recurringScheduleBlocked()
                    && !settlement.recurringRaidRun.isBlocked(),
                "a retreat must never block the recurring schedule");
        } finally {
            for (RaiderEntity raider : RaidDirector.livingRaidersOf(helper.getLevel(), settlement)) {
                raider.discard();
            }
            forget(helper, settlement);
        }
        helper.succeed();
    }
}

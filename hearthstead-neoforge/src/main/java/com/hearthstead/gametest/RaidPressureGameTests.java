package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidPressure;
import com.hearthstead.settlement.raid.RaidTelegraph;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE A3 step 1 — the raid schedule, before any raider exists.
 *
 * <p>These pin the rules that make Hearthstead's raids different from both
 * references (docs/project/RAID_REFERENCE_RESEARCH.md): no provably safe
 * night, a feedback loop that points forward, and exactly one hard grace
 * guarantee so a nightly roll cannot degenerate into MineColonies #4838.
 *
 * <p>The model takes its roll as a parameter, so none of this is
 * probabilistic — every assertion below is exact.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidPressureGameTests {

    private static Settlement settlement(int settlers, int buildings) {
        Settlement s = new Settlement(UUID.randomUUID(), "Pressuretown",
            BlockPos.ZERO);
        for (int i = 0; i < settlers; i++) {
            s.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        }
        for (int i = 0; i < buildings; i++) {
            Building b = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
                BlockPos.ZERO, BlockPos.ZERO,
                BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO));
            b.valid = true;
            s.buildings.add(b);
        }
        return s;
    }

    /**
     * The core difference from both references: there is no safe night. Even
     * at zero pressure a qualifying settlement can be raided, because
     * MineColonies' nine guaranteed-safe nights out of fourteen are exactly
     * what makes its raids feel scheduled rather than dangerous.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_no_night_is_ever_provably_safe")
    public void noNightIsEverProvablySafe(GameTestHelper helper) {
        Settlement s = settlement(6, 2);
        RaidPressure p = s.raidPressure;
        helper.assertTrue(RaidPressure.worthRaiding(s),
            "6 settlers and 2 buildings should be worth raiding, worth="
                + RaidPressure.worthOf(s));
        helper.assertTrue(p.pressure() == 0, "a new settlement starts calm");
        helper.assertTrue(p.chanceTonight() >= 0.01,
            "even at zero pressure the chance must be materially positive, got "
                + p.chanceTonight());
        // A roll under the floor raids on night one.
        helper.assertTrue(p.rollForNight(s, 1L, RaidPressure.MIN_CHANCE / 2.0),
            "a low roll must be able to raid a calm settlement");
        helper.succeed();
    }

    /** A hamlet is genuinely left alone, and accumulates no hidden debt. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_tiny_settlements_are_not_raided_and_build_no_pressure")
    public void tinySettlementsAreNotRaidedAndBuildNoPressure(GameTestHelper helper) {
        Settlement s = settlement(2, 0);
        RaidPressure p = s.raidPressure;
        helper.assertTrue(!RaidPressure.worthRaiding(s),
            "2 settlers alone should be beneath notice, worth="
                + RaidPressure.worthOf(s));
        for (long night = 1; night <= 30; night++) {
            helper.assertTrue(!p.rollForNight(s, night, 0.0),
                "an unworthy settlement must never be raided, night " + night);
        }
        helper.assertTrue(p.pressure() == 0,
            "and it must not quietly accumulate pressure, got " + p.pressure());
        helper.succeed();
    }

    /**
     * The one hard guarantee. Without it a nightly roll reproduces
     * MineColonies #4838 -- a raid every night until the server restarts.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_never_two_nights_running_below_siege")
    public void neverTwoNightsRunningBelowSiege(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        RaidPressure p = s.raidPressure;
        helper.assertTrue(p.rollForNight(s, 1L, 0.0), "night 1 should raid");
        helper.assertTrue(p.stage() != RaidPressure.Stage.BELEIRING,
            "this settlement should not be under siege yet");
        helper.assertTrue(!p.rollForNight(s, 2L, 0.0),
            "the night straight after a raid must be safe below siege");
        helper.assertTrue(p.rollForNight(s, 3L, 0.0),
            "but the night after that must be available again");
        helper.succeed();
    }

    /** Under siege the grace is gone, and that is the designed crescendo. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_siege_allows_consecutive_nights")
    public void siegeAllowsConsecutiveNights(GameTestHelper helper) {
        Settlement s = settlement(10, 4);
        RaidPressure p = s.raidPressure;
        p.setPressureForTesting(RaidPressure.BELEIRING_THRESHOLD);
        helper.assertTrue(p.stage() == RaidPressure.Stage.BELEIRING,
            "expected BELEIRING at the threshold, got " + p.stage());
        helper.assertTrue(p.rollForNight(s, 1L, 0.0), "night 1 should raid");
        helper.assertTrue(p.rollForNight(s, 2L, 0.0),
            "under siege, consecutive nights are allowed");
        helper.succeed();
    }

    /**
     * The inversion of MineColonies. There, losing more than 15% of the
     * population lowers difficulty AND buys six quiet nights, so the system
     * converges on safe however you play. Here, holding the line makes you
     * a bigger target.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_surviving_a_raid_raises_pressure_and_losing_relieves_it")
    public void survivingARaidRaisesPressureAndLosingRelievesIt(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        RaidPressure p = s.raidPressure;
        p.setPressureForTesting(40);
        p.recordRepelled();
        helper.assertTrue(p.pressure() == 40 + RaidPressure.REPEL_GAIN,
            "repelling must RAISE pressure, got " + p.pressure());
        p.setPressureForTesting(40);
        p.recordLost();
        helper.assertTrue(p.pressure() == 40 - RaidPressure.LOSS_RELIEF,
            "losing relieves some pressure, got " + p.pressure());
        helper.succeed();
    }

    /** Quiet nights are not free: being left alone makes you conspicuous. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_quiet_nights_raise_pressure_and_the_stage_is_readable")
    public void quietNightsRaisePressureAndTheStageIsReadable(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        RaidPressure p = s.raidPressure;
        helper.assertTrue(p.stage() == RaidPressure.Stage.ROLIG,
            "a calm settlement reads as ROLIG, got " + p.stage());
        int before = p.pressure();
        // A roll of 1.0 can never be below any chance, so every night is quiet.
        for (long night = 1; night <= 12; night++) {
            helper.assertTrue(!p.rollForNight(s, night, 1.0),
                "a maximal roll must never raid, night " + night);
        }
        helper.assertTrue(p.pressure() > before,
            "twelve quiet nights must raise pressure, got " + p.pressure());
        helper.assertTrue(p.stage() != RaidPressure.Stage.ROLIG,
            "and the stage must have moved off calm, got " + p.stage());
        helper.assertTrue(p.chanceTonight() > RaidPressure.MIN_CHANCE,
            "a tenser settlement faces a higher chance");
        helper.succeed();
    }

    /** One roll per night, however many times the director asks. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_the_nightly_roll_happens_exactly_once")
    public void theNightlyRollHappensExactlyOnce(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        RaidPressure p = s.raidPressure;
        helper.assertTrue(p.rollForNight(s, 5L, 0.0), "first ask raids");
        helper.assertTrue(!p.rollForNight(s, 5L, 0.0),
            "asking again for the same night must not raid again");
        int pressureAfter = p.pressure();
        for (int i = 0; i < 20; i++) {
            p.rollForNight(s, 5L, 1.0);
        }
        helper.assertTrue(p.pressure() == pressureAfter,
            "repeat asks must not accumulate pressure either, got "
                + p.pressure());
        helper.succeed();
    }

    /** Pressure is settlement state and must survive a save/reload. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_pressure_survives_save_and_reload")
    public void pressureSurvivesSaveAndReload(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        s.raidPressure.setPressureForTesting(63);
        s.raidPressure.rollForNight(s, 9L, 0.0);
        CompoundTag tag = s.writeNbt();
        Settlement reloaded = Settlement.readNbt(tag);
        helper.assertTrue(reloaded.raidPressure.pressure() == s.raidPressure.pressure(),
            "pressure must round-trip, got " + reloaded.raidPressure.pressure()
                + " expected " + s.raidPressure.pressure());
        helper.assertTrue(reloaded.raidPressure.nightsSinceRaid()
                == s.raidPressure.nightsSinceRaid(),
            "nightsSinceRaid must round-trip");
        helper.assertTrue(reloaded.raidPressure.lastRolledNight() == 9L,
            "the last rolled night must round-trip, got "
                + reloaded.raidPressure.lastRolledNight());
        // And the grace guarantee must still hold across the reload.
        helper.assertTrue(!reloaded.raidPressure.rollForNight(reloaded, 10L, 0.0),
            "a reloaded settlement still gets its morning-after grace");
        helper.succeed();
    }

    /**
     * The director asks only after nightfall, and each world day is one
     * night index. Asserted on the arithmetic rather than by setting the
     * level's time: {@code setDayTime} is level-wide, and a test that
     * flipped the world to night would stall every settler in every other
     * test running beside it in the same batch.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_the_director_only_asks_after_nightfall")
    public void theDirectorOnlyAsksAfterNightfall(GameTestHelper helper) {
        helper.assertTrue(!RaidDirector.isRollTime(0L), "midnight-dawn is not roll time");
        helper.assertTrue(!RaidDirector.isRollTime(6000L), "noon is not roll time");
        helper.assertTrue(!RaidDirector.isRollTime(12000L),
            "dusk is not yet roll time");
        helper.assertTrue(RaidDirector.isRollTime(RaidDirector.ROLL_AT_DAYTIME),
            "the roll time itself counts");
        helper.assertTrue(RaidDirector.isRollTime(16000L), "deep night rolls");
        helper.assertTrue(RaidDirector.isRollTime(23999L), "so does pre-dawn");
        // And it repeats every day, rather than only on the first.
        helper.assertTrue(RaidDirector.isRollTime(24000L * 7 + 16000L),
            "night seven rolls too");
        helper.assertTrue(!RaidDirector.isRollTime(24000L * 7 + 6000L),
            "and day seven does not");
        helper.assertTrue(RaidDirector.nightOf(24000L * 7 + 16000L) == 7L,
            "night index should be 7, got "
                + RaidDirector.nightOf(24000L * 7 + 16000L));
        helper.succeed();
    }

    /**
     * Objectives come from what the settlement actually has. Nobody rides
     * out to steal grain from a place with no stores.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_objectives_match_what_the_settlement_actually_has")
    public void objectivesMatchWhatTheSettlementActuallyHas(GameTestHelper helper) {
        Settlement bare = settlement(1, 0);
        helper.assertTrue(!RaidObjective.KORN.isAvailableAt(bare),
            "no warehouse means nothing to come for");
        helper.assertTrue(!RaidObjective.BRANN.isAvailableAt(bare),
            "nothing built means nothing to burn");
        helper.assertTrue(!RaidObjective.LOSEPENGER.isAvailableAt(bare),
            "LOSEPENGER is disarmed (raid-night audit, 2026-08-26): never "
                + "available, not even for a settlement big enough to miss "
                + "a settler -- see RaidObjective#isAvailableAt");
        helper.assertTrue(RaidObjective.BLOD.isAvailableAt(bare),
            "but people are always something to lose");

        Settlement rich = settlement(8, 3);
        // LOSEPENGER is deliberately excluded even here: it is disarmed
        // (RaidObjective#isAvailableAt), not merely gated on wealth, so a
        // rich settlement attracts every OTHER objective but never this one.
        helper.assertTrue(!RaidObjective.LOSEPENGER.isAvailableAt(rich),
            "LOSEPENGER must stay disarmed regardless of settlement size");
        helper.assertTrue(RaidObjective.availableAt(rich).size()
                == RaidObjective.values().length - 1,
            "a settlement with people, buildings and stores attracts every "
                + "objective except the disarmed LOSEPENGER, got "
                + RaidObjective.availableAt(rich));

        // And a pick is always one of the available ones, never a dud.
        var random = helper.getLevel().getRandom();
        for (int i = 0; i < 40; i++) {
            helper.assertTrue(
                RaidObjective.pick(bare, random).isAvailableAt(bare),
                "picked an objective this settlement cannot offer");
        }
        helper.succeed();
    }

    /**
     * The Nemesis half: a settlement remembers its enemies, and a beaten
     * captain comes back by a different road. MineColonies feature request
     * #193 is a player working out that raiders "usually come from the same
     * spawn point" and gang up on one tower guard.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_captains_are_remembered_and_never_take_the_same_road_twice")
    public void captainsAreRememberedAndNeverTakeTheSameRoadTwice(GameTestHelper helper) {
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidCaptain.generate(random);
        helper.assertTrue(captain.name() != null && captain.name().contains(" "),
            "a captain has an earned byname, got " + captain.name());
        helper.assertTrue(!captain.hasApproached(),
            "a new captain has no road behind them yet");

        float previous = captain.nextApproachDegrees(random);
        captain.recordApproach(previous, RaidObjective.KORN);
        for (int i = 0; i < 60; i++) {
            float next = captain.nextApproachDegrees(random);
            float delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(next - previous));
            helper.assertTrue(delta >= RaidCaptain.MIN_APPROACH_SHIFT - 0.01F,
                "approach " + next + " is only " + delta
                    + " degrees off the last one (" + previous + ")");
            captain.recordApproach(next, RaidObjective.KORN);
            previous = next;
        }
        helper.succeed();
    }

    /** Winning makes a captain worse news; losing teaches them something too. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_captains_grow_from_both_outcomes")
    public void captainsGrowFromBothOutcomes(GameTestHelper helper) {
        RaidCaptain captain = RaidCaptain.generate(helper.getLevel().getRandom());
        float base = captain.menace();
        captain.recordDefeat();
        float afterDefeat = captain.menace();
        helper.assertTrue(afterDefeat > base,
            "even a beaten captain learns, got " + afterDefeat + " from " + base);
        captain.recordVictory();
        helper.assertTrue(captain.menace() > afterDefeat,
            "and a win must count for more");
        captain.rememberGrudge("Hedda");
        helper.assertTrue("Hedda".equals(captain.grudge()),
            "a captain remembers who hurt them, got " + captain.grudge());
        helper.succeed();
    }

    /**
     * A scheduled raid, its captain and their grudge must survive a reload.
     * A plan that evaporates on restart is the raid-shaped version of
     * MineColonies' silently-never-happening deliveries.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_a_scheduled_raid_and_its_captain_survive_reload")
    public void aScheduledRaidAndItsCaptainSurviveReload(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        var random = helper.getLevel().getRandom();
        RaidCaptain captain = RaidDirector.pickCaptain(s, random);
        captain.recordVictory();
        captain.rememberGrudge("Yrsa");
        captain.recordApproach(120.0F, RaidObjective.KORN);
        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.KORN, 120.0F, 4L);

        Settlement reloaded = Settlement.readNbt(s.writeNbt());
        helper.assertTrue(reloaded.pendingRaid != null,
            "the scheduled raid must survive a reload");
        helper.assertTrue(reloaded.pendingRaid.objective() == RaidObjective.KORN,
            "objective must round-trip, got " + reloaded.pendingRaid.objective());
        helper.assertTrue(reloaded.pendingRaid.night() == 4L,
            "night must round-trip");
        RaidCaptain back = RaidDirector.captainOf(reloaded,
            reloaded.pendingRaid.captainId());
        helper.assertTrue(back != null,
            "the captain leading it must still be remembered");
        helper.assertTrue(back.name().equals(captain.name()),
            "by name: " + back.name() + " vs " + captain.name());
        helper.assertTrue("Yrsa".equals(back.grudge()),
            "and their grudge must persist, got " + back.grudge());
        helper.assertTrue(back.victories() == 1, "as must their record");
        helper.assertTrue(Math.abs(back.lastApproachDegrees() - 120.0F) < 0.01F,
            "and the road they last took, got " + back.lastApproachDegrees());
        helper.succeed();
    }

    /** The gallery is a cast, not a crowd. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_the_enemy_gallery_stays_bounded")
    public void theEnemyGalleryStaysBounded(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        var random = helper.getLevel().getRandom();
        for (int i = 0; i < 200; i++) {
            RaidDirector.pickCaptain(s, random);
        }
        helper.assertTrue(
            s.raidCaptains.size() <= RaidDirector.MAX_REMEMBERED_CAPTAINS,
            "the enemy gallery must stay bounded, got " + s.raidCaptains.size());
        helper.assertTrue(!s.raidCaptains.isEmpty(),
            "but it must not forget everyone");
        helper.succeed();
    }

    /**
     * On Peaceful nothing hostile can exist, so the schedule must not run
     * at all. Found by playing, not by testing: raiders reported
     * "Summoned" and were discarded a tick later, which would have left the
     * Tingbok announcing a siege that could never arrive.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_peaceful_means_no_raids_and_no_pressure")
    public void peacefulMeansNoRaidsAndNoPressure(GameTestHelper helper) {
        helper.assertTrue(!RaidDirector.raidsPossibleAt(
                net.minecraft.world.Difficulty.PEACEFUL),
            "raids must be impossible on peaceful");
        for (var d : net.minecraft.world.Difficulty.values()) {
            if (d != net.minecraft.world.Difficulty.PEACEFUL) {
                helper.assertTrue(RaidDirector.raidsPossibleAt(d),
                    "raids must be possible on " + d);
            }
        }
        helper.succeed();
    }

    /** A fresh settlement must not inherit the morning-after grace. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_a_new_settlement_is_raidable_on_its_first_qualifying_night")
    public void aNewSettlementIsRaidableOnItsFirstQualifyingNight(GameTestHelper helper) {
        Settlement s = settlement(6, 2);
        helper.assertTrue(!s.raidPressure.inGracePeriod(),
            "a founding settlement has not just survived a raid");
        helper.assertTrue(s.raidPressure.rollForNight(s, 1L, 0.0),
            "so night one is available");
        helper.succeed();
    }

    // ---------------------------------------------------------- SLICE A3-RAIDS ---
    // Telegraphing: the scout at the treeline and the bard's unease
    // (DESIGN.md: "escalating, telegraphed 1-2 days ahead"). Deliberately
    // NOT a predictor of the actual nightly roll -- see RaidTelegraph's
    // class doc for why that would reintroduce a provably-safe schedule.

    /** An omen is a sign of real pressure, not ambient noise -- a calm settlement gets none. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_omens_are_never_scheduled_on_a_calm_night")
    public void omensAreNeverScheduledOnACalmNight(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        helper.assertTrue(s.raidPressure.stage() == RaidPressure.Stage.ROLIG,
            "a fresh settlement reads as ROLIG");
        // roll=0.0 would clear any positive chance, so a true here would
        // mean the calm gate is not actually gating anything.
        helper.assertTrue(!RaidTelegraph.rollForecast(s, 1L, 0.0),
            "a calm settlement must never get a telegraph omen");
        helper.assertTrue(s.raidPressure.forecastNight() < 1L,
            "and nothing should have been scheduled");
        helper.succeed();
    }

    /**
     * Escalation applies to dread, too: a tenser settlement gets warned more
     * often, and Beleiring gives less lead time -- a designed crescendo, the
     * same shape as D-A3-4 letting consecutive raid nights through at that
     * stage.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_omens_escalate_with_pressure_and_give_one_to_two_nights_warning")
    public void omensEscalateWithPressureAndGiveOneToTwoNightsWarning(GameTestHelper helper) {
        Settlement uro = settlement(8, 3);
        uro.raidPressure.setPressureForTesting(RaidPressure.URO_THRESHOLD);
        helper.assertTrue(RaidTelegraph.rollForecast(uro, 10L, 0.0),
            "a roll of 0.0 must always beat a positive schedule chance");
        helper.assertTrue(uro.raidPressure.forecastNight() == 12L,
            "Uro should warn two nights out, got night "
                + uro.raidPressure.forecastNight() + " from night 10");

        Settlement siege = settlement(8, 3);
        siege.raidPressure.setPressureForTesting(RaidPressure.BELEIRING_THRESHOLD);
        helper.assertTrue(RaidTelegraph.rollForecast(siege, 10L, 0.0),
            "Beleiring must also schedule an omen");
        helper.assertTrue(siege.raidPressure.forecastNight() == 11L,
            "but give LESS warning at the top of the curve -- got "
                + siege.raidPressure.forecastNight());
        helper.succeed();
    }

    /** Only one omen is ever pending: it must not be re-rolled out from under itself. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_only_one_omen_is_pending_at_once")
    public void onlyOneOmenIsPendingAtOnce(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        s.raidPressure.setPressureForTesting(RaidPressure.URO_THRESHOLD);
        helper.assertTrue(RaidTelegraph.rollForecast(s, 1L, 0.0), "first ask schedules");
        long first = s.raidPressure.forecastNight();
        helper.assertTrue(!RaidTelegraph.rollForecast(s, 2L, 0.0),
            "a second ask before the first omen has fired must not reschedule it");
        helper.assertTrue(s.raidPressure.forecastNight() == first,
            "the original forecast must be undisturbed, got "
                + s.raidPressure.forecastNight() + " expected " + first);
        helper.succeed();
    }

    /**
     * The dusk gate is pure arithmetic, tested directly rather than by
     * setting the shared level's day time -- exactly like
     * {@link RaidDirector#isRollTime}, and for the same reason (a batch of
     * concurrently running tests shares one level's clock).
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_the_telegraph_fires_at_dusk_before_the_nightly_roll")
    public void theTelegraphFiresAtDuskBeforeTheNightlyRoll(GameTestHelper helper) {
        helper.assertTrue(!RaidTelegraph.isDuskOrLater(RaidTelegraph.DUSK_TIME - 1L),
            "a moment before dusk must not yet be dusk");
        helper.assertTrue(RaidTelegraph.isDuskOrLater(RaidTelegraph.DUSK_TIME),
            "dusk itself counts");
        helper.assertTrue(RaidTelegraph.isDuskOrLater(RaidDirector.ROLL_AT_DAYTIME),
            "and the omen must have fired by the time the roll itself is due, "
                + "since dusk is strictly earlier in the day");
        helper.assertTrue(RaidTelegraph.isDuskOrLater(23999L), "so does pre-dawn");
        helper.assertTrue(!RaidTelegraph.isDuskOrLater(24000L * 4 + 6000L),
            "and it repeats every day rather than staying true forever, "
                + "just like the roll gate does");
        helper.succeed();
    }

    /** Forecast and telegraph state is settlement state, and must survive a reload too. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_pressure_telegraph_state_survives_save_and_reload")
    public void telegraphStateSurvivesSaveAndReload(GameTestHelper helper) {
        Settlement s = settlement(8, 3);
        s.raidPressure.setPressureForTesting(RaidPressure.VARSEL_THRESHOLD);
        helper.assertTrue(RaidTelegraph.rollForecast(s, 4L, 0.0), "schedules an omen");
        s.raidPressure.markTelegraphed(s.raidPressure.forecastNight());

        Settlement reloaded = Settlement.readNbt(s.writeNbt());
        helper.assertTrue(
            reloaded.raidPressure.forecastNight() == s.raidPressure.forecastNight(),
            "the forecast night must round-trip, got "
                + reloaded.raidPressure.forecastNight());
        helper.assertTrue(reloaded.raidPressure.lastTelegraphedNight()
                == s.raidPressure.lastTelegraphedNight(),
            "as must the last night an omen was actually shown");
        helper.succeed();
    }
}

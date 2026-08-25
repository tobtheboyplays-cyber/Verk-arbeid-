package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidPressure;
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
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

    /** A fresh settlement must not inherit the morning-after grace. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "day")
    public void aNewSettlementIsRaidableOnItsFirstQualifyingNight(GameTestHelper helper) {
        Settlement s = settlement(6, 2);
        helper.assertTrue(!s.raidPressure.inGracePeriod(),
            "a founding settlement has not just survived a raid");
        helper.assertTrue(s.raidPressure.rollForNight(s, 1L, 0.0),
            "so night one is available");
        helper.succeed();
    }
}

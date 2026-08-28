package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The guard progression audit, 2026-08-25: the rank ladder reads
 * {@link Attribute#STRENGTH} ({@link GuardRank#of}), but no guard goal used
 * to train it — a career guard could never leave RECRUIT — the night watch
 * had a ~4500-tick morning dead zone in which it was neither on duty nor
 * allowed to sleep, and Cleave's splash scanned every LivingEntity in reach.
 *
 * <p>Each test here pins one of those fixes so it cannot quietly regress.
 * Helpers mirror {@link GuardRankGameTests} and {@link EmploymentGameTests}
 * exactly (a settlement the entity layer can actually find, a small radius so
 * neighbouring arenas cannot answer for each other's hearth).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardTrainingGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** See {@link GuardRankGameTests#settlement}: registered, and small, for
     *  exactly the same reasons. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Treningsholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** See {@link EmploymentGameTests}: a valid building the hire API accepts. */
    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, type, x, z);
    }

    /** See {@link GuardRankGameTests#trainStrengthTo}: repeated small calls,
     *  so the result lands close to the target instead of overshooting. */
    private static void trainStrengthTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.STRENGTH) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.STRENGTH, 5.0F, 1.0F);
        }
    }

    // ------------------------------------------------- combat trains rank ---

    /**
     * The CRITICAL audit finding: rank reads Strength, but no guard goal
     * trained it. Now a blow that actually lands (GuardMeleeGoal gates on
     * canPerformAttack, the same predicate vanilla deals the damage on) pays
     * {@link GuardRank#TRAIN_COMBAT} — so a guard who fights visibly climbs.
     * The raider is a no-AI pell so nothing here can kill the fixture.
     */
    @GameTest(batch = "guard_training", template = "empty16", timeoutTicks = 400)
    public void landedMeleeHitsTrainStrength(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity guard = settler(helper, s, "Rekrutt", 4, 6);
        guard.assignProfession(Profession.GUARD);
        int before = guard.attribute(Attribute.STRENGTH);

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 4));
        pell.setNoAi(true);
        guard.setTarget(pell);

        helper.succeedWhen(() -> helper.assertTrue(
            guard.attribute(Attribute.STRENGTH) > before,
            "a landed blow must train Strength -- the rank ladder reads it"
                + " (started " + before + ", still "
                + guard.attribute(Attribute.STRENGTH) + ")"));
    }

    // ------------------------------------------------ splash is hostile-only ---

    /**
     * Cleave's secondary victim must be a {@link RaiderEntity}: the swing
     * follows through into the raid, never into a bystander. The pig is
     * parked no-AI right beside the guard — well inside cleave's 2.2-block
     * reach — through many swings, and must come out at full health while
     * the raider demonstrably does not.
     *
     * <p>Diagnosed 20260826: the pig was never hit at all. It was buried in
     * the arena's own floor/platform at its nominal spawn cell and took
     * vanilla suffocation damage (1 HP every 10 ticks, {@code
     * DamageTypes.IN_WALL}) until it died and read as a mystery removal --
     * a real cleave bug would have left an attacker on the damage source,
     * this left none. Same class of fixture bug as {@code
     * AdvancementGameTests.hangingAPlaqueGrantsTheFirstStepsAdvancement}'s
     * buried target cell. The spawn cell is cleared explicitly below and
     * checked at full health before any waiting, so a fixture that buries
     * its own control animal fails loudly right there instead of looking
     * like a cleave bug two hundred lines away.
     */
    @GameTest(batch = "guard_training", template = "empty16", timeoutTicks = 400)
    public void cleaveSplashNeverHitsABystander(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity guard = settler(helper, s, "Veteran", 4, 5);
        guard.assignProfession(Profession.GUARD);
        trainStrengthTo(guard, GuardRank.VETERAN.threshold());
        helper.assertTrue(GuardRank.of(guard).atLeast(GuardRank.VETERAN),
            "fixture sanity: cleave needs a Veteran, Strength="
                + guard.attribute(Attribute.STRENGTH));

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(4, 1, 4));
        raider.setNoAi(true);
        float raiderMax = raider.getMaxHealth();
        // The pig's own cell, cleared explicitly rather than trusted -- the
        // arena's floor/platform can fill the cell a spawn call assumes is
        // air (see the class javadoc above).
        BlockPos pigSpawn = new BlockPos(5, 1, 5);
        helper.setBlock(pigSpawn, Blocks.AIR);
        Pig bystander = helper.spawn(EntityType.PIG, pigSpawn);
        bystander.setNoAi(true);
        float pigHealth = bystander.getHealth();
        helper.assertTrue(pigHealth == bystander.getMaxHealth(),
            "fixture sanity: the bystander must spawn at full health, not "
                + pigHealth + "/" + bystander.getMaxHealth()
                + " -- its spawn cell may be buried in the arena floor");
        guard.setTarget(raider);

        // Long enough for several swings (one per 20-tick attack cooldown),
        // so this asserts across many cleave attempts, not one lucky miss.
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(!raider.isAlive() || raider.getHealth() < raiderMax,
                "fixture sanity: the guard must actually have landed blows, raider at "
                    + raider.getHealth() + "/" + raiderMax);
            // Name the killer rather than assuming it. Both splash filters
            // take RaiderEntity only and the target goal takes Monster only,
            // so if this pig is ever hurt the interesting question is BY WHAT
            // -- and a failure that just says "pig at 0.0" sends the next
            // reader hunting through guard code that cannot have done it.
            helper.assertTrue(bystander.isAlive() && bystander.getHealth() >= pigHealth,
                "cleave splash must never touch a non-raider bystander, pig at "
                    + bystander.getHealth() + "/" + pigHealth
                    + " lastDamage=" + (bystander.getLastDamageSource() == null
                        ? "none" : bystander.getLastDamageSource().getMsgId())
                    + " attacker=" + (bystander.getLastDamageSource() == null
                        ? "none" : String.valueOf(bystander.getLastDamageSource().getEntity()))
                    + " removed=" + bystander.isRemoved()
                    + " removalReason=" + bystander.getRemovalReason()
                    + " pos=" + bystander.blockPosition().toShortString());
            helper.succeed();
        });
    }

    // --------------------------------------------- the night watch's clock ---

    /**
     * The HIGH audit finding: {@link Schedule#onWatch}'s night set is
     * {REST, EVENING, RISE} but {@link Schedule#shouldSleep} used to allow
     * night-watch sleep only in {MEAL, AFTERNOON_WORK} — MORNING_WORK
     * (ticks 1000–5500) was in neither, a ~4500-tick dead zone spent
     * standing at the barracks. Pure static logic, asserted directly: the
     * morning is now sleep, and the whole clock partitions with no phase
     * left in neither set (the dead zone) and none in both (asleep on
     * watch). The two-guards-one-barracks fixture is what makes the night
     * guard deterministic (worker index parity, see Employment.watchOf).
     */
    @GameTest(batch = "guard_training", template = "empty16", timeoutTicks = 200)
    public void theNightWatchSleepsThroughTheWholeMorning(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building barracks = building(helper, s, BuildingType.BARRACKS, 2, 2);
        SettlerEntity first = settler(helper, s, "Dag", 4, 4);
        SettlerEntity second = settler(helper, s, "Natt", 5, 4);
        Employment.hire(helper.getLevel(), s, barracks, first);
        Employment.hire(helper.getLevel(), s, barracks, second);

        SettlerEntity night =
            Employment.watchOf(s, first) == Employment.Watch.NIGHT ? first : second;
        helper.assertTrue(Employment.watchOf(s, night) == Employment.Watch.NIGHT,
            "fixture sanity: one of two barracks guards stands the night watch");

        helper.assertTrue(Schedule.shouldSleep(s, night, DayPhase.MORNING_WORK),
            "a night guard's morning is for sleeping, not standing at the barracks");

        for (DayPhase phase : DayPhase.values()) {
            boolean duty = Schedule.onWatch(s, night, phase);
            boolean sleep = Schedule.shouldSleep(s, night, phase);
            helper.assertTrue(duty != sleep,
                "night watch at " + phase + ": onWatch=" + duty
                    + " shouldSleep=" + sleep + " -- must be exactly one of the two");
        }
        helper.succeed();
    }

    // -------------------------------------------- the front door, fighting ---

    /**
     * ACCEPT-JOBS audit (2026-08-26): {@code landedMeleeHitsTrainStrength}
     * above and {@code RaiderGameTests#guardsTreatRaidersAsHostile} between
     * them prove that a GUARD trained by {@code assignProfession} directly
     * fights, and that a GUARD's own {@code SettlerDefenseTargetGoal} finds
     * a raider on its own -- but neither test ever calls {@link
     * Employment#hire} at a real BARRACKS, and every hire-mechanic test in
     * {@code EmploymentGameTests} stops at the profession assignment, never
     * watching the settler actually fight. No single test closes the full
     * chain the owner is judging: hired through the front door, at a real
     * building, finding its own target, landing real blows -- with no
     * {@code setTarget} shortcut anywhere in this one.
     */
    @GameTest(batch = "guard_training", template = "empty16", timeoutTicks = 400)
    public void aHiredGuardFindsAndFightsARaiderWithNoHelp(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building barracks = building(helper, s, BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, s, "Vakt", 4, 4);

        Employment.Hired hired = Employment.hire(helper.getLevel(), s, barracks, guard);
        helper.assertTrue(hired.ok(),
            "the barracks must be able to hire a guard, refused with "
                + hired.refusal());
        helper.assertTrue(guard.getProfession() == Profession.GUARD,
            "hired into the barracks, they take up the trade");
        helper.assertTrue(guard.getTarget() == null,
            "fixture sanity: nothing may hand the guard a target");

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(6, 1, 6));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        raider.setNoAi(true);
        float raiderMax = raider.getMaxHealth();

        helper.succeedWhen(() -> {
            helper.assertTrue(guard.getTarget() == raider,
                "the guard must find the raider through its OWN "
                    + "SettlerDefenseTargetGoal (never setTarget from the test), "
                    + "got " + guard.getTarget());
            helper.assertTrue(!raider.isAlive() || raider.getHealth() < raiderMax,
                "a guard hired through Employment.hire that finds its own target "
                    + "must actually land blows, raider at " + raider.getHealth()
                    + "/" + raiderMax);
        });
    }
}

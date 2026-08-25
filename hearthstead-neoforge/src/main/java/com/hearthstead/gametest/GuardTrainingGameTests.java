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
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
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
    @GameTest(template = "empty16", timeoutTicks = 400)
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
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
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
        Pig bystander = helper.spawn(EntityType.PIG, new BlockPos(5, 1, 5));
        bystander.setNoAi(true);
        float pigHealth = bystander.getHealth();
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
    @GameTest(template = "empty16", timeoutTicks = 200)
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
}

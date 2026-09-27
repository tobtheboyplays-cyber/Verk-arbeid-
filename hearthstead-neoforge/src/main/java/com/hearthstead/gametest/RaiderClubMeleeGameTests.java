package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.RaiderMeleeGoal;
import com.hearthstead.entity.combat.RaiderMove;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Runtime checks for BRUTE's server-owned club contact only. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class RaiderClubMeleeGameTests {

    private record Fixture(Settlement settlement, RaiderEntity brute, SettlerEntity target,
                           RaiderMeleeGoal goal, float startingHealth) {
    }

    private static Fixture fixture(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z),
                    Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Clubholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        SettlementSavedData.get(helper.getLevel()).settlements.put(
            settlement.id, settlement);

        SettlerEntity target = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(7, 1, 8));
        target.setSettlerName("Shieldless");
        target.bindTo(settlement.id, settlement.center);
        settlement.putRecord(target.getUUID(), target.getSettlerName(),
            Profession.NONE);
        target.setNoAi(true);

        RaiderEntity brute = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(6, 1, 8));
        brute.setVariant(RaiderEntity.Variant.BRUTE);
        brute.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD,
            1.0F, false);
        brute.setNoAi(true);
        brute.setTarget(target);
        // The Brute now mostly swings RaiderMove.HEAVY. These proofs pin the
        // historical club timeline, so the first swing is forced to CLUB
        // (RaiderMoveGameTests covers the heavy and the selector).
        RaiderMeleeGoal goal = new RaiderMeleeGoal(brute, 1.0);
        goal.forceNextMove(RaiderMove.CLUB);
        return new Fixture(settlement, brute, target, goal,
            target.getHealth());
    }

    private static void tick(GameTestHelper helper, long at,
                             RaiderMeleeGoal goal, Runnable assertion) {
        GameTestTicks.at(helper, at, () -> {
            goal.tick();
            assertion.run();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "raider_brute_club_contacts_once_on_tick_seven")
    public void bruteClubContactsOnceOnTickSeven(GameTestHelper helper) {
        Fixture f = fixture(helper);
        for (int elapsed = 0; elapsed < RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK;
             elapsed++) {
            tick(helper, 1L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "club must not deal early damage before tick "
                        + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK));
        }
        tick(helper, 1L + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, f.goal(), () -> {
            helper.assertTrue(f.target().getHealth() < f.startingHealth(),
                "BRUTE club must land its ordinary damage on tick seven");
            helper.assertTrue(f.brute().committedBruteClubContacts() == 1L,
                "one accepted club contact must record exactly once");
        });
        tick(helper, 2L + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, f.goal(), () -> {
            helper.assertTrue(f.brute().committedBruteClubContacts() == 1L,
                "a consumed club ticket must not replay after recovery");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "raider_brute_club_registered_goal_keeps_windup_until_contact")
    public void bruteClubRegisteredGoalKeepsWindupUntilContact(GameTestHelper helper) {
        Fixture f = fixture(helper);
        // Exercise RaiderEntity's registered GoalSelector, unlike the direct
        // goal calls above. The passive adjacent target makes navigation finish
        // before contact, which is the lifecycle edge this test protects.
        f.brute().setNoAi(false);
        // 45 ticks: the retuned crushing blows contact at tick 14 (club) or
        // 18 (heavy) after the goal first starts (previously 7 within 30).
        helper.runAfterDelay(45L, () -> {
            helper.assertTrue(f.target().getHealth() < f.startingHealth(),
                "registered Brute goal must keep a valid wind-up through its contact");
            helper.assertTrue(f.brute().committedBruteClubContacts() >= 1L,
                "registered Brute goal must commit its pending club contact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "raider_brute_club_lost_range_cancels_without_damage")
    public void bruteClubLostRangeCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tick(helper, 1L, f.goal(), () -> { });
        GameTestTicks.at(helper, 2L, () -> f.target().teleportTo(
            f.brute().getX() + 6.0D, f.brute().getY(), f.brute().getZ()));
        for (int elapsed = 1; elapsed <= RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK;
             elapsed++) {
            tick(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "a target leaving club range during wind-up must take no damage"));
        }
        GameTestTicks.at(helper, 3L + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, () -> {
            helper.assertTrue(f.brute().committedBruteClubContacts() == 0L,
                "a range-cancelled ticket must have no terminal contact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "raider_brute_club_target_switch_cancels_old_ticket")
    public void bruteClubTargetSwitchCancelsOldTicket(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tick(helper, 1L, f.goal(), () -> { });
        GameTestTicks.at(helper, 2L, () -> {
            SettlerEntity replacement = helper.spawn(ModEntities.SETTLER.get(),
                new BlockPos(7, 1, 10));
            replacement.setSettlerName("Replacement");
            replacement.bindTo(f.settlement().id, f.settlement().center);
            replacement.setNoAi(true);
            f.brute().setTarget(replacement);
        });
        for (int elapsed = 1; elapsed <= RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK;
             elapsed++) {
            tick(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "a switched target must not inherit the old club ticket"));
        }
        GameTestTicks.at(helper, 3L + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, () -> {
            helper.assertTrue(f.brute().committedBruteClubContacts() == 0L,
                "the switched-away target's ticket must remain uncommitted");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "raider_brute_club_lost_los_cancels_without_damage")
    public void bruteClubLostLineOfSightCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tick(helper, 1L, f.goal(), () -> { });
        GameTestTicks.at(helper, 2L, () -> {
            helper.setBlock(new BlockPos(7, 1, 8), Blocks.IRON_BARS);
            helper.setBlock(new BlockPos(7, 2, 8), Blocks.IRON_BARS);
            f.brute().getSensing().tick();
            helper.assertFalse(f.brute().hasLineOfSight(f.target()),
                "fixture must break sight without moving the club target");
        });
        for (int elapsed = 1; elapsed <= RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK;
             elapsed++) {
            tick(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "lost line of sight during wind-up must prevent club damage"));
        }
        GameTestTicks.at(helper, 3L + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, () -> {
            helper.assertTrue(f.brute().committedBruteClubContacts() == 0L,
                "a line-of-sight cancellation must have no terminal contact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "raider_brute_club_dead_target_cancels_without_damage")
    public void bruteClubDeadTargetCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tick(helper, 1L, f.goal(), () -> { });
        GameTestTicks.at(helper, 2L, f.target()::kill);
        GameTestTicks.at(helper, 1L + RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, () -> {
            f.goal().tick();
            helper.assertFalse(f.target().isAlive(),
                "fixture target must be dead before club contact");
            helper.assertTrue(f.brute().committedBruteClubContacts() == 0L,
                "dead target must consume/cancel the pending club ticket");
            helper.succeed();
        });
    }
}

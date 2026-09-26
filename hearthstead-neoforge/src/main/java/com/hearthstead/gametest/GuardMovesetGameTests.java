package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.RaiderMeleeGoal;
import com.hearthstead.entity.combat.GuardMove;
import com.hearthstead.entity.combat.RaiderMove;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Runtime proofs for the guard moveset (light, heavy, light-light-finisher
 * combo, shield bash) and the raider light/heavy pair. Every batch name starts
 * with {@code guard_moveset_} so the lane can be run alone with
 * {@code -Dhearthstead.gametest.batchPrefix=guard_moveset_}.
 *
 * <p>Goals are ticked directly on no-AI entities, exactly as the contact
 * proofs in {@link GuardMeleeContactGameTests} do, so every server tick is
 * observed and nothing depends on pathing.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardMovesetGameTests {

    private record Fixture(Settlement settlement, SettlerEntity guard,
                           RaiderEntity raider, GuardMeleeGoal guardGoal,
                           RaiderMeleeGoal raiderGoal) {
    }

    private static Fixture fixture(GameTestHelper helper, RaiderEntity.Variant variant,
                                   boolean shield) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Movesetholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 8));
        guard.setSettlerName("Edge");
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), guard.getSettlerName(), Profession.NONE);
        guard.assignProfession(Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        if (shield) {
            guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }
        // Recruit, and no rolled Strength: forced moves stay the only choice
        // and one landed hit never crosses a rank threshold mid-test.
        guard.attributes().pinForTest(Attribute.STRENGTH, 0);
        guard.attributes().pinForTest(Attribute.WITS, 0);
        guard.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        guard.setHealth(200.0F);
        guard.setNoAi(true);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(7, 1, 8));
        raider.setVariant(variant);
        raider.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
        // Durable, immovable dummy: several blows must land from the same
        // spot without killing it or knocking it out of reach.
        raider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        raider.setHealth(200.0F);
        raider.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        raider.setNoAi(true);

        guard.setTarget(raider);
        raider.setTarget(guard);
        return new Fixture(settlement, guard, raider, new GuardMeleeGoal(guard),
            new RaiderMeleeGoal(raider, 1.0D));
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_heavy_hits_only_on_its_hit_tick")
    public void heavyDamagesOnlyOnItsHitTickAndStaggers(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.BRUTE, false);
        f.guardGoal().forceNextMove(GuardMove.HEAVY, false);
        int hit = GuardMove.HEAVY.hitTick();
        float[] start = {Float.NaN};
        float[] after = {Float.NaN};
        for (long t = 1L; t <= 2L + hit; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (tick == 1L) {
                    start[0] = f.raider().getHealth();
                }
                f.guardGoal().tick();
                if (tick == 1L) {
                    helper.assertTrue(f.guardGoal().pendingMove() == GuardMove.HEAVY,
                        "the forced heavy must begin its wind-up at tick 1");
                }
                if (tick < 1L + hit) {
                    helper.assertTrue(f.raider().getHealth() == start[0],
                        "heavy dealt damage during its wind-up at tick " + tick);
                    helper.assertTrue(f.guard().committedMeleeContacts() == 0L,
                        "no contact receipt before the hit tick");
                } else if (tick == 1L + hit) {
                    helper.assertTrue(f.raider().getHealth() < start[0],
                        "the heavy must land exactly on its hit tick " + hit);
                    helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                        "one heavy, one contact receipt");
                    helper.assertTrue(f.raider().isStaggered(),
                        "a landed heavy staggers the target");
                    helper.assertTrue(f.raider().beginMeleeMove(f.guard(),
                            RaiderMove.LIGHT) == 0L,
                        "a staggered raider cannot start a wind-up");
                    after[0] = f.raider().getHealth();
                } else {
                    helper.assertTrue(f.raider().getHealth() == after[0],
                        "one heavy must produce exactly one damage pass");
                    helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                        "no duplicate receipt after the heavy resolved");
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_combo_lands_three_links_on_their_ticks")
    public void comboLandsLightLightFinisherOnExactTicks(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.SKIRMISHER, false);
        f.guardGoal().forceNextMove(GuardMove.LIGHT_A, true);
        // A starts at 1 and hits at 5; B chains at 1+6=7 and hits at 11;
        // the finisher chains at 7+8=15 and hits at 19.
        long aHit = 1L + GuardMove.LIGHT_A.hitTick();
        long bStart = 1L + GuardMove.LIGHT_A.chainTick();
        long bHit = bStart + GuardMove.LIGHT_B.hitTick();
        long fStart = bStart + GuardMove.LIGHT_B.chainTick();
        long fHit = fStart + GuardMove.COMBO_FINISHER.hitTick();
        float[] last = {Float.NaN};
        for (long t = 1L; t <= fHit + 2L; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                float before = f.raider().getHealth();
                f.guardGoal().tick();
                long expected = (tick >= aHit ? 1 : 0) + (tick >= bHit ? 1 : 0)
                    + (tick >= fHit ? 1 : 0);
                helper.assertTrue(f.guard().committedMeleeContacts() == expected,
                    "tick " + tick + ": expected " + expected + " contacts, got "
                        + f.guard().committedMeleeContacts());
                boolean hitTick = tick == aHit || tick == bHit || tick == fHit;
                helper.assertTrue(hitTick == (f.raider().getHealth() < before),
                    "tick " + tick + ": damage must land on and only on link hit ticks");
                if (tick == bStart) {
                    helper.assertTrue(f.guardGoal().pendingMove() == GuardMove.LIGHT_B,
                        "the mirrored second link must chain at tick " + bStart);
                }
                if (tick == fStart) {
                    helper.assertTrue(f.guardGoal().pendingMove() == GuardMove.COMBO_FINISHER,
                        "the finisher must chain at tick " + fStart);
                }
                if (tick == fHit) {
                    helper.assertTrue(f.raider().isStaggered(),
                        "the combo finisher staggers");
                    last[0] = f.raider().getHealth();
                }
                if (tick == fHit + 2L) {
                    helper.assertTrue(f.guardGoal().landedMoves() == 3,
                        "exactly three landed links");
                    helper.assertTrue(f.guardGoal().comboLinkIndex() == -1
                            && !f.guardGoal().awaitingComboLink(),
                        "the combo ends after its finisher");
                    helper.assertTrue(f.raider().getHealth() == last[0],
                        "no trailing damage after the finisher");
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_combo_breaks_when_target_leaves_reach")
    public void comboBreaksWhenTargetLeavesReach(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.SKIRMISHER, false);
        f.guardGoal().forceNextMove(GuardMove.LIGHT_A, true);
        long aHit = 1L + GuardMove.LIGHT_A.hitTick();
        long windowOpens = 1L + GuardMove.LIGHT_A.chainTick();
        long windowCloses = windowOpens + 6L;
        float[] afterA = {Float.NaN};
        for (long t = 1L; t <= windowCloses + 3L; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (tick == aHit + 1L) {
                    // Step well outside blade reach before the chain point.
                    f.raider().teleportTo(f.guard().getX() + 6.0D,
                        f.guard().getY(), f.guard().getZ());
                }
                f.guardGoal().tick();
                if (tick == aHit) {
                    helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                        "fixture: the first link must land");
                    helper.assertTrue(f.guardGoal().awaitingComboLink(),
                        "a landed planned link opens the chain window");
                    afterA[0] = f.raider().getHealth();
                }
                if (tick > aHit) {
                    helper.assertTrue(f.guardGoal().pendingMove() == null,
                        "no follow-up link may start while the target is out of reach (tick "
                            + tick + ")");
                    helper.assertTrue(f.raider().getHealth() == afterA[0],
                        "an out-of-reach target must take no further damage");
                }
                if (tick == windowCloses + 3L) {
                    helper.assertTrue(!f.guardGoal().awaitingComboLink()
                            && f.guardGoal().comboLinkIndex() == -1,
                        "the combo must break once its window closes");
                    helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                        "only the first link landed");
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_bash_interrupts_enemy_heavy_windup")
    public void shieldBashInterruptsAnEnemyHeavyWindup(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.BRUTE, true);
        // Shield Bash is the Spearman's rank ability.
        f.guard().attributes().pinForTest(Attribute.STRENGTH, 20);
        f.raiderGoal().forceNextMove(RaiderMove.HEAVY);
        long raiderContact = 1L + RaiderMove.HEAVY.hitTick();
        float[] guardStart = {Float.NaN};
        for (long t = 1L; t <= raiderContact + 3L; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (tick == 1L) {
                    guardStart[0] = f.guard().getHealth();
                }
                f.raiderGoal().tick();
                if (tick == 1L) {
                    helper.assertTrue(f.raider().isWindingUpHeavy(),
                        "fixture: the brute must be winding up its heavy");
                }
                f.guardGoal().tick();
                if (tick == 1L) {
                    helper.assertTrue(f.guardGoal().pendingMove() == GuardMove.SHIELD_BASH,
                        "a shielded guard must read the heavy and answer with a bash");
                }
                if (tick == 1L + GuardMove.SHIELD_BASH.hitTick()) {
                    helper.assertTrue(f.guardGoal().lastResolvedMove() == GuardMove.SHIELD_BASH
                            && f.guardGoal().lastResolvedLanded(),
                        "the bash must land on its hit tick");
                    helper.assertTrue(f.raider().isStaggered(),
                        "a landed bash staggers the raider");
                    helper.assertTrue(!f.raider().isWindingUpHeavy()
                            && f.raider().pendingMeleeMove() == null,
                        "the bash must cancel the heavy wind-up outright");
                }
                if (tick >= raiderContact) {
                    helper.assertTrue(f.guard().getHealth() == guardStart[0],
                        "the interrupted heavy must never land (tick " + tick + ")");
                    helper.assertTrue(f.raider().committedMeleeMoveContacts() == 0L,
                        "the interrupted heavy must leave no contact receipt");
                }
                if (tick == raiderContact + 3L) {
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_raider_heavy_lands_once_and_staggers_guard")
    public void raiderHeavyLandsOnItsHitTickAndStaggersAGuard(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.BRUTE, false);
        f.raiderGoal().forceNextMove(RaiderMove.HEAVY);
        long contact = 1L + RaiderMove.HEAVY.hitTick();
        float[] start = {Float.NaN};
        float[] after = {Float.NaN};
        for (long t = 1L; t <= contact + 2L; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (tick == 1L) {
                    start[0] = f.guard().getHealth();
                }
                f.raiderGoal().tick();
                if (tick < contact) {
                    helper.assertTrue(f.guard().getHealth() == start[0],
                        "raider heavy dealt damage during its wind-up at tick " + tick);
                } else if (tick == contact) {
                    helper.assertTrue(f.guard().getHealth() < start[0],
                        "the raider heavy must land on tick " + contact);
                    helper.assertTrue(f.raider().committedMeleeMoveContacts() == 1L,
                        "one heavy, one receipt");
                    helper.assertTrue(f.guard().isCombatStaggered(),
                        "a landed heavy staggers the guard");
                    helper.assertTrue(f.guard().beginGuardMove(f.raider(),
                            GuardMove.LIGHT_A) == 0L,
                        "a staggered guard cannot start a move");
                    after[0] = f.guard().getHealth();
                } else {
                    helper.assertTrue(f.guard().getHealth() == after[0],
                        "no second damage pass from one raider heavy");
                    helper.assertTrue(f.raider().committedMeleeMoveContacts() == 1L,
                        "no duplicate raider receipt");
                    if (tick == contact + 2L) {
                        helper.succeed();
                    }
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_spear_guard_swings_on_its_own_clip_and_reach")
    public void spearGuardUsesItsWeaponTimingReachAndNeverChains(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.SKIRMISHER, false);
        f.guard().setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(com.hearthstead.registry.RoleItems.IRON_SPEAR.get()));
        // 1.8 blocks apart: past a sword's vanilla box, inside a spear's reach.
        f.raider().teleportTo(f.guard().getX() + 1.8D, f.guard().getY(), f.guard().getZ());
        f.guardGoal().forceNextMove(GuardMove.LIGHT_A, true);
        int hit = GuardMove.LIGHT_A.hitTick(com.hearthstead.entity.combat.WeaponClass.SPEAR);
        float[] start = {Float.NaN};
        for (long t = 1L; t <= hit + 12L; t++) {
            long tick = t;
            helper.runAtTickTime(tick, () -> {
                if (tick == 1L) {
                    start[0] = f.raider().getHealth();
                    helper.assertTrue(f.guard().hasGuardMeleeWeapon()
                            && !f.guard().hasPhysicalMainhandSword(),
                        "fixture: a spear is an accepted guard weapon that is not a sword");
                    helper.assertTrue(!f.guard().isWithinMeleeAttackRange(f.raider())
                            && f.guard().isWithinGuardReach(f.raider()),
                        "fixture: the target is beyond sword reach but inside spear reach");
                }
                f.guardGoal().tick();
                if (tick < 1L + hit) {
                    helper.assertTrue(f.raider().getHealth() == start[0],
                        "no damage before the spear's own contact tick " + hit);
                } else if (tick == 1L + hit) {
                    helper.assertTrue(f.raider().getHealth() < start[0],
                        "the spear lands on its WeaponClass contact tick from spear reach");
                    helper.assertTrue(!f.guardGoal().awaitingComboLink()
                            && f.guardGoal().comboLinkIndex() == -1,
                        "a two-handed weapon never chains a combo, even when planned");
                } else if (tick == hit + 12L) {
                    helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                        "one spear thrust, one contact");
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_moveset_unshielded_guard_steps_back_from_late_heavy")
    public void unshieldedGuardStepsBackFromAnImminentHeavy(GameTestHelper helper) {
        Fixture f = fixture(helper, RaiderEntity.Variant.BRUTE, false);
        f.raiderGoal().forceNextMove(RaiderMove.HEAVY);
        long contact = 1L + RaiderMove.HEAVY.hitTick();
        // The guard only notices once the blow is 3 ticks out: too late for a
        // light (4 ticks) and it has no shield, so it must back off.
        long notice = contact - 3L;
        for (long t = 1L; t <= notice; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                f.raiderGoal().tick();
                if (tick == notice) {
                    helper.assertTrue(f.raider().ticksUntilHeavyContact() == 3,
                        "fixture: heavy contact must be 3 ticks out, was "
                            + f.raider().ticksUntilHeavyContact());
                    f.guardGoal().tick();
                    helper.assertTrue(f.guardGoal().isEvading(),
                        "an unshielded guard must step back from an imminent heavy");
                    helper.assertTrue(f.guardGoal().pendingMove() == null,
                        "stepping back never starts a doomed swing");
                    helper.succeed();
                }
            });
        }
    }
}

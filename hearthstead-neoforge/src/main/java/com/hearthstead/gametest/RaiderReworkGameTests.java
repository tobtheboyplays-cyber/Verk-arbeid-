package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.RaiderMeleeGoal;
import com.hearthstead.entity.ai.RaiderSkirmishGoal;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Runtime proofs for the 2026-09-26 raider rework: the Brute's ground-slam
 * shockwave and the Skirmisher's hit-and-run jab/retreat and dodge. Batch
 * names start with {@code raider_rework_}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaiderReworkGameTests {

    private static Settlement arena(GameTestHelper helper, String name) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement,
                                         BlockPos pos, String name, Profession job) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), pos);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        if (job != Profession.NONE) {
            settler.assignProfession(job);
        }
        if (job == Profession.GUARD) {
            settler.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            settler.attributes().pinForTest(Attribute.STRENGTH, 0);
            settler.attributes().pinForTest(Attribute.WITS, 0);
        }
        settler.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        settler.setHealth(200.0F);
        settler.setNoAi(true);
        return settler;
    }

    private static RaiderEntity raider(GameTestHelper helper, Settlement settlement,
                                       BlockPos pos, RaiderEntity.Variant variant) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), pos);
        raider.setVariant(variant);
        raider.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, false);
        raider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        raider.setHealth(200.0F);
        raider.setNoAi(true);
        return raider;
    }

    private static double horizontal(Vec3 v) {
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static void face(RaiderEntity raider, float yaw) {
        raider.setYRot(yaw);
        raider.setYHeadRot(yaw);
        raider.yBodyRot = yaw;
        raider.yBodyRotO = yaw;
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "raider_rework_brute_slam_pushes_defenders_not_raiders")
    public void bruteSlamPushesAndStaggersDefendersButNotRaiders(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Slamholm");
        RaiderEntity brute = raider(helper, settlement, new BlockPos(6, 1, 8),
            RaiderEntity.Variant.BRUTE);
        face(brute, -90.0F); // facing +X, towards the main target
        SettlerEntity target = settler(helper, settlement, new BlockPos(7, 1, 8),
            "Anvil", Profession.GUARD);
        SettlerEntity sideGuard = settler(helper, settlement, new BlockPos(8, 1, 9),
            "Flank", Profession.GUARD);
        SettlerEntity civilian = settler(helper, settlement, new BlockPos(9, 1, 7),
            "Miller", Profession.NONE);
        RaiderEntity ally = raider(helper, settlement, new BlockPos(8, 1, 7),
            RaiderEntity.Variant.SKIRMISHER);
        brute.setTarget(target);
        RaiderMeleeGoal goal = new RaiderMeleeGoal(brute, 1.0D);
        goal.forceNextMove(RaiderMove.HEAVY);
        long contact = 1L + RaiderMove.HEAVY.hitTick();
        float[] start = {Float.NaN};
        float[] after = {Float.NaN};
        for (long t = 1L; t <= contact + 1L; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (tick == 1L) {
                    start[0] = target.getHealth();
                }
                face(brute, -90.0F);
                goal.tick();
                if (tick < contact) {
                    helper.assertTrue(target.getHealth() == start[0],
                        "no damage during the slam wind-up (tick " + tick + ")");
                    helper.assertTrue(brute.lastSlamAffected() == 0,
                        "no shockwave before the contact tick");
                    return;
                }
                if (tick == contact) {
                    Vec3 point = brute.slamPoint();
                    helper.assertTrue(target.getHealth() < start[0],
                        "the main target takes the heavy on its contact tick");
                    helper.assertTrue(brute.committedMeleeMoveContacts() == 1L,
                        "one heavy, one damage receipt");
                    helper.assertTrue(target.isCombatStaggered(),
                        "the main guard is staggered");
                    for (SettlerEntity pushed : new SettlerEntity[] {sideGuard, civilian}) {
                        Vec3 v = pushed.getDeltaMovement();
                        Vec3 away = pushed.position().subtract(point);
                        helper.assertTrue(horizontal(v) > 0.05D
                                && v.x * away.x + v.z * away.z > 0.0D,
                            pushed.getSettlerName() + " must be pushed radially away, v=" + v);
                    }
                    helper.assertTrue(sideGuard.isCombatStaggered(),
                        "a guard inside the shockwave is briefly staggered");
                    helper.assertTrue(horizontal(ally.getDeltaMovement()) < 1.0E-3D
                            && !ally.isStaggered(),
                        "other raiders are never touched by the slam");
                    helper.assertTrue(brute.lastSlamAffected() == 2,
                        "exactly the two bystanding defenders feel the shockwave, was "
                            + brute.lastSlamAffected());
                    helper.assertTrue(sideGuard.getHealth() >= 199.0F,
                        "bystanders take little or no damage");
                    after[0] = target.getHealth();
                    return;
                }
                helper.assertTrue(target.getHealth() == after[0],
                    "the main target is damaged exactly once");
                helper.assertTrue(brute.committedMeleeMoveContacts() == 1L,
                    "no duplicate receipt");
                helper.succeed();
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "raider_rework_brute_slam_lands_even_if_target_steps_away")
    public void bruteSlamStillLandsWhenTheTargetStepsOutOfReach(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Stompholm");
        RaiderEntity brute = raider(helper, settlement, new BlockPos(6, 1, 8),
            RaiderEntity.Variant.BRUTE);
        face(brute, -90.0F);
        SettlerEntity target = settler(helper, settlement, new BlockPos(7, 1, 8),
            "Runner", Profession.GUARD);
        SettlerEntity bystander = settler(helper, settlement, new BlockPos(8, 1, 9),
            "Watcher", Profession.GUARD);
        brute.setTarget(target);
        RaiderMeleeGoal goal = new RaiderMeleeGoal(brute, 1.0D);
        goal.forceNextMove(RaiderMove.CLUB);
        long contact = 1L + RaiderMove.CLUB.hitTick();
        float start = target.getHealth();
        for (long t = 1L; t <= contact; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                if (tick == 3L) {
                    target.teleportTo(brute.getX() + 7.0D, brute.getY(), brute.getZ());
                }
                face(brute, -90.0F);
                goal.tick();
                if (tick == contact) {
                    helper.assertTrue(target.getHealth() == start
                            && brute.committedMeleeMoveContacts() == 0L,
                        "an escaped main target takes no damage");
                    helper.assertTrue(brute.lastSlamAffected() == 1
                            && bystander.isCombatStaggered(),
                        "the committed club still slams the ground and rocks the bystander");
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 140,
        batch = "raider_rework_skirmisher_retreats_after_jab")
    public void skirmisherHopsBackOutOfReachAfterItsJab(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Pesterholm");
        SettlerEntity victim = settler(helper, settlement, new BlockPos(9, 1, 8),
            "Victim", Profession.NONE);
        RaiderEntity pest = raider(helper, settlement, new BlockPos(5, 1, 8),
            RaiderEntity.Variant.SKIRMISHER);
        pest.setNoAi(false);
        pest.setTarget(victim);
        float start = victim.getHealth();
        long[] jabTick = {-1L};
        float[] afterJab = {Float.NaN};
        helper.onEachTick(() -> {
            long now = helper.getTick();
            RaiderSkirmishGoal goal = pest.skirmishGoal();
            if (jabTick[0] < 0L) {
                if (victim.getHealth() < start) {
                    jabTick[0] = now;
                    afterJab[0] = victim.getHealth();
                    helper.assertTrue(goal.phase() == RaiderSkirmishGoal.Phase.RECOVER,
                        "the jab must hand over to its short, punishable follow-through, was "
                            + goal.phase());
                }
                return;
            }
            helper.assertTrue(victim.getHealth() == afterJab[0],
                "one jab, one damage pass; no follow-up inside the retreat");
            if (now >= jabTick[0] + RaiderSkirmishGoal.JAB_RECOVERY_TICKS
                    + RaiderSkirmishGoal.RETREAT_TICKS) {
                double gap = Math.sqrt(pest.distanceToSqr(victim));
                helper.assertTrue(gap >= 2.8D,
                    "the skirmisher must be out of reach after its retreat, gap=" + gap);
                helper.assertTrue(goal.jabs() == 1 && goal.lastJabLanded(),
                    "exactly one landed jab");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "raider_rework_skirmisher_dodges_guard_heavy")
    public void skirmisherSidestepsAGuardHeavy(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Dodgeholm");
        SettlerEntity guard = settler(helper, settlement, new BlockPos(6, 1, 8),
            "Chopper", Profession.GUARD);
        RaiderEntity pest = raider(helper, settlement, new BlockPos(7, 1, 8),
            RaiderEntity.Variant.SKIRMISHER);
        pest.setNoAi(false);
        pest.setTarget(guard);
        guard.setTarget(pest);
        GuardMeleeGoal guardGoal = new GuardMeleeGoal(guard);
        guardGoal.forceNextMove(GuardMove.HEAVY, false);
        long contact = 1L + GuardMove.HEAVY.hitTick();
        float[] start = {Float.NaN};
        for (long t = 1L; t <= contact + 1L; t++) {
            long tick = t;
            GameTestTicks.at(helper, tick, () -> {
                RaiderSkirmishGoal goal = pest.skirmishGoal();
                if (tick == 1L) {
                    goal.forceDodge(true);
                    start[0] = pest.getHealth();
                }
                guardGoal.tick();
                if (tick == 1L) {
                    helper.assertTrue(guardGoal.pendingMove() == GuardMove.HEAVY,
                        "fixture: the guard must be winding up its heavy on the skirmisher");
                }
                if (tick == contact) {
                    helper.assertTrue(goal.dodges() >= 1 && goal.lastDodgeTick() >= 0L,
                        "the skirmisher must read the heavy wind-up and sidestep");
                    helper.assertTrue(guardGoal.lastResolvedMove() == GuardMove.HEAVY
                            && !guardGoal.lastResolvedLanded(),
                        "the dodged heavy must miss");
                    helper.assertTrue(pest.getHealth() == start[0],
                        "a dodged heavy deals no damage");
                }
                if (tick == contact + 1L) {
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 60,
        batch = "raider_rework_bandit_swings_light_only")
    public void banditUsesOnlyTheLightSwing(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Outlawholm");
        SettlerEntity victim = settler(helper, settlement, new BlockPos(7, 1, 8),
            "Mark", Profession.NONE);
        helper.runAtTickTime(1L, () -> {
            for (int i = 0; i < 6; i++) {
                RaiderEntity bandit = raider(helper, settlement, new BlockPos(6, 1, 8),
                    RaiderEntity.Variant.BANDIT);
                bandit.setTarget(victim);
                RaiderMeleeGoal goal = new RaiderMeleeGoal(bandit, 1.0D);
                goal.tick();
                helper.assertTrue(goal.pendingMove() == RaiderMove.LIGHT,
                    "a bandit only ever swings the light, got " + goal.pendingMove());
                bandit.discard();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "raider_rework_broken_bandit_band_flees_captain_stands")
    public void brokenBanditBandFleesButItsCaptainStands(GameTestHelper helper) {
        Settlement settlement = arena(helper, "Routholm");
        SettlerEntity victim = settler(helper, settlement, new BlockPos(8, 1, 8),
            "Bait", Profession.NONE);
        RaiderEntity bandit = raider(helper, settlement, new BlockPos(9, 1, 8),
            RaiderEntity.Variant.BANDIT);
        RaiderEntity captain = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 9));
        captain.setVariant(RaiderEntity.Variant.BANDIT);
        captain.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD, 1.0F, true);
        Vec3 centre = Vec3.atCenterOf(settlement.center);
        double[] startOut = {Double.NaN};
        helper.runAtTickTime(1L, () -> {
            bandit.setNoAi(false);
            bandit.setTarget(victim);
            captain.setTarget(victim);
            bandit.moraleGoal().forceBandBroken(true);
            captain.moraleGoal().forceBandBroken(true);
            startOut[0] = horizontal(bandit.position().subtract(centre));
        });
        helper.runAtTickTime(45L, () -> {
            helper.assertTrue(bandit.moraleGoal().isFleeing() && bandit.getTarget() == null,
                "an ordinary bandit breaks and stops fighting once the band is broken");
            double out = horizontal(bandit.position().subtract(centre));
            helper.assertTrue(out >= startOut[0] + 2.5D,
                "the broken bandit runs away from the settlement centre: "
                    + startOut[0] + " -> " + out);
            helper.assertTrue(!captain.moraleGoal().isFleeing(),
                "a bandit captain never flees");
            helper.succeed();
        });
    }
}

package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.role.HealerMedicGoal;
import com.hearthstead.entity.combat.role.LongswordCombatGoal;
import com.hearthstead.entity.combat.role.RoleCombat;
import com.hearthstead.entity.combat.role.RoleHiring;
import com.hearthstead.entity.combat.role.RoleMove;
import com.hearthstead.entity.combat.role.RoleWorld;
import com.hearthstead.entity.combat.role.RuneMageGoal;
import com.hearthstead.entity.combat.role.RuneSpell;
import com.hearthstead.entity.combat.role.SpearmanCombatGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.RoleItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Battle roles (plan/BATTLE-ROLES.md): one GameTest per core promise.
 * Settlers and raiders are no-AI dummies so each test drives exactly one
 * goal, deterministically, the same pattern as GuardMovesetGameTests.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BattleRoleGameTests {

    private static Settlement arena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        RoleCombat.overrideEnabledForTests(null);
        // Own clock and a clean floor: no stray mob from an earlier batch may
        // stand in a rune or a blast, and night spawning cannot interfere.
        helper.getLevel().setDayTime(6000L);
        for (net.minecraft.world.entity.Mob stray : helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.Mob.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(0, 0, 0)
                    .expandTowards(16, 6, 16))) {
            stray.discard();
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Roleholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s, String name,
                                         Profession profession, BlockPos at) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), at);
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        settler.assignProfession(profession);
        settler.attributes().pinForTest(Attribute.STRENGTH, 0);
        settler.attributes().pinForTest(Attribute.WITS, 0);
        settler.setHunger(40.0F);
        settler.setNoAi(true);
        return settler;
    }

    private static RaiderEntity raider(GameTestHelper helper, Settlement s,
                                       RaiderEntity.Variant variant, BlockPos at) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), at);
        raider.setVariant(variant);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        raider.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        raider.setHealth(200.0F);
        raider.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        raider.setNoAi(true);
        return raider;
    }

    // ------------------------------------------------------------- spear

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "battle_roles_spear_brace")
    public void spearBraceStaggersACharger(GameTestHelper helper) {
        Settlement s = arena(helper);
        SettlerEntity spear = settler(helper, s, "Pike", Profession.SPEARMAN, new BlockPos(6, 1, 8));
        spear.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(RoleItems.IRON_SPEAR.get()));
        RaiderEntity brute = raider(helper, s, RaiderEntity.Variant.BRUTE, new BlockPos(8, 1, 8));
        spear.setTarget(brute);
        SpearmanCombatGoal goal = new SpearmanCombatGoal(spear);
        int hit = RoleMove.SPEAR_BRACE_STRIKE.hitTick();
        for (long t = 1L; t <= 3L + hit; t++) {
            long tick = t;
            at(helper, tick, () -> {
                long now = helper.getLevel().getGameTime();
                if (tick == 1L) {
                    goal.forceBrace(now, 100);
                }
                // The brute is mid-charge: closing on the spearman at 0.2 b/t.
                brute.setDeltaMovement(-0.2D, 0.0D, 0.0D);
                goal.tick();
                if (tick == 1L) {
                    helper.assertTrue(goal.pendingMove() == RoleMove.SPEAR_BRACE_STRIKE,
                        "a braced spearman must meet a charging brute with the brace strike, got "
                            + goal.pendingMove());
                }
                if (tick == 3L + hit) {
                    helper.assertTrue(goal.braceStrikes() == 1,
                        "exactly one brace strike must land, got " + goal.braceStrikes());
                    helper.assertTrue(brute.isStaggered(), "the brace strike staggers the charger");
                    helper.assertTrue(brute.getHealth() < 200.0F, "the charger took the blow");
                    helper.succeed();
                }
            });
        }
    }

    // --------------------------------------------------------- longsword

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "battle_roles_longsword_cleave")
    public void longswordCleaveHitsTwoTargetsOnceEach(GameTestHelper helper) {
        Settlement s = arena(helper);
        SettlerEntity sword = settler(helper, s, "Edda", Profession.LONGSWORDSMAN, new BlockPos(6, 1, 8));
        sword.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(RoleItems.IRON_LONGSWORD.get()));
        RaiderEntity a = raider(helper, s, RaiderEntity.Variant.SKIRMISHER, new BlockPos(7, 1, 8));
        RaiderEntity b = raider(helper, s, RaiderEntity.Variant.SKIRMISHER, new BlockPos(7, 1, 9));
        sword.setTarget(a);
        LongswordCombatGoal goal = new LongswordCombatGoal(sword);
        goal.forceNextMove(RoleMove.LONGSWORD_CLEAVE);
        int hit = RoleMove.LONGSWORD_CLEAVE.hitTick();
        float[] after = new float[2];
        for (long t = 1L; t <= 12L + hit; t++) {
            long tick = t;
            at(helper, tick, () -> {
                goal.tick();
                if (tick == 1L) {
                    helper.assertTrue(goal.pendingMove() == RoleMove.LONGSWORD_CLEAVE,
                        "the forced cleave must begin at tick 1");
                }
                if (tick < 1L + hit) {
                    helper.assertTrue(a.getHealth() == 200.0F && b.getHealth() == 200.0F,
                        "no damage during the wind-up (tick " + tick + ")");
                } else if (tick == 1L + hit) {
                    helper.assertTrue(goal.hitsOn(a.getUUID()) == 1 && goal.hitsOn(b.getUUID()) == 1,
                        "the cleave must hit both raiders exactly once, got "
                            + goal.hitsOn(a.getUUID()) + "/" + goal.hitsOn(b.getUUID()));
                    helper.assertTrue(a.getHealth() < 200.0F && b.getHealth() < 200.0F,
                        "both raiders took damage");
                    helper.assertTrue(b.getHealth() > a.getHealth(),
                        "the second target takes the secondary share");
                    after[0] = a.getHealth();
                    after[1] = b.getHealth();
                    // Nothing else may start: freeze the next opener.
                    sword.setTarget(null);
                } else if (tick == 12L + hit) {
                    helper.assertTrue(a.getHealth() == after[0] && b.getHealth() == after[1],
                        "one cleave, one damage pass per target");
                    helper.assertTrue(goal.landedContacts() == 2, "two contacts total");
                    helper.succeed();
                }
            });
        }
    }

    // ------------------------------------------------------------- healer

    @GameTest(template = "empty16", timeoutTicks = 400, batch = "battle_roles_healer")
    public void healerHealsAWoundedGuard(GameTestHelper helper) {
        Settlement s = arena(helper);
        SettlerEntity healer = settler(helper, s, "Mend", Profession.HEALER, new BlockPos(6, 1, 8));
        healer.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(RoleItems.BANDAGE.get(), 4));
        SettlerEntity guard = settler(helper, s, "Hurt", Profession.GUARD, new BlockPos(7, 1, 8));
        guard.setHealth(8.0F);
        HealerMedicGoal goal = new HealerMedicGoal(healer);
        float[] start = {8.0F};
        at(helper, 1L, () -> helper.assertTrue(goal.canUse(),
            "a healer with bandages next to a guard at a third of their health must start"));
        for (long t = 2L; t <= 60L; t++) {
            at(helper, t, () -> {
                if (goal.canContinueToUse()) {
                    goal.tick();
                }
            });
        }
        at(helper, 61L, () -> {
            helper.assertTrue(goal.bandagesApplied() == 1,
                "one bandage after the 1.5 s channel, got " + goal.bandagesApplied());
            helper.assertTrue(healer.getOffhandItem().getCount() == 3,
                "exactly one bandage spent, left " + healer.getOffhandItem().getCount());
        });
        helper.succeedWhen(() -> helper.assertTrue(guard.getHealth() >= start[0] + 7.9F,
            "the bandage heals 8 over 8 s; guard at " + guard.getHealth()));
    }

    // --------------------------------------------------------------- mage

    private static RuneMageGoal mage(GameTestHelper helper, Settlement s) {
        SettlerEntity mage = settler(helper, s, "Runa", Profession.RUNE_MAGE, new BlockPos(3, 1, 8));
        mage.getPersistentData().remove(RuneMageGoal.CHARGES_KEY);
        return new RuneMageGoal(mage);
    }

    /**
     * Schedules {@code r} at test tick {@code t} counted from when the test
     * function runs. runAtTickTime is absolute from the batch start, and a
     * slow batch spawn starts the function at tick k > 1: every entry at
     * t <= k then fires together in that first tick, in hash-map order
     * (W8a mage_frost: the forced cast began late and the last scheduled
     * goal tick came before the release, "released once (interrupted 0)").
     */
    private static void at(GameTestHelper helper, long t, Runnable r) {
        GameTestTicks.at(helper, t, r);
    }

    private static void castAndRun(GameTestHelper helper, RuneMageGoal goal, RuneSpell spell,
                                   Vec3 aim, long from, long to) {
        // One runnable per tick: the cast is forced, then ticked, in that order.
        at(helper, from, () -> {
            goal.forceCast(spell, aim);
            goal.tick();
        });
        for (long t = from + 1; t <= to; t++) {
            at(helper, t, goal::tick);
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "battle_roles_mage_ward")
    public void mageWardAbsorbsThenExpires(GameTestHelper helper) {
        Settlement s = arena(helper);
        RuneMageGoal goal = mage(helper, s);
        SettlerEntity guard = settler(helper, s, "Shield", Profession.GUARD, new BlockPos(5, 1, 8));
        long release = 1L + RuneSpell.WARD.castTicks();
        castAndRun(helper, goal, RuneSpell.WARD, Vec3.ZERO, 1L, release + 1);
        at(helper, release + 2, () -> {
            helper.assertTrue(goal.released(RuneSpell.WARD) == 1, "the ward was released once");
            helper.assertTrue(guard.getAbsorptionAmount() == RuneSpell.WARD_ABSORB,
                "the ally carries the rune shield, absorption " + guard.getAbsorptionAmount());
            float health = guard.getHealth();
            guard.hurt(helper.getLevel().damageSources().generic(), 4.0F);
            helper.assertTrue(guard.getHealth() == health, "the ward took the 4 damage");
            helper.assertTrue(Math.abs(guard.getAbsorptionAmount() - 2.0F) < 1.0E-3F,
                "2 of the ward is left, got " + guard.getAbsorptionAmount());
        });
        at(helper, release + RuneSpell.WARD_DURATION_TICKS + 5, () -> {
            helper.assertTrue(guard.getAbsorptionAmount() == 0.0F,
                "an expired ward leaves nothing behind, got " + guard.getAbsorptionAmount());
            helper.assertTrue(guard.getAttribute(Attributes.MAX_ABSORPTION).getValue() == 0.0D,
                "the ward's absorption capacity is removed too");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "battle_roles_mage_frost")
    public void mageFrostRuneSlowsOnlyEnemies(GameTestHelper helper) {
        Settlement s = arena(helper);
        RuneMageGoal goal = mage(helper, s);
        RaiderEntity raider = raider(helper, s, RaiderEntity.Variant.SKIRMISHER, new BlockPos(10, 1, 8));
        SettlerEntity guard = settler(helper, s, "Line", Profession.GUARD, new BlockPos(11, 1, 8));
        long release = 1L + RuneSpell.FROST_RUNE.castTicks();
        castAndRun(helper, goal, RuneSpell.FROST_RUNE, raider.position(), 1L, release + 6);
        at(helper, release + 7, () -> {
            helper.assertTrue(goal.released(RuneSpell.FROST_RUNE) == 1,
                "the frost rune was released once (released " + goal.released(RuneSpell.FROST_RUNE)
                    + ", interrupted " + goal.interrupted() + ", still channelling "
                    + goal.channelling() + ", charges " + goal.charges().charges() + ")");
            MobEffectInstance slow = raider.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
            helper.assertTrue(slow != null && slow.getAmplifier() >= RuneSpell.FROST_SLOW_AMPLIFIER,
                "the raider inside the frost rune has Slowness II");
            helper.assertTrue(!guard.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "an ally standing in the rune is never slowed");
        });
        at(helper, release + 25, () -> {
            helper.assertTrue(raider.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "the rune keeps the raider slowed while it lasts");
            helper.assertTrue(!guard.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "an ally standing in the rune is never slowed");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "battle_roles_mage_firebolt")
    public void mageFireboltDamagesOnlyEnemies(GameTestHelper helper) {
        Settlement s = arena(helper);
        RuneMageGoal goal = mage(helper, s);
        RaiderEntity raider = raider(helper, s, RaiderEntity.Variant.SKIRMISHER, new BlockPos(10, 1, 8));
        SettlerEntity guard = settler(helper, s, "Close", Profession.GUARD, new BlockPos(11, 1, 8));
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(10, 1, 9));
        pig.setNoAi(true);
        float guardStart = guard.getHealth();
        float pigStart = pig.getHealth();
        long release = 1L + RuneSpell.FIREBOLT.castTicks();
        castAndRun(helper, goal, RuneSpell.FIREBOLT, raider.position(), 1L, release + 1);
        at(helper, release + 3, () -> {
            helper.assertTrue(goal.released(RuneSpell.FIREBOLT) == 1, "the firebolt was released");
            helper.assertTrue(raider.getHealth() < 200.0F, "the raider took the blast");
            helper.assertTrue(guard.getHealth() == guardStart && !guard.isOnFire(),
                "the ally in the blast is untouched");
            helper.assertTrue(pig.getHealth() == pigStart && !pig.isOnFire(),
                "a bystander animal is untouched");
            helper.succeed();
        });
    }

    // ------------------------------------------------ supplies on death

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "battle_roles_death_supplies")
    public void fallenHealerDropsCarriedBandages(GameTestHelper helper) {
        Settlement s = arena(helper);
        SettlerEntity healer = settler(helper, s, "Fallen", Profession.HEALER, new BlockPos(8, 1, 8));
        healer.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(RoleItems.BANDAGE.get(), 5));
        at(helper, 2L, () -> healer.hurt(
            helper.getLevel().damageSources().generic(), 1000.0F));
        helper.succeedWhen(() -> {
            int found = 0;
            for (net.minecraft.world.entity.item.ItemEntity item : helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO)).inflate(20.0D))) {
                if (item.getItem().is(RoleItems.BANDAGE.get())) {
                    found += item.getItem().getCount();
                }
            }
            helper.assertTrue(!healer.isAlive(), "the healer must have died");
            helper.assertTrue(found == 5, "all 5 carried bandages must survive the death, found " + found);
        });
    }

    // -------------------------------------------------------- kill-switch

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "battle_roles_kill_switch")
    public void battleRolesDisabledMeansNoRoleGoals(GameTestHelper helper) {
        Settlement s = arena(helper);
        SettlerEntity spear = settler(helper, s, "Off", Profession.SPEARMAN, new BlockPos(6, 1, 8));
        spear.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(RoleItems.IRON_SPEAR.get()));
        RaiderEntity raider = raider(helper, s, RaiderEntity.Variant.SKIRMISHER, new BlockPos(7, 1, 8));
        spear.setTarget(raider);
        SettlerEntity mage = settler(helper, s, "Quiet", Profession.RUNE_MAGE, new BlockPos(3, 1, 8));
        SettlerEntity healer = settler(helper, s, "Still", Profession.HEALER, new BlockPos(4, 1, 8));
        healer.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(RoleItems.BANDAGE.get(), 4));
        SettlerEntity hurt = settler(helper, s, "Hurt", Profession.GUARD, new BlockPos(5, 1, 8));
        hurt.setHealth(6.0F);
        at(helper, 1L, () -> {
            try {
                helper.assertTrue(new SpearmanCombatGoal(spear).canUse(),
                    "control: with roles on, the spearman engages");
                RoleCombat.overrideEnabledForTests(false);
                helper.assertTrue(!new SpearmanCombatGoal(spear).canUse(),
                    "roles off: no spear goal");
                helper.assertTrue(!new LongswordCombatGoal(spear).canUse(),
                    "roles off: no longsword goal");
                helper.assertTrue(!new RuneMageGoal(mage).canUse(), "roles off: no mage goal");
                helper.assertTrue(!new HealerMedicGoal(healer).canUse(), "roles off: no healer goal");
                helper.assertTrue(RoleHiring.refusal(s, BuildingType.PIKE_YARD, spear) != null,
                    "roles off: role halls refuse to hire");
                helper.assertTrue(RoleHiring.refusal(s, BuildingType.BARRACKS, spear) == null,
                    "roles off: the Barracks still hires Guards");
            } finally {
                RoleCombat.overrideEnabledForTests(null);
            }
            helper.succeed();
        });
    }
}

package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardExperience;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.GuardExperienceEvents;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/** Deterministic server-path coverage for defender kill experience. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardExperienceGameTests {

    private static void arena(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static SettlerEntity defender(GameTestHelper helper, Profession profession,
                                           int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setNoAi(true);
        settler.assignProfession(profession);
        if (profession == Profession.GUARD) {
            settler.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(Items.IRON_SWORD));
        } else if (profession == Profession.ARCHER) {
            settler.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(Items.BOW));
        }
        return settler;
    }

    private static RaiderEntity raider(GameTestHelper helper, int x, int z) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(x, 1, z));
        raider.setNoAi(true);
        return raider;
    }

    private static void kill(GameTestHelper helper, SettlerEntity killer,
                             net.minecraft.world.entity.LivingEntity victim) {
        kill(helper, helper.getLevel().damageSources().mobAttack(killer), victim);
    }

    private static void kill(GameTestHelper helper, DamageSource source,
                             net.minecraft.world.entity.LivingEntity victim) {
        victim.setHealth(1.0F);
        boolean landed = victim.hurt(source, 20.0F);
        helper.assertTrue(landed && victim.isDeadOrDying(),
            "fixture sanity: the authored killing blow must actually kill the victim");
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void guardKillAwardsOnceAndTrainsStrength(GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = defender(helper, Profession.GUARD, 3, 3);
        guard.attributes().pinForTest(Attribute.STRENGTH, 0);
        RaiderEntity raider = raider(helper, 5, 3);
        DamageSource source = helper.getLevel().damageSources().mobAttack(guard);

        kill(helper, guard, raider);
        helper.assertTrue(guard.combatExperience() == GuardExperience.RAIDER_KILL_XP,
            "one ordinary raider must award exactly "
                + GuardExperience.RAIDER_KILL_XP + " XP, got "
                + guard.combatExperience());
        helper.assertTrue(guard.attribute(Attribute.STRENGTH) > 0,
            "a credited guard kill must also train the existing Strength rank");
        helper.assertTrue(raider.getPersistentData().hasUUID(
                GuardExperienceEvents.KILL_SOURCE_ID_TAG),
            "the death must own a persisted award-ledger identity");
        UUID sourceId = raider.getPersistentData().getUUID(
            GuardExperienceEvents.KILL_SOURCE_ID_TAG);
        helper.assertTrue(!sourceId.equals(raider.getUUID())
                && guard.hasCombatExperienceSource(sourceId)
                && guard.committedCombatExperienceAwards() == 1L,
            "XP authority must be the random persisted receipt, not victim UUID");

        CompoundTag savedGuard = new CompoundTag();
        guard.addAdditionalSaveData(savedGuard);
        SettlerEntity loadedGuard = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(loadedGuard != null,
            "fixture sanity: settler type must recreate for ledger reload");
        loadedGuard.readAdditionalSaveData(savedGuard);
        helper.assertTrue(loadedGuard.hasCombatExperienceSource(sourceId)
                && loadedGuard.committedCombatExperienceAwards() == 1L,
            "the exact XP receipt must remain consumed after restart");

        int afterFirst = guard.combatExperience();
        helper.assertFalse(GuardExperienceEvents.tryAward(raider, source),
            "replaying the same death must be rejected by the victim marker");
        helper.assertTrue(guard.combatExperience() == afterFirst,
            "duplicate delivery must not add XP twice");
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void archerKillAwardsOnceAndTrainsDexterity(GameTestHelper helper) {
        arena(helper);
        SettlerEntity archer = defender(helper, Profession.ARCHER, 3, 3);
        archer.attributes().pinForTest(Attribute.DEXTERITY, 0);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(5, 1, 3));
        zombie.setNoAi(true);
        Arrow arrow = new Arrow(helper.getLevel(), archer,
            new ItemStack(Items.ARROW), null);
        DamageSource projectile = helper.getLevel().damageSources().arrow(arrow, archer);
        helper.assertTrue(projectile.getDirectEntity() == arrow
                && projectile.getEntity() == archer,
            "fixture sanity: projectile credit must resolve the owning archer");

        kill(helper, projectile, zombie);
        helper.assertTrue(archer.combatExperience() == GuardExperience.HOSTILE_KILL_XP,
            "one vanilla hostile must award the ordinary hostile value");
        float dexterityProgress = archer.attributes()
            .trainingProgress(Attribute.DEXTERITY);
        helper.assertTrue(archer.attribute(Attribute.DEXTERITY) > 0
                || dexterityProgress > 0.0F,
            "a credited archer kill must train Dexterity, including honest "
                + "sub-point progress; progress=" + dexterityProgress);
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void friendlyEnvironmentalAndCivilianDeathsAwardNothing(GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = defender(helper, Profession.GUARD, 2, 2);

        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(4, 1, 2));
        pig.setNoAi(true);
        kill(helper, guard, pig);
        helper.assertTrue(guard.combatExperience() == 0,
            "a friendly animal is never valid guard kill credit");

        RaiderEntity environmental = raider(helper, 6, 2);
        environmental.setHealth(1.0F);
        environmental.hurt(helper.getLevel().damageSources().generic(), 20.0F);
        helper.assertTrue(guard.combatExperience() == 0,
            "environmental death without a credited defender must award nothing");

        SettlerEntity civilian = defender(helper, Profession.NONE, 2, 5);
        RaiderEntity civilianVictim = raider(helper, 4, 5);
        kill(helper, civilian, civilianVictim);
        helper.assertTrue(civilian.combatExperience() == 0,
            "a non-defender settler cannot farm guard experience");
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void lateCancelledDeathAwardsNoXpReceiptOrCue(GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = defender(helper, Profession.GUARD, 3, 3);
        guard.attributes().pinForTest(Attribute.STRENGTH, 0);
        RaiderEntity raider = raider(helper, 5, 3);

        Consumer<LivingDeathEvent> cancelLate = event -> {
            if (event.getEntity() == raider) {
                event.getEntity().setHealth(1.0F);
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
            LivingDeathEvent.class, cancelLate);
        boolean landed;
        try {
            raider.setHealth(1.0F);
            landed = raider.hurt(
                helper.getLevel().damageSources().mobAttack(guard), 20.0F);
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelLate);
        }

        helper.assertTrue(landed && raider.isAlive() && !raider.isRemoved(),
            "fixture sanity: a late compatibility listener must cancel the death");
        helper.assertTrue(guard.combatExperience() == 0
                && guard.committedCombatExperienceAwards() == 0L
                && guard.attribute(Attribute.STRENGTH) == 0,
            "a cancelled death must commit no XP, receipt, or training/cue gate");
        helper.assertTrue(!raider.getPersistentData().contains(
                GuardExperienceEvents.KILL_SOURCE_ID_TAG)
                && !raider.getPersistentData().contains(
                    GuardExperienceEvents.KILL_CREDIT_TAG),
            "a cancelled death must leave no victim-side award evidence");
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void cancelledDropsStillAwardOneAcceptedDeath(GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = defender(helper, Profession.GUARD, 3, 3);
        RaiderEntity raider = raider(helper, 5, 3);

        Consumer<LivingDropsEvent> cancelDrops = event -> {
            if (event.getEntity() == raider) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true,
            LivingDropsEvent.class, cancelDrops);
        try {
            kill(helper, guard, raider);
        } finally {
            NeoForge.EVENT_BUS.unregister(cancelDrops);
        }

        helper.assertTrue(guard.combatExperience()
                == GuardExperience.RAIDER_KILL_XP
                && guard.committedCombatExperienceAwards() == 1L,
            "loot cancellation changes drops, not accepted death credit");
        helper.assertTrue(raider.getPersistentData().getBoolean(
                GuardExperienceEvents.KILL_CREDIT_TAG),
            "the accepted death must still own exactly one terminal marker");
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void experiencePersistsAndMalformedValuesClamp(GameTestHelper helper) {
        arena(helper);
        SettlerEntity original = defender(helper, Profession.GUARD, 3, 3);
        original.awardCombatExperience(GuardExperience.Tier.VETERAN.threshold());

        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        SettlerEntity loaded = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(loaded != null, "fixture sanity: settler type must create");
        loaded.readAdditionalSaveData(saved);
        helper.assertTrue(loaded.combatExperience()
                == GuardExperience.Tier.VETERAN.threshold(),
            "combat experience must survive the entity NBT round trip");

        saved.putInt(SettlerEntity.COMBAT_EXPERIENCE_NBT_KEY, Integer.MAX_VALUE);
        loaded.readAdditionalSaveData(saved);
        helper.assertTrue(loaded.combatExperience() == GuardExperience.MAX_EXPERIENCE,
            "malformed oversized NBT must saturate at the owned maximum");
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void wrongTypeCombatLedgerQuarantinesInsteadOfLegacyReset(
            GameTestHelper helper) {
        arena(helper);
        SettlerEntity original = defender(helper, Profession.GUARD, 3, 3);
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        saved.putString(SettlerEntity.COMBAT_LEDGER_NBT_KEY,
            "present_but_wrong_type");

        SettlerEntity corrupted = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(corrupted != null,
            "fixture sanity: settler type must recreate for corrupt load");
        corrupted.readAdditionalSaveData(saved);
        helper.assertTrue(corrupted.commitCombatExperienceAward(
                UUID.randomUUID(), GuardExperience.HOSTILE_KILL_XP) == null
                && corrupted.combatExperience() == 0
                && corrupted.committedCombatExperienceAwards() == 0L,
            "a present wrong-type ledger must quarantine and reject authority");
        CompoundTag rewritten = new CompoundTag();
        corrupted.addAdditionalSaveData(rewritten);
        helper.assertTrue(rewritten.get(SettlerEntity.COMBAT_LEDGER_NBT_KEY)
                instanceof CompoundTag quarantined
                && quarantined.getBoolean("Quarantined"),
            "the quarantine marker must survive the entity's next save");

        saved.remove(SettlerEntity.COMBAT_LEDGER_NBT_KEY);
        SettlerEntity legacy = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(legacy != null,
            "fixture sanity: settler type must recreate for legacy load");
        legacy.readAdditionalSaveData(saved);
        helper.assertTrue(legacy.commitCombatExperienceAward(
                UUID.randomUUID(), GuardExperience.HOSTILE_KILL_XP) != null,
            "genuine field absence remains the only empty-ledger migration");
        helper.succeed();
    }

    @GameTest(batch = "guard_experience", template = "empty16", timeoutTicks = 100)
    public void cappedDefenderGetsNoFalseGainSideEffects(GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = defender(helper, Profession.GUARD, 3, 3);
        guard.awardCombatExperience(GuardExperience.MAX_EXPERIENCE);
        guard.attributes().pinForTest(Attribute.STRENGTH, 0);
        RaiderEntity raider = raider(helper, 5, 3);
        DamageSource source = helper.getLevel().damageSources().mobAttack(guard);

        kill(helper, source, raider);
        helper.assertTrue(guard.combatExperience() == GuardExperience.MAX_EXPERIENCE,
            "the cap must remain saturated after another valid kill");
        helper.assertTrue(guard.attribute(Attribute.STRENGTH) == 0,
            "a capped kill gained no XP and must not fire hidden rank training");
        helper.assertFalse(GuardExperienceEvents.tryAward(raider, source),
            "the capped death is still consumed once and deduplicated");
        helper.succeed();
    }
}

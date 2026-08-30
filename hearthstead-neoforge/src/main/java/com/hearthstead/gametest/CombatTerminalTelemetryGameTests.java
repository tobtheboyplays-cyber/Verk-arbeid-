package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.OwnedProjectileLedger;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native-server scenarios for final combat-event observation. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class CombatTerminalTelemetryGameTests {

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

    private static SettlerEntity settler(GameTestHelper helper,
                                          Profession profession,
                                          BlockPos pos) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), pos);
        settler.setNoAi(true);
        settler.assignProfession(profession);
        return settler;
    }

    private static Zombie zombie(GameTestHelper helper, BlockPos pos) {
        Zombie zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
            pos);
        zombie.setNoAi(true);
        return zombie;
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "combat_terminal_true_and_false_shield")
    public void genuineShieldBlockCommitsButOrdinaryHurtDoesNot(
            GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = settler(helper, Profession.GUARD,
            new BlockPos(5, 1, 5));
        Zombie attacker = zombie(helper, new BlockPos(5, 1, 3));
        guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        // Minecraft yaw 180 faces north, directly toward the attacker.
        guard.setYRot(180.0F);
        guard.setYHeadRot(180.0F);
        guard.yBodyRot = 180.0F;
        guard.startUsingItem(InteractionHand.OFF_HAND);

        helper.runAfterDelay(6, () -> {
            helper.assertTrue(guard.isBlocking(),
                "fixture must reach vanilla's five-tick genuine shield gate");
            float healthBefore = guard.getHealth();
            boolean healthDamage = guard.hurt(
                helper.getLevel().damageSources().mobAttack(attacker), 4.0F);
            helper.assertFalse(healthDamage,
                "a full vanilla shield block must prevent health damage");
            helper.assertTrue(guard.getHealth() == healthBefore,
                "blocked damage must not leak into guard health");
            helper.assertTrue(guard.committedShieldBlocks() == 1L,
                "immutable blockedDamage>0 must commit one shield terminal");
            helper.assertTrue(guard.shieldBlockPresentationSequence() == 1L,
                "the committed full block must emit exactly one custom cue");
            helper.assertTrue(guard.getOffhandItem().getDamageValue() == 1,
                "one terminal must spend exactly one shield durability attempt");

            // Vanilla performs shield mechanics before its post-attack
            // invulnerability early return. Exercise a full old-queue cap of
            // such rejected contacts: none has a LivingDamageEvent.Post, so
            // none may consume replay identity or strand pending state.
            for (int rejected = 0; rejected < 64; rejected++) {
                helper.assertFalse(guard.hurt(
                    helper.getLevel().damageSources().mobAttack(attacker), 3.0F),
                    "an in-frame repeat must be rejected before the terminal");
            }
            helper.assertTrue(guard.committedShieldBlocks() == 1L,
                "in-frame rejections must not forge or queue block commits");
            helper.assertTrue(guard.shieldBlockPresentationSequence() == 1L,
                "in-frame rejections must not forge shield presentation");
            helper.assertTrue(guard.getOffhandItem().getDamageValue() == 1,
                "in-frame rejections must not spend shield serviceability");

            // Two distinct genuine sequences can share guard, attacker, tick
            // and damage amount. Each immutable Post object must retain its
            // own identity rather than collapsing under a derived hash.
            guard.invulnerableTime = 0;
            helper.assertFalse(guard.hurt(
                helper.getLevel().damageSources().mobAttack(attacker), 4.0F),
                "second full vanilla block must also prevent health damage");
            helper.assertTrue(guard.committedShieldBlocks() == 2L,
                "a second real same-tick block needs its own terminal identity");
            helper.assertTrue(guard.shieldBlockPresentationSequence() == 2L,
                "each distinct committed full block needs exactly one cue");
            helper.assertTrue(guard.getOffhandItem().getDamageValue() == 2,
                "the second committed terminal must spend one more attempt");

            guard.stopUsingItem();
            guard.invulnerableTime = 0;
            boolean ordinaryHurt = guard.hurt(
                helper.getLevel().damageSources().mobAttack(attacker), 4.0F);
            helper.assertTrue(ordinaryHurt && guard.getHealth() < healthBefore,
                "fixture must also execute one real non-blocked hurt");
            helper.assertTrue(guard.committedShieldBlocks() == 2L,
                "ordinary hurt/animation must not forge shield telemetry");
            helper.assertTrue(guard.shieldBlockPresentationSequence() == 2L,
                "ordinary health damage must never impersonate a shield cue");
            helper.assertTrue(guard.getOffhandItem().getDamageValue() == 2,
                "ordinary health damage must not wear an idle shield");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "combat_terminal_shield_break")
    public void committedShieldWearEmitsOneBreakAndCannotReplay(
            GameTestHelper helper) {
        arena(helper);
        SettlerEntity guard = settler(helper, Profession.GUARD,
            new BlockPos(5, 1, 5));
        Zombie attacker = zombie(helper, new BlockPos(5, 1, 3));
        ItemStack fragileShield = new ItemStack(Items.SHIELD);
        fragileShield.setDamageValue(fragileShield.getMaxDamage() - 1);
        guard.setItemSlot(EquipmentSlot.OFFHAND, fragileShield);
        guard.setYRot(180.0F);
        guard.setYHeadRot(180.0F);
        guard.yBodyRot = 180.0F;
        guard.startUsingItem(InteractionHand.OFF_HAND);

        helper.runAfterDelay(6, () -> {
            float healthBefore = guard.getHealth();
            helper.assertFalse(guard.hurt(
                helper.getLevel().damageSources().mobAttack(attacker), 4.0F),
                "the breaking blow must still be a complete physical block");
            helper.assertTrue(guard.getHealth() == healthBefore
                    && guard.committedShieldBlocks() == 1L,
                "the breaking block must retain one genuine terminal");
            helper.assertTrue(guard.getOffhandItem().isEmpty(),
                "terminal wear must physically consume the exhausted shield");
            helper.assertTrue(guard.shieldBreakEventSequence() == 1L,
                "the equipped OFFHAND break callback must emit exactly once");
            helper.assertFalse(guard.isUsingItem() || guard.isBlocking(),
                "production break cleanup must prevent ghost blocking");

            guard.invulnerableTime = 0;
            helper.assertTrue(guard.hurt(
                helper.getLevel().damageSources().mobAttack(attacker), 2.0F),
                "without the broken shield the next contact must hurt normally");
            helper.assertTrue(guard.committedShieldBlocks() == 1L
                    && guard.shieldBreakEventSequence() == 1L,
                "ordinary follow-up damage must not replay commit or break");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "combat_terminal_owned_projectile_replay_restart")
    public void ownedProjectileNeedsSuccessfulHurtAndRetainsReplayState(
            GameTestHelper helper) {
        arena(helper);
        SettlerEntity archer = settler(helper, Profession.ARCHER,
            new BlockPos(3, 1, 5));
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        archer.attributes().pinForTest(Attribute.DEXTERITY, 0);
        Zombie firstVictim = zombie(helper, new BlockPos(6, 1, 5));
        Zombie secondVictim = zombie(helper, new BlockPos(8, 1, 5));
        Zombie thirdVictim = zombie(helper, new BlockPos(8, 1, 7));
        Zombie fourthVictim = zombie(helper, new BlockPos(6, 1, 7));

        Arrow arrow = new Arrow(helper.getLevel(), archer,
            new ItemStack(Items.ARROW), archer.getMainHandItem());
        helper.assertTrue(OwnedProjectileLedger.issue(arrow, archer),
            "fixture arrow must receive exact server-owned provenance");
        helper.assertTrue(OwnedProjectileLedger.committedCount(arrow) == 0L,
            "release or miss alone is not a terminal contact");
        float trainingBefore = dexterityTrainingStamp(archer);

        firstVictim.setInvulnerable(true);
        boolean immune = firstVictim.hurt(
            helper.getLevel().damageSources().arrow(arrow, archer), 3.0F);
        helper.assertFalse(immune,
            "fixture must exercise a genuinely invulnerable target");
        helper.assertTrue(OwnedProjectileLedger.committedCount(arrow) == 0L,
            "failed hurt must not consume projectile contact authority");
        helper.assertTrue(dexterityTrainingStamp(archer) == trainingBefore,
            "an invulnerable target must not train from an absent terminal");

        firstVictim.setInvulnerable(false);
        firstVictim.invulnerableTime = 0;
        float before = firstVictim.getHealth();
        boolean landed = firstVictim.hurt(
            helper.getLevel().damageSources().arrow(arrow, archer), 3.0F);
        helper.assertTrue(landed && firstVictim.getHealth() < before,
            "owned projectile must execute an actual successful hurt");
        helper.assertTrue(OwnedProjectileLedger.committedCount(arrow) == 1L,
            "first successful arrow/victim pair must commit exactly once");
        float trainingAfterFirst = dexterityTrainingStamp(archer);
        helper.assertTrue(trainingAfterFirst > trainingBefore,
            "the first committed contact must author Dexterity progress");

        // A repeated real hurt by the same projectile/victim is still gameplay
        // damage, but it cannot replay the already-consumed telemetry identity.
        firstVictim.invulnerableTime = 0;
        helper.assertTrue(firstVictim.hurt(
            helper.getLevel().damageSources().arrow(arrow, archer), 1.0F),
            "fixture replay must remain a real successful hurt");
        helper.assertTrue(OwnedProjectileLedger.committedCount(arrow) == 1L,
            "same projectile/victim replay must not commit twice");
        helper.assertTrue(dexterityTrainingStamp(archer) == trainingAfterFirst,
            "a replayed victim receipt must not train Dexterity twice");

        // Exercise the real unload/restart boundary. Entity save writes the
        // registered type, owner UUID and NeoForgeData; recursive load must
        // reconstruct a plain vanilla Arrow with all three intact.
        CompoundTag savedArrow = new CompoundTag();
        helper.assertTrue(arrow.save(savedArrow),
            "fixture arrow must be accepted by the normal entity save path");
        Entity restored = EntityType.loadEntityRecursive(savedArrow,
            helper.getLevel(), entity -> entity);
        helper.assertTrue(restored instanceof Arrow,
            "registered arrow NBT must restore through the vanilla factory");
        Arrow loaded = (Arrow) restored;
        helper.assertTrue(loaded.getClass() == Arrow.class,
            "restart must not depend on an anonymous Java subclass");
        helper.assertTrue(loaded.getOwner() == archer,
            "restored owner UUID must resolve to the authoritative archer");
        helper.assertTrue(OwnedProjectileLedger.committedCount(loaded) == 1L,
            "restart must retain the first consumed victim identity");

        secondVictim.invulnerableTime = 0;
        float secondBefore = secondVictim.getHealth();
        helper.assertTrue(secondVictim.hurt(
                helper.getLevel().damageSources().arrow(loaded, archer), 3.0F)
                && secondVictim.getHealth() < secondBefore,
            "restored owned projectile must still author a new real victim hit");
        helper.assertTrue(OwnedProjectileLedger.committedCount(loaded) == 2L,
            "restart must preserve revision and admit only the new victim");
        float trainingAfterSecond = dexterityTrainingStamp(archer);
        helper.assertTrue(trainingAfterSecond > trainingAfterFirst,
            "a vanilla-restored arrow contact must still train from terminal authority");

        CompoundTag savedAgain = new CompoundTag();
        helper.assertTrue(loaded.save(savedAgain),
            "updated restored arrow must remain saveable after a new commit");
        Entity restoredAgain = EntityType.loadEntityRecursive(savedAgain,
            helper.getLevel(), entity -> entity);
        helper.assertTrue(restoredAgain instanceof Arrow,
            "a second restart must still restore the registered Arrow type");
        Arrow reloaded = (Arrow) restoredAgain;
        helper.assertTrue(reloaded.getClass() == Arrow.class
                && reloaded.getOwner() == archer
                && OwnedProjectileLedger.committedCount(reloaded) == 2L,
            "second restart must retain owner, ledger revision and victim set");

        for (Zombie laterVictim : new Zombie[]{thirdVictim, fourthVictim}) {
            laterVictim.invulnerableTime = 0;
            helper.assertTrue(laterVictim.hurt(
                helper.getLevel().damageSources().arrow(reloaded, archer), 3.0F),
                "each distinct restored-arrow victim must receive real damage");
        }
        helper.assertTrue(OwnedProjectileLedger.committedCount(reloaded) == 4L,
            "restored persistent authority must commit four distinct victims");
        helper.assertTrue(dexterityTrainingStamp(archer) > trainingAfterSecond,
            "later restored contacts must keep training monotonically");
        helper.succeed();
    }

    private static float dexterityTrainingStamp(SettlerEntity archer) {
        return archer.attribute(Attribute.DEXTERITY)
            + archer.attributes().trainingProgress(Attribute.DEXTERITY);
    }

    public CombatTerminalTelemetryGameTests() {
    }
}

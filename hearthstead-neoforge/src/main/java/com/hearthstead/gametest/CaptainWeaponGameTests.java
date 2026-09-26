package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.weapon.CaptainWeaponItem;
import com.hearthstead.item.weapon.WeaponConfig;
import com.hearthstead.item.weapon.WeaponTraits;
import com.hearthstead.item.weapon.WeaponType;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.WeaponItems;
import com.hearthstead.registry.WeaponItems.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Captain weapons (weapons lane, 26 Sep; plan/WEAPONS.md): the attributes a player really gets,
 * the traits, the settler-side damage path and the survival recipes.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CaptainWeaponGameTests {

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** Applies a stack's main-hand modifiers to a mock player, like equipping it for a tick. */
    private static Player wielding(GameTestHelper helper, ItemStack stack) {
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.setItemSlot(EquipmentSlot.MAINHAND, stack);
        stack.forEachModifier(EquipmentSlot.MAINHAND, (Holder<Attribute> attr, net.minecraft.world.entity.ai.attributes.AttributeModifier mod) -> {
            var inst = p.getAttribute(attr);
            if (inst != null && !inst.hasModifier(mod.id())) {
                inst.addTransientModifier(mod);
            }
        });
        return p;
    }

    private static void near(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < 1.0E-4D, what + ": expected " + expected + ", got " + actual);
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "captain_weapons")
    public void attributesApplyPerTypeAndTier(GameTestHelper helper) {
        Player halberd = wielding(helper, new ItemStack(WeaponItems.get(WeaponType.HALBERD, Material.IRON)));
        near(helper, halberd.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), 3.0D + WeaponType.HALBERD_REACH,
            "halberd reach");
        near(helper, halberd.getAttributeValue(Attributes.ATTACK_SPEED), 0.9D, "halberd attack speed");
        near(helper, halberd.getAttributeValue(Attributes.ATTACK_DAMAGE), 8.0D, "iron halberd damage");

        Player sword = wielding(helper, new ItemStack(WeaponItems.get(WeaponType.SHORT_SWORD, Material.IRON)));
        near(helper, sword.getAttributeValue(Attributes.ATTACK_SPEED), 2.0D, "short sword attack speed");
        near(helper, sword.getAttributeValue(Attributes.ATTACK_DAMAGE), 5.0D, "iron short sword damage");
        near(helper, sword.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), 3.0D, "short sword keeps vanilla reach");

        Player hammer = wielding(helper, new ItemStack(WeaponItems.get(WeaponType.WARHAMMER, Material.NETHERITE)));
        near(helper, hammer.getAttributeValue(Attributes.ATTACK_KNOCKBACK), WeaponType.WARHAMMER_KNOCKBACK,
            "warhammer knockback");
        near(helper, hammer.getAttributeValue(Attributes.ATTACK_DAMAGE), 11.0D, "netherite warhammer damage");

        Player axe = wielding(helper, new ItemStack(WeaponItems.get(WeaponType.DOUBLE_AXE, Material.WOODEN)));
        near(helper, axe.getAttributeValue(Attributes.ATTACK_DAMAGE), 8.0D, "wooden double axe damage");
        near(helper, axe.getAttributeValue(Attributes.ATTACK_SPEED), 0.8D, "double axe attack speed");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "captain_weapons")
    public void traitFlagsMatchTheTable(GameTestHelper helper) {
        ItemStack hammer = new ItemStack(WeaponItems.get(WeaponType.WARHAMMER, Material.IRON));
        ItemStack axe = new ItemStack(WeaponItems.get(WeaponType.DOUBLE_AXE, Material.IRON));
        ItemStack halberd = new ItemStack(WeaponItems.get(WeaponType.HALBERD, Material.IRON));
        ItemStack shortSword = new ItemStack(WeaponItems.get(WeaponType.SHORT_SWORD, Material.IRON));
        helper.assertTrue(!hammer.canPerformAction(ItemAbilities.SWORD_SWEEP), "the warhammer does not sweep");
        helper.assertTrue(axe.canPerformAction(ItemAbilities.SWORD_SWEEP), "the double axe sweeps");
        helper.assertTrue(halberd.canPerformAction(ItemAbilities.SWORD_SWEEP), "the halberd sweeps");
        Zombie z = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 1, 4));
        ItemStack shield = new ItemStack(Items.SHIELD);
        helper.assertTrue(hammer.getItem().canDisableShield(hammer, shield, z, z), "the warhammer breaks shields");
        helper.assertTrue(axe.getItem().canDisableShield(axe, shield, z, z), "the double axe breaks shields");
        helper.assertTrue(!halberd.getItem().canDisableShield(halberd, shield, z, z), "the halberd does not");
        helper.assertTrue(WeaponTraits.isTwoHanded(axe) && WeaponTraits.isTwoHanded(halberd)
            && WeaponTraits.isTwoHanded(hammer) && !WeaponTraits.isTwoHanded(shortSword), "two-handed flags");
        helper.assertTrue(shortSword.is(net.minecraft.tags.ItemTags.SWORDS), "short swords are #minecraft:swords");
        helper.assertTrue(axe.is(WeaponItems.CAPTAIN_GREAT_AXES) && halberd.is(WeaponItems.CAPTAIN_HALBERDS)
            && hammer.is(WeaponItems.CAPTAIN_WARHAMMERS) && shortSword.is(WeaponItems.CAPTAIN_DUAL_SWORDS)
            && new ItemStack(Items.BOW).is(WeaponItems.CAPTAIN_BOWS), "captain loadout tags");
        helper.assertTrue(new ItemStack(WeaponItems.get(WeaponType.HALBERD, Material.NETHERITE)).has(
            net.minecraft.core.component.DataComponents.FIRE_RESISTANT), "netherite tier is fire resistant");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "captain_weapons")
    public void doubleAxeShredsArmourAndBrutes(GameTestHelper helper) {
        floor(helper);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        RaiderEntity brute = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(6, 1, 6));
        brute.setVariant(RaiderEntity.Variant.BRUTE);
        brute.setNoAi(true);
        Zombie plain = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
        plain.getAttribute(Attributes.ARMOR).setBaseValue(0.0D);
        Zombie armoured = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(9, 1, 9));
        armoured.getAttribute(Attributes.ARMOR).setBaseValue(10.0D);
        float none = WeaponTraits.bonus(WeaponType.DOUBLE_AXE, p, plain, 10.0F);
        float some = WeaponTraits.bonus(WeaponType.DOUBLE_AXE, p, armoured, 10.0F);
        float vsBrute = WeaponTraits.bonus(WeaponType.DOUBLE_AXE, p, brute, 10.0F);
        helper.assertTrue(none == 0.0F, "no armour, no shred bonus, got " + none);
        near(helper, some, Math.min(WeaponConfig.shredMax(), 10 * WeaponConfig.shredPerArmor()), "shred vs 10 armour");
        helper.assertTrue(vsBrute >= (float) WeaponConfig.bruteBonus(), "Brute bonus, got " + vsBrute);
        helper.assertTrue(WeaponTraits.bonus(WeaponType.SHORT_SWORD, p, armoured, 10.0F) == 0.0F,
            "short swords have no shred");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "captain_weapons")
    public void halberdPunishesACharge(GameTestHelper helper) {
        floor(helper);
        Zombie wielder = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 1, 8));
        Zombie charger = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(8, 1, 8));
        charger.setDeltaMovement(new Vec3(-0.25D, 0.0D, 0.0D));          // running at the wielder
        float charging = WeaponTraits.bonus(WeaponType.HALBERD, wielder, charger, 8.0F);
        near(helper, charging, 8.0F * (WeaponConfig.chargeMultiplier() - 1.0D), "anti-charge bonus");
        charger.setDeltaMovement(new Vec3(0.25D, 0.0D, 0.0D));           // running away
        helper.assertTrue(WeaponTraits.bonus(WeaponType.HALBERD, wielder, charger, 8.0F) == 0.0F,
            "no bonus against a foe running away");
        charger.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(WeaponTraits.bonus(WeaponType.HALBERD, wielder, charger, 8.0F) == 0.0F,
            "no bonus against a foe standing still");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "captain_weapons")
    public void settlerMeleeGetsTheTraitBonus(GameTestHelper helper) {
        floor(helper);
        // two identical armoured targets; one is hit by a settler with a double axe, one by a bare-handed
        // settler with the same raw damage -- only the weapon's armour shred may make the difference
        SettlerEntity captain = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 4));
        captain.setNoAi(true);
        captain.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WeaponItems.get(WeaponType.DOUBLE_AXE, Material.IRON)));
        SettlerEntity bare = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 11));
        bare.setNoAi(true);
        Zombie a = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 1, 4));
        Zombie b = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 1, 11));
        for (Zombie z : new Zombie[] {a, b}) {
            z.getAttribute(Attributes.ARMOR).setBaseValue(10.0D);
            z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
            z.setHealth(200.0F);
        }
        a.hurt(captain.damageSources().mobAttack(captain), 10.0F);
        b.hurt(bare.damageSources().mobAttack(bare), 10.0F);
        float lostA = 200.0F - a.getHealth();
        float lostB = 200.0F - b.getHealth();
        helper.assertTrue(lostB > 0.0F, "the reference hit must land");
        helper.assertTrue(lostA > lostB + 1.0F, "a settler's double-axe hit must shred armour (" + lostA + " vs "
            + lostB + ")");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "captain_weapons")
    public void warhammerStunSlowsHard(GameTestHelper helper) {
        floor(helper);
        Zombie z = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 1, 6));
        helper.assertTrue(!WeaponTraits.rollStun(WeaponType.WARHAMMER, z, 0.99F), "a high roll never stuns");
        helper.assertTrue(!WeaponTraits.rollStun(WeaponType.DOUBLE_AXE, z, 0.0F), "only the warhammer stuns");
        helper.assertTrue(WeaponTraits.rollStun(WeaponType.WARHAMMER, z, 0.0F), "a low roll stuns");
        helper.assertTrue(z.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "the stun is a heavy slowness");
        helper.succeed();
    }

    /**
     * T30: a mob's warhammer stuns only on a hit that lands. Incoming damage fires before a
     * shield block and before the invulnerability-frame rejection, so neither may stun; real
     * hurt() calls, 40 of each (a 25% stun would show in 40 by chance with odds 1 in 100 000).
     */
    @GameTest(template = "empty16", timeoutTicks = 60, batch = "captain_weapon_stun")
    public void aMobWarhammerStunsOnlyOnAHitThatLands(GameTestHelper helper) {
        floor(helper);
        Zombie hammer = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 1, 3));
        hammer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WeaponItems.get(WeaponType.WARHAMMER, Material.IRON)));
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(5, 1, 5));
        guard.setNoAi(true);
        guard.assignProfession(Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        // Yaw 180 faces north, toward the hammer.
        guard.setYRot(180.0F);
        guard.setYHeadRot(180.0F);
        guard.yBodyRot = 180.0F;
        guard.startUsingItem(InteractionHand.OFF_HAND);
        Zombie target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(11, 1, 11));
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2000.0D);
        target.setHealth(2000.0F);
        int hits = 40;

        helper.runAfterDelay(6, () -> {
            helper.assertTrue(WeaponConfig.stunChance() > 0.0D && WeaponTraits.enabled(), "fixture: the stun is on");
            helper.assertTrue(guard.isBlocking(), "fixture: the guard's shield is really up");
            float guardHealth = guard.getHealth();
            for (int i = 0; i < hits; i++) {
                guard.invulnerableTime = 0;
                helper.assertFalse(guard.hurt(hammer.damageSources().mobAttack(hammer), 6.0F),
                    "fixture: a full shield block");
            }
            helper.assertTrue(guard.getHealth() == guardHealth, "fully blocked hits cost no health");
            helper.assertFalse(guard.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "a full shield block never stuns");

            // A big plain hit opens the invulnerability window; smaller hammer hits inside it are rejected.
            helper.assertTrue(target.hurt(helper.getLevel().damageSources().generic(), 100.0F), "fixture: the window opens");
            float windowHealth = target.getHealth();
            for (int i = 0; i < hits; i++) {
                helper.assertFalse(target.hurt(hammer.damageSources().mobAttack(hammer), 6.0F),
                    "fixture: an in-frame hit is rejected");
            }
            helper.assertTrue(target.getHealth() == windowHealth, "rejected hits cost no health");
            helper.assertFalse(target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "an in-frame rejection never stuns");

            int stuns = 0;
            for (int i = 0; i < hits; i++) {
                target.invulnerableTime = 0;
                float before = target.getHealth();
                helper.assertTrue(target.hurt(hammer.damageSources().mobAttack(hammer), 6.0F), "fixture: the hit lands");
                helper.assertTrue(target.getHealth() < before, "fixture: a landed hit costs health");
                if (target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
                    stuns++;
                    target.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                }
            }
            helper.assertTrue(stuns > 0, "landed warhammer hits still stun (0 of " + hits + ")");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 20, batch = "captain_weapons")
    public void everyTierIsCraftableInSurvival(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager();
        for (String id : WeaponItems.ids()) {
            helper.assertTrue(recipes.byKey(ResourceLocation.fromNamespaceAndPath(Hearthstead.MODID, id)).isPresent(),
                "no recipe for " + id);
        }
        for (var byMat : WeaponItems.byType().values()) {
            for (var h : byMat.values()) {
                helper.assertTrue(h.get() instanceof CaptainWeaponItem, "registered as a captain weapon: " + h.getId());
            }
        }
        helper.succeed();
    }
}

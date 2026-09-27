package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardSaluteGoal;
import com.hearthstead.entity.combat.captain.CaptainConfig;
import com.hearthstead.entity.combat.captain.CaptainKit;
import com.hearthstead.entity.combat.captain.CaptainLoadout;
import com.hearthstead.entity.combat.captain.CaptainService;
import com.hearthstead.entity.combat.captain.CaptainSpecial;
import com.hearthstead.entity.combat.captain.CaptainSpecialGoal;
import com.hearthstead.entity.combat.captain.CaptainState;
import com.hearthstead.entity.combat.captain.CaptainStatus;
import com.hearthstead.entity.combat.captain.CaptainWorld;
import com.hearthstead.item.weapon.WeaponType;
import com.hearthstead.registry.WeaponItems;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;

/**
 * The hero Captain (plan/CAPTAIN.md): stats, the one-time field promotion,
 * the salute on pass-by, specials firing under their condition with their
 * cooldown, friendly fire off, and the item-gated loadout switch.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CaptainGameTests {

    private record Fixture(Settlement settlement, Building barracks) {
    }

    private static Fixture arena(GameTestHelper helper, boolean commissioned) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 5; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        CaptainConfig.overrideEnabledForTests(null);
        helper.getLevel().setDayTime(6000L);
        Settlement s = new Settlement(UUID.randomUUID(), "Captainholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        Building barracks = GameTestFixtures.register(helper, s, BuildingType.BARRACKS, 0, 0);
        CaptainStatus.commissionForTests(s.id, commissioned);
        return new Fixture(s, barracks);
    }

    private static SettlerEntity guard(GameTestHelper helper, Fixture f, String name, int strength,
                                       BlockPos at, boolean noAi) {
        SettlerEntity g = helper.spawn(ModEntities.SETTLER.get(), at);
        g.bindTo(f.settlement().id, f.settlement().center);
        g.setSettlerName(name);
        f.settlement().putRecord(g.getUUID(), name, Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement(), f.barracks(), g).ok(), "hire " + name);
        g.attributes().pinForTest(Attribute.STRENGTH, strength);
        g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        g.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        g.setHunger(90.0F);
        g.setNoAi(noAi);
        CaptainStatus.invalidate(f.settlement().id);
        return g;
    }

    private static RaiderEntity raider(GameTestHelper helper, Fixture f, RaiderEntity.Variant v, BlockPos at) {
        RaiderEntity r = helper.spawn(ModEntities.RAIDER.get(), at);
        r.setVariant(v);
        r.assign(UUID.randomUUID(), f.settlement().id, RaidObjective.BLOD, 1.0F, false);
        r.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        r.setHealth(200.0F);
        r.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        r.setNoAi(true);
        return r;
    }

    // ------------------------------------------------------------ stats

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "captain_stats")
    public void heroCaptainIsBiggerAndStrongerOnlyWhenCommissionedAndSergeant(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity cap = guard(helper, f, "Aldric", GuardRank.SERGEANT.threshold(), new BlockPos(6, 1, 8), true);
        SettlerEntity other = guard(helper, f, "Bram", 10, new BlockPos(10, 1, 8), true);
        helper.runAtTickTime(2L, () -> {
            helper.assertTrue(CaptainStatus.isHero(cap), "top guard, Sergeant, commissioned: hero");
            helper.assertTrue(!CaptainStatus.isHero(other), "exactly one hero per settlement");
            CaptainKit.sync(cap);
            CaptainKit.sync(other);
            // base max health belongs to the attributes lane (Vitality/Strength); assert the hero BONUS
            helper.assertTrue(CaptainKit.healthBonusOf(cap) == CaptainConfig.healthBonus(),
                "hero health bonus " + CaptainKit.healthBonusOf(cap) + " (max " + cap.getMaxHealth() + ")");
            helper.assertTrue(cap.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)
                >= CaptainConfig.knockbackResistance(), "hero knockback resistance");
            helper.assertTrue(cap.getAttributeValue(Attributes.ATTACK_DAMAGE)
                > other.getAttributeValue(Attributes.ATTACK_DAMAGE), "hero hits harder");
            helper.assertTrue(CaptainKit.healthBonusOf(other) == 0.0D, "an ordinary guard is unchanged");
            // Not invincible: a hero, bounded.
            helper.assertTrue(CaptainKit.healthBonusOf(cap) <= 40.0D, "bounded health bonus");
            // Below Sergeant: no hero kit, and it comes off again.
            cap.attributes().pinForTest(Attribute.STRENGTH, GuardRank.SERGEANT.threshold() - 1);
            CaptainStatus.invalidate(f.settlement().id);
            helper.assertTrue(!CaptainStatus.isHero(cap), "a Veteran is not the hero");
            CaptainKit.sync(cap);
            helper.assertTrue(CaptainKit.healthBonusOf(cap) == 0.0D, "the kit comes off");
            CaptainStatus.commissionForTests(f.settlement().id, false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "captain_promotion")
    public void commissionFieldPromotesTheTopGuardOnce(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity top = guard(helper, f, "Cedric", 12, new BlockPos(6, 1, 8), true);
        guard(helper, f, "Dunstan", 5, new BlockPos(10, 1, 8), true);
        helper.runAtTickTime(2L, () -> {
            SettlerEntity promoted = CaptainWorld.tryPromote(helper.getLevel(), f.settlement());
            helper.assertTrue(promoted == top, "the top guard is the one promoted");
            helper.assertTrue(top.attribute(Attribute.STRENGTH) >= GuardRank.SERGEANT.threshold(),
                "field promotion to Sergeant, Strength " + top.attribute(Attribute.STRENGTH));
            helper.assertTrue(CaptainStatus.isHero(top), "commissioned and Sergeant: the hero");
            helper.assertTrue(CaptainWorld.stateOf(top).promptPending(), "the name/loadout prompt is pending");
            helper.assertTrue(CaptainWorld.tryPromote(helper.getLevel(), f.settlement()) == null,
                "the promotion happens once per settlement");
            CaptainStatus.commissionForTests(f.settlement().id, false);
            helper.succeed();
        });
    }

    // ------------------------------------------------------------ salute

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_salute")
    public void soldiersSaluteTheCaptainWalkingPast(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity soldier = guard(helper, f, "Edwin", 0, new BlockPos(8, 1, 6), false);
        soldier.setYRot(0.0F);
        soldier.setYHeadRot(0.0F);
        soldier.setYBodyRot(0.0F);
        SettlerEntity cap = guard(helper, f, "Aldric", GuardRank.SERGEANT.threshold(), new BlockPos(9, 1, 15), true);
        double x = soldier.getX() + 1.0D;
        double z0 = soldier.getZ() + 9.0D;
        for (int i = 0; i <= 80; i++) {
            final double z = z0 - 0.2D * i;
            helper.runAfterDelay(10 + i, () -> cap.setPos(x, soldier.getY(), z));
        }
        helper.runAfterDelay(5, () -> helper.assertTrue(CaptainStatus.isHero(cap), "the captain is the hero"));
        helper.succeedWhen(() -> {
            helper.assertTrue(GuardSaluteGoal.saluteCount(soldier) >= 1,
                "a soldier salutes the passing Captain, count " + GuardSaluteGoal.saluteCount(soldier));
            CaptainStatus.commissionForTests(f.settlement().id, false);
        });
    }

    // ---------------------------------------------------------- specials

    private static SettlerEntity hero(GameTestHelper helper, Fixture f, ItemStack main, ItemStack off) {
        SettlerEntity cap = guard(helper, f, "Aldric", 80, new BlockPos(6, 1, 8), true);
        CaptainService.equipForTests(cap, main, off);
        return cap;
    }

    @GameTest(template = "empty16", timeoutTicks = 120, batch = "captain_special_cooldown")
    public void aSpecialFiresOnItsConditionThenRespectsItsCooldown(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity cap = hero(helper, f, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD));
        RaiderEntity brute = raider(helper, f, RaiderEntity.Variant.BRUTE, new BlockPos(7, 1, 8));
        cap.setTarget(brute);
        CaptainSpecialGoal goal = new CaptainSpecialGoal(cap);
        int windup = CaptainSpecial.POMMEL_STUN.windupTicks();
        helper.runAtTickTime(2L, () -> {
            goal.forceNext(CaptainSpecial.POMMEL_STUN);
            helper.assertTrue(goal.canUse(), "a brute in reach: Pommel Stun can start");
            goal.start();
        });
        for (long t = 3L; t <= 4L + windup; t++) {
            helper.runAtTickTime(t, () -> {
                if (goal.canContinueToUse()) {
                    goal.tick();
                }
            });
        }
        helper.runAtTickTime(6L + windup, () -> {
            goal.stop();
            helper.assertTrue(brute.isStaggered(), "the pommel strike stuns the brute");
            helper.assertTrue(brute.getHealth() < 200.0F, "and hurts it");
            CaptainState cs = CaptainWorld.stateOf(cap);
            helper.assertTrue(cs.fired(CaptainSpecial.POMMEL_STUN) == 0 || !cs.ready(CaptainSpecial.POMMEL_STUN,
                helper.getLevel().getGameTime()), "on cooldown after firing");
            goal.forceNext(CaptainSpecial.POMMEL_STUN);
            helper.assertTrue(!goal.canUse(), "a special on cooldown cannot be forced again");
            CaptainStatus.commissionForTests(f.settlement().id, false);
            helper.succeed();
        });
    }

    /**
     * Codex T31: an interrupted Shield Charge kept its victims' ids, so the same goal's next
     * charge could never hit them again. Charge 1 hits the brute and is broken off before its
     * end; after the cooldown, charge 2 must hit the same brute.
     */
    @GameTest(template = "empty16", timeoutTicks = 460, batch = "captain_special_rehit")
    public void anInterruptedShieldChargeCanHitTheSameRaiderNextTime(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity cap = hero(helper, f, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD));
        RaiderEntity brute = raider(helper, f, RaiderEntity.Variant.BRUTE, new BlockPos(7, 1, 8));
        CaptainSpecialGoal goal = new CaptainSpecialGoal(cap);
        BlockPos home = helper.absolutePos(new BlockPos(7, 1, 8));
        BlockPos capHome = helper.absolutePos(new BlockPos(6, 1, 8));
        int[] stage = {0};
        Runnable arm = () -> {
            brute.setPos(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
            brute.setDeltaMovement(0.0D, 0.0D, 0.0D);
            cap.setPos(capHome.getX() + 0.5D, capHome.getY(), capHome.getZ() + 0.5D);
            brute.setHealth(200.0F);
            cap.setTarget(brute);
            goal.forceNext(CaptainSpecial.SHIELD_CHARGE);
            helper.assertTrue(goal.canUse(), "Shield Charge can start (stage " + stage[0] + ")");
            goal.start();
        };
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    arm.run();
                    stage[0] = 1;
                }
                case 1, 3 -> {
                    if (brute.getHealth() < 200.0F) {
                        if (stage[0] == 1) {
                            goal.stop(); // broken off mid-charge, before its end phase
                            stage[0] = 2;
                        } else {
                            stage[0] = 4;
                        }
                    } else if (goal.canContinueToUse()) {
                        goal.tick();
                    } else {
                        helper.fail(stage[0] == 1 ? "charge 1 must hit the brute"
                            : "charge 2 never hit the brute an interrupted charge had hit");
                    }
                }
                case 2 -> {
                    if (CaptainWorld.stateOf(cap).ready(CaptainSpecial.SHIELD_CHARGE, now)) {
                        arm.run();
                        stage[0] = 3;
                    }
                }
                default -> { }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stage[0] == 4, "both charges hit the brute (stage " + stage[0] + ")");
            CaptainStatus.commissionForTests(f.settlement().id, false);
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 120, batch = "captain_friendly_fire")
    public void crowdSpecialsNeverHurtSettlersPlayersOrAnimals(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity cap = hero(helper, f, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_SWORD));
        RaiderEntity a = raider(helper, f, RaiderEntity.Variant.SKIRMISHER, new BlockPos(7, 1, 8));
        RaiderEntity b = raider(helper, f, RaiderEntity.Variant.SKIRMISHER, new BlockPos(6, 1, 9));
        SettlerEntity ally = guard(helper, f, "Friend", 0, new BlockPos(5, 1, 8), true);
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(6, 1, 7));
        pig.setNoAi(true);
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos p = helper.absolutePos(new BlockPos(5, 1, 7));
        player.setPos(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
        cap.setTarget(a);
        CaptainSpecialGoal goal = new CaptainSpecialGoal(cap);
        float allyStart = ally.getHealth();
        float pigStart = pig.getHealth();
        float playerStart = player.getHealth();
        int span = CaptainSpecial.BLADE_WHIRL.windupTicks() + 10;
        helper.runAtTickTime(2L, () -> {
            goal.forceNext(CaptainSpecial.BLADE_WHIRL);
            helper.assertTrue(goal.canUse(), "Blade Whirl can start");
            goal.start();
        });
        for (long t = 3L; t <= 4L + span; t++) {
            helper.runAtTickTime(t, () -> {
                if (goal.canContinueToUse()) {
                    goal.tick();
                }
            });
        }
        helper.runAtTickTime(6L + span, () -> {
            helper.assertTrue(a.getHealth() < 200.0F && b.getHealth() < 200.0F, "both raiders hit");
            helper.assertTrue(ally.getHealth() == allyStart, "the settler beside him is untouched");
            helper.assertTrue(pig.getHealth() == pigStart, "the pig is untouched");
            helper.assertTrue(player.getHealth() == playerStart, "the player is untouched");
            CaptainStatus.commissionForTests(f.settlement().id, false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "captain_loadout_item")
    public void switchingLoadoutNeedsTheWeaponItem(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity cap = hero(helper, f, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD));
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        helper.runAtTickTime(2L, () -> {
            String refused = CaptainService.switchLoadout(player, cap, CaptainLoadout.BOW);
            helper.assertTrue(refused.contains("refused"), "no bow anywhere: refused, got " + refused);
            helper.assertTrue(cap.getMainHandItem().is(Items.IRON_SWORD), "his kit is unchanged");
            player.getInventory().add(new ItemStack(Items.BOW));
            player.getInventory().add(new ItemStack(Items.ARROW, 16));
            String ok = CaptainService.switchLoadout(player, cap, CaptainLoadout.BOW);
            helper.assertTrue(ok.equals("hearthstead.captain.switched"), "with a bow: switched, got " + ok);
            helper.assertTrue(cap.getMainHandItem().is(Items.BOW) && cap.getOffhandItem().is(Items.ARROW),
                "bow in hand, arrows in the off hand");
            helper.assertTrue(player.getInventory().countItem(Items.IRON_SWORD) == 1
                && player.getInventory().countItem(Items.SHIELD) == 1, "the old kit came back to the player");
            helper.assertTrue(player.getInventory().countItem(Items.BOW) == 0, "one real bow moved");
            helper.assertTrue(CaptainWorld.stateOf(cap).rearming(helper.getLevel().getGameTime()),
                "a switch re-arms before any special");
            CaptainStatus.commissionForTests(f.settlement().id, false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "captain_kill_switch")
    public void captainDisabledMeansNoHero(GameTestHelper helper) {
        Fixture f = arena(helper, true);
        SettlerEntity cap = guard(helper, f, "Aldric", 80, new BlockPos(6, 1, 8), true);
        helper.runAtTickTime(2L, () -> {
            try {
                helper.assertTrue(CaptainStatus.isHero(cap), "control: hero while enabled");
                CaptainConfig.overrideEnabledForTests(false);
                helper.assertTrue(!CaptainStatus.isHero(cap), "switched off: no hero");
                CaptainKit.sync(cap);
                helper.assertTrue(CaptainKit.healthBonusOf(cap) == 0.0D, "switched off: no hero stats");
                helper.assertTrue(!cap.hasEffect(MobEffects.DAMAGE_BOOST), "nothing lingering");
            } finally {
                CaptainConfig.overrideEnabledForTests(null);
                CaptainStatus.commissionForTests(f.settlement().id, false);
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------- specials per loadout

    private static ItemStack weapon(WeaponType type) {
        return new ItemStack(WeaponItems.get(type, WeaponItems.Material.IRON));
    }

    /**
     * Every special of one loadout, forced in turn against a brute in reach: each one fires on its
     * condition (damage, or its effect for the non-damaging ones), then sits on cooldown and cannot be
     * forced again; the settler behind him is never touched (no friendly fire).
     */
    private static void specialsOf(GameTestHelper helper, CaptainLoadout loadout, ItemStack main, ItemStack off,
                                   List<CaptainSpecial> specials) {
        Fixture f = arena(helper, true);
        CaptainConfig.overrideExtraLoadoutsForTests(true);
        SettlerEntity cap = hero(helper, f, main, off);
        SettlerEntity ally = guard(helper, f, "Friend", 0, new BlockPos(4, 1, 8), true);
        RaiderEntity brute = raider(helper, f, RaiderEntity.Variant.BRUTE, new BlockPos(7, 1, 8));
        CaptainSpecialGoal goal = new CaptainSpecialGoal(cap);
        float allyStart = ally.getHealth();
        BlockPos home = helper.absolutePos(new BlockPos(7, 1, 8));
        BlockPos capHome = helper.absolutePos(new BlockPos(6, 1, 8));
        long t = 2L;
        for (CaptainSpecial sp : specials) {
            final long start = t;
            final int span = sp.windupTicks() + 16;
            helper.runAtTickTime(start, () -> {
                helper.assertTrue(CaptainKit.heldLoadout(cap) == loadout, sp + ": holds the " + loadout + " kit");
                brute.setPos(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
                cap.setPos(capHome.getX() + 0.5D, capHome.getY(), capHome.getZ() + 0.5D);
                brute.setDeltaMovement(0.0D, 0.0D, 0.0D);
                brute.setHealth(200.0F);
                cap.setTarget(brute);
                goal.forceNext(sp);
                helper.assertTrue(goal.canUse(), sp + " can start with a brute in reach");
                goal.start();
            });
            for (long k = start + 1; k <= start + span; k++) {
                helper.runAtTickTime(k, () -> {
                    if (goal.canContinueToUse()) {
                        goal.tick();
                    }
                });
            }
            helper.runAtTickTime(start + span + 1, () -> {
                goal.stop();
                long now = helper.getLevel().getGameTime();
                switch (sp) {
                    case HOLD_THE_LINE -> helper.assertTrue(cap.hasEffect(MobEffects.DAMAGE_RESISTANCE),
                        "Hold the Line: he braces (resistance)");
                    case MARK_TARGET -> helper.assertTrue(brute.hasEffect(MobEffects.GLOWING),
                        "Mark Target: the brute is marked");
                    default -> helper.assertTrue(brute.getHealth() < 200.0F,
                        sp + " fires on its condition and hurts the brute, health " + brute.getHealth());
                }
                CaptainState cs = CaptainWorld.stateOf(cap);
                helper.assertTrue(!cs.ready(sp, now), sp + " is on cooldown after firing");
                goal.forceNext(sp);
                helper.assertTrue(!goal.canUse(), sp + " cannot be forced again on cooldown");
                helper.assertTrue(ally.getHealth() == allyStart, sp + ": the settler behind him is untouched");
            });
            t = start + span + 3;
        }
        helper.runAtTickTime(t + 1, () -> {
            // Do NOT reset the extra-loadouts override here: the five loadout
            // tests of this batch run in parallel and share that static, so the
            // first one to finish switched the HALBERD/WARHAMMER kits off under
            // the others (broad run 16:04, "HOOK_PULL: holds the HALBERD kit").
            // GameTestIsolation resets it before the next batch.
            CaptainStatus.commissionForTests(f.settlement().id, false);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_loadout_specials")
    public void swordAndShieldSpecialsFireAndCoolDown(GameTestHelper helper) {
        specialsOf(helper, CaptainLoadout.SWORD_SHIELD, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD),
            List.of(CaptainSpecial.POMMEL_STUN, CaptainSpecial.SHIELD_CHARGE, CaptainSpecial.HOLD_THE_LINE));
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_loadout_specials")
    public void dualSwordSpecialsFireAndCoolDown(GameTestHelper helper) {
        specialsOf(helper, CaptainLoadout.DUAL_SWORDS, weapon(WeaponType.SHORT_SWORD), weapon(WeaponType.SHORT_SWORD),
            List.of(CaptainSpecial.TWIN_THRUST, CaptainSpecial.DISARM, CaptainSpecial.BLADE_WHIRL));
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_loadout_specials")
    public void doubleAxeSpecialsFireAndCoolDown(GameTestHelper helper) {
        specialsOf(helper, CaptainLoadout.GREAT_AXE, weapon(WeaponType.DOUBLE_AXE), ItemStack.EMPTY,
            List.of(CaptainSpecial.AXE_CLEAVE, CaptainSpecial.SPINNING_CHOP, CaptainSpecial.ARMOUR_BREAKER));
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_loadout_specials")
    public void bowSpecialsFireAndCoolDown(GameTestHelper helper) {
        specialsOf(helper, CaptainLoadout.BOW, new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 32),
            List.of(CaptainSpecial.PIERCING_SHOT, CaptainSpecial.MARK_TARGET, CaptainSpecial.ARROW_VOLLEY));
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_loadout_specials")
    public void halberdSpecialsFireAndCoolDown(GameTestHelper helper) {
        specialsOf(helper, CaptainLoadout.HALBERD, weapon(WeaponType.HALBERD), ItemStack.EMPTY,
            List.of(CaptainSpecial.BRACE_CHARGE, CaptainSpecial.HOOK_PULL, CaptainSpecial.HALBERD_SWEEP));
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "captain_loadout_specials")
    public void warhammerSpecialsFireAndCoolDown(GameTestHelper helper) {
        specialsOf(helper, CaptainLoadout.WARHAMMER, weapon(WeaponType.WARHAMMER), ItemStack.EMPTY,
            List.of(CaptainSpecial.SHIELD_BREAKER, CaptainSpecial.CRUSHING_BLOW, CaptainSpecial.GROUND_SLAM));
    }
}

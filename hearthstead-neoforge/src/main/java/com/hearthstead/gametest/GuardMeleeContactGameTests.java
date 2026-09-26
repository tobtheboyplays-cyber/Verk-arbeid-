package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.combat.CinematicOpportunity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Runtime proofs for MELEE's server-authored wind-up/contact contract. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardMeleeContactGameTests {

    private record Fixture(Settlement settlement, SettlerEntity guard,
                           RaiderEntity target, GuardMeleeGoal goal,
                           float startingHealth, int startingStrength) {
    }

    private static Fixture fixture(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Contactholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 8));
        guard.setSettlerName("Blade");
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), guard.getSettlerName(),
            Profession.NONE);
        guard.assignProfession(Profession.GUARD);
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        // Attribute rolls are intentionally random in production. Pin this
        // contact-timing fixture after profession/trait initialisation so one
        // accepted hit always crosses a visible Strength point; otherwise a
        // high rolled Strength can legitimately earn sub-integer progress and
        // make the authority assertion intermittent.
        guard.attributes().pinForTest(Attribute.STRENGTH, 0);
        guard.attributes().pinForTest(Attribute.WITS, 0);
        guard.setNoAi(true);

        RaiderEntity target = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(7, 1, 8));
        target.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD,
            1.0F, false);
        target.setNoAi(true);
        guard.setTarget(target);

        return new Fixture(settlement, guard, target,
            new GuardMeleeGoal(guard), target.getHealth(),
            guard.attribute(Attribute.STRENGTH));
    }

    private static void tickAt(GameTestHelper helper, long tick,
                               GuardMeleeGoal goal, Runnable assertion) {
        GameTestTicks.at(helper, tick, () -> {
            goal.tick();
            assertion.run();
        });
    }

    /** Makes one ordinary hit leave a live Raider inside the finisher threshold. */
    private static void prepareCinematicTarget(RaiderEntity target) {
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0D);
        target.setHealth(13.0F);
    }

    private static SettlerEntity alliedGuard(GameTestHelper helper, Fixture f,
                                              BlockPos pos) {
        SettlerEntity ally = helper.spawn(ModEntities.SETTLER.get(), pos);
        ally.setSettlerName("Sideblade");
        ally.bindTo(f.settlement().id, f.settlement().center);
        f.settlement().putRecord(ally.getUUID(), ally.getSettlerName(),
            Profession.NONE);
        ally.assignProfession(Profession.GUARD);
        // It must exceed the first iron-sword hit while vanilla hurt immunity
        // is active, otherwise this overlapping real contact is correctly
        // rejected before RaiderEntity can observe the interruption.
        ally.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        ally.setNoAi(true);
        ally.setTarget(f.target());
        return ally;
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_exact_tick_and_single_damage")
    public void exactTickAndSingleDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        for (int elapsed = 0; elapsed < GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            long testTick = 1L + elapsed;
            tickAt(helper, testTick, f.goal(), () -> {
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "MELEE must deal no early damage before contact tick "
                        + GuardMeleeGoal.MELEE_CONTACT_TICK + ", health="
                        + f.target().getHealth() + "/" + f.startingHealth());
                helper.assertTrue(f.guard().attribute(Attribute.STRENGTH)
                        == f.startingStrength(),
                    "an unlanded wind-up must not train Strength");
            });
        }

        float[] afterContact = {Float.NaN};
        tickAt(helper, 1L + GuardMeleeGoal.MELEE_CONTACT_TICK, f.goal(), () -> {
            helper.assertTrue(f.target().getHealth() < f.startingHealth(),
                "damage must land on the authored t=0.20 s contact tick");
            helper.assertTrue(f.guard().attribute(Attribute.STRENGTH)
                    > f.startingStrength(),
                "only the accepted contact may train Strength");
            helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                "one successful hurt must create one terminal contact receipt");
            afterContact[0] = f.target().getHealth();
        });
        tickAt(helper, 2L + GuardMeleeGoal.MELEE_CONTACT_TICK, f.goal(), () -> {
            helper.assertTrue(f.target().getHealth() == afterContact[0],
                "one contact must produce one damage pass; next-tick retry changed "
                    + afterContact[0] + " to " + f.target().getHealth());
            helper.assertTrue(f.guard().committedMeleeContacts() == 1L,
                "next-tick replay must not create a duplicate terminal receipt");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_interruption_cancels_without_damage")
    public void interruptionCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        GameTestTicks.at(helper, 2L, f.goal()::stop);
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "an interrupted wind-up must never replay damage"));
        }
        GameTestTicks.at(helper, 3L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertTrue(f.guard().attribute(Attribute.STRENGTH)
                    == f.startingStrength(),
                "interruption must not pay combat training");
            helper.assertTrue(f.guard().committedMeleeContacts() == 0L,
                "interruption must not create terminal contact evidence");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_out_of_range_cancels_without_damage")
    public void outOfRangeCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        GameTestTicks.at(helper, 2L, () -> f.target().teleportTo(
            f.guard().getX() + 6.0, f.guard().getY(), f.guard().getZ()));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "leaving blade range before contact must turn the swing into a miss"));
        }
        GameTestTicks.at(helper, 3L + GuardMeleeGoal.MELEE_CONTACT_TICK,
            helper::succeed);
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_lost_los_cancels_without_damage")
    public void lostLineOfSightCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        GameTestTicks.at(helper, 2L, () -> {
            // An iron-bar screen blocks the eye ray without suffocating the
            // no-AI target standing in that block. Clear Sensing's per-tick
            // cache so this assertion proves the fixture before contact.
            helper.setBlock(new BlockPos(7, 1, 8), Blocks.IRON_BARS);
            helper.setBlock(new BlockPos(7, 2, 8), Blocks.IRON_BARS);
            f.guard().getSensing().tick();
            helper.assertFalse(f.guard().getSensing().hasLineOfSight(f.target()),
                "fixture: the inserted screen must break guard-to-target LOS");
        });
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "LOS loss before contact must turn the swing into a miss"));
        }
        GameTestTicks.at(helper, 3L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertTrue(f.guard().attribute(Attribute.STRENGTH)
                    == f.startingStrength(),
                "a LOS-cancelled contact must not pay combat training");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_dead_target_cannot_take_contact")
    public void targetDeathBeforeContactCannotTakeDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] ticket = {0L};
        GameTestTicks.at(helper, 1L, () -> {
            ticket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(ticket[0] > 0L,
                "fixture: a live authorized target must issue a contact ticket");
        });
        GameTestTicks.at(helper, 2L, f.target()::kill);
        GameTestTicks.at(helper, 1L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertFalse(f.target().isAlive(),
                "fixture: the target must already be dead before contact");
            helper.assertFalse(f.guard().commitMeleeContact(ticket[0], f.target()),
                "a dead target must consume/refuse the due contact ticket");
            helper.assertFalse(f.guard().commitMeleeContact(ticket[0], f.target()),
                "a refused dead-target ticket must never become replayable");
            helper.assertTrue(f.guard().attribute(Attribute.STRENGTH)
                    == f.startingStrength(),
                "a dead-target refusal must not pay combat training");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_removed_target_cannot_take_contact")
    public void removedTargetBeforeContactCannotTakeDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] ticket = {0L};
        GameTestTicks.at(helper, 1L, () -> {
            ticket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(ticket[0] > 0L,
                "fixture: a live authorized target must issue a contact ticket");
        });
        GameTestTicks.at(helper, 2L, f.target()::discard);
        GameTestTicks.at(helper, 1L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertTrue(f.target().isRemoved(),
                "fixture: the target must be removed before contact");
            helper.assertFalse(f.guard().commitMeleeContact(ticket[0], f.target()),
                "a removed target must consume/refuse the due contact ticket");
            helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                "removal must not be followed by hidden melee damage");
            helper.assertFalse(f.guard().commitMeleeContact(ticket[0], f.target()),
                "a refused removed-target ticket must never become replayable");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_target_switch_cancels_old_ticket")
    public void targetSwitchCancelsOldTicket(GameTestHelper helper) {
        Fixture f = fixture(helper);
        RaiderEntity replacement = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(7, 1, 8));
        replacement.assign(UUID.randomUUID(), f.settlement().id,
            RaidObjective.BLOD, 1.0F, false);
        replacement.setNoAi(true);
        float replacementHealth = replacement.getHealth();

        tickAt(helper, 1L, f.goal(), () -> {
        });
        GameTestTicks.at(helper, 2L, () -> f.guard().setTarget(replacement));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () -> {
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "the old target must not receive damage after target switch");
                helper.assertTrue(replacement.getHealth() == replacementHealth,
                    "the old ticket must never transfer damage to a new target");
            });
        }
        GameTestTicks.at(helper, 3L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertTrue(f.guard().attribute(Attribute.STRENGTH)
                    == f.startingStrength(),
                "target-switch cancellation must not pay combat training");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_removed_weapon_cancels_without_damage")
    public void removedWeaponCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        GameTestTicks.at(helper, 2L, () -> f.guard().setItemSlot(
            EquipmentSlot.MAINHAND, ItemStack.EMPTY));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "removing the physical sword must cancel pending damage"));
        }
        GameTestTicks.at(helper, 3L + GuardMeleeGoal.MELEE_CONTACT_TICK,
            helper::succeed);
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_foreign_raid_target_cancels_without_damage")
    public void foreignRaidTargetCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        GameTestTicks.at(helper, 2L, () -> f.target().assign(UUID.randomUUID(),
            UUID.randomUUID(), RaidObjective.BLOD, 1.0F, false));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "a raider rebound to another settlement must not receive this blade"));
        }
        GameTestTicks.at(helper, 3L + GuardMeleeGoal.MELEE_CONTACT_TICK,
            helper::succeed);
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_ticket_never_survives_reload")
    public void ticketNeverSurvivesReload(GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] oldTicket = {0L};
        SettlerEntity[] loaded = {null};

        GameTestTicks.at(helper, 1L, () -> {
            oldTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(oldTicket[0] > 0L,
                "fixture must issue a real pending contact ticket");
            CompoundTag saved = new CompoundTag();
            f.guard().addAdditionalSaveData(saved);
            helper.assertFalse(saved.getAllKeys().stream()
                    .anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT)
                        .contains("melee")),
                "runtime melee authority must have no NBT key");

            f.guard().discard();
            loaded[0] = helper.spawn(ModEntities.SETTLER.get(),
                new BlockPos(6, 1, 8));
            loaded[0].readAdditionalSaveData(saved);
            loaded[0].setNoAi(true);
            loaded[0].setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(Items.IRON_SWORD));
        });

        GameTestTicks.at(helper, 1L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertFalse(loaded[0].commitMeleeContact(oldTicket[0],
                    f.target()),
                "a reloaded entity must refuse the pre-save contact ticket");
            helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                "reload must not replay pending melee damage");
            helper.succeed();
        });
    }
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "cinematic_finisher_real_hit_opens_and_resolves_normally")
    public void realHitOpensOpportunityAndFinisherUsesOrdinaryContact(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        float[] healthBeforeOpening = {0.0F};
        float[] healthAfterOpening = {0.0F};

        GameTestTicks.at(helper, 1L, () -> {
            f.target().getAttribute(Attributes.MAX_HEALTH).setBaseValue(80.0D);
            f.target().setHealth(27.0F);
            healthBeforeOpening[0] = f.target().getHealth();
            f.goal().tick();
        });
        for (long tick = 2L; tick <= 25L; tick++) {
            long currentTick = tick;
            tickAt(helper, currentTick, f.goal(), () -> {
                if (currentTick == 5L) {
                    helper.assertTrue(f.target().getHealth() < healthBeforeOpening[0],
                        "the normal goal's opening contact must land at tick 5");
                    healthAfterOpening[0] = f.target().getHealth();
                    helper.assertTrue(f.target().isAlive() && healthAfterOpening[0] <= 28.0F,
                        "the real hit must leave a live Raider in the finisher threshold");
                    helper.assertTrue(f.target().cinematicOpportunityState()
                            == CinematicOpportunity.State.OFFERED,
                        "only the accepted opening hit may create an offered opportunity");
                    // The normal goal trains after each accepted contact. Pin
                    // the comparison fixture again so the second contact has
                    // identical weapon, armour and combat attributes.
                    f.guard().attributes().pinForTest(Attribute.STRENGTH, 0);
                } else if (currentTick == 21L) {
                    helper.assertTrue(f.target().cinematicOpportunityState()
                            == CinematicOpportunity.State.CLAIMED,
                        "the ordinary 20-tick Guard cadence must claim the live offer");
                } else if (currentTick == 25L) {
                    float openingDamage = healthBeforeOpening[0] - healthAfterOpening[0];
                    float finisherDamage = healthAfterOpening[0] - f.target().getHealth();
                    helper.assertTrue(f.target().getHealth() < healthAfterOpening[0],
                        "the normal goal's claimed contact must still deal damage");
                    helper.assertTrue(Math.abs(finisherDamage - openingDamage) < 0.001F,
                        "the finisher must use exactly the ordinary contact damage, not execute damage");
                    helper.assertTrue(f.target().cinematicOpportunityClearReason()
                            == CinematicOpportunity.ClearReason.FINISHER_RESOLVED,
                        "the accepted contact must resolve the claimed opportunity once");
                    helper.assertTrue(f.guard().committedMeleeContacts() == 2L,
                        "opening and finisher must each commit exactly one ledger contact");
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "cinematic_finisher_side_guard_interrupt_cancels_ticket")
    public void sideGuardHitCancelsClaimedFinisherWithoutDelayedDamage(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        SettlerEntity side = alliedGuard(helper, f, new BlockPos(6, 1, 9));
        long[] openingTicket = {0L};
        long[] finisherTicket = {0L};
        long[] sideTicket = {0L};
        float[] healthAfterInterrupt = {0.0F};

        GameTestTicks.at(helper, 1L, () -> {
            prepareCinematicTarget(f.target());
            f.target().getAttribute(Attributes.MAX_HEALTH).setBaseValue(80.0D);
            f.target().setHealth(27.0F);
            openingTicket[0] = f.guard().beginMeleeWindup(f.target());
        });
        GameTestTicks.at(helper, 2L, () -> {
            sideTicket[0] = side.beginMeleeWindup(f.target());
            helper.assertTrue(sideTicket[0] > 0L,
                "fixture: the side Guard must issue a normal ticket before the offer");
        });
        GameTestTicks.at(helper, 5L, () -> {
            helper.assertTrue(f.guard().commitMeleeContact(openingTicket[0], f.target()),
                "fixture: the opening contact must create the opportunity");
            finisherTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(finisherTicket[0] > 0L,
                "the same post-hit callback must claim the fresh finisher offer");
        });
        GameTestTicks.at(helper, 6L, () -> {
            helper.assertTrue(side.commitMeleeContact(sideTicket[0], f.target()),
                "a live allied Guard side contact must be accepted by the Raider");
            healthAfterInterrupt[0] = f.target().getHealth();
        });
        GameTestTicks.at(helper, 9L, () -> {
            helper.assertFalse(f.guard().commitMeleeContact(finisherTicket[0], f.target()),
                "an accepted side hit must cancel the exact pending finisher ticket");
            helper.assertTrue(f.target().getHealth() == healthAfterInterrupt[0],
                "the cancelled finisher must not add delayed or duplicate damage");
            helper.assertTrue(f.guard().committedMeleeContacts() == 1,
                "only the opening contact may belong to the original Guard");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "cinematic_finisher_arrow_interrupt_cancels_ticket")
    public void arrowHitCancelsClaimedFinisherWithoutDelayedDamage(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] openingTicket = {0L};
        long[] finisherTicket = {0L};
        float[] healthAfterInterrupt = {0.0F};

        GameTestTicks.at(helper, 1L, () -> {
            prepareCinematicTarget(f.target());
            openingTicket[0] = f.guard().beginMeleeWindup(f.target());
        });
        GameTestTicks.at(helper, 5L, () -> helper.assertTrue(
            f.guard().commitMeleeContact(openingTicket[0], f.target()),
            "fixture: the opening contact must create the opportunity"));
        GameTestTicks.at(helper, 15L, () -> {
            finisherTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(finisherTicket[0] > 0L,
                "fixture: the original Guard must claim the finisher");
        });
        GameTestTicks.at(helper, 16L, () -> {
            Arrow arrow = new Arrow(helper.getLevel(), f.guard(),
                new ItemStack(Items.ARROW), f.guard().getMainHandItem());
            helper.assertTrue(f.target().hurt(helper.getLevel().damageSources()
                    .arrow(arrow, f.guard()), 1.0F),
                "a real Arrow damage source must be accepted by the Raider");
            healthAfterInterrupt[0] = f.target().getHealth();
        });
        GameTestTicks.at(helper, 19L, () -> {
            helper.assertFalse(f.guard().commitMeleeContact(finisherTicket[0], f.target()),
                "an accepted arrow hit must cancel the claimed finisher ticket");
            helper.assertTrue(f.target().getHealth() == healthAfterInterrupt[0],
                "the arrow cancellation must leave no delayed finisher damage");
            helper.assertTrue(f.guard().committedMeleeContacts() == 1,
                "the original Guard must retain only its opening contact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "cinematic_finisher_range_loss_cancels_ticket")
    public void claimedFinisherLeavingRangeCancelsWithoutDelayedDamage(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] openingTicket = {0L};
        long[] finisherTicket = {0L};
        float[] healthBeforeContact = {0.0F};

        GameTestTicks.at(helper, 1L, () -> {
            prepareCinematicTarget(f.target());
            openingTicket[0] = f.guard().beginMeleeWindup(f.target());
        });
        GameTestTicks.at(helper, 5L, () -> helper.assertTrue(
            f.guard().commitMeleeContact(openingTicket[0], f.target()),
            "fixture: the opening contact must create the opportunity"));
        GameTestTicks.at(helper, 15L, () -> {
            finisherTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(finisherTicket[0] > 0L,
                "fixture: the live low-health Raider must offer a claimable ticket");
        });
        GameTestTicks.at(helper, 16L, () -> {
            f.target().teleportTo(f.guard().getX() + 6.0D, f.guard().getY(),
                f.guard().getZ());
            healthBeforeContact[0] = f.target().getHealth();
        });
        GameTestTicks.at(helper, 17L, () -> f.target().teleportTo(
            f.guard().getX() + 1.0D, f.guard().getY(), f.guard().getZ()));
        GameTestTicks.at(helper, 19L, () -> {
            helper.assertFalse(f.guard().commitMeleeContact(finisherTicket[0], f.target()),
                "even temporary range loss must immediately cancel the claimed ticket");
            helper.assertTrue(f.target().getHealth() == healthBeforeContact[0],
                "a cancelled claim must not revive when the target returns to range");
            helper.assertTrue(f.guard().committedMeleeContacts() == 1,
                "the original Guard must retain only its valid opening contact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "cinematic_finisher_removed_target_cancels_ticket")
    public void removedTargetCancelsClaimedFinisherWithoutDelayedDamage(
            GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] openingTicket = {0L};
        long[] finisherTicket = {0L};

        GameTestTicks.at(helper, 1L, () -> {
            prepareCinematicTarget(f.target());
            openingTicket[0] = f.guard().beginMeleeWindup(f.target());
        });
        GameTestTicks.at(helper, 5L, () -> helper.assertTrue(
            f.guard().commitMeleeContact(openingTicket[0], f.target()),
            "fixture: the opening contact must create the opportunity"));
        GameTestTicks.at(helper, 6L, () -> {
            finisherTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(finisherTicket[0] > 0L,
                "fixture: the low-health target must issue a claimed finisher ticket");
            helper.assertTrue(f.target().cinematicOpportunityState()
                    == CinematicOpportunity.State.CLAIMED,
                "fixture: target removal must follow an actual claimed opportunity");
        });
        GameTestTicks.at(helper, 7L, f.target()::discard);
        GameTestTicks.at(helper, 10L, () -> {
            helper.assertTrue(f.target().isRemoved(),
                "fixture: the target must be gone before the claimed contact");
            helper.assertFalse(f.guard().commitMeleeContact(finisherTicket[0], f.target()),
                "a removed target must cancel the exact claimed finisher ticket");
            helper.assertTrue(f.guard().committedMeleeContacts() == 1,
                "target loss must not leave a delayed original-Guard contact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "cinematic_finisher_expiry_returns_to_normal_contact")
    public void expiredOpportunityReturnsToOrdinaryContact(GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] openingTicket = {0L};
        long[] claimedTicket = {0L};
        long[] postExpiryTicket = {0L};
        float[] healthBeforePostExpiryContact = {0.0F};

        GameTestTicks.at(helper, 1L, () -> {
            prepareCinematicTarget(f.target());
            openingTicket[0] = f.guard().beginMeleeWindup(f.target());
        });
        GameTestTicks.at(helper, 5L, () -> helper.assertTrue(
            f.guard().commitMeleeContact(openingTicket[0], f.target()),
            "fixture: the opening contact must create the opportunity"));
        GameTestTicks.at(helper, 6L, () -> {
            claimedTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(claimedTicket[0] > 0L
                    && f.target().cinematicOpportunityState()
                        == CinematicOpportunity.State.CLAIMED,
                "fixture: an ordinary Guard ticket must claim the live window first");
        });
        GameTestTicks.at(helper, 26L, () -> {
            helper.assertFalse(f.target().claimCinematicOpportunity(UUID.randomUUID(),
                    helper.getLevel().getGameTime()),
                "a competing claim after expiry must fail through Raider-owned cleanup");
            helper.assertTrue(f.target().cinematicOpportunityClearReason()
                    == CinematicOpportunity.ClearReason.EXPIRED,
                "expiry must be recorded before normal combat resumes");
            postExpiryTicket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(postExpiryTicket[0] > 0L,
                "expiry must cancel the old claimed ticket before a new normal one begins");
            healthBeforePostExpiryContact[0] = f.target().getHealth();
        });
        GameTestTicks.at(helper, 26L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertTrue(f.guard().commitMeleeContact(postExpiryTicket[0], f.target()),
                "after expiry, a normal valid contact must still resolve");
            helper.assertTrue(f.target().getHealth() < healthBeforePostExpiryContact[0],
                "expiry must not lock the Raider or suppress ordinary combat");
            helper.assertTrue(f.guard().committedMeleeContacts() == 2,
                "expiry must preserve one contact per valid ticket");
            helper.succeed();
        });
    }
}

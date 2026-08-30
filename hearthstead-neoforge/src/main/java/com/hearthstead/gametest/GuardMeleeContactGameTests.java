package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
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
        helper.runAtTickTime(tick, () -> {
            goal.tick();
            assertion.run();
        });
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
        helper.runAtTickTime(2L, f.goal()::stop);
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "an interrupted wind-up must never replay damage"));
        }
        helper.runAtTickTime(3L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
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
        helper.runAtTickTime(2L, () -> f.target().teleportTo(
            f.guard().getX() + 6.0, f.guard().getY(), f.guard().getZ()));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "leaving blade range before contact must turn the swing into a miss"));
        }
        helper.runAtTickTime(3L + GuardMeleeGoal.MELEE_CONTACT_TICK,
            helper::succeed);
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_lost_los_cancels_without_damage")
    public void lostLineOfSightCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        helper.runAtTickTime(2L, () -> {
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
        helper.runAtTickTime(3L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
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
        helper.runAtTickTime(1L, () -> {
            ticket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(ticket[0] > 0L,
                "fixture: a live authorized target must issue a contact ticket");
        });
        helper.runAtTickTime(2L, f.target()::kill);
        helper.runAtTickTime(1L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
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
        helper.runAtTickTime(1L, () -> {
            ticket[0] = f.guard().beginMeleeWindup(f.target());
            helper.assertTrue(ticket[0] > 0L,
                "fixture: a live authorized target must issue a contact ticket");
        });
        helper.runAtTickTime(2L, f.target()::discard);
        helper.runAtTickTime(1L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
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
        helper.runAtTickTime(2L, () -> f.guard().setTarget(replacement));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () -> {
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "the old target must not receive damage after target switch");
                helper.assertTrue(replacement.getHealth() == replacementHealth,
                    "the old ticket must never transfer damage to a new target");
            });
        }
        helper.runAtTickTime(3L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
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
        helper.runAtTickTime(2L, () -> f.guard().setItemSlot(
            EquipmentSlot.MAINHAND, ItemStack.EMPTY));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "removing the physical sword must cancel pending damage"));
        }
        helper.runAtTickTime(3L + GuardMeleeGoal.MELEE_CONTACT_TICK,
            helper::succeed);
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_foreign_raid_target_cancels_without_damage")
    public void foreignRaidTargetCancelsWithoutDamage(GameTestHelper helper) {
        Fixture f = fixture(helper);
        tickAt(helper, 1L, f.goal(), () -> {
        });
        helper.runAtTickTime(2L, () -> f.target().assign(UUID.randomUUID(),
            UUID.randomUUID(), RaidObjective.BLOD, 1.0F, false));
        for (int elapsed = 1; elapsed <= GuardMeleeGoal.MELEE_CONTACT_TICK;
             elapsed++) {
            tickAt(helper, 2L + elapsed, f.goal(), () ->
                helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                    "a raider rebound to another settlement must not receive this blade"));
        }
        helper.runAtTickTime(3L + GuardMeleeGoal.MELEE_CONTACT_TICK,
            helper::succeed);
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "guard_melee_contact_ticket_never_survives_reload")
    public void ticketNeverSurvivesReload(GameTestHelper helper) {
        Fixture f = fixture(helper);
        long[] oldTicket = {0L};
        SettlerEntity[] loaded = {null};

        helper.runAtTickTime(1L, () -> {
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

        helper.runAtTickTime(1L + GuardMeleeGoal.MELEE_CONTACT_TICK, () -> {
            helper.assertFalse(loaded[0].commitMeleeContact(oldTicket[0],
                    f.target()),
                "a reloaded entity must refuse the pre-save contact ticket");
            helper.assertTrue(f.target().getHealth() == f.startingHealth(),
                "reload must not replay pending melee damage");
            helper.succeed();
        });
    }
}

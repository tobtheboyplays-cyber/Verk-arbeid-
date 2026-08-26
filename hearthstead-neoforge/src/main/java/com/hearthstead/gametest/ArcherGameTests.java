package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The archer trade, end to end (owner's ask, 2026-08-25: an archer whose
 * abilities — Power Shot, Triple Shot — arrive over time).
 *
 * <p>The load-bearing rules pinned here, each of which would fail its named
 * test if broken in code:
 *
 * <ul>
 *   <li><b>Chest truth.</b> Every arrow an archer looses left the
 *       WATCHTOWER's own chest, exactly counted — and an empty tower means
 *       an archer who cannot shoot. This is the consumer end of FLOWS.md's
 *       fletcher → watchtower edge, and the first test asserts the full
 *       conservation identity, not just "the chest went down".
 *   <li><b>Doing the job makes you better at it</b> (job standard point 8):
 *       loosing arrows trains DEXTERITY, the number {@link ArcherRank#of}
 *       reads — without it, a career archer could never leave RECRUIT, the
 *       exact defect the guard progression audit found on STRENGTH.
 *   <li><b>The Power Shot cadence</b>: a SHARPSHOOTER's every-4th-shot
 *       ability actually fires on its cadence, observed through the goal's
 *       own counters (designed-for-testability seams, not reflection).
 * </ul>
 *
 * <p>Helpers mirror {@link GuardTrainingGameTests} exactly (a registered
 * settlement small enough that neighbouring arenas cannot answer for each
 * other, a valid building the hire API accepts).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArcherGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** See {@link GuardTrainingGameTests#settlement}: registered, and small,
     *  for exactly the same reasons. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Skytterholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** A valid WATCHTOWER whose bounds contain the chest the tests stock —
     *  bounds are what {@code WarehouseIndex.containers} walks. */
    private static Building tower(GameTestHelper helper, Settlement s,
                                  int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, x, z);
    }

    private static Container chestAt(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        var be = helper.getBlockEntity(rel);
        if (!(be instanceof Container c)) {
            throw new IllegalStateException("fixture: no container at " + rel);
        }
        return c;
    }

    private static int countOf(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** See {@link GuardTrainingGameTests#trainStrengthTo}: repeated small
     *  calls, so the result lands close to the target. */
    private static void trainDexterityTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.DEXTERITY) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.DEXTERITY, 5.0F, 1.0F);
        }
    }

    /**
     * The goal under test, from the entity's own selector.
     *
     * <p>{@code SettlerEntity.registerGoals} is the model-wiring worker's
     * file this cycle, so until the registration line lands there the
     * fixture arms the goal itself, at the same slot {@code GuardMeleeGoal}
     * holds (2). The lookup-first shape means these tests keep measuring the
     * one real instance — never a duplicate that would double-shoot — both
     * before and after that wiring lands.
     */
    private static ArcherAttackGoal arm(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ArcherAttackGoal existing) {
                return existing;
            }
        }
        ArcherAttackGoal goal = new ArcherAttackGoal(archer);
        archer.goalSelector.addGoal(2, goal);
        return goal;
    }

    // --------------------------------------------------- chest-true ammo ---

    /**
     * The whole trade in one arena: a hired archer, a stocked tower, a
     * raider — the raider gets hurt AND the tower's arrow count goes down,
     * and the conservation identity holds at every observed instant:
     * chest + quiver + loosed == what the chest started with. (A MARKSMAN
     * fixture, so the spread is tight, every volley is exactly one arrow,
     * and the assertion is arithmetic rather than luck.)
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void archerLoosesChestTrueArrowsAtARaider(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        rack.setItem(0, new ItemStack(Items.ARROW, 16));

        SettlerEntity archer = settler(helper, s, "Skytte", 4, 4);
        Employment.Hired hired = Employment.hire(helper.getLevel(), s, tower, archer);
        helper.assertTrue(hired.ok(), "fixture: the watchtower must hire an archer");
        helper.assertTrue(archer.getProfession() == Profession.ARCHER,
            "the watchtower's trade is ARCHER now, was " + archer.getProfession());
        trainDexterityTo(archer, ArcherRank.MARKSMAN.threshold());

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            int inChest = countOf(rack, Items.ARROW);
            helper.assertTrue(inChest + goal.quiverCount() + goal.shotsFired() == 16,
                "ammo conservation broke: chest " + inChest + " + quiver "
                    + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " != the 16 the tower started with");
            helper.assertTrue(inChest < 16,
                "the tower's own chest must be what the quiver drains");
            helper.assertTrue(pell.getHealth() < pellMax,
                "an archer with arrows and a clear shot must hurt the raider"
                    + " (still " + pell.getHealth() + "/" + pellMax
                    + " after " + goal.shotsFired() + " volleys)");
        });
    }

    /**
     * No arrows in the tower = no shooting. The mirror image of the test
     * above, and the pressure that makes the fletcher worth hiring: the
     * archer stands the post empty-handed rather than conjuring ammunition.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 400)
    public void anEmptyTowerMeansNoShots(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));  // present, empty

        SettlerEntity archer = settler(helper, s, "Tomhendt", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        float pellMax = pell.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.runAfterDelay(250, () -> {
            helper.assertTrue(goal.shotsFired() == 0,
                "no arrows in the tower must mean no shots, yet "
                    + goal.shotsFired() + " were loosed");
            helper.assertTrue(rack.isEmpty(),
                "an empty rack must stay empty -- nothing may mint arrows");
            helper.assertTrue(pell.isAlive() && pell.getHealth() >= pellMax,
                "the raider must be untouched, at " + pell.getHealth()
                    + "/" + pellMax);
            helper.succeed();
        });
    }

    // ------------------------------------------------- doing trains rank ---

    /**
     * Loosing arrows trains DEXTERITY — the number {@link ArcherRank#of}
     * reads. Counted at the moment of release (and again per arrow that
     * strikes, from the arrow's own hit hook), never on a timer: without
     * this, a career archer could never leave RECRUIT, the exact defect the
     * guard progression audit found on the STRENGTH ladder.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void loosingArrowsTrainsDexterity(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        chestAt(helper, new BlockPos(3, 1, 3))
            .setItem(0, new ItemStack(Items.ARROW, 16));

        SettlerEntity archer = settler(helper, s, "Laerling", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        int before = archer.attribute(Attribute.DEXTERITY);

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> helper.assertTrue(
            archer.attribute(Attribute.DEXTERITY) > before,
            "loosing arrows must train Dexterity -- the rank ladder reads it"
                + " (started " + before + ", still "
                + archer.attribute(Attribute.DEXTERITY) + ")"));
    }

    // ---------------------------------------------------- the power shot ---

    /**
     * A SHARPSHOOTER's Power Shot fires on its cadence: every 4th volley,
     * no more and no fewer — {@code shotsFired / 4 == powerShotsFired} is an
     * invariant at any instant, because both counters move in the same
     * release. Observed through the goal's own seams; the fixture reaches
     * DEX 35 the same way {@link GuardTrainingGameTests} reaches VETERAN.
     */
    @GameTest(batch = "archer", template = "empty16", timeoutTicks = 600)
    public void aSharpshooterFiresThePowerShotOnItsCadence(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building tower = tower(helper, s, 2, 2);
        chestAt(helper, new BlockPos(3, 1, 3))
            .setItem(0, new ItemStack(Items.ARROW, 16));

        SettlerEntity archer = settler(helper, s, "Skarpskytter", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the watchtower must hire an archer");
        trainDexterityTo(archer, ArcherRank.SHARPSHOOTER.threshold());
        helper.assertTrue(ArcherRank.of(archer).atLeast(ArcherRank.SHARPSHOOTER),
            "fixture sanity: the Power Shot needs a Sharpshooter, DEX="
                + archer.attribute(Attribute.DEXTERITY));

        RaiderEntity pell = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(13, 1, 4));
        pell.setNoAi(true);
        ArcherAttackGoal goal = arm(archer);
        archer.setTarget(pell);

        helper.succeedWhen(() -> {
            helper.assertTrue(
                goal.shotsFired() / ArcherRank.POWER_SHOT_EVERY == goal.powerShotsFired(),
                "the cadence broke: " + goal.powerShotsFired() + " power shots in "
                    + goal.shotsFired() + " volleys is not every "
                    + ArcherRank.POWER_SHOT_EVERY + "th");
            helper.assertTrue(goal.powerShotsFired() >= 1,
                "a Sharpshooter's 4th volley must be a Power Shot ("
                    + goal.shotsFired() + " volleys so far)");
        });
    }
}

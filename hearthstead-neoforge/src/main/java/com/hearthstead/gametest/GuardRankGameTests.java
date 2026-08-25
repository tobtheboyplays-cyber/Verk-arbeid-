package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The two owner asks behind {@link GuardRank}, 2026-08-25: "guards must not
 * have good armor before they upgrade — they need experience" and "I want a
 * captain whom people greet."
 *
 * <p>Each test here is one line of {@code GuardRank}'s own class doc, written
 * so that breaking the rule fails the test by name rather than by a mystery.
 * Helpers mirror {@link EmploymentGameTests} exactly (a settlement the entity
 * layer can actually find, a small radius so neighbouring arenas cannot
 * answer for each other's hearth).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardRankGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** See {@link EmploymentGameTests#settlement}: registered, and small, for
     *  exactly the same reasons. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Vaktholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }


    /**
     * An armoury holding every piece the rank ladder can ask for.
     *
     * <p>Needed since the kit became chest-true: armor is WITHDRAWN from the
     * settlement's own stores now, never conjured, so a test about who is
     * allowed to wear what has to give the settlement something to hand out.
     * Stocked generously on purpose — these two tests are about the RANK
     * rule, and an armoury that runs dry mid-promotion would be testing
     * supply instead.
     */
    private static void stockedArmoury(GameTestHelper helper, Settlement s,
                                       int x, int z) {
        BlockPos chestRel = new BlockPos(x, 1, z);
        helper.setBlock(chestRel, net.minecraft.world.level.block.Blocks.CHEST);
        BlockPos anchor = helper.absolutePos(chestRel);
        com.hearthstead.settlement.Building armoury =
            new com.hearthstead.settlement.Building(UUID.randomUUID(),
            com.hearthstead.building.BuildingType.ARMOURY,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                anchor, anchor.offset(2, 2, 2)));
        armoury.valid = true;
        s.buildings.add(armoury);
        if (helper.getLevel().getBlockEntity(anchor)
            instanceof net.minecraft.world.Container chest) {
            net.minecraft.world.item.Item[] kit = {
                Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
                Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            };
            for (int i = 0; i < kit.length; i++) {
                chest.setItem(i, new net.minecraft.world.item.ItemStack(kit[i], 2));
            }
        }
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

    /** Repeated small {@code train} calls rather than one huge one, so the
     *  result lands close to {@code target} instead of overshooting into the
     *  next tier by an unpredictable margin near the growth curve's cap. */
    private static void trainStrengthTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.STRENGTH) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.STRENGTH, 5.0F, 1.0F);
        }
    }

    // ------------------------------------------------------------- armor ---

    /**
     * "Guards must not have good armor before they upgrade — they need
     * experience." A recruit is guaranteed below {@link GuardRank#SPEARMAN}'s
     * threshold (fresh Strength caps at
     * {@link com.hearthstead.entity.SettlerAttributes#START_CAP}, well under
     * 20) so this is deterministic, not a fluke of the roll. Crossing the
     * threshold with the same attributes API the rest of the game uses must
     * dress them within {@link SettlerEntity}'s refresh window, unprompted.
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void armorArrivesOnlyAfterTheRankThatEarnsIt(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        stockedArmoury(helper, s, 10, 10);
        SettlerEntity guard = settler(helper, s, "Rekrutt", 4, 4);
        guard.assignProfession(Profession.GUARD);

        // Deterministic, not a fluke of the roll: a fresh settler's Strength
        // is capped well under SPEARMAN's threshold of 20 (SettlerAttributes
        // .START_CAP), so this is true the instant profession is assigned --
        // no need to wait a tick for it.
        helper.assertTrue(GuardRank.of(guard) == GuardRank.RECRUIT,
            "a fresh guard must not already be a Spearman, Strength="
                + guard.attribute(Attribute.STRENGTH));
        for (EquipmentSlot slot : new EquipmentSlot[] {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            helper.assertTrue(guard.getItemBySlot(slot).isEmpty(),
                "a RECRUIT must wear nothing in " + slot + ", found "
                    + guard.getItemBySlot(slot));
        }

        trainStrengthTo(guard, GuardRank.SPEARMAN.threshold());

        // Now time-dependent: SettlerEntity's equipment-refresh hook only
        // runs once a second, so this must wait for that window rather than
        // checking the very next tick.
        helper.succeedWhen(() -> {
            helper.assertTrue(GuardRank.of(guard) == GuardRank.SPEARMAN,
                "training must have earned Spearman, Strength="
                    + guard.attribute(Attribute.STRENGTH));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "a Spearman must be wearing the leather chestplate, found "
                    + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                "a Spearman's equipment table has no helmet yet, found "
                    + guard.getItemBySlot(EquipmentSlot.HEAD));
        });
    }

    /**
     * The equipment table's own class doc: no separate "best rank ever
     * reached" record, so armor tracks whatever {@link GuardRank#of} reads
     * off <i>current</i> Strength. Proven here by forcing Strength down after
     * it earned CAPTAIN's full iron, and watching the kit follow it back down
     * to VETERAN's -- never staying stuck one tier above what the guard
     * currently measures up to.
     */
    @GameTest(template = "empty16", timeoutTicks = 400)
    public void armorNeverOutstripsTheCurrentRank(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        stockedArmoury(helper, s, 10, 10);
        SettlerEntity guard = settler(helper, s, "Kaptein", 4, 4);
        guard.assignProfession(Profession.GUARD);
        trainStrengthTo(guard, GuardRank.CAPTAIN.threshold());

        // One fixed wait for the first hook cycle to dress the Captain in
        // full iron, then the demotion, then a second (polling) wait for the
        // hook to catch up and undress them back to Veteran's kit. Both
        // registrations are top-level and independent -- succeedWhen simply
        // keeps failing harmlessly, tick after tick, until the delayed
        // mutation below has actually happened and the next hook cycle has
        // caught up to it.
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(
                guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                    && guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "a Captain must be in full iron before this test can mean anything, head="
                    + guard.getItemBySlot(EquipmentSlot.HEAD) + " chest="
                    + guard.getItemBySlot(EquipmentSlot.CHEST));
            // Land exactly on 50 (VETERAN's band: 40 <= v < 60), whatever the
            // training loop's own overshoot past 80 happened to leave.
            int current = guard.attribute(Attribute.STRENGTH);
            guard.attributes().penalise(current - 50);
            helper.assertTrue(guard.attribute(Attribute.STRENGTH) == 50,
                "penalise must land exactly on the target, got "
                    + guard.attribute(Attribute.STRENGTH));
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(GuardRank.of(guard) == GuardRank.VETERAN,
                "50 Strength must read back as Veteran, not the old Captain rank");
            helper.assertTrue(!guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "the iron chestplate must not survive the demotion, found "
                    + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "VETERAN wears leather, found " + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET),
                "VETERAN's helmet is leather too, found " + guard.getItemBySlot(EquipmentSlot.HEAD));
        });
    }

    // ---------------------------------------------------------- the salute ---

    /**
     * "I want a captain whom people greet." A settler with nothing more
     * pressing to do, standing near the settlement's one guard, must turn to
     * face them inside {@link com.hearthstead.entity.ai.SaluteCaptainGoal}'s
     * pause window -- the assertable half of a cue that is otherwise a sound
     * and a pause (see that goal's own class doc for why there is no
     * AnimationState to check instead).
     */
    @GameTest(template = "empty16", timeoutTicks = 300)
    public void aNearbySettlerFacesTheCaptain(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity captain = settler(helper, s, "Kaptein", 4, 4);
        captain.assignProfession(Profession.GUARD);

        SettlerEntity onlooker = settler(helper, s, "Tilskuer", 6, 4);
        // Facing squarely away, so any convergence toward the captain is the
        // goal's doing and not a lucky spawn rotation.
        onlooker.setYRot(180.0F);
        onlooker.setYHeadRot(180.0F);
        onlooker.setYBodyRot(180.0F);

        helper.succeedWhen(() -> {
            Vec3 toCaptain = captain.position().subtract(onlooker.position()).normalize();
            Vec3 looking = onlooker.getLookAngle();
            double facing = looking.dot(toCaptain);
            helper.assertTrue(facing > 0.6,
                "the onlooker should turn to face the captain during a salute, dot="
                    + facing + " activity=" + onlooker.getActivity());
        });
    }

    /**
     * The Vaktkaptein: computed on demand from whoever the settlement's
     * loaded guards actually are right now, and the higher rank wins.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void theCaptainIsTheHighestRankedLivingGuard(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity junior = settler(helper, s, "Grønnskolling", 3, 3);
        junior.assignProfession(Profession.GUARD);
        trainStrengthTo(junior, GuardRank.SPEARMAN.threshold());

        SettlerEntity senior = settler(helper, s, "Veteran", 10, 10);
        senior.assignProfession(Profession.GUARD);
        trainStrengthTo(senior, GuardRank.VETERAN.threshold());

        helper.assertTrue(GuardRank.of(junior) == GuardRank.SPEARMAN,
            "fixture sanity: junior should read as Spearman");
        helper.assertTrue(GuardRank.of(senior).atLeast(GuardRank.VETERAN),
            "fixture sanity: senior should have outgrown Spearman");

        SettlerEntity captain = GuardRank.captainOf(
            SettlementManager.loadedMembers(helper.getLevel(), s));
        helper.assertTrue(captain == senior,
            "the higher-ranked guard must be picked as captain, got "
                + (captain == null ? "null" : captain.getSettlerName()));
        helper.succeed();
    }
}

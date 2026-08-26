package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Owner's bug report, live survival play (2026-08-26): "skaffet meg en
 * archer også. når zombier kom så beskyttet ikke archer noen ting og han
 * skjøt ikke" -- a hired watchtower archer neither protected anyone nor
 * fired a single arrow when vanilla zombies (not raiders) attacked at
 * night.
 *
 * <h2>What the audit actually found</h2>
 *
 * <p>Both {@link com.hearthstead.entity.ai.SettlerDefenseTargetGoal} (the
 * guard's target selector) and {@link ArcherAttackGoal#acquire()} (the
 * archer's own duplicated copy of that acquisition) have always searched
 * for {@code Monster.class} candidates scoped by settlement-radius
 * proximity -- never a {@code RaiderEntity}-only predicate. That has been
 * true since these goals were first written (see their own class docs and
 * git history: {@code SettlerDefenseTargetGoal} has read
 * {@code Monster.class} since the settler AI first existed; {@code
 * ArcherAttackGoal#acquire()} since the archer trade was added). A vanilla
 * {@link Zombie} was already a valid target by type on this branch, and
 * {@code HearthsteadGameTests#guardEngagesThreat} already proved the guard
 * half of that for a plain zombie with no settler nearby.
 *
 * <p>What had <b>zero</b> coverage was the two shapes the owner's report
 * actually named: an archer's shoot path firing at a vanilla hostile (every
 * existing archer GameTest uses a {@link RaiderEntity} target), and the
 * guard's protect-civilians-first priority order holding across a MIXED
 * pair -- a vanilla zombie actively attacking a settler outranking a
 * nearer, idle raider, proving the tiering in {@code
 * SettlerDefenseTargetGoal#findTarget} is about behaviour (who is
 * currently hunting a settler), not about raid membership. These two tests
 * close that gap; see the report for the full diagnosis and line
 * references.
 *
 * <h2>Follow-up: the real suspect was ammo, not aim (coordinator redirect)</h2>
 *
 * <p>The owner had just hired the archer with no fletcher in the village --
 * the watchtower rack was almost certainly empty. "No arrows means no
 * shooting" is correct (chest truth); standing at post silently, giving no
 * signal why, is not. {@link ArcherAttackGoal#reportOutOfAmmo} makes that
 * state speak (a distinct {@link SettlerActivity#OUT_OF_AMMO} activity plus
 * a once-per-episode player line); {@link
 * #archerReportsOutOfAmmoThenShootsOnceRackIsFilled} pins both the starving
 * signal and that the archer resumes on its own once the same rack is
 * restocked, no re-hire needed.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MonsterDefenseGameTests {

    // ---------------------------------------------------------- fixtures ---

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, String name, int radius) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = radius;
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

    private static RaiderEntity spawnIdleRaider(GameTestHelper helper, Settlement s, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        return raider;
    }

    private static Container chestAt(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        var be = helper.getBlockEntity(rel);
        if (!(be instanceof Container c)) {
            throw new IllegalStateException("fixture: no container at " + rel);
        }
        return c;
    }

    /** Same lookup-first pattern {@link ArcherGameTests#arm} uses: measure
     *  the real registered goal, never a duplicate that would double-shoot. */
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

    // ------------------------------------------------- archer vs zombie ---

    /**
     * The archer half of the owner's report: a hired watchtower archer with
     * a stocked rack, a settler nearby, and a vanilla {@link Zombie} inside
     * the settlement ring -- no raider anywhere in the arena. The archer
     * must find the zombie through its OWN acquisition ({@code
     * ArcherAttackGoal#acquire()}, never handed a target by the test, the
     * same discipline {@link ArcherGameTests#archerFindsAndLoosesAtARaiderWithNoHelp}
     * uses) and hurt it.
     */
    @GameTest(batch = "monster_defense", template = "empty16", timeoutTicks = 400)
    public void archerEngagesAZombieApproachingASettler(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper, "Skytterholm", 6);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));
        rack.setItem(0, new ItemStack(Items.ARROW, 16));

        SettlerEntity archer = settler(helper, s, "Speider", 4, 4);
        Employment.Hired hired = Employment.hire(helper.getLevel(), s, tower, archer);
        helper.assertTrue(hired.ok(), "fixture: the watchtower must hire an archer");
        helper.assertTrue(archer.getProfession() == Profession.ARCHER,
            "the watchtower's trade is ARCHER now, was " + archer.getProfession());
        helper.assertTrue(archer.getTarget() == null,
            "fixture sanity: nothing may hand the archer a target");

        // A settler the zombie is approaching -- the owner's actual scene:
        // hired archer on the tower, a civilian in the settlement, zombies
        // closing in at night.
        SettlerEntity civilian = settler(helper, s, "Bosetter", 6, 10);

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(13, 1, 4));
        zombie.setNoAi(true);
        float zombieMax = zombie.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);

        helper.succeedWhen(() -> {
            int inChest = 0;
            for (int slot = 0; slot < rack.getContainerSize(); slot++) {
                ItemStack stack = rack.getItem(slot);
                if (stack.is(Items.ARROW)) {
                    inChest += stack.getCount();
                }
            }
            helper.assertTrue(inChest + goal.quiverCount() + goal.shotsFired() == 16,
                "ammo conservation broke against a vanilla hostile: chest " + inChest
                    + " + quiver " + goal.quiverCount() + " + loosed " + goal.shotsFired()
                    + " != the 16 the tower started with");
            helper.assertTrue(archer.getTarget() == zombie,
                "the archer must find the zombie through its OWN goal (never "
                    + "setTarget from the test), got " + archer.getTarget());
            helper.assertTrue(zombie.isDeadOrDying() || zombie.getHealth() < zombieMax,
                "an archer with arrows and a clear shot must hurt a vanilla zombie, "
                    + "not just a raider (still " + zombie.getHealth() + "/" + zombieMax
                    + " after " + goal.shotsFired() + " volleys, civilian="
                    + civilian.getActivity() + ")");
        });
    }

    // ------------------------------------------- guard priority, mixed ---

    /**
     * {@link GuardDefenseGameTests} pins protect-civilians-first with
     * raider-vs-raider fixtures only. This proves the same tier generalises
     * across entity types: a vanilla zombie actively attacking a settler
     * still outranks a raider that is merely closer and idle -- the tier
     * read is behavioural (whose own {@code getTarget()} is a live {@link
     * SettlerEntity}), not a raid-membership check, so it was never going to
     * special-case one {@code Monster} subclass over another. Mirrors
     * {@link GuardDefenseGameTests#prefersARaiderAttackingASettlerOverANearerIdleOne}
     * with the attacking half swapped for a zombie.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "guard_defense_prefers_a_zombie_attacking_a_settler_over_a_nearer_idle_raider")
    public void guardPrefersAZombieAttackingASettlerOverANearerIdleRaider(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper, "Wardholm", 8);
        SettlerEntity guard = settler(helper, s, "Ward", 8, 8);
        guard.assignProfession(Profession.GUARD);
        SettlerEntity victim = settler(helper, s, "Civilian", 13, 8);

        RaiderEntity idleAndNear = spawnIdleRaider(helper, s, new BlockPos(9, 1, 8));
        Zombie attackingAndFar = helper.spawn(EntityType.ZOMBIE, new BlockPos(12, 1, 8));
        attackingAndFar.setTarget(victim);

        helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == attackingAndFar,
            "a guard must prefer a vanilla zombie actively attacking a settler over a "
                + "raider merely closer and idle; got " + guard.getTarget()
                + " (idle raider=" + idleAndNear + ")"));
    }

    // ------------------------------------------------ starving speaks ---

    /**
     * ARCHER-2 follow-up, coordinator redirect (2026-08-26): targeting was
     * never the defect (see this file's class doc and the report) -- the
     * owner had JUST hired the archer with no fletcher in the village, so
     * the watchtower rack was almost certainly empty, and an out-of-ammo
     * archer used to stand at post silently, giving no signal it was
     * behaving correctly rather than being broken. Two phases in one test,
     * same idiom {@link GuardDefenseGameTests#abandonsADistantFightToInterceptOneStandingOverACivilian}
     * uses for a sequenced fixture: phase one pins the starving state (empty
     * rack, live target, zero shots, the {@link SettlerActivity#OUT_OF_AMMO}
     * activity AND the announced-episode flag both true, the zombie
     * untouched) and then stocks the SAME rack -- no re-hire, no goal
     * reset. Phase two proves the archer resumes on its own: shots fired,
     * the starving activity cleared, the zombie taking damage.
     */
    @GameTest(batch = "monster_defense", template = "empty16", timeoutTicks = 600)
    public void archerReportsOutOfAmmoThenShootsOnceRackIsFilled(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper, "Tomtarn", 6);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = chestAt(helper, new BlockPos(3, 1, 3));  // present, empty

        SettlerEntity archer = settler(helper, s, "Sultenskytter", 4, 4);
        Employment.Hired hired = Employment.hire(helper.getLevel(), s, tower, archer);
        helper.assertTrue(hired.ok(), "fixture: the watchtower must hire an archer");

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(13, 1, 4));
        zombie.setNoAi(true);
        float zombieMax = zombie.getMaxHealth();
        ArcherAttackGoal goal = arm(archer);

        boolean[] refilled = {false};
        helper.succeedWhen(() -> {
            if (!refilled[0]) {
                helper.assertTrue(archer.getTarget() == zombie,
                    "setup: the archer must find the zombie through its own goal, got "
                        + archer.getTarget());
                helper.assertTrue(goal.shotsFired() == 0,
                    "setup: an empty rack must mean no shots yet, but "
                        + goal.shotsFired() + " were loosed");
                helper.assertTrue(rack.isEmpty(),
                    "setup: an empty rack must stay empty -- nothing may mint arrows");
                helper.assertTrue(zombie.isAlive() && zombie.getHealth() >= zombieMax,
                    "setup: the zombie must be untouched while the rack is empty, at "
                        + zombie.getHealth() + "/" + zombieMax);
                helper.assertTrue(
                    archer.getActivity() == SettlerActivity.OUT_OF_AMMO
                        && goal.outOfAmmoAnnounced(),
                    "an archer with a live target and no arrows must report the "
                        + "starving state, not stand there silently (activity="
                        + archer.getActivity() + ", announced="
                        + goal.outOfAmmoAnnounced() + ")");

                // The fletcher finally delivers: arrows land in the SAME
                // rack, mid-fight -- no re-hire, no goal reset.
                rack.setItem(0, new ItemStack(Items.ARROW, 16));
                refilled[0] = true;
            }
            helper.assertTrue(
                refilled[0] && goal.shotsFired() > 0
                    && archer.getActivity() != SettlerActivity.OUT_OF_AMMO
                    && zombie.getHealth() < zombieMax,
                "the archer must resume shooting on its own once the rack is filled, "
                    + "without being re-hired (shots=" + goal.shotsFired() + ", activity="
                    + archer.getActivity() + ", zombie=" + zombie.getHealth() + "/"
                    + zombieMax + ")");
        });
    }
}

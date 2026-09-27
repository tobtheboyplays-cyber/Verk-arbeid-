package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.ArcherHeightAdvantage;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Archer fire (owner, 27 Sep, native combat clip: "the archers didn't even
 * shoot"). The Battle QA fixture puts two Archers on the Watchtower roof with a
 * Hold Here (Stand) order. The Stand leash (8 blocks, measured post to threat)
 * was applied to ranged target authority, so a raider fighting the Guards
 * 11 blocks out and 5 below was never assigned to either Archer: no target, no
 * restock, no draw, silence.
 *
 * <p>These tests put a real, ordinarily-scheduled Archer (no test-set target)
 * on a raised post with a Stand order and real raiders on the ground beyond
 * the melee leash, and require an actual arrow hit. Variants: a roof lip, a
 * one-block parapet, arrows in the tower rack vs in his bag, a height-bonus
 * long shot, and the ground-level control that stays out of range.</p>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArcherFireGameTests {

    private static final int ARENA = 48;
    /** The archer's post column (x, z). */
    private static final int PX = 19, PZ = 19;

    private static Settlement settlement(GameTestHelper helper) {
        for (int x = 0; x < ARENA; x++) {
            for (int z = 0; z < ARENA; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Skuddholm",
            helper.absolutePos(new BlockPos(PX, 1, PZ + 6)));
        s.radius = 24;
        data.settlements.put(s.id, s);
        data.setDirty();
        helper.getLevel().setDayTime(1000); // nobody asleep on a watch rota
        return s;
    }

    /** A solid platform whose top surface is at y=height; the post stands on it. */
    private static void platform(GameTestHelper helper, int height, int halfWidth) {
        for (int x = PX - halfWidth; x <= PX + halfWidth; x++) {
            for (int z = PZ - halfWidth; z <= PZ + halfWidth; z++) {
                for (int y = 1; y <= height; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
                }
            }
        }
    }

    private static Building tower(GameTestHelper helper, Settlement s) {
        return GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 10, 10);
    }

    private static Container rack(GameTestHelper helper, int arrows) {
        BlockPos rel = new BlockPos(11, 1, 11);
        helper.setBlock(rel, Blocks.CHEST);
        Container chest = (Container) helper.getBlockEntity(rel);
        if (arrows > 0) chest.setItem(0, new ItemStack(Items.ARROW, arrows));
        return chest;
    }

    /** A hired, bow-armed Archer at the post with an authored Stand order. */
    private static SettlerEntity postedArcher(GameTestHelper helper, Settlement s,
                                              Building tower, int standY) {
        BlockPos rel = new BlockPos(PX, standY, PZ);
        SettlerEntity archer = helper.spawn(ModEntities.SETTLER.get(), rel);
        archer.setSettlerName("Roof Archer");
        archer.bindTo(s.id, s.center);
        s.putRecord(archer.getUUID(), archer.getSettlerName(), Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, archer).ok(),
            "fixture: the Watchtower hires the Archer");
        helper.assertTrue(archer.getProfession() == Profession.ARCHER, "fixture: Archer trade");
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        Development.of(helper.getLevel(), s);
        GuardOrder order = s.guardOrders.orderForMutation(s.id, archer.getUUID(),
            helper.getLevel().dimension().location()).orElseThrow();
        helper.assertTrue(order.issueStand(helper.absolutePos(rel), Direction.SOUTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, UUID.randomUUID(), tower.id,
                helper.getLevel().getGameTime()),
            "fixture: Hold Here on the raised post, exactly like the Battle QA roof");
        return archer;
    }

    /** A settlement-bound raider standing on the ground; health raised so it survives the count. */
    private static RaiderEntity raider(GameTestHelper helper, Settlement s, int x, int z,
                                       boolean ai) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(x, 1, z));
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        raider.setNoAi(!ai);
        raider.setPersistenceRequired();
        var maxHealth = raider.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(1000.0D);
        raider.setHealth(1000.0F);
        return raider;
    }

    private static ArcherAttackGoal goal(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ArcherAttackGoal existing) return existing;
        }
        throw new IllegalStateException("the Archer has no ArcherAttackGoal");
    }

    private static String diag(SettlerEntity archer, ArcherAttackGoal goal) {
        StringBuilder running = new StringBuilder();
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning()) running.append(wrapped.getGoal().getClass().getSimpleName()).append(' ');
        }
        return "[activity=" + archer.getActivity() + " target="
            + (archer.getTarget() == null ? "none" : archer.getTarget().getType().toShortString())
            + " pos=" + archer.blockPosition() + " quiver=" + goal.quiverCount()
            + " shots=" + goal.shotsFired() + " goals=" + running.toString().trim() + "]";
    }

    /** Counts real arrow hits by the archer: a tick-over-tick health drop credited to him. */
    private static final class HitCounter {
        float lastHealth;
        int hits;
        HitCounter(RaiderEntity raider) { lastHealth = raider.getHealth(); }
        void tick(RaiderEntity raider, SettlerEntity archer) {
            float now = raider.getHealth();
            if (now < lastHealth && raider.getLastHurtByMob() == archer) hits++;
            lastHealth = now;
        }
    }

    // ------------------------------------------------------------ tests ---

    /**
     * The clip, headless: a Stand-posted Archer on a roof 4 high, 2 blocks
     * back from the lip, arrows only in the Watchtower rack below, a raider 13
     * blocks out on the ground (post-to-raider 13.6 > the 8/12 melee leash).
     * He must restock from the rack, draw, and hit -- with no test-set target.
     */
    @GameTest(batch = "archer_fire", template = "empty64", timeoutTicks = 600)
    public void roofArcherBehindLipShootsRaiderBeyondMeleeLeash(GameTestHelper helper) {
        Settlement s = settlement(helper);
        platform(helper, 4, 2); // 5x5 roof: the lip runs 2 blocks past the post
        Building tower = tower(helper, s);
        Container rack = rack(helper, 32);
        SettlerEntity archer = postedArcher(helper, s, tower, 5);
        RaiderEntity raider = raider(helper, s, PX, PZ + 13, false);
        ArcherAttackGoal bow = goal(archer);
        HitCounter hits = new HitCounter(raider);
        helper.onEachTick(() -> {
            hits.tick(raider, archer);
            helper.assertTrue(archer.getY() >= helper.absolutePos(new BlockPos(0, 5, 0)).getY() - 0.01D,
                "the Archer must hold the roof, not climb down to chase " + diag(archer, bow));
            if (hits.hits > 0 && bow.shotsFired() > 0) {
                int rackLeft = 0;
                for (int i = 0; i < rack.getContainerSize(); i++) {
                    if (rack.getItem(i).is(Items.ARROW)) rackLeft += rack.getItem(i).getCount();
                }
                helper.assertTrue(rackLeft + bow.quiverCount() + bow.shotsFired() == 32,
                    "every loosed arrow came out of the rack (conservation)");
                helper.succeed();
            }
        });
        helper.runAtTickTime(590, () -> helper.fail(
            "roof Archer never hit the raider 13 blocks out " + diag(archer, bow)));
    }

    /**
     * A one-block parapet at the roof edge right in front of him, arrows
     * carried in his own bag, a raider standing in the open 14 blocks out and
     * 4 below. He aims over the parapet from standing eye height and hits.
     * (A raider hugging the foot of the tower is out of any bow's sight line,
     * as in vanilla; that one is the Guards'.)
     */
    @GameTest(batch = "archer_fire", template = "empty64", timeoutTicks = 600)
    public void archerBehindParapetShootsRaiderInTheOpen(GameTestHelper helper) {
        Settlement s = settlement(helper);
        platform(helper, 4, 1);
        for (int x = PX - 1; x <= PX + 1; x++) {
            helper.setBlock(new BlockPos(x, 5, PZ + 1), Blocks.STONE_BRICKS);
        }
        Building tower = tower(helper, s);
        rack(helper, 0);
        SettlerEntity archer = postedArcher(helper, s, tower, 5);
        archer.bag.setItem(0, new ItemStack(Items.ARROW, 32));
        RaiderEntity raider = raider(helper, s, PX + 2, PZ + 14, false);
        ArcherAttackGoal bow = goal(archer);
        helper.assertTrue(archer.hasLineOfSight(raider),
            "fixture: from standing eye height the raider is visible over the parapet");
        HitCounter hits = new HitCounter(raider);
        helper.onEachTick(() -> {
            hits.tick(raider, archer);
            if (hits.hits > 0 && bow.shotsFired() > 0) helper.succeed();
        });
        helper.runAtTickTime(590, () -> helper.fail(
            "Archer behind a parapet never hit the raider in the open " + diag(archer, bow)));
    }

    /**
     * Owner addition (height bonus): on a 6-high tower the Archer reaches a
     * raider 23 blocks out (beyond the ordinary 18) and, shooting from height,
     * actually hits: at least half of at least 10 volleys land.
     */
    @GameTest(batch = "archer_fire", template = "empty64", timeoutTicks = 1000)
    public void towerArcherHeightBonusReachesAndHitsAt23Blocks(GameTestHelper helper) {
        Settlement s = settlement(helper);
        platform(helper, 6, 1);
        Building tower = tower(helper, s);
        rack(helper, 0);
        SettlerEntity archer = postedArcher(helper, s, tower, 7);
        archer.bag.setItem(0, new ItemStack(Items.ARROW, 64));
        RaiderEntity raider = raider(helper, s, PX, PZ + 23, false);
        ArcherAttackGoal bow = goal(archer);
        helper.assertTrue(ArcherHeightAdvantage.bonusRange(6.0D) >= 5.0D - 1.0E-6D,
            "six blocks of height buys the ordinary range at least five blocks");
        HitCounter hits = new HitCounter(raider);
        int[] settleAt = {-1};
        helper.onEachTick(() -> {
            hits.tick(raider, archer);
            if (bow.shotsFired() >= 10 && settleAt[0] < 0) settleAt[0] = (int) helper.getTick() + 30;
            if (settleAt[0] >= 0 && helper.getTick() >= settleAt[0]) {
                int shots = bow.shotsFired();
                Hearthstead.LOGGER.info("ARCHER_FIRE_HEIGHT_HITRATE hits={} shots={}", hits.hits, shots);
                helper.assertTrue(hits.hits * 100 >= shots * 50,
                    "from height the Archer must hit at least half: " + hits.hits + "/" + shots
                        + " " + diag(archer, bow));
                helper.succeed();
            }
        });
        helper.runAtTickTime(990, () -> helper.fail(
            "tower Archer did not get 10 volleys off at 23 blocks (hits " + hits.hits + ") "
                + diag(archer, bow)));
    }

    /**
     * Balance control: the same posted Archer on level ground never looses at
     * a raider 23 blocks out -- beyond the ordinary 18 (+Perception) range.
     */
    @GameTest(batch = "archer_fire", template = "empty64", timeoutTicks = 260)
    public void groundArcherDoesNotFireAt23Blocks(GameTestHelper helper) {
        Settlement s = settlement(helper);
        Building tower = tower(helper, s);
        rack(helper, 0);
        SettlerEntity archer = postedArcher(helper, s, tower, 1);
        archer.bag.setItem(0, new ItemStack(Items.ARROW, 64));
        RaiderEntity raider = raider(helper, s, PX, PZ + 23, false);
        ArcherAttackGoal bow = goal(archer);
        helper.onEachTick(() -> {
            helper.assertTrue(bow.shotsFired() == 0,
                "a ground Archer must not loose at 23 blocks " + diag(archer, bow));
            helper.assertTrue(raider.getHealth() >= 1000.0F, "the out-of-range raider stays unhurt");
        });
        helper.runAtTickTime(240, helper::succeed);
    }
}

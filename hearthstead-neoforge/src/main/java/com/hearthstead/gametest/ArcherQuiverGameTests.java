package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.ArcherResupplyGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Owner, 27 Sep: "archers kan baere 6 piler, sa ma de lope a hente piler i
 * piltonna" -- a Recruit carries six arrows, rank carries more, a dry archer
 * holds the line in battle (arrow bubble, no chat) and refills at the
 * Watchtower rack out of combat. Every arrow is conserved:
 * rack + quiver + loosed == what the rack started with.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArcherQuiverGameTests {
    private static final int STOCK = 16;

    // ---------------------------------------------------------- fixtures ---

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Koggerholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        helper.getLevel().setDayTime(1000);
        return s;
    }

    private static SettlerEntity archer(GameTestHelper helper, Settlement s, Building tower,
                                        String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, tower, settler).ok(),
            "fixture: the watchtower must hire an archer");
        settler.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        return settler;
    }

    private static Container rack(GameTestHelper helper, int arrows) {
        BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, Blocks.CHEST);
        if (!(helper.getBlockEntity(rel) instanceof Container c)) {
            throw new IllegalStateException("fixture: no rack chest");
        }
        if (arrows > 0) {
            c.setItem(1, new ItemStack(Items.ARROW, arrows));
        }
        return c;
    }

    private static int countOf(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (c.getItem(slot).is(item)) {
                total += c.getItem(slot).getCount();
            }
        }
        return total;
    }

    private static ArcherAttackGoal goal(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ArcherAttackGoal existing) {
                return existing;
            }
        }
        ArcherAttackGoal goal = new ArcherAttackGoal(archer);
        archer.goalSelector.addGoal(2, goal);
        return goal;
    }

    private static boolean resupplyRunning(SettlerEntity archer) {
        for (WrappedGoal wrapped : archer.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning() && wrapped.getGoal() instanceof ArcherResupplyGoal) {
                return true;
            }
        }
        return false;
    }

    private static RaiderEntity dummy(GameTestHelper helper, Settlement s, int x, int z) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(x, 1, z));
        raider.setNoAi(true);
        // Combat targeting rejects invulnerable entities. A durable, damageable
        // stationary enemy keeps these ammo tests in the real combat path.
        raider.assign(UUID.randomUUID(), s.id, com.hearthstead.settlement.raid.RaidObjective.BLOD, 1.0F, false);
        raider.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000.0D);
        raider.setHealth(1000.0F);
        return raider;
    }

    private static void trainDexterityTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.DEXTERITY) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.DEXTERITY, 5.0F, 1.0F);
        }
    }

    // ------------------------------------------------------------- tests ---

    /** A Recruit carries six: he looses six, then refills to six at the rack. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 900)
    public void archerQuiverSixShotsThenRefill(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, STOCK);
        SettlerEntity archer = archer(helper, s, tower, "Seksern", 4, 4);
        helper.assertTrue(ArcherRank.of(archer) == ArcherRank.RECRUIT,
            "fixture: a fresh archer is a Recruit");
        helper.assertTrue(ArcherAttackGoal.quiverCapacity(archer) == 6,
            "a Recruit carries 6, got " + ArcherAttackGoal.quiverCapacity(archer));
        ArcherAttackGoal goal = goal(archer);
        RaiderEntity pell = dummy(helper, s, 13, 4);
        archer.setTarget(pell);
        int[] maxQuiver = {0};
        helper.onEachTick(() -> {
            maxQuiver[0] = Math.max(maxQuiver[0], goal.quiverCount());
            helper.assertTrue(goal.quiverCount() <= 6,
                "a Recruit never carries more than 6, got " + goal.quiverCount());
            helper.assertTrue(countOf(rack, Items.ARROW) + goal.quiverCount() + goal.shotsFired() == STOCK,
                "conservation: rack " + countOf(rack, Items.ARROW) + " + quiver " + goal.quiverCount()
                    + " + loosed " + goal.shotsFired() + " != " + STOCK);
        });
        helper.succeedWhen(() -> helper.assertTrue(goal.shotsFired() >= 7 && maxQuiver[0] == 6,
            "six loosed, then a second handful from the rack (shots=" + goal.shotsFired()
                + " max=" + maxQuiver[0] + " rack=" + countOf(rack, Items.ARROW) + ")"));
    }

    /** Rank carries more: a Sharpshooter's quiver holds ten; a level-2 tower adds two. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 600)
    public void archerQuiverSharpshooterCarriesTen(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, STOCK);
        SettlerEntity archer = archer(helper, s, tower, "Tiern", 4, 4);
        trainDexterityTo(archer, ArcherRank.SHARPSHOOTER.threshold());
        helper.assertTrue(ArcherRank.of(archer) == ArcherRank.SHARPSHOOTER,
            "fixture: a Sharpshooter");
        helper.assertTrue(ArcherAttackGoal.quiverCapacity(archer) == 10,
            "a Sharpshooter carries 10, got " + ArcherAttackGoal.quiverCapacity(archer));
        helper.succeedWhen(() -> {
            helper.assertTrue(archer.archerQuiverCount() == 10 && archer.archerQuiverOwnedBy(tower.id),
                "out of combat he refills to 10 at the rack, has " + archer.archerQuiverCount());
            helper.assertTrue(countOf(rack, Items.ARROW) == STOCK - 10,
                "exactly ten left the rack, rack=" + countOf(rack, Items.ARROW));
            tower.level = 2;
            helper.assertTrue(ArcherAttackGoal.quiverCapacity(archer) == 12,
                "a level-2 Watchtower adds 2");
            tower.level = 1;
        });
    }

    /**
     * Holding the line: a dry archer away from the rack, with a live target,
     * does not leave his place. He shows the out-of-arrows cue, looses nothing,
     * and only once the fight is over walks to the rack and refills.
     */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 1000)
    public void archerQuiverDryArcherHoldsThenRefillsAfterTheFight(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, STOCK);
        SettlerEntity archer = archer(helper, s, tower, "Standhaftig", 12, 12);
        tower.bounds = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(3, 4, 3)));
        ArcherAttackGoal goal = goal(archer);
        RaiderEntity pell = dummy(helper, s, 12, 3);
        archer.setTarget(pell);
        Vec3 start = archer.position();
        boolean[] fightOver = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(countOf(rack, Items.ARROW) + goal.quiverCount() + goal.shotsFired() == STOCK,
                "conservation");
            if (!fightOver[0]) {
                helper.assertTrue(goal.shotsFired() == 0, "a dry archer looses nothing");
                helper.assertTrue(archer.position().distanceTo(start) < 2.5D,
                    "a dry archer in battle holds his place, moved to " + archer.position());
                helper.assertTrue(!resupplyRunning(archer), "no refill trip mid-fight");
            }
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(goal.outOfAmmoAnnounced()
                    && archer.getActivity() == SettlerActivity.OUT_OF_AMMO,
                "the dry cue (arrow bubble) is up: activity=" + archer.getActivity());
            helper.assertTrue(countOf(rack, Items.ARROW) == STOCK, "nothing borrowed yet");
            fightOver[0] = true;
            pell.discard();
            archer.setTarget(null);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(fightOver[0], "still fighting");
            int cap = ArcherAttackGoal.quiverCapacity(archer);
            helper.assertTrue(archer.archerQuiverCount() == cap && archer.archerQuiverOwnedBy(tower.id),
                "after the fight he walked to the rack and refilled to " + cap + ", has "
                    + archer.archerQuiverCount() + " at " + archer.blockPosition());
            helper.assertTrue(countOf(rack, Items.ARROW) == STOCK - cap, "exact custody");
        });
    }

    /** An empty rack: the dry cue shows, nobody loops back and forth to it. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 700)
    public void archerQuiverEmptyRackNoLoop(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, 0);
        SettlerEntity archer = archer(helper, s, tower, "Tomkogger", 4, 4);
        ArcherAttackGoal goal = goal(archer);
        RaiderEntity pell = dummy(helper, s, 13, 4);
        archer.setTarget(pell);
        int[] resupplyTicks = {0};
        helper.onEachTick(() -> {
            if (resupplyRunning(archer)) resupplyTicks[0]++;
            helper.assertTrue(goal.shotsFired() == 0 && countOf(rack, Items.ARROW) == 0
                    && goal.quiverCount() == 0, "an empty rack mints nothing");
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(goal.outOfAmmoAnnounced()
                    && archer.getActivity() == SettlerActivity.OUT_OF_AMMO,
                "the dry cue is up, activity=" + archer.getActivity());
            pell.discard();
            archer.setTarget(null);
        });
        helper.runAfterDelay(650, () -> {
            helper.assertTrue(resupplyTicks[0] == 0,
                "an empty rack must not start refill trips, ran " + resupplyTicks[0] + " ticks");
            helper.succeed();
        });
    }

    /**
     * The player's RESUPPLY field order: a dry archer holding a field-order
     * line runs to the Watchtower rack, refills to capacity (custody exact),
     * returns to his slot with his LINE order untouched, and looses again.
     */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 1200)
    public void archerQuiverResupplyCallRefillsAndReturns(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, STOCK);
        SettlerEntity archer = archer(helper, s, tower, "Etterfyll", 12, 12);
        tower.bounds = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(3, 4, 3)));
        @SuppressWarnings("removal")
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
            player.connection.getConnection());
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(new BlockPos(12, 1, 10));
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        BlockPos slotPos = helper.absolutePos(new BlockPos(12, 1, 12));
        com.hearthstead.settlement.guard.FieldOrders.Result line = com.hearthstead.settlement.guard.FieldOrders.issue(
            player, new com.hearthstead.network.FieldOrderRequestPayload(
                com.hearthstead.settlement.guard.FieldOrderRules.Group.ARCHERS.wireId(),
                com.hearthstead.settlement.guard.FieldOrderRules.Kind.LINE.wireId(), slotPos, 4, 1, -1));
        helper.assertTrue(line.accepted(), "fixture: the archer takes a LINE order, got " + line.refusal());
        ArcherAttackGoal goal = goal(archer);
        RaiderEntity pell = dummy(helper, s, 12, 3);
        archer.setTarget(pell);
        int cap = ArcherAttackGoal.quiverCapacity(archer);
        boolean[] called = {false};
        boolean[] refilled = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(countOf(rack, Items.ARROW) + goal.quiverCount() + goal.shotsFired() == STOCK,
                "conservation");
            if (goal.quiverCount() == cap) refilled[0] = true;
            if (!called[0]) {
                helper.assertTrue(goal.shotsFired() == 0, "dry before the call");
            }
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(archer.getActivity() == SettlerActivity.OUT_OF_AMMO,
                "dry in the line, bubble up: " + archer.getActivity());
            com.hearthstead.settlement.guard.FieldOrders.Result call = com.hearthstead.settlement.guard.FieldOrders.issue(
                player, new com.hearthstead.network.FieldOrderRequestPayload(
                    com.hearthstead.settlement.guard.FieldOrderRules.Group.ARCHERS.wireId(),
                    com.hearthstead.settlement.guard.FieldOrderRules.Kind.RESUPPLY.wireId(),
                    com.hearthstead.network.FieldOrderRequestPayload.NO_POS, 4, 1, -1));
            helper.assertTrue(call.accepted() && ArcherResupplyGoal.called(archer),
                "RESUPPLY is accepted and calls the archer, got " + call.refusal());
            called[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(called[0] && refilled[0], "refilled to " + cap + " at the rack");
            com.hearthstead.settlement.guard.FieldOrders.Assignment held =
                com.hearthstead.settlement.guard.FieldOrders.assignment(archer);
            helper.assertTrue(held != null && held.order.kind
                    == com.hearthstead.settlement.guard.FieldOrderRules.Kind.LINE,
                "the LINE order is untouched by the call");
            helper.assertTrue(archer.blockPosition().distSqr(held.slot()) <= 16.0D,
                "he returned to his slot " + held.slot() + ", is at " + archer.blockPosition());
            helper.assertTrue(goal.shotsFired() > 0, "and looses again from the line");
            com.hearthstead.settlement.guard.FieldOrders.release(archer);
        });
    }

    // ------------------------------------------------------ arrow barrel ---

    private static com.hearthstead.block.ArrowBarrelBlockEntity barrel(GameTestHelper helper, BlockPos rel, int arrows) {
        helper.setBlock(rel, com.hearthstead.registry.ModBlocks.ARROW_BARREL.get());
        if (!(helper.getBlockEntity(rel) instanceof com.hearthstead.block.ArrowBarrelBlockEntity b)) {
            throw new IllegalStateException("fixture: no arrow barrel at " + rel);
        }
        if (arrows > 0) b.setItem(0, new ItemStack(Items.ARROW, arrows));
        return b;
    }

    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer commander(GameTestHelper helper, BlockPos rel) {
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
            player.connection.getConnection());
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(rel);
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        return player;
    }

    /** The barrel holds plain arrows only, through the container and through its menu. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 40)
    public void archerQuiverBarrelAcceptsOnlyArrows(GameTestHelper helper) {
        floor(helper);
        com.hearthstead.block.ArrowBarrelBlockEntity b = barrel(helper, new BlockPos(5, 1, 5), 0);
        helper.assertTrue(b.canPlaceItem(0, new ItemStack(Items.ARROW)), "arrows go in");
        helper.assertTrue(!b.canPlaceItem(0, new ItemStack(Items.BOW)), "a bow does not");
        helper.assertTrue(!b.canPlaceItem(0, new ItemStack(Items.TIPPED_ARROW)), "tipped arrows do not");
        helper.assertTrue(!b.canPlaceItem(0, new ItemStack(Items.WHEAT)), "wheat does not");
        net.minecraft.server.level.ServerPlayer player = commander(helper, new BlockPos(6, 1, 5));
        com.hearthstead.menu.ArrowBarrelMenu menu = new com.hearthstead.menu.ArrowBarrelMenu(1,
            player.getInventory(), b, helper.absolutePos(new BlockPos(5, 1, 5)));
        helper.assertTrue(menu.getSlot(0).mayPlace(new ItemStack(Items.ARROW, 5))
                && !menu.getSlot(0).mayPlace(new ItemStack(Items.BOW)),
            "the menu slot takes arrows only");
        player.getInventory().setItem(0, new ItemStack(Items.BOW));
        int bowSlot = com.hearthstead.block.ArrowBarrelBlockEntity.SLOTS + 27; // hotbar slot 0
        menu.quickMoveStack(player, bowSlot);
        helper.assertTrue(b.isEmpty() && player.getInventory().getItem(0).is(Items.BOW),
            "shift-click cannot put a bow in the barrel");
        helper.assertTrue(com.hearthstead.settlement.techtree.TechRecipeGates.nodesFor(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("hearthstead", "arrow_barrel"),
                com.hearthstead.registry.ModItems.ARROW_BARREL.get()).contains("arm_the_watch"),
            "the Arrow Barrel recipe is gated by Arm the Watch (the Watchtower node)");
        helper.succeed();
    }

    /** Out of combat an archer prefers the settlement Arrow Barrel to the tower rack. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 800)
    public void archerQuiverAutoRefillPrefersTheBarrel(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, STOCK);
        com.hearthstead.block.ArrowBarrelBlockEntity b = barrel(helper, new BlockPos(10, 1, 10), STOCK);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(com.hearthstead.block.ArrowBarrelBlockEntity.loaded(helper.getLevel())
                    .contains(helper.absolutePos(new BlockPos(10, 1, 10))),
                "fixture: barrel is loaded before the archer begins choosing a source");
            SettlerEntity archer = archer(helper, s, tower, "Toenne", 12, 12);
            helper.assertTrue(helper.absolutePos(new BlockPos(10, 1, 10)).equals(
                    ArcherResupplyGoal.nearestBarrel(helper.getLevel(), archer)),
                "fixture: this stocked barrel is the nearest loaded source");
            helper.succeedWhen(() -> {
                int cap = ArcherAttackGoal.quiverCapacity(archer);
                helper.assertTrue(archer.archerQuiverCount() == cap && b.arrows() == STOCK - cap,
                    "refilled " + archer.archerQuiverCount() + "/" + cap + " from the barrel (barrel "
                        + b.arrows() + ")");
                helper.assertTrue(countOf(rack, Items.ARROW) == STOCK, "the rack is untouched");
            });
        });
    }

    /**
     * The barrel button: a dry archer holding a field-order line runs to
     * THIS barrel, refills (custody exact), returns to his slot and looses
     * again. An empty barrel refuses; a second press inside the cooldown is refused.
     */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 1200)
    public void archerQuiverBarrelCallRefillsAndReturns(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, 0);
        BlockPos barrelRel = new BlockPos(6, 1, 9);
        com.hearthstead.block.ArrowBarrelBlockEntity b = barrel(helper, barrelRel, 0);
        SettlerEntity archer = archer(helper, s, tower, "Kalla", 12, 12);
        net.minecraft.server.level.ServerPlayer player = commander(helper, new BlockPos(7, 1, 9));
        BlockPos slotPos = helper.absolutePos(new BlockPos(12, 1, 12));
        com.hearthstead.settlement.guard.FieldOrders.Result line = com.hearthstead.settlement.guard.FieldOrders.issue(
            player, new com.hearthstead.network.FieldOrderRequestPayload(
                com.hearthstead.settlement.guard.FieldOrderRules.Group.ARCHERS.wireId(),
                com.hearthstead.settlement.guard.FieldOrderRules.Kind.LINE.wireId(), slotPos, 4, 1, -1));
        helper.assertTrue(line.accepted(), "fixture: the archer takes a LINE order, got " + line.refusal());
        ArcherAttackGoal goal = goal(archer);
        RaiderEntity pell = dummy(helper, s, 12, 3);
        archer.setTarget(pell);
        int cap = ArcherAttackGoal.quiverCapacity(archer);
        boolean[] called = {false};
        boolean[] refilled = {false};
        helper.onEachTick(() -> {
            if (called[0]) {
                helper.assertTrue(b.arrows() + countOf(rack, Items.ARROW) + goal.quiverCount()
                        + goal.shotsFired() == STOCK, "conservation: barrel " + b.arrows() + " quiver "
                        + goal.quiverCount() + " loosed " + goal.shotsFired());
            } else {
                helper.assertTrue(goal.shotsFired() == 0, "dry before the call");
            }
            if (goal.quiverCount() == cap) refilled[0] = true;
        });
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(com.hearthstead.settlement.guard.ArrowBarrelCall.call(player,
                    helper.absolutePos(barrelRel)).outcome()
                    == com.hearthstead.settlement.guard.ArrowBarrelCall.Outcome.EMPTY,
                "an empty barrel refuses the call");
            b.setItem(0, new ItemStack(Items.ARROW, STOCK));
            var sent = com.hearthstead.settlement.guard.ArrowBarrelCall.call(player, helper.absolutePos(barrelRel));
            helper.assertTrue(sent.outcome() == com.hearthstead.settlement.guard.ArrowBarrelCall.Outcome.SENT
                    && sent.called() == 1 && ArcherResupplyGoal.called(archer),
                "the call sends the one short archer, got " + sent);
            helper.assertTrue(com.hearthstead.settlement.guard.ArrowBarrelCall.call(player,
                    helper.absolutePos(barrelRel)).outcome()
                    == com.hearthstead.settlement.guard.ArrowBarrelCall.Outcome.COOLDOWN,
                "a second press inside 5 s is refused");
            called[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(called[0] && refilled[0] && b.arrows() + goal.shotsFired() + goal.quiverCount() == STOCK
                    && b.arrows() == STOCK - cap,
                "refilled to " + cap + " from THIS barrel (barrel " + b.arrows() + ")");
            com.hearthstead.settlement.guard.FieldOrders.Assignment held =
                com.hearthstead.settlement.guard.FieldOrders.assignment(archer);
            helper.assertTrue(held != null && held.order.kind
                    == com.hearthstead.settlement.guard.FieldOrderRules.Kind.LINE,
                "the LINE order is untouched by the call");
            helper.assertTrue(archer.blockPosition().distSqr(held.slot()) <= 16.0D,
                "he returned to his slot " + held.slot() + ", is at " + archer.blockPosition());
            helper.assertTrue(goal.shotsFired() > 0, "and looses again from the line");
            com.hearthstead.settlement.guard.FieldOrders.release(archer);
        });
    }

    /** Another consumer empties the barrel mid-trip: a dry archer still rejoins his retained line. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 1200)
    public void archerQuiverEmptySourceAfterDepartureStillReturns(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        rack(helper, 0);
        BlockPos barrelRel = new BlockPos(7, 1, 12);
        com.hearthstead.block.ArrowBarrelBlockEntity b = barrel(helper, barrelRel, STOCK);
        SettlerEntity archer = archer(helper, s, tower, "Tomtur", 13, 12);
        var player = commander(helper, new BlockPos(7, 1, 10));
        BlockPos slot = helper.absolutePos(new BlockPos(13, 1, 12));
        var order = com.hearthstead.settlement.guard.FieldOrders.issue(player,
            new com.hearthstead.network.FieldOrderRequestPayload(
                com.hearthstead.settlement.guard.FieldOrderRules.Group.ARCHERS.wireId(),
                com.hearthstead.settlement.guard.FieldOrderRules.Kind.LINE.wireId(), slot, 4, 1, -1));
        helper.assertTrue(order.accepted(), "fixture: LINE accepted");
        ArcherAttackGoal attack = goal(archer);
        RaiderEntity enemy = dummy(helper, s, 13, 3);
        archer.setTarget(enemy);
        boolean[] called = {false};
        boolean[] emptied = {false};
        boolean[] returned = {false};
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(ArcherResupplyGoal.callTo(java.util.List.of(archer),
                helper.getLevel().getGameTime(), helper.absolutePos(barrelRel)) == 1,
                "fixture: one dry archer called");
            called[0] = true;
        });
        helper.onEachTick(() -> {
            if (called[0]) {
                helper.assertTrue(enemy.isAlive() && !enemy.isInvulnerable() && archer.getTarget() == enemy,
                    "fixture: live combat tick=" + helper.getTick() + " alive=" + enemy.isAlive() + " invul=" + enemy.isInvulnerable() + " target=" + archer.getTarget() + " archer=" + archer.position() + " enemy=" + enemy.position());
            }
            if (called[0] && !emptied[0] && archer.blockPosition().distSqr(slot) > 9.0D) {
                helper.assertTrue(b.takeArrows(STOCK) == STOCK,
                    "fixture: another consumer takes all arrows while archer is en route");
                emptied[0] = true;
            }
            if (emptied[0] && ArcherResupplyGoal.returning(archer) && !returned[0]) {
                helper.assertTrue(archer.blockPosition().distSqr(slot) > 16.0D,
                    "fixture: failed trip must actually require movement back to the line");
                returned[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(emptied[0] && returned[0], "empty trip must enter its return leg");
            var held = com.hearthstead.settlement.guard.FieldOrders.assignment(archer);
            helper.assertTrue(held != null && held.order.kind
                == com.hearthstead.settlement.guard.FieldOrderRules.Kind.LINE, "LINE retained");
            helper.assertTrue(archer.blockPosition().distSqr(held.slot()) <= 16.0D
                && !ArcherResupplyGoal.returning(archer), "dry archer returns to line before holding");
            helper.assertTrue(attack.quiverCount() == 0 && attack.shotsFired() == 0 && b.arrows() == 0,
                "no arrows minted and no shots on the empty trip");
            com.hearthstead.settlement.guard.FieldOrders.release(archer);
        });
    }

    /** Standing down puts the unspent arrows back in the rack, exactly. */
    @GameTest(batch = "archer_quiver_", template = "empty16", timeoutTicks = 500)
    public void archerQuiverStandDownReturnsUnspent(GameTestHelper helper) {
        floor(helper);
        Settlement s = settlement(helper);
        Building tower = GameTestFixtures.register(helper, s, BuildingType.WATCHTOWER, 2, 2);
        Container rack = rack(helper, STOCK);
        SettlerEntity archer = archer(helper, s, tower, "Hjemvender", 4, 4);
        ArcherAttackGoal goal = goal(archer);
        RaiderEntity pell = dummy(helper, s, 13, 4);
        archer.setTarget(pell);
        boolean[] stoodDown = {false};
        long[] stoodDownAt = {0L};
        helper.onEachTick(() -> {
            if (!stoodDown[0] && goal.shotsFired() >= 1 && goal.quiverCount() > 0) {
                stoodDown[0] = true;
                stoodDownAt[0] = helper.getTick();
                pell.discard();
                archer.setTarget(null);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stoodDown[0], "fixture: first volley pending");
            helper.assertTrue(helper.getTick() - stoodDownAt[0] < ArcherResupplyGoal.CALM_TICKS,
                "checked before any peacetime refill");
            helper.assertTrue(goal.quiverCount() == 0 && archer.archerQuiverSourceBuildingId() == null,
                "the unspent arrows left the quiver, has " + goal.quiverCount());
            helper.assertTrue(countOf(rack, Items.ARROW) == STOCK - goal.shotsFired(),
                "rack " + countOf(rack, Items.ARROW) + " == " + STOCK + " - loosed " + goal.shotsFired());
        });
    }
}

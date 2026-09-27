package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.TechTreeNetwork;
import com.hearthstead.network.TechTreeSnapshotPayload;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeConfig;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.hearthstead.settlement.techtree.effects.CrownEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tech tree v3 core contracts (batch {@code techtree_core}): learn pays Coins
 * and goods exactly once and the effect turns on; pick-one choices block
 * both ways (also on the old purchase path); a co-op second player sees the
 * shared tree; study time finishes; the Village Charter stamps itself; a
 * pre-v3 save loads with its v3 fields defaulted. Never touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechTreeCoreGameTests {

    public TechTreeCoreGameTests() {
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void learnPaysOnceAndTurnsTheEffectOn(GameTestHelper helper) {
        Fixture f = fixture(helper, "Learn");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        for (int i = 0; i < 6; i++) {
            state.noteCourierDelivery(1);
        }
        TechNodeDef straps = TechTreeData.get().node("stout_straps");
        int before = CourierSatchel.targetCapacity(helper.getLevel(), f.settlement);
        // 2 Coins + 4 leather + 4 string, plus one spare of each.
        put(f.hearth, ModItems.GOLD_COIN.get(), 3);
        put(f.hearth, Items.LEATHER, 5);
        put(f.hearth, Items.STRING, 5);
        int revision = state.revision();
        TechTree.Result learned = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "stout_straps", revision, null);
        helper.assertTrue(learned == TechTree.Result.LEARNED, "learn must succeed, got " + learned);
        helper.assertTrue(count(f.hearth, ModItems.GOLD_COIN.get()) == 1
                && count(f.hearth, Items.LEATHER) == 1 && count(f.hearth, Items.STRING) == 1,
            "exactly the data price (" + TechCosts.costs(straps).size() + " lines) is paid once");
        helper.assertTrue(Development.has(helper.getLevel(), f.settlement, "stout_straps")
                && Development.hasUpgrade(helper.getLevel(), f.settlement, PostRaidUpgrade.STOUT_STRAPS),
            "a legacy-backed node is recorded in its legacy catalogue");
        helper.assertTrue(CourierSatchel.targetCapacity(helper.getLevel(), f.settlement)
                == before + PostRaidUpgrade.STOUT_STRAPS_BONUS,
            "the effect (Couriers carry +2) is active");
        TechTree.Result replay = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "stout_straps", revision, null);
        TechTree.Result again = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "stout_straps", state.revision(), null);
        helper.assertTrue(replay == TechTree.Result.STALE && again == TechTree.Result.ALREADY
                && count(f.hearth, ModItems.GOLD_COIN.get()) == 1,
            "a stale replay and a second learn pay nothing: " + replay + "/" + again);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void shortPriceRefusesAndTakesNothing(GameTestHelper helper) {
        Fixture f = fixture(helper, "Short");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        for (int i = 0; i < 6; i++) {
            state.noteCourierDelivery(1);
        }
        put(f.hearth, ModItems.GOLD_COIN.get(), 2);
        put(f.hearth, Items.LEATHER, 4);
        put(f.hearth, Items.STRING, 3);
        int revision = state.revision();
        TechTree.Result result = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "stout_straps", revision, null);
        helper.assertTrue(result == TechTree.Result.MATERIALS
                && count(f.hearth, ModItems.GOLD_COIN.get()) == 2
                && count(f.hearth, Items.LEATHER) == 4 && count(f.hearth, Items.STRING) == 3
                && state.revision() == revision
                && !Development.has(helper.getLevel(), f.settlement, "stout_straps"),
            "one string short must refuse and take nothing, got " + result);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void pickOneBlocksTheOtherChoiceOnBothPaths(GameTestHelper helper) {
        Fixture f = fixture(helper, "Choice");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        fund(f.hearth, TechTreeData.get().node("archer_longbow_drill"));
        // The other side of the pair is taken (v3 storage).
        state.learnTech("crossbows");
        TechTree.Assessment a = TechTree.assess(helper.getLevel(), f.settlement, f.hearth,
            "archer_longbow_drill", null);
        TechTree.Result v3 = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "archer_longbow_drill", state.revision(), null);
        Development.Result legacy = Development.assessUpgrade(helper.getLevel(), f.settlement,
            f.hearth, PostRaidUpgrade.ARCHER_LONGBOW_DRILL, null);
        helper.assertTrue(a.status() == TechTree.Status.BLOCKED && "crossbows".equals(a.reasonArg())
                && v3 == TechTree.Result.EXCLUDED
                && legacy == Development.Result.DOCTRINE_EXCLUSIVE
                && count(f.hearth, ModItems.GOLD_COIN.get()) == 6,
            "Longbows must be closed once Crossbows are taken: " + a.status() + "/" + v3 + "/" + legacy);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void coopSecondPlayerSeesTheSharedTree(GameTestHelper helper) {
        Fixture f = fixture(helper, "Coop");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.noteLumberLogs(16);
        ServerPlayer first = helper.makeMockServerPlayerInLevel();
        ServerPlayer second = helper.makeMockServerPlayerInLevel();
        fund(f.hearth, TechTreeData.get().node("sharpened_axes"));
        TechTree.Result result = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "sharpened_axes", state.revision(), first);
        TechTreeSnapshotPayload seen = TechTreeNetwork.snapshot(second, f.settlement, f.hearth,
            "", "", false, false);
        TechTreeSnapshotPayload.NodeState axes = seen.node("sharpened_axes");
        helper.assertTrue(result == TechTree.Result.LEARNED && axes != null
                && TechTree.Status.byOrdinal(axes.status()) == TechTree.Status.LEARNED
                && seen.revision() == state.revision(),
            "the second co-op player must see the node the first learned, got " + result);
        TechTreeSnapshotPayload.NodeState next = seen.node("builders_hut");
        helper.assertTrue(next != null && seen.nodes().size() == TechTreeData.get().nodes().size(),
            "the snapshot carries every node");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void townCharterStudiesThenGrantsItsBonus(GameTestHelper helper) {
        Fixture f = fixture(helper, "Study");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        // Two Village nodes in three branches (watch, logistics, craft).
        state.unlockUpgrade(PostRaidUpgrade.GUARD_ARMS_IRON);
        state.unlockUpgrade(PostRaidUpgrade.ARCHER_LONGBOW_DRILL);
        state.unlockUpgrade(PostRaidUpgrade.WORKER_PACKS);
        state.unlockUpgrade(PostRaidUpgrade.WAREHOUSE_RACKS);
        state.learnDoctrine(DevelopmentNode.GUILD_DOCTRINE, 0L);
        // Option 2: border_wardens is a founding trade (ring 1) now; the
        // Tannery is Craft's second Village node.
        state.learnTech("tannery");
        f.settlement.raidLifecycle.setEscalationForTesting(3, false);
        TechTree.GateEvaluator settlers = TechTree.swapGateForTest("settlers",
            (level, s, st, gate) -> 15);
        Double scale = TechTreeConfig.studyScaleOverride;
        try {
            TechTreeConfig.studyScaleOverride = 1.0D;
            fund(f.hearth, TechTreeData.get().node("town_charter"));
            TechTree.Result started = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
                "town_charter", state.revision(), null);
            helper.assertTrue(started == TechTree.Result.STUDY_STARTED
                    && state.studying("town_charter")
                    && !Development.has(helper.getLevel(), f.settlement, "town_charter")
                    && count(f.hearth, ModItems.GOLD_COIN.get()) == 0,
                "a Town node is paid up front and studied first, got " + started);
            TechTree.advanceStudiesForTest(helper.getLevel(), f.settlement, TechTree.DAY_TICKS - 1);
            helper.assertTrue(!Development.has(helper.getLevel(), f.settlement, "town_charter"),
                "not learned one tick early");
            TechTree.advanceStudiesForTest(helper.getLevel(), f.settlement, 1);
            helper.assertTrue(Development.has(helper.getLevel(), f.settlement, "town_charter")
                    && state.hasTech("town_charter")
                    && TechTree.bonus(helper.getLevel(), f.settlement, CrownEffects.MERCHANT_PURSE) == 4.0D,
                "after one in-game day the charter is learned and its purse bonus is live");
        } finally {
            TechTree.swapGateForTest("settlers", settlers);
            TechTreeConfig.studyScaleOverride = scale;
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void villageCharterStampsItselfAfterTheFirstRaid(GameTestHelper helper) {
        Fixture f = fixture(helper, "Stamp");
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlock(DevelopmentNode.ARM_THE_WATCH);
        boolean early = TechTree.stampAutoForTest(helper.getLevel(), f.settlement);
        helper.assertTrue(!early && !state.unlocked(DevelopmentNode.FIRST_RAID_AFTERMATH),
            "no stamp before the first raid resolves");
        completeFirstRaid(f.settlement);
        int revision = state.revision();
        boolean stamped = TechTree.stampAutoForTest(helper.getLevel(), f.settlement);
        helper.assertTrue(stamped && state.unlocked(DevelopmentNode.FIRST_RAID_AFTERMATH)
                && state.revision() == revision + 1,
            "the Village Charter stamps itself once, with a revision");
        helper.assertTrue(!TechTree.stampAutoForTest(helper.getLevel(), f.settlement),
            "a second pass stamps nothing");
        CompoundTag saved = state.writeNbt();
        helper.assertTrue(!DevelopmentState.readNbt(saved).quarantined(),
            "a stamped save reloads clean");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree_core", timeoutTicks = 60)
    public void preV3SavesLoadAndV3FieldsRoundTrip(GameTestHelper helper) {
        DevelopmentState state = new DevelopmentState();
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        CompoundTag old = state.writeNbt();
        old.remove("TechNodes");
        old.remove("TechStudies");
        old.remove("TechGrandfathered");
        DevelopmentState loaded = DevelopmentState.readNbt(old);
        helper.assertTrue(!loaded.quarantined() && loaded.unlocked(DevelopmentNode.TIMBER_RIGHTS)
                && TechTree.learned(loaded, "timber_rights") && TechTree.learned(loaded, "shelter"),
            "a pre-v3 save loads clean; legacy ids read through the v3 tree");
        for (var rule : com.hearthstead.settlement.techtree.EffectRegistry.get().grandfathers().entrySet()) {
            if (rule.getValue().contains("timber_rights")) {
                helper.assertTrue(loaded.hasTech(rule.getKey()),
                    rule.getKey() + " must be granted to a pre-v3 save that owns timber_rights");
            }
        }
        state.learnTech("town_charter");
        state.startStudy("castle_charter", 48_000L);
        CompoundTag v3 = state.writeNbt();
        ((ListTag) v3.get("TechNodes")).add(StringTag.valueOf("some_future_node"));
        DevelopmentState back = DevelopmentState.readNbt(v3);
        long[] study = back.study("castle_charter");
        helper.assertTrue(!back.quarantined() && back.hasTech("town_charter")
                && back.hasTech("some_future_node") && study != null && study[0] == 48_000L,
            "v3 nodes, unknown future ids and studies survive a round trip");
        helper.succeed();
    }

    // ------------------------------------------------------------ fixtures

    private static void founding(DevelopmentState state) {
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
    }

    private static void village(DevelopmentState state) {
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlock(DevelopmentNode.ARM_THE_WATCH);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
    }

    private static void completeFirstRaid(Settlement settlement) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        UUID participant = UUID.randomUUID();
        boolean complete = lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(plan)
            && lifecycle.beginFirstRaid(plan)
            && lifecycle.recordParticipant(participant)
            && lifecycle.sealParticipants()
            && lifecycle.recordTerminalParticipant(participant)
            && lifecycle.completeFirstRaid(false);
        if (!complete || lifecycle.firstState() != FirstRaidState.COMPLETED) {
            throw new IllegalStateException("could not complete first raid fixture");
        }
        settlement.raidLifecycle = lifecycle;
    }

    private static void fund(HearthBlockEntity hearth, TechNodeDef def) {
        for (DevelopmentNode.Cost cost : TechCosts.costs(def)) {
            put(hearth, cost.item(), cost.count());
        }
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(relative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
            if (existing.is(item) && existing.getCount() + amount <= existing.getMaxStackSize()) {
                existing.grow(amount);
                hearth.getInventory().setStackInSlot(slot, existing);
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static int count(HearthBlockEntity hearth, Item item) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP = SettlerEntity.class;
}

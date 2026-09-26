package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechEffect;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): every node of the v3 tech tree from a fresh
 * settlement (batch {@code scenario_techtree}, one test per node).
 *
 * <ul>
 *   <li>An implemented node is learned through {@link TechTree#learn} after
 *   its prerequisites (each learned the same way). Every paid node takes
 *   exactly its price once, and a replay of the same revision is refused and
 *   takes nothing. Its plans and emblems are then open. Nodes held behind a
 *   progress gate (quests, the first raid, a charter) are granted instead;
 *   the tree lane's own tests prove those gates.</li>
 *   <li>Each side of every pick-one pair, once learned, blocks the other:
 *   BLOCKED on the screen, EXCLUDED on purchase, nothing paid.</li>
 *   <li>A planned (not implemented) node cannot be bought and takes
 *   nothing.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
public final class ScenarioTechTreeGameTests {
    private static final String TEMPLATE = Hearthstead.MODID + ":empty16";

    @GameTestGenerator
    public static Collection<TestFunction> scenarioTechTree() {
        List<TestFunction> out = new ArrayList<>();
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            String id = def.id();
            out.add(new TestFunction("scenario_techtree", "scenario_techtree_" + id, TEMPLATE, Rotation.NONE,
                60, 0L, true, helper -> node(helper, id)));
        }
        return out;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }

    static void node(GameTestHelper helper, String id) {
        Fixture f = fixture(helper);
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement());
        TechNodeDef def = TechTreeData.get().node(id);
        helper.assertTrue(def != null, "node " + id + " exists");
        if (!TechTree.implemented(def)) {
            putCosts(f.hearth(), TechCosts.costs(def));
            int[] before = counts(f.hearth(), TechCosts.costs(def));
            TechTree.Result result = TechTree.learn(helper.getLevel(), f.settlement(), f.hearth(), id,
                Development.revisionOf(helper.getLevel(), f.settlement()), null);
            helper.assertTrue(!result.applied() && !TechTree.learned(state, id),
                "a planned node " + id + " cannot be bought: " + result);
            helper.assertTrue(java.util.Arrays.equals(before, counts(f.hearth(), TechCosts.costs(def))),
                "a planned node takes nothing");
            done(helper, f);
            return;
        }
        Set<String> paid = new LinkedHashSet<>();
        Set<String> granted = new LinkedHashSet<>();
        for (String pre : def.requires()) learn(helper, f, pre, paid, granted);
        // Its pick-one partner is still open before it is learned.
        learn(helper, f, id, paid, granted);
        helper.assertTrue(TechTree.learned(state, id), id + " is learned (paid " + paid + ", granted " + granted + ")");
        for (TechEffect effect : EffectRegistry.get().effects(id)) {
            if (effect instanceof TechEffect.UnlockBuilding b) {
                helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement(), b.type()),
                    id + " opens the " + b.type().id() + " plan");
            } else if (effect instanceof TechEffect.UnlockProfession p
                && JobEmblemCatalog.forProfession(p.profession()) != null) {
                helper.assertTrue(Development.isEmblemUnlocked(helper.getLevel(), f.settlement(), p.profession()),
                    id + " opens the " + p.profession() + " emblem");
            }
        }
        for (String other : def.excludes()) {
            TechNodeDef otherDef = TechTreeData.get().node(other);
            helper.assertTrue(otherDef != null, id + " excludes a real node " + other);
            TechTree.Assessment assessment = TechTree.assess(helper.getLevel(), f.settlement(), f.hearth(), other, null);
            helper.assertTrue(assessment.status() == TechTree.Status.BLOCKED,
                "learning " + id + " closes " + other + ": " + assessment.status() + " " + assessment.reason());
            // The partner's own prerequisites may sit on the far side of another pick-one pair
            // (hall_of_revels / cathedral): learn only what is still open.
            boolean chainOpen = true;
            for (String pre : otherDef.requires()) chainOpen &= learnIfOpen(helper, f, pre, paid, granted);
            List<com.hearthstead.settlement.development.DevelopmentNode.Cost> costs = TechCosts.costs(otherDef);
            putCosts(f.hearth(), costs);
            int[] before = counts(f.hearth(), costs);
            TechTree.Result refused = TechTree.learn(helper.getLevel(), f.settlement(), f.hearth(), other,
                Development.revisionOf(helper.getLevel(), f.settlement()), null);
            helper.assertTrue(!refused.applied() && !TechTree.learned(state, other)
                    && (refused == TechTree.Result.EXCLUDED || !chainOpen && refused == TechTree.Result.LOCKED),
                "the other side " + other + " is refused as EXCLUDED: " + refused + " (chain open " + chainOpen + ")");
            helper.assertTrue(java.util.Arrays.equals(before, counts(f.hearth(), costs)), "and takes nothing");
        }
        done(helper, f);
    }

    /** Learns {@code id} unless it (or a prerequisite) is closed by a pick-one choice; false when closed. */
    private static boolean learnIfOpen(GameTestHelper helper, Fixture f, String id, Set<String> paid,
                                       Set<String> granted) {
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement());
        if (TechTree.learned(state, id)) return true;
        TechNodeDef def = TechTreeData.get().node(id);
        if (def == null) return false;
        for (String pre : def.requires()) {
            if (!learnIfOpen(helper, f, pre, paid, granted)) return false;
        }
        if (TechTree.assess(helper.getLevel(), f.settlement(), f.hearth(), id, null).status() == TechTree.Status.BLOCKED) {
            return false;
        }
        learn(helper, f, id, paid, granted);
        return true;
    }

    /** TechTree.learn with prerequisites first; exact price once, a replay refused; progress gates granted. */
    private static void learn(GameTestHelper helper, Fixture f, String id, Set<String> paid, Set<String> granted) {
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement());
        if (TechTree.learned(state, id)) return;
        TechNodeDef def = TechTreeData.get().node(id);
        helper.assertTrue(def != null, "prerequisite " + id + " exists");
        for (String pre : def.requires()) learn(helper, f, pre, paid, granted);
        if (TechTree.learned(state, id)) return;
        List<com.hearthstead.settlement.development.DevelopmentNode.Cost> costs = TechCosts.costs(def);
        putCosts(f.hearth(), costs);
        int[] before = counts(f.hearth(), costs);
        int revision = Development.revisionOf(helper.getLevel(), f.settlement());
        TechTree.Result result = TechTree.learn(helper.getLevel(), f.settlement(), f.hearth(), id, revision, null);
        if (result.applied()) {
            for (int i = 0; i < costs.size(); i++) {
                Item item = costs.get(i).item();
                int sameItem = 0;
                for (var c : costs) if (c.item() == item) sameItem += c.count();
                helper.assertTrue(count(f.hearth(), item) == before[i] - sameItem,
                    id + " takes exactly its " + item + " price once (" + before[i] + " -> "
                        + count(f.hearth(), item) + ", price " + sameItem + ")");
            }
            int[] after = counts(f.hearth(), costs);
            TechTree.Result replay = TechTree.learn(helper.getLevel(), f.settlement(), f.hearth(), id, revision, null);
            helper.assertTrue(!replay.applied() && java.util.Arrays.equals(after, counts(f.hearth(), costs)),
                id + " replayed purchase is refused and takes nothing: " + replay);
            if (result == TechTree.Result.STUDY_STARTED) {
                // Study time: the settlement's own clock, driven through the tree's test seam.
                long[] study = state.study(id);
                helper.assertTrue(study != null && state.studying(id), id + " is being studied");
                // Study-speed bonuses already learned (e.g. the Hearth doctrine's library) may shorten it,
                // so only the full study time is asserted here; the exact clock is techtree_core's.
                helper.assertTrue(!TechTree.learned(state, id), id + " is not learned before its study runs");
                TechTree.advanceStudiesForTest(helper.getLevel(), f.settlement(), study[0]);
                helper.assertTrue(TechTree.learned(state, id), id + " is learned once its study time is over");
            }
            paid.add(id);
            return;
        }
        helper.assertTrue(result == TechTree.Result.GATE || result == TechTree.Result.AUTO,
            id + " must be learnable once its prerequisites are learned and its price is in the Hearth, got "
                + result);
        helper.assertTrue(java.util.Arrays.equals(before, counts(f.hearth(), costs)),
            id + " refused (" + result + ") takes nothing");
        for (var cost : costs) take(f.hearth(), cost.item(), cost.count());
        TechTreeTestGrants.grant(state, id);
        granted.add(id);
    }

    // ------------------------------------------------------------ fixture --

    private static Fixture fixture(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos abs = helper.absolutePos(hearthRel);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(abs);
        Settlement s = new Settlement(UUID.randomUUID(), "Treeholm", abs);
        s.radius = 8;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        hearth.bindSettlement(s.id);
        Development.revisionOf(helper.getLevel(), s);
        SettlerEntity mayor = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(10, 1, 8));
        mayor.setSettlerName("Mayor");
        mayor.bindTo(s.id, s.center);
        s.putRecord(mayor.getUUID(), "Mayor", Profession.NONE);
        s.mayorId = mayor.getUUID();
        s.putRecord(UUID.randomUUID(), "Founder A", Profession.NONE);
        s.putRecord(UUID.randomUUID(), "Founder B", Profession.NONE);
        return new Fixture(s, hearth);
    }

    private static void done(GameTestHelper helper, Fixture f) {
        SettlementSavedData.get(helper.getLevel()).settlements.remove(f.settlement().id);
        helper.succeed();
    }

    private static void putCosts(HearthBlockEntity hearth, List<com.hearthstead.settlement.development.DevelopmentNode.Cost> costs) {
        for (var cost : costs) {
            int left = cost.count();
            while (left > 0) {
                int batch = Math.min(left, new ItemStack(cost.item()).getMaxStackSize());
                boolean placed = false;
                for (int slot = 0; slot < hearth.getInventory().getSlots() && !placed; slot++) {
                    ItemStack existing = hearth.getInventory().getStackInSlot(slot);
                    if (existing.isEmpty()) {
                        hearth.getInventory().setStackInSlot(slot, new ItemStack(cost.item(), batch));
                        placed = true;
                    } else if (existing.is(cost.item()) && existing.getCount() + batch <= existing.getMaxStackSize()) {
                        hearth.getInventory().setStackInSlot(slot, existing.copyWithCount(existing.getCount() + batch));
                        placed = true;
                    }
                }
                if (!placed) throw new IllegalStateException("fixture Hearth inventory full");
                left -= batch;
            }
        }
    }

    private static void take(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots() && amount > 0; slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                int n = Math.min(amount, stack.getCount());
                hearth.getInventory().setStackInSlot(slot, stack.copyWithCount(stack.getCount() - n));
                amount -= n;
            }
        }
    }

    private static int[] counts(HearthBlockEntity hearth, List<com.hearthstead.settlement.development.DevelopmentNode.Cost> costs) {
        int[] out = new int[costs.size()];
        for (int i = 0; i < out.length; i++) out[i] = count(hearth, costs.get(i).item());
        return out;
    }

    private static int count(HearthBlockEntity hearth, Item item) {
        int n = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) n += stack.getCount();
        }
        return n;
    }
}

package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): every profession the Mayor sells, hired through
 * the real survival path from a fresh settlement (batch
 * {@code scenario_hire}). One test per catalogue entry:
 *
 * <ol>
 *   <li>Learn the unlocking node and every prerequisite with Hearth goods
 *   through {@link Development#purchaseNode}; each paid node takes exactly its
 *   price once and commits one revision, and a replayed purchase takes
 *   nothing. Nodes gated by play progress (quests, the first raid) are
 *   granted directly; RJ and the development tests prove those gates.</li>
 *   <li>The node grants the workplace plan and the emblem.</li>
 *   <li>Buy the emblem from the living Mayor (exact price, once), receive it
 *   in hand, and give it to a settler while <b>no</b> workplace exists: the
 *   hire is refused and the emblem is kept.</li>
 *   <li>With the workplace standing, the same emblem hires the settler
 *   there.</li>
 *   <li>A workshop trade then runs one real production loop: its first
 *   recipe's inputs in the chest become a workshop output. Trades whose
 *   loop needs the open world (fields, trees, water, game, raiders) keep
 *   their dedicated loop tests, which hire through the same
 *   {@link Employment#hire} this path ends in.</li>
 * </ol>
 */
@GameTestHolder(Hearthstead.MODID)
public final class ScenarioHireGameTests {
    private static final String TEMPLATE = Hearthstead.MODID + ":empty16";
    private static final BlockPos CHEST = new BlockPos(5, 1, 5);

    @GameTestGenerator
    public static Collection<TestFunction> scenarioHires() {
        List<TestFunction> out = new ArrayList<>();
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            Profession profession = entry.profession();
            BuildingType workplace = workplaceOf(profession);
            int timeout = 400;
            if (workplace != null && Production.produces(workplace)) {
                timeout = Math.max(800, Production.of(workplace).get(0).ticks() * 3 + 600);
            }
            out.add(new TestFunction("scenario_hire", "scenario_hire_" + profession.name().toLowerCase(),
                TEMPLATE, Rotation.NONE, timeout, 0L, true, helper -> hire(helper, profession)));
        }
        return out;
    }

    static BuildingType workplaceOf(Profession profession) {
        for (BuildingType type : BuildingType.values()) {
            if (Employment.tradeOf(type) == profession) return type;
        }
        return null;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }

    // --------------------------------------------------------------- body --

    static void hire(GameTestHelper helper, Profession profession) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Fixture f = fixture(helper);
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        helper.assertTrue(entry != null, profession + " is sold by the Mayor on a default (extendedTrades on) world");
        BuildingType workplace = workplaceOf(profession);
        helper.assertTrue(workplace != null, profession + " has a workplace building type");
        helper.assertTrue(!Development.isEmblemUnlocked(helper.getLevel(), f.settlement(), profession),
            profession + " is locked on a fresh settlement");

        Set<DevelopmentNode> paid = new LinkedHashSet<>();
        Set<DevelopmentNode> granted = new LinkedHashSet<>();
        learn(helper, f, entry.unlock(), paid, granted);
        // Tech tree v3: the node that now claims this emblem/plan (builders_hut, tannery, ...),
        // learned for real through TechTree.learn with its own prerequisites.
        Set<String> v3Paid = new LinkedHashSet<>();
        Set<String> v3Granted = new LinkedHashSet<>();
        learnClaimant(helper, f, com.hearthstead.settlement.techtree.EffectRegistry.get()
            .professionClaimants(profession), v3Paid, v3Granted);
        learnClaimant(helper, f, com.hearthstead.settlement.techtree.EffectRegistry.get()
            .buildingClaimants(workplace), v3Paid, v3Granted);
        helper.assertTrue(Development.isEmblemUnlocked(helper.getLevel(), f.settlement(), profession),
            entry.unlock().id() + " grants the " + profession + " emblem (paid " + paid + " v3 " + v3Paid
                + ", granted " + granted + " v3 " + v3Granted + ")");
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement(), workplace),
            "the path to " + profession + " grants the " + workplace.id() + " plan (paid " + paid
                + ", granted " + granted + ")");

        SettlerEntity worker = settler(helper, f.settlement(), "Scen" + profession.name(), 8, 8);
        worker.attributes().pinForTest(Attribute.STAMINA, 50);
        ServerPlayer player = buyEmblem(helper, f, entry, worker);
        ItemStack emblem = player.getMainHandItem();

        // No workplace yet: the emblem is refused and kept.
        ((JobEmblemItem) emblem.getItem()).interactLivingEntity(emblem, player, worker, InteractionHand.MAIN_HAND);
        helper.assertTrue(worker.getProfession() == Profession.NONE
                && JobEmblemItem.professionOf(player.getMainHandItem()) == profession,
            "without a " + workplace.id() + " the " + profession + " emblem hires nobody and stays in hand (now "
                + worker.getProfession() + ")");

        Building work = GameTestFixtures.register(helper, f.settlement(), workplace, 4, 4);
        helper.setBlock(CHEST, Blocks.CHEST);
        ItemStack held = player.getMainHandItem();
        ((JobEmblemItem) held.getItem()).interactLivingEntity(held, player, worker, InteractionHand.MAIN_HAND);
        helper.assertTrue(work.workers.contains(worker.getUUID()) && worker.getProfession() == profession
                && player.getMainHandItem().isEmpty(),
            "the " + profession + " emblem hires the settler at the " + workplace.id() + " and is used up");
        helper.getLevel().getServer().getPlayerList().remove(player);

        if (!Production.produces(workplace)) {
            helper.succeed();
            return;
        }
        // One real production loop at the bench.
        helper.getLevel().setDayTime(3000);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        Production.Recipe recipe = Production.of(workplace).get(0);
        ItemStack[] inputs = recipe.input().getItems();
        helper.assertTrue(inputs.length > 0, workplace.id() + " first recipe has an input");
        chest.setItem(0, new ItemStack(inputs[0].getItem(), Math.min(64, recipe.inputCount() * 2)));
        chest.setItem(1, new ItemStack(Items.COAL, 32));
        chest.setItem(2, new ItemStack(Items.CHARCOAL, 32));
        Set<Item> outputs = new LinkedHashSet<>();
        for (Production.Recipe r : Production.of(workplace)) outputs.add(r.output());
        helper.succeedWhen(() -> {
            int made = 0;
            for (Item out : outputs) {
                if (out == Items.COAL || out == Items.CHARCOAL) continue;
                made += count(chest, out);
            }
            helper.assertTrue(made > 0, "the hired " + profession + " turns " + inputs[0].getItem()
                + " into a workshop output " + outputs + " (activity " + worker.getActivity()
                + ", stop " + worker.logisticsStopReason() + ")");
        });
    }

    // ----------------------------------------------------------- learning --

    /** Learns {@code node} and its prerequisites: paid for real where the tree allows it. */
    private static void learn(GameTestHelper helper, Fixture f, DevelopmentNode node,
                              Set<DevelopmentNode> paid, Set<DevelopmentNode> granted) {
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement());
        if (state.unlocked(node)) return;
        for (String pre : node.prerequisites()) {
            DevelopmentNode prerequisite = DevelopmentNode.byId(pre);
            helper.assertTrue(prerequisite != null, node.id() + " names a real prerequisite: " + pre);
            learn(helper, f, prerequisite, paid, granted);
        }
        if (state.unlocked(node)) return;
        int revision = Development.revisionOf(helper.getLevel(), f.settlement());
        putCosts(f.hearth(), node.costs());
        int[] before = new int[node.costs().size()];
        for (int i = 0; i < before.length; i++) before[i] = count(f.hearth(), node.costs().get(i).item());
        Development.Result result = Development.purchaseNode(helper.getLevel(), f.settlement(), f.hearth(),
            node, revision);
        if (result == Development.Result.APPLIED) {
            for (int i = 0; i < before.length; i++) {
                DevelopmentNode.Cost cost = node.costs().get(i);
                // Two costs of the same item add up, so compare the item's total.
                int sameItem = 0;
                for (DevelopmentNode.Cost c : node.costs()) if (c.item() == cost.item()) sameItem += c.count();
                helper.assertTrue(count(f.hearth(), cost.item()) == before[i] - sameItem,
                    node.id() + " takes exactly its " + cost.item() + " price once ("
                        + before[i] + " -> " + count(f.hearth(), cost.item()) + ", price " + sameItem + ")");
            }
            helper.assertTrue(Development.revisionOf(helper.getLevel(), f.settlement()) == revision + 1,
                node.id() + " commits exactly one revision");
            int[] after = new int[before.length];
            for (int i = 0; i < after.length; i++) after[i] = count(f.hearth(), node.costs().get(i).item());
            Development.Result replay = Development.purchaseNode(helper.getLevel(), f.settlement(), f.hearth(),
                node, revision);
            helper.assertTrue(replay != Development.Result.APPLIED, node.id() + " replayed purchase is refused: " + replay);
            for (int i = 0; i < after.length; i++) {
                helper.assertTrue(count(f.hearth(), node.costs().get(i).item()) == after[i],
                    node.id() + " replayed purchase takes nothing");
            }
            paid.add(node);
            return;
        }
        helper.assertTrue(result == Development.Result.QUEST_REQUIRED
                || result == Development.Result.FIRST_RAID_REQUIRED,
            node.id() + " must be buyable once its prerequisites are learned and its price is in the Hearth, got "
                + result);
        for (int i = 0; i < before.length; i++) {
            helper.assertTrue(count(f.hearth(), node.costs().get(i).item()) == before[i],
                node.id() + " refused (" + result + ") takes nothing");
        }
        for (DevelopmentNode.Cost cost : node.costs()) take(f.hearth(), cost.item(), cost.count());
        if (node.doctrine()) {
            state.learnDoctrine(node, helper.getLevel().getGameTime());
        } else {
            state.unlock(node);
        }
        DevelopmentQuests.ensureEligibleBaselines(state);
        granted.add(node);
    }

    /** Learns one claimant of a v3 plan/emblem for real (unless one is already learned). */
    private static void learnClaimant(GameTestHelper helper, Fixture f, java.util.List<String> claimants,
                                      Set<String> paid, Set<String> granted) {
        if (claimants.isEmpty()) return;
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement());
        for (String id : claimants) if (TechTree.learned(state, id)) return;
        learnV3(helper, f, claimants.get(0), paid, granted);
    }

    /** TechTree.learn with prerequisites first; exact price once, a replay refused; progress gates granted. */
    private static void learnV3(GameTestHelper helper, Fixture f, String id, Set<String> paid, Set<String> granted) {
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement());
        if (TechTree.learned(state, id)) return;
        com.hearthstead.settlement.techtree.TechNodeDef def =
            com.hearthstead.settlement.techtree.TechTreeData.get().node(id);
        helper.assertTrue(def != null, "v3 node " + id + " exists");
        for (String pre : def.requires()) learnV3(helper, f, pre, paid, granted);
        if (TechTree.learned(state, id)) return;
        java.util.List<DevelopmentNode.Cost> costs = com.hearthstead.settlement.techtree.TechCosts.costs(def);
        putCosts(f.hearth(), costs);
        int[] before = new int[costs.size()];
        for (int i = 0; i < before.length; i++) before[i] = count(f.hearth(), costs.get(i).item());
        int revision = Development.revisionOf(helper.getLevel(), f.settlement());
        TechTree.Result result = TechTree.learn(helper.getLevel(), f.settlement(), f.hearth(), id, revision, null);
        if (result.applied()) {
            for (int i = 0; i < before.length; i++) {
                DevelopmentNode.Cost cost = costs.get(i);
                int sameItem = 0;
                for (DevelopmentNode.Cost c : costs) if (c.item() == cost.item()) sameItem += c.count();
                helper.assertTrue(count(f.hearth(), cost.item()) == before[i] - sameItem,
                    "v3 " + id + " takes exactly its " + cost.item() + " price once");
            }
            TechTree.Result replay = TechTree.learn(helper.getLevel(), f.settlement(), f.hearth(), id, revision, null);
            helper.assertTrue(!replay.applied(), "v3 " + id + " replay refused: " + replay);
            // A node with study days (carpenter_mason, rune_mage, ...) is paid now and
            // learned when its study ends: finish the study so a second claim sees it learned.
            if (!TechTree.learned(state, id)) {
                TechTree.advanceStudiesForTest(helper.getLevel(), f.settlement(), TechTree.studyTicks(def) + 1L);
            }
            helper.assertTrue(TechTree.learned(state, id), "v3 " + id + " is learned once its study ends");
            paid.add(id);
            return;
        }
        helper.assertTrue(result == TechTree.Result.GATE || result == TechTree.Result.AUTO,
            "v3 " + id + " must be learnable once its prerequisites are learned and its price is in the Hearth, got "
                + result);
        for (int i = 0; i < before.length; i++) {
            helper.assertTrue(count(f.hearth(), costs.get(i).item()) == before[i], "v3 " + id + " refused takes nothing");
        }
        for (DevelopmentNode.Cost cost : costs) take(f.hearth(), cost.item(), cost.count());
        TechTreeTestGrants.grant(state, id); // a progress gate (quests, raids) proven by the tree lane's tests
        granted.add(id);
    }

    // ------------------------------------------------------------ fixture --

    private static Fixture fixture(GameTestHelper helper) {
        BlockPos hearthRelative = new BlockPos(12, 1, 12);
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(hearthRelative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Hireholm", absolute);
        settlement.radius = 8;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        SettlerEntity mayor = settler(helper, settlement, "Mayor", 13, 10);
        settlement.mayorId = mayor.getUUID();
        // The founders' roll (three workers + the Mayor): First Fire's proof.
        settlement.putRecord(UUID.randomUUID(), "Founder A", Profession.NONE);
        settlement.putRecord(UUID.randomUUID(), "Founder B", Profession.NONE);
        return new Fixture(settlement, hearth);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s, String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** Pays the Mayor from the Hearth (exact price, once) and takes the emblem in hand. */
    private static ServerPlayer buyEmblem(GameTestHelper helper, Fixture f, JobEmblemCatalog.Entry entry,
                                          SettlerEntity settler) {
        Profession profession = entry.profession();
        putCosts(f.hearth(), entry.costs());
        int[] before = new int[entry.costs().size()];
        for (int i = 0; i < before.length; i++) before[i] = count(f.hearth(), entry.costs().get(i).item());
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.getAbilities().instabuild = false;
        player.onUpdateAbilities();
        player.setPos(settler.getX(), settler.getY(), settler.getZ());
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, ItemStack.EMPTY);
        Development.EmblemPurchase purchase = Development.purchaseEmblem(helper.getLevel(), f.settlement(),
            f.hearth(), profession, Development.revisionOf(helper.getLevel(), f.settlement()), player);
        helper.assertTrue(purchase.applied(), "the exact price issues the " + profession + " emblem: "
            + purchase.result());
        for (int i = 0; i < before.length; i++) {
            DevelopmentNode.Cost cost = entry.costs().get(i);
            int sameItem = 0;
            for (DevelopmentNode.Cost c : entry.costs()) if (c.item() == cost.item()) sameItem += c.count();
            helper.assertTrue(count(f.hearth(), cost.item()) == before[i] - sameItem,
                "the " + profession + " emblem takes exactly its " + cost.item() + " price once");
        }
        PendingPlayerDeliveryLedger.DeliveryResult delivery = Development.deliverPending(helper.getLevel(),
            f.settlement(), player, purchase.deliveryId());
        helper.assertTrue(delivery.outcome() == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND,
            "the paid " + profession + " emblem lands in the empty hand: " + delivery.outcome());
        helper.assertTrue(JobEmblemItem.professionOf(player.getMainHandItem()) == profession,
            "the Mayor hands over a physical " + profession + " emblem");
        return player;
    }

    private static void putCosts(HearthBlockEntity hearth, List<DevelopmentNode.Cost> costs) {
        for (DevelopmentNode.Cost cost : costs) {
            int left = cost.count();
            while (left > 0) {
                int batch = Math.min(left, new ItemStack(cost.item()).getMaxStackSize());
                put(hearth, cost.item(), batch);
                left -= batch;
            }
        }
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

    private static int count(HearthBlockEntity hearth, Item item) {
        int n = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) n += stack.getCount();
        }
        return n;
    }

    private static int count(Container container, Item item) {
        int n = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) n += container.getItem(slot).getCount();
        }
        return n;
    }
}

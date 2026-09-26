package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/**
 * Trades-unlock lane (26 Sep), batch {@code trades_unlock}: for each of the
 * four specializations, learn the node, buy the emblem from the living Mayor,
 * give it to a settler, and watch the hired worker produce real output. Plus
 * the QA-JOBS J-01/J-10/J-11 status and tool contracts for the new trades,
 * and the {@code [features] extendedTrades} off switch.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TradesUnlockGameTests {

    /** NeoForge creates holder instances for non-static {@link GameTest}s. */
    public TradesUnlockGameTests() {
    }

    // ------------------------------------------------ learn, buy, hire, work ---

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 600)
    public void landAndHarvestMillerIsLearnedBoughtHiredAndMills(GameTestHelper helper) {
        learnBuyHireAndWork(helper, DevelopmentNode.LAND_AND_HARVEST,
            DevelopmentNode.GUILD_DOCTRINE, Profession.MILLER, BuildingType.MILL,
            Items.WHEAT, 6, ModItems.FLOUR.get());
    }

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 600)
    public void craftAndIndustryCarpenterIsLearnedBoughtHiredAndWorks(GameTestHelper helper) {
        learnBuyHireAndWork(helper, DevelopmentNode.CRAFT_AND_INDUSTRY,
            DevelopmentNode.GUILD_DOCTRINE, Profession.CARPENTER, BuildingType.CARPENTER,
            Items.OAK_PLANKS, 4, Items.STICK);
    }

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 600)
    public void hallAndLearningCookIsLearnedBoughtHiredAndCooks(GameTestHelper helper) {
        // Tech tree v3: the Kitchen and Cook moved to kitchen_and_hall
        // (grandfathered from Hall and Learning on old saves).
        learnBuyHireAndWork(helper, DevelopmentNode.HALL_AND_LEARNING,
            DevelopmentNode.HEARTH_DOCTRINE, Profession.COOK, BuildingType.KITCHEN,
            Items.POTATO, 2, Items.BAKED_POTATO, "kitchen_and_hall");
    }

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 600)
    public void fortificationFletcherIsLearnedBoughtHiredAndFletches(GameTestHelper helper) {
        learnBuyHireAndWork(helper, DevelopmentNode.FORTIFICATION,
            DevelopmentNode.SHIELD_DOCTRINE, Profession.FLETCHER, BuildingType.FLETCHER,
            Items.FLINT, 2, Items.ARROW);
    }

    private static void learnBuyHireAndWork(GameTestHelper helper, DevelopmentNode node,
                                            DevelopmentNode doctrine, Profession profession,
                                            BuildingType type, Item input, int inputCount,
                                            Item output) {
        learnBuyHireAndWork(helper, node, doctrine, profession, type, input, inputCount, output, null);
    }

    /** {@code v3Node}: the tech tree v3 node that now claims this plan and emblem, or null. */
    private static void learnBuyHireAndWork(GameTestHelper helper, DevelopmentNode node,
                                            DevelopmentNode doctrine, Profession profession,
                                            BuildingType type, Item input, int inputCount,
                                            Item output, String v3Node) {
        floor(helper);
        Fixture f = fixture(helper);
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        primeAftermath(state);
        state.learnDoctrine(doctrine, helper.getLevel().getGameTime());
        DevelopmentQuests.ensureEligibleBaselines(state);

        helper.assertTrue(Development.assessNode(helper.getLevel(), f.settlement, f.hearth,
                node).status() != Development.NodeStatus.FUTURE,
            node.id() + " must be learnable, not a FUTURE placeholder");
        helper.assertTrue(!Development.isEmblemUnlocked(helper.getLevel(), f.settlement,
                profession),
            profession + " must stay locked before " + node.id() + " is learned");

        putCosts(f.hearth, node.costs());
        Development.Result learned = Development.purchaseNode(helper.getLevel(),
            f.settlement, f.hearth, node,
            Development.revisionOf(helper.getLevel(), f.settlement));
        helper.assertTrue(learned == Development.Result.APPLIED,
            node.id() + " purchase failed: " + learned);
        if (v3Node != null) {
            Development.of(helper.getLevel(), f.settlement).learnTech(v3Node);
        }
        // Tech tree v3: a trade claimed onto its own node (carpenter_mason, ...) needs that node too.
        TechTreeTestGrants.grantClaimants(state, profession, type);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, type)
                && Development.isEmblemUnlocked(helper.getLevel(), f.settlement, profession),
            node.id() + " must grant the " + type.id() + " plan and the "
                + profession + " emblem");

        Building work = GameTestFixtures.register(helper, f.settlement, type, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = container(helper, new BlockPos(5, 1, 4));
        SettlerEntity worker = settler(helper, f.settlement, "Worker", 4, 4);
        worker.attributes().pinForTest(Attribute.STAMINA, 50);
        buyAndGiveEmblem(helper, f, profession, worker, work);
        helper.getLevel().setDayTime(3000);
        chest.setItem(0, new ItemStack(input, inputCount));

        final boolean[] sawWork = {false};
        helper.succeedWhen(() -> {
            if (worker.getActivity() == Employment.motionOf(type)) {
                sawWork[0] = true;
            }
            helper.assertTrue(count(chest, output) > 0,
                "the hired " + profession + " must produce " + output
                    + " (activity=" + worker.getActivity() + ")");
            helper.assertTrue(sawWork[0],
                "the " + profession + " work motion must have been observed");
        });
    }

    // ------------------------------------------------------------ switch ---

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 100)
    public void extendedTradesOffKeepsTheOldPlannedNodesAndShop(GameTestHelper helper) {
        floor(helper);
        Fixture f = fixture(helper);
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        primeAftermath(state);
        state.learnDoctrine(DevelopmentNode.GUILD_DOCTRINE, helper.getLevel().getGameTime());
        putCosts(f.hearth, DevelopmentNode.CRAFT_AND_INDUSTRY.costs());
        Development.Result result;
        Development.Assessment assessment;
        JobEmblemCatalog.Entry miner;
        ExtendedTrades.overrideForTests(false);
        try {
            assessment = Development.assessNode(helper.getLevel(), f.settlement, f.hearth,
                DevelopmentNode.CRAFT_AND_INDUSTRY);
            result = Development.purchaseNode(helper.getLevel(), f.settlement, f.hearth,
                DevelopmentNode.CRAFT_AND_INDUSTRY,
                Development.revisionOf(helper.getLevel(), f.settlement));
            miner = JobEmblemCatalog.forProfession(Profession.MINER);
        } finally {
            ExtendedTrades.overrideForTests(null);
        }
        helper.assertTrue(assessment.status() == Development.NodeStatus.FUTURE
                && result == Development.Result.FUTURE
                && !state.unlocked(DevelopmentNode.CRAFT_AND_INDUSTRY)
                && miner == null,
            "extendedTrades=false must keep Craft and Industry PLANNED and the Miner unsold");
        helper.succeed();
    }

    // ------------------------------------------- J-10 / J-11 / J-01 contracts ---

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 200)
    public void minerWithoutAPickaxeRequestsOneAndDoesNotDig(GameTestHelper helper) {
        floor(helper);
        Fixture f = fixture(helper);
        Building mine = GameTestFixtures.register(helper, f.settlement, BuildingType.MINE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        SettlerEntity miner = settler(helper, f.settlement, "Miner", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement, mine, miner).ok(),
            "fixture: the mine must take a miner");
        helper.getLevel().setDayTime(3000);
        com.hearthstead.gametest.GameTestTicks.at(helper, 120, () -> {
            helper.assertTrue(miner.getActivity() != SettlerActivity.WORK_MINE,
                "a Miner without a pickaxe must not mine, got " + miner.getActivity());
            EquipmentRequest request = EquipmentRequests.refreshFor(helper.getLevel(), miner);
            helper.assertTrue(request != null
                    && request.requirement().matches(new ItemStack(Items.STONE_PICKAXE))
                    && !request.requirement().matches(new ItemStack(Items.SHEARS)),
                "the Miner must publish a pickaxe request");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 400)
    public void herderShearsOnlyWithRealShearsAndWearsThem(GameTestHelper helper) {
        floor(helper);
        Fixture f = fixture(helper);
        Building pasture = GameTestFixtures.register(helper, f.settlement,
            BuildingType.PASTURE, 4, 4);
        helper.setBlock(new BlockPos(6, 1, 4), Blocks.CHEST);
        Sheep sheep = helper.spawn(EntityType.SHEEP, new BlockPos(5, 1, 6));
        sheep.setNoAi(true);
        SettlerEntity herder = settler(helper, f.settlement, "Herder", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement, pasture, herder).ok(),
            "fixture: the pasture must take a herder");
        helper.getLevel().setDayTime(3000);
        final int[] shearsDamage = {-1};
        com.hearthstead.gametest.GameTestTicks.at(helper, 120, () -> {
            helper.assertTrue(!sheep.isSheared(),
                "a Herder without shears must not shear");
            EquipmentRequest request = EquipmentRequests.refreshFor(helper.getLevel(), herder);
            helper.assertTrue(request != null
                    && request.requirement().matches(new ItemStack(Items.SHEARS)),
                "the Herder must publish a shears request");
            herder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SHEARS));
            shearsDamage[0] = 0;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(shearsDamage[0] == 0 && sheep.isSheared(),
                "with shears in hand the Herder must shear the sheep");
            helper.assertTrue(herder.getMainHandItem().getDamageValue() > 0,
                "shearing must wear the real shears");
        });
    }

    @GameTest(template = "empty16", batch = "trades_unlock", timeoutTicks = 300)
    public void idleCrafterSaysWaitingForInputThenChestFull(GameTestHelper helper) {
        floor(helper);
        Fixture f = fixture(helper);
        Building shop = GameTestFixtures.register(helper, f.settlement,
            BuildingType.CARPENTER, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = container(helper, new BlockPos(5, 1, 4));
        SettlerEntity carpenter = settler(helper, f.settlement, "Carpenter", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement, shop, carpenter).ok(),
            "fixture: the carpenter's shop must take a carpenter");
        helper.getLevel().setDayTime(3000);
        com.hearthstead.gametest.GameTestTicks.at(helper, 80, () -> {
            helper.assertTrue(carpenter.logisticsStopReason() == StopReason.WAITING_INPUT,
                "an empty bench must say WAITING_INPUT, got "
                    + carpenter.logisticsStopReason());
            // Inputs present, but every slot full of something else.
            chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            for (int slot = 1; slot < chest.getContainerSize(); slot++) {
                chest.setItem(slot, new ItemStack(Items.DIRT, 64));
            }
        });
        com.hearthstead.gametest.GameTestTicks.at(helper, 160, () -> {
            helper.assertTrue(carpenter.logisticsStopReason() == StopReason.CHEST_FULL,
                "a fed bench with no room must say CHEST_FULL, got "
                    + carpenter.logisticsStopReason());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------ fixture ---

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Fixture fixture(GameTestHelper helper) {
        BlockPos hearthRelative = new BlockPos(12, 1, 12);
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(hearthRelative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Tradeholm", absolute);
        settlement.radius = 6;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        SettlerEntity mayor = settler(helper, settlement, "Mayor", 13, 10);
        settlement.mayorId = mayor.getUUID();
        return new Fixture(settlement, hearth);
    }

    private static void primeAftermath(DevelopmentState state) {
        for (DevelopmentNode node : new DevelopmentNode[] {
                DevelopmentNode.TIMBER_RIGHTS, DevelopmentNode.STORES_AND_ROADS,
                DevelopmentNode.CULTIVATED_GROUND, DevelopmentNode.SHORE_PROVISIONS,
                DevelopmentNode.HOME, DevelopmentNode.HOSPITALITY,
                DevelopmentNode.FIRST_WATCH, DevelopmentNode.ARM_THE_WATCH,
                DevelopmentNode.FIRST_RAID_AFTERMATH}) {
            state.unlock(node);
        }
        DevelopmentQuests.ensureEligibleBaselines(state);
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

    /** The real survival path: pay the Mayor, take the emblem, give it. */
    private static void buyAndGiveEmblem(GameTestHelper helper, Fixture f,
                                         Profession profession, SettlerEntity settler,
                                         Building expectedWorkplace) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        helper.assertTrue(entry != null, profession + " must be in the Mayor's catalog");
        putCosts(f.hearth, entry.costs());
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.getAbilities().instabuild = false;
        player.onUpdateAbilities();
        player.setPos(settler.getX(), settler.getY(), settler.getZ());
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, ItemStack.EMPTY);
        Development.EmblemPurchase purchase = Development.purchaseEmblem(
            helper.getLevel(), f.settlement, f.hearth, profession,
            Development.revisionOf(helper.getLevel(), f.settlement), player);
        helper.assertTrue(purchase.applied(),
            "the exact Hearth cost must issue the " + profession + " emblem: "
                + purchase.result());
        PendingPlayerDeliveryLedger.DeliveryResult delivery =
            Development.deliverPending(helper.getLevel(), f.settlement, player,
                purchase.deliveryId());
        helper.assertTrue(delivery.outcome() == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND,
            "the paid " + profession + " emblem must land in the empty hand: "
                + delivery.outcome());
        ItemStack emblem = player.getMainHandItem();
        helper.assertTrue(JobEmblemItem.professionOf(emblem) == profession,
            "the Mayor must hand over a physical " + profession + " emblem");
        ((JobEmblemItem) emblem.getItem()).interactLivingEntity(emblem,
            player, settler, InteractionHand.MAIN_HAND);
        helper.assertTrue(expectedWorkplace.workers.contains(settler.getUUID())
                && settler.getProfession() == profession
                && player.getMainHandItem().isEmpty(),
            "giving the " + profession + " emblem must hire the settler at the "
                + expectedWorkplace.type.id());
    }

    private static void putCosts(HearthBlockEntity hearth, java.util.List<DevelopmentNode.Cost> costs) {
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

    private static Container container(GameTestHelper helper, BlockPos relative) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(relative))
            instanceof Container c ? c : null;
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}

// PROPOSAL ONLY. TavernClientQaFixture calls this only after its existing
// console/owned-playtest/copy/trace guard succeeds.
package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.AleTapBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentQuote;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.TavernGuestPayment;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Copy-only assisted setup. It creates physical actors and inputs, then leaves
 * quote, payment, preparation, service and eating entirely to ordinary AI.
 * It intentionally never calls reserveQuote, quoteAtHostContact,
 * acceptAtHostContact, pickup, mounts a guest, grants food, or edits Coins
 * after setup.
 */
public final class OwnerTavernRestaurantSceneProposal {
    private static final UUID VILLAGE = UUID.fromString("0a6c58d1-1be1-4fd3-8e11-9f38dce3b5a9");
    private static final UUID TAVERN = UUID.fromString("39bd6743-ae00-4473-a237-0456af6462ee");
    private static final UUID[] ORIGINAL = {
        UUID.fromString("6cbc0857-3be7-49b9-8228-cbb830adf5f9"),
        UUID.fromString("d1d7d290-2231-435a-aff9-91602716ebf6"),
        UUID.fromString("2fa06a1f-a96d-47e1-8a7d-3e0139bf8ec4"),
        UUID.fromString("47ec007e-74be-4590-a485-e1c63d9dc0da")
    };
    private static final Profession[] ORIGINAL_ROLES = {
        Profession.FARMER, Profession.MAYOR, Profession.COURIER, Profession.LUMBERER
    };
    private static final BlockPos HEARTH = new BlockPos(110, 72, -116);
    private static final BlockPos PLAQUE = new BlockPos(153, 79, -133);
    private static final BlockPos STOCK = new BlockPos(152, 72, -124);
    private static final BlockPos SERVICE_TABLE = new BlockPos(153, 72, -122);
    private static final BlockPos SERVICE_CHAIR = new BlockPos(153, 72, -123);
    private static final BlockPos ALE_BARREL = new BlockPos(156, 72, -122);
    private static final BlockPos ALE_TAP = new BlockPos(156, 72, -121);
    private static final AABB ROOM = new AABB(147, 71, -132, 160, 81, -117);
    private static final String MARKER = "HearthsteadOwnerRestaurantScene20260925";

    private OwnerTavernRestaurantSceneProposal() {}

    public static int commandAfterGuard(CommandSourceStack source, String action) {
        try {
            ServerLevel level = source.getLevel();
            var player = level.getServer().getPlayerList().getPlayers().getFirst();
            if ("owner-restaurant-diagnose".equals(action)) {
                // existing() never creates, reconciles, quarantines, or dirties data.
                SettlementSavedData data = SettlementSavedData.existing(level);
                Settlement diagnosticSettlement = data == null ? null : data.settlements.get(VILLAGE);
                require(diagnosticSettlement != null && HEARTH.equals(diagnosticSettlement.center),
                    "wrong_owner_copy");
                requireOriginals(level, diagnosticSettlement);
                diagnose(level, source, diagnosticSettlement);
                return 1;
            }
            Settlement settlement = SettlementSavedData.get(level).settlements.get(VILLAGE);
            require(settlement != null && HEARTH.equals(settlement.center), "wrong_owner_copy");
            requireOriginals(level, settlement);
            PlaqueBlockEntity plaque = level.getBlockEntity(PLAQUE) instanceof PlaqueBlockEntity found
                ? found : null;
            require(plaque != null, "tavern_plaque_missing");
            Building tavern = plaque.building(level);
            require(tavern != null && tavern.valid && tavern.type == BuildingType.TAVERN
                && TAVERN.equals(tavern.id) && PLAQUE.equals(tavern.plaquePos), "wrong_tavern");
            if ("owner-restaurant-prepare".equals(action)) {
                prepare(level, player, settlement, tavern);
                source.sendSuccess(() -> Component.literal(
                    "HSQA_OWNER_RESTAURANT prepared=resident_first; wait_for_ordinary_goals=true"), true);
                return 1;
            }
            require(player.getPersistentData().getBoolean(MARKER), "not_prepared");
            if ("owner-restaurant-open-paid-visitor".equals(action)) {
                openPaidVisitor(level, player, settlement, tavern);
                source.sendSuccess(() -> Component.literal(
                    "HSQA_OWNER_RESTAURANT paid_visitor_open=true; wait_for_ordinary_goals=true"), true);
                return 1;
            }
            require("owner-restaurant-assert".equals(action), "unknown_owner_restaurant_action");
            assertCompleted(level, player, settlement, tavern);
            source.sendSuccess(() -> Component.literal("HSQA_OWNER_RESTAURANT_COMPLETE"), true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("HSQA_OWNER_RESTAURANT result=FAIL reason="
                + failure.getMessage()));
            return 0;
        }
    }

    /**
     * Read-only evidence for a guarded, disposable owner-copy run. It runs
     * only after the diagnostic branch has proved the exact owner identity;
     * it neither relinks nor changes validity.
     */
    private static void diagnose(ServerLevel level, CommandSourceStack source, Settlement settlement) {
        PlaqueBlockEntity plaque = level.getBlockEntity(PLAQUE) instanceof PlaqueBlockEntity found
            ? found : null;
        UUID linkedId = plaque == null ? null : plaque.buildingId();
        Building expected = null;
        Building linked = null;
        int expectedMatches = 0;
        int linkedMatches = 0;
        for (Building candidate : settlement.buildings) {
            if (candidate != null && TAVERN.equals(candidate.id)) {
                expected = candidate;
                expectedMatches++;
            }
            if (candidate != null && linkedId != null && linkedId.equals(candidate.id)) {
                linked = candidate;
                linkedMatches++;
            }
        }
        String survey = "none";
        if (plaque != null && !plaque.lastSurvey().isEmpty()) {
            StringBuilder statuses = new StringBuilder();
            for (var status : plaque.lastSurvey()) {
                if (!statuses.isEmpty()) statuses.append(',');
                statuses.append(status.requirement().id()).append('=')
                    .append(status.have()).append('/').append(status.needed());
            }
            survey = statuses.toString();
        }
        String line = "HSQA_OWNER_RESTAURANT_DIAGNOSTIC"
            + " settlementPresent=true"
            + " settlementCenter=" + settlement.center.toShortString()
            + " plaquePresent=" + (plaque != null)
            + " plaqueState=" + (plaque == null ? "none" : plaque.state())
            + " plaqueType=" + (plaque == null ? "none" : plaque.type().id())
            + " plaqueRevision=" + (plaque == null ? -1 : plaque.revision())
            + " plaqueBuildingId=" + (linkedId == null ? "none" : linkedId)
            + " lastScanReason=" + (plaque == null || plaque.lastScanReason() == null
                ? "none" : plaque.lastScanReason().getString())
            + " survey=" + survey
            + " expectedBuildingMatches=" + expectedMatches
            + " expectedBuildingPresent=" + (expected != null)
            + " expectedBuildingValid=" + (expected != null && expected.valid)
            + " expectedBuildingType=" + (expected == null ? "none" : expected.type.id())
            + " expectedBuildingId=" + (expected == null ? "none" : expected.id)
            + " expectedBuildingPlaquePos=" + (expected == null ? "none" : expected.plaquePos.toShortString())
            + " linkedBuildingMatches=" + linkedMatches
            + " linkedBuildingPresent=" + (linked != null)
            + " linkedBuildingValid=" + (linked != null && linked.valid)
            + " linkedBuildingType=" + (linked == null ? "none" : linked.type.id())
            + " linkedBuildingId=" + (linked == null ? "none" : linked.id)
            + " linkedBuildingPlaquePos=" + (linked == null ? "none" : linked.plaquePos.toShortString())
            + " linkedEqualsExpected=" + (linked != null && TAVERN.equals(linked.id));
        Hearthstead.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), true);
    }

    private static void prepare(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
                                Settlement settlement, Building tavern) {
        require(!player.getPersistentData().contains(MARKER), "already_attempted");
        require(tavern.workers.isEmpty(), "owner_tavern_already_staffed");
        var blocks = BuiltInRegistries.BLOCK;
        var table = blocks.get(ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_table"));
        var chair = blocks.get(ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_chair"));
        require(table != Blocks.AIR && chair != Blocks.AIR, "another_furniture_missing");
        require(level.getBlockState(SERVICE_TABLE).isAir() && level.getBlockState(SERVICE_CHAIR).isAir()
            && level.getBlockState(STOCK).isAir() && level.getBlockState(ALE_BARREL).isAir()
            && level.getBlockState(ALE_TAP).isAir() && level.getBlockState(ALE_TAP.south()).isAir(),
            "service_probe_cells_changed");

        // The proposal adds one service table/chair in verified empty space.
        // It does not move any original bench. If the later seating preflight
        // cannot prove a straight host lane, stop and survey one opposing bench
        // row; move only that row in the disposable derivative.
        level.setBlockAndUpdate(SERVICE_TABLE, table.defaultBlockState());
        level.setBlockAndUpdate(SERVICE_CHAIR, chair.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        level.setBlockAndUpdate(STOCK, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(ALE_BARREL, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(ALE_TAP, ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(AleTapBlock.FACING, Direction.SOUTH));
        Container chest = level.getBlockEntity(STOCK) instanceof Container found ? found : null;
        require(chest != null, "stock_missing");
        chest.setItem(0, new ItemStack(Items.WHEAT, 6));
        chest.setItem(1, new ItemStack(Items.GLASS_BOTTLE, 1));
        chest.setItem(2, new ItemStack(Items.OAK_PLANKS, 2)); // must remain untouched
        chest.setChanged();

        // (152.5,-123.5) floors into the stock chest block; start the host on
        // its service stand south of the service table instead.
        SettlerEntity host = resident(level, settlement, "Mara QA Innkeeper",
            new Vec3(153.5, 72, -120.5), 100);
        require(Employment.hire(level, settlement, tavern, host).ok()
            && host.getProfession() == Profession.INNKEEPER, "innkeeper_hire_refused");
        SettlerEntity resident = resident(level, settlement, "Freya QA Resident",
            new Vec3(149.5, 72, -123.5), 55);
        SettlerEntity traveler = traveler(level, settlement, tavern, "Bram QA Visitor",
            new Vec3(152.5, 72, -119.5));
        level.setDayTime(11_500L);
        settlement.lastTavernVisitorDay = com.hearthstead.settlement.SettlementManager.currentTavernVisitorDay(level);
        player.getPersistentData().putBoolean(MARKER, true);
        player.getPersistentData().putUUID(MARKER + "Host", host.getUUID());
        player.getPersistentData().putUUID(MARKER + "Resident", resident.getUUID());
        player.getPersistentData().putUUID(MARKER + "Traveler", traveler.getUUID());
        player.getPersistentData().putFloat(MARKER + "ResidentHungerBefore", resident.getHunger());
        SettlementSavedData.get(level).setDirty();
        player.getPersistentData().putInt(MARKER + "Phase", 1);
        Hearthstead.LOGGER.info("HSQA_OWNER_RESTAURANT prepared=resident_first settlement={} tavern={} "
            + "originals=4 host={} resident={} traveler={} stock=wheat6,bottle1,planks2,visitorCoins3"
            + " travelerHunger=100 residentHunger=55",
            settlement.id, tavern.id, host.getUUID(), resident.getUUID(), traveler.getUUID());
    }

    /**
     * Sequential staging only: it opens the already-created traveler's normal
     * hunger predicate after the free resident's physical terminal state. It
     * never reserves, quotes, accepts, pays, mounts, or transfers an order.
     */
    private static void openPaidVisitor(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
                                        Settlement settlement, Building tavern) {
        require(player.getPersistentData().getInt(MARKER + "Phase") == 1, "resident_phase_not_ready");
        UUID hostId = player.getPersistentData().getUUID(MARKER + "Host");
        UUID residentId = player.getPersistentData().getUUID(MARKER + "Resident");
        UUID travelerId = player.getPersistentData().getUUID(MARKER + "Traveler");
        SettlerEntity host = level.getEntity(hostId) instanceof SettlerEntity found ? found : null;
        SettlerEntity resident = level.getEntity(residentId) instanceof SettlerEntity found ? found : null;
        SettlerEntity traveler = level.getEntity(travelerId) instanceof SettlerEntity found ? found : null;
        Container chest = level.getBlockEntity(STOCK) instanceof Container found ? found : null;
        require(host != null && host.isAlive() && !TavernHostService.hasSession(host),
            "resident_order_not_terminal");
        require(resident != null && resident.isAlive() && resident.getHunger()
            > player.getPersistentData().getFloat(MARKER + "ResidentHungerBefore")
            && !resident.hasMeal() && !TavernHostService.hasOrderForGuest(resident),
            "resident_consumption_not_proven");
        require(traveler != null && traveler.isAlive() && traveler.isTraveler() && traveler.getHunger() >= 75
            && count(traveler.bag, ModItems.GOLD_COIN.get()) == 3, "traveler_start_changed");
        boolean residentStockExact = chest != null && count(chest, Items.WHEAT) == 3
            && count(chest, Items.GLASS_BOTTLE) == 1 && count(chest, Items.OAK_PLANKS) == 2
            && count(chest, ModItems.GOLD_COIN.get()) == 0 && readyMeals(chest) == 0;
        if (!residentStockExact) {
            logResidentStockMismatch(level, settlement, tavern, chest, host, resident, traveler);
            throw new IllegalStateException("resident_stock_or_payment_not_exact");
        }
        player.getPersistentData().putFloat(MARKER + "TravelerHungerBefore", 55.0F);
        traveler.setHunger(55);
        // The resident's full service can outlast the 1200-tick evening window;
        // reopen the same evening so the paid visitor is judged on service, not clock.
        level.setDayTime(level.getDayTime() - Math.floorMod(level.getDayTime(), 24000L) + 11_500L);
        settlement.lastTavernVisitorDay = com.hearthstead.settlement.SettlementManager.currentTavernVisitorDay(level);
        player.getPersistentData().putInt(MARKER + "Phase", 2);
        SettlementSavedData.get(level).setDirty();
        Hearthstead.LOGGER.info("HSQA_OWNER_RESTAURANT paid_visitor_open=true settlement={} tavern={} "
            + "resident=free stock=wheat3,bottle1,planks2,coins0 travelerCoins3",
            settlement.id, tavern.id);
    }

    /** Logs the exact failed resident-terminal ledger, then the caller stops fail-closed. */
    private static void logResidentStockMismatch(ServerLevel level, Settlement settlement, Building tavern,
                                                 Container chest, SettlerEntity host, SettlerEntity resident,
                                                 SettlerEntity traveler) {
        int wheat = chest == null ? -1 : count(chest, Items.WHEAT);
        int bottles = chest == null ? -1 : count(chest, Items.GLASS_BOTTLE);
        int planks = chest == null ? -1 : count(chest, Items.OAK_PLANKS);
        int chestCoins = chest == null ? -1 : count(chest, ModItems.GOLD_COIN.get());
        int meals = chest == null ? -1 : readyMeals(chest);
        int travelerCoins = traveler == null ? -1 : count(traveler.bag, ModItems.GOLD_COIN.get());
        Hearthstead.LOGGER.info("HSQA_OWNER_RESTAURANT_RESIDENT_GATE result=FAIL settlement={} tavern={} "
                + "expected=wheat3,bottle1,planks2,chestCoins0,readyMeals0,travelerCoins3 "
                + "actual=wheat{},bottle{},planks{},chestCoins{},readyMeals{},travelerCoins{} "
                + "slots={} hostSession={} residentOrder={} travelerOrder={}",
            settlement.id, tavern.id, wheat, bottles, planks, chestCoins, meals, travelerCoins,
            slotCounts(chest), servingPointer(level, host, TavernHostService.SESSION_KEY),
            servingPointer(level, resident, TavernHostService.ORDER_KEY),
            servingPointer(level, traveler, TavernHostService.ORDER_KEY));
        Hearthstead.LOGGER.info("HSQA_OWNER_RESTAURANT_MEAL_SEARCH dayTime={} residentPos={} "
                + "residentActivity={} residentVisit={} hostPos={} hostActivity={} hostHand={} "
                + "tavernWorkers={}",
            level.getDayTime(), resident == null ? "none" : resident.blockPosition(),
            resident == null ? "none" : resident.getActivity(),
            resident == null ? "none" : resident.tavernVisitDiagnostic(),
            host == null ? "none" : host.blockPosition(),
            host == null ? "none" : host.getActivity(),
            host == null ? "none" : host.getMainHandItem(), tavern.workers);
    }

    /** Read-only nonempty slot ledger; component-bearing stacks are labeled rather than normalized. */
    private static String slotCounts(Container chest) {
        if (chest == null) return "missing";
        StringBuilder slots = new StringBuilder();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!slots.isEmpty()) slots.append(',');
            slots.append(slot).append(':').append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .append('x').append(stack.getCount())
                .append(stack.getComponentsPatch().isEmpty() ? ":plain" : ":components");
        }
        return slots.isEmpty() ? "empty" : slots.toString();
    }

    /** Reads persisted session/order identity without calling a cleanup or state-transition API. */
    private static String servingPointer(ServerLevel level, SettlerEntity actor, String key) {
        if (actor == null || !actor.getPersistentData().hasUUID(key)) return "none";
        UUID id = actor.getPersistentData().getUUID(key);
        var entity = level.getEntity(id);
        return id + "@" + (entity instanceof TavernServingEntity serving
            ? serving.restaurantStage() : entity == null ? "missing" : "not_serving");
    }

    private static void assertCompleted(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
                                        Settlement settlement, Building tavern) {
        requireOriginals(level, settlement);
        UUID hostId = player.getPersistentData().getUUID(MARKER + "Host");
        UUID residentId = player.getPersistentData().getUUID(MARKER + "Resident");
        UUID travelerId = player.getPersistentData().getUUID(MARKER + "Traveler");
        require(player.getPersistentData().getInt(MARKER + "Phase") == 2, "paid_visitor_not_opened");
        SettlerEntity host = level.getEntity(hostId) instanceof SettlerEntity found ? found : null;
        SettlerEntity resident = level.getEntity(residentId) instanceof SettlerEntity found ? found : null;
        SettlerEntity traveler = level.getEntity(travelerId) instanceof SettlerEntity found ? found : null;
        Container chest = level.getBlockEntity(STOCK) instanceof Container found ? found : null;
        require(host != null && host.isAlive() && Employment.employerOf(settlement, hostId) == tavern,
            "host_changed");
        require(resident != null && resident.isAlive() && traveler != null && traveler.isAlive()
            && !residentId.equals(travelerId) && traveler.isTraveler(), "guest_identity_changed");
        require(chest != null && count(chest, Items.WHEAT) == 0 && count(chest, Items.GLASS_BOTTLE) == 1
            && count(chest, Items.OAK_PLANKS) == 2 && count(chest, ModItems.GOLD_COIN.get()) == 2
            && readyMeals(chest) == 0,
            "stock_or_payment_not_exact");
        require(count(traveler.bag, ModItems.GOLD_COIN.get()) == 1
            && resident.getHunger() > player.getPersistentData().getFloat(MARKER + "ResidentHungerBefore")
            && traveler.getHunger() > player.getPersistentData().getFloat(MARKER + "TravelerHungerBefore")
            && !resident.hasMeal() && !traveler.hasMeal()
            && !TavernHostService.hasOrderForGuest(resident)
            && !TavernHostService.hasOrderForGuest(traveler)
            && level.getEntitiesOfClass(TavernServingEntity.class, ROOM).isEmpty(),
            "orders_or_meals_not_terminal");
        Hearthstead.LOGGER.info("HSQA_OWNER_RESTAURANT_COMPLETE settlement={} tavern={} "
            + "resident=free travelerPaid=2 stock=wheat0,bottle1,planks2,coins2 travelerCoins1",
            settlement.id, tavern.id);
    }

    private static SettlerEntity traveler(ServerLevel level, Settlement settlement, Building tavern,
                                          String name, Vec3 pos) {
        SettlerEntity actor = ModEntities.SETTLER.get().create(level);
        require(actor != null, "traveler_creation_failed");
        actor.moveTo(pos.x, pos.y, pos.z, 180, 0);
        actor.setSettlerName(name);
        actor.setPersistenceRequired();
        actor.markTraveler(settlement.id, settlement.center);
        actor.setActivity(SettlerActivity.IDLE);
        // The paid guest is intentionally ineligible until the free resident
        // completes. openPaidVisitor changes only this hunger gate.
        actor.setHunger(100);
        actor.setEnergy(100);
        require(level.addFreshEntity(actor), "traveler_join_rejected");
        UUID transactionId = UUID.randomUUID();
        RecruitmentQuote quote = RecruitmentQuote.fromStartingAttributes(
            transactionId, actor.getUUID(), actor.attributes(), List.of());
        RecruitmentTransaction transaction = RecruitmentTransaction.fresh(settlement.id)
            .adminPrime(transactionId, level.getGameTime(), tavern.id, tavern.plaquePos,
                tavern.anchor, level.dimension().location())
            .travelerSpawned(actor.getUUID(), name, level.getGameTime(), quote)
            .arrived(level.getGameTime());
        settlement.applyRecruitment(transaction);
        require(TavernGuestPayment.initializeTraveler(actor)
            && count(actor.bag, ModItems.GOLD_COIN.get()) == 3, "visitor_purse_not_exact");
        return actor;
    }

    private static SettlerEntity resident(ServerLevel level, Settlement settlement, String name,
                                          Vec3 pos, float hunger) {
        SettlerEntity actor = ModEntities.SETTLER.get().create(level);
        require(actor != null, "resident_creation_failed");
        actor.moveTo(pos.x, pos.y, pos.z, 180, 0);
        actor.setSettlerName(name);
        actor.setPersistenceRequired();
        actor.bindTo(settlement.id, settlement.center);
        settlement.putRecord(actor.getUUID(), name, Profession.NONE);
        actor.setHunger(hunger);
        actor.setEnergy(100);
        require(level.addFreshEntity(actor), "resident_join_rejected");
        return actor;
    }

    private static void requireOriginals(ServerLevel level, Settlement settlement) {
        require(settlement.mayorId != null && settlement.mayorId.equals(ORIGINAL[1]), "mayor_changed");
        for (int i = 0; i < ORIGINAL.length; i++) {
            require(settlement.record(ORIGINAL[i]) != null
                && level.getEntity(ORIGINAL[i]) instanceof SettlerEntity actor && actor.isAlive()
                && actor.getProfession() == ORIGINAL_ROLES[i]
                && settlement.id.equals(actor.getSettlementId()), "original_changed_" + i);
        }
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        return total;
    }

    private static int readyMeals(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (ReadyFood.isReadyMeal(stack)) total += stack.getCount();
        }
        return total;
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalStateException(reason);
    }
}

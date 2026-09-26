package com.hearthstead.qa;

import com.hearthstead.BuildIdentity;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.*;
import com.hearthstead.settlement.request.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.util.UUID;

/** Explicit disposable-server fixture. Only setup is synthetic; FOOD work is ordinary AI. */
public final class FoodRestartQaFixtureService {
    public static final String DATA_NAME = "hearthstead_food_restart_fixture";
    private static final UUID BOOT = UUID.randomUUID();
    private static final BlockPos HEARTH = new BlockPos(520, 80, 520);
    private static final BlockPos SOURCE = new BlockPos(530, 80, 520);
    private static final BlockPos OTHER = new BlockPos(530, 80, 523);
    private static final BlockPos PLAQUE = new BlockPos(528, 81, 520);
    private static final AABB AREA = new AABB(512, 79, 512, 544, 86, 544);
    private FoodRestartQaFixtureService() { }

    public static int command(CommandSourceStack source, String action, String token) {
        try {
            ServerLevel level = source.getLevel();
            var world = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(source.getEntity() == null && source.hasPermission(4)
                && level.getServer().isSameThread()
                && level.getServer().getPlayerList().getPlayerCount() == 0
                && level.dimension().equals(Level.OVERWORLD)
                && token.matches("[a-f0-9]{32}")
                && token.equals(System.getProperty("hearthstead.qa.foodRestart"))
                && world.getFileName().toString().equals("food_restart_world")
                && Files.readString(world.getParent().resolve(".hsqa-instance-owned"))
                    .trim().equals("hsqa-instance-v1:food-restart"), "not_owned_enabled_qa_world");
            Fixture data = level.getDataStorage().computeIfAbsent(Fixture.FACTORY, DATA_NAME);
            if (action.equals("prepare")) {
                prepare(level, data, token);
            } else {
                require(token.equals(data.tag.getString("Token")), "fixture_token_changed");
                require(BuildIdentity.inputHash().equals(data.tag.getString("InputHash"))
                    && BuildIdentity.artifactFileName().equals(data.tag.getString("Artifact")),
                    "runtime_build_changed");
                require(level.getGameTime() >= data.tag.getLong("CreatedAt")
                    && level.getGameTime() - data.tag.getLong("CreatedAt") <= 2400L,
                    "fixture_exceeded_2400_game_ticks");
                check(level, data, action);
            }
            source.sendSuccess(() -> Component.literal("HSQA_FOOD_RESTART token=" + token
                + " action=" + action + " result=OK boot=" + BOOT
                + " input=" + BuildIdentity.inputHash() + " artifact=" + BuildIdentity.artifactFileName()), true);
            return 1;
        } catch (Waiting waiting) {
            source.sendSuccess(() -> Component.literal("HSQA_FOOD_RESTART token=" + token
                + " action=" + action + " result=WAIT boot=" + BOOT
                + " input=" + BuildIdentity.inputHash() + " artifact=" + BuildIdentity.artifactFileName()), true);
            return 0;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("HSQA_FOOD_RESTART token=" + token
                + " action=" + action + " result=FAIL reason=" + failure.getMessage()));
            return 0;
        }
    }

    private static void prepare(ServerLevel level, Fixture data, String token) {
        require(data.tag.isEmpty(), "fixture_already_exists");
        require(SettlementManager.data(level).settlements.isEmpty(), "world_already_has_settlement");
        require(level.getEntitiesOfClass(SettlerEntity.class, AREA).isEmpty(), "site_has_settlers");
        for (int x = 515; x <= 535; x++) for (int z = 515; z <= 525; z++) {
            for (int y = 79; y <= 84; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                require(level.isLoaded(pos) && level.getBlockState(pos).isAir()
                    && level.getBlockEntity(pos) == null, "site_not_empty_loaded_air");
            }
        }
        for (int x = 515; x <= 535; x++) for (int z = 515; z <= 525; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE_BRICKS.defaultBlockState());
        }
        // An enclosed 7x7 Hearth room prevents <=2.5-block delivery contact.
        // The empty Hearth still accepts FOOD selection; no inventory is made full.
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            for (int y = 0; y <= 3; y++) if (Math.abs(x) == 3 || Math.abs(z) == 3 || y == 3) {
                level.setBlockAndUpdate(HEARTH.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(HEARTH, ModBlocks.HEARTH.get().defaultBlockState());
        level.setBlockAndUpdate(SOURCE, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(OTHER, Blocks.CHEST.defaultBlockState());
        // Default NORTH-facing plaque requires its solid backing on the SOUTH face.
        level.setBlockAndUpdate(PLAQUE.south(), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(PLAQUE, ModBlocks.PLAQUE.get().defaultBlockState());
        Settlement settlement = new Settlement(UUID.randomUUID(), "FOOD restart QA", HEARTH);
        settlement.radius = 24;
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(HEARTH);
        require(hearth != null, "hearth_missing");
        hearth.bindSettlement(settlement.id);
        // Same explicit synthetic building contract as the dedicated BagQA fixture:
        // actual plaque, actual bounds and chests; not player progression evidence.
        Building warehouse = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            PLAQUE, PLAQUE, BoundingBox.fromCorners(new BlockPos(528, 80, 518), new BlockPos(533, 83, 524)));
        warehouse.valid = true;
        settlement.buildings.add(warehouse);
        SettlementManager.data(level).settlements.put(settlement.id, settlement);
        SettlerEntity courier = ModEntities.SETTLER.get().create(level);
        require(courier != null, "courier_creation_failed");
        courier.moveTo(531.5, 80, 520.5, 0, 0);
        courier.setSettlerName("FoodRestart Courier");
        courier.bindTo(settlement.id, HEARTH);
        settlement.putRecord(courier.getUUID(), courier.getSettlerName(), Profession.NONE);
        require(level.addFreshEntity(courier), "courier_spawn_failed");
        require(Employment.hire(level, settlement, warehouse, courier).ok(), "employment_refused");
        courier.setHunger(100.0F);
        courier.setPersistenceRequired();
        SettlementManager.data(level).setDirty();
        data.tag.putInt("Version", 1);
        data.tag.putString("Token", token);
        data.tag.putString("InputHash", BuildIdentity.inputHash());
        data.tag.putString("Artifact", BuildIdentity.artifactFileName());
        data.tag.putUUID("InitialBoot", BOOT);
        data.tag.putUUID("Settlement", settlement.id);
        data.tag.putUUID("Warehouse", warehouse.id);
        data.tag.putUUID("Courier", courier.getUUID());
        data.tag.putLong("CreatedAt", level.getGameTime());
        data.setDirty();
        // Final setup mutation. No request, bag or transport state is authored.
        Container chest = (Container) level.getBlockEntity(SOURCE);
        chest.setItem(0, new ItemStack(Items.BREAD, 4));
        chest.setChanged();
    }

    private static void check(ServerLevel level, Fixture data, String action) {
        CompoundTag tag = data.tag;
        Settlement settlement = SettlementManager.byId(level, tag.getUUID("Settlement"));
        require(settlement != null && settlement.center.equals(HEARTH), "settlement_changed");
        require(level.getEntity(tag.getUUID("Courier")) instanceof SettlerEntity, "courier_not_loaded");
        SettlerEntity courier = (SettlerEntity) level.getEntity(tag.getUUID("Courier"));
        require(courier.isAlive() && courier.getProfession() == Profession.COURIER
            && settlement.id.equals(courier.getSettlementId()) && HEARTH.equals(courier.getHearthPos())
            && courier.getHunger() > 80.0F, "worker_identity_or_hunger_changed");
        Building warehouse = settlement.buildings.stream()
            .filter(b -> b.id.equals(tag.getUUID("Warehouse"))).findFirst().orElseThrow();
        require(warehouse.valid && warehouse.type == BuildingType.WAREHOUSE
            && warehouse.workers.contains(courier.getUUID()), "employment_changed");
        require(level.getBlockEntity(HEARTH) instanceof HearthBlockEntity, "hearth_missing");
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(HEARTH);
        require(settlement.id.equals(hearth.getSettlementId()), "hearth_rebound");
        int source = bread((Container) level.getBlockEntity(SOURCE));
        int other = bread((Container) level.getBlockEntity(OTHER));
        int bag = bread(courier.bag);
        int target = 0;
        for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(i);
            if (stack.is(Items.BREAD)) target += stack.getCount();
        }
        require(source + bag + target == 4 && other == 0
            && level.getEntitiesOfClass(ItemEntity.class, AREA,
                e -> e.getItem().is(Items.BREAD)).isEmpty(), "physical_conservation_failed");
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (ledger == null) {
            require(action.equals("checkpoint") && source == 4 && bag == 0 && target == 0,
                "request_authority_missing");
            throw new Waiting();
        }
        require(!saved.rootQuarantined() && !ledger.quarantined(), "ledger_quarantined");
        RequestRecord request = tag.hasUUID("Request") ? ledger.any(tag.getUUID("Request"))
            : ledger.active().stream().filter(r -> r.type() == RequestType.FOOD
                && courier.getUUID().equals(r.courierId())).findFirst().orElse(null);
        if (request == null || request.movedCount() == 0) {
            require(action.equals("checkpoint") && source == 4 && bag == 0 && target == 0,
                "owned_request_missing");
            throw new Waiting();
        }
        boolean transportIdentityMatches = request.type() == RequestType.FOOD
            && request.settlementId().equals(settlement.id)
            && courier.getUUID().equals(request.courierId())
            && request.sourceBuildingId().equals(warehouse.id) && request.sourceContainer().equals(SOURCE)
            && request.targetBuildingId().equals(settlement.id) && request.targetContainer().equals(HEARTH)
            && request.fingerprint().itemId().toString().equals("minecraft:bread")
            && request.fingerprint().count() == 4;
        if (!transportIdentityMatches) {
            throw new IllegalStateException("transport_identity_changed"
                + " expected={type=FOOD,settlement=" + settlement.id + ",courier=" + courier.getUUID()
                + ",sourceBuilding=" + warehouse.id + ",sourceContainer=" + SOURCE
                + ",targetBuilding=" + settlement.id + ",targetContainer=" + HEARTH
                + ",requestedItem=minecraft:bread,requestedCount=4}"
                + " actual={type=" + request.type() + ",settlement=" + request.settlementId()
                + ",courier=" + request.courierId() + ",sourceBuilding=" + request.sourceBuildingId()
                + ",sourceContainer=" + request.sourceContainer() + ",targetBuilding="
                + request.targetBuildingId() + ",targetContainer=" + request.targetContainer()
                + ",requestedItem=" + request.fingerprint().itemId()
                + ",requestedCount=" + request.fingerprint().count() + ",pickedCount="
                + request.movedCount() + ",deliveredCount=" + request.deliveredCount()
                + ",state=" + request.state() + ",effectiveState=" + request.effectiveState() + "}");
        }
        // Source bags load one physical loaf per contact. Intermediate pickup
        // is legitimate, but the durable restart checkpoint still requires all
        // four exact units in custody and an empty source.
        if (action.equals("checkpoint") && request.movedCount() < 4) {
            require(request.movedCount() > 0 && request.deliveredCount() == 0
                && bag == request.movedCount() && source == 4 - bag && target == 0,
                "partial_pickup_conservation_failed");
            throw new Waiting();
        }
        require(request.movedCount() == 4, "transport_quantity_changed");
        require(source == 0, "source_reinsert_or_second_withdrawal");
        if (action.equals("complete")) {
            require(tag.getBoolean("Released") && tag.hasUUID("LoadedBoot")
                && BOOT.equals(tag.getUUID("LoadedBoot")), "not_released_on_second_boot");
            if (request.state() != RequestState.SATISFIED) throw new Waiting();
            require(bag == 0 && target == 4 && request.deliveredCount() == 4
                && request.hasFullTransportTrace(), "completion_not_physical");
            tag.putLong("CompletedAt", level.getGameTime());
        } else {
            require(bag == 4 && target == 0 && request.deliveredCount() == 0
                && request.effectiveState() == RequestState.IN_TRANSIT, "checkpoint_not_in_transit");
            for (int y = 0; y < 2; y++) require(level.getBlockState(HEARTH.offset(3, y, 0))
                .is(Blocks.STONE_BRICKS), "checkpoint_obstruction_changed");
            if (action.equals("checkpoint")) {
                require(BOOT.equals(tag.getUUID("InitialBoot")) && !tag.hasUUID("Request"),
                    "checkpoint_replayed_or_wrong_boot");
                tag.putUUID("Request", request.id());
                tag.putLong("CheckpointAt", level.getGameTime());
            } else if (action.equals("loaded")) {
                require(!BOOT.equals(tag.getUUID("InitialBoot")) && !tag.getBoolean("Released"),
                    "not_new_process");
                tag.putUUID("LoadedBoot", BOOT);
                tag.putLong("LoadedAt", level.getGameTime());
            } else if (action.equals("release")) {
                require(tag.hasUUID("LoadedBoot") && BOOT.equals(tag.getUUID("LoadedBoot"))
                    && !tag.getBoolean("Released"), "loaded_checkpoint_required");
                for (int y = 0; y < 2; y++) level.setBlockAndUpdate(HEARTH.offset(3, y, 0),
                    Blocks.AIR.defaultBlockState());
                tag.putBoolean("Released", true);
            } else throw new IllegalStateException("unknown_action");
        }
        data.setDirty();
    }

    private static int bread(Container inventory) {
        require(inventory != null, "inventory_missing");
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item.is(Items.BREAD)) count += item.getCount();
        }
        return count;
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalStateException(reason);
    }
    private static final class Waiting extends RuntimeException { }
    public static final class Fixture extends SavedData {
        private CompoundTag tag = new CompoundTag();
        private static final Factory<Fixture> FACTORY = new Factory<>(Fixture::new, Fixture::load, null);
        private static Fixture load(CompoundTag tag, HolderLookup.Provider registries) {
            Fixture fixture = new Fixture();
            fixture.tag = tag.copy();
            return fixture;
        }
        @Override public CompoundTag save(CompoundTag ignored, HolderLookup.Provider registries) {
            return tag.copy();
        }
    }
}

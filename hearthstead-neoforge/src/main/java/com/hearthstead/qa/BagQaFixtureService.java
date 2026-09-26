package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.BagTransferPresentation;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Narrow, permission-two native observer fixture for the real Courier route.
 *
 * <p>The fixture creates physical Hearths, plaques, warehouse chests and
 * Courier entities.  Once {@link #start} supplies Hearth goods, every pickup,
 * path, bag placement, animation clock and chest insert is owned by the normal
 * {@code CourierWorkGoal}; this class never publishes an animation or writes a
 * Courier bag.  One lane fills its destination during the authored reach to
 * exercise the same live-capacity interruption a player can cause.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class BagQaFixtureService {
    private static final int WIDTH = 48;
    private static final int DEPTH = 20;
    private static final int[] MILESTONES = {0, 12, 30, 31, 36, 40, 48, 49, 64, 80};
    private static final WeakHashMap<ServerLevel, Session> SESSIONS =
        new WeakHashMap<>();

    private BagQaFixtureService() {
    }

    public enum Stage { INVALID, PREPARED, STARTED }

    public record Result(Stage stage, UUID sessionId, BlockPos origin,
                         BlockPos camera, String detail) {
        public boolean ok() {
            return stage != Stage.INVALID;
        }
    }

    /** Builds three isolated production routes but supplies no source goods. */
    public static Result prepare(ServerLevel level, ServerPlayer actor,
                                 BlockPos origin) {
        if (level == null || actor == null || origin == null
                || !level.getServer().isSameThread()) {
            return invalid(origin, "invalid_context");
        }
        Session existing = SESSIONS.get(level);
        if (existing != null) {
            return new Result(existing.started ? Stage.STARTED : Stage.PREPARED,
                existing.id, existing.origin, existing.camera,
                "session_already_exists");
        }
        if (!clearAndLoaded(level, origin)) {
            return invalid(origin, "site_not_empty_or_loaded");
        }

        buildFloor(level, origin);
        UUID sessionId = UUID.randomUUID();
        List<Lane> lanes = new ArrayList<>();
        Lane partial = createLane(level, origin, sessionId, "partial", 5,
            2, false, false);
        Lane interrupted = createLane(level, origin, sessionId, "full_interrupt", 21,
            6, true, false);
        Lane layered = createLane(level, origin, sessionId, "layered_normal", 37,
            8, false, true);
        if (partial == null || interrupted == null || layered == null) {
            return invalid(origin, "physical_fixture_creation_failed");
        }
        lanes.add(partial);
        lanes.add(interrupted);
        lanes.add(layered);
        BlockPos camera = origin.offset(23, 4, 19);
        Session session = new Session(sessionId, origin.immutable(), camera,
            lanes);
        SESSIONS.put(level, session);
        SettlementSavedData.get(level).setDirty();
        emitSession(level, session, "prepared");
        return new Result(Stage.PREPARED, sessionId, origin, camera,
            "three_real_courier_routes_ready;run_bagqa_start");
    }

    /** Supplies the three physical Hearth inventories and releases normal AI. */
    public static Result start(ServerLevel level) {
        Session session = SESSIONS.get(level);
        if (session == null || !level.getServer().isSameThread()) {
            return invalid(null, "no_prepared_session");
        }
        if (session.started) {
            return new Result(Stage.STARTED, session.id, session.origin,
                session.camera, "already_started");
        }
        for (Lane lane : session.lanes) {
            if (!(level.getBlockEntity(lane.hearthPos) instanceof HearthBlockEntity hearth)
                    || hearth.getSettlementId() == null
                    || !hearth.getSettlementId().equals(lane.settlementId)) {
                return invalid(session.origin, "hearth_authority_changed:" + lane.name);
            }
            ItemStack left = hearth.insertGoods(new ItemStack(Items.OAK_LOG,
                lane.sourceCount));
            if (!left.isEmpty()) {
                return invalid(session.origin, "hearth_insert_refused:" + lane.name);
            }
        }
        level.setDayTime(2000L);
        session.started = true;
        emitSession(level, session, "started");
        return new Result(Stage.STARTED, session.id, session.origin,
            session.camera, "production_ai_released");
    }

    /** Emits an exact read of every live authority named by the fixture. */
    public static Result status(ServerLevel level) {
        Session session = SESSIONS.get(level);
        if (session == null || !level.getServer().isSameThread()) {
            return invalid(null, "no_prepared_session");
        }
        emitSession(level, session, "status");
        return new Result(session.started ? Stage.STARTED : Stage.PREPARED,
            session.id, session.origin, session.camera, "snapshot_logged");
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Session session = SESSIONS.get(level);
        if (session == null || !session.started) {
            return;
        }
        for (Lane lane : session.lanes) {
            observeLane(level, session, lane);
        }
    }

    private static Lane createLane(ServerLevel level, BlockPos origin,
                                   UUID sessionId, String name, int x,
                                   int sourceCount, boolean interrupt,
                                   boolean layered) {
        BlockPos hearthPos = origin.offset(x, 1, 4);
        BlockPos plaquePos = origin.offset(x - 2, 2, 13);
        BlockPos chestPos = origin.offset(x, 1, 14);
        level.setBlockAndUpdate(hearthPos,
            ModBlocks.HEARTH.get().defaultBlockState());
        level.setBlockAndUpdate(plaquePos,
            ModBlocks.PLAQUE.get().defaultBlockState());
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(hearthPos) instanceof HearthBlockEntity hearth)
                || !(level.getBlockEntity(chestPos) instanceof Container chest)) {
            return null;
        }

        Settlement settlement = new Settlement(UUID.randomUUID(),
            "BagQA-" + name, hearthPos);
        settlement.radius = 18;
        hearth.bindSettlement(settlement.id);
        Building warehouse = new Building(UUID.randomUUID(),
            BuildingType.WAREHOUSE, plaquePos, plaquePos,
            BoundingBox.fromCorners(origin.offset(x - 3, 1, 12),
                origin.offset(x + 3, 4, 16)));
        warehouse.valid = true;
        settlement.buildings.add(warehouse);
        SettlementSavedData.get(level).settlements.put(settlement.id, settlement);

        if (name.equals("partial") || interrupt) {
            fillForTwoItemRoom(chest);
        }

        SettlerEntity worker = ModEntities.SETTLER.get().create(level);
        if (worker == null) {
            return null;
        }
        BlockPos spawn = origin.offset(x, 1, 6);
        worker.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
            0.0F, 0.0F);
        worker.setSettlerName("BagQA " + name);
        worker.finalizeSpawn(level, level.getCurrentDifficultyAt(spawn),
            MobSpawnType.MOB_SUMMONED, null);
        worker.bindTo(settlement.id, hearthPos);
        settlement.putRecord(worker.getUUID(), worker.getSettlerName(),
            Profession.NONE);
        worker.assignProfession(Profession.COURIER);
        worker.addTag("hsqa_bag_" + sessionId);
        worker.addTag("hsqa_bag_lane_" + name);
        if (layered) {
            worker.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(Items.IRON_AXE));
            worker.setItemSlot(EquipmentSlot.HEAD,
                new ItemStack(Items.LEATHER_HELMET));
            worker.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(Items.LEATHER_CHESTPLATE));
            worker.setItemSlot(EquipmentSlot.LEGS,
                new ItemStack(Items.LEATHER_LEGGINGS));
            worker.setItemSlot(EquipmentSlot.FEET,
                new ItemStack(Items.LEATHER_BOOTS));
        }
        if (!level.addFreshEntity(worker)) {
            settlement.removeRecord(worker.getUUID());
            return null;
        }
        return new Lane(name, settlement.id, worker.getUUID(), hearthPos,
            chestPos, sourceCount, interrupt);
    }

    private static void observeLane(ServerLevel level, Session session, Lane lane) {
        if (!(level.getEntity(lane.workerId) instanceof SettlerEntity worker)) {
            if (!lane.missingLogged) {
                Hearthstead.LOGGER.error(
                    "HSQA_BAG event=worker_missing session={} lane={} worker={}",
                    session.id, lane.name, lane.workerId);
                lane.missingLogged = true;
            }
            return;
        }
        BagTransferPresentation presentation = worker.bagTransferPresentation();
        UUID transfer = presentation.transferId();
        int clock = presentation.active() ? presentation.clock() : -1;
        boolean transferChanged = transfer != null && !transfer.equals(lane.lastTransfer);
        if (transferChanged || isMilestone(clock) && clock != lane.lastLoggedClock) {
            logLane(level, session, lane, worker,
                transferChanged ? "transfer_begin" : "clock");
            lane.lastTransfer = transfer;
            lane.lastLoggedClock = clock;
        }
        if (lane.interrupt && !lane.interruptionApplied
                && presentation.active() && clock >= 40 && clock < 48) {
            int before = countIn(container(level, lane.chestPos), Items.OAK_LOG);
            Container chest = container(level, lane.chestPos);
            if (chest == null || before != 62) {
                Hearthstead.LOGGER.error(
                    "HSQA_BAG event=interrupt_refused session={} lane={} chestBefore={} expected=62",
                    session.id, lane.name, before);
                lane.interruptionApplied = true;
                return;
            }
            chest.setItem(0, new ItemStack(Items.OAK_LOG, 64));
            chest.setChanged();
            lane.interruptionApplied = true;
            Hearthstead.LOGGER.info(
                "HSQA_BAG event=interrupt_applied session={} lane={} transfer={} clock={} chestBefore={} chestNow={} bag={}",
                session.id, lane.name, presentation.transferId(), clock, before,
                countIn(chest, Items.OAK_LOG), bagCount(worker));
        }
        if (!presentation.active() && lane.lastTransfer != null
                && !lane.transferEndLogged) {
            logLane(level, session, lane, worker, "transfer_end");
            lane.transferEndLogged = true;
        }
    }

    private static void emitSession(ServerLevel level, Session session,
                                    String event) {
        Hearthstead.LOGGER.info(
            "HSQA_BAG event={} session={} origin={} camera={} started={} gameTime={} dayTime={}",
            event, session.id, pos(session.origin), pos(session.camera),
            session.started, level.getGameTime(), level.getDayTime());
        for (Lane lane : session.lanes) {
            if (level.getEntity(lane.workerId) instanceof SettlerEntity worker) {
                logLane(level, session, lane, worker, event);
            } else {
                Hearthstead.LOGGER.error(
                    "HSQA_BAG event={} session={} lane={} worker={} state=missing",
                    event, session.id, lane.name, lane.workerId);
            }
        }
    }

    private static void logLane(ServerLevel level, Session session, Lane lane,
                                SettlerEntity worker, String event) {
        BagTransferPresentation p = worker.bagTransferPresentation();
        ItemStack item = p.item();
        String lid = p.clock() == 31 ? "open31"
            : p.clock() == 64 ? "close64" : "none";
        Hearthstead.LOGGER.info(
            "HSQA_BAG event={} session={} lane={} worker={} activity={} transfer={} clock={} committed={} item={} itemCount={} bagAnchor={} placedBag={} entityPos={} chest={} chestNow={} bag={} source={} lid={} mainhand={} armorPieces={}",
            event, session.id, lane.name, worker.getUUID(), worker.getActivity(),
            p.transferId() == null ? "none" : p.transferId(),
            p.active() ? p.clock() : -1, p.active() && p.committed(),
            item.isEmpty() ? "none" : BuiltInRegistries.ITEM.getKey(item.getItem()),
            item.getCount(), pos(p.bagAnchor()), pos(worker.placedWorkContainerPos()),
            precisePos(worker), pos(lane.chestPos),
            countIn(container(level, lane.chestPos), Items.OAK_LOG),
            bagCount(worker), sourceCount(level, lane.hearthPos), lid,
            BuiltInRegistries.ITEM.getKey(worker.getMainHandItem().getItem()),
            armorPieces(worker));
    }

    private static void fillForTwoItemRoom(Container chest) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 62));
        chest.setChanged();
    }

    private static boolean clearAndLoaded(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin,
                origin.offset(WIDTH - 1, 5, DEPTH - 1))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void buildFloor(ServerLevel level, BlockPos origin) {
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                level.setBlockAndUpdate(origin.offset(x, 0, z),
                    (x % 16 == 5 && z % 5 == 0 ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS)
                        .defaultBlockState());
            }
        }
    }

    private static Container container(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    private static int countIn(Container container, net.minecraft.world.item.Item item) {
        if (container == null) return -1;
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int bagCount(SettlerEntity worker) {
        int count = 0;
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            count += worker.bag.getItem(slot).getCount();
        }
        return count;
    }

    private static int sourceCount(ServerLevel level, BlockPos hearthPos) {
        if (!(level.getBlockEntity(hearthPos) instanceof HearthBlockEntity hearth)) {
            return -1;
        }
        int count = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(Items.OAK_LOG)) count += stack.getCount();
        }
        return count;
    }

    private static int armorPieces(SettlerEntity worker) {
        int count = 0;
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            if (!worker.getItemBySlot(slot).isEmpty()) count++;
        }
        return count;
    }

    private static boolean isMilestone(int clock) {
        for (int milestone : MILESTONES) {
            if (clock == milestone) return true;
        }
        return false;
    }

    private static String pos(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String precisePos(SettlerEntity worker) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
            worker.getX(), worker.getY(), worker.getZ());
    }

    private static Result invalid(BlockPos origin, String detail) {
        return new Result(Stage.INVALID, null, origin, null, detail);
    }

    private static final class Session {
        private final UUID id;
        private final BlockPos origin;
        private final BlockPos camera;
        private final List<Lane> lanes;
        private boolean started;

        private Session(UUID id, BlockPos origin, BlockPos camera,
                        List<Lane> lanes) {
            this.id = id;
            this.origin = origin;
            this.camera = camera;
            this.lanes = List.copyOf(lanes);
        }
    }

    private static final class Lane {
        private final String name;
        private final UUID settlementId;
        private final UUID workerId;
        private final BlockPos hearthPos;
        private final BlockPos chestPos;
        private final int sourceCount;
        private final boolean interrupt;
        private UUID lastTransfer;
        private int lastLoggedClock = Integer.MIN_VALUE;
        private boolean interruptionApplied;
        private boolean transferEndLogged;
        private boolean missingLogged;

        private Lane(String name, UUID settlementId, UUID workerId,
                     BlockPos hearthPos, BlockPos chestPos, int sourceCount,
                     boolean interrupt) {
            this.name = name;
            this.settlementId = settlementId;
            this.workerId = workerId;
            this.hearthPos = hearthPos;
            this.chestPos = chestPos;
            this.sourceCount = sourceCount;
            this.interrupt = interrupt;
        }
    }
}

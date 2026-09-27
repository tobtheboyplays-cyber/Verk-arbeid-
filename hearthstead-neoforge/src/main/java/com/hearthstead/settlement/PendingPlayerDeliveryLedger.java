package com.hearthstead.settlement;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded source-owned outbox for paid or earned player items.
 *
 * <p>The outbox is embedded in the same NBT compound as the sale/offer that
 * created it. A successful source commit therefore always has either a real
 * player/world owner or one exact durable row. Full hands, a full inventory,
 * an unloaded fallback position and a rejected {@code addFreshEntity} all
 * leave that row intact for login/player-tick retry.
 *
 * <p>Every output carries a stable delivery id in its item components. World
 * fallback delegates to {@link DeferredItemMaterializationSavedData} using
 * that same id, exact stack, position and pickup owner. Replaying either side
 * of a save tear can therefore acknowledge the existing item but cannot mint
 * a second physical copy.
 */
public final class PendingPlayerDeliveryLedger {
    public static final int DATA_VERSION = 1;
    public static final int MAX_PENDING = 64;
    public static final int RETRIES_PER_PLAYER_TICK = 4;
    private static final int MAX_STACK_NBT_CHARS = 32_768;
    private static final double WORLD_LIMIT = 30_000_000.0D;
    private static final String MARKER_ROOT =
        "HearthsteadPendingPlayerDeliveryV1";

    public enum ReserveResult {
        INSERTED,
        IDEMPOTENT,
        CAPACITY,
        COLLISION,
        INVALID,
        QUARANTINED;

        public boolean accepted() {
            return this == INSERTED || this == IDEMPOTENT;
        }
    }

    public enum Outcome {
        MAIN_HAND("main_hand"),
        OFF_HAND("off_hand"),
        INVENTORY("inventory"),
        DROP("drop"),
        ALREADY_DELIVERED("already_delivered"),
        PENDING("pending");

        private final String id;

        Outcome(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public boolean delivered() {
            return this != PENDING;
        }
    }

    /** A source-ready, exact serialized output. */
    public record Reservation(UUID id, UUID playerId, double x, double y,
                              double z, CompoundTag stackTag) {
        public Reservation {
            stackTag = stackTag == null ? new CompoundTag() : stackTag.copy();
        }

        @Override
        public CompoundTag stackTag() {
            return stackTag.copy();
        }
    }

    /** changed means the containing SavedData must be marked dirty. */
    public record DeliveryResult(Outcome outcome, boolean changed) {
    }

    private static final class Pending {
        final Reservation reservation;
        boolean worldFallback;

        Pending(Reservation reservation, boolean worldFallback) {
            this.reservation = reservation;
            this.worldFallback = worldFallback;
        }
    }

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private boolean quarantined;
    @Nullable private CompoundTag quarantinedSource;

    /**
     * Creates the one authoritative serialized output before the source
     * mutates payment, revision, offer or issued counters.
     */
    @Nullable
    public static Reservation reservation(ServerLevel level, UUID deliveryId,
                                          ServerPlayer player,
                                          ItemStack source) {
        if (level == null || player == null || source == null || source.isEmpty()
            || isNil(deliveryId) || isNil(player.getUUID())
            || !finiteWorldCoordinate(player.getX())
            || !Double.isFinite(player.getY())
            || !finiteWorldCoordinate(player.getZ())) {
            return null;
        }
        ItemStack exact = stamp(source, deliveryId, player.getUUID());
        Tag encoded = exact.saveOptional(level.registryAccess());
        if (!(encoded instanceof CompoundTag stackTag)
            || stackTag.toString().length() > MAX_STACK_NBT_CHARS) {
            return null;
        }
        Reservation reservation = new Reservation(deliveryId, player.getUUID(),
            player.getX(), player.getY() + 0.5D, player.getZ(), stackTag);
        return structurallyValid(reservation) ? reservation : null;
    }

    public synchronized ReserveResult reserve(@Nullable Reservation reservation) {
        if (quarantined) {
            return ReserveResult.QUARANTINED;
        }
        if (!structurallyValid(reservation)) {
            return ReserveResult.INVALID;
        }
        Pending existing = pending.get(reservation.id());
        if (existing != null) {
            return sameReservation(existing.reservation, reservation)
                ? ReserveResult.IDEMPOTENT : ReserveResult.COLLISION;
        }
        if (pending.size() >= MAX_PENDING) {
            return ReserveResult.CAPACITY;
        }
        pending.put(reservation.id(), new Pending(reservation, false));
        return ReserveResult.INSERTED;
    }

    /** Rollback is legal only before the enclosing source commit succeeds. */
    public synchronized boolean cancel(UUID deliveryId) {
        return deliveryId != null && pending.remove(deliveryId) != null;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized boolean hasPending(UUID deliveryId) {
        return deliveryId != null && pending.containsKey(deliveryId);
    }

    public synchronized boolean quarantined() {
        return quarantined;
    }

    /**
     * Attempts one exact delivery. PENDING always means this ledger still owns
     * the stack; every success removes the row only after ownership is proven.
     */
    public synchronized DeliveryResult deliver(ServerLevel level,
                                               ServerPlayer player,
                                               UUID deliveryId) {
        Pending row = deliveryId == null ? null : pending.get(deliveryId);
        if (quarantined || row == null || level == null || player == null
            || !row.reservation.playerId().equals(player.getUUID())
            || player.serverLevel() != level) {
            return new DeliveryResult(Outcome.PENDING, false);
        }
        ItemStack exact = decode(level, row.reservation);
        if (exact.isEmpty() || !markedFor(exact, row.reservation.id(),
                row.reservation.playerId())) {
            quarantined = true;
            return new DeliveryResult(Outcome.PENDING, true);
        }

        if (anyOnlinePlayerOwns(level, player, exact)) {
            pending.remove(deliveryId);
            return new DeliveryResult(Outcome.ALREADY_DELIVERED, true);
        }

        DeferredItemMaterializationSavedData drops =
            DeferredItemMaterializationSavedData.get(level);
        DeferredItemMaterializationSavedData.ItemEntityOptions options =
            options(row.reservation.playerId());

        // Cross-ledger save tear: DeferredItemMaterialization may have
        // successfully removed its durable row and left the physical entity,
        // while this source-owned row survived from the previous save. The
        // stable UUID plus exact marked stack and pickup target are sufficient
        // proof even after the entity has fallen or flowed away from its spawn
        // coordinates. Acknowledge it before any second inventory handoff.
        if (drops.materializedExact(level, row.reservation.id(),
                row.reservation.x(), row.reservation.y(), row.reservation.z(),
                exact, options)) {
            pending.remove(deliveryId);
            return new DeliveryResult(Outcome.ALREADY_DELIVERED, true);
        }
        boolean changed = false;
        if (!row.worldFallback && drops.containsExact(level,
                row.reservation.id(), row.reservation.x(), row.reservation.y(),
                row.reservation.z(), exact, options)) {
            // Save tear: the world outbox survived before this source row's
            // phase flip. Never race it with a second inventory handoff.
            row.worldFallback = true;
            changed = true;
        }

        if (!row.worldFallback) {
            Outcome direct = deliverDirect(player, exact);
            if (direct != Outcome.PENDING) {
                com.hearthstead.network.PickupNoticeNetwork.notify(player, exact, exact.getCount());
                pending.remove(deliveryId);
                return new DeliveryResult(direct, true);
            }
        }

        DeferredItemMaterializationSavedData.QueueResult queued = drops.queue(
            level, row.reservation.id(), row.reservation.x(),
            row.reservation.y(), row.reservation.z(), exact, options);
        if (!queued.accepted()) {
            return new DeliveryResult(Outcome.PENDING, changed);
        }
        boolean phaseChanged = !row.worldFallback;
        row.worldFallback = true;
        if (!drops.materialize(level, row.reservation.id())) {
            return new DeliveryResult(Outcome.PENDING, phaseChanged);
        }
        pending.remove(deliveryId);
        return new DeliveryResult(Outcome.DROP, true);
    }

    /** Bounded login/player-tick retry for this exact player. */
    public synchronized int retry(ServerLevel level, ServerPlayer player) {
        if (quarantined || level == null || player == null) {
            return 0;
        }
        int changed = 0;
        int attempted = 0;
        for (UUID id : List.copyOf(pending.keySet())) {
            Pending row = pending.get(id);
            if (row == null || !row.reservation.playerId().equals(player.getUUID())) {
                continue;
            }
            if (attempted++ >= RETRIES_PER_PLAYER_TICK) {
                break;
            }
            if (deliver(level, player, id).changed()) {
                changed++;
            }
        }
        return changed;
    }

    public synchronized CompoundTag writeNbt() {
        if (quarantined && quarantinedSource != null) {
            CompoundTag retained = quarantinedSource.copy();
            retained.putBoolean("Quarantined", true);
            return retained;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rows = new ListTag();
        for (Pending row : pending.values()) {
            Reservation reservation = row.reservation;
            CompoundTag out = new CompoundTag();
            out.putUUID("Id", reservation.id());
            out.putUUID("Player", reservation.playerId());
            out.putDouble("X", reservation.x());
            out.putDouble("Y", reservation.y());
            out.putDouble("Z", reservation.z());
            out.put("Stack", reservation.stackTag());
            out.putBoolean("WorldFallback", row.worldFallback);
            rows.add(out);
        }
        tag.put("Pending", rows);
        return tag;
    }

    public static PendingPlayerDeliveryLedger readNbt(@Nullable CompoundTag tag) {
        PendingPlayerDeliveryLedger ledger = new PendingPlayerDeliveryLedger();
        if (tag == null || !tag.contains("DataVersion", Tag.TAG_INT)
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.contains("Quarantined", Tag.TAG_BYTE)
            || !(tag.get("Pending") instanceof ListTag rows)
            || rows.size() > MAX_PENDING || tag.getBoolean("Quarantined")) {
            return quarantined(tag);
        }
        for (int i = 0; i < rows.size(); i++) {
            Tag raw = rows.get(i);
            if (!(raw instanceof CompoundTag entry)
                || !entry.hasUUID("Id") || !entry.hasUUID("Player")
                || !entry.contains("X", Tag.TAG_DOUBLE)
                || !entry.contains("Y", Tag.TAG_DOUBLE)
                || !entry.contains("Z", Tag.TAG_DOUBLE)
                || !(entry.get("Stack") instanceof CompoundTag stack)
                || !entry.contains("WorldFallback", Tag.TAG_BYTE)) {
                return quarantined(tag);
            }
            Reservation reservation = new Reservation(entry.getUUID("Id"),
                entry.getUUID("Player"), entry.getDouble("X"),
                entry.getDouble("Y"), entry.getDouble("Z"), stack);
            if (!structurallyValid(reservation)
                || ledger.pending.putIfAbsent(reservation.id(),
                    new Pending(reservation,
                        entry.getBoolean("WorldFallback"))) != null) {
                return quarantined(tag);
            }
        }
        return ledger;
    }

    private static PendingPlayerDeliveryLedger quarantined(
            @Nullable CompoundTag source) {
        PendingPlayerDeliveryLedger ledger = new PendingPlayerDeliveryLedger();
        ledger.quarantined = true;
        ledger.quarantinedSource = source == null
            ? new CompoundTag() : source.copy();
        return ledger;
    }

    private static Outcome deliverDirect(ServerPlayer player, ItemStack exact) {
        if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, exact.copy());
            return ownsExact(player.getMainHandItem(), exact)
                ? Outcome.MAIN_HAND : Outcome.PENDING;
        }
        if (player.getOffhandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.OFF_HAND, exact.copy());
            return ownsExact(player.getOffhandItem(), exact)
                ? Outcome.OFF_HAND : Outcome.PENDING;
        }
        boolean hasSpace = player.getInventory().getFreeSlot() >= 0
            || player.getInventory().getSlotWithRemainingSpace(exact) >= 0;
        if (!hasSpace) {
            return Outcome.PENDING;
        }
        ItemStack copy = exact.copy();
        player.getInventory().add(copy);
        return inventoryOwns(player, exact) ? Outcome.INVENTORY : Outcome.PENDING;
    }

    private static boolean anyOnlinePlayerOwns(ServerLevel level,
                                                ServerPlayer target,
                                                ItemStack exact) {
        if (inventoryOwns(target, exact)) {
            return true;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player != target && player.serverLevel() == level
                && inventoryOwns(player, exact)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inventoryOwns(ServerPlayer player, ItemStack exact) {
        if (ownsExact(player.getMainHandItem(), exact)
            || ownsExact(player.getOffhandItem(), exact)) {
            return true;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (ownsExact(player.getInventory().getItem(slot), exact)) {
                return true;
            }
        }
        return false;
    }

    /** Read-only receipt projection; canonical player slots, never menu slot ids. */
    public static int exactRecipientSlot(ServerLevel level, ServerPlayer player,
                                         Reservation reservation) {
        if (level == null || player == null || reservation == null
                || player.serverLevel() != level
                || !player.getUUID().equals(reservation.playerId())) return -1;
        ItemStack exact = decode(level, reservation);
        if (exact.isEmpty() || !markedFor(exact, reservation.id(), reservation.playerId())) return -1;
        for (int slot = 0; slot < 36; slot++) {
            if (ownsExact(player.getInventory().getItem(slot), exact)) return slot;
        }
        return ownsExact(player.getInventory().getItem(40), exact) ? 40 : -1;
    }

    /** Shared client/server proof against the exact stamped expected output. */
    public static boolean matchesDelivery(ItemStack candidate, ItemStack source,
                                          UUID deliveryId, UUID playerId) {
        return source != null && !source.isEmpty() && !isNil(deliveryId) && !isNil(playerId)
            && candidate != null && markedFor(candidate, deliveryId, playerId)
            && ownsExact(candidate, stamp(source, deliveryId, playerId));
    }

    private static boolean ownsExact(ItemStack candidate, ItemStack exact) {
        return candidate != null && !candidate.isEmpty()
            && candidate.getCount() >= exact.getCount()
            && ItemStack.isSameItemSameComponents(candidate, exact);
    }

    private static ItemStack decode(ServerLevel level, Reservation reservation) {
        try {
            return ItemStack.parseOptional(level.registryAccess(),
                reservation.stackTag());
        } catch (RuntimeException malformed) {
            return ItemStack.EMPTY;
        }
    }

    private static DeferredItemMaterializationSavedData.ItemEntityOptions options(
            UUID playerId) {
        return new DeferredItemMaterializationSavedData.ItemEntityOptions(
            new CompoundTag(), 10, playerId, true, true);
    }

    private static ItemStack stamp(ItemStack source, UUID deliveryId,
                                   UUID playerId) {
        ItemStack exact = source.copy();
        CompoundTag marker = new CompoundTag();
        marker.putInt("DataVersion", DATA_VERSION);
        marker.putUUID("Delivery", deliveryId);
        marker.putUUID("Player", playerId);
        CustomData.update(DataComponents.CUSTOM_DATA, exact,
            root -> root.put(MARKER_ROOT, marker));
        return exact;
    }

    private static boolean markedFor(ItemStack stack, UUID deliveryId,
                                     UUID playerId) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return false;
        }
        CompoundTag root = custom.copyTag();
        if (!(root.get(MARKER_ROOT) instanceof CompoundTag marker)
            || !marker.contains("DataVersion", Tag.TAG_INT)
            || marker.getInt("DataVersion") != DATA_VERSION
            || !marker.hasUUID("Delivery") || !marker.hasUUID("Player")) {
            return false;
        }
        return deliveryId.equals(marker.getUUID("Delivery"))
            && playerId.equals(marker.getUUID("Player"));
    }

    private static boolean sameReservation(Reservation left,
                                           Reservation right) {
        return left.id().equals(right.id())
            && left.playerId().equals(right.playerId())
            && Double.compare(left.x(), right.x()) == 0
            && Double.compare(left.y(), right.y()) == 0
            && Double.compare(left.z(), right.z()) == 0
            && left.stackTag().equals(right.stackTag());
    }

    private static boolean structurallyValid(@Nullable Reservation value) {
        return value != null && !isNil(value.id()) && !isNil(value.playerId())
            && finiteWorldCoordinate(value.x()) && Double.isFinite(value.y())
            && finiteWorldCoordinate(value.z()) && !value.stackTag().isEmpty()
            && value.stackTag().toString().length() <= MAX_STACK_NBT_CHARS;
    }

    private static boolean finiteWorldCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= WORLD_LIMIT;
    }

    private static boolean isNil(@Nullable UUID value) {
        return value == null || value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L;
    }
}

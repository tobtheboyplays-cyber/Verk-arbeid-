package com.hearthstead.settlement.request;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M1 "Crafting orders" (minecolonies-logistics-plan.md): one settlement's
 * open and recently closed crafting orders. Pure data, no world access, so
 * the exact-once and cap rules are unit-testable.
 *
 * <p><b>Rules this class owns.</b> At most one non-terminal order per
 * (requester, item); at most {@link #MAX_ACTIVE} non-terminal orders per
 * settlement; a terminal transition happens exactly once (a second close of
 * the same order is refused and changes nothing). Orders never gate work:
 * they only tell a workshop what to prefer and a player what is missing.
 */
public final class CraftingOrderBook {
    public static final int MAX_ACTIVE = 32;
    public static final int MAX_HISTORY = 16;
    public static final int MAX_COUNT = 64;

    /** Which existing deficit path raised the need. Wire ids are stable. */
    public enum Source {
        MATERIAL(0, "material"),
        FUEL(1, "fuel"),
        TAVERN(2, "tavern"),
        EQUIPMENT(3, "equipment"),
        FOOD(4, "food"),
        AMMUNITION(5, "ammunition"),
        DIRECT(6, "direct");

        private final int wireId;
        private final String id;

        Source(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() { return wireId; }
        public String id() { return id; }

        static Source fromWireId(int wireId) {
            for (Source value : values()) {
                if (value.wireId == wireId) return value;
            }
            return DIRECT;
        }
    }

    /** Order lifecycle. FULFILLED and CANCELLED are terminal. */
    public enum Status {
        /** A staffed workshop can make it and prefers it until made. */
        OPEN(0, false),
        /** The ordered count has physically landed in the workshop's chests. */
        CRAFTED(1, false),
        /** No staffed workshop in the settlement can make it: the player must. */
        NEEDS_PLAYER(2, false),
        FULFILLED(3, true),
        CANCELLED(4, true);

        private final int wireId;
        private final boolean terminal;

        Status(int wireId, boolean terminal) {
            this.wireId = wireId;
            this.terminal = terminal;
        }

        public int wireId() { return wireId; }
        public boolean terminal() { return terminal; }

        static Optional<Status> fromWireId(int wireId) {
            for (Status value : values()) {
                if (value.wireId == wireId) return Optional.of(value);
            }
            return Optional.empty();
        }
    }

    public enum OpenResult { CREATED, DUPLICATE, CAPPED, INVALID }

    public record OpenDecision(OpenResult result, @Nullable Order order) {
    }

    /** One order. Mutated only through the book. */
    public static final class Order {
        private final UUID id;
        private final UUID requesterId;
        private final ResourceLocation itemId;
        private int count;
        private final Source source;
        @Nullable private UUID workshopId;
        private Status status;
        private int made;
        private final long createdTick;
        private long updatedTick;

        Order(UUID id, UUID requesterId, ResourceLocation itemId, int count,
              Source source, @Nullable UUID workshopId, Status status,
              int made, long createdTick, long updatedTick) {
            this.id = id;
            this.requesterId = requesterId;
            this.itemId = itemId;
            this.count = count;
            this.source = source;
            this.workshopId = workshopId;
            this.status = status;
            this.made = made;
            this.createdTick = createdTick;
            this.updatedTick = updatedTick;
        }

        public UUID id() { return id; }
        public UUID requesterId() { return requesterId; }
        public ResourceLocation itemId() { return itemId; }
        public int count() { return count; }
        public Source source() { return source; }
        @Nullable public UUID workshopId() { return workshopId; }
        public Status status() { return status; }
        public int made() { return made; }
        public long createdTick() { return createdTick; }
        public long updatedTick() { return updatedTick; }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putUUID("Requester", requesterId);
            tag.putString("Item", itemId.toString());
            tag.putInt("Count", count);
            tag.putInt("Source", source.wireId());
            if (workshopId != null) tag.putUUID("Workshop", workshopId);
            tag.putInt("Status", status.wireId());
            tag.putInt("Made", made);
            tag.putLong("Created", createdTick);
            tag.putLong("Updated", updatedTick);
            return tag;
        }

        /** Tolerant reader: a malformed entry is dropped, never guessed at. */
        @Nullable
        static Order load(CompoundTag tag) {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Requester")) return null;
            ResourceLocation item = ResourceLocation.tryParse(tag.getString("Item"));
            Optional<Status> status = Status.fromWireId(tag.getInt("Status"));
            int count = tag.getInt("Count");
            if (item == null || status.isEmpty() || count <= 0 || count > MAX_COUNT) return null;
            UUID workshop = tag.hasUUID("Workshop") ? tag.getUUID("Workshop") : null;
            return new Order(tag.getUUID("Id"), tag.getUUID("Requester"), item,
                count, Source.fromWireId(tag.getInt("Source")), workshop,
                status.get(), Math.max(0, tag.getInt("Made")),
                Math.max(0L, tag.getLong("Created")), Math.max(0L, tag.getLong("Updated")));
        }
    }

    private final List<Order> orders = new ArrayList<>();

    /** Non-terminal orders, oldest first. */
    public List<Order> active() {
        List<Order> out = new ArrayList<>();
        for (Order order : orders) {
            if (!order.status.terminal()) out.add(order);
        }
        return out;
    }

    /** Recently closed orders, oldest first, bounded by {@link #MAX_HISTORY}. */
    public List<Order> history() {
        List<Order> out = new ArrayList<>();
        for (Order order : orders) {
            if (order.status.terminal()) out.add(order);
        }
        return out;
    }

    public List<Order> all() {
        return List.copyOf(orders);
    }

    @Nullable
    public Order find(UUID requesterId, ResourceLocation itemId) {
        for (Order order : orders) {
            if (!order.status.terminal() && order.requesterId.equals(requesterId)
                && order.itemId.equals(itemId)) {
                return order;
            }
        }
        return null;
    }

    @Nullable
    public Order byId(UUID id) {
        for (Order order : orders) {
            if (order.id.equals(id)) return order;
        }
        return null;
    }

    /**
     * Idempotent open keyed by (requester, item): a second need for the same
     * thing returns the live order (DUPLICATE) instead of a second row.
     */
    public OpenDecision open(UUID requesterId, ResourceLocation itemId, int count,
                             Source source, @Nullable UUID workshopId, long tick) {
        if (requesterId == null || itemId == null || source == null || count <= 0) {
            return new OpenDecision(OpenResult.INVALID, null);
        }
        Order existing = find(requesterId, itemId);
        if (existing != null) {
            return new OpenDecision(OpenResult.DUPLICATE, existing);
        }
        if (active().size() >= MAX_ACTIVE) {
            return new OpenDecision(OpenResult.CAPPED, null);
        }
        Order order = new Order(UUID.randomUUID(), requesterId, itemId,
            Math.min(MAX_COUNT, count), source, workshopId,
            workshopId == null ? Status.NEEDS_PLAYER : Status.OPEN, 0, tick, tick);
        orders.add(order);
        return new OpenDecision(OpenResult.CREATED, order);
    }

    /**
     * The one terminal transition. Returns true only the first time; a
     * second call (reload, double scan) is refused and changes nothing.
     */
    public boolean close(Order order, boolean fulfilled, long tick) {
        if (order == null || order.status.terminal() || !orders.contains(order)) {
            return false;
        }
        order.status = fulfilled ? Status.FULFILLED : Status.CANCELLED;
        order.updatedTick = tick;
        prune();
        return true;
    }

    /** Re-points a live order at a (new) workshop, or at the player. */
    public boolean reassign(Order order, @Nullable UUID workshopId, long tick) {
        if (order == null || order.status.terminal() || !orders.contains(order)) return false;
        if (java.util.Objects.equals(order.workshopId, workshopId)) return false;
        order.workshopId = workshopId;
        if (workshopId == null) {
            order.status = Status.NEEDS_PLAYER;
        } else if (order.status == Status.NEEDS_PLAYER) {
            order.status = order.made >= order.count ? Status.CRAFTED : Status.OPEN;
        }
        order.updatedTick = tick;
        return true;
    }

    /**
     * Counts one physically completed batch at a workshop toward the oldest
     * live order there for that item. Returns the order credited, if any.
     */
    @Nullable
    public Order noteMade(UUID workshopId, ResourceLocation itemId, int amount, long tick) {
        if (workshopId == null || itemId == null || amount <= 0) return null;
        for (Order order : orders) {
            if (order.status == Status.OPEN && workshopId.equals(order.workshopId)
                && order.itemId.equals(itemId)) {
                order.made = Math.min(MAX_COUNT * 4, order.made + amount);
                if (order.made >= order.count) {
                    order.status = Status.CRAFTED;
                }
                order.updatedTick = tick;
                return order;
            }
        }
        return null;
    }

    /** The item a workshop should prefer right now: its oldest live order. */
    @Nullable
    public ResourceLocation preferredAt(UUID workshopId) {
        if (workshopId == null) return null;
        for (Order order : orders) {
            if (!order.status.terminal() && order.status != Status.NEEDS_PLAYER
                && workshopId.equals(order.workshopId)) {
                return order.itemId;
            }
        }
        return null;
    }

    private void prune() {
        int terminal = 0;
        for (Order order : orders) {
            if (order.status.terminal()) terminal++;
        }
        for (int i = 0; i < orders.size() && terminal > MAX_HISTORY; ) {
            if (orders.get(i).status.terminal()) {
                orders.remove(i);
                terminal--;
            } else {
                i++;
            }
        }
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Order order : orders) list.add(order.save());
        return list;
    }

    /** Old saves (no tag) load empty; malformed or over-cap entries are dropped. */
    public static CraftingOrderBook load(@Nullable ListTag list) {
        CraftingOrderBook book = new CraftingOrderBook();
        if (list == null) return book;
        int active = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() != Tag.TAG_COMPOUND) continue;
            Order order = Order.load(list.getCompound(i));
            if (order == null) continue;
            if (!order.status.terminal()) {
                if (active >= MAX_ACTIVE
                    || book.find(order.requesterId, order.itemId) != null) continue;
                active++;
            }
            book.orders.add(order);
        }
        book.prune();
        return book;
    }
}

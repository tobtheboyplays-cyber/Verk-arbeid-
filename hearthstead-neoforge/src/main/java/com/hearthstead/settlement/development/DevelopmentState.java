package com.hearthstead.settlement.development;

import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent per-settlement state for {@link Development}. */
public final class DevelopmentState {
    public static final int SCHEMA_VERSION = 5;
    private static final int MAX_QUEST_COUNTER = 1_000_000;
    private static final int MAX_SEEN_REQUESTS = 128;

    private final Set<String> unlocked = new LinkedHashSet<>();
    @Nullable
    private String activeDoctrine;
    private long doctrineChangedAt = Long.MIN_VALUE;
    private int revision;
    private boolean initialized;
    private boolean quarantined;
    /** Preserves the old charter-granted House plan without granting Home. */
    private boolean legacyHouseEntitlement;
    private int lumberLogsStored;
    private int farmCropsStored;
    private int courierDeliveries;
    private int equipmentRequestsServed;
    private int guardExperienceEarned;
    private int productiveGoodsMoved;
    private int allHousedTicks;
    private int guardEquipmentDeliveries;
    private final Map<String, Integer> questBaselines = new LinkedHashMap<>();
    private final Set<UUID> seenEquipmentRequests = new LinkedHashSet<>();
    private final Set<UUID> seenGuardEquipmentRequests = new LinkedHashSet<>();
    /** Same persisted compound as revision/payment authority. */
    private PendingPlayerDeliveryLedger pendingDeliveries =
        new PendingPlayerDeliveryLedger();

    public DevelopmentState() {
        unlocked.add(DevelopmentNode.SETTLEMENT_CHARTER.id());
        // First Fire is founding knowledge, not a second research purchase.
        unlocked.add(DevelopmentNode.SHELTER.id());
    }

    public boolean unlocked(DevelopmentNode node) {
        return node != null && unlocked.contains(node.id());
    }

    public Set<String> unlockedIds() {
        return Set.copyOf(unlocked);
    }

    @Nullable
    public DevelopmentNode activeDoctrine() {
        return DevelopmentNode.byId(activeDoctrine);
    }

    public long doctrineChangedAt() {
        return doctrineChangedAt;
    }

    public int revision() {
        return revision;
    }

    public boolean initialized() {
        return initialized;
    }

    public boolean quarantined() {
        return quarantined;
    }

    public boolean legacyHouseEntitlement() {
        return legacyHouseEntitlement;
    }

    public PendingPlayerDeliveryLedger.ReserveResult reserveDelivery(
            PendingPlayerDeliveryLedger.Reservation reservation) {
        return quarantined ? PendingPlayerDeliveryLedger.ReserveResult.QUARANTINED
            : pendingDeliveries.reserve(reservation);
    }

    public boolean cancelDelivery(UUID deliveryId) {
        return pendingDeliveries.cancel(deliveryId);
    }

    public PendingPlayerDeliveryLedger.DeliveryResult deliverPending(
            ServerLevel level, ServerPlayer player, UUID deliveryId) {
        return quarantined
            ? new PendingPlayerDeliveryLedger.DeliveryResult(
                PendingPlayerDeliveryLedger.Outcome.PENDING, false)
            : pendingDeliveries.deliver(level, player, deliveryId);
    }

    public int retryPending(ServerLevel level, ServerPlayer player) {
        return quarantined ? 0 : pendingDeliveries.retry(level, player);
    }

    public int pendingDeliveryCount() {
        return pendingDeliveries.pendingCount();
    }

    void markInitialized() {
        initialized = true;
    }

    void unlock(DevelopmentNode node) {
        unlocked.add(node.id());
    }

    void chooseDoctrine(DevelopmentNode doctrine, long gameTime) {
        if (!doctrine.doctrine()) {
            throw new IllegalArgumentException("Only doctrine nodes can be active");
        }
        if (activeDoctrine != null && !activeDoctrine.equals(doctrine.id())) {
            throw new IllegalStateException("Settlement doctrine choice is permanent");
        }
        unlocked.add(doctrine.id());
        activeDoctrine = doctrine.id();
        doctrineChangedAt = gameTime;
    }

    void commit() {
        revision++;
    }

    int counter(DevelopmentObjective objective) {
        return switch (objective) {
            case LUMBER_LOGS_STORED -> lumberLogsStored;
            case FARM_CROPS_STORED -> farmCropsStored;
            case COURIER_DELIVERIES -> courierDeliveries;
            case EQUIPMENT_REQUESTS_SERVED -> equipmentRequestsServed;
            case GUARD_XP_EARNED -> guardExperienceEarned;
            case PRODUCTIVE_GOODS_MOVED -> productiveGoodsMoved;
            case ALL_HOUSED_TICKS -> allHousedTicks;
            case GUARD_EQUIPMENT_DELIVERIES -> guardEquipmentDeliveries;
            default -> 0;
        };
    }

    boolean ensureQuestBaseline(DevelopmentNode node,
                                DevelopmentObjective objective) {
        if (!objective.baselineCounter()) {
            return false;
        }
        String key = baselineKey(node, objective);
        if (questBaselines.containsKey(key)) {
            return false;
        }
        questBaselines.put(key, counter(objective));
        return true;
    }

    int counterProgress(DevelopmentNode node, DevelopmentObjective objective) {
        int value = counter(objective);
        if (!objective.baselineCounter()) {
            return value;
        }
        return Math.max(0, value - questBaselines.getOrDefault(
            baselineKey(node, objective), value));
    }

    boolean noteLumberLogs(int amount) {
        int next = addBounded(lumberLogsStored, amount);
        if (next == lumberLogsStored) {
            return false;
        }
        lumberLogsStored = next;
        return true;
    }

    boolean noteFarmCrops(int amount) {
        int next = addBounded(farmCropsStored, amount);
        if (next == farmCropsStored) {
            return false;
        }
        farmCropsStored = next;
        return true;
    }

    boolean noteCourierDelivery(int productiveItems) {
        int nextDeliveries = addBounded(courierDeliveries, 1);
        int nextGoods = addBounded(productiveGoodsMoved, productiveItems);
        boolean changed = nextDeliveries != courierDeliveries
            || nextGoods != productiveGoodsMoved;
        courierDeliveries = nextDeliveries;
        productiveGoodsMoved = nextGoods;
        return changed;
    }

    boolean noteEquipmentRequest(UUID requestId) {
        if (requestId == null || seenEquipmentRequests.contains(requestId)) {
            return false;
        }
        if (seenEquipmentRequests.size() >= MAX_SEEN_REQUESTS) {
            var oldest = seenEquipmentRequests.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        seenEquipmentRequests.add(requestId);
        equipmentRequestsServed = addBounded(equipmentRequestsServed, 1);
        return true;
    }

    /** Counts each strictly validated Guard request at most once. */
    boolean noteGuardEquipmentRequest(UUID requestId) {
        if (requestId == null || seenGuardEquipmentRequests.contains(requestId)) {
            return false;
        }
        if (seenGuardEquipmentRequests.size() >= MAX_SEEN_REQUESTS) {
            var oldest = seenGuardEquipmentRequests.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        seenGuardEquipmentRequests.add(requestId);
        guardEquipmentDeliveries = addBounded(guardEquipmentDeliveries, 1);
        return true;
    }

    boolean noteGuardExperience(int amount) {
        int next = addBounded(guardExperienceEarned, amount);
        if (next == guardExperienceEarned) {
            return false;
        }
        guardExperienceEarned = next;
        return true;
    }

    boolean updateAllHousedTicks(boolean allHoused, int elapsedTicks) {
        int next = allHoused ? addBounded(allHousedTicks, elapsedTicks) : 0;
        if (next == allHousedTicks) {
            return false;
        }
        allHousedTicks = next;
        return true;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", SCHEMA_VERSION);
        ListTag unlockedList = new ListTag();
        for (String id : unlocked) {
            unlockedList.add(StringTag.valueOf(id));
        }
        tag.put("Unlocked", unlockedList);
        if (activeDoctrine != null) {
            tag.putString("ActiveDoctrine", activeDoctrine);
        }
        tag.putLong("DoctrineChangedAt", doctrineChangedAt);
        tag.putInt("Revision", revision);
        tag.putBoolean("Initialized", initialized);
        tag.putBoolean("Quarantined", quarantined);
        tag.putBoolean("LegacyHouseEntitlement", legacyHouseEntitlement);
        CompoundTag counters = new CompoundTag();
        counters.putInt("LumberLogsStored", lumberLogsStored);
        counters.putInt("FarmCropsStored", farmCropsStored);
        counters.putInt("CourierDeliveries", courierDeliveries);
        counters.putInt("EquipmentRequestsServed", equipmentRequestsServed);
        counters.putInt("GuardExperienceEarned", guardExperienceEarned);
        counters.putInt("ProductiveGoodsMoved", productiveGoodsMoved);
        counters.putInt("AllHousedTicks", allHousedTicks);
        counters.putInt("GuardEquipmentDeliveries", guardEquipmentDeliveries);
        tag.put("QuestCounters", counters);
        ListTag baselines = new ListTag();
        for (Map.Entry<String, Integer> entry : questBaselines.entrySet()) {
            CompoundTag baseline = new CompoundTag();
            baseline.putString("Key", entry.getKey());
            baseline.putInt("Value", entry.getValue());
            baselines.add(baseline);
        }
        tag.put("QuestBaselines", baselines);
        ListTag seenRequests = new ListTag();
        for (UUID id : seenEquipmentRequests) {
            CompoundTag seen = new CompoundTag();
            seen.putUUID("Id", id);
            seenRequests.add(seen);
        }
        tag.put("SeenEquipmentRequests", seenRequests);
        ListTag seenGuardRequests = new ListTag();
        for (UUID id : seenGuardEquipmentRequests) {
            CompoundTag seen = new CompoundTag();
            seen.putUUID("Id", id);
            seenGuardRequests.add(seen);
        }
        tag.put("SeenGuardEquipmentRequests", seenGuardRequests);
        tag.put("PendingPlayerDeliveries", pendingDeliveries.writeNbt());
        return tag;
    }

    public static DevelopmentState readNbt(CompoundTag tag) {
        DevelopmentState state = new DevelopmentState();
        int schema = tag.contains("Schema", Tag.TAG_INT) ? tag.getInt("Schema") : 0;
        if (schema < 0 || schema > SCHEMA_VERSION) {
            state.quarantined = true;
            return state;
        }
        ListTag list = tag.getList("Unlocked", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            DevelopmentNode node = DevelopmentNode.byId(id);
            if (node == null) {
                state.quarantined = true;
            } else {
                state.unlocked.add(node.id());
            }
        }
        // Schema 2's First Watch required three unique physical equipment
        // deliveries before it could be owned. That is stronger historical
        // evidence than Arm the Watch's one-Guard quest, but it is not proof
        // that the new node was explicitly claimed. Preserve the objective as
        // a zero-cost AVAILABLE claim instead of silently owning the node;
        // the ordinary purchase path can then author FJ-551 exactly once.
        boolean legacyArmTheWatchClaim = schema < 3
            && state.unlocked.contains(DevelopmentNode.FIRST_WATCH.id())
            && !state.unlocked.contains(DevelopmentNode.ARM_THE_WATCH.id());
        if (schema < 3) {
            if (state.unlocked.contains(DevelopmentNode.ARM_THE_WATCH.id())) {
                state.quarantined = true;
            }
        }
        // Schema 0..3 ordered Farmhouse before Warehouse and granted House
        // directly from the charter. Preserve every legitimately learned plan
        // without relocking a world, then write the corrected schema once.
        if (schema < 4) {
            state.legacyHouseEntitlement = true;
            if (state.unlocked.contains(DevelopmentNode.CULTIVATED_GROUND.id())) {
                state.unlocked.add(DevelopmentNode.STORES_AND_ROADS.id());
            }
            if (state.unlocked.contains(DevelopmentNode.HOSPITALITY.id())) {
                state.unlocked.add(DevelopmentNode.HOME.id());
            }
        } else if (!tag.contains("LegacyHouseEntitlement", Tag.TAG_BYTE)) {
            state.quarantined = true;
        } else {
            state.legacyHouseEntitlement = tag.getBoolean(
                "LegacyHouseEntitlement");
        }
        if (tag.contains("ActiveDoctrine", Tag.TAG_STRING)) {
            String id = tag.getString("ActiveDoctrine");
            DevelopmentNode doctrine = DevelopmentNode.byId(id);
            if (doctrine == null || !doctrine.doctrine()) {
                state.quarantined = true;
            } else {
                state.activeDoctrine = doctrine.id();
                state.unlocked.add(doctrine.id());
            }
        }
        if (state.activeDoctrine != null) {
            for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
                if (node.doctrine() && state.unlocked.contains(node.id())
                    && !state.activeDoctrine.equals(node.id())) {
                    state.quarantined = true;
                }
            }
        }
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (!state.unlocked.contains(node.id())) {
                continue;
            }
            for (String requiredId : node.prerequisites()) {
                if (!state.unlocked.contains(requiredId)) {
                    state.quarantined = true;
                }
            }
        }
        state.doctrineChangedAt = tag.contains("DoctrineChangedAt", Tag.TAG_LONG)
            ? tag.getLong("DoctrineChangedAt") : Long.MIN_VALUE;
        state.revision = Math.max(0, tag.getInt("Revision"));
        state.initialized = tag.getBoolean("Initialized");
        state.quarantined |= tag.getBoolean("Quarantined");
        if (tag.get("QuestCounters") instanceof CompoundTag counters) {
            state.lumberLogsStored = bounded(counters.getInt("LumberLogsStored"));
            state.farmCropsStored = bounded(counters.getInt("FarmCropsStored"));
            state.courierDeliveries = bounded(counters.getInt("CourierDeliveries"));
            state.equipmentRequestsServed = bounded(
                counters.getInt("EquipmentRequestsServed"));
            state.guardExperienceEarned = bounded(
                counters.getInt("GuardExperienceEarned"));
            state.productiveGoodsMoved = bounded(counters.getInt("ProductiveGoodsMoved"));
            state.allHousedTicks = bounded(counters.getInt("AllHousedTicks"));
            if (schema >= 3
                && !counters.contains("GuardEquipmentDeliveries", Tag.TAG_INT)) {
                state.quarantined = true;
            }
            state.guardEquipmentDeliveries = schema >= 3
                ? bounded(counters.getInt("GuardEquipmentDeliveries")) : 0;
        } else if (schema >= 3) {
            state.quarantined = true;
        }
        ListTag baselines = tag.getList("QuestBaselines", Tag.TAG_COMPOUND);
        for (int i = 0; i < baselines.size(); i++) {
            CompoundTag baseline = baselines.getCompound(i);
            String key = baseline.getString("Key");
            if (!validBaselineKey(key) || state.questBaselines.containsKey(key)
                || schema < 3 && key.equals(baselineKey(
                    DevelopmentNode.ARM_THE_WATCH,
                    DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES))) {
                state.quarantined = true;
                continue;
            }
            state.questBaselines.put(key, bounded(baseline.getInt("Value")));
        }
        if (legacyArmTheWatchClaim) {
            state.guardEquipmentDeliveries = Math.max(1,
                state.guardEquipmentDeliveries);
            state.questBaselines.put(baselineKey(
                DevelopmentNode.ARM_THE_WATCH,
                DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES), 0);
        }
        ListTag seenRequests = tag.getList("SeenEquipmentRequests", Tag.TAG_COMPOUND);
        if (seenRequests.size() > MAX_SEEN_REQUESTS) {
            state.quarantined = true;
        }
        for (int i = 0; i < Math.min(MAX_SEEN_REQUESTS, seenRequests.size()); i++) {
            CompoundTag seen = seenRequests.getCompound(i);
            if (!seen.hasUUID("Id")
                || !state.seenEquipmentRequests.add(seen.getUUID("Id"))) {
                state.quarantined = true;
            }
        }
        if (schema < 3 && tag.contains("SeenGuardEquipmentRequests")) {
            state.quarantined = true;
        }
        if (schema >= 3
            && !tag.contains("SeenGuardEquipmentRequests", Tag.TAG_LIST)) {
            state.quarantined = true;
        }
        ListTag seenGuardRequests = tag.getList("SeenGuardEquipmentRequests",
            Tag.TAG_COMPOUND);
        if (seenGuardRequests.size() > MAX_SEEN_REQUESTS) {
            state.quarantined = true;
        }
        for (int i = 0; i < Math.min(MAX_SEEN_REQUESTS,
                seenGuardRequests.size()); i++) {
            CompoundTag seen = seenGuardRequests.getCompound(i);
            if (!seen.hasUUID("Id")
                || !state.seenGuardEquipmentRequests.add(seen.getUUID("Id"))) {
                state.quarantined = true;
            }
        }
        if (schema >= 5) {
            if (tag.get("PendingPlayerDeliveries") instanceof CompoundTag deliveries) {
                state.pendingDeliveries = PendingPlayerDeliveryLedger.readNbt(
                    deliveries);
                state.quarantined |= state.pendingDeliveries.quarantined();
            } else {
                state.quarantined = true;
            }
        }
        return state;
    }

    private static String baselineKey(DevelopmentNode node,
                                      DevelopmentObjective objective) {
        return node.id() + "/" + objective.id();
    }

    private static boolean validBaselineKey(String key) {
        int slash = key == null ? -1 : key.indexOf('/');
        if (slash <= 0 || slash == key.length() - 1) {
            return false;
        }
        DevelopmentNode node = DevelopmentNode.byId(key.substring(0, slash));
        String objectiveId = key.substring(slash + 1);
        if (node == null) {
            return false;
        }
        for (DevelopmentNode.QuestRequirement requirement : node.quests()) {
            if (requirement.objective().id().equals(objectiveId)
                && requirement.objective().baselineCounter()) {
                return true;
            }
        }
        return false;
    }

    private static int addBounded(int current, int amount) {
        if (amount <= 0 || current >= MAX_QUEST_COUNTER) {
            return current;
        }
        return (int) Math.min(MAX_QUEST_COUNTER, (long) current + amount);
    }

    private static int bounded(int value) {
        return Math.max(0, Math.min(MAX_QUEST_COUNTER, value));
    }
}

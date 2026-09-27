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
    public static final int SCHEMA_VERSION = 6;
    private static final int MAX_QUEST_COUNTER = 1_000_000;
    private static final int MAX_SEEN_REQUESTS = 128;
    /**
     * First Watch used to measure housing directly. Existing saves can retain
     * this inert historical row even though the live node now measures a
     * Courier delivery; it must neither quarantine the save nor satisfy the
     * new delivery objective.
     */
    private static final String INACTIVE_FIRST_WATCH_HOUSED_BASELINE =
        DevelopmentNode.FIRST_WATCH.id() + "/"
            + DevelopmentObjective.HOUSED_SETTLERS.id();
    /**
     * Home used to measure farm storage. The current Home objective is the
     * non-counter Foundation Ready fact, so this historical row is retained
     * solely to preserve valid save data and can never credit the new goal.
     */
    private static final String INACTIVE_HOME_FARM_CROPS_BASELINE =
        DevelopmentNode.HOME.id() + "/"
            + DevelopmentObjective.FARM_CROPS_STORED.id();

    private final Set<String> unlocked = new LinkedHashSet<>();
    /** Paid post-raid upgrades by stable id; absent in older saves (empty). */
    private final Set<String> upgrades = new LinkedHashSet<>();
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
    /**
     * v3 tech tree nodes that have no legacy catalogue entry, by id
     * ({@link TechTree}). Optional NBT "TechNodes": older saves own none and
     * unknown ids are kept (a data file may lag behind a save), never
     * quarantined.
     */
    private final Set<String> techNodes = new LinkedHashSet<>();
    /** Paid nodes still being studied: id -> [total, done] in day-time ticks. */
    private final Map<String, long[]> studies = new LinkedHashMap<>();
    /**
     * Grandfather rules (EffectRegistry.grandfatheredBy) already applied to
     * this save. A new settlement starts with every current rule applied; a
     * pre-v3 save starts with none, so knowledge a lane moved onto a new node
     * (the Builder's Hut off Timber Rights, ...) is granted once on load.
     */
    private final Set<String> techGrandfathered = new LinkedHashSet<>(
        com.hearthstead.settlement.techtree.EffectRegistry.get().grandfathers().keySet());
    /**
     * Tech tree Option 2 (26 Sep) split the Trading Post and the Trader off
     * Hospitality onto their own node. A save that learned Hospitality before
     * the split already owned both, so it keeps them: Trading Post is granted
     * once on load (marker in TechGrandfathered). New saves start with the
     * marker, so learning Hospitality never hands out the Trading Post.
     */
    static final String TRADING_POST_SPLIT = "legacy:hospitality_trading_post_split";

    public DevelopmentState() {
        techGrandfathered.add(TRADING_POST_SPLIT);
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

    public boolean hasUpgrade(PostRaidUpgrade upgrade) {
        return upgrade != null && upgrades.contains(upgrade.id());
    }

    public Set<String> upgradeIds() {
        return Set.copyOf(upgrades);
    }

    void unlockUpgrade(PostRaidUpgrade upgrade) {
        upgrades.add(upgrade.id());
    }

    /** A v3-only tech node (no legacy catalogue entry) is learned. */
    public boolean hasTech(String id) {
        return id != null && techNodes.contains(id);
    }

    public Set<String> techIds() {
        return Set.copyOf(techNodes);
    }

    void learnTech(String id) {
        techNodes.add(id);
    }

    /**
     * Grants each v3-only node whose grandfather rule has not run yet when
     * this save already owns one of the old nodes it replaced. Runs on load.
     */
    boolean applyGrandfathers() {
        boolean changed = false;
        var rules = com.hearthstead.settlement.techtree.EffectRegistry.get().grandfathers();
        var data = com.hearthstead.settlement.techtree.TechTreeData.get();
        for (var rule : rules.entrySet()) {
            if (!techGrandfathered.add(rule.getKey())) {
                continue;
            }
            var target = data.node(rule.getKey());
            if (target == null || target.legacy() != null) {
                continue;
            }
            for (String old : rule.getValue()) {
                if (TechTree.learned(this, old)) {
                    techNodes.add(target.id());
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    public boolean studying(String id) {
        return studies.containsKey(id);
    }

    /** [total, done] study ticks, or null when not studying. */
    @Nullable
    public long[] study(String id) {
        long[] s = studies.get(id);
        return s == null ? null : s.clone();
    }

    public Set<String> studyIds() {
        return Set.copyOf(studies.keySet());
    }

    void startStudy(String id, long totalTicks) {
        studies.put(id, new long[] {Math.max(1L, totalTicks), 0L});
    }

    /** Advances every study; returns the ids that finished (and removes them). */
    java.util.List<String> advanceStudies(long ticks) {
        java.util.List<String> done = new java.util.ArrayList<>();
        if (ticks <= 0 || studies.isEmpty()) {
            return done;
        }
        var it = studies.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long[] s = entry.getValue();
            s[1] = Math.min(s[0], s[1] + ticks);
            if (s[1] >= s[0]) {
                done.add(entry.getKey());
                it.remove();
            }
        }
        return done;
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

    /**
     * Learns one paid doctrine while preserving the first doctrine as the
     * settlement's durable primary identity. Later doctrines add knowledge;
     * they never replace or re-date that first choice.
     */
    void learnDoctrine(DevelopmentNode doctrine, long gameTime) {
        if (!doctrine.doctrine()) {
            throw new IllegalArgumentException("Only doctrine nodes can be learned here");
        }
        unlocked.add(doctrine.id());
        if (activeDoctrine == null) {
            activeDoctrine = doctrine.id();
            doctrineChangedAt = gameTime;
        }
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
        ListTag upgradeList = new ListTag();
        for (String id : upgrades) {
            upgradeList.add(StringTag.valueOf(id));
        }
        tag.put("PostRaidUpgrades", upgradeList);
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
        ListTag techList = new ListTag();
        for (String id : techNodes) {
            techList.add(StringTag.valueOf(id));
        }
        tag.put("TechNodes", techList);
        ListTag studyList = new ListTag();
        for (Map.Entry<String, long[]> entry : studies.entrySet()) {
            CompoundTag study = new CompoundTag();
            study.putString("Id", entry.getKey());
            study.putLong("Total", entry.getValue()[0]);
            study.putLong("Done", entry.getValue()[1]);
            studyList.add(study);
        }
        tag.put("TechStudies", studyList);
        ListTag grandfathered = new ListTag();
        for (String id : techGrandfathered) {
            grandfathered.add(StringTag.valueOf(id));
        }
        tag.put("TechGrandfathered", grandfathered);
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
        Set<String> listedUnlocked = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            DevelopmentNode node = DevelopmentNode.byId(id);
            if (node == null) {
                state.quarantined = true;
            } else {
                if (!listedUnlocked.add(node.id())) {
                    state.quarantined = true;
                }
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
                if (schema >= 6 && !listedUnlocked.contains(doctrine.id())) {
                    state.quarantined = true;
                }
                state.unlocked.add(doctrine.id());
            }
        }
        int learnedDoctrines = 0;
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (node.doctrine() && state.unlocked.contains(node.id())) {
                learnedDoctrines++;
            }
        }
        if (learnedDoctrines > 0 && state.activeDoctrine == null) {
            state.quarantined = true;
        }
        // Schema 0..5 was written under the exclusive doctrine contract.
        // Multiple doctrines in such data remain evidence of corruption;
        // schema 6 is the first format that can truthfully author them.
        if (schema < 6 && learnedDoctrines > 1) {
            state.quarantined = true;
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
        // Optional since the Courier Satchel slice: a save without the list
        // owns no upgrades. Unknown, duplicate or unprerequisited ids fail
        // closed exactly like unknown Development nodes.
        ListTag upgradeList = tag.getList("PostRaidUpgrades", Tag.TAG_STRING);
        for (int i = 0; i < upgradeList.size(); i++) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(upgradeList.getString(i));
            if (upgrade == null || !state.upgrades.add(upgrade.id())
                || !state.unlocked.contains(upgrade.requires().id())) {
                state.quarantined = true;
            }
        }
        // An owned upgrade whose prerequisite upgrade is missing (a Hand Cart
        // without its Satchel) fails closed like an orphaned node.
        for (String owned : state.upgrades) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(owned);
            PostRaidUpgrade required = upgrade == null ? null
                : upgrade.requiresUpgrade();
            if (required != null && !state.upgrades.contains(required.id())) {
                state.quarantined = true;
            }
        }
        boolean hasDoctrineTimestamp = tag.contains("DoctrineChangedAt",
            Tag.TAG_LONG);
        state.doctrineChangedAt = hasDoctrineTimestamp
            ? tag.getLong("DoctrineChangedAt") : Long.MIN_VALUE;
        if (schema >= 6 && (!hasDoctrineTimestamp
            || state.activeDoctrine == null
                && state.doctrineChangedAt != Long.MIN_VALUE)) {
            state.quarantined = true;
        }
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
            // Under schema 0..5, learning Shield or Guild stopped the housing
            // sampler permanently. Its saved number therefore says nothing
            // about continuity after that choice: beds may have been lost for
            // any length of time while the counter was frozen. Reset only
            // that stale objective when adopting the cumulative doctrine
            // contract. Schema 6 counters were observed continuously and a
            // learned Hearth doctrine has already consumed this requirement.
            if (schema < 6 && state.activeDoctrine != null
                && !state.activeDoctrine.equals(
                    DevelopmentNode.HEARTH_DOCTRINE.id())
                && !state.unlocked.contains(
                    DevelopmentNode.HEARTH_DOCTRINE.id())) {
                state.allHousedTicks = 0;
            }
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
            // A baseline for a gate a later tech tree dropped (Option 2:
            // Fields and Fishery lost their Courier-delivery gate) is kept as
            // an inert row: it never credits anything, and it must not turn
            // earned research into a quarantined "records need repair" save.
            if (!(validBaselineKey(key) || retiredBaselineKey(key))
                || state.questBaselines.containsKey(key)
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
        // v3 tech tree (optional, additive): ids go through the migration
        // table; unknown ids are kept so a save outlives a data change.
        ListTag techList = tag.getList("TechNodes", Tag.TAG_STRING);
        for (int i = 0; i < techList.size(); i++) {
            String id = com.hearthstead.settlement.techtree.TechIdMigration
                .canonical(techList.getString(i));
            if (id != null && !id.isEmpty()) {
                state.techNodes.add(id);
            }
        }
        ListTag studyList = tag.getList("TechStudies", Tag.TAG_COMPOUND);
        for (int i = 0; i < studyList.size(); i++) {
            CompoundTag study = studyList.getCompound(i);
            String id = com.hearthstead.settlement.techtree.TechIdMigration
                .canonical(study.getString("Id"));
            long total = Math.max(1L, study.getLong("Total"));
            if (id != null && !id.isEmpty()) {
                state.studies.put(id, new long[] {total,
                    Math.max(0L, Math.min(total, study.getLong("Done")))});
            }
        }
        state.techGrandfathered.clear();
        ListTag grandfathered = tag.getList("TechGrandfathered", Tag.TAG_STRING);
        for (int i = 0; i < grandfathered.size(); i++) {
            state.techGrandfathered.add(grandfathered.getString(i));
        }
        if (!state.quarantined) {
            state.applyGrandfathers();
            state.applyTradingPostSplit();
        }
        return state;
    }

    /** Once per save: Hospitality learned before the Option 2 split keeps its Trading Post. */
    boolean applyTradingPostSplit() {
        if (!techGrandfathered.add(TRADING_POST_SPLIT)) {
            return false;
        }
        if (unlocked.contains(DevelopmentNode.HOSPITALITY.id())
            && !unlocked.contains(DevelopmentNode.TRADING_POST.id())) {
            unlocked.add(DevelopmentNode.TRADING_POST.id());
            return true;
        }
        return false;
    }

    private static String baselineKey(DevelopmentNode node,
                                      DevelopmentObjective objective) {
        return node.id() + "/" + objective.id();
    }

    /**
     * A well-formed baseline row for a known node and a known counter
     * objective that the node no longer requires (its gate was retired).
     * Unknown nodes, unknown or non-counter objectives and malformed keys are
     * still invalid, and duplicates still quarantine.
     */
    static boolean retiredBaselineKey(String key) {
        int slash = key == null ? -1 : key.indexOf('/');
        if (slash <= 0 || slash == key.length() - 1) {
            return false;
        }
        DevelopmentNode node = DevelopmentNode.byId(key.substring(0, slash));
        String objectiveId = key.substring(slash + 1);
        if (node == null) {
            return false;
        }
        for (DevelopmentObjective objective : DevelopmentObjective.values()) {
            if (objective.id().equals(objectiveId)) {
                return objective.baselineCounter();
            }
        }
        return false;
    }

    private static boolean validBaselineKey(String key) {
        if (INACTIVE_FIRST_WATCH_HOUSED_BASELINE.equals(key)
            || INACTIVE_HOME_FARM_CROPS_BASELINE.equals(key)) {
            return true;
        }
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

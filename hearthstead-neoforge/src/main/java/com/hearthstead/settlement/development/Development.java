package com.hearthstead.settlement.development;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.journey.JourneyTransactionIds;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative Development tree and Mayor emblem economy. */
public final class Development extends SavedData {
    private static final String DATA_NAME = "hearthstead_development";

    public enum Result {
        APPLIED("hearthstead.development.result.applied"),
        STALE("hearthstead.development.blocked.stale"),
        QUARANTINED("hearthstead.development.blocked.quarantined"),
        INVALID("hearthstead.development.blocked.invalid"),
        ALREADY_UNLOCKED("hearthstead.development.blocked.done"),
        FUTURE("hearthstead.development.blocked.future"),
        PREREQUISITE("hearthstead.development.blocked.prerequisite"),
        QUEST_REQUIRED("hearthstead.development.blocked.quest"),
        FIRST_RAID_REQUIRED("hearthstead.development.blocked.first_raid"),
        DOCTRINE_EXCLUSIVE("hearthstead.development.blocked.exclusive"),
        MATERIALS("hearthstead.development.blocked.materials"),
        MAYOR_REQUIRED("hearthstead.development.blocked.mayor"),
        MAYOR_UNAVAILABLE("hearthstead.development.blocked.mayor_unavailable"),
        EMBLEM_LOCKED("hearthstead.development.blocked.emblem_locked"),
        EMBLEM_PARKED("hearthstead.development.blocked.emblem_parked"),
        DELIVERY_BACKLOG("hearthstead.development.blocked.delivery_backlog");

        private final String translationKey;

        Result(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    public enum NodeStatus {
        ACTIVE(0),
        OWNED(1),
        DORMANT(2),
        AVAILABLE(3),
        LOCKED(4),
        FUTURE(5),
        QUARANTINED(6);

        private final int wireId;

        NodeStatus(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static NodeStatus fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> ACTIVE;
                case 1 -> OWNED;
                case 2 -> DORMANT;
                case 3 -> AVAILABLE;
                case 5 -> FUTURE;
                case 6 -> QUARANTINED;
                default -> LOCKED;
            };
        }
    }

    public record Assessment(Result result, NodeStatus status) {
        public boolean allowed() {
            return result == Result.APPLIED;
        }
    }

    public record EmblemPurchase(Result result, ItemStack emblem,
                                 @Nullable UUID deliveryId) {
        public EmblemPurchase(Result result, ItemStack emblem) {
            this(result, emblem, null);
        }

        public EmblemPurchase {
            emblem = emblem == null ? ItemStack.EMPTY : emblem;
        }

        public boolean applied() {
            return result == Result.APPLIED && !emblem.isEmpty();
        }
    }

    private final Map<UUID, DevelopmentState> settlements = new HashMap<>();

    private static final Factory<Development> FACTORY =
        new Factory<>(Development::new, Development::load, null);

    public static Development get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static Development load(CompoundTag tag, HolderLookup.Provider registries) {
        Development data = new Development();
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("Id") && entry.contains("State", Tag.TAG_COMPOUND)) {
                data.settlements.put(entry.getUUID("Id"),
                    DevelopmentState.readNbt(entry.getCompound("State")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, DevelopmentState> entry : settlements.entrySet()) {
            CompoundTag out = new CompoundTag();
            out.putUUID("Id", entry.getKey());
            out.put("State", entry.getValue().writeNbt());
            list.add(out);
        }
        tag.put("Settlements", list);
        return tag;
    }

    public static DevelopmentState of(ServerLevel level, Settlement settlement) {
        Development data = get(level);
        DevelopmentState state = data.settlements.computeIfAbsent(settlement.id,
            ignored -> new DevelopmentState());
        if (!state.initialized() && !state.quarantined()) {
            grandfatherExistingBuildings(state, settlement);
            state.markInitialized();
            data.setDirty();
        }
        if (!state.quarantined()
            && DevelopmentQuests.ensureEligibleBaselines(state)) {
            data.setDirty();
        }
        return state;
    }

    /** Pure read for UI/tests after the state has already been initialized. */
    @Nullable
    public static DevelopmentState existing(ServerLevel level, UUID settlementId) {
        return get(level).settlements.get(settlementId);
    }

    /** Completes one already-committed paid delivery without minting a copy. */
    public static PendingPlayerDeliveryLedger.DeliveryResult deliverPending(
            ServerLevel level, Settlement settlement, ServerPlayer player,
            UUID deliveryId) {
        if (level == null || settlement == null || player == null
            || deliveryId == null) {
            return new PendingPlayerDeliveryLedger.DeliveryResult(
                PendingPlayerDeliveryLedger.Outcome.PENDING, false);
        }
        Development data = get(level);
        DevelopmentState state = data.settlements.get(settlement.id);
        if (state == null) {
            return new PendingPlayerDeliveryLedger.DeliveryResult(
                PendingPlayerDeliveryLedger.Outcome.PENDING, false);
        }
        PendingPlayerDeliveryLedger.DeliveryResult result =
            state.deliverPending(level, player, deliveryId);
        if (result.changed()) {
            data.setDirty();
        }
        return result;
    }

    /** Bounded login/player-tick retry over all Development settlements. */
    public static int retryPending(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) {
            return 0;
        }
        Development data = get(level);
        int changed = 0;
        for (DevelopmentState state : data.settlements.values()) {
            changed += state.retryPending(level, player);
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    @Nullable
    DevelopmentState existingState(UUID settlementId) {
        return settlements.get(settlementId);
    }

    public static boolean isBuildingUnlocked(ServerLevel level, Settlement settlement,
                                             BuildingType type) {
        DevelopmentState state = of(level, settlement);
        if (state.quarantined()) {
            return false;
        }
        if (type == BuildingType.HOUSE && state.legacyHouseEntitlement()) {
            return true;
        }
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (state.unlocked(node) && node.buildings().contains(type)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEmblemUnlocked(ServerLevel level, Settlement settlement,
                                           Profession profession) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        DevelopmentState state = of(level, settlement);
        return entry != null && !state.quarantined()
            && state.unlocked(entry.unlock());
    }

    public static int revisionOf(ServerLevel level, Settlement settlement) {
        return of(level, settlement).revision();
    }

    public static Assessment assessNode(ServerLevel level, Settlement settlement,
                                        HearthBlockEntity hearth, DevelopmentNode node) {
        DevelopmentState state = of(level, settlement);
        if (state.quarantined()) {
            return new Assessment(Result.QUARANTINED, NodeStatus.QUARANTINED);
        }
        if (node == null) {
            return new Assessment(Result.INVALID, NodeStatus.LOCKED);
        }
        DevelopmentNode activeDoctrine = state.activeDoctrine();
        if (node.doctrine() && activeDoctrine == node) {
            return new Assessment(Result.ALREADY_UNLOCKED, NodeStatus.ACTIVE);
        }
        // The post-raid split is a real strategic choice, not a three-day
        // shopping delay before the settlement owns every branch. Once one
        // doctrine is chosen, the other two remain visible with an explicit
        // exclusion reason and can never be charged accidentally.
        if (node.doctrine() && activeDoctrine != null) {
            return new Assessment(Result.DOCTRINE_EXCLUSIVE,
                state.unlocked(node) ? NodeStatus.DORMANT : NodeStatus.LOCKED);
        }
        if (!node.doctrine() && state.unlocked(node)) {
            return new Assessment(Result.ALREADY_UNLOCKED, NodeStatus.OWNED);
        }
        if (!node.implemented()) {
            return new Assessment(Result.FUTURE, NodeStatus.FUTURE);
        }
        for (String prerequisite : node.prerequisites()) {
            DevelopmentNode required = DevelopmentNode.byId(prerequisite);
            if (required == null || !state.unlocked(required)) {
                return new Assessment(Result.PREREQUISITE, NodeStatus.LOCKED);
            }
        }
        if (node == DevelopmentNode.FIRST_RAID_AFTERMATH
            && settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED) {
            return new Assessment(Result.FIRST_RAID_REQUIRED, NodeStatus.LOCKED);
        }
        if (!DevelopmentQuests.complete(level, settlement, hearth, state, node)) {
            return new Assessment(Result.QUEST_REQUIRED, NodeStatus.LOCKED);
        }
        if (hearth == null || !canPay(hearth.getInventory(), node.costs())) {
            return new Assessment(Result.MATERIALS, NodeStatus.LOCKED);
        }
        return new Assessment(Result.APPLIED,
            state.unlocked(node) ? NodeStatus.DORMANT : NodeStatus.AVAILABLE);
    }

    public static Result purchaseNode(ServerLevel level, Settlement settlement,
                                      HearthBlockEntity hearth, DevelopmentNode node,
                                      int expectedRevision) {
        return purchaseNode(level, settlement, hearth, node, expectedRevision,
            null);
    }

    public static Result purchaseNode(ServerLevel level, Settlement settlement,
                                      HearthBlockEntity hearth, DevelopmentNode node,
                                      int expectedRevision,
                                      @Nullable ServerPlayer actor) {
        DevelopmentState state = of(level, settlement);
        int beforeRevision = state.revision();
        String target = "development:" + (node == null ? "invalid" : node.id());
        if (state.revision() != expectedRevision) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target,
                    beforeRevision, beforeRevision, 0, 0, "stale_revision"));
            return Result.STALE;
        }
        Assessment assessment = assessNode(level, settlement, hearth, node);
        if (!assessment.allowed()) {
            if (assessment.result() == Result.ALREADY_UNLOCKED
                && node == DevelopmentNode.ARM_THE_WATCH && actor != null) {
                JourneyServerHooks.reconcileOwnedTechUnlock(actor, settlement);
            }
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target,
                    beforeRevision, beforeRevision, 0, 0,
                    assessment.result().name().toLowerCase(java.util.Locale.ROOT)));
            return assessment.result();
        }
        int materialBefore = node.costs().stream()
            .mapToInt(cost -> count(hearth.getInventory(), cost)).sum();
        int expectedConsumed = node.costs().stream()
            .mapToInt(DevelopmentNode.Cost::count).sum();
        pay(hearth.getInventory(), node.costs());
        if (node.doctrine()) {
            state.chooseDoctrine(node, level.getGameTime());
        } else {
            state.unlock(node);
        }
        state.commit();
        DevelopmentQuests.ensureEligibleBaselines(state);
        get(level).setDirty();
        int materialAfter = node.costs().stream()
            .mapToInt(cost -> count(hearth.getInventory(), cost)).sum();
        AuthorityTelemetry.Event event = node.doctrine()
            ? AuthorityTelemetry.Event.DOCTRINE_COMMITTED
            : AuthorityTelemetry.Event.DEVELOPMENT_NODE_COMMITTED;
        AuthorityTelemetry.emit(level, event,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id, target,
                beforeRevision, state.revision(), 0, 1, "mixed_material_cost",
                materialBefore, materialAfter, -expectedConsumed,
                node.doctrine() ? "permanent_branch_choice" : "node_unlock"));
        if (!node.buildings().isEmpty()) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.PLAN_UNLOCK_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement.id,
                    "plans:" + node.id(), beforeRevision, state.revision(),
                    0, node.buildings().size(), "settlement_knowledge"));
        }
        if (actor != null) {
            JourneyServerHooks.noteTechUnlocked(actor, settlement, node,
                state.revision());
        }
        return Result.APPLIED;
    }

    public static Result assessEmblem(ServerLevel level, Settlement settlement,
                                      HearthBlockEntity hearth, Profession profession) {
        DevelopmentState state = of(level, settlement);
        if (state.quarantined()) {
            return Result.QUARANTINED;
        }
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        if (entry == null) {
            return Result.EMBLEM_PARKED;
        }
        if (settlement.mayorId == null) {
            return Result.MAYOR_REQUIRED;
        }
        if (!(level.getEntity(settlement.mayorId) instanceof SettlerEntity mayor)
            || !mayor.isAlive()
            || !settlement.id.equals(mayor.getSettlementId())
            || settlement.record(mayor.getUUID()) == null) {
            return Result.MAYOR_UNAVAILABLE;
        }
        if (!state.unlocked(entry.unlock())) {
            return Result.EMBLEM_LOCKED;
        }
        if (hearth == null || !canPay(hearth.getInventory(), entry.costs())) {
            return Result.MATERIALS;
        }
        return Result.APPLIED;
    }

    /**
     * Reserves and pays for exactly one physical emblem. The caller must use
     * the returned stack's safe hand/inventory/drop delivery ladder. A stale
     * replay cannot pay twice because every accepted sale advances revision.
     */
    public static EmblemPurchase purchaseEmblem(ServerLevel level, Settlement settlement,
                                                HearthBlockEntity hearth,
                                                Profession profession,
                                                int expectedRevision) {
        return purchaseEmblem(level, settlement, hearth, profession,
            expectedRevision, null);
    }

    public static EmblemPurchase purchaseEmblem(ServerLevel level, Settlement settlement,
                                                HearthBlockEntity hearth,
                                                Profession profession,
                                                int expectedRevision,
                                                @Nullable ServerPlayer actor) {
        DevelopmentState state = of(level, settlement);
        int beforeRevision = state.revision();
        String target = "emblem:" + (profession == null
            ? "invalid" : profession.id());
        if (state.revision() != expectedRevision) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target,
                    beforeRevision, beforeRevision, 0, 0, "stale_revision"));
            return new EmblemPurchase(Result.STALE, ItemStack.EMPTY);
        }
        Result assessment = assessEmblem(level, settlement, hearth, profession);
        if (assessment != Result.APPLIED) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target,
                    beforeRevision, beforeRevision, 0, 0,
                    assessment.name().toLowerCase(java.util.Locale.ROOT)));
            return new EmblemPurchase(assessment, ItemStack.EMPTY);
        }
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        ItemStack emblem = JobEmblemItem.stackFor(profession);
        if (entry == null || emblem.isEmpty()) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target,
                    beforeRevision, beforeRevision, 0, 0, "emblem_parked"));
            return new EmblemPurchase(Result.EMBLEM_PARKED, ItemStack.EMPTY);
        }
        UUID transaction = null;
        boolean reserved = false;
        if (actor != null) {
            transaction = JourneyTransactionIds.forRevision("emblem_trade",
                settlement.id, actor.getUUID(), beforeRevision + 1L);
            JourneyEmblemProvenance.stamp(emblem, settlement.id, transaction,
                profession);
            PendingPlayerDeliveryLedger.Reservation reservation =
                PendingPlayerDeliveryLedger.reservation(level, transaction,
                    actor, emblem);
            if (reservation == null
                || !state.reserveDelivery(reservation).accepted()) {
                AuthorityTelemetry.emit(level,
                    AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                    AuthorityTelemetry.Result.REJECTED,
                    AuthorityTelemetry.Fields.state(settlement.id, target,
                        beforeRevision, beforeRevision, 0, 0,
                        "delivery_backlog"));
                return new EmblemPurchase(Result.DELIVERY_BACKLOG,
                    ItemStack.EMPTY);
            }
            reserved = true;
        }
        try {
            pay(hearth.getInventory(), entry.costs());
            state.commit();
        } catch (RuntimeException failure) {
            if (reserved) {
                state.cancelDelivery(transaction);
            }
            throw failure;
        }
        get(level).setDirty();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EMBLEM_PURCHASED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id, target,
                beforeRevision, state.revision(), 0, 1,
                BuiltInRegistries.ITEM.getKey(emblem.getItem()).toString(),
                0, emblem.getCount(), emblem.getCount(),
                "hearth_payment_and_issue"));
        if (actor != null) {
            JourneyServerHooks.noteEmblemPurchased(actor, settlement, profession,
                transaction);
        }
        return new EmblemPurchase(Result.APPLIED, emblem, transaction);
    }

    private static void grandfatherExistingBuildings(DevelopmentState state,
                                                     Settlement settlement) {
        for (Building building : settlement.buildings) {
            for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
                if (node.buildings().contains(building.type)) {
                    unlockWithPrerequisites(state, node);
                }
            }
        }
    }

    private static boolean unlockWithPrerequisites(DevelopmentState state,
                                                   DevelopmentNode node) {
        for (String requiredId : node.prerequisites()) {
            DevelopmentNode required = DevelopmentNode.byId(requiredId);
            if (required == null || !unlockWithPrerequisites(state, required)) {
                return false;
            }
        }
        if (node.doctrine() && state.activeDoctrine() == null) {
            // Existing saves may already contain a post-raid building. Keep
            // that real world intact by adopting the first matching branch;
            // fresh saves can only reach this through purchaseNode.
            state.chooseDoctrine(node, Long.MIN_VALUE);
        } else if (node.doctrine() && state.activeDoctrine() != node) {
            // A legacy settlement can physically contain buildings from more
            // than one future doctrine. Preserve those blocks, but never turn
            // them into contradictory plan/emblem knowledge.
            return false;
        } else {
            state.unlock(node);
        }
        return true;
    }

    private static boolean canPay(ItemStackHandler inventory,
                                  List<DevelopmentNode.Cost> costs) {
        if (inventory == null) {
            return costs.isEmpty();
        }
        for (DevelopmentNode.Cost cost : costs) {
            if (count(inventory, cost) < cost.count()) {
                return false;
            }
        }
        return true;
    }

    private static int count(ItemStackHandler inventory, DevelopmentNode.Cost cost) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (cost.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void pay(ItemStackHandler inventory,
                            List<DevelopmentNode.Cost> costs) {
        for (DevelopmentNode.Cost cost : costs) {
            int left = cost.count();
            for (int slot = 0; slot < inventory.getSlots() && left > 0; slot++) {
                if (!cost.matches(inventory.getStackInSlot(slot))) {
                    continue;
                }
                left -= inventory.extractItem(slot, left, false).getCount();
            }
            if (left != 0) {
                throw new IllegalStateException("Development payment changed after validation");
            }
        }
    }
}

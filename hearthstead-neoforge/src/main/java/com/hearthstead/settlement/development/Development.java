package com.hearthstead.settlement.development;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.CoinTreasury;
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

/** Server-authoritative Development tree and Guildmaster emblem economy. */
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
        // Stable legacy enum position; cumulative doctrine code never emits it.
        DOCTRINE_EXCLUSIVE("hearthstead.development.blocked.exclusive"),
        MATERIALS("hearthstead.development.blocked.materials"),
        MAYOR_REQUIRED("hearthstead.development.blocked.mayor"),
        MAYOR_UNAVAILABLE("hearthstead.development.blocked.mayor_unavailable"),
        EMBLEM_LOCKED("hearthstead.development.blocked.emblem_locked"),
        EMBLEM_PARKED("hearthstead.development.blocked.emblem_parked"),
        DELIVERY_BACKLOG("hearthstead.development.blocked.delivery_backlog"),
        READ_ONLY("hearthstead.development.blocked.read_only");

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
        boolean migrated = false;
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("Id") && entry.contains("State", Tag.TAG_COMPOUND)) {
                CompoundTag stateTag = entry.getCompound("State");
                data.settlements.put(entry.getUUID("Id"),
                    DevelopmentState.readNbt(stateTag));
                migrated |= !stateTag.contains("Schema", Tag.TAG_INT)
                    || stateTag.getInt("Schema") < DevelopmentState.SCHEMA_VERSION;
            }
        }
        if (migrated) {
            // Persist migrations even if this settlement remains idle after load.
            data.setDirty();
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

    /**
     * v3 tech tree: true once the node {@code id} (any of the 84 in
     * data/hearthstead/techtree) is learned for this settlement. Side-effect
     * free, safe from AI ticks. See {@link TechTree#has}.
     */
    public static boolean has(ServerLevel level, Settlement settlement, String id) {
        return TechTree.has(level, settlement, id);
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
        // v3: once a tech node claims this plan (EffectRegistry), only the
        // claimants unlock it; legacy node lists and RoleUnlocks step aside.
        // With [techtree] enabled=false the old screen cannot learn v3 nodes,
        // so claims step aside and the legacy lists decide again.
        List<String> claimants = com.hearthstead.settlement.techtree.TechTreeConfig.enabled()
            ? com.hearthstead.settlement.techtree.EffectRegistry.get().buildingClaimants(type)
            : List.of();
        if (!claimants.isEmpty()) {
            for (String id : claimants) {
                if (TechTree.learned(state, id)) {
                    return true;
                }
            }
            return false;
        }
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (state.unlocked(node) && node.buildings().contains(type)) {
                return true;
            }
        }
        // BATTLE-ROLES: the four role halls ride on today's nodes until the
        // new tech tree lands (plan/BATTLE-ROLES.md section 5).
        DevelopmentNode roleNode =
            com.hearthstead.entity.combat.role.RoleUnlocks.buildingNode(type);
        return roleNode != null && state.unlocked(roleNode);
    }

    public static boolean isEmblemUnlocked(ServerLevel level, Settlement settlement,
                                           Profession profession) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        DevelopmentState state = of(level, settlement);
        return entry != null && !state.quarantined()
            && emblemKnowledge(state, entry);
    }

    /**
     * The emblem's knowledge gate: the tech nodes that claim the profession
     * (EffectRegistry) when any do, otherwise the catalogue's legacy node.
     */
    static boolean emblemKnowledge(DevelopmentState state, JobEmblemCatalog.Entry entry) {
        List<String> claimants = com.hearthstead.settlement.techtree.TechTreeConfig.enabled()
            ? com.hearthstead.settlement.techtree.EffectRegistry.get().professionClaimants(entry.profession())
            : List.of();
        if (claimants.isEmpty()) {
            return state.unlocked(entry.unlock());
        }
        for (String id : claimants) {
            if (TechTree.learned(state, id)) {
                return true;
            }
        }
        return false;
    }

    public static int revisionOf(ServerLevel level, Settlement settlement) {
        return of(level, settlement).revision();
    }

    public static Assessment assessNode(ServerLevel level, Settlement settlement,
                                        HearthBlockEntity hearth, DevelopmentNode node) {
        return assessNode(level, settlement, hearth, node, null);
    }

    public static Assessment assessNode(ServerLevel level, Settlement settlement, HearthBlockEntity hearth,
            DevelopmentNode node, @Nullable ServerPlayer actor) {
        DevelopmentState state = of(level, settlement);
        if (state.quarantined()) {
            return new Assessment(Result.QUARANTINED, NodeStatus.QUARANTINED);
        }
        if (node == null) {
            return new Assessment(Result.INVALID, NodeStatus.LOCKED);
        }
        DevelopmentNode activeDoctrine = state.activeDoctrine();
        if (state.unlocked(node)) {
            NodeStatus learnedStatus = node.doctrine()
                ? activeDoctrine == node ? NodeStatus.ACTIVE : NodeStatus.DORMANT
                : NodeStatus.OWNED;
            return new Assessment(Result.ALREADY_UNLOCKED, learnedStatus);
        }
        if (!node.implemented()) {
            return new Assessment(Result.FUTURE, NodeStatus.FUTURE);
        }
        // v3 pick-one: the old screen cannot buy past a choice either.
        if (TechTree.excludedBy(state, node.id()) != null) {
            return new Assessment(Result.DOCTRINE_EXCLUSIVE, NodeStatus.LOCKED);
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
        if (hearth == null || !canPay(CoinTreasury.open(level, settlement, hearth, actor), node.costs())) {
            return new Assessment(Result.MATERIALS, NodeStatus.LOCKED);
        }
        return new Assessment(Result.APPLIED, NodeStatus.AVAILABLE);
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
        Assessment assessment = assessNode(level, settlement, hearth, node, actor);
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
        boolean establishesPrimaryDoctrine = node.doctrine()
            && state.activeDoctrine() == null;
        ItemStackHandler treasury = CoinTreasury.open(level, settlement, hearth, actor);
        int materialBefore = node.costs().stream()
            .mapToInt(cost -> count(treasury, cost)).sum();
        int expectedConsumed = node.costs().stream()
            .mapToInt(DevelopmentNode.Cost::count).sum();
        pay(treasury, node.costs());
        if (node.doctrine()) {
            state.learnDoctrine(node, level.getGameTime());
        } else {
            state.unlock(node);
        }
        state.commit();
        DevelopmentQuests.ensureEligibleBaselines(state);
        get(level).setDirty();
        int materialAfter = node.costs().stream()
            .mapToInt(cost -> count(treasury, cost)).sum();
        AuthorityTelemetry.Event event = node.doctrine()
            ? AuthorityTelemetry.Event.DOCTRINE_COMMITTED
            : AuthorityTelemetry.Event.DEVELOPMENT_NODE_COMMITTED;
        AuthorityTelemetry.emit(level, event,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id, target,
                beforeRevision, state.revision(), 0, 1, "mixed_material_cost",
                materialBefore, materialAfter, -expectedConsumed,
                node.doctrine()
                    ? establishesPrimaryDoctrine
                        ? "primary_doctrine_learned"
                        : "additional_doctrine_learned"
                    : "node_unlock"));
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
        com.hearthstead.fx.FxHooks.techLearned(level, settlement);
        com.hearthstead.settlement.TownChat.send(level, settlement,
            com.hearthstead.settlement.TownChat.Kind.RESEARCH,
            net.minecraft.network.chat.Component.translatableWithFallback("hearthstead.techtree.announce.learned",
                "New research: %s is learned.", node.displayName()));
        return Result.APPLIED;
    }

    /**
     * True when this settlement has paid for {@code upgrade}; never on
     * quarantine. Deliberately side-effect free: upgrade effects are read
     * from AI ticks, so this never creates, grandfathers or baselines a
     * settlement's Development state the way {@link #of} does. A settlement
     * without stored state has bought nothing.
     */
    public static boolean hasUpgrade(ServerLevel level, Settlement settlement,
                                     PostRaidUpgrade upgrade) {
        if (level == null || settlement == null || upgrade == null) {
            return false;
        }
        DevelopmentState state = get(level).existingState(settlement.id);
        return state != null && !state.quarantined() && state.hasUpgrade(upgrade);
    }

    /**
     * Side-effect-free node ownership read for AI-tick effects (Shield
     * Doctrine's Guard XP bonus). Never creates or grandfathers state.
     */
    public static boolean hasNode(ServerLevel level, Settlement settlement,
                                  DevelopmentNode node) {
        if (level == null || settlement == null || node == null) {
            return false;
        }
        DevelopmentState state = get(level).existingState(settlement.id);
        return state != null && !state.quarantined() && state.unlocked(node);
    }

    /**
     * Read-only gate for one post-raid upgrade. The upgrade stays locked
     * until its Development node (First Raid Aftermath) is learned, and is
     * bought at most once per settlement.
     */
    public static Result assessUpgrade(ServerLevel level, Settlement settlement,
                                       HearthBlockEntity hearth,
                                       PostRaidUpgrade upgrade,
                                       @Nullable ServerPlayer actor) {
        if (actor != null && actor.isSpectator()) {
            return Result.READ_ONLY;
        }
        DevelopmentState state = of(level, settlement);
        if (state.quarantined()) {
            return Result.QUARANTINED;
        }
        if (upgrade == null) {
            return Result.INVALID;
        }
        if (state.hasUpgrade(upgrade)) {
            return Result.ALREADY_UNLOCKED;
        }
        // v3 pick-one (Longbows vs Crossbows): refused on the old path too.
        if (TechTree.excludedBy(state, upgrade.id()) != null) {
            return Result.DOCTRINE_EXCLUSIVE;
        }
        if (!state.unlocked(upgrade.requires())) {
            return upgrade.requires() == DevelopmentNode.FIRST_RAID_AFTERMATH
                ? Result.FIRST_RAID_REQUIRED : Result.PREREQUISITE;
        }
        // Upgrade chains (Hand Cart requires the Courier Satchel).
        PostRaidUpgrade requiredUpgrade = upgrade.requiresUpgrade();
        if (requiredUpgrade != null && !state.hasUpgrade(requiredUpgrade)) {
            return Result.PREREQUISITE;
        }
        // Milestone gate (lifetime logs stored, deliveries, housed settlers...).
        if (upgrade.gateObjective() != null
            && DevelopmentQuests.upgradeGateProgress(level, settlement, state,
                upgrade.gateObjective()) < upgrade.gateTarget()) {
            return Result.QUEST_REQUIRED;
        }
        if (hearth == null || !canPay(CoinTreasury.open(level, settlement, hearth, actor),
                upgrade.costs())) {
            return Result.MATERIALS;
        }
        return Result.APPLIED;
    }

    /**
     * Pays for and records one post-raid upgrade through the same physical
     * Coins treasury and revision fence as {@link #purchaseNode}. A stale or
     * repeated request pays nothing and authors no revision.
     */
    public static Result purchaseUpgrade(ServerLevel level, Settlement settlement,
                                         HearthBlockEntity hearth,
                                         PostRaidUpgrade upgrade,
                                         int expectedRevision,
                                         @Nullable ServerPlayer actor) {
        DevelopmentState state = of(level, settlement);
        int beforeRevision = state.revision();
        String target = "development_upgrade:"
            + (upgrade == null ? "invalid" : upgrade.id());
        Result result = state.revision() != expectedRevision ? Result.STALE
            : assessUpgrade(level, settlement, hearth, upgrade, actor);
        if (result != Result.APPLIED) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target,
                    beforeRevision, beforeRevision, 0, 0,
                    result == Result.STALE ? "stale_revision"
                        : result.name().toLowerCase(java.util.Locale.ROOT)));
            return result;
        }
        ItemStackHandler treasury = CoinTreasury.open(level, settlement, hearth, actor);
        int materialBefore = upgrade.costs().stream()
            .mapToInt(cost -> count(treasury, cost)).sum();
        int expectedConsumed = upgrade.costs().stream()
            .mapToInt(DevelopmentNode.Cost::count).sum();
        pay(treasury, upgrade.costs());
        state.unlockUpgrade(upgrade);
        state.commit();
        get(level).setDirty();
        int materialAfter = upgrade.costs().stream()
            .mapToInt(cost -> count(treasury, cost)).sum();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.DEVELOPMENT_NODE_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id, target,
                beforeRevision, state.revision(), 0, 1,
                upgrade.materialCosts().isEmpty() ? "coin_cost" : "mixed_material_cost",
                materialBefore, materialAfter, -expectedConsumed,
                "post_raid_upgrade"));
        return Result.APPLIED;
    }

    public static Result assessEmblem(ServerLevel level, Settlement settlement,
                                      HearthBlockEntity hearth, Profession profession) {
        return assessEmblem(level, settlement, hearth, profession, null);
    }

    public static Result assessEmblem(ServerLevel level, Settlement settlement, HearthBlockEntity hearth,
            Profession profession, @Nullable ServerPlayer actor) {
        // Recheck live mode: a previously buyable shop snapshot grants no authority.
        if (actor != null && actor.isSpectator()) {
            return Result.READ_ONLY;
        }
        DevelopmentState state = of(level, settlement);
        if (state.quarantined()) {
            return Result.QUARANTINED;
        }
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        if (entry == null) {
            return Result.EMBLEM_PARKED;
        }
        // Emblems are traded by the settlement's seated Guildmaster (the
        // Mayor office is retired). MAYOR_UNAVAILABLE keeps its stable enum
        // slot and now reads "the Guildmaster is not at the Banner".
        if (com.hearthstead.settlement.guildmaster.GuildmasterService
                .live(level, settlement) == null) {
            return Result.MAYOR_UNAVAILABLE;
        }
        if (!emblemKnowledge(state, entry)) {
            return Result.EMBLEM_LOCKED;
        }
        if (hearth == null || !canPay(CoinTreasury.open(level, settlement, hearth, actor), entry.costs())) {
            return Result.MATERIALS;
        }
        return Result.APPLIED;
    }

    /**
     * The shortfall of each emblem cost line (Coins and goods) against the
     * exact treasury view a purchase would pay from. Empty when affordable or
     * when the emblem is not in the catalogue. Read-only; used to tell the
     * buyer precisely what is missing when a purchase is refused.
     */
    public static List<DevelopmentNode.Cost> missingEmblemCosts(ServerLevel level,
            Settlement settlement, HearthBlockEntity hearth, Profession profession,
            @Nullable ServerPlayer actor) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        if (entry == null) {
            return List.of();
        }
        return missingCosts(level, settlement, hearth, entry.costs(), actor);
    }

    /**
     * The shortfall of any price (a tech-tree node, an upgrade or an emblem)
     * against the exact treasury view a purchase pays from: the buyer's
     * inventory, the bound Hearth and linked Warehouse storage. Read-only;
     * lets a refusal name what is missing instead of only "not enough".
     */
    public static List<DevelopmentNode.Cost> missingCosts(ServerLevel level,
            Settlement settlement, HearthBlockEntity hearth,
            List<DevelopmentNode.Cost> costs, @Nullable ServerPlayer actor) {
        if (costs == null || costs.isEmpty()) {
            return List.of();
        }
        ItemStackHandler treasury = hearth == null ? null
            : CoinTreasury.open(level, settlement, hearth, actor);
        java.util.ArrayList<DevelopmentNode.Cost> missing = new java.util.ArrayList<>();
        for (DevelopmentNode.Cost cost : costs) {
            int have = treasury == null ? 0 : count(treasury, cost);
            if (have < cost.count()) {
                missing.add(new DevelopmentNode.Cost(cost.item(),
                    cost.count() - have, cost.acceptedTag(), cost.displayKey()));
            }
        }
        return List.copyOf(missing);
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
        Result assessment = assessEmblem(level, settlement, hearth, profession, actor);
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
            pay(CoinTreasury.open(level, settlement, hearth, actor), entry.costs());
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
        if (node.doctrine() && state.unlocked(node)) {
            return true;
        }
        if (node.doctrine() && state.activeDoctrine() == null) {
            // Existing saves may already contain a post-raid building. Keep
            // that real world intact by adopting the first matching branch;
            // fresh saves can only reach this through purchaseNode.
            state.learnDoctrine(node, Long.MIN_VALUE);
        } else if (node.doctrine() && state.activeDoctrine() != node) {
            // A legacy settlement can physically contain buildings from more
            // than one future doctrine. Preserve those blocks, but never grant
            // a second doctrine for free: later doctrine knowledge is paid.
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

    /**
     * All-or-nothing physical payment of Coins plus goods across the whole
     * treasury view (buyer, Hearth, Warehouse chests). Every line is checked
     * first; then each line is extracted through the owning container's own
     * extract path, verified, and on any refusal every touched slot is
     * restored from a snapshot before the failure propagates, so a purchase
     * can never charge part of a price.
     */
    static void pay(ItemStackHandler inventory,
                    List<DevelopmentNode.Cost> costs) {
        if (costs.isEmpty()) return;
        if (!canPay(inventory, costs)) {
            throw new IllegalStateException("Insufficient physical materials");
        }
        List<ItemStack> before = CoinTreasury.snapshot(inventory);
        try {
            for (DevelopmentNode.Cost cost : costs) {
                int left = cost.count();
                for (int slot = 0; slot < inventory.getSlots() && left > 0; slot++) {
                    ItemStack stack = inventory.getStackInSlot(slot);
                    if (!cost.matches(stack)) continue;
                    int take = Math.min(left, stack.getCount());
                    ItemStack expected = stack.copyWithCount(take);
                    ItemStack removed = inventory.extractItem(slot, take, false);
                    if (removed.getCount() != take
                        || !ItemStack.isSameItemSameComponents(expected, removed)) {
                        throw new IllegalStateException("Physical extraction refused");
                    }
                    left -= take;
                }
                if (left != 0) throw new IllegalStateException("Payment changed during extraction");
            }
        } catch (RuntimeException failure) {
            CoinTreasury.restore(inventory, before);
            throw failure;
        }
    }

    /** Package test seam: the exact check purchase paths use. */
    static boolean canPayForTest(ItemStackHandler inventory,
                                 List<DevelopmentNode.Cost> costs) {
        return canPay(inventory, costs);
    }
}

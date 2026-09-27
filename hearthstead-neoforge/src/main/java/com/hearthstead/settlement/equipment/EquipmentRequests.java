package com.hearthstead.settlement.equipment;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestItemFingerprint;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.util.AuthorityTelemetry;
import com.hearthstead.util.QaTrace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server authority for the equipment request board.
 *
 * <p>The board stores intent only. A tool exists exclusively in a chest, a
 * courier bag, on the ground, or in a settler's hand. That makes reloads and
 * interrupted routes recoverable without ever reconciling a virtual item.
 */
public final class EquipmentRequests {

    /** Long enough for a normal cross-settlement route, renewed while active. */
    public static final long CLAIM_TTL_TICKS = 1_200L;
    /** Persist at most twice per lease instead of dirtying SavedData every AI tick. */
    private static final long CLAIM_RENEW_WINDOW_TICKS = CLAIM_TTL_TICKS / 2;
    /** A delivered tool may wait one minute for its worker to load/reach work. */
    public static final long DELIVERED_RECHECK_TICKS = 1_200L;
    /** Goal selectors may ask every tick; missing-tool storage scans may not. */
    public static final int WORKPLACE_RETRY_TICKS = 20;
    private static final int MAX_RETRY_ROWS = 512;
    private static final Map<UUID, Long> NEXT_WORKPLACE_RETRY =
        new LinkedHashMap<>();

    /**
     * Per-worker floor on a tool's remaining uses for the job already in hand
     * (set by a Lumberer whose axe cannot finish the tree it has claimed).
     * Without it a 10-use iron axe was "serviceable" (>= 8) yet refused a
     * 10-log tree forever, so no replacement was ever requested and the
     * Lumberer stood at the trunk for hours (captain1 soak, Bramwell).
     */
    public static final String JOB_MIN_USES_TAG = "HearthsteadJobToolMinUses";

    /** Raise this worker's tool floor to {@code uses} until the job clears it. */
    public static void requireJobUses(SettlerEntity settler, int uses) {
        settler.getPersistentData().putInt(JOB_MIN_USES_TAG, Math.max(1, uses));
    }

    public static void clearJobUses(SettlerEntity settler) {
        settler.getPersistentData().remove(JOB_MIN_USES_TAG);
    }

    /** The profession requirement with this worker's job floor applied. */
    @Nullable
    public static EquipmentRequirement requirementFor(Profession profession, SettlerEntity settler) {
        EquipmentRequirement base = requirementFor(profession);
        if (base == null || settler == null) {
            return base;
        }
        // The hero Captain's chosen weapon kit (axe, bow, halberd...) is his
        // equipment: no plain-sword request while he holds a complete kit.
        if (com.hearthstead.entity.combat.captain.CaptainKit.holdsKit(settler)) {
            return null;
        }
        // A Guard holding any serviceable melee weapon TYPE (longsword, spear,
        // great axe, halberd, warhammer, short sword) is armed: accept it
        // instead of requesting a plain sword over it. The armoury still
        // requests a sword for an unarmed guard.
        if (profession == Profession.GUARD && holdsServiceableGuardWeapon(settler,
                base.minimumRemainingUses())) {
            return null;
        }
        int floor = settler.getPersistentData().getInt(JOB_MIN_USES_TAG);
        return floor > base.minimumRemainingUses()
            ? new EquipmentRequirement(base.preferredItem(), base.acceptedTag(), floor, base.maxGearTier())
            : base;
    }

    /** Non-sword guard weapon types, by tag, with enough uses left to fight. */
    static boolean holdsServiceableGuardWeapon(SettlerEntity settler, int minimumUses) {
        net.minecraft.world.item.ItemStack held = settler.getMainHandItem();
        if (held.is(ItemTags.SWORDS) || !settler.hasGuardMeleeWeapon()) {
            return false;
        }
        return !held.isDamageableItem()
            || held.getMaxDamage() - held.getDamageValue() >= minimumUses;
    }

    @Nullable
    public static EquipmentRequirement requirementFor(Profession profession) {
        return switch (profession) {
            case FISHER -> new EquipmentRequirement(com.hearthstead.registry.ModItems.FISHERS_ROD.get(), null, 1);
            case FARMER -> new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8);
            case LUMBERER -> new EquipmentRequirement(Items.IRON_AXE,
                ResourceLocation.withDefaultNamespace("axes"), 8);
            case GUARD -> new EquipmentRequirement(Items.WOODEN_SWORD,
                ItemTags.SWORDS.location(), 8);
            case ARCHER -> new EquipmentRequirement(Items.BOW,
                ItemTags.BOW_ENCHANTABLE.location(), 8);
            case HUNTER -> new EquipmentRequirement(Items.BOW,
                ItemTags.BOW_ENCHANTABLE.location(), 8);
            // QA-JOBS J-10: the Miner cuts with a real pickaxe and the Herder
            // shears with real shears; both are requested like any work tool.
            case MINER -> new EquipmentRequirement(Items.IRON_PICKAXE,
                ItemTags.PICKAXES.location(), 8);
            case HERDER -> new EquipmentRequirement(Items.SHEARS,
                ResourceLocation.fromNamespaceAndPath("c", "tools/shear"), 8);
            // BATTLE-ROLES: the spear/longsword request, any tier of the tag.
            case SPEARMAN -> new EquipmentRequirement(
                com.hearthstead.registry.RoleItems.WOODEN_SPEAR.get(),
                com.hearthstead.registry.RoleItems.SPEARS.location(), 8);
            case LONGSWORDSMAN -> new EquipmentRequirement(
                com.hearthstead.registry.RoleItems.IRON_LONGSWORD.get(),
                com.hearthstead.registry.RoleItems.LONGSWORDS.location(), 8);
            default -> null;
        };
    }

    /**
     * Immutable delivery order for tests and cold callers without a level.
     * Production callers use the level-aware overload so migrations and new
     * request membership are persisted before the order is consumed.
     */
    public static List<EquipmentRequest> list(Settlement settlement) {
        return settlement.equipmentRequestQueue.ordered(rawList(settlement));
    }

    /** Exact persisted order shared by Courier AI and the Request Queue UI. */
    public static List<EquipmentRequest> list(ServerLevel level,
                                               Settlement settlement) {
        synchronizeQueue(level, settlement);
        return settlement.equipmentRequestQueue.ordered(rawList(settlement));
    }

    public static long queueRevision(ServerLevel level,
                                     Settlement settlement) {
        synchronizeQueue(level, settlement);
        return settlement.equipmentRequestQueue.revision();
    }

    /** One atomic, revision-checked manual move; nil before-id means the end. */
    public static EquipmentRequestQueue.MoveResult reorder(
            ServerLevel level, Settlement settlement, UUID movedRequestId,
            @Nullable UUID beforeRequestId, long expectedRevision) {
        List<EquipmentRequest> active = rawList(settlement);
        if (settlement.equipmentRequestQueue.reconcile(active)) {
            SettlementManager.data(level).setDirty();
            return EquipmentRequestQueue.MoveResult.STALE;
        }
        EquipmentRequestQueue.MoveResult result =
            settlement.equipmentRequestQueue.move(active, movedRequestId,
                beforeRequestId, expectedRevision);
        if (result == EquipmentRequestQueue.MoveResult.APPLIED) {
            SettlementManager.data(level).setDirty();
        }
        return result;
    }

    /**
     * Reconciles one worker and publishes exactly one request when needed.
     * Repeated calls are idempotent; changes dirty SavedData immediately.
     */
    @Nullable
    public static EquipmentRequest refreshFor(ServerLevel level,
                                              Settlement settlement,
                                              Building workplace,
                                              SettlerEntity settler) {
        EquipmentRequirement requirement = requirementFor(
            Employment.professionOf(settlement, settler.getUUID()), settler);
        if (requirement == null) {
            cancelFor(level, workplace, settler.getUUID());
            settler.setRequestedEquipmentProjection(null);
            return null;
        }

        ItemStack held = settler.getItemBySlot(EquipmentSlot.MAINHAND);
        if (requirement.serviceable(held)) {
            cancelFor(level, workplace, settler.getUUID());
            settler.setRequestedEquipmentProjection(null);
            return null;
        }

        EquipmentRequest.Reason reason;
        EquipmentRequest.Priority priority;
        if (held.isEmpty()) {
            reason = EquipmentRequest.Reason.MISSING;
            priority = EquipmentRequest.Priority.URGENT;
        } else if (requirement.matches(held)) {
            reason = EquipmentRequest.Reason.WORN;
            priority = EquipmentRequest.Priority.HIGH;
        } else {
            reason = EquipmentRequest.Reason.WRONG_TOOL;
            priority = EquipmentRequest.Priority.URGENT;
        }

        EquipmentRequest existing = requestFor(workplace, settler.getUUID());
        if (existing != null) {
            boolean changed = existing.updateNeed(priority, reason);
            changed |= reconcileDelivered(level, workplace, existing, held);
            if (changed) {
                settlement.equipmentRequestQueue.noteRowChanged();
                SettlementManager.data(level).setDirty();
            }
            settler.setRequestedEquipmentProjection(existing);
            return existing;
        }
        // Couriers and ground pickups honour the requester's Gear Tier cap.
        EquipmentRequest created = new EquipmentRequest(settler.getUUID(),
            workplace.id, Employment.professionOf(settlement, settler.getUUID()),
            com.hearthstead.settlement.gear.GearGate.limit(requirement, settler),
            1, priority, reason, level.getGameTime());
        int rowsBefore = rawList(settlement).size();
        long revisionBefore = settlement.equipmentRequestQueue.revision();
        workplace.equipmentRequests.add(created);
        synchronizeQueue(level, settlement);
        SettlementManager.data(level).setDirty();
        settler.setRequestedEquipmentProjection(created);
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EQUIPMENT_REQUEST_OPENED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "request:" + created.id(), revisionBefore,
                settlement.equipmentRequestQueue.revision(), rowsBefore,
                rawList(settlement).size(),
                BuiltInRegistries.ITEM.getKey(
                    requirement.preferredItem()).toString(),
                0, 0, 0, "requester:" + settler.getUUID()));
        JourneyServerHooks.noteEquipmentRequestOpened(level, settlement,
            workplace, settler, created);
        return created;
    }

    /**
     * Resolves a worker's live employment, consumes a matching physical tool
     * already in their own bag when possible, then publishes exactly one
     * request if a need remains.
     */
    @Nullable
    public static EquipmentRequest refreshFor(ServerLevel level,
                                              SettlerEntity settler) {
        Settlement settlement = settler.settlement();
        Building workplace = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (settlement == null || workplace == null) {
            settler.setRequestedEquipmentProjection(null);
            return null;
        }
        if (equipFromPersonalInventory(level, settlement, settler)) {
            return null;
        }
        return refreshFor(level, settlement, workplace, settler);
    }

    /**
     * Direct player-supply path for the settler's own real inventory.
     *
     * <p>If an unsuitable or worn item is already held, the newly supplied
     * unstackable tool and the old held stack swap places. No stack is
     * overwritten, copied or represented by the request row.
     */
    public static boolean equipFromPersonalInventory(ServerLevel level,
                                                     Settlement settlement,
                                                     SettlerEntity settler) {
        Building workplace = Employment.employerOf(settlement, settler.getUUID());
        Profession profession = Employment.professionOf(settlement,
            settler.getUUID());
        EquipmentRequirement requirement = requirementFor(profession, settler);
        if (workplace == null || requirement == null) {
            if (workplace != null) {
                cancelFor(level, workplace, settler.getUUID());
            }
            settler.setRequestedEquipmentProjection(null);
            return requirement == null;
        }

        ItemStack held = settler.getItemBySlot(EquipmentSlot.MAINHAND);
        EquipmentRequest satisfiedRequest = requestFor(workplace,
            settler.getUUID());
        if (requirement.serviceable(held)) {
            cancelFor(level, workplace, settler.getUUID());
            settler.setRequestedEquipmentProjection(null);
            return true;
        }

        // Gear Tier gate: a tool above this settler's clearance stays in the
        // pack (refused, never deleted) and the held tool stays in hand.
        EquipmentRequirement allowed =
            com.hearthstead.settlement.gear.GearGate.limit(requirement, settler);
        int suppliedSlot = -1;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            if (allowed.serviceable(settler.bag.getItem(slot))) {
                suppliedSlot = slot;
                break;
            }
        }
        if (suppliedSlot < 0) {
            return false;
        }

        ItemStack suppliedStack = settler.bag.getItem(suppliedSlot);
        ItemStack supplied = suppliedStack.copyWithCount(1);
        String suppliedId = itemId(supplied);
        if (held.isEmpty()) {
            settler.bag.removeItem(suppliedSlot, 1);
        } else if (suppliedStack.getCount() == 1) {
            // Requested tools are unstackable today. The source slot is a
            // guaranteed physical home for the displaced stack, making this
            // an exact swap with no failure window.
            settler.bag.setItem(suppliedSlot, held.copy());
        } else {
            int emptySlot = firstEmptySlotExcept(settler, suppliedSlot);
            if (emptySlot < 0) {
                return false;
            }
            settler.bag.setItem(emptySlot, held.copy());
            suppliedStack.shrink(1);
            settler.bag.setItem(suppliedSlot, suppliedStack);
        }
        settler.setItemSlot(EquipmentSlot.MAINHAND, supplied);
        NEXT_WORKPLACE_RETRY.remove(settler.getUUID());
        cancelFor(level, workplace, settler.getUUID());
        settler.setRequestedEquipmentProjection(null);
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EQUIPMENT_ITEM_PICKED_UP,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "settler:" + settler.getUUID(), 0, 0, 1, 1,
                suppliedId, 1, 1, 0, "personal_inventory_to_mainhand"));
        if (satisfiedRequest != null) {
            JourneyServerHooks.noteEquipmentRequestSatisfied(level, settlement,
                workplace, settler, satisfiedRequest);
        }
        return true;
    }

    /**
     * Player fulfilment path: putting a requested tool in the worker's own
     * workplace chest lets the worker take exactly that physical item.
     */
    public static boolean equipFromWorkplace(ServerLevel level,
                                             Settlement settlement,
                                             SettlerEntity settler) {
        Building workplace = Employment.employerOf(settlement, settler.getUUID());
        if (workplace == null) {
            return false;
        }
        EquipmentRequirement requirement = requirementFor(
            Employment.professionOf(settlement, settler.getUUID()), settler);
        if (requirement == null) {
            return true;
        }
        ItemStack held = settler.getItemBySlot(EquipmentSlot.MAINHAND);
        if (requirement.serviceable(held)) {
            NEXT_WORKPLACE_RETRY.remove(settler.getUUID());
            cancelFor(level, workplace, settler.getUUID());
            return true;
        }
        // The worker's own physical inventory is closer authority than a
        // workplace chest. This is what makes direct player insertion usable
        // without waiting for, or duplicating, a Courier delivery.
        if (equipFromPersonalInventory(level, settlement, settler)) {
            return true;
        }
        held = settler.getItemBySlot(EquipmentSlot.MAINHAND);
        long now = level.getGameTime();
        Long retryAt = NEXT_WORKPLACE_RETRY.get(settler.getUUID());
        if (retryAt != null && retryAt > now) {
            return false;
        }
        scheduleRetry(settler.getUUID(), now);
        refreshFor(level, settlement, workplace, settler);
        BlockPos source = WorkplaceStorage.nearestMatchingContainer(level,
            workplace, com.hearthstead.settlement.gear.GearGate.limit(
                requirement, settler), settler.blockPosition());
        if (!canReachContainer(level, settler, source)) {
            return false;
        }
        return equipFromWorkplaceAt(level, settlement, settler, source);
    }

    /**
     * Exact contact transfer used by the acquisition goal after the worker
     * physically reaches and can see their workplace container.
     */
    public static boolean equipFromWorkplaceAt(ServerLevel level,
                                               Settlement settlement,
                                               SettlerEntity settler,
                                               BlockPos source) {
        Building workplace = Employment.employerOf(settlement, settler.getUUID());
        EquipmentRequirement requirement = requirementFor(
            Employment.professionOf(settlement, settler.getUUID()), settler);
        if (workplace == null || requirement == null
            || !canReachContainer(level, settler, source)) {
            return false;
        }
        ItemStack held = settler.getItemBySlot(EquipmentSlot.MAINHAND);
        EquipmentRequest satisfiedRequest = requestFor(workplace,
            settler.getUUID());
        if (requirement.serviceable(held)) {
            cancelFor(level, workplace, settler.getUUID());
            return true;
        }
        ItemStack supplied = WorkplaceStorage.swapOneAt(level, workplace,
            source, com.hearthstead.settlement.gear.GearGate.limit(
                requirement, settler), held);
        if (supplied.isEmpty()) {
            return false;
        }
        supplied.setCount(1);
        String suppliedId = itemId(supplied);
        settler.setItemSlot(EquipmentSlot.MAINHAND, supplied);
        NEXT_WORKPLACE_RETRY.remove(settler.getUUID());
        cancelFor(level, workplace, settler.getUUID());
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EQUIPMENT_ITEM_PICKED_UP,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "settler:" + settler.getUUID(), 0, 0, 1, 1,
                suppliedId, 1, 1, 0, "workplace_chest_contact"));
        if (satisfiedRequest != null) {
            JourneyServerHooks.noteEquipmentRequestSatisfied(level, settlement,
                workplace, settler, satisfiedRequest);
        }
        return true;
    }

    private static boolean canReachContainer(ServerLevel level,
                                             SettlerEntity settler,
                                             @Nullable BlockPos source) {
        return ContainerApproach.inspect(level, settler, source).canInteract();
    }

    /**
     * Server-authoritative hand-contact ray for a real ground item. Aim at the
     * upper face of the low ItemEntity box so the supporting floor does not
     * reject a legitimate pickup while solid walls and roofs still occlude it.
     */
    public static boolean hasPhysicalGroundContact(ServerLevel level,
                                                   SettlerEntity settler,
                                                   ItemEntity groundItem) {
        if (level == null || settler == null || groundItem == null
            || !groundItem.isAlive() || settler.level() != level
            || groundItem.level() != level) {
            return false;
        }
        AABB box = groundItem.getBoundingBox();
        Vec3 contact = new Vec3(groundItem.getX(), box.maxY + 1.0E-3D,
            groundItem.getZ());
        Vec3 eye = settler.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, contact,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, settler));
        if (QaTrace.ENABLED && hit.getType() != HitResult.Type.MISS) {
            QaTrace.event(settler, "equipment_ground_contact_blocked",
                "eye=" + eye + ";contact=" + contact + ";item="
                    + groundItem.position() + ";box=" + box + ";hit="
                    + hit.getBlockPos() + ";hitPos=" + hit.getLocation()
                    + ";face=" + hit.getDirection());
        }
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Contact-frame transfer from one matching world entity into the worker's
     * main hand. Any displaced held stack is first preserved in the bag or,
     * when the bag is full, as a newly accepted world entity at the worker's
     * feet. Only then is one item removed from {@code groundItem}.
     */
    public static boolean equipFromGround(ServerLevel level,
                                          SettlerEntity settler,
                                          ItemEntity groundItem,
                                          double maxDistanceSqr) {
        EquipmentRequest request = refreshFor(level, settler);
        if (request == null || groundItem == null || !groundItem.isAlive()
            || settler.distanceToSqr(groundItem) > maxDistanceSqr
            || !hasPhysicalGroundContact(level, settler, groundItem)
            || groundItem.getPersistentData().hasUUID(
                "HearthsteadGroundCollectionOwner")) {
            return false;
        }
        ItemStack source = groundItem.getItem();
        if (source.isEmpty() || !request.requirement().serviceable(source)
            || !com.hearthstead.settlement.gear.GearGate.allows(settler, source)) {
            return false;
        }

        ItemStack displaced = settler.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!displaced.isEmpty()) {
            int emptySlot = firstEmptySlotExcept(settler, -1);
            if (emptySlot >= 0) {
                settler.bag.setItem(emptySlot, displaced.copy());
            } else {
                ItemEntity preserved = new ItemEntity(level, settler.getX(),
                    settler.getY() + 0.25D, settler.getZ(), displaced.copy());
                if (!level.addFreshEntity(preserved)) {
                    return false;
                }
            }
        }

        ItemStack supplied = source.copyWithCount(1);
        String suppliedId = itemId(supplied);
        if (source.getCount() == 1) {
            groundItem.discard();
        } else {
            source.shrink(1);
            groundItem.setItem(source);
        }
        settler.setItemSlot(EquipmentSlot.MAINHAND, supplied);
        Building workplace = Employment.employerOf(settler.settlement(),
            settler.getUUID());
        if (workplace != null) {
            cancelFor(level, workplace, settler.getUUID());
        }
        NEXT_WORKPLACE_RETRY.remove(settler.getUUID());
        settler.setRequestedEquipmentProjection(null);
        Settlement settlement = settler.settlement();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EQUIPMENT_ITEM_PICKED_UP,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(
                settlement == null ? null : settlement.id,
                "settler:" + settler.getUUID(), 0, 0, 1, 1,
                suppliedId, 1, 1, 0, "ground_entity_contact"));
        if (settlement != null && workplace != null) {
            JourneyServerHooks.noteEquipmentRequestSatisfied(level, settlement,
                workplace, settler, request);
        }
        return true;
    }

    /**
     * Shared combat/work gate. A direct-profession test fixture may provide
     * a real weapon in hand; a live employee with no weapon must obtain one
     * through their workplace request and physical storage transfer.
     */
    public static boolean readyForProfession(ServerLevel level,
                                             SettlerEntity settler,
                                             Profession expected) {
        if (settler.getProfession() != expected) {
            return false;
        }
        EquipmentRequirement requirement = requirementFor(expected, settler);
        if (requirement == null) {
            return true;
        }
        if (requirement.serviceable(
                settler.getItemBySlot(EquipmentSlot.MAINHAND))) {
            return true;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null
            || Employment.professionOf(settlement, settler.getUUID()) != expected) {
            return false;
        }
        Building workplace = Employment.employerOf(settlement,
            settler.getUUID());
        if (workplace != null) {
            refreshFor(level, settlement, workplace, settler);
        }
        // Readiness is a pure combat/work gate. The dedicated acquisition
        // goal owns the visible walk and contact-frame chest transfer.
        return false;
    }

    @Nullable
    public static EquipmentRequest requestFor(Building workplace, UUID requesterId) {
        for (EquipmentRequest request : workplace.equipmentRequests) {
            if (request.requesterId().equals(requesterId)) {
                return request;
            }
        }
        return null;
    }

    public static boolean cancelFor(ServerLevel level, Building workplace,
                                    UUID requesterId) {
        boolean changed = false;
        Iterator<EquipmentRequest> requests =
            workplace.equipmentRequests.iterator();
        while (requests.hasNext()) {
            EquipmentRequest request = requests.next();
            if (!request.requesterId().equals(requesterId)) {
                continue;
            }
            if (request.cancelPending()) {
                if (request.cancellationReadyForRemoval()) {
                    requests.remove();
                    changed = true;
                }
                continue;
            }
            if (request.requestCancellation()) {
                changed = true;
                continue;
            }
            requests.remove();
            changed = true;
        }
        if (changed) {
            Settlement settlement = settlementContaining(level, workplace);
            if (settlement != null) {
                long revision = settlement.equipmentRequestQueue.revision();
                synchronizeQueue(level, settlement);
                if (settlement.equipmentRequestQueue.revision() == revision) {
                    settlement.equipmentRequestQueue.noteRowChanged();
                }
            }
            SettlementManager.data(level).setDirty();
        }
        return changed;
    }

    @Nullable
    public static EquipmentRequest byId(Settlement settlement, UUID requestId) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        return request != null && !request.cancelPending() ? request : null;
    }

    /** Internal physical-recovery lookup; public callers cannot deliver it. */
    @Nullable
    private static EquipmentRequest byIdIncludingCancellation(
            Settlement settlement, UUID requestId) {
        for (Building building : settlement.buildings) {
            for (EquipmentRequest request : building.equipmentRequests) {
                if (request.id().equals(requestId)) {
                    return request;
                }
            }
        }
        return null;
    }

    public static boolean claim(ServerLevel level, Settlement settlement,
                                UUID requestId, UUID courierId) {
        EquipmentRequest request = byId(settlement, requestId);
        long revisionBefore = settlement.equipmentRequestQueue.revision();
        EquipmentRequest.Status beforeStatus = request == null ? null
            : request.status();
        UUID beforeCourier = request == null ? null : request.claimedBy();
        long beforeLease = request == null ? 0L : request.leaseUntilTick();
        if (request == null || !request.claim(courierId, level.getGameTime(),
                CLAIM_TTL_TICKS)) {
            return false;
        }
        boolean visibleChange = beforeStatus != request.status()
            || !java.util.Objects.equals(beforeCourier, request.claimedBy());
        if (visibleChange) {
            settlement.equipmentRequestQueue.noteRowChanged();
        }
        if (visibleChange || beforeLease != request.leaseUntilTick()) {
            SettlementManager.data(level).setDirty();
        }
        if (visibleChange) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.EQUIPMENT_REQUEST_CLAIMED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "request:" + request.id(), revisionBefore,
                    settlement.equipmentRequestQueue.revision(),
                    rawList(settlement).size(), rawList(settlement).size(),
                    BuiltInRegistries.ITEM.getKey(
                        request.requirement().preferredItem()).toString(),
                    0, 0, 0, "courier:" + courierId));
        }
        return true;
    }

    /**
     * Commits one exact loaded route after claim, without moving or owning an
     * item. Both endpoints and the selected source slot are re-read here so
     * a stale AI scan cannot publish a route that no longer exists.
     */
    public static boolean bindRoute(ServerLevel level, Settlement settlement,
                                    UUID requestId, SettlerEntity courier,
                                    Building sourceBuilding,
                                    BlockPos sourcePos, int sourceSlot,
                                    Building targetBuilding,
                                    BlockPos targetPos, ItemStack selected) {
        EquipmentRequest request = byId(settlement, requestId);
        if (!validCourier(settlement, courier) || request == null
            || request.status() != EquipmentRequest.Status.CLAIMED
            || !courier.getUUID().equals(request.claimedBy())
            || sourceBuilding == null
            || sourceBuilding.type != BuildingType.WAREHOUSE
            || targetBuilding == null
            || !targetBuilding.id.equals(request.destinationBuildingId())
            || !sourceBuilding.contains(sourcePos)
            || !targetBuilding.contains(targetPos)
            || selected == null || selected.isEmpty()
            || selected.getCount() != 1
            || !request.requirement().serviceable(selected)
            || !level.hasChunkAt(sourcePos) || !level.hasChunkAt(targetPos)
            || !(level.getBlockEntity(sourcePos) instanceof Container source)
            || !(level.getBlockEntity(targetPos) instanceof Container target)
            || sourceSlot < 0 || sourceSlot >= source.getContainerSize()) {
            return false;
        }
        ItemStack live = source.getItem(sourceSlot);
        if (live.isEmpty() || !ItemStack.isSameItemSameComponents(live, selected)
            || !request.requirement().serviceable(live)
            || !hasRoomForExact(target, selected)) {
            return false;
        }
        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(),
                live, 1);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        int targetBefore = matchingCount(level, target, fingerprint);
        if (targetBefore > 64 || !request.bindRoute(courier.getUUID(),
                sourceBuilding.id, sourcePos, sourceSlot, live.getCount(),
                targetPos, targetBefore, fingerprint)) {
            return false;
        }
        settlement.equipmentRequestQueue.noteRowChanged();
        SettlementManager.data(level).setDirty();
        return true;
    }

    /** Commits SOURCE -> COURIER_BAG only after both exact inventories agree. */
    public static boolean markPickedUp(ServerLevel level, Settlement settlement,
                                       UUID requestId, SettlerEntity courier) {
        EquipmentRequest request = byId(settlement, requestId);
        RequestItemFingerprint fingerprint = request == null ? null
            : request.traceFingerprint(level.registryAccess());
        if (!validCourier(settlement, courier) || request == null
            || fingerprint == null
            || request.traceStage() != EquipmentRequest.TraceStage.SOURCE
            || !courier.getUUID().equals(request.claimedBy())
            || !loadedExactSourceAfterPickup(level, request, fingerprint)
            || matchingCount(courier.bag, fingerprint,
                level.registryAccess()) < 1
            || !request.markPickedUp(courier.getUUID())) {
            return false;
        }
        settlement.equipmentRequestQueue.noteRowChanged();
        SettlementManager.data(level).setDirty();
        return true;
    }

    public static void renew(ServerLevel level, Settlement settlement,
                             UUID requestId, UUID courierId) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        long now = level.getGameTime();
        if (request == null
            || request.status() != EquipmentRequest.Status.CLAIMED
            || !courierId.equals(request.claimedBy())
            || request.leaseUntilTick() - now > CLAIM_RENEW_WINDOW_TICKS) {
            return;
        }
        if (request.renew(courierId, now, CLAIM_TTL_TICKS)) {
            SettlementManager.data(level).setDirty();
        }
    }

    public static boolean release(ServerLevel level, Settlement settlement,
                                  UUID requestId, UUID courierId) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        if (request != null && request.release(courierId)) {
            settlement.equipmentRequestQueue.noteRowChanged();
            SettlementManager.data(level).setDirty();
            return true;
        }
        return false;
    }

    /**
     * Compatibility signature retained for existing callers, but no longer a
     * legacy state-only bypass: it resolves the live Courier and delegates to
     * the same strict physical proof edge as production AI.
     */
    @Deprecated(forRemoval = true)
    public static boolean markDelivered(ServerLevel level,
                                        Settlement settlement,
                                        UUID requestId, UUID courierId) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        if (request == null || request.targetContainer() == null
            || courierId == null
            || !(level.getEntity(courierId) instanceof SettlerEntity courier)) {
            return false;
        }
        return markDelivered(level, settlement, requestId, courier,
            request.targetContainer());
    }

    /**
     * Strict production edge: the Courier bag must no longer own the exact
     * stack and the exact loaded workplace container must show the committed
     * +1 delta before TARGET is persisted.
     */
    public static boolean markDelivered(ServerLevel level, Settlement settlement,
                                        UUID requestId, SettlerEntity courier,
                                        BlockPos targetPos) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        RequestItemFingerprint fingerprint = request == null ? null
            : request.traceFingerprint(level.registryAccess());
        long revisionBefore = settlement.equipmentRequestQueue.revision();
        if (!validCourier(settlement, courier) || request == null
            || fingerprint == null
            || request.traceStage() != EquipmentRequest.TraceStage.COURIER_BAG
            || !courier.getUUID().equals(request.claimedBy())
            || request.targetContainer() == null
            || !request.targetContainer().equals(targetPos)
            || !level.hasChunkAt(targetPos)
            || !(level.getBlockEntity(targetPos) instanceof Container target)
            || matchingCount(courier.bag, fingerprint,
                level.registryAccess()) != 0
            || matchingCount(level, target, fingerprint)
                < request.targetCountBefore() + 1
            || !request.markDeliveredTraced(courier.getUUID(),
                level.getGameTime())) {
            return false;
        }
        afterDelivered(level, settlement, request, courier.getUUID(),
            revisionBefore);
        return true;
    }

    /** Commits a failed trip only after the exact source owns the item again. */
    public static boolean markReturned(ServerLevel level, Settlement settlement,
                                       UUID requestId, SettlerEntity courier,
                                       int physicallyReturned) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        RequestItemFingerprint fingerprint = request == null ? null
            : request.traceFingerprint(level.registryAccess());
        BlockPos sourcePos = request == null ? null : request.sourceContainer();
        if (!validCourier(settlement, courier) || request == null
            || fingerprint == null || physicallyReturned != 1
            || request.traceStage() != EquipmentRequest.TraceStage.COURIER_BAG
            || sourcePos == null || !level.hasChunkAt(sourcePos)
            || !(level.getBlockEntity(sourcePos) instanceof Container source)
            || matchingCount(courier.bag, fingerprint,
                level.registryAccess()) != 0
            || matchingCount(level, source, fingerprint) < 1
            || !request.markReturned(courier.getUUID())) {
            return false;
        }
        if (request.cancellationReadyForRemoval()) {
            removeRequest(settlement, request);
            synchronizeQueue(level, settlement);
        } else {
            settlement.equipmentRequestQueue.noteRowChanged();
        }
        SettlementManager.data(level).setDirty();
        return true;
    }

    public static void block(ServerLevel level, Settlement settlement,
                             UUID requestId, UUID courierId,
                             RequestBlocker blocker) {
        EquipmentRequest request = byIdIncludingCancellation(settlement,
            requestId);
        if (request != null && request.block(courierId, blocker)) {
            settlement.equipmentRequestQueue.noteRowChanged();
            SettlementManager.data(level).setDirty();
        }
    }

    /** Persisted route adopted by the same Courier after goal/world reload. */
    @Nullable
    public static CourierRoute routeForCourier(ServerLevel level,
                                                 Settlement settlement,
                                                 SettlerEntity courier) {
        if (!validCourier(settlement, courier)) {
            return null;
        }
        EquipmentRequest found = null;
        for (EquipmentRequest request : rawList(settlement)) {
            if (request.status() == EquipmentRequest.Status.CLAIMED
                && courier.getUUID().equals(request.claimedBy())
                && request.hasTransportTrace()) {
                if (found != null) {
                    return null;
                }
                found = request;
            }
        }
        if (found == null
            || found.traceStage() == EquipmentRequest.TraceStage.TARGET) {
            return null;
        }
        RequestItemFingerprint fingerprint = found.traceFingerprint(
            level.registryAccess());
        Building source = buildingById(settlement, found.sourceBuildingId());
        Building target = buildingById(settlement,
            found.destinationBuildingId());
        BlockPos sourcePos = found.sourceContainer();
        BlockPos targetPos = found.targetContainer();
        boolean cancellationReturn = found.cancelPending();
        if (fingerprint == null || source == null
            || source.type != BuildingType.WAREHOUSE
            || sourcePos == null || targetPos == null
            || !source.contains(sourcePos)
            || !cancellationReturn && (target == null
                || !target.contains(targetPos))) {
            return null;
        }
        ItemStack exact = fingerprint.prototype(level.registryAccess());
        if (exact.isEmpty() || !found.requirement().serviceable(exact)) {
            return null;
        }
        RequestBlocker resumeBlocker = RequestBlocker.NONE;
        if (found.traceStage() == EquipmentRequest.TraceStage.SOURCE) {
            if (bagItemCount(courier) != 0 || !level.hasChunkAt(sourcePos)
                || !(level.getBlockEntity(sourcePos) instanceof Container sourceChest)
                || found.sourceSlot() < 0
                || found.sourceSlot() >= sourceChest.getContainerSize()
                || !fingerprint.matches(level.registryAccess(),
                    sourceChest.getItem(found.sourceSlot()))
                || !level.hasChunkAt(targetPos)
                || !(level.getBlockEntity(targetPos) instanceof Container targetChest)
                || !hasRoomForExact(targetChest, exact)) {
                return null;
            }
        } else if (found.traceStage()
                == EquipmentRequest.TraceStage.COURIER_BAG) {
            if (bagItemCount(courier) != 1
                || matchingCount(courier.bag, fingerprint,
                    level.registryAccess()) != 1) {
                return null;
            }
            if (cancellationReturn) {
                resumeBlocker = RequestBlocker.RETURNED_TO_SOURCE;
            } else {
                boolean targetLoaded = level.hasChunkAt(targetPos);
                Container targetChest = targetLoaded
                        && level.getBlockEntity(targetPos) instanceof Container container
                    ? container : null;
                resumeBlocker = targetResumeBlocker(targetLoaded,
                    targetChest != null,
                    targetChest != null && hasRoomForExact(targetChest, exact));
            }
        } else {
            return null;
        }
        if (resumeBlocker == RequestBlocker.NONE
            && found.clearRevalidatedTargetBlocker(courier.getUUID())) {
            settlement.equipmentRequestQueue.noteRowChanged();
            SettlementManager.data(level).setDirty();
        }
        Building routeTarget = target == null ? source : target;
        BlockPos routeTargetPos = target == null ? sourcePos : targetPos;
        return new CourierRoute(found, source, sourcePos, routeTarget,
            routeTargetPos,
            exact.copyWithCount(1), found.traceStage(), resumeBlocker);
    }

    /**
     * A persisted bag-stage claim must never fall through to generic Courier
     * cargo after goal/entity reconstruction. If its exact route cannot be
     * reconstructed, keep the physical load where it is and publish one
     * concrete fail-closed blocker instead of authorising unrelated work.
     */
    public static boolean parkUnadoptableInTransit(
            ServerLevel level, Settlement settlement, SettlerEntity courier) {
        if (!validCourier(settlement, courier)) {
            return false;
        }
        List<EquipmentRequest> found = new ArrayList<>();
        for (EquipmentRequest request : rawList(settlement)) {
            if (request.status() == EquipmentRequest.Status.CLAIMED
                && courier.getUUID().equals(request.claimedBy())
                && (request.traceStage()
                        == EquipmentRequest.TraceStage.COURIER_BAG
                    || request.cancelPending())) {
                found.add(request);
            }
        }
        if (found.isEmpty()) {
            return false;
        }
        for (EquipmentRequest request : found) {
            RequestBlocker blocker = found.size() == 1
                ? unadoptableBlocker(level, settlement, courier, request)
                : RequestBlocker.MALFORMED;
            block(level, settlement, request.id(), courier.getUUID(), blocker);
        }
        return true;
    }

    static RequestBlocker targetResumeBlocker(boolean chunkLoaded,
                                               boolean containerPresent,
                                               boolean hasRoom) {
        if (!chunkLoaded) {
            return RequestBlocker.TARGET_UNLOADED;
        }
        if (!containerPresent) {
            return RequestBlocker.TARGET_INVALID;
        }
        return hasRoom ? RequestBlocker.NONE : RequestBlocker.TARGET_FULL;
    }

    private static RequestBlocker unadoptableBlocker(
            ServerLevel level, Settlement settlement, SettlerEntity courier,
            EquipmentRequest request) {
        RequestItemFingerprint fingerprint = request.traceFingerprint(
            level.registryAccess());
        Building source = buildingById(settlement, request.sourceBuildingId());
        Building target = buildingById(settlement,
            request.destinationBuildingId());
        if (fingerprint == null) {
            return RequestBlocker.MALFORMED;
        }
        if (source == null || source.type != BuildingType.WAREHOUSE
            || request.sourceContainer() == null
            || !source.contains(request.sourceContainer())) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (target == null || request.targetContainer() == null
            || !target.contains(request.targetContainer())) {
            return RequestBlocker.TARGET_INVALID;
        }
        if (bagItemCount(courier) != 1
            || matchingCount(courier.bag, fingerprint,
                level.registryAccess()) != 1) {
            return RequestBlocker.FINGERPRINT_MISMATCH;
        }
        return RequestBlocker.MALFORMED;
    }

    public record CourierRoute(EquipmentRequest request, Building source,
                               BlockPos sourceContainer, Building target,
                               BlockPos targetContainer, ItemStack exactStack,
                               EquipmentRequest.TraceStage stage,
                               RequestBlocker resumeBlocker) {
        public CourierRoute {
            sourceContainer = sourceContainer.immutable();
            targetContainer = targetContainer.immutable();
            exactStack = exactStack.copyWithCount(1);
            resumeBlocker = java.util.Objects.requireNonNull(resumeBlocker,
                "resumeBlocker");
        }
    }

    private static void afterDelivered(ServerLevel level,
                                       Settlement settlement,
                                       EquipmentRequest request,
                                       UUID courierId,
                                       long revisionBefore) {
        settlement.equipmentRequestQueue.noteRowChanged();
        SettlementManager.data(level).setDirty();
        DevelopmentQuests.noteEquipmentRequestDelivered(level, settlement,
            request.id(), courierId);
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.EQUIPMENT_ITEM_DELIVERED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "request:" + request.id(), revisionBefore,
                settlement.equipmentRequestQueue.revision(),
                rawList(settlement).size(), rawList(settlement).size(),
                BuiltInRegistries.ITEM.getKey(
                    request.requirement().preferredItem()).toString(),
                request.count(), request.count(), 0,
                "physical_workplace_insert_by:" + courierId));
    }

    /** Expired claims reopen; delivered rows reopen only if the tool vanished. */
    public static void reconcile(ServerLevel level, Settlement settlement,
                                 Building workplace, EquipmentRequest request) {
        boolean changed = request.reopenExpiredClaim(level.getGameTime());
        if (request.status() == EquipmentRequest.Status.DELIVERED
            && level.getGameTime() - request.deliveredAtTick()
                >= DELIVERED_RECHECK_TICKS
            && !WorkplaceStorage.hasMatching(level, workplace,
                request.requirement())) {
            request.reopen();
            changed = true;
        }
        // Reassignment/dismissal cancels stale rows even while the entity is
        // unloaded, because the employer roster is the employment authority.
        if (!workplace.workers.contains(request.requesterId())
            || workplace.id.equals(request.destinationBuildingId())
                && Employment.tradeOf(workplace.type) != request.profession()) {
            if (request.cancelPending()) {
                if (request.cancellationReadyForRemoval()) {
                    workplace.equipmentRequests.remove(request);
                    changed = true;
                }
            } else if (request.requestCancellation()) {
                changed = true;
            } else {
                workplace.equipmentRequests.remove(request);
                changed = true;
            }
        }
        if (changed) {
            synchronizeQueue(level, settlement);
            settlement.equipmentRequestQueue.noteRowChanged();
            SettlementManager.data(level).setDirty();
        }
    }

    private static boolean reconcileDelivered(ServerLevel level, Building workplace,
                                              EquipmentRequest request,
                                              ItemStack held) {
        boolean changed = request.reopenExpiredClaim(level.getGameTime());
        if (request.status() == EquipmentRequest.Status.DELIVERED
            && level.getGameTime() - request.deliveredAtTick()
                >= DELIVERED_RECHECK_TICKS
            && !request.requirement().serviceable(held)
            && !WorkplaceStorage.hasMatching(level, workplace,
                request.requirement())) {
            request.reopen();
            changed = true;
        }
        return changed;
    }

    /** Professions whose first complete physical-equipment vertical is live. */
    public static boolean supports(BuildingType type) {
        Profession profession = Employment.tradeOf(type);
        return profession == Profession.FARMER || profession == Profession.LUMBERER
            || profession == Profession.GUARD || profession == Profession.ARCHER
            || profession == Profession.HUNTER;
    }

    private static void scheduleRetry(UUID workerId, long now) {
        if (NEXT_WORKPLACE_RETRY.size() >= MAX_RETRY_ROWS) {
            NEXT_WORKPLACE_RETRY.entrySet().removeIf(row -> row.getValue() <= now);
        }
        Iterator<UUID> oldest = NEXT_WORKPLACE_RETRY.keySet().iterator();
        while (NEXT_WORKPLACE_RETRY.size() >= MAX_RETRY_ROWS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        NEXT_WORKPLACE_RETRY.put(workerId, now + WORKPLACE_RETRY_TICKS);
    }

    private static int firstEmptySlotExcept(SettlerEntity settler,
                                            int excludedSlot) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            if (slot != excludedSlot && settler.bag.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean validCourier(Settlement settlement,
                                        @Nullable SettlerEntity courier) {
        return settlement != null && courier != null && courier.isBound()
            && courier.settlement() == settlement
            && Employment.courierWorkplace(settlement, courier) != null;
    }

    private static boolean loadedExactSourceAfterPickup(
            ServerLevel level, EquipmentRequest request,
            RequestItemFingerprint fingerprint) {
        BlockPos pos = request.sourceContainer();
        if (pos == null || !level.hasChunkAt(pos)
            || !(level.getBlockEntity(pos) instanceof Container source)
            || request.sourceSlot() < 0
            || request.sourceSlot() >= source.getContainerSize()) {
            return false;
        }
        ItemStack after = source.getItem(request.sourceSlot());
        int remaining = fingerprint.matches(level.registryAccess(), after)
            ? after.getCount() : 0;
        return remaining <= request.sourceCountBefore() - 1;
    }

    private static int matchingCount(ServerLevel level, Container container,
                                     RequestItemFingerprint fingerprint) {
        return matchingCount(container, fingerprint, level.registryAccess());
    }

    private static int matchingCount(Container container,
                                     RequestItemFingerprint fingerprint,
                                     net.minecraft.core.HolderLookup.Provider registries) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (fingerprint.matches(registries, stack)) {
                total += stack.getCount();
                if (total > 64) {
                    return 65;
                }
            }
        }
        return total;
    }

    private static boolean hasRoomForExact(Container container,
                                           ItemStack incoming) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, incoming)) continue;
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.getCount() < Math.min(container.getMaxStackSize(),
                    existing.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private static int bagItemCount(SettlerEntity courier) {
        int total = 0;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            total += courier.bag.getItem(slot).getCount();
            if (total > 64) {
                return 65;
            }
        }
        return total;
    }

    @Nullable
    private static Building buildingById(Settlement settlement,
                                         @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building.id.equals(id)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found;
    }

    private static List<EquipmentRequest> rawList(Settlement settlement) {
        List<EquipmentRequest> requests = new ArrayList<>();
        for (Building building : settlement.buildings) {
            requests.addAll(building.equipmentRequests);
        }
        return requests;
    }

    private static boolean removeRequest(Settlement settlement,
                                         EquipmentRequest target) {
        for (Building building : settlement.buildings) {
            if (building.equipmentRequests.remove(target)) {
                return true;
            }
        }
        return false;
    }

    private static void synchronizeQueue(ServerLevel level,
                                         Settlement settlement) {
        if (settlement.equipmentRequestQueue.reconcile(rawList(settlement))) {
            SettlementManager.data(level).setDirty();
        }
    }

    @Nullable
    private static Settlement settlementContaining(ServerLevel level,
                                                    Building workplace) {
        for (Settlement settlement : SettlementManager.data(level)
                .settlements.values()) {
            for (Building registered : settlement.buildings) {
                if (registered == workplace || registered.id.equals(workplace.id)) {
                    return settlement;
                }
            }
        }
        return null;
    }

    private static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "none"
            : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private EquipmentRequests() {
    }
}

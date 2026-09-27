package com.hearthstead.settlement.warehouse;

import com.hearthstead.building.BuildingType;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.journey.JourneyEmblemProvenance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Physical Warehouse chest grouping for the first visible stockroom pass.
 *
 * <p>Only a real chest or barrel inside one valid Warehouse may receive an assignment.
 * Grouping is intentionally an insertion decision, never an inventory move:
 * existing mixed and non-resource chests are excluded until a separate,
 * physical migration route handles them. Item components are never read or
 * changed for classification, so quality and provenance travel unchanged.
 */
public final class WarehouseSorting {
    private static final String WAREHOUSE_ID = "HearthsteadWarehouseSortingWarehouse";
    private static final String GROUP = "HearthsteadWarehouseSortingGroup";
    /** Game time of the last assignment (absent on labels older than 26 Sep). */
    private static final String ASSIGNED_AT = "HearthsteadWarehouseSortingAt";
    /**
     * An EMPTY unit labelled for another group (or another warehouse id) may
     * be re-labelled once its label is at least this old. The age guard keeps
     * a Courier who reserved that empty unit a moment ago from losing it to
     * a second Courier on the way (insertAt refuses a group mismatch, so the
     * cost of a race is a wasted walk, never an item).
     */
    public static final long RECLAIM_AFTER_TICKS = 1200L;

    public enum Group {
        WOOD("Wood", Items.OAK_LOG),
        CROPS("Crops", Items.WHEAT),
        FOOD("Food", Items.BREAD),
        TOOLS("Tools", Items.IRON_AXE),
        BUILDING_MATERIALS("Building Materials", Items.COBBLESTONE),
        COINS("Coins", ModItems.GOLD_COIN.get()),
        OTHER("Other", Items.CHEST);

        private final String label;
        private final Item icon;

        Group(String label, Item icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() {
            return label;
        }

        public Item icon() {
            return icon;
        }
    }

    private WarehouseSorting() {
    }

    /**
     * Classifies resource identity only. A named, blessed, or provenance
     * stamped log is still Wood; the caller continues to use the complete
     * original stack when it performs the eventual physical insert.
     */
    public static Group groupOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)
            || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK)) {
            return Group.WOOD;
        }
        if (stack.is(Items.WHEAT) || stack.is(Items.CARROT)
            || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
            || stack.is(Items.MELON_SLICE) || stack.is(Items.PUMPKIN)
            || stack.is(Items.SUGAR_CANE) || stack.is(Items.WHEAT_SEEDS)
            || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SEEDS)
            || stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.TORCHFLOWER_SEEDS)
            || stack.is(Items.PITCHER_POD)) {
            return Group.CROPS;
        }
        if (stack.is(ModItems.GOLD_COIN.get())) {
            return Group.COINS;
        }
        if (stack.getFoodProperties(null) != null) {
            return Group.FOOD;
        }
        Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.DiggerItem
            || item instanceof net.minecraft.world.item.SwordItem
            || item instanceof net.minecraft.world.item.BowItem
            || item instanceof net.minecraft.world.item.CrossbowItem
            || item instanceof net.minecraft.world.item.ShieldItem
            || item instanceof net.minecraft.world.item.ArmorItem
            || item instanceof net.minecraft.world.item.FishingRodItem
            || item instanceof net.minecraft.world.item.ShearsItem) {
            return Group.TOOLS;
        }
        if (item instanceof net.minecraft.world.item.BlockItem) {
            return Group.BUILDING_MATERIALS;
        }
        // The registry is the catalogue.  Do not keep a hand-maintained
        // "everything else" list: newly registered vanilla or compatible
        // mod items become visible to a Warehouse immediately after load.
        return item != Items.AIR && BuiltInRegistries.ITEM.getKey(item) != null
            ? Group.OTHER : null;
    }

    /** A compact, live server-registry catalogue for a Warehouse list/export.
     * It deliberately reads the registry when asked, rather than shipping a
     * stale generated item list alongside a changing modpack. */
    public static List<CatalogEntry> registeredItemCatalog() {
        return BuiltInRegistries.ITEM.stream()
            .filter(item -> item != Items.AIR)
            .map(item -> new CatalogEntry(BuiltInRegistries.ITEM.getKey(item),
                groupOf(new ItemStack(item))))
            .filter(entry -> entry.group() != null)
            .sorted(Comparator.comparing(entry -> entry.itemId().toString()))
            .toList();
    }

    public record CatalogEntry(ResourceLocation itemId, Group group) {
    }

    /** Category visibility is broader than Courier authority.  Job emblems
     * carry a paid employment receipt; automatic storage movement must never
     * become an alternate route around its assignment/refund protocol. */
    public static boolean isCourierSortable(ItemStack stack) {
        return groupOf(stack) != null
            && !(stack.getItem() instanceof JobEmblemItem)
            && JourneyEmblemProvenance.read(stack).isEmpty();
    }

    /**
     * Returns the ordered legal physical drop-off blocks for one stack.
     * Assigned compatible storage comes first, then already-homogeneous
     * compatible storage, then unassigned empty storage. A full container is never
     * returned: overflow may claim only another empty or same-group chest.
     *
     * <p>A double chest is one assignment. Both halves are returned because
     * the existing Warehouse index exposes them as separate physical blocks;
     * both persistent records are written together so neither half can later
     * become the other resource group.
     */
    public static List<BlockPos> destinations(ServerLevel level,
                                               Building warehouse,
                                               ItemStack stack) {
        Plan plan = plan(level, warehouse, stack);
        Group wanted = groupOf(stack);
        if (wanted == Group.FOOD || wanted == Group.CROPS) {
            noteLifeSupport(level, warehouse, plan != null && plan.overflow());
        }
        if (plan == null) {
            return List.of();
        }
        if (plan.label()) {
            assign(level, plan.unit(), warehouse.id, wanted);
        }
        // Assignment reserves the physical destination only. The Courier
        // creates its fixed label at the actual bag-to-chest contact, so a
        // planned route can never make a frame appear before a delivery.
        return List.copyOf(plan.unit().positions());
    }

    /** The chosen unit, whether it gets a label, and whether it is food overflow. */
    private record Plan(ChestUnit unit, boolean label, boolean overflow) {
    }

    @javax.annotation.Nullable
    private static Plan plan(ServerLevel level, Building warehouse, ItemStack stack) {
        Group wanted = groupOf(stack);
        if (level == null || warehouse == null
            || warehouse.type != BuildingType.WAREHOUSE || wanted == null) {
            return null;
        }

        Set<BlockPos> indexed = new LinkedHashSet<>(
            WarehouseIndex.containers(level, warehouse));
        List<ChestUnit> assigned = new ArrayList<>();
        List<ChestUnit> homogeneous = new ArrayList<>();
        List<ChestUnit> empty = new ArrayList<>();
        List<ChestUnit> staleEmpty = new ArrayList<>();
        List<ChestUnit> lifeOverflow = new ArrayList<>();
        boolean lifeSupport = wanted == Group.FOOD || wanted == Group.CROPS;
        List<ChestUnit> units = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos position : indexed) {
            if (!visited.add(position)) {
                continue;
            }
            ChestUnit chest = chestUnit(level, position, indexed);
            if (chest == null) {
                continue;
            }
            visited.addAll(chest.positions());
            if (playerOwned(level, chest)) {
                continue; // the player's own renamed chest: never labelled or filled by a Courier
            }
            units.add(chest);
            Candidate candidate = candidate(level, warehouse.id, chest, wanted,
                stack);
            if (candidate == Candidate.ASSIGNED) {
                assigned.add(chest);
            } else if (candidate == Candidate.HOMOGENEOUS) {
                homogeneous.add(chest);
            } else if (candidate == Candidate.EMPTY) {
                empty.add(chest);
            } else if (candidate == Candidate.STALE_EMPTY) {
                staleEmpty.add(chest);
            } else if (lifeSupport && lifeOverflowUnit(level, warehouse.id, chest, stack)) {
                lifeOverflow.add(chest);
            }
        }

        // One destination means one real leg. Reuse the first assigned home
        // until it is full, then claim one
        // empty overflow unit. Do not reserve every empty chest after the
        // first log; those remain available for the other resource group.
        ChestUnit selected = !assigned.isEmpty() ? assigned.getFirst()
            : !homogeneous.isEmpty() ? homogeneous.getFirst()
                : !empty.isEmpty() ? empty.getFirst()
                    // Last resort: an emptied unit still carrying an old
                    // label. Without this, a warehouse whose every chest was
                    // once labelled (e.g. filled with cobble, then emptied)
                    // had no home for food at all and the village starved
                    // (economy soak, 26 Sep).
                    : !staleEmpty.isEmpty() ? staleEmpty.getFirst() : null;
        // A one-container starter Warehouse is a real general store, not a
        // falsely labelled category chest.  Keep it unassigned until there is
        // a second physical storage unit that can become a dedicated home.
        // This never opens an assigned, foreign, or personal/mixed chest.
        if (units.size() == 1 && wanted != Group.WOOD && wanted != Group.CROPS
                && unassignedGeneralStoreWithRoom(level, warehouse.id,
                    units.getFirst(), stack)) {
            return new Plan(units.getFirst(), false, false);
        }
        if (selected != null) {
            return new Plan(selected, true, false);
        }
        // Food and crops are life support: the Hearth is fed only from the
        // Warehouse. When no proper home exists they overflow into any unit
        // of this warehouse with room (owner-approved, 26 Sep), preferring
        // one that already holds that group, then an unlabelled one. No
        // label is written or changed, a freshly reserved empty unit is never
        // taken, and personal/job-receipt stock is never opened.
        if (!lifeOverflow.isEmpty()) {
            lifeOverflow.sort(Comparator
                .comparing((ChestUnit unit) -> !holdsGroup(level, unit, wanted))
                .thenComparing(unit -> assignedGroup(
                    level.getBlockEntity(unit.positions().getFirst())) != null));
            return new Plan(lifeOverflow.getFirst(), false, true);
        }
        return null;
    }

    /**
     * Insert gate for {@code WarehouseStorage.insertAt}: food or crops may
     * enter a unit labelled for another group only while this warehouse is
     * in food overflow, or when the unit already holds that group (an
     * overflow top-up, or a cancelled pickup returning to its own chest).
     */
    public static boolean lifeOverflowAllowed(ServerLevel level, Building warehouse,
                                              BlockPos pos, ItemStack stack) {
        Group wanted = groupOf(stack);
        if ((wanted != Group.FOOD && wanted != Group.CROPS) || warehouse == null
            || warehouse.type != BuildingType.WAREHOUSE || pos == null) {
            return false;
        }
        Set<BlockPos> indexed = new LinkedHashSet<>(
            WarehouseIndex.containers(level, warehouse));
        ChestUnit unit = chestUnit(level, pos, indexed);
        if (unit == null || !lifeOverflowUnit(level, warehouse.id, unit, stack)) {
            return false;
        }
        if (holdsGroup(level, unit, wanted)) {
            return true;
        }
        Plan plan = plan(level, warehouse, stack);
        return plan != null && plan.overflow();
    }

    /** Warehouses currently in food overflow (runtime; drives notice + status). */
    private static final Set<UUID> FOOD_OVERFLOW = new HashSet<>();
    /** One-time notices sent (GameTest evidence). */
    private static int overflowNotices;

    /** True while food/crops of this warehouse have no proper home. */
    public static boolean foodOverflowActive(UUID warehouseId) {
        return warehouseId != null && FOOD_OVERFLOW.contains(warehouseId);
    }

    public static int overflowNoticeCount() {
        return overflowNotices;
    }

    /**
     * Tracks the overflow episode. The notice goes out once when a warehouse
     * enters overflow, and may go out again only after it has recovered.
     */
    private static void noteLifeSupport(ServerLevel level, Building warehouse,
                                        boolean overflow) {
        if (warehouse == null || warehouse.id == null) {
            return;
        }
        if (!overflow) {
            FOOD_OVERFLOW.remove(warehouse.id);
            return;
        }
        if (!FOOD_OVERFLOW.add(warehouse.id)) {
            return;
        }
        overflowNotices++;
        com.hearthstead.Hearthstead.LOGGER.info(
            "HSQA_WAREHOUSE_FOOD_OVERFLOW warehouse={} plaque={}",
            warehouse.id, warehouse.plaquePos);
        net.minecraft.network.chat.Component notice =
            net.minecraft.network.chat.Component.translatable(
                "hearthstead.warehouse.food_overflow.notice")
                .withStyle(net.minecraft.ChatFormatting.GOLD);
        var settlement = settlementOf(level, warehouse);
        for (var player : level.players()) {
            boolean near = settlement != null
                ? player.blockPosition().distSqr(settlement.center)
                    <= (double) settlement.radius * settlement.radius
                : warehouse.plaquePos != null
                    && player.blockPosition().distSqr(warehouse.plaquePos) <= 128 * 128;
            if (near) {
                player.sendSystemMessage(notice);
            }
        }
    }

    @javax.annotation.Nullable
    private static com.hearthstead.settlement.Settlement settlementOf(ServerLevel level,
                                                                     Building warehouse) {
        for (var settlement : com.hearthstead.settlement.SettlementSavedData.get(level)
                .settlements.values()) {
            if (settlement.buildings.contains(warehouse)) {
                return settlement;
            }
        }
        return null;
    }

    /**
     * A unit food may overflow into: any unit of THIS warehouse (labelled or
     * not) with room, holding only Courier-sortable stock, and never an empty
     * unit whose label is fresh (another Courier's reservation).
     */
    private static boolean lifeOverflowUnit(ServerLevel level, UUID warehouseId,
                                            ChestUnit chest, ItemStack offered) {
        Assignment assignment = assignment(level, chest, warehouseId, groupOf(offered));
        if (assignment == Assignment.CONFLICTING || assignment == Assignment.FOREIGN
            || !hasRoom(level, chest, offered)) {
            return false;
        }
        boolean any = false;
        for (BlockPos position : chest.positions()) {
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                return false;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (stored.isEmpty()) {
                    continue;
                }
                any = true;
                if (!isCourierSortable(stored)) {
                    return false;
                }
            }
        }
        return any || assignment == Assignment.UNASSIGNED
            || labelOldEnough(level, chest);
    }

    /** A lone, unlabelled Warehouse container may hold ordinary stock until
     * a second physical unit exists for category separation.  It must contain
     * only Courier-sortable stacks, so job receipts and personal/mixed stock
     * are never adopted by the fallback. */
    private static boolean unassignedGeneralStoreWithRoom(ServerLevel level,
                                                           UUID warehouseId,
                                                           ChestUnit chest,
                                                           ItemStack offered) {
        if (assignment(level, chest, warehouseId, null) != Assignment.UNASSIGNED
                || !hasRoom(level, chest, offered)) {
            return false;
        }
        for (BlockPos position : chest.positions()) {
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                return false;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (!stored.isEmpty() && !isCourierSortable(stored)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------ Courier tidying ---

    /**
     * A container the player has made their own by renaming it (anvil custom
     * name). Couriers never tidy out of it, never sort into it and never
     * label it; the Work Scepter's EXCLUDED mark is the other way to say so.
     */
    public static boolean playerOwned(BlockEntity blockEntity) {
        return blockEntity instanceof net.minecraft.world.Nameable named && named.hasCustomName();
    }

    /**
     * Tidying may extract only from a complete, managed, non-personal unit.
     * Both halves count, including a partner renamed after a route was saved.
     * An unavailable or excluded partner makes the whole unit ineligible.
     */
    public static boolean mayTidySource(ServerLevel level, Building warehouse, BlockPos position) {
        if (level == null || warehouse == null || !warehouse.valid
            || warehouse.type != BuildingType.WAREHOUSE || position == null
            || !level.hasChunkAt(position)) {
            return false;
        }
        Set<BlockPos> indexed = new LinkedHashSet<>(WarehouseIndex.containers(level, warehouse));
        if (!indexed.contains(position)) {
            return false;
        }
        ChestUnit unit = chestUnit(level, position, indexed);
        return unit != null && !playerOwned(level, unit);
    }

    private static boolean playerOwned(ServerLevel level, ChestUnit chest) {
        for (BlockPos position : chest.positions()) {
            if (playerOwned(level.getBlockEntity(position))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the stack already sits in a unit of THIS warehouse labelled
     * for its own group. Such a stack is never "misplaced", only merged:
     * without this, two full-and-partial chests of one group traded stacks
     * back and forth for ever (destinations() names only one home at a time).
     */
    public static boolean correctlyPlaced(ServerLevel level, Building warehouse,
                                          BlockPos pos, ItemStack stack) {
        Group group = groupOf(stack);
        if (level == null || warehouse == null || pos == null || group == null) {
            return false;
        }
        Set<BlockPos> indexed = new LinkedHashSet<>(WarehouseIndex.containers(level, warehouse));
        ChestUnit unit = chestUnit(level, pos, indexed);
        return unit != null && assignment(level, unit, warehouse.id, group) == Assignment.MATCHING;
    }

    /**
     * Pure merge direction: a partial stack moves only toward the unit that
     * already holds MORE of the exact item (ties: the lower position key), so
     * merging concentrates and can never ping-pong.
     */
    public static boolean mergeInto(int sourceHeld, long sourceKey, int targetHeld, long targetKey) {
        return targetHeld > sourceHeld || targetHeld == sourceHeld && targetKey < sourceKey;
    }

    /**
     * Where a correctly placed PARTIAL stack should merge: another unit of
     * this warehouse labelled for the same group, not the player's own, that
     * already holds more of the exact item ({@link #mergeInto}) and has room
     * for the whole stack in one container. Null when it should stay.
     */
    @javax.annotation.Nullable
    public static BlockPos mergeTarget(ServerLevel level, Building warehouse,
                                       BlockPos source, ItemStack stack) {
        Group group = groupOf(stack);
        if (level == null || warehouse == null || source == null || group == null
            || stack.getCount() >= stack.getMaxStackSize()) {
            return null;
        }
        Set<BlockPos> indexed = new LinkedHashSet<>(WarehouseIndex.containers(level, warehouse));
        ChestUnit from = chestUnit(level, source, indexed);
        if (from == null) {
            return null;
        }
        int sourceHeld = held(level, from, stack);
        long sourceKey = from.positions().getFirst().asLong();
        BlockPos best = null;
        int bestHeld = -1;
        long bestKey = Long.MAX_VALUE;
        Set<BlockPos> visited = new HashSet<>(from.positions());
        for (BlockPos position : indexed) {
            if (!visited.add(position)) {
                continue;
            }
            ChestUnit unit = chestUnit(level, position, indexed);
            if (unit == null) {
                continue;
            }
            visited.addAll(unit.positions());
            if (playerOwned(level, unit)
                || assignment(level, unit, warehouse.id, group) != Assignment.MATCHING) {
                continue;
            }
            int targetHeld = held(level, unit, stack);
            long targetKey = unit.positions().getFirst().asLong();
            if (targetHeld <= 0 || !mergeInto(sourceHeld, sourceKey, targetHeld, targetKey)) {
                continue;
            }
            BlockPos room = roomForAll(level, unit, stack);
            if (room != null && (targetHeld > bestHeld
                    || targetHeld == bestHeld && targetKey < bestKey)) {
                best = room;
                bestHeld = targetHeld;
                bestKey = targetKey;
            }
        }
        return best;
    }

    private static int held(ServerLevel level, ChestUnit unit, ItemStack like) {
        int count = 0;
        for (BlockPos position : unit.positions()) {
            if (level.getBlockEntity(position) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stored = container.getItem(slot);
                    if (ItemStack.isSameItemSameComponents(stored, like)) {
                        count += stored.getCount();
                    }
                }
            }
        }
        return count;
    }

    /** A position of the unit whose own container can take the whole stack, or null. */
    @javax.annotation.Nullable
    private static BlockPos roomForAll(ServerLevel level, ChestUnit unit, ItemStack stack) {
        for (BlockPos position : unit.positions()) {
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                continue;
            }
            int room = 0;
            int max = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (stored.isEmpty()) {
                    room += max;
                } else if (ItemStack.isSameItemSameComponents(stored, stack)) {
                    room += Math.max(0, max - stored.getCount());
                }
            }
            if (room >= stack.getCount()) {
                return position;
            }
        }
        return null;
    }

    /** Visible-label seam: invalid or absent group data is deliberately unlabeled. */
    public static Group assignedGroup(BlockEntity blockEntity) {
        if (!(blockEntity instanceof ChestBlockEntity)
            && !(blockEntity instanceof BarrelBlockEntity)) {
            return null;
        }
        String value = blockEntity.getPersistentData().getString(GROUP);
        try {
            return Group.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Candidate candidate(ServerLevel level, UUID warehouseId,
                                       ChestUnit chest, Group wanted,
                                       ItemStack offered) {
        Assignment assignment = assignment(level, chest, warehouseId, wanted);
        Contents contents = contents(level, chest, wanted);
        if (assignment == Assignment.CONFLICTING) {
            return Candidate.NONE;
        }
        if (assignment == Assignment.FOREIGN
            || assignment == Assignment.WRONG_GROUP) {
            // Holding nothing, a label protects nothing: an old, empty unit
            // may be re-labelled for the group that has no home.
            return contents == Contents.EMPTY && hasRoom(level, chest, offered)
                && labelOldEnough(level, chest)
                ? Candidate.STALE_EMPTY : Candidate.NONE;
        }
        if (contents == Contents.MIXED_OR_OTHER) {
            return Candidate.NONE;
        }
        // Existing homogeneous stock is safe to label even when it is full.
        // It cannot be an equipment/personal chest because every stored stack
        // already proved the requested resource identity.
        if (assignment == Assignment.UNASSIGNED && contents == Contents.HOMOGENEOUS) {
            if (!hasRoom(level, chest, offered)) {
                assign(level, chest, warehouseId, wanted);
                return Candidate.NONE;
            }
            return Candidate.HOMOGENEOUS;
        }
        if (!hasRoom(level, chest, offered)) {
            return Candidate.NONE;
        }
        if (assignment == Assignment.MATCHING) {
            return Candidate.ASSIGNED;
        }
        return contents == Contents.EMPTY ? Candidate.EMPTY : Candidate.NONE;
    }

    private static ChestUnit chestUnit(ServerLevel level, BlockPos position,
                                       Set<BlockPos> indexed) {
        if (level.getBlockEntity(position) instanceof BarrelBlockEntity) {
            return new ChestUnit(List.of(position));
        }
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity)) {
            return null;
        }
        BlockState state = level.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock)
            || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return new ChestUnit(List.of(position));
        }
        BlockPos partner = position.relative(ChestBlock.getConnectedDirection(state));
        if (!indexed.contains(partner)
            || !(level.getBlockEntity(partner) instanceof ChestBlockEntity)) {
            return null;
        }
        BlockState partnerState = level.getBlockState(partner);
        if (!(partnerState.getBlock() instanceof ChestBlock)
            || partnerState.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return Long.compare(position.asLong(), partner.asLong()) <= 0
            ? new ChestUnit(List.of(position, partner))
            : new ChestUnit(List.of(partner, position));
    }

    private static Assignment assignment(ServerLevel level, ChestUnit chest,
                                         UUID intendedOwner, Group intendedGroup) {
        UUID owner = null;
        Group group = null;
        for (BlockPos position : chest.positions()) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof ChestBlockEntity)
                && !(blockEntity instanceof BarrelBlockEntity)) {
                return Assignment.CONFLICTING;
            }
            CompoundTag persistent = blockEntity.getPersistentData();
            boolean hasOwner = persistent.hasUUID(WAREHOUSE_ID);
            boolean hasGroup = persistent.contains(GROUP);
            if (hasOwner != hasGroup) {
                return Assignment.CONFLICTING;
            }
            if (!hasOwner) {
                continue;
            }
            Group found = assignedGroup(blockEntity);
            if (found == null) {
                return Assignment.CONFLICTING;
            }
            UUID foundOwner = persistent.getUUID(WAREHOUSE_ID);
            if (owner == null) {
                owner = foundOwner;
                group = found;
            } else if (!owner.equals(foundOwner) || group != found) {
                return Assignment.CONFLICTING;
            }
        }
        if (owner == null) {
            return Assignment.UNASSIGNED;
        }
        if (!owner.equals(intendedOwner)) {
            return Assignment.FOREIGN;
        }
        return group == intendedGroup ? Assignment.MATCHING
            : Assignment.WRONG_GROUP;
    }

    private static Contents contents(ServerLevel level, ChestUnit chest, Group wanted) {
        boolean any = false;
        for (BlockPos position : chest.positions()) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container)) {
                return Contents.MIXED_OR_OTHER;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (stored.isEmpty()) {
                    continue;
                }
                any = true;
                if (groupOf(stored) != wanted) {
                    return Contents.MIXED_OR_OTHER;
                }
            }
        }
        return any ? Contents.HOMOGENEOUS : Contents.EMPTY;
    }

    private static boolean hasRoom(ServerLevel level, ChestUnit chest,
                                   ItemStack offered) {
        for (BlockPos position : chest.positions()) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container)) {
                return false;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (stored.isEmpty()) {
                    return true;
                }
                if (ItemStack.isSameItemSameComponents(stored, offered)
                    && stored.getCount() < Math.min(container.getMaxStackSize(),
                        stored.getMaxStackSize())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assign(ServerLevel level, ChestUnit chest,
                               UUID warehouseId, Group group) {
        for (BlockPos position : chest.positions()) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof ChestBlockEntity)
                && !(blockEntity instanceof BarrelBlockEntity)) {
                continue;
            }
            CompoundTag persistent = blockEntity.getPersistentData();
            persistent.putUUID(WAREHOUSE_ID, warehouseId);
            persistent.putString(GROUP, group.name());
            persistent.putLong(ASSIGNED_AT, level.getGameTime());
            blockEntity.setChanged();
        }
    }

    private static boolean holdsGroup(ServerLevel level, ChestUnit chest, Group group) {
        for (BlockPos position : chest.positions()) {
            if (level.getBlockEntity(position) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    if (groupOf(container.getItem(slot)) == group) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Labels without a timestamp predate the guard and count as old. */
    private static boolean labelOldEnough(ServerLevel level, ChestUnit chest) {
        long now = level.getGameTime();
        for (BlockPos position : chest.positions()) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null) {
                return false;
            }
            CompoundTag persistent = blockEntity.getPersistentData();
            if (persistent.contains(ASSIGNED_AT)
                && Math.abs(now - persistent.getLong(ASSIGNED_AT)) < RECLAIM_AFTER_TICKS) {
                return false;
            }
        }
        return true;
    }

    private record ChestUnit(List<BlockPos> positions) {
    }

    private enum Candidate {
        NONE,
        ASSIGNED,
        HOMOGENEOUS,
        EMPTY,
        STALE_EMPTY
    }

    private enum Contents {
        EMPTY,
        HOMOGENEOUS,
        MIXED_OR_OTHER
    }

    private enum Assignment {
        UNASSIGNED,
        MATCHING,
        FOREIGN,
        WRONG_GROUP,
        CONFLICTING
    }

}

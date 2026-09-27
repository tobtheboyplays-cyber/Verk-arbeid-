package com.hearthstead.menu;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModMenus;
import com.hearthstead.settlement.gear.GearGate;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.request.RequestItemFingerprint;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative access to one settler's persisted bag.
 *
 * <p>The client constructor receives identity only. Its temporary
 * {@link SimpleContainer} is a render target for vanilla slot synchronization;
 * every accepted click is still executed against {@link SettlerEntity#bag} by
 * the server menu. This deliberately does not reuse the inspection snapshot,
 * whose bag rows are read-only presentation data.
 */
public final class SettlerInventoryMenu extends AbstractContainerMenu {
    public static final int SETTLER_SLOTS = SettlerEntity.BAG_SIZE;
    public static final int BAG_COLUMNS = 4;
    public static final int BAG_ROWS = 2;
    /**
     * Screen-space coordinates only. The menu still owns exactly the same
     * eight settler slots and thirty-six player slots; the wider field-kit
     * screen merely gives those real transactions a readable home instead of
     * pinning them into a generic 176px chest dialog.
     */
    public static final int BAG_X = 98;
    public static final int BAG_Y = 44;
    public static final int PLAYER_X = 96;
    public static final int PLAYER_Y = 132;
    /** Same live window as the settler sheet that opens this menu. */
    public static final double REACH_SQUARED =
        com.hearthstead.network.SettlerNetwork.SHEET_REACH_SQUARED;

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final Container settlerInventory;
    @Nullable
    private final SettlerEntity settler;
    private final int entityId;
    private final UUID settlerId;
    /** Runtime-only evidence count; never serialized or consulted by gameplay. */
    private int transferTelemetryEmissions;

    /** Client constructor used by {@link net.neoforged.neoforge.common.extensions.IMenuTypeExtension}. */
    public SettlerInventoryMenu(int containerId, Inventory playerInventory,
                                FriendlyByteBuf buffer) {
        this(containerId, playerInventory, readOpenData(playerInventory, buffer));
    }

    private SettlerInventoryMenu(int containerId, Inventory playerInventory,
                                 OpenData openData) {
        this(containerId, playerInventory, openData.settler,
            openData.settler == null ? new SimpleContainer(SETTLER_SLOTS)
                : openData.settler.bag,
            openData.entityId, openData.settlerId);
    }

    /** Server constructor; its slots point directly at the entity-owned bag. */
    public SettlerInventoryMenu(int containerId, Inventory playerInventory,
                                SettlerEntity settler) {
        this(containerId, playerInventory, settler, settler.bag,
            settler.getId(), settler.getUUID());
    }

    private SettlerInventoryMenu(int containerId, Inventory playerInventory,
                                 @Nullable SettlerEntity settler,
                                 Container container, int entityId,
                                 UUID settlerId) {
        super(ModMenus.SETTLER_INVENTORY.get(), containerId);
        checkContainerSize(container, SETTLER_SLOTS);
        this.settler = settler;
        this.settlerInventory = container;
        this.entityId = entityId;
        this.settlerId = settlerId == null ? NIL_UUID : settlerId;
        container.startOpen(playerInventory.player);

        for (int row = 0; row < BAG_ROWS; row++) {
            for (int column = 0; column < BAG_COLUMNS; column++) {
                addSlot(new Slot(container, column + row * BAG_COLUMNS,
                    BAG_X + column * 18, BAG_Y + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9,
                    PLAYER_X + column * 18, PLAYER_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column,
                PLAYER_X + column * 18, PLAYER_Y + 58));
        }
    }

    private static OpenData readOpenData(Inventory playerInventory,
                                         FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        UUID settlerId = buffer.readUUID();
        Entity entity = playerInventory.player.level().getEntity(entityId);
        SettlerEntity settler = entity instanceof SettlerEntity candidate
            && candidate.getUUID().equals(settlerId) ? candidate : null;
        return new OpenData(entityId, settlerId, settler);
    }

    public int entityId() {
        return entityId;
    }

    public UUID settlerId() {
        return settlerId;
    }

    @Nullable
    public SettlerEntity settler() {
        return settler;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            // The server remains authoritative. A client that receives the
            // menu before the tracked entity packet catches up may render its
            // synchronized temporary container instead of closing itself.
            return settler == null || settler.getId() == entityId
                && settler.getUUID().equals(settlerId);
        }
        return settler != null && settler.isAlive()
            && settler.getId() == entityId
            && settler.getUUID().equals(settlerId)
            && player.level() == settler.level()
            && player.distanceToSqr(settler) <= REACH_SQUARED;
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == settlerInventory && settler != null
            && !settler.level().isClientSide) {
            // A matching physical tool placed here should become usable on
            // this same server interaction, not after a polling delay.
            settler.reconcileEquipmentNeedNow();
        }
    }

    /**
     * Observes the entity-owned bag immediately around vanilla's authoritative
     * server click transaction. Packet slot ids, carried stacks and client
     * predictions are never trusted as evidence: only exact physical before
     * and after stacks from the real bag can author transfer telemetry.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType,
                        Player player) {
        BagSnapshot before = mayObserveAuthoritativeClick(player)
            ? BagSnapshot.capture(settlerInventory) : null;
        BagSnapshot gearBefore = settler != null && !player.level().isClientSide
            ? BagSnapshot.capture(settlerInventory) : null;
        super.clicked(slotId, button, clickType, player);
        if (before != null && mayObserveAuthoritativeClick(player)) {
            emitCommittedTransfers((ServerLevel) settler.level(), before,
                BagSnapshot.capture(settlerInventory));
        }
        if (gearBefore != null) {
            answerHandedGear(player, gearBefore);
        }
    }

    /**
     * Gear Tier hand-over. Pack armour the settler may wear goes on at once;
     * any newly handed gear above their clearance is refused out loud and
     * simply stays in the pack (nothing moves, nothing is deleted) until a
     * promotion or new settlement knowledge lets them take it.
     */
    private void answerHandedGear(Player player, BagSnapshot before) {
        if (settler == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Component refusal = null;
        for (int slot = 0; slot < SETTLER_SLOTS && refusal == null; slot++) {
            ItemStack now = settlerInventory.getItem(slot);
            ItemStack was = before.stack(slot);
            if (now.isEmpty() || (!was.isEmpty()
                && ItemStack.isSameItemSameComponents(was, now))) {
                continue;
            }
            if (GearGate.relevant(settler, now) && !GearGate.allows(settler, now)) {
                refusal = GearGate.refusal(settler, now);
            }
        }
        GearGate.equipArmourFromPack(settler);
        if (refusal != null) {
            serverPlayer.sendSystemMessage(refusal);
        }
    }

    private boolean mayObserveAuthoritativeClick(Player player) {
        return settler != null && settler.level() instanceof ServerLevel
            && player != null && !player.level().isClientSide
            && player.level() == settler.level() && stillValid(player)
            && settler.getSettlementId() != null;
    }

    /**
     * Emits one exact fingerprint delta per materially changed settler slot.
     * A vanilla swap can therefore produce two records (one physical stack in,
     * one out), while an invalid/no-op click produces none.
     */
    private void emitCommittedTransfers(ServerLevel level, BagSnapshot before,
                                        BagSnapshot after) {
        Settlement settlement = settler.settlement();
        if (settlement == null
            || !settlement.id.equals(settler.getSettlementId())
            || !Objects.equals(settlement.center, settler.getHearthPos())
            || settler.isTraveler()
            || !exactMember(settlement, settler.getUUID())) {
            return;
        }
        for (int slot = 0; slot < SETTLER_SLOTS; slot++) {
            ItemStack beforeStack = before.stack(slot);
            ItemStack afterStack = after.stack(slot);
            if (sameStackAndCount(beforeStack, afterStack)) {
                continue;
            }
            if (!beforeStack.isEmpty()) {
                emitFingerprintDelta(level, settlement, before, after, slot,
                    beforeStack);
            }
            if (!afterStack.isEmpty()
                && (beforeStack.isEmpty()
                    || !ItemStack.isSameItemSameComponents(beforeStack,
                        afterStack))) {
                emitFingerprintDelta(level, settlement, before, after, slot,
                    afterStack);
            }
        }
    }

    private void emitFingerprintDelta(ServerLevel level, Settlement settlement,
                                      BagSnapshot before, BagSnapshot after,
                                      int slot, ItemStack fingerprintStack) {
        int slotBefore = before.countInSlot(slot, fingerprintStack);
        int slotAfter = after.countInSlot(slot, fingerprintStack);
        if (slotBefore == slotAfter) {
            return;
        }
        try {
            RequestItemFingerprint fingerprint = RequestItemFingerprint.capture(
                level.registryAccess(), fingerprintStack, 1);
            String direction = slotAfter > slotBefore
                ? "player_to_settler" : "settler_to_player";
            if (AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.SETTLER_INVENTORY_TRANSFER_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "settler:" + settlerId + ":slot:" + slot, 0, 0,
                    before.matchingCount(fingerprintStack),
                    after.matchingCount(fingerprintStack),
                    "fp:" + fingerprint.digest(), slotBefore, slotAfter,
                    slotAfter - slotBefore, direction))) {
                transferTelemetryEmissions++;
            }
        } catch (RuntimeException invalidFingerprint) {
            // Telemetry is downstream of the already-committed vanilla slot
            // transaction and may never turn a valid inventory move into a
            // menu failure. Native evidence simply remains absent.
        }
    }

    private static boolean sameStackAndCount(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount()
            && (left.isEmpty() && right.isEmpty()
                || ItemStack.isSameItemSameComponents(left, right));
    }

    /** Duplicate/null roster rows are corrupt authority, never one member. */
    private static boolean exactMember(Settlement settlement, UUID id) {
        int matches = 0;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record == null || record.entityId == null) {
                return false;
            }
            if (record.entityId.equals(id) && ++matches > 1) {
                return false;
            }
        }
        return matches == 1;
    }

    int transferTelemetryCountForTest() {
        return transferTelemetryEmissions;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack source = slot.getItem();
        ItemStack original = source.copy();
        if (index < SETTLER_SLOTS) {
            if (!moveItemStackTo(source, SETTLER_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(source, 0, SETTLER_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (source.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (source.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, source);
        return original;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        settlerInventory.stopOpen(player);
        if (settler != null && !settler.level().isClientSide) {
            settler.reconcileEquipmentNeedNow();
            GearGate.equipArmourFromPack(settler);
        }
    }

    private record OpenData(int entityId, UUID settlerId,
                            @Nullable SettlerEntity settler) {
    }

    /** Immutable bounded copy of the eight authoritative settler slots. */
    private record BagSnapshot(ItemStack[] stacks) {
        private static BagSnapshot capture(Container container) {
            ItemStack[] stacks = new ItemStack[SETTLER_SLOTS];
            for (int slot = 0; slot < SETTLER_SLOTS; slot++) {
                stacks[slot] = container.getItem(slot).copy();
            }
            return new BagSnapshot(stacks);
        }

        private ItemStack stack(int slot) {
            return stacks[slot];
        }

        private int countInSlot(int slot, ItemStack fingerprint) {
            ItemStack stack = stacks[slot];
            return !stack.isEmpty()
                && ItemStack.isSameItemSameComponents(stack, fingerprint)
                ? stack.getCount() : 0;
        }

        private int matchingCount(ItemStack fingerprint) {
            int count = 0;
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()
                    && ItemStack.isSameItemSameComponents(stack, fingerprint)) {
                    count += stack.getCount();
                }
            }
            return count;
        }
    }
}

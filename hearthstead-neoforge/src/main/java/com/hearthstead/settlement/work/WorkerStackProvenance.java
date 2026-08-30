package com.hearthstead.settlement.work;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/**
 * Short-lived physical provenance carried by worker cargo and tool receipts.
 *
 * <p>Transit provenance exists only while an item moves through world, hand
 * and bag. An authorised workplace insert strips it from the inserted copy so
 * ordinary goods remain stack-compatible; the bounded SavedData receipt is
 * the permanent authority record. Unknown or malformed tags are never
 * accepted and are never silently rewritten into valid provenance.
 */
public final class WorkerStackProvenance {
    private static final String TRANSIT_ROOT = "HearthsteadWorkerTransitV1";
    private static final String TOOL_ROOT = "HearthsteadWorkerToolUseV1";
    private static final int DATA_VERSION = 1;

    public enum TransitKind {
        LUMBER_LOG,
        FARM_SEED_INPUT,
        FARM_CROP
    }

    public record Transit(UUID actionId, TransitKind kind,
                          UUID settlementId, UUID buildingId, UUID workerId,
                          ResourceLocation dimension, long sourcePos) {
    }

    public record ToolUse(UUID actionId, long operationPos,
                          ResourceLocation itemId, int damageBefore,
                          int damageAfter) {
    }

    public static boolean stampTransit(ItemStack stack, UUID actionId,
                                       TransitKind kind, UUID settlementId,
                                       UUID buildingId, UUID workerId,
                                       ResourceLocation dimension,
                                       long sourcePos) {
        if (stack == null || stack.isEmpty() || !valid(actionId)
            || kind == null || !valid(settlementId) || !valid(buildingId)
            || !valid(workerId) || dimension == null
            || hasTransitMarker(stack)) {
            return false;
        }
        CompoundTag authored = new CompoundTag();
        authored.putInt("DataVersion", DATA_VERSION);
        authored.putUUID("Action", actionId);
        authored.putString("Kind", kind.name());
        authored.putUUID("Settlement", settlementId);
        authored.putUUID("Building", buildingId);
        authored.putUUID("Worker", workerId);
        authored.putString("Dimension", dimension.toString());
        authored.putLong("Source", sourcePos);
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            root -> root.put(TRANSIT_ROOT, authored));
        return true;
    }

    public static Optional<Transit> readTransit(ItemStack stack) {
        CompoundTag tag = root(stack, TRANSIT_ROOT);
        if (tag == null || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.hasUUID("Action") || !tag.hasUUID("Settlement")
            || !tag.hasUUID("Building") || !tag.hasUUID("Worker")
            || !tag.contains("Kind", Tag.TAG_STRING)
            || !tag.contains("Dimension", Tag.TAG_STRING)
            || !tag.contains("Source", Tag.TAG_LONG)) {
            return Optional.empty();
        }
        try {
            UUID action = tag.getUUID("Action");
            UUID settlement = tag.getUUID("Settlement");
            UUID building = tag.getUUID("Building");
            UUID worker = tag.getUUID("Worker");
            TransitKind kind = TransitKind.valueOf(tag.getString("Kind"));
            ResourceLocation dimension = ResourceLocation.tryParse(
                tag.getString("Dimension"));
            if (!valid(action) || !valid(settlement) || !valid(building)
                || !valid(worker) || dimension == null) {
                return Optional.empty();
            }
            return Optional.of(new Transit(action, kind, settlement, building,
                worker, dimension, tag.getLong("Source")));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** True even for a malformed row, so it cannot be laundered as ordinary. */
    public static boolean hasTransitMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().contains(TRANSIT_ROOT);
    }

    /** Removes only Hearthstead's transit row and preserves unrelated data. */
    public static boolean clearTransit(ItemStack stack) {
        if (stack == null || stack.isEmpty() || readTransit(stack).isEmpty()) {
            return false;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            root -> root.remove(TRANSIT_ROOT));
        CustomData remaining = stack.get(DataComponents.CUSTOM_DATA);
        if (remaining != null && remaining.copyTag().isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        }
        return true;
    }

    public static boolean stampToolUse(ItemStack stack, UUID actionId,
                                       long operationPos,
                                       ResourceLocation itemId,
                                       int damageBefore, int damageAfter) {
        if (stack == null || stack.isEmpty() || !valid(actionId)
            || itemId == null || damageBefore < 0
            || damageAfter != damageBefore + 1 || hasToolUseMarker(stack)) {
            return false;
        }
        CompoundTag authored = new CompoundTag();
        authored.putInt("DataVersion", DATA_VERSION);
        authored.putUUID("Action", actionId);
        authored.putLong("Operation", operationPos);
        authored.putString("Item", itemId.toString());
        authored.putInt("DamageBefore", damageBefore);
        authored.putInt("DamageAfter", damageAfter);
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            root -> root.put(TOOL_ROOT, authored));
        return true;
    }

    public static Optional<ToolUse> readToolUse(ItemStack stack) {
        CompoundTag tag = root(stack, TOOL_ROOT);
        if (tag == null || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.hasUUID("Action") || !tag.contains("Operation", Tag.TAG_LONG)
            || !tag.contains("Item", Tag.TAG_STRING)
            || !tag.contains("DamageBefore", Tag.TAG_INT)
            || !tag.contains("DamageAfter", Tag.TAG_INT)) {
            return Optional.empty();
        }
        UUID action = tag.getUUID("Action");
        ResourceLocation item = ResourceLocation.tryParse(tag.getString("Item"));
        int before = tag.getInt("DamageBefore");
        int after = tag.getInt("DamageAfter");
        if (!valid(action) || item == null || before < 0 || after != before + 1) {
            return Optional.empty();
        }
        return Optional.of(new ToolUse(action, tag.getLong("Operation"), item,
            before, after));
    }

    /** True even for malformed data, preventing receipt ownership laundering. */
    public static boolean hasToolUseMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().contains(TOOL_ROOT);
    }

    /** Clears only the exact completed tool-use row, preserving foreign data. */
    public static boolean clearToolUse(ItemStack stack, UUID actionId,
                                       long operationPos) {
        ToolUse receipt = readToolUse(stack).orElse(null);
        if (receipt == null || !receipt.actionId().equals(actionId)
            || receipt.operationPos() != operationPos) {
            return false;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            root -> root.remove(TOOL_ROOT));
        CustomData remaining = stack.get(DataComponents.CUSTOM_DATA);
        if (remaining != null && remaining.copyTag().isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        }
        return true;
    }

    private static CompoundTag root(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return null;
        }
        CompoundTag all = custom.copyTag();
        return all.get(key) instanceof CompoundTag nested ? nested : null;
    }

    private static boolean valid(UUID id) {
        return id != null && (id.getMostSignificantBits() != 0L
            || id.getLeastSignificantBits() != 0L);
    }

    private WorkerStackProvenance() {
    }
}

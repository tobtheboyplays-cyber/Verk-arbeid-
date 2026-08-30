package com.hearthstead.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * The single durable owner of a crafted output between table contact and
 * storage contact. It is deliberately separate from {@link CraftPresentation}:
 * the latter may disappear when nobody can see the action; this object may
 * not disappear until the exact stack is committed to a real container.
 */
public final class CraftOutputEscrow {
    private final UUID actionId;
    private final UUID settlementId;
    private final UUID buildingId;
    private final BlockPos storageTarget;
    private final ItemStack output;

    public CraftOutputEscrow(UUID actionId, UUID settlementId, UUID buildingId,
                             BlockPos storageTarget, ItemStack output) {
        this.actionId = java.util.Objects.requireNonNull(actionId, "actionId");
        this.settlementId = java.util.Objects.requireNonNull(settlementId,
            "settlementId");
        this.buildingId = java.util.Objects.requireNonNull(buildingId,
            "buildingId");
        this.storageTarget = java.util.Objects.requireNonNull(storageTarget,
            "storageTarget").immutable();
        if (output == null || output.isEmpty() || output.getCount() != 1) {
            throw new IllegalArgumentException(
                "craft output escrow owns exactly one physical item");
        }
        this.output = output.copy();
    }

    public UUID actionId() {
        return actionId;
    }

    public UUID settlementId() {
        return settlementId;
    }

    public UUID buildingId() {
        return buildingId;
    }

    public BlockPos storageTarget() {
        return storageTarget;
    }

    public ItemStack output() {
        return output.copy();
    }

    public CraftOutputEscrow retarget(BlockPos target) {
        return new CraftOutputEscrow(actionId, settlementId, buildingId,
            target, output);
    }

    public boolean owns(UUID expectedAction, ItemStack expectedOutput) {
        return actionId.equals(expectedAction) && expectedOutput != null
            && expectedOutput.getCount() == output.getCount()
            && ItemStack.isSameItemSameComponents(output, expectedOutput);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Action", actionId);
        tag.putUUID("Settlement", settlementId);
        tag.putUUID("Building", buildingId);
        tag.put("Storage", NbtUtils.writeBlockPos(storageTarget));
        Tag encoded = output.copy().saveOptional(registries);
        tag.put("Output", encoded instanceof CompoundTag compound
            ? compound : new CompoundTag());
        return tag;
    }

    public static CraftOutputEscrow load(HolderLookup.Provider registries,
                                         CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Action")
            || !tag.hasUUID("Settlement") || !tag.hasUUID("Building")) {
            return null;
        }
        BlockPos target = NbtUtils.readBlockPos(tag, "Storage").orElse(null);
        ItemStack output = ItemStack.parseOptional(registries,
            tag.getCompound("Output"));
        if (target == null || output.isEmpty() || output.getCount() != 1) {
            return null;
        }
        try {
            return new CraftOutputEscrow(tag.getUUID("Action"),
                tag.getUUID("Settlement"), tag.getUUID("Building"), target,
                output);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}

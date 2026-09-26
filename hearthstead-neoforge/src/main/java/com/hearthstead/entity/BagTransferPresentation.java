package com.hearthstead.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Server-authored, read-only truth for one visible bag-to-container cycle.
 * Inventory remains in {@link SettlerEntity#bag}; this projection names the
 * exact stack bundle whose sole commit ticket is owned by {@code transferId}.
 */
public final class BagTransferPresentation {
    private static final BagTransferPresentation EMPTY = new BagTransferPresentation();

    @Nullable private final UUID transferId;
    @Nullable private final BlockPos bagAnchor;
    @Nullable private final BlockPos containerPos;
    private final float bagYaw;
    private final int clock;
    private final boolean committed;
    private final ItemStack item;
    private final boolean sourcePickup;

    private BagTransferPresentation() {
        transferId = null;
        bagAnchor = null;
        containerPos = null;
        bagYaw = 0.0F;
        clock = 0;
        committed = false;
        item = ItemStack.EMPTY;
        sourcePickup = false;
    }

    public BagTransferPresentation(UUID transferId, BlockPos bagAnchor,
                                   float bagYaw, BlockPos containerPos,
                                   int clock, boolean committed,
                                   ItemStack item) {
        this(transferId, bagAnchor, bagYaw, containerPos, clock, committed, item, false);
    }

    public BagTransferPresentation(UUID transferId, BlockPos bagAnchor,
                                   float bagYaw, BlockPos containerPos,
                                   int clock, boolean committed,
                                   ItemStack item, boolean sourcePickup) {
        this.sourcePickup = sourcePickup;
        this.transferId = java.util.Objects.requireNonNull(transferId);
        this.bagAnchor = java.util.Objects.requireNonNull(bagAnchor).immutable();
        this.containerPos = java.util.Objects.requireNonNull(containerPos).immutable();
        this.bagYaw = bagYaw;
        this.clock = Math.max(0, Math.min(80, clock));
        this.committed = committed;
        if (item == null || item.isEmpty()) {
            throw new IllegalArgumentException("bag transfer presentation requires a visible stack bundle");
        }
        this.item = item.copy();
    }

    public static BagTransferPresentation empty() { return EMPTY; }
    public boolean active() { return transferId != null && bagAnchor != null && containerPos != null && !item.isEmpty(); }
    @Nullable public UUID transferId() { return transferId; }
    @Nullable public BlockPos bagAnchor() { return bagAnchor; }
    @Nullable public BlockPos containerPos() { return containerPos; }
    public float bagYaw() { return bagYaw; }
    public int clock() { return clock; }
    public boolean committed() { return committed; }
    public ItemStack item() { return item.copy(); }
    public boolean sourcePickup() { return sourcePickup; }

    /** Grounded sack sits forward-left of the worker (reviewed Blender anchor), in blocks. */
    public static final double VISUAL_SACK_FORWARD = 0.28D;
    public static final double VISUAL_SACK_LEFT = 0.58D;

    /**
     * Presentation-only world point of the grounded sack's sole. The persisted
     * anchor is the block the worker stands on; drawing the sack at its centre
     * put the canvas through both legs. This offsets it forward-left relative
     * to the anchor-to-container heading, so it is world-fixed (never follows
     * body yaw) and matches the reviewed BAG_TO_CHEST_UNLOAD candidate, whose
     * left hand reaches forward-left into the sack mouth. Never inventory.
     */
    public net.minecraft.world.phys.Vec3 visualSackPoint() {
        var base = net.minecraft.world.phys.Vec3.atBottomCenterOf(bagAnchor);
        if (containerPos == null) return base;
        double fx = containerPos.getX() - bagAnchor.getX();
        double fz = containerPos.getZ() - bagAnchor.getZ();
        double length = Math.sqrt(fx * fx + fz * fz);
        if (length < 1.0E-4D) {
            double yaw = Math.toRadians(bagYaw);
            fx = -Math.sin(yaw);
            fz = Math.cos(yaw);
        } else {
            fx /= length;
            fz /= length;
        }
        // Minecraft left of a (fx, fz) heading is (fz, -fx).
        return base.add(fx * VISUAL_SACK_FORWARD + fz * VISUAL_SACK_LEFT, 0,
            fz * VISUAL_SACK_FORWARD - fx * VISUAL_SACK_LEFT);
    }

    /** Read-only preview from near chest face to fixed sack rim; never an inventory. */
    public net.minecraft.world.phys.Vec3 sourceUnitPosition(float sampleClock) {
        var sack = visualSackPoint().add(0, .72, 0);
        var centre = net.minecraft.world.phys.Vec3.atBottomCenterOf(containerPos).add(0, 1.05, 0);
        var toward = new net.minecraft.world.phys.Vec3(sack.x - centre.x, 0, sack.z - centre.z);
        double axis = Math.max(Math.abs(toward.x), Math.abs(toward.z));
        var face = axis > 0 ? centre.add(toward.scale(.48 / axis)) : centre;
        float t = net.minecraft.util.Mth.clamp((sampleClock - 30) / 18, 0, 1);
        t = t * t * (3 - 2 * t);
        return face.lerp(sack, t).add(0, Math.sin(Math.PI * t) * .08, 0);
    }

    /** A saved floor prop cannot seize the body while its owner flees or approaches. */
    public boolean ownsBodyPose(SettlerEntity actor) {
        if (!active() || actor.position().distanceToSqr(
                net.minecraft.world.phys.Vec3.atBottomCenterOf(bagAnchor)) > 4.0D) return false;
        if (actor.getActivity() == SettlerActivity.SORTING) {
            return bagAnchor.equals(actor.placedWorkContainerPos())
                || (actor.getProfession() == Profession.LUMBERER || actor.getProfession() == Profession.FARMER
                    || actor.getProfession() == Profession.FISHER
                    || (actor.getProfession() == Profession.COURIER || actor.getProfession() == Profession.TRADER))
                    && actor.placedWorkContainerPos() == null
                    && clock < com.hearthstead.entity.animation.BagToChestAnimationContract.BAG_WORLD_CONTACT_TICK;
        }
        return (actor.getProfession() == Profession.COURIER || actor.getProfession() == Profession.TRADER)
            && actor.getActivity() == SettlerActivity.CARRYING && clock <= 24;
    }

    public BagTransferPresentation advance(int nextClock, boolean nextCommitted) {
        if (!active()) throw new IllegalStateException("cannot advance empty transfer");
        return new BagTransferPresentation(transferId, bagAnchor, bagYaw,
            containerPos, nextClock, nextCommitted, item, sourcePickup);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (!active()) return tag;
        tag.putUUID("Transfer", transferId);
        tag.put("BagAnchor", NbtUtils.writeBlockPos(bagAnchor));
        tag.put("Container", NbtUtils.writeBlockPos(containerPos));
        tag.putFloat("BagYaw", bagYaw);
        tag.putInt("Clock", clock);
        tag.putBoolean("Committed", committed);
        tag.putBoolean("SourcePickup", sourcePickup);
        Tag encoded = item.saveOptional(registries);
        tag.put("Item", encoded instanceof CompoundTag compound ? compound : new CompoundTag());
        return tag;
    }

    public static BagTransferPresentation load(HolderLookup.Provider registries,
                                               CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Transfer")) return empty();
        if (tag.contains("SourcePickup") && (!tag.contains("SourcePickup", Tag.TAG_BYTE)
            || tag.getByte("SourcePickup") < 0 || tag.getByte("SourcePickup") > 1)) return empty();
        BlockPos bag = NbtUtils.readBlockPos(tag, "BagAnchor").orElse(null);
        BlockPos container = NbtUtils.readBlockPos(tag, "Container").orElse(null);
        ItemStack item = tag.contains("Item", Tag.TAG_COMPOUND)
            ? ItemStack.parseOptional(registries, tag.getCompound("Item")) : ItemStack.EMPTY;
        if (bag == null || container == null || item.isEmpty()) return empty();
        try {
            return new BagTransferPresentation(tag.getUUID("Transfer"), bag,
                tag.getFloat("BagYaw"), container, tag.getInt("Clock"),
                tag.getBoolean("Committed"), item, tag.getBoolean("SourcePickup"));
        } catch (RuntimeException malformed) {
            return empty();
        }
    }
}

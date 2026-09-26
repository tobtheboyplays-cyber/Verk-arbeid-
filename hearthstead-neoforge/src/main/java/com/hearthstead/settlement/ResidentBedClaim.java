package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Persistent display projection of a real resident claim; never a new housing authority. */
public record ResidentBedClaim(boolean known, BlockPos head) {
    public ResidentBedClaim {
        if (!known && head != null) throw new IllegalArgumentException("Unknown bed owner");
        if (head != null) head = head.immutable();
    }
    public static ResidentBedClaim unknown() { return new ResidentBedClaim(false, null); }
    public static ResidentBedClaim known(BlockPos head) { return new ResidentBedClaim(true, head); }
    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Version", (byte)1);
        tag.putBoolean("Known", known);
        if (head != null) tag.putLong("Head", head.asLong());
        return tag;
    }
    public static ResidentBedClaim readNbt(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
            || !tag.contains("Version", Tag.TAG_BYTE) || tag.getByte("Version") != 1
            || !tag.contains("Known", Tag.TAG_BYTE)
            || (tag.getByte("Known") != 0 && tag.getByte("Known") != 1)
            || (tag.contains("Head") && !tag.contains("Head", Tag.TAG_LONG))) return unknown();
        if (!tag.getBoolean("Known")) return unknown();
        return known(tag.contains("Head", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("Head")) : null);
    }
}

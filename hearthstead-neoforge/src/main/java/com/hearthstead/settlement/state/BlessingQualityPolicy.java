package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.UUID;

/** Persisted rules1: stable SplitMix64 finalizer, unsigned modulo100, Rare below10. */
public record BlessingQualityPolicy(long seed, int rulesVersion, int legacyThroughSerial) {
    public BlessingQualityPolicy {
        if (rulesVersion != 1 || legacyThroughSerial < 0
                || legacyThroughSerial > BlessingState.MAX_COUNTER) {
            throw new IllegalArgumentException("invalid Blessing quality policy");
        }
    }
    public static BlessingQualityPolicy fresh(int legacyThroughSerial) {
        return new BlessingQualityPolicy(UUID.randomUUID().getMostSignificantBits(), 1, legacyThroughSerial);
    }
    public BlessingQuality qualityFor(int serial, BlessingId blessing) {
        if (serial < 1 || serial > BlessingState.MAX_COUNTER || blessing == null) {
            throw new IllegalArgumentException("invalid Blessing quality identity");
        }
        if (serial <= legacyThroughSerial) return BlessingQuality.COMMON;
        long value = seed ^ (0x9E3779B97F4A7C15L * serial)
            ^ (0xD1B54A32D192ED03L * (blessing.wireId() + 1L));
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return Long.remainderUnsigned(value, 100L) < 10L ? BlessingQuality.RARE : BlessingQuality.COMMON;
    }
    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Seed", seed);
        tag.putInt("RulesVersion", rulesVersion);
        tag.putInt("LegacyThroughSerial", legacyThroughSerial);
        return tag;
    }
    public static BlessingQualityPolicy readNbt(CompoundTag tag, int earned) {
        if (!tag.contains("Seed", Tag.TAG_LONG) || !tag.contains("RulesVersion", Tag.TAG_INT)
                || !tag.contains("LegacyThroughSerial", Tag.TAG_INT)
                || tag.getInt("LegacyThroughSerial") > earned) {
            throw new IllegalArgumentException("malformed Blessing quality policy");
        }
        return new BlessingQualityPolicy(tag.getLong("Seed"), tag.getInt("RulesVersion"),
            tag.getInt("LegacyThroughSerial"));
    }
}

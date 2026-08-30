package com.hearthstead.settlement.journey;

import com.hearthstead.entity.Profession;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/** Bounded server-authored sale identity carried by one physical Job Emblem. */
public final class JourneyEmblemProvenance {
    private static final String ROOT = "HearthsteadJourneyEmblemV1";
    private static final int DATA_VERSION = 1;

    public record Provenance(UUID settlementId, UUID transactionId,
                             Profession profession) {
    }

    public static boolean stamp(ItemStack stack, UUID settlementId,
                                UUID transactionId, Profession profession) {
        if (stack == null || stack.isEmpty() || settlementId == null
            || transactionId == null || profession == null
            || isNil(settlementId) || isNil(transactionId)
            || profession == Profession.NONE) {
            return false;
        }
        CompoundTag authored = new CompoundTag();
        authored.putInt("DataVersion", DATA_VERSION);
        authored.putUUID("Settlement", settlementId);
        authored.putUUID("Transaction", transactionId);
        authored.putString("Profession", profession.key());
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            root -> root.put(ROOT, authored));
        return true;
    }

    public static Optional<Provenance> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return Optional.empty();
        }
        CompoundTag root = custom.copyTag();
        if (!(root.get(ROOT) instanceof CompoundTag tag)
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.hasUUID("Settlement") || !tag.hasUUID("Transaction")
            || !tag.contains("Profession", Tag.TAG_STRING)) {
            return Optional.empty();
        }
        Profession profession = Profession.NONE;
        for (Profession candidate : Profession.values()) {
            if (candidate.key().equals(tag.getString("Profession"))) {
                profession = candidate;
                break;
            }
        }
        if (profession == Profession.NONE) {
            return Optional.empty();
        }
        UUID settlementId = tag.getUUID("Settlement");
        UUID transactionId = tag.getUUID("Transaction");
        if (isNil(settlementId) || isNil(transactionId)) {
            return Optional.empty();
        }
        return Optional.of(new Provenance(settlementId, transactionId, profession));
    }

    private static boolean isNil(UUID value) {
        return value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L;
    }

    private JourneyEmblemProvenance() {
    }
}

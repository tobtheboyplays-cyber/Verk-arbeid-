package com.hearthstead.settlement.equipment;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * One physical tool requirement. The preferred item makes the request
 * concrete and readable; the optional tag lets a player satisfy it with a
 * compatible tier instead of demanding one magic item forever.
 */
public record EquipmentRequirement(Item preferredItem,
                                   @Nullable ResourceLocation acceptedTag,
                                   int minimumRemainingUses) {

    public EquipmentRequirement {
        if (preferredItem == null) {
            throw new IllegalArgumentException("preferredItem");
        }
        minimumRemainingUses = Math.max(1, minimumRemainingUses);
    }

    /** Accepts the preferred item or any member of the declared tool tag. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(preferredItem)) {
            return true;
        }
        return acceptedTag != null
            && stack.is(TagKey.create(Registries.ITEM, acceptedTag));
    }

    /**
     * A tool can match its trade but still be too close to breaking to start
     * another job. Non-damageable matching items are always serviceable.
     */
    public boolean serviceable(ItemStack stack) {
        if (!matches(stack)) {
            return false;
        }
        return !stack.isDamageableItem()
            || stack.getMaxDamage() - stack.getDamageValue() >= minimumRemainingUses;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("PreferredItem",
            BuiltInRegistries.ITEM.getKey(preferredItem).toString());
        if (acceptedTag != null) {
            tag.putString("AcceptedTag", acceptedTag.toString());
        }
        tag.putInt("MinimumRemainingUses", minimumRemainingUses);
        return tag;
    }

    @Nullable
    public static EquipmentRequirement readNbt(CompoundTag tag) {
        ResourceLocation itemId = ResourceLocation.tryParse(
            tag.getString("PreferredItem"));
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            return null;
        }
        ResourceLocation acceptedTag = null;
        if (tag.contains("AcceptedTag")) {
            acceptedTag = ResourceLocation.tryParse(tag.getString("AcceptedTag"));
            if (acceptedTag == null) {
                return null;
            }
        }
        return new EquipmentRequirement(BuiltInRegistries.ITEM.get(itemId),
            acceptedTag, Math.max(1, tag.getInt("MinimumRemainingUses")));
    }
}

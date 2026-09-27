package com.hearthstead.settlement.equipment;

import com.hearthstead.settlement.gear.GearTier;
import com.hearthstead.settlement.gear.GearTiers;
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
                                   int minimumRemainingUses,
                                   int maxGearTier) {

    public EquipmentRequirement {
        if (preferredItem == null) {
            throw new IllegalArgumentException("preferredItem");
        }
        minimumRemainingUses = Math.max(1, minimumRemainingUses);
        maxGearTier = Math.max(0, Math.min(GearTier.MAX, maxGearTier));
    }

    /** No Gear Tier cap: the profession-wide shape of the need. */
    public EquipmentRequirement(Item preferredItem,
                                @Nullable ResourceLocation acceptedTag,
                                int minimumRemainingUses) {
        this(preferredItem, acceptedTag, minimumRemainingUses, GearTier.MAX);
    }

    /**
     * The same need narrowed to what one settler may take ({@code GearGate}):
     * a Recruit's sword request is never filled with diamond.
     */
    public EquipmentRequirement withMaxGearTier(int tier) {
        int capped = Math.max(0, Math.min(GearTier.MAX, tier));
        return capped == maxGearTier ? this
            : new EquipmentRequirement(preferredItem, acceptedTag,
                minimumRemainingUses, capped);
    }

    /** Accepts the preferred item or any member of the declared tool tag,
     *  within the Gear Tier cap. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (maxGearTier < GearTier.MAX && GearTiers.tierOf(stack) > maxGearTier
            && com.hearthstead.settlement.gear.GearGate.enabled()) {
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

    /**
     * The same profession need (item, tag, minimum uses), ignoring the
     * per-settler Gear Tier cap. A stored request carries its requester's cap
     * ({@code GearGate.limit}), so record equality with the profession-wide
     * {@code EquipmentRequests.requirementFor} fails for every capped
     * settler (BH-24: the Arm the Watch Guard-delivery credit never counted).
     */
    public boolean sameNeed(@Nullable EquipmentRequirement other) {
        return other != null && preferredItem == other.preferredItem
            && java.util.Objects.equals(acceptedTag, other.acceptedTag)
            && minimumRemainingUses == other.minimumRemainingUses;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("PreferredItem",
            BuiltInRegistries.ITEM.getKey(preferredItem).toString());
        if (acceptedTag != null) {
            tag.putString("AcceptedTag", acceptedTag.toString());
        }
        tag.putInt("MinimumRemainingUses", minimumRemainingUses);
        if (maxGearTier < GearTier.MAX) {
            tag.putInt("MaxGearTier", maxGearTier);
        }
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
        int maxGearTier = tag.contains("MaxGearTier")
            ? tag.getInt("MaxGearTier") : GearTier.MAX;
        return new EquipmentRequirement(BuiltInRegistries.ITEM.get(itemId),
            acceptedTag, Math.max(1, tag.getInt("MinimumRemainingUses")),
            maxGearTier);
    }
}

package com.hearthstead.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The physical identity of one hunted carcass: which species it was, and the
 * exact vanilla death loot that the kill rolled.
 *
 * <p>The yield is captured from the real {@code LivingDropsEvent} at the
 * moment of death and the individual drops are removed from the world in the
 * same event, so the carcass is the ONLY authority for that loot -- carried,
 * dropped, stored or lying on a table, it is one item. Butchering converts it
 * back into exactly these stacks (plus an optional trade-level bonus that only
 * ever repeats an item the vanilla roll already produced).
 *
 * <p>Equality compares stacks by content, not identity, so a carcass survives
 * the component equality checks used by exact-unit bag/chest transfers after
 * a save/load or network round trip.
 */
public record CarcassData(ResourceLocation entityType, List<ItemStack> yield) {
    /** Hard bound on persisted rows: vanilla passives drop at most three stacks. */
    public static final int MAX_YIELD_STACKS = 8;

    public static final Codec<CarcassData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("entity_type").forGetter(CarcassData::entityType),
        ItemStack.CODEC.listOf().optionalFieldOf("yield", List.of()).forGetter(CarcassData::yield)
    ).apply(instance, CarcassData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CarcassData> STREAM_CODEC =
        StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, CarcassData::entityType,
            ItemStack.LIST_STREAM_CODEC, CarcassData::yield,
            CarcassData::new);

    public CarcassData {
        List<ItemStack> copy = new ArrayList<>();
        if (yield != null) {
            for (ItemStack stack : yield) {
                if (stack != null && !stack.isEmpty() && copy.size() < MAX_YIELD_STACKS) {
                    copy.add(stack.copy());
                }
            }
        }
        yield = List.copyOf(copy);
    }

    public static CarcassData of(EntityType<?> type, List<ItemStack> yield) {
        return new CarcassData(BuiltInRegistries.ENTITY_TYPE.getKey(type), yield);
    }

    public Optional<EntityType<?>> type() {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(entityType);
    }

    /** Fresh copies; callers may mutate the returned stacks freely. */
    public List<ItemStack> yieldCopies() {
        List<ItemStack> out = new ArrayList<>(yield.size());
        for (ItemStack stack : yield) {
            out.add(stack.copy());
        }
        return out;
    }

    public int yieldCount() {
        int n = 0;
        for (ItemStack stack : yield) {
            n += stack.getCount();
        }
        return n;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CarcassData data
            && entityType.equals(data.entityType)
            && ItemStack.listMatches(yield, data.yield);
    }

    @Override
    public int hashCode() {
        return 31 * entityType.hashCode() + ItemStack.hashStackList(yield);
    }
}

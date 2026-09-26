package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.item.JobEmblemItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/**
 * Physical Mayor emblems for the 15 extended trades (trades-unlock lane,
 * 26 Sep). Own register, like {@link RoleItems}, so this lane never rewrites
 * the shared {@link ModItems}. The items always exist (saves and chests stay
 * valid); whether the Mayor sells them is {@code [features] extendedTrades}.
 */
public final class TradeEmblemItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, Hearthstead.MODID);

    private static final Map<Profession, DeferredHolder<Item, JobEmblemItem>> BY_PROFESSION =
        new EnumMap<>(Profession.class);

    static {
        for (Profession profession : new Profession[] {
                Profession.MILLER, Profession.HERDER, Profession.BAKER,
                Profession.BUTCHER, Profession.MINER, Profession.CARPENTER,
                Profession.MASON, Profession.SMELTER, Profession.SMITH,
                Profession.TANNER, Profession.WEAVER, Profession.COOK,
                Profession.BREWER, Profession.ARMOURER, Profession.FLETCHER}) {
            BY_PROFESSION.put(profession, ITEMS.register(
                profession.key() + "_emblem",
                () -> new JobEmblemItem(profession,
                    new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON))));
        }
    }

    /** The emblem stack for an extended trade, or empty for any other job. */
    public static ItemStack stackFor(Profession profession) {
        DeferredHolder<Item, JobEmblemItem> holder = BY_PROFESSION.get(profession);
        return holder == null ? ItemStack.EMPTY : new ItemStack(holder.get());
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    private TradeEmblemItems() {
    }
}

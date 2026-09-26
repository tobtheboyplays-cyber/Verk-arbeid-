package com.hearthstead.event.worldevent;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Wandering peddler: an odd, colourful trader with a pack llama pulls up by
 * the Banner and sells a small curated table of rare goods for Coins. He
 * stays one (eligible) day. Only Coins buy here, and he buys nothing; the
 * shared Coin market (GoldCoinTrades) never touches him because his click is
 * handled first and never falls through to vanilla.
 */
final class PeddlerEvent implements WorldEventHandler {
    static final String ROLE_PEDDLER = "peddler";
    static final String ROLE_CART = "peddler_cart";

    /** The curated rare-goods table: item, count, price in Coins, how many sales. */
    record Ware(Item item, int count, int price, int uses) {}

    static final List<Ware> TABLE = List.of(
        new Ware(Items.NAME_TAG, 1, 6, 1),
        new Ware(Items.SADDLE, 1, 8, 1),
        new Ware(Items.ENDER_PEARL, 1, 4, 2),
        new Ware(Items.SPYGLASS, 1, 7, 1),
        new Ware(Items.GLOW_INK_SAC, 3, 3, 2),
        new Ware(Items.AMETHYST_SHARD, 4, 3, 2),
        new Ware(Items.HONEY_BOTTLE, 2, 2, 2),
        new Ware(Items.COCOA_BEANS, 4, 3, 1),
        new Ware(Items.MELON_SEEDS, 4, 2, 1),
        new Ware(Items.PUMPKIN_SEEDS, 4, 2, 1),
        new Ware(Items.LEAD, 2, 3, 1),
        new Ware(Items.GOLDEN_APPLE, 1, 10, 1),
        new Ware(Items.MUSIC_DISC_CAT, 1, 12, 1),
        new Ware(Items.SWEET_BERRIES, 8, 2, 2),
        new Ware(Items.LILY_OF_THE_VALLEY, 2, 1, 1));
    static final int WARES_PER_VISIT = 5;

    @Override
    public WorldEventType type() {
        return WorldEventType.PEDDLER;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return settlement.population() >= 1;
    }

    /** Deterministic pick of this visit's wares from the event id. */
    static List<Ware> waresFor(java.util.UUID eventId) {
        List<Ware> pool = new ArrayList<>(TABLE);
        SplittableRandom random = new SplittableRandom(eventId.getMostSignificantBits() ^ eventId.getLeastSignificantBits());
        List<Ware> out = new ArrayList<>();
        while (out.size() < WARES_PER_VISIT && !pool.isEmpty()) out.add(pool.remove(random.nextInt(pool.size())));
        return out;
    }

    static MerchantOffers offers(List<Ware> wares) {
        MerchantOffers offers = new MerchantOffers();
        for (Ware ware : wares) {
            offers.add(new MerchantOffer(new ItemCost(ModItems.GOLD_COIN.get(), ware.price()),
                new ItemStack(ware.item(), ware.count()), ware.uses(), 0, 0.0F));
        }
        return offers;
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, settlement.center, 5, 10, 1.0F, 2.0F, level.random);
        if (spot == null) return false;
        WanderingTrader peddler = EntityType.WANDERING_TRADER.create(level);
        if (peddler == null) return false;
        peddler.moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, level.random.nextFloat() * 360F, 0F);
        peddler.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
        peddler.setDespawnDelay(active.type.budgetTicks() * 2 + 2400);
        peddler.restrictTo(spot, 6);
        peddler.setPersistenceRequired();
        WorldEventDirector.tag(peddler, settlement, active, ROLE_PEDDLER);
        peddler.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", "Odd Peddler");
        WorldEventActors.markWaiting(peddler, Component.translatableWithFallback(
            "hearthstead.event.peddler.title", "rare goods for Coins"), true);
        // Replace vanilla's table before anyone can open it.
        MerchantOffers offers = peddler.getOffers();
        offers.clear();
        offers.addAll(offers(waresFor(active.id)));
        if (!level.addFreshEntity(peddler)) return false;
        active.state.putLong("Spot", spot.asLong());
        active.state.putBoolean("PeddlerSeen", true);
        net.minecraft.nbt.CompoundTag stock = new net.minecraft.nbt.CompoundTag();
        for (Ware ware : waresFor(active.id)) {
            stock.putInt(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ware.item()).toString(),
                ware.count() * ware.uses());
        }
        active.state.put("Stock", stock);
        WorldEventConversations.bind(peddler, settlement, WorldEventConversations.PEDDLER, "peddler",
            "conversation.hearthstead.title.peddler", java.util.Map.of());

        BlockPos cartSpot = WorldEventCreatures.nearFeet(level, spot.offset(2, 0, 1), 0.9F, 1.9F);
        if (cartSpot != null) {
            TraderLlama cart = EntityType.TRADER_LLAMA.create(level);
            if (cart != null) {
                cart.moveTo(cartSpot.getX() + .5, cartSpot.getY(), cartSpot.getZ() + .5, 0F, 0F);
                cart.finalizeSpawn(level, level.getCurrentDifficultyAt(cartSpot), MobSpawnType.EVENT, null);
                cart.setPersistenceRequired();
                cart.setCustomName(Component.translatableWithFallback("hearthstead.event.peddler.cart", "Peddler's pack llama"));
                WorldEventDirector.tag(cart, settlement, active, ROLE_CART);
                if (level.addFreshEntity(cart)) cart.setLeashedTo(peddler, true);
            }
        }
        level.playSound(null, spot, com.hearthstead.registry.ModSounds.EVENT_PEDDLER_BELLS.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.peddler.arrive",
                "An odd peddler has pulled up by the Banner with rare goods."),
            Component.translatableWithFallback("hearthstead.event.peddler.cta",
                "Right-click him to trade for Coins. He leaves tomorrow."), spot);
        return true;
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        if (actor instanceof WanderingTrader peddler) {
            // Never let vanilla (or anyone) restock him on reload.
            if (active.state.contains("Spot")) peddler.restrictTo(BlockPos.of(active.state.getLong("Spot")), 6);
        }
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Entity peddler = WorldEventActors.actor(level, active, ROLE_PEDDLER);
        if (peddler == null && active.state.getBoolean("PeddlerSeen")) {
            // He was loaded before and is gone now (killed or unloaded far away).
            if (active.state.getBoolean("PeddlerKilled")) {
                WorldEventDirector.finish(level, settlement, "peddler_killed", Component.translatableWithFallback(
                    "hearthstead.event.peddler.killed", "The peddler is dead. Word of it will travel the roads."));
            }
            return;
        }
        if (peddler != null) active.state.putBoolean("PeddlerSeen", true);
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                          Entity actor, net.minecraft.world.damagesource.DamageSource source) {
        if (ROLE_PEDDLER.equals(WorldEventDirector.tagRole(actor))) active.state.putBoolean("PeddlerKilled", true);
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        WorldEventDirector.finish(level, settlement, "left", Component.translatableWithFallback(
            "hearthstead.event.peddler.leave", "The odd peddler packs up his cart and rolls on down the road."));
    }

    @Override
    public boolean interact(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            ServerPlayer player, Entity actor) {
        if (!(actor instanceof WanderingTrader peddler) || !ROLE_PEDDLER.equals(WorldEventDirector.tagRole(actor))) {
            return false;
        }
        if (peddler.getTradingPlayer() != null && peddler.getTradingPlayer() != player) {
            player.displayClientMessage(Component.translatableWithFallback("hearthstead.event.peddler.busy",
                "The peddler is busy with another customer."), true);
            return true;
        }
        if (!active.state.getBoolean("Talked")) {
            active.state.putBoolean("Talked", true);
            WorldEventActors.markWaiting(peddler, Component.translatableWithFallback(
                "hearthstead.event.peddler.title", "rare goods for Coins"), false);
        }
        peddler.setTradingPlayer(player);
        peddler.openTradingScreen(player, peddler.getDisplayName(), 1);
        return true;
    }

    /** Someone talked to him: drop the waiting marker. */
    static void talked(ServerLevel level, Entity speaker) {
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, speaker);
        if (owner == null || owner.active().state.getBoolean("Talked")) return;
        owner.active().state.putBoolean("Talked", true);
        WorldEventActors.markWaiting(speaker, Component.translatableWithFallback(
            "hearthstead.event.peddler.title", "rare goods for Coins"), false);
    }

    /** The peddler's remaining vanilla-menu stock (chat-fallback path), as copies. */
    static List<ItemStack> stock(ServerLevel level, WorldEventSavedData.Active active) {
        List<ItemStack> out = new ArrayList<>();
        if (WorldEventActors.actor(level, active, ROLE_PEDDLER) instanceof WanderingTrader peddler) {
            for (MerchantOffer offer : peddler.getOffers()) {
                if (!offer.isOutOfStock()) out.add(offer.getResult().copy());
            }
        }
        return out;
    }
}

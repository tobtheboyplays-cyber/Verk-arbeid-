package com.hearthstead.settlement.development;

import com.hearthstead.network.PayloadSend;
import com.hearthstead.network.TechKnowledgePayload;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.techtree.TechRecipeGates;
import com.hearthstead.settlement.techtree.TechTreeConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each player's client knowledge (the learned nodes of the settlement
 * they craft for) and recipe book in step with the tech-gated crafting rule:
 * gated recipes are awarded when learned and withheld before.
 */
public final class TechKnowledgeSync {
    /** Player -> settlement id last synced for ("" = none). */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    private TechKnowledgeSync() {
    }

    public static void sync(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Settlement settlement = TechCraftGate.settlementFor(level, player);
        LAST.put(player.getUUID(), settlement == null ? "" : settlement.id.toString());
        List<String> learned = new ArrayList<>();
        if (settlement != null) {
            DevelopmentState state = Development.existing(level, settlement.id);
            if (state != null && !state.quarantined()) {
                learned.addAll(TechTree.learnedIds(state));
            }
        }
        PayloadSend.toPlayer(player, new TechKnowledgePayload(TechTreeConfig.gateCrafting(), learned));
        syncRecipeBook(player, settlement);
        refreshGrids(player);
    }

    /** Recompute open crafting results so a result cached under another context re-gates. */
    static void refreshGrids(ServerPlayer player) {
        player.inventoryMenu.slotsChanged(player.inventoryMenu.getCraftSlots());
        if (player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu table
            && table.slots.size() > 1) {
            table.slotsChanged(table.getSlot(1).container);
        }
    }

    /** Call every ~2 s per player: re-syncs after walking into another settlement. */
    public static void tick(ServerPlayer player) {
        Settlement settlement = TechCraftGate.settlementFor(player.serverLevel(), player);
        String now = settlement == null ? "" : settlement.id.toString();
        if (!Objects.equals(LAST.get(player.getUUID()), now)) {
            sync(player);
        } else if (player.tickCount % 400 < 40) {
            syncRecipeBook(player, settlement); // undo vanilla auto-unlocks of gated recipes
        }
    }

    /** After a learn in {@code settlement}: everyone crafting for it gets fresh knowledge. */
    public static void onChanged(ServerLevel level, Settlement settlement) {
        for (ServerPlayer player : level.players()) {
            if (settlement.id.toString().equals(LAST.get(player.getUUID()))) {
                sync(player);
            }
        }
    }

    public static void forget(UUID player) {
        LAST.remove(player);
    }

    static void syncRecipeBook(ServerPlayer player, @Nullable Settlement settlement) {
        ServerLevel level = player.serverLevel();
        List<RecipeHolder<?>> award = new ArrayList<>();
        List<RecipeHolder<?>> revoke = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            var output = holder.value().getResultItem(level.registryAccess()).getItem();
            List<String> nodes = TechRecipeGates.nodesFor(holder.id(), output);
            if (nodes.isEmpty()) {
                continue;
            }
            boolean ok = !TechTreeConfig.gateCrafting()
                || TechCraftGate.unlocked(level, settlement, holder.id(), nodes);
            boolean known = player.getRecipeBook().contains(holder);
            if (ok && !known) {
                award.add(holder);
            } else if (!ok && known) {
                revoke.add(holder);
            }
        }
        if (!award.isEmpty()) {
            player.awardRecipes(award);
        }
        if (!revoke.isEmpty()) {
            player.resetRecipes(revoke);
        }
    }
}

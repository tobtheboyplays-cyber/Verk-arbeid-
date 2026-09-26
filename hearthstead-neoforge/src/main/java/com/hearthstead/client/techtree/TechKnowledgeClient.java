package com.hearthstead.client.techtree;

import com.hearthstead.network.TechKnowledgePayload;
import com.hearthstead.settlement.techtree.TechRecipeGates;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Client cache of what the player's settlement has learned (filled by
 * {@link TechKnowledgePayload}). Read by the crafting padlock and the
 * handbook: {@link #isLearned}, {@link #locked}.
 */
public final class TechKnowledgeClient {
    private static volatile Set<String> learned = Set.of();
    private static volatile boolean gating = true;

    private TechKnowledgeClient() {
    }

    public static void accept(TechKnowledgePayload payload) {
        learned = Set.copyOf(payload.learned());
        gating = payload.gating();
    }

    public static boolean isLearned(String nodeId) {
        return learned.contains(nodeId);
    }

    public static boolean gatingOn() {
        return gating;
    }

    /** The node that still locks this recipe for the player, or empty when craftable. */
    public static Optional<String> locked(@Nullable ResourceLocation recipeId, @Nullable Item output) {
        if (!gating) {
            return Optional.empty();
        }
        List<String> nodes = TechRecipeGates.nodesFor(recipeId, output);
        for (String node : nodes) {
            if (learned.contains(node)) {
                return Optional.empty();
            }
        }
        return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.getFirst());
    }
}

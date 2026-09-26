package com.hearthstead.settlement.techtree;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import net.minecraft.network.chat.Component;

/**
 * What learning a node does. Registered per node id in
 * {@link EffectRegistry} by the branch lanes' effect classes
 * ({@code techtree/effects/*Effects}).
 *
 * <p>Most gameplay reads a node through {@code Development.has(level,
 * settlement, id)} or a {@link TechBonus}; the effect entry is the contract
 * that says so, feeds the screen's "what it unlocks" list, and is what the
 * Sunday gate test counts. A node with no registered effect shows as
 * "Planned" in game and cannot be learned: no fake promises.
 */
public sealed interface TechEffect {

    /** One line for the side panel. */
    Component describe();

    /**
     * Authoritative building-plan unlock. Once ANY node claims a building
     * type, only claimants unlock it (the legacy DevelopmentNode lists and
     * RoleUnlocks are ignored for that type), which is how a lane moves a
     * building onto a new node.
     */
    record UnlockBuilding(BuildingType type) implements TechEffect {
        @Override
        public Component describe() {
            return Component.translatableWithFallback("hearthstead.techtree.effect.building", "Plan: %s",
                Component.translatable("hearthstead.building." + type.id()));
        }
    }

    /** Authoritative Mayor emblem unlock; same claim rule as buildings. */
    record UnlockProfession(Profession profession) implements TechEffect {
        @Override
        public Component describe() {
            return Component.translatableWithFallback("hearthstead.techtree.effect.profession", "Emblem: %s",
                Component.translatable("item.hearthstead.job_emblem." + profession.key()));
        }
    }

    /** A number read through {@code TechTree.bonus(level, settlement, key)}. */
    record Bonus(TechBonus key, double amount) implements TechEffect {
        @Override
        public Component describe() {
            return key.describe(amount);
        }
    }

    /**
     * Behaviour that gameplay code reads with {@code Development.has(...,
     * id)}. {@code readBy} names the class/method that reads it, so a flag
     * can never be registered for code that does not exist.
     */
    record Flag(String readBy, Component line) implements TechEffect {
        @Override
        public Component describe() {
            return line;
        }
    }

    /** Runs once on the server when the node becomes learned. */
    record OnLearn(Component line, LearnHook hook) implements TechEffect {
        @Override
        public Component describe() {
            return line;
        }
    }

    /**
     * Effect already implemented by the pre-v3 catalogue (DevelopmentNode
     * buildings/professions, a PostRaidUpgrade read via hasUpgrade).
     * Registered automatically for legacy-backed nodes.
     */
    record Legacy(String source, Component line) implements TechEffect {
        @Override
        public Component describe() {
            return line;
        }
    }

    @FunctionalInterface
    interface LearnHook {
        void onLearned(net.minecraft.server.level.ServerLevel level,
                       com.hearthstead.settlement.Settlement settlement,
                       TechNodeDef node);
    }
}

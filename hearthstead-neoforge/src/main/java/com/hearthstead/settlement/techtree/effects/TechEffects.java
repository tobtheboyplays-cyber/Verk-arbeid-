package com.hearthstead.settlement.techtree.effects;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import net.minecraft.network.chat.Component;

/**
 * Fills the {@link EffectRegistry} once. The framework owns this file; each
 * branch lane owns exactly one {@code *Effects} class and never edits this
 * one or another lane's.
 */
public final class TechEffects {
    private TechEffects() {
    }

    public static void bootstrap(EffectRegistry registry) {
        // One lane's mistake (a typo'd node id) must never stop a world from
        // loading: log it loudly; TechTreeDataTest/the Sunday gate catch it.
        guarded("crown", () -> CrownEffects.register(registry));
        guarded("watch", () -> WatchEffects.register(registry));
        guarded("logistics", () -> LogisticsEffects.register(registry));
        guarded("commons", () -> CommonsEffects.register(registry));
        guarded("craft", () -> CraftEffects.register(registry));
        // Last, so a plan or emblem a lane moved onto a new node is no longer
        // listed under its old one.
        registerLegacy(registry);
    }

    private static final java.util.List<String> FAILURES = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Lanes whose register() threw (empty when every effect registered). */
    public static java.util.List<String> registrationFailures() {
        return java.util.List.copyOf(FAILURES);
    }

    private static void guarded(String branch, Runnable register) {
        try {
            register.run();
        } catch (RuntimeException failure) {
            FAILURES.add(branch + ": " + failure);
            com.hearthstead.Hearthstead.LOGGER.error("Tech tree {} effects failed to register", branch, failure);
            if (Boolean.getBoolean("hearthstead.techtree.strict")) {
                throw failure;
            }
        }
    }

    /**
     * Legacy-backed nodes already work through the old catalogues: a
     * DevelopmentNode's plans and emblems, or a PostRaidUpgrade read with
     * Development.hasUpgrade. Record that as a {@code Legacy} effect so the
     * screen can list it and the node counts as implemented.
     */
    private static void registerLegacy(EffectRegistry registry) {
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            if (def.legacyNode()) {
                DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
                if (node == null) {
                    continue;
                }
                EffectRegistry.NodeEffects fx = registry.node(def.id());
                for (BuildingType type : node.buildings()) {
                    if (registry.claimed(type)) {
                        continue;
                    }
                    fx.legacy("DevelopmentNode." + node.name() + ".buildings",
                        Component.translatableWithFallback("hearthstead.techtree.effect.building", "Plan: %s",
                            Component.translatable("hearthstead.building." + type.id())));
                }
                for (Profession profession : node.professions()) {
                    if (registry.claimed(profession)) {
                        continue;
                    }
                    fx.legacy("DevelopmentNode." + node.name() + ".professions",
                        Component.translatableWithFallback("hearthstead.techtree.effect.profession", "Emblem: %s",
                            Component.translatable("item.hearthstead.job_emblem." + profession.key())));
                }
            } else if (def.legacyUpgrade()) {
                PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
                if (upgrade != null) {
                    registry.node(def.id()).legacy("PostRaidUpgrade." + upgrade.name(),
                        upgrade.description());
                }
            }
        }
    }
}

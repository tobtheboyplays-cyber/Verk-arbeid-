package com.hearthstead.settlement.techtree;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.techtree.effects.TechEffects;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node id -> effects. Filled once, lazily, by {@link TechEffects#bootstrap}
 * which calls each branch lane's own {@code register(EffectRegistry)}; lanes
 * never edit a shared file to add an effect.
 *
 * <p>Usage in a branch effect class:
 * <pre>{@code
 * r.node("spearmen")
 *     .building(BuildingType.PIKE_YARD)
 *     .profession(Profession.SPEARMAN);
 * r.node("watchfires")
 *     .bonus(RAID_WARNING_TICKS, 1200)
 *     .flag("AlarmBell.radius", "The alarm bell is heard across the whole village");
 * }</pre>
 */
public final class EffectRegistry {
    private static volatile EffectRegistry instance;

    private final Map<String, List<TechEffect>> effects = new LinkedHashMap<>();
    private boolean frozen;

    EffectRegistry() {
    }

    public static EffectRegistry get() {
        EffectRegistry loaded = instance;
        if (loaded == null) {
            synchronized (EffectRegistry.class) {
                loaded = instance;
                if (loaded == null) {
                    loaded = new EffectRegistry();
                    TechEffects.bootstrap(loaded);
                    loaded.frozen = true;
                    instance = loaded;
                }
            }
        }
        return loaded;
    }

    /** Builder for one node's effects. Unknown ids fail fast. */
    public NodeEffects node(String id) {
        if (frozen) {
            throw new IllegalStateException("EffectRegistry is frozen; register in *Effects.register");
        }
        if (TechTreeData.get().node(id) == null) {
            throw new IllegalArgumentException("No tech node '" + id + "' in data/hearthstead/techtree");
        }
        return new NodeEffects(id);
    }

    public List<TechEffect> effects(String id) {
        List<TechEffect> list = effects.get(id);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** True when the node does something in game (any effect registered). */
    public boolean implemented(String id) {
        return !effects(id).isEmpty();
    }

    /** True when the node has an effect beyond the automatic legacy one. */
    public boolean hasNewEffect(String id) {
        for (TechEffect effect : effects(id)) {
            if (!(effect instanceof TechEffect.Legacy)) {
                return true;
            }
        }
        return false;
    }

    /** Nodes that claim this building plan (empty = legacy rules apply). */
    public List<String> buildingClaimants(BuildingType type) {
        List<String> out = new ArrayList<>();
        effects.forEach((id, list) -> {
            for (TechEffect e : list) {
                if (e instanceof TechEffect.UnlockBuilding b && b.type() == type) {
                    out.add(id);
                }
            }
        });
        return out;
    }

    /** Nodes that claim this emblem (empty = JobEmblemCatalog's unlock applies). */
    public List<String> professionClaimants(Profession profession) {
        List<String> out = new ArrayList<>();
        effects.forEach((id, list) -> {
            for (TechEffect e : list) {
                if (e instanceof TechEffect.UnlockProfession p && p.profession() == profession) {
                    out.add(id);
                }
            }
        });
        return out;
    }

    private final Map<String, Map<String, Double>> bonusCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Every (node id, amount) that feeds a bonus key (cached once frozen). */
    public Map<String, Double> bonusSources(TechBonus key) {
        if (!frozen) {
            return computeBonusSources(key);
        }
        return bonusCache.computeIfAbsent(key.id(), ignored -> computeBonusSources(key));
    }

    private Map<String, Double> computeBonusSources(TechBonus key) {
        Map<String, Double> out = new LinkedHashMap<>();
        effects.forEach((id, list) -> {
            for (TechEffect e : list) {
                if (e instanceof TechEffect.Bonus b && b.key().id().equals(key.id())) {
                    out.merge(id, b.amount(), Double::sum);
                }
            }
        });
        return out;
    }

    public Map<String, List<TechEffect>> all() {
        return Collections.unmodifiableMap(effects);
    }

    void add(String id, TechEffect effect) {
        effects.computeIfAbsent(id, ignored -> new ArrayList<>()).add(effect);
    }

    /** Fluent builder returned by {@link #node}. */
    public final class NodeEffects {
        private final String id;

        private NodeEffects(String id) {
            this.id = id;
        }

        public NodeEffects building(BuildingType type) {
            add(id, new TechEffect.UnlockBuilding(type));
            return this;
        }

        public NodeEffects profession(Profession profession) {
            add(id, new TechEffect.UnlockProfession(profession));
            return this;
        }

        public NodeEffects bonus(TechBonus key, double amount) {
            add(id, new TechEffect.Bonus(key, amount));
            return this;
        }

        /** Behaviour read by {@code readBy} through Development.has(..., id). */
        public NodeEffects flag(String readBy, String line) {
            add(id, new TechEffect.Flag(readBy,
                Component.translatableWithFallback(
                    "hearthstead.techtree.flag." + id + "." + (effects(id).size()), line)));
            return this;
        }

        public NodeEffects onLearn(String line, TechEffect.LearnHook hook) {
            add(id, new TechEffect.OnLearn(Component.literal(line), hook));
            return this;
        }

        public NodeEffects legacy(String source, Component line) {
            add(id, new TechEffect.Legacy(source, line));
            return this;
        }

        /**
         * Save migration when you MOVE knowledge onto this new node: a save
         * written before the v3 tree that owns any of {@code oldNodes} gets
         * this node for free, once, on load (e.g. builders_hut granted with
         * timber_rights). Not an effect: it never makes a node "implemented".
         */
        public NodeEffects grandfatheredBy(String... oldNodes) {
            for (String old : oldNodes) {
                if (TechTreeData.get().node(old) == null) {
                    throw new IllegalArgumentException(id + ": unknown grandfather node " + old);
                }
                grandfathers.computeIfAbsent(id, ignored -> new ArrayList<>()).add(old);
            }
            return this;
        }
    }

    private final Map<String, List<String>> grandfathers = new LinkedHashMap<>();

    /** New node id -> old node ids that grant it to a pre-v3 save. */
    public Map<String, List<String>> grandfathers() {
        return Collections.unmodifiableMap(grandfathers);
    }

    /** True when some node claims this building/profession (legacy lines skip it). */
    public boolean claimed(Object typeOrProfession) {
        for (List<TechEffect> list : effects.values()) {
            for (TechEffect e : list) {
                if (e instanceof TechEffect.UnlockBuilding b && b.type() == typeOrProfession
                    || e instanceof TechEffect.UnlockProfession p && p.profession() == typeOrProfession) {
                    return true;
                }
            }
        }
        return false;
    }
}

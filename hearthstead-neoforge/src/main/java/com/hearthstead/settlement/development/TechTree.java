package com.hearthstead.settlement.development;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.settlement.CoinTreasury;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechBonus;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechEffect;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeConfig;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server rules of the v3 tech tree (84 data-driven nodes, see
 * {@link TechTreeData}). One shared tree per settlement: every player who may
 * build at the Banner has the same rights (co-op).
 *
 * <p>Storage: a legacy-backed node is recorded in its old catalogue
 * ({@link DevelopmentNode} / {@link PostRaidUpgrade}) so every existing
 * effect read keeps working; a v3-only node is recorded by id in
 * {@link DevelopmentState#hasTech}. Learning checks, in order: already
 * learned or studying, implemented (has a registered effect), pick-one
 * excludes, requires (plus the legacy prerequisites a save validates on
 * load), gates, and the full price. Coins and goods are paid exactly once,
 * all-or-nothing, behind the Development revision fence. Town+ nodes then
 * study for {@code study_days} in-game days before they count as learned.
 *
 * <p>Gameplay reads: {@link #has}, {@link #bonus}. Forget/respec does not
 * exist (not in the design).
 */
public final class TechTree {

    public enum Status {
        LEARNED, STUDYING, READY, AVAILABLE, LOCKED, BLOCKED, PLANNED, QUARANTINED;

        public static Status byOrdinal(int ordinal) {
            Status[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : LOCKED;
        }
    }

    public enum Result {
        LEARNED, STUDY_STARTED, STALE, READ_ONLY, QUARANTINED, INVALID, ALREADY, STUDYING,
        PLANNED, EXCLUDED, LOCKED, GATE, MATERIALS, AUTO, DISABLED;

        public String key() {
            return "hearthstead.techtree.result." + name().toLowerCase(Locale.ROOT);
        }

        public boolean applied() {
            return this == LEARNED || this == STUDY_STARTED;
        }
    }

    /** One gate line: {@code kind} plus what it measures, progress/target. */
    public record GateProgress(String kind, String detail, int progress, int target) {
        public boolean met() {
            return progress >= target;
        }
    }

    /**
     * Why a node is in its state. {@code reason} is a lang key (empty when
     * READY/LEARNED), {@code reasonArg} a node id or objective the text names.
     */
    public record Assessment(Status status, String reason, String reasonArg,
                             List<GateProgress> gates) {
    }

    /** A gate kind a lane can add: progress for one gate spec. */
    @FunctionalInterface
    public interface GateEvaluator {
        int progress(ServerLevel level, Settlement settlement, DevelopmentState state,
                     TechNodeDef.Gate gate);
    }

    private static final Map<String, GateEvaluator> GATES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_DAY_TIME = new HashMap<>();
    /** Study time per in-game day of study, in day-time ticks. */
    public static final long DAY_TICKS = 24_000L;
    /** Bonus key: study speed in percent (Scholar, Library...). */
    public static final TechBonus STUDY_SPEED = TechBonus.percent("crown.study_speed",
        "Study %s%% faster");

    static {
        GATES.put("settlers", (level, s, state, gate) -> s.population());
        GATES.put("raids_won", (level, s, state, gate) -> s.raidLifecycle.raidsSurvived());
        GATES.put("first_raid", (level, s, state, gate) ->
            s.raidLifecycle.firstState() == FirstRaidState.COMPLETED ? 1 : 0);
        GATES.put("owns_any", (level, s, state, gate) -> {
            for (String id : gate.nodes()) {
                if (learned(state, id)) {
                    return gate.target();
                }
            }
            return 0;
        });
        GATES.put("branch_nodes", (level, s, state, gate) -> {
            // Branches (outside the Crown) holding >= count learned nodes of this tier.
            Map<String, Integer> perBranch = new HashMap<>();
            for (TechNodeDef def : TechTreeData.get().nodes()) {
                if (def.tier() == gate.tier() && !"crown".equals(def.branch())
                    && learned(state, def.id())) {
                    perBranch.merge(def.branch(), 1, Integer::sum);
                }
            }
            int branches = 0;
            for (int n : perBranch.values()) {
                if (n >= gate.count()) {
                    branches++;
                }
            }
            return branches;
        });
        GATES.put("objective", (level, s, state, gate) -> {
            DevelopmentObjective objective = objective(gate.objective());
            return objective == null ? 0
                : DevelopmentQuests.upgradeGateProgress(level, s, state, objective);
        });
    }

    private TechTree() {
    }

    /** Lanes may add a gate kind (data: {"kind":"my_kind", ...}). */
    public static void registerGate(String kind, GateEvaluator evaluator) {
        GATES.put(kind, evaluator);
    }

    public static boolean knownGateKind(String kind) {
        return GATES.containsKey(kind);
    }

    /** Test seam: swap one gate evaluator, returning the previous one. */
    static GateEvaluator swapGateForTest(String kind, GateEvaluator evaluator) {
        return evaluator == null ? GATES.remove(kind) : GATES.put(kind, evaluator);
    }

    // ------------------------------------------------------------ queries

    /**
     * Side-effect-free ownership read for gameplay (safe from AI ticks):
     * true once {@code id} is learned (not while studying), never on
     * quarantine. Accepts legacy spellings (see TechIdMigration).
     */
    public static boolean has(ServerLevel level, Settlement settlement, String id) {
        if (level == null || settlement == null || id == null) {
            return false;
        }
        DevelopmentState state = Development.get(level).existingState(settlement.id);
        return state != null && !state.quarantined() && learned(state, id);
    }

    /** Sum of a bonus over the settlement's learned nodes (0 when none). */
    public static double bonus(ServerLevel level, Settlement settlement, TechBonus key) {
        if (level == null || settlement == null || key == null) {
            return 0.0D;
        }
        DevelopmentState state = Development.get(level).existingState(settlement.id);
        if (state == null || state.quarantined()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (Map.Entry<String, Double> source : EffectRegistry.get().bonusSources(key).entrySet()) {
            if (learned(state, source.getKey())) {
                total += source.getValue();
            }
        }
        return total;
    }

    /** True when {@code id} is learned in this state (legacy or v3 storage). */
    public static boolean learned(DevelopmentState state, String id) {
        if (state == null || id == null) {
            return false;
        }
        TechNodeDef def = TechTreeData.get().node(id);
        if (def == null) {
            return state.hasTech(com.hearthstead.settlement.techtree.TechIdMigration.canonical(id));
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            return node != null && state.unlocked(node);
        }
        if (def.legacyUpgrade()) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
            return upgrade != null && state.hasUpgrade(upgrade);
        }
        return state.hasTech(def.id());
    }

    /** Learned or being studied: counts as "taken" for pick-one choices. */
    static boolean taken(DevelopmentState state, String id) {
        TechNodeDef def = TechTreeData.get().node(id);
        return learned(state, id) || (def != null && state.studying(def.id()));
    }

    /**
     * The pick-one partner already taken, or null. Also used by the legacy
     * purchase paths so the old screen cannot buy past a choice.
     */
    @Nullable
    static String excludedBy(DevelopmentState state, String id) {
        TechNodeDef def = TechTreeData.get().node(id);
        if (def == null) {
            return null;
        }
        for (String other : def.excludes()) {
            if (taken(state, other)) {
                return other;
            }
        }
        return null;
    }

    public static boolean implemented(TechNodeDef def) {
        if (!EffectRegistry.get().implemented(def.id())) {
            return false;
        }
        // A node whose claimed emblem is switched off ([features]
        // extendedTrades, ...) promises nothing it can deliver: Planned.
        for (TechEffect effect : EffectRegistry.get().effects(def.id())) {
            if (effect instanceof TechEffect.UnlockProfession p
                && JobEmblemCatalog.forProfession(p.profession()) == null) {
                return false;
            }
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            return node != null && node.implemented();
        }
        return true;
    }

    // ---------------------------------------------------------- assessment

    public static Assessment assess(ServerLevel level, Settlement settlement,
                                    @Nullable HearthBlockEntity hearth, String id,
                                    @Nullable ServerPlayer actor) {
        return assess(level, settlement, Development.of(level, settlement), id,
            hearth == null ? null : Treasury.of(CoinTreasury.open(level, settlement, hearth, actor)));
    }

    static Assessment assess(ServerLevel level, Settlement settlement, DevelopmentState state,
                             String id, @Nullable Treasury treasury) {
        if (state.quarantined()) {
            return new Assessment(Status.QUARANTINED, Result.QUARANTINED.key(), "", List.of());
        }
        TechNodeDef def = TechTreeData.get().node(id);
        if (def == null) {
            return new Assessment(Status.LOCKED, Result.INVALID.key(), id, List.of());
        }
        if (learned(state, def.id())) {
            return new Assessment(Status.LEARNED, "", "", List.of());
        }
        if (state.studying(def.id())) {
            return new Assessment(Status.STUDYING, Result.STUDYING.key(), "", List.of());
        }
        if (!implemented(def)) {
            return new Assessment(Status.PLANNED, Result.PLANNED.key(), "", List.of());
        }
        String excluded = excludedBy(state, def.id());
        if (excluded != null) {
            return new Assessment(Status.BLOCKED, Result.EXCLUDED.key(), excluded, List.of());
        }
        String missing = missingRequirement(state, def);
        if (missing != null) {
            return new Assessment(Status.LOCKED, Result.LOCKED.key(), missing, List.of());
        }
        List<GateProgress> gates = gates(level, settlement, state, def);
        boolean gatesMet = legacyGatesMet(level, settlement, state, def);
        for (GateProgress gate : gates) {
            gatesMet &= gate.met();
        }
        if (def.auto()) {
            return new Assessment(Status.AVAILABLE, Result.AUTO.key(), "", gates);
        }
        if (!gatesMet) {
            return new Assessment(Status.AVAILABLE, Result.GATE.key(), "", gates);
        }
        if (treasury == null || !treasury.canPay(TechCosts.costs(def))) {
            return new Assessment(Status.AVAILABLE, Result.MATERIALS.key(), "", gates);
        }
        return new Assessment(Status.READY, "", "", gates);
    }

    /** First unmet requirement (data requires, then legacy load prerequisites). */
    @Nullable
    static String missingRequirement(DevelopmentState state, TechNodeDef def) {
        for (String required : def.requires()) {
            if (!learned(state, required)) {
                return required;
            }
        }
        // A legacy entry's own prerequisites are re-validated when a save
        // loads; never record past them or the whole state quarantines.
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                for (String prerequisite : node.prerequisites()) {
                    DevelopmentNode required = DevelopmentNode.byId(prerequisite);
                    if (required == null || !state.unlocked(required)) {
                        return prerequisite;
                    }
                }
            }
        } else if (def.legacyUpgrade()) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
            if (upgrade != null) {
                if (!state.unlocked(upgrade.requires())) {
                    return upgrade.requires().id();
                }
                PostRaidUpgrade chain = upgrade.requiresUpgrade();
                if (chain != null && !state.hasUpgrade(chain)) {
                    return chain.id();
                }
            }
        }
        return null;
    }

    static List<GateProgress> gates(ServerLevel level, Settlement settlement,
                                    DevelopmentState state, TechNodeDef def) {
        List<GateProgress> out = new ArrayList<>();
        if (def.legacyNode()) {
            // Legacy quests measure from per-node baselines; show exactly that.
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                HearthBlockEntity hearth = level.getBlockEntity(settlement.center)
                    instanceof HearthBlockEntity h ? h : null;
                for (DevelopmentQuests.Progress p
                        : DevelopmentQuests.progress(level, settlement, hearth, state, node)) {
                    out.add(new GateProgress("objective", p.objective().id(),
                        Math.min(p.progress(), p.target()), Math.max(1, p.target())));
                }
            }
            return out;
        }
        for (TechNodeDef.Gate gate : def.gates()) {
            GateEvaluator evaluator = GATES.get(gate.kind());
            int target = Math.max(1, "branch_nodes".equals(gate.kind()) ? gate.branches() : gate.target());
            int progress = evaluator == null ? 0
                : Math.max(0, evaluator.progress(level, settlement, state, gate));
            String detail = gate.objective() != null ? gate.objective()
                : !gate.nodes().isEmpty() ? String.join(",", gate.nodes())
                : "branch_nodes".equals(gate.kind()) ? gate.tier() + ":" + gate.count() : "";
            out.add(new GateProgress(gate.kind(), detail, Math.min(progress, target), target));
        }
        return out;
    }

    /**
     * Legacy quests keep their exact old semantics (per-node baselines for
     * DevelopmentNode quests, lifetime counters for upgrade gates).
     */
    static boolean legacyGatesMet(ServerLevel level, Settlement settlement,
                                  DevelopmentState state, TechNodeDef def) {
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node == null) {
                return false;
            }
            if (node == DevelopmentNode.FIRST_RAID_AFTERMATH
                && settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED) {
                return false;
            }
            HearthBlockEntity hearth = level.getBlockEntity(settlement.center)
                instanceof HearthBlockEntity h ? h : null;
            return DevelopmentQuests.complete(level, settlement, hearth, state, node);
        }
        if (def.legacyUpgrade()) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
            return upgrade != null && (upgrade.gateObjective() == null
                || DevelopmentQuests.upgradeGateProgress(level, settlement, state,
                    upgrade.gateObjective()) >= upgrade.gateTarget());
        }
        return true;
    }

    // --------------------------------------------------------------- learn

    /**
     * Pays for and learns (or starts studying) one node. Exactly-once: a
     * stale revision, a refusal or a failed extraction pays nothing; a
     * success advances the revision so a replay is STALE.
     */
    public static Result learn(ServerLevel level, Settlement settlement, HearthBlockEntity hearth,
                               String id, int expectedRevision, @Nullable ServerPlayer actor) {
        DevelopmentState state = Development.of(level, settlement);
        int before = state.revision();
        TechNodeDef def = TechTreeData.get().node(id);
        String target = "techtree:" + (def == null ? "invalid" : def.id());
        Result refusal = null;
        if (!TechTreeConfig.enabled()) {
            refusal = Result.DISABLED;
        } else if (actor != null && (!actor.isAlive() || actor.isSpectator() || !actor.mayBuild())) {
            refusal = Result.READ_ONLY;
        } else if (state.revision() != expectedRevision) {
            refusal = Result.STALE;
        } else if (hearth == null || def == null) {
            refusal = Result.INVALID;
        }
        Treasury treasury = null;
        if (refusal == null) {
            treasury = Treasury.of(CoinTreasury.open(level, settlement, hearth, actor));
            Assessment assessment = assess(level, settlement, state, def.id(), treasury);
            refusal = switch (assessment.status()) {
                case READY -> null;
                case LEARNED -> Result.ALREADY;
                case STUDYING -> Result.STUDYING;
                case PLANNED -> Result.PLANNED;
                case BLOCKED -> Result.EXCLUDED;
                case LOCKED -> Result.LOCKED;
                case QUARANTINED -> Result.QUARANTINED;
                case AVAILABLE -> Result.AUTO.key().equals(assessment.reason()) ? Result.AUTO
                    : Result.MATERIALS.key().equals(assessment.reason()) ? Result.MATERIALS
                    : Result.GATE;
            };
        }
        if (refusal != null) {
            AuthorityTelemetry.emit(level, AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(settlement.id, target, before, before, 0, 0,
                    refusal.name().toLowerCase(Locale.ROOT)));
            return refusal;
        }
        List<DevelopmentNode.Cost> costs = TechCosts.costs(def);
        ItemStackHandler handler = treasury.handler;
        int expectedConsumed = costs.stream().mapToInt(DevelopmentNode.Cost::count).sum();
        Development.pay(handler, costs);
        long studyTicks = studyTicks(def);
        Result result;
        if (studyTicks > 0) {
            state.startStudy(def.id(), studyTicks);
            result = Result.STUDY_STARTED;
        } else {
            record(level, state, def);
            result = Result.LEARNED;
        }
        state.commit();
        Development.get(level).setDirty();
        AuthorityTelemetry.emit(level, AuthorityTelemetry.Event.DEVELOPMENT_NODE_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id, target, before, state.revision(), 0, 1,
                costs.isEmpty() ? "free" : "mixed_material_cost", expectedConsumed, 0,
                -expectedConsumed, result == Result.LEARNED ? "tech_learned" : "tech_study_started"));
        if (result == Result.LEARNED) {
            afterLearned(level, settlement, state, def, actor);
        }
        com.hearthstead.network.TechTreeNetwork.broadcast(level, settlement);
        return result;
    }

    static long studyTicks(TechNodeDef def) {
        if (def.studyDays() <= 0) {
            return 0L;
        }
        double scale = TechTreeConfig.studyTimeScale();
        return scale <= 0.0D ? 0L : Math.max(1L, Math.round(def.studyDays() * DAY_TICKS * scale));
    }

    /** Writes the learned mark into the right catalogue. */
    static void record(ServerLevel level, DevelopmentState state, TechNodeDef def) {
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node == null) {
                return;
            }
            if (node.doctrine()) {
                state.learnDoctrine(node, level.getGameTime());
            } else {
                state.unlock(node);
            }
        } else if (def.legacyUpgrade()) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
            if (upgrade != null) {
                state.unlockUpgrade(upgrade);
            }
        } else {
            state.learnTech(def.id());
        }
        DevelopmentQuests.ensureEligibleBaselines(state);
    }

    /** Learn hooks, journey + telemetry for newly learned nodes. */
    static void afterLearned(ServerLevel level, Settlement settlement, DevelopmentState state,
                             TechNodeDef def, @Nullable ServerPlayer actor) {
        com.hearthstead.fx.FxHooks.techLearned(level, settlement);
        for (TechEffect effect : EffectRegistry.get().effects(def.id())) {
            if (effect instanceof TechEffect.OnLearn hook) {
                try {
                    hook.hook().onLearned(level, settlement, def);
                } catch (RuntimeException failure) {
                    com.hearthstead.Hearthstead.LOGGER.error("Tech node {} learn hook failed",
                        def.id(), failure);
                }
            }
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null && !node.buildings().isEmpty()) {
                AuthorityTelemetry.emit(level, AuthorityTelemetry.Event.PLAN_UNLOCK_COMMITTED,
                    AuthorityTelemetry.Result.COMMITTED,
                    AuthorityTelemetry.Fields.state(settlement.id, "plans:" + node.id(),
                        state.revision() - 1, state.revision(), 0, node.buildings().size(),
                        "settlement_knowledge"));
            }
            if (node != null && actor != null) {
                JourneyServerHooks.noteTechUnlocked(actor, settlement, node, state.revision());
            }
        }
    }

    // ---------------------------------------------------------------- tick

    /**
     * Once a second: advance study clocks (only while a player is in the
     * level; sleeping skips ahead like any in-game day) and stamp {@code auto}
     * nodes (the Village Charter) whose requires and gates are met.
     */
    public static void tick(ServerLevel level) {
        if (!TechTreeConfig.enabled()) {
            LAST_DAY_TIME.remove(level.dimension());
            return;
        }
        // The shared GameTest server keeps other suites' raids and saves
        // untouched: tests drive studies and stamps through the test seams.
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) {
            return;
        }
        long now = level.getDayTime();
        Long last = LAST_DAY_TIME.put(level.dimension(), now);
        long delta = last == null || now < last ? 0L : Math.min(now - last, 13_000L);
        if (level.players().isEmpty()) {
            delta = 0L;
        }
        Development data = Development.get(level);
        for (Settlement settlement : SettlementSavedData.get(level).settlements.values()) {
            DevelopmentState state = data.existingState(settlement.id);
            if (state == null || state.quarantined()) {
                continue;
            }
            boolean changed = advanceStudies(level, settlement, state, delta);
            changed |= stampAuto(level, settlement, state);
            if (changed) {
                data.setDirty();
                com.hearthstead.network.TechTreeNetwork.broadcast(level, settlement);
            }
        }
    }

    /** True while any tech node of this settlement is being studied. */
    public static boolean anyStudy(ServerLevel level, Settlement settlement) {
        if (!TechTreeConfig.enabled() || level == null || settlement == null) {
            return false;
        }
        DevelopmentState state = Development.get(level).existingState(settlement.id);
        return state != null && !state.quarantined() && !state.studyIds().isEmpty();
    }

    /**
     * One completed Scholar session at the study: the settlement's studies
     * advance by {@code dayTicks} on top of the daily clock (the same
     * STUDY_SPEED scaling applies). Returns whether anything advanced.
     * Reliability soak 2026-09-26: the Scholar had no work at all once no
     * Research project was running, although Town+ tech studies ran.
     */
    public static boolean scholarSession(ServerLevel level, Settlement settlement, long dayTicks) {
        if (!anyStudy(level, settlement)) {
            return false;
        }
        DevelopmentState state = Development.get(level).existingState(settlement.id);
        boolean changed = advanceStudies(level, settlement, state, dayTicks);
        com.hearthstead.network.TechTreeNetwork.broadcast(level, settlement);
        return changed || !state.studyIds().isEmpty();
    }

    /** Advances study clocks by {@code dayTicks} (scaled by STUDY_SPEED). */
    static boolean advanceStudies(ServerLevel level, Settlement settlement,
                                  DevelopmentState state, long dayTicks) {
        if (dayTicks <= 0 || state.studyIds().isEmpty()) {
            return false;
        }
        double speed = 1.0D + Math.max(-0.9D, bonus(level, settlement, STUDY_SPEED) / 100.0D);
        boolean changed = false;
        for (String done : state.advanceStudies(Math.round(dayTicks * speed))) {
            TechNodeDef def = TechTreeData.get().node(done);
            if (def == null) {
                state.learnTech(done);
            } else {
                record(level, state, def);
                state.commit();
                afterLearned(level, settlement, state, def, null);
                announce(level, settlement, def, "hearthstead.techtree.announce.studied");
            }
            changed = true;
        }
        Development.get(level).setDirty();
        return changed;
    }

    /** Stamps every implemented {@code auto} node whose requires and gates are met. */
    static boolean stampAuto(ServerLevel level, Settlement settlement, DevelopmentState state) {
        boolean changed = false;
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            if (!def.auto() || learned(state, def.id()) || !implemented(def)
                || missingRequirement(state, def) != null
                || !legacyGatesMet(level, settlement, state, def)) {
                continue;
            }
            boolean met = true;
            for (GateProgress gate : gates(level, settlement, state, def)) {
                met &= gate.met();
            }
            if (met) {
                record(level, state, def);
                state.commit();
                afterLearned(level, settlement, state, def, null);
                announce(level, settlement, def, "hearthstead.techtree.announce.stamped");
                changed = true;
            }
        }
        if (changed) {
            Development.get(level).setDirty();
        }
        return changed;
    }

    private static void announce(ServerLevel level, Settlement settlement, TechNodeDef def,
                                 String key) {
        String fallback = key.endsWith(".studied")
            ? "Your scholars have finished their study: %s is learned."
            : "%s is stamped into the settlement's charter.";
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component
            .translatableWithFallback(key, fallback, def.displayName());
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(settlement.center) <= 160 * 160) {
                player.displayClientMessage(line, false);
            }
        }
    }

    // ------------------------------------------------------------ helpers

    @Nullable
    static DevelopmentObjective objective(String id) {
        if (id == null) {
            return null;
        }
        for (DevelopmentObjective objective : DevelopmentObjective.values()) {
            if (objective.id().equals(id)) {
                return objective;
            }
        }
        return null;
    }

    /** All v3 ids currently learned in a state (for snapshots/tests). */
    public static Set<String> learnedIds(DevelopmentState state) {
        Set<String> out = new HashSet<>();
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            if (learned(state, def.id())) {
                out.add(def.id());
            }
        }
        return out;
    }

    /**
     * One pass over the treasury view (buyer inventory, Banner, Warehouse
     * chests) so 84 nodes can be priced without 84 scans.
     */
    public static final class Treasury {
        final ItemStackHandler handler;
        private final List<ItemStack> stacks;

        private Treasury(ItemStackHandler handler, List<ItemStack> stacks) {
            this.handler = handler;
            this.stacks = stacks;
        }

        static Treasury of(ItemStackHandler handler) {
            List<ItemStack> stacks = new ArrayList<>();
            if (handler != null) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (!stack.isEmpty()) {
                        stacks.add(stack);
                    }
                }
            }
            return new Treasury(handler, stacks);
        }

        public int have(DevelopmentNode.Cost cost) {
            int total = 0;
            for (ItemStack stack : stacks) {
                if (cost.matches(stack)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        public boolean canPay(List<DevelopmentNode.Cost> costs) {
            if (handler == null) {
                return costs.isEmpty();
            }
            for (DevelopmentNode.Cost cost : costs) {
                if (have(cost) < cost.count()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Snapshot seam: a treasury view for the network layer. */
    public static Treasury treasury(ServerLevel level, Settlement settlement,
                                    HearthBlockEntity hearth, @Nullable ServerPlayer actor) {
        return Treasury.of(hearth == null ? null
            : CoinTreasury.open(level, settlement, hearth, actor));
    }

    /** Snapshot seam: assess against a prepared treasury view. */
    public static Assessment assess(ServerLevel level, Settlement settlement, String id,
                                    Treasury treasury) {
        return assess(level, settlement, Development.of(level, settlement), id, treasury);
    }

    /**
     * QA/filming (op command {@code /hstech grant}): learns {@code id} and
     * every node it needs (tree requires plus legacy prerequisites) without
     * paying, so a save never holds a node past its prerequisites. Returns the
     * number of nodes newly learned.
     */
    public static int qaGrant(ServerLevel level, Settlement settlement, String id) {
        DevelopmentState state = Development.of(level, settlement);
        TechNodeDef def = TechTreeData.get().node(id);
        if (def == null || state.quarantined()) {
            return 0;
        }
        int granted = qaGrant(level, state, def, new HashSet<>());
        if (granted > 0) {
            state.commit();
            Development.get(level).setDirty();
            com.hearthstead.network.TechTreeNetwork.broadcast(level, settlement);
        }
        return granted;
    }

    private static int qaGrant(ServerLevel level, DevelopmentState state, TechNodeDef def, Set<String> seen) {
        if (!seen.add(def.id()) || learned(state, def.id())) {
            return 0;
        }
        int n = 0;
        List<String> needs = new ArrayList<>(def.requires());
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                needs.addAll(node.prerequisites());
            }
        } else if (def.legacyUpgrade()) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
            if (upgrade != null) {
                needs.add(upgrade.requires().id());
                if (upgrade.requiresUpgrade() != null) {
                    needs.add(upgrade.requiresUpgrade().id());
                }
            }
        }
        for (String need : needs) {
            TechNodeDef req = TechTreeData.get().node(need);
            if (req != null) {
                n += qaGrant(level, state, req, seen);
            }
        }
        for (String ex : def.excludes()) {
            if (taken(state, ex)) {
                return n; // never grant past a pick-one choice
            }
        }
        record(level, state, def);
        return n + 1;
    }

    /** Test seam: study progress without waiting for day time. */
    static boolean advanceStudiesForTest(ServerLevel level, Settlement settlement, long ticks) {
        return advanceStudies(level, settlement, Development.of(level, settlement), ticks);
    }

    /** Test seam: one auto-stamp pass (the ticker is off on the GameTest server). */
    static boolean stampAutoForTest(ServerLevel level, Settlement settlement) {
        return stampAuto(level, settlement, Development.of(level, settlement));
    }
}

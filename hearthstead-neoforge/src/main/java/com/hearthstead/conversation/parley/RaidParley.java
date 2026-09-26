package com.hearthstead.conversation.parley;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.Conversation;
import com.hearthstead.conversation.ConversationActions;
import com.hearthstead.conversation.ConversationConfig;
import com.hearthstead.conversation.ConversationContext;
import com.hearthstead.conversation.ConversationGraphs;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.conversation.RelationSavedData;
import com.hearthstead.conversation.SpeakerProfile;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.raid.RaidBroadcast;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.state.RecurringRaidRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Raid-captain parley: before a recurring raid charges, its captain halts at
 * the edge (the whole band holds, frozen in place) and a "!" shows. Walk up
 * or right-click to talk:
 * <ul>
 *   <li><b>Pay tribute</b> (coins or food, scaled to the band): the raid leaves, nothing is looted.</li>
 *   <li><b>Persuade a truce</b>: success, the raid leaves; failure, they charge enraged.</li>
 *   <li><b>Duel</b>: the player against the captain alone; the band holds and every
 *       other blow is refused. Drive him to a quarter of his health and he yields
 *       (the raid leaves); fall to a fifth of yours and you yield (the raid charges).</li>
 *   <li><b>Refuse</b>: the fight starts.</li>
 * </ul>
 * Nobody talks within {@code parleySeconds} (counted only while a player is
 * near), or anyone strikes a holding raider: the raid charges as normal.
 * Authored first raids never parley. Behind {@code [conversations] raidParley}.
 */
public final class RaidParley {
    public static final String GRAPH = "hearthstead:raid_parley";
    public static final String BANDIT_GRAPH = "hearthstead:bandit_parley";
    public static final String HOLD_TAG = "HearthsteadParleyHold";
    public static final int DUEL_TICKS = 20 * 90;
    public static final double DUEL_LEASH = 20.0D;
    public static final float CAPTAIN_YIELD = 0.25F;
    public static final float PLAYER_YIELD = 0.20F;

    enum Phase { WAITING, DUEL }

    static final class State {
        final UUID settlementId;
        final long serial;
        final UUID captainEntity;
        final List<UUID> band;
        final ServerLevel level;
        Phase phase = Phase.WAITING;
        int eligibleTicks;
        UUID duelist;
        int duelTicks;

        State(ServerLevel level, UUID settlementId, long serial, UUID captainEntity, List<UUID> band) {
            this.level = level;
            this.settlementId = settlementId;
            this.serial = serial;
            this.captainEntity = captainEntity;
            this.band = band;
        }
    }

    private static final Map<UUID, State> ACTIVE = new HashMap<>();
    /** GameTest observability: duel outcomes per settlement (true = the player won). */
    private static final Map<UUID, List<Boolean>> OUTCOMES_FOR_TESTS = new HashMap<>();
    private static boolean bootstrapped;

    private RaidParley() {
    }

    // ------------------------------------------------------------- graph ---

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        registerGraph(GRAPH, "conversation.hearthstead.parley.");
        // Early outlaw bands (raids 1-3): same choices, a coin-hungry outlaw voice.
        registerGraph(BANDIT_GRAPH, "conversation.hearthstead.parley.bandit.");
        ConversationActions.register("parley.leave_tribute", ctx -> leave(ctx, "tribute"));
        ConversationActions.register("parley.leave_truce", ctx -> leave(ctx, "truce"));
        ConversationActions.register("parley.enrage", ctx -> charge(ctx, true));
        ConversationActions.register("parley.refuse", ctx -> charge(ctx, false));
        ConversationActions.register("parley.duel", RaidParley::startDuel);
    }

    /** One parley graph; {@code k} prefixes its spoken lines and reply texts (memories are shared). */
    private static void registerGraph(String id, String k) {
        String m = "conversation.hearthstead.parley.";
        ConversationGraphs.register(Conversation.graph(id)
            .encounter(9, "low", k + "intro")
            .node("start", n -> n
                .line(k + "demand")
                .option("tribute_coins", o -> o.text(k + "tribute_coins").cost(Conversation.coins("tribute"))
                    .relation(5).reputation(2).memory(m + "memory.tribute").action("parley.leave_tribute")
                    .reply(k + "reply.tribute").end())
                .option("tribute_food", o -> o.text(k + "tribute_food").cost(Conversation.food("food"))
                    .relation(3).reputation(1).memory(m + "memory.tribute").action("parley.leave_tribute")
                    .reply(k + "reply.tribute").end())
                .option("truce", o -> o.text(k + "truce").persuade(25, "charisma")
                    .success(s -> s.relation(10).reputation(3).memory(m + "memory.truce")
                        .action("parley.leave_truce").reply(k + "reply.truce_yes").end())
                    .failure(f -> f.relation(-10).memory(m + "memory.truce_failed")
                        .action("parley.enrage").reply(k + "reply.truce_no").end()))
                // Only a town with real defenders can threaten (odds from guards, towers and raids won).
                .option("warn", o -> o.text(k + "warn").when("town.defended").persuade(20, "intimidation")
                    .success(s -> s.relation(-5).reputation(4).memory(m + "memory.warned")
                        .action("parley.leave_truce").reply(k + "reply.warn_yes").end())
                    .failure(f -> f.relation(-15).memory(m + "memory.truce_failed")
                        .action("parley.enrage").reply(k + "reply.warn_no").end()))
                .option("duel", o -> o.text(k + "duel").relation(5).action("parley.duel")
                    .reply(k + "reply.duel").end())
                .option("refuse", o -> o.text(k + "refuse").relation(-5).reputation(-2)
                    .memory(m + "memory.refused").action("parley.refuse").reply(k + "reply.refuse").end()))
            .build());
    }

    /**
     * An early outlaw band (the raid lane's bandits, raids 1-3) speaks with the bandit lines.
     * Every outlaw band is led by a bandit captain (raid lane).
     */
    static boolean outlaw(RaiderEntity captain) {
        return captain != null && captain.isBandit();
    }

    private static String farewellKey(boolean outlaw) {
        return outlaw ? "conversation.hearthstead.parley.bandit.farewell." : "conversation.hearthstead.parley.farewell.";
    }

    public static void clear() {
        ACTIVE.clear();
    }

    /** Test/QA: whether this settlement's raid is currently holding for a parley. */
    public static boolean holding(UUID settlementId) {
        return ACTIVE.containsKey(settlementId);
    }

    @Nullable
    public static UUID captainOf(UUID settlementId) {
        State state = ACTIVE.get(settlementId);
        return state == null ? null : state.captainEntity;
    }

    // -------------------------------------------------------------- tick ---

    public static void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        if (!ACTIVE.isEmpty() && !ConversationConfig.raidParley()) {
            // Codex T14: conversations or the parley switched off mid-parley.
            // Every held band is released and the talk closed; nothing is paid,
            // no duel is scored, and the raid simply goes on as a raid.
            for (State state : List.copyOf(ACTIVE.values())) cancelDisabled(state);
            return;
        }
        for (State state : List.copyOf(ACTIVE.values())) tickState(state);
        if (tick % 20 != 7 || !ConversationConfig.raidParley()) return;
        // GameTest servers start parleys only explicitly (tryStart), so other raid tests keep their bands.
        if (server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Settlement settlement : List.copyOf(SettlementManager.data(level).settlements.values())) {
                if (ACTIVE.containsKey(settlement.id)) continue;
                tryStart(level, settlement);
            }
        }
    }

    /** Starts a parley for a freshly arrived recurring raid, at most once per raid serial. */
    public static boolean tryStart(ServerLevel level, Settlement settlement) {
        if (!ConversationConfig.raidParley()) return false;
        RecurringRaidRun run = settlement.recurringRaidRun;
        if (run == null || !run.isActive() || !run.terminalParticipants().isEmpty()) return false;
        RelationSavedData relations = RelationSavedData.get(level.getServer());
        long serial = run.activeSerial();
        if (relations.lastParleySerial(settlement.id) == serial) return false;
        List<RaiderEntity> band = RaidDirector.livingRaidersOf(level, settlement);
        RaiderEntity captain = null;
        for (RaiderEntity raider : band) {
            if (raider.isCaptain() && run.isParticipant(raider.getUUID())) captain = raider;
        }
        if (captain == null || band.size() < run.participants().size()
            || !RaidDirector.outsideClaim(settlement, captain.blockPosition()) || !playerNear(level, settlement)) {
            return false;
        }
        relations.markParley(settlement.id, serial);
        List<UUID> ids = new ArrayList<>();
        for (RaiderEntity raider : band) {
            if (!run.isParticipant(raider.getUUID())) continue;
            ids.add(raider.getUUID());
            hold(raider, true);
        }
        State state = new State(level, settlement.id, serial, captain.getUUID(), ids);
        ACTIVE.put(settlement.id, state);
        bindCaptain(level, settlement, captain, ids.size());
        RaidBroadcast.send(level, settlement, Component.translatable("conversation.hearthstead.parley.halted",
            captainName(settlement, captain), ConversationConfig.parleyTicks() / 20));
        return true;
    }

    private static void bindCaptain(ServerLevel level, Settlement settlement, RaiderEntity captain, int bandSize) {
        UUID identity = captain.captainId() != null ? captain.captainId() : captain.getUUID();
        RaidCaptain record = captain.captainId() == null ? null : RaidDirector.captainOf(settlement, captain.captainId());
        Map<String, Integer> vars = new LinkedHashMap<>();
        vars.put("tribute", tributeCoins(bandSize));
        vars.put("food", tributeFood(bandSize));
        // Always present so the lang args keep their order: %4$s defeats, %5$s food, %6$s tribute, %7$s victories.
        vars.put("victories", record == null ? 0 : record.victories());
        vars.put("defeats", record == null ? 0 : record.defeats());
        boolean outlaw = outlaw(captain);
        SpeakerProfile profile = new SpeakerProfile(identity, captainName(settlement, captain),
            outlaw ? "conversation.hearthstead.title.bandit_leader" : "conversation.hearthstead.title.raid_captain", "raider");
        ConversationService.bind(captain, outlaw ? BANDIT_GRAPH : GRAPH, profile, vars, settlement.id);
    }

    /** Coins asked: 3 per raider (the captain counts double), at least 6. */
    public static int tributeCoins(int bandSize) {
        return Math.max(6, 3 * (Math.max(1, bandSize) + 1));
    }

    /** Food asked instead: 6 per raider, at least 12. */
    public static int tributeFood(int bandSize) {
        return Math.max(12, 6 * (Math.max(1, bandSize) + 1));
    }

    private static String captainName(Settlement settlement, RaiderEntity captain) {
        if (captain.captainId() != null) {
            var name = RaidDirector.leaderNameOf(settlement, captain.captainId());
            if (name.isPresent()) return name.get();
        }
        return captain.getName().getString();
    }

    private static boolean playerNear(ServerLevel level, Settlement settlement) {
        double reach = settlement.radius + RaidDirector.PLAYER_PRESENCE_MARGIN;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.isAlive()
                && player.blockPosition().distSqr(settlement.center) <= reach * reach) return true;
        }
        return false;
    }

    private static void tickState(State state) {
        ServerLevel level = state.level;
        Settlement settlement = SettlementManager.byId(level, state.settlementId);
        Entity entity = level.getEntity(state.captainEntity);
        if (settlement == null || !(entity instanceof RaiderEntity captain) || !captain.isAlive()
            || !settlement.recurringRaidRun.isActive() || settlement.recurringRaidRun.activeSerial() != state.serial) {
            release(state, false);
            return;
        }
        if (state.phase == Phase.DUEL) {
            tickDuel(state, settlement, captain);
            return;
        }
        // Face the nearest player; the "!" marker comes from the conversation binding.
        Player nearest = level.getNearestPlayer(captain, 48.0D);
        if (nearest != null) face(captain, nearest.position());
        if (ConversationService.isTalking(captain)) return;
        if (playerNear(level, settlement)) state.eligibleTicks++;
        if (state.eligibleTicks >= ConversationConfig.parleyTicks()) {
            RaidBroadcast.send(level, settlement, Component.translatable("conversation.hearthstead.parley.impatient",
                captainName(settlement, captain)));
            release(state, false);
        }
    }

    private static void face(LivingEntity mob, Vec3 target) {
        double dx = target.x - mob.getX();
        double dz = target.z - mob.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
    }

    // ----------------------------------------------------------- outcomes ---

    @Nullable
    private static State stateFor(ConversationContext ctx) {
        for (State state : ACTIVE.values()) {
            if (state.captainEntity.equals(ctx.speaker().getUUID())) return state;
        }
        return null;
    }

    private static void leave(ConversationContext ctx, String why) {
        State state = stateFor(ctx);
        Settlement settlement = ctx.settlement();
        if (state == null || settlement == null) return;
        Component line = Component.translatable("conversation.hearthstead.parley.left." + why,
            ctx.profile().name(), ctx.player().getGameProfile().getName());
        ACTIVE.remove(state.settlementId);
        ConversationService.unbind(ctx.speaker(), line);
        if (!walkAway(state, settlement, ctx.speaker(), line, ctx.profile(),
            Component.translatable(farewellKey(BANDIT_GRAPH.equals(ctx.graphId())) + why, settlement.name))) {
            // The raid ledger refused (contradictory state): the band charges instead.
            releaseBand(state, false);
            return;
        }
        ctx.notice(line);
    }

    /**
     * Resolves the raid WITHOUT the dust: the band is released from its hold
     * and walks off together behind its captain (conversation Departure), and
     * vanishes only out of sight. The raid, its bar and the alarm end now.
     */
    private static boolean walkAway(State state, Settlement settlement, Entity captain, Component line,
                                    SpeakerProfile profile, Component farewell) {
        List<net.minecraft.world.entity.Mob> band = new ArrayList<>();
        if (captain instanceof RaiderEntity c && c.isAlive()) band.add(c);
        for (UUID id : state.band) {
            if (state.level.getEntity(id) instanceof RaiderEntity raider && raider.isAlive() && raider != captain) {
                band.add(raider);
            }
        }
        if (!RaidDirector.resolveRecurringRetreat(state.level, settlement, line, false)) return false;
        for (net.minecraft.world.entity.Mob mob : band) {
            if (mob instanceof RaiderEntity raider) hold(raider, false);
        }
        com.hearthstead.conversation.Departure.depart(state.level, band, settlement.center, null, farewell,
            new com.hearthstead.conversation.Departure.Truce(settlement.id, profile));
        return true;
    }

    private static void charge(ConversationContext ctx, boolean enraged) {
        State state = stateFor(ctx);
        if (state == null) return;
        Settlement settlement = ctx.settlement();
        if (settlement != null) {
            RaidBroadcast.send(state.level, settlement, Component.translatable(enraged
                ? "conversation.hearthstead.parley.charge_enraged" : "conversation.hearthstead.parley.charge",
                ctx.profile().name()));
        }
        ACTIVE.remove(state.settlementId);
        ConversationService.unbind(ctx.speaker());
        releaseBand(state, enraged);
    }

    private static void startDuel(ConversationContext ctx) {
        State state = stateFor(ctx);
        if (state == null || !(ctx.speaker() instanceof RaiderEntity captain)) return;
        state.phase = Phase.DUEL;
        state.duelist = ctx.player().getUUID();
        state.duelTicks = 0;
        ConversationService.unbind(captain);
        hold(captain, false);
        captain.setTarget(ctx.player());
        Settlement settlement = ctx.settlement();
        if (settlement != null) {
            RaidBroadcast.send(state.level, settlement, Component.translatable("conversation.hearthstead.parley.duel_start",
                ctx.player().getGameProfile().getName(), ctx.profile().name()));
        }
        state.level.playSound(null, captain.blockPosition(), com.hearthstead.registry.ModSounds.RAID_HORN.get(), SoundSource.HOSTILE, 1.5F, 1.1F);
        ctx.close();
    }

    private static void tickDuel(State state, Settlement settlement, RaiderEntity captain) {
        ServerPlayer duelist = state.level.getServer().getPlayerList().getPlayer(state.duelist);
        state.duelTicks++;
        if (duelist == null || !duelist.isAlive() || duelist.level() != state.level
            || duelist.distanceTo(captain) > DUEL_LEASH || state.duelTicks > DUEL_TICKS) {
            endDuel(state, settlement, captain, false);
            return;
        }
        captain.setTarget(duelist);
        if (state.duelTicks % 10 == 0) {
            // A ring of dust marks the duel ground.
            Vec3 c = captain.position().add(duelist.position()).scale(0.5D);
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6.0D;
                state.level.sendParticles(ParticleTypes.CRIT, c.x + Math.cos(a) * 5.0D, c.y + 0.1D,
                    c.z + Math.sin(a) * 5.0D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private static void endDuel(State state, Settlement settlement, RaiderEntity captain, boolean playerWon) {
        // Exactly once: a second yield callback, a tick timeout in the same tick
        // or any re-entry finds this duel already over.
        if (ACTIVE.get(state.settlementId) != state || state.phase != Phase.DUEL) return;
        ACTIVE.remove(state.settlementId);
        OUTCOMES_FOR_TESTS.computeIfAbsent(state.settlementId, k -> new java.util.ArrayList<>()).add(playerWon);
        ServerPlayer duelist = state.level.getServer().getPlayerList().getPlayer(state.duelist);
        String playerName = duelist == null ? "?" : duelist.getGameProfile().getName();
        String captainName = captainName(settlement, captain);
        UUID identity = captain.captainId() != null ? captain.captainId() : captain.getUUID();
        SpeakerProfile profile = new SpeakerProfile(identity, captainName,
            "conversation.hearthstead.title.raid_captain", "raider");
        RelationSavedData.get(state.level.getServer()).change(settlement.id, profile, playerWon ? 15 : -5,
            playerWon ? 5 : -3, playerWon ? "conversation.hearthstead.parley.memory.duel_lost"
                : "conversation.hearthstead.parley.memory.duel_won", state.level.getGameTime());
        if (playerWon) {
            captain.setTarget(null);
            Component line = Component.translatable("conversation.hearthstead.parley.duel_won", playerName, captainName);
            if (!walkAway(state, settlement, captain, line, profile,
                Component.translatable(farewellKey(outlaw(captain)) + "duel", playerName))) {
                releaseBand(state, false);
            }
        } else {
            RaidBroadcast.send(state.level, settlement,
                Component.translatable("conversation.hearthstead.parley.duel_lost", playerName, captainName));
            releaseBand(state, false);
        }
    }

    /**
     * Duel fairness, decided on the incoming (raw) blow: only the duelist may
     * strike the captain, the captain may strike only the duelist, nobody else
     * may strike the duelist, and the holding band cannot be hurt while it
     * holds. Yields are NOT decided here: a shield, armour or absorption can
     * still soak this blow, so {@link #yieldOnDamage} decides on what lands.
     */
    public static boolean blockDamage(LivingEntity victim, DamageSource source, float amount) {
        // T14: switched off, the parley's fairness rules no longer apply (the
        // next server tick releases the band).
        if (ACTIVE.isEmpty() || victim.level().isClientSide || !ConversationConfig.raidParley()) return false;
        Entity attacker = source.getEntity();
        for (State state : List.copyOf(ACTIVE.values())) {
            if (state.phase == Phase.WAITING) {
                if (state.band.contains(victim.getUUID())) {
                    // Striking a holding raider breaks the parley: the raid charges.
                    Settlement settlement = SettlementManager.byId(state.level, state.settlementId);
                    ACTIVE.remove(state.settlementId);
                    Entity captain = state.level.getEntity(state.captainEntity);
                    if (captain != null) ConversationService.unbind(captain);
                    if (settlement != null) RaidBroadcast.send(state.level, settlement,
                        Component.translatable("conversation.hearthstead.parley.broken"));
                    releaseBand(state, false);
                    return false;
                }
                continue;
            }
            boolean isCaptain = victim.getUUID().equals(state.captainEntity);
            boolean isDuelist = victim.getUUID().equals(state.duelist);
            boolean fromCaptain = attacker != null && attacker.getUUID().equals(state.captainEntity);
            boolean fromDuelist = attacker != null && attacker.getUUID().equals(state.duelist);
            // /kill, the void and other invulnerability-bypassing damage are never refused.
            if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) continue;
            if (state.band.contains(victim.getUUID()) && !isCaptain) return true;
            // Only blows from other living attackers are refused; lava, fire, falls and the like still
            // land, and yieldOnDamage turns them into a yield instead of a death.
            if (isCaptain && attacker != null && !fromDuelist) return true;
            if (isDuelist && attacker != null && !fromCaptain) return true;
            if (fromCaptain && !isDuelist) return true;
        }
        return false;
    }

    /**
     * The yield, decided on the damage that would really land: {@code damage}
     * is the amount after shield, armour and enchantments
     * ({@code LivingDamageEvent.Pre}); absorption hearts still soak their share.
     * A blow that would take either duelist to or below the yield line ends
     * the duel (exactly once) and returns true: the caller then lands nothing.
     */
    public static boolean yieldOnDamage(LivingEntity victim, DamageSource source, float damage) {
        // T14: no duel is scored while the parley is switched off.
        if (ACTIVE.isEmpty() || victim.level().isClientSide || !ConversationConfig.raidParley()) return false;
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        float effective = Math.max(0.0F, damage - victim.getAbsorptionAmount());
        if (effective <= 0.0F) return false;
        for (State state : List.copyOf(ACTIVE.values())) {
            if (state.phase != Phase.DUEL || ACTIVE.get(state.settlementId) != state) continue;
            boolean isCaptain = victim.getUUID().equals(state.captainEntity);
            boolean isDuelist = victim.getUUID().equals(state.duelist);
            if (!isCaptain && !isDuelist) continue;
            Settlement settlement = SettlementManager.byId(state.level, state.settlementId);
            if (settlement == null) continue;
            if (isCaptain && victim instanceof RaiderEntity captain
                && victim.getHealth() - effective <= victim.getMaxHealth() * CAPTAIN_YIELD) {
                endDuel(state, settlement, captain, true);
                return true;
            }
            if (isDuelist && state.level.getEntity(state.captainEntity) instanceof RaiderEntity captain
                && victim.getHealth() - effective <= victim.getMaxHealth() * PLAYER_YIELD) {
                endDuel(state, settlement, captain, false);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------- GameTest ---

    /** Puts {@code player} and {@code captain} straight into a duel (no raid run needed until the next tick). */
    static void startDuelForTests(ServerLevel level, UUID settlementId, RaiderEntity captain, ServerPlayer player) {
        State state = new State(level, settlementId, 0L, captain.getUUID(), List.of());
        state.phase = Phase.DUEL;
        state.duelist = player.getUUID();
        ACTIVE.put(settlementId, state);
        OUTCOMES_FOR_TESTS.remove(settlementId);
    }

    /** Holds {@code band} (captain included) in a WAITING parley as tryStart would, without a raid run. */
    static void startWaitingForTests(ServerLevel level, UUID settlementId, RaiderEntity captain,
                                     List<RaiderEntity> band) {
        List<UUID> ids = new ArrayList<>();
        for (RaiderEntity raider : band) {
            ids.add(raider.getUUID());
            hold(raider, true);
        }
        ACTIVE.put(settlementId, new State(level, settlementId, 0L, captain.getUUID(), ids));
        OUTCOMES_FOR_TESTS.remove(settlementId);
    }

    static boolean duelActiveForTests(UUID settlementId) {
        State state = ACTIVE.get(settlementId);
        return state != null && state.phase == Phase.DUEL;
    }

    /** Every outcome recorded for this settlement's duels (true = the player won). */
    static List<Boolean> duelOutcomesForTests(UUID settlementId) {
        return List.copyOf(OUTCOMES_FOR_TESTS.getOrDefault(settlementId, List.of()));
    }

    static void clearDuelForTests(UUID settlementId) {
        ACTIVE.remove(settlementId);
        OUTCOMES_FOR_TESTS.remove(settlementId);
    }

    public static void onHurt(LivingEntity victim) {
        // Nothing yet: yields are decided as damage lands (yieldOnDamage).
    }

    /** Raiders frozen by a parley that no longer runs (server restart) are released when they load. */
    public static void onJoin(Entity entity) {
        if (!(entity instanceof RaiderEntity raider) || !raider.getPersistentData().getBoolean(HOLD_TAG)) return;
        for (State state : ACTIVE.values()) if (state.band.contains(raider.getUUID())) return;
        hold(raider, false);
        ConversationService.unbind(raider);
    }

    // -------------------------------------------------------------- hold ---

    private static void hold(RaiderEntity raider, boolean on) {
        raider.setNoAi(on);
        if (on) {
            raider.getPersistentData().putBoolean(HOLD_TAG, true);
            raider.setTarget(null);
            raider.getNavigation().stop();
        } else {
            raider.getPersistentData().remove(HOLD_TAG);
        }
    }

    /** Switch-off release (T14): no payout, no duel outcome, no relation change. */
    private static void cancelDisabled(State state) {
        ACTIVE.remove(state.settlementId);
        Entity captain = state.level.getEntity(state.captainEntity);
        if (captain != null) {
            ConversationService.unbind(captain);
            if (captain instanceof RaiderEntity raider) {
                hold(raider, false);
                if (state.phase == Phase.DUEL) raider.setTarget(null); // no forced duel target
            }
        }
        releaseBand(state, false);
    }

    private static void release(State state, boolean enraged) {
        ACTIVE.remove(state.settlementId);
        Entity captain = state.level.getEntity(state.captainEntity);
        if (captain != null) ConversationService.unbind(captain);
        releaseBand(state, enraged);
    }

    private static void releaseBand(State state, boolean enraged) {
        for (UUID id : state.band) {
            if (state.level.getEntity(id) instanceof RaiderEntity raider && raider.isAlive()) {
                hold(raider, false);
                if (enraged) {
                    raider.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 120, 0));
                    raider.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 120, 0));
                    state.level.sendParticles(ParticleTypes.ANGRY_VILLAGER, raider.getX(), raider.getEyeY() + 0.3D,
                        raider.getZ(), 3, 0.3D, 0.2D, 0.3D, 0.0D);
                }
            }
        }
        Hearthstead.LOGGER.info("Raid parley at {} ended; band released (enraged={})", state.settlementId, enraged);
    }
}

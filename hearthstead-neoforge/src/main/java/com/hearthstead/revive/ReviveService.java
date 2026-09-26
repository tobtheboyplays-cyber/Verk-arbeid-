package com.hearthstead.revive;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.network.ReviveEventPayload;
import com.hearthstead.network.ReviveStatePayload;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidDirector;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server authority for co-op "downed / revive your comrade".
 *
 * <p>During an active raid at a player's settlement (or {@code
 * postRaidGraceSeconds} after it), with another player standing near that
 * settlement, a lethal hit puts the player DOWN instead of killing them:
 * forced crawl pose, heavy slow, no jumping, attacking, using or healing, and
 * a bleed-out timer. A teammate holding use on them for {@code reviveSeconds}
 * gets them up with {@code reviveHealthPercent} health and brief Resistance;
 * damage to either side resets the hold. Sneak + use drags them. Bleeding
 * out, being finished off, or logging out while down is an ordinary vanilla
 * death (drops, respawn) - the items are dropped exactly once, by vanilla.
 *
 * <p>All state is server-side and transient. A crash while down is covered
 * by one persistent marker on the player: the next login treats it as a
 * bleed-out, so nobody resumes a half-dead state and nothing is duplicated.
 */
public final class ReviveService {
    public static final ResourceKey<DamageType> BLEED_OUT = ResourceKey.create(
        Registries.DAMAGE_TYPE, Hearthstead.id("bleed_out"));
    /** Persistent-data marker: set while down, cleared on revive/death. */
    public static final String PERSIST_KEY = "hearthstead_downed";
    /** Health a player is left with while down: enemies can still finish them. */
    public static final float DOWNED_HEALTH = 8.0F;
    static final ResourceLocation SLOW_ID = Hearthstead.id("downed_slow");
    static final ResourceLocation NO_JUMP_ID = Hearthstead.id("downed_no_jump");
    static final ResourceLocation DRAG_SLOW_ID = Hearthstead.id("dragging_slow");
    private static final int FINISHER_ROLL_TICKS = 100;
    private static final double FINISHER_RANGE = 24.0D;
    private static final double ALLY_MARGIN = 64.0D;
    private static final int RESISTANCE_TICKS = 100;

    private static final Map<MinecraftServer, ServerState> STATES = new WeakHashMap<>();
    private static final List<DownedRescuer> RESCUERS = new CopyOnWriteArrayList<>();
    /**
     * Client mirror of downed entity ids (written only on the client thread
     * by DownedClient). Lets common-side input guards run on the client too
     * without this class touching client-only code.
     */
    public static final Set<Integer> CLIENT_DOWNED_IDS = Collections.synchronizedSet(new HashSet<>());

    private static int testBleedTicks = -1;
    private static int testReviveTicks = -1;
    private static int testGraceTicks = -1;

    private ReviveService() {
    }

    // ------------------------------------------------------------------ state

    static final class ServerState {
        final Map<UUID, Downed> downed = new LinkedHashMap<>();
        final Map<UUID, Long> lastRaidActive = new HashMap<>();
        final Set<UUID> raidActiveNow = new HashSet<>();
        final Map<UUID, ReviveTally> tallies = new HashMap<>();
        final Map<UUID, Long> lastDragPing = new HashMap<>();
        /** Crash-recovery kills refused by another mod, retried once per second. */
        final Map<UUID, Integer> pendingRecovery = new LinkedHashMap<>();
        final Set<ResourceKey<net.minecraft.world.level.Level>> sentNonEmpty = new HashSet<>();
        boolean dirty;
        long lastSync;
    }

    /** One downed player. */
    static final class Downed {
        final UUID playerId;
        @Nullable
        final UUID settlementId;
        final ReviveRules.BleedTimer bleed;
        final ReviveRules.ReviveProgress revive;
        final long downedAt;
        @Nullable
        UUID reviver;
        @Nullable
        UUID dragger;
        @Nullable
        UUID finisher;

        Downed(UUID playerId, @Nullable UUID settlementId, int bleedTicks, int reviveTicks,
               long now) {
            this.playerId = playerId;
            this.settlementId = settlementId;
            this.bleed = new ReviveRules.BleedTimer(bleedTicks);
            this.revive = new ReviveRules.ReviveProgress(reviveTicks);
            this.downedAt = now;
        }
    }

    private static ServerState state(MinecraftServer server) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(server, s -> new ServerState());
        }
    }

    private static long now(MinecraftServer server) {
        return server.getTickCount();
    }

    // ------------------------------------------------------------------ queries

    public static boolean isDowned(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        if (player.level().isClientSide) {
            return CLIENT_DOWNED_IDS.contains(player.getId());
        }
        MinecraftServer server = player.getServer();
        return server != null && state(server).downed.containsKey(player.getUUID());
    }

    /** Bleed-out ticks left, or -1 when not down. */
    public static int bleedTicksLeft(ServerPlayer player) {
        Downed d = downedOf(player);
        return d == null ? -1 : d.bleed.ticksLeft();
    }

    /** Revive hold progress 0..1, or -1 when not down. */
    public static float reviveFraction(ServerPlayer player) {
        Downed d = downedOf(player);
        return d == null ? -1.0F : d.revive.fraction();
    }

    @Nullable
    public static UUID draggerOf(ServerPlayer player) {
        Downed d = downedOf(player);
        return d == null ? null : d.dragger;
    }

    /** Raid-report hook: downs / revives / bleed-outs for this settlement's current or last raid. */
    public static ReviveTally tally(MinecraftServer server, UUID settlementId) {
        return state(server).tallies.getOrDefault(settlementId, ReviveTally.EMPTY);
    }

    /** Future Infirmary / Healer / guard rescue hook, see {@link DownedRescuer}. */
    public static void registerRescuer(DownedRescuer rescuer) {
        if (rescuer != null) {
            RESCUERS.add(rescuer);
        }
    }

    @Nullable
    private static Downed downedOf(@Nullable ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return null;
        }
        return state(player.getServer()).downed.get(player.getUUID());
    }

    // ------------------------------------------------------------------ tuning

    static int bleedTicks() {
        return testBleedTicks > 0 ? testBleedTicks
            : ReviveRules.secondsToTicks(HearthsteadServerConfig.reviveBleedOutSeconds());
    }

    static int reviveTicks() {
        return testReviveTicks > 0 ? testReviveTicks
            : ReviveRules.secondsToTicks(HearthsteadServerConfig.reviveSeconds());
    }

    static long graceTicks() {
        return testGraceTicks >= 0 ? testGraceTicks
            : (long) HearthsteadServerConfig.reviveGraceSeconds() * ReviveRules.TPS;
    }

    /** GameTest seam: shorter timers. Pass -1 to restore the config values. */
    public static void overrideTimingsForTest(int bleedTicks, int reviveTicks, int graceTicks) {
        testBleedTicks = bleedTicks;
        testReviveTicks = reviveTicks;
        testGraceTicks = graceTicks;
    }

    // ------------------------------------------------------------------ down

    /**
     * Called from the (cancellable) death event. Returns true when the player
     * was put down instead, in which case the caller cancels the death.
     */
    public static boolean tryDown(ServerPlayer player, DamageSource source) {
        return tryDown(player, source, false);
    }

    /** QA seam (/hsrevive down): downs a survival player with no raid, ally or settlement needed. */
    public static boolean forceDown(ServerPlayer player) {
        return tryDown(player, null, true);
    }

    private static boolean tryDown(ServerPlayer player, @Nullable DamageSource source, boolean forced) {
        MinecraftServer server = player.getServer();
        if (server == null || player instanceof FakePlayer) {
            return false;
        }
        ServerState st = state(server);
        ServerLevel level = player.serverLevel();
        Settlement settlement = raidSettlementAt(level, player, st, now(server));
        if (forced) {
            if (st.downed.containsKey(player.getUUID()) || player.isCreative() || player.isSpectator()
                || !player.isAlive()) {
                return false;
            }
            if (settlement == null) {
                settlement = settlementCovering(level, player); // so rescuers are offered in QA/tests
            }
        } else {
            ReviveRules.DownContext ctx = new ReviveRules.DownContext(
            HearthsteadServerConfig.reviveEnabled(),
            player.isCreative() || player.isSpectator(),
            source != null && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY),
            st.downed.containsKey(player.getUUID()),
            settlement != null,
            settlement != null,
            settlement != null && standingAllyNear(level, player, settlement, st),
            HearthsteadServerConfig.reviveSolo());
            if (!ReviveRules.shouldDown(ctx)) {
                return false;
            }
        }
        long now = now(server);
        UUID settlementId = settlement == null ? null : settlement.id;
        Downed d = new Downed(player.getUUID(), settlementId, bleedTicks(), reviveTicks(), now);
        st.downed.put(player.getUUID(), d);
        if (settlementId != null && !forced) {
            st.tallies.merge(settlementId, ReviveTally.EMPTY.withDown(), (a, b) -> a.withDown());
        }

        player.setHealth(Math.min(player.getMaxHealth(), DOWNED_HEALTH));
        player.stopUsingItem();
        if (player.isSleeping()) {
            player.stopSleeping();
        }
        player.stopRiding();
        player.stopFallFlying();
        player.setSprinting(false);
        player.setForcedPose(Pose.SWIMMING);
        addModifier(player, Attributes.MOVEMENT_SPEED, SLOW_ID, -0.5D);
        addModifier(player, Attributes.JUMP_STRENGTH, NO_JUMP_ID, -1.0D);
        player.getPersistentData().putBoolean(PERSIST_KEY, true);

        // Most enemies lose interest in a downed player; with a small chance
        // one of the raiders already on them stays on to finish the job.
        double chance = HearthsteadServerConfig.reviveFinisherChance();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32.0D),
            m -> m.getTarget() == player)) {
            if (d.finisher == null && mob instanceof RaiderEntity
                && level.random.nextDouble() < chance) {
                d.finisher = mob.getUUID();
            } else {
                mob.setTarget(null);
            }
        }

        Component name = player.getDisplayName();
        Component line = Component.translatable("hearthstead.revive.down", name,
            Component.keybind("key.use")).withStyle(ChatFormatting.RED);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != player) {
                other.sendSystemMessage(line);
            }
        }
        event(level, ReviveEventPayload.DOWNED, player);
        st.dirty = true;
        Hearthstead.LOGGER.info("HS_REVIVE down player={} settlement={} bleedTicks={} forced={}",
            player.getGameProfile().getName(), settlementId, d.bleed.totalTicks(), forced);
        return true;
    }

    /** The settlement whose raid (or grace) covers this player's position, or null. */
    @Nullable
    private static Settlement raidSettlementAt(ServerLevel level, ServerPlayer player,
                                               ServerState st, long now) {
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null) {
            return null;
        }
        for (Settlement s : data.settlements.values()) {
            if (s == null || s.center == null || !within(player, s, RaidDirector.PLAYER_PRESENCE_MARGIN)) {
                continue;
            }
            boolean active = BlessingEffects.raidActive(s);
            if (active) {
                if (st.raidActiveNow.add(s.id)) {
                    st.tallies.put(s.id, ReviveTally.EMPTY); // first sight of this raid
                }
                st.lastRaidActive.put(s.id, now);
                return s;
            }
            if (ReviveRules.inGrace(now, st.lastRaidActive.getOrDefault(s.id, -1L), graceTicks())) {
                return s;
            }
        }
        return null;
    }

    /** The settlement whose claim (plus the raid presence margin) covers this player, raid or not. */
    @Nullable
    private static Settlement settlementCovering(ServerLevel level, ServerPlayer player) {
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null) {
            return null;
        }
        for (Settlement s : data.settlements.values()) {
            if (s != null && s.center != null && within(player, s, RaidDirector.PLAYER_PRESENCE_MARGIN)) {
                return s;
            }
        }
        return null;
    }

    private static boolean within(Entity e, Settlement s, double margin) {
        double reach = s.radius + margin;
        double dx = e.getX() - (s.center.getX() + 0.5D);
        double dz = e.getZ() - (s.center.getZ() + 0.5D);
        return dx * dx + dz * dz <= reach * reach;
    }

    private static boolean standingAllyNear(ServerLevel level, ServerPlayer player,
                                            Settlement s, ServerState st) {
        for (ServerPlayer other : level.players()) {
            if (other == player || other instanceof FakePlayer || other.isSpectator()
                || !other.isAlive() || st.downed.containsKey(other.getUUID())) {
                continue;
            }
            if (within(other, s, ALLY_MARGIN)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ revive

    /**
     * One "still holding use" ping from a reviver (a player's held use key, or
     * later a settler rescuer each tick). Returns true when the ping was
     * accepted as the active revive.
     */
    public static boolean pingRevive(LivingEntity reviver, ServerPlayer target) {
        MinecraftServer server = target.getServer();
        if (server == null || reviver == target || !reviver.isAlive()
            || reviver.level() != target.level()
            || reviver.distanceToSqr(target) > ReviveRules.REVIVE_REACH_SQR
            || reviver instanceof Player p && (isDowned(p) || p.isSpectator())) {
            return false;
        }
        ServerState st = state(server);
        Downed d = st.downed.get(target.getUUID());
        if (d == null) {
            return false;
        }
        if (d.reviver != null && !d.reviver.equals(reviver.getUUID()) && d.revive.active()) {
            if (reviver instanceof ServerPlayer rp) {
                Entity busy = target.serverLevel().getEntity(d.reviver);
                rp.displayClientMessage(Component.translatable("hearthstead.revive.busy",
                    busy == null ? Component.literal("?") : busy.getDisplayName()), true);
            }
            return false;
        }
        if (d.reviver == null || !d.revive.active()) {
            st.dirty = true;
        }
        d.reviver = reviver.getUUID();
        d.revive.ping(now(server));
        if (reviver instanceof ServerPlayer rp && rp.getForcedPose() == null) {
            rp.setForcedPose(Pose.CROUCHING);
        }
        return true;
    }

    /** Handles a use-key interaction by {@code actor} on a downed {@code target}. */
    public static void onUse(ServerPlayer actor, ServerPlayer target, boolean sneaking) {
        MinecraftServer server = actor.getServer();
        if (server == null || !isDowned(target) || isDowned(actor)) {
            return;
        }
        ServerState st = state(server);
        if (sneaking) {
            long now = now(server);
            Long last = st.lastDragPing.put(actor.getUUID(), now);
            if (last != null && now - last <= ReviveRules.DRAG_TOGGLE_DEBOUNCE_TICKS) {
                return; // the same held press, not a new toggle
            }
            Downed d = st.downed.get(target.getUUID());
            if (d != null && actor.getUUID().equals(d.dragger)) {
                releaseDrag(target.serverLevel(), d, true);
            } else {
                startDrag(actor, target);
            }
            return;
        }
        pingRevive(actor, target);
    }

    private static void completeRevive(ServerLevel level, ServerPlayer player, Downed d,
                                       @Nullable Entity reviver) {
        ServerState st = state(level.getServer());
        st.downed.remove(player.getUUID());
        cleanup(level, player, d);
        player.setHealth(ReviveRules.reviveHealth(player.getMaxHealth(),
            HearthsteadServerConfig.reviveHealthPercent()));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, RESISTANCE_TICKS, 1));
        player.invulnerableTime = 20;
        if (d.settlementId != null) {
            st.tallies.merge(d.settlementId, ReviveTally.EMPTY.withRevive(), (a, b) -> a.withRevive());
        }
        Component line = Component.translatable("hearthstead.revive.revived",
            reviver == null ? Component.literal("?") : reviver.getDisplayName(),
            player.getDisplayName()).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
            other.sendSystemMessage(line);
        }
        event(level, ReviveEventPayload.REVIVED, player);
        st.dirty = true;
        Hearthstead.LOGGER.info("HS_REVIVE revived player={} by={}",
            player.getGameProfile().getName(), reviver == null ? "?" : reviver.getName().getString());
    }

    /** Test/admin seam: revive immediately as if the hold completed. */
    public static boolean forceRevive(ServerPlayer player, @Nullable Entity reviver) {
        Downed d = downedOf(player);
        if (d == null) {
            return false;
        }
        completeRevive(player.serverLevel(), player, d, reviver);
        return true;
    }

    // ------------------------------------------------------------------ drag

    /** Starts dragging a downed player (sneak + use). */
    public static boolean startDrag(LivingEntity dragger, ServerPlayer target) {
        MinecraftServer server = target.getServer();
        if (server == null || dragger == target || !dragger.isAlive()
            || dragger.level() != target.level()
            || dragger.distanceToSqr(target) > ReviveRules.REVIVE_REACH_SQR
            || dragger instanceof Player p && (isDowned(p) || p.isSpectator())) {
            return false;
        }
        ServerState st = state(server);
        Downed d = st.downed.get(target.getUUID());
        if (d == null) {
            return false;
        }
        // Only one hauler per player, and a hauler hauls one player.
        for (Downed other : st.downed.values()) {
            if (other != d && dragger.getUUID().equals(other.dragger)) {
                releaseDrag(target.serverLevel(), other, false);
            }
        }
        if (d.dragger != null) {
            releaseDrag(target.serverLevel(), d, false);
        }
        d.dragger = dragger.getUUID();
        removeModifier(target, Attributes.MOVEMENT_SPEED, SLOW_ID);
        addModifier(dragger, Attributes.MOVEMENT_SPEED, DRAG_SLOW_ID, -0.7D);
        if (dragger instanceof ServerPlayer dp) {
            dp.setSprinting(false);
            dp.displayClientMessage(Component.translatable("hearthstead.revive.drag_start",
                target.getDisplayName(), Component.keybind("key.sneak"),
                Component.keybind("key.use")), true);
        }
        st.dirty = true;
        return true;
    }

    private static void releaseDrag(ServerLevel level, Downed d, boolean announce) {
        if (d.dragger == null) {
            return;
        }
        Entity dragger = level.getServer() == null ? null : findEntity(level.getServer(), d.dragger);
        if (dragger instanceof LivingEntity living) {
            removeModifier(living, Attributes.MOVEMENT_SPEED, DRAG_SLOW_ID);
            if (announce && living instanceof ServerPlayer dp) {
                dp.displayClientMessage(Component.translatable("hearthstead.revive.drag_stop"), true);
            }
        }
        d.dragger = null;
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(d.playerId);
        if (target != null && state(level.getServer()).downed.containsKey(d.playerId)) {
            addModifier(target, Attributes.MOVEMENT_SPEED, SLOW_ID, -0.5D);
        }
        state(level.getServer()).dirty = true;
    }

    // ------------------------------------------------------------------ death / exit

    /**
     * Death of a player who was down (bled out, finished off, killed by
     * /kill...). Vanilla death proceeds; we only clean up and count.
     */
    public static void onDownedDeath(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerState st = state(server);
        Downed d = st.downed.remove(player.getUUID());
        if (d == null) {
            return;
        }
        cleanup(player.serverLevel(), player, d);
        if (d.settlementId != null) {
            st.tallies.merge(d.settlementId, ReviveTally.EMPTY.withBleedOut(), (a, b) -> a.withBleedOut());
        }
        event(player.serverLevel(), ReviveEventPayload.DIED, player);
        st.dirty = true;
        Hearthstead.LOGGER.info("HS_REVIVE died_while_down player={}", player.getGameProfile().getName());
    }

    /** Kills a downed player with the bleed-out damage type (normal vanilla death). */
    public static void bleedOut(ServerPlayer player) {
        DamageSource source = player.damageSources().source(BLEED_OUT);
        player.hurt(source, Float.MAX_VALUE);
        if (player.isAlive() && isDowned(player)) {
            // Something refused the hit; never leave a half-dead player.
            player.kill();
        }
    }

    /** A downed player logging out is treated as bleeding out (drops once, here). */
    public static void onLogout(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerState st = state(server);
        // A reviver or hauler leaving simply lets go.
        for (Downed d : st.downed.values()) {
            if (player.getUUID().equals(d.dragger)) {
                releaseDrag(player.serverLevel(), d, false);
            }
            if (player.getUUID().equals(d.reviver)) {
                d.revive.interrupt();
                d.reviver = null;
                st.dirty = true;
            }
        }
        st.lastDragPing.remove(player.getUUID());
        if (st.downed.containsKey(player.getUUID())) {
            Hearthstead.LOGGER.info("HS_REVIVE logout_while_down player={} -> bleed out",
                player.getGameProfile().getName());
            bleedOut(player);
        }
    }

    /**
     * Crash recovery: a player saved while down is treated as bled out at
     * login. The marker stays until the death is confirmed; if another mod
     * refuses both the bleed-out hit and the kill fallback, the player is
     * retried once per second ({@link ReviveRules#LOGIN_RECOVERY_RETRIES}
     * times) and, failing that, again at the next login.
     */
    public static void onLogin(ServerPlayer player) {
        if (!player.getPersistentData().getBoolean(PERSIST_KEY)) {
            return;
        }
        removeModifier(player, Attributes.MOVEMENT_SPEED, SLOW_ID);
        removeModifier(player, Attributes.JUMP_STRENGTH, NO_JUMP_ID);
        if (!recoverUncleanDown(player) && player.getServer() != null) {
            state(player.getServer()).pendingRecovery.put(player.getUUID(), 0);
        }
    }

    /** One recovery attempt. True when finished (marker cleared), false when the kill was refused. */
    static boolean recoverUncleanDown(ServerPlayer player) {
        ReviveRules.LoginRecovery step = ReviveRules.loginRecovery(
            player.getPersistentData().getBoolean(PERSIST_KEY), player.isAlive(),
            player.isCreative() || player.isSpectator());
        switch (step) {
            case NOTHING -> {
                return true;
            }
            case CLEAR_MARKER -> {
                player.getPersistentData().remove(PERSIST_KEY);
                return true;
            }
            default -> {
                Hearthstead.LOGGER.info("HS_REVIVE login_after_unclean_down player={} -> bleed out",
                    player.getGameProfile().getName());
                player.hurt(player.damageSources().source(BLEED_OUT), Float.MAX_VALUE);
                if (player.isAlive()) {
                    player.kill(); // same fallback as bleedOut
                }
                if (ReviveRules.markerMayClear(player.isAlive())) {
                    player.getPersistentData().remove(PERSIST_KEY);
                    return true;
                }
                Hearthstead.LOGGER.warn("HS_REVIVE login_recovery_refused player={} (marker kept, will retry)",
                    player.getGameProfile().getName());
                return false;
            }
        }
    }

    private static void retryRecoveries(MinecraftServer server, ServerState st) {
        if (st.pendingRecovery.isEmpty()) {
            return;
        }
        var it = st.pendingRecovery.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null || recoverUncleanDown(player)) {
                it.remove();
            } else if (e.getValue() + 1 >= ReviveRules.LOGIN_RECOVERY_RETRIES) {
                Hearthstead.LOGGER.warn("HS_REVIVE login_recovery_gave_up player={} (retried at next login)",
                    player.getGameProfile().getName());
                it.remove();
            } else {
                e.setValue(e.getValue() + 1);
            }
        }
    }

    private static void cleanup(ServerLevel level, ServerPlayer player, Downed d) {
        if (player.getForcedPose() == Pose.SWIMMING) {
            player.setForcedPose(null);
        }
        removeModifier(player, Attributes.MOVEMENT_SPEED, SLOW_ID);
        removeModifier(player, Attributes.JUMP_STRENGTH, NO_JUMP_ID);
        player.getPersistentData().remove(PERSIST_KEY);
        clearReviverPose(level.getServer(), d.reviver);
        d.reviver = null;
        if (d.dragger != null) {
            Entity dragger = findEntity(level.getServer(), d.dragger);
            if (dragger instanceof LivingEntity living) {
                removeModifier(living, Attributes.MOVEMENT_SPEED, DRAG_SLOW_ID);
            }
            d.dragger = null;
        }
        if (d.finisher != null && level.getEntity(d.finisher) instanceof Mob mob
            && mob.getTarget() == player) {
            mob.setTarget(null);
        }
        d.finisher = null;
    }

    // ------------------------------------------------------------------ damage

    /** Damage actually taken by a player: interrupts any revive they are part of. */
    public static void onDamaged(ServerPlayer player, float amount) {
        MinecraftServer server = player.getServer();
        if (server == null || amount <= 0.0F) {
            return;
        }
        ServerState st = state(server);
        for (Downed d : st.downed.values()) {
            boolean involved = player.getUUID().equals(d.reviver)
                || player.getUUID().equals(d.playerId) && d.reviver != null;
            if (involved && d.revive.active()) {
                interrupt(server, d);
            }
        }
    }

    private static void interrupt(MinecraftServer server, Downed d) {
        Entity reviver = findEntity(server, d.reviver);
        if (reviver instanceof ServerPlayer rp) {
            rp.displayClientMessage(Component.translatable("hearthstead.revive.interrupted")
                .withStyle(ChatFormatting.RED), true);
        }
        clearReviverPose(server, d.reviver);
        d.revive.interrupt();
        d.reviver = null;
        state(server).dirty = true;
    }

    /** True if {@code mob} is the raider allowed to go after this downed player. */
    public static boolean isFinisher(ServerPlayer downed, Entity mob) {
        Downed d = downedOf(downed);
        return d != null && mob.getUUID().equals(d.finisher);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        ServerState st;
        synchronized (STATES) {
            st = STATES.get(server);
        }
        long now = now(server);
        if (now % 20L == 0L) {
            if (st == null) {
                st = state(server);
            }
            scanRaids(server, st, now);
            retryRecoveries(server, st);
        }
        if (st == null) {
            return;
        }
        if (!st.downed.isEmpty()) {
            for (Downed d : new ArrayList<>(st.downed.values())) {
                tickOne(server, st, d, now);
            }
        }
        sync(server, st, now);
    }

    private static void scanRaids(MinecraftServer server, ServerState st, long now) {
        for (ServerLevel level : server.getAllLevels()) {
            SettlementSavedData data = SettlementSavedData.existing(level);
            if (data == null) {
                continue;
            }
            for (Settlement s : data.settlements.values()) {
                if (s == null) {
                    continue;
                }
                if (BlessingEffects.raidActive(s)) {
                    if (st.raidActiveNow.add(s.id)) {
                        st.tallies.put(s.id, ReviveTally.EMPTY); // a new raid starts a new report
                    }
                    st.lastRaidActive.put(s.id, now);
                } else {
                    st.raidActiveNow.remove(s.id);
                }
            }
        }
    }

    private static void tickOne(MinecraftServer server, ServerState st, Downed d, long now) {
        ServerPlayer player = server.getPlayerList().getPlayer(d.playerId);
        if (player == null) {
            st.downed.remove(d.playerId); // logout already handled
            st.dirty = true;
            return;
        }
        if (!player.isAlive()) {
            onDownedDeath(player);
            return;
        }
        ServerLevel level = player.serverLevel();
        if (player.getForcedPose() != Pose.SWIMMING) {
            player.setForcedPose(Pose.SWIMMING);
        }
        player.setSprinting(false);

        // Revive hold.
        if (d.reviver != null) {
            Entity reviver = level.getEntity(d.reviver);
            boolean valid = reviver instanceof LivingEntity living && living.isAlive()
                && living.distanceToSqr(player) <= ReviveRules.REVIVE_REACH_SQR
                && !(living instanceof Player p && isDowned(p));
            if (!valid) {
                clearReviverPose(server, d.reviver);
                d.revive.interrupt();
                d.reviver = null;
                st.dirty = true;
            } else {
                ReviveRules.Step step = d.revive.tick(now);
                if (step == ReviveRules.Step.COMPLETE) {
                    completeRevive(level, player, d, reviver);
                    return;
                }
                if (step == ReviveRules.Step.LAPSED) {
                    clearReviverPose(server, d.reviver);
                    d.reviver = null;
                    st.dirty = true;
                }
            }
        }

        // Bleed-out (paused while a revive is being held).
        // An expired timer whose death another mod canceled retries once per second.
        boolean expiredNow = d.bleed.tick(d.revive.active());
        if (expiredNow || d.bleed.expired() && (now - d.downedAt) % 20L == 0L) {
            Hearthstead.LOGGER.info("HS_REVIVE bleed_out player={} retry={}",
                player.getGameProfile().getName(), !expiredNow);
            bleedOut(player);
            if (!st.downed.containsKey(d.playerId)) {
                return;
            }
        }

        // Drag tether.
        if (d.dragger != null) {
            Entity dragger = level.getEntity(d.dragger);
            boolean valid = dragger instanceof LivingEntity living && living.isAlive()
                && living.distanceTo(player) <= ReviveRules.DRAG_BREAK_DISTANCE
                && !(living instanceof Player p && (isDowned(p) || p.isSpectator()));
            if (!valid) {
                releaseDrag(level, d, true);
            }
        }

        long age = now - d.downedAt;
        if (age > 0 && age % 20L == 0L) {
            offerRescuers(level, player, d);
        }
        if (age > 0 && age % FINISHER_ROLL_TICKS == 0L) {
            rollFinisher(level, player, d);
        }
    }

    private static void offerRescuers(ServerLevel level, ServerPlayer player, Downed d) {
        if (RESCUERS.isEmpty() || d.settlementId == null) {
            return;
        }
        for (DownedRescuer rescuer : RESCUERS) {
            try {
                if (rescuer.offer(level, player, d.settlementId, d.bleed.ticksLeft())) {
                    return;
                }
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("HS_REVIVE rescuer failed", failure);
            }
        }
    }

    private static void rollFinisher(ServerLevel level, ServerPlayer player, Downed d) {
        if (d.finisher != null) {
            Entity current = level.getEntity(d.finisher);
            if (current instanceof Mob mob && mob.isAlive()
                && mob.distanceTo(player) <= FINISHER_RANGE * 1.5D) {
                return;
            }
            d.finisher = null;
        }
        if (level.random.nextDouble() >= HearthsteadServerConfig.reviveFinisherChance()) {
            return;
        }
        RaiderEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (RaiderEntity raider : level.getEntitiesOfClass(RaiderEntity.class,
            player.getBoundingBox().inflate(FINISHER_RANGE),
            r -> r.isAlive() && !r.isScout())) {
            double dist = raider.distanceToSqr(player);
            if (dist < bestDist) {
                bestDist = dist;
                best = raider;
            }
        }
        if (best == null) {
            return;
        }
        d.finisher = best.getUUID();
        best.setTarget(player);
        Component warn = Component.translatable("hearthstead.revive.finisher", player.getDisplayName())
            .withStyle(ChatFormatting.GOLD);
        for (ServerPlayer other : level.players()) {
            if (other.distanceTo(player) <= ReviveRules.MARKER_RANGE) {
                other.displayClientMessage(warn, true);
            }
        }
        Hearthstead.LOGGER.info("HS_REVIVE finisher raider={} target={}", best.getUUID(),
            player.getGameProfile().getName());
    }

    // ------------------------------------------------------------------ sync

    private static void sync(MinecraftServer server, ServerState st, long now) {
        boolean anyReviving = false;
        for (Downed d : st.downed.values()) {
            anyReviving |= d.revive.active();
        }
        long every = anyReviving ? 2L : 10L;
        if (!st.dirty && (st.downed.isEmpty() || now - st.lastSync < every)) {
            return;
        }
        st.dirty = false;
        st.lastSync = now;
        Map<ServerLevel, List<ReviveStatePayload.Entry>> byLevel = new HashMap<>();
        for (Downed d : st.downed.values()) {
            ServerPlayer p = server.getPlayerList().getPlayer(d.playerId);
            if (p == null) {
                continue;
            }
            ServerLevel level = p.serverLevel();
            byLevel.computeIfAbsent(level, l -> new ArrayList<>()).add(new ReviveStatePayload.Entry(
                p.getId(), p.getUUID(), p.getGameProfile().getName(), p.getX(), p.getY(), p.getZ(),
                d.bleed.ticksLeft(), d.bleed.totalTicks(),
                entityId(level, d.reviver, d.revive.active()), d.revive.progressTicks(),
                d.revive.requiredTicks(), entityId(level, d.dragger, true)));
        }
        Set<ResourceKey<net.minecraft.world.level.Level>> sentNow = new HashSet<>();
        for (Map.Entry<ServerLevel, List<ReviveStatePayload.Entry>> e : byLevel.entrySet()) {
            sendToDimension(e.getKey(), new ReviveStatePayload(e.getValue()));
            sentNow.add(e.getKey().dimension());
        }
        for (ResourceKey<net.minecraft.world.level.Level> dim : st.sentNonEmpty) {
            if (!sentNow.contains(dim)) {
                ServerLevel level = server.getLevel(dim);
                if (level != null) {
                    sendToDimension(level, new ReviveStatePayload(List.of()));
                }
            }
        }
        st.sentNonEmpty.clear();
        st.sentNonEmpty.addAll(sentNow);
    }

    /** A player who changed dimension or just logged in gets the current picture next tick. */
    public static void markDirty(MinecraftServer server) {
        if (server != null) {
            state(server).dirty = true;
        }
    }

    private static int entityId(ServerLevel level, @Nullable UUID id, boolean active) {
        if (id == null || !active) {
            return ReviveStatePayload.NONE;
        }
        Entity e = level.getEntity(id);
        return e == null ? ReviveStatePayload.NONE : e.getId();
    }

    private static void event(ServerLevel level, int kind, ServerPlayer player) {
        sendToDimension(level,
            new ReviveEventPayload(kind, player.getId(), player.getX(), player.getY(), player.getZ()));
    }

    /**
     * Sends only to players whose connection negotiated the payload. A plain
     * sendToPlayersInDimension throws UnsupportedOperationException in the
     * server tick for any connection without the channel (GameTest mock
     * players, vanilla/proxy clients) and crashes the server (integration
     * captain, W1 26 Sep 06:28).
     */
    private static void sendToDimension(ServerLevel level,
                                        net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        for (ServerPlayer p : level.players()) {
            if (p.connection != null && net.neoforged.neoforge.network.registration.NetworkRegistry
                    .hasChannel(p.connection, payload.type().id())) {
                com.hearthstead.network.PayloadSend.toPlayer(p, payload);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static Entity findEntity(MinecraftServer server, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            return player;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static void clearReviverPose(MinecraftServer server, @Nullable UUID reviver) {
        if (reviver == null) {
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(reviver);
        if (p != null && p.getForcedPose() == Pose.CROUCHING) {
            p.setForcedPose(null);
        }
    }

    private static void addModifier(LivingEntity entity,
                                    net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    ResourceLocation id, double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && !instance.hasModifier(id)) {
            instance.addTransientModifier(new AttributeModifier(id, amount,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void removeModifier(LivingEntity entity,
                                       net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                       ResourceLocation id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    /** Server stop: forget everything for that server. */
    public static void onServerStopped(MinecraftServer server) {
        synchronized (STATES) {
            STATES.remove(server);
        }
        testBleedTicks = -1;
        testReviveTicks = -1;
        testGraceTicks = -1;
    }
}

package com.hearthstead.settlement.summon;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.SummonStatePayload;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.guard.FieldOrders;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * "Come to me." Any settler (soldier or civilian) can be summoned to a
 * player: soldiers run and arrive with attention + salute, civilians jog and
 * arrive with a nod. They then wait at the player's side, facing where the
 * player looks, until a new order or {@link #waitTicks()} passes; then they
 * simply resume their day (their work goals re-plan from their own saved
 * state; nothing is carried away or dropped: the summon only takes the
 * movement flag, exactly like an alarm or a meal does).
 *
 * <p>Transient, in memory per server (like the plaque {@code Summons}); a
 * restart forgets a summon. Gated by the {@code [features] guardCommands}
 * switch. Latest request wins. Refusals are always explained to the player.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class PlayerSummons {
    /** Game time of each player's last rally horn, so a squad summon sounds one call. */
    private static final Map<UUID, Long> HORN_AT = new HashMap<>();
    /** Waiting at the player's side lasts this long by default (60 s). */
    public static final int DEFAULT_WAIT_TICKS = 60 * 20;
    /** A summon that never arrives gives up after this long (2 min). */
    public static final int MAX_EN_ROUTE_TICKS = 120 * 20;
    public static final double ARRIVE_DISTANCE = 3.0D;
    /** Players may summon from their settlement's reach or from near the settler. */
    public static final double NEAR_SETTLER = 64.0D;
    private static final int RATE_LIMIT_TICKS = 4;
    private static final int TICK_INTERVAL = 10;

    /** Test seam: shorter waits in GameTests. */
    @Nullable static Integer waitOverride;
    /** Test seam: force the feature switch off/on in GameTests. */
    @Nullable static Boolean enabledOverride;

    public enum Refusal {
        NONE, DISABLED, NOT_ALLOWED, NOT_FOUND, OTHER_DIMENSION, NOT_SETTLER, NO_PERMISSION, ASLEEP, FLEEING,
        NO_ROUTE, TOO_FAST
    }

    public record Result(Refusal refusal, Component message) {
        public boolean accepted() { return refusal == Refusal.NONE; }
    }

    /** One live summon. */
    public static final class Entry {
        public final UUID player;
        public final String playerName;
        public final ResourceKey<Level> dimension;
        public final boolean soldier;
        final long issuedAt;
        long arrivedAt = -1L;

        Entry(UUID player, String playerName, ResourceKey<Level> dimension, boolean soldier, long now) {
            this.player = player;
            this.playerName = playerName;
            this.dimension = dimension;
            this.soldier = soldier;
            this.issuedAt = now;
        }

        public boolean arrived() { return arrivedAt >= 0L; }
    }

    private static final class State {
        final Map<UUID, Entry> entries = new HashMap<>();
        final Map<UUID, Long> lastRequest = new HashMap<>();
        final Map<UUID, Integer> sentHash = new HashMap<>();
    }

    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

    public static boolean enabled() {
        return enabledOverride != null ? enabledOverride : HearthsteadServerConfig.guardCommandsEnabled();
    }

    public static int waitTicks() {
        return waitOverride != null ? waitOverride : DEFAULT_WAIT_TICKS;
    }

    // ---------------------------------------------------------------- queries

    /** The live summon for this settler, or null. */
    @Nullable
    public static Entry active(SettlerEntity settler) {
        if (settler == null || !(settler.level() instanceof ServerLevel level) || !enabled()) return null;
        State state = STATES.get(level.getServer());
        if (state == null || state.entries.isEmpty()) return null;
        Entry entry = state.entries.get(settler.getUUID());
        return entry != null && entry.dimension.equals(level.dimension()) ? entry : null;
    }

    /** Map/HUD query: the player a settler is summoned to, or null. */
    @Nullable
    public static UUID summoner(ServerLevel level, UUID settlerId) {
        if (!enabled()) return null;
        State state = STATES.get(level.getServer());
        Entry entry = state == null ? null : state.entries.get(settlerId);
        return entry == null ? null : entry.player;
    }

    @Nullable
    public static ServerPlayer summoningPlayer(SettlerEntity settler, Entry entry) {
        if (!(settler.level() instanceof ServerLevel level)) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.player);
        return player != null && player.isAlive() && !player.isSpectator() && player.level() == level
            ? player : null;
    }

    // ---------------------------------------------------------------- request

    public static Result request(ServerPlayer player, int entityId, UUID settlerId) {
        if (player == null) return refuse(null, Refusal.NOT_ALLOWED, "");
        if (!enabled()) return refuse(player, Refusal.DISABLED, "");
        if (!player.isAlive() || player.isSpectator()) return refuse(player, Refusal.NOT_ALLOWED, "");
        ServerLevel level = player.serverLevel();
        State state = STATES.computeIfAbsent(level.getServer(), s -> new State());
        long now = level.getGameTime();
        Long last = state.lastRequest.get(player.getUUID());
        if (last != null && now - last < RATE_LIMIT_TICKS && now >= last) {
            return new Result(Refusal.TOO_FAST, Component.empty());
        }
        SettlerEntity settler = find(level, entityId, settlerId);
        if (settler == null) {
            for (ServerLevel other : level.getServer().getAllLevels()) {
                if (other != level && other.getEntity(settlerId) instanceof SettlerEntity elsewhere) {
                    return refuse(player, Refusal.OTHER_DIMENSION, elsewhere.getSettlerName());
                }
            }
            return refuse(player, Refusal.NOT_FOUND, "");
        }
        String name = settler.getSettlerName();
        Settlement settlement = settler.settlement();
        if (settlement == null || !settler.isBound() || settler.isTraveler()) {
            return refuse(player, Refusal.NOT_SETTLER, name);
        }
        Settlement home = FieldOrders.commandedSettlement(player);
        boolean member = home != null && home.id.equals(settlement.id)
            || player.distanceToSqr(settler) <= NEAR_SETTLER * NEAR_SETTLER;
        if (!member) return refuse(player, Refusal.NO_PERMISSION, name);
        if (settler.isSleeping()) return refuse(player, Refusal.ASLEEP, name);
        if (settler.getActivity() == SettlerActivity.FLEEING) return refuse(player, Refusal.FLEEING, name);

        state.lastRequest.put(player.getUUID(), now);
        boolean soldier = FieldOrders.roleOf(settler).isPresent();
        // A summon is the newest order: it replaces any field order this soldier held.
        FieldOrders.release(settler);
        state.entries.put(settler.getUUID(), new Entry(player.getUUID(), player.getGameProfile().getName(),
            level.dimension(), soldier, now));
        state.sentHash.remove(player.getUUID());
        settler.getNavigation().stop();
        level.playSound(null, settler.blockPosition(),
            soldier ? ModSounds.COMMAND_ACK.get() : ModSounds.SETTLER_HM.get(), SoundSource.NEUTRAL,
            soldier ? 1.0F : 0.8F, soldier ? 1.0F : 1.05F);
        // One rallying horn call from the player per burst of soldier summons (sound pass).
        Long lastHorn = HORN_AT.get(player.getUUID());
        if (soldier && (lastHorn == null || now - lastHorn > 60L)) {
            HORN_AT.put(player.getUUID(), now);
            level.playSound(null, player.blockPosition(), ModSounds.SUMMON_HORN.get(),
                SoundSource.PLAYERS, 1.2F, 1.0F);
        }
        int blocks = (int) Math.round(Math.sqrt(player.distanceToSqr(settler)));
        Component line = Component.translatable(soldier ? "hearthstead.summon.coming.soldier"
            : "hearthstead.summon.coming", name, blocks);
        player.displayClientMessage(line, true);
        return new Result(Refusal.NONE, line);
    }

    @Nullable
    private static SettlerEntity find(ServerLevel level, int entityId, UUID settlerId) {
        Entity byId = entityId >= 0 ? level.getEntity(entityId) : null;
        if (byId instanceof SettlerEntity settler && settler.getUUID().equals(settlerId) && settler.isAlive()) {
            return settler;
        }
        Entity byUuid = settlerId == null ? null : level.getEntity(settlerId);
        return byUuid instanceof SettlerEntity settler && settler.isAlive() ? settler : null;
    }

    private static Result refuse(@Nullable ServerPlayer player, Refusal refusal, String name) {
        Component line = Component.translatable("hearthstead.summon.refused."
            + refusal.name().toLowerCase(java.util.Locale.ROOT), name);
        if (player != null && refusal != Refusal.TOO_FAST) {
            player.displayClientMessage(line.copy().withStyle(ChatFormatting.RED), true);
        }
        return new Result(refusal, line);
    }

    // ------------------------------------------------------- goal callbacks

    /** The goal proved there is no way to the player: end with a clear reason. */
    public static void unreachable(SettlerEntity settler) {
        Entry entry = active(settler);
        if (entry == null) return;
        ServerPlayer player = summoningPlayer(settler, entry);
        end(settler);
        if (player != null) {
            player.displayClientMessage(Component.translatable("hearthstead.summon.refused.no_route",
                settler.getSettlerName()).withStyle(ChatFormatting.RED), true);
        }
    }

    /** Called once when the settler first reaches the player's side. */
    public static void arrived(SettlerEntity settler) {
        Entry entry = active(settler);
        if (entry == null || entry.arrived() || !(settler.level() instanceof ServerLevel level)) return;
        entry.arrivedAt = level.getGameTime();
        level.broadcastEntityEvent(settler, entry.soldier ? SettlerEntity.EV_GUARD_SALUTE : SettlerEntity.EV_GUARD_NOD);
        State state = STATES.get(level.getServer());
        if (state != null) state.sentHash.remove(entry.player);
    }

    /** Ends a summon (new order, timeout, lost player); the settler resumes its day. */
    public static void end(SettlerEntity settler) {
        if (!(settler.level() instanceof ServerLevel level)) return;
        State state = STATES.get(level.getServer());
        if (state == null) return;
        Entry removed = state.entries.remove(settler.getUUID());
        if (removed != null) state.sentHash.remove(removed.player);
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % TICK_INTERVAL != 0) return;
        State state = STATES.get(server);
        if (state == null) return;
        if (!enabled()) {
            clearAll(server, state);
            return;
        }
        for (Iterator<Map.Entry<UUID, Entry>> it = state.entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Entry> row = it.next();
            Entry entry = row.getValue();
            ServerLevel level = server.getLevel(entry.dimension);
            SettlerEntity settler = level != null && level.getEntity(row.getKey()) instanceof SettlerEntity s
                && s.isAlive() ? s : null;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.player);
            long now = level == null ? 0L : level.getGameTime();
            String name = settler == null ? "" : settler.getSettlerName();
            Component notice = null;
            if (settler == null) {
                notice = Component.translatable("hearthstead.summon.ended.lost");
            } else if (player == null || !player.isAlive() || player.level() != level) {
                notice = null; // player left: quietly resume
            } else if (entry.arrived() && now - entry.arrivedAt >= waitTicks()) {
                notice = Component.translatable("hearthstead.summon.ended.timeout", name);
            } else if (!entry.arrived() && now - entry.issuedAt >= MAX_EN_ROUTE_TICKS) {
                notice = Component.translatable("hearthstead.summon.ended.no_route", name);
            } else {
                continue;
            }
            it.remove();
            state.sentHash.remove(entry.player);
            if (notice != null && player != null) player.displayClientMessage(notice, true);
        }
        sync(server, state);
    }

    private static void clearAll(MinecraftServer server, State state) {
        if (state.entries.isEmpty() && state.sentHash.isEmpty()) return;
        state.entries.clear();
        sync(server, state);
    }

    private static void sync(MinecraftServer server, State state) {
        Map<UUID, List<SummonStatePayload.Row>> rows = new HashMap<>();
        for (Map.Entry<UUID, Entry> row : state.entries.entrySet()) {
            Entry entry = row.getValue();
            ServerLevel level = server.getLevel(entry.dimension);
            if (level == null || !(level.getEntity(row.getKey()) instanceof SettlerEntity settler)) continue;
            rows.computeIfAbsent(entry.player, p -> new ArrayList<>()).add(
                new SummonStatePayload.Row(settler.getId(), settler.getUUID(), entry.arrived()));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.connection == null
                || !NetworkRegistry.hasChannel(player.connection, SummonStatePayload.TYPE.id())) continue;
            List<SummonStatePayload.Row> mine = rows.getOrDefault(player.getUUID(), List.of());
            int hash = mine.hashCode();
            Integer last = state.sentHash.get(player.getUUID());
            if (last != null && last == hash) continue;
            if (last == null && mine.isEmpty()) {
                state.sentHash.put(player.getUUID(), hash);
                continue;
            }
            state.sentHash.put(player.getUUID(), hash);
            com.hearthstead.network.PayloadSend.toPlayer(player, new SummonStatePayload(mine));
        }
        state.sentHash.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    // ----------------------------------------------------------- test seams

    public static void resetForTests(MinecraftServer server) {
        STATES.remove(server);
        waitOverride = null;
        enabledOverride = null;
    }

    public static void setWaitTicksForTests(@Nullable Integer ticks) {
        waitOverride = ticks;
    }

    public static void setEnabledForTests(@Nullable Boolean enabled) {
        enabledOverride = enabled;
    }

    private PlayerSummons() {
    }
}

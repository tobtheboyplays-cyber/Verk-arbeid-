package com.hearthstead.settlement.defense;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The settlement's physical alarm: a plain vanilla bell inside the claim.
 *
 * <p>A player ringing any bell inside a settlement (by hand or by an owned
 * projectile) raises a bounded, persisted ALARM through the existing
 * {@link Settlement#alertUntilGameTime}/{@link Settlement#alertPos} pair.
 * Every existing alert consumer then does the rest: civilians run
 * {@code SettlerPanicGoal} to their home or the nearest valid housing interior,
 * unordered Guards/Archers run {@code GuardRespondToAlertGoal} to the bell or
 * threat, and posted defenders keep their authored posts.</p>
 *
 * <p>When a raid warning or arrival is observed and the settlement still has a
 * living martial resident, that defender rings the settlement's bell
 * automatically. A raid-driven alarm is cleared ("all clear") as soon as the
 * raid resolves; otherwise the alarm simply times out.</p>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class AlarmBell {
    /** Two in-game minutes of ALARM per ring; re-ringing extends it. */
    public static final int ALARM_TICKS = 2400;
    /** A bell this close to the Hearth is the preferred auto-ring bell. */
    public static final int HEARTH_BELL_RADIUS = 16;
    private static final int RAID_SCAN_INTERVAL = 20;
    private static final double THREAT_SCAN_RADIUS = 32.0D;

    /** Last bell each settlement actually rang (runtime-only cache, re-validated before use). */
    private static final Map<UUID, BlockPos> KNOWN_BELLS = new HashMap<>();
    /** Last raid stage auto-signalled per settlement: 0 none, 1 warning, 2 active. */
    private static final Map<UUID, Integer> RAID_STAGE = new HashMap<>();
    /** Settlements whose current alarm belongs to a raid and ends with it. */
    private static final Map<UUID, Boolean> RAID_BOUND = new HashMap<>();

    private AlarmBell() {}

    /** Server stop: drop runtime caches so a reloaded world re-signals its raid state. */
    public static void clear() {
        KNOWN_BELLS.clear();
        RAID_STAGE.clear();
        RAID_BOUND.clear();
    }

    /** Server hook: every real bell ring emits BLOCK_CHANGE with the ringer as cause. */
    @SubscribeEvent
    public static void onGameEvent(VanillaGameEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || !event.getVanillaEvent().is(GameEvent.BLOCK_CHANGE)) {
            return;
        }
        Player ringer = ringingPlayer(event.getCause());
        if (ringer == null) return;
        BlockPos pos = BlockPos.containing(event.getEventPosition());
        if (!level.hasChunkAt(pos) || !(level.getBlockState(pos).getBlock() instanceof BellBlock)) {
            return;
        }
        Settlement s = settlementFor(level, pos);
        if (s == null) return;
        ring(level, s, pos, false);
    }

    @Nullable
    private static Player ringingPlayer(@Nullable Entity cause) {
        if (cause instanceof Player player) return player;
        if (cause instanceof Projectile projectile && projectile.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    /** The settlement whose claim contains this bell, nearest centre first. */
    @Nullable
    public static Settlement settlementFor(ServerLevel level, BlockPos bell) {
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null) return null;
        return data.settlements.values().stream()
            .filter(s -> s != null && s.center != null && s.inside(bell))
            .min(Comparator.comparingDouble(s -> s.center.distSqr(bell)))
            .orElse(null);
    }

    /** True when {@code pos} holds a bell that counts as this settlement's alarm bell. */
    public static boolean isAlarmBell(ServerLevel level, Settlement s, BlockPos pos) {
        return s != null && pos != null && s.inside(pos) && level.hasChunkAt(pos)
            && level.getBlockState(pos).getBlock() instanceof BellBlock;
    }

    /**
     * Enters (or extends) ALARM. Never shortens an existing alert and never
     * replaces a real threat position already reported while the alarm runs.
     */
    public static void ring(ServerLevel level, Settlement s, BlockPos bell, boolean raidDriven) {
        long now = level.getGameTime();
        boolean fresh = !s.alertActive(now);
        s.alertUntilGameTime = Math.max(s.alertUntilGameTime, now + ALARM_TICKS);
        if (fresh || s.alertPos == null) {
            BlockPos threat = raidDriven ? nearestRaider(level, s, bell) : null;
            s.alertPos = (threat != null ? threat : bell).immutable();
        }
        KNOWN_BELLS.put(s.id, bell.immutable());
        if (raidDriven) {
            RAID_BOUND.put(s.id, true);
        } else if (fresh) {
            RAID_BOUND.remove(s.id);
        }
        SettlementManager.data(level).setDirty();
        actionBar(level, s, Component.translatable(raidDriven
            ? "hearthstead.message.alarm_bell_raid" : "hearthstead.message.alarm_bell", s.name));
    }

    /** Ends ALARM now (raid resolved). Civilians leave shelter on their next goal tick. */
    public static void allClear(ServerLevel level, Settlement s) {
        RAID_BOUND.remove(s.id);
        long now = level.getGameTime();
        if (!s.alertActive(now)) return;
        s.alertUntilGameTime = now;
        SettlementManager.data(level).setDirty();
        actionBar(level, s, Component.translatable("hearthstead.message.alarm_all_clear", s.name));
    }

    /**
     * Nearest living hostile around {@code from} (bounded box), so a sheltering
     * civilian can pick a route that keeps clear of it.
     */
    @Nullable
    public static BlockPos nearestKnownThreat(ServerLevel level, Settlement s, BlockPos from) {
        AABB box = new AABB(from).inflate(THREAT_SCAN_RADIUS);
        return level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, box,
                m -> m.isAlive() && (!(m instanceof RaiderEntity raider)
                    || raider.settlementId() == null || s.id.equals(raider.settlementId())))
            .stream().min(Comparator.comparingDouble(m -> m.blockPosition().distSqr(from)))
            .map(m -> m.blockPosition().immutable()).orElse(null);
    }

    @Nullable
    private static BlockPos nearestRaider(ServerLevel level, Settlement s, BlockPos from) {
        AABB box = new AABB(s.center).inflate(s.radius + THREAT_SCAN_RADIUS);
        return level.getEntitiesOfClass(RaiderEntity.class, box,
                r -> r.isAlive() && s.id.equals(r.settlementId()))
            .stream().min(Comparator.comparingDouble(r -> r.blockPosition().distSqr(from)))
            .map(r -> r.blockPosition().immutable()).orElse(null);
    }

    /**
     * The bell a defender would ring: the last bell rung here if still valid,
     * else the nearest bell within {@link #HEARTH_BELL_RADIUS} of the Hearth,
     * else a bell inside a valid registered building. Bounded block scan.
     */
    @Nullable
    public static BlockPos findBell(ServerLevel level, Settlement s) {
        BlockPos known = KNOWN_BELLS.get(s.id);
        if (known != null && isAlarmBell(level, s, known)) return known;
        BlockPos best = scan(level, s, s.center.offset(-HEARTH_BELL_RADIUS, -6, -HEARTH_BELL_RADIUS),
            s.center.offset(HEARTH_BELL_RADIUS, 10, HEARTH_BELL_RADIUS), s.center);
        if (best == null) {
            for (Building b : s.buildings) {
                if (b == null || !b.valid || b.bounds == null) continue;
                long volume = (long) b.bounds.getXSpan() * b.bounds.getYSpan() * b.bounds.getZSpan();
                if (volume > 16_384L) continue;
                best = scan(level, s, new BlockPos(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ()),
                    new BlockPos(b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ()), s.center);
                if (best != null) break;
            }
        }
        if (best != null) KNOWN_BELLS.put(s.id, best);
        return best;
    }

    @Nullable
    private static BlockPos scan(ServerLevel level, Settlement s, BlockPos min, BlockPos max, BlockPos origin) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(p) || !(level.getBlockState(p).getBlock() instanceof BellBlock)
                || !s.inside(p)) {
                continue;
            }
            double d = p.distSqr(origin);
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Raid warning/arrival watcher: a living defender rings the bell; raid end sounds the all clear. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % RAID_SCAN_INTERVAL != 0) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null || data.settlements.isEmpty()) return;
        for (Settlement s : new ArrayList<>(data.settlements.values())) {
            if (s == null || s.center == null) continue;
            int stage = s.recurringRaidRun.isActive() ? 2 : s.pendingRaid != null ? 1 : 0;
            int previous = RAID_STAGE.getOrDefault(s.id, 0);
            if (stage == 0) {
                RAID_STAGE.remove(s.id);
                if (previous > 0 && RAID_BOUND.containsKey(s.id)) allClear(level, s);
                continue;
            }
            if (stage <= previous) continue;
            // Record the stage only once a defender could act on it: a raid
            // that starts while every Guard/Archer is unloaded must still
            // ring the bell when one loads, not be silently marked as done.
            SettlerEntity defender = livingDefender(level, s);
            if (defender == null || !level.hasChunkAt(s.center)) continue;
            RAID_STAGE.put(s.id, stage);
            BlockPos bell = findBell(level, s);
            if (bell == null) continue;
            if (level.getBlockState(bell).getBlock() instanceof BellBlock block) {
                // Cause is the defender, never a player, so this does not re-enter onGameEvent.
                block.attemptToRing(defender, level, bell, null);
            }
            ring(level, s, bell, true);
        }
    }

    @Nullable
    private static SettlerEntity livingDefender(ServerLevel level, Settlement s) {
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, s)) {
            if (settler.isAlive() && settler.getProfession().martial()) return settler;
        }
        return null;
    }

    private static void actionBar(ServerLevel level, Settlement s, Component msg) {
        double range = s.radius + 32;
        List<ServerPlayer> players = level.players();
        for (ServerPlayer p : players) {
            if (p.blockPosition().distSqr(s.center) <= range * range) {
                p.displayClientMessage(msg, true);
            }
        }
    }

    /** Test/QA seam: forget runtime caches for one settlement. */
    public static void forget(UUID settlementId) {
        KNOWN_BELLS.remove(settlementId);
        RAID_STAGE.remove(settlementId);
        RAID_BOUND.remove(settlementId);
    }
}

package com.hearthstead.util;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * QA decision-trace recorder. Active only with
 * {@code -Dhearthstead.qa.trace=true} (set by tools/hearthstead-qa behavior);
 * zero overhead otherwise. One JSONL line per settler per sample tick,
 * consumed by qa/scripts/analyze_trace.py.
 */
public final class QaTrace {
    public static final boolean ENABLED = Boolean.getBoolean("hearthstead.qa.trace");

    /**
     * Narrow co-op-only tracing. It does not enable the periodic behavior
     * samples, so the capacity measurement retains its normal I/O profile.
     */
    private static final boolean COOP_DEATHS_ENABLED =
        Boolean.getBoolean("hearthstead.qa.coopDeaths");
    private static final int MAX_COOP_DEATH_RECEIPTS = 32;
    /** One failed food-contact probe per settler per ten seconds of QA time. */
    private static final long HEARTH_APPROACH_MISS_INTERVAL = 200L;

    // Weak server keys scope the cap to one QA server without retaining a
    // stopped world across a later run in the same JVM.
    private static final Map<MinecraftServer, Integer> coopDeathReceipts =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SettlerEntity, Long> lastHearthApproachMiss =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static BufferedWriter writer;

    public static void record(SettlerEntity settler) {
        if (!ENABLED) {
            return;
        }
        // Observe the existing route without requesting a new path or loading
        // chunks. Position alone cannot distinguish a bad node and collision.
        var navigation = settler.getNavigation();
        var route = navigation.getPath();
        int nextIndex = route == null ? -1 : route.getNextNodeIndex();
        int nodeCount = route == null ? 0 : route.getNodeCount();
        var next = route != null && nextIndex >= 0 && nextIndex < nodeCount
            ? route.getNodePos(nextIndex) : null;
        var level = settler.level();
        String nextFeet = next != null && level.isLoaded(next)
            ? level.getBlockState(next).toString() : "unavailable";
        String nextHead = next != null && level.isLoaded(next.above())
            ? level.getBlockState(next.above()).toString() : "unavailable";
        String line = String.format(java.util.Locale.ROOT,
            "{\"tick\":%d,\"uuid\":\"%s\",\"name\":\"%s\",\"activity\":\"%s\","
                + "\"profession\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,"
                + "\"navDone\":%b,\"hunger\":%.1f,\"energy\":%.1f,\"morale\":%.1f,"
                + "\"sleeping\":%b,\"bag\":%d}%n",
            settler.level().getGameTime(), settler.getUUID(),
            json(settler.getSettlerName()),
            settler.getActivity().name(), settler.getProfession().name(),
            settler.getX(), settler.getY(), settler.getZ(),
            navigation.isDone(),
            settler.getHunger(), settler.getEnergy(), settler.getMorale(),
            settler.isSleeping(), bagCount(settler));
        write(line);
        if (!navigation.isDone()) {
            // Keep the strict liveness sample schema unchanged. Additional
            // route observations use the existing diagnostic-event channel.
            event(settler, "navigation_snapshot", String.format(java.util.Locale.ROOT,
                "target=%s;next=%s;index=%d;count=%d;canReach=%b;"
                    + "feet=%s;head=%s;horizontalCollision=%b;onGround=%b;"
                    + "entityBlock=%s;entityFeet=%s;entityHead=%s;"
                    + "entityFluid=%s;inWater=%b;canFloat=%b",
                route == null ? "none" : route.getTarget().toShortString(),
                next == null ? "none" : next.toShortString(),
                nextIndex, nodeCount, route != null && route.canReach(),
                nextFeet, nextHead, settler.horizontalCollision, settler.onGround(),
                settler.blockPosition().toShortString(),
                level.getBlockState(settler.blockPosition()).toString(),
                level.getBlockState(settler.blockPosition().above()).toString(),
                level.getFluidState(settler.blockPosition()).toString(),
                settler.isInWater(), navigation.canFloat()));
        }
    }

    /**
     * Sparse decision event for a QA-only investigation. Callers should
     * still guard expensive detail construction with {@link #ENABLED}; this
     * method itself is a no-op in ordinary worlds.
     */
    public static void event(SettlerEntity settler, String event, String detail) {
        if (!ENABLED) {
            return;
        }
        eventLine(settler, event, detail);
    }

    /**
     * Cheap eligibility check used before a caller constructs a diagnostic
     * detail string. It deliberately does not consume the interval: only a
     * confirmed failed probe may stamp the per-settler receipt.
     */
    public static boolean shouldRecordHearthApproachMiss(SettlerEntity settler) {
        if (!ENABLED || settler.getHunger() >= 40.0F) {
            return false;
        }
        synchronized (lastHearthApproachMiss) {
            Long previous = lastHearthApproachMiss.get(settler);
            return previous == null || settler.level().getGameTime() - previous
                >= HEARTH_APPROACH_MISS_INTERVAL;
        }
    }

    /**
     * Emits the otherwise-hidden reason that a hungry settler received no
     * physical Hearth-contact route. This is trace-opt-in and rate-limited,
     * so ordinary worlds and repeated 40-tick meal repaths stay silent.
     */
    public static void recordHearthApproachMiss(SettlerEntity settler, String detail) {
        if (!ENABLED || settler.getHunger() >= 40.0F) {
            return;
        }
        long now = settler.level().getGameTime();
        synchronized (lastHearthApproachMiss) {
            Long previous = lastHearthApproachMiss.get(settler);
            if (previous != null && now - previous < HEARTH_APPROACH_MISS_INTERVAL) {
                return;
            }
            lastHearthApproachMiss.put(settler, now);
        }
        eventLine(settler, "HEARTH_APPROACH_MISS", detail);
    }

    /**
     * Observes an already-committed co-op member death. The caller invokes
     * this only after {@code super.die(cause)} has left the entity dead.
     */
    public static void recordCoopDeath(SettlerEntity settler, DamageSource cause) {
        if (!COOP_DEATHS_ENABLED || !settler.getTags().contains("hsqa_member")
            || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        int receipt;
        MinecraftServer server = level.getServer();
        synchronized (coopDeathReceipts) {
            int used = coopDeathReceipts.getOrDefault(server, 0);
            if (used >= MAX_COOP_DEATH_RECEIPTS) {
                return;
            }
            receipt = used + 1;
            coopDeathReceipts.put(server, receipt);
        }
        Entity attacker = cause.getEntity();
        Entity direct = cause.getDirectEntity();
        eventLine(settler, "COOP_SETTLER_DEATH", String.format(java.util.Locale.ROOT,
            "receipt=%d;damageType=%s;msgId=%s;attacker=%s;direct=%s",
            receipt, damageType(cause), cause.getMsgId(), entityIdentity(attacker),
            entityIdentity(direct)));
    }

    private static String damageType(DamageSource cause) {
        return cause.typeHolder().unwrapKey()
            .map(key -> key.location().toString())
            .orElse("unregistered:" + cause.getMsgId());
    }

    private static String entityIdentity(Entity entity) {
        return entity == null ? "none" : entity.getType().builtInRegistryHolder()
            .key().location() + "/" + entity.getUUID();
    }

    private static void eventLine(SettlerEntity settler, String event, String detail) {
        String line = String.format(java.util.Locale.ROOT,
            "{\"tick\":%d,\"uuid\":\"%s\",\"name\":\"%s\","
                + "\"event\":\"%s\",\"activity\":\"%s\","
                + "\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"detail\":\"%s\"}%n",
            settler.level().getGameTime(), settler.getUUID(),
            json(settler.getSettlerName()), json(event),
            settler.getActivity().name(), settler.getX(), settler.getY(),
            settler.getZ(), json(detail));
        write(line);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private static int bagCount(SettlerEntity settler) {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    private static synchronized void write(String line) {
        try {
            if (writer == null) {
                // The QA controller passes an absolute path so it never has
                // to guess which directory the game forked into.
                Path path = Path.of(System.getProperty(
                    "hearthstead.qa.traceFile", "hearthstead-trace.jsonl"))
                    .toAbsolutePath();
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        writer.flush();
                        writer.close();
                    } catch (IOException ignored) {
                        // shutdown; nothing to recover
                    }
                }));
            }
            writer.write(line);
            writer.flush();
        } catch (IOException e) {
            // QA-only path; never let tracing break the game.
        }
    }

    private QaTrace() {
    }
}

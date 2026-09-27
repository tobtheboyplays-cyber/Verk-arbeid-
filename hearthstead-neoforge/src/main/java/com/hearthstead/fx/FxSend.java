package com.hearthstead.fx;

import com.hearthstead.network.FxPayload;
import com.hearthstead.network.PayloadSend;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Server side of the FX pipeline: one {@link FxPayload} per moment, to each
 * player within the effect's range whose connection negotiated the channel
 * ({@link PayloadSend}: never a mock, fake or vanilla client, never a throw
 * inside a tick). No particles are sent per particle.
 *
 * <p>Same-tick dedupe: two code paths reporting the same moment at the same
 * block on the same tick (a legacy and a v3 tech-learn hook) collapse into
 * one send.
 */
public final class FxSend {
    private static final Map<Long, Long> LAST = new HashMap<>();

    private FxSend() {
    }

    /** Sends at a point. Returns how many players got the payload. */
    public static int at(ServerLevel level, FxEffect effect, double x, double y, double z) {
        return send(level, effect, x, y, z, FxPayload.NO_ENTITY, 0, null);
    }

    /** Sends for an entity (the client follows it while loaded). */
    public static int on(Entity entity, FxEffect effect, int arg) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return 0;
        }
        return send(level, effect, entity.getX(), entity.getY(), entity.getZ(), entity.getId(), arg, null);
    }

    /** The general form; {@code box} may be null. */
    public static synchronized int send(ServerLevel level, FxEffect effect, double x, double y, double z,
                                        int entityId, int arg, @Nullable BoundingBox box) {
        if (level == null || effect == null || !effect.networked()) {
            return 0;
        }
        long now = level.getGameTime();
        long key = BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) * 31L
            + effect.wireId() * 1_000_003L + level.dimension().location().hashCode();
        Long previous = LAST.get(key);
        if (previous != null && previous == now) {
            return 0;
        }
        if (LAST.size() > 256) {
            LAST.values().removeIf(t -> t != now);
        }
        LAST.put(key, now);
        int[] boxArray = box == null ? null : new int[] {box.minX(), box.minY(), box.minZ(),
            box.maxX(), box.maxY(), box.maxZ()};
        FxPayload payload = new FxPayload(effect.wireId(), x, y, z, entityId, arg, boxArray);
        double rangeSq = effect.range() * effect.range();
        int sent = 0;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= rangeSq && PayloadSend.toPlayer(player, payload)) {
                sent++;
            }
        }
        return sent;
    }

    /** GameTest hook: forget dedupe state. */
    public static synchronized void resetForTests() {
        LAST.clear();
    }
}

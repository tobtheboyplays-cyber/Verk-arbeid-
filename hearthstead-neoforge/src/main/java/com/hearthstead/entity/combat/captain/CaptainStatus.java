package com.hearthstead.entity.combat.captain;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who the hero Captain is (owner decision, option C + field promotion):
 * the settlement's computed Captain ({@link GuardRank#captainOf}: its
 * highest-ranked living Guard) once he is at least a {@link GuardRank#SERGEANT}
 * AND the settlement has learned {@code captains_commission}. Exactly one per
 * settlement by construction. Every hero behaviour asks {@link #isHero} and
 * nothing else.
 */
public final class CaptainStatus {
    public static final String COMMISSION_NODE = "captains_commission";
    public static final GuardRank MIN_RANK = GuardRank.SERGEANT;
    private static final int CACHE_TICKS = 20;

    private record Cached(@Nullable UUID captain, long at) {
    }

    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    /** GameTest seam: settlements that count as commissioned without the node. */
    private static final Set<UUID> TEST_COMMISSIONED = ConcurrentHashMap.newKeySet();

    private CaptainStatus() {
    }

    public static boolean isHero(@Nullable SettlerEntity settler) {
        if (settler == null || !CaptainConfig.enabled() || !settler.isAlive()
            || !(settler.level() instanceof ServerLevel level)
            || settler.getProfession() != Profession.GUARD
            || !GuardRank.of(settler).atLeast(MIN_RANK)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return settlement != null && commissioned(level, settlement)
            && settler.getUUID().equals(captainId(level, settlement));
    }

    /** The settlement's computed Captain (hero or not), cached for a second. */
    @Nullable
    public static UUID captainId(ServerLevel level, Settlement settlement) {
        long now = level.getGameTime();
        Cached c = CACHE.get(settlement.id);
        if (c == null || now - c.at() >= CACHE_TICKS || now < c.at()) {
            SettlerEntity best = GuardRank.captainOf(SettlementManager.loadedMembers(level, settlement));
            c = new Cached(best == null ? null : best.getUUID(), now);
            CACHE.put(settlement.id, c);
        }
        return c.captain();
    }

    @Nullable
    public static SettlerEntity hero(ServerLevel level, Settlement settlement) {
        UUID id = captainId(level, settlement);
        return id != null && level.getEntity(id) instanceof SettlerEntity s && isHero(s) ? s : null;
    }

    public static boolean commissioned(ServerLevel level, Settlement settlement) {
        if (TEST_COMMISSIONED.contains(settlement.id)) {
            return true;
        }
        try {
            return Development.has(level, settlement, COMMISSION_NODE);
        } catch (RuntimeException unknownNode) {
            return false;
        }
    }

    /** Forget the cached captain (a death, a hire, a test). */
    public static void invalidate(UUID settlementId) {
        CACHE.remove(settlementId);
    }

    public static void commissionForTests(UUID settlementId, boolean on) {
        if (on) {
            TEST_COMMISSIONED.add(settlementId);
        } else {
            TEST_COMMISSIONED.remove(settlementId);
        }
        invalidate(settlementId);
    }
}

package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Settlement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends one line to every player near a settlement.
 *
 * <p>{@code SettlementManager.broadcast} already does exactly this, but it
 * is {@code private} and this slice's ownership is scoped to
 * {@code settlement/raid/**} plus a short explicit list of entity/render/
 * gametest files -- {@code SettlementManager.java} is not on that list, so
 * it must not be edited to add a public entry point. This mirrors its range
 * and delivery rule exactly (radius + 32 blocks, an unfiltered
 * {@code displayClientMessage}) so a player cannot tell which system sent a
 * given line -- the telegraph omen, the morning defense report, and
 * "the settlement of X has been founded" all read the same way.
 */
public final class RaidBroadcast {

    /** How far past the settlement edge a line still reaches, in blocks. */
    private static final int RANGE_MARGIN = 32;

    public static void send(ServerLevel level, Settlement settlement, Component message) {
        double range = settlement.radius + RANGE_MARGIN;
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(settlement.center) <= range * range) {
                p.displayClientMessage(message, false);
            }
        }
    }

    private RaidBroadcast() {
    }
}

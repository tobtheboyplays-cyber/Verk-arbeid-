package com.hearthstead.finisher;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player execution counts, the raw material for the raid report and MVP
 * (built later). Kept in the player's persistent data so they survive
 * relogs and deaths ({@code Player#getPersistentData} is copied on respawn
 * by NeoForge only for the {@code PlayerPersisted} sub-tag, which is where
 * these live).
 *
 * <ul>
 *   <li>{@code total}: every execution this player took part in;</li>
 *   <li>{@code doubles}: co-op double executions;</li>
 *   <li>{@code raid}: since the raid lane last called {@link #resetRaidCount}.</li>
 * </ul>
 */
public final class FinisherStats {
    public static final String ROOT = "hearthstead_finishers";

    private FinisherStats() {
    }

    public static void record(Player player, boolean doubleExecution) {
        if (player == null) {
            return;
        }
        CompoundTag tag = tag(player);
        tag.putInt("total", tag.getInt("total") + 1);
        tag.putInt("raid", tag.getInt("raid") + 1);
        if (doubleExecution) {
            tag.putInt("doubles", tag.getInt("doubles") + 1);
        }
    }

    public static int total(Player player) {
        return tag(player).getInt("total");
    }

    public static int doubles(Player player) {
        return tag(player).getInt("doubles");
    }

    public static int raidCount(Player player) {
        return tag(player).getInt("raid");
    }

    /** Raid report hook: call when a raid starts (or after reading the counts). */
    public static void resetRaidCount(Player player) {
        tag(player).putInt("raid", 0);
    }

    private static CompoundTag tag(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (!player.getPersistentData().contains(Player.PERSISTED_NBT_TAG)) {
            player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        }
        CompoundTag mine = persisted.getCompound(ROOT);
        if (!persisted.contains(ROOT)) {
            persisted.put(ROOT, mine);
        }
        return mine;
    }
}

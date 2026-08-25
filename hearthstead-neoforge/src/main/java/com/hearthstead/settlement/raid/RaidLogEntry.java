package com.hearthstead.settlement.raid;

import net.minecraft.nbt.CompoundTag;

/**
 * One finished raid, as the settlement will remember it the morning after.
 *
 * <p>The scar half of D-A3-8. MineColonies leaves one chat line and a day of
 * mourning, and its own feature requests (#113, #129) are, at root, asking
 * for a raid that leaves a mark. A capped log of what was actually taken,
 * who was actually hurt, and what the threat reads as afterward is that
 * mark -- readable in the Tingbok later, not gone the moment the chat
 * message scrolls off screen.
 *
 * <p>Deliberately a plain record of already-known facts (nothing here is
 * itself gameplay state anything else reads back) so it costs nothing to
 * keep a short history of them, capped by {@link RaidDirector#MAX_RAID_LOG}
 * the same way the enemy gallery is capped -- a settlement remembers its
 * history, not an unbounded diary.
 */
public record RaidLogEntry(long night, String captainName, String objectiveId,
                           boolean held, int itemsStolen, int settlersHurt,
                           String stageAfterId) {

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Night", night);
        tag.putString("Captain", captainName == null ? "" : captainName);
        tag.putString("Objective", objectiveId == null ? "" : objectiveId);
        tag.putBoolean("Held", held);
        tag.putInt("ItemsStolen", itemsStolen);
        tag.putInt("SettlersHurt", settlersHurt);
        tag.putString("StageAfter", stageAfterId == null ? "" : stageAfterId);
        return tag;
    }

    public static RaidLogEntry readNbt(CompoundTag tag) {
        return new RaidLogEntry(tag.getLong("Night"), tag.getString("Captain"),
            tag.getString("Objective"), tag.getBoolean("Held"),
            tag.getInt("ItemsStolen"), tag.getInt("SettlersHurt"),
            tag.getString("StageAfter"));
    }
}

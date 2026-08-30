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

    /**
     * Hard bounds for the player-facing projection. Persisted v0 history was
     * permissive, so the Hearth must validate an entry before it sends that
     * entry to a client. A malformed newest entry is not a reason to fall
     * back to an older, more convenient story.
     */
    public static final int MAX_CAPTAIN_NAME = 96;
    public static final int MAX_REPORTED_COUNT = 1_000_000;
    public static final long MAX_REPORTED_NIGHT = 10_000_000L;

    public static boolean isValid(RaidLogEntry entry) {
        return entry != null && entry.night >= 0L
            && entry.night <= MAX_REPORTED_NIGHT
            && boundedText(entry.captainName, MAX_CAPTAIN_NAME)
            && !"?".equals(entry.captainName)
            && knownObjective(entry.objectiveId)
            && entry.itemsStolen >= 0
            && entry.itemsStolen <= MAX_REPORTED_COUNT
            && entry.settlersHurt >= 0
            && entry.settlersHurt <= MAX_REPORTED_COUNT
            && knownStage(entry.stageAfterId);
    }

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

    private static boolean knownObjective(String id) {
        if (id == null) {
            return false;
        }
        for (RaidObjective objective : RaidObjective.values()) {
            if (objective.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean knownStage(String id) {
        if (id == null) {
            return false;
        }
        for (RaidPressure.Stage stage : RaidPressure.Stage.values()) {
            if (stage.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean boundedText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0'
                || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}

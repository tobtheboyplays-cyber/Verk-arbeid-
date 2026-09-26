package com.hearthstead.conversation;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidLogEntry;
import javax.annotation.Nullable;

/**
 * Reads {@link TownFacts} from a live settlement and registers the town
 * conditions dialogue content can use: {@code town.defended},
 * {@code town.guards}, {@code town.walls}, {@code town.fed},
 * {@code town.raids_won} (prefix "!" to negate).
 */
public final class TownFactsLive {
    private TownFactsLive() {
    }

    public static TownFacts of(@Nullable Settlement settlement) {
        if (settlement == null) return TownFacts.NONE;
        int guards = 0;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record.profession != null && record.profession.martial()) guards++;
        }
        int defenses = 0;
        for (Building building : settlement.buildings) {
            if (building.valid && (building.type == BuildingType.BARRACKS || building.type == BuildingType.WATCHTOWER)) {
                defenses++;
            }
        }
        int held = 0;
        for (RaidLogEntry entry : settlement.raidLog) {
            if (entry != null && entry.held()) held++;
        }
        return new TownFacts(guards, defenses, Math.max(0, settlement.foodCache), held, settlement.population());
    }

    public static void registerConditions() {
        ConversationActions.condition("town.defended", ctx -> of(ctx.settlement()).defended());
        ConversationActions.condition("town.guards", ctx -> of(ctx.settlement()).guards() >= 2);
        ConversationActions.condition("town.walls", ctx -> of(ctx.settlement()).defenses() >= 1);
        ConversationActions.condition("town.fed", ctx -> of(ctx.settlement()).fed());
        ConversationActions.condition("town.raids_won", ctx -> of(ctx.settlement()).raidsHeld() >= 1);
    }
}

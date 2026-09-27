package com.hearthstead.event.worldevent;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidCaptain;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The story lane's only entry points for other systems: one-line hooks,
 * each guarded so a failure here can never break the caller.
 */
public final class StoryHooks {
    private StoryHooks() {
    }

    /**
     * Raid director hook (RaidDirector.pickCaptain): the captain sworn to
     * lead this settlement's next raid (Varg Ironjaw after a defied last
     * warning), once; null otherwise. Never throws.
     */
    @Nullable
    public static RaidCaptain swornCaptain(Settlement settlement) {
        try {
            if (settlement == null || settlement.raidLifecycle.firstState()
                != com.hearthstead.settlement.state.FirstRaidState.COMPLETED) {
                return null;
            }
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            ServerLevel level = server == null ? null : server.overworld();
            VisitorMemory memory = level == null ? null : VisitorMemory.existing(level);
            VisitorMemory.Book book = memory == null ? null : memory.existingBook(settlement.id);
            VisitorMemory.Ladder ladder = book == null ? null : book.existingLadder(StoryRules.LADDER_VARG);
            if (ladder == null || ladder.planned || ladder.captain == null
                || !VisitorMemory.Ladder.SWORN.equals(ladder.status)) {
                return null;
            }
            for (RaidCaptain captain : settlement.raidCaptains) {
                if (captain.id().equals(ladder.captain)) {
                    ladder.planned = true;
                    memory.changed();
                    com.hearthstead.Hearthstead.LOGGER.info("HEARTHSTEAD_STORY_SWORN_CAPTAIN settlement={} captain={}",
                        settlement.id, captain.name());
                    return captain;
                }
            }
            return null;
        } catch (RuntimeException | LinkageError failure) {
            com.hearthstead.Hearthstead.LOGGER.warn("HEARTHSTEAD_STORY_SWORN_CAPTAIN_FAILED", failure);
            return null;
        }
    }

    /**
     * "<Name> will remember this." for the existing events (envoy, brute
     * toll, raid parley, refugees): records the choice first, then the cue.
     * Never throws.
     */
    public static void remember(ServerLevel level, @Nullable Settlement settlement, String who, String name,
                                String choice, int mood) {
        try {
            if (settlement != null) StoryChat.remember(level, settlement, who, name, choice, mood);
        } catch (RuntimeException failure) {
            com.hearthstead.Hearthstead.LOGGER.warn("HEARTHSTEAD_STORY_REMEMBER_FAILED who={}", who, failure);
        }
    }

    // ------------------------------------------------------ Gorm (T3) ------

    /** Memory id of the brute toll's chief. */
    public static final String GORM = "gorm";

    /** Gorm's band: three brutes, four after his band was killed last time. */
    public static int gormBand(ServerLevel level, Settlement settlement, int base) {
        try {
            VisitorMemory memory = VisitorMemory.existing(level);
            VisitorMemory.Book book = memory == null ? null : memory.existingBook(settlement.id);
            return book != null && "defeated".equals(book.lastChoice(GORM)) ? base + 1 : base;
        } catch (RuntimeException failure) {
            return base;
        }
    }

    /** Gorm remembers the last toll: one line on arrival (none on the first visit). */
    public static void gormArrives(ServerLevel level, Settlement settlement) {
        try {
            VisitorMemory memory = VisitorMemory.get(level);
            VisitorMemory.Book book = memory.book(settlement.id);
            if (book == null) return;
            String last = book.lastChoice(GORM);
            if (!last.isEmpty()) {
                String line = switch (last) {
                    case "paid_food", "paid_coins" -> "paid";
                    case "defeated" -> "killed";
                    case "talked_down" -> "talked";
                    case "took_food" -> "took";
                    default -> "defied";
                };
                StoryChat.town(level, settlement, Component.translatableWithFallback("hearthstead.story.gorm.back." + line,
                    "%s: \"You again.\"", StoryCharacter.GORM).withStyle(ChatFormatting.ITALIC));
            }
            book.recordVisit(GORM, StoryFacts.of(level, settlement));
            memory.changed();
        } catch (RuntimeException failure) {
            com.hearthstead.Hearthstead.LOGGER.warn("HEARTHSTEAD_STORY_GORM_FAILED", failure);
        }
    }
}

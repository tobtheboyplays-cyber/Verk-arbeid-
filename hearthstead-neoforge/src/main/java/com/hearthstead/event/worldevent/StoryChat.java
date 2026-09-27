package com.hearthstead.event.worldevent;

import com.hearthstead.settlement.Settlement;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The story lane's chat: one short "[Town] ..." line when a named visitor
 * arrives or leaves, and the Telltale-style cue "<Name> will remember
 * this." (owner, 26 Sep). A cue is ONLY posted by {@link #remember}, which
 * first writes the choice to {@link VisitorMemory}: a cue never appears
 * without a consequence in memory.
 */
public final class StoryChat {
    /** Co-op kingdom: every player within this range of the Banner is "in town" for chat. */
    public static final double TOWN_RANGE_EXTRA = 160.0D;
    /** One cue per choice: the same person and choice within this many ticks is the same choice. */
    private static final long CUE_DEDUP_TICKS = 40L;
    private static final Map<String, Long> LAST_CUE = new HashMap<>();
    /** How many cues were posted (GameTests: exactly one per upsetting choice). */
    static final java.util.concurrent.atomic.AtomicInteger CUES = new java.util.concurrent.atomic.AtomicInteger();

    private StoryChat() {
    }

    /** Players who count as the settlement's members for its chat (co-op: everyone near the realm). */
    public static java.util.List<ServerPlayer> members(ServerLevel level, Settlement settlement) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
        if (settlement == null || settlement.center == null) return out;
        double range = settlement.radius + TOWN_RANGE_EXTRA;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - settlement.center.getX();
            double dz = player.getZ() - settlement.center.getZ();
            if (dx * dx + dz * dz <= range * range) out.add(player);
        }
        return out;
    }

    /** "[Town] ..." to the settlement's members. */
    public static void town(ServerLevel level, Settlement settlement, Component message) {
        MutableComponent line = Component.empty()
            .append(Component.translatableWithFallback("hearthstead.story.chat.prefix", "[Town] ")
                .withStyle(ChatFormatting.GOLD))
            .append(message.copy().withStyle(ChatFormatting.GRAY));
        for (ServerPlayer player : members(level, settlement)) player.sendSystemMessage(line);
    }

    /** Arrival/departure line of a named visitor (switchable). */
    public static void arrival(ServerLevel level, Settlement settlement, Component message) {
        if (StoryConfig.arrivals()) town(level, settlement, message);
    }

    /**
     * Records a choice {@code who} (stable memory id) will remember, then
     * shows the cue: dark red "<name> will remember this." when upset, green
     * "<name> appreciated that." when pleased, nothing when neutral.
     * Returns the memory entry (null if the settlement has no book).
     */
    @Nullable
    public static VisitorMemory.Entry remember(ServerLevel level, Settlement settlement, String who, String name,
                                               String choice, int mood) {
        if (level == null || settlement == null || who == null) return null;
        VisitorMemory memory = VisitorMemory.get(level);
        VisitorMemory.Book book = memory.book(settlement.id);
        if (book == null) return null;
        VisitorMemory.Entry entry = book.remember(who, name, choice, mood,
            WorldEventSchedule.dayOf(level.getDayTime()));
        memory.changed();
        com.hearthstead.Hearthstead.LOGGER.info("HEARTHSTEAD_STORY_REMEMBER settlement={} who={} choice={} mood={}",
            settlement.id, who, choice, mood);
        if (mood != VisitorMemory.NEUTRAL && StoryConfig.rememberCues() && name != null && !name.isBlank()) {
            String key = settlement.id + "|" + who + "|" + choice;
            long now = level.getGameTime();
            Long last = LAST_CUE.get(key);
            if (last == null || now - last > CUE_DEDUP_TICKS || now < last) {
                LAST_CUE.put(key, now);
                CUES.incrementAndGet();
                if (LAST_CUE.size() > 512) LAST_CUE.clear();
                Component cue = mood < 0
                    ? Component.translatableWithFallback("hearthstead.story.remember.displeased",
                        "%s will remember this.", name).withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC)
                    : Component.translatableWithFallback("hearthstead.story.remember.pleased",
                        "%s appreciated that.", name).withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC);
                for (ServerPlayer player : members(level, settlement)) player.sendSystemMessage(cue);
            }
        }
        return entry;
    }

    /** Forgets the cue de-duplication (GameTests, server stop). */
    public static void resetForTests() {
        LAST_CUE.clear();
    }
}

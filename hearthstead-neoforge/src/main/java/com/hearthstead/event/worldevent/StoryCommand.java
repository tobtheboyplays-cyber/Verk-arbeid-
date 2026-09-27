package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * QA for the story lane (op 2):
 * <pre>
 *   /hsstory visit &lt;character&gt;          a named visitor comes to the nearest settlement now
 *   /hsstory threat &lt;character&gt; [step]  a threat herald (sigrun, hamon), step 1 or 2
 *   /hsstory memory                       what the nearest settlement remembers
 * </pre>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class StoryCommand {
    private StoryCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hsstory").requires(source -> source.hasPermission(2))
            .then(Commands.literal("visit")
                .then(Commands.argument("who", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(StoryCharacter.values())
                        .filter(c -> !c.threat()).map(StoryCharacter::id), b))
                    .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "who"), 0))))
            .then(Commands.literal("threat")
                .then(Commands.argument("who", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(StoryCharacter.values())
                        .filter(StoryCharacter::threat).map(StoryCharacter::id), b))
                    .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "who"), 1))
                    .then(Commands.argument("step", IntegerArgumentType.integer(1, 2))
                        .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "who"),
                            IntegerArgumentType.getInteger(ctx, "step"))))))
            .then(Commands.literal("memory").executes(ctx -> memory(ctx.getSource()))));
    }

    @Nullable
    private static Settlement nearest(CommandSourceStack source) {
        SettlementSavedData data = SettlementSavedData.existing(source.getLevel());
        if (data == null) return null;
        Settlement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Settlement s : data.settlements.values()) {
            double d = s.center.distToCenterSqr(source.getPosition());
            if (d < bestDistance) { bestDistance = d; best = s; }
        }
        return best;
    }

    private static int start(CommandSourceStack source, String id, int step) {
        StoryCharacter c = StoryCharacter.byId(id);
        Settlement settlement = nearest(source);
        if (c == null || settlement == null) {
            source.sendFailure(Component.literal(c == null ? "Unknown character '" + id + "'." : "No settlement here."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        WorldEventType type = c.threat() ? WorldEventType.STORY_THREAT : WorldEventType.STORY_VISIT;
        if (c.threat()) {
            StoryThreatEvent.forceNext = c;
            StoryThreatEvent.forceStep = Math.max(1, step);
        } else {
            StoryVisitEvent.forceNext = c;
        }
        boolean ok = WorldEventDirector.start(level, settlement, type, true);
        StoryVisitEvent.forceNext = null;
        StoryThreatEvent.forceNext = null;
        StoryThreatEvent.forceStep = 0;
        if (!ok) {
            source.sendFailure(Component.literal("Could not start " + c.id() + " at " + settlement.name
                + " (an event already running, no safe spot, or no settler/player for it)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started " + c.id() + " at " + settlement.name + "."), true);
        return 1;
    }

    private static int memory(CommandSourceStack source) {
        Settlement settlement = nearest(source);
        VisitorMemory memory = settlement == null ? null : VisitorMemory.existing(source.getLevel());
        VisitorMemory.Book book = memory == null ? null : memory.existingBook(settlement.id);
        if (book == null) {
            source.sendSuccess(() -> Component.literal("Nobody has visited yet."), false);
            return 1;
        }
        for (StoryCharacter c : StoryCharacter.values()) {
            VisitorMemory.Person p = book.person(c.id());
            if (p == null) continue;
            source.sendSuccess(() -> Component.literal(c.id() + ": visits " + p.visits + ", last day " + p.lastDay
                + ", last choice " + p.lastChoice + " (mood " + p.lastMood + ")"), false);
        }
        for (String ladder : new String[] {StoryRules.LADDER_VARG, StoryRules.LADDER_HAMON}) {
            VisitorMemory.Ladder l = book.existingLadder(ladder);
            if (l != null) {
                source.sendSuccess(() -> Component.literal("ladder " + ladder + ": step " + l.step + ", " + l.status
                    + ", not before day " + l.notBeforeDay + ", paid " + l.payments), false);
            }
        }
        for (VisitorMemory.Entry e : book.log()) {
            source.sendSuccess(() -> Component.literal("day " + e.day() + ": " + e.name() + " / " + e.choice()
                + " (" + e.mood() + ")"), false);
        }
        return 1;
    }
}

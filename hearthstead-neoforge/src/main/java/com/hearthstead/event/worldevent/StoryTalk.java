package com.hearthstead.event.worldevent;

import com.hearthstead.conversation.Conversation;
import com.hearthstead.conversation.ConversationActions;
import com.hearthstead.conversation.ConversationContext;
import com.hearthstead.conversation.ConversationGraph;
import com.hearthstead.conversation.ConversationGraphs;
import com.hearthstead.conversation.SpeakerProfile;
import com.hearthstead.settlement.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The talk UI side of the story visitors. A visit's lines are picked at
 * arrival ({@link StoryLines}), so each visit gets its own small graph whose
 * id carries the character, the threat step and a signature of the chosen
 * keys: {@code hearthstead:story/<id>/<step>/<sig>}. The set of possible ids
 * is finite (the line choices are), so the registry stays bounded; the
 * graph is re-registered when the visitor loads after a restart.
 *
 * <p>Every answer goes through {@link WorldEventVisitors#applyFromConversation}
 * (first answer wins, cost paid once by the talk UI), exactly like the other
 * events.
 */
@EventBusSubscriber(modid = com.hearthstead.Hearthstead.MODID)
public final class StoryTalk {
    public static final String ACTION_PREFIX = "worldevent.story.";
    /** Every outcome id a story answer can produce. */
    static final Set<String> OUTCOMES = Set.of(
        "thank", "give_back", "refuse", "receive", "reply_warm", "tear", "brush_off", "buy_round",
        "listen", "tip", "hush", "take_in", "feed", "decline", "blessing", "donate", "send_away",
        "heal", "buy_potions", "inspect", "drink", "dismiss",
        "pay", "talked_down", "talk_failed", "defy", "attack", "pay_tax", "make_amends");
    private static boolean bootstrapped;

    private StoryTalk() {
    }

    @SubscribeEvent
    public static void serverStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        bootstrap();
    }

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        for (String outcome : OUTCOMES) {
            ConversationActions.register(ACTION_PREFIX + outcome, ctx -> apply(ctx, outcome));
        }
    }

    private static void apply(ConversationContext ctx, String outcome) {
        var result = WorldEventVisitors.applyFromConversation(ctx.player(), ctx.speaker(), outcome,
            Component.translatable(ctx.graphId().isEmpty() ? "conversation.hearthstead.option." + outcome
                : optionKey(ctx.graphId(), ctx.optionId())));
        if (result.accepted()) {
            ctx.notice(result.message());
            ctx.close();
        }
    }

    /** Lang key of an option's label: {@code conversation.hearthstead.story.<id>.opt.<option>}. */
    static String optionKey(String graphId, String optionId) {
        String rest = graphId.startsWith("hearthstead:story/") ? graphId.substring("hearthstead:story/".length()) : "";
        int slash = rest.indexOf('/');
        String who = slash < 0 ? rest : rest.substring(0, slash);
        return StoryLines.PREFIX + who + ".opt." + optionId;
    }

    static String graphId(StoryCharacter c, int step, StoryLines.Lines lines) {
        return "hearthstead:story/" + c.id() + "/" + step + "/" + lines.signature();
    }

    /** Registers the visit's graph (idempotent) and returns its id. */
    static String register(StoryCharacter c, int step, StoryLines.Lines lines) {
        bootstrap();
        String id = graphId(c, step, lines);
        if (ConversationGraphs.get(id) == null) ConversationGraphs.register(build(id, c, step, lines.keys()));
        return id;
    }

    /** Makes the visitor talkable with its visit graph. */
    static void bind(Entity visitor, Settlement settlement, StoryCharacter c, int step, StoryLines.Lines lines) {
        String id = register(c, step, lines);
        SpeakerProfile profile = new SpeakerProfile(WorldEventActors.stableId("story_" + c.id(), settlement.id),
            c.displayName(), "conversation.hearthstead.title.story_" + c.id(), "story_" + c.id());
        WorldEventConversations.bindAs(visitor, settlement, id, profile, lines.vars());
    }

    private static String act(String outcome) {
        return ACTION_PREFIX + outcome;
    }

    static ConversationGraph build(String id, StoryCharacter c, int step, List<String> lineKeys) {
        String o = StoryLines.PREFIX + c.id() + ".opt.";
        Conversation.GraphBuilder graph = Conversation.graph(id);
        graph = c.threat() ? graph.encounter(8, "low", null) : graph.encounter(6, "eye", null);
        return graph.node("start", n -> {
            for (String key : lineKeys) n.line(key);
            for (StoryOptions.Spec spec : StoryOptions.of(c, step)) {
                n.option(spec.id(), b -> {
                    b.text(o + spec.id());
                    if (spec.condition() != null) b.when(spec.condition());
                    if (spec.food() > 0) b.cost(Conversation.food(spec.food()));
                    if (spec.coins() > 0) b.cost(Conversation.coins(spec.coins()));
                    if (spec.coinsVar() != null) b.cost(Conversation.coins(spec.coinsVar()));
                    if (spec.persuade() > 0) {
                        b.persuade(spec.persuade(), spec.skill())
                            .success(x -> x.relation(spec.relation()).action(act(spec.success())).end())
                            .failure(x -> x.relation(-10).action(act(spec.failure())).end());
                    } else {
                        b.relation(spec.relation()).action(act(spec.id())).end();
                    }
                });
            }
        }).build();
    }

    /** The action ids one character's graph can fire (for tests). */
    static List<String> actions(StoryCharacter c, int step) {
        List<String> out = new ArrayList<>();
        for (StoryOptions.Spec spec : StoryOptions.of(c, step)) {
            if (spec.persuade() > 0) {
                out.add(spec.success());
                out.add(spec.failure());
            } else {
                out.add(spec.id());
            }
        }
        return out;
    }
}

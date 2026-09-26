package com.hearthstead.event.worldevent;

import com.hearthstead.conversation.BarterStock;
import com.hearthstead.conversation.Conversation;
import com.hearthstead.conversation.ConversationActions;
import com.hearthstead.conversation.ConversationConfig;
import com.hearthstead.conversation.ConversationContext;
import com.hearthstead.conversation.ConversationGraphs;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.conversation.ListBarterStock;
import com.hearthstead.conversation.SpeakerProfile;
import com.hearthstead.settlement.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Adapter onto the conversation lane's talk UI (camera, relations,
 * persuasion, barter). The graphs below are the event content; every
 * outcome action lands in {@link WorldEventVisitors#applyFromConversation},
 * which applies the handler's answer exactly once. The conversation service
 * pays the costs. With conversations switched off, the visitors fall back
 * to the chat answers of {@link WorldEventVisitors}.
 */
public final class WorldEventConversations {
    public static final String REFUGEES = "hearthstead:refugees";
    public static final String MINSTRELS = "hearthstead:minstrels";
    public static final String BRUTE_TOLL = "hearthstead:brute_toll";
    public static final String PEDDLER = "hearthstead:peddler";
    public static final String PEDDLER_STOCK = "worldevent.peddler";
    public static final String STRAY_DOG = "hearthstead:stray_dog";
    public static final String CARAVAN = "hearthstead:caravan";
    public static final String CARAVAN_STOCK = "worldevent.caravan";
    public static final String RIVAL_ENVOY = "hearthstead:rival_envoy";
    private static boolean bootstrapped;

    private WorldEventConversations() {
    }

    public static boolean enabled() {
        return ConversationConfig.enabled();
    }

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        String r = "conversation.hearthstead.refugees.";
        ConversationGraphs.register(Conversation.graph(REFUGEES)
            .encounter(6, "eye", r + "intro")
            .node("start", n -> n.line(r + "line1").line(r + "line2")
                .option("accept", o -> o.text(r + "accept").relation(15).action(act("accept")).end())
                .option("work", o -> o.text(r + "work").persuade(50, "charisma")
                    .success(x -> x.relation(10).action(act("work_yes")).end())
                    .failure(x -> x.relation(-10).action(act("work_no")).end()))
                .option("feed", o -> o.text(r + "feed").cost(Conversation.food(RefugeesEvent.FOOD_GIFT))
                    .relation(8).action(act("feed")).end())
                .option("decline", o -> o.text(r + "decline").relation(-10).action(act("decline")).end()))
            .build());
        String m = "conversation.hearthstead.minstrels.";
        ConversationGraphs.register(Conversation.graph(MINSTRELS)
            .encounter(6, "eye", m + "intro")
            .node("start", n -> n.line(m + "line1").line(m + "line2")
                .option("host", o -> o.text(m + "host").cost(Conversation.coins(MinstrelsEvent.HOST_COINS))
                    .relation(10).action(act("host")).end())
                .option("tips", o -> o.text(m + "tips").action(act("tips")).end())
                .option("away", o -> o.text(m + "away").relation(-5).action(act("away")).end()))
            .build());
        String b = "conversation.hearthstead.brute_toll.";
        ConversationGraphs.register(Conversation.graph(BRUTE_TOLL)
            .encounter(8, "low", b + "intro")
            .node("start", n -> n.line(b + "demand").line(b + "demand2")
                .option("pay_food", o -> o.text(b + "pay_food").cost(Conversation.food("toll"))
                    .relation(5).action(act("pay_food")).end())
                .option("pay_coins", o -> o.text(b + "pay_coins").cost(Conversation.coins("coins"))
                    .relation(3).action(act("pay_coins")).end())
                // Persuasion lines match the real town (owner rule): the threat only
                // shows when the town is defended; otherwise an honest appeal.
                .option("talk_walls", o -> o.text(b + "talk_walls").when("town.defended").persuade(35, "intimidation")
                    .success(x -> x.relation(10).action(act("talked_down")).end())
                    .failure(x -> x.relation(-15).action(act("insulted")).end()))
                .option("talk_honest", o -> o.text(b + "talk_honest").when("!town.defended").persuade(30, "charisma")
                    .success(x -> x.relation(10).action(act("talked_down")).end())
                    .failure(x -> x.relation(-15).action(act("insulted")).end()))
                .option("refuse", o -> o.text(b + "refuse").relation(-10).action(act("refuse")).end())
                .option("attack", o -> o.text(b + "attack").relation(-20).action(act("attack")).end()))
            .build());
        String p = "conversation.hearthstead.peddler.";
        ConversationGraphs.register(Conversation.graph(PEDDLER)
            .encounter(6, "eye", p + "intro")
            .node("start", n -> n.line(p + "line1")
                .option("trade", o -> o.text(p + "trade").barter(PEDDLER_STOCK).action(act("talked")).goTo("start"))
                .option("leave", o -> o.text(p + "leave").action(act("talked")).end()))
            .build());
        String d = "conversation.hearthstead.stray_dog.";
        ConversationGraphs.register(Conversation.graph(STRAY_DOG)
            .noEncounter()
            .node("start", n -> n.line(d + "line1")
                .option("feed_dog", o -> o.text(d + "feed").cost(Conversation.food("food")).relation(20)
                    .action(act("feed_dog")).end())
                .option("shoo", o -> o.text(d + "shoo").relation(-10).action(act("shoo")).end()))
            .build());
        String c = "conversation.hearthstead.caravan.";
        ConversationGraphs.register(Conversation.graph(CARAVAN)
            .encounter(7, "eye", c + "intro")
            .node("start", n -> n.line(c + "line1").line(c + "line2")
                .option("trade", o -> o.text(c + "trade").barter(CARAVAN_STOCK).goTo("start"))
                .option("escort", o -> o.text(c + "escort").relation(10).action(act("escort")).end())
                .option("pass", o -> o.text(c + "pass").action(act("pass")).end()))
            .build());
        String e = "conversation.hearthstead.rival_envoy.";
        ConversationGraphs.register(Conversation.graph(RIVAL_ENVOY)
            .encounter(7, "eye", e + "intro")
            .node("start", n -> n.line(e + "line1").line(e + "line2")
                .option("negotiate", o -> o.text(e + "negotiate").persuade(RivalEnvoyEvent.PACT_CHANCE, "trade")
                    .success(x -> x.relation(10).memory(e + "memory.pact").action(act("pact")).end())
                    .failure(x -> x.relation(-5).memory(e + "memory.pact_failed").action(act("pact_failed")).end()))
                .option("befriend", o -> o.text(e + "befriend").cost(Conversation.food("gift")).relation(20)
                    .memory(e + "memory.befriend").action(act("befriend")).end())
                .option("insult", o -> o.text(e + "insult").relation(-25).memory(e + "memory.insult")
                    .action(act("insult")).end())
                .option("dismiss", o -> o.text(e + "dismiss").action(act("dismiss")).end()))
            .build());
        for (String outcome : List.of("accept", "work_yes", "work_no", "feed", "decline", "host", "tips", "away",
                "pay_food", "pay_coins", "talked_down", "insulted", "refuse", "attack", "talked",
                "feed_dog", "shoo", "escort", "pass", "pact", "pact_failed", "befriend", "insult", "dismiss")) {
            ConversationActions.register(act(outcome), ctx -> apply(ctx, outcome));
        }
        ListBarterStock.register(PEDDLER_STOCK, WorldEventConversations::peddlerStock);
        ListBarterStock.register(CARAVAN_STOCK, WorldEventConversations::caravanStock);
    }

    private static String act(String outcome) {
        return "worldevent." + outcome;
    }

    private static void apply(ConversationContext ctx, String outcome) {
        if ("talked".equals(outcome)) {
            PeddlerEvent.talked(ctx.level(), ctx.speaker());
            return;
        }
        var result = WorldEventVisitors.applyFromConversation(ctx.player(), ctx.speaker(), outcome,
            Component.translatable("conversation.hearthstead." + graphKey(ctx.graphId()) + "." + ctx.optionId()));
        if (result.accepted()) {
            ctx.notice(result.message());
            ctx.close();
        }
    }

    private static String graphKey(String graphId) {
        int colon = graphId.indexOf(':');
        return colon < 0 ? graphId : graphId.substring(colon + 1);
    }

    /** Makes a visitor talkable; no-op for the chat fallback. */
    public static void bind(Entity visitor, Settlement settlement, String graph, String kind, String titleKey,
                            Map<String, Integer> vars) {
        bootstrap();
        SpeakerProfile profile = new SpeakerProfile(WorldEventActors.stableId(kind, settlement.id),
            WorldEventActors.plainName(visitor), titleKey, kind);
        ConversationService.bind(visitor, graph, profile, vars, settlement.id);
    }

    /** Binds with an explicit speaker profile (e.g. the rival lord behind an envoy). */
    public static void bindAs(Entity visitor, Settlement settlement, String graph, SpeakerProfile profile,
                              Map<String, Integer> vars) {
        bootstrap();
        ConversationService.bind(visitor, graph, profile, vars, settlement.id);
    }

    public static void unbind(Entity visitor, @Nullable Component notice) {
        if (ConversationService.isBound(visitor)) ConversationService.unbind(visitor, notice);
    }

    // ---------------------------------------------------------- peddler ---

    @Nullable
    private static BarterStock peddlerStock(ConversationContext ctx) {
        WorldEventDirector.Owner owner = WorldEventDirector.owner(ctx.level(), ctx.speaker());
        if (owner == null || owner.active().type != WorldEventType.PEDDLER) return null;
        return new PeddlerStock(ctx.level(), owner.active().id);
    }

    @Nullable
    private static BarterStock caravanStock(ConversationContext ctx) {
        WorldEventDirector.Owner owner = WorldEventDirector.owner(ctx.level(), ctx.speaker());
        if (owner == null || owner.active().type != WorldEventType.CARAVAN) return null;
        return new PeddlerStock(ctx.level(), owner.active().id) {
            @Override
            public int unitValue(ItemStack stack) {
                return CaravanEvent.unitValue(stack);
            }
        };
    }

    /**
     * The peddler's goods, kept in the event state (item id to count). He
     * sells at his own curated Coin prices and keeps what he is paid.
     */
    static class PeddlerStock implements BarterStock {
        private final ServerLevel level;
        private final UUID eventId;

        PeddlerStock(ServerLevel level, UUID eventId) {
            this.level = level;
            this.eventId = eventId;
        }

        @Nullable
        private CompoundTag tag() {
            WorldEventDirector.Owner owner = WorldEventDirector.ownerOfEvent(level, eventId);
            return owner == null ? null : owner.active().state.getCompound("Stock");
        }

        @Override
        public List<ItemStack> items() {
            List<ItemStack> out = new ArrayList<>();
            CompoundTag stock = tag();
            if (stock == null) return out;
            for (String key : stock.getAllKeys()) {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(key));
                int count = stock.getInt(key);
                if (count > 0 && item != null) out.add(new ItemStack(item, count));
            }
            return out;
        }

        @Override
        public boolean remove(List<ItemStack> taken) {
            CompoundTag stock = tag();
            if (stock == null) return false;
            CompoundTag work = stock.copy();
            for (ItemStack want : taken) {
                String key = BuiltInRegistries.ITEM.getKey(want.getItem()).toString();
                int left = work.getInt(key) - want.getCount();
                if (left < 0) return false;
                work.putInt(key, left);
            }
            for (String key : work.getAllKeys()) stock.putInt(key, work.getInt(key));
            WorldEventSavedData.get(level).markChanged();
            return true;
        }

        @Override
        public void add(List<ItemStack> given) {
            // The peddler pockets his Coins and rolls on; he does not resell.
        }

        @Override
        public int unitValue(ItemStack stack) {
            for (PeddlerEvent.Ware ware : PeddlerEvent.TABLE) {
                if (stack.is(ware.item())) return Math.max(1, ware.price() * 100 / ware.count());
            }
            return -1;
        }
    }
}

package com.hearthstead.event.worldevent;

import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The seam between the world events (content: who asks what, what each
 * answer does) and whatever presents the question to players.
 *
 * <p>The conversation lane's talk UI is the intended presenter. Until it
 * registers (or when it is switched off), right-clicking a waiting visitor
 * prints the request with clickable answers in chat, which run
 * {@code /hsevent respond <event> <option>}. Either way every answer comes
 * back through {@link #choose}, which is server-authoritative: distance,
 * settlement area, event still open, first answer wins, and the cost is
 * paid exactly once (player inventory first, then Warehouse stores).
 */
public final class WorldEventVisitors {
    public static final double TALK_DISTANCE = 8.0D;
    public static final double CHAT_TALK_DISTANCE = 12.0D;
    private static final String ANSWERED = "Answered";

    public enum CostKind { NONE, FOOD, COINS }
    public enum OptionStyle { PRIMARY, SECONDARY, DANGER, TRADE }

    public record Cost(CostKind kind, int amount) {
        public static final Cost FREE = new Cost(CostKind.NONE, 0);
        public static Cost food(int amount) { return new Cost(CostKind.FOOD, Math.max(0, amount)); }
        public static Cost coins(int amount) { return new Cost(CostKind.COINS, Math.max(0, amount)); }
        public boolean free() { return kind == CostKind.NONE || amount <= 0; }
    }

    /** A server-rolled chance: {@code base} percent, clamped 5..95. */
    public record Persuasion(int base, String successOptionId, String failureOptionId) {}

    public record Option(String id, Component label, Cost cost, OptionStyle style, int relation,
                         @Nullable Persuasion persuasion, boolean available, Component unavailableReason) {
        public static Option of(String id, Component label, Cost cost, OptionStyle style) {
            return new Option(id, label, cost, style, 0, null, true, Component.empty());
        }
        public Option withRelation(int delta) {
            return new Option(id, label, cost, style, delta, persuasion, available, unavailableReason);
        }
        public Option persuade(Persuasion roll) {
            return new Option(id, label, cost, style, relation, roll, available, unavailableReason);
        }
        public Option unavailable(Component reason) {
            return new Option(id, label, cost, style, relation, persuasion, false, reason);
        }
    }

    public record Topic(UUID eventId, int entityId, UUID entityUuid, UUID speakerId, String kind,
                        Component title, Component speaker, List<Component> lines,
                        List<Option> options, String defaultOptionId, long answerByGameTime) {}

    public record Result(boolean accepted, Component message) {}

    private static volatile BiConsumer<ServerPlayer, Topic> presenter;
    private static final List<BiConsumer<UUID, Component>> ANSWER_LISTENERS = new CopyOnWriteArrayList<>();

    private WorldEventVisitors() {
    }

    /** The conversation UI registers itself here; null restores the chat fallback. */
    public static void setPresenter(@Nullable BiConsumer<ServerPlayer, Topic> value) {
        presenter = value;
    }

    public static void onAnswered(BiConsumer<UUID, Component> listener) {
        if (listener != null) ANSWER_LISTENERS.add(listener);
    }

    /** True for an event visitor currently waiting for an answer (for a "!" marker). */
    public static boolean isAwaitingVisitor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, entity);
        return owner != null && !owner.active().state.contains(ANSWERED)
            && owner.handler().awaitingAnswer(owner.active(), entity);
    }

    public static boolean answered(WorldEventSavedData.Active active) {
        return active.state.contains(ANSWERED);
    }

    /** Marks an event answered without a player (default outcomes). */
    public static void markAnswered(WorldEventSavedData.Active active, String optionId) {
        active.state.putString(ANSWERED, optionId);
    }

    public static java.util.Optional<Topic> topicFor(ServerPlayer player, Entity visitor) {
        if (!(visitor.level() instanceof ServerLevel level)) return java.util.Optional.empty();
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, visitor);
        if (owner == null || answered(owner.active()) || !owner.handler().awaitingAnswer(owner.active(), visitor)) {
            return java.util.Optional.empty();
        }
        Topic topic = owner.handler().topic(level, owner.settlement(), owner.active(), player, visitor);
        if (topic == null) return java.util.Optional.empty();
        List<Option> priced = new ArrayList<>();
        for (Option option : owner.handler().options(level, owner.settlement(), owner.active(), player, visitor)) {
            if (option.available() && !canPay(level, owner.settlement(), player, option.cost())) {
                option = option.unavailable(Component.translatableWithFallback(
                    "hearthstead.event.cannot_afford", "You cannot afford that: %s", costText(option.cost())));
            }
            priced.add(option);
        }
        return java.util.Optional.of(new Topic(topic.eventId(), topic.entityId(), topic.entityUuid(),
            topic.speakerId(), topic.kind(), topic.title(), topic.speaker(), topic.lines(), List.copyOf(priced),
            topic.defaultOptionId(), topic.answerByGameTime()));
    }

    /** Right-click on a waiting visitor: present the request. */
    public static boolean present(ServerPlayer player, Entity visitor) {
        var topic = topicFor(player, visitor);
        if (topic.isEmpty()) return false;
        BiConsumer<ServerPlayer, Topic> current = presenter;
        if (current != null) {
            current.accept(player, topic.get());
        } else {
            presentInChat(player, topic.get());
        }
        return true;
    }

    private static void presentInChat(ServerPlayer player, Topic topic) {
        player.sendSystemMessage(Component.empty().append(topic.title().copy().withStyle(ChatFormatting.GOLD,
            ChatFormatting.BOLD)).append(Component.literal("  " ).append(topic.speaker().copy()
            .withStyle(ChatFormatting.GRAY))));
        for (Component line : topic.lines()) {
            player.sendSystemMessage(Component.literal("  ").append(line.copy().withStyle(ChatFormatting.ITALIC)));
        }
        MutableComponent row = Component.literal("  ");
        for (Option option : topic.options()) {
            MutableComponent label = Component.literal("[").append(option.label()).append(
                option.cost().free() ? Component.empty() : Component.literal(" - ").append(costText(option.cost())))
                .append("]");
            Component hover = option.available()
                ? (option.persuasion() != null
                    ? Component.translatableWithFallback("hearthstead.event.persuade_hover",
                        "A gamble: about %s%% chance.", clampChance(option.persuasion().base()))
                    : Component.translatableWithFallback("hearthstead.event.answer_hover", "Click to answer"))
                : option.unavailableReason();
            ChatFormatting color = !option.available() ? ChatFormatting.DARK_GRAY
                : switch (option.style()) {
                    case PRIMARY, TRADE -> ChatFormatting.GREEN;
                    case DANGER -> ChatFormatting.RED;
                    case SECONDARY -> ChatFormatting.YELLOW;
                };
            Style style = Style.EMPTY.withColor(color).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
            if (option.available()) {
                style = style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/hsevent respond " + topic.eventId() + " " + option.id()));
            }
            row.append(label.withStyle(style)).append(" ");
        }
        player.sendSystemMessage(row);
    }

    public static int clampChance(int base) {
        return Math.max(5, Math.min(95, base));
    }

    /**
     * Server-authoritative answer. {@code visitorHint} may be null (chat
     * path): the event's waiting visitor is then looked up.
     */
    public static Result choose(ServerPlayer player, UUID eventId, @Nullable Entity visitorHint, String optionId) {
        ServerLevel level = player.serverLevel();
        WorldEventSavedData data = WorldEventSavedData.existing(level);
        WorldEventSavedData.Active active = data == null ? null : data.activeById(eventId);
        UUID settlementId = data == null ? null : data.settlementOfEvent(eventId);
        Settlement settlement = settlementId == null ? null : SettlementManager.byId(level, settlementId);
        if (active == null || settlement == null) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.over",
                "That moment has passed."));
        }
        if (answered(active)) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.already_answered",
                "Someone has already answered."));
        }
        WorldEventHandler handler = WorldEventDirector.handler(active.type);
        Entity visitor = visitorHint;
        if (visitor == null || !active.actors.contains(visitor.getUUID())) {
            visitor = null;
            for (UUID id : active.actors) {
                Entity candidate = level.getEntity(id);
                if (candidate != null && handler.awaitingAnswer(active, candidate)) { visitor = candidate; break; }
            }
        }
        if (visitor == null || !visitor.isAlive() || !handler.awaitingAnswer(active, visitor)) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.over",
                "That moment has passed."));
        }
        double reach = visitorHint == null ? CHAT_TALK_DISTANCE : TALK_DISTANCE;
        if (!player.isAlive() || player.isSpectator() || player.level() != visitor.level()
            || player.distanceToSqr(visitor) > reach * reach
            || !WorldEventDirector.insideArea(settlement, player.blockPosition())) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.too_far",
                "Stand closer to them to answer."));
        }
        Option chosen = null;
        for (Option option : handler.options(level, settlement, active, player, visitor)) {
            if (option.id().equals(optionId)) { chosen = option; break; }
        }
        if (chosen == null || !chosen.available()) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.not_an_option",
                "That is not an answer they will take."));
        }
        if (!pay(level, settlement, player, chosen.cost())) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.cannot_afford",
                "You cannot afford that: %s", costText(chosen.cost())));
        }
        String outcomeId = chosen.id();
        if (chosen.persuasion() != null) {
            int chance = clampChance(chosen.persuasion().base());
            outcomeId = level.random.nextInt(100) < chance
                ? chosen.persuasion().successOptionId() : chosen.persuasion().failureOptionId();
        }
        return apply(level, data, settlement, active, handler, player, visitor, outcomeId, chosen.label());
    }

    private static Result apply(ServerLevel level, WorldEventSavedData data, Settlement settlement,
                                WorldEventSavedData.Active active, WorldEventHandler handler, ServerPlayer player,
                                Entity visitor, String outcomeId, Component label) {
        markAnswered(active, outcomeId);
        data.markChanged();
        WorldEventDirector.notice(level, settlement, Component.translatableWithFallback(
            "hearthstead.event.answered", "%s: \"%s\"", player.getDisplayName(), label)
            .withStyle(ChatFormatting.GRAY));
        Component outcome = handler.answer(level, settlement, active, player, visitor, outcomeId);
        if (outcome != null) WorldEventDirector.notice(level, settlement, outcome);
        Component closing = outcome == null ? Component.empty() : outcome;
        for (BiConsumer<UUID, Component> listener : ANSWER_LISTENERS) listener.accept(active.id, closing);
        return new Result(true, closing);
    }

    /**
     * The conversation service already validated reach, rolled any
     * persuasion and paid the cost exactly once; this applies the outcome
     * (first answer wins) with the same event checks as {@link #choose}.
     */
    public static Result applyFromConversation(ServerPlayer player, Entity visitor, String outcomeId, Component label) {
        ServerLevel level = player.serverLevel();
        WorldEventDirector.Owner owner = WorldEventDirector.owner(level, visitor);
        WorldEventSavedData data = WorldEventSavedData.existing(level);
        if (owner == null || data == null || answered(owner.active())
            || !owner.handler().awaitingAnswer(owner.active(), visitor)) {
            return new Result(false, Component.translatableWithFallback("hearthstead.event.over",
                "That moment has passed."));
        }
        owner.active().state.putBoolean("ViaTalk", true);
        return apply(level, data, owner.settlement(), owner.active(), owner.handler(), player, visitor,
            outcomeId, label);
    }

    // ------------------------------------------------------------ payment --

    public static Component costText(Cost cost) {
        return switch (cost.kind()) {
            case NONE -> Component.empty();
            case FOOD -> Component.translatableWithFallback("hearthstead.event.cost.food", "%s food", cost.amount());
            case COINS -> Component.translatableWithFallback("hearthstead.event.cost.coins", "%s Coins", cost.amount());
        };
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD)
            && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.SPIDER_EYE)
            && !stack.is(Items.POISONOUS_POTATO) && !stack.is(Items.PUFFERFISH);
    }

    private static Predicate<ItemStack> matcher(CostKind kind) {
        return kind == CostKind.FOOD ? WorldEventVisitors::isFood
            : stack -> stack.is(ModItems.GOLD_COIN.get());
    }

    public static boolean canPay(ServerLevel level, Settlement settlement, ServerPlayer player, Cost cost) {
        if (cost.free()) return true;
        Predicate<ItemStack> match = matcher(cost.kind());
        long have = count(player.getInventory(), match);
        if (have >= cost.amount()) return true;
        for (Container store : stores(level, settlement)) {
            have += count(store, match);
            if (have >= cost.amount()) return true;
        }
        return false;
    }

    /** Takes exactly the cost (player first, then stores) or nothing at all. */
    public static boolean pay(ServerLevel level, Settlement settlement, ServerPlayer player, Cost cost) {
        if (cost.free()) return true;
        if (!canPay(level, settlement, player, cost)) return false;
        Predicate<ItemStack> match = matcher(cost.kind());
        int left = take(player.getInventory(), match, cost.amount());
        for (Container store : stores(level, settlement)) {
            if (left <= 0) break;
            left = take(store, match, left);
        }
        player.getInventory().setChanged();
        return left <= 0;
    }

    /** Removes up to {@code max} food from the settlement's stores only; returns how much. */
    public static int takeFoodFromStores(ServerLevel level, Settlement settlement, int max) {
        int left = max;
        for (Container store : stores(level, settlement)) {
            if (left <= 0) break;
            left = take(store, WorldEventVisitors::isFood, left);
        }
        return max - left;
    }

    public static int storeCoins(ServerLevel level, Settlement settlement) {
        long total = 0;
        for (Container store : stores(level, settlement)) total += count(store, stack -> stack.is(ModItems.GOLD_COIN.get()));
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static int storeFood(ServerLevel level, Settlement settlement) {
        long total = 0;
        for (Container store : stores(level, settlement)) total += count(store, WorldEventVisitors::isFood);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static long count(Container container, Predicate<ItemStack> match) {
        long total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (match.test(stack)) total += stack.getCount();
        }
        return total;
    }

    private static int take(Container container, Predicate<ItemStack> match, int amount) {
        int left = amount;
        for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!match.test(stack)) continue;
            int used = Math.min(left, stack.getCount());
            stack.shrink(used);
            if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
            left -= used;
        }
        if (left != amount) container.setChanged();
        return left;
    }

    /** Loaded Warehouse containers of the settlement (never loads chunks). */
    public static List<Container> stores(ServerLevel level, Settlement settlement) {
        List<Container> out = new ArrayList<>();
        if (settlement == null) return out;
        for (Building building : settlement.buildings) {
            if (!building.valid || building.type != BuildingType.WAREHOUSE) continue;
            for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level, building)) {
                if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Container container
                    && !out.contains(container)) {
                    out.add(container);
                }
            }
        }
        return out;
    }
}

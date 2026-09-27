package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Town chat (owner, 26 Sep): one short line per important event, sent only to
 * the settlement's members, as {@code [Town name] body} with one colour per
 * kind. The single door for raid warnings and endings, settler deaths,
 * finished buildings, upgrades, new research and the Trader's sales.
 *
 * <p>No spam: lines are queued and sent once at the end of the server tick.
 * Identical lines in the same tick go out once; several buildings (or
 * upgrades) finished in the same tick become one line. Different bodies of
 * any other kind are never merged. Every kind has a switch in [chat].
 *
 * <p>Members are the players who have used the settlement's Banner
 * ({@link Settlement#members}). A settlement nobody has joined yet (an old
 * save) falls back to the players near it, the old delivery rule.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class TownChat {

    public enum Kind {
        RAID(ChatFormatting.RED),
        DEATH(ChatFormatting.DARK_RED),
        BUILDING(ChatFormatting.GREEN),
        UPGRADE(ChatFormatting.GREEN),
        RESEARCH(ChatFormatting.GOLD),
        TRADE(ChatFormatting.AQUA);

        private final ChatFormatting color;

        Kind(ChatFormatting color) {
            this.color = color;
        }

        public ChatFormatting color() {
            return color;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Kinds whose same-tick lines fold into one list line. */
        public boolean groups() {
            return this == BUILDING || this == UPGRADE;
        }
    }

    /** Blocks past the settlement edge the no-member fallback reaches. */
    static final int FALLBACK_MARGIN = 64;

    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();
    private static final List<BiConsumer<ServerPlayer, Component>> TEST_TAPS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Queues one line for this settlement's members. {@code body} should be
     * unstyled so the kind colour applies (an explicit colour in it wins).
     */
    public static void send(ServerLevel level, Settlement settlement, Kind kind, Component body) {
        if (level == null || settlement == null || kind == null || body == null
            || !TownChatConfig.enabled(kind)) {
            return;
        }
        PENDING.computeIfAbsent(settlement.id, id -> new Pending(level, settlement)).batch.add(kind, body);
    }

    /** Sends everything queued now (end of tick; GameTests may call it directly). */
    public static void flushNow() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> due = new ArrayList<>(PENDING.values());
        PENDING.clear();
        for (Pending pending : due) {
            try {
                deliver(pending);
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Town chat delivery failed for {}", pending.settlement.name, failure);
            }
        }
    }

    private static void deliver(Pending pending) {
        List<Component> lines = pending.batch.render(pending.settlement.name);
        if (lines.isEmpty()) {
            return;
        }
        List<ServerPlayer> to = recipients(pending.level, pending.settlement);
        for (ServerPlayer player : to) {
            for (Component line : lines) {
                player.sendSystemMessage(line);
                for (BiConsumer<ServerPlayer, Component> tap : TEST_TAPS) {
                    tap.accept(player, line);
                }
            }
        }
    }

    /** Online members, wherever they are; nobody-joined-yet falls back to nearby players. */
    static List<ServerPlayer> recipients(ServerLevel level, Settlement settlement) {
        List<ServerPlayer> out = new ArrayList<>();
        MinecraftServer server = level.getServer();
        if (!settlement.members.isEmpty()) {
            for (UUID id : settlement.members) {
                ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
                if (player != null) {
                    out.add(player);
                }
            }
            return out;
        }
        BlockPos center = settlement.center;
        if (center == null) {
            return out;
        }
        double reach = settlement.radius + FALLBACK_MARGIN;
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(center) <= reach * reach) {
                out.add(player);
            }
        }
        return out;
    }

    /** GameTest hook: sees every delivered line until removed. Several tests may tap at once. */
    public static void addTestTap(BiConsumer<ServerPlayer, Component> tap) {
        TEST_TAPS.add(tap);
    }

    public static void removeTestTap(BiConsumer<ServerPlayer, Component> tap) {
        TEST_TAPS.remove(tap);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        flushNow();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
    }

    // ------------------------------------------------------------ batching

    private record Pending(ServerLevel level, Settlement settlement, Batch batch) {
        Pending(ServerLevel level, Settlement settlement) {
            this(level, settlement, new Batch());
        }
    }

    /** One settlement's lines for one tick. Pure, so JUnit covers the rules. */
    static final class Batch {
        private final List<Slot> slots = new ArrayList<>();

        void add(Kind kind, Component body) {
            for (Slot slot : slots) {
                if (slot.kind != kind) {
                    continue;
                }
                if (slot.bodies.contains(body)) {
                    return; // the same line twice in one tick goes out once
                }
                if (kind.groups()) {
                    slot.bodies.add(body);
                    return;
                }
            }
            Slot slot = new Slot(kind);
            slot.bodies.add(body);
            slots.add(slot);
        }

        List<Component> render(String townName) {
            List<Component> out = new ArrayList<>(slots.size());
            for (Slot slot : slots) {
                out.add(line(townName, slot.kind, slot.body()));
            }
            return out;
        }

        int size() {
            return slots.size();
        }
    }

    private static final class Slot {
        final Kind kind;
        final List<Component> bodies = new ArrayList<>(2);

        Slot(Kind kind) {
            this.kind = kind;
        }

        Component body() {
            if (!kind.groups()) {
                return bodies.get(0);
            }
            String key = "hearthstead.chat." + kind.id() + (bodies.size() == 1 ? ".one" : ".many");
            return Component.translatable(key, ComponentUtils.formatList(bodies, Component.literal(", ")));
        }
    }

    /** {@code [Town] body}, the whole line in the kind colour unless the body sets its own. */
    static Component line(String townName, Kind kind, Component body) {
        MutableComponent line = Component.empty().withStyle(kind.color());
        line.append(Component.translatable("hearthstead.chat.prefix",
            townName == null || townName.isBlank() ? "?" : townName));
        line.append(Component.literal(" "));
        line.append(body);
        return line;
    }

    private TownChat() {
    }
}

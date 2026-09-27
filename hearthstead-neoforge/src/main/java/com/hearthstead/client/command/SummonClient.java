package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.ClientOutlineHook;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.SummonRequestPayload;
import com.hearthstead.network.SummonStatePayload;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Client API for "Summon" (any settler comes to the player), used by the
 * settler sheet, the Banner map and the command keys (look at a settler and
 * tap R or G, see {@link CommandKeys}).
 *
 * <ul>
 *   <li>{@link #request(int, UUID)}: send the request (entity id may be -1 when
 *       the settler is not loaded on this client).</li>
 *   <li>{@link #unavailableReason(SettlerEntity)}: client-known reasons to
 *       disable the button (asleep, already summoned, not a settler of ours).
 *       Server-only reasons (other dimension, no route, no permission, switch
 *       off) come back as the action-bar message.</li>
 *   <li>{@link #isSummonedByMe(UUID)} / {@link #enRoute()}: this player's
 *       summons, for button state and the map line.</li>
 * </ul>
 * Also owns the private outline: gold on a settler summoned to you (until it
 * arrives), and the role colour on the soldiers who will hear a command key
 * while you hold it.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class SummonClient {
    public static final int SUMMON_OUTLINE_RGB = 0xD9B880;

    private static final Map<UUID, SummonStatePayload.Row> MINE = new HashMap<>();
    private static final Set<Integer> EN_ROUTE_IDS = new HashSet<>();
    private static final Map<Integer, Integer> COMMAND_OUTLINE = new HashMap<>();

    static {
        ClientOutlineHook.color = SummonClient::outline;
    }

    /** Called once from client setup so the outline hook is installed early. */
    public static void install() {
        ClientOutlineHook.color = SummonClient::outline;
    }

    public static void request(int entityId, UUID settlerId) {
        if (settlerId == null || Minecraft.getInstance().getConnection() == null) return;
        PacketDistributor.sendToServer(new SummonRequestPayload(entityId, settlerId));
    }

    public static void request(SettlerEntity settler) {
        request(settler.getId(), settler.getUUID());
    }

    /** Empty when the button may be pressed; otherwise the tooltip reason. */
    public static Optional<Component> unavailableReason(@Nullable SettlerEntity settler) {
        if (settler == null) return Optional.empty(); // unknown here; the server decides
        if (!settler.isAlive()) return Optional.of(Component.translatable("hearthstead.summon.refused.not_found", ""));
        if (settler.isSleeping()) {
            return Optional.of(Component.translatable("hearthstead.summon.refused.asleep", settler.getSettlerName()));
        }
        if (isSummonedByMe(settler.getUUID())) {
            return Optional.of(Component.translatable("hearthstead.summon.already", settler.getSettlerName()));
        }
        return Optional.empty();
    }

    public static boolean isSummonedByMe(UUID settlerId) {
        return MINE.containsKey(settlerId);
    }

    /** This player's summons: settler UUID -> arrived (false while en route). */
    public static Map<UUID, Boolean> enRoute() {
        Map<UUID, Boolean> view = new HashMap<>();
        MINE.forEach((id, row) -> view.put(id, row.arrived()));
        return view;
    }

    public static void accept(SummonStatePayload payload) {
        MINE.clear();
        EN_ROUTE_IDS.clear();
        for (SummonStatePayload.Row row : payload.rows()) {
            MINE.put(row.settlerId(), row);
            if (!row.arrived()) EN_ROUTE_IDS.add(row.entityId());
        }
    }

    // ---------------------------------------------------------------- outline

    static int outline(int entityId) {
        if (EN_ROUTE_IDS.contains(entityId)) return SUMMON_OUTLINE_RGB;
        Integer command = COMMAND_OUTLINE.get(entityId);
        return command == null ? -1 : command;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        COMMAND_OUTLINE.clear();
        if (mc.player == null || mc.level == null) return;
        Group group = CommandKeys.activeGroup();
        if (group != null) {
            for (SettlerEntity soldier : CommandClientState.inEarshot(group)) {
                Group role = CommandClientState.roleOf(soldier.getId());
                COMMAND_OUTLINE.put(soldier.getId(), CommandStyle.rgb(role == null ? group : role));
            }
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        MINE.clear();
        EN_ROUTE_IDS.clear();
        COMMAND_OUTLINE.clear();
    }

    /** For the map lane: all rows as (entity id, settler UUID, arrived). */
    public static List<SummonStatePayload.Row> rows() {
        return List.copyOf(MINE.values());
    }

    private SummonClient() {
    }
}

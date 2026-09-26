package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.ClientOutlineHook;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.SummonRequestPayload;
import com.hearthstead.network.SummonStatePayload;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;
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
 * settler sheet, the Banner map and the in-world key (default O: look at a
 * settler and press it).
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
        while (CommandKeys.SUMMON_KEY.consumeClick()) {
            if (mc.screen != null) continue;
            SettlerEntity looked = lookedAtSettler(mc);
            if (looked == null) {
                mc.player.displayClientMessage(Component.translatable("hearthstead.summon.aim"), true);
                continue;
            }
            Optional<Component> reason = unavailableReason(looked);
            if (reason.isPresent()) mc.player.displayClientMessage(reason.get(), true);
            else request(looked);
        }
    }

    @Nullable
    private static SettlerEntity lookedAtSettler(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof SettlerEntity settler) {
            return settler;
        }
        double range = 64.0D;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        var blockHit = mc.player.pick(range, 1.0F, false);
        double max = eye.distanceToSqr(blockHit.getLocation());
        EntityHitResult far = ProjectileUtil.getEntityHitResult(mc.player, eye, eye.add(look.scale(range)),
            mc.player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D),
            e -> e instanceof SettlerEntity && e.isAlive(), max);
        return far != null && far.getEntity() instanceof SettlerEntity settler ? settler : null;
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

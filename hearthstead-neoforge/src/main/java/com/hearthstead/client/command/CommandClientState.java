package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.FieldOrderStatePayload;
import com.hearthstead.settlement.guard.FieldOrderRules;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Latest server snapshot of the nearby settlement's soldiers and field orders. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CommandClientState {
    private static FieldOrderStatePayload state;
    private static ClientLevel boundLevel;
    private static long receivedAt;
    private static final OrderMarkerVisibility MARKERS = new OrderMarkerVisibility();
    private static List<FieldOrderStatePayload.SlotEntry> issuedMarkers = List.of();
    private static long issuedAt = Long.MIN_VALUE;
    private static long now() { return System.nanoTime() / 1_000_000L; }

    public static void accept(FieldOrderStatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (boundLevel != mc.level) clear();
        Map<Integer, OrderMarkerVisibility.Order> orders = new HashMap<>();
        for (FieldOrderStatePayload.GroupLine line : payload.groups()) {
            orders.put(line.group(), new OrderMarkerVisibility.Order(line.orderId(), line.holdFire()));
        }
        MARKERS.snapshot(orders, now());
        state = payload;
        boundLevel = mc.level;
        receivedAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        state = null;
        boundLevel = null;
        receivedAt = 0;
        MARKERS.clear();
        issuedMarkers = List.of();
        issuedAt = Long.MIN_VALUE;
    }

    /** Local dispatch feedback, not a claim that the server accepted the order. */
    public static void issued(Group group, FieldOrderRules.Kind kind) {
        if (state() == null) return;
        long time = now();
        MARKERS.issued(time);
        issuedAt = time;
        List<FieldOrderStatePayload.SlotEntry> hints = new ArrayList<>();
        if (kind == FieldOrderRules.Kind.RETURN || kind == FieldOrderRules.Kind.RESUPPLY) {
            for (SettlerEntity soldier : inEarshot(group)) {
                Group role = roleOf(soldier.getId());
                if (role != null && (kind != FieldOrderRules.Kind.RESUPPLY || role == Group.ARCHERS)) {
                    hints.add(new FieldOrderStatePayload.SlotEntry(soldier.getId(), role.wireId(),
                        kind.wireId(), soldier.blockPosition(), true, false));
                }
            }
        }
        issuedMarkers = List.copyOf(hints);
    }

    public static List<FieldOrderStatePayload.SlotEntry> markerSlots() {
        FieldOrderStatePayload current = state();
        if (current == null) return List.of();
        Map<Integer, FieldOrderStatePayload.SlotEntry> result = new java.util.LinkedHashMap<>();
        for (var roster : current.roster()) {
            result.put(roster.entityId(), new FieldOrderStatePayload.SlotEntry(roster.entityId(), roster.group(),
                FieldOrderRules.Kind.LINE.wireId(), net.minecraft.core.BlockPos.ZERO, true, false));
        }
        for (var slot : current.slots()) result.put(slot.entityId(), slot);
        long time = now();
        if (issuedAt != Long.MIN_VALUE && time >= issuedAt && time - issuedAt < OrderMarkerVisibility.DISPLAY_MS) {
            for (var slot : issuedMarkers) {
                Group role = roleOf(slot.entityId());
                if (role != null && role.wireId() == slot.group()) result.put(slot.entityId(), slot);
            }
        } else {
            issuedMarkers = List.of();
        }
        return List.copyOf(result.values());
    }

    @Nullable
    public static FieldOrderStatePayload state() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != boundLevel) { clear(); return null; }
        if (state == null) return null;
        if (System.currentTimeMillis() - receivedAt > 10_000L) return null; // stale: server stopped syncing
        return state;
    }

    /** Milliseconds since the orders last changed (for the brief post-order dot display). */
    public static long millisSinceChange() {
        return MARKERS.age(now());
    }

    /** Role of a roster soldier by network id, or null if not a commandable soldier. */
    @Nullable
    public static Group roleOf(int entityId) {
        FieldOrderStatePayload s = state();
        if (s == null) return null;
        for (FieldOrderStatePayload.RosterEntry entry : s.roster()) {
            if (entry.entityId() == entityId) return Group.fromWire(entry.group()).orElse(null);
        }
        return null;
    }

    /** Roster soldiers of one arm loaded on this client and within earshot of the player. */
    public static List<SettlerEntity> inEarshot(Group arm) {
        List<SettlerEntity> result = new ArrayList<>();
        FieldOrderStatePayload s = state();
        Minecraft mc = Minecraft.getInstance();
        if (s == null || mc.level == null || mc.player == null) return result;
        for (FieldOrderStatePayload.RosterEntry entry : s.roster()) {
            if (!arm.includes(Group.fromWire(entry.group()).orElse(Group.ALL))) continue;
            Entity entity = mc.level.getEntity(entry.entityId());
            if (entity instanceof SettlerEntity soldier && soldier.isAlive()
                && FieldOrderRules.withinEarshot(soldier.distanceToSqr(mc.player))) {
                result.add(soldier);
            }
        }
        return result;
    }

    public static int rosterSize(Group arm) {
        FieldOrderStatePayload s = state();
        if (s == null) return 0;
        int count = 0;
        for (FieldOrderStatePayload.RosterEntry entry : s.roster()) {
            if (arm.includes(Group.fromWire(entry.group()).orElse(Group.ALL))) count++;
        }
        return count;
    }

    @Nullable
    public static FieldOrderStatePayload.GroupLine line(Group arm) {
        FieldOrderStatePayload s = state();
        if (s == null) return null;
        for (FieldOrderStatePayload.GroupLine line : s.groups()) {
            if (line.group() == arm.wireId()) return line;
        }
        return null;
    }

    private CommandClientState() {
    }
}

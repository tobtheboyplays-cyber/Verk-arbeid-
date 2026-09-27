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
    private static long changedAt;
    private static final Map<Integer, Integer> SEEN_ORDER_IDS = new HashMap<>();

    public static void accept(FieldOrderStatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        boolean changed = state == null || payload.revision() != state.revision()
            || !payload.slots().equals(state.slots());
        for (FieldOrderStatePayload.GroupLine line : payload.groups()) {
            Integer previous = SEEN_ORDER_IDS.put(line.group(), line.orderId());
            if (previous == null || previous != line.orderId()) changed = true;
        }
        state = payload;
        boundLevel = mc.level;
        receivedAt = System.currentTimeMillis();
        if (changed) changedAt = receivedAt;
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        state = null;
        boundLevel = null;
        SEEN_ORDER_IDS.clear();
    }

    @Nullable
    public static FieldOrderStatePayload state() {
        Minecraft mc = Minecraft.getInstance();
        if (state == null || mc.level != boundLevel) return null;
        if (System.currentTimeMillis() - receivedAt > 10_000L) return null; // stale: server stopped syncing
        return state;
    }

    /** Milliseconds since the orders last changed (for the brief post-order dot display). */
    public static long millisSinceChange() {
        return System.currentTimeMillis() - changedAt;
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

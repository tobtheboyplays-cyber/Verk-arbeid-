package com.hearthstead.client.fx;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.command.CommandClientState;
import com.hearthstead.client.command.CommandStyle;
import com.hearthstead.client.command.SummonClient;
import com.hearthstead.client.patrol.PatrolClient;
import com.hearthstead.fx.FxEffect;
import com.hearthstead.item.PatrolMapItem;
import com.hearthstead.network.FieldOrderStatePayload;
import com.hearthstead.network.PatrolSnapshotPayload;
import com.hearthstead.network.SummonStatePayload;
import com.hearthstead.registry.ModParticles;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Feedback moments derived from client state the command, patrol and summon
 * lanes already sync (no extra network):
 * <ul>
 *   <li>Summon arrival: a ring at the soldier's feet when its row flips to
 *       arrived.</li>
 *   <li>Order confirmed: small dots pulse under the soldiers of the arm
 *       whose order id just changed (role colour).</li>
 *   <li>Patrol waypoints: while the patrol map is held, a slow mote rises
 *       from the selected route's posts; a newly set waypoint gets a small
 *       burst.</li>
 * </ul>
 * No particles by the "!" talk marker: the conversation lane's glowing badge
 * owns that cue (owner found a shimmer there cluttered).
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FxFeedback {
    private static final Int2BooleanOpenHashMap SUMMON_ARRIVED = new Int2BooleanOpenHashMap();
    private static final Int2IntOpenHashMap ORDER_IDS = new Int2IntOpenHashMap();
    private static final LongOpenHashSet WAYPOINTS = new LongOpenHashSet();
    private static int patrolRevision = Integer.MIN_VALUE;
    private static boolean ordersPrimed;
    private static int ticks;

    private FxFeedback() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            SUMMON_ARRIVED.clear();
            ORDER_IDS.clear();
            WAYPOINTS.clear();
            ordersPrimed = false;
            patrolRevision = Integer.MIN_VALUE;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        try {
            if (ticks % 4 == 0) {
                summons(level);
                orders(level, mc.player);
            }
            if (ticks % 10 == 0) {
                patrol(level, mc.player);
            }
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.debug("FX feedback tick failed", failure);
        }
    }

    private static void summons(ClientLevel level) {
        List<SummonStatePayload.Row> rows = SummonClient.rows();
        if (rows.isEmpty()) {
            SUMMON_ARRIVED.clear();
            return;
        }
        for (SummonStatePayload.Row row : rows) {
            boolean known = SUMMON_ARRIVED.containsKey(row.entityId());
            boolean was = known && SUMMON_ARRIVED.get(row.entityId());
            SUMMON_ARRIVED.put(row.entityId(), row.arrived());
            if (!known || was || !row.arrived()) {
                continue;
            }
            Entity soldier = level.getEntity(row.entityId());
            if (soldier == null || !FxClient.inRange(soldier.getX(), soldier.getY(), soldier.getZ(),
                    FxEffect.SUMMON_ARRIVAL.range())) {
                continue;
            }
            float[] c = rgb(SummonClient.SUMMON_OUTLINE_RGB);
            FxRecipes.ring(level, ModParticles.MOTE.get(), soldier.getX(), soldier.getY() + 0.1D,
                soldier.getZ(), FxClient.count(10, true), 0.4D, 0.05D, 0.02D, c);
            FxRecipes.burst(level, ModParticles.SPARKLE.get(), soldier.getX(),
                soldier.getY() + soldier.getBbHeight() + 0.2D, soldier.getZ(), FxClient.count(4, false), 0.06D, c);
            FxClient.playSound(FxEffect.SUMMON_ARRIVAL, soldier.getX(), soldier.getY() + 1.0D, soldier.getZ(), 1.0F);
        }
    }

    private static void orders(ClientLevel level, Player player) {
        FieldOrderStatePayload state = CommandClientState.state();
        if (state == null) {
            return;
        }
        for (FieldOrderStatePayload.GroupLine line : state.groups()) {
            boolean known = ORDER_IDS.containsKey(line.group());
            int previous = known ? ORDER_IDS.get(line.group()) : 0;
            ORDER_IDS.put(line.group(), line.orderId());
            if (!ordersPrimed || previous == line.orderId()) {
                continue;
            }
            Group arm = Group.fromWire(line.group()).orElse(null);
            if (arm == null) {
                continue;
            }
            float[] c = rgb(CommandStyle.rgb(arm));
            for (var soldier : CommandClientState.inEarshot(arm)) {
                FxRecipes.ring(level, ModParticles.MOTE.get(), soldier.getX(), soldier.getY() + 0.06D,
                    soldier.getZ(), FxClient.count(6, true), 0.45D, 0.0D, 0.004D, c);
            }
            FxClient.playSound(FxEffect.ORDER_CONFIRMED, player.getX(), player.getY(), player.getZ(), 1.0F);
        }
        ordersPrimed = true;
    }

    private static boolean holdingMap(Player player) {
        return player.getMainHandItem().getItem() instanceof PatrolMapItem
            || player.getOffhandItem().getItem() instanceof PatrolMapItem;
    }

    private static void patrol(ClientLevel level, Player player) {
        PatrolSnapshotPayload snapshot = PatrolClient.snapshot();
        if (snapshot == null || !holdingMap(player)
            || !level.dimension().location().toString().equals(snapshot.dimension())) {
            WAYPOINTS.clear();
            patrolRevision = Integer.MIN_VALUE;
            return;
        }
        boolean first = patrolRevision == Integer.MIN_VALUE;
        boolean fresh = !first && snapshot.revision() != patrolRevision;
        patrolRevision = snapshot.revision();
        for (PatrolSnapshotPayload.Route route : snapshot.routes()) {
            boolean selected = route.id() == snapshot.selectedRoute();
            float[] c = rgb(route.color());
            for (BlockPos p : route.waypoints()) {
                boolean added = WAYPOINTS.add(p.asLong());
                double x = p.getX() + 0.5D;
                double y = p.getY() + (selected ? 1.6D : 1.1D);
                double z = p.getZ() + 0.5D;
                if (!FxClient.inRange(x, y, z, FxEffect.PATROL_WAYPOINT.range())) {
                    continue;
                }
                if (added && fresh) {
                    FxRecipes.burst(level, ModParticles.SPARKLE.get(), x, y, z, FxClient.count(8, true), 0.1D, c);
                    FxClient.playSound(FxEffect.PATROL_WAYPOINT, x, y, z, 1.0F);
                } else if (selected && level.random.nextFloat() < 0.5F) {
                    FxRecipes.p(ModParticles.MOTE.get(), x, y, z, 0.0D, 0.015D, 0.0D, c);
                }
            }
        }
    }

    static float[] rgb(int rgb) {
        return new float[] {((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F};
    }
}

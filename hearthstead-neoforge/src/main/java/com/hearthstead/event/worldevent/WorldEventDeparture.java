package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.Departure;
import com.hearthstead.conversation.DepartureRules;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Owner rule (26 Sep): departing event NPCs never vanish in a puff. This is
 * a thin delegate to the conversation lane's shared {@link Departure}
 * (group walk 48-64 blocks out, farewell beat, harmless while leaving,
 * despawn only unseen after walking 20 blocks). The event-specific part
 * kept here is {@link #roadExit}: caravans roll out along a real road.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WorldEventDeparture {
    /** Tag of this lane's earlier stand-in; walkers saved with it are handed to the shared helper. */
    private static final String LEGACY_TAG = "HearthsteadDeparting";
    public static final double FAR = DepartureRules.FAR;
    public static final double HIDDEN = DepartureRules.HIDDEN_NEAR;
    public static final int MIN_WALK = (int) DepartureRules.MIN_WALK;

    private WorldEventDeparture() {
    }

    public static void depart(ServerLevel level, List<? extends Entity> group, BlockPos from,
                              @Nullable BlockPos preferredExit, @Nullable Component farewell) {
        Departure.depart(level, group, from, preferredExit, farewell);
    }

    public static boolean isDeparting(Entity entity) {
        return Departure.isDeparting(entity);
    }

    public static boolean unseen(ServerLevel level, Entity entity) {
        return Departure.unseen(level, entity);
    }

    /**
     * A leaving trader is no shop (bug hunt, 26 Sep): released from its event,
     * a departing peddler or caravan master is a plain wandering trader, and
     * the coin-market hook (GoldCoinTrades) would stock and open it for anyone
     * who right-clicks it on the way out. Cancelled first, so nothing else runs.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void noShopWhileLeaving(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof net.minecraft.world.entity.npc.AbstractVillager trader
            && Departure.isDeparting(trader)) {
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Mob mob
            && mob.getPersistentData().contains(LEGACY_TAG)) {
            BlockPos from = BlockPos.of(mob.getPersistentData().getCompound(LEGACY_TAG).getLong("From"));
            mob.getPersistentData().remove(LEGACY_TAG);
            Departure.depart(level, List.of(mob), from, null, null);
        }
    }

    /**
     * A road out: among 32 headings from {@code from}, the one with the most
     * path/gravel/cobble surface blocks in the first 16 blocks (never back
     * through the settlement). Null when no heading has any road.
     */
    @Nullable
    public static BlockPos roadExit(ServerLevel level, BlockPos from, BlockPos settlementCenter) {
        double awayX = from.getX() - settlementCenter.getX(), awayZ = from.getZ() - settlementCenter.getZ();
        double awayLen = Math.max(1.0D, Math.sqrt(awayX * awayX + awayZ * awayZ));
        int bestScore = 0;
        double bestAngle = 0;
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI / 16.0D;
            double cx = Math.cos(angle), cz = Math.sin(angle);
            if ((cx * awayX + cz * awayZ) / awayLen < -0.2D) continue; // not back into town
            int score = 0;
            for (int step = 2; step <= 16; step += 2) {
                int x = from.getX() + (int) Math.round(cx * step), z = from.getZ() + (int) Math.round(cz * step);
                BlockPos column = new BlockPos(x, from.getY(), z);
                if (!level.hasChunkAt(column)) break;
                BlockState surface = level.getBlockState(new BlockPos(x,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z));
                if (surface.is(Blocks.DIRT_PATH) || surface.is(Blocks.GRAVEL) || surface.is(Blocks.COBBLESTONE)
                    || surface.is(Blocks.STONE_BRICKS) || surface.is(Blocks.COARSE_DIRT)) score++;
            }
            if (score > bestScore) { bestScore = score; bestAngle = angle; }
        }
        if (bestScore < 2) return null;
        int x = from.getX() + (int) Math.round(Math.cos(bestAngle) * 56.0D);
        int z = from.getZ() + (int) Math.round(Math.sin(bestAngle) * 56.0D);
        return new BlockPos(x, from.getY(), z);
    }
}

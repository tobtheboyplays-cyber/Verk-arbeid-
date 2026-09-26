package com.hearthstead.util;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Last-resort placement of a REAL item that no container would take (a
 * superseded guard's armour, a workshop's overflow, a settler's leftovers).
 *
 * <p>Never use {@code Block.popResource} for this: it is block-loot policy
 * and silently does nothing when the {@code doTileDrops} gamerule is off, so
 * the item would simply vanish (BH-16, Codex P2). This goes through the
 * durable deferred-drop ledger (spawned now when the chunk is loaded, retried
 * after a reload otherwise), then a direct ItemEntity. If both refuse (the
 * ledger full or quarantined AND the spawn cancelled), the stack is still
 * owned: it waits in a bounded backlog that retries every tick (BH-16
 * follow-up), so a caller that already cleared its source never loses it.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ItemSpill {
    /** Backlog attempts per server tick. */
    static final int RETRIES_PER_TICK = 8;
    /** Rows kept before the oldest is reported (logged, never silently dropped). */
    static final int MAX_BACKLOG = 4096;

    private record Held(ResourceKey<Level> dimension, BlockPos pos, ItemStack stack) {
    }

    private static final Deque<Held> BACKLOG = new ArrayDeque<>();
    /** GameTest seam: simulate the ledger AND the spawn refusing. */
    private static volatile boolean refuseForTests;

    private ItemSpill() {
    }

    /**
     * Puts {@code stack} into the world at the centre of {@code pos}. The
     * caller's stack is not mutated. Always takes ownership of a non-empty
     * stack: false means only "not in the world yet" (it is in the backlog).
     */
    public static boolean conserve(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (level == null || pos == null || stack == null || stack.isEmpty()) {
            return true;
        }
        if (tryPlace(level, pos, stack)) {
            return true;
        }
        synchronized (BACKLOG) {
            if (BACKLOG.size() >= MAX_BACKLOG) {
                Held oldest = BACKLOG.peekFirst();
                Hearthstead.LOGGER.error("ItemSpill backlog full; still holding {} x{} (oldest {} x{})",
                    stack.getItem(), stack.getCount(),
                    oldest == null ? "?" : oldest.stack().getItem(), oldest == null ? 0 : oldest.stack().getCount());
            }
            BACKLOG.addLast(new Held(level.dimension(), pos.immutable(), stack.copy()));
        }
        Hearthstead.LOGGER.warn("ItemSpill could not place {} x{} at {} yet; holding it for retry",
            stack.getItem(), stack.getCount(), pos);
        return false;
    }

    private static boolean tryPlace(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (refuseForTests) {
            return false;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.25D;
        double z = pos.getZ() + 0.5D;
        DeferredItemMaterializationSavedData drops = DeferredItemMaterializationSavedData.get(level);
        UUID row = drops.queue(level, x, y, z, stack.copy());
        if (row != null) {
            drops.materialize(level, row); // not loaded / refused: the ledger retries
            return true;
        }
        ItemEntity item = new ItemEntity(level, x, y, z, stack.copy());
        item.setDefaultPickUpDelay();
        return level.addFreshEntity(item);
    }

    /** GameTest seam: while true every placement is refused (the backlog holds the stacks). */
    public static void refuseForTests(boolean refuse) {
        refuseForTests = refuse;
    }

    /** Stacks still waiting for a place in the world (tests, diagnostics). */
    public static int backlog() {
        synchronized (BACKLOG) {
            return BACKLOG.size();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        retry(event.getServer(), RETRIES_PER_TICK);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        retry(event.getServer(), Integer.MAX_VALUE);
        int left = backlog();
        if (left > 0) {
            Hearthstead.LOGGER.error("ItemSpill: {} stack(s) could not be placed before shutdown", left);
        }
        // Never carry a held stack into the next world opened in this JVM
        // (singleplayer world switch): it belongs to this save only.
        synchronized (BACKLOG) {
            BACKLOG.clear();
        }
    }

    private static void retry(MinecraftServer server, int budget) {
        for (int i = 0; i < budget; i++) {
            Held held;
            synchronized (BACKLOG) {
                held = BACKLOG.pollFirst();
            }
            if (held == null) {
                return;
            }
            ServerLevel level = server.getLevel(held.dimension());
            if (level == null) {
                level = server.overworld();
            }
            if (!tryPlace(level, held.pos(), held.stack())) {
                synchronized (BACKLOG) {
                    BACKLOG.addLast(held);
                }
                return; // still refused: try again next tick
            }
        }
    }
}

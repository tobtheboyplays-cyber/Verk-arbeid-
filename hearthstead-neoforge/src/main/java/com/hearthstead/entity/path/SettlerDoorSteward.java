package com.hearthstead.entity.path;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Remembers every door, gate and hatch a settler opened and closes it once
 * nobody is in it, so no passage a settler used is left open (at night or
 * otherwise). Passages a player opened are never recorded and never touched.
 *
 * <p>The door goal closes behind its own settler; this steward covers the
 * cases that goal cannot: it was interrupted, another settler was standing
 * nearby when it stopped, or the settler died or unloaded mid-passage.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class SettlerDoorSteward {
    /** A passage untouched this long, with nobody in it, is closed. */
    static final long IDLE_BEFORE_CLOSE = 40L;
    private static final int SWEEP_INTERVAL = 20;
    private static final int MAX_TRACKED = 512;
    private static final Map<ServerLevel, Map<BlockPos, Long>> OPENED = new WeakHashMap<>();

    private SettlerDoorSteward() {
    }

    /** The door goal opened (or is holding open) this passage now. */
    public static void touched(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Long> map = OPENED.computeIfAbsent(level, ignored -> new HashMap<>());
        if (map.size() >= MAX_TRACKED && !map.containsKey(pos)) return;
        map.put(pos.immutable(), level.getGameTime());
    }

    /** The passage was closed by its owner; stop tracking it. */
    public static void closed(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Long> map = OPENED.get(level);
        if (map != null) map.remove(pos);
    }

    public static int tracked(ServerLevel level) {
        Map<BlockPos, Long> map = OPENED.get(level);
        return map == null ? 0 : map.size();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % SWEEP_INTERVAL != 0) return;
        Map<BlockPos, Long> map = OPENED.get(level);
        if (map == null || map.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<BlockPos, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) {
                it.remove();
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!IndoorBlocks.isOpen(state) || IndoorBlocks.openablePassage(level, pos) == null) {
                it.remove();
                continue;
            }
            if (now - entry.getValue() < IDLE_BEFORE_CLOSE) continue;
            // Only a body the closed door would actually hit keeps it open;
            // a settler standing beside the doorway does not.
            if (IndoorBlocks.closingWouldHit(level, pos)) {
                continue;
            }
            setOpen(level, pos, false, null);
            it.remove();
        }
    }

    /**
     * Opens or closes one passage the way a hand would, with its sound and
     * game event. Returns true only when the collision state changed.
     */
    public static boolean setOpen(ServerLevel level, BlockPos pos, boolean open,
                                  @Nullable LivingEntity actor) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door) {
            if (!door.type().canOpenByHand() || state.getValue(DoorBlock.OPEN) == open) return false;
            door.setOpen(actor, level, state, pos, open);
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            if (state.getValue(FenceGateBlock.OPEN) == open) return false;
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), 10);
            level.playSound(null, pos, open ? net.minecraft.sounds.SoundEvents.FENCE_GATE_OPEN
                    : net.minecraft.sounds.SoundEvents.FENCE_GATE_CLOSE,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(actor, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
            return true;
        }
        if (state.getBlock() instanceof TrapDoorBlock
            && state.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS)) {
            if (state.getValue(TrapDoorBlock.OPEN) == open) return false;
            level.setBlock(pos, state.setValue(TrapDoorBlock.OPEN, open), 2);
            level.playSound(null, pos, open ? net.minecraft.sounds.SoundEvents.WOODEN_TRAPDOOR_OPEN
                    : net.minecraft.sounds.SoundEvents.WOODEN_TRAPDOOR_CLOSE,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(actor, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
            return true;
        }
        return false;
    }
}

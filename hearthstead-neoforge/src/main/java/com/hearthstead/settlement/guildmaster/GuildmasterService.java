package com.hearthstead.settlement.guildmaster;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.GuildmasterEntity;
import com.hearthstead.network.DevelopmentNetwork;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps exactly one {@link GuildmasterEntity} seated at every loaded Banner.
 *
 * <h2>One per Banner, no duplicates</h2>
 * <ul>
 *   <li>{@link GuildmasterRegistry} links settlement -> Guildmaster UUID.</li>
 *   <li>{@link #ensure} runs only when the Banner block and the seat are in
 *       entity-ticking chunks, i.e. their entity sections are loaded. Only
 *       then can "the linked Guildmaster is not here" be believed.</li>
 *   <li>A loaded Guildmaster of this settlement found near the Banner is
 *       adopted instead of spawning a new one; extra ones are discarded.</li>
 *   <li>Every Guildmaster checks himself ({@link #check}) once a second and
 *       removes himself when his settlement (Banner) is gone or another UUID
 *       is linked and present.</li>
 * </ul>
 *
 * <h2>Banner removal and relocation</h2>
 * Breaking the Banner disbands the settlement, so its Guildmaster leaves at
 * his next check. If a settlement's centre ever moves, he re-seats himself
 * beside the Banner at its new position.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GuildmasterService {
    /** Seat search runs every 5 s per dimension; cheap block reads only. */
    static final int ENSURE_INTERVAL = 100;
    /** Adoption radius around the Banner for a Guildmaster that lost its link. */
    private static final double ADOPT_RADIUS = 8.0D;

    public record Seat(BlockPos pos, float yaw) {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % ENSURE_INTERVAL != 53L) {
            return;
        }
        ensureAll(level);
    }

    /** One pass over every settlement of this dimension. */
    public static void ensureAll(ServerLevel level) {
        GuildmasterRegistry registry = GuildmasterRegistry.get(level);
        for (UUID linked : registry.linkedSettlements()) {
            if (SettlementManager.byId(level, linked) == null) {
                registry.unlink(linked); // disbanded: the NPC removes himself
            }
        }
        for (Settlement settlement : List.copyOf(SettlementManager.data(level).settlements.values())) {
            ensure(level, settlement);
        }
    }

    /**
     * The seated Guildmaster for this settlement, spawning or adopting one
     * when (and only when) the Banner area is fully loaded. Null while the
     * area is not loaded or no seat could be found.
     */
    @Nullable
    public static GuildmasterEntity ensure(ServerLevel level, Settlement settlement) {
        HearthBlockEntity banner = boundBanner(level, settlement);
        if (banner == null) {
            return null;
        }
        GuildmasterRegistry registry = GuildmasterRegistry.get(level);
        UUID linked = registry.idFor(settlement.id);
        if (linked != null && level.getEntity(linked) instanceof GuildmasterEntity live
            && live.isAlive()) {
            return live;
        }
        Seat seat = seatFor(level, settlement, banner.getBlockState());
        if (!level.isPositionEntityTicking(settlement.center)
            || !level.isPositionEntityTicking(seat.pos())) {
            return null; // entities not loaded yet: absence proves nothing
        }
        // Adopt a loaded Guildmaster of this settlement (e.g. the link was
        // lost), keep one, discard any extra.
        List<GuildmasterEntity> near = new ArrayList<>(level.getEntitiesOfClass(
            GuildmasterEntity.class, new AABB(settlement.center).inflate(ADOPT_RADIUS),
            g -> g.isAlive() && settlement.id.equals(g.settlementId())));
        if (!near.isEmpty()) {
            GuildmasterEntity keep = near.get(0);
            for (int i = 1; i < near.size(); i++) {
                near.get(i).discard();
            }
            registry.link(settlement.id, keep.getUUID());
            return keep;
        }
        GuildmasterEntity spawned = com.hearthstead.registry.GuildmasterEntities.GUILDMASTER.get()
            .create(level);
        if (spawned == null) {
            return null;
        }
        spawned.bind(settlement.id, seat.pos(), seat.yaw());
        spawned.finalizeSpawn(level, level.getCurrentDifficultyAt(seat.pos()),
            MobSpawnType.EVENT, null);
        if (!level.addFreshEntity(spawned)) {
            return null;
        }
        registry.link(settlement.id, spawned.getUUID());
        return spawned;
    }

    /**
     * Self-check, once a second from the entity: leave when the settlement
     * is gone or another Guildmaster owns the link; re-seat after a Banner
     * move or if something displaced him.
     */
    public static void check(ServerLevel level, GuildmasterEntity guildmaster) {
        UUID settlementId = guildmaster.settlementId();
        Settlement settlement = settlementId == null ? null
            : SettlementManager.byId(level, settlementId);
        if (settlement == null) {
            guildmaster.discard();
            return;
        }
        GuildmasterRegistry registry = GuildmasterRegistry.get(level);
        UUID linked = registry.idFor(settlement.id);
        if (linked == null) {
            registry.link(settlement.id, guildmaster.getUUID());
        } else if (!linked.equals(guildmaster.getUUID())) {
            if (level.getEntity(linked) instanceof GuildmasterEntity other
                && other.isAlive() && other != guildmaster) {
                guildmaster.discard(); // duplicate: the linked one stays
                return;
            }
            registry.link(settlement.id, guildmaster.getUUID()); // linked one is gone
        }
        HearthBlockEntity banner = boundBanner(level, settlement);
        if (banner == null) {
            return; // Banner chunk not loaded right now: stay put
        }
        BlockPos seat = guildmaster.seat();
        boolean bannerMoved = seat == null || seat.distManhattan(settlement.center) > 4;
        boolean blocked = seat != null && !seatFree(level, seat);
        if (bannerMoved || blocked) {
            Seat next = seatFor(level, settlement, banner.getBlockState());
            guildmaster.seatAt(next.pos(), next.yaw());
            return;
        }
        if (guildmaster.distanceToSqr(seat.getX() + 0.5D, seat.getY(), seat.getZ() + 0.5D) > 0.36D) {
            guildmaster.seatAt(seat, guildmaster.seatYaw());
        }
    }

    /** The loaded, alive Guildmaster linked to this settlement, or null. */
    @Nullable
    public static GuildmasterEntity live(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) {
            return null;
        }
        UUID linked = GuildmasterRegistry.get(level).idFor(settlement.id);
        return linked != null && level.getEntity(linked) instanceof GuildmasterEntity g
            && g.isAlive() && settlement.id.equals(g.settlementId()) ? g : null;
    }

    @Nullable
    public static UUID liveId(ServerLevel level, Settlement settlement) {
        GuildmasterEntity g = live(level, settlement);
        return g == null ? null : g.getUUID();
    }

    /** Whether this exact entity is the settlement's live Guildmaster within reach of the player. */
    public static boolean inReach(ServerPlayer player, Settlement settlement, @Nullable UUID claimed) {
        if (player == null || settlement == null || claimed == null) {
            return false;
        }
        GuildmasterEntity g = live(player.serverLevel(), settlement);
        return g != null && g.getUUID().equals(claimed)
            && g.level() == player.level()
            && player.distanceToSqr(g) <= GuildmasterTrade.REACH_SQUARED;
    }

    /** Right-click on the Guildmaster: open Professions & Emblems. */
    public static void openTrade(ServerPlayer player, GuildmasterEntity guildmaster) {
        if (player == null || guildmaster == null || player.isSpectator()
            || !(player.level() instanceof ServerLevel level)
            || guildmaster.level() != level) {
            return;
        }
        Settlement settlement = guildmaster.settlementId() == null ? null
            : SettlementManager.byId(level, guildmaster.settlementId());
        HearthBlockEntity banner = boundBanner(level, settlement);
        if (settlement == null || banner == null
            || !guildmaster.getUUID().equals(GuildmasterRegistry.get(level).idFor(settlement.id))
            || player.distanceToSqr(guildmaster) > GuildmasterTrade.REACH_SQUARED) {
            return;
        }
        guildmaster.greet(player);
        JourneyServerHooks.noteGuildmasterMet(player, settlement, guildmaster.getUUID());
        DevelopmentNetwork.openEmblemShop(player, guildmaster, settlement, banner);
    }

    // --------------------------------------------------------------- seats ---

    @Nullable
    private static HearthBlockEntity boundBanner(ServerLevel level, @Nullable Settlement settlement) {
        if (level == null || settlement == null || !level.isLoaded(settlement.center)
            || !(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity banner)
            || !settlement.id.equals(banner.getSettlementId())) {
            return null;
        }
        return banner;
    }

    /**
     * Beside the Banner stand, facing the same way as its ledger side, so the
     * front of the Banner stays free: right side first, then left, then the
     * front corners, then a ring two blocks out. Falls back to the block in
     * front of the stand.
     */
    public static Seat seatFor(ServerLevel level, Settlement settlement, BlockState bannerState) {
        Direction facing = bannerState.hasProperty(HearthBlock.FACING)
            ? bannerState.getValue(HearthBlock.FACING) : Direction.NORTH;
        float yaw = facing.toYRot();
        BlockPos c = settlement.center;
        Direction right = facing.getClockWise();
        Direction left = facing.getCounterClockWise();
        BlockPos[] candidates = {
            c.relative(right), c.relative(left),
            c.relative(right).relative(facing), c.relative(left).relative(facing),
            c.relative(right, 2), c.relative(left, 2),
            c.relative(right, 2).relative(facing), c.relative(left, 2).relative(facing),
            c.relative(facing, 2).relative(right), c.relative(facing, 2).relative(left),
        };
        for (BlockPos base : candidates) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos p = base.above(dy);
                if (seatFree(level, p)) {
                    return new Seat(p, yaw);
                }
            }
            BlockPos up = base.above();
            if (seatFree(level, up)) {
                return new Seat(up, yaw);
            }
        }
        return new Seat(c.relative(facing), yaw);
    }

    /** Two free blocks to sit in and solid ground under the stool. */
    static boolean seatFree(ServerLevel level, BlockPos p) {
        return level.isLoaded(p)
            && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
            && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
            && level.getFluidState(p).isEmpty()
            && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }

    private GuildmasterService() {
    }
}

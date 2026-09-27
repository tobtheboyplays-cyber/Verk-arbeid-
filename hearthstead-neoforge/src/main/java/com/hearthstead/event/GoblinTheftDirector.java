package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.*;
import com.hearthstead.settlement.development.*;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Sparse eligible-play cadence; never replaces an unloaded outstanding thief. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GoblinTheftDirector {
    private GoblinTheftDirector() {}

    /**
     * QA for the goblin scenes (bug hunt, 26 Sep; Codex films them):
     * {@code /hsgoblin thief} publishes a natural thief at the nearest village now
     * (a Coin store first, else the nearest survival player with Coins);
     * {@code /hsgoblin thief <player>} makes that player the pickpocket target;
     * {@code /hsgoblin status} shows the cadence row. Op level 2.
     */
    @SubscribeEvent public static void commands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("hsgoblin")
            .requires(source -> source.hasPermission(2))
            .then(net.minecraft.commands.Commands.literal("thief")
                .executes(ctx -> forceCommand(ctx.getSource(), null))
                .then(net.minecraft.commands.Commands.argument("player",
                        net.minecraft.commands.arguments.EntityArgument.player())
                    .executes(ctx -> forceCommand(ctx.getSource(),
                        net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")))))
            .then(net.minecraft.commands.Commands.literal("status")
                .executes(ctx -> statusCommand(ctx.getSource()))));
    }

    private static Settlement nearest(net.minecraft.commands.CommandSourceStack source) {
        var saved = SettlementSavedData.existing(source.getLevel());
        if (saved == null) return null;
        Settlement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Settlement settlement : saved.settlements.values()) {
            double d = settlement.center.distToCenterSqr(source.getPosition());
            if (d < bestDistance) { bestDistance = d; best = settlement; }
        }
        return best;
    }

    private static int forceCommand(net.minecraft.commands.CommandSourceStack source, ServerPlayer victim) {
        Settlement settlement = nearest(source);
        if (settlement == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("No settlement in this dimension."));
            return 0;
        }
        String failure = forceNatural(source.getLevel(), settlement, victim);
        if (failure != null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("No goblin thief: " + failure + "."));
            return 0;
        }
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("A goblin thief sets out for "
            + settlement.name + (victim != null ? " (target: " + victim.getScoreboardName() + ")." : ".")), true);
        return 1;
    }

    private static int statusCommand(net.minecraft.commands.CommandSourceStack source) {
        Settlement settlement = nearest(source);
        var data = GoblinTheftSavedData.existing(source.getLevel());
        var view = settlement == null || data == null ? null : data.view(settlement.id);
        String text = settlement == null ? "No settlement in this dimension."
            : view == null ? settlement.name + ": no goblin row yet (hostile-ready: "
                + com.hearthstead.event.worldevent.WorldEventDirector.hostileReady(source.getLevel(), settlement) + ")."
            : settlement.name + ": eligible " + view.eligibleTicks() + "/" + view.nextVisit() + " ticks, active "
                + (view.active() == null ? "none" : view.active()) + ", last " + view.outcome()
                + ", hostile-ready " + com.hearthstead.event.worldevent.WorldEventDirector.hostileReady(source.getLevel(), settlement) + ".";
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }
    @SubscribeEvent public static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
            || level.getGameTime() % 20 != 0) return;
        var settlements = SettlementSavedData.existing(level);
        if (settlements == null) return;
        int checked = 0;
        for (Settlement settlement : settlements.settlements.values()) {
            if (++checked > 256) break;
            observe(level, settlement);
        }
    }
    public static void observe(ServerLevel level, Settlement settlement) {
        long now = level.getGameTime();
        var data = GoblinTheftSavedData.get(level);
        BlockPos target = eligibleTarget(level, settlement);
        var playerTarget = target == null ? eligiblePlayer(level, settlement) : null;
        data.observe(settlement.id, now, target != null || playerTarget != null);
        if (target == null && playerTarget == null) return;
        if (!data.mayAttempt(settlement.id,now)) return;
        data.attempted(settlement.id,now);
        publish(level, settlement, data, target, playerTarget);
    }

    /** Spawns one natural thief for a chest {@code target} or a {@code playerTarget}; null when no start fits. */
    private static com.hearthstead.entity.RaiderEntity publish(ServerLevel level, Settlement settlement,
            GoblinTheftSavedData data, BlockPos target, ServerPlayer playerTarget) {
        int rotation = level.random.nextInt(16);
        // Player-source visitors still begin outside the village edge, just
        // like chest thieves. Their longer travel window lives in the actor;
        // only the final eight-block approach is a pickpocket attempt.
        int minimumRadius = 24;
        int radiusSpread = 13;
        BlockPos origin = playerTarget == null ? target : playerTarget.blockPosition();
        for (int i=0;i<16;i++) {
            double angle = (rotation+i)*Math.PI/8;
            int radius = minimumRadius + level.random.nextInt(radiusSpread);
            int x=origin.getX()+(int)Math.round(Math.cos(angle)*radius);
            int z=origin.getZ()+(int)Math.round(Math.sin(angle)*radius);
            BlockPos column = new BlockPos(x,origin.getY(),z);
            if (!level.hasChunkAt(column)) continue;
            BlockPos start = new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
            if (settlement.buildings.stream().anyMatch(building -> building.valid && building.contains(start))) continue;
            var thief = playerTarget == null
                ? GoblinThiefDemo.spawnNatural(level,target,start,settlement.id)
                : GoblinThiefDemo.spawnNaturalPlayer(level,playerTarget,start,settlement.id);
            if (thief != null) {
                data.published(settlement.id,thief.getUUID());
                Hearthstead.LOGGER.info("HEARTHSTEAD_GOBLIN_PUBLISHED settlement={} actor={} target={}",settlement.id,thief.getUUID(),target);
                return thief;
            }
        }
        return null;
    }

    /**
     * QA (/hsgoblin thief [player], op 2; bug hunt, 26 Sep): publishes a real
     * NATURAL thief now -- the same spawn, witnesses, messages, custody and
     * cleanup as in play -- skipping only the hostile grace and the cadence.
     * {@code victim} forces a pickpocket attempt on that player; null prefers
     * a registered Coin store, then the nearest carrying player.
     * Returns a failure reason, or null on success.
     */
    public static String forceNatural(ServerLevel level, Settlement settlement, ServerPlayer victim) {
        if (!eligibleSettlement(level, settlement, false)) {
            return "the village is not ready: Banner loaded and bound, not Peaceful, no raid on, a player within 80 blocks";
        }
        BlockPos target = null;
        ServerPlayer playerTarget = null;
        if (victim != null) {
            if (!GoblinThiefDemo.eligiblePickpocketVictim(victim)) return victim.getScoreboardName()
                + " must be in survival and carry at least 1 Coin";
            playerTarget = victim;
        } else {
            target = storeTarget(level, settlement);
            if (target == null) playerTarget = nearestVictim(level, settlement);
            if (target == null && playerTarget == null) {
                return "no registered store with Coins and no survival player with Coins within 80 blocks";
            }
        }
        var data = GoblinTheftSavedData.get(level);
        if (!data.forceReady(settlement.id, level.getGameTime())) return "a thief is already out (or the goblin file is quarantined)";
        return publish(level, settlement, data, target, playerTarget) == null
            ? "no reachable start 24-36 blocks out (walled in, or unloaded terrain)" : null;
    }
    public static BlockPos eligibleTarget(ServerLevel level, Settlement settlement) {
        if (!eligibleSettlement(level, settlement)) return null;
        return storeTarget(level, settlement);
    }
    private static BlockPos storeTarget(ServerLevel level, Settlement settlement) {
        int checked=0;
        for (Building building : settlement.buildings) {
            if (++checked > 64) break;
            if (!validStore(level,settlement,building)) continue;
            for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level,building))
                if (coinCount(level,pos) >= 1) return pos;
        }
        return null;
    }
    private static boolean eligibleSettlement(ServerLevel level, Settlement settlement) {
        return eligibleSettlement(level, settlement, true);
    }
    private static boolean eligibleSettlement(ServerLevel level, Settlement settlement, boolean grace) {
        return settlement != null && level.getDifficulty() != Difficulty.PEACEFUL
            // Survival QA #7: the same early grace as hostile world events --
            // no thief before the village is HOSTILE_GRACE_DAYS old AND has a
            // hired martial settler (or has come through its first raid).
            && (!grace || com.hearthstead.event.worldevent.WorldEventDirector.hostileReady(level, settlement))
            && level.hasChunkAt(settlement.center) && settlement.pendingRaid == null
            && !settlement.raidLifecycle.isAuthoredFirstRaidActive()
            && !settlement.recurringRaidRun.isActive()
            && level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth
            && settlement.id.equals(hearth.getSettlementId())
            && level.players().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(settlement.center.getX(), settlement.center.getY(),
                    settlement.center.getZ()) <= 80 * 80);
    }

    /** Player Coins are a distinct source from registered storage. This only
     * participates after the same peaceful/raid/proximity gates as chest theft. */
    private static ServerPlayer eligiblePlayer(ServerLevel level, Settlement settlement) {
        if (!eligibleSettlement(level, settlement)) return null;
        return nearestVictim(level, settlement);
    }
    private static ServerPlayer nearestVictim(ServerLevel level, Settlement settlement) {
        return level.players().stream()
            .filter(GoblinThiefDemo::eligiblePickpocketVictim)
            .filter(player -> player.distanceToSqr(settlement.center.getX(), settlement.center.getY(),
                settlement.center.getZ()) <= 80 * 80)
            .min(java.util.Comparator.comparingDouble(player -> player.distanceToSqr(
                settlement.center.getX(), settlement.center.getY(), settlement.center.getZ())))
            .orElse(null);
    }
    private static boolean validStore(ServerLevel level, Settlement settlement, Building building) {
        return building.valid && building.type != null && building.plaquePos != null
            && level.hasChunkAt(building.plaquePos)
            && level.getBlockEntity(building.plaquePos) instanceof PlaqueBlockEntity plaque
            && building.id.equals(plaque.buildingId()) && plaque.type() == building.type
            && plaque.settlementFor(level) == settlement;
    }
    public static boolean targetStillOwned(ServerLevel level, java.util.UUID settlementId, BlockPos pos) {
        var saved = SettlementSavedData.existing(level);
        var settlement = saved == null ? null : saved.settlements.get(settlementId);
        if (settlement == null || !level.hasChunkAt(pos)) return false;
        for (Building building : settlement.buildings)
            if (building.contains(pos) && validStore(level,settlement,building)
                && WorkerStorageAuthority.loadedContainers(level,building).contains(pos)) return true;
        return false;
    }
    public static int coinCount(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Container container)) return 0;
        long total=0;
        for(int slot=0;slot<container.getContainerSize();slot++) if(container.getItem(slot).is(ModItems.GOLD_COIN.get())) total+=container.getItem(slot).getCount();
        return (int)Math.min(Integer.MAX_VALUE,total);
    }
    /** A caught or ignored goblin remains meaningful without taking a
     * first-defense budget in one fast sequence of encounters. */
    public static int theftLimit(int coins) {
        if (coins <= 0) return 0;
        if (coins < 25) return 1;
        if (coins < 50) return 2;
        return 3;
    }
    @SubscribeEvent public static void removed(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof com.hearthstead.entity.RaiderEntity thief)
            || !(event.getLevel() instanceof ServerLevel level)) return;
        var state=thief.getPersistentData().getCompound("HearthsteadGoblinThiefDemo");
        if (!state.hasUUID("NaturalSettlement")) return;
        var reason=thief.getRemovalReason();
        boolean died=reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED;
        // An escape empties the sack right before discard; the Escaped flag tells it apart.
        boolean emptyDeparture=reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED
            && (thief.lootCount()==0 || state.getBoolean("Escaped"));
        if (!died && !emptyDeparture) return;
        var data=GoblinTheftSavedData.existing(level);
        if (data != null) data.finished(state.getUUID("NaturalSettlement"),thief.getUUID(),
            died ? "killed" : state.getBoolean("Escaped") ? "escaped" : "empty_departure");
    }
}

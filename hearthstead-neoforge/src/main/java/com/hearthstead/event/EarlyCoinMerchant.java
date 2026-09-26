package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.util.QaTrace;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Finite-stock visitors on a saved site cadence; no free Coins or reload restock. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class EarlyCoinMerchant {
    private static final Map<Settlement, Long> RETRIES = new WeakHashMap<>();
    private static final Map<Settlement, String> LAST_ATTEMPT = new WeakHashMap<>();
    private static final String RECEIPTS = "hearthstead_early_merchants";
    private static final long RETRY_TICKS = 1000L;
    public static final long VISIT_TICKS = 24000L, PERIOD_TICKS = 24000L;
    /** Previous receipts used a full quiet day after the twenty-minute visit. */
    private static final long PREVIOUS_PERIOD_TICKS = 48000L;
    private static final long PREVIOUS_GAP_TICKS = PREVIOUS_PERIOD_TICKS - VISIT_TICKS;
    /** Older version-1 receipts used a 30k period and a 6k quiet gap. */
    private static final long LEGACY_PERIOD_TICKS = 30000L;
    private static final long LEGACY_GAP_TICKS = LEGACY_PERIOD_TICKS - VISIT_TICKS;
    private static final String OWNER = "HearthsteadEarlyMerchantSettlement";
    private static final String EXPIRES = "HearthsteadMerchantExpires";
    private static final String ARRIVAL_TARGET = "HearthsteadMerchantArrivalTarget";
    private static final String ARRIVED = "HearthsteadMerchantArrived";
    private static final Map<WanderingTrader, Boolean> ARRIVAL_GOALS = new WeakHashMap<>();
    private EarlyCoinMerchant() {}

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.dimension() != Level.OVERWORLD || level.getGameTime() % 100 != 0) return;
        SettlementSavedData settlements = SettlementSavedData.existing(level);
        if (settlements == null) return;
        int checked = 0;
        for (Settlement settlement : settlements.settlements.values()) {
            if (++checked > 256) break;
            if (settlement == null || settlement.mayorId == null
                || !level.hasChunkAt(settlement.center)
                || !level.getBlockState(settlement.center).is(ModBlocks.HEARTH.get())
                || level.players().stream().noneMatch(player -> player.isAlive()
                    && player.blockPosition().distSqr(settlement.center) <= 80 * 80)) continue;
            visit(level, settlement);
        }
    }

    /** Public production seam for bounded physical publication/replay GameTests. */
    public static boolean visit(ServerLevel level, Settlement settlement) {
        if (!level.getServer().isSameThread() || settlement == null
            || SettlementSavedData.get(level).settlements.get(settlement.id) != settlement
            || Mayor.find(level, settlement) == null || settlement.radius < 1 || settlement.radius > 80
            || !level.hasChunkAt(settlement.center)
            || !level.getBlockState(settlement.center).is(ModBlocks.HEARTH.get())
            || !(level.getBlockEntity(settlement.center) instanceof com.hearthstead.block.HearthBlockEntity hearth)
            || !settlement.id.equals(hearth.getSettlementId())) return false;
        Receipts receipts = level.getDataStorage().computeIfAbsent(Receipts.FACTORY, RECEIPTS);
        long now = level.getGameTime(), next = RETRIES.getOrDefault(settlement, 0L);
        if (now < 0 || now > Long.MAX_VALUE - PERIOD_TICKS
            || !receipts.available(settlement.center, now)) return false;
        if (now < next && next - now <= RETRY_TICKS) return false;
        RETRIES.put(settlement, now + RETRY_TICKS);
        WanderingTrader trader = EntityType.WANDERING_TRADER.create(level);
        if (trader == null) return failed(settlement, now, "entity_creation", 0, 0, 0, 0, 0);
        // Vanilla constructs its node budget from the original FOLLOW_RANGE.
        // An explicit 112-block path range alone does not enlarge that budget.
        double originalRange = trader.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        trader.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE).setBaseValue(112.0);
        trader.getNavigation().setMaxVisitedNodesMultiplier((float)Math.max(1.0, 112.0 / Math.max(1.0, originalRange)));
        Set<BlockPos> targets = new LinkedHashSet<>();
        for (int distance = 2; distance <= 6; distance++) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = nearbyFeet(level, trader, settlement.center.relative(facing, distance));
                if (candidate != null) targets.add(candidate);
            }
        }
        if (targets.isEmpty()) return failed(settlement, now, "no_safe_destination", 0, 0, 0, 0, 0);
        int paths = 0;
        int unloaded = 0, unsafeSpawn = 0, unreachable = 0, unsafePath = 0;
        // Rotate across retries so the same first eight viable columns cannot
        // starve other approaches on a village surrounded by uneven terrain.
        int rotation = (int)Math.floorMod(now / RETRY_TICKS + settlement.id.hashCode(), 32L);
        // Outer ring first: the merchant walks in from beyond the claim. Dense
        // forest or cliffs can leave every outer column without a route, which
        // used to starve the only early Coin source forever; inner rings keep
        // the same on-foot arrival while still preferring the outside approach.
        int[] rings = {settlement.radius + 8, Math.max(12, settlement.radius / 2), 12};
        for (int ring = 0; ring < rings.length; ring++) {
            int radius = rings[ring];
            if (ring > 0 && radius >= rings[ring - 1]) continue;
            int ringPaths = 0;
            for (int index = 0; index < 32 && ringPaths < 8; index++) {
                double angle = (index + rotation) * Math.PI / 16.0;
                BlockPos column = new BlockPos(settlement.center.getX() + (int)Math.round(Math.cos(angle) * radius),
                    settlement.center.getY(), settlement.center.getZ() + (int)Math.round(Math.sin(angle) * radius));
                if (!level.hasChunkAt(column)) { unloaded++; continue; }
                BlockPos feet = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX(), column.getZ()), column.getZ());
                if (!safeFeet(level, trader, feet) || !openFooting(level, trader, feet)) { unsafeSpawn++; continue; }
                trader.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
                trader.setOnGround(true);
                paths++; ringPaths++;
                // Vanilla region uses getChunkNow, so unloaded chunks remain empty;
                // the bounded arrival range belongs only to this event merchant.
                var path = trader.getNavigation().createPath(targets, 0);
                if (path == null || !path.canReach() || !targets.contains(path.getTarget())) {
                    unreachable++;
                    continue;
                }
                BlockPos target = path.getTarget();
                boolean safe = true;
                for (int node = 0; node < path.getNodeCount(); node++) {
                    if (!safeFeet(level, trader, path.getNodePos(node))) { safe = false; break; }
                }
                if (!safe) { unsafePath++; continue; }
                trader.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
                trader.setDespawnDelay((int)VISIT_TICKS);
                trader.setWanderTarget(target);
                trader.restrictTo(target, 16);
                trader.getPersistentData().putUUID(OWNER, settlement.id);
                trader.getPersistentData().putLong(EXPIRES, now + VISIT_TICKS);
                trader.getPersistentData().putLong(ARRIVAL_TARGET, target.asLong());
                // Before publication every Hearthstead visitor is Coin-only. The
                // requested goods are frozen at the first safe player/Trader use.
                if (!GoldCoinTrades.clearOwnedMerchantForPublication(trader, settlement.id))
                    return failed(settlement, now, "market_initialization", paths, unloaded, unsafeSpawn, unreachable, unsafePath);
                if (!level.addFreshEntity(trader)) return failed(settlement, now, "publication_rejected",
                    paths, unloaded, unsafeSpawn, unreachable, unsafePath);
                {
                    double dx = trader.getX() - (settlement.center.getX() + 0.5D);
                    double dz = trader.getZ() - (settlement.center.getZ() + 0.5D);
                    double horizontalDistanceSquared = dx * dx + dz * dz;
                    Hearthstead.LOGGER.info(
                        "HSQA_EARLY_MERCHANT_PUBLISHED settlement={} trader={} x={} y={} z={} centerX={} centerZ={} radius={} horizontalOutside={} tick={}",
                        settlement.id, trader.getUUID(), trader.getX(), trader.getY(), trader.getZ(),
                        settlement.center.getX() + 0.5D, settlement.center.getZ() + 0.5D,
                        settlement.radius,
                        horizontalDistanceSquared > (double) settlement.radius * settlement.radius,
                        level.getGameTime());
                }
                // Same server thread, receipt only after genuine entity publication.
                receipts.commit(settlement.center, trader.getUUID(), now);
                LAST_ATTEMPT.put(settlement, "published trader=" + trader.getUUID() + " target=" + target.toShortString() + " tick=" + now);
                RETRIES.remove(settlement);
                installArrivalGoal(trader);
                trader.getNavigation().moveTo(path, 1.0);
                if (QaTrace.ENABLED) observeArrival(trader, true);
                for (var player : level.players()) if (player.isAlive()
                    && player.blockPosition().distSqr(settlement.center) <= 80 * 80)
                    player.displayClientMessage(Component.translatable(
                        "hearthstead.merchant.approaching"), false);
                return true;
            }
        }
        return failed(settlement, now, "no_safe_reachable_route", paths,
            unloaded, unsafeSpawn, unreachable, unsafePath);
    }

    /** Read-only, bounded live support status; no world scan or retry mutation. */
    public static String diagnosticStatus(Settlement settlement) {
        if (settlement == null) return "no_settlement";
        return LAST_ATTEMPT.getOrDefault(settlement, "no_eligible_attempt_since_load")
            + " retryAfter=" + RETRIES.getOrDefault(settlement, 0L);
    }

    // Called only inside the 1000-tick eligible-attempt gate: at most one
    // failure line per settlement per 50 seconds, never per mob/server tick.
    private static boolean failed(Settlement settlement, long now, String reason,
            int paths, int unloaded, int unsafeSpawn, int unreachable, int unsafePath) {
        String detail = "reason=" + reason + " paths=" + paths + " unloaded=" + unloaded
            + " unsafeSpawn=" + unsafeSpawn + " unreachable=" + unreachable
            + " unsafePath=" + unsafePath + " tick=" + now;
        LAST_ATTEMPT.put(settlement, detail);
        Hearthstead.LOGGER.info("HEARTHSTEAD_MERCHANT_ARRIVAL_FAILED settlement={} center={} {}",
            settlement.id, settlement.center.toShortString(), detail);
        return false;
    }

    private static BlockPos nearbyFeet(ServerLevel level, WanderingTrader trader, BlockPos column) {
        if (!level.hasChunkAt(column)) return null;
        // Prefer the Hearth floor, then the nearest viable step above/below it.
        // Unlike a heightmap-only target this also works beneath a Tavern roof.
        for (int offset = 0; offset <= 6; offset++) {
            BlockPos above = column.above(offset);
            if (safeFeet(level, trader, above)) return above;
            if (offset > 0) {
                BlockPos below = column.below(offset);
                if (safeFeet(level, trader, below)) return below;
            }
        }
        return null;
    }

    private static boolean safeFeet(ServerLevel level, WanderingTrader trader, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())
            || !level.getWorldBorder().isWithinBounds(feet)
            || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()
            || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) return false;
        double half = trader.getBbWidth() / 2.0;
        return level.noCollision(trader, new AABB(feet.getX()+.5-half, feet.getY(), feet.getZ()+.5-half,
            feet.getX()+.5+half, feet.getY()+trader.getBbHeight(), feet.getZ()+.5+half));
    }

    /**
     * Survival QA #5: a merchant published into a 1-wide notch of a terraced
     * hillside never arrived. A spawn column must be a flat, open 2x2 of
     * standable cells at one height, with headroom (no overhang), on no leaves.
     */
    static boolean openFooting(ServerLevel level, WanderingTrader trader, BlockPos feet) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                if (!safeFeet(level, trader, cell)
                    || !level.getBlockState(cell.above(2)).getCollisionShape(level, cell.above(2)).isEmpty()
                    || level.getBlockState(cell.below()).is(net.minecraft.tags.BlockTags.LEAVES)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Expiry is absolute saved game time, including time while its chunk is unloaded. */
    public static boolean availableForTrade(WanderingTrader trader) {
        CompoundTag tag = trader.getPersistentData();
        return !tag.hasUUID(OWNER) || tag.contains(EXPIRES, Tag.TAG_LONG)
            && tag.getLong(EXPIRES) > trader.level().getGameTime();
    }

    @SubscribeEvent
    public static void onMerchantTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof WanderingTrader trader)
            || !(trader.level() instanceof ServerLevel)) return;
        if (!availableForTrade(trader)) {
            // Ordinary menu removal returns unspent physical input to its player.
            if (trader.getTradingPlayer() != null) trader.getTradingPlayer().closeContainer();
            trader.discard();
            return;
        }
        if (trader.getPersistentData().hasUUID(OWNER)) {
            // New visitors remain pending until their first safe consumer can
            // choose local wood; old loaded visitors are stripped once safely.
            GoldCoinTrades.migrateLegacyOwnedMerchant((ServerLevel) trader.level(), trader);
            installArrivalGoal(trader);
            if (QaTrace.ENABLED) observeArrival(trader, false);
        }
    }

    /** QA-only observation after entity AI, including when another goal owns MOVE. */
    private static void observeArrival(WanderingTrader trader, boolean publication) {
        if (!QaTrace.ENABLED || trader.getPersistentData().getBoolean(ARRIVED)) return;
        long now = trader.level().getGameTime();
        if (!publication && now % 200 != 0) return;
        boolean hasTarget = trader.getPersistentData().contains(ARRIVAL_TARGET, Tag.TAG_LONG);
        BlockPos target = hasTarget ? BlockPos.of(trader.getPersistentData().getLong(ARRIVAL_TARGET)) : null;
        MerchantArrivalGoal arrival = null;
        StringBuilder running = new StringBuilder();
        for (var wrapped : trader.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MerchantArrivalGoal found) arrival = found;
            if (wrapped.isRunning()) {
                if (running.length() > 0) running.append(';');
                running.append(wrapped.getPriority()).append(':')
                    .append(wrapped.getGoal().getClass().getSimpleName())
                    .append(':').append(wrapped.getGoal().getFlags());
            }
        }
        var navigation = trader.getNavigation();
        var path = navigation.getPath();
        int next = path == null ? -1 : path.getNextNodeIndex();
        int count = path == null ? 0 : path.getNodeCount();
        Hearthstead.LOGGER.info(
            "HSQA_MERCHANT_ARRIVAL_STATE phase={} trader={} tick={} pos={} target={} distanceSq={} alive={} onGround={} water={} horizontalCollision={} navDone={} pathReach={} pathTarget={} pathNext={} pathCount={} nextNode={} running={} arrivalGoal={} nextPath={} lastPathDecision={}",
            publication ? "published" : "sample", trader.getUUID(), now, trader.position(),
            target == null ? "none" : target.toShortString(),
            target == null ? -1.0 : trader.distanceToSqr(target.getX() + .5, target.getY(), target.getZ() + .5),
            trader.isAlive(), trader.onGround(), trader.isInWater(), trader.horizontalCollision, navigation.isDone(),
            path != null && path.canReach(), path == null ? "none" : path.getTarget().toShortString(), next, count,
            path != null && next >= 0 && next < count ? path.getNodePos(next).toShortString() : "none",
            running.length() == 0 ? "none" : running.toString(), arrival != null,
            arrival == null ? -1L : arrival.nextPath,
            arrival == null ? "no_arrival_goal" : arrival.lastPathDecision);
    }

    private static void installArrivalGoal(WanderingTrader trader) {
        if (ARRIVAL_GOALS.putIfAbsent(trader, Boolean.TRUE) != null) return;
        CompoundTag tag = trader.getPersistentData();
        if (tag.getBoolean(ARRIVED)) return;
        if (!tag.contains(ARRIVAL_TARGET, Tag.TAG_LONG)) {
            // Resume already-published visitors from before the arrival fix.
            int[] legacyTarget = trader.saveWithoutId(new CompoundTag()).getIntArray("wander_target");
            BlockPos target = legacyTarget.length == 3 ? new BlockPos(legacyTarget[0], legacyTarget[1], legacyTarget[2]) : null;
            if (target == null && trader.level() instanceof ServerLevel level) {
                var saved = SettlementSavedData.existing(level);
                var settlement = saved == null ? null : saved.settlements.get(tag.getUUID(OWNER));
                if (settlement != null) {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        target = nearbyFeet(level, trader, settlement.center.relative(direction, 2));
                        if (target != null) break;
                    }
                }
            }
            if (target == null) { ARRIVAL_GOALS.remove(trader); return; }
            tag.putLong(ARRIVAL_TARGET, target.asLong());
        }
        // Vanilla WanderToPositionGoal replaces an ended route with a straight
        // ten-block hint, losing our safe terrain route. Replace only that
        // arrival goal on our visitors; panic, trading and flotation still win.
        for (var wrapped : java.util.List.copyOf(trader.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal().getClass().getSimpleName().equals("WanderToPositionGoal"))
                trader.goalSelector.removeGoal(wrapped.getGoal());
        }
        trader.goalSelector.addGoal(2, new MerchantArrivalGoal(trader));
    }

    /** About 10 s without a block of progress counts as stuck (survival QA #5). */
    static final long STUCK_TICKS = 200L;

    private static final class MerchantArrivalGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final WanderingTrader trader;
        private long nextPath;
        private int nextAlternateColumn;
        /** Stuck recovery (QA #5): progress sample, and how many 10 s stalls in a row. */
        private BlockPos progressPos;
        private long progressTick;
        private int stalls;
        /** Diagnostic only; never read by navigation or goal selection. */
        private String lastPathDecision = "initial_or_reloaded";
        MerchantArrivalGoal(WanderingTrader trader) {
            this.trader = trader;
            setFlags(java.util.EnumSet.of(Flag.MOVE));
            trader.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE).setBaseValue(112.0);
            // Navigation is constructed before saved attributes are restored.
            // Both fresh and reloaded vanilla traders start with a 256-node budget.
            trader.getNavigation().setMaxVisitedNodesMultiplier(7.0F);
        }
        @Override public boolean canUse() {
            return trader.isAlive() && !trader.getPersistentData().getBoolean(ARRIVED)
                && trader.getPersistentData().contains(ARRIVAL_TARGET, Tag.TAG_LONG);
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void stop() { trader.getNavigation().stop(); }
        @Override public void tick() {
            ServerLevel level = (ServerLevel)trader.level();
            BlockPos target = BlockPos.of(trader.getPersistentData().getLong(ARRIVAL_TARGET));
            if (trader.distanceToSqr(target.getX() + .5, target.getY(), target.getZ() + .5) <= 6.25
                && Math.abs(trader.getY() - target.getY()) <= 1.5 && !trader.isInWater()) {
                trader.getPersistentData().putBoolean(ARRIVED, true);
                trader.setWanderTarget(null);
                trader.getNavigation().stop();
                Hearthstead.LOGGER.info("HEARTHSTEAD_MERCHANT_ARRIVED trader={} target={} tick={}",
                    trader.getUUID(), target.toShortString(), level.getGameTime());
                return;
            }
            if (stuckRecovery(level, target)) return;
            if (!trader.getNavigation().isDone() || level.getGameTime() < nextPath) return;
            nextPath = level.getGameTime() + 40;
            var path = trader.getNavigation().createPath(target, 0, 112);
            if (!safeArrivalPath(level, path)) {
                tryAlternateArrival(level, target);
                return;
            }
            trader.getNavigation().moveTo(path, 1.0);
            if (QaTrace.ENABLED) lastPathDecision = "submitted_reachable_path";
        }

        /**
         * No progress for {@link #STUCK_TICKS} (about 10 s) while walking to the
         * Banner: drop the stuck path and try another approach; after three
         * stalls in a row, step the merchant onto open footing by the Banner.
         * Returns true when this tick was spent recovering.
         */
        private boolean stuckRecovery(ServerLevel level, BlockPos target) {
            long now = level.getGameTime();
            if (progressPos == null || trader.getTradingPlayer() != null
                || trader.blockPosition().distSqr(progressPos) >= 2) {
                // Moving, or standing still on purpose (a player is trading): not stuck.
                progressPos = trader.blockPosition();
                progressTick = now;
                stalls = 0;
                return false;
            }
            if (now - progressTick < STUCK_TICKS) return false;
            progressTick = now;
            stalls++;
            trader.getNavigation().stop();
            nextPath = 0;
            Hearthstead.LOGGER.info("HEARTHSTEAD_MERCHANT_STUCK trader={} pos={} target={} stalls={}",
                trader.getUUID(), trader.blockPosition().toShortString(), target.toShortString(), stalls);
            if (stalls >= 3 && relocateNearBanner(level)) {
                stalls = 0;
                return true;
            }
            tryAlternateArrival(level, target);
            return true;
        }

        private boolean relocateNearBanner(ServerLevel level) {
            CompoundTag tag = trader.getPersistentData();
            if (!tag.hasUUID(OWNER)) return false;
            var saved = SettlementSavedData.existing(level);
            Settlement settlement = saved == null ? null : saved.settlements.get(tag.getUUID(OWNER));
            if (settlement == null || !level.hasChunkAt(settlement.center)) return false;
            for (int distance = 2; distance <= 6; distance++) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    BlockPos spot = nearbyFeet(level, trader, settlement.center.relative(facing, distance));
                    if (spot == null || !openFooting(level, trader, spot)) continue;
                    trader.moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, trader.getYRot(), 0F);
                    tag.putLong(ARRIVAL_TARGET, spot.asLong());
                    trader.setWanderTarget(spot);
                    trader.restrictTo(spot, 16);
                    Hearthstead.LOGGER.info("HEARTHSTEAD_MERCHANT_RELOCATED trader={} to={}",
                        trader.getUUID(), spot.toShortString());
                    return true;
                }
            }
            return false;
        }

        private boolean safeArrivalPath(ServerLevel level, net.minecraft.world.level.pathfinder.Path path) {
            if (path == null || !path.canReach()) {
                if (QaTrace.ENABLED) lastPathDecision = path == null ? "rejected_null" : "rejected_unreachable";
                return false;
            }
            boolean stillEscapingWater = trader.isInWater();
            for (int i = 0; i < path.getNodeCount(); i++) {
                BlockPos node = path.getNodePos(i);
                if (safeFeet(level, trader, node)) { stillEscapingWater = false; continue; }
                // Preserve the existing escape rule; no fresh water segment after dry ground.
                if (!(stillEscapingWater && level.hasChunkAt(node)
                    && !level.getFluidState(node).isEmpty())) {
                    if (QaTrace.ENABLED) lastPathDecision = "rejected_unsafe_node:" + i + ':' + node.toShortString();
                    return false;
                }
            }
            return true;
        }

        /** One extra path query per existing40tick gate, anchored to the same real Hearth. */
        private void tryAlternateArrival(ServerLevel level, BlockPos rejectedTarget) {
            CompoundTag tag = trader.getPersistentData();
            if (!tag.hasUUID(OWNER) || !availableForTrade(trader)) return;
            var saved = SettlementSavedData.existing(level);
            Settlement settlement = saved == null ? null : saved.settlements.get(tag.getUUID(OWNER));
            if (settlement == null || !level.hasChunkAt(settlement.center)
                || !level.getBlockState(settlement.center).is(ModBlocks.HEARTH.get())
                || !(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
                || !settlement.id.equals(hearth.getSettlementId())) return;

            // Twenty columns: four directions at radius2..6, matching publication's
            // local destination envelope. Advance across failures instead of always
            // retrying the first safe standing cell. This scan never loads chunks.
            for (int scanned = 0; scanned < 20; scanned++) {
                int column = nextAlternateColumn;
                nextAlternateColumn = (nextAlternateColumn + 1) % 20;
                BlockPos alternative = nearbyFeet(level, trader, settlement.center.relative(
                    Direction.from2DDataValue(column % 4), 2 + column / 4));
                if (alternative == null || alternative.equals(rejectedTarget)) continue;

                var path = trader.getNavigation().createPath(alternative, 0, 112);
                if (!safeArrivalPath(level, path)) return;
                if (!path.getTarget().equals(alternative)) {
                    if (QaTrace.ENABLED) lastPathDecision = "alternate_rejected_target_mismatch";
                    return;
                }
                if (!trader.getNavigation().moveTo(path, 1.0)) {
                    if (QaTrace.ENABLED) lastPathDecision = "alternate_submission_refused";
                    return;
                }
                // Commit destination identity only after a complete safe route was
                // accepted. Publication identity, market, receipt and expiry stay intact.
                tag.putLong(ARRIVAL_TARGET, alternative.asLong());
                trader.setWanderTarget(alternative);
                trader.restrictTo(alternative, 16);
                if (QaTrace.ENABLED) lastPathDecision = "submitted_safe_alternate:" + alternative.toShortString();
                return;
            }
        }
    }

    /** Clean-save cadence; does not claim crash-atomic cross-chunk persistence. */
    public static final class Receipts extends SavedData {
        private static final int CAP = 4096;
        private static final Factory<Receipts> FACTORY = new Factory<>(Receipts::new, Receipts::load, null);
        private record Visit(UUID merchant, long expires, long next) {}
        private final Map<Long, Visit> visits = new HashMap<>();
        private boolean quarantined;
        public boolean available(BlockPos site, long now) {
            Visit last = visits.get(site.asLong());
            return !quarantined && now >= 0 && (last != null ? now >= last.next : visits.size() < CAP);
        }
        public void commit(BlockPos site, UUID merchant, long now) {
            if (!available(site, now) || now > Long.MAX_VALUE - PERIOD_TICKS
                || merchant == null || merchant.equals(new UUID(0,0))) return;
            visits.put(site.asLong(), new Visit(merchant, now + VISIT_TICKS, now + PERIOD_TICKS)); setDirty();
        }
        public static Receipts load(CompoundTag tag, HolderLookup.Provider registries) {
            Receipts value = new Receipts();
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                || !tag.contains("Quarantined", Tag.TAG_BYTE)
                || (tag.getByte("Quarantined") != 0 && tag.getByte("Quarantined") != 1)
                || !(tag.get("Visits") instanceof ListTag rows)
                || rows.size() > CAP || !rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND) {
                value.quarantined = true; return value;
            }
            value.quarantined = tag.getBoolean("Quarantined");
            Set<UUID> identities = new HashSet<>();
            boolean migratedLegacyCadence = false;
            for (int i=0;i<rows.size();i++) {
                CompoundTag row = rows.getCompound(i);
                long expires = row.getLong("Expires"), next = row.getLong("Next");
                boolean currentCadence = validCadence(expires, next, PERIOD_TICKS - VISIT_TICKS, PERIOD_TICKS);
                boolean previousCadence = !value.quarantined
                    && validCadence(expires, next, PREVIOUS_GAP_TICKS, PREVIOUS_PERIOD_TICKS);
                boolean knownLegacyCadence = !value.quarantined && !previousCadence
                    && validCadence(expires, next, LEGACY_GAP_TICKS, LEGACY_PERIOD_TICKS);
                long migratedNext = (previousCadence || knownLegacyCadence)
                    ? Math.max(next, expires + (PERIOD_TICKS - VISIT_TICKS)) : next;
                if (!row.contains("Site",Tag.TAG_LONG) || !row.hasUUID("Merchant")
                    || row.getUUID("Merchant").equals(new UUID(0,0))
                    || !identities.add(row.getUUID("Merchant"))
                    || !row.contains("Expires",Tag.TAG_LONG) || !row.contains("Next",Tag.TAG_LONG)
                    || !currentCadence && !previousCadence && !knownLegacyCadence
                    || value.visits.putIfAbsent(row.getLong("Site"), new Visit(row.getUUID("Merchant"),
                        expires, migratedNext)) != null) {
                    value.quarantined = true; break;
                }
                migratedLegacyCadence |= previousCadence || knownLegacyCadence;
            }
            // A recognized old visit stays the same visit and site; it simply cannot publish early under
            // the new continuous cadence. Unknown intervals continue to quarantine the complete receipt file.
            if (migratedLegacyCadence && !value.quarantined) value.setDirty();
            return value;
        }
        private static boolean validCadence(long expires, long next, long gap, long minimumNext) {
            return expires >= VISIT_TICKS && next >= minimumNext && expires <= Long.MAX_VALUE - gap
                && next == expires + gap;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("Version",1); tag.putBoolean("Quarantined",quarantined);
            ListTag rows = new ListTag();
            visits.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row -> {
                CompoundTag entry = new CompoundTag(); entry.putLong("Site",row.getKey());
                entry.putUUID("Merchant",row.getValue().merchant);
                entry.putLong("Expires",row.getValue().expires); entry.putLong("Next",row.getValue().next); rows.add(entry);
            });
            tag.put("Visits",rows); return tag;
        }
    }
}

package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;

/** Real service stock -> physical tabletop -> seated guest -> reusable glass return. */
public class InnkeeperWorkGoal extends Goal {
    private final SettlerEntity settler;
    private Building tavern;
    private int cooldown;
    private int searchTicks;
    private int approachTicks;
    private BlockPos source;
    private SettlerEntity guest;
    private BlockPos refillSource;
    private int refillTicks;
    private final java.util.Map<BlockPos, Long> failedSources = new java.util.HashMap<>();
    private long retrySourcesAfter;
    // Tavern lane: idle bar tending (presentation only; no stock, cargo or reservation).
    private static final int BAR_IDLE_TICKS = 400;
    private int barIdleTicks;
    private InnkeeperBarStation.Station barStation;
    private long nextBarSearch;
    public InnkeeperWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    @Override public boolean canUse() {
        if (cooldown > 0) { cooldown--; return false; }
        var settlement = settler.settlement();
        tavern = settlement == null ? null : Employment.employerOf(settlement, settler.getUUID());
        return tavern != null && (TavernHostService.authorizedHost(settler, tavern.id)
            || TavernHostService.canReturnCargo(settler, TavernHostService.current(settler)))
            && (TavernHostService.hasSession(settler)
                || tavern.anchor != null && settler.blockPosition().closerThan(
                    com.hearthstead.settlement.Schedule.tavernFloorPost(settler, tavern),
                    com.hearthstead.settlement.Schedule.AT_POST)
                || tavern.bounds != null && tavern.bounds.isInside(settler.blockPosition()));
    }
    @Override public boolean canContinueToUse() {
        return tavern != null && (TavernHostService.authorizedHost(settler, tavern.id)
            || TavernHostService.canReturnCargo(settler, TavernHostService.current(settler)));
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        source = null; guest = null; searchTicks = 0; approachTicks = 0; refillSource = null; refillTicks = 0;
        failedSources.clear(); retrySourcesAfter = 0; barIdleTicks = 0;
        settler.setActivity(SettlerActivity.IDLE);
    }
    @Override public void stop() {
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        settler.innkeeperAtmosphere().stop(settler); // Root-owned atmosphere seam.
        // Owned cargo stays in its saved serving entity; a stop never deletes it.
        source = null; guest = null; tavern = null; cooldown = 20; refillSource = null; refillTicks = 0;
    }
    @Override public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) return;
        var serving = TavernHostService.current(settler);
        boolean transaction = TavernHostService.hasSession(settler);
        boolean handsBusy = transaction || refillSource != null || !settler.getNavigation().isDone()
            || !settler.getMainHandItem().isEmpty() || !settler.getOffhandItem().isEmpty();
        if (settler.innkeeperAtmosphere().tick(settler, tavern, handsBusy)) {
            settler.getNavigation().stop(); return;
        }
        if (transaction) {
            barIdleTicks = 0;
            // An unloaded serving entity is not proof that its saved cargo vanished.
            if (serving != null) {
                settler.setActivity(serving.phase() == com.hearthstead.entity.TavernServingEntity.Phase.CARRYING
                    || serving.phase() == com.hearthstead.entity.TavernServingEntity.Phase.RETURNING
                    ? SettlerActivity.CARRYING : SettlerActivity.IDLE);
                serving.serviceTick(settler);
            } else {
                TavernHostService.reacquire(settler);
            }
            return;
        }
        if (!settler.getMainHandItem().isEmpty() || !settler.getOffhandItem().isEmpty()) return;
        // New visits are served only through the quoted restaurant transaction.
        // Explicit legacy pickup remains available for old saved/test sessions.
        if (guest == null || source == null) {
            tickBarStation(level);
            if (level.getGameTime() < retrySourcesAfter) return;
            if (++searchTicks < 20) return;
            searchTicks = 0;
            // A repaired store re-enters selection even while another store stays stocked.
            failedSources.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
            guest = null;
            source = null;
            // Restaurant orders already set the host session and are handled
            // above by their durable serving owner. guest() scans past
            // unbound/traveler actors and returns only a bound resident's
            // retained legacy saved/test session.
            guest = TavernHostService.guest(settler, tavern);
            source = guest == null ? null : TavernHostService.source(settler, tavern,
                failedSources.keySet());
            if (guest != null && source == null && !failedSources.isEmpty()) {
                // A whole bounded pass failed. Retry repaired stores after ten seconds.
                failedSources.clear(); retrySourcesAfter = level.getGameTime() + 200;
                settler.getNavigation().stop(); settler.setActivity(SettlerActivity.IDLE);
                return;
            }
            approachTicks = 0;
            if (guest == null || source == null) tickRefill(level);
            return;
        }
        if (!TavernHostService.validGuest(settler, guest,
                com.hearthstead.settlement.TavernSeating.currentSite(guest))) {
            settler.getNavigation().stop(); source = null; guest = null; return;
        }
        if (++approachTicks > 360) {
            failedSources.put(source, level.getGameTime() + 1200);
            settler.recordRouteFailure("tavern_stock_unreachable:" + source.toShortString());
            settler.getNavigation().stop(); settler.setActivity(SettlerActivity.IDLE);
            source = null; guest = null; return;
        }
        if (ContainerApproach.inspect(level, settler, source).canInteract()) {
            TavernHostService.pickup(settler, guest, tavern, source);
            source = null; guest = null;
        } else if (approachTicks % 40 == 1) {
            ContainerApproach.moveToContact(level, settler, source, 1.0);
        }
    }
    /**
     * Tavern lane: after twenty idle seconds with nothing to serve or refill, stand at the bar -
     * in front of an ale tap mounted one block up (client ALE_POUR), else facing a counter
     * (COUNTER_WIPE) - and face it. Any service, refill or greeting takes over at once.
     */
    private void tickBarStation(ServerLevel level) {
        if (refillSource != null || guest != null || settler.innkeeperSocialMode() != 0) { barIdleTicks = 0; return; }
        if (++barIdleTicks < BAR_IDLE_TICKS) return;
        long now = level.getGameTime();
        if (now >= nextBarSearch) {
            barStation = InnkeeperBarStation.find(level, settler, tavern);
            nextBarSearch = now + 1200;
        }
        if (barStation == null) return;
        BlockPos stand = barStation.stand();
        if (settler.blockPosition().equals(stand)) {
            if (!settler.getNavigation().isDone()) settler.getNavigation().stop();
            float yaw = barStation.face().toYRot();
            settler.setYRot(net.minecraft.util.Mth.approachDegrees(settler.getYRot(), yaw, 15));
            settler.setYBodyRot(net.minecraft.util.Mth.approachDegrees(settler.yBodyRot, yaw, 15));
            settler.setYHeadRot(settler.yBodyRot);
            settler.setActivity(SettlerActivity.IDLE);
        } else if (settler.getNavigation().isDone() && barIdleTicks % 40 == 0) {
            settler.getNavigation().moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5, 0.8);
        }
    }
    /** Independent finite reserve job; active table service always takes priority. */
    private void tickRefill(ServerLevel level) {
        if (refillSource == null) {
            refillSource = com.hearthstead.settlement.work.TavernAleService.findRefillSource(settler, tavern);
            refillTicks = 0;
        }
        if (refillSource == null) return;
        if ((refillTicks += 20) > 360
            || !com.hearthstead.settlement.work.TavernAleService.canRefillSource(settler, tavern, refillSource)) {
            refillSource = null; return;
        }
        if (ContainerApproach.inspect(level, settler, refillSource).canInteract()) {
            if (level.getBlockEntity(refillSource) instanceof net.minecraft.world.Container container)
                com.hearthstead.settlement.work.TavernAleService.brewAtContact(settler, tavern, refillSource, container);
            refillSource = null;
        } else {
            settler.setActivity(SettlerActivity.TRAVELING);
            ContainerApproach.moveToContact(level, settler, refillSource, 1.0);
        }
    }
}

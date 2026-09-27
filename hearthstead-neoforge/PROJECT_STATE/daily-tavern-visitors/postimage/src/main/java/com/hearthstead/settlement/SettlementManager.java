package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidProfile;
import com.hearthstead.util.AuthorityTelemetry;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.raid.FirstRaidReadiness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

/** All server-side settlement operations. Everything goes through here. */
public final class SettlementManager {
    /** GameTests found settlements in cramped test structures; the spacing
     *  rule would make every test after the first fail. Never true in play. */
    public static boolean ignoreFoundingDistance = false;

    /**
     * How long a guest waits at the tavern (or the hearth, tavern-less)
     * before giving up on a settlement that cannot pay. 2.5 game days: long
     * enough that a settlement mid-harvest gets a real second chance, short
     * enough that an unpayable settlement is not haunted by the same guest
     * forever. Doubled while an innkeeper is on shift (see
     * {@link #tickWaitingTraveler}) — hospitality buys a guest more time to
     * wait.
     *
     * <p>That is a DIFFERENT hook from the innkeeper's price discount: what
     * joining costs (DESIGN.md system 8: "recruit by paying a price in
     * village-grown goods"), and the named discounts a settlement can earn
     * against it (an innkeeper on shift, a dining hall), live in
     * {@link Costs#recruit()} and {@link Costs#discountsFor} — this class
     * only ever asks, through {@link #recruitPrice} and
     * {@link #recruitDiscounts} below, so {@link #tickWaitingTraveler} and
     * the UI stay on the same one number, exactly what COSTS.md's
     * implementation map requires. One hook lets a slow settlement wait
     * longer, the other lowers what it pays once it can — the two stack
     * independently, and neither substitutes for the other.
     */
    private static final long GUEST_PATIENCE_TICKS = 60_000L;
    /** The ordinary visitor batch is published during the same evening each game day. */
    public static final long TAVERN_VISITOR_ARRIVAL_TIME = 11_000L;
    /** A waiting visitor returns home during night, after a full physical visit window. */
    public static final long TAVERN_VISITOR_DEPARTURE_TIME = 15_000L;

    public static SettlementSavedData data(ServerLevel level) {
        return SettlementSavedData.get(level);
    }

    @Nullable
    public static Settlement byId(ServerLevel level, UUID id) {
        return id == null ? null : data(level).settlements.get(id);
    }

    /** The settlement whose radius contains {@code pos}, if any. */
    @Nullable
    public static Settlement at(ServerLevel level, BlockPos pos) {
        for (Settlement s : data(level).settlements.values()) {
            if (s.inside(pos)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Found a new settlement centered on a placed hearth. Returns null when
     * another settlement is too close. Idempotent per position: a hearth that
     * already sits inside its own settlement re-binds instead of re-founding.
     */
    @Nullable
    public static Settlement tryFound(ServerLevel level, BlockPos hearthPos) {
        return tryFoundWithSpawner(level, hearthPos,
            (spawnLevel, settlement) -> spawnSettler(spawnLevel, settlement,
                false));
    }

    public static final int FOUNDER_COUNT = 4;

    /** Three hireable founders and one dedicated Mayor commit together. */
    @Nullable
    static Settlement tryFoundWithSpawner(ServerLevel level, BlockPos hearthPos,
                                          FounderSpawner spawner) {
        SettlementSavedData data = data(level);
        for (Settlement other : data.settlements.values()) {
            if (other.center.equals(hearthPos)) {
                return other;
            }
        }
        // Existing identity wins independently of map iteration order. Distance
        // protects only a genuinely new founding, never an exact rebind.
        for (Settlement other : data.settlements.values()) {
            double minDist = other.radius + Settlement.DEFAULT_RADIUS;
            if (!ignoreFoundingDistance
                && other.center.distSqr(hearthPos) < minDist * minDist) {
                return null;
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(),
            SettlerNames.pickSettlementName(level.random), hearthPos);
        // New player-founded settlements use B02's deliberate first-raid
        // calendar. Constructor and load fallbacks remain PEACEFUL so saved
        // settlements retain their persisted identity.
        s.raidProfile = RaidProfile.BALANCED;
        long foundedNight = Math.max(0L,
            Math.floorDiv(level.getDayTime(), RaidLifecycle.DAY_LENGTH));
        if (!s.raidLifecycle.prepareAtFounding(foundedNight, level.random,
            s.raidProfile)) {
            // A fresh instance has no competing lifecycle state, so failure
            // here would mean the founding record is unsafe to persist.
            return null;
        }
        List<SettlerEntity> founders = new ArrayList<>(FOUNDER_COUNT);
        for (int i = 0; i < FOUNDER_COUNT; i++) {
            SettlerEntity founder = spawner.spawn(level, s);
            if (founder == null) {
                // Founding is one transaction, not "a settlement plus however
                // many founders happened to spawn". Remove every entity and
                // record already committed in this same tick, then remove the
                // settlement before any celebration or active journey exists.
                for (SettlerEntity earlier : founders) {
                    s.removeRecord(earlier.getUUID());
                    earlier.discard();
                }
                s.settlers.clear();
                data.setDirty();
                AuthorityTelemetry.emit(level,
                    AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                    AuthorityTelemetry.Result.REJECTED,
                    AuthorityTelemetry.Fields.state(null, "founding", 0, 0,
                        data.settlements.size(), data.settlements.size(),
                        "founder_spawn_rollback"));
                return null;
            }
            founders.add(founder);
        }
        // Publish only the complete aggregate. EntityJoinLevelEvent can run
        // synchronously inside addFreshEntity, so registering the settlement
        // before every founder existed exposed a partially-founded village to
        // other event handlers even though a later failure was rolled back.
        s.foundingJourney = FoundingJourney.fresh();
        s.journeyState = JourneyState.fresh(s.id);
        s.firstRaidReadiness = FirstRaidReadiness.fresh();
        data.settlements.put(s.id, s);
        if (!JourneyServerHooks.noteSettlementFounded(level, s)
            || Mayor.appoint(level, s, founders.get(FOUNDER_COUNT - 1)) != null) {
            data.settlements.remove(s.id);
            for (SettlerEntity founder : founders) {
                s.removeRecord(founder.getUUID());
                founder.discard();
            }
            s.settlers.clear();
            data.setDirty();
            return null;
        }
        data.setDirty();

        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.FOUNDING_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(s.id, "settlement:" + s.id,
                0, s.foundingJourney.revision(), 0, s.population(),
                "three_workers_and_mayor_atomic"));

        level.playSound(null, hearthPos, ModSounds.SETTLEMENT_FOUNDED.get(),
            SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            hearthPos.getX() + 0.5, hearthPos.getY() + 1.2, hearthPos.getZ() + 0.5,
            24, 1.2, 0.8, 1.2, 0.02);
        broadcast(level, s, Component.translatable("hearthstead.message.founded", s.name));
        return s;
    }

    @FunctionalInterface
    interface FounderSpawner {
        @Nullable SettlerEntity spawn(ServerLevel level, Settlement settlement);
    }

    /** Called when a hearth block is broken. The settlement dissolves. */
    public static void disbandAt(ServerLevel level, BlockPos hearthPos) {
        SettlementSavedData data = data(level);
        Settlement found = null;
        for (Settlement s : data.settlements.values()) {
            if (s.center.equals(hearthPos)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            return;
        }
        for (SettlerEntity settler : loadedMembers(level, found)) {
            settler.unbind();
        }
        data.settlements.remove(found.id);
        data.setDirty();
        broadcast(level, found, Component.translatable("hearthstead.message.disbanded", found.name));
    }

    @Nullable
    public static SettlerEntity spawnSettler(ServerLevel level, Settlement s, boolean traveler) {
        RecruitmentTransaction beforeRecruitment = s.recruitment;
        if (traveler && (beforeRecruitment == null
            || beforeRecruitment.revisionSaturated()
            || beforeRecruitment.status()
                != RecruitmentTransaction.Status.READY_TO_SPAWN
            || resolveLockedTavern(level, s, beforeRecruitment).state()
                != LockedTavernState.VALID)) {
            return null;
        }
        if (traveler && !TravelerArrivalRoutes.beginAttempt(s, level.getGameTime())) {
            return null;
        }
        SettlerEntity settler = ModEntities.SETTLER.get().create(level);
        if (settler == null) {
            return null;
        }
        BlockPos spawnPos = traveler
            ? TravelerArrivalRoutes.findOrigin(level, s, settler,
                resolveTravelerApproach(level, beforeRecruitment.tavernAnchor()))
            : findGround(level, s.center.offset(level.random.nextInt(7) - 3, 0,
                level.random.nextInt(7) - 3), s.center);
        if (spawnPos == null) {
            return null; // unpublished probe; no guest, notice or fallback at the Hearth
        }
        Set<String> taken = new HashSet<>();
        for (Settlement.SettlerRecord r : s.settlers) {
            taken.add(r.name);
        }
        String name = SettlerNames.pickSettlerName(level.random, taken);
        settler.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
            level.random.nextFloat() * 360.0F, 0.0F);
        settler.setSettlerName(name);
        settler.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
            MobSpawnType.MOB_SUMMONED, null);
        RecruitmentQuote candidateQuote = traveler
            ? RecruitmentQuote.fromStartingAttributes(beforeRecruitment.transactionId(),
                settler.getUUID(), settler.attributes(), recruitDiscounts(level, s)) : null;
        // Appearance seed is already rolled in the SettlerEntity constructor
        // for every creation path, not just this one.
        if (traveler) {
            settler.markTraveler(s.id, s.center);
            com.hearthstead.settlement.work.TavernGuestPayment.initializeTraveler(settler);
        } else {
            settler.bindTo(s.id, s.center);
            s.putRecord(settler.getUUID(), name, Profession.NONE);
        }
        if (!level.addFreshEntity(settler)) {
            // The target clock must only renew after an entity entered the
            // world. Roll back the bookkeeping performed above if the level
            // refused the spawn (another mod/event may cancel it).
            if (!traveler) {
                s.removeRecord(settler.getUUID());
            }
            data(level).setDirty();
            return null;
        }
        if (traveler) {
            RecruitmentTransaction spawned = beforeRecruitment.travelerSpawned(
                settler.getUUID(), settler.getSettlerName(), level.getGameTime(), candidateQuote);
            if (spawned == beforeRecruitment
                || spawned.status() != RecruitmentTransaction.Status.TRAVELING) {
                // addFreshEntity succeeded, but the persisted transaction did
                // not. Do not leave an untracked guest in the world.
                settler.discard();
                return null;
            }
            s.applyRecruitment(spawned);
            // The same committed spawn owns the physical origin used to leave
            // at night. Admin prime remains an explicit immediate-only tool.
            if (beforeRecruitment.survivalAuthored()) {
                s.lastTavernVisitorDay = currentTavernVisitorDay(level);
                s.tavernVisitorDepartureOrigin = spawnPos.immutable();
            }
            TravelerArrivalRoutes.completeAttempt(s);
        }
        data(level).setDirty();
        return settler;
    }

    /** One-second cadence, driven by the hearth block entity. */
    public static void tickRecruitment(ServerLevel level, Settlement s) {
        recruitPrice(level, s); // Freeze a legacy guest before any state transition.
        List<SettlerEntity> members = loadedMembers(level, s);
        if (!members.isEmpty()) {
            int total = 0;
            for (SettlerEntity m : members) {
                total += (int) m.getMorale();
            }
            s.moraleCache = total / members.size();
        }

        RecruitmentTransaction transaction = s.recruitment;
        if (transaction == null) {
            s.applyRecruitment(RecruitmentTransaction.quarantined(s.id, 0, 0,
                null, RecruitmentTransaction.TerminalReason.MALFORMED_SAVE));
            data(level).setDirty();
            return;
        }

        // Journey is derived evidence. A failed write never rolls gameplay
        // back; the persisted transaction id makes each retry idempotent.
        reconcileRecruitmentEvidence(level, s);
        // Reconciliation may have replaced the immutable transaction with a
        // value carrying a newly acknowledged evidence bit. Continue from
        // that persisted value so a later transition in this same tick
        // cannot accidentally erase the acknowledgement.
        transaction = s.recruitment;
        if (transaction.revisionSaturated()) {
            return; // fail closed: no gameplay mutation may reuse MAX_VALUE
        }

        switch (transaction.status()) {
            case ATTRACTING -> tickAttraction(level, s, transaction);
            case QUALIFYING -> tickQualification(level, s, transaction);
            case READY_TO_SPAWN -> {
                // Admin fast-forward intentionally remains immediate. Natural,
                // persisted recruitment is the one daily visitor batch.
                if (transaction.survivalAuthored() && !tavernVisitorArrivalDue(level, s)) {
                    return;
                }
                SettlerEntity spawned = spawnSettler(level, s, true);
                if (spawned != null) {
                    broadcast(level, s,
                        Component.translatable("hearthstead.message.traveler_spotted"));
                }
            }
            case TRAVELING -> tickTravelingTraveler(level, s, transaction);
            case WAITING_ADMISSION -> tickWaitingTraveler(level, s, transaction);
            case DEPARTING -> tickDepartingTraveler(level, s, transaction);
            case ADMITTED -> {
                // Do not erase the persisted proof until FJ460 has observed it.
                if (s.recruitment.evidenceAcknowledged(
                    RecruitmentTransaction.EVIDENCE_ADMISSION)) {
                    s.applyRecruitment(s.recruitment.nextCycle(s.id));
                    data(level).setDirty();
                }
            }
            case LEFT -> {
                s.applyRecruitment(transaction.nextCycle(s.id));
                s.tavernVisitorDepartureOrigin = null;
                data(level).setDirty();
            }
            case QUARANTINED, UNKNOWN -> {
                // Malformed, ambiguous, duplicate and legacy-unverifiable
                // states remain inert for an operator/player-visible repair.
            }
        }
    }

    private static void tickAttraction(ServerLevel level, Settlement s,
                                       RecruitmentTransaction transaction) {
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level,
            s, transaction.survivalAuthored()
                ? RecruitmentPolicy.Stage.TAVERN_VISIT : RecruitmentPolicy.Stage.ATTRACTION);
        Building tavern = firstLiveTavern(level, s);
        if (!assessment.eligible() || tavern == null) {
            return;
        }
        RecruitmentTransaction qualifying = transaction.beginQualification(
            UUID.randomUUID(), level.getGameTime(), tavern.id, tavern.plaquePos,
            tavern.anchor, level.dimension().location(),
            JourneyServerHooks.useCallToArmsTiming(level, s));
        if (qualifying == transaction) {
            return;
        }
        s.applyRecruitment(qualifying);
        data(level).setDirty();
        reconcileRecruitmentEvidence(level, s);
    }

    private static void tickQualification(ServerLevel level, Settlement s,
                                          RecruitmentTransaction transaction) {
        LockedTavern locked = resolveLockedTavern(level, s, transaction);
        if (locked.state() == LockedTavernState.UNLOADED) {
            return; // never force-load or count an unobserved second
        }
        if (locked.state() != LockedTavernState.VALID) {
            return; // preserve the exact locked trip; invalid time never rerolls it
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level,
            s, transaction.survivalAuthored()
                ? RecruitmentPolicy.Stage.TAVERN_VISIT : RecruitmentPolicy.Stage.QUALIFYING);
        RecruitmentTransaction next = assessment.eligible()
            ? transaction.advanceQualification()
            : transaction;
        if (!next.equals(transaction)) {
            s.applyRecruitment(next);
            data(level).setDirty();
        }
    }

    private static void tickTravelingTraveler(ServerLevel level, Settlement s,
                                              RecruitmentTransaction transaction) {
        Entity entity = level.getEntity(transaction.travelerId());
        if (entity == null) {
            return; // unloaded is indistinguishable from absent; never terminalize it
        }
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive()
            || !guest.isTraveler()
            || !s.id.equals(guest.getTargetSettlementId())) {
            s.applyRecruitment(transaction.quarantine(
                RecruitmentTransaction.TerminalReason.CROSS_SETTLEMENT));
            data(level).setDirty();
            return;
        }
        if (beginTavernVisitorNightDeparture(level, s, transaction, guest)) {
            return;
        }
        LockedTavern locked = resolveLockedTavern(level, s, transaction);
        if (locked.state() == LockedTavernState.UNLOADED) {
            return; // never force-load or infer loss from an unobserved Tavern
        }
        if (locked.state() == LockedTavernState.INVALID) {
            // Both the traveler and Tavern chunks were observed. The exact
            // destination no longer exists, so finish this physical visit
            // deterministically; never retarget it to the Hearth or a new Tavern.
            String name = guest.getSettlerName();
            RecruitmentTransaction left = transaction.left(
                RecruitmentTransaction.TerminalReason.TAVERN_INVALIDATED);
            if (left != transaction) {
                s.applyRecruitment(left);
                data(level).setDirty();
                guest.discard();
                broadcast(level, s, Component.translatable(
                    "hearthstead.message.traveler_left", name));
            }
            return;
        }
        // The persisted anchor remains the exact Tavern identity, but a
        // no-bed Tavern may truthfully anchor at its wall-hung Plaque. A
        // mob cannot occupy that thin block; TravelerJoinGoal therefore
        // walks to the derived, standable doorstep. Arrival must use the
        // same physical position after resolveLockedTavern has already
        // proved the immutable plaque/building/anchor transaction intact.
        // Keep the pre-existing exact-anchor arrival contract for lawful
        // anchors that a physical traveler can already occupy (and for
        // persisted journeys from before wall-Plaque approach resolution).
        // A wall-hung Plaque additionally admits the real, standable
        // doorstep that TravelerJoinGoal was instructed to reach.  Both
        // alternatives remain bound to the same LockedTavern validation
        // above; neither can retarget the transaction.
        if (!atTravelerDestination(level, guest, transaction)) {
            return;
        }
        RecruitmentTransaction arrived = transaction.arrived(level.getGameTime());
        if (arrived == transaction) {
            return;
        }
        s.applyRecruitment(arrived);
        data(level).setDirty();
        // Emit only on this committed transition, never while reconciling a
        // saved WAITING_ADMISSION transaction or on its subsequent ticks.
        broadcast(level, s, Component.translatable(
            "hearthstead.message.traveler_waiting"));
        TravelerArrivalRoutes.ringArrivalBell(level, locked.building());
        reconcileRecruitmentEvidence(level, s);
    }

    /** Read-only daily gate; the persisted marker changes only after a real guest is published. */
    public static boolean tavernVisitorArrivalDue(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) {
            return false;
        }
        long day = currentTavernVisitorDay(level);
        long local = Math.floorMod(level.getDayTime(), 24_000L);
        return local >= TAVERN_VISITOR_ARRIVAL_TIME
            && local < TAVERN_VISITOR_DEPARTURE_TIME
            && settlement.lastTavernVisitorDay != day;
    }

    /** Read-only cutoff for the batch that was actually published this day. */
    public static boolean tavernVisitorNightDepartureDue(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null || settlement.lastTavernVisitorDay < 0L) {
            return false;
        }
        long day = currentTavernVisitorDay(level);
        return day > settlement.lastTavernVisitorDay
            || (day == settlement.lastTavernVisitorDay
                && Math.floorMod(level.getDayTime(), 24_000L) >= TAVERN_VISITOR_DEPARTURE_TIME);
    }

    private static long currentTavernVisitorDay(ServerLevel level) {
        return Math.floorDiv(level.getDayTime(), 24_000L);
    }

    /** Starts the exact saved outward trip after a collision-safe seat release. */
    private static boolean beginTavernVisitorNightDeparture(ServerLevel level,
            Settlement settlement, RecruitmentTransaction transaction, SettlerEntity guest) {
        if (!tavernVisitorNightDepartureDue(level, settlement)
            || settlement.tavernVisitorDepartureOrigin == null) {
            return false;
        }
        // The paid meal transaction remains owned by the visitor until its
        // existing seat goal has consumed it. Never discard paid food at the
        // night cutoff merely to make a timer appear punctual.
        if (guest.hasMeal()) {
            return false;
        }
        if (guest.isPassenger() && !guest.leaveSeat()) {
            return true;
        }
        RecruitmentTransaction departing = transaction.departing();
        if (departing == transaction) {
            return false;
        }
        settlement.applyRecruitment(departing);
        data(level).setDirty();
        return true;
    }

    /** Arrival and admission share one physical radius, after locked identity validation. */
    private static boolean atTravelerDestination(ServerLevel level,
            SettlerEntity guest, RecruitmentTransaction transaction) {
        var seated = TavernSeating.currentSite(guest);
        if (seated != null && transaction.status() == RecruitmentTransaction.Status.WAITING_ADMISSION
                && waitingTravelerTavern(level, guest) != null
                && transaction.tavernBuildingId().equals(seated.tavernId())
                && TavernSeating.valid(level, guest, seated)) return true;
        if (guest.blockPosition().distSqr(transaction.tavernAnchor()) <= 9.0D) {
            return true;
        }
        BlockPos approach = travelerTavernApproach(level, guest);
        return approach != null && guest.blockPosition().distSqr(approach) <= 9.0D;
    }

    /** Waiting never pays or converts. Only an explicit Hearth action may admit. */
    private static void tickWaitingTraveler(ServerLevel level, Settlement s,
                                            RecruitmentTransaction transaction) {
        Entity entity = level.getEntity(transaction.travelerId());
        if (entity == null) {
            return; // chunk unload pauses physical leave; arrival tick is preserved
        }
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive()
            || !guest.isTraveler()
            || !s.id.equals(guest.getTargetSettlementId())) {
            s.applyRecruitment(transaction.quarantine(
                RecruitmentTransaction.TerminalReason.CROSS_SETTLEMENT));
            data(level).setDirty();
            return;
        }
        if (beginTavernVisitorNightDeparture(level, s, transaction, guest)) {
            return;
        }
        LockedTavern locked = resolveLockedTavern(level, s, transaction);
        boolean innkeeper = locked.state() == LockedTavernState.VALID
            && locked.building() != null && !locked.building().workers.isEmpty();
        long patience = innkeeper ? GUEST_PATIENCE_TICKS * 2 : GUEST_PATIENCE_TICKS;
        long waited = Math.max(0L, level.getGameTime() - transaction.arrivedTick());
        if (waited <= patience) {
            return;
        }
        String name = guest.getSettlerName();
        guest.discard();
        s.applyRecruitment(transaction.left(
            RecruitmentTransaction.TerminalReason.PATIENCE_EXPIRED));
        data(level).setDirty();
        broadcast(level, s,
            Component.translatable("hearthstead.message.traveler_left", name));
    }

    /** The departure goal owns movement; this terminal only observes a real outside return. */
    private static void tickDepartingTraveler(ServerLevel level, Settlement settlement,
                                              RecruitmentTransaction transaction) {
        Entity entity = level.getEntity(transaction.travelerId());
        if (entity == null) {
            return; // unloaded travelers and exterior chunks pause, never despawn remotely
        }
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive() || !guest.isTraveler()
            || !settlement.id.equals(guest.getTargetSettlementId())) {
            settlement.applyRecruitment(transaction.quarantine(
                RecruitmentTransaction.TerminalReason.CROSS_SETTLEMENT));
            data(level).setDirty();
            return;
        }
        BlockPos origin = settlement.tavernVisitorDepartureOrigin;
        if (origin == null) {
            settlement.applyRecruitment(transaction.quarantine(
                RecruitmentTransaction.TerminalReason.MALFORMED_SAVE));
            data(level).setDirty();
            return;
        }
        if (!level.hasChunkAt(origin)) {
            return;
        }
        if (guest.blockPosition().distSqr(origin) > 9.0D || !outsideSettlement(settlement,
                guest.blockPosition())) {
            return;
        }
        guest.discard();
        settlement.applyRecruitment(transaction.left(
            RecruitmentTransaction.TerminalReason.VISIT_COMPLETE));
        data(level).setDirty();
    }

    private static boolean outsideSettlement(Settlement settlement, BlockPos feet) {
        double x = feet.getX() - settlement.center.getX();
        double z = feet.getZ() - settlement.center.getZ();
        return x * x + z * z > (double) settlement.radius * settlement.radius;
    }

    /** The first valid TAVERN building in this settlement, or null. */
    @Nullable
    private static Building firstValidTavern(Settlement s) {
        for (Building b : s.buildings) {
            if (b.valid && b.type == BuildingType.TAVERN && b.anchor != null) {
                return b;
            }
        }
        return null;
    }

    @Nullable
    private static Building firstLiveTavern(ServerLevel level, Settlement s) {
        return s.buildings.stream()
            .filter(building -> liveTavern(level, building))
            .min(Comparator.comparing(building -> building.id.toString()))
            .orElse(null);
    }

    private static boolean liveTavern(ServerLevel level, Building building) {
        if (building == null || !building.valid
            || building.type != BuildingType.TAVERN
            || building.plaquePos == null || building.anchor == null
            || !level.getWorldBorder().isWithinBounds(building.plaquePos)
            || !level.getWorldBorder().isWithinBounds(building.anchor)
            || !level.isLoaded(building.plaquePos)
            || !level.isLoaded(building.anchor)
            || !(level.getBlockEntity(building.plaquePos)
                instanceof PlaqueBlockEntity plaque)
            || plaque.isRemoved()) {
            return false;
        }
        return building.id.equals(plaque.buildingId());
    }

    private enum LockedTavernState {
        VALID,
        UNLOADED,
        INVALID
    }

    private record LockedTavern(LockedTavernState state,
                                @Nullable Building building) {
    }

    private static LockedTavern resolveLockedTavern(ServerLevel level,
                                                    Settlement settlement,
                                                    RecruitmentTransaction transaction) {
        if (transaction == null || !transaction.hasLockedTavern()
            || !level.dimension().location().equals(transaction.dimension())) {
            return new LockedTavern(LockedTavernState.INVALID, null);
        }
        if (!level.getWorldBorder().isWithinBounds(transaction.tavernPlaquePos())
            || !level.getWorldBorder().isWithinBounds(transaction.tavernAnchor())) {
            return new LockedTavern(LockedTavernState.INVALID, null);
        }
        if (!level.isLoaded(transaction.tavernPlaquePos())
            || !level.isLoaded(transaction.tavernAnchor())) {
            return new LockedTavern(LockedTavernState.UNLOADED, null);
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (!transaction.tavernBuildingId().equals(building.id)) {
                continue;
            }
            if (found != null) {
                return new LockedTavern(LockedTavernState.INVALID, null);
            }
            found = building;
        }
        if (found == null || !found.valid || found.type != BuildingType.TAVERN
            || !transaction.tavernPlaquePos().equals(found.plaquePos)
            || !transaction.tavernAnchor().equals(found.anchor)
            || !(level.getBlockEntity(found.plaquePos)
                instanceof PlaqueBlockEntity plaque)
            || plaque.isRemoved()
            || !found.id.equals(plaque.buildingId())) {
            return new LockedTavern(LockedTavernState.INVALID, found);
        }
        return new LockedTavern(LockedTavernState.VALID, found);
    }

    /** Read-only visit authority for the one arrived, still-unowned candidate. */
    @Nullable
    public static Building waitingTravelerTavern(ServerLevel level, SettlerEntity guest) {
        if (level == null || guest == null || !guest.isAlive() || !guest.isTraveler()
                || guest.level() != level || guest.getSettlementId() != null) return null;
        Settlement settlement = byId(level, guest.getTargetSettlementId());
        RecruitmentTransaction transaction = settlement == null ? null : settlement.recruitment;
        if (transaction == null || transaction.status() != RecruitmentTransaction.Status.WAITING_ADMISSION
                || !guest.getUUID().equals(transaction.travelerId())
                || settlement.record(guest.getUUID()) != null
                || transaction.quote() == null
                || !transaction.quote().matches(transaction.transactionId(), guest.getUUID())) return null;
        LockedTavern locked = resolveLockedTavern(level, settlement, transaction);
        return locked.state() == LockedTavernState.VALID ? locked.building() : null;
    }

    /** Exact Tavern destination for the exact persisted traveler; never Hearth fallback. */
    @Nullable
    public static BlockPos travelerTavernAnchor(ServerLevel level,
                                                SettlerEntity traveler) {
        if (level == null || traveler == null || !traveler.isTraveler()) {
            return null;
        }
        Settlement settlement = byId(level, traveler.getTargetSettlementId());
        if (settlement == null || settlement.recruitment == null
            || !traveler.getUUID().equals(settlement.recruitment.travelerId())) {
            return null;
        }
        RecruitmentTransaction.Status status = settlement.recruitment.status();
        if (status != RecruitmentTransaction.Status.TRAVELING
            && status != RecruitmentTransaction.Status.WAITING_ADMISSION) {
            return null;
        }
        LockedTavern locked = resolveLockedTavern(level, settlement,
            settlement.recruitment);
        return locked.state() == LockedTavernState.VALID
            ? settlement.recruitment.tavernAnchor() : null;
    }

    /**
     * A walkable feet position for the exact Tavern locked by a travel
     * transaction. The transaction continues to own the immutable anchor used
     * for arrival and admission; this method merely resolves the physical
     * doorstep from that already-verified identity.
     *
     * <p>Most workplace anchors are ordinary interior feet cells. A Tavern
     * without beds correctly falls back to its wall-hung Plaque, however, and
     * a plaque block is not a place a mob may stand. In that case the front
     * stoop is the one valid destination. It is derived from the plaque's
     * actual facing rather than from an assumed building orientation, so it
     * cannot redirect a traveler to another Tavern or the Hearth.
     */
    @Nullable
    public static BlockPos travelerTavernApproach(ServerLevel level,
                                                  SettlerEntity traveler) {
        return resolveTravelerApproach(level, travelerTavernAnchor(level, traveler));
    }

    /** The registered traveler goal reuses its ordinary bounded legs for both directions. */
    @Nullable
    public static BlockPos travelerRouteDestination(ServerLevel level, SettlerEntity traveler) {
        if (level == null || traveler == null || !traveler.isTraveler()) {
            return null;
        }
        Settlement settlement = byId(level, traveler.getTargetSettlementId());
        RecruitmentTransaction transaction = settlement == null ? null : settlement.recruitment;
        if (transaction == null || !traveler.getUUID().equals(transaction.travelerId())) {
            return null;
        }
        if (transaction.status() == RecruitmentTransaction.Status.TRAVELING) {
            return travelerTavernApproach(level, traveler);
        }
        return transaction.status() == RecruitmentTransaction.Status.DEPARTING
            && settlement.tavernVisitorDepartureOrigin != null
            && level.hasChunkAt(settlement.tavernVisitorDepartureOrigin)
                ? settlement.tavernVisitorDepartureOrigin : null;
    }

    @Nullable
    private static BlockPos resolveTravelerApproach(ServerLevel level,
                                                     @Nullable BlockPos anchor) {
        if (anchor == null || !level.isLoaded(anchor)) {
            return null;
        }
        BlockState anchorState = level.getBlockState(anchor);
        if (anchorState.getBlock() instanceof PlaqueBlock
            && anchorState.hasProperty(PlaqueBlock.FACING)) {
            Direction front = anchorState.getValue(PlaqueBlock.FACING);
            BlockPos stoopFeet = anchor.relative(front).below();
            return standableTravelerFeet(level, stoopFeet) ? stoopFeet : null;
        }
        // Bed-backed and ordinary surveyed anchors are normally feet cells.
        // A legacy survey can instead point at a furnishing one block above
        // its floor, so accept only an adjacent, physically standable cell.
        for (BlockPos candidate : List.of(anchor, anchor.below(),
                anchor.north(), anchor.south(), anchor.east(), anchor.west(),
                anchor.north().below(), anchor.south().below(),
                anchor.east().below(), anchor.west().below())) {
            if (standableTravelerFeet(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean standableTravelerFeet(ServerLevel level,
                                                  BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())
            || !level.isLoaded(feet.above())) {
            return false;
        }
        BlockState floor = level.getBlockState(feet.below());
        return floor.isFaceSturdy(level, feet.below(), Direction.UP)
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above())
                .getCollisionShape(level, feet.above()).isEmpty();
    }

    /** Server-authored blocker projection for the Hearth candidate card. */
    public static RecruitmentPolicy.Blocker candidateBlocker(ServerLevel level,
                                                             Settlement settlement) {
        return candidateBlocker(level, settlement, null);
    }

    public static RecruitmentPolicy.Blocker candidateBlocker(ServerLevel level, Settlement settlement, ServerPlayer payer) {
        if (level == null || settlement == null || settlement.recruitment == null
            || !settlement.recruitment.hasCandidate()) {
            return RecruitmentPolicy.Blocker.INVALID_STATE;
        }
        RecruitmentTransaction transaction = settlement.recruitment;
        if (transaction.revisionSaturated()) {
            return RecruitmentPolicy.Blocker.INVALID_STATE;
        }
        if (resolveLockedTavern(level, settlement, transaction).state()
            != LockedTavernState.VALID) {
            return RecruitmentPolicy.Blocker.NO_TAVERN;
        }
        Entity entity = level.getEntity(transaction.travelerId());
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive()
            || !guest.isTraveler()
            || !settlement.id.equals(guest.getTargetSettlementId())) {
            return RecruitmentPolicy.Blocker.INVALID_STATE;
        }
        RecruitmentPolicy.Stage stage = transaction.status()
            == RecruitmentTransaction.Status.WAITING_ADMISSION
                ? RecruitmentPolicy.Stage.WAITING_ADMISSION
                : RecruitmentPolicy.Stage.TRAVELING;
        return RecruitmentPolicy.assess(level, settlement, stage, payer).blocker();
    }

    public static boolean candidateMayAdmit(ServerLevel level,
                                            Settlement settlement) {
        return candidateMayAdmit(level, settlement, null);
    }

    public static boolean candidateMayAdmit(ServerLevel level, Settlement settlement, ServerPlayer payer) {
        RecruitmentTransaction transaction = settlement == null
            ? null : settlement.recruitment;
        if (transaction == null
            || transaction.status() != RecruitmentTransaction.Status.WAITING_ADMISSION
            || candidateBlocker(level, settlement, payer) != RecruitmentPolicy.Blocker.NONE) {
            return false;
        }
        Entity entity = level.getEntity(transaction.travelerId());
        return entity instanceof SettlerEntity guest && guest.isAlive()
            && atTravelerDestination(level, guest, transaction);
    }

    public static boolean candidateMayDismiss(ServerLevel level,
                                              Settlement settlement) {
        RecruitmentTransaction transaction = settlement == null
            ? null : settlement.recruitment;
        if (transaction == null || transaction.revisionSaturated()
            || transaction.status()
                != RecruitmentTransaction.Status.WAITING_ADMISSION) {
            return false;
        }
        Entity entity = level.getEntity(transaction.travelerId());
        return entity instanceof SettlerEntity guest && guest.isAlive()
            && guest.isTraveler()
            && settlement.id.equals(guest.getTargetSettlementId());
    }

    public static long candidatePatienceUntil(ServerLevel level,
                                              Settlement settlement) {
        RecruitmentTransaction transaction = settlement == null
            ? null : settlement.recruitment;
        if (transaction == null
            || transaction.status() != RecruitmentTransaction.Status.WAITING_ADMISSION) {
            return 0L;
        }
        LockedTavern locked = resolveLockedTavern(level, settlement, transaction);
        boolean innkeeper = locked.state() == LockedTavernState.VALID
            && locked.building() != null && !locked.building().workers.isEmpty();
        long patience = innkeeper ? GUEST_PATIENCE_TICKS * 2 : GUEST_PATIENCE_TICKS;
        return transaction.arrivedTick() > Long.MAX_VALUE - patience
            ? Long.MAX_VALUE : transaction.arrivedTick() + patience;
    }

    public enum AdmissionResult {
        COMMITTED,
        STALE_REVISION,
        NOT_WAITING,
        WRONG_TRAVELER,
        TRAVELER_UNLOADED,
        INVALID_TRAVELER,
        INVALID_TAVERN,
        BLOCKED_POLICY,
        REVISION_SATURATED,
        INTERNAL_ROLLBACK
    }

    /**
     * The only natural-recruit admission transaction. The caller's packet
     * supplies only exact open-menu identity, traveler UUID and revision;
     * every policy, item and world identity is resolved here on the server.
     */
    public static AdmissionResult admitWaitingTraveler(ServerPlayer player,
                                                        Settlement settlement,
                                                        UUID travelerId,
                                                        int expectedRevision) {
        if (player == null || settlement == null || travelerId == null) {
            return AdmissionResult.NOT_WAITING;
        }
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) {
            return AdmissionResult.INTERNAL_ROLLBACK;
        }
        recruitPrice(level, settlement); // One-time legacy quote before retaining the transaction.
        RecruitmentTransaction before = settlement.recruitment;
        if (before == null) {
            return AdmissionResult.NOT_WAITING;
        }
        if (before.revision() != expectedRevision) {
            return AdmissionResult.STALE_REVISION;
        }
        if (before.revisionSaturated()) {
            return AdmissionResult.REVISION_SATURATED;
        }
        if (before.status() != RecruitmentTransaction.Status.WAITING_ADMISSION) {
            return AdmissionResult.NOT_WAITING;
        }
        if (!travelerId.equals(before.travelerId())) {
            return AdmissionResult.WRONG_TRAVELER;
        }
        LockedTavern locked = resolveLockedTavern(level, settlement, before);
        if (locked.state() != LockedTavernState.VALID) {
            return AdmissionResult.INVALID_TAVERN;
        }
        Entity entity = level.getEntity(travelerId);
        if (entity == null) {
            return AdmissionResult.TRAVELER_UNLOADED;
        }
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive()
            || !guest.isTraveler()
            || !settlement.id.equals(guest.getTargetSettlementId())
            || !atTravelerDestination(level, guest, before)
            || settlement.record(travelerId) != null) {
            return AdmissionResult.INVALID_TRAVELER;
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level,
            settlement, RecruitmentPolicy.Stage.WAITING_ADMISSION, player);
        HearthBlockEntity hearth = RecruitmentPolicy.hearth(level, settlement);
        if (!assessment.eligible() || hearth == null) {
            return AdmissionResult.BLOCKED_POLICY;
        }

        var paymentInventory = CoinTreasury.forPrice(level, settlement, hearth, player, assessment.price());
        if (!Costs.canPay(paymentInventory, assessment.price())) return AdmissionResult.BLOCKED_POLICY;

        // A blocked physical exit cannot charge or admit the seated visitor.
        // Policy (including the live free-bed check) was assessed first.
        if (guest.isPassenger() && !TavernSeating.leaveSeat(guest)) {
            return AdmissionResult.BLOCKED_POLICY;
        }

        List<ItemStack> inventoryBefore = CoinTreasury.snapshot(paymentInventory);
        long hashBefore = inventoryHash(inventoryBefore);
        boolean recordAdded = false;
        try {
            Costs.pay(paymentInventory, assessment.price());
            PaymentDelta delta = paymentDelta(inventoryBefore,
                CoinTreasury.snapshot(paymentInventory), assessment.price());
            if (!delta.valid()) {
                throw new IllegalStateException("recruit payment delta was not conservative");
            }
            guest.bindTo(settlement.id, settlement.center);
            settlement.putRecord(guest.getUUID(), guest.getSettlerName(), Profession.NONE);
            recordAdded = true;
            long hashAfter = inventoryHash(CoinTreasury.snapshot(paymentInventory));
            RecruitmentTransaction.AdmissionReceipt receipt =
                new RecruitmentTransaction.AdmissionReceipt(player.getUUID(),
                    delta.fingerprint(), delta.removedItems(), hashBefore, hashAfter);
            RecruitmentTransaction committed = before.admitted(receipt);
            if (committed == before
                || committed.status() != RecruitmentTransaction.Status.ADMITTED) {
                throw new IllegalStateException("admission state refused commit");
            }
            settlement.applyRecruitment(committed);
            data(level).setDirty();
        } catch (RuntimeException failure) {
            CoinTreasury.restore(paymentInventory, inventoryBefore);
            if (recordAdded) {
                settlement.removeRecord(guest.getUUID());
            }
            guest.markTraveler(settlement.id, settlement.center);
            settlement.applyRecruitment(before);
            data(level).setDirty();
            return AdmissionResult.INTERNAL_ROLLBACK;
        }

        // The gameplay aggregate is already durable. Journey/telemetry is an
        // idempotent derived observation and must never undo payment/member.
        reconcileRecruitmentEvidence(level, settlement);
        int populationAfter = settlement.population();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.RECRUITMENT_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "settler:" + guest.getUUID(), before.revision(),
                settlement.recruitment.revision(), populationAfter - 1,
                populationAfter, "explicit_tavern_admission"));
        guest.celebrate();
        level.playSound(null, settlement.center, ModSounds.SETTLER_RECRUITED.get(),
            SoundSource.NEUTRAL, 1.0F, 1.0F);
        broadcast(level, settlement, Component.translatable(
            "hearthstead.message.recruited", guest.getSettlerName(), settlement.name));
        return AdmissionResult.COMMITTED;
    }

    public enum RejectionResult {
        COMMITTED,
        STALE_REVISION,
        NOT_WAITING,
        WRONG_TRAVELER,
        TRAVELER_UNLOADED,
        INVALID_TRAVELER,
        REVISION_SATURATED,
        INTERNAL_FAILURE
    }

    /**
     * Explicit no-payment dismissal of the exact waiting physical traveler.
     * The open Hearth session is validated by {@code HearthNetwork}; this
     * inner transaction repeats the persisted candidate/revision/entity
     * checks so replay or a second player cannot dismiss a newer guest.
     */
    public static RejectionResult rejectWaitingTraveler(ServerPlayer player,
                                                         Settlement settlement,
                                                         UUID travelerId,
                                                         int expectedRevision) {
        if (player == null || settlement == null || travelerId == null) {
            return RejectionResult.NOT_WAITING;
        }
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) {
            return RejectionResult.INTERNAL_FAILURE;
        }
        recruitPrice(level, settlement); // One-time legacy quote before retaining the transaction.
        RecruitmentTransaction before = settlement.recruitment;
        if (before == null) {
            return RejectionResult.NOT_WAITING;
        }
        if (before.revision() != expectedRevision) {
            return RejectionResult.STALE_REVISION;
        }
        if (before.revisionSaturated()) {
            return RejectionResult.REVISION_SATURATED;
        }
        if (before.status()
            != RecruitmentTransaction.Status.WAITING_ADMISSION) {
            return RejectionResult.NOT_WAITING;
        }
        if (!travelerId.equals(before.travelerId())) {
            return RejectionResult.WRONG_TRAVELER;
        }
        Entity entity = level.getEntity(travelerId);
        if (entity == null) {
            return RejectionResult.TRAVELER_UNLOADED;
        }
        if (!(entity instanceof SettlerEntity guest) || !guest.isAlive()
            || !guest.isTraveler()
            || !settlement.id.equals(guest.getTargetSettlementId())
            || settlement.record(travelerId) != null) {
            return RejectionResult.INVALID_TRAVELER;
        }
        RecruitmentTransaction rejected = before.left(
            RecruitmentTransaction.TerminalReason.PLAYER_REJECTED);
        if (rejected == before
            || rejected.status() != RecruitmentTransaction.Status.LEFT
            || rejected.revision() <= before.revision()) {
            return RejectionResult.INTERNAL_FAILURE;
        }
        settlement.applyRecruitment(rejected);
        data(level).setDirty();
        guest.discard();
        broadcast(level, settlement, Component.translatable(
            "hearthstead.message.traveler_dismissed", guest.getSettlerName()));
        return RejectionResult.COMMITTED;
    }

    private record PaymentDelta(boolean valid, int removedItems,
                                String fingerprint) {
    }

    private static List<ItemStack> snapshotInventory(HearthBlockEntity hearth) {
        List<ItemStack> snapshot = new ArrayList<>(hearth.getInventory().getSlots());
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            snapshot.add(hearth.getInventory().getStackInSlot(slot).copy());
        }
        return List.copyOf(snapshot);
    }

    private static void restoreInventory(HearthBlockEntity hearth,
                                         List<ItemStack> snapshot) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            hearth.getInventory().setStackInSlot(slot, snapshot.get(slot).copy());
        }
    }

    private static PaymentDelta paymentDelta(List<ItemStack> before,
                                             List<ItemStack> after,
                                             Costs.Price exactPrice) {
        if (before.size() != after.size()) {
            return new PaymentDelta(false, 0, "");
        }
        int[] remainingByLine = new int[exactPrice.lines().size()];
        for (int line = 0; line < exactPrice.lines().size(); line++) {
            remainingByLine[line] = exactPrice.lines().get(line).count();
        }
        int removed = 0;
        StringBuilder fingerprint = new StringBuilder();
        for (int slot = 0; slot < before.size(); slot++) {
            ItemStack oldStack = before.get(slot);
            ItemStack newStack = after.get(slot);
            if (oldStack.isEmpty() && newStack.isEmpty()) {
                continue;
            }
            if (oldStack.isEmpty() || (!newStack.isEmpty()
                && !ItemStack.isSameItemSameComponents(oldStack, newStack))
                || newStack.getCount() > oldStack.getCount()) {
                return new PaymentDelta(false, 0, "");
            }
            int delta = oldStack.getCount()
                - (newStack.isEmpty() ? 0 : newStack.getCount());
            if (delta <= 0) {
                continue;
            }
            int unmatched = delta;
            for (int line = 0; line < exactPrice.lines().size()
                    && unmatched > 0; line++) {
                if (remainingByLine[line] <= 0
                    || !exactPrice.lines().get(line).matches(oldStack)) {
                    continue;
                }
                int matched = Math.min(unmatched, remainingByLine[line]);
                remainingByLine[line] -= matched;
                unmatched -= matched;
            }
            if (unmatched != 0) {
                return new PaymentDelta(false, 0, "");
            }
            removed += delta;
            if (!fingerprint.isEmpty()) {
                fingerprint.append(';');
            }
            fingerprint.append(slot).append('@')
                .append(BuiltInRegistries.ITEM.getKey(oldStack.getItem()))
                .append('#').append(oldStack.getComponents().hashCode())
                .append('-').append(delta);
        }
        for (int remaining : remainingByLine) {
            if (remaining != 0) {
                return new PaymentDelta(false, 0, "");
            }
        }
        return new PaymentDelta(removed > 0 && fingerprint.length() <= 512,
            removed, fingerprint.toString());
    }

    private static long inventoryHash(List<ItemStack> stacks) {
        long hash = 0xcbf29ce484222325L;
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            String token = slot + ":" + (stack.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "#"
                    + stack.getComponents().hashCode() + "x" + stack.getCount());
            for (int i = 0; i < token.length(); i++) {
                hash ^= token.charAt(i);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static void reconcileRecruitmentEvidence(ServerLevel level,
                                                     Settlement settlement) {
        RecruitmentTransaction transaction = settlement.recruitment;
        if (transaction == null || transaction.transactionId() == null) {
            return;
        }
        if (!transaction.survivalAuthored()) {
            int acknowledged = 0;
            if (transaction.qualificationStartedTick() >= 0L) {
                acknowledged |= RecruitmentTransaction.EVIDENCE_QUALIFICATION;
            }
            if (transaction.arrivedTick() >= transaction.spawnedTick()
                && transaction.spawnedTick() >= 0L) {
                acknowledged |= RecruitmentTransaction.EVIDENCE_ARRIVAL;
            }
            if (transaction.status() == RecruitmentTransaction.Status.ADMITTED) {
                acknowledged |= RecruitmentTransaction.EVIDENCE_ADMISSION;
            }
            RecruitmentTransaction next = transaction.acknowledgeEvidence(acknowledged);
            if (next != transaction) {
                settlement.applyRecruitment(next);
                data(level).setDirty();
            }
            return;
        }
        if (transaction.qualificationStartedTick() >= 0L
            && !transaction.evidenceAcknowledged(
                RecruitmentTransaction.EVIDENCE_QUALIFICATION)
            && JourneyServerHooks.noteRecruitmentQualificationCommitted(level,
                settlement, transaction.transactionId())) {
            transaction = transaction.acknowledgeEvidence(
                RecruitmentTransaction.EVIDENCE_QUALIFICATION);
            settlement.applyRecruitment(transaction);
            data(level).setDirty();
        }
        if (transaction.spawnedTick() >= 0L
            && transaction.arrivedTick() >= transaction.spawnedTick()
            && !transaction.evidenceAcknowledged(
                RecruitmentTransaction.EVIDENCE_ARRIVAL)
            && JourneyServerHooks.noteTravelerArrivedAtTavern(level,
                settlement, transaction.transactionId())) {
            transaction = transaction.acknowledgeEvidence(
                RecruitmentTransaction.EVIDENCE_ARRIVAL);
            settlement.applyRecruitment(transaction);
            data(level).setDirty();
        }
        if (transaction.status() == RecruitmentTransaction.Status.ADMITTED
            && !transaction.evidenceAcknowledged(
                RecruitmentTransaction.EVIDENCE_ADMISSION)
            && JourneyServerHooks.noteTravelerAdmitted(level, settlement,
                transaction.transactionId())) {
            transaction = transaction.acknowledgeEvidence(
                RecruitmentTransaction.EVIDENCE_ADMISSION);
            settlement.applyRecruitment(transaction);
            data(level).setDirty();
        }
    }

    /**
     * Whether {@code s} has a valid tavern right now -- the exact same
     * building-level test {@link #tickRecruitment}'s attractive-check
     * reads (D-TAVERN-1), exposed for the hearth's synced
     * {@code HearthMenu.DATA_TAVERN} slot and {@code /hearthstead recruit}'s
     * feedback, so neither call site re-derives its own copy of the gate.
     */
    public static boolean hasValidTavern(Settlement s) {
        return firstValidTavern(s) != null;
    }

    /**
     * Admin fast-forward used by {@code /hearthstead recruit}. It advances
     * both clocks to one qualified second before completion, but refuses the
     * same attraction blockers normal runtime does. Admission remains a
     * separate, fully assessed and paid transaction after the guest arrives.
     */
    public static boolean primeRecruitment(ServerLevel level, Settlement s) {
        if (s.recruitment == null
            || s.recruitment.status() != RecruitmentTransaction.Status.ATTRACTING) {
            return false;
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(
            level, s, RecruitmentPolicy.Stage.ATTRACTION);
        Building tavern = firstLiveTavern(level, s);
        if (!assessment.eligible() || tavern == null) {
            return false;
        }
        RecruitmentTransaction primed = s.recruitment.adminPrime(UUID.randomUUID(),
            level.getGameTime(), tavern.id, tavern.plaquePos, tavern.anchor,
            level.dimension().location());
        if (primed == s.recruitment) {
            return false;
        }
        s.applyRecruitment(primed);
        data(level).setDirty();
        return true;
    }

    // ------------------------------------------------------- the price ---

    /**
     * Exact frozen effective quote for a real candidate; explicit undiscounted
     * base forecast before spawn. Old saved guests freeze their legacy base
     * discount once on the live server, before projection or admission.
     */
    public static Costs.Price recruitPrice(ServerLevel level, Settlement s) {
        RecruitmentTransaction transaction = s.recruitment;
        if (transaction != null && transaction.hasCandidate() && transaction.quote() != null) {
            RecruitmentTransaction frozen = transaction.quote().legacyPending()
                ? transaction.freezeLegacyQuote(recruitDiscounts(level, s)) : transaction;
            if (frozen != transaction) {
                s.applyRecruitment(frozen);
                data(level).setDirty();
            }
            return frozen.quote().price();
        }
        // Before a candidate exists, qualification forecasts only the explicit base.
        return Costs.recruit();
    }

    /**
     * The named discount lines making up {@link #recruitPrice}'s reduction —
     * e.g. an employed innkeeper, a valid dining hall — for the UI to render
     * per COSTS.md's "UI rule": full price, then discounted price with each
     * hook named ("Vertshusholderen -25%, Spisesalen -25%").
     */
    public static List<Costs.Discount> recruitDiscounts(ServerLevel level, Settlement s) {
        return Costs.discountsFor(level, s, Costs.PriceKey.RECRUIT);
    }

    public static void raiseAlert(ServerLevel level, Settlement s, BlockPos threatPos) {
        long now = level.getGameTime();
        boolean fresh = !s.alertActive(now);
        s.alertUntilGameTime = now + 400;
        s.alertPos = threatPos;
        data(level).setDirty();
        if (fresh) {
            level.playSound(null, s.center, ModSounds.GUARD_ALERT.get(),
                SoundSource.NEUTRAL, 1.2F, 1.0F);
            broadcast(level, s, Component.translatable("hearthstead.message.alert", s.name));
        }
    }

    public static void onSettlerDied(ServerLevel level, SettlerEntity settler) {
        if (settler.isTraveler()) {
            Settlement target = byId(level, settler.getTargetSettlementId());
            if (target != null && target.recruitment != null
                && settler.getUUID().equals(target.recruitment.travelerId())
                && (target.recruitment.status()
                    == RecruitmentTransaction.Status.TRAVELING
                    || target.recruitment.status()
                        == RecruitmentTransaction.Status.WAITING_ADMISSION
                    || target.recruitment.status()
                        == RecruitmentTransaction.Status.DEPARTING)) {
                target.applyRecruitment(target.recruitment.left(
                    RecruitmentTransaction.TerminalReason.ENTITY_GONE));
                data(level).setDirty();
            }
            return;
        }
        // A dead mayor is the settlement's problem, not just this
        // settler's: morale for everyone and three days of mourning.
        Settlement mayorSeat = byId(level, settler.getSettlementId());
        if (mayorSeat != null) {
            Mayor.onDeath(level, mayorSeat, settler);
        }
        Settlement s = byId(level, settler.getSettlementId());
        if (s == null) {
            return;
        }
        Employment.terminateMember(level, s, settler);
        s.removeRecord(settler.getUUID());
        for (SettlerEntity m : loadedMembers(level, s)) {
            m.addMorale(-15.0F);
        }
        broadcast(level, s, Component.translatable("hearthstead.message.settler_died",
            settler.getSettlerName()));
        data(level).setDirty();
    }

    public static void noteProfessionChange(ServerLevel level, SettlerEntity settler) {
        // A corpse can still receive a final projection refresh before vanilla
        // removes it. Its death has already removed the UUID from this roster;
        // a post-death projection must never recreate that record.
        if (!settler.isAlive() || settler.isRemoved()) {
            return;
        }
        Settlement s = byId(level, settler.getSettlementId());
        if (s == null) {
            return;
        }
        s.putRecord(settler.getUUID(), settler.getSettlerName(), settler.getProfession());
        data(level).setDirty();
    }

    public static List<SettlerEntity> loadedMembers(ServerLevel level, Settlement s) {
        List<SettlerEntity> out = new ArrayList<>();
        for (Settlement.SettlerRecord r : s.settlers) {
            if (level.getEntity(r.entityId) instanceof SettlerEntity settler && settler.isAlive()) {
                out.add(settler);
            }
        }
        return out;
    }

    private static void broadcast(ServerLevel level, Settlement s, Component msg) {
        double range = s.radius + 32;
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(s.center) <= range * range) {
                p.displayClientMessage(msg, false);
            }
        }
    }

    /** Finds a standable Y near the candidate, scanning a short column. */
    private static BlockPos findGround(ServerLevel level, BlockPos candidate, BlockPos fallback) {
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos feet = candidate.atY(candidate.getY() + dy);
            BlockPos below = feet.below();
            BlockState floor = level.getBlockState(below);
            if (floor.isSolidRender(level, below)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return feet;
            }
        }
        return fallback.above();
    }

    private SettlementManager() {
    }
}

package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.util.AuthorityTelemetry;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.raid.FirstRaidReadiness;
import net.minecraft.core.BlockPos;
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

    /** Package-private failure seam used only to prove three-founder atomicity. */
    @Nullable
    static Settlement tryFoundWithSpawner(ServerLevel level, BlockPos hearthPos,
                                          FounderSpawner spawner) {
        SettlementSavedData data = data(level);
        for (Settlement other : data.settlements.values()) {
            if (other.center.equals(hearthPos)) {
                return other;
            }
            double minDist = other.radius + Settlement.DEFAULT_RADIUS;
            if (!ignoreFoundingDistance
                && other.center.distSqr(hearthPos) < minDist * minDist) {
                return null;
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(),
            SettlerNames.pickSettlementName(level.random), hearthPos);
        long foundedNight = Math.max(0L,
            Math.floorDiv(level.getDayTime(), RaidLifecycle.DAY_LENGTH));
        if (!s.raidLifecycle.prepareAtFounding(foundedNight, level.random,
            s.raidProfile)) {
            // A fresh instance has no competing lifecycle state, so failure
            // here would mean the founding record is unsafe to persist.
            return null;
        }
        List<SettlerEntity> founders = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
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
        // before founder three existed exposed a partially-founded village to
        // other event handlers even though a later failure was rolled back.
        s.foundingJourney = FoundingJourney.fresh();
        s.journeyState = JourneyState.fresh(s.id);
        s.firstRaidReadiness = FirstRaidReadiness.fresh();
        data.settlements.put(s.id, s);
        if (!JourneyServerHooks.noteSettlementFounded(level, s)) {
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
                "three_founders_atomic"));

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
        BlockPos spawnPos = traveler
            ? findEdgeSpawn(level, s)
            : findGround(level, s.center.offset(level.random.nextInt(7) - 3, 0,
                level.random.nextInt(7) - 3), s.center);
        SettlerEntity settler = ModEntities.SETTLER.get().create(level);
        if (settler == null) {
            return null;
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
        // Appearance seed is already rolled in the SettlerEntity constructor
        // for every creation path, not just this one.
        if (traveler) {
            settler.markTraveler(s.id, s.center);
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
                settler.getUUID(), settler.getSettlerName(), level.getGameTime());
            if (spawned == beforeRecruitment
                || spawned.status() != RecruitmentTransaction.Status.TRAVELING) {
                // addFreshEntity succeeded, but the persisted transaction did
                // not. Do not leave an untracked guest in the world.
                settler.discard();
                return null;
            }
            s.applyRecruitment(spawned);
        }
        data(level).setDirty();
        return settler;
    }

    /** One-second cadence, driven by the hearth block entity. */
    public static void tickRecruitment(ServerLevel level, Settlement s) {
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
                SettlerEntity spawned = spawnSettler(level, s, true);
                if (spawned != null) {
                    broadcast(level, s,
                        Component.translatable("hearthstead.message.traveler_spotted"));
                }
            }
            case TRAVELING -> tickTravelingTraveler(level, s, transaction);
            case WAITING_ADMISSION -> tickWaitingTraveler(level, s, transaction);
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
            s, RecruitmentPolicy.Stage.ATTRACTION);
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
            s.applyRecruitment(transaction.restartAttraction(s.id));
            data(level).setDirty();
            return;
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level,
            s, RecruitmentPolicy.Stage.QUALIFYING);
        RecruitmentTransaction next = assessment.eligible()
            ? transaction.advanceQualification()
            : transaction.decayQualification();
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
        if (guest.blockPosition().distSqr(transaction.tavernAnchor()) > 9.0D) {
            return;
        }
        RecruitmentTransaction arrived = transaction.arrived(level.getGameTime());
        if (arrived == transaction) {
            return;
        }
        s.applyRecruitment(arrived);
        data(level).setDirty();
        reconcileRecruitmentEvidence(level, s);
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

    /** Server-authored blocker projection for the Hearth candidate card. */
    public static RecruitmentPolicy.Blocker candidateBlocker(ServerLevel level,
                                                             Settlement settlement) {
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
        return RecruitmentPolicy.assess(level, settlement, stage).blocker();
    }

    public static boolean candidateMayAdmit(ServerLevel level,
                                            Settlement settlement) {
        RecruitmentTransaction transaction = settlement == null
            ? null : settlement.recruitment;
        if (transaction == null
            || transaction.status() != RecruitmentTransaction.Status.WAITING_ADMISSION
            || candidateBlocker(level, settlement) != RecruitmentPolicy.Blocker.NONE) {
            return false;
        }
        Entity entity = level.getEntity(transaction.travelerId());
        return entity instanceof SettlerEntity guest && guest.isAlive()
            && guest.blockPosition().distSqr(transaction.tavernAnchor()) <= 9.0D;
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
            || guest.blockPosition().distSqr(before.tavernAnchor()) > 9.0D
            || settlement.record(travelerId) != null) {
            return AdmissionResult.INVALID_TRAVELER;
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level,
            settlement, RecruitmentPolicy.Stage.WAITING_ADMISSION);
        HearthBlockEntity hearth = RecruitmentPolicy.hearth(level, settlement);
        if (!assessment.eligible() || hearth == null
            || !Costs.canPay(hearth.getInventory(), assessment.price())) {
            return AdmissionResult.BLOCKED_POLICY;
        }

        List<ItemStack> inventoryBefore = snapshotInventory(hearth);
        long hashBefore = inventoryHash(inventoryBefore);
        boolean recordAdded = false;
        try {
            Costs.pay(hearth.getInventory(), assessment.price());
            PaymentDelta delta = paymentDelta(inventoryBefore,
                snapshotInventory(hearth), assessment.price());
            if (!delta.valid()) {
                throw new IllegalStateException("recruit payment delta was not conservative");
            }
            guest.bindTo(settlement.id, settlement.center);
            settlement.putRecord(guest.getUUID(), guest.getSettlerName(), Profession.NONE);
            recordAdded = true;
            long hashAfter = inventoryHash(snapshotInventory(hearth));
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
            restoreInventory(hearth, inventoryBefore);
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
     * What recruiting would cost this settlement RIGHT NOW, every discount
     * it has earned already applied — the one call both
     * {@link #tickWaitingTraveler} and the hire/recruit UI are meant to make,
     * so the two never drift onto two different numbers. See
     * {@link #recruitDiscounts} for the itemization behind this total.
     */
    public static Costs.Price recruitPrice(ServerLevel level, Settlement s) {
        return Costs.afterDiscounts(Costs.recruit(), recruitDiscounts(level, s));
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
                        == RecruitmentTransaction.Status.WAITING_ADMISSION)) {
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

    private static BlockPos findEdgeSpawn(ServerLevel level, Settlement s) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            int x = s.center.getX() + (int) (Math.cos(angle) * (s.radius - 4));
            int z = s.center.getZ() + (int) (Math.sin(angle) * (s.radius - 4));
            BlockPos edge = new BlockPos(x, s.center.getY(), z);
            if (level.isLoaded(edge)) {
                return findGround(level, edge, s.center);
            }
        }
        return findGround(level, s.center.offset(4, 0, 4), s.center);
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

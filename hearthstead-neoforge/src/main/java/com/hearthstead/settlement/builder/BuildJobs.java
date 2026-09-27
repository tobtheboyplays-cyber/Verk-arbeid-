package com.hearthstead.settlement.builder;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The site queue of one settlement: ordering, claims, completion, the raid
 * rush and the few player-facing notices. Server only.
 */
public final class BuildJobs {

    /** A Builder's lease on a site outlives his last heartbeat by this much. */
    public static final int LEASE_TICKS = 600;
    /** A blocked site is announced at most this often (3 in-game hours). */
    public static final long NOTICE_INTERVAL = 3_000L;

    private BuildJobs() {
    }

    // ------------------------------------------------------------ queue ---

    /** Commits a planned job to the queue. Returns a refusal key or null. */
    @Nullable
    public static String commit(ServerLevel level, Settlement settlement, BuildJob job) {
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        if (!data.add(job)) {
            return "hearthstead.builder.refuse.queue_full";
        }
        job.setStatus(hasBuilder(level, settlement) ? BuildStatus.QUEUED : BuildStatus.NO_BUILDER);
        return null;
    }

    public enum SiteAction {
        PAUSE, RESUME, UP, DOWN, RUSH, CANCEL_KEEP, DISMANTLE, ALLOW_OVERWRITE, REQUEST_NOW;

        public static SiteAction byOrdinal(int ordinal) {
            SiteAction[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PAUSE;
        }
    }

    /** Applies one Sites-tab action. Returns a feedback lang key. */
    public static String act(ServerLevel level, Settlement settlement, UUID jobId, SiteAction action,
                             @Nullable ServerPlayer player) {
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        BuildJob job = data.job(settlement.id, jobId);
        if (job == null) {
            return "hearthstead.builder.site.missing";
        }
        switch (action) {
            case PAUSE -> {
                job.paused = true;
                job.setStatus(BuildStatus.PAUSED);
            }
            case RESUME -> {
                job.paused = false;
                job.setStatus(BuildStatus.QUEUED);
            }
            case RUSH -> job.rush = !job.rush;
            case UP, DOWN -> move(data, settlement.id, job, action == SiteAction.UP ? -1 : 1);
            case ALLOW_OVERWRITE -> {
                job.allowOverwrite = true;
                job.clearBlocked();
                job.setStatus(BuildStatus.QUEUED);
            }
            case CANCEL_KEEP -> {
                queueScaffoldCleanup(level, data, job);
                if (job.state == BuildJob.State.ACTIVE) {
                    job.state = BuildJob.State.CANCELLED;
                    job.setStatus(BuildStatus.CANCELLED);
                    job.claimant = null;
                }
            }
            case DISMANTLE -> {
                queueScaffoldCleanup(level, data, job);
                if (job.state == BuildJob.State.ACTIVE) {
                    job.state = BuildJob.State.CANCELLED;
                    job.setStatus(BuildStatus.CANCELLED);
                    job.claimant = null;
                }
                BuildJob reverse = BuildPlanner.planDismantle(level, job,
                    player == null ? null : player.getUUID());
                if (reverse == null) {
                    data.changed();
                    return "hearthstead.builder.site.nothing_to_dismantle";
                }
                reverse.rush = true; // taking down is quick and wanted now
                if (!data.add(reverse)) {
                    return "hearthstead.builder.refuse.queue_full";
                }
                reverse.setStatus(BuildStatus.QUEUED);
            }
            case REQUEST_NOW -> {
                Building hut = anyHut(settlement);
                if (hut != null) {
                    BuilderSupply.request(level, settlement, hut, job, null);
                }
            }
        }
        data.changed();
        return "hearthstead.builder.site.ok";
    }

    /**
     * Codex T3b: a stopped or dismantled site must not strand its temporary
     * ladders. They become their own small, rushed, persisted take-down job
     * (each rung a DISMANTLE step refunding its ladder to the hut), so the
     * list survives cancellation and restarts until every rung is back.
     */
    /** Public entry for the goal's bounded take-down (see BuilderWorkGoal). */
    public static void queueScaffoldCleanupFor(ServerLevel level, BuildJob job) {
        queueScaffoldCleanup(level, BuildSiteSavedData.get(level), job);
    }

    static void queueScaffoldCleanup(ServerLevel level, BuildSiteSavedData data, BuildJob job) {
        if (job.scaffold.isEmpty()) {
            return;
        }
        // Codex T3b: EVERY recorded rung goes into the cleanup, loaded or not
        // -- an unloaded one is checked when the Builder reaches it (the
        // executor only acts on loaded cells, and a changed cell is left).
        BuildJob.Builder builder = new BuildJob.Builder();
        for (BlockPos rung : job.scaffold) {
            BlockState state = level.isLoaded(rung) && level.getBlockState(rung).is(net.minecraft.world.level.block.Blocks.LADDER)
                ? level.getBlockState(rung) : net.minecraft.world.level.block.Blocks.LADDER.defaultBlockState();
            builder.add(rung, state, BuildPhase.DISMANTLE, BuildJob.F_SCAFFOLD, null, null);
        }
        BuildJob cleanup = builder.build(UUID.randomUUID(), job.settlementId, BuildJob.Kind.DISMANTLE,
            job.sourceId, "Ladders: " + job.label, job.anchor, 0, false, job.owner, level.getGameTime());
        cleanup.rush = true;
        cleanup.setStatus(BuildStatus.QUEUED);
        // System work: admitted past the player queue cap. Ownership moves
        // only once the cleanup is accepted; otherwise the list stays.
        if (data.addSystem(cleanup)) {
            job.scaffold.clear();
            data.changed();
        }
    }

    /**
     * Codex T3b: a rung stops being the Builder's the moment a player breaks
     * it or builds into its cell -- whatever stands there later is theirs,
     * never taken down or refunded by a cleanup.
     */
    public static void forgetRung(ServerLevel level, BlockPos pos) {
        // W3b: every settlement's jobs, not "the settlement at pos" -- where
        // settlements overlap, the lookup can name the wrong one and the
        // player's ladder would stay recorded as the Builder's.
        BuildSiteSavedData data = BuildSiteSavedData.existing(level);
        if (data == null) {
            return;
        }
        boolean changed = false;
        for (BuildJob job : data.allJobs()) {
            if (job.scaffold.remove(pos)) {
                changed = true;
            }
            if (job.kind == BuildJob.Kind.DISMANTLE
                && (job.state == BuildJob.State.ACTIVE
                    || (job.state == BuildJob.State.COMPLETE && job.hasSkippedScaffold()))) {
                for (int i = 0; i < job.size(); i++) {
                    if (!job.isDone(i) && job.hasFlag(i, BuildJob.F_SCAFFOLD) && job.pos(i).equals(pos)) {
                        job.markDone(i, false);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            data.changed();
        }
    }

    private static void move(BuildSiteSavedData data, UUID settlementId, BuildJob job, int delta) {
        List<BuildJob> active = data.activeJobs(settlementId);
        int index = active.indexOf(job);
        int swap = index + delta;
        if (index < 0 || swap < 0 || swap >= active.size()) {
            return;
        }
        BuildJob other = active.get(swap);
        int order = job.order;
        job.order = other.order;
        other.order = order;
        if (job.order == other.order) {
            job.order += delta;
        }
    }

    // ------------------------------------------------------------ claims ---

    /**
     * The site this Builder should work: his own claimed one if still
     * workable, else the first workable, unclaimed site in queue order that
     * is not waiting purely on a player (blocked-only).
     */
    @Nullable
    public static BuildJob claimNext(ServerLevel level, Settlement settlement, SettlerEntity builder) {
        long now = level.getGameTime();
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        List<BuildJob> active = data.activeJobs(settlement.id);
        for (BuildJob job : active) {
            if (builder.getUUID().equals(job.claimant) && job.workable() && job.leaseUntil > now) {
                return job;
            }
        }
        for (BuildJob job : active) {
            if (!job.workable()) {
                continue;
            }
            if (job.claimant != null && !job.claimant.equals(builder.getUUID()) && job.leaseUntil > now) {
                continue; // another Builder's site: one Builder per site
            }
            if (job.exhausted() && job.blockedCount() > 0 && !job.allowOverwrite) {
                continue; // waits on the player, not on this Builder
            }
            job.claimant = builder.getUUID();
            job.leaseUntil = now + LEASE_TICKS;
            return job;
        }
        return null;
    }

    public static void renew(ServerLevel level, BuildJob job, SettlerEntity builder) {
        if (builder.getUUID().equals(job.claimant)) {
            job.leaseUntil = level.getGameTime() + LEASE_TICKS;
        }
    }

    public static void release(BuildJob job, SettlerEntity builder) {
        if (builder.getUUID().equals(job.claimant)) {
            job.claimant = null;
            job.leaseUntil = 0L;
        }
    }

    /** Whether some OTHER live Builder holds this job's lease (test window). */
    public static boolean heldByOther(ServerLevel level, BuildJob job, UUID builder) {
        return job.claimant != null && !job.claimant.equals(builder)
            && job.leaseUntil > level.getGameTime();
    }

    // -------------------------------------------------------- completion ---

    /** Called when a job has no step left to try. */
    public static void finish(ServerLevel level, Settlement settlement, BuildJob job) {
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        if (job.skippedCount() > 0 && !job.secondPass) {
            job.secondPass = true;
            job.retrySkipped();
            data.changed();
            return;
        }
        job.state = BuildJob.State.COMPLETE;
        job.claimant = null;
        if (job.skippedCount() > 0) {
            // Safety net (lead + owner, Sunday): a block nobody could reach
            // -- not even with the last-resort stretch -- never hangs the
            // site. The Builder has finished everything else and taken his
            // ladders down; he moves on to the next order and asks for a
            // hand, once per job (the chat line cannot repeat).
            String where = job.skippedWhere(3);
            job.setStatus(BuildStatus.SKIPPED, job.skippedCount(), where);
            announce(level, settlement, Component.translatable("hearthstead.builder.notice.needs_hand",
                job.skippedCount(), job.label, where).withStyle(ChatFormatting.YELLOW), job.bounds.getCenter());
        } else {
            job.setStatus(BuildStatus.DONE);
        }
        // A segment blueprint (palisade / stone wall pieces, gatehouses) is a
        // defense work too, not only a drawn line (QA: 4 generated-build fails).
        if (job.segment != null && job.kind != BuildJob.Kind.DISMANTLE) {
            long[] blocks = new long[job.placed.cardinality()];
            int k = 0;
            for (int i = job.placed.nextSetBit(0); i >= 0 && k < blocks.length; i = job.placed.nextSetBit(i + 1)) {
                blocks[k++] = job.pos(i).asLong();
            }
            List<BlockPos> gates = new java.util.ArrayList<>(job.gates);
            if (gates.isEmpty()) {
                // A gate blueprint (palisade gate, gatehouse) carries its
                // gates as blocks: every fence gate and door lower half.
                for (int i = job.placed.nextSetBit(0); i >= 0; i = job.placed.nextSetBit(i + 1)) {
                    net.minecraft.world.level.block.state.BlockState st = job.state(i);
                    if (st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                        || (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                            && st.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)) {
                        gates.add(job.pos(i));
                    }
                }
            }
            data.recordDefense(settlement.id, new BuildSiteSavedData.DefenseWork(job.id, job.segment,
                blocks, List.copyOf(gates)));
        }
        if (job.kind == BuildJob.Kind.DISMANTLE && job.targetId != null) {
            data.forgetDefense(settlement.id, job.targetId);
        }
        boolean levelRose = false;
        if (job.kind == BuildJob.Kind.UPGRADE && job.targetId != null) {
            // The plaque is the surveyor: let it measure the room now, so
            // the new level shows at once instead of at the next survey.
            for (Building building : settlement.buildings) {
                if (building.id.equals(job.targetId) && level.isLoaded(building.plaquePos)
                    && level.getBlockEntity(building.plaquePos)
                        instanceof com.hearthstead.block.PlaqueBlockEntity plaque) {
                    int before = building.level;
                    plaque.survey(level);
                    levelRose |= building.level > before;
                }
            }
        }
        data.changed();
        if (job.kind == BuildJob.Kind.BLUEPRINT || job.kind == BuildJob.Kind.DEFENSE_LINE) {
            // Town chat: a finished building is news for every member.
            com.hearthstead.settlement.TownChat.send(level, settlement,
                com.hearthstead.settlement.TownChat.Kind.BUILDING, Component.literal(job.label));
        } else if (!levelRose) {
            // Barricades, tear-downs and an upgrade that did not (yet) lift
            // the level stay a note for whoever is nearby; a lifted level is
            // told by the plaque as a town chat upgrade line.
            announce(level, settlement, Component.translatable("hearthstead.builder.notice.done",
                job.label).withStyle(ChatFormatting.GOLD), job.bounds.getCenter());
        }
        if (job.kind != BuildJob.Kind.DISMANTLE) {
            com.hearthstead.fx.FxHooks.buildDone(level, job.bounds);
        }
    }

    // ---------------------------------------------------------- builders ---

    /** Any valid Builder's Hut with a hired Builder. */
    public static boolean hasBuilder(ServerLevel level, Settlement settlement) {
        for (Building building : settlement.buildings) {
            if (building.valid && building.type == BuildingType.BUILDERS_HUT && !building.workers.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static Building anyHut(Settlement settlement) {
        for (Building building : settlement.buildings) {
            if (building.valid && building.type == BuildingType.BUILDERS_HUT) {
                return building;
            }
        }
        return null;
    }

    /** The Builder's own hut, or null when he is not employed at a valid one. */
    @Nullable
    public static Building hutOf(Settlement settlement, SettlerEntity builder) {
        Building employer = Employment.employerOf(settlement, builder.getUUID());
        return employer != null && employer.valid && employer.type == BuildingType.BUILDERS_HUT
            ? employer : null;
    }

    /**
     * The headline of the site a Builder is working (for the settler sheet
     * lane): "Waiting for 24 oak planks", or null when he has none.
     */
    @Nullable
    public static Component headlineFor(ServerLevel level, SettlerEntity builder) {
        if (builder.getProfession() != Profession.BUILDER) {
            return null;
        }
        Settlement settlement = builder.settlement();
        if (settlement == null) {
            return null;
        }
        for (BuildJob job : BuildSiteSavedData.get(level).activeJobs(settlement.id)) {
            if (builder.getUUID().equals(job.claimant)) {
                return Component.translatable("hearthstead.builder.headline", job.label,
                    job.status.describe(job.statusArgs));
            }
        }
        return null;
    }

    // ------------------------------------------------------------- raids ---

    /** A raid is warned (first or recurring) or already on its way. */
    public static boolean raidWarned(ServerLevel level, Settlement settlement) {
        if (settlement.pendingRaid != null) {
            return true;
        }
        var lifecycle = settlement.raidLifecycle;
        if (lifecycle.firstState() == FirstRaidState.SCHEDULED && lifecycle.queuedPlan().isPresent()) {
            return true;
        }
        return lifecycle.recurringWarnedPlan().isPresent() || settlement.recurringRaidRun.isQueued();
    }

    /**
     * Periodic (every 40 ticks) upkeep for one settlement: raid rush,
     * statuses of sites nobody works, one notice per newly blocked site.
     */
    /** A job with every step finished completes at most this long after (ticks). */
    static final long FINISH_WATCHDOG_TICKS = 1200L;

    public static void tick(ServerLevel level, Settlement settlement) {
        BuildSiteSavedData data = BuildSiteSavedData.existing(level);
        if (data == null) {
            return;
        }
        // Retry any stopped site whose ladders never got a cleanup (queue was
        // full at the time, or an older save): ownership is never dropped.
        for (BuildJob stopped : List.copyOf(data.jobs(settlement.id))) {
            if (stopped.state != BuildJob.State.ACTIVE && !stopped.scaffold.isEmpty()) {
                queueScaffoldCleanup(level, data, stopped);
            }
        }
        List<BuildJob> active = data.activeJobs(settlement.id);
        if (active.isEmpty()) {
            // Failed ladder removal stays owned, but never starts another pass ahead of other work.
            // Reuse the persisted job, including old COMPLETE/SKIPPED saves, without new queue entries.
            List<BuildJob> retries = data.jobs(settlement.id).stream()
                .filter(job -> job.state == BuildJob.State.COMPLETE && !job.paused && job.hasSkippedScaffold())
                .sorted(java.util.Comparator.comparingInt(job -> job.order)).toList();
            if (!retries.isEmpty()) {
                // Persist a bounded round-robin order: a failed first column cannot starve the next.
                for (int i = 1; i < retries.size(); i++) retries.get(i).order = i - 1;
                BuildJob stopped = retries.get(0);
                stopped.order = retries.size() - 1;
                stopped.retrySkippedScaffold();
                stopped.state = BuildJob.State.ACTIVE;
                stopped.secondPass = true; // One bounded pass, then yield to any newly queued work.
                stopped.rush = false;
                stopped.claimant = null;
                stopped.leaseUntil = 0L;
                stopped.exhaustedSince = 0L;
                stopped.setStatus(BuildStatus.QUEUED);
                data.changed();
            }
            active = data.activeJobs(settlement.id);
            if (active.isEmpty()) return;
        }
        boolean warned = raidWarned(level, settlement);
        boolean builder = hasBuilder(level, settlement);
        long now = level.getGameTime();
        boolean changed = false;
        for (BuildJob job : List.copyOf(active)) {
            // Sunday rule: no job hangs forever. Every step is finished but the
            // job is still ACTIVE (a last rung he cannot get off, W36d sword
            // hall 505/505): after a minute the ladders go to their own cleanup
            // job and the site is finished here.
            // A site waiting on a player's block, or paused, waits by design.
            if (job.exhausted() && job.state == BuildJob.State.ACTIVE && !job.paused
                && (job.blockedCount() == 0 || job.allowOverwrite)) {
                if (job.exhaustedSince == 0L) {
                    job.exhaustedSince = now;
                } else if (now - job.exhaustedSince > FINISH_WATCHDOG_TICKS) {
                    job.exhaustedSince = 0L;
                    queueScaffoldCleanup(level, data, job);
                    if (job.scaffold.isEmpty()) {
                        com.hearthstead.Hearthstead.LOGGER.info("builder_finish_watchdog {} {}/{}", job.label,
                            job.doneCount(), job.size());
                        finish(level, settlement, job);
                        changed = true;
                        continue;
                    }
                }
            } else {
                job.exhaustedSince = 0L;
            }
            if (warned && job.kind == BuildJob.Kind.BARRICADE && !job.rush) {
                job.rush = true; // barricades first: re-raise what the player placed
                changed = true;
            }
            boolean worked = job.claimant != null && job.leaseUntil > now;
            if (!worked) {
                BuildStatus idle = job.paused ? BuildStatus.PAUSED
                    : !builder ? BuildStatus.NO_BUILDER
                    : settlement.pendingRaid != null ? BuildStatus.SHELTERING
                    : job.status == BuildStatus.WAITING_FOR || job.status == BuildStatus.NEEDS_PLAYER
                      || job.status == BuildStatus.BLOCKED_PLAYER_BLOCK ? job.status
                    : BuildStatus.QUEUED;
                if (idle != job.status) {
                    job.setStatus(idle);
                    changed = true;
                }
            }
        }
        if (changed) {
            data.changed();
        }
    }

    /**
     * Says once (then at most every {@link #NOTICE_INTERVAL}) that a site is
     * stuck on something only the player can fix. Sent to players near the
     * settlement; never spammed.
     */
    public static void noticeBlocked(ServerLevel level, Settlement settlement, BuildJob job,
                                     @Nullable SettlerEntity builder, Component what) {
        long now = level.getGameTime();
        Long last = LAST_NOTICE.get(job.id);
        if (last != null && now - last < NOTICE_INTERVAL) {
            return;
        }
        LAST_NOTICE.put(job.id, now);
        Component who = builder == null ? Component.translatable("hearthstead.profession.builder")
            : builder.getDisplayName();
        announce(level, settlement, Component.translatable("hearthstead.builder.notice.blocked",
            who, what, job.label).withStyle(ChatFormatting.YELLOW), job.bounds.getCenter());
    }

    private static final java.util.Map<UUID, Long> LAST_NOTICE = new java.util.HashMap<>();

    static void announce(ServerLevel level, Settlement settlement, Component message, BlockPos near) {
        int reach = settlement.radius + 64;
        for (ServerPlayer player : level.players()) {
            if (settlement.center != null
                && player.blockPosition().distSqr(settlement.center) <= (double) reach * reach) {
                player.sendSystemMessage(message);
            }
        }
    }
}

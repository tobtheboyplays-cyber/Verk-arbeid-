package com.hearthstead.qa.watchdog;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.AcquireRequestedEquipmentGoal;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.BannerTeamGoal;
import com.hearthstead.entity.ai.BoundedStrollGoal;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.entity.ai.CrafterWorkGoal;
import com.hearthstead.entity.ai.EatFromHearthGoal;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.entity.ai.FisherWorkGoal;
import com.hearthstead.entity.ai.FreeTimeGoal;
import com.hearthstead.entity.ai.GoToPostGoal;
import com.hearthstead.entity.ai.GuardLeapGoal;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.GuardOrderGoal;
import com.hearthstead.entity.ai.GuardPatrolGoal;
import com.hearthstead.entity.ai.GuardRaidEscortGoal;
import com.hearthstead.entity.ai.GuardRecoveryGoal;
import com.hearthstead.entity.ai.GuardRespondToAlertGoal;
import com.hearthstead.entity.ai.HerderWorkGoal;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.entity.ai.InnkeeperWorkGoal;
import com.hearthstead.entity.ai.LumbererSelfCraftGoal;
import com.hearthstead.entity.ai.LumbererWorkGoal;
import com.hearthstead.entity.ai.MinerWorkGoal;
import com.hearthstead.entity.ai.RepairWorkGoal;
import com.hearthstead.entity.ai.RespondToSummonsGoal;
import com.hearthstead.entity.ai.RestAtNightGoal;
import com.hearthstead.entity.ai.ReturnToSettlementGoal;
import com.hearthstead.entity.ai.SaluteCaptainGoal;
import com.hearthstead.entity.ai.ScholarWorkGoal;
import com.hearthstead.entity.ai.SettlerPanicGoal;
import com.hearthstead.entity.ai.TavernVisitGoal;
import com.hearthstead.entity.ai.TidyWarehouseGoal;
import com.hearthstead.entity.ai.TraderWorkGoal;
import com.hearthstead.entity.ai.TravelerJoinGoal;
import com.hearthstead.entity.ai.WorkCompanionGoal;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestRecord;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side worker watchdog: a read-only observer that samples every
 * loaded settler once per second and reports stalls (see {@link WatchdogFlag})
 * and per-job time accounting.
 *
 * <p>Off by default. Enable with the server config
 * {@code debug.workerWatchdog = true} or {@code /hearthstead watchdog on}.
 * While disabled the only cost is one boolean check per server tick. While
 * enabled the sampler reuses its buffers; allocation happens only when a flag
 * is raised or a report is written.
 *
 * <p>It never writes to a settler, a goal, navigation, a container or saved
 * data. Output goes to the server log ({@code [HS-WATCHDOG]}) and to
 * {@code <world>/hearthstead-watchdog/}: {@code events.csv} (every raise and
 * clear), {@code summary-<session>.json} (per-job statistics) and optionally
 * {@code samples.csv} (one row per settler every five seconds).
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WorkerWatchdog {
    private static final String TAG = "[HS-WATCHDOG] ";
    /** Sample cadence in server ticks. */
    public static final int SAMPLE_INTERVAL = 20;
    private static final int SAMPLES_CSV_EVERY = 5;
    private static final long SUMMARY_EVERY = 6_000L;
    private static final long PRUNE_AFTER = 72_000L;

    /** Command override: null follows the config value. */
    private static volatile Boolean override;
    private static volatile boolean samplesCsv;
    private static State state;
    private static final Map<Class<?>, GoalKind> KINDS = new IdentityHashMap<>();
    private static final EntityTypeTest<net.minecraft.world.entity.Entity, SettlerEntity> SETTLERS =
        EntityTypeTest.forClass(SettlerEntity.class);
    private static final java.util.function.Predicate<SettlerEntity> ALIVE = SettlerEntity::isAlive;

    static {
        put(GoalKind.WORK, CourierWorkGoal.class, FarmerWorkGoal.class, LumbererWorkGoal.class,
            TraderWorkGoal.class, CrafterWorkGoal.class, MinerWorkGoal.class,
            InnkeeperWorkGoal.class, ScholarWorkGoal.class, HerderWorkGoal.class,
            FisherWorkGoal.class, HunterWorkGoal.class, GuardPatrolGoal.class,
            GuardOrderGoal.class, TidyWarehouseGoal.class, RepairWorkGoal.class,
            com.hearthstead.entity.ai.ArcherDefaultPostGoal.class,
            com.hearthstead.entity.ai.BuilderWorkGoal.class);
        put(GoalKind.COMMUTE, GoToPostGoal.class);
        put(GoalKind.SUPPORT, AcquireRequestedEquipmentGoal.class, LumbererSelfCraftGoal.class,
            com.hearthstead.entity.ai.MinerEscapeGoal.class);
        put(GoalKind.NEED, EatFromHearthGoal.class, RestAtNightGoal.class, TavernVisitGoal.class,
            GuardRecoveryGoal.class);
        put(GoalKind.THREAT, SettlerPanicGoal.class, GuardMeleeGoal.class, ArcherAttackGoal.class,
            GuardLeapGoal.class, GuardRaidEscortGoal.class, GuardRespondToAlertGoal.class,
            BannerTeamGoal.class, RespondToSummonsGoal.class);
        put(GoalKind.IDLE, BoundedStrollGoal.class, FreeTimeGoal.class, WorkCompanionGoal.class,
            SaluteCaptainGoal.class, ReturnToSettlementGoal.class, TravelerJoinGoal.class);
    }

    private static void put(GoalKind kind, Class<?>... classes) {
        for (Class<?> c : classes) {
            KINDS.put(c, kind);
        }
    }

    private WorkerWatchdog() {
    }

    // ----------------------------------------------------------------- state

    private static final class Entry {
        final UUID id;
        final WatchdogDetector.Track track = new WatchdogDetector.Track();
        String name;
        Profession profession = Profession.NONE;
        int lastEffort = -1;
        long lastSeen;
        Goal dominant;

        Entry(UUID id) {
            this.id = id;
        }
    }

    private static final class State {
        final MinecraftServer server;
        final Path dir;
        final String session;
        final long startedAt;
        final Map<UUID, Entry> entries = new HashMap<>();
        final Map<Profession, WatchdogJobStats> jobs = new EnumMap<>(Profession.class);
        final List<SettlerEntity> buffer = new ArrayList<>();
        final WatchdogDetector.Sample sample = new WatchdogDetector.Sample();
        final WatchdogDetector.Result result = new WatchdogDetector.Result();
        BufferedWriter events;
        BufferedWriter samples;
        long lastSummary;
        long sampleCount;

        State(MinecraftServer server) {
            this.server = server;
            this.dir = server.getWorldPath(LevelResource.ROOT).resolve("hearthstead-watchdog");
            this.session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            this.startedAt = server.overworld().getGameTime();
            this.lastSummary = startedAt;
        }

        WatchdogJobStats job(Profession p) {
            return jobs.computeIfAbsent(p, k -> new WatchdogJobStats(k.key()));
        }
    }

    public static boolean enabled() {
        Boolean o = override;
        return o != null ? o : HearthsteadServerConfig.workerWatchdog();
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SAMPLE_INTERVAL != 0) {
            return;
        }
        if (!enabled()) {
            if (state != null) {
                shutdown("disabled");
            }
            return;
        }
        if (state == null || state.server != server) {
            start(server);
        }
        try {
            sampleAll(state);
        } catch (RuntimeException failure) {
            // A diagnostic must never take the server down with it.
            Hearthstead.LOGGER.error(TAG + "sampling failed; watchdog disabled", failure);
            override = Boolean.FALSE;
            shutdown("error");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (state != null) {
            shutdown("server_stopping");
        }
        override = null;
    }

    private static void start(MinecraftServer server) {
        state = new State(server);
        try {
            Files.createDirectories(state.dir);
            Path eventsFile = state.dir.resolve("events.csv");
            boolean fresh = !Files.exists(eventsFile);
            state.events = Files.newBufferedWriter(eventsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                state.events.write("session,gameTime,dayTime,phase,edge,flag,durationTicks,name,uuid,"
                    + "profession,activity,goals,x,y,z,cause,carried,hunger,energy,health,effort,"
                    + "stop,route,equipment,requests,nav\n");
            }
        } catch (IOException failure) {
            Hearthstead.LOGGER.warn(TAG + "cannot open output in {}: {}", state.dir, failure.toString());
        }
        Hearthstead.LOGGER.info(TAG + "enabled, session {} -> {}", state.session, state.dir);
    }

    private static void shutdown(String why) {
        State s = state;
        if (s == null) {
            return;
        }
        long now = s.server.overworld().getGameTime();
        for (Entry e : s.entries.values()) {
            s.result.raised = 0;
            s.result.cleared = 0;
            WatchdogDetector.closeAll(e.track, Math.min(now, e.track.lastSampleTick), s.result);
            recordClears(s, e, null, s.result);
        }
        writeSummary(s);
        logReport(s, null);
        close(s.events);
        close(s.samples);
        state = null;
        Hearthstead.LOGGER.info(TAG + "stopped ({}), summary written to {}", why, s.dir);
    }

    // -------------------------------------------------------------- sampling

    private static void sampleAll(State s) {
        long now = s.server.overworld().getGameTime();
        boolean writeSamples = samplesCsv && (s.sampleCount++ % SAMPLES_CSV_EVERY == 0);
        for (ServerLevel level : s.server.getAllLevels()) {
            s.buffer.clear();
            level.getEntities(SETTLERS, ALIVE, s.buffer);
            for (int i = 0; i < s.buffer.size(); i++) {
                sampleOne(s, level, s.buffer.get(i), now, writeSamples);
            }
        }
        s.buffer.clear();
        if (now - s.lastSummary >= SUMMARY_EVERY) {
            s.lastSummary = now;
            prune(s, now);
            writeSummary(s);
            if ((now - s.startedAt) % 24_000L < SUMMARY_EVERY) {
                logReport(s, null);
            }
        }
    }

    private static void sampleOne(State s, ServerLevel level, SettlerEntity settler, long now,
                                  boolean writeSamples) {
        if (settler.isNoAi()) {
            return;
        }
        Entry e = s.entries.get(settler.getUUID());
        if (e == null) {
            e = new Entry(settler.getUUID());
            s.entries.put(e.id, e);
        }
        Profession profession = settler.getProfession();
        if (profession != e.profession || e.name == null) {
            e.profession = profession;
            e.name = settler.getSettlerName();
            s.job(profession).settlers++;
        }
        e.lastSeen = now;
        WatchdogDetector.Sample sample = s.sample;
        DayPhase phase = DayPhase.of(level.getDayTime());
        sample.tick = now;
        // A night-watch guard's work phase is the night: use the same
        // predicate the goals use, so its daytime sleep is not a stall.
        Settlement seat = profession.martial() ? settler.settlement() : null;
        sample.workPhase = seat != null
            ? com.hearthstead.settlement.Schedule.shouldWork(seat, settler, phase)
            : phase.work();
        sample.employed = profession.employed() && settler.isBound();

        // Dominant running goal.
        GoalKind kind = GoalKind.NONE;
        Goal dominant = null;
        for (WrappedGoal wrapped : settler.goalSelector.getAvailableGoals()) {
            if (!wrapped.isRunning()) {
                continue;
            }
            GoalKind k = KINDS.get(wrapped.getGoal().getClass());
            if (k != null && k.ordinal() > kind.ordinal()) {
                kind = k;
                dominant = wrapped.getGoal();
            }
        }
        e.dominant = dominant;
        sample.kind = kind;
        sample.jobGoalId = kind.jobShaped() && dominant != null ? System.identityHashCode(dominant) | 1 : 0;
        PathNavigation navigation = settler.getNavigation();
        sample.stationaryOk = stationaryOk(profession, dominant, navigation);
        sample.outputFree = profession.martial()
            && (dominant instanceof GuardPatrolGoal || dominant instanceof GuardOrderGoal
                || dominant instanceof com.hearthstead.entity.ai.ArcherDefaultPostGoal);
        sample.x = settler.getX();
        sample.y = settler.getY();
        sample.z = settler.getZ();

        int carry = 1;
        boolean carrying = false;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty()) {
                carrying = true;
                carry = carry * 31 + BuiltInRegistries.ITEM.getId(stack.getItem()) * 97 + stack.getCount();
            }
        }
        ItemStack main = settler.getMainHandItem();
        ItemStack off = settler.getOffhandItem();
        carry = carry * 31 + (main.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(main.getItem()) * 97 + main.getCount());
        carry = carry * 31 + (off.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(off.getItem()) * 97 + off.getCount());
        sample.carryHash = carry;
        sample.carrying = carrying;

        int effort = settler.effortSpent();
        int output = carry;
        output = output * 31 + effort;
        output = output * 31 + settler.tradeXp();
        output = output * 31 + settler.getActivity().ordinal();
        output = output * 31 + settler.getFisherCycleTick();
        sample.outputHash = output;
        sample.routeFailureTick = routeFailureTick(settler);
        sample.navigationStuck = navigation.isStuck();
        sample.needLevel = settler.getEnergy() + settler.getHunger();

        WatchdogDetector.Result result = s.result;
        WatchdogDetector.update(e.track, sample, result);

        // Accounting.
        WatchdogJobStats job = s.job(profession);
        if (e.lastEffort >= 0 && effort > e.lastEffort) {
            job.effortUnits += effort - e.lastEffort;
        }
        e.lastEffort = effort;
        if (sample.workPhase && sample.employed) {
            job.workSamples++;
            job.kindSamples[kind.ordinal()]++;
            if (e.track.productive) {
                job.productiveSamples++;
            }
            if ((e.track.active & (WatchdogFlag.STUCK.bit() | WatchdogFlag.LOOP.bit())) != 0) {
                job.stalledSamples++;
            }
            if (kind.idle()) {
                job.idleReasonSamples[idleReason(level, settler).ordinal()]++;
            }
        }
        if (result.raised != 0) {
            recordRaises(s, e, settler, level, phase, result);
        }
        if (result.cleared != 0) {
            recordClears(s, e, settler, result);
        }
        if (writeSamples) {
            writeSample(s, e, settler, level, phase, kind, dominant);
        }
    }

    private static boolean stationaryOk(Profession profession, Goal dominant, PathNavigation navigation) {
        if (dominant == null || !navigation.isDone()) {
            return false;
        }
        if (profession.martial()) {
            return dominant instanceof GuardOrderGoal || dominant instanceof GuardPatrolGoal
                || dominant instanceof com.hearthstead.entity.ai.ArcherDefaultPostGoal;
        }
        // Standing behind a counter is the job for these two.
        return (profession == Profession.INNKEEPER && dominant instanceof InnkeeperWorkGoal)
            || (profession == Profession.TRADER && dominant instanceof TraderWorkGoal);
    }

    private static long routeFailureTick(SettlerEntity settler) {
        // routeFailureNote() is "reason@tick" or "none". Parse only the tail,
        // without building intermediate strings.
        String note = settler.routeFailureNote();
        int at = note.lastIndexOf('@');
        if (at < 0) {
            return Long.MIN_VALUE;
        }
        long v = 0;
        boolean neg = false;
        for (int i = at + 1; i < note.length(); i++) {
            char c = note.charAt(i);
            if (c == '-' && i == at + 1) {
                neg = true;
            } else if (c >= '0' && c <= '9') {
                v = v * 10 + (c - '0');
            } else {
                return Long.MIN_VALUE;
            }
        }
        return neg ? -v : v;
    }

    private static WatchdogJobStats.IdleReason idleReason(ServerLevel level, SettlerEntity settler) {
        if (!settler.isBound()) {
            return WatchdogJobStats.IdleReason.UNBOUND;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || Employment.employerOf(settlement, settler.getUUID()) == null) {
            return WatchdogJobStats.IdleReason.NO_EMPLOYER;
        }
        if (!settler.requestedEquipmentIcon().isEmpty()) {
            return WatchdogJobStats.IdleReason.NO_TOOL;
        }
        if (settler.logisticsStopReason() != com.hearthstead.logistics.StopReason.NONE) {
            return WatchdogJobStats.IdleReason.LOGISTICS_STOP;
        }
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        if (employer != null && employer.valid && employer.anchor != null
            && com.hearthstead.building.Production.produces(employer.type)) {
            // Read-only: counts the bench's containers, like CrafterWorkGoal.canUse.
            if (!settler.blockPosition().closerThan(employer.anchor,
                    com.hearthstead.settlement.Schedule.AT_POST)) {
                return WatchdogJobStats.IdleReason.NOT_AT_POST;
            }
            if (com.hearthstead.building.Production.ready(level, employer) == null) {
                return WatchdogJobStats.IdleReason.NO_INPUT;
            }
        }
        // Effort paces work but gates no goal in this tree (economy lane,
        // 2026-09-26): only report it when nothing else explains the idle.
        if (settler.isEffortSpent()) {
            return WatchdogJobStats.IdleReason.EFFORT_SPENT;
        }
        return WatchdogJobStats.IdleReason.OTHER;
    }

    // ---------------------------------------------------------------- output

    private static void recordRaises(State s, Entry e, SettlerEntity settler, ServerLevel level,
                                     DayPhase phase, WatchdogDetector.Result result) {
        WatchdogJobStats job = s.job(e.profession);
        for (WatchdogFlag flag : WatchdogFlag.VALUES) {
            if ((result.raised & flag.bit()) == 0) {
                continue;
            }
            job.episodes[flag.ordinal()]++;
            String cause = cause(flag, e, settler, level);
            job.cause(flag.name() + "|" + cause);
            String goals = runningGoals(settler);
            String carried = carried(settler);
            String route = settler.routeFailureNote();
            String equipment = settler.requestedEquipmentIcon().isEmpty() ? "none"
                : BuiltInRegistries.ITEM.getKey(settler.requestedEquipmentIcon().getItem()).toString();
            String requests = requests(level, settler);
            String nav = nav(settler.getNavigation()) + " state=" + goalState(e.dominant);
            Hearthstead.LOGGER.info(TAG + "{} {} ({}) act={} goals=[{}] pos={},{},{} cause={} carried=[{}] "
                    + "hunger={} energy={} effort={} stop={} route={} equip={} req=[{}] nav={}",
                flag, e.name, e.profession.key(), settler.getActivity().key(), goals,
                (int) settler.getX(), (int) settler.getY(), (int) settler.getZ(), cause, carried,
                (int) settler.getHunger(), (int) settler.getEnergy(), settler.effortDescribe(),
                settler.logisticsStopReason(), route, equipment, requests, nav);
            csv(s, level, phase, "RAISE", flag, 0L, e, settler, goals, cause, carried, route,
                equipment, requests, nav);
        }
    }

    private static void recordClears(State s, Entry e, SettlerEntity settler, WatchdogDetector.Result result) {
        WatchdogJobStats job = s.job(e.profession);
        for (WatchdogFlag flag : WatchdogFlag.VALUES) {
            if ((result.cleared & flag.bit()) == 0) {
                continue;
            }
            long duration = result.clearedDuration[flag.ordinal()];
            job.episodeTicks[flag.ordinal()] += duration;
            if (settler != null && settler.level() instanceof ServerLevel level) {
                Hearthstead.LOGGER.info(TAG + "{} cleared for {} ({}) after {}s act={}",
                    flag, e.name, e.profession.key(), duration / 20L, settler.getActivity().key());
                csv(s, level, DayPhase.of(level.getDayTime()), "CLEAR", flag, duration, e, settler,
                    runningGoals(settler), "", "", "", "", "", "");
            } else if (s.events != null) {
                try {
                    s.events.write(String.join(",", s.session, "", "", "", "CLOSE", flag.name(),
                        Long.toString(duration), csvCell(e.name), e.id.toString(), e.profession.key())
                        + ",,,,,,,,,,,,,,,,\n");
                } catch (IOException ignored) {
                    // Output is best-effort.
                }
            }
        }
    }

    private static String cause(WatchdogFlag flag, Entry e, SettlerEntity settler, ServerLevel level) {
        String goal = e.dominant == null ? "none" : e.dominant.getClass().getSimpleName();
        String activity = settler.getActivity().key();
        return switch (flag) {
            case STUCK -> goal + "|" + activity + "|" + (e.track.stuckNeed ? "need_no_recovery"
                : e.track.stuckNoOutput ? "no_output" : "static");
            case IDLE_IN_WORK -> goal + "|" + idleReason(level, settler).name()
                + "|" + settler.logisticsStopReason().name();
            case LOOP -> goal + "|" + activity;
            case PATH_FAIL -> goal + "|" + routeReason(settler.routeFailureNote());
            case ORPHANED_ITEMS -> goal + "|" + firstCarried(settler);
        };
    }

    private static String routeReason(String note) {
        int at = note.lastIndexOf('@');
        String reason = at < 0 ? note : note.substring(0, at);
        // Keep the histogram key bounded: drop coordinates and numbers.
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < reason.length() && b.length() < 60; i++) {
            char c = reason.charAt(i);
            if (Character.isLetter(c) || c == '_' || c == ' ' || c == ':') {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static String firstCarried(SettlerEntity settler) {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty()) {
                return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            }
        }
        return "hands";
    }

    private static String runningGoals(SettlerEntity settler) {
        StringBuilder b = new StringBuilder();
        for (WrappedGoal wrapped : settler.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning()) {
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(wrapped.getGoal().getClass().getSimpleName());
            }
        }
        return b.toString();
    }

    private static String carried(SettlerEntity settler) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (!stack.isEmpty()) {
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(stack.getCount()).append('x')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            }
        }
        b.append(" | main=").append(itemKey(settler.getMainHandItem()))
            .append(" off=").append(itemKey(settler.getOffhandItem()));
        return b.toString();
    }

    private static String itemKey(ItemStack stack) {
        return stack.isEmpty() ? "-" : stack.getCount() + "x"
            + BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String requests(ServerLevel level, SettlerEntity settler) {
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return "no_settlement";
        }
        RequestLedgerSavedData data = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = data == null ? null : data.existing(settlement.id);
        if (ledger == null) {
            return "no_ledger";
        }
        if (ledger.quarantined()) {
            return "quarantined:" + ledger.quarantineReason();
        }
        StringBuilder b = new StringBuilder();
        int mine = 0;
        int total = 0;
        for (RequestRecord record : ledger.active()) {
            total++;
            boolean requester = settler.getUUID().equals(record.requesterId());
            boolean courier = settler.getUUID().equals(record.courierId());
            if (!requester && !courier) {
                continue;
            }
            if (mine++ < 4) {
                b.append(requester ? "R:" : "C:").append(record.type()).append('/')
                    .append(record.effectiveState()).append('/').append(record.blocker().id()).append(' ');
            }
        }
        return "mine=" + mine + " active=" + total + " " + b.toString().trim();
    }

    /**
     * Read-only peek at a goal's own progress fields (mode, action, target,
     * tree, done...) for the raise line only. Reflection runs on a raise,
     * never per sample, and never writes.
     */
    private static String goalState(Goal goal) {
        if (goal == null) {
            return "-";
        }
        StringBuilder b = new StringBuilder();
        for (java.lang.reflect.Field f : goal.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            String n = f.getName();
            if (!(n.equals("mode") || n.equals("action") || n.equals("done") || n.equals("finished")
                || n.equals("target") || n.equals("treeBase") || n.equals("workActionId")
                || n.equals("destination") || n.equals("recipe") || n.equals("stuckChecks")
                || n.equals("treeLogs") || n.equals("cut") || n.equals("stepTo"))) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(goal);
                String text = v instanceof java.util.Collection<?> c ? "n" + c.size() : String.valueOf(v);
                b.append(n).append('=').append(text.length() > 40 ? text.substring(0, 40) : text).append(';');
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Diagnostics only.
            }
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    private static String nav(PathNavigation navigation) {
        var path = navigation.getPath();
        if (path == null) {
            return "none done=" + navigation.isDone();
        }
        return "target=" + path.getTarget().toShortString() + " reach=" + path.canReach()
            + " node=" + path.getNextNodeIndex() + "/" + path.getNodeCount()
            + " stuck=" + navigation.isStuck();
    }

    private static void csv(State s, ServerLevel level, DayPhase phase, String edge, WatchdogFlag flag,
                            long duration, Entry e, SettlerEntity settler, String goals, String cause,
                            String carried, String route, String equipment, String requests, String nav) {
        if (s.events == null) {
            return;
        }
        String row = String.join(",",
            s.session, Long.toString(level.getGameTime()), Long.toString(level.getDayTime() % 24_000L),
            phase.key(), edge, flag.name(), Long.toString(duration), csvCell(e.name), e.id.toString(),
            e.profession.key(), settler.getActivity().key(), csvCell(goals),
            Integer.toString((int) settler.getX()), Integer.toString((int) settler.getY()),
            Integer.toString((int) settler.getZ()), csvCell(cause), csvCell(carried),
            Integer.toString((int) settler.getHunger()), Integer.toString((int) settler.getEnergy()),
            Integer.toString((int) settler.getHealth()), csvCell(settler.effortDescribe()),
            settler.logisticsStopReason().name(), csvCell(route), csvCell(equipment),
            csvCell(requests), csvCell(nav));
        try {
            s.events.write(row);
            s.events.write('\n');
        } catch (IOException ignored) {
            // Output is best-effort.
        }
    }

    private static void writeSample(State s, Entry e, SettlerEntity settler, ServerLevel level,
                                    DayPhase phase, GoalKind kind, Goal dominant) {
        try {
            if (s.samples == null) {
                Path file = s.dir.resolve("samples-" + s.session + ".csv");
                s.samples = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                s.samples.write("gameTime,dayTime,phase,name,profession,kind,goal,activity,x,y,z,"
                    + "bag,hunger,energy,health,effortSpent,flags\n");
            }
            int bag = 0;
            for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                bag += settler.bag.getItem(i).getCount();
            }
            // A Stout Straps batch's waiting load counts as carried.
            bag += com.hearthstead.settlement.request.RequestLedgerService.stowedCount(settler);
            s.samples.write(String.format(Locale.ROOT, "%d,%d,%s,%s,%s,%s,%s,%s,%.1f,%.1f,%.1f,%d,%.0f,%.0f,%.0f,%d,%d%n",
                level.getGameTime(), level.getDayTime() % 24_000L, phase.key(), csvCell(e.name),
                e.profession.key(), kind.name(), dominant == null ? "-" : dominant.getClass().getSimpleName(),
                settler.getActivity().key(), settler.getX(), settler.getY(), settler.getZ(), bag,
                settler.getHunger(), settler.getEnergy(), settler.getHealth(), settler.effortSpent(),
                e.track.active));
        } catch (IOException ignored) {
            // Output is best-effort.
        }
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace('\n', ' ').replace('\r', ' ');
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static void prune(State s, long now) {
        Iterator<Entry> it = s.entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.lastSeen > PRUNE_AFTER) {
                s.result.raised = 0;
                s.result.cleared = 0;
                WatchdogDetector.closeAll(e.track, e.track.lastSampleTick, s.result);
                recordClears(s, e, null, s.result);
                it.remove();
            }
        }
    }

    private static void writeSummary(State s) {
        StringBuilder b = new StringBuilder(4096);
        long now = s.server.overworld().getGameTime();
        b.append("{\"session\":\"").append(s.session).append("\",\"startedAt\":").append(s.startedAt)
            .append(",\"gameTime\":").append(now).append(",\"elapsedTicks\":").append(now - s.startedAt)
            .append(",\"sampleInterval\":").append(SAMPLE_INTERVAL)
            .append(",\"settlersTracked\":").append(s.entries.size()).append(",\"jobs\":[");
        boolean first = true;
        for (WatchdogJobStats job : s.jobs.values()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            job.appendJson(b);
        }
        b.append("]}\n");
        try {
            Files.createDirectories(s.dir);
            Files.writeString(s.dir.resolve("summary-" + s.session + ".json"), b.toString(),
                StandardCharsets.UTF_8);
            if (s.events != null) {
                s.events.flush();
            }
            if (s.samples != null) {
                s.samples.flush();
            }
        } catch (IOException failure) {
            Hearthstead.LOGGER.warn(TAG + "summary write failed: {}", failure.toString());
        }
    }

    private static List<String> reportLines(State s) {
        List<String> lines = new ArrayList<>();
        long now = s.server.overworld().getGameTime();
        lines.add(String.format(Locale.ROOT, "session %s: %.1f game-minutes observed, %d settlers",
            s.session, (now - s.startedAt) / 1200.0D, s.entries.size()));
        for (WatchdogJobStats job : s.jobs.values()) {
            if (job.workSamples > 0 || job.episodes[WatchdogFlag.ORPHANED_ITEMS.ordinal()] > 0) {
                lines.add(job.line());
            }
        }
        int open = 0;
        for (Entry e : s.entries.values()) {
            for (WatchdogFlag flag : WatchdogFlag.VALUES) {
                if ((e.track.active & flag.bit()) != 0) {
                    open++;
                    if (open <= 12) {
                        lines.add(String.format(Locale.ROOT, "  open %s %s (%s) for %ds", flag, e.name,
                            e.profession.key(), (now - e.track.raisedAt[flag.ordinal()]) / 20L));
                    }
                }
            }
        }
        lines.add("open flags: " + open);
        return lines;
    }

    private static void logReport(State s, CommandSourceStack source) {
        for (String line : reportLines(s)) {
            Hearthstead.LOGGER.info(TAG + "{}", line);
            if (source != null) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
    }

    private static void close(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Output is best-effort.
            }
        }
    }

    // --------------------------------------------------------------- command

    /** Read-only snapshot of one settler's goals and their progress fields. */
    private static int inspect(CommandSourceStack source, String name) {
        int found = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            java.util.List<SettlerEntity> all = new ArrayList<>();
            level.getEntities(SETTLERS, ALIVE, all);
            for (SettlerEntity s : all) {
                if (!name.equalsIgnoreCase(s.getSettlerName())) {
                    continue;
                }
                found++;
                StringBuilder b = new StringBuilder();
                b.append(s.getSettlerName()).append(" (").append(s.getProfession().key()).append(") act=")
                    .append(s.getActivity().key()).append(" pos=").append(s.blockPosition().toShortString())
                    .append(" hunger=").append((int) s.getHunger()).append(" energy=").append((int) s.getEnergy())
                    .append(" stop=").append(s.logisticsStopReason()).append(" route=").append(s.routeFailureNote())
                    .append(" equip=").append(s.requestedEquipmentIcon().isEmpty() ? "none"
                        : BuiltInRegistries.ITEM.getKey(s.requestedEquipmentIcon().getItem()).toString())
                    .append(" hand=").append(itemKey(s.getMainHandItem()))
                    .append(" nav=").append(nav(s.getNavigation()));
                for (WrappedGoal wrapped : s.goalSelector.getAvailableGoals()) {
                    if (wrapped.isRunning() || KINDS.get(wrapped.getGoal().getClass()) == GoalKind.WORK) {
                        b.append("\n  ").append(wrapped.isRunning() ? "RUN " : "    ")
                            .append(wrapped.getGoal().getClass().getSimpleName()).append(' ')
                            .append(goalState(wrapped.getGoal()));
                    }
                }
                String text = b.toString();
                Hearthstead.LOGGER.info(TAG + "inspect {}", text);
                source.sendSuccess(() -> Component.literal(text), false);
            }
        }
        return found;
    }

    /** {@code /hearthstead watchdog on|off|report|reset|samples on|off} (op only). */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("watchdog").requires(src -> src.hasPermission(2))
            .then(Commands.literal("on").executes(ctx -> {
                override = Boolean.TRUE;
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Worker watchdog on (samples every " + SAMPLE_INTERVAL + " ticks)."), true);
                return 1;
            }))
            .then(Commands.literal("off").executes(ctx -> {
                override = Boolean.FALSE;
                if (state != null) {
                    shutdown("command");
                }
                ctx.getSource().sendSuccess(() -> Component.literal("Worker watchdog off."), true);
                return 1;
            }))
            .then(Commands.literal("report").executes(ctx -> {
                if (state == null) {
                    ctx.getSource().sendFailure(Component.literal("Worker watchdog is not running."));
                    return 0;
                }
                writeSummary(state);
                logReport(state, ctx.getSource());
                return 1;
            }))
            .then(Commands.literal("reset").executes(ctx -> {
                if (state != null) {
                    shutdown("reset");
                }
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Worker watchdog statistics reset; a new session starts on the next sample."), true);
                return 1;
            }))
            .then(Commands.literal("inspect")
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(ctx -> inspect(ctx.getSource(),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("samples")
                .then(Commands.literal("on").executes(ctx -> {
                    samplesCsv = true;
                    ctx.getSource().sendSuccess(() -> Component.literal("Watchdog samples.csv on."), true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    samplesCsv = false;
                    ctx.getSource().sendSuccess(() -> Component.literal("Watchdog samples.csv off."), true);
                    return 1;
                })));
    }
}

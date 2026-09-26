package com.hearthstead.ambient;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.conversation.ConversationService;
import com.hearthstead.entity.LifeNeed;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.AmbientCuePayload;
import com.hearthstead.network.PayloadSend;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Living village (ambient life): small reactions, barks and aftermath
 * moments. Server side it only decides WHEN a settler waves, nods, cheers,
 * mourns or says a short line, and sends one display-only
 * {@link AmbientCuePayload} to the players tracking that settler. It never
 * touches navigation, activity, items, Coins or goals, so work time is
 * unaffected (the only movement is {@link ShelterFromRainGoal}, which runs
 * only for an IDLE settler and yields to every job).
 *
 * <p>Cheap by construction: once a second, and only around players (a
 * {@value #AROUND_PLAYER}-block box per player, at most {@value #PER_PASS_CAP}
 * settlers each). A village nobody is looking at costs nothing but the
 * five-second settlement size poll. Behind {@code [features] livingVillage}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class LivingVillage {
    static final int PASS_TICKS = 20;
    static final int POLL_TICKS = 100;
    static final double AROUND_PLAYER = 16.0D;
    static final int PER_PASS_CAP = 32;
    static final double GREET_RANGE = 6.0D;
    /** One in N passes (about once a second) a settler in view considers a line. */
    static final int CONTEXT_ODDS = 60;
    static final int SHIVER_ODDS = 30;
    static final double CHEER_RANGE = 24.0D;
    static final int CHEER_CAP = 12;
    static final int CHEER_LINES = 3;
    static final double MOURN_RANGE = 16.0D;
    static final int MOURN_CAP = 6;
    static final long MOURN_DELAY = 60L;
    static final long MOURN_RETRY = 200L;
    static final long MOURN_GIVE_UP = 12_000L;
    static final double MOMENT_RANGE = 16.0D;

    static final BarkLimiter LIMITER = new BarkLimiter();
    static final GreetingLedger GREETED = new GreetingLedger();
    private static final Map<UUID, Integer> COUNTERS = new HashMap<>();
    /** settlement id -> {population, buildings} at the last poll. */
    private static final Map<UUID, int[]> SEEN = new HashMap<>();
    private static final List<PendingMourn> PENDING = new ArrayList<>();
    /** GameTest seam: {barks, cues, last cue ordinal} per settler. */
    private static final Map<SettlerEntity, int[]> STATS = new WeakHashMap<>();

    private record PendingMourn(UUID settlementId, String name, Vec3 pos, long since, long due) {
    }

    private LivingVillage() {
    }

    /** {@code [features] livingVillage}; safe before the config loads. */
    public static boolean enabled() {
        return HearthsteadServerConfig.livingVillageEnabled();
    }

    // ---- Ticking ----

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled()) {
            return;
        }
        long now = level.getGameTime();
        if (now % PASS_TICKS == 7L) {
            aroundPlayers(level, now);
            processMourning(level, now);
        }
        if (now % POLL_TICKS == 27L) {
            pollSettlements(level, now);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LIMITER.clear();
        GREETED.clear();
        synchronized (LivingVillage.class) {
            COUNTERS.clear();
            SEEN.clear();
            PENDING.clear();
        }
    }

    private static void aroundPlayers(ServerLevel level, long now) {
        if (level.players().isEmpty()) {
            return;
        }
        Set<Integer> considered = new HashSet<>();
        long day = level.getDayTime() / 24000L;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            List<SettlerEntity> near = level.getEntitiesOfClass(SettlerEntity.class,
                player.getBoundingBox().inflate(AROUND_PLAYER, 6.0D, AROUND_PLAYER),
                s -> s.isAlive() && s.isBound() && !s.isTraveler());
            int n = 0;
            for (SettlerEntity settler : near) {
                if (n++ >= PER_PASS_CAP) {
                    break;
                }
                if (busy(settler)) {
                    continue;
                }
                Settlement settlement = settler.settlement();
                if (settlement == null || LifeNeed.threatActive(settlement, now)) {
                    continue;
                }
                if (greet(level, settler, player, day, now)) {
                    considered.add(settler.getId());
                    continue;
                }
                if (considered.add(settler.getId())) {
                    everyday(level, settler, now);
                }
            }
        }
    }

    /** First sight of a player today: a wave or a nod, and (rate limited) a line. */
    static boolean greet(ServerLevel level, SettlerEntity settler, ServerPlayer player, long day, long now) {
        // Guards and the other martial roles salute (GuardSaluteGoal) instead.
        if (settler.getProfession().martial()
            || settler.distanceToSqr(player) > GREET_RANGE * GREET_RANGE
            || !GREETED.shouldGreet(settler.getUUID(), player.getUUID(), day)
            || !settler.hasLineOfSight(player)) {
            return false;
        }
        GREETED.mark(settler.getUUID(), player.getUUID(), day);
        // Busy hands only nod; an idle or walking settler may raise a hand.
        AmbientCue cue = working(settler) || level.getRandom().nextBoolean()
            ? AmbientCue.NOD : AmbientCue.WAVE;
        String line = LIMITER.tryAcquire(settler.getUUID(), BarkContext.GREET, settler.getX(), settler.getZ(), now)
            ? pick(settler, BarkContext.GREET) : "";
        send(settler, cue, line, player.getGameProfile().getName());
        return true;
    }

    /** The rare everyday line: needs first, then weather, evening, work. */
    private static void everyday(ServerLevel level, SettlerEntity settler, long now) {
        RandomSource random = level.getRandom();
        BlockPos head = settler.blockPosition().above();
        boolean cold = level.getBiome(settler.blockPosition()).value().coldEnoughToSnow(settler.blockPosition());
        if (cold && random.nextInt(SHIVER_ODDS) == 0 && LIMITER.tryCue(settler.getUUID(), now)) {
            send(settler, AmbientCue.SHIVER, "", "");
        }
        if (random.nextInt(CONTEXT_ODDS) != 0) {
            return;
        }
        BarkContext context = contextFor(settler, level.isRaining() && level.isRainingAt(head), cold);
        if (context == null
            || !LIMITER.tryAcquire(settler.getUUID(), context, settler.getX(), settler.getZ(), now)) {
            return;
        }
        String key = context == BarkContext.JOB
            ? pickJob(settler) : pick(settler, context);
        AmbientCue cue = switch (context) {
            case TIRED -> AmbientCue.STRETCH_YAWN;
            case COLD -> AmbientCue.SHIVER;
            default -> AmbientCue.NONE;
        };
        send(settler, cue, key, "");
    }

    /** Pure over the synced facts, so GameTests can pin the priority order. */
    @Nullable
    public static BarkContext contextFor(SettlerEntity settler, boolean rainedOn, boolean cold) {
        if (settler.getHunger() < 30.0F) {
            return BarkContext.HUNGRY;
        }
        if (settler.getEnergy() < 20.0F) {
            return BarkContext.TIRED;
        }
        if (rainedOn) {
            return BarkContext.RAIN;
        }
        if (cold) {
            return BarkContext.COLD;
        }
        SettlerActivity activity = settler.getActivity();
        DayPhase phase = settler.dayPhase();
        if (phase.social() && (activity == SettlerActivity.IDLE || activity == SettlerActivity.SOCIALIZING)) {
            return BarkContext.EVENING;
        }
        if (activity.name().startsWith("WORK_")) {
            return BarkContext.JOB;
        }
        return null;
    }

    // ---- Village moments ----

    /** A won raid: the settlers near the Banner cheer, a few call out. */
    public static int onRaidHeld(ServerLevel level, Settlement settlement) {
        if (!enabled() || settlement == null || settlement.center == null) {
            return 0;
        }
        Vec3 banner = Vec3.atCenterOf(settlement.center);
        List<SettlerEntity> crowd = level.getEntitiesOfClass(SettlerEntity.class,
            new AABB(settlement.center).inflate(CHEER_RANGE, 8.0D, CHEER_RANGE),
            s -> s.isAlive() && settlement.id.equals(s.getSettlementId()) && !s.isTraveler()
                && !s.isSleeping() && s.getActivity() != SettlerActivity.SLEEPING
                && s.getActivity() != SettlerActivity.FLEEING);
        crowd.sort(Comparator.comparingDouble(s -> s.distanceToSqr(banner)));
        long now = level.getGameTime();
        int cheered = 0;
        int lines = 0;
        for (SettlerEntity settler : crowd) {
            if (cheered >= CHEER_CAP) {
                break;
            }
            String line = "";
            if (lines < CHEER_LINES
                && LIMITER.tryAcquireChorus(settler.getUUID(), BarkContext.RAID_WON, now)) {
                line = pick(settler, BarkContext.RAID_WON);
                lines++;
            }
            send(settler, AmbientCue.CHEER, line, "");
            cheered++;
        }
        // The particle burst above the Banner belongs to the FX lane (FxAtmosphere
        // RAID_WON); this moment is the people: cheers and a few lines.
        return cheered;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof SettlerEntity settler)
            || !(settler.level() instanceof ServerLevel level)
            || !enabled() || !settler.isBound() || settler.isTraveler()
            || settler.getSettlementId() == null) {
            return;
        }
        long now = level.getGameTime();
        synchronized (LivingVillage.class) {
            if (PENDING.size() < 32) {
                PENDING.add(new PendingMourn(settler.getSettlementId(), settler.getSettlerName(),
                    settler.position(), now, now + MOURN_DELAY));
            }
        }
    }

    /** A death is mourned once the danger has passed, briefly, by those nearby. */
    private static void processMourning(ServerLevel level, long now) {
        List<PendingMourn> due = new ArrayList<>();
        synchronized (LivingVillage.class) {
            if (PENDING.isEmpty()) {
                return;
            }
            Iterator<PendingMourn> it = PENDING.iterator();
            while (it.hasNext()) {
                PendingMourn pending = it.next();
                if (now - pending.since() > MOURN_GIVE_UP || now < pending.since()) {
                    it.remove();
                } else if (now >= pending.due()) {
                    it.remove();
                    due.add(pending);
                }
            }
        }
        for (PendingMourn pending : due) {
            Settlement settlement = SettlementSavedData.get(level).settlements.get(pending.settlementId());
            if (settlement == null) {
                continue;
            }
            if (LifeNeed.threatActive(settlement, now)) {
                synchronized (LivingVillage.class) {
                    PENDING.add(new PendingMourn(pending.settlementId(), pending.name(), pending.pos(),
                        pending.since(), now + MOURN_RETRY));
                }
                continue;
            }
            mourn(level, settlement, pending.name(), pending.pos(), now);
        }
    }

    /** Public for GameTests: bows the heads of those near {@code pos}; returns how many. */
    public static int mourn(ServerLevel level, Settlement settlement, String name, Vec3 pos, long now) {
        List<SettlerEntity> near = level.getEntitiesOfClass(SettlerEntity.class,
            new AABB(pos, pos).inflate(MOURN_RANGE, 6.0D, MOURN_RANGE),
            s -> s.isAlive() && settlement.id.equals(s.getSettlementId()) && !busy(s));
        near.sort(Comparator.comparingDouble(s -> s.distanceToSqr(pos)));
        int n = 0;
        for (SettlerEntity settler : near) {
            if (n >= MOURN_CAP) {
                break;
            }
            String line = n == 0 && LIMITER.tryAcquire(settler.getUUID(), BarkContext.MOURN,
                settler.getX(), settler.getZ(), now) ? pick(settler, BarkContext.MOURN) : "";
            send(settler, AmbientCue.MOURN, line, name == null ? "" : name);
            n++;
        }
        return n;
    }

    /** Morning: a sleepy line from some of the risers (the stretch already plays). */
    public static void onWake(SettlerEntity settler) {
        if (!(settler.level() instanceof ServerLevel level) || !enabled()
            || level.getRandom().nextInt(3) != 0) {
            return;
        }
        if (LIMITER.tryAcquire(settler.getUUID(), BarkContext.WAKE, settler.getX(), settler.getZ(),
                level.getGameTime())) {
            send(settler, AmbientCue.NONE, pick(settler, BarkContext.WAKE), "");
        }
    }

    /** Newcomers and finished buildings, noticed by watching the settlement's own counts. */
    private static void pollSettlements(ServerLevel level, long now) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (Settlement settlement : data.settlements.values()) {
            int population = settlement.population();
            int buildings = settlement.buildings.size();
            int[] previous;
            synchronized (LivingVillage.class) {
                previous = SEEN.put(settlement.id, new int[] {population, buildings});
            }
            if (previous == null) {
                continue;
            }
            if (population > previous[0] && !settlement.settlers.isEmpty()) {
                UUID newest = settlement.settlers.get(settlement.settlers.size() - 1).entityId;
                Entity entity = newest == null ? null : level.getEntity(newest);
                if (entity instanceof SettlerEntity newcomer) {
                    welcome(level, settlement, newcomer, now);
                }
            }
            if (buildings > previous[1]) {
                Building building = settlement.buildings.get(settlement.buildings.size() - 1);
                BlockPos at = building == null ? null
                    : building.plaquePos != null ? building.plaquePos : building.anchor;
                if (at != null) {
                    buildingDone(level, settlement, Vec3.atCenterOf(at), now);
                }
            }
        }
    }

    /** Public for GameTests: the nearest neighbour waves and welcomes a newcomer. */
    public static boolean welcome(ServerLevel level, Settlement settlement, SettlerEntity newcomer, long now) {
        if (!enabled()) {
            return false;
        }
        SettlerEntity host = nearest(level, settlement, newcomer.position(), newcomer);
        send(newcomer, AmbientCue.WAVE, "", "");
        if (host == null) {
            return false;
        }
        String line = LIMITER.tryAcquire(host.getUUID(), BarkContext.NEWCOMER, host.getX(), host.getZ(), now)
            ? pick(host, BarkContext.NEWCOMER) : "";
        send(host, AmbientCue.WAVE, line, newcomer.getSettlerName());
        return true;
    }

    /** Public for GameTests: someone near a newly finished building is pleased with it. */
    public static boolean buildingDone(ServerLevel level, Settlement settlement, Vec3 at, long now) {
        if (!enabled()) {
            return false;
        }
        SettlerEntity speaker = nearest(level, settlement, at, null);
        if (speaker == null || !LIMITER.tryAcquire(speaker.getUUID(), BarkContext.BUILDING_DONE,
                speaker.getX(), speaker.getZ(), now)) {
            return false;
        }
        send(speaker, AmbientCue.NOD, pick(speaker, BarkContext.BUILDING_DONE), "");
        return true;
    }

    @Nullable
    private static SettlerEntity nearest(ServerLevel level, Settlement settlement, Vec3 at,
                                         @Nullable SettlerEntity except) {
        List<SettlerEntity> near = level.getEntitiesOfClass(SettlerEntity.class,
            new AABB(at, at).inflate(MOMENT_RANGE, 6.0D, MOMENT_RANGE),
            s -> s != except && s.isAlive() && settlement.id.equals(s.getSettlementId())
                && !s.isTraveler() && !busy(s));
        return near.stream().min(Comparator.comparingDouble(s -> s.distanceToSqr(at))).orElse(null);
    }

    // ---- Helpers ----

    /** Never interrupt sleep, a fight, a flight or a conversation. */
    static boolean busy(SettlerEntity settler) {
        SettlerActivity activity = settler.getActivity();
        return settler.isSleeping() || activity == SettlerActivity.SLEEPING
            || activity == SettlerActivity.COMBAT || activity == SettlerActivity.FLEEING
            || activity == SettlerActivity.RETREATING || settler.hurtTime > 0
            || settler.getTarget() != null || ConversationService.isTalking(settler);
    }

    static boolean working(SettlerEntity settler) {
        SettlerActivity activity = settler.getActivity();
        return activity.name().startsWith("WORK_") || activity == SettlerActivity.CARRYING
            || activity == SettlerActivity.HAULING_LOG || activity == SettlerActivity.HAULING_CARCASS
            || activity == SettlerActivity.CARRY_MATERIALS || activity == SettlerActivity.SORTING
            || !settler.getMainHandItem().isEmpty();
    }

    private static String pick(SettlerEntity settler, BarkContext context) {
        return BarkPicker.key(context, seed(settler), nextCounter(settler, context.ordinal()));
    }

    private static String pickJob(SettlerEntity settler) {
        return BarkPicker.jobKey(settler.getProfession().name(), seed(settler),
            nextCounter(settler, BarkContext.JOB.ordinal()));
    }

    private static long seed(SettlerEntity settler) {
        return settler.getUUID().getLeastSignificantBits() ^ settler.getAppearanceSeed();
    }

    private static synchronized int nextCounter(SettlerEntity settler, int context) {
        UUID key = new UUID(settler.getUUID().getMostSignificantBits(),
            settler.getUUID().getLeastSignificantBits() ^ context);
        if (COUNTERS.size() > 4096) {
            COUNTERS.clear();
        }
        return COUNTERS.merge(key, 1, Integer::sum) - 1;
    }

    /** One display-only packet to the players tracking this settler. */
    static void send(SettlerEntity settler, AmbientCue cue, String barkKey, String arg) {
        if ((cue == null || cue == AmbientCue.NONE) && (barkKey == null || barkKey.isEmpty())) {
            return;
        }
        AmbientCue safeCue = cue == null ? AmbientCue.NONE : cue;
        synchronized (STATS) {
            int[] stats = STATS.computeIfAbsent(settler, s -> new int[] {0, 0, 0});
            if (barkKey != null && !barkKey.isEmpty()) {
                stats[0]++;
            }
            if (safeCue != AmbientCue.NONE) {
                stats[1]++;
                stats[2] = safeCue.ordinal();
            }
        }
        PayloadSend.toTracking(settler, new AmbientCuePayload(settler.getId(), safeCue.ordinal(), barkKey, arg));
    }

    // ---- GameTest seams ----

    public static int barksSaid(SettlerEntity settler) {
        synchronized (STATS) {
            int[] stats = STATS.get(settler);
            return stats == null ? 0 : stats[0];
        }
    }

    public static int cuesPlayed(SettlerEntity settler) {
        synchronized (STATS) {
            int[] stats = STATS.get(settler);
            return stats == null ? 0 : stats[1];
        }
    }

    public static AmbientCue lastCue(SettlerEntity settler) {
        synchronized (STATS) {
            int[] stats = STATS.get(settler);
            return stats == null ? AmbientCue.NONE : AmbientCue.byId(stats[2]);
        }
    }

    /** Runs the once-a-second pass for one player now (GameTests; no clock moves). */
    public static void passForTest(ServerLevel level, ServerPlayer player, long now) {
        aroundPlayers(level, now);
    }

    /** Asks for one everyday line immediately, ignoring the dice (GameTests). */
    public static boolean everydayLineForTest(ServerLevel level, SettlerEntity settler, long now) {
        BarkContext context = contextFor(settler, false, false);
        if (context == null || !LIMITER.tryAcquire(settler.getUUID(), context, settler.getX(), settler.getZ(), now)) {
            return false;
        }
        send(settler, AmbientCue.NONE, context == BarkContext.JOB ? pickJob(settler) : pick(settler, context), "");
        return true;
    }
}

package com.hearthstead.settlement.builder;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every construction site and every finished defensive work, per settlement.
 *
 * <p>Its own SavedData ("hearthstead_build_sites") rather than a field on
 * {@code Settlement}, so a Builder bug can never corrupt the settlement file
 * and the settlement schema version never moves for this lane. Finished and
 * cancelled jobs are pruned (only the last {@link #KEEP_FINISHED} stay for the
 * Sites list); finished DEFENSE works are kept as {@link DefenseWork} records.
 */
public final class BuildSiteSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int DATA_VERSION = 1;
    private static final String DATA_NAME = "hearthstead_build_sites";
    /** Active + recent jobs per settlement, a hard bound on the file. */
    public static final int MAX_JOBS = 32;
    public static final int KEEP_FINISHED = 6;
    public static final int MAX_DEFENSE_WORKS = 256;

    /**
     * A finished defensive work, for the raid/combat lanes: which blocks
     * belong to which kind of segment, and where its gates are. Read with
     * {@link #segmentAt} / {@link #gates}; the raid lane may scale breach
     * effort by kind and send raiders to gates or the weakest segment.
     */
    public record DefenseWork(UUID jobId, String segment, long[] blocks, List<BlockPos> gates) {
    }

    /**
     * Builder settings per settlement (MineColonies' builder options).
     * PICKUP: HYBRID = Couriers deliver, the Builder fetches himself only when
     * no Courier is employed; FETCH_MYSELF = he always walks to the warehouse;
     * DELIVERIES_ONLY = he never leaves the site for materials. FILL: what
     * goes under a foundation where the ground falls away.
     */
    public enum Pickup { HYBRID, FETCH_MYSELF, DELIVERIES_ONLY }

    public enum Fill { MATCH, DIRT, COBBLESTONE }

    public record Settings(Pickup pickup, Fill fill) {
        public static final Settings DEFAULT = new Settings(Pickup.HYBRID, Fill.MATCH);
    }

    private final Map<UUID, Settings> settings = new HashMap<>();

    public Settings settings(UUID settlementId) {
        return settings.getOrDefault(settlementId, Settings.DEFAULT);
    }

    public void setSettings(UUID settlementId, Settings next) {
        settings.put(settlementId, next);
        changed();
    }

    private final Map<UUID, List<BuildJob>> jobs = new HashMap<>();
    private final Map<UUID, List<DefenseWork>> defense = new HashMap<>();
    /** Monotone change counter for site snapshots (runtime only). */
    private int revision;

    private static final Factory<BuildSiteSavedData> FACTORY =
        new Factory<>(BuildSiteSavedData::new, BuildSiteSavedData::load, null);

    public static BuildSiteSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    public static BuildSiteSavedData existing(ServerLevel level) {
        return level == null ? null : level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    public static BuildSiteSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        BuildSiteSavedData data = new BuildSiteSavedData();
        if (tag == null || registries == null) {
            return data;
        }
        HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        ListTag settlements = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlements.size(); i++) {
            CompoundTag s = settlements.getCompound(i);
            if (!s.hasUUID("Id")) {
                continue;
            }
            UUID sid = s.getUUID("Id");
            List<BuildJob> list = new ArrayList<>();
            ListTag jobList = s.getList("Jobs", Tag.TAG_COMPOUND);
            for (int j = 0; j < jobList.size() && list.size() < MAX_SYSTEM_JOBS; j++) {
                try {
                    BuildJob job = BuildJob.load(jobList.getCompound(j), blocks);
                    if (job.settlementId.equals(sid)) {
                        list.add(job);
                    }
                } catch (RuntimeException malformed) {
                    LOGGER.warn("Builder: dropped a malformed build site in settlement {}: {}",
                        sid, malformed.toString());
                }
            }
            if (!list.isEmpty()) {
                data.jobs.put(sid, list);
            }
            List<DefenseWork> works = new ArrayList<>();
            ListTag workList = s.getList("Defense", Tag.TAG_COMPOUND);
            for (int j = 0; j < workList.size() && works.size() < MAX_DEFENSE_WORKS; j++) {
                CompoundTag w = workList.getCompound(j);
                if (!w.hasUUID("Job")) {
                    continue;
                }
                List<BlockPos> gates = new ArrayList<>();
                for (long g : w.getLongArray("Gates")) {
                    gates.add(BlockPos.of(g));
                }
                works.add(new DefenseWork(w.getUUID("Job"), w.getString("Segment"),
                    w.getLongArray("Blocks"), List.copyOf(gates)));
            }
            if (!works.isEmpty()) {
                data.defense.put(sid, works);
            }
            if (s.contains("PickupName")) {
                data.settings.put(sid, new Settings(
                    BuildJob.byName(Pickup.class, s.getString("PickupName"), Pickup.HYBRID),
                    BuildJob.byName(Fill.class, s.getString("FillName"), Fill.MATCH)));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        ListTag settlements = new ListTag();
        java.util.Set<UUID> ids = new java.util.HashSet<>(jobs.keySet());
        ids.addAll(defense.keySet());
        ids.addAll(settings.keySet());
        for (UUID sid : ids) {
            CompoundTag s = new CompoundTag();
            s.putUUID("Id", sid);
            ListTag jobList = new ListTag();
            for (BuildJob job : jobs.getOrDefault(sid, List.of())) {
                jobList.add(job.save());
            }
            s.put("Jobs", jobList);
            ListTag workList = new ListTag();
            for (DefenseWork work : defense.getOrDefault(sid, List.of())) {
                CompoundTag w = new CompoundTag();
                w.putUUID("Job", work.jobId());
                w.putString("Segment", work.segment());
                w.put("Blocks", new LongArrayTag(work.blocks()));
                long[] gates = new long[work.gates().size()];
                for (int i = 0; i < gates.length; i++) {
                    gates[i] = work.gates().get(i).asLong();
                }
                w.put("Gates", new LongArrayTag(gates));
                workList.add(w);
            }
            s.put("Defense", workList);
            Settings set = settings.get(sid);
            if (set != null) {
                s.putString("PickupName", set.pickup().name());
                s.putString("FillName", set.fill().name());
            }
            settlements.add(s);
        }
        tag.put("Settlements", settlements);
        return tag;
    }

    // ------------------------------------------------------------- jobs ---

    /** Every job of every settlement (bounded by MAX_SYSTEM_JOBS per settlement). */
    public List<BuildJob> allJobs() {
        List<BuildJob> out = new ArrayList<>();
        for (List<BuildJob> list : jobs.values()) {
            out.addAll(list);
        }
        return out;
    }

    /** Live list (queue order) of a settlement's jobs. Mutate through the service. */
    public List<BuildJob> jobs(UUID settlementId) {
        return jobs.getOrDefault(settlementId, List.of());
    }

    @Nullable
    public BuildJob job(UUID settlementId, UUID jobId) {
        for (BuildJob job : jobs(settlementId)) {
            if (job.id.equals(jobId)) {
                return job;
            }
        }
        return null;
    }

    public List<BuildJob> activeJobs(UUID settlementId) {
        List<BuildJob> out = new ArrayList<>();
        for (BuildJob job : jobs(settlementId)) {
            if (job.state == BuildJob.State.ACTIVE) {
                out.add(job);
            }
        }
        out.sort(QUEUE_ORDER);
        return out;
    }

    /** Rush first, then queue order, then age. */
    public static final Comparator<BuildJob> QUEUE_ORDER = Comparator
        .comparing((BuildJob j) -> !j.rush)
        .thenComparingInt(j -> j.order)
        .thenComparingLong(j -> j.createdAt);

    /** Adds a job at the back of the queue. False when the settlement is at its cap. */
    public boolean add(BuildJob job) {
        List<BuildJob> list = jobs.computeIfAbsent(job.settlementId, id -> new ArrayList<>());
        prune(list);
        if (list.size() >= MAX_JOBS) {
            return false;
        }
        int maxOrder = 0;
        for (BuildJob other : list) {
            maxOrder = Math.max(maxOrder, other.order);
        }
        job.order = maxOrder + 1;
        list.add(job);
        changed();
        return true;
    }

    /** Hard bound for system jobs (scaffold cleanups) past the player cap. */
    public static final int MAX_SYSTEM_JOBS = MAX_JOBS + 32;

    /**
     * Admits system work (a scaffold take-down) even when the player queue
     * is at {@link #MAX_JOBS}; still hard-bounded so the file cannot grow
     * without limit.
     */
    public boolean addSystem(BuildJob job) {
        List<BuildJob> list = jobs.computeIfAbsent(job.settlementId, id -> new ArrayList<>());
        prune(list);
        if (list.size() >= MAX_SYSTEM_JOBS) {
            return false;
        }
        job.order = Integer.MIN_VALUE / 2 + list.size(); // ahead of the queue
        list.add(job);
        changed();
        return true;
    }

    /** Drops the oldest finished jobs beyond {@link #KEEP_FINISHED}. */
    private static void prune(List<BuildJob> list) {
        List<BuildJob> finished = new ArrayList<>();
        for (BuildJob job : list) {
            // A stopped job still owning ladders is never pruned before its
            // cleanup is queued (Codex T3b).
            if (job.state != BuildJob.State.ACTIVE && job.scaffold.isEmpty()) {
                finished.add(job);
            }
        }
        finished.sort(Comparator.comparingLong(j -> j.createdAt));
        for (int i = 0; i < finished.size() - KEEP_FINISHED; i++) {
            list.remove(finished.get(i));
        }
    }

    public void changed() {
        revision++;
        setDirty();
    }

    public int revision() {
        return revision;
    }

    // ---------------------------------------------------------- defense ---

    public void recordDefense(UUID settlementId, DefenseWork work) {
        List<DefenseWork> list = defense.computeIfAbsent(settlementId, id -> new ArrayList<>());
        list.removeIf(existing -> existing.jobId().equals(work.jobId()));
        list.add(work);
        while (list.size() > MAX_DEFENSE_WORKS) {
            list.remove(0);
        }
        changed();
    }

    public void forgetDefense(UUID settlementId, UUID jobId) {
        List<DefenseWork> list = defense.get(settlementId);
        if (list != null && list.removeIf(existing -> existing.jobId().equals(jobId))) {
            changed();
        }
    }

    public List<DefenseWork> defenseWorks(UUID settlementId) {
        return defense.getOrDefault(settlementId, List.of());
    }

    /** The segment kind of a defensive block, or null (raid-lane read API). */
    @Nullable
    public static String segmentAt(ServerLevel level, UUID settlementId, BlockPos pos) {
        BuildSiteSavedData data = existing(level);
        if (data == null) {
            return null;
        }
        long packed = pos.asLong();
        for (DefenseWork work : data.defenseWorks(settlementId)) {
            for (long b : work.blocks()) {
                if (b == packed) {
                    return work.segment();
                }
            }
        }
        return null;
    }

    /** Every gate of a settlement's finished defense lines (raid-lane read API). */
    public static List<BlockPos> gates(ServerLevel level, UUID settlementId) {
        BuildSiteSavedData data = existing(level);
        if (data == null) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (DefenseWork work : data.defenseWorks(settlementId)) {
            out.addAll(work.gates());
        }
        return out;
    }
}

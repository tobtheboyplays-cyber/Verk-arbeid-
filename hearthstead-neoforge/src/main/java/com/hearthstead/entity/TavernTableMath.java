package com.hearthstead.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared table geometry and timing for the tavern patron clips (tavern lane). Pure maths - no
 * level, no client classes - so JUnit proves it.
 *
 * <p><b>A loose conductor, not a metronome</b> (owner, 26 Sep: "fully synced must NOT happen").
 * The per-table clock only decides WHEN something may happen (a cheer, a story, a toast). Every
 * patron's execution is individual and deterministic from (table, cycle, seat index), so all
 * clients agree without any network traffic:
 * <ul>
 *   <li><b>Cheer</b> ({@link #planCheer}): one initiator raises first; each other patron joins
 *       with {@link #JOIN_CHANCE} (the rest keep sipping) 0.2-0.8 s later on distinct slots, at
 *       their own speed (0.9-1.1). Joined mugs clink PAIRWISE in one or two staggered clinks
 *       (an odd third taps the first pair late); each pair meets at its own point - the midpoint
 *       of the two uncorrected mugs, nudged a few px - with the mugs touching, not merged. An early
 *       raiser holds the mug up until the partner arrives. Three clip styles (plain, a mouth wipe
 *       after, a quick sip) are picked per patron.</li>
 *   <li><b>Story</b>: listeners react on the teller's beats with their own lag (0.1-0.6 s on
 *       distinct slots) and their own style (big laugh + slap, smile + head shake, chuckle).</li>
 *   <li><b>Toast</b>: the toaster calls it; ~70% answer 0.2-0.8 s later, the rest sip.</li>
 *   <li><b>Posture</b>: every seat gets a relaxed body angle and some lean back.</li>
 * </ul>
 *
 * <p>TABLE_CHEER's contact frame puts the ale mug's body {@link #CLINK_LEFT} / {@link #CLINK_UP}
 * / {@link #CLINK_FORWARD} px from the seated hip (the Blender script records it in the clip meta,
 * "clink_mug_hip_px"); {@link #correction} turns and leans the torso so the mug reaches a target.
 */
public final class TavernTableMath {
    /** TABLE_CHEER contact: mug body, hip-relative px (x = the settler's left, up, forward). */
    public static final double CLINK_LEFT = -0.6, CLINK_UP = 16.2, CLINK_FORWARD = 12.0;
    public static final float CLINK_SECONDS = 1.0F, CHEER_CLIP_SECONDS = 3.0F, RAISE_HOLD = 0.8F;
    /** The seated hip above the rider origin (model torso pivot, 12 px). */
    public static final double HIP = .75;
    public static final double JOIN_CHANCE = 0.7;
    /** Two touching mugs: each body sits this far from the pair point, toward its drinker. */
    public static final double MUG_TOUCH = 1.3 / 16.0;
    /** Start/lag slots (seconds): distinct by at least 0.1 s after jitter. */
    private static final float[] JOIN_SLOTS = {0.2F, 0.4F, 0.6F, 0.8F};
    private static final float[] LAG_SLOTS = {0.1F, 0.25F, 0.4F, 0.55F};

    /** One seated patron: hip (world), body yaw (degrees, Minecraft), and the table cell it faces. */
    public record Seat(Vec3 hip, float yaw, BlockPos table) {}

    /** Torso correction: yaw and extra lean (radians; yaw + = toward the settler's right), lift px up. */
    public record Correction(float yaw, float pitch, float lift) {
        public static final Correction NONE = new Correction(0, 0, 0);
    }

    /**
     * One cheer, planned identically on every client. Arrays are per seat index; seconds count
     * from the cheer's start. {@code group} = clink pair (0 / 1), -1 = not joining (sips);
     * {@code late} = the odd third tapping pair 0 after it clinked.
     */
    public record Cheer(int initiator, boolean[] joined, float[] start, float[] speed, int[] group,
                        boolean[] late, float[] contact, int[] style) {
        public int count() { return joined.length; }
    }

    private TavernTableMath() {
    }

    // ------------------------------------------------------------------ hashing / clock

    /** Deterministic [0,1) from a seed and salts (SplitMix64). */
    public static double hash01(long seed, long a, long b) {
        long z = seed + a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    public static long seed(BlockPos table) {
        return table.asLong() * 0x9E3779B97F4A7C15L + 0x5DEECE66DL;
    }

    public static Vec3 forward(float yawDegrees) {
        double a = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(a), 0, Math.cos(a));
    }

    public static Vec3 left(float yawDegrees) {
        double a = Math.toRadians(yawDegrees);
        return new Vec3(Math.cos(a), 0, Math.sin(a));
    }

    // ------------------------------------------------------------------ rhythm (owner: natural gaps)

    /**
     * Table events. Time is cut into {@link #EVENT_WINDOW} s windows per table; a window holds at
     * most one big event (cheer / story / toast, weighted so toasts are rarest) starting 10-30 s
     * into it - so two big events start at least {@link #EVENT_WINDOW} - 20 s apart, and some
     * windows stay quiet altogether.
     */
    public enum Event { NONE, CHEER, STORY, TOAST }
    public static final double EVENT_WINDOW = 70.0, CHEER_SECONDS = 5.5, STORY_SECONDS = 8.0, TOAST_SECONDS = 8.0;
    public static final double EVENT_CHANCE = 0.85;

    /** The event active at {@code seconds} (NONE when quiet), its start, and its index (plan seed). */
    public record TableEvent(Event event, double start, double length, long index) {
        public static final TableEvent QUIET = new TableEvent(Event.NONE, 0, 0, -1);
        public double local(double seconds) { return seconds - start; }
    }

    public static TableEvent tableEvent(BlockPos table, double seconds) {
        long seed = seed(table);
        double off = hash01(seed, 5, 5) * EVENT_WINDOW;
        long k = (long) Math.floor((seconds + off) / EVENT_WINDOW);
        if (hash01(seed, k, 21) >= EVENT_CHANCE) return TableEvent.QUIET;
        double roll = hash01(seed, k, 22);
        Event e = roll < 0.35 ? Event.CHEER : roll < 0.8 ? Event.STORY : Event.TOAST;
        double len = e == Event.CHEER ? CHEER_SECONDS : e == Event.STORY ? STORY_SECONDS : TOAST_SECONDS;
        double start = k * EVENT_WINDOW - off + 10 + 20 * hash01(seed, k, 23);
        if (seconds < start || seconds >= start + len) return TableEvent.QUIET;
        return new TableEvent(e, start, len, k);
    }

    /**
     * A patron's own beats between table events: mostly CALM (breathing, settling, looking round,
     * sitting still for 3-10 s), now and then a SIP, occasionally a small fidget (about one every
     * 15-30 s). Windows of {@link #BEAT_WINDOW} s per person, desynced by the person's seed.
     */
    public enum Beat { CALM, SIP, FIDGET_CHIN, FIDGET_STRETCH, FIDGET_SHIFT }
    public static final double BEAT_WINDOW = 60.0;
    public static final double SIP_SECONDS = 3.0, CHIN_SECONDS = 3.0, STRETCH_SECONDS = 3.4, SHIFT_SECONDS = 2.4;
    public static final double SIP_CHANCE = 0.33, FIDGET_CHANCE = 0.30;

    public record Act(Beat beat, double start, double length) {
        public double local(double seconds) { return seconds - start; }
    }

    public static double beatLength(Beat b) {
        return switch (b) {
            case SIP -> SIP_SECONDS;
            case FIDGET_CHIN -> CHIN_SECONDS;
            case FIDGET_STRETCH -> STRETCH_SECONDS;
            case FIDGET_SHIFT -> SHIFT_SECONDS;
            default -> 0;
        };
    }

    public static Act personal(long entitySeed, double seconds) {
        double off = hash01(entitySeed, 7, 7) * BEAT_WINDOW;
        double s = seconds + off;
        long w = (long) Math.floor(s / BEAT_WINDOW);
        double t = w * BEAT_WINDOW;
        double end = t + BEAT_WINDOW;
        int n = 0;
        while (t < end) {
            double calm = 3 + 7 * hash01(entitySeed, w * 64 + n, 31);
            if (s < t + calm) return new Act(Beat.CALM, t - off, calm);
            t += calm;
            double r = hash01(entitySeed, w * 64 + n, 32);
            Beat b = r < SIP_CHANCE ? Beat.SIP : r < SIP_CHANCE + FIDGET_CHANCE
                ? Beat.values()[2 + (int) (hash01(entitySeed, w * 64 + n, 33) * 3)] : Beat.CALM;
            double len = beatLength(b);
            if (b != Beat.CALM && t + len <= end - 0.5) {
                if (s < t + len) return new Act(b, t - off, len);
                t += len;
            }
            n++;
        }
        return new Act(Beat.CALM, t - off, BEAT_WINDOW);
    }

    /** Whether patron {@code index} takes part in a table event (a story holds everyone). */
    public static boolean inEvent(TableEvent e, BlockPos table, int index, int count) {
        if (e.event() == Event.NONE || count < 2) return false;
        if (e.event() == Event.STORY) return true;
        if (e.event() == Event.CHEER) return planCheer(table, e.index(), count).joined()[index];
        return index == teller(e.index(), 0, count) || toastAnswer(table, e.index(), index, count) >= 0F;
    }

    public static int teller(long cycle, int segment, int count) {
        return (int) Math.floorMod(cycle + segment, (long) count);
    }

    // ------------------------------------------------------------------ per-patron variety

    /** Distinct-slot lag for patron {@code index} among {@code count}: slots permuted by the seed. */
    private static float slot(float[] slots, long seed, long salt, int index, int count) {
        int n = Math.min(slots.length, Math.max(1, count));
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = (int) (hash01(seed, salt, i) * (i + 1));
            int t = order[i]; order[i] = order[j]; order[j] = t;
        }
        float jitter = (float) (hash01(seed, salt + 7, index) - 0.5) * 0.04F;
        return slots[order[Math.floorMod(index, n)]] + jitter;
    }

    /** Story reaction lag (s) of a listener: 0.1-0.6 s, no two listeners within 0.1 s. */
    public static float reactionLag(BlockPos table, long cycle, int segment, int index, int count) {
        return slot(LAG_SLOTS, seed(table), cycle * 31 + segment * 7 + 1, index, count);
    }

    /** Listener style this segment: 0 big laugh + slap, 1 smile + head shake, 2 chuckle + sip. */
    public static int listenStyle(BlockPos table, long cycle, int segment, int index) {
        return (int) (hash01(seed(table), cycle * 131 + segment, index * 17L + 3) * 3);
    }

    /** Toast: does this patron answer, and how late (s)? Returns -1 for "keeps sipping". */
    public static float toastAnswer(BlockPos table, long cycle, int index, int count) {
        if (hash01(seed(table), cycle * 977 + 5, index) >= JOIN_CHANCE) return -1F;
        return slot(JOIN_SLOTS, seed(table), cycle * 977 + 11, index, count);
    }

    /** A relaxed seated posture: torso yaw offset (rad, +- 4..14 deg) and lean (rad, some lean back). */
    public static float[] posture(long entitySeed) {
        double a = hash01(entitySeed, 71, 1), b = hash01(entitySeed, 71, 2), c = hash01(entitySeed, 71, 3);
        float yaw = (float) Math.toRadians((4 + 10 * a) * (b < 0.5 ? -1 : 1));
        float lean = (float) Math.toRadians(c < 0.45 ? -(2 + 6 * hash01(entitySeed, 71, 4)) : 1.5 * c);
        return new float[] {yaw, lean};
    }

    /** Per-patron playback speed 0.9-1.1 and phase (s) for the loose loops. */
    public static float speed(long entitySeed) {
        return (float) (0.9 + 0.2 * hash01(entitySeed, 73, 1));
    }

    public static float phaseOffset(long entitySeed, float length) {
        return (float) (hash01(entitySeed, 73, 2) * length);
    }

    // ------------------------------------------------------------------ the cheer

    public static Cheer planCheer(BlockPos table, long cycle, int count) {
        return planCheer(table, cycle, count, null, null);
    }

    /**
     * {@code yaws} (seat body yaws, degrees) and {@code cells} (each seat's table cell,
     * BlockPos.asLong), may be null: the initiator clinks with the joined patron sitting most
     * OPPOSITE, preferring the same table cell (across the table, their mugs naturally meet over
     * that cell); the remaining two pair up, an odd third taps the first pair late.
     */
    public static Cheer planCheer(BlockPos table, long cycle, int count, float[] yaws, long[] cells) {
        long seed = seed(table);
        int init = (int) Math.floorMod(cycle, (long) Math.max(1, count));
        boolean[] joined = new boolean[count];
        float[] start = new float[count], speed = new float[count], contact = new float[count];
        int[] group = new int[count], style = new int[count];
        boolean[] late = new boolean[count];
        java.util.Arrays.fill(group, -1);
        joined[init] = true;
        int joiners = 0;
        for (int i = 0; i < count; i++) {
            speed[i] = (float) (0.9 + 0.2 * hash01(seed, cycle * 37 + 1, i));
            style[i] = (int) (hash01(seed, cycle * 37 + 2, i) * 3);
            if (i == init) continue;
            joined[i] = hash01(seed, cycle * 37 + 3, i) < JOIN_CHANCE;
            if (joined[i]) joiners++;
        }
        if (joiners == 0 && count > 1) { joined[(init + 1) % count] = true; joiners = 1; }
        // raise order: the initiator at 0, joiners on distinct 0.2-0.8 s slots
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!joined[i]) continue;
            start[i] = i == init ? 0F : slot(JOIN_SLOTS, seed, cycle * 37 + 4, i, count);
            order.add(i);
        }
        order.sort((a, b) -> Float.compare(start[a], start[b]));
        float[] natural = new float[count];
        for (int i : order) natural[i] = start[i] + CLINK_SECONDS / speed[i];
        // pair 0: the initiator and the patron across from it; pair 1: the next two; an odd
        // third taps pair 0 late
        if (yaws != null && order.size() > 2) {
            int best = 1;
            double most = -1;
            for (int k = 1; k < order.size(); k++) {
                double diff = Math.abs(wrap(Math.toRadians(yaws[order.get(k)] - yaws[order.get(0)])))
                    + (cells != null && cells[order.get(k)] == cells[order.get(0)] ? 1.0 : 0.0);
                if (diff > most + 1e-3) { most = diff; best = k; }
            }
            order.add(1, order.remove(best));
            if (order.size() == 4) {
                // the other two are then across from each other as well
                List<Integer> rest = new ArrayList<>(order.subList(2, 4));
                rest.sort((x, y) -> Float.compare(start[x], start[y]));
                order.set(2, rest.get(0)); order.set(3, rest.get(1));
            }
        }
        int m0 = order.get(0), m1 = order.size() > 1 ? order.get(1) : -1;
        float t0 = m1 < 0 ? natural[m0] : Math.max(natural[m0], natural[m1]);
        group[m0] = 0; contact[m0] = t0;
        if (m1 >= 0) { group[m1] = 0; contact[m1] = t0; }
        if (order.size() == 3) {
            int m2 = order.get(2);
            group[m2] = 0; late[m2] = true;
            contact[m2] = Math.max(natural[m2], t0 + 0.35F + 0.25F * (float) hash01(seed, cycle * 37 + 5, m2));
        } else if (order.size() >= 4) {
            int m2 = order.get(2), m3 = order.get(3);
            float t1 = Math.max(Math.max(natural[m2], natural[m3]),
                t0 + 0.3F + 0.4F * (float) hash01(seed, cycle * 37 + 6, 0));
            group[m2] = 1; group[m3] = 1; contact[m2] = t1; contact[m3] = t1;
        }
        return new Cheer(init, joined, start, speed, group, late, contact, style);
    }

    /** TABLE_CHEER clip seconds for patron i, {@code t} s after the cheer start (raise, hold, clink). */
    public static float cheerLocal(Cheer c, int i, float t) {
        float s = c.speed()[i];
        float u = (t - c.start()[i]) * s;
        if (u <= RAISE_HOLD) return Math.max(0F, u);
        float approach = c.contact()[i] - (CLINK_SECONDS - RAISE_HOLD) / s;
        if (t < approach) return RAISE_HOLD;
        return Math.min(CHEER_CLIP_SECONDS, RAISE_HOLD + (t - approach) * s);
    }

    /** The uncorrected TABLE_CHEER mug body at contact, in the world. */
    public static Vec3 clinkMug(Seat seat) {
        return mugWorld(seat, Correction.NONE);
    }

    /** Where a pair's mugs meet: midpoint of the members' natural mugs, nudged up to ~3 px. */
    public static Vec3 pairPoint(List<Seat> seats, Cheer c, int group, BlockPos table, long cycle) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (int i = 0; i < seats.size(); i++) {
            if (c.group()[i] != group || c.late()[i]) continue;
            Vec3 m = clinkMug(seats.get(i));
            x += m.x; y += m.y; z += m.z; n++;
        }
        if (n == 0) return null;
        long seed = seed(table);
        double r = (0.5 + 1.5 * hash01(seed, cycle * 41 + group, 2)) / 16.0
            * (hash01(seed, cycle * 41 + group, 1) < 0.5 ? -1 : 1);
        double dy = (hash01(seed, cycle * 41 + group, 3) - 0.5) * 2.0 / 16.0;
        double px = x / n, pz = z / n;
        // both members reach equally far (so they lean alike and meet at one height): put the
        // point on the perpendicular bisector of their hips, nudged along it
        Seat a = null, b = null;
        for (int i = 0; i < seats.size(); i++) {
            if (c.group()[i] != group || c.late()[i]) continue;
            if (a == null) a = seats.get(i); else if (b == null) b = seats.get(i);
        }
        if (a != null && b != null) {
            double mx = (a.hip().x + b.hip().x) / 2, mz = (a.hip().z + b.hip().z) / 2;
            double ex = b.hip().x - a.hip().x, ez = b.hip().z - a.hip().z, el = Math.hypot(ex, ez);
            if (el > 1e-6) {
                double ax = -ez / el, az = ex / el;
                double along = (px - mx) * ax + (pz - mz) * az + r;
                px = mx + ax * along;
                pz = mz + az * along;
            }
        }
        // height: where the members' mugs actually are once they lean in to reach it (so no one
        // has to rise off the seat), plus a small nudge
        double hy = 0;
        for (int i = 0; i < seats.size(); i++) {
            if (c.group()[i] != group || c.late()[i]) continue;
            Seat s = seats.get(i);
            Vec3 d = new Vec3(s.hip().x - px, 0, s.hip().z - pz);
            double len = d.length();
            Vec3 dir = len < 1e-6 ? forward(s.yaw()).scale(-1) : d.scale(1 / len);
            Correction lean = correction(s, new Vec3(px, clinkMug(s).y, pz).add(dir.scale(MUG_TOUCH)));
            hy += mugWorld(s, new Correction(lean.yaw(), lean.pitch(), 0)).y;
        }
        return new Vec3(px, hy / n + dy, pz);
    }

    /** Patron i's mug target at its clink: beside the pair point, on its own side (mugs touch). */
    public static Vec3 mugTarget(List<Seat> seats, Cheer c, int i, BlockPos table, long cycle) {
        int g = c.group()[i];
        if (g < 0) return null;
        Vec3 p = pairPoint(seats, c, g, table, cycle);
        if (c.late()[i]) {
            // the odd third raises TOWARD the clinked pair from its own place (a 4 px lean in):
            // across a two-block table the pair's point may be out of any seated reach
            Vec3 mine = clinkMug(seats.get(i));
            Vec3 d = new Vec3(p.x - mine.x, 0, p.z - mine.z);
            double len = d.length();
            if (len < 1e-6) return mine;
            Vec3 reach = mine.add(d.scale(Math.min(len, 4.0 / 16.0) / len));
            Correction lean = correction(seats.get(i), reach);
            return new Vec3(reach.x, mugWorld(seats.get(i), new Correction(lean.yaw(), lean.pitch(), 0)).y, reach.z);
        }
        Vec3 own = seats.get(i).hip();
        Vec3 d = new Vec3(own.x - p.x, 0, own.z - p.z);
        double len = d.length();
        Vec3 dir = len < 1e-6 ? forward(seats.get(i).yaw()).scale(-1) : d.scale(1 / len);
        return p.add(dir.scale(MUG_TOUCH));
    }

    /** Mug body at contact after a torso correction about the hip (exact rotations). */
    public static Vec3 mugWorld(Seat seat, Correction c) {
        // model space: +x left, +y down, -z forward (px)
        double x = CLINK_LEFT, y = -CLINK_UP, z = -CLINK_FORWARD;
        double cp = Math.cos(c.pitch()), sp = Math.sin(c.pitch());
        double y1 = cp * y - sp * z, z1 = sp * y + cp * z;
        double cy = Math.cos(c.yaw()), sy = Math.sin(c.yaw());
        double x2 = cy * x + sy * z1, z2 = -sy * x + cy * z1;
        return seat.hip().add(left(seat.yaw()).scale(x2 / 16.0)).add(0, (c.lift() - y1) / 16.0, 0)
            .add(forward(seat.yaw()).scale(-z2 / 16.0));
    }

    /** Torso yaw + lean (+ lift) that bring this seat's contact mug onto {@code target}. */
    public static Correction correction(Seat seat, Vec3 target) {
        float yaw = 0, pitch = 0;
        for (int i = 0; i < 8; i++) {
            Vec3 mug = mugWorld(seat, new Correction(yaw, pitch, 0));
            Vec3 d = target.subtract(seat.hip());
            Vec3 m = mug.subtract(seat.hip());
            double want = heading(seat, d), have = heading(seat, m);
            yaw += (float) wrap(want - have);
            double reachWant = Math.hypot(d.x, d.z), reachHave = Math.hypot(m.x, m.z);
            pitch += (float) ((reachWant - reachHave) / (CLINK_UP / 16.0));
            yaw = clamp(yaw, -0.9F, 0.9F);
            pitch = clamp(pitch, -0.35F, 0.6F);
        }
        Vec3 mug = mugWorld(seat, new Correction(yaw, pitch, 0));
        float lift = clamp((float) ((target.y - mug.y) * 16.0), -3.0F, 3.0F);
        return new Correction(yaw, pitch, lift);
    }

    private static double heading(Seat seat, Vec3 v) {
        double l = v.dot(left(seat.yaw())), f = v.dot(forward(seat.yaw()));
        return Math.atan2(-l, f);
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

package com.hearthstead.client.model;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.entity.TavernTableMath;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-only choice of the tavern lane's clips (never changes AI, activity or items).
 *
 * <ul>
 *   <li>Seated patrons (settled TavernSeatEntity, no real service projection, not eating, not the
 *       bard) follow their TABLE's shared clock ({@link TavernTableMath}): the cheer with mugs
 *       meeting over the table, a teller + listeners laughing on the punchline, a toast; alone
 *       at a table: {@code SEATED_DRINK}; closing time or spent: {@code SLEEPY_NOD}.</li>
 *   <li>An idle innkeeper facing an ale tap one block up: {@code ALE_POUR}; facing a counter-high
 *       solid block: {@code COUNTER_WIPE}.</li>
 *   <li>A host carrying real service cargo: {@code SERVE_CARRY} over the walk (arms stay on the
 *       cargo IK).</li>
 *   <li>Standing idle residents near a playing bard in the evening: {@code DANCE_JIG}.</li>
 * </ul>
 * Pair talk (SocialPair) plays {@link #PAIR_SPEAKER}/{@link #PAIR_LISTENER}; the animation lane
 * may point these at its TALK_GESTURE / LISTEN_IDLE (lengths must divide 4.8 s).
 */
public final class TavernPatronMotion {
    public static AnimationDefinition PAIR_SPEAKER = SettlerAnimations.VILLAGE_CHAT;
    public static AnimationDefinition PAIR_LISTENER = SettlerAnimations.VILLAGE_LISTEN;

    /** Which parts the chosen clip owns (they are reset before sampling). */
    public enum Owns { UPPER, FULL, OVERLAY }

    /**
     * A chosen clip, its clock, the parts it owns, and procedural additions applied after it:
     * torso yaw/pitch (radians, the clink reach) and head yaw (radians, look at a table-mate).
     */
    public record Pick(AnimationDefinition def, long millis, Owns owns, float torsoYaw, float torsoPitch,
                       float torsoLift, float headYaw, float headWeight,
                       AnimationDefinition base, long baseMillis, float weight) {
        /** A single clip at full weight (no calm base under it). */
        public Pick(AnimationDefinition def, long millis, Owns owns, float torsoYaw, float torsoPitch,
                    float torsoLift, float headYaw, float headWeight) {
            this(def, millis, owns, torsoYaw, torsoPitch, torsoLift, headYaw, headWeight, null, 0L, 1F);
        }
    }

    private static final class TableCache {
        long tick = Long.MIN_VALUE;
        List<TavernSeatEntity> seats = List.of();
    }

    private static final Map<Long, TableCache> TABLES = new java.util.HashMap<>();
    private static final Map<SettlerEntity, long[]> BARD_NEAR = new WeakHashMap<>();

    private TavernPatronMotion() {
    }

    /** The tavern clip for this settler this frame, or null for the ordinary presentation. */
    public static Pick select(SettlerEntity e, SettlerActivity activity, float ageInTicks) {
        if (!e.isAlive() || e.isSleeping() || e.blessingReceiveState.isStarted()) return null;
        float partial = Mth.clamp(ageInTicks - e.tickCount, 0F, 1F);
        if (e.getVehicle() instanceof TavernSeatEntity seat) return seated(e, seat, activity, ageInTicks, partial);
        if (e.isPassenger()) return null;
        if (e.getProfession() == Profession.INNKEEPER) return innkeeper(e, activity, ageInTicks);
        return dance(e, activity, ageInTicks);
    }

    // ------------------------------------------------------------------ seated

    private static final AnimationDefinition[] CHEER_STYLES = {
        TavernMotionAnimations.TABLE_CHEER, TavernMotionAnimations.TABLE_CHEER_WIPE, TavernMotionAnimations.TABLE_CHEER_QUICK};
    private static final AnimationDefinition[] LISTEN_STYLES = {
        TavernMotionAnimations.SEATED_LISTEN, TavernMotionAnimations.SEATED_LISTEN_SMILE,
        TavernMotionAnimations.SEATED_LISTEN_CHUCKLE};

    private static final class CheerCache {
        long cycle = Long.MIN_VALUE;
        int count;
        TavernTableMath.Cheer plan;
    }

    private static final Map<Long, CheerCache> CHEERS = new java.util.HashMap<>();

    private static long entitySeed(SettlerEntity e) {
        return e.getUUID().getLeastSignificantBits() ^ e.getUUID().getMostSignificantBits();
    }

    /** A loose loop on this patron's own phase and speed (never in step with a neighbour). */
    private static long ownClock(SettlerEntity e, float age, float length) {
        long seed = entitySeed(e);
        float t = age / 20F * TavernTableMath.speed(seed) + TavernTableMath.phaseOffset(seed, length);
        return (long) (t * 1000F);
    }

    /** The act's weight over the calm base: eased in and out over 0.45 s. */
    private static float actWeight(double local, double length) {
        return smooth((float) (local / 0.45)) * smooth((float) ((length - local) / 0.45));
    }

    private static AnimationDefinition beatClip(TavernTableMath.Beat b, boolean lefty) {
        return switch (b) {
            case SIP -> lefty ? TavernMotionAnimations.SEATED_SIP_LEFT : TavernMotionAnimations.SEATED_SIP;
            case FIDGET_CHIN -> TavernMotionAnimations.SEATED_FIDGET_CHIN;
            case FIDGET_STRETCH -> TavernMotionAnimations.SEATED_FIDGET_STRETCH;
            case FIDGET_SHIFT -> TavernMotionAnimations.SEATED_FIDGET_SHIFT;
            default -> null;
        };
    }

    /**
     * Owner (26 Sep): natural gaps. Most of the time a patron is CALM (SEATED_IDLE on the
     * person's own phase and speed: breathing, settling, looking round the room or at the bard);
     * a sip now and then, a small fidget every 15-30 s, and a table event only every 50-90 s.
     */
    private static Pick own(SettlerEntity e, TavernSeatEntity seat, float age, float[] posture) {
        long seed = entitySeed(e);
        double sec = age / 20.0;
        long calm = ownClock(e, age, 12F);
        float look = bardLook(e, seat);
        TavernTableMath.Act act = TavernTableMath.personal(seed, sec);
        if (act.beat() == TavernTableMath.Beat.CALM) {
            return new Pick(TavernMotionAnimations.SEATED_IDLE, calm, Owns.UPPER, posture[0], posture[1], 0,
                look, look == 0F ? 0F : 0.7F);
        }
        boolean lefty = TavernTableMath.hash01(seed, 79, 1) < 0.3;
        double local = act.local(sec);
        return new Pick(beatClip(act.beat(), lefty), (long) (local * 1000.0), Owns.UPPER, posture[0], posture[1], 0,
            look, look == 0F ? 0F : 0.35F, TavernMotionAnimations.SEATED_IDLE, calm, actWeight(local, act.length()));
    }

    private static final Map<SettlerEntity, float[]> BARD_LOOK = new WeakHashMap<>();

    /** Head yaw toward a playing bard within 16 blocks (about 70% of patrons watch), else 0. */
    private static float bardLook(SettlerEntity e, TavernSeatEntity seat) {
        if (TavernTableMath.hash01(entitySeed(e), 83, 1) >= 0.7) return 0F;
        long now = e.level().getGameTime();
        float[] c = BARD_LOOK.computeIfAbsent(e, k -> new float[] {Float.NaN, 0F});
        if (Float.isNaN(c[0]) || now - (long) c[0] >= 40) {
            c[0] = now;
            c[1] = 0F;
            for (SettlerEntity b : e.level().getEntitiesOfClass(SettlerEntity.class, e.getBoundingBox().inflate(16),
                    s -> s != e && s.isAlive() && s.getActivity() == SettlerActivity.PLAYING_MUSIC)) {
                c[1] = lookYaw(e, seat, b.position());
                break;
            }
        }
        return c[1];
    }

    private static Pick seated(SettlerEntity e, TavernSeatEntity seat, SettlerActivity activity, float age, float partial) {
        if (!seat.isSettled() || activity == SettlerActivity.EATING || activity == SettlerActivity.PLAYING_MUSIC
            || e.hasMeal() || TavernServingHandPose.findServing(e) != null) return null;
        BlockPos cell = seat.syncedTable();
        if (cell == null) return null;
        float[] posture = TavernTableMath.posture(entitySeed(e));
        long day = Math.floorMod(e.level().getDayTime(), 24000L);
        if (e.getEnergy() < 25.0F || day >= 12250L && day < 13500L) {
            return new Pick(TavernMotionAnimations.SLEEPY_NOD, ownClock(e, age, 6F), Owns.UPPER,
                posture[0], 0, 0, 0, 0);
        }
        List<TavernSeatEntity> mates = tableSeats(e, cell);
        int count = mates.size();
        int index = mates.indexOf(seat);
        if (count < 2 || index < 0) return own(e, seat, age, posture);
        // one conductor per physical table: the lowest cell of a multi-block table
        BlockPos table = cell;
        for (TavernSeatEntity s : mates) {
            BlockPos t = s.syncedTable();
            if (t != null && t.asLong() < table.asLong()) table = t;
        }
        double sec = (e.level().getGameTime() + partial) / 20.0;
        TavernTableMath.TableEvent ev = TavernTableMath.tableEvent(table, sec);
        if (!TavernTableMath.inEvent(ev, table, index, count)) return own(e, seat, age, posture);
        long cycle = ev.index();
        double el = ev.local(sec);
        float w = actWeight(el, ev.length());
        long calm = ownClock(e, age, 12F);
        switch (ev.event()) {
            case CHEER -> {
                TavernTableMath.Cheer plan = cheerPlan(table, cycle, mates);
                float t = (float) el;
                float local = TavernTableMath.cheerLocal(plan, index, t);
                List<TavernTableMath.Seat> seats = new ArrayList<>(count);
                for (TavernSeatEntity s : mates) seats.add(mathSeat(s));
                var target = TavernTableMath.mugTarget(seats, plan, index, table, cycle);
                TavernTableMath.Correction c = target == null ? TavernTableMath.Correction.NONE
                    : TavernTableMath.correction(seats.get(index), target);
                // reach in with the raise, hold through the clink, let go before the swig
                float r = smooth((local - 0.3F) / 0.45F) * (1F - smooth((local - 1.25F) / 0.4F));
                return new Pick(CHEER_STYLES[plan.style()[index]], (long) (local * 1000F), Owns.UPPER,
                    c.yaw() * r + posture[0] * (1 - r), c.pitch() * r + posture[1] * (1 - r), c.lift() * r, 0, 0,
                    TavernMotionAnimations.SEATED_IDLE, calm, w);
            }
            case STORY -> {
                int teller = TavernTableMath.teller(cycle, 0, count);
                if (index == teller) {
                    return new Pick(TavernMotionAnimations.SEATED_STORY, (long) (el * 1000.0), Owns.UPPER,
                        posture[0] * 0.5F, posture[1], 0, lookYaw(e, seat, centroidOthers(mates, seat)), 0.9F,
                        TavernMotionAnimations.SEATED_IDLE, calm, w);
                }
                float lag = TavernTableMath.reactionLag(table, cycle, 0, index, count);
                int style = TavernTableMath.listenStyle(table, cycle, 0, index);
                return new Pick(LISTEN_STYLES[style], (long) (Math.max(0.0, el - lag) * 1000.0), Owns.UPPER,
                    posture[0], posture[1], 0, lookYaw(e, seat, mates.get(teller).position()), style == 2 ? 0.7F : 1F,
                    TavernMotionAnimations.SEATED_IDLE, calm, w);
            }
            case TOAST -> {
                int teller = TavernTableMath.teller(cycle, 0, count);
                if (index == teller) {
                    return new Pick(TavernMotionAnimations.SEATED_TOAST, (long) (el * 1000.0), Owns.UPPER,
                        posture[0] * 0.5F, posture[1], 0, lookYaw(e, seat, centroidOthers(mates, seat)), 0.6F,
                        TavernMotionAnimations.SEATED_IDLE, calm, w);
                }
                float lag = TavernTableMath.toastAnswer(table, cycle, index, count);
                return new Pick(TavernMotionAnimations.SEATED_TOAST, (long) (Math.max(0.0, el - lag) * 1000.0),
                    Owns.UPPER, posture[0], posture[1], 0, 0, 0, TavernMotionAnimations.SEATED_IDLE, calm, w);
            }
            default -> {
                return own(e, seat, age, posture);
            }
        }
    }

    private static TavernTableMath.Cheer cheerPlan(BlockPos table, long cycle, List<TavernSeatEntity> mates) {
        int count = mates.size();
        CheerCache c = CHEERS.computeIfAbsent(table.asLong(), k -> new CheerCache());
        if (c.cycle != cycle || c.count != count || c.plan == null) {
            if (CHEERS.size() > 256) CHEERS.clear();
            c.cycle = cycle; c.count = count;
            float[] yaws = new float[count];
            long[] cells = new long[count];
            for (int i = 0; i < count; i++) {
                yaws[i] = mates.get(i).getYRot();
                BlockPos t = mates.get(i).syncedTable();
                cells[i] = t == null ? 0L : t.asLong();
            }
            c.plan = TavernTableMath.planCheer(table, cycle, count, yaws, cells);
        }
        return c.plan;
    }

    private static TavernTableMath.Seat mathSeat(TavernSeatEntity s) {
        return new TavernTableMath.Seat(s.position().add(0, TavernTableMath.HIP, 0), s.getYRot(), s.syncedTable());
    }

    /** Settled, occupied seats at this table or the next cell of a two-block table; stable order. */
    private static List<TavernSeatEntity> tableSeats(SettlerEntity e, BlockPos table) {
        long now = e.level().getGameTime();
        TableCache cache = TABLES.computeIfAbsent(table.asLong(), k -> new TableCache());
        if (cache.tick == now) return cache.seats;
        if (TABLES.size() > 256) TABLES.clear();
        List<TavernSeatEntity> seats = new ArrayList<>(4);
        for (TavernSeatEntity s : e.level().getEntitiesOfClass(TavernSeatEntity.class,
                new AABB(table).inflate(2.5), s -> !s.isRemoved() && s.isSettled()
                    && s.getFirstPassenger() instanceof SettlerEntity)) {
            BlockPos t = s.syncedTable();
            if (t != null && t.getY() == table.getY() && Math.abs(t.getX() - table.getX()) + Math.abs(t.getZ() - table.getZ()) <= 1)
                seats.add(s);
            if (seats.size() >= 8) break;
        }
        seats.sort(Comparator.comparingInt(s -> s.getFirstPassenger().getId()));
        cache.tick = now;
        cache.seats = seats;
        return seats;
    }

    private static Vec3 centroidOthers(List<TavernSeatEntity> mates, TavernSeatEntity self) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (TavernSeatEntity s : mates) {
            if (s == self) continue;
            x += s.getX(); y += s.getY(); z += s.getZ(); n++;
        }
        return n == 0 ? self.position() : new Vec3(x / n, y / n, z / n);
    }

    /** Head yaw (model radians, + = toward the settler's right) that looks at a world point. */
    private static float lookYaw(SettlerEntity e, TavernSeatEntity seat, Vec3 at) {
        Vec3 d = at.subtract(seat.position());
        float yaw = seat.getYRot();
        double l = d.dot(TavernTableMath.left(yaw)), f = d.dot(TavernTableMath.forward(yaw));
        return Mth.clamp((float) Math.atan2(-l, f), -1.05F, 1.05F);
    }

    // ------------------------------------------------------------------ innkeeper

    private static Pick innkeeper(SettlerEntity e, SettlerActivity activity, float age) {
        TavernServingEntity serving = TavernServingHandPose.findServing(e);
        if (serving != null) {
            if (e.getUUID().equals(serving.hostId()) && (serving.phase() == TavernServingEntity.Phase.CARRYING
                || serving.phase() == TavernServingEntity.Phase.RETURNING)) {
                return new Pick(TavernMotionAnimations.SERVE_CARRY, (long) (age * 50F), Owns.OVERLAY, 0, 0, 0, 0, 0);
            }
            return null;
        }
        if (activity != SettlerActivity.IDLE || e.walkAnimation.speed() > 0.05F
            || !e.getMainHandItem().isEmpty() || !e.getOffhandItem().isEmpty()
            || e.innkeeperSocialMode() != 0 || e.villageSocialMode() != 0) return null;
        Direction facing = Direction.fromYRot(e.yBodyRot);
        BlockPos front = e.blockPosition().relative(facing);
        var level = e.level();
        if (!level.hasChunkAt(front) || !level.hasChunkAt(front.above())) return null;
        long seed = entitySeed(e);
        double sec = age / 20.0;
        if (level.getBlockState(front.above()).getBlock() instanceof com.hearthstead.block.AleTapBlock) {
            // one pour, then back to the ordinary idle (polishing, watching the room) for a while
            double[] bout = bout(seed, sec, 4.0, 1, 14.0, 26.0);
            if (bout == null) return null;
            // under it the very idle the innkeeper was already playing (same clock), so no pop
            return new Pick(TavernMotionAnimations.ALE_POUR, (long) (bout[0] * 1000.0), Owns.FULL, 0, 0, 0, 0, 0,
                SettlerAnimations.IDLE_INNKEEPER, e.idleInnkeeperState.getAccumulatedTime(),
                actWeight(bout[0], bout[1]));
        }
        var counter = level.getBlockState(front).getCollisionShape(level, front);
        var above = level.getBlockState(front.above()).getCollisionShape(level, front.above());
        if (!counter.isEmpty() && counter.max(Direction.Axis.Y) >= .85 && counter.max(Direction.Axis.Y) <= 1.0
            && (above.isEmpty() || above.min(Direction.Axis.Y) > .3)) {
            // a bout of wiping (1-2 passes), then leaning on the bar watching the room
            double[] bout = bout(seed, sec, 4.0, 2, 10.0, 20.0);
            long lean = ownClock(e, age, 8F);
            if (bout == null) return new Pick(TavernMotionAnimations.COUNTER_LEAN, lean, Owns.FULL, 0, 0, 0, 0, 0);
            return new Pick(TavernMotionAnimations.COUNTER_WIPE, (long) (bout[0] * 1000.0), Owns.FULL, 0, 0, 0, 0, 0,
                TavernMotionAnimations.COUNTER_LEAN, lean, actWeight(bout[0], bout[1]));
        }
        return null;
    }

    /**
     * A work bout inside a calm stretch: windows of calmMin..calmMax + the bout; the bout is
     * 1..maxLoops passes of {@code loop} s. Returns {local, length} while in the bout, else null.
     */
    private static double[] bout(long seed, double sec, double loop, int maxLoops, double calmMin, double calmMax) {
        double off = TavernTableMath.hash01(seed, 87, 0) * 60.0;
        double t = sec + off;
        long k = (long) Math.floor(t / 60.0);
        double w0 = k * 60.0, x = w0;
        for (int n = 0; n < 8 && x < w0 + 60.0; n++) {
            double calm = calmMin + (calmMax - calmMin) * TavernTableMath.hash01(seed, k * 16 + n, 88);
            x += calm;
            double len = loop * (1 + (int) (TavernTableMath.hash01(seed, k * 16 + n, 89) * maxLoops));
            if (x + len > w0 + 60.0) break;
            if (t >= x && t < x + len) return new double[] {t - x, len};
            x += len;
        }
        return null;
    }

    // ------------------------------------------------------------------ dance

    private static Pick dance(SettlerEntity e, SettlerActivity activity, float age) {
        if (activity != SettlerActivity.IDLE || e.walkAnimation.speed() > 0.05F
            || e.villageSocialMode() != 0 || e.getProfession().martial()
            || !com.hearthstead.settlement.TavernVisitSchedule.isOpen(e.level().getDayTime())
            || Math.floorMod(e.getId() * 0x2F6B, 3) == 0) return null;
        long now = e.level().getGameTime();
        long[] c = BARD_NEAR.computeIfAbsent(e, k -> new long[] {Long.MIN_VALUE, 0});
        if (now - c[0] >= 20) {
            c[0] = now;
            c[1] = e.level().getEntitiesOfClass(SettlerEntity.class, e.getBoundingBox().inflate(8),
                s -> s != e && s.isAlive() && s.getActivity() == SettlerActivity.PLAYING_MUSIC).isEmpty() ? 0 : 1;
        }
        if (c[1] == 0) return null;
        return new Pick(TavernMotionAnimations.DANCE_JIG, (long) ((age + e.getId() % 40) * 50F), Owns.FULL, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ talking pair

    /** This person's own clip clock in a SocialPair (own phase + 0.9-1.1 speed), millis. */
    public static long pairClock(SettlerEntity e, float elapsedTicks) {
        long seed = entitySeed(e);
        float t = elapsedTicks / 20F * TavernTableMath.speed(seed) + TavernTableMath.phaseOffset(seed, 2.4F);
        return (long) (t * 1000F);
    }

    private static float pulse(float u) {
        return u <= 0F || u >= 1F ? 0F : (float) Math.sin(Math.PI * u);
    }

    /**
     * Procedural pair head life on this person's own timers (radians): {yaw, pitch, roll, torso roll}.
     * Glances away and back (more while speaking), back-channel nods and a raised-brow head tilt
     * while listening, a slow weight shift.
     */
    public static float[] pairHead(SettlerEntity e, float elapsedTicks) {
        long seed = entitySeed(e);
        float t = elapsedTicks / 20F;
        boolean speaking = com.hearthstead.entity.SocialPair.role(e) == com.hearthstead.entity.VillageSocial.CHAT;
        float yaw = 0, pitch = 0, roll = 0;
        // glances: 1.6 s slots, each may hold a 0.5-0.8 s look away
        int slot = (int) Math.floor(t / 1.6F);
        for (int k = slot - 1; k <= slot; k++) {
            if (k < 0) continue;
            double chance = TavernTableMath.hash01(seed, 91, k);
            if (chance >= (speaking ? 0.4 : 0.22)) continue;
            float st = k * 1.6F + (float) TavernTableMath.hash01(seed, 92, k) * 0.8F;
            float len = 0.5F + 0.3F * (float) TavernTableMath.hash01(seed, 93, k);
            float amp = (float) Math.toRadians(15 + 10 * TavernTableMath.hash01(seed, 94, k))
                * (TavernTableMath.hash01(seed, 95, k) < 0.5 ? -1 : 1);
            float u = (t - st) / len;
            float w = smooth(u * 4F) * (1F - smooth((u - 0.75F) * 4F));
            yaw += amp * w;
            pitch += (float) Math.toRadians(4) * w * (float) (TavernTableMath.hash01(seed, 96, k) - 0.3);
        }
        if (!speaking) {
            // back-channel: "mm" nods and a head tilt on 1.2 s slots
            int b = (int) Math.floor(t / 1.2F);
            for (int k = b - 1; k <= b; k++) {
                if (k < 0) continue;
                double kind = TavernTableMath.hash01(seed, 97, k);
                float st = k * 1.2F + (float) TavernTableMath.hash01(seed, 98, k) * 0.7F;
                if (kind < 0.35) pitch += (float) Math.toRadians(7) * pulse((t - st) / 0.35F);
                else if (kind < 0.55) roll += (float) Math.toRadians(6) * pulse((t - st) / 0.9F)
                    * (TavernTableMath.hash01(seed, 99, k) < 0.5 ? -1 : 1);
            }
        }
        float period = 3F + 2F * (float) TavernTableMath.hash01(seed, 100, 1);
        float torsoRoll = (float) Math.toRadians(1.6) * (float) Math.sin(2 * Math.PI * (t / period
            + TavernTableMath.hash01(seed, 100, 2)));
        return new float[] {yaw, pitch, roll, torsoRoll};
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0F, 1F);
        return x * x * (3F - 2F * x);
    }
}

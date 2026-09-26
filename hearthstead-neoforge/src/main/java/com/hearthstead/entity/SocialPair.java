package com.hearthstead.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The shared pairing system for two settlers talking (tavern lane, agreed with the animation and
 * living-village lanes). One synced start tick and seed are only a loose conductor (owner, 26 Sep:
 * "fully synced must NOT happen"):
 * <ul>
 *   <li>turns are NOT metronomic: each lasts {@link #TURN_MIN}..{@link #TURN_MAX} ticks
 *       (1.5-6 s) from the pair seed;</li>
 *   <li>about a third of the turns end in a comfortable silence of 1.5-4 s (nobody talks, they
 *       just stand together); about a third of the others hand over a moment early (a tiny
 *       {@link #OVERLAP}-tick overlap: the next speaker cuts in);</li>
 *   <li>the two stand at a comfortable 1.2-1.5 blocks, each body turned 10-20 degrees off the
 *       partner (20-40 degrees off square together) while the eyes stay on the partner;</li>
 *   <li>the client adds per-person clip phase/speed, back-channel nods and glances away
 *       (TavernPatronMotion.pairHead), and cross-fades the speak/listen clips at hand-overs.</li>
 * </ul>
 * Server-authoritative. The cue rides DATA_VILLAGE_SOCIAL (mode {@link VillageSocial#PAIR});
 * it owns no inventory, navigation target or schedule: the caller's goal decides when to talk.
 */
public final class SocialPair {
    /** Mean turn, kept for the cue's length bookkeeping. */
    public static final int TURN_TICKS = 72;
    public static final int TURN_MIN = 30, TURN_MAX = 120, OVERLAP = 5;
    public static final double MAX_DISTANCE = 4.0, NEAR = 1.2, FAR = 1.5;

    private SocialPair() {
    }

    // ------------------------------------------------------------------ server

    /** Starts a pair on both settlers with one start tick and seed; {@code speaker} talks first. */
    public static boolean start(SettlerEntity speaker, SettlerEntity listener, int turns) {
        if (speaker == null || listener == null || speaker == listener
            || !(speaker.level() instanceof ServerLevel level) || listener.level() != level
            || !speaker.isAlive() || !listener.isAlive() || turns <= 0
            || speaker.distanceToSqr(listener) > MAX_DISTANCE * MAX_DISTANCE) return false;
        long now = level.getGameTime();
        int n = Math.min(turns, 32);
        int seed = (int) (now * 31 + speaker.getId() * 7919L + listener.getId());
        int total = turnStart(seed, n);
        int per = (total + n - 1) / n;
        speaker.setSocialPairCue(listener.getId(), 0, now, per, n, seed);
        listener.setSocialPairCue(speaker.getId(), 1, now, per, n, seed);
        face(speaker, listener, 0, seed);
        face(listener, speaker, 1, seed);
        return true;
    }

    /** Per server tick while paired: comfortable distance, relaxed facing, eye contact. */
    public static boolean tick(SettlerEntity self) {
        if (self == null || self.level().isClientSide) return false;
        SettlerEntity other = partner(self);
        if (other == null || !other.isAlive() || role(self) == VillageSocial.NONE
            || partner(other) != self || self.distanceToSqr(other) > MAX_DISTANCE * MAX_DISTANCE) {
            stop(self);
            return false;
        }
        long elapsed = self.level().getGameTime() - self.villageSocialStart();
        double d = Math.sqrt(self.distanceToSqr(other));
        if (elapsed < 80 && (d < NEAR - 0.05 || d > FAR + 0.1) && self.socialPairSlot() == 1) {
            // the listener settles to a comfortable distance once, early; the speaker stays put
            Vec3 away = self.position().subtract(other.position());
            Vec3 dir = away.horizontalDistanceSqr() < 1e-6 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 goal = other.position().add(dir.scale((NEAR + FAR) / 2));
            if (self.getNavigation().isDone()) self.getNavigation().moveTo(goal.x, goal.y, goal.z, 0.45);
            self.getLookControl().setLookAt(other, 30.0F, 30.0F);
            return true;
        }
        face(self, other, self.socialPairSlot(), self.socialPairSeed());
        return true;
    }

    /** Clears the cue on self and on the partner while they are still paired to each other. */
    public static void stop(SettlerEntity self) {
        if (self == null || self.level().isClientSide) return;
        SettlerEntity other = partner(self);
        if (self.socialPairPartnerId() >= 0) self.setVillageSocial(VillageSocial.NONE, self.level().getGameTime());
        if (other != null && other.socialPairPartnerId() == self.getId())
            other.setVillageSocial(VillageSocial.NONE, other.level().getGameTime());
    }

    // ------------------------------------------------------------------ shared timing (client + server + tests)

    private static double h(long seed, long a, long b) {
        return TavernTableMath.hash01(seed, a, b);
    }

    /** Length of turn k in ticks: 1.5-6 s, never metronomic. */
    public static int turnLength(int seed, int k) {
        return TURN_MIN + (int) (h(seed, 11, k) * (TURN_MAX - TURN_MIN + 1));
    }

    /**
     * Comfortable silence after turn k (owner: natural gaps): about 35% of turns end in a pause of
     * 1.5-4 s where nobody talks - they just stand together - else 0.
     */
    public static int silenceAfter(int seed, int k) {
        return h(seed, 15, k) < 0.35 ? 30 + (int) (h(seed, 16, k) * 51) : 0;
    }

    /** Whether the hand-over at the END of turn k is a small overlap (never after a silence). */
    public static boolean overlaps(int seed, int k) {
        return silenceAfter(seed, k) == 0 && h(seed, 13, k) < 0.35;
    }

    /** Tick at which turn k starts (turn 0 starts at 0). */
    public static int turnStart(int seed, int k) {
        int t = 0;
        for (int i = 0; i < k; i++) t += turnLength(seed, i) + silenceAfter(seed, i);
        return t;
    }

    /** The turn at this elapsed tick (a silence belongs to the turn before it). */
    public static int turnAt(int seed, long elapsed) {
        int k = 0, t = 0;
        while (k < 64) {
            int len = turnLength(seed, k) + silenceAfter(seed, k);
            if (elapsed < t + len) return k;
            t += len;
            k++;
        }
        return k;
    }

    /** Pure turn rule: CHAT while slot speaks (incl. an early cut-in), LISTEN otherwise and in silences. */
    public static int role(int slot, long elapsed, int seed) {
        if (elapsed < 0) return VillageSocial.NONE;
        int k = turnAt(seed, elapsed);
        int start = turnStart(seed, k), talkEnd = start + turnLength(seed, k);
        if (elapsed >= talkEnd) return VillageSocial.LISTEN;          // a comfortable silence
        boolean mine = ((k + slot) & 1) == 0;
        if (!mine && overlaps(seed, k) && talkEnd - elapsed <= OVERLAP) return VillageSocial.CHAT;
        return mine ? VillageSocial.CHAT : VillageSocial.LISTEN;
    }

    /** 0..1 speaking weight, eased over the hand-over (for the client cross-fade). */
    public static float speakWeight(int slot, float elapsed, int seed) {
        float sum = 0F;
        for (int i = -3; i <= 3; i++) {
            long t = (long) Math.floor(elapsed + i * 2);
            sum += role(slot, Math.max(0, t), seed) == VillageSocial.CHAT ? 1F : 0F;
        }
        return sum / 7F;
    }

    public static int role(SettlerEntity e) {
        if (e == null || e.villageSocialMode() != VillageSocial.PAIR) return VillageSocial.NONE;
        long elapsed = e.level().getGameTime() - e.villageSocialStart();
        return role(e.socialPairSlot(), elapsed, e.socialPairSeed());
    }

    public static SettlerEntity partner(SettlerEntity e) {
        if (e == null) return null;
        int id = e.socialPairPartnerId();
        return id >= 0 && e.level().getEntity(id) instanceof SettlerEntity s ? s : null;
    }

    /** Body yaw offset (deg) for this slot: 10-20 degrees, the two turned opposite ways. */
    public static float angleOffset(int seed, int slot) {
        float a = 10F + 10F * (float) h(seed, 17, slot);
        return slot == 0 ? a : -a;
    }

    private static void face(SettlerEntity self, SettlerEntity other, int slot, int seed) {
        float yaw = (float) (Mth.atan2(other.getZ() - self.getZ(), other.getX() - self.getX())
            * (180.0 / Math.PI)) - 90.0F + angleOffset(seed, slot);
        self.getNavigation().stop();
        self.setYBodyRot(Mth.rotLerp(0.12F, self.yBodyRot, yaw));
        self.setYRot(self.yBodyRot);
        self.getLookControl().setLookAt(other, 30.0F, 30.0F);
    }
}

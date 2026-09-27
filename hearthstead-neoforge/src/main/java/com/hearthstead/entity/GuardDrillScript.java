package com.hearthstead.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * The morning drill's choreography (Guard Drill, Watch &amp; Defense ring 1), shared by the
 * server (footwork displacement, practice-blow sounds, Strength reps) and the client (which
 * clips play when, with what weights).
 *
 * <p>v2 (owner, 26 Sep: "smoother, more movement, more fighting"). One synced start tick and
 * seed per pair are only a loose conductor; everything below comes from the seed:
 * <ul>
 *   <li><b>fighting</b>: combos of one to three blows, feints, parry-then-riposte, and now and
 *       then a stumble that ends with both guards nodding and laughing it off;</li>
 *   <li><b>footwork</b>: the pair circles (both side-step the same way, so they turn round each
 *       other), presses (one advances as the other gives ground) and opens or closes the
 *       distance; the server moves the entities with the clip's own easing
 *       ({@link #moveEase}) and keeps them 1.8-3 blocks apart;</li>
 *   <li><b>never in unison</b>: each action starts on its own 0.1-0.8 s offset, each person
 *       has their own speed (0.9-1.1) plus a per-action wobble, answers land up to a tick
 *       early or two late, and breathers are short, rare and staggered;</li>
 *   <li><b>smooth</b>: clips cross-fade into each other and into the stance over
 *       {@link #BLEND_S} (5 ticks); a chained blow starts in the previous blow's recovery.</li>
 * </ul>
 * A guard with no partner shadow-drills with the same grammar.
 *
 * <p>Pure and allocation-light: the plan for a session is built once from the seed and sampled
 * per frame. Deterministic across JVMs (splitmix64), so the Blender reel reproduces it exactly
 * (tools/blender/pipeline/clips/combat/guard_drill.py ports it line by line).
 */
public final class GuardDrillScript {
    public static final int STANCE = 0;
    public static final int CUT_HIGH = 1;
    public static final int CUT_LOW = 2;
    public static final int PARRY_HIGH = 3;
    public static final int PARRY_LOW = 4;
    public static final int EVADE = 5;
    public static final int BREATHER = 6;
    public static final int STEP_LEFT = 7;
    public static final int STEP_RIGHT = 8;
    public static final int ADVANCE = 9;
    public static final int RETREAT = 10;
    public static final int FEINT = 11;
    public static final int STUMBLE = 12;
    public static final int NOD = 13;
    public static final int CLIPS = 14;

    /** Clip lengths in seconds: the contract of the authored clips (GuardDrillAnimations). */
    public static final float[] LENGTH_S = {3.2F, 1.2F, 1.1F, 1.0F, 1.0F, 0.9F, 2.2F,
        0.8F, 0.8F, 0.7F, 0.7F, 0.8F, 1.4F, 1.3F};
    /** Blade meets blade (cuts, parries), furthest back (evade, stumble), fake beat (feint). */
    public static final float[] CONTACT_S = {0.0F, 0.55F, 0.50F, 0.45F, 0.42F, 0.40F, 0.0F,
        0.0F, 0.0F, 0.0F, 0.0F, 0.32F, 0.35F, 0.0F};
    /** Where a blow in a combo hands over to the next one (seconds into the cut). */
    public static final float[] CHAIN_S = {0.0F, 0.80F, 0.74F, 0.0F, 0.0F, 0.0F, 0.0F,
        0.0F, 0.0F, 0.0F, 0.0F, 0.52F, 0.0F, 0.0F};
    /** Footwork: blocks moved to the guard's own left / forward over the clip. */
    public static final float[] MOVE_LEFT = {0, 0, 0, 0, 0, 0, 0, 0.5F, -0.5F, 0, 0, 0, 0, 0};
    public static final float[] MOVE_FWD = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0.375F, -0.375F, 0, -0.375F, 0};
    /** The move eases in between these fractions of the clip (the feet's contract). */
    public static final float MOVE_FROM = 0.1F;
    public static final float MOVE_TO = 0.8F;

    /** Slot of a guard drilling alone. Pair slots are 0 and 1. */
    public static final int SOLO = 2;

    /** Cross-fade into and out of every clip, in seconds (5 ticks). */
    public static final float BLEND_S = 0.25F;

    public static final int PHASE_MIN_TICKS = 2;   // 0.1 s
    public static final int PHASE_MAX_TICKS = 16;  // 0.8 s
    public static final int COOLDOWN_MIN_TICKS = 8;   // 0.4 s
    public static final int COOLDOWN_MAX_TICKS = 24;  // 1.2 s
    /** A stumble happens at most once per this many ticks. */
    public static final int STUMBLE_GAP_TICKS = 500;

    public enum Answer { PARRY, EVADE, NONE, STUMBLE }

    /** One clip played by one person, starting at a session tick at a playback speed. */
    public record Action(int clip, float start, float speed) {
        public float end() {
            return start + LENGTH_S[clip] * 20.0F / speed;
        }

        public float at(float seconds) {
            return start + seconds * 20.0F / speed;
        }
    }

    /** A practice blow reaching its target: when, by whom, how it was answered; feints included. */
    public record Contact(float tick, int attacker, Answer answer, boolean high, boolean feint) {
    }

    /** A whole session: per-slot actions in start order and the blows. */
    public record Plan(int seed, boolean pair, int lengthTicks, List<Action> slot0, List<Action> slot1,
                       List<Contact> contacts) {
        public List<Action> actions(int slot) {
            return slot == 1 ? slot1 : slot0;
        }
    }

    /**
     * What one person shows at one moment: up to two clips cross-fading over the stance.
     * {@code weight + prevWeight <= 1}; the stance takes the rest.
     */
    public record Sample(int clip, float local, float weight, int prevClip, float prevLocal, float prevWeight,
                         float stanceLocal) {
    }

    private GuardDrillScript() {
    }

    // ------------------------------------------------------------------ per-person feel

    /** This person's stance phase offset, {@link #PHASE_MIN_TICKS}..{@link #PHASE_MAX_TICKS} ticks. */
    public static float phaseTicks(int seed, int slot) {
        return PHASE_MIN_TICKS + unit(seed, 11 + slot) * (PHASE_MAX_TICKS - PHASE_MIN_TICKS);
    }

    /** This person's playback speed, 0.9..1.1. */
    public static float speed(int seed, int slot) {
        return 0.9F + unit(seed, 23 + slot) * 0.2F;
    }

    public static boolean moves(int clip) {
        return MOVE_LEFT[clip] != 0.0F || MOVE_FWD[clip] != 0.0F;
    }

    public static boolean fighting(int clip) {
        return clip == CUT_HIGH || clip == CUT_LOW || clip == PARRY_HIGH || clip == PARRY_LOW
            || clip == EVADE || clip == FEINT || clip == STUMBLE;
    }

    /** 0..1: how far a footwork clip has carried the body at {@code local} seconds. */
    public static float moveEase(int clip, float local) {
        float len = LENGTH_S[clip];
        float u = (local / len - MOVE_FROM) / (MOVE_TO - MOVE_FROM);
        u = Math.max(0.0F, Math.min(1.0F, u));
        return u * u * (3.0F - 2.0F * u);
    }

    // ------------------------------------------------------------------ plan

    /** The whole session for a pair ({@code pair}) or a solo shadow drill. */
    public static Plan plan(int seed, boolean pair, int lengthTicks) {
        Rng rng = new Rng(seed);
        List<Action> a = new ArrayList<>();
        List<Action> b = new ArrayList<>();
        List<Contact> contacts = new ArrayList<>();
        float t = 16.0F + rng.range(0, 12);
        int untilBreather = 6 + rng.nextInt(4);
        int attacker = pair ? rng.nextInt(2) : 0;
        float lastStumble = -STUMBLE_GAP_TICKS;
        int guard = 0;
        while (t < lengthTicks - 50 && guard++ < 600) {
            if (untilBreather <= 0) {
                // Short, rare, staggered: one lowers the guard first, the other 0.4-1.5 s later
                // (or keeps the stance and waits).
                int skip = pair && rng.nextFloat() < 0.35F ? rng.nextInt(2) : -1;
                int first = rng.nextInt(2);
                float lead = rng.range(PHASE_MIN_TICKS, PHASE_MAX_TICKS);
                float lag = lead + rng.range(8, 30);
                float end = t;
                for (int slot = 0; slot < (pair ? 2 : 1); slot++) {
                    if (slot == skip) continue;
                    Action act = new Action(BREATHER, t + (slot == first || !pair ? lead : lag),
                        speed(seed, slot) * rng.wobble());
                    list(slot, a, b).add(act);
                    end = Math.max(end, act.end());
                }
                t = end + rng.range(6, 18);
                untilBreather = 6 + rng.nextInt(4);
                continue;
            }
            float r = rng.nextFloat();
            if (r < 0.22F) {
                // Footwork: circle (both side-step the same way), press, or open/close.
                float rk = rng.nextFloat();
                int kind = rk < 0.5F ? 0 : rk < 0.75F ? 1 : 2;
                int steps = 1 + rng.nextInt(3);
                int side = rng.nextBoolean() ? STEP_LEFT : STEP_RIGHT;
                for (int s = 0; s < steps; s++) {
                    int together = rng.nextBoolean() ? ADVANCE : RETREAT;
                    float end = t;
                    for (int slot = 0; slot < (pair ? 2 : 1); slot++) {
                        int clip = kind == 0 ? side
                            : kind == 1 ? (slot == attacker ? ADVANCE : RETREAT)
                            : together;
                        Action act = new Action(clip, t + rng.range(PHASE_MIN_TICKS, PHASE_MAX_TICKS),
                            speed(seed, slot) * rng.wobble());
                        list(slot, a, b).add(act);
                        end = Math.max(end, act.end());
                    }
                    // The next step starts inside this one's settle: a flowing shuffle.
                    t = end - BLEND_S * 20.0F;
                }
                t += rng.range(2, 10);
                continue;
            }
            // ---- an exchange: optional feint, one to three blows, maybe a riposte
            float rb = rng.nextFloat();
            int blows = rb < 0.35F ? 1 : rb < 0.77F ? 2 : 3;
            int def = pair ? 1 - attacker : SOLO;
            float sa = speed(seed, attacker) * rng.wobble();
            float cursor = t + rng.range(PHASE_MIN_TICKS, PHASE_MAX_TICKS);
            float end = t;
            boolean steppedIn = false;
            if (rng.nextFloat() < 0.4F) {
                // Step in to close the distance, and cut out of the step's settle.
                steppedIn = true;
                Action in = new Action(ADVANCE, cursor, sa);
                list(attacker, a, b).add(in);
                cursor = in.at(0.5F);
                end = Math.max(end, in.end());
            }
            if (rng.nextFloat() < 0.15F) {
                Action feint = new Action(FEINT, cursor, sa);
                list(attacker, a, b).add(feint);
                float fake = feint.at(CONTACT_S[FEINT]);
                boolean bites = pair && rng.nextFloat() < 0.5F;
                if (bites) {
                    // The partner flinches back from the fake: a real evade on a blow that never comes.
                    float sd = speed(seed, def) * rng.wobble();
                    Action flinch = new Action(EVADE, fake + rng.range(-1, 2) - CONTACT_S[EVADE] * 20.0F / sd, sd);
                    list(def, a, b).add(flinch);
                    end = Math.max(end, flinch.end());
                }
                contacts.add(new Contact(fake, pair ? attacker : SOLO, bites ? Answer.EVADE : Answer.NONE, true, true));
                cursor = feint.at(CHAIN_S[FEINT]);
                end = Math.max(end, feint.end());
            }
            Answer last = Answer.NONE;
            Action lastAnswer = null;
            for (int i = 0; i < blows; i++) {
                boolean high = rng.nextBoolean();
                int cutClip = high ? CUT_HIGH : CUT_LOW;
                Action cut = new Action(cutClip, cursor, sa * (0.98F + rng.nextFloat() * 0.04F));
                list(attacker, a, b).add(cut);
                float contact = cut.at(CONTACT_S[cutClip]);
                Answer answer = Answer.NONE;
                if (pair) {
                    float ra = rng.nextFloat();
                    answer = ra < 0.62F ? Answer.PARRY : ra < 0.80F ? Answer.EVADE : Answer.NONE;
                    if (i == blows - 1 && contact - lastStumble > STUMBLE_GAP_TICKS && rng.nextFloat() < 0.08F) {
                        answer = Answer.STUMBLE;
                    }
                    if (answer != Answer.NONE) {
                        int clip = switch (answer) {
                            case PARRY -> high ? PARRY_HIGH : PARRY_LOW;
                            case EVADE -> EVADE;
                            default -> STUMBLE;
                        };
                        float sd = speed(seed, def) * rng.wobble();
                        float react = rng.range(-1, 2);
                        Action ans = new Action(clip, contact + react - CONTACT_S[clip] * 20.0F / sd, sd);
                        list(def, a, b).add(ans);
                        end = Math.max(end, ans.end());
                        lastAnswer = ans;
                    }
                }
                contacts.add(new Contact(contact, pair ? attacker : SOLO, answer, high, false));
                last = answer;
                end = Math.max(end, cut.end());
                cursor = cut.at(CHAIN_S[cutClip]);
            }
            if (!pair && rng.nextFloat() < 0.35F) {
                // Shadow drill: a guard against an imagined answer, straight out of the last cut.
                Action guardUp = new Action(rng.nextBoolean() ? PARRY_HIGH : PARRY_LOW, cursor,
                    speed(seed, 0) * rng.wobble());
                a.add(guardUp);
                end = Math.max(end, guardUp.end());
            }
            if (last == Answer.STUMBLE) {
                // Knocked off balance: both laugh it off with a nod before going again.
                lastStumble = lastAnswer.start();
                Action nod1 = new Action(NOD, lastAnswer.end() - BLEND_S * 20.0F + rng.range(2, 8),
                    speed(seed, def) * rng.wobble());
                Action nod2 = new Action(NOD, end + rng.range(4, 16), speed(seed, attacker) * rng.wobble());
                list(def, a, b).add(nod1);
                list(attacker, a, b).add(nod2);
                end = Math.max(nod1.end(), nod2.end());
                attacker = 1 - attacker;
            } else if (pair && last == Answer.PARRY && rng.nextFloat() < 0.38F) {
                // Parry, then riposte: the defender cuts back out of the parry's recovery.
                boolean high = rng.nextBoolean();
                int cutClip = high ? CUT_HIGH : CUT_LOW;
                float sd = speed(seed, def) * rng.wobble();
                Action rip = new Action(cutClip, lastAnswer.at(0.62F), sd);
                list(def, a, b).add(rip);
                float contact = rip.at(CONTACT_S[cutClip]);
                Answer answer = rng.nextFloat() < 0.7F ? Answer.PARRY : Answer.EVADE;
                int clip = answer == Answer.PARRY ? (high ? PARRY_HIGH : PARRY_LOW) : EVADE;
                float sb = speed(seed, attacker) * rng.wobble();
                Action ans = new Action(clip, contact + rng.range(-1, 2) - CONTACT_S[clip] * 20.0F / sb, sb);
                list(attacker, a, b).add(ans);
                contacts.add(new Contact(contact, def, answer, high, false));
                end = Math.max(end, Math.max(rip.end(), ans.end()));
                attacker = def;   // the riposte takes the initiative
            } else {
                if (steppedIn && rng.nextFloat() < 0.6F) {
                    // Out again after the combo: the attacker gives back the step it took.
                    Action out = new Action(RETREAT, end - BLEND_S * 20.0F + rng.range(0, 6),
                        speed(seed, attacker) * rng.wobble());
                    list(attacker, a, b).add(out);
                    end = Math.max(end, out.end());
                }
                if (pair && rng.nextFloat() < 0.45F) {
                    // Pressed, the defender gives ground (or both drift round a step).
                    int clip = rng.nextFloat() < 0.6F ? RETREAT : rng.nextBoolean() ? STEP_LEFT : STEP_RIGHT;
                    float after = end - BLEND_S * 20.0F;
                    Action give = new Action(clip, after + rng.range(0, 6), speed(seed, def) * rng.wobble());
                    list(def, a, b).add(give);
                    if (clip != RETREAT) {
                        Action with = new Action(clip, after + rng.range(4, 14), speed(seed, attacker) * rng.wobble());
                        list(attacker, a, b).add(with);
                        end = Math.max(end, with.end());
                    }
                    end = Math.max(end, give.end());
                }
                if (pair && rng.nextFloat() < 0.7F) attacker = 1 - attacker;
            }
            t = end + rng.range(COOLDOWN_MIN_TICKS, COOLDOWN_MAX_TICKS);
            untilBreather--;
        }
        a.sort((x, y) -> Float.compare(x.start(), y.start()));
        b.sort((x, y) -> Float.compare(x.start(), y.start()));
        contacts.sort((x, y) -> Float.compare(x.tick(), y.tick()));
        return new Plan(seed, pair, lengthTicks, List.copyOf(a), List.copyOf(b), List.copyOf(contacts));
    }

    private static List<Action> list(int slot, List<Action> a, List<Action> b) {
        return slot == 1 ? b : a;
    }

    // ------------------------------------------------------------------ sample

    private static float fade(float x) {
        float w = Math.max(0.0F, Math.min(1.0F, x / BLEND_S));
        return w * w * (3.0F - 2.0F * w);
    }

    private static float envelope(Action act, float elapsed) {
        float local = (elapsed - act.start()) * act.speed() / 20.0F;
        return Math.min(fade(local), fade(LENGTH_S[act.clip()] - local));
    }

    /**
     * What {@code slot} (0, 1 or {@link #SOLO}) shows {@code elapsed} ticks into the session:
     * the newest running clip over the one it is replacing, both over the relaxed stance on the
     * person's own clock.
     */
    public static Sample sample(Plan plan, int slot, float elapsed) {
        int person = slot == SOLO ? 0 : slot;
        float stance = stanceLocal(plan.seed(), person, elapsed);
        if (elapsed < 0.0F || elapsed > plan.lengthTicks() + 40.0F) {
            return new Sample(STANCE, stance, 0.0F, STANCE, 0.0F, 0.0F, stance);
        }
        Action newest = null;
        Action older = null;
        for (Action act : plan.actions(person)) {
            if (act.start() > elapsed) break;
            if (elapsed < act.end()) {
                older = newest;
                newest = act;
            }
        }
        if (newest == null) {
            return new Sample(STANCE, stance, 0.0F, STANCE, 0.0F, 0.0F, stance);
        }
        float w = envelope(newest, elapsed);
        float local = (elapsed - newest.start()) * newest.speed() / 20.0F;
        if (older == null) {
            return new Sample(newest.clip(), local, w, STANCE, 0.0F, 0.0F, stance);
        }
        float wo = (1.0F - w) * envelope(older, elapsed);
        float olderLocal = (elapsed - older.start()) * older.speed() / 20.0F;
        return new Sample(newest.clip(), local, w, older.clip(), olderLocal, wo, stance);
    }

    /** The looping stance on this person's own phase and speed, in clip seconds. */
    public static float stanceLocal(int seed, int person, float elapsed) {
        float s = (elapsed + phaseTicks(seed, person) * 3.0F) * speed(seed, person) / 20.0F;
        float len = LENGTH_S[STANCE];
        return ((s % len) + len) % len;
    }

    /**
     * Footwork displacement for {@code slot} between two session ticks, as {left, forward}
     * blocks in the guard's own frame (the server turns it into a world move each tick).
     */
    public static float[] displacement(Plan plan, int slot, float from, float to) {
        float left = 0.0F;
        float fwd = 0.0F;
        int person = slot == SOLO ? 0 : slot;
        for (Action act : plan.actions(person)) {
            if (act.start() > to) break;
            if (!moves(act.clip()) || act.end() < from) continue;
            float l0 = Math.max(0.0F, (from - act.start()) * act.speed() / 20.0F);
            float l1 = Math.max(0.0F, (to - act.start()) * act.speed() / 20.0F);
            float d = moveEase(act.clip(), l1) - moveEase(act.clip(), l0);
            left += d * MOVE_LEFT[act.clip()];
            fwd += d * MOVE_FWD[act.clip()];
        }
        return new float[] {left, fwd};
    }

    /** The gap (blocks) the blade contacts are authored for; a cut closes to it in its wind-up. */
    public static final float STRIKE_GAP = 2.0F;
    /** Most a guard closes per tick while winding up a cut (blocks). */
    public static final float CLOSE_PER_TICK = 0.08F;

    /**
     * Whether {@code slot} is winding up a cut at {@code elapsed}: the server then closes the
     * distance to {@link #STRIKE_GAP} with the cut's own lead-foot step, so blades meet wherever
     * the footwork has taken the pair.
     */
    public static boolean closing(Plan plan, int slot, float elapsed) {
        int person = slot == SOLO ? 0 : slot;
        for (Action act : plan.actions(person)) {
            if (act.start() > elapsed) break;
            if ((act.clip() == CUT_HIGH || act.clip() == CUT_LOW) && elapsed < act.end()) {
                float local = (elapsed - act.start()) * act.speed() / 20.0F;
                if (local < CONTACT_S[act.clip()] - 0.05F) return true;
            }
        }
        return false;
    }

    /** Blows (and feints) whose contact falls in (from, to]: sounds and training beats. */
    public static List<Contact> contactsBetween(Plan plan, float from, float to) {
        List<Contact> out = new ArrayList<>(2);
        for (Contact c : plan.contacts()) {
            if (c.tick() > from && c.tick() <= to) out.add(c);
        }
        return out;
    }

    /** Whether {@code slot} took part in a real blow (struck it, or answered it). */
    public static boolean involved(Contact contact, int slot) {
        if (contact.feint()) return false;
        if (contact.attacker() == SOLO) return slot == SOLO;
        return contact.attacker() == slot || contact.answer() != Answer.NONE;
    }

    /** Fraction of the session in which at least one of the two is fighting (not stance/footwork). */
    public static float activeFraction(Plan plan) {
        int active = 0;
        int n = 0;
        for (float t = 0.0F; t < plan.lengthTicks(); t += 1.0F, n++) {
            if (fightingAt(plan.slot0(), t) || fightingAt(plan.slot1(), t)) active++;
        }
        return n == 0 ? 0.0F : active / (float) n;
    }

    private static boolean fightingAt(List<Action> actions, float t) {
        for (Action act : actions) {
            if (act.start() > t) break;
            if (t < act.end() && fighting(act.clip())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ rng

    /** A stable 0..1 value for (seed, salt). */
    static float unit(int seed, int salt) {
        long z = mix(((long) seed << 16) ^ (salt * 0x632BE59BD9B4E019L));
        return (z >>> 40) / (float) (1 << 24);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** splitmix64: tiny, fast and identical in the Python reel script. */
    static final class Rng {
        private long state;

        Rng(int seed) {
            state = seed * 0x9E3779B97F4A7C15L + 0x1D8E4E27C47D124FL;
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            return mix(state);
        }

        float nextFloat() {
            return (next() >>> 40) / (float) (1 << 24);
        }

        int nextInt(int bound) {
            return (int) ((next() >>> 33) % bound);
        }

        boolean nextBoolean() {
            return (next() & 1L) != 0L;
        }

        /** Uniform in [lo, hi]. */
        float range(float lo, float hi) {
            return lo + nextFloat() * (hi - lo);
        }

        /** A per-action speed wobble, 0.97..1.03. */
        float wobble() {
            return 0.97F + nextFloat() * 0.06F;
        }
    }
}

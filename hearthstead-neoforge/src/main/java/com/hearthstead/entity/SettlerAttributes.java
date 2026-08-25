package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A settler's five numbers: how they are rolled, and how they grow.
 *
 * <h2>Nobody starts good, and nobody ever finishes</h2>
 *
 * <p>Two rules decide the whole feel of this system, and both were the owner's:
 *
 * <ol>
 *   <li><b>A fresh settler caps at {@value #START_CAP} out of 100.</b> Not
 *       "usually low" — capped. Everything above that has to be earned by doing
 *       the specific work that trains it, so a strong settler is evidence of a
 *       settlement that gave them axes, not evidence of a lucky roll.
 *   <li><b>Nobody reaches 100.</b> Growth is proportional to
 *       {@code (1 - v/100)²}, so the last stretch is asymptotic — at 90 an
 *       attribute grows a hundredth as fast as at 0. {@value #CEILING} is the
 *       hard clamp, and in practice nothing gets near it.
 * </ol>
 *
 * <h2>The roll</h2>
 *
 * <p>Ordinary attributes use {@code 1 + floor(15·u^2.2)}: median <b>4</b>, mean
 * 5.2, one in five reaches 10, and about <b>3%</b> touch the cap. Each settler
 * also gets one <b>knack</b> — a single attribute rolled on {@code u^1.1}
 * instead, median <b>7</b>, 6% at the cap. That one line is what makes every
 * settler arrive as somebody in particular rather than as a bundle of fours.
 *
 * <h2>The growth</h2>
 *
 * <p>{@link #train} is called with work units — one completed work action, one
 * delivery, one blow landed — and the attribute creeps. Starting from 5, an
 * attribute reaches 25 in roughly 550 units, 50 in 1,800, 70 in 4,400 and 80
 * in 7,700. That is deliberately a long arc: a veteran should be a thing the
 * player remembers building.
 *
 * <p>{@link Attribute#WITS} multiplies the growth of everything, itself
 * included, which is the one compounding decision in the system: investing a
 * clever settler in schooling early pays for the rest of their life.
 */
public final class SettlerAttributes {

    /** The best a settler can arrive at. Above this must be trained. */
    public static final int START_CAP = 15;
    /** The hard clamp. Growth is asymptotic long before here. */
    public static final int CEILING = 99;

    /** How much one work unit is worth at value 0. Tuned; see the class note. */
    private static final float RATE = 0.05F;

    private final int[] value = new int[Attribute.COUNT];
    /** Sub-integer progress, so slow growth is not rounded away to nothing. */
    private final float[] progress = new float[Attribute.COUNT];
    private Attribute knack = Attribute.STRENGTH;

    private SettlerAttributes() {
    }

    // ------------------------------------------------------------- rolling ---

    /**
     * A newcomer. Low, uneven, and with one thing they are naturally better at.
     */
    public static SettlerAttributes roll(RandomSource random) {
        SettlerAttributes a = new SettlerAttributes();
        a.knack = Attribute.byOrdinal(random.nextInt(Attribute.COUNT));
        for (Attribute attribute : Attribute.ALL) {
            float u = random.nextFloat();
            // The exponent IS the design: 2.2 crushes the distribution towards
            // the bottom, 1.1 barely bends it. Flattening either of these would
            // quietly turn newcomers into heroes.
            double curve = Math.pow(u, attribute == a.knack ? 1.1 : 2.2);
            a.value[attribute.ordinal()] =
                Math.min(START_CAP, 1 + (int) (START_CAP * curve));
        }
        return a;
    }

    /** Everything at zero: for fixtures that want to test growth from scratch. */
    public static SettlerAttributes blank() {
        return new SettlerAttributes();
    }

    // -------------------------------------------------------------- values ---

    public int get(Attribute attribute) {
        return value[attribute.ordinal()];
    }

    public Attribute knack() {
        return knack;
    }

    /** 0..1, for drawing a bar or five pips without the caller doing maths. */
    public float ratio(Attribute attribute) {
        return get(attribute) / 100.0F;
    }

    /** 0..5 pips, the way the hire screen shows a value. */
    public int pips(Attribute attribute) {
        return Mth.clamp(Math.round(get(attribute) / 20.0F), 0, 5);
    }

    // -------------------------------------------------------------- growth ---

    /**
     * Does {@code units} of the work that trains this attribute.
     *
     * @param units      work actions completed — a chop, a till, a delivery
     * @param growthBoost trait multiplier, 1.0 for an ordinary settler
     * @return whether the visible integer changed, so callers can avoid syncing
     */
    public boolean train(Attribute attribute, float units, float growthBoost) {
        int index = attribute.ordinal();
        int current = value[index];
        if (current >= CEILING || units <= 0.0F) {
            return false;
        }
        float headroom = 1.0F - current / 100.0F;
        float wits = 1.0F + get(Attribute.WITS) / 200.0F;
        progress[index] += units * RATE * headroom * headroom * wits * growthBoost;
        if (progress[index] < 1.0F) {
            return false;
        }
        int gained = (int) progress[index];
        progress[index] -= gained;
        value[index] = Math.min(CEILING, current + gained);
        return value[index] != current;
    }

    /**
     * Takes points off every attribute, floored at 1.
     *
     * <p>This is what {@link Trait.Flag#SLOW_START} spends: the quick study
     * learns half again as fast, from a step further back on everything. A
     * trait that only gave would not be a character.
     */
    public void penalise(int points) {
        for (int i = 0; i < Attribute.COUNT; i++) {
            value[i] = Math.max(1, value[i] - points);
        }
    }

    // --------------------------------------------------------- persistence ---

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        int[] values = new int[Attribute.COUNT];
        System.arraycopy(value, 0, values, 0, Attribute.COUNT);
        tag.putIntArray("Values", values);
        tag.putByte("Knack", (byte) knack.ordinal());
        for (Attribute attribute : Attribute.ALL) {
            float partial = progress[attribute.ordinal()];
            if (partial > 0.0F) {
                tag.putFloat("P" + attribute.ordinal(), partial);
            }
        }
        return tag;
    }

    public static SettlerAttributes load(CompoundTag tag, RandomSource fallback) {
        // A settler saved before attributes existed has none; rolling gives
        // them a life rather than five zeroes.
        if (!tag.contains("Values")) {
            return roll(fallback);
        }
        SettlerAttributes a = new SettlerAttributes();
        int[] values = tag.getIntArray("Values");
        for (int i = 0; i < Attribute.COUNT && i < values.length; i++) {
            a.value[i] = Mth.clamp(values[i], 0, CEILING);
            a.progress[i] = tag.getFloat("P" + i);
        }
        a.knack = Attribute.byOrdinal(tag.getByte("Knack"));
        return a;
    }
}

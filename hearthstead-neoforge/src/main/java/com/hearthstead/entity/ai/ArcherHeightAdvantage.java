package com.hearthstead.entity.ai;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.AttributeRuntime;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.ArcherDrill;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;

/**
 * Archers who stand above their target shoot farther and truer (owner, 27 Sep:
 * "when they get up high they can get more range, and to keep it fair they get
 * more accurate so they actually hit").
 *
 * <p>This is the one place the ordinary (non-Tower-Post) shot range of an
 * Archer is computed, so target authority ({@code RaidThreatBoard},
 * {@link SettlerDefenseTargetGoal}) and the bow itself ({@link ArcherAttackGoal})
 * can never disagree about whether a raider is "in range":
 *
 * <ul>
 *   <li><b>Range:</b> the ordinary 18 blocks (plus the paid Longbow Drill and
 *       Perception, exactly as before) gains {@code heightRangePerBlock} per
 *       whole block the Archer's feet stand above the target's feet, capped at
 *       {@code heightRangeCap}. Ground-level Archers are unchanged.</li>
 *   <li><b>Accuracy:</b> the arrow spread shrinks with the same height
 *       advantage, to {@code 1 - heightAccuracyBonus} of normal at
 *       {@value #FULL_ACCURACY_HEIGHT} blocks and above.</li>
 * </ul>
 *
 * <p>The extra range is reachable with vanilla arrow physics: the bow uses the
 * Tower Post's ballistic lower-arc solve for every elevated shot (see
 * {@link ArcherAttackGoal#towerBallisticVerticalInput}), which only corrects the
 * aim; speed, gravity, damage and collision stay the vanilla Arrow's.</p>
 *
 * <p>{@code [archers]} in the server config. Safe before the config has loaded
 * (defaults).</p>
 */
public final class ArcherHeightAdvantage {
    public static final double DEFAULT_RANGE_PER_BLOCK = 1.0D;
    public static final double DEFAULT_RANGE_CAP = 8.0D;
    public static final double DEFAULT_ACCURACY_BONUS = 0.67D;
    /** Height advantage (blocks) at which the full accuracy bonus applies. */
    public static final double FULL_ACCURACY_HEIGHT = 6.0D;
    /** A height difference smaller than this is "level ground" (slabs, steps). */
    private static final double MIN_ADVANTAGE = 1.0D;

    private static ModConfigSpec.DoubleValue rangePerBlock;
    private static ModConfigSpec.DoubleValue rangeCap;
    private static ModConfigSpec.DoubleValue accuracyBonus;

    private ArcherHeightAdvantage() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s static builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Archers: shooting from height (towers, walls, roofs).").push("archers");
        rangePerBlock = builder.comment("Extra ordinary shot range (blocks) per block the Archer stands above the target.",
                "0 = height gives no extra range.")
            .defineInRange("heightRangePerBlock", DEFAULT_RANGE_PER_BLOCK, 0.0D, 3.0D);
        rangeCap = builder.comment("Most extra range height can give (blocks). Ordinary range is 18.")
            .defineInRange("heightRangeCap", DEFAULT_RANGE_CAP, 0.0D, 14.0D);
        accuracyBonus = builder.comment("Share of the arrow spread removed at a height advantage of 6+ blocks",
                "(scales linearly from 0 at level ground). 0 = no accuracy bonus.")
            .defineInRange("heightAccuracyBonus", DEFAULT_ACCURACY_BONUS, 0.0D, 0.9D);
        builder.pop();
    }

    /** Whole blocks the archer's feet stand above the target's feet (never negative). */
    public static double advantage(LivingEntity archer, @Nullable LivingEntity target) {
        if (archer == null || target == null) return 0.0D;
        double delta = Math.floor(archer.getY() - target.getY() + 1.0E-3D);
        return delta >= MIN_ADVANTAGE ? delta : 0.0D;
    }

    /** Extra ordinary range from a height advantage, in blocks. */
    public static double bonusRange(double advantage) {
        if (!(advantage >= MIN_ADVANTAGE)) return 0.0D;
        return Math.max(0.0D, Math.min(rangeCap(), advantage * rangePerBlock()));
    }

    /** Multiplier (0.1..1) on the arrow spread for a height advantage. */
    public static double spreadScale(double advantage) {
        if (!(advantage >= MIN_ADVANTAGE)) return 1.0D;
        double share = Math.min(1.0D, advantage / FULL_ACCURACY_HEIGHT);
        return Math.max(0.1D, 1.0D - accuracyBonus() * share);
    }

    /**
     * The ordinary (non-Tower-Post) shot range of this Archer against this
     * target: the base range, the paid Longbow Drill, Perception, and the
     * height bonus. The single authority for "in range" (see class doc).
     */
    public static double normalShotRange(@Nullable ServerLevel level,
                                         @Nullable Settlement settlement,
                                         SettlerEntity archer,
                                         @Nullable LivingEntity target) {
        double range = ArcherDrill.normalShotRange(level, settlement,
            ArcherAttackGoal.NORMAL_SHOT_RANGE);
        range = AttributeRuntime.range(archer, range);
        return range + bonusRange(advantage(archer, target));
    }

    public static double rangePerBlock() { return get(rangePerBlock, DEFAULT_RANGE_PER_BLOCK); }
    public static double rangeCap() { return get(rangeCap, DEFAULT_RANGE_CAP); }
    public static double accuracyBonus() { return get(accuracyBonus, DEFAULT_ACCURACY_BONUS); }

    private static double get(ModConfigSpec.DoubleValue v, double fallback) {
        try {
            return v == null || !HearthsteadServerConfig.SPEC.isLoaded() ? fallback : v.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}

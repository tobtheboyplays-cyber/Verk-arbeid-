package com.hearthstead.entity;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;

/**
 * Drunkenness (tavern lane, owner request 26 Sep). Presentation-first and bounded:
 * <ul>
 *   <li>Every real ale sip (TavernServingEntity DRINKING) adds one point; points decay by one per
 *       {@link #DECAY_TICKS} (two in-game minutes) and cap at {@link #MAX_POINTS}. Level =
 *       points rounded UP, so every ale lasts its full two minutes and a second ale drunk while
 *       the first still lingers makes you drunk: 1 TIPSY (a slight sway only), 2 DRUNK (weaving,
 *       -25% speed, stumbles), 3+ VERY (stronger weave, -40%, lean / sit stops and the odd fall).
 *       The level is synced; points + a game-time stamp are saved and the elapsed wear-off is
 *       applied on load.</li>
 *   <li>Guards and archers never get drunk (the watch stays safe); panic, a raid alert or combat
 *       sober the MOVEMENT at once (no slowdown, no weave, no stumble or fall).</li>
 *   <li>Work slows (-10% drunk, -20% very drunk) through AttributeRuntime's work cut.</li>
 *   <li>Weaving is a small sideways drift layered on the walk, never toward an unsafe cell
 *       (no floor, a drop, water or lava, or a blocked body box).</li>
 *   <li>Kill switch: {@code [livingVillage] drunkenness} (false = nobody gets drunk).</li>
 * </ul>
 */
public final class Drunkenness {
    public static final int SOBER = 0, TIPSY = 1, DRUNK = 2, VERY = 3;
    public static final int DECAY_TICKS = 2400;
    public static final double MAX_POINTS = 4.0;
    public static final ResourceLocation SPEED_ID = Hearthstead.id("drunk_speed");

    /** Server-authored visual cues (synced with a start tick; the client plays the clip). */
    public static final int CUE_NONE = 0, CUE_STUMBLE = 1, CUE_FALL_FORWARD = 2, CUE_FALL_SIDE = 3,
        CUE_LEAN = 4, CUE_SIT = 5;
    public static final int STUMBLE_TICKS = 32, FALL_TICKS = 100, LEAN_TICKS = 100, SIT_TICKS = 120;

    private static ModConfigSpec.BooleanValue enabled;
    /** GameTests may force the switch; null = the configured value. */
    @Nullable
    public static volatile Boolean testOverride;

    private Drunkenness() {
    }

    // ------------------------------------------------------------------ config

    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Living-village extras (tavern lane).").push("livingVillage");
        enabled = builder
            .comment("Drunkenness: settlers who drink ale at the Tavern get tipsy (1 ale), drunk (2) or",
                "very drunk (3+): they walk slower and weave, stumble, may lean, sit or fall (visual,",
                "no damage), work a little slower and are a little happier; it wears off over a few",
                "in-game minutes. Guards and archers never get drunk. false = nobody gets drunk.")
            .define("drunkenness", true);
        builder.pop();
    }

    public static boolean enabled(@Nullable MinecraftServer server) {
        Boolean o = testOverride;
        if (o != null) return o;
        if (enabled == null) return true;
        try {
            return enabled.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    // ------------------------------------------------------------------ pure rules

    private static final double EPS = 1e-6;

    /**
     * Points rounded up: one ale (1.0) stays TIPSY until it has fully worn off (tick 2400), and any
     * leftover of an earlier ale lifts the next one to DRUNK. Exactly-whole values keep their level.
     */
    public static int level(double points) {
        if (!(points > EPS)) return SOBER;
        return (int) Math.min(VERY, Math.ceil(points - EPS));
    }

    /** Continuous wear-off, one point per {@link #DECAY_TICKS}; residue below EPS snaps to sober. */
    public static double decay(double points, long ticks) {
        double p = points - Math.max(0L, ticks) / (double) DECAY_TICKS;
        return p > EPS ? p : 0.0;
    }

    /**
     * Saved points after a reload: bounded (NaN / negative = sober, capped at MAX_POINTS) with the
     * wear-off for the game time that passed since {@code stamp} (never negative).
     */
    public static double loadPoints(double saved, long stamp, long now) {
        if (!(saved > 0.0)) return 0.0;
        return decay(Math.min(MAX_POINTS, saved), Math.max(0L, now - stamp));
    }

    public static double addAle(double points) {
        return Math.min(MAX_POINTS, points + 1.0);
    }

    /** Movement speed change (ADD_MULTIPLIED_BASE): a tipsy glow does not slow anyone. */
    public static double speedPenalty(int level) {
        return level >= VERY ? -0.40 : level == DRUNK ? -0.25 : 0.0;
    }

    /** Signed work-time cut (negative = slower work). */
    public static double workCut(int level) {
        return level >= VERY ? -0.20 : level == DRUNK ? -0.10 : 0.0;
    }

    /** Sideways weave amplitude (blocks) of the walk. */
    public static double weaveAmplitude(int level) {
        return level >= VERY ? 0.55 : level == DRUNK ? 0.3 : 0.0;
    }

    /** Lateral weave offset at {@code seconds}: a slow sine plus a second, detuned one (per person). */
    public static double weave(long seed, double seconds, int level) {
        double a = weaveAmplitude(level);
        if (a == 0.0) return 0.0;
        double p1 = 2.2 + 1.2 * TavernTableMath.hash01(seed, 301, 1), p2 = 0.9 + 0.5 * TavernTableMath.hash01(seed, 301, 2);
        double ph = TavernTableMath.hash01(seed, 301, 3) * Math.PI * 2;
        return a * (0.75 * Math.sin(2 * Math.PI * seconds / p1 + ph) + 0.25 * Math.sin(2 * Math.PI * seconds / p2 + 2 * ph));
    }

    public static boolean mayGetDrunk(SettlerEntity settler) {
        return settler != null && mayGetDrunk(settler.getProfession());
    }

    /** Guards and archers (every martial job) keep the watch sober. */
    public static boolean mayGetDrunk(Profession profession) {
        return profession != null && !profession.martial();
    }

    /** Movement is sobered while fleeing, fighting or under a raid alert. */
    public static boolean movementSobered(SettlerEntity s) {
        SettlerActivity a = s.getActivity();
        if (a == SettlerActivity.FLEEING || a == SettlerActivity.COMBAT || a == SettlerActivity.RETREATING
            || s.getTarget() != null || s.hurtTime > 0) return true;
        var settlement = s.settlement();
        return settlement != null && (settlement.pendingRaid != null
            || settlement.alertUntilGameTime > s.level().getGameTime());
    }

    /**
     * A cell a weaving (or falling) settler may enter: sturdy floor directly below, no drop, no
     * water or lava in or around the feet and head cells, no collision in the body.
     */
    public static boolean safeCell(BlockGetter level, BlockPos feet) {
        BlockPos below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        for (BlockPos p : new BlockPos[] {feet, feet.above(), below}) {
            var fluid = level.getFluidState(p);
            if (!fluid.isEmpty() && (fluid.is(FluidTags.LAVA) || fluid.is(FluidTags.WATER))) return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            var f = level.getFluidState(feet.relative(d));
            if (!f.isEmpty() && f.is(FluidTags.LAVA)) return false;
        }
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** Flat, safe ground for a fall: the cell and its four neighbours are all safe and level. */
    public static boolean safeToFall(BlockGetter level, BlockPos feet) {
        if (!safeCell(level, feet)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!safeCell(level, feet.relative(d))) return false;
        return true;
    }

    // ------------------------------------------------------------------ server

    /** Applies the movement modifier for this level (removed when sobered or off). */
    public static void applySpeed(SettlerEntity s, int level) {
        applySpeed(s, level, movementSobered(s));
    }

    /** As above with the sobered state already known; sobered = the modifier goes at once. */
    public static void applySpeed(SettlerEntity s, int level, boolean sobered) {
        AttributeInstance speed = s.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double want = sobered ? 0.0 : speedPenalty(level);
        AttributeModifier current = speed.getModifier(SPEED_ID);
        double have = current == null ? 0.0 : current.amount();
        if (Math.abs(have - want) < 1e-6) return;
        if (Math.abs(want) < 1e-6) speed.removeModifier(SPEED_ID);
        else speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_ID, want,
            AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /** A small sideways push while walking; skipped toward any unsafe cell. */
    public static void weaveStep(SettlerEntity s, int level) {
        if (level < DRUNK || movementSobered(s) || s.getNavigation().isDone() || !s.onGround()
            || s.isPassenger()) return;
        Vec3 v = s.getDeltaMovement();
        double h = Math.hypot(v.x, v.z);
        if (h < 0.02) return;
        long seed = s.getUUID().getLeastSignificantBits();
        double t = s.level().getGameTime() / 20.0;
        // velocity of the weave offset (its derivative) -> a lateral nudge
        double dw = (weave(seed, t + 0.05, level) - weave(seed, t - 0.05, level)) / 0.1;
        Vec3 side = new Vec3(-v.z / h, 0, v.x / h);
        Vec3 push = side.scale(dw * 0.05);
        BlockPos next = BlockPos.containing(s.position().add(side.scale(Math.signum(dw) * 0.6)));
        if (!safeCell(s.level(), next)) return;
        s.setDeltaMovement(v.add(push.x, 0, push.z));
    }

    public static boolean onDuty(SettlerEntity s) {
        return !mayGetDrunk(s);
    }

    /** Stable per-person seed. */
    public static long seed(SettlerEntity s) {
        return s.getUUID().getLeastSignificantBits() ^ s.getUUID().getMostSignificantBits();
    }

    /** The GameTest server keeps drunkenness off unless a batch opts in. */
    public static boolean enabledIn(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server instanceof GameTestServer && testOverride == null) return false;
        return enabled(server);
    }
}

package com.hearthstead.entity.combat.captain;

import javax.annotation.Nullable;

/**
 * Hero Captain animation contract (plan/CAPTAIN.md), for the motion lane.
 *
 * <p>Entity events: {@link #EV_CAPTAIN_BASE} + {@link CaptainSpecial#ordinal()} (100..121) start
 * that special's authored one-shot; {@link #EV_AXE_SWING_A}..{@link #EV_DUAL_LIGHT_B} (122..125)
 * the plain-swing variants. Clips are ABSOLUTE full-body clips exported to
 * {@code animations/settler/<const lower>.animation.json} by
 * {@code tools/blender/pipeline/clips/captain/}; each one-shot starts and ends on its loadout's
 * stance clip ({@link #stanceClip}), which is the hero's guard stance while he holds that kit.
 *
 * <p>Until the motion lane wires these events ({@link #WIRED}), the goals keep broadcasting the
 * Guard fallback events so something visible always plays.
 */
public final class CaptainClips {
    public static final byte EV_CAPTAIN_BASE = 100;
    public static final byte EV_AXE_SWING_A = 122;
    public static final byte EV_AXE_SWING_B = 123;
    public static final byte EV_DUAL_LIGHT_A = 124;
    public static final byte EV_DUAL_LIGHT_B = 125;
    /** Flip once SettlerEntity/SettlerModel play the captain events (motion lane). */
    public static final boolean WIRED = false;

    private CaptainClips() {
    }

    public static byte event(CaptainSpecial special) {
        return (byte) (EV_CAPTAIN_BASE + special.ordinal());
    }

    /** The authored clip constant for a special, or null while it only has a fallback. */
    @Nullable
    public static String clip(CaptainSpecial special) {
        return switch (special) {
            case BLADE_WHIRL, TWIN_THRUST, DISARM, DODGE_STEP, SPINNING_CHOP, ARMOUR_BREAKER,
                 HALBERD_SWEEP, BRACE_CHARGE, HOOK_PULL, SHIELD_BREAKER, GROUND_SLAM, CRUSHING_BLOW ->
                "CAPTAIN_" + special.name();
            case AXE_CLEAVE -> "CAPTAIN_AXE_CLEAVE";
            default -> null;   // common + sword&shield: anim lane; bow: after the archer shot cycle
        };
    }

    @Nullable
    public static String stanceClip(CaptainLoadout loadout) {
        return switch (loadout) {
            case DUAL_SWORDS -> "CAPTAIN_DUAL_STANCE";
            case GREAT_AXE -> "CAPTAIN_AXE_STANCE";
            case HALBERD -> "CAPTAIN_HALBERD_STANCE";
            case WARHAMMER -> "CAPTAIN_HAMMER_STANCE";
            default -> null;
        };
    }

    /** Which event to broadcast for a special right now. */
    public static byte startEvent(CaptainSpecial special) {
        return WIRED && clip(special) != null ? event(special) : special.fallbackEvent();
    }
}

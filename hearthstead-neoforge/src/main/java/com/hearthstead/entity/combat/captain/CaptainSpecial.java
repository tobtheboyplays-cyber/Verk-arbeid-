package com.hearthstead.entity.combat.captain;

import com.hearthstead.entity.SettlerEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Every hero-Captain special as pure data (plan/CAPTAIN.md). Ticks are 20 Hz
 * server ticks from the start of the telegraph. Every special:
 * <ul>
 *   <li>telegraphs for {@link #windupTicks()} (a readable wind-up with a VFX
 *       and sound hook), then resolves ONCE;</li>
 *   <li>has its own cooldown ({@code SECOND_WIND} is once per fight);</li>
 *   <li>hits only authorized enemies of the settlement: never a settler, a
 *       player, a pet or a bystander (friendly fire off).</li>
 * </ul>
 * {@link #fxId()} is the hook id the fx and sound lanes key their effects on:
 * {@code captain.<id>.windup} and {@code captain.<id>.impact}.
 */
public enum CaptainSpecial {
    // ------------------------------------------------------------- common
    RALLY_CRY(null, "rally_cry", 20, 1200, 12.0D, SettlerEntity.EV_CELEBRATE),
    SECOND_WIND(null, "second_wind", 10, 0, 0.0D, SettlerEntity.EV_CELEBRATE),
    EXECUTION(null, "execution", 16, 400, 3.0D, SettlerEntity.EV_GUARD_FINISHER),

    // ------------------------------------------------------ sword & shield
    SHIELD_CHARGE(CaptainLoadout.SWORD_SHIELD, "shield_charge", 10, 300, 8.0D, SettlerEntity.EV_GUARD_SHIELD_BASH),
    HOLD_THE_LINE(CaptainLoadout.SWORD_SHIELD, "hold_the_line", 12, 600, 10.0D, SettlerEntity.EV_SHIELD_BLOCK),
    POMMEL_STUN(CaptainLoadout.SWORD_SHIELD, "pommel_stun", 8, 200, 3.0D, SettlerEntity.EV_GUARD_SHIELD_BASH),

    // --------------------------------------------------------- dual swords
    BLADE_WHIRL(CaptainLoadout.DUAL_SWORDS, "blade_whirl", 10, 260, 2.8D, SettlerEntity.EV_GUARD_FINISHER),
    TWIN_THRUST(CaptainLoadout.DUAL_SWORDS, "twin_thrust", 6, 120, 3.0D, SettlerEntity.EV_GUARD_LIGHT_B),
    DISARM(CaptainLoadout.DUAL_SWORDS, "disarm", 8, 400, 3.0D, SettlerEntity.EV_MELEE),
    DODGE_STEP(CaptainLoadout.DUAL_SWORDS, "dodge_step", 0, 100, 3.0D, SettlerEntity.EV_GUARD_STAGGER),

    // ----------------------------------------------------------- great axe
    AXE_CLEAVE(CaptainLoadout.GREAT_AXE, "axe_cleave", 14, 200, 3.0D, SettlerEntity.EV_GUARD_FINISHER),
    SPINNING_CHOP(CaptainLoadout.GREAT_AXE, "spinning_chop", 12, 320, 3.2D, SettlerEntity.EV_GUARD_FINISHER),
    ARMOUR_BREAKER(CaptainLoadout.GREAT_AXE, "armour_breaker", 16, 300, 3.0D, SettlerEntity.EV_GUARD_HEAVY),

    // ----------------------------------------------------------------- bow
    ARROW_VOLLEY(CaptainLoadout.BOW, "arrow_volley", 20, 400, 24.0D, SettlerEntity.EV_ARCHER_LOOSE),
    PIERCING_SHOT(CaptainLoadout.BOW, "piercing_shot", 16, 240, 24.0D, SettlerEntity.EV_ARCHER_LOOSE),
    MARK_TARGET(CaptainLoadout.BOW, "mark_target", 10, 600, 24.0D, SettlerEntity.EV_ARCHER_LOOSE),

    // ------------------------------------------------------------- halberd
    HALBERD_SWEEP(CaptainLoadout.HALBERD, "halberd_sweep", 12, 200, 3.5D, SettlerEntity.EV_GUARD_FINISHER),
    BRACE_CHARGE(CaptainLoadout.HALBERD, "brace_charge", 4, 240, 3.5D, SettlerEntity.EV_GUARD_SHIELD_BASH),
    HOOK_PULL(CaptainLoadout.HALBERD, "hook_pull", 10, 300, 6.0D, SettlerEntity.EV_MELEE),

    // ----------------------------------------------------------- warhammer
    SHIELD_BREAKER(CaptainLoadout.WARHAMMER, "shield_breaker", 14, 240, 3.0D, SettlerEntity.EV_GUARD_HEAVY),
    GROUND_SLAM(CaptainLoadout.WARHAMMER, "ground_slam", 18, 400, 3.5D, SettlerEntity.EV_GUARD_HEAVY),
    CRUSHING_BLOW(CaptainLoadout.WARHAMMER, "crushing_blow", 20, 260, 3.0D, SettlerEntity.EV_GUARD_HEAVY);

    // ------------------------------------------------------------- tuning
    public static final int RALLY_TICKS = 200;
    public static final float SECOND_WIND_BELOW = 0.30F;
    public static final float SECOND_WIND_HEAL_SHARE = 0.40F;
    public static final float EXECUTE_BELOW = 0.25F;
    public static final int HOLD_TICKS = 160;
    public static final int STUN_TICKS = 40;
    public static final int SLAM_STUN_TICKS = 30;
    public static final int MARK_TICKS = 200;
    public static final float MARK_DAMAGE_BONUS = 0.25F;
    public static final int DISARM_TICKS = 100;
    public static final int SHRED_TICKS = 200;
    public static final double SHRED_ARMOR = -6.0D;
    public static final double ARMOUR_BREAKER_BRUTE_BONUS = 1.5D;
    public static final double CHARGE_LINE_HALF_WIDTH = 1.2D;
    public static final int VOLLEY_WAVES = 3;
    public static final int VOLLEY_WAVE_GAP = 6;
    public static final double VOLLEY_RADIUS = 3.0D;
    public static final float VOLLEY_DAMAGE = 3.0F;
    public static final float PIERCE_DAMAGE = 8.0F;
    public static final double PIERCE_HALF_WIDTH = 0.8D;
    /** One fight ends after this long with no enemy in sight (resets Second Wind). */
    public static final int FIGHT_GAP_TICKS = 600;

    @Nullable private final CaptainLoadout loadout;
    private final String id;
    private final int windup;
    private final int cooldown;
    private final double range;
    private final byte fallbackEvent;

    CaptainSpecial(@Nullable CaptainLoadout loadout, String id, int windup, int cooldown,
                   double range, byte fallbackEvent) {
        this.loadout = loadout;
        this.id = id;
        this.windup = windup;
        this.cooldown = cooldown;
        this.range = range;
        this.fallbackEvent = fallbackEvent;
    }

    /** Null = common to every loadout. */
    @Nullable public CaptainLoadout loadout() { return loadout; }
    public String id() { return id; }
    public int windupTicks() { return windup; }
    public int cooldownTicks() { return cooldown; }
    public double range() { return range; }
    public byte fallbackEvent() { return fallbackEvent; }
    public boolean oncePerFight() { return this == SECOND_WIND; }

    public String fxId() {
        return "captain." + id;
    }

    /** The common three plus the loadout's own, in AI preference order is decided by CaptainBrain. */
    public static List<CaptainSpecial> available(CaptainLoadout loadout) {
        List<CaptainSpecial> out = new ArrayList<>(List.of(RALLY_CRY, SECOND_WIND, EXECUTION));
        out.addAll(loadout.specials());
        return out;
    }
}

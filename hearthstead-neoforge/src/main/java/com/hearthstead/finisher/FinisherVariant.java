package com.hearthstead.finisher;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every authored execution. Timings are CLIP ticks at 20 Hz and must match the
 * Blender-authored JSON clips in {@code assets/hearthstead/animations/player/}
 * (actor) and {@code .../raider/} or {@code .../goblin/} (victim), exported by
 * {@code tools/blender/pipeline/clips/finisher/}.
 *
 * <p>The runtime inserts {@link FinisherTimeline#HIT_STOP_TICKS} frozen ticks
 * at {@link #impactTick()} (the shared hit-stop every viewer sees), so a
 * variant's real lock lasts {@code lengthTicks + HIT_STOP_TICKS}. The victim
 * dies on the server at {@code start + impactTick}.</p>
 *
 * <p>Every move has its own victim clip ({@link #victimReaction()} is the
 * move id), so the reaction is authored against that exact choreography;
 * the rig folder (raider / goblin) is chosen by the renderer.</p>
 */
public enum FinisherVariant {
    /** Beat the blade aside, shoulder-shove, then a driving thrust under the ribs. */
    SWORD_PARRY_THRUST("sword_parry_thrust", weapons(WeaponClass.SWORD),
        enemies(EnemyClass.SKIRMISHER, EnemyClass.CAPTAIN, EnemyClass.OTHER),
        24, 14, 1.60D, "sword_parry_thrust", false, 3),
    /** Knee to the belly, then a rising diagonal cut that spins the victim away. */
    SWORD_RISING_CUT("sword_rising_cut", weapons(WeaponClass.SWORD),
        enemies(EnemyClass.SKIRMISHER, EnemyClass.OTHER),
        22, 13, 1.40D, "sword_rising_cut", false, 3),
    /** Axe beard hooks the leg out from under, victim drops to a knee, overhead chop. */
    AXE_HOOK_CHOP("axe_hook_chop", weapons(WeaponClass.AXE),
        enemies(EnemyClass.SKIRMISHER, EnemyClass.CAPTAIN, EnemyClass.OTHER),
        26, 17, 1.65D, "axe_hook_chop", false, 3),
    /** Haft butt into the face, victim reels, one long diagonal cleave. */
    AXE_HAFT_CLEAVE("axe_haft_cleave", weapons(WeaponClass.AXE),
        enemies(EnemyClass.SKIRMISHER, EnemyClass.OTHER),
        24, 15, 1.40D, "axe_haft_cleave", false, 2),
    /** Haft jab to the gut folds the victim, then the head comes down on its back. */
    MACE_GUT_SLAM("mace_gut_slam", weapons(WeaponClass.MACE),
        enemies(EnemyClass.SKIRMISHER, EnemyClass.CAPTAIN, EnemyClass.OTHER),
        24, 16, 1.60D, "mace_gut_slam", false, 3),
    /** Grab the collar, knee to the gut, hammer-fist down onto the back of the neck. */
    BARE_COLLAR_THROW("bare_collar_throw", weapons(WeaponClass.BARE),
        enemies(EnemyClass.SKIRMISHER, EnemyClass.CAPTAIN, EnemyClass.OTHER),
        24, 15, 1.00D, "bare_collar_throw", false, 3),
    /** Cut behind the knee, the Brute drops to one knee, overhead two-handed finish. */
    BRUTE_KNEE_BUCKLE("brute_knee_buckle",
        weapons(WeaponClass.SWORD, WeaponClass.AXE, WeaponClass.MACE, WeaponClass.BARE),
        enemies(EnemyClass.BRUTE),
        30, 20, 2.05D, "brute_knee_buckle", false, 3),
    /** Step onto the buckled Brute's thigh, rise above it and drive down at the collar. */
    BRUTE_CLIMB_STRIKE("brute_climb_strike", weapons(WeaponClass.SWORD, WeaponClass.AXE),
        enemies(EnemyClass.BRUTE),
        30, 20, 1.60D, "brute_climb_strike", false, 2),
    /** Bind the captain's weapon, rip it away in a shower of sparks, drive through. */
    CAPTAIN_DISARM_DRIVE("captain_disarm_drive",
        weapons(WeaponClass.SWORD, WeaponClass.AXE, WeaponClass.MACE),
        enemies(EnemyClass.CAPTAIN),
        28, 19, 1.50D, "captain_disarm_drive", true, 4),
    /** Grab the goblin by the scruff, hoist it, slam it flat. */
    GOBLIN_SCRUFF_SLAM("goblin_scruff_slam",
        weapons(WeaponClass.SWORD, WeaponClass.AXE, WeaponClass.MACE, WeaponClass.BARE),
        enemies(EnemyClass.GOBLIN),
        20, 12, 0.85D, "goblin_scruff_slam", false, 3),
    /**
     * Co-op: the lead hooks and pins the victim to its knees from the side,
     * the partner delivers the overhead from the front.
     */
    DOUBLE_PIN_EXECUTION("double_pin", weapons(WeaponClass.SWORD, WeaponClass.AXE,
        WeaponClass.MACE, WeaponClass.BARE),
        enemies(EnemyClass.BRUTE, EnemyClass.CAPTAIN),
        32, 22, 1.30D, "double_pin", true, 1);

    /** Partner stands in FRONT of the victim at this distance; the lead at its left. */
    public static final double DOUBLE_PARTNER_DISTANCE = 1.95D;
    /** Degrees around the victim (from its facing) the lead stands at in a double. */
    public static final float DOUBLE_LEAD_ANGLE = 90.0F;

    private final String id;
    private final Set<WeaponClass> weapons;
    private final Set<EnemyClass> enemies;
    private final int lengthTicks;
    private final int impactTick;
    private final double actorDistance;
    private final String victimReaction;
    private final boolean sparks;
    private final int weight;

    FinisherVariant(String id, Set<WeaponClass> weapons, Set<EnemyClass> enemies,
                    int lengthTicks, int impactTick, double actorDistance,
                    String victimReaction, boolean sparks, int weight) {
        this.id = id;
        this.weapons = weapons;
        this.enemies = enemies;
        this.lengthTicks = lengthTicks;
        this.impactTick = impactTick;
        this.actorDistance = actorDistance;
        this.victimReaction = victimReaction;
        this.sparks = sparks;
        this.weight = weight;
    }

    public String id() { return id; }
    public Set<WeaponClass> weapons() { return weapons; }
    public Set<EnemyClass> enemies() { return enemies; }
    public int lengthTicks() { return lengthTicks; }
    public int impactTick() { return impactTick; }
    public double actorDistance() { return actorDistance; }
    public String victimReaction() { return victimReaction; }
    public boolean sparks() { return sparks; }
    public int weight() { return weight; }
    public boolean isDouble() { return this == DOUBLE_PIN_EXECUTION; }

    /** Real ticks the executor is locked for, including the shared hit-stop. */
    public int lockTicks() {
        return lengthTicks + FinisherTimeline.HIT_STOP_TICKS;
    }

    public boolean fits(WeaponClass weapon, EnemyClass enemy) {
        return weapons.contains(weapon) && enemies.contains(enemy);
    }

    /** Clip key the lead (or solo) executor plays. */
    public String leadClipKey() {
        return isDouble() ? "player/finisher_" + id + "_lead" : "player/finisher_" + id;
    }

    /** Clip key the double's partner plays (null for solo moves). */
    public String partnerClipKey() {
        return isDouble() ? "player/finisher_" + id + "_partner" : null;
    }

    /** Victim clip key on the given rig folder ("raider" or "goblin"). */
    public String victimClipKey(String rig) {
        return rig + "/finisher_victim_" + victimReaction;
    }

    public static FinisherVariant byOrdinal(int ordinal) {
        FinisherVariant[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    private static Set<WeaponClass> weapons(WeaponClass first, WeaponClass... rest) {
        return java.util.Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    private static Set<EnemyClass> enemies(EnemyClass first, EnemyClass... rest) {
        return java.util.Collections.unmodifiableSet(EnumSet.of(first, rest));
    }
}

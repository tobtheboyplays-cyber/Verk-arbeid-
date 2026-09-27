package com.hearthstead.entity.ai;

/**
 * The 26 Sep civilian-safety pass as one switch: threat-aware panic shelter
 * ({@link SettlerPanicGoal}), ranked raider targets
 * ({@link RaiderSettlerTargetGoal}) and the defender-sighting ALARM
 * ({@link SettlerDefenseTargetGoal}). Always on in play. It exists so one
 * GameTest run can measure the same raid scenario before and after; a test
 * that turns it off must restore it.
 */
public final class CivilianSafety {
    public static volatile boolean enabled = true;

    private CivilianSafety() {
    }
}

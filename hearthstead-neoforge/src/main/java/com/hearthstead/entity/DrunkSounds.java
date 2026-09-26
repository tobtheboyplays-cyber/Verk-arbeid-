package com.hearthstead.entity;

import com.hearthstead.Hearthstead;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Client-side hiccups and burps of a drunk settler (tavern lane). Positional, quiet, pitch-varied
 * per play, on a per-person deterministic schedule (never in unison): each second of game time is
 * a window with a small, level-dependent chance, fired on a tick offset of its own. The stumble /
 * fall sounds ride their clips instead (contact frames).
 */
public final class DrunkSounds {
    private static final SoundEvent HICCUP = SoundEvent.createVariableRangeEvent(Hearthstead.id("tavern.hiccup"));
    private static final SoundEvent BURP = SoundEvent.createVariableRangeEvent(Hearthstead.id("tavern.burp"));

    private DrunkSounds() {
    }

    /** Chance per one-second window: a tipsy glow hiccups now and then, the very drunk more often. */
    public static double hiccupChance(int level) {
        return level >= Drunkenness.VERY ? 1.0 / 9.0 : level == Drunkenness.DRUNK ? 1.0 / 14.0
            : level == Drunkenness.TIPSY ? 1.0 / 30.0 : 0.0;
    }

    public static double burpChance(int level) {
        return level >= Drunkenness.DRUNK ? 1.0 / 70.0 : 0.0;
    }

    public static void clientTick(SettlerEntity e) {
        int level = e.drunkLevel();
        if (level <= 0 || !e.isAlive()) return;
        long now = e.level().getGameTime();
        long seed = Drunkenness.seed(e);
        long window = Math.floorDiv(now, 20L);
        int at = (int) (TavernTableMath.hash01(seed, window, 401) * 20);
        if (Math.floorMod(now, 20L) != at) return;
        double roll = TavernTableMath.hash01(seed, window, 402);
        SoundEvent sound = roll < hiccupChance(level) ? HICCUP
            : roll < hiccupChance(level) + burpChance(level) ? BURP : null;
        if (sound == null) return;
        float pitch = 0.9F + 0.25F * (float) TavernTableMath.hash01(seed, window, 403);
        e.level().playLocalSound(e.getX(), e.getY() + 1.4, e.getZ(), sound, SoundSource.NEUTRAL,
            sound == HICCUP ? 0.22F : 0.18F, pitch, false);
    }
}

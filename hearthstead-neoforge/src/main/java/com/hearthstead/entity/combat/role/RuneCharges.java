package com.hearthstead.entity.combat.role;

import net.minecraft.nbt.CompoundTag;

/**
 * One Rune Mage's spell economy: charges, their slow recharge, per-spell
 * cooldowns and a short global cooldown. Pure state driven by an explicit
 * game-time argument, so it is fully unit-testable and survives reload
 * (plan/BATTLE-ROLES.md §4, "the limiters").
 *
 * <p>Charges are spent at RELEASE, not at the start of the channel: an
 * interrupted cast keeps its charge but still pays the cooldown (you were
 * seen drawing the rune; you lost the moment).
 */
public final class RuneCharges {
    public static final int MAX_CHARGES = 3;
    /** One charge back every 60 s. */
    public static final int RECHARGE_TICKS = 1200;
    /** Minimum gap between any two releases. */
    public static final int GLOBAL_COOLDOWN_TICKS = 20;
    private static final long NONE = Long.MIN_VALUE;

    private int charges = MAX_CHARGES;
    /** Start of the current recharge period, or NONE while full. */
    private long rechargeFrom = NONE;
    private final long[] readyAt = new long[RuneSpell.values().length];
    private long globalReadyAt = NONE;
    private int stonesUsed;
    private int spellsReleased;

    public RuneCharges() {
        java.util.Arrays.fill(readyAt, NONE);
    }

    public int charges() {
        return charges;
    }

    public int stonesUsed() {
        return stonesUsed;
    }

    public int spellsReleased() {
        return spellsReleased;
    }

    /** Applies every recharge that has come due by {@code now}. */
    public void update(long now) {
        if (charges >= MAX_CHARGES) {
            rechargeFrom = NONE;
            return;
        }
        if (rechargeFrom == NONE) {
            rechargeFrom = now;
            return;
        }
        while (charges < MAX_CHARGES && now - rechargeFrom >= RECHARGE_TICKS) {
            charges++;
            rechargeFrom += RECHARGE_TICKS;
        }
        if (charges >= MAX_CHARGES) {
            rechargeFrom = NONE;
        }
    }

    /** Ticks until the next natural charge, or 0 when full. */
    public int ticksToNextCharge(long now) {
        if (charges >= MAX_CHARGES || rechargeFrom == NONE) {
            return 0;
        }
        return (int) Math.max(0L, RECHARGE_TICKS - (now - rechargeFrom));
    }

    public boolean offCooldown(RuneSpell spell, long now) {
        long ready = readyAt[spell.ordinal()];
        return (ready == NONE || now >= ready)
            && (globalReadyAt == NONE || now >= globalReadyAt);
    }

    public int cooldownLeft(RuneSpell spell, long now) {
        long ready = readyAt[spell.ordinal()];
        return ready == NONE ? 0 : (int) Math.max(0L, ready - now);
    }

    /** Charges and cooldown both allow this spell right now. */
    public boolean canCast(RuneSpell spell, long now) {
        update(now);
        return charges >= spell.cost() && offCooldown(spell, now);
    }

    /**
     * Pays for one release: spends the charges and starts the cooldowns.
     * Returns false (and changes nothing) if the spell was not castable.
     */
    public boolean release(RuneSpell spell, long now) {
        if (!canCast(spell, now)) {
            return false;
        }
        boolean wasFull = charges >= MAX_CHARGES;
        charges -= spell.cost();
        if (wasFull) {
            rechargeFrom = now;
        }
        readyAt[spell.ordinal()] = now + spell.cooldownTicks();
        globalReadyAt = now + GLOBAL_COOLDOWN_TICKS;
        spellsReleased++;
        return true;
    }

    /** An interrupted channel: no charge spent, but the cooldown is. */
    public void interrupt(RuneSpell spell, long now) {
        readyAt[spell.ordinal()] = now + spell.cooldownTicks();
        globalReadyAt = now + GLOBAL_COOLDOWN_TICKS;
    }

    /**
     * Inscribes rune stones: +1 charge each, never above max. Returns how
     * many stones were actually used, so the caller consumes exactly that
     * many physical items.
     */
    public int inscribe(int stonesAvailable, long now) {
        update(now);
        int used = Math.max(0, Math.min(stonesAvailable, MAX_CHARGES - charges));
        charges += used;
        stonesUsed += used;
        if (charges >= MAX_CHARGES) {
            rechargeFrom = NONE;
        }
        return used;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Charges", charges);
        tag.putLong("RechargeFrom", rechargeFrom);
        tag.putLongArray("ReadyAt", readyAt.clone());
        tag.putLong("GlobalReadyAt", globalReadyAt);
        tag.putInt("StonesUsed", stonesUsed);
        tag.putInt("Released", spellsReleased);
        return tag;
    }

    public static RuneCharges load(CompoundTag tag) {
        RuneCharges c = new RuneCharges();
        if (tag == null || !tag.contains("Charges")) {
            return c;
        }
        c.charges = Math.max(0, Math.min(MAX_CHARGES, tag.getInt("Charges")));
        c.rechargeFrom = tag.getLong("RechargeFrom");
        long[] saved = tag.getLongArray("ReadyAt");
        for (int i = 0; i < Math.min(saved.length, c.readyAt.length); i++) {
            c.readyAt[i] = saved[i];
        }
        c.globalReadyAt = tag.getLong("GlobalReadyAt");
        c.stonesUsed = Math.max(0, tag.getInt("StonesUsed"));
        c.spellsReleased = Math.max(0, tag.getInt("Released"));
        if (c.charges >= MAX_CHARGES) {
            c.rechargeFrom = NONE;
        }
        return c;
    }
}

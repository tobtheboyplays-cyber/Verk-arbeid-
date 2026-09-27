package com.hearthstead.entity.combat.captain;

import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * One Captain's persistent kit state: chosen loadout, per-special cooldowns,
 * the once-per-fight Second Wind, the re-arm window after a loadout switch
 * and the player's cosmetic choices. Pure and explicit-clock, so JUnit covers
 * every rule; saved on the settler's persistent data.
 */
public final class CaptainState {
    public static final String KEY = "HearthsteadCaptain";
    /** Switching loadouts takes this long before any special may fire. */
    public static final int REARM_TICKS = 60;
    private static final long NONE = Long.MIN_VALUE;

    private CaptainLoadout loadout = CaptainLoadout.SWORD_SHIELD;
    private final Map<CaptainSpecial, Long> readyAt = new EnumMap<>(CaptainSpecial.class);
    private boolean secondWindUsed;
    private long fightStart = NONE;
    private long lastEnemySeen = NONE;
    private long rearmUntil = NONE;
    /** Dye id for the cape, -1 = the outfit's own. */
    private int capeColour = -1;
    private boolean plume = true;
    private boolean promptPending;
    private final Map<CaptainSpecial, Integer> fired = new EnumMap<>(CaptainSpecial.class);

    public CaptainLoadout loadout() { return loadout; }
    public boolean secondWindUsed() { return secondWindUsed; }
    public int capeColour() { return capeColour; }
    public boolean plume() { return plume; }
    public boolean promptPending() { return promptPending; }
    public void setPromptPending(boolean pending) { promptPending = pending; }
    public void setCapeColour(int dye) { capeColour = Math.max(-1, Math.min(15, dye)); }
    public void setPlume(boolean on) { plume = on; }

    public int fired(CaptainSpecial sp) {
        return fired.getOrDefault(sp, 0);
    }

    /** Switch loadout: cooldowns carry over, but nothing fires while re-arming. */
    public void switchTo(CaptainLoadout next, long now) {
        if (next != loadout) {
            loadout = next;
            rearmUntil = now + REARM_TICKS;
        }
    }

    public boolean rearming(long now) {
        return rearmUntil != NONE && now < rearmUntil;
    }

    // ---------------------------------------------------------------- fight

    /** Called whenever an enemy is in sight; opens a new fight after a long gap. */
    public void sawEnemy(long now) {
        if (lastEnemySeen == NONE || now - lastEnemySeen > CaptainSpecial.FIGHT_GAP_TICKS) {
            fightStart = now;
            secondWindUsed = false;
        }
        lastEnemySeen = now;
    }

    /** Ticks since this fight began (large when there is no fight). */
    public int fightTicks(long now) {
        return fightStart == NONE ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, now - fightStart);
    }

    // ------------------------------------------------------------ cooldowns

    public boolean ready(CaptainSpecial sp, long now) {
        if (rearming(now)) {
            return false;
        }
        if (sp.loadout() != null && sp.loadout() != loadout) {
            return false;
        }
        if (sp.oncePerFight()) {
            return !secondWindUsed;
        }
        Long at = readyAt.get(sp);
        return at == null || now >= at;
    }

    public int cooldownLeft(CaptainSpecial sp, long now) {
        Long at = readyAt.get(sp);
        return at == null ? 0 : (int) Math.max(0L, at - now);
    }

    /** The special resolved: start its cooldown (or spend the fight's Second Wind). */
    public void fire(CaptainSpecial sp, long now) {
        if (sp.oncePerFight()) {
            secondWindUsed = true;
        } else {
            readyAt.put(sp, now + sp.cooldownTicks());
        }
        fired.merge(sp, 1, Integer::sum);
    }

    // --------------------------------------------------------------- persist

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Loadout", loadout.wireId());
        CompoundTag cds = new CompoundTag();
        readyAt.forEach((sp, at) -> cds.putLong(sp.id(), at));
        tag.put("Ready", cds);
        tag.putBoolean("SecondWind", secondWindUsed);
        tag.putLong("FightStart", fightStart);
        tag.putLong("LastEnemy", lastEnemySeen);
        tag.putLong("Rearm", rearmUntil);
        tag.putInt("Cape", capeColour);
        tag.putBoolean("Plume", plume);
        tag.putBoolean("Prompt", promptPending);
        return tag;
    }

    public static CaptainState load(CompoundTag tag) {
        CaptainState s = new CaptainState();
        if (tag == null || !tag.contains("Loadout")) {
            return s;
        }
        s.loadout = CaptainLoadout.byWireId(tag.getInt("Loadout"));
        CompoundTag cds = tag.getCompound("Ready");
        for (CaptainSpecial sp : CaptainSpecial.values()) {
            if (cds.contains(sp.id())) {
                s.readyAt.put(sp, cds.getLong(sp.id()));
            }
        }
        s.secondWindUsed = tag.getBoolean("SecondWind");
        s.fightStart = tag.getLong("FightStart");
        s.lastEnemySeen = tag.getLong("LastEnemy");
        s.rearmUntil = tag.getLong("Rearm");
        s.capeColour = tag.contains("Cape") ? tag.getInt("Cape") : -1;
        s.plume = !tag.contains("Plume") || tag.getBoolean("Plume");
        s.promptPending = tag.getBoolean("Prompt");
        return s;
    }
}

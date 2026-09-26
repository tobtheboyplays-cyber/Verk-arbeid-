package com.hearthstead.event;

import net.minecraft.nbt.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Scheduling identity only: Coins stay in physical containers or the thief's saved loot. */
public final class GoblinTheftSavedData extends SavedData {
    // The first theft teaches protection after a player has started a village;
    // later attempts are spaced far enough apart to stay tense, not taxing.
    public static final long GRACE = 12_000L, COOLDOWN = 36_000L;
    private static final int FORMAT_VERSION = 2;
    private static final Factory<GoblinTheftSavedData> FACTORY = new Factory<>(GoblinTheftSavedData::new, GoblinTheftSavedData::load, null);
    private static final int CAP = 256;
    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private boolean quarantined;
    public record View(long eligibleTicks, long nextVisit, UUID active, boolean warned, String outcome) {}
    private static final class Row {
        long eligible, next = GRACE, retryAt, observed = -1;
        UUID active;
        boolean warned, previouslyEligible;
        String outcome = "none";
    }
    public static GoblinTheftSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "hearthstead_goblin_thefts");
    }
    public static GoblinTheftSavedData existing(ServerLevel level) {
        return level.getDataStorage().get(FACTORY, "hearthstead_goblin_thefts");
    }
    public View view(UUID id) {
        Row row = rows.get(id);
        return row == null ? null : new View(row.eligible,row.next,row.active,row.warned,row.outcome);
    }
    public void observe(UUID id, long now, boolean eligible) {
        if (quarantined || id == null || now < 0) return;
        Row row = rows.get(id);
        if (row == null) {
            if (!eligible || rows.size() >= CAP) return;
            row = new Row(); rows.put(id,row); setDirty();
        }
        if (eligible && row.previouslyEligible && row.observed >= 0 && now >= row.observed
            && now - row.observed <= 20) {
            row.eligible = Math.min(Long.MAX_VALUE - COOLDOWN, row.eligible + now - row.observed);
            setDirty();
        }
        row.observed = now; row.previouslyEligible = eligible;
    }
    public boolean warnOnce(UUID id) {
        Row row = rows.get(id);
        if (quarantined || row == null || row.warned) return false;
        row.warned = true; setDirty(); return true;
    }
    public boolean mayAttempt(UUID id, long now) {
        Row row = rows.get(id);
        return !quarantined && row != null && row.active == null && row.previouslyEligible
            && row.eligible >= row.next && now >= row.retryAt;
    }
    public void attempted(UUID id, long now) {
        Row row = rows.get(id);
        if (row != null) { row.retryAt = Math.min(Long.MAX_VALUE, now + 1200); setDirty(); }
    }
    public boolean published(UUID id, UUID actor) {
        Row row = rows.get(id);
        if (quarantined || row == null || actor == null || row.active != null || row.eligible < row.next) return false;
        row.active = actor; row.outcome = "active"; setDirty(); return true;
    }
    public boolean finished(UUID id, UUID actor, String outcome) {
        Row row = rows.get(id);
        if (quarantined || row == null || actor == null || !actor.equals(row.active)) return false;
        row.active = null; row.next = after(row.eligible, COOLDOWN); row.outcome = outcome; setDirty(); return true;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", FORMAT_VERSION); tag.putBoolean("Quarantined",quarantined);
        ListTag saved = new ListTag();
        rows.forEach((id,row) -> {
            CompoundTag entry = new CompoundTag(); entry.putUUID("Settlement",id);
            entry.putLong("Eligible",row.eligible); entry.putLong("Next",row.next); entry.putLong("Retry",row.retryAt);
            entry.putBoolean("Warned",row.warned); entry.putString("Outcome",row.outcome);
            if (row.active != null) entry.putUUID("Active",row.active);
            saved.add(entry);
        });
        tag.put("Rows",saved); return tag;
    }
    public static GoblinTheftSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new GoblinTheftSavedData();
        int version = tag.getInt("Version");
        if ((version != 1 && version != FORMAT_VERSION) || !(tag.get("Rows") instanceof ListTag rawRows)
            || rawRows.size() > CAP || (!rawRows.isEmpty() && rawRows.getElementType() != Tag.TAG_COMPOUND)) { data.quarantined = true; return data; }
        boolean legacy = version == 1;
        data.quarantined = tag.getBoolean("Quarantined");
        Set<UUID> actors = new HashSet<>();
        for (Tag value : tag.getList("Rows",Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)value;
            if (!entry.hasUUID("Settlement") || !entry.contains("Eligible",Tag.TAG_LONG)
                || !entry.contains("Next",Tag.TAG_LONG) || !entry.contains("Retry",Tag.TAG_LONG)) { data.quarantined = true; break; }
            Row row = new Row(); row.eligible = entry.getLong("Eligible"); row.next = entry.getLong("Next");
            row.retryAt = entry.getLong("Retry"); row.warned = entry.getBoolean("Warned");
            row.outcome = entry.getString("Outcome");
            if (entry.contains("Active")) {
                if (!entry.hasUUID("Active")) { data.quarantined = true; break; }
                row.active = entry.getUUID("Active");
                if (!actors.add(row.active)) { data.quarantined = true; break; }
            }
            // Version 1 allowed an immediate first visit and a three-minute
            // cooldown. Clamp only its impossible-new-cadence edge before
            // translating it; active thief identity and loot state stay intact.
            if (legacy) row.eligible = Math.min(row.eligible, Long.MAX_VALUE - COOLDOWN);
            if (row.eligible < 0 || row.eligible > Long.MAX_VALUE - COOLDOWN
                || row.next < (legacy ? 0 : GRACE)
                || row.retryAt < 0 || row.outcome.length() > 40
                || data.rows.putIfAbsent(entry.getUUID("Settlement"),row) != null) { data.quarantined = true; break; }
            if (legacy) {
                // An active v1 thief must be allowed to resolve normally. An
                // inactive v1 row gets one full new cooldown, never a surprise
                // immediate spawn after an update.
                row.next = row.active == null
                    ? Math.max(GRACE, after(row.eligible, COOLDOWN))
                    : Math.max(GRACE, row.next);
            }
        }
        // observed/previouslyEligible deliberately reset: no offline/reload time credit.
        if (legacy && !data.quarantined) data.setDirty();
        return data;
    }

    private static long after(long value, long duration) {
        return value >= Long.MAX_VALUE - duration ? Long.MAX_VALUE : value + duration;
    }
}

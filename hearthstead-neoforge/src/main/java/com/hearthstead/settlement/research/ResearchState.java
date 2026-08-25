package com.hearthstead.settlement.research;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * One settlement's research: what it has finished, and what its scholar is
 * working on now. The per-settlement record {@link Research} keeps a map of,
 * the same relationship {@link com.hearthstead.settlement.Settlement} has to
 * {@link com.hearthstead.settlement.SettlementSavedData}.
 *
 * <p>Deliberately not on {@code Settlement} itself — that class belongs to
 * another worker while this slice lands, and its own class doc already names
 * the merge as future work (see {@code PLAN_RESEARCH.md} §3). Nothing here
 * assumes it will always live apart; {@link Research#readNbt}/{@code writeNbt}
 * are the only two places that would need to change.
 */
public final class ResearchState {

    /** One active project's progress. At most one exists at a time (v1). */
    public static final class Active {
        public ResearchProject project;
        /** Whole work-sessions banked, from {@code ScholarWorkGoal} and the
         *  daily trickle rolling over together. */
        public int sessions;
        /** Fractional session progress from the passive trickle only,
         *  0..1 — rolls into {@link #sessions} the instant it reaches 1. */
        public float trickle;

        CompoundTag writeNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Project", project.id());
            tag.putInt("Sessions", sessions);
            tag.putFloat("Trickle", trickle);
            return tag;
        }

        @Nullable
        static Active readNbt(CompoundTag tag) {
            ResearchProject project = byId(tag.getString("Project"));
            if (project == null) {
                return null;
            }
            Active active = new Active();
            active.project = project;
            active.sessions = tag.getInt("Sessions");
            active.trickle = tag.getFloat("Trickle");
            return active;
        }
    }

    public final Set<ResearchProject> completed = EnumSet.noneOf(ResearchProject.class);
    @Nullable
    public Active active;
    /** The in-game day ({@code level.getDayTime() / 24000}) the passive
     *  trickle last fired, so it applies once per day rather than once per
     *  tick inside the window that checks it. -1 = never. */
    public long trickleDay = -1;

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        net.minecraft.nbt.ListTag completedList = new net.minecraft.nbt.ListTag();
        for (ResearchProject project : completed) {
            completedList.add(net.minecraft.nbt.StringTag.valueOf(project.id()));
        }
        tag.put("Completed", completedList);
        if (active != null) {
            tag.put("Active", active.writeNbt());
        }
        tag.putLong("TrickleDay", trickleDay);
        return tag;
    }

    public static ResearchState readNbt(CompoundTag tag) {
        ResearchState state = new ResearchState();
        net.minecraft.nbt.ListTag completedList =
            tag.getList("Completed", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < completedList.size(); i++) {
            ResearchProject project = byId(completedList.getString(i));
            if (project != null) {
                state.completed.add(project);
            }
        }
        if (tag.contains("Active")) {
            state.active = Active.readNbt(tag.getCompound("Active"));
        }
        state.trickleDay = tag.contains("TrickleDay") ? tag.getLong("TrickleDay") : -1;
        return state;
    }

    @Nullable
    private static ResearchProject byId(String id) {
        for (ResearchProject project : ResearchProject.values()) {
            if (project.id().equals(id)) {
                return project;
            }
        }
        return null;
    }
}

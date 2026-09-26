package com.hearthstead.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Why does this newcomer suit a job?" for the hire card (attributes lane,
 * plan/ATTRIBUTES.md). Pure and client-safe. It never compares two people:
 * it only reads which jobs lean on the two attributes a newcomer is
 * strongest in, straight off {@link JobAttributeProfile}, so the choice of
 * whom to hire stays with the player.
 */
public final class AttributeFit {

    private AttributeFit() {
    }

    /** 3 for the job's primary, 2 for its secondary, 1 for support, else 0. */
    public static int weight(Profession profession, Attribute attribute) {
        return JobAttributeProfile.find(profession).map(profile -> {
            int core = 0;
            for (JobAttributeProfile.Slot slot : profile.slots()) {
                if (slot.importance() == JobAttributeProfile.Importance.CORE) {
                    if (slot.attribute() == attribute) {
                        return core == 0 ? 3 : 2;
                    }
                    core++;
                } else if (slot.attribute() == attribute) {
                    return 1;
                }
            }
            return 0;
        }).orElse(0);
    }

    /**
     * Up to {@code limit} jobs that lean hardest on {@code first} and
     * {@code second} together (ties in profession order), each at least a
     * primary match. Empty when nothing fits that well.
     */
    public static List<Profession> suitedJobs(Attribute first, Attribute second, int limit) {
        List<Profession> out = new ArrayList<>();
        for (Profession p : Profession.BY_ID) {
            if (p.employed() && score(p, first, second) >= 3) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparingInt((Profession p) -> -score(p, first, second))
            .thenComparingInt(Enum::ordinal));
        return List.copyOf(out.subList(0, Math.min(Math.max(0, limit), out.size())));
    }

    private static int score(Profession p, Attribute first, Attribute second) {
        return weight(p, first) * 2 + (second == first ? 0 : weight(p, second));
    }
}

package com.hearthstead.saga;

import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * The narrative half of a raid captain -- DESIGN.md system 4's Nemesis
 * identity: "generated names/scars, learnable strengths, they grow on
 * victories, lieutenants inherit hatred when captains fall".
 *
 * <p>Deliberately layered beside {@link com.hearthstead.settlement.raid.RaidCaptain}
 * rather than replacing it: that class already owns menace, approach
 * avoidance and the win/loss record, and every existing raid GameTest
 * exercises it directly, so this v1 slice does not restructure it. A
 * {@code Captain} decorates one {@code RaidCaptain} by sharing its id and
 * adds exactly what that class does not: an earned name.
 *
 * <p><b>The name is earned, not decorative.</b> A fresh captain is a bare
 * Norse first name -- "Grimr" -- and nothing else. Only what they actually
 * did on a raid that got away with the goods buys them an epithet
 * ({@link #earnEpithetFrom}), so "Grimr the Grain-Thief" is a claim a
 * player can check against the morning report that produced it (D-A3-3: no
 * hidden stats -- everything a captain IS reads from a broadcast or a
 * report).
 *
 * <p><b>Known gap, found by the 2026-08-26 raid-night audit (KF-031's
 * sibling finding, not yet its own entry).</b> {@code earnEpithetFrom} is
 * only ever called with {@code !held}, and {@link com.hearthstead.settlement.raid.RaidDirector#resolveIfOver}
 * computes {@code held} purely from whether loot physically escaped
 * (KORN). No raid ever sets that flag for BRANN, BLOD or the disarmed
 * LOSEPENGER, so {@link #epithetsFor}'s BRANN and BLOD pairs -- "the
 * Torch"/"Ember-Bringer", "Red-Handed"/"the Reaper" -- describe real raider
 * behaviour (raiders genuinely burn and genuinely hurt settlers) but are
 * currently UNREACHABLE: no BRANN or BLOD captain can ever earn one, no
 * matter what their raid actually did. Left as-is rather than papered over
 * pending an owner decision on whether "held" should become objective-aware
 * -- see the audit report for the exact mechanism and the fix this would
 * take.
 */
public final class Captain {

    /**
     * Norse first names for the roster. A separate table from
     * {@code RaidCaptain}'s own (used only for its un-displayed internal
     * name) so a Saga-tracked captain never coincidentally shares a bare
     * first name with a "wild" one outside the tracked three.
     */
    private static final String[] FIRST_NAMES = {
        "Grimr", "Kettil", "Alrik", "Sveinung", "Bodvar", "Eirik",
        "Tostig", "Vidkun", "Haakon", "Ottar", "Skjold", "Runolf",
        "Asger", "Dagfinn", "Ingjald", "Torvald",
    };

    /**
     * Epithets a captain can earn, keyed by the objective their raid
     * actually accomplished -- "burns/steals" the way the task frames it,
     * never handed out for merely existing. Two per objective: the first
     * earned outright, the second an upgrade once they have proven it is not
     * a fluke ({@link #EPITHET_UPGRADE_VICTORIES}). Bounded and finite
     * (D-A3-3), the same shape as {@code RaidCaptain}'s own byname table.
     */
    private static String[] epithetsFor(RaidObjective objective) {
        return switch (objective) {
            case KORN -> new String[] {"the Grain-Thief", "Larder's Bane"};
            case BRANN -> new String[] {"the Torch", "Ember-Bringer"};
            case BLOD -> new String[] {"Red-Handed", "the Reaper"};
            case LOSEPENGER -> new String[] {"the Ransomer", "Chain-Bringer"};
        };
    }

    /** Victories (on the underlying RaidCaptain's own record) needed before
     * a first epithet can be upgraded to the fiercer second one. */
    public static final int EPITHET_UPGRADE_VICTORIES = 3;

    /** Matches the id of the {@code RaidCaptain} this identity decorates. */
    private UUID id;
    private String firstName;
    /** Null until earned -- see the class doc. */
    private String epithet;
    /** The fallen predecessor's first name, set only on a lieutenant. */
    private String swornTo;

    private Captain() {
    }

    /** A fresh, epithet-less captain: a name, nothing earned yet. */
    public static Captain generate(RandomSource nameRandom, UUID id) {
        Captain c = new Captain();
        c.id = id;
        c.firstName = FIRST_NAMES[nameRandom.nextInt(FIRST_NAMES.length)];
        return c;
    }

    /**
     * A lieutenant raised to replace a captain killed in the field. Carries
     * the grudge marker in their own name from the moment they exist --
     * "Kettil, sworn to Grimr" -- rather than earning it, because who they
     * answer to is a fact about them from the start, unlike an epithet.
     */
    public static Captain lieutenantOf(RandomSource nameRandom, UUID id, String fallenFirstName) {
        Captain c = generate(nameRandom, id);
        c.swornTo = fallenFirstName;
        return c;
    }

    public UUID id() {
        return id;
    }

    public String firstName() {
        return firstName;
    }

    public String epithet() {
        return epithet;
    }

    public String swornTo() {
        return swornTo;
    }

    public boolean hasEpithet() {
        return epithet != null;
    }

    /** The full name exactly as a broadcast or report shows it. */
    public String displayName() {
        StringBuilder sb = new StringBuilder(firstName);
        if (epithet != null) {
            sb.append(' ').append(epithet);
        }
        if (swornTo != null) {
            sb.append(", sworn to ").append(swornTo);
        }
        return sb.toString();
    }

    /**
     * Earns or upgrades an epithet from what this captain's raid actually
     * did. Only ever meaningful to call on a raid that escaped with the
     * goods ({@code RaidDirector#resolveIfOver}'s "lost" branch) -- a raid
     * that was repelled earns nothing, however hard it was fought.
     *
     * @param victoriesSoFar the underlying RaidCaptain's victory count,
     *                       already incremented for tonight's win
     * @return true if the name actually changed (worth announcing)
     */
    public boolean earnEpithetFrom(RaidObjective objective, int victoriesSoFar) {
        String[] table = epithetsFor(objective);
        if (epithet == null) {
            epithet = table[0];
            return true;
        }
        if (victoriesSoFar >= EPITHET_UPGRADE_VICTORIES && !epithet.equals(table[1])) {
            epithet = table[1];
            return true;
        }
        return false;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("FirstName", firstName);
        if (epithet != null) {
            tag.putString("Epithet", epithet);
        }
        if (swornTo != null) {
            tag.putString("SwornTo", swornTo);
        }
        return tag;
    }

    public static Captain readNbt(CompoundTag tag) {
        Captain c = new Captain();
        c.id = tag.getUUID("Id");
        c.firstName = tag.getString("FirstName");
        c.epithet = tag.contains("Epithet") ? tag.getString("Epithet") : null;
        c.swornTo = tag.contains("SwornTo") ? tag.getString("SwornTo") : null;
        return c;
    }
}

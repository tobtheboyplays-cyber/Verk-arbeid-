package com.hearthstead.settlement.guildmaster;

import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Settlement;

/**
 * The Mayor office no longer exists (owner decision, 26 Sep 2026: "remove the
 * Mayor completely; a Guildmaster under the Banner trades roles instead").
 *
 * <p>Old saves still carry the office: {@code MayorId}, {@code MayorSince},
 * {@code MourningUntil}, a Warehouse the Mayor helped at, and a settler whose
 * saved profession is {@link Profession#MAYOR}. Loading such a save must never
 * crash, lose the settler or leave a lingering buff/debuff behind. This class
 * is the single place that turns every one of those into its neutral value:
 * the former Mayor simply becomes an unassigned settler (same UUID, name,
 * inventory, skills and bed), and mourning or a settling-in clock is gone.
 *
 * <p>{@link Profession#MAYOR} itself stays in the enum only as a stable legacy
 * wire/NBT id (26) so an old byte still decodes; nothing assigns it any more.
 */
public final class MayorRetirement {

    /** The profession a saved one becomes: MAYOR retires to NONE. */
    public static Profession retired(Profession saved) {
        return saved == null || saved == Profession.MAYOR ? Profession.NONE : saved;
    }

    /**
     * Clears every Mayor field on a freshly loaded (or live) settlement and
     * retires any Mayor roster row. Idempotent.
     *
     * @return true when something was actually cleared (an old save)
     */
    public static boolean scrub(Settlement settlement) {
        if (settlement == null) {
            return false;
        }
        boolean changed = settlement.mayorId != null
            || settlement.mayorCourierWarehouseId != null
            || settlement.mayorSince != 0L
            || settlement.mourningUntil != 0L;
        settlement.mayorId = null;
        settlement.mayorCourierWarehouseId = null;
        settlement.mayorSince = 0L;
        settlement.mourningUntil = 0L;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record.profession == null || record.profession == Profession.MAYOR) {
                record.profession = Profession.NONE;
                changed = true;
            }
        }
        return changed;
    }

    private MayorRetirement() {
    }
}

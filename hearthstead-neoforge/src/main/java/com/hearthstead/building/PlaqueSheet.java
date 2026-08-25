package com.hearthstead.building;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What the plaque's parchment sheet says — as data, not as pixels.
 *
 * <p>This is deliberately common code rather than client code. The block
 * entity renderer draws it, but the decisions it encodes are the product ones:
 * which lines a plaque shows, in what order, and what colour each one is. A
 * sheet whose ink was decided inside a renderer could only ever be checked by
 * looking at it, and "I looked at it" is the weakest evidence this repository
 * accepts. Here it can be asserted on in a GameTest — see
 * {@code theSheetSaysWhatIsMissing}.
 *
 * <p>The sheet is a VIEW. It holds no state, caches nothing, and is rebuilt
 * from (type, state, survey) every frame. D-006: the plaque is an access
 * point, never a second source of truth.
 */
public record PlaqueSheet(@Nullable Component title, List<Line> lines) {

    /** A plaque with no plan fitted: an empty well, and nothing written. */
    public static final PlaqueSheet BLANK = new PlaqueSheet(null, List.of());

    /** Dark iron-gall ink for the title, on parchment. */
    public static final int TITLE_COLOUR = 0xFF3A2A18;

    /**
     * How one line reads. The colour carries the same three-way signal as the
     * lamp in the board — met / some progress / none — so a player who has
     * learnt what amber means on the wall already knows what amber means on
     * the sheet. Deliberately dark, saturated inks: this is writing on
     * parchment, not a HUD, and bright UI green would look pasted on.
     */
    public enum Ink {
        MET(0xFF2F6B2E, "✔"),
        PARTIAL(0xFF9A5F12, "✘"),
        UNMET(0xFF8E2B22, "✘"),
        /** A note about the plaque itself rather than a requirement. */
        NOTE(0xFF8E2B22, "");

        private final int colour;
        private final String mark;

        Ink(int colour, String mark) {
            this.colour = colour;
            this.mark = mark;
        }

        public int colour() {
            return colour;
        }

        /**
         * The tick or cross drawn at the end of the line, or "" for a note.
         *
         * <p>U+2714 and U+2718, NOT the lighter U+2713/U+2717 the mockup's
         * font uses: vanilla's own {@code nonlatin_european.png} bitmap
         * provider carries the heavy pair, while the light pair exists only in
         * the unifont fallback. Verified by reading
         * {@code assets/minecraft/font/include/default.json} out of the client
         * jar rather than by assuming — a missing glyph renders as a box, and
         * a box beside every requirement would be worse than no mark at all.
         */
        public String mark() {
            return mark;
        }
    }

    /**
     * @param id   the requirement this line reports, by
     *             {@link Requirement#id()} — {@link #STATE_ID} for a note
     *             about the plaque itself. Carried so the line can be checked
     *             by name rather than by reading its rendered words, and so
     *             step 4 can hang a per-requirement icon off it.
     */
    public record Line(String id, Component text, Ink ink) {
    }

    /** {@link Line#id()} of a line that reports the plaque's state, not a requirement. */
    public static final String STATE_ID = "state";

    /**
     * Reads a plaque into a sheet.
     *
     * @param state  the plaque's state; {@link PlaqueState#EMPTY} writes
     *               nothing at all, because no plan is fitted and the plaque
     *               has nothing to claim
     * @param survey the last survey; empty when no room was found, in which
     *               case the sheet says so instead of listing requirements it
     *               could not measure
     */
    public static PlaqueSheet of(BuildingType type, PlaqueState state,
                                 List<Requirement.Status> survey) {
        if (state == PlaqueState.EMPTY) {
            return BLANK;
        }
        List<Line> lines = new ArrayList<>();
        if (survey.isEmpty()) {
            // No room found, or an orphaned plaque: naming the state is worth
            // more than an empty list, because "no room found" tells the
            // player their walls do not close and a blank sheet does not.
            lines.add(new Line(STATE_ID,
                Component.translatable("hearthstead.plaque.state." + state.id()),
                Ink.NOTE));
        } else {
            for (Requirement.Status status : survey) {
                lines.add(new Line(status.requirement().id(), status.describe(),
                    inkFor(status)));
            }
        }
        return new PlaqueSheet(type.displayName(), List.copyOf(lines));
    }

    private static Ink inkFor(Requirement.Status status) {
        if (status.met()) {
            return Ink.MET;
        }
        return status.partial() ? Ink.PARTIAL : Ink.UNMET;
    }

    /** Nothing to draw — the well stays an empty recess. */
    public boolean isBlank() {
        return title == null;
    }

    /** Every line met: the sheet reads as finished. */
    public boolean complete() {
        if (isBlank() || lines.isEmpty()) {
            return false;
        }
        for (Line line : lines) {
            if (line.ink() != Ink.MET) {
                return false;
            }
        }
        return true;
    }
}

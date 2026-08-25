package com.hearthstead.settlement.raid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * A named enemy the settlement remembers, and who remembers back.
 *
 * <p>This is the Nemesis-shaped half of the raid design. The research is
 * blunt about why it matters: MineColonies raids leave no tail at all — one
 * day of mourning and a chat line — and two of its own feature requests
 * (#113, #129) are, at root, asking for consequences that outlive the
 * fight. A raid that leaves no scar cannot make the next one feel closer.
 *
 * <p>A captain carries that tail. Beaten, they come back harder and by a
 * different road; successful, they grow bolder. They remember one settler
 * by name — whoever hurt them most — which is what turns a wave of mobs
 * into somebody with a grievance.
 *
 * <p>Persisted on the settlement that met them: this is the settlement's
 * memory of its enemies, the Tingbok's enemy gallery.
 */
public final class RaidCaptain {

    private static final String[] FIRST = {
        "Hrafn", "Skarde", "Ulf", "Grimm", "Bjorn", "Halgeir", "Ravn",
        "Torkel", "Steinar", "Vragi", "Kolbein", "Arnkel", "Gunnhild",
        "Signy", "Hervor", "Thorgunn", "Ingimund", "Ozur",
    };

    /** Earned, not decorative: each byname is a claim about the bearer. */
    private static final String[] BYNAME = {
        "the Ashen", "Ironjaw", "the Patient", "Nine-Fingers",
        "the Unquiet", "Blackbriar", "the Gate-Breaker", "Coldhand",
        "the Debtor", "Wolf-Sworn", "the Sleepless", "Emberbeard",
    };

    /** Cap on remembered wins/losses; a captain is a character, not a ledger. */
    public static final int MAX_RECORD = 99;

    private UUID id;
    private String name;
    private RaidObjective lastObjective;
    private int victories;
    private int defeats;
    /** The settler this captain holds a grudge against, if any. */
    private String grudge;
    /**
     * The compass angle they came from last time, in degrees. Kept so the
     * next approach can be biased AWAY from it: MineColonies feature request
     * #193 is a player working out that raiders "usually come from the same
     * spawn point", and TekTopia spawns every hostile at one of four fixed
     * corners. A captain who was beaten at the north gate does not walk into
     * it twice.
     */
    private float lastApproachDegrees = Float.NaN;

    private RaidCaptain() {
    }

    public static RaidCaptain generate(RandomSource random) {
        RaidCaptain c = new RaidCaptain();
        c.id = UUID.randomUUID();
        c.name = FIRST[random.nextInt(FIRST.length)] + " "
            + BYNAME[random.nextInt(BYNAME.length)];
        return c;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int victories() {
        return victories;
    }

    public int defeats() {
        return defeats;
    }

    public String grudge() {
        return grudge;
    }

    public RaidObjective lastObjective() {
        return lastObjective;
    }

    public boolean hasApproached() {
        return !Float.isNaN(lastApproachDegrees);
    }

    public float lastApproachDegrees() {
        return lastApproachDegrees;
    }

    /**
     * How much harder this captain has become. Grows with wins and, more
     * slowly, with defeats — being beaten teaches them something too, which
     * is the opposite of MineColonies lowering difficulty after a bad night.
     */
    public float menace() {
        return 1.0F + victories * 0.15F + defeats * 0.05F;
    }

    public void recordVictory() {
        victories = Math.min(MAX_RECORD, victories + 1);
    }

    public void recordDefeat() {
        defeats = Math.min(MAX_RECORD, defeats + 1);
    }

    /** Remembers whoever hurt them. Only the most recent is kept. */
    public void rememberGrudge(String settlerName) {
        this.grudge = settlerName;
    }

    public void recordApproach(float degrees, RaidObjective objective) {
        this.lastApproachDegrees = Mth.wrapDegrees(degrees);
        this.lastObjective = objective;
    }

    /**
     * Where they will come from next. Never the same road twice: the new
     * angle is at least {@value #MIN_APPROACH_SHIFT} degrees off the last
     * one, so walling the direction that worked last time is not a solution.
     */
    public static final float MIN_APPROACH_SHIFT = 60.0F;

    public float nextApproachDegrees(RandomSource random) {
        float candidate = random.nextFloat() * 360.0F - 180.0F;
        if (!hasApproached()) {
            return Mth.wrapDegrees(candidate);
        }
        // Push the candidate out of the forbidden arc around the last one,
        // in whichever direction it is already leaning.
        float delta = Mth.wrapDegrees(candidate - lastApproachDegrees);
        if (Math.abs(delta) < MIN_APPROACH_SHIFT) {
            float sign = delta < 0 ? -1.0F : 1.0F;
            candidate = lastApproachDegrees + sign * MIN_APPROACH_SHIFT;
        }
        return Mth.wrapDegrees(candidate);
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putInt("Victories", victories);
        tag.putInt("Defeats", defeats);
        if (grudge != null) {
            tag.putString("Grudge", grudge);
        }
        if (lastObjective != null) {
            tag.putString("LastObjective", lastObjective.id());
        }
        if (hasApproached()) {
            tag.putFloat("LastApproach", lastApproachDegrees);
        }
        return tag;
    }

    public static RaidCaptain readNbt(CompoundTag tag) {
        RaidCaptain c = new RaidCaptain();
        c.id = tag.getUUID("Id");
        c.name = tag.getString("Name");
        c.victories = Mth.clamp(tag.getInt("Victories"), 0, MAX_RECORD);
        c.defeats = Mth.clamp(tag.getInt("Defeats"), 0, MAX_RECORD);
        c.grudge = tag.contains("Grudge") ? tag.getString("Grudge") : null;
        if (tag.contains("LastObjective")) {
            String id = tag.getString("LastObjective");
            for (RaidObjective o : RaidObjective.values()) {
                if (o.id().equals(id)) {
                    c.lastObjective = o;
                    break;
                }
            }
        }
        c.lastApproachDegrees = tag.contains("LastApproach")
            ? tag.getFloat("LastApproach") : Float.NaN;
        return c;
    }
}

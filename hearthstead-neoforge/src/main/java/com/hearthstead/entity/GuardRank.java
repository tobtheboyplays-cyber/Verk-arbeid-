package com.hearthstead.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/**
 * What a guard has learned to do, earned by fighting.
 *
 * <h2>Rank is not a number you spend, it is a number you reach</h2>
 *
 * <p>Owner's ask, 2026-08-25: abilities that unlock as a guard levels, one per
 * twenty points, and TekTopia's leap that hits several enemies at once. So
 * rank reads straight off {@link Attribute#STRENGTH} — the attribute a guard's
 * own work trains — and there is nothing to allocate. A veteran guard is
 * evidence of nights survived, which is the only currency this mod has that
 * cannot be farmed quickly.
 *
 * <p>Every twenty points is deliberately a long way: with growth slowing as it
 * rises, {@link #SERGEANT} is weeks of patrols and fights, and the leap is
 * meant to be a thing you remember the first time you see one of your own
 * guards do it.
 *
 * <h2>The abilities</h2>
 *
 * <table>
 *   <caption>Ranks</caption>
 *   <tr><th>rank</th><th>at</th><th>what they can do</th><th>wears</th></tr>
 *   <tr><td>RECRUIT</td><td>0</td><td>swings a sword</td><td>nothing</td></tr>
 *   <tr><td>SPEARMAN</td><td>20</td><td><b>Shield Bash</b> — hits knock back and stagger</td><td>a leather chestplate</td></tr>
 *   <tr><td>VETERAN</td><td>40</td><td><b>Cleave</b> — the swing also catches a second enemy</td><td>full leather</td></tr>
 *   <tr><td>SERGEANT</td><td>60</td><td><b>Leap Strike</b> — leaps a gap and lands on everyone at once</td><td>iron chest and legs over a leather cap and boots</td></tr>
 *   <tr><td>CAPTAIN</td><td>80</td><td><b>Rally</b> — a kill lifts every guard nearby</td><td>full iron</td></tr>
 * </table>
 *
 * <h2>Armor is earned too</h2>
 *
 * <p>Owner's second ask, 2026-08-25: "guards must not have good armor before
 * they upgrade — they need experience." A settlement can afford iron the
 * moment it has a smith, but a recruit does not get to wear it just because
 * the chest has it; {@link #applyEquipment} only ever puts a rank's own gear
 * on a guard who has actually reached that rank, and the sword stays
 * untouched — it is the profession's tool ({@link Profession#GUARD}), not a
 * reward, and every rank keeps it.
 *
 * <p>The ramp is deliberately readable at a glance: bare, then a leather vest,
 * then full leather, then iron creeping in at the core while the cap and
 * boots stay leather, then head to toe iron. A player who has never opened a
 * settler screen should be able to eyeball who the veterans are just by
 * walking the wall.
 *
 * <h2>Whether armor can go backwards</h2>
 *
 * <p>There is no separate "rank ever reached" record — {@link #of} always
 * reads the <i>current</i> Strength, and {@link #applyEquipment} always
 * dresses a guard for the rank that comes back. Nothing in ordinary play
 * lowers Strength once trained, so in practice this never bites: a guard's
 * gear only ever gets better. But the mechanism itself has no memory, on
 * purpose — a high-water-mark rank would mean a settler could be dressed
 * above what they currently measure up to, which is the opposite of "armor
 * gated by experience". If Strength ever *can* drop (a future injury system,
 * a debuff), the guard's kit will drop with it, honestly, rather than
 * quietly keeping gear they no longer show the Strength for.
 */
public enum GuardRank {
    RECRUIT("recruit", 0,
        () -> ItemStack.EMPTY, () -> ItemStack.EMPTY,
        () -> ItemStack.EMPTY, () -> ItemStack.EMPTY),
    SPEARMAN("spearman", 20,
        () -> ItemStack.EMPTY, () -> new ItemStack(Items.LEATHER_CHESTPLATE),
        () -> ItemStack.EMPTY, () -> ItemStack.EMPTY),
    VETERAN("veteran", 40,
        () -> new ItemStack(Items.LEATHER_HELMET), () -> new ItemStack(Items.LEATHER_CHESTPLATE),
        () -> new ItemStack(Items.LEATHER_LEGGINGS), () -> new ItemStack(Items.LEATHER_BOOTS)),
    SERGEANT("sergeant", 60,
        () -> new ItemStack(Items.LEATHER_HELMET), () -> new ItemStack(Items.IRON_CHESTPLATE),
        () -> new ItemStack(Items.IRON_LEGGINGS), () -> new ItemStack(Items.LEATHER_BOOTS)),
    CAPTAIN("captain", 80,
        () -> new ItemStack(Items.IRON_HELMET), () -> new ItemStack(Items.IRON_CHESTPLATE),
        () -> new ItemStack(Items.IRON_LEGGINGS), () -> new ItemStack(Items.IRON_BOOTS));

    /** Secondary targets take this share, the way vanilla's sweep does. */
    public static final float CLEAVE_SHARE = 0.6F;
    /** How far a sergeant will leap. Short enough to read as a lunge. */
    public static final double LEAP_MIN = 3.5;
    public static final double LEAP_MAX = 9.0;
    /** Everything within this of the landing takes the blow. */
    public static final double LEAP_RADIUS = 3.0;
    /** Ticks between leaps, so it stays an event rather than a walk cycle. */
    public static final int LEAP_COOLDOWN = 200;

    private final String key;
    private final int threshold;
    // One fresh ItemStack per call, never a shared instance -- two guards
    // dressed at the same rank must never be able to corrupt each other's
    // gear through one stack's NBT or stack count.
    private final Supplier<ItemStack> helmet;
    private final Supplier<ItemStack> chest;
    private final Supplier<ItemStack> legs;
    private final Supplier<ItemStack> boots;

    GuardRank(String key, int threshold,
              Supplier<ItemStack> helmet, Supplier<ItemStack> chest,
              Supplier<ItemStack> legs, Supplier<ItemStack> boots) {
        this.key = key;
        this.threshold = threshold;
        this.helmet = helmet;
        this.chest = chest;
        this.legs = legs;
        this.boots = boots;
    }

    public String key() {
        return key;
    }

    public int threshold() {
        return threshold;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.rank." + key);
    }

    /** The rank this strength has earned. */
    public static GuardRank of(int strength) {
        GuardRank best = RECRUIT;
        for (GuardRank rank : values()) {
            if (strength >= rank.threshold) {
                best = rank;
            }
        }
        return best;
    }

    public static GuardRank of(SettlerEntity settler) {
        return of(settler.attribute(Attribute.STRENGTH));
    }

    public boolean atLeast(GuardRank other) {
        return ordinal() >= other.ordinal();
    }

    // ---------------------------------------------------------- equipment ---

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    /**
     * Dresses a guard for the rank {@link #of(SettlerEntity)} says they have
     * actually reached — no more, no less. Safe to call on any settler at any
     * cadence; it always sets all four armor slots (an empty {@link ItemStack}
     * for a slot the rank does not fill), so a caller never has to know what
     * the guard was wearing a moment ago. {@code setDropChance(0)} on every
     * slot: a settlement's investment in its guards must not evaporate the
     * first time one loses a fight (mirrors {@link SettlerEntity}'s own
     * MAINHAND tool, which is dropChance-0 for the same reason).
     */
    public static void applyEquipment(SettlerEntity settler) {
        GuardRank rank = of(settler);
        settler.setItemSlot(EquipmentSlot.HEAD, rank.helmet.get());
        settler.setItemSlot(EquipmentSlot.CHEST, rank.chest.get());
        settler.setItemSlot(EquipmentSlot.LEGS, rank.legs.get());
        settler.setItemSlot(EquipmentSlot.FEET, rank.boots.get());
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            settler.setDropChance(slot, 0.0F);
        }
    }

    /** Strips the four armor slots bare — a settler who has stopped being a
     *  guard keeps their earned Strength, but the armor was the guard's, not
     *  theirs; see {@link SettlerEntity}'s equipment-refresh hook. */
    public static void clearEquipment(SettlerEntity settler) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            settler.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------ captain ---

    /**
     * The settlement's highest-ranked living guard, or {@code null} if it has
     * none — computed fresh from {@code loadedMembers} every call, never
     * stored. The Vaktkaptein is a fact about who the guards currently are,
     * not a record that could quietly go stale the moment a better guard
     * grows into the role or the old one falls.
     *
     * <p>Ties (two guards who share a rank tier) go to whichever has the
     * higher raw Strength, so the answer is still a single settler and not an
     * arbitrary "whoever the list happened to put first".
     *
     * @param loadedMembers a settlement's currently-loaded settlers, e.g.
     *                      {@code SettlementManager.loadedMembers(level, s)}
     */
    @Nullable
    public static SettlerEntity captainOf(List<SettlerEntity> loadedMembers) {
        SettlerEntity best = null;
        GuardRank bestRank = null;
        for (SettlerEntity settler : loadedMembers) {
            if (settler.getProfession() != Profession.GUARD || !settler.isAlive()) {
                continue;
            }
            GuardRank rank = of(settler);
            if (bestRank == null
                || rank.ordinal() > bestRank.ordinal()
                || (rank.ordinal() == bestRank.ordinal()
                    && settler.attribute(Attribute.STRENGTH) > best.attribute(Attribute.STRENGTH))) {
                best = settler;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Progress towards the next rank, 0..1; 1 at the top. */
    public static float progress(int strength) {
        GuardRank now = of(strength);
        if (now == CAPTAIN) {
            return 1.0F;
        }
        GuardRank next = values()[now.ordinal() + 1];
        int span = next.threshold - now.threshold;
        return span <= 0 ? 1.0F : (strength - now.threshold) / (float) span;
    }
}

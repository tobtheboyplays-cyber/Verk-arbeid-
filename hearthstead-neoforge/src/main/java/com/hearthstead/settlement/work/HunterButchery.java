package com.hearthstead.settlement.work;

import com.hearthstead.entity.SkillLevels;
import com.hearthstead.item.CarcassData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Pure butchery rules for one hunted carcass.
 *
 * <h2>Fairness</h2>
 * The base yield IS the vanilla loot-table roll captured at the kill
 * ({@link CarcassData#yield()}), so a butchered cow gives exactly what an
 * arrow-killed cow drops in vanilla: 1-3 beef and 0-2 leather. Nothing is
 * rerolled here.
 *
 * <h2>Trade bonus (the SkillLevels secondary pattern)</h2>
 * From trade level 5 the Hunter's secondary side chance (Dexterity, cap 8%)
 * may add ONE more unit of the first meat stack the vanilla roll already
 * produced -- never a new item type, exactly like the Farmer's "+1 seed on a
 * harvest whose vanilla loot already dropped it". The primary (Perception)
 * speed bonus shortens the work in whole clip loops via
 * {@link SkillLevels#shortenLooped}, so contact frames never move.
 *
 * <h2>Phases</h2>
 * A carcass with a pelt (leather, rabbit hide, feathers, wool) is first
 * skinned (hide-scrape clip) for {@value #SKIN_SHARE_PERCENT}% of the time,
 * then jointed (cleave clip) for the rest.
 */
public final class HunterButchery {
    /** WORK_CLEAVE loop length (Employment.soundPeriodOf) and its contact tick. */
    public static final int CLEAVE_PERIOD = 17;
    public static final int CLEAVE_CONTACT = 9;
    /** WORK_SCRAPE loop length and contact tick. */
    public static final int SCRAPE_PERIOD = 24;
    public static final int SCRAPE_CONTACT = 4;
    public static final int SKIN_SHARE_PERCENT = 40;

    private HunterButchery() {
    }

    /**
     * Exact output of one butchered carcass: fresh copies of the recorded
     * vanilla drops, plus one unit of the first meat stack when
     * {@code bonus} is true and that stack has room.
     */
    public static List<ItemStack> yield(CarcassData data, boolean bonus) {
        List<ItemStack> out = data.yieldCopies();
        if (bonus) {
            for (ItemStack stack : out) {
                if (isMeat(stack) && stack.getCount() < stack.getMaxStackSize()) {
                    stack.grow(1);
                    break;
                }
            }
        }
        return out;
    }

    public static boolean isMeat(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    public static boolean isPelt(ItemStack stack) {
        return stack.is(Items.LEATHER) || stack.is(Items.RABBIT_HIDE)
            || stack.is(Items.FEATHER) || stack.is(ItemTags.WOOL)
            // Name fallback: tags are unbound outside a loaded world (unit tests).
            || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .getPath().endsWith("_wool");
    }

    public static boolean hasPelt(CarcassData data) {
        for (ItemStack stack : data.yield()) {
            if (isPelt(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Total work ticks after the trade speed bonus (whole cleave loops only). */
    public static int workTicks(int baseTicks, int tradeLevel, int primary) {
        return Math.max(CLEAVE_PERIOD, SkillLevels.shortenLooped(Math.max(1, baseTicks),
            CLEAVE_PERIOD, tradeLevel, primary));
    }

    /** Ticks of the skinning phase at the start of {@code totalTicks}; 0 without a pelt. */
    public static int skinTicks(int totalTicks, boolean pelt) {
        return pelt ? totalTicks * SKIN_SHARE_PERCENT / 100 : 0;
    }

    /** True on the tick of a phase's clip where its work sound belongs. */
    public static boolean soundTick(int phaseTick, boolean skinning) {
        return skinning ? phaseTick % SCRAPE_PERIOD == SCRAPE_CONTACT
            : phaseTick % CLEAVE_PERIOD == CLEAVE_CONTACT;
    }
}

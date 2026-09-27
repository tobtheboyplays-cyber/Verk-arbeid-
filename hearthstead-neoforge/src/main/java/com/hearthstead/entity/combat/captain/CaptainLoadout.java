package com.hearthstead.entity.combat.captain;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The hero Captain's weapon loadouts (plan/CAPTAIN.md). Each loadout is a
 * physical kit -- the weapon items must actually be in his hands -- with its
 * own specials. Items are matched by TAG so the weapons lane's premium items
 * slot in without code changes. Wire ids are save/network contracts: append,
 * never renumber.
 */
public enum CaptainLoadout {
    SWORD_SHIELD(0, "sword_shield", false, true),
    DUAL_SWORDS(1, "dual_swords", false, true),
    GREAT_AXE(2, "great_axe", true, true),
    BOW(3, "bow", true, true),
    HALBERD(4, "halberd", true, false),
    WARHAMMER(5, "warhammer", true, false);

    public static final TagKey<Item> DUAL_SWORD_TAG = tag("captain/dual_swords");
    public static final TagKey<Item> GREAT_AXE_TAG = tag("captain/great_axes");
    public static final TagKey<Item> HALBERD_TAG = tag("captain/halberds");
    public static final TagKey<Item> WARHAMMER_TAG = tag("captain/warhammers");
    public static final TagKey<Item> BOW_TAG = tag("captain/bows");

    private final int wireId;
    private final String key;
    private final boolean twoHanded;
    /** Ships for Sunday; the others hide behind [captain] extraLoadouts until their clips exist. */
    private final boolean core;

    CaptainLoadout(int wireId, String key, boolean twoHanded, boolean core) {
        this.wireId = wireId;
        this.key = key;
        this.twoHanded = twoHanded;
        this.core = core;
    }

    public int wireId() { return wireId; }
    public String key() { return key; }
    public boolean twoHanded() { return twoHanded; }
    public boolean core() { return core; }

    public static CaptainLoadout byWireId(int id) {
        for (CaptainLoadout l : values()) {
            if (l.wireId == id) {
                return l;
            }
        }
        return SWORD_SHIELD;
    }

    /** The specials this loadout owns (the common three are added by {@link CaptainSpecial#available}). */
    public List<CaptainSpecial> specials() {
        return switch (this) {
            case SWORD_SHIELD -> List.of(CaptainSpecial.SHIELD_CHARGE, CaptainSpecial.HOLD_THE_LINE,
                CaptainSpecial.POMMEL_STUN);
            case DUAL_SWORDS -> List.of(CaptainSpecial.BLADE_WHIRL, CaptainSpecial.TWIN_THRUST,
                CaptainSpecial.DISARM, CaptainSpecial.DODGE_STEP);
            case GREAT_AXE -> List.of(CaptainSpecial.AXE_CLEAVE, CaptainSpecial.SPINNING_CHOP,
                CaptainSpecial.ARMOUR_BREAKER);
            case BOW -> List.of(CaptainSpecial.ARROW_VOLLEY, CaptainSpecial.PIERCING_SHOT,
                CaptainSpecial.MARK_TARGET);
            case HALBERD -> List.of(CaptainSpecial.HALBERD_SWEEP, CaptainSpecial.BRACE_CHARGE,
                CaptainSpecial.HOOK_PULL);
            case WARHAMMER -> List.of(CaptainSpecial.SHIELD_BREAKER, CaptainSpecial.GROUND_SLAM,
                CaptainSpecial.CRUSHING_BLOW);
        };
    }

    /** Is this main/off-hand pair this loadout's physical kit? */
    public boolean matches(ItemStack main, ItemStack off) {
        return switch (this) {
            case SWORD_SHIELD -> isSword(main) && off.is(Items.SHIELD);
            case DUAL_SWORDS -> (main.is(DUAL_SWORD_TAG) || isSword(main))
                && (off.is(DUAL_SWORD_TAG) || isSword(off));
            case GREAT_AXE -> safeIs(main, GREAT_AXE_TAG) && off.isEmpty();
            case BOW -> (main.is(Items.BOW) || safeIs(main, BOW_TAG)) && !off.is(Items.SHIELD);
            case HALBERD -> safeIs(main, HALBERD_TAG) && off.isEmpty();
            case WARHAMMER -> safeIs(main, WARHAMMER_TAG) && off.isEmpty();
        };
    }

    /** The loadout this kit IS, or null if the hands hold no complete kit. */
    @Nullable
    public static CaptainLoadout of(ItemStack main, ItemStack off) {
        // Dual swords before sword & shield: two blades never read as a shield kit.
        for (CaptainLoadout l : new CaptainLoadout[] {DUAL_SWORDS, SWORD_SHIELD, GREAT_AXE, BOW,
                HALBERD, WARHAMMER}) {
            if (l.matches(main, off)) {
                return l;
            }
        }
        return null;
    }

    private static boolean isSword(ItemStack s) {
        return !s.isEmpty() && safeIs(s, ItemTags.SWORDS);
    }

    private static boolean safeIs(ItemStack s, TagKey<Item> tag) {
        try {
            return !s.isEmpty() && s.is(tag);
        } catch (IllegalStateException unbound) {
            return false;
        }
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Hearthstead.id(path));
    }
}

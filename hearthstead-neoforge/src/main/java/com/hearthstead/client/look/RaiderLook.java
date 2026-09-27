package com.hearthstead.client.look;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.look.CaptainLook;
import com.hearthstead.entity.look.CharacterLooks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Raider skins: per-UUID variants for grunts, brutes, bandits and goblins
 * (static textures), and a runtime-composed persistent look for every
 * captain (saga captains by first name, see {@link CharacterLooks#captainLook}).
 */
public final class RaiderLook {
    private static final String DIR = "textures/entity/look/";
    private static final ResourceLocation[] SKIRMISHERS = series("raider/skirmisher_", CharacterLooks.SKIRMISHER_VARIANTS);
    private static final ResourceLocation[] BRUTES = series("raider/brute_", CharacterLooks.BRUTE_VARIANTS);
    private static final ResourceLocation[] BANDITS = series("raider/bandit_", CharacterLooks.BANDIT_VARIANTS);
    private static final ResourceLocation[] GOBLINS = series("goblin/goblin_", CharacterLooks.GOBLIN_VARIANTS);
    private static final ResourceLocation TOLL_CHIEF = Hearthstead.id(DIR + "raider/toll_chief.png");

    private RaiderLook() {
    }

    private static ResourceLocation[] series(String prefix, int n) {
        ResourceLocation[] out = new ResourceLocation[n];
        for (int i = 0; i < n; i++) {
            out[i] = Hearthstead.id(DIR + prefix + i + ".png");
        }
        return out;
    }

    /** Null when [features] characterSkins is off (or a captain look is not composed yet). */
    public static ResourceLocation texture(RaiderEntity r) {
        if (!CharacterLooks.enabled()) {
            return null;
        }
        if (r.isGoblinThiefDemo()) {
            return GOBLINS[CharacterLooks.raiderVariant(r.getUUID(), GOBLINS.length)];
        }
        if (r.isCaptain()) {
            return captain(r);
        }
        if (CharacterLooks.isTollChief(r)) {
            return TOLL_CHIEF;
        }
        if (CharacterLooks.isRoadBandit(r)) {
            return BANDITS[CharacterLooks.raiderVariant(r.getUUID(), BANDITS.length)];
        }
        return r.variant() == RaiderEntity.Variant.BRUTE
            ? BRUTES[CharacterLooks.raiderVariant(r.getUUID(), BRUTES.length)]
            : SKIRMISHERS[CharacterLooks.raiderVariant(r.getUUID(), SKIRMISHERS.length)];
    }

    private static ResourceLocation captain(RaiderEntity r) {
        CaptainLook look = CharacterLooks.captainLook(r);
        boolean brute = r.variant() == RaiderEntity.Variant.BRUTE;
        boolean marked = r.isSagaMarked();
        return LookTextureCache.get(key(look, brute, marked), 64, 64, layers(look, brute, marked));
    }

    static String key(CaptainLook l, boolean brute, boolean marked) {
        return "c" + (brute ? "b" : "s") + (marked ? "m" : "") + l.scheme() + "." + l.skin() + "." + l.hair()
            + "." + l.paint() + "." + l.scar() + "." + l.helm();
    }

    public static List<ResourceLocation> layers(CaptainLook l, boolean brute, boolean marked) {
        String build = brute ? "brute" : "skirmisher";
        String scheme = CharacterLooks.SCHEMES[l.scheme()];
        List<ResourceLocation> out = new ArrayList<>(6);
        out.add(cap("body_" + build + "_" + scheme + "_" + l.skin()));
        if (marked) {
            out.add(cap("rank_" + build));
        }
        out.add(cap("hair_" + l.hair()));
        out.add(cap("paint_" + l.paint() + "_" + scheme));
        if (l.scar() > 0) {
            out.add(cap("scar_" + l.scar()));
        }
        out.add(cap("helm_" + l.helm() + "_" + scheme));
        return out;
    }

    private static ResourceLocation cap(String name) {
        return Hearthstead.id(DIR + "captain/" + name + ".png");
    }
}

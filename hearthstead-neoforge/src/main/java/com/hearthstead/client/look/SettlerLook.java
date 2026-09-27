package com.hearthstead.client.look;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.look.CharacterGenome;
import com.hearthstead.entity.look.CharacterLooks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Builds a settler's (or event visitor's) layer stack from its genome and
 * returns the composed texture. Composite order mirrors
 * tools/skins/look_settler.compose_settler:
 * skin, age, mark, eyes, brows, hair, clothing, job outfit or costume, beard.
 */
public final class SettlerLook {
    private static final String DIR = "textures/entity/look/settler/";

    private SettlerLook() {
    }

    /** Null when [features] characterSkins is off or the texture is not ready yet. */
    public static ResourceLocation texture(SettlerEntity entity) {
        if (!CharacterLooks.enabled()) {
            return null;
        }
        CharacterGenome g = CharacterLooks.genomeOf(entity);
        Profession p = entity.getProfession();
        int costume = p == Profession.NONE ? CharacterLooks.costumeOf(entity) : 0;
        int variant = CharacterLooks.costumeVariant(entity.getUUID(), costume);
        boolean hero = CharacterLooks.heroHook.test(entity);
        return texture(g, p, costume, variant, hero);
    }

    /** Portrait/map heads: settlers this client may not be tracking. */
    public static ResourceLocation texture(int seed, Profession profession, String name) {
        if (!CharacterLooks.enabled()) {
            return null;
        }
        return texture(CharacterGenome.decode(seed, CharacterGenome.presentationOf(name)),
            profession, 0, 0, false);
    }

    public static ResourceLocation texture(CharacterGenome g, Profession p, int costume, int variant, boolean hero) {
        List<ResourceLocation> layers = layers(g, p, costume, variant, hero);
        String key = "s" + g.skinTone() + "." + g.age() + "." + g.mark() + "." + g.eyes() + "." + g.sex()
            + "." + g.brows() + "." + g.hairStyle() + "." + g.hairColor() + "." + g.clothing() + "." + g.beard()
            + "|" + (hero ? "hero" : costume > 0 ? "c" + costume + "_" + variant : p.key());
        return LookTextureCache.get(key, 128, 64, layers);
    }

    public static List<ResourceLocation> layers(CharacterGenome g, Profession p, int costume, int variant,
                                                boolean hero) {
        List<ResourceLocation> l = new ArrayList<>(10);
        l.add(loc("skin_" + g.skinTone()));
        if (g.age() == 2) {
            l.add(loc("age_old"));
        }
        if (g.mark() > 0) {
            l.add(loc("mark_" + g.mark()));
        }
        l.add(loc("eyes_" + g.eyes() + "_" + g.sex()));
        l.add(loc("brows_" + g.brows() + "_" + g.sex() + "_" + g.hairColor()));
        l.add(loc("hair_" + g.hairStyle() + "_" + g.hairColor()));
        if (hero) {
            l.add(loc("clothing_" + g.clothing()));
            l.add(loc("outfit_hero_captain"));
        } else if (costume > 0) {
            l.add(loc("costume_" + CharacterLooks.COSTUME_KEYS[costume] + "_" + variant));
        } else {
            l.add(loc("clothing_" + g.clothing()));
            // The job outfit layers stay the gen_settler.py ones: the job
            // silhouette is owned by that pipeline, the face by this one.
            l.add(Hearthstead.id("textures/entity/settler/layers/outfit_" + p.key() + ".png"));
        }
        if (g.beard() > 0) {
            l.add(loc("beard_" + g.beard() + "_" + g.hairColor()));
        }
        return l;
    }

    private static ResourceLocation loc(String name) {
        return Hearthstead.id(DIR + name + ".png");
    }
}

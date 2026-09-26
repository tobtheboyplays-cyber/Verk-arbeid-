package com.hearthstead.entity.combat;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner rule: the animation set follows the weapon TYPE, by tag, for every material tier. */
class WeaponClassTest {

    @SafeVarargs
    private static WeaponClass of(boolean bow, TagKey<Item>... tags) {
        Set<TagKey<Item>> set = Set.of(tags);
        return WeaponClass.classify(set::contains, bow);
    }

    @Test
    void everyTagMapsToItsClipSet() {
        assertEquals(WeaponClass.SHORT_SWORD, of(false, WeaponClass.DUAL_SWORDS, ItemTags.SWORDS),
            "short swords are also #minecraft:swords but play the dual clips");
        assertEquals(WeaponClass.GREAT_AXE, of(false, WeaponClass.GREAT_AXES, ItemTags.AXES),
            "a double axe in #minecraft:axes still plays the great-axe clips");
        assertEquals(WeaponClass.HALBERD, of(false, WeaponClass.HALBERDS));
        assertEquals(WeaponClass.WARHAMMER, of(false, WeaponClass.WARHAMMERS));
        assertEquals(WeaponClass.BOW, of(true));
        assertEquals(WeaponClass.BOW, of(false, WeaponClass.BOWS));
        assertEquals(WeaponClass.LONGSWORD, of(false, WeaponClass.LONGSWORDS));
        assertEquals(WeaponClass.SPEAR, of(false, WeaponClass.SPEARS));
        assertEquals(WeaponClass.SWORD, of(false, ItemTags.SWORDS));
        assertEquals(WeaponClass.AXE, of(false, ItemTags.AXES));
        assertEquals(WeaponClass.NONE, of(false));
    }

    @Test
    void clipSetsAreDistinctPerWeaponType() {
        assertEquals("captain_dual", WeaponClass.SHORT_SWORD.clipSet());
        assertEquals("captain_axe", WeaponClass.GREAT_AXE.clipSet());
        assertEquals("guard", WeaponClass.SWORD.clipSet());
        assertEquals("longsword", WeaponClass.LONGSWORD.clipSet());
        assertEquals("archer", WeaponClass.BOW.clipSet());
        assertEquals("captain_halberd", WeaponClass.HALBERD.clipSet());
        assertEquals("captain_hammer", WeaponClass.WARHAMMER.clipSet());
        assertEquals("spear", WeaponClass.SPEAR.clipSet());
        assertEquals("guard", WeaponClass.AXE.clipSet(), "a one-handed axe swings with the guard moveset");
    }

    /**
     * Every weapon type with its own clip set ships its guard plain strike
     * ({@code animations/settler/<clipSet>_strike.animation.json}, additive over GUARD_STANCE) whose
     * length is the weapon's swing, so the contact frame lands on the server's contact tick.
     */
    @Test
    void authoredStrikeClipsMatchTheWeaponTiming() throws java.io.IOException {
        for (WeaponClass w : new WeaponClass[] {WeaponClass.SHORT_SWORD, WeaponClass.GREAT_AXE,
                WeaponClass.HALBERD, WeaponClass.WARHAMMER}) {
            String path = "/assets/hearthstead/animations/settler/" + w.clipSet() + "_strike.animation.json";
            try (java.io.InputStream in = WeaponClassTest.class.getResourceAsStream(path)) {
                if (in == null) {
                    assertTrue(w == WeaponClass.HALBERD || w == WeaponClass.WARHAMMER, "missing " + path);
                    continue;   // authored after the great axe (owner rule 26 Sep)
                }
                com.google.gson.JsonObject anims = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("animations");
                com.google.gson.JsonObject clip = anims.getAsJsonObject("animation.settler." + w.clipSet() + "_strike");
                assertTrue(clip != null, path + " names its animation by key");
                assertEquals(w.swingLength() / 20.0D, clip.get("animation_length").getAsDouble(), 1e-4,
                    w + " strike length = swing length");
            }
        }
    }

    @Test
    void slowerWeaponsWindUpLonger() {
        assertTrue(WeaponClass.WARHAMMER.contactTick() > WeaponClass.GREAT_AXE.contactTick());
        assertTrue(WeaponClass.GREAT_AXE.contactTick() > WeaponClass.HALBERD.contactTick());
        assertTrue(WeaponClass.HALBERD.contactTick() > WeaponClass.SHORT_SWORD.contactTick());
        for (WeaponClass w : WeaponClass.values()) {
            assertTrue(w == WeaponClass.NONE || w.contactTick() < w.swingLength(), w + " contact inside the swing");
        }
        // the Captain's great-axe clips land on tick 9 (0.45 s)
        assertEquals(9, WeaponClass.GREAT_AXE.contactTick());
    }
}

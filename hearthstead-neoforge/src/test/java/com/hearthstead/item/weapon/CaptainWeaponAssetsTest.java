package com.hearthstead.item.weapon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every captain weapon (5 types x 6 tiers) ships a complete, sane set of assets: item model with
 * separate GUI / hand perspectives, a 3D held model whose elements stay inside Minecraft's
 * [-16, 32] box, display transforms in sane ranges, a 32x32 icon, a square held texture, an
 * English name, a recipe, a recipe-book advancement and its captain tag. Plus the trait maths.
 */
class CaptainWeaponAssetsTest {
    static final String[] TIERS = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};
    static final String[] TYPES = {"short_sword", "double_axe", "halberd", "warhammer", "longsword"};

    static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (String type : TYPES) {
            for (String tier : TIERS) {
                out.add(tier + "_" + type);
            }
        }
        return out;
    }

    private static JsonObject json(String path) throws IOException {
        InputStream in = CaptainWeaponAssetsTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing resource " + path);
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static BufferedImage png(String path) throws IOException {
        InputStream in = CaptainWeaponAssetsTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing texture " + path);
        try (in) {
            return ImageIO.read(in);
        }
    }

    @Test
    void everyWeaponHasModelsTexturesAndSaneDisplayTransforms() throws IOException {
        for (String id : ids()) {
            JsonObject item = json("/assets/hearthstead/models/item/" + id + ".json");
            assertEquals("neoforge:separate_transforms", item.get("loader").getAsString(), id);
            assertEquals("hearthstead:item/" + id + "_held",
                item.getAsJsonObject("base").get("parent").getAsString(), id);
            JsonObject gui = item.getAsJsonObject("perspectives").getAsJsonObject("gui");
            assertEquals("hearthstead:item/" + id + "_icon", gui.get("parent").getAsString(), id);

            JsonObject held = json("/assets/hearthstead/models/item/" + id + "_held.json");
            JsonArray elements = held.getAsJsonArray("elements");
            assertTrue(elements.size() > 4, id + ": a real 3D model");
            for (JsonElement e : elements) {
                JsonObject el = e.getAsJsonObject();
                for (String k : new String[] {"from", "to"}) {
                    for (JsonElement c : el.getAsJsonArray(k)) {
                        double v = c.getAsDouble();
                        assertTrue(v >= -16.0D && v <= 32.0D, id + ": element outside [-16, 32]: " + v);
                    }
                }
                assertTrue(el.getAsJsonObject("faces").size() >= 2, id + ": every element has faces");
            }
            JsonObject display = held.getAsJsonObject("display");
            for (String ctx : new String[] {"thirdperson_righthand", "thirdperson_lefthand",
                "firstperson_righthand", "firstperson_lefthand"}) {
                JsonObject d = display.getAsJsonObject(ctx);
                assertNotNull(d, id + ": display " + ctx);
                for (JsonElement t : d.getAsJsonArray("translation")) {
                    assertTrue(Math.abs(t.getAsDouble()) <= 80.0D, id + " " + ctx + " translation");
                }
                for (JsonElement s : d.getAsJsonArray("scale")) {
                    assertTrue(s.getAsDouble() > 0.2D && s.getAsDouble() <= 4.0D, id + " " + ctx + " scale");
                }
                for (JsonElement r : d.getAsJsonArray("rotation")) {
                    assertTrue(Math.abs(r.getAsDouble()) <= 360.0D, id + " " + ctx + " rotation");
                }
            }
            String heldTex = held.getAsJsonObject("textures").get("0").getAsString();
            BufferedImage profile = png("/assets/hearthstead/textures/" + heldTex.substring("hearthstead:".length())
                + ".png");
            assertEquals(profile.getWidth(), profile.getHeight(), id + ": square held texture");
            BufferedImage icon = png("/assets/hearthstead/textures/item/" + id + ".png");
            assertEquals(32, icon.getWidth(), id + ": 32x32 icon");
            assertEquals(32, icon.getHeight(), id + ": 32x32 icon");
        }
    }

    @Test
    void everyWeaponHasANameRecipeAdvancementAndTag() throws IOException {
        JsonObject lang = json("/assets/hearthstead/lang/en_us.json");
        List<String> rewarded = new ArrayList<>();
        for (String tier : TIERS) {
            JsonObject adv = json("/data/hearthstead/advancement/recipes/weapons_" + tier + ".json");
            for (JsonElement r : adv.getAsJsonObject("rewards").getAsJsonArray("recipes")) {
                rewarded.add(r.getAsString());
            }
        }
        for (String id : ids()) {
            assertTrue(lang.has("item.hearthstead." + id), id + ": English name");
            JsonObject recipe = json("/data/hearthstead/recipe/" + id + ".json");
            assertEquals("hearthstead:" + id, recipe.getAsJsonObject("result").get("id").getAsString(), id);
            if (id.startsWith("netherite_")) {
                assertEquals("minecraft:smithing_transform", recipe.get("type").getAsString(), id);
            }
            boolean legacy = id.equals("iron_longsword") || id.equals("diamond_longsword");
            assertTrue(legacy || rewarded.contains("hearthstead:" + id), id + ": recipe-book advancement");
        }
        String[][] tagged = {{"captain/dual_swords", "short_sword"}, {"captain/great_axes", "double_axe"},
            {"captain/halberds", "halberd"}, {"captain/warhammers", "warhammer"}};
        for (String[] t : tagged) {
            JsonArray values = json("/data/hearthstead/tags/item/" + t[0] + ".json").getAsJsonArray("values");
            for (String tier : TIERS) {
                assertTrue(values.toString().contains("hearthstead:" + tier + "_" + t[1]), t[0] + " has " + tier);
            }
        }
        JsonArray longswords = json("/data/hearthstead/tags/item/longswords.json").getAsJsonArray("values");
        for (String tier : TIERS) {
            assertTrue(longswords.toString().contains("hearthstead:" + tier + "_longsword"), "longswords tag: " + tier);
        }
        for (String k : new String[] {"trait", "detail", "captain"}) {
            for (String type : new String[] {"short_sword", "double_axe", "halberd", "warhammer"}) {
                assertTrue(lang.has("item.hearthstead.weapon." + type + "." + k), type + " tooltip " + k);
            }
        }
    }

    @Test
    void traitMathsIsPinned() {
        assertEquals(0.0F, WeaponTraits.shredBonus(0, false, 0.3D, 4.0D, 2.0D), 1.0E-6F);
        assertEquals(3.0F, WeaponTraits.shredBonus(10, false, 0.3D, 4.0D, 2.0D), 1.0E-6F);
        assertEquals(4.0F, WeaponTraits.shredBonus(20, false, 0.3D, 4.0D, 2.0D), 1.0E-6F, "capped");
        assertEquals(6.0F, WeaponTraits.shredBonus(20, true, 0.3D, 4.0D, 2.0D), 1.0E-6F, "cap + Brute");
        assertEquals(2.8F, WeaponTraits.chargeBonus(8.0F, 1.35D), 1.0E-5F);
        // target at x=5 moving -0.25 b/t toward an attacker at x=0: closing 0.25
        assertEquals(0.25D, WeaponTraits.closingSpeed(-0.25D, 0.0D, 5.0D, 0.0D, 0.0D, 0.0D), 1.0E-9D);
        assertEquals(-0.25D, WeaponTraits.closingSpeed(0.25D, 0.0D, 5.0D, 0.0D, 0.0D, 0.0D), 1.0E-9D);
        assertEquals(0.0D, WeaponTraits.closingSpeed(0.0D, 0.3D, 5.0D, 0.0D, 0.0D, 0.0D), 1.0E-9D, "sideways");
    }

    @Test
    void theTableMatchesPlanWeapons() {
        // iron (tier bonus 2): short sword 5 @2.0, double axe 10 @0.8, halberd 8 @0.9, warhammer 9 @0.8
        assertEquals(5.0F, WeaponType.SHORT_SWORD.totalDamage(2.0F), 1.0E-6F);
        assertEquals(10.0F, WeaponType.DOUBLE_AXE.totalDamage(2.0F), 1.0E-6F);
        assertEquals(8.0F, WeaponType.HALBERD.totalDamage(2.0F), 1.0E-6F);
        assertEquals(9.0F, WeaponType.WARHAMMER.totalDamage(2.0F), 1.0E-6F);
        assertEquals(2.0F, WeaponType.SHORT_SWORD.attacksPerSecond(), 1.0E-6F);
        assertEquals(0.8F, WeaponType.DOUBLE_AXE.attacksPerSecond(), 1.0E-5F);
        assertEquals(0.9F, WeaponType.HALBERD.attacksPerSecond(), 1.0E-5F);
        assertEquals(0.8F, WeaponType.WARHAMMER.attacksPerSecond(), 1.0E-5F);
        assertTrue(!WeaponType.SHORT_SWORD.twoHanded() && WeaponType.DOUBLE_AXE.twoHanded()
            && WeaponType.HALBERD.twoHanded() && WeaponType.WARHAMMER.twoHanded());
        assertTrue(WeaponType.DOUBLE_AXE.breaksShields() && WeaponType.WARHAMMER.breaksShields()
            && !WeaponType.HALBERD.breaksShields());
        assertTrue(!WeaponType.WARHAMMER.sweeps());
    }
}

package com.hearthstead.entity.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Layout gate for every generated character texture: right size, every file
 * the runtime compositor can ask for exists, and settler layers only paint
 * inside SettlerModel's UV islands (a stray pixel elsewhere would be a
 * texture nobody can see, or worse, bleed onto a prop sharing that area).
 */
class LookAssetsTest {
    private static final String RELATIVE = "src/main/resources/assets/hearthstead/textures/entity/look";
    private static final File ROOT = root();

    /** Source folder found from the working directory upward (moddev runs JUnit elsewhere), else the classpath copy. */
    private static File root() {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParentFile()) {
            File c = new File(dir, RELATIVE);
            if (c.isDirectory()) {
                return c;
            }
            File m = new File(new File(dir, "hearthstead-neoforge"), RELATIVE);
            if (m.isDirectory()) {
                return m;
            }
        }
        var url = LookAssetsTest.class.getResource("/assets/hearthstead/textures/entity/look");
        try {
            return url == null ? new File(RELATIVE) : new File(url.toURI());
        } catch (java.net.URISyntaxException e) {
            return new File(RELATIVE);
        }
    }

    /** SettlerModel.createBodyLayer UV islands: u, v, w, h, d (mirrors gen_settler.UV). */
    private static final int[][] SETTLER_ISLANDS = {
        {0, 0, 8, 8, 8}, {120, 32, 2, 4, 2}, {32, 0, 8, 8, 8}, {64, 0, 10, 12, 5}, {96, 0, 6, 7, 3},
        {96, 20, 10, 2, 5}, {0, 32, 4, 12, 4}, {16, 32, 4, 12, 4}, {32, 32, 4, 12, 4}, {48, 32, 4, 12, 4},
        {64, 32, 11, 4, 6}, {64, 44, 12, 1, 12}, {0, 17, 7, 6, 6}, {28, 17, 5, 3, 4},
        {0, 49, 1, 10, 1}, {7, 49, 8, 1, 1}, {26, 49, 8, 1, 4}, {50, 49, 2, 8, 2},
        {40, 4, 10, 2, 8}, {99, 3, 4, 3, 3},
    };

    private static BufferedImage read(String rel) {
        File f = new File(ROOT, rel);
        assertTrue(f.isFile(), "missing texture " + f);
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                fail("unreadable " + f);
            }
            return img;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> settlerLayers() {
        List<String> out = new ArrayList<>();
        for (int t = 0; t < CharacterGenome.SKIN_COUNT; t++) out.add("skin_" + t);
        out.add("age_old");
        for (int m = 1; m < CharacterGenome.MARK_COUNT; m++) out.add("mark_" + m);
        for (int e = 0; e < CharacterGenome.EYES_COUNT; e++) {
            for (int s = 0; s < 2; s++) out.add("eyes_" + e + "_" + s);
        }
        for (int c = 0; c < CharacterGenome.HAIR_COLOR_COUNT; c++) {
            for (int b = 0; b < CharacterGenome.BROWS_COUNT; b++) {
                for (int s = 0; s < 2; s++) out.add("brows_" + b + "_" + s + "_" + c);
            }
            for (int st = 0; st < CharacterGenome.HAIR_STYLE_COUNT; st++) out.add("hair_" + st + "_" + c);
            for (int bd = 1; bd < CharacterGenome.BEARD_COUNT; bd++) out.add("beard_" + bd + "_" + c);
        }
        for (int c = 0; c < CharacterGenome.CLOTHING_COUNT; c++) out.add("clothing_" + c);
        for (int k = 1; k < CharacterLooks.COSTUME_KEYS.length; k++) {
            for (int v = 0; v < CharacterLooks.COSTUME_VARIANTS[k]; v++) {
                out.add("costume_" + CharacterLooks.COSTUME_KEYS[k] + "_" + v);
            }
        }
        out.add("outfit_hero_captain");
        return out;
    }

    private static boolean inIsland(int x, int y) {
        for (int[] b : SETTLER_ISLANDS) {
            int u = b[0], v = b[1], w = b[2], h = b[3], d = b[4];
            boolean topRow = y >= v && y < v + d && x >= u + d && x < u + d + 2 * w;
            boolean sideRow = y >= v + d && y < v + d + h && x >= u && x < u + 2 * d + 2 * w;
            if (topRow || sideRow) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everySettlerLayerExistsIs128x64AndStaysOnTheUvMap() {
        for (String name : settlerLayers()) {
            BufferedImage img = read("settler/" + name + ".png");
            assertEquals(128, img.getWidth(), name);
            assertEquals(64, img.getHeight(), name);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 128; x++) {
                    if ((img.getRGB(x, y) >>> 24) != 0 && !inIsland(x, y)) {
                        fail(name + " paints outside the SettlerModel UV map at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void raiderTraderAndCaptainTexturesAre64x64() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < CharacterLooks.SKIRMISHER_VARIANTS; i++) names.add("raider/skirmisher_" + i);
        for (int i = 0; i < CharacterLooks.BRUTE_VARIANTS; i++) names.add("raider/brute_" + i);
        for (int i = 0; i < CharacterLooks.BANDIT_VARIANTS; i++) names.add("raider/bandit_" + i);
        names.add("raider/toll_chief");
        for (int i = 0; i < CharacterLooks.PEDDLER_VARIANTS; i++) names.add("trader/peddler_" + i);
        for (int i = 0; i < CharacterLooks.CARAVAN_VARIANTS; i++) names.add("trader/caravan_master_" + i);
        for (int i = 0; i < CharacterLooks.MERCHANT_VARIANTS; i++) names.add("trader/merchant_" + i);
        for (String build : new String[] {"skirmisher", "brute"}) {
            names.add("captain/rank_" + build);
            for (String scheme : CharacterLooks.SCHEMES) {
                for (int s = 0; s < 4; s++) names.add("captain/body_" + build + "_" + scheme + "_" + s);
            }
        }
        for (int h = 0; h < 9; h++) names.add("captain/hair_" + h);
        for (String scheme : CharacterLooks.SCHEMES) {
            for (int p = 1; p < CaptainLook.PAINTS; p++) names.add("captain/paint_" + p + "_" + scheme);
            for (int h = 0; h < CaptainLook.HELMS; h++) names.add("captain/helm_" + h + "_" + scheme);
        }
        for (int k = 1; k < 5; k++) names.add("captain/scar_" + k);
        for (String n : names) {
            BufferedImage img = read(n + ".png");
            assertEquals(64, img.getWidth(), n);
            assertEquals(64, img.getHeight(), n);
        }
        for (int i = 0; i < CharacterLooks.GOBLIN_VARIANTS; i++) {
            BufferedImage img = read("goblin/goblin_" + i + ".png");
            assertEquals(256, img.getWidth());
            assertEquals(128, img.getHeight());
        }
        BufferedImage atlas = read("accessories.png");
        assertEquals(128, atlas.getWidth());
        assertEquals(64, atlas.getHeight());
    }
}

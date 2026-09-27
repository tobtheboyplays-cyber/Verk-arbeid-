package com.hearthstead.client.techtree;

import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tech tree node icons (icon lane): every node in the tech tree JSON has a
 * hand-made icon at {@code assets/hearthstead/textures/gui/techtree/node/<id>.png},
 * every icon is 32x32 true RGBA, and no icon file is left without a node.
 * A NEW NODE WITHOUT AN ICON FAILS THIS TEST: add the icon with
 * tools/techtree_icons (python generate.py) in the same change.
 */
class TechTreeNodeIconsTest {
    private static final String DIR = "assets/hearthstead/textures/gui/techtree/node/";

    private static Set<String> nodeIds() {
        Set<String> ids = new TreeSet<>();
        for (TechNodeDef node : TechTreeData.get().nodes()) {
            ids.add(node.id());
        }
        assertFalse(ids.isEmpty(), "no tech tree nodes loaded");
        return ids;
    }

    @Test
    void everyNodeHasAClean32x32RgbaIcon() throws IOException {
        List<String> errors = new ArrayList<>();
        for (String id : nodeIds()) {
            try (InputStream in = TechTreeNodeIconsTest.class.getClassLoader().getResourceAsStream(DIR + id + ".png")) {
                if (in == null) {
                    errors.add(id + ": missing icon " + DIR + id + ".png");
                    continue;
                }
                BufferedImage img = ImageIO.read(in);
                if (img == null) {
                    errors.add(id + ": not a readable PNG");
                    continue;
                }
                if (img.getWidth() != 32 || img.getHeight() != 32) {
                    errors.add(id + ": is " + img.getWidth() + "x" + img.getHeight() + ", must be 32x32");
                }
                if (!img.getColorModel().hasAlpha() || img.getColorModel().getNumComponents() != 4
                    || img.getColorModel() instanceof IndexColorModel) {
                    errors.add(id + ": must be true RGBA (PNG colour type 6)");
                }
                int opaque = 0;
                int clear = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int a = img.getRGB(x, y) >>> 24;
                        if (a == 255) {
                            opaque++;
                        } else if (a == 0) {
                            clear++;
                        }
                    }
                }
                if (opaque < 64) {
                    errors.add(id + ": nearly empty (" + opaque + " opaque px)");
                }
                if (clear < 32) {
                    errors.add(id + ": no transparent background (the screen draws the frame)");
                }
            }
        }
        assertTrue(errors.isEmpty(), "tech tree icon problems:\n" + String.join("\n", errors));
    }

    /** The icon source folder: next to a known icon on the classpath, else found by walking up from user.dir. */
    private static Path iconFolder(String anyId) throws URISyntaxException {
        URL url = TechTreeNodeIconsTest.class.getClassLoader().getResource(DIR + anyId + ".png");
        if (url != null && "file".equals(url.getProtocol())) {
            return Paths.get(url.toURI()).getParent();
        }
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; p != null && i < 6; i++, p = p.getParent()) {
            for (Path candidate : List.of(p.resolve("src/main/resources/" + DIR),
                                          p.resolve("hearthstead-neoforge/src/main/resources/" + DIR))) {
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Test
    void noOrphanIcons() throws IOException, URISyntaxException {
        Set<String> ids = nodeIds();
        Path dir = iconFolder(ids.iterator().next());
        assertTrue(dir != null && Files.isDirectory(dir), "icon folder not found (user.dir="
            + System.getProperty("user.dir") + ")");
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.endsWith(".png") || !ids.contains(name.substring(0, name.length() - 4))) {
                    orphans.add(name);
                }
            });
        }
        assertNotNull(orphans);
        assertTrue(orphans.isEmpty(), "icon files without a tech tree node: " + orphans);
    }
}

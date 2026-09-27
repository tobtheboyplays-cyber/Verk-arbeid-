package com.hearthstead.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.entity.Profession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Texture audit lane (26 Sep): no purple/black "missing texture" in game.
 * Every hearthstead texture a model, blockstate or particle JSON names must
 * exist as a PNG, every hearthstead parent/sub-model must exist, and every
 * job has its GUI job icon. Vanilla (no namespace / minecraft:) references
 * are not checked. A NEW MODEL THAT POINTS AT A PNG YOU FORGOT TO ADD FAILS
 * THIS TEST: land the PNG in the same change.
 */
class AssetReferenceGuardTest {
    private static final String NS = "hearthstead";

    private static Path assets() throws URISyntaxException {
        URL url = AssetReferenceGuardTest.class.getClassLoader()
            .getResource("assets/hearthstead/lang/en_us.json");
        assertNotNull(url, "assets/hearthstead not on the test classpath");
        return Paths.get(url.toURI()).getParent().getParent();
    }

    private static JsonElement read(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        }
    }

    /** "hearthstead:item/foo" -> "item/foo"; null for vanilla or other mods. */
    private static String own(String ref) {
        int colon = ref.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return NS.equals(ref.substring(0, colon)) ? ref.substring(colon + 1) : null;
    }

    private static void checkModelRef(Path root, String where, String ref, List<String> errors) {
        String path = own(ref);
        if (path != null && !Files.isRegularFile(root.resolve("models/" + path + ".json"))) {
            errors.add(where + ": missing model " + ref);
        }
    }

    private static void walkModelRefs(Path root, String where, JsonElement e, List<String> errors) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                JsonElement v = en.getValue();
                if ("model".equals(en.getKey()) && v.isJsonPrimitive()) {
                    checkModelRef(root, where, v.getAsString(), errors);
                } else if (!"textures".equals(en.getKey())) {
                    walkModelRefs(root, where, v, errors);
                }
            }
        } else if (e.isJsonArray()) {
            for (JsonElement v : e.getAsJsonArray()) {
                walkModelRefs(root, where, v, errors);
            }
        }
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).toList();
        }
    }

    @Test
    void everyModelTextureAndParentExists() throws IOException, URISyntaxException {
        Path root = assets();
        List<String> errors = new ArrayList<>();
        List<Path> models = jsonFiles(root.resolve("models"));
        assertTrue(models.size() > 50, "expected the item/block models, found " + models.size());
        for (Path p : models) {
            String where = root.relativize(p).toString().replace('\\', '/');
            JsonObject o = read(p).getAsJsonObject();
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> t : o.getAsJsonObject("textures").entrySet()) {
                    if (!t.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String ref = t.getValue().getAsString();
                    if (ref.startsWith("#")) {
                        continue;
                    }
                    String path = own(ref);
                    if (path != null && !Files.isRegularFile(root.resolve("textures/" + path + ".png"))) {
                        errors.add(where + ": texture '" + t.getKey() + "' -> missing " + ref);
                    }
                }
            }
            if (o.has("parent") && o.get("parent").isJsonPrimitive()) {
                checkModelRef(root, where, o.get("parent").getAsString(), errors);
            }
            walkModelRefs(root, where, o, errors);
        }
        assertTrue(errors.isEmpty(), "Model references to missing assets:\n" + String.join("\n", errors));
    }

    @Test
    void everyBlockstateModelExists() throws IOException, URISyntaxException {
        Path root = assets();
        List<String> errors = new ArrayList<>();
        for (Path p : jsonFiles(root.resolve("blockstates"))) {
            walkModelRefs(root, root.relativize(p).toString().replace('\\', '/'), read(p), errors);
        }
        assertTrue(errors.isEmpty(), "Blockstates pointing at missing models:\n" + String.join("\n", errors));
    }

    @Test
    void everyParticleTextureExists() throws IOException, URISyntaxException {
        Path root = assets();
        List<String> errors = new ArrayList<>();
        for (Path p : jsonFiles(root.resolve("particles"))) {
            JsonObject o = read(p).getAsJsonObject();
            if (!o.has("textures")) {
                continue;
            }
            for (JsonElement t : o.getAsJsonArray("textures")) {
                String path = own(t.getAsString());
                if (path != null && !Files.isRegularFile(root.resolve("textures/particle/" + path + ".png"))) {
                    errors.add(root.relativize(p) + ": missing particle texture " + t.getAsString());
                }
            }
        }
        assertTrue(errors.isEmpty(), "Particles pointing at missing textures:\n" + String.join("\n", errors));
    }

    @Test
    void everyHandbookTextureExists() throws IOException, URISyntaxException {
        Path root = assets();
        List<String> errors = new ArrayList<>();
        java.util.regex.Pattern ref = java.util.regex.Pattern.compile("\"hearthstead:(textures/[^\"]+\\.png)\"");
        for (Path p : jsonFiles(root.resolve("handbook"))) {
            java.util.regex.Matcher m = ref.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!Files.isRegularFile(root.resolve(m.group(1)))) {
                    errors.add(root.relativize(p) + ": missing " + m.group(1));
                }
            }
        }
        assertTrue(errors.isEmpty(), "Handbook pages pointing at missing pictures:\n" + String.join("\n", errors));
    }

    @Test
    void everyJobHasAGuiJobIcon() throws URISyntaxException {
        Path root = assets();
        List<String> errors = new ArrayList<>();
        for (Profession p : Profession.values()) {
            if (!Files.isRegularFile(root.resolve("textures/gui/job/" + p.key() + ".png"))) {
                errors.add("textures/gui/job/" + p.key() + ".png");
            }
        }
        assertTrue(errors.isEmpty(), "JobIcons would draw the missing texture for:\n" + String.join("\n", errors));
    }
}

package com.hearthstead.fx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FxEffectTest {

    @Test
    void wireIdsAreUniqueAndRoundTrip() {
        Set<Integer> ids = new HashSet<>();
        for (FxEffect effect : FxEffect.values()) {
            assertTrue(ids.add(effect.wireId()), "duplicate wire id " + effect.wireId() + " on " + effect);
            assertSame(effect, FxEffect.byWireId(effect.wireId()));
        }
        assertNull(FxEffect.byWireId(0));
        assertNull(FxEffect.byWireId(-1));
        assertNull(FxEffect.byWireId(9999));
    }

    @Test
    void keysAndSoundIdsAreUniqueAndResourceSafe() {
        Set<String> keys = new HashSet<>();
        Set<String> sounds = new HashSet<>();
        for (FxEffect effect : FxEffect.values()) {
            assertTrue(keys.add(effect.key()), "duplicate key " + effect.key());
            assertTrue(sounds.add(effect.soundId()), "duplicate sound id " + effect.soundId());
            assertTrue(effect.soundId().matches("[a-z0-9_.]+"), "sound id must be a valid path: " + effect.soundId());
            assertTrue(effect.fallbackSound().isEmpty() || effect.fallbackSound().matches("[a-z0-9_.]+"),
                "fallback must be a vanilla sound path: " + effect.fallbackSound());
            assertTrue(effect.range() > 0 && effect.range() <= 64, effect + " range");
        }
    }

    @Test
    void everyServerMomentIsNetworkedAndAtmosphereIsNot() {
        for (FxEffect effect : new FxEffect[] {FxEffect.SKILL_LEVEL_UP, FxEffect.TECH_LEARNED,
            FxEffect.BUILDING_LEVEL_UP, FxEffect.WAREHOUSE_LEVEL_UP, FxEffect.JOURNEY_CHAPTER,
            FxEffect.CRAFT_GLINT, FxEffect.CRAFT_LEGENDARY, FxEffect.COIN_SALE, FxEffect.BUILD_DONE,
            FxEffect.RAID_WON, FxEffect.SETTLER_WELCOME, FxEffect.CAPTAIN_PROMOTED,
            FxEffect.CAPTAIN_WINDUP, FxEffect.CAPTAIN_IMPACT}) {
            assertTrue(effect.networked(), effect + " starts on the server");
        }
        for (FxEffect effect : new FxEffect[] {FxEffect.BANNER_EMBER, FxEffect.FIREFLY, FxEffect.CANDLE_MOTE,
            FxEffect.WORKSHOP_DUST, FxEffect.BREW_STEAM, FxEffect.FISH_SPLASH, FxEffect.SUMMON_ARRIVAL,
            FxEffect.PATROL_WAYPOINT, FxEffect.ORDER_CONFIRMED}) {
            assertFalse(effect.networked(), effect + " is derived on the client, never sent");
        }
    }

    private static final Path RELATIVE = Path.of("src", "main", "resources", "assets", "hearthstead");

    /** The source assets folder, found by walking up from the test's working directory. */
    private static Path assetsRoot() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(RELATIVE).resolve("particles"))) {
                return dir.resolve(RELATIVE);
            }
            Path module = dir.resolve("hearthstead-neoforge").resolve(RELATIVE);
            if (Files.isDirectory(module.resolve("particles"))) {
                return module;
            }
        }
        var url = FxEffectTest.class.getResource("/assets/hearthstead/particles");
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                return Path.of(url.toURI()).getParent();
            } catch (java.net.URISyntaxException bad) {
                throw new IOException(bad);
            }
        }
        throw new IOException("assets folder not found from " + Path.of("").toAbsolutePath());
    }

    /** Every registered particle type has a definition file and its sprites exist. */
    @Test
    void particleDefinitionsPointAtRealSprites() throws IOException {
        Path assets = assetsRoot();
        String[] types = {"sparkle", "mote", "ember", "firefly", "anvil_spark", "flour_puff", "steam",
            "wood_chip", "coin", "confetti", "dust_mote"};
        Set<String> seen = new HashSet<>();
        for (String type : types) {
            assertTrue(seen.add(type), "duplicate particle id " + type);
            Path def = assets.resolve("particles").resolve(type + ".json");
            assertTrue(Files.exists(def), "missing particle definition " + def);
            String json = Files.readString(def);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("hearthstead:([a-z0-9_]+)").matcher(json);
            int sprites = 0;
            while (m.find()) {
                sprites++;
                Path png = assets.resolve("textures").resolve("particle").resolve(m.group(1) + ".png");
                assertTrue(Files.exists(png), "missing sprite " + png);
            }
            assertTrue(sprites > 0, type + " lists no sprites");
        }
    }
}

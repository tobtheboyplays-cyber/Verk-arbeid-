package com.hearthstead.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner, 27 Sep: "2 keys + handbook". Bannerhold registers exactly Command
 * melee troops, Command ranged troops, Open the Handbook and the unbound
 * Finish, and none of their defaults clashes with vanilla 1.21.1 or the
 * Sunday friend mods (defaults read from the jars' bytecode, see
 * plan/state/keybinds.md).
 */
class KeyDefaultsTest {
    /** Vanilla 1.21.1 default keyboard bindings (GLFW codes), incl. hard-wired F1/F3 and hotbar 1-9. */
    private static final Map<Integer, String> VANILLA = Map.ofEntries(
        Map.entry(87, "W forward"), Map.entry(65, "A left"), Map.entry(83, "S back"), Map.entry(68, "D right"),
        Map.entry(32, "Space jump"), Map.entry(340, "LShift sneak"), Map.entry(341, "LCtrl sprint"),
        Map.entry(69, "E inventory"), Map.entry(70, "F swap offhand"), Map.entry(81, "Q drop"),
        Map.entry(84, "T chat"), Map.entry(47, "/ command"), Map.entry(258, "Tab player list"),
        Map.entry(80, "P social interactions"), Map.entry(76, "L advancements"),
        Map.entry(67, "C save hotbar"), Map.entry(88, "X load hotbar"),
        Map.entry(290, "F1 hide GUI"), Map.entry(291, "F2 screenshot"), Map.entry(292, "F3 debug"),
        Map.entry(294, "F5 perspective"), Map.entry(300, "F11 fullscreen"),
        Map.entry(49, "1"), Map.entry(50, "2"), Map.entry(51, "3"), Map.entry(52, "4"), Map.entry(53, "5"),
        Map.entry(54, "6"), Map.entry(55, "7"), Map.entry(56, "8"), Map.entry(57, "9"));

    /** Sunday pack client mods with bound defaults: Xaero's Minimap 26.5.0 and World Map 1.46.0. */
    private static final Map<Integer, String> FRIEND_MODS = Map.of(
        89, "Y Xaero minimap settings", 66, "B Xaero new waypoint", 85, "U Xaero waypoints",
        90, "Z Xaero enlarge minimap", 334, "Numpad + Xaero quick waypoint", 77, "M Xaero world map",
        93, "] Xaero world map settings", 344, "RShift Xaero quick confirm");

    @Test
    void exactlyTheFourBannerholdKeys() {
        List<String> names = KeyDefaults.ALL.stream().map(KeyDefaults.Binding::name).toList();
        assertEquals(List.of("key.hearthstead.command_melee", "key.hearthstead.command_ranged",
            "key.hearthstead.handbook", "key.hearthstead.finisher"), names);
        // Every "key.hearthstead.*" name in the lang file is a registered binding and vice versa,
        // so the removed J/K/N/H/B/O/all keys cannot linger in Controls or the handbook.
        JsonObject en = english();
        Set<String> lang = new TreeSet<>();
        for (String k : en.keySet()) if (k.startsWith("key.hearthstead.")) lang.add(k);
        assertEquals(new TreeSet<>(names), lang);
        assertEquals("Bannerhold", en.get("key.categories.hearthstead").getAsString());
        assertEquals(KeyDefaults.UNBOUND, KeyDefaults.FINISHER, "Finish stays unbound (falls back to R)");
    }

    @Test
    void defaultsAreRAndGAndJ() {
        assertEquals(82, KeyDefaults.COMMAND_MELEE, "R");
        assertEquals(71, KeyDefaults.COMMAND_RANGED, "G");
        assertEquals(74, KeyDefaults.HANDBOOK, "J");
    }

    @Test
    void noDefaultClashesWithVanillaOrFriendModsOrEachOther() {
        Set<Integer> seen = new HashSet<>();
        for (KeyDefaults.Binding b : KeyDefaults.ALL) {
            if (b.key() == KeyDefaults.UNBOUND) continue;
            assertFalse(VANILLA.containsKey(b.key()), b.name() + " clashes with vanilla " + VANILLA.get(b.key()));
            assertFalse(FRIEND_MODS.containsKey(b.key()),
                b.name() + " clashes with a friend mod: " + FRIEND_MODS.get(b.key()));
            assertTrue(seen.add(b.key()), b.name() + " shares its default with another Bannerhold key");
        }
    }

    private static JsonObject english() {
        var in = KeyDefaultsTest.class.getClassLoader().getResourceAsStream("assets/hearthstead/lang/en_us.json");
        assertNotNull(in, "en_us.json on the test classpath");
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}

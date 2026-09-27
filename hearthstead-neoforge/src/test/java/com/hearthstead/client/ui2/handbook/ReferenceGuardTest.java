package com.hearthstead.client.ui2.handbook;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonObject;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner rule (26 Sep): "make sure it keeps getting updated as we add things".
 * Each guard reads the LIVE enum, registry, data or config spec, so a new job,
 * building, world event, conversation, tech node, key binding or feature
 * switch fails the build until some handbook page documents it.
 *
 * <p>Documented means: an {@code entries} row with {@code ref} =
 * {@code profession:ID}, {@code building:ID}, {@code event:id},
 * {@code conversation:id}, {@code node:id} or {@code option:path} on any page;
 * for key bindings, a key chip naming the KeyMapping on any page. The
 * reference pages are generated from {@code tools/handbook/facts/*.json}: add
 * a row there and run {@code tools/handbook/check.sh} (COORD/DECISIONS.md,
 * HANDBOOK RULE).
 */
class ReferenceGuardTest {
    /**
     * TEMPORARY, dated: refs for features genuinely in progress. Must be
     * empty at the Sunday freeze; after the date nothing is excused.
     */
    private static final LocalDate PENDING_UNTIL = LocalDate.of(2026, 9, 27);
    private static final Set<String> PENDING = Set.of();

    private static void check(String what, Iterable<String> live, Set<String> documented) {
        List<String> missing = new ArrayList<>();
        boolean grace = !LocalDate.now().isAfter(PENDING_UNTIL);
        for (String ref : live) {
            if (documented.contains(ref)) continue;
            if (grace && (PENDING.contains(ref) || PENDING.contains("*"))) continue;
            missing.add(ref);
        }
        assertTrue(missing.isEmpty(), what + " without a handbook entry (add a row to tools/handbook/facts and run "
            + "tools/handbook/check.sh): " + missing);
    }

    @Test
    void everyProfessionIsDocumented() {
        List<String> live = new ArrayList<>();
        for (Profession p : Profession.values()) {
            // MAYOR is only a legacy save id (26): the office was retired for
            // the Guildmaster on 26 Sep and must not have a handbook entry.
            if (p != Profession.MAYOR) live.add("profession:" + p.name());
        }
        check("Professions", live, HandbookTestData.book().refs());
    }

    @Test
    void everyBuildingTypeIsDocumented() {
        List<String> live = new ArrayList<>();
        for (BuildingType b : BuildingType.values()) live.add("building:" + b.name());
        check("Buildings", live, HandbookTestData.book().refs());
    }

    @Test
    void everyWorldEventAndConversationIsDocumented() throws Exception {
        List<String> live = new ArrayList<>();
        for (WorldEventType e : WorldEventType.values()) live.add("event:" + e.id());
        URL dir = getClass().getClassLoader().getResource("data/hearthstead/conversations");
        if (dir != null) {
            try (Stream<Path> files = Files.walk(Path.of(dir.toURI()))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    live.add("conversation:" + f.getFileName().toString().replace(".json", ""));
                }
            }
        }
        check("World events / conversations", live, HandbookTestData.book().refs());
    }

    @Test
    void everyTechNodeIsDocumented() {
        List<String> live = new ArrayList<>();
        for (TechNodeDef n : TechTreeData.get().nodes()) live.add("node:" + n.id());
        assertTrue(live.size() > 20, "tech tree data loaded: " + live.size());
        check("Tech nodes", live, HandbookTestData.book().refs());
    }

    @Test
    void everyKeyBindingAppearsAsAKeyChip() {
        JsonObject en = HandbookTestData.english();
        Set<String> live = new TreeSet<>();
        for (String k : en.keySet()) {
            if (k.startsWith("key.hearthstead.")) live.add(k);
        }
        // Owner, 27 Sep: "2 keys + handbook" (melee, ranged, handbook, unbound finish).
        assertTrue(live.size() >= 4, "mod key bindings found: " + live);
        check("Key bindings", live, HandbookTestData.book().keyNames());
    }

    @Test
    void everyFeatureSwitchAndConfigSectionIsOnTheOptionsPage() {
        Set<String> live = new TreeSet<>();
        for (UnmodifiableConfig spec : List.of(HearthsteadServerConfig.SPEC.getSpec(),
                HearthsteadClientConfig.SPEC.getSpec())) {
            for (var e : spec.valueMap().entrySet()) {
                if (!(e.getValue() instanceof UnmodifiableConfig section)) continue;
                live.add("option:" + e.getKey());
                if (e.getKey().equals("features")) {
                    for (String sw : section.valueMap().keySet()) live.add("option:features." + sw);
                }
            }
        }
        assertTrue(live.contains("option:features"), "config spec read: " + live);
        check("Config sections / feature switches", live, HandbookTestData.book().refs());
    }
}

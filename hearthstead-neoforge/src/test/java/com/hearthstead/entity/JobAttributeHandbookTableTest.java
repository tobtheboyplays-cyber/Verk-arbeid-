package com.hearthstead.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Playtest 27 Sep #3: the handbook's job/attribute table matches JobAttributeProfile. */
class JobAttributeHandbookTableTest {
    @Test
    void handbookTableListsEveryProfileRow() throws IOException {
        String lang;
        try (InputStream in = JobAttributeHandbookTableTest.class.getClassLoader()
                .getResourceAsStream("assets/hearthstead/lang/en_us.json")) {
            if (in == null) {
                throw new IOException("missing en_us.json");
            }
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String table = JsonParser.parseString(lang).getAsJsonObject()
            .get("hearthstead.guide.jobs.attributes.body").getAsString();
        List<String> missing = new ArrayList<>();
        for (JobAttributeProfile profile : JobAttributeProfile.all().values()) {
            List<String> core = new ArrayList<>();
            String support = null;
            for (JobAttributeProfile.Slot slot : profile.slots()) {
                if (slot.importance() == JobAttributeProfile.Importance.CORE) {
                    core.add(cap(slot.attribute().name()));
                } else {
                    support = cap(slot.attribute().name());
                }
            }
            String row = cap(profile.profession().name()) + " — primary " + core.get(0)
                + ", secondary " + core.get(1) + (support == null ? "" : ", support " + support);
            if (!table.contains(row)) {
                missing.add(row);
            }
        }
        assertTrue(missing.isEmpty(), "handbook attribute table is stale; missing rows: " + missing);
    }

    private static String cap(String name) {
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }
}

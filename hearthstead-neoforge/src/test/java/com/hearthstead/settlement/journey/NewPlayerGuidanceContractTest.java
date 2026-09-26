package com.hearthstead.settlement.journey;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the prices a new player reads (Journey steps and the Handbook's Tech
 * Tree page) equal to the prices the server charges. Every number in the
 * checked text must be, in order, the Coin price followed by each goods count.
 * When a cost changes in {@link DevelopmentNode} or {@link JobEmblemCatalog},
 * this fails until the player-facing text is updated too.
 */
class NewPlayerGuidanceContractTest {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    @Test
    void journeyTechStepsQuoteTheChargedNodePrice() throws Exception {
        JsonObject english = language();
        assertPrice(english, step("fj_100_unlock_lumber_camp"),
            price(DevelopmentNode.TIMBER_RIGHTS));
        assertPrice(english, step("fj_200_unlock_warehouse"),
            price(DevelopmentNode.STORES_AND_ROADS));
        assertPrice(english, step("fj_300_unlock_farmhouse"),
            concat(price(DevelopmentNode.CULTIVATED_GROUND),
                price(DevelopmentNode.SHORE_PROVISIONS)));
        assertPrice(english, step("fj_400_unlock_home"),
            price(DevelopmentNode.HOME));
        assertPrice(english, step("fj_420_unlock_tavern"),
            price(DevelopmentNode.HOSPITALITY));
        assertPrice(english, step("fj_500_unlock_first_watch"),
            price(DevelopmentNode.FIRST_WATCH));
        assertTrue(DevelopmentNode.ARM_THE_WATCH.costs().isEmpty(),
            "fj_551 tells the player Arm the Watch is free");
        assertTrue(text(english, step("fj_551_unlock_arm_the_watch")).contains("free"));
    }

    @Test
    void journeyEmblemStepsQuoteTheChargedEmblemPrice() throws Exception {
        JsonObject english = language();
        assertPrice(english, step("fj_120_staff_lumber_camp"), emblem(Profession.LUMBERER));
        assertPrice(english, step("fj_220_staff_warehouse"), emblem(Profession.COURIER));
        assertPrice(english, step("fj_320_staff_farmhouse"),
            concat(emblem(Profession.FARMER), emblem(Profession.FISHER)));
        assertPrice(english, step("fj_520_staff_barracks"), emblem(Profession.GUARD));
        assertPrice(english, step("fj_557_staff_watchtower"), emblem(Profession.ARCHER));
    }

    @Test
    void handbookTechTreePageListsEveryTrunkPrice() throws Exception {
        String body = text(language(), "hearthstead.guide.tech_tree.body");
        assertLine(body, "Lumber Camp", price(DevelopmentNode.TIMBER_RIGHTS));
        assertLine(body, "Stores & Roads", price(DevelopmentNode.STORES_AND_ROADS));
        assertLine(body, "Cultivated Ground", price(DevelopmentNode.CULTIVATED_GROUND));
        assertLine(body, "Home", price(DevelopmentNode.HOME));
        assertLine(body, "Hospitality & Trade", price(DevelopmentNode.HOSPITALITY));
        assertLine(body, "First Watch", price(DevelopmentNode.FIRST_WATCH));
    }

    @Test
    void refusalsAndFirstStepsPointAtTheCurrentScreens() throws Exception {
        JsonObject english = language();
        // The Hearth's knowledge tab is labelled "Tech Tree"; "Growth" and
        // "Development" were older names a new player cannot find.
        for (String key : List.of("hearthstead.development.plan_locked",
                step("fj_100_unlock_lumber_camp"), step("fj_200_unlock_warehouse"),
                step("fj_420_unlock_tavern"), "hearthstead.guide.research.body")) {
            String value = text(english, key);
            assertTrue(value.contains("Tech Tree"), key + " must name the Tech Tree: " + value);
            assertFalse(value.contains("Growth") || value.contains("Development cost"),
                key + " must not use the old tab name: " + value);
        }
        assertTrue(text(english, "hearthstead.development.blocked.materials").contains("goods"),
            "tech-tree refusals must mention goods, not only Coins");
        assertTrue(text(english, "hearthstead.development.blocked.missing").contains("%s"));
        assertTrue(text(english, "hearthstead.guide.coins.body").contains("Traveling Merchant"),
            "the Handbook must explain where the first Coins come from");
        assertFalse(text(english, "hearthstead.message.raid_warning_exact").startsWith("FIRST RAID"),
            "the warning is reused for recurring raids");
    }

    private static List<Integer> price(DevelopmentNode node) {
        List<Integer> out = new ArrayList<>();
        out.add(node.coinCost());
        for (DevelopmentNode.Cost cost : node.materialCosts()) {
            out.add(cost.count());
        }
        return out;
    }

    private static List<Integer> emblem(Profession profession) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        assertNotNull(entry, profession.name());
        List<Integer> out = new ArrayList<>();
        out.add(entry.coinPrice());
        for (DevelopmentNode.Cost cost : entry.goods()) {
            out.add(cost.count());
        }
        return out;
    }

    private static List<Integer> concat(List<Integer> a, List<Integer> b) {
        List<Integer> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void assertPrice(JsonObject english, String key, List<Integer> expected) {
        String value = text(english, key);
        assertEquals(expected, numbers(value), key + " quotes a stale price: " + value);
    }

    private static void assertLine(String body, String label, List<Integer> expected) {
        for (String line : body.split("\n")) {
            if (line.startsWith(label + ": ")) {
                assertEquals(expected, numbers(line), "stale Tech Tree price line: " + line);
                return;
            }
        }
        throw new AssertionError("Tech Tree page has no line for " + label + ": " + body);
    }

    private static List<Integer> numbers(String value) {
        List<Integer> out = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            out.add(Integer.parseInt(matcher.group()));
        }
        return out;
    }

    private static String step(String id) {
        return "journey.hearthstead.step." + id + ".description";
    }

    private static String text(JsonObject english, String key) {
        assertTrue(english.has(key), "missing language key " + key);
        return english.get(key).getAsString();
    }

    private static JsonObject language() throws Exception {
        String path = "assets/hearthstead/lang/en_us.json";
        var stream = NewPlayerGuidanceContractTest.class.getClassLoader()
            .getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

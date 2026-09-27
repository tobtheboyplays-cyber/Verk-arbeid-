package com.hearthstead.client.motion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.client.model.TraderMotionAnimations;
import com.hearthstead.settlement.work.TraderDealScene;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRADER lane: the authored deal clip matches the server's deal clock
 * (TraderDealScene), its props are the Trader's own display items and are in
 * hand exactly when the tally sounds play, and the ledger idle carries its
 * own tally cue.
 */
final class TraderClipContractTest {
    private static final String RES = "src/main/resources/assets/hearthstead";

    private static Path res() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParent()) {
            for (Path p : List.of(dir.resolve(RES), dir.resolve("hearthstead-neoforge").resolve(RES))) {
                if (Files.isDirectory(p)) return p;
            }
        }
        throw new IOException("resources not found");
    }

    private static JsonObject anim(String key) throws IOException {
        Path file = res().resolve("animations/settler/" + key + ".animation.json");
        assertTrue(Files.isRegularFile(file), file + " is authored");
        JsonObject doc = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject a = doc.getAsJsonObject("animations").getAsJsonObject("animation.settler." + key);
        assertNotNull(a, key + " animation name");
        return a;
    }

    private static boolean held(JsonArray props, String item, String hand, float t) {
        for (JsonElement e : props) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("item").getAsString().equals(item) && p.get("hand").getAsString().equals(hand)
                && p.get("from").getAsFloat() <= t && t <= p.get("to").getAsFloat()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void dealClipIsTheServersDealClock() throws IOException {
        JsonObject a = anim("trader_deal");
        float length = a.get("animation_length").getAsFloat();
        assertEquals(TraderDealScene.DEAL_TICKS / 20.0F, length, 1.0E-4F);
        assertEquals(TraderMotionAnimations.TRADER_DEAL.lengthInSeconds(), length, 1.0E-4F, "Java fallback length");
        assertFalse(TraderMotionAnimations.TRADER_DEAL.looping(), "one-shot");
        assertFalse(a.has("loop") && a.get("loop").getAsBoolean(), "one-shot");
        JsonArray props = a.getAsJsonArray("hearthstead_props");
        assertNotNull(props, "the deal has props");
        for (int t : TraderDealScene.COUNT_TICKS) {
            assertTrue(held(props, "hearthstead:prop_coin_purse", "offhand", t / 20.0F), "purse in hand at tick " + t);
        }
        for (int t : TraderDealScene.WRITE_TICKS) {
            assertTrue(held(props, "hearthstead:prop_ledger", "offhand", t / 20.0F), "ledger in hand at tick " + t);
            assertTrue(held(props, "minecraft:feather", "mainhand", t / 20.0F), "quill in hand at tick " + t);
        }
        float commit = TraderDealScene.COMMIT_TICK / 20.0F;
        assertFalse(held(props, "hearthstead:prop_coin_purse", "offhand", commit), "both hands are free for the handover");
        assertFalse(held(props, "hearthstead:prop_ledger", "offhand", commit), "both hands are free for the handover");
        for (JsonElement e : props) {
            assertTrue(e.getAsJsonObject().get("hide_real").getAsBoolean(), "props never draw over a real item");
        }
    }

    @Test
    void ledgerIdleTalliesWithTheBookInHand() throws IOException {
        JsonObject a = anim("idle_trader__v4");
        JsonArray props = a.getAsJsonArray("hearthstead_props");
        JsonArray sounds = a.getAsJsonArray("hearthstead_sounds");
        assertNotNull(props);
        assertNotNull(sounds);
        assertFalse(sounds.isEmpty());
        for (JsonElement e : sounds) {
            JsonObject s = e.getAsJsonObject();
            assertEquals("hearthstead:work.ledger_tally", s.get("sound").getAsString());
            assertTrue(held(props, "hearthstead:prop_ledger", "offhand", s.get("t").getAsFloat()),
                "the tally sounds only while the ledger is open, t=" + s.get("t"));
        }
    }

    @Test
    void propItemsHaveModelsAndOriginalTextures() throws IOException {
        for (String id : List.of("prop_ledger", "prop_coin_purse")) {
            Path model = res().resolve("models/item/" + id + ".json");
            Path tex = res().resolve("textures/item/" + id + ".png");
            assertTrue(Files.isRegularFile(model), model.toString());
            assertTrue(Files.isRegularFile(tex), tex.toString());
            JsonObject m = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
            assertEquals("hearthstead:item/" + id, m.getAsJsonObject("textures").get("layer0").getAsString());
        }
    }
}

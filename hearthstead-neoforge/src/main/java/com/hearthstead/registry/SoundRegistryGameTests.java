package com.hearthstead.registry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Every hearthstead sound event registered at runtime has a sounds.json entry
 * (so no event plays silently or logs "unknown sound"), and the ElevenLabs
 * pass's new events are really in the registry.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SoundRegistryGameTests {

    @GameTest(template = "empty5", timeoutTicks = 20)
    public static void registeredSoundEventsHaveSoundsJsonEntries(GameTestHelper helper) {
        JsonObject sounds;
        try (InputStream in = Hearthstead.class.getResourceAsStream("/assets/hearthstead/sounds.json")) {
            if (in == null) {
                helper.fail("assets/hearthstead/sounds.json is not on the classpath");
                return;
            }
            sounds = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            helper.fail("could not read sounds.json: " + e);
            return;
        }
        List<String> missing = new ArrayList<>();
        int registered = 0;
        for (ResourceLocation id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            if (!Hearthstead.MODID.equals(id.getNamespace())) continue;
            registered++;
            if (!sounds.has(id.getPath())) missing.add(id.getPath());
        }
        for (String required : List.of("raider.brute_roar", "work.quill_scratch", "combat.execution_stinger",
                "combat.execution_stinger.axe", "tavern.clink", "raid.won_fanfare", "patrol.march")) {
            if (!BuiltInRegistries.SOUND_EVENT.containsKey(Hearthstead.id(required))) {
                missing.add("not registered: " + required);
            }
        }
        if (registered < 100) missing.add("only " + registered + " hearthstead sound events registered");
        if (!missing.isEmpty()) {
            helper.fail("sound registry / sounds.json mismatch: " + missing);
            return;
        }
        helper.succeed();
    }
}

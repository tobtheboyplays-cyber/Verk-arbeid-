package com.hearthstead.client.motion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A client-local sound cue on a clip's own timeline (idle flourishes such as
 * a whetstone scrape). Fired by the runtime when the clip's local time
 * crosses {@code t}, so it follows the per-settler phase, playback rate and
 * variant choice exactly. Work-contact sounds stay server-side
 * (WorkSoundSync); these are for idle detail only.
 *
 * <p>JSON, inside the animation object:
 * {@code "hearthstead_sounds": [{"t": 2.6, "sound": "hearthstead:whetstone_scrape",
 * "volume": 0.4, "pitch_jitter": 0.06}]}
 */
public record ClipSound(float t, SoundEvent sound, float volume, float pitchJitter) {

    static ClipSound[] parse(JsonElement element, String where, List<String> warnings) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        List<ClipSound> out = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject obj = entry.getAsJsonObject();
            try {
                float t = obj.get("t").getAsFloat();
                SoundEvent sound = SoundEvent.createVariableRangeEvent(
                    ResourceLocation.parse(obj.get("sound").getAsString()));
                float volume = obj.has("volume") ? obj.get("volume").getAsFloat() : 0.5F;
                float jitter = obj.has("pitch_jitter") ? obj.get("pitch_jitter").getAsFloat() : 0.05F;
                out.add(new ClipSound(t, sound, Math.min(volume, 0.6F), jitter));
            } catch (RuntimeException bad) {
                warnings.add(where + ": bad hearthstead_sounds entry " + obj);
            }
        }
        return out.isEmpty() ? null : out.toArray(new ClipSound[0]);
    }
}

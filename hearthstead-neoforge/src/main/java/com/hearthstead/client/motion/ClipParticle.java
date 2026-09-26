package com.hearthstead.client.motion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * A client-only contact cue on a clip's own timeline: wood chips when the
 * axe bites, sparks off the anvil, soil off the hoe, dust under a brute's
 * club. Fired exactly like {@link ClipSound} when the clip's local time
 * crosses {@code t}, so it lands on the same frame as the contact pose (and
 * the server's WorkSoundSync sound). Every cue is also an <i>impact</i> for
 * the secondary layer: the tool shakes, the hat brim and cloak jolt, the
 * breathing picks up. Display only; never touches world state.
 *
 * <p>JSON (animation object "hearthstead_particles", or the sidecar
 * {@code assets/hearthstead/motion/particles.json} keyed by clip key; a
 * sidecar entry for a base key also covers its {@code __vN} variants,
 * which keep the base's contact times by contract):
 * <pre>
 * {"t": 0.55, "particle": "minecraft:block", "block": "facing",
 *  "count": 6, "at": [0.75, 0.9, -0.15], "spread": 0.12, "speed": 0.08, "impact": 1.0}
 * </pre>
 * {@code at} is [forward, up, right] in blocks from the entity's feet, turned
 * with its body yaw and scaled with the entity. {@code particle} is any
 * simple particle id, {@code "none"} for a pure impact, or a block particle
 * ({@code minecraft:block}, {@code minecraft:falling_dust},
 * {@code minecraft:dust_pillar}) whose {@code block} is a block id,
 * {@code "facing"} (the block at the point -- the actual log, stone or anvil
 * being struck) or {@code "ground"} (the block under the point).
 */
public record ClipParticle(float t, ParticleOptions options, ParticleType<BlockParticleOption> blockType,
                           int blockMode, int count, float forward, float up, float right,
                           float spread, float speed, float impact) {

    public static final int FIXED = 0;
    public static final int GROUND = 1;
    public static final int FACING = 2;

    static ClipParticle[] parse(JsonElement element, String where, List<String> warnings) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        List<ClipParticle> out = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject obj = entry.getAsJsonObject();
            try {
                float t = obj.get("t").getAsFloat();
                String id = obj.has("particle") ? obj.get("particle").getAsString() : "minecraft:block";
                ParticleOptions options = null;
                ParticleType<BlockParticleOption> blockType = null;
                int mode = FIXED;
                if (!id.equals("none")) {
                    ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(id));
                    if (type == ParticleTypes.BLOCK || type == ParticleTypes.DUST_PILLAR
                        || type == ParticleTypes.FALLING_DUST) {
                        @SuppressWarnings("unchecked")
                        ParticleType<BlockParticleOption> bt = (ParticleType<BlockParticleOption>) type;
                        blockType = bt;
                        String blockId = obj.has("block") ? obj.get("block").getAsString() : "facing";
                        if (blockId.equals("ground")) {
                            mode = GROUND;
                        } else if (blockId.equals("facing")) {
                            mode = FACING;
                        } else {
                            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
                            options = new BlockParticleOption(bt, block.defaultBlockState());
                        }
                    } else if (type instanceof SimpleParticleType simple) {
                        options = simple;
                    } else {
                        warnings.add(where + ": particle " + id
                            + " needs options; use a simple particle, a block particle or \"none\"");
                        continue;
                    }
                }
                float[] at = {0.7F, 0.1F, 0.0F};
                if (obj.has("at") && obj.get("at").isJsonArray()) {
                    var arr = obj.getAsJsonArray("at");
                    for (int i = 0; i < 3 && i < arr.size(); i++) at[i] = arr.get(i).getAsFloat();
                }
                int count = obj.has("count") ? Math.max(0, Math.min(24, obj.get("count").getAsInt())) : 5;
                float spread = obj.has("spread") ? obj.get("spread").getAsFloat() : 0.12F;
                float speed = obj.has("speed") ? obj.get("speed").getAsFloat() : 0.06F;
                float impact = obj.has("impact") ? obj.get("impact").getAsFloat() : 1.0F;
                out.add(new ClipParticle(t, options, blockType, mode, count, at[0], at[1], at[2],
                    spread, speed, Math.max(0.0F, Math.min(2.0F, impact))));
            } catch (RuntimeException bad) {
                warnings.add(where + ": bad hearthstead_particles entry " + obj);
            }
        }
        return out.isEmpty() ? null : out.toArray(new ClipParticle[0]);
    }
}

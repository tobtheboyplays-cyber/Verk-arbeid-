package com.hearthstead.client.motion;

import com.google.gson.JsonObject;
import com.hearthstead.client.model.SettlerModel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration guarantee: every Java AnimationDefinition, played through
 * the motion runtime, lands on exactly the pose vanilla KeyframeAnimations
 * produces -- for every clip, at many times (including past a one-shot's
 * end and across a loop seam), at full and partial weight. A second pass
 * round-trips each clip through bedrock JSON (what Blockbench edits) and
 * checks the reloaded clip still evaluates to the same pose.
 */
final class LegacyClipBridgeTest {
    private static final float TOLERANCE = 2.0E-4F;

    @Test
    void everyLegacyClipEvaluatesIdenticallyToVanilla() {
        ModelPart baked = SettlerModel.createBodyLayer().bakeRoot();
        SettlerModel model = new SettlerModel(baked);
        List<ModelPart> parts = baked.getAllParts().toList();
        MotionRig rig = new MotionRig(model);
        Map<String, AnimationDefinition> all = LegacyClipBridge.allKeys();
        assertTrue(all.size() > 90, "expected every settler + raider clip, got " + all.size());
        float[] scratch = new float[3];
        Vector3f vec = new Vector3f();
        int checks = 0;
        for (Map.Entry<String, AnimationDefinition> entry : all.entrySet()) {
            AnimationDefinition def = entry.getValue();
            MotionClip clip = LegacyClipBridge.clipOf(def);
            assertEquals(def.lengthInSeconds(), clip.length(), entry.getKey());
            for (float weight : new float[] {1.0F, 0.6F}) {
                for (int i = 0; i <= 97; i++) {
                    long millis = (long) (def.lengthInSeconds() * 1300.0F * i / 97.0F);
                    parts.forEach(ModelPart::resetPose);
                    KeyframeAnimations.animate(model, def, millis, weight, vec);
                    float[] expected = snapshot(parts);
                    parts.forEach(ModelPart::resetPose);
                    clip.apply(rig, millis / 1000.0F, weight, null, scratch);
                    float[] actual = snapshot(parts);
                    for (int k = 0; k < expected.length; k++) {
                        assertEquals(expected[k], actual[k], TOLERANCE,
                            entry.getKey() + " t=" + millis + "ms w=" + weight + " channel " + k);
                    }
                    checks++;
                }
            }
        }
        assertTrue(checks > 10000);
    }

    @Test
    void bedrockJsonRoundTripKeepsThePose() {
        ModelPart baked = SettlerModel.createBodyLayer().bakeRoot();
        SettlerModel model = new SettlerModel(baked);
        List<ModelPart> parts = baked.getAllParts().toList();
        MotionRig rig = new MotionRig(model);
        float[] scratch = new float[3];
        for (Map.Entry<String, AnimationDefinition> entry : LegacyClipBridge.allKeys().entrySet()) {
            MotionClip clip = LegacyClipBridge.clipOf(entry.getValue());
            String[] key = entry.getKey().split("/");
            JsonObject file = BedrockClipCodec.write("animation." + key[0] + "." + key[1], clip);
            List<String> warnings = new ArrayList<>();
            List<MotionClip> read = new ArrayList<>();
            BedrockClipCodec.read(file, key[0], "test", (k, c) -> {
                assertEquals(entry.getKey(), k);
                read.add(c);
            }, warnings);
            assertTrue(warnings.isEmpty(), warnings.toString());
            assertEquals(1, read.size());
            MotionClip back = read.get(0);
            assertEquals(clip.looping(), back.looping(), entry.getKey());
            assertEquals(clip.length(), back.length(), 1.0E-4F, entry.getKey());
            for (int i = 0; i <= 41; i++) {
                float seconds = clip.length() * 1.2F * i / 41.0F;
                parts.forEach(ModelPart::resetPose);
                clip.apply(rig, seconds, 1.0F, null, scratch);
                float[] expected = snapshot(parts);
                parts.forEach(ModelPart::resetPose);
                back.apply(rig, seconds, 1.0F, null, scratch);
                float[] actual = snapshot(parts);
                for (int k = 0; k < expected.length; k++) {
                    // JSON stores 4 decimals of a degree / pixel.
                    assertEquals(expected[k], actual[k], 2.0E-3F, entry.getKey() + " t=" + seconds);
                }
            }
        }
    }

    private static float[] snapshot(List<ModelPart> parts) {
        float[] out = new float[parts.size() * 9];
        int o = 0;
        for (ModelPart part : parts) {
            out[o++] = part.x;
            out[o++] = part.y;
            out[o++] = part.z;
            out[o++] = part.xRot;
            out[o++] = part.yRot;
            out[o++] = part.zRot;
            out[o++] = part.xScale;
            out[o++] = part.yScale;
            out[o++] = part.zScale;
        }
        return out;
    }
}

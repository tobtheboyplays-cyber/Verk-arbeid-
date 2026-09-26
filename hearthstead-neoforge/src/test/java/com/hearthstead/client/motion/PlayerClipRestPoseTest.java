package com.hearthstead.client.motion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA-ANIM-01: revive clips are additive, and vanilla re-sets only part of each bone, so
 * applying one every frame without a rest pose drifted the player model and left it
 * deformed after the clip. PlayerClips.Model now restores the rest pose at frame start.
 */
class PlayerClipRestPoseTest {

    private static MotionClip clip(String name) throws IOException {
        Path file = null;
        Path rel = Path.of("src/main/resources/assets/hearthstead/animations/player", name + ".animation.json");
        for (Path dir = Path.of("").toAbsolutePath(); dir != null && file == null; dir = dir.getParent()) {
            for (Path candidate : List.of(dir.resolve(rel), dir.resolve("hearthstead-neoforge").resolve(rel))) {
                if (Files.isRegularFile(candidate)) {
                    file = candidate;
                }
            }
        }
        var url = file == null ? PlayerClipRestPoseTest.class.getResource(
            "/assets/hearthstead/animations/player/" + name + ".animation.json") : null;
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                file = Path.of(url.toURI());
            } catch (java.net.URISyntaxException bad) {
                throw new IOException(bad);
            }
        }
        assertNotNull(file, name + " clip file found from " + Path.of("").toAbsolutePath());
        Map<String, MotionClip> clips = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            BedrockClipCodec.read(json, "player", file.toString(), clips::put, warnings);
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
        return clips.get("player/" + name);
    }

    /** Drift in the body fields vanilla setupAnim never re-sets: x, z and zRot. */
    private static float offset(ModelPart part, float x0, float z0, float zRot0) {
        return Math.abs(part.x - x0) + Math.abs(part.z - z0) + Math.abs(part.zRot - zRot0);
    }

    @Test
    void anAdditiveReviveClipDriftsWithoutARestPoseAndStaysPutWithOne() throws IOException {
        // get_up moves the body forward (position z); downed_crawl rolls it (rotation z).
        MotionClip downed = clip("get_up");
        assertNotNull(downed, "player/get_up parses");
        ModelPart root = LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot();
        PlayerClips.Model model = new PlayerClips.Model(root, false);
        MotionRig rig = new MotionRig(root);
        float[] scratch = new float[3];
        float x0 = model.body.x;
        float z0 = model.body.z;
        float zRot0 = model.body.zRot;

        // A moment where the clip moves the body sideways or forward (a field vanilla never re-sets).
        float t = -1.0F;
        for (float s = 0.0F; s <= downed.length(); s += 0.05F) {
            model.restPose();
            downed.apply(rig, s, 1.0F, null, scratch);
            if (offset(model.body, x0, z0, zRot0) > 1.0E-3F) {
                t = s;
                break;
            }
        }
        assertTrue(t >= 0.0F, "get_up moves the body in a field vanilla never re-sets");
        model.restPose();
        downed.apply(rig, t, 1.0F, null, scratch);
        float once = offset(model.body, x0, z0, zRot0);

        // Old lifecycle: the clip lands on last frame's leftovers every frame.
        model.restPose();
        for (int frame = 0; frame < 20; frame++) {
            downed.apply(rig, t, 1.0F, null, scratch);
        }
        assertTrue(offset(model.body, x0, z0, zRot0) > 10.0F * once, "without a rest pose the offset accumulates");

        // New lifecycle: rest pose first, every frame.
        for (int frame = 0; frame < 20; frame++) {
            model.restPose();
            downed.apply(rig, t, 1.0F, null, scratch);
        }
        assertEquals(once, offset(model.body, x0, z0, zRot0), 1.0E-4F, "with a rest pose every frame the offset holds");

        // The clip ends (provider returns null): the next frame's rest pose clears it.
        model.restPose();
        assertEquals(0.0F, offset(model.body, x0, z0, zRot0), 1.0E-6F, "nothing is left after the clip");
        assertEquals(1.0F, model.body.xScale, 0.0F);
    }
}

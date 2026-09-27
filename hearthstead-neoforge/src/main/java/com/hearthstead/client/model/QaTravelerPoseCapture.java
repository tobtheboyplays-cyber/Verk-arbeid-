package com.hearthstead.client.model;

import com.google.gson.Gson;
import com.hearthstead.Hearthstead;
import com.hearthstead.client.QaClientObserver;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/** Bounded, read-only QA capture of actual final geometry; dormant in ordinary play. */
public final class QaTravelerPoseCapture {
    private static final Gson JSON = new Gson();
    private static UUID selected;
    private static String nonce;
    private static int firstTick, lastTick = Integer.MIN_VALUE, count, batches;
    private static boolean armed, finished;
    private static long nextPoll, captureDeadline;

    /** Called by the activated frame observer, including menu frames with no rendered settlers. */
    public static void expireIfNeeded() {
        if (armed && !finished && System.nanoTime() > captureDeadline) {
            finished = true;
            Hearthstead.LOGGER.error("HSQA_TRAVELER_POSE_ERROR selected guest/state not captured before deadline nonce={}",nonce);
        }
    }

    public static void capture(SettlerEntity actor, float age, ModelPart root) {
        if (!QaClientObserver.travelerPoseEnabled() || finished) return;
        if (Minecraft.getInstance().screen != null) return; // do not capture a UI portrait pose
        if (!armed) {
            // One tiny fixed-name mailbox poll per second, only in an activated QA client.
            long now = System.nanoTime();
            if (now < nextPoll) return;
            nextPoll = now + 1_000_000_000L;
            Path request = Minecraft.getInstance().gameDirectory.toPath().resolve("hsqa-traveler-pose-request");
            if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                if (Files.size(request) > 256) throw new IllegalArgumentException("oversize request");
                String[] parts = Files.readString(request).trim().split("\\|", -1);
                if (parts.length != 3 || !parts[0].equals(QaClientObserver.travelerPoseSession())
                    || !parts[1].matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("invalid session/nonce");
                }
                if (parts[1].equals(nonce)) return;
                UUID requested = UUID.fromString(parts[2]);
                if (selected != null && !selected.equals(requested)) throw new IllegalArgumentException("actor changed");
                selected = requested;
                nonce = parts[1];
                armed = true;
                count = 0;
                lastTick = Integer.MIN_VALUE;
                captureDeadline = now + 30_000_000_000L;
            } catch (Exception failure) {
                finished = true;
                Hearthstead.LOGGER.error("HSQA_TRAVELER_POSE_ERROR request rejected", failure);
                return;
            }
        }
        expireIfNeeded();
        if (finished) return;
        if (!actor.getUUID().equals(selected) || actor.tickCount == lastTick) return;
        // Only real grounded, empty-handed guests; no synthetic actors or equipment stand-ins.
        if (!actor.hasTravelerAppearance() || !actor.onGround() || !actor.getMainHandItem().isEmpty()
            || !actor.getOffhandItem().isEmpty() || !root.getChild("traveler_staff").visible) return;
        lastTick = actor.tickCount;
        if (count == 0) firstTick = lastTick;
        if (lastTick - firstTick > 80) {
            finished = true;
            Hearthstead.LOGGER.error("HSQA_TRAVELER_POSE_ERROR incomplete bounded capture nonce={}", nonce);
            return;
        }
        try {
            float partial = Mth.clamp(age - actor.tickCount, 0, 1);
            Vec3 position = new Vec3(Mth.lerp(partial, actor.xo, actor.getX()),
                Mth.lerp(partial, actor.yo, actor.getY()), Mth.lerp(partial, actor.zo, actor.getZ()));
            var record = new TreeMap<String, Object>();
            record.put("schema", 1);
            record.put("nonce", nonce);
            record.put("session", QaClientObserver.travelerPoseSession());
            record.put("runtimeJarSha256", QaClientObserver.travelerPoseJar());
            record.put("actor", selected.toString());
            record.put("index", count);
            record.put("ageTicks", age);
            record.put("tick", lastTick);
            record.put("activity", actor.getActivity().name());
            record.put("position", new double[]{position.x, position.y, position.z});
            record.put("bodyYaw", Mth.rotLerp(partial, actor.yBodyRotO, actor.yBodyRot));
            record.put("scale", actor.getScale());
            record.put("planted", TravelerStaffPose.qaPlanted(actor));
            // render(), unlike visit(), obeys all actual parent visibility/skipDraw flags.
            var vertices = new CaptureVertices();
            root.render(new PoseStack(), vertices, 0, 0, -1);
            record.put("vertices", vertices.rows);
            var parts = new TreeMap<String, Object>();
            root.visit(new PoseStack(), (pose, path, cube, geometry) -> {
                if (parts.containsKey(path)) return;
                ModelPart part = root;
                for (String segment : path.split("/")) if (!segment.isEmpty()) part = part.getChild(segment);
                parts.put(path, new Object[]{new float[]{part.x, part.y, part.z, part.xRot, part.yRot,
                    part.zRot, part.xScale, part.yScale, part.zScale}, part.visible, part.skipDraw});
            });
            record.put("parts", parts);
            root.visit(new PoseStack(), (pose, path, cube, geometry) -> {
                if (!path.equals("/traveler_staff") || cube != 0) return;
                var tip = pose.pose().transformPosition(new org.joml.Vector3f());
                double yaw = Math.toRadians(180 - ((Number) record.get("bodyYaw")).doubleValue());
                double c = Math.cos(yaw), s = Math.sin(yaw), scale = actor.getScale();
                Vec3 worldTip = position.add((-c * tip.x + s * tip.z) * scale,
                    (1.501 - tip.y) * scale, (s * tip.x + c * tip.z) * scale);
                record.put("staffTip", new double[]{worldTip.x,worldTip.y,worldTip.z});
                var support = new ArrayList<double[]>();
                var base = net.minecraft.core.BlockPos.containing(worldTip);
                for (int dy=0; dy>=-2; dy--) {
                    var block = base.offset(0,dy,0);
                    for (var box : actor.level().getBlockState(block).getCollisionShape(actor.level(),block).toAabbs()) {
                        support.add(new double[]{box.minX+block.getX(),box.minY+block.getY(),box.minZ+block.getZ(),
                            box.maxX+block.getX(),box.maxY+block.getY(),box.maxZ+block.getZ()});
                    }
                }
                record.put("supportCollisionBoxes",support);
            });
            var texture = Minecraft.getInstance().getResourceManager().getResourceOrThrow(
                Hearthstead.id("textures/entity/settler/settler_none.png"));
            try (var input = texture.open()) {
                byte[] png = input.readNBytes(1_048_577);
                if (png.length > 1_048_576) throw new IllegalStateException("oversize texture");
                record.put("textureBase64", Base64.getEncoder().encodeToString(png));
            }
            Hearthstead.LOGGER.info("HSQA_TRAVELER_POSE {}", JSON.toJson(record));
            count++;
            // Twelve consecutive actual tick samples cover contact/recovery without open-ended logging.
            if (count == 12) {
                armed = false;
                if (++batches == 2) finished = true;
            }
        } catch (Exception failure) {
            finished = true;
            Hearthstead.LOGGER.error("HSQA_TRAVELER_POSE_ERROR capture failed nonce={}", nonce, failure);
        }
    }

    private static final class CaptureVertices implements VertexConsumer {
        final List<float[]> rows = new ArrayList<>();
        float[] current;
        public VertexConsumer addVertex(float x, float y, float z) {
            if (rows.size() >= 12000) throw new IllegalStateException("vertex budget exceeded");
            current = new float[]{x,y,z,0,0}; rows.add(current); return this;
        }
        public VertexConsumer setUv(float u, float v) { current[3]=u; current[4]=v; return this; }
        public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        public VertexConsumer setUv1(int u,int v) { return this; }
        public VertexConsumer setUv2(int u,int v) { return this; }
        public VertexConsumer setNormal(float x,float y,float z) { return this; }
    }
    private QaTravelerPoseCapture() {}
}

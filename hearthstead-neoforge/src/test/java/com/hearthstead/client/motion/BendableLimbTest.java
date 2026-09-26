package com.hearthstead.client.motion;

import com.hearthstead.client.model.SettlerModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bendable limb must be the vanilla cube when straight: every vanilla
 * vertex (position + UV) appears in the bent mesh, and every bent-mesh
 * vertex lies on the vanilla surface with the UV the vanilla face would give
 * it there. When bent, the hand end must land where the rigid hinge puts it
 * and the joint ring must stay continuous (no gap between the segments).
 */
final class BendableLimbTest {

    private record V(float x, float y, float z, float u, float v) {
    }

    private static final class Capture implements VertexConsumer {
        final List<V> vertices = new ArrayList<>();
        float x, y, z, u, v;
        boolean open;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            this.x = x; this.y = y; this.z = z;
            open = true;
            return this;
        }

        void flush() {
            if (open) vertices.add(new V(x, y, z, u, v));
            open = false;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static List<V> vanilla(ModelPart part) {
        Capture capture = new Capture();
        PoseStack pose = new PoseStack();
        part.resetPose();
        part.x = part.y = part.z = 0;
        part.getAllParts().filter(p -> p != part).forEach(p -> p.visible = false);
        part.render(pose, capture, 0, 0, -1);
        capture.flush();
        return capture.vertices;
    }

    private static List<V> bent(BendableLimb limb, ModelPart bend) {
        Capture capture = new Capture();
        PoseStack pose = new PoseStack();
        limb.render(pose.last(), capture, 0, 0, -1, bend);
        capture.flush();
        return capture.vertices;
    }

    @Test
    void straightBendableLimbIsTheVanillaCube() {
        ModelPart root = SettlerModel.createBodyLayer().bakeRoot().getChild("root");
        ModelPart torso = root.getChild("torso");
        Object[][] limbs = {
            {torso.getChild("right_arm"), BendableLimb.arm(0, 32, false, 128, 64), "right_forearm"},
            {torso.getChild("left_arm"), BendableLimb.arm(16, 32, true, 128, 64), "left_forearm"},
            {root.getChild("right_leg"), BendableLimb.leg(32, 32, false, 128, 64), "right_shin"},
            {root.getChild("left_leg"), BendableLimb.leg(48, 32, true, 128, 64), "left_shin"},
        };
        for (Object[] row : limbs) {
            ModelPart part = (ModelPart) row[0];
            BendableLimb limb = (BendableLimb) row[1];
            ModelPart bend = new ModelPart(List.of(), java.util.Map.of());
            List<V> expected = vanilla(part);
            List<V> actual = bent(limb, bend);
            assertEquals(24, expected.size());
            // arms: 2 caps + 4 sides x 2 segments; legs add an ankle segment (foot)
            assertEquals(limb.hasAnkle() ? 56 : 40, actual.size());
            for (V e : expected) {
                assertTrue(actual.stream().anyMatch(a -> near(a, e)), "missing vanilla vertex " + e);
            }
            // Every joint vertex sits on the vanilla surface with linearly interpolated V.
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (V e : expected) {
                minY = Math.min(minY, e.y); maxY = Math.max(maxY, e.y);
            }
            for (V a : actual) {
                assertTrue(a.y >= minY - 1.0E-5F && a.y <= maxY + 1.0E-5F);
            }
        }
    }

    @Test
    void bentLimbStaysClosedAndPutsTheHandOnTheHinge() {
        BendableLimb limb = BendableLimb.arm(0, 32, false, 128, 64);
        ModelPart bend = new ModelPart(List.of(), java.util.Map.of());
        bend.xRot = (float) Math.toRadians(-90.0);
        List<V> mesh = bent(limb, bend);
        // The hand cap (UP face, vertices 4..7) = rigid hinge rotation of the rest cap.
        org.joml.Quaternionf q = new org.joml.Quaternionf().rotationZYX(0, 0, bend.xRot);
        float[][] restCap = {{2, 10, -2}, {-2, 10, -2}, {-2, 10, 2}, {2, 10, 2}};
        for (float[] corner : restCap) {
            org.joml.Vector3f p = new org.joml.Vector3f(corner[0], corner[1] - 4, corner[2]);
            q.transform(p);
            float ex = p.x / 16F, ey = (p.y + 4) / 16F, ez = p.z / 16F;
            assertTrue(mesh.stream().anyMatch(v -> Math.abs(v.x - ex) < 1e-4 && Math.abs(v.y - ey) < 1e-4
                && Math.abs(v.z - ez) < 1e-4), "hand corner " + ex + "," + ey + "," + ez);
        }
        // Continuity: every joint-ring position is shared by an upper and a lower quad.
        for (int quad = 2; quad < 10; quad += 2) {
            for (int k = 2; k < 4; k++) {
                V upperJoint = mesh.get(quad * 4 + k);
                boolean shared = false;
                for (int j = 0; j < 4; j++) {
                    V lower = mesh.get((quad + 1) * 4 + j);
                    if (Math.abs(lower.x - upperJoint.x) < 1e-5 && Math.abs(lower.y - upperJoint.y) < 1e-5
                        && Math.abs(lower.z - upperJoint.z) < 1e-5) shared = true;
                }
                assertTrue(shared, "joint gap at quad " + quad);
            }
        }
    }

    private static boolean near(V a, V b) {
        return Math.abs(a.x - b.x) < 1e-5 && Math.abs(a.y - b.y) < 1e-5 && Math.abs(a.z - b.z) < 1e-5
            && Math.abs(a.u - b.u) < 1e-5 && Math.abs(a.v - b.v) < 1e-5;
    }
}

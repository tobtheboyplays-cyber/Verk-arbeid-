package com.hearthstead.client.motion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A limb cuboid that bends at its midpoint, in the manner of bendy-lib's
 * BendableCuboid (what PlayerAnimator/Better Combat use), built in-house.
 *
 * <p>The box is the exact vanilla {@code ModelPart.Cube} it replaces -- same
 * box-UV table, same mirror handling, same face winding -- but each of the
 * four side faces is split at the joint into an upper and a lower quad. The
 * vertices form rings:
 * <ul>
 *   <li>top ring: untouched (follows the upper arm / thigh);</li>
 *   <li>joint ring: rotated by HALF the bend and stretched across the bend
 *       direction by 1/cos(angle/2), so the joint keeps the limb's thickness
 *       and the outer side wraps like a filled wedge instead of opening a
 *       gap or pinching to a hinge;</li>
 *   <li>(legs only) ankle ring: rotated by the full bend;</li>
 *   <li>bottom ring: rotated by the full bend -- and for legs additionally by
 *       an ANKLE correction about the ankle ring that keeps the sole level
 *       with the ground, so a bent knee plants a flat foot instead of standing
 *       on one corner of a tilted shin.</li>
 * </ul>
 * At zero bend and zero ankle correction every vertex lands exactly where the
 * vanilla cube puts it, and the texture reads unbroken from shoulder to hand
 * because the V coordinate is split at the same fractions as the geometry.
 *
 * <p>Render-thread only; every scratch object is preallocated, nothing is
 * allocated per frame.
 */
public final class BendableLimb {
    private static final int RING_TOP = 0;
    private static final int RING_JOINT = 1;
    private static final int RING_ANKLE = 2;
    private static final int RING_BOTTOM = 3;
    /** Joint stretch cap: beyond ~140 degrees a real elbow is fully closed anyway. */
    private static final float MAX_STRETCH = 1.9F;

    private final int quadCount;
    /** Per vertex: x, y, z (pixels), u, v (0..1), ring. Four vertices per quad. */
    private final float[] vx, vy, vz, vu, vv;
    private final byte[] ring;
    /** Per quad: rest normal, and which segment (0 upper, 1 lower, 2 foot). */
    private final float[] nx, ny, nz;
    private final byte[] segment;
    private final float pivotY;
    /** Ankle joint along local Y (NaN for arms). */
    private final float ankleY;

    private final Quaternionf full = new Quaternionf();
    private final Quaternionf half = new Quaternionf();
    private final Quaternionf identity = new Quaternionf();
    private final Quaternionf foot = new Quaternionf();
    private final Quaternionf ankleRot = new Quaternionf();
    private final Vector3f stretchDir = new Vector3f();
    private final Vector3f scratch = new Vector3f();
    private final Vector3f normalScratch = new Vector3f();
    private float stretch = 1.0F;

    /**
     * @param u,v      texOffs of the vanilla cube
     * @param x0,y0,z0 box origin (addBox), @param w,h,d box size
     * @param inflate  CubeDeformation grow (uniform)
     * @param mirror   CubeListBuilder.mirror()
     * @param pivotY   joint along local Y (elbow 4, knee 6)
     * @param ankleY   optional ankle along local Y (NaN = none)
     */
    public BendableLimb(int u, int v, float x0, float y0, float z0, float w, float h, float d,
                        float inflate, boolean mirror, float texW, float texH, float pivotY, float ankleY) {
        this.pivotY = pivotY;
        this.ankleY = ankleY;
        boolean ankle = !Float.isNaN(ankleY);
        this.quadCount = ankle ? 14 : 10;
        int verts = quadCount * 4;
        vx = new float[verts]; vy = new float[verts]; vz = new float[verts];
        vu = new float[verts]; vv = new float[verts];
        ring = new byte[verts];
        nx = new float[quadCount]; ny = new float[quadCount]; nz = new float[quadCount];
        segment = new byte[quadCount];

        float minX = x0 - inflate, minY = y0 - inflate, minZ = z0 - inflate;
        float maxX = x0 + w + inflate, maxY = y0 + h + inflate, maxZ = z0 + d + inflate;
        if (mirror) {
            float t = maxX;
            maxX = minX;
            minX = t;
        }
        float f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d,
            f9 = u + d + w + d + w;
        float f10 = v, f11 = v + d, f12 = v + d + h;
        float span = maxY - minY;
        float vMid = f11 + (f12 - f11) * ((pivotY - minY) / span);
        float vAnkle = ankle ? f11 + (f12 - f11) * ((ankleY - minY) / span) : f12;
        int bottomRing = RING_BOTTOM;
        int bottomSeg = ankle ? 2 : 1;

        int q = 0;
        // DOWN (vanilla name; the cap at minY = shoulder/hip end): v4 v3 v7 v0
        q = face(q, mirror, texW, texH, f5, f10, f6, f11, 0, -1, 0, 0,
            maxX, minY, maxZ, RING_TOP, minX, minY, maxZ, RING_TOP,
            minX, minY, minZ, RING_TOP, maxX, minY, minZ, RING_TOP);
        // UP (cap at maxY = hand/foot end): v1 v2 v6 v5
        q = face(q, mirror, texW, texH, f6, f11, f7, f10, 0, 1, 0, bottomSeg,
            maxX, maxY, minZ, bottomRing, minX, maxY, minZ, bottomRing,
            minX, maxY, maxZ, bottomRing, maxX, maxY, maxZ, bottomRing);
        float[][] sides = {
            // WEST: v7 v3 v6 v2
            {f4, f5, -1, 0, 0, minX, minZ, minX, maxZ, minX, maxZ, minX, minZ},
            // NORTH: v0 v7 v2 v1
            {f5, f6, 0, 0, -1, maxX, minZ, minX, minZ, minX, minZ, maxX, minZ},
            // EAST: v4 v0 v1 v5
            {f6, f8, 1, 0, 0, maxX, maxZ, maxX, minZ, maxX, minZ, maxX, maxZ},
            // SOUTH: v3 v4 v5 v6
            {f8, f9, 0, 0, 1, minX, maxZ, maxX, maxZ, maxX, maxZ, minX, maxZ},
        };
        for (float[] sd : sides) {
            float u1 = sd[0], u2 = sd[1];
            float ax = sd[5], az = sd[6], bx = sd[7], bz = sd[8], cx = sd[9], cz = sd[10], dx = sd[11], dz = sd[12];
            // Upper quad: top edge -> joint.
            q = face(q, mirror, texW, texH, u1, f11, u2, vMid, sd[2], sd[3], sd[4], 0,
                ax, minY, az, RING_TOP, bx, minY, bz, RING_TOP,
                cx, pivotY, cz, RING_JOINT, dx, pivotY, dz, RING_JOINT);
            if (ankle) {
                // Shin: joint -> ankle (full knee), then foot: ankle -> sole (knee + ankle).
                q = face(q, mirror, texW, texH, u1, vMid, u2, vAnkle, sd[2], sd[3], sd[4], 1,
                    ax, pivotY, az, RING_JOINT, bx, pivotY, bz, RING_JOINT,
                    cx, ankleY, cz, RING_ANKLE, dx, ankleY, dz, RING_ANKLE);
                q = face(q, mirror, texW, texH, u1, vAnkle, u2, f12, sd[2], sd[3], sd[4], 2,
                    ax, ankleY, az, RING_ANKLE, bx, ankleY, bz, RING_ANKLE,
                    cx, maxY, cz, RING_BOTTOM, dx, maxY, dz, RING_BOTTOM);
            } else {
                q = face(q, mirror, texW, texH, u1, vMid, u2, f12, sd[2], sd[3], sd[4], 1,
                    ax, pivotY, az, RING_JOINT, bx, pivotY, bz, RING_JOINT,
                    cx, maxY, cz, RING_BOTTOM, dx, maxY, dz, RING_BOTTOM);
            }
        }
    }

    public BendableLimb(int u, int v, float x0, float y0, float z0, float w, float h, float d,
                        float inflate, boolean mirror, float texW, float texH, float pivotY) {
        this(u, v, x0, y0, z0, w, h, d, inflate, mirror, texW, texH, pivotY, Float.NaN);
    }

    /** Standard vanilla arm (4x12x4 at -2,-2,-2, elbow at y=4). */
    public static BendableLimb arm(int u, int v, boolean mirror, float texW, float texH) {
        return new BendableLimb(u, v, -2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, 0.0F, mirror, texW, texH, 4.0F);
    }

    /** Standard vanilla leg (4x12x4 at -2,0,-2, knee at y=6, ankle at y=10 above a 2 px foot). */
    public static BendableLimb leg(int u, int v, boolean mirror, float texW, float texH) {
        return new BendableLimb(u, v, -2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, 0.0F, mirror, texW, texH,
            6.0F, 10.0F);
    }

    /** Vanilla Polygon: vertex 0 -> (u2,v1), 1 -> (u1,v1), 2 -> (u1,v2), 3 -> (u2,v2); mirror reverses. */
    private int face(int q, boolean mirror, float texW, float texH,
                     float u1, float v1, float u2, float v2,
                     float nxr, float nyr, float nzr, int seg,
                     float x0, float y0, float z0, int r0, float x1, float y1, float z1, int r1,
                     float x2, float y2, float z2, int r2, float x3, float y3, float z3, int r3) {
        float[] px = {x0, x1, x2, x3};
        float[] py = {y0, y1, y2, y3};
        float[] pz = {z0, z1, z2, z3};
        int[] pr = {r0, r1, r2, r3};
        float[] pu = {u2 / texW, u1 / texW, u1 / texW, u2 / texW};
        float[] pv = {v1 / texH, v1 / texH, v2 / texH, v2 / texH};
        for (int k = 0; k < 4; k++) {
            int src = mirror ? 3 - k : k;
            int dst = q * 4 + k;
            vx[dst] = px[src];
            vy[dst] = py[src];
            vz[dst] = pz[src];
            vu[dst] = pu[src];
            vv[dst] = pv[src];
            ring[dst] = (byte) pr[src];
        }
        nx[q] = mirror ? -nxr : nxr;
        ny[q] = nyr;
        nz[q] = nzr;
        segment[q] = (byte) seg;
        return q + 1;
    }

    public float pivotY() {
        return pivotY;
    }

    public boolean hasAnkle() {
        return !Float.isNaN(ankleY);
    }

    /** True when the bend part carries any rotation worth drawing. */
    public static boolean isBent(ModelPart bend) {
        return Math.abs(bend.xRot) > 1.0E-4F || Math.abs(bend.yRot) > 1.0E-4F
            || Math.abs(bend.zRot) > 1.0E-4F;
    }

    public static boolean isIdentity(Quaternionf q) {
        return q == null || (Math.abs(q.x) < 1.0E-5F && Math.abs(q.y) < 1.0E-5F && Math.abs(q.z) < 1.0E-5F);
    }

    /** Applies the lower-segment transform (hand/foot space) to a pose stack. */
    public void applyLowerTransform(PoseStack pose, ModelPart bend) {
        applyLowerTransform(pose, bend, null);
    }

    /** Lower segment, then (legs) the ankle correction about the ankle pivot. */
    public void applyLowerTransform(PoseStack pose, ModelPart bend, Quaternionf ankle) {
        if (isBent(bend)) {
            pose.translate(0.0F, pivotY / 16.0F, 0.0F);
            pose.mulPose(full.rotationZYX(bend.zRot, bend.yRot, bend.xRot));
            pose.translate(0.0F, -pivotY / 16.0F, 0.0F);
        }
        if (hasAnkle() && !isIdentity(ankle)) {
            pose.translate(0.0F, ankleY / 16.0F, 0.0F);
            pose.mulPose(ankleRot.set(ankle));
            pose.translate(0.0F, -ankleY / 16.0F, 0.0F);
        }
    }

    /** Draws the bent limb in the limb's own (already translated/rotated/scaled) space. */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color,
                       ModelPart bend) {
        render(pose, consumer, light, overlay, color, bend, null);
    }

    /**
     * @param ankle foot correction relative to the shin (legs; null or identity = none)
     */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color,
                       ModelPart bend, Quaternionf ankle) {
        full.rotationZYX(bend.zRot, bend.yRot, bend.xRot);
        identity.identity();
        identity.slerp(full, 0.5F, half);
        boolean useAnkle = hasAnkle() && !isIdentity(ankle);
        if (useAnkle) {
            ankleRot.set(ankle);
            full.mul(ankleRot, foot);
        } else {
            foot.set(full);
        }
        // Joint stretch across the bend direction keeps the elbow as thick as the arm.
        float angle = 2.0F * (float) Math.acos(Math.min(1.0F, Math.abs(full.w)));
        stretch = 1.0F;
        stretchDir.set(0.0F);
        if (angle > 1.0E-3F) {
            float s = (float) Math.sqrt(Math.max(0.0F, 1.0F - full.w * full.w));
            float ax = full.x / s, az = full.z / s;
            // Bend direction = axis x Y (perpendicular to limb and axis), in the XZ plane.
            float dirX = -az, dirZ = ax;
            float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 0.1F) {
                stretchDir.set(dirX / len, 0.0F, dirZ / len);
                float c = (float) Math.cos(angle * 0.5F);
                stretch = Math.min(MAX_STRETCH, 1.0F / Math.max(0.05F, c));
                // Only the part of the axis that is not pure twist bends the joint.
                stretch = 1.0F + (stretch - 1.0F) * Math.min(1.0F, len);
            }
        }
        Matrix4f matrix = pose.pose();
        for (int q = 0; q < quadCount; q++) {
            Quaternionf normalRot = segment[q] == 0 ? identity : segment[q] == 1 ? full : foot;
            normalScratch.set(nx[q], ny[q], nz[q]);
            normalRot.transform(normalScratch);
            pose.transformNormal(normalScratch, normalScratch);
            float fnx = normalScratch.x, fny = normalScratch.y, fnz = normalScratch.z;
            for (int k = 0; k < 4; k++) {
                int i = q * 4 + k;
                float x = vx[i], y = vy[i], z = vz[i];
                int r = ring[i];
                if (r != RING_TOP) {
                    if (r == RING_BOTTOM && useAnkle) {
                        // Foot: level it about the ankle first (shin frame), then the knee.
                        scratch.set(x, y - ankleY, z);
                        ankleRot.transform(scratch);
                        scratch.y += ankleY;
                        scratch.y -= pivotY;
                    } else {
                        scratch.set(x, y - pivotY, z);
                    }
                    if (r == RING_JOINT) {
                        if (stretch != 1.0F) {
                            float along = scratch.x * stretchDir.x + scratch.z * stretchDir.z;
                            float extra = along * (stretch - 1.0F);
                            scratch.x += stretchDir.x * extra;
                            scratch.z += stretchDir.z * extra;
                        }
                        half.transform(scratch);
                    } else {
                        full.transform(scratch);
                    }
                    x = scratch.x;
                    y = scratch.y + pivotY;
                    z = scratch.z;
                }
                matrix.transformPosition(x / 16.0F, y / 16.0F, z / 16.0F, scratch);
                consumer.addVertex(scratch.x, scratch.y, scratch.z, color, vu[i], vv[i],
                    overlay, light, fnx, fny, fnz);
            }
        }
    }

    // Kept for tests: the rest-pose vertex positions must equal vanilla's cube.
    float restX(int vertex) { return vx[vertex]; }
    float restY(int vertex) { return vy[vertex]; }
    float restZ(int vertex) { return vz[vertex]; }
    int vertexCount() { return quadCount * 4; }
}

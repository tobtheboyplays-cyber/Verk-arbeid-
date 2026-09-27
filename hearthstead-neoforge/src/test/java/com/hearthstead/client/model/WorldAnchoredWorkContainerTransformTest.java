package com.hearthstead.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WorldAnchoredWorkContainerTransformTest {
    // Production model coordinates are floats. This is still below four
    // thousandths of a model pixel while allowing the final float round-trip.
    private static final double EPSILON = 2.0E-4D;

    @Test
    void placedContainerPositionSurvivesFullWorkerRotation() {
        double[][] deltas = {
            {0.0D, 0.0D},
            {1.25D, -0.75D},
            {-3.5D, 2.125D},
        };
        for (int yaw = -180; yaw <= 180; yaw += 15) {
            for (double[] delta : deltas) {
                SettlerModel.WorldAnchoredContainerTransform transform =
                    SettlerModel.worldAnchoredContainerTransform(
                        delta[0], delta[1], yaw);
                double[] world = rendererWorldPosition(transform, yaw);
                assertEquals(delta[0], world[0], EPSILON,
                    "world X changed at body yaw " + yaw);
                assertEquals(delta[1] + 3.0D / 16.0D, world[1], EPSILON,
                    "world Z/mesh centring changed at body yaw " + yaw);
            }
        }
    }

    @Test
    void placedContainerHeadingDoesNotSpinWithWorker() {
        double[][] axes = {
            {1.0D, 0.0D},
            {0.0D, 1.0D},
            {0.6D, -0.8D},
        };
        for (int yaw = -180; yaw <= 180; yaw += 15) {
            SettlerModel.WorldAnchoredContainerTransform transform =
                SettlerModel.worldAnchoredContainerTransform(0.0D, 0.0D,
                    yaw);
            for (double[] axis : axes) {
                double[] world = rendererWorldDirection(axis,
                    transform.yRotRadians(), yaw);
                // The stable authored ground heading is R(180deg) * mirror.
                assertEquals(axis[0], world[0], EPSILON,
                    "world-facing X changed at body yaw " + yaw);
                assertEquals(-axis[1], world[1], EPSILON,
                    "world-facing Z changed at body yaw " + yaw);
            }
        }
    }

    private static double[] rendererWorldPosition(
            SettlerModel.WorldAnchoredContainerTransform transform,
            float bodyYawDegrees) {
        double modelX = transform.xPixels() / 16.0D;
        double modelZ = transform.zPixels() / 16.0D;
        return rotate(modelX * -1.0D, modelZ,
            Math.toRadians(180.0D - bodyYawDegrees));
    }

    private static double[] rendererWorldDirection(double[] modelDirection,
                                                    float childYawRadians,
                                                    float bodyYawDegrees) {
        double[] child = rotate(modelDirection[0], modelDirection[1],
            childYawRadians);
        return rotate(child[0] * -1.0D, child[1],
            Math.toRadians(180.0D - bodyYawDegrees));
    }

    /** JOML/Axis.YP convention used by PoseStack: x'=cx+sz, z'=-sx+cz. */
    private static double[] rotate(double x, double z, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[] {
            cos * x + sin * z,
            -sin * x + cos * z,
        };
    }
}

package com.hearthstead.client.model;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TravelerStaffMotionTest {
    @Test
    void staffContactUsesActualStairTreadAndRejectsUnsupportedFenceEdge() {
        var stair = Shapes.or(Shapes.box(0, 0, 0, 1, .5, 1),
            Shapes.box(0, .5, .5, 1, 1, 1));
        assertEquals(.5, TravelerStaffPose.surfaceTop(stair, .5, .25), 1e-9);
        assertEquals(1, TravelerStaffPose.surfaceTop(stair, .5, .75), 1e-9);
        var post = Shapes.box(.375, 0, .375, .625, 1.5, .625);
        assertFalse(Double.isFinite(TravelerStaffPose.surfaceTop(post, .1, .1)));
        assertEquals(1.5, TravelerStaffPose.surfaceTop(post, .5, .5), 1e-9);
    }

    @Test
    void plantStaysAtExactWorldPointWhileActorMovesAndWhileStopped() {
        TravelerStaffMotion motion = new TravelerStaffMotion();
        Vec3 ground = new Vec3(-.43, 0, .12);
        assertEquals(ground, motion.sample(Vec3.ZERO, ground, 0));
        for (int step = 1; step <= 5; step++) {
            Vec3 actor = new Vec3(0, 0, step * .04);
            assertEquals(ground, motion.sample(actor, ground.add(actor), step));
            assertTrue(motion.planted());
        }
        assertEquals(ground, motion.sample(new Vec3(0, 0, .2), ground.add(0, 0, .2), 100));
    }

    @Test
    void interruptedRecoveryFinishesWithoutAdvancingStoppedFeet() {
        TravelerStaffMotion motion = new TravelerStaffMotion();
        Vec3 ground = new Vec3(-.43, 0, .12);
        motion.sample(Vec3.ZERO, ground, 0);
        Vec3 stopped = new Vec3(0, 0, .31);
        Vec3 destination = ground.add(stopped);
        assertEquals(ground, motion.sample(stopped, destination, 1));
        assertFalse(motion.planted());
        Vec3 raised = motion.sample(stopped, destination, 5);
        assertTrue(raised.y > 0);
        assertEquals(destination, motion.sample(stopped, destination, 9));
        assertTrue(motion.planted());
        assertEquals(destination, motion.sample(stopped, destination, 100));
    }

    @Test
    void fullCycleHasGroundEndpointsAndNoContactDip() {
        TravelerStaffMotion motion = new TravelerStaffMotion();
        Vec3 ground = new Vec3(-.43, 0, .12);
        motion.sample(Vec3.ZERO, ground, 0);
        motion.sample(new Vec3(0, 0, .31), ground.add(0, 0, .31), 1);
        for (int quarter = 0; quarter <= 4; quarter++) {
            double travel = .31 + .22 * quarter / 4;
            Vec3 tip = motion.sample(new Vec3(0, 0, travel), ground.add(0, 0, travel), 1 + quarter);
            assertTrue(tip.y >= -1e-9 && tip.y <= TravelerStaffMotion.LIFT_HEIGHT + 1e-9);
            if (quarter == 0 || quarter == 4) assertEquals(0, tip.y, 1e-9);
        }
        assertTrue(motion.planted());
    }

    @Test
    void realArmSolverReachesEveryNormalPlantAndLiftGripWithoutStretch() {
        Vec3 shoulder = new Vec3(-6, -10, 0);
        for (int sample = 0; sample <= 20; sample++) {
            double drift = -.30 * sample / 20;
            Vec3 palm = new Vec3(-.43 * 16, -4, (.12 + drift) * -16);
            var arm = TavernServingHandPose.solve(shoulder, palm, -1);
            assertNotNull(arm);
            assertEquals(4, shoulder.distanceTo(arm.elbow()), 1e-8);
            assertEquals(6, arm.elbow().distanceTo(palm), 1e-8);
        }
    }
}

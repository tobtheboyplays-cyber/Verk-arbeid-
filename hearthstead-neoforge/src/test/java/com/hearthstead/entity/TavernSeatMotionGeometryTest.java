package com.hearthstead.entity;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the actual production cuboid intersection, not a duplicate SAT implementation. */
class TavernSeatMotionGeometryTest {
    @Test
    void nearlyParallelAxesCannotEraseAnActualSolidOverlap() {
        for(double angle:new double[]{1e-5,5e-5,9e-5}) {
            for(Vec3 center:new Vec3[]{Vec3.ZERO,new Vec3(-11_001_950.5,-59,8_177_250.5)}) {
                Vec3[] corners=new Vec3[8];
                double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=maxX,maxZ=maxX;
                for(int i=0;i<8;i++) {
                    double x=(i&1)==0?-.5:.5,y=(i&2)==0?-.5:.5,z=(i&4)==0?-.5:.5;
                    Vec3 v=center.add(x*Math.cos(angle)-y*Math.sin(angle),x*Math.sin(angle)+y*Math.cos(angle),z);
                    corners[i]=v;minX=Math.min(minX,v.x);minY=Math.min(minY,v.y);minZ=Math.min(minZ,v.z);
                    maxX=Math.max(maxX,v.x);maxY=Math.max(maxY,v.y);maxZ=Math.max(maxZ,v.z);
                }
                var mesh=new TavernSeatMotion.MeshBox(new AABB(minX,minY,minZ,maxX,maxY,maxZ),corners);
                AABB inside=new AABB(center.x-.1,center.y-.1,center.z-.1,center.x+.1,center.y+.1,center.z+.1);
                assertTrue(mesh.intersects(inside),"a deeply contained solid must collide even with near-parallel axes, angle="+angle);
                assertFalse(mesh.intersects(inside.move(2,0,0)),"a separate solid remains separate");
            }
        }
    }
}

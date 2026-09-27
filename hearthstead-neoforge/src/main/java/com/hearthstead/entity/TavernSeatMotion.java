package com.hearthstead.entity;

import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** One original table-side motion, shared by actual rider placement and its limb projection. */
public final class TavernSeatMotion {
    public static final int TICKS = 44;
    /** At the finished lateral pose, the hips remain behind the table-facing chair edge. */
    private static final double ANOTHER_FURNITURE_SETTLED_FRONT = .125 - Math.sqrt(5.0) / 12.0;
    private static final double ANOTHER_FURNITURE_FRONT_LANE_ADVANCE = .25 - Math.sqrt(5.0) / 12.0;
    /**
     * Another Furniture seats (tavern lane, 26 Sep): the old frame kept the whole body 7/16 above
     * the vanilla stair height, so the thighs hovered half a block over the real seat and the feet
     * stood on it. The side-step onto the seat is unchanged (both feet lifted onto the seat top:
     * with a horizontal thigh a foot on the floor and one on the seat is unreachable), but it now
     * ends by {@link #AF_LATERAL_END}; the last eight ticks sit DOWN: the hip lowers onto the seat
     * surface (Furniture.seatTop) and moves forward so the knees clear the seat's front edge, while
     * the feet slide off the seat's front edge and down onto the floor under the tabletop.
     */
    static final double AF_LATERAL_END = 36;
    private static final double AF_FINAL_ANKLE_Y = .126;
    public record Leg(float x, float y, float z, float shinX, float shinY, float shinZ, float bootX, float bootY, float bootZ) {}
    public record Frame(Vec3 origin, float yaw, float bend, Vec3 rightFoot, Vec3 leftFoot, Leg right, Leg left) {}
    private TavernSeatMotion() {}
    public static Vec3 staging(TavernSeating.SeatSite site) {
        return Vec3.atBottomCenterOf(site.aisle()).add(front(site).scale(stagingForward(site)));
    }
    /** The saved vehicle anchor and its final articulated frame must agree exactly. */
    public static Vec3 seatedAnchor(TavernSeating.SeatSite site) {
        Vec3 f = front(site);
        if (site.furniture().another()) {
            // Hip ON the seat surface; knees just past the seat's front edge.
            double front = afHipFront(site.furniture());
            return new Vec3(site.chair().getX() + .5 + f.x * front,
                site.chair().getY() + site.furniture().seatTop() - .75,
                site.chair().getZ() + .5 + f.z * front);
        }
        return new Vec3(site.chair().getX() + .5 + f.x * .25,
            site.chair().getY() - .25,
            site.chair().getZ() + .5 + f.z * .25);
    }
    /** Feet ride the seat top while side-stepping over it (optional furniture only). */
    private static double furnitureSeatLift(TavernSeating.SeatSite site) {
        return site.furniture().another() ? site.furniture().seatTop() : 0;
    }
    /** Seated hip forward of the seat block centre: the 4 px thigh link ends at the seat edge. */
    static double afHipFront(TavernSeating.Furniture furniture) {
        return furniture.seatEdge() - .25;
    }
    /** Planted boot centre forward of the seat block centre: clear of the seat edge, reachable. */
    static double afFootFront(TavernSeating.Furniture furniture) {
        double drop = (furniture.seatTop() - afFootLift(furniture) - AF_FINAL_ANKLE_Y) * 16;
        double reach = (4 + Math.sqrt(Math.max(0, 36 - drop * drop))) / 16;
        return Math.min(furniture.seatEdge() + .2, afHipFront(furniture) + .125 + .85 * reach);
    }
    /** A seat higher than the shin (stool, 8/16) lets the boots rest a hair above the floor. */
    static double afFootLift(TavernSeating.Furniture furniture) {
        return Math.max(0, furniture.seatTop() - AF_FINAL_ANKLE_Y - 5.6 / 16);
    }
    private static double stagingForward(TavernSeating.SeatSite site) {
        // Another Furniture tables retain physical corner legs, so this stays in the
        // chair-side lane rather than advancing through the table-facing corner.
        return site.furniture().another() ? .25 : .625;
    }
    private static Vec3 front(TavernSeating.SeatSite site) {
        return new Vec3(site.dinerFacing().getStepX(), 0, site.dinerFacing().getStepZ());
    }
    private static double smooth(double v) { v = Math.max(0, Math.min(1, v)); return v*v*(3-2*v); }
    public static Frame sample(TavernSeating.SeatSite site, double ticks) {
        // Lower in the clear side aisle, then make three pairs of lifted placements.
        // A planted foot never follows the pelvis's weight transfer. On optional
        // furniture the feet ride the seat top while stepping over it, then the
        // last eight ticks sit down onto the real seat (see AF_LATERAL_END).
        boolean optional = site.furniture().another();
        double lateralTicks = optional ? AF_LATERAL_END - 8 : 36;
        double p = Math.max(0, Math.min(1, (ticks-8) / lateralTicks));
        double bend = smooth(ticks/8);
        double seatLift = furnitureSeatLift(site) * bend;
        Vec3 f = front(site), l = new Vec3(f.z, 0, -f.x);
        Vec3 side = Vec3.atBottomCenterOf(site.aisle()).subtract(Vec3.atBottomCenterOf(site.chair()));
        Vec3 start = Vec3.atBottomCenterOf(site.aisle()).add(f.scale(stagingForward(site)));
        boolean leftLeads = side.dot(l) < 0;
        int step = Math.min(5, (int)(p*6));
        double u = p == 1 ? 1 : p*6-step, eased = smooth(u);
        boolean movingLeft = (step % 2 == 0) == leftLeads;
        double leftShift = ((step + (leftLeads ? 1 : 0))/2) / 3.0;
        double rightShift = ((step + (leftLeads ? 0 : 1))/2) / 3.0;
        if (movingLeft) leftShift += eased/3; else rightShift += eased/3;
        double weight = Math.pow(Math.sin(Math.PI*u),2);
        double lift = .05*weight*weight;
        Vec3 left = start.add(l.scale(.1625)).subtract(side.scale(leftShift)).add(0, seatLift + (movingLeft ? lift : 0), 0);
        Vec3 right = start.subtract(l.scale(.1625)).subtract(side.scale(rightShift)).add(0, seatLift + (movingLeft ? 0 : lift), 0);
        double centerShift = (leftShift+rightShift)/2
            + (movingLeft == leftLeads ? 1 : -1)*.10*weight;
        double lateral = Math.abs((movingLeft ? rightShift : leftShift)-centerShift);
        if (lateral > .25) return null;
        double angle = Math.PI/2*bend;
        double upperReach = Math.sqrt(Math.max(0,.25*.25-lateral*lateral));
        double forward = upperReach*Math.sin(angle), hip = .5+upperReach*Math.cos(angle);
        Vec3 origin = start.subtract(side.scale(centerShift))
            .subtract(f.scale(.125*bend+forward)).add(0, hip-.75 + furnitureSeatLift(site) * bend, 0);
        if (optional) {
            // Advance the whole frame through the actual chair/table gap during
            // the first eight lift ticks; retain leg geometry and the mount anchor.
            Vec3 laneAdvance = f.scale(ANOTHER_FURNITURE_FRONT_LANE_ADVANCE * smooth(ticks / 8));
            left = left.add(laneAdvance);
            right = right.add(laneAdvance);
            origin = origin.add(laneAdvance);
            // Sit down: hip onto the seat surface and forward; feet forward off the
            // seat edge first, then down to the floor under the tabletop.
            double d = smooth((ticks - AF_LATERAL_END) / (TICKS - AF_LATERAL_END));
            if (d > 0) {
                TavernSeating.Furniture kind = site.furniture();
                Vec3 lateralEnd = Vec3.atBottomCenterOf(site.chair()).add(f.scale(ANOTHER_FURNITURE_SETTLED_FRONT))
                    .add(0, -.25 + kind.seatTop(), 0);
                origin = origin.add(seatedAnchor(site).subtract(lateralEnd).scale(d));
                double feetForward = (afFootFront(kind) - (.25 + ANOTHER_FURNITURE_FRONT_LANE_ADVANCE)) * smooth(d / .6);
                double feetDown = (kind.seatTop() - afFootLift(kind)) * smooth((d - .5) / .5);
                Vec3 settle = f.scale(feetForward).add(0, -feetDown, 0);
                left = left.add(settle);
                right = right.add(settle);
            }
        }
        float yaw = site.dinerFacing().toYRot();
        Leg r = solve(origin, yaw, (float)bend, right, -1);
        Leg a = solve(origin, yaw, (float)bend, left, 1);
        return r == null || a == null ? null : new Frame(origin, yaw, (float)bend, right, left, r, a);
    }
    private static Vec3 toModel(Vec3 origin, float yaw, Vec3 point) {
        double a = Math.toRadians(yaw); Vec3 d = point.subtract(origin);
        return new Vec3((Math.cos(a)*d.x+Math.sin(a)*d.z)*16,
            (1.501-d.y)*16-24, (Math.sin(a)*d.x-Math.cos(a)*d.z)*16);
    }
    private static Vec3 toWorld(Frame frame, Vec3 point) {
        double a = Math.toRadians(frame.yaw());
        return frame.origin().add((Math.cos(a)*point.x+Math.sin(a)*point.z)/16,
            1.501-(point.y+24)/16, (Math.sin(a)*point.x-Math.cos(a)*point.z)/16);
    }
    private static Leg solve(Vec3 origin, float yaw, float bend, Vec3 foot, int side) {
        if (bend < 1) {
            float angle=(float)(Math.PI/2*bend);
            return new Leg(-angle,0,0,angle,0,0,0,0,0);
        }
        double a = Math.toRadians(yaw);
        Vec3 f = new Vec3(-Math.sin(a),0,Math.cos(a));
        Vec3 ankle = toModel(origin,yaw,foot.subtract(f.scale(.125)).add(0,.126,0));
        Vec3 hip = new Vec3(side*2.6,-12,0);
        Vec3 delta = ankle.subtract(hip);
        double horizontal = Math.hypot(delta.x,delta.z);
        double lowerRadiusSquared = 36-delta.y*delta.y;
        if (!Double.isFinite(horizontal) || horizontal<1e-7 || lowerRadiusSquared < -1e-5) return null;
        // The upper link turns horizontally over the seat. A real lifted ankle
        // determines the lower link's horizontal circle; retain one anatomical branch.
        double along=(16-Math.max(0,lowerRadiusSquared)+horizontal*horizontal)/(2*horizontal);
        if (Math.abs(along)>4+1e-5) return null;
        double height=Math.sqrt(Math.max(0,16-along*along));
        double ax=delta.x/horizontal, az=delta.z/horizontal;
        Vec3 k1=hip.add(along*ax-height*az,0,along*az+height*ax);
        Vec3 k2=hip.add(along*ax+height*az,0,along*az-height*ax);
        // Choosing whichever knee is further forward would switch solutions
        // when delta.x crosses zero while lifted. Keep the outward branch;
        // the two solutions coincide only at actual planted contact.
        Vec3 knee=side>0?k1:k2;
        Vec3 upper=knee.subtract(hip), lower=ankle.subtract(knee);
        float x=-(float)Math.PI/2, y=(float)Math.atan2(-upper.x,-upper.z);
        float gx=(float)Math.atan2(lower.z,Math.hypot(lower.x,lower.y));
        float gz=(float)Math.atan2(-lower.x,lower.y);
        // Counter the hip yaw at the knee: the calf faces forward rather than
        // twisting its rear corner into the stair. All transforms use Rz*Ry*Rx.
        Vec3 sx=inverse(rotate(new Vec3(1,0,0),gx,0,gz),x,y,0);
        Vec3 sy=inverse(rotate(new Vec3(0,1,0),gx,0,gz),x,y,0);
        Vec3 sz=inverse(rotate(new Vec3(0,0,1),gx,0,gz),x,y,0);
        Vec3 shin=euler(sx,sy,sz);
        // Full inverse of the global calf rotation keeps soles level and toes parallel.
        Vec3 boot=euler(inverse(new Vec3(1,0,0),gx,0,gz),
            inverse(new Vec3(0,1,0),gx,0,gz),inverse(new Vec3(0,0,1),gx,0,gz));
        return new Leg(x,y,0,(float)shin.x,(float)shin.y,(float)shin.z,
            (float)boot.x,(float)boot.y,(float)boot.z);
    }
    /** Actual upper-link endpoint, using the same rotations as the rendered mesh. */
    public static Vec3 kneePosition(Frame frame, boolean left) {
        Leg leg=left?frame.left():frame.right();
        Vec3 joint=rotate(new Vec3(0,4,0),leg.x(),leg.y(),leg.z())
            .add(left?2.6:-2.6,-12,2*(1-frame.bend()));
        return toWorld(frame,joint);
    }
    private static Vec3 euler(Vec3 x,Vec3 y,Vec3 z) {
        double pitch=Math.asin(Math.max(-1,Math.min(1,-x.z)));
        if(Math.abs(Math.cos(pitch))<1e-7)
            return new Vec3(Math.atan2(-z.y,y.y),pitch,0);
        return new Vec3(Math.atan2(y.z,z.z),pitch,Math.atan2(x.y,x.x));
    }
    private static Vec3 rotate(Vec3 v,double x,double y,double z) {
        Vec3 q=new Vec3(v.x,v.y*Math.cos(x)-v.z*Math.sin(x),v.y*Math.sin(x)+v.z*Math.cos(x));
        Vec3 r=new Vec3(q.x*Math.cos(y)+q.z*Math.sin(y),q.y,-q.x*Math.sin(y)+q.z*Math.cos(y));
        return new Vec3(r.x*Math.cos(z)-r.y*Math.sin(z),r.x*Math.sin(z)+r.y*Math.cos(z),r.z);
    }
    private static Vec3 inverse(Vec3 v,double x,double y,double z) {
        Vec3 q=new Vec3(v.x*Math.cos(z)+v.y*Math.sin(z),-v.x*Math.sin(z)+v.y*Math.cos(z),v.z);
        Vec3 r=new Vec3(q.x*Math.cos(y)-q.z*Math.sin(y),q.y,q.x*Math.sin(y)+q.z*Math.cos(y));
        return new Vec3(r.x,r.y*Math.cos(x)+r.z*Math.sin(x),-r.y*Math.sin(x)+r.z*Math.cos(x));
    }
    /** Loaded, bounded actual mesh envelopes; never exempt the chair from collision. */
    public static boolean clear(SettlerEntity actor, TavernSeating.SeatSite site, Frame frame) {
        return clear(actor,site,frame,null);
    }
    /** Optional diagnostic sink; the ordinary path retains every exact predicate. */
    public static boolean clear(SettlerEntity actor, TavernSeating.SeatSite site, Frame frame,
                                java.util.function.Consumer<String> failure) {
        if (frame == null || Math.abs(actor.getScale()-1)>1e-5) {
            if(failure!=null) failure.accept("unreachable frame="+frame+" actorScale="+actor.getScale());
            return false;
        }
        for(Vec3 foot:new Vec3[]{frame.rightFoot(),frame.leftFoot()}) {
            if(foot.y>site.chair().getY()+.00001) continue;
            BlockPos floor=BlockPos.containing(foot.x,site.chair().getY()-.001,foot.z);
            if(!actor.level().hasChunkAt(floor) || !actor.level().getBlockState(floor)
                .isFaceSturdy(actor.level(),floor,net.minecraft.core.Direction.UP)) {
                if(failure!=null) failure.accept("unsupported planted foot="+foot+" floor="+floor
                    +" loaded="+actor.level().hasChunkAt(floor));
                return false;
            }
        }
        List<MeshBox> boxes = new ArrayList<>(8);
        boxes.add(box(frame,-5,-24,-2.5,5,-12,2.5,null,0,0));
        boxes.add(box(frame,-4.6,-32.6,-4.6,4.6,-23.4,4.6,null,0,0));
        for(int side : new int[]{-1,1}) {
            Leg leg=side<0?frame.right():frame.left();
            boxes.add(box(frame,-2,0,-4,2,6,0,leg,side,0));
            boxes.add(box(frame,-2,0,-4,2,6,0,leg,side,1));
            boxes.add(box(frame,-2,0,-4,2,2,0,leg,side,2));
        }
        int index=0;
        for(MeshBox mesh:boxes) {
            AABB box=mesh.bounds();
            int envelope=index++;
            if(!actor.level().getWorldBorder().isWithinBounds(box)) {
                if(failure!=null) failure.accept("world border envelope="+envelope+" box="+box);
                return false;
            }
            for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
                if(!actor.level().hasChunkAt(p) || !actor.level().getFluidState(p).isEmpty()) {
                    if(failure!=null) failure.accept("unloaded/fluid envelope="+envelope+" block="+p+" box="+box);
                    return false;
                }
            var collisions=actor.level().getBlockCollisions(actor,box.deflate(.00001)).iterator();
            AABB hit=null;
            while(collisions.hasNext() && hit==null) {
                // Stairs are a union of boxes. Their aggregate bounds include air.
                // The broad phase stays unchanged; only an actual oriented mesh
                // intersection with one solid component refuses this frame.
                for(AABB solid:collisions.next().toAabbs()) {
                    if(mesh.intersects(solid)) { hit=solid; break; }
                }
            }
            if(hit!=null) {
                if(failure!=null) {
                    StringBuilder blocks=new StringBuilder();
                    for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
                        blocks.append(p).append('=').append(actor.level().getBlockState(p)).append(';');
                    failure.accept("block collision envelope="+envelope+" box="+box
                        +" solid="+hit+" loadedBlocks="+blocks);
                }
                return false;
            }
            var others=actor.level().getEntities(actor,box,e -> e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive());
            if(!others.isEmpty()) {
                if(failure!=null) failure.accept("living collision envelope="+envelope+" box="+box
                    +" other="+others.get(0).getUUID()+" position="+others.get(0).position()+" bounds="+others.get(0).getBoundingBox());
                return false;
            }
        }
        return true;
    }
    private static MeshBox box(Frame f,double x0,double y0,double z0,double x1,double y1,double z1,Leg leg,int side,int part) {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=maxX,maxZ=maxX;
        Vec3[] corners=new Vec3[8];
        for(int i=0;i<8;i++) {
            Vec3 v=new Vec3((i&1)==0?x0:x1,(i&2)==0?y0:y1,(i&4)==0?z0:z1);
            if(leg!=null) {
                if(part==2) {
                    Vec3 ankle=rotate(rotate(new Vec3(0,6,0),leg.shinX(),leg.shinY(),leg.shinZ()).add(0,4,0),leg.x(),leg.y(),leg.z());
                    v=v.add(ankle); // Counter-rotation keeps boot axes in root space.
                } else {
                    if(part==1) v=rotate(v,leg.shinX(),leg.shinY(),leg.shinZ()).add(0,4,0);
                    v=rotate(v,leg.x(),leg.y(),leg.z());
                }
                v=v.add(side*2.6,-12,2*(1-f.bend()));
            }
            v=toWorld(f,v); corners[i]=v; minX=Math.min(minX,v.x);minY=Math.min(minY,v.y);minZ=Math.min(minZ,v.z);
            maxX=Math.max(maxX,v.x);maxY=Math.max(maxY,v.y);maxZ=Math.max(maxZ,v.z);
        }
        return new MeshBox(new AABB(minX,minY,minZ,maxX,maxY,maxZ),corners);
    }
    /** Separating-axis test for this unchanged rigid cuboid against a voxel box. */
    record MeshBox(AABB bounds, Vec3[] corners) {
        private static final Vec3[] WORLD_AXES={new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};
        boolean intersects(AABB solid) {
            Vec3 center=corners[0].add(corners[7]).scale(.5);
            Vec3 offset=solid.getCenter().subtract(center);
            Vec3[] axes={corners[1].subtract(corners[0]).normalize(),
                corners[2].subtract(corners[0]).normalize(),corners[4].subtract(corners[0]).normalize()};
            // Three solid face normals, three mesh face normals, nine edge crosses.
            // Projection is relative to mesh centre, avoiding huge world-coordinate dot products.
            for(Vec3 axis:WORLD_AXES) if(separated(axis,center,offset,solid)) return false;
            for(Vec3 axis:axes) {
                if(separated(axis,center,offset,solid)) return false;
                for(Vec3 world:WORLD_AXES) {
                    Vec3 cross=axis.cross(world);
                    double lengthSquared=cross.lengthSqr();
                    if(lengthSquared>1e-12
                        && separated(cross.scale(1/Math.sqrt(lengthSquared)),center,offset,solid)) return false;
                }
            }
            return true;
        }
        private boolean separated(Vec3 axis,Vec3 center,Vec3 offset,AABB solid) {
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(Vec3 corner:corners) {
                double projection=corner.subtract(center).dot(axis);
                min=Math.min(min,projection); max=Math.max(max,projection);
            }
            double middle=offset.dot(axis);
            double radius=(Math.abs(axis.x)*solid.getXsize()+Math.abs(axis.y)*solid.getYsize()
                +Math.abs(axis.z)*solid.getZsize())*.5;
            // Same 0.00001-block contact tolerance as the retained broad phase.
            return max<=middle-radius+.00001 || min>=middle+radius-.00001;
        }
    }
}

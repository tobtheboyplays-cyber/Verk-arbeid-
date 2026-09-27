package com.hearthstead.entity;

import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.HomeSeating;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Invisible, saved vehicle; vanilla saves its actual resident passenger recursively. */
public final class TavernSeatEntity extends Entity {
    /** Legacy saves omit Mode and decode as TAVERN. */
    public enum Mode {
        TAVERN("tavern"),
        HOME("home");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Mode fromId(String id) {
            for (Mode value : values()) {
                if (value.id.equals(id)) return value;
            }
            return null;
        }
    }

    private UUID owner;
    private Mode mode = Mode.TAVERN;
    private TavernSeating.SeatSite site;
    private long expires;
    private boolean closing;
    private static final net.minecraft.network.syncher.EntityDataAccessor<CompoundTag> MOTION =
        SynchedEntityData.defineId(TavernSeatEntity.class, net.minecraft.network.syncher.EntityDataSerializers.COMPOUND_TAG);
    private CompoundTag previousMotion;
    private long retryMotionAt;
    private int phase() { return entityData.get(MOTION).getInt("Phase"); }
    private int clock() { return entityData.get(MOTION).getInt("Tick"); }
    public boolean isSettled() { return phase() == 0 && !entityData.get(MOTION).getBoolean("Reserved") && isVehicle() && !closing; }
    public boolean isTransitioning() { return phase() != 0; }
    private void motion(int phase, int tick) { motion(phase,tick,false); }
    private void motion(int phase, int tick, boolean reserved) {
        CompoundTag value = new CompoundTag();
        value.putBoolean("Reserved",reserved);
        value.putInt("Phase", phase); value.putInt("Tick", tick);
        value.putLong("Chair", site.chair().asLong()); value.putLong("Aisle", site.aisle().asLong());
        value.putInt("Facing", site.dinerFacing().get3DDataValue());
        value.putLong("Serial", entityData.get(MOTION).getLong("Serial") + 1);
        previousMotion = entityData.get(MOTION).copy(); entityData.set(MOTION, value);
    }
    public TavernSeatMotion.Frame motionFrame(float partial) {
        CompoundTag latest = entityData.get(MOTION);
        CompoundTag value = level().isClientSide && currentClientMotion != null
            ? currentClientMotion : latest;
        // Metadata and passenger packets can run before a render with zero client ticks.
        // Do not retain the reservation once the actual entry snapshot has arrived.
        if (level().isClientSide && value.getBoolean("Reserved") && !latest.isEmpty()
            && !latest.getBoolean("Reserved")) value = latest;
        if (value.isEmpty()) return null;
        int facing = value.getInt("Facing");
        if (facing < 2 || facing > 5) return null;
        Direction front = Direction.from3DDataValue(facing);
        BlockPos chair = BlockPos.of(value.getLong("Chair")), table = chair.relative(front);
        var geometry = new TavernSeating.SeatSite(new UUID(0,0),chair,table,front,
            BlockPos.of(value.getLong("Aisle")),table.relative(front));
        float tick = sampleTick(value);
        if (level().isClientSide && previousMotion != null) {
            int from = previousMotion.getInt("Phase"), to = value.getInt("Phase");
            // Reservation -> entry begins at the standing side, never at the seat.
            // Entry -> settled retains the final frame rather than dropping it.
            if (!previousMotion.getBoolean("Reserved")
                && (from == to || from == 1 && to == 0 || from == 0 && to == 2))
                tick = net.minecraft.util.Mth.lerp(net.minecraft.util.Mth.clamp(partial,0,1),
                    sampleTick(previousMotion), tick);
        }
        return TavernSeatMotion.sample(geometry,tick);
    }
    private static float sampleTick(CompoundTag snapshot) {
        int phase = snapshot.getInt("Phase"), tick = snapshot.getInt("Tick");
        return phase == 1 ? tick : phase == 2 ? TavernSeatMotion.TICKS-tick
            : snapshot.getBoolean("Reserved") ? 0 : TavernSeatMotion.TICKS;
    }
    private CompoundTag currentClientMotion;
    public boolean atEntry(SettlerEntity actor) {
        if (site == null) return false;
        Vec3 target = TavernSeatMotion.staging(site);
        return actor.position().distanceToSqr(target) <= .0009
            && Math.abs(net.minecraft.util.Mth.wrapDegrees(actor.yBodyRot-site.dinerFacing().toYRot())) <= 1;
    }
    private boolean clearMotion(SettlerEntity actor, double from, double to) {
        for (double t=from; t<=to+.00001; t+=.5)
            if (!TavernSeatMotion.clear(actor,site,TavernSeatMotion.sample(site,t))) return false;
        return true;
    }
    public boolean beginEntry(SettlerEntity actor) {
        if (!(level() instanceof ServerLevel server) || !atEntry(actor) || closing || !owns(actor)
            || !getPassengers().isEmpty() || actor.isPassenger() || !permitted(server, actor)
            || level().getGameTime()<retryMotionAt) return false;
        retryMotionAt = level().getGameTime()+20;
        if (!clearMotion(actor,0,TavernSeatMotion.TICKS)) return false;
        motion(1,0);
        if (!actor.startRiding(this)) { motion(0,0); return false; }
        actor.getNavigation().stop(); expires=level().getGameTime()+visitTicks();
        positionRider(actor); return true;
    }
    public boolean requestNormalExit() {
        if (!(getFirstPassenger() instanceof SettlerEntity actor) || !isSettled()
            || level().getGameTime()<retryMotionAt) return false;
        retryMotionAt = level().getGameTime()+20;
        if (!clearMotion(actor,0,TavernSeatMotion.TICKS)) return false;
        motion(2,0); return true;
    }
    public TavernSeatEntity(EntityType<? extends TavernSeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }
    public TavernSeating.SeatSite site() { return site; }
    public Mode mode() { return mode; }
    public boolean isClosing() { return closing; }
    public boolean owns(SettlerEntity actor) { return actor.getUUID().equals(owner); }
    public void reserve(SettlerEntity actor, TavernSeating.SeatSite value) {
        mode = Mode.TAVERN;
        reserveInternal(actor, value, 240);
    }

    /** Same physical chair vehicle, without Tavern food, host, or table authority. */
    public void reserveHome(SettlerEntity actor, TavernSeating.SeatSite value) {
        mode = Mode.HOME;
        reserveInternal(actor, value, 240);
    }

    private void reserveInternal(SettlerEntity actor, TavernSeating.SeatSite value,
                                 int reservationTicks) {
        owner = actor.getUUID(); site = value;
        expires = level().getGameTime() + reservationTicks;
        Direction front = site.dinerFacing();
        setPos(site.chair().getX() + .5 + front.getStepX() * .25,
            site.chair().getY() - .25, site.chair().getZ() + .5 + front.getStepZ() * .25);
        setYRot(front.toYRot()); motion(0,0,true);
    }

    private boolean permitted(ServerLevel server, SettlerEntity actor) {
        return mode == Mode.TAVERN
            ? TavernSeating.mayVisit(actor) && TavernSeating.valid(server, actor, site)
            : HomeSeating.mayRest(actor) && HomeSeating.valid(server, actor, site);
    }

    private int visitTicks() {
        return mode == Mode.TAVERN ? TavernSeating.VISIT_TICKS : HomeSeating.REST_TICKS;
    }

    public boolean mount(SettlerEntity actor) {
        if (!(level() instanceof ServerLevel server) || closing || !owns(actor) || site == null
            || !getPassengers().isEmpty() || actor.isPassenger() || !permitted(server, actor)
            || !TavernSeating.clearStand(level(), actor, site.aisle())
            || actor.position().distanceToSqr(Vec3.atBottomCenterOf(site.aisle())) > .64) return false;
        if (!actor.startRiding(this)) return false;
        actor.getNavigation().stop();
        expires = level().getGameTime() + visitTicks();
        motion(0,0);
        positionRider(actor);
        return true;
    }
    public Vec3 safeExit(LivingEntity actor) {
        if (site == null) {
            BlockPos feet = BlockPos.containing(getX(), getY() + .25, getZ());
            for (BlockPos p : new BlockPos[]{feet, feet.north(), feet.south(), feet.east(), feet.west(), feet.above()})
                if (TavernSeating.clearStand(level(), actor, p)) return Vec3.atBottomCenterOf(p);
            return null;
        }
        Direction front = site.dinerFacing();
        BlockPos[] exits = {site.aisle(), site.chair().relative(front.getClockWise()),
            site.chair().relative(front.getCounterClockWise()), site.chair().relative(front.getOpposite()),
            site.chair(), site.chair().above()};
        for (BlockPos p : exits) if (TavernSeating.clearStand(level(), actor, p)) return Vec3.atBottomCenterOf(p);
        return null;
    }
    public boolean release() {
        closing = true;
        for (Entity passenger : getPassengers()) {
            if (passenger instanceof LivingEntity living && safeExit(living) == null) return false;
        }
        ejectPassengers();
        discard();
        return true;
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            // Sample every client tick, including unchanged settled snapshots.
            // Otherwise the final interpolated frame would replay indefinitely.
            CompoundTag incoming = entityData.get(MOTION);
            previousMotion = currentClientMotion == null ? incoming : currentClientMotion;
            currentClientMotion = incoming;
            return;
        }
        if (!(level() instanceof ServerLevel server)) return;
        if (site == null || owner == null) { release(); return; }
        // A saved mount keeps identity and deadline. It never restarts a fresh visit on reload.
        SettlerEntity actor = server.getEntity(owner) instanceof SettlerEntity s ? s : null;
        Vec3 anchor = new Vec3(site.chair().getX() + .5 + site.dinerFacing().getStepX() * .25,
            site.chair().getY() - .25, site.chair().getZ() + .5 + site.dinerFacing().getStepZ() * .25);
        if (closing || position().distanceToSqr(anchor) > .0001
            || expires > level().getGameTime() + visitTicks()
            || actor == null || !actor.isAlive() || !permitted(server, actor)) { release(); return; }
        if (isVehicle() && (!hasPassenger(actor) || getPassengers().size() != 1)) { release(); return; }
        if (isTransitioning() && hasPassenger(actor)) {
            int next=Math.min(TavernSeatMotion.TICKS,clock()+1);
            double from=phase()==1?clock():TavernSeatMotion.TICKS-next;
            if (!clearMotion(actor,from,from+1)) {
                if (phase()==1 || level().getGameTime()>expires+120) release();
                return;
            }
            int prior=phase(); motion(prior,next); positionRider(actor);
            if(next==TavernSeatMotion.TICKS) {
                if(prior==1) motion(0,0); else release();
            }
        } else if(isVehicle() && level().getGameTime()>=expires-TavernSeatMotion.TICKS) {
            if(!requestNormalExit() && level().getGameTime()>expires+120) release();
        } else if(!isVehicle() && level().getGameTime()>=expires) {
            release();
        }
    }
    @Override protected boolean canAddPassenger(Entity passenger) {
        return !closing && passenger instanceof SettlerEntity actor && owns(actor) && getPassengers().isEmpty();
    }
    @Override protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        // Vanilla saves passenger X/Z at the vehicle anchor and does not position
        // it during recursive loading. Restore the exact saved moving frame now,
        // before the first tick; do not advance its phase, deadline or ownership.
        if (!level().isClientSide && !closing && site!=null && isTransitioning()
            && getPassengers().size()==1 && passenger instanceof SettlerEntity actor && owns(actor))
            positionRider(actor);
    }
    @Override protected void positionRider(Entity rider, Entity.MoveFunction move) {
        TavernSeatMotion.Frame frame = isTransitioning() ? motionFrame(1) : null;
        Vec3 point=frame==null?position():frame.origin();
        move.accept(rider, point.x, point.y, point.z);
        rider.setYRot(getYRot());
        if (rider instanceof LivingEntity living) living.setYBodyRot(getYRot());
    }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if(phase()==2 && clock()==TavernSeatMotion.TICKS && site!=null) {
            Vec3 target=TavernSeatMotion.staging(site);
            if(level().noCollision(passenger,passenger.getDimensions(passenger.getPose()).makeBoundingBox(target))) return target;
        }
        Vec3 exit = safeExit(passenger);
        return exit != null ? exit : passenger.position();
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(MOTION,new CompoundTag()); }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (site == null || owner == null) return;
        tag.putUUID("Owner", owner); tag.putUUID("Tavern", site.tavernId());
        tag.putString("Mode", mode.id());
        tag.putLong("Chair", site.chair().asLong()); tag.putLong("Aisle", site.aisle().asLong());
        tag.putInt("Facing", site.dinerFacing().get3DDataValue()); tag.putLong("Expires", expires);
        tag.putBoolean("Closing", closing); tag.put("Motion",entityData.get(MOTION).copy());
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        if (!tag.hasUUID("Owner") || !tag.hasUUID("Tavern") || !tag.contains("Chair", 4)
            || !tag.contains("Aisle", 4) || !tag.contains("Facing", 3) || !tag.contains("Expires", 4)) return;
        int code = tag.getInt("Facing");
        if (code < 2 || code > 5) return;
        Direction front = Direction.from3DDataValue(code);
        BlockPos chair = BlockPos.of(tag.getLong("Chair"));
        BlockPos table = chair.relative(front);
        owner = tag.getUUID("Owner");
        // Missing Mode is a real legacy Tavern vehicle, never a guessed HOME row.
        mode = tag.contains("Mode", Tag.TAG_STRING) ? Mode.fromId(tag.getString("Mode")) : Mode.TAVERN;
        if (mode == null) {
            mode = Mode.TAVERN;
            closing = true;
        }
        site = new TavernSeating.SeatSite(tag.getUUID("Tavern"), chair, table, front,
            BlockPos.of(tag.getLong("Aisle")), table.relative(front));
        expires = tag.getLong("Expires"); closing = tag.getBoolean("Closing");
        if(tag.contains("Motion",10)) {
            CompoundTag motion=tag.getCompound("Motion");
            if(!motion.contains("Phase",3) || !motion.contains("Tick",3)
                || !motion.contains("Chair",4) || !motion.contains("Aisle",4)
                || !motion.contains("Facing",3) || !motion.contains("Serial",4)
                || motion.contains("Reserved") && !motion.contains("Reserved",1)
                || motion.getBoolean("Reserved") && (motion.getInt("Phase")!=0 || motion.getInt("Tick")!=0)
                || motion.getInt("Phase")<0 || motion.getInt("Phase")>2
                || motion.getInt("Tick")<0 || motion.getInt("Tick")>TavernSeatMotion.TICKS
                || motion.getLong("Chair")!=site.chair().asLong() || motion.getLong("Aisle")!=site.aisle().asLong()
                || motion.getInt("Facing")!=code) closing=true;
            else { entityData.set(MOTION,motion.copy()); previousMotion=motion.copy(); }
        } else motion(0,0);
    }
}

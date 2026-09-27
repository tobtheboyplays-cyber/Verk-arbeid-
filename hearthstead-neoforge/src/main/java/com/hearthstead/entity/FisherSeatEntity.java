package com.hearthstead.entity;

import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.work.FishingGrounds;
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

/** Actual saved chair passenger. Contains no fish, progress, or economic authority. */
public final class FisherSeatEntity extends Entity {
    private BlockPos chair;
    private BlockPos exit;
    private UUID owner;
    public FisherSeatEntity(EntityType<? extends FisherSeatEntity> type, Level level) {
        super(type,level); noPhysics=true; setNoGravity(true);
    }
    public void prepare(SettlerEntity worker, BlockPos chair, BlockPos exit, Direction facing) {
        this.chair=chair.immutable(); this.exit=exit.immutable(); owner=worker.getUUID();
        setPos(chair.getX()+.5,chair.getY(),chair.getZ()+.5); setYRot(facing.toYRot());
    }
    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server)) return;
        if (chair==null || !level().getBlockState(chair).is(ModBlocks.FISHERS_CHAIR.get())
                || !(getFirstPassenger() instanceof SettlerEntity worker)
                || !worker.getUUID().equals(owner) || worker.getProfession()!=Profession.FISHER
                || worker.getActivity()!=SettlerActivity.WORK_FISH || worker.getFisherCycleTick()<0) {
            ejectPassengers(); discard();
        }
    }
    @Override protected boolean canAddPassenger(Entity passenger) {
        return passenger instanceof SettlerEntity && passenger.getUUID().equals(owner) && getPassengers().isEmpty();
    }
    @Override protected void positionRider(Entity rider, Entity.MoveFunction move) {
        move.accept(rider,getX(),getY(),getZ()); rider.setYRot(getYRot());
        if(rider instanceof LivingEntity living) living.setYBodyRot(getYRot());
    }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if(level() instanceof ServerLevel server) {
            if(exit!=null && FishingGrounds.standable(server,exit)) return Vec3.atBottomCenterOf(exit);
            if(chair!=null) for(Direction dir:Direction.Plane.HORIZONTAL) {
                BlockPos p=chair.relative(dir);
                if(FishingGrounds.standable(server,p)) return Vec3.atBottomCenterOf(p);
            }
        }
        return new Vec3(getX(),getY()+.6,getZ());
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if(chair!=null) tag.putLong("Chair",chair.asLong());
        if(exit!=null) tag.putLong("Exit",exit.asLong());
        if(owner!=null) tag.putUUID("Owner",owner);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        if(tag.contains("Chair")) chair=BlockPos.of(tag.getLong("Chair"));
        if(tag.contains("Exit")) exit=BlockPos.of(tag.getLong("Exit"));
        if(tag.hasUUID("Owner")) owner=tag.getUUID("Owner");
    }
}

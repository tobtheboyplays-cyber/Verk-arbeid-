package com.hearthstead.entity;

import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Finite visual snapshot only. Never owns inventory, destroys blocks or drops loot. */
public final class FallingTreeEntity extends Entity {
    public static final int DURATION_TICKS=40, HOLD_TICKS=10, MAX_BLOCKS=512, MAX_LOGS=96;
    private static final EntityDataAccessor<CompoundTag> GEOMETRY=SynchedEntityData.defineId(FallingTreeEntity.class,EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Long> START=SynchedEntityData.defineId(FallingTreeEntity.class,EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DIRECTION=SynchedEntityData.defineId(FallingTreeEntity.class,EntityDataSerializers.INT);
    public record Piece(BlockPos offset, BlockState state) {}
    private List<Piece> cachedPieces;
    private boolean creakPlayed, impactPlayed;

    public FallingTreeEntity(EntityType<? extends FallingTreeEntity> type,Level level) {
        super(type,level); noPhysics=true; setNoGravity(true);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(GEOMETRY,new CompoundTag()); builder.define(START,0L);
        builder.define(DIRECTION,Direction.NORTH.get3DDataValue());
    }
    public static boolean spawn(ServerLevel level,BlockPos root,List<BlockPos> logs,List<BlockPos> leaves,Direction fallDirection) {
        FallingTreeEntity visual=capture(level,root,logs,leaves,fallDirection);
        return visual!=null&&level.addFreshEntity(visual);
    }
    /** Captures before the caller's block transaction; does not publish or change the world. */
    public static FallingTreeEntity capture(ServerLevel level,BlockPos root,List<BlockPos> logs,List<BlockPos> leaves,Direction fallDirection) {
        if(!level.getServer().isSameThread()||root==null||logs==null||leaves==null||fallDirection==null
            ||fallDirection.getAxis()==Direction.Axis.Y||logs.isEmpty()||logs.size()>MAX_LOGS
            ||logs.size()+leaves.size()>MAX_BLOCKS||!logs.contains(root)) return null;
        Set<BlockPos> seen=new HashSet<>(); ListTag pieces=new ListTag();
        for(int group=0;group<2;group++) for(BlockPos pos:group==0?logs:leaves) {
            if(pos==null||!seen.add(pos)||!level.hasChunkAt(pos)) return null;
            BlockPos offset=pos.subtract(root);
            if(!validOffset(offset)) return null;
            BlockState state=level.getBlockState(pos);
            if(group==0?!state.is(BlockTags.LOGS_THAT_BURN)
                :!(state.getBlock() instanceof LeavesBlock)||state.getValue(LeavesBlock.PERSISTENT)) return null;
            CompoundTag piece=new CompoundTag(); piece.putInt("X",offset.getX());piece.putInt("Y",offset.getY());piece.putInt("Z",offset.getZ());
            piece.put("State",NbtUtils.writeBlockState(state)); pieces.add(piece);
        }
        FallingTreeEntity visual=ModEntities.FALLING_TREE.get().create(level);
        if(visual==null) return null;
        CompoundTag snapshot=new CompoundTag();snapshot.put("Pieces",pieces);
        visual.entityData.set(GEOMETRY,snapshot);visual.entityData.set(START,level.getGameTime());
        visual.entityData.set(DIRECTION,fallDirection.get3DDataValue());
        visual.setPos(root.getX()+.5,root.getY(),root.getZ()+.5);
        return visual;
    }
    private static boolean validOffset(BlockPos pos) {
        return Math.abs(pos.getX())<=16&&Math.abs(pos.getZ())<=16&&pos.getY()>=0&&pos.getY()<64;
    }
    public List<Piece> pieces() {
        if(cachedPieces!=null) return cachedPieces;
        ListTag entries=entityData.get(GEOMETRY).getList("Pieces",Tag.TAG_COMPOUND);
        if(entries.size()>MAX_BLOCKS) return cachedPieces=List.of();
        List<Piece> result=new ArrayList<>();
        for(Tag entry:entries) {
            CompoundTag piece=(CompoundTag)entry;
            BlockPos offset=new BlockPos(piece.getInt("X"),piece.getInt("Y"),piece.getInt("Z"));
            if(!validOffset(offset)) return cachedPieces=List.of();
            BlockState state=NbtUtils.readBlockState(level().holderLookup(Registries.BLOCK),piece.getCompound("State"));
            if(!state.isAir()) result.add(new Piece(offset,state));
        }
        return cachedPieces=List.copyOf(result);
    }
    public Direction fallDirection() {
        Direction direction=Direction.from3DDataValue(entityData.get(DIRECTION));
        return direction.getAxis()==Direction.Axis.Y?Direction.NORTH:direction;
    }
    public float fallAngle(float partialTick) {
        float t=net.minecraft.util.Mth.clamp(((float)(level().getGameTime()-entityData.get(START))+partialTick)/DURATION_TICKS,0,1);
        return 90*t*t;
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor); if(GEOMETRY.equals(accessor)) cachedPieces=null;
    }
    @Override public void tick() {
        super.tick();
        if(!level().isClientSide) {
            long elapsed=level().getGameTime()-entityData.get(START);
            if(!creakPlayed) {
                creakPlayed=true;
                // Only a fall that is actually starting creaks; a reloaded, mid-fall visual stays quiet.
                if(elapsed<DURATION_TICKS/4) level().playSound(null,getX(),getY()+1,getZ(),
                    ModSounds.TREE_CREAK.get(),net.minecraft.sounds.SoundSource.BLOCKS,1F,.95F+random.nextFloat()*.1F);
            }
            if(!impactPlayed&&elapsed>=DURATION_TICKS) {
                impactPlayed=true;
                // An unloaded, already expired visual must not replay an old impact.
                // The crash is on the clip's first beat, so it lands on the ground-contact tick.
                if(elapsed<DURATION_TICKS+HOLD_TICKS) level().playSound(null,getX(),getY(),getZ(),
                    ModSounds.TREE_FALL.get(),net.minecraft.sounds.SoundSource.BLOCKS,1F,1F);
            }
            if(elapsed>=DURATION_TICKS+HOLD_TICKS||pieces().isEmpty()) discard();
        }
    }
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(70); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance<192*192; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isIgnoringBlockTriggers() { return true; }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Geometry",entityData.get(GEOMETRY).copy());tag.putLong("Start",entityData.get(START));
        tag.putInt("Direction",entityData.get(DIRECTION));
        tag.putBoolean("ImpactPlayed",impactPlayed);
        tag.putBoolean("CreakPlayed",creakPlayed);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        CompoundTag geometry=tag.getCompound("Geometry");
        if(geometry.getList("Pieces",Tag.TAG_COMPOUND).size()>MAX_BLOCKS) geometry=new CompoundTag();
        entityData.set(GEOMETRY,geometry.copy());entityData.set(START,tag.getLong("Start"));
        entityData.set(DIRECTION,tag.getInt("Direction"));cachedPieces=null;
        impactPlayed=tag.getBoolean("ImpactPlayed");
        creakPlayed=tag.getBoolean("CreakPlayed");
    }
}

package com.hearthstead.entity;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.work.FisherNetYield;
import com.hearthstead.settlement.work.FishingGrounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Fisher's Nets: the visible float of a Fisher's set net. The entity is the
 * net's whole saved state (owning fishery, water cell, fish held, accrual
 * clock), so it survives save and reload with its chunk.
 *
 * <p>Not a mob: a plain non-living, non-pickable entity. Arrows, guards,
 * raiders and population counts never see it, and it places or breaks no
 * block. It removes itself when its fishery is gone or its water is.
 */
public final class FisherNetBuoyEntity extends Entity {
    private static final EntityDataAccessor<Integer> DATA_STORED =
        SynchedEntityData.defineId(FisherNetBuoyEntity.class, EntityDataSerializers.INT);
    /** Search box around a fishery anchor for its buoy (grounds radius plus slack). */
    public static final int SEARCH = FishingGrounds.RADIUS + 6;

    @Nullable private UUID settlementId;
    @Nullable private UUID fisheryId;
    @Nullable private BlockPos water;
    private int stored;
    private long since;

    public FisherNetBuoyEntity(EntityType<? extends FisherNetBuoyEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** A freshly set net over {@code water}, counting from {@code now}. */
    public void set(Settlement settlement, Building fishery, BlockPos water, long now) {
        this.settlementId = settlement.id;
        this.fisheryId = fishery.id;
        this.water = water.immutable();
        this.stored = 0;
        this.since = now;
        setPos(water.getX() + .5, surfaceY(water), water.getZ() + .5);
        entityData.set(DATA_STORED, 0);
    }

    public static double surfaceY(BlockPos water) {
        return water.getY() + 0.84;
    }

    public boolean belongsTo(Building fishery) {
        return fishery != null && fishery.id.equals(fisheryId);
    }

    @Nullable
    public BlockPos water() {
        return water;
    }

    /** Fish held after bringing the clock up to {@code now}. */
    public int accrue(long now) {
        FisherNetYield.State state = FisherNetYield.accrue(stored, since, now);
        stored = state.stored();
        since = state.since();
        if (entityData.get(DATA_STORED) != stored) {
            entityData.set(DATA_STORED, stored);
        }
        return stored;
    }

    /** Hauls the net: returns the fish held and sets it again, empty, from {@code now}. */
    public int haul(long now) {
        int fish = accrue(now);
        stored = 0;
        since = now;
        entityData.set(DATA_STORED, 0);
        return fish;
    }

    /** Client view of the fish held (render tug). */
    public int storedForDisplay() {
        return entityData.get(DATA_STORED);
    }

    /** Test/QA hook: pretend the net was set {@code ticks} ago. */
    public void backdate(long ticks) {
        since -= ticks;
    }

    @Nullable
    public static FisherNetBuoyEntity find(ServerLevel level, Building fishery) {
        if (fishery == null || fishery.anchor == null) {
            return null;
        }
        List<FisherNetBuoyEntity> found = level.getEntitiesOfClass(FisherNetBuoyEntity.class,
            new AABB(fishery.anchor).inflate(SEARCH, 8, SEARCH), buoy -> buoy.isAlive() && buoy.belongsTo(fishery));
        return found.stream().min(Comparator.comparing(Entity::getUUID)).orElse(null);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        if (water != null) {
            // Held exactly on its water cell; nothing may push or carry it.
            setPos(water.getX() + .5, surfaceY(water), water.getZ() + .5);
            setDeltaMovement(0, 0, 0);
        }
        if ((tickCount + getId()) % 40 != 0) {
            return;
        }
        Building fishery = fishery(level);
        if (fishery == null || water == null || !waterHolds(level, water)) {
            discard();
            return;
        }
        FisherNetBuoyEntity keeper = find(level, fishery);
        if (keeper != null && keeper != this) {
            // Two floats for one fishery (a race or a copied chunk): one stays.
            discard();
            return;
        }
        accrue(level.getGameTime());
    }

    @Nullable
    private Building fishery(ServerLevel level) {
        if (settlementId == null || fisheryId == null) {
            return null;
        }
        Settlement settlement = SettlementManager.byId(level, settlementId);
        if (settlement == null) {
            return null;
        }
        for (Building building : settlement.buildings) {
            if (building.id.equals(fisheryId)) {
                return building.type == BuildingType.FISHERY ? building : null;
            }
        }
        return null;
    }

    /** Loaded, still open surface water: a filled-in or drained cell ends the net. */
    public static boolean waterHolds(ServerLevel level, BlockPos water) {
        if (!level.hasChunkAt(water)) {
            return true;
        }
        var fluid = level.getFluidState(water);
        return fluid.is(FluidTags.WATER) && fluid.isSource()
            && !level.getFluidState(water.above()).is(FluidTags.WATER)
            && level.getBlockState(water.above()).getCollisionShape(level, water.above()).isEmpty();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STORED, 0);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (settlementId != null) tag.putUUID("Settlement", settlementId);
        if (fisheryId != null) tag.putUUID("Fishery", fisheryId);
        if (water != null) tag.putLong("Water", water.asLong());
        tag.putInt("Stored", stored);
        tag.putLong("Since", since);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        settlementId = tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null;
        fisheryId = tag.hasUUID("Fishery") ? tag.getUUID("Fishery") : null;
        water = tag.contains("Water") ? BlockPos.of(tag.getLong("Water")) : null;
        stored = Math.max(0, Math.min(FisherNetYield.CAP, tag.getInt("Stored")));
        since = tag.getLong("Since");
        entityData.set(DATA_STORED, stored);
    }
}

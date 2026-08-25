package com.hearthstead.entity;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * A raider: somebody's follower, come for a specific thing.
 *
 * <p>Extends {@link Monster} deliberately — the settlement's existing guard
 * targeting already looks for hostiles inside the settlement radius, so
 * defenders react to raiders without a special case.
 *
 * <p><b>They are not scaled from the player's stat sheet.</b> MineColonies
 * computes raid strength from citizen, building and research totals, and its
 * own dev reply says raiders are meant to be "similar to guards"; players
 * report the result as undifferentiated HP sponges (#11655). Here a raider's
 * strength comes from their captain's menace — a record of what that
 * captain has personally done to this settlement — so the threat grows from
 * its own history rather than mirroring yours.
 */
public class RaiderEntity extends Monster {

    private static final EntityDataAccessor<Boolean> DATA_CAPTAIN =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_MENACE =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_OBJECTIVE =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BYTE);

    /** Health and damage a captain carries over an ordinary follower. */
    public static final float CAPTAIN_HEALTH_BONUS = 14.0F;
    public static final double CAPTAIN_DAMAGE_BONUS = 2.0;
    /** Ceiling on menace scaling, so a long feud cannot become unwinnable. */
    public static final float MAX_MENACE = 3.0F;

    private UUID captainId;
    private UUID settlementId;
    private BlockPos objectivePos;

    public RaiderEntity(EntityType<? extends RaiderEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            // Raiders open doors and leave them open behind them — the
            // opposite of a settler, and a visible sign the place has been
            // entered.
            nav.setCanOpenDoors(true);
        }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 22.0)
            .add(Attributes.MOVEMENT_SPEED, 0.32)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.ARMOR, 2.0)
            .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, false));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2,
            new NearestAttackableTargetGoal<>(this, SettlerEntity.class, true));
        targetSelector.addGoal(3,
            new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CAPTAIN, false);
        builder.define(DATA_MENACE, 1.0F);
        builder.define(DATA_OBJECTIVE, (byte) RaidObjective.BLOD.ordinal());
    }

    public boolean isCaptain() {
        return entityData.get(DATA_CAPTAIN);
    }

    public float menace() {
        return entityData.get(DATA_MENACE);
    }

    public RaidObjective objective() {
        byte id = entityData.get(DATA_OBJECTIVE);
        RaidObjective[] all = RaidObjective.values();
        return id >= 0 && id < all.length ? all[id] : RaidObjective.BLOD;
    }

    public UUID captainId() {
        return captainId;
    }

    public UUID settlementId() {
        return settlementId;
    }

    /** Where this raider is headed; the objective decides what that means. */
    public BlockPos objectivePos() {
        return objectivePos;
    }

    public void setObjectivePos(BlockPos pos) {
        this.objectivePos = pos;
    }

    public Settlement settlement() {
        if (settlementId == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        return SettlementManager.byId(server, settlementId);
    }

    /**
     * Arms this raider for a specific raid. Menace scales health and damage
     * together so a feared captain's band hits harder and lasts longer,
     * capped so a long-running feud stays winnable.
     */
    public void assign(UUID captain, UUID settlement, RaidObjective objective,
                       float menace, boolean isCaptain) {
        this.captainId = captain;
        this.settlementId = settlement;
        entityData.set(DATA_OBJECTIVE, (byte) objective.ordinal());
        float scaled = Mth.clamp(menace, 1.0F, MAX_MENACE);
        entityData.set(DATA_MENACE, scaled);
        entityData.set(DATA_CAPTAIN, isCaptain);

        double health = getAttributeBaseValue(Attributes.MAX_HEALTH) * scaled
            + (isCaptain ? CAPTAIN_HEALTH_BONUS : 0.0);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth((float) health);
        double damage = getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
            + (scaled - 1.0F) * 2.0 + (isCaptain ? CAPTAIN_DAMAGE_BONUS : 0.0);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);
    }

    /** Raiders never turn on each other, however the melee goes. */
    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof RaiderEntity) && super.canAttack(target);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // a raid that despawns is a raid that never happened
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (captainId != null) {
            tag.putUUID("CaptainId", captainId);
        }
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
        if (objectivePos != null) {
            tag.put("ObjectivePos", NbtUtils.writeBlockPos(objectivePos));
        }
        tag.putBoolean("Captain", isCaptain());
        tag.putFloat("Menace", menace());
        tag.putByte("Objective", (byte) objective().ordinal());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        captainId = tag.hasUUID("CaptainId") ? tag.getUUID("CaptainId") : null;
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        objectivePos = tag.contains("ObjectivePos")
            ? NbtUtils.readBlockPos(tag, "ObjectivePos").orElse(null) : null;
        entityData.set(DATA_CAPTAIN, tag.getBoolean("Captain"));
        entityData.set(DATA_MENACE, Mth.clamp(tag.getFloat("Menace"), 1.0F, MAX_MENACE));
        entityData.set(DATA_OBJECTIVE, tag.getByte("Objective"));
    }
}

package com.hearthstead.entity;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.ai.BoundedStrollGoal;
import com.hearthstead.entity.ai.EatFromHearthGoal;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.GuardPatrolGoal;
import com.hearthstead.entity.ai.GuardRespondToAlertGoal;
import com.hearthstead.entity.ai.LumbererWorkGoal;
import com.hearthstead.entity.ai.RestAtNightGoal;
import com.hearthstead.entity.ai.ReturnToSettlementGoal;
import com.hearthstead.entity.ai.SettlerDefenseTargetGoal;
import com.hearthstead.entity.ai.SettlerPanicGoal;
import com.hearthstead.entity.ai.TravelerJoinGoal;
import com.hearthstead.network.OpenSettlerScreenPayload;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

public class SettlerEntity extends PathfinderMob {
    private static final EntityDataAccessor<Byte> DATA_PROFESSION =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_ACTIVITY =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_HUNGER =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ENERGY =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MORALE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.FLOAT);

    public static final byte EV_CELEBRATE = 64;
    public static final byte EV_MELEE = 65;

    /** Bag capacity: harvested goods carried before a hearth deposit run. */
    public static final int BAG_SIZE = 8;

    @Nullable
    private UUID settlementId;
    @Nullable
    private UUID targetSettlementId;
    @Nullable
    private BlockPos hearthPos;
    private boolean traveler;
    public final SimpleContainer bag = new SimpleContainer(BAG_SIZE);
    private int voiceCooldown;

    // Client-side animation machinery.
    public final AnimationState idleState = new AnimationState();
    public final AnimationState farmState = new AnimationState();
    public final AnimationState chopState = new AnimationState();
    public final AnimationState eatState = new AnimationState();
    public final AnimationState restState = new AnimationState();
    public final AnimationState stanceState = new AnimationState();
    public final AnimationState meleeState = new AnimationState();
    public final AnimationState celebrateState = new AnimationState();

    public SettlerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 24.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.ATTACK_DAMAGE, 4.0)
            .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PROFESSION, Profession.NONE.id());
        builder.define(DATA_ACTIVITY, SettlerActivity.IDLE.id());
        builder.define(DATA_HUNGER, 80.0F);
        builder.define(DATA_ENERGY, 90.0F);
        builder.define(DATA_MORALE, 60.0F);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new SettlerPanicGoal(this));
        goalSelector.addGoal(2, new TravelerJoinGoal(this));
        goalSelector.addGoal(2, new GuardMeleeGoal(this));
        goalSelector.addGoal(3, new GuardRespondToAlertGoal(this));
        goalSelector.addGoal(4, new EatFromHearthGoal(this));
        goalSelector.addGoal(5, new RestAtNightGoal(this));
        goalSelector.addGoal(6, new FarmerWorkGoal(this));
        goalSelector.addGoal(6, new LumbererWorkGoal(this));
        goalSelector.addGoal(6, new GuardPatrolGoal(this));
        goalSelector.addGoal(7, new ReturnToSettlementGoal(this));
        goalSelector.addGoal(8, new BoundedStrollGoal(this));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new SettlerDefenseTargetGoal(this));
    }

    // -------------------------------------------------------------- sync ---

    public Profession getProfession() {
        return Profession.byId(entityData.get(DATA_PROFESSION));
    }

    public SettlerActivity getActivity() {
        return SettlerActivity.byId(entityData.get(DATA_ACTIVITY));
    }

    public void setActivity(SettlerActivity activity) {
        if (getActivity() != activity) {
            entityData.set(DATA_ACTIVITY, activity.id());
        }
    }

    public float getHunger() {
        return entityData.get(DATA_HUNGER);
    }

    public void setHunger(float value) {
        entityData.set(DATA_HUNGER, Mth.clamp(value, 0.0F, 100.0F));
    }

    public float getEnergy() {
        return entityData.get(DATA_ENERGY);
    }

    public void setEnergy(float value) {
        entityData.set(DATA_ENERGY, Mth.clamp(value, 0.0F, 100.0F));
    }

    public float getMorale() {
        return entityData.get(DATA_MORALE);
    }

    public void addMorale(float delta) {
        entityData.set(DATA_MORALE, Mth.clamp(getMorale() + delta, 0.0F, 100.0F));
    }

    // -------------------------------------------------------- membership ---

    public boolean isBound() {
        return settlementId != null;
    }

    public boolean isTraveler() {
        return traveler;
    }

    @Nullable
    public UUID getSettlementId() {
        return settlementId;
    }

    @Nullable
    public UUID getTargetSettlementId() {
        return targetSettlementId;
    }

    @Nullable
    public BlockPos getHearthPos() {
        return hearthPos;
    }

    public String getSettlerName() {
        Component custom = getCustomName();
        return custom != null ? custom.getString() : "Settler";
    }

    public void setSettlerName(String name) {
        setCustomName(Component.literal(name));
        setCustomNameVisible(false);
    }

    public void bindTo(UUID settlement, BlockPos hearth) {
        this.settlementId = settlement;
        this.targetSettlementId = null;
        this.hearthPos = hearth;
        this.traveler = false;
        setActivity(SettlerActivity.IDLE);
    }

    public void markTraveler(UUID settlement, BlockPos hearth) {
        this.traveler = true;
        this.targetSettlementId = settlement;
        this.hearthPos = hearth;
        setActivity(SettlerActivity.TRAVELING);
    }

    public void unbind() {
        this.settlementId = null;
        this.targetSettlementId = null;
        this.hearthPos = null;
        this.traveler = false;
        if (getProfession() != Profession.NONE) {
            entityData.set(DATA_PROFESSION, Profession.NONE.id());
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        setActivity(SettlerActivity.IDLE);
    }

    @Nullable
    public Settlement settlement() {
        return level() instanceof ServerLevel serverLevel
            ? SettlementManager.byId(serverLevel, settlementId) : null;
    }

    @Nullable
    public HearthBlockEntity hearth() {
        return hearthPos != null
            && level().getBlockEntity(hearthPos) instanceof HearthBlockEntity be ? be : null;
    }

    public void assignProfession(Profession profession) {
        entityData.set(DATA_PROFESSION, profession.id());
        setItemSlot(EquipmentSlot.MAINHAND, profession.tool());
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        addMorale(10.0F);
        celebrate();
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, blockPosition(), ModSounds.PROFESSION_ASSIGNED.get(),
                SoundSource.NEUTRAL, 1.0F, 1.0F);
            SettlementManager.noteProfessionChange(serverLevel, this);
        }
    }

    public void celebrate() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_CELEBRATE);
        }
    }

    // ------------------------------------------------------------- needs ---

    /** Rough day schedule; guards ignore the REST phase. */
    public enum DayPhase {
        WORK, EVENING, REST;

        public static DayPhase of(long dayTime) {
            long t = dayTime % 24000L;
            if (t >= 12700 && t < 23500) {
                return REST;
            }
            if (t >= 11500 && t < 12700) {
                return EVENING;
            }
            return WORK;
        }
    }

    public DayPhase dayPhase() {
        return DayPhase.of(level().getDayTime());
    }

    private void tickNeeds() {
        SettlerActivity activity = getActivity();
        boolean working = activity == SettlerActivity.WORK_FARM
            || activity == SettlerActivity.WORK_CHOP
            || activity == SettlerActivity.PATROLLING
            || activity == SettlerActivity.COMBAT;

        setHunger(getHunger() - (working ? 0.10F : 0.04F));
        if (activity == SettlerActivity.RESTING) {
            setEnergy(getEnergy() + 1.2F);
        } else {
            setEnergy(getEnergy() - (working ? 0.09F : 0.02F));
        }

        float target = 50.0F;
        float hunger = getHunger();
        float energy = getEnergy();
        if (hunger > 60) {
            target += 20;
        } else if (hunger < 20) {
            target -= 30;
        }
        if (energy > 60) {
            target += 15;
        } else if (energy < 15) {
            target -= 15;
        }
        if (getProfession().employed()) {
            target += 5;
        }
        Settlement s = settlement();
        if (s != null && s.alertActive(level().getGameTime())) {
            target -= 20;
        }
        if (hurtTime > 0 || getLastHurtByMob() != null && tickCount - getLastHurtByMobTimestamp() < 100) {
            target -= 15;
        }
        float morale = getMorale();
        addMorale(Mth.clamp(target - morale, -1.0F, 1.0F) * 0.5F);
    }

    // -------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            setupAnimationStates();
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide) {
            if (tickCount % 20 == 0) {
                tickNeeds();
            }
            if (voiceCooldown > 0) {
                voiceCooldown--;
            }
        }
    }

    private void setupAnimationStates() {
        SettlerActivity activity = getActivity();
        boolean moving = walkAnimation.speed() > 0.05F;

        idleState.animateWhen(!moving
            && (activity == SettlerActivity.IDLE || activity == SettlerActivity.EATING
                || activity == SettlerActivity.CELEBRATING), tickCount);
        farmState.animateWhen(activity == SettlerActivity.WORK_FARM && !moving, tickCount);
        chopState.animateWhen(activity == SettlerActivity.WORK_CHOP && !moving, tickCount);
        eatState.animateWhen(activity == SettlerActivity.EATING, tickCount);
        restState.animateWhen(activity == SettlerActivity.RESTING, tickCount);
        stanceState.animateWhen((activity == SettlerActivity.PATROLLING
            || activity == SettlerActivity.COMBAT) && !moving, tickCount);

        // One-shots expire on their own clock.
        if (meleeState.isStarted() && meleeState.getAccumulatedTime() > 500L) {
            meleeState.stop();
        }
        if (celebrateState.isStarted() && celebrateState.getAccumulatedTime() > 2100L) {
            celebrateState.stop();
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EV_CELEBRATE) {
            celebrateState.start(tickCount);
        } else if (id == EV_MELEE) {
            meleeState.start(tickCount);
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        level().broadcastEntityEvent(this, EV_MELEE);
        return super.doHurtTarget(target);
    }

    // ------------------------------------------------------- interaction ---

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof com.hearthstead.item.ProfessionWritItem) {
            return InteractionResult.PASS; // handled by the writ item itself
        }
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (voiceCooldown <= 0) {
                level().playSound(null, blockPosition(), ModSounds.SETTLER_HM.get(),
                    SoundSource.NEUTRAL, 0.9F, 0.95F + random.nextFloat() * 0.1F);
                voiceCooldown = 100;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                serverPlayer, new OpenSettlerScreenPayload(getId()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ------------------------------------------------------------ combat ---

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !level().isClientSide
            && source.getEntity() instanceof LivingEntity attacker
            && attacker instanceof Enemy
            && level() instanceof ServerLevel serverLevel) {
            Settlement s = SettlementManager.byId(serverLevel,
                settlementId != null ? settlementId : targetSettlementId);
            if (s != null) {
                SettlementManager.raiseAlert(serverLevel, s, attacker.blockPosition());
            }
        }
        return result;
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (level() instanceof ServerLevel serverLevel && settlementId != null) {
            SettlementManager.onSettlerDied(serverLevel, this);
        }
    }

    // ------------------------------------------------------- persistence ---

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Profession", getProfession().id());
        tag.putFloat("Hunger", getHunger());
        tag.putFloat("Energy", getEnergy());
        tag.putFloat("Morale", getMorale());
        tag.putBoolean("Traveler", traveler);
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
        if (targetSettlementId != null) {
            tag.putUUID("TargetSettlementId", targetSettlementId);
        }
        if (hearthPos != null) {
            tag.put("HearthPos", NbtUtils.writeBlockPos(hearthPos));
        }
        tag.put("Bag", bag.createTag(registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        Profession profession = Profession.byId(tag.getByte("Profession"));
        entityData.set(DATA_PROFESSION, profession.id());
        setHunger(tag.getFloat("Hunger"));
        setEnergy(tag.getFloat("Energy"));
        entityData.set(DATA_MORALE, Mth.clamp(tag.getFloat("Morale"), 0.0F, 100.0F));
        traveler = tag.getBoolean("Traveler");
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        targetSettlementId = tag.hasUUID("TargetSettlementId")
            ? tag.getUUID("TargetSettlementId") : null;
        hearthPos = NbtUtils.readBlockPos(tag, "HearthPos").orElse(null);
        bag.fromTag(tag.getList("Bag", 10), registryAccess());
    }

    /** Idempotent re-registration protects against lost records; called from
     *  EntityJoinLevelEvent so it works on every loader. */
    public void reRegisterWithSettlement() {
        if (level() instanceof ServerLevel serverLevel && settlementId != null) {
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s != null) {
                s.putRecord(getUUID(), getSettlerName(), getProfession());
                SettlementManager.data(serverLevel).setDirty();
            }
        }
    }

    @Nullable
    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
        net.minecraft.world.level.ServerLevelAccessor level, DifficultyInstance difficulty,
        net.minecraft.world.entity.MobSpawnType spawnType,
        @Nullable net.minecraft.world.entity.SpawnGroupData groupData) {
        if (getCustomName() == null) {
            setSettlerName(com.hearthstead.settlement.SettlerNames.pickSettlerName(
                level.getRandom(), java.util.Collections.emptySet()));
        }
        return super.finalizeSpawn(level, difficulty, spawnType, groupData);
    }

    // ------------------------------------------------------------- audio ---

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 600;
    }
}

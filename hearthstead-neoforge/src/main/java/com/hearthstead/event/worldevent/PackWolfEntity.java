package com.hearthstead.event.worldevent;

import java.util.EnumSet;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A night wolf of the wolf-pack event. A {@link Monster} on purpose: the
 * Guards' ordinary combat (which only engages hostile monsters inside the
 * settlement ring) fights it without any special case. It stalks the
 * village livestock, kills at most what the event allows, then the pack
 * slinks off. Never breaks blocks, never despawns on its own; the event
 * director owns its lifetime.
 */
public class PackWolfEntity extends Monster {
    private static final String LEAVING = "HearthsteadPackLeaving";
    private static final String HOME = "HearthsteadPackAnchor";

    public PackWolfEntity(EntityType<? extends PackWolfEntity> type, Level level) {
        super(type, level);
        this.xpReward = 3;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 16.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.32D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new LeaveGoal());
        goalSelector.addGoal(2, new LeapAtTargetGoal(this, 0.4F));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.25D, true));
        goalSelector.addGoal(4, new StalkGoal());
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(PackWolfEntity.class));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Animal.class, 10, true, false,
            target -> !leaving() && WorldEventCreatures.isLivestock(target)
                && WorldEventDirector.packMayHunt(this)));
    }

    public boolean leaving() {
        return getPersistentData().getBoolean(LEAVING);
    }

    public void setLeaving() {
        if (leaving()) return;
        getPersistentData().putBoolean(LEAVING, true);
        if (getTarget() instanceof Animal) setTarget(null);
    }

    public void setAnchor(BlockPos pos) {
        getPersistentData().putLong(HOME, pos.asLong());
    }

    @Nullable
    public BlockPos anchor() {
        return getPersistentData().contains(HOME) ? BlockPos.of(getPersistentData().getLong(HOME)) : null;
    }

    /** Wolves slink away from a lost fight or a kill; the director discards them out of sight. */
    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return true;
    }

    @Override
    public boolean isPreventingPlayerRest(Player player) {
        // Co-op: a passing pack must not block everyone's bed.
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return getTarget() != null ? SoundEvents.WOLF_GROWL : SoundEvents.WOLF_PANT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WOLF_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WOLF_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.WOLF_STEP, 0.15F, 1.0F);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        spawnAtLocation(new ItemStack(Items.BONE, 1 + random.nextInt(2)));
        if (random.nextInt(3) == 0) spawnAtLocation(new ItemStack(Items.LEATHER));
    }

    /** Skin variant for the renderer, stable per wolf. */
    public int variant() {
        UUID id = getUUID();
        return Math.floorMod((int) (id.getLeastSignificantBits() ^ id.getMostSignificantBits()), 3);
    }

    /** Circle toward the pasture anchor while nothing is targeted. */
    private final class StalkGoal extends Goal {
        private int cooldown;

        StalkGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (leaving() || getTarget() != null || --cooldown > 0) return false;
            BlockPos anchor = anchor();
            return anchor != null && distanceToSqr(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5) > 36.0D;
        }

        @Override
        public void start() {
            cooldown = 40;
            BlockPos anchor = anchor();
            if (anchor != null) {
                getNavigation().moveTo(anchor.getX() + .5 + random.nextInt(7) - 3, anchor.getY(),
                    anchor.getZ() + .5 + random.nextInt(7) - 3, 0.9D);
            }
        }

        @Override
        public boolean canContinueToUse() {
            return !leaving() && getTarget() == null && !getNavigation().isDone();
        }
    }

    /** Leaving wolves run away from the anchor; the director removes them once out of sight. */
    private final class LeaveGoal extends Goal {
        LeaveGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return leaving();
        }

        @Override
        public void tick() {
            if (!getNavigation().isDone() && tickCount % 40 != 0) return;
            BlockPos anchor = anchor();
            if (anchor == null) anchor = blockPosition();
            double dx = getX() - anchor.getX(), dz = getZ() - anchor.getZ();
            double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
            getNavigation().moveTo(getX() + dx / len * 16.0D, getY(), getZ() + dz / len * 16.0D, 1.3D);
            setTarget(null);
        }
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !leaving() && super.canAttack(target);
    }
}

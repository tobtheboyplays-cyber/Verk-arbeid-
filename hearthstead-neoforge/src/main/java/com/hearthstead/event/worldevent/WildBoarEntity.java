package com.hearthstead.event.worldevent;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The wild-boar event's animal: heavy (knockback resistant), bad tempered
 * (charges whoever comes close or hurts it) and hungry (roots up a small,
 * capped number of crops). Rooting only resets a crop's growth; it never
 * breaks farmland or any player-built block. A Monster, so Guards fight it
 * with their ordinary combat; a Hunter's kill yields extra meat.
 */
public class WildBoarEntity extends Monster {
    public static final int MAX_ROOTED = 6;
    private static final String ROOTED = "HearthsteadBoarRooted";
    private static final String LEAVING = "HearthsteadBoarLeaving";
    private static final String HOME = "HearthsteadBoarFields";
    private static final ResourceLocation CHARGE_BOOST = ResourceLocation.fromNamespaceAndPath("hearthstead", "boar_charge");

    public WildBoarEntity(EntityType<? extends WildBoarEntity> type, Level level) {
        super(type, level);
        this.xpReward = 6;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 40.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 5.0D)
            .add(Attributes.ATTACK_KNOCKBACK, 1.2D)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.8D)
            .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new LeaveGoal());
        goalSelector.addGoal(2, new ChargeGoal());
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15D, true));
        goalSelector.addGoal(4, new RootFieldsGoal());
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Territorial: whoever steps right up to it gets charged.
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
            target -> !leaving() && (target instanceof Player || target instanceof SettlerEntity)
                && target.distanceToSqr(this) < 16.0D));
    }

    public int rooted() { return getPersistentData().getInt(ROOTED); }
    public boolean leaving() { return getPersistentData().getBoolean(LEAVING); }
    public void setLeaving() { getPersistentData().putBoolean(LEAVING, true); setTarget(null); }
    public void setFields(BlockPos pos) { getPersistentData().putLong(HOME, pos.asLong()); }

    @Nullable
    public BlockPos fields() {
        return getPersistentData().contains(HOME) ? BlockPos.of(getPersistentData().getLong(HOME)) : null;
    }

    @Override public boolean removeWhenFarAway(double distance) { return false; }
    /** Co-op: a boar near the fields must not block everyone's bed. */
    @Override public boolean isPreventingPlayerRest(Player player) { return false; }
    @Override public float getVoicePitch() { return 0.62F + random.nextFloat() * 0.1F; }
    @Override protected SoundEvent getAmbientSound() { return com.hearthstead.registry.ModSounds.EVENT_BOAR_GRUNT.get(); }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.HOGLIN_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.HOGLIN_DEATH; }
    @Override protected void playStepSound(BlockPos pos, BlockState state) { playSound(SoundEvents.HOGLIN_STEP, 0.25F, 0.8F); }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        boolean hunter = source.getEntity() instanceof SettlerEntity settler
            && settler.getProfession() == Profession.HUNTER;
        int meat = 3 + random.nextInt(3) + (hunter ? 3 : 0);
        spawnAtLocation(new ItemStack(isOnFire() ? Items.COOKED_PORKCHOP : Items.PORKCHOP, meat));
        spawnAtLocation(new ItemStack(Items.LEATHER, 1 + random.nextInt(2)));
        if (hunter) getPersistentData().putBoolean("HearthsteadHunterKill", true);
    }

    /** A short, straight, heavy rush with a big shove; then a cooldown. */
    private final class ChargeGoal extends Goal {
        private int cooldown;
        private int running;
        private Vec3 direction = Vec3.ZERO;

        ChargeGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (cooldown > 0) { cooldown--; return false; }
            LivingEntity target = getTarget();
            if (target == null || !target.isAlive() || leaving()) return false;
            double d = distanceToSqr(target);
            return d > 9.0D && d < 144.0D && hasLineOfSight(target);
        }

        @Override
        public void start() {
            LivingEntity target = getTarget();
            running = 0;
            if (target == null) return;
            Vec3 delta = target.position().subtract(position());
            direction = new Vec3(delta.x, 0, delta.z).normalize();
            var speed = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null && !speed.hasModifier(CHARGE_BOOST)) {
                speed.addTransientModifier(new AttributeModifier(CHARGE_BOOST, 1.4D,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
            level().playSound(null, blockPosition(), com.hearthstead.registry.ModSounds.EVENT_BOAR_CHARGE.get(), SoundSource.HOSTILE, 1.2F, 1.0F);
        }

        @Override
        public boolean canContinueToUse() {
            return running < 30 && getTarget() != null && getTarget().isAlive();
        }

        @Override
        public void tick() {
            running++;
            LivingEntity target = getTarget();
            if (target == null) return;
            getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (running % 5 == 1) {
                getNavigation().moveTo(getX() + direction.x * 5.0D, getY(), getZ() + direction.z * 5.0D, 1.6D);
            }
            if (level() instanceof ServerLevel server && running % 4 == 0) {
                server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,
                        level().getBlockState(blockPosition().below())),
                    getX(), getY() + 0.1D, getZ(), 4, 0.3D, 0.05D, 0.3D, 0.05D);
            }
            if (distanceToSqr(target) < 3.2D) {
                doHurtTarget(target);
                target.knockback(1.4D, getX() - target.getX(), getZ() - target.getZ());
                level().playSound(null, blockPosition(), SoundEvents.HOGLIN_ATTACK, SoundSource.HOSTILE, 1.0F, 0.8F);
                running = 99;
            }
        }

        @Override
        public void stop() {
            var speed = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(CHARGE_BOOST);
            cooldown = 80;
            getNavigation().stop();
        }
    }

    /** Walks to a grown crop in the settlement's farm zones and resets it (capped). */
    private final class RootFieldsGoal extends Goal {
        private BlockPos crop;
        private int ticks;
        private int search;

        RootFieldsGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (leaving() || getTarget() != null || rooted() >= MAX_ROOTED) return false;
            if (--search > 0) return false;
            search = 40;
            if (!(level() instanceof ServerLevel server)) return false;
            crop = WorldEventCreatures.nearestCrop(server, WildBoarEntity.this, false);
            return crop != null;
        }

        @Override
        public void start() {
            ticks = 0;
            getNavigation().moveTo(crop.getX() + .5, crop.getY(), crop.getZ() + .5, 0.9D);
        }

        @Override
        public boolean canContinueToUse() {
            return crop != null && ticks < 400 && getTarget() == null && !leaving();
        }

        @Override
        public void tick() {
            ticks++;
            if (distanceToSqr(crop.getX() + .5, crop.getY(), crop.getZ() + .5) > 2.6D) {
                if (getNavigation().isDone()) getNavigation().moveTo(crop.getX() + .5, crop.getY(), crop.getZ() + .5, 0.9D);
                return;
            }
            getNavigation().stop();
            getLookControl().setLookAt(crop.getX() + .5, crop.getY(), crop.getZ() + .5);
            if (ticks % 30 != 0 || !(level() instanceof ServerLevel server)) return;
            BlockState state = server.getBlockState(crop);
            if (state.getBlock() instanceof CropBlock block && block.getAge(state) > 0) {
                server.setBlock(crop, block.getStateForAge(0), 3);
                server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    crop.getX() + .5, crop.getY() + .3, crop.getZ() + .5, 12, 0.3, 0.1, 0.3, 0.05);
                server.playSound(null, crop, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
                getPersistentData().putInt(ROOTED, rooted() + 1);
                WorldEventDirector.noteCropDamage(WildBoarEntity.this, 1);
            }
            crop = null;
        }
    }

    private final class LeaveGoal extends Goal {
        LeaveGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return leaving() || rooted() >= MAX_ROOTED && getTarget() == null;
        }

        @Override
        public void tick() {
            if (!getNavigation().isDone() && tickCount % 40 != 0) return;
            BlockPos home = fields();
            if (home == null) home = blockPosition();
            double dx = getX() - home.getX(), dz = getZ() - home.getZ();
            double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
            getNavigation().moveTo(getX() + dx / len * 16.0D, getY(), getZ() + dz / len * 16.0D, 1.0D);
        }
    }
}

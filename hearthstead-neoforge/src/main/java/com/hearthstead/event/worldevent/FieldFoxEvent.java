package com.hearthstead.event.worldevent;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A fox on the fields: sneaks in, takes a few ripe crops (the crop is reset,
 * the farmland stays), and flees from anyone who comes close. The Farmer
 * runs out to chase it; a Hunter with a bow can shoot it (the stolen crop
 * drops). Three scares or the crop cap and it slinks off. Vanilla fox body,
 * this event's own small AI.
 */
final class FieldFoxEvent implements WorldEventHandler, WorldEventDirector.RoleListener {
    static final String ROLE_FOX = "field_fox";
    static final int MAX_STOLEN = 4;
    static final int SCARES_TO_LEAVE = 3;

    @Override
    public WorldEventType type() {
        return WorldEventType.FIELD_FOX;
    }

    @Override
    public boolean available(ServerLevel level, Settlement settlement) {
        return WorldEventCreatures.hasFields(level, settlement);
    }

    @Override
    public boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        List<BlockPos> crops = WorldEventCreatures.crops(level, settlement, false, 32);
        BlockPos fields = WorldEventCreatures.centroid(crops);
        if (fields == null) return false;
        BlockPos spot = WorldEventCreatures.ringSpot(level, settlement, fields, 18, 26, 0.7F, 0.8F, level.random);
        if (spot == null) spot = WorldEventCreatures.ringSpot(level, null, fields, 10, 16, 0.7F, 0.8F, level.random);
        if (spot == null) return false;
        Fox fox = EntityType.FOX.create(level);
        if (fox == null) return false;
        fox.moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, level.random.nextFloat() * 360F, 0F);
        fox.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
        // Vanilla may spawn a fox with an emerald or egg in its mouth; the mouth is for the crop.
        fox.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
        fox.setPersistenceRequired();
        WorldEventDirector.tag(fox, settlement, active, ROLE_FOX);
        fox.getPersistentData().getCompound(WorldEventDirector.TAG).putLong("Fields", fields.asLong());
        install(fox);
        if (!level.addFreshEntity(fox)) return false;
        active.state.putLong("Fields", fields.asLong());
        active.state.putBoolean("Seen", true);
        WorldEventDirector.announce(level, settlement, active,
            Component.translatableWithFallback("hearthstead.event.fox.arrive", "A fox is sneaking into the fields!"),
            Component.translatableWithFallback("hearthstead.event.fox.cta",
                "Chase it off before it steals the harvest. Your Farmer runs out, and a Hunter can shoot it."), fields);
        return true;
    }

    /** Vanilla fox body with only this event's behaviour (no sleeping, no hunting chickens). */
    static void install(Fox fox) {
        // Never picks up players' dropped items and walks off with them (bug hunt, 26 Sep).
        fox.setCanPickUpLoot(false);
        fox.goalSelector.removeAllGoals(goal -> true);
        fox.targetSelector.removeAllGoals(goal -> true);
        fox.setTarget(null);
        fox.goalSelector.addGoal(0, new FloatGoal(fox));
        fox.goalSelector.addGoal(1, new RaidFieldsGoal(fox));
        fox.goalSelector.addGoal(8, new LookAtPlayerGoal(fox, Player.class, 10.0F));
    }

    @Override
    public void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
        if (actor instanceof Fox fox) install(fox);
    }

    @Override
    public void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        Entity fox = WorldEventActors.actor(level, active, ROLE_FOX);
        if (fox == null) {
            if (WorldEventActors.presence(level, active, ROLE_FOX) == WorldEventActors.Presence.ABSENT) return;
            if (active.state.getBoolean("Seen")) finishFox(level, settlement, active);
            return;
        }
        active.state.putBoolean("Seen", true);
        CompoundState state = new CompoundState(fox);
        if (state.leaving()) {
            if (!active.state.contains("LeaveAt")) active.state.putInt("LeaveAt", active.eligibleTicks);
            if (fox.blockPosition().distSqr(settlement.center) > (double) (settlement.radius + 20) * (settlement.radius + 20)
                || active.eligibleTicks - active.state.getInt("LeaveAt") > 600) {
                active.state.putString("Outcome", state.stolen() >= MAX_STOLEN ? "stole_and_left" : "chased_off");
                finishFox(level, settlement, active);
            }
            return;
        }
        // The Farmer runs out to chase it.
        List<SettlerEntity> farmers = WorldEventActors.members(level, settlement,
            s -> s.getProfession() == Profession.FARMER && !s.isSleeping());
        SettlerEntity farmer = WorldEventActors.nearest(farmers, fox, 48.0D);
        if (farmer != null) {
            WorldEventDirector.assign(farmer, new WorldEventDirector.Role(WorldEventDirector.RoleKind.CHASE,
                active.id, fox.getUUID(), null, level.getGameTime() + 40, null, 1.15D));
        }
        if (active.eligibleTicks % 40 == 0) WorldEventActors.hunterShot(level, settlement, fox, 6, active);
    }

    @Override
    public void roleReached(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                            SettlerEntity settler, WorldEventDirector.Role role) {
        if (level.getEntity(role.target()) instanceof Fox fox) scare(fox, settler);
    }

    static void scare(Fox fox, Entity by) {
        CompoundState state = new CompoundState(fox);
        long now = fox.level().getGameTime();
        if (now < state.tag().getLong("ScaredUntil")) return;
        state.tag().putLong("ScaredUntil", now + 60);
        state.tag().putInt("Scares", state.scares() + 1);
        state.tag().putLong("ScaredFrom", by.blockPosition().asLong());
        fox.playSound(com.hearthstead.registry.ModSounds.EVENT_FOX_YIP.get(), 1.0F, 1.0F);
        if (state.scares() >= SCARES_TO_LEAVE) state.tag().putBoolean("Leaving", true);
    }

    private static void finishFox(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        int lost = active.state.getInt("CropDamage");
        String outcome = active.state.contains("Outcome") ? active.state.getString("Outcome") : "gone";
        Component notice = switch (outcome) {
            case "killed" -> Component.translatableWithFallback("hearthstead.event.fox.killed",
                "The fox won't be back. Crops lost: %s.", lost);
            case "stole_and_left" -> Component.translatableWithFallback("hearthstead.event.fox.stole",
                "The fox slinks off with a full belly. Crops lost: %s.", lost);
            default -> Component.translatableWithFallback("hearthstead.event.fox.chased",
                "The fox is chased off the fields. Crops lost: %s.", lost);
        };
        WorldEventDirector.finish(level, settlement, outcome, notice);
    }

    @Override
    public void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                          Entity actor, DamageSource source) {
        active.state.putString("Outcome", "killed");
    }

    @Override
    public void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        active.state.putString("Outcome", "chased_off");
        finishFox(level, settlement, active);
    }

    /**
     * Switched off mid-visit (Codex T14): the crop in the fox's mouth is put
     * back on the ground instead of vanishing with the discarded fox.
     */
    @Override
    public void cleanup(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
        if (!WorldEventDirector.DISABLED_OUTCOME.equals(outcome)) return;
        if (WorldEventActors.actor(level, active, ROLE_FOX) instanceof LivingEntity fox && !fox.isRemoved()) {
            ItemStack held = fox.getItemBySlot(EquipmentSlot.MAINHAND);
            if (!held.isEmpty()) {
                fox.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                com.hearthstead.util.ItemSpill.conserve(level, fox.blockPosition(), held);
            }
        }
    }

    /** View over the fox's own event state in its tag. */
    record CompoundState(Entity fox) {
        net.minecraft.nbt.CompoundTag tag() {
            return fox.getPersistentData().getCompound(WorldEventDirector.TAG);
        }
        int stolen() { return tag().getInt("Stolen"); }
        int scares() { return tag().getInt("Scares"); }
        boolean leaving() { return tag().getBoolean("Leaving"); }
        BlockPos fields() { return BlockPos.of(tag().getLong("Fields")); }
    }

    /** Steal ripe crops, bolt from anyone close, then slink away. */
    static final class RaidFieldsGoal extends Goal {
        private final Fox fox;
        private BlockPos crop;
        private int search;
        private int nibble;

        RaidFieldsGoal(Fox fox) {
            this.fox = fox;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override public boolean canUse() { return fox.isAlive(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void tick() {
            if (!(fox.level() instanceof ServerLevel level)) return;
            CompoundState state = new CompoundState(fox);
            if (state.leaving()) {
                runFrom(state.fields(), 1.5D, 20.0D);
                return;
            }
            LivingEntity threat = nearestThreat(level);
            if (threat != null) {
                scare(fox, threat);
                runFrom(threat.blockPosition(), 1.7D, 9.0D);
                crop = null;
                return;
            }
            if (crop == null) {
                if (--search > 0) return;
                search = 40;
                crop = WorldEventCreatures.nearestCrop(level, fox, true);
                if (crop == null) {
                    if (state.stolen() > 0) state.tag().putBoolean("Leaving", true);
                    return;
                }
            }
            if (fox.distanceToSqr(crop.getX() + .5, crop.getY(), crop.getZ() + .5) > 2.0D) {
                if (fox.getNavigation().isDone() || fox.tickCount % 20 == 0) {
                    fox.getNavigation().moveTo(crop.getX() + .5, crop.getY(), crop.getZ() + .5, 1.0D);
                }
                nibble = 0;
                return;
            }
            fox.getNavigation().stop();
            fox.getLookControl().setLookAt(crop.getX() + .5, crop.getY(), crop.getZ() + .5);
            if (++nibble < 40) return;
            BlockState stateAt = level.getBlockState(crop);
            if (stateAt.getBlock() instanceof CropBlock block && block.isMaxAge(stateAt)
                && state.stolen() < MAX_STOLEN) {
                ItemStack mouthful = stateAt.getBlock().getCloneItemStack(level, crop, stateAt);
                level.setBlock(crop, block.getStateForAge(0), 3);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, stateAt),
                    crop.getX() + .5, crop.getY() + .3, crop.getZ() + .5, 10, 0.3, 0.1, 0.3, 0.05);
                level.playSound(null, crop, SoundEvents.FOX_EAT, SoundSource.NEUTRAL, 1.0F, 1.0F);
                if (fox.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty() && !mouthful.isEmpty()) {
                    fox.setItemSlot(EquipmentSlot.MAINHAND, mouthful.copyWithCount(1));
                }
                state.tag().putInt("Stolen", state.stolen() + 1);
                WorldEventDirector.noteCropDamage(fox, 1);
                if (state.stolen() >= MAX_STOLEN) state.tag().putBoolean("Leaving", true);
            }
            crop = null;
            nibble = 0;
        }

        private LivingEntity nearestThreat(ServerLevel level) {
            LivingEntity best = null;
            double bestDistance = 5.0D * 5.0D;
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, fox.getBoundingBox().inflate(5.0D),
                    e -> e.isAlive() && (e instanceof SettlerEntity || e instanceof Player player && !player.isSpectator()))) {
                double d = candidate.distanceToSqr(fox);
                if (d < bestDistance) { best = candidate; bestDistance = d; }
            }
            return best;
        }

        private void runFrom(BlockPos from, double speed, double distance) {
            if (!fox.getNavigation().isDone() && fox.tickCount % 20 != 0) return;
            double dx = fox.getX() - from.getX(), dz = fox.getZ() - from.getZ();
            double len = Math.max(0.5D, Math.sqrt(dx * dx + dz * dz));
            fox.getNavigation().moveTo(fox.getX() + dx / len * distance, fox.getY(), fox.getZ() + dz / len * distance, speed);
        }
    }
}

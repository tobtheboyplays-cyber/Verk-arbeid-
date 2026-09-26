package com.hearthstead.event.worldevent;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Carries out whatever a running world event asks of one settler (see
 * {@link WorldEventDirector.Role}): visitors hold their spot, a farmer
 * chases the fox, guards run to the wolves or the brawl, two brawlers go at
 * it. Installed on every settler at join (priority 2), but idle unless a
 * role is assigned. Yields to panic; a guard's role ends the moment it has
 * a combat target, so its normal combat always takes over.
 */
public class WorldEventSettlerGoal extends Goal {
    private final SettlerEntity settler;
    private WorldEventDirector.Role role;
    private int ticks;
    private int repath;

    public WorldEventSettlerGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel) || !settler.isAlive()) return false;
        role = WorldEventDirector.role(settler);
        if (role == null) return false;
        return role.kind() != WorldEventDirector.RoleKind.CHASE || settler.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        WorldEventDirector.Role current = WorldEventDirector.role(settler);
        if (current == null || !settler.isAlive()) return false;
        role = current;
        return role.kind() != WorldEventDirector.RoleKind.CHASE || settler.getTarget() == null;
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
        if (settler.isSleeping()) settler.stopSleeping();
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        if (settler.getActivity() == SettlerActivity.PLAYING_MUSIC
            || settler.getActivity() == SettlerActivity.CELEBRATING) {
            settler.setActivity(SettlerActivity.IDLE);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticks++;
        if (!(settler.level() instanceof ServerLevel level) || role == null) return;
        if (role.activity() != null && settler.getActivity() != role.activity()) settler.setActivity(role.activity());
        switch (role.kind()) {
            case HOLD -> hold(level);
            case CHASE -> chase(level);
            case BRAWL -> brawl(level);
            case WALK_OFF -> walkOff();
        }
    }

    private void hold(ServerLevel level) {
        BlockPos pos = role.pos();
        if (pos == null) return;
        double d = settler.distanceToSqr(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        if (d > 4.0D) {
            if (--repath <= 0) {
                repath = 20;
                settler.getNavigation().moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, role.speed());
            }
        } else {
            settler.getNavigation().stop();
            Player nearest = level.getNearestPlayer(settler, 8.0D);
            if (nearest != null) settler.getLookControl().setLookAt(nearest, 20.0F, 20.0F);
        }
    }

    private void chase(ServerLevel level) {
        Entity target = role.target() == null ? null : level.getEntity(role.target());
        if (target == null || !target.isAlive()) {
            WorldEventDirector.clearRole(settler);
            return;
        }
        settler.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (settler.distanceToSqr(target) <= 6.25D) {
            settler.getNavigation().stop();
            settler.swing(InteractionHand.MAIN_HAND);
            WorldEventDirector.clearRole(settler);
            WorldEventDirector.roleReached(settler, role);
            return;
        }
        if (--repath <= 0) {
            repath = 10;
            settler.getNavigation().moveTo(target, role.speed());
        }
    }

    private void brawl(ServerLevel level) {
        Entity partner = role.target() == null ? null : level.getEntity(role.target());
        if (!(partner instanceof LivingEntity other) || !partner.isAlive()) {
            WorldEventDirector.clearRole(settler);
            return;
        }
        settler.getLookControl().setLookAt(partner, 40.0F, 40.0F);
        if (settler.distanceToSqr(partner) > 3.2D) {
            if (--repath <= 0) {
                repath = 10;
                settler.getNavigation().moveTo(partner, role.speed());
            }
            return;
        }
        settler.getNavigation().stop();
        // Staggered by UUID so the two brawlers trade blows instead of swinging in unison.
        int phase = Math.floorMod(settler.getUUID().hashCode(), 7);
        if ((ticks + phase) % 14 == 0) {
            settler.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, settler.blockPosition(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.NEUTRAL,
                0.6F, 0.9F + settler.getRandom().nextFloat() * 0.2F);
            other.knockback(0.15D, settler.getX() - other.getX(), settler.getZ() - other.getZ());
        }
        if ((ticks + phase) % 25 == 0) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, settler.getX(), settler.getY() + settler.getBbHeight() + 0.3D,
                settler.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.0D);
        }
    }

    private void walkOff() {
        BlockPos pos = role.pos();
        if (pos == null) return;
        if (--repath <= 0) {
            repath = 30;
            settler.getNavigation().moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, role.speed());
        }
    }
}

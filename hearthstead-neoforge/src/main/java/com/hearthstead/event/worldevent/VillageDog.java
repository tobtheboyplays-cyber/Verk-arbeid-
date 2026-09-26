package com.hearthstead.event.worldevent;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * The settlement's adopted dog (stray-dog event, "feed it and it moves in").
 * Exactly one per settlement (the row's {@code villageDog}), persistent, and
 * purely ambient: it follows a nearby player or a guard, and it pesters the
 * Courier: trots after them, begs and barks, but always keeps a gap so it
 * never bumps or slows them. Vanilla wolf body; every vanilla goal is
 * replaced by this one (no hunting sheep, no sitting orders).
 */
public final class VillageDog {
    public static final String TAG = "HearthsteadVillageDog";
    /** Never closer than this to the settler it pesters: no pushing, no slowdown. */
    static final double KEEP_CLEAR = 2.2D;

    private VillageDog() {
    }

    /** Turns a fed stray into the settlement's dog. */
    static void adopt(ServerLevel level, Settlement settlement, Wolf dog, WorldEventSavedData.Row row) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Settlement", settlement.id);
        dog.getPersistentData().put(TAG, tag);
        dog.setTame(true, false); // collar, no owner: nobody can order it to sit
        dog.setPersistenceRequired();
        row.villageDog = dog.getUUID();
        WorldEventSavedData.get(level).markChanged();
        install(dog);
    }

    @Nullable
    static UUID settlementOf(Entity dog) {
        CompoundTag tag = dog.getPersistentData().getCompound(TAG);
        return tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null;
    }

    /**
     * Join check: keeps exactly one dog per settlement. A dog of a settlement
     * that no longer exists, or a duplicate, returns false (refused at join).
     */
    static boolean accept(ServerLevel level, Wolf dog) {
        UUID settlementId = settlementOf(dog);
        Settlement settlement = settlementId == null ? null : SettlementManager.byId(level, settlementId);
        WorldEventSavedData data = WorldEventSavedData.get(level);
        if (data.quarantined()) {
            // BH-28: a quarantined (unreadable) events file must not delete the
            // player's dog at chunk load; keep it until the file is repaired.
            install(dog);
            return true;
        }
        WorldEventSavedData.Row row = settlement == null ? null : data.row(settlement.id);
        if (row == null || !dog.getUUID().equals(row.villageDog)) return false;
        install(dog);
        return true;
    }

    static void died(ServerLevel level, Wolf dog) {
        UUID settlementId = settlementOf(dog);
        WorldEventSavedData data = WorldEventSavedData.get(level);
        WorldEventSavedData.Row row = settlementId == null ? null : data.row(settlementId);
        if (row != null && dog.getUUID().equals(row.villageDog)) {
            row.villageDog = null; // a new stray may come some day
            data.markChanged();
        }
    }

    /** The live projection, or the settlement roll when the projection lags behind. */
    static boolean isCourier(@Nullable Settlement home, SettlerEntity settler) {
        if (settler.getProfession() == Profession.COURIER) return true;
        var record = home == null ? null : home.record(settler.getUUID());
        return record != null && record.profession == Profession.COURIER;
    }

    static void install(Wolf dog) {
        dog.goalSelector.removeAllGoals(goal -> true);
        dog.targetSelector.removeAllGoals(goal -> true);
        dog.setTarget(null);
        dog.setOrderedToSit(false);
        dog.setInSittingPose(false);
        dog.goalSelector.addGoal(0, new FloatGoal(dog));
        dog.goalSelector.addGoal(3, new CompanionGoal(dog));
        dog.goalSelector.addGoal(8, new LookAtPlayerGoal(dog, Player.class, 8.0F));
        dog.goalSelector.addGoal(9, new RandomLookAroundGoal(dog));
    }

    /** Pester the Courier, else follow a player or a guard, else potter about the Banner. */
    static final class CompanionGoal extends Goal {
        private final Wolf dog;
        private LivingEntity buddy;
        private boolean pestering;
        private int retarget;
        private int repath;
        private long pesterUntil;
        private long pesterCooldownUntil;

        CompanionGoal(Wolf dog) {
            this.dog = dog;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() { return dog.isAlive(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void stop() {
            dog.setIsInterested(false);
        }

        @Override
        public void tick() {
            if (!(dog.level() instanceof ServerLevel level)) return;
            long now = level.getGameTime();
            if (--retarget <= 0 || buddy == null || !buddy.isAlive()) {
                retarget = 40;
                chooseBuddy(level, now);
            }
            if (buddy == null) {
                wander(level);
                return;
            }
            double d = dog.distanceToSqr(buddy);
            double want = pestering ? KEEP_CLEAR : 3.0D;
            dog.getLookControl().setLookAt(buddy, 30F, 30F);
            if (d > (want + 1.5D) * (want + 1.5D)) {
                if (--repath <= 0) {
                    repath = 10;
                    dog.getNavigation().moveTo(buddy, pestering ? 1.2D : 1.0D);
                }
            } else if (d < want * want) {
                // Too close: back off so we never push or block the worker.
                dog.getNavigation().stop();
                double dx = dog.getX() - buddy.getX(), dz = dog.getZ() - buddy.getZ();
                double len = Math.max(0.1D, Math.sqrt(dx * dx + dz * dz));
                dog.getNavigation().moveTo(dog.getX() + dx / len * 1.5D, dog.getY(), dog.getZ() + dz / len * 1.5D, 1.0D);
            } else {
                dog.getNavigation().stop();
            }
            dog.setIsInterested(pestering && d < 16.0D);
            if (pestering && dog.getRandom().nextInt(120) == 0) dog.playSound(com.hearthstead.registry.ModSounds.EVENT_DOG_BARK.get(), 1.0F, 1.0F);
            if (pestering && dog.getRandom().nextInt(200) == 0) dog.playSound(com.hearthstead.registry.ModSounds.EVENT_DOG_WHINE.get(), 0.8F, 1.0F);
            if (pestering && now > pesterUntil) {
                pestering = false;
                pesterCooldownUntil = now + 1200;
                buddy = null;
            }
        }

        private void chooseBuddy(ServerLevel level, long now) {
            UUID settlementId = settlementOf(dog);
            if (settlementId == null) { buddy = null; return; }
            if (pestering && buddy != null && buddy.isAlive()) return;
            AABB around = dog.getBoundingBox().inflate(24.0D);
            if (now >= pesterCooldownUntil) {
                Settlement home = SettlementManager.byId(level, settlementId);
                List<SettlerEntity> couriers = level.getEntitiesOfClass(SettlerEntity.class, around,
                    s -> s.isAlive() && settlementId.equals(s.getSettlementId()) && isCourier(home, s));
                if (!couriers.isEmpty()) {
                    buddy = couriers.get(dog.getRandom().nextInt(couriers.size()));
                    pestering = true;
                    pesterUntil = now + 200 + dog.getRandom().nextInt(200);
                    return;
                }
            }
            pestering = false;
            Player player = level.getNearestPlayer(dog, 16.0D);
            if (player != null && !player.isSpectator()) { buddy = player; return; }
            List<SettlerEntity> guards = level.getEntitiesOfClass(SettlerEntity.class, dog.getBoundingBox().inflate(32.0D),
                s -> s.isAlive() && s.getProfession().martial() && !s.isSleeping()
                    && settlementId.equals(s.getSettlementId()));
            buddy = guards.isEmpty() ? null : guards.get(0);
        }

        private void wander(ServerLevel level) {
            if (!dog.getNavigation().isDone() || dog.getRandom().nextInt(80) != 0) return;
            Settlement settlement = SettlementManager.byId(level, settlementOf(dog));
            BlockPos home = settlement == null ? dog.blockPosition() : settlement.center;
            dog.getNavigation().moveTo(home.getX() + dog.getRandom().nextInt(17) - 8, home.getY(),
                home.getZ() + dog.getRandom().nextInt(17) - 8, 0.8D);
        }
    }
}

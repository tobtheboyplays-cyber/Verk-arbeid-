package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.IShearable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Two ways HerderWorkGoal and HunterWorkGoal turn a live animal into real
 * items, shared so both write it once: {@link #shear} and {@link #kill}.
 *
 * <h2>Shearing: a clean {@code captureDrops} wrap</h2>
 *
 * <p>{@link net.minecraft.world.entity.Entity#captureDrops} lets a caller
 * install a sink list so that every {@code spawnAtLocation} call the entity
 * makes appends to it INSTEAD of spawning an {@link ItemEntity} into the
 * world. {@code Sheep#shear()} has no capture logic of its own — it just
 * calls {@code spawnAtLocation} directly — so installing a sink around it
 * (the same pattern NeoForge's own {@code IShearable#onSheared} default
 * uses) cleanly answers "what did shearing just drop" with nothing ever
 * touching the ground.
 *
 * <h2>Killing: that trick does NOT work, and {@link #kill} does not try it</h2>
 *
 * <p>KF-029 (live crash, first suite run after the hunter landed): the
 * original {@link #kill} wrapped {@code hurt()} in the exact same
 * install/read-back pattern, and it took the whole GameTest server down —
 * {@code NullPointerException: Cannot invoke "Collection.size()" because
 * "captured" is null}. The reason is that {@code LivingEntity#die()} calls
 * {@code dropAllDeathLoot()}, which installs and owns ITS OWN capture list
 * (discarding whatever the caller installed — the return value of its own
 * {@code captureDrops(new ArrayList<>())} call is never even read), collects
 * every real drop into it, and then — unconditionally, unless a mod's
 * {@code onLivingDrops} event cancels it — hands that list to
 * {@code drops.forEach(e -> level().addFreshEntity(e))} and sets the capture
 * field back to {@code null}. So a kill's drops are never sitting in a
 * capturable list for an outer caller to read at all: by the time
 * {@code hurt()} returns, they are already real {@link ItemEntity}s standing
 * in the world, and the field an outer wrap tried to read back is {@code null}
 * — which is exactly the crash.
 *
 * <p>The fix here does what shearing's capture trick was always a shortcut
 * for: snapshot which {@link ItemEntity} UUIDs already exist near the kill
 * position, let the kill happen for real, then diff. Drops land within a
 * couple of blocks of the death position in the same tick (before their
 * randomized velocity has carried them anywhere), so the window this reads
 * is small and bounded — the same "budgeted, not unbounded" shape every
 * other scan in this mod follows, just sized in blocks instead of ticks.
 * Every matched drop is immediately absorbed ({@code discard()}) rather than
 * left for a second pass to remember to sweep up.
 */
final class AnimalHarvest {

    /** How far a fresh death drop can have scattered by the time this reads
     *  the world back, one tick after the kill. Generous on purpose. */
    private static final double DROP_SCAN_RADIUS = 4.0;

    private AnimalHarvest() {
    }

    /**
     * Shears {@code sheep} through NeoForge's real shearing capability (the
     * same path a player's shears would take) and returns exactly what came
     * off — the sheep's own wool colour, whatever it is. Caller must already
     * have checked {@link IShearable#isShearable}; returns an empty list if
     * {@code sheep} is somehow not an {@link IShearable} at all.
     */
    static List<ItemStack> shear(ServerLevel level, Sheep sheep) {
        if (!(sheep instanceof IShearable shearable)) {
            return List.of();
        }
        // player=null, item=EMPTY: this is the settler's own hands doing the
        // work, not an item stack that could break -- onSheared's default
        // implementation only uses `player` to pick a sound source (BLOCKS
        // vs PLAYERS) and to hand back change from a tool, neither of which
        // applies here.
        return shearable.onSheared(null, ItemStack.EMPTY, level, sheep.blockPosition());
    }

    /**
     * Kills {@code target} outright and returns its real death drops —
     * whatever its own loot table hands back for the damage source given,
     * feathers/hides/rare drops included. The kill is genuinely lethal
     * ({@code target.getHealth() + 1} damage) so this always resolves in one
     * call; nothing is left half-dead for a caller to finish off later.
     *
     * <p>See the class doc's KF-029 section for why this reads the world
     * back rather than wrapping {@code hurt()} in {@code captureDrops}.
     */
    static List<ItemStack> kill(ServerLevel level, LivingEntity target, DamageSource source) {
        BlockPos deathPos = target.blockPosition();
        AABB nearby = new AABB(deathPos).inflate(DROP_SCAN_RADIUS);
        Set<UUID> before = new HashSet<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, nearby)) {
            before.add(item.getUUID());
        }

        target.hurt(source, target.getHealth() + 1.0F);

        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, nearby)) {
            if (item.isAlive() && !before.contains(item.getUUID())) {
                drops.add(item.getItem().copy());
                // Absorbed here, not left on the ground for anyone to
                // remember to sweep up -- the caller is the one taking it.
                item.discard();
            }
        }
        return drops;
    }
}

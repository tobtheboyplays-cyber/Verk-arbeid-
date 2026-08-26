package com.hearthstead.entity.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.IShearable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Two ways HerderWorkGoal and HunterWorkGoal turn a live animal into real
 * items, shared so both write it once: {@link #shear} and {@link #kill}.
 *
 * <h2>Chest truth without a scavenger hunt</h2>
 *
 * <p>The naive way to "collect what an action drops" is to let the game spawn
 * the {@link ItemEntity}s into the world and then scan a radius for them a
 * moment later — which works, but makes every caller re-solve budgeting
 * ("how far", "how long") for a problem the engine already has a clean
 * answer to. {@link net.minecraft.world.entity.Entity#captureDrops} is that
 * answer: while a collection is installed, every {@code spawnAtLocation}
 * call the entity makes appends to it INSTEAD of spawning an
 * {@code ItemEntity} into the world at all (see {@code Entity#spawnAtLocation}
 * and NeoForge's own {@code IShearable#onSheared} default, which uses exactly
 * this to answer "what did shearing just drop"). So the pattern below is:
 * install a capture list, perform the real action (a real shear, a real
 * kill), read the list back, uninstall it. What comes back is the honest
 * loot — the sheep's own {@code SHEEP_<color>} table when sheared, the real
 * death loot table when killed, feathers and all — never a hand-picked
 * subset, and never anything left ownerless on the ground for a caller to
 * remember to sweep up.
 */
final class AnimalHarvest {

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
     */
    static List<ItemStack> kill(ServerLevel level, LivingEntity target, DamageSource source) {
        // Read from OUR OWN sink, never from what the second captureDrops
        // call hands back.
        //
        // LIVE CRASH (run 20260826T063349Z, first suite run after the
        // hunter landed): the second call returned null and the whole
        // GameTest server went down on
        // "Cannot invoke Collection.size() because captured is null" --
        // 207 tests reduced to no result at all. The reason is
        // re-entrancy: LivingEntity#die does this same save/swap/restore
        // dance internally around dropAllDeathLoot, so by the time hurt()
        // returns, the field is no longer whatever we put there and its
        // value is not ours to reason about. Holding a reference to the
        // list we passed in sidesteps the question entirely, and the
        // restore below is in a finally so a throw inside hurt() can never
        // leave this entity capturing drops forever.
        List<ItemEntity> sink = new ArrayList<>();
        Collection<ItemEntity> previous = target.captureDrops(sink);
        try {
            target.hurt(source, target.getHealth() + 1.0F);
        } finally {
            target.captureDrops(previous);
        }
        List<ItemStack> drops = new ArrayList<>(sink.size());
        for (ItemEntity item : sink) {
            drops.add(item.getItem());
        }
        return drops;
    }
}

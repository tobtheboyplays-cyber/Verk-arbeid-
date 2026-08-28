package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.IShearable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The herder's day, inside the paddock the plaque already scanned: shears
 * ready sheep, feeds breedable pairs, collects laid eggs, and — only once a
 * species is genuinely crowded — culls one for the butcher.
 *
 * <h2>The paddock is the building's own bounds</h2>
 *
 * <p>PLAN_CIRCULATION.md: "tends/breeds real animals in a bounded paddock."
 * There is no separate fenced-area concept here — {@link Building#bounds} IS
 * the paddock, the same room {@code RoomScanner} already measured and capped
 * (PASTURE's {@code floorSpace(25)} requirement), so every scan this goal
 * runs is bounded by construction, not by an extra budget this file has to
 * invent. <b>The player stocks the paddock</b> — this goal never spawns an
 * animal, only ever acts on real {@link Animal} entities the player already
 * walked in there; an empty pasture is empty, forever, until the player puts
 * something in it.
 *
 * <h2>The breeding floor</h2>
 *
 * <p>"a herd that is culled to nothing produces nothing ever again" — so
 * culling is gated on a real headcount, read fresh every scan, never a
 * cached one: a species is only offered up for culling once its ADULT
 * headcount exceeds {@value #CULL_THRESHOLD}, which leaves at least that
 * many behind — comfortably above {@value #MIN_HERD_FOR_BREEDING}, the
 * floor breeding itself already needs a mate to clear. A baby is never
 * culled (it is tomorrow's adult) and an animal already courting
 * ({@code isInLove()}) is never culled either (killing half a courtship a
 * tick before it would have produced a lamb is the one way this system could
 * make itself worse, not better).
 *
 * <h2>Chest truth, both directions</h2>
 *
 * <p>Feed for breeding is real: one matching item, taken from the herder's
 * own bag or (failing that) the pasture's own chests — the same
 * bag-then-chest withdrawal {@link FarmerWorkGoal#ensureSeedInBag} already
 * established — and only spent once the feeding actually lands
 * ({@link Animal#setInLove}), never before. Wool and cull yields are the
 * real loot the world hands back ({@link AnimalHarvest}), inserted into the
 * pasture's own containers exactly like {@link MinerWorkGoal} does for the
 * mine; whatever does not fit pops onto the ground at the anchor rather than
 * vanishing (INV-3).
 */
public class HerderWorkGoal extends Goal {

    private enum Action { EGG, SHEAR, FEED, CULL }

    /** How often to rescan the paddock when there is nothing already found. */
    private static final int LOOK_INTERVAL = 30;
    /** HERDER_SHEAR is a 1.00s/20-tick loop; one full loop is one shear. */
    private static final int SHEAR_DURATION = 20;
    private static final int SHEAR_ACCENT_TICK = 9;
    /** Reuses WORK_SOW's SOW_BROADCAST clip (1.40s/28 ticks) -- see the
     *  class doc on why feeding reuses the farmer's broadcast gesture. */
    private static final int FEED_DURATION = 28;
    private static final int FEED_ACCENT_TICK = 14;
    /** Reuses WORK_CLEAVE's CLEAVE clip (0.85s/17 ticks). */
    private static final int CULL_DURATION = 17;
    private static final int CULL_ACCENT_TICK = 8;
    private static final double REACH = 3.0;
    private static final double EGG_REACH = 2.0;
    private static final int REPATH_INTERVAL = 30;
    private static final int PATIENCE = 8;

    /** Only bother feeding a species that already has a potential mate. */
    private static final int MIN_HERD_FOR_BREEDING = 2;
    /** THE BREEDING FLOOR: a species is a cull candidate only once its adult
     *  headcount is strictly greater than this, so at least this many always
     *  remain -- see the class doc. */
    private static final int CULL_THRESHOLD = 5;

    private final SettlerEntity settler;
    private Building pasture;
    private Action action;
    private Animal targetAnimal;
    private ItemEntity targetEgg;
    private int workTicks;
    private int lookCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public HerderWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.HERDER
            && settler.isBound()
            && settler.dayPhase().work()
            && settler.getEnergy() > 15
            && !settler.isEffortSpent();
    }

    @Override
    public boolean canUse() {
        if (!workConditions()) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL + settler.getRandom().nextInt(LOOK_INTERVAL);
        Building building = Employment.employerOf(s, settler.getUUID());
        if (building == null || !building.valid || building.bounds == null) {
            return false;
        }
        AABB box = AABB.of(building.bounds);

        // Eggs are the cheapest win and the only yield that ages badly (a
        // laid egg just sits there until picked up), so they go first.
        List<ItemEntity> eggs = level.getEntitiesOfClass(ItemEntity.class, box,
            e -> e.isAlive() && e.getItem().is(Items.EGG));
        if (!eggs.isEmpty()) {
            pasture = building;
            targetEgg = eggs.get(0);
            targetAnimal = null;
            action = Action.EGG;
            return true;
        }

        List<Animal> animals = level.getEntitiesOfClass(Animal.class, box, Animal::isAlive);
        if (animals.isEmpty()) {
            return false; // the player has not stocked the paddock
        }

        for (Animal a : animals) {
            if (a instanceof Sheep sheep && readyToShear(level, sheep)) {
                pasture = building;
                targetAnimal = sheep;
                targetEgg = null;
                action = Action.SHEAR;
                return true;
            }
        }

        Map<Class<?>, Integer> adultCounts = new HashMap<>();
        for (Animal a : animals) {
            if (a.getAge() == 0) {
                adultCounts.merge(a.getClass(), 1, Integer::sum);
            }
        }

        for (Animal a : animals) {
            if (a.getAge() == 0 && a.canFallInLove()
                && adultCounts.getOrDefault(a.getClass(), 0) >= MIN_HERD_FOR_BREEDING
                && hasFeedFor(level, building, a)) {
                pasture = building;
                targetAnimal = a;
                targetEgg = null;
                action = Action.FEED;
                return true;
            }
        }

        for (Animal a : animals) {
            if (a.getAge() == 0 && !a.isInLove()
                && adultCounts.getOrDefault(a.getClass(), 0) > CULL_THRESHOLD) {
                pasture = building;
                targetAnimal = a;
                targetEgg = null;
                action = Action.CULL;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (done || !workConditions()) {
            return false;
        }
        return switch (action) {
            case EGG -> targetEgg != null && targetEgg.isAlive();
            default -> targetAnimal != null && targetAnimal.isAlive();
        };
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        workTicks = 0;
        stuckChecks = 0;
        repathTimer = 0;
        settler.setActivity(switch (action) {
            case EGG -> SettlerActivity.TRAVELING;
            case SHEAR -> SettlerActivity.WORK_SHEAR;
            // D-016's reuse rule (§16.3): both borrow an already-authored
            // motion rather than inventing a near-duplicate. Tossing feed to
            // the herd IS a broadcast toss -- the same gesture WORK_SOW's
            // SOW_BROADCAST clip already is. Dispatching a surplus animal IS
            // the same knife-to-livestock stroke as the butcher's own
            // WORK_CLEAVE. Both are named in HerderWorkGoal's class doc, not
            // just here, so the reuse is documented where a reader would
            // first look for HERDER's own signature (WORK_SHEAR above).
            case FEED -> SettlerActivity.WORK_SOW;
            case CULL -> SettlerActivity.WORK_CLEAVE;
        });
        pathToTarget();
    }

    private void pathToTarget() {
        if (action == Action.EGG && targetEgg != null) {
            settler.getNavigation().moveTo(targetEgg, 0.9);
        } else if (targetAnimal != null) {
            settler.getNavigation().moveTo(targetAnimal, 0.9);
        }
    }

    @Override
    public void tick() {
        switch (action) {
            case EGG -> tickEgg();
            case SHEAR -> tickShear();
            case FEED -> tickFeed();
            case CULL -> tickCull();
        }
    }

    private boolean approach(BlockPos targetPos, double reach) {
        if (settler.blockPosition().closerThan(targetPos, reach)) {
            settler.getNavigation().stop();
            return true;
        }
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > PATIENCE) {
                settler.recordRouteFailure("herder_unreachable");
                done = true;
                return false;
            }
            pathToTarget();
        }
        return false;
    }

    private void tickEgg() {
        if (targetEgg == null || !targetEgg.isAlive()) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(targetEgg.getX(), targetEgg.getY() + 0.2,
            targetEgg.getZ());
        if (!approach(targetEgg.blockPosition(), EGG_REACH)) {
            return;
        }
        if (settler.level() instanceof ServerLevel level && pasture != null) {
            ItemStack picked = targetEgg.getItem().copy();
            ItemStack leftover = insertIntoContainers(level, pasture, picked);
            if (leftover.isEmpty()) {
                targetEgg.discard();
            } else {
                // Chest truth: a full pasture leaves the rest sitting exactly
                // where it was, never destroyed.
                targetEgg.setItem(leftover);
            }
            settler.triggerPickup();
        }
        done = true;
    }

    private void tickShear() {
        if (!(targetAnimal instanceof Sheep sheep) || !sheep.isAlive()
            || !(settler.level() instanceof ServerLevel level) || !readyToShear(level, sheep)) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(sheep.getX(), sheep.getEyeY(), sheep.getZ());
        if (!approach(sheep.blockPosition(), REACH)) {
            return;
        }
        workTicks++;
        if (workTicks == SHEAR_ACCENT_TICK) {
            level.playSound(null, sheep.blockPosition(), ModSounds.HIDE_SCRAPE.get(),
                SoundSource.NEUTRAL, 0.7F, 1.15F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= SHEAR_DURATION) {
            List<ItemStack> drops = AnimalHarvest.shear(level, sheep);
            depositOrDrop(level, drops);
            settler.train(Employment.trainedBy(BuildingType.PASTURE), 1.0F);
            settler.spendEffort(1);
            done = true;
        }
    }

    private void tickFeed() {
        if (targetAnimal == null || !targetAnimal.isAlive() || targetAnimal.getAge() != 0
            || !targetAnimal.canFallInLove()) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(targetAnimal.getX(), targetAnimal.getEyeY(),
            targetAnimal.getZ());
        if (!approach(targetAnimal.blockPosition(), REACH)) {
            return;
        }
        workTicks++;
        if (settler.level() instanceof ServerLevel level && workTicks == FEED_ACCENT_TICK) {
            level.playSound(null, targetAnimal.blockPosition(), ModSounds.SEED_PRESS.get(),
                SoundSource.NEUTRAL, 0.6F, 0.9F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= FEED_DURATION) {
            if (settler.level() instanceof ServerLevel level
                && consumeFeedFor(level, pasture, targetAnimal)) {
                targetAnimal.setInLove(null);
                settler.train(Employment.trainedBy(BuildingType.PASTURE), 1.0F);
                settler.spendEffort(1);
            }
            // No feed anywhere: the walk was for nothing this cycle, same as
            // a farmer finding a mature crop that vanished before arrival --
            // nothing is destroyed, the next scan simply tries again.
            done = true;
        }
    }

    private void tickCull() {
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(targetAnimal.getX(), targetAnimal.getEyeY(),
            targetAnimal.getZ());
        if (!approach(targetAnimal.blockPosition(), REACH)) {
            return;
        }
        workTicks++;
        if (settler.level() instanceof ServerLevel level && workTicks == CULL_ACCENT_TICK) {
            level.playSound(null, targetAnimal.blockPosition(), ModSounds.CLEAVER_CHOP.get(),
                SoundSource.NEUTRAL, 0.8F, 0.9F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= CULL_DURATION) {
            if (settler.level() instanceof ServerLevel level && targetAnimal.isAlive()) {
                DamageSource source = level.damageSources().mobAttack(settler);
                List<ItemStack> drops = AnimalHarvest.kill(level, targetAnimal, source);
                depositOrDrop(level, drops);
                settler.train(Employment.trainedBy(BuildingType.PASTURE), 1.0F);
                settler.spendEffort(2);
            }
            done = true;
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        targetAnimal = null;
        targetEgg = null;
        pasture = null;
    }

    // ------------------------------------------------------------ helpers ---

    private boolean readyToShear(ServerLevel level, Sheep sheep) {
        return sheep instanceof IShearable shearable
            && shearable.isShearable(null, ItemStack.EMPTY, level, sheep.blockPosition());
    }

    /** Peeks the bag, then the pasture's own chests, for a stack this animal
     *  would eat -- without removing anything. */
    private boolean hasFeedFor(ServerLevel level, Building building, Animal animal) {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (animal.isFood(settler.bag.getItem(i))) {
                return true;
            }
        }
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            if (level.getBlockEntity(pos) instanceof Container chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (animal.isFood(chest.getItem(slot))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Removes exactly one matching feed item, bag first. */
    private boolean consumeFeedFor(ServerLevel level, Building building, Animal animal) {
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (animal.isFood(settler.bag.getItem(i))) {
                settler.bag.removeItem(i, 1);
                return true;
            }
        }
        if (building == null) {
            return false;
        }
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            if (level.getBlockEntity(pos) instanceof Container chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (animal.isFood(chest.getItem(slot))) {
                        chest.removeItem(slot, 1);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void depositOrDrop(ServerLevel level, List<ItemStack> drops) {
        if (pasture == null) {
            return;
        }
        List<Container> containers = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, pasture)) {
            if (level.getBlockEntity(pos) instanceof Container chest) {
                containers.add(chest);
            }
        }
        for (ItemStack drop : drops) {
            ItemStack left = insert(containers, drop);
            if (!left.isEmpty() && pasture.anchor != null) {
                // INV-3: never destroyed, only ever set down where there was
                // no room -- the same overflow rule MinerWorkGoal uses.
                Block.popResource(level, pasture.anchor, left);
            }
        }
    }

    private ItemStack insertIntoContainers(ServerLevel level, Building building, ItemStack stack) {
        List<Container> containers = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            if (level.getBlockEntity(pos) instanceof Container chest) {
                containers.add(chest);
            }
        }
        return insert(containers, stack);
    }

    private static ItemStack insert(List<Container> containers, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack in = container.getItem(slot);
                if (in.isEmpty()) {
                    container.setItem(slot, remaining.copy());
                    return ItemStack.EMPTY;
                }
                if (ItemStack.isSameItemSameComponents(in, remaining)
                    && in.getCount() < in.getMaxStackSize()) {
                    int move = Math.min(remaining.getCount(), in.getMaxStackSize() - in.getCount());
                    in.grow(move);
                    container.setChanged();
                    remaining.shrink(move);
                }
            }
        }
        return remaining;
    }
}

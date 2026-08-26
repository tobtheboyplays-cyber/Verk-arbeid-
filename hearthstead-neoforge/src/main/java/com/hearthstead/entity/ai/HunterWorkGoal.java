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
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hunter's expedition: range a bounded radius out from the lodge, take
 * one wild animal at a time, and bring the real meat and hide home — or, if
 * nothing is safe to take, forage a mushroom instead.
 *
 * <h2>What stops this from stripping the world</h2>
 *
 * <p>"a hunter that clears every animal in the chunk permanently is a
 * strictly worse pasture" — so four separate things bound it, all real
 * every time, none cached:
 *
 * <ol>
 *   <li><b>A hard range cap.</b> {@link #findHuntable} only ever looks
 *       inside {@value #HUNT_RADIUS} blocks of the lodge's own anchor — an
 *       expedition, not a search of the whole loaded world.
 *   <li><b>The population floor.</b> A species is huntable only when MORE
 *       than {@value #MIN_SPECIES_POPULATION} of it are alive inside that
 *       same radius <i>right now</i> — counted fresh on every scan, the same
 *       "read live, never a cached headcount" rule {@link HerderWorkGoal}'s
 *       cull floor follows. Thin the herd to the floor and the hunter simply
 *       finds nothing to hunt of that species until it breeds back — this is
 *       what makes a hunter different from a strictly-worse pasture: a
 *       pasture's floor is enforced by the player choosing what to cull, a
 *       hunter's has to enforce itself.
 *   <li><b>The player's own stock is off limits.</b> Any animal standing
 *       inside a REAL building's bounds — a player's pasture chief among
 *       them — is invisible to this goal ({@link #insideAnyBuilding}). A
 *       hunter that could walk into the paddock it is supposed to leave
 *       alone would make {@code HerderWorkGoal}'s whole breeding-floor
 *       promise a lie.
 *   <li><b>The daily labor pool.</b> {@code isEffortSpent()} caps how many
 *       kills one hunter can even attempt in a day, the same cap every
 *       other trade already obeys.
 * </ol>
 *
 * <h2>Foraging (PLAN_CIRCULATION.md: "occasional mushrooms — the forage
 * source the kitchen audit demands")</h2>
 *
 * <p>When nothing huntable is in range, the same expedition also checks for
 * a real {@code BROWN_MUSHROOM} within reach — {@code KITCHEN}'s own
 * {@code stew} recipe already consumes exactly that block
 * ({@code Production.of(KITCHEN)}), so this is a genuine Ring-1 input, not a
 * flavour add-on with no consumer.
 */
public class HunterWorkGoal extends Goal {

    private enum Action { HUNT, FORAGE }

    private enum Mode { TO_TARGET, WORKING, TO_LODGE }

    /** THE RANGE CAP: see the class doc, point 1. */
    private static final int HUNT_RADIUS = 28;
    private static final int VERTICAL_BAND = 10;
    /** THE POPULATION FLOOR: see the class doc, point 2. */
    private static final int MIN_SPECIES_POPULATION = 4;

    private static final int LOOK_INTERVAL = 50;
    private static final double HUNT_REACH = 6.0;
    private static final double FORAGE_REACH = 2.5;
    /** HUNTER_LOOSE is a 1.20s/24-tick loop; one full loop is one shot. */
    private static final int HUNT_DURATION = 24;
    private static final int LOOSE_ACCENT_TICK = 14;
    private static final int BAG_TRIGGER = 6;
    private static final int REPATH_INTERVAL = 40;
    private static final int PATIENCE = 6;
    private static final int FORAGE_SCAN_BUDGET = 400;

    private final SettlerEntity settler;
    private final WorkScanner mushroomScanner = new WorkScanner();
    private Mode mode;
    private Action action;
    private Building lodge;
    private Animal target;
    private BlockPos mushroomTarget;
    private int workTicks;
    private int lookCooldown;
    private int mushroomCooldown;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public HunterWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean workConditions() {
        return settler.getProfession() == Profession.HUNTER
            && settler.isBound()
            && settler.dayPhase().work()
            && settler.getEnergy() > 15
            && !settler.isEffortSpent();
    }

    private int bagCount() {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    @Override
    public boolean canUse() {
        if (!workConditions()) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null) {
            return false;
        }
        if (bagCount() >= BAG_TRIGGER) {
            mode = Mode.TO_LODGE;
            return true;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL + settler.getRandom().nextInt(LOOK_INTERVAL);
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Building building = Employment.employerOf(s, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null) {
            return false;
        }
        lodge = building;

        Animal found = findHuntable(level, s, building.anchor);
        if (found != null) {
            target = found;
            mushroomTarget = null;
            action = Action.HUNT;
            mode = Mode.TO_TARGET;
            return true;
        }

        // Nothing safe to hunt this cycle: forage instead. Own cooldown/
        // cursor, budgeted like every other block scan in the mod
        // (WorkScanner), so a hunt-poor cycle never costs more than a
        // gathering one would.
        if (mushroomCooldown > 0) {
            mushroomCooldown--;
        } else {
            List<BlockPos> found2 = mushroomScanner.scan(building.anchor, HUNT_RADIUS,
                FORAGE_SCAN_BUDGET, 4, this::isForageable);
            if (!found2.isEmpty()) {
                mushroomTarget = found2.get(0);
                target = null;
                action = Action.FORAGE;
                mode = Mode.TO_TARGET;
                return true;
            }
            mushroomCooldown = 100 + settler.getRandom().nextInt(60);
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && (mode == Mode.TO_LODGE || workConditions());
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
        settler.setActivity(SettlerActivity.TRAVELING);
        if (mode == Mode.TO_LODGE) {
            pathToLodge();
        } else if (action == Action.HUNT && target != null) {
            settler.getNavigation().moveTo(target, 1.0);
        } else if (mushroomTarget != null) {
            settler.getNavigation().moveTo(mushroomTarget.getX() + 0.5, mushroomTarget.getY(),
                mushroomTarget.getZ() + 0.5, 0.9);
        }
    }

    private void pathToLodge() {
        if (lodge != null && lodge.anchor != null) {
            settler.getNavigation().moveTo(lodge.anchor.getX() + 0.5,
                lodge.anchor.getY() + 1, lodge.anchor.getZ() + 0.5, 1.0);
        } else {
            done = true;
        }
    }

    @Override
    public void tick() {
        if (mode == Mode.TO_LODGE) {
            tickDeposit();
        } else if (action == Action.FORAGE) {
            tickForage();
        } else if (mode == Mode.TO_TARGET) {
            tickTravelToTarget();
        } else {
            tickHunt();
        }
    }

    private void tickTravelToTarget() {
        if (target == null || !target.isAlive()) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
        if (settler.blockPosition().closerThan(target.blockPosition(), HUNT_REACH)) {
            mode = Mode.WORKING;
            workTicks = 0;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.WORK_HUNT);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > PATIENCE) {
                settler.recordRouteFailure("hunt_unreachable");
                done = true;
            } else {
                settler.getNavigation().moveTo(target, 1.0);
            }
        }
    }

    private void tickHunt() {
        if (target == null || !target.isAlive()) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ());
        workTicks++;
        if (workTicks == LOOSE_ACCENT_TICK && settler.level() instanceof ServerLevel level) {
            level.playSound(null, settler.blockPosition(), ModSounds.PICK_STRIKE.get(),
                SoundSource.NEUTRAL, 0.8F, 1.05F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= HUNT_DURATION) {
            if (settler.level() instanceof ServerLevel level && target.isAlive()) {
                DamageSource source = level.damageSources().mobAttack(settler);
                List<ItemStack> drops = AnimalHarvest.kill(level, target, source);
                for (ItemStack drop : drops) {
                    ItemStack leftover = settler.bag.addItem(drop);
                    if (!leftover.isEmpty()) {
                        Block.popResource(level, target.blockPosition(), leftover);
                    }
                }
                settler.train(Employment.trainedBy(BuildingType.HUNTERS_LODGE), 1.0F);
                settler.spendEffort(2);
            }
            // BUG (suite run 20260826, "activity=IDLE, alive=4"): this used
            // to be an unconditional done=true, on the assumption a settler
            // would top the bag up to BAG_TRIGGER over several engagements
            // before heading home -- exactly wrong for a floored trade. A
            // single kill's drops (2-4 items) rarely reach BAG_TRIGGER (6),
            // and once the population floor takes the species out of
            // findHuntable's eligible set there may be no SECOND kill this
            // expedition ever to top it off with -- the catch sat in the
            // bag forever, never reaching the chest, while the settler read
            // as an idle failure with real loot already on its back. A
            // hunter brings a catch home after every kill, the same way a
            // real one does not wait for a second deer before walking back;
            // BAG_TRIGGER (checked at the top of canUse()) still exists as
            // a resume-with-a-loaded-bag safety net, not the only way home.
            if (bagCount() > 0) {
                mode = Mode.TO_LODGE;
                settler.setActivity(SettlerActivity.TRAVELING);
                pathToLodge();
            } else {
                done = true;
            }
        }
    }

    private void tickForage() {
        if (mushroomTarget == null || !isForageable(mushroomTarget)) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(mushroomTarget.getX() + 0.5,
            mushroomTarget.getY() + 0.3, mushroomTarget.getZ() + 0.5);
        if (!settler.blockPosition().closerThan(mushroomTarget, FORAGE_REACH)) {
            if (--repathTimer <= 0) {
                repathTimer = REPATH_INTERVAL;
                if (++stuckChecks > PATIENCE) {
                    done = true;
                    return;
                }
                settler.getNavigation().moveTo(mushroomTarget.getX() + 0.5, mushroomTarget.getY(),
                    mushroomTarget.getZ() + 0.5, 0.9);
            }
            return;
        }
        if (settler.level() instanceof ServerLevel level) {
            BlockState state = level.getBlockState(mushroomTarget);
            if (state.is(Blocks.BROWN_MUSHROOM)) {
                List<ItemStack> drops = Block.getDrops(state, level, mushroomTarget, null);
                level.destroyBlock(mushroomTarget, false);
                for (ItemStack drop : drops) {
                    ItemStack leftover = settler.bag.addItem(drop);
                    if (!leftover.isEmpty()) {
                        Block.popResource(level, mushroomTarget, leftover);
                    }
                }
                settler.triggerPickup();
                settler.train(Employment.trainedBy(BuildingType.HUNTERS_LODGE), 1.0F);
            }
        }
        // Same fix as tickHunt(): carry a single forage home rather than
        // stranding it in the bag waiting for a BAG_TRIGGER that a light
        // yield may never reach on its own.
        if (bagCount() > 0) {
            mode = Mode.TO_LODGE;
            settler.setActivity(SettlerActivity.TRAVELING);
            pathToLodge();
        } else {
            done = true;
        }
    }

    private void tickDeposit() {
        if (lodge == null || lodge.anchor == null) {
            done = true;
            return;
        }
        BlockPos anchor = lodge.anchor;
        settler.getLookControl().setLookAt(anchor.getX() + 0.5, anchor.getY() + 1.0,
            anchor.getZ() + 0.5);
        if (settler.blockPosition().closerThan(anchor, 3.0)) {
            if (settler.level() instanceof ServerLevel level) {
                List<Container> containers = new ArrayList<>();
                for (BlockPos pos : WarehouseIndex.containers(level, lodge)) {
                    if (level.getBlockEntity(pos) instanceof Container chest) {
                        containers.add(chest);
                    }
                }
                for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                    ItemStack stack = settler.bag.getItem(i);
                    if (!stack.isEmpty()) {
                        settler.bag.setItem(i, insert(containers, stack));
                    }
                }
            }
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            pathToLodge();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        target = null;
        mushroomTarget = null;
    }

    // ------------------------------------------------------------ helpers ---

    /** Wild game only — the classic overworld passives whose real loot
     *  tables already back Ring-2 (BUTCHER's meat, TANNERY's rabbit hide,
     *  FLETCHER's future feather input, and sheared-in-the-wild wool). */
    private static boolean isWildGame(Animal a) {
        return a instanceof Cow || a instanceof Pig || a instanceof Sheep
            || a instanceof Chicken || a instanceof Rabbit;
    }

    private boolean insideAnyBuilding(Settlement s, BlockPos pos) {
        for (Building b : s.buildings) {
            if (b.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest wild animal this hunter is actually allowed to take, or
     * null. Every guard here is re-evaluated from the live world on every
     * call — see the class doc's "none cached" promise.
     */
    private Animal findHuntable(ServerLevel level, Settlement s, BlockPos anchor) {
        AABB box = new AABB(anchor).inflate(HUNT_RADIUS, VERTICAL_BAND, HUNT_RADIUS);
        List<Animal> animals = level.getEntitiesOfClass(Animal.class, box,
            a -> a.isAlive() && isWildGame(a) && !insideAnyBuilding(s, a.blockPosition()));
        if (animals.isEmpty()) {
            return null;
        }
        Map<Class<?>, Integer> counts = new HashMap<>();
        for (Animal a : animals) {
            counts.merge(a.getClass(), 1, Integer::sum);
        }
        Animal best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Animal a : animals) {
            if (counts.getOrDefault(a.getClass(), 0) <= MIN_SPECIES_POPULATION) {
                continue; // the population floor: leave this species alone
            }
            double d = a.distanceToSqr(settler);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                best = a;
            }
        }
        return best;
    }

    private boolean isForageable(BlockPos pos) {
        return settler.level().getBlockState(pos).is(Blocks.BROWN_MUSHROOM);
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

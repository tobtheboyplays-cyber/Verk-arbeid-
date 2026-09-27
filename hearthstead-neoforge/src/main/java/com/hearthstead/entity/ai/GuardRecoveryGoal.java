package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** Injured guards temporarily yield combat to a physical, food-funded recovery. */
public final class GuardRecoveryGoal extends Goal {
    private static final String ACTIVE_KEY = "HearthsteadGuardRecovery";
    private static final double SAFE_RADIUS = 6.0;
    private static final double RUN_SPEED = 1.3;
    private static final int REPATH_TICKS = 20;
    private final SettlerEntity settler;
    private final boolean peacetime;
    private long nextPathAt;
    private Status status = Status.INACTIVE;

    public enum Status { INACTIVE, RETREATING, RECOVERING, WAITING_FOR_FOOD, PATH_BLOCKED }

    public GuardRecoveryGoal(SettlerEntity settler) {
        this(settler, false);
    }

    public GuardRecoveryGoal(SettlerEntity settler, boolean peacetime) {
        this.settler = settler;
        this.peacetime = peacetime;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public Status status() { return status; }
    public boolean peacetime() { return peacetime; }

    private boolean eligible() {
        Settlement settlement = settler.settlement();
        return settler.isAlive() && !settler.isTraveler()
            && settler.getProfession() == Profession.GUARD && settlement != null
            && !settler.getUUID().equals(settlement.mayorId)
            && Employment.professionOf(settlement, settler.getUUID()) == Profession.GUARD;
    }

    @Override
    public boolean canUse() {
        if (!eligible()) {
            if (!peacetime) settler.getPersistentData().remove(ACTIVE_KEY);
            return false;
        }
        if (peacetime) return peaceReady();
        boolean active = settler.getPersistentData().getBoolean(ACTIVE_KEY);
        boolean needed = GuardRecoveryPolicy.shouldRecover(settler.getHealth(),
            settler.getMaxHealth(), active);
        // Tech tree (War Feast): feasted guards hold the line until 15% health.
        if (needed && !active) {
            needed = settler.getHealth() <= settler.getMaxHealth()
                * com.hearthstead.settlement.techtree.effects.CommonsEffects
                    .guardRecoverFraction(settler, GuardRecoveryPolicy.ENTER_FRACTION);
        }
        if (!needed || !hasRecoveryMeal()) {
            settler.getPersistentData().remove(ACTIVE_KEY);
            return false;
        }
        return true;
    }

    /**
     * Emergency recovery is an exchange for an actual owned meal. Without one
     * to consume, clearing the Guard's target only turns an injured defender
     * into a fleeing target; combat must retain authority until supplies exist.
     */
    private boolean hasRecoveryMeal() {
        if (settler.hasMeal()) return true;
        var hearth = settler.hearth();
        BlockPos hearthPos = settler.getHearthPos();
        if (!(settler.level() instanceof ServerLevel level) || hearth == null
                || hearthPos == null || !level.hasChunkAt(hearthPos)
                || !settler.settlement().id.equals(hearth.getSettlementId())) {
            return false;
        }
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            var stack = hearth.getInventory().getStackInSlot(slot);
            if (com.hearthstead.settlement.ReadyFood.isReadyMeal(stack)) return true;
        }
        return false;
    }

    /** A reversible readiness task, never authority to ignore a player's defensive order. */
    private boolean peaceReady() {
        if (!(settler.level() instanceof ServerLevel level) || settler.hasTavernSeat()
            || settler.getHealth() >= settler.getMaxHealth() || settler.hurtTime > 0 || settler.isOnFire()
            || settler.getTarget() != null || settler.settlement().alertActive(level.getGameTime())
            || GuardRecoveryPolicy.shouldRecover(settler.getHealth(), settler.getMaxHealth(),
                settler.getPersistentData().getBoolean(ACTIVE_KEY))) return false;
        var order = com.hearthstead.settlement.guard.GuardAssignmentService.validate(level,
            settler.settlement(), settler, false);
        if (order.valid() && order.order().orElseThrow().modeAt(level.getGameTime())
                != com.hearthstead.settlement.state.GuardOrder.Mode.NONE) return false;
        var hearth = settler.hearth();
        BlockPos pos = settler.getHearthPos();
        if (hearth == null || pos == null || !level.hasChunkAt(pos)
                || !settler.settlement().id.equals(hearth.getSettlementId())) return false;
        var danger = settler.getBoundingBox().inflate(SAFE_RADIUS)
            .minmax(new net.minecraft.world.phys.AABB(pos).inflate(SAFE_RADIUS));
        if (!level.getEntitiesOfClass(Mob.class, danger, enemy -> enemy instanceof Enemy
                && enemy.isAlive() && enemy.canAttack(settler)
                && (enemy.distanceToSqr(settler) <= SAFE_RADIUS * SAFE_RADIUS
                    || enemy.distanceToSqr(Vec3.atCenterOf(pos)) <= SAFE_RADIUS * SAFE_RADIUS)).isEmpty()) return false;
        if (settler.hasMeal()) return true;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            var stack = hearth.getInventory().getStackInSlot(slot);
            if (com.hearthstead.settlement.ReadyFood.isReadyMeal(stack)) return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void start() {
        if (!peacetime) settler.getPersistentData().putBoolean(ACTIVE_KEY, true);
        settler.setTarget(null);
        if (settler.isSleeping()) settler.stopSleeping();
        if (settler.isPassenger()) settler.stopRiding();
        nextPathAt = 0;
        status = Status.RETREATING;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level) || !eligible()) return;
        // Recheck in the actual transaction tick, not only selector scheduling.
        if (peacetime && !peaceReady()) return;
        // Target selection may run separately; MOVE/LOOK priority still prevents
        // another attack goal taking ownership during this recovery episode.
        settler.setTarget(null);
        List<Mob> threats = level.getEntitiesOfClass(Mob.class,
            settler.getBoundingBox().inflate(SAFE_RADIUS), enemy -> enemy instanceof Enemy
                && enemy.isAlive() && enemy.canAttack(settler)
                && settler.hasLineOfSight(enemy));
        if (!threats.isEmpty() || settler.hurtTime > 0 || settler.isOnFire()) {
            retreat(level, threats);
            return; // No meal progress, consumption or healing in danger.
        }
        var hearth = settler.hearth();
        BlockPos hearthPos = settler.getHearthPos();
        if (hearth == null || hearthPos == null || !canContact(level, hearthPos)) {
            retreat(level, threats);
            return;
        }
        settler.getNavigation().stop();
        settler.setSprinting(false);
        if (settler.hasMeal()) {
            status = Status.RECOVERING;
            settler.setActivity(SettlerActivity.EATING);
            var food = settler.mealDisplayCopy().getFoodProperties(settler);
            int nutrition = food == null ? 0 : food.nutrition();
            if (settler.tickMeal()) {
                // ResidentMeal commits its owned item before returning true.
                // Its durable LastAdvance also rejects a duplicate same-tick call.
                float heal = GuardRecoveryPolicy.mealHeal(settler.getHealth(),
                    settler.getMaxHealth(), nutrition, peacetime);
                if (heal > 0) settler.heal(heal);
            }
            return;
        }
        var inventory = hearth.getInventory();
        int best = -1;
        int nutrition = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var live = inventory.getStackInSlot(slot);
            var food = com.hearthstead.settlement.ReadyFood.isReadyMeal(live)
                ? live.getFoodProperties(settler) : null;
            if (food != null && food.nutrition() > nutrition) {
                nutrition = food.nutrition();
                best = slot;
            }
        }
        if (best >= 0) {
            var live = inventory.getStackInSlot(best);
            if (settler.beginMeal(live)) {
                inventory.setStackInSlot(best, live);
                status = Status.RECOVERING;
                settler.setActivity(SettlerActivity.EATING);
                return;
            }
        }
        status = Status.WAITING_FOR_FOOD;
        settler.setActivity(SettlerActivity.RESTING);
    }

    private void retreat(ServerLevel level, List<Mob> threats) {
        status = Status.RETREATING;
        settler.setActivity(peacetime ? SettlerActivity.TRAVELING : SettlerActivity.RETREATING);
        settler.setSprinting(!peacetime);
        if (level.getGameTime() < nextPathAt) return;
        nextPathAt = level.getGameTime() + REPATH_TICKS;
        BlockPos hearth = settler.getHearthPos();
        if (hearth != null) {
            for (int[] offset : new int[][]{{1,0},{-1,0},{0,1},{0,-1},
                    {1,1},{1,-1},{-1,1},{-1,-1}}) {
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos candidate = hearth.offset(offset[0], dy, offset[1]);
                    if (navigate(level, Vec3.atBottomCenterOf(candidate), threats)) return;
                }
            }
        }
        // A besieged Hearth is not inherently safe. Use ordinary pathfinding
        // away from the closest actual threat instead of eating beside it.
        Mob nearest = threats.stream().min(Comparator.comparingDouble(settler::distanceToSqr))
            .orElse(null);
        if (nearest != null) {
            for (int attempt = 0; attempt < 4; attempt++) {
                Vec3 away = DefaultRandomPos.getPosAway(settler, 10, 4, nearest.position());
                if (away != null && navigate(level, away, threats)) return;
            }
        }
        settler.getNavigation().stop();
        settler.setSprinting(false);
        status = Status.PATH_BLOCKED;
    }

    private boolean navigate(ServerLevel level, Vec3 destination, List<Mob> threats) {
        BlockPos feet = BlockPos.containing(destination);
        Settlement settlement = settler.settlement();
        if (settlement == null || !level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())
            || feet.distSqr(settlement.center) > (settlement.radius + 8.0) * (settlement.radius + 8.0)
            || !level.getFluidState(feet).isEmpty()
            || level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
            || !level.noCollision(settler, settler.getBoundingBox().move(destination.subtract(settler.position())))) {
            return false;
        }
        for (Mob threat : threats) {
            if (threat.distanceToSqr(destination) < SAFE_RADIUS * SAFE_RADIUS) return false;
        }
        Path path = settler.getNavigation().createPath(feet, 0);
        if (path == null || !path.canReach()) return false;
        // Do not route through the attacker to reach a nominally safe endpoint.
        for (int i = 0; i < path.getNodeCount(); i++) {
            Vec3 node = Vec3.atBottomCenterOf(path.getNode(i).asBlockPos());
            for (Mob threat : threats) {
                double minimum = Math.min(3.0, Math.max(0, settler.distanceTo(threat) - 0.5));
                if (threat.distanceToSqr(node) < minimum * minimum) return false;
            }
        }
        return settler.getNavigation().moveTo(path, peacetime ? 0.9 : RUN_SPEED);
    }

    private boolean canContact(ServerLevel level, BlockPos hearth) {
        if (!level.hasChunkAt(hearth) || settler.blockPosition().distSqr(hearth) > 6.25) return false;
        Vec3 eye = settler.getEyePosition();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(eye), hearth)) {
            if (!level.hasChunkAt(cell)) return false;
        }
        var hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(hearth),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, settler));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(hearth);
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.setSprinting(false);
        settler.setActivity(SettlerActivity.IDLE);
        status = Status.INACTIVE;
        // Intent stays across interruptions; ResidentMeal retains exact cargo.
        // canUse clears intent only at recovery threshold or lost Guard authority.
    }
}

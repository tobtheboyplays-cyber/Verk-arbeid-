package com.hearthstead.entity.ai;

import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * The watchtower archer: hold the ring, keep the distance, and make every
 * arrow one the fletcher actually made.
 *
 * <h2>Chest-true arrows — the fletcher finally has a consumer</h2>
 *
 * <p>FLOWS.md's table has carried the edge "fletcher → barracks/watchtower"
 * since it was written, and until this goal nothing on the receiving end
 * consumed a single arrow. Now it is literal: the archer's quiver is
 * restocked, item for item, from the WATCHTOWER's own chests
 * ({@link WarehouseIndex#containers} over the building's scanned bounds), a
 * fired arrow is a real {@link AbstractArrow} entity in the world, and an
 * empty tower means an archer who cannot shoot — which is precisely the
 * pressure that makes hiring a fletcher matter. Every arrow is conserved
 * exactly: chest → quiver on restock, quiver → world on release, quiver →
 * chest again when the archer stands down with shafts unspent.
 *
 * <p>Fired arrows are spawned with {@code pickup = DISALLOWED}, the way
 * vanilla skeleton arrows are. That is the <b>sanctioned consumption
 * sink</b>: the martial chain is the economy's one set of goods that is
 * allowed to be genuinely used up (FLOWS.md, acyclicity — "arms decay
 * through wear"), and a spent shaft in the grass that could be picked back
 * up would quietly turn the tower into an arrow fountain.
 *
 * <h2>Kiting</h2>
 *
 * <p>An archer is not a guard with a longer sword: they prefer the
 * {@value #PREFER_MIN}–{@value #PREFER_MAX} block ring, close the gap only
 * when the target is beyond it, and back away the moment anything comes
 * inside {@value #BACK_AWAY_UNDER} — holding still to draw only while the
 * ring holds.
 *
 * <h2>What is deliberately mirrored from the guard trade</h2>
 *
 * <ul>
 *   <li>Rank is earned by doing ({@link ArcherRank}, on DEXTERITY, trained
 *       per volley and per arrow that strikes — never on a timer).
 *   <li>Effort is spent (1 per volley-cycle) but <b>never gates combat</b>,
 *       the same rule {@code GuardPatrolGoal} documents: a spent archer
 *       still defends. Safety beats bookkeeping.
 *   <li>Target acquisition uses the same predicate as
 *       {@link SettlerDefenseTargetGoal} — hostiles inside the settlement
 *       ring. It is duplicated here only because that goal's own
 *       {@code canUse} gates on {@link Profession#GUARD} and widening it is
 *       outside this slice's ownership; the two should merge into one
 *       militia targeting goal when it next opens.
 * </ul>
 */
public class ArcherAttackGoal extends Goal {

    /** Arrows the archer carries at once. Small on purpose: the quiver is a
     *  handful borrowed from the tower rack, not a second warehouse. */
    public static final int QUIVER_SIZE = 16;
    /** How far from the tower's room a restock (or return) still counts as
     *  "at the rack". */
    private static final int RESTOCK_REACH = 8;

    /** The preferred fighting ring, in blocks. */
    public static final double PREFER_MIN = 8.0;
    public static final double PREFER_MAX = 16.0;
    /** Inside this, stop drawing and open the distance. */
    public static final double BACK_AWAY_UNDER = 6.0;
    /** Beyond this no arrow is loosed at all, even mid-draw. */
    private static final double MAX_SHOT_RANGE = 18.0;

    /** Ticks of steady aim before an ordinary volley releases. */
    private static final int ORDINARY_DRAW_TICKS = 20;
    /** Ticks of rest between volleys, so the cycle reads as aim-loose-lower
     *  rather than a turret. One full ordinary cycle is ~35 ticks. */
    private static final int VOLLEY_RECOVERY_TICKS = 15;

    /** Re-scan interval for self-acquisition, mirroring the 10-tick
     *  randomInterval {@link SettlerDefenseTargetGoal} passes to vanilla —
     *  one bounded AABB query per interval, never per tick (budgeted). */
    private static final int RETARGET_INTERVAL = 10;

    /** How far the "out of arrows" line reaches, in blocks -- narrower than
     *  {@code RaidBroadcast}'s settlement-wide radius+32 on purpose: this is
     *  a post-specific complaint ("this tower is empty"), not settlement
     *  news, so it should only reach someone standing near enough to have
     *  noticed the archer in the first place. See class doc "Starving
     *  speaks". */
    private static final double ANNOUNCE_RANGE = 12.0;

    private final SettlerEntity settler;

    /** Arrows in hand. Chest-true: every increment came out of a tower
     *  chest, every decrement is an arrow entity in the world or a shaft
     *  put back in the rack. */
    private int quiver;
    private int drawTicks;
    private int recoverTicks;
    private int retargetIn;
    /** Decided at the moment a draw begins, so the long pause telegraphs
     *  the Power Shot before it exists. */
    private boolean drawingPowerShot;
    private boolean drawingTripleShot;
    /** True for the span of ONE continuous starvation episode: set the
     *  first tick a live target goes unshot for want of arrows, cleared the
     *  moment the rack has arrows again (restock succeeds) or this goal
     *  stops. Gates the player-facing line to once per episode rather than
     *  once per tick -- see {@link #reportOutOfAmmo}. */
    private boolean outOfAmmoAnnounced;

    // Test seams (ArcherGameTests): cadence and ammo are asserted through
    // these, because "design for testability" beats poking at private state.
    private int shotsFired;
    private int powerShotsFired;
    // ACCEPT-JOBS audit (2026-08-26): Triple Shot had zero test coverage --
    // only powerShotsFired existed as a seam, so nothing could tell a MASTER
    // archer's 5th-volley fan apart from an ordinary shot without reaching
    // into private state. Same seam shape as powerShotsFired, one cycle
    // later.
    private int tripleShotsFired;

    public ArcherAttackGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Draw timing is the whole feel of the trade; counting it in the
        // selector's 2-tick steps would make the Power Shot pause mushy.
        return true;
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.ARCHER) {
            return false;
        }
        LivingEntity target = settler.getTarget();
        if (target != null && target.isAlive()) {
            return true;
        }
        if (--retargetIn > 0) {
            return false;
        }
        retargetIn = RETARGET_INTERVAL;
        LivingEntity acquired = acquire();
        if (acquired == null) {
            return false;
        }
        settler.setTarget(acquired);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = settler.getTarget();
        return settler.getProfession() == Profession.ARCHER
            && target != null && target.isAlive();
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.COMBAT);
        drawTicks = 0;
        recoverTicks = 0;
        outOfAmmoAnnounced = false;
        planNextVolley();
    }

    @Override
    public void tick() {
        LivingEntity target = settler.getTarget();
        if (target == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        settler.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (quiver <= 0 && !restock(level)) {
            // No arrows in hand and none reachable: walk to the tower rack.
            // "No arrows in the tower = no shooting" is the honest outcome —
            // the archer stands their post empty-handed rather than the
            // arrows appearing from nowhere. But standing there silently is
            // NOT honest -- see class doc "Starving speaks" (owner's bug
            // report, 2026-08-26: a hired archer facing zombies "did
            // nothing" with no signal why). Say so instead.
            reportOutOfAmmo(level);
            walkTowardsTower(level);
            drawTicks = 0;
            return;
        }
        if (outOfAmmoAnnounced) {
            // The rack has arrows again: this starvation episode is over.
            // Clearing the flag here (not just on stop/start) is what makes
            // the NEXT empty spell against the SAME target announce fresh,
            // rather than staying silently "already told them once" for the
            // rest of the fight.
            outOfAmmoAnnounced = false;
            settler.setActivity(SettlerActivity.COMBAT);
        }

        double distance = settler.distanceTo(target);
        if (distance < BACK_AWAY_UNDER) {
            // Kite: open the ring before anything else. The draw is lost --
            // an archer stumbling backwards is not aiming.
            Vec3 away = DefaultRandomPos.getPosAway(settler, 8, 4, target.position());
            if (away != null) {
                settler.getNavigation().moveTo(away.x, away.y, away.z, 1.15);
            }
            drawTicks = 0;
            return;
        }
        if (distance > PREFER_MAX) {
            settler.getNavigation().moveTo(target, 1.05);
            drawTicks = 0;
            return;
        }
        settler.getNavigation().stop();

        if (recoverTicks > 0) {
            recoverTicks--;
            return;
        }
        if (distance > MAX_SHOT_RANGE || !settler.hasLineOfSight(target)) {
            drawTicks = 0;
            return;
        }
        // Holding still, target in the ring and in sight: draw. COMBAT +
        // standing renders GUARD_STANCE, so the long Power Shot draw is a
        // visible held pose (v1 -- the bespoke aim clip is the polish
        // worker's; see the clip request in this slice's report).
        drawTicks++;
        int needed = ORDINARY_DRAW_TICKS
            + (drawingPowerShot ? ArcherRank.POWER_SHOT_DRAW_TICKS : 0);
        if (drawTicks >= needed) {
            loose(level, target);
            drawTicks = 0;
            recoverTicks = VOLLEY_RECOVERY_TICKS;
            planNextVolley();
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        drawTicks = 0;
        outOfAmmoAnnounced = false;
        LivingEntity target = settler.getTarget();
        if (target != null && !target.isAlive()) {
            settler.setTarget(null);
        }
        // Standing down with nothing left to fight: the unspent handful goes
        // back in the rack (exact conservation -- the quiver is borrowed,
        // not owned). While hostiles remain, keep it: mid-raid churn of
        // take-out/put-back between kills would hammer the same chest.
        if (settler.level() instanceof ServerLevel level
            && settler.getTarget() == null && acquire() == null) {
            returnUnspent(level);
        }
    }

    // ------------------------------------------------------------ cadence ---

    /**
     * THE CADENCE, decided here and only here, at the moment a draw begins
     * (so the drawn-long pause telegraphs the Power Shot before it exists).
     * Volleys are counted on {@link #shotsFired}; the NEXT volley is number
     * {@code shotsFired + 1}:
     *
     * <pre>
     *   Power Shot  (SHARPSHOOTER+): every 4th  -> 4, 8, 12, 16, 20, ...
     *   Triple Shot (MASTER):        every 5th  -> 5, 10, 15,     ...
     * </pre>
     *
     * <p>They <b>never stack on the same shot</b>: where both cadences land
     * together (volley 20, 40 — every lcm(4,5) = 20th), the Power Shot wins
     * and the fan simply waits for its next multiple of five. A 2.5× piercing
     * shot that also fanned into three would stop being a special move and
     * start being the only move — the exact failure GuardRank.CLEAVE_SHARE's
     * doc exists to prevent.
     */
    private void planNextVolley() {
        ArcherRank rank = ArcherRank.of(settler);
        int next = shotsFired + 1;
        drawingPowerShot = rank.atLeast(ArcherRank.SHARPSHOOTER)
            && next % ArcherRank.POWER_SHOT_EVERY == 0;
        drawingTripleShot = !drawingPowerShot
            && rank.atLeast(ArcherRank.MASTER)
            && next % ArcherRank.TRIPLE_SHOT_EVERY == 0;
    }

    // ------------------------------------------------------------ loosing ---

    private void loose(ServerLevel level, LivingEntity target) {
        // A thinning quiver fans down honestly: a "triple" with one arrow
        // left is one arrow. Conservation beats spectacle.
        int arrows = drawingTripleShot ? Math.min(3, quiver) : 1;
        if (arrows <= 0) {
            return;
        }
        ArcherRank rank = ArcherRank.of(settler);
        boolean power = drawingPowerShot;
        ItemStack weapon = power ? powerShotWeapon(level) : null;
        float inaccuracy = power ? ArcherRank.POWER_SHOT_INACCURACY
            : rank.atLeast(ArcherRank.MARKSMAN)
                ? ArcherRank.MARKSMAN_INACCURACY : ArcherRank.BASE_INACCURACY;

        for (int i = 0; i < arrows; i++) {
            float yawOffset = arrows == 1 ? 0.0F
                : (i - (arrows - 1) / 2.0F) * ArcherRank.TRIPLE_SHOT_YAW_DEGREES;
            spawnArrow(level, target, weapon, power, rank, inaccuracy, yawOffset);
        }
        quiver -= arrows;

        // The twang. Ordinary volleys are the vanilla arrow loose; the Power
        // Shot is the crossbow's heavier snap pitched far down -- a deeper
        // voice for a heavier shot (v1: pitch-shifted vanilla until the
        // sound pipeline grows a bespoke archer loose).
        if (power) {
            level.playSound(null, settler.blockPosition(), SoundEvents.CROSSBOW_SHOOT,
                SoundSource.NEUTRAL, 1.0F,
                0.65F + settler.getRandom().nextFloat() * 0.06F);
        } else {
            level.playSound(null, settler.blockPosition(), SoundEvents.ARROW_SHOOT,
                SoundSource.NEUTRAL, 1.0F,
                (drawingTripleShot ? 0.9F : 1.0F)
                    / (settler.getRandom().nextFloat() * 0.4F + 0.8F));
        }

        shotsFired++;
        if (power) {
            powerShotsFired++;
        } else if (drawingTripleShot) {
            tripleShotsFired++;
        }
        // One volley loosed is one unit of the trade done: train at the
        // moment the work completes (job standard point 8), spend effort the
        // same way -- and never GATE on effort here; combat is exempt, the
        // same "safety beats bookkeeping" rule GuardPatrolGoal documents.
        settler.train(Attribute.DEXTERITY, ArcherRank.TRAIN_SHOT);
        settler.spendEffort(1);
    }

    private void spawnArrow(ServerLevel level, LivingEntity target,
                            @Nullable ItemStack weapon, boolean power,
                            ArcherRank rank, float inaccuracy, float yawOffsetDeg) {
        SettlerEntity archer = settler;
        // A real arrow entity from a real item (the shaft this very call
        // removed from the quiver's chest-true count). The subclass hook is
        // the per-hit half of training: the arrow itself knows whether it
        // struck, so the hit is counted where it happens and a miss teaches
        // only the loose.
        Arrow arrow = new Arrow(level, archer, new ItemStack(Items.ARROW), weapon) {
            @Override
            protected void doPostHurtEffects(LivingEntity struck) {
                super.doPostHurtEffects(struck);
                if (archer.isAlive()) {
                    archer.train(Attribute.DEXTERITY, ArcherRank.TRAIN_HIT);
                }
            }
        };
        // The sanctioned consumption sink (see class doc): spent shafts are
        // used up, exactly like vanilla skeletons' -- retrievable arrows
        // would turn the tower into an arrow fountain.
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        double mult = 1.0;
        if (rank.atLeast(ArcherRank.MARKSMAN)) {
            mult *= ArcherRank.MARKSMAN_DAMAGE_MULT;
        }
        if (power) {
            // 2.5x the rank's own ordinary shot: the multipliers compose, so
            // a sharpshooter's Power Shot is heavier than a recruit's would
            // ever have been -- experience shoots harder, the same rule as
            // GuardRank.MELEE_EDGE_PER_RANK.
            mult *= ArcherRank.POWER_SHOT_DAMAGE_MULT;
        }
        arrow.setBaseDamage(arrow.getBaseDamage() * mult);

        // The skeleton's own aim math, with the fan rotated in around Y.
        double dx = target.getX() - settler.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - settler.getZ();
        if (yawOffsetDeg != 0.0F) {
            double a = Math.toRadians(yawOffsetDeg);
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            double rx = dx * cos - dz * sin;
            double rz = dx * sin + dz * cos;
            dx = rx;
            dz = rz;
        }
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + flat * 0.2, dz, 1.6F, inaccuracy);
        level.addFreshEntity(arrow);
    }

    /**
     * The transient weapon a Power Shot is "fired from". Punch and Piercing
     * ride the vanilla enchantment pipeline ({@code AbstractArrow} reads the
     * firing weapon's enchantments for knockback and pierce count), so the
     * strong knockback and the pierce-one behave exactly like their vanilla
     * selves -- resistance, deflection and all -- instead of a hand-rolled
     * shove. The stack exists only on the arrow; nothing ever holds it.
     */
    private ItemStack powerShotWeapon(ServerLevel level) {
        Registry<Enchantment> enchantments =
            level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.enchant(enchantments.getHolderOrThrow(Enchantments.PUNCH),
            ArcherRank.POWER_SHOT_PUNCH);
        bow.enchant(enchantments.getHolderOrThrow(Enchantments.PIERCING),
            ArcherRank.POWER_SHOT_PIERCE);
        return bow;
    }

    // ------------------------------------------------------------- quiver ---

    /** The WATCHTOWER that employs this archer, or null. Derived per call
     *  (D-011): employment lives on the building, never cached here. */
    @Nullable
    private Building tower() {
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return null;
        }
        Building employer = Employment.employerOf(settlement, settler.getUUID());
        return employer != null && employer.valid ? employer : null;
    }

    private boolean nearTower(Building tower) {
        if (tower.bounds != null
            && tower.bounds.inflatedBy(RESTOCK_REACH).isInside(settler.blockPosition())) {
            return true;
        }
        return tower.anchor != null
            && settler.blockPosition().closerThan(tower.anchor, RESTOCK_REACH);
    }

    private void walkTowardsTower(ServerLevel level) {
        Building tower = tower();
        if (tower == null || tower.anchor == null || nearTower(tower)) {
            return;
        }
        settler.getNavigation().moveTo(tower.anchor.getX() + 0.5,
            tower.anchor.getY(), tower.anchor.getZ() + 0.5, 1.1);
    }

    /**
     * "No arrows in the tower = no shooting" is the correct call (chest
     * truth) -- but saying nothing about it is the defect the owner's bug
     * report actually named: a hired archer standing at post, target in
     * sight, doing nothing, gives the player zero signal that the mod is
     * behaving correctly rather than being broken.
     *
     * <h2>Starving speaks</h2>
     *
     * <p>Two channels, both bounded to ONE continuous starvation episode by
     * {@link #outOfAmmoAnnounced} (cleared the instant the rack has arrows
     * again, in {@code tick()}, and on {@link #start()}/{@link #stop()}):
     *
     * <ul>
     *   <li>The settler's {@code SettlerActivity} flips to
     *       {@link SettlerActivity#OUT_OF_AMMO} every tick this branch runs
     *       -- cheap and idempotent, so it stays true, not just "was true
     *       once" -- which the nameplate/sheet already render for free
     *       through {@code SettlerActivity#displayName()}
     *       ({@code SettlerRenderer} reads {@code getActivity()} generically,
     *       no per-activity renderer code needed).
     *   <li>A chat line to players near enough to plausibly be watching this
     *       archer ({@link #ANNOUNCE_RANGE}), fired ONCE per episode -- the
     *       same "say it, don't spam it" shape {@code RaidBroadcast} uses
     *       for settlement-wide lines, narrowed here to a post-specific
     *       radius rather than the whole settlement.
     * </ul>
     */
    private void reportOutOfAmmo(ServerLevel level) {
        settler.setActivity(SettlerActivity.OUT_OF_AMMO);
        if (outOfAmmoAnnounced) {
            return;
        }
        outOfAmmoAnnounced = true;
        Component message = Component.translatable("hearthstead.archer.no_arrows");
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(settler) <= ANNOUNCE_RANGE * ANNOUNCE_RANGE) {
                player.displayClientMessage(message, false);
            }
        }
    }

    /**
     * Fills the quiver from the tower's own chests, arrow for arrow.
     *
     * <p>Chest truth: the only source is a container inside the WATCHTOWER's
     * scanned bounds ({@link WarehouseIndex#containers} -- a doubly bounded
     * walk), the only quantity is what was physically removed, and an
     * archer out of reach of the rack gets nothing. This is the consumer
     * end of FLOWS' fletcher edge: arrows leave the economy here.
     *
     * @return whether the quiver now holds anything
     */
    private boolean restock(ServerLevel level) {
        Building tower = tower();
        if (tower == null || !nearTower(tower)) {
            return quiver > 0;
        }
        int need = QUIVER_SIZE - quiver;
        int taken = 0;
        for (BlockPos pos : WarehouseIndex.containers(level, tower)) {
            if (need <= 0) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize() && need > 0; slot++) {
                ItemStack stack = chest.getItem(slot);
                if (!stack.is(Items.ARROW)) {
                    continue;
                }
                int n = Math.min(need, stack.getCount());
                stack.shrink(n);
                if (stack.isEmpty()) {
                    chest.setItem(slot, ItemStack.EMPTY);
                }
                chest.setChanged();
                taken += n;
                need -= n;
            }
        }
        quiver += taken;
        return quiver > 0;
    }

    /** Puts unspent arrows back in the rack -- the reverse of
     *  {@link #restock}, with the same reach rule and the same exactness.
     *  Whatever no chest has room for stays honestly in the quiver. */
    private void returnUnspent(ServerLevel level) {
        Building tower = tower();
        if (quiver <= 0 || tower == null || !nearTower(tower)) {
            return;
        }
        for (BlockPos pos : WarehouseIndex.containers(level, tower)) {
            if (quiver <= 0) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                continue;
            }
            for (int slot = 0; slot < chest.getContainerSize() && quiver > 0; slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty()) {
                    int n = Math.min(quiver, new ItemStack(Items.ARROW).getMaxStackSize());
                    chest.setItem(slot, new ItemStack(Items.ARROW, n));
                    chest.setChanged();
                    quiver -= n;
                } else if (stack.is(Items.ARROW)
                    && stack.getCount() < stack.getMaxStackSize()) {
                    int n = Math.min(quiver, stack.getMaxStackSize() - stack.getCount());
                    stack.grow(n);
                    chest.setChanged();
                    quiver -= n;
                }
            }
        }
    }

    // ---------------------------------------------------------- targeting ---

    /**
     * The same acquisition {@link SettlerDefenseTargetGoal} gives guards --
     * the nearest hostile inside the settlement ring -- duplicated only
     * because that goal's canUse gates on GUARD (see class doc). One bounded
     * AABB query per {@value #RETARGET_INTERVAL} ticks, budgeted.
     */
    @Nullable
    private LivingEntity acquire() {
        Settlement settlement = settler.settlement();
        if (settlement == null || !(settler.level() instanceof ServerLevel level)) {
            return null;
        }
        double range = settlement.radius + 8;
        AABB ring = new AABB(settlement.center).inflate(range);
        Monster nearest = null;
        double best = Double.MAX_VALUE;
        for (Monster monster : level.getEntitiesOfClass(Monster.class, ring)) {
            if (!monster.isAlive() || !settler.canAttack(monster)
                || monster.blockPosition().distSqr(settlement.center) > range * range) {
                continue;
            }
            double d = settler.distanceToSqr(monster);
            if (d < best) {
                best = d;
                nearest = monster;
            }
        }
        return nearest;
    }

    // --------------------------------------------------------- test seams ---

    /** Volleys loosed since this goal was constructed (a triple counts once). */
    public int shotsFired() {
        return shotsFired;
    }

    /** How many of those volleys were Power Shots. */
    public int powerShotsFired() {
        return powerShotsFired;
    }

    /** How many of those volleys were Triple Shots (a 4th-multiple always
     *  wins the slot instead -- see {@link #planNextVolley}). */
    public int tripleShotsFired() {
        return tripleShotsFired;
    }

    /** Arrows currently in hand. */
    public int quiverCount() {
        return quiver;
    }

    /** Whether the current starvation episode (if any) has already sent its
     *  one player-facing line -- see {@link #reportOutOfAmmo}. */
    public boolean outOfAmmoAnnounced() {
        return outOfAmmoAnnounced;
    }
}

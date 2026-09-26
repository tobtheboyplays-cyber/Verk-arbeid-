package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.RoleItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.guard.BannerTeams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * The Rune Mage (plan/BATTLE-ROLES.md §4): stays behind the line, reads the
 * fight, and spends scarce rune charges on Ward, Frost Rune or Firebolt.
 *
 * <p>Every cast is a visible channel (glyphs around the mage, a rune ring at
 * its feet, a cast sound) and damage during the channel interrupts it: the
 * charge is kept, the cooldown is spent. Charges live in
 * {@link RuneCharges}, persisted on the settler, refilled slowly by time or
 * instantly by rune stones carried in the offhand.
 */
public class RuneMageGoal extends Goal {
    public static final String CHARGES_KEY = "HearthsteadRuneCharges";
    private static final double SCAN = 20.0D;

    private final SettlerEntity mage;
    @Nullable private RuneCharges charges;
    @Nullable private RuneSpell channel;
    private long channelStart = Long.MIN_VALUE;
    /** Ticks this goal has actually channelled: the rune completes after
     *  castTicks of the mage's own drawing, never by wall-clock arithmetic. */
    private int channelTicks;
    @Nullable private Vec3 aim;
    private long nextThink = Long.MIN_VALUE;
    private long nextMove = Long.MIN_VALUE;
    private long nextStone = Long.MIN_VALUE;
    private long nextScan = Long.MIN_VALUE;
    private boolean threatSeen;

    // ----- J-08 rune-stone supply (peacetime only) ------------------------
    /** Restock when fewer stones than this are carried. */
    public static final int RESTOCK_BELOW = 2;
    /** Carry up to this many (one stack of 16 fits the offhand). */
    public static final int RESTOCK_TARGET = 8;
    private static final int RESTOCK_TIMEOUT_TICKS = 400;
    private static final int RESTOCK_RETRY_TICKS = 600;
    /** Below this energy the errand waits (RestAtNightGoal forces rest under 12). */
    public static final float RESTOCK_MIN_ENERGY = 20.0F;
    @Nullable private com.hearthstead.settlement.Building restockFrom;
    private long restockUntil = Long.MIN_VALUE;
    private long nextRestockCheck = Long.MIN_VALUE;
    private long nextRestockRepath = Long.MIN_VALUE;

    // ----- deterministic QA seam + evidence ----------------------------
    @Nullable private RuneSpell forced;
    @Nullable private Vec3 forcedAim;
    private final Map<RuneSpell, Integer> released = new EnumMap<>(RuneSpell.class);
    private int interrupted;

    public RuneMageGoal(SettlerEntity mage) {
        this.mage = mage;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean isMage() {
        return RoleCombat.enabled() && mage.getProfession() == Profession.RUNE_MAGE
            && mage.level() instanceof ServerLevel && mage.settlement() != null;
    }

    @Override
    public boolean canUse() {
        if (!isMage()) {
            return false;
        }
        ServerLevel level = (ServerLevel) mage.level();
        long now = level.getGameTime();
        if (forced != null) {
            return true;
        }
        if (now >= nextScan) {
            nextScan = now + 10;
            threatSeen = !enemies(level, SCAN).isEmpty();
        }
        return threatSeen || planRestock(level, now);
    }

    @Override
    public boolean canContinueToUse() {
        return isMage() && (channel != null || forced != null
            || restocking(mage.level().getGameTime())
            || !enemies((ServerLevel) mage.level(), SCAN).isEmpty());
    }

    // ------------------------------------------------------------- supply

    private boolean restocking(long now) {
        return restockFrom != null && now < restockUntil;
    }

    /** Rune stones the mage carries: the offhand plus the bag (players may hand them over). */
    public int stonesCarried() {
        int n = mage.getOffhandItem().is(RoleItems.RUNE_STONE.get()) ? mage.getOffhandItem().getCount() : 0;
        for (int i = 0; i < mage.bag.getContainerSize(); i++) {
            if (mage.bag.getItem(i).is(RoleItems.RUNE_STONE.get())) {
                n += mage.bag.getItem(i).getCount();
            }
        }
        return n;
    }

    /**
     * J-08: nothing used to supply rune stones. With no threat near and few
     * stones left, the mage walks to its Rune Hall (or a Warehouse) chest
     * that holds some, like the Healer's bandage restock.
     */
    private boolean planRestock(ServerLevel level, long now) {
        if (now < nextRestockCheck || stonesCarried() >= RESTOCK_BELOW) {
            return false;
        }
        // Peacetime errand only: never on the sleep shift, asleep or exhausted
        // (this goal outranks RestAtNightGoal; a real threat still fights).
        Settlement home = mage.settlement();
        if (home == null || mage.isSleeping() || mage.getEnergy() < RESTOCK_MIN_ENERGY
            || !com.hearthstead.settlement.Schedule.onWatch(home, mage, mage.dayPhase())) {
            return false;
        }
        nextRestockCheck = now + RESTOCK_RETRY_TICKS;
        ItemStack off = mage.getOffhandItem();
        if (!off.isEmpty() && !off.is(RoleItems.RUNE_STONE.get())) {
            return false; // hands full of something else: never swap unasked
        }
        restockFrom = stoneSource(level, mage.settlement());
        if (restockFrom == null) {
            return false;
        }
        restockUntil = now + RESTOCK_TIMEOUT_TICKS;
        return true;
    }

    @Nullable
    private com.hearthstead.settlement.Building stoneSource(ServerLevel level, @Nullable Settlement settlement) {
        if (settlement == null) {
            return null;
        }
        for (com.hearthstead.building.BuildingType type : new com.hearthstead.building.BuildingType[] {
                com.hearthstead.building.BuildingType.RUNE_HALL, com.hearthstead.building.BuildingType.WAREHOUSE}) {
            for (com.hearthstead.settlement.Building b : settlement.buildings) {
                if (b.valid && b.type == type && b.anchor != null && stonesIn(level, b) > 0) {
                    return b;
                }
            }
        }
        return null;
    }

    private static int stonesIn(ServerLevel level, com.hearthstead.settlement.Building b) {
        int n = 0;
        for (BlockPos pos : com.hearthstead.settlement.warehouse.WarehouseIndex.containers(level, b)) {
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).is(RoleItems.RUNE_STONE.get())) {
                        n += c.getItem(i).getCount();
                    }
                }
            }
        }
        return n;
    }

    private void tickRestock(ServerLevel level, long now) {
        com.hearthstead.settlement.Building source = restockFrom;
        if (source == null || !source.valid || now >= restockUntil) {
            restockFrom = null;
            return;
        }
        mage.setActivity(SettlerActivity.TRAVELING);
        if (!mage.blockPosition().closerThan(source.anchor, 3.0D)) {
            if (now >= nextRestockRepath) {
                nextRestockRepath = now + 20;
                mage.getNavigation().moveTo(source.anchor.getX() + 0.5D, source.anchor.getY(),
                    source.anchor.getZ() + 0.5D, 1.0D);
            }
            return;
        }
        mage.getNavigation().stop();
        withdrawStones(level, source);
        restockFrom = null;
        mage.setActivity(SettlerActivity.IDLE);
    }

    /** Chest-true transfer into the offhand, up to {@link #RESTOCK_TARGET}. */
    private void withdrawStones(ServerLevel level, com.hearthstead.settlement.Building b) {
        for (BlockPos pos : com.hearthstead.settlement.warehouse.WarehouseIndex.containers(level, b)) {
            if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack in = c.getItem(i);
                if (!in.is(RoleItems.RUNE_STONE.get())) {
                    continue;
                }
                ItemStack off = mage.getOffhandItem();
                if (!off.isEmpty() && !ItemStack.isSameItemSameComponents(off, in)) {
                    return;
                }
                int room = Math.min(RESTOCK_TARGET, in.getMaxStackSize()) - (off.isEmpty() ? 0 : off.getCount());
                if (room <= 0) {
                    return;
                }
                ItemStack taken = c.removeItem(i, Math.min(room, in.getCount()));
                if (taken.isEmpty()) {
                    continue;
                }
                c.setChanged();
                if (off.isEmpty()) {
                    mage.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, taken);
                } else {
                    off.grow(taken.getCount());
                }
            }
        }
    }

    @Override
    public void start() {
        mage.setActivity(SettlerActivity.COMBAT);
    }

    @Override
    public void stop() {
        channel = null;
        restockFrom = null;
        castPoseUntil = Long.MIN_VALUE;
        mage.getNavigation().stop();
        mage.setActivity(SettlerActivity.IDLE);
        save();
    }

    public RuneCharges charges() {
        if (charges == null) {
            charges = RuneCharges.load(mage.getPersistentData().getCompound(CHARGES_KEY));
        }
        return charges;
    }

    private void save() {
        if (charges != null) {
            mage.getPersistentData().put(CHARGES_KEY, charges.save());
        }
    }

    /** Cast clips (RUNE_CAST/FROST/WARD) keep their activity this long past the release. */
    static final int CAST_FOLLOW_THROUGH_TICKS = 8;
    private long castPoseUntil = Long.MIN_VALUE;

    static SettlerActivity castActivity(RuneSpell spell) {
        return switch (spell) {
            case FIREBOLT -> SettlerActivity.CAST_FIREBOLT;
            case FROST_RUNE -> SettlerActivity.CAST_FROST;
            case WARD -> SettlerActivity.CAST_WARD;
        };
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) mage.level();
        long now = level.getGameTime();
        RuneCharges c = charges();
        c.update(now);
        if (channel == null && castPoseUntil != Long.MIN_VALUE && now >= castPoseUntil) {
            castPoseUntil = Long.MIN_VALUE;
            mage.setActivity(SettlerActivity.COMBAT);
        }
        inscribeStone(level, now, c);
        if (channel != null) {
            tickChannel(level, now, c);
            return;
        }
        List<LivingEntity> foes = enemies(level, SCAN);
        if (restockFrom != null) {
            if (foes.isEmpty() && forced == null) {
                tickRestock(level, now);
                return;
            }
            restockFrom = null; // a fight comes first
        }
        if (now >= nextMove) {
            nextMove = now + 5;
            position(level, foes);
        }
        if (forced != null) {
            begin(level, forced, forcedAim != null ? forcedAim : mage.position(), now);
            forced = null;
            forcedAim = null;
            return;
        }
        if (now < nextThink) {
            return;
        }
        nextThink = now + 10;
        Settlement settlement = mage.settlement();
        List<LivingEntity> inRange = new ArrayList<>();
        List<double[]> points = new ArrayList<>();
        for (LivingEntity e : foes) {
            if (mage.distanceToSqr(e) <= RuneSpell.FIREBOLT.range() * RuneSpell.FIREBOLT.range()
                && mage.getSensing().hasLineOfSight(e)) {
                inRange.add(e);
                points.add(new double[] {e.getX(), e.getY(), e.getZ()});
            }
        }
        RuneMageBrain.Aim frostAim = RuneMageBrain.bestCluster(points, RuneSpell.FROST_RADIUS);
        RuneMageBrain.Aim fireAim = RuneMageBrain.bestCluster(points, RuneSpell.FIREBOLT_RADIUS);
        LivingEntity charger = charger(inRange);
        List<LivingEntity> allies = alliesInCombat(level, settlement, now);
        float lowest = 1.0F;
        for (LivingEntity a : allies) {
            lowest = Math.min(lowest, a.getHealth() / Math.max(1.0F, a.getMaxHealth()));
        }
        RuneMageBrain.View view = new RuneMageBrain.View(allies.size(), lowest,
            heavyIncoming(foes, allies),
            frostAim == null ? 0 : frostAim.count(), charger != null,
            fireAim == null ? 0 : fireAim.count(), !inRange.isEmpty());
        RuneSpell spell = RuneMageBrain.choose(view, c, now);
        if (spell == null) {
            return;
        }
        Vec3 target = switch (spell) {
            case WARD -> mage.position();
            case FROST_RUNE -> frostAim != null && frostAim.count() >= RuneMageBrain.FROST_MIN_ENEMIES
                ? new Vec3(frostAim.x(), frostAim.y(), frostAim.z())
                : charger != null ? charger.position() : null;
            case FIREBOLT -> fireAim != null ? new Vec3(fireAim.x(), fireAim.y(), fireAim.z()) : null;
        };
        if (target != null) {
            begin(level, spell, target, now);
        }
    }

    // ------------------------------------------------------------ casting

    private void begin(ServerLevel level, RuneSpell spell, Vec3 target, long now) {
        if (!charges().canCast(spell, now)) {
            return;
        }
        channel = spell;
        channelStart = now;
        channelTicks = 0;
        aim = target;
        mage.getNavigation().stop();
        mage.getLookControl().setLookAt(target.x, target.y + 1.0D, target.z);
        castPoseUntil = Long.MIN_VALUE;
        mage.setActivity(castActivity(spell));
        level.playSound(null, mage.blockPosition(), RoleItems.RUNE_CAST_SOUND.get(),
            SoundSource.NEUTRAL, 0.7F, spell == RuneSpell.WARD ? 1.2F : 1.0F);
    }

    private void tickChannel(ServerLevel level, long now, RuneCharges c) {
        RuneSpell spell = channel;
        mage.getNavigation().stop();
        if (aim != null) {
            mage.getLookControl().setLookAt(aim.x, aim.y + 1.0D, aim.z);
        }
        // Damage after the channel began breaks the rune.
        if (RoleWorld.lastHurt(level, mage.getUUID()) > channelStart) {
            c.interrupt(spell, now);
            channel = null;
            interrupted++;
            castPoseUntil = Long.MIN_VALUE;
            mage.setActivity(SettlerActivity.COMBAT);
            save();
            level.sendParticles(ParticleTypes.SMOKE, mage.getX(), mage.getY(1.2D), mage.getZ(),
                8, 0.2D, 0.2D, 0.2D, 0.02D);
            return;
        }
        channelTicks++;
        if (channelTicks % 2 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, mage.getX(), mage.getY(1.4D), mage.getZ(),
                6, 0.5D, 0.4D, 0.5D, 0.6D);
            RoleWorld.ringParticles(level, mage.position(), 1.1D,
                spell == RuneSpell.FIREBOLT ? ParticleTypes.FLAME
                    : spell == RuneSpell.FROST_RUNE ? ParticleTypes.SNOWFLAKE : ParticleTypes.END_ROD,
                10);
        }
        // Focus: -0..20% cast time (plan/ATTRIBUTES.md).
        if (channelTicks < com.hearthstead.entity.AttributeRuntime.drawCast(mage, spell.castTicks())) {
            return;
        }
        channel = null;
        castPoseUntil = now + CAST_FOLLOW_THROUGH_TICKS;
        if (!c.release(spell, now)) {
            save();
            return;
        }
        released.merge(spell, 1, Integer::sum);
        mage.train(Attribute.FOCUS, 2.0F);
        switch (spell) {
            case FIREBOLT -> firebolt(level, aim);
            case WARD -> ward(level, now);
            case FROST_RUNE -> frost(level, aim);
        }
        save();
    }

    private void firebolt(ServerLevel level, Vec3 at) {
        Vec3 from = mage.getEyePosition();
        int steps = (int) Math.max(4, from.distanceTo(at) * 2);
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.lerp(at.add(0, 0.8D, 0), i / (double) steps);
            level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5D, at.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3D, at.z, 30,
            RuneSpell.FIREBOLT_RADIUS * 0.5D, 0.3D, RuneSpell.FIREBOLT_RADIUS * 0.5D, 0.02D);
        level.playSound(null, BlockPos.containing(at), RoleItems.FIREBOLT_SOUND.get(),
            SoundSource.NEUTRAL, 0.9F, 1.0F);
        AABB box = new AABB(at, at).inflate(RuneSpell.FIREBOLT_RADIUS, 2.0D, RuneSpell.FIREBOLT_RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            // Enemies only: never a settler, a player, a pet or a bystander.
            if (!RoleCombat.isAuthorizedHostile(mage, e)) {
                continue;
            }
            double dx = e.getX() - at.x;
            double dz = e.getZ() - at.z;
            float damage = RuneSpell.fireboltDamage(Math.sqrt(dx * dx + dz * dz));
            if (damage > 0.0F && e.hurt(level.damageSources().indirectMagic(mage, mage), damage)) {
                e.igniteForTicks(RuneSpell.FIREBOLT_BURN_TICKS);
            }
        }
    }

    private void ward(ServerLevel level, long now) {
        Settlement settlement = mage.settlement();
        List<LivingEntity> allies = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                mage.getBoundingBox().inflate(RuneSpell.WARD.range(), 3.0D, RuneSpell.WARD.range()))) {
            if (e != mage && RoleCombat.isAlly(settlement, e)
                && mage.distanceToSqr(e) <= RuneSpell.WARD.range() * RuneSpell.WARD.range()) {
                allies.add(e);
            }
        }
        allies.sort(Comparator.comparingDouble(a -> a.getHealth() / Math.max(1.0F, a.getMaxHealth())));
        // Tech tree (High Runes): wards last 6 s instead of 5 s.
        int ticks = com.hearthstead.settlement.techtree.effects.WatchEffects.wardTicks(level, settlement,
            RuneSpell.WARD_DURATION_TICKS, RuneSpell.WARD_DURATION_TICKS_HIGH_RUNES);
        for (int i = 0; i < Math.min(RuneSpell.WARD_MAX_ALLIES, allies.size()); i++) {
            RoleWorld.applyWard(level, allies.get(i), RuneSpell.WARD_ABSORB, ticks);
        }
        RoleWorld.ringParticles(level, mage.position(), RuneSpell.WARD.range(), ParticleTypes.END_ROD, 32);
        level.playSound(null, mage.blockPosition(), RoleItems.WARD_SOUND.get(),
            SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    private void frost(ServerLevel level, Vec3 at) {
        Settlement settlement = mage.settlement();
        if (settlement == null) {
            return;
        }
        RoleWorld.addFrost(level, at, RuneSpell.FROST_LIFETIME_TICKS, settlement);
        RoleWorld.ringParticles(level, at, RuneSpell.FROST_RADIUS, ParticleTypes.SNOWFLAKE, 24);
        level.playSound(null, BlockPos.containing(at), RoleItems.FROST_SOUND.get(),
            SoundSource.NEUTRAL, 0.9F, 1.0F);
    }

    private void inscribeStone(ServerLevel level, long now, RuneCharges c) {
        if (now < nextStone || c.charges() >= RuneCharges.MAX_CHARGES) {
            return;
        }
        ItemStack offhand = mage.getOffhandItem();
        ItemStack stone = offhand.is(RoleItems.RUNE_STONE.get()) ? offhand : ItemStack.EMPTY;
        for (int i = 0; stone.isEmpty() && i < mage.bag.getContainerSize(); i++) {
            if (mage.bag.getItem(i).is(RoleItems.RUNE_STONE.get())) {
                stone = mage.bag.getItem(i); // J-08: stones handed over into the bag count too
            }
        }
        if (stone.isEmpty()) {
            return;
        }
        int used = c.inscribe(1, now);
        if (used > 0) {
            stone.shrink(used);
            mage.bag.setChanged();
            nextStone = now + 40;
            level.sendParticles(ParticleTypes.ENCHANT, mage.getX(), mage.getY(1.0D), mage.getZ(),
                12, 0.3D, 0.3D, 0.3D, 0.5D);
            save();
        }
    }

    // --------------------------------------------------------- positioning

    private void position(ServerLevel level, List<LivingEntity> foes) {
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : foes) {
            double d = mage.distanceToSqr(e);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        if (nearest == null) {
            return;
        }
        double dist = Math.sqrt(best);
        boolean ordered = BannerTeams.active(mage) != null;
        if (dist < RuneMageBrain.KEEP_AWAY) {
            Vec3 away = mage.position().subtract(nearest.position()).normalize().scale(6.0D);
            Vec3 to = mage.position().add(away.x, 0, away.z);
            mage.getNavigation().moveTo(to.x, to.y, to.z, 1.2D);
        } else if (ordered) {
            // FieldOrderGoal owns the walk to the slot; the mage casts from it.
        } else if (dist > RuneMageBrain.PREFERRED_MAX) {
            Vec3 toward = nearest.position().subtract(mage.position()).normalize()
                .scale(dist - RuneMageBrain.PREFERRED_MAX + 1.0D);
            Vec3 to = mage.position().add(toward.x, 0, toward.z);
            mage.getNavigation().moveTo(to.x, to.y, to.z, 1.0D);
        } else {
            mage.getNavigation().stop();
            mage.getLookControl().setLookAt(nearest, 20.0F, 20.0F);
        }
    }

    // ------------------------------------------------------------- vision

    private List<LivingEntity> enemies(ServerLevel level, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                mage.getBoundingBox().inflate(radius, 4.0D, radius))) {
            if (RoleCombat.isAuthorizedHostile(mage, e)) {
                out.add(e);
            }
        }
        return out;
    }

    private List<LivingEntity> alliesInCombat(ServerLevel level, @Nullable Settlement settlement,
                                              long now) {
        List<LivingEntity> out = new ArrayList<>();
        double r = RuneSpell.WARD.range();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                mage.getBoundingBox().inflate(r, 3.0D, r))) {
            if (e == mage || !RoleCombat.isAlly(settlement, e) || mage.distanceToSqr(e) > r * r) {
                continue;
            }
            boolean fighting = e instanceof Player
                || e instanceof Mob mob && mob.getTarget() != null
                || RoleWorld.lastHurt(level, e.getUUID()) > now - 100;
            if (fighting) {
                out.add(e);
            }
        }
        return out;
    }

    private boolean heavyIncoming(List<LivingEntity> foes, List<LivingEntity> allies) {
        for (LivingEntity f : foes) {
            if (f instanceof RaiderEntity raider && raider.ticksUntilHeavyContact() >= 0
                && raider.getTarget() != null && allies.contains(raider.getTarget())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private LivingEntity charger(List<LivingEntity> foes) {
        for (LivingEntity f : foes) {
            Vec3 v = f.getDeltaMovement();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            if (RoleCombatRules.isCharging(speed, RoleCombat.knownCharger(f))) {
                return f;
            }
        }
        return null;
    }

    // --------------------------------------------- test seams / evidence ---

    /** Deterministic QA: cast exactly this spell at {@code at} next tick. */
    public void forceCast(RuneSpell spell, Vec3 at) {
        forced = spell;
        forcedAim = at;
    }

    public int released(RuneSpell spell) {
        return released.getOrDefault(spell, 0);
    }

    public int interrupted() {
        return interrupted;
    }

    @Nullable
    public RuneSpell channelling() {
        return channel;
    }
}

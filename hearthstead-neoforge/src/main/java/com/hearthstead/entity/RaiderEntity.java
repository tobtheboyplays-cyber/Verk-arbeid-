package com.hearthstead.entity;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * A raider: somebody's follower, come for a specific thing.
 *
 * <p>Extends {@link Monster} deliberately — the settlement's existing guard
 * targeting already looks for hostiles inside the settlement radius, so
 * defenders react to raiders without a special case.
 *
 * <p><b>They are not scaled from the player's stat sheet.</b> MineColonies
 * computes raid strength from citizen, building and research totals, and its
 * own dev reply says raiders are meant to be "similar to guards"; players
 * report the result as undifferentiated HP sponges (#11655). Here a raider's
 * strength comes from their captain's menace — a record of what that
 * captain has personally done to this settlement — so the threat grows from
 * its own history rather than mirroring yours.
 */
public class RaiderEntity extends Monster {

    private static final EntityDataAccessor<Boolean> DATA_CAPTAIN =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_MENACE =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_OBJECTIVE =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BYTE);
    /** The telegraph: a scout at the treeline, not a raider assigned to a plan. */
    private static final EntityDataAccessor<Boolean> DATA_SCOUT =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * SAGA v1: whether the captain leading this raid has earned an epithet
     * yet (see {@code com.hearthstead.saga.Captain#hasEpithet}) -- a proven
     * leader wears a visibly different mark than a captain nobody has a
     * story about yet. Meaningless unless {@link #DATA_CAPTAIN} is also
     * true.
     */
    private static final EntityDataAccessor<Boolean> DATA_SAGA_MARKED =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * Which BUILD of raider this is -- the contract between the raid
     * director (who composes a band), the model (who shapes the
     * silhouette) and the renderer (who picks the skin). Orthogonal to
     * {@link #DATA_CAPTAIN} on purpose: a captain is a ROLE, and either
     * build can hold it. Byte-synced ordinal, same idiom as
     * {@link #DATA_OBJECTIVE}.
     */
    private static final EntityDataAccessor<Byte> DATA_VARIANT =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BYTE);

    /**
     * The two builds a band is composed from. SKIRMISHER is the pack --
     * lean, hooded, quick. BRUTE is the door-breaker -- fewer, slower,
     * huge. The enum is deliberately tiny: a variant earns its place here
     * only when it moves differently AND reads differently at a glance
     * from across a plaza; palette swaps do not qualify.
     */
    public enum Variant {
        SKIRMISHER,
        BRUTE;

        static Variant byOrdinal(int ord) {
            Variant[] all = values();
            return all[Math.floorMod(ord, all.length)];
        }
    }

    // -------------------------------------------------- animation states ---
    // Client-side AnimationState + entity-event trigger idiom, mirroring
    // SettlerEntity's EV_*/handleEntityEvent/setupAnimationStates pattern
    // exactly (a distinct byte range per class, not a shared namespace --
    // handleEntityEvent dispatches per entity instance). See RaiderModel
    // and RaiderAnimations for what plays and how the builds differ.

    public static final byte EV_STRIKE = 64;
    public static final byte EV_BREACH_SLAM = 65;
    public static final byte EV_LOOT_SNATCH = 66;

    /** RAIDER_STRIKE -- the ordinary wild swing, both builds. */
    public final AnimationState strikeState = new AnimationState();
    /** BREACH_SLAM -- the BRUTE's door-breaking blow. */
    public final AnimationState breachSlamState = new AnimationState();
    /** LOOT_SNATCH -- the instant a stack actually leaves a chest. */
    public final AnimationState lootSnatchState = new AnimationState();
    /** MENACE_IDLE -- the stationary read; gated purely on not moving. */
    public final AnimationState menaceIdleState = new AnimationState();

    /** Health and damage a captain carries over an ordinary follower. */
    public static final float CAPTAIN_HEALTH_BONUS = 14.0F;
    public static final double CAPTAIN_DAMAGE_BONUS = 2.0;
    /** Ceiling on menace scaling, so a long feud cannot become unwinnable. */
    public static final float MAX_MENACE = 3.0F;

    /**
     * SAGA v1's own "modest, readable" marking, layered on top of the plain
     * captain bonus above: a NAMED captain -- one the settlement's Saga
     * roster actually tracks -- is a little stronger again, and grows with
     * their own record. Deliberately small next to {@link #CAPTAIN_HEALTH_BONUS}:
     * the point is a readable escalation across a long campaign, not a
     * wall on raid one.
     */
    public static final float SAGA_CAPTAIN_HEALTH_BONUS = 8.0F; // +4 hearts
    public static final float SAGA_CAPTAIN_SPEED_BONUS = 0.15F; // +15%
    /** One heart per victory, same cap the task specifies. */
    public static final int SAGA_VICTORY_HEART_CAP = 6;

    /** How much a raider can carry off. Theft is physical (INV: chest truth). */
    public static final int LOOT_SIZE = 6;

    /**
     * What this raider has actually stolen. Real items, taken out of real
     * chests and dropped on death, so a player who kills a laden raider gets
     * the goods back and one who lets them escape genuinely loses them.
     * MineColonies' feature request #113 and #129 are both, at root, asking
     * for a raid that leaves a mark; a counter decrement leaves none.
     */
    public final net.minecraft.world.SimpleContainer loot =
        new net.minecraft.world.SimpleContainer(LOOT_SIZE);

    private UUID captainId;
    private UUID settlementId;
    private BlockPos objectivePos;

    public RaiderEntity(EntityType<? extends RaiderEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            // Raiders open doors and leave them open behind them — the
            // opposite of a settler, and a visible sign the place has been
            // entered.
            nav.setCanOpenDoors(true);
        }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 22.0)
            .add(Attributes.MOVEMENT_SPEED, 0.32)
            .add(Attributes.ATTACK_DAMAGE, 3.0)
            .add(Attributes.ARMOR, 2.0)
            .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, false));
        // SLICE RAIDER-BREACH: priority 1, not 2 -- a raider that cannot make
        // progress must be able to interrupt whichever of the priority-2/3
        // goals below is holding MOVE and start chopping, and the
        // GoalSelector only lets a goal steal a flag from one with a
        // strictly GREATER priority number (WrappedGoal#canBeReplacedBy).
        // Harmless alongside OpenDoorGoal, which holds no flags at all.
        goalSelector.addGoal(1,
            new com.hearthstead.entity.ai.RaiderBreachGoal(this));
        // Above melee: a raid that came for the stores goes for the stores.
        // Fighting is what happens on the way, not the point.
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderLootGoal(this));
        // Mutually exclusive with the loot goal above (objective() can never
        // be KORN on an unassigned scout), so sharing priority 2 is safe.
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderScoutGoal(this));
        // RAIDER-HUNT: the BLOD objective's own goal -- see its class doc.
        // Mutually exclusive with both goals above at the same priority:
        // gated on objective()==BLOD (RaiderLootGoal needs KORN) and
        // !isScout() (RaiderScoutGoal needs isScout()), so exactly one of
        // the three can ever be canUse()==true for a given raider.
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderHuntGoal(this));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // A scout never STARTS a fight -- it is an omen, not a free
        // skirmish (D-A3's telegraph step). It still defends itself: the
        // HurtByTargetGoal above and MeleeAttackGoal below are untouched.
        // KF-027: a raider's violence is scoped to the settlement its raid
        // is against. Caught live: a raider from one GameTest's "Breachholm"
        // raid walked into a different test's arena and murdered its courier
        // mid-haul, and in the product the same unscoped selector would have
        // any passing band aggro NPC neighbour villages (B2) it was never
        // raiding. Retaliation stays universal -- HurtByTargetGoal above:
        // anyone who strikes a raider is fair game, whoever they belong to.
        targetSelector.addGoal(2,
            new NearestAttackableTargetGoal<>(this, SettlerEntity.class, true,
                target -> !isScout() && isMyWar(target)));
        targetSelector.addGoal(3,
            new NearestAttackableTargetGoal<>(this, Player.class, true,
                target -> !isScout()));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CAPTAIN, false);
        builder.define(DATA_MENACE, 1.0F);
        builder.define(DATA_OBJECTIVE, (byte) RaidObjective.BLOD.ordinal());
        builder.define(DATA_SCOUT, false);
        builder.define(DATA_SAGA_MARKED, false);
        builder.define(DATA_VARIANT, (byte) Variant.SKIRMISHER.ordinal());
    }

    public boolean isCaptain() {
        return entityData.get(DATA_CAPTAIN);
    }

    public Variant variant() {
        return Variant.byOrdinal(entityData.get(DATA_VARIANT));
    }

    public void setVariant(Variant variant) {
        entityData.set(DATA_VARIANT, (byte) variant.ordinal());
    }

    /** Whether the captain leading this raid has earned an epithet yet. */
    public boolean isSagaMarked() {
        return entityData.get(DATA_SAGA_MARKED);
    }

    public boolean isScout() {
        return entityData.get(DATA_SCOUT);
    }

    /**
     * Arms this raider as a telegraph scout (D-A3's "Telegraphing"): no
     * captain, no objective, no menace scaling -- it is not part of a raid
     * plan, only an omen that one may be close. See
     * {@link com.hearthstead.settlement.raid.RaidTelegraph#spawnScout} and
     * {@link com.hearthstead.entity.ai.RaiderScoutGoal}.
     */
    public void markScout(UUID settlement) {
        this.settlementId = settlement;
        entityData.set(DATA_SCOUT, true);
    }

    public float menace() {
        return entityData.get(DATA_MENACE);
    }

    public RaidObjective objective() {
        byte id = entityData.get(DATA_OBJECTIVE);
        RaidObjective[] all = RaidObjective.values();
        return id >= 0 && id < all.length ? all[id] : RaidObjective.BLOD;
    }

    public UUID captainId() {
        return captainId;
    }

    public UUID settlementId() {
        return settlementId;
    }

    /**
     * Whether this settler belongs to the settlement this raider's raid is
     * against. An UNBOUND raider (no raid -- hand-spawned, a stray) keeps
     * the old any-settler menace so a bare spawn still bites; a BOUND one
     * ignores other settlements' people entirely. An unbound settler is
     * fair game either way -- raiders are not gentle with strangers.
     *
     * <p>Public so {@link com.hearthstead.entity.ai.RaiderHuntGoal} can scope
     * its own settler scan with the exact same rule the target selector
     * uses above, rather than a second copy that could drift from it.
     */
    public boolean isMyWar(net.minecraft.world.entity.LivingEntity target) {
        if (settlementId == null) {
            return true;
        }
        if (!(target instanceof SettlerEntity settler)) {
            return true;
        }
        UUID theirs = settler.boundOrTargetSettlementId();
        return theirs == null || settlementId.equals(theirs);
    }

    /** Where this raider is headed; the objective decides what that means. */
    public BlockPos objectivePos() {
        return objectivePos;
    }

    public void setObjectivePos(BlockPos pos) {
        this.objectivePos = pos;
    }

    public Settlement settlement() {
        if (settlementId == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        return SettlementManager.byId(server, settlementId);
    }

    /**
     * Arms this raider for a specific raid. Menace scales health and damage
     * together so a feared captain's band hits harder and lasts longer,
     * capped so a long-running feud stays winnable.
     */
    public void assign(UUID captain, UUID settlement, RaidObjective objective,
                       float menace, boolean isCaptain) {
        this.captainId = captain;
        this.settlementId = settlement;
        entityData.set(DATA_OBJECTIVE, (byte) objective.ordinal());
        float scaled = Mth.clamp(menace, 1.0F, MAX_MENACE);
        entityData.set(DATA_MENACE, scaled);
        entityData.set(DATA_CAPTAIN, isCaptain);

        double health = getAttributeBaseValue(Attributes.MAX_HEALTH) * scaled
            + (isCaptain ? CAPTAIN_HEALTH_BONUS : 0.0);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth((float) health);
        double damage = getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
            + (scaled - 1.0F) * 2.0 + (isCaptain ? CAPTAIN_DAMAGE_BONUS : 0.0);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);
    }

    /**
     * SAGA v1: marks this raider as leading the raid under a name the
     * settlement's Saga roster actually tracks, on top of the plain
     * {@link #assign} above -- call only for {@code isCaptain} raiders, and
     * only once a matching {@code com.hearthstead.saga.Captain} exists
     * (see {@code CaptainRoster#find}). Everything here is readable: the
     * name floats over the raider's head (D-A3-3, no hidden stats) and the
     * strength it buys is exactly the captain's own earned record --
     * {@code victories}, already capped by the caller's source, capped
     * again here defensively.
     */
    public void markSagaCaptain(String displayName, int victories) {
        setCustomName(net.minecraft.network.chat.Component.literal(displayName));
        setCustomNameVisible(true);
        int hearts = Mth.clamp(victories, 0, SAGA_VICTORY_HEART_CAP);
        double health = getAttributeBaseValue(Attributes.MAX_HEALTH)
            + SAGA_CAPTAIN_HEALTH_BONUS + hearts * 2.0;
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth((float) health);
        double speed = getAttributeBaseValue(Attributes.MOVEMENT_SPEED)
            * (1.0 + SAGA_CAPTAIN_SPEED_BONUS);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
    }

    /** Overload for a captain who has earned an epithet -- see
     * {@link #isSagaMarked()} and {@code RaiderRenderer}'s third texture
     * tier. */
    public void markSagaCaptain(String displayName, int victories, boolean earnedEpithet) {
        markSagaCaptain(displayName, victories);
        entityData.set(DATA_SAGA_MARKED, earnedEpithet);
    }

    /** Raiders never turn on each other, however the melee goes. */
    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof RaiderEntity) && super.canAttack(target);
    }

    // ------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            setupRaiderAnimationStates();
        }
    }

    /** Client-side AnimationState gating + one-shot expiry -- the same
     * animateWhen idiom {@code SettlerEntity.setupAnimationStates()} uses,
     * scaled down to what a raider actually needs. */
    private void setupRaiderAnimationStates() {
        boolean moving = walkAnimation.speed() > 0.05F;
        // MENACE_IDLE is the stationary read: rolling shoulders, head
        // hunting side to side. Every raider gets it while stopped -- pack
        // and captain, brute and skirmisher, and the telegraph scout at the
        // treeline (RaidTelegraph#spawnScout just stands watching, which is
        // already !moving) -- because a raider that is not moving is never
        // merely waiting, it is looking for the opening. No profession- or
        // variant-specific condition, unlike the settler's own idle gates.
        menaceIdleState.animateWhen(!moving, tickCount);

        // One-shots expire on their own clock (same idiom as SettlerEntity).
        // Lengths match each clip's own catalogue duration (§23).
        if (strikeState.isStarted() && strikeState.getAccumulatedTime() > 550L) {
            strikeState.stop();
        }
        if (breachSlamState.isStarted() && breachSlamState.getAccumulatedTime() > 1500L) {
            breachSlamState.stop();
        }
        if (lootSnatchState.isStarted() && lootSnatchState.getAccumulatedTime() > 700L) {
            lootSnatchState.stop();
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EV_STRIKE) {
            strikeState.start(tickCount);
        } else if (id == EV_BREACH_SLAM) {
            breachSlamState.start(tickCount);
        } else if (id == EV_LOOT_SNATCH) {
            lootSnatchState.start(tickCount);
        } else {
            super.handleEntityEvent(id);
        }
    }

    /**
     * Fired from {@link com.hearthstead.entity.ai.RaiderBreachGoal} the
     * instant a door or wall actually gives way, so the scar and this
     * clip's playback start the same tick (see {@code RaiderAnimations}'s
     * header for why the clip's own internal impact keyframe still lands a
     * few ticks later -- the same shape as {@code MELEE}'s own precedent).
     * A BRUTE gets its own huge door-breaking blow ({@code BREACH_SLAM}); a
     * SKIRMISHER breaching reuses the ordinary swing ({@code RAIDER_STRIKE})
     * -- the pack build was never given a signature demolition clip, only
     * the door-breaker was.
     */
    public void triggerBreach() {
        if (level().isClientSide) {
            return;
        }
        if (variant() == Variant.BRUTE) {
            level().broadcastEntityEvent(this, EV_BREACH_SLAM);
        } else {
            level().broadcastEntityEvent(this, EV_STRIKE);
        }
    }

    /** Fired from {@link com.hearthstead.entity.ai.RaiderLootGoal} the
     * instant a stack actually leaves the chest. */
    public void triggerLootSnatch() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_LOOT_SNATCH);
        }
    }

    /**
     * Tallies a landed hit on a settler for the morning defense report
     * (D-A3-8 / the task's "Aftermath"). Scoped to a LIVE raid on purpose
     * ({@code settlement.pendingRaid != null}): a scout that gets attacked
     * and fights back (see {@link com.hearthstead.entity.ai.RaiderScoutGoal})
     * must never inflate a report for a raid that never actually happened.
     */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        // RAIDER_STRIKE's trigger, mirroring SettlerEntity's own EV_MELEE
        // broadcast on doHurtTarget exactly: unconditional, before the
        // outcome is known -- MeleeAttackGoal only calls this once the
        // target is already in reach, so this is genuinely "the swing", not
        // a speculative check.
        level().broadcastEntityEvent(this, EV_STRIKE);
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof SettlerEntity
            && level() instanceof ServerLevel server) {
            Settlement s = settlement();
            if (s != null && s.pendingRaid != null) {
                s.raidSettlersHurtTonight++;
                SettlementSavedData.get(server).setDirty();
            }
        }
        return hit;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // a raid that despawns is a raid that never happened
    }

    /**
     * SAGA v1: tells the settlement its captain fell here, so
     * {@code RaidDirector#recordAftermath} can retire them permanently and
     * raise a lieutenant in their place -- the task's "a captain KILLED
     * during a raid is dead permanently".
     *
     * <p>Deliberately NOT triggered by {@link com.hearthstead.entity.ai.RaiderLootGoal}'s
     * successful withdrawal, which calls {@code discard()} directly rather
     * than dying: an escaped captain is alive and richer for it, not slain.
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        super.die(cause);
        if (isCaptain() && level() instanceof ServerLevel server) {
            Settlement s = settlement();
            if (s != null && s.pendingRaid != null && captainId != null) {
                s.raidCaptainSlainId = captainId;
                SettlementSavedData.get(server).setDirty();
            }
        }
    }

    /** Total items carried off. Zero means the raid took nothing. */
    public int lootCount() {
        int n = 0;
        for (int i = 0; i < loot.getContainerSize(); i++) {
            n += loot.getItem(i).getCount();
        }
        return n;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level,
                                       net.minecraft.world.damagesource.DamageSource source,
                                       boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        // Kill a laden raider and the goods come back. This is the other half
        // of theft being real: it can be undone by fighting for it.
        for (int i = 0; i < loot.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = loot.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack);
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Loot", loot.createTag(registryAccess()));
        if (captainId != null) {
            tag.putUUID("CaptainId", captainId);
        }
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
        if (objectivePos != null) {
            tag.put("ObjectivePos", NbtUtils.writeBlockPos(objectivePos));
        }
        tag.putBoolean("Captain", isCaptain());
        tag.putFloat("Menace", menace());
        tag.putByte("Objective", (byte) objective().ordinal());
        tag.putBoolean("Scout", isScout());
        tag.putBoolean("SagaMarked", isSagaMarked());
        tag.putByte("Variant", entityData.get(DATA_VARIANT));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loot.fromTag(tag.getList("Loot", 10), registryAccess());
        captainId = tag.hasUUID("CaptainId") ? tag.getUUID("CaptainId") : null;
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        objectivePos = tag.contains("ObjectivePos")
            ? NbtUtils.readBlockPos(tag, "ObjectivePos").orElse(null) : null;
        entityData.set(DATA_CAPTAIN, tag.getBoolean("Captain"));
        entityData.set(DATA_MENACE, Mth.clamp(tag.getFloat("Menace"), 1.0F, MAX_MENACE));
        entityData.set(DATA_OBJECTIVE, tag.getByte("Objective"));
        // Absent on an older save (before scouts existed); default false is
        // exactly right -- an old raider was never a scout.
        entityData.set(DATA_SCOUT, tag.getBoolean("Scout"));
        // Absent on an older save (before Saga existed); default false is
        // exactly right -- an old captain never earned an epithet.
        entityData.set(DATA_SAGA_MARKED, tag.getBoolean("SagaMarked"));
        entityData.set(DATA_VARIANT, (byte) Variant.byOrdinal(tag.getByte("Variant")).ordinal());
    }
}

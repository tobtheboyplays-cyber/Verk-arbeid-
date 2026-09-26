package com.hearthstead.entity;

import com.hearthstead.registry.ModDamageTypes;
import com.hearthstead.entity.combat.CinematicOpportunity;
import com.hearthstead.entity.combat.RaiderMove;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.item.PoopStickItem;
import com.hearthstead.event.GoblinThiefDemo;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.CaptainRallyRules;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
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

    public enum RallyPhase {
        READY, CHANNELING, EMPOWERED, EXPOSED, FINAL_STAND, SPENT;

        static RallyPhase byOrdinal(int value) {
            RallyPhase[] phases = values();
            return value >= 0 && value < phases.length ? phases[value] : READY;
        }
    }

    public static final int RALLY_CHANNEL_TICKS = 60;
    public static final int RALLY_BUFF_TICKS = 200;
    public static final int RALLY_EXPOSED_TICKS = 100;
    public static final int RALLY_FINAL_STAND_TICKS = 160;
    private RallyPhase rallyPhase = RallyPhase.READY;
    private long rallyDeadline;
    private boolean finalStandUsed;
    /** Runtime-only cinematic invitation; deliberately absent from Raider NBT. */
    private CinematicOpportunity cinematicOpportunity;
    private long cinematicCooldownUntil;
    private boolean cinematicFinisherImpact;
    /** Transient ticketed-melee authority (light, club, heavy). Never saved. */
    private final MeleeContactLedger bruteClubContacts = new MeleeContactLedger();
    /** Suppresses the legacy immediate swing event during an accepted ticketed contact. */
    private boolean committingBruteClubContact;
    /** The registered bandit-morale goal (read-only QA seam). */
    private com.hearthstead.entity.ai.RaiderMoraleGoal moraleGoal;
    /** The registered hit-and-run goal (read-only QA seam). */
    private com.hearthstead.entity.ai.RaiderSkirmishGoal skirmishGoal;
    /** Runtime-only move bound to the one live melee ticket. */
    private RaiderMove pendingMove;
    private long pendingMoveTicket;
    private long pendingMoveContactTick = Long.MIN_VALUE;
    private UUID pendingMoveTargetId;
    /** Damage multiplier of the move whose contact is being committed. */
    private double committingMoveDamageMultiplier = 1.0D;
    /** Server game time until which a guard heavy/bash keeps this raider staggered. */
    private long staggerUntil = Long.MIN_VALUE;
    /** Server game time until which a whiffed crushing blow leaves it open. */
    private long whiffRecoverUntil = Long.MIN_VALUE;

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
     * Whether this raider is closing on a live quarry right now -- the one
     * fact the CLIENT needs to pick its gait, and the reason this exists as
     * synced data at all.
     *
     * <p>{@code RaiderModel} used to read {@code entity.getTarget()} directly
     * to decide between SPRINT and STALK. Vanilla's {@code Mob.target} is
     * server-only AI state and is never networked on its own, so on the
     * client render copy it is ALWAYS null: {@code sprinting} was always
     * false and SPRINT was unreachable by construction -- every skirmisher
     * crept at the player at walking pace, mid-charge, through the kill.
     * Confirmed on film 2026-08-26 (take-09-raider-sprint-charge) before
     * this field existed.
     *
     * <p>The server therefore publishes the boolean the renderer actually
     * needs, rather than the renderer guessing from state it cannot see.
     * Boolean and not the target's id on purpose: the gait depends only on
     * WHETHER there is live quarry, never on which, so this syncs the
     * smallest fact that answers the question.
     */
    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Boolean> DATA_GOBLIN_THIEF =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_GOBLIN_STAGE =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_GOBLIN_STAGE_STARTED =
        SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.LONG);


    /**
     * The two builds a band is composed from. SKIRMISHER is the pack --
     * lean, hooded, quick. BRUTE is the door-breaker -- fewer, slower,
     * huge. The enum is deliberately tiny: a variant earns its place here
     * only when it moves differently AND reads differently at a glance
     * from across a plaza; palette swaps do not qualify.
     */
    public enum Variant {
        SKIRMISHER,
        BRUTE,
        /**
         * Owner request 26 Sep: human outlaws for the early raids (raid 1 is
         * bandits only, see RaidEscalation). Normal human size, a light
         * weapon, no special attacks. Appended last: the ordinal is saved.
         */
        BANDIT;

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
    public static final byte EV_CINEMATIC_EXPOSED = 67;
    public static final byte EV_CINEMATIC_STAGGER = 68;
    public static final byte EV_CINEMATIC_CLEAR = 69;
    /** BRUTE_CLUB_STRIKE starts at wind-up, before its server contact. */
    public static final byte EV_BRUTE_CLUB_STRIKE = 70;
    /** RAIDER_HEAVY: the telegraphed two-handed smash (RaiderMove.HEAVY). */
    public static final byte EV_RAIDER_HEAVY = 71;
    /** RAIDER_LIGHT: the ticketed ordinary slash (RaiderMove.LIGHT). RAIDER_STRIKE
     *  remains the breach/legacy swing on EV_STRIKE. */
    public static final byte EV_RAIDER_LIGHT = 72;
    /** Skirmisher hit-and-run presentation (server applies the motion). */
    public static final byte EV_RAIDER_HOP_BACK = 73;
    public static final byte EV_RAIDER_DODGE_LEFT = 74;
    public static final byte EV_RAIDER_DODGE_RIGHT = 75;
    public static final byte EV_RAIDER_TAUNT = 76;
    /** Brute ground-slam camera cues (no clip): heavy and club magnitude. */
    public static final byte EV_GROUND_SLAM_HEAVY = 77;
    public static final byte EV_GROUND_SLAM_CLUB = 78;

    /**
     * Client-only presentation hook for slam / footstep camera shake. Common
     * code never references a client class; the client assigns this from its
     * own event subscriber (client/CombatCameraShake).
     */
    @FunctionalInterface
    public interface ImpactCue {
        void play(RaiderEntity source, double x, double y, double z,
                  float magnitude, double radius);
    }

    public static volatile ImpactCue clientImpactCue;
    /** Close-range footstep tremor of a walking Brute (client only). */
    public static final float BRUTE_FOOTSTEP_SHAKE = 0.07F;
    public static final double BRUTE_FOOTSTEP_RADIUS = 7.0D;

    /** RAIDER_STRIKE -- the ordinary wild swing, both builds. */
    public final AnimationState strikeState = new AnimationState();
    /** BREACH_SLAM -- the BRUTE's door-breaking blow. */
    public final AnimationState breachSlamState = new AnimationState();
    /** LOOT_SNATCH -- the instant a stack actually leaves a chest. */
    public final AnimationState lootSnatchState = new AnimationState();
    public final AnimationState cinematicExposedState = new AnimationState();
    public final AnimationState cinematicStaggerState = new AnimationState();
    /** The BRUTE-only melee one-shot; BREACH_SLAM remains block-only. */
    public final AnimationState bruteClubStrikeState = new AnimationState();
    /** RAIDER_HEAVY one-shot; guards read its wind-up and bash or step back. */
    public final AnimationState raiderHeavyState = new AnimationState();
    /** RAIDER_LIGHT one-shot (0.55 s, contact 0.25 s). */
    public final AnimationState raiderLightState = new AnimationState();
    /** Skirmisher hit-and-run one-shots. */
    public final AnimationState raiderHopBackState = new AnimationState();
    public final AnimationState raiderDodgeLeftState = new AnimationState();
    public final AnimationState raiderDodgeRightState = new AnimationState();
    public final AnimationState raiderTauntState = new AnimationState();
    /** Client-only footstep phase for the Brute's close-range tremor. */
    private int bruteFootstepIndex = Integer.MIN_VALUE;
    /** MENACE_IDLE -- the stationary read; gated purely on not moving. */
    public final AnimationState menaceIdleState = new AnimationState();

    /** Health and damage a captain carries over an ordinary follower. */
    public static final float CAPTAIN_HEALTH_BONUS = 14.0F;
    public static final double CAPTAIN_DAMAGE_BONUS = 2.0;
    /**
     * The two ordinary combat silhouettes are also two honest stat profiles.
     * Skirmishers are readable as the fast, fragile pack; Brutes are the slow,
     * heavy line-breaker. Damage and armour deliberately stay equal so the
     * first raid teaches movement and target choice without hiding a sudden
     * damage spike behind a model change.
     */
    // 2026-09-26 owner direction: Skirmishers are small, fast pests; Brutes
    // are slow, crushing and barely pushed by hits. Owner request (26 Sep,
    // later): fights must last long enough to give orders, switch roles,
    // revive and use the finisher, but only "a bit more health". A new Guard
    // with an iron sword hits for about 12.8 net (4 base + 5 sword + 4
    // training edge, minus armour 2), a player's iron sword for about 6:
    // a Skirmisher takes 3 Guard light slashes or about 5 player hits; a
    // Brute about 5 Guard slashes (the Guard's 25% Brute counter included)
    // or about 12 player hits (70, lowered from 110 on 26 Sep so four new
    // Guards win the first raid wave; revisit after the playtest). These are defaults for the [raids] server
    // config (skirmisherBaseHealth, bruteBaseHealth, goblinThiefBaseHealth,
    // enemyHealthMultiplier).
    public static final double SKIRMISHER_MAX_HEALTH = 28.0;
    public static final double SKIRMISHER_MOVEMENT_SPEED = 0.42;
    public static final double SKIRMISHER_KNOCKBACK_RESISTANCE = 0.0;
    /** generic.scale for an ordinary Skirmisher; shrinks the hitbox too. */
    public static final double SKIRMISHER_SCALE = 0.82;
    public static final double BRUTE_MAX_HEALTH = 70.0;
    /** A pickpocket that runs rather than fights: a few blows during the chase. */
    public static final double GOBLIN_THIEF_MAX_HEALTH = 20.0;
    /**
     * Extra health per menace point above 1. Damage still scales with the
     * full menace, but health only half as fast: at the menace cap of 3 a
     * raider has twice its base health, not three times, so a feared
     * captain's Brute stays a focus-fire target rather than a sponge.
     */
    public static final double MENACE_HEALTH_PER_POINT = 0.5;
    public static final double BRUTE_MOVEMENT_SPEED = 0.23;
    /** Bandit defaults (owner request 26 Sep: about 16-18 HP, an ordinary human). */
    public static final double BANDIT_MAX_HEALTH = 17.0;
    public static final double BANDIT_MOVEMENT_SPEED = 0.30;
    public static final double BRUTE_KNOCKBACK_RESISTANCE = 0.90;
    public static final double VARIANT_ATTACK_DAMAGE = 3.0;
    public static final double VARIANT_ARMOR = 2.0;
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

    // Runtime-only Blessing combat cache. None of these values are written to
    // NBT: a reload must never revive or stack a short snare. The building
    // zone is refreshed on a bounded cadence (or immediately when its
    // settlement revision changes), avoiding a spatial lookup every tick.
    private int cachedBlessingZoneRank;
    private long cachedBlessingZoneRevision = Long.MIN_VALUE;
    private long nextBlessingZoneCheckTick = Long.MIN_VALUE;
    private int transientBlessingSnareRank;
    private long transientBlessingSnareUntilTick = Long.MIN_VALUE;

    // A captain's visible identity is raid authority, not cosmetic metadata.
    // Full Entity NBT owns CustomName/CustomNameVisible; these two flags make
    // one bounded post-load comparison against the settlement's canonical
    // roster and preserve a fail-closed decision if that identity was missing,
    // malformed or spoofed. No per-tick roster scan remains after validation.
    private boolean captainIdentityAuthorityCheckPending;
    private boolean captainIdentityQuarantined;
    private boolean captainIdentityQuarantineReported;

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
            .add(Attributes.MAX_HEALTH, SKIRMISHER_MAX_HEALTH)
            .add(Attributes.MOVEMENT_SPEED, SKIRMISHER_MOVEMENT_SPEED)
            .add(Attributes.ATTACK_DAMAGE, VARIANT_ATTACK_DAMAGE)
            .add(Attributes.ARMOR, VARIANT_ARMOR)
            .add(Attributes.KNOCKBACK_RESISTANCE,
                SKIRMISHER_KNOCKBACK_RESISTANCE)
            .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        // Bandit morale outranks everything: a broken band runs (FloatGoal
        // holds only JUMP, so sharing priority 0 is safe).
        moraleGoal = new com.hearthstead.entity.ai.RaiderMoraleGoal(this);
        goalSelector.addGoal(0, moraleGoal);
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
        // RAIDER-ARSON: BRANN's equivalent, same shape, buildings instead of
        // settlers -- see its class doc. All four goals at this priority are
        // mutually exclusive: gated on objective()==BLOD, objective()==BRANN,
        // objective()==KORN (RaiderLootGoal) and isScout() (RaiderScoutGoal)
        // respectively, so exactly one of the four can ever be canUse()==true
        // for a given raider.
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderHuntGoal(this));
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderArsonGoal(this));
        // Ticketed light/club/heavy melee for Brutes and Captains; ordinary
        // Skirmishers instead run the hit-and-run skirmish goal. The two
        // canUse() predicates are disjoint, so sharing priority 3 is safe.
        goalSelector.addGoal(3,
            new com.hearthstead.entity.ai.RaiderMeleeGoal(this, 1.0));
        skirmishGoal = new com.hearthstead.entity.ai.RaiderSkirmishGoal(this);
        goalSelector.addGoal(3, skirmishGoal);
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
        // Same candidates, ranked: defenders first, fleeing civilians last
        // (26 Sep civilian-safety pass, see RaiderSettlerTargetGoal).
        targetSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderSettlerTargetGoal(this,
                target -> !isScout() && isMyWar(target)));
        targetSelector.addGoal(3,
            new NearestAttackableTargetGoal<>(this, Player.class, true,
                target -> !isScout()));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GOBLIN_THIEF, false);
        builder.define(DATA_GOBLIN_STAGE, 0);
        builder.define(DATA_GOBLIN_STAGE_STARTED, 0L);
        builder.define(DATA_CAPTAIN, false);
        builder.define(DATA_MENACE, 1.0F);
        builder.define(DATA_OBJECTIVE, (byte) RaidObjective.BLOD.ordinal());
        builder.define(DATA_SCOUT, false);
        builder.define(DATA_SAGA_MARKED, false);
        builder.define(DATA_VARIANT, (byte) Variant.SKIRMISHER.ordinal());
        builder.define(DATA_CHARGING, false);
    }

    /** Client-readable presentation; authoritative theft state remains in the demo marker. */
    public boolean isGoblinThiefDemo() { return entityData.get(DATA_GOBLIN_THIEF); }
    public int goblinThiefStage() { return entityData.get(DATA_GOBLIN_STAGE); }
    public long goblinThiefStageStartedAt() { return entityData.get(DATA_GOBLIN_STAGE_STARTED); }
    public void setGoblinThiefPresentation(boolean goblin, int stage) {
        setGoblinThiefPresentation(goblin,stage,0L);
    }
    public void setGoblinThiefPresentation(boolean goblin, int stage, long startedAt) {
        entityData.set(DATA_GOBLIN_THIEF, goblin);
        entityData.set(DATA_GOBLIN_STAGE, goblin ? Mth.clamp(stage, 0, 3) : 0);
        entityData.set(DATA_GOBLIN_STAGE_STARTED, goblin ? Math.max(0,startedAt) : 0L);
    }

    @Override protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource source) {
        return isGoblinThiefDemo() ? com.hearthstead.registry.ModSounds.GOBLIN_HURT.get()
            : com.hearthstead.registry.ModSounds.RAIDER_HURT.get();
    }
    @Override protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return isGoblinThiefDemo() ? SoundEvents.FOX_DEATH : com.hearthstead.registry.ModSounds.RAIDER_DEATH.get();
    }
    /** Occasional wordless battle yell; the goblin thief keeps its own frozen voice set. */
    @Override protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return isGoblinThiefDemo() ? null : com.hearthstead.registry.ModSounds.RAIDER_BARK.get();
    }
    @Override public int getAmbientSoundInterval() {
        return 240;
    }
    @Override public float getVoicePitch() {
        return isGoblinThiefDemo() ? 1F : super.getVoicePitch();
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

    /**
     * Full server-side predicate for any ticketed raider melee contact. The
     * goal repeats it before issuing a ticket and at its due tick; the entity
     * repeats it after consuming the ticket, so a caller cannot turn a stale
     * animation into damage. Normal Mob melee range and sight rules apply.
     */
    public boolean isAuthorizedMeleeMoveTarget(LivingEntity target) {
        return level() instanceof ServerLevel level
            && target != null && target.level() == level
            && target.isAlive() && !target.isRemoved()
            && target.canBeSeenAsEnemy() && canAttack(target)
            && isWithinMeleeAttackRange(target)
            && hasLineOfSight(target);
    }

    /** The BRUTE club keeps its historical, variant-bound predicate. */
    public boolean isAuthorizedBruteClubContactTarget(LivingEntity target) {
        return variant() == Variant.BRUTE && isAuthorizedMeleeMoveTarget(target);
    }

    /**
     * Starts one readable wind-up and records one exact target and due tick
     * ({@link RaiderMove#hitTick()} from now). The ledger is transient by
     * design: reloading during a wind-up cannot replay an old contact. A
     * staggered raider cannot begin anything; CLUB is BRUTE-only.
     */
    public long beginMeleeMove(LivingEntity target, RaiderMove move) {
        if (move == null) {
            return MeleeContactLedger.NO_TICKET;
        }
        if (move == RaiderMove.CLUB && variant() != Variant.BRUTE) {
            return MeleeContactLedger.NO_TICKET;
        }
        return beginTicketedMove(target, move, move.hitTick());
    }

    /** Historical seam: the BRUTE club with an explicit contact tick. */
    public long beginBruteClubWindup(LivingEntity target, int contactTick) {
        if (variant() != Variant.BRUTE) {
            return MeleeContactLedger.NO_TICKET;
        }
        return beginTicketedMove(target, RaiderMove.CLUB, contactTick);
    }

    private long beginTicketedMove(LivingEntity target, RaiderMove move, int contactTick) {
        if (!(level() instanceof ServerLevel level)
            || contactTick < 1 || isStaggered()
            || !isAuthorizedMeleeMoveTarget(target)) {
            return MeleeContactLedger.NO_TICKET;
        }
        long due = level.getGameTime() + contactTick;
        long ticket = bruteClubContacts.begin(target.getUUID(), due);
        if (ticket != MeleeContactLedger.NO_TICKET) {
            pendingMove = move;
            pendingMoveTicket = ticket;
            pendingMoveContactTick = due;
            pendingMoveTargetId = target.getUUID();
            level.broadcastEntityEvent(this, switch (move) {
                case LIGHT -> EV_RAIDER_LIGHT;
                case CLUB -> EV_BRUTE_CLUB_STRIKE;
                case HEAVY -> EV_RAIDER_HEAVY;
            });
        }
        return ticket;
    }

    /**
     * Consumes one exact ticket before any damage callback. A failed
     * validation is terminal for that ticket; only an accepted normal
     * {@link #doHurtTarget(Entity)} pass can land one hit. A landed HEAVY
     * adds knockback and staggers a Guard.
     */
    public boolean commitMeleeMove(long ticket, @Nullable LivingEntity target) {
        return commitTicketedMove(ticket, target, false);
    }

    /** Historical seam: identical to {@link #commitMeleeMove} but BRUTE-only. */
    public boolean commitBruteClubContact(long ticket, LivingEntity target) {
        return commitTicketedMove(ticket, target, true);
    }

    private boolean commitTicketedMove(long ticket, @Nullable LivingEntity target,
                                       boolean requireBrute) {
        if (!(level() instanceof ServerLevel level)) {
            return false;
        }
        boolean pending = ticket == pendingMoveTicket;
        RaiderMove move = pending && pendingMove != null ? pendingMove : RaiderMove.CLUB;
        UUID targetId = target != null ? target.getUUID()
            : pending ? pendingMoveTargetId : null;
        MeleeContactLedger.Attempt attempt = bruteClubContacts.consumeAttempt(
            ticket, targetId, level.getGameTime());
        if (pending) {
            clearPendingMove();
        }
        if (attempt == null) {
            return false;
        }
        boolean brute = variant() == Variant.BRUTE;
        boolean hit = false;
        boolean blocked = false;
        if (target != null && isAuthorizedMeleeMoveTarget(target)
            && (!requireBrute || brute)) {
            committingBruteClubContact = true;
            committingMoveDamageMultiplier = move.damageMultiplier();
            try {
                hit = doHurtTarget(target);
                if (hit) {
                    bruteClubContacts.commit(attempt);
                }
            } finally {
                committingBruteClubContact = false;
                committingMoveDamageMultiplier = 1.0D;
            }
            blocked = !hit && target instanceof SettlerEntity settler
                && settler.isBlocking() && settler.hasPhysicalOffhandShield();
            if (hit || blocked) {
                applyBlowConsequences(level, move, target, blocked);
            }
        }
        // A committed crushing blow always reaches the ground, whether or not
        // its main target was still there to take it.
        if (move.crushing() && !hit && !blocked) {
            // A whiffed crushing blow leaves the raider over-committed for its
            // whole authored recovery: the guards' (and finishers') opening.
            whiffRecoverUntil = level.getGameTime() + move.recoveryTicks();
        }
        if (move.slamsGround(brute)) {
            groundSlam(level, move, hit || blocked ? target : null);
        } else if (hit && move == RaiderMove.HEAVY) {
            level.playSound(null, target.blockPosition(),
                com.hearthstead.registry.ModSounds.COMBAT_HEAVY_IMPACT.get(),
                SoundSource.HOSTILE, 0.9F, 0.75F + random.nextFloat() * 0.1F);
        }
        return hit;
    }

    /**
     * Knockback and stagger of a crushing blow on its main target. A guard
     * who caught it on a raised shield takes no damage but is still rocked:
     * reduced knockback and the move's shorter blocked stagger.
     */
    private void applyBlowConsequences(ServerLevel level, RaiderMove move,
                                       LivingEntity target, boolean blocked) {
        if (!move.crushing() || !target.isAlive()) {
            return;
        }
        double push = move.knockback() * (blocked ? RaiderMove.BRACED_SLAM_SCALE : 1.0D);
        if (push > 0.0D) {
            target.knockback(push, getX() - target.getX(), getZ() - target.getZ());
            target.hurtMarked = true;
        }
        if (target instanceof SettlerEntity settler && settler.getProfession().martial()) {
            settler.applyCombatStagger(blocked ? move.blockedStaggerTicks()
                : move.staggerTicks());
        }
    }

    /** Where the club meets the ground: just in front of the Brute's feet. */
    public net.minecraft.world.phys.Vec3 slamPoint() {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double reach = getBbWidth() * 0.5D + 1.0D;
        return new net.minecraft.world.phys.Vec3(getX() - Mth.sin(yaw) * reach,
            getY(), getZ() + Mth.cos(yaw) * reach);
    }

    /**
     * The Brute's ground-slam shockwave, on the blow's contact tick. Block
     * crack, an outward dust ring and debris at the ground point; the
     * heavy_impact sound; a camera-shake cue for nearby players; and a radial
     * push plus a short stagger for every defender (settler or player) in
     * the radius. Other raiders are never touched; the main target already
     * took the blow itself and is skipped; a braced shield softens it.
     */
    private void groundSlam(ServerLevel level, RaiderMove move,
                            @Nullable LivingEntity directTarget) {
        net.minecraft.world.phys.Vec3 point = slamPoint();
        BlockPos ground = BlockPos.containing(point.x, point.y - 0.2D, point.z);
        net.minecraft.world.level.block.state.BlockState groundState =
            level.getBlockState(ground);
        if (groundState.isAir()) {
            ground = ground.below();
            groundState = level.getBlockState(ground);
        }
        double y = point.y + 0.05D;
        if (!groundState.isAir()) {
            net.minecraft.core.particles.BlockParticleOption crack =
                new net.minecraft.core.particles.BlockParticleOption(
                    ParticleTypes.BLOCK, groundState);
            level.sendParticles(crack, point.x, y, point.z,
                move == RaiderMove.HEAVY ? 40 : 26, 0.45D, 0.05D, 0.45D, 0.15D);
            // A few chunks thrown up and out.
            for (int i = 0; i < 6; i++) {
                double a = random.nextDouble() * Math.PI * 2.0D;
                level.sendParticles(crack, point.x, y + 0.1D, point.z, 0,
                    Math.cos(a) * 0.25D, 0.45D + random.nextDouble() * 0.2D,
                    Math.sin(a) * 0.25D, 1.0D);
            }
        }
        // Dust ring spreading outward (count 0 = velocity mode).
        int ring = move == RaiderMove.HEAVY ? 20 : 14;
        double ringSpeed = move == RaiderMove.HEAVY ? 0.22D : 0.16D;
        for (int i = 0; i < ring; i++) {
            double a = Math.PI * 2.0D * i / ring;
            level.sendParticles(ParticleTypes.POOF,
                point.x + Math.cos(a) * 0.3D, y, point.z + Math.sin(a) * 0.3D, 0,
                Math.cos(a), 0.02D, Math.sin(a), ringSpeed);
        }
        if (directTarget != null) {
            level.sendParticles(ParticleTypes.CRIT, directTarget.getX(),
                directTarget.getY(0.6D), directTarget.getZ(), 10,
                0.3D, 0.3D, 0.3D, 0.2D);
        }
        level.playSound(null, point.x, point.y, point.z,
            com.hearthstead.registry.ModSounds.RAIDER_BRUTE_SLAM.get(),
            SoundSource.HOSTILE, move == RaiderMove.HEAVY ? 1.3F : 1.0F,
            (move == RaiderMove.HEAVY ? 0.7F : 0.85F) + random.nextFloat() * 0.08F);
        level.broadcastEntityEvent(this, move == RaiderMove.HEAVY
            ? EV_GROUND_SLAM_HEAVY : EV_GROUND_SLAM_CLUB);

        double radius = move.slamRadius();
        lastSlamAffected = 0;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            point.x - radius, point.y - 1.5D, point.z - radius,
            point.x + radius, point.y + 2.5D, point.z + radius);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (victim == this || victim == directTarget || !victim.isAlive()
                || victim instanceof RaiderEntity
                || !(victim instanceof SettlerEntity || victim instanceof Player)) {
                continue;
            }
            if (victim instanceof Player player
                && (player.isSpectator() || player.isCreative())) {
                continue;
            }
            double dx = victim.getX() - point.x;
            double dz = victim.getZ() - point.z;
            double falloff = RaiderMove.slamFalloff(Math.sqrt(dx * dx + dz * dz), radius);
            if (falloff <= 0.0D) {
                continue;
            }
            boolean braced = victim.isBlocking();
            double scale = falloff * (braced ? RaiderMove.BRACED_SLAM_SCALE : 1.0D);
            if (dx * dx + dz * dz < 1.0E-4D) {
                float yaw = yBodyRot * Mth.DEG_TO_RAD;
                dx = -Mth.sin(yaw);
                dz = Mth.cos(yaw);
            }
            // knockback(strength, x, z) pushes away from (x, z): pass the
            // vector from the victim back to the impact point.
            victim.knockback(move.slamKnockback() * scale, -dx, -dz);
            victim.hurtMarked = true;
            int stagger = (int) Math.ceil(move.slamStaggerTicks()
                * (braced ? 0.5D : 1.0D));
            if (victim instanceof SettlerEntity settler) {
                if (settler.getProfession().martial()) {
                    settler.applyCombatStagger(stagger);
                } else {
                    settler.getNavigation().stop();
                    settler.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN, stagger, 3, false, false));
                }
            }
            if (move.slamSplashDamage() > 0.0D) {
                victim.hurt(damageSources().source(ModDamageTypes.RAIDER_ATTACK, this),
                    (float) (move.slamSplashDamage() * falloff));
            }
            lastSlamAffected++;
        }
    }

    public com.hearthstead.entity.ai.RaiderMoraleGoal moraleGoal() {
        return moraleGoal;
    }

    public com.hearthstead.entity.ai.RaiderSkirmishGoal skirmishGoal() {
        return skirmishGoal;
    }

    /** True for the small hit-and-run build (Captains fight as line raiders). */
    public boolean isSkirmisherPest() {
        return variant() == Variant.SKIRMISHER && !isCaptain();
    }

    /** Test/QA evidence: defenders touched by the most recent slam. */
    private int lastSlamAffected;

    public int lastSlamAffected() {
        return lastSlamAffected;
    }

    /** Skirmisher hop back after a jab; the caller applies the motion. */
    public void presentHopBack() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_RAIDER_HOP_BACK);
        }
    }

    public void presentDodge(boolean left) {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, left ? EV_RAIDER_DODGE_LEFT
                : EV_RAIDER_DODGE_RIGHT);
        }
    }

    public void presentTaunt() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_RAIDER_TAUNT);
        }
    }

    /** Cancels only this exact wind-up; stale goals cannot clear a newer one. */
    public void cancelMeleeMove(long ticket) {
        bruteClubContacts.cancel(ticket);
        if (ticket == pendingMoveTicket) {
            clearPendingMove();
        }
    }

    /** Historical alias of {@link #cancelMeleeMove(long)}. */
    public void cancelBruteClubContact(long ticket) {
        cancelMeleeMove(ticket);
    }

    private void clearPendingMove() {
        pendingMove = null;
        pendingMoveTicket = MeleeContactLedger.NO_TICKET;
        pendingMoveContactTick = Long.MIN_VALUE;
        pendingMoveTargetId = null;
    }

    /** The move bound to a still-live ticket, or null. */
    public RaiderMove pendingMeleeMove() {
        return pendingMoveTicket != MeleeContactLedger.NO_TICKET
            && bruteClubContacts.isActive(pendingMoveTicket) ? pendingMove : null;
    }

    /** True while a HEAVY wind-up is live and its contact is still ahead. */
    public boolean isWindingUpHeavy() {
        return ticksUntilHeavyContact() >= 0;
    }

    /**
     * Ticks until the pending HEAVY contact (0 = this tick), or -1 when no
     * heavy is winding up. Guards read this to bash or step back.
     */
    public int ticksUntilHeavyContact() {
        RaiderMove pendingNow = pendingMeleeMove();
        if (!(level() instanceof ServerLevel level)
            || pendingNow == null || !pendingNow.crushing()) {
            return -1;
        }
        long in = pendingMoveContactTick - level.getGameTime();
        return in < 0L ? -1 : (int) Math.min(in, Integer.MAX_VALUE);
    }

    /**
     * A guard heavy, finisher or shield bash rocks this raider: its pending
     * wind-up is cancelled outright (the old ticket can never deal damage)
     * and it may not begin another swing until the stagger lapses.
     * Runtime-only, reusing the CINEMATIC_STAGGER clip.
     */
    public void stagger(int ticks) {
        if (ticks <= 0 || !(level() instanceof ServerLevel level) || !isAlive()) {
            return;
        }
        if (pendingMoveTicket != MeleeContactLedger.NO_TICKET) {
            cancelMeleeMove(pendingMoveTicket);
        }
        staggerUntil = Math.max(staggerUntil, level.getGameTime() + ticks);
        getNavigation().stop();
        level.broadcastEntityEvent(this, EV_CINEMATIC_STAGGER);
    }

    /**
     * True while the raider recovers from a crushing blow that missed its
     * main target (no clip change: the authored recovery already shows it).
     * Runtime-only; never saved.
     */
    public boolean isRecoveringFromWhiff() {
        return level() instanceof ServerLevel level
            && level.getGameTime() < whiffRecoverUntil;
    }

    /** Cancels whatever wind-up is pending (an external freeze, an execution). */
    public void cancelPendingMeleeMove() {
        if (pendingMoveTicket != MeleeContactLedger.NO_TICKET) {
            cancelMeleeMove(pendingMoveTicket);
        }
    }

    public boolean isStaggered() {
        return level() instanceof ServerLevel level
            && level.getGameTime() < staggerUntil;
    }

    /** Read-only seam for focused contact tests: every ticketed raider contact. */
    public long committedMeleeMoveContacts() {
        return bruteClubContacts.committedCount();
    }

    /** Historical name kept for the club GameTests; counts every ticketed move. */
    public long committedBruteClubContacts() {
        return bruteClubContacts.committedCount();
    }

    /**
     * Whether this raider is actively closing on a live quarry -- safe to
     * read on the client, unlike {@link #getTarget()}. See
     * {@link #DATA_CHARGING} for why the renderer must use this instead.
     */
    public boolean isCharging() {
        return entityData.get(DATA_CHARGING);
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

    public RallyPhase rallyPhase() {
        return rallyPhase;
    }

    public long rallyDeadline() { return rallyDeadline; }

    public boolean rallyFinalStandUsed() { return finalStandUsed; }

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

    // ------------------------------------------------ runtime Blessings ---

    /**
     * True at most once per interval unless a building edit invalidated the
     * settlement index. Primitive comparisons only; no allocation or scan.
     */
    public boolean blessingZoneRefreshDue(long gameTime, long revision,
                                          int intervalTicks) {
        if (revision == cachedBlessingZoneRevision
            && gameTime < nextBlessingZoneCheckTick) {
            return false;
        }
        cachedBlessingZoneRevision = revision;
        nextBlessingZoneCheckTick = gameTime + Math.max(1, intervalTicks);
        return true;
    }

    public void cacheBlessingZoneRank(int rank) {
        cachedBlessingZoneRank = Mth.clamp(rank, 0,
            TargetBlessingState.MAX_RANK);
    }

    public int cachedBlessingZoneRank() {
        return cachedBlessingZoneRank;
    }

    /**
     * Applies/refreshes one non-stacking personal Thorned Roads snare. A
     * weaker hit cannot extend a stronger active snare; equal rank may
     * refresh it and stronger rank replaces it.
     */
    public void applyTransientBlessingSnare(int rank, long now,
                                            int durationTicks) {
        int bounded = Mth.clamp(rank, 0, TargetBlessingState.MAX_RANK);
        if (bounded <= 0 || durationTicks <= 0) {
            return;
        }
        if (now >= transientBlessingSnareUntilTick) {
            transientBlessingSnareRank = 0;
        }
        long expiry = now + durationTicks;
        if (bounded > transientBlessingSnareRank) {
            transientBlessingSnareRank = bounded;
            transientBlessingSnareUntilTick = expiry;
        } else if (bounded == transientBlessingSnareRank) {
            transientBlessingSnareUntilTick = Math.max(
                transientBlessingSnareUntilTick, expiry);
        }
    }

    public int transientBlessingSnareRank(long gameTime) {
        if (gameTime >= transientBlessingSnareUntilTick) {
            transientBlessingSnareRank = 0;
            transientBlessingSnareUntilTick = Long.MIN_VALUE;
        }
        return transientBlessingSnareRank;
    }

    /** Immediate raid-end and reload cleanup; modifier removal is event-owned. */
    public void clearBlessingRuntimeState() {
        cachedBlessingZoneRank = 0;
        cachedBlessingZoneRevision = Long.MIN_VALUE;
        nextBlessingZoneCheckTick = Long.MIN_VALUE;
        transientBlessingSnareRank = 0;
        transientBlessingSnareUntilTick = Long.MIN_VALUE;
    }

    /**
     * Walk-away after a parley or toll (conversation Departure): this raider
     * no longer belongs to any settlement, so it counts for no raid, alarm,
     * settler panic or retreat sweep while it walks off out of sight.
     */
    public void detachForDeparture() {
        this.settlementId = null;
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

        Variant build = variant();
        double baseSpeed = build == Variant.BRUTE ? BRUTE_MOVEMENT_SPEED
            : build == Variant.BANDIT ? BANDIT_MOVEMENT_SPEED : SKIRMISHER_MOVEMENT_SPEED;
        double knockbackResistance = build == Variant.BRUTE
            ? BRUTE_KNOCKBACK_RESISTANCE
            : SKIRMISHER_KNOCKBACK_RESISTANCE;
        double health = armedMaxHealth(build, scaled, isCaptain);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth((float) health);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(baseSpeed);
        // Bandits are unarmoured road outlaws.
        getAttribute(Attributes.ARMOR).setBaseValue(build == Variant.BANDIT ? 0.0 : VARIANT_ARMOR);
        getAttribute(Attributes.KNOCKBACK_RESISTANCE)
            .setBaseValue(knockbackResistance);
        if (build == Variant.BANDIT) {
            armBandit();
        }
        // Captains keep full size so they stay the readable leader.
        getAttribute(Attributes.SCALE).setBaseValue(
            build == Variant.SKIRMISHER && !isCaptain ? SKIRMISHER_SCALE : 1.0D);
        double damage = VARIANT_ATTACK_DAMAGE
            + (scaled - 1.0F) * 2.0 + (isCaptain ? CAPTAIN_DAMAGE_BONUS : 0.0);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);
    }

    /** True for the early-raid human outlaw build. */
    public boolean isBandit() {
        return variant() == Variant.BANDIT;
    }

    /**
     * A bandit's visible weapon: a wooden or stone sword or a wooden axe,
     * chosen by UUID so it never changes on reload. Purely visual -- the
     * item's attribute modifiers are removed so a bandit hits for exactly the
     * shared {@link #VARIANT_ATTACK_DAMAGE} (an axe would otherwise add +6)
     * -- and never dropped.
     */
    private void armBandit() {
        net.minecraft.world.item.Item[] kit = {
            net.minecraft.world.item.Items.WOODEN_SWORD,
            net.minecraft.world.item.Items.STONE_SWORD,
            net.minecraft.world.item.Items.WOODEN_AXE};
        net.minecraft.world.item.ItemStack weapon = new net.minecraft.world.item.ItemStack(
            kit[Math.floorMod(getUUID().hashCode(), kit.length)]);
        weapon.set(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, weapon);
        setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
    }

    /** Configured base health of one build, with the server multiplier. */
    public static double baseMaxHealth(Variant build) {
        double base = build == Variant.BRUTE
            ? com.hearthstead.HearthsteadServerConfig.bruteBaseHealth()
            : build == Variant.BANDIT
            ? com.hearthstead.HearthsteadServerConfig.banditBaseHealth()
            : com.hearthstead.HearthsteadServerConfig.skirmisherBaseHealth();
        return base * com.hearthstead.HearthsteadServerConfig.enemyHealthMultiplier();
    }

    /** Goblin Thief health with the server multiplier. */
    public static double goblinThiefMaxHealth() {
        return com.hearthstead.HearthsteadServerConfig.goblinThiefBaseHealth()
            * com.hearthstead.HearthsteadServerConfig.enemyHealthMultiplier();
    }

    /** Health factor bought by menace: 1.0 at menace 1, 2.0 at the cap of 3. */
    public static double menaceHealthFactor(float menace) {
        return 1.0 + (Mth.clamp(menace, 1.0F, MAX_MENACE) - 1.0F) * MENACE_HEALTH_PER_POINT;
    }

    /** The exact max health {@link #assign} gives a raid raider. */
    public static double armedMaxHealth(Variant build, float menace, boolean isCaptain) {
        return baseMaxHealth(build) * menaceHealthFactor(menace)
            + (isCaptain ? CAPTAIN_HEALTH_BONUS
                * com.hearthstead.HearthsteadServerConfig.enemyHealthMultiplier() : 0.0);
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
        if (target instanceof RaiderEntity) {
            return false;
        }
        // LivingEntity rejects every Player target on Peaceful before the
        // target selector can even consider it. Hearthstead's raid profile is
        // independent of that world setting, so keep the ordinary visibility
        // and invulnerability checks while bypassing only that difficulty
        // veto. Settlers and all other targets retain vanilla semantics.
        return target instanceof Player
            ? target.canBeSeenAsEnemy() : super.canAttack(target);
    }

    /** A Hearthstead raid remains present when the world itself is Peaceful. */
    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    // ------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            setupRaiderAnimationStates();
        } else {
            // Publish the gait fact the client cannot derive for itself.
            // SynchedEntityData only sends on CHANGE, so setting the same
            // boolean every tick costs one comparison and no packets.
            LivingEntity quarry = getTarget();
            entityData.set(DATA_CHARGING, quarry != null && quarry.isAlive());
            expireCinematicOpportunity((ServerLevel) level());
            validateLoadedCaptainIdentity();
            tickCaptainRally((ServerLevel) level());
            if (tickCount % 20 == 0) {
                fleeIfRaidRetreated();
            }
        }
    }

    private void tickCaptainRally(ServerLevel level) {
        if (!isCaptain() || isScout() || settlementId == null
            || captainIdentityQuarantined || !isAlive()) {
            return;
        }
        Settlement owner = settlement();
        if (owner == null || !owner.raidLifecycle.isAuthoredFirstRaidActive()
            || !owner.raidLifecycle.isParticipant(getUUID())) {
            return;
        }
        long now = level.getGameTime();
        if (CaptainRallyRules.mayStartFinalStand(finalStandUsed,
                finalStandReady(owner))) {
            finalStandUsed = true;
            rallyPhase = RallyPhase.FINAL_STAND;
            rallyDeadline = now + RALLY_FINAL_STAND_TICKS;
            addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                RALLY_FINAL_STAND_TICKS, 0, false, true));
            addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,
                RALLY_FINAL_STAND_TICKS, 0, false, true));
            presentRally(level, ParticleTypes.SOUL_FIRE_FLAME,
                ModSounds.RAIDER_BRUTE_ROAR.get(), 0.8F);
            return;
        }
        if (rallyPhase == RallyPhase.READY
            && getHealth() <= getMaxHealth() * 0.80F) {
            rallyPhase = RallyPhase.CHANNELING;
            rallyDeadline = now + RALLY_CHANNEL_TICKS;
            presentRally(level, ParticleTypes.ANGRY_VILLAGER,
                ModSounds.RAID_HORN.get(), 0.95F);
        } else if (rallyPhase == RallyPhase.CHANNELING
            && now >= rallyDeadline) {
            rallyPhase = RallyPhase.EMPOWERED;
            rallyDeadline = now + RALLY_BUFF_TICKS;
            applyRallyToLoadedBand(level, RALLY_BUFF_TICKS);
            presentRally(level, ParticleTypes.FLAME,
                ModSounds.RAIDER_BRUTE_ROAR.get(), 0.95F);
        } else if (rallyPhase == RallyPhase.EMPOWERED) {
            // Reapply a short overlap so a roster member loaded during the
            // bounded window receives the same server-owned rally.
            if (now >= rallyDeadline) {
                rallyPhase = RallyPhase.SPENT;
            } else if (CaptainRallyRules.shouldRefresh(now, rallyDeadline,
                    tickCount)) {
                applyRallyToLoadedBand(level,
                    CaptainRallyRules.refreshDuration(now, rallyDeadline));
            }
        } else if (rallyPhase == RallyPhase.EXPOSED && now >= rallyDeadline) {
            rallyPhase = RallyPhase.SPENT;
        }
        if (rallyPhase == RallyPhase.FINAL_STAND
            && now >= rallyDeadline) {
            rallyPhase = RallyPhase.SPENT;
        }
    }

    private boolean finalStandReady(Settlement owner) {
        return owner.raidLifecycle.participants().stream()
            .filter(id -> !id.equals(getUUID()))
            .allMatch(owner.raidLifecycle.terminalParticipants()::contains);
    }

    private void applyRallyToLoadedBand(ServerLevel level, int duration) {
        Settlement owner = settlement();
        if (owner == null || duration <= 0) {
            return;
        }
        AABB bounds = getBoundingBox().inflate(64.0D);
        for (RaiderEntity follower : level.getEntitiesOfClass(
                RaiderEntity.class, bounds, RaiderEntity::isAlive)) {
            if (follower == this || follower.isCaptain()
                || !java.util.Objects.equals(captainId, follower.captainId())
                || !java.util.Objects.equals(settlementId,
                    follower.settlementId())
                || !owner.raidLifecycle.isParticipant(follower.getUUID())
                || owner.raidLifecycle.terminalParticipants().contains(
                    follower.getUUID())) {
                continue;
            }
            follower.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
                duration, 0, false, true));
            follower.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,
                duration, 0, false, true));
        }
    }

    private void presentRally(ServerLevel level,
                              net.minecraft.core.particles.ParticleOptions particle,
                              net.minecraft.sounds.SoundEvent sound, float pitch) {
        level.sendParticles(particle, getX(), getY() + 1.5D, getZ(),
            18, 0.7D, 0.5D, 0.7D, 0.02D);
        level.playSound(null, blockPosition(), sound, SoundSource.HOSTILE,
            1.2F, pitch);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean finisherImpact = cinematicFinisherImpact;
        cinematicFinisherImpact = false;
        boolean accepted = super.hurt(source, amount);
        if (accepted && !level().isClientSide && cinematicOpportunity != null) {
            clearCinematicOpportunity(finisherImpact
                ? CinematicOpportunity.ClearReason.FINISHER_RESOLVED
                : CinematicOpportunity.ClearReason.INTERVENING_HIT);
        }
        if (accepted && !level().isClientSide
            && rallyPhase == RallyPhase.CHANNELING
            && interruptsRally(source)) {
            rallyPhase = RallyPhase.EXPOSED;
            rallyDeadline = level().getGameTime() + RALLY_EXPOSED_TICKS;
            addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                RALLY_EXPOSED_TICKS, 0, false, true));
            addEffect(new MobEffectInstance(MobEffects.GLOWING,
                RALLY_EXPOSED_TICKS, 0, false, true));
            if (level() instanceof ServerLevel server) {
                presentRally(server, ParticleTypes.CRIT,
                    SoundEvents.SHIELD_BREAK, 1.25F);
            }
        }
        return accepted;
    }

    /** Called only after a Guard's exact ticketed hit was accepted. */
    public boolean offerCinematicOpportunity(UUID openingGuardId, long now) {
        if (!(level() instanceof ServerLevel server) || !isAlive() || isRemoved()) {
            return false;
        }
        expireCinematicOpportunity(server, now);
        if (now < cinematicCooldownUntil
            || cinematicOpportunity != null
                && cinematicOpportunity.state() != CinematicOpportunity.State.CLEARED) {
            return false;
        }
        CinematicOpportunity next = CinematicOpportunity.open(getUUID(),
            openingGuardId, getHealth(), getMaxHealth(), now);
        if (next == null) return false;
        cinematicOpportunity = next;
        level().broadcastEntityEvent(this, EV_CINEMATIC_EXPOSED);
        return true;
    }

    public boolean claimCinematicOpportunity(UUID guardId, long now) {
        if (!(level() instanceof ServerLevel server)) {
            return false;
        }
        expireCinematicOpportunity(server, now);
        return cinematicOpportunity != null && cinematicOpportunity.claim(guardId, now);
    }

    /** Read-only runtime observation for server-side combat verification. */
    @Nullable
    public CinematicOpportunity.State cinematicOpportunityState() {
        return cinematicOpportunity == null ? null : cinematicOpportunity.state();
    }

    /** Read-only terminal observation; the opportunity itself is never persisted. */
    @Nullable
    public CinematicOpportunity.ClearReason cinematicOpportunityClearReason() {
        return cinematicOpportunity == null ? null : cinematicOpportunity.clearReason();
    }

    /** Rechecks the claimed opportunity at the exact ordinary contact tick. */
    public boolean beginCinematicFinisherImpact(UUID guardId, long now) {
        if (level() instanceof ServerLevel server) {
            expireCinematicOpportunity(server, now);
        }
        if (!isAlive() || isRemoved() || cinematicOpportunity == null
            || !cinematicOpportunity.mayResolve(guardId, now)) {
            return false;
        }
        cinematicFinisherImpact = true;
        return true;
    }

    public void clearCinematicOpportunity(CinematicOpportunity.ClearReason reason) {
        cinematicFinisherImpact = false;
        if (cinematicOpportunity == null
            || cinematicOpportunity.state() == CinematicOpportunity.State.CLEARED) return;
        UUID claimingGuardId = cinematicOpportunity.claimingGuardId();
        cinematicOpportunity.clear(reason);
        cinematicCooldownUntil = Math.max(cinematicCooldownUntil,
            level().getGameTime() + CinematicOpportunity.WINDOW_TICKS);
        if (level() instanceof ServerLevel server) {
            server.broadcastEntityEvent(this, EV_CINEMATIC_CLEAR);
            if (reason != CinematicOpportunity.ClearReason.FINISHER_RESOLVED
                && claimingGuardId != null
                && server.getEntity(claimingGuardId) instanceof SettlerEntity guard) {
                guard.cancelCinematicFinisherFor(getUUID());
            }
        }
    }

    private void expireCinematicOpportunity(ServerLevel level) {
        expireCinematicOpportunity(level, level.getGameTime());
    }

    /** All expiry paths come through here so a claim cannot outlive its target. */
    private void expireCinematicOpportunity(ServerLevel level, long now) {
        if (cinematicOpportunity == null
            || cinematicOpportunity.state() == CinematicOpportunity.State.CLEARED
            || cinematicOpportunity.isLiveAt(now)) return;
        clearCinematicOpportunity(CinematicOpportunity.ClearReason.EXPIRED);
    }

    /** Presentation only, after ordinary finishing damage is accepted. */
    public void triggerCinematicStagger() {
        if (!level().isClientSide && isAlive()) {
            level().broadcastEntityEvent(this, EV_CINEMATIC_STAGGER);
        }
    }

    private boolean interruptsRally(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker instanceof Player player) {
            return player.isAlive();
        }
        if (attacker instanceof SettlerEntity defender) {
            return defender.isAlive() && defender.getProfession().martial()
                && settlementId != null
                && defender.settlement() != null
                && settlementId.equals(defender.settlement().id);
        }
        return false;
    }

    /**
     * One-shot post-load authority check. Entity NBT is read before every
     * settlement lookup is guaranteed to be available, so a syntactically
     * valid captain waits here until its settlement can confirm the exact
     * persisted display name. Invalid identity is demoted/frozen immediately,
     * disarms the active raid's reward ledger once that ledger is reachable,
     * then is discarded as a definitive terminal participant rather than
     * fighting on with hidden captain bonuses.
     */
    private void validateLoadedCaptainIdentity() {
        if (captainIdentityQuarantined) {
            reportCaptainIdentityQuarantine();
            if (captainIdentityQuarantineReported && !isRemoved()) {
                discard();
            }
            return;
        }
        if (!captainIdentityAuthorityCheckPending || !isCaptain()) {
            return;
        }
        Settlement owner = settlement();
        if (owner == null) {
            return;
        }
        String actual = getCustomName() == null
            ? null : getCustomName().getString();
        String expected = RaidDirector.leaderNameOf(owner, captainId)
            .orElse(null);
        if (expected == null || !expected.equals(actual)
            || !isCustomNameVisible()) {
            quarantineCaptainIdentity();
            return;
        }
        captainIdentityAuthorityCheckPending = false;
    }

    private void quarantineCaptainIdentity() {
        captainIdentityQuarantined = true;
        captainIdentityAuthorityCheckPending = false;
        entityData.set(DATA_CAPTAIN, false);
        entityData.set(DATA_SAGA_MARKED, false);
        setCustomName(null);
        setCustomNameVisible(false);
        setNoAi(true);
    }

    private void reportCaptainIdentityQuarantine() {
        if (captainIdentityQuarantineReported
            || !(level() instanceof ServerLevel server)) {
            return;
        }
        Settlement owner = settlement();
        if (owner == null) {
            return;
        }
        boolean changed = false;
        if (owner.raidLifecycle.isAuthoredFirstRaidActive()) {
            changed = !owner.raidLifecycle.integrityLost();
            owner.raidLifecycle.markIntegrityLost();
        } else if (owner.recurringRaidRun.isActive()) {
            changed = !owner.recurringRaidRun.integrityLost();
            owner.recurringRaidRun.markIntegrityLost();
        }
        if (changed) {
            SettlementSavedData.get(server).setDirty();
        }
        captainIdentityQuarantineReported = true;
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
        if (cinematicExposedState.isStarted()
            && cinematicExposedState.getAccumulatedTime() > 800L) {
            cinematicExposedState.stop();
        }
        if (cinematicStaggerState.isStarted()
            && cinematicStaggerState.getAccumulatedTime() > 420L) {
            cinematicStaggerState.stop();
        }
        if (bruteClubStrikeState.isStarted()
            && bruteClubStrikeState.getAccumulatedTime() > 1700L) {
            bruteClubStrikeState.stop();
        }
        if (raiderHeavyState.isStarted()
            && raiderHeavyState.getAccumulatedTime() > 2200L) {
            raiderHeavyState.stop();
        }
        if (raiderLightState.isStarted()
            && raiderLightState.getAccumulatedTime() > 400L) {
            raiderLightState.stop();
        }
        if (raiderHopBackState.isStarted()
            && raiderHopBackState.getAccumulatedTime() > 400L) {
            raiderHopBackState.stop();
        }
        if (raiderDodgeLeftState.isStarted()
            && raiderDodgeLeftState.getAccumulatedTime() > 450L) {
            raiderDodgeLeftState.stop();
        }
        if (raiderDodgeRightState.isStarted()
            && raiderDodgeRightState.getAccumulatedTime() > 450L) {
            raiderDodgeRightState.stop();
        }
        if (raiderTauntState.isStarted()
            && raiderTauntState.getAccumulatedTime() > 2000L) {
            raiderTauntState.stop();
        }
        // A walking Brute's footfalls: one tiny close-range tremor per step
        // (two steps per walk cycle). Pure client presentation.
        if (variant() == Variant.BRUTE && moving && clientImpactCue != null) {
            int step = (int) Math.floor(walkAnimation.position() / (float) Math.PI);
            if (bruteFootstepIndex != Integer.MIN_VALUE && step != bruteFootstepIndex) {
                clientImpactCue.play(this, getX(), getY(), getZ(),
                    BRUTE_FOOTSTEP_SHAKE, BRUTE_FOOTSTEP_RADIUS);
            }
            bruteFootstepIndex = step;
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EV_STRIKE) {
            startOnlyCombatOneShot(strikeState);
        } else if (id == EV_BREACH_SLAM) {
            startOnlyCombatOneShot(breachSlamState);
        } else if (id == EV_LOOT_SNATCH) {
            startOnlyCombatOneShot(lootSnatchState);
        } else if (id == EV_CINEMATIC_EXPOSED) {
            startOnlyCombatOneShot(cinematicExposedState);
        } else if (id == EV_CINEMATIC_STAGGER) {
            startOnlyCombatOneShot(cinematicStaggerState);
        } else if (id == EV_BRUTE_CLUB_STRIKE) {
            startOnlyCombatOneShot(bruteClubStrikeState);
        } else if (id == EV_RAIDER_HEAVY) {
            startOnlyCombatOneShot(raiderHeavyState);
        } else if (id == EV_RAIDER_LIGHT) {
            startOnlyCombatOneShot(raiderLightState);
        } else if (id == EV_RAIDER_HOP_BACK) {
            startOnlyCombatOneShot(raiderHopBackState);
        } else if (id == EV_RAIDER_DODGE_LEFT) {
            startOnlyCombatOneShot(raiderDodgeLeftState);
        } else if (id == EV_RAIDER_DODGE_RIGHT) {
            startOnlyCombatOneShot(raiderDodgeRightState);
        } else if (id == EV_RAIDER_TAUNT) {
            startOnlyCombatOneShot(raiderTauntState);
            if (!isGoblinThiefDemo()) {
                level().playLocalSound(getX(), getEyeY(), getZ(), ModSounds.RAIDER_BARK.get(),
                    SoundSource.HOSTILE, 1.0F, getVoicePitch(), false);
            }
        } else if (id == EV_GROUND_SLAM_HEAVY || id == EV_GROUND_SLAM_CLUB) {
            ImpactCue cue = clientImpactCue;
            if (cue != null) {
                RaiderMove slam = id == EV_GROUND_SLAM_HEAVY
                    ? RaiderMove.HEAVY : RaiderMove.CLUB;
                net.minecraft.world.phys.Vec3 point = slamPoint();
                cue.play(this, point.x, point.y, point.z, slam.shakeMagnitude(),
                    RaiderMove.SLAM_SHAKE_RADIUS);
            }
        } else if (id == EV_CINEMATIC_CLEAR) {
            cinematicExposedState.stop();
        } else {
            super.handleEntityEvent(id);
        }
    }

    private void startOnlyCombatOneShot(AnimationState selected) {
        strikeState.stop();
        breachSlamState.stop();
        lootSnatchState.stop();
        cinematicExposedState.stop();
        cinematicStaggerState.stop();
        bruteClubStrikeState.stop();
        raiderHeavyState.stop();
        raiderLightState.stop();
        raiderHopBackState.stop();
        raiderDodgeLeftState.stop();
        raiderDodgeRightState.stop();
        raiderTauntState.stop();
        selected.start(tickCount);
    }

    /**
     * Fired from {@link com.hearthstead.entity.ai.RaiderBreachGoal} the
     * instant a door or wall actually gives way, so the scar and this
     * clip's playback start the same tick (see {@code RaiderAnimations}'s
     * header for why the clip's own internal impact keyframe still lands a
     * few ticks later. This reaction-first breach is deliberately not the
     * guard's newer ticketed-contact {@code MELEE} contract.)
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
    public boolean doHurtTarget(Entity target) {
        // RAIDER_STRIKE's trigger, mirroring SettlerEntity's own EV_MELEE
        // broadcast on doHurtTarget exactly: unconditional, before the
        // outcome is known -- MeleeAttackGoal only calls this once the
        // target is already in reach, so this is genuinely "the swing", not
        // a speculative check.
        if (!committingBruteClubContact) {
            level().broadcastEntityEvent(this, EV_STRIKE);
        }
        // Mob#doHurtTarget is reproduced with one intentional substitution:
        // the data-driven Hearthstead source uses scaling=never, so Player
        // damage is not multiplied to zero on Peaceful. The enchantment,
        // knockback, post-attack, sound and last-target hooks stay identical
        // to vanilla 1.21.1.
        float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (committingBruteClubContact) {
            // RaiderMove damage multiplier; exactly 1.0 for LIGHT and CLUB.
            damage = (float) (damage * committingMoveDamageMultiplier);
        }
        DamageSource source = damageSources().source(
            ModDamageTypes.RAIDER_ATTACK, this);
        if (level() instanceof ServerLevel server) {
            damage = EnchantmentHelper.modifyDamage(server, getWeaponItem(),
                target, source, damage);
        }
        boolean hit = target.hurt(source, damage);
        if (hit) {
            if (isGoblinThiefDemo() && target instanceof LivingEntity living
                    && getMainHandItem().is(ModItems.POOP_STICK.get())) {
                // The demo's bounded stationary retaliation calls this only
                // after its windup and only when this damage has landed.
                PoopStickItem.applyPoison(living, this);
            }
            float knockback = getKnockback(target, source);
            if (knockback > 0.0F && target instanceof LivingEntity living) {
                living.knockback((double) (knockback * 0.5F),
                    (double) Mth.sin(getYRot() * (float) (Math.PI / 180.0)),
                    (double) -Mth.cos(getYRot() * (float) (Math.PI / 180.0)));
                setDeltaMovement(getDeltaMovement().multiply(0.6, 1.0, 0.6));
            }
            if (level() instanceof ServerLevel server) {
                EnchantmentHelper.doPostAttackEffects(server, target, source);
            }
            setLastHurtMob(target);
            playAttackSound();
        }
        // A blow on a Guard or Archer is the defence doing its job; only a hurt
        // civilian counts against a BLOD raid, or one scratch on a winning
        // defender turned every victory into a defeat.
        if (hit && target instanceof SettlerEntity hurtSettler
            && !hurtSettler.getProfession().martial()
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

    /** Keep a tracked participant in the settlement's dimension. */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    /**
     * Explicit destruction is terminal; chunk unload is deliberately not.
     * {@link Entity#discard()} dispatches through this virtual method, while
     * the chunk manager calls final setRemoved(UNLOADED_TO_CHUNK) directly.
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (reason == Entity.RemovalReason.KILLED
            || reason == Entity.RemovalReason.DISCARDED) {
            clearCinematicOpportunity(CinematicOpportunity.ClearReason.TARGET_LOST);
        }
        if (reason == Entity.RemovalReason.KILLED
            || reason == Entity.RemovalReason.DISCARDED) {
            recordDefinitiveRaidTerminal();
        }
        super.remove(reason);
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
    public void die(DamageSource cause) {
        super.die(cause);
        // NeoForge's LivingDeathEvent is cancellable. LivingEntity#die
        // returns before setting its protected `dead` flag when another mod
        // cancels that event; recording a captain kill or terminal UUID in
        // that case would let a living raider complete the raid ledger.
        if (!dead) {
            return;
        }
        if (level() instanceof ServerLevel lootLevel) {
            returnStolenGoods(lootLevel);
        }
        if (isCaptain() && level() instanceof ServerLevel server) {
            Settlement s = settlement();
            if (s != null && s.pendingRaid != null && captainId != null) {
                s.raidCaptainSlainId = captainId;
                SettlementSavedData.get(server).setDirty();
            }
        }
        // Death is definitive immediately; waiting for the later death-timer
        // removal would make the saved ledger depend on whether the chunk
        // stayed loaded for the animation.
        recordDefinitiveRaidTerminal();
    }

    /**
     * Dawn-retreat straggler: a raider whose raid (first or recurring)
     * already resolved by retreat (it was in an unloaded chunk at dawn)
     * leaves as soon as it loads. Its discard is ignored by the terminal
     * ledger below.
     */
    private void fleeIfRaidRetreated() {
        if (isRemoved() || isScout()) {
            return;
        }
        Settlement settlement = settlement();
        if (settlement != null
            && settlement.raidLifecycle.isRetreatedRaider(getUUID())
            && !settlement.recurringRaidRun.isParticipant(getUUID())) {
            discard();
        }
    }

    private void recordDefinitiveRaidTerminal() {
        if (!(level() instanceof ServerLevel server) || isScout()) {
            return;
        }
        Settlement settlement = settlement();
        if (settlement == null) {
            return;
        }
        if (settlement.raidLifecycle.isRetreatedRaider(getUUID())
            && !settlement.recurringRaidRun.isParticipant(getUUID())
            && !(settlement.raidLifecycle.isAuthoredFirstRaidActive()
                && settlement.raidLifecycle.isParticipant(getUUID()))) {
            // Belongs to a raid that already retreated at dawn; never
            // evidence (or an integrity loss) for any newer raid.
            return;
        }
        if (settlement.raidLifecycle.isAuthoredFirstRaidActive()) {
            // Feed every non-scout raider assigned to this settlement through
            // the strict first ledger. An UUID outside the sealed set proves
            // the live band and capture disagree and disarms its Blessing.
            boolean integrityWasLost = settlement.raidLifecycle.integrityLost();
            boolean recorded = settlement.raidLifecycle
                .recordTerminalParticipant(getUUID());
            if (recorded || (!integrityWasLost
                && settlement.raidLifecycle.integrityLost())) {
                SettlementSavedData.get(server).setDirty();
            }
            return;
        }
        if (settlement.recurringRaidRun.isActive()) {
            // Recurring raids use the same definitive evidence: death and
            // explicit discard arrive here, while chunk unload never does.
            // Unknown ids preserve the known ledger but permanently remove
            // this serial's reward eligibility.
            boolean integrityWasLost = settlement.recurringRaidRun.integrityLost();
            boolean recorded = settlement.recurringRaidRun
                .recordTerminalParticipant(getUUID());
            if (recorded || (!integrityWasLost
                && settlement.recurringRaidRun.integrityLost())) {
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
        GoblinThiefDemo.dropRareDeathLoot(this);
        super.dropCustomDeathLoot(level, source, recentlyHit);
        // Stolen settlement goods are returned in die(), not here: vanilla
        // skips this hook when doMobLoot is false (BH-19).
    }

    /**
     * Kill a laden raider and the goods come back. This is the other half of
     * theft being real: it can be undone by fighting for it. The goods are
     * real settlement or player items, so they bypass doMobLoot and use the
     * durable, gamerule-independent spill (BH-19).
     */
    private void returnStolenGoods(ServerLevel level) {
        for (int i = 0; i < loot.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = loot.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                com.hearthstead.util.ItemSpill.conserve(level, blockPosition(), stack);
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
        if (captainIdentityQuarantined) {
            tag.putBoolean("CaptainIdentityQuarantined", true);
        }
        tag.putByte("RallyPhase", (byte) rallyPhase.ordinal());
        tag.putLong("RallyDeadline", rallyDeadline);
        tag.putBoolean("RallyFinalStandUsed", finalStandUsed);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        CompoundTag goblin = getPersistentData().getCompound("HearthsteadGoblinThiefDemo");
        setGoblinThiefPresentation(getPersistentData().contains("HearthsteadGoblinThiefDemo", Tag.TAG_COMPOUND),
            goblin.getInt("Stage"), goblin.contains("StageStartedAt") ? goblin.getLong("StageStartedAt")
                : goblin.getInt("Stage") == 1 ? Math.max(0,goblin.getLong("ReadyAt")-60) : 0L);
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
        captainIdentityQuarantined = tag.getBoolean(
            "CaptainIdentityQuarantined");
        captainIdentityQuarantineReported = false;
        captainIdentityAuthorityCheckPending = isCaptain();
        rallyPhase = RallyPhase.byOrdinal(tag.getByte("RallyPhase"));
        rallyDeadline = Math.max(0L, tag.getLong("RallyDeadline"));
        finalStandUsed = tag.getBoolean("RallyFinalStandUsed");
        boolean persistedVisibleName = tag.contains("CustomName", Tag.TAG_STRING)
            && tag.contains("CustomNameVisible", Tag.TAG_BYTE)
            && tag.getBoolean("CustomNameVisible")
            && getCustomName() != null
            && RaidDirector.isValidLeaderName(getCustomName().getString());
        if (captainIdentityQuarantined || (isCaptain()
            && (captainId == null || settlementId == null
                || !persistedVisibleName))) {
            quarantineCaptainIdentity();
        }
        // Deliberately not persisted: neither a short personal snare nor a
        // cached building-zone result may survive entity reconstruction.
        clearBlessingRuntimeState();
    }
}

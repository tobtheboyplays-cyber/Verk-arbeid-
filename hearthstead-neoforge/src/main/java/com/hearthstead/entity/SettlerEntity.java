package com.hearthstead.entity;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.ai.BoundedStrollGoal;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.entity.ai.EatFromHearthGoal;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.GuardPatrolGoal;
import com.hearthstead.entity.ai.GuardRespondToAlertGoal;
import com.hearthstead.entity.ai.LumbererWorkGoal;
import com.hearthstead.entity.ai.RestAtNightGoal;
import com.hearthstead.entity.ai.ReturnToSettlementGoal;
import com.hearthstead.entity.ai.SettlerDefenseTargetGoal;
import com.hearthstead.entity.ai.SettlerPanicGoal;
import com.hearthstead.entity.ai.TravelerJoinGoal;
import com.hearthstead.network.OpenSettlerScreenPayload;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.entity.ai.GoToPostGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

public class SettlerEntity extends PathfinderMob {
    private static final EntityDataAccessor<Byte> DATA_PROFESSION =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_ACTIVITY =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_HUNGER =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ENERGY =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MORALE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_APPEARANCE_SEED =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /**
     * What the settler is physically carrying, and how much they could.
     * The bag itself is server-only, so without these the client has no way
     * to draw a sack that means anything -- and a sack that does not track
     * the real load is decoration, which is what the old always-on backpack
     * cube was (D-A2b-1).
     */
    private static final EntityDataAccessor<Integer> DATA_CARRY_LOAD =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_CARRY_CAPACITY =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);

    public static final byte EV_CELEBRATE = 64;
    public static final byte EV_MELEE = 65;
    public static final byte EV_SHIELD_BLOCK = 66;
    public static final byte EV_WAKE = 67;
    public static final byte EV_COURIER_LIFT = 68;
    public static final byte EV_COURIER_SET_DOWN = 69;

    // Sound-sync contracts (docs/ANIMATION_CATALOGUE.md §0.4): each value
    // must agree with the clip comment in SettlerAnimations and the
    // assertion in tools/anim_check.py.
    public static final int LADDER_CREAK_PERIOD = 20;
    public static final int LADDER_CREAK_TICK_A = 5;
    public static final int LADDER_CREAK_TICK_B = 15;
    public static final int LIMP_GRUNT_MOD = 84;
    public static final int LIMP_GRUNT_TICK = 8;
    public static final int WAKE_YAWN_TICK = 24;
    public static final int SHIELD_THUD_DELAY = 2;
    public static final int CHEER_TICK_A = 9;
    public static final int CHEER_TICK_B = 22;

    /** Bag capacity: harvested goods carried before a hearth deposit run. */
    public static final int BAG_SIZE = 8;
    /**
     * How much a settler carries on their back before the sack is full.
     * The sack is tier one of a visible capacity mechanic (D-007): a cart
     * raises this later, and both the AI's stop condition and the renderer
     * read this one number so they can never disagree.
     */
    public static final int BASE_CARRY_CAPACITY = 8;
    /**
     * How much a full sack costs in speed. A load you can carry at full
     * pace is not a load -- the whole point of making capacity visible is
     * that the player can SEE the trade, so it has to cost something in the
     * world and not only in a number.
     */
    public static final float MAX_CARRY_SLOW = 0.38F;
    private static final net.minecraft.resources.ResourceLocation CARRY_SLOW_ID =
        com.hearthstead.Hearthstead.id("carry_slow");

    @Nullable
    private UUID settlementId;
    @Nullable
    private UUID targetSettlementId;
    @Nullable
    private BlockPos hearthPos;
    @Nullable
    private BlockPos claimedBed;
    private boolean traveler;
    /** Rolled once, then earned. See {@link SettlerAttributes}. */
    /** Last block a footfall was counted on; see {@link #wearPath()}. */
    private BlockPos lastFootfall;
    private SettlerAttributes attributes;
    /** What they are like, and what it costs them. See {@link Trait}. */
    private java.util.EnumSet<Trait> traits = java.util.EnumSet.noneOf(Trait.class);
    public final SimpleContainer bag = new SimpleContainer(BAG_SIZE);
    private int voiceCooldown;

    // Server-side accent scheduler: countdowns to staggered one-shot
    // broadcasts and their delayed sound accents (-1 = idle). One-shot
    // variation must stagger the TRIGGER tick, never the clip's sampled
    // time -- offsetting a one-shot's ageInTicks truncates it.
    private int wakeBroadcastIn = -1;
    private int wakeYawnIn = -1;
    private int shieldThudIn = -1;
    private int celebrateBroadcastIn = -1;
    private int celebrateAge = -1;

    // Client-side animation machinery.
    public final AnimationState idleState = new AnimationState();
    public final AnimationState farmState = new AnimationState();
    public final AnimationState chopState = new AnimationState();
    public final AnimationState eatState = new AnimationState();
    public final AnimationState restState = new AnimationState();
    public final AnimationState stanceState = new AnimationState();
    public final AnimationState meleeState = new AnimationState();
    public final AnimationState celebrateState = new AnimationState();
    // SLICE ANIM-1 additions.
    public final AnimationState plantState = new AnimationState();
    public final AnimationState harvestState = new AnimationState();
    public final AnimationState waterState = new AnimationState();
    public final AnimationState limbState = new AnimationState();
    public final AnimationState haulState = new AnimationState();
    public final AnimationState patrolState = new AnimationState();
    public final AnimationState shieldState = new AnimationState();
    public final AnimationState sleepState = new AnimationState();
    public final AnimationState wakeState = new AnimationState();
    public final AnimationState climbState = new AnimationState();
    // SLICE A2a additions (clips land with the courier piece).
    public final AnimationState carryState = new AnimationState();
    // CHAINS-1 craft motions. One state per MOTION, not per trade (D-015).
    public final AnimationState kneadState = new AnimationState();
    public final AnimationState cleaveState = new AnimationState();
    public final AnimationState stokeState = new AnimationState();
    public final AnimationState hammerState = new AnimationState();
    public final AnimationState sawState = new AnimationState();
    public final AnimationState fineWorkState = new AnimationState();
    // D-016 signature motions.
    public final AnimationState gatherState = new AnimationState();
    public final AnimationState ovenState = new AnimationState();
    public final AnimationState sowState = new AnimationState();
    public final AnimationState mineState = new AnimationState();
    public final AnimationState leapState = new AnimationState();
    public final AnimationState sortState = new AnimationState();
    public final AnimationState liftState = new AnimationState();
    public final AnimationState setDownState = new AnimationState();

    public SettlerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        // Roll a real appearance seed for every settler the moment it is
        // constructed, regardless of creation path (SettlementManager,
        // spawn egg, /summon, mob spawner...). This is the only point every
        // path passes through, so it's the only place that can guarantee no
        // settler is ever left at the synced-data default of 0 -- which
        // would decode to an identical, permanently-baked-in appearance
        // once first saved. A later readAdditionalSaveData for a loaded
        // entity always overrides this with the persisted value.
        entityData.set(DATA_APPEARANCE_SEED, random.nextInt());
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 24.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.ATTACK_DAMAGE, 4.0)
            .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PROFESSION, Profession.NONE.id());
        builder.define(DATA_ACTIVITY, SettlerActivity.IDLE.id());
        builder.define(DATA_HUNGER, 80.0F);
        builder.define(DATA_ENERGY, 90.0F);
        builder.define(DATA_MORALE, 60.0F);
        builder.define(DATA_APPEARANCE_SEED, 0);
        builder.define(DATA_CARRY_LOAD, 0);
        builder.define(DATA_CARRY_CAPACITY, BASE_CARRY_CAPACITY);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        // Flag-free: runs alongside any move goal, opens doors on the path
        // and closes them again behind (keeps homes enclosed and defensible).
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(1, new SettlerPanicGoal(this));
        goalSelector.addGoal(2, new TravelerJoinGoal(this));
        goalSelector.addGoal(2, new com.hearthstead.entity.ai.GuardLeapGoal(this));
        goalSelector.addGoal(2, new GuardMeleeGoal(this));
        goalSelector.addGoal(3, new GuardRespondToAlertGoal(this));
        goalSelector.addGoal(4, new EatFromHearthGoal(this));
        goalSelector.addGoal(5, new RestAtNightGoal(this));
        goalSelector.addGoal(6, new GoToPostGoal(this));
        goalSelector.addGoal(6, new FarmerWorkGoal(this));
        goalSelector.addGoal(6, new LumbererWorkGoal(this));
        goalSelector.addGoal(6, new CourierWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.CrafterWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.MinerWorkGoal(this));
        // Lower than the delivery goal: tidying is what a courier does when
        // there is nothing to fetch.
        goalSelector.addGoal(7, new com.hearthstead.entity.ai.TidyWarehouseGoal(this));
        goalSelector.addGoal(6, new GuardPatrolGoal(this));
        goalSelector.addGoal(7, new ReturnToSettlementGoal(this));
        goalSelector.addGoal(8, new BoundedStrollGoal(this));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new SettlerDefenseTargetGoal(this));
    }

    // -------------------------------------------------------------- sync ---

    public Profession getProfession() {
        return Profession.byId(entityData.get(DATA_PROFESSION));
    }

    public SettlerActivity getActivity() {
        return SettlerActivity.byId(entityData.get(DATA_ACTIVITY));
    }

    public void setActivity(SettlerActivity activity) {
        if (getActivity() != activity) {
            entityData.set(DATA_ACTIVITY, activity.id());
        }
    }

    public float getHunger() {
        return entityData.get(DATA_HUNGER);
    }

    public void setHunger(float value) {
        entityData.set(DATA_HUNGER, Mth.clamp(value, 0.0F, 100.0F));
    }

    public float getEnergy() {
        return entityData.get(DATA_ENERGY);
    }

    public void setEnergy(float value) {
        entityData.set(DATA_ENERGY, Mth.clamp(value, 0.0F, 100.0F));
    }

    public float getMorale() {
        return entityData.get(DATA_MORALE);
    }

    /**
     * Moves morale, through whatever this settler's temperament does to it.
     *
     * <p>STOIC dampens both directions, which is the trade-off: hard to
     * dishearten and hard to cheer. Scaling only the losses would have made it
     * a pure advantage, and a trait that is only an advantage is a stat point
     * with a name.
     */
    public void addMorale(float delta) {
        float scaled = delta * (delta < 0.0F
            ? Trait.moraleDecay(traits()) : Trait.moraleGain(traits()));
        entityData.set(DATA_MORALE, Mth.clamp(getMorale() + scaled, 0.0F, 100.0F));
    }

    public int getAppearanceSeed() {
        return entityData.get(DATA_APPEARANCE_SEED);
    }

    /** The constructor already rolls a real seed for every settler
     *  regardless of creation path; this setter exists only for explicit
     *  overrides (e.g. a future "restyle" feature). Never call this after
     *  the settler has joined a settlement -- the look must stay stable for
     *  a given settler across their whole life. */
    public void setAppearanceSeed(int seed) {
        entityData.set(DATA_APPEARANCE_SEED, seed);
    }

    public SettlerAppearance getAppearance() {
        return SettlerAppearance.decode(getAppearanceSeed());
    }

    // -------------------------------------------------------- membership ---

    public boolean isBound() {
        return settlementId != null;
    }

    public boolean isTraveler() {
        return traveler;
    }

    @Nullable
    public UUID getSettlementId() {
        return settlementId;
    }

    @Nullable
    public UUID getTargetSettlementId() {
        return targetSettlementId;
    }

    @Nullable
    public BlockPos getHearthPos() {
        return hearthPos;
    }

    @Nullable
    public BlockPos getClaimedBed() {
        return claimedBed;
    }

    public void claimBed(@Nullable BlockPos bed) {
        this.claimedBed = bed;
    }

    public void releaseBed() {
        if (isSleeping()) {
            stopSleeping();
        }
        this.claimedBed = null;
    }

    public String getSettlerName() {
        Component custom = getCustomName();
        return custom != null ? custom.getString() : "Settler";
    }

    public void setSettlerName(String name) {
        setCustomName(Component.literal(name));
        setCustomNameVisible(false);
    }

    public void bindTo(UUID settlement, BlockPos hearth) {
        this.settlementId = settlement;
        this.targetSettlementId = null;
        this.hearthPos = hearth;
        this.traveler = false;
        setActivity(SettlerActivity.IDLE);
    }

    public void markTraveler(UUID settlement, BlockPos hearth) {
        this.traveler = true;
        this.targetSettlementId = settlement;
        this.hearthPos = hearth;
        setActivity(SettlerActivity.TRAVELING);
    }

    public void unbind() {
        releaseBed();
        this.settlementId = null;
        this.targetSettlementId = null;
        this.hearthPos = null;
        this.traveler = false;
        if (getProfession() != Profession.NONE) {
            entityData.set(DATA_PROFESSION, Profession.NONE.id());
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        setActivity(SettlerActivity.IDLE);
    }

    @Nullable
    public Settlement settlement() {
        return level() instanceof ServerLevel serverLevel
            ? SettlementManager.byId(serverLevel, settlementId) : null;
    }

    @Nullable
    public HearthBlockEntity hearth() {
        return hearthPos != null
            && level().getBlockEntity(hearthPos) instanceof HearthBlockEntity be ? be : null;
    }

    /**
     * Sets the synced projection of this settler's trade.
     *
     * <p>D-011: employment itself lives in {@link com.hearthstead.settlement.Building#workers}
     * and nowhere else. What is stored here is the <b>projection</b> the client
     * needs — the outfit, the tool in the hand, the animation set — recomputed
     * on the server whenever employment changes, exactly the way the plaque's
     * occupancy is. It is never consulted to decide who works where.
     *
     * <p>Deliberately silent: no morale, no sound, no celebration. Those belong
     * to the events ({@link #onHired}, {@link #onDismissed}), not to keeping a
     * projection in step — otherwise a settler cheers every time a chunk
     * reloads.
     */
    public void setProfessionProjection(Profession profession) {
        if (getProfession() == profession) {
            return;
        }
        entityData.set(DATA_PROFESSION, profession.id());
        setItemSlot(EquipmentSlot.MAINHAND, profession.tool());
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        if (level() instanceof ServerLevel serverLevel) {
            SettlementManager.noteProfessionChange(serverLevel, this);
        }
    }

    /** Taking up a post: the good day. */
    public void onHired(ServerLevel level, com.hearthstead.settlement.Building building) {
        addMorale(10.0F);
        celebrate();
        level.playSound(null, blockPosition(), ModSounds.PROFESSION_ASSIGNED.get(),
            SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /**
     * Being let go.
     *
     * <p>Dismissal has weight on purpose (PLAN_EMPLOYMENT 3.5). In
     * MineColonies firing is a button with no consequence, which makes
     * managing people feel like editing a spreadsheet. Here it costs the
     * settler morale and they walk out of the building in front of you.
     */
    public void onDismissed(ServerLevel level, com.hearthstead.settlement.Building building) {
        addMorale(-8.0F);
        setActivity(SettlerActivity.IDLE);
        getNavigation().stop();
    }

    /**
     * Fixture helper: appoints a trade directly, with the ceremony.
     *
     * <p>Production code must go through
     * {@link com.hearthstead.settlement.Employment#hire}, which is the only
     * thing that can change who works where. This exists because GameTests
     * need a farmer without first building a farmhouse around them.
     */
    public void assignProfession(Profession profession) {
        setProfessionProjection(profession);
        addMorale(10.0F);
        celebrate();
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, blockPosition(), ModSounds.PROFESSION_ASSIGNED.get(),
                SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }

    /**
     * The lumberjack stoops for the log they just felled.
     *
     * <p>Triggered at the moment the log comes down, not on a timer, so the
     * stoop always lands on a log that actually exists.
     */
    /** A sergeant's leap. One-shot, expiring on its own clock. */
    public void triggerLeapStrike() {
        leapState.start(tickCount);
    }

    public void triggerGatherLog() {
        if (!level().isClientSide) {
            setActivity(SettlerActivity.GATHERING_LOG);
        }
        gatherState.start(tickCount);
    }

    public void celebrate() {
        if (!level().isClientSide && celebrateBroadcastIn < 0 && celebrateAge < 0) {
            // Catalogue §14: stagger celebration starts by up to 20 ticks per
            // settler so village-wide cheers overlap instead of chorusing.
            celebrateBroadcastIn = getId() % 20;
        }
    }

    // ------------------------------------------------------------- needs ---

    /** Rough day schedule; guards ignore the REST phase. */
    /**
     * The five numbers. Rolled lazily so a settler summoned by a test or a
     * command is never without them.
     */
    public SettlerAttributes attributes() {
        if (attributes == null) {
            attributes = SettlerAttributes.roll(getRandom());
            applySlowStart();
        }
        return attributes;
    }

    public java.util.EnumSet<Trait> traits() {
        if (traits.isEmpty()) {
            traits = Trait.roll(getRandom());
        }
        return traits;
    }

    public int attribute(Attribute attribute) {
        return attributes().get(attribute);
    }

    /**
     * Does work that trains an attribute.
     *
     * <p>Call this from a work goal when an action <i>completes</i> — a chop
     * landed, a delivery made, a blow struck. Never on a timer: a settler
     * should get stronger because they did the work, not because they stood in
     * the right room, and "learning by doing" is only true if doing is what is
     * counted.
     */
    public void train(Attribute attribute, float units) {
        float boost = Trait.growth(traits());
        // A clever mayor makes the whole settlement quicker to learn
        // (Mayor.Boon.GOOD_COUNSEL) -- the one compounding thing a mayor does.
        Settlement s = settlement();
        if (s != null && level() instanceof ServerLevel serverLevel) {
            boost *= com.hearthstead.settlement.Mayor.growth(serverLevel, s);
        }
        attributes().train(attribute, units, boost);
    }

    /** QUICK_STUDY buys its growth with a point off every attribute. */
    private void applySlowStart() {
        if (!Trait.any(traits(), Trait.Flag.SLOW_START)) {
            return;
        }
        attributes.penalise(1);
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation
            createNavigation(net.minecraft.world.level.Level level) {
        return new com.hearthstead.entity.path.RoadNavigation(this, level);
    }

    /**
     * Whether this settler will go out of their way to stay on a path.
     *
     * <p>Everyone does, except while there is something to fight or flee.
     * Nobody follows the road with a raider in the wheat, and a settler who
     * detoured along a path while running for their life would look ridiculous
     * — so combat and panic take the straight line.
     */
    public boolean prefersRoads() {
        return getTarget() == null
            && getActivity() != SettlerActivity.COMBAT
            && getActivity() != SettlerActivity.FLEEING;
    }

    /** The village clock. One rhythm for everyone — see {@link DayPhase}. */
    public DayPhase dayPhase() {
        return DayPhase.of(level().getDayTime());
    }

    private void tickNeeds() {
        SettlerActivity activity = getActivity();
        boolean working = activity == SettlerActivity.WORK_FARM
            || activity == SettlerActivity.WORK_CHOP
            || activity == SettlerActivity.WORK_PLANT
            || activity == SettlerActivity.WORK_HARVEST
            || activity == SettlerActivity.WORK_WATER
            || activity == SettlerActivity.WORK_LIMB
            || activity == SettlerActivity.HAULING_LOG
            || activity == SettlerActivity.PATROLLING
            || activity == SettlerActivity.COMBAT
            || activity == SettlerActivity.WORK_KNEAD
            || activity == SettlerActivity.WORK_CLEAVE
            || activity == SettlerActivity.WORK_STOKE
            || activity == SettlerActivity.WORK_HAMMER
            || activity == SettlerActivity.WORK_SAW
            || activity == SettlerActivity.WORK_WEAVE
            || activity == SettlerActivity.WORK_OVEN
            || activity == SettlerActivity.WORK_SOW
            || activity == SettlerActivity.WORK_MINE;

        setHunger(getHunger()
            - (working ? 0.10F : 0.04F) * Trait.hunger(traits()));
        // LONG_DAYS: a hardy mayor's settlement tires more slowly.
        float drain = 1.0F;
        Settlement mayorSeat = settlement();
        if (mayorSeat != null && level() instanceof ServerLevel mayorLevel) {
            drain = com.hearthstead.settlement.Mayor.energyDrain(mayorLevel, mayorSeat);
        }
        if (drain != 1.0F && getEnergy() < 100.0F) {
            setEnergy(Math.min(100.0F, getEnergy() + (1.0F - drain) * 0.10F));
        }
        if (activity == SettlerActivity.SLEEPING) {
            // A claimed bed must beat rough hearth-side rest, and a sleeper
            // that cannot regain energy can never satisfy RestAtNightGoal's
            // exit condition -- it would be stuck asleep forever.
            setEnergy(getEnergy() + 1.5F);
        } else if (activity == SettlerActivity.RESTING) {
            setEnergy(getEnergy() + 1.2F);
        } else {
            setEnergy(getEnergy() - (working ? 0.09F : 0.02F));
        }

        float target = 50.0F;
        float hunger = getHunger();
        float energy = getEnergy();
        if (hunger > 60) {
            target += 20;
        } else if (hunger < 20) {
            target -= 30;
        }
        if (energy > 60) {
            target += 15;
        } else if (energy < 15) {
            target -= 15;
        }
        if (getProfession().employed()) {
            target += 5;
        }
        Settlement s = settlement();
        if (s != null) {
            int homeQuality = com.hearthstead.settlement.BuildingManager
                .homeQualityFor(s, claimedBed);
            target += claimedBed != null ? 5 + homeQuality : -5;
        }
        if (s != null && s.alertActive(level().getGameTime())) {
            target -= 20;
        }
        if (hurtTime > 0 || getLastHurtByMob() != null && tickCount - getLastHurtByMobTimestamp() < 100) {
            target -= 15;
        }
        float morale = getMorale();
        addMorale(Mth.clamp(target - morale, -1.0F, 1.0F) * 0.5F);
    }

    // -------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            setupAnimationStates();
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide) {
            if (tickCount % 20 == 0) {
                tickNeeds();
                com.hearthstead.util.QaTrace.record(this);
            }
            tickAccents();
            syncCarryLoad();
            wearPath();
            if (voiceCooldown > 0) {
                voiceCooldown--;
            }
        }
    }

    /**
     * Why this settler last gave up on a route, and when. Transient and
     * diagnostic only: a settler standing still is the hardest kind of bug
     * to see, because "idle" looks identical whether the AI decided to rest
     * or silently failed. KF-014 cost two reproductions for exactly this
     * reason. Never persisted, never read by the simulation.
     */
    private String lastRouteFailure;
    private long lastRouteFailureTick = Long.MIN_VALUE;

    public void recordRouteFailure(String reason) {
        this.lastRouteFailure = reason;
        this.lastRouteFailureTick = level().getGameTime();
    }

    /** Human-readable, for test messages and live inspection. */
    public String routeFailureNote() {
        if (lastRouteFailure == null) {
            return "none";
        }
        return lastRouteFailure + "@" + lastRouteFailureTick;
    }

    /** How many items are on this settler's back right now. */
    public int getCarryLoad() {
        return entityData.get(DATA_CARRY_LOAD);
    }

    /** How many they can carry; the sack's size, raised by upgrades later. */
    public int getCarryCapacity() {
        return Math.max(1, entityData.get(DATA_CARRY_CAPACITY));
    }

    public void setCarryCapacity(int capacity) {
        entityData.set(DATA_CARRY_CAPACITY, Mth.clamp(capacity, 1, BAG_SIZE * 64));
    }

    /** 0 when empty, 1 when full. What the sack's size is drawn from. */
    public float carryFraction() {
        return Mth.clamp((float) getCarryLoad() / getCarryCapacity(), 0.0F, 1.0F);
    }

    /**
     * Re-reads the bag and publishes the total. Called from the server tick
     * rather than from every site that touches the bag: the bag is eight
     * slots, so a recompute is cheaper than keeping every caller honest, and
     * a missed call would silently desync the sack from the goods.
     */
    /**
     * Counts one footfall where the settler is standing, once per block they
     * walk onto. Gated on the position actually changing so a sleeper never
     * digs a track under their own bed.
     */
    private void wearPath() {
        BlockPos here = blockPosition();
        if (here.equals(lastFootfall)) {
            return;
        }
        lastFootfall = here;
        if (onGround() && level() instanceof ServerLevel serverLevel) {
            com.hearthstead.entity.path.PathWear.step(serverLevel, this);
        }
    }

    private void syncCarryLoad() {
        int total = 0;
        for (int i = 0; i < bag.getContainerSize(); i++) {
            total += bag.getItem(i).getCount();
        }
        if (entityData.get(DATA_CARRY_LOAD) != total) {
            entityData.set(DATA_CARRY_LOAD, total);
            applyCarrySlow();
        }
    }

    /**
     * Slows the settler in proportion to what is on their back. Applied as a
     * transient attribute modifier so it affects every kind of movement --
     * fleeing included -- rather than only the speed a work goal happens to
     * ask for, and so it disappears cleanly the moment the sack is emptied.
     */
    private void applyCarrySlow() {
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(CARRY_SLOW_ID);
        float fill = carryFraction();
        if (fill > 0.0F) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(
                CARRY_SLOW_ID, -MAX_CARRY_SLOW * fill,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private void setupAnimationStates() {
        SettlerActivity activity = getActivity();
        boolean moving = walkAnimation.speed() > 0.05F;

        idleState.animateWhen(!moving
            && (activity == SettlerActivity.IDLE || activity == SettlerActivity.EATING
                || activity == SettlerActivity.CELEBRATING), tickCount);
        farmState.animateWhen(activity == SettlerActivity.WORK_FARM && !moving, tickCount);
        chopState.animateWhen(activity == SettlerActivity.WORK_CHOP && !moving, tickCount);
        eatState.animateWhen(activity == SettlerActivity.EATING, tickCount);
        restState.animateWhen(activity == SettlerActivity.RESTING && !isSleeping(),
            tickCount);
        stanceState.animateWhen((activity == SettlerActivity.PATROLLING
            || activity == SettlerActivity.COMBAT) && !moving, tickCount);

        // SLICE ANIM-1 additions.
        plantState.animateWhen(activity == SettlerActivity.WORK_PLANT && !moving, tickCount);
        harvestState.animateWhen(activity == SettlerActivity.WORK_HARVEST && !moving, tickCount);
        waterState.animateWhen(activity == SettlerActivity.WORK_WATER && !moving, tickCount);
        limbState.animateWhen(activity == SettlerActivity.WORK_LIMB && !moving, tickCount);
        haulState.animateWhen(activity == SettlerActivity.HAULING_LOG && moving, tickCount);
        patrolState.animateWhen(activity == SettlerActivity.PATROLLING && moving, tickCount);
        sleepState.animateWhen(activity == SettlerActivity.SLEEPING, tickCount);
        climbState.animateWhen(onClimbable(), tickCount);
        carryState.animateWhen(activity == SettlerActivity.CARRYING, tickCount);
        sortState.animateWhen(activity == SettlerActivity.SORTING && !moving, tickCount);

        // CHAINS-1: stationary craft loops, the same gate as chopState above --
        // a crafter who is walking is not at their bench.
        kneadState.animateWhen(activity == SettlerActivity.WORK_KNEAD && !moving, tickCount);
        cleaveState.animateWhen(activity == SettlerActivity.WORK_CLEAVE && !moving, tickCount);
        stokeState.animateWhen(activity == SettlerActivity.WORK_STOKE && !moving, tickCount);
        hammerState.animateWhen(activity == SettlerActivity.WORK_HAMMER && !moving, tickCount);
        sawState.animateWhen(activity == SettlerActivity.WORK_SAW && !moving, tickCount);
        fineWorkState.animateWhen(activity == SettlerActivity.WORK_WEAVE && !moving, tickCount);
        ovenState.animateWhen(activity == SettlerActivity.WORK_OVEN && !moving, tickCount);
        sowState.animateWhen(activity == SettlerActivity.WORK_SOW && !moving, tickCount);
        mineState.animateWhen(activity == SettlerActivity.WORK_MINE && !moving, tickCount);
        // GATHER_LOG is a one-shot: triggered when a log actually comes down,
        // and expiring on its own clock like CELEBRATE does.
        if (leapState.isStarted() && leapState.getAccumulatedTime() > 1350L) {
            leapState.stop();
        }
        if (gatherState.isStarted() && gatherState.getAccumulatedTime() > 1150L) {
            gatherState.stop();
            // Hand the settler back to their goal. Without this the activity
            // stays GATHERING_LOG for as long as nothing else happens to set
            // it, and the lumberjack reads as frozen mid-stoop.
            if (getActivity() == SettlerActivity.GATHERING_LOG) {
                setActivity(SettlerActivity.IDLE);
            }
        }

        // One-shots expire on their own clock.
        if (meleeState.isStarted() && meleeState.getAccumulatedTime() > 500L) {
            meleeState.stop();
        }
        if (celebrateState.isStarted() && celebrateState.getAccumulatedTime() > 2100L) {
            celebrateState.stop();
        }
        // Reflexive block: only the impact portion of the loop plays.
        if (shieldState.isStarted() && shieldState.getAccumulatedTime() > 300L) {
            shieldState.stop();
        }
        if (wakeState.isStarted() && wakeState.getAccumulatedTime() > 2600L) {
            wakeState.stop();
        }
        // COURIER_LIFT is 1.40 s, COURIER_SET_DOWN is 1.20 s (catalogue
        // §5.1/§5.3); both are one-shots and must release the parts they
        // own back to the carry/idle pose when they expire.
        if (liftState.isStarted() && liftState.getAccumulatedTime() > 1400L) {
            liftState.stop();
        }
        if (setDownState.isStarted() && setDownState.getAccumulatedTime() > 1200L) {
            setDownState.stop();
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EV_CELEBRATE) {
            celebrateState.start(tickCount);
        } else if (id == EV_MELEE) {
            meleeState.start(tickCount);
        } else if (id == EV_SHIELD_BLOCK) {
            shieldState.start(tickCount);
        } else if (id == EV_WAKE) {
            wakeState.start(tickCount);
        } else if (id == EV_COURIER_LIFT) {
            liftState.start(tickCount);
        } else if (id == EV_COURIER_SET_DOWN) {
            setDownState.start(tickCount);
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        level().broadcastEntityEvent(this, EV_MELEE);
        boolean hit = super.doHurtTarget(target);
        if (hit && !level().isClientSide) {
            // MELEE's contact accent -- damage is applied synchronously in
            // vanilla's doHurtTarget, so "the same tick the server applies
            // damage" per the catalogue is literally this tick.
            level().playSound(null, blockPosition(), ModSounds.BLADE_HIT.get(),
                SoundSource.NEUTRAL, 0.85F, 0.95F + random.nextFloat() * 0.1F);
        }
        return hit;
    }

    /** Fired from CourierWorkGoal when the load is gripped and lifted. */
    public void triggerCourierLift() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_COURIER_LIFT);
        }
    }

    /** Fired from CourierWorkGoal when the load is set down at the warehouse. */
    public void triggerCourierSetDown() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_COURIER_SET_DOWN);
        }
    }

    /** Fired from RestAtNightGoal when a sleeping settler naturally wakes. */
    public void triggerWakeStretch() {
        if (!level().isClientSide && wakeBroadcastIn < 0) {
            // Catalogue §16.2: stagger village wake events >= 8 ticks per
            // settler so the yawns land as a morning murmur, not in unison.
            wakeBroadcastIn = (getId() % 5) * 8;
        }
    }

    /**
     * Server-side accent scheduler: fires staggered one-shot broadcasts and
     * the delayed sound accents tied to their clips, plus the cycle-locked
     * accents of locomotion clips that have no server goal (climb, limp).
     */
    private void tickAccents() {
        if (wakeBroadcastIn >= 0 && wakeBroadcastIn-- == 0) {
            level().broadcastEntityEvent(this, EV_WAKE);
            wakeYawnIn = WAKE_YAWN_TICK;
        }
        if (wakeYawnIn >= 0 && wakeYawnIn-- == 0) {
            playAccent(ModSounds.YAWN.get(), 0.9F, 0.95F + random.nextFloat() * 0.1F);
        }
        if (shieldThudIn >= 0 && shieldThudIn-- == 0) {
            playAccent(ModSounds.SHIELD_THUD.get(), 1.0F, 0.95F + random.nextFloat() * 0.1F);
        }
        if (celebrateBroadcastIn >= 0 && celebrateBroadcastIn-- == 0) {
            level().broadcastEntityEvent(this, EV_CELEBRATE);
            celebrateAge = 0;
        }
        if (celebrateAge >= 0) {
            if (celebrateAge == CHEER_TICK_A || celebrateAge == CHEER_TICK_B) {
                playAccent(ModSounds.CHEER.get(), 0.9F, 0.9F + random.nextFloat() * 0.2F);
            }
            if (++celebrateAge > 40) {
                celebrateAge = -1; // CELEBRATE is a 2.0 s one-shot
            }
        }
        if (onClimbable() && getDeltaMovement().y * getDeltaMovement().y > 1.0E-4) {
            int phase = tickCount % LADDER_CREAK_PERIOD;
            if (phase == LADDER_CREAK_TICK_A || phase == LADDER_CREAK_TICK_B) {
                playAccent(ModSounds.LADDER_CREAK.get(), 0.4F,
                    1.0F + (random.nextFloat() - 0.5F) * 0.3F);
            }
        }
        // WALK_LIMP is health-driven on the client (no enum value); mirror
        // its trigger here. A grunt every third 28-tick cycle, not every
        // step -- on every step it is comedy, on every third it is pain.
        if (getHealth() < getMaxHealth() * 0.4F
            && getDeltaMovement().horizontalDistanceSqr() > 1.0E-4
            && tickCount % LIMP_GRUNT_MOD == LIMP_GRUNT_TICK) {
            playAccent(ModSounds.SETTLER_HM.get(), 0.9F, 0.8F);
        }
    }

    private void playAccent(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        level().playSound(null, getX(), getY(), getZ(), sound,
            SoundSource.NEUTRAL, volume, pitch);
    }

    // ------------------------------------------------------- interaction ---

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (voiceCooldown <= 0) {
                level().playSound(null, blockPosition(), ModSounds.SETTLER_HM.get(),
                    SoundSource.NEUTRAL, 0.9F, 0.95F + random.nextFloat() * 0.1F);
                voiceCooldown = 100;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                serverPlayer, new OpenSettlerScreenPayload(getId()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ------------------------------------------------------------ combat ---

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !level().isClientSide
            && source.getEntity() instanceof LivingEntity attacker
            && attacker instanceof Enemy
            && level() instanceof ServerLevel serverLevel) {
            Settlement s = SettlementManager.byId(serverLevel,
                settlementId != null ? settlementId : targetSettlementId);
            if (s != null) {
                SettlementManager.raiseAlert(serverLevel, s, attacker.blockPosition());
            }
            // SHIELD_BLOCK's reflexive-hit-react: a guard mid-combat flinches
            // behind the shield for the impact. The "hold" trigger (command
            // wheel) doesn't exist yet -- A3.
            if (getProfession() == Profession.GUARD && getActivity() == SettlerActivity.COMBAT) {
                level().broadcastEntityEvent(this, EV_SHIELD_BLOCK);
                // Catalogue §4.4: the thud lands on the tick the block is
                // registered, 2 ticks after the event.
                shieldThudIn = SHIELD_THUD_DELAY;
            }
        }
        return result;
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (level() instanceof ServerLevel serverLevel) {
            // Item conservation: the carried bag is physically real. Before
            // couriers, losing ~8 wheat on death was cosmetic; once couriers
            // haul real goods it becomes a raid-driven item sink -- drop it.
            for (int i = 0; i < bag.getContainerSize(); i++) {
                ItemStack stack = bag.removeItemNoUpdate(i);
                if (!stack.isEmpty()) {
                    serverLevel.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                        serverLevel, getX(), getY() + 0.3, getZ(), stack));
                }
            }
            if (settlementId != null) {
                SettlementManager.onSettlerDied(serverLevel, this);
            }
        }
    }

    // ------------------------------------------------------- persistence ---

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Profession", getProfession().id());
        tag.putFloat("Hunger", getHunger());
        tag.putFloat("Energy", getEnergy());
        tag.putFloat("Morale", getMorale());
        tag.putInt("Appearance", getAppearanceSeed());
        tag.putBoolean("Traveler", traveler);
        tag.put("Attributes", attributes().save());
        net.minecraft.nbt.ListTag traitTag = new net.minecraft.nbt.ListTag();
        for (String key : Trait.keys(traits())) {
            traitTag.add(net.minecraft.nbt.StringTag.valueOf(key));
        }
        tag.put("Traits", traitTag);
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
        if (targetSettlementId != null) {
            tag.putUUID("TargetSettlementId", targetSettlementId);
        }
        if (hearthPos != null) {
            tag.put("HearthPos", NbtUtils.writeBlockPos(hearthPos));
        }
        if (claimedBed != null) {
            tag.put("ClaimedBed", NbtUtils.writeBlockPos(claimedBed));
        }
        tag.put("Bag", bag.createTag(registryAccess()));
        tag.putInt("CarryCapacity", getCarryCapacity());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        Profession profession = Profession.byId(tag.getByte("Profession"));
        entityData.set(DATA_PROFESSION, profession.id());
        setHunger(tag.getFloat("Hunger"));
        setEnergy(tag.getFloat("Energy"));
        entityData.set(DATA_MORALE, Mth.clamp(tag.getFloat("Morale"), 0.0F, 100.0F));
        // Pre-VISUAL-1 saves have no "Appearance" key; fall back to a seed
        // derived from the settler's own UUID (deterministic, not salted --
        // unlike Python's hash(), UUID.hashCode() is a pure function of the
        // UUID bits) rather than leaving every such settler at seed 0.
        entityData.set(DATA_APPEARANCE_SEED,
            tag.contains("Appearance") ? tag.getInt("Appearance") : getUUID().hashCode());
        traveler = tag.getBoolean("Traveler");
        // Traits first: rolling attributes consults SLOW_START.
        java.util.List<String> traitKeys = new java.util.ArrayList<>();
        net.minecraft.nbt.ListTag storedTraits = tag.getList("Traits", 8);
        for (int i = 0; i < storedTraits.size(); i++) {
            traitKeys.add(storedTraits.getString(i));
        }
        traits = traitKeys.isEmpty() ? Trait.roll(getRandom())
            : Trait.fromKeys(traitKeys);
        attributes = SettlerAttributes.load(tag.getCompound("Attributes"), getRandom());
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        targetSettlementId = tag.hasUUID("TargetSettlementId")
            ? tag.getUUID("TargetSettlementId") : null;
        hearthPos = NbtUtils.readBlockPos(tag, "HearthPos").orElse(null);
        claimedBed = NbtUtils.readBlockPos(tag, "ClaimedBed").orElse(null);
        bag.fromTag(tag.getList("Bag", 10), registryAccess());
        setCarryCapacity(tag.contains("CarryCapacity")
            ? tag.getInt("CarryCapacity") : BASE_CARRY_CAPACITY);
        syncCarryLoad();
    }

    /** Idempotent re-registration protects against lost records; called from
     *  EntityJoinLevelEvent so it works on every loader. */
    public void reRegisterWithSettlement() {
        if (level() instanceof ServerLevel serverLevel && settlementId != null) {
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s != null) {
                s.putRecord(getUUID(), getSettlerName(), getProfession());
                SettlementManager.data(serverLevel).setDirty();
            }
        }
    }

    @Nullable
    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
        net.minecraft.world.level.ServerLevelAccessor level, DifficultyInstance difficulty,
        net.minecraft.world.entity.MobSpawnType spawnType,
        @Nullable net.minecraft.world.entity.SpawnGroupData groupData) {
        if (getCustomName() == null) {
            setSettlerName(com.hearthstead.settlement.SettlerNames.pickSettlerName(
                level.getRandom(), java.util.Collections.emptySet()));
        }
        return super.finalizeSpawn(level, difficulty, spawnType, groupData);
    }

    // ------------------------------------------------------------- audio ---

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 600;
    }
}

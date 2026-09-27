package com.hearthstead.entity;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.work.WorkerLifecycle;
import com.hearthstead.entity.combat.CinematicOpportunity;
import com.hearthstead.entity.combat.GuardMove;
import com.hearthstead.entity.ai.BoundedStrollGoal;
import com.hearthstead.entity.ai.AcquireRequestedEquipmentGoal;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.entity.ai.EatFromHearthGoal;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.entity.ai.FisherWorkGoal;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.GuardPatrolGoal;
import com.hearthstead.entity.ai.GuardRaidEscortGoal;
import com.hearthstead.entity.ai.GuardRespondToAlertGoal;
import com.hearthstead.entity.ai.HerderWorkGoal;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.entity.ai.LumbererSelfCraftGoal;
import com.hearthstead.entity.ai.LumbererWorkGoal;
import com.hearthstead.entity.ai.RestAtNightGoal;
import com.hearthstead.entity.ai.ReturnToSettlementGoal;
import com.hearthstead.entity.ai.SettlerDefenseTargetGoal;
import com.hearthstead.entity.ai.SettlerPanicGoal;
import com.hearthstead.entity.ai.TravelerJoinGoal;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.network.OpenSettlerScreenPayload;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.BlessingPresentation;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.util.AuthorityTelemetry;
import com.hearthstead.entity.ai.GoToPostGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
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
import com.hearthstead.entity.ai.SettlerDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SettlerEntity extends PathfinderMob {
    private final WorkerLifecycle workerLifecycle = new WorkerLifecycle();
    private final ResidentMeal residentMeal = new ResidentMeal();
    private final InnkeeperAtmosphere innkeeperAtmosphere = new InnkeeperAtmosphere();
    private static final EntityDataAccessor<CompoundTag> DATA_INNKEEPER_SOCIAL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Ephemeral, server-authored cue for an optional village moment. */
    private static final EntityDataAccessor<CompoundTag> DATA_VILLAGE_SOCIAL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Tavern lane: drunkenness level 0-3 (Drunkenness) and its visual cue (mode + start tick). */
    private static final EntityDataAccessor<Byte> DATA_DRUNK_LEVEL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<CompoundTag> DATA_DRUNK_CUE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private double drunkPoints;
    private long drunkUpdated = Long.MIN_VALUE, nextStumble, nextFall, nextStop, drunkPauseUntil;
    private float drunkLeanYaw = Float.NaN;
    private static final EntityDataAccessor<Long> DATA_TAVERN_MUSIC_UNTIL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_MORALE_JOY_UNTIL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.LONG);
    private long nextMoraleJoy;
    /** Synced {@link LifeNeed} code for the thought bubble (presentation only). */
    private static final EntityDataAccessor<Byte> DATA_LIFE_NEED =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    /** Transient: a resident stays shaken briefly after a raid/alarm ends. */
    private long frightenedUntil = Long.MIN_VALUE;
    private static final EntityDataAccessor<CompoundTag> DATA_TAVERN_CUE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Guard Drill lane: the morning sparring cue (GuardDrillYard.Assignment#cue); render only. */
    private static final EntityDataAccessor<CompoundTag> DATA_GUARD_DRILL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Synced drill cue: Mode (GuardDrillYard.MODE_*), Start, Seed, Slot, Len; empty = none. */
    public CompoundTag guardDrillCue() { return entityData.get(DATA_GUARD_DRILL); }
    /** Server: set or clear (empty tag) the drill cue. */
    public void setGuardDrillCue(CompoundTag cue) {
        if (level().isClientSide) return;
        if (cue.isEmpty() && entityData.get(DATA_GUARD_DRILL).isEmpty()) return;
        entityData.set(DATA_GUARD_DRILL, cue);
    }

    public TavernCue tavernCue() {
        CompoundTag cue = entityData.get(DATA_TAVERN_CUE);
        long remaining = cue.getLong("Until") - level().getGameTime();
        return isAlive() && remaining > 0 && remaining <= 160
            ? TavernCue.decode(cue.getString("Stage")) : TavernCue.NONE;
    }
    public int tavernQuotedCoins() { return Mth.clamp(entityData.get(DATA_TAVERN_CUE).getInt("Coins"), 0, 64); }
    /** Short, deduplicated cue. Persistence and all decisions remain with the order owner. */
    public void showTavernCue(TavernCue stage, int coins, int durationTicks) {
        if (level().isClientSide) return;
        if (stage == TavernCue.NONE) {
            if (!entityData.get(DATA_TAVERN_CUE).isEmpty()) entityData.set(DATA_TAVERN_CUE, new CompoundTag());
            return;
        }
        int duration = Mth.clamp(durationTicks, 1, 160);
        CompoundTag current = entityData.get(DATA_TAVERN_CUE);
        if (stage.name().equals(current.getString("Stage")) && coins == current.getInt("Coins")
            && current.getLong("Until") - level().getGameTime() > 20) return;
        CompoundTag cue = new CompoundTag();
        cue.putString("Stage", stage.name()); cue.putInt("Coins", Mth.clamp(coins, 0, 64));
        cue.putLong("Until", level().getGameTime() + duration);
        entityData.set(DATA_TAVERN_CUE, cue);
    }

    public boolean tavernMusicActive() {
        return isAlive() && getProfession() == Profession.INNKEEPER
            && entityData.get(DATA_TAVERN_MUSIC_UNTIL) > level().getGameTime();
    }
    public void setTavernMusicUntil(long until) {
        if (!level().isClientSide) entityData.set(DATA_TAVERN_MUSIC_UNTIL, until);
    }
    public int moraleJoyTicksRemaining() {
        return (int) Mth.clamp(entityData.get(DATA_MORALE_JOY_UNTIL) - level().getGameTime(), 0L, 40L);
    }

    public InnkeeperAtmosphere innkeeperAtmosphere() { return innkeeperAtmosphere; }
    public long innkeeperSocialStart() { return entityData.get(DATA_INNKEEPER_SOCIAL).getLong("Start"); }
    public int innkeeperSocialMode() {
        int mode = entityData.get(DATA_INNKEEPER_SOCIAL).getInt("Mode");
        long age = level().getGameTime() - innkeeperSocialStart();
        return isAlive() && age >= 0 && age < InnkeeperAtmosphere.duration(mode) ? mode : 0;
    }
    public void setInnkeeperSocial(int mode, long started) {
        if (level().isClientSide) return;
        if (mode == 0 && entityData.get(DATA_INNKEEPER_SOCIAL).isEmpty()) return;
        CompoundTag cue = new CompoundTag();
        if (InnkeeperAtmosphere.duration(mode) > 0) {
            cue.putInt("Mode", mode);
            cue.putLong("Start", started);
        }
        entityData.set(DATA_INNKEEPER_SOCIAL, cue);
    }
    public long villageSocialStart() { return entityData.get(DATA_VILLAGE_SOCIAL).getLong("Start"); }
    public int villageSocialMode() {
        CompoundTag cue = entityData.get(DATA_VILLAGE_SOCIAL);
        int mode = cue.getInt("Mode");
        long age = level().getGameTime() - villageSocialStart();
        // A shared talking pair (SocialPair) carries its own length: turns x turn ticks.
        int duration = mode == VillageSocial.PAIR
            ? Math.max(0, cue.getInt("Turns")) * Math.max(0, cue.getInt("TurnTicks"))
            : VillageSocial.duration(mode);
        return isAlive() && age >= 0 && age < duration ? mode : VillageSocial.NONE;
    }
    /** Synced SocialPair cue (tavern lane): the partner's entity id, or -1. */
    public int socialPairPartnerId() {
        CompoundTag cue = entityData.get(DATA_VILLAGE_SOCIAL);
        return cue.getInt("Mode") == VillageSocial.PAIR && cue.contains("Partner", 3) ? cue.getInt("Partner") : -1;
    }
    /** 0 = speaks on even turns, 1 = speaks on odd turns. */
    public int socialPairSlot() { return entityData.get(DATA_VILLAGE_SOCIAL).getInt("Slot"); }
    public int socialPairTurnTicks() { return entityData.get(DATA_VILLAGE_SOCIAL).getInt("TurnTicks"); }
    /** Server: one SocialPair cue (see SocialPair.start). A render cue only, like setVillageSocial. */
    public int socialPairSeed() { return entityData.get(DATA_VILLAGE_SOCIAL).getInt("Seed"); }
    public void setSocialPairCue(int partnerId, int slot, long started, int turnTicks, int turns) {
        setSocialPairCue(partnerId, slot, started, turnTicks, turns, (int) started);
    }
    public void setSocialPairCue(int partnerId, int slot, long started, int turnTicks, int turns, int seed) {
        if (level().isClientSide) return;
        CompoundTag cue = new CompoundTag();
        cue.putInt("Mode", VillageSocial.PAIR);
        cue.putLong("Start", started);
        cue.putInt("Partner", partnerId);
        cue.putInt("Slot", slot & 1);
        cue.putInt("TurnTicks", turnTicks);
        cue.putInt("Turns", turns);
        cue.putInt("Seed", seed);
        entityData.set(DATA_VILLAGE_SOCIAL, cue);
    }
    /** Tavern lane: synced drunkenness level (Drunkenness.SOBER..VERY). */
    public int drunkLevel() { return isAlive() ? entityData.get(DATA_DRUNK_LEVEL) : 0; }
    public boolean isTipsy() { return drunkLevel() >= Drunkenness.TIPSY; }
    public double drunkPoints() { return drunkPoints; }
    public int drunkCueMode() { return entityData.get(DATA_DRUNK_CUE).getInt("Mode"); }
    public long drunkCueStart() { return entityData.get(DATA_DRUNK_CUE).getLong("Start"); }
    /** Server: a real ale was drunk (TavernServingEntity DRINKING sip). The ticks argument is kept
     *  for callers; the wear-off comes from Drunkenness.DECAY_TICKS per level. */
    public void markAleDrunk(long now, int ticks) {
        if (level().isClientSide || !(level() instanceof net.minecraft.server.level.ServerLevel server)
            || !Drunkenness.enabledIn(server) || !Drunkenness.mayGetDrunk(this)) return;
        tickDrunkDecay(now);
        int before = Drunkenness.level(drunkPoints);
        drunkPoints = Drunkenness.addAle(drunkPoints);
        int after = Drunkenness.level(drunkPoints);
        if (after >= Drunkenness.DRUNK && after > before) addMorale(1.5F);
        entityData.set(DATA_DRUNK_LEVEL, (byte) after);
        long seed = Drunkenness.seed(this);
        if (nextStumble < now) nextStumble = now + 120 + (long) (TavernTableMath.hash01(seed, now, 1) * 160);
        if (nextFall < now) nextFall = now + 400 + (long) (TavernTableMath.hash01(seed, now, 2) * 600);
        if (nextStop < now) nextStop = now + 300 + (long) (TavernTableMath.hash01(seed, now, 3) * 600);
    }
    /** Load: saved points minus the wear-off since the stamp; legacy saves (no fields) are sober.
     *  The speed modifier stays transient and is re-applied by tickDrunk. */
    private void readDrunkenness(CompoundTag tag) {
        long now = level().getGameTime();
        drunkPoints = tag.contains("DrunkPoints")
            ? Drunkenness.loadPoints(tag.getDouble("DrunkPoints"),
                tag.contains("DrunkStamp") ? tag.getLong("DrunkStamp") : now, now)
            : 0.0;
        drunkUpdated = drunkPoints > 0.0 ? now : Long.MIN_VALUE;
        entityData.set(DATA_DRUNK_LEVEL, (byte) Drunkenness.level(drunkPoints));
        drunkPauseUntil = 0L;
        nextStumble = now + 120;
        nextFall = now + 400;
        nextStop = now + 300;
    }
    private void tickDrunkDecay(long now) {
        if (drunkUpdated != Long.MIN_VALUE) drunkPoints = Drunkenness.decay(drunkPoints, now - drunkUpdated);
        drunkUpdated = now;
    }
    private void setDrunkCue(int mode, long start) {
        CompoundTag cue = new CompoundTag();
        cue.putInt("Mode", mode);
        cue.putLong("Start", start);
        entityData.set(DATA_DRUNK_CUE, cue);
    }
    /** Server tick: decay, the movement modifier, the weave, and the occasional stumble / stop / fall. */
    private void tickDrunk() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        long now = server.getGameTime();
        if (!Drunkenness.enabledIn(server) || !Drunkenness.mayGetDrunk(this)) {
            if (drunkPoints > 0 || drunkLevel() != 0) { drunkPoints = 0; entityData.set(DATA_DRUNK_LEVEL, (byte) 0); }
            Drunkenness.applySpeed(this, 0);
            return;
        }
        if (drunkPoints <= 0 && drunkLevel() == 0) return;
        boolean sobered = Drunkenness.movementSobered(this)
            || com.hearthstead.conversation.ConversationService.isTalking(this);
        if (tickCount % 20 == 0) {
            tickDrunkDecay(now);
            int lvl = Drunkenness.level(drunkPoints);
            if (lvl != drunkLevel()) entityData.set(DATA_DRUNK_LEVEL, (byte) lvl);
            Drunkenness.applySpeed(this, lvl, sobered);
        }
        int lvl = drunkLevel();
        if (sobered) {
            // an alarm, a target, panic or a conversation: full speed on this very tick
            Drunkenness.applySpeed(this, lvl, true);
            if (now < drunkPauseUntil) { drunkPauseUntil = now; setDrunkCue(Drunkenness.CUE_NONE, now); }
            return;
        }
        if (now < drunkPauseUntil) {
            // a fall / lean / sit holds the settler still (visual only, no damage)
            getNavigation().stop();
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (!Float.isNaN(drunkLeanYaw) && drunkCueMode() == Drunkenness.CUE_LEAN) {
                // DRUNK_LEAN is authored with the wall on the settler's right
                setYRot(drunkLeanYaw); setYBodyRot(drunkLeanYaw); setYHeadRot(drunkLeanYaw);
            }
            return;
        }
        drunkLeanYaw = Float.NaN;
        Drunkenness.weaveStep(this, lvl);
        boolean walking = !getNavigation().isDone() && onGround() && !isPassenger();
        if (!walking) return;
        long seed = Drunkenness.seed(this);
        if (lvl >= Drunkenness.VERY && now >= nextFall && Drunkenness.safeToFall(server, blockPosition())) {
            boolean side = TavernTableMath.hash01(seed, now, 11) < 0.5;
            setDrunkCue(side ? Drunkenness.CUE_FALL_SIDE : Drunkenness.CUE_FALL_FORWARD, now);
            drunkPauseUntil = now + Drunkenness.FALL_TICKS;
            nextFall = now + 800 + (long) (TavernTableMath.hash01(seed, now, 12) * 1000);   // 40-90 s
            nextStumble = Math.max(nextStumble, drunkPauseUntil + 100);
            return;
        }
        if (lvl >= Drunkenness.VERY && now >= nextStop) {
            boolean wall = false;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var p = blockPosition().relative(d);
                var st = server.getBlockState(p);
                var head = server.getBlockState(p.above());
                if (!st.getCollisionShape(server, p).isEmpty() && !head.getCollisionShape(server, p.above()).isEmpty()) {
                    wall = true;
                    drunkLeanYaw = net.minecraft.util.Mth.wrapDegrees(d.toYRot() - 90.0F);   // wall on the right
                    break;
                }
            }
            if (wall || Drunkenness.safeToFall(server, blockPosition())) {
                setDrunkCue(wall ? Drunkenness.CUE_LEAN : Drunkenness.CUE_SIT, now);
                drunkPauseUntil = now + (wall ? Drunkenness.LEAN_TICKS : Drunkenness.SIT_TICKS);
            }
            nextStop = now + 600 + (long) (TavernTableMath.hash01(seed, now, 13) * 600);        // 30-60 s
            return;
        }
        if (lvl >= Drunkenness.DRUNK && now >= nextStumble) {
            setDrunkCue(Drunkenness.CUE_STUMBLE, now);
            nextStumble = now + 160 + (long) (TavernTableMath.hash01(seed, now, 14) * 240);     // 8-20 s
        }
    }
    /** A render cue only; event scheduling and worker authority stay server-side. */
    public void setVillageSocial(int mode, long started) {
        if (level().isClientSide) return;
        if (mode == VillageSocial.NONE && entityData.get(DATA_VILLAGE_SOCIAL).isEmpty()) return;
        CompoundTag cue = new CompoundTag();
        if (VillageSocial.duration(mode) > 0) {
            cue.putInt("Mode", mode);
            cue.putLong("Start", started);
        }
        entityData.set(DATA_VILLAGE_SOCIAL, cue);
    }
    private static final EntityDataAccessor<ItemStack> DATA_RESIDENT_MEAL =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> DATA_MEAL_REMAINING =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);

    /** Takes one item from the LIVE owned source after caller-proven contact. */
    public boolean beginMeal(ItemStack ownedSource) {
        boolean accepted = residentMeal.begin(this, ownedSource);
        if (accepted) syncResidentMeal();
        return accepted;
    }

    public boolean hasMeal() { return mealRemainingTicks() > 0; }
    public ItemStack mealDisplayCopy() { return entityData.get(DATA_RESIDENT_MEAL).copy(); }
    public int mealRemainingTicks() { return entityData.get(DATA_MEAL_REMAINING); }

    /** No movement or seating changes; advances only once per EATING server tick. */
    public boolean tickMeal() {
        boolean completed = residentMeal.tick(this);
        if (!level().isClientSide) syncResidentMeal();
        return completed;
    }

    private void syncResidentMeal() {
        entityData.set(DATA_RESIDENT_MEAL, residentMeal.displayCopy());
        entityData.set(DATA_MEAL_REMAINING, residentMeal.remainingTicks());
    }

    public WorkerLifecycle workerLifecycle() { return workerLifecycle; }
    private static final EntityDataAccessor<Byte> DATA_PROFESSION =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    /** Ephemeral normalized fishing clock: -1 idle, 0..299 one real catch cycle. */
    private static final EntityDataAccessor<Integer> DATA_FISHER_CYCLE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
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
    /** Skins lane: event-visitor costume (entity.look.CharacterLooks.COSTUME_*), 0 = none. */
    private static final EntityDataAccessor<Integer> DATA_LOOK_COSTUME =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /** Server-authored, persisted kill XP; synced only so inspection is live. */
    private static final EntityDataAccessor<Integer> DATA_COMBAT_EXPERIENCE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /** Trade-skill XP for the CURRENT profession (SkillLevels); client derives the level. */
    private static final EntityDataAccessor<Integer> DATA_TRADE_XP =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /** Server-authoritative per-profession trade XP; persisted under SkillLevels.NBT_KEY. */
    private final SkillLevels.Track tradeSkills = new SkillLevels.Track();
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
    /**
     * Presentation-only logistics gear: sack tier (low 4 bits) and Hand Cart
     * (bit 4), packed by {@link com.hearthstead.settlement.development.HaulGear}.
     * Recomputed from settlement ownership on the server; never saved.
     */
    private static final EntityDataAccessor<Integer> DATA_HAUL_GEAR =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /**
     * Presentation-only Gear Tier clearance (role, personal tier, per-tier
     * knowledge bits) and settlement heraldry colours, packed by
     * {@link com.hearthstead.settlement.gear.GearGate}. Recomputed once a
     * second on the server; never saved.
     */
    private static final EntityDataAccessor<Integer> DATA_GEAR_CLEARANCE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HERALDRY =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /**
     * Preferred item id for the current active equipment need, or -1. This is
     * a tiny render/UI projection only; the persistent EquipmentRequest and
     * physical ItemStacks remain the authority.
     */
    private static final EntityDataAccessor<Integer> DATA_REQUESTED_EQUIPMENT =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /**
     * Low 4 bits: {@link StopReason#wireId()}. Remaining bits: whole retry
     * seconds remaining (0..31). An integer keeps the courier's full existing
     * backoff while adding explicit Work Zone stop reasons.
     */
    private static final EntityDataAccessor<Integer> DATA_LOGISTICS_STOP =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.INT);
    /** Plaque or hearth the diagnosis points at; projection only, never NBT. */
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_LOGISTICS_TARGET =
        SynchedEntityData.defineId(SettlerEntity.class,
            EntityDataSerializers.OPTIONAL_BLOCK_POS);
    /**
     * Transient, server-authored position of the lumberer's detached work
     * sack. It is a render projection, not a second inventory: the real bag
     * remains {@link #bag}, while this makes the sack stay in one world spot
     * as the worker walks to each physical dropped log.
     */
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_WORK_CONTAINER_POS =
        SynchedEntityData.defineId(SettlerEntity.class,
            EntityDataSerializers.OPTIONAL_BLOCK_POS);
    /** Kind and position form one validated projection; NONE always means no position. */
    private static final EntityDataAccessor<Byte> DATA_WORK_CONTAINER_KIND =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BYTE);
    /**
     * One bounded, transient render projection for a server-owned craft
     * action. The exact output escrow is persisted separately; clearing this
     * tag may hide props but can never delete an item.
     */
    private static final EntityDataAccessor<CompoundTag> DATA_CRAFT_PRESENTATION =
        SynchedEntityData.defineId(SettlerEntity.class,
            EntityDataSerializers.COMPOUND_TAG);
    /** Exact item/contact clock for one server-owned bag-to-container cycle. */
    private static final EntityDataAccessor<CompoundTag> DATA_BAG_TRANSFER_PRESENTATION =
        SynchedEntityData.defineId(SettlerEntity.class,
            EntityDataSerializers.COMPOUND_TAG);

    public static final byte EV_CELEBRATE = 64;
    public static final byte EV_MELEE = 65;
    public static final byte EV_SHIELD_BLOCK = 66;
    public static final byte EV_WAKE = 67;
    public static final byte EV_COURIER_LIFT = 68;
    public static final byte EV_COURIER_SET_DOWN = 69;
    /** The universal one-shot: any settler stooping to pick something up off
     *  the ground, whatever the trade. PICKUP_STOW (1.40 s) is authored in
     *  parallel; see {@link #triggerPickup()}. */
    public static final byte EV_PICKUP = 70;
    /** The lumberjack's stoop for a felled log. Broadcast, not started
     *  locally -- see {@link #triggerGatherLog()}. */
    public static final byte EV_GATHER_LOG = 71;
    /** A sergeant's leap. Broadcast for the same reason. */
    public static final byte EV_LEAP_STRIKE = 72;
    /** Permanent Blessing accepted: a full-body, event-only one-shot. */
    public static final byte EV_BLESSING_RECEIVE = 73;
    /** Lumberer detaches and puts the work sack on the ground. */
    public static final byte EV_WORK_CONTAINER_DOWN = 74;
    /** Field worker lifts one real ground item into the physical offhand. */
    public static final byte EV_GROUND_ITEM_PICKUP = 75;
    /** Field worker moves the physical offhand item into the placed container. */
    public static final byte EV_WORK_CONTAINER_STOW = 76;
    /** Field worker shoulders or lifts the placed container for transport. */
    public static final byte EV_WORK_CONTAINER_UP = 77;
    /** Bow release follow-through; volley authority remains ArcherAttackGoal. */
    public static final byte EV_ARCHER_LOOSE = 78;
    /** Reviewed bag-to-chest one-shot; inventory commits remain server-owned. */
    public static final byte EV_BAG_TO_CHEST_UNLOAD = 79;
    /** An interruptible low-health finishing drive; damage still uses the normal ticket. */
    public static final byte EV_GUARD_FINISHER = 80;
    /** Stops the optional drive when its target window has been lost. */
    public static final byte EV_GUARD_FINISHER_CANCEL = 81;
    /** Guard moveset: mirrored backhand second combo link (GUARD_LIGHT_SLASH_B). */
    public static final byte EV_GUARD_LIGHT_B = 82;
    /** Guard moveset: telegraphed two-handed overhead chop (GUARD_HEAVY_OVERHEAD). */
    public static final byte EV_GUARD_HEAVY = 83;
    /** Guard moveset: offhand shield punch that interrupts a wind-up (GUARD_SHIELD_BASH). */
    public static final byte EV_GUARD_SHIELD_BASH = 84;
    /** A landed raider heavy rocks the guard (GUARD_STAGGER); strikes stop. */
    public static final byte EV_GUARD_STAGGER = 85;
    /** Battle-role strikes: EV_ROLE_MOVE_BASE + RoleMove.ordinal() (86..91) plays that
     *  move's authored clip (RoleMotionAnimations) on {@link #roleMoveState}. */
    public static final byte EV_ROLE_MOVE_BASE = 86;
    /** Guard comes to attention and salutes (GuardSaluteGoal): 1.8 s clip. */
    public static final byte EV_GUARD_SALUTE = 96;
    /** Captain's small return nod to a saluting guard: 0.7 s clip. */
    public static final byte EV_GUARD_NOD = 97;
    /** Greeting over (GuardSaluteGoal): salute cut away, sword drawn (1.25 s). */
    public static final byte EV_GUARD_SALUTE_END = 98;
    /** Greeting dropped for danger: the presentation snaps back to the drawn sword. */
    public static final byte EV_GUARD_SALUTE_CANCEL = 99;

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
    /** BLESSING_RECEIVE reaches its authored hand-to-heart contact at 0.50 s. */
    public static final int BLESSING_CONTACT_DELAY_TICKS = 10;

    /** Bag capacity: harvested goods carried before a hearth deposit run. */
    public static final int BAG_SIZE = 8;
    /**
     * Exact server-owned arrows currently borrowed from a Watchtower rack.
     * The count is deliberately smaller than an inventory stack and persists
     * with the settler so chunk unload/restart cannot erase physical stock.
     */
    public static final int ARCHER_QUIVER_CAPACITY = 16;
    public static final String ARCHER_QUIVER_NBT_KEY = "ArcherQuiver";
    public static final String ARCHER_QUIVER_SOURCE_NBT_KEY =
        "ArcherQuiverSource";
    /**
     * How much a settler carries on their back before the sack is full.
     * The sack is tier one of a visible capacity mechanic (D-007): a cart
     * raises this later, and both the AI's stop condition and the renderer
     * read this one number so they can never disagree.
     */
    public static final int BASE_CARRY_CAPACITY = 8;
    /** Stable entity-save key for the bounded guard/archer kill-XP counter. */
    public static final String COMBAT_EXPERIENCE_NBT_KEY = "GuardExperience";
    /** Persisted replay-proof identities/revisions for XP and genuine blocks. */
    public static final String COMBAT_LEDGER_NBT_KEY = "GuardCombatLedger";
    /**
     * How much a full sack costs in speed. A load you can carry at full
     * pace is not a load -- the whole point of making capacity visible is
     * that the player can SEE the trade, so it has to cost something in the
     * world and not only in a number.
     */
    public static final float MAX_CARRY_SLOW = 0.38F;
    /** Visual-only haul transitions: five client ticks gives a 0.25 s ease. */
    private static final float HAUL_POSE_BLEND_STEP = 0.20F;
    /** A full sack deflates over eight client ticks instead of vanishing. */
    private static final float VISUAL_LOAD_BLEND_STEP = 0.125F;
    /** Heavy/light is latched when HAULING_LOG begins, never during unload. */
    private static final float HAUL_HEAVY_ENTER_FILL = 0.65F;
    private static final net.minecraft.resources.ResourceLocation CARRY_SLOW_ID =
        com.hearthstead.Hearthstead.id("carry_slow");
    private static final net.minecraft.resources.ResourceLocation FATIGUE_SLOW_ID =
        com.hearthstead.Hearthstead.id("fatigue_slow");

    @Nullable
    private UUID settlementId;
    @Nullable
    private UUID targetSettlementId;
    @Nullable
    private BlockPos hearthPos;
    /** Decoded once per synced-tag change, never rebuilt every render frame. */
    private CraftPresentation craftPresentation = CraftPresentation.empty();
    private BagTransferPresentation bagTransferPresentation =
        BagTransferPresentation.empty();
    /** Sole durable item owner from recipe contact until container contact. */
    @Nullable
    private CraftOutputEscrow craftOutputEscrow;
    @Nullable
    private BlockPos claimedBed;
    private static final EntityDataAccessor<Boolean> DATA_TRAVELER_APPEARANCE =
        SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.BOOLEAN);
    private boolean traveler;
    /** Rolled once, then earned. See {@link SettlerAttributes}. */
    /** Last block a footfall was counted on; see {@link #wearPath()}. */
    private BlockPos lastFootfall;
    private SettlerAttributes attributes;
    /** Last whole-percent Work Pace applied to movement; avoids modifier churn. */
    private int appliedFatiguePacePercent = -1;
    /** The daily labor pool every trade spends against. See {@link Effort}. */
    private Effort effort;
    /** What they are like, and what it costs them. See {@link Trait}. */
    private java.util.EnumSet<Trait> traits = java.util.EnumSet.noneOf(Trait.class);
    /** Entity-owned marker that distinguishes pre-feature absence from loss. */
    public static final String TARGET_BLESSINGS_SCHEMA_KEY =
        "TargetBlessingsSchema";
    public static final int TARGET_BLESSINGS_SCHEMA_VERSION = 1;
    /** Permanent per-settler Blessings; compact server-only state, never tick-scanned. */
    private TargetBlessingState targetBlessings = new TargetBlessingState();
    public final SimpleContainer bag = new SimpleContainer(BAG_SIZE);
    /**
     * Stout Straps batching (CourierBatching): the cargo of a Courier's
     * second, waiting request while the other one runs. Real items, saved as
     * "BatchStow", dropped on death like the bag; only RequestLedgerService
     * moves items in or out, so every other reader keeps "bag = active load".
     */
    public final SimpleContainer batchStow = new SimpleContainer(BAG_SIZE);
    /** Server-only physical ownership; never a client animation counter. */
    private int archerQuiver;
    /**
     * Exact Watchtower whose rack supplied {@link #archerQuiver}. Count and
     * source are one persisted ownership fact: a reassigned Archer may not
     * carry Tower A's readiness proof into Tower B.
     */
    @Nullable
    private UUID archerQuiverSource;
    private int voiceCooldown;
    /** The last {@link GuardRank} this settler was actually dressed for, so
     *  {@link #tickGuardEquipment()} can change-detect instead of re-setting
     *  four item slots every refresh. {@code null} means "never applied" --
     *  distinct from {@link GuardRank#RECRUIT}, which is a real rank with its
     *  own (empty) equipment, so a freshly-hired guard still gets one real
     *  application rather than being silently skipped because RECRUIT looks
     *  like "nothing to do". */
    @Nullable
    private GuardRank lastAppliedGuardRank;

    // Server-authoritative transient logistics diagnosis. The client sees a
    // throttled projection through DATA_LOGISTICS_STOP/TARGET; none of this
    // is persisted because it must be re-derived from today's real chests.
    private StopReason logisticsStopReason = StopReason.NONE;
    @Nullable
    private BlockPos logisticsStopTarget;
    private long logisticsRetryUntil = Long.MIN_VALUE;
    /** Absolute server game-time through which a civilian must remain at an arrived home. */
    private long panicShelterUntil = Long.MIN_VALUE;
    /**
     * Runtime-only handoff from a bounded workplace route to the door goal.
     * The position is never saved or synced: it grants no durable authority
     * and must be re-proven by the caller after a reload or failed attempt.
     */
    @Nullable
    private BlockPos requestedDoorPassage;

    // Server-side accent scheduler: countdowns to staggered one-shot
    // broadcasts and their delayed sound accents (-1 = idle). One-shot
    // variation must stagger the TRIGGER tick, never the clip's sampled
    // time -- offsetting a one-shot's ageInTicks truncates it.
    private int wakeBroadcastIn = -1;
    private int wakeYawnIn = -1;
    private int shieldThudIn = -1;
    /** Runtime-only QA projection; never combat authority and never persisted. */
    private long shieldBlockPresentationSequence;
    /** Runtime-only proof that the standard OFFHAND break callback fired. */
    private long shieldBreakEventSequence;
    private int celebrateBroadcastIn = -1;
    private int celebrateAge = -1;
    /**
     * Rare, runtime-only APPLIED cues. The permanent target state permits at
     * most nine successful bindings total, so this lazy queue is intrinsically
     * bounded and costs nothing for an unblessed settler.
     */
    @Nullable
    private ArrayDeque<ScheduledBlessingCue> pendingBlessingCues;
    /**
     * Runtime-only combat authority. The EV_MELEE one-shot starts when this
     * ledger issues a ticket; only that ticket can authorize the authored
     * contact four ticks later. It is intentionally absent from NBT.
     */
    private final MeleeContactLedger meleeContacts = new MeleeContactLedger();
    /** One runtime-only optional finisher bound to one existing melee ticket. */
    private long cinematicFinisherTicket;
    private UUID cinematicFinisherTargetId;
    /** Runtime-only move bound to the one live melee ticket; never persisted. */
    private GuardMove pendingGuardMove;
    private long pendingGuardMoveTicket;
    private UUID pendingGuardMoveTargetId;
    private long pendingGuardMoveContactTick = Long.MIN_VALUE;
    /** Server game time until which a raider heavy keeps this guard staggered. */
    private long combatStaggerUntil = Long.MIN_VALUE;
    /** Minimal NBT-backed proof; never a replacement gameplay state store. */
    private final GuardCombatLedger guardCombatLedger = new GuardCombatLedger();

    // Client-side animation machinery.
    public final AnimationState idleState = new AnimationState();
    public final AnimationState farmState = new AnimationState();
    public final AnimationState chopState = new AnimationState();
    public final AnimationState eatState = new AnimationState();
    public final AnimationState restState = new AnimationState();
    public final AnimationState stanceState = new AnimationState();
    public final AnimationState meleeState = new AnimationState();
    /** A short, server-claimed presentation layered over one existing melee ticket. */
    public final AnimationState guardFinisherState = new AnimationState();
    /** Guard moveset one-shots (entity/combat/GuardMove). Strikes are mutually
     *  exclusive with meleeState/guardFinisherState; see startOnlyGuardStrike. */
    public final AnimationState guardLightBState = new AnimationState();
    public final AnimationState guardHeavyState = new AnimationState();
    public final AnimationState guardShieldBashState = new AnimationState();
    public final AnimationState guardStaggerState = new AnimationState();
    /** GUARD_ATTENTION_SNAP then GUARD_SALUTE (started by EV_GUARD_SALUTE). */
    public final AnimationState guardSaluteState = new AnimationState();
    /** Captain's return nod (started by EV_GUARD_NOD). */
    public final AnimationState guardNodState = new AnimationState();
    /** GUARD_SALUTE_RELEASE then GUARD_DRAW_SWORD (started by EV_GUARD_SALUTE_END). */
    public final AnimationState guardSaluteEndState = new AnimationState();
    /** Battle-role strike one-shot (EV_ROLE_MOVE_BASE + move); {@link #roleMoveId} says which. */
    public final AnimationState roleMoveState = new AnimationState();
    /** Client: RoleMove ordinal of the running {@link #roleMoveState} clip. */
    public int roleMoveId = -1;
    public final AnimationState archerLooseState = new AnimationState();
    public final AnimationState celebrateState = new AnimationState();
    // SLICE ANIM-1 additions.
    public final AnimationState plantState = new AnimationState();
    public final AnimationState harvestState = new AnimationState();
    public final AnimationState waterState = new AnimationState();
    public final AnimationState limbState = new AnimationState();
    public final AnimationState haulState = new AnimationState();
    /** Client-local presentation state; gameplay still follows activity/bag. */
    private float haulPoseBlend;
    private float heavyHaulPoseBlend;
    private float visualCarryFraction;
    private boolean heavyHaulPoseTarget;
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
    public final AnimationState stirState = new AnimationState();
    public final AnimationState planeState = new AnimationState();
    public final AnimationState chiselState = new AnimationState();
    public final AnimationState fletchState = new AnimationState();
    public final AnimationState scrapeState = new AnimationState();
    // TRADES-1: three new signature motions. One state per CLIP, the same
    // rule the D-016 group above follows.
    public final AnimationState shearState = new AnimationState();
    public final AnimationState fishState = new AnimationState();
    public final AnimationState huntState = new AnimationState();
    /** Hunter carcass carry: arms-only shoulder hold over WALK_LADEN. */
    public final AnimationState carcassCarryState = new AnimationState();
    /** Hunter butchery at the table / on the Lodge floor. */
    public final AnimationState butcherState = new AnimationState();
    public final AnimationState skinState = new AnimationState();
    /**
     * Truthful, non-looping table craft. Its clock starts from the server
     * activity transition, so tick 30 in the clip is tick 30 in the atomic
     * input-to-escrow transaction; never phase-offset this state in the model.
     */
    public final AnimationState craftState = new AnimationState();
    /** Separate chest-contact one-shot for moving escrow into real storage. */
    public final AnimationState craftStoreState = new AnimationState();
    public final AnimationState liftState = new AnimationState();
    public final AnimationState setDownState = new AnimationState();
    /** The universal pickup: any settler, any trade, stooping for something
     *  on the ground. One-shot, see {@link #EV_PICKUP}/{@link #triggerPickup()}. */
    public final AnimationState pickupState = new AnimationState();
    /** Four explicit phases of the fell -> collect -> sack -> haul loop. */
    public final AnimationState workContainerDownState = new AnimationState();
    public final AnimationState groundItemPickupState = new AnimationState();
    public final AnimationState workContainerStowState = new AnimationState();
    public final AnimationState workContainerUpState = new AnimationState();
    /** Exact offline candidate 1bae8d124f5cb89c, started only by a chest cycle. */
    public final AnimationState bagToChestUnloadState = new AnimationState();
    /** A permanent Blessing binding, deliberately independent of activity. */
    public final AnimationState blessingReceiveState = new AnimationState();

    // Trade idles (owner: "vil ogsa ha idle animations som matcher jobben").
    // Sixteen self-contained loops covering all 24 employed professions --
    // see SettlerAnimations' own comment on the set. Each is mutually
    // exclusive with idleState AND with every other state in this group:
    // exactly one plays at a time, gated by profession in
    // setupAnimationStates() below. Most states map one-to-one to a clip;
    // idleSentryState deliberately selects IDLE_ARCHER only for ARCHER so a
    // real bow never enters the GUARD sword pose. SMITH and
    // SMELTER share idleForgeState, and so on. TRADES-1 raised the count
    // from fourteen/21 to fifteen/24; the truthful Archer split makes sixteen.
    public final AnimationState idleFarmerState = new AnimationState();
    public final AnimationState idleLumbererState = new AnimationState();
    public final AnimationState idleSentryState = new AnimationState();
    public final AnimationState idleCourierState = new AnimationState();
    /** Trader-only stationary negotiation idle; it owns no inventory state. */
    public final AnimationState idleTraderState = new AnimationState();
    public final AnimationState idleForgeState = new AnimationState();
    public final AnimationState idleBakerState = new AnimationState();
    public final AnimationState idleCookState = new AnimationState();
    public final AnimationState idleSightEdgeState = new AnimationState();
    public final AnimationState idleFletcherState = new AnimationState();
    public final AnimationState idleMinerState = new AnimationState();
    public final AnimationState idleScholarState = new AnimationState();
    public final AnimationState idleInnkeeperState = new AnimationState();
    public final AnimationState idleWeaverState = new AnimationState();
    public final AnimationState idleBladeBenchState = new AnimationState();
    // TRADES-1: FISHER is the only one of the three new trades that needs a
    // clip of its own -- HERDER shares idleFarmerState and HUNTER shares
    // idleSentryState, both justified in setupAnimationStates() below.
    public final AnimationState idleFisherState = new AnimationState();

    public SettlerEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.moveControl = new com.hearthstead.entity.path.SettlerMoveControl(this);
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

    @Override
    public boolean isPushedByFluid(net.neoforged.neoforge.fluids.FluidType type) {
        return super.isPushedByFluid(type)
            && (type != net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value()
                || !isGroundedShallowWaterNavigation());
    }

    private boolean isGroundedShallowWaterNavigation() {
        if (!onGround() || isPassenger() || getNavigation().isDone()) {
            return false;
        }
        BlockPos support = blockPosition().below();
        if (!level().isLoaded(support)
                || !level().getBlockState(support).isFaceSturdy(level(), support,
                    net.minecraft.core.Direction.UP)) {
            return false;
        }
        // NeoForge stores fluid heights only AFTER applying its flow push.
        // Inspect the same actual body volume now, rather than reading the
        // previous tick's height when deciding whether our feet can wade.
        var body = getBoundingBox().deflate(0.001D);
        double maximumDepth = getFluidJumpThreshold();
        boolean touchesWater = false;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(body.minX); x < Mth.ceil(body.maxX); x++) {
            for (int y = Mth.floor(body.minY); y < Mth.ceil(body.maxY); y++) {
                for (int z = Mth.floor(body.minZ); z < Mth.ceil(body.maxZ); z++) {
                    cell.set(x, y, z);
                    if (!level().isLoaded(cell)) return false;
                    var fluid = level().getFluidState(cell);
                    if (!fluid.is(net.minecraft.tags.FluidTags.WATER)) continue;
                    double depth = y + fluid.getHeight(level(), cell) - body.minY;
                    if (depth < 0.0D) continue;
                    if (depth > maximumDepth) return false;
                    touchesWater = true;
                }
            }
        }
        // Supported wading keeps ordinary speed, fatigue and carry effects.
        // Deep water, airborne motion and idle settlers retain vanilla flow.
        return touchesWater;
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
        builder.define(DATA_TRAVELER_APPEARANCE, false);
        builder.define(DATA_RESIDENT_MEAL, ItemStack.EMPTY);
        builder.define(DATA_INNKEEPER_SOCIAL, new CompoundTag());
        builder.define(DATA_VILLAGE_SOCIAL, new CompoundTag());
        builder.define(DATA_DRUNK_LEVEL, (byte) 0);
        builder.define(DATA_DRUNK_CUE, new CompoundTag());
        builder.define(DATA_TAVERN_MUSIC_UNTIL, 0L);
        builder.define(DATA_MORALE_JOY_UNTIL, 0L);
        builder.define(DATA_LIFE_NEED, (byte) LifeNeed.NONE);
        builder.define(DATA_TAVERN_CUE, new CompoundTag());
        builder.define(DATA_GUARD_DRILL, new CompoundTag());
        builder.define(DATA_MEAL_REMAINING, 0);
        builder.define(DATA_PROFESSION, Profession.NONE.id());
        builder.define(DATA_TRADE_XP, 0);
        builder.define(DATA_ACTIVITY, SettlerActivity.IDLE.id());
        builder.define(DATA_FISHER_CYCLE, -1);
        builder.define(DATA_HUNGER, 80.0F);
        builder.define(DATA_ENERGY, 90.0F);
        builder.define(DATA_MORALE, 60.0F);
        builder.define(DATA_APPEARANCE_SEED, 0);
        builder.define(DATA_LOOK_COSTUME, 0);
        builder.define(DATA_COMBAT_EXPERIENCE, 0);
        builder.define(DATA_CARRY_LOAD, 0);
        builder.define(DATA_CARRY_CAPACITY, BASE_CARRY_CAPACITY);
        builder.define(DATA_HAUL_GEAR, 0);
        builder.define(DATA_GEAR_CLEARANCE, 0);
        builder.define(DATA_HERALDRY, 0);
        builder.define(DATA_REQUESTED_EQUIPMENT, -1);
        builder.define(DATA_LOGISTICS_STOP, 0);
        builder.define(DATA_LOGISTICS_TARGET, Optional.empty());
        builder.define(DATA_WORK_CONTAINER_POS, Optional.empty());
        builder.define(DATA_WORK_CONTAINER_KIND, WorkContainerKind.NONE.id());
        builder.define(DATA_CRAFT_PRESENTATION, new CompoundTag());
        builder.define(DATA_BAG_TRANSFER_PRESENTATION, new CompoundTag());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_CRAFT_PRESENTATION.equals(key)) {
            craftPresentation = CraftPresentation.load(registryAccess(),
                entityData.get(DATA_CRAFT_PRESENTATION));
        } else if (DATA_BAG_TRANSFER_PRESENTATION.equals(key)) {
            bagTransferPresentation = BagTransferPresentation.load(
                registryAccess(), entityData.get(DATA_BAG_TRANSFER_PRESENTATION));
        }
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        // Flag-free: runs alongside any move goal, opens doors on the path
        // and closes them again behind (keeps homes enclosed and defensible).
        goalSelector.addGoal(1, new SettlerDoorGoal(this));
        goalSelector.addGoal(1, new SettlerPanicGoal(this));
        goalSelector.addGoal(1, new com.hearthstead.entity.ai.GuardRecoveryGoal(this));
        // A real TavernVisit reservation wins; idle strolling never owns a waiting guest.
        goalSelector.addGoal(6, new TravelerJoinGoal(this));
        goalSelector.addGoal(2, new com.hearthstead.entity.ai.GuardLeapGoal(this));
        goalSelector.addGoal(2, new GuardMeleeGoal(this));
        // BATTLE-ROLES (plan/BATTLE-ROLES.md): each goal gates itself on its
        // own profession and on the battleRoles kill-switch.
        goalSelector.addGoal(1, new com.hearthstead.entity.combat.role.HealerMedicGoal(this));
        goalSelector.addGoal(1, new com.hearthstead.entity.combat.role.EvacuateToInfirmaryGoal(this));
        goalSelector.addGoal(2, new com.hearthstead.entity.combat.role.SpearmanCombatGoal(this));
        goalSelector.addGoal(2, new com.hearthstead.entity.combat.role.LongswordCombatGoal(this));
        goalSelector.addGoal(2, new com.hearthstead.entity.combat.role.RuneMageGoal(this));
        // Hero Captain (plan/CAPTAIN.md): specials pre-empt the plain swing;
        // the weapon goal swings the loadouts the Guard moveset cannot.
        goalSelector.addGoal(1, new com.hearthstead.entity.combat.captain.CaptainSpecialGoal(this));
        goalSelector.addGoal(2, new com.hearthstead.entity.combat.captain.CaptainWeaponGoal(this));
        // The archer's ranged loop sits beside the guard's melee at the same
        // slot: both are "fight the target I have", and only one of the two
        // professions ever activates either.
        goalSelector.addGoal(2, new com.hearthstead.entity.ai.ArcherAttackGoal(this));
        // A live raid may turn ONE Stand guard into the issuing player's
        // local bodyguard, but only inside that Stand leash. Patrol and Tower
        // never yield to it. Combat at priority 2 still interrupts instantly;
        // the exact Stand order at 4 resumes when the local escort ends.
        // Live R/G field orders (FieldOrders) move soldiers to their formation
        // slots; registered first so it owns MOVE among the priority-3 goals.
        // A player's summon ("come to me", any settler) outranks the other
        // priority-3 orders; combat at 2 still interrupts it.
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.ComeToPlayerGoal(this));
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.FieldOrderGoal(this));
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.BannerTeamGoal(this));
        // Guard Drill (ring 1): the relieved watch spars in the Barracks yard after breakfast.
        // Before the salute so a drilling pair is not pulled away; any alarm, raid or target
        // ends it in the same tick (GuardDrillGoal#canContinueToUse).
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.GuardDrillGoal(this));
        // Attention + salute for a passing player or the Captain; peace only.
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.GuardSaluteGoal(this));
        goalSelector.addGoal(3, new GuardRaidEscortGoal(this));
        goalSelector.addGoal(3, new GuardRespondToAlertGoal(this));
        // An explicit order owns the ordinary post schedule, but yields to
        // reachable food or critical rest when safe without rewriting it.
        // Stand/Tower reject generic alarm movement; combat still selects
        // threats within the existing authored target leash.
        goalSelector.addGoal(4, new com.hearthstead.entity.ai.GuardOrderGoal(this));
        // Same numeric slot as the alert response above -- both are "answer
        // a call that outranks the ordinary day", and RespondToSummonsGoal's
        // own class doc explains why 3 and not higher (it must still yield
        // to eating and, via Flag contention, to combat and panic).
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.RespondToSummonsGoal(this));
        // A Miner walled into an old vertical pit carves steps out before it
        // can eat or sleep again (reliability soak 2026-09-25).
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.MinerEscapeGoal(this));
        // Same meal owner; explicit order/alert/threat predicates relinquish this paid preparation.
        goalSelector.addGoal(4, new com.hearthstead.entity.ai.GuardRecoveryGoal(this, true));
        goalSelector.addGoal(4, new EatFromHearthGoal(this));
        goalSelector.addGoal(5, new RestAtNightGoal(this));
        tavernVisitGoal = new com.hearthstead.entity.ai.TavernVisitGoal(this);
        goalSelector.addGoal(5, tavernVisitGoal);
        // A worker who can see the exact physical tool they are asking for
        // collects it before resuming trade work, but never before panic,
        // combat, eating or night rest.
        goalSelector.addGoal(5, new AcquireRequestedEquipmentGoal(this));
        // Same priority, registered after acquisition: any real local tool
        // wins before the Lumberer's bounded emergency-crafting fallback.
        goalSelector.addGoal(5, new LumbererSelfCraftGoal(this));
        // Raid-damage repair outranks the ordinary trades (5, after rest):
        // a breached wall is everyone's problem before anyone's workday.
        goalSelector.addGoal(5, new com.hearthstead.entity.ai.RepairWorkGoal(this));
        // An eligible delivery chooses its source before the generic workplace
        // posting claims MOVE. Keep priority 6 so rest and equipment at 5 can
        // still interrupt and the saved delivery resumes afterwards.
        goalSelector.addGoal(6, new CourierWorkGoal(this));
        goalSelector.addGoal(6, new GoToPostGoal(this));
        goalSelector.addGoal(6, new FarmerWorkGoal(this));
        // RING-1 lane: registered first at the same priority, so the whet only
        // starts between trees and never preempts (or is preempted by) felling.
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.LumbererWhetGoal(this));
        goalSelector.addGoal(6, new LumbererWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.TraderWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.CrafterWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.MinerWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.InnkeeperWorkGoal(this));
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.ScholarWorkGoal(this));
        // TRADES-1 (SURVIVAL_AUDIT F1): the three Ring-1 gathering trades
        // this roster was missing -- same priority slot as every other
        // trade goal above, same reason (the flag fight is between trade
        // goals of one settler, not a race with anything else in this list).
        goalSelector.addGoal(6, new HerderWorkGoal(this));
        // RING-1 lane: net set/haul between casts, same priority, registered first.
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.FisherNetGoal(this));
        goalSelector.addGoal(6, new FisherWorkGoal(this));
        goalSelector.addGoal(6, new HunterWorkGoal(this));
        // BUILDER lane (plan/BUILDER.md): a trade goal like the others; it
        // gates itself on the Builder profession, working hours and the
        // feature switch, and yields to rest/eating/raid-repair above it.
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.BuilderWorkGoal(this));
        // Lower than the delivery goal: tidying is what a courier does when
        // there is nothing to fetch.
        goalSelector.addGoal(7, new com.hearthstead.entity.ai.TidyWarehouseGoal(this));
        // A guard picked for a player-drawn patrol route walks it with its
        // squad (PATROL ROUTES lane); the ordinary rounds below stand aside.
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.PatrolRouteGoal(this));
        goalSelector.addGoal(6, new GuardPatrolGoal(this));
        // An Archer with no order holds its own tower by default; every real
        // order, field order, summons or alert outranks it (reliability soak).
        goalSelector.addGoal(6, new com.hearthstead.entity.ai.ArcherDefaultPostGoal(this));
        // Peacetime quiver top-up at the Watchtower rack (owner, 27 Sep: 6 arrows).
        goalSelector.addGoal(3, new com.hearthstead.entity.ai.ArcherResupplyGoal(this));
        // The player's RESUPPLY field order: the only thing that pulls a dry archer out of the line.
        goalSelector.addGoal(1, new com.hearthstead.entity.ai.ArcherResupplyGoal(this, true));
        goalSelector.addGoal(7, new ReturnToSettlementGoal(this));
        // Modest, and lower than every trade: greeting the captain is what a
        // settler does between real things, never instead of them.
        goalSelector.addGoal(7, new com.hearthstead.entity.ai.WorkCompanionGoal(this));
        // Free-time life (meal/evening only): greet a player, warm at the Hearth.
        goalSelector.addGoal(7, new com.hearthstead.entity.ai.FreeTimeGoal(this));
        // Living village: an idle settler steps out of the rain (MOVE only, idle time only).
        goalSelector.addGoal(7, new com.hearthstead.ambient.ShelterFromRainGoal(this));
        goalSelector.addGoal(7, new com.hearthstead.entity.ai.SaluteCaptainGoal(this));
        goalSelector.addGoal(8, new BoundedStrollGoal(this));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new SettlerDefenseTargetGoal(this));
    }

    // -------------------------------------------------------------- sync ---

    public Profession getProfession() {
        return Profession.byId(entityData.get(DATA_PROFESSION));
    }

    public int getFisherCycleTick() { return entityData.get(DATA_FISHER_CYCLE); }

    public void setFisherCycleTick(int tick) {
        entityData.set(DATA_FISHER_CYCLE, Mth.clamp(tick, -1, 299));
    }

    public SettlerActivity getActivity() {
        return SettlerActivity.byId(entityData.get(DATA_ACTIVITY));
    }

    public void setActivity(SettlerActivity activity) {
        // Vanilla restores bed sleep from NBT, but goals/activity do not resume
        // their old lifecycle. A new owner (combat, alert, work...) must actually
        // wake that body, even if the old RestAtNightGoal never ran its stop().
        // Keep the bed claim; only vanilla's sleeping pose/occupancy is released.
        if (!level().isClientSide && activity != SettlerActivity.SLEEPING && isSleeping()) {
            stopSleeping();
        }
        if (activity != SettlerActivity.WORK_FISH && getFisherCycleTick() >= 0) {
            setFisherCycleTick(-1);
        }
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
        // Spirit shrinks losses (AttributeRuntime.moraleDelta, plan/ATTRIBUTES.md).
        float scaled = (delta < 0.0F && !level().isClientSide()
            ? AttributeRuntime.moraleDelta(this, delta) : delta) * (delta < 0.0F
            ? Trait.moraleDecay(traits()) : Trait.moraleGain(traits()));
        float before = getMorale();
        float after = Mth.clamp(before + scaled, 0.0F, 100.0F);
        entityData.set(DATA_MORALE, after);
        // Celebrate real meal/social gains, not the small passive equilibrium drift.
        // A capped or negative change never fabricates happiness. This transient
        // cue is synced but deliberately not saved/replayed after loading.
        long now = level().getGameTime();
        if (!level().isClientSide && isAlive() && delta >= 1.0F && after > before && now >= nextMoraleJoy) {
            entityData.set(DATA_MORALE_JOY_UNTIL, now + 40);
            nextMoraleJoy = now + 80;
        }
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

    /** Event-visitor costume (skins lane); 0 = the settler's own clothes. */
    public int getLookCostume() {
        return entityData.get(DATA_LOOK_COSTUME);
    }

    public void setLookCostume(int costume) {
        entityData.set(DATA_LOOK_COSTUME, costume);
    }

    /**
     * Persistent hostile-kill experience, projected through synced entity data
     * so an already-open inspection sheet updates without polling or another
     * custom packet. The value is always in GuardExperience's bounded domain.
     */
    public int combatExperience() {
        return GuardExperience.clamp(entityData.get(DATA_COMBAT_EXPERIENCE));
    }

    /**
     * Server-only award endpoint used by the death-event policy. Returns the
     * amount actually added (zero at the cap or for a rejected amount).
     */
    public int awardCombatExperience(int amount) {
        if (!(level() instanceof ServerLevel) || amount <= 0) {
            return 0;
        }
        int before = combatExperience();
        int after = GuardExperience.add(before, amount);
        if (after != before) {
            entityData.set(DATA_COMBAT_EXPERIENCE, after);
        }
        return after - before;
    }

    /**
     * Server death-event transaction: one persisted source identity and one XP
     * transition commit together. Direct administrative/test XP adjustments
     * continue to use {@link #awardCombatExperience(int)} and intentionally do
     * not masquerade as a combat award.
     */
    @Nullable
    public GuardCombatLedger.XpCommit commitCombatExperienceAward(
            UUID sourceId, int amount) {
        if (!(level() instanceof ServerLevel) || amount <= 0) {
            return null;
        }
        // Guard Drill / Shield Doctrine: a real combat award is scaled once,
        // inside the same ledger commit (admin awards stay exact).
        amount = com.hearthstead.settlement.development.DevelopmentBonuses
            .guardCombatXp(this, amount);
        int before = combatExperience();
        int after = GuardExperience.add(before, amount);
        if (after <= before) {
            return null;
        }
        GuardCombatLedger.XpCommit commit = guardCombatLedger.commitXp(
            sourceId, before, after);
        if (commit == null) {
            return null;
        }
        // SynchedEntityData#set is the non-throwing entity persistence source;
        // telemetry is emitted only after both this value and the ledger row
        // have reached their terminal in-memory state.
        entityData.set(DATA_COMBAT_EXPERIENCE, after);
        if (GuardExperience.tierOf(after).level() > GuardExperience.tierOf(before).level()) {
            playTrainingLevelCue();
        }
        return commit;
    }

    public boolean hasCombatExperienceSource(UUID sourceId) {
        return guardCombatLedger.containsXpSource(sourceId);
    }

    public long committedCombatExperienceAwards() {
        return guardCombatLedger.xpCount();
    }

    /** Called only from immutable final-damage observation after real blocking. */
    @Nullable
    public GuardCombatLedger.ShieldCommit commitShieldBlock(UUID actionId,
                                                             long tick) {
        if (!(level() instanceof ServerLevel level)
            || getProfession() != Profession.GUARD
            || tick != level.getGameTime()) {
            return null;
        }
        return guardCombatLedger.commitShield(actionId, tick);
    }

    public long committedShieldBlocks() {
        return guardCombatLedger.shieldCount();
    }

    /**
     * Presents one already-committed, final-damage shield block. The caller is
     * the immutable combat terminal, never {@link #hurt(DamageSource, float)}:
     * vanilla returns {@code false} for a full block, while ordinary health
     * loss returns {@code true}. Using that return value as shield evidence
     * therefore inverts the cue.
     *
     * @return true only when a live server Guard still owns the physical shield
     */
    public boolean presentCommittedShieldBlock() {
        if (!(level() instanceof ServerLevel) || !isAlive() || isRemoved()
            || getProfession() != Profession.GUARD
            || !hasPhysicalOffhandShield()) {
            return false;
        }
        level().broadcastEntityEvent(this, EV_SHIELD_BLOCK);
        shieldThudIn = SHIELD_THUD_DELAY;
        if (shieldBlockPresentationSequence < Long.MAX_VALUE) {
            shieldBlockPresentationSequence++;
        }
        return true;
    }

    /** Read-only native-test evidence that no ordinary hurt forged a block cue. */
    public long shieldBlockPresentationSequence() {
        return shieldBlockPresentationSequence;
    }

    /**
     * Applies one physical serviceability transition for an already-persisted
     * block terminal. Vanilla's base LivingEntity shield hook is intentionally
     * empty (only Player overrides it), so a settler shield otherwise lasts
     * forever. The standard equipped-slot overload owns enchantment handling,
     * stack removal, and the OFFHAND break entity event.
     */
    public boolean consumeCommittedShieldDurability() {
        if (!(level() instanceof ServerLevel serverLevel)
            || serverLevel.getServer() == null
            || !serverLevel.getServer().isSameThread()
            || isRemoved() || getProfession() != Profession.GUARD
            || !isUsingItem()
            || getUsedItemHand() != InteractionHand.OFF_HAND
            || !getUseItem().is(Items.SHIELD)
            || !hasPhysicalOffhandShield() || !isBlocking()) {
            return false;
        }
        ItemStack shield = getOffhandItem();
        // Exactly one canonical durability attempt belongs to each committed
        // block. ItemStack remains the authority for NeoForge item hooks and
        // Unbreaking; an absorbed attempt is still a consumed terminal, so the
        // return value reports eligibility rather than observed damage delta.
        shield.hurtAndBreak(1, this, EquipmentSlot.OFFHAND);
        if (shield.isEmpty()) {
            // Mirror Player's break cleanup. The equipped-slot overload emits
            // the vanilla break event but does not clear a mob's active-use
            // state, which would otherwise leave a guard ghost-blocking.
            stopUsingItem();
            setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            shieldThudIn = -1;
            playSound(SoundEvents.SHIELD_BREAK, 0.8F,
                0.8F + level().random.nextFloat() * 0.4F);
        }
        return true;
    }

    /** Exact server callback proof that the OFFHAND break event was emitted. */
    public long shieldBreakEventSequence() {
        return shieldBreakEventSequence;
    }

    @Override
    public void onEquippedItemBroken(Item item, EquipmentSlot slot) {
        super.onEquippedItemBroken(item, slot);
        if (!level().isClientSide && item == Items.SHIELD
            && slot == EquipmentSlot.OFFHAND
            && shieldBreakEventSequence < Long.MAX_VALUE) {
            shieldBreakEventSequence++;
        }
    }

    public SettlerAppearance getAppearance() {
        return SettlerAppearance.decode(getAppearanceSeed());
    }

    // --------------------------------------------------------- Blessings ---

    /** Server-authoritative binding endpoint used by a physical Blessing Seal. */
    public TargetBlessingState.ApplyResult applyBlessing(BlessingId blessing) {
        return applyBlessing(blessing, 1);
    }

    public TargetBlessingState.ApplyResult applyBlessing(BlessingId blessing, int rankUnits) {
        TargetBlessingState.ApplyResult result = targetBlessings.apply(blessing, rankUnits);
        if (result == TargetBlessingState.ApplyResult.APPLIED) {
            triggerBlessingReceive();
            if (level() instanceof ServerLevel serverLevel) {
                if (pendingBlessingCues == null) {
                    pendingBlessingCues = new ArrayDeque<>();
                }
                pendingBlessingCues.addLast(new ScheduledBlessingCue(blessing,
                    serverLevel.getGameTime() + BLESSING_CONTACT_DELAY_TICKS));
                // The permanent ledger is already authoritative here. Push
                // an update only to players still viewing this exact sheet;
                // MAXED/INVALID never enter this branch.
                com.hearthstead.network.InspectionViewers.refreshSettler(
                    serverLevel, this);
            }
        }
        return result;
    }

    /** Constant-time permanent rank lookup for future effect hooks. */
    public int blessingRank(BlessingId blessing) {
        return targetBlessings.rank(blessing);
    }

    // -------------------------------------------------------- membership ---

    public boolean isBound() {
        return settlementId != null;
    }

    /** Client: a private outline (summoned to this player, or hearing a held command key). */
    @Override
    public boolean isCurrentlyGlowing() {
        if (level().isClientSide && ClientOutlineHook.colorFor(getId()) >= 0) return true;
        return super.isCurrentlyGlowing();
    }

    @Override
    public int getTeamColor() {
        if (level().isClientSide) {
            int outline = ClientOutlineHook.colorFor(getId());
            if (outline >= 0) return outline;
        }
        return super.getTeamColor();
    }

    public boolean isTraveler() {
        return traveler;
    }

    /** Synced projection of the existing saved guest state, never equipment. */
    public boolean hasTravelerAppearance() {
        return entityData.get(DATA_TRAVELER_APPEARANCE)
            && getProfession() == Profession.NONE;
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
        syncBedClaimProjection();
    }

    public void releaseBed() {
        if (isSleeping()) {
            stopSleeping();
        }
        this.claimedBed = null;
        syncBedClaimProjection();
    }

    private void syncBedClaimProjection() {
        if (!(level() instanceof ServerLevel server) || settlementId == null) return;
        Settlement owner = SettlementManager.byId(server, settlementId);
        if (owner == null || owner.record(getUUID()) == null) return;
        var record = owner.record(getUUID());
        var next = com.hearthstead.settlement.ResidentBedClaim.known(claimedBed);
        if (!next.equals(record.bedClaim)) {
            record.bedClaim = next;
            SettlementManager.data(server).setDirty();
        }
    }

    public String getSettlerName() {
        Component custom = getCustomName();
        return custom != null ? custom.getString() : "Settler";
    }

    public void setSettlerName(String name) {
        setCustomName(Component.literal(name));
        setCustomNameVisible(false);
    }

    // Assigned in registerGoals (super-constructor), not a later field initializer.
    private com.hearthstead.entity.ai.TavernVisitGoal tavernVisitGoal;
    public boolean prefersTavernMeal() { return tavernVisitGoal != null && tavernVisitGoal.prefersMeal(); }
    /** Read-only live visit state for owned QA diagnostics. */
    public String tavernVisitDiagnostic() {
        return tavernVisitGoal == null ? "uninitialized" : tavernVisitGoal.diagnosticState();
    }
    public boolean hasTavernSeat() { return com.hearthstead.settlement.TavernSeating.hasTavernSeat(this); }
    public com.hearthstead.settlement.Building currentTavern() {
        return com.hearthstead.settlement.TavernSeating.currentTavern(this);
    }
    public boolean leaveSeat() { return com.hearthstead.settlement.TavernSeating.leaveSeat(this); }

    public void bindTo(UUID settlement, BlockPos hearth) {
        this.settlementId = settlement;
        this.targetSettlementId = null;
        this.hearthPos = hearth;
        this.traveler = false;
        entityData.set(DATA_TRAVELER_APPEARANCE, false);
        setActivity(SettlerActivity.IDLE);
    }

    public void markTraveler(UUID settlement, BlockPos hearth) {
        this.traveler = true;
        entityData.set(DATA_TRAVELER_APPEARANCE, true);
        this.targetSettlementId = settlement;
        this.hearthPos = hearth;
        setActivity(SettlerActivity.TRAVELING);
    }

    public void unbind() {
        if (level() instanceof ServerLevel serverLevel
            && archerQuiver > 0) {
            releaseCarriedArrows(serverLevel, settlement());
        }
        releaseBed();
        this.settlementId = null;
        this.targetSettlementId = null;
        this.hearthPos = null;
        this.traveler = false;
        entityData.set(DATA_TRAVELER_APPEARANCE, false);
        if (getProfession() != Profession.NONE) {
            entityData.set(DATA_PROFESSION, Profession.NONE.id());
        }
        clearLogisticsStop();
        setActivity(SettlerActivity.IDLE);
    }

    @Nullable
    public Settlement settlement() {
        return level() instanceof ServerLevel serverLevel
            ? SettlementManager.byId(serverLevel, settlementId) : null;
    }

    /**
     * The raw settlement binding, for identity checks that must not resolve
     * the settlement object -- e.g. a raider deciding whether this settler
     * is its war's business at all (KF-027). A traveler counts as belonging
     * to the settlement they are walking toward: a raid that ignores the
     * incoming recruit it was sent to strangle is not much of a siege.
     */
    @Nullable
    public UUID boundOrTargetSettlementId() {
        return settlementId != null ? settlementId : targetSettlementId;
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
     * occupancy is. It is never consulted to decide who works where, and it
     * never manufactures or destroys physical equipment. Tools must arrive
     * through the settlement's request and delivery loop.
     *
     * <p>Deliberately silent: no morale, no sound, no celebration. Those belong
     * to the events ({@link #onHired}, {@link #onDismissed}), not to keeping a
     * projection in step — otherwise a settler cheers every time a chunk
     * reloads.
     */
    public void setProfessionProjection(Profession profession) {
        GuardHealth.refresh(this);
        if (getProfession() == profession) {
            return;
        }
        // Super-QA: a stop reason belongs to the old trade (e.g. a Farmer's
        // NO_WORK_ZONE); never let it linger on the settler's new job sheet.
        clearLogisticsStop();
        entityData.set(DATA_PROFESSION, profession.id());
        // Never leave yesterday's request icon above a reassigned worker.
        // The next server reconciliation publishes the new trade's need.
        entityData.set(DATA_REQUESTED_EQUIPMENT, -1);
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
    /**
     * A sergeant's leap. One-shot, expiring on its own clock.
     *
     * <p>Broadcast, not started here. This used to be a bare
     * {@code leapState.start(tickCount)}, and its only caller is
     * {@code GuardLeapGoal#start}, which runs server-side -- so the state was
     * started on the SERVER copy, the one no renderer ever sees, and
     * {@code SettlerModel}'s {@code animate(entity.leapState, LEAP_STRIKE)}
     * could never fire. The authored leap never played: a leaping guard fell
     * back on the plain WALK cycle and pedalled through the air. Same defect
     * and same fix as {@link #triggerGatherLog()}; the working idiom is
     * {@link #triggerPickup()}'s.
     */
    public void triggerLeapStrike() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_LEAP_STRIKE);
        }
    }

    /**
     * The lumberjack stoops for the log they just felled.
     *
     * <p>Triggered at the moment the log comes down, not on a timer, so the
     * stoop always lands on a log that actually exists.
     *
     * <p>Two bugs lived in this method's three previous lines, and they
     * compounded. First, {@code gatherState.start()} was called directly, so
     * on a server-side caller ({@code LumbererWorkGoal}) the state started on
     * a copy no renderer sees and GATHER_LOG never played. Second -- and this
     * is what a player actually SAW -- it also set the activity to
     * GATHERING_LOG, which matches no animation gate anywhere and was only
     * ever cleared inside the client-only {@code setupAnimationStates()}. The
     * server therefore parked the lumberjack in an activity with no clip and
     * nothing put it back: after his first log he stood in the bare rig, arms
     * down, for the rest of the tree. At 60 ticks per log that is a
     * motionless woodcutter for most of every oak, and he is the first worker
     * anyone hires.
     *
     * <p>Now it broadcasts the one-shot and leaves the activity alone. The
     * stoop plays as an event on top of WORK_CHOP, which is both what it
     * looks like and what he is actually doing.
     */
    public void triggerGatherLog() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_GATHER_LOG);
        }
    }

    /** Starts the authored detach-and-place phase of lumber collection. */
    public void triggerWorkContainerDown() {
        broadcastWorkContainerEvent(EV_WORK_CONTAINER_DOWN);
    }

    /** Starts the authored ground-item pickup; transfer occurs at its contact tick. */
    private long lastGroundItemPickupStartedTick = Long.MIN_VALUE;

    public void triggerGroundItemPickup() {
        if (!level().isClientSide) lastGroundItemPickupStartedTick = level().getGameTime();
        broadcastWorkContainerEvent(EV_GROUND_ITEM_PICKUP);
    }

    /** Read-only server observation of the real pickup event, separate from bag placement. */
    public long lastGroundItemPickupStartedTick() {
        return lastGroundItemPickupStartedTick;
    }

    /** Starts the authored offhand-item into placed-container phase. */
    public void triggerWorkContainerStow() {
        broadcastWorkContainerEvent(EV_WORK_CONTAINER_STOW);
    }

    /** Starts the authored container lift phase before transport. */
    public void triggerWorkContainerUp() {
        broadcastWorkContainerEvent(EV_WORK_CONTAINER_UP);
    }

    /** Starts one complete 80-tick bag-to-chest presentation cycle. */
    public void triggerBagToChestUnload() {
        broadcastWorkContainerEvent(EV_BAG_TO_CHEST_UNLOAD);
    }

    private void broadcastWorkContainerEvent(byte event) {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, event);
        }
    }

    public void celebrate() {
        if (!level().isClientSide && celebrateBroadcastIn < 0 && celebrateAge < 0) {
            // Catalogue §14: stagger celebration starts by up to 20 ticks per
            // settler so village-wide cheers overlap instead of chorusing.
            celebrateBroadcastIn = getId() % 20;
        }
    }

    /**
     * The equipment-refresh hook (owner's ask, 2026-08-25: "guards must not
     * have good armor before they upgrade — they need experience"). Runs off
     * the same once-a-second cadence as {@link #tickNeeds()}; cheap because
     * it change-detects against {@link #lastAppliedGuardRank} rather than
     * re-setting four item slots every second for every guard in the
     * settlement -- see {@link GuardRank}'s own class doc for the equipment
     * table and for why this never has to un-rank a guard on its own.
     *
     * <p>A settler who is not (or no longer) a {@link Profession#GUARD}
     * neither wears nor keeps guard armor: the moment this hook sees the
     * profession has changed away, it strips the four slots once and forgets
     * the cached rank, so a demoted guard does not spend the rest of their
     * life in someone else's iron.
     */
    private void tickGuardEquipment() {
        if (getProfession() != Profession.GUARD) {
            if (lastAppliedGuardRank != null) {
                GuardRank.clearEquipment(this);
                lastAppliedGuardRank = null;
            }
            return;
        }
        GuardRank rank = GuardRank.of(this);
        // Change-detect on the rank, but ALSO re-check a guard whose kit is
        // incomplete: since the kit is bought from the settlement's stores
        // rather than conjured, a promotion can legitimately outrun the
        // armoury. Without this second condition the guard who ranked up on
        // an empty armoury would stay bare forever, because their rank never
        // changes again -- the smith could deliver and nobody would notice.
        if (rank == lastAppliedGuardRank && GuardRank.isFullyEquipped(this)) {
            return;
        }
        // Never true on the FIRST application (lastAppliedGuardRank is null,
        // not RECRUIT) -- being freshly hired already celebrates via
        // onHired/assignProfession, and a guard should not double-cheer the
        // instant they put on their first (empty) kit.
        boolean rankUp = lastAppliedGuardRank != null
            && rank.ordinal() > lastAppliedGuardRank.ordinal();
        lastAppliedGuardRank = rank;
        GuardRank.applyEquipment(this);
        if (rankUp) {
            celebrate();
            if (level() instanceof ServerLevel serverLevel) {
                Settlement s = settlement();
                if (s != null) {
                    com.hearthstead.settlement.raid.RaidBroadcast.send(serverLevel, s,
                        Component.translatable("hearthstead.message.rank_up",
                            getSettlerName(), rank.displayName()));
                }
            }
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
     * The daily labor pool. Lazily full for a settler who has not spent
     * anything today, mirroring how {@link #attributes()} lazily rolls —
     * see {@link Effort}'s class doc for the whole design.
     */
    public Effort effort() {
        if (effort == null) {
            effort = Effort.full();
        }
        return effort;
    }

    /** STAMINA-scaled ceiling on today's work; see {@link Effort#capacity}. */
    public int effortCapacity() {
        return effort().capacity(attribute(Attribute.STAMINA));
    }

    /** What is left to spend today. No hidden numbers (job standard point 1). */
    public int effortLeft() {
        return effort().left(attribute(Attribute.STAMINA));
    }

    /** What has already gone today. */
    public int effortSpent() {
        return effort().spent(attribute(Attribute.STAMINA));
    }

    /**
     * The one check every trade's work goal makes before starting a NEW
     * action (docs/project/PLAN_EFFORT.md). Spent is not broken: the
     * existing stroll/rest goals take over the moment the work goal steps
     * aside in the priority list — nothing else has to be told.
     */
    public boolean isEffortSpent() {
        return effort().isSpent(attribute(Attribute.STAMINA));
    }

    /** Pays for one completed work action — call at the moment the action
     *  finishes, the same rule {@link #train} already follows. */
    public void spendEffort(int units) {
        effort().spend(units, attribute(Attribute.STAMINA));
    }

    /** "14/32", for {@code /hearthstead why} and any future UI. */
    public String effortDescribe() {
        return effort().describe(attribute(Attribute.STAMINA));
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
        if (attributes().train(attribute, units, boost) && getProfession() != Profession.NONE) {
            playTrainingLevelCue();
        }
    }

    // Award endpoints only: loading an existing level must never replay it.
    // Several attributes earned from one action share a single nearby cue.
    private long lastTrainingLevelCue = Long.MIN_VALUE;

    private void playTrainingLevelCue() {
        if (!(level() instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        if (lastTrainingLevelCue == now) return;
        lastTrainingLevelCue = now;
        // Same soft chime as before, plus motes and a throttled action-bar
        // line; SettlerFlourish owns all three so other growth hooks reuse it.
        SettlerFlourish.play(server, this, SettlerFlourish.skillGrowthLine(this));
    }

    /** Server-authoritative trade-skill XP per profession (SkillLevels). */
    public SkillLevels.Track tradeSkills() {
        return tradeSkills;
    }

    /** Packed {@link com.hearthstead.settlement.gear.GearGate} clearance; 0 until first sync. */
    public int gearClearancePacked() {
        return entityData.get(DATA_GEAR_CLEARANCE);
    }

    /** Packed settlement heraldry colours (GearGate#packHeraldry); 0 when unknown. */
    public int heraldryPacked() {
        return entityData.get(DATA_HERALDRY);
    }

    /** Server-only projection write, change-detected. */
    public void setGearProjection(int clearance, int heraldry) {
        if (entityData.get(DATA_GEAR_CLEARANCE) != clearance) {
            entityData.set(DATA_GEAR_CLEARANCE, clearance);
        }
        if (entityData.get(DATA_HERALDRY) != heraldry) {
            entityData.set(DATA_HERALDRY, heraldry);
        }
    }

    /** Synced trade XP of the current profession; readable on both sides. */
    public int tradeXp() {
        return SkillLevels.clampXp(entityData.get(DATA_TRADE_XP));
    }

    /** Server: project the current profession's XP into synced data. */
    public void syncTradeSkill() {
        if (level().isClientSide) return;
        int xp = tradeSkills.xp(getProfession());
        if (entityData.get(DATA_TRADE_XP) != xp) {
            entityData.set(DATA_TRADE_XP, xp);
        }
    }

    // --------------------------------------------------------- life need ---

    /** The synced {@link LifeNeed} code; readable on both sides. */
    public int lifeNeed() {
        return entityData.get(DATA_LIFE_NEED);
    }

    /** Server: refresh the life-need projection on the once-a-second tick. */
    private void syncLifeNeed() {
        if (!(level() instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        Settlement s = settlement();
        if (s != null && LifeNeed.threatActive(s, now)) {
            frightenedUntil = now + LifeNeed.FRIGHT_LINGER_TICKS;
        }
        byte code = (byte) LifeNeed.compute(this, server, frightenedUntil);
        if (entityData.get(DATA_LIFE_NEED) != code) {
            entityData.set(DATA_LIFE_NEED, code);
        }
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
            && getActivity() != SettlerActivity.FLEEING
            && getActivity() != SettlerActivity.RETREATING;
    }

    /** The village clock. One rhythm for everyone — see {@link DayPhase}. */
    public DayPhase dayPhase() {
        // EARLY_RISER / NIGHT_OWL keep their own hour (AttributeRuntime.dayPhaseOf,
        // plan/ATTRIBUTES.md); the plain village clock when trait effects are off.
        return AttributeRuntime.dayPhaseOf(this, level().getDayTime());
    }

    /** Hunger at or above this counts as "full" (vanilla: 18 of 20). */
    public static final float WELL_FED_HUNGER = 90.0F;
    /** Seconds between well-fed heals (vanilla: one half-heart every 4 s). */
    private static final int WELL_FED_HEAL_SECONDS = 4;
    /** Hunger spent per health point restored. */
    private static final float WELL_FED_HUNGER_PER_HEALTH = 1.0F;

    /**
     * Owner decision 25 Sep 2026: a settler with a full belly slowly heals,
     * like a player. Never mid-fight, burning, or within 10 s of being hurt;
     * each point costs hunger, so a wounded village eats its stores.
     * Called from the once-a-second {@link #tickNeeds()}.
     */
    /** GameTest seam: measure meal-only healing without the well-fed trickle (never saved). */
    private boolean wellFedRegenSuppressedForTests;

    public void suppressWellFedRegenForTests() {
        wellFedRegenSuppressedForTests = true;
    }

    private void tickWellFedRegen(SettlerActivity activity) {
        if (wellFedRegenSuppressedForTests) return;
        if (getHealth() >= getMaxHealth() || getHunger() < WELL_FED_HUNGER) return;
        if (activity == SettlerActivity.COMBAT
            || activity == SettlerActivity.FLEEING
            || activity == SettlerActivity.RETREATING) return;
        if (hurtTime > 0 || isOnFire() || getLastDamageSource() != null) return;
        if (getLastHurtByMob() != null && tickCount - getLastHurtByMobTimestamp() < 200) return;
        if ((tickCount / 20) % WELL_FED_HEAL_SECONDS != 0) return;
        heal(1.0F);
        setHunger(getHunger() - WELL_FED_HUNGER_PER_HEALTH);
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
            || activity == SettlerActivity.WORK_BANDAGE
            || activity == SettlerActivity.WORK_REVIVE
            || activity == SettlerActivity.CAST_FIREBOLT
            || activity == SettlerActivity.CAST_FROST
            || activity == SettlerActivity.CAST_WARD
            || activity == SettlerActivity.WORK_OVEN
            || activity == SettlerActivity.WORK_SOW
            || activity == SettlerActivity.WORK_MINE
            || activity == SettlerActivity.WORK_STIR
            || activity == SettlerActivity.WORK_PLANE
            || activity == SettlerActivity.WORK_CHISEL
            || activity == SettlerActivity.WORK_FLETCH
            || activity == SettlerActivity.WORK_SCRAPE
            || activity == SettlerActivity.WORK_SHEAR
            || activity == SettlerActivity.WORK_FISH
            || activity == SettlerActivity.WORK_HUNT
            || activity == SettlerActivity.HAULING_CARCASS
            || activity == SettlerActivity.WORK_BUTCHER
            || activity == SettlerActivity.WORK_SKIN
            || activity == SettlerActivity.WORK_CRAFT
            || activity == SettlerActivity.STORE_CRAFT_OUTPUT;

        setHunger(getHunger()
            - (working ? 0.10F : 0.04F) * Trait.hunger(traits())
                * com.hearthstead.settlement.development.DevelopmentBonuses
                    .hungerDrainScale(this)
                // [economy] hungerDrainMultiplier (plan/ECONOMY.md): 1.0 made a
                // worker eat ~3.7 loaves a day, more than a courier chain can
                // carry for a 35-settler village. Neutral (1.0) in GameTests.
                * (float) com.hearthstead.settlement.economy.EconomyConfig
                    .hungerDrainMultiplier(getServer()));
        tickWellFedRegen(activity);
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
            setEnergy(getEnergy() + 1.5F
                * com.hearthstead.settlement.development.DevelopmentBonuses
                    .sleepEnergyScale(this));
        } else if (activity == SettlerActivity.RESTING) {
            setEnergy(getEnergy() + 1.2F);
        } else {
            // Stamina: working drains less (AttributeRuntime.workingDrain).
            setEnergy(getEnergy() - (working
                ? AttributeRuntime.workingDrain(this, 0.09F) : 0.02F));
        }
        applyFatigueSlow();
        AttributeRuntime.applyMaxHealth(this);
        AttributeRuntime.applyTraitSpeed(this);

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
            target += claimedBed != null ? 5 + homeQuality
                + com.hearthstead.settlement.development.DevelopmentBonuses
                    .bedMorale(this)
                // Tech tree (Sturdy Beds / Manors): a level 2 / 3 home.
                + com.hearthstead.settlement.techtree.effects.CommonsEffects
                    .homeMorale(this, s, claimedBed) : -5;
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

    /**
     * Refills the daily effort pool the moment rest actually ENDS, not on a
     * timer — "at wake", per docs/project/PLAN_EFFORT.md. Watched here as a
     * SettlerEntity need rather than from inside a rest goal: a genuine
     * night in a bed is {@link #isSleeping()} (vanilla) going true, then
     * false, and rough rest by the hearth is the {@link SettlerActivity#RESTING}
     * activity ending — both are things this entity already knows about
     * itself, edge-detected against last tick so the refill fires exactly
     * once per rest rather than every tick of it.
     *
     * <p>The rough-rest branch also requires the day to have actually turned
     * over into working hours. Without that guard, a settler resting rough
     * who finds a bed partway through the night (see RestAtNightGoal) would
     * briefly leave RESTING to walk over to it, which looks identical to a
     * finished night unless the clock itself is checked too.
     */
    private void tickEffortRefill() {
        boolean sleepingNow = isSleeping();
        if (sleepingLastTick && !sleepingNow) {
            // Tech tree (Feather Quilts): a calm night wakes Well Rested (110%).
            effort().refillFull(attribute(Attribute.STAMINA),
                com.hearthstead.settlement.techtree.effects.CommonsEffects.wakeRefillFraction(this));
        }
        sleepingLastTick = sleepingNow;

        boolean restingRoughNow = getActivity() == SettlerActivity.RESTING;
        if (restingRoughLastTick && !restingRoughNow && !sleepingNow && dayPhase().work()) {
            effort().refillRough(attribute(Attribute.STAMINA));
        }
        restingRoughLastTick = restingRoughNow;
    }

    // -------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        if (!level().isClientSide && (tickCount == 0 || tickCount % 20 == 0)) {
            GuardHealth.refresh(this);
        }
        // Reconcile the saved civic seat before any old job goal can execute.
        // Retry a deferred physical workplace return once per second.
        if (!level().isClientSide && isAlive()
                && (tickCount == 0 || tickCount % 20 == 0)) {
            Settlement seat = settlement();
            if (seat != null && (getUUID().equals(seat.mayorId)
                    || getProfession() == Profession.MAYOR)) {
                com.hearthstead.settlement.Employment.refresh(seat, this);
            }
        }
        super.tick();
        if (level().isClientSide) {
            setupAnimationStates();
            DrunkSounds.clientTick(this);
        } else {
            validateCinematicFinisher();
            tickDrunk();
            tickBlessingContactCues();
        }
    }

    /** A claimed drive is only a presentation for a normal, live blade contact. */
    private void validateCinematicFinisher() {
        if (cinematicFinisherTicket == MeleeContactLedger.NO_TICKET
            || !(level() instanceof ServerLevel server)) {
            return;
        }
        if (!(server.getEntity(cinematicFinisherTargetId) instanceof RaiderEntity raider)
            || !isAuthorizedMeleeContactTarget(raider)) {
            cancelMeleeContact(cinematicFinisherTicket);
        }
    }

    private void tickBlessingContactCues() {
        if (pendingBlessingCues == null
            || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        long now = serverLevel.getGameTime();
        while (!pendingBlessingCues.isEmpty()
            && pendingBlessingCues.peekFirst().dueTick() <= now) {
            ScheduledBlessingCue cue = pendingBlessingCues.removeFirst();
            presentBlessingContact(cue.blessing());
        }
        if (pendingBlessingCues.isEmpty()) {
            pendingBlessingCues = null;
        }
    }

    /** Overridable emission seam lets GameTests observe the exact contact tick. */
    protected void presentBlessingContact(BlessingId blessing) {
        if (level() instanceof ServerLevel serverLevel) {
            BlessingPresentation.bindingContact(serverLevel, blessing,
                getX(), getY() + getBbHeight() * 0.55D, getZ(), getYRot(),
                SoundSource.NEUTRAL);
        }
    }

    int pendingBlessingCueCount() {
        return pendingBlessingCues == null ? 0 : pendingBlessingCues.size();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide) {
            if (tickCount % 20 == 0) {
                tickNeeds();
                if (level() instanceof ServerLevel mealLevel) {
                    residentMeal.releaseRemainder(this, mealLevel);
                }
                reconcileEquipmentNeedNow();
                com.hearthstead.settlement.gear.GearGate.tickSecond(this);
                tickGuardEquipment();
                syncLogisticsStopProjection();
                syncLifeNeed();
                syncTradeSkill();
                com.hearthstead.util.QaTrace.record(this);
            }
            tickAccents();
            syncCarryLoad();
            wearPath();
            tickEffortRefill();
            com.hearthstead.entity.ai.SettlerStuckWatchdog.tick(this);
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

    /**
     * Transient, server-side proof captured only when workplace storage really
     * changes. It is diagnostic rather than authority: the inventory
     * transaction remains owned by WorkerProvenanceService, and this row is
     * neither persisted nor synced. Keeping it on the exact worker avoids a
     * global GameTest observer and adds no work to ordinary entity ticks.
     */
    public record StorageMutationWitness(BlockPos target,
                                         BlockPos contactPosition,
                                         ContainerApproach.State contactState,
                                         long contactTick,
                                         long mutationTick,
                                         int destinationBefore,
                                         int destinationAfter) {
        public StorageMutationWitness {
            target = java.util.Objects.requireNonNull(target,
                "storage witness target").immutable();
            contactPosition = java.util.Objects.requireNonNull(contactPosition,
                "storage witness contact position").immutable();
        }

        public boolean directContactCommit() {
            return contactState == ContainerApproach.State.CONTACT
                && contactTick == mutationTick
                && destinationAfter > destinationBefore;
        }
    }

    @Nullable private StorageMutationWitness lastStorageMutationWitness;

    // Effort's wake-refill edges (see tickEffortRefill above): whether this
    // settler was asleep/resting on the PREVIOUS tick, so the refill fires
    // exactly once, at the instant rest ends, rather than every tick of it.
    private boolean sleepingLastTick;
    private boolean restingRoughLastTick;

    /** Persisted absolute deadline; max avoids shortening shelter on repeated threats. */
    public long panicShelterUntil() { return panicShelterUntil; }

    public void extendPanicShelterUntil(long deadline) {
        panicShelterUntil = Math.max(panicShelterUntil, deadline);
    }

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

    /**
     * Records the exact successful contact result which immediately preceded
     * one authoritative storage insert. Invalid/no-op observations fail
     * closed and can never overwrite an earlier real witness.
     */
    public void recordStorageMutationWitness(ContainerApproach.Result contact,
                                             long contactTick,
                                             long mutationTick,
                                             int destinationBefore,
                                             int destinationAfter) {
        if (level().isClientSide || contact == null || !contact.canInteract()
            || contact.target() == null || contact.approach() == null
            || contactTick < 0L || mutationTick < contactTick
            || destinationBefore < 0
            || destinationAfter <= destinationBefore) {
            return;
        }
        lastStorageMutationWitness = new StorageMutationWitness(
            contact.target(), contact.approach(), contact.state(), contactTick,
            mutationTick, destinationBefore, destinationAfter);
    }

    /** Read-only, transient diagnostics for integration tests/live triage. */
    public Optional<StorageMutationWitness> lastStorageMutationWitness() {
        return Optional.ofNullable(lastStorageMutationWitness);
    }

    /**
     * Publishes an additive, player-facing logistics diagnosis. The old
     * {@link #recordRouteFailure(String)} trace remains untouched for tests
     * and debugging; this is the bounded network projection beside it.
     *
     * @param retryTicks 0 when merely waiting, otherwise the courier's real
     *                   remaining backoff duration
     */
    public void setLogisticsStop(StopReason reason, @Nullable BlockPos target,
                                 int retryTicks) {
        if (level().isClientSide) {
            return;
        }
        logisticsStopReason = java.util.Objects.requireNonNull(reason);
        logisticsStopTarget = target == null ? null : target.immutable();
        logisticsRetryUntil = retryTicks > 0
            ? level().getGameTime() + retryTicks : Long.MIN_VALUE;
        // Publish a changed diagnosis immediately. The once-per-second tick
        // below still owns countdown updates, while entity-data's equality
        // check keeps repeated blocked scans from producing duplicate packets.
        syncLogisticsStopProjection();
    }

    public void clearLogisticsStop() {
        setLogisticsStop(StopReason.NONE, null, 0);
    }

    /** Client-safe view of the packed projection. */
    public StopReason logisticsStopReason() {
        int packed = entityData.get(DATA_LOGISTICS_STOP);
        return StopReason.fromWireId(packed & 0x0F);
    }

    /** Whole seconds left in the visible retry countdown, 0 when not resting. */
    public int logisticsRetrySeconds() {
        return entityData.get(DATA_LOGISTICS_STOP) >>> 4;
    }

    /** Target plaque/hearth for the diegetic diagnosis. */
    public Optional<BlockPos> logisticsStopTarget() {
        return entityData.get(DATA_LOGISTICS_TARGET);
    }

    /**
     * Requests operation of one already-validated nearby workplace door.
     * {@link SettlerDoorGoal} independently revalidates proximity and block
     * type before opening it, then consumes this transient request.
     */
    public void requestDoorPassage(BlockPos door) {
        if (!level().isClientSide && door != null) {
            requestedDoorPassage = door.immutable();
        }
    }

    /** Runtime-only input for the flag-free door goal. */
    public Optional<BlockPos> requestedDoorPassage() {
        return Optional.ofNullable(requestedDoorPassage);
    }

    /** Consumes or invalidates the current transient door handoff. */
    public void clearDoorPassageRequest() {
        requestedDoorPassage = null;
    }

    /**
     * Fixed world block occupied by the detached portable work container, or
     * {@code null} while it is being carried. Position and kind are saved as
     * one recovery record: after reload the job must walk back and lift the
     * same container rather than teleporting its persistent {@link #bag}
     * contents onto the worker.
     */
    @Nullable
    public BlockPos placedWorkContainerPos() {
        return entityData.get(DATA_WORK_CONTAINER_POS).orElse(null);
    }

    /** Which portable prop is standing at {@link #placedWorkContainerPos()}. */
    public WorkContainerKind placedWorkContainerKind() {
        return WorkContainerKind.byId(entityData.get(DATA_WORK_CONTAINER_KIND));
    }

    /**
     * Atomically publishes one real placed work container. The server owns
     * the projection; clients may only render it.
     */
    public void placeWorkContainer(WorkContainerKind kind, BlockPos pos) {
        if (level().isClientSide) {
            return;
        }
        if (kind == null || kind == WorkContainerKind.NONE || pos == null) {
            clearWorkContainer();
            return;
        }
        Optional<BlockPos> next = Optional.of(pos.immutable());
        if (!entityData.get(DATA_WORK_CONTAINER_POS).equals(next)) {
            entityData.set(DATA_WORK_CONTAINER_POS, next);
        }
        if (entityData.get(DATA_WORK_CONTAINER_KIND) != kind.id()) {
            entityData.set(DATA_WORK_CONTAINER_KIND, kind.id());
        }
        applyCarrySlow();
    }

    /** Removes only the placed projection; the persistent bag contents remain. */
    public void clearWorkContainer() {
        if (level().isClientSide) {
            return;
        }
        if (entityData.get(DATA_WORK_CONTAINER_KIND) != WorkContainerKind.NONE.id()) {
            entityData.set(DATA_WORK_CONTAINER_KIND, WorkContainerKind.NONE.id());
        }
        if (entityData.get(DATA_WORK_CONTAINER_POS).isPresent()) {
            entityData.set(DATA_WORK_CONTAINER_POS, Optional.empty());
        }
        applyCarrySlow();
    }

    /** Client-safe, immutable view of the current server-authored craft. */
    public CraftPresentation craftPresentation() {
        return craftPresentation;
    }

    public BagTransferPresentation bagTransferPresentation() {
        return bagTransferPresentation;
    }

    /** Publish a visual claim only; callers retain all inventory authority. */
    public void publishBagTransferPresentation(BagTransferPresentation presentation) {
        if (level().isClientSide || presentation == null || !presentation.active()) return;
        bagTransferPresentation = presentation;
        entityData.set(DATA_BAG_TRANSFER_PRESENTATION,
            presentation.save(registryAccess()));
    }

    /** An old/reordered owner may never clear a newer transfer. */
    public void clearBagTransferPresentation(UUID transferId) {
        if (level().isClientSide || transferId == null
            || !bagTransferPresentation.active()
            || !transferId.equals(bagTransferPresentation.transferId())) return;
        bagTransferPresentation = BagTransferPresentation.empty();
        entityData.set(DATA_BAG_TRANSFER_PRESENTATION, new CompoundTag());
    }

    /**
     * Publishes only a visual projection. Callers must already own the exact
     * source reservation or output escrow named by {@code actionId}.
     */
    public void publishCraftPresentation(CraftPresentation presentation) {
        if (level().isClientSide || presentation == null
            || !presentation.active()) {
            return;
        }
        UUID actionId = presentation.actionId();
        if (craftOutputEscrow != null
            && !craftOutputEscrow.actionId().equals(actionId)
            || craftPresentation.active()
                && !craftPresentation.actionId().equals(actionId)) {
            throw new IllegalStateException(
                "craft presentation action does not own this settler");
        }
        craftPresentation = presentation;
        entityData.set(DATA_CRAFT_PRESENTATION,
            presentation.save(registryAccess()));
    }

    /** Stale actions may hide only their own props. */
    public void clearCraftPresentation(UUID actionId) {
        if (level().isClientSide || actionId == null
            || !craftPresentation.active()
            || !actionId.equals(craftPresentation.actionId())) {
            return;
        }
        craftPresentation = CraftPresentation.empty();
        entityData.set(DATA_CRAFT_PRESENTATION, new CompoundTag());
    }

    @Nullable
    public CraftOutputEscrow craftOutputEscrow() {
        return craftOutputEscrow;
    }

    public boolean hasCraftOutputEscrow() {
        return craftOutputEscrow != null;
    }

    /** Preflight used before any input slot is changed at recipe contact. */
    public boolean canBeginCraftOutputEscrow(UUID actionId, ItemStack output) {
        return !level().isClientSide && craftOutputEscrow == null
            && actionId != null && output != null && !output.isEmpty()
            && output.getCount() == 1;
    }

    /**
     * Creates the output's sole durable owner. The crafting service calls
     * this inside the same guarded contact transaction that consumes inputs.
     */
    public boolean beginCraftOutputEscrow(UUID actionId, UUID settlement,
                                          UUID building, BlockPos storageTarget,
                                          ItemStack output) {
        if (!canBeginCraftOutputEscrow(actionId, output)) {
            return false;
        }
        craftOutputEscrow = new CraftOutputEscrow(actionId, settlement,
            building, storageTarget, output);
        return true;
    }

    /** A blocked delivery can retarget without changing item ownership. */
    public boolean retargetCraftOutputEscrow(UUID actionId,
                                             BlockPos storageTarget) {
        if (level().isClientSide || craftOutputEscrow == null
            || actionId == null
            || !actionId.equals(craftOutputEscrow.actionId())
            || storageTarget == null) {
            return false;
        }
        craftOutputEscrow = craftOutputEscrow.retarget(storageTarget);
        return true;
    }

    /**
     * Clears authority only after a destination container already contains
     * the exact expected item; callers restore that container if this compare
     * and clear fails.
     */
    public boolean clearCraftOutputEscrow(UUID actionId,
                                          ItemStack expectedOutput) {
        if (level().isClientSide || craftOutputEscrow == null
            || !craftOutputEscrow.owns(actionId, expectedOutput)) {
            return false;
        }
        craftOutputEscrow = null;
        return true;
    }

    /**
     * At most one entity-data publication per second. Fifty settlers create
     * fifty tiny dirty-byte updates per second in the worst blocked case,
     * never a per-frame poll or a world scan.
     */
    private void syncLogisticsStopProjection() {
        long now = level().getGameTime();
        int seconds = logisticsRetryUntil > now
            ? Mth.clamp((int) ((logisticsRetryUntil - now + 19L) / 20L), 1, 31)
            : 0;
        int packed = seconds << 4 | logisticsStopReason.wireId();
        if (entityData.get(DATA_LOGISTICS_STOP) != packed) {
            entityData.set(DATA_LOGISTICS_STOP, packed);
        }
        Optional<BlockPos> target = Optional.ofNullable(logisticsStopTarget);
        if (!entityData.get(DATA_LOGISTICS_TARGET).equals(target)) {
            entityData.set(DATA_LOGISTICS_TARGET, target);
        }
    }

    /** How many items are in the real bag, whether placed or on the back. */
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

    /** Sack tier 0..3 (none, Satchel, Leather Pack, Frame Pack); client-safe. */
    public int sackTier() {
        return com.hearthstead.settlement.development.HaulGear.unpackTier(
            entityData.get(DATA_HAUL_GEAR));
    }

    /** True when this Courier's settlement owns the Hand Cart; client-safe. */
    public boolean hasHandCart() {
        return com.hearthstead.settlement.development.HaulGear.unpackCart(
            entityData.get(DATA_HAUL_GEAR));
    }

    /** Cart tier 0..3 (none, Hand Cart, Coster's Cart, Mule Cart); client-safe. */
    public int cartTier() {
        return com.hearthstead.settlement.development.HaulGear.unpackCartTier(
            entityData.get(DATA_HAUL_GEAR));
    }

    /** Sack prop scale for the tier: 1.00, 1.15, 1.30, 1.45. */
    public float sackVisualScale() {
        return com.hearthstead.settlement.development.HaulGear.visualScale(sackTier());
    }

    /** Server: publishes the packed gear projection (see HaulGear). */
    public void setHaulGear(int packed) {
        if (entityData.get(DATA_HAUL_GEAR) != packed) {
            entityData.set(DATA_HAUL_GEAR, packed);
        }
    }

    /**
     * Client-safe icon for the current server-authored equipment need.
     * Returning a fresh one-count stack prevents render code from ever
     * mutating request or inventory state.
     */
    public ItemStack requestedEquipmentIcon() {
        int itemId = entityData.get(DATA_REQUESTED_EQUIPMENT);
        if (itemId < 0) {
            return ItemStack.EMPTY;
        }
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.byId(itemId);
        return item == null || item == net.minecraft.world.item.Items.AIR
            ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * Publishes request intent for presentation without storing an item.
     * Called only from server request reconciliation.
     */
    public void setRequestedEquipmentProjection(@Nullable EquipmentRequest request) {
        if (level().isClientSide) {
            return;
        }
        int next = request == null ? -1 : BuiltInRegistries.ITEM.getId(
            request.requirement().preferredItem());
        if (entityData.get(DATA_REQUESTED_EQUIPMENT) == next) {
            return;
        }
        entityData.set(DATA_REQUESTED_EQUIPMENT, next);
        if (level() instanceof ServerLevel serverLevel) {
            // Exact active inspection sessions refresh in place. This never
            // opens a screen and therefore cannot steal focus in co-op.
            com.hearthstead.network.InspectionViewers.refreshSettler(
                serverLevel, this);
        }
    }

    /**
     * Immediate server reconciliation seam used by the real container menu
     * and by contact-frame ground pickup. Idempotent and physical: it may
     * move one matching stack from the bag into the equipment slot, but never
     * creates a stack or treats the request row as inventory.
     */
    public void reconcileEquipmentNeedNow() {
        if (level() instanceof ServerLevel serverLevel) {
            EquipmentRequest request = EquipmentRequests.refreshFor(
                serverLevel, this);
            setRequestedEquipmentProjection(request);
        }
    }

    /** 0 when empty, 1 when full. What the sack's size is drawn from. */
    public float carryFraction() {
        return Mth.clamp((float) getCarryLoad() / getCarryCapacity(), 0.0F, 1.0F);
    }

    /** 0..1 ease between ordinary locomotion arms and the hauling hold. */
    public float haulPoseBlend() {
        return haulPoseBlend;
    }

    /** 0..1 ease between the low free hand and the heavy shoulder-strap hold. */
    public float heavyHaulPoseBlend() {
        return heavyHaulPoseBlend;
    }

    /** Client-smoothed bag fill used only by the sack model and carry lean. */
    public float visualCarryFraction() {
        return visualCarryFraction;
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
        // Road / courier / cart speed and the visible sack tier (HaulGear).
        com.hearthstead.settlement.development.HaulGear.onFootfall(this);
        if (onGround() && level() instanceof ServerLevel serverLevel) {
            com.hearthstead.entity.path.PathWear.step(serverLevel, this);
        }
    }

    private void syncCarryLoad() {
        int total = 0;
        for (int i = 0; i < bag.getContainerSize(); i++) {
            total += bag.getItem(i).getCount();
        }
        // A stowed batch load is carried too (sack fill and walking weight).
        for (int i = 0; i < batchStow.getContainerSize(); i++) {
            total += batchStow.getItem(i).getCount();
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
        // A detached field container still owns the real bag contents, but
        // none of that weight is on the worker while they walk between it
        // and the physical drops.
        float fill = placedWorkContainerPos() == null ? carryFraction() : 0.0F;
        if (fill > 0.0F) {
            // Stamina never grants free capacity, but it does make the same
            // physical load less punishing: up to 25% relief at 99 / 100.
            float staminaRelief = 0.25F
                * attribute(Attribute.STAMINA) / SettlerAttributes.CEILING;
            speed.addOrUpdateTransientModifier(new AttributeModifier(
                CARRY_SLOW_ID, -MAX_CARRY_SLOW * fill * (1.0F - staminaRelief),
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /**
     * Work Pace is a continuous fatigue effect, never a hidden daily stop.
     * It is applied once per needs tick and only rewrites the modifier when
     * the visible whole-percent value changes.
     */
    private void applyFatigueSlow() {
        int pacePercent = Mth.clamp((int) Math.round(JobEffects.workPace(
            getEnergy(), attribute(Attribute.STAMINA)) * 100.0D), 0, 100);
        if (pacePercent == appliedFatiguePacePercent) {
            return;
        }
        appliedFatiguePacePercent = pacePercent;
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(FATIGUE_SLOW_ID);
        if (pacePercent < 100) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(
                FATIGUE_SLOW_ID, pacePercent / 100.0D - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private void setupAnimationStates() {
        // This custom one-shot is read procedurally rather than through
        // HierarchicalModel.animate(), so advance it on the client tick too.
        // The tick update also guarantees expiry while the entity is offscreen.
        archerLooseState.updateTime(tickCount, 1.0F);
        SettlerActivity activity = getActivity();
        Profession profession = getProfession();
        boolean moving = walkAnimation.speed() > 0.05F;
        // Trade idles fully replace the generic IDLE breath-and-sway loop
        // rather than layering on it (vanilla's animate() is additive, so
        // summing two full-body loops on the same bones would corrupt
        // both poses -- the same hazard SettlerModel's own header comment
        // documents for IDLE). idleState is therefore gated to NONE only
        // while standing idle; EATING and CELEBRATING keep using it as
        // their breath layer regardless of profession, unchanged from
        // before this piece.
        boolean idleTrade = activity == SettlerActivity.IDLE && profession != Profession.NONE;

        idleState.animateWhen(!moving
            && ((activity == SettlerActivity.IDLE && profession == Profession.NONE)
                || (idleTrade && profession == Profession.LUMBERER
                    && getMainHandItem().isEmpty())
                // The Mayor has no trade idle of its own; without this it
                // stood rigid with no breathing or sway.
                || (idleTrade && profession == Profession.MAYOR)
                || activity == SettlerActivity.EATING || activity == SettlerActivity.CELEBRATING),
            tickCount);
        // One gate per motion family, not per profession -- GUARD/ARCHER
        // share the idleSentryState clock, but SettlerModel selects the
        // equipment-specific IDLE_SENTRY / IDLE_ARCHER definition.
        // SMITH/SMELTER share idleForgeState,
        // BAKER/MILLER share idleBakerState, COOK/BREWER share
        // idleCookState, MASON/CARPENTER/SAWYER share idleSightEdgeState,
        // BUTCHER/TANNER share idleBladeBenchState (see each clip's own
        // sharing justification in SettlerAnimations). !moving matches
        // every other stationary work/idle gate in this method.
        // TRADES-1: a shepherd's watchful stance over the paddock is the
        // same watching-the-field idle as the farmer's own -- both read as
        // someone minding open ground, not a bench, and the leaning-on-a-
        // tool grip reads the same whether the shaft is a hoe or a
        // shepherd's crook.
        idleFarmerState.animateWhen(idleTrade && !moving
            && (profession == Profession.FARMER || profession == Profession.HERDER), tickCount);
        idleLumbererState.animateWhen(idleTrade && !moving
            && profession == Profession.LUMBERER
            && !getMainHandItem().isEmpty(), tickCount);
        // TRADES-1: a hunter's alert, weight-shifted readiness scanning for
        // game is the same watching-for-movement stance as the guard/
        // archer's own sentry idle -- the tool differs, the way of standing
        // does not.
        idleSentryState.animateWhen(idleTrade && !moving
            && (profession == Profession.GUARD || profession == Profession.ARCHER
                || profession == Profession.HUNTER
                // battle roles: IDLE_SPEARMAN / IDLE_LONGSWORDSMAN (SettlerModel picks the clip)
                || profession == Profession.SPEARMAN || profession == Profession.LONGSWORDSMAN), tickCount);
        idleCourierState.animateWhen(idleTrade && !moving
            && profession == Profession.COURIER, tickCount);
        idleTraderState.animateWhen(idleTrade && !moving
            && profession == Profession.TRADER, tickCount);
        // ARMOURY-3: an armourer's idle is the same forge-side wait as the
        // smith's and the smelter's -- both hands flex, then wipe down the
        // apron -- so it joins this gate rather than getting a bespoke clip.
        idleForgeState.animateWhen(idleTrade && !moving
            && (profession == Profession.SMITH || profession == Profession.SMELTER
                || profession == Profession.ARMOURER), tickCount);
        idleBakerState.animateWhen(idleTrade && !moving
            && (profession == Profession.BAKER || profession == Profession.MILLER), tickCount);
        idleCookState.animateWhen(idleTrade && !moving
            && (profession == Profession.COOK || profession == Profession.BREWER), tickCount);
        idleSightEdgeState.animateWhen(idleTrade && !moving
            && (profession == Profession.MASON || profession == Profession.CARPENTER
                || profession == Profession.SAWYER
                // BUILDER lane: sighting a straight edge is the builder's idle too.
                || profession == Profession.BUILDER), tickCount);
        idleFletcherState.animateWhen(idleTrade && !moving
            && profession == Profession.FLETCHER, tickCount);
        idleMinerState.animateWhen(idleTrade && !moving
            && profession == Profession.MINER, tickCount);
        idleScholarState.animateWhen(idleTrade && !moving
            && profession == Profession.SCHOLAR, tickCount);
        idleInnkeeperState.animateWhen(idleTrade && !moving
            && profession == Profession.INNKEEPER, tickCount);
        idleWeaverState.animateWhen(idleTrade && !moving
            && profession == Profession.WEAVER, tickCount);
        idleBladeBenchState.animateWhen(idleTrade && !moving
            && (profession == Profession.BUTCHER || profession == Profession.TANNER), tickCount);
        // TRADES-1: FISHER is the only one of the three new trades whose
        // gesture (patient, watching the water) is not genuinely the same
        // as an existing idle -- see IDLE_FISHER's own catalogue entry.
        idleFisherState.animateWhen(idleTrade && !moving
            && profession == Profession.FISHER, tickCount);

        farmState.animateWhen(activity == SettlerActivity.WORK_FARM && !moving, tickCount);
        chopState.animateWhen(activity == SettlerActivity.WORK_CHOP && !moving, tickCount);
        eatState.animateWhen(activity == SettlerActivity.EATING, tickCount);
        restState.animateWhen(activity == SettlerActivity.RESTING && !isSleeping(),
            tickCount);
        // OUT_OF_AMMO keeps the stance: an archer standing at post with an
        // empty rack still holds the dedicated bow-ready pose -- dropping to the plain
        // idle there made the starving state read as a broken settler,
        // which is the exact misread the activity exists to prevent.
        stanceState.animateWhen((activity == SettlerActivity.PATROLLING
            || activity == SettlerActivity.COMBAT
            || activity == SettlerActivity.OUT_OF_AMMO
            || activity == SettlerActivity.RETREATING) && !moving, tickCount);

        // SLICE ANIM-1 additions.
        plantState.animateWhen(activity == SettlerActivity.WORK_PLANT && !moving, tickCount);
        harvestState.animateWhen(activity == SettlerActivity.WORK_HARVEST && !moving, tickCount);
        waterState.animateWhen(activity == SettlerActivity.WORK_WATER && !moving, tickCount);
        limbState.animateWhen(activity == SettlerActivity.WORK_LIMB && !moving, tickCount);
        // Keep the arms-only load hold alive through the final step and ease
        // it out for five ticks after the activity changes. Gating this on
        // walk speed made both arms snap at the old 0.05 movement threshold.
        boolean haulingLog = activity == SettlerActivity.HAULING_LOG;
        boolean startingHaul = haulingLog && haulPoseBlend <= 0.0F;
        if (startingHaul) {
            // Latch before the bag is emptied at deposit. Reading live fill
            // every frame made a full load jump heavy -> light -> idle.
            heavyHaulPoseTarget = carryFraction() >= HAUL_HEAVY_ENTER_FILL;
        }
        haulPoseBlend = haulingLog
            ? Math.min(1.0F, haulPoseBlend + HAUL_POSE_BLEND_STEP)
            : Math.max(0.0F, haulPoseBlend - HAUL_POSE_BLEND_STEP);

        float visualLoadTarget = carryFraction();
        if (visualCarryFraction < visualLoadTarget) {
            visualCarryFraction = Math.min(visualLoadTarget,
                visualCarryFraction + VISUAL_LOAD_BLEND_STEP);
        } else if (visualCarryFraction > visualLoadTarget) {
            visualCarryFraction = Math.max(visualLoadTarget,
                visualCarryFraction - VISUAL_LOAD_BLEND_STEP);
        }
        float heavyTarget = heavyHaulPoseTarget ? 1.0F : 0.0F;
        if (heavyHaulPoseBlend < heavyTarget) {
            heavyHaulPoseBlend = Math.min(heavyTarget,
                heavyHaulPoseBlend + HAUL_POSE_BLEND_STEP);
        } else if (heavyHaulPoseBlend > heavyTarget) {
            heavyHaulPoseBlend = Math.max(heavyTarget,
                heavyHaulPoseBlend - HAUL_POSE_BLEND_STEP);
        }
        if (haulPoseBlend <= 0.0F) {
            heavyHaulPoseTarget = false;
            heavyHaulPoseBlend = 0.0F;
        }
        haulState.animateWhen(haulPoseBlend > 0.0F, tickCount);
        // Presentation-only martial locomotion state. Guards own ordinary
        // patrols; archers also need their distinct bow-carry overlay while
        // closing/opening the combat ring or walking to an empty tower rack.
        // This does not alter navigation or combat authority -- it only keeps
        // a real MAINHAND bow out of the civilian arm-swing/sword silhouette.
        patrolState.animateWhen(moving
            && ((profession == Profession.GUARD
                    && (activity == SettlerActivity.PATROLLING
                        || activity == SettlerActivity.COMBAT
                        || activity == SettlerActivity.RETREATING))
                || (profession == Profession.ARCHER
                    && (activity == SettlerActivity.PATROLLING
                        || activity == SettlerActivity.COMBAT
                        || activity == SettlerActivity.OUT_OF_AMMO))), tickCount);
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
        stirState.animateWhen(activity == SettlerActivity.WORK_STIR && !moving, tickCount);
        planeState.animateWhen(activity == SettlerActivity.WORK_PLANE && !moving, tickCount);
        chiselState.animateWhen(activity == SettlerActivity.WORK_CHISEL && !moving, tickCount);
        fletchState.animateWhen(activity == SettlerActivity.WORK_FLETCH && !moving, tickCount);
        scrapeState.animateWhen(activity == SettlerActivity.WORK_SCRAPE && !moving, tickCount);
        // Hunter butchery: own states on the butcher's CLEAVE and the
        // tanner's TANNER_SCRAPE clips (same stationary-work gate).
        butcherState.animateWhen(activity == SettlerActivity.WORK_BUTCHER && !moving, tickCount);
        skinState.animateWhen(activity == SettlerActivity.WORK_SKIN && !moving, tickCount);
        // TRADES-1: same stationary-work gate as every clip just above --
        // moving cancels the trade clip and plain WALK takes over instead.
        shearState.animateWhen(activity == SettlerActivity.WORK_SHEAR && !moving, tickCount);
        fishState.animateWhen(activity == SettlerActivity.WORK_FISH && !moving, tickCount);
        huntState.animateWhen(activity == SettlerActivity.WORK_HUNT, tickCount);
        carcassCarryState.animateWhen(activity == SettlerActivity.HAULING_CARCASS, tickCount);
        // ANIM-TRUTH-0A: these are transaction clocks, not ambient loops.
        // They start at the exact server-authored phase boundary and carry
        // no per-entity phase offset in SettlerModel.
        craftState.animateWhen(activity == SettlerActivity.WORK_CRAFT && !moving,
            tickCount);
        craftStoreState.animateWhen(
            activity == SettlerActivity.STORE_CRAFT_OUTPUT && !moving,
            tickCount);
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
        // Weapon-aware: a warhammer's plain swing runs 24 ticks, not 10.
        com.hearthstead.entity.combat.WeaponClass heldWeapon = guardWeaponClass();
        if (meleeState.isStarted() && meleeState.getAccumulatedTime()
                > 50L * GuardMove.LIGHT_A.lengthTicks(heldWeapon)) {
            meleeState.stop();
        }
        if (guardFinisherState.isStarted()
            && guardFinisherState.getAccumulatedTime() > 700L) {
            guardFinisherState.stop();
        }
        // Guard moveset clip lengths (GuardMove authored lengths at 20 Hz).
        if (guardLightBState.isStarted()
            && guardLightBState.getAccumulatedTime() > 500L) {
            guardLightBState.stop();
        }
        if (guardHeavyState.isStarted()
            && guardHeavyState.getAccumulatedTime()
                > 50L * GuardMove.HEAVY.lengthTicks(heldWeapon)) {
            guardHeavyState.stop();
        }
        if (guardShieldBashState.isStarted()
            && guardShieldBashState.getAccumulatedTime() > 500L) {
            guardShieldBashState.stop();
        }
        if (guardStaggerState.isStarted()
            && guardStaggerState.getAccumulatedTime() > 600L) {
            guardStaggerState.stop();
        }
        if (roleMoveState.isStarted() && (roleMoveId < 0
            || roleMoveId >= com.hearthstead.entity.combat.role.RoleMove.values().length
            || roleMoveState.getAccumulatedTime()
                > com.hearthstead.entity.combat.role.RoleMove.values()[roleMoveId].lengthTicks() * 50L)) {
            roleMoveState.stop();
        }
        // 800 ms: the authored ARCHER_RELOAD (0.75 s quiver fetch) runs on this event (anim lane);
        // the procedural bow release still finishes its 400 ms blend inside it.
        if (archerLooseState.isStarted()
            && archerLooseState.getAccumulatedTime() > 800L) {
            archerLooseState.stop();
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
        // PICKUP_STOW is 1.40 s (catalogue section 21.1, rebuilt 2026-08-25
        // -- the extra 4 ticks bought a real bag-contact hold and a
        // decelerating return, which is what stopped the expiry snapping on
        // film), mirroring gatherState's own expiry above -- a one-shot
        // with no server goal driving it back to idle, so this is the only
        // place its clock runs out.
        if (pickupState.isStarted() && pickupState.getAccumulatedTime() > 1450L) {
            pickupState.stop();
        }
        // The four lumber collection beats are separate one-shots so every
        // state can be reviewed independently and no courier animation is
        // silently reused for a different physical action.
        if (workContainerDownState.isStarted()
            && workContainerDownState.getAccumulatedTime() > 1450L) {
            workContainerDownState.stop();
        }
        if (groundItemPickupState.isStarted()
            && groundItemPickupState.getAccumulatedTime() > 1050L) {
            groundItemPickupState.stop();
        }
        if (workContainerStowState.isStarted()
            && workContainerStowState.getAccumulatedTime() > 1150L) {
            workContainerStowState.stop();
        }
        if (workContainerUpState.isStarted()
            && workContainerUpState.getAccumulatedTime() > 1650L) {
            workContainerUpState.stop();
        }
        if (bagToChestUnloadState.isStarted()
            && bagToChestUnloadState.getAccumulatedTime() > 4050L) {
            bagToChestUnloadState.stop();
        }
        // BLESSING_RECEIVE is 1.60 s. The small safety margin lets the final
        // keyed settle render before releasing every bone back to its real
        // work/idle pose; no activity is changed or restored here.
        // The greeting is held for as long as the goal says (until EV_GUARD_SALUTE_END or
        // EV_GUARD_SALUTE_CANCEL); this is only a safety net past the goal's own 20 s cap.
        if (guardSaluteState.isStarted() && guardSaluteState.getAccumulatedTime() > 30_000L) {
            guardSaluteState.stop();
        }
        if (guardSaluteEndState.isStarted() && guardSaluteEndState.getAccumulatedTime() > 1250L) {
            guardSaluteEndState.stop();
        }
        if (guardNodState.isStarted() && guardNodState.getAccumulatedTime() > 750L) {
            guardNodState.stop();
        }
        if (blessingReceiveState.isStarted()
            && blessingReceiveState.getAccumulatedTime() > 1650L) {
            blessingReceiveState.stop();
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EV_CELEBRATE) {
            celebrateState.start(tickCount);
        } else if (id == EV_MELEE) {
            startOnlyGuardStrike(meleeState);
        } else if (id == EV_GUARD_FINISHER) {
            startOnlyGuardStrike(guardFinisherState);
        } else if (id == EV_GUARD_FINISHER_CANCEL) {
            guardFinisherState.stop();
        } else if (id == EV_GUARD_LIGHT_B) {
            startOnlyGuardStrike(guardLightBState);
        } else if (id == EV_GUARD_HEAVY) {
            startOnlyGuardStrike(guardHeavyState);
        } else if (id == EV_GUARD_SHIELD_BASH) {
            startOnlyGuardStrike(guardShieldBashState);
        } else if (id >= EV_ROLE_MOVE_BASE
            && id < EV_ROLE_MOVE_BASE + com.hearthstead.entity.combat.role.RoleMove.values().length) {
            stopGuardStrikes();
            guardStaggerState.stop();
            roleMoveId = id - EV_ROLE_MOVE_BASE;
            roleMoveState.start(tickCount);
        } else if (id == EV_GUARD_STAGGER) {
            stopGuardStrikes();
            guardStaggerState.start(tickCount);
        } else if (id == EV_GUARD_SALUTE) {
            guardSaluteEndState.stop();
            guardSaluteState.start(tickCount);
        } else if (id == EV_GUARD_SALUTE_END) {
            guardSaluteState.stop();
            guardSaluteEndState.start(tickCount);
        } else if (id == EV_GUARD_SALUTE_CANCEL) {
            guardSaluteState.stop();
            guardSaluteEndState.stop();
        } else if (id == EV_GUARD_NOD) {
            guardNodState.start(tickCount);
        } else if (id == EV_ARCHER_LOOSE) {
            archerLooseState.start(tickCount);
        } else if (id == EV_SHIELD_BLOCK) {
            shieldState.start(tickCount);
        } else if (id == EV_WAKE) {
            wakeState.start(tickCount);
        } else if (id == EV_COURIER_LIFT) {
            liftState.start(tickCount);
        } else if (id == EV_COURIER_SET_DOWN) {
            setDownState.start(tickCount);
        } else if (id == EV_PICKUP) {
            pickupState.start(tickCount);
        } else if (id == EV_GATHER_LOG) {
            gatherState.start(tickCount);
        } else if (id == EV_LEAP_STRIKE) {
            leapState.start(tickCount);
        } else if (id == EV_BLESSING_RECEIVE) {
            blessingReceiveState.start(tickCount);
        } else if (id == EV_WORK_CONTAINER_DOWN) {
            startOnlyWorkContainerState(workContainerDownState);
        } else if (id == EV_GROUND_ITEM_PICKUP) {
            startOnlyWorkContainerState(groundItemPickupState);
        } else if (id == EV_WORK_CONTAINER_STOW) {
            startOnlyWorkContainerState(workContainerStowState);
        } else if (id == EV_WORK_CONTAINER_UP) {
            startOnlyWorkContainerState(workContainerUpState);
        } else if (id == EV_BAG_TO_CHEST_UNLOAD) {
            // A duplicate/reordered event may restart neither the pose nor its
            // contact clock. A legitimate next cycle arrives after the full
            // four-second handoff and may restart the same AnimationState.
            if (!bagToChestUnloadState.isStarted()
                || bagToChestUnloadState.getAccumulatedTime() >= 3900L) {
                bagToChestUnloadState.start(tickCount);
            }
        } else {
            super.handleEntityEvent(id);
        }
    }

    /** One guard strike at a time: a newer move always replaces the older pose. */
    private void startOnlyGuardStrike(AnimationState selected) {
        stopGuardStrikes();
        guardStaggerState.stop();
        selected.start(tickCount);
    }

    private void stopGuardStrikes() {
        roleMoveState.stop();
        meleeState.stop();
        guardFinisherState.stop();
        guardLightBState.stop();
        guardHeavyState.stop();
        guardShieldBashState.stop();
    }

    /** Network reordering must never add two mutually exclusive full-body poses. */
    private void startOnlyWorkContainerState(AnimationState selected) {
        workContainerDownState.stop();
        groundItemPickupState.stop();
        workContainerStowState.stop();
        workContainerUpState.stop();
        selected.start(tickCount);
    }

    /** Shared presentation truth for model and sound: a raised shield may be
     * shown/heard only when the synced OFFHAND stack is the physical shield. */
    public boolean hasPhysicalOffhandShield() {
        return shieldThudDelayFor(getOffhandItem()) >= 0;
    }

    /** Client-visible equipment truth used only to select a matching pose. */
    public boolean hasPhysicalMainhandSword() {
        return getMainHandItem().is(ItemTags.SWORDS);
    }

    /** The weapon TYPE in the main hand (tag-classified, tier-agnostic). */
    public com.hearthstead.entity.combat.WeaponClass guardWeaponClass() {
        return com.hearthstead.entity.combat.WeaponClass.of(getMainHandItem());
    }

    /**
     * A Guard fights with any melee weapon type: sword, short sword,
     * longsword, spear, great axe, halberd or warhammer. Timing, reach and
     * clips follow the type (GuardMove#hitTick(WeaponClass)).
     */
    public boolean hasGuardMeleeWeapon() {
        return switch (guardWeaponClass()) {
            case SWORD, SHORT_SWORD, LONGSWORD, SPEAR, GREAT_AXE, HALBERD,
                WARHAMMER -> true;
            default -> false;
        };
    }

    /**
     * Vanilla melee reach, extended by the weapon's reach scale for long
     * weapons (spear, halberd, longsword). Swords keep the vanilla box.
     */
    public boolean isWithinGuardReach(LivingEntity target) {
        double scale = GuardMove.weaponReach(guardWeaponClass());
        if (scale <= 1.0D) {
            return isWithinMeleeAttackRange(target);
        }
        double inflate = 0.828D + (scale - 1.0D)
            * (getBbWidth() * 0.5D + 0.828D + target.getBbWidth() * 0.5D);
        return getBoundingBox().inflate(inflate, 0.0D, inflate)
            .intersects(target.getHitbox());
    }

    /** Archers currently request the vanilla bow, not a crossbow. */
    public boolean hasPhysicalMainhandBow() {
        return getMainHandItem().is(Items.BOW);
    }

    /**
     * Complete server-side blade-contact predicate, shared by the goal's
     * start/contact gates and the entity's damage seam. It intentionally
     * includes settlement scope here rather than trusting whichever target a
     * caller happens to pass.
     */
    public boolean isAuthorizedMeleeContactTarget(LivingEntity target) {
        if (!(level() instanceof ServerLevel level)
            || getProfession() != Profession.GUARD
            || !hasGuardMeleeWeapon()
            || !EquipmentRequests.readyForProfession(level, this,
                Profession.GUARD)
            || target == null || target.level() != level
            || !target.isAlive() || target.isRemoved()
            || !(target instanceof Enemy) || !canAttack(target)
            || !isWithinGuardReach(target)
            || !getSensing().hasLineOfSight(target)) {
            return false;
        }
        Settlement settlement = settlement();
        if (settlement == null) {
            return false;
        }
        if (com.hearthstead.settlement.guard.BannerTeams.active(this) != null) {
            return com.hearthstead.settlement.guard.BannerTeams.allowsTarget(this, target);
        }
        double defendedRadius = settlement.radius + 8.0;
        if (target.blockPosition().distSqr(settlement.center)
            > defendedRadius * defendedRadius) {
            return false;
        }
        return !(target instanceof RaiderEntity raider)
            || raider.settlementId() == null
            || settlement.id.equals(raider.settlementId());
    }

    /** Package-private test seam; keeps the sound scheduler's decision pure. */
    static int shieldThudDelayFor(ItemStack offhand) {
        return offhand.is(Items.SHIELD) ? SHIELD_THUD_DELAY : -1;
    }

    /**
     * Starts the visible wind-up and issues its one server-only contact
     * ticket. The animation's authored blade contact is
     * {@link GuardMeleeGoal#MELEE_CONTACT_TICK} ticks after this event.
     *
     * <p>This seam deliberately refuses clients, non-guards, missing swords,
     * dead targets and overlapping swings. Range, sight and settlement
     * hostility are rechecked by {@link GuardMeleeGoal} both before calling
     * this method and again at contact.
     *
     * @return a positive one-use ticket, or zero when no wind-up was issued
     */
    public long beginMeleeWindup(LivingEntity target) {
        return beginGuardMove(target, GuardMove.LIGHT_A);
    }

    /**
     * Starts one moveset wind-up and issues its single contact ticket, due
     * {@link GuardMove#hitTick()} ticks from now. The broadcast event starts
     * the matching client clip on the same tick, so the authored impact frame
     * and the server contact coincide.
     *
     * <p>Only the light links (whose contact is the drive's tick four) may
     * claim a raider's cinematic opportunity; that presentation keeps its
     * "ordinary damage only" contract. A shield bash needs a physical shield;
     * a staggered guard cannot begin anything.
     */
    public long beginGuardMove(LivingEntity target, GuardMove move) {
        if (move == null || !(level() instanceof ServerLevel level)
            || isCombatStaggered()
            || (move == GuardMove.SHIELD_BASH && (!hasPhysicalOffhandShield()
                || !GuardMove.allowsShieldBash(guardWeaponClass())))
            || !isAuthorizedMeleeContactTarget(target)) {
            return MeleeContactLedger.NO_TICKET;
        }
        com.hearthstead.entity.combat.WeaponClass weapon = guardWeaponClass();
        long ticket = meleeContacts.begin(target.getUUID(),
            level.getGameTime() + move.hitTick(weapon));
        if (ticket == MeleeContactLedger.NO_TICKET) {
            return ticket;
        }
        pendingGuardMove = move;
        pendingGuardMoveTicket = ticket;
        pendingGuardMoveTargetId = target.getUUID();
        pendingGuardMoveContactTick = level.getGameTime() + move.hitTick(weapon);
        // The cinematic drive's contact is the sword's tick four: only a
        // sword-timed light may present as it.
        if (move.isLight() && GuardMove.swordTimed(weapon)
            && target instanceof RaiderEntity raider
            && com.hearthstead.finisher.FinisherHooks.guardMayClaim(this, raider)
            && raider.claimCinematicOpportunity(getUUID(), level.getGameTime())) {
            cinematicFinisherTicket = ticket;
            cinematicFinisherTargetId = target.getUUID();
            level.broadcastEntityEvent(this, EV_GUARD_FINISHER);
            return ticket;
        }
        level.broadcastEntityEvent(this, switch (move) {
            // The ordinary ticket remains the usual animated strike.
            case LIGHT_A -> EV_MELEE;
            case LIGHT_B -> EV_GUARD_LIGHT_B;
            case COMBO_FINISHER -> EV_GUARD_FINISHER;
            case HEAVY -> EV_GUARD_HEAVY;
            case SHIELD_BASH -> EV_GUARD_SHIELD_BASH;
        });
        return ticket;
    }

    /**
     * Ticks until this guard's pending HEAVY or combo finisher lands on
     * {@code target}, or -1. Skirmishers read it to sidestep the blow.
     */
    public int ticksUntilHeavyBlowOn(LivingEntity target) {
        if (!(level() instanceof ServerLevel level) || target == null
            || pendingGuardMoveTicket == MeleeContactLedger.NO_TICKET
            || !meleeContacts.isActive(pendingGuardMoveTicket)
            || (pendingGuardMove != GuardMove.HEAVY
                && pendingGuardMove != GuardMove.COMBO_FINISHER)
            || !target.getUUID().equals(pendingGuardMoveTargetId)) {
            return -1;
        }
        long in = pendingGuardMoveContactTick - level.getGameTime();
        return in < 0L ? -1 : (int) Math.min(in, Integer.MAX_VALUE);
    }

    /** The move bound to a still-live ticket, or null once it is spent or cancelled. */
    public GuardMove guardMoveFor(long ticket) {
        return ticket != MeleeContactLedger.NO_TICKET
            && ticket == pendingGuardMoveTicket
            && meleeContacts.isActive(ticket) ? pendingGuardMove : null;
    }

    /**
     * A landed enemy heavy rocks this guard: any pending wind-up is cancelled
     * (its ticket can no longer deal damage) and no new move may begin until
     * the stagger lapses. Runtime-only; a reload simply ends it.
     */
    public void applyCombatStagger(int ticks) {
        if (ticks <= 0 || !(level() instanceof ServerLevel level) || !isAlive()) {
            return;
        }
        long ticket = pendingGuardMoveTicket;
        if (ticket != MeleeContactLedger.NO_TICKET && meleeContacts.isActive(ticket)) {
            cancelMeleeContact(ticket);
        }
        combatStaggerUntil = Math.max(combatStaggerUntil, level.getGameTime() + ticks);
        getNavigation().stop();
        level.broadcastEntityEvent(this, EV_GUARD_STAGGER);
    }

    public boolean isCombatStaggered() {
        return level() instanceof ServerLevel level
            && level.getGameTime() < combatStaggerUntil;
    }

    /**
     * Consumes one exact contact ticket and applies one vanilla damage pass.
     * Ticket consumption occurs before damage, so a retry or re-entrant call
     * cannot duplicate it. Damage and blade audio are emitted by this single
     * server-tick transaction; a refused/immune hit remains silent.
     */
    public boolean commitMeleeContact(long ticket, LivingEntity target) {
        if (!(level() instanceof ServerLevel level) || target == null) {
            return false;
        }
        MeleeContactLedger.Attempt attempt = meleeContacts.consumeAttempt(
            ticket, target.getUUID(), level.getGameTime());
        GuardMove committedMove = null;
        if (ticket == pendingGuardMoveTicket) {
            committedMove = pendingGuardMove;
            pendingGuardMove = null;
            pendingGuardMoveTicket = MeleeContactLedger.NO_TICKET;
        }
        if (attempt == null) {
            cancelCinematicFinisher(ticket);
            return false;
        }
        boolean finishing = cinematicFinisherTicket == ticket
            && target.getUUID().equals(cinematicFinisherTargetId);
        if (finishing && (!(target instanceof RaiderEntity raider)
                || !raider.beginCinematicFinisherImpact(getUUID(),
                    level.getGameTime()))) {
            cancelCinematicFinisher(ticket);
            return false;
        }
        // Consume first, validate second: a weapon/range/hostility failure is
        // terminal for this ticket and cannot be repaired then retried on the
        // same tick to resurrect the contact.
        if (!isAuthorizedMeleeContactTarget(target)) {
            if (finishing && target instanceof RaiderEntity raider) {
                raider.clearCinematicOpportunity(
                    CinematicOpportunity.ClearReason.TARGET_LOST);
            }
            cancelCinematicFinisher(ticket);
            return false;
        }
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            MeleeContactLedger.Commit contact = meleeContacts.commit(attempt);
            if (contact != null) {
                Settlement settlement = settlement();
                AuthorityTelemetry.emit(level,
                    AuthorityTelemetry.Event.MELEE_CONTACT_COMMITTED,
                    AuthorityTelemetry.Result.COMMITTED,
                    AuthorityTelemetry.Fields.state(
                        settlement == null ? null : settlement.id,
                        "attacker:" + getUUID() + "/victim:" + target.getUUID(),
                        contact.revisionBefore(), contact.revisionAfter(),
                        contact.countBefore(), contact.countAfter(),
                        "attack:" + contact.actionId()));
            }
            if (committedMove != GuardMove.SHIELD_BASH) {
                // A bash lands the shield, not the edge; its thud is the
                // goal's impact cue (GuardMeleeGoal#applyImpact).
                level.playSound(null, target.blockPosition(),
                    ModSounds.BLADE_HIT.get(), SoundSource.NEUTRAL,
                    0.85F, 0.95F + random.nextFloat() * 0.1F);
            }
            if (target instanceof RaiderEntity raider && !finishing) {
                raider.offerCinematicOpportunity(getUUID(), level.getGameTime());
            } else if (finishing && target instanceof RaiderEntity raider
                && raider.isAlive()) {
                // A finishable raider becomes a full execution (rarer than a player's).
                if (!com.hearthstead.finisher.FinisherHooks.onGuardFinisherContact(this, raider)) {
                    raider.triggerCinematicStagger();
                }
            }
        }
        if (finishing && !hit && target instanceof RaiderEntity raider) {
            raider.clearCinematicOpportunity(
                CinematicOpportunity.ClearReason.TARGET_LOST);
        }
        finishCinematicFinisher(ticket);
        return hit;
    }

    /** Cancels only the matching swing; a stale goal cannot cancel a newer one. */
    public void cancelMeleeContact(long ticket) {
        meleeContacts.cancel(ticket);
        if (ticket == pendingGuardMoveTicket) {
            pendingGuardMove = null;
            pendingGuardMoveTicket = MeleeContactLedger.NO_TICKET;
        }
        cancelCinematicFinisher(ticket);
    }

    /** The accepted drive may play its recovery after its ticket is consumed. */
    private void finishCinematicFinisher(long ticket) {
        if (cinematicFinisherTicket == ticket) {
            cinematicFinisherTicket = MeleeContactLedger.NO_TICKET;
            cinematicFinisherTargetId = null;
        }
    }

    /** A miss, target change or side intervention must stop the optional pose now. */
    private void cancelCinematicFinisher(long ticket) {
        if (cinematicFinisherTicket == ticket) {
            UUID targetId = cinematicFinisherTargetId;
            cinematicFinisherTicket = MeleeContactLedger.NO_TICKET;
            cinematicFinisherTargetId = null;
            if (level() instanceof ServerLevel server) {
                if (targetId != null
                    && server.getEntity(targetId) instanceof RaiderEntity raider) {
                    raider.clearCinematicOpportunity(
                        CinematicOpportunity.ClearReason.TARGET_LOST);
                }
                level().broadcastEntityEvent(this, EV_GUARD_FINISHER_CANCEL);
            }
        }
    }

    /** Target-side cancellation seam used by an arrow, side attack or expiry. */
    public void cancelCinematicFinisherFor(UUID targetId) {
        if (targetId != null && targetId.equals(cinematicFinisherTargetId)) {
            long ticket = cinematicFinisherTicket;
            meleeContacts.cancel(ticket);
            cancelCinematicFinisher(ticket);
        }
    }

    /** Package-visible proof seam for deterministic authority tests. */
    boolean hasMeleeContact(long ticket) {
        return meleeContacts.isActive(ticket);
    }

    /** Read-only terminal cardinality used by adversarial GameTests. */
    public long committedMeleeContacts() {
        return meleeContacts.committedCount();
    }

    /**
     * Guards may deal ordinary melee damage only through
     * {@link #commitMeleeContact(long, LivingEntity)}. Keeping this override
     * fail-closed prevents a future vanilla goal from silently restoring the
     * old damage-before-animation path.
     */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        return getProfession() != Profession.GUARD
            && super.doHurtTarget(target);
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

    /**
     * The universal pickup: any settler, any trade, stooping for something
     * on the ground -- mirrors {@link #triggerCourierLift()} exactly, one
     * event broadcast that starts a client-side one-shot. Deliberately not
     * trade-specific the way {@link #triggerGatherLog()} is: PICKUP_STOW is
     * the clip for "a hand reaches down and comes back up with something in
     * it", which is the same motion whether what's on the ground is a dropped
     * tool, a fumbled crate, or anything else a future goal wants to animate
     * without inventing its own one-shot for it.
     */
    public void triggerPickup() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_PICKUP);
        }
    }

    /**
     * Presentation-only bow release snap emitted after the physical projectile
     * has entered the level. Both ranged professions use the same stable
     * packet; their goals retain separate mechanics and timings.
     */
    public void triggerBowLoose() {
        if (!level().isClientSide) {
            level().broadcastEntityEvent(this, EV_ARCHER_LOOSE);
        }
    }

    /** Existing Archer API retained for source and packet compatibility. */
    public void triggerArcherLoose() {
        triggerBowLoose();
    }

    /**
     * Plays the permanent-binding acceptance without borrowing or changing
     * {@link SettlerActivity}. The server broadcasts; only each client starts
     * its render-side state in {@link #handleEntityEvent(byte)}.
     */
    public void triggerBlessingReceive() {
        if (!level().isClientSide) {
            broadcastBlessingReceiveEvent();
        }
    }

    /** Overridable packet seam; production always emits stable event 73. */
    protected void broadcastBlessingReceiveEvent() {
        level().broadcastEntityEvent(this, EV_BLESSING_RECEIVE);
    }

    private record ScheduledBlessingCue(BlessingId blessing, long dueTick) {
    }

    /** Fired from RestAtNightGoal when a sleeping settler naturally wakes. */
    public void triggerWakeStretch() {
        if (!level().isClientSide && wakeBroadcastIn < 0) {
            // Catalogue §16.2: stagger village wake events >= 8 ticks per
            // settler so the yawns land as a morning murmur, not in unison.
            wakeBroadcastIn = (getId() % 5) * 8;
            com.hearthstead.ambient.LivingVillage.onWake(this);
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
        if (shieldThudIn >= 0 && shieldThudIn-- == 0
            && hasPhysicalOffhandShield()) {
            playAccent(ModSounds.SHIELD_THUD.get(), 1.0F,
                0.95F + random.nextFloat() * 0.1F);
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
        // Entity interaction runs before Item#interactLivingEntity. Yield the
        // definitive result whenever a sneaking seal is the intended action,
        // otherwise this sheet-open response would consume the click first.
        // A main-hand item may consume interaction before vanilla ever tries
        // the offhand. Dispatch that waiting physical seal here, using its
        // actual stack, unless the main hand itself is already a seal.
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND
            && !(held.getItem() instanceof BlessingSealItem)
            && player.getOffhandItem().getItem()
                instanceof BlessingSealItem offhandSeal) {
            return offhandSeal.bindToSettler(player.getOffhandItem(), player, this);
        }
        if (player.isShiftKeyDown()
            && held.getItem() instanceof BlessingSealItem) {
            return InteractionResult.PASS;
        }

        // Inventory access is deliberately narrower than "sneaking": only
        // an actually empty MAIN_HAND can open it. A held Job Emblem or any
        // future item therefore keeps NeoForge's normal interactLivingEntity
        // dispatch instead of being swallowed by this entity first.
        if (player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND
            && player.getMainHandItem().isEmpty()
            && player.getOffhandItem().isEmpty()) {
            if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                if (getProfession() == Profession.BUILDER) {
                    com.hearthstead.network.BuilderNeedsNetwork.open(serverPlayer, this);
                    return InteractionResult.CONSUME;
                }
                reconcileEquipmentNeedNow();
                serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, ignored) ->
                        new SettlerInventoryMenu(containerId, inventory, this),
                    Component.translatable("hearthstead.settler.inventory.title",
                        getSettlerName())), buffer -> {
                            buffer.writeVarInt(getId());
                            buffer.writeUUID(getUUID());
                        });
                Settlement settlement = settlement();
                if (settlement != null) {
                    com.hearthstead.settlement.journey.JourneyServerHooks
                        .noteSettlerInventoryViewed(serverPlayer, settlement, this);
                }
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        // Non-empty hands must reach their item interaction (notably Job
        // Emblems). Sneaking non-seal items also pass through unchanged.
        if (!held.isEmpty()) {
            return super.mobInteract(player, hand);
        }

        // Do not let an empty OFFHAND interaction open a second screen after
        // a non-empty main hand has already passed to its item.
        if (hand != InteractionHand.MAIN_HAND) {
            return super.mobInteract(player, hand);
        }

        // Ordinary empty-hand right-click remains the inspection sheet.
        if (player.isShiftKeyDown()) {
            return super.mobInteract(player, hand);
        }
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (voiceCooldown <= 0) {
                level().playSound(null, blockPosition(), ModSounds.SETTLER_HM.get(),
                    SoundSource.NEUTRAL, 0.9F, 0.95F + random.nextFloat() * 0.1F);
                voiceCooldown = 100;
            }
            com.hearthstead.network.PayloadSend.toPlayer(
                serverPlayer, new OpenSettlerScreenPayload(getId()));
            // Sent right after: the screen opens on the first packet and the
            // second fills in what is not on the synced entity data (D-014 —
            // it must never sit there with attributes and employment blank).
            com.hearthstead.network.SettlerNetwork.openFor(serverPlayer, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ------------------------------------------------------------ combat ---

    /** Persisted arrows this settler currently owns between rack and release. */
    public int archerQuiverCount() {
        return archerQuiver;
    }

    /** Exact Watchtower that loaned the current persisted quiver, if proven. */
    @Nullable
    public UUID archerQuiverSourceBuildingId() {
        return archerQuiverSource;
    }

    /**
     * Whether every currently carried shaft is proven to come from this exact
     * Watchtower. A positive unprovenanced legacy/malformed count deliberately
     * returns false until it is materialized back into the world.
     */
    public boolean archerQuiverOwnedBy(UUID buildingId) {
        return archerQuiver > 0 && buildingId != null
            && buildingId.equals(archerQuiverSource);
    }

    /**
     * Moves up to {@code count} physical arrows into the bounded quiver.
     * The accepted count is returned so callers can transfer exactly that
     * amount from the source container without minting or deleting stock.
     */
    public int storeArcherQuiverArrows(UUID sourceBuildingId, int count) {
        if (sourceBuildingId == null
            || (sourceBuildingId.getMostSignificantBits() == 0L
                && sourceBuildingId.getLeastSignificantBits() == 0L)) {
            return 0;
        }
        if (archerQuiver > 0 && !sourceBuildingId.equals(archerQuiverSource)) {
            return 0;
        }
        int accepted = Math.min(Math.max(0, count),
            ARCHER_QUIVER_CAPACITY - archerQuiver);
        if (accepted > 0 && archerQuiver == 0) {
            archerQuiverSource = sourceBuildingId;
        }
        archerQuiver += accepted;
        return accepted;
    }

    /**
     * Removes up to {@code count} arrows from persisted quiver ownership.
     * The returned amount is the only quantity a release, rack return or
     * death drop may materialize elsewhere.
     */
    public int takeArcherQuiverArrows(int count) {
        int taken = Math.min(Math.max(0, count), archerQuiver);
        archerQuiver -= taken;
        if (archerQuiver == 0) {
            archerQuiverSource = null;
        }
        return taken;
    }

    // Neutral aliases retain the existing persisted NBT keys and capacity.
    // Archer and Hunter therefore share one physical carried-arrow authority,
    // while their attack and targeting rules remain separate.
    public int carriedArrowCount() {
        return archerQuiverCount();
    }

    @Nullable
    public UUID carriedArrowSourceBuildingId() {
        return archerQuiverSourceBuildingId();
    }

    public boolean carriedArrowsOwnedBy(UUID buildingId) {
        return archerQuiverOwnedBy(buildingId);
    }

    public int storeCarriedArrows(UUID sourceBuildingId, int count) {
        return storeArcherQuiverArrows(sourceBuildingId, count);
    }

    public int takeCarriedArrows(int count) {
        return takeArcherQuiverArrows(count);
    }

    /**
     * Returns Hunter arrows to their exact valid Lodge before the shared
     * durable world fallback runs. Watchtower and malformed-source behavior
     * deliberately remains owned by the established Archer helper.
     */
    public boolean releaseCarriedArrows(ServerLevel level,
                                        @Nullable Settlement settlement) {
        if (carriedArrowCount() <= 0) {
            return true;
        }
        Building source = exactCarriedArrowSource(settlement,
            carriedArrowSourceBuildingId());
        if (source != null && source.type == BuildingType.HUNTERS_LODGE) {
            for (BlockPos pos : WarehouseIndex.containers(level, source)) {
                if (carriedArrowCount() <= 0) {
                    break;
                }
                if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                    continue;
                }
                for (int slot = 0; slot < chest.getContainerSize()
                        && carriedArrowCount() > 0; slot++) {
                    ItemStack stack = chest.getItem(slot);
                    if (stack.isEmpty()) {
                        int moved = Math.min(carriedArrowCount(),
                            new ItemStack(Items.ARROW).getMaxStackSize());
                        chest.setItem(slot, new ItemStack(Items.ARROW, moved));
                        chest.setChanged();
                        takeCarriedArrows(moved);
                    } else if (stack.is(Items.ARROW)
                        && stack.getCount() < stack.getMaxStackSize()) {
                        int moved = Math.min(carriedArrowCount(),
                            stack.getMaxStackSize() - stack.getCount());
                        stack.grow(moved);
                        chest.setChanged();
                        takeCarriedArrows(moved);
                    }
                }
            }
        }
        return ArcherAttackGoal.releaseBorrowedArrows(level, settlement, this);
    }

    @Nullable
    private static Building exactCarriedArrowSource(
            @Nullable Settlement settlement, @Nullable UUID sourceId) {
        if (settlement == null || sourceId == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (sourceId.equals(building.id)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found != null && found.valid
            && (found.type == BuildingType.WATCHTOWER
                || found.type == BuildingType.HUNTERS_LODGE) ? found : null;
    }

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
            // Ordinary health loss owns vanilla hurtTime and the model's
            // procedural hit flinch. A shield pose/thud is emitted only by
            // presentCommittedShieldBlock after final blockedDamage > 0.
        }
        return result;
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        // NeoForge LivingDeathEvent is cancellable. The superclass leaves
        // dead=false when another mod saves this settler; no terminal ledger,
        // equipment, bag or quiver mutation may run in that case.
        if (!dead) {
            return;
        }
        com.hearthstead.util.QaTrace.recordCoopDeath(this, cause);
        if (level() instanceof ServerLevel serverLevel) {
            recordTavernDeathNotice(serverLevel);
            // Chest truth (2026-08-26 raid-night audit): a guard's armor is
            // real kit an armourer forged, and GuardRank#applyEquipment sets
            // every armor slot's drop chance to 0 so the settlement's
            // investment cannot evaporate the first time a guard loses a
            // fight -- vanilla's own dropEquipment then never rolls to drop
            // it, and it never gets cleared from the slot either, so a dead,
            // soon-to-be-removed entity simply took 24 iron ingots of plate
            // with it: no drop, no return, a chest-truth-shaped hole. Return
            // it home through the exact same armoury-then-warehouse-then-
            // hearth chain an ordinary rank supersession already uses
            // (GuardRank#clearEquipment) rather than dropping it loose on
            // the ground the way RaiderEntity's stolen loot does: this kit
            // was made for THIS settlement, so it goes back to the armoury
            // that issued it, not to whoever happens to be standing over the
            // body.
            GuardRank.clearEquipment(this);
            transferTerminalCargo(serverLevel);
            if (settlementId != null) {
                SettlementManager.onSettlerDied(serverLevel, this, cause);
            }
        }
    }

    /**
     * Moves all terminal item authority out of this entity without a sink.
     * A full/quarantined escrow refuses before the source changes; tickDeath
     * then keeps the corpse (and source NBT) alive and retries after the
     * bounded level-tick materializer has made room.
     */
    private boolean transferTerminalCargo(ServerLevel serverLevel) {
        boolean complete = releaseCarriedArrows(serverLevel, settlement());
        complete = residentMeal.releaseCargo(this, serverLevel) && complete;
        syncResidentMeal();
        DeferredItemMaterializationSavedData deathDrops =
            DeferredItemMaterializationSavedData.get(serverLevel);
        for (int i = 0; i < bag.getContainerSize(); i++) {
            ItemStack live = bag.getItem(i);
            if (live.isEmpty()) {
                continue;
            }
            ItemStack expected = live.copy();
            UUID transfer = deathDrops.queue(serverLevel, getX(),
                getY() + 0.3D, getZ(), expected);
            if (transfer == null) {
                complete = false;
                continue;
            }
            ItemStack removed = bag.removeItemNoUpdate(i);
            if (removed.getCount() != expected.getCount()
                || !ItemStack.isSameItemSameComponents(removed, expected)) {
                // The staged row and bag slot may never both claim the stack.
                bag.setItem(i, expected);
                deathDrops.cancel(transfer);
                complete = false;
                com.hearthstead.Hearthstead.LOGGER.error(
                    "Death-drop transfer for settler {} bag slot {} failed source-clear invariant",
                    getUUID(), i);
                continue;
            }
            deathDrops.materialize(serverLevel, transfer);
        }
        // A worker's picked-up log/crop lives in the offhand (its ground item
        // was already discarded) and a self-crafted tool lives in escrow after
        // its inputs were consumed. Neither may vanish with the corpse.
        String carriedTag = com.hearthstead.entity.ai.GroundCollectionSession.PERSISTENT_OFFHAND_OWNERSHIP_TAG;
        ItemStack carried = getOffhandItem();
        if (!carried.isEmpty() && getPersistentData().getBoolean(carriedTag)) {
            UUID transfer = deathDrops.queue(serverLevel, getX(), getY() + 0.3D, getZ(), carried.copy());
            if (transfer == null) {
                complete = false;
            } else {
                setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                getPersistentData().remove(carriedTag);
                deathDrops.materialize(serverLevel, transfer);
            }
        }
        if (craftOutputEscrow != null && !craftOutputEscrow.output().isEmpty()) {
            UUID transfer = deathDrops.queue(serverLevel, getX(), getY() + 0.3D, getZ(),
                craftOutputEscrow.output().copy());
            if (transfer == null) {
                complete = false;
            } else {
                craftOutputEscrow = null;
                deathDrops.materialize(serverLevel, transfer);
            }
        }
        complete = dropBatchStow(serverLevel, deathDrops) && complete;
        return complete && archerQuiverCount() == 0 && bag.isEmpty()
            && batchStow.isEmpty() && !residentMeal.hasCargo();
    }

    /** Death: the stowed batch load leaves exactly like the bag (deferred drops). */
    private boolean dropBatchStow(ServerLevel serverLevel, DeferredItemMaterializationSavedData deathDrops) {
        boolean complete = true;
        for (int i = 0; i < batchStow.getContainerSize(); i++) {
            ItemStack live = batchStow.getItem(i);
            if (live.isEmpty()) {
                continue;
            }
            ItemStack expected = live.copy();
            UUID transfer = deathDrops.queue(serverLevel, getX(), getY() + 0.3D, getZ(), expected);
            if (transfer == null) {
                complete = false;
                continue;
            }
            ItemStack removed = batchStow.removeItemNoUpdate(i);
            if (removed.getCount() != expected.getCount()
                || !ItemStack.isSameItemSameComponents(removed, expected)) {
                batchStow.setItem(i, expected);
                deathDrops.cancel(transfer);
                complete = false;
                com.hearthstead.Hearthstead.LOGGER.error(
                    "Death-drop transfer for settler {} batch stow slot {} failed source-clear invariant",
                    getUUID(), i);
                continue;
            }
            deathDrops.materialize(serverLevel, transfer);
        }
        return complete;
    }

    private boolean recordTavernDeathNotice(ServerLevel level) {
        if (!getPersistentData().hasUUID(com.hearthstead.settlement.work.TavernHostService.ORDER_KEY)) return true;
        return com.hearthstead.settlement.work.TavernOrderDeathSavedData.get(level).record(
            level, getPersistentData().getUUID(com.hearthstead.settlement.work.TavernHostService.ORDER_KEY),
            getUUID(), getX(), getY(), getZ());
    }

    @Override
    protected void tickDeath() {
        if (level() instanceof ServerLevel serverLevel
            && (!recordTavernDeathNotice(serverLevel) || !transferTerminalCargo(serverLevel))) {
            // Do not increment vanilla's removal timer while this corpse still
            // owns cargo. Capacity/rejection can delay cleanup, never delete it.
            return;
        }
        super.tickDeath();
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
        if (drunkPoints > 0.0) {
            // tavern lane: bounded points + the game time they were last brought up to date
            tag.putDouble("DrunkPoints", Math.min(Drunkenness.MAX_POINTS, drunkPoints));
            tag.putLong("DrunkStamp", drunkUpdated == Long.MIN_VALUE ? level().getGameTime() : drunkUpdated);
        }
        tag.putInt("Appearance", getAppearanceSeed());
        if (getLookCostume() != 0) {
            tag.putInt("LookCostume", getLookCostume());
        }
        tag.putInt(COMBAT_EXPERIENCE_NBT_KEY, combatExperience());
        tag.put(COMBAT_LEDGER_NBT_KEY, guardCombatLedger.writeNbt());
        tag.putBoolean("Traveler", traveler);
        tag.put("Attributes", attributes().save());
        tag.put(SkillLevels.NBT_KEY, tradeSkills.save());
        effort().writeTo(tag);
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
        if (panicShelterUntil > Long.MIN_VALUE) {
            tag.putLong("PanicShelterUntil", panicShelterUntil);
        }
        if (claimedBed != null) {
            tag.put("ClaimedBed", NbtUtils.writeBlockPos(claimedBed));
        }
        tag.put("Bag", bag.createTag(registryAccess()));
        if (!batchStow.isEmpty()) {
            tag.put("BatchStow", batchStow.createTag(registryAccess()));
        }
        tag.put(ResidentMeal.NBT_KEY, residentMeal.save(registryAccess()));
        tag.putInt(ARCHER_QUIVER_NBT_KEY, archerQuiver);
        if (archerQuiver > 0 && archerQuiverSource != null) {
            tag.putUUID(ARCHER_QUIVER_SOURCE_NBT_KEY, archerQuiverSource);
        }
        tag.putInt("CarryCapacity", getCarryCapacity());
        BlockPos workContainerPos = placedWorkContainerPos();
        WorkContainerKind workContainerKind = placedWorkContainerKind();
        if (workContainerPos != null && workContainerKind != WorkContainerKind.NONE) {
            CompoundTag workContainer = new CompoundTag();
            workContainer.putByte("Kind", workContainerKind.id());
            workContainer.put("Pos", NbtUtils.writeBlockPos(workContainerPos));
            tag.put("PlacedWorkContainer", workContainer);
        }
        if (craftOutputEscrow != null) {
            tag.put("CraftOutputEscrow",
                craftOutputEscrow.save(registryAccess()));
        }
        if (bagTransferPresentation.active()) {
            tag.put("BagTransferPresentation",
                bagTransferPresentation.save(registryAccess()));
        }
        tag.putInt(TARGET_BLESSINGS_SCHEMA_KEY,
            TARGET_BLESSINGS_SCHEMA_VERSION);
        tag.put("TargetBlessings", targetBlessings.writeNbt());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        workerLifecycle.clear(); // Ledger authority reconstructs work after load.
        readDrunkenness(tag);
        // Presentation cues are runtime-only. Reusing an entity instance for a
        // load must never replay an already-consumed permanent binding.
        pendingBlessingCues = null;
        // MELEE contact authority is also runtime-only. A save made during
        // wind-up reloads cold instead of replaying an old blade on a target.
        meleeContacts.resetTransientState();
        pendingGuardMove = null;
        pendingGuardMoveTicket = MeleeContactLedger.NO_TICKET;
        combatStaggerUntil = Long.MIN_VALUE;
        shieldThudIn = -1;
        shieldBlockPresentationSequence = 0L;
        shieldBreakEventSequence = 0L;
        // Retired Mayor office (Guildmaster, 26 Sep): an old MAYOR byte loads
        // as an unassigned settler.
        Profession profession = com.hearthstead.settlement.guildmaster.MayorRetirement
            .retired(Profession.byId(tag.getByte("Profession")));
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
        entityData.set(DATA_LOOK_COSTUME, tag.getInt("LookCostume"));
        entityData.set(DATA_COMBAT_EXPERIENCE, GuardExperience.clamp(
            tag.contains(COMBAT_EXPERIENCE_NBT_KEY)
                ? tag.getInt(COMBAT_EXPERIENCE_NBT_KEY) : 0));
        guardCombatLedger.readOptionalNbt(tag.get(COMBAT_LEDGER_NBT_KEY));
        // Pre-skill saves have no key: every trade loads at level 1, 0 XP.
        tradeSkills.load(tag.contains(SkillLevels.NBT_KEY)
            ? tag.getCompound(SkillLevels.NBT_KEY) : null);
        entityData.set(DATA_TRADE_XP, tradeSkills.xp(profession));
        traveler = tag.getBoolean("Traveler");
        entityData.set(DATA_TRAVELER_APPEARANCE, traveler);
        // Traits first: rolling attributes consults SLOW_START.
        java.util.List<String> traitKeys = new java.util.ArrayList<>();
        net.minecraft.nbt.ListTag storedTraits = tag.getList("Traits", 8);
        for (int i = 0; i < storedTraits.size(); i++) {
            traitKeys.add(storedTraits.getString(i));
        }
        traits = traitKeys.isEmpty() ? Trait.roll(getRandom())
            : Trait.fromKeys(traitKeys);
        // Old five-attribute settlers need three new values exactly once.
        // Seed that migration from this settler's stable identity so two
        // otherwise identical legacy profiles do not become identical people.
        long attributeMigrationSeed = getUUID().getMostSignificantBits()
            ^ Long.rotateLeft(getUUID().getLeastSignificantBits(), 1);
        attributes = SettlerAttributes.load(tag.getCompound("Attributes"),
            getRandom(), attributeMigrationSeed);
        effort = Effort.readFrom(tag);
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
        targetSettlementId = tag.hasUUID("TargetSettlementId")
            ? tag.getUUID("TargetSettlementId") : null;
        hearthPos = NbtUtils.readBlockPos(tag, "HearthPos").orElse(null);
        claimedBed = NbtUtils.readBlockPos(tag, "ClaimedBed").orElse(null);
        panicShelterUntil = tag.contains("PanicShelterUntil", Tag.TAG_LONG)
            ? tag.getLong("PanicShelterUntil") : Long.MIN_VALUE;
        bag.fromTag(tag.getList("Bag", 10), registryAccess());
        batchStow.fromTag(tag.getList("BatchStow", 10), registryAccess());
        residentMeal.load(registryAccess(), tag.getCompound(ResidentMeal.NBT_KEY));
        syncResidentMeal();
        archerQuiver = tag.contains(ARCHER_QUIVER_NBT_KEY, Tag.TAG_INT)
            ? Mth.clamp(tag.getInt(ARCHER_QUIVER_NBT_KEY), 0,
                ARCHER_QUIVER_CAPACITY)
            : 0;
        archerQuiverSource = archerQuiver > 0
                && tag.hasUUID(ARCHER_QUIVER_SOURCE_NBT_KEY)
            ? tag.getUUID(ARCHER_QUIVER_SOURCE_NBT_KEY) : null;
        if (archerQuiverSource != null
            && archerQuiverSource.getMostSignificantBits() == 0L
            && archerQuiverSource.getLeastSignificantBits() == 0L) {
            archerQuiverSource = null;
        }
        setCarryCapacity(tag.contains("CarryCapacity")
            ? tag.getInt("CarryCapacity") : BASE_CARRY_CAPACITY);
        CompoundTag workContainer = tag.getCompound("PlacedWorkContainer");
        WorkContainerKind workContainerKind = WorkContainerKind.byId(
            workContainer.getByte("Kind"));
        BlockPos workContainerPos = NbtUtils.readBlockPos(workContainer, "Pos")
            .orElse(null);
        if (workContainerKind != WorkContainerKind.NONE && workContainerPos != null) {
            entityData.set(DATA_WORK_CONTAINER_KIND, workContainerKind.id());
            entityData.set(DATA_WORK_CONTAINER_POS,
                Optional.of(workContainerPos.immutable()));
        } else {
            entityData.set(DATA_WORK_CONTAINER_KIND, WorkContainerKind.NONE.id());
            entityData.set(DATA_WORK_CONTAINER_POS, Optional.empty());
        }
        craftOutputEscrow = CraftOutputEscrow.load(registryAccess(),
            tag.getCompound("CraftOutputEscrow"));
        if (craftOutputEscrow != null) {
            java.util.List<ItemStack> emptyGrid = new java.util.ArrayList<>(
                CraftPresentation.GRID_SIZE);
            for (int slot = 0; slot < CraftPresentation.GRID_SIZE; slot++) {
                emptyGrid.add(ItemStack.EMPTY);
            }
            craftPresentation = new CraftPresentation(
                craftOutputEscrow.actionId(), craftOutputEscrow.storageTarget(),
                net.minecraft.core.Direction.NORTH,
                CraftPresentation.Phase.CARRIED, 0, emptyGrid,
                craftOutputEscrow.output());
            entityData.set(DATA_CRAFT_PRESENTATION,
                craftPresentation.save(registryAccess()));
        } else {
            craftPresentation = CraftPresentation.empty();
            entityData.set(DATA_CRAFT_PRESENTATION, new CompoundTag());
        }
        BagTransferPresentation restoredBagPresentation = BagTransferPresentation.load(
            registryAccess(), tag.getCompound("BagTransferPresentation"));
        if (isStaleFarmerOutputPresentation(restoredBagPresentation)) {
            // This output projection owns no inventory. Clear only its exact
            // visual sack when the uncommitted crop is absent from the bag.
            if (placedWorkContainerKind() == WorkContainerKind.SACK
                && restoredBagPresentation.bagAnchor().equals(placedWorkContainerPos())) {
                clearWorkContainer();
            }
            bagTransferPresentation = BagTransferPresentation.empty();
        } else {
            bagTransferPresentation = restoredBagPresentation;
        }
        entityData.set(DATA_BAG_TRANSFER_PRESENTATION,
            bagTransferPresentation.active()
                ? bagTransferPresentation.save(registryAccess())
                : new CompoundTag());
        net.minecraft.nbt.Tag rawSchema = tag.get(TARGET_BLESSINGS_SCHEMA_KEY);
        net.minecraft.nbt.Tag rawBlessings = tag.get("TargetBlessings");
        boolean preFeature = rawSchema == null && rawBlessings == null;
        boolean currentSchema = tag.contains(TARGET_BLESSINGS_SCHEMA_KEY,
                net.minecraft.nbt.Tag.TAG_INT)
            && tag.getInt(TARGET_BLESSINGS_SCHEMA_KEY)
                == TARGET_BLESSINGS_SCHEMA_VERSION;
        if (preFeature) {
            // Only joint absence proves an entity from before per-target
            // Blessings. Its first physical seal may legitimately create rank I.
            targetBlessings = new TargetBlessingState();
        } else if ((rawSchema == null || currentSchema)
            && rawBlessings instanceof CompoundTag blessingTag) {
            // A valid strict nested ledger from the short pre-marker feature
            // window remains migratable; the next save writes this marker.
            targetBlessings = TargetBlessingState.readNbt(blessingTag);
        } else {
            // A present marker of the wrong type/version, or a current marker
            // whose owned ledger is missing/wrongly typed, is corruption. The
            // nested state writes Quarantined=true, so the decision is sticky.
            targetBlessings = TargetBlessingState.quarantinedEmpty();
        }
        syncCarryLoad();
    }

    private boolean isStaleFarmerOutputPresentation(BagTransferPresentation presentation) {
        if (!presentation.active() || presentation.sourcePickup() || presentation.committed()
            || (getProfession() != Profession.FARMER
                && getProfession() != Profession.FISHER)) return false;
        ItemStack visible = presentation.item();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            ItemStack live = bag.getItem(slot);
            if (ItemStack.isSameItemSameComponents(live, visible)
                && live.getCount() >= visible.getCount()) return false;
        }
        return true;
    }

    /** Idempotent re-registration protects against lost records; called from
     *  EntityJoinLevelEvent so it works on every loader. */
    public void reRegisterWithSettlement() {
        if (!isAlive() || isRemoved()) {
            return;
        }
        if (level() instanceof ServerLevel serverLevel && settlementId != null) {
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s != null) {
                s.putRecord(getUUID(), getSettlerName(), getProfession());
                syncBedClaimProjection();
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
        return com.hearthstead.registry.ModSounds.SETTLER_HURT.get();
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return com.hearthstead.registry.ModSounds.SETTLER_DEATH.get();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 600;
    }
}

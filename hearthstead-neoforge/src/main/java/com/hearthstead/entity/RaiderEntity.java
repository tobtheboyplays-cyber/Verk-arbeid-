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

    /** Health and damage a captain carries over an ordinary follower. */
    public static final float CAPTAIN_HEALTH_BONUS = 14.0F;
    public static final double CAPTAIN_DAMAGE_BONUS = 2.0;
    /** Ceiling on menace scaling, so a long feud cannot become unwinnable. */
    public static final float MAX_MENACE = 3.0F;

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
        // Above melee: a raid that came for the stores goes for the stores.
        // Fighting is what happens on the way, not the point.
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderLootGoal(this));
        // Mutually exclusive with the loot goal above (objective() can never
        // be KORN on an unassigned scout), so sharing priority 2 is safe.
        goalSelector.addGoal(2,
            new com.hearthstead.entity.ai.RaiderScoutGoal(this));
        goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // A scout never STARTS a fight -- it is an omen, not a free
        // skirmish (D-A3's telegraph step). It still defends itself: the
        // HurtByTargetGoal above and MeleeAttackGoal below are untouched.
        targetSelector.addGoal(2,
            new NearestAttackableTargetGoal<>(this, SettlerEntity.class, true,
                target -> !isScout()));
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
    }

    public boolean isCaptain() {
        return entityData.get(DATA_CAPTAIN);
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

    /** Raiders never turn on each other, however the melee goes. */
    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof RaiderEntity) && super.canAttack(target);
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
    }
}

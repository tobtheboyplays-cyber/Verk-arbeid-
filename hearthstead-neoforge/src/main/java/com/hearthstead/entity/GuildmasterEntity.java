package com.hearthstead.entity;

import com.hearthstead.settlement.guildmaster.GuildmasterService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The Guildmaster: a separate NPC who sits on his stool beside a settlement's
 * Banner and trades profession emblems (owner decision, 26 Sep 2026, which
 * also retired the Mayor).
 *
 * <p>He is deliberately NOT a {@link SettlerEntity}: he never appears in the
 * settlement roster, so he never counts toward population, founder or bed
 * slots, workforce, food or housing. He does not path, cannot be pushed and is
 * invulnerable to everything except the out-of-world / kill-command damage
 * types. Raiders only ever target players and settlers, so he is not a raid
 * target either. If an operator does kill him, {@link GuildmasterService}
 * seats a new one at the next check.
 *
 * <p>Identity: {@link GuildmasterService} links each settlement to exactly one
 * Guildmaster UUID. A loaded Guildmaster whose settlement is gone, or who is
 * not the linked UUID while the linked one is present, removes himself.
 */
public class GuildmasterEntity extends PathfinderMob {
    /** Entity event: a short greeting when a player opens the trade screen. */
    public static final byte EVENT_GREET = 91;
    /** Greeting length on the client, in ticks. */
    public static final int GREET_TICKS = 34;
    /** A greeting at most every 12 s per Guildmaster: calm, never a constant wave. */
    static final long GREET_COOLDOWN_TICKS = 240L;
    private static final int CHECK_INTERVAL = 20;

    @Nullable
    private UUID settlementId;
    @Nullable
    private BlockPos seat;
    private float seatYaw;
    private long lastGreetAt = Long.MIN_VALUE / 4;

    /** Client only: tickCount when the last greeting started. */
    public int greetStartTick = Integer.MIN_VALUE / 4;

    public GuildmasterEntity(EntityType<? extends GuildmasterEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setInvulnerable(true);
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.0D)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void registerGoals() {
        // Bounded look only: glance at a nearby player, otherwise look about
        // now and then. No movement goal exists, so he never leaves his seat.
        goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 6.0F, 0.03F));
        goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------ binding ---

    public void bind(UUID settlementId, BlockPos seat, float seatYaw) {
        this.settlementId = settlementId;
        seatAt(seat, seatYaw);
    }

    /** Places him on his stool at {@code seat}, facing {@code seatYaw}. */
    public void seatAt(BlockPos seat, float seatYaw) {
        this.seat = seat.immutable();
        this.seatYaw = seatYaw;
        moveTo(seat.getX() + 0.5D, seat.getY(), seat.getZ() + 0.5D, seatYaw, 0.0F);
        setYHeadRot(seatYaw);
        setYBodyRot(seatYaw);
        setDeltaMovement(0.0D, 0.0D, 0.0D);
    }

    @Nullable
    public UUID settlementId() {
        return settlementId;
    }

    @Nullable
    public BlockPos seat() {
        return seat;
    }

    public float seatYaw() {
        return seatYaw;
    }

    // ------------------------------------------------------------ ticking ---

    @Override
    public void aiStep() {
        super.aiStep();
        // Seated: the body always faces the ledger side of the Banner; only
        // the head follows a player (clamped by getMaxHeadYRot).
        yBodyRot = seatYaw;
        yBodyRotO = seatYaw;
        if (!level().isClientSide && level() instanceof ServerLevel server
            && (tickCount + getId()) % CHECK_INTERVAL == 0) {
            GuildmasterService.check(server, this);
        }
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                // Fixed seat: never swing the body round after the head.
            }
        };
    }

    @Override
    public int getMaxHeadYRot() {
        return 55;
    }

    // -------------------------------------------------------- interaction ---

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
            && !com.hearthstead.settlement.guildmaster.GuildmasterWelcome.onInteract(serverPlayer, this)) {
            // A player's first talk is his welcome (Coins and iron tools); after that, the emblems.
            GuildmasterService.openTrade(serverPlayer, this);
        }
        return InteractionResult.CONSUME;
    }

    /** Server: a short greeting for a player who opened trade, rate limited. */
    public void greet(Player player) {
        if (level().isClientSide) {
            return;
        }
        getLookControl().setLookAt(player, 30.0F, 30.0F);
        long now = level().getGameTime();
        if (now - lastGreetAt < GREET_COOLDOWN_TICKS) {
            return;
        }
        lastGreetAt = now;
        level().broadcastEntityEvent(this, EVENT_GREET);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_GREET) {
            greetStartTick = tickCount;
            return;
        }
        super.handleEntityEvent(id);
    }

    // --------------------------------------------------------- protection ---

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    // --------------------------------------------------------------- save ---

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (settlementId != null) {
            tag.putUUID("GuildmasterSettlement", settlementId);
        }
        if (seat != null) {
            tag.put("GuildmasterSeat", NbtUtils.writeBlockPos(seat));
        }
        tag.putFloat("GuildmasterSeatYaw", seatYaw);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        settlementId = tag.hasUUID("GuildmasterSettlement")
            ? tag.getUUID("GuildmasterSettlement") : null;
        seat = NbtUtils.readBlockPos(tag, "GuildmasterSeat").orElse(null);
        seatYaw = tag.getFloat("GuildmasterSeatYaw");
        setInvulnerable(true);
        setPersistenceRequired();
    }
}

package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.work.ContainerApproach;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Opt-in thief demonstration; synced identity selects the dedicated goblin presentation. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GoblinThiefDemo {
    private static final String TAG = "HearthsteadGoblinThiefDemo";
    private static final int PICKPOCKET_WINDUP_TICKS = 60;
    private static final int PICKPOCKET_COOLDOWN_TICKS = 600;
    /** Outside-settlement travel is deliberately separate from the final
     * close approach, so a natural thief does not burn its hands-in-pocket
     * budget simply walking in from the village edge. */
    private static final int PICKPOCKET_TRAVEL_TICKS = 600;
    private static final int PICKPOCKET_FINAL_APPROACH_TICKS = 100;
    private static final double PICKPOCKET_ACQUIRE_SQR = 8.0D * 8.0D;
    private static final double PICKPOCKET_CONTACT_SQR = 1.8D * 1.8D;
    private static final double PICKPOCKET_TRAVEL_ABANDON_SQR = 48.0D * 48.0D;
    /** Navigation multipliers, rather than raw attribute values. A skirmisher
     * flees upright just below a sprint; a tight reroute only brakes modestly. */
    static final double FLEE_CRUISE_SPEED = 1.10D;
    static final double FLEE_TIGHT_TURN_SPEED = .90D;
    private static final double FLEE_BRAKE_PER_REPATH = .06D;
    private static final double FLEE_RECOVERY_PER_REPATH = .025D;
    private static final String FLEE_SPEED_TAG = "FleeNavigationSpeed";
    private static final double CIVILIAN_WITNESS_RANGE_SQR = 4.0D * 4.0D;
    private static final double GUARD_WITNESS_RANGE_SQR = 12.0D * 12.0D;
    private static final double CIVILIAN_WITNESS_FRONT_DOT = .5D;
    private static final double SNEAK_TARGET_RANGE_SQR = 12.0D * 12.0D;
    /** Each roll is independent and evaluated only from RaiderEntity death loot. */
    static final float POOP_STICK_DEATH_DROP_CHANCE = 0.15F;
    static final float TROLL_TOENAIL_DEATH_DROP_CHANCE = 0.20F;
    // Vanilla hand swing peaks around its third tick; contact happens there
    // instead of after the six-tick animation has returned to neutral.
    private static final int DEFENSIVE_POKE_CONTACT_TICKS = 3;
    private static final int DEFENSIVE_POKE_COOLDOWN_TICKS = 60;
    private static final String DEFENSIVE_POKE_HIT_SEQUENCE = "DefensivePokeHitSequence";
    private static final String DEFENSIVE_POKE_HANDLED_HIT = "DefensivePokeHandledHit";
    private static final String DEFENSIVE_POKE_COOLDOWN = "DefensivePokeCooldown";
    private static final Map<RaiderEntity, Boolean> INSTALLED = new WeakHashMap<>();
    private GoblinThiefDemo() {}

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hearthsteadthiefdemo")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("container", BlockPosArgument.blockPos())
                .then(Commands.argument("spawn", BlockPosArgument.blockPos()).executes(context -> {
                    var source = context.getSource();
                    var thief = spawn(source.getLevel(),
                        BlockPosArgument.getLoadedBlockPos(context, "container"),
                        BlockPosArgument.getLoadedBlockPos(context, "spawn"));
                    if (thief == null) {
                        source.sendFailure(Component.literal("Use a loaded chest/barrel with Coins and a clear spawn 8-40 blocks away."));
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("Goblin thief demo started. Protect your Coins."), false);
                    return 1;
                }))));
    }

    public static RaiderEntity spawn(ServerLevel level, BlockPos target, BlockPos start) {
        return spawn(level,target,start,null);
    }
    public static RaiderEntity spawnNatural(ServerLevel level, BlockPos target, BlockPos start, java.util.UUID settlement) {
        if (settlement == null || !GoblinTheftDirector.targetStillOwned(level,settlement,target)) return null;
        return spawn(level,target,start,settlement);
    }

    /** Natural player-source visitor. The player UUID is the source authority;
     * the stored block position remains only an escape anchor and is never
     * interpreted as an inventory. */
    public static RaiderEntity spawnNaturalPlayer(ServerLevel level, ServerPlayer victim,
                                                   BlockPos start, java.util.UUID settlement) {
        if (settlement == null || !eligiblePickpocketVictim(victim)
                || victim.level() != level || !level.getServer().isSameThread()
                || !level.hasChunkAt(start) || start.distSqr(victim.blockPosition()) < 24 * 24
                || start.distSqr(victim.blockPosition()) > 36 * 36
                || !level.getWorldBorder().isWithinBounds(start)) return null;
        if (!level.getEntitiesOfClass(RaiderEntity.class,
                new net.minecraft.world.phys.AABB(victim.blockPosition()).inflate(64),
                actor -> actor.isAlive() && actor.getPersistentData().contains(TAG)).isEmpty()) return null;
        RaiderEntity thief = ModEntities.RAIDER.get().create(level);
        if (thief == null) return null;
        if (thief.getAttribute(Attributes.SCALE) != null) thief.getAttribute(Attributes.SCALE).setBaseValue(.65);
        thief.getAttribute(Attributes.MAX_HEALTH).setBaseValue(RaiderEntity.goblinThiefMaxHealth());
        thief.setHealth((float) RaiderEntity.goblinThiefMaxHealth());
        thief.setCustomName(Component.literal("Goblin Thief"));
        thief.setCustomNameVisible(true);
        thief.setCanPickUpLoot(false);
        thief.setPersistenceRequired();
        thief.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, 0, 0);
        thief.setOnGround(true);
        if (!level.noCollision(thief) || !level.getFluidState(start).isEmpty()
                || !level.getBlockState(start.below()).isFaceSturdy(level, start.below(),
                    net.minecraft.core.Direction.UP)) return null;
        if (level.players().stream().anyMatch(player -> player != victim && player.isAlive()
                && !player.isSpectator() && !player.getAbilities().instabuild
                && player.distanceToSqr(thief) < 16 * 16
                && thief.hasLineOfSight(player))) return null;
        var path = thief.getNavigation().createPath(victim.blockPosition(), 0);
        if (path == null || !path.canReach()) return null;
        CompoundTag state = new CompoundTag();
        state.putLong("Target", victim.blockPosition().asLong());
        state.putLong("Escape", start.asLong());
        state.putInt("Stage", 0);
        state.putLong("StageStartedAt", level.getGameTime());
        state.putUUID("NaturalSettlement", settlement);
        state.putUUID("PlayerTarget", victim.getUUID());
        state.putLong("PlayerTargetStartedAt", level.getGameTime());
        state.putLong("PlayerTravelUntil", level.getGameTime() + PICKPOCKET_TRAVEL_TICKS);
        state.putBoolean("PlayerOnly", true);
        thief.getPersistentData().put(TAG, state);
        install(thief);
        return level.addFreshEntity(thief) ? thief : null;
    }
    private static RaiderEntity spawn(ServerLevel level, BlockPos target, BlockPos start, java.util.UUID settlement) {
        if (!level.getServer().isSameThread() || !level.hasChunkAt(target) || !level.hasChunkAt(start)
            || target.distSqr(start) < 64 || target.distSqr(start) > 1600
            || !level.getWorldBorder().isWithinBounds(start) || coins(level, target) == null) return null;
        // Keep manual demonstrations isolated: at most one tagged thief near this container.
        if (!level.getEntitiesOfClass(RaiderEntity.class, new net.minecraft.world.phys.AABB(target).inflate(64),
                actor -> actor.isAlive() && actor.getPersistentData().contains(TAG)).isEmpty()) return null;
        RaiderEntity thief = ModEntities.RAIDER.get().create(level);
        if (thief == null) return null;
        if (thief.getAttribute(Attributes.SCALE) != null) thief.getAttribute(Attributes.SCALE).setBaseValue(.65);
        thief.getAttribute(Attributes.MAX_HEALTH).setBaseValue(RaiderEntity.goblinThiefMaxHealth());
        thief.setHealth((float) RaiderEntity.goblinThiefMaxHealth());
        thief.setCustomName(Component.literal("Goblin Thief"));
        thief.setCustomNameVisible(true);
        thief.setCanPickUpLoot(false);
        thief.setPersistenceRequired();
        thief.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, 0, 0);
        thief.setOnGround(true);
        if (!level.noCollision(thief) || !level.getFluidState(start).isEmpty()
            || !level.getBlockState(start.below()).isFaceSturdy(level, start.below(), net.minecraft.core.Direction.UP)) return null;
        CompoundTag state = new CompoundTag();
        state.putLong("Target", target.asLong());
        state.putLong("Escape", start.asLong());
        state.putInt("Stage", 0);
        state.putLong("StageStartedAt", level.getGameTime());
        if (settlement != null) {
            state.putUUID("NaturalSettlement",settlement);
            if (level.players().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(thief) < 16*16)) return null;
            var approach = ContainerApproach.moveMobToContact(level,thief,target,.8);
            var path = thief.getNavigation().getPath();
            if (!approach.startedPath() || path == null || !path.canReach()) return null;
            for(int i=0;i<path.getNodeCount();i++) {
                BlockPos node=path.getNodePos(i);
                if (!level.hasChunkAt(node) || !level.hasChunkAt(node.below())
                    || !level.getFluidState(node).isEmpty()) return null;
            }
        }        thief.getPersistentData().put(TAG, state);
        install(thief);
        return level.addFreshEntity(thief) ? thief : null;
    }

    private static Container coins(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var block = level.getBlockEntity(pos);
        if (!(block instanceof ChestBlockEntity || block instanceof BarrelBlockEntity)
            || !(block instanceof Container container)) return null;
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            if (container.getItem(slot).is(ModItems.GOLD_COIN.get())) return container;
        return null;
    }

    /** One server-thread move of real items, guarded by the persisted telegraph and contact. */
    public static int trySteal(ServerLevel level, RaiderEntity thief) {
        if (!level.getServer().isSameThread() || !thief.isAlive() || thief.level() != level
            || level.getEntity(thief.getUUID()) != thief || !thief.getPersistentData().contains(TAG)) return 0;
        CompoundTag state = thief.getPersistentData().getCompound(TAG);
        BlockPos target = BlockPos.of(state.getLong("Target"));
        if (state.getInt("Stage") != 1 || !state.contains("ReadyAt")
            || level.getGameTime() < state.getLong("ReadyAt") || thief.lootCount() != 0
            ) return 0;
        if (state.hasUUID("PlayerTarget")) {
            ServerPlayer victim = playerTarget(level, state);
            if (!eligiblePickpocketVictim(victim)
                || thief.distanceToSqr(victim) > PICKPOCKET_CONTACT_SQR
                || !thief.hasLineOfSight(victim)) return 0;
            int available = coinCount(victim);
            int limit = GoblinTheftDirector.theftLimit(available);
            ItemStack stolen = removeCoins(victim, limit);
            if (stolen.isEmpty()) return 0;
            thief.loot.setItem(0, stolen);
            state.putLong("NextPickpocketAt", level.getGameTime() + PICKPOCKET_COOLDOWN_TICKS);
            changeStage(thief, state, 2);
            level.playSound(null, thief.blockPosition(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                net.minecraft.sounds.SoundSource.HOSTILE, .35F, 1.6F);
            level.playSound(null, thief.blockPosition(), com.hearthstead.registry.ModSounds.GOBLIN_STEAL.get(),
                net.minecraft.sounds.SoundSource.HOSTILE, .8F, 1F);
            announce(level, thief, state, "The thief stole " + stolen.getCount() + " Coins! Stop him to recover them.");
            return stolen.getCount();
        }
        if (!ContainerApproach.hasPhysicalContact(level, thief, target)) return 0;
        if (state.hasUUID("NaturalSettlement")
            && !GoblinTheftDirector.targetStillOwned(level,state.getUUID("NaturalSettlement"),target)) {
            changeStage(thief,state,2); return 0;
        }
        Container source = coins(level, target);
        if (source == null) { changeStage(thief,state,2); return 0; }
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            if (!source.getItem(slot).is(ModItems.GOLD_COIN.get())) continue;
            ItemStack stolen = source.removeItem(slot, GoblinTheftDirector.theftLimit(GoblinTheftDirector.coinCount(level,target)));
            thief.loot.setItem(0, stolen);
            source.setChanged();
            changeStage(thief,state,2);
            level.playSound(null, thief.blockPosition(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                net.minecraft.sounds.SoundSource.HOSTILE, .35F, 1.6F);
            level.playSound(null, thief.blockPosition(), com.hearthstead.registry.ModSounds.GOBLIN_STEAL.get(),
                net.minecraft.sounds.SoundSource.HOSTILE, .8F, 1F);
            announce(level, thief, state, "The thief stole " + stolen.getCount() + " Coins! Stop him to recover them.");
            return stolen.getCount();
        }
        return 0;
    }

    /** A landed attack from a living attacker has one fair chance to dislodge a real Coin.
     * The deterministic release seam keeps the 50% roll out of custody tests. */
    @SubscribeEvent
    public static void onDamagingHit(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F || !(event.getSource().getEntity() instanceof LivingEntity)
                || !(event.getEntity() instanceof RaiderEntity thief)
                || !(thief.level() instanceof ServerLevel level) || !thief.isAlive()
                || !thief.getPersistentData().contains(TAG)) return;
        CompoundTag state = thief.getPersistentData().getCompound(TAG);
        if (event.getSource().getEntity() instanceof ServerPlayer) {
            state.putLong(DEFENSIVE_POKE_HIT_SEQUENCE,
                state.getLong(DEFENSIVE_POKE_HIT_SEQUENCE) + 1L);
        }
        // A landed attack breaks concealment in this damage event, rather than
        // leaving a crouched thief visible until the next 20-tick scan.
        if (state.getInt("Stage") != 2) {
            state.putBoolean("SpottedCue", true);
            state.remove("ReadyAt");
            changeStage(thief, state, 2);
            thief.getNavigation().stop();
            thief.setShiftKeyDown(false);
        }
        releaseOneCarriedCoinOnHit(level, thief, thief.getRandom().nextBoolean());
    }

    /**
     * Deterministic public seam for GameTest: releases exactly one physically carried
     * Coin only after a supplied successful hit roll. Custody is reduced before the
     * item joins the world; an insertion failure restores the same stack so neither
     * branch can duplicate or erase it.
     */
    public static int releaseOneCarriedCoinOnHit(ServerLevel level, RaiderEntity thief,
                                                 boolean successfulRoll) {
        if (!successfulRoll || !thief.isAlive() || thief.level() != level
                || !thief.getPersistentData().contains(TAG)) return 0;
        for (int slot = 0; slot < thief.loot.getContainerSize(); slot++) {
            ItemStack carried = thief.loot.getItem(slot);
            if (!carried.is(ModItems.GOLD_COIN.get())) continue;
            ItemStack original = carried.copy();
            ItemStack released = carried.split(1);
            thief.loot.setItem(slot, carried.isEmpty() ? ItemStack.EMPTY : carried);
            var drop = new net.minecraft.world.entity.item.ItemEntity(level, thief.getX(),
                thief.getY(), thief.getZ(), released);
            if (level.addFreshEntity(drop)) return 1;
            thief.loot.setItem(slot, original);
            return 0;
        }
        return 0;
    }

    @Nullable private static ServerPlayer playerTarget(ServerLevel level, CompoundTag state) {
        if (!state.hasUUID("PlayerTarget")) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(state.getUUID("PlayerTarget"));
        return player != null && player.level() == level ? player : null;
    }

    /** Read-only player-source eligibility shared with the natural director. */
    public static boolean eligiblePickpocketVictim(@Nullable ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator()
            && !player.getAbilities().instabuild && coinCount(player) > 0;
    }

    private static int coinCount(ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) total += stack.getCount();
        }
        return total;
    }

    private static ItemStack removeCoins(ServerPlayer player, int limit) {
        if (limit <= 0) return ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(ModItems.GOLD_COIN.get())) continue;
            ItemStack stolen = player.getInventory().removeItem(slot, limit);
            if (!stolen.isEmpty()) {
                player.getInventory().setChanged();
                return stolen;
            }
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent public static void tick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof RaiderEntity thief)
            || !(thief.level() instanceof ServerLevel level)
            || !thief.getPersistentData().contains(TAG)) return;
        install(thief);
        CompoundTag state = thief.getPersistentData().getCompound(TAG);
        thief.setGoblinThiefPresentation(true, state.getInt("Stage"), state.getLong("StageStartedAt"));
        // Saved one-shot voice receipts prevent chatter repeating after reload.
        if (thief.tickCount % 20 == 0 && state.getInt("Stage") == 0
                && !state.getBoolean("ApproachVoice")) {
            state.putBoolean("ApproachVoice", true);
            level.playSound(null, thief.blockPosition(), com.hearthstead.registry.ModSounds.GOBLIN_SNEAK.get(),
                net.minecraft.sounds.SoundSource.HOSTILE, .6F, 1F);
        }
        if (thief.tickCount % 20 == 0 && state.getInt("Stage") == 2
                && level.getGameTime() - state.getLong("StageStartedAt") >= 100
                && !state.getBoolean("FleeVoice")) {
            state.putBoolean("FleeVoice", true);
            level.playSound(null, thief.blockPosition(), com.hearthstead.registry.ModSounds.GOBLIN_FLEE.get(),
                net.minecraft.sounds.SoundSource.HOSTILE, .65F, 1F);
        }
        // One saved surprised squeak, not a repeated combat alarm or raid roar.
        // A selected survival player is the source of the visible approach and
        // windup. Treating that same player as an instant "spotted" threat
        // would cancel every fair pickpocket before the 60-tick tell ends.
        boolean activePickpocket = state.hasUUID("PlayerTarget")
            && state.getInt("Stage") != 2
            && eligiblePickpocketVictim(playerTarget(level, state));
        boolean ownSettlerWitness = witnessedByOwnSettler(level, thief, state);
        if (ownSettlerWitness) state.putBoolean("SeenByOwnSettler", true);
        if (!state.getBoolean("SpottedCue") && (ownSettlerWitness || thief.tickCount % 20 == 0)) {
            // A selected player source may complete its fair wind-up unseen, but
            // damage or a real resident witness always breaks concealment.
            // Own-settlement sight is evaluated every entity tick for an immediate stand/run.
            boolean noticed = thief.getLastHurtByMob() != null || ownSettlerWitness
                || (!activePickpocket && level.players().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                    && player.distanceToSqr(thief) <= 25 && thief.hasLineOfSight(player)));
            if (noticed) {
                state.putBoolean("SpottedCue", true);
                changeStage(thief,state,2);
                state.remove("ReadyAt");
                thief.getNavigation().stop();
                announce(level,thief,state,"A goblin thief has been spotted near your stores!");
                level.playSound(null, thief.blockPosition(), com.hearthstead.registry.ModSounds.GOBLIN_SPOTTED.get(),
                    net.minecraft.sounds.SoundSource.HOSTILE, .8F, 1F);
            }
        }
        updateSneakPose(thief, state);
    }

    private static void install(RaiderEntity thief) {
        CompoundTag state=thief.getPersistentData().getCompound(TAG);
        if (!state.contains("StageStartedAt")) state.putLong("StageStartedAt",
            state.getInt("Stage")==1 && state.contains("ReadyAt") ? Math.max(0,state.getLong("ReadyAt")-60) : thief.level().getGameTime());
        thief.setGoblinThiefPresentation(true,state.getInt("Stage"),state.getLong("StageStartedAt"));
        // This visible weapon is used only by the bounded defensive-poke goal
        // below. The thief has no target selector and never pursues a player.
        if (!thief.getMainHandItem().is(ModItems.POOP_STICK.get())) {
            thief.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(ModItems.POOP_STICK.get()));
        }
        // The visibly carried stick is never a second, vanilla equipment
        // drop. A separate rare death roll below is the only loot authority.
        thief.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        if (state.getInt("Stage") == 2) thief.setShiftKeyDown(false);
        if (INSTALLED.putIfAbsent(thief, Boolean.TRUE) != null) return;
        if (state.getInt("Stage") == 3) { state.remove("PickDoor"); changeStage(thief,state,0); }
        if (state.getInt("Stage") == 1) {
            // Unloaded time is not continuous physical contact with the chest.
            state.putLong("ReadyAt",thief.level().getGameTime()+60);
            state.putLong("StageStartedAt",thief.level().getGameTime());
            thief.setGoblinThiefPresentation(true,1,state.getLong("StageStartedAt"));
        }
        // Only tagged demo actors lose attack goals; ordinary raids remain untouched.
        for (var goal : List.copyOf(thief.goalSelector.getAvailableGoals())) thief.goalSelector.removeGoal(goal.getGoal());
        for (var goal : List.copyOf(thief.targetSelector.getAvailableGoals())) thief.targetSelector.removeGoal(goal.getGoal());
        thief.setTarget(null);
        thief.goalSelector.addGoal(0, new FloatGoal(thief));

        thief.goalSelector.addGoal(1, new DefensivePokeGoal(thief));
        thief.goalSelector.addGoal(2, new TheftGoal(thief));
    }

    /**
     * Called solely by {@link RaiderEntity#dropCustomDeathLoot}; successful
     * escape uses discard rather than that death hook and therefore cannot
     * produce either trophy.
     */
    public static void dropRareDeathLoot(RaiderEntity thief) {
        if (!thief.isGoblinThiefDemo() || thief.level().isClientSide) return;
        if (rareDropRoll(thief.getRandom().nextFloat(), POOP_STICK_DEATH_DROP_CHANCE)) {
            thief.spawnAtLocation(new ItemStack(ModItems.POOP_STICK.get()));
        }
        if (rareDropRoll(thief.getRandom().nextFloat(), TROLL_TOENAIL_DEATH_DROP_CHANCE)) {
            thief.spawnAtLocation(new ItemStack(ModItems.TROLL_TOENAIL.get()));
        }
    }

    /** Strict boundary makes a chance of exactly 15% / 20%, never a guarantee. */
    static boolean rareDropRoll(float roll, float chance) {
        return Float.isFinite(roll) && Float.isFinite(chance)
            && chance > 0.0F && chance <= 1.0F && roll >= 0.0F && roll < chance;
    }

    /**
     * One stationary retaliation after a player has actually hurt this goblin.
     * It does not install targeting, navigate toward the player, or compete
     * with escape after the one visible windup/contact attempt.
     */
    private static final class DefensivePokeGoal extends Goal {
        private final RaiderEntity thief;
        private ServerPlayer attacker;
        private long contactAt = Long.MIN_VALUE;

        DefensivePokeGoal(RaiderEntity thief) {
            this.thief = thief;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override public boolean canUse() {
            if (!(thief.level() instanceof ServerLevel level) || !thief.isAlive()
                    || thief.goblinThiefStage() != 2) return false;
            CompoundTag state = thief.getPersistentData().getCompound(TAG);
            long hitSequence = state.getLong(DEFENSIVE_POKE_HIT_SEQUENCE);
            if (hitSequence <= state.getLong(DEFENSIVE_POKE_HANDLED_HIT)
                    || level.getGameTime() < state.getLong(DEFENSIVE_POKE_COOLDOWN)
                    || !(thief.getLastHurtByMob() instanceof ServerPlayer player)) return false;
            if (!legalContact(player)) return false;
            attacker = player;
            return true;
        }

        @Override public boolean canContinueToUse() {
            return attacker != null && attacker.isAlive() && attacker.level() == thief.level()
                    && contactAt != Long.MIN_VALUE;
        }

        @Override public void start() {
            ServerLevel level = (ServerLevel) thief.level();
            contactAt = level.getGameTime() + DEFENSIVE_POKE_CONTACT_TICKS;
            CompoundTag state = thief.getPersistentData().getCompound(TAG);
            // Consume this actual player hit before its visible windup.
            // Losing line of sight cannot turn the same hit into a series of
            // chase swings after the thief has resumed escape.
            state.putLong(DEFENSIVE_POKE_HANDLED_HIT,
                state.getLong(DEFENSIVE_POKE_HIT_SEQUENCE));
            state.putLong(DEFENSIVE_POKE_COOLDOWN,
                level.getGameTime() + DEFENSIVE_POKE_COOLDOWN_TICKS);
            thief.getNavigation().stop();
            thief.getLookControl().setLookAt(attacker);
            // Entity hand swing is synchronized and is the windup telegraph.
            thief.swing(InteractionHand.MAIN_HAND);
        }

        @Override public void tick() {
            ServerLevel level = (ServerLevel) thief.level();
            thief.getNavigation().stop();
            thief.getLookControl().setLookAt(attacker);
            if (level.getGameTime() < contactAt) return;
            if (legalContact(attacker)) {
                // RaiderEntity gates the poison after target.hurt succeeds.
                thief.doHurtTarget(attacker);
            }
            contactAt = Long.MIN_VALUE;
        }

        @Override public void stop() {
            attacker = null;
            contactAt = Long.MIN_VALUE;
        }

        private boolean legalContact(ServerPlayer player) {
            return player != null && player.isAlive() && !player.isSpectator()
                && player.level() == thief.level() && thief.isWithinMeleeAttackRange(player)
                && thief.hasLineOfSight(player);
        }
    }

    private static void changeStage(RaiderEntity thief, CompoundTag state, int stage) {
        if (state.getInt("Stage") != stage) {
            state.putInt("Stage",stage);
            state.putLong("StageStartedAt",thief.level().getGameTime());
        }
        if (stage != 2) state.remove(FLEE_SPEED_TAG);
        if (stage == 2) thief.setShiftKeyDown(false);
        thief.setGoblinThiefPresentation(true,stage,state.getLong("StageStartedAt"));
    }

    /**
     * Flee locomotion has momentum: a sharp reroute brakes before it can build
     * back to the modest cruise modifier. This is deliberately pure so turn and
     * recovery bounds stay covered without simulating pathfinding in a unit test.
     */
    static double fleeNavigationSpeedForHeading(double previousSpeed, double headingAlignment) {
        double alignment = Math.max(-1D, Math.min(1D, headingAlignment));
        double turn = (1D - alignment) * .5D;
        double target = FLEE_CRUISE_SPEED
            + (FLEE_TIGHT_TURN_SPEED - FLEE_CRUISE_SPEED) * turn;
        double previous = Math.max(FLEE_TIGHT_TURN_SPEED,
            Math.min(FLEE_CRUISE_SPEED, previousSpeed));
        return target < previous
            ? Math.max(target, previous - FLEE_BRAKE_PER_REPATH)
            : Math.min(target, previous + FLEE_RECOVERY_PER_REPATH);
    }

    /** Concealment is local to the final approach or lockpick, never a whole route. */
    private static void updateSneakPose(RaiderEntity thief, CompoundTag state) {
        int stage = state.getInt("Stage");
        BlockPos target = BlockPos.of(state.getLong("Target"));
        boolean concealed = !state.getBoolean("SpottedCue") && (stage == 0 || stage == 1 || stage == 3)
            && thief.distanceToSqr(target.getX() + .5D, target.getY(), target.getZ() + .5D)
                <= SNEAK_TARGET_RANGE_SQR;
        thief.setShiftKeyDown(concealed);
    }

    /** Small pure policy seam: civilians must face a nearby thief; martial
     * settlers (Guard/Archer) have the agreed 12-block watch advantage. */
    static boolean noticeDistanceAndFacing(boolean martial, double distanceSqr, double frontDot) {
        return martial ? distanceSqr <= GUARD_WITNESS_RANGE_SQR
            : distanceSqr <= CIVILIAN_WITNESS_RANGE_SQR && frontDot >= CIVILIAN_WITNESS_FRONT_DOT;
    }

    /** The only Goblin notice policy. It is shared by theft text and civilian
     * panic so a different colony or a far worker cannot leak an alert. */
    public static boolean canNotice(SettlerEntity observer, RaiderEntity thief) {
        CompoundTag state = thief.getPersistentData().getCompound(TAG);
        if (!thief.isGoblinThiefDemo() || !state.hasUUID("NaturalSettlement")
                || !observer.isAlive() || !observer.isBound()
                || !state.getUUID("NaturalSettlement").equals(observer.getSettlementId())
                || !observer.hasLineOfSight(thief)) return false;
        var toward = thief.position().subtract(observer.position());
        double horizontal = Math.sqrt(toward.x * toward.x + toward.z * toward.z);
        double frontDot = horizontal < .001D ? 1D
            : (observer.getLookAngle().x * toward.x + observer.getLookAngle().z * toward.z) / horizontal;
        return noticeDistanceAndFacing(observer.getProfession().martial(),
            observer.distanceToSqr(thief), frontDot);
    }

    private static boolean witnessedByOwnSettler(ServerLevel level, RaiderEntity thief, CompoundTag state) {
        if (!state.hasUUID("NaturalSettlement")) return false;
        return !level.getEntitiesOfClass(SettlerEntity.class, thief.getBoundingBox().inflate(12.0D),
            settler -> canNotice(settler, thief)).isEmpty();
    }

    private static void announce(ServerLevel level, RaiderEntity thief, CompoundTag state, String text) {
        if (!witnessedByOwnSettler(level, thief, state)) return;
        // Kept on the entity through reload so a later escape can distinguish a
        // genuinely hidden store theft from one the settlement already witnessed.
        state.putBoolean("SeenByOwnSettler", true);
        for (var player : level.players()) if (player.distanceToSqr(thief) <= 64 * 64)
            player.displayClientMessage(Component.literal(text), false);
    }

    private static final class TheftGoal extends Goal {
        private final RaiderEntity thief;
        private long nextPath;
        private net.minecraft.world.phys.Vec3 lastProgress;
        private long progressAt;
        private java.util.UUID lastThreatId;
        private net.minecraft.world.phys.Vec3 lastThreatPosition;
        TheftGoal(RaiderEntity thief) { this.thief=thief; setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() { return thief.isAlive(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            ServerLevel level=(ServerLevel)thief.level();
            CompoundTag state=thief.getPersistentData().getCompound(TAG);
            long now=level.getGameTime();
            BlockPos target=BlockPos.of(state.getLong("Target"));
            if(thief.lootCount()>0 && state.getInt("Stage")!=3) changeStage(thief,state,2);
            if(state.getInt("Stage")==3) {
                if(pickDoor(level,state,now)) return;
            }
            if (state.hasUUID("PlayerTarget") && state.getInt("Stage") != 2
                    && handlePickpocket(level, state, now)) return;
            if (state.getBoolean("PlayerOnly") && state.getInt("Stage") != 2) {
                // A natural player-source visitor never falls through to an
                // arbitrary block after its UUID source disappears.
                changeStage(thief, state, 2);
            }
            if (thief.lootCount() == 0 && state.getInt("Stage") == 0
                    && now >= state.getLong("NextPickpocketAt")) {
                ServerPlayer candidate = nearestPickpocketVictim(level);
                if (candidate != null) {
                    state.putUUID("PlayerTarget", candidate.getUUID());
                    state.putLong("PlayerTargetStartedAt", now);
                    state.putLong("PlayerTravelUntil", now + PICKPOCKET_TRAVEL_TICKS);
                    nextPath = 0;
                    if (handlePickpocket(level, state, now)) return;
                }
            }
            if(nearbyDoor(level,state,now)) return;
            if(lastProgress==null || thief.position().distanceToSqr(lastProgress)>.25) {
                lastProgress=thief.position(); progressAt=now;
            }
            boolean stuck=now-progressAt>=60;
            if(stuck) { nextPath=0; progressAt=now; state.remove("Prowl"); }
            if(thief.lootCount()==0) {
                int empty=state.getInt("EmptyTicks")+1; state.putInt("EmptyTicks",empty);
                if(empty>=1200) changeStage(thief,state,2);
            }
            if(state.getInt("Stage")==2) {
                // A pursuer crossing the route invalidates the old destination
                // immediately; waiting twenty ticks can run straight past them.
                var danger=nearestThreat(level);
                if(danger!=null && (!danger.getUUID().equals(lastThreatId)
                    || lastThreatPosition==null || danger.position().distanceToSqr(lastThreatPosition)>1)) {
                    nextPath=0;
                    lastThreatId=danger.getUUID();lastThreatPosition=danger.position();
                } else if(danger==null) { lastThreatId=null;lastThreatPosition=null; }
                if(now>=nextPath) { nextPath=now+20; escape(level,state,target); }
                // Coins remain in the thief's real custody until he reaches an
                // unwatched boundary, where the stolen value leaves the world.
                boolean watched=level.players().stream().anyMatch(p->p.isAlive()&&!p.isSpectator()
                    && (p.distanceToSqr(thief)<24*24 || (p.distanceToSqr(thief)<48*48 && p.hasLineOfSight(thief))));
                watched |= !level.getEntitiesOfClass(com.hearthstead.entity.SettlerEntity.class,
                    thief.getBoundingBox().inflate(48),g->g.isAlive()&&g.getProfession().martial()
                        && (g.distanceToSqr(thief)<24*24 || (g.distanceToSqr(thief)<48*48 && g.hasLineOfSight(thief)))).isEmpty();
                if(now-state.getLong("StageStartedAt")>=160 && !watched
                    && thief.blockPosition().distSqr(target)>24*24) {
                    // An unwatched thief gets away with the stolen value. Remove
                    // the real carried stacks before discard; never leave a free
                    // boundary drop that players can recover after an escape.
                    for(int slot=0;slot<thief.loot.getContainerSize();slot++) {
                        thief.loot.removeItemNoUpdate(slot);
                    }
                    thief.discard();
                }
                return;
            }
            if(ContainerApproach.hasPhysicalContact(level,thief,target)) {
                thief.getNavigation().stop();
                thief.getLookControl().setLookAt(target.getX()+.5,target.getY()+.5,target.getZ()+.5);
                if(state.getInt("Stage")!=1) {
                    changeStage(thief,state,1); state.putLong("ReadyAt",now+60);
                    announce(level,thief,state,"A thief is opening your coin chest! Guards!");
                    level.playSound(null,target,net.minecraft.sounds.SoundEvents.CHEST_OPEN,
                        net.minecraft.sounds.SoundSource.HOSTILE,.8F,1.2F);
                }
                trySteal(level,thief); return;
            }
            changeStage(thief,state,0); state.remove("ReadyAt");
            if(now>=nextPath) {
                nextPath=now+20;
                // One short, physical scouting leg on natural arrivals before approach.
                if(state.hasUUID("NaturalSettlement") && !state.getBoolean("Prowled")) {
                    if(state.contains("Prowl")) {
                        BlockPos prowl=BlockPos.of(state.getLong("Prowl"));
                        if(thief.blockPosition().distSqr(prowl)<4 || stuck) state.putBoolean("Prowled",true);
                        else { thief.getNavigation().moveTo(prowl.getX()+.5,prowl.getY(),prowl.getZ()+.5,.85); return; }
                    } else {
                        var toward=net.minecraft.world.phys.Vec3.atBottomCenterOf(target).subtract(thief.position()).normalize();
                        BlockPos prowl=reachable(level,thief.getX()+toward.x*5-toward.z*3,
                            thief.getZ()+toward.z*5+toward.x*3,thief.blockPosition().getY());
                        state.putBoolean("Prowled",prowl==null);
                        if(prowl!=null) {state.putLong("Prowl",prowl.asLong()); thief.getNavigation().moveTo(prowl.getX()+.5,prowl.getY(),prowl.getZ()+.5,.85);return;}
                    }
                }
                var result=ContainerApproach.moveMobToContact(level,thief,target,.9);
                if(!result.startedPath() && !result.canInteract()) {
                    int failures=state.getInt("PathFailures")+1;state.putInt("PathFailures",failures);
                    if(failures>=5) changeStage(thief,state,2);
                } else state.putInt("PathFailures",0);
            }
        }

        /** A thief may approach only a nearby, carrying survival player. The
         * actual item transfer is still gated by the persisted stage-one
         * windup and contact check in {@link #trySteal}. */
        private boolean handlePickpocket(ServerLevel level, CompoundTag state, long now) {
            ServerPlayer victim = playerTarget(level, state);
            if (!eligiblePickpocketVictim(victim)
                    || now > state.getLong("PlayerTravelUntil")
                    || thief.distanceToSqr(victim) > PICKPOCKET_TRAVEL_ABANDON_SQR) {
                abandonPickpocket(state, now);
                return false;
            }
            if (thief.distanceToSqr(victim) > PICKPOCKET_ACQUIRE_SQR) {
                if (now >= nextPath) {
                    nextPath = now + 10;
                    thief.getNavigation().moveTo(victim, 1.05D);
                }
                return true;
            }
            if (!state.contains("PlayerCloseStartedAt")) {
                state.putLong("PlayerCloseStartedAt", now);
            }
            if (now - state.getLong("PlayerCloseStartedAt") > PICKPOCKET_FINAL_APPROACH_TICKS) {
                abandonPickpocket(state, now);
                return false;
            }
            if (state.getInt("Stage") == 1) {
                if (thief.distanceToSqr(victim) > PICKPOCKET_CONTACT_SQR
                        || !thief.hasLineOfSight(victim)) {
                    abandonPickpocket(state, now);
                    return false;
                }
                thief.getNavigation().stop();
                thief.getLookControl().setLookAt(victim);
                trySteal(level, thief);
                return true;
            }
            if (thief.distanceToSqr(victim) <= PICKPOCKET_CONTACT_SQR) {
                thief.getNavigation().stop();
                thief.getLookControl().setLookAt(victim);
                changeStage(thief, state, 1);
                state.putLong("ReadyAt", now + PICKPOCKET_WINDUP_TICKS);
                announce(level, thief, state, "A goblin thief is reaching for your Coins!");
                return true;
            }
            if (now >= nextPath) {
                nextPath = now + 10;
                thief.getNavigation().moveTo(victim, 1.05D);
            }
            return true;
        }

        private void abandonPickpocket(CompoundTag state, long now) {
            state.remove("PlayerTarget");
            state.remove("PlayerTargetStartedAt");
            state.remove("PlayerTravelUntil");
            state.remove("PlayerCloseStartedAt");
            state.remove("ReadyAt");
            state.putLong("NextPickpocketAt", now + PICKPOCKET_COOLDOWN_TICKS);
            if (state.getInt("Stage") == 1) changeStage(thief, state, 0);
            nextPath = 0;
        }

        @Nullable private ServerPlayer nearestPickpocketVictim(ServerLevel level) {
            ServerPlayer best = null;
            double closest = PICKPOCKET_ACQUIRE_SQR;
            for (ServerPlayer player : level.players()) {
                if (!eligiblePickpocketVictim(player)) continue;
                double distance = thief.distanceToSqr(player);
                if (distance <= closest && thief.hasLineOfSight(player)) {
                    best = player;
                    closest = distance;
                }
            }
            return best;
        }
        private boolean nearbyDoor(ServerLevel level,CompoundTag state,long now) {
            var path=thief.getNavigation().getPath();
            if(path==null) return false;
            for(int i=Math.max(0,path.getNextNodeIndex()-1);i<Math.min(path.getNodeCount(),path.getNextNodeIndex()+3);i++) {
                BlockPos pos=path.getNodePos(i);
                var block=level.getBlockState(pos);
                if(!(block.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                    || !block.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)) continue;
                if(block.getValue(net.minecraft.world.level.block.DoorBlock.HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) pos=pos.below();
                block=level.getBlockState(pos);
                if(block.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)
                    || Math.abs(thief.getY()-pos.getY())>1.25
                    || thief.distanceToSqr(pos.getX()+.5,thief.getY(),pos.getZ()+.5)>2.25) continue;
                state.putInt("AfterDoor",state.getInt("Stage"));state.putLong("PickDoor",pos.asLong());
                changeStage(thief,state,3); thief.getNavigation().stop();
                return pickDoor(level,state,now);
            }
            return false;
        }
        private boolean pickDoor(ServerLevel level,CompoundTag state,long now) {
            BlockPos pos=BlockPos.of(state.getLong("PickDoor")); var block=level.getBlockState(pos);
            if(!(block.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door)
                || !block.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)
                || Math.abs(thief.getY()-pos.getY())>1.25
                || thief.distanceToSqr(pos.getX()+.5,thief.getY(),pos.getZ()+.5)>3.0) {
                state.remove("PickDoor");changeStage(thief,state,thief.lootCount()>0?2:0);nextPath=0;return false;
            }
            thief.getNavigation().stop();thief.getLookControl().setLookAt(pos.getX()+.5,pos.getY()+.8,pos.getZ()+.5);
            long elapsed=now-state.getLong("StageStartedAt");
            if(!block.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && elapsed<40) {
                if(elapsed%10==0)level.playSound(null,pos,net.minecraft.sounds.SoundEvents.TRIPWIRE_CLICK_ON,
                    net.minecraft.sounds.SoundSource.HOSTILE,.25F,1.3F);
                return true;
            }
            if(!block.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) door.setOpen(thief,level,block,pos,true);
            state.remove("PickDoor");changeStage(thief,state,state.getInt("AfterDoor")==2?2:0);nextPath=0;return false;
        }
        private BlockPos reachable(ServerLevel level,double x,double z,int y) {
            BlockPos column=BlockPos.containing(x,y,z);
            if(!level.hasChunkAt(column))return null;
            for(int dy=2;dy>=-3;dy--) {
                BlockPos p=column.offset(0,dy,0);
                if(!level.getFluidState(p).isEmpty()||level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty())continue;
                if(!level.noCollision(thief,thief.getBoundingBox().move(p.getX()+.5-thief.getX(),p.getY()-thief.getY(),p.getZ()+.5-thief.getZ())))continue;
                var path=thief.getNavigation().createPath(p,0);
                if(path!=null && path.canReach())return p;
            }
            return null;
        }
        private net.minecraft.world.entity.LivingEntity nearestThreat(ServerLevel level) {
            net.minecraft.world.entity.LivingEntity danger=null;double closest=32*32;
            for(var player:level.players())if(player.isAlive()&&!player.isSpectator()&&player.distanceToSqr(thief)<closest){danger=player;closest=player.distanceToSqr(thief);}
            for(var guard:level.getEntitiesOfClass(com.hearthstead.entity.SettlerEntity.class,thief.getBoundingBox().inflate(24),g->g.isAlive()&&g.getProfession().martial()))
                if(guard.distanceToSqr(thief)<closest){danger=guard;closest=guard.distanceToSqr(thief);}
            return danger;
        }
        private void escape(ServerLevel level,CompoundTag state,BlockPos target) {
            var danger=nearestThreat(level);
            var away=thief.position().subtract(danger==null?net.minecraft.world.phys.Vec3.atBottomCenterOf(target):danger.position());
            if(away.horizontalDistanceSqr()<.01)away=new net.minecraft.world.phys.Vec3(1,0,0);
            double heading=Math.atan2(away.z,away.x);
            BlockPos best=null;double bestScore=-Double.MAX_VALUE;
            for(int distance:new int[]{10,6,3})for(double turn:new double[]{0,.7,-.7,1.4,-1.4,2.2,-2.2,Math.PI}) {
                double angle=heading+turn;
                BlockPos point=reachable(level,thief.getX()+Math.cos(angle)*distance,thief.getZ()+Math.sin(angle)*distance,thief.blockPosition().getY());
                if(point==null)continue;
                double score=danger==null?point.distSqr(target):danger.distanceToSqr(point.getX()+.5,point.getY(),point.getZ()+.5);
                score-=Math.abs(turn)*2;
                if(score>bestScore){best=point;bestScore=score;}
            }
            if(best!=null) {
                double prior = state.contains(FLEE_SPEED_TAG)
                    ? state.getDouble(FLEE_SPEED_TAG) : FLEE_CRUISE_SPEED;
                double speed = fleeNavigationSpeedForHeading(prior, escapeHeadingAlignment(best));
                state.putDouble(FLEE_SPEED_TAG, speed);
                state.putLong("Escape",best.asLong());
                thief.getNavigation().moveTo(best.getX()+.5,best.getY(),best.getZ()+.5,speed);
            }
        }

        /** Compare the physical or current-path heading with the fresh escape leg.
         * A stalled mob has no turn to brake for; a reroute while moving does. */
        private double escapeHeadingAlignment(BlockPos destination) {
            var current = thief.getDeltaMovement();
            if (current.horizontalDistanceSqr() < .0004D) {
                var path = thief.getNavigation().getPath();
                if (path != null && path.getNextNodeIndex() < path.getNodeCount()) {
                    current = net.minecraft.world.phys.Vec3.atBottomCenterOf(
                        path.getNodePos(path.getNextNodeIndex())).subtract(thief.position());
                }
            }
            var desired = net.minecraft.world.phys.Vec3.atBottomCenterOf(destination)
                .subtract(thief.position());
            double currentLength = Math.sqrt(current.x * current.x + current.z * current.z);
            double desiredLength = Math.sqrt(desired.x * desired.x + desired.z * desired.z);
            if (currentLength < .001D || desiredLength < .001D) return 1D;
            return Math.max(-1D, Math.min(1D,
                (current.x * desired.x + current.z * desired.z) / (currentLength * desiredLength)));
        }
    }
}

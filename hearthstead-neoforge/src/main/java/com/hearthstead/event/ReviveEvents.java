package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.revive.ReviveService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Game-bus wiring for {@link ReviveService}. Guards that stop a downed player
 * from acting run on both sides (the client mirror is
 * {@link ReviveService#CLIENT_DOWNED_IDS}) so nothing even swings.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ReviveEvents {
    private ReviveEvents() {
    }

    // ---------------------------------------------------------------- death

    /** HIGH so a down cancels the death before ordinary death listeners see it. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || ReviveService.isDowned(player)) {
            return; // a downed player's death is only confirmed at LOWEST, below
        }
        if (ReviveService.tryDown(player, event.getSource())) {
            event.setCanceled(true);
        }
    }

    /**
     * A downed player finished off / bled out. Runs LAST and only if nobody
     * canceled the death (receiveCanceled=false), so a totem-like mod that
     * saves them leaves the downed, drag and crash-marker state intact.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeathConfirmed(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ReviveService.isDowned(player)) {
            ReviveService.onDownedDeath(player);
        }
    }

    // ---------------------------------------------------------------- tick / lifecycle

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ReviveService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ReviveService.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ReviveService.onLogin(player);
            ReviveService.markDirty(player.getServer());
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ReviveService.markDirty(player.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ReviveService.onServerStopped(event.getServer());
    }

    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof Player player && ReviveService.isDowned(player)) {
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- revive / drag input

    /**
     * Use key on a downed player: revive (held) or drag (sneak). Runs on both
     * sides; the client consumes the click so the reviver's held item is
     * never used, and the server does the work.
     */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        Player actor = event.getEntity();
        if (ReviveService.isDowned(actor)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        if (!(event.getTarget() instanceof Player target) || !ReviveService.isDowned(target)) {
            return;
        }
        // CONSUME: the click is used up (no item use) without an arm swing
        // every 4 ticks while the key is held.
        event.setCancellationResult(InteractionResult.CONSUME);
        event.setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND
            && actor instanceof ServerPlayer serverActor && target instanceof ServerPlayer serverTarget) {
            ReviveService.onUse(serverActor, serverTarget, serverActor.isShiftKeyDown());
        }
    }

    @SubscribeEvent
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        // The plain EntityInteract sent right after carries the revive; only
        // a downed actor is stopped here.
        if (ReviveService.isDowned(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- downed can't act

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (ReviveService.isDowned(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (ReviveService.isDowned(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (ReviveService.isDowned(event.getEntity())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (ReviveService.isDowned(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (ReviveService.isDowned(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && ReviveService.isDowned(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseItem(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player player && ReviveService.isDowned(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ReviveService.isDowned(player)) {
            event.setCanceled(true); // only a comrade gets you up
        }
    }

    // ---------------------------------------------------------------- damage

    /** No friendly fire on a downed player (a stray swing must not finish a friend). */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ReviveService.isDowned(player)
            && event.getSource().getEntity() instanceof Player attacker && attacker != player
            && !event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    /** Damage actually taken interrupts a revive (both reviver and downed). */
    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ReviveService.onDamaged(player, event.getNewDamage());
        }
    }

    /**
     * Enemies leave a downed player alone - except the one raider picked to
     * finish them. Others are redirected to the nearest standing player, or
     * simply keep their current target.
     */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity mob = event.getEntity();
        if (mob.level().isClientSide
            || !(event.getNewAboutToBeSetTarget() instanceof ServerPlayer downed)
            || !ReviveService.isDowned(downed)
            || ReviveService.isFinisher(downed, mob)) {
            return;
        }
        LivingEntity replacement = null;
        if (mob instanceof Mob m) {
            double range = 16.0D;
            double best = range * range;
            for (Player other : mob.level().players()) {
                if (other == downed || !other.isAlive() || other.isSpectator() || other.isCreative()
                    || ReviveService.isDowned(other)) {
                    continue;
                }
                double d = other.distanceToSqr(m);
                if (d < best) {
                    best = d;
                    replacement = other;
                }
            }
        }
        if (replacement != null) {
            event.setNewAboutToBeSetTarget(replacement);
        } else {
            event.setCanceled(true);
        }
    }

    /** True when the entity is a downed player on this side (tests and other lanes). */
    public static boolean isDownedPlayer(Entity entity) {
        return entity instanceof Player player && ReviveService.isDowned(player);
    }
}

package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.parley.RaidParley;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus wiring for conversations; no shared file needs to know about them. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ConversationEvents {
    private ConversationEvents() {
    }

    @SubscribeEvent
    public static void onReload(AddReloadListenerEvent event) {
        event.addListener(new ConversationGraphs.Loader());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ConversationService.clearAll();
        RaidParley.bootstrap();
        TownFactsLive.registerConditions();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ConversationService.clearAll();
        Departure.clear();
        RaidParley.clear();
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        ConversationService.tick(event.getServer());
        RaidParley.tick(event.getServer());
        Departure.tick(event.getServer());
    }

    /** Right-click a bound NPC: talk. HIGH so the talk wins over vanilla trade/menus. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || !ConversationService.isBound(event.getTarget())) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !ConversationConfig.enabled()) return;
        // Both hands are consumed (a bound villager must not open its trade menu with the off hand).
        if (event.getHand() == InteractionHand.MAIN_HAND) ConversationService.onRightClick(player, event.getTarget());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (Departure.onIncomingDamage(event.getEntity(), event.getSource())
            || RaidParley.blockDamage(event.getEntity(), event.getSource(), event.getAmount())) event.setCanceled(true);
    }

    /** Duel yields, on the damage left after shield, armour and enchantments (LOWEST: after other modifiers). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        if (RaidParley.yieldOnDamage(event.getEntity(), event.getSource(), event.getNewDamage())) {
            event.setNewDamage(0.0F);
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F) return;
        ConversationService.onHurt(event.getEntity());
        RaidParley.onHurt(event.getEntity());
    }

    /** A raider left frozen by a parley that no longer exists (e.g. a crash) is released on load. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        // A leaver that unloaded mid-walk is not revived (owner rule: no stale entities).
        if (event.loadedFromDisk() && Departure.dropOnLoad(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        RaidParley.onJoin(event.getEntity());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ConversationService.closeFor(player, null);
    }
}

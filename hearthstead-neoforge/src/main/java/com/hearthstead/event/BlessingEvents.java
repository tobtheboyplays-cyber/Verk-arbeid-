package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.UUID;

/** Server-authoritative hooks for the three permanent raid Blessings. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class BlessingEvents {
    public static final ResourceLocation THORNED_ROADS_MODIFIER =
        Hearthstead.id("thorned_roads");
    /** Two seconds: readable control, never a permanent attribute state. */
    public static final int PERSONAL_SNARE_DURATION_TICKS = 40;
    /** Eight ticks caps building lookups at 2.5 per second per live raider. */
    public static final int BUILDING_ZONE_REFRESH_TICKS = 8;

    /**
     * Name tags and spawn eggs are handled by Mob before mobInteract, so a
     * normal entity override cannot protect a delivered offhand seal from a
     * consuming main item. NeoForge's pre-interaction event is the earliest
     * authoritative boundary: route the actual offhand stack once and cancel
     * only when that physical seal handled a living target.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOffhandSealEntityInteract(
            PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || !event.getEntity().isShiftKeyDown()
            || event.getEntity().getMainHandItem().getItem()
                instanceof BlessingSealItem
            || !(event.getEntity().getOffhandItem().getItem()
                instanceof BlessingSealItem seal)
            || !(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        InteractionResult result = seal.interactLivingEntity(
            event.getEntity().getOffhandItem(), event.getEntity(), target,
            InteractionHand.OFF_HAND);
        if (result.consumesAction()) {
            event.setCanceled(true);
            event.setCancellationResult(result);
        }
    }

    /**
     * Changes only a hit whose attacker, target and active raid all name the
     * same settlement. Vanilla mobs, players and neighbouring settlements are
     * never caught by a broad attribute buff.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof RaiderEntity raider
            && event.getSource().getEntity() instanceof SettlerEntity settler) {
            Settlement settlement = raider.settlement();
            UUID settlementId = raider.settlementId();
            if (settlementId == null || !settlementId.equals(settler.getSettlementId())
                || !BlessingEffects.isAuthorizedRaidParticipant(settlement, raider)) {
                return;
            }

            if (settler.getProfession().martial()) {
                int wardenRank = BlessingEffects.effectiveSettlerRank(
                    settlement, settler, BlessingId.WARDEN_OATH);
                if (wardenRank > 0) {
                    event.setAmount(event.getAmount()
                        * BlessingEffects.wardenDamageMultiplier(wardenRank));
                }
            }

            return;
        }
        if (event.getEntity() instanceof SettlerEntity settler
            && event.getSource().getEntity() instanceof RaiderEntity raider) {
            Settlement settlement = raider.settlement();
            UUID settlementId = raider.settlementId();
            if (settlementId != null && settlementId.equals(settler.getSettlementId())
                && BlessingEffects.isAuthorizedRaidParticipant(settlement, raider)) {
                int hearthwardRank = BlessingEffects.effectiveSettlerRank(
                    settlement, settler, BlessingId.HEARTHWARD);
                if (hearthwardRank > 0) {
                    event.setAmount(event.getAmount()
                        * BlessingEffects.hearthwardDamageMultiplier(
                            hearthwardRank));
                }
            }
        }
    }

    /**
     * Applies the personal Thorned Roads rider only after health was actually
     * lost. Keeping this side effect on the post-damage event means a shield,
     * a zeroed hit or a later cancellation can never grant a free snare.
     * Building ranks intentionally stay out of this branch: their zone is
     * evaluated independently and throttled in {@link #onRaiderTick}.
     */
    @SubscribeEvent
    public static void onDamageApplied(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F
            || !(event.getEntity() instanceof RaiderEntity raider)
            || !(event.getSource().getEntity() instanceof SettlerEntity settler)
            || !(settler.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Settlement settlement = raider.settlement();
        UUID settlementId = raider.settlementId();
        if (settlementId == null || !settlementId.equals(settler.getSettlementId())
            || !BlessingEffects.isAuthorizedRaidParticipant(settlement, raider)) {
            return;
        }

        int thornedRank = settler.blessingRank(BlessingId.THORNED_ROADS);
        if (thornedRank > 0) {
            raider.applyTransientBlessingSnare(thornedRank,
                serverLevel.getGameTime(), PERSONAL_SNARE_DURATION_TICKS);
        }
    }

    /**
     * Keeps Torneveier transient and change-detected. Reapplying an identical
     * synced attribute every tick would waste packets; persisting it would
     * risk stacking after reload. This does neither.
     */
    @SubscribeEvent
    public static void onRaiderTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof RaiderEntity raider)
            || !(raider.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        AttributeInstance speed = raider.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }

        Settlement settlement = raider.settlement();
        if (!BlessingEffects.isAuthorizedRaidParticipant(settlement, raider)) {
            raider.clearBlessingRuntimeState();
            updateSpeedModifier(speed, 0.0D);
            return;
        }

        long now = serverLevel.getGameTime();
        long revision = settlement.buildingBlessingRevision();
        if (raider.blessingZoneRefreshDue(now, revision,
                BUILDING_ZONE_REFRESH_TICKS)) {
            raider.cacheBlessingZoneRank(BlessingEffects.buildingRankAt(
                settlement, raider, BlessingId.THORNED_ROADS));
        }
        int effectiveRank = Math.max(raider.cachedBlessingZoneRank(),
            raider.transientBlessingSnareRank(now));
        updateSpeedModifier(speed,
            BlessingEffects.thornedRoadsModifier(effectiveRank));
    }

    /** Change-detected: unchanged ticks emit no attribute sync packet. */
    private static void updateSpeedModifier(AttributeInstance speed,
                                            double wanted) {
        AttributeModifier current = speed.getModifier(THORNED_ROADS_MODIFIER);
        if (wanted == 0.0D) {
            if (current != null) {
                speed.removeModifier(THORNED_ROADS_MODIFIER);
            }
            return;
        }
        if (current == null || Math.abs(current.amount() - wanted) > 1.0E-9D) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(
                THORNED_ROADS_MODIFIER, wanted,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private BlessingEvents() {
    }
}

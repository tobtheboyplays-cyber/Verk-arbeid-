package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.research.Research;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The research house's two triggers: a player opening the screen, and the
 * settlement's own clock advancing the passive scribe trickle.
 *
 * <p>{@code PLAQUE_BLOCK.useWithoutItem} is not available here the way it is
 * for {@code PlaqueBlock} and {@code HearthBlock} — the study's lectern is
 * plain vanilla {@code Blocks.LECTERN} (a furniture <i>requirement</i>, per
 * {@code BuildingType.ARCHITECTS_STUDY}), not a block this mod owns, so there
 * is no subclass to override. {@link PlayerInteractEvent.RightClickBlock} is
 * the documented seam for exactly this: intercept a vanilla block's
 * interaction from mod code (mirrors this file's own {@code
 * CommonEvents}'s {@code @EventBusSubscriber} style for every other
 * cross-cutting hook).
 *
 * <p>Cancelling the vanilla interaction on a registered study's own lectern
 * is deliberate, not an oversight: the same way {@code HearthBlock} replaces
 * a plain container's behaviour once it is founding a settlement, this
 * lectern becomes the study's control surface once the room around it is a
 * registered {@code ARCHITECTS_STUDY} — reading a placed book there is
 * superseded, exactly as reading loose chest contents is superseded once a
 * chest joins a warehouse. A lectern anywhere else (including the LIBRARY
 * building type's own, unrelated lectern requirement) behaves exactly like
 * vanilla, because the building-bounds check below is what gates this.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ResearchEvents {

    /** Only the first second of a new in-game day; the trickle's own
     *  once-per-day guard (see {@code Research#tickDailyTrickle}) makes the
     *  window's exact width harmless, and 20 ticks of margin means a missed
     *  tick zero (server hiccup, a level that was not loaded at that instant)
     *  still fires the same day rather than being skipped entirely. */
    private static final long TRICKLE_WINDOW_TICKS = 20L;

    @SubscribeEvent
    public static void onRightClickLectern(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return; // vanilla dispatches this once per hand; act on one
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(Blocks.LECTERN)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Settlement settlement = SettlementManager.at(level, pos);
        if (settlement == null) {
            return; // no settlement claims this lectern; leave vanilla alone
        }
        Building study = Research.studyAt(settlement, pos);
        if (study == null) {
            return; // not the lectern of a registered, valid study
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        com.hearthstead.network.ResearchNetwork.open(player, settlement, study, pos);
    }

    /**
     * Drives the passive scribe trickle once a day, per settlement that has
     * ever touched research — bounded on both axes: the tick window below
     * only opens for {@value #TRICKLE_WINDOW_TICKS} ticks a day, and even
     * then only {@code Research}'s own (already small) settlement map is
     * walked, never every settlement in the world.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getDayTime() % 24000L >= TRICKLE_WINDOW_TICKS) {
            return;
        }
        Research data = Research.get(level);
        if (data.settlements.isEmpty()) {
            return;
        }
        SettlementSavedData settlementData = SettlementSavedData.get(level);
        for (var entry : data.settlements.entrySet()) {
            if (entry.getValue().active == null) {
                continue;
            }
            Settlement settlement = settlementData.settlements.get(entry.getKey());
            if (settlement == null) {
                continue;
            }
            Building study = Research.firstStudy(settlement);
            if (study != null) {
                Research.tickDailyTrickle(level, settlement, study);
            }
        }
    }

    private ResearchEvents() {
    }
}

package com.hearthstead.event.worldevent;

import com.hearthstead.settlement.Settlement;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/**
 * One small world event. The director owns scheduling, budgets, actor
 * bookkeeping and cleanup; a handler owns what happens in the world.
 * Every callback runs on the server thread, and only while someone is near
 * the settlement (pause-when-empty).
 */
public interface WorldEventHandler {
    WorldEventType type();

    /** Whether what the event needs exists here (fields, tavern, livestock...). */
    boolean available(ServerLevel level, Settlement settlement);

    /** Spawns and announces. False aborts the start (nothing is kept). */
    boolean start(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active);

    /** Once per second while eligible. May call {@link WorldEventDirector#finish}. */
    void tick(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active);

    /** Eligible-time budget ran out: the default outcome, which must end the event. */
    default void timeout(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active) {
        WorldEventDirector.finish(level, settlement, "timeout", null);
    }

    /** Last look before the director discards the actors. */
    default void cleanup(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active, String outcome) {
    }

    /** A tagged actor entered the world (spawn or chunk load): re-install custom AI. */
    default void actorLoaded(ServerLevel level, WorldEventSavedData.Active active, Entity actor) {
    }

    /** A tagged actor died. */
    default void actorDied(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                           Entity actor, DamageSource source) {
    }

    /** Right-click on a tagged actor that is not waiting for an answer (e.g. the peddler's wares). */
    default boolean interact(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                             ServerPlayer player, Entity actor) {
        return false;
    }

    // ------------------------------------------------------------ visitors --

    /** Whether {@code actor} is a visitor currently waiting for somebody's answer. */
    default boolean awaitingAnswer(WorldEventSavedData.Active active, Entity actor) {
        return false;
    }

    /** Title, speaker and lines of the visitor's request. */
    default WorldEventVisitors.Topic topic(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                                           ServerPlayer player, Entity actor) {
        return null;
    }

    /** The answers on offer; hidden persuasion outcomes are not listed. */
    default List<WorldEventVisitors.Option> options(ServerLevel level, Settlement settlement,
                                                    WorldEventSavedData.Active active, ServerPlayer player, Entity actor) {
        return List.of();
    }

    /**
     * Runs an already validated and paid answer. Returns the notice every
     * nearby player sees (null for none).
     */
    default Component answer(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                             ServerPlayer player, Entity actor, String optionId) {
        return null;
    }
}

package com.hearthstead.revive;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Design hook (no implementation yet): lets settlement NPCs help downed
 * players once the village has an Infirmary / Healer.
 *
 * <p>Planned use: a guard goal (e.g. {@code GuardRescueGoal}) registers one
 * rescuer through {@link ReviveService#registerRescuer}. Once per second the
 * service asks every registered rescuer about every downed player at a
 * settlement. A rescuer that wants to help walks its settler to the player
 * and then, each tick while in reach, calls
 * {@link ReviveService#pingRevive(net.minecraft.world.entity.LivingEntity, ServerPlayer)}
 * exactly like a player holding the use key (same timer, same damage
 * interruption), or {@link ReviveService#startDrag} to haul the player
 * toward the Infirmary. Gating on an Infirmary existing stays inside the
 * rescuer, so the core feature never depends on buildings.
 */
@FunctionalInterface
public interface DownedRescuer {
    /**
     * Offered once per second for each downed player whose settlement is
     * known. Return true if this rescuer has taken the job (the service then
     * stops offering it to later rescuers this second).
     */
    boolean offer(ServerLevel level, ServerPlayer downed, java.util.UUID settlementId,
                  int bleedTicksLeft);
}

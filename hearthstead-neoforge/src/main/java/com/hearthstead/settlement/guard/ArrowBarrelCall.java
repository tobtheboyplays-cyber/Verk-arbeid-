package com.hearthstead.settlement.guard;

import com.hearthstead.block.ArrowBarrelBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.entity.ai.ArcherResupplyGoal;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Arrow Barrel's "Call archers to resupply" button (owner, 27 Sep: the
 * resupply order is given AT the barrel). Server-validated: the player must
 * stand at the barrel, the barrel must lie in the settlement the player
 * commands and hold arrows, and one barrel answers at most once per
 * {@link #COOLDOWN_TICKS}. Every short archer of that settlement then runs to
 * THIS barrel, refills and returns to his post, formation or order.
 */
public final class ArrowBarrelCall {
    public static final int COOLDOWN_TICKS = 100;
    /** The player must be at the barrel (the menu's own reach). */
    public static final double REACH = 8.0D;

    public enum Outcome { SENT, NONE_SHORT, EMPTY, COOLDOWN, NO_SETTLEMENT, TOO_FAR, NOT_BARREL }

    public record Result(Outcome outcome, int called) {
    }

    /** Server-thread only: last call per barrel ("dimension|pos"), game time. */
    private static final Map<String, Long> LAST = new HashMap<>();

    private ArrowBarrelCall() {
    }

    public static Result call(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        Result result = evaluate(player, level, pos);
        String key = "hearthstead.arrow_barrel.call." + result.outcome().name().toLowerCase(java.util.Locale.ROOT);
        player.displayClientMessage(Component.translatable(key, result.called()), true);
        if (result.outcome() == Outcome.SENT) {
            level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
                com.hearthstead.registry.ModSounds.COMMAND_SHOUT.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        return result;
    }

    private static Result evaluate(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ArrowBarrelBlockEntity barrel)) {
            return new Result(Outcome.NOT_BARREL, 0);
        }
        if (!player.isAlive() || player.isSpectator()
            || player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH * REACH) {
            return new Result(Outcome.TOO_FAR, 0);
        }
        Settlement settlement = FieldOrders.commandedSettlement(player);
        if (settlement == null || settlement.center == null) {
            return new Result(Outcome.NO_SETTLEMENT, 0);
        }
        double reach = settlement.radius + FieldOrderRules.COMMAND_REACH_BEYOND_CLAIM;
        if (pos.distSqr(settlement.center) > reach * reach) {
            return new Result(Outcome.NO_SETTLEMENT, 0);
        }
        long now = level.getGameTime();
        String key = level.dimension().location() + "|" + pos.asLong();
        Long last = LAST.get(key);
        if (last != null && now >= last && now - last < COOLDOWN_TICKS) {
            return new Result(Outcome.COOLDOWN, 0);
        }
        if (barrel.arrows() <= 0) {
            return new Result(Outcome.EMPTY, 0);
        }
        LAST.put(key, now);
        List<SettlerEntity> short_ = new ArrayList<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (!(level.getEntity(record.entityId) instanceof SettlerEntity archer)
                || !archer.isAlive() || archer.getProfession() != Profession.ARCHER
                || !settlement.id.equals(archer.getSettlementId())
                || !EquipmentRequests.readyForProfession(level, archer, Profession.ARCHER)) {
                continue;
            }
            if (archer.archerQuiverCount() < ArcherAttackGoal.quiverCapacity(archer)) {
                short_.add(archer);
            }
        }
        if (short_.isEmpty()) {
            return new Result(Outcome.NONE_SHORT, 0);
        }
        ArcherResupplyGoal.callTo(short_, now, pos);
        return new Result(Outcome.SENT, short_.size());
    }
}

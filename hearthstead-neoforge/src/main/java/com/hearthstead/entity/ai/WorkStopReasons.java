package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * Player-facing idle reasons for non-courier work goals (QA-JOBS J-01, J-05,
 * J-10). Reuses the settler's logistics stop projection, so the settler sheet
 * shows e.g. "Waiting for input" / "Chest full" with its existing fix line
 * instead of "Nothing needed from you". Only the three work reasons are ever
 * cleared here, so a courier-owned diagnosis is never erased by accident.
 */
final class WorkStopReasons {

    static void report(SettlerEntity settler, StopReason reason,
                       @Nullable BlockPos at) {
        if (settler.logisticsStopReason() != reason) {
            settler.setLogisticsStop(reason, at, 0);
        }
    }

    static void clear(SettlerEntity settler) {
        StopReason current = settler.logisticsStopReason();
        if (current == StopReason.WAITING_INPUT
            || current == StopReason.CHEST_FULL
            || current == StopReason.NO_VALID_TARGET) {
            settler.clearLogisticsStop();
        }
    }

    private WorkStopReasons() {
    }
}

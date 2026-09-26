package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Proves that production authority evidence is an observation, not a seam. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class AuthorityTelemetryGameTests {
    @GameTest(template = "empty5", timeoutTicks = 40,
        batch = "authority_telemetry_read_only")
    public void serverThreadEmissionDoesNotMutateSettlementState(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Telemetry",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        int mapSizeBefore = data.settlements.size();
        int populationBefore = settlement.population();
        BlockPos centerBefore = settlement.center;
        Settlement identityBefore = data.settlements.get(settlement.id);

        boolean emitted = AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.STATE_LOAD_SUMMARY,
            AuthorityTelemetry.Result.OBSERVED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "settlement:" + settlement.id, 4, 4, mapSizeBefore,
                mapSizeBefore, "gametest_read_only"));

        helper.assertTrue(emitted,
            "a genuine GameTest server-thread transaction must be observable");
        helper.assertTrue(data.settlements.size() == mapSizeBefore,
            "telemetry must not change settlement cardinality");
        helper.assertTrue(data.settlements.get(settlement.id) == identityBefore,
            "telemetry must not replace the authoritative settlement object");
        helper.assertTrue(settlement.population() == populationBefore,
            "telemetry must not change population");
        helper.assertTrue(settlement.center.equals(centerBefore),
            "telemetry must not change settlement identity or location");
        helper.succeed();
    }
}

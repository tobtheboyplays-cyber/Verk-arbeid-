package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.state.FoundingJourney;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Runtime proof that founding and its three settlers commit atomically. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FoundingJourneyGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "founding_journey_atomic_founders")
    public void thirdFounderFailureRollsBackTheWholeFounding(GameTestHelper helper) {
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }

        BlockPos hearthPos = helper.absolutePos(new BlockPos(8, 1, 8));
        List<SettlerEntity> spawned = new ArrayList<>(2);
        Settlement[] attempted = {null};
        int[] calls = {0};
        boolean[] exposedBeforeCommit = {false};
        boolean previousDistanceOverride = SettlementManager.ignoreFoundingDistance;
        Settlement result;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            result = SettlementManager.tryFoundWithSpawner(
                helper.getLevel(), hearthPos, (level, settlement) -> {
                    attempted[0] = settlement;
                    calls[0]++;
                    exposedBeforeCommit[0] |= SettlementManager.byId(
                        level, settlement.id) != null;
                    if (calls[0] == 3) {
                        return null;
                    }
                    SettlerEntity founder = SettlementManager.spawnSettler(
                        level, settlement, false);
                    if (founder != null) {
                        spawned.add(founder);
                    }
                    return founder;
                });
        } finally {
            SettlementManager.ignoreFoundingDistance = previousDistanceOverride;
        }

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        helper.assertTrue(result == null,
            "a failed third founder must fail the complete founding transaction");
        helper.assertTrue(calls[0] == 3 && spawned.size() == 2,
            "the fixture must create exactly two real founders before the injected third failure");
        helper.assertTrue(attempted[0] != null,
            "the transaction must have created an attempted settlement record");
        helper.assertTrue(!exposedBeforeCommit[0],
            "a partial settlement must stay invisible until all three founders commit");
        helper.assertTrue(SettlementManager.at(helper.getLevel(), hearthPos) == null
                && !data.settlements.containsKey(attempted[0].id),
            "failed founding must leave no registered settlement at the Hearth");
        helper.assertTrue(attempted[0].settlers.isEmpty(),
            "failed founding must remove every provisional settler record");
        helper.assertTrue(attempted[0].foundingJourney.phase()
                == FoundingJourney.Phase.SKIPPED,
            "the active journey must never begin before all three founders commit");
        for (SettlerEntity founder : spawned) {
            helper.assertTrue(founder.isRemoved() && !founder.isAlive(),
                "every provisional founder entity must be discarded on rollback");
        }
        helper.succeed();
    }
}

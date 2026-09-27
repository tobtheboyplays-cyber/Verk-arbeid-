package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.RaidProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Runtime proof that founding and its four ordinary founders commit atomically. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FoundingJourneyGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "founding_journey_atomic_founders")
    public void fourthFounderFailureRollsBackTheWholeFounding(GameTestHelper helper) {
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }

        BlockPos hearthPos = helper.absolutePos(new BlockPos(8, 1, 8));
        List<SettlerEntity> spawned = new ArrayList<>(3);
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
                    if (calls[0] == 4) {
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
            "a failed fourth founder must fail the complete founding transaction");
        helper.assertTrue(calls[0] == 4 && spawned.size() == 3,
            "the fixture must create exactly three real founders before the injected fourth failure");
        helper.assertTrue(attempted[0] != null,
            "the transaction must have created an attempted settlement record");
        helper.assertTrue(!exposedBeforeCommit[0],
            "a partial settlement must stay invisible until all four founders commit");
        helper.assertTrue(SettlementManager.at(helper.getLevel(), hearthPos) == null
                && !data.settlements.containsKey(attempted[0].id),
            "failed founding must leave no registered settlement at the Hearth");
        helper.assertTrue(attempted[0].settlers.isEmpty(),
            "failed founding must remove every provisional settler record");
        helper.assertTrue(attempted[0].foundingJourney.phase()
                == FoundingJourney.Phase.SKIPPED,
            "the active journey must never begin before all four founders commit");
        for (SettlerEntity founder : spawned) {
            helper.assertTrue(founder.isRemoved() && !founder.isAlive(),
                "every provisional founder entity must be discarded on rollback");
        }
        helper.succeed();
    }
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "founding_dedicated_mayor")
    public void fourFoundersIncludeExactlyThreeHireableWorkers(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos hearth = helper.absolutePos(new BlockPos(8, 1, 8));
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement settlement;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(helper.getLevel(), hearth);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }
        helper.assertTrue(settlement != null && settlement.population() == 4,
            "founding must atomically create four ordinary founders");
        long foundingNight = settlement.raidLifecycle.foundedNight();
        long rolledNotBeforeNight = settlement.raidLifecycle.rolledNotBeforeNight();
        helper.assertTrue(settlement.raidProfile == RaidProfile.BALANCED
                && settlement.raidLifecycle.firstState() == FirstRaidState.PREPARING
                && rolledNotBeforeNight - foundingNight >= 2L
                && rolledNotBeforeNight - foundingNight <= 3L
                && settlement.raidLifecycle.warningLead() == 1
                && settlement.raidLifecycle.queuedPlan().isEmpty(),
            "a real new founding must choose the B02 2-3-night floor, one-night warning, and no queued raid");
        // The Mayor office is retired: all four founders are ordinary,
        // unassigned settlers and nobody holds a seat.
        List<SettlerEntity> founders = SettlementManager.loadedMembers(helper.getLevel(), settlement);
        helper.assertTrue(founders.size() == 4 && settlement.mayorId == null
                && founders.stream().allMatch(founder -> founder.getProfession()
                    == com.hearthstead.entity.Profession.NONE),
            "all four founders must start unassigned with no Mayor seat");
        Building camp = com.hearthstead.gametest.GameTestFixtures.register(helper,
            settlement, com.hearthstead.building.BuildingType.LUMBER_CAMP, 2, 2);
        helper.assertTrue(Employment.candidatesFor(helper.getLevel(), settlement, camp).size() == 4,
            "every one of the four founders must be a hireable worker");
        helper.assertTrue(SettlementManager.tryFound(helper.getLevel(), hearth) == settlement
                && settlement.population() == 4 && settlement.mayorId == null
                && settlement.raidProfile == RaidProfile.BALANCED
                && settlement.raidLifecycle.firstState() == FirstRaidState.PREPARING
                && settlement.raidLifecycle.rolledNotBeforeNight() == rolledNotBeforeNight
                && settlement.raidLifecycle.warningLead() == 1
                && settlement.raidLifecycle.queuedPlan().isEmpty(),
            "repeated founding must not add founders, appoint anyone, or reroll the B02 calendar");
        helper.assertTrue(settlement.journeyState.isCompleted(
                com.hearthstead.settlement.journey.JourneyIds.FJ_010_FOUND_HEARTH)
            && !settlement.journeyState.isCompleted(
                com.hearthstead.settlement.journey.JourneyIds.FJ_030_APPOINT_MAYOR),
            "founding is genuine; FJ030 evidence must await a live Guildmaster meeting");
        helper.succeed();
    }

    /**
     * Capacity setup must not hide an orphaned record behind a founder trim.
     * This reaches the live founding transaction, kills three of the four
     * founders through Entity#kill, the same terminal path as /kill, then waits beyond corpse removal.
     */
    @GameTest(template = "empty16", timeoutTicks = 160,
        batch = "founding_death_roster")
    public void killingThreeFoundersIncludingMayorLeavesOneRecordAndOneLiveMember(
            GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos hearth = helper.absolutePos(new BlockPos(8, 1, 8));
        boolean previous = SettlementManager.ignoreFoundingDistance;
        Settlement settlement;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(helper.getLevel(), hearth);
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
        }

        helper.assertTrue(settlement != null && settlement.population() == 4,
            "fixture must commit four real founders before any death");
        List<SettlerEntity> founders = new ArrayList<>(
            SettlementManager.loadedMembers(helper.getLevel(), settlement));
        helper.assertTrue(founders.size() == 4,
            "fixture must load the four committed founder entities");
        helper.assertTrue(settlement.mayorId == null,
            "the retired Mayor office must leave every founder an ordinary member");

        SettlerEntity retained = null;
        List<SettlerEntity> doomed = new ArrayList<>(3);
        for (SettlerEntity founder : founders) {
            founder.setNoAi(true);
            if (doomed.size() < 3) {
                doomed.add(founder);
            } else {
                retained = founder;
            }
        }
        helper.assertTrue(doomed.size() == 3 && retained != null,
            "fixture must choose exactly three founders to die");
        java.util.UUID retainedId = retained.getUUID();
        List<java.util.UUID> deadIds = doomed.stream()
            .map(SettlerEntity::getUUID).toList();

        int[] actualKillCalls = {0};
        for (SettlerEntity dead : doomed) {
            dead.kill(); // LivingEntity#kill: the exact /kill terminal path.
            actualKillCalls[0]++;
            helper.assertTrue(!dead.isAlive() && dead.isDeadOrDying(),
                "fixture /kill-equivalent must terminate " + dead.getUUID());
        }
        helper.assertTrue(actualKillCalls[0] == 3,
            "fixture must invoke the exact /kill path for three founders");
        helper.assertTrue(settlement.population() == 1,
            "each accepted death must remove its saved record immediately; got "
                + settlement.population());
        helper.assertTrue(settlement.mayorId == null && settlement.mourningUntil
                <= helper.getLevel().getGameTime(),
            "with no Mayor office, founder deaths must start no Mayor mourning");

        helper.runAfterDelay(25, () -> {
            List<SettlerEntity> loaded = SettlementManager.loadedMembers(
                helper.getLevel(), settlement);
            List<java.util.UUID> resurrectedDeadIds = deadIds.stream()
                .filter(deadId -> settlement.record(deadId) != null).toList();
            helper.assertTrue(settlement.population() == 1 && loaded.size() == 1
                    && resurrectedDeadIds.isEmpty(),
                "after corpse removal one saved record and one live member must remain; records="
                    + settlement.population() + ", loaded=" + loaded.size()
                    + ", resurrectedDeadIds=" + resurrectedDeadIds);
            helper.assertTrue(loaded.get(0).getUUID().equals(retainedId)
                    && helper.getLevel().getEntity(retainedId) == loaded.get(0)
                    && loaded.get(0).isAlive(),
                "the retained founder must be the sole live, registered entity");
            for (java.util.UUID deadId : deadIds) {
                helper.assertTrue(settlement.settlers.stream().noneMatch(record ->
                        record.entityId.equals(deadId))
                        && helper.getLevel().getEntity(deadId) == null,
                    "a removed founder UUID may not survive in either record or world: "
                        + deadId);
            }
            helper.succeed();
        });
    }

}

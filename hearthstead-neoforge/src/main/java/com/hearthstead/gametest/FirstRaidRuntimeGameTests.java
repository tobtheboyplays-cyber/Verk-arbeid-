package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModDamageTypes;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Slice B: authored first-raid runtime and Peaceful combat contracts. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FirstRaidRuntimeGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement registeredSettlement(GameTestHelper helper,
                                                   String name,
                                                   BlockPos centerRel) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(centerRel));
        settlement.radius = 10;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static RaidPlan armFirstRaid(GameTestHelper helper,
                                         Settlement settlement,
                                         RaidObjective objective) {
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2),
            "first raid fixture should initialize");
        RaidCaptain captain = RaidDirector.pickCaptain(settlement,
            helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), objective, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "first raid fixture should activate the exact queued plan");
        settlement.pendingRaid = plan;
        return plan;
    }

    private static RaiderEntity participant(GameTestHelper helper,
                                             Settlement settlement,
                                             RaidPlan plan,
                                             BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(plan.captainId(), settlement.id, plan.objective(),
            1.0F, false);
        helper.assertTrue(settlement.raidLifecycle.recordParticipant(raider.getUUID()),
            "actual raider UUID should enter the first-raid capture");
        return raider;
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "first_raid_peaceful_attack_is_real")
    public void peacefulRaiderSurvivesTargetsAndDamagesPlayer(GameTestHelper helper) {
        buildArena(helper, 8);
        helper.assertTrue(helper.getLevel().getDifficulty() == Difficulty.PEACEFUL,
            "the QA level must be Peaceful for this regression to prove anything");

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(2, 1, 2));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absolutePos(new BlockPos(3, 1, 2)).getCenter());
        float before = player.getHealth();

        helper.assertTrue(raider.canAttack(player),
            "Peaceful must not veto a visible Player target");
        helper.assertTrue(raider.doHurtTarget(player),
            "the real raider melee path must land on Peaceful");
        helper.assertTrue(player.getHealth() < before,
            "scaling=never must reduce Player health on Peaceful: before="
                + before + " after=" + player.getHealth());
        helper.assertTrue(player.getLastDamageSource() != null
                && player.getLastDamageSource().is(ModDamageTypes.RAIDER_ATTACK),
            "the landed hit must use hearthstead:raider_attack");

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(raider.isAlive() && !raider.isRemoved(),
                "a raid entity must not be discarded by Peaceful ticking");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 300,
        batch = "first_raid_warning_queues_once_and_spawns_exact_plan")
    public void warningQueuesOnceAndArrivalUsesExactPlan(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement settlement = registeredSettlement(helper, "Varselvik",
            new BlockPos(8, 1, 8));
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(10L, 4, 2),
            "authored schedule should initialize");

        helper.assertTrue(!RaidDirector.queueFirstWarningIfDue(
                helper.getLevel(), settlement, 11L),
            "night 11 is before the persisted warning night");
        helper.assertTrue(RaidDirector.queueFirstWarningIfDue(
                helper.getLevel(), settlement, 12L),
            "night 12 should create the one warning plan");
        RaidPlan warned = settlement.raidLifecycle.queuedPlan().orElseThrow();
        helper.assertTrue(warned.night() == 14L,
            "the warning must name the already-rolled attack night");
        helper.assertTrue(!RaidDirector.queueFirstWarningIfDue(
                helper.getLevel(), settlement, 13L)
                && settlement.raidLifecycle.queuedPlan().orElseThrow().equals(warned),
            "later ticks must not replace or reroll the warned plan");

        List<RaiderEntity> early = RaidDirector.startQueuedFirstRaid(
            helper.getLevel(), settlement, 13L);
        helper.assertTrue(early.isEmpty()
                && settlement.raidLifecycle.firstState() == FirstRaidState.SCHEDULED
                && settlement.raidLifecycle.queuedPlan().orElseThrow().equals(warned)
                && settlement.raidLifecycle.participants().isEmpty(),
            "night 13 must not start the band planned for night 14");

        List<RaiderEntity> band = RaidDirector.startQueuedFirstRaid(
            helper.getLevel(), settlement, 14L);
        helper.assertTrue(!band.isEmpty(), "the warned band should actually arrive");
        helper.assertTrue(settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE
                && settlement.raidLifecycle.activePlan().orElseThrow().equals(warned)
                && settlement.pendingRaid.equals(warned),
            "activation must use record-equality with the one warned plan");
        helper.assertTrue(settlement.raidLifecycle.participantsTracked()
                && settlement.raidLifecycle.participants().size() == band.size(),
            "the sealed ledger must contain exactly the accepted entity UUIDs");
        helper.assertTrue(settlement.raidPressure.lastRolledNight() == warned.night()
                && settlement.raidPressure.nightsSinceRaid() == 0,
            "authored arrival must block a same-night recurring pressure roll");
        for (RaiderEntity raider : band) {
            helper.assertTrue(settlement.raidLifecycle.participants()
                    .contains(raider.getUUID()),
                "every accepted raider must be in the sealed capture");
            raider.discard();
        }
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), settlement),
            "cleanup should close after every captured UUID is terminal");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "first_raid_zero_spawn_stays_queued_without_false_arrival")
    public void zeroSpawnStaysQueuedForSafeRetry(GameTestHelper helper) {
        // No floor exists within the bounded vertical search at this height.
        Settlement settlement = new Settlement(UUID.randomUUID(), "Skyhold",
            helper.absolutePos(new BlockPos(8, 300, 8)));
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2),
            "zero-spawn fixture should initialize");
        RaidCaptain captain = RaidDirector.pickCaptain(settlement,
            helper.getLevel().getRandom());
        RaidPlan warned = new RaidPlan(captain.id(), RaidObjective.BLOD, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.queueFirstPlan(warned),
            "zero-spawn fixture should retain a warned plan");

        List<RaiderEntity> band = RaidDirector.startQueuedFirstRaid(
            helper.getLevel(), settlement, 4L);
        helper.assertTrue(band.isEmpty(), "no footing means no actual participants");
        helper.assertTrue(settlement.raidLifecycle.firstState() == FirstRaidState.SCHEDULED
                && settlement.raidLifecycle.queuedPlan().orElseThrow().equals(warned)
                && settlement.raidLifecycle.participants().isEmpty()
                && settlement.pendingRaid == null,
            "zero spawns must stay queued and never announce/activate a false raid");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "first_raid_chunk_unload_is_not_terminal")
    public void chunkUnloadCannotProduceFalseVictory(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement settlement = registeredSettlement(helper, "Lastvik",
            new BlockPos(5, 1, 5));
        RaidPlan plan = armFirstRaid(helper, settlement, RaidObjective.KORN);
        RaiderEntity unloaded = participant(helper, settlement, plan,
            new BlockPos(3, 1, 5));
        RaiderEntity destroyed = participant(helper, settlement, plan,
            new BlockPos(7, 1, 5));
        helper.assertTrue(settlement.raidLifecycle.sealParticipants(),
            "two actual UUIDs should seal");

        unloaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        destroyed.discard();
        helper.assertTrue(settlement.raidLifecycle.terminalParticipants().size() == 1
                && !settlement.raidLifecycle.terminalParticipants()
                    .contains(unloaded.getUUID()),
            "only explicit destruction may enter the terminal ledger");
        helper.assertTrue(!RaidDirector.resolveIfOver(helper.getLevel(), settlement)
                && settlement.pendingRaid != null
                && settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE,
            "an empty loaded AABB must not close a raid with an unloaded UUID");

        RaidLifecycle reloaded = RaidLifecycle.readNbt(
            settlement.raidLifecycle.writeNbt());
        helper.assertTrue(!reloaded.integrityLost()
                && reloaded.terminalParticipants().size() == 1
                && !reloaded.allParticipantsTerminal(),
            "partial definitive evidence must remain pending across reload");
        SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 250,
        batch = "first_raid_definitive_completion_grants_once")
    public void definitiveHeldCompletionGrantsExactlyOneSharedOffer(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement settlement = registeredSettlement(helper, "Eidheim",
            new BlockPos(5, 1, 5));
        RaidPlan plan = armFirstRaid(helper, settlement, RaidObjective.KORN);
        RaiderEntity fallen = participant(helper, settlement, plan,
            new BlockPos(3, 1, 5));
        RaiderEntity escaped = participant(helper, settlement, plan,
            new BlockPos(7, 1, 5));
        helper.assertTrue(settlement.raidLifecycle.sealParticipants(),
            "two actual UUIDs should seal");

        fallen.die(helper.getLevel().damageSources().genericKill());
        escaped.discard();
        helper.assertTrue(settlement.raidLifecycle.allParticipantsTerminal(),
            "death and explicit discard should both be definitive");
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), settlement),
            "all definitive outcomes should close the authored first raid");
        helper.assertTrue(settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                && !settlement.raidLifecycle.mayGrantReward(),
            "completion should consume its one reward eligibility marker");
        helper.assertTrue(settlement.blessingState.earned() == 1
                && settlement.blessingState.spent() == 0
                && settlement.blessingState.offerSerial() == 1,
            "an intact held first raid must grant exactly one shared offer");
        helper.assertTrue(settlement.raidLog.size() == 1
                && settlement.raidLog.get(0).held()
                && settlement.pendingRaid == null,
            "aftermath must log once and clear the runtime mirror");

        int revision = settlement.blessingState.revision();
        helper.assertTrue(!RaidDirector.resolveIfOver(helper.getLevel(), settlement)
                && settlement.blessingState.earned() == 1
                && settlement.blessingState.revision() == revision
                && settlement.raidLog.size() == 1,
            "a duplicate resolution call must not mint or log twice");

        Settlement loaded = Settlement.readNbt(settlement.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(loaded.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                && !loaded.raidLifecycle.mayGrantReward()
                && loaded.blessingState.earned() == 1
                && loaded.blessingState.offerSerial() == 1
                && loaded.raidLog.size() == 1,
            "reload must preserve exactly one offer and one aftermath record");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "first_raid_v0_active_bridge_closes_without_blessing")
    public void v0ActiveBridgeClosesWithoutBlessing(GameTestHelper helper) {
        Settlement settlement = registeredSettlement(helper, "Gamlevik",
            new BlockPos(8, 1, 8));
        RaidCaptain captain = RaidDirector.pickCaptain(settlement,
            helper.getLevel().getRandom());
        RaidPlan legacy = new RaidPlan(captain.id(), RaidObjective.KORN,
            0.0F, 3L);
        settlement.pendingRaid = legacy;
        settlement.raidLifecycle = RaidLifecycle.migrateV0(legacy, false, false);

        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), settlement),
            "an empty legacy pending band should still close through its old runtime");
        helper.assertTrue(settlement.raidLifecycle.firstState()
                == FirstRaidState.COMPLETED
                && settlement.raidLifecycle.integrityLost()
                && !settlement.raidLifecycle.mayGrantReward()
                && settlement.blessingState.earned() == 0,
            "the v0 bridge must complete but can never mint a Blessing");
        helper.succeed();
    }
}

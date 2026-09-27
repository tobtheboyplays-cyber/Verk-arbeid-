package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RecurringRaidRun;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** DataVersion 3 recurring-raid authority and exactly-once reward contracts. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RecurringRaidGameTests {

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

    private static RaidLifecycle completedFirstLifecycle(GameTestHelper helper) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        UUID participant = UUID.randomUUID();
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
                && lifecycle.queueFirstPlan(first)
                && lifecycle.beginFirstRaid(first)
                && lifecycle.recordParticipant(participant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(participant)
                && lifecycle.completeFirstRaid(false),
            "the recurring fixture requires one cleanly completed first raid");
        helper.assertTrue(lifecycle.firstState() == FirstRaidState.COMPLETED
                && !lifecycle.integrityLost() && !lifecycle.mayGrantReward(),
            "the completed first fixture must carry no reward token");
        return lifecycle;
    }

    private static Settlement registeredCompletedSettlement(GameTestHelper helper,
                                                              String name,
                                                              BlockPos centerRel) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(centerRel));
        settlement.radius = 10;
        settlement.raidLifecycle = completedFirstLifecycle(helper);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static RaidPlan queuePlan(GameTestHelper helper, Settlement settlement,
                                      RaidObjective objective, long night) {
        RaidCaptain captain = RaidDirector.pickCaptain(settlement,
            helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), objective, 0.0F, night);
        helper.assertTrue(settlement.recurringRaidRun.queue(plan),
            "a completed settlement should allocate one recurring serial");
        return plan;
    }

    private static RaiderEntity assignedRaider(GameTestHelper helper,
                                                Settlement settlement,
                                                RaidPlan plan, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(plan.captainId(), settlement.id, plan.objective(),
            1.0F, false);
        return raider;
    }

    /** Uses vanilla login registration without GameTestHelper's spectator override. */
    private static ServerPlayer registeredModeAwarePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(UUID.randomUUID(), "raid-presence"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(
            net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
    private static void forget(GameTestHelper helper, Settlement settlement) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(settlement.id);
        data.setDirty();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "recurring_raid_zero_spawn_retries_exact_serial")
    public void zeroSpawnRetriesExactQueuedPlanAndSerial(GameTestHelper helper) {
        // No floor exists within the bounded vertical search at this height.
        Settlement settlement = registeredCompletedSettlement(helper, "Skyvik",
            new BlockPos(8, 300, 8));
        RaidPlan plan = queuePlan(helper, settlement, RaidObjective.BLOD, 12L);
        long serial = settlement.recurringRaidRun.activeSerial();

        List<RaiderEntity> first = RaidDirector.startQueuedRecurringRaid(
            helper.getLevel(), settlement);
        List<RaiderEntity> retry = RaidDirector.startQueuedRecurringRaid(
            helper.getLevel(), settlement);
        helper.assertTrue(first.isEmpty() && retry.isEmpty(),
            "terrain with no footing must accept no participant");
        helper.assertTrue(settlement.recurringRaidRun.isQueued()
                && settlement.recurringRaidRun.activeSerial() == serial
                && settlement.recurringRaidRun.lastIssuedSerial() == serial
                && settlement.recurringRaidRun.lastResolvedSerial() == serial - 1L
                && settlement.recurringRaidRun.plan().orElseThrow().equals(plan)
                && settlement.pendingRaid == null,
            "zero spawn must retain the exact plan/serial without activating PendingRaid");

        RecurringRaidRun reloaded = RecurringRaidRun.readNbt(
            settlement.recurringRaidRun.writeNbt());
        helper.assertTrue(reloaded.isQueued() && reloaded.activeSerial() == serial
                && reloaded.plan().orElseThrow().equals(plan),
            "a queued retry must survive reload without rerolling");
        forget(helper, settlement);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 250,
        batch = "recurring_raid_spawn_seal_reward_once")
    public void spawnedBandSealsAndHeldRewardIsExactlyOnce(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement settlement = registeredCompletedSettlement(helper, "Nyhavn",
            new BlockPos(8, 1, 8));
        helper.setBlock(new BlockPos(8, 1, 8), com.hearthstead.registry.ModBlocks.HEARTH.get());
        var hearth = (com.hearthstead.block.HearthBlockEntity)
            helper.getLevel().getBlockEntity(settlement.center);
        hearth.bindSettlement(settlement.id);
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            hearth.getInventory().setStackInSlot(slot,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        }
        RaidPlan plan = queuePlan(helper, settlement, RaidObjective.KORN, 13L);

        List<RaiderEntity> band = RaidDirector.startQueuedRecurringRaid(
            helper.getLevel(), settlement);
        helper.assertTrue(!band.isEmpty() && band.size() <= RecurringRaidRun.MAX_PARTICIPANTS,
            "at least one and at most nine accepted entities may activate a run");
        helper.assertTrue(settlement.recurringRaidRun.isActive()
                && settlement.recurringRaidRun.participantsSealed()
                && settlement.recurringRaidRun.participants().size() == band.size()
                && settlement.pendingRaid != null
                && settlement.pendingRaid.equals(plan),
            "activation must atomically seal exactly the accepted UUID set");
        for (RaiderEntity raider : band) {
            helper.assertTrue(settlement.recurringRaidRun.participants()
                    .contains(raider.getUUID()),
                "every accepted entity must be in the sealed capture");
        }

        RaiderEntity first = band.get(0);
        helper.assertTrue(first.hurt(helper.getLevel().damageSources().genericKill(),
                first.getMaxHealth() + 100.0F) && !first.isAlive(),
            "the first recurring participant must use the real death path");
        for (int i = 1; i < band.size(); i++) {
            band.get(i).discard();
        }
        helper.assertTrue(settlement.recurringRaidRun.allParticipantsTerminal(),
            "death and explicit discard must account for every sealed UUID");
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), settlement),
            "an intact all-terminal held run should resolve");
        helper.assertTrue(settlement.recurringRaidRun.isEmpty()
                && settlement.recurringRaidRun.lastIssuedSerial() == 1L
                && settlement.recurringRaidRun.lastResolvedSerial() == 1L
                && settlement.recurringRaidRun.lastRewardProcessedSerial() == 1L
                && settlement.blessingState.earned() == 1
                && settlement.raidLog.size() == 1
                && settlement.pendingRaid == null,
            "one held serial must create one shared offer and one aftermath entry");

        helper.assertTrue(settlement.raidCoinRewards.pending() == 4,
            "full Hearth must retain the four recurring victory coins as saved debt");
        int revision = settlement.blessingState.revision();
        helper.assertTrue(!RaidDirector.resolveIfOver(helper.getLevel(), settlement)
                && settlement.blessingState.earned() == 1
                && settlement.blessingState.revision() == revision
                && settlement.raidLog.size() == 1,
            "duplicate resolution must not mint or log twice");

        Settlement loaded = Settlement.readNbt(settlement.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(loaded.recurringRaidRun.isEmpty()
                && loaded.recurringRaidRun.lastRewardProcessedSerial() == 1L
                && loaded.blessingState.earned() == 1
                && loaded.raidLog.size() == 1,
            "reload must preserve the consumed serial and the single shared offer");

        helper.assertTrue(loaded.raidCoinRewards.pending() == 4,
            "clean reload must preserve unpaid physical coins");
        loaded.raidCoinRewards.deliver(helper.getLevel(), loaded);
        helper.assertTrue(loaded.raidCoinRewards.pending() == 4,
            "retry against full storage must not lose coins");
        hearth.getInventory().setStackInSlot(0,
            new net.minecraft.world.item.ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), 62));
        loaded.raidCoinRewards.deliver(helper.getLevel(), loaded);
        helper.assertTrue(loaded.raidCoinRewards.pending() == 2
                && hearth.getInventory().getStackInSlot(0).getCount() == 64,
            "partial capacity must accept only two coins and retain the remaining two");
        hearth.getInventory().setStackInSlot(1, net.minecraft.world.item.ItemStack.EMPTY);
        loaded.raidCoinRewards.deliver(helper.getLevel(), loaded);
        loaded.raidCoinRewards.deliver(helper.getLevel(), loaded);
        helper.assertTrue(loaded.raidCoinRewards.pending() == 0
                && hearth.getInventory().getStackInSlot(1).is(com.hearthstead.registry.ModItems.GOLD_COIN.get())
                && hearth.getInventory().getStackInSlot(1).getCount() == 2,
            "capacity recovery must materialize remaining debt exactly once");
        loaded = Settlement.readNbt(loaded.writeNbt(), SettlementSavedData.CURRENT_DATA_VERSION);
        loaded.raidCoinRewards.awardRecurring(1L);
        loaded.raidCoinRewards.deliver(helper.getLevel(), loaded);
        helper.assertTrue(loaded.raidCoinRewards.pending() == 0
                && hearth.getInventory().getStackInSlot(1).getCount() == 2,
            "reloaded consumed serial must not mint another physical reward");

        // A completed run may allocate the next monotonic serial. Resolve it
        // as lost to prove every outcome consumes its reward watermark.
        RaidCaptain lostCaptain = RaidDirector.pickCaptain(loaded,
            helper.getLevel().getRandom());
        RaidPlan lostPlan = new RaidPlan(lostCaptain.id(), RaidObjective.KORN,
            45.0F, 14L);
        UUID lostParticipant = UUID.randomUUID();
        helper.assertTrue(loaded.recurringRaidRun.queue(lostPlan)
                && loaded.recurringRaidRun.activeSerial() == 2L
                && loaded.recurringRaidRun.sealAndActivate(lostPlan,
                    List.of(lostParticipant))
                && loaded.recurringRaidRun.recordTerminalParticipant(lostParticipant),
            "the next recurring raid must advance to serial two and seal normally");
        loaded.pendingRaid = lostPlan;
        loaded.raidLootEscaped = true;
        int coinsBeforeLostRaid = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            var stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())) coinsBeforeLostRaid += stack.getCount();
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(loaded.id, loaded);
        data.setDirty();
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), loaded)
                && loaded.blessingState.earned() == 1
                && loaded.raidCoinRewards.pending() == 0
                && loaded.recurringRaidRun.lastResolvedSerial() == 2L
                && loaded.recurringRaidRun.lastRewardProcessedSerial() == 2L,
            "a lost future run must close without reusing or granting its serial");
        int coinsAfterLostRaid = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            var stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())) coinsAfterLostRaid += stack.getCount();
        }
        helper.assertTrue(coinsBeforeLostRaid == 66 && coinsAfterLostRaid == coinsBeforeLostRaid,
            "lost run must not mint even immediately deliverable physical coins");
        forget(helper, loaded);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 220,
        batch = "recurring_raid_unload_and_unknown_uuid_fail_closed")
    public void unloadIsNotTerminalAndUnknownUuidDisarmsReward(GameTestHelper helper) {
        buildArena(helper, 10);
        Settlement settlement = registeredCompletedSettlement(helper, "Lastheim",
            new BlockPos(5, 1, 5));
        RaidPlan plan = queuePlan(helper, settlement, RaidObjective.KORN, 20L);
        RaiderEntity unloaded = assignedRaider(helper, settlement, plan,
            new BlockPos(3, 1, 5));
        RaiderEntity discarded = assignedRaider(helper, settlement, plan,
            new BlockPos(7, 1, 5));
        helper.assertTrue(settlement.recurringRaidRun.sealAndActivate(plan,
                List.of(unloaded.getUUID(), discarded.getUUID())),
            "two unique actual UUIDs should seal the recurring ledger");
        settlement.pendingRaid = plan;

        unloaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        discarded.discard();
        helper.assertTrue(settlement.recurringRaidRun.terminalParticipants().size() == 1
                && settlement.recurringRaidRun.terminalParticipants()
                    .contains(discarded.getUUID())
                && !settlement.recurringRaidRun.terminalParticipants()
                    .contains(unloaded.getUUID()),
            "chunk unload must not become terminal; explicit discard must");
        helper.assertTrue(!RaidDirector.resolveIfOver(helper.getLevel(), settlement)
                && settlement.recurringRaidRun.isActive()
                && settlement.pendingRaid != null,
            "an empty loaded AABB cannot complete a non-terminal sealed run");

        RecurringRaidRun partialReload = RecurringRaidRun.readNbt(
            settlement.recurringRaidRun.writeNbt());
        helper.assertTrue(partialReload.isActive()
                && !partialReload.allParticipantsTerminal()
                && partialReload.terminalParticipants().size() == 1,
            "partial terminal evidence must remain active across reload");

        helper.assertTrue(!settlement.recurringRaidRun
                .recordTerminalParticipant(UUID.randomUUID())
                && settlement.recurringRaidRun.integrityLost(),
            "an unknown terminal UUID must permanently disarm this serial");
        helper.assertTrue(settlement.recurringRaidRun
                .recordTerminalParticipant(unloaded.getUUID())
                && settlement.recurringRaidRun.allParticipantsTerminal(),
            "later definitive evidence for the known unloaded UUID may close it");
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), settlement)
                && settlement.recurringRaidRun.isEmpty()
                && settlement.recurringRaidRun.lastRewardProcessedSerial() == 1L
                && settlement.blessingState.earned() == 0,
            "an integrity-lost serial may complete but can never grant an offer");
        forget(helper, settlement);
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 150,
        batch = "recurring_raid_v2_bridge_migration")
    public void onlyV2CompletedPendingBecomesNoRewardBridge(GameTestHelper helper) {
        Settlement source = registeredCompletedSettlement(helper, "Gamlebru",
            new BlockPos(2, 1, 2));
        RaidCaptain captain = RaidDirector.pickCaptain(source,
            helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 31L);

        Settlement noPending = Settlement.readNbt(source.writeNbt(), 2);
        helper.assertTrue(noPending.recurringRaidRun.isEmpty(),
            "v2 with completed first raid and no PendingRaid must migrate empty");

        source.pendingRaid = plan;
        Settlement bridge = Settlement.readNbt(source.writeNbt(), 2);
        helper.assertTrue(bridge.recurringRaidRun.isLegacyBridgeActive()
                && bridge.recurringRaidRun.plan().orElseThrow().equals(plan),
            "only a v2 completed-first PendingRaid may become the explicit bridge");
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(source.id);
        data.settlements.put(bridge.id, bridge);
        data.setDirty();
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), bridge)
                && bridge.recurringRaidRun.isEmpty()
                && bridge.blessingState.earned() == 0
                && bridge.raidLog.size() == 1,
            "the explicit v2 bridge may AABB-close once but can never reward");
        helper.assertTrue(!RaidDirector.resolveIfOver(helper.getLevel(), bridge)
                && bridge.raidLog.size() == 1,
            "the migrated bridge must not close or log a second time");
        forget(helper, bridge);
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 150,
        batch = "recurring_raid_v3_strict_blocked_roundtrip")
    public void missingMalformedAndBlockedV3StateStayFailClosed(GameTestHelper helper) {
        Settlement source = registeredCompletedSettlement(helper, "Stengtbru",
            new BlockPos(2, 1, 2));
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD,
            -45.0F, 40L);
        UUID participant = UUID.randomUUID();
        helper.assertTrue(source.recurringRaidRun.queue(plan)
                && source.recurringRaidRun.sealAndActivate(plan, List.of(participant)),
            "the malformed-save fixture must begin as a valid active run");
        source.pendingRaid = plan;

        CompoundTag missingTag = source.writeNbt();
        missingTag.remove("RecurringRaidRun");
        Settlement missing = Settlement.readNbt(missingTag, 3);
        helper.assertTrue(missing.recurringRaidRun.isBlocked()
                && !RaidDirector.resolveIfOver(helper.getLevel(), missing)
                && missing.blessingState.earned() == 0,
            "v3 may never reconstruct missing authority from PendingRaid/AABB");

        CompoundTag malformedTag = source.writeNbt();
        CompoundTag malformedRun = malformedTag.getCompound("RecurringRaidRun");
        malformedRun.remove("TerminalParticipants");
        malformedTag.put("RecurringRaidRun", malformedRun);
        Settlement malformed = Settlement.readNbt(malformedTag, 3);
        helper.assertTrue(malformed.recurringRaidRun.isBlocked()
                && !RaidDirector.resolveIfOver(helper.getLevel(), malformed)
                && malformed.blessingState.earned() == 0,
            "malformed v3 active state must block instead of using its mirror");

        long issued = source.recurringRaidRun.activeSerial();
        source.recurringRaidRun.block();
        RecurringRaidRun blockedReload = RecurringRaidRun.readNbt(
            source.recurringRaidRun.writeNbt());
        helper.assertTrue(blockedReload.isBlocked()
                && blockedReload.lastIssuedSerial() == issued
                && blockedReload.lastResolvedSerial() == issued
                && blockedReload.lastRewardProcessedSerial() == issued,
            "blocking an active run must consume its serial and round-trip fail-closed");
        helper.assertTrue(!blockedReload.queue(plan),
            "a blocked ledger can never reopen or replay its consumed serial");

        RecurringRaidRun duplicateCapture = new RecurringRaidRun();
        UUID duplicate = UUID.randomUUID();
        helper.assertTrue(duplicateCapture.queue(plan)
                && !duplicateCapture.sealAndActivate(plan,
                    List.of(duplicate, duplicate))
                && duplicateCapture.isBlocked(),
            "a non-empty duplicate UUID capture must fail closed");
        forget(helper, source);
        helper.succeed();
    }
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "recurring_raid_presence_persisted_warning")
    public void recurringWarningNeedsAnAliveNearbyPlayerAndKeepsItsExactPlan(
            GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement settlement = registeredCompletedSettlement(helper, "Nattvik",
            new BlockPos(8, 1, 8));
        helper.assertTrue(RaidDirector.firstResolutionReceiptReady(settlement),
            "the completed fixture must use the persisted skipped-Journey receipt path");
        net.minecraft.world.level.storage.ServerLevelData clock =
            (net.minecraft.world.level.storage.ServerLevelData) helper.getLevel().getLevelData();
        long originalGameTime = helper.getLevel().getGameTime();
        long originalDayTime = helper.getLevel().getDayTime();
        ServerPlayer spectator = null;
        ServerPlayer dead = null;
        ServerPlayer player = null;
        try {
            // A real post-resolution recovery is closed through its exact server-tick boundary.
            settlement.raidLifecycle.recordRecurringRecovery(1_000L, true);
            setRecurringClock(helper, clock, 24_999L, 13_000L);
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.raidLifecycle.recurringWarnedPlan().isEmpty()
                    && settlement.raidPressure.lastRolledNight() == Long.MIN_VALUE,
                "recovery must prevent a warning or pressure roll before its persisted boundary");

            setRecurringClock(helper, clock, 25_000L, 13_000L);
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.raidLifecycle.recurringWarnedPlan().isEmpty()
                    && settlement.raidPressure.lastRolledNight() == Long.MIN_VALUE,
                "an absent player must not create an offline warning backlog");

            spectator = registeredModeAwarePlayer(helper);
            spectator.setPos(settlement.center.getX() + 0.5D, settlement.center.getY(),
                settlement.center.getZ() + 0.5D);
            spectator.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            helper.assertTrue(helper.getLevel().players().contains(spectator)
                    && spectator.isSpectator(),
                "fixture spectator must be a real registered level player");
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.raidLifecycle.recurringWarnedPlan().isEmpty()
                    && settlement.raidPressure.lastRolledNight() == Long.MIN_VALUE,
                "a nearby spectator must not advance recurring scheduling");

            dead = helper.makeMockServerPlayerInLevel();
            dead.setPos(settlement.center.getX() + 1.5D, settlement.center.getY(),
                settlement.center.getZ() + 0.5D);
            dead.setHealth(0.0F);
            helper.assertTrue(helper.getLevel().players().contains(dead) && !dead.isAlive(),
                "fixture dead player must remain registered while failing the live-player predicate");
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.raidLifecycle.recurringWarnedPlan().isEmpty()
                    && settlement.raidPressure.lastRolledNight() == Long.MIN_VALUE,
                "a nearby dead player must not advance recurring scheduling");

            player = helper.makeMockServerPlayerInLevel();
            player.setPos(settlement.center.getX() + 0.5D, settlement.center.getY(),
                settlement.center.getZ() + 1.5D);
            helper.assertTrue(helper.getLevel().players().contains(player) && player.isAlive()
                    && !player.isSpectator(),
                "fixture eligible player must be alive, non-spectating and registered in this level");

            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.raidLifecycle.recurringQuietEligibleRolls() == 0
                    && settlement.raidLifecycle.recurringWarnedPlan().isEmpty()
                    && settlement.raidPressure.lastRolledNight() == Long.MIN_VALUE,
                "a nearby live player cannot turn an under-worth hamlet into quiet-roll debt");

            // The real director's pressure roll only considers a settlement worth raiding.
            for (int index = 0; index < 5; index++) {
                settlement.putRecord(UUID.randomUUID(), "Nattvik " + index,
                    com.hearthstead.entity.Profession.NONE);
            }
            // This is a valid persisted state after two earlier eligible quiet rolls.
            CompoundTag persistedQuiet = settlement.raidLifecycle.writeNbt();
            persistedQuiet.putInt("RecurringQuietEligibleRolls", 2);
            settlement.raidLifecycle = RaidLifecycle.readNbt(persistedQuiet);
            helper.assertTrue(settlement.raidLifecycle.recurringQuietEligibleRolls() == 2
                    && settlement.raidLifecycle.recurringWarnedPlan().isEmpty(),
                "fixture must reload the two prior eligible quiet rolls without inventing a plan");
            setRecurringClock(helper, clock, 73_000L, 85_000L);
            RaidDirector.tick(helper.getLevel(), settlement);
            RaidPlan warned = settlement.raidLifecycle.recurringWarnedPlan().orElseThrow();
            helper.assertTrue(warned.night() == 4L
                    && settlement.raidLifecycle.recurringWarningNight() == 3L
                    && settlement.raidLifecycle.recurringWarningGameTime() == 73_000L
                    && settlement.raidLifecycle.recurringQuietEligibleRolls() == 0,
                "the eligible third director tick must persist one exact next-night warning");

            Settlement reloaded = Settlement.readNbt(settlement.writeNbt(),
                SettlementSavedData.CURRENT_DATA_VERSION);
            SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
            data.settlements.put(reloaded.id, reloaded);
            data.setDirty();
            helper.assertTrue(reloaded.raidLifecycle.recurringWarnedPlan().orElseThrow().equals(warned)
                    && reloaded.raidLifecycle.recurringWarningGameTime() == 73_000L,
                "the exact warned plan and its server-time lead must survive settlement NBT reload");

            setRecurringClock(helper, clock, 78_999L, 109_000L);
            RaidDirector.tick(helper.getLevel(), reloaded);
            helper.assertTrue(reloaded.raidLifecycle.recurringWarnedPlan().orElseThrow().equals(warned)
                    && reloaded.recurringRaidRun.isEmpty(),
                "the director must retain the warning until the full six-thousand-tick lead");
            setRecurringClock(helper, clock, 79_000L, 109_000L);
            RaidDirector.tick(helper.getLevel(), reloaded);
            helper.assertTrue(reloaded.recurringRaidRun.plan().orElseThrow().equals(warned)
                    && (reloaded.recurringRaidRun.isQueued() || reloaded.recurringRaidRun.isActive()),
                "the exact reloaded warning hands off only to its recurring-run authority at the later night");
        } finally {
            if (spectator != null) spectator.discard();
            if (dead != null) dead.discard();
            if (player != null) player.discard();
            clock.setGameTime(originalGameTime);
            helper.getLevel().setDayTime(originalDayTime);
            forget(helper, settlement);
        }
        helper.succeed();
    }

    private static void setRecurringClock(GameTestHelper helper,
                                          net.minecraft.world.level.storage.ServerLevelData clock,
                                          long gameTime, long dayTime) {
        clock.setGameTime(gameTime);
        helper.getLevel().setDayTime(dayTime);
    }
}

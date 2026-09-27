package com.hearthstead.settlement.journey;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.SimpleContainerData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

/** QA-JOURNEY-01: a first meeting remains immutable before its prerequisite. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class JourneyGuildmasterReplayGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40, batch = "journey_guildmaster_replay")
    public void earlyMeetingReplaysAcrossTicksReloadAndCoopThenClosesNormally(GameTestHelper helper) {
        Settlement settlement = register(helper);
        ServerPlayer firstPlayer = helper.makeMockServerPlayerInLevel();
        ServerPlayer secondPlayer = helper.makeMockServerPlayerInLevel();
        UUID guildmaster = UUID.randomUUID();
        try {
            // Controlled founding receipt isolates the meeting adapter and real
            // Journey-open prerequisite; this is not a native survival fixture.
            settlement.journeyState.record(new JourneyEvidence(
                JourneyIds.FJ_010_FOUND_HEARTH, JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED,
                UUID.randomUUID(), helper.getLevel().getGameTime(), settlement.id,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), JourneySource.SURVIVAL, JourneyOutcome.NONE),
                JourneyDefinition.CURRENT);
            helper.assertTrue(JourneyServerHooks.noteGuildmasterMet(firstPlayer, settlement, guildmaster),
                "first shop meeting before Journey must record evidence");
            JourneyEvidence first = settlement.journeyState.evidence().stream()
                .filter(item -> item.stepId().equals(JourneyIds.FJ_030_APPOINT_MAYOR))
                .findFirst().orElseThrow();
            helper.assertTrue(!settlement.journeyState.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY)
                    && !settlement.journeyState.isCompleted(JourneyIds.FJ_030_APPOINT_MAYOR),
                "early meeting must wait for the Journey-open prerequisite");
            CompoundTag original = settlement.journeyState.writeNbt();
            settlement.journeyState = JourneyState.readNbt(original, settlement.id);
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(helper.getLevel().getGameTime() > first.gameTime(),
                        "repeat must cross actual server ticks after codec reload");
                    JourneyServerHooks.noteGuildmasterMet(firstPlayer, settlement, guildmaster);
                    helper.assertTrue(original.equals(settlement.journeyState.writeNbt()),
                        "same player revisiting shop must preserve the complete first receipt");
                    helper.assertTrue(!firstPlayer.getUUID().equals(secondPlayer.getUUID()),
                        "coop observer must have a distinct player identity");
                    JourneyServerHooks.noteGuildmasterMet(secondPlayer, settlement, guildmaster);
                    helper.assertTrue(original.equals(settlement.journeyState.writeNbt())
                            && settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE,
                        "coop revisit must preserve first actor/time and remain active");

                    helper.setBlock(new BlockPos(2, 1, 2), ModBlocks.HEARTH.get());
                    HearthBlockEntity banner = (HearthBlockEntity) helper.getLevel()
                        .getBlockEntity(settlement.center);
                    banner.bindSettlement(settlement.id);
                    secondPlayer.setPos(settlement.center.getX() + 0.5D,
                        settlement.center.getY(), settlement.center.getZ() + 0.5D);
                    secondPlayer.containerMenu = new HearthMenu(1, secondPlayer.getInventory(),
                        banner, new SimpleContainerData(HearthMenu.DATA_COUNT), settlement.name);
                    helper.assertTrue(JourneyServerHooks.noteJourneyViewOpened(secondPlayer, settlement)
                            && settlement.journeyState.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY)
                            && settlement.journeyState.isCompleted(JourneyIds.FJ_030_APPOINT_MAYOR)
                            && settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE,
                        "real Journey-open hook must close prerequisites using the earlier meeting");
                    helper.assertTrue(settlement.journeyState.evidence().contains(first)
                            && settlement.journeyState.evidence().stream().filter(item ->
                                item.stepId().equals(JourneyIds.FJ_030_APPOINT_MAYOR)).count() == 1,
                        "completion must retain exactly one unchanged first-meeting receipt");

                    // The adapter's canonical first observer must not weaken the
                    // ledger: a directly changed actor under this key is a conflict.
                    JourneyState conflict = JourneyState.readNbt(settlement.journeyState.writeNbt(),
                        settlement.id);
                    JourneyEvidence changed = new JourneyEvidence(first.stepId(), first.event(),
                        first.transactionId(), first.gameTime(), first.settlementId(),
                        Optional.of(secondPlayer.getUUID()), first.subjectEntityId(), first.buildingId(),
                        first.requestId(), first.stackFingerprint(), first.source(), first.outcome());
                    helper.assertTrue(conflict.record(changed, JourneyDefinition.CURRENT)
                            == JourneyApplyResult.QUARANTINED
                            && conflict.quarantineReason().equals("transaction_identity_collision"),
                        "changed same-key payload must still quarantine even after step completion");
                    helper.succeed();
                } finally {
                    cleanup(helper, settlement, firstPlayer, secondPlayer);
                }
            });
        } catch (RuntimeException | Error failure) {
            cleanup(helper, settlement, firstPlayer, secondPlayer);
            throw failure;
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "journey_guildmaster_replay")
    public void meetingAdapterStillRejectsConflictingSameKeySubject(GameTestHelper helper) {
        Settlement settlement = register(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        UUID guildmaster = UUID.randomUUID();
        try {
            UUID transaction = JourneyTransactionIds.forRevision("guildmaster_met",
                settlement.id, guildmaster, 0L);
            // A structurally valid receipt with the right key but wrong subject
            // must not be accepted merely because the key already exists.
            JourneyEvidence conflicting = new JourneyEvidence(JourneyIds.FJ_030_APPOINT_MAYOR,
                JourneyEvent.MAYOR_APPOINTED_COMMITTED, transaction,
                helper.getLevel().getGameTime(), settlement.id, Optional.of(player.getUUID()),
                Optional.of(UUID.randomUUID()), Optional.empty(), Optional.empty(), Optional.empty(),
                JourneySource.SURVIVAL, JourneyOutcome.NONE);
            helper.assertTrue(settlement.journeyState.record(conflicting, JourneyDefinition.CURRENT)
                    == JourneyApplyResult.APPLIED,
                "conflict fixture must hold pending evidence before prerequisite completion");
            JourneyServerHooks.noteGuildmasterMet(player, settlement, guildmaster);
            helper.assertTrue(settlement.journeyState.mode() == JourneyPresentationMode.QUARANTINED
                    && settlement.journeyState.quarantineReason().equals("transaction_identity_collision"),
                "public adapter must pass semantic mismatches to strict collision rejection");
            helper.succeed();
        } finally {
            cleanup(helper, settlement, player);
        }
    }

    private static Settlement register(GameTestHelper helper) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Guildmaster Replay",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        settlement.journeyState = JourneyState.fresh(settlement.id);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static void cleanup(GameTestHelper helper, Settlement settlement, ServerPlayer... players) {
        for (ServerPlayer player : players) {
            helper.getLevel().getServer().getPlayerList().remove(player);
            player.discard();
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(settlement.id, settlement);
        data.setDirty();
    }
}

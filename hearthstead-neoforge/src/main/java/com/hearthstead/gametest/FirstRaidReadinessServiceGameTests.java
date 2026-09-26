package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Black-box proof that assessment observes authority without changing it. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FirstRaidReadinessServiceGameTests {
    private static final BlockPos HEARTH = new BlockPos(8, 1, 8);

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "first_raid_readiness_service_is_pure")
    public void domainAssessmentReturnsAllKnownBlockersWithoutMutation(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        SettlementSavedData root = fixture.root();
        WorkerProvenanceSavedData provenance =
            WorkerProvenanceSavedData.existing(helper.getLevel());
        RequestLedgerSavedData requests =
            RequestLedgerSavedData.existing(helper.getLevel());

        CompoundTag settlementBefore = fixture.settlement().writeNbt();
        CompoundTag hearthBefore = fixture.hearth().saveWithoutMetadata(
            helper.getLevel().registryAccess());
        CompoundTag provenanceBefore = provenance == null ? null
            : provenance.save(new CompoundTag(),
                helper.getLevel().registryAccess());
        CompoundTag requestsBefore = requests == null ? null
            : requests.save(new CompoundTag(),
                helper.getLevel().registryAccess());
        boolean provenanceDirty = provenance != null && provenance.isDirty();
        boolean requestsDirty = requests != null && requests.isDirty();
        root.setDirty(false);

        FirstRaidReadinessService.Report first =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                fixture.settlement());
        FirstRaidReadinessService.Report replay =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                fixture.settlement());

        helper.assertTrue(first.blockers().size() > 8
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.LUMBER_CAMP_INVALID)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.WAREHOUSE_INVALID)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.FARMHOUSE_INVALID)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.GUARD_UNARMED)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.WATCHTOWER_INVALID)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_INVALID)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_UNARMED)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_NO_ARROWS)
                && first.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_ORDER_INVALID)
                && first.blockedBy(FirstRaidReadinessService.Blocker
                    .SETTLER_ROSTER_INSUFFICIENT),
            "one assessment must return the complete known blocker set, not the first failure");
        helper.assertTrue(!first.blockedBy(
                FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY),
            "an explicit skipped presentation must pass only the Journey presentation rule");
        helper.assertTrue(first.equals(replay),
            "an unchanged server snapshot must produce the exact same report and revision");
        helper.assertTrue(!root.isDirty()
                && settlementBefore.equals(fixture.settlement().writeNbt())
                && hearthBefore.equals(fixture.hearth().saveWithoutMetadata(
                    helper.getLevel().registryAccess()))
                && WorkerProvenanceSavedData.existing(helper.getLevel())
                    == provenance
                && RequestLedgerSavedData.existing(helper.getLevel())
                    == requests
                && (provenance == null
                    || provenanceDirty == provenance.isDirty()
                        && provenanceBefore.equals(provenance.save(
                            new CompoundTag(),
                            helper.getLevel().registryAccess())))
                && (requests == null
                    || requestsDirty == requests.isDirty()
                        && requestsBefore.equals(requests.save(
                            new CompoundTag(),
                            helper.getLevel().registryAccess()))),
            "assessment must not dirty, create, revise, reserve, reconcile or move any authority");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "first_raid_readiness_declaration_session")
    public void declarationRequiresExactLiveSessionAndDomainRevision(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(fixture.settlement().center.getX() + 0.5D,
            fixture.settlement().center.getY() + 0.5D,
            fixture.settlement().center.getZ() + 0.5D,
            0.0F, 0.0F);
        FirstRaidReadinessService.Report domain =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                fixture.settlement());
        long now = helper.getLevel().getGameTime();
        UUID sessionId = UUID.randomUUID();
        FirstRaidReadinessService.DeclarationSession exact =
            new FirstRaidReadinessService.DeclarationSession(sessionId,
                player.getUUID(), fixture.settlement().id,
                helper.getLevel().dimension().location(),
                fixture.settlement().center, domain.domainRevision(),
                now, now + 100L, true);

        FirstRaidReadinessService.Report accepted =
            FirstRaidReadinessService.assessDeclaration(player,
                fixture.settlement(), exact);
        helper.assertTrue(!accepted.blockedBy(
                FirstRaidReadinessService.Blocker.SESSION_MISMATCH)
                && accepted.blockers().equals(domain.blockers()),
            "the exact server session and displayed domain revision must preserve domain truth");

        FirstRaidReadinessService.DeclarationSession stale =
            new FirstRaidReadinessService.DeclarationSession(sessionId,
                player.getUUID(), fixture.settlement().id,
                helper.getLevel().dimension().location(),
                fixture.settlement().center, domain.domainRevision() + 1L,
                now, now + 100L, true);
        FirstRaidReadinessService.Report rejected =
            FirstRaidReadinessService.assessDeclaration(player,
                fixture.settlement(), stale);
        helper.assertTrue(rejected.blockedBy(
                FirstRaidReadinessService.Blocker.SESSION_MISMATCH)
                && !accepted.blockedBy(
                    FirstRaidReadinessService.Blocker.SESSION_MISMATCH),
            "a stale revision must fail closed without changing the underlying domain blockers");

        helper.assertTrue(fixture.settlement().raidLifecycle
                .scheduleAfterReadiness(0L),
            "fixture: successful declaration must cross PREPARING to SCHEDULED once");
        FirstRaidReadinessService.Report bridge =
            FirstRaidReadinessService.assessScheduledCommitBridge(
                helper.getLevel(), fixture.settlement());
        FirstRaidReadinessService.Report execution =
            FirstRaidReadinessService.assessExecution(helper.getLevel(),
                fixture.settlement());
        FirstRaidReadinessService.Report declarationClosed =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                fixture.settlement());
        helper.assertTrue(!bridge.blockedBy(
                FirstRaidReadinessService.Blocker.RAID_STATE_NOT_SCHEDULED)
                && !bridge.blockedBy(
                    FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY)
                && !execution.blockedBy(
                    FirstRaidReadinessService.Blocker.RAID_STATE_NOT_SCHEDULED)
                && !execution.blockedBy(
                    FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY)
                && declarationClosed.blockedBy(
                    FirstRaidReadinessService.Blocker.RAID_STATE_NOT_PREPARING),
            "a scheduled skipped Journey must keep warning/start execution open while closing redeclaration");
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper) {
        helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        BlockPos center = helper.absolutePos(HEARTH);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(center);
        helper.assertTrue(hearth != null, "fixture: physical Hearth");

        SettlementSavedData root = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Readiness service", center);
        helper.assertTrue(settlement.raidLifecycle.prepareAtFounding(0L, 4, 2),
            "fixture: PREPARING first-raid calendar");
        root.settlements.put(settlement.id, settlement);
        hearth.bindSettlement(settlement.id);
        return new Fixture(root, settlement, hearth);
    }

    private record Fixture(SettlementSavedData root, Settlement settlement,
                           HearthBlockEntity hearth) {
    }
}

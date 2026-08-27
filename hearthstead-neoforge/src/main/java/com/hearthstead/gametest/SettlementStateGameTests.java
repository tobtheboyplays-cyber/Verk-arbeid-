package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** M1 persistence, migration and atomic shared-settlement state contracts. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SettlementStateGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_v1_roundtrip")
    public void v1RoundTripPreservesSharedState(GameTestHelper helper) {
        Settlement original = settlement("Stateholm", new BlockPos(2, 1, 2));
        original.raidProfile = RaidProfile.IRON_WINTER;
        helper.assertTrue(original.raidLifecycle.initializeAtFounding(20L, 6, 1),
            "fresh lifecycle should initialize");
        RaidPlan plan = plan(26L);
        helper.assertTrue(original.raidLifecycle.queueFirstPlan(plan),
            "first authored plan should queue once");
        helper.assertTrue(original.raidLifecycle.beginFirstRaid(plan),
            "scheduled first raid should begin");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        helper.assertTrue(original.raidLifecycle.recordParticipant(first),
            "first participant should be tracked");
        helper.assertTrue(original.raidLifecycle.recordParticipant(second),
            "second participant should be tracked");
        helper.assertTrue(original.raidLifecycle.sealParticipants(),
            "non-empty participant capture should seal");
        helper.assertTrue(original.raidLifecycle.completeFirstRaid(true),
            "held first raid should complete");

        helper.assertTrue(original.blessingState.grantOffer(), "offer one should be earned");
        helper.assertTrue(original.blessingState.grantOffer(), "offer two should be earned");
        int blessingRevision = original.blessingState.revision();
        helper.assertTrue(original.blessingState.compareAndCommit(blessingRevision, 1,
                BlessingId.WARDEN_OATH) == BlessingState.CommitResult.ACCEPTED,
            "first Blessing should commit");

        BlockPos orderPos = new BlockPos(9, 70, -4);
        helper.assertTrue(original.guardOrder.issue(GuardOrder.Mode.DEFEND_HEARTH,
            orderPos, 12_345L), "guard order should be accepted");

        SettlementSavedData output = new SettlementSavedData();
        output.settlements.put(original.id, original);
        CompoundTag root = output.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(root.getInt("DataVersion")
                == SettlementSavedData.CURRENT_DATA_VERSION,
            "root must write DataVersion=" + SettlementSavedData.CURRENT_DATA_VERSION);
        Settlement loaded = SettlementSavedData.load(root,
            helper.getLevel().registryAccess()).settlements.get(original.id);

        helper.assertTrue(loaded != null, "settlement should survive v1 root load");
        helper.assertTrue(loaded.raidProfile == RaidProfile.IRON_WINTER,
            "raid profile should round-trip");
        helper.assertTrue(loaded.raidLifecycle.firstState() == FirstRaidState.COMPLETED,
            "first lifecycle state should round-trip");
        helper.assertTrue(loaded.raidLifecycle.foundedNight() == 20L
                && loaded.raidLifecycle.firstAttackNight() == 26L
                && loaded.raidLifecycle.firstWarningNight() == 25L,
            "authored first-raid dates should round-trip");
        helper.assertTrue(loaded.raidLifecycle.activePlan().orElseThrow()
                .approachDegrees() == -90.0F,
            "valid negative wrapped approach must round-trip unchanged");
        helper.assertTrue(loaded.raidLifecycle.participants().size() == 2
                && loaded.raidLifecycle.participants().contains(first)
                && loaded.raidLifecycle.participants().contains(second),
            "bounded participant set should round-trip");
        helper.assertTrue(loaded.raidLifecycle.mayGrantReward(),
            "intact held first raid should remain reward-eligible");
        helper.assertTrue(loaded.blessingState.earned() == 2
                && loaded.blessingState.spent() == 1
                && loaded.blessingState.rank(BlessingId.WARDEN_OATH) == 1,
            "Blessing counters and ranks should round-trip");
        helper.assertTrue(loaded.guardOrder.mode() == GuardOrder.Mode.DEFEND_HEARTH
                && loaded.guardOrder.pos().orElseThrow().equals(orderPos)
                && loaded.guardOrder.untilGameTime() == 12_345L,
            "guard order should round-trip");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_v0_migration")
    public void v0MigrationNeverMintsAFreeBlessing(GameTestHelper helper) {
        Settlement pending = settlement("Legacy Active", new BlockPos(1, 1, 1));
        pending.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD,
            -90.0F, 31L); // current v0 director persisted wrapped [-180,180) angles
        pending.raidPressure.scheduleForecast(44L);
        CompoundTag pendingTag = asV0(pending.writeNbt());

        Settlement history = settlement("Legacy History", new BlockPos(3, 1, 3));
        history.raidLog.add(new RaidLogEntry(9L, "Old Captain", "blod",
            true, 0, 0, "uro"));
        CompoundTag historyTag = asV0(history.writeNbt());

        Settlement captainResult = settlement("Legacy Captain Result",
            new BlockPos(4, 1, 4));
        RaidCaptain oldCaptain = RaidCaptain.generate(RandomSource.create(77L));
        oldCaptain.recordDefeat();
        captainResult.raidCaptains.add(oldCaptain);
        CompoundTag captainResultTag = asV0(captainResult.writeNbt());

        CompoundTag root = new CompoundTag(); // deliberately no DataVersion
        ListTag list = new ListTag();
        list.add(pendingTag);
        list.add(historyTag);
        list.add(captainResultTag);
        root.put("Settlements", list);
        SettlementSavedData loaded = SettlementSavedData.load(root,
            helper.getLevel().registryAccess());

        Settlement active = loaded.settlements.get(pending.id);
        helper.assertTrue(active.raidProfile == RaidProfile.PEACEFUL,
            "v0 profile must migrate to peaceful");
        helper.assertTrue(active.pendingRaid != null,
            "legacy runtime pending raid must still load");
        helper.assertTrue(active.raidLifecycle.firstState() == FirstRaidState.ACTIVE
                && active.raidLifecycle.activePlan().isPresent(),
            "legacy PendingRaid must bridge to an ACTIVE lifecycle marker");
        helper.assertTrue(active.raidLifecycle.activePlan().orElseThrow()
                .approachDegrees() == -90.0F,
            "valid legacy wrapped angle should survive strict v1 persistence");
        helper.assertTrue(!active.raidLifecycle.rewardEligible()
                && !active.raidLifecycle.mayGrantReward(),
            "unauditable legacy active raid must never earn a free Blessing");
        helper.assertTrue(active.raidLifecycle.queuedPlan().isEmpty(),
            "legacy forecast must not become a queued authored plan");
        helper.assertTrue(active.raidLifecycle.integrityLost(),
            "legacy participant integrity should be explicitly unavailable");
        helper.assertTrue(active.blessingState.earned() == 0
                && active.guardOrder.mode() == GuardOrder.Mode.NONE,
            "missing v0 Blessing/Guard compounds must load empty");

        Settlement completed = loaded.settlements.get(history.id);
        helper.assertTrue(completed.raidLifecycle.firstState()
                == FirstRaidState.COMPLETED,
            "legacy raid history/result must migrate to completed");
        helper.assertTrue(!completed.raidLifecycle.mayGrantReward(),
            "legacy completed history must not mint a post-upgrade reward");
        Settlement captainCompleted = loaded.settlements.get(captainResult.id);
        helper.assertTrue(captainCompleted.raidLifecycle.firstState()
                == FirstRaidState.COMPLETED
                && !captainCompleted.raidLifecycle.mayGrantReward(),
            "pre-RaidLog captain win/loss must also migrate completed/ineligible");

        CompoundTag upgradedRoot = loaded.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        helper.assertTrue(upgradedRoot.getInt("DataVersion")
                == SettlementSavedData.CURRENT_DATA_VERSION,
            "migrated v0 state must rewrite with the current root version");
        SettlementSavedData reloaded = SettlementSavedData.load(upgradedRoot,
            helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.settlements.get(pending.id).raidLifecycle.firstState()
                == FirstRaidState.ACTIVE
                && reloaded.settlements.get(pending.id).raidLifecycle.activePlan()
                    .orElseThrow().approachDegrees() == -90.0F
                && !reloaded.settlements.get(pending.id).raidLifecycle.mayGrantReward(),
            "legacy active marker must survive its first strict v1 reload");
        helper.assertTrue(reloaded.settlements.get(history.id).raidLifecycle.firstState()
                == FirstRaidState.COMPLETED
                && !reloaded.settlements.get(history.id).raidLifecycle.mayGrantReward(),
            "legacy completed marker must survive its first strict v1 reload");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_unknown_ids")
    public void unknownStableIdsAreRejectedSafely(GameTestHelper helper) {
        helper.assertTrue(RaidProfile.PEACEFUL.wireId() == 0
                && RaidProfile.BALANCED.wireId() == 1
                && RaidProfile.IRON_WINTER.wireId() == 2
                && "iron_winter".equals(RaidProfile.IRON_WINTER.id()),
            "raid profile wire/string ids are a frozen contract");
        helper.assertTrue(FirstRaidState.UNINITIALIZED.wireId() == 0
                && FirstRaidState.SCHEDULED.wireId() == 1
                && FirstRaidState.ACTIVE.wireId() == 2
                && FirstRaidState.COMPLETED.wireId() == 3,
            "first raid state wire ids are a frozen contract");
        helper.assertTrue(BlessingId.WARDEN_OATH.wireId() == 0
                && BlessingId.HEARTHWARD.wireId() == 1
                && BlessingId.THORNED_ROADS.wireId() == 2
                && "thorned_roads".equals(BlessingId.THORNED_ROADS.id()),
            "Blessing wire/string ids are a frozen contract");
        helper.assertTrue(GuardOrder.Mode.NONE.wireId() == 0
                && GuardOrder.Mode.RALLY_HERE.wireId() == 1
                && GuardOrder.Mode.DEFEND_HEARTH.wireId() == 2
                && "defend_hearth".equals(GuardOrder.Mode.DEFEND_HEARTH.id()),
            "guard order wire/string ids are a frozen contract");
        helper.assertTrue(RaidProfile.tryFromWireId(99).isEmpty()
                && RaidProfile.tryFromId("future_profile").isEmpty(),
            "unknown profile ids must not decode");
        helper.assertTrue(FirstRaidState.tryFromWireId(-4).isEmpty()
                && BlessingId.tryFromId("future_blessing").isEmpty()
                && GuardOrder.Mode.tryFromWireId(44).isEmpty(),
            "unknown lifecycle, Blessing and order ids must not decode");

        Settlement original = settlement("Unknownholm", BlockPos.ZERO);
        CompoundTag tag = original.writeNbt();
        tag.putInt("RaidProfileWireId", 99);
        tag.putString("RaidProfile", RaidProfile.BALANCED.id());

        CompoundTag lifecycle = new CompoundTag();
        lifecycle.putInt("FirstStateWireId", 99);
        lifecycle.putString("FirstState", FirstRaidState.SCHEDULED.id());
        lifecycle.putBoolean("RewardEligible", true);
        tag.put("RaidLifecycle", lifecycle);

        CompoundTag guard = new CompoundTag();
        guard.putInt("ModeWireId", 99);
        guard.putString("Mode", GuardOrder.Mode.RALLY_HERE.id());
        guard.put("Pos", net.minecraft.nbt.NbtUtils.writeBlockPos(new BlockPos(4, 4, 4)));
        guard.putLong("Until", 999L);
        tag.put("GuardOrder", guard);

        CompoundTag blessing = new CompoundTag();
        blessing.putInt("Earned", 1);
        ListTag ranks = new ListTag();
        CompoundTag unknownRank = new CompoundTag();
        unknownRank.putInt("WireId", 99);
        unknownRank.putString("Id", BlessingId.HEARTHWARD.id());
        unknownRank.putInt("Rank", 1);
        ranks.add(unknownRank);
        blessing.put("Ranks", ranks);
        tag.put("BlessingState", blessing);

        Settlement loaded = Settlement.readNbt(tag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(loaded.raidProfile == RaidProfile.PEACEFUL,
            "unknown/mismatched profile must fall back safely to peaceful");
        helper.assertTrue(loaded.raidLifecycle.firstState()
                == FirstRaidState.UNINITIALIZED
                && loaded.raidLifecycle.integrityLost()
                && !loaded.raidLifecycle.rewardEligible(),
            "unknown lifecycle state must reset safely and disarm reward");
        helper.assertTrue(loaded.guardOrder.mode() == GuardOrder.Mode.NONE,
            "unknown guard mode must load as no order");
        helper.assertTrue(loaded.blessingState.rank(BlessingId.HEARTHWARD) == 0
                && loaded.blessingState.spent() == 1
                && loaded.blessingState.offerSerial() == 0,
            "unknown Blessing rank must grant no power or resurrect a free offer");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_data_version_fail_closed")
    public void malformedOrFutureDataVersionsAreRejected(GameTestHelper helper) {
        assertVersionRejected(helper, new CompoundTag(),
            "missing Settlements root");

        CompoundTag negative = emptyRoot();
        negative.putInt("DataVersion", -1);
        assertVersionRejected(helper, negative, "negative DataVersion");

        CompoundTag wrongType = emptyRoot();
        wrongType.putString("DataVersion", "1");
        assertVersionRejected(helper, wrongType, "wrong DataVersion tag type");

        CompoundTag future = emptyRoot();
        future.putInt("DataVersion", SettlementSavedData.CURRENT_DATA_VERSION + 1);
        assertVersionRejected(helper, future, "future DataVersion");

        CompoundTag wrongSettlements = new CompoundTag();
        wrongSettlements.putInt("DataVersion", SettlementSavedData.CURRENT_DATA_VERSION);
        wrongSettlements.putString("Settlements", "not_a_list");
        assertVersionRejected(helper, wrongSettlements, "wrong Settlements tag type");

        CompoundTag wrongSettlementEntry = emptyRoot();
        wrongSettlementEntry.putInt("DataVersion",
            SettlementSavedData.CURRENT_DATA_VERSION);
        ListTag wrongEntries = new ListTag();
        wrongEntries.add(net.minecraft.nbt.StringTag.valueOf("not_a_compound"));
        wrongSettlementEntry.put("Settlements", wrongEntries);
        assertVersionRejected(helper, wrongSettlementEntry,
            "non-compound Settlements entry");

        CompoundTag missingIdRoot = emptyRoot();
        missingIdRoot.putInt("DataVersion",
            SettlementSavedData.CURRENT_DATA_VERSION);
        ListTag missingIdEntries = new ListTag();
        missingIdEntries.add(new CompoundTag());
        missingIdRoot.put("Settlements", missingIdEntries);
        assertVersionRejected(helper, missingIdRoot, "missing settlement id");

        Settlement duplicate = settlement("Duplicate", BlockPos.ZERO);
        CompoundTag duplicateRoot = new CompoundTag();
        duplicateRoot.putInt("DataVersion",
            SettlementSavedData.CURRENT_DATA_VERSION);
        ListTag duplicateEntries = new ListTag();
        duplicateEntries.add(duplicate.writeNbt());
        duplicateEntries.add(duplicate.writeNbt());
        duplicateRoot.put("Settlements", duplicateEntries);
        assertVersionRejected(helper, duplicateRoot, "duplicate settlement id");

        Settlement versioned = settlement("Version Marker", BlockPos.ZERO);
        SettlementSavedData versionedData = new SettlementSavedData();
        versionedData.settlements.put(versioned.id, versioned);
        CompoundTag missingVersion = versionedData.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        missingVersion.remove("DataVersion");
        assertVersionRejected(helper, missingVersion,
            "versioned settlement state without DataVersion");

        CompoundTag explicitV0 = emptyRoot();
        explicitV0.putInt("DataVersion", 0);
        SettlementSavedData.load(explicitV0, helper.getLevel().registryAccess());
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_legacy_plan_fail_closed")
    public void malformedLegacyPendingRaidIsDisarmed(GameTestHelper helper) {
        assertLegacyPendingDisarmed(helper,
            planTag -> net.minecraft.nbt.StringTag.valueOf("not_a_compound"),
            "wrong PendingRaid tag type");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.remove("CaptainId");
            return planTag;
        }, "missing CaptainId");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putString("Objective", "future_objective");
            return planTag;
        }, "unknown Objective");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putInt("Approach", 0);
            return planTag;
        }, "wrong Approach tag type");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putInt("Night", 1);
            return planTag;
        }, "wrong Night tag type");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putFloat("Approach", Float.NaN);
            return planTag;
        }, "NaN Approach");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putFloat("Approach", Float.POSITIVE_INFINITY);
            return planTag;
        }, "infinite Approach");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putFloat("Approach", -180.1F);
            return planTag;
        }, "Approach below wrapped range");
        assertLegacyPendingDisarmed(helper, planTag -> {
            planTag.putFloat("Approach", 180.0F);
            return planTag;
        }, "Approach outside wrapped range");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_bounds_and_invariants")
    public void corruptBoundsCannotYieldRewardOrPower(GameTestHelper helper) {
        RaidLifecycle valid = new RaidLifecycle();
        helper.assertTrue(valid.initializeAtFounding(10L, 4, 2),
            "valid lifecycle should initialize");
        RaidPlan plan = plan(14L);
        helper.assertTrue(valid.queueFirstPlan(plan),
            "valid lifecycle should queue its plan");
        helper.assertTrue(valid.beginFirstRaid(plan),
            "valid lifecycle should activate");
        for (int i = 0; i < RaidLifecycle.MAX_PARTICIPANTS; i++) {
            helper.assertTrue(valid.recordParticipant(UUID.randomUUID()),
                "first nine participants should fit");
        }
        helper.assertTrue(valid.sealParticipants(),
            "nine-participant capture should seal");
        helper.assertTrue(valid.completeFirstRaid(true), "valid lifecycle should complete");

        CompoundTag overflowTag = valid.writeNbt();
        ListTag participantList = overflowTag.getList("Participants",
            net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < 2; i++) {
            CompoundTag extra = new CompoundTag();
            extra.putUUID("Id", UUID.randomUUID());
            participantList.add(extra);
        }
        RaidLifecycle overflow = RaidLifecycle.readNbt(overflowTag);
        helper.assertTrue(overflow.participants().size() == RaidLifecycle.MAX_PARTICIPANTS,
            "participant list must be bounded to nine");
        helper.assertTrue(overflow.integrityLost() && !overflow.mayGrantReward(),
            "truncated participant evidence must disarm the reward");

        CompoundTag duplicateTag = valid.writeNbt();
        ListTag duplicateList = duplicateTag.getList("Participants",
            net.minecraft.nbt.Tag.TAG_COMPOUND);
        CompoundTag duplicate = new CompoundTag();
        duplicate.putUUID("Id", duplicateList.getCompound(0).getUUID("Id"));
        duplicateList.add(duplicate);
        RaidLifecycle duplicated = RaidLifecycle.readNbt(duplicateTag);
        helper.assertTrue(duplicated.integrityLost() && !duplicated.mayGrantReward(),
            "duplicate participant UUID in persisted evidence must fail closed");

        CompoundTag malformedListTag = valid.writeNbt();
        malformedListTag.putString("Participants", "not_a_list");
        RaidLifecycle malformedList = RaidLifecycle.readNbt(malformedListTag);
        helper.assertTrue(malformedList.integrityLost()
                && !malformedList.mayGrantReward(),
            "wrong Participants tag type must fail closed");

        CompoundTag malformedEntryTag = valid.writeNbt();
        ListTag malformedEntries = new ListTag();
        malformedEntries.add(new CompoundTag());
        malformedEntryTag.put("Participants", malformedEntries);
        RaidLifecycle malformedEntry = RaidLifecycle.readNbt(malformedEntryTag);
        helper.assertTrue(malformedEntry.integrityLost()
                && !malformedEntry.mayGrantReward(),
            "participant entry without a UUID must fail closed");

        Settlement missingLifecycleSource = settlement("Missing Lifecycle",
            BlockPos.ZERO);
        CompoundTag missingLifecycleTag = missingLifecycleSource.writeNbt();
        missingLifecycleTag.remove("RaidLifecycle");
        Settlement missingLifecycle = Settlement.readNbt(missingLifecycleTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(missingLifecycle.raidLifecycle.integrityLost()
                && !missingLifecycle.raidLifecycle.mayGrantReward(),
            "missing v1 lifecycle must not reset into reward-capable pristine state");

        CompoundTag wrongLifecycleTag = missingLifecycleSource.writeNbt();
        wrongLifecycleTag.putString("RaidLifecycle", "not_a_compound");
        Settlement wrongLifecycle = Settlement.readNbt(wrongLifecycleTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(wrongLifecycle.raidLifecycle.integrityLost()
                && !wrongLifecycle.raidLifecycle.mayGrantReward(),
            "wrong v1 lifecycle tag type must fail closed");

        RaidLifecycle corruptLifecycle = RaidLifecycle.readNbt(new CompoundTag());
        RandomSource attemptedRandom = RandomSource.create(31_337L);
        RandomSource untouchedRandom = RandomSource.create(31_337L);
        helper.assertTrue(!corruptLifecycle.initializeAtFounding(50L,
                attemptedRandom, RaidProfile.PEACEFUL)
                && attemptedRandom.nextInt() == untouchedRandom.nextInt(),
            "integrity-lost lifecycle must reject before consuming randomness");
        helper.assertTrue(!corruptLifecycle.initializeAtFounding(50L, 4, 2)
                && corruptLifecycle.firstState() == FirstRaidState.UNINITIALIZED
                && corruptLifecycle.foundedNight() == RaidLifecycle.UNSET_NIGHT
                && !corruptLifecycle.queueFirstPlan(plan(54L)),
            "integrity-lost lifecycle must never become a repeated first raid");

        CompoundTag emptyTrackedTag = valid.writeNbt();
        emptyTrackedTag.put("Participants", new ListTag());
        emptyTrackedTag.putBoolean("ParticipantsTracked", true);
        emptyTrackedTag.putBoolean("RewardEligible", true);
        RaidLifecycle emptyTracked = RaidLifecycle.readNbt(emptyTrackedTag);
        helper.assertTrue(emptyTracked.integrityLost()
                && !emptyTracked.participantsTracked()
                && !emptyTracked.mayGrantReward(),
            "sealed/reward state with an empty set must fail closed");

        CompoundTag invalidDates = valid.writeNbt();
        invalidDates.putLong("FirstAttackNight", 30L);
        RaidLifecycle invalid = RaidLifecycle.readNbt(invalidDates);
        helper.assertTrue(invalid.integrityLost() && !invalid.rewardEligible(),
            "invalid 4-7 night lifecycle must never yield a reward");

        assertPlanRejected(helper, valid,
            planTag -> planTag.putString("Objective", "future_objective"),
            "unknown Objective");
        assertPlanRejected(helper, valid,
            planTag -> planTag.remove("Objective"),
            "missing Objective");
        assertPlanRejected(helper, valid,
            planTag -> planTag.remove("Approach"),
            "missing Approach");
        assertPlanRejected(helper, valid,
            planTag -> planTag.putFloat("Approach", Float.NaN),
            "NaN Approach");
        assertPlanRejected(helper, valid,
            planTag -> planTag.putFloat("Approach", Float.POSITIVE_INFINITY),
            "infinite Approach");
        assertPlanRejected(helper, valid,
            planTag -> planTag.putFloat("Approach", -180.1F),
            "Approach below wrapped range");
        assertPlanRejected(helper, valid,
            planTag -> planTag.putFloat("Approach", 180.0F),
            "non-normalized Approach");
        assertPlanRejected(helper, valid,
            planTag -> planTag.remove("Night"),
            "missing Night");
        assertPlanRejected(helper, valid,
            planTag -> planTag.putInt("Night", 14),
            "wrong Night tag type");

        RaidLifecycle emptyCapture = new RaidLifecycle();
        helper.assertTrue(emptyCapture.initializeAtFounding(20L, 4, 2),
            "zero-capture lifecycle should initialize");
        RaidPlan emptyPlan = plan(24L);
        helper.assertTrue(emptyCapture.queueFirstPlan(emptyPlan)
                && emptyCapture.beginFirstRaid(emptyPlan),
            "zero-capture lifecycle should begin the exact queued plan");
        helper.assertTrue(!emptyCapture.sealParticipants(),
            "an empty participant capture must not seal");
        helper.assertTrue(emptyCapture.completeFirstRaid(true)
                && !emptyCapture.mayGrantReward(),
            "a completed raid with zero actual UUIDs must never yield a Blessing");

        RaidLifecycle partialCapture = new RaidLifecycle();
        helper.assertTrue(partialCapture.initializeAtFounding(40L, 4, 2),
            "partial-capture lifecycle should initialize");
        RaidPlan partialPlan = plan(44L);
        UUID partialId = UUID.randomUUID();
        helper.assertTrue(partialCapture.queueFirstPlan(partialPlan)
                && partialCapture.beginFirstRaid(partialPlan)
                && partialCapture.recordParticipant(partialId),
            "partial capture should retain its actual evidence before sealing");
        RaidLifecycle partialReload = RaidLifecycle.readNbt(partialCapture.writeNbt());
        helper.assertTrue(partialReload.participants().contains(partialId)
                && !partialReload.participantsTracked()
                && !partialReload.rewardEligible()
                && !partialReload.integrityLost(),
            "unsealed partial evidence must survive reload but remain ineligible");

        CompoundTag blessingTag = new CompoundTag();
        blessingTag.putInt("Earned", 2);
        blessingTag.putInt("Spent", -50);
        blessingTag.putInt("Revision", -8);
        ListTag blessingRanks = new ListTag();
        blessingRanks.add(rankTag(BlessingId.WARDEN_OATH, 99));
        blessingRanks.add(rankTag(BlessingId.HEARTHWARD, 99));
        blessingTag.put("Ranks", blessingRanks);
        BlessingState bounded = BlessingState.readNbt(blessingTag);
        helper.assertTrue(bounded.earned() == 2 && bounded.spent() == 2,
            "corrupt counters must conservatively account every earned token");
        helper.assertTrue(bounded.rank(BlessingId.WARDEN_OATH) == 0
                && bounded.rank(BlessingId.HEARTHWARD) == 0,
            "a structurally corrupt ledger must grant no permanent effect");
        helper.assertTrue(bounded.revision() >= 0 && bounded.offerSerial() == 0
                && !bounded.grantOffer(),
            "corrupt ledger must quarantine future grants and expose no offer");

        BlessingState oneOffer = new BlessingState();
        helper.assertTrue(oneOffer.grantOffer(), "corruption fixture needs one offer");
        CompoundTag missingSpent = oneOffer.writeNbt();
        missingSpent.remove("Spent");
        assertLedgerFailsClosed(helper, BlessingState.readNbt(missingSpent),
            "missing Spent");

        CompoundTag wrongSpent = oneOffer.writeNbt();
        wrongSpent.putString("Spent", "zero");
        assertLedgerFailsClosed(helper, BlessingState.readNbt(wrongSpent),
            "wrong Spent tag type");

        CompoundTag wrongRanks = oneOffer.writeNbt();
        wrongRanks.putString("Ranks", "not_a_list");
        assertLedgerFailsClosed(helper, BlessingState.readNbt(wrongRanks),
            "wrong Ranks tag type");

        CompoundTag malformedRank = oneOffer.writeNbt();
        ListTag malformedRankList = new ListTag();
        CompoundTag malformedRankEntry = new CompoundTag();
        malformedRankEntry.putString("Id", BlessingId.WARDEN_OATH.id());
        malformedRankEntry.putString("Rank", "one"); // missing WireId + wrong Rank type
        malformedRankList.add(malformedRankEntry);
        malformedRank.put("Ranks", malformedRankList);
        assertLedgerFailsClosed(helper, BlessingState.readNbt(malformedRank),
            "missing/wrong WireId or Rank field");

        Settlement missingLedgerSource = settlement("Missing Ledger", BlockPos.ZERO);
        CompoundTag missingLedgerTag = missingLedgerSource.writeNbt();
        missingLedgerTag.remove("BlessingState");
        assertSettlementLedgerQuarantinePersists(helper, missingLedgerTag,
            "missing BlessingState");

        CompoundTag wrongLedgerTag = missingLedgerSource.writeNbt();
        wrongLedgerTag.putString("BlessingState", "not_a_compound");
        assertSettlementLedgerQuarantinePersists(helper, wrongLedgerTag,
            "wrong BlessingState tag type");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_first_raid_schedule")
    public void firstRaidScheduleIsBoundedAndReloadDeterministic(GameTestHelper helper) {
        for (long seed = 0; seed < 64; seed++) {
            RaidLifecycle lifecycle = new RaidLifecycle();
            helper.assertTrue(lifecycle.initializeAtFounding(100L,
                RandomSource.create(seed), RaidProfile.PEACEFUL),
                "fresh schedule should initialize for seed " + seed);
            long attack = lifecycle.firstAttackNight();
            helper.assertTrue(attack >= 104L && attack <= 107L,
                "first attack must be 4-7 nights after founding, got " + attack);
            helper.assertTrue(attack - lifecycle.firstWarningNight() == 2L,
                "peaceful warning must persist two nights before attack");
            helper.assertTrue(!lifecycle.queueFirstPlan(new RaidPlan(UUID.randomUUID(),
                    RaidObjective.BLOD, -180.1F, attack)),
                "non-normalized plan must be rejected before persistence");
            RaidPlan queued = plan(attack);
            helper.assertTrue(lifecycle.queueFirstPlan(queued),
                "the authored plan should queue once for seed " + seed);

            RaidLifecycle reloaded = RaidLifecycle.readNbt(lifecycle.writeNbt());
            helper.assertTrue(reloaded.foundedNight() == 100L
                    && reloaded.firstAttackNight() == attack
                    && reloaded.firstWarningNight() == lifecycle.firstWarningNight()
                    && reloaded.queuedPlan().orElseThrow().equals(queued),
                "reload must preserve the single original draw/plan for seed " + seed);
            helper.assertTrue(!reloaded.queueFirstPlan(queued),
                "even an identical plan resend must not mutate a one-shot queue");
            RaidPlan replacement = plan(attack);
            helper.assertTrue(!reloaded.queueFirstPlan(replacement),
                "a different plan must not silently replace the persisted first plan");
            helper.assertTrue(!reloaded.beginFirstRaid(replacement)
                    && reloaded.queuedPlan().orElseThrow().equals(queued),
                "activation must require record-equality with the persisted warning plan");
            helper.assertTrue(!reloaded.initializeAtFounding(100L,
                    RandomSource.create(seed + 1), RaidProfile.PEACEFUL),
                "reload/rebind must never draw a replacement schedule");
            helper.assertTrue(reloaded.firstAttackNight() == attack,
                "rejected reinitialization must not mutate attack night");
        }
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_blessing_atomicity")
    public void staleTwoPlayerClickCannotConsumeSecondToken(GameTestHelper helper) {
        BlessingState state = new BlessingState();
        helper.assertTrue(state.grantOffer() && state.grantOffer(),
            "two offers should be earned for the race test");
        int sharedRevision = state.revision();
        int sharedSerial = state.offerSerial();

        helper.assertTrue(state.compareAndCommit(sharedRevision, sharedSerial,
                BlessingId.WARDEN_OATH) == BlessingState.CommitResult.ACCEPTED,
            "player A should commit token one");
        helper.assertTrue(state.compareAndCommit(sharedRevision, sharedSerial,
                BlessingId.THORNED_ROADS) == BlessingState.CommitResult.STALE,
            "player B's same-snapshot click must be stale");
        helper.assertTrue(state.spent() == 1 && state.offerSerial() == 2
                && state.rank(BlessingId.THORNED_ROADS) == 0,
            "stale resend must leave token two untouched");

        int refreshedRevision = state.revision();
        helper.assertTrue(state.compareAndCommit(refreshedRevision, 2,
                BlessingId.HEARTHWARD) == BlessingState.CommitResult.ACCEPTED,
            "a refreshed view may spend token two exactly once");
        helper.assertTrue(state.compareAndCommit(refreshedRevision, 2,
                BlessingId.HEARTHWARD) == BlessingState.CommitResult.STALE,
            "resending an accepted click must be stale");
        helper.assertTrue(state.spent() == 2
                && state.rank(BlessingId.WARDEN_OATH) == 1
                && state.rank(BlessingId.HEARTHWARD) == 1,
            "exactly two accepted commits should yield exactly two ranks");
        helper.assertTrue(state.compareAndCommit(state.revision(), 3,
                BlessingId.THORNED_ROADS) == BlessingState.CommitResult.NO_OFFER,
            "correct current revision with no token should report no_offer");
        helper.assertTrue(state.compareAndCommit(state.revision(), 3, null)
                == BlessingState.CommitResult.INVALID,
            "null/unknown typed choice should be invalid");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "settlement_state_guard_order")
    public void guardOrderUsesLastValidWinsAndSafeTimeout(GameTestHelper helper) {
        GuardOrder order = new GuardOrder();
        helper.assertTrue(!order.clear() && order.revision() == 0,
            "clearing an already empty order must be revision-idempotent");
        BlockPos rally = new BlockPos(7, 65, 7);
        helper.assertTrue(order.issue(GuardOrder.Mode.RALLY_HERE, rally, 500L),
            "valid rally order should be accepted");
        int revision = order.revision();
        helper.assertTrue(!order.issue(GuardOrder.Mode.DEFEND_HEARTH, null, 800L),
            "invalid order should be rejected");
        helper.assertTrue(order.mode() == GuardOrder.Mode.RALLY_HERE
                && order.pos().orElseThrow().equals(rally)
                && order.revision() == revision,
            "invalid attempt must not erase the last valid order");

        GuardOrder loaded = GuardOrder.readNbt(order.writeNbt());
        helper.assertTrue(loaded.activeAt(499L)
                && loaded.modeAt(499L) == GuardOrder.Mode.RALLY_HERE,
            "round-tripped order should be active before timeout");
        helper.assertTrue(loaded.expireIfNeeded(500L),
            "timeout helper should clear exactly at until time");
        helper.assertTrue(!loaded.activeAt(500L)
                && loaded.mode() == GuardOrder.Mode.NONE
                && loaded.pos().isEmpty(),
            "expired order should become empty independently of alert state");
        helper.assertTrue(!loaded.expireIfNeeded(501L),
            "already-cleared order should not revise twice");
        int clearedRevision = loaded.revision();
        helper.assertTrue(!loaded.clear() && loaded.revision() == clearedRevision,
            "explicit release after timeout must also be revision-idempotent");
        helper.succeed();
    }

    private static Settlement settlement(String name, BlockPos center) {
        return new Settlement(UUID.randomUUID(), name, center);
    }

    private static RaidPlan plan(long night) {
        return new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, -90.0F, night);
    }

    private static CompoundTag asV0(CompoundTag tag) {
        tag.remove("RaidProfileWireId");
        tag.remove("RaidProfile");
        tag.remove("RaidLifecycle");
        tag.remove("BlessingState");
        tag.remove("GuardOrder");
        return tag;
    }

    private static CompoundTag rankTag(BlessingId blessing, int rank) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("WireId", blessing.wireId());
        tag.putString("Id", blessing.id());
        tag.putInt("Rank", rank);
        return tag;
    }

    private static CompoundTag emptyRoot() {
        CompoundTag root = new CompoundTag();
        root.put("Settlements", new ListTag());
        return root;
    }

    private static void assertVersionRejected(GameTestHelper helper,
                                              CompoundTag root,
                                              String scenario) {
        try {
            SettlementSavedData.load(root, helper.getLevel().registryAccess());
            helper.assertTrue(false, scenario + " must be rejected");
        } catch (SettlementSavedData.DataVersionException expected) {
            helper.assertTrue(!expected.getMessage().isBlank(),
                scenario + " should report a controlled reason");
        }
    }

    private static void assertLedgerFailsClosed(GameTestHelper helper,
                                                 BlessingState state,
                                                 String scenario) {
        helper.assertTrue(state.earned() == 1 && state.spent() == 1
                && state.offerSerial() == 0 && state.ranks().isEmpty()
                && !state.grantOffer(),
            scenario + " must grant no effect and expose no fresh offer");
    }

    private static void assertSettlementLedgerQuarantinePersists(
            GameTestHelper helper, CompoundTag settlementTag, String scenario) {
        Settlement first = Settlement.readNbt(settlementTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(first.blessingState.quarantined()
                && first.blessingState.ranks().isEmpty()
                && first.blessingState.offerSerial() == 0
                && !first.blessingState.grantOffer(),
            scenario + " must enter a closed quarantine");
        Settlement second = Settlement.readNbt(first.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(second.blessingState.quarantined()
                && second.blessingState.ranks().isEmpty()
                && second.blessingState.offerSerial() == 0
                && !second.blessingState.grantOffer(),
            scenario + " quarantine must survive a second save/reload");
    }

    private static void assertLegacyPendingDisarmed(GameTestHelper helper,
            java.util.function.Function<CompoundTag, net.minecraft.nbt.Tag> corrupt,
            String scenario) {
        Settlement source = settlement("Legacy Corrupt", BlockPos.ZERO);
        source.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD,
            -90.0F, 1L);
        CompoundTag settlementTag = asV0(source.writeNbt());
        CompoundTag pending = settlementTag.getCompound("PendingRaid");
        settlementTag.put("PendingRaid", corrupt.apply(pending));
        CompoundTag root = emptyRoot();
        ListTag settlements = new ListTag();
        settlements.add(settlementTag);
        root.put("Settlements", settlements);
        Settlement loaded = SettlementSavedData.load(root,
            helper.getLevel().registryAccess()).settlements.get(source.id);
        helper.assertTrue(loaded != null && loaded.pendingRaid == null
                && loaded.raidLifecycle.integrityLost()
                && !loaded.raidLifecycle.initializeAtFounding(5L, 4, 2)
                && !loaded.raidLifecycle.mayGrantReward(),
            scenario + " must be disarmed without fabricating a raid or reward");
    }

    private static void assertPlanRejected(GameTestHelper helper,
                                           RaidLifecycle valid,
                                           java.util.function.Consumer<CompoundTag> corrupt,
                                           String scenario) {
        CompoundTag tag = valid.writeNbt();
        CompoundTag activePlan = tag.getCompound("ActivePlan");
        corrupt.accept(activePlan);
        tag.put("ActivePlan", activePlan);
        RaidLifecycle loaded = RaidLifecycle.readNbt(tag);
        helper.assertTrue(loaded.integrityLost()
                && loaded.activePlan().isEmpty()
                && !loaded.mayGrantReward(),
            scenario + " must reject the plan and disarm the reward");
    }
}

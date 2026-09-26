package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Persistence and mutation contracts for Blessings bound to one target. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TargetBlessingStateGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "target_blessing_exact_cap")
    public void everyBlessingCapsAtExactlyRankThree(GameTestHelper helper) {
        TargetBlessingState state = new TargetBlessingState();
        for (BlessingId blessing : BlessingId.values()) {
            for (int expected = 1; expected <= TargetBlessingState.MAX_RANK; expected++) {
                helper.assertTrue(state.apply(blessing)
                        == TargetBlessingState.ApplyResult.APPLIED,
                    blessing.id() + " rank " + expected + " should apply");
                helper.assertTrue(state.rank(blessing) == expected,
                    blessing.id() + " must expose exact applied rank");
            }
            helper.assertTrue(state.apply(blessing)
                    == TargetBlessingState.ApplyResult.MAXED,
                blessing.id() + " rank IV must be refused as MAXED");
            helper.assertTrue(state.rank(blessing) == TargetBlessingState.MAX_RANK,
                "a MAXED attempt must not mutate rank III");
        }
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "target_blessing_invalid_input")
    public void invalidInputDoesNotMutateAValidLedger(GameTestHelper helper) {
        TargetBlessingState state = new TargetBlessingState();
        helper.assertTrue(state.apply(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED,
            "fixture rank should apply");
        helper.assertTrue(state.apply(null) == TargetBlessingState.ApplyResult.INVALID,
            "null ids must fail closed");
        helper.assertTrue(state.rank(null) == 0
                && state.rank(BlessingId.HEARTHWARD) == 1
                && state.rank(BlessingId.WARDEN_OATH) == 0,
            "invalid input must neither leak nor mutate any rank");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "target_blessing_nbt_round_trip")
    public void validRanksRoundTripWithoutDrift(GameTestHelper helper) {
        TargetBlessingState original = new TargetBlessingState();
        apply(original, BlessingId.WARDEN_OATH, 1);
        apply(original, BlessingId.HEARTHWARD, 2);
        apply(original, BlessingId.THORNED_ROADS, 3);

        TargetBlessingState copy = TargetBlessingState.readNbt(original.writeNbt());
        helper.assertTrue(!copy.quarantined(),
            "a valid bounded ledger must remain auditable");
        helper.assertTrue(copy.rank(BlessingId.WARDEN_OATH) == 1
                && copy.rank(BlessingId.HEARTHWARD) == 2
                && copy.rank(BlessingId.THORNED_ROADS) == 3,
            "every permanent target rank must survive exact NBT reload");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "target_blessing_corrupt_quarantine")
    public void corruptLedgerGrantsNoEffectAndNoReplacementRank(
            GameTestHelper helper) {
        TargetBlessingState original = new TargetBlessingState();
        apply(original, BlessingId.WARDEN_OATH, 1);
        CompoundTag corrupted = original.writeNbt();
        ListTag ranks = (ListTag) corrupted.get("Ranks");
        ((CompoundTag) ranks.get(0)).putInt("Rank", TargetBlessingState.MAX_RANK + 1);

        TargetBlessingState quarantined = TargetBlessingState.readNbt(corrupted);
        helper.assertTrue(quarantined.quarantined(),
            "an out-of-range saved rank must quarantine the complete ledger");
        helper.assertTrue(quarantined.rank(BlessingId.WARDEN_OATH) == 0,
            "quarantined data must grant no effect");
        helper.assertTrue(quarantined.apply(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.INVALID,
            "corruption must not turn a lost rank into a free replacement rank");

        TargetBlessingState reloaded = TargetBlessingState.readNbt(
            quarantined.writeNbt());
        helper.assertTrue(reloaded.quarantined()
                && reloaded.apply(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.INVALID,
            "quarantine must remain sticky across later saves");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "target_blessing_settler_reload")
    public void settlerEntityPersistsPermanentBlessings(GameTestHelper helper) {
        SettlerEntity original = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        helper.assertTrue(original.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED
                && original.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED,
            "two physical seals should bind two permanent ranks");

        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        SettlerEntity copy = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 3));
        copy.readAdditionalSaveData(saved);
        helper.assertTrue(copy.blessingRank(BlessingId.HEARTHWARD) == 2
                && copy.blessingRank(BlessingId.WARDEN_OATH) == 0,
            "the target's exact ranks must survive entity save/load");

        // The feature briefly wrote a strict nested ledger before it gained an
        // entity-owned marker. Preserve that auditable shape and stamp it on
        // the next save instead of quarantining a real bound rank.
        CompoundTag preMarker = saved.copy();
        preMarker.remove(SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY);
        SettlerEntity migrated = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 3));
        migrated.readAdditionalSaveData(preMarker);
        helper.assertTrue(migrated.blessingRank(BlessingId.HEARTHWARD) == 2,
            "an auditable pre-marker nested ledger must retain its permanent ranks");
        CompoundTag migratedSave = new CompoundTag();
        migrated.addAdditionalSaveData(migratedSave);
        helper.assertTrue(migratedSave.contains(
                SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY, Tag.TAG_INT)
                && migratedSave.getInt(SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY)
                    == SettlerEntity.TARGET_BLESSINGS_SCHEMA_VERSION,
            "the first rewrite must stamp the current settler-owned schema marker");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "target_blessing_settler_schema_ownership")
    public void settlerSchemaDistinguishesLegacyAbsenceFromCurrentCorruption(
            GameTestHelper helper) {
        SettlerEntity fixture = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        CompoundTag current = new CompoundTag();
        fixture.addAdditionalSaveData(current);
        helper.assertTrue(current.contains(
                SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY, Tag.TAG_INT)
                && current.getInt(SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY)
                    == SettlerEntity.TARGET_BLESSINGS_SCHEMA_VERSION
                && current.get("TargetBlessings") instanceof CompoundTag,
            "every current settler save must own its strict nested target ledger");

        CompoundTag legacyTag = current.copy();
        legacyTag.remove(SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY);
        legacyTag.remove("TargetBlessings");
        SettlerEntity legacy = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 2));
        legacy.readAdditionalSaveData(legacyTag);
        helper.assertTrue(legacy.applyBlessing(BlessingId.THORNED_ROADS)
                == TargetBlessingState.ApplyResult.APPLIED,
            "joint marker and ledger absence is a legitimate pre-feature empty migration");

        CompoundTag missingTag = current.copy();
        missingTag.remove("TargetBlessings");
        SettlerEntity missing = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 2));
        missing.readAdditionalSaveData(missingTag);
        helper.assertTrue(missing.blessingRank(BlessingId.HEARTHWARD) == 0
                && missing.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.INVALID,
            "a current marker with a missing owned ledger must fail closed");

        CompoundTag wrongTypeTag = current.copy();
        wrongTypeTag.putString("TargetBlessings", "malformed-present-ledger");
        SettlerEntity wrongType = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        wrongType.readAdditionalSaveData(wrongTypeTag);
        helper.assertTrue(wrongType.blessingRank(BlessingId.HEARTHWARD) == 0
                && wrongType.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.INVALID,
            "a current marker with the wrong nested NBT type must fail closed");

        CompoundTag malformedTag = current.copy();
        malformedTag.put("TargetBlessings", new CompoundTag());
        SettlerEntity malformed = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 4));
        malformed.readAdditionalSaveData(malformedTag);
        helper.assertTrue(malformed.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.INVALID,
            "a current marker with malformed strict nested fields must fail closed");

        CompoundTag wrongMarkerTag = current.copy();
        wrongMarkerTag.putString(SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY, "1");
        SettlerEntity wrongMarker = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 4));
        wrongMarker.readAdditionalSaveData(wrongMarkerTag);
        helper.assertTrue(wrongMarker.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.INVALID,
            "a present marker of the wrong NBT type must not authorize its nested ledger");

        CompoundTag rewritten = new CompoundTag();
        missing.addAdditionalSaveData(rewritten);
        SettlerEntity stickyReload = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(1, 1, 4));
        stickyReload.readAdditionalSaveData(rewritten);
        helper.assertTrue(rewritten.contains(
                SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY, Tag.TAG_INT)
                && rewritten.getInt(SettlerEntity.TARGET_BLESSINGS_SCHEMA_KEY)
                    == SettlerEntity.TARGET_BLESSINGS_SCHEMA_VERSION
                && stickyReload.blessingRank(BlessingId.HEARTHWARD) == 0
                && stickyReload.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.INVALID,
            "current missing-ledger quarantine must remain sticky after rewrite/reload");
        helper.succeed();
    }

    private static void apply(TargetBlessingState state, BlessingId blessing,
                              int count) {
        for (int i = 0; i < count; i++) {
            state.apply(blessing);
        }
    }
}

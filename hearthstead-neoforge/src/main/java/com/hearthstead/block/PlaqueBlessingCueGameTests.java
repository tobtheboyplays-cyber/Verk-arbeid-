package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exact APPLIED-only rune onset, queue-cap and contact-timing contract. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PlaqueBlessingCueGameTests {

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "plaque_blessing_rune_cues_are_applied_only_and_land_at_tick_four")
    public void runeCuesAreAppliedOnlyAndLandAtTickFour(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(2, 2, 2));
        CueSpy plaque = fixture.plaque();
        long appliedTick = helper.getLevel().getGameTime();

        for (BlessingId blessing : BlessingId.values()) {
            for (int rank = 1; rank <= TargetBlessingState.MAX_RANK; rank++) {
                helper.assertTrue(plaque.applyBlessing(blessing)
                        == TargetBlessingState.ApplyResult.APPLIED,
                    blessing.id() + " rank " + rank
                        + " must be the sole path that authors a rune cue");
            }
        }

        helper.assertTrue(plaque.onsets.size() == 9
                && plaque.pendingBlessingCueCount() == 9,
            "three types times ranks I-III must yield exactly nine immediate "
                + "onsets and nine delayed contacts");
        for (BlessingId blessing : BlessingId.values()) {
            helper.assertTrue(plaque.onsetCounts.getOrDefault(blessing, 0) == 3,
                blessing.id() + " must retain its own three-cue type identity");
        }

        // Attempts ten and eleven are both MAXED. A null/corrupt choice is
        // INVALID. None may grow the bounded nine-entry runtime queue.
        helper.assertTrue(plaque.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.MAXED
                && plaque.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.MAXED
                && plaque.applyBlessing(null)
                    == TargetBlessingState.ApplyResult.INVALID,
            "attempts 10/11 must be MAXED and an invalid choice must be refused");
        helper.assertTrue(plaque.onsets.size() == 9
                && plaque.pendingBlessingCueCount() == 9,
            "MAXED/INVALID must add neither onset nor delayed contact cue");

        helper.runAfterDelay(PlaqueBlockEntity.BLESSING_RUNE_CONTACT_DELAY_TICKS - 1L,
            () -> {
                plaque.tickBlessingContactCues(helper.getLevel(),
                    helper.getLevel().getGameTime());
                helper.assertTrue(plaque.contacts.isEmpty()
                        && plaque.pendingBlessingCueCount() == 9,
                    "the completed rune must not lead the authored +4 tick contact");
            });
        helper.runAfterDelay(PlaqueBlockEntity.BLESSING_RUNE_CONTACT_DELAY_TICKS,
            () -> {
                plaque.tickBlessingContactCues(helper.getLevel(),
                    helper.getLevel().getGameTime());
                helper.assertTrue(plaque.contacts.equals(plaque.onsets)
                        && plaque.pendingBlessingCueCount() == 0,
                    "all nine contacts must drain once, in APPLIED/type order");
                for (BlessingId blessing : BlessingId.values()) {
                    helper.assertTrue(plaque.contactCounts.getOrDefault(blessing, 0) == 3,
                        blessing.id() + " must land exactly three type-specific contacts");
                }
                helper.assertTrue(plaque.contactTicks.size() == 9
                        && plaque.contactTicks.stream().allMatch(tick ->
                            tick - appliedTick
                                == PlaqueBlockEntity.BLESSING_RUNE_CONTACT_DELAY_TICKS),
                    "every plaque contact must land exactly four server ticks after APPLIED");
                helper.succeed();
            });
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "plaque_blessing_runtime_cues_are_cleared_by_reload")
    public void runtimeCuesAreClearedByReload(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(2, 2, 2));
        CueSpy plaque = fixture.plaque();
        helper.assertTrue(plaque.applyBlessing(BlessingId.THORNED_ROADS)
                == TargetBlessingState.ApplyResult.APPLIED
                && plaque.onsets.size() == 1
                && plaque.pendingBlessingCueCount() == 1,
            "fixture must own one already-acknowledged, pending contact cue");

        CompoundTag saved = plaque.saveForTest(helper.getLevel().registryAccess());
        plaque.loadForTest(saved, helper.getLevel().registryAccess());
        helper.assertTrue(plaque.pendingBlessingCueCount() == 0,
            "loadAdditional must discard runtime-only plaque cues, never replay them");

        helper.runAfterDelay(PlaqueBlockEntity.BLESSING_RUNE_CONTACT_DELAY_TICKS,
            () -> {
                plaque.tickBlessingContactCues(helper.getLevel(),
                    helper.getLevel().getGameTime());
                helper.assertTrue(plaque.contacts.isEmpty()
                        && plaque.onsets.size() == 1,
                    "the permanent saved rank may survive reload, but its old VFX cannot replay");
                helper.succeed();
            });
    }

    private static Fixture fixture(GameTestHelper helper, BlockPos relativePos) {
        BlockPos plaquePos = helper.absolutePos(relativePos);
        BlockState state = ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH);
        // Preserve the world invariant even though the instance-bound spy is
        // intentionally detached from the chunk's ordinary survey ticker.
        helper.setBlock(relativePos, state);
        CueSpy plaque = new CueSpy(plaquePos, state);
        plaque.setLevel(helper.getLevel());

        Building building = new Building(UUID.randomUUID(), BuildingType.FARMHOUSE,
            plaquePos, plaquePos.below(), BoundingBox.fromCorners(
                plaquePos.offset(-1, -1, -1), plaquePos.offset(1, 1, 1)));
        building.valid = true;
        Settlement settlement = new Settlement(UUID.randomUUID(), "Runehall", plaquePos);
        settlement.radius = 2;
        settlement.buildings.add(building);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        plaque.linkForTest(building.id, helper.getLevel().registryAccess());
        return new Fixture(plaque, building);
    }

    private record Fixture(CueSpy plaque, Building building) {
    }

    private static final class CueSpy extends PlaqueBlockEntity {
        private final List<BlessingId> onsets = new ArrayList<>();
        private final List<BlessingId> contacts = new ArrayList<>();
        private final List<Long> contactTicks = new ArrayList<>();
        private final Map<BlessingId, Integer> onsetCounts =
            new EnumMap<>(BlessingId.class);
        private final Map<BlessingId, Integer> contactCounts =
            new EnumMap<>(BlessingId.class);

        private CueSpy(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        private void linkForTest(UUID buildingId, HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", BuildingType.FARMHOUSE.id());
            tag.putString("State", PlaqueState.LINKED_VALID.id());
            tag.putUUID("Building", buildingId);
            loadAdditional(tag, provider);
        }

        private CompoundTag saveForTest(HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            saveAdditional(tag, provider);
            return tag;
        }

        private void loadForTest(CompoundTag tag, HolderLookup.Provider provider) {
            loadAdditional(tag, provider);
        }

        @Override
        protected void presentBlessingOnset(ServerLevel level, BlessingId blessing,
                                            float yaw) {
            onsets.add(blessing);
            onsetCounts.merge(blessing, 1, Integer::sum);
        }

        @Override
        protected void presentBlessingContact(ServerLevel level, BlessingId blessing,
                                              float yaw) {
            contacts.add(blessing);
            contactCounts.merge(blessing, 1, Integer::sum);
            contactTicks.add(level.getGameTime());
        }
    }
}

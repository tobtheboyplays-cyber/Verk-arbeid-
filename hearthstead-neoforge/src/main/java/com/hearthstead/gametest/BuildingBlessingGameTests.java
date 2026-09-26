package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Permanent Blessing binding on a building's physical plaque identity.
 *
 * <p>These tests pin the conservation boundary as well as persistence: the
 * caller may consume a physical seal only for {@link
 * TargetBlessingState.ApplyResult#APPLIED}. MAXED and INVALID are explicit
 * no-consumption results and must leave every rank unchanged.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuildingBlessingGameTests {

    private static Building building() {
        BlockPos plaque = new BlockPos(2, 2, 2);
        Building building = new Building(UUID.randomUUID(), BuildingType.FARMHOUSE,
            plaque, new BlockPos(2, 1, 3),
            BoundingBox.fromCorners(new BlockPos(1, 1, 1),
                new BlockPos(6, 4, 6)));
        building.valid = true;
        return building;
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "building_blessing_ranks_cap_and_survive_reload")
    public void ranksCapAndSurviveReload(GameTestHelper helper) {
        Building building = building();

        for (int expectedRank = 1; expectedRank <= TargetBlessingState.MAX_RANK;
             expectedRank++) {
            helper.assertTrue(building.applyBlessing(BlessingId.WARDEN_OATH)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "each rank through III must return APPLIED so the caller may consume "
                    + "exactly one physical seal");
            helper.assertTrue(building.blessingRank(BlessingId.WARDEN_OATH)
                    == expectedRank,
                "an applied building Blessing must advance exactly one rank");
        }
        helper.assertTrue(building.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.MAXED,
            "rank III must return MAXED, the seal item's no-consumption contract");
        helper.assertTrue(building.blessingRank(BlessingId.WARDEN_OATH)
                == TargetBlessingState.MAX_RANK,
            "MAXED must not mutate the permanent rank");
        helper.assertTrue(building.applyBlessing(BlessingId.HEARTHWARD)
                == TargetBlessingState.ApplyResult.APPLIED,
            "different Blessing types keep independent rank ledgers");

        Building restored = Building.readNbt(building.writeNbt());
        helper.assertTrue(restored.blessingRank(BlessingId.WARDEN_OATH) == 3,
            "building Blessing rank III must survive a save/reload");
        helper.assertTrue(restored.blessingRank(BlessingId.HEARTHWARD) == 1,
            "independent building Blessing ranks must survive a save/reload");
        helper.assertTrue(restored.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.MAXED,
            "the cap must still be authoritative after reload");
        helper.assertTrue(restored.applyBlessing(null)
                == TargetBlessingState.ApplyResult.INVALID
                && restored.blessingRank(BlessingId.HEARTHWARD) == 1,
            "INVALID must be a no-consumption, no-mutation result");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "building_blessing_strict_nbt_and_current_missing")
    public void strictNbtAndCurrentMissing(GameTestHelper helper) {
        CompoundTag malformed = building().writeNbt();
        malformed.putInt("TargetBlessings", 7);
        Building quarantined = Building.readNbt(malformed);
        helper.assertTrue(quarantined.blessingStateQuarantined(),
            "a present TargetBlessings value of the wrong NBT type must quarantine");
        helper.assertTrue(quarantined.blessingRank(BlessingId.THORNED_ROADS) == 0,
            "a quarantined building must grant no hidden effect");
        helper.assertTrue(quarantined.applyBlessing(BlessingId.THORNED_ROADS)
                == TargetBlessingState.ApplyResult.INVALID,
            "a corrupt ledger must return INVALID rather than accepting a replacement "
                + "rank and consuming a seal");

        CompoundTag malformedCompound = building().writeNbt();
        malformedCompound.put("TargetBlessings", new CompoundTag());
        Building badFields = Building.readNbt(malformedCompound);
        helper.assertTrue(badFields.blessingStateQuarantined()
                && badFields.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.INVALID,
            "a compound with missing version/rank fields must quarantine just like a "
                + "wrong outer NBT type");

        CompoundTag missingCurrent = building().writeNbt();
        missingCurrent.remove("TargetBlessings");
        Building missing = Building.readNbt(missingCurrent);
        helper.assertTrue(missing.blessingStateQuarantined()
                && missing.blessingRank(BlessingId.THORNED_ROADS) == 0
                && missing.applyBlessing(BlessingId.THORNED_ROADS)
                    == TargetBlessingState.ApplyResult.INVALID,
            "the standalone overload owns the current schema, so a missing target "
                + "ledger must fail closed instead of becoming a replacement rank");

        Building sticky = Building.readNbt(missing.writeNbt());
        helper.assertTrue(sticky.blessingStateQuarantined()
                && sticky.applyBlessing(BlessingId.WARDEN_OATH)
                    == TargetBlessingState.ApplyResult.INVALID,
            "current-schema missing-ledger quarantine must survive rewrite and reload");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "building_blessing_outer_source_version_owns_missing_ledger")
    public void outerSourceVersionOwnsMissingLedger(GameTestHelper helper) {
        Settlement authored = new Settlement(UUID.randomUUID(), "Skjematun",
            BlockPos.ZERO);
        authored.buildings.add(building());
        CompoundTag missingLedger = authored.writeNbt();
        ListTag buildingTags = missingLedger.getList("Buildings", Tag.TAG_COMPOUND);
        helper.assertTrue(buildingTags.size() == 1,
            "fixture must contain exactly one persisted building");
        buildingTags.getCompound(0).remove("TargetBlessings");

        for (int sourceVersion = 0; sourceVersion <= 1; sourceVersion++) {
            Settlement migrated = Settlement.readNbt(missingLedger.copy(),
                sourceVersion);
            helper.assertTrue(migrated.buildings.size() == 1,
                "v" + sourceVersion + " migration must retain the building record");
            Building migratedBuilding = migrated.buildings.get(0);
            helper.assertTrue(!migratedBuilding.blessingStateQuarantined()
                    && migratedBuilding.applyBlessing(BlessingId.THORNED_ROADS)
                        == TargetBlessingState.ApplyResult.APPLIED,
                "v" + sourceVersion + " predates per-building target ledgers, so "
                    + "absence is a legitimate empty migration");
        }

        Settlement legacyV2 = Settlement.readNbt(missingLedger.copy(), 2);
        Building legacyV2Building = legacyV2.buildings.get(0);
        helper.assertTrue(!legacyV2Building.blessingStateQuarantined()
                && legacyV2Building.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.APPLIED,
            "explicit legacy v2 is the final schema before per-building target "
                + "ledger ownership, so missing TargetBlessings migrates empty");

        Settlement currentMissing = Settlement.readNbt(missingLedger.copy(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        Building currentBuilding = currentMissing.buildings.get(0);
        helper.assertTrue(currentBuilding.blessingStateQuarantined()
                && currentBuilding.blessingRank(BlessingId.THORNED_ROADS) == 0
                && currentBuilding.applyBlessing(BlessingId.THORNED_ROADS)
                    == TargetBlessingState.ApplyResult.INVALID,
            "v3 owns TargetBlessings, so a missing ledger must be inert and reject seals");

        Settlement stickyReload = Settlement.readNbt(currentMissing.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(stickyReload.buildings.get(0).blessingStateQuarantined(),
            "v3 missing-ledger quarantine must remain sticky through settlement rewrite");

        CompoundTag wrongType = authored.writeNbt();
        wrongType.getList("Buildings", Tag.TAG_COMPOUND).getCompound(0)
            .putInt("TargetBlessings", 7);
        Settlement currentWrongType = Settlement.readNbt(wrongType,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(currentWrongType.buildings.get(0)
                .blessingStateQuarantined(),
            "v3 TargetBlessings with the wrong outer type must also fail closed");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 400,
        batch = "building_blessing_a_linked_valid_plaque_binds_the_building")
    public void aLinkedValidPlaqueBindsTheBuilding(GameTestHelper helper) {
        Settlement settlement = settlement(helper);
        BlockPos room = new BlockPos(4, 0, 4);
        buildFarmRoom(helper, room);
        BlockPos plaquePos = fitFarmPlaque(helper, room);

        helper.runAfterDelay(20, () -> {
            PlaqueBlockEntity plaque = plaqueAt(helper, plaquePos);
            plaque.survey(helper.getLevel());
            Building building = plaque.building(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID
                    && building != null && building.valid,
                "setup: the physical plaque must declare a live building");
            helper.assertTrue(settlement.buildings.contains(building)
                    && building.plaquePos.equals(helper.absolutePos(plaquePos)),
                "setup: that exact plaque position must be the building identity");

            SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
            data.setDirty(false);
            helper.assertTrue(plaque.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "a linked, valid plaque must bind one permanent building rank");
            helper.assertTrue(data.isDirty(),
                "APPLIED must dirty SettlementSavedData so the permanent rank is saved");
            helper.assertTrue(plaque.blessingRank(BlessingId.HEARTHWARD) == 1
                    && building.blessingRank(BlessingId.HEARTHWARD) == 1,
                "plaque and building queries must read the same sole ledger");

            // Fill the same card to rank III, then prove that MAXED neither
            // changes the ledger nor dirties the save.
            helper.assertTrue(plaque.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.APPLIED
                    && plaque.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.APPLIED,
                "setup: two more real seals fill Hearthward to rank III");
            data.setDirty(false);
            helper.assertTrue(plaque.applyBlessing(BlessingId.HEARTHWARD)
                    == TargetBlessingState.ApplyResult.MAXED,
                "a rank-III plaque must return MAXED so the fourth seal is kept");
            helper.assertTrue(!data.isDirty()
                    && plaque.blessingRank(BlessingId.HEARTHWARD) == 3,
                "MAXED must not mutate or dirty the permanent building ledger");

            // The UUID alone is not enough: the physical plaque coordinate
            // is part of the building identity and must still agree.
            building.plaquePos = building.plaquePos.east();
            data.setDirty(false);
            helper.assertTrue(plaque.applyBlessing(BlessingId.THORNED_ROADS)
                    == TargetBlessingState.ApplyResult.INVALID,
                "a linked UUID whose Building points at another plaque position is INVALID");
            helper.assertTrue(!data.isDirty()
                    && building.blessingRank(BlessingId.THORNED_ROADS) == 0,
                "identity mismatch INVALID must not mutate or dirty the building");

            building.plaquePos = helper.absolutePos(plaquePos);
            building.valid = false;
            data.setDirty(false);
            helper.assertTrue(plaque.applyBlessing(BlessingId.WARDEN_OATH)
                    == TargetBlessingState.ApplyResult.INVALID,
                "a dissolved/invalid Building cannot receive a seal through its old plaque");
            helper.assertTrue(!data.isDirty()
                    && building.blessingRank(BlessingId.WARDEN_OATH) == 0,
                "invalid-building refusal must be a no-mutation result");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 400,
        batch = "building_blessing_seal_item_conservation")
    public void physicalSealUseOnPlaqueConsumesOnlyWhenApplied(GameTestHelper helper) {
        settlement(helper);
        BlockPos room = new BlockPos(4, 0, 4);
        buildFarmRoom(helper, room);
        BlockPos plaqueRel = fitFarmPlaque(helper, room);

        helper.runAfterDelay(20, () -> {
            PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
            Building building = plaque.building(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID
                    && building != null && building.valid,
                "setup: the physical plaque must declare one live building");

            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            player.getAbilities().instabuild = false;
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.HEARTHWARD_SEAL.get(), 4));

            int screenOpensBeforeSeal = plaque.screenOpenCount();
            dispatchUseBlock(helper, plaqueRel, player);
            helper.assertTrue(player.getMainHandItem().getCount() == 3
                    && plaque.blessingRank(BlessingId.HEARTHWARD) == 1
                    && plaque.screenOpenCount() == screenOpensBeforeSeal,
                "the real block dispatch must reach the seal item without opening the plaque sheet");
            useSealOnPlaque(helper, player, plaqueRel);
            useSealOnPlaque(helper, player, plaqueRel);
            helper.assertTrue(player.getMainHandItem().getCount() == 1
                    && plaque.blessingRank(BlessingId.HEARTHWARD) == 3,
                "three APPLIED plaque uses must consume exactly three seals and reach III");

            useSealOnPlaque(helper, player, plaqueRel);
            helper.assertTrue(player.getMainHandItem().getCount() == 1
                    && plaque.blessingRank(BlessingId.HEARTHWARD) == 3,
                "MAXED plaque use must retain the fourth physical seal");

            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.WARDEN_OATH_SEAL.get(), 2));
            dispatchUseBlock(helper, plaqueRel, player);
            helper.assertTrue(player.getMainHandItem().getCount() == 2
                    && plaque.blessingRank(BlessingId.WARDEN_OATH) == 0,
                "ordinary plaque right-click must inspect without binding or consuming");

            player.setShiftKeyDown(true);
            player.getAbilities().instabuild = true;
            useSealOnPlaque(helper, player, plaqueRel);
            helper.assertTrue(player.getMainHandItem().getCount() == 2
                    && plaque.blessingRank(BlessingId.WARDEN_OATH) == 1,
                "creative plaque use must bind the rank while retaining the seal");

            player.getAbilities().instabuild = false;
            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.STONE, 2));
            player.setItemInHand(InteractionHand.OFF_HAND,
                new ItemStack(ModItems.THORNED_ROADS_SEAL.get(), 2));
            dispatchUseBlock(helper, plaqueRel, player);
            helper.assertTrue(player.getMainHandItem().is(Items.STONE)
                    && player.getMainHandItem().getCount() == 2
                    && player.getOffhandItem().getCount() == 1
                    && plaque.blessingRank(BlessingId.THORNED_ROADS) == 1,
                "a consuming main-hand BlockItem must not block plaque binding; "
                    + "only the actual offhand seal may shrink");

            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(ModItems.THORNED_ROADS_SEAL.get(), 2));
            building.valid = false;
            useSealOnPlaque(helper, player, plaqueRel);
            helper.assertTrue(player.getMainHandItem().getCount() == 2
                    && building.blessingRank(BlessingId.THORNED_ROADS) == 1,
                "INVALID plaque identity must retain the physical seal and remain inert");
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "building_blessing_unlinked_or_invalid_plaque_refuses_without_mutation")
    public void unlinkedOrInvalidPlaqueRefusesWithoutMutation(GameTestHelper helper) {
        BlockPos blankRel = new BlockPos(2, 2, 2);
        GameTestFixtures.placePlaque(helper, blankRel);
        PlaqueBlockEntity blank = plaqueAt(helper, blankRel);
        int originalRevision = blank.revision();
        PlaqueState originalState = blank.state();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.setDirty(false);

        helper.assertTrue(blank.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.INVALID,
            "an unlinked plaque must return INVALID so no seal is consumed");
        helper.assertTrue(blank.blessingRank(BlessingId.WARDEN_OATH) == 0,
            "an unlinked plaque cannot expose a phantom rank");
        helper.assertTrue(blank.revision() == originalRevision
                && blank.state() == originalState && blank.buildingId() == null,
            "INVALID must not rewrite the plaque while refusing the seal");
        helper.assertTrue(!data.isDirty(),
            "an unlinked INVALID result must not dirty SettlementSavedData");

        // A block entity beside a synthetic building is still not linked to
        // it. Identity is the persisted buildingId + exact plaquePos pair,
        // never proximity and never a global block-entity search.
        Settlement settlement = settlement(helper);
        Building nearby = new Building(UUID.randomUUID(), BuildingType.FARMHOUSE,
            helper.absolutePos(blankRel), helper.absolutePos(blankRel.below()),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(5, 4, 5))));
        nearby.valid = true;
        settlement.buildings.add(nearby);
        data.setDirty(false);
        helper.assertTrue(blank.applyBlessing(BlessingId.WARDEN_OATH)
                == TargetBlessingState.ApplyResult.INVALID
                && nearby.blessingRank(BlessingId.WARDEN_OATH) == 0,
            "a nearby valid building with no exact plaque link must remain untouched");
        helper.assertTrue(!data.isDirty(),
            "proximity-only INVALID must not dirty SettlementSavedData");
        helper.succeed();
    }

    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Sealstead",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    /** Proven 7x7 farmhouse fixture used by the plaque grace tests. */
    private static void buildFarmRoom(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(origin.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(origin.offset(3, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(3, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(origin.offset(1, 1, 1), Blocks.COMPOSTER);
        helper.setBlock(origin.offset(5, 1, 1), Blocks.CHEST);
        helper.setBlock(origin.offset(1, 2, 5), Blocks.TORCH);
    }

    private static BlockPos fitFarmPlaque(GameTestHelper helper, BlockPos origin) {
        BlockPos plaqueRel = origin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), PlaqueItemData.stamped(
                new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.FARMHOUSE)),
            "setup: the physical farmhouse plan must fit into the plaque");
        return plaqueRel;
    }

    private static PlaqueBlockEntity plaqueAt(GameTestHelper helper, BlockPos rel) {
        var blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        helper.assertTrue(blockEntity instanceof PlaqueBlockEntity,
            "setup: expected a plaque block entity at " + rel);
        return (PlaqueBlockEntity) blockEntity;
    }

    private static void useSealOnPlaque(GameTestHelper helper, ServerPlayer player,
                                        BlockPos plaqueRel) {
        useSealOnPlaque(helper, player, plaqueRel, InteractionHand.MAIN_HAND);
    }

    private static void useSealOnPlaque(GameTestHelper helper, ServerPlayer player,
                                        BlockPos plaqueRel, InteractionHand hand) {
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(plaqueAbs),
            Direction.NORTH, plaqueAbs, false);
        UseOnContext context = new UseOnContext(helper.getLevel(), player,
            hand, player.getItemInHand(hand), hit);
        player.getItemInHand(hand).getItem().useOn(context);
    }

    private static void dispatchUseBlock(GameTestHelper helper, BlockPos plaqueRel,
                                         ServerPlayer player) {
        try {
            helper.useBlock(plaqueRel, player);
        } catch (UnsupportedOperationException exception) {
            String message = exception.getMessage();
            if (message == null || !message.contains("may not be sent")) {
                throw exception;
            }
        }
    }
}

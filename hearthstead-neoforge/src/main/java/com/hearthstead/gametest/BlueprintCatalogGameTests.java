package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.YardScanner;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Blueprint-artist lane: the WORK YARD survey (owner, 26 Sep: "the buildings
 * must be unique to the job -- they don't even need a roof, like a lumberjack
 * camp"). See {@link YardScanner} and {@code PlaqueBlockEntity.surveyRoom}.
 *
 * <p>Every yard here is built block by block (never a synthetic
 * {@code GameTestFixtures.register}) and registers ONLY through its own
 * plaque's survey, exactly as a player's would:
 * <ul>
 *   <li>a fenced camp with a covered tool lean-to registers as a Lumber Camp;</li>
 *   <li>the same camp with no shelter, or with a gap in its fence, does not;</li>
 *   <li>a ROOM type (a House) never registers as a yard;</li>
 *   <li>an enclosed lumber camp ROOM still registers (save compatibility);</li>
 *   <li>a Lumberer hired at the open camp fells a real tree into its chest.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BlueprintCatalogGameTests {

    private static final String BATCH = "blueprint_yard";
    private static final String TEMPLATE = "empty32";
    /** The plaque on the lean-to's back wall, looking south into the yard. */
    private static final BlockPos YARD_PLAQUE = new BlockPos(16, 2, 13);
    private static final BlockPos YARD_CHEST = new BlockPos(17, 1, 13);

    // ------------------------------------------------------------ builders

    /** A 9x7 fenced lot (x 12..20, z 12..18), a plank back wall, an optional lean-to roof. */
    private static void buildYardCamp(GameTestHelper h, boolean shelter, boolean gap) {
        for (int x = 12; x <= 20; x++) {
            for (int z = 12; z <= 18; z++) {
                boolean edge = x == 12 || x == 20 || z == 12 || z == 18;
                if (edge) {
                    h.setBlock(new BlockPos(x, 1, z), Blocks.OAK_FENCE);
                }
            }
        }
        for (int x = 14; x <= 18; x++) {
            for (int y = 1; y <= 3; y++) {
                h.setBlock(new BlockPos(x, y, 12), x == 14 || x == 18 ? Blocks.OAK_LOG : Blocks.OAK_PLANKS);
            }
        }
        h.setBlock(new BlockPos(16, 1, 18), Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.SOUTH));
        if (gap) {
            h.setBlock(new BlockPos(12, 1, 15), Blocks.AIR);
        }
        if (shelter) {
            for (int y = 1; y <= 3; y++) {
                h.setBlock(new BlockPos(14, y, 14), Blocks.OAK_LOG);
                h.setBlock(new BlockPos(18, y, 14), Blocks.OAK_LOG);
            }
            for (int x = 14; x <= 18; x++) {
                for (int z = 12; z <= 14; z++) {
                    h.setBlock(new BlockPos(x, 4, z), Blocks.OAK_SLAB);
                }
            }
        }
        h.setBlock(new BlockPos(15, 1, 13), Blocks.CRAFTING_TABLE);
        h.setBlock(YARD_CHEST, Blocks.CHEST);
        // a lantern post in the east fence (a boundary column the yard survey reads)
        h.setBlock(new BlockPos(20, 1, 15), Blocks.OAK_LOG);
        h.setBlock(new BlockPos(20, 2, 15), Blocks.LANTERN);
        h.setBlock(YARD_PLAQUE, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.SOUTH));
    }

    private static PlaqueBlockEntity fitPlan(GameTestHelper h, BlockPos plaqueRel, BuildingType type) {
        ServerLevel level = h.getLevel();
        BlockPos abs = h.absolutePos(plaqueRel);
        h.assertTrue(level.getBlockEntity(abs) instanceof PlaqueBlockEntity, "a plaque stands at " + plaqueRel);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) level.getBlockEntity(abs);
        h.assertTrue(plaque.insertPlan(level, PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type)),
            "the plaque accepts a " + type.id() + " plan");
        return plaque;
    }

    private static String why(PlaqueBlockEntity plaque) {
        return plaque.state() + " " + (plaque.lastScanReason() == null ? "-" : plaque.lastScanReason().getString())
            + " " + plaque.lastSurvey();
    }

    // --------------------------------------------------------------- tests

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void yardCampWithShelterRegistersAsLumberCamp(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildYardCamp(h, true, false);
        YardScanner.Yard yard = YardScanner.scan(h.getLevel(), h.absolutePos(YARD_PLAQUE.south()));
        h.assertTrue(yard != null && yard.bounded() && yard.covered() >= YardScanner.MIN_COVERED && yard.entrances() == 1,
            "the fenced lot is a bounded yard with a covered shelter: "
                + (yard == null ? "null" : "bounded=" + yard.bounded() + " covered=" + yard.covered() + " area=" + yard.area()));
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.succeedWhen(() -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b != null && b.valid && b.type == BuildingType.LUMBER_CAMP,
                "an open-air camp registers as a valid Lumber Camp through its plaque: " + why(plaque));
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void yardWithoutShelterIsRefused(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildYardCamp(h, false, false);
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.runAtTickTime(200, () -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b == null || !b.valid, "a yard with no covered tool shelter must not register: " + why(plaque));
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void yardWithAGapInItsFenceIsRefused(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildYardCamp(h, true, true);
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.runAtTickTime(200, () -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b == null || !b.valid, "an unbounded lot (fence gap) must not register: " + why(plaque));
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void aHouseNeverRegistersAsAYard(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildYardCamp(h, true, false);
        BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT);
        h.setBlock(new BlockPos(13, 1, 16), foot);
        h.setBlock(new BlockPos(13, 1, 15), foot.setValue(BedBlock.PART, BedPart.HEAD));
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.HOUSE);
        h.assertTrue(BuildingType.HOUSE.validationMode() == BuildingType.ValidationMode.ROOM, "homes stay rooms");
        h.runAtTickTime(200, () -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b == null || !b.valid, "a House is a ROOM type and must never register as an open yard: " + why(plaque));
            h.succeed();
        });
    }

    /** A stone-walled lot whose west side (x=12, z 13..17) is left to the given fluid. */
    private static void buildStoneYard(GameTestHelper h, BlockState westEdge) {
        for (int x = 12; x <= 20; x++) {
            for (int z = 12; z <= 18; z++) {
                boolean edge = x == 12 || x == 20 || z == 12 || z == 18;
                if (edge) {
                    h.setBlock(new BlockPos(x, 1, z), Blocks.STONE_BRICKS);
                }
            }
        }
        for (int z = 13; z <= 17; z++) {
            h.setBlock(new BlockPos(12, 1, z), westEdge);
        }
        for (int x = 14; x <= 18; x++) {
            for (int y = 1; y <= 3; y++) {
                h.setBlock(new BlockPos(x, y, 12), Blocks.STONE_BRICKS);
            }
            for (int z = 12; z <= 14; z++) {
                h.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICK_SLAB);
            }
        }
        for (int y = 1; y <= 3; y++) {
            h.setBlock(new BlockPos(14, y, 14), Blocks.STONE_BRICKS);
            h.setBlock(new BlockPos(18, y, 14), Blocks.STONE_BRICKS);
        }
        h.setBlock(new BlockPos(16, 1, 18), Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.SOUTH));
        h.setBlock(new BlockPos(15, 1, 13), Blocks.CRAFTING_TABLE);
        h.setBlock(YARD_CHEST, Blocks.CHEST);
        h.setBlock(new BlockPos(20, 2, 15), Blocks.LANTERN);
        h.setBlock(YARD_PLAQUE, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.SOUTH));
    }

    /** Codex review P2: water is an unsafe edge, never a wall. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void aLotRingedByWaterIsNotAYard(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildStoneYard(h, Blocks.WATER.defaultBlockState());
        YardScanner.Yard yard = YardScanner.scan(h.getLevel(), h.absolutePos(YARD_PLAQUE.south()));
        h.assertTrue(yard != null && !yard.bounded() && yard.unsafeEdge(),
            "a water edge must read as an unsafe, unbounded lot: "
                + (yard == null ? "null" : "bounded=" + yard.bounded() + " unsafe=" + yard.unsafeEdge()));
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.runAtTickTime(200, () -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b == null || !b.valid, "a lot ringed by water must not register: " + why(plaque));
            h.succeed();
        });
    }

    /** Codex review P2: lava is an unsafe edge, never a wall. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void aLotRingedByLavaIsNotAYard(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildStoneYard(h, Blocks.LAVA.defaultBlockState());
        YardScanner.Yard yard = YardScanner.scan(h.getLevel(), h.absolutePos(YARD_PLAQUE.south()));
        h.assertTrue(yard != null && !yard.bounded() && yard.unsafeEdge(),
            "a lava edge must read as an unsafe, unbounded lot: "
                + (yard == null ? "null" : "bounded=" + yard.bounded() + " unsafe=" + yard.unsafeEdge()));
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.runAtTickTime(200, () -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b == null || !b.valid, "a lot ringed by lava must not register: " + why(plaque));
            h.succeed();
        });
    }

    /** The same stone lot, fully walled: the fluid tests fail for the fluid, nothing else. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void theSameStoneLotFullyWalledRegisters(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildStoneYard(h, Blocks.STONE_BRICKS.defaultBlockState());
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.succeedWhen(() -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b != null && b.valid && b.type == BuildingType.LUMBER_CAMP,
                "the walled stone lot registers as a yard: " + why(plaque));
        });
    }

    /** Codex review: a sealed lot whose only gate is walled up from outside has no entrance. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void aYardWhoseOnlyGateIsBlockedHasNoEntrance(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        buildYardCamp(h, true, false);
        h.setBlock(new BlockPos(16, 1, 19), Blocks.STONE_BRICKS);
        h.setBlock(new BlockPos(16, 2, 19), Blocks.STONE_BRICKS);
        YardScanner.Yard yard = YardScanner.scan(h.getLevel(), h.absolutePos(YARD_PLAQUE.south()));
        h.assertTrue(yard != null && yard.bounded() && yard.entrances() == 0,
            "a walled-up gate is not an entrance: " + (yard == null ? "null" : "entrances=" + yard.entrances()));
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        h.runAtTickTime(200, () -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b == null || !b.valid, "a yard with no entrance must not register: " + why(plaque));
            boolean namesDoors = plaque.lastSurvey().stream()
                .anyMatch(st -> st.requirement().id().equals("doors") && !st.met());
            h.assertTrue(namesDoors, "the plaque names the missing entrance (doors 0/1): " + why(plaque));
            h.succeed();
        });
    }

    /** Save compatibility: a lumber camp built as a closed room keeps registering exactly as before. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public void anEnclosedLumberCampRoomStillRegisters(GameTestHelper h) {
        BuilderTestKit.arena(h, 32, 8);
        for (int x = 12; x <= 17; x++) {
            for (int z = 12; z <= 17; z++) {
                boolean edge = x == 12 || x == 17 || z == 12 || z == 17;
                for (int y = 1; y <= 3; y++) {
                    h.setBlock(new BlockPos(x, y, z), edge ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
                h.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICKS);
            }
        }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        h.setBlock(new BlockPos(14, 1, 17), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        h.setBlock(new BlockPos(14, 2, 17), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        h.setBlock(new BlockPos(13, 1, 13), Blocks.CRAFTING_TABLE);
        h.setBlock(new BlockPos(16, 1, 13), Blocks.CHEST);
        h.setBlock(new BlockPos(15, 1, 13), Blocks.TORCH);
        BlockPos plaqueRel = new BlockPos(15, 2, 16);
        h.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = fitPlan(h, plaqueRel, BuildingType.LUMBER_CAMP);
        h.succeedWhen(() -> {
            Building b = plaque.building(h.getLevel());
            h.assertTrue(b != null && b.valid && b.type == BuildingType.LUMBER_CAMP,
                "an enclosed lumber camp still registers as a room: " + why(plaque));
        });
    }

    /**
     * The worker still works: a Lumberer hired at the OPEN camp (registered
     * through its plaque as a yard) fells a real oak and banks a log in the
     * camp's own chest.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 3000)
    public void aLumbererWorksTheOpenYardCamp(GameTestHelper h) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 32, 9);
        ServerLevel level = h.getLevel();
        buildYardCamp(h, true, false);
        Container chest = (Container) level.getBlockEntity(h.absolutePos(YARD_CHEST));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));
        // a real oak outside the lot, inside the settlement
        BlockPos base = new BlockPos(24, 1, 24);
        h.setBlock(base, Blocks.DIRT);
        for (int i = 1; i <= 4; i++) {
            h.setBlock(base.above(i), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx != 0 || dz != 0) {
                    h.setBlock(base.above(5).offset(dx, 0, dz), Blocks.OAK_LEAVES);
                }
            }
        }
        h.setBlock(base.above(5), Blocks.OAK_LEAVES);
        PlaqueBlockEntity plaque = fitPlan(h, YARD_PLAQUE, BuildingType.LUMBER_CAMP);
        Settlement s = arena.settlement();
        SettlerEntity[] ulf = {null};
        h.onEachTick(() -> {
            if (ulf[0] != null) {
                return;
            }
            Building camp = plaque.building(level);
            if (camp == null || !camp.valid) {
                return;
            }
            WorkZone zone = WorkZone.between(s.id, camp.id, WorkZone.Type.fromBuilding(camp.type),
                level.dimension().location(), h.absolutePos(new BlockPos(0, 0, 0)),
                h.absolutePos(new BlockPos(31, 9, 31)), 1);
            camp.commitWorkZone(0, zone);
            SettlerEntity settler = h.spawn(ModEntities.SETTLER.get(), new BlockPos(16, 1, 16));
            settler.setSettlerName("Ulf");
            settler.bindTo(s.id, s.center);
            s.putRecord(settler.getUUID(), "Ulf", Profession.NONE);
            h.assertTrue(Employment.hire(level, s, camp, settler).ok(), "the yard camp hires a lumberer");
            ulf[0] = settler;
        });
        h.succeedWhen(() -> {
            h.assertTrue(ulf[0] != null, "the camp registers and hires: " + why(plaque));
            int logs = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) {
                    logs += chest.getItem(i).getCount();
                }
            }
            h.assertTrue(logs > 0, "the lumberer fells the oak and banks a log in the open camp's chest (activity="
                + ulf[0].getActivity() + " pos=" + ulf[0].blockPosition() + ")");
        });
    }

    // ------------------------------------------------------ match town style

    /**
     * Owner: "make the blocks match what we have used most in our town". A
     * town built of stone brick walls, spruce log posts, a spruce roof and a
     * spruce floor re-skins the Elmfield cottage to spruce + stone brick; the
     * result still registers as an enclosed House through its plaque, and the
     * Builder's bill counts exactly the re-skinned blocks.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public void aSpruceAndStoneBrickTownReskinsAPreset(GameTestHelper h) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 32, 12);
        ServerLevel level = h.getLevel();
        Settlement s = arena.settlement();
        // the town's own building (x 20..26, z 2..8)
        for (int x = 20; x <= 26; x++) {
            for (int z = 2; z <= 8; z++) {
                boolean edgeX = x == 20 || x == 26;
                boolean edgeZ = z == 2 || z == 8;
                h.setBlock(new BlockPos(x, 0, z), Blocks.SPRUCE_PLANKS);
                for (int y = 1; y <= 3; y++) {
                    h.setBlock(new BlockPos(x, y, z), edgeX && edgeZ ? Blocks.SPRUCE_LOG
                        : (edgeX || edgeZ) ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
                h.setBlock(new BlockPos(x, 4, z), edgeX || edgeZ
                    ? Blocks.SPRUCE_STAIRS.defaultBlockState() : Blocks.SPRUCE_SLAB.defaultBlockState());
            }
        }
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        h.setBlock(new BlockPos(23, 1, 8), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        h.setBlock(new BlockPos(23, 2, 8), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        GameTestFixtures.registerWithBounds(h, s, BuildingType.HOUSE, new BlockPos(21, 1, 3), new BlockPos(22, 2, 5),
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                h.absolutePos(new BlockPos(20, 0, 2)), h.absolutePos(new BlockPos(26, 4, 8))));
        com.hearthstead.settlement.builder.TownStyle.invalidate(s.id);
        var palette = com.hearthstead.settlement.builder.TownStyle.scan(level, s);
        h.assertTrue("minecraft:stone_bricks".equals(palette.get(com.hearthstead.settlement.builder.TownPaletteRules.Role.WALL))
                && "minecraft:spruce_log".equals(palette.get(com.hearthstead.settlement.builder.TownPaletteRules.Role.FRAME))
                && "minecraft:spruce_planks".equals(palette.get(com.hearthstead.settlement.builder.TownPaletteRules.Role.FLOOR))
                && palette.get(com.hearthstead.settlement.builder.TownPaletteRules.Role.ROOF) != null
                && palette.get(com.hearthstead.settlement.builder.TownPaletteRules.Role.ROOF).startsWith("minecraft:spruce_"),
            "the scan reads the town: " + palette.chosen() + " from " + palette.counts());

        com.hearthstead.settlement.builder.Blueprint authored =
            com.hearthstead.settlement.builder.BlueprintLibrary.get(level.getServer(), "house_cottage");
        h.assertTrue(authored != null, "house_cottage loads");
        com.hearthstead.settlement.builder.Blueprint styled =
            com.hearthstead.settlement.builder.BlueprintStyles.apply(level, s, authored, true);
        java.util.Map<net.minecraft.world.level.block.Block, Integer> cells = new java.util.HashMap<>();
        for (var cell : styled.cells()) {
            cells.merge(cell.state().getBlock(), 1, Integer::sum);
        }
        h.assertTrue(cells.getOrDefault(Blocks.OAK_PLANKS, 0) == 0 && cells.getOrDefault(Blocks.OAK_LOG, 0) == 0
                && cells.getOrDefault(Blocks.OAK_STAIRS, 0) == 0,
            "no Elmfield oak is left in the re-skinned walls, frame or roof: " + cells);
        h.assertTrue(cells.getOrDefault(Blocks.STONE_BRICKS, 0) > 0 && cells.getOrDefault(Blocks.SPRUCE_LOG, 0) > 0
                && cells.getOrDefault(Blocks.SPRUCE_STAIRS, 0) > 0 && cells.getOrDefault(Blocks.SPRUCE_PLANKS, 0) > 0,
            "the preset uses the town's spruce and stone brick: " + cells);
        h.assertTrue(styled.cells().size() == authored.cells().size(), "same cells, only re-skinned");

        // the Builder's bill counts exactly the re-skinned blocks
        BlockPos origin = h.absolutePos(new BlockPos(4, 1, 14));
        var plan = com.hearthstead.settlement.builder.BuildPlanner.planBlueprint(level, s, styled, origin, 0, false, null);
        h.assertTrue(plan.validation().ok() && plan.job() != null,
            "the re-skinned cottage plans: " + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        var bill = com.hearthstead.settlement.builder.BuilderMaterials.total(plan.job());
        h.assertTrue(bill.getOrDefault(Items.SPRUCE_LOG, 0).intValue() == cells.getOrDefault(Blocks.SPRUCE_LOG, 0)
                && bill.getOrDefault(Items.STONE_BRICKS, 0).intValue() == cells.getOrDefault(Blocks.STONE_BRICKS, 0)
                && bill.getOrDefault(Items.OAK_PLANKS, 0) <= 1,
            "the bill matches the re-skinned cells (the plaque plan may add one plank): " + bill + " vs " + cells);

        // and the re-skinned cottage is still an enclosed, valid House
        for (var cell : styled.cells()) {
            level.setBlock(origin.offset(cell.x(), cell.y(), cell.z()), cell.state(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        int[] p = styled.meta().plaquePos();
        PlaqueBlockEntity plaque = fitPlan(h, new BlockPos(4 + p[0], 1 + p[1], 14 + p[2]), BuildingType.HOUSE);
        h.succeedWhen(() -> {
            Building b = plaque.building(level);
            h.assertTrue(b != null && b.valid && b.type == BuildingType.HOUSE,
                "the re-skinned cottage still registers as an enclosed House: " + why(plaque));
        });
    }

    // ------------------------------------------------------------ fishery

    /**
     * Main (26 Sep): a hired Builder builds fishery_small on DRY land from a
     * fresh warehouse holding exactly the bill (2 water buckets included);
     * the Builder pours the indoor basin; the finished boathouse registers as
     * a valid Fishery with at least 20 fishing water; and a hired Fisher lands
     * real fish into its rack.
     */
    @GameTest(template = TEMPLATE, batch = "blueprint_fishery", timeoutTicks = 40_000)
    public void aBuilderBuildsTheFisheryOnDryLandAndAFisherWorksIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        com.hearthstead.settlement.builder.Blueprint blueprint =
            com.hearthstead.settlement.builder.BlueprintLibrary.get(level.getServer(), "fishery_small");
        h.assertTrue(blueprint != null, "fishery_small loads");
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 32, blueprint.sizeY() + 3);
        Settlement s = arena.settlement();
        BlockPos originRel = new BlockPos(8, 1, 8);
        BlockPos origin = h.absolutePos(originRel);
        var plan = com.hearthstead.settlement.builder.BuildPlanner.planBlueprint(level, s, blueprint, origin, 0, false, null);
        h.assertTrue(plan.validation().ok() && plan.job() != null, "fishery_small plans on dry land: "
            + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        var job = plan.job();
        h.assertTrue(com.hearthstead.settlement.builder.BuildJobs.commit(level, s, job) == null, "the job enters the queue");
        var bill = com.hearthstead.settlement.builder.BuilderMaterials.total(job);
        h.assertTrue(bill.getOrDefault(Items.WATER_BUCKET, 0) >= 1, "the bill carries water buckets for the basin: " + bill);
        Building warehouse = GameTestFixtures.register(h, s, BuildingType.WAREHOUSE, 2, 24);
        java.util.List<Container> stores = new java.util.ArrayList<>();
        for (int x = 2; x <= 7; x++) {
            for (int z = 25; z <= 27; z++) {
                BlockPos at = new BlockPos(x, 1, z);
                h.setBlock(at, Blocks.BARREL);
                stores.add((Container) level.getBlockEntity(h.absolutePos(at)));
            }
        }
        for (var e : bill.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                h.assertTrue(com.hearthstead.settlement.builder.BuilderStock.insertAll(stores, new ItemStack(e.getKey(), n)).isEmpty(),
                    "the warehouse fixture holds the whole bill");
                left -= n;
            }
        }
        com.hearthstead.settlement.builder.BuilderStock.insertAll(stores, new ItemStack(Items.BREAD, 32));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.LADDER, 24));
        BuilderTestKit.hireBuilder(h, arena, new BlockPos(4, 1, 7), "Bram");

        int[] p = blueprint.meta().plaquePos();
        BlockPos plaqueAbs = origin.offset(p[0], p[1], p[2]);
        BlockPos rackAbs = null;
        BlockPos barrelAbs = null;
        for (var cell : blueprint.cells()) {
            BlockPos at = origin.offset(cell.x(), cell.y(), cell.z());
            if (cell.state().is(ModBlocks.FISH_RACK.get()) && rackAbs == null) {
                rackAbs = at;
            }
            if (cell.state().is(Blocks.BARREL) && (barrelAbs == null || at.distSqr(plaqueAbs) < barrelAbs.distSqr(plaqueAbs))) {
                barrelAbs = at;
            }
        }
        final BlockPos rack = rackAbs;
        final BlockPos rodBarrel = barrelAbs;
        SettlerEntity[] finn = {null};
        boolean[] sawFishing = {false};
        h.onEachTick(() -> {
            if (finn[0] != null) {
                if (finn[0].getActivity() == com.hearthstead.entity.SettlerActivity.WORK_FISH) {
                    sawFishing[0] = true;
                }
                return;
            }
            var live = BuilderTestKit.job(level, s, job.id);
            if (live == null || live.state != com.hearthstead.settlement.builder.BuildJob.State.COMPLETE) {
                return;
            }
            if (!(level.getBlockEntity(plaqueAbs) instanceof PlaqueBlockEntity plaque)) {
                return;
            }
            Building fishery = plaque.building(level);
            if (fishery == null || !fishery.valid) {
                return;
            }
            var grounds = com.hearthstead.settlement.work.FishingGrounds.scan(level, plaqueAbs);
            h.assertTrue(grounds.ready() && grounds.waterCount() >= 20,
                "the poured basin is real fishing water with a ready chair: " + grounds);
            WorkZone zone = WorkZone.between(s.id, fishery.id, WorkZone.Type.fromBuilding(fishery.type),
                level.dimension().location(), h.absolutePos(new BlockPos(0, 0, 0)), h.absolutePos(new BlockPos(31, 15, 31)), 1);
            fishery.commitWorkZone(0, zone);
            if (level.getBlockEntity(rodBarrel) instanceof Container c) {
                c.setItem(0, new ItemStack(ModItems.FISHERS_ROD.get()));
            }
            SettlerEntity settler = h.spawn(ModEntities.SETTLER.get(), originRel.offset(7, 0, 6));
            settler.setSettlerName("Finn");
            settler.bindTo(s.id, s.center);
            s.putRecord(settler.getUUID(), "Finn", Profession.NONE);
            h.assertTrue(Employment.hire(level, s, fishery, settler).ok(), "the built fishery hires a fisher");
            level.setDayTime(3000);
            finn[0] = settler;
        });
        h.succeedWhen(() -> {
            var live = BuilderTestKit.job(level, s, job.id);
            String plaqueWhy = level.getBlockEntity(plaqueAbs) instanceof PlaqueBlockEntity pl ? why(pl) : "no plaque yet";
            h.assertTrue(finn[0] != null, "the Builder finishes and the fishery registers: "
                + (live == null ? "job gone" : live.state + " " + live.status + " " + live.statusArgs
                    + " done " + live.doneCount() + "/" + live.size() + " skipped " + live.skipReport())
                + " | plaque " + plaqueWhy
                + " | grounds " + com.hearthstead.settlement.work.FishingGrounds.scan(level, plaqueAbs));
            int fish = 0;
            if (level.getBlockEntity(rack) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).is(net.minecraft.tags.ItemTags.FISHES)) {
                        fish += c.getItem(i).getCount();
                    }
                }
            }
            h.assertTrue(fish > 0 && sawFishing[0], "the fisher lands real fish into the boathouse rack (activity="
                + finn[0].getActivity() + " pos=" + finn[0].blockPosition() + " sawFishing=" + sawFishing[0] + ")");
        });
    }

    // ------------------------------------------------- every yard blueprint

    /** The shipped open-air (work yard) blueprints and their style presets. */
    static final java.util.List<String> YARD_IDS = java.util.List.of(
        "lumber_camp_small", "lumber_camp_timber", "lumber_camp_rustic",
        "mine_small", "mine_timber", "mine_rustic",
        "sawmill_small", "sawmill_timber", "sawmill_rustic",
        "smelter_small", "smelter_timber", "smelter_rustic",
        "smithy_small", "smithy_timber", "smithy_rustic",
        "tannery_small", "tannery_timber", "tannery_rustic",
        "mason_small", "mason_timber", "mason_rustic",
        "well_small", "well_timber", "well_rustic",
        "market_small", "market_timber", "market_rustic");

    /**
     * Every open-air blueprint, placed exactly as authored, registers through
     * its own plaque as a valid building of its type (the WORK YARD survey),
     * and a type with a trade takes on its worker.
     */
    @net.minecraft.gametest.framework.GameTestGenerator
    public static java.util.Collection<net.minecraft.gametest.framework.TestFunction> yardBlueprints() {
        java.util.List<net.minecraft.gametest.framework.TestFunction> out = new java.util.ArrayList<>();
        for (String id : YARD_IDS) {
            out.add(new net.minecraft.gametest.framework.TestFunction("blueprint_yard_catalog",
                "blueprint_yard_registers_" + id, Hearthstead.MODID + ":" + TEMPLATE,
                net.minecraft.world.level.block.Rotation.NONE, 400, 0L, true, h -> yardRegistersAndHires(h, id)));
        }
        return out;
    }

    static void yardRegistersAndHires(GameTestHelper h, String id) {
        ServerLevel level = h.getLevel();
        com.hearthstead.settlement.builder.Blueprint blueprint =
            com.hearthstead.settlement.builder.BlueprintLibrary.get(level.getServer(), id);
        h.assertTrue(blueprint != null, id + " loads");
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 32, Math.min(15, blueprint.sizeY() + 2));
        Settlement s = arena.settlement();
        BlockPos origin = h.absolutePos(new BlockPos(8, 1, 8));
        int below = blueprint.meta().groundLevel();
        for (var cell : blueprint.cells()) {
            level.setBlock(origin.offset(cell.x(), cell.y() - below, cell.z()), cell.state(),
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        BuildingType type = null;
        for (BuildingType t : BuildingType.values()) {
            if (t.id().equals(blueprint.meta().buildingType())) {
                type = t;
            }
        }
        h.assertTrue(type != null && type.validationMode() == BuildingType.ValidationMode.YARD_OR_ROOM,
            id + " is a yard-capable type: " + blueprint.meta().buildingType());
        int[] p = blueprint.meta().plaquePos();
        BlockPos plaqueRel = new BlockPos(8 + p[0], 1 + p[1] - below, 8 + p[2]);
        PlaqueBlockEntity plaque = fitPlan(h, plaqueRel, type);
        final BuildingType fType = type;
        boolean[] hired = {false};
        h.succeedWhen(() -> {
            Building b = plaque.building(level);
            h.assertTrue(b != null && b.valid && b.type == fType,
                id + " registers as a valid " + fType.id() + " (work yard): " + why(plaque));
            if (!hired[0]) {
                SettlerEntity settler = h.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 30));
                settler.setSettlerName("Wren");
                settler.bindTo(s.id, s.center);
                s.putRecord(settler.getUUID(), "Wren", Profession.NONE);
                var result = Employment.hire(level, s, b, settler);
                hired[0] = true;
                if (fType.employsWorkers()) {
                    h.assertTrue(result.ok() && settler.getProfession() != Profession.NONE,
                        id + " takes on its worker: " + result.refusal());
                }
            }
        });
    }
}

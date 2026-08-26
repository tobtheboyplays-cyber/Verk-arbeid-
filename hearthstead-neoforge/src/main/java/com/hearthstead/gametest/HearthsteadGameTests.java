package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Headless verification of the settlement loop. Run with
 * ./gradlew runGameTestServer — exits nonzero on any failure.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class HearthsteadGameTests {

    /**
     * Creates a settlement record directly (no founding flow). Test radii are
     * tight: the gametest world is a void with lava between platforms, and a
     * generous radius lets idle settlers stroll off the edge to their doom.
     */
    private static Settlement makeSettlement(ServerLevel level, BlockPos center, int radius) {
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm", center);
        s.radius = radius;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * Arena-scoped settlement: the gametest world is one shared level whose
     * structure columns are recycled between batches, so a finished test's
     * settlement can still sit in this arena and swallow our room
     * registrations. Purging by ARENA BOUNDS (not by distance) is exact — it
     * can only ever remove settlements standing in the space this test now
     * owns, never a neighbouring test running concurrently.
     */
    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel,
                                             int radius) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.phys.AABB arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        return makeSettlement(level, helper.absolutePos(centerRel), radius);
    }

    /**
     * The structure templates only reserve the test bounds — their contents
     * cannot be trusted (they placed nothing in this harness). Every AI test
     * therefore builds its own arena explicitly: a guaranteed-flat floor,
     * cleared air, and a 2-high perimeter wall so nobody wanders into the
     * hostile void terrain between test platforms.
     */
    private static void buildArena(GameTestHelper helper, int sizeX, int sizeZ) {
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                boolean rim = x == 0 || z == 0 || x == sizeX - 1 || z == sizeZ - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Arena variant with a hydrated field: farmland interior, water center. */
    private static void buildFarmArena(GameTestHelper helper, int size) {
        buildArena(helper, size, size);
        for (int x = 1; x < size - 1; x++) {
            for (int z = 1; z < size - 1; z++) {
                helper.setBlock(new BlockPos(x, 0, z),
                    Blocks.FARMLAND.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
            }
        }
        helper.setBlock(new BlockPos(size / 2, 0, size / 2), Blocks.WATER);
    }

    private static SettlerEntity boundSettler(GameTestHelper helper, Settlement s,
                                              BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Testar");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        return settler;
    }

    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void foundingSpawnsSettlers(GameTestHelper helper) {
        SettlementManager.ignoreFoundingDistance = true;
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        helper.succeedWhen(() -> {
            Settlement s = SettlementManager.at(helper.getLevel(), hearthAbs);
            helper.assertTrue(s != null, "settlement should be founded at the hearth");
            helper.assertTrue(s.population() == 3,
                "expected 3 initial settlers, got " + (s == null ? -1 : s.population()));
            List<SettlerEntity> loaded = SettlementManager.loadedMembers(helper.getLevel(), s);
            helper.assertTrue(loaded.size() == 3,
                "expected 3 live settler entities, got " + loaded.size());
        });
    }

    @GameTest(batch = "hearthstead", template = "empty5", timeoutTicks = 200)
    public void professionAssignmentEquipsTool(GameTestHelper helper) {
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 2);
        SettlerEntity settler = boundSettler(helper, s, new BlockPos(2, 1, 2));
        settler.assignProfession(Profession.FARMER);
        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getProfession() == Profession.FARMER,
                "profession should be FARMER");
            helper.assertTrue(settler.getItemBySlot(EquipmentSlot.MAINHAND)
                .is(Items.IRON_HOE), "farmer should hold an iron hoe");
            Settlement.SettlerRecord record = s.record(settler.getUUID());
            helper.assertTrue(record != null && record.profession == Profession.FARMER,
                "settlement record should show FARMER");
            helper.assertTrue(s.employed() == 1, "employed count should be 1");
        });
    }

    @GameTest(template = "farm9", timeoutTicks = 1600, batch = "hearthstead_day")
    public void farmerHarvestsAndDeposits(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildFarmArena(helper, 9);
        // Hearth sits over the water cell at the plot's center; radius 5
        // keeps everyone on the platform.
        BlockPos hearthRel = new BlockPos(4, 1, 4);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 5);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        // ANIM-1: replanting now consumes an actual withheld seed (chest-
        // truth conservation -- CLAUDE.md's "logistics must conserve
        // items"), so it depends on wheat's own drop RNG (0-3 seeds,
        // uniform -- ~25% chance of 0 per crop). 8 independent crops keeps
        // P(zero seeds from all of them) astronomically small instead of
        // the ~1.6% a 3-crop sample left it at.
        BlockPos[] crops = {
            new BlockPos(2, 1, 2), new BlockPos(6, 1, 2), new BlockPos(2, 1, 6),
            new BlockPos(6, 1, 6), new BlockPos(1, 1, 4), new BlockPos(7, 1, 4),
            new BlockPos(4, 1, 1), new BlockPos(4, 1, 7)
        };
        for (BlockPos crop : crops) {
            helper.setBlock(crop,
                Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        }
        SettlerEntity farmer = boundSettler(helper, s, new BlockPos(4, 1, 2));
        farmer.assignProfession(Profession.FARMER);

        helper.succeedWhen(() -> {
            if (!(helper.getLevel().getBlockEntity(hearthAbs)
                instanceof HearthBlockEntity hearth)) {
                helper.fail("hearth block entity missing");
                return;
            }
            boolean hasWheat = false;
            for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
                if (hearth.getInventory().getStackInSlot(i).is(Items.WHEAT)) {
                    hasWheat = true;
                    break;
                }
            }
            int bagged = 0;
            for (int i = 0; i < farmer.bag.getContainerSize(); i++) {
                bagged += farmer.bag.getItem(i).getCount();
            }
            helper.assertTrue(hasWheat, "hearth should have received wheat"
                + " [farmer act=" + farmer.getActivity()
                + " pos=" + farmer.blockPosition()
                + " bag=" + bagged
                + " alive=" + farmer.isAlive()
                + " prof=" + farmer.getProfession()
                + " day=" + helper.getLevel().getDayTime()
                + " crop0=" + helper.getBlockState(crops[0]) + "]");
            int replanted = 0;
            for (BlockPos crop : crops) {
                BlockState state = helper.getBlockState(crop);
                if (state.is(Blocks.WHEAT)
                    && state.getValue(CropBlock.AGE) < 7) {
                    replanted++;
                }
            }
            helper.assertTrue(replanted >= 1,
                "at least one crop should be replanted, saw " + replanted);
        });
    }

    /** ANIM-1: the single WORKING phase is now a real HARVEST-then-PLANT
     *  sequence, each with its own activity/clip. */
    @GameTest(template = "farm9", timeoutTicks = 1600, batch = "hearthstead_day")
    public void farmerActivityProgressesThroughHarvestAndPlant(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildFarmArena(helper, 9);
        BlockPos hearthRel = new BlockPos(4, 1, 4);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 5);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        helper.setBlock(new BlockPos(2, 1, 2),
            Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        SettlerEntity farmer = boundSettler(helper, s, new BlockPos(4, 1, 2));
        farmer.assignProfession(Profession.FARMER);

        final boolean[] sawHarvest = {false};
        final boolean[] sawPlant = {false};
        helper.succeedWhen(() -> {
            if (farmer.getActivity() == SettlerActivity.WORK_HARVEST) {
                sawHarvest[0] = true;
            }
            if (farmer.getActivity() == SettlerActivity.WORK_SOW) {
                sawPlant[0] = true;
            }
            helper.assertTrue(sawHarvest[0], "farmer should pass through WORK_HARVEST "
                + "(act=" + farmer.getActivity() + " planted=" + sawPlant[0] + ")");
            helper.assertTrue(sawPlant[0], "farmer should pass through WORK_SOW after "
                + "harvesting (act=" + farmer.getActivity() + ")");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "hearthstead_day")
    public void lumbererFellsTreeCleanly(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 5);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        // Build a small natural oak near the hearth: dirt base, 4 logs, leaf cap.
        BlockPos dirtRel = new BlockPos(10, 1, 10);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 4; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        BlockPos crown = baseRel.above(3);
        for (BlockPos leaf : new BlockPos[]{crown.north(), crown.south(),
            crown.east(), crown.west(), crown.above()}) {
            helper.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState());
        }
        // TEMPORARY DIAGNOSTIC (STALL-1 / lumberjack GameTest hunt) -- remove.
        com.hearthstead.entity.ai.LumbererWorkGoal.DEBUG_TRUNK_COLUMN =
            helper.absolutePos(dirtRel);
        SettlerEntity lumberer = boundSettler(helper, s, new BlockPos(6, 1, 8));
        lumberer.assignProfession(Profession.LUMBERER);

        helper.succeedWhen(() -> {
            for (int i = 0; i < 4; i++) {
                helper.assertTrue(!helper.getBlockState(baseRel.above(i)).is(Blocks.OAK_LOG),
                    "log " + i + " should be felled [lumberer act="
                        + lumberer.getActivity() + " pos=" + lumberer.blockPosition()
                        + " alive=" + lumberer.isAlive()
                        + " day=" + helper.getLevel().getDayTime() + "]");
            }
            helper.assertTrue(helper.getBlockState(baseRel).is(Blocks.OAK_SAPLING),
                "a sapling should be replanted on the stump");
            if (!(helper.getLevel().getBlockEntity(hearthAbs)
                instanceof HearthBlockEntity hearth)) {
                helper.fail("hearth block entity missing");
                return;
            }
            int logs = 0;
            for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
                ItemStack stack = hearth.getInventory().getStackInSlot(i);
                if (stack.is(Items.OAK_LOG)) {
                    logs += stack.getCount();
                }
            }
            helper.assertTrue(logs >= 4, "hearth should hold >= 4 oak logs, has " + logs);
        });
    }

    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 800)
    public void guardEngagesThreat(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8), 6);
        SettlerEntity guard = boundSettler(helper, s, new BlockPos(6, 1, 8));
        guard.assignProfession(Profession.GUARD);
        Zombie zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
            new BlockPos(10, 1, 8));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));

        helper.succeedWhen(() -> helper.assertTrue(
            zombie.isDeadOrDying()
                || (guard.getTarget() == zombie
                    && guard.getActivity() == SettlerActivity.COMBAT),
            "guard should engage (activity=" + guard.getActivity()
                + ", target=" + (guard.getTarget() != null) + ")"));
    }

    @GameTest(template = "empty16", timeoutTicks = 900, batch = "hearthstead_day")
    public void hungrySettlerEatsFromHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 5);
        HearthBlockEntity hearth =
            (HearthBlockEntity) helper.getLevel().getBlockEntity(hearthAbs);
        hearth.bindSettlement(s.id);
        hearth.insertGoods(new ItemStack(Items.BREAD, 8));
        SettlerEntity settler = boundSettler(helper, s, new BlockPos(5, 1, 8));
        settler.setHunger(20.0F);

        helper.succeedWhen(() -> helper.assertTrue(settler.getHunger() > 50.0F,
            "settler should have eaten (hunger=" + settler.getHunger()
                + ", act=" + settler.getActivity()
                + ", food=" + hearth.countFoodUnits() + ")"));
    }

    @GameTest(template = "empty16", timeoutTicks = 600, batch = "hearthstead_day")
    public void civilianFleesAndAlarmSounds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        SettlerEntity civilian = boundSettler(helper, s, new BlockPos(12, 1, 8));
        Zombie zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
            new BlockPos(13, 1, 8));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));

        // The injected targeting goal makes the zombie hunt the settler; the
        // first hit must raise the settlement alarm and send them fleeing.
        helper.succeedWhen(() -> helper.assertTrue(
            s.alertActive(helper.getLevel().getGameTime()),
            "alarm should be active (civilian act=" + civilian.getActivity()
                + " alive=" + civilian.isAlive()
                + " zombieTarget=" + (zombie.getTarget() != null) + ")"));
    }

    @GameTest(batch = "hearthstead", template = "empty5", timeoutTicks = 100)
    public void settlerNbtRoundTrip(GameTestHelper helper) {
        SettlerEntity original = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        original.setSettlerName("Runa");
        UUID settlementId = UUID.randomUUID();
        original.bindTo(settlementId, helper.absolutePos(new BlockPos(1, 1, 1)));
        original.assignProfession(Profession.LUMBERER);
        original.setHunger(42.5F);
        original.setEnergy(77.25F);
        original.bag.setItem(0, new ItemStack(Items.OAK_LOG, 5));

        CompoundTag tag = new CompoundTag();
        original.addAdditionalSaveData(tag);

        SettlerEntity copy = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 3));
        copy.readAdditionalSaveData(tag);

        helper.succeedWhen(() -> {
            helper.assertTrue(copy.getProfession() == Profession.LUMBERER,
                "profession survives NBT");
            helper.assertTrue(Math.abs(copy.getHunger() - 42.5F) < 0.01F,
                "hunger survives NBT");
            helper.assertTrue(Math.abs(copy.getEnergy() - 77.25F) < 0.01F,
                "energy survives NBT");
            helper.assertTrue(settlementId.equals(copy.getSettlementId()),
                "settlement binding survives NBT");
            helper.assertTrue(copy.bag.getItem(0).is(Items.OAK_LOG)
                && copy.bag.getItem(0).getCount() == 5, "bag survives NBT");
        });
    }

    @GameTest(batch = "hearthstead", template = "empty5", timeoutTicks = 100)
    public void settlerAppearanceSurvivesNbtRoundTrip(GameTestHelper helper) {
        SettlerEntity original = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(2, 1, 2));
        original.setAppearanceSeed(123456789);

        CompoundTag tag = new CompoundTag();
        original.addAdditionalSaveData(tag);

        SettlerEntity copy = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 3));
        copy.readAdditionalSaveData(tag);

        com.hearthstead.entity.SettlerAppearance originalLook = original.getAppearance();
        helper.succeedWhen(() -> {
            helper.assertTrue(copy.getAppearanceSeed() == 123456789,
                "appearance seed survives NBT");
            helper.assertTrue(copy.getAppearance().equals(originalLook),
                "decoded appearance survives NBT unchanged");
        });
    }

    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void spawnedSettlersHaveVariedAppearance(GameTestHelper helper) {
        SettlementManager.ignoreFoundingDistance = true;
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8), 8);
        List<com.hearthstead.entity.SettlerAppearance> looks = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            SettlerEntity settler = SettlementManager.spawnSettler(helper.getLevel(), s, false);
            helper.assertTrue(settler != null, "spawnSettler should return a settler");
            looks.add(settler.getAppearance());
        }
        long distinct = looks.stream().distinct().count();
        helper.succeedWhen(() -> helper.assertTrue(distinct > 1,
            "8 spawned settlers should not all share one identical appearance, got "
                + distinct + " distinct look(s)"));
    }

    /** RELEASE_GATE HIGH-1: a settler created by ANY path other than
     *  SettlementManager.spawnSettler (spawn egg, /summon, a raw
     *  helper.spawn as used by GameTest fixtures elsewhere in this file)
     *  must still get a real, non-degenerate appearance seed -- never the
     *  synced-data default of 0, which would make it a permanent visual
     *  clone of every other settler stuck at that default. */
    @GameTest(batch = "hearthstead", template = "empty5", timeoutTicks = 100)
    public void settlerSpawnedOutsideSettlementManagerGetsRealAppearance(GameTestHelper helper) {
        List<Integer> seeds = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
                new BlockPos(1 + i, 1, 1));
            seeds.add(settler.getAppearanceSeed());
        }
        helper.succeedWhen(() -> {
            for (int seed : seeds) {
                helper.assertTrue(seed != 0,
                    "a settler spawned outside SettlementManager must not keep the "
                        + "degenerate appearance seed 0, got " + seed);
            }
            helper.assertTrue(seeds.stream().distinct().count() > 1,
                "6 settlers spawned outside SettlementManager should not collide on one seed");
        });
    }

    @GameTest(batch = "hearthstead", template = "empty5", timeoutTicks = 100)
    public void savedDataRoundTrip(GameTestHelper helper) {
        Settlement s = new Settlement(UUID.randomUUID(), "Ashford",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        s.radius = 48;
        s.foodCache = 17;
        s.recruitProgress = 55;
        s.recruitTarget = 240;
        s.putRecord(UUID.randomUUID(), "Sigrun", Profession.GUARD);
        s.putRecord(UUID.randomUUID(), "Aldric", Profession.NONE);

        SettlementSavedData out = new SettlementSavedData();
        out.settlements.put(s.id, s);
        CompoundTag tag = out.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        SettlementSavedData in = SettlementSavedData.load(tag,
            helper.getLevel().registryAccess());

        Settlement loaded = in.settlements.get(s.id);
        helper.succeedWhen(() -> {
            helper.assertTrue(loaded != null, "settlement survives save/load");
            helper.assertTrue("Ashford".equals(loaded.name), "name survives");
            helper.assertTrue(loaded.center.equals(s.center), "center survives");
            helper.assertTrue(loaded.population() == 2, "records survive");
            helper.assertTrue(loaded.employed() == 1, "professions survive");
            helper.assertTrue(loaded.recruitProgress == 55, "recruit progress survives");
        });
    }

    // ================================================== room detection ===

    /**
     * Builds a 5x5 hut on the arena floor at {@code o} (rel): stone walls
     * y1..3, full roof at y4, oak door, bed, torch. Returns the bed HEAD rel
     * position.
     */
    private static BlockPos buildHut(GameTestHelper helper, BlockPos o) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean wall = x == 0 || z == 0 || x == 4 || z == 4;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(o.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // Door in the south wall (z=0), lower + upper half.
        helper.setBlock(o.offset(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(2, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        // Bed: foot at (2,1,2) facing north, head at (2,1,3).
        helper.setBlock(o.offset(2, 1, 2), Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING,
                net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        helper.setBlock(o.offset(2, 1, 3), Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING,
                net.minecraft.core.Direction.NORTH)
            .setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        helper.setBlock(o.offset(1, 2, 1), Blocks.TORCH);
        return o.offset(2, 1, 3);
    }


    /**
     * {@code helper.useBlock}, tolerating a harness-only side effect: a mock
     * player made by {@code makeMockServerPlayerInLevel} carries a
     * {@code Connection} that never runs NeoForge's real login/configuration
     * channel handshake, so the very first server-to-client custom payload
     * this mod ever sends it (the plaque snapshot, opening the screen) is
     * refused by {@code NetworkRegistry.hasChannel} with an
     * {@code UnsupportedOperationException}. That is a property of the fake
     * connection, not of this mod's networking — a real client always
     * completes the handshake before any screen could open. The interaction
     * itself (state change, the {@code screenOpens} counter) already
     * happened before the packet send is attempted, so this only silences the
     * expected send failure; any other exception still propagates.
     */
    private static void useBlockTolerateNoRealConnection(GameTestHelper helper, BlockPos pos,
                                                          Player player) {
        try {
            helper.useBlock(pos, player);
        } catch (UnsupportedOperationException e) {
            if (e.getMessage() == null || !e.getMessage().contains("may not be sent")) {
                throw e;
            }
        }
    }

    /**
     * A stamped Build Plan for {@code type} — what a player would insert into
     * a blank plaque (D-006).
     */
    private static ItemStack buildPlan(com.hearthstead.building.BuildingType type) {
        return com.hearthstead.block.PlaqueItemData.stamped(
            new ItemStack(com.hearthstead.registry.ModItems.BUILD_PLAN.get()), type);
    }

    /**
     * Hangs a blank plaque and fits it with a Build Plan for {@code type}, on
     * the hut's outside south wall, beside the door — the way the reference
     * image shows it and the way players naturally do it. Returns the plaque
     * position.
     *
     * <p>KF-001: the plaque is placed in the AIR CELL outside the wall, facing
     * NORTH into it, exactly as {@code getStateForPlacement} would when a
     * player right-clicks the wall's outer face — not by replacing a wall
     * block, which used to punch a hole in the room and made every enclosure
     * check fail. {@code canSurvive} then finds the wall behind it, and the
     * block entity's seed search reaches the room via its second candidate
     * (the mounted-outside case it was written for).
     *
     * <p>Fitting the plan through {@code insertPlan} directly rather than
     * through item use is because the harness has no player for most tests;
     * the block entity receives exactly the same call either way, and starts
     * surveying the moment the plan goes in.
     */
    private static BlockPos hangPlaque(GameTestHelper helper, BlockPos hutOrigin,
                                       com.hearthstead.building.BuildingType type) {
        // South wall is z = hutOrigin.z; hang one cell further out (z - 1),
        // facing NORTH so canSurvive's support check lands on the wall.
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, com.hearthstead.registry.ModBlocks.PLAQUE.get()
            .defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING,
                net.minecraft.core.Direction.NORTH));
        BlockPos abs = helper.absolutePos(plaqueRel);
        if (helper.getLevel().getBlockEntity(abs)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque) {
            plaque.insertPlan(helper.getLevel(), buildPlan(type));
        }
        return plaqueRel;
    }

    /**
     * The lamp is a real state signal and must actually change with the
     * survey. Pinned because I once claimed the plaque showed nothing,
     * having staged that check by data merge-ing the block entity's State
     * directly -- which never calls survey(), and survey() is what writes
     * this property. The claim was a measurement error; this test makes the
     * real behaviour impossible to mistake again.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void theLampTracksTheSurvey(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);

        // Blank plaque, nothing fitted: the lamp is dark.
        BlockPos blankRel = hutOrigin.offset(3, 2, -1);
        helper.setBlock(blankRel, com.hearthstead.registry.ModBlocks.PLAQUE.get()
            .defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING,
                net.minecraft.core.Direction.NORTH));
        helper.assertTrue(
            helper.getBlockState(blankRel)
                .getValue(com.hearthstead.block.PlaqueBlock.GLOW)
                == com.hearthstead.block.PlaqueBlock.Glow.EMPTY,
            "an unfitted plaque's lamp is dark, got "
                + helper.getBlockState(blankRel)
                    .getValue(com.hearthstead.block.PlaqueBlock.GLOW));

        // Fitted on a room that satisfies it: the lamp goes green.
        BlockPos plaqueRel = hangPlaque(helper, hutOrigin,
            com.hearthstead.building.BuildingType.HOUSE);

        helper.succeedWhen(() -> {
            var glow = helper.getBlockState(plaqueRel)
                .getValue(com.hearthstead.block.PlaqueBlock.GLOW);
            helper.assertTrue(glow != com.hearthstead.block.PlaqueBlock.Glow.EMPTY,
                "a fitted plaque's lamp must leave EMPTY once it has surveyed");
            helper.assertTrue(glow == com.hearthstead.block.PlaqueBlock.Glow.GREEN,
                "this hut satisfies a house, so the lamp must read GREEN, got "
                    + glow);
            // And the blank one beside it must still be dark, so the two are
            // genuinely distinguishable on the wall.
            helper.assertTrue(
                helper.getBlockState(blankRel)
                    .getValue(com.hearthstead.block.PlaqueBlock.GLOW)
                    == com.hearthstead.block.PlaqueBlock.Glow.EMPTY,
                "the unfitted plaque beside it must still read EMPTY");
        });
    }

    /**
     * The plaque's survey must reach the CLIENT. Verified in game that three
     * plaques in three different states rendered identically, because
     * saveAdditional carries type/state/revision/plan and nothing else -- so
     * the block entity renderer had no requirement data to draw and a player
     * had to run a command to learn whether their room passed.
     *
     * <p>Asserted on the update tag, which is what the client actually
     * receives, rather than on the server-side list, which was never the
     * thing that was missing.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void theSurveyReachesTheClient(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hangPlaque(helper, hutOrigin,
            com.hearthstead.building.BuildingType.HOUSE);

        helper.succeedWhen(() -> {
            var be = helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
            helper.assertTrue(be instanceof com.hearthstead.block.PlaqueBlockEntity,
                "the plaque block entity should exist");
            var plaque = (com.hearthstead.block.PlaqueBlockEntity) be;
            var tag = plaque.getUpdateTag(helper.getLevel().registryAccess());

            helper.assertTrue(tag.contains("Survey"),
                "the update tag must carry the survey -- without it the block "
                    + "renders identically in every state");
            var list = tag.getList("Survey", net.minecraft.nbt.Tag.TAG_COMPOUND);
            helper.assertTrue(!list.isEmpty(),
                "and it must carry the actual requirement lines, got none");
            helper.assertTrue(list.size()
                    == com.hearthstead.building.BuildingType.HOUSE.requirements().size(),
                "one line per requirement: got " + list.size() + " for "
                    + com.hearthstead.building.BuildingType.HOUSE.requirements().size()
                    + " requirements");

            boolean anyMet = false;
            for (int i = 0; i < list.size(); i++) {
                var entry = list.getCompound(i);
                helper.assertTrue(!entry.getString("Id").isEmpty(),
                    "each line must name its requirement");
                helper.assertTrue(entry.getInt("Needed") > 0,
                    "and say how many are needed");
                if (entry.getInt("Have") >= entry.getInt("Needed")) {
                    anyMet = true;
                }
                // The requirement must be reconstructible client-side by id,
                // since the counter function cannot cross the wire.
                helper.assertTrue(com.hearthstead.building.BuildingType.HOUSE
                        .requirementById(entry.getString("Id")) != null,
                    "unknown requirement id on the wire: " + entry.getString("Id"));
            }
            helper.assertTrue(anyMet,
                "this hut satisfies its house plaque, so some line must read met");
        });
    }

    /**
     * Every building type must stand for a DIFFERENT item.
     *
     * <p>The plaque draws its plan's emblem as a real Minecraft item, the way
     * an item frame does. That only tells a player anything if no two types
     * share one: a warehouse plaque and a house plaque have to differ at a
     * glance, before any text on the sheet is legible, and the whole point of
     * using vanilla items is that they do.
     *
     * <p>This is also what stops a seventh building type shipping blank. There
     * is no texture to forget any more -- the type either names an item or it
     * does not compile -- but nothing except this test stops someone naming
     * the same one twice.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 100)
    public void everyPlanHasItsOwnEmblem(GameTestHelper helper) {
        var seen = new java.util.HashMap<net.minecraft.world.item.Item, String>();
        for (var type : com.hearthstead.building.BuildingType.values()) {
            var emblem = type.emblem();
            helper.assertTrue(emblem != null
                    && emblem != net.minecraft.world.item.Items.AIR,
                type.id() + " has no emblem item to draw on its plan");
            String clash = seen.put(emblem, type.id());
            helper.assertTrue(clash == null,
                "two building types share one emblem: " + clash + " and "
                    + type.id() + " both use " + emblem);
        }
        helper.assertTrue(
            seen.size() == com.hearthstead.building.BuildingType.values().length,
            "one emblem per type: got " + seen.size() + " for "
                + com.hearthstead.building.BuildingType.values().length + " types");
        helper.succeed();
    }

    /**
     * Reads a sheet the way the CLIENT does: through the update tag, into a
     * fresh block entity that has never seen the server's own list. A sheet
     * built straight off the surveying block entity would pass even if the
     * survey never crossed the wire, which is the exact failure PLAQUE-2
     * step 1 existed to fix.
     */
    private static com.hearthstead.building.PlaqueSheet clientSheet(
            GameTestHelper helper, com.hearthstead.block.PlaqueBlockEntity server) {
        var received = new com.hearthstead.block.PlaqueBlockEntity(
            server.getBlockPos(), server.getBlockState());
        received.handleUpdateTag(server.getUpdateTag(helper.getLevel().registryAccess()),
            helper.getLevel().registryAccess());
        return com.hearthstead.building.PlaqueSheet.of(
            received.type(), received.state(), received.lastSurvey(),
            received.occupants(), received.capacity());
    }

    private static com.hearthstead.building.PlaqueSheet.Line lineNamed(
            GameTestHelper helper, com.hearthstead.building.PlaqueSheet sheet, String id) {
        for (var line : sheet.lines()) {
            if (line.id().equals(id)) {
                return line;
            }
        }
        helper.fail("the sheet has no line for '" + id + "'; it has "
            + sheet.lines().stream().map(com.hearthstead.building.PlaqueSheet.Line::id)
                .toList());
        return null;
    }

    /**
     * The point of the whole slice: the sheet must name WHAT is missing, and
     * change when the room changes. The lamp already says whether the room
     * passes; it cannot say that the bed is the thing that is gone.
     *
     * <p>Take the bed out of a hut that satisfied a house, and exactly one
     * line must flip — the beds line. If every line flipped, the sheet would
     * be reporting the plaque's state rather than the room's contents, which
     * is what the lamp is for.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void theSheetSaysWhatIsMissing(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hangPlaque(helper, hutOrigin,
            com.hearthstead.building.BuildingType.HOUSE);

        helper.runAfterDelay(20, () -> {
            var plaque = (com.hearthstead.block.PlaqueBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
            helper.assertTrue(plaque != null, "the plaque block entity should exist");
            plaque.survey(helper.getLevel());

            // Registered: the checklist has done its job and the sheet turns
            // over to its other face (step 3b, and the specification
            // correction recorded in the quality ledger, iteration 7).
            var sheet = clientSheet(helper, plaque);
            helper.assertTrue(!sheet.isBlank(),
                "a fitted plaque must write on its sheet");
            helper.assertTrue(sheet.complete(),
                "this hut satisfies a house, so the sheet must read as finished");
            helper.assertTrue(sheet.lines().size() == 1
                    && sheet.lines().get(0).id()
                        .equals(com.hearthstead.building.PlaqueSheet.OCCUPANCY_ID),
                "a registered building shows occupancy, not the finished checklist; got "
                    + sheet.lines().stream()
                        .map(com.hearthstead.building.PlaqueSheet.Line::id).toList());

            // Take the bed out. Both halves, or the block simply breaks itself.
            helper.setBlock(hutOrigin.offset(2, 1, 2), Blocks.AIR);
            helper.setBlock(hutOrigin.offset(2, 1, 3), Blocks.AIR);
            plaque.survey(helper.getLevel());

            var after = clientSheet(helper, plaque);
            helper.assertTrue(!after.complete(),
                "with the bed gone the sheet must not read as finished");
            helper.assertTrue(
                lineNamed(helper, after, "beds").ink()
                    == com.hearthstead.building.PlaqueSheet.Ink.UNMET,
                "the beds line must read unmet, got "
                    + lineNamed(helper, after, "beds").ink());
            for (String stillThere : new String[]{"doors", "lights", "floor_space"}) {
                helper.assertTrue(
                    lineNamed(helper, after, stillThere).ink()
                        == com.hearthstead.building.PlaqueSheet.Ink.MET,
                    "removing the bed must not disturb the " + stillThere
                        + " line, which read "
                        + lineNamed(helper, after, stillThere).ink());
            }
            helper.succeed();
        });
    }

    /**
     * The three-way ink, and the two glyphs.
     *
     * <p>Amber is not decoration: it is the difference between "you have not
     * started" and "you need one more lantern", and it is the same signal the
     * lamp in the board uses, so the two must not disagree.
     *
     * <p>The marks are pinned to U+2714/U+2718 on purpose. The lighter
     * U+2713/U+2717 pair that the mockup's font draws exists in vanilla only
     * through the unifont fallback, while the heavy pair is in
     * {@code nonlatin_european.png} and is always there. Swapping them back
     * would put a missing-glyph box beside every requirement.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 100)
    public void theSheetKnowsPartialFromMissing(GameTestHelper helper) {
        var lights = com.hearthstead.building.Requirement.lights(2);

        var blank = com.hearthstead.building.PlaqueSheet.of(
            com.hearthstead.building.BuildingType.HOUSE,
            com.hearthstead.building.PlaqueState.EMPTY,
            java.util.List.of(new com.hearthstead.building.Requirement.Status(lights, 2, 2)),
            0, 0);
        helper.assertTrue(blank.isBlank() && blank.lines().isEmpty(),
            "a plaque with no plan fitted writes nothing at all (D-006), got "
                + blank.lines().size() + " lines");

        var noRoom = com.hearthstead.building.PlaqueSheet.of(
            com.hearthstead.building.BuildingType.HOUSE,
            com.hearthstead.building.PlaqueState.PLAN_INSERTED_UNLINKED,
            java.util.List.of(), 0, 0);
        helper.assertTrue(!noRoom.isBlank() && noRoom.lines().size() == 1
                && noRoom.lines().get(0).id()
                    .equals(com.hearthstead.building.PlaqueSheet.STATE_ID),
            "a fitted plaque that found no room must say so, not go blank");

        var measured = com.hearthstead.building.PlaqueSheet.of(
            com.hearthstead.building.BuildingType.HOUSE,
            com.hearthstead.building.PlaqueState.LINKED_INCOMPLETE,
            java.util.List.of(
                new com.hearthstead.building.Requirement.Status(lights, 2, 2),
                new com.hearthstead.building.Requirement.Status(lights, 1, 2),
                new com.hearthstead.building.Requirement.Status(lights, 0, 2)),
            0, 0);
        var inks = measured.lines().stream()
            .map(com.hearthstead.building.PlaqueSheet.Line::ink).toList();
        helper.assertTrue(inks.equals(java.util.List.of(
                com.hearthstead.building.PlaqueSheet.Ink.MET,
                com.hearthstead.building.PlaqueSheet.Ink.PARTIAL,
                com.hearthstead.building.PlaqueSheet.Ink.UNMET)),
            "2/2 is met, 1/2 is partial and 0/2 is unmet, got " + inks);
        helper.assertTrue(
            com.hearthstead.building.PlaqueSheet.Ink.PARTIAL.colour()
                != com.hearthstead.building.PlaqueSheet.Ink.UNMET.colour(),
            "partial must not be painted the same as nothing at all");
        helper.assertTrue(!measured.complete(),
            "one unmet line means the sheet does not read as finished");

        helper.assertTrue(
            com.hearthstead.building.PlaqueSheet.Ink.MET.mark().equals("✔")
                && com.hearthstead.building.PlaqueSheet.Ink.UNMET.mark().equals("✘")
                && com.hearthstead.building.PlaqueSheet.Ink.PARTIAL.mark().equals("✘"),
            "the marks must stay the HEAVY pair U+2714/U+2718, which vanilla's "
                + "own bitmap font carries; the light pair is unifont-only and "
                + "renders as a box");
        helper.succeed();
    }

    /**
     * A registered house says who is in it, and a full one is visibly full.
     *
     * <p>The owner asked for this so a house that is full can be told from one
     * with a spare bed without opening anything — and asked, minutes later,
     * that there be NO working/not-working line beside it, because the lamp in
     * the board already says that better than a word can.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void theSheetSaysWhoLivesThere(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hangPlaque(helper, hutOrigin,
            com.hearthstead.building.BuildingType.HOUSE);

        helper.runAfterDelay(20, () -> {
            var plaque = (com.hearthstead.block.PlaqueBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
            helper.assertTrue(plaque != null, "the plaque block entity should exist");
            plaque.survey(helper.getLevel());

            // Registered and empty: the checklist has done its job, and the
            // sheet turns over to the one thing still worth reading.
            var empty = clientSheet(helper, plaque);
            helper.assertTrue(empty.lines().size() == 1,
                "a registered building shows occupancy, not the finished checklist; got "
                    + empty.lines().size() + " lines");
            var line = lineNamed(helper, empty,
                com.hearthstead.building.PlaqueSheet.OCCUPANCY_ID);
            helper.assertTrue(line.ink() == com.hearthstead.building.PlaqueSheet.Ink.SPACE,
                "an empty house has room, got " + line.ink());
            helper.assertTrue(plaque.capacity() == 1 && plaque.occupants() == 0,
                "one bed, nobody in it: got " + plaque.occupants() + "/"
                    + plaque.capacity());

            // Move someone in. The count follows the settler's bed claim, not
            // a number the plaque keeps for itself.
            SettlerEntity resident = boundSettler(helper, s, new BlockPos(8, 1, 8));
            resident.claimBed(helper.absolutePos(bedRel));
            plaque.survey(helper.getLevel());

            var full = clientSheet(helper, plaque);
            helper.assertTrue(plaque.occupants() == 1,
                "the resident claimed the only bed, got " + plaque.occupants());
            helper.assertTrue(
                lineNamed(helper, full, com.hearthstead.building.PlaqueSheet.OCCUPANCY_ID)
                    .ink() == com.hearthstead.building.PlaqueSheet.Ink.FULL,
                "one of one is full, got "
                    + lineNamed(helper, full,
                        com.hearthstead.building.PlaqueSheet.OCCUPANCY_ID).ink());

            // And the checklist must come back on its own when the room breaks.
            helper.setBlock(hutOrigin.offset(2, 1, 2), Blocks.AIR);
            helper.setBlock(hutOrigin.offset(2, 1, 3), Blocks.AIR);
            plaque.survey(helper.getLevel());
            var broken = clientSheet(helper, plaque);
            helper.assertTrue(
                lineNamed(helper, broken, "beds").ink()
                    == com.hearthstead.building.PlaqueSheet.Ink.UNMET,
                "with the bed gone the checklist returns, naming the beds line");
            helper.assertTrue(plaque.occupants() == 0 && plaque.capacity() == 0,
                "an unregistered building holds nobody, got " + plaque.occupants()
                    + "/" + plaque.capacity());
            helper.succeed();
        });
    }

    /**
     * Occupancy rides the WIRE and never the disk.
     *
     * <p>D-006: the plaque reads the settlement, it does not keep a copy of
     * it. A saved occupant count would be wrong the first time a settler died
     * in an unloaded chunk, and would then be wrong forever, because nothing
     * would ever contradict it. This is the test that makes that mistake
     * impossible to make quietly.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void occupancyNeverReachesTheDisk(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hangPlaque(helper, hutOrigin,
            com.hearthstead.building.BuildingType.HOUSE);

        helper.runAfterDelay(20, () -> {
            var plaque = (com.hearthstead.block.PlaqueBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
            helper.assertTrue(plaque != null, "the plaque block entity should exist");
            SettlerEntity resident = boundSettler(helper, s, new BlockPos(8, 1, 8));
            resident.claimBed(helper.absolutePos(bedRel));
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.occupants() == 1,
                "precondition: the plaque should see its one resident, got "
                    + plaque.occupants());

            var registries = helper.getLevel().registryAccess();
            var wire = plaque.getUpdateTag(registries);
            helper.assertTrue(wire.contains("Occupants") && wire.contains("Capacity"),
                "the client cannot draw an occupancy line it was never sent");
            helper.assertTrue(wire.getInt("Occupants") == 1,
                "and it must be the real count, got " + wire.getInt("Occupants"));

            var disk = plaque.saveWithoutMetadata(registries);
            helper.assertTrue(!disk.contains("Occupants") && !disk.contains("Capacity"),
                "occupancy must NEVER be persisted -- the settlement owns it "
                    + "(D-006); saved tag was " + disk);
            helper.succeed();
        });
    }

    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void roomDetectedAsHome(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);

        helper.succeedWhen(() -> {
            com.hearthstead.settlement.RoomScanner.Result diag =
                com.hearthstead.settlement.RoomScanner.scan(helper.getLevel(),
                    helper.absolutePos(bedRel));
            helper.assertTrue(s.validHomeCount() == 1,
                "expected 1 valid home, got " + s.validHomeCount()
                    + " diag=" + (diag == null ? "null-seed"
                        : "enc=" + diag.enclosed() + " sky=" + diag.skyLeak()
                        + " beds=" + diag.beds().size() + " doors=" + diag.doors()
                        + " lights=" + diag.lights() + " vol=" + diag.volume()));
            helper.assertTrue(s.validBedCount() == 1,
                "expected 1 bed, got " + s.validBedCount());
            helper.assertTrue(s.capacity() == 4,
                "capacity should be 3 founders + 1 bed, got " + s.capacity());
        });
    }

    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void leakyRoomRejected(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        helper.setBlock(hutOrigin.offset(2, 4, 2), Blocks.AIR); // roof hole
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);

        helper.runAfterDelay(100, () -> {
            com.hearthstead.settlement.RoomScanner.Result r =
                com.hearthstead.settlement.RoomScanner.scan(helper.getLevel(),
                    helper.absolutePos(bedRel));
            StringBuilder col = new StringBuilder();
            for (int y = 3; y <= 9; y++) {
                col.append(" y").append(y).append('=')
                   .append(helper.getBlockState(new BlockPos(8, y, 8))
                       .getBlock().getName().getString());
            }
            helper.assertTrue(s.validHomeCount() == 0,
                "leaky room must not register, got " + s.validHomeCount()
                    + " scan=" + (r == null ? "null" : "(enc=" + r.enclosed()
                        + " sky=" + r.skyLeak() + " vol=" + r.volume()
                        + " valid=" + r.validHome() + ")")
                    + " columnAboveHole:" + col);
            helper.succeed();
        });
    }

    /**
     * Failure-message diagnostics: what the settlement believes about its
     * homes right now, plus a live rescan at the settler's own anchor. Keeps
     * housing failures self-explaining instead of "assert false".
     */
    private static String homeDiag(GameTestHelper helper, Settlement s,
                                   SettlerEntity settler) {
        StringBuilder sb = new StringBuilder("homes=" + s.validHomeCount()
            + " buildings=" + s.buildings.size());
        // Census of every settlement that could have swallowed this room, and
        // a live rescan of the bed — tells us whether the room is invalid or
        // simply registered somewhere else.
        BlockPos bedAbs = settler.getClaimedBed() != null ? settler.getClaimedBed()
            : helper.absolutePos(new BlockPos(8, 1, 9));
        com.hearthstead.settlement.RoomScanner.Result live =
            com.hearthstead.settlement.RoomScanner.scan(helper.getLevel(), bedAbs);
        sb.append(" liveScan=").append(live == null ? "null-seed"
            : "(enc=" + live.enclosed() + " sky=" + live.skyLeak()
                + " beds=" + live.beds().size() + " doors=" + live.doors()
                + " lights=" + live.lights() + " vol=" + live.volume()
                + " valid=" + live.validHome() + ")");
        SettlementSavedData all = SettlementSavedData.get(helper.getLevel());
        sb.append(" buildingMgr[").append(all.buildingManager.stats()).append("]");
        sb.append(" settlements=").append(all.settlements.size());
        for (Settlement other : all.settlements.values()) {
            sb.append(other.id.equals(s.id) ? " MINE" : " other")
              .append("{c=").append(other.center)
              .append(" r=").append(other.radius)
              .append(" d=").append(String.format("%.1f",
                    Math.sqrt(other.center.distSqr(bedAbs))))
              .append(" holds=").append(other.inside(bedAbs))
              .append(" blds=").append(other.buildings.size()).append("}");
        }
        for (com.hearthstead.settlement.Building b : s.buildings) {
            sb.append(" [valid=").append(b.valid)
              .append(" beds=").append(b.beds.size())
              .append(" doors=").append(b.doorCount)
              .append(" lights=").append(b.lightSources)
              .append(" lastVal=").append(b.lastValidatedGameTime).append("]");
            com.hearthstead.settlement.RoomScanner.Result r =
                com.hearthstead.settlement.RoomScanner.scan(helper.getLevel(), b.anchor);
            sb.append(r == null ? " rescan=null-seed"
                : " rescan(enc=" + r.enclosed() + " sky=" + r.skyLeak()
                    + " beds=" + r.beds().size() + " doors=" + r.doors()
                    + " lights=" + r.lights() + " vol=" + r.volume() + ")");
        }
        sb.append(" gameTime=").append(helper.getLevel().getGameTime())
          .append(" dayTime=").append(helper.getLevel().getDayTime())
          .append(" energy=").append(settler.getEnergy());
        return sb.toString();
    }

    /**
     * Regression lock: a room that fails its scan must be re-checked, not
     * written off. The hut is scanned while it is still dark, then lit — with
     * no further scan request — and must register on a retry. (Placing the
     * torch through the helper fires no block event, so only the retry path
     * can save it.) Guards the class of bug where a single unlucky scan left
     * a finished house permanently unregistered.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 900)
    public void unlitRoomRegistersOnceLit(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        BlockPos torchRel = hutOrigin.offset(1, 2, 1);
        helper.setBlock(torchRel, Blocks.AIR);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);

        final boolean[] lit = {false};
        helper.succeedWhen(() -> {
            if (!lit[0]) {
                helper.assertTrue(s.validHomeCount() == 0,
                    "a dark room must not register as a home");
                helper.setBlock(torchRel, Blocks.TORCH);
                lit[0] = true;
                helper.fail("waiting for the retry scan to notice the light");
            }
            helper.assertTrue(s.validHomeCount() == 1,
                "lighting the room must register it on a retry, got "
                    + s.validHomeCount());
        });
    }

    /**
     * Regression lock: roofs are judged geometrically, so anything with a
     * collision shape roofs a room. A glass roof is a real build people make;
     * it also proves the check does not consult the light engine.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 400)
    public void glassRoofCountsAsRoofed(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(hutOrigin.offset(x, 4, z), Blocks.GLASS);
            }
        }
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);

        helper.succeedWhen(() -> helper.assertTrue(s.validHomeCount() == 1,
            "a glass-roofed room is roofed, got " + s.validHomeCount()));
    }

    @GameTest(template = "empty16", timeoutTicks = 900, batch = "night_sleep")
    public void settlerSleepsInClaimedBed(GameTestHelper helper) {
        helper.getLevel().setDayTime(16000); // deep night: REST phase
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);
        SettlerEntity settler = boundSettler(helper, s, new BlockPos(4, 1, 3));

        helper.succeedWhen(() -> {
            helper.assertTrue(settler.getClaimedBed() != null,
                "settler should claim the bed (act=" + settler.getActivity()
                    + " " + homeDiag(helper, s, settler) + ")");
            helper.assertTrue(settler.isSleeping(),
                "settler should sleep in the bed (act=" + settler.getActivity()
                    + " pos=" + settler.blockPosition()
                    + " " + homeDiag(helper, s, settler) + ")");
            helper.assertTrue(settler.getActivity() == SettlerActivity.SLEEPING,
                "a settler asleep in a bed should carry SLEEPING, not RESTING "
                    + "(SLEEP_IN_BED vs REST are different clips), got "
                    + settler.getActivity());
        });
    }

    /** Regression for the ANIM-1 RELEASE_GATE blocker: SLEEPING must
     *  actually restore energy (tickNeeds() used to only recognise RESTING),
     *  or a settler who goes to bed tired can never satisfy
     *  RestAtNightGoal's exit condition and is stuck asleep forever. Drives
     *  a full night, through dawn, and asserts the settler naturally wakes
     *  (WAKE_STRETCH fires, activity returns to IDLE) and is free to work
     *  again -- not still SLEEPING once energy has recovered past dawn. */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "night_wake")
    public void settlerWakesAtDawnWithRecoveredEnergy(GameTestHelper helper) {
        // Still deep in REST, a few hundred ticks short of RISE at 23000.
        // The village clock (DayPhase) now opens a waking phase before
        // dawn instead of running REST straight into working hours, so
        // "just before waking" moved earlier. The assertion below is
        // unchanged: drive a full night through dawn and require a
        // natural wake with recovered energy.
        helper.getLevel().setDayTime(22600);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);
        SettlerEntity settler = boundSettler(helper, s, new BlockPos(4, 1, 3));
        settler.setEnergy(20.0F); // realistic post-workday energy

        final boolean[] sawSleeping = {false};
        final boolean[] sawEnergyRiseWhileAsleep = {false};
        final float[] energyAtSleepStart = {-1.0F};
        helper.succeedWhen(() -> {
            if (settler.getActivity() == SettlerActivity.SLEEPING) {
                sawSleeping[0] = true;
                if (energyAtSleepStart[0] < 0) {
                    energyAtSleepStart[0] = settler.getEnergy();
                } else if (settler.getEnergy() > energyAtSleepStart[0] + 1.0F) {
                    sawEnergyRiseWhileAsleep[0] = true;
                }
            }
            helper.assertTrue(sawSleeping[0], "settler should have slept at some point "
                + "(act=" + settler.getActivity() + " energy=" + settler.getEnergy() + ")");
            helper.assertTrue(sawEnergyRiseWhileAsleep[0], "energy should rise while "
                + "SLEEPING, not just RESTING (BLOCKER-1 regression)");
            helper.assertTrue(settler.getEnergy() >= 60.0F,
                "settler should have recovered past the 60-energy wake threshold, has "
                    + settler.getEnergy());
            helper.assertTrue(!settler.isSleeping(),
                "settler must actually leave the bed once energy recovers past dawn");
            helper.assertTrue(settler.getActivity() != SettlerActivity.SLEEPING,
                "settler must not still carry SLEEPING once awake, got "
                    + settler.getActivity());
        });
    }

    /** ANIM-1: LumbererWorkGoal now inserts a WORK_LIMB beat between the
     *  last strike and the trip home, and hauls logs under HAULING_LOG. */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "hearthstead_day")
    public void lumbererLimbsThenHaulsAfterFelling(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        BlockPos dirtRel = new BlockPos(8, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 4; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(baseRel.above(4).offset(dx, 0, dz), Blocks.OAK_LEAVES);
            }
        }
        // TEMPORARY DIAGNOSTIC (STALL-1 / lumberjack GameTest hunt) -- remove.
        com.hearthstead.entity.ai.LumbererWorkGoal.DEBUG_TRUNK_COLUMN =
            helper.absolutePos(dirtRel);
        SettlerEntity lumberer = boundSettler(helper, s, new BlockPos(4, 1, 4));
        lumberer.assignProfession(Profession.LUMBERER);

        final boolean[] sawLimbing = {false};
        final boolean[] sawHauling = {false};
        helper.succeedWhen(() -> {
            if (lumberer.getActivity() == SettlerActivity.WORK_LIMB) {
                sawLimbing[0] = true;
            }
            if (lumberer.getActivity() == SettlerActivity.HAULING_LOG) {
                sawHauling[0] = true;
            }
            int logs = 0;
            if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
                for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
                    if (hearth.getInventory().getStackInSlot(i).is(Items.OAK_LOG)) {
                        logs += hearth.getInventory().getStackInSlot(i).getCount();
                    }
                }
            }
            helper.assertTrue(logs >= 1, "hearth should hold >= 1 oak log by now, has " + logs
                + " (act=" + lumberer.getActivity() + " limbed=" + sawLimbing[0]
                + " hauled=" + sawHauling[0] + ")");
            helper.assertTrue(sawLimbing[0], "lumberer should pass through WORK_LIMB "
                + "after felling and before the trip home");
            helper.assertTrue(sawHauling[0], "lumberer should carry HAULING_LOG on the "
                + "walk home, not plain WALK");
        });
    }

    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 600)
    public void homeInvalidatedWhenWallBroken(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        hangPlaque(helper, hutOrigin, com.hearthstead.building.BuildingType.HOUSE);

        final boolean[] broke = {false};
        helper.succeedWhen(() -> {
            if (!broke[0]) {
                helper.assertTrue(s.validHomeCount() == 1, "waiting for registration");
                // Tear a hole in the roof, then poke the manager the same way
                // the block-break event does.
                helper.setBlock(hutOrigin.offset(2, 4, 2), Blocks.AIR);
                // Exactly what a block change does in play: tell the plaques
                // near enough to care.
                data.buildingManager.nudgeNear(helper.getLevel(),
                    helper.absolutePos(hutOrigin.offset(2, 4, 2)));
                broke[0] = true;
                helper.assertTrue(false, "hole torn, waiting for invalidation");
            }
            helper.assertTrue(s.validHomeCount() == 0,
                "home should invalidate after roof breach, homes="
                    + s.validHomeCount());
            helper.assertTrue(s.capacity() == 3,
                "capacity should fall back to founders, got " + s.capacity());
        });
    }

    // ---------------------------------------------------------- PLAQUE-1 ---

    /**
     * W3: a plaque with no Build Plan fitted must never run a room scan at
     * all — not on placement, not on its periodic tick. Guards the class of
     * bug where an EMPTY plaque quietly does the same work a fitted one does.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 450)
    public void emptyPlaqueNeverScans(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING,
                net.minecraft.core.Direction.NORTH));
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }
        helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.EMPTY,
            "a freshly hung plaque with no plan must start EMPTY, got " + plaque.state());

        helper.runAfterDelay(400, () -> {
            helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.EMPTY,
                "an uninserted plaque must remain EMPTY, got " + plaque.state());
            helper.assertTrue(plaque.scanAttempts() == 0,
                "an EMPTY plaque must run zero scans over 400 ticks, ran "
                    + plaque.scanAttempts());
            helper.succeed();
        });
    }

    /**
     * W4: right-clicking an EMPTY plaque with an empty hand opens no screen at
     * all — just a hint. Inserting a Build Plan is the moment the screen (and
     * the surveyor) starts working, and it happens exactly once per click.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 100)
    public void emptyPlaqueOpensNoScreenUntilPlanInserted(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING,
                net.minecraft.core.Direction.NORTH));
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.useBlock(plaqueRel, player);
        helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.EMPTY,
            "an empty-hand click on an EMPTY plaque must not change its state, got "
                + plaque.state());
        helper.assertTrue(plaque.screenOpenCount() == 0,
            "right-clicking an EMPTY plaque must not open a screen, opened "
                + plaque.screenOpenCount() + " times");

        player.setItemInHand(InteractionHand.MAIN_HAND,
            buildPlan(com.hearthstead.building.BuildingType.HOUSE));
        useBlockTolerateNoRealConnection(helper, plaqueRel, player);
        helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
            "inserting a plan for an already-valid room should register it, got "
                + plaque.state());
        helper.assertTrue(plaque.screenOpenCount() == 1,
            "inserting a plan must open the screen exactly once, opened "
                + plaque.screenOpenCount() + " times");
        helper.succeed();
    }

    /**
     * W5: sneak-use with an empty hand on a fitted plaque pulls the exact
     * plan back out (same item, same stamped type) and dissolves the building
     * it declared — conservation (INV-3), not destruction.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 200)
    public void sneakUseWithEmptyHandExtractsPlanAndDissolvesBuilding(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hangPlaque(helper, hutOrigin,
            com.hearthstead.building.BuildingType.HOUSE);
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaqueAbs)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }
        helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
            "setup: expected a registered house, got " + plaque.state());
        helper.assertTrue(s.buildings.size() == 1,
            "setup: expected one building, got " + s.buildings.size());

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        helper.useBlock(plaqueRel, player);

        helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.EMPTY,
            "extracting the plan must return the plaque to EMPTY, got " + plaque.state());
        helper.assertTrue(s.buildings.isEmpty(),
            "extracting the plan must dissolve the building, buildings="
                + s.buildings.size());
        ItemStack held = player.getMainHandItem();
        helper.assertTrue(
            !held.isEmpty() && held.getItem() == com.hearthstead.registry.ModItems.BUILD_PLAN.get()
                && "house".equals(com.hearthstead.block.PlaqueItemData.typeOf(held)),
            "the player must receive the exact stamped house plan back, got " + held);
        helper.succeed();
    }

    /**
     * W2 (save-compat): a plaque tag written before PLAQUE-1's state rename
     * carries the old id {@code "linked"} plus a {@code Building} UUID. That
     * id must resolve forward to {@code LINKED_VALID}, not fall back to
     * {@code EMPTY} — a bare fallback would silently un-home every already
     * -registered building on the first post-update load. A test written only
     * against the new ids would pass while that bug shipped.
     */
    @GameTest(batch = "hearthstead", template = "empty16", timeoutTicks = 100)
    public void legacyPlaqueStateLoadsWithoutLosingBuilding(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        ServerLevel level = helper.getLevel();
        Settlement s = makeSettlement(helper, new BlockPos(2, 1, 2), 12);
        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        BlockPos bedRel = buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING,
                net.minecraft.core.Direction.NORTH));
        BlockPos plaqueAbs = helper.absolutePos(plaqueRel);
        if (!(level.getBlockEntity(plaqueAbs)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            helper.fail("plaque block entity missing");
            return;
        }

        // A building this plaque already declared, exactly as an old save
        // would have had before the rename ever happened.
        com.hearthstead.settlement.Building building = new com.hearthstead.settlement.Building(
            UUID.randomUUID(), com.hearthstead.building.BuildingType.HOUSE, plaqueAbs,
            helper.absolutePos(bedRel),
            new net.minecraft.world.level.levelgen.structure.BoundingBox(
                plaqueAbs.getX() - 3, plaqueAbs.getY() - 2, plaqueAbs.getZ() - 3,
                plaqueAbs.getX() + 3, plaqueAbs.getY() + 2, plaqueAbs.getZ() + 3));
        building.valid = true;
        s.buildings.add(building);

        // A tag exactly like a pre-rename save would write: the old "linked"
        // id, never "linked_valid" — this build has never written that id.
        CompoundTag legacy = new CompoundTag();
        legacy.putString("Type", "house");
        legacy.putString("State", "linked");
        legacy.putInt("Revision", 3);
        legacy.putUUID("Building", building.id);
        plaque.loadCustomOnly(legacy, level.registryAccess());

        helper.assertTrue(
            plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
            "legacy id 'linked' must resolve to LINKED_VALID, got " + plaque.state());
        helper.assertTrue(building.id.equals(plaque.buildingId()),
            "the building id must survive the legacy-tag load");
        helper.assertTrue(s.buildings.contains(building),
            "the building itself must still be registered after a legacy-tag load, buildings="
                + s.buildings.size());
        helper.succeed();
    }
}

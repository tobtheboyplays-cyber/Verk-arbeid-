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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm", center);
        s.radius = radius;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
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

    @GameTest(template = "empty16", timeoutTicks = 400)
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

    @GameTest(template = "empty5", timeoutTicks = 200)
    public void professionAssignmentEquipsTool(GameTestHelper helper) {
        Settlement s = makeSettlement(helper.getLevel(),
            helper.absolutePos(new BlockPos(2, 1, 2)), 2);
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

    @GameTest(template = "farm9", timeoutTicks = 1600)
    public void farmerHarvestsAndDeposits(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildFarmArena(helper, 9);
        // Hearth sits over the water cell at the plot's center; radius 5
        // keeps everyone on the platform.
        BlockPos hearthRel = new BlockPos(4, 1, 4);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper.getLevel(), hearthAbs, 5);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        BlockPos[] crops = {
            new BlockPos(2, 1, 2), new BlockPos(6, 1, 2), new BlockPos(2, 1, 6)
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

    @GameTest(template = "empty16", timeoutTicks = 1600)
    public void lumbererFellsTreeCleanly(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper.getLevel(), hearthAbs, 5);
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

    @GameTest(template = "empty16", timeoutTicks = 800)
    public void guardEngagesThreat(GameTestHelper helper) {
        buildArena(helper, 16, 16);
        Settlement s = makeSettlement(helper.getLevel(),
            helper.absolutePos(new BlockPos(8, 1, 8)), 6);
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

    @GameTest(template = "empty16", timeoutTicks = 900)
    public void hungrySettlerEatsFromHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper.getLevel(), hearthAbs, 5);
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

    @GameTest(template = "empty16", timeoutTicks = 600)
    public void civilianFleesAndAlarmSounds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = makeSettlement(helper.getLevel(), hearthAbs, 6);
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

    @GameTest(template = "empty5", timeoutTicks = 100)
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

    @GameTest(template = "empty5", timeoutTicks = 100)
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
        CompoundTag tag = out.save(new CompoundTag());
        SettlementSavedData in = SettlementSavedData.load(tag);

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
}

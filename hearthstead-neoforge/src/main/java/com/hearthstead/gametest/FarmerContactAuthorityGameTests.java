package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Server-authoritative animation-contact tests for the three demo farmer
 * actions.  Each test crosses both sides of the dangerous persistence seam:
 * a full entity reload interrupts anticipation before contact, then a second
 * reload follows the accepted contact.  World/item truth must therefore be
 * absent before the authored beat, commit once on that beat, and remain
 * conserved without a replay after a fresh goal instance starts.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FarmerContactAuthorityGameTests {
    private static final int ARENA_SIZE = 16;
    private static final int PRE_CONTACT_RELOAD_LEAD = 3;
    private static final int POST_CONTACT_OBSERVATION_TICKS = 30;

    private record Fixture(Container chest, SettlerEntity farmer,
                           BlockPos target) {}

    private static void buildArena(GameTestHelper helper) {
        for (int x = 0; x < ARENA_SIZE; x++) {
            for (int z = 0; z < ARENA_SIZE; z++) {
                boolean rim = x == 0 || z == 0
                    || x == ARENA_SIZE - 1 || z == ARENA_SIZE - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Fixture fixture(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper);

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Kontaktgard",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 6;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        Building farmhouse = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 8, 8);
        BlockPos chestRel = new BlockPos(10, 1, 10);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(chestRel));
        helper.assertTrue(chest != null,
            "fixture: farmhouse storage must be a real loaded container");

        SettlerEntity farmer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(8, 1, 8));
        farmer.setSettlerName("Astrid Contact");
        farmer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(farmer.getUUID(), "Astrid Contact", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                farmhouse, farmer).ok(),
            "fixture: the registered farmhouse must hire its farmer");
        farmer.attributes().pinForTest(
            com.hearthstead.entity.Attribute.DEXTERITY, 10);

        return new Fixture(chest, farmer, new BlockPos(9, 1, 8));
    }

    private static SettlerEntity reload(GameTestHelper helper,
                                        SettlerEntity original) {
        ServerLevel level = helper.getLevel();
        UUID expectedId = original.getUUID();
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);

        SettlerEntity replacement = ModEntities.SETTLER.get().create(level);
        helper.assertTrue(replacement != null,
            "fixture: a replacement settler must be constructible");
        replacement.load(saved);
        helper.assertTrue(expectedId.equals(replacement.getUUID()),
            "full NBT reload must retain the employed farmer UUID");
        helper.assertTrue(level.addFreshEntity(replacement),
            "the reloaded farmer must re-enter the real ServerLevel");
        return replacement;
    }

    private static int countIn(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countInBag(SettlerEntity settler, Item item) {
        int total = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int seedMatter(GameTestHelper helper, Fixture fixture,
                                  SettlerEntity farmer) {
        int crop = helper.getBlockState(fixture.target()).is(Blocks.WHEAT) ? 1 : 0;
        return crop + countIn(fixture.chest(), Items.WHEAT_SEEDS)
            + countInBag(farmer, Items.WHEAT_SEEDS);
    }

    /** FARM_PLANT: tagged seed leaves storage/bag exactly at t=0.70 s. */
    @GameTest(template = "empty16", timeoutTicks = 900,
        batch = "farmer_contact_authority")
    public void plantContactSurvivesInterruptionAndCannotReplay(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        fixture.chest().setItem(0, new ItemStack(Items.WHEAT_SEEDS));
        fixture.chest().setItem(1, new ItemStack(Items.IRON_HOE));
        helper.setBlock(fixture.target().below(),
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));

        final SettlerEntity[] active = {fixture.farmer()};
        final long[] firstStart = {Long.MIN_VALUE};
        final long[] restartStart = {Long.MIN_VALUE};
        final long[] committedAt = {Long.MIN_VALUE};
        final long[] postReloadAt = {Long.MIN_VALUE};
        final boolean[] interrupted = {false};

        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            SettlerEntity farmer = active[0];

            if (!interrupted[0]) {
                if (firstStart[0] == Long.MIN_VALUE
                    && farmer.getActivity() == SettlerActivity.WORK_PLANT) {
                    firstStart[0] = now;
                }
                if (firstStart[0] != Long.MIN_VALUE) {
                    long delta = now - firstStart[0];
                    if (delta < FarmerWorkGoal.PLANT_CONTACT_TICK) {
                        helper.assertTrue(
                            helper.getBlockState(fixture.target()).isAir(),
                            "FARM_PLANT must not place a crop before contact +"
                                + FarmerWorkGoal.PLANT_CONTACT_TICK
                                + " (observed +" + delta + ")");
                        helper.assertTrue(seedMatter(helper, fixture, farmer) == 1,
                            "pre-contact planting must conserve its one physical seed");
                    }
                    if (delta == FarmerWorkGoal.PLANT_CONTACT_TICK
                            - PRE_CONTACT_RELOAD_LEAD) {
                        active[0] = reload(helper, farmer);
                        interrupted[0] = true;
                        return;
                    }
                }
                return;
            }

            farmer = active[0];
            if (committedAt[0] == Long.MIN_VALUE) {
                if (restartStart[0] == Long.MIN_VALUE
                    && farmer.getActivity() == SettlerActivity.WORK_PLANT) {
                    restartStart[0] = now;
                }
                if (restartStart[0] == Long.MIN_VALUE) {
                    helper.assertTrue(
                        helper.getBlockState(fixture.target()).isAir()
                            && seedMatter(helper, fixture, farmer) == 1,
                        "reload before FARM_PLANT contact must remain mutation-free");
                    return;
                }
                long delta = now - restartStart[0];
                boolean planted = helper.getBlockState(fixture.target())
                    .is(Blocks.WHEAT);
                if (delta < FarmerWorkGoal.PLANT_CONTACT_TICK) {
                    helper.assertTrue(!planted,
                        "restarted FARM_PLANT committed early at +" + delta);
                    helper.assertTrue(seedMatter(helper, fixture, farmer) == 1,
                        "restarted anticipation must conserve the exact seed");
                }
                if (planted) {
                    helper.assertTrue(delta == FarmerWorkGoal.PLANT_CONTACT_TICK,
                        "crop must appear exactly on FARM_PLANT contact +"
                            + FarmerWorkGoal.PLANT_CONTACT_TICK
                            + " (observed +" + delta + ")");
                    helper.assertTrue(seedMatter(helper, fixture, farmer) == 1,
                        "accepted planting contact must convert one seed into one crop");
                    committedAt[0] = now;
                    active[0] = reload(helper, farmer);
                    postReloadAt[0] = now;
                    return;
                }
                return;
            }

            if (now - postReloadAt[0] >= POST_CONTACT_OBSERVATION_TICKS) {
                helper.assertTrue(helper.getBlockState(fixture.target())
                        .is(Blocks.WHEAT),
                    "post-contact reload must retain the committed crop");
                helper.assertTrue(seedMatter(helper, fixture, active[0]) == 1,
                    "post-contact replay must not duplicate or lose seed matter");
                helper.succeed();
            }
        });
    }

    private static int harvestMatter(GameTestHelper helper, SettlerEntity farmer, Container chest) {
        var bounds = new net.minecraft.world.phys.AABB(
            net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(0,0,0))),
            net.minecraft.world.phys.Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16,8,16))));
        int world = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            bounds, item -> item.isAlive() && item.getItem().is(Items.WHEAT)).stream()
            .mapToInt(item -> item.getItem().getCount()).sum();
        int hand = farmer.getOffhandItem().is(Items.WHEAT) ? farmer.getOffhandItem().getCount() : 0;
        return world + hand + countInBag(farmer, Items.WHEAT) + countIn(chest, Items.WHEAT);
    }

    /** FARM_HARVEST: crop pull, stamped physical output and receipt share tick 9. */
    @GameTest(template = "empty16", timeoutTicks = 900,
        batch = "farmer_contact_authority")
    public void harvestContactSurvivesInterruptionAndCannotReplay(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        fixture.chest().setItem(0, new ItemStack(Items.IRON_HOE));
        helper.setBlock(fixture.target().below(),
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(fixture.target(), Blocks.WHEAT.defaultBlockState()
            .setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        final SettlerEntity[] active = {fixture.farmer()};
        final long[] firstStart = {Long.MIN_VALUE};
        final long[] restartStart = {Long.MIN_VALUE};
        final long[] committedAt = {Long.MIN_VALUE};
        final long[] postReloadAt = {Long.MIN_VALUE};
        final boolean[] interrupted = {false};

        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            SettlerEntity farmer = active[0];

            if (!interrupted[0]) {
                if (firstStart[0] == Long.MIN_VALUE
                    && farmer.getActivity() == SettlerActivity.WORK_HARVEST) {
                    firstStart[0] = now;
                }
                if (firstStart[0] != Long.MIN_VALUE) {
                    long delta = now - firstStart[0];
                    if (delta < FarmerWorkGoal.HARVEST_CONTACT_TICK) {
                        helper.assertTrue(helper.getBlockState(fixture.target())
                                .is(Blocks.WHEAT)
                                && harvestMatter(helper, farmer, fixture.chest()) == 0,
                            "FARM_HARVEST must keep crop/output physical before contact +"
                                + FarmerWorkGoal.HARVEST_CONTACT_TICK
                                + " (observed +" + delta + ")");
                    }
                    if (delta == FarmerWorkGoal.HARVEST_CONTACT_TICK
                            - PRE_CONTACT_RELOAD_LEAD) {
                        active[0] = reload(helper, farmer);
                        interrupted[0] = true;
                        return;
                    }
                }
                return;
            }

            farmer = active[0];
            if (committedAt[0] == Long.MIN_VALUE) {
                if (restartStart[0] == Long.MIN_VALUE
                    && farmer.getActivity() == SettlerActivity.WORK_HARVEST) {
                    restartStart[0] = now;
                }
                if (restartStart[0] == Long.MIN_VALUE) {
                    helper.assertTrue(helper.getBlockState(fixture.target())
                            .is(Blocks.WHEAT)
                            && harvestMatter(helper, farmer, fixture.chest()) == 0,
                        "reload before FARM_HARVEST contact must remain mutation-free");
                    return;
                }
                long delta = now - restartStart[0];
                boolean pulled = helper.getBlockState(fixture.target()).isAir();
                if (delta < FarmerWorkGoal.HARVEST_CONTACT_TICK) {
                    helper.assertTrue(!pulled && harvestMatter(helper, farmer, fixture.chest()) == 0,
                        "restarted FARM_HARVEST committed early at +" + delta);
                }
                if (pulled) {
                    helper.assertTrue(delta == FarmerWorkGoal.HARVEST_CONTACT_TICK,
                        "crop must leave the world exactly on FARM_HARVEST contact +"
                            + FarmerWorkGoal.HARVEST_CONTACT_TICK
                            + " (observed +" + delta + ")");
                    helper.assertTrue(harvestMatter(helper, farmer, fixture.chest()) == 1,
                        "accepted harvest contact must make exactly one wheat physical across world/hand/bag/storage");
                    committedAt[0] = now;
                    active[0] = reload(helper, farmer);
                    postReloadAt[0] = now;
                    return;
                }
                return;
            }

            if (now - postReloadAt[0] >= POST_CONTACT_OBSERVATION_TICKS) {
                int wheat = harvestMatter(helper, active[0], fixture.chest());
                var postContactCrop = helper.getBlockState(fixture.target());
                helper.assertTrue(!postContactCrop.is(Blocks.WHEAT)
                        || postContactCrop.getValue(CropBlock.AGE)
                            < CropBlock.MAX_AGE,
                    "post-contact reload must not replay or restore the pulled "
                        + "mature crop; an ordinary age-0 replant is valid (saw "
                        + postContactCrop + ")");
                helper.assertTrue(wheat == 1,
                    "post-contact harvest replay must conserve exactly one wheat, got "
                        + wheat);
                helper.succeed();
            }
        });
    }

    /** FARM_WATER: moisture changes exactly at the visible t=0.80 s pour. */
    @GameTest(template = "empty16", timeoutTicks = 900,
        batch = "farmer_contact_authority")
    public void waterContactSurvivesInterruptionAndCannotReplay(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        fixture.chest().setItem(0, new ItemStack(Items.IRON_HOE));
        helper.setBlock(fixture.target().below(),
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
        helper.setBlock(fixture.target(), Blocks.WHEAT.defaultBlockState()
            .setValue(CropBlock.AGE, 1));

        final SettlerEntity[] active = {fixture.farmer()};
        final long[] firstStart = {Long.MIN_VALUE};
        final long[] restartStart = {Long.MIN_VALUE};
        final long[] committedAt = {Long.MIN_VALUE};
        final long[] postReloadAt = {Long.MIN_VALUE};
        final boolean[] interrupted = {false};
        final boolean[] leftCommittedWaterPose = {false};

        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            SettlerEntity farmer = active[0];
            int moisture = helper.getBlockState(fixture.target().below())
                .getValue(FarmBlock.MOISTURE);

            if (!interrupted[0]) {
                if (firstStart[0] == Long.MIN_VALUE
                    && farmer.getActivity() == SettlerActivity.WORK_WATER) {
                    firstStart[0] = now;
                }
                if (firstStart[0] != Long.MIN_VALUE) {
                    long delta = now - firstStart[0];
                    if (delta < FarmerWorkGoal.WATER_CONTACT_TICK) {
                        helper.assertTrue(moisture == 0,
                            "FARM_WATER must not change moisture before contact +"
                                + FarmerWorkGoal.WATER_CONTACT_TICK
                                + " (observed +" + delta + ")");
                    }
                    if (delta == FarmerWorkGoal.WATER_CONTACT_TICK
                            - PRE_CONTACT_RELOAD_LEAD) {
                        active[0] = reload(helper, farmer);
                        interrupted[0] = true;
                        return;
                    }
                }
                return;
            }

            farmer = active[0];
            if (committedAt[0] == Long.MIN_VALUE) {
                if (restartStart[0] == Long.MIN_VALUE
                    && farmer.getActivity() == SettlerActivity.WORK_WATER) {
                    restartStart[0] = now;
                }
                if (restartStart[0] == Long.MIN_VALUE) {
                    helper.assertTrue(moisture == 0,
                        "reload before FARM_WATER contact must remain mutation-free");
                    return;
                }
                long delta = now - restartStart[0];
                if (delta < FarmerWorkGoal.WATER_CONTACT_TICK) {
                    helper.assertTrue(moisture == 0,
                        "restarted FARM_WATER committed early at +" + delta);
                }
                if (moisture == 7) {
                    helper.assertTrue(delta == FarmerWorkGoal.WATER_CONTACT_TICK,
                        "moisture must change exactly on FARM_WATER contact +"
                            + FarmerWorkGoal.WATER_CONTACT_TICK
                            + " (observed +" + delta + ")");
                    committedAt[0] = now;
                    active[0] = reload(helper, farmer);
                    postReloadAt[0] = now;
                    return;
                }
                return;
            }

            if (active[0].getActivity() != SettlerActivity.WORK_WATER) {
                leftCommittedWaterPose[0] = true;
            } else if (leftCommittedWaterPose[0]) {
                helper.fail("post-contact reload must not start a second FARM_WATER action");
                return;
            }
            if (now - postReloadAt[0] >= POST_CONTACT_OBSERVATION_TICKS) {
                helper.assertTrue(helper.getBlockState(fixture.target().below())
                        .getValue(FarmBlock.MOISTURE) == 7,
                    "post-contact reload must retain the one committed pour");
                helper.assertTrue(leftCommittedWaterPose[0],
                    "reloaded farmer must leave the completed FARM_WATER pose");
                helper.succeed();
            }
        });
    }

    /** An obstruction after anticipation must not spend the hoe or mature crop. */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "farmer_contact_authority")
    public void harvestRejectsWallIntroducedDuringAnticipation(GameTestHelper helper) {
        rejectsObstructedContact(helper, false);
    }

    /** The exact withdrawn seed remains physical when its authored press is occluded. */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "farmer_contact_authority")
    public void plantRejectsWallIntroducedDuringAnticipation(GameTestHelper helper) {
        rejectsObstructedContact(helper, true);
    }

    private static void rejectsObstructedContact(GameTestHelper helper, boolean planting) {
        Fixture fixture = fixture(helper);
        SettlerEntity farmer = fixture.farmer();
        fixture.chest().setItem(0, new ItemStack(Items.IRON_HOE));
        if (planting) {
            fixture.chest().setItem(1, new ItemStack(Items.WHEAT_SEEDS));
        }
        helper.setBlock(fixture.target().below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        if (!planting) {
            helper.setBlock(fixture.target(), Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        }
        long[] started = {Long.MIN_VALUE};
        int[] toolDamage = {-1};
        int contactTick = planting ? FarmerWorkGoal.PLANT_CONTACT_TICK
            : FarmerWorkGoal.HARVEST_CONTACT_TICK;
        SettlerActivity expected = planting ? SettlerActivity.WORK_PLANT
            : SettlerActivity.WORK_HARVEST;
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            if (started[0] == Long.MIN_VALUE) {
                if (farmer.getActivity() != expected) {
                    return;
                }
                started[0] = now;
                helper.assertTrue(farmer.getMainHandItem().is(Items.IRON_HOE),
                    "fixture: the normal equipment goal must supply the physical hoe");
                toolDamage[0] = farmer.getMainHandItem().getDamageValue();
                // Deliberate adversarial world change after the normal goal
                // enters anticipation: displace to distance-squared4 (still
                // inside the old6.5 envelope), then interpose a solid wall.
                // This does not manually tick or replace the production goal.
                BlockPos feet = helper.absolutePos(new BlockPos(7, 1, 8));
                farmer.teleportTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
                for (int y = 1; y <= 3; y++) {
                    for (int z = 7; z <= 9; z++) {
                        helper.setBlock(new BlockPos(8, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.assertTrue(farmer.blockPosition().distSqr(
                        helper.absolutePos(fixture.target())) == 4.0D,
                    "fixture: obstruction, not excessive distance, must invalidate contact");
                return;
            }
            if (now - started[0] < contactTick + 2L) {
                return;
            }
            helper.assertTrue(farmer.getMainHandItem().is(Items.IRON_HOE)
                    && farmer.getMainHandItem().getDamageValue() == toolDamage[0],
                "an occluded authored contact must not charge a new hoe point");
            if (planting) {
                helper.assertTrue(helper.getBlockState(fixture.target()).isAir()
                        && seedMatter(helper, fixture, farmer) == 1,
                    "rejected planting must preserve its exact physical seed and empty crop cell");
            } else {
                helper.assertTrue(helper.getBlockState(fixture.target()).is(Blocks.WHEAT)
                        && helper.getBlockState(fixture.target()).getValue(CropBlock.AGE)
                            == CropBlock.MAX_AGE
                        && countInBag(farmer, Items.WHEAT) == 0
                        && countIn(fixture.chest(), Items.WHEAT) == 0,
                    "rejected harvesting must preserve the mature crop and create no output");
            }
            helper.succeed();
        });
    }
}

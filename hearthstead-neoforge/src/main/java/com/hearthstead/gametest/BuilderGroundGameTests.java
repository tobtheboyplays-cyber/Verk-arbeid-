package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.BuildingPlanItem;
import com.hearthstead.network.BuilderActionPayload;
import com.hearthstead.network.BuilderNetwork;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintGround;
import com.hearthstead.settlement.builder.BlueprintLibrary;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.hearthstead.settlement.builder.BuilderStock;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builder ground (owner, 27 Sep: "every building the Builder made stood one
 * block above the ground"). On flat grass, an order placed the player's way
 * -- the ghost's depth the server hands the client, then a PLACE -- puts the
 * blueprint's ground layer IN the terrain surface: the grass already there
 * satisfies the apron (no dirt charged, no fill), the finished building
 * registers, nothing floats and the door sill is level with the ground.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderGroundGameTests {

    /** The free cell above the clicked grass (the terrain surface is y = 0). */
    private static final BlockPos CLICK = new BlockPos(15, 1, 15);
    private static final BlockPos HEARTH = new BlockPos(16, 1, 3);
    private static final int TERRAIN_Y = 0;

    @GameTest(template = "empty32", timeoutTicks = 40_000, batch = "builder_ground_place")
    public static void builder_ground_cottage_plan(GameTestHelper helper) {
        builds(helper, "house_cottage", ItemStack.EMPTY, true);
    }

    @GameTest(template = "empty32", timeoutTicks = 40_000, batch = "builder_ground_place")
    public static void builder_ground_lumber_camp_blueprint(GameTestHelper helper) {
        // The Blueprint (Building Plan) path: a plan in hand, used up on PLACE.
        builds(helper, "lumber_camp_small", BuildingPlanItem.of(BuildingType.LUMBER_CAMP), false);
    }

    private static void builds(GameTestHelper helper, String id, ItemStack inHand, boolean learnHome) {
        ServerLevel level = helper.getLevel();
        Blueprint blueprint = BlueprintLibrary.get(level.getServer(), id);
        helper.assertTrue(blueprint != null, id + " must load from the data pack");
        BlueprintMeta meta = blueprint.meta();
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, blueprint.sizeY() + 3);
        // Flat grass terrain: the arena floor becomes grass on dirt.
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                helper.setBlock(new BlockPos(x, TERRAIN_Y - 1, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, TERRAIN_Y, z), Blocks.GRASS_BLOCK);
            }
        }
        arena.settlement().center = helper.absolutePos(HEARTH);
        for (int i = 0; i < 3; i++) {
            SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(24 + i, 1, 4));
            resident.bindTo(arena.settlement().id, arena.settlement().center);
            resident.setNoAi(true);
            arena.settlement().putRecord(resident.getUUID(), "Ground resident " + i, Profession.NONE);
        }
        BuilderTestKit.researchBuildersHut(helper, arena, HEARTH);
        if (learnHome) {
            purchaseHome(helper, arena);
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos c = arena.settlement().center;
        player.moveTo(c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, inHand.copy());

        // The ghost: the client offsets the aimed free cell by the depth the
        // server's Preview carries (BuilderPlacement / PlanPlacement).
        int depth = BlueprintGround.placementDepth(meta);
        helper.assertTrue(depth == Math.max(0, meta.groundLevel()) + 1,
            id + ": a building's ground layer sinks one into the surface, depth " + depth);
        BlockPos origin = helper.absolutePos(CLICK).offset(-blueprint.sizeX() / 2, -depth, -blueprint.sizeZ() / 2);
        int terrainY = helper.absolutePos(new BlockPos(0, TERRAIN_Y, 0)).getY();
        int groundY = origin.getY() + Math.max(0, meta.groundLevel());
        helper.assertTrue(groundY == terrainY,
            id + ": the ground layer sits AT terrain y " + terrainY + ", not " + groundY);

        // Evidence: the bill one block up (the old ghost) against the bill now.
        BuildPlanner.Plan old = BuildPlanner.planBlueprint(level, arena.settlement(), blueprint, origin.above(),
            0, false, null);
        BuildPlanner.Plan now = BuildPlanner.planBlueprint(level, arena.settlement(), blueprint, origin,
            0, false, null);
        helper.assertTrue(now.validation().ok() && now.job() != null,
            id + " plans sunk into flat grass: " + now.validation().reasonKey() + " " + now.validation().reasonArgs());
        int oldDirt = old.job() == null ? -1 : BuilderMaterials.total(old.job()).getOrDefault(Items.DIRT, 0);
        int nowDirt = BuilderMaterials.total(now.job()).getOrDefault(Items.DIRT, 0);
        int nowCoarse = BuilderMaterials.total(now.job()).getOrDefault(Items.COARSE_DIRT, 0);
        Hearthstead.LOGGER.info("BUILDER_GROUND {} dirt before={} after={} coarse_after={} fills={} clears={}",
            id, oldDirt, nowDirt, nowCoarse, now.validation().fills(), now.validation().clears());
        helper.assertTrue(now.validation().fills() == 0, id + ": flat grass needs no fill, got "
            + now.validation().fills());
        helper.assertTrue(nowDirt == 0 && nowCoarse == 0,
            id + ": the grass already there is the ground layer; dirt charged " + nowDirt + " (+" + nowCoarse
                + " coarse), was " + oldDirt + " one block up");

        // The player's way: PLACE with the ghost's origin.
        BuilderNetwork.handle(player, new BuilderActionPayload(BuilderActionPayload.Action.PLACE, id, origin,
            BlockPos.ZERO, 0, false, 0, false, BuilderActionPayload.NONE));
        List<BuildJob> queued = BuildSiteSavedData.get(level).jobs(arena.settlement().id);
        helper.assertTrue(queued.size() == 1, id + ": the order is accepted (queued " + queued.size() + ")");
        if (!inHand.isEmpty()) {
            helper.assertTrue(player.getMainHandItem().isEmpty(), id + ": the Building Plan is used up");
        }
        BuildJob job = queued.get(0);
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        helper.assertTrue(bill.getOrDefault(Items.DIRT, 0) == 0, id + ": the queued job charges no dirt: " + bill);

        Building warehouse = GameTestFixtures.register(helper, arena.settlement(), BuildingType.WAREHOUSE, 2, 24);
        List<Container> stores = new ArrayList<>();
        for (int x = 2; x <= 5; x++) {
            for (int z = 25; z <= 27; z++) {
                BlockPos at = new BlockPos(x, 1, z);
                helper.setBlock(at, Blocks.BARREL);
                stores.add((Container) level.getBlockEntity(helper.absolutePos(at)));
            }
        }
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                ItemStack rest = BuilderStock.insertAll(stores, new ItemStack(e.getKey(), n));
                helper.assertTrue(rest.isEmpty(), id + ": the warehouse fixture must hold the whole bill");
                left -= n;
            }
        }
        BuilderStock.insertAll(stores, new ItemStack(Items.BREAD, 32));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.LADDER, 24));
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ground");

        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                id + " completes: " + (live == null ? "gone" : live.state + " " + live.status + " "
                    + live.statusArgs + " done " + live.doneCount() + "/" + live.size())
                    + " | " + com.hearthstead.entity.ai.BuilderUtilisation.debug(builder));
            helper.assertTrue(live.skippedCount() == 0, id + " skipped nothing: " + live.skipReport());
            // Nothing floats: every ground-layer cell stands in the terrain surface.
            for (Blueprint.Cell cell : blueprint.cells()) {
                if (cell.y() != Math.max(0, meta.groundLevel()) || cell.state().isAir()) {
                    continue;
                }
                BlockPos at = origin.offset(cell.x(), cell.y(), cell.z());
                helper.assertTrue(at.getY() == terrainY && !level.getBlockState(at).isAir(),
                    id + ": ground cell " + at + " is filled at terrain y (" + level.getBlockState(at) + ")");
            }
            // The door sill is level with the ground outside: no step up.
            for (int i = 0; i < live.size(); i++) {
                BlockState state = live.state(i);
                if (!(state.getBlock() instanceof DoorBlock)
                    || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                BlockPos door = live.pos(i);
                helper.assertTrue(door.getY() == terrainY + 1,
                    id + ": the door " + door + " opens at walking height " + (terrainY + 1));
                Direction facing = state.getValue(DoorBlock.FACING);
                for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
                    BlockPos step = door.relative(side);
                    helper.assertTrue(level.getBlockState(step).getCollisionShape(level, step).isEmpty()
                            && !level.getBlockState(step.below()).isAir(),
                        id + ": walkable floor at the door's " + side + " side, level with the sill: "
                            + level.getBlockState(step) + " over " + level.getBlockState(step.below()));
                }
            }
            if (meta.buildingType() != null && meta.plaquePos() != null) {
                int[] p = meta.plaquePos();
                BlockPos plaquePos = origin.offset(p[0], p[1], p[2]);
                BuildingType type = BuildingType.byId(meta.buildingType());
                Building building = level.getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque
                    ? plaque.building(level) : null;
                helper.assertTrue(building != null && building.type == type && building.valid,
                    id + " registers as a valid " + meta.buildingType() + " through its plaque");
            }
            helper.assertTrue(warehouse.valid, "the warehouse stays valid");
        });
    }

    private static void purchaseHome(GameTestHelper helper, BuilderTestKit.Arena arena) {
        var settlement = arena.settlement();
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(settlement.center);
        var inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
        int slot = 0;
        for (var cost : DevelopmentNode.HOME.costs()) {
            inventory.setStackInSlot(slot++, new ItemStack(cost.item(), cost.count()));
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(), settlement, hearth,
            DevelopmentNode.HOME, Development.revisionOf(helper.getLevel(), settlement));
        helper.assertTrue(result == Development.Result.APPLIED
                || result == Development.Result.ALREADY_UNLOCKED,
            "the town learns Home, got " + result);
        for (int i = 0; i < inventory.getSlots(); i++) {
            inventory.setStackInSlot(i, ItemStack.EMPTY);
        }
    }
}

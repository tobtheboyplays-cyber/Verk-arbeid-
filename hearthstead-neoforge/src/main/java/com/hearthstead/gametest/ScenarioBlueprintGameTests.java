package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintLibrary;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.hearthstead.settlement.builder.BuilderStock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SCENARIO lane (26 Sep): every one of the shipped blueprints gets its own
 * GameTest, from a fresh settlement.
 *
 * <ul>
 *   <li>{@code scenario_blueprint}: fast, one per blueprint. The real
 *   blueprint from the data pack plans on a fresh arena, every block in it
 *   can be charged (nothing is silently skipped), a typed blueprint will fit
 *   its own plan when the Builder finishes it, and the finished shape
 *   registers as a valid building of that type through its own plaque
 *   (i.e. meets its level-1 checklist).</li>
 *   <li>{@code scenario_blueprint_build}: slow, one per blueprint. A hired
 *   Builder builds it for real, self-fetching every material from a fresh
 *   warehouse (no Courier: the HYBRID pickup default). It must complete with
 *   nothing skipped, use exactly the bill, return every ladder, and the
 *   result must register (a typed building through its plaque, a wall
 *   through its segment).</li>
 * </ul>
 *
 * <p>The slow tier takes several minutes of ticks. Set the system property
 * {@code hearthstead.gametest.skipBlueprintBuilds=true} to leave it out of a
 * quick full-suite run.
 */
@GameTestHolder(Hearthstead.MODID)
public class ScenarioBlueprintGameTests {

    /**
     * Every blueprint that ships, read from data/hearthstead/blueprint_catalog.txt, which the
     * blueprint generator (tools/blueprints/gen_town_blueprints.py) writes next to the
     * blueprints themselves -- so this list cannot drift from the data pack (catalogueComplete
     * still checks it against what BlueprintLibrary actually loads). 179 on 26 Sep.
     */
    static final List<String> IDS = loadCatalogIds();

    static List<String> loadCatalogIds() {
        List<String> ids = new ArrayList<>();
        try (java.io.InputStream in = ScenarioBlueprintGameTests.class.getResourceAsStream(
                "/data/hearthstead/blueprint_catalog.txt")) {
            if (in == null) {
                return List.of();
            }
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String id = line.trim();
                if (!id.isEmpty() && !id.startsWith("#")) {
                    ids.add(id);
                }
            }
        } catch (java.io.IOException e) {
            return List.of();
        }
        return List.copyOf(ids);
    }

    static final String SKIP_BUILDS = "hearthstead.gametest.skipBlueprintBuilds";
    private static final String TEMPLATE = Hearthstead.MODID + ":empty32";
    /** Blueprint origin (template y = 0 sits on the stone floor at y = 0). */
    private static final BlockPos ORIGIN = new BlockPos(8, 1, 8);
    private static final int LADDERS = 24;

    @GameTestGenerator
    public static Collection<TestFunction> scenarioBlueprints() {
        List<TestFunction> out = new ArrayList<>();
        out.add(new TestFunction("scenario_blueprint", "scenario_blueprint_catalogue_complete", TEMPLATE,
            Rotation.NONE, 100, 0L, true, ScenarioBlueprintGameTests::catalogueComplete));
        for (String id : IDS) {
            out.add(new TestFunction("scenario_blueprint", "scenario_blueprint_plan_" + id, TEMPLATE,
                Rotation.NONE, 400, 0L, true, helper -> planAndRegister(helper, id)));
        }
        if (!Boolean.getBoolean(SKIP_BUILDS)) {
            for (String id : IDS) {
                out.add(new TestFunction("scenario_blueprint_build", "scenario_blueprint_build_" + id, TEMPLATE,
                    Rotation.NONE, Integer.getInteger("hearthstead.gametest.blueprintTimeout", 40_000), 0L, true,
                    helper -> builderBuilds(helper, id, false)));
            }
            // P1 (native film 26 Sep): ordered the player's way, through the
            // Builder's Plan, with the level-1 hut of a fresh world.
            for (String id : List.of("house_cottage", "house_two_storey")) {
                out.add(new TestFunction("scenario_blueprint_build", "scenario_blueprint_build_l1plan_" + id, TEMPLATE,
                    Rotation.NONE, Integer.getInteger("hearthstead.gametest.blueprintTimeout", 40_000), 0L, true,
                    helper -> builderBuilds(helper, id, true)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ guard ---

    /** A blueprint added to the data pack without a scenario test fails here. */
    static void catalogueComplete(GameTestHelper helper) {
        Set<String> shipped = new TreeSet<>();
        for (Blueprint blueprint : BlueprintLibrary.all(helper.getLevel().getServer())) {
            if (!blueprint.id().startsWith("design_") && !blueprint.id().startsWith("test_")) {
                shipped.add(blueprint.id());
            }
        }
        Set<String> listed = new TreeSet<>(IDS);
        Set<String> missing = new TreeSet<>(shipped);
        missing.removeAll(listed);
        Set<String> gone = new TreeSet<>(listed);
        gone.removeAll(shipped);
        helper.assertTrue(missing.isEmpty() && gone.isEmpty(),
            "the scenario list must match the shipped blueprints; untested: " + missing + ", not loaded: " + gone);
        helper.succeed();
    }

    // ------------------------------------------------------------- fast ---

    static void planAndRegister(GameTestHelper helper, String id) {
        ServerLevel level = helper.getLevel();
        Blueprint blueprint = BlueprintLibrary.get(level.getServer(), id);
        helper.assertTrue(blueprint != null, "blueprint " + id + " must load from the data pack");
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, blueprint.sizeY() + 2);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(level, arena.settlement(), blueprint,
            helper.absolutePos(ORIGIN), 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null,
            id + " must plan on flat ground: " + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        BuildJob job = plan.job();
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        helper.assertTrue(!bill.isEmpty() && !bill.containsKey(Items.AIR), id + " has a real bill: " + bill);

        // Every block in the blueprint can be charged, so none is silently skipped.
        Map<String, Integer> unbuildable = new LinkedHashMap<>();
        for (Blueprint.Cell cell : blueprint.cells()) {
            if (!cell.state().isAir() && !BuilderMaterials.buildable(cell.state())) {
                unbuildable.merge(BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString(), 1, Integer::sum);
            }
        }
        helper.assertTrue(unbuildable.isEmpty(), id + " has blocks no item can place: " + unbuildable);

        BlueprintMeta meta = blueprint.meta();
        boolean typed = meta.buildingType() != null && meta.plaquePos() != null;
        if (typed) {
            helper.assertTrue(meta.buildingType().equals(job.fitPlanType),
                id + " (" + meta.kind().id() + ", type " + meta.buildingType()
                    + ") must fit its own plan when the Builder finishes it; the job fits: " + job.fitPlanType);
        }
        if (!typed) {
            helper.succeed();
            return;
        }
        // The finished shape, as the Builder leaves it, then the plan he fits.
        BlockPos origin = helper.absolutePos(ORIGIN);
        for (Blueprint.Cell cell : blueprint.cells()) {
            level.setBlock(origin.offset(cell.x(), cell.y(), cell.z()), cell.state(), Block.UPDATE_CLIENTS);
        }
        int[] p = meta.plaquePos();
        BlockPos plaquePos = origin.offset(p[0], p[1], p[2]);
        BuildingType type = BuildingType.byId(meta.buildingType());
        helper.assertTrue(type != null, id + " names a real building type: " + meta.buildingType());
        helper.assertTrue(level.getBlockEntity(plaquePos) instanceof PlaqueBlockEntity,
            id + " has its plaque where its meta says: " + plaquePos);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) level.getBlockEntity(plaquePos);
        helper.assertTrue(plaque.insertPlan(level,
                PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type)),
            id + " accepts its plan");
        helper.succeedWhen(() -> {
            Building building = plaque.building(level);
            helper.assertTrue(building != null && building.type == type && building.valid,
                id + " registers as a valid " + type.id() + " (level-1 checklist met); plaque "
                    + plaque.state() + " " + (plaque.lastScanReason() == null ? "-" : plaque.lastScanReason().getString()) + " " + plaque.lastSurvey());
        });
    }

    // ------------------------------------------------------------- slow ---

    static void builderBuilds(GameTestHelper helper, String id, boolean viaPlan) {
        ServerLevel level = helper.getLevel();
        Blueprint blueprint = BlueprintLibrary.get(level.getServer(), id);
        helper.assertTrue(blueprint != null, "blueprint " + id + " must load from the data pack");
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, blueprint.sizeY() + 3);
        int ground = Math.max(0, blueprint.meta().groundLevel());
        if (ground > 0) {
            // A Mine's shaft or a basin goes INTO the ground: the lot stands on
            // natural terrain ground_level deep (stone, grass on top), so the
            // placement sinks it exactly as the Builder's Plan does in game
            // and the Builder digs. A ramp leads up from the arena floor.
            for (int x = ORIGIN.getX() - 1; x <= ORIGIN.getX() + blueprint.sizeX(); x++) {
                for (int z = ORIGIN.getZ() - 1; z <= ORIGIN.getZ() + blueprint.sizeZ(); z++) {
                    for (int y = 1; y <= ground; y++) {
                        helper.setBlock(new BlockPos(x, y, z), y == ground ? Blocks.GRASS_BLOCK : Blocks.STONE);
                    }
                }
            }
            for (int k = 1; k <= ground; k++) {
                for (int y = 1; y <= k; y++) {
                    helper.setBlock(new BlockPos(ORIGIN.getX(), y, ORIGIN.getZ() - 1 - (ground - k)),
                        y == k ? Blocks.GRASS_BLOCK : Blocks.STONE);
                }
            }
        }
        // The click lands on the lot's ground surface; ground_level sinks it.
        BlockPos origin = helper.absolutePos(ORIGIN.above(ground)).below(ground);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(level, arena.settlement(), blueprint,
            origin, 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null,
            id + " must plan: " + plan.validation().reasonKey());
        BuildJob job;
        if (viaPlan) {
            BuilderTestKit.researchBuildersHut(helper, arena, new BlockPos(1, 1, 1));
            helper.assertTrue(com.hearthstead.settlement.builder.BuilderUnlocks.hutLevel(arena.settlement()) == 1,
                "a fresh level-1 hut");
            net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
            BlockPos c = arena.settlement().center;
            player.moveTo(c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
            com.hearthstead.network.BuilderNetwork.handle(player, new com.hearthstead.network.BuilderActionPayload(
                com.hearthstead.network.BuilderActionPayload.Action.PLACE, id, origin, BlockPos.ZERO, 0, false, 0,
                false, com.hearthstead.network.BuilderActionPayload.NONE));
            List<BuildJob> queued = com.hearthstead.settlement.builder.BuildSiteSavedData.get(level)
                .jobs(arena.settlement().id);
            helper.assertTrue(queued.size() == 1,
                id + ": a level-1 hut accepts the order from the Builder's Plan (queued " + queued.size() + ")");
            job = queued.get(0);
        } else {
            job = plan.job();
            helper.assertTrue(BuildJobs.commit(level, arena.settlement(), job) == null, id + " enters the queue");
        }
        Map<Item, Integer> bill = BuilderMaterials.total(job);

        // A fresh warehouse holding exactly the bill (and bread for the Builder).
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
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.LADDER, LADDERS));
        Map<Item, Integer> before = new LinkedHashMap<>();
        for (Item item : bill.keySet()) {
            before.put(item, BuilderStock.count(stores, item) + BuilderTestKit.count(arena.chest(), item));
        }
        before.put(Items.LADDER, BuilderStock.count(stores, Items.LADDER)
            + BuilderTestKit.count(arena.chest(), Items.LADDER));

        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Scen");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                id + " completes: " + (live == null ? "gone" : live.state + " " + live.status + " "
                    + live.statusArgs + " done " + live.doneCount() + "/" + live.size())
                    + " | " + com.hearthstead.entity.ai.BuilderUtilisation.debug(builder));
            helper.assertTrue(live.skippedCount() == 0, id + " skipped nothing: " + live.skipReport());
            List<Container> hut = BuilderStock.hutContainers(level, arena.hut());
            for (Map.Entry<Item, Integer> e : before.entrySet()) {
                Item item = e.getKey();
                int now = BuilderStock.count(stores, item) + BuilderStock.count(hut, item)
                    + BuilderStock.bagCount(builder.bag, item);
                int used = bill.getOrDefault(item, 0);
                helper.assertTrue(now == e.getValue() - used, id + ": exactly the bill of " + item
                    + " was used (had " + e.getValue() + ", bill " + used + ", left " + now + ")");
            }
            BlueprintMeta meta = blueprint.meta();
            if (meta.buildingType() != null && meta.plaquePos() != null) {
                int[] p = meta.plaquePos();
                BlockPos plaquePos = helper.absolutePos(ORIGIN).offset(p[0], p[1], p[2]);
                BuildingType type = BuildingType.byId(meta.buildingType());
                Building building = level.getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque
                    ? plaque.building(level) : null;
                helper.assertTrue(building != null && building.type == type && building.valid,
                    id + " registers as a valid " + meta.buildingType() + " through its plaque");
            }
            if (meta.segment() != null) {
                BlockPos wall = null;
                for (int i = 0; i < live.size() && wall == null; i++) {
                    if (live.isPlaced(i) && !live.state(i).isAir()) {
                        wall = live.pos(i);
                    }
                }
                helper.assertTrue(wall != null
                        && BuildSiteSavedData.segmentAt(level, arena.settlement().id, wall) != null,
                    id + " registers its " + meta.segment() + " segment for the raid lane");
            }
            helper.assertTrue(warehouse.valid, "the warehouse stays valid");
        });
    }
}

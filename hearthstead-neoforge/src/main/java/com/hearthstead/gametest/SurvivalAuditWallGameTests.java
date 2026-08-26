package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.building.Requirement;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * GAPS-1 / SURVIVAL_AUDIT.md: proves the two WALLs the audit's follow-up
 * claims are closed actually are, from materials the settlement itself can
 * produce -- not from an assertion that the coordinator's note reads
 * correctly. Nobody had run either before this pass.
 *
 * <p><b>INFIRMARY (F3):</b> the room used to demand a {@code brewing_stand}
 * (a Nether-gated blaze rod); the follow-up claims it now asks for a
 * {@code cauldron} instead -- reachable with iron alone. {@link
 * #infirmaryRoomValidatesWithASurvivalCauldron} builds a real room with a
 * PLAIN {@code Blocks.CAULDRON} (7 iron ingots, vanilla, no water, no
 * Nether) and drives the actual survey path
 * ({@code PlaqueBlockEntity#survey}) to {@code LINKED_VALID}.
 *
 * <p><b>MARKET (F4):</b> the build plan used to want a biome/villager-
 * dependent emerald; the follow-up claims it now wants
 * {@code hearthstead:wool_bolt} instead, staffing-gated rather than
 * seed-gated. Three tests close the whole chain: the ROOM validates with
 * survival-plain barrels and chests
 * ({@link #marketRoomValidatesWithBarrelsAndChests}), the WEAVER genuinely
 * turns wool into wool_bolt, chest-true
 * ({@link #weaverGrindsWoolIntoWoolBoltChestTrue} -- zero prior GameTest
 * coverage existed for this recipe at all), and the build plan itself
 * crafts through the real recipe manager once wool_bolt exists
 * ({@link #marketBuildPlanCraftsFromPaperFeatherBookAndWoolBolt}).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class SurvivalAuditWallGameTests {

    // ------------------------------------------------------------- arena ---

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 6; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel, int radius) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Torget",
            helper.absolutePos(centerRel));
        s.radius = radius;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /**
     * A stone room, footprint {@code size}x{@code size}, walls y1..3, a full
     * roof at y4, floor at y0 -- comfortably bigger than any of this pass's
     * {@code floorSpace} requirements at this footprint (see each test's own
     * comment for the exact interior volume). A door sits centred on the
     * south wall (z=o.z); the caller furnishes the interior.
     */
    private static void buildRoom(GameTestHelper helper, BlockPos o, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(o.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        int mid = size / 2;
        helper.setBlock(o.offset(mid, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(mid, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void placeBed(GameTestHelper helper, BlockPos footRel) {
        helper.setBlock(footRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(footRel.north(), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static ItemStack buildPlan(BuildingType type) {
        return PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type);
    }

    /**
     * Same idiom {@code HearthsteadGameTests#hangPlaque} uses: the plaque
     * hangs in the AIR CELL outside the wall (z - 1), facing NORTH into it,
     * exactly as a player right-clicking the wall's outer face would place
     * it (KF-001) -- never by replacing a wall block, which punches a hole
     * in the enclosure the survey is about to check.
     */
    private static PlaqueBlockEntity hangPlaque(GameTestHelper helper, BlockPos roomOrigin,
                                                int size, BuildingType type) {
        int mid = size / 2;
        BlockPos plaqueRel = roomOrigin.offset(mid + 1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos abs = helper.absolutePos(plaqueRel);
        PlaqueBlockEntity plaque = helper.getLevel().getBlockEntity(abs)
            instanceof PlaqueBlockEntity pbe ? pbe : null;
        helper.assertTrue(plaque != null, "setup: the hung plaque must be a real block entity");
        plaque.insertPlan(helper.getLevel(), buildPlan(type));
        return plaque;
    }

    // ------------------------------------------------------------ (a) ---

    /**
     * INFIRMARY, F3: a room furnished with a PLAIN cauldron (vanilla: 7 iron
     * ingots, no water poured in, no blaze rod, no Nether) plus its other
     * requirements (2 beds, 1 storage, 1 door, 2 lights) inside a 7x7 room
     * (interior 5x5x3 = 75 cells, comfortably over the 20-cell requirement)
     * must drive a real plaque all the way to {@code LINKED_VALID} through
     * the actual survey path, not an assertion that the room TYPE's
     * requirement list merely contains "cauldron".
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "survival_audit_walls")
    public void infirmaryRoomValidatesWithASurvivalCauldron(GameTestHelper helper) {
        buildArena(helper, 16);
        makeSettlement(helper, new BlockPos(1, 1, 1), 12);
        BlockPos origin = new BlockPos(1, 0, 1);
        buildRoom(helper, origin, 7);
        // The plain, Nether-free cauldron -- the exact claim under test.
        helper.setBlock(origin.offset(3, 1, 3), Blocks.CAULDRON);
        placeBed(helper, origin.offset(1, 1, 4));
        placeBed(helper, origin.offset(5, 1, 4));
        helper.setBlock(origin.offset(1, 1, 1), Blocks.CHEST);
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
        helper.setBlock(origin.offset(5, 2, 1), Blocks.TORCH);

        PlaqueBlockEntity plaque = hangPlaque(helper, origin, 7, BuildingType.INFIRMARY);

        helper.succeedWhen(() -> {
            plaque.survey(helper.getLevel());
            String diag = " [state=" + plaque.state() + " survey="
                + describeSurvey(plaque) + "]";
            helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
                "an infirmary room with a plain survival cauldron (7 iron ingots, no "
                    + "Nether trip) must validate" + diag);
        });
    }

    // ------------------------------------------------------------ (b) ---

    /**
     * MARKET, F4 (room half): 4 barrels ("stall"), 2 SEPARATE chests
     * ("storage" -- deliberately not re-using the same 4 barrels, which
     * {@code Requirement.blocks} would legally allow to double-count toward
     * both categories; a real player is more likely to build distinct
     * furniture for each, so this is the representative case, not the
     * minimum-legal one), 1 door, 2 lights, inside a 9x9 room (interior
     * 7x7x3 = 147 cells, comfortably over the 36-cell requirement).
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "survival_audit_walls")
    public void marketRoomValidatesWithBarrelsAndChests(GameTestHelper helper) {
        buildArena(helper, 16);
        makeSettlement(helper, new BlockPos(1, 1, 1), 12);
        BlockPos origin = new BlockPos(1, 0, 1);
        buildRoom(helper, origin, 9);
        helper.setBlock(origin.offset(2, 1, 2), Blocks.BARREL);
        helper.setBlock(origin.offset(2, 1, 6), Blocks.BARREL);
        helper.setBlock(origin.offset(6, 1, 2), Blocks.BARREL);
        helper.setBlock(origin.offset(6, 1, 6), Blocks.BARREL);
        helper.setBlock(origin.offset(4, 1, 2), Blocks.CHEST);
        helper.setBlock(origin.offset(4, 1, 6), Blocks.CHEST);
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
        helper.setBlock(origin.offset(7, 2, 1), Blocks.TORCH);

        PlaqueBlockEntity plaque = hangPlaque(helper, origin, 9, BuildingType.MARKET);

        helper.succeedWhen(() -> {
            plaque.survey(helper.getLevel());
            String diag = " [state=" + plaque.state() + " survey="
                + describeSurvey(plaque) + "]";
            helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
                "a market room with survival-plain barrels and chests must validate"
                    + diag);
        });
    }

    private static String describeSurvey(PlaqueBlockEntity plaque) {
        StringBuilder sb = new StringBuilder();
        for (Requirement.Status status : plaque.lastSurvey()) {
            sb.append(status.requirement().id()).append('=').append(status.have())
                .append('/').append(status.needed()).append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ (c) ---

    /**
     * MARKET, F4 (recipe half): the build plan crafts, through the real
     * recipe manager ({@code RecipeManager#getRecipeFor(RecipeType.CRAFTING,
     * ...)} -- the exact path a survival crafting table uses), from 2 paper,
     * 1 feather and 1 book (all vanilla, hand-reachable) plus ONE
     * {@code hearthstead:wool_bolt} -- the modded good that replaced the old
     * emerald. Mirrors {@code BuildPlanRecipeGameTests#assertCraftsInto}'s
     * own idiom rather than reaching into its private helper.
     */
    @GameTest(template = "empty5", timeoutTicks = 100, batch = "survival_audit_walls")
    public void marketBuildPlanCraftsFromPaperFeatherBookAndWoolBolt(GameTestHelper helper) {
        List<ItemStack> slots = new ArrayList<>(Arrays.asList(
            new ItemStack(Items.PAPER),
            new ItemStack(Items.PAPER),
            new ItemStack(Items.FEATHER),
            new ItemStack(Items.BOOK),
            new ItemStack(ModItems.WOOL_BOLT.get())));
        while (slots.size() < 9) {
            slots.add(ItemStack.EMPTY);
        }
        CraftingInput input = CraftingInput.of(3, 3, slots);

        RecipeManager recipes = helper.getLevel().getRecipeManager();
        Optional<RecipeHolder<CraftingRecipe>> match =
            recipes.getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
        helper.assertTrue(match.isPresent(),
            "market: no crafting recipe matched paper+paper+feather+book+wool_bolt "
                + "through the recipe manager -- the survival table would show nothing");

        ItemStack result = match.get().value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.is(ModItems.BUILD_PLAN.get()),
            "market: matched recipe did not assemble a build plan, got " + result);
        String stamped = result.get(ModComponents.BUILDING_TYPE.get());
        helper.assertTrue(BuildingType.MARKET.id().equals(stamped),
            "market: expected a plan stamped 'market', got '" + stamped + "'");
        helper.succeed();
    }

    // ------------------------------------------------------------ (d) ---

    /**
     * The chain's own upstream link, never previously covered by any
     * GameTest: the WEAVER's "wool_bolt" recipe ({@code Production.java})
     * genuinely turns real wool into a real wool_bolt in the building's own
     * chest, chest-true (3 wool leaves for exactly 2 wool_bolt, nothing
     * more or less) -- the same discipline
     * {@code ChainsGameTests#millGrindsSugarCaneIntoPaperChestTrue} already
     * holds the mill's own paper recipe to.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "survival_audit_walls")
    public void weaverGrindsWoolIntoWoolBoltChestTrue(GameTestHelper helper) {
        buildArena(helper, 16);
        // NOT registered into SettlementSavedData -- Production.run needs
        // only the Building and the level, never the settlement, the same
        // reasoning ChainsGameTests's own mill/paper test documents for
        // why registering one here would only risk BuildingManager's sweep
        // dissolving this bare fixture mid-run for nothing gained.
        Settlement s = new Settlement(UUID.randomUUID(), "Vevholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        Building weaver = GameTestFixtures.register(helper, s, BuildingType.WEAVER, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = (Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(chest != null, "the registered weaver's chest should be a container");
        chest.setItem(0, new ItemStack(Items.WHITE_WOOL, 9));

        Production.Recipe recipe = null;
        for (Production.Recipe r : Production.of(BuildingType.WEAVER)) {
            if (r.id().equals("wool_bolt")) {
                recipe = r;
                break;
            }
        }
        helper.assertTrue(recipe != null, "the weaver must know how to make wool_bolt");
        helper.assertTrue(recipe.inputCount() == 3 && recipe.outputCount() == 2,
            "wool_bolt should cost 3 wool for 2 wool_bolt, got "
                + recipe.inputCount() + " -> " + recipe.outputCount());

        boolean ran = Production.run(helper.getLevel(), weaver, recipe);
        helper.assertTrue(ran, "the wool_bolt recipe should have run");

        helper.assertTrue(countOf(chest, Items.WHITE_WOOL) == 6,
            "three wool gone, 6 left of 9; saw " + countOf(chest, Items.WHITE_WOOL));
        helper.assertTrue(countOf(chest, ModItems.WOOL_BOLT.get()) == 2,
            "and two wool_bolt made; saw " + countOf(chest, ModItems.WOOL_BOLT.get()));
        helper.succeed();
    }

    private static int countOf(Container container, Item item) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }
}

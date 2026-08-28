package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Effort;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerAttributes;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The daily labor pool (docs/project/PLAN_EFFORT.md): every trade's own
 * natural limit, and the pool that makes "spent for the day" a real state
 * instead of a slogan. Each test here is a real judge -- delete the feature
 * it names and the test fails, not just goes quiet.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class EffortGameTests {

    /**
     * A guaranteed-flat floor, cleared air, and a 2-high perimeter wall, the
     * way every AI GameTest in this mod builds its own arena (the structure
     * templates only reserve bounds; their contents cannot be trusted).
     */
    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Registered with SettlementSavedData, because settler.settlement()
     *  resolves by id through the manager -- see EmploymentGameTests for
     *  the KF this avoids. Radius 6, small on purpose (arenas sit close
     *  together in the shared GameTest level). */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, type, x, z);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    // -------------------------------------------------------- the plot ---

    /**
     * THE TENDED PLOT: a farmer with fresh (sub-20 DEXTERITY) skill only
     * works the 3x3 square around their farmhouse's anchor. A crop just
     * outside that square is not merely unreached by chance -- the scan
     * predicate throws it out on purpose -- and a crop inside it is worked
     * exactly as before.
     */
    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "effort_day")
    public void farmerNeverTouchesCropsOutsideTheTendedPlot(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building farmhouse = building(helper, s, BuildingType.FARMHOUSE, 8, 8);
        SettlerEntity farmer = settler(helper, s, "Astrid", 8, 8);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, farmhouse, farmer).ok(),
            "the farmhouse must take its first farmer");
        helper.assertTrue(farmer.attribute(Attribute.DEXTERITY) < 20,
            "a fresh settler starts under the 5x5 threshold (START_CAP="
                + SettlerAttributes.START_CAP + "), got "
                + farmer.attribute(Attribute.DEXTERITY));

        // The 3x3 plot spans anchor +-1. "near" sits inside it; "far" sits
        // 4 blocks past its edge, well inside the settlement's own scan
        // radius (6) -- so if it is never touched, that is the plot filter
        // at work, not the crop simply being out of scanning reach.
        BlockPos nearRel = new BlockPos(9, 1, 8);
        BlockPos farRel = new BlockPos(13, 1, 8);
        helper.setBlock(nearRel, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        helper.setBlock(farRel, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(farRel).is(Blocks.WHEAT)
                    && helper.getBlockState(farRel).getValue(CropBlock.AGE) == 7,
                "a crop 4+ blocks outside the tended plot must never be touched, saw "
                    + helper.getBlockState(farRel));
            helper.assertFalse(helper.getBlockState(nearRel).is(Blocks.WHEAT)
                    && helper.getBlockState(nearRel).getValue(CropBlock.AGE) == 7,
                "the crop inside the tended plot must be harvested [farmer act="
                    + farmer.getActivity() + " pos=" + farmer.blockPosition() + "]");
        });
    }

    // ------------------------------------------------------- the limit ---

    /**
     * The pool itself: a settler drained to zero never starts a NEW work
     * motion, even with work sitting right in front of them. This is the
     * feature the owner actually asked for -- "I don't want the farmer
     * farming forever" -- so it has to hold with the farmer given every
     * reason to work.
     */
    @GameTest(template = "empty16", timeoutTicks = 700, batch = "effort_day")
    public void aSpentSettlerNeverStartsANewWorkMotion(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building farmhouse = building(helper, s, BuildingType.FARMHOUSE, 8, 8);
        SettlerEntity farmer = settler(helper, s, "Astrid", 8, 8);
        Employment.hire(helper.getLevel(), s, farmhouse, farmer);
        // Drain the pool entirely before there is any work to react to.
        farmer.spendEffort(farmer.effortCapacity() + 10);
        helper.assertTrue(farmer.isEffortSpent(),
            "setup: draining past capacity must read as spent");

        BlockPos cropRel = new BlockPos(9, 1, 8); // well inside the tended plot
        helper.setBlock(cropRel, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));

        boolean[] sawWorkMotion = {false};
        int[] observedTicks = {0};
        helper.succeedWhen(() -> {
            observedTicks[0]++;
            if (farmer.getActivity() == SettlerActivity.WORK_HARVEST) {
                sawWorkMotion[0] = true;
            }
            helper.assertFalse(sawWorkMotion[0],
                "a spent settler must never start a work motion, activity="
                    + farmer.getActivity());
            helper.assertTrue(observedTicks[0] > 300,
                "let the goal have a real chance to (wrongly) fire before "
                    + "declaring victory");
        });
    }

    // ------------------------------------------------------- the refill ---

    /**
     * Sleep quality as an economic input: a genuine night in a claimed bed
     * refills the pool to full. This is the lever the owner's farmhouse
     * question actually plugs into on the housing side -- the reason to
     * build real beds rather than let settlers doze by the hearth.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "night_effort")
    public void aNightInAClaimedBedRefillsThePoolInFull(GameTestHelper helper) {
        helper.getLevel().setDayTime(22600); // short of dawn, deep in REST
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        BlockPos bedFootRel = new BlockPos(6, 1, 6);
        BlockPos bedHeadRel = new BlockPos(6, 1, 7);
        helper.setBlock(bedFootRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(bedHeadRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD));
        // Pre-claimed so this test is about the refill, not about home
        // registration and BuildingManager's own bed search -- that is
        // EmploymentGameTests/HearthsteadGameTests' territory already.
        astrid.claimBed(helper.absolutePos(bedHeadRel));
        astrid.spendEffort(astrid.effortCapacity()); // drained before the night
        helper.assertTrue(astrid.isEffortSpent(), "setup: pool must start drained");

        boolean[] sawSleeping = {false};
        helper.succeedWhen(() -> {
            if (astrid.getActivity() == SettlerActivity.SLEEPING) {
                sawSleeping[0] = true;
            }
            helper.assertTrue(sawSleeping[0],
                "settler should have slept in the claimed bed at some point (act="
                    + astrid.getActivity() + ")");
            helper.assertTrue(!astrid.isSleeping(),
                "settler must actually wake once the night is over");
            helper.assertTrue(astrid.effortLeft() == astrid.effortCapacity(),
                "a genuine night in a claimed bed must refill the pool in full: "
                    + astrid.effortLeft() + "/" + astrid.effortCapacity());
        });
    }

    // ------------------------------------------------- forestry, again ---

    /**
     * The lumberjack's own renewal: a sapling stands where the trunk did.
     * Paired here with the tree's effort cost (3 to fell, 1 to limb) so this
     * file also proves the two pieces of PLAN_EFFORT.md's lumberer section
     * land together, not just that the replant survived untouched.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "effort_day")
    public void lumbererReplantsWhereTheTreeStoodAndPaysForIt(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity lumberer = settler(helper, s, "Bjorn", 6, 8);
        lumberer.assignProfession(Profession.LUMBERER);
        int capacity = lumberer.effortCapacity();

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

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(baseRel).is(Blocks.OAK_SAPLING),
                "the lumberer's own forestry: a sapling must stand where the trunk "
                    + "did [act=" + lumberer.getActivity()
                    + " pos=" + lumberer.blockPosition() + "]");
            helper.assertTrue(lumberer.effortLeft() == capacity - 4,
                "felling a whole tree (3) plus its limbing stint (1) must come out "
                    + "of the daily pool: " + capacity + " -> " + lumberer.effortLeft());
        });
    }

    // ------------------------------------------------- research discount ---

    /**
     * {@link Effort#spendResearched} pins the determinism BALANCE_AUDIT.md
     * finding 2's follow-up promised: "same input, same outcome, every
     * run" — no die roll anywhere in the accumulator. Two completely
     * independent pools, given the identical sequence of calls a
     * researched bakery's batches actually make, must land on the exact
     * same running total after every single call, not just at the end —
     * catching a hypothetical future regression (e.g. a stray {@code
     * Math.random()}) the moment it appears rather than only on average.
     * No game world needed: this exercises the real production method
     * directly, the same one {@code CrafterWorkGoal} calls.
     */
    @GameTest(template = "empty16", timeoutTicks = 20, batch = "effort_day")
    public void researchedEffortDiscountIsDeterministicAcrossIdenticalRuns(GameTestHelper helper) {
        // An arbitrary STAMINA attribute -- Effort#capacity derives its own
        // ceiling from this (20 + 15/5 = 23 here), the same raw number
        // CrafterWorkGoal reads off the settler and passes straight through.
        int stamina = 15;
        Effort a = Effort.full();
        Effort b = Effort.full();
        for (int call = 1; call <= 20; call++) {
            a.spendResearched(2, 0.85F, stamina);
            b.spendResearched(2, 0.85F, stamina);
            helper.assertTrue(a.left(stamina) == b.left(stamina),
                "two identically-set-up pools must agree after every single call, "
                    + "not just at the end -- call " + call + ": "
                    + a.left(stamina) + " vs " + b.left(stamina));
            helper.assertTrue(a.isSpent(stamina) == b.isSpent(stamina),
                "and must agree on WHEN the pool runs dry, call " + call);
        }
        helper.assertTrue(a.isSpent(stamina) && b.isSpent(stamina),
            "20 calls at 2 base effort must be more than enough to exhaust a 23-capacity "
                + "pool even at a 15% discount, or this test proves nothing");
        helper.succeed();
    }
}

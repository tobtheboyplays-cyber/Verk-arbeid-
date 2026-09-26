package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.BuilderUnlocks;
import com.hearthstead.settlement.economy.QualityConfig;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.hearthstead.settlement.techtree.effects.CraftEffects;
import com.hearthstead.settlement.work.CraftedQuality;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tech tree v3, Craft &amp; Production branch (batch {@code techtree_craft},
 * lane "techtree-craft", 26 Sep). One test per non-A node: the hook reads
 * nothing before the node is learned and changes after, per settlement; the
 * three claim nodes also prove their old-save grandfathering.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechTreeCraftGameTests {

    private static final String BATCH = "techtree_craft";

    public TechTreeCraftGameTests() {
    }

    // ------------------------------------------------------------ builders_hut

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void buildersHutIsItsOwnNodeAndOldSavesKeepIt(GameTestHelper helper) {
        Fixture f = fixture(helper, "Hut", new BlockPos(3, 1, 3));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.LUMBER_CAMP)
                && !Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.BUILDERS_HUT)
                && !Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.BUILDER)
                && !BuilderUnlocks.owns(helper.getLevel(), f.settlement, BuilderUnlocks.BUILDERS_HUT),
            "Timber Rights alone gives the Lumber Camp, not the Builder");
        fund(f.hearth, TechTreeData.get().node("builders_hut"));
        TechTree.Result learned = TechTree.learn(helper.getLevel(), f.settlement, f.hearth,
            "builders_hut", state.revision(), null);
        helper.assertTrue(learned == TechTree.Result.LEARNED, "builders_hut must be learnable: " + learned);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), f.settlement, BuildingType.BUILDERS_HUT)
                && Development.isEmblemUnlocked(helper.getLevel(), f.settlement, Profession.BUILDER)
                && BuilderUnlocks.owns(helper.getLevel(), f.settlement, BuilderUnlocks.BUILDERS_HUT),
            "Builder's Hut grants the hut plan, the Builder emblem and the Builder's orders");

        // A pre-v3 save that owned Timber Rights keeps its Builder; a v3 save does not get it free.
        DevelopmentState old = new DevelopmentState();
        founding(old);
        old.unlock(DevelopmentNode.TIMBER_RIGHTS);
        helper.assertTrue(migrate(old, true).hasTech("builders_hut"),
            "a pre-v3 save with Timber Rights is granted builders_hut on load");
        helper.assertTrue(!migrate(old, false).hasTech("builders_hut"),
            "a v3 save with Timber Rights gets no free Builder's Hut");
        helper.succeed();
    }

    // ---------------------------------------------- tannery + carpenter_mason

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void tanneryAndWorkshopsMoveOffCraftAndIndustry(GameTestHelper helper) {
        Fixture f = fixture(helper, "Trades", new BlockPos(3, 1, 3));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        state.unlock(DevelopmentNode.CRAFT_AND_INDUSTRY);
        state.unlock(DevelopmentNode.BORDER_WARDENS);
        var level = helper.getLevel();
        helper.assertTrue(Development.isBuildingUnlocked(level, f.settlement, BuildingType.MINE)
                && Development.isBuildingUnlocked(level, f.settlement, BuildingType.SMELTER)
                && Development.isBuildingUnlocked(level, f.settlement, BuildingType.SMITHY),
            "Mine, Smelter & Smithy keeps its three plans");
        for (BuildingType moved : new BuildingType[] {BuildingType.TANNERY, BuildingType.CARPENTER,
                BuildingType.MASON, BuildingType.WEAVER}) {
            helper.assertTrue(!Development.isBuildingUnlocked(level, f.settlement, moved),
                moved.id() + " moved off Mine, Smelter & Smithy");
        }
        for (Profession moved : new Profession[] {Profession.TANNER, Profession.CARPENTER,
                Profession.MASON, Profession.WEAVER}) {
            helper.assertTrue(!Development.isEmblemUnlocked(level, f.settlement, moved),
                moved + " emblem moved off Mine, Smelter & Smithy");
        }

        fund(f.hearth, TechTreeData.get().node("tannery"));
        TechTree.Result tannery = TechTree.learn(level, f.settlement, f.hearth, "tannery", state.revision(), null);
        helper.assertTrue(tannery == TechTree.Result.LEARNED, "tannery must be learnable with a hide source: " + tannery);
        helper.assertTrue(Development.isBuildingUnlocked(level, f.settlement, BuildingType.TANNERY)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.TANNER),
            "Tannery grants the Tannery plan and the Tanner emblem");

        state.learnTech("carpenter_mason");
        helper.assertTrue(Development.isBuildingUnlocked(level, f.settlement, BuildingType.CARPENTER)
                && Development.isBuildingUnlocked(level, f.settlement, BuildingType.MASON)
                && Development.isBuildingUnlocked(level, f.settlement, BuildingType.WEAVER)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.CARPENTER)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.MASON)
                && Development.isEmblemUnlocked(level, f.settlement, Profession.WEAVER),
            "Carpenter, Mason & Weaver grants all three plans and emblems");

        DevelopmentState old = new DevelopmentState();
        village(old);
        old.unlock(DevelopmentNode.CRAFT_AND_INDUSTRY);
        DevelopmentState migrated = migrate(old, true);
        helper.assertTrue(migrated.hasTech("tannery") && migrated.hasTech("carpenter_mason"),
            "a pre-v3 save with Mine, Smelter & Smithy keeps its Tanner, Carpenter, Mason and Weaver");

        ExtendedTrades.overrideForTests(false);
        boolean tanneryImplemented;
        boolean workshopsImplemented;
        try {
            tanneryImplemented = TechTree.implemented(TechTreeData.get().node("tannery"));
            workshopsImplemented = TechTree.implemented(TechTreeData.get().node("carpenter_mason"));
        } finally {
            ExtendedTrades.overrideForTests(null);
        }
        helper.assertTrue(!tanneryImplemented && !workshopsImplemented,
            "with [features] extendedTrades off both nodes show as Planned");
        helper.assertTrue(TechTree.implemented(TechTreeData.get().node("tannery"))
                && TechTree.implemented(TechTreeData.get().node("carpenter_mason")),
            "with the switch on both nodes are learnable");
        helper.succeed();
    }

    // -------------------------------------------------- craft_and_industry

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void ironToolsWorkFasterAfterMineSmelterSmithy(GameTestHelper helper) {
        Fixture f = fixture(helper, "Tools", new BlockPos(3, 1, 3));
        Fixture other = fixture(helper, "ToolsOther", new BlockPos(12, 1, 12));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        Development.of(helper.getLevel(), other.settlement);
        var level = helper.getLevel();
        ItemStack iron = new ItemStack(Items.IRON_AXE);
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack netherite = new ItemStack(Items.NETHERITE_AXE);
        ItemStack stone = new ItemStack(Items.STONE_PICKAXE);
        helper.assertTrue(CraftEffects.toolTicks(level, f.settlement, iron, 100) == 100
                && CraftEffects.toolTicks(level, f.settlement, diamond, 60) == 60,
            "no tool speed before Mine, Smelter & Smithy");
        state.unlock(DevelopmentNode.CRAFT_AND_INDUSTRY);
        helper.assertTrue(CraftEffects.toolTicks(level, f.settlement, iron, 100) == 90
                && CraftEffects.toolTicks(level, f.settlement, iron, 60) == 54
                && CraftEffects.toolTicks(level, f.settlement, diamond, 60) == 48
                && CraftEffects.toolTicks(level, f.settlement, netherite, 100) == 80,
            "iron tools -10%, diamond/netherite -20% once learned");
        helper.assertTrue(CraftEffects.toolTicks(level, f.settlement, stone, 100) == 100
                && CraftEffects.toolTicks(level, f.settlement, ItemStack.EMPTY, 100) == 100,
            "stone tools and bare hands get nothing");
        helper.assertTrue(CraftEffects.toolTicks(level, other.settlement, iron, 100) == 100,
            "the bonus is per settlement: a neighbour without the node gets nothing");
        helper.succeed();
    }

    /**
     * Live: two identical mines side by side, each with a Miner holding a
     * diamond pickaxe; only settlement A knows Mine, Smelter &amp; Smithy
     * (cut 60 -&gt; 48 ticks per block). A must bank 8 cobblestone first.
     */
    @GameTest(template = "empty32", batch = BATCH, timeoutTicks = 2400)
    public void aRealMinerCutsFasterWithMineSmelterSmithy(GameTestHelper helper) {
        var level = helper.getLevel();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y <= MINE_ROCK_TOP; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                for (int y = MINE_ROCK_TOP + 1; y < 12; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        level.setDayTime(2000);
        helper.onEachTick(() -> {
            long t = Math.floorMod(level.getDayTime(), 24000L);
            if (t < 1500L || t > 5000L) level.setDayTime(2000);
        });
        Settlement taught = mineArena(helper, 0, 0, "MineTaught");
        Settlement plain = mineArena(helper, 16, 16, "MinePlain");
        DevelopmentState state = Development.of(level, taught);
        village(state);
        state.unlock(DevelopmentNode.CRAFT_AND_INDUSTRY);
        village(Development.of(level, plain));
        SettlerEntity fast = liveMiner(helper, taught, 8, 9);
        SettlerEntity slow = liveMiner(helper, plain, 24, 25);
        BlockPos fastChest = new BlockPos(14, MINE_ROCK_TOP + 1, 14);
        BlockPos slowChest = new BlockPos(30, MINE_ROCK_TOP + 1, 30);
        long start = level.getGameTime();
        long[] done = {-1L, -1L};
        helper.onEachTick(() -> {
            long now = level.getGameTime() - start;
            if (done[0] < 0 && cobble(helper, fastChest) >= 8) done[0] = now;
            if (done[1] < 0 && cobble(helper, slowChest) >= 8) done[1] = now;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(done[0] >= 0 && done[1] >= 0,
                "both Miners bank 8 cobblestone (taught " + cobble(helper, fastChest) + " at " + done[0]
                    + ", plain " + cobble(helper, slowChest) + " at " + done[1] + ")");
            helper.assertTrue(done[0] < done[1],
                "the Miner with Mine, Smelter & Smithy and a diamond pick is faster: "
                    + done[0] + " vs " + done[1] + " ticks for 8 blocks");
            fast.discard();
            slow.discard();
        });
    }

    private static final int MINE_ROCK_TOP = 6;

    private static Settlement mineArena(GameTestHelper helper, int ox, int oz, String name) {
        var data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(ox + 8, MINE_ROCK_TOP + 1, oz + 8)));
        s.radius = 8;
        data.settlements.put(s.id, s);
        data.setDirty();
        GameTestFixtures.registerWithBounds(helper, s, BuildingType.MINE,
            new BlockPos(ox + 8, MINE_ROCK_TOP + 1, oz + 8), new BlockPos(ox + 1, MINE_ROCK_TOP + 2, oz + 1),
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(ox + 1, MINE_ROCK_TOP + 1, oz + 1)),
                helper.absolutePos(new BlockPos(ox + 15, MINE_ROCK_TOP + 3, oz + 15))));
        helper.setBlock(new BlockPos(ox + 14, MINE_ROCK_TOP + 1, oz + 14), Blocks.CHEST);
        return s;
    }

    private static SettlerEntity liveMiner(GameTestHelper helper, Settlement s, int x, int z) {
        Building mine = s.buildings.stream().filter(b -> b.type == BuildingType.MINE).findFirst().orElseThrow();
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, MINE_ROCK_TOP + 1, z));
        settler.setSettlerName("Miner" + x);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Miner" + x, Profession.NONE);
        settler.attributes().pinForTest(Attribute.STAMINA, 99);
        settler.attributes().pinForTest(Attribute.STRENGTH, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, mine, settler).ok(), "hire miner");
        settler.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        settler.setHunger(100.0F);
        settler.setEnergy(100.0F);
        return settler;
    }

    private static int cobble(GameTestHelper helper, BlockPos chestRel) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(chestRel)) instanceof Container chest)) return 0;
        int n = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)) n += stack.getCount();
        }
        return n;
    }

    // ------------------------------------------------------- charcoal_kilns

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void charcoalKilnsSpeedEverySmelterBatch(GameTestHelper helper) {
        Fixture f = fixture(helper, "Kilns", new BlockPos(3, 1, 3));
        Fixture other = fixture(helper, "KilnsOther", new BlockPos(12, 1, 12));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        var level = helper.getLevel();
        Production.Recipe iron = recipe(BuildingType.SMELTER, Items.IRON_INGOT);
        Production.Recipe bread = Production.of(BuildingType.BAKERY).get(0);
        int before = Production.ticksFor(level, f.settlement.id, BuildingType.SMELTER, iron);
        int bakeBefore = Production.ticksFor(level, f.settlement.id, BuildingType.BAKERY, bread);
        helper.assertTrue(CraftEffects.smelterScale(level, f.settlement.id, BuildingType.SMELTER) == 1.0D,
            "no kiln effect before learning");
        state.learnTech("charcoal_kilns");
        int after = Production.ticksFor(level, f.settlement.id, BuildingType.SMELTER, iron);
        helper.assertTrue(Math.abs(after - Math.round(before * 0.75D)) <= 1,
            "Smelter batch time x0.75 after Charcoal Kilns: " + before + " -> " + after);
        helper.assertTrue(Math.abs(CraftEffects.smelterScale(level, f.settlement.id, BuildingType.SMELTER) - 0.75D) < 1.0E-6,
            "Smelter effort per batch x0.75 (read by CrafterWorkGoal)");
        helper.assertTrue(Production.ticksFor(level, f.settlement.id, BuildingType.BAKERY, bread) == bakeBefore,
            "other workshops are untouched");
        helper.assertTrue(Production.ticksFor(level, other.settlement.id, BuildingType.SMELTER, iron) == before,
            "the bonus is per settlement");
        helper.succeed();
    }

    // ------------------------------------------------------------ deep_mine

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void deepMineDigsDeeperAndFindsMoreOre(GameTestHelper helper) {
        Fixture f = fixture(helper, "Deep", new BlockPos(3, 1, 3));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        var level = helper.getLevel();
        var ore = Blocks.IRON_ORE.defaultBlockState();
        var rock = Blocks.STONE.defaultBlockState();
        helper.assertTrue(CraftEffects.mineReach(level, f.settlement, 12) == 12
                && extraOres(level, f.settlement, ore, 2000) == 0,
            "no depth or ore bonus before Deep Mine");
        state.learnTech("deep_mine");
        int extra = extraOres(level, f.settlement, ore, 2000);
        helper.assertTrue(CraftEffects.mineReach(level, f.settlement, 12) == 14,
            "the Miner digs 14 blocks deep after Deep Mine");
        helper.assertTrue(extra >= 220 && extra <= 380,
            "about 15% of ore blocks give +1 ore: " + extra + "/2000");
        helper.assertTrue(extraOres(level, f.settlement, rock, 500) == 0,
            "plain stone never gets the ore bonus");
        helper.succeed();
    }

    // -------------------------------------------------- masters_apprentices

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 80)
    public void apprenticesBesideAMasterLearnFaster(GameTestHelper helper) {
        Fixture f = fixture(helper, "Guild", new BlockPos(1, 1, 1));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        Building smithy = GameTestFixtures.register(helper, f.settlement, BuildingType.SMITHY, 4, 4);
        Building mill = GameTestFixtures.register(helper, f.settlement, BuildingType.MILL, 10, 10);
        SettlerEntity master = hire(helper, f.settlement, smithy, "Master", 5, 7);
        SettlerEntity apprentice = hire(helper, f.settlement, smithy, "Apprentice", 6, 7);
        SettlerEntity loner = hire(helper, f.settlement, mill, "Loner", 11, 13);
        Profession trade = master.getProfession();
        master.tradeSkills().add(trade, SkillLevels.xpForLevel(CraftEffects.MASTER_LEVEL));
        helper.assertTrue(SkillLevels.levelOf(master) >= CraftEffects.MASTER_LEVEL
                && SkillLevels.levelOf(apprentice) < CraftEffects.MASTER_LEVEL,
            "fixture: one master and one apprentice at the Smithy");
        int plain = SkillLevels.completeUnit(apprentice, 2);
        helper.assertTrue(plain == 2, "2 XP per job before the node, got " + plain);
        state.learnTech("masters_apprentices");
        int boosted = SkillLevels.completeUnit(apprentice, 2);
        int alone = SkillLevels.completeUnit(loner, 2);
        helper.assertTrue(boosted == 3, "+50% beside a master: 2 -> 3 XP, got " + boosted);
        helper.assertTrue(alone == 2, "a worker with no master at his workplace gets no bonus, got " + alone);
        master.discard();
        int afterMasterLeft = SkillLevels.completeUnit(apprentice, 2);
        helper.assertTrue(afterMasterLeft == 2, "no master present, no bonus, got " + afterMasterLeft);
        apprentice.discard();
        loner.discard();
        helper.succeed();
    }

    // ---------------------------------------------------------- guild_halls

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 80)
    public void guildHallsRollFineGoodsFarMoreOften(GameTestHelper helper) {
        Fixture f = fixture(helper, "Halls", new BlockPos(1, 1, 1));
        DevelopmentState state = Development.of(helper.getLevel(), f.settlement);
        village(state);
        var level = helper.getLevel();
        Building bakery = GameTestFixtures.register(helper, f.settlement, BuildingType.BAKERY, 4, 4);
        BlockPos chestPos = new BlockPos(5, 1, 5);
        helper.setBlock(chestPos, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(chestPos));
        SettlerEntity baker = hire(helper, f.settlement, bakery, "Baker", 6, 7);
        SkillLevels.primaryOf(baker.getProfession()).ifPresent(a -> baker.attributes().pinForTest(a, 0));
        Production.Recipe bread = recipe(BuildingType.BAKERY, Items.BREAD);

        helper.assertTrue(CraftEffects.qualityMeanBonus(level, f.settlement) == 0.0D,
            "no quality bonus before Guild Halls");

        // The real workshop path: the same baker, same bench, before and after.
        Boolean previous = QualityConfig.testOverride;
        QualityConfig.testOverride = true;
        int before;
        int after;
        try {
            before = fineBatches(level, bakery, chest, bread, baker, 200);
            state.learnTech("guild_halls");
            after = fineBatches(level, bakery, chest, bread, baker, 200);
        } finally {
            QualityConfig.testOverride = previous;
        }
        helper.assertTrue(before <= 45 && after >= 55 && after - before >= 25,
            "Fine+ loaves out of 200 at trade level 1: " + before + " -> " + after);

        // Exact odds from the same roll the workshop uses (a 100x100 grid of u1/u2).
        double guildBonus = CraftEffects.qualityMeanBonus(level, f.settlement);
        double baseFine = fineShare(3, 0.0D);
        double guildFine = fineShare(3, guildBonus);
        helper.assertTrue(Math.abs(guildBonus - 2 * CraftedQuality.PER_TRADE_LEVEL) < 1.0E-9
                && baseFine < 0.50D && guildFine > 0.75D,
            "a level-3 crafter: Fine or better " + pct(baseFine) + " -> " + pct(guildFine));
        helper.assertTrue(CraftedQuality.roll(6, 99, 5, 4, guildBonus, 0.999D, 0.999D) <= GoodsQuality.EXCEPTIONAL,
            "Masterwork still needs a real trade level 7");
        baker.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------ fixtures

    private static double fineShare(int tradeLevel, double extraMean) {
        int fine = 0;
        int n = 100;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (CraftedQuality.roll(tradeLevel, 0, 1, 0, extraMean, (i + 0.5D) / n, (j + 0.5D) / n)
                    >= GoodsQuality.FINE) {
                    fine++;
                }
            }
        }
        return fine / (double) (n * n);
    }

    private static String pct(double share) {
        return Math.round(share * 100.0D) + "%";
    }

    /** Runs {@code batches} real bakery batches and counts the Fine-or-better loaves. */
    private static int fineBatches(net.minecraft.server.level.ServerLevel level, Building bakery, Container chest,
                                   Production.Recipe recipe, SettlerEntity baker, int batches) {
        int fine = 0;
        Item input = recipe.input().getItems()[0].getItem();
        for (int i = 0; i < batches; i++) {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                chest.setItem(slot, ItemStack.EMPTY);
            }
            chest.setItem(0, new ItemStack(input, recipe.inputCount()));
            chest.setItem(1, new ItemStack(Items.COAL, 64));
            chest.setItem(2, new ItemStack(Items.CHARCOAL, 64));
            if (!Production.run(level, bakery, recipe, baker)) {
                throw new IllegalStateException("fixture bakery batch did not run");
            }
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(recipe.output()) && GoodsQuality.of(stack) >= GoodsQuality.FINE) {
                    fine++;
                }
            }
        }
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, ItemStack.EMPTY);
        }
        return fine;
    }

    private static int extraOres(net.minecraft.server.level.ServerLevel level, Settlement settlement,
                                 net.minecraft.world.level.block.state.BlockState state, int trials) {
        RandomSource random = RandomSource.create(1234L);
        int extra = 0;
        for (int i = 0; i < trials; i++) {
            if (CraftEffects.extraOre(level, settlement, state, random)) {
                extra++;
            }
        }
        return extra;
    }

    private static Production.Recipe recipe(BuildingType type, Item output) {
        for (Production.Recipe recipe : Production.of(type)) {
            if (recipe.output() == output) {
                return recipe;
            }
        }
        throw new IllegalStateException("no " + output + " recipe at " + type.id());
    }

    private static SettlerEntity hire(GameTestHelper helper, Settlement settlement, Building work,
                                      String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        settler.attributes().pinForTest(Attribute.WITS, 0);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, work, settler).ok(),
            name + " must be hired at the " + work.type.id());
        return settler;
    }

    /** Writes {@code state} and reads it back, as a pre-v3 save ({@code preV3}) or a v3 one. */
    private static DevelopmentState migrate(DevelopmentState state, boolean preV3) {
        CompoundTag tag = state.writeNbt();
        if (preV3) {
            tag.remove("TechNodes");
            tag.remove("TechStudies");
            tag.remove("TechGrandfathered");
        }
        return DevelopmentState.readNbt(tag);
    }

    private static void founding(DevelopmentState state) {
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
    }

    private static void village(DevelopmentState state) {
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlock(DevelopmentNode.ARM_THE_WATCH);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
    }

    private static void fund(HearthBlockEntity hearth, TechNodeDef def) {
        for (DevelopmentNode.Cost cost : TechCosts.costs(def)) {
            put(hearth, cost.item(), cost.count());
        }
    }

    private static Fixture fixture(GameTestHelper helper, String name, BlockPos relative) {
        helper.setBlock(relative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(relative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
            if (existing.is(item) && existing.getCount() + amount <= existing.getMaxStackSize()) {
                existing.grow(amount);
                hearth.getInventory().setStackInSlot(slot, existing);
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}

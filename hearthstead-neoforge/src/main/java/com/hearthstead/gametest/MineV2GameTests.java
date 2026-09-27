package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.MineConfig;
import com.hearthstead.entity.ai.MineFaceTable;
import com.hearthstead.entity.ai.MineShaftPlan;
import com.hearthstead.entity.ai.MineShaftWork;
import com.hearthstead.entity.ai.MinerEscapeGoal;
import com.hearthstead.entity.ai.MinerWorkGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.TownChat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * MINE V2 (owner 27 Sep): the Miner digs a ladder shaft down from the ladder
 * at the Mine's shaft mouth, one lane out, then works the rock face. These
 * run the real goal on flat stone ground with a short shaft and lane
 * ({@link MineConfig#overrideForTests}: level 1 four deep, lanes three long).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MineV2GameTests {
    /** Feet level of the ground; stone below. */
    public static final int GROUND = 10;
    public static final BlockPos MOUTH = new BlockPos(8, GROUND, 8);
    public static final BlockPos CHEST = new BlockPos(4, GROUND, 4);
    public static final int DEPTH_ONE = 4;
    public static final int DEPTH_TWO = 3;
    public static final int LANE = 3;

    public record Fixture(Settlement settlement, Building mine, SettlerEntity miner) {
    }

    /** Flat stone, a shaft ladder on a wall at the mouth, a chest, a Mine and its Miner. */
    public static Fixture mine(GameTestHelper helper, Item pick, ItemStack... chest) {
        MineConfig.overrideForTests(null, DEPTH_ONE, DEPTH_TWO, LANE);
        MineConfig.overrideMinYForTests(-200); // GameTest arenas stand at y=-60
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < GROUND; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
                for (int y = GROUND; y < GROUND + 5; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        helper.setBlock(MOUTH.north(), Blocks.COBBLESTONE);
        helper.setBlock(MOUTH, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        helper.setBlock(CHEST, Blocks.CHEST);
        Container box = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        for (int i = 0; i < chest.length; i++) {
            box.setItem(i, chest[i].copy());
        }
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Gruvby", helper.absolutePos(MOUTH));
        s.radius = 40;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building mine = GameTestFixtures.registerWithBounds(helper, s, BuildingType.MINE,
            new BlockPos(8, GROUND, 5), new BlockPos(8, GROUND + 1, 3),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(3, GROUND - 1, 3)),
                helper.absolutePos(new BlockPos(12, GROUND + 3, 12))));
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, GROUND, 8));
        settler.setSettlerName("Brokk");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Brokk", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, mine, settler).ok(), "hire miner");
        settler.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(pick));
        settler.setHunger(100.0F);
        settler.setEnergy(100.0F);
        return new Fixture(s, mine, settler);
    }

    public static String debug(SettlerEntity settler) {
        StringBuilder out = new StringBuilder();
        for (var wrapped : settler.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MinerWorkGoal work) {
                out.append("[work running=").append(wrapped.isRunning()).append(' ').append(work.shaftDebug()).append(']');
            } else if (wrapped.getGoal() instanceof MinerEscapeGoal escape) {
                out.append("[escape running=").append(wrapped.isRunning()).append(' ').append(escape.debugState()).append(']');
            } else if (wrapped.isRunning()) {
                out.append('[').append(wrapped.getGoal().getClass().getSimpleName()).append(']');
            }
        }
        return "at " + settler.blockPosition() + " note=" + settler.routeFailureNote() + " " + out;
    }

    public static int count(GameTestHelper helper, Item item) {
        Container box = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).is(item)) {
                n += box.getItem(i).getCount();
            }
        }
        return n;
    }

    /** Everything in the chest except the supplies the test put there. */
    public static int mined(GameTestHelper helper) {
        Container box = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack st = box.getItem(i);
            if (!st.isEmpty() && !st.is(Items.LADDER) && !st.is(Items.TORCH)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** Shaft (ladders + open dig column) to level 1, the lane, the face untouched, and face finds in the chest. */
    @GameTest(batch = "mine_v2_shaft", template = "empty16", timeoutTicks = 4000)
    public void mineV2DigsLadderShaftLaneAndWorksTheFace(GameTestHelper helper) {
        Fixture f = mine(helper, Items.IRON_PICKAXE, new ItemStack(Items.LADDER, 16), new ItemStack(Items.TORCH, 4));
        helper.getLevel().setDayTime(1000);
        int floor = GROUND - DEPTH_ONE;
        helper.onEachTick(() -> {
            // Never a fall: whenever he is below ground, a ladder runs from the
            // mouth down to at least his level.
            SettlerEntity m = f.miner();
            BlockPos feet = helper.relativePos(m.blockPosition());
            if (feet.getY() < GROUND) {
                int y = GROUND;
                while (helper.getBlockState(new BlockPos(8, y - 1, 8)).is(Blocks.LADDER)) {
                    y--;
                }
                helper.assertTrue(y <= feet.getY(), "the ladder must reach his level: ladder bottom " + y
                    + ", " + debug(m));
            }
        });
        helper.succeedWhen(() -> {
            for (int y = GROUND - 1; y >= floor; y--) {
                helper.assertTrue(helper.getBlockState(new BlockPos(8, y, 8)).is(Blocks.LADDER), "ladder at " + y + " " + debug(f.miner()));
                helper.assertTrue(helper.getBlockState(new BlockPos(8, y, 9)).isAir(), "dig column open at " + y + " " + debug(f.miner()));
            }
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(8, floor - 1, 8));
            for (int i = 1; i <= LANE; i++) {
                helper.assertTrue(!helper.getBlockState(new BlockPos(8, floor, 9 + i)).isCollisionShapeFullBlock(
                    helper.getLevel(), helper.absolutePos(new BlockPos(8, floor, 9 + i))), "lane cell " + i);
                helper.assertTrue(helper.getBlockState(new BlockPos(8, floor + 1, 9 + i)).isAir(), "lane head " + i);
            }
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(8, floor, 9 + LANE + 1));
            helper.assertBlockPresent(Blocks.TORCH, new BlockPos(8, floor, 10));
            // 2 x 4 shaft cells + 2 x 3 lane cells gave 14 cobblestone; more is face work.
            helper.assertTrue(mined(helper) >= 16 && debug(f.miner()).contains("last=FACE"),
                "face finds in the chest: " + mined(helper) + " " + debug(f.miner()));
            helper.assertTrue(count(helper, Items.LADDER) == 16 - DEPTH_ONE, "one ladder per shaft block: "
                + count(helper, Items.LADDER));
        });
    }

    /** Lava beside the lane and over its head is sealed with the mine's stone; never breached. */
    @GameTest(batch = "mine_v2_shaft", template = "empty16", timeoutTicks = 4000)
    public void mineV2SealsLavaNextToTheLane(GameTestHelper helper) {
        Fixture f = mine(helper, Items.IRON_PICKAXE, new ItemStack(Items.LADDER, 16), new ItemStack(Items.COBBLESTONE, 4));
        helper.getLevel().setDayTime(1000);
        int floor = GROUND - DEPTH_ONE;
        BlockPos side = new BlockPos(9, floor, 11);
        BlockPos over = new BlockPos(8, floor + 2, 12);
        helper.setBlock(side, Blocks.LAVA);
        helper.setBlock(over, Blocks.LAVA);
        helper.onEachTick(() -> {
            for (int i = 0; i <= LANE; i++) {
                for (int y = floor; y <= floor + 1; y++) {
                    helper.assertTrue(helper.getBlockState(new BlockPos(8, y, 9 + i)).getFluidState().isEmpty(),
                        "lava got into the lane at " + i + "," + y);
                }
            }
            helper.assertTrue(!f.miner().isOnFire() && f.miner().getHealth() >= f.miner().getMaxHealth() - 0.01F,
                "the Miner was hurt: " + f.miner().getHealth());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(side).getFluidState().isEmpty(), "the side lava is sealed " + debug(f.miner()));
            helper.assertTrue(helper.getBlockState(over).getFluidState().isEmpty(), "the lava over the lane is sealed");
            helper.assertTrue(helper.getBlockState(new BlockPos(8, floor + 1, 9 + LANE)).isAir(), "the lane is done "
                + debug(f.miner()));
        });
    }

    /** No ladders and nothing to make them from: a "needs Ladders" town chat line and not one block cut. */
    @GameTest(batch = "mine_v2_shaft", template = "empty16", timeoutTicks = 600)
    public void mineV2WithoutLaddersAsksAndDoesNotDig(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absolutePos(new BlockPos(2, GROUND, 2)).getCenter());
        Fixture f = mine(helper, Items.IRON_PICKAXE, new ItemStack(Items.TORCH, 4));
        helper.getLevel().setDayTime(1000);
        AtomicBoolean asked = new AtomicBoolean();
        BiConsumer<ServerPlayer, Component> tap = (p, line) -> {
            if (line.getString().contains("needs Ladders")) {
                asked.set(true);
            }
        };
        TownChat.addTestTap(tap);
        GameTestTicks.at(helper, 500, () -> {
            TownChat.removeTestTap(tap);
            helper.assertTrue(asked.get(), "town chat says the Miner needs Ladders; " + debug(f.miner()));
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(8, GROUND - 1, 8));
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(8, GROUND - 1, 9));
            helper.assertTrue(mined(helper) == 0, "nothing was dug");
            helper.succeed();
        });
    }

    /** At night the Miner at the face climbs the ladder out of the shaft. */
    @GameTest(batch = "mine_v2_shaft", template = "empty16", timeoutTicks = 1600)
    public void mineV2MinerClimbsOutToRest(GameTestHelper helper) {
        Fixture f = mine(helper, Items.IRON_PICKAXE, new ItemStack(Items.LADDER, 16));
        int floor = GROUND - DEPTH_ONE;
        // A finished level 1: ladders, the open dig column, the lane.
        for (int y = GROUND - 1; y >= floor; y--) {
            helper.setBlock(new BlockPos(8, y, 8), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
            helper.setBlock(new BlockPos(8, y, 9), Blocks.AIR);
        }
        for (int i = 1; i <= LANE; i++) {
            helper.setBlock(new BlockPos(8, floor, 9 + i), Blocks.AIR);
            helper.setBlock(new BlockPos(8, floor + 1, 9 + i), Blocks.AIR);
        }
        f.miner().moveTo(helper.absolutePos(new BlockPos(8, floor, 9 + LANE)).getBottomCenter());
        helper.getLevel().setDayTime(13000);
        helper.succeedWhen(() -> helper.assertTrue(
            helper.relativePos(f.miner().blockPosition()).getY() >= GROUND,
            "the Miner must climb out at night: " + debug(f.miner())));
    }

    /** Pick tiers like vanilla, through the shipped level-2 table: a stone pick never finds diamonds. */
    @GameTest(batch = "mine_v2_table", template = "empty16", timeoutTicks = 40)
    public void mineV2StonePickNeverFindsDiamonds(GameTestHelper helper) {
        List<MineFaceTable.Entry> table = MineFaceTable.table(helper.getLevel().getServer().getResourceManager(), 2);
        helper.assertTrue(table.stream().anyMatch(e -> e.item() == Items.DIAMOND), "level 2 lists diamonds");
        RandomSource rng = RandomSource.create(20260927L);
        int iron = 0;
        for (int i = 0; i < 6000; i++) {
            ItemStack found = MineFaceTable.roll(table, new ItemStack(Items.STONE_PICKAXE), rng).stack();
            helper.assertTrue(!found.is(Items.DIAMOND) && !found.is(Items.RAW_GOLD) && !found.is(Items.REDSTONE),
                "a stone pick found " + found);
            iron += found.is(Items.RAW_IRON) ? 1 : 0;
        }
        helper.assertTrue(iron > 300, "a stone pick still finds iron: " + iron);
        int diamonds = 0;
        for (int i = 0; i < 6000; i++) {
            diamonds += MineFaceTable.roll(table, new ItemStack(Items.IRON_PICKAXE), rng).stack().is(Items.DIAMOND) ? 1 : 0;
        }
        helper.assertTrue(diamonds > 0 && diamonds < 200, "an iron pick finds a few diamonds: " + diamonds);
        List<MineFaceTable.Entry> one = MineFaceTable.table(helper.getLevel().getServer().getResourceManager(), 1);
        helper.assertTrue(one.stream().noneMatch(e -> e.item() == Items.DIAMOND), "no diamonds at level 1");
        helper.succeed();
    }

    /** The planner's site finder reads a real Mine: the ladder at the mouth, facing its open side. */
    @GameTest(batch = "mine_v2_table", template = "empty16", timeoutTicks = 40)
    public void mineV2FindsTheShaftLadder(GameTestHelper helper) {
        Fixture f = mine(helper, Items.IRON_PICKAXE);
        MineShaftPlan.Site site = MineShaftWork.find(helper.getLevel(), f.mine());
        helper.assertTrue(site != null && site.mouth().equals(helper.absolutePos(MOUTH))
            && site.facing() == Direction.SOUTH, "shaft site: " + site);
        helper.setBlock(MOUTH, Blocks.AIR);
        helper.assertTrue(MineShaftWork.find(helper.getLevel(), f.mine()) == null,
            "no shaft ladder: no site (the quarry is used)");
        helper.succeed();
    }
}

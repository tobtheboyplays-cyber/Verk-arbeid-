package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.block.FishersChairBlock;
import com.hearthstead.settlement.work.FishingGrounds;
import com.hearthstead.settlement.work.FisherEvidenceSavedData;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Physical rod -> seated cast -> persisted bag -> reachable rack integration. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeFisherGameTests {

    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 450)
    public void fisherWithoutPhysicalRodCannotProduce(GameTestHelper helper) {
        floor(helper,16); clearAir(helper,16,1,3); seal(helper,16,1,3);
        Settlement s=settlement(helper);
        Building b=building(helper,s,BuildingType.FISHERY,8,8);
        helper.setBlock(new BlockPos(9,1,8),ModBlocks.FISH_RACK.get());
        helper.setBlock(new BlockPos(8,1,11),ModBlocks.FISHERS_CHAIR.get().defaultBlockState()
            .setValue(FishersChairBlock.FACING,Direction.EAST));
        for(int x=9;x<=13;x++) for(int z=9;z<=13;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.WATER);
        SettlerEntity fisher=settler(helper,s,"Rodless",7,7);
        fisher.attributes().pinForTest(Attribute.STAMINA,50);
        Employment.hire(helper.getLevel(),s,b,fisher); helper.getLevel().setDayTime(3000);
        helper.runAfterDelay(440,()->{
            Container rack=(Container)helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(9,1,8)));
            helper.assertTrue(countOf(rack,ItemTags.FISHES)==0 && countOf(fisher.bag,ItemTags.FISHES)==0,
                "rodless worker must never create a catch");
            helper.assertTrue(!FisherEvidenceSavedData.hasStoredCatch(helper.getLevel(),s),
                "empty fishery cannot create production evidence");
            helper.succeed();
        });
    }

    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 20)
    public void disconnectedPuddlesDoNotCombineIntoFishingGrounds(GameTestHelper helper) {
        floor(helper,16); clearAir(helper,16,1,3); seal(helper,16,1,3);
        // Twenty-five isolated sources still offer no connected fishing ground.
        for(int x=3;x<=11;x+=2) for(int z=3;z<=11;z+=2) helper.setBlock(new BlockPos(x,0,z),Blocks.WATER);
        helper.setBlock(new BlockPos(2,1,3),ModBlocks.FISHERS_CHAIR.get().defaultBlockState()
            .setValue(FishersChairBlock.FACING,Direction.EAST));
        var scan=FishingGrounds.scan(helper.getLevel(),helper.absolutePos(new BlockPos(8,1,8)));
        helper.assertTrue(!scan.ready(),"disconnected single sources cannot support the chair");
        helper.succeed();
    }

    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 450)
    public void landedBagReturnsToRackWithoutRod(GameTestHelper helper) {
        floor(helper,16); clearAir(helper,16,1,3); seal(helper,16,1,3);
        Settlement s=settlement(helper);
        Building b=building(helper,s,BuildingType.FISHERY,8,8);
        helper.setBlock(new BlockPos(9,1,8),ModBlocks.FISH_RACK.get());
        SettlerEntity fisher=settler(helper,s,"Returning",7,7);
        Employment.hire(helper.getLevel(),s,b,fisher);
        fisher.bag.addItem(new ItemStack(Items.COD,3));
        helper.getLevel().setDayTime(3000);
        helper.succeedWhen(()->{
            Container rack=(Container)helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(9,1,8)));
            helper.assertTrue(countOf(rack,ItemTags.FISHES)==3 && countOf(fisher.bag,ItemTags.FISHES)==0,
                "persisted bag must be physically delivered even without rod or fishing grounds"
                    + " [activity=" + fisher.getActivity() + " pos=" + fisher.blockPosition()
                    + " route=" + fisher.routeFailureNote() + " bagFish=" + countOf(fisher.bag,ItemTags.FISHES)
                    + " rackFish=" + countOf(rack,ItemTags.FISHES) + "]");
            helper.assertTrue(!FisherEvidenceSavedData.hasStoredCatch(helper.getLevel(),s),
                "manually stocked bag must not fabricate autonomous catch evidence");
        });
    }

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** See the class doc's root cause 1: every block {@code
     *  FisherWorkGoal}'s own scan could ever read must be under this
     *  fixture's control, not left to whatever real terrain the structure
     *  happens to be sitting in. */
    private static void clearAir(GameTestHelper helper, int size, int minY, int maxY) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = minY; y <= maxY; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    /**
     * See the class doc's root cause 3 (KF-031): closes the region {@link
     * #clearAir} emptied into a real sealed box — a stone ceiling one block
     * above {@code maxY}, and four stone walls one block outside the
     * {@code size}×{@code size} footprint from {@code minY} through the
     * ceiling — so nothing from the untouched natural terrain around this
     * structure (which really does hold water at this depth) can ever flow
     * back into the space this fixture controls, no matter how long the
     * test keeps ticking. Must run AFTER {@link #clearAir}, which only
     * touches the strictly interior region this never overlaps.
     */
    private static void seal(GameTestHelper helper, int size, int minY, int maxY) {
        int ceilingY = maxY + 1;
        for (int x = -1; x <= size; x++) {
            for (int z = -1; z <= size; z++) {
                helper.setBlock(new BlockPos(x, ceilingY, z), Blocks.STONE_BRICKS);
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int i = -1; i <= size; i++) {
                helper.setBlock(new BlockPos(i, y, -1), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(i, y, size), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(-1, y, i), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(size, y, i), Blocks.STONE_BRICKS);
            }
        }
    }

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
        return GameTestFixtures.register(helper, s, type, x, z);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        helper.assertTrue(FishingGrounds.standable(helper.getLevel(),helper.absolutePos(new BlockPos(x,1,z))),
            "worker fixture must start in a clear standing cell, never under the plaque or its support");
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static int countOf(Container chest, net.minecraft.tags.TagKey<Item> tag) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(tag)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * A real pond, a fishery, a hired fisher, and real fish in the fishery's
     * own chest that did not exist before — nothing else built anywhere in
     * the world (D-007).
     *
     * <p>Layout (see class doc): fishery anchored at the arena's own centre
     * (8,8) so {@code WATER_SEARCH_RADIUS} (6) never has to leave the
     * 16×16 footprint {@link #floor}/{@link #clearAir} actually control;
     * the pond sits immediately southeast of the anchor (offset 1-5), so
     * the nearest dock — {@code FisherWorkGoal} picks the closest, not the
     * first found — is a step away, not a hike across the water.
     */
    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 800)
    public void aHiredFisherActuallyFishes(GameTestHelper helper) {
        floor(helper, 16);
        clearAir(helper, 16, 1, 3);
        seal(helper, 16, 1, 3);
        Settlement s = settlement(helper);
        Building fishery = building(helper, s, BuildingType.FISHERY, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 8), ModBlocks.FISH_RACK.get());
        helper.setBlock(new BlockPos(8, 1, 11), ModBlocks.FISHERS_CHAIR.get().defaultBlockState().setValue(FishersChairBlock.FACING, Direction.EAST));
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(9, 1, 8)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;

        // A real pond: 5x5 = 25 water blocks, comfortably clearing
        // FisherWorkGoal.MIN_ADJACENT_WATER (20), offset only 1-5 blocks
        // from the fishery's own anchor at (8,1,8) -- well inside its
        // WATER_SEARCH_RADIUS (6) with margin, and close enough that the
        // nearest dock is barely a step from the settler's own spawn tile.
        //
        // Carved at y=0 (KF-032, class doc root cause 3), one level BELOW
        // the walking floor at y=1, not sitting in the open y=1 layer the
        // settler actually walks through: floor() already filled all of
        // y=0 solid, so overwriting only this 5x5 to water leaves it
        // surrounded on every side by real stone -- nowhere to spread but
        // its own footprint, no matter how long the test runs. The
        // settler's dock (FisherWorkGoal.dockableDirection's raised-bank
        // case) stands dry at y=1 looking down and across at the water
        // one level below, the ordinary vanilla shoreline shape, so it is
        // never itself a tile the pond could ever grow onto.
        for (int x = 9; x <= 13; x++) {
            for (int z = 9; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.WATER);
            }
        }

        helper.assertTrue(FishingGrounds.scan(helper.getLevel(),fishery.anchor).ready(),
            "fixture must provide a shore chair with clear headroom beside its connected pond");
        SettlerEntity finn = settler(helper, s, "Finn", 7, 7);
        finn.attributes().pinForTest(Attribute.STAMINA, 50);
        finn.attributes().pinForTest(Attribute.DEXTERITY, 5);
        helper.setBlock(new BlockPos(10,1,8), Blocks.BARREL);
        Container rodChest=(Container)helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(10,1,8)));
        rodChest.setItem(0,new ItemStack(com.hearthstead.registry.ModItems.FISHERS_ROD.get()));
        helper.assertTrue(Employment.hire(helper.getLevel(), s, fishery, finn).ok(),
            "a fishery must be able to take a fisher");
        helper.assertTrue(finn.getProfession() == Profession.FISHER,
            "hired into a fishery, they fish");

        helper.getLevel().setDayTime(3000);

        boolean[] sawFishing = new boolean[1];
        long startedAt=helper.getLevel().getGameTime();
        long[] firstCastAfter={-1};
        int[] activeFishingTicks={0};

        helper.succeedWhen(() -> {
            if (finn.getActivity() == SettlerActivity.WORK_FISH) {
                sawFishing[0] = true;
                if(firstCastAfter[0]<0) firstCastAfter[0]=helper.getLevel().getGameTime()-startedAt;
                activeFishingTicks[0]++;
            }
            int fish = countOf(chest, ItemTags.FISHES);
            helper.assertTrue(fish > 0,
                "a hired fisher standing at a real pond must land real fish into "
                    + "the fishery's rack (activity=" + finn.getActivity() + ", pos=" + finn.blockPosition()
                    + ", hand=" + finn.getMainHandItem() + ", rodBarrel=" + rodChest.getItem(0) + ", bagFish=" + countOf(finn.bag,ItemTags.FISHES)
                    + ", grounds=" + FishingGrounds.scan(helper.getLevel(),fishery.anchor)
                    + ", passenger=" + finn.isPassenger() + ", cycle=" + finn.getFisherCycleTick()
                    + ", hunger=" + finn.getHunger() + ", energy=" + finn.getEnergy()
                    + ", route=" + finn.routeFailureNote() + ", firstCastAfter=" + firstCastAfter[0]
                    + ", observedFishingTicks=" + activeFishingTicks[0] + ", data=" + finn.getPersistentData() + ")");
            helper.assertTrue(FisherEvidenceSavedData.hasStoredCatch(helper.getLevel(), s), "physical deposit must record autonomous production");
            helper.assertTrue(rodChest.getItem(0).isEmpty(), "Fisher must acquire the physical rod from workplace storage");
            helper.assertTrue(finn.getMainHandItem().getDamageValue() > 0, "catch must pay real rod durability");
            helper.assertTrue(sawFishing[0],
                "the fisher must actually be seen performing WORK_FISH at some point, "
                    + "not just have the output appear while idle");
        });
    }

    /**
     * A fishery whose "water" is a token puzzle-box (well under
     * {@code MIN_ADJACENT_WATER}) must never produce — a free food printer
     * is exactly the bug this goal's own floor exists to forbid. Runs the
     * whole timeout and asserts the chest is still empty at the end, rather
     * than racing a positive assertion. Same centred layout and the same
     * {@link #clearAir} coverage as the real-pond test above, for the same
     * reason: without it, stray natural water inside the scan box could
     * push this fixture over the floor by accident, which is exactly what
     * happened live before this fix.
     */
    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 450)
    public void aFisheryBuiltInAPuddleProducesNothing(GameTestHelper helper) {
        floor(helper, 16);
        clearAir(helper, 16, 1, 3);
        seal(helper, 16, 1, 3);
        Settlement s = settlement(helper);
        Building fishery = building(helper, s, BuildingType.FISHERY, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 8), ModBlocks.FISH_RACK.get());
        helper.setBlock(new BlockPos(8, 1, 11), ModBlocks.FISHERS_CHAIR.get().defaultBlockState().setValue(FishersChairBlock.FACING, Direction.EAST));
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(9, 1, 8)));
        Container chest = (Container) be;

        // Exactly the plaque's own room requirement (2 water blocks) --
        // enough to build, never enough to fish. Carved at y=0, same as
        // the real pond above and for the same reason (KF-032, class doc
        // root cause 3): floor() already made every other y=0 cell in the
        // footprint solid stone, so these two are boxed in on every side
        // from the moment they're placed and can never spread into
        // anything the goal's own scan would count as more water than
        // this fixture actually put here.
        helper.setBlock(new BlockPos(9, 0, 8), Blocks.WATER);
        helper.setBlock(new BlockPos(9, 0, 9), Blocks.WATER);

        SettlerEntity finn = settler(helper, s, "Finn", 7, 7);
        finn.attributes().pinForTest(Attribute.STAMINA, 50);
        finn.attributes().pinForTest(Attribute.DEXTERITY, 5);
        helper.setBlock(new BlockPos(10,1,8), Blocks.BARREL);
        Container rodChest=(Container)helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(10,1,8)));
        rodChest.setItem(0,new ItemStack(com.hearthstead.registry.ModItems.FISHERS_ROD.get()));
        Employment.hire(helper.getLevel(), s, fishery, finn);
        helper.getLevel().setDayTime(3000);

        helper.runAfterDelay(440, () -> {
            int fish = countOf(chest, ItemTags.FISHES);
            helper.assertTrue(fish == 0,
                "a fishery built in a puddle must never produce fish -- found "
                    + fish + " (activity=" + finn.getActivity() + ")");
            helper.assertTrue(finn.getActivity() != SettlerActivity.WORK_FISH,
                "a puddle-fishery's fisher must never even start fishing");
            helper.succeed();
        });
    }
}

package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.economy.EconomyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.UUID;

/**
 * JOBS MATRIX (owner P1 "alle jobbene må virke", 27 Sep): the BAKER's own work day, which until
 * now was covered only indirectly (Chains / CraftingOrder / Fuel / CourierFoodRoute). A Baker is
 * hired into a real BAKERY through {@link Employment#hire}; the real {@code CrafterWorkGoal} (never
 * {@code Production.run}) does the work:
 * <ol>
 *   <li>bake: wheat and fuel in the bakery's own chest become bread in that chest, 3 wheat per
 *       loaf, conserved exactly, with the Baker seen at the oven ({@code WORK_OVEN});</li>
 *   <li>fetch - bake - store: an empty bakery with fuel and a Warehouse shelf of wheat. The
 *       starved Baker walks to the Warehouse, carries the wheat home in the bag, bakes it and
 *       stores the bread in the bakery chest. Wheat is conserved every tick (shelf + bench + bag +
 *       floor + 3 per loaf) and the Baker is seen carrying it.</li>
 * </ol>
 * The economy fallbacks (self-fetch, upkeep) are on for this batch only.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeBakerGameTests {
    private static final String BATCH = "baker_work_day";

    @BeforeBatch(batch = BATCH)
    public static void tune(ServerLevel level) {
        EconomyConfig.testOverride = Boolean.TRUE;
    }

    @AfterBatch(batch = BATCH)
    public static void untune(ServerLevel level) {
        EconomyConfig.testOverride = null;
    }

    private static void arena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Container chest(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static SettlerEntity baker(GameTestHelper helper, Settlement s, Building bakery, BlockPos at) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), at);
        settler.setSettlerName("Brodd");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Brodd", Profession.NONE);
        settler.attributes().pinForTest(Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, bakery, settler).ok(), "a bakery must take a baker");
        helper.assertTrue(settler.getProfession() == Profession.BAKER, "hired into a bakery, they bake");
        helper.assertTrue(Employment.motionOf(BuildingType.BAKERY) == SettlerActivity.WORK_OVEN,
            "the baker's motion is the oven, not a shared work loop");
        return settler;
    }

    /** Fed and rested every tick: this measures the work loop, not the Baker's own lunch. */
    private static void keepFed(GameTestHelper helper, SettlerEntity settler) {
        helper.getLevel().setDayTime(3000); // inside the morning work phase
        settler.setHunger(100.0F);
        settler.setEnergy(100.0F);
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static int count(ItemStackHandler h, Item item) {
        int n = 0;
        for (int i = 0; i < h.getSlots(); i++) if (h.getStackInSlot(i).is(item)) n += h.getStackInSlot(i).getCount();
        return n;
    }

    private static int onGround(GameTestHelper helper, Item item) {
        int n = 0;
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(24);
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box))
            if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    /** Bake: wheat + fuel in the bakery's own chest become bread there, 3 wheat per loaf. */
    @GameTest(template = "empty16", timeoutTicks = 1200, batch = BATCH)
    public void aHiredBakerBakesBreadAtTheOven(GameTestHelper helper) {
        arena(helper);
        Settlement s = settlement(helper, "Brodheim");
        Building bakery = GameTestFixtures.register(helper, s, BuildingType.BAKERY, 4, 4);
        Container bench = chest(helper, new BlockPos(5, 1, 4));
        bench.setItem(0, new ItemStack(Items.WHEAT, 30));
        bench.setItem(1, new ItemStack(Items.CHARCOAL, 16));
        int wheatBefore = count(bench, Items.WHEAT);
        SettlerEntity brodd = baker(helper, s, bakery, new BlockPos(4, 1, 4));
        final boolean[] atOven = {false};
        helper.onEachTick(() -> {
            keepFed(helper, brodd);
            if (brodd.getActivity() == SettlerActivity.WORK_OVEN) atOven[0] = true;
            int bread = count(bench, Items.BREAD) + count(brodd.bag, Items.BREAD) + onGround(helper, Items.BREAD);
            int wheat = count(bench, Items.WHEAT) + count(brodd.bag, Items.WHEAT) + onGround(helper, Items.WHEAT);
            if (wheat + 3 * bread != wheatBefore) {
                helper.fail("wheat must be conserved: " + wheat + " held + 3 x " + bread + " bread != " + wheatBefore);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(count(bench, Items.BREAD) > 0, "the Baker must bake real bread into the bakery chest"
                + " (activity " + brodd.getActivity() + ", stop " + brodd.logisticsStopReason() + ")");
            helper.assertTrue(atOven[0], "the Baker must be seen at the oven (WORK_OVEN), not bread appearing while idle");
            helper.assertTrue(count(bench, Items.CHARCOAL) <= 16, "fuel is only ever spent, never minted");
        });
    }

    /** Fetch - bake - store: the starved Baker brings wheat home from the Warehouse and bakes it. */
    @GameTest(template = "empty16", timeoutTicks = 3600, batch = BATCH)
    public void aStarvedBakerFetchesWheatFromTheWarehouseAndBakesIt(GameTestHelper helper) {
        arena(helper);
        Settlement s = settlement(helper, "Brodvik");
        Building bakery = GameTestFixtures.register(helper, s, BuildingType.BAKERY, 2, 2);
        Container bench = chest(helper, new BlockPos(3, 1, 2));
        bench.setItem(0, new ItemStack(Items.CHARCOAL, 16));
        GameTestFixtures.register(helper, s, BuildingType.WAREHOUSE, 9, 9);
        Container shelf = chest(helper, new BlockPos(10, 1, 10));
        shelf.setItem(0, new ItemStack(Items.WHEAT, 9));
        SettlerEntity brodd = baker(helper, s, bakery, new BlockPos(2, 1, 2));
        final boolean[] carried = {false};
        final boolean[] atOven = {false};
        helper.onEachTick(() -> {
            keepFed(helper, brodd);
            int bread = count(bench, Items.BREAD) + count(brodd.bag, Items.BREAD) + onGround(helper, Items.BREAD);
            int wheat = count(shelf, Items.WHEAT) + count(bench, Items.WHEAT) + count(brodd.bag, Items.WHEAT)
                + onGround(helper, Items.WHEAT);
            if (wheat + 3 * bread != 9) {
                helper.fail("wheat must be conserved on the whole trip: " + wheat + " held + 3 x " + bread + " bread != 9");
            }
            if (brodd.getActivity() == SettlerActivity.CARRYING && count(brodd.bag, Items.WHEAT) > 0) carried[0] = true;
            if (brodd.getActivity() == SettlerActivity.WORK_OVEN) atOven[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(carried[0], "the Baker must visibly carry the Warehouse wheat home (fetch)");
            helper.assertTrue(count(shelf, Items.WHEAT) < 9, "the wheat really left the Warehouse shelf");
            helper.assertTrue(atOven[0], "the Baker must bake at the oven (WORK_OVEN)");
            helper.assertTrue(count(bench, Items.BREAD) > 0, "the baked bread is stored in the bakery chest"
                + " (activity " + brodd.getActivity() + ", stop " + brodd.logisticsStopReason() + ")");
        });
    }
}

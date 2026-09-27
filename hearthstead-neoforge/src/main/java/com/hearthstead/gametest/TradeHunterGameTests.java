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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TRADES-1's own production-loop proof for HUNTER: a real wild herd near
 * the lodge, a settler hired into it, real meat and hide in the lodge's own
 * chest that did not exist before — AND, in the same run, proof that
 * {@code HunterWorkGoal}'s population floor actually holds: with exactly one
 * more cow than {@code MIN_SPECIES_POPULATION}, a hunter given ample effort
 * to attempt several kills over the test window must still never take the
 * herd below the floor. "a hunter that clears every animal in the chunk
 * permanently is a strictly worse pasture" — this is the test that would go
 * red if the floor check were ever deleted.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeHunterGameTests {

    /** Mirrors HunterWorkGoal.MIN_SPECIES_POPULATION exactly. */
    private static final int MIN_SPECIES_POPULATION = 4;

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // The floor ends exactly at this empty16 arena's edge. Keep wandering
        // wildlife and its real vanilla drops over that authored support: the
        // failed run killed its sole prey at x=15.55 in the open edge cell,
        // where a one-and-a-half-block fence let arrow knockback lift the cow
        // onto its top and the ensuing loot could leave the supported floor.
        // Two full solid courses contain both the animal and its real drops;
        // the Lodge and hunting rules remain untouched.
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x < size; x++) {
                helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, y, size - 1), Blocks.STONE_BRICKS);
            }
            for (int z = 1; z < size - 1; z++) {
                helper.setBlock(new BlockPos(0, y, z), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(size - 1, y, z), Blocks.STONE_BRICKS);
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
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    // Hunter rework: the kill is now carried home as a carcass, dressed on
    // the Lodge floor (this fixture has no Butchering Table, 12 s) and
    // unloaded unit by unit, so the window covers the whole physical loop.
    @GameTest(batch = "trade_hunter", template = "empty16", timeoutTicks = 2600)
    public void aHiredHunterHuntsButNeverBreaksTheFloor(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lodge = building(helper, s, BuildingType.HUNTERS_LODGE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof Container, "the arena chest should be a container");
        Container chest = (Container) be;
        chest.setItem(0, new ItemStack(Items.ARROW, 16));

        // MIN_SPECIES_POPULATION + 1 real wild cows. Keep one nearest prey
        // in a clear lane and the four protected herd members well away from
        // that lane: the old clustered herd let a low-inaccuracy vanilla
        // arrow sequence injure every cow without making one lethal hit.
        // Every cow remains inside HUNT_RADIUS and outside the Lodge bounds.
        int[][] spots = {{7, 8}, {1, 13}, {13, 2}, {13, 13}, {2, 13}};
        List<Cow> herd = new ArrayList<>();
        for (int[] spot : spots) {
            herd.add(helper.spawn(EntityType.COW, new BlockPos(spot[0], 1, spot[1])));
        }
        helper.assertTrue(herd.size() == MIN_SPECIES_POPULATION + 1,
            "the fixture herd must be exactly one over the floor, or this test "
                + "cannot tell a working floor from an unlucky short run");

        SettlerEntity orn = settler(helper, s, "Orn", 4, 4);
        // Ample stamina: this hunter has effort for several kill attempts
        // over the test window, so the floor is what has to stop it, not an
        // exhausted labour pool giving a false pass.
        orn.attributes().pinForTest(Attribute.STAMINA, 100);
        orn.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        helper.assertTrue(Employment.hire(helper.getLevel(), s, lodge, orn).ok(),
            "a hunters lodge must be able to take a hunter");
        helper.assertTrue(orn.getProfession() == Profession.HUNTER,
            "hired into a lodge, they hunt");

        helper.getLevel().setDayTime(3000);

        boolean[] sawHunting = new boolean[1];
        boolean[] sawPhysicalArrow = new boolean[1];
        boolean[] sawSyncedBowUse = new boolean[1];

        helper.onEachTick(() -> {
            if (orn.getActivity() == SettlerActivity.WORK_HUNT
                && orn.isUsingItem()) {
                sawSyncedBowUse[0] = true;
            }
            if (!helper.getLevel().getEntitiesOfClass(Arrow.class,
                    orn.getBoundingBox().inflate(32.0D)).isEmpty()) {
                sawPhysicalArrow[0] = true;
            }
        });

        helper.succeedWhen(() -> {
            if (orn.getActivity() == SettlerActivity.WORK_HUNT) {
                sawHunting[0] = true;
            }
            int alive = 0;
            for (Cow c : herd) {
                if (c.isAlive()) {
                    alive++;
                }
            }
            // THE POPULATION FLOOR, checked every tick this test polls, not
            // only at the end: population only ever falls, so once it dips
            // under the floor it never recovers on its own -- a hard fail()
            // here catches the violation on the tick it happens rather than
            // waiting for a timeout to report it as a vaguer "never
            // succeeded".
            if (alive < MIN_SPECIES_POPULATION) {
                helper.fail("HunterWorkGoal's population floor was violated: only "
                    + alive + " of " + herd.size() + " cows remain alive "
                    + "(floor is " + MIN_SPECIES_POPULATION + ")");
            }
            int meat = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.is(Items.BEEF) || stack.is(Items.LEATHER)) {
                    meat += stack.getCount();
                }
            }
            helper.assertTrue(meat > 0,
                "a hired hunter must bring back real meat or hide into the lodge's "
                    + "own chest (activity=" + orn.getActivity() + ", alive=" + alive + ")"
                    + (meat > 0 ? "" : hunterDeliveryDiagnostic(helper, orn, chest, herd,
                        sawHunting[0], sawPhysicalArrow[0], sawSyncedBowUse[0])));
            helper.assertTrue(sawHunting[0],
                "the hunter must actually be seen performing WORK_HUNT at some point, "
                    + "not just have the output appear while idle");
            helper.assertTrue(sawSyncedBowUse[0],
                "the draw must use the server-synced vanilla bow-use state");
            int arrows = orn.carriedArrowCount();
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(Items.ARROW)) {
                    arrows += chest.getItem(slot).getCount();
                }
            }
            helper.assertTrue(sawPhysicalArrow[0] && arrows < 16,
                "the kill must pass through a spawned real Arrow and consume "
                    + "at least one lodge-owned shaft; sawArrow="
                    + sawPhysicalArrow[0] + "; remaining=" + arrows);
        });
    }

    /** Read-only timeout context; this does not assert a kill or complete a delivery. */
    private static String hunterDeliveryDiagnostic(GameTestHelper helper, SettlerEntity hunter,
                                                    Container chest, List<Cow> herd,
                                                    boolean sawHunting, boolean sawArrow,
                                                    boolean sawBowUse) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int droppedOwnedLoot = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(origin).inflate(32.0D),
                item -> item.isAlive() && hunter.getUUID().equals(item.getTarget()))
            .stream().mapToInt(item -> meatOrHide(item.getItem())).sum();
        StringBuilder cows = new StringBuilder();
        for (int index = 0; index < herd.size(); index++) {
            Cow cow = herd.get(index);
            cows.append(index).append(":alive=").append(cow.isAlive())
                .append(",removed=").append(cow.isRemoved())
                .append(",health=").append(cow.getHealth())
                .append(",pos=").append(cow.position()).append(';');
        }
        int bagLoot = 0;
        for (int slot = 0; slot < hunter.bag.getContainerSize(); slot++) {
            bagLoot += meatOrHide(hunter.bag.getItem(slot));
        }
        int chestArrows = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).is(Items.ARROW)) {
                chestArrows += chest.getItem(slot).getCount();
            }
        }
        int carcasses = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(origin).inflate(32.0D),
                item -> item.isAlive() && com.hearthstead.item.CarcassItem.isCarcass(item.getItem())).size()
            + (com.hearthstead.item.CarcassItem.isCarcass(hunter.getOffhandItem()) ? 1 : 0);
        return "; carcassesLooseOrCarried=" + carcasses
            + ", activityNow=" + hunter.getActivity()
            + ", bagMeatOrHide=" + bagLoot
            + ", offhandMeatOrHide=" + meatOrHide(hunter.getOffhandItem())
            + ", targetOwnedDroppedMeatOrHideWithinOrigin32=" + droppedOwnedLoot
            + ", sawHunting=" + sawHunting + ", sawPhysicalArrow=" + sawArrow
            + ", sawSyncedBowUse=" + sawBowUse
            + ", carriedArrows=" + hunter.carriedArrowCount() + ", chestArrows=" + chestArrows
            + ", hunterPos=" + hunter.position() + ", navDone=" + hunter.getNavigation().isDone()
            + ", navTarget=" + hunter.getNavigation().getTargetPos()
            + ", route=" + hunter.routeFailureNote() + ", origin=" + origin
            + ", lodgeChest=" + helper.absolutePos(new BlockPos(5, 1, 4))
            + ", fixtureCows=[" + cows + "]";
    }

    private static int meatOrHide(ItemStack stack) {
        return stack.is(Items.BEEF) || stack.is(Items.LEATHER) ? stack.getCount() : 0;
    }
}

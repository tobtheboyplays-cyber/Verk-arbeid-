package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.ai.RaiderBreachGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SLICE RAIDER-BREACH — the physical half of DESIGN.md system 5 that was
 * missing before this: "Raiders hunt settlers, breach gates/barricades,
 * steal from real chests, commit arson". Theft ({@code RaiderLootGoal}) and
 * arson ({@link RaidDirector#tickArson}) already had coverage; these pin the
 * breach goal ({@link RaiderBreachGoal}) and re-confirm theft's conservation
 * arithmetic under the new pressure of a raider that can now knock down
 * what stands in its way.
 *
 * <p>Every raider here is genuinely walking toward a real KORN loot chest
 * (via {@code RaiderLootGoal}, unmodified) rather than being hand-placed
 * already adjacent to whatever it must breach: {@link RaiderBreachGoal}'s
 * own "am I actually blocked" detector keys off {@code
 * Entity#horizontalCollision} (see its class doc), which only ever fires
 * from a real, failed movement attempt — so the fixtures have to produce
 * one honestly, the same way a real raid would.
 *
 * <p>Every scenario here is built the same way {@code RepairGameTests}
 * verifies the other end of this same ledger: seed the world directly, run
 * real ticks, and read the outcome back from {@link RaidDirector}'s own
 * public API — never from intended behaviour.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidDamageGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Breachholm",
            helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /** A raid genuinely on, the same shape every other raid fixture uses. */
    private static RaidCaptain beginRaid(ServerLevel level, Settlement s,
                                         RaidObjective objective) {
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        s.pendingRaid = new RaidPlan(captain.id(), objective, 0.0F, 1L);
        return captain;
    }

    /**
     * Walls a 3x3 interior (x=8..10, z=8..10 — the exact footprint {@code
     * RaiderGameTests}' warehouse fixture already uses) with stone brick
     * from y=1..3 and a roof at y=4, leaving a gap in the west wall at
     * z=9 for the caller to fill. {@code gapHeight} 1 leaves only y=1 open
     * (a chest-height gate); 2 leaves y=1..2 open (a door-height gate).
     */
    private static void buildWalledRoom(GameTestHelper helper, int gapHeight) {
        for (int x = 7; x <= 11; x++) {
            for (int z = 7; z <= 11; z++) {
                boolean edge = x == 7 || x == 11 || z == 7 || z == 11;
                if (!edge) {
                    continue; // interior stays the open air buildArena set
                }
                for (int y = 1; y <= 3; y++) {
                    if (x == 7 && z == 9 && y <= gapHeight) {
                        continue; // the gate gap
                    }
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
                }
            }
        }
        for (int x = 7; x <= 11; x++) {
            for (int z = 7; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICKS); // roof
            }
        }
    }

    /**
     * Registers a real, lootable KORN target at (9,1,9) — a plaque at the
     * building's corner, a WAREHOUSE {@link Building} whose bounds cover
     * it (so {@code RaiderLootGoal}'s own {@code WarehouseIndex} walk
     * actually finds it), and the chest stocked with {@code count} wheat.
     * The exact fixture {@code RaiderGameTests}' theft test uses.
     */
    private static Container installLootChest(GameTestHelper helper, Settlement s, int count) {
        BlockPos chestRel = new BlockPos(9, 1, 9);
        helper.setBlock(chestRel, Blocks.CHEST);
        var bounds = BoundingBox.fromCorners(helper.absolutePos(new BlockPos(8, 1, 8)),
            helper.absolutePos(new BlockPos(10, 3, 10)));
        helper.setBlock(new BlockPos(8, 1, 8), ModBlocks.PLAQUE.get());
        Building warehouse = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(new BlockPos(8, 1, 8)),
            helper.absolutePos(new BlockPos(8, 1, 8)), bounds);
        warehouse.valid = true;
        s.buildings.add(warehouse);
        Container chest = (Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(chestRel));
        helper.assertTrue(chest != null, "the store fixture must actually be a container");
        chest.setItem(0, new ItemStack(Items.WHEAT, count));
        return chest;
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

    /** {@code Mob#goalSelector} is public (vanilla), same access {@code
     * ArcherGameTests} already relies on to reach into a goal by type. */
    private static RaiderBreachGoal breachGoalOf(RaiderEntity raider) {
        for (WrappedGoal wrapped : raider.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof RaiderBreachGoal breach) {
                return breach;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- (a) ---

    /**
     * A raider genuinely trying to reach a real loot chest, sealed behind a
     * door it cannot open (an iron one — mobs never open those, vanilla or
     * otherwise), does not mill about at it: it chops through, and {@link
     * RaidDirector#recordScar} is called for the door's own exact original
     * state before the block ever changes — exactly the ordering {@link
     * RaidDirector}'s class doc demands.
     */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "raid_damage_day")
    public void breachingADoorScarsItBeforeDestroyingIt(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(9, 1, 9));
        buildWalledRoom(helper, 2);
        BlockPos doorLowerRel = new BlockPos(7, 1, 9);
        BlockPos doorUpperRel = new BlockPos(7, 2, 9);
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        helper.setBlock(doorLowerRel, lower);
        helper.setBlock(doorUpperRel, upper);
        BlockPos doorLowerAbs = helper.absolutePos(doorLowerRel);
        BlockState placedDoor = helper.getLevel().getBlockState(doorLowerAbs);
        helper.assertTrue(placedDoor.getBlock() == Blocks.IRON_DOOR,
            "fixture must actually place the door");

        var level = helper.getLevel();
        RaidCaptain captain = beginRaid(level, s, RaidObjective.KORN);
        installLootChest(helper, s, 20);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 9));
        raider.assign(captain.id(), s.id, RaidObjective.KORN, 1.0F, false);
        raider.setObjectivePos(s.center);

        helper.succeedWhen(() -> {
            List<RaidDirector.Scar> scars = RaidDirector.scarsOf(level, s.id);
            RaidDirector.Scar doorScar = null;
            for (RaidDirector.Scar scar : scars) {
                if (scar.pos().equals(doorLowerAbs)) {
                    doorScar = scar;
                }
            }
            helper.assertTrue(doorScar != null,
                "the door's lower half must be scarred before it goes");
            helper.assertTrue(doorScar.original() == placedDoor,
                "the scar must hold the door's own original state, got "
                    + doorScar.original());
            BlockState now = level.getBlockState(doorLowerAbs);
            helper.assertTrue(!(now.getBlock() instanceof DoorBlock),
                "the door must actually be gone by the time it is scarred, got "
                    + now);
            RaiderBreachGoal breach = breachGoalOf(raider);
            helper.assertTrue(breach != null && breach.breaksUsed() == 1,
                "exactly one break should be spent on the door");
        });
    }

    // --------------------------------------------------------------- (b) ---

    /**
     * Theft is physical: at every instant the raider is looting, chest
     * count plus carried count equals the original total. Nothing is ever
     * duplicated and nothing is ever destroyed — see {@code RaiderLootGoal}.
     */
    @GameTest(template = "empty16", timeoutTicks = 900, batch = "raid_damage_day")
    public void lootingMovesItemsRatherThanDuplicatingThem(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        var level = helper.getLevel();
        RaidCaptain captain = beginRaid(level, s, RaidObjective.KORN);
        final int total = 20;
        Container chest = installLootChest(helper, s, total);

        RaiderEntity thief = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 9));
        thief.assign(captain.id(), s.id, RaidObjective.KORN, 1.0F, false);
        thief.setObjectivePos(s.center);

        helper.succeedWhen(() -> {
            int inChest = countOf(chest, Items.WHEAT);
            int carried = thief.lootCount();
            helper.assertTrue(inChest + carried == total,
                "wheat must be conserved at every instant: chest=" + inChest
                    + " carried=" + carried);
            helper.assertTrue(carried > 0,
                "the raider should be carrying some of it by now");
            helper.assertTrue(inChest < total,
                "and the chest must be genuinely lighter, not just counted down");
        });
    }

    // --------------------------------------------------------------- (c) ---

    /**
     * Kill a laden raider and the goods come back — conservation across the
     * WHOLE scenario: whatever is not in the chest anymore is either on the
     * raider or, once it is dead, on the ground where it fell.
     */
    @GameTest(template = "empty16", timeoutTicks = 900, batch = "raid_damage_day")
    public void aKilledRaiderDropsExactlyWhatItCarried(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        var level = helper.getLevel();
        RaidCaptain captain = beginRaid(level, s, RaidObjective.KORN);
        final int total = 20;
        Container chest = installLootChest(helper, s, total);

        RaiderEntity thief = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(7, 1, 9));
        thief.assign(captain.id(), s.id, RaidObjective.KORN, 1.0F, false);
        thief.setObjectivePos(s.center);

        helper.runAtTickTime(300, () -> {
            int carriedAtDeath = thief.lootCount();
            helper.assertTrue(carriedAtDeath > 0,
                "the raider should have grabbed something well before now");
            BlockPos deathPos = thief.blockPosition();
            thief.kill();
            helper.assertTrue(!thief.isAlive(), "the killing blow must land");

            int inChest = countOf(chest, Items.WHEAT);
            int dropped = 0;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(deathPos).inflate(3.0), e -> e.getItem().is(Items.WHEAT))) {
                dropped += item.getItem().getCount();
            }
            helper.assertTrue(dropped > 0,
                "a killed thief must actually drop what it was carrying");
            helper.assertTrue(inChest + dropped == total,
                "conservation across the whole raid: chest=" + inChest
                    + " dropped=" + dropped + " total=" + total);
            helper.succeed();
        });
    }

    // --------------------------------------------------------------- (d) ---

    /**
     * A chest sits directly in the gap a raider genuinely trying to reach
     * its real loot chest must pass. It is never broken — a block entity
     * anywhere in the candidate set is skipped outright — even though the
     * raider keeps trying at point-blank range for long enough to spend its
     * ENTIRE per-raid break budget on whatever else is reachable instead.
     */
    @GameTest(template = "empty16", timeoutTicks = 1400, batch = "raid_damage_day")
    public void aChestBlockingTheWayIsNeverBroken(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(9, 1, 9));
        buildWalledRoom(helper, 1);
        BlockPos gateRel = new BlockPos(7, 1, 9);
        helper.setBlock(gateRel, Blocks.CHEST);
        BlockPos gateAbs = helper.absolutePos(gateRel);
        Container gateChest = (Container) helper.getLevel().getBlockEntity(gateAbs);
        helper.assertTrue(gateChest != null, "the gate fixture must be a real chest");
        gateChest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));

        var level = helper.getLevel();
        RaidCaptain captain = beginRaid(level, s, RaidObjective.KORN);
        // The REAL loot target, registered as a warehouse so RaiderLootGoal
        // actually paths toward it -- the gate chest above is deliberately
        // NOT part of any building, so it is never a loot target itself,
        // only ever the thing physically standing in the way of this one.
        installLootChest(helper, s, 20);

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(2, 1, 9));
        raider.assign(captain.id(), s.id, RaidObjective.KORN, 1.0F, false);
        raider.setObjectivePos(s.center);

        helper.runAtTickTime(1200, () -> {
            BlockState now = level.getBlockState(gateAbs);
            helper.assertTrue(level.getBlockEntity(gateAbs) instanceof Container,
                "the chest must still be a real container, got " + now);
            helper.assertTrue(countOf(gateChest, Items.IRON_INGOT) == 5,
                "and its contents must be untouched, got "
                    + countOf(gateChest, Items.IRON_INGOT));
            helper.assertTrue(!RaidDirector.hasScarAt(level, s.id, gateAbs),
                "the chest must never even be recorded as a wound");
            for (RaidDirector.Scar scar : RaidDirector.scarsOf(level, s.id)) {
                helper.assertTrue(!scar.pos().equals(gateAbs),
                    "no scar may ever name the chest's position");
            }
            RaiderBreachGoal breach = breachGoalOf(raider);
            helper.assertTrue(breach != null && breach.breaksUsed() > 0,
                "the raider must actually have kept trying nearby, not "
                    + "simply given up -- otherwise this test would be "
                    + "proving nothing");
            helper.succeed();
        });
    }
}

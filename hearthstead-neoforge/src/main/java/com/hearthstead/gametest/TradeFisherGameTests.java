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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * TRADES-1's own production-loop proof for FISHER: a real pond next to the
 * fishery (clearing the goal's own {@code MIN_ADJACENT_WATER} floor — see
 * {@code FisherWorkGoal}'s class doc for why a token puzzle-box of water is
 * not enough), a settler hired into it, and real fish in the fishery's own
 * chest that did not exist before.
 *
 * <h2>Three root causes, live suite runs (2026-08-26)</h2>
 *
 * <p><b>1. {@code empty16} is not a cleared void.</b> It is a real structure
 * placed into real generated world terrain at a fixed, quite deep Y
 * (observed around Y=-59) — only the blocks a fixture explicitly sets are
 * guaranteed. Every other trade test's settler only ever touches its own
 * spawn tile and whatever it explicitly placed, so this never showed up
 * before {@code FisherWorkGoal}'s dock search needed real, standable,
 * passable, WATER-FREE ground in a whole radius around a real pond.
 * Diagnostic logging against a live run proved it two different ways: the
 * "shore" this test expected to be open air was solid natural stone
 * (blocking every dock candidate), and — once that was fixed — the puddle
 * test intermittently failed too, with the fisher starting to fish off
 * water this fixture never placed. {@link #clearAir} and {@link #seal}
 * (real stone on every side the fixture's own placed blocks don't already
 * cover) close this off for good: nothing this fixture did not explicitly
 * place can ever be read by the goal's scan.
 *
 * <p><b>2. A dock chosen without regard to distance can send the fisher
 * wading STRAIGHT THROUGH the pond</b> to reach it — a real, valid,
 * water-adjacent tile is still a legitimate dock, but the path there can
 * resolve to a line of {@code PathType.WATER} nodes, which wades rather
 * than walks and can stall past the goal's own patience budget. Fixed in
 * {@code FisherWorkGoal.findFishingSpot} itself (nearest candidate, not
 * first-found) — the class doc there has the full story.
 *
 * <p><b>3. A flush dock is a live spread target for its OWN pond
 * (KF-032).</b> The first fix for root cause 1 placed the pond flush with
 * the walking floor — the settler's dock standing on open air at the
 * water's own height, directly touching a live source block. That is not
 * an outside-contamination problem (sealing the fixture's outer boundary,
 * tried first, changed nothing): a water SOURCE block spreads to fill any
 * open space it can still reach on its own, with no outside water needed
 * at all, and the dock tile itself — open air, immediately beside a
 * source — is the very first thing it reaches. A live per-tick trace
 * caught both faces of this directly: the puddle-test fisher correctly
 * refused at tick 2 (waterSeen=2, its own placed puddle only) but a SECOND
 * scan later in the same run saw waterSeen=115 at the identical anchor and
 * started fishing anyway — its own two source blocks had spread to fill
 * the whole open room around them; and the real-pond test's own fisher,
 * already fishing correctly, was seen drifting one block every 10-20 ticks
 * straight off its dock with {@code dockPos} never changing — not a
 * pathing bug, but vanilla flowing-water push physics, once the dock's own
 * tile had itself been claimed by the pond's spread. The actual fix is the
 * ordinary vanilla shoreline shape: {@code FisherWorkGoal.dockableDirection}
 * now also accepts a <em>raised-bank</em> dock (water one level below the
 * dock, not level with it — see its own class doc), and this fixture
 * places both ponds one level BELOW the walking floor, carved into {@link
 * #floor}'s own solid y=0 layer rather than sitting in {@link #clearAir}'s
 * open one. A dock's own foot level is then solid ground on every side —
 * never itself an open tile the pond could ever spread onto, no matter how
 * long the test keeps ticking.
 *
 * <p>The fixture fix for root cause 1: {@link #clearAir} (and {@link
 * #seal}) cover the WHOLE region {@code FisherWorkGoal}'s own {@code
 * WATER_SEARCH_RADIUS} (6) can ever read, not just "wherever this test
 * happens to place things" — the fishery sits at the arena's own centre
 * (relative (8,8)) specifically so that anchor ± 6 never has to leave the
 * 16×16 footprint this template actually owns.
 *
 * <p><b>4. The chest sat one block outside the building's own bounds
 * (KF-033).</b> With root causes 1-3 fixed the fisher docked and fished
 * flawlessly every time — six real catches, bag hit {@code BAG_TRIGGER},
 * clean switch to the deposit trip — and then sat "activity=TRAVELING"
 * for the rest of the run anyway, forever re-arriving at the fishery's own
 * anchor and never actually emptying its bag. {@code
 * WarehouseIndex.containers} only ever looks inside {@code
 * building.bounds}, and {@code GameTestFixtures.register} sets that bounds
 * to a 4×3×4 box starting AT the anchor — {@code (x,1,z)} to {@code
 * (x+3,y+2,z+3)} — never one block outside it. This fixture's chest sat at
 * relative (7,1,8), one column WEST of an anchor at (8,1,8): a real,
 * working, player-visible chest that the goal's own warehouse lookup could
 * never see, so every deposit trip silently found zero containers, put
 * nothing anywhere, and immediately went looking for the next (still full)
 * trip forever. Not a pathing bug, not a threshold, and nothing {@code
 * FisherWorkGoal} did wrong — moving the chest to relative (9,1,8), inside
 * the building's own bounds, was the entire fix.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeFisherGameTests {

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
        helper.setBlock(new BlockPos(9, 1, 8), Blocks.CHEST);
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

        SettlerEntity finn = settler(helper, s, "Finn", 8, 8);
        finn.attributes().pinForTest(Attribute.STAMINA, 50);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, fishery, finn).ok(),
            "a fishery must be able to take a fisher");
        helper.assertTrue(finn.getProfession() == Profession.FISHER,
            "hired into a fishery, they fish");

        helper.getLevel().setDayTime(3000);

        boolean[] sawFishing = new boolean[1];

        helper.succeedWhen(() -> {
            if (finn.getActivity() == SettlerActivity.WORK_FISH) {
                sawFishing[0] = true;
            }
            int fish = countOf(chest, ItemTags.FISHES);
            helper.assertTrue(fish > 0,
                "a hired fisher standing at a real pond must land real fish into "
                    + "the fishery's own chest (activity=" + finn.getActivity() + ")");
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
    @GameTest(batch = "trade_fisher", template = "empty16", timeoutTicks = 200)
    public void aFisheryBuiltInAPuddleProducesNothing(GameTestHelper helper) {
        floor(helper, 16);
        clearAir(helper, 16, 1, 3);
        seal(helper, 16, 1, 3);
        Settlement s = settlement(helper);
        Building fishery = building(helper, s, BuildingType.FISHERY, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 8), Blocks.CHEST);
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

        SettlerEntity finn = settler(helper, s, "Finn", 8, 8);
        finn.attributes().pinForTest(Attribute.STAMINA, 50);
        Employment.hire(helper.getLevel(), s, fishery, finn);
        helper.getLevel().setDayTime(3000);

        helper.runAfterDelay(190, () -> {
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

package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.research.Research;
import com.hearthstead.settlement.research.ResearchKey;
import com.hearthstead.settlement.research.ResearchProject;
import com.hearthstead.settlement.research.ResearchState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE RESEARCH-1 — Prøvebenken: starting a project pays for it once and
 * exactly, progress moves only through an employed, working scholar (plus
 * the documented daily trickle), a finished project's bonus is retrievable
 * and survives a save/load round trip, and cancelling refunds the
 * documented share and no more.
 *
 * <p>Fixtures mirror {@code EmploymentGameTests}'s own: a settlement not
 * swept from the shared level, a small building whose bounds a chest sits
 * inside (so {@code WarehouseIndex.containers} finds it, the same lookup
 * {@code Research} itself uses), and settlers spawned already at their
 * post so a test proves the research system, not the pathfinder.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ResearchGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** See {@code EmploymentGameTests#settlement} for why this does not
     *  sweep the shared level's other settlements. */
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

    /** A registered, valid architects' study, its bounds wide enough to hold
     *  a chest one block off the anchor. */
    private static Building study(GameTestHelper helper, Settlement s, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, BuildingType.ARCHITECTS_STUDY, x, z);
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

    private static Container chestAt(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.CHEST);
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, 1, z)));
        return (Container) be;
    }

    private static void put(Container chest, Item item, int count) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) {
                chest.setItem(slot, new ItemStack(item, count));
                return;
            }
        }
    }

    private static int countIn(Container chest, Item item) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------- (a) ---

    /** Starting a project takes exactly its costs, from the study's own
     *  chest — conservation, not "some items disappear." */
    @GameTest(batch = "research", template = "empty16", timeoutTicks = 200)
    public void startingConsumesExactlyItsCosts(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lab = study(helper, s, 4, 4);
        Container chest = chestAt(helper, 5, 4);
        put(chest, Items.PAPER, 4);
        put(chest, Items.WHEAT, 16);

        Research.Refusal refusal = Research.start(helper.getLevel(), s, lab,
            ResearchProject.BEDRE_GJAER);

        helper.assertTrue(refusal == null, "a fully-stocked chest must be able to start");
        helper.assertTrue(countIn(chest, Items.PAPER) == 0,
            "every paper spent must actually leave the chest, found "
                + countIn(chest, Items.PAPER));
        helper.assertTrue(countIn(chest, Items.WHEAT) == 0,
            "every wheat spent must actually leave the chest, found "
                + countIn(chest, Items.WHEAT));
        ResearchState state = Research.of(helper.getLevel(), s.id);
        helper.assertTrue(state.active != null && state.active.project == ResearchProject.BEDRE_GJAER,
            "the chosen project must become the active one");
        helper.assertTrue(state.active.sessions == 0, "no work has happened yet");
        helper.succeed();
    }

    /** A chest that cannot pay every line is refused, and refused whole:
     *  nothing already there is taken (atomic, not partial). */
    @GameTest(batch = "research", template = "empty16", timeoutTicks = 200)
    public void startingRefusesWhenMaterialsAreShort(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lab = study(helper, s, 4, 4);
        Container chest = chestAt(helper, 5, 4);
        put(chest, Items.PAPER, 4);
        put(chest, Items.WHEAT, 3); // short of BEDRE_GJAER's 16

        Research.Refusal refusal = Research.start(helper.getLevel(), s, lab,
            ResearchProject.BEDRE_GJAER);

        helper.assertTrue(refusal != null, "a short chest must refuse to start");
        helper.assertTrue("hearthstead.research.refused.materials".equals(refusal.key()),
            "and say why, got " + refusal.key());
        helper.assertTrue(countIn(chest, Items.PAPER) == 4,
            "a refused start must take nothing, paper found "
                + countIn(chest, Items.PAPER));
        helper.assertTrue(countIn(chest, Items.WHEAT) == 3,
            "a refused start must take nothing, wheat found "
                + countIn(chest, Items.WHEAT));
        helper.assertTrue(Research.of(helper.getLevel(), s.id).active == null,
            "and nothing becomes active");
        helper.succeed();
    }

    // ------------------------------------------------------------- (b) ---

    /** With no scholar employed at all, an active project does not move —
     *  neither the work goal (nobody to run it) nor the daily trickle
     *  (which itself requires an employed worker) has anything to advance. */
    @GameTest(batch = "research", template = "empty16", timeoutTicks = 120)
    public void progressDoesNotMoveWithNoScholarEmployed(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lab = study(helper, s, 4, 4);
        Container chest = chestAt(helper, 5, 4);
        put(chest, Items.PAPER, 4);
        put(chest, Items.WHEAT_SEEDS, 16);
        Research.start(helper.getLevel(), s, lab, ResearchProject.AAKERSKIFTE);

        helper.runAtTickTime(100, () -> {
            ResearchState state = Research.of(helper.getLevel(), s.id);
            helper.assertTrue(state.active != null && state.active.sessions == 0,
                "no scholar means no session, got " + state.active.sessions);
            helper.succeed();
        });
    }

    /** Hire a scholar, stand them at the lectern in working hours, and the
     *  active project's sessions actually climb — the whole loop the owner
     *  asked for, end to end, with no shortcut through {@code
     *  Research.advanceSession} directly. */
    @GameTest(batch = "research", template = "empty16", timeoutTicks = 900)
    public void anEmployedScholarActuallyAdvancesTheProject(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lab = study(helper, s, 4, 4);
        Container chest = chestAt(helper, 5, 4);
        put(chest, Items.PAPER, 4);
        put(chest, Items.WHEAT_SEEDS, 16);
        Research.start(helper.getLevel(), s, lab, ResearchProject.AAKERSKIFTE);

        SettlerEntity scholar = settler(helper, s, "Gro", 4, 4); // AT the lectern already
        helper.assertTrue(Employment.hire(helper.getLevel(), s, lab, scholar).ok(),
            "an architects' study must be able to take a scholar");
        helper.assertTrue(scholar.getProfession() == Profession.SCHOLAR,
            "hired into the study, they take up the trade");
        helper.getLevel().setDayTime(3000); // mid-morning: working hours

        helper.succeedWhen(() -> {
            ResearchState state = Research.of(helper.getLevel(), s.id);
            boolean progressed = state.active != null && state.active.sessions > 0;
            boolean finished = state.active == null
                && state.completed.contains(ResearchProject.AAKERSKIFTE);
            helper.assertTrue(progressed || finished,
                "a scholar standing in a study with an active project must eventually "
                    + "advance it (activity=" + scholar.getActivity() + ")");
        });
    }

    // ------------------------------------------------------------- (c) ---

    /** A finished project's bonus is readable, a project untouched stays
     *  neutral (FLOWS.md: multiply, never gate), and the record survives a
     *  save/load round trip through its own NBT — {@code Research} is never
     *  consulted for this, only the plain data {@code ResearchState} is,
     *  the exact idiom {@code SagaGameTests} uses for {@code Settlement}. */
    @GameTest(batch = "research", template = "empty16", timeoutTicks = 900)
    public void completionExposesTheBonusAndSurvivesReload(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lab = study(helper, s, 4, 4);
        Container chest = chestAt(helper, 5, 4);
        put(chest, Items.PAPER, 4);
        put(chest, Items.WHEAT_SEEDS, 16);
        Research.start(helper.getLevel(), s, lab, ResearchProject.AAKERSKIFTE);
        SettlerEntity scholar = settler(helper, s, "Gro", 4, 4);
        Employment.hire(helper.getLevel(), s, lab, scholar);
        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            ResearchState state = Research.of(helper.getLevel(), s.id);
            helper.assertTrue(state.completed.contains(ResearchProject.AAKERSKIFTE),
                "Åkerskifte must eventually finish (sessions="
                    + (state.active == null ? "done" : state.active.sessions) + ")");

            float grown = Research.bonus(helper.getLevel(), s.id, ResearchKey.FARM_GROWTH);
            helper.assertTrue(grown == ResearchProject.AAKERSKIFTE.bonus(),
                "the finished project's own multiplier must be readable, got " + grown);
            float untouched = Research.bonus(helper.getLevel(), s.id, ResearchKey.SMELTER_TICKS);
            helper.assertTrue(untouched == 1.0F,
                "a key nothing finished touched must stay neutral (FLOWS.md), got " + untouched);

            ResearchState reloaded = ResearchState.readNbt(state.writeNbt());
            helper.assertTrue(reloaded.completed.contains(ResearchProject.AAKERSKIFTE),
                "completion must survive a save/load round trip");
            helper.assertTrue(reloaded.active == null,
                "and there must be nothing active left over");
        });
    }

    // ------------------------------------------------------------- (d) ---

    /** Cancelling refunds exactly the documented half of the domain sample
     *  — never the paper, never the whole amount. */
    @GameTest(batch = "research", template = "empty16", timeoutTicks = 200)
    public void cancellingRefundsHalfTheDomainSampleOnly(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building lab = study(helper, s, 4, 4);
        Container chest = chestAt(helper, 5, 4);
        put(chest, Items.PAPER, 4);
        put(chest, Items.RAW_IRON, 12);
        Research.start(helper.getLevel(), s, lab, ResearchProject.BLESTRING);
        helper.assertTrue(countIn(chest, Items.RAW_IRON) == 0, "the start must have taken it all");

        Research.cancel(helper.getLevel(), s, lab);

        helper.assertTrue(countIn(chest, Items.RAW_IRON) == 6,
            "cancelling must refund exactly half of the 12 raw iron, found "
                + countIn(chest, Items.RAW_IRON));
        helper.assertTrue(countIn(chest, Items.PAPER) == 0,
            "the paper -- the write-up -- is never refunded, found "
                + countIn(chest, Items.PAPER));
        helper.assertTrue(Research.of(helper.getLevel(), s.id).active == null,
            "a cancelled project is no longer active");
        helper.succeed();
    }
}

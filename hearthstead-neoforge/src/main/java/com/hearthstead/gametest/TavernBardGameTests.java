package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.ai.TavernVisitGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernBard;
import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Tavern evening bard: selection from residents already seated, and the once-a-day meal lift. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernBardGameTests {
    private record Fixture(Settlement village, Building tavern, SettlerEntity host,
                           SettlerEntity first, SettlerEntity second) {}

    /** Two seated residents: the lower UUID plays; a Guard inside and the Innkeeper never do. */
    @GameTest(batch = "tavern_bard", template = "empty16", timeoutTicks = 200)
    public void seatedResidentBecomesSingleBardAndMartialNever(GameTestHelper h) {
        Fixture f = fixture(h);
        SettlerEntity guard = resident(h, f.village(), new BlockPos(8, 1, 10));
        f.village().putRecord(guard.getUUID(), "Guard", Profession.GUARD);
        guard.setProfessionProjection(Profession.GUARD);
        guard.setNoAi(true);
        seat(h, f, f.first());
        seat(h, f, f.second());
        TavernBard.resetForTests();

        SettlerEntity bard = TavernBard.bard(h.getLevel(), f.village(), f.tavern());
        SettlerEntity expected = f.first().getUUID().compareTo(f.second().getUUID()) < 0 ? f.first() : f.second();
        SettlerEntity listener = expected == f.first() ? f.second() : f.first();
        h.assertTrue(bard == expected, "the lowest-UUID seated resident is the one evening bard");
        h.assertTrue(TavernBard.isBard(expected) && !TavernBard.isBard(listener),
            "exactly one bard per Tavern");
        h.assertTrue(f.tavern().contains(guard.blockPosition())
                && !TavernBard.eligible(guard, f.village(), f.tavern()),
            "a martial resident inside the Tavern is never a bard");
        h.assertTrue(!TavernBard.eligible(f.host(), f.village(), f.tavern()),
            "the Innkeeper is never the bard");
        h.assertTrue(TavernBard.bard(h.getLevel(), f.village(), f.tavern()) == expected,
            "the incumbent keeps playing while eligible");

        TavernVisitGoal visit = new TavernVisitGoal(expected);
        h.assertTrue(visit.canUse(), "the seated bard keeps its ordinary visit goal");
        visit.start();
        h.succeedWhen(() -> {
            visit.tick();
            h.assertTrue(expected.getActivity() == SettlerActivity.PLAYING_MUSIC,
                "the seated visit presents PLAYING_MUSIC for the bard");
            h.assertTrue(listener.getActivity() != SettlerActivity.PLAYING_MUSIC,
                "the other guest is not playing");
            h.getLevel().setDayTime(Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L + 24000L + 2000L);
            h.assertTrue(TavernBard.bard(h.getLevel(), f.village(), f.tavern()) == null,
                "the performance ends when the evening window closes");
        });
    }

    /** A real completed seated meal next to a bard lifts morale once; a second meal that day does not. */
    @GameTest(batch = "tavern_bard", template = "empty16", timeoutTicks = 400)
    public void completedTavernMealWithBardLiftsMoraleOncePerDay(GameTestHelper h) {
        Fixture f = fixture(h);
        seat(h, f, f.first());
        seat(h, f, f.second());
        TavernBard.resetForTests();
        // The diner holds a meal first, so it cannot be selected; the other resident plays.
        SettlerEntity diner = f.first();
        SettlerEntity musician = f.second();
        diner.setHunger(60);
        h.assertTrue(diner.beginMeal(new ItemStack(Items.BREAD, 2)), "real meal ownership begins");
        h.assertTrue(TavernBard.bard(h.getLevel(), f.village(), f.tavern()) == musician,
            "the resident without a meal is the bard");
        h.assertTrue(!diner.getPersistentData().contains(TavernBard.LIFT_DAY_KEY),
            "a fresh resident has no lift day (old-save default)");
        long day = Math.floorDiv(h.getLevel().getDayTime(), 24000L);

        // The visit goal calls this exact hook when a seated Tavern meal finishes.
        // Exercise it synchronously so the shared test level never stays in the
        // evening while unrelated worker tests run.
        float moraleBefore = diner.getMorale();
        boolean first = TavernBard.onRefreshmentCompleted(diner, f.tavern().id);
        boolean second = TavernBard.onRefreshmentCompleted(diner, f.tavern().id);
        boolean self = TavernBard.onRefreshmentCompleted(musician, f.tavern().id);
        h.getLevel().setDayTime(Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L + 24000L + 2000L);
        h.assertTrue(first, "a completed Tavern meal with a bard lifts morale");
        h.assertTrue(diner.getPersistentData().getLong(TavernBard.LIFT_DAY_KEY) == day,
            "the lift records today's day");
        h.assertTrue(diner.getMorale() > moraleBefore, "morale rose");
        h.assertTrue(!second, "a further completion the same day grants nothing");
        h.assertTrue(!self, "the bard does not lift itself");
        h.succeed();
    }

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L + 11500L);
        Settlement s = new Settlement(UUID.randomUUID(), "Bard fixture", h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(12, 4, 12))));
        // Two real stair chairs, each facing its own fence-and-plate table.
        for (int x : new int[]{6, 9}) {
            h.setBlock(new BlockPos(x, 1, 7), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            h.setBlock(new BlockPos(x, 1, 6), Blocks.OAK_FENCE);
            h.setBlock(new BlockPos(x, 2, 6), Blocks.OAK_PRESSURE_PLATE);
        }
        SettlerEntity host = resident(h, s, new BlockPos(3, 1, 4));
        h.assertTrue(Employment.hire(h.getLevel(), s, tavern, host).ok(), "real fixture Innkeeper employment must succeed");
        host.setNoAi(true);
        SettlerEntity first = resident(h, s, new BlockPos(10, 1, 10));
        SettlerEntity second = resident(h, s, new BlockPos(4, 1, 10));
        first.setNoAi(true); second.setNoAi(true);
        return new Fixture(s, tavern, host, first, second);
    }

    private static SettlerEntity resident(GameTestHelper h, Settlement s, BlockPos p) {
        SettlerEntity e = h.spawn(ModEntities.SETTLER.get(), p);
        Vec3 spawnPosition = e.position();
        e.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.0, -0.05, 0.0));
        h.assertTrue(e.onGround() && e.position().equals(spawnPosition),
            "fixture resident must contact its real floor without displacement");
        e.bindTo(s.id, s.center); s.putRecord(e.getUUID(), "Diner", Profession.NONE);
        e.setHunger(100); e.setEnergy(100);
        return e;
    }

    /** Real reservation, then mount from the reserved aisle (same contact rule as TavernSeatingGameTests). */
    private static TavernSeatEntity seat(GameTestHelper h, Fixture f, SettlerEntity guest) {
        var result = TavernSeating.reserveReachable(guest);
        h.assertTrue(result.seat() != null, "a real reachable chair reservation is required");
        TavernSeatEntity seat = result.seat();
        guest.setPos(Vec3.atBottomCenterOf(seat.site().aisle()));
        h.assertTrue(seat.mount(guest), "contact at the reserved aisle must mount");
        h.assertTrue(TavernSeating.currentTavern(guest) == f.tavern(), "seat keeps exact Tavern identity");
        return seat;
    }
}

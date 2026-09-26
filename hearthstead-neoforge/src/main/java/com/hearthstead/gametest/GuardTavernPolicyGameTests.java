package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Real actor policy checks; no movement, food or combat behavior is simulated. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class GuardTavernPolicyGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40, batch = "guard_tavern_policy")
    public void bothMartialWatchesRejectTavernInEveryPhaseWithoutChangingShifts(GameTestHelper h) {
        Settlement s = new Settlement(UUID.randomUUID(), "Martial policy", h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        Building tavern = GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN,
            new BlockPos(12, 1, 12), new BlockPos(11, 2, 11),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(10, 1, 10)), h.absolutePos(new BlockPos(14, 4, 14))));
        long oldTime = h.getLevel().getDayTime();
        long[] times = {0, 2000, 6000, 8000, 12000, 18000};
        try {
            for (Profession role : new Profession[]{Profession.GUARD, Profession.ARCHER}) {
                Building post = GameTestFixtures.registerWithBounds(h, s,
                    role == Profession.GUARD ? BuildingType.BARRACKS : BuildingType.WATCHTOWER,
                    new BlockPos(role == Profession.GUARD ? 3 : 7, 1, 3),
                    new BlockPos(role == Profession.GUARD ? 3 : 7, 2, 4),
                    BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(8, 4, 5))));
                for (int slot = 0; slot < 2; slot++) {
                    SettlerEntity actor = h.spawn(ModEntities.SETTLER.get(), new BlockPos(4 + slot, 1, 7));
                    actor.bindTo(s.id, s.center);
                    actor.setProfessionProjection(role);
                    s.putRecord(actor.getUUID(), role.name(), role);
                    // Populate the persisted employer slots used by watchOf;
                    // recruitment and equipment are independent of this phase policy.
                    post.workers.add(actor.getUUID());
                    actor.setNoAi(true);
                    boolean night = slot == 1;
                    h.assertTrue(Employment.watchOf(s, actor) == (night ? Employment.Watch.NIGHT : Employment.Watch.DAY),
                        "real martial actor must resolve its exact employer watch slot");
                    for (long time : times) {
                        h.getLevel().setDayTime(time);
                        DayPhase phase = DayPhase.of(time);
                        boolean expectedWatch = night
                            ? phase == DayPhase.REST || phase == DayPhase.EVENING || phase == DayPhase.RISE
                            : phase.work() || phase.meal() || phase == DayPhase.EVENING;
                        boolean expectedSleep = night
                            ? phase.work() || phase.meal() : phase.rest();
                        h.assertTrue(!TavernSeating.mayVisit(actor), role + " must never visit in " + phase);
                        h.assertTrue(Schedule.postFor(s, actor, phase) == null,
                            role + " must not receive generic Tavern gathering in " + phase);
                        h.assertTrue(Schedule.onWatch(s, actor, phase) == expectedWatch
                                && Schedule.shouldWork(s, actor, phase) == expectedWatch
                                && Schedule.shouldSleep(s, actor, phase) == expectedSleep,
                            role + " must retain its existing watch and sleep clock in " + phase);
                    }
                }
            }
            SettlerEntity civilian = h.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 7));
            civilian.bindTo(s.id, s.center);
            s.putRecord(civilian.getUUID(), "Civilian control", Profession.NONE);
            h.getLevel().setDayTime(12000);
            h.assertTrue(TavernSeating.mayVisit(civilian), "healthy civilian keeps the evening visit policy");
            h.assertTrue(Schedule.postFor(s, civilian, DayPhase.RISE).where().equals(tavern.anchor),
                "civilian gathering is unchanged by the martial exclusion");
            h.succeed();
        } finally {
            h.getLevel().setDayTime(oldTime);
        }
    }
}
